package com.vocaease.patient.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

open class EncryptedMediaException internal constructor(message: String) : java.io.IOException(message)
class MediaKeyInvalidatedException internal constructor() : EncryptedMediaException("加密媒体密钥已失效")

internal data class EncryptedMediaReference(val relativePath: String, val encryptedSizeBytes: Long)
internal enum class StoreIoStep { TEMP_CHMOD, FILE_FSYNC, ATOMIC_MOVE, DESTINATION_CHMOD, DIRECTORY_FSYNC, TEMP_CLEANUP }

/** 软件 AES key 的单一生命周期实例；destroy 后密钥字节不可再导出或用于新 cipher。 */
internal class WipeableAesKey(keyMaterial: ByteArray) : SecretKey {
    private val bytes = keyMaterial.copyOf()
    @Volatile private var destroyed = false

    init {
        require(keyMaterial.size == 32)
    }

    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String = "RAW"

    @Synchronized override fun getEncoded(): ByteArray {
        check(!destroyed) { "key destroyed" }
        return bytes.copyOf()
    }

    @Synchronized override fun destroy() {
        if (destroyed) return
        bytes.fill(0)
        destroyed = true
    }

    override fun isDestroyed(): Boolean = destroyed
}

/**
 * VEF1 媒体存储。调用方只持有账户内相对路径，物理目录固定为 root/SHA-256(scope)。
 * [encrypt] 在明确的 IO dispatcher 执行；播放器读取由 Media3 loader 线程调用。
 */
internal class ChunkedAesGcmFileStore(
    context: Context,
    private val rootDirectory: File = File(context.filesDir, "encrypted_media"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileIdGenerator: () -> ByteArray = { ByteArray(FILE_ID_LENGTH).also(SecureRandom()::nextBytes) },
    private val chunkCipherObserver: (provider: String, keyImplementation: String) -> Unit = { _, _ -> },
    private val failureInjector: (StoreIoStep) -> Unit = {},
    private val readerCloseObserver: () -> Unit = {},
) {
    private val secureRandom = SecureRandom()

    suspend fun encrypt(accountScope: String, plaintext: InputStream, originalLength: Long): EncryptedMediaReference =
        runInterruptible(ioDispatcher) { encryptBlocking(accountScope, plaintext, originalLength) }

    private fun encryptBlocking(accountScope: String, plaintext: InputStream, originalLength: Long): EncryptedMediaReference {
        if (accountScope.isBlank() || originalLength <= 0 || originalLength > MAX_ORIGINAL_LENGTH) failWrite()
        val scopeHash = sha256(accountScope)
        return synchronized(accountLocks.computeIfAbsent(scopeHash) { Any() }) {
            val mediaDirectory = File(accountDirectory(scopeHash), "media/v1")
            ensurePrivateDirectory(mediaDirectory)
            val fileId = fileIdGenerator()
            if (fileId.size != FILE_ID_LENGTH || fileId.all { it == 0.toByte() }) failWrite()
            val relativePath = "media/v1/${fileId.toHex()}.vef"
            val destination = resolveAccountRelative(accountScope, relativePath, WRITE_ERROR)
            val destinationLock = publicationLocks.computeIfAbsent(destination.canonicalPath) { Any() }
            synchronized(destinationLock) {
            if (destination.exists()) failWrite()
            val temporary = try {
                File.createTempFile(".vef-", ".tmp", mediaDirectory)
            } catch (_: Exception) {
                failWrite()
            }
            var published = false
            try {
                failureInjector(StoreIoStep.TEMP_CHMOD)
                makeOwnerOnly(temporary, false)
                loadMaster(accountScope, createIfMissing = true).use { master ->
                    val chunkCount = chunkCountFor(originalLength)
                    val header = buildHeader(originalLength, chunkCount, fileId)
                    FileOutput(temporary).use { output ->
                        output.write(header)
                        var remaining = originalLength
                        repeat(chunkCount) { chunkIndex ->
                            val plainLength = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
                            val plain = ByteArray(plainLength)
                            try {
                                readExact(plaintext, plain)
                                val key = master.secretKey()
                                val cipher = softwareCipher()
                                chunkCipherObserver(cipher.provider.name, key.javaClass.name)
                                val nonce = ByteArray(NONCE_LENGTH).also(secureRandom::nextBytes)
                                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                                cipher.updateAAD(chunkAad(header, chunkIndex, plainLength))
                                output.writeInt(plainLength)
                                output.write(nonce.copyOf())
                                output.write(cipher.doFinal(plain))
                            } finally {
                                plain.fill(0)
                            }
                            remaining -= plainLength
                        }
                        if (remaining != 0L || plaintext.read() != -1) failWrite()
                        failureInjector(StoreIoStep.FILE_FSYNC)
                        output.sync()
                    }
                }
                failureInjector(StoreIoStep.ATOMIC_MOVE)
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                published = true
                failureInjector(StoreIoStep.DESTINATION_CHMOD)
                makeOwnerOnly(destination, false)
                failureInjector(StoreIoStep.DIRECTORY_FSYNC)
                syncDirectory(mediaDirectory)
                EncryptedMediaReference(relativePath, destination.length())
            } catch (error: MediaKeyInvalidatedException) {
                if (published) rollbackPublished(destination, mediaDirectory)
                throw error
            } catch (error: EncryptedMediaException) {
                if (published) rollbackPublished(destination, mediaDirectory)
                throw error
            } catch (_: Exception) {
                if (published) rollbackPublished(destination, mediaDirectory)
                failWrite()
            } finally {
                if (temporary.exists()) {
                    runCatching { failureInjector(StoreIoStep.TEMP_CLEANUP) }
                    temporary.delete()
                }
            }
            }
        }
    }

    private fun rollbackPublished(destination: File, parent: File) {
        destination.delete()
        runCatching { syncDirectory(parent) }
    }

    fun open(accountScope: String, encryptedRelativePath: String): EncryptedFileReader {
        if (accountScope.isBlank()) failOpen()
        val scopeHash = sha256(accountScope)
        val registryKey = registryKey(scopeHash)
        return synchronized(accountLocks.computeIfAbsent(scopeHash) { Any() }) {
            val source = resolveAccountRelative(accountScope, encryptedRelativePath, OPEN_ERROR)
            var file: RandomAccessFile? = null
            var master: MasterKeyLease? = null
            try {
                file = RandomAccessFile(source, "r")
                val header = ByteArray(HEADER_SIZE)
                file.readFully(header)
                val parsed = parseAndValidateHeader(header, file.length())
                validateUniqueNonces(file, parsed.chunkCount)
                master = loadMaster(accountScope, createIfMissing = false)
                val generation = accountGenerations.computeIfAbsent(registryKey) { AtomicLong() }.get()
                lateinit var reader: EncryptedFileReader
                reader = EncryptedFileReader(
                    file = file,
                    master = master,
                    header = header,
                    length = parsed.originalLength,
                    chunkCount = parsed.chunkCount,
                    cipherObserver = chunkCipherObserver,
                    generationValid = {
                        accountGenerations.computeIfAbsent(registryKey) { AtomicLong() }.get() == generation
                    },
                    closeObserver = {
                        activeReaders[registryKey]?.remove(reader)
                        readerCloseObserver()
                    },
                )
                activeReaders.computeIfAbsent(registryKey) { ConcurrentHashMap.newKeySet() }.add(reader)
                reader
            } catch (error: MediaKeyInvalidatedException) {
                file?.closeQuietly(); master?.close(); throw error
            } catch (error: EncryptedMediaException) {
                file?.closeQuietly(); master?.close(); throw error
            } catch (_: Exception) {
                file?.closeQuietly(); master?.close(); failOpen()
            }
        }
    }

    internal fun encryptedMediaExists(accountScope: String, encryptedRelativePath: String): Boolean =
        runCatching {
            val scopeHash = sha256(accountScope)
            synchronized(accountLocks.computeIfAbsent(scopeHash) { Any() }) {
                resolveAccountRelative(accountScope, encryptedRelativePath, OPEN_ERROR).isFile
            }
        }.getOrDefault(false)

    internal fun verifyEncryptedMedia(accountScope: String, encryptedRelativePath: String, expectedLength: Long) {
        open(accountScope, encryptedRelativePath).use { reader ->
            if (reader.length != expectedLength || expectedLength <= 0) failOpen()
            val buffer = ByteArray(CHUNK_SIZE)
            var position = 0L
            while (position < reader.length) {
                val read = reader.read(position, buffer, 0, minOf(buffer.size.toLong(), reader.length - position).toInt())
                if (read <= 0) failOpen()
                position += read
            }
            buffer.fill(0)
        }
    }

    internal fun deleteEncryptedMedia(accountScope: String, encryptedRelativePath: String) {
        if (accountScope.isBlank()) failWrite()
        val scopeHash = sha256(accountScope)
        synchronized(accountLocks.computeIfAbsent(scopeHash) { Any() }) {
            val target = resolveAccountRelative(accountScope, encryptedRelativePath, WRITE_ERROR)
            if (target.exists() && !target.delete()) failWrite()
            runCatching { syncDirectory(target.parentFile ?: return@synchronized) }
        }
    }

    internal fun destroyAccountEncryption(accountScope: String) {
        if (accountScope.isBlank()) failWrite()
        val scopeHash = sha256(accountScope)
        try {
            synchronized(accountLocks.computeIfAbsent(scopeHash) { Any() }) {
                val registryKey = registryKey(scopeHash)
                accountGenerations.computeIfAbsent(registryKey) { AtomicLong() }.incrementAndGet()
                activeReaders.remove(registryKey)?.toList()?.forEach(EncryptedFileReader::revoke)
                val directory = accountDirectory(scopeHash)
                if (directory.exists()) {
                    directory.walkBottomUp().forEach { if (it.exists() && !it.delete()) failWrite() }
                }
                keyStore().deleteEntry(alias(scopeHash))
            }
        } catch (error: EncryptedMediaException) {
            throw error
        } catch (_: Exception) {
            failWrite()
        }
    }

    private fun loadMaster(accountScope: String, createIfMissing: Boolean): MasterKeyLease {
        if (accountScope.isBlank()) if (createIfMissing) failWrite() else failOpen()
        val scopeHash = sha256(accountScope)
        return synchronized(accountLocks.computeIfAbsent(scopeHash) { Any() }) {
            val directory = accountDirectory(scopeHash)
            val envelope = File(directory, MASTER_FILE)
            val store = keyStore()
            val kekAlias = alias(scopeHash)
            val existingKek = store.getKey(kekAlias, null) as? SecretKey
            if (envelope.exists()) {
                if (existingKek == null) throw MediaKeyInvalidatedException()
                return@synchronized unwrapMaster(envelope, existingKek, scopeHash)
            }
            if (!createIfMissing) failOpen()
            // 孤立 alias 代表创建中断或损坏；绝不静默轮换后尝试读取旧密文。
            if (existingKek != null) throw MediaKeyInvalidatedException()
            ensurePrivateDirectory(directory)
            val kek = generateKek(kekAlias)
            val masterBytes = ByteArray(MASTER_LENGTH).also(secureRandom::nextBytes)
            try {
                publishEnvelope(directory, envelope, wrapMaster(masterBytes, kek, scopeHash))
                MasterKeyLease(masterBytes.copyOf())
            } catch (error: Exception) {
                runCatching { store.deleteEntry(kekAlias) }
                if (error is EncryptedMediaException) throw error
                failWrite()
            } finally {
                masterBytes.fill(0)
            }
        }
    }

    private fun wrapMaster(master: ByteArray, kek: SecretKey, scopeHash: String): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        // AndroidKeyStore 在 encrypt 模式自行生成随机 nonce；KEK 禁止 caller supplied nonce。
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val nonce = cipher.iv.copyOf()
        if (nonce.size != NONCE_LENGTH) failWrite()
        cipher.updateAAD(masterAad(scopeHash))
        val ciphertext = cipher.doFinal(master)
        return ByteBuffer.allocate(MASTER_HEADER_SIZE + ciphertext.size)
            .put(MASTER_MAGIC).putInt(MASTER_VERSION).putInt(nonce.size).putInt(ciphertext.size)
            .put(nonce).put(ciphertext).array()
    }

    private fun unwrapMaster(envelope: File, kek: SecretKey, scopeHash: String): MasterKeyLease {
        try {
            val bytes = envelope.readBytes()
            if (bytes.size != MASTER_FILE_SIZE) throw MediaKeyInvalidatedException()
            val buffer = ByteBuffer.wrap(bytes)
            val magic = ByteArray(MASTER_MAGIC.size).also(buffer::get)
            val version = buffer.int
            val nonceLength = buffer.int
            val ciphertextLength = buffer.int
            if (!magic.contentEquals(MASTER_MAGIC) || version != MASTER_VERSION || nonceLength != NONCE_LENGTH || ciphertextLength != MASTER_LENGTH + TAG_LENGTH) throw MediaKeyInvalidatedException()
            val nonce = ByteArray(nonceLength).also(buffer::get)
            val ciphertext = ByteArray(ciphertextLength).also(buffer::get)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(masterAad(scopeHash))
            val master = cipher.doFinal(ciphertext)
            if (master.size != MASTER_LENGTH) { master.fill(0); throw MediaKeyInvalidatedException() }
            return MasterKeyLease(master)
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw MediaKeyInvalidatedException()
        } catch (error: MediaKeyInvalidatedException) {
            throw error
        } catch (_: Exception) {
            throw MediaKeyInvalidatedException()
        }
    }

    private fun publishEnvelope(directory: File, envelope: File, payload: ByteArray) {
        val temporary = File.createTempFile(".master-", ".tmp", directory)
        var published = false
        try {
            makeOwnerOnly(temporary, false)
            FileOutput(temporary).use { it.write(payload); it.sync() }
            Files.move(temporary.toPath(), envelope.toPath(), StandardCopyOption.ATOMIC_MOVE)
            published = true
            makeOwnerOnly(envelope, false)
            syncDirectory(directory)
        } catch (_: Exception) {
            if (published) envelope.delete()
            failWrite()
        } finally {
            payload.fill(0)
            temporary.delete()
        }
    }

    private fun resolveAccountRelative(accountScope: String, relativePath: String, safeError: String): File {
        if (accountScope.isBlank() || !MEDIA_PATH.matches(relativePath)) throw EncryptedMediaException(safeError)
        try {
            val accountRoot = accountDirectory(sha256(accountScope)).canonicalFile
            val candidate = File(accountRoot, relativePath).canonicalFile
            if (!candidate.path.startsWith(accountRoot.path + File.separator)) throw EncryptedMediaException(safeError)
            return candidate
        } catch (error: EncryptedMediaException) {
            throw error
        } catch (_: Exception) {
            throw EncryptedMediaException(safeError)
        }
    }

    private fun accountDirectory(scopeHash: String) = File(rootDirectory, scopeHash)
    private fun registryKey(scopeHash: String): String = rootDirectory.canonicalPath + '\u0000' + scopeHash

    private fun ensurePrivateDirectory(target: File) {
        val root = rootDirectory.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget != root && !canonicalTarget.path.startsWith(root.path + File.separator)) failWrite()
        generateSequence(canonicalTarget) { it.parentFile }
            .takeWhile { it.path.startsWith(root.path) }
            .toList().asReversed()
            .forEach { directory ->
                if (!directory.isDirectory && !directory.mkdir() && !directory.isDirectory) failWrite()
                makeOwnerOnly(directory, true)
            }
    }

    private fun generateKek(kekAlias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(kekAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    private fun alias(scopeHash: String) = KEY_ALIAS_PREFIX + scopeHash
    private fun softwareCipher(): Cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)

    private fun validateUniqueNonces(file: RandomAccessFile, chunkCount: Int) {
        val seen = HashSet<String>(chunkCount)
        repeat(chunkCount) { index ->
            file.seek(chunkOffset(index) + Int.SIZE_BYTES)
            val nonce = ByteArray(NONCE_LENGTH); file.readFully(nonce)
            if (!seen.add(nonce.toHex())) failOpen()
        }
    }

    private data class ParsedHeader(val originalLength: Long, val chunkCount: Int)

    private fun parseAndValidateHeader(header: ByteArray, actualFileLength: Long): ParsedHeader {
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC) || buffer.int != CHUNK_SIZE) failOpen()
        val originalLength = buffer.long
        val chunkCount = buffer.int
        val fileId = ByteArray(FILE_ID_LENGTH).also(buffer::get)
        if (originalLength <= 0 || originalLength > MAX_ORIGINAL_LENGTH || chunkCount <= 0 || chunkCount != chunkCountFor(originalLength) || fileId.all { it == 0.toByte() }) failOpen()
        val expectedLength = try {
            Math.addExact(HEADER_SIZE.toLong(), Math.addExact(originalLength, Math.multiplyExact(chunkCount.toLong(), RECORD_OVERHEAD.toLong())))
        } catch (_: ArithmeticException) { failOpen() }
        if (expectedLength != actualFileLength) failOpen()
        return ParsedHeader(originalLength, chunkCount)
    }

    private fun buildHeader(originalLength: Long, chunkCount: Int, fileId: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).putInt(CHUNK_SIZE).putLong(originalLength).putInt(chunkCount).put(fileId).array()

    private fun chunkCountFor(originalLength: Long): Int =
        ((originalLength + CHUNK_SIZE - 1) / CHUNK_SIZE).also { if (it <= 0 || it > Int.MAX_VALUE) failOpen() }.toInt()

    private fun readExact(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) failWrite()
            if (read == 0) {
                val single = input.read(); if (single < 0) failWrite(); target[offset++] = single.toByte()
            } else offset += read
        }
    }

    private fun makeOwnerOnly(file: File, directory: Boolean) = Os.chmod(file.absolutePath, if (directory) OWNER_RWX else OWNER_RW)
    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private class FileOutput(file: File) : Closeable {
        private val stream = FileOutputStream(file)
        private val data = DataOutputStream(stream)
        fun write(bytes: ByteArray) = data.write(bytes)
        fun writeInt(value: Int) = data.writeInt(value)
        fun sync() { data.flush(); stream.fd.sync() }
        override fun close() = data.close()
    }

    internal class MasterKeyLease(bytes: ByteArray) : Closeable {
        private val key = WipeableAesKey(bytes)

        init {
            bytes.fill(0)
        }

        fun secretKey(): SecretKey {
            check(!key.isDestroyed)
            return key
        }

        override fun close() {
            key.destroy()
        }
    }

    companion object {
        internal const val CHUNK_SIZE = 1024 * 1024
        internal const val HEADER_SIZE = 36
        internal const val RECORD_OVERHEAD = 4 + 12 + 16
        private const val FILE_ID_LENGTH = 16
        private const val NONCE_LENGTH = 12
        private const val TAG_LENGTH = 16
        private const val TAG_BITS = 128
        private const val MAX_ORIGINAL_LENGTH = 32L shl 30
        private const val MASTER_LENGTH = 32
        private const val MASTER_VERSION = 1
        private const val MASTER_HEADER_SIZE = 4 + 4 + 4 + 4 + NONCE_LENGTH
        private const val MASTER_FILE_SIZE = MASTER_HEADER_SIZE + MASTER_LENGTH + TAG_LENGTH
        private const val MASTER_FILE = "master.v1"
        private const val OWNER_RWX = 0x1c0
        private const val OWNER_RW = 0x180
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "vocaease_media_kek_"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val OPEN_ERROR = "无法读取加密媒体"
        private const val WRITE_ERROR = "无法保存加密媒体"
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'E'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
        private val MASTER_MAGIC = byteArrayOf('V'.code.toByte(), 'M'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
        private val MEDIA_PATH = Regex("media/v1/[0-9a-f]{32}\\.vef")
        private val accountLocks = ConcurrentHashMap<String, Any>()
        private val publicationLocks = ConcurrentHashMap<String, Any>()
        private val accountGenerations = ConcurrentHashMap<String, AtomicLong>()
        private val activeReaders = ConcurrentHashMap<String, MutableSet<EncryptedFileReader>>()

        internal fun chunkAad(header: ByteArray, chunkIndex: Int, plainLength: Int): ByteArray =
            ByteBuffer.allocate(header.size + Int.SIZE_BYTES * 2).put(header).putInt(chunkIndex).putInt(plainLength).array()
        internal fun chunkOffset(chunkIndex: Int): Long = HEADER_SIZE.toLong() + chunkIndex.toLong() * (CHUNK_SIZE + RECORD_OVERHEAD).toLong()
        private fun masterAad(scopeHash: String): ByteArray = ByteBuffer.allocate(MASTER_MAGIC.size + 4 + 32)
            .put(MASTER_MAGIC).putInt(MASTER_VERSION).put(scopeHash.hexToBytes()).array()
        internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun failOpen(): Nothing = throw EncryptedMediaException(OPEN_ERROR)
        private fun failWrite(): Nothing = throw EncryptedMediaException(WRITE_ERROR)
        private fun RandomAccessFile.closeQuietly() { runCatching { close() } }
    }
}

internal class EncryptedFileReader internal constructor(
    private val file: RandomAccessFile,
    private val master: ChunkedAesGcmFileStore.MasterKeyLease,
    private val header: ByteArray,
    val length: Long,
    private val chunkCount: Int,
    private val cipherObserver: (String, String) -> Unit,
    private val generationValid: () -> Boolean,
    private val closeObserver: () -> Unit,
) : Closeable {
    private var closed = false
    private var cachedChunkIndex = -1
    private var cachedPlaintext: ByteArray? = null

    @Synchronized fun read(position: Long, target: ByteArray, offset: Int, requestedLength: Int): Int {
        if (closed || !generationValid() || position < 0 || offset < 0 || requestedLength < 0 || offset > target.size - requestedLength) failRead()
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
            sourcePosition += copied; destinationOffset += copied; remaining -= copied
        }
        return total
    }

    private fun decryptedChunk(chunkIndex: Int): ByteArray {
        cachedPlaintext?.takeIf { cachedChunkIndex == chunkIndex }?.let { return it }
        if (chunkIndex !in 0 until chunkCount) failRead()
        try {
            file.seek(ChunkedAesGcmFileStore.chunkOffset(chunkIndex))
            val plainLength = file.readInt()
            val expected = if (chunkIndex == chunkCount - 1) (length - chunkIndex.toLong() * ChunkedAesGcmFileStore.CHUNK_SIZE).toInt() else ChunkedAesGcmFileStore.CHUNK_SIZE
            if (plainLength != expected) failRead()
            val nonce = ByteArray(12).also(file::readFully)
            val ciphertext = ByteArray(plainLength + 16).also(file::readFully)
            val key = master.secretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipherObserver(cipher.provider.name, key.javaClass.name)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(ChunkedAesGcmFileStore.chunkAad(header, chunkIndex, plainLength))
            val plaintext = cipher.doFinal(ciphertext)
            cachedPlaintext?.fill(0)
            cachedChunkIndex = chunkIndex; cachedPlaintext = plaintext
            return plaintext
        } catch (_: Exception) {
            poison(); failRead()
        }
    }

    private fun poison() {
        if (closed) return
        closed = true; cachedPlaintext?.fill(0); cachedPlaintext = null; cachedChunkIndex = -1
        runCatching { file.close() }; master.close(); runCatching(closeObserver)
    }

    @Synchronized internal fun revoke() {
        poison()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true; cachedPlaintext?.fill(0); cachedPlaintext = null; cachedChunkIndex = -1
        val closeFailed = runCatching { file.close() }.isFailure
        master.close()
        runCatching(closeObserver)
        if (closeFailed) failRead()
    }

    private fun failRead(): Nothing = throw EncryptedMediaException("无法读取加密媒体")
}
