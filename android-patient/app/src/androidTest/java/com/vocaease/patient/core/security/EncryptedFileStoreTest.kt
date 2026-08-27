package com.vocaease.patient.core.security

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.media.EncryptedMediaDataSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

@RunWith(AndroidJUnit4::class)
class EncryptedFileStoreTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: ChunkedAesGcmFileStore
    private lateinit var sample: ByteArray

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "encrypted-test-${System.nanoTime()}")
        store = ChunkedAesGcmFileStore(context, root)
        sample = ByteArray(3 * MIB + MIB / 2) { index -> ((index * 31 + index / 97) and 0xff).toByte() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun encrypt_writesVef1WithoutLargePlaintextAndWithPrivatePermissions() {
        val encrypted = encrypt("patient-record-90001", "sample.vef", sample)
        val bytes = encrypted.readBytes()

        assertArrayEquals(byteArrayOf('V'.code.toByte(), 'E'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte()), bytes.copyOfRange(0, 4))
        assertFalse(bytes.containsSlice(sample.copyOfRange(321_000, 325_096)))
        val mode = Os.stat(encrypted.absolutePath).st_mode
        assertEquals(0, mode and (OsConstants.S_IROTH or OsConstants.S_IWOTH or OsConstants.S_IXOTH))
    }

    @Test
    fun reader_randomSeekMatchesOriginalAcrossChunkBoundariesAndTail() {
        encrypt(ACCOUNT_A, "sample.vef", sample)
        store.open(ACCOUNT_A, "sample.vef").use { reader ->
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
    fun mediaDataSource_honorsPositionLengthEofCloseAndReopen() {
        encrypt(ACCOUNT_A, "media/sample.vef", sample)
        val source = EncryptedMediaDataSource(store, ACCOUNT_A)
        val uri = Uri.parse("vocaease-encrypted:///media/sample.vef")
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
    fun readerRejectsTagNonceHeaderTruncationTrailingBytesAndChunkReordering() {
        val original = encrypt(ACCOUNT_A, "original.vef", sample)
        val originalBytes = original.readBytes()
        val firstRecordLength = RECORD_OVERHEAD + MIB

        val tag = originalBytes.copyOf().also { it[HEADER_SIZE + firstRecordLength - 1] = (it[HEADER_SIZE + firstRecordLength - 1].toInt() xor 1).toByte() }
        assertUnreadable("tag.vef", tag, 0)

        val nonce = originalBytes.copyOf().also { it[HEADER_SIZE + 4] = (it[HEADER_SIZE + 4].toInt() xor 1).toByte() }
        assertUnreadable("nonce.vef", nonce, 0)

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
            store.open(ACCOUNT_A, "reordered.vef").use { it.read(0, ByteArray(1), 0, 1) }
        }
    }

    @Test
    fun readerRejectsMalformedBoundedHeadersBeforeAllocation() {
        val bytes = encrypt(ACCOUNT_A, "original.vef", sample).readBytes()

        assertOpenFails("magic.vef", bytes.copyOf().also { it[0] = 'X'.code.toByte() })
        assertOpenFails("chunk-size.vef", bytes.copyOf().also { writeInt(it, 4, Int.MAX_VALUE) })
        assertOpenFails("negative-length.vef", bytes.copyOf().also { writeLong(it, 8, -1L) })
        assertOpenFails("huge-length.vef", bytes.copyOf().also { writeLong(it, 8, Long.MAX_VALUE) })
        assertOpenFails("chunk-count.vef", bytes.copyOf().also { writeInt(it, 16, Int.MAX_VALUE) })
    }

    @Test
    fun chunksCannotBeSplicedAcrossFilesEvenWhenLengthsMatch() {
        val first = encrypt(ACCOUNT_A, "first.vef", sample).readBytes()
        val otherPlaintext = sample.copyOf().also { it[MIB + 10] = (it[MIB + 10].toInt() xor 0x55).toByte() }
        val second = encrypt(ACCOUNT_A, "second.vef", otherPlaintext).readBytes()
        val recordLength = RECORD_OVERHEAD + MIB
        second.copyOfRange(HEADER_SIZE + recordLength, HEADER_SIZE + 2 * recordLength)
            .copyInto(first, HEADER_SIZE + recordLength)
        writeEncrypted("spliced.vef", first)

        assertThrows(EncryptedMediaException::class.java) {
            store.open(ACCOUNT_A, "spliced.vef").use {
                it.read(MIB.toLong(), ByteArray(32), 0, 32)
            }
        }
    }

    @Test
    fun authenticationFailurePoisonsReaderAndPreventsFurtherPlaintextReads() {
        val bytes = encrypt(ACCOUNT_A, "original.vef", sample).readBytes()
        val secondTagOffset = HEADER_SIZE + 2 * (RECORD_OVERHEAD + MIB) - 1
        bytes[secondTagOffset] = (bytes[secondTagOffset].toInt() xor 1).toByte()
        writeEncrypted("poisoned.vef", bytes)

        val reader = store.open(ACCOUNT_A, "poisoned.vef")
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
    fun differentAccountCannotDecryptEvenWithEncryptedRelativePath() {
        encrypt(ACCOUNT_A, "sample.vef", sample)

        assertThrows(EncryptedMediaException::class.java) {
            store.open("patient-b", "sample.vef").use { it.read(0, ByteArray(32), 0, 32) }
        }
    }

    @Test
    fun keyAliasContainsDigestOnlyAndConcurrentFirstUseRemainsDecryptable() {
        val privateAccount = "medical-record-123456"
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val futures = (0 until 2).map { index ->
            executor.submit<File> {
                ready.countDown()
                start.await(10, TimeUnit.SECONDS)
                encrypt(privateAccount, "concurrent-$index.vef", sample.copyOfRange(0, 2048))
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        futures.forEach { assertNotNull(it.get(30, TimeUnit.SECONDS)) }
        executor.shutdownNow()

        futures.indices.forEach { index ->
            store.open(privateAccount, "concurrent-$index.vef").use { reader ->
                val actual = ByteArray(2048)
                assertEquals(2048, reader.read(0, actual, 0, actual.size))
                assertArrayEquals(sample.copyOfRange(0, 2048), actual)
            }
        }
        val aliases = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()
        assertTrue(aliases.any { it.startsWith("vocaease_media_") })
        assertTrue(aliases.none { it.contains(privateAccount, ignoreCase = true) })
        assertTrue(aliases.none { it.contains("123456") })
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

    private fun encrypt(account: String, path: String, bytes: ByteArray): File =
        store.encrypt(account, path, ByteArrayInputStream(bytes), bytes.size.toLong())

    private fun assertUnreadable(path: String, bytes: ByteArray, position: Int) {
        writeEncrypted(path, bytes)
        assertThrows(EncryptedMediaException::class.java) {
            store.open(ACCOUNT_A, path).use { it.read(position.toLong(), ByteArray(1), 0, 1) }
        }
    }

    private fun assertOpenFails(path: String, bytes: ByteArray) {
        writeEncrypted(path, bytes)
        assertThrows(EncryptedMediaException::class.java) { store.open(ACCOUNT_A, path) }
    }

    private fun writeEncrypted(path: String, bytes: ByteArray) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use {
            it.setLength(0)
            it.write(bytes)
        }
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
