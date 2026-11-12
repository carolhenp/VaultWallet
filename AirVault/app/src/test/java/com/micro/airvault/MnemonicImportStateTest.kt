package com.micro.airvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.MnemonicUtils

class MnemonicImportStateTest {
    @Test
    fun changingWordCountKeepsOverlappingWordsAndDropsRemovedWords() {
        val entered = MnemonicImportState().enter("abandon").enter("about")
        val expanded = entered.resize(24).select(23).enter("zoo")
        val reduced = expanded.select(23).resize(12)
        assertEquals(listOf("abandon", "about"), reduced.words.take(2))
        assertEquals(2, reduced.active)
        assertEquals(2, reduced.filled)
        assertTrue(reduced.resize(24).words.drop(12).all { it.isEmpty() })
        assertThrows(IllegalArgumentException::class.java) { reduced.resize(13) }
    }

    @Test
    fun enteringWordsFollowsEmptyPositionsAndAllowsEditingAnyWord() {
        val state = MnemonicImportState().select(11).enter("about")
        assertEquals(0, state.active)
        val entered = state.enter("abandon").enter("ability")
        val edited = entered.select(0).enter("able")
        assertEquals(2, edited.active)
        assertEquals("able", edited.words[0])
        assertEquals("ability", edited.words[1])
        assertEquals("about", edited.words[11])
        assertFalse(edited.complete)
        assertThrows(IllegalArgumentException::class.java) { edited.mnemonic() }
        assertThrows(IllegalArgumentException::class.java) { edited.enter("not-a-bip39-word") }
    }

    @Test
    fun acceptsAllSupportedBip39LengthsInOriginalOrder() {
        for (entropyBytes in listOf(16, 20, 24, 28, 32)) {
            val mnemonic = MnemonicUtils.generateMnemonic(ByteArray(entropyBytes) { it.toByte() })
            val words = mnemonic.split(' ')
            val state = words.fold(MnemonicImportState(wordCount = words.size)) { input, word -> input.enter(word) }
            assertTrue(state.complete)
            assertEquals(words.size, state.filled)
            assertEquals(mnemonic, WalletEngine.normalizeMnemonic(state.mnemonic()))
        }
    }

    @Test
    fun completingWordsStillRequiresChecksumValidation() {
        val state = (0 until 12).fold(MnemonicImportState()) { input, _ -> input.enter("abandon") }
        assertTrue(state.complete)
        assertThrows(IllegalArgumentException::class.java) { WalletEngine.normalizeMnemonic(state.mnemonic()) }
        val corrected = state.select(11).enter("about")
        assertEquals(corrected.mnemonic(), WalletEngine.normalizeMnemonic(corrected.mnemonic()))
    }
}
