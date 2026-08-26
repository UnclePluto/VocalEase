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

class AndroidTokenVault(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileStore: VaultFileStore = AtomicVaultFileStore(context.applicationContext),
) : TokenVault {
    private val session = AtomicReference(SessionSnapshot(accessToken = null, epoch = 0))
    private val storageMutex = Mutex()

    override fun sessionSnapshot(): SessionSnapshot = session.get()

    override suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead = serialized {
        val current = session.get()
        if (current.epoch != expectedEpoch || !fileStore.exists()) {
            return@serialized RefreshTokenRead.Missing(current.epoch)
        }
        try {
            val token = decrypt(fileStore.readFully())
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
                fileStore.writeAtomically(encrypt(refreshToken))
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
        session.set(cleared)
        try {
            fileStore.delete()
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

    private fun encrypt(refreshToken: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
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

    private fun decrypt(payload: ByteArray): String = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        require(input.readInt() == FILE_VERSION) { "不支持的 token 文件版本" }
        val ivLength = input.readInt()
        val ciphertextLength = input.readInt()
        require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES) { "非法 IV 长度" }
        require(ciphertextLength in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) { "非法密文长度" }
        require(payload.size == HEADER_BYTES + ivLength + ciphertextLength) { "token 文件长度不匹配" }
        val iv = ByteArray(ivLength).also(input::readFully)
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
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
        const val FILE_VERSION = 1

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val HEADER_BYTES = 12
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 32
        private const val MIN_CIPHERTEXT_BYTES = 17
        private const val MAX_CIPHERTEXT_BYTES = 64 * 1024
    }
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
