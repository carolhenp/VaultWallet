package com.micro.airvault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class EntropyCollectorTest {
    private val drawing = (0..EntropyCollector.MIN_DRAW_MOVEMENTS).map {
        DrawSample(it.toFloat(), (it * 2).toFloat(), 1000L + it, it == 0)
    }

    @Test
    fun streamsRawSourcesIntoOneSha3DigestAndClearsInputs() {
        val random = ByteArray(EntropyCollector.RANDOM_BYTES) { it.toByte() }
        val expected = MessageDigest.getInstance("SHA3-256")
        expected.update(random)
        val collector = EntropyCollector(random)
        repeat(3) { index ->
            val photo = ByteArray(100 + index) { (it + index).toByte() }
            expected.update(photo)
            collector.addCameraPhoto(CameraSide.BACK, photo)
            assertArrayEquals(ByteArray(photo.size), photo)
        }
        repeat(3) { index ->
            val photo = ByteArray(70 + index) { (it * 2 + index).toByte() }
            expected.update(photo)
            collector.addCameraPhoto(CameraSide.FRONT, photo)
        }
        val microphone = ByteArray(EntropyCollector.MICROPHONE_BYTES) { (it * 3).toByte() }
        expected.update(microphone)
        collector.addMicrophoneData(microphone)
        val gyroscope = ByteArray(120) { (it * 7).toByte() }
        expected.update(gyroscope)
        collector.addGyroscopeData(gyroscope)
        assertTrue(collector.ready)
        drawing.forEach { sample ->
            expected.update(ByteBuffer.allocate(17).putInt(sample.x.toBits()).putInt(sample.y.toBits())
                .putLong(sample.timeNanos).put(if (sample.startsStroke) 1 else 0).array())
        }
        val result = collector.finish(drawing)
        assertArrayEquals(expected.digest(), result)
        assertEquals(24, WalletEngine.generateMnemonic(result).split(' ').size)
        assertArrayEquals(ByteArray(random.size), random)
        assertArrayEquals(ByteArray(microphone.size), microphone)
        assertArrayEquals(ByteArray(gyroscope.size), gyroscope)
        assertThrows(IllegalStateException::class.java) { collector.finish(drawing) }
    }

    @Test
    fun needsAllSixPhotosAndAudioButAllowsMissingGyroscope() {
        val collector = EntropyCollector(ByteArray(EntropyCollector.RANDOM_BYTES))
        repeat(3) { collector.addCameraPhoto(CameraSide.BACK, byteArrayOf(it.toByte())) }
        repeat(2) { collector.addCameraPhoto(CameraSide.FRONT, byteArrayOf(it.toByte())) }
        collector.addMicrophoneData(ByteArray(EntropyCollector.MICROPHONE_BYTES))
        collector.addGyroscopeData(null)
        assertFalse(collector.ready)
        assertThrows(IllegalStateException::class.java) { collector.finish(drawing) }
        collector.addCameraPhoto(CameraSide.FRONT, byteArrayOf(3))
        assertTrue(collector.ready)
        assertEquals(32, collector.finish(drawing).size)
    }

    @Test
    fun gyroscopeIgnoresStationaryReadingsAndRequiresThousandChanges() {
        val gyroscope = GyroscopeEntropy()
        val start = 1_000_000_000L
        gyroscope.begin(start)
        assertFalse(gyroscope.addSample(start, 0f, 0f, 0f))
        repeat(1000) { index ->
            assertFalse(gyroscope.addSample(start + index * 1_000_000L, 0.001f, 0.001f, 0.001f))
        }
        assertEquals(0, gyroscope.acceptedSamples)
        val expected = ByteBuffer.allocate(GyroscopeEntropy.REQUIRED_SAMPLES * 20)
        repeat(GyroscopeEntropy.REQUIRED_SAMPLES) { index ->
            val time = start + (index + 1) * 5_000_000L
            val x = if (index % 2 == 0) 0.2f else -0.2f
            assertTrue(gyroscope.addSample(time, x, 0f, 0f))
            expected.putLong(time).putInt(x.toBits()).putInt(0f.toBits()).putInt(0f.toBits())
        }
        assertEquals(1000, gyroscope.acceptedSamples)
        assertFalse(gyroscope.addSample(start + 6_000_000_000L, 1f, 0f, 0f))
        gyroscope.end(start + 5_000_000_000L)
        assertArrayEquals(expected.array(), gyroscope.finish())
    }

    @Test
    fun gyroscopeCannotFinishWithOnlyNoise() {
        val gyroscope = GyroscopeEntropy()
        gyroscope.begin(0)
        gyroscope.addSample(1, 0f, 0f, 0f)
        repeat(1000) { gyroscope.addSample(it.toLong() + 2, 0.001f, 0f, 0f) }
        gyroscope.end(5_000_000_000L)
        assertThrows(IllegalArgumentException::class.java) { gyroscope.finish() }
        gyroscope.clear()
    }

    @Test
    fun cameraTakesThreeFramesOneSecondApart() {
        val schedule = CameraFrameSchedule()
        val start = 1_000_000_000L
        assertFalse(schedule.shouldCapture(start))
        assertFalse(schedule.shouldCapture(start + 999_999_999L))
        assertTrue(schedule.shouldCapture(start + 1_000_000_000L))
        assertTrue(schedule.shouldCapture(start + 2_000_000_000L))
        assertTrue(schedule.shouldCapture(start + 3_000_000_000L))
        assertFalse(schedule.shouldCapture(start + 4_000_000_000L))
    }
}
