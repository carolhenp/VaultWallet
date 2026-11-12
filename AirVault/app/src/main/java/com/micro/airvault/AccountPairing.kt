package com.micro.airvault

import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoCoinInfo
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import org.bitcoinj.crypto.ECKey
import co.nstant.`in`.cbor.model.Array
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Map
import co.nstant.`in`.cbor.model.UnsignedInteger
import java.math.BigInteger

internal enum class PairingWallet(val label: String) {
    AIRWALLET("AirWallet"), METAMASK("MetaMask"), TRUST_WALLET("TrustWallet");

    fun supports(account: WalletAccount): Boolean = account.customChain == null && when (this) {
        AIRWALLET -> true
        METAMASK -> account.chain.isEvm
        // Checked against Android 26.45.8's View-only wallet network list.
        // Bitcoin and Zcash are absent even though wallet-core can encode their addresses.
        TRUST_WALLET -> when (account.chain) {
            WalletChain.ETHEREUM, WalletChain.SOLANA, WalletChain.TRON,
            WalletChain.BNB_CHAIN, WalletChain.POLYGON, WalletChain.ARBITRUM,
            WalletChain.OPTIMISM, WalletChain.BASE, WalletChain.AVALANCHE,
            WalletChain.GNOSIS, WalletChain.CRONOS, WalletChain.SONIC -> true
            WalletChain.BITCOIN, WalletChain.ZCASH -> false
        }
    }
}

internal object AccountPairing {
    fun frames(target: PairingWallet, account: WalletAccount, catalog: WalletPublicCatalog): List<String> {
        require(target.supports(account)) { "${target.label} 不支持 ${account.title} 账号同步" }
        val selected = catalog.address(account)
        return when (target) {
            PairingWallet.AIRWALLET -> WireBridge.account(account.chain, selected)
            PairingWallet.TRUST_WALLET -> listOf(selected.address)
            PairingWallet.METAMASK -> {
                // MetaMask QR keyring ACCOUNT mode: an exact public key + origin, never a private key.
                val origin = CryptoKeypath(selected.path.removePrefix("m/").split('/').map {
                    IndexPathComponent(it.removeSuffix("'").toInt(), it.endsWith("'"))
                }, catalog.masterFingerprint)
                val key = ECKey.fromPublicOnly(byteArrayOf(4) + selected.publicKey)
                val hdKey = CryptoHDKey(false, key.pubKeyPoint.getEncoded(true), null,
                    CryptoCoinInfo(CryptoCoinInfo.Type.ETHEREUM, null),
                    origin, null, null, "AirVault", account.chain.label)
                val output = CryptoOutput(listOf(ScriptExpression.PUBLIC_KEY_HASH), hdKey)
                val envelope = object : CryptoAccount(catalog.masterFingerprint, listOf(output)) {
                    override fun toCbor(): DataItem = Map()
                        .put(UnsignedInteger(1), UnsignedInteger(BigInteger(1, catalog.masterFingerprint)))
                        // Keystone's registry expects script/HDKey tags directly, without the optional output tag 308.
                        .put(UnsignedInteger(2), Array().add(output.toCbor()))
                }
                val ur = envelope.toUR()
                val encoder = UREncoder(ur, 250, 10, 0)
                List(encoder.seqLen) { encoder.nextPart().uppercase(java.util.Locale.ROOT) }
            }
        }
    }
}
