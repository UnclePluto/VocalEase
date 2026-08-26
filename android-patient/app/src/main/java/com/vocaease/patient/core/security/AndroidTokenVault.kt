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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidTokenVault(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TokenVault {
    private val applicationContext = context.applicationContext
    private val encryptedFile = AtomicFile(applicationContext.getFileStreamPath(FILE_NAME))
    private val access = AtomicReference<String?>(null)
    private val generation = AtomicLong(0)

    override fun accessSnapshot(): AccessTokenSnapshot = AccessTokenSnapshot(
        value = access.get(),
        generation = generation.get(),
    )

    override suspend fun readRefreshToken(): String? = withContext(ioDispatcher) {
        if (!encryptedFile.baseFile.exists()) return@withContext null
        try {
            decrypt(encryptedFile.readFully()).also { token ->
                require(token.isNotBlank()) { "refresh token 为空" }
            }
        } catch (error: Exception) {
            invalidateLocalSession()
            null
        }
    }

    override suspend fun replaceTokens(accessToken: String, refreshToken: String) {
        require(accessToken.isNotBlank()) { "access token 不能为空" }
        require(refreshToken.isNotBlank()) { "refresh token 不能为空" }
        withContext(ioDispatcher) {
            val encrypted = encrypt(refreshToken)
            writeAtomically(encrypted)
            access.set(accessToken)
            generation.incrementAndGet()
        }
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            invalidateLocalSession()
        }
    }

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

    private fun writeAtomically(payload: ByteArray) {
        val output = encryptedFile.startWrite()
        try {
            output.write(payload)
            output.fd.sync()
            encryptedFile.finishWrite(output)
            Os.chmod(encryptedFile.baseFile.absolutePath, PRIVATE_FILE_MODE)
        } catch (error: Exception) {
            encryptedFile.failWrite(output)
            throw error
        }
    }

    private fun invalidateLocalSession() {
        encryptedFile.delete()
        access.set(null)
        generation.incrementAndGet()
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
        private const val PRIVATE_FILE_MODE = 384 // 0600
    }
}
