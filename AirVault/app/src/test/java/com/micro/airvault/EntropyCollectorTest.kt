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
    private data class GyroSample(val timestamp: Long, val x: Float, val y: Float, val z: Float)

    @Test
    fun hashesSourcesInCaptureOrderAndGeneratesTwentyFourWords() {
        val random = ByteArray(EntropyCollector.RANDOM_BYTES) { it.toByte() }
        val expectedHashes = mutableListOf(sha3(random))
        val collector = EntropyCollector(random)
        assertArrayEquals(ByteArray(random.size), random)

        repeat(EntropyCollector.CAMERA_FRAMES) { index ->
            val frame = ByteArray(EntropyCollector.FRAME_WIDTH * EntropyCollector.FRAME_HEIGHT) {
                (it + index).toByte()
            }
            expectedHashes += sha3(frame)
            collector.addCameraFrame(frame)
            assertArrayEquals(ByteArray(frame.size), frame)
            assertEquals(index + 1, collector.cameraFrames)
        }

        val gyroscope = GyroscopeEntropy()
        val startedAt = 1_000_000_000L
        gyroscope.begin(startedAt)
        val gyroSamples = (1..12).map { index ->
            GyroSample(startedAt + index * 200_000_000L, index.toFloat(), (index * 2).toFloat(), (-index).toFloat())
        }
        gyroSamples.forEach { sample ->
            gyroscope.addSample(sample.timestamp, sample.x, sample.y, sample.z)
        }
        gyroscope.end(startedAt + 3_000_000_000L)
        val gyroscopeDigest = gyroscope.finish()
        val gyroHash = MessageDigest.getInstance("SHA3-256")
        gyroSamples.forEach { sample ->
            gyroHash.update(ByteBuffer.allocate(20)
                .putLong(sample.timestamp)
                .putInt(sample.x.toBits())
                .putInt(sample.y.toBits())
                .putInt(sample.z.toBits())
                .array())
        }
        assertArrayEquals(gyroHash.digest(), gyroscopeDigest)
        expectedHashes += gyroscopeDigest.copyOf()
        collector.addGyroscopeDigest(gyroscopeDigest)
        assertArrayEquals(ByteArray(32), gyroscopeDigest)

        val microphoneBytes = ByteArray(96_000) { (it * 3).toByte() }
        val microphoneDigest = sha3(microphoneBytes)
        expectedHashes += microphoneDigest.copyOf()
        collector.addMicrophoneDigest(microphoneDigest)
        assertArrayEquals(ByteArray(32), microphoneDigest)

        val drawing = (0..20).map { index ->
            DrawSample(index.toFloat(), (index * 2).toFloat(), 1000L + index, index == 0)
        }
        val drawingDigest = MessageDigest.getInstance("SHA3-256")
        drawing.forEach { sample ->
            drawingDigest.update(ByteBuffer.allocate(17)
                .putInt(sample.x.toBits())
                .putInt(sample.y.toBits())
                .putLong(sample.timeNanos)
                .put(if (sample.startsStroke) 1.toByte() else 0.toByte())
                .array())
        }
        expectedHashes += drawingDigest.digest()
        val expected = sha3(expectedHashes.reduce { combined, next -> combined + next })

        val result = collector.finish(drawing)
        assertArrayEquals(expected, result)
        assertEquals(24, WalletEngine.generateMnemonic(result).split(' ').size)
        assertArrayEquals(ByteArray(32), result)
        assertThrows(IllegalStateException::class.java) { collector.finish(drawing) }
    }

    @Test
    fun refusesIncompleteCollection() {
        val collector = EntropyCollector(ByteArray(EntropyCollector.RANDOM_BYTES))
        val microphoneDigest = ByteArray(32)
        assertThrows(IllegalStateException::class.java) { collector.addMicrophoneDigest(microphoneDigest) }
        repeat(EntropyCollector.CAMERA_FRAMES) {
            collector.addCameraFrame(ByteArray(EntropyCollector.FRAME_WIDTH * EntropyCollector.FRAME_HEIGHT))
        }
        assertThrows(IllegalStateException::class.java) { collector.addMicrophoneDigest(ByteArray(32)) }
        assertThrows(IllegalStateException::class.java) {
            collector.finish((0..20).map { DrawSample(it.toFloat(), it.toFloat(), it.toLong(), it == 0) })
        }
        collector.clear()
    }

    @Test
    fun generatesMnemonicWithoutGyroscope() {
        val random = ByteArray(EntropyCollector.RANDOM_BYTES) { it.toByte() }
        val expectedHashes = mutableListOf(sha3(random))
        val collector = EntropyCollector(random)
        repeat(EntropyCollector.CAMERA_FRAMES) { index ->
            val frame = ByteArray(EntropyCollector.FRAME_WIDTH * EntropyCollector.FRAME_HEIGHT) { index.toByte() }
            expectedHashes += sha3(frame)
            collector.addCameraFrame(frame)
        }
        collector.addGyroscopeDigest(null)
        assertEquals(EntropyCollector.CAMERA_FRAMES, collector.cameraFrames)
        val microphone = ByteArray(EntropyCollector.HASH_BYTES) { 7 }
        expectedHashes += microphone.copyOf()
        collector.addMicrophoneDigest(microphone)
        val drawing = (0..20).map { index ->
            DrawSample(index.toFloat(), (index * 2).toFloat(), index.toLong(), index == 0)
        }
        val drawingHash = MessageDigest.getInstance("SHA3-256")
        drawing.forEach { sample ->
            drawingHash.update(ByteBuffer.allocate(17).putInt(sample.x.toBits())
                .putInt(sample.y.toBits()).putLong(sample.timeNanos)
                .put(if (sample.startsStroke) 1 else 0).array())
        }
        expectedHashes += drawingHash.digest()
        val expected = sha3(expectedHashes.reduce { joined, next -> joined + next })
        val entropy = collector.finish(drawing)
        assertArrayEquals(expected, entropy)
        assertEquals(24, WalletEngine.generateMnemonic(entropy).split(' ').size)
    }

    @Test
    fun gyroscopeRequiresSamplesWithinCameraWindow() {
        val gyroscope = GyroscopeEntropy()
        gyroscope.begin(1_000_000_000L)
        gyroscope.addSample(900_000_000L, 1f, 2f, 3f)
        gyroscope.addSample(1_100_000_000L, 1f, 2f, 3f)
        gyroscope.end(4_000_000_000L)
        gyroscope.addSample(4_100_000_000L, 1f, 2f, 3f)
        assertThrows(IllegalArgumentException::class.java) { gyroscope.finish() }
        gyroscope.clear()
    }

    @Test
    fun cameraTakesThreeFramesAtLeastOneSecondApartOverThreeSeconds() {
        val schedule = CameraFrameSchedule()
        val start = 1_000_000_000L
        assertFalse(schedule.shouldCapture(start))
        assertFalse(schedule.shouldCapture(start + 999_999_999L))
        assertTrue(schedule.shouldCapture(start + 1_000_000_000L))
        assertFalse(schedule.shouldCapture(start + 1_500_000_000L))
        assertTrue(schedule.shouldCapture(start + 2_000_000_000L))
        assertFalse(schedule.shouldCapture(start + 2_500_000_000L))
        assertTrue(schedule.shouldCapture(start + 3_000_000_000L))
        assertFalse(schedule.shouldCapture(start + 4_000_000_000L))
    }

    private fun sha3(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA3-256").digest(bytes)
}
