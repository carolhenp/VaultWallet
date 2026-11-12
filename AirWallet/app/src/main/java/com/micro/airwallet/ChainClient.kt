package com.micro.airwallet

import com.micro.airprotocol.BitcoinAddressType

import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import com.micro.airprotocol.PsbtV0
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import org.bitcoinj.base.AddressParser
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptBuilder
import org.sol4k.Base58
import org.sol4k.PublicKey
import org.sol4k.Constants
import org.sol4k.instruction.CreateAssociatedTokenAccountInstruction
import org.sol4k.instruction.CreateAssociatedToken2022AccountInstruction
import org.sol4k.instruction.SplTransferInstruction
import org.sol4k.instruction.Token2022TransferInstruction
import org.sol4k.instruction.Instruction
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import java.math.BigDecimal
import java.math.BigInteger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

data class ChainSnapshot(val balance: String, val history: List<HistoryEntry>)
enum class TransferMode { NATIVE, TOKEN, CONTRACT }
data class TransferDraft(
    val mode: TransferMode = TransferMode.NATIVE,
    val recipient: String = "",
    val amount: String = "",
    val tokenContract: String = "",
    val tokenDecimals: String = "18",
    val data: String = "0x",
    val nonce: String = "",
    val gasLimit: String = "",
    val fee: String = "",
    val priorityFee: String = ""
)

class ChainClient(private val store: WatchStore) {
    private fun request(url: String, body: String? = null, contentType: String = "application/json"): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 18_000
            requestMethod = if (body == null) "GET" else "POST"
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", contentType) }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_000_000) { "节点响应过长" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            val text = bytes.toString(Charsets.UTF_8)
            require(connection.responseCode in 200..299) { "节点 HTTP ${connection.responseCode}: ${text.take(180)}" }
            text
        } finally { connection.disconnect() }
    }

    private fun rpc(chain: WalletChain, method: String, params: JSONArray = JSONArray()): Any {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val reply = JSONObject(request(store.endpoint(chain), body.toString()))
        require(!reply.has("error")) { reply.getJSONObject("error").optString("message", "节点错误") }
        return reply.get("result")
    }

    fun verifyNetwork(chain: WalletChain) {
        if (chain.isEvm) {
            val actual = Numeric.decodeQuantity(rpc(chain, "eth_chainId") as String).toLong()
            require(actual == chain.chainId) { "节点连接到链 $actual，预期 ${chain.chainId}" }
        }
    }

    fun refresh(chain: WalletChain, address: String): ChainSnapshot = when {
        chain.isEvm -> refreshEvm(chain, address)
        chain == WalletChain.BITCOIN -> refreshBitcoin(chain, address)
        chain == WalletChain.SOLANA -> refreshSolana(chain, address)
        chain == WalletChain.TRON -> refreshTron(chain, address)
        else -> error("此网络目前仅支持地址查看，暂不支持链上查询")
    }

    private fun refreshEvm(chain: WalletChain, address: String): ChainSnapshot {
        verifyNetwork(chain)
        val wei = Numeric.decodeQuantity(rpc(chain, "eth_getBalance", JSONArray().put(address).put("latest")) as String)
        val history = if (chain.explorer.isEmpty()) store.history(chain, address) else runCatching {
            val json = JSONObject(request("${chain.explorer}/api/v2/addresses/$address/transactions"))
            val items = json.getJSONArray("items")
            (0 until minOf(items.length(), 30)).map { i ->
                val tx = items.getJSONObject(i)
                val incoming = tx.optJSONObject("to")?.optString("hash")?.equals(address, true) == true
                val timestamp = runCatching { Instant.parse(tx.getString("timestamp")).epochSecond }.getOrDefault(0)
                HistoryEntry(tx.getString("hash"), amount(BigInteger(tx.optString("value", "0")), 18) + " ${chain.symbol}",
                    if (incoming) "收入" else "支出", timestamp)
            }
        }.getOrElse { store.history(chain, address) }
        return ChainSnapshot(amount(wei, 18), history)
    }

    private fun refreshBitcoin(chain: WalletChain, address: String): ChainSnapshot {
        val base = store.endpoint(chain)
        val info = JSONObject(request("$base/address/$address"))
        val confirmed = info.getJSONObject("chain_stats")
        val mempool = info.getJSONObject("mempool_stats")
        val sats = confirmed.getLong("funded_txo_sum") - confirmed.getLong("spent_txo_sum") +
            mempool.getLong("funded_txo_sum") - mempool.getLong("spent_txo_sum")
        val txs = JSONArray(request("$base/address/$address/txs"))
        val history = (0 until minOf(txs.length(), 30)).map { i ->
            val tx = txs.getJSONObject(i)
            val outputs = tx.getJSONArray("vout")
            val inputs = tx.getJSONArray("vin")
            val received = (0 until outputs.length()).sumOf { n ->
                val output = outputs.getJSONObject(n)
                if (output.optString("scriptpubkey_address") == address) output.optLong("value") else 0
            }
            val sent = (0 until inputs.length()).sumOf { n ->
                val input = inputs.getJSONObject(n).optJSONObject("prevout")
                if (input?.optString("scriptpubkey_address") == address) input.optLong("value") else 0
            }
            val net = received - sent
            HistoryEntry(tx.getString("txid"), amount(BigInteger.valueOf(kotlin.math.abs(net)), 8) + " BTC",
                if (net >= 0) "收入" else "支出", tx.optJSONObject("status")?.optLong("block_time") ?: 0)
        }
        return ChainSnapshot(amount(BigInteger.valueOf(sats), 8), history)
    }

    private fun refreshSolana(chain: WalletChain, address: String): ChainSnapshot {
        val account = rpc(chain, "getBalance", JSONArray().put(address).put(JSONObject().put("commitment", "confirmed"))) as JSONObject
        val signatures = rpc(chain, "getSignaturesForAddress", JSONArray().put(address).put(JSONObject().put("limit", 30))) as JSONArray
        val history = (0 until signatures.length()).map { i ->
            val item = signatures.getJSONObject(i)
            HistoryEntry(item.getString("signature"), "", if (item.isNull("err")) "已确认" else "失败", item.optLong("blockTime"))
        }
        return ChainSnapshot(amount(BigInteger.valueOf(account.getLong("value")), 9), history)
    }

    private fun refreshTron(chain: WalletChain, address: String): ChainSnapshot {
        val base = store.endpoint(chain)
        val info = JSONObject(request("$base/v1/accounts/$address"))
        val rows = info.optJSONArray("data")
        val sun = if (rows != null && rows.length() > 0) rows.getJSONObject(0).optLong("balance") else 0
        val txs = JSONObject(request("$base/v1/accounts/$address/transactions?limit=30"))
        val array = txs.optJSONArray("data") ?: JSONArray()
        val history = (0 until array.length()).map { i ->
            val tx = array.getJSONObject(i)
            HistoryEntry(tx.getString("txID"), "", "交易", tx.optLong("block_timestamp") / 1000)
        }
        return ChainSnapshot(amount(BigInteger.valueOf(sun), 6), history)
    }

    fun prepareEvm(chain: WalletChain, from: String, draft: TransferDraft): WireMessage {
        require(chain.isEvm)
        verifyNetwork(chain)
        val recipient = checkedEvmAddress(draft.recipient)
        val nonce = if (draft.nonce.isBlank()) Numeric.decodeQuantity(rpc(chain, "eth_getTransactionCount",
            JSONArray().put(from).put("pending")) as String) else decimal(draft.nonce)
        val value = if (draft.mode == TransferMode.TOKEN) BigInteger.ZERO else units(draft.amount.ifBlank { "0" }, 18)
        val to = if (draft.mode == TransferMode.TOKEN) checkedEvmAddress(draft.tokenContract) else recipient
        val data = when (draft.mode) {
            TransferMode.NATIVE -> "0x"
            TransferMode.CONTRACT -> checkedHex(draft.data)
            TransferMode.TOKEN -> {
                val result = rpc(chain, "eth_call", JSONArray().put(JSONObject()
                    .put("to", to).put("data", "0x313ce567")).put("latest")) as String
                val onChainDecimals = Numeric.decodeQuantity(result)
                require(onChainDecimals in BigInteger.ZERO..BigInteger.valueOf(36)) { "链上代币精度无效" }
                val decimals = onChainDecimals.toInt()
                if (draft.tokenDecimals.isNotBlank())
                    require(draft.tokenDecimals.toInt() == decimals) { "输入的代币精度与链上合约不一致" }
                val tokens = units(draft.amount, decimals)
                "0xa9059cbb" + recipient.removePrefix("0x").padStart(64, '0') + tokens.toString(16).padStart(64, '0')
            }
        }
        val call = JSONObject().put("from", from).put("to", to).put("value", Numeric.encodeQuantity(value)).put("data", data)
        val gasLimit = if (draft.gasLimit.isBlank()) Numeric.decodeQuantity(rpc(chain, "eth_estimateGas", JSONArray().put(call)) as String)
            else decimal(draft.gasLimit)
        require(gasLimit > BigInteger.ZERO && gasLimit.bitLength() <= 256)
        val block = rpc(chain, "eth_getBlockByNumber", JSONArray().put("latest").put(false)) as JSONObject
        val gasPrice = if (draft.fee.isBlank()) Numeric.decodeQuantity(rpc(chain, "eth_gasPrice") as String)
            else units(draft.fee, 9)
        val id = WireCodec.newId()
        val base = listOf(WireCodec.number(nonce), Numeric.hexStringToByteArray(to), WireCodec.number(value), WireCodec.number(gasLimit))
        return if (block.has("baseFeePerGas") && !block.isNull("baseFeePerGas")) {
            val priority = if (draft.priorityFee.isBlank()) BigInteger.valueOf(1_000_000_000L) else units(draft.priorityFee, 9)
            val maxFee = if (draft.fee.isBlank()) gasPrice.max(Numeric.decodeQuantity(block.getString("baseFeePerGas")) * BigInteger.valueOf(2) + priority)
                else gasPrice
            require(maxFee >= priority && maxFee > BigInteger.ZERO)
            WireMessage(chain.ordinal, WireCodec.EVM_1559, id, base + listOf(WireCodec.number(priority), WireCodec.number(maxFee), Numeric.hexStringToByteArray(data)))
        } else {
            require(gasPrice > BigInteger.ZERO)
            WireMessage(chain.ordinal, WireCodec.EVM_LEGACY, id, base + listOf(WireCodec.number(gasPrice), Numeric.hexStringToByteArray(data)))
        }
    }

    fun prepareBitcoin(from: String, draft: TransferDraft): WireMessage {
        val chain = WalletChain.BITCOIN
        require(draft.mode == TransferMode.NATIVE) { "Bitcoin 合约类输出请导入已构建的原始交易" }
        val destination = AddressParser.getDefault(BitcoinNetwork.MAINNET).parseAddress(draft.recipient)
        val changeAddress = AddressParser.getDefault(BitcoinNetwork.MAINNET).parseAddress(from)
        val addressType = BitcoinAddressType.fromAddress(from)
        val outputVbytes = 8L + 1 + ScriptBuilder.createOutputScript(destination).program().size
        val changeVbytes = 8L + 1 + ScriptBuilder.createOutputScript(changeAddress).program().size
        val amount = units(draft.amount, 8).longValueExact()
        require(amount >= 546) { "BTC 金额低于粉尘阈值" }
        val rate = if (draft.fee.isBlank()) 5L else BigDecimal(draft.fee).longValueExact()
        require(rate in 1..10_000) { "费率应为 1 至 10000 sat/vB" }
        val utxos = JSONArray(request("${store.endpoint(chain)}/address/$from/utxo"))
        val tx = Transaction()
        tx.addOutput(Coin.valueOf(amount), destination)
        var total = 0L
        val previous = mutableListOf<ByteArray>()
        for (i in 0 until minOf(utxos.length(), 32)) {
            val utxo = utxos.getJSONObject(i)
            val raw = Numeric.hexStringToByteArray(request("${store.endpoint(chain)}/tx/${utxo.getString("txid")}/hex"))
            val old = Transaction.read(ByteBuffer.wrap(raw))
            require(old.txId.toString() == utxo.getString("txid")) { "前序交易 ID 校验失败" }
            val vout = utxo.getInt("vout")
            require(vout in old.outputs.indices && old.outputs[vout].value.value == utxo.getLong("value")) { "UTXO 金额不匹配" }
            require(BitcoinAddressType.fromScript(old.outputs[vout].scriptPubKey) == addressType &&
                old.outputs[vout].scriptPubKey.getToAddress(BitcoinNetwork.MAINNET).toString() == from) {
                "UTXO 不属于当前 Bitcoin 地址"
            }
            tx.addInput(old.outputs[vout])
            previous += raw
            total = Math.addExact(total, utxo.getLong("value"))
            val feeWithChange = rate * (11 + addressType.inputVbytes.toLong() * tx.inputs.size + outputVbytes + changeVbytes)
            if (total >= amount + feeWithChange + 546) {
                tx.addOutput(Coin.valueOf(total - amount - feeWithChange), changeAddress)
                break
            }
            val feeWithoutChange = rate * (11 + addressType.inputVbytes.toLong() * tx.inputs.size + outputVbytes)
            if (total >= amount + feeWithoutChange && i == utxos.length() - 1) break
        }
        require(total >= amount + rate * (11 + addressType.inputVbytes.toLong() * tx.inputs.size + outputVbytes)) { "BTC 余额不足或可用 UTXO 超过 32 个" }
        return WireMessage(chain.ordinal, WireCodec.BITCOIN_PSBT, WireCodec.newId(),
            listOf(PsbtV0.create(tx.serialize(), previous)))
    }

    fun prepareSolana(from: String, draft: TransferDraft): WireMessage {
        require(draft.mode != TransferMode.CONTRACT) { "自定义 Solana 合约请导入已编译的未签名交易" }
        val sender = PublicKey(from)
        val recipient = PublicKey(draft.recipient)
        val latest = rpc(WalletChain.SOLANA, "getLatestBlockhash", JSONArray().put(JSONObject().put("commitment", "confirmed"))) as JSONObject
        val blockhash = latest.getJSONObject("value").getString("blockhash")
        val instructions: List<Instruction> = if (draft.mode == TransferMode.NATIVE) {
            val lamports = units(draft.amount, 9).longValueExact()
            require(lamports > 0)
            listOf(org.sol4k.instruction.TransferInstruction(sender, recipient, lamports))
        } else {
            val mint = PublicKey(draft.tokenContract)
            val info = rpc(WalletChain.SOLANA, "getAccountInfo", JSONArray().put(mint.toBase58())
                .put(JSONObject().put("encoding", "base64"))) as JSONObject
            val mintInfo = info.optJSONObject("value") ?: error("代币 mint 不存在")
            val program = PublicKey(mintInfo.getString("owner"))
            require(program == Constants.TOKEN_PROGRAM_ID || program == Constants.TOKEN_2022_PROGRAM_ID) {
                "代币 mint 不属于支持的 Token Program"
            }
            val decimals = (rpc(WalletChain.SOLANA, "getTokenSupply", JSONArray().put(mint.toBase58())) as JSONObject)
                .getJSONObject("value").getInt("decimals")
            require(decimals in 0..18)
            if (draft.tokenDecimals.isNotBlank())
                require(draft.tokenDecimals.toInt() == decimals) { "输入的代币精度与链上 mint 不一致" }
            val amount = units(draft.amount, decimals).longValueExact()
            require(amount > 0)
            val source = PublicKey.findProgramDerivedAddress(sender, mint, program).publicKey
            val destination = PublicKey.findProgramDerivedAddress(recipient, mint, program).publicKey
            val sourceInfo = rpc(WalletChain.SOLANA, "getAccountInfo", JSONArray().put(source.toBase58())
                .put(JSONObject().put("encoding", "base64"))) as JSONObject
            require(!sourceInfo.isNull("value")) { "发送方代币账户不存在" }
            val destinationInfo = rpc(WalletChain.SOLANA, "getAccountInfo", JSONArray().put(destination.toBase58())
                .put(JSONObject().put("encoding", "base64"))) as JSONObject
            buildList {
                if (destinationInfo.isNull("value")) add(if (program == Constants.TOKEN_PROGRAM_ID)
                    CreateAssociatedTokenAccountInstruction(sender, destination, recipient, mint)
                    else CreateAssociatedToken2022AccountInstruction(sender, destination, recipient, mint))
                add(if (program == Constants.TOKEN_PROGRAM_ID)
                    SplTransferInstruction(source, destination, mint, sender, amount, decimals)
                    else Token2022TransferInstruction(source, destination, mint, sender, amount, decimals))
            }
        }
        val transaction = org.sol4k.Transaction(blockhash, instructions, sender).apply {
            addSignature(Base58.encode(ByteArray(64)))
        }.serialize()
        return WireMessage(WalletChain.SOLANA.ordinal, WireCodec.SOLANA, WireCodec.newId(), listOf(transaction))
    }

    fun prepareTron(from: String, draft: TransferDraft): WireMessage {
        val chain = WalletChain.TRON
        fun tronAddress(value: String): String {
            val bytes = org.tron.trident.utils.Base58Check.base58ToBytes(value)
            require(bytes.size == 21 && bytes[0] == 0x41.toByte()) { "TRON 地址无效" }
            return Numeric.toHexStringNoPrefix(bytes)
        }
        val owner = tronAddress(from)
        val result = if (draft.mode == TransferMode.NATIVE) {
            val amount = units(draft.amount, 6).longValueExact()
            require(amount > 0)
            JSONObject(request("${store.endpoint(chain)}/wallet/createtransaction",
                JSONObject().put("owner_address", owner).put("to_address", tronAddress(draft.recipient))
                    .put("amount", amount).toString()))
        } else {
            val contract = tronAddress(if (draft.mode == TransferMode.TOKEN) draft.tokenContract else draft.recipient)
            val data = if (draft.mode == TransferMode.TOKEN) {
                val decimals = draft.tokenDecimals.toInt().also { require(it in 0..36) }
                val quantity = units(draft.amount, decimals)
                "a9059cbb" + tronAddress(draft.recipient).drop(2).padStart(64, '0') + quantity.toString(16).padStart(64, '0')
            } else checkedHex(draft.data).removePrefix("0x")
            val callValue = if (draft.mode == TransferMode.CONTRACT) units(draft.amount.ifBlank { "0" }, 6).longValueExact() else 0
            val limit = if (draft.gasLimit.isBlank()) 100_000_000L else decimal(draft.gasLimit).longValueExact()
            require(limit > 0)
            JSONObject(request("${store.endpoint(chain)}/wallet/triggersmartcontract",
                JSONObject().put("owner_address", owner).put("contract_address", contract).put("data", data)
                    .put("call_value", callValue).put("fee_limit", limit).toString())).getJSONObject("transaction")
        }
        require(result.has("raw_data_hex")) { result.optString("message", "TRON 节点未返回交易") }
        val rawData = Chain.Transaction.raw.parseFrom(Numeric.hexStringToByteArray(result.getString("raw_data_hex")))
        require(rawData.contractCount == 1 && rawData.expiration > rawData.timestamp && rawData.timestamp > 0) {
            "TRON 节点返回的交易无效"
        }
        val contract = rawData.getContract(0)
        if (draft.mode == TransferMode.NATIVE) {
            require(contract.type == Chain.Transaction.Contract.ContractType.TransferContract)
            val transfer = contract.parameter.unpack(Contract.TransferContract::class.java)
            require(Numeric.toHexStringNoPrefix(transfer.ownerAddress.toByteArray()).equals(owner, true) &&
                Numeric.toHexStringNoPrefix(transfer.toAddress.toByteArray()).equals(tronAddress(draft.recipient), true) &&
                transfer.amount == units(draft.amount, 6).longValueExact()) { "TRON 节点返回的转账内容不符" }
        } else {
            require(contract.type == Chain.Transaction.Contract.ContractType.TriggerSmartContract)
            val trigger = contract.parameter.unpack(Contract.TriggerSmartContract::class.java)
            val expectedContract = tronAddress(if (draft.mode == TransferMode.TOKEN) draft.tokenContract else draft.recipient)
            val expectedData = if (draft.mode == TransferMode.TOKEN) {
                val decimals = draft.tokenDecimals.toInt().also { require(it in 0..36) }
                "a9059cbb" + tronAddress(draft.recipient).drop(2).padStart(64, '0') +
                    units(draft.amount, decimals).toString(16).padStart(64, '0')
            } else checkedHex(draft.data).removePrefix("0x")
            val expectedValue = if (draft.mode == TransferMode.CONTRACT)
                units(draft.amount.ifBlank { "0" }, 6).longValueExact() else 0
            val expectedLimit = if (draft.gasLimit.isBlank()) 100_000_000L else decimal(draft.gasLimit).longValueExact()
            require(Numeric.toHexStringNoPrefix(trigger.ownerAddress.toByteArray()).equals(owner, true) &&
                Numeric.toHexStringNoPrefix(trigger.contractAddress.toByteArray()).equals(expectedContract, true) &&
                Numeric.toHexStringNoPrefix(trigger.data.toByteArray()).equals(expectedData, true) &&
                trigger.callValue == expectedValue && rawData.feeLimit == expectedLimit) {
                "TRON 节点返回的合约内容或费用上限不符"
            }
        }
        val transaction = Chain.Transaction.newBuilder().setRawData(rawData).build()
        return WireMessage(chain.ordinal, WireCodec.TRON, WireCodec.newId(), listOf(transaction.toByteArray()))
    }

    fun importCompiled(chain: WalletChain, encoded: String, previous: List<String> = emptyList()): WireMessage {
        val fields = when (chain) {
            WalletChain.BITCOIN -> {
                if (encoded.startsWith("cHNidP")) listOf(java.util.Base64.getDecoder().decode(encoded))
                else {
                    require(previous.isNotEmpty()) { "Bitcoin 需要每个输入的完整前序交易" }
                    listOf(PsbtV0.create(Numeric.hexStringToByteArray(encoded), previous.map(Numeric::hexStringToByteArray)))
                }
            }
            WalletChain.SOLANA -> listOf(java.util.Base64.getDecoder().decode(encoded))
            WalletChain.TRON -> listOf(Numeric.hexStringToByteArray(encoded))
            else -> error("此网络请使用交易编辑器")
        }
        val kind = when (chain) {
            WalletChain.BITCOIN -> WireCodec.BITCOIN_PSBT
            WalletChain.SOLANA -> WireCodec.SOLANA
            WalletChain.TRON -> WireCodec.TRON
            else -> error("不支持的网络")
        }
        return WireMessage(chain.ordinal, kind, WireCodec.newId(), fields)
    }

    fun broadcast(chain: WalletChain, raw: ByteArray): String = when {
        chain.isEvm -> {
            verifyNetwork(chain)
            rpc(chain, "eth_sendRawTransaction", JSONArray().put(Numeric.toHexString(raw))) as String
        }
        chain == WalletChain.BITCOIN -> request("${store.endpoint(chain)}/tx", Numeric.toHexStringNoPrefix(raw), "text/plain")
        chain == WalletChain.SOLANA -> rpc(chain, "sendTransaction", JSONArray()
            .put(java.util.Base64.getEncoder().encodeToString(raw))
            .put(JSONObject().put("encoding", "base64").put("preflightCommitment", "confirmed"))) as String
        chain == WalletChain.TRON -> {
            val tx = Chain.Transaction.parseFrom(raw)
            val txId = Numeric.toHexStringNoPrefix(MessageDigest.getInstance("SHA-256").digest(tx.rawData.toByteArray()))
            val result = JSONObject(request("${store.endpoint(chain)}/wallet/broadcasthex",
                JSONObject().put("transaction", Numeric.toHexStringNoPrefix(raw)).toString()))
            require(result.optBoolean("result")) { result.optString("message", "TRON 广播失败") }
            txId
        }
        else -> error("此网络暂不支持交易广播")
    }

    companion object {
        fun amount(value: BigInteger, decimals: Int): String = BigDecimal(value).movePointLeft(decimals)
            .stripTrailingZeros().toPlainString()
        fun units(input: String, decimals: Int): BigInteger {
            require(input.isNotBlank()) { "请输入金额" }
            val value = BigDecimal(input).movePointRight(decimals).toBigIntegerExact()
            require(value >= BigInteger.ZERO && value.bitLength() <= 256) { "金额无效" }
            return value
        }
        fun decimal(input: String): BigInteger = BigInteger(input).also { require(it >= BigInteger.ZERO && it.bitLength() <= 256) }
        fun checkedEvmAddress(input: String): String {
            require(input.matches(Regex("0x[0-9a-fA-F]{40}"))) { "EVM 地址无效" }
            val body = input.substring(2)
            if (body.any(Char::isLowerCase) && body.any(Char::isUpperCase))
                require(Keys.toChecksumAddress(input) == input) { "地址校验和错误" }
            return input
        }
        fun checkedHex(input: String): String {
            require(input.matches(Regex("0x(?:[0-9a-fA-F]{2})*"))) { "调用数据必须是偶数位十六进制" }
            return input
        }
    }
}
