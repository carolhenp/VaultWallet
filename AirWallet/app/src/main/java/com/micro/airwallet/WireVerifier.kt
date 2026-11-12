package com.micro.airwallet

import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import com.micro.airprotocol.PsbtV0
import com.micro.airprotocol.BitcoinAddressType
import com.micro.airprotocol.BitcoinSupport
import com.micro.airprotocol.ZcashSupport
import org.bitcoinj.core.Transaction
import org.sol4k.Base58
import org.sol4k.PublicKey
import org.tron.trident.proto.Chain
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.crypto.SignedRawTransaction
import org.web3j.crypto.TransactionDecoder
import org.web3j.crypto.TransactionEncoder
import org.web3j.crypto.transaction.type.Transaction1559
import org.web3j.utils.Numeric
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

object WireVerifier {
    fun responseFromSignedJson(request: WireMessage, text: String): WireMessage {
        require(text.length <= 131_072) { "签名结果过长" }
        val json = JSONObject(text)
        val expectedType = when (request.kind) {
            WireCodec.EVM_LEGACY, WireCodec.EVM_1559 -> "eth_transaction_result"
            WireCodec.BITCOIN, WireCodec.BITCOIN_PSBT -> "btc_transaction_result"
            WireCodec.SOLANA -> "sol_transaction_result"
            WireCodec.TRON -> "tron_transaction_result"
            else -> error("不支持的签名结果")
        }
        require(json.getString("type") == expectedType) { "签名结果类型不匹配" }
        if (request.kind in setOf(WireCodec.EVM_LEGACY, WireCodec.EVM_1559)) {
            require(json.getLong("chainId") == WalletChain.entries[request.chain].chainId) { "签名结果网络不匹配" }
        }
        val encoded = json.getString("signedTransaction")
        val signed = if (request.kind == WireCodec.SOLANA) Base64.getDecoder().decode(encoded)
            else {
                require(encoded.matches(Regex("0x(?:[0-9a-fA-F]{2})+"))) { "签名交易编码无效" }
                Numeric.hexStringToByteArray(encoded)
            }
        require(signed.isNotEmpty()) { "签名交易为空" }
        return WireMessage(request.chain, WireCodec.RESULT, request.id,
            listOf(json.getString("from").toByteArray(Charsets.UTF_8), signed))
    }

    fun importAccounts(message: WireMessage): Map<WalletChain, WatchedAccount> {
        require(message.kind == WireCodec.ADDRESSES) { "观察账户二维码格式无效" }
        val chains = if (message.chain == 255) {
            // Legacy bundles contain the original 13 chains, optionally followed by Zcash.
            require(message.fields.size in setOf(26, WalletChain.entries.size * 2)) { "观察账户二维码格式无效" }
            WalletChain.entries.take(message.fields.size / 2)
        } else {
            require(message.fields.size == 2) { "单链观察账户须包含地址和公钥" }
            listOf(WalletChain.entries.getOrNull(message.chain) ?: error("不支持的网络"))
        }
        return chains.mapIndexed { index, chain ->
            val address = message.fields[index * 2].toString(Charsets.UTF_8)
            val key = message.fields[index * 2 + 1]
            when {
                chain.isEvm -> {
                    require(key.size == 64 && Keys.toChecksumAddress("0x" + Keys.getAddress(BigInteger(1, key))).equals(address, true))
                }
                chain == WalletChain.BITCOIN -> {
                    require(key.size == 33 && key[0] in byteArrayOf(2, 3)) { "Bitcoin 公钥格式无效" }
                    val expected = BitcoinSupport.address(org.bitcoinj.crypto.ECKey.fromPublicOnly(key),
                        BitcoinAddressType.fromAddress(address))
                    require(address == expected)
                }
                chain == WalletChain.SOLANA -> require(key.size == 32 && Base58.encode(key) == address)
                chain == WalletChain.ZCASH -> require(ZcashSupport.address(key) == address) { "Zcash 地址与公钥不匹配" }
                chain == WalletChain.TRON -> {
                    require(key.size == 64 || key.size == 65)
                    require(("41" + Keys.getAddress(BigInteger(1, key.takeLast(64).toByteArray())))
                        .equals(Numeric.toHexStringNoPrefix(org.tron.trident.utils.Base58Check.base58ToBytes(address)), true))
                }
                else -> error("不支持的网络")
            }
            chain to WatchedAccount(address, Numeric.toHexStringNoPrefix(key))
        }.toMap()
    }

    fun verify(request: WireMessage, response: WireMessage, account: WatchedAccount): ByteArray {
        require(response.chain == request.chain && response.kind == WireCodec.RESULT &&
            response.id.contentEquals(request.id) && response.fields.size == 2) { "签名结果与当前请求不匹配" }
        val from = response.fields[0].toString(Charsets.UTF_8)
        require(from.equals(account.address, true)) { "签名账户不是当前观察地址" }
        val signed = response.fields[1]
        require(signed.isNotEmpty())
        when (request.kind) {
            WireCodec.EVM_LEGACY, WireCodec.EVM_1559 -> verifyEvm(request, signed, account)
            WireCodec.BITCOIN, WireCodec.BITCOIN_PSBT -> verifyBitcoin(request, signed, account)
            WireCodec.SOLANA -> verifySolana(request, signed, account)
            WireCodec.TRON -> verifyTron(request, signed, account)
            else -> error("不支持的签名结果")
        }
        return signed
    }

    private fun verifyEvm(request: WireMessage, signed: ByteArray, account: WatchedAccount) {
        val chain = WalletChain.entries[request.chain]
        val fields = request.fields
        val decoded = TransactionDecoder.decode(Numeric.toHexString(signed)) as? SignedRawTransaction
            ?: error("EVM 交易缺少签名")
        val is1559 = request.kind == WireCodec.EVM_1559
        require((decoded.transaction is Transaction1559) == is1559) { "EVM 交易类型不一致" }
        if (is1559) require((decoded.transaction as Transaction1559).chainId == chain.chainId)
        val to = Numeric.toHexString(fields[1])
        require(decoded.nonce == WireCodec.unsigned(fields[0]) && decoded.to.equals(to, true) &&
            decoded.value == WireCodec.unsigned(fields[2]) && decoded.gasLimit == WireCodec.unsigned(fields[3]) &&
            Numeric.hexStringToByteArray(decoded.data).contentEquals(fields.last())) { "EVM 签名交易内容被修改" }
        if (is1559) {
            val tx = decoded.transaction as Transaction1559
            require(tx.maxPriorityFeePerGas == WireCodec.unsigned(fields[4]) && tx.maxFeePerGas == WireCodec.unsigned(fields[5]))
        } else require(decoded.gasPrice == WireCodec.unsigned(fields[4]))
        val signature = decoded.signatureData
        val recId = Sign.getRecId(signature, chain.chainId!!)
        require(recId in 0..3) { "EVM 签名链 ID 无效" }
        val normal = Sign.SignatureData(byteArrayOf((recId + 27).toByte()), signature.r, signature.s)
        val unsigned = if (is1559) TransactionEncoder.encode(decoded)
            else TransactionEncoder.encode(decoded, chain.chainId)
        val key = Sign.signedMessageToKey(unsigned, normal)
        val address = "0x" + Keys.getAddress(key)
        require(address.equals(account.address, true)) { "EVM 签名公钥与观察地址不符" }
        if (account.publicKey.isNotEmpty())
            require(Numeric.toBytesPadded(key, 64).contentEquals(Numeric.hexStringToByteArray(account.publicKey)))
    }

    private fun readBitcoin(bytes: ByteArray): Transaction {
        val buffer = ByteBuffer.wrap(bytes)
        val tx = Transaction.read(buffer)
        require(!buffer.hasRemaining()) { "Bitcoin 交易尾部数据无效" }
        return tx
    }

    private fun verifyBitcoin(request: WireMessage, signed: ByteArray, account: WatchedAccount) {
        val source = if (request.kind == WireCodec.BITCOIN_PSBT) PsbtV0.parse(request.fields.single())
            else PsbtV0.Data(request.fields[0], request.fields.drop(1))
        val unsigned = readBitcoin(source.unsigned)
        val tx = readBitcoin(signed)
        BitcoinSupport.requireSameTransaction(unsigned, tx)
        require(source.previous.size == tx.inputs.size) { "缺少完整前序交易" }
        val key = org.bitcoinj.crypto.ECKey.fromPublicOnly(Numeric.hexStringToByteArray(account.publicKey))
        val type = BitcoinAddressType.fromAddress(account.address)
        require(BitcoinSupport.address(key, type) == account.address) { "Bitcoin 公钥与观察地址不符" }
        val spent = tx.inputs.mapIndexed { index, input ->
            val previous = readBitcoin(source.previous[index])
            require(input.outpoint.hash() == previous.txId && input.outpoint.index() in 0 until previous.outputs.size.toLong())
            previous.outputs[input.outpoint.index().toInt()]
        }
        tx.inputs.indices.forEach { BitcoinSupport.verifyInput(tx, spent, it, key, type) }
    }

    private fun verifySolana(request: WireMessage, signed: ByteArray, account: WatchedAccount) {
        val unsigned = request.fields.single()
        require(signed.size == unsigned.size && signed[0] == 1.toByte() && unsigned[0] == 1.toByte())
        require(signed.copyOfRange(65, signed.size).contentEquals(unsigned.copyOfRange(65, unsigned.size))) {
            "Solana 消息被修改"
        }
        val key = PublicKey(account.address)
        require(key.verify(signed.copyOfRange(1, 65), signed.copyOfRange(65, signed.size))) {
            "Solana 签名无效"
        }
    }

    private fun verifyTron(request: WireMessage, signed: ByteArray, account: WatchedAccount) {
        val unsigned = Chain.Transaction.parseFrom(request.fields.single())
        val tx = Chain.Transaction.parseFrom(signed)
        require(tx.rawData == unsigned.rawData && tx.signatureCount == 1) { "TRON 交易内容被修改" }
        val signature = tx.getSignature(0).toByteArray()
        require(signature.size == 65) { "TRON 签名长度无效" }
        val v = (signature[64].toInt() and 255).let { if (it < 27) it + 27 else it }
        val data = Sign.SignatureData(byteArrayOf(v.toByte()), signature.copyOfRange(0, 32), signature.copyOfRange(32, 64))
        val hash = MessageDigest.getInstance("SHA-256").digest(tx.rawData.toByteArray())
        val publicKey = Sign.signedMessageHashToKey(hash, data)
        val derived = "41" + Keys.getAddress(publicKey)
        val expected = Numeric.toHexStringNoPrefix(org.tron.trident.utils.Base58Check.base58ToBytes(account.address))
        require(derived.equals(expected, true)) { "TRON 签名公钥与观察地址不符" }
    }
}
