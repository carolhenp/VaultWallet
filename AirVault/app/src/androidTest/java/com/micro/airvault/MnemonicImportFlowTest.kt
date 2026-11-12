package com.micro.airvault

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MnemonicImportFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun customKeyboardImportsValidMnemonicAfterCorrectingChecksum() {
        openImport()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("import-next").assertIsNotEnabled()
        repeat(12) { enterWord("aban", "abandon") }
        compose.runOnIdle {
            assertEquals(12, state<MnemonicImportState>("mnemonicImport").filled)
        }
        compose.onNodeWithTag("import-next").assertIsEnabled().performClick()
        compose.onNodeWithText("助记词校验失败，请核对单词及顺序").assertIsDisplayed()
        compose.onNodeWithTag("wallet-name-input").assertDoesNotExist()

        compose.onNodeWithTag("import-word-grid").performScrollToIndex(3)
        compose.onNodeWithTag("import-word-11").performClick()
        enterWord("abo", "about")
        compose.onNodeWithTag("import-next").performClick()
        compose.waitUntil(10_000) { state<Screen>("screen") == Screen.WALLET_NAME }
        compose.onNodeWithText("设置钱包名称").assertIsDisplayed()
        compose.onNodeWithText("钱包1").assertIsDisplayed()
        compose.runOnIdle {
            val field = MainActivity::class.java.getDeclaredField("protectedMnemonic").apply { isAccessible = true }
            val protected = field.get(compose.activity) as ProtectedMnemonic
            assertEquals("${List(11) { "abandon" }.joinToString(" ")} about", protected.withMnemonic { it })
            assertEquals(0, state<MnemonicImportState>("mnemonicImport").filled)
        }
        compose.runOnUiThread {
            MainActivity::class.java.getDeclaredMethod("goBack").apply { isAccessible = true }.invoke(compose.activity)
        }
        compose.onNodeWithTag("import-keyboard").assertIsDisplayed()
        compose.onNodeWithTag("import-candidates").onChildren().assertCountEquals(0)
        compose.onNodeWithTag("import-next").assertIsNotEnabled()
    }

    @Test
    fun wordCountSelectorUsesThreeColumnsAndKeepsKeyboardFixedWhenScrolling() {
        openImport()
        for (count in listOf(12, 15, 18, 21, 24)) {
            compose.onNodeWithTag("import-word-count").performClick()
            compose.onNodeWithTag("import-count-$count").performClick()
            compose.onNodeWithText("$count 词").assertIsDisplayed()
            compose.waitForIdle()
            val first = compose.onNodeWithTag("import-word-0").getUnclippedBoundsInRoot()
            val second = compose.onNodeWithTag("import-word-1").getUnclippedBoundsInRoot()
            val third = compose.onNodeWithTag("import-word-2").getUnclippedBoundsInRoot()
            assertEquals(first.top, second.top)
            assertEquals(first.top, third.top)
            assertTrue(first.left < second.left && second.left < third.left)
            val keyboard = compose.onNodeWithTag("import-keyboard").getUnclippedBoundsInRoot()
            compose.onNodeWithTag("import-word-grid").performScrollToIndex(count / 3 - 1)
            compose.onNodeWithTag("import-word-${count - 1}").assertIsDisplayed().performClick()
            assertEquals(keyboard, compose.onNodeWithTag("import-keyboard").getUnclippedBoundsInRoot())
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun backgroundingClearsEnteredWordsAndUnfinishedKeyboardInput() {
        openImport()
        compose.onNodeWithTag("import-word-count").performClick()
        compose.onNodeWithTag("import-count-24").performClick()
        enterWord("aban", "abandon")
        compose.onNodeWithTag("import-letter-a").performClick()
        compose.onNodeWithTag("import-candidate-abandon").assertIsDisplayed()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithTag("import-keyboard").assertIsDisplayed()
        compose.onNodeWithTag("import-candidates").onChildren().assertCountEquals(0)
        compose.runOnIdle {
            val input = state<MnemonicImportState>("mnemonicImport")
            assertEquals(24, input.wordCount)
            assertEquals(0, input.filled)
            assertEquals(0, input.active)
        }
    }

    private fun openImport() {
        compose.waitUntil(10_000) { !state<Boolean>("busy") && state<Screen>("screen") != Screen.SPLASH }
        compose.onNodeWithText("导入助记词").performClick()
        compose.onNodeWithTag("mnemonic-import").assertIsDisplayed()
    }

    private fun enterWord(prefix: String, word: String) {
        prefix.forEach { compose.onNodeWithTag("import-letter-$it").performClick() }
        compose.onNodeWithTag("import-candidate-$word").performClick()
    }

    private fun <T> state(name: String): T {
        val field = MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(compose.activity) as MutableState<T>).value
    }
}
