package com.micro.airvault

import java.nio.ByteBuffer
import org.bouncycastle.crypto.digests.SHA3Digest

internal class GyroscopeEntropy {
    private val digest = SHA3Digest(256)
    private val encoded = ByteBuffer.allocate(20)
    private var startedAtNanos: Long? = null
    private var endedAtNanos: Long? = null
    private var samples = 0
    private var finished = false

    @Synchronized
    fun begin(nowNanos: Long) {
        check(!finished) { "陀螺仪采集已结束" }
        if (startedAtNanos == null) startedAtNanos = nowNanos
    }

    @Synchronized
    fun addSample(timestampNanos: Long, x: Float, y: Float, z: Float) {
        val start = startedAtNanos ?: return
        if (finished || timestampNanos < start || timestampNanos > (endedAtNanos ?: Long.MAX_VALUE)) return
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "陀螺仪数据无效" }
        encoded.clear()
        encoded.putLong(timestampNanos)
        encoded.putInt(x.toBits())
        encoded.putInt(y.toBits())
        encoded.putInt(z.toBits())
        digest.update(encoded.array(), 0, encoded.position())
        samples++
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
        require(samples >= MIN_SAMPLES) { "陀螺仪采样不足，请重新创建钱包" }
        finished = true
        encoded.array().fill(0)
        return ByteArray(EntropyCollector.HASH_BYTES).also { digest.doFinal(it, 0) }
    }

    @Synchronized
    fun clear() {
        finished = true
        encoded.array().fill(0)
        digest.reset()
    }

    companion object {
        const val MIN_SAMPLES = 3
    }
}
