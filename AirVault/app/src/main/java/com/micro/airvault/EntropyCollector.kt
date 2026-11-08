package com.micro.airvault

import java.nio.ByteBuffer
import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA3Digest

internal data class DrawSample(
    val x: Float,
    val y: Float,
    val timeNanos: Long,
    val startsStroke: Boolean
)

internal class EntropyCollector(randomBytes: ByteArray) {
    private val sourceHashes = ArrayList<ByteArray>(7)
    private var finished = false
    private var cameraFrameCount = 0
    private var gyroscopeHandled = false
    private var microphoneHandled = false

    init {
        try {
            require(randomBytes.size == RANDOM_BYTES) { "随机数长度无效" }
            sourceHashes += sha3(randomBytes)
        } finally {
            randomBytes.fill(0)
        }
    }

    val cameraFrames: Int
        get() = cameraFrameCount

    fun addCameraFrame(frame: ByteArray) {
        try {
            check(!finished && !gyroscopeHandled && cameraFrameCount < CAMERA_FRAMES) { "相机采集顺序无效" }
            require(frame.size == FRAME_WIDTH * FRAME_HEIGHT) { "相机帧尺寸无效" }
            sourceHashes += sha3(frame)
            cameraFrameCount++
        } finally {
            frame.fill(0)
        }
    }

    fun addMicrophoneDigest(digest: ByteArray) {
        try {
            check(!finished && gyroscopeHandled && !microphoneHandled) { "麦克风采集顺序无效" }
            require(digest.size == HASH_BYTES) { "麦克风摘要长度无效" }
            sourceHashes += digest.copyOf()
            microphoneHandled = true
        } finally {
            digest.fill(0)
        }
    }

    fun addGyroscopeDigest(digest: ByteArray?) {
        try {
            check(!finished && cameraFrameCount == CAMERA_FRAMES && !gyroscopeHandled) { "陀螺仪采集顺序无效" }
            if (digest != null) {
                require(digest.size == HASH_BYTES) { "陀螺仪摘要长度无效" }
                sourceHashes += digest.copyOf()
            }
            gyroscopeHandled = true
        } finally {
            digest?.fill(0)
        }
    }

    fun finish(drawing: List<DrawSample>): ByteArray {
        check(!finished && microphoneHandled) { "采集数据不完整" }
        require(drawing.count { !it.startsStroke } >= MIN_DRAW_MOVEMENTS) { "请继续绘制图形" }
        val drawingDigest = SHA3Digest(256).let { digest ->
            val encoded = ByteBuffer.allocate(17)
            drawing.forEach { sample ->
                encoded.clear()
                encoded.putInt(sample.x.toBits())
                encoded.putInt(sample.y.toBits())
                encoded.putLong(sample.timeNanos)
                encoded.put(if (sample.startsStroke) 1.toByte() else 0.toByte())
                digest.update(encoded.array(), 0, encoded.position())
            }
            ByteArray(HASH_BYTES).also { digest.doFinal(it, 0) }
        }
        sourceHashes += drawingDigest
        val joined = ByteArray(sourceHashes.size * HASH_BYTES)
        return try {
            sourceHashes.forEachIndexed { index, hash ->
                hash.copyInto(joined, index * HASH_BYTES)
            }
            sha3(joined)
        } finally {
            joined.fill(0)
            clear()
        }
    }

    fun clear() {
        sourceHashes.forEach { it.fill(0) }
        sourceHashes.clear()
        finished = true
    }

    companion object {
        const val RANDOM_BYTES = 4096
        const val FRAME_WIDTH = 480
        const val FRAME_HEIGHT = 640
        const val CAMERA_FRAMES = 3
        const val HASH_BYTES = 32
        const val MIN_DRAW_MOVEMENTS = 12

        fun create(): EntropyCollector {
            val random = ByteArray(RANDOM_BYTES)
            SecureRandom().nextBytes(random)
            return EntropyCollector(random)
        }

        private fun sha3(bytes: ByteArray): ByteArray {
            val digest = SHA3Digest(256)
            digest.update(bytes, 0, bytes.size)
            return ByteArray(HASH_BYTES).also { digest.doFinal(it, 0) }
        }
    }
}
