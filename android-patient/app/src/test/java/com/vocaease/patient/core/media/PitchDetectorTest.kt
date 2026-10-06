package com.vocaease.patient.core.media
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PitchDetectorTest {
    @Test fun detectsKnownPitchAtBothSampleRates() {
        for (rate in listOf(48000,44100)) for (frequency in listOf(110,220,440)) {
            val pcm=ShortArray((rate*.046).toInt()) { (12000*sin(2*PI*frequency*it/rate)).toInt().toShort() }
            val sample=PitchDetector().detect(pcm,rate,0)
            assertNotNull(sample.frequencyHz)
            assertEquals(pitchToMidi(frequency.toFloat()),pitchToMidi(sample.frequencyHz!!),.5f)
        }
    }
    @Test fun silenceAndNoiseHaveNoPitch() {
        val detector=PitchDetector()
        assertNull(detector.detect(ShortArray(2208),48000,0).frequencyHz)
        val random=Random(7)
        assertNull(detector.detect(ShortArray(2208) { random.nextInt(-12000,12000).toShort() },48000,0).frequencyHz)
    }
    @Test fun harmonicRichVoiceAvoidsOctaveError() {
        val pcm=ShortArray(2208) { (5000*sin(2*PI*110*it/48000)+9000*sin(2*PI*220*it/48000)+4000*sin(2*PI*330*it/48000)).toInt().toShort() }
        assertEquals(pitchToMidi(110f),pitchToMidi(PitchDetector().detect(pcm,48000,0).frequencyHz!!),.5f)
    }
}
