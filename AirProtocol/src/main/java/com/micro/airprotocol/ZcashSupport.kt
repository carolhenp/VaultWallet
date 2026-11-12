package com.micro.airprotocol

import org.bitcoinj.crypto.ECKey
import org.sol4k.Base58
import java.security.MessageDigest

/** Zcash mainnet transparent P2PKH addresses; no shielded or unified receivers. */
object ZcashSupport {
    fun address(publicKey: ByteArray): String {
        require(publicKey.size == 33 && publicKey[0] in byteArrayOf(2, 3)) { "Zcash 公钥必须为压缩 secp256k1 公钥" }
        val key = ECKey.fromPublicOnly(publicKey)
        require(key.pubKeyPoint.isValid && !key.pubKeyPoint.isInfinity) { "Zcash 公钥无效" }
        // Zcash uses a two-byte network prefix, unlike Bitcoin's single-byte prefix.
        val payload = byteArrayOf(0x1c, 0xb8.toByte()) + key.pubKeyHash
        val sha256 = MessageDigest.getInstance("SHA-256")
        val checksum = sha256.digest(sha256.digest(payload)).copyOf(4)
        return Base58.encode(payload + checksum)
    }
}
