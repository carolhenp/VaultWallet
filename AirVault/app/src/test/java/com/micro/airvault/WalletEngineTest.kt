package com.micro.airvault

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.MnemonicUtils

class WalletEngineTest {
    private val hardhatMnemonic = "test test test test test test test test test test test junk"
    private val recipient = "0x000000000000000000000000000000000000dEaD"

    @Test
    fun bip39ZeroEntropyVector() {
        val expected = List(11) { "abandon" }.plus("about").joinToString(" ")
        assertEquals(expected, MnemonicUtils.generateMnemonic(ByteArray(16)))
        assertEquals(expected, WalletEngine.normalizeMnemonic("  $expected  "))
        assertThrows(Exception::class.java) {
            WalletEngine.normalizeMnemonic(expected.replace("about", "ability"))
        }
    }

    @Test
    fun derivesEthereumAccountAtDocumentedPath() {
        assertEquals(
            "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266",
            WalletEngine.address(hardhatMnemonic).lowercase()
        )
    }

    @Test
    fun signsPersonalMessageAndBothTransactionFormats() {
        val message = WalletEngine.parseRequest("""{"type":"eth_personal_sign","message":"Hello AirVault"}""")
        val signedMessage = JSONObject(WalletEngine.sign(message, hardhatMnemonic))
        assertEquals("eth_personal_sign_result", signedMessage.getString("type"))
        assertTrue(signedMessage.getString("signature").matches(Regex("0x[0-9a-f]{130}")))

        val legacy = WalletEngine.parseRequest("""{"type":"eth_transaction","chainId":1,"nonce":"0","to":"$recipient","value":"1000000000000000000","gasLimit":"21000","gasPrice":"1000000000"}""")
        val signedLegacy = JSONObject(WalletEngine.sign(legacy, hardhatMnemonic))
        assertTrue(signedLegacy.getString("signedTransaction").startsWith("0x"))
        assertEquals(1L, signedLegacy.getLong("chainId"))

        val eip1559 = WalletEngine.parseRequest("""{"type":"eth_transaction_1559","chainId":1,"nonce":"0","to":"$recipient","value":"0","gasLimit":"21000","maxPriorityFeePerGas":"1000000000","maxFeePerGas":"2000000000"}""")
        val signed1559 = JSONObject(WalletEngine.sign(eip1559, hardhatMnemonic))
        assertTrue(signed1559.getString("signedTransaction").startsWith("0x02"))
    }

    @Test
    fun rejectsInvalidFeeAndRecipient() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletEngine.parseRequest("""{"type":"eth_transaction_1559","chainId":1,"nonce":"0","to":"$recipient","value":"0","gasLimit":"21000","maxPriorityFeePerGas":"3","maxFeePerGas":"2"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletEngine.parseRequest("""{"type":"eth_transaction","chainId":1,"nonce":"0","to":"0x123","value":"0","gasLimit":"21000","gasPrice":"1"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletEngine.parseRequest("""{"type":"eth_transaction","chainId":1.5,"nonce":"0","to":"$recipient","value":"0","gasLimit":"21000","gasPrice":"1"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletEngine.parseRequest("""{"type":"eth_transaction","chainId":1,"nonce":"0","to":"0x000000000000000000000000000000000000deAd","value":"0","gasLimit":"21000","gasPrice":"1"}""")
        }
    }
}
