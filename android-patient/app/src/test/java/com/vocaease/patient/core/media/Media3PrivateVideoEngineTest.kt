package com.vocaease.patient.core.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Media3PrivateVideoEngineTest {
    @Test
    fun `播放错误只从其media period不可变绑定解析source而不读当前可变source`() {
        val item = MediaItem.EMPTY.buildUpon().setMediaId("source-old").build()
        val timeline = SinglePeriodTimeline(C.TIME_UNSET, false, false, false, null, item)
        val periodUid = timeline.getUidOfPeriod(0)

        assertEquals("source-old", immutableSourceIdFor(timeline, periodUid))
        assertNull(immutableSourceIdFor(timeline, Any()))
    }
}
