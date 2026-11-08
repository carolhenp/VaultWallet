package com.micro.airvault

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Coin
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Base58
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import org.tron.trident.utils.Base58Check
import org.web3j.utils.Numeric
import java.util.Base64

class MultiChainWalletTest {
    private val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")

    @Test
    fun derivesBip84BitcoinAddress() {
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", WalletEngine.address(mnemonic, WalletChain.BITCOIN))
        assertTrue(WalletEngine.address(mnemonic, WalletChain.SOLANA).isNotEmpty())
        assertTrue(WalletEngine.address(mnemonic, WalletChain.TRON).startsWith("T"))
    }

    @Test
    fun derivesAllPublicAddressesForUnlockedSession() {
        val addresses = WalletEngine.addresses(mnemonic)
        assertEquals(WalletChain.entries.toSet(), addresses.keys)
        assertEquals(addresses.getValue(WalletChain.ETHEREUM), addresses.getValue(WalletChain.BASE))
        assertEquals(WalletEngine.address(mnemonic, WalletChain.BITCOIN), addresses.getValue(WalletChain.BITCOIN))
        assertEquals(WalletEngine.address(mnemonic, WalletChain.SOLANA), addresses.getValue(WalletChain.SOLANA))
        assertEquals(WalletEngine.address(mnemonic, WalletChain.TRON), addresses.getValue(WalletChain.TRON))
    }

    @Test
    fun rejectsCrossChainEvmTransactionAndMessage() {
        val tx = WalletEngine.parseRequest("""{"type":"eth_transaction","chainId":56,"nonce":"0","to":"0x000000000000000000000000000000000000dEaD","value":"1","gasLimit":"21000","gasPrice":"1"}""")
        assertThrows(IllegalArgumentException::class.java) { WalletEngine.requireChain(tx, WalletChain.ETHEREUM) }
        WalletEngine.requireChain(tx, WalletChain.BNB_CHAIN)
        val message = WalletEngine.parseRequest("""{"type":"eth_personal_sign","chainId":56,"message":"hello"}""")
        assertThrows(IllegalArgumentException::class.java) { WalletEngine.requireChain(message, WalletChain.ETHEREUM) }
    }

    @Test
    fun verifiesPreviousTransactionAndSignsBitcoinWitness() {
        val address = org.bitcoinj.base.SegwitAddress.fromBech32(WalletEngine.address(mnemonic, WalletChain.BITCOIN), BitcoinNetwork.MAINNET)
        val previous = Transaction().apply {
            addInput(Sha256Hash.ZERO_HASH, 1, ScriptBuilder.createEmpty())
            addOutput(Coin.valueOf(100_000), address)
        }
        val unsigned = Transaction().apply {
            addInput(previous.txId, 0, ScriptBuilder.createEmpty())
            addOutput(Coin.valueOf(90_000), address)
        }
        val json = JSONObject().put("type", "btc_transaction")
            .put("unsignedTransaction", Numeric.toHexString(unsigned.serialize()))
            .put("previousTransactions", JSONArray().put(Numeric.toHexString(previous.serialize())))
        val request = WalletEngine.parseRequest(json.toString())
        val signed = JSONObject(WalletEngine.sign(request, mnemonic, WalletChain.BITCOIN))
        val parsed = Transaction.read(java.nio.ByteBuffer.wrap(Numeric.hexStringToByteArray(signed.getString("signedTransaction"))))
        assertEquals(2, parsed.inputs[0].witness.pushCount)
        assertEquals(unsigned.outputs[0].value, parsed.outputs[0].value)
        json.put("previousTransactions", JSONArray().put(Numeric.toHexString(unsigned.serialize())))
        assertThrows(IllegalArgumentException::class.java) { WalletEngine.parseRequest(json.toString()) }
    }

    @Test
    fun parsesAndSignsSolanaProgramInstruction() {
        val address = WalletEngine.address(mnemonic, WalletChain.SOLANA)
        val key = Base58.decode(address)
        val message = byteArrayOf(1, 0, 0, 2) + key + ByteArray(32) + ByteArray(32) +
            byteArrayOf(1, 1, 1, 0, 1, 7)
        val unsigned = byteArrayOf(1) + ByteArray(64) + message
        val json = JSONObject().put("type", "sol_transaction")
            .put("transaction", Base64.getEncoder().encodeToString(unsigned))
        val request = WalletEngine.parseRequest(json.toString())
        val signed = JSONObject(WalletEngine.sign(request, mnemonic, WalletChain.SOLANA))
        val signedBytes = Base64.getDecoder().decode(signed.getString("signedTransaction"))
        assertTrue(signedBytes.copyOfRange(1, 65).any { it != 0.toByte() })
        assertTrue(signedBytes.copyOfRange(65, signedBytes.size).contentEquals(message))
    }

    @Test
    fun rejectsUnsupportedTronContractBeforeReview() {
        val tx = Chain.Transaction.newBuilder().setRawData(Chain.Transaction.raw.newBuilder()
            .setTimestamp(1).setExpiration(2).build()).build()
        val json = JSONObject().put("type", "tron_transaction")
            .put("transaction", Numeric.toHexString(tx.toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { WalletEngine.parseRequest(json.toString()) }
    }

    @Test
    fun signsTronTransferAndContractCall() {
        val owner = Base58Check.base58ToBytes(WalletEngine.address(mnemonic, WalletChain.TRON))
        val transfer = Contract.TransferContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(owner)).setToAddress(ByteString.copyFrom(owner))
            .setAmount(1_000_000).build()
        val call = Contract.TriggerSmartContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(owner)).setContractAddress(ByteString.copyFrom(owner))
            .setCallValue(0).setData(ByteString.copyFrom(byteArrayOf(0xa9.toByte(), 0x05, 0x9c.toByte(), 0xbb.toByte())))
            .build()
        listOf(
            Chain.Transaction.Contract.newBuilder().setType(Chain.Transaction.Contract.ContractType.TransferContract)
                .setParameter(Any.pack(transfer)).build(),
            Chain.Transaction.Contract.newBuilder().setType(Chain.Transaction.Contract.ContractType.TriggerSmartContract)
                .setParameter(Any.pack(call)).build()
        ).forEach { contract ->
            val tx = Chain.Transaction.newBuilder().setRawData(Chain.Transaction.raw.newBuilder()
                .setTimestamp(1).setExpiration(2).setFeeLimit(10_000_000).addContract(contract).build()).build()
            val json = JSONObject().put("type", "tron_transaction")
                .put("transaction", Numeric.toHexString(tx.toByteArray()))
            val request = WalletEngine.parseRequest(json.toString())
            val result = JSONObject(WalletEngine.sign(request, mnemonic, WalletChain.TRON))
            val signed = Chain.Transaction.parseFrom(Numeric.hexStringToByteArray(result.getString("signedTransaction")))
            assertEquals(1, signed.signatureCount)
            assertEquals(65, signed.getSignature(0).size())
            assertEquals(tx.rawData, signed.rawData)
        }
    }
}
