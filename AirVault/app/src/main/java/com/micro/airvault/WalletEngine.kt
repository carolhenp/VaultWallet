package com.micro.airvault

import org.json.JSONObject
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger

sealed interface SigningRequest {
    data class Message(val text: String, val chainId: Long = 1) : SigningRequest
    data class ChainTransaction(
        val chain: WalletChain,
        val payload: String,
        val previousTransactions: List<String> = emptyList(),
        val review: List<Pair<String, String>>,
        val warning: String = ""
    ) : SigningRequest
    data class Transaction(
        val chainId: Long,
        val nonce: BigInteger,
        val to: String,
        val value: BigInteger,
        val gasLimit: BigInteger,
        val gasPrice: BigInteger,
        val data: String
    ) : SigningRequest
    data class Eip1559Transaction(
        val chainId: Long,
        val nonce: BigInteger,
        val to: String,
        val value: BigInteger,
        val gasLimit: BigInteger,
        val maxPriorityFeePerGas: BigInteger,
        val maxFeePerGas: BigInteger,
        val data: String
    ) : SigningRequest
}

object WalletEngine {
    private val derivationPath = intArrayOf(
        44 or Bip32ECKeyPair.HARDENED_BIT,
        60 or Bip32ECKeyPair.HARDENED_BIT,
        0 or Bip32ECKeyPair.HARDENED_BIT,
        0,
        0
    )

    fun generateMnemonic(entropy: ByteArray): String {
        return try {
            require(entropy.size == 32) { "助记词熵长度无效" }
            MnemonicUtils.generateMnemonic(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    fun normalizeMnemonic(input: String): String {
        val mnemonic = input.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        require(mnemonic.split(' ').size in setOf(12, 15, 18, 21, 24)) { "助记词长度应为 12、15、18、21 或 24 个单词" }
        MnemonicUtils.generateEntropy(mnemonic)
        return mnemonic
    }

    fun address(mnemonic: String): String = address(mnemonic, WalletChain.ETHEREUM)

    internal fun address(mnemonic: String, chain: WalletChain): String = when (chain) {
        WalletChain.BITCOIN, WalletChain.SOLANA, WalletChain.TRON -> NonEvmWallet.address(mnemonic, chain)
        else -> credentials(mnemonic).address
    }

    internal fun addresses(mnemonic: String): Map<WalletChain, String> {
        val evmAddress = address(mnemonic)
        return WalletChain.entries.associateWith { chain ->
            if (chain.isEvm) evmAddress else address(mnemonic, chain)
        }
    }

    fun parseRequest(input: String): SigningRequest {
        require(input.length <= 131_072) { "请求过长" }
        val json = JSONObject(input)
        return when (json.getString("type")) {
            "eth_personal_sign" -> {
                val message = json.getString("message")
                require(message.isNotEmpty() && message.length <= 4_096) { "消息长度无效" }
                SigningRequest.Message(message, if (json.has("chainId")) chainId(json) else 1)
            }
            "eth_transaction" -> {
                val chainId = chainId(json)
                val nonce = unsigned(json.getString("nonce"))
                val gasLimit = unsigned(json.getString("gasLimit"))
                val gasPrice = unsigned(json.getString("gasPrice"))
                val value = unsigned(json.getString("value"))
                val to = validatedAddress(json.getString("to"))
                val data = json.optString("data", "0x")
                require(chainId > 0 && nonce.bitLength() <= 256 && gasLimit > BigInteger.ZERO && gasLimit.bitLength() <= 256) { "链 ID、nonce 或 gasLimit 无效" }
                require(gasPrice > BigInteger.ZERO && gasPrice.bitLength() <= 256 && value.bitLength() <= 256) { "gasPrice 或 value 无效" }
                require(data.matches(Regex("0x(?:[0-9a-fA-F]{2})*")) && data.length <= 8_194) { "调用数据无效" }
                SigningRequest.Transaction(chainId, nonce, to, value, gasLimit, gasPrice, data)
            }
            "eth_transaction_1559" -> {
                val chainId = chainId(json)
                val nonce = unsigned(json.getString("nonce"))
                val gasLimit = unsigned(json.getString("gasLimit"))
                val maxPriorityFeePerGas = unsigned(json.getString("maxPriorityFeePerGas"))
                val maxFeePerGas = unsigned(json.getString("maxFeePerGas"))
                val value = unsigned(json.getString("value"))
                val to = validatedAddress(json.getString("to"))
                val data = json.optString("data", "0x")
                require(chainId > 0 && nonce.bitLength() <= 256 && gasLimit > BigInteger.ZERO && gasLimit.bitLength() <= 256) { "链 ID、nonce 或 gasLimit 无效" }
                require(maxPriorityFeePerGas >= BigInteger.ZERO && maxFeePerGas > BigInteger.ZERO &&
                    maxPriorityFeePerGas <= maxFeePerGas && maxFeePerGas.bitLength() <= 256 && value.bitLength() <= 256) { "Gas 费用或 value 无效" }
                require(data.matches(Regex("0x(?:[0-9a-fA-F]{2})*")) && data.length <= 8_194) { "调用数据无效" }
                SigningRequest.Eip1559Transaction(chainId, nonce, to, value, gasLimit, maxPriorityFeePerGas, maxFeePerGas, data)
            }
            "btc_transaction", "sol_transaction", "tron_transaction" -> NonEvmWallet.parse(json)
            else -> error("不支持的请求类型")
        }
    }

    internal fun requireChain(request: SigningRequest, chain: WalletChain) {
        when (request) {
            is SigningRequest.ChainTransaction -> require(request.chain == chain) { "请求网络与当前所选网络不一致" }
            is SigningRequest.Message -> require(chain.chainId == request.chainId) { "消息链 ID 与当前网络不一致" }
            is SigningRequest.Transaction -> require(chain.chainId == request.chainId) { "交易链 ID 与当前网络不一致" }
            is SigningRequest.Eip1559Transaction -> require(chain.chainId == request.chainId) { "交易链 ID 与当前网络不一致" }
        }
    }

    fun sign(request: SigningRequest, mnemonic: String): String = sign(request, mnemonic, WalletChain.ETHEREUM)

    internal fun sign(request: SigningRequest, mnemonic: String, chain: WalletChain): String {
        requireChain(request, chain)
        if (request is SigningRequest.ChainTransaction) return NonEvmWallet.sign(request, mnemonic)
        val credentials = credentials(mnemonic)
        val result = JSONObject().put("from", credentials.address)
        when (request) {
            is SigningRequest.Message -> {
                val signature = Sign.signPrefixedMessage(request.text.toByteArray(Charsets.UTF_8), credentials.ecKeyPair)
                result.put("type", "eth_personal_sign_result")
                result.put("chainId", request.chainId)
                result.put("signature", Numeric.toHexString(signature.r + signature.s + signature.v))
            }
            is SigningRequest.Transaction -> {
                val raw = RawTransaction.createTransaction(
                    request.nonce, request.gasPrice, request.gasLimit,
                    request.to, request.value, request.data
                )
                val signed = TransactionEncoder.signMessage(raw, request.chainId, credentials)
                result.put("type", "eth_transaction_result")
                result.put("chainId", request.chainId)
                result.put("signedTransaction", Numeric.toHexString(signed))
                signed.fill(0)
            }
            is SigningRequest.Eip1559Transaction -> {
                val raw = RawTransaction.createTransaction(
                    request.chainId, request.nonce, request.gasLimit, request.to,
                    request.value, request.data, request.maxPriorityFeePerGas, request.maxFeePerGas
                )
                val signed = TransactionEncoder.signMessage(raw, credentials)
                result.put("type", "eth_transaction_result")
                result.put("chainId", request.chainId)
                result.put("signedTransaction", Numeric.toHexString(signed))
                signed.fill(0)
            }
            is SigningRequest.ChainTransaction -> error("非 EVM 请求必须走对应链签名器")
        }
        return result.toString()
    }

    fun ether(wei: BigInteger): String = BigDecimal(wei).movePointLeft(18).stripTrailingZeros().toPlainString()
    fun gwei(wei: BigInteger): String = BigDecimal(wei).movePointLeft(9).stripTrailingZeros().toPlainString()

    private fun credentials(mnemonic: String): Credentials {
        val seed = MnemonicUtils.generateSeed(mnemonic, "")
        return try {
            val master = Bip32ECKeyPair.generateKeyPair(seed)
            Credentials.create(Bip32ECKeyPair.deriveKeyPair(master, derivationPath))
        } finally {
            seed.fill(0)
        }
    }

    internal fun privateKey(mnemonic: String, coinType: Int, purpose: Int = 44): ByteArray {
        val seed = MnemonicUtils.generateSeed(mnemonic, "")
        return try {
            val path = intArrayOf(
                purpose or Bip32ECKeyPair.HARDENED_BIT,
                coinType or Bip32ECKeyPair.HARDENED_BIT,
                0 or Bip32ECKeyPair.HARDENED_BIT,
                0, 0
            )
            Numeric.toBytesPadded(Bip32ECKeyPair.deriveKeyPair(Bip32ECKeyPair.generateKeyPair(seed), path).privateKey, 32)
        } finally {
            seed.fill(0)
        }
    }

    private fun unsigned(text: String): BigInteger {
        val number = if (text.startsWith("0x")) BigInteger(text.substring(2), 16) else BigInteger(text, 10)
        require(number >= BigInteger.ZERO) { "数值不能为负" }
        return number
    }

    private fun chainId(json: JSONObject): Long {
        val value = json.get("chainId").toString()
        require(value.matches(Regex("[1-9][0-9]*"))) { "链 ID 必须是正整数" }
        return value.toLong()
    }

    private fun validatedAddress(address: String): String {
        require(address.matches(Regex("0x[0-9a-fA-F]{40}"))) { "接收地址无效" }
        val body = address.substring(2)
        if (body.any(Char::isLowerCase) && body.any(Char::isUpperCase)) {
            require(Keys.toChecksumAddress(address) == address) { "接收地址校验和错误" }
        }
        return address
    }
}
