package com.micro.airvault

import kotlin.math.abs
import kotlin.math.min

internal class AudiblePcmCollector(private val requiredBytes: Int = EntropyCollector.MICROPHONE_BYTES) {
    init { require(requiredBytes > 0 && requiredBytes % 2 == 0) { "PCM 目标长度无效" } }
    private var data: ByteArray? = ByteArray(requiredBytes)
    private var collected = 0

    val progress: Int get() = collected * 100 / requiredBytes
    val complete: Boolean get() = collected == requiredBytes

    fun addIfAudible(buffer: ByteArray, length: Int): Boolean {
        require(length in 0..buffer.size) { "PCM 长度无效" }
        val target = checkNotNull(data) { "采集已结束" }
        if (complete || !hasAudibleSound(buffer, length)) return false
        val accepted = min(length and -2, requiredBytes - collected)
        buffer.copyInto(target, collected, 0, accepted)
        collected += accepted
        return accepted > 0
    }

    fun takeComplete(): ByteArray {
        check(complete) { "有效声音采集不足" }
        return checkNotNull(data).also { data = null }
    }

    fun clear() {
        data?.fill(0)
        data = null
    }
}

internal fun hasAudibleSound(buffer: ByteArray, length: Int): Boolean {
    val samples = length / 2
    if (samples == 0) return false
    var sumSquares = 0L
    var peak = 0
    for (index in 0 until samples) {
        val value = (buffer[index * 2].toInt() and 0xff) or (buffer[index * 2 + 1].toInt() shl 8)
        val sample = value.toShort().toInt()
        peak = maxOf(peak, abs(sample))
        sumSquares += sample.toLong() * sample
    }
    return peak >= 900 && sumSquares >= 300L * 300 * samples
}
