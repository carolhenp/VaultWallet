package com.micro.airprotocol

import org.bitcoinj.core.Transaction
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BIP-174 v0 subset for P2PKH, P2WPKH and BIP-86 P2TR, with full previous transactions. */
object PsbtV0 {
    private val magic = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
    data class Data(val unsigned: ByteArray, val previous: List<ByteArray>)

    fun create(unsigned: ByteArray, previous: List<ByteArray>): ByteArray {
        val tx = readTx(unsigned)
        require(tx.inputs.size == previous.size && tx.inputs.isNotEmpty() && tx.outputs.isNotEmpty())
        require(!tx.hasWitnesses() && tx.inputs.all { it.scriptBytes.isEmpty() })
        val out = ByteArrayOutputStream()
        out.write(magic)
        entry(out, byteArrayOf(0), unsigned)
        out.write(0)
        previous.forEachIndexed { index, raw ->
            val old = readTx(raw)
            val input = tx.inputs[index]
            require(input.outpoint.hash() == old.txId && input.outpoint.index() < old.outputs.size)
            val output = old.outputs[input.outpoint.index().toInt()]
            entry(out, byteArrayOf(0), raw)
            val serializedOutput = ByteArrayOutputStream().apply {
                write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(output.value.value).array())
                compact(this, output.scriptBytes.size)
                write(output.scriptBytes)
            }.toByteArray()
            entry(out, byteArrayOf(1), serializedOutput)
            out.write(0)
        }
        repeat(tx.outputs.size) { out.write(0) }
        return out.toByteArray().also { require(it.size <= 131_072) }
    }

    fun parse(bytes: ByteArray): Data {
        require(bytes.size in 10..131_072 && bytes.copyOfRange(0, 5).contentEquals(magic)) { "PSBT 文件头无效" }
        val reader = Reader(bytes, 5)
        val global = reader.map()
        require(global.size == 1 && global[0].first.contentEquals(byteArrayOf(0))) { "只支持 BIP-174 v0 未签名交易" }
        val unsigned = global[0].second
        val tx = readTx(unsigned)
        require(!tx.hasWitnesses() && tx.inputs.isNotEmpty() && tx.outputs.isNotEmpty() &&
            tx.inputs.all { it.scriptBytes.isEmpty() }) { "PSBT 交易必须未签名" }
        val previous = tx.inputs.mapIndexed { index, input ->
            val entries = reader.map()
            val full = entries.singleOrNull { it.first.contentEquals(byteArrayOf(0)) }?.second
                ?: error("输入 ${index + 1} 缺少完整前序交易")
            val old = readTx(full)
            require(input.outpoint.hash() == old.txId && input.outpoint.index() < old.outputs.size) {
                "输入 ${index + 1} 的前序交易不匹配"
            }
            val output = old.outputs[input.outpoint.index().toInt()]
            val type = BitcoinAddressType.fromScript(output.scriptPubKey)
            entries.forEach { (key, value) ->
                when (key[0].toInt() and 255) {
                    0, 1, 0x17 -> require(key.size == 1) { "PSBT 输入字段无效" }
                    3 -> {
                        require(key.size == 1 && value.size == 4) { "PSBT sighash 字段无效" }
                        val sighash = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).int
                        require(sighash == if (type == BitcoinAddressType.TAPROOT) 0 else 1) {
                            "只支持 Legacy/SegWit SIGHASH_ALL 和 Taproot SIGHASH_DEFAULT"
                        }
                    }
                    6, 0x16 -> Unit // Derivation metadata never overrides the selected wallet's key.
                    else -> error("不支持此 PSBT 输入字段，仅支持未签名的单密钥交易")
                }
            }
            entries.singleOrNull { it.first.contentEquals(byteArrayOf(1)) }?.second?.let { witness ->
                val expected = ByteArrayOutputStream().apply {
                    write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(output.value.value).array())
                    compact(this, output.scriptBytes.size)
                    write(output.scriptBytes)
                }.toByteArray()
                require(witness.contentEquals(expected)) { "输入 ${index + 1} 的 witness UTXO 不匹配" }
            }
            full
        }
        repeat(tx.outputs.size) { reader.map() }
        require(reader.done()) { "PSBT 包含尾随数据" }
        return Data(unsigned, previous)
    }

    private fun readTx(bytes: ByteArray): Transaction {
        val buffer = ByteBuffer.wrap(bytes)
        val tx = Transaction.read(buffer)
        require(!buffer.hasRemaining()) { "Bitcoin 交易包含尾随数据" }
        return tx
    }

    private fun entry(out: ByteArrayOutputStream, key: ByteArray, value: ByteArray) {
        compact(out, key.size)
        out.write(key)
        compact(out, value.size)
        out.write(value)
    }

    private fun compact(out: ByteArrayOutputStream, value: Int) {
        require(value >= 0)
        when {
            value < 253 -> out.write(value)
            value <= 65535 -> { out.write(253); out.write(value and 255); out.write(value ushr 8) }
            else -> { out.write(254); repeat(4) { out.write(value ushr (8 * it) and 255) } }
        }
    }

    private class Reader(val data: ByteArray, var offset: Int) {
        fun compact(): Int {
            val first = byte()
            val value = when (first) {
                253 -> byte() or (byte() shl 8)
                254 -> (0..3).sumOf { byte().toLong() shl (8 * it) }.toInt()
                255 -> error("PSBT 长度过大")
                else -> first
            }
            require(value in 0..131_072) { "PSBT 长度无效" }
            return value
        }
        fun byte(): Int { require(offset < data.size) { "PSBT 数据截断" }; return data[offset++].toInt() and 255 }
        fun take(size: Int): ByteArray {
            require(size >= 0 && offset <= data.size - size) { "PSBT 字段截断" }
            return data.copyOfRange(offset, offset + size).also { offset += size }
        }
        fun map(): List<Pair<ByteArray, ByteArray>> {
            val entries = mutableListOf<Pair<ByteArray, ByteArray>>()
            while (true) {
                val length = compact()
                if (length == 0) return entries
                val key = take(length)
                require(entries.none { it.first.contentEquals(key) }) { "PSBT 字段重复" }
                entries += key to take(compact())
                require(entries.size <= 128) { "PSBT 字段过多" }
            }
        }
        fun done() = offset == data.size
    }
}
