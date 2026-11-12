package com.micro.airprotocol

import org.bitcoinj.base.AddressParser
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.ScriptType
import org.bitcoinj.base.SegwitAddress
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptPattern
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.BIP340Signer
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class BitcoinAddressType(val label: String, val purpose: Int, val inputVbytes: Int) {
    LEGACY("Legacy", 44, 148),
    SEGWIT("SegWit", 84, 68),
    TAPROOT("Taproot", 86, 58);

    val derivationPath: String get() = "m/$purpose'/0'/0'/0/0"

    companion object {
        fun fromScript(script: Script): BitcoinAddressType = when {
            ScriptPattern.isP2PKH(script) -> LEGACY
            ScriptPattern.isP2WPKH(script) -> SEGWIT
            ScriptPattern.isP2TR(script) -> TAPROOT
            else -> error("只支持 Bitcoin Legacy、原生 SegWit 和 Taproot 输入")
        }

        fun fromAddress(address: String): BitcoinAddressType = fromScript(
            ScriptBuilder.createOutputScript(AddressParser.getDefault(BitcoinNetwork.MAINNET).parseAddress(address)))
    }
}

/** Single-key mainnet P2PKH, P2WPKH and BIP-86 P2TR accounts. */
object BitcoinSupport {
    private val curve get() = BIP340Signer.getDomain()
    fun address(key: ECKey, type: BitcoinAddressType): String = when (type) {
        BitcoinAddressType.LEGACY -> key.toAddress(ScriptType.P2PKH, BitcoinNetwork.MAINNET).toString()
        BitcoinAddressType.SEGWIT -> key.toAddress(ScriptType.P2WPKH, BitcoinNetwork.MAINNET).toString()
        BitcoinAddressType.TAPROOT -> SegwitAddress.fromProgram(BitcoinNetwork.MAINNET, 1, taprootOutputKey(key)).toString()
    }

    fun outputScript(key: ECKey, type: BitcoinAddressType): Script = ScriptBuilder.createOutputScript(
        AddressParser.getDefault(BitcoinNetwork.MAINNET).parseAddress(address(key, type)))

    private fun tweak(key: ECKey): BigInteger = BigInteger(1,
        taggedHash("TapTweak", key.pubKeyPoint.normalize().affineXCoord.encoded)).also {
        require(it < curve.n) { "Taproot tweak 超出范围" }
    }

    fun taprootOutputKey(key: ECKey): ByteArray {
        val internal = curve.curve.decodePoint(byteArrayOf(2) + key.pubKeyPoint.normalize().affineXCoord.encoded)
        val output = internal.add(curve.g.multiply(tweak(key))).normalize()
        require(!output.isInfinity) { "Taproot 公钥无效" }
        return output.affineXCoord.encoded
    }

    /** BIP-341 key-path SIGHASH_DEFAULT, with no annex or script-path extension. */
    fun taprootSignatureHash(tx: Transaction, spent: List<TransactionOutput>, index: Int): ByteArray {
        require(spent.size == tx.inputs.size && index in tx.inputs.indices)
        val message = ByteArrayOutputStream().apply {
            write(0) // epoch
            write(0) // SIGHASH_DEFAULT (commits to every input and output)
            write(little(tx.version, 4))
            write(little(tx.lockTime, 4))
            write(sha256(tx.inputs.fold(ByteArrayOutputStream()) { out, input ->
                out.apply { write(input.outpoint.serialize()) }
            }.toByteArray()))
            write(sha256(spent.fold(ByteArrayOutputStream()) { out, output ->
                out.apply { write(little(output.value.value, 8)) }
            }.toByteArray()))
            write(sha256(spent.fold(ByteArrayOutputStream()) { out, output ->
                out.apply { compact(this, output.scriptBytes.size); write(output.scriptBytes) }
            }.toByteArray()))
            write(sha256(tx.inputs.fold(ByteArrayOutputStream()) { out, input ->
                out.apply { write(little(input.sequenceNumber, 4)) }
            }.toByteArray()))
            write(sha256(tx.outputs.fold(ByteArrayOutputStream()) { out, output ->
                out.apply { write(output.serialize()) }
            }.toByteArray()))
            write(0) // spend_type: key path, no annex
            write(little(index.toLong(), 4))
        }.toByteArray()
        return taggedHash("TapSighash", message)
    }

    fun signInput(tx: Transaction, spent: List<TransactionOutput>, index: Int, key: ECKey, type: BitcoinAddressType) {
        val output = spent[index]
        require(output.scriptBytes.contentEquals(outputScript(key, type).program())) { "输入 ${index + 1} 不属于当前钱包地址" }
        val input = tx.inputs[index]
        when (type) {
            BitcoinAddressType.LEGACY -> {
                val signature = tx.calculateSignature(index, key, output.scriptPubKey, Transaction.SigHash.ALL, false)
                tx.replaceInput(index, input.withScriptSig(ScriptBuilder.createInputScript(signature, key)))
            }
            BitcoinAddressType.SEGWIT -> {
                val signature = tx.calculateWitnessSignature(index, key, ScriptBuilder.createP2PKHOutputScript(key),
                    output.value, Transaction.SigHash.ALL, false)
                tx.replaceInput(index, input.withWitness(TransactionWitness.redeemP2WPKH(signature, key)))
            }
            BitcoinAddressType.TAPROOT -> {
                val point = key.pubKeyPoint.normalize()
                val normalized = if (point.affineYCoord.testBitZero()) curve.n.subtract(key.privKey) else key.privKey
                val tweaked = normalized.add(tweak(key)).mod(curve.n)
                require(tweaked.signum() > 0) { "Taproot 私钥无效" }
                val hash = taprootSignatureHash(tx, spent, index)
                val signer = BIP340Signer().apply {
                    init(true, ECPrivateKeyParameters(tweaked, BIP340Signer.getDomain()))
                    update(hash, 0, hash.size)
                }
                tx.replaceInput(index, input.withWitness(TransactionWitness.of(signer.generateSignature())))
            }
        }
    }

    fun verifyInput(tx: Transaction, spent: List<TransactionOutput>, index: Int, key: ECKey, type: BitcoinAddressType) {
        val input = tx.inputs[index]
        val output = spent[index]
        require(output.scriptBytes.contentEquals(outputScript(key, type).program())) { "Bitcoin 输入与观察地址不符" }
        if (type == BitcoinAddressType.TAPROOT) {
            require(input.scriptBytes.isEmpty() && input.witness.pushCount == 1) { "只支持 Taproot key-path 单签名" }
            val signature = input.witness.getPush(0)
            require(signature.size == 64) { "只支持 Taproot SIGHASH_DEFAULT" }
            val hash = taprootSignatureHash(tx, spent, index)
            val verifier = BIP340Signer().apply {
                init(false, requireNotNull(BIP340Signer.decodePublicKey(taprootOutputKey(key))))
                update(hash, 0, hash.size)
            }
            require(verifier.verifySignature(signature)) { "Taproot Schnorr 签名无效" }
            return
        }
        val bytes = if (type == BitcoinAddressType.LEGACY) {
            require(!input.hasWitness() && input.scriptSig.chunks().size == 2) { "Legacy 输入签名格式无效" }
            require(input.scriptSig.chunks()[1].data.contentEquals(key.pubKey)) { "Legacy 签名公钥不匹配" }
            requireNotNull(input.scriptSig.chunks()[0].data)
        } else {
            require(input.scriptBytes.isEmpty() && input.witness.pushCount == 2) { "SegWit 输入签名格式无效" }
            require(input.witness.getPush(1).contentEquals(key.pubKey)) { "SegWit 签名公钥不匹配" }
            input.witness.getPush(0)
        }
        val signature = TransactionSignature.decodeFromBitcoin(bytes, true, true)
        require(signature.sighashFlags == 1) { "只支持 Bitcoin SIGHASH_ALL" }
        if (type == BitcoinAddressType.LEGACY) {
            require(input.scriptBytes.contentEquals(ScriptBuilder.createInputScript(signature, key).program()))
        }
        val hash = if (type == BitcoinAddressType.LEGACY)
            tx.hashForSignature(index, output.scriptPubKey, Transaction.SigHash.ALL, false)
        else tx.hashForWitnessSignature(index, ScriptBuilder.createP2PKHOutputScript(key), output.value, Transaction.SigHash.ALL, false)
        require(key.verify(hash, signature)) { "Bitcoin ECDSA 签名无效" }
    }

    /** Legacy scriptSig changes txid, so compare every unsigned byte after removing signatures. */
    fun requireSameTransaction(unsigned: Transaction, signed: Transaction) {
        require(!unsigned.hasWitnesses() && unsigned.inputs.all { it.scriptBytes.isEmpty() }) { "原始交易必须未签名" }
        val stripped = Transaction.read(ByteBuffer.wrap(signed.serialize()))
        stripped.inputs.toList().forEachIndexed { index, input ->
            stripped.replaceInput(index, input.withoutWitness().withScriptSig(ScriptBuilder.createEmpty()))
        }
        require(stripped.serialize().contentEquals(unsigned.serialize())) { "Bitcoin 签名交易内容被修改" }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun taggedHash(tag: String, bytes: ByteArray): ByteArray {
        val prefix = sha256(tag.toByteArray(Charsets.US_ASCII))
        return sha256(prefix + prefix + bytes)
    }

    private fun little(value: Long, count: Int) = ByteArray(count) { (value ushr (8 * it)).toByte() }
    private fun compact(out: ByteArrayOutputStream, value: Int) {
        when {
            value < 253 -> out.write(value)
            value <= 65535 -> { out.write(253); out.write(little(value.toLong(), 2)) }
            else -> { out.write(254); out.write(little(value.toLong(), 4)) }
        }
    }
}
