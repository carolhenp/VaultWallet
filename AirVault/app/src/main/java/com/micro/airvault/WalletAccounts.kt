package com.micro.airvault

import com.micro.airprotocol.BitcoinAddressType
import com.micro.airprotocol.BitcoinSupport
import com.micro.airprotocol.ZcashSupport
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.DeterministicKey
import org.sol4k.Keypair
import org.tron.trident.utils.Base58Check
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils
import org.web3j.utils.Numeric
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class WalletAccount(
    val chain: WalletChain, val bitcoinType: BitcoinAddressType? = null, val customChain: CustomChain? = null
) {
    init { require((chain == WalletChain.BITCOIN) == (bitcoinType != null)) }
    val id: String get() = customChain?.let { "custom:${it.id}" } ?: "${chain.name}:${bitcoinType?.name.orEmpty()}"
    val title: String get() = customChain?.name ?: if (bitcoinType == null) chain.label else "${chain.label} ${bitcoinType.label}"
    val addressType: String get() = bitcoinType?.label ?: when {
        chain.isEvm -> "EVM"
        chain == WalletChain.SOLANA -> "Ed25519"
        chain == WalletChain.ZCASH -> "透明地址"
        else -> "BIP44"
    }
    val path: String get() = customChain?.derivationPath ?: bitcoinType?.derivationPath ?: chain.derivationPath
    val supportsSigning: Boolean get() = customChain == null && chain.supportsSigning
    fun path(branch: Int, index: Int): String {
        require(branch in 0..1 && index in 0 until ADDRESS_LIMIT)
        val parts = path.split('/').toMutableList()
        val branchHardened = parts[parts.lastIndex - 1].endsWith("'")
        val indexHardened = parts.last().endsWith("'")
        parts[parts.lastIndex - 1] = "$branch" + if (branchHardened) "'" else ""
        parts[parts.lastIndex] = "$index" + if (indexHardened) "'" else ""
        return parts.joinToString("/")
    }

    companion object {
        fun custom(chain: CustomChain) = WalletAccount(chain.addressFormat.chain, chain.addressFormat.bitcoinType, chain)
        fun including(chains: List<CustomChain>) = all + chains.map(::custom)
        const val ADDRESS_LIMIT = 100
        val all: List<WalletAccount> = WalletChain.entries.flatMap { chain ->
            if (chain == WalletChain.BITCOIN) BitcoinAddressType.entries.map { WalletAccount(chain, it) }
            else listOf(WalletAccount(chain))
        }
    }
}

internal data class DerivedAddress(val address: String, val path: String, val publicKey: ByteArray)

/** Only public keys, chain codes and already derived addresses survive an unlock. */
internal class WalletPublicCatalog(
    val masterFingerprint: ByteArray,
    private val roots: Map<Int, PublicRoot>,
    private val solana: List<List<DerivedAddress>>,
    private val customAddresses: Map<String, CustomAddresses> = emptyMap()
) {
    internal data class PublicRoot(val key: ByteArray, val chainCode: ByteArray)

    internal data class CustomAddresses(val primary: DerivedAddress, val branches: List<List<DerivedAddress>>)

    fun address(account: WalletAccount): DerivedAddress = account.customChain?.let {
        customAddresses.getValue(it.id).primary
    } ?: address(account, 0, 0)

    fun address(account: WalletAccount, branch: Int, index: Int): DerivedAddress {
        val path = account.path(branch, index)
        account.customChain?.let { return customAddresses.getValue(it.id).branches[branch][index] }
        if (account.chain == WalletChain.SOLANA) return solana[branch][index]
        val root = roots.getValue(rootId(account))
        val publicRoot = HDKeyDerivation.createMasterPubKeyFromBytes(root.key, root.chainCode)
        val key = HDKeyDerivation.deriveChildKey(HDKeyDerivation.deriveChildKey(publicRoot, branch), index)
        return encodedAddress(account, key, path)
    }

    fun addresses(account: WalletAccount, branch: Int): List<DerivedAddress> =
        (0 until WalletAccount.ADDRESS_LIMIT).map { address(account, branch, it) }

    companion object {
        private fun rootId(account: WalletAccount): Int = when (account.chain) {
            WalletChain.BITCOIN -> requireNotNull(account.bitcoinType).purpose
            WalletChain.TRON -> 195
            WalletChain.ZCASH -> 133
            else -> 60
        }

        fun derive(mnemonic: String, customChains: List<CustomChain> = emptyList()): WalletPublicCatalog {
            val seed = MnemonicUtils.generateSeed(mnemonic, "")
            return try {
                val master = HDKeyDerivation.createMasterPrivateKey(seed)
                val roots = listOf(44 to 0, 84 to 0, 86 to 0, 44 to 60, 44 to 195, 44 to 133).associate { (purpose, coin) ->
                    var key = master
                    listOf(purpose, coin, 0).forEach { key = HDKeyDerivation.deriveChildKey(key, ChildNumber(it, true)) }
                    (if (coin == 0) purpose else coin) to PublicRoot(key.pubKey.copyOf(), key.chainCode.copyOf())
                }
                val fingerprint = ByteBuffer.allocate(4).putInt(master.fingerprint).array()
                val custom = customChains.associate { chain ->
                    val account = WalletAccount.custom(chain)
                    val parts = CustomChainValidation.components(CustomChainValidation.path(chain.derivationPath, chain.addressFormat))
                    val branches = if (account.chain == WalletChain.SOLANA) {
                        (0..1).map { branch -> (0 until WalletAccount.ADDRESS_LIMIT).map { index ->
                            val path = account.path(branch, index)
                            customSolanaAddress(seed, CustomChainValidation.components(path), path)
                        } }
                    } else {
                        var prefix = master
                        parts.dropLast(2).forEach { prefix = HDKeyDerivation.deriveChildKey(prefix, it) }
                        (0..1).map { branch ->
                            val branchKey = HDKeyDerivation.deriveChildKey(prefix, ChildNumber(branch, parts[parts.lastIndex - 1].isHardened))
                            (0 until WalletAccount.ADDRESS_LIMIT).map { index ->
                                encodedAddress(account, HDKeyDerivation.deriveChildKey(branchKey, ChildNumber(index, parts.last().isHardened)), account.path(branch, index))
                            }
                        }
                    }
                    val branch = parts[parts.lastIndex - 1].num()
                    val index = parts.last().num()
                    val primary = if (branch in 0..1 && index in 0 until WalletAccount.ADDRESS_LIMIT) branches[branch][index]
                    else if (account.chain == WalletChain.SOLANA) customSolanaAddress(seed, parts, account.path)
                    else {
                        var key = master
                        parts.forEach { key = HDKeyDerivation.deriveChildKey(key, it) }
                        encodedAddress(account, key, account.path)
                    }
                    chain.id to CustomAddresses(primary, branches)
                }
                WalletPublicCatalog(fingerprint, roots, solanaAddresses(seed), custom)
            } finally { SodiumMemory.wipe(seed) }
        }

        private fun encodedAddress(account: WalletAccount, key: DeterministicKey, path: String): DerivedAddress {
            val bytes = if (account.chain in listOf(WalletChain.BITCOIN, WalletChain.ZCASH)) key.pubKey
                else key.pubKeyPoint.getEncoded(false).copyOfRange(1, 65)
            val address = when {
                account.chain == WalletChain.BITCOIN -> BitcoinSupport.address(key, requireNotNull(account.bitcoinType))
                account.chain == WalletChain.ZCASH -> ZcashSupport.address(bytes)
                account.chain.isEvm -> "0x" + Keys.getAddress(Numeric.toBigInt(bytes))
                account.chain == WalletChain.TRON -> Base58Check.bytesToBase58(byteArrayOf(0x41) + Numeric.hexStringToByteArray(Keys.getAddress(Numeric.toBigInt(bytes))))
                else -> error("不支持的地址格式")
            }
            return DerivedAddress(address, path, bytes)
        }

        private fun customSolanaAddress(seed: ByteArray, parts: List<ChildNumber>, path: String): DerivedAddress {
            var material = hmac("ed25519 seed".toByteArray(), seed)
            try {
                parts.forEach { component ->
                    val next = child(material, component.num())
                    SodiumMemory.wipe(material)
                    material = next
                }
                val secret = material.copyOfRange(0, 32)
                return try {
                    val public = Keypair.fromSecretKey(secret).publicKey
                    DerivedAddress(public.toBase58(), path, public.bytes())
                } finally { SodiumMemory.wipe(secret) }
            } finally { SodiumMemory.wipe(material) }
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(key, "HmacSHA512")) }.doFinal(data)

        private fun child(parent: ByteArray, index: Int): ByteArray {
            val chain = parent.copyOfRange(32, 64)
            val data = ByteBuffer.allocate(37).put(0).put(parent, 0, 32).putInt(index or Int.MIN_VALUE).array()
            return try { hmac(chain, data) } finally { SodiumMemory.wipe(chain); SodiumMemory.wipe(data) }
        }

        private fun solanaAddresses(seed: ByteArray): List<List<DerivedAddress>> {
            var root = hmac("ed25519 seed".toByteArray(), seed)
            try {
                listOf(44, 501).forEach { index ->
                    val next = child(root, index)
                    SodiumMemory.wipe(root)
                    root = next
                }
                return (0..1).map { branch ->
                    val branchKey = child(root, branch)
                    try {
                        (0 until WalletAccount.ADDRESS_LIMIT).map { index ->
                            val material = child(branchKey, index)
                            val secret = material.copyOfRange(0, 32)
                            try {
                                val public = Keypair.fromSecretKey(secret).publicKey
                                DerivedAddress(public.toBase58(), WalletAccount(WalletChain.SOLANA).path(branch, index), public.bytes())
                            } finally { SodiumMemory.wipe(material); SodiumMemory.wipe(secret) }
                        }
                    } finally { SodiumMemory.wipe(branchKey) }
                }
            } finally { SodiumMemory.wipe(root) }
        }
    }
}
