package com.micro.airvault

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

internal class GyroscopeEntropy {
    private var bytes = ByteArray(4096)
    private val encoded = ByteBuffer.allocate(20)
    private var size = 0
    private var startedAtNanos: Long? = null
    private var endedAtNanos: Long? = null
    private var samples = 0
    private var finished = false
    private var lastX: Float? = null
    private var lastY = 0f
    private var lastZ = 0f

    val acceptedSamples: Int
        @Synchronized get() = samples

    @Synchronized
    fun begin(nowNanos: Long) {
        check(!finished) { "陀螺仪采集已结束" }
        if (startedAtNanos == null) startedAtNanos = nowNanos
    }

    @Synchronized
    fun addSample(timestampNanos: Long, x: Float, y: Float, z: Float): Boolean {
        val start = startedAtNanos ?: return false
        if (finished || samples == REQUIRED_SAMPLES || timestampNanos < start ||
            timestampNanos > (endedAtNanos ?: Long.MAX_VALUE)) return false
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "陀螺仪数据无效" }
        val previousX = lastX
        if (previousX == null) {
            lastX = x
            lastY = y
            lastZ = z
            return false
        }
        val change = max(abs(x - previousX), max(abs(y - lastY), abs(z - lastZ)))
        if (change < MIN_CHANGE_RADIANS_PER_SECOND) return false

        lastX = x
        lastY = y
        lastZ = z
        encoded.clear()
        encoded.putLong(timestampNanos)
        encoded.putInt(x.toBits())
        encoded.putInt(y.toBits())
        encoded.putInt(z.toBits())
        if (size + encoded.position() > bytes.size) {
            val old = bytes
            bytes = old.copyOf(old.size * 2)
            old.fill(0)
        }
        encoded.array().copyInto(bytes, size, 0, encoded.position())
        size += encoded.position()
        samples++
        return true
    }

    @Synchronized
    fun end(nowNanos: Long) {
        val start = checkNotNull(startedAtNanos) { "陀螺仪尚未开始采集" }
        check(!finished && endedAtNanos == null && nowNanos >= start) { "陀螺仪采集顺序无效" }
        endedAtNanos = nowNanos
    }

    @Synchronized
    fun finish(): ByteArray {
        check(!finished && endedAtNanos != null) { "陀螺仪采集未完成" }
        require(samples == REQUIRED_SAMPLES) { "陀螺仪采样不足" }
        return bytes.copyOf(size).also { clear() }
    }

    @Synchronized
    fun clear() {
        finished = true
        bytes.fill(0)
        encoded.array().fill(0)
        size = 0
    }

    companion object {
        const val REQUIRED_SAMPLES = 500
        private const val MIN_CHANGE_RADIANS_PER_SECOND = 0.0001f
    }
}
