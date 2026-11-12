package com.micro.airvault

import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric
import java.math.BigInteger
import com.micro.airprotocol.PsbtV0
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Coin
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.base.ScriptType
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.script.ScriptBuilder

class WireBridgeTest {
    private val mnemonic = "test test test test test test test test test test test junk"

    @Test fun binaryEvmRequestSignsAndReturnsSameRequestId() {
        val id = ByteArray(16) { it.toByte() }
        val fields = listOf(WireCodec.number(BigInteger.ZERO),
            Numeric.hexStringToByteArray("0x000000000000000000000000000000000000dEaD"),
            WireCodec.number(BigInteger.valueOf(42)), WireCodec.number(BigInteger.valueOf(21_000)),
            WireCodec.number(BigInteger.valueOf(1_000_000_000)),
            WireCodec.number(BigInteger.valueOf(2_000_000_000)), byteArrayOf())
        val original = WireMessage(WalletChain.ETHEREUM.ordinal, WireCodec.EVM_1559, id, fields)
        val requestFrames = WireCodec.frames(WireCodec.encode(original))
        val requestCollector = WireCodec.Collector()
        val decoded = WireCodec.decode(requestCollector.offer(requestFrames.single())!!)
        val (chain, json) = WireBridge.request(decoded)
        assertEquals(WalletChain.ETHEREUM, chain)
        val signed = WalletEngine.sign(WalletEngine.parseRequest(json), mnemonic, chain)
        val resultCollector = WireCodec.Collector()
        val result = WireCodec.decode(resultCollector.offer(WireBridge.result(decoded, signed).single())!!)
        assertArrayEquals(id, result.id)
        assertEquals(WireCodec.RESULT, result.kind)
        assertEquals(WalletEngine.address(mnemonic), result.fields[0].toString(Charsets.UTF_8))
        assertTrue(result.fields[1].isNotEmpty())
    }

    @Test fun multiFrameCollectorChecksOrderDuplicatesAndDigest() {
        val message = WireMessage(3, WireCodec.TRON, ByteArray(16) { 7 }, listOf(ByteArray(3000) { it.toByte() }))
        val frames = WireCodec.frames(WireCodec.encode(message))
        assertTrue(frames.size > 1)
        val collector = WireCodec.Collector()
        assertEquals(null, collector.offer(frames.last()))
        assertEquals(null, collector.offer(frames.last()))
        var output: ByteArray? = null
        frames.dropLast(1).forEach { output = collector.offer(it) }
        assertArrayEquals(WireCodec.encode(message), output)
        val bad = frames.first().replaceRange(50, 51, if (frames.first()[50] == 'A') "B" else "A")
        val corruptCollector = WireCodec.Collector()
        corruptCollector.offer(bad)
        assertThrows(Exception::class.java) { frames.drop(1).forEach(corruptCollector::offer) }
    }

    @Test fun singleFrameChecksSizeAndDigestAndKeepsLegacyFramesReadable() {
        val message = WireMessage(255, WireCodec.ADDRESSES, ByteArray(16) { 9 }, listOf(ByteArray(1900) { it.toByte() }))
        val encoded = WireCodec.encode(message)
        val frame = WireCodec.singleFrame(encoded)
        assertArrayEquals(encoded, WireCodec.Collector().offer(frame))
        val corrupt = frame.replaceRange(50, 51, if (frame[50] == 'A') "B" else "A")
        assertThrows(IllegalArgumentException::class.java) { WireCodec.Collector().offer(corrupt) }
        val legacyCollector = WireCodec.Collector()
        var payload: ByteArray? = null
        val legacyFrames = WireCodec.frames(encoded)
        assertTrue(legacyFrames.size > 1)
        legacyFrames.reversed().forEach { payload = legacyCollector.offer(it) }
        assertArrayEquals(encoded, payload)
        assertThrows(IllegalArgumentException::class.java) { WireCodec.singleFrame(ByteArray(2001)) }
    }

    @Test fun exportContainsAddressAndPublicKeyForEveryChain() {
        val frames = WireBridge.addresses(WalletEngine.addresses(mnemonic), WalletEngine.publicKeys(mnemonic))
        assertEquals(1, frames.size)
        val collector = WireCodec.Collector()
        var output: ByteArray? = null
        frames.forEach { output = collector.offer(it) }
        val decoded = WireCodec.decode(output!!)
        assertEquals(WireCodec.ADDRESSES, decoded.kind)
        assertEquals(WalletChain.entries.size * 2, decoded.fields.size)
    }

    @Test fun psbtCarriesVerifiedPreviousTransaction() {
        val key = ECKey.fromPrivate(BigInteger.TWO)
        val address = key.toAddress(ScriptType.P2WPKH, BitcoinNetwork.MAINNET)
        val previous = Transaction().apply {
            addInput(Sha256Hash.ZERO_HASH, 0, ScriptBuilder.createEmpty())
            addOutput(Coin.valueOf(50_000), address)
        }
        val unsigned = Transaction().apply {
            addInput(previous.outputs[0])
            addOutput(Coin.valueOf(45_000), address)
        }
        val psbt = PsbtV0.create(unsigned.serialize(), listOf(previous.serialize()))
        val parsed = PsbtV0.parse(psbt)
        assertArrayEquals(unsigned.serialize(), parsed.unsigned)
        assertArrayEquals(previous.serialize(), parsed.previous.single())
        val message = WireMessage(WalletChain.BITCOIN.ordinal, WireCodec.BITCOIN_PSBT, ByteArray(16), listOf(psbt))
        val (_, json) = WireBridge.request(message)
        assertEquals(WalletChain.BITCOIN, (WalletEngine.parseRequest(json) as SigningRequest.ChainTransaction).chain)
    }
}
