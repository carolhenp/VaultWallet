package com.micro.airvault

import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import com.micro.airprotocol.PsbtV0
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.utils.Numeric
import java.util.Base64

internal object WireBridge {
    fun account(chain: WalletChain, derived: DerivedAddress): List<String> {
        val message = WireMessage(chain.ordinal, WireCodec.ADDRESSES, WireCodec.newId(),
            listOf(derived.address.toByteArray(Charsets.UTF_8), derived.publicKey))
        return listOf(WireCodec.singleFrame(WireCodec.encode(message)))
    }

    fun addresses(addresses: Map<WalletChain, String>, publicKeys: Map<WalletChain, ByteArray>): List<String> {
        val message = WireMessage(255, WireCodec.ADDRESSES, WireCodec.newId(),
            WalletChain.entries.flatMap { chain -> listOf(addresses.getValue(chain).toByteArray(Charsets.UTF_8), publicKeys.getValue(chain)) })
        return listOf(WireCodec.singleFrame(WireCodec.encode(message)))
    }

    fun request(message: WireMessage): Pair<WalletChain, String> {
        val chain = WalletChain.entries.getOrNull(message.chain) ?: error("不支持的网络")
        val fields = message.fields
        fun hex(bytes: ByteArray) = Numeric.toHexString(bytes)
        fun number(index: Int) = WireCodec.unsigned(fields[index]).toString()
        val json = when (message.kind) {
            WireCodec.EVM_LEGACY, WireCodec.EVM_1559 -> {
                require(chain.isEvm && fields.size == if (message.kind == WireCodec.EVM_LEGACY) 6 else 7)
                require(fields[1].size == 20) { "EVM 接收地址长度无效" }
                JSONObject().put("type", if (message.kind == WireCodec.EVM_LEGACY) "eth_transaction" else "eth_transaction_1559")
                    .put("chainId", chain.chainId).put("nonce", number(0)).put("to", hex(fields[1]))
                    .put("value", number(2)).put("gasLimit", number(3))
                    .put("data", hex(fields.last()))
                    .apply {
                        if (message.kind == WireCodec.EVM_LEGACY) put("gasPrice", number(4))
                        else { put("maxPriorityFeePerGas", number(4)); put("maxFeePerGas", number(5)) }
                    }
            }
            WireCodec.BITCOIN -> {
                require(chain == WalletChain.BITCOIN && fields.size in 2..33)
                JSONObject().put("type", "btc_transaction").put("unsignedTransaction", hex(fields[0]))
                    .put("previousTransactions", JSONArray(fields.drop(1).map(::hex)))
            }
            WireCodec.BITCOIN_PSBT -> {
                require(chain == WalletChain.BITCOIN && fields.size == 1)
                val parsed = PsbtV0.parse(fields[0])
                JSONObject().put("type", "btc_transaction").put("unsignedTransaction", hex(parsed.unsigned))
                    .put("previousTransactions", JSONArray(parsed.previous.map(::hex)))
            }
            WireCodec.SOLANA -> {
                require(chain == WalletChain.SOLANA && fields.size == 1)
                JSONObject().put("type", "sol_transaction")
                    .put("transaction", Base64.getEncoder().encodeToString(fields[0]))
            }
            WireCodec.TRON -> {
                require(chain == WalletChain.TRON && fields.size == 1)
                JSONObject().put("type", "tron_transaction").put("transaction", hex(fields[0]))
            }
            else -> error("不支持的签名请求类型")
        }
        return chain to json.toString()
    }

    fun resultMessage(request: WireMessage, signedJson: String): WireMessage {
        val json = JSONObject(signedJson)
        val signed = json.getString("signedTransaction")
        val bytes = if (request.kind == WireCodec.SOLANA) Base64.getDecoder().decode(signed)
            else Numeric.hexStringToByteArray(signed)
        return WireMessage(request.chain, WireCodec.RESULT, request.id,
            listOf(json.getString("from").toByteArray(Charsets.UTF_8), bytes))
    }

    fun result(request: WireMessage, signedJson: String): List<String> =
        WireCodec.frames(WireCodec.encode(resultMessage(request, signedJson)))
}
