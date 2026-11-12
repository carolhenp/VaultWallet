package com.micro.airvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.MnemonicUtils
import java.security.SecureRandom

class MnemonicBackupVerificationTest {
    @Test
    fun shufflesAllLettersForEachVerificationAttempt() {
        var previous = "abcdefghijklmnopqrstuvwxyz"
        repeat(20) {
            val letters = MnemonicBackupVerification.shuffledLetters(previous)
            assertEquals(26, letters.length)
            assertEquals(('a'..'z').toSet(), letters.toSet())
            assertTrue(letters != previous)
            previous = letters
        }
    }

    @Test
    fun choosesTenDistinctPositionsAndChecksExactWord() {
        val phrase = MnemonicUtils.generateMnemonic(ByteArray(32))
        val positions = MnemonicBackupVerification.selectPositions(24, SecureRandom())
        assertEquals(10, positions.size)
        assertEquals(10, positions.toSet().size)
        assertTrue(positions.all { it in 0..23 })
        val words = phrase.split(' ')
        val answers = positions.indices.associateWith { words[positions[it]] }
        assertFalse(MnemonicBackupVerification.filled(positions, answers - 0))
        assertThrows(IllegalArgumentException::class.java) {
            MnemonicBackupVerification.incorrectPositions(phrase, positions, answers - 0)
        }
        assertTrue(MnemonicBackupVerification.filled(positions, answers))
        assertTrue(MnemonicBackupVerification.incorrectPositions(phrase, positions, answers).isEmpty())
        val changed = answers + (3 to "ability") + (8 to "ability")
        assertEquals(setOf(3, 8), MnemonicBackupVerification.incorrectPositions(phrase, positions, changed))
        val position = positions.first()
        assertTrue(MnemonicBackupVerification.matches(phrase, position, words[position]))
        assertFalse(MnemonicBackupVerification.matches(phrase, position, "wrong"))
    }

    @Test
    fun candidatesComeFromStandardBip39WordList() {
        assertTrue(MnemonicBackupVerification.suggestions("").isEmpty())
        assertTrue(MnemonicBackupVerification.suggestions("A").isEmpty())
        val suggestions = MnemonicBackupVerification.suggestions("aban")
        assertTrue("abandon" in suggestions)
        assertTrue(suggestions.all { it.startsWith("aban") && it in MnemonicUtils.getWords() })
        assertTrue(suggestions.size <= 24)
    }

    @Test
    fun nextLettersFollowFullBip39WordList() {
        assertEquals(('a'..'z').toSet(), MnemonicBackupVerification.nextLetters(""))
        assertTrue('b' in MnemonicBackupVerification.nextLetters("a"))
        assertFalse('z' in MnemonicBackupVerification.nextLetters("a"))
        assertEquals(setOf('d'), MnemonicBackupVerification.nextLetters("aban"))
        assertTrue(MnemonicBackupVerification.nextLetters("abandon").isEmpty())
    }
}
