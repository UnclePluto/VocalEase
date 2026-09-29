package com.vocaease.patient.feature.upload

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RevocablePlaintextLeaseProviderTest {
    @Test
    fun `换号或worker stop立即关闭活动明文且禁止新lease`() = runBlocking {
        val file = File.createTempFile("lease-revoke-", ".bin").apply { writeBytes(ByteArray(4)) }
        val provider = RevocablePlaintextLeaseProvider(
            PlaintextUploadLeaseProvider { _, _ ->
                object : PlaintextUploadLease {
                    override val file: File = file
                    override fun close() { file.delete() }
                }
            },
        )
        provider.open(UploadMediaKind.AUDIO, UploadMedia("audio/mp4", 4))

        provider.revokeAll()

        assertFalse(file.exists())
        assertThrows(CancellationException::class.java) {
            runBlocking { provider.open(UploadMediaKind.AUDIO, UploadMedia("audio/mp4", 4)) }
        }
        Unit
    }
}
