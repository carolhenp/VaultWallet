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

internal enum class CameraSide { BACK, FRONT }

internal class EntropyCollector(private val randomBytes: ByteArray) {
    private val backPhotos = ArrayList<ByteArray>(PHOTOS_PER_CAMERA)
    private val frontPhotos = ArrayList<ByteArray>(PHOTOS_PER_CAMERA)
    private var microphone: ByteArray? = null
    private var gyroscope: ByteArray? = null
    private var gyroscopeHandled = false
    private var finished = false

    init { require(randomBytes.size == RANDOM_BYTES) { "随机数长度无效" } }

    val backCount: Int get() = backPhotos.size
    val frontCount: Int get() = frontPhotos.size
    val ready: Boolean get() = !finished && backCount == PHOTOS_PER_CAMERA &&
        frontCount == PHOTOS_PER_CAMERA && microphone != null && gyroscopeHandled

    fun addCameraPhoto(side: CameraSide, photo: ByteArray) {
        try {
            check(!finished) { "采集已结束" }
            require(photo.isNotEmpty()) { "照片数据为空" }
            val photos = if (side == CameraSide.BACK) backPhotos else frontPhotos
            check(photos.size < PHOTOS_PER_CAMERA) { "照片数量超出预期" }
            photos += photo.copyOf()
        } finally { photo.fill(0) }
    }

    fun addMicrophoneData(data: ByteArray) {
        try {
            check(!finished && microphone == null) { "麦克风采集顺序无效" }
            require(data.size == MICROPHONE_BYTES) { "麦克风数据长度无效" }
            microphone = data.copyOf()
        } finally { data.fill(0) }
    }

    fun addGyroscopeData(data: ByteArray?) {
        try {
            check(!finished && !gyroscopeHandled) { "陀螺仪采集顺序无效" }
            gyroscope = data?.copyOf()
            gyroscopeHandled = true
        } finally { data?.fill(0) }
    }

    fun finish(drawing: List<DrawSample>): ByteArray {
        check(ready) { "采集数据不完整" }
        require(drawing.count { !it.startsStroke } >= MIN_DRAW_MOVEMENTS) { "请继续绘制图形" }
        val digest = SHA3Digest(256)
        val encoded = ByteBuffer.allocate(17)
        return try {
            digest.update(randomBytes, 0, randomBytes.size)
            backPhotos.forEach { digest.update(it, 0, it.size) }
            frontPhotos.forEach { digest.update(it, 0, it.size) }
            microphone!!.let { digest.update(it, 0, it.size) }
            gyroscope?.let { digest.update(it, 0, it.size) }
            drawing.forEach { sample ->
                encoded.clear()
                encoded.putInt(sample.x.toBits())
                encoded.putInt(sample.y.toBits())
                encoded.putLong(sample.timeNanos)
                encoded.put(if (sample.startsStroke) 1.toByte() else 0.toByte())
                digest.update(encoded.array(), 0, encoded.position())
            }
            ByteArray(HASH_BYTES).also { digest.doFinal(it, 0) }
        } finally {
            encoded.array().fill(0)
            clear()
        }
    }

    fun clear() {
        randomBytes.fill(0)
        backPhotos.forEach { it.fill(0) }
        frontPhotos.forEach { it.fill(0) }
        microphone?.fill(0)
        gyroscope?.fill(0)
        backPhotos.clear()
        frontPhotos.clear()
        microphone = null
        gyroscope = null
        finished = true
    }

    companion object {
        const val RANDOM_BYTES = 4096
        const val PHOTOS_PER_CAMERA = 3
        const val MICROPHONE_BYTES = 16_000 * 2 * 5
        const val HASH_BYTES = 32
        const val MIN_DRAW_MOVEMENTS = 100

        fun create(): EntropyCollector {
            val random = ByteArray(RANDOM_BYTES)
            SecureRandom().nextBytes(random)
            return EntropyCollector(random)
        }
    }
}
