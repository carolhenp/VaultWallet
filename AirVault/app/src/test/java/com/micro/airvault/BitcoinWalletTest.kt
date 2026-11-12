package com.micro.airvault

import com.micro.airprotocol.BitcoinAddressType
import com.micro.airprotocol.BitcoinSupport
import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.PsbtV0
import org.bitcoinj.base.AddressParser
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Coin
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.web3j.utils.Numeric
import java.nio.ByteBuffer

class BitcoinWalletTest {
    private val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")

    @Test
    fun derivesStandardBip44Bip84AndBip86Addresses() {
        val expected = mapOf(
            BitcoinAddressType.LEGACY to "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA",
            BitcoinAddressType.SEGWIT to "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            BitcoinAddressType.TAPROOT to "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
        )
        expected.forEach { (type, address) ->
            assertEquals(address, WalletEngine.address(mnemonic, WalletChain.BITCOIN, type))
            assertEquals(type, BitcoinAddressType.fromAddress(address))
        }
        val internal = NonEvmWallet.bitcoinKey(mnemonic, BitcoinAddressType.TAPROOT)
        assertEquals("cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115",
            Numeric.toHexStringNoPrefix(internal.pubKeyPoint.normalize().affineXCoord.encoded))
        assertEquals("a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c",
            Numeric.toHexStringNoPrefix(BitcoinSupport.taprootOutputKey(internal)))
    }

    @Test
    fun taprootDefaultSignatureHashMatchesOfficialBip341Vector() {
        val json = JSONObject(requireNotNull(javaClass.getResource("/bitcoin/bip341-default.json")).readText())
        val tx = read(Numeric.hexStringToByteArray(json.getString("rawUnsignedTx")))
        val entries = json.getJSONArray("utxosSpent")
        val spent = (0 until entries.length()).map { index ->
            val output = entries.getJSONObject(index)
            TransactionOutput(Transaction(), Coin.valueOf(output.getLong("amountSats")),
                Numeric.hexStringToByteArray(output.getString("scriptPubKey")))
        }
        assertEquals(json.getString("signatureHash"), Numeric.toHexStringNoPrefix(
            BitcoinSupport.taprootSignatureHash(tx, spent, json.getInt("inputIndex"))))
    }

    @Test
    fun signsAndVerifiesEveryAddressTypeAndRejectsChanges() {
        BitcoinAddressType.entries.forEach { type ->
            val key = NonEvmWallet.bitcoinKey(mnemonic, type)
            val address = AddressParser.getDefault(BitcoinNetwork.MAINNET).parseAddress(BitcoinSupport.address(key, type))
            val previous = Transaction().apply {
                addInput(Sha256Hash.ZERO_HASH, 1, ScriptBuilder.createEmpty())
                addOutput(Coin.valueOf(100_000), address)
                addOutput(Coin.valueOf(60_000), address)
            }
            val unsigned = Transaction().apply {
                addInput(previous.txId, 0, ScriptBuilder.createEmpty())
                addInput(previous.txId, 1, ScriptBuilder.createEmpty())
                addOutput(Coin.valueOf(150_000), address)
            }
            val request = request(unsigned, listOf(previous, previous))
            assertEquals(type, request.bitcoinAddressType)
            val json = JSONObject(WalletEngine.sign(request, mnemonic, WalletChain.BITCOIN, type))
            assertEquals(address.toString(), json.getString("from"))
            val signed = read(Numeric.hexStringToByteArray(json.getString("signedTransaction")))
            BitcoinSupport.requireSameTransaction(unsigned, signed)
            signed.inputs.indices.forEach { BitcoinSupport.verifyInput(signed, previous.outputs, it, key, type) }
            if (type == BitcoinAddressType.LEGACY) {
                assertNotEquals(unsigned.txId, signed.txId)
                assertFalse(signed.inputs[0].hasWitness())
            } else {
                assertEquals(unsigned.txId, signed.txId)
                assertEquals(if (type == BitcoinAddressType.TAPROOT) 1 else 2, signed.inputs[0].witness.pushCount)
            }
            if (type != BitcoinAddressType.TAPROOT) {
                signed.inputs.indices.forEach {
                    signed.inputs[it].connect(previous.outputs[it])
                    signed.inputs[it].verify(previous.outputs[it])
                }
            }
            val other = BitcoinAddressType.entries.first { it != type }
            assertThrows(IllegalArgumentException::class.java) {
                WalletEngine.sign(request, mnemonic, WalletChain.BITCOIN, other)
            }
            signed.addOutput(Coin.valueOf(1), address)
            assertThrows(IllegalArgumentException::class.java) { BitcoinSupport.requireSameTransaction(unsigned, signed) }
            assertThrows(IllegalArgumentException::class.java) { BitcoinSupport.verifyInput(signed, previous.outputs, 0, key, type) }
        }
    }

    @Test
    fun psbtHonorsSupportedSighashAndRejectsScriptPathFields() {
        BitcoinAddressType.entries.forEach { type ->
            val key = NonEvmWallet.bitcoinKey(mnemonic, type)
            val previous = Transaction().apply {
                addInput(Sha256Hash.ZERO_HASH, 1, ScriptBuilder.createEmpty())
                addOutput(Coin.valueOf(100_000), BitcoinSupport.outputScript(key, type))
            }
            val tx = Transaction().apply {
                addInput(previous.outputs[0])
                addOutput(Coin.valueOf(90_000), BitcoinSupport.outputScript(key, type))
            }
            val psbt = PsbtV0.create(tx.serialize(), listOf(previous.serialize()))
            // The final two bytes terminate the sole input and output maps.
            fun field(key: Int, value: ByteArray) = psbt.copyOf(psbt.size - 2) +
                byteArrayOf(1, key.toByte(), value.size.toByte()) + value + byteArrayOf(0, 0)
            val supported = if (type == BitcoinAddressType.TAPROOT) 0 else 1
            assertArrayEquals(tx.serialize(), PsbtV0.parse(field(3, byteArrayOf(supported.toByte(), 0, 0, 0))).unsigned)
            assertThrows(IllegalArgumentException::class.java) { PsbtV0.parse(field(3, byteArrayOf(2, 0, 0, 0))) }
            assertThrows(IllegalStateException::class.java) { PsbtV0.parse(field(0x15, byteArrayOf(1))) }
        }
    }

    @Test
    fun publicExportUsesTheSelectedTypeAndKeepsAllThreePublicAccounts() {
        BitcoinAddressType.entries.forEach { type ->
            val data = WalletEngine.publicData(mnemonic, type)
            assertEquals(BitcoinAddressType.entries.toSet(), data.bitcoinAccounts.keys)
            val frames = WireBridge.addresses(data.addresses, data.publicKeys)
            val collector = WireCodec.Collector()
            var payload: ByteArray? = null
            frames.forEach { payload = collector.offer(it) }
            val message = WireCodec.decode(payload!!)
            val index = WalletChain.BITCOIN.ordinal * 2
            assertEquals(WalletEngine.address(mnemonic, WalletChain.BITCOIN, type), message.fields[index].toString(Charsets.UTF_8))
            assertArrayEquals(NonEvmWallet.bitcoinKey(mnemonic, type).pubKey, message.fields[index + 1])
        }
    }

    @Test
    fun refusesRepeatedOutpointsAndForeignWalletInputs() {
        val type = BitcoinAddressType.TAPROOT
        val key = NonEvmWallet.bitcoinKey(mnemonic, type)
        val previous = Transaction().apply {
            addInput(Sha256Hash.ZERO_HASH, 1, ScriptBuilder.createEmpty())
            addOutput(Coin.valueOf(100_000), BitcoinSupport.outputScript(key, type))
        }
        val duplicate = Transaction().apply {
            repeat(2) { addInput(previous.txId, 0, ScriptBuilder.createEmpty()) }
            addOutput(Coin.valueOf(90_000), BitcoinSupport.outputScript(key, type))
        }
        assertThrows(IllegalArgumentException::class.java) { request(duplicate, listOf(previous, previous)) }
        val unsigned = Transaction().apply {
            addInput(previous.txId, 0, ScriptBuilder.createEmpty())
            addOutput(Coin.valueOf(90_000), BitcoinSupport.outputScript(key, type))
        }
        val transaction = request(unsigned, listOf(previous))
        val otherMnemonic = List(23) { "abandon" }.plus("art").joinToString(" ")
        assertThrows(IllegalArgumentException::class.java) { WalletEngine.sign(transaction, otherMnemonic, WalletChain.BITCOIN, type) }
    }

    private fun request(tx: Transaction, previous: List<Transaction>): SigningRequest.ChainTransaction =
        WalletEngine.parseRequest(JSONObject().put("type", "btc_transaction")
            .put("unsignedTransaction", Numeric.toHexString(tx.serialize()))
            .put("previousTransactions", JSONArray(previous.map { Numeric.toHexString(it.serialize()) })).toString()) as SigningRequest.ChainTransaction

    private fun read(bytes: ByteArray) = Transaction.read(ByteBuffer.wrap(bytes))
}
