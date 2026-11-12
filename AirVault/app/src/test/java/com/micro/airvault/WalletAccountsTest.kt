package com.micro.airvault

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.micro.airprotocol.BitcoinAddressType
import com.micro.airprotocol.BitcoinSupport
import com.micro.airprotocol.ZcashSupport
import com.micro.airprotocol.WireCodec
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import org.bitcoinj.crypto.ECKey
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils
import org.web3j.utils.Numeric

class WalletAccountsTest {
    private val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")
    private val catalog by lazy { WalletPublicCatalog.derive(mnemonic) }

    @Test fun firstAddressesMatchExistingWalletsAndAllBitcoinTypesArePresent() {
        assertEquals(16, WalletAccount.all.size)
        WalletAccount.all.forEach { account ->
            assertEquals(WalletEngine.address(mnemonic, account.chain, account.bitcoinType ?: BitcoinAddressType.SEGWIT),
                catalog.address(account).address)
            assertEquals(account.path, catalog.address(account).path)
        }
        assertEquals("73c5da0a", Numeric.toHexStringNoPrefix(catalog.masterFingerprint))
    }

    @Test fun receiveAndChangeHaveExactlyOneHundredDistinctAddressesForEveryType() {
        // EVM networks share one account and therefore need only one derivation check.
        WalletAccount.all.filter { !it.chain.isEvm || it.chain == WalletChain.ETHEREUM }.forEach { account ->
            val receive = catalog.addresses(account, 0)
            val change = catalog.addresses(account, 1)
            assertEquals(100, receive.size)
            assertEquals(100, change.size)
            assertEquals(200, (receive + change).map { it.address }.toSet().size)
            assertEquals(account.path(0, 99), receive.last().path)
            assertEquals(account.path(1, 99), change.last().path)
        }
        val solana = WalletAccount(WalletChain.SOLANA)
        assertEquals("m/44'/501'/0'/99'", catalog.address(solana, 0, 99).path)
        assertEquals("m/44'/501'/1'/99'", catalog.address(solana, 1, 99).path)
        assertThrows(IllegalArgumentException::class.java) { catalog.address(solana, 0, 100) }
        assertThrows(IllegalArgumentException::class.java) { catalog.address(solana, 2, 0) }
    }

    @Test fun publicDerivationMatchesIndependentPrivateDerivationAtBothBranchBoundaries() {
        val seed = MnemonicUtils.generateSeed(mnemonic, "")
        try {
            val master = Bip32ECKeyPair.generateKeyPair(seed)
            WalletAccount.all.filter { it.chain == WalletChain.BITCOIN || it.chain == WalletChain.ETHEREUM || it.chain == WalletChain.TRON || it.chain == WalletChain.ZCASH }
                .forEach { account ->
                    for (branch in 0..1) for (index in listOf(0, 1, 99)) {
                        val path = account.path(branch, index).removePrefix("m/").split('/').map {
                            it.removeSuffix("'").toInt() or if (it.endsWith("'")) Int.MIN_VALUE else 0
                        }.toIntArray()
                        val key = Bip32ECKeyPair.deriveKeyPair(master, path)
                        val derived = catalog.address(account, branch, index)
                        val actual = ECKey.fromPrivate(key.privateKey)
                        assertArrayEquals(actual.pubKey, ECKey.fromPublicOnly(
                            if (derived.publicKey.size == 64) byteArrayOf(4) + derived.publicKey else derived.publicKey).pubKeyPoint.getEncoded(true))
                        if (account.chain == WalletChain.BITCOIN)
                            assertEquals(BitcoinSupport.address(actual, account.bitcoinType!!), derived.address)
                        else if (account.chain.isEvm) assertEquals("0x" + Keys.getAddress(key), derived.address)
                        else if (account.chain == WalletChain.ZCASH) assertEquals(ZcashSupport.address(actual.pubKey), derived.address)
                    }
                }
        } finally { SodiumMemory.wipe(seed) }
    }

    @Test fun metaMaskHardwareAccountContainsExactPublicKeyOriginAndFingerprint() {
        val account = WalletAccount(WalletChain.ETHEREUM)
        val frames = AccountPairing.frames(PairingWallet.METAMASK, account, catalog)
        val decoder = URDecoder()
        frames.reversed().forEach { decoder.receivePart(it) }
        val ur = decoder.result.ur
        assertEquals("crypto-account", ur.type)
        val fixture = JSONObject(requireNotNull(javaClass.getResource("/pairing/metamask-account.json")).readText())
        assertArrayEquals(Numeric.hexStringToByteArray(fixture.getString("cbor")), ur.cborBytes)
        val decoded = ur.decodeFromRegistry() as CryptoAccount
        assertArrayEquals(catalog.masterFingerprint, decoded.masterFingerprint)
        val key = decoded.outputDescriptors.single().hdKey
        assertFalse(key.isPrivateKey)
        assertFalse(key.isMaster)
        assertNull(key.chainCode)
        assertEquals("44'/60'/0'/0/0", key.origin.path)
        assertArrayEquals(catalog.masterFingerprint, key.origin.sourceFingerprint)
        assertEquals(catalog.address(account).address, "0x" + Keys.getAddress(
            Numeric.toBigInt(ECKey.fromPublicOnly(key.key).pubKeyPoint.getEncoded(false).copyOfRange(1, 65))))
        assertThrows(IllegalArgumentException::class.java) {
            AccountPairing.frames(PairingWallet.METAMASK, WalletAccount(WalletChain.BITCOIN, BitcoinAddressType.TAPROOT), catalog)
        }
    }

    @Test fun airWalletPairingUsesOneScannableQrContainingOnlySelectedPublicAccount() {
        WalletAccount.all.forEach { selected ->
            val frames = AccountPairing.frames(PairingWallet.AIRWALLET, selected, catalog)
            assertEquals(1, frames.size)
            val frame = frames.single()
            val matrix = QRCodeWriter().encode(frame, BarcodeFormat.QR_CODE, 512, 512)
            val pixels = IntArray(matrix.width * matrix.height) { i ->
                if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            val scanned = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(matrix.width, matrix.height, pixels))),
                mapOf(DecodeHintType.PURE_BARCODE to true)).text
            assertEquals(frame, scanned)
            val message = WireCodec.decode(requireNotNull(WireCodec.Collector().offer(scanned)))
            assertEquals(WireCodec.ADDRESSES, message.kind)
            assertEquals(selected.chain.ordinal, message.chain)
            assertEquals(2, message.fields.size)
            val expected = catalog.address(selected)
            assertEquals(expected.address, message.fields[0].toString(Charsets.UTF_8))
            assertArrayEquals(expected.publicKey, message.fields[1])
        }
    }

    @Test fun airWalletExportsSelectedBitcoinTypeAndTrustWalletRejectsBitcoin() {
        BitcoinAddressType.entries.forEach { type ->
            val account = WalletAccount(WalletChain.BITCOIN, type)
            val frames = AccountPairing.frames(PairingWallet.AIRWALLET, account, catalog)
            val collector = WireCodec.Collector()
            var payload: ByteArray? = null
            frames.forEach { payload = collector.offer(it) }
            val fields = WireCodec.decode(payload!!).fields
            assertEquals(catalog.address(account).address, fields[0].toString(Charsets.UTF_8))
            assertFalse(PairingWallet.TRUST_WALLET.supports(account))
            assertThrows(IllegalArgumentException::class.java) {
                AccountPairing.frames(PairingWallet.TRUST_WALLET, account, catalog)
            }
        }
    }

    @Test fun trustWalletExportsOnlyPublicAddressesForItsViewOnlyNetworks() {
        val supportedChains = setOf(WalletChain.ETHEREUM, WalletChain.SOLANA, WalletChain.TRON,
            WalletChain.BNB_CHAIN, WalletChain.POLYGON, WalletChain.ARBITRUM, WalletChain.OPTIMISM,
            WalletChain.BASE, WalletChain.AVALANCHE, WalletChain.GNOSIS, WalletChain.CRONOS, WalletChain.SONIC)
        assertEquals(supportedChains, WalletAccount.all.filter(PairingWallet.TRUST_WALLET::supports).map { it.chain }.toSet())
        supportedChains.forEach { chain ->
            val account = WalletAccount(chain)
            assertEquals(listOf(catalog.address(account).address),
                AccountPairing.frames(PairingWallet.TRUST_WALLET, account, catalog))
        }
    }
}
