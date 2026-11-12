package com.micro.airprotocol

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Shared, versioned binary format for AirWallet and AirVault. Chain IDs are stable wire values. */
data class WireMessage(val chain: Int, val kind: Int, val id: ByteArray, val fields: List<ByteArray>) {
    init {
        require(chain in 0..255 && kind in 0..255 && id.size == 16)
    }
}

object WireCodec {
    const val ADDRESSES = 0
    const val EVM_LEGACY = 1
    const val EVM_1559 = 2
    const val BITCOIN = 3
    const val SOLANA = 4
    const val TRON = 5
    const val BITCOIN_PSBT = 6
    const val RESULT = 128
    private const val MAX_PAYLOAD = 131_072
    private const val CHUNK_SIZE = 520
    // Including the AV1 envelope and Base64URL, this fits a byte-mode QR at level L.
    private const val SINGLE_FRAME_PAYLOAD = 2000
    private const val PREFIX = "AV1:"

    fun newId() = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun encode(message: WireMessage): ByteArray {
        require(message.fields.size <= 64)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x41, 0x56, 1, message.chain.toByte(), message.kind.toByte()))
        out.write(message.id)
        writeVarint(out, message.fields.size)
        message.fields.forEach { writeVarint(out, it.size); out.write(it) }
        return out.toByteArray().also { require(it.size <= MAX_PAYLOAD) { "协议消息过长" } }
    }

    fun decode(bytes: ByteArray): WireMessage {
        require(bytes.size in 22..MAX_PAYLOAD && bytes[0] == 0x41.toByte() &&
            bytes[1] == 0x56.toByte() && bytes[2] == 1.toByte()) { "协议版本或数据无效" }
        val reader = Reader(bytes, 3)
        val chain = reader.byte()
        val kind = reader.byte()
        val id = reader.take(16)
        val count = reader.varint()
        require(count <= 64) { "字段数量无效" }
        val fields = (0 until count).map { reader.take(reader.varint()) }
        require(reader.done()) { "协议消息包含尾随数据" }
        return WireMessage(chain, kind, id, fields)
    }

    fun frames(bytes: ByteArray): List<String> = frameChunks(bytes, CHUNK_SIZE)

    /** Static public-account QR. Transactions continue using the smaller animated frames. */
    fun singleFrame(bytes: ByteArray): String {
        require(bytes.size in 22..SINGLE_FRAME_PAYLOAD) { "公开账户数据过长，无法生成单张二维码" }
        return frameChunks(bytes, SINGLE_FRAME_PAYLOAD).single()
    }

    private fun frameChunks(bytes: ByteArray, chunkSize: Int): List<String> {
        require(bytes.size in 22..MAX_PAYLOAD)
        val count = (bytes.size + chunkSize - 1) / chunkSize
        require(count in 1..255) { "二维码分片过多" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(8)
        val session = bytes.copyOfRange(5, 13)
        return (0 until count).map { index ->
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x41, 0x51, 1, count.toByte(), index.toByte()))
            out.write(session)
            out.write(hash)
            out.write(bytes, index * chunkSize, minOf(chunkSize, bytes.size - index * chunkSize))
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        }
    }

    fun isFrame(text: String) = text.startsWith(PREFIX)

    class Collector {
        private var session: ByteArray? = null
        private var hash: ByteArray? = null
        private var chunks: Array<ByteArray?> = emptyArray()
        var received = 0
            private set
        val total get() = chunks.size

        fun clear() { session = null; hash = null; chunks = emptyArray(); received = 0 }

        fun offer(text: String): ByteArray? {
            require(isFrame(text)) { "非 AirVault 二维码" }
            val raw = Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))
            require(raw.size in 22..(21 + SINGLE_FRAME_PAYLOAD) && raw[0] == 0x41.toByte() &&
                raw[1] == 0x51.toByte() && raw[2] == 1.toByte()) { "二维码分片无效" }
            val count = raw[3].toInt() and 255
            val index = raw[4].toInt() and 255
            require(count in 1..255 && index < count) { "二维码片序无效" }
            require(count == 1 || raw.size <= 21 + CHUNK_SIZE) { "二维码分片过长" }
            val incomingSession = raw.copyOfRange(5, 13)
            val incomingHash = raw.copyOfRange(13, 21)
            if (session == null || !session!!.contentEquals(incomingSession) || !hash!!.contentEquals(incomingHash)) {
                clear()
                session = incomingSession
                hash = incomingHash
                chunks = arrayOfNulls(count)
            }
            require(chunks.size == count) { "二维码总片数不一致" }
            val chunk = raw.copyOfRange(21, raw.size)
            require(chunk.isNotEmpty())
            if (chunks[index] == null) {
                chunks[index] = chunk
                received++
            } else require(chunks[index]!!.contentEquals(chunk)) { "重复分片内容不一致" }
            if (received < count) return null
            val payload = ByteArrayOutputStream().also { output -> chunks.forEach { output.write(it!!) } }.toByteArray()
            require(payload.size <= MAX_PAYLOAD && MessageDigest.getInstance("SHA-256").digest(payload)
                .copyOf(8).contentEquals(incomingHash)) { "二维码内容校验失败" }
            clear()
            return payload
        }
    }

    fun unsigned(bytes: ByteArray): java.math.BigInteger =
        if (bytes.isEmpty()) java.math.BigInteger.ZERO else java.math.BigInteger(1, bytes)

    fun number(value: java.math.BigInteger): ByteArray {
        require(value.signum() >= 0 && value.bitLength() <= 256)
        return if (value.signum() == 0) byteArrayOf() else value.toByteArray().let {
            if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
    }

    private fun writeVarint(out: ByteArrayOutputStream, number: Int) {
        require(number >= 0)
        var value = number
        while (value >= 128) { out.write((value and 127) or 128); value = value ushr 7 }
        out.write(value)
    }

    private class Reader(val data: ByteArray, var offset: Int) {
        fun byte(): Int { require(offset < data.size) { "协议消息截断" }; return data[offset++].toInt() and 255 }
        fun take(size: Int): ByteArray {
            require(size >= 0 && size <= MAX_PAYLOAD && offset <= data.size - size) { "协议字段长度无效" }
            return data.copyOfRange(offset, offset + size).also { offset += size }
        }
        fun varint(): Int {
            var value = 0
            for (shift in 0..21 step 7) {
                val next = byte()
                value = value or ((next and 127) shl shift)
                if (next and 128 == 0) return value
            }
            error("协议整数过长")
        }
        fun done() = offset == data.size
    }
}
