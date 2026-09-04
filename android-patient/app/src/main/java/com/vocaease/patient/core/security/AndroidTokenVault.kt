package com.vocaease.patient.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface VaultFileStore {
    fun exists(): Boolean
    fun readFully(): ByteArray
    fun writeAtomically(payload: ByteArray)
    fun delete()
}

internal class AndroidTokenVault(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileStore: VaultFileStore = AtomicVaultFileStore(context.applicationContext),
) : TokenVault, DurableLogoutTokenVault {
    private val session = AtomicReference(SessionSnapshot(accessToken = null, epoch = 0))
    private val storageMutex = Mutex()

    override fun sessionSnapshot(): SessionSnapshot = session.get()

    override suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead = serialized {
        val current = session.get()
        if (current.epoch != expectedEpoch || !fileStore.exists()) {
            return@serialized RefreshTokenRead.Missing(current.epoch)
        }
        try {
            val active = readState() as? VaultState.Active
                ?: return@serialized RefreshTokenRead.Missing(current.epoch)
            if (active.boundOperationId != null) return@serialized RefreshTokenRead.Missing(current.epoch)
            val token = active.refreshToken
            require(token.isNotBlank()) { "refresh token 为空" }
            RefreshTokenRead.Available(RefreshTokenLease(token, current.epoch))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            RefreshTokenRead.Invalidated(invalidateLocked(current), error)
        }
    }

    override suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        replacementId: String,
    ): SessionMutation {
        require(accessToken.isNotBlank()) { "access token 不能为空" }
        require(refreshToken.isNotBlank()) { "refresh token 不能为空" }
        return serialized {
            val current = session.get()
            if (current.epoch != expectedEpoch) {
                return@serialized SessionMutation(applied = false, current)
            }
            try {
                val stored = readStateOrNull()
                if (stored is VaultState.RevocationPending ||
                    (stored as? VaultState.Active)?.boundOperationId != null
                ) {
                    return@serialized SessionMutation(applied = false, current)
                }
                writeState(VaultState.Active(refreshToken, boundOperationId = null, persistedAccessToken = accessToken))
                val updated = SessionSnapshot(accessToken, current.epoch + 1, replacementId)
                session.set(updated)
                SessionMutation(applied = true, updated)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val invalidation = invalidateLocked(current)
                if (error.isExpectedVaultFailure()) {
                    throw VaultInvalidatedException(invalidation, error)
                }
                throw error
            }
        }
    }

    override suspend fun clear(expectedEpoch: Long?): SessionMutation = serialized {
        val current = session.get()
        if (expectedEpoch != null && current.epoch != expectedEpoch) {
            return@serialized SessionMutation(applied = false, current)
        }
        val cleared = SessionSnapshot(accessToken = null, epoch = current.epoch + 1, replacementId = null)
        try {
            check(readStateOrNull() !is VaultState.RevocationPending) { "待撤销凭据不得被普通清理删除" }
            fileStore.delete()
            session.set(cleared)
            SessionMutation(applied = true, cleared)
        } catch (error: Exception) {
            if (error.isExpectedVaultFailure()) {
                throw VaultInvalidatedException(
                    SessionInvalidation(current.epoch, cleared.epoch),
                    error,
                )
            }
            throw error
        }
    }

    override suspend fun bindLogoutOperation(
        expectedEpoch: Long,
        operationId: String,
    ): BoundLogoutCredential? = serialized {
        requireOperationId(operationId)
        val current = session.get()
        val active = readStateOrNull() as? VaultState.Active ?: return@serialized null
        if (active.boundOperationId != null && active.boundOperationId != operationId) return@serialized null
        if (active.boundOperationId == null && current.accessToken != null && current.epoch != expectedEpoch) {
            return@serialized null
        }
        val access = current.accessToken ?: active.persistedAccessToken ?: return@serialized null
        if (active.boundOperationId == null) {
            writeState(active.copy(boundOperationId = operationId, persistedAccessToken = access))
        }
        BoundLogoutCredential(operationId, access, active.refreshToken, current.epoch)
    }

    override suspend fun boundLogoutOperation(
        expectedEpoch: Long,
        operationId: String,
    ): BoundLogoutCredential? = serialized {
        requireOperationId(operationId)
        val current = session.get()
        val active = readStateOrNull() as? VaultState.Active ?: return@serialized null
        if (active.boundOperationId != operationId) return@serialized null
        val access = current.accessToken ?: active.persistedAccessToken ?: return@serialized null
        BoundLogoutCredential(operationId, access, active.refreshToken, current.epoch)
    }

    override suspend fun clearBoundLogout(expectedEpoch: Long, operationId: String): SessionMutation = serialized {
        requireOperationId(operationId)
        val current = session.get()
        val active = readStateOrNull() as? VaultState.Active
        if (active?.boundOperationId != operationId) {
            return@serialized SessionMutation(false, current)
        }
        fileStore.delete()
        val cleared = SessionSnapshot(null, current.epoch + 1, null)
        session.set(cleared)
        SessionMutation(true, cleared)
    }

    override suspend fun moveBoundLogoutToRevocation(
        expectedEpoch: Long,
        operationId: String,
        handle: RevocationHandle,
    ): SessionMutation = serialized {
        requireOperationId(operationId)
        val current = session.get()
        val active = readStateOrNull() as? VaultState.Active
        val access = current.accessToken ?: active?.persistedAccessToken
        if (active?.boundOperationId != operationId || access == null) {
            return@serialized SessionMutation(false, current)
        }
        writeState(VaultState.RevocationPending(operationId, handle, access, active.refreshToken))
        val cleared = SessionSnapshot(null, current.epoch + 1, null)
        session.set(cleared)
        SessionMutation(true, cleared)
    }

    override suspend fun pendingRevocationTransfer(): PendingRevocationTransfer? = serialized {
        val current = session.get()
        try {
            val pending = readStateOrNull() as? VaultState.RevocationPending ?: return@serialized null
            PendingRevocationTransfer(
                pending.operationId,
                pending.handle,
                pending.accessToken,
                pending.refreshToken,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw VaultInvalidatedException(invalidateLocked(current), error)
        }
    }

    override suspend fun completePendingRevocationTransfer(operationId: String, handle: RevocationHandle) = serialized {
        requireOperationId(operationId)
        val pending = readStateOrNull() as? VaultState.RevocationPending ?: return@serialized
        check(pending.operationId == operationId && pending.handle == handle) { "待撤销移交所有权不匹配" }
        fileStore.delete()
    }

    private suspend fun <T> serialized(block: () -> T): T =
        withContext(ioDispatcher + NonCancellable) {
            storageMutex.withLock { block() }
        }

    private fun invalidateLocked(current: SessionSnapshot): SessionInvalidation {
        val invalidation = SessionInvalidation(current.epoch, current.epoch + 1)
        session.set(SessionSnapshot(accessToken = null, epoch = invalidation.toEpoch, replacementId = null))
        runCatching { fileStore.delete() }
        return invalidation
    }

    private fun Throwable.isExpectedVaultFailure(): Boolean =
        this is IOException ||
            this is GeneralSecurityException ||
            this is ProviderException ||
            this is ErrnoException

    private fun writeState(state: VaultState) {
        fileStore.writeAtomically(encrypt(encodeState(state)))
    }

    private fun readStateOrNull(): VaultState? = if (fileStore.exists()) readState() else null

    private fun readState(): VaultState {
        val (version, plaintext) = decrypt(fileStore.readFully())
        return if (version == LEGACY_FILE_VERSION) {
            VaultState.Active(plaintext.toString(Charsets.UTF_8), null, null)
        } else {
            decodeState(plaintext)
        }
    }

    private fun encodeState(state: VaultState): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(STATE_VERSION)
            when (state) {
                is VaultState.Active -> {
                    output.writeByte(ACTIVE_STATE)
                    output.writeUTF(state.boundOperationId.orEmpty())
                    output.writeSizedOrEmpty(state.persistedAccessToken)
                    output.writeSized(state.refreshToken)
                }
                is VaultState.RevocationPending -> {
                    output.writeByte(REVOCATION_PENDING_STATE)
                    output.writeUTF(state.operationId)
                    output.writeUTF(state.handle.slotId)
                    output.writeSized(state.accessToken)
                    output.writeSized(state.refreshToken)
                }
            }
        }
        bytes.toByteArray()
    }

    private fun decodeState(plaintext: ByteArray): VaultState = DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
        require(input.readInt() == STATE_VERSION) { "不支持的会话状态版本" }
        val state = when (input.readUnsignedByte()) {
            ACTIVE_STATE -> {
                val operationId = input.readUTF().ifBlank { null }
                val accessToken = input.readSizedOrNull()
                VaultState.Active(
                    refreshToken = input.readSized(),
                    boundOperationId = operationId,
                    persistedAccessToken = accessToken,
                )
            }
            REVOCATION_PENDING_STATE -> VaultState.RevocationPending(
                operationId = input.readUTF(),
                handle = RevocationHandle(input.readUTF()),
                accessToken = input.readSized(),
                refreshToken = input.readSized(),
            )
            else -> error("非法会话状态")
        }
        require(input.available() == 0) { "会话状态存在尾随数据" }
        state
    }

    private fun DataOutputStream.writeSized(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_TOKEN_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeSizedOrEmpty(value: String?) {
        if (value == null) writeInt(0) else writeSized(value)
    }

    private fun DataInputStream.readSized(): String {
        val size = readInt()
        require(size in 1..MAX_TOKEN_BYTES)
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8).also { require(it.isNotBlank()) }
    }

    private fun DataInputStream.readSizedOrNull(): String? {
        val size = readInt()
        if (size == 0) return null
        require(size in 1..MAX_TOKEN_BYTES)
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8).also { require(it.isNotBlank()) }
    }

    private fun requireOperationId(operationId: String) {
        require(operationId.matches(OPERATION_ID))
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FILE_VERSION)
                output.writeInt(cipher.iv.size)
                output.writeInt(ciphertext.size)
                output.write(cipher.iv)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }

    private fun decrypt(payload: ByteArray): Pair<Int, ByteArray> = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        val version = input.readInt()
        require(version == FILE_VERSION || version == LEGACY_FILE_VERSION) { "不支持的 token 文件版本" }
        val ivLength = input.readInt()
        val ciphertextLength = input.readInt()
        require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES) { "非法 IV 长度" }
        require(ciphertextLength in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) { "非法密文长度" }
        require(payload.size == HEADER_BYTES + ivLength + ciphertextLength) { "token 文件长度不匹配" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        version to cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val KEY_ALIAS = "vocaease.refresh.v1"
        const val FILE_NAME = "vocaease-refresh-token.vault"
        const val FILE_VERSION = 2

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val HEADER_BYTES = 12
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 32
        private const val MIN_CIPHERTEXT_BYTES = 17
        private const val MAX_CIPHERTEXT_BYTES = 128 * 1024
        private const val MAX_TOKEN_BYTES = 48 * 1024
        private const val LEGACY_FILE_VERSION = 1
        private const val STATE_VERSION = 1
        private const val ACTIVE_STATE = 1
        private const val REVOCATION_PENDING_STATE = 2
        private val OPERATION_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

private sealed interface VaultState {
    data class Active(
        val refreshToken: String,
        val boundOperationId: String?,
        val persistedAccessToken: String?,
    ) : VaultState
    data class RevocationPending(
        val operationId: String,
        val handle: RevocationHandle,
        val accessToken: String,
        val refreshToken: String,
    ) : VaultState
}

private class AtomicVaultFileStore(context: Context) : VaultFileStore {
    private val atomicFile = AtomicFile(context.getFileStreamPath(AndroidTokenVault.FILE_NAME))

    override fun exists(): Boolean = atomicFile.baseFile.exists()
    override fun readFully(): ByteArray = atomicFile.readFully()

    override fun writeAtomically(payload: ByteArray) {
        val output = atomicFile.startWrite()
        var finished = false
        try {
            output.write(payload)
            output.fd.sync()
            atomicFile.finishWrite(output)
            finished = true
            Os.chmod(atomicFile.baseFile.absolutePath, PRIVATE_FILE_MODE)
        } catch (error: Exception) {
            if (finished) atomicFile.delete() else atomicFile.failWrite(output)
            throw error
        }
    }

    override fun delete() {
        atomicFile.delete()
    }

    private companion object {
        const val PRIVATE_FILE_MODE = 384 // 0600
    }
}
