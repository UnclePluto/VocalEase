package com.vocaease.patient.core.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.security.EncryptedFileReader
import com.vocaease.patient.core.security.EncryptedMediaException

@UnstableApi
/** Media3 只应在 Loader/播放后台线程调用本 DataSource；禁止从主线程直接读取。 */
internal class EncryptedMediaDataSource(
    private val store: ChunkedAesGcmFileStore,
    private val accountScope: String,
    private val fixedRelativePath: String? = null,
    private val leaseActive: () -> Boolean = { true },
) : BaseDataSource(false) {
    private var reader: EncryptedFileReader? = null
    private var openedUri: Uri? = null
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var transferOpen = false

    override fun open(dataSpec: DataSpec): Long {
        if (reader != null) close()
        if (!leaseActive()) throw EncryptedMediaException("无法读取加密媒体")
        transferInitializing(dataSpec)
        try {
            val relativePath = fixedRelativePath ?: dataSpec.uri.path.orEmpty().removePrefix("/")
            val openedReader = store.open(accountScope, relativePath)
            if (dataSpec.position < 0 || dataSpec.position > openedReader.length) {
                openedReader.close()
                throw EncryptedMediaException("无法读取加密媒体")
            }
            val available = openedReader.length - dataSpec.position
            val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else dataSpec.length
            if (requested < 0) {
                openedReader.close()
                throw EncryptedMediaException("无法读取加密媒体")
            }
            reader = openedReader
            openedUri = dataSpec.uri
            readPosition = dataSpec.position
            bytesRemaining = minOf(requested, available)
            transferOpen = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (error: EncryptedMediaException) {
            reader?.closeSafely()
            clearState()
            throw error
        } catch (_: Exception) {
            reader?.closeSafely()
            clearState()
            throw EncryptedMediaException("无法读取加密媒体")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!leaseActive()) {
            close()
            throw EncryptedMediaException("无法读取加密媒体")
        }
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val activeReader = reader ?: throw EncryptedMediaException("无法读取加密媒体")
        val requested = minOf(length.toLong(), bytesRemaining).toInt()
        val read = activeReader.read(readPosition, buffer, offset, requested)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        readPosition += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        val wasOpen = transferOpen
        try {
            reader?.close()
        } finally {
            clearState()
            if (wasOpen) transferEnded()
        }
    }

    private fun clearState() {
        reader = null
        openedUri = null
        readPosition = 0
        bytesRemaining = 0
        transferOpen = false
    }

    private fun EncryptedFileReader.closeSafely() {
        runCatching { close() }
    }
}
