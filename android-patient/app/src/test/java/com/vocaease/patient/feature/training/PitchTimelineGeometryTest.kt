package com.vocaease.patient.feature.training
import org.junit.Assert.*
import org.junit.Test

class PitchTimelineGeometryTest {
    @Test fun timelineMovesReferenceLeft() {
        assertEquals(100f,timelineX(3000,1000,800f)-timelineX(3000,2000,800f),.1f)
        assertEquals(200f,timelineX(1000,1000,800f),.1f)
    }
    @Test fun fallbackRangeStaysStable() {
        assertEquals(36f,pitchRange(emptyList()).first,.01f)
        assertEquals(84f,pitchRange(emptyList()).second,.01f)
    }
}
