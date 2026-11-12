package com.micro.airvault

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.web3j.crypto.MnemonicUtils

@RunWith(AndroidJUnit4::class)
class MnemonicLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun backupAndRecoveryStayOpenButHidePhraseAfterBackgrounding() {
        val phrase = MnemonicUtils.generateMnemonic(ByteArray(32))
        for (page in listOf(Screen.BACKUP, Screen.RECOVERY)) {
            compose.runOnUiThread {
                compose.activity.setProtectedPhrase(phrase)
                compose.activity.setState("phrase", phrase)
                compose.activity.setState("screen", page)
            }
            compose.onNodeWithText("查看助记词").performClick()
            compose.onNodeWithText("查看助记词").assertDoesNotExist()

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

            compose.onNodeWithText("查看助记词").assertIsDisplayed()
            compose.runOnUiThread {
                assertEquals(page, compose.activity.getState<Screen>("screen"))
                assertEquals(phrase, compose.activity.getState<String>("phrase"))
            }
        }
    }

    @Test
    fun verificationAnswersSurviveBackgrounding() {
        val positions = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18)
        compose.runOnUiThread {
            compose.activity.setProtectedPhrase(MnemonicUtils.generateMnemonic(ByteArray(32)))
            compose.activity.setState("backupPositions", positions)
            compose.activity.setState("backupAnswers", mapOf(0 to "abandon"))
            compose.activity.setState("backupKeyboardLetters", "abcdefghijklmnopqrstuvwxyz")
            compose.activity.setState("screen", Screen.BACKUP_VERIFY)
        }
        compose.onNodeWithText("1 / 10 已填写").assertIsDisplayed()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithText("1 / 10 已填写").assertIsDisplayed()
        compose.onNodeWithTag("backup-verify-action").assertIsDisplayed()
        compose.runOnUiThread {
            assertEquals(Screen.BACKUP_VERIFY, compose.activity.getState<Screen>("screen"))
            assertEquals(mapOf(0 to "abandon"), compose.activity.getState<Map<Int, String>>("backupAnswers"))
        }
    }

    private fun <T> MainActivity.setState(name: String, value: T) {
        val field = MainActivity::class.java.getDeclaredField("${name}\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as MutableState<T>).value = value
    }

    private fun <T> MainActivity.getState(name: String): T {
        val field = MainActivity::class.java.getDeclaredField("${name}\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(this) as MutableState<T>).value
    }

    private fun MainActivity.setProtectedPhrase(value: String) {
        val field = MainActivity::class.java.getDeclaredField("protectedMnemonic")
        field.isAccessible = true
        (field.get(this) as? ProtectedMnemonic)?.close()
        field.set(this, ProtectedMnemonic.fromString(value))
    }
}
