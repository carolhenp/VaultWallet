package com.micro.airwallet

import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Coin
import org.bitcoinj.base.ScriptType
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.script.ScriptBuilder
import org.tron.trident.core.key.KeyPair as TronKeyPair
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import org.sol4k.Keypair as SolanaKeypair
import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.micro.airprotocol.PsbtV0
import com.micro.airprotocol.BitcoinAddressType
import com.micro.airprotocol.BitcoinSupport
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.math.BigInteger

class WireVerifierTest {
    private val key = ECKeyPair.create(BigInteger.ONE)
    private val credentials = Credentials.create(key)
    private val to = Numeric.hexStringToByteArray("0x000000000000000000000000000000000000dEaD")

    @Test fun verifiesEip1559SignatureAndRejectsAlteredAmount() {
        val fields = listOf(WireCodec.number(BigInteger.ONE), to, WireCodec.number(BigInteger.valueOf(42)),
            WireCodec.number(BigInteger.valueOf(21_000)), WireCodec.number(BigInteger.valueOf(1_000_000_000)),
            WireCodec.number(BigInteger.valueOf(2_000_000_000)), byteArrayOf())
        val request = WireMessage(0, WireCodec.EVM_1559, ByteArray(16) { 3 }, fields)
        val raw = RawTransaction.createTransaction(1L, BigInteger.ONE, BigInteger.valueOf(21_000),
            Numeric.toHexString(to), BigInteger.valueOf(42), "0x", BigInteger.valueOf(1_000_000_000),
            BigInteger.valueOf(2_000_000_000))
        val signed = TransactionEncoder.signMessage(raw, credentials)
        val response = WireMessage(0, WireCodec.RESULT, request.id,
            listOf(credentials.address.toByteArray(), signed))
        val account = WatchedAccount(credentials.address, Numeric.toHexStringNoPrefix(Numeric.toBytesPadded(key.publicKey, 64)))
        assertArrayEquals(signed, WireVerifier.verify(request, response, account))
        val json = JSONObject().put("type", "eth_transaction_result").put("chainId", 1)
            .put("from", credentials.address).put("signedTransaction", Numeric.toHexString(signed)).toString()
        assertArrayEquals(signed, WireVerifier.verify(request,
            WireVerifier.responseFromSignedJson(request, json), account))
        assertThrows(Exception::class.java) {
            WireVerifier.responseFromSignedJson(request, JSONObject(json).put("chainId", 137).toString())
        }
        assertThrows(Exception::class.java) {
            WireVerifier.verify(request.copy(fields = fields.toMutableList().also { it[2] = WireCodec.number(BigInteger.valueOf(43)) }), response, account)
        }
        assertThrows(Exception::class.java) {
            WireVerifier.verify(request, response.copy(id = ByteArray(16)), account)
        }
    }

    @Test fun framesRoundTripInReverseOrder() {
        val request = WireMessage(1, WireCodec.BITCOIN, ByteArray(16) { 8 }, listOf(ByteArray(2400) { it.toByte() }))
        val frames = WireCodec.frames(WireCodec.encode(request))
        assertTrue(frames.size > 1)
        val collector = WireCodec.Collector()
        var result: ByteArray? = null
        frames.reversed().forEach { result = collector.offer(it) }
        val decoded = WireCodec.decode(result!!)
        assertEquals(request.kind, decoded.kind)
        assertArrayEquals(request.fields[0], decoded.fields[0])
    }

    @Test fun verifiesBitcoinP2wpkhWitnessAndRejectsMissingWitness() {
        val key = ECKey.fromPrivate(BigInteger.TWO)
        val address = key.toAddress(ScriptType.P2WPKH, BitcoinNetwork.MAINNET).toString()
        val previous = Transaction().apply {
            addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder.createEmpty())
            addOutput(Coin.valueOf(50_000), key.toAddress(ScriptType.P2WPKH, BitcoinNetwork.MAINNET))
        }
        val unsigned = Transaction().apply {
            addInput(previous.outputs[0])
            addOutput(Coin.valueOf(45_000), key.toAddress(ScriptType.P2WPKH, BitcoinNetwork.MAINNET))
        }
        val request = WireMessage(1, WireCodec.BITCOIN_PSBT, ByteArray(16),
            listOf(PsbtV0.create(unsigned.serialize(), listOf(previous.serialize()))))
        val input = unsigned.inputs[0]
        val signature = unsigned.calculateWitnessSignature(0, key, ScriptBuilder.createP2PKHOutputScript(key),
            previous.outputs[0].value, Transaction.SigHash.ALL, false)
        unsigned.replaceInput(0, input.withWitness(TransactionWitness.redeemP2WPKH(signature, key)))
        val account = WatchedAccount(address, Numeric.toHexStringNoPrefix(key.pubKey))
        val response = WireMessage(1, WireCodec.RESULT, request.id, listOf(address.toByteArray(), unsigned.serialize()))
        assertArrayEquals(unsigned.serialize(), WireVerifier.verify(request, response, account))
        assertThrows(Exception::class.java) {
            WireVerifier.verify(request, response.copy(fields = listOf(address.toByteArray(),
                PsbtV0.parse(request.fields[0]).unsigned)), account)
        }
    }

    @Test fun verifiesTronSignatureAgainstExportedAddress() {
        val tron = TronKeyPair("2".padStart(64, '0'))
        val owner = org.tron.trident.utils.Base58Check.base58ToBytes(tron.toBase58CheckAddress())
        val transfer = Contract.TransferContract.newBuilder().setOwnerAddress(ByteString.copyFrom(owner))
            .setToAddress(ByteString.copyFrom(owner)).setAmount(1000).build()
        val raw = Chain.Transaction.raw.newBuilder().setTimestamp(1_000).setExpiration(60_000)
            .addContract(Chain.Transaction.Contract.newBuilder()
                .setType(Chain.Transaction.Contract.ContractType.TransferContract)
                .setParameter(Any.pack(transfer))).build()
        val unsigned = Chain.Transaction.newBuilder().setRawData(raw).build()
        val signature = TronKeyPair.signTransaction(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()), tron)
        val signed = unsigned.toBuilder().addSignature(ByteString.copyFrom(signature)).build().toByteArray()
        val request = WireMessage(3, WireCodec.TRON, ByteArray(16), listOf(unsigned.toByteArray()))
        val account = WatchedAccount(tron.toBase58CheckAddress(), tron.toPublicKey())
        val response = WireMessage(3, WireCodec.RESULT, request.id, listOf(account.address.toByteArray(), signed))
        assertArrayEquals(signed, WireVerifier.verify(request, response, account))
    }

    @Test fun verifiesAllBitcoinTypesAndRejectsTransactionOrSignatureChanges() {
        val bitcoin = ECKey.fromPrivate(BigInteger.TWO)
        BitcoinAddressType.entries.forEach { type ->
            val address = BitcoinSupport.address(bitcoin, type)
            val previous = Transaction().apply {
                addInput(Sha256Hash.ZERO_HASH, 1, ScriptBuilder.createEmpty())
                addOutput(Coin.valueOf(50_000), BitcoinSupport.outputScript(bitcoin, type))
            }
            val unsigned = Transaction().apply {
                addInput(previous.outputs[0])
                addOutput(Coin.valueOf(45_000), BitcoinSupport.outputScript(bitcoin, type))
            }
            val request = WireMessage(1, WireCodec.BITCOIN_PSBT, ByteArray(16),
                listOf(PsbtV0.create(unsigned.serialize(), listOf(previous.serialize()))))
            val signed = Transaction.read(ByteBuffer.wrap(unsigned.serialize()))
            BitcoinSupport.signInput(signed, previous.outputs, 0, bitcoin, type)
            val account = WatchedAccount(address, Numeric.toHexStringNoPrefix(bitcoin.pubKey))
            fun response(tx: Transaction) = WireMessage(1, WireCodec.RESULT, request.id,
                listOf(address.toByteArray(), tx.serialize()))
            assertArrayEquals(signed.serialize(), WireVerifier.verify(request, response(signed), account))
            assertThrows(Exception::class.java) { WireVerifier.verify(request, response(unsigned), account) }
            val changed = Transaction.read(ByteBuffer.wrap(signed.serialize())).apply {
                addOutput(Coin.valueOf(1), BitcoinSupport.outputScript(bitcoin, type))
            }
            assertThrows(Exception::class.java) { WireVerifier.verify(request, response(changed), account) }
            val broken = Transaction.read(ByteBuffer.wrap(signed.serialize()))
            val input = broken.inputs[0]
            if (type == BitcoinAddressType.LEGACY) {
                broken.replaceInput(0, input.withScriptSig(ScriptBuilder.createEmpty()))
            } else {
                val signature = input.witness.getPush(0).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                val witness = if (type == BitcoinAddressType.TAPROOT) TransactionWitness.of(signature)
                    else TransactionWitness.of(signature, bitcoin.pubKey)
                broken.replaceInput(0, input.withWitness(witness))
            }
            assertThrows(Exception::class.java) { WireVerifier.verify(request, response(broken), account) }
            val wrongKey = account.copy(publicKey = Numeric.toHexStringNoPrefix(ECKey.fromPrivate(BigInteger.TEN).pubKey))
            assertThrows(Exception::class.java) { WireVerifier.verify(request, response(signed), wrongKey) }
        }
    }

    @Test fun importsPublicKeysForAllChainsAndAcceptsLegacyThirteenChainExports() {
        val bitcoin = ECKey.fromPrivate(BigInteger.TWO)
        val solana = SolanaKeypair.fromSecretKey(ByteArray(32) { 9 })
        val tron = TronKeyPair("2".padStart(64, '0'))
        val evmKey = Numeric.toBytesPadded(key.publicKey, 64)
        BitcoinAddressType.entries.forEach { type ->
            val fields = WalletChain.entries.flatMap { chain ->
                val account = when (chain) {
                    WalletChain.BITCOIN -> BitcoinSupport.address(bitcoin, type) to bitcoin.pubKey
                    WalletChain.SOLANA -> solana.publicKey.toBase58() to solana.publicKey.bytes()
                    WalletChain.TRON -> tron.toBase58CheckAddress() to Numeric.hexStringToByteArray(tron.toPublicKey())
                    WalletChain.ZCASH -> "t1XVXWCvpMgBvUaed4XDqWtgQgJSu1Ghz7F" to Numeric.hexStringToByteArray(
                        "03db98d8f87716269ed31879aef19bdadbc869a9ea67729e36332d023b916cbcc9")
                    else -> credentials.address to evmKey
                }
                listOf(account.first.toByteArray(), account.second)
            }
            val message = WireMessage(255, WireCodec.ADDRESSES, ByteArray(16), fields)
            val frame = WireCodec.singleFrame(WireCodec.encode(message))
            val decoded = WireCodec.decode(requireNotNull(WireCodec.Collector().offer(frame)))
            val imported = WireVerifier.importAccounts(decoded)
            assertEquals(14, imported.size)
            WalletChain.entries.forEach { chain ->
                val single = WireMessage(chain.ordinal, WireCodec.ADDRESSES, ByteArray(16),
                    fields.subList(chain.ordinal * 2, chain.ordinal * 2 + 2))
                val singleFrame = WireCodec.singleFrame(WireCodec.encode(single))
                val selected = WireVerifier.importAccounts(WireCodec.decode(requireNotNull(WireCodec.Collector().offer(singleFrame))))
                assertEquals(mapOf(chain to imported.getValue(chain)), selected)
                assertThrows(Exception::class.java) { WireVerifier.importAccounts(single.copy(fields = single.fields.take(1))) }
                assertThrows(Exception::class.java) { WireVerifier.importAccounts(single.copy(fields = single.fields + single.fields)) }
                assertThrows(Exception::class.java) { WireVerifier.importAccounts(single.copy(kind = WireCodec.RESULT)) }
                assertThrows(Exception::class.java) { WireVerifier.importAccounts(single.copy(chain = 254)) }
                val mismatched = single.fields.toMutableList().also { it[0] = "invalid-address".toByteArray() }
                assertThrows(Exception::class.java) { WireVerifier.importAccounts(single.copy(fields = mismatched)) }
            }
            assertEquals("t1XVXWCvpMgBvUaed4XDqWtgQgJSu1Ghz7F", imported.getValue(WalletChain.ZCASH).address)
            val legacy = WireVerifier.importAccounts(message.copy(fields = fields.take(26)))
            assertEquals(13, legacy.size)
            assertTrue(WalletChain.ZCASH !in legacy)
            assertEquals(credentials.address, imported.getValue(WalletChain.ETHEREUM).address)
            assertEquals(tron.toBase58CheckAddress(), imported.getValue(WalletChain.TRON).address)
            assertEquals(BitcoinSupport.address(bitcoin, type), imported.getValue(WalletChain.BITCOIN).address)
            val invalid = fields.toMutableList().also {
                it[WalletChain.BITCOIN.ordinal * 2 + 1] = ECKey.fromPrivate(BigInteger.TEN).pubKey
            }
            assertThrows(Exception::class.java) { WireVerifier.importAccounts(message.copy(fields = invalid)) }
            val wrongZcashKey = fields.toMutableList().also {
                it[WalletChain.ZCASH.ordinal * 2 + 1] = ECKey.fromPrivate(BigInteger.TEN).pubKey
            }
            assertThrows(Exception::class.java) { WireVerifier.importAccounts(message.copy(fields = wrongZcashKey)) }
            val wrongZcashAddress = fields.toMutableList().also {
                it[WalletChain.ZCASH.ordinal * 2] = "t1Wg9uPPAfwhBWeRjtDPa5ZHNzyBx9rJVKY".toByteArray()
            }
            assertThrows(Exception::class.java) { WireVerifier.importAccounts(message.copy(fields = wrongZcashAddress)) }
            assertThrows(Exception::class.java) { WireVerifier.importAccounts(message.copy(fields = fields.dropLast(1))) }
        }
    }

    @Test fun verifiesSolanaEd25519AndRejectsChangedMessage() {
        val solana = SolanaKeypair.fromSecretKey(ByteArray(32) { 5 })
        val message = byteArrayOf(1, 0, 0, 1) + solana.publicKey.bytes() + ByteArray(32) + byteArrayOf(0)
        val unsigned = byteArrayOf(1) + ByteArray(64) + message
        val request = WireMessage(2, WireCodec.SOLANA, ByteArray(16), listOf(unsigned))
        val signed = byteArrayOf(1) + solana.sign(message) + message
        val account = WatchedAccount(solana.publicKey.toBase58(), Numeric.toHexStringNoPrefix(solana.publicKey.bytes()))
        val result = WireMessage(2, WireCodec.RESULT, request.id, listOf(account.address.toByteArray(), signed))
        assertArrayEquals(signed, WireVerifier.verify(request, result, account))
        assertThrows(Exception::class.java) {
            WireVerifier.verify(request, result.copy(fields = listOf(account.address.toByteArray(),
                signed.copyOf().also { it[it.lastIndex] = 1 })), account)
        }
    }
}
