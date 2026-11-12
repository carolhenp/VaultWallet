package com.micro.airvault

import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.ZcashSupport
import org.bitcoinj.crypto.ECKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.web3j.utils.Numeric

class ZcashWalletTest {
    private val fixture = JSONObject(requireNotNull(javaClass.getResource("/zcash/bip44.json")).readText())
    private val mnemonic = fixture.getString("mnemonic")
    private val catalog by lazy { WalletPublicCatalog.derive(mnemonic) }
    private val account = WalletAccount(WalletChain.ZCASH)

    @Test fun transparentEncodingMatchesWalletCoreVectors() {
        // https://github.com/trustwallet/wallet-core/blob/master/tests/chains/Zcash/AddressTests.cpp
        listOf(
            "2d8f68944bdbfbc0769542fba8fc2d2a3de67393334471624364c7006da2aa54" to "t1Wg9uPPAfwhBWeRjtDPa5ZHNzyBx9rJVKY",
            "be88df1d0bf30a923cb39c3bb953178baaf3726e8d3ce81e7c8462e046e0d835" to "t1gaySCXCYtXE3ygP38YuWtVZczsEbdjG49"
        ).forEach { (secret, expected) ->
            assertEquals(expected, ZcashSupport.address(ECKey.fromPrivate(Numeric.hexStringToByteArray(secret)).pubKey))
        }
    }

    @Test fun bip44ReceiveAndChangeMatchIndependentScureVectors() {
        val vectors = fixture.getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val derived = catalog.address(account, vector.getInt("branch"), vector.getInt("index"))
            assertEquals(vector.getString("path"), derived.path)
            assertEquals(vector.getString("publicKey"), Numeric.toHexStringNoPrefix(derived.publicKey))
            assertEquals(vector.getString("address"), derived.address)
        }
        val first = vectors.getJSONObject(0)
        assertEquals(first.getString("address"), WalletEngine.address(mnemonic, WalletChain.ZCASH))
        assertEquals(first.getString("address"), WalletEngine.addresses(mnemonic).getValue(WalletChain.ZCASH))
        assertEquals(first.getString("publicKey"), Numeric.toHexStringNoPrefix(WalletEngine.publicKeys(mnemonic).getValue(WalletChain.ZCASH)))
    }

    @Test fun pairingExportsTransparentAccountAtAppendedWireOrdinal() {
        val selected = catalog.address(account)
        assertEquals(13, WalletChain.ZCASH.ordinal)
        val collector = WireCodec.Collector()
        var payload: ByteArray? = null
        AccountPairing.frames(PairingWallet.AIRWALLET, account, catalog).reversed().forEach { payload = collector.offer(it) }
        val message = WireCodec.decode(requireNotNull(payload))
        assertEquals(WalletChain.ZCASH.ordinal, message.chain)
        assertEquals(2, message.fields.size)
        assertEquals(selected.address, message.fields[0].toString(Charsets.UTF_8))
        assertArrayEquals(selected.publicKey, message.fields[1])
        assertThrows(IllegalArgumentException::class.java) { AccountPairing.frames(PairingWallet.METAMASK, account, catalog) }
    }

    @Test fun rejectsTrustWalletPairingForZcash() {
        assertFalse(PairingWallet.TRUST_WALLET.supports(account))
        val exception = assertThrows(IllegalArgumentException::class.java) {
            AccountPairing.frames(PairingWallet.TRUST_WALLET, account, catalog)
        }
        assertEquals("TrustWallet 不支持 Zcash 账号同步", exception.message)
    }

    @Test fun rejectsUncompressedAndInvalidPublicKeys() {
        val valid = ECKey.fromPrivate(java.math.BigInteger.TWO)
        listOf(byteArrayOf(), ByteArray(32), valid.pubKeyPoint.getEncoded(false),
            ByteArray(33) { 0xff.toByte() }.also { it[0] = 2 }).forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { ZcashSupport.address(key) }
        }
    }
}
