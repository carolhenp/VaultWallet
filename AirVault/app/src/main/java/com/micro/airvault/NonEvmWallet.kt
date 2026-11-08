package com.micro.airvault

import com.google.protobuf.ByteString
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Coin
import org.bitcoinj.base.ScriptType
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptPattern
import org.json.JSONObject
import org.sol4k.Base58
import org.sol4k.Keypair as SolanaKeypair
import org.tron.trident.core.key.KeyPair as TronKeypair
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import org.tron.trident.utils.Base58Check
import org.web3j.crypto.MnemonicUtils
import org.web3j.utils.Numeric
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object NonEvmWallet {
    fun address(mnemonic: String, chain: WalletChain): String = when (chain) {
        WalletChain.BITCOIN -> bitcoinKey(mnemonic).toAddress(ScriptType.P2WPKH, BitcoinNetwork.MAINNET).toString()
        WalletChain.SOLANA -> solanaKey(mnemonic).publicKey.toBase58()
        WalletChain.TRON -> tronKey(mnemonic).toBase58CheckAddress()
        else -> error("不支持的网络")
    }

    fun parse(json: JSONObject): SigningRequest.ChainTransaction = when (json.getString("type")) {
        "btc_transaction" -> parseBitcoin(json)
        "sol_transaction" -> parseSolana(json)
        "tron_transaction" -> parseTron(json)
        else -> error("不支持的请求类型")
    }

    fun sign(request: SigningRequest.ChainTransaction, mnemonic: String): String = when (request.chain) {
        WalletChain.BITCOIN -> signBitcoin(request, mnemonic)
        WalletChain.SOLANA -> signSolana(request, mnemonic)
        WalletChain.TRON -> signTron(request, mnemonic)
        else -> error("不支持的网络")
    }

    private fun bitcoinKey(mnemonic: String): ECKey {
        val privateKey = WalletEngine.privateKey(mnemonic, 0, 84)
        return try { ECKey.fromPrivate(privateKey, true) } finally { privateKey.fill(0) }
    }

    private fun tronKey(mnemonic: String): TronKeypair {
        val privateKey = WalletEngine.privateKey(mnemonic, 195)
        return try { TronKeypair(Numeric.toHexStringNoPrefix(privateKey)) } finally { privateKey.fill(0) }
    }

    private fun solanaKey(mnemonic: String): SolanaKeypair {
        val seed = MnemonicUtils.generateSeed(mnemonic, "")
        var material = hmac("ed25519 seed".toByteArray(Charsets.US_ASCII), seed)
        seed.fill(0)
        try {
            for (index in intArrayOf(44, 501, 0, 0)) {
                val data = ByteBuffer.allocate(37).order(ByteOrder.BIG_ENDIAN)
                    .put(0).put(material, 0, 32).putInt(index or Int.MIN_VALUE).array()
                val next = hmac(material.copyOfRange(32, 64), data)
                material.fill(0)
                data.fill(0)
                material = next
            }
            val secret = material.copyOfRange(0, 32)
            return try { SolanaKeypair.fromSecretKey(secret) } finally { secret.fill(0) }
        } finally { material.fill(0) }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(key, "HmacSHA512")) }.doFinal(data)

    private fun hex(text: String, maxBytes: Int): ByteArray {
        val body = text.removePrefix("0x")
        require(body.isNotEmpty() && body.length <= maxBytes * 2 && body.length % 2 == 0 &&
            body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "十六进制交易数据无效" }
        return Numeric.hexStringToByteArray(body)
    }

    private fun readBitcoin(text: String): Transaction {
        val buffer = ByteBuffer.wrap(hex(text, 65_536))
        val tx = Transaction.read(buffer)
        require(!buffer.hasRemaining()) { "Bitcoin 交易包含尾随数据" }
        return tx
    }

    private fun bitcoinRequest(request: SigningRequest.ChainTransaction): Triple<Transaction, List<Transaction>, List<Pair<String, String>>> {
        val tx = readBitcoin(request.payload)
        require(tx.inputs.isNotEmpty() && tx.outputs.isNotEmpty() && !tx.isCoinBase) { "Bitcoin 交易输入或输出无效" }
        require(!tx.hasWitnesses() && tx.inputs.all { it.scriptBytes.isEmpty() }) { "只接受未签名的 Bitcoin 交易" }
        require(request.previousTransactions.size == tx.inputs.size) { "每个输入都需要对应的完整前序交易" }
        val prev = request.previousTransactions.map(::readBitcoin)
        var totalIn = 0L
        val review = mutableListOf<Pair<String, String>>("类型" to "Bitcoin P2WPKH 交易", "版本" to tx.version.toString(), "Locktime" to tx.lockTime.toString())
        tx.inputs.forEachIndexed { index, input ->
            val old = prev[index]
            require(input.outpoint.hash() == old.txId) { "输入 ${index + 1} 的前序交易 ID 不匹配" }
            val n = input.outpoint.index()
            require(n >= 0 && n < old.outputs.size) { "输入 ${index + 1} 的输出索引无效" }
            val output = old.outputs[n.toInt()]
            require(ScriptPattern.isP2WPKH(output.scriptPubKey)) { "只支持 P2WPKH 输入" }
            require(output.value.value > 0) { "输入 ${index + 1} 的金额无效" }
            totalIn = Math.addExact(totalIn, output.value.value)
            review += "输入 ${index + 1}" to "${old.txId}:$n · ${output.value.toPlainString()} BTC · ${output.scriptPubKey.getToAddress(BitcoinNetwork.MAINNET)}"
        }
        var totalOut = 0L
        tx.outputs.forEachIndexed { index, output ->
            require(output.value.value >= 0) { "输出 ${index + 1} 的金额无效" }
            totalOut = Math.addExact(totalOut, output.value.value)
            val script = output.scriptPubKey
            val destination = try { script.getToAddress(BitcoinNetwork.MAINNET).toString() }
                catch (_: Exception) { "脚本 ${Numeric.toHexString(output.scriptBytes)}" }
            review += "输出 ${index + 1}" to "${output.value.toPlainString()} BTC · $destination"
        }
        require(totalIn >= totalOut) { "输入总额小于输出总额" }
        review += "网络费用" to "${Coin.valueOf(totalIn - totalOut).toPlainString()} BTC"
        return Triple(tx, prev, review)
    }

    private fun parseBitcoin(json: JSONObject): SigningRequest.ChainTransaction {
        val previous = json.getJSONArray("previousTransactions")
        require(previous.length() in 1..32) { "前序交易数量无效" }
        val request = SigningRequest.ChainTransaction(WalletChain.BITCOIN, json.getString("unsignedTransaction"),
            (0 until previous.length()).map(previous::getString), emptyList(),
            "Bitcoin 代币或脚本输出只显示原始脚本；请逐项核对全部输出及网络费用。")
        return request.copy(review = bitcoinRequest(request).third)
    }

    private fun signBitcoin(request: SigningRequest.ChainTransaction, mnemonic: String): String {
        val (tx, previous) = bitcoinRequest(request)
        val key = bitcoinKey(mnemonic)
        val ownScript = ScriptBuilder.createP2WPKHOutputScript(key)
        tx.inputs.forEachIndexed { index, input ->
            val output = previous[index].outputs[input.outpoint.index().toInt()]
            require(output.scriptPubKey.program().contentEquals(ownScript.program())) { "输入 ${index + 1} 不属于当前钱包" }
            val sig = tx.calculateWitnessSignature(index, key, ScriptBuilder.createP2PKHOutputScript(key),
                output.value, Transaction.SigHash.ALL, false)
            tx.replaceInput(index, input.withWitness(TransactionWitness.redeemP2WPKH(sig, key)))
        }
        return JSONObject().put("type", "btc_transaction_result")
            .put("from", key.toAddress(ScriptType.P2WPKH, BitcoinNetwork.MAINNET).toString())
            .put("signedTransaction", Numeric.toHexString(tx.serialize())).toString()
    }

    private data class SolInstruction(val program: String, val accounts: List<String>, val data: ByteArray)
    private data class SolTransaction(val message: ByteArray, val feePayer: String, val blockhash: String, val instructions: List<SolInstruction>)

    private class ByteReader(private val bytes: ByteArray, var offset: Int = 0) {
        fun byte(): Int {
            require(offset < bytes.size) { "Solana 交易数据截断" }
            return bytes[offset++].toInt() and 0xff
        }
        fun take(count: Int): ByteArray {
            require(count >= 0 && count <= bytes.size - offset) { "Solana 交易数据截断" }
            return bytes.copyOfRange(offset, offset + count).also { offset += count }
        }
        fun length(): Int {
            var result = 0
            for (shift in 0..14 step 7) {
                val b = byte()
                result = result or ((b and 127) shl shift)
                if (b and 128 == 0) return result
            }
            error("Solana 长度编码无效")
        }
        fun done() = offset == bytes.size
    }

    private fun solanaTransaction(encoded: String): SolTransaction {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size in 70..16_384) { "Solana 交易长度无效" }
        val r = ByteReader(bytes)
        require(r.length() == 1 && r.take(64).all { it == 0.toByte() }) { "只接受单签名且尚未签名的 Solana 交易" }
        val message = bytes.copyOfRange(r.offset, bytes.size)
        val version = r.byte()
        if (version and 128 != 0) require(version == 128) { "不支持的 Solana 消息版本" }
        else r.offset--
        require(r.byte() == 1 && r.byte() == 0) { "只支持一个可写签名账户" }
        val readonly = r.byte()
        val count = r.length()
        require(count in 1..64 && readonly < count) { "Solana 账户数量无效" }
        val accounts = (0 until count).map { Base58.encode(r.take(32)) }
        val blockhash = Base58.encode(r.take(32))
        val instructionCount = r.length()
        require(instructionCount in 1..32) { "Solana 指令数量无效" }
        val instructions = (0 until instructionCount).map {
            val program = r.byte()
            require(program < count) { "Solana 程序索引无效" }
            val accountCount = r.length()
            require(accountCount <= 64) { "Solana 指令账户过多" }
            val accountIds = (0 until accountCount).map {
                val index = r.byte()
                require(index < count) { "Solana 指令账户索引无效" }
                accounts[index]
            }
            val data = r.take(r.length().also { require(it <= 8_192) { "Solana 指令数据过长" } })
            SolInstruction(accounts[program], accountIds, data)
        }
        if (version == 128) require(r.length() == 0) { "暂不支持地址查找表交易" }
        require(r.done()) { "Solana 交易包含尾随数据" }
        return SolTransaction(message, accounts[0], blockhash, instructions)
    }

    private fun parseSolana(json: JSONObject): SigningRequest.ChainTransaction {
        val payload = json.getString("transaction")
        val tx = solanaTransaction(payload)
        val review = mutableListOf<Pair<String, String>>("类型" to "Solana 交易", "手续费支付账户" to tx.feePayer,
            "最近区块哈希" to tx.blockhash)
        tx.instructions.forEachIndexed { index, instruction ->
            review += "指令 ${index + 1} · 程序" to instruction.program
            review += "指令 ${index + 1} · 账户" to instruction.accounts.joinToString("\n")
            review += "指令 ${index + 1} · 数据" to Numeric.toHexString(instruction.data)
        }
        return SigningRequest.ChainTransaction(WalletChain.SOLANA, payload, review = review,
            warning = "代币与合约指令按程序地址、账户和原始数据展示；无法从原始数据推断完整执行效果。")
    }

    private fun signSolana(request: SigningRequest.ChainTransaction, mnemonic: String): String {
        val tx = solanaTransaction(request.payload)
        val key = solanaKey(mnemonic)
        require(tx.feePayer == key.publicKey.toBase58()) { "Solana 签名账户不属于当前钱包" }
        val signature = key.sign(tx.message)
        val signed = byteArrayOf(1) + signature + tx.message
        return JSONObject().put("type", "sol_transaction_result").put("from", tx.feePayer)
            .put("signedTransaction", Base64.getEncoder().encodeToString(signed)).toString()
    }

    private data class TronTransaction(val tx: Chain.Transaction, val owner: String, val review: List<Pair<String, String>>)

    private fun tronAddress(bytes: ByteArray): String {
        require(bytes.size == 21 && bytes[0] == 0x41.toByte()) { "TRON 地址无效" }
        return Base58Check.bytesToBase58(bytes)
    }

    private fun tronTransaction(payload: String): TronTransaction {
        val bytes = hex(payload, 65_536)
        val tx = Chain.Transaction.parseFrom(bytes)
        require(tx.signatureCount == 0 && tx.rawData.contractCount == 1) { "只接受尚未签名的单合约 TRON 交易" }
        require(tx.rawData.expiration > tx.rawData.timestamp && tx.rawData.timestamp > 0) { "TRON 交易时间无效" }
        require(tx.rawData.feeLimit >= 0) { "TRON 费用上限无效" }
        val contract = tx.rawData.getContract(0)
        val review = mutableListOf<Pair<String, String>>("类型" to contract.type.name,
            "过期时间" to tx.rawData.expiration.toString(), "费用上限" to "${tx.rawData.feeLimit} SUN")
        val owner = when (contract.type) {
            Chain.Transaction.Contract.ContractType.TransferContract -> {
                val value = contract.parameter.unpack(Contract.TransferContract::class.java)
                require(value.amount > 0) { "TRX 金额无效" }
                review += "接收地址" to tronAddress(value.toAddress.toByteArray())
                review += "金额" to "${value.amount} SUN"
                tronAddress(value.ownerAddress.toByteArray())
            }
            Chain.Transaction.Contract.ContractType.TransferAssetContract -> {
                val value = contract.parameter.unpack(Contract.TransferAssetContract::class.java)
                require(value.amount > 0) { "TRC10 金额无效" }
                review += "资产 ID" to value.assetName.toStringUtf8()
                review += "接收地址" to tronAddress(value.toAddress.toByteArray())
                review += "数量" to value.amount.toString()
                tronAddress(value.ownerAddress.toByteArray())
            }
            Chain.Transaction.Contract.ContractType.TriggerSmartContract -> {
                val value = contract.parameter.unpack(Contract.TriggerSmartContract::class.java)
                require(value.callValue >= 0) { "合约调用金额无效" }
                review += "合约地址" to tronAddress(value.contractAddress.toByteArray())
                review += "附带 TRX" to "${value.callValue} SUN"
                review += "调用数据" to Numeric.toHexString(value.data.toByteArray())
                tronAddress(value.ownerAddress.toByteArray())
            }
            else -> error("暂不支持此 TRON 合约类型：${contract.type}")
        }
        review.add(1, "发起地址" to owner)
        return TronTransaction(tx, owner, review)
    }

    private fun parseTron(json: JSONObject): SigningRequest.ChainTransaction {
        val payload = json.getString("transaction")
        val tx = tronTransaction(payload)
        return SigningRequest.ChainTransaction(WalletChain.TRON, payload, review = tx.review,
            warning = "智能合约调用只展示原始数据；请核对合约地址及实际执行效果。")
    }

    private fun signTron(request: SigningRequest.ChainTransaction, mnemonic: String): String {
        val parsed = tronTransaction(request.payload)
        val key = tronKey(mnemonic)
        require(parsed.owner == key.toBase58CheckAddress()) { "TRON 发起地址不属于当前钱包" }
        val txId = MessageDigest.getInstance("SHA-256").digest(parsed.tx.rawData.toByteArray())
        val signature = TronKeypair.signTransaction(txId, key)
        val signed = parsed.tx.toBuilder().addSignature(ByteString.copyFrom(signature)).build()
        return JSONObject().put("type", "tron_transaction_result").put("from", parsed.owner)
            .put("txId", Numeric.toHexString(txId))
            .put("signedTransaction", Numeric.toHexString(signed.toByteArray())).toString()
    }
}
