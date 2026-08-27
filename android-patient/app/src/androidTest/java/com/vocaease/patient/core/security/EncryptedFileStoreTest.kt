package com.vocaease.patient.core.security

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.media.EncryptedMediaDataSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

@RunWith(AndroidJUnit4::class)
class EncryptedFileStoreTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: ChunkedAesGcmFileStore
    private lateinit var sample: ByteArray
    private val aliases = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "encrypted-test-${System.nanoTime()}")
        store = ChunkedAesGcmFileStore(context, root)
        sample = ByteArray(3 * MIB + MIB / 2) { index -> ((index * 31 + index / 97) and 0xff).toByte() }
    }

    @After
    fun tearDown() {
        listOf(ACCOUNT_A, "patient-b", "patient-record-90001", "medical-record-123456").forEach {
            runCatching { store.destroyAccountEncryption(it) }
        }
        root.deleteRecursively()
    }

    @Test
    fun encrypt_writesVef1WithoutLargePlaintextAndWithPrivatePermissions() = runBlocking {
        val encrypted = encrypt("patient-record-90001", "sample.vef", sample)
        val bytes = encrypted.readBytes()

        assertArrayEquals(byteArrayOf('V'.code.toByte(), 'E'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte()), bytes.copyOfRange(0, 4))
        assertFalse(bytes.containsSlice(sample.copyOfRange(321_000, 325_096)))
        val mode = Os.stat(encrypted.absolutePath).st_mode
        assertEquals(0, mode and (OsConstants.S_IROTH or OsConstants.S_IWOTH or OsConstants.S_IXOTH))
    }

    @Test
    fun reader_randomSeekMatchesOriginalAcrossChunkBoundariesAndTail() = runBlocking {
        encrypt(ACCOUNT_A, "sample.vef", sample)
        store.open(ACCOUNT_A, pathFor("sample.vef")).use { reader ->
            assertEquals(sample.size.toLong(), reader.length)
            listOf(
                0L to 257,
                (MIB - 73).toLong() to 401,
                MIB.toLong() to 777,
                (2 * MIB - 13).toLong() to 1027,
                (sample.size - 333).toLong() to 333,
            ).forEach { (position, requested) ->
                val actual = ByteArray(requested)
                val read = reader.read(position, actual, 0, actual.size)
                assertEquals(requested, read)
                assertArrayEquals(sample.copyOfRange(position.toInt(), position.toInt() + requested), actual)
            }
            assertEquals(-1, reader.read(sample.size.toLong(), ByteArray(8), 0, 8))
        }
    }

    @Test
    fun mediaDataSource_honorsPositionLengthEofCloseAndReopen() = runBlocking {
        encrypt(ACCOUNT_A, "media/sample.vef", sample)
        val source = EncryptedMediaDataSource(store, ACCOUNT_A)
        val uri = Uri.parse("vocaease-encrypted:///${pathFor("media/sample.vef")}")
        val position = (MIB - 19).toLong()
        val expectedLength = 200L

        assertEquals(expectedLength, source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(expectedLength).build()))
        assertEquals(uri, source.uri)
        val actual = ByteArray(300)
        assertEquals(137, source.read(actual, 0, 137))
        assertEquals(63, source.read(actual, 137, 163))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(actual, 200, 100))
        assertEquals(0, source.read(actual, 0, 0))
        source.close()
        assertEquals(null, source.uri)

        assertEquals(10L, source.open(DataSpec.Builder().setUri(uri).setPosition(12).setLength(10).build()))
        val reopened = ByteArray(10)
        assertEquals(10, source.read(reopened, 0, 10))
        assertArrayEquals(sample.copyOfRange(12, 22), reopened)
        source.close()
    }

    @Test
    fun dataSourceClosesReaderWhenTransferStartedCallbackThrows() = runBlocking {
        var closeCount = 0
        val observedStore = ChunkedAesGcmFileStore(context, root, readerCloseObserver = { closeCount++ })
        val reference = observedStore.encrypt(ACCOUNT_A, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        val source = EncryptedMediaDataSource(observedStore, ACCOUNT_A)
        source.addTransferListener(
            object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    throw IllegalStateException("listener")
                }
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            },
        )

        assertThrows(EncryptedMediaException::class.java) {
            source.open(DataSpec(Uri.parse("vocaease-encrypted:///${reference.relativePath}")))
        }
        assertEquals(1, closeCount)
        assertEquals(null, source.uri)
    }

    @Test
    fun readerRejectsTagNonceHeaderTruncationTrailingBytesAndChunkReordering() = runBlocking {
        val original = encrypt(ACCOUNT_A, "original.vef", sample)
        val originalBytes = original.readBytes()
        val firstRecordLength = RECORD_OVERHEAD + MIB

        val tag = originalBytes.copyOf().also { it[HEADER_SIZE + firstRecordLength - 1] = (it[HEADER_SIZE + firstRecordLength - 1].toInt() xor 1).toByte() }
        assertUnreadable("tag.vef", tag, 0)

        val nonce = originalBytes.copyOf().also { it[HEADER_SIZE + 4] = (it[HEADER_SIZE + 4].toInt() xor 1).toByte() }
        assertUnreadable("nonce.vef", nonce, 0)

        val copiedNonce = originalBytes.copyOf().also { bytes ->
            bytes.copyOfRange(HEADER_SIZE + 4, HEADER_SIZE + 4 + 12)
                .copyInto(bytes, HEADER_SIZE + firstRecordLength + 4)
        }
        assertOpenFails("copied-nonce.vef", copiedNonce)

        val header = originalBytes.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertUnreadable("header.vef", header, 0)

        assertOpenFails("truncated.vef", originalBytes.copyOf(originalBytes.size - 1))
        assertOpenFails("trailing.vef", originalBytes + 0x55.toByte())

        val reordered = originalBytes.copyOf()
        val first = originalBytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + firstRecordLength)
        val second = originalBytes.copyOfRange(HEADER_SIZE + firstRecordLength, HEADER_SIZE + 2 * firstRecordLength)
        second.copyInto(reordered, HEADER_SIZE)
        first.copyInto(reordered, HEADER_SIZE + firstRecordLength)
        writeEncrypted("reordered.vef", reordered)
        assertThrows(EncryptedMediaException::class.java) {
            store.open(ACCOUNT_A, pathFor("reordered.vef")).use { it.read(0, ByteArray(1), 0, 1) }
        }
        Unit
    }

    @Test
    fun readerRejectsMalformedBoundedHeadersBeforeAllocation() = runBlocking {
        val bytes = encrypt(ACCOUNT_A, "original.vef", sample).readBytes()

        assertOpenFails("magic.vef", bytes.copyOf().also { it[0] = 'X'.code.toByte() })
        assertOpenFails("chunk-size.vef", bytes.copyOf().also { writeInt(it, 4, Int.MAX_VALUE) })
        assertOpenFails("negative-length.vef", bytes.copyOf().also { writeLong(it, 8, -1L) })
        assertOpenFails("huge-length.vef", bytes.copyOf().also { writeLong(it, 8, Long.MAX_VALUE) })
        assertOpenFails("chunk-count.vef", bytes.copyOf().also { writeInt(it, 16, Int.MAX_VALUE) })
    }

    @Test
    fun chunksCannotBeSplicedAcrossFilesEvenWhenLengthsMatch() = runBlocking {
        val first = encrypt(ACCOUNT_A, "first.vef", sample).readBytes()
        val otherPlaintext = sample.copyOf().also { it[MIB + 10] = (it[MIB + 10].toInt() xor 0x55).toByte() }
        val second = encrypt(ACCOUNT_A, "second.vef", otherPlaintext).readBytes()
        val recordLength = RECORD_OVERHEAD + MIB
        second.copyOfRange(HEADER_SIZE + recordLength, HEADER_SIZE + 2 * recordLength)
            .copyInto(first, HEADER_SIZE + recordLength)
        writeEncrypted("spliced.vef", first)

        assertThrows(EncryptedMediaException::class.java) {
            store.open(ACCOUNT_A, pathFor("spliced.vef")).use {
                it.read(MIB.toLong(), ByteArray(32), 0, 32)
            }
        }
        Unit
    }

    @Test
    fun authenticationFailurePoisonsReaderAndPreventsFurtherPlaintextReads() = runBlocking {
        val bytes = encrypt(ACCOUNT_A, "original.vef", sample).readBytes()
        val secondTagOffset = HEADER_SIZE + 2 * (RECORD_OVERHEAD + MIB) - 1
        bytes[secondTagOffset] = (bytes[secondTagOffset].toInt() xor 1).toByte()
        writeEncrypted("poisoned.vef", bytes)

        val reader = store.open(ACCOUNT_A, pathFor("poisoned.vef"))
        val first = ByteArray(32)
        assertEquals(32, reader.read(0, first, 0, first.size))
        assertThrows(EncryptedMediaException::class.java) {
            reader.read(MIB.toLong(), ByteArray(32), 0, 32)
        }
        assertThrows(EncryptedMediaException::class.java) {
            reader.read(0, ByteArray(32), 0, 32)
        }
        reader.close()
    }

    @Test
    fun differentAccountCannotDecryptEvenWithEncryptedRelativePath() = runBlocking {
        val reference = store.encrypt(ACCOUNT_A, ByteArrayInputStream(sample), sample.size.toLong())
        store.encrypt("patient-b", ByteArrayInputStream(byteArrayOf(1)), 1)
        val copiedIntoB = physicalFile("patient-b", reference.relativePath)
        copiedIntoB.parentFile?.mkdirs()
        physicalFile(ACCOUNT_A, reference.relativePath).copyTo(copiedIntoB)

        assertThrows(EncryptedMediaException::class.java) {
            store.open("patient-b", reference.relativePath).use { it.read(0, ByteArray(32), 0, 32) }
        }
        Unit
    }

    @Test
    fun accountScopeOwnsPhysicalNamespaceAndCallerCannotChoosePrefix() = runBlocking {
        val fixedFileId = ByteArray(16) { 7 }
        val namespacedStore = ChunkedAesGcmFileStore(context, root, fileIdGenerator = { fixedFileId.copyOf() })
        val a = namespacedStore.encrypt(ACCOUNT_A, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        val b = namespacedStore.encrypt("patient-b", ByteArrayInputStream(byteArrayOf(4, 5, 6)), 3)

        assertEquals(a.relativePath, b.relativePath)
        assertFalse(physicalFile(ACCOUNT_A, a.relativePath).canonicalPath == physicalFile("patient-b", b.relativePath).canonicalPath)
        assertArrayEquals(byteArrayOf(1, 2, 3), readAll(namespacedStore, ACCOUNT_A, a.relativePath))
        assertArrayEquals(byteArrayOf(4, 5, 6), readAll(namespacedStore, "patient-b", b.relativePath))
        physicalFile("patient-b", b.relativePath).delete()
        assertArrayEquals(byteArrayOf(1, 2, 3), readAll(namespacedStore, ACCOUNT_A, a.relativePath))
        assertThrows(EncryptedMediaException::class.java) {
            namespacedStore.open("", a.relativePath)
        }
        Unit
    }

    @Test
    fun wrappedMasterEnvelopeIsPrivateAndDoesNotContainPlainMasterWhileChunksUseSoftwareAes() = runBlocking {
        var chunkProvider = ""
        var keyClass = ""
        val inspectedStore = ChunkedAesGcmFileStore(
            context,
            root,
            chunkCipherObserver = { provider, implementation ->
                chunkProvider = provider
                keyClass = implementation
            },
        )
        val reference = inspectedStore.encrypt(ACCOUNT_A, ByteArrayInputStream(sample), sample.size.toLong())
        val accountDirectory = File(root, digest(ACCOUNT_A))
        val envelope = File(accountDirectory, "master.v1")

        assertTrue(envelope.isFile)
        val envelopeBytes = envelope.readBytes()
        assertArrayEquals(byteArrayOf('V'.code.toByte(), 'M'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte()), envelopeBytes.copyOfRange(0, 4))
        assertEquals(0, Os.stat(envelope.absolutePath).st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO))
        listOf(root, accountDirectory, File(accountDirectory, "media"), File(accountDirectory, "media/v1")).forEach { directory ->
            assertEquals(0, Os.stat(directory.absolutePath).st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO))
        }
        assertFalse(chunkProvider.contains("AndroidKeyStore", ignoreCase = true))
        assertTrue(keyClass.contains("WipeableAesKey"))
        val kek = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("vocaease_media_kek_${digest(ACCOUNT_A)}", null) as SecretKey
        assertEquals(null, kek.encoded)
        val envelopeBuffer = ByteBuffer.wrap(envelopeBytes)
        val magic = ByteArray(4).also(envelopeBuffer::get)
        val version = envelopeBuffer.int
        val nonceLength = envelopeBuffer.int
        val ciphertextLength = envelopeBuffer.int
        val nonce = ByteArray(nonceLength).also(envelopeBuffer::get)
        val ciphertext = ByteArray(ciphertextLength).also(envelopeBuffer::get)
        val master = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, nonce))
            updateAAD(ByteBuffer.allocate(40).put(magic).putInt(version).put(digest(ACCOUNT_A).hexToBytes()).array())
            doFinal(ciphertext)
        }
        assertEquals(32, master.size)
        assertFalse(envelopeBytes.containsSlice(master))
        master.fill(0)
        assertArrayEquals(sample.copyOfRange(0, 64), readAll(inspectedStore, ACCOUNT_A, reference.relativePath).copyOfRange(0, 64))
    }

    @Test
    fun wipeableSoftwareKeyDestroyZeroizesAndRejectsEncodingOrCipherReuse() {
        val original = ByteArray(32) { (it + 1).toByte() }
        val key = WipeableAesKey(original)
        assertArrayEquals(original, key.encoded)

        key.destroy()

        assertTrue(key.isDestroyed)
        assertThrows(IllegalStateException::class.java) { key.encoded }
        assertThrows(IllegalStateException::class.java) {
            Cipher.getInstance("AES/GCM/NoPadding").init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(128, ByteArray(12)),
            )
        }
    }

    @Test
    fun destroyAccountRevokesOpenReaderAndCachedPlaintextBeforeDeletingKeyMaterial() = runBlocking {
        val reference = store.encrypt(ACCOUNT_A, ByteArrayInputStream(sample), sample.size.toLong())
        val reader = store.open(ACCOUNT_A, reference.relativePath)
        assertEquals(64, reader.read(0, ByteArray(64), 0, 64))

        store.destroyAccountEncryption(ACCOUNT_A)

        assertThrows(EncryptedMediaException::class.java) {
            reader.read(0, ByteArray(64), 0, 64)
        }
        reader.close()
    }

    @Test
    fun concurrentReadAndDestroyLinearizeAndClosedReaderLeavesRegistry() = runBlocking {
        val blockRead = AtomicBoolean(false)
        val readEnteredCipher = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        var closeCount = 0
        val observedStore = ChunkedAesGcmFileStore(
            context,
            root,
            chunkCipherObserver = { _, _ ->
                if (blockRead.get()) {
                    readEnteredCipher.countDown()
                    assertTrue(releaseRead.await(10, TimeUnit.SECONDS))
                }
            },
            readerCloseObserver = { closeCount++ },
        )
        val reference = observedStore.encrypt(ACCOUNT_A, ByteArrayInputStream(sample), sample.size.toLong())
        val reader = observedStore.open(ACCOUNT_A, reference.relativePath)
        val executor = Executors.newFixedThreadPool(2)
        try {
            blockRead.set(true)
            val readFuture = executor.submit<Int> {
                reader.read(MIB.toLong(), ByteArray(64), 0, 64)
            }
            assertTrue(readEnteredCipher.await(10, TimeUnit.SECONDS))
            val destroyFuture = executor.submit<Unit> { observedStore.destroyAccountEncryption(ACCOUNT_A) }
            Thread.sleep(100)
            assertFalse("destroy 必须与已进入的 read 线性化", destroyFuture.isDone)

            releaseRead.countDown()
            assertEquals(64, readFuture.get(10, TimeUnit.SECONDS))
            destroyFuture.get(10, TimeUnit.SECONDS)
            assertThrows(EncryptedMediaException::class.java) {
                reader.read(0, ByteArray(1), 0, 1)
            }
            assertEquals(1, closeCount)
            reader.close()
            observedStore.destroyAccountEncryption(ACCOUNT_A)
            assertEquals(1, closeCount)
        } finally {
            releaseRead.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellingEncryptInterruptsBlockingInputAndNeverPublishesCiphertext() = runBlocking {
        supervisorScope {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val readInterrupted = CountDownLatch(1)
        val blockingInput = object : java.io.InputStream() {
            override fun read(): Int = error("bulk read expected")
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                readEntered.countDown()
                try {
                    releaseRead.await(10, TimeUnit.SECONDS)
                } catch (error: InterruptedException) {
                    readInterrupted.countDown()
                    throw java.io.IOException("interrupted", error)
                }
                target[offset] = 1
                return 1
            }
        }
        val encryption = async(Dispatchers.IO) {
            store.encrypt(ACCOUNT_A, blockingInput, 1)
        }
        assertTrue(readEntered.await(10, TimeUnit.SECONDS))
        try {
            encryption.cancel()
            assertTrue("取消必须中断阻塞输入", readInterrupted.await(2, TimeUnit.SECONDS))
        } finally {
            releaseRead.countDown()
            runCatching { encryption.await() }
        }
        assertTrue(root.walkTopDown().none { it.extension == "vef" })
        }
    }

    @Test
    fun publicationFailuresRollbackNewTargetPreserveExistingAndCleanTemps() = runBlocking {
        val baseline = store.encrypt(ACCOUNT_A, ByteArrayInputStream(byteArrayOf(8, 8, 8)), 3)
        val baselineBytes = readAll(store, ACCOUNT_A, baseline.relativePath)
        listOf(
            StoreIoStep.TEMP_CHMOD,
            StoreIoStep.FILE_FSYNC,
            StoreIoStep.ATOMIC_MOVE,
            StoreIoStep.DESTINATION_CHMOD,
            StoreIoStep.DIRECTORY_FSYNC,
        ).forEachIndexed { index, failingStep ->
            val failedId = ByteArray(16) { (index + 20).toByte() }
            val failing = ChunkedAesGcmFileStore(
                context,
                root,
                fileIdGenerator = { failedId.copyOf() },
                failureInjector = { step -> if (step == failingStep) throw java.io.IOException("injected") },
            )
            assertThrows(EncryptedMediaException::class.java) {
                runBlocking { failing.encrypt(ACCOUNT_A, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3) }
            }
            assertFalse(physicalFile(ACCOUNT_A, "media/v1/${failedId.toHex()}.vef").exists())
            assertArrayEquals(baselineBytes, readAll(store, ACCOUNT_A, baseline.relativePath))
            assertTrue(root.walkTopDown().none { it.name.startsWith(".vef-") })
        }

        val cleanupFailure = ChunkedAesGcmFileStore(
            context,
            root,
            failureInjector = { step -> if (step == StoreIoStep.FILE_FSYNC || step == StoreIoStep.TEMP_CLEANUP) throw java.io.IOException("injected") },
        )
        assertThrows(EncryptedMediaException::class.java) {
            runBlocking { cleanupFailure.encrypt(ACCOUNT_A, ByteArrayInputStream(byteArrayOf(1)), 1) }
        }
        assertTrue(root.walkTopDown().none { it.name.startsWith(".vef-") })
    }

    @Test
    fun permanentlyLostKekFailsClosedUntilExplicitPurgeAndRecreate() = runBlocking {
        val old = store.encrypt(ACCOUNT_A, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        val alias = "vocaease_media_kek_${digest(ACCOUNT_A)}"
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }

        assertThrows(MediaKeyInvalidatedException::class.java) { store.open(ACCOUNT_A, old.relativePath) }
        assertThrows(MediaKeyInvalidatedException::class.java) {
            runBlocking { store.encrypt(ACCOUNT_A, ByteArrayInputStream(byteArrayOf(4)), 1) }
        }
        store.destroyAccountEncryption(ACCOUNT_A)
        val recreated = store.encrypt(ACCOUNT_A, ByteArrayInputStream(byteArrayOf(9)), 1)
        assertArrayEquals(byteArrayOf(9), readAll(store, ACCOUNT_A, recreated.relativePath))
    }

    @Test
    fun immutablePublicationAllowsOnlyOneConcurrentWriterForSameGeneratedVersion() {
        val fixedFileId = ByteArray(16) { 9 }
        val concurrentStore = ChunkedAesGcmFileStore(context, root, fileIdGenerator = { fixedFileId.copyOf() })
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val futures = (0..1).map { value ->
            executor.submit<Boolean> {
                ready.countDown()
                start.await(10, TimeUnit.SECONDS)
                runCatching {
                    runBlocking {
                        concurrentStore.encrypt(ACCOUNT_A, ByteArrayInputStream(ByteArray(2048) { value.toByte() }), 2048)
                    }
                }.isSuccess
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        assertEquals(1, futures.count { it.get(30, TimeUnit.SECONDS) })
        executor.shutdownNow()
        assertTrue(root.walkTopDown().none { it.name.startsWith(".vef-") })
    }

    @Test
    fun keyAliasContainsDigestOnlyAndConcurrentFirstUseRemainsDecryptable() {
        val privateAccount = "medical-record-123456"
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val futures = (0 until 2).map { index ->
            executor.submit<EncryptedMediaReference> {
                ready.countDown()
                start.await(10, TimeUnit.SECONDS)
                runBlocking { store.encrypt(privateAccount, ByteArrayInputStream(sample.copyOfRange(0, 2048)), 2048) }
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        futures.forEach { assertNotNull(it.get(30, TimeUnit.SECONDS)) }
        executor.shutdownNow()

        futures.forEach { future ->
            store.open(privateAccount, future.get().relativePath).use { reader ->
                val actual = ByteArray(2048)
                assertEquals(2048, reader.read(0, actual, 0, actual.size))
                assertArrayEquals(sample.copyOfRange(0, 2048), actual)
            }
        }
        val aliases = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()
        assertTrue(aliases.any { it.startsWith("vocaease_media_") })
        assertTrue(aliases.none { it.contains(privateAccount, ignoreCase = true) })
        assertTrue(aliases.none { it.contains("123456") })
        assertTrue(root.walkTopDown().none { it.name.contains(privateAccount) || it.name.contains("123456") })
    }

    @Test
    fun traversalAndAbsolutePathsAreRejectedWithoutLeakingFilesystemDetails() {
        val absolute = assertThrows(EncryptedMediaException::class.java) {
            store.open(ACCOUNT_A, "/data/user/0/secret.vef")
        }
        val traversal = assertThrows(EncryptedMediaException::class.java) {
            store.open(ACCOUNT_A, "../secret.vef")
        }
        assertFalse(absolute.message.orEmpty().contains("/data/user"))
        assertFalse(traversal.message.orEmpty().contains(".."))
    }

    private suspend fun encrypt(account: String, path: String, bytes: ByteArray): File {
        val reference = store.encrypt(account, ByteArrayInputStream(bytes), bytes.size.toLong())
        aliases["$account:$path"] = reference.relativePath
        return physicalFile(account, reference.relativePath)
    }

    private fun assertUnreadable(path: String, bytes: ByteArray, position: Int) {
        writeEncrypted(path, bytes)
        assertThrows(EncryptedMediaException::class.java) {
            store.open(ACCOUNT_A, pathFor(path)).use { it.read(position.toLong(), ByteArray(1), 0, 1) }
        }
    }

    private fun assertOpenFails(path: String, bytes: ByteArray) {
        writeEncrypted(path, bytes)
        assertThrows(EncryptedMediaException::class.java) { store.open(ACCOUNT_A, pathFor(path)) }
    }

    private fun writeEncrypted(path: String, bytes: ByteArray) {
        val relativePath = aliases.getOrPut("$ACCOUNT_A:$path") { "media/v1/${digest(path).take(32)}.vef" }
        val file = physicalFile(ACCOUNT_A, relativePath)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use {
            it.setLength(0)
            it.write(bytes)
        }
    }

    private fun pathFor(path: String, account: String = ACCOUNT_A): String =
        aliases["$account:$path"] ?: error("测试路径尚未创建")

    private fun physicalFile(account: String, relativePath: String): File =
        File(File(root, digest(account)), relativePath)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun readAll(source: ChunkedAesGcmFileStore, account: String, relativePath: String): ByteArray =
        source.open(account, relativePath).use { reader ->
            ByteArray(reader.length.toInt()).also { assertEquals(it.size, reader.read(0, it, 0, it.size)) }
        }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> target[offset + index] = (value ushr (24 - index * 8)).toByte() }
    }

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        repeat(8) { index -> target[offset + index] = (value ushr (56 - index * 8)).toByte() }
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
        while (hasMoreElements()) add(nextElement())
    }

    companion object {
        private const val ACCOUNT_A = "patient-a"
        private const val MIB = 1024 * 1024
        private const val HEADER_SIZE = 36
        private const val RECORD_OVERHEAD = 4 + 12 + 16
    }
}
