package com.micro.airvault

import org.tron.trident.utils.Base58Check
import org.web3j.utils.Numeric
import java.math.BigInteger

internal object ContractReview {
    fun decode(data: String, chain: WalletChain): List<Pair<String, String>> {
        val body = data.removePrefix("0x")
        if (body.length < 8 || body.length % 2 != 0) return emptyList()
        val selector = body.substring(0, 8).lowercase()
        val arguments = body.substring(8)
        fun word(index: Int): String? = arguments.drop(index * 64).take(64).takeIf { it.length == 64 }
        fun address(index: Int): String? = word(index)?.let { hex ->
            val bytes = Numeric.hexStringToByteArray(hex.takeLast(40))
            if (chain == WalletChain.TRON) Base58Check.bytesToBase58(byteArrayOf(0x41) + bytes)
            else "0x" + hex.takeLast(40)
        }
        fun amount(index: Int): String? = word(index)?.let { BigInteger(it, 16).toString() }
        return when (selector) {
            "a9059cbb" -> listOfNotNull(
                "调用方法" to "transfer(address,uint256)",
                address(0)?.let { "代币接收地址" to it },
                amount(1)?.let { "代币数量（最小单位）" to it }
            )
            "095ea7b3" -> listOfNotNull(
                "调用方法" to "approve(address,uint256)",
                address(0)?.let { "授权给" to it },
                amount(1)?.let { "授权额度（最小单位）" to it }
            )
            "23b872dd" -> listOfNotNull(
                "调用方法" to "transferFrom(address,address,uint256)",
                address(0)?.let { "转出地址" to it },
                address(1)?.let { "接收地址" to it },
                amount(2)?.let { "数量（最小单位）" to it }
            )
            "42842e0e", "b88d4fde" -> listOfNotNull(
                "调用方法" to "safeTransferFrom(address,address,uint256)",
                address(0)?.let { "转出地址" to it },
                address(1)?.let { "接收地址" to it },
                amount(2)?.let { "Token ID" to it }
            )
            "a22cb465" -> listOfNotNull(
                "调用方法" to "setApprovalForAll(address,bool)",
                address(0)?.let { "授权给" to it },
                word(1)?.let { "授权状态" to if (BigInteger(it, 16) != BigInteger.ZERO) "全部授权" else "撤销授权" }
            )
            else -> listOf("方法选择器" to "0x$selector（未识别）")
        }
    }
}
