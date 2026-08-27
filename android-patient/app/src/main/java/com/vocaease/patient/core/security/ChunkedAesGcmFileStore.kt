package com.vocaease.patient.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedMediaException internal constructor(message: String) : java.io.IOException(message)

/**
 * VEF1 media store. The account master key itself lives in AndroidKeyStore and is not exportable.
 * Only SHA-256(accountScope), never the account identifier, is used in the key alias.
 */
class ChunkedAesGcmFileStore(
    context: Context,
    private val rootDirectory: File = File(context.filesDir, "encrypted_media"),
) {
    private val secureRandom = SecureRandom()

    fun encrypt(
        accountScope: String,
        encryptedRelativePath: String,
        plaintext: InputStream,
        originalLength: Long,
    ): File {
        if (accountScope.isBlank() || originalLength <= 0 || originalLength > MAX_ORIGINAL_LENGTH) {
            throw EncryptedMediaException(WRITE_ERROR)
        }
        val destination = resolve(encryptedRelativePath, WRITE_ERROR)
        val parent = destination.parentFile ?: throw EncryptedMediaException(WRITE_ERROR)
        val temporary = synchronized(FILE_PREPARATION_LOCK) {
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw EncryptedMediaException(WRITE_ERROR)
            }
            makeOwnerOnly(parent, directory = true)
            try {
                File.createTempFile(".vef-", ".tmp", parent).also { makeOwnerOnly(it, directory = false) }
            } catch (_: Exception) {
                throw EncryptedMediaException(WRITE_ERROR)
            }
        }

        try {
            val key = accountKey(accountScope, createIfMissing = true)
            val chunkCount = chunkCountFor(originalLength)
            val fileId = ByteArray(FILE_ID_LENGTH).also(secureRandom::nextBytes)
            val header = buildHeader(originalLength, chunkCount, fileId)
            FileOutput(temporary).use { output ->
                output.write(header)
                var remaining = originalLength
                repeat(chunkCount) { chunkIndex ->
                    val plainLength = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
                    val plain = ByteArray(plainLength)
                    try {
                        readExact(plaintext, plain)
                        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                        cipher.init(Cipher.ENCRYPT_MODE, key)
                        val nonce = cipher.iv
                        if (nonce.size != NONCE_LENGTH) throw EncryptedMediaException(WRITE_ERROR)
                        cipher.updateAAD(chunkAad(header, chunkIndex, plainLength))
                        val encrypted = cipher.doFinal(plain)
                        output.writeInt(plainLength)
                        output.write(nonce)
                        output.write(encrypted)
                    } finally {
                        plain.fill(0)
                    }
                    remaining -= plainLength
                }
                if (remaining != 0L || plaintext.read() != -1) throw EncryptedMediaException(WRITE_ERROR)
                output.sync()
            }
            moveIntoPlace(temporary, destination)
            makeOwnerOnly(destination, directory = false)
            return destination
        } catch (error: EncryptedMediaException) {
            temporary.delete()
            throw error
        } catch (_: Exception) {
            temporary.delete()
            throw EncryptedMediaException(WRITE_ERROR)
        }
    }

    fun open(accountScope: String, encryptedRelativePath: String): EncryptedFileReader {
        if (accountScope.isBlank()) throw EncryptedMediaException(OPEN_ERROR)
        val source = resolve(encryptedRelativePath, OPEN_ERROR)
        var randomAccessFile: RandomAccessFile? = null
        try {
            randomAccessFile = RandomAccessFile(source, "r")
            val header = ByteArray(HEADER_SIZE)
            randomAccessFile.readFully(header)
            val parsed = parseAndValidateHeader(header, randomAccessFile.length())
            validateUniqueNonces(randomAccessFile, parsed.chunkCount)
            val key = accountKey(accountScope, createIfMissing = false)
            return EncryptedFileReader(randomAccessFile, key, header, parsed.originalLength, parsed.chunkCount)
        } catch (error: EncryptedMediaException) {
            randomAccessFile?.closeQuietly()
            throw error
        } catch (_: Exception) {
            randomAccessFile?.closeQuietly()
            throw EncryptedMediaException(OPEN_ERROR)
        }
    }

    private fun resolve(relativePath: String, safeError: String): File {
        if (!safeRelativePath(relativePath)) throw EncryptedMediaException(safeError)
        try {
            val canonicalRoot = rootDirectory.canonicalFile
            val candidate = File(canonicalRoot, relativePath).canonicalFile
            if (candidate == canonicalRoot || !candidate.path.startsWith(canonicalRoot.path + File.separator)) {
                throw EncryptedMediaException(safeError)
            }
            return candidate
        } catch (error: EncryptedMediaException) {
            throw error
        } catch (_: Exception) {
            throw EncryptedMediaException(safeError)
        }
    }

    private fun accountKey(accountScope: String, createIfMissing: Boolean): SecretKey = synchronized(KEY_CREATION_LOCK) {
        try {
            val alias = KEY_ALIAS_PREFIX + sha256(accountScope)
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
            if (!createIfMissing) throw EncryptedMediaException(OPEN_ERROR)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        } catch (error: EncryptedMediaException) {
            throw error
        } catch (_: Exception) {
            throw EncryptedMediaException(if (createIfMissing) WRITE_ERROR else OPEN_ERROR)
        }
    }

    private fun validateUniqueNonces(file: RandomAccessFile, chunkCount: Int) {
        val seen = HashSet<String>(chunkCount)
        repeat(chunkCount) { index ->
            file.seek(chunkOffset(index) + Int.SIZE_BYTES)
            val nonce = ByteArray(NONCE_LENGTH)
            file.readFully(nonce)
            val fingerprint = nonce.joinToString(separator = "") { "%02x".format(it) }
            if (!seen.add(fingerprint)) throw EncryptedMediaException(OPEN_ERROR)
        }
    }

    private fun moveIntoPlace(temporary: File, destination: File) {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun makeOwnerOnly(file: File, directory: Boolean) {
        Os.chmod(file.absolutePath, if (directory) OWNER_RWX else OWNER_RW)
    }

    private data class ParsedHeader(val originalLength: Long, val chunkCount: Int)

    private fun parseAndValidateHeader(header: ByteArray, actualFileLength: Long): ParsedHeader {
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw EncryptedMediaException(OPEN_ERROR)
        if (buffer.int != CHUNK_SIZE) throw EncryptedMediaException(OPEN_ERROR)
        val originalLength = buffer.long
        val chunkCount = buffer.int
        val fileId = ByteArray(FILE_ID_LENGTH).also(buffer::get)
        if (
            originalLength <= 0 ||
            originalLength > MAX_ORIGINAL_LENGTH ||
            chunkCount <= 0 ||
            chunkCount != chunkCountFor(originalLength) ||
            fileId.all { it == 0.toByte() }
        ) {
            throw EncryptedMediaException(OPEN_ERROR)
        }
        val expectedLength = try {
            Math.addExact(
                HEADER_SIZE.toLong(),
                Math.addExact(originalLength, Math.multiplyExact(chunkCount.toLong(), RECORD_OVERHEAD.toLong())),
            )
        } catch (_: ArithmeticException) {
            throw EncryptedMediaException(OPEN_ERROR)
        }
        if (expectedLength != actualFileLength) throw EncryptedMediaException(OPEN_ERROR)
        return ParsedHeader(originalLength, chunkCount)
    }

    private fun buildHeader(originalLength: Long, chunkCount: Int, fileId: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC)
            .putInt(CHUNK_SIZE)
            .putLong(originalLength)
            .putInt(chunkCount)
            .put(fileId)
            .array()

    private fun chunkCountFor(originalLength: Long): Int =
        ((originalLength + CHUNK_SIZE - 1) / CHUNK_SIZE).also {
            if (it <= 0 || it > Int.MAX_VALUE) throw EncryptedMediaException(OPEN_ERROR)
        }.toInt()

    private fun readExact(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EncryptedMediaException(WRITE_ERROR)
            if (read == 0) {
                val single = input.read()
                if (single < 0) throw EncryptedMediaException(WRITE_ERROR)
                target[offset++] = single.toByte()
            } else {
                offset += read
            }
        }
    }

    private class FileOutput(file: File) : Closeable {
        private val stream = java.io.FileOutputStream(file)
        private val data = DataOutputStream(stream)

        fun write(bytes: ByteArray) = data.write(bytes)
        fun writeInt(value: Int) = data.writeInt(value)
        fun sync() {
            data.flush()
            stream.fd.sync()
        }

        override fun close() = data.close()
    }

    companion object {
        internal const val CHUNK_SIZE = 1024 * 1024
        internal const val HEADER_SIZE = 36
        internal const val RECORD_OVERHEAD = 4 + 12 + 16
        private const val FILE_ID_LENGTH = 16
        private const val NONCE_LENGTH = 12
        private const val TAG_LENGTH = 16
        private const val MAX_ORIGINAL_LENGTH = 32L shl 30
        private const val OWNER_RWX = 0x1c0
        private const val OWNER_RW = 0x180
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "vocaease_media_"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val OPEN_ERROR = "无法读取加密媒体"
        private const val WRITE_ERROR = "无法保存加密媒体"
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'E'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
        private val KEY_CREATION_LOCK = Any()
        private val FILE_PREPARATION_LOCK = Any()

        internal fun chunkAad(header: ByteArray, chunkIndex: Int, plainLength: Int): ByteArray =
            ByteBuffer.allocate(header.size + Int.SIZE_BYTES * 2)
                .put(header)
                .putInt(chunkIndex)
                .putInt(plainLength)
                .array()

        internal fun chunkOffset(chunkIndex: Int): Long =
            HEADER_SIZE.toLong() + chunkIndex.toLong() * (CHUNK_SIZE + RECORD_OVERHEAD).toLong()

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }

        private fun safeRelativePath(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
            return path.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }
        }

        private fun RandomAccessFile.closeQuietly() {
            try {
                close()
            } catch (_: Exception) {
                // The original safe error remains authoritative.
            }
        }
    }
}

class EncryptedFileReader internal constructor(
    private val file: RandomAccessFile,
    private val key: SecretKey,
    private val header: ByteArray,
    val length: Long,
    private val chunkCount: Int,
) : Closeable {
    private var closed = false
    private var cachedChunkIndex = -1
    private var cachedPlaintext: ByteArray? = null

    @Synchronized
    fun read(position: Long, target: ByteArray, offset: Int, requestedLength: Int): Int {
        if (closed) throw EncryptedMediaException(READ_ERROR)
        if (position < 0 || offset < 0 || requestedLength < 0 || offset > target.size - requestedLength) {
            throw EncryptedMediaException(READ_ERROR)
        }
        if (requestedLength == 0) return 0
        if (position >= length) return -1
        var sourcePosition = position
        var destinationOffset = offset
        var remaining = minOf(requestedLength.toLong(), length - position).toInt()
        val total = remaining
        while (remaining > 0) {
            val chunkIndex = (sourcePosition / ChunkedAesGcmFileStore.CHUNK_SIZE).toInt()
            val withinChunk = (sourcePosition % ChunkedAesGcmFileStore.CHUNK_SIZE).toInt()
            val chunk = decryptedChunk(chunkIndex)
            val copied = minOf(remaining, chunk.size - withinChunk)
            chunk.copyInto(target, destinationOffset, withinChunk, withinChunk + copied)
            sourcePosition += copied
            destinationOffset += copied
            remaining -= copied
        }
        return total
    }

    private fun decryptedChunk(chunkIndex: Int): ByteArray {
        cachedPlaintext?.takeIf { cachedChunkIndex == chunkIndex }?.let { return it }
        if (chunkIndex !in 0 until chunkCount) throw EncryptedMediaException(READ_ERROR)
        try {
            file.seek(ChunkedAesGcmFileStore.chunkOffset(chunkIndex))
            val plainLength = file.readInt()
            val expectedPlainLength = if (chunkIndex == chunkCount - 1) {
                (length - chunkIndex.toLong() * ChunkedAesGcmFileStore.CHUNK_SIZE).toInt()
            } else {
                ChunkedAesGcmFileStore.CHUNK_SIZE
            }
            if (plainLength != expectedPlainLength) throw EncryptedMediaException(READ_ERROR)
            val nonce = ByteArray(12)
            file.readFully(nonce)
            val ciphertext = ByteArray(plainLength + 16)
            file.readFully(ciphertext)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(ChunkedAesGcmFileStore.chunkAad(header, chunkIndex, plainLength))
            val plaintext = cipher.doFinal(ciphertext)
            cachedPlaintext?.fill(0)
            cachedChunkIndex = chunkIndex
            cachedPlaintext = plaintext
            return plaintext
        } catch (_: Exception) {
            poison()
            throw EncryptedMediaException(READ_ERROR)
        }
    }

    private fun poison() {
        closed = true
        cachedPlaintext?.fill(0)
        cachedPlaintext = null
        cachedChunkIndex = -1
        try {
            file.close()
        } catch (_: Exception) {
            // Authentication or I/O failure already made the reader unusable.
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cachedPlaintext?.fill(0)
        cachedPlaintext = null
        cachedChunkIndex = -1
        try {
            file.close()
        } catch (_: Exception) {
            throw EncryptedMediaException(READ_ERROR)
        }
    }

    private companion object {
        const val READ_ERROR = "无法读取加密媒体"
    }
}
