package com.micro.airvault

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.micro.airprotocol.BitcoinAddressType
import org.bitcoinj.crypto.ChildNumber

/** The format determines address encoding, independently of the chain's display name. */
internal enum class CustomAddressFormat(
    val label: String, val chain: WalletChain, val bitcoinType: BitcoinAddressType? = null
) {
    EVM("EVM (0x)", WalletChain.ETHEREUM),
    BITCOIN_LEGACY("Bitcoin Legacy", WalletChain.BITCOIN, BitcoinAddressType.LEGACY),
    BITCOIN_SEGWIT("Bitcoin Segwit", WalletChain.BITCOIN, BitcoinAddressType.SEGWIT),
    BITCOIN_TAPROOT("Bitcoin Taproot", WalletChain.BITCOIN, BitcoinAddressType.TAPROOT),
    SOLANA("Solana", WalletChain.SOLANA),
    TRON("TRON", WalletChain.TRON),
    ZCASH("Zcash 透明地址", WalletChain.ZCASH);

    val examplePath: String get() = bitcoinType?.derivationPath ?: chain.derivationPath
}

@Entity(tableName = "custom_chains", indices = [Index(value = ["name"], unique = true)])
internal data class CustomChain(
    @PrimaryKey val id: String,
    val name: String,
    val derivationPath: String,
    val addressFormat: CustomAddressFormat,
    val createdAt: Long,
    @ColumnInfo(name = "chain_id", defaultValue = "NULL") val chainId: Long? = null
)

internal object CustomChainValidation {
    const val MAX_NAME_LENGTH = 30

    fun chainId(value: String, format: CustomAddressFormat): Long? {
        if (format != CustomAddressFormat.EVM) return null
        val normalized = value.trim()
        val id = normalized.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
        require(id != null && id > 0) { "ChainId 须为 1–9223372036854775807 的十进制整数" }
        return id
    }

    fun name(value: String): String = value.trim().also { name ->
        require(name.length in 1..MAX_NAME_LENGTH) { "链名称须为 1–30 个字符" }
        require(WalletAccount.all.none { it.title.equals(name, ignoreCase = true) }) { "链名称与已有链重复" }
    }

    fun path(value: String, format: CustomAddressFormat): String {
        val components = components(value)
        require(format != CustomAddressFormat.SOLANA || components.all { it.isHardened }) {
            "Solana 的派生路径每一段都需要使用强化派生（例如 0'）"
        }
        return "m/" + components.joinToString("/") { "${it.num()}" + if (it.isHardened) "'" else "" }
    }

    fun components(value: String): List<ChildNumber> {
        val path = value.trim()
        require(path.length <= 256 && path.startsWith("m/")) { "派生路径须以 m/ 开头" }
        val parts = path.removePrefix("m/").split('/')
        require(parts.size in 2..32) { "派生路径须包含分支和地址序号，且最多 32 段" }
        return parts.map { part ->
            require(part.matches(Regex("[0-9]+['hH]?"))) { "派生路径格式无效" }
            val hardened = part.last() in "'hH"
            val index = (if (hardened) part.dropLast(1) else part).toLongOrNull()
            require(index != null && index in 0..Int.MAX_VALUE.toLong()) { "派生路径序号须为 0–2147483647" }
            ChildNumber(index.toInt(), hardened)
        }
    }
}
