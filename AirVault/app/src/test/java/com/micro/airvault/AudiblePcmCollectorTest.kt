package com.micro.airvault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiblePcmCollectorTest {
    @Test
    fun silenceAndLowLevelNoiseDoNotAdvanceProgress() {
        val collector = AudiblePcmCollector(6400)
        val silent = ByteArray(3200)
        val noise = pcm(3200, 100)
        repeat(20) {
            assertFalse(collector.addIfAudible(silent, silent.size))
            assertFalse(collector.addIfAudible(noise, noise.size))
        }
        assertEquals(0, collector.progress)
        assertFalse(collector.complete)
        collector.clear()
    }

    @Test
    fun audibleChunksAdvanceProgressAndAreKeptForEntropy() {
        val collector = AudiblePcmCollector(6400)
        val voice = pcm(3200, 2000)
        assertTrue(hasAudibleSound(voice, voice.size))
        assertTrue(collector.addIfAudible(voice, voice.size))
        assertEquals(50, collector.progress)
        assertFalse(collector.addIfAudible(ByteArray(3200), 3200))
        assertEquals(50, collector.progress)
        assertTrue(collector.addIfAudible(voice, voice.size))
        assertEquals(100, collector.progress)
        assertTrue(collector.complete)
        assertArrayEquals(voice + voice, collector.takeComplete())
    }

    private fun pcm(size: Int, amplitude: Int): ByteArray = ByteArray(size).also { bytes ->
        for (index in bytes.indices step 2) {
            val sample = if (index % 4 == 0) amplitude else -amplitude
            bytes[index] = sample.toByte()
            bytes[index + 1] = (sample shr 8).toByte()
        }
    }
}
