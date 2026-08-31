package com.vocaease.patient.feature.upload

import com.vocaease.patient.core.database.UploadJobEntity
import com.vocaease.patient.core.database.UploadOverallState
import com.vocaease.patient.core.database.UploadPipelineStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUploadItemMappingTest {
    @Test
    fun `只有带重试deadline的FAILED显示立即重试`() {
        val failed = UploadJobEntity.newPending(
            "account",
            "draft",
            "grant:draft:audio",
            "grant:draft:video",
            "submit:draft",
        ).copy(
            overallState = UploadOverallState.FAILED,
            pipelineStage = UploadPipelineStage.FAILED,
        )

        assertTrue(failed.copy(nextRetryAt = 1_000).toItem("练习歌曲").canRetry)
        assertFalse(failed.copy(nextRetryAt = null).toItem("练习歌曲").canRetry)
    }
}
