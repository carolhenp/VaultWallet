package com.micro.airvault

import com.google.protobuf.ByteString
import com.micro.airprotocol.BitcoinAddressType
import com.micro.airprotocol.BitcoinSupport
import com.micro.airprotocol.ZcashSupport
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
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
    fun publicKey(mnemonic: String, chain: WalletChain, bitcoinType: BitcoinAddressType = BitcoinAddressType.SEGWIT): ByteArray = when (chain) {
        WalletChain.BITCOIN -> bitcoinKey(mnemonic, bitcoinType).pubKey
        WalletChain.SOLANA -> Base58.decode(solanaKey(mnemonic).publicKey.toBase58())
        WalletChain.TRON -> Numeric.hexStringToByteArray(tronKey(mnemonic).toPublicKey())
        WalletChain.ZCASH -> zcashPublicKey(mnemonic)
        else -> error("不支持的网络")
    }

    fun address(mnemonic: String, chain: WalletChain, bitcoinType: BitcoinAddressType = BitcoinAddressType.SEGWIT): String = when (chain) {
        WalletChain.BITCOIN -> BitcoinSupport.address(bitcoinKey(mnemonic, bitcoinType), bitcoinType)
        WalletChain.SOLANA -> solanaKey(mnemonic).publicKey.toBase58()
        WalletChain.TRON -> tronKey(mnemonic).toBase58CheckAddress()
        WalletChain.ZCASH -> ZcashSupport.address(zcashPublicKey(mnemonic))
        else -> error("不支持的网络")
    }

    fun parse(json: JSONObject): SigningRequest.ChainTransaction = when (json.getString("type")) {
        "btc_transaction" -> parseBitcoin(json)
        "sol_transaction" -> parseSolana(json)
        "tron_transaction" -> parseTron(json)
        else -> error("不支持的请求类型")
    }

    fun sign(request: SigningRequest.ChainTransaction, mnemonic: String, bitcoinType: BitcoinAddressType = BitcoinAddressType.SEGWIT): String = when (request.chain) {
        WalletChain.BITCOIN -> signBitcoin(request, mnemonic, bitcoinType)
        WalletChain.SOLANA -> signSolana(request, mnemonic)
        WalletChain.TRON -> signTron(request, mnemonic)
        else -> error("不支持的网络")
    }

    internal fun bitcoinKey(mnemonic: String, type: BitcoinAddressType): ECKey {
        val privateKey = WalletEngine.privateKey(mnemonic, 0, type.purpose)
        return try { ECKey.fromPrivate(privateKey, true) } finally { SodiumMemory.wipe(privateKey) }
    }

    private fun tronKey(mnemonic: String): TronKeypair {
        val privateKey = WalletEngine.privateKey(mnemonic, 195)
        return try { TronKeypair(Numeric.toHexStringNoPrefix(privateKey)) } finally { SodiumMemory.wipe(privateKey) }
    }

    private fun zcashPublicKey(mnemonic: String): ByteArray {
        val privateKey = WalletEngine.privateKey(mnemonic, 133)
        return try { ECKey.fromPrivate(privateKey, true).pubKey } finally { SodiumMemory.wipe(privateKey) }
    }

    private fun solanaKey(mnemonic: String): SolanaKeypair {
        val seed = MnemonicUtils.generateSeed(mnemonic, "")
        var material = hmac("ed25519 seed".toByteArray(Charsets.US_ASCII), seed)
        SodiumMemory.wipe(seed)
        try {
            for (index in intArrayOf(44, 501, 0, 0)) {
                val data = ByteBuffer.allocate(37).order(ByteOrder.BIG_ENDIAN)
                    .put(0).put(material, 0, 32).putInt(index or Int.MIN_VALUE).array()
                val next = hmac(material.copyOfRange(32, 64), data)
                SodiumMemory.wipe(material)
                SodiumMemory.wipe(data)
                material = next
            }
            val secret = material.copyOfRange(0, 32)
            return try { SolanaKeypair.fromSecretKey(secret) } finally { SodiumMemory.wipe(secret) }
        } finally { SodiumMemory.wipe(material) }
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
        val review = mutableListOf<Pair<String, String>>("类型" to "Bitcoin 交易", "版本" to tx.version.toString(), "Locktime" to tx.lockTime.toString())
        val outpoints = mutableSetOf<String>()
        tx.inputs.forEachIndexed { index, input ->
            val old = prev[index]
            require(input.outpoint.hash() == old.txId) { "输入 ${index + 1} 的前序交易 ID 不匹配" }
            val n = input.outpoint.index()
            require(outpoints.add("${old.txId}:$n")) { "交易包含重复输入" }
            require(n >= 0 && n < old.outputs.size) { "输入 ${index + 1} 的输出索引无效" }
            val output = old.outputs[n.toInt()]
            val type = BitcoinAddressType.fromScript(output.scriptPubKey)
            require(output.value.value > 0) { "输入 ${index + 1} 的金额无效" }
            totalIn = Math.addExact(totalIn, output.value.value)
            review += "输入 ${index + 1}" to "${type.label} · ${old.txId}:$n · ${output.value.toPlainString()} BTC · ${output.scriptPubKey.getToAddress(BitcoinNetwork.MAINNET)}"
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
        val (tx, previousTxs, review) = bitcoinRequest(request)
        val types = tx.inputs.mapIndexed { index, input ->
            BitcoinAddressType.fromScript(previousTxs[index].outputs[input.outpoint.index().toInt()].scriptPubKey)
        }.distinct()
        require(types.size == 1) { "每次签名只支持同一种 Bitcoin 地址类型" }
        return request.copy(review = listOf("地址类型" to types.single().label,
            "派生路径" to types.single().derivationPath) + review, bitcoinAddressType = types.single())
    }

    private fun signBitcoin(request: SigningRequest.ChainTransaction, mnemonic: String, type: BitcoinAddressType): String {
        val (tx, previous) = bitcoinRequest(request)
        val key = bitcoinKey(mnemonic, type)
        val spent = tx.inputs.mapIndexed { index, input -> previous[index].outputs[input.outpoint.index().toInt()] }
        tx.inputs.indices.forEach { BitcoinSupport.signInput(tx, spent, it, key, type) }
        tx.inputs.indices.forEach { BitcoinSupport.verifyInput(tx, spent, it, key, type) }
        return JSONObject().put("type", "btc_transaction_result")
            .put("from", BitcoinSupport.address(key, type))
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
            if (instruction.program == "11111111111111111111111111111111" &&
                instruction.data.size == 12 && ByteBuffer.wrap(instruction.data).order(ByteOrder.LITTLE_ENDIAN).int == 2 &&
                instruction.accounts.size >= 2) {
                val lamports = ByteBuffer.wrap(instruction.data, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
                review += "指令 ${index + 1} · 接收地址" to instruction.accounts[1]
                review += "指令 ${index + 1} · 转账金额" to "${lamports} lamports"
            }
            if (instruction.program in setOf("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb") &&
                instruction.data.size >= 9 && instruction.data[0].toInt() in setOf(3, 12)) {
                val quantity = ByteBuffer.wrap(instruction.data, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
                review += "指令 ${index + 1} · SPL 数量" to "$quantity 最小单位"
                if (instruction.accounts.size >= 2) review += "指令 ${index + 1} · 目标代币账户" to
                    instruction.accounts[if (instruction.data[0].toInt() == 12 && instruction.accounts.size >= 3) 2 else 1]
            }
        }
        return SigningRequest.ChainTransaction(WalletChain.SOLANA, payload, review = review,
            warning = "常见转账指令会显示可识别字段；其他代币与合约指令仍需核对程序地址、账户和原始数据。")
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
                review += ContractReview.decode(Numeric.toHexString(value.data.toByteArray()), WalletChain.TRON)
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
            warning = "常见代币调用会显示可识别字段；请核对合约地址、原始数据及实际执行效果。")
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
