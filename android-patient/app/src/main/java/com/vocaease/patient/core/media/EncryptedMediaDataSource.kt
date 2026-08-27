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
class EncryptedMediaDataSource(
    private val store: ChunkedAesGcmFileStore,
    private val accountScope: String,
) : BaseDataSource(false) {
    private var reader: EncryptedFileReader? = null
    private var openedUri: Uri? = null
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var transferOpen = false

    override fun open(dataSpec: DataSpec): Long {
        if (reader != null) close()
        transferInitializing(dataSpec)
        try {
            val relativePath = dataSpec.uri.path.orEmpty().removePrefix("/")
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
            clearState()
            throw error
        } catch (_: Exception) {
            clearState()
            throw EncryptedMediaException("无法读取加密媒体")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
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
}
