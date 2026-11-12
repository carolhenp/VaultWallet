package com.micro.airvault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.micro.airvault.ui.theme.AirVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupVerificationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun customKeyboardFiltersBip39WordsWithoutTextField() {
        var answers by mutableStateOf<Map<Int, String>>(emptyMap())
        var active by mutableStateOf(0)
        compose.setContent {
            AirVaultTheme {
                BackupVerification(listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18),
                    answers, emptySet(), false, "zyxwvutsrqponmlkjihgfedcba", active, "", { active = it }, {
                        answers = answers + (active to it)
                        active = 1
                    })
            }
        }
        compose.onNodeWithTag("backup-keyboard").assertIsDisplayed()
        compose.onNodeWithTag("backup-letter-a").performClick()
        compose.onNodeWithTag("backup-letter-b").assertIsEnabled()
        compose.onNodeWithTag("backup-letter-z").assertIsNotEnabled()
        listOf("B", "A", "N").forEach { letter -> compose.onNodeWithText(letter).performClick() }
        val candidates = compose.onNodeWithTag("backup-candidates").getUnclippedBoundsInRoot()
        assertTrue(candidates.bottom - candidates.top <= 96.dp)
        compose.onNodeWithText("abandon").assertIsDisplayed().performClick()
        assertEquals("abandon", answers[0])
        compose.onNodeWithText("1 / 10 已填写").assertIsDisplayed()
        compose.onNodeWithText("10 个单词已验证").assertDoesNotExist()
    }

    @Test
    fun twentyFourWordsFitInEightCompactRows() {
        val phrase = org.web3j.crypto.MnemonicUtils.generateMnemonic(ByteArray(32))
        compose.setContent {
            AirVaultTheme {
                Box(Modifier.fillMaxSize()) { PhraseGrid(phrase) }
            }
        }
        val grid = compose.onNodeWithTag("backup-phrase-grid").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(grid.bottom - grid.top <= 340.dp)
        assertTrue(grid.bottom <= compose.onRoot().getUnclippedBoundsInRoot().bottom)
    }
}
