package com.vocaease.patient.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.SecureRandom
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

internal interface RevocationVaultFileStore {
    fun exists(): Boolean
    fun readFully(): ByteArray
    fun writeAtomically(payload: ByteArray)
    fun delete()
}

internal class AndroidRevocationVault(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileStore: RevocationVaultFileStore = AtomicRevocationVaultFileStore(context.applicationContext),
) : RevocationTokenSink, RevocationTokenSource {
    private val mutex = Mutex()

    override suspend fun store(accessToken: String, refreshToken: String): RevocationHandle {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank())
        require(accessToken.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN_BYTES)
        require(refreshToken.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN_BYTES)
        return serialized {
            val records = readRecords()
            check(records.size < MAX_RECORDS) { "待撤销凭据过多" }
            var handle: RevocationHandle
            do {
                handle = RevocationHandle(ByteArray(SLOT_BYTES).also(SecureRandom()::nextBytes).toHex())
            } while (records.containsKey(handle.slotId))
            records[handle.slotId] = RevocationCredential(accessToken, refreshToken)
            fileStore.writeAtomically(encrypt(encodeRecords(records)))
            handle
        }
    }

    override suspend fun lease(handle: RevocationHandle): RevocationTokenLease? = serialized {
        readRecords()[handle.slotId]?.let { RevocationTokenLease(handle, it.accessToken, it.refreshToken) }
    }

    override suspend fun remove(handle: RevocationHandle) = serialized {
        val records = readRecords()
        if (records.remove(handle.slotId) != null) {
            if (records.isEmpty()) fileStore.delete()
            else fileStore.writeAtomically(encrypt(encodeRecords(records)))
        }
    }

    override suspend fun handles(): List<RevocationHandle> = serialized {
        readRecords().keys.map(::RevocationHandle)
    }

    private suspend fun <T> serialized(block: () -> T): T =
        withContext(ioDispatcher + NonCancellable) { mutex.withLock { block() } }

    private fun readRecords(): LinkedHashMap<String, RevocationCredential> =
        if (!fileStore.exists()) linkedMapOf()
        else decodeRecords(decrypt(fileStore.readFully()))

    private fun encodeRecords(records: Map<String, RevocationCredential>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(RECORDS_VERSION)
                output.writeInt(records.size)
                records.forEach { (slotId, credential) ->
                    output.writeUTF(slotId)
                    listOf(credential.accessToken, credential.refreshToken).forEach { token ->
                        val tokenBytes = token.toByteArray(Charsets.UTF_8)
                        output.writeInt(tokenBytes.size)
                        output.write(tokenBytes)
                    }
                }
            }
            bytes.toByteArray()
        }

    private fun decodeRecords(payload: ByteArray): LinkedHashMap<String, RevocationCredential> =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == RECORDS_VERSION) { "不支持的撤销记录版本" }
            val count = input.readInt()
            require(count in 0..MAX_RECORDS) { "非法撤销记录数量" }
            linkedMapOf<String, RevocationCredential>().also { records ->
                repeat(count) {
                    val handle = RevocationHandle(input.readUTF())
                    fun readToken(): String {
                        val tokenLength = input.readInt()
                        require(tokenLength in 1..MAX_TOKEN_BYTES) { "非法撤销凭据长度" }
                        return ByteArray(tokenLength).also(input::readFully).toString(Charsets.UTF_8)
                            .also { require(it.isNotBlank()) }
                    }
                    val credential = RevocationCredential(readToken(), readToken())
                    require(records.put(handle.slotId, credential) == null)
                }
                require(input.available() == 0) { "撤销记录文件存在尾随数据" }
            }
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

    private fun decrypt(payload: ByteArray): ByteArray =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == FILE_VERSION) { "不支持的撤销保险库版本" }
            val ivLength = input.readInt()
            val ciphertextLength = input.readInt()
            require(ivLength in 12..32 && ciphertextLength in 17..MAX_FILE_BYTES)
            require(payload.size == HEADER_BYTES + ivLength + ciphertextLength)
            val iv = ByteArray(ivLength).also(input::readFully)
            val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ciphertext)
            }
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val KEY_ALIAS = "vocaease.revocation.v1"
        const val FILE_NAME = "vocaease-revocation-only.vault"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FILE_VERSION = 1
        private const val RECORDS_VERSION = 1
        private const val SLOT_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val HEADER_BYTES = 12
        private const val MAX_RECORDS = 128
        private const val MAX_TOKEN_BYTES = 64 * 1024
        private const val MAX_FILE_BYTES = 9 * 1024 * 1024
    }
}

private data class RevocationCredential(
    val accessToken: String,
    val refreshToken: String,
)

private class AtomicRevocationVaultFileStore(context: Context) : RevocationVaultFileStore {
    private val atomicFile = AtomicFile(context.getFileStreamPath(AndroidRevocationVault.FILE_NAME))

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

    override fun delete() = atomicFile.delete()

    private companion object {
        const val PRIVATE_FILE_MODE = 384 // 0600
    }
}
