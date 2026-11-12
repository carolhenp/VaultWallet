package com.micro.airvault

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.web3j.crypto.MnemonicUtils

@RunWith(AndroidJUnit4::class)
class WalletNamingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun successfulVerificationOpensNamingAndValidatesName() {
        val phrase = MnemonicUtils.generateMnemonic(ByteArray(32))
        val positions = (0 until 10).toList()
        compose.runOnUiThread {
            val field = MainActivity::class.java.getDeclaredField("protectedMnemonic")
            field.isAccessible = true
            field.set(compose.activity, ProtectedMnemonic.fromString(phrase))
            setState("backupPositions", positions)
            setState("backupAnswers", positions.associateWith { phrase.split(' ')[it] })
            setState("backupKeyboardLetters", "abcdefghijklmnopqrstuvwxyz")
            setState("confirmed", true)
            setState("screen", Screen.BACKUP_VERIFY)
        }
        compose.onNodeWithTag("backup-verify-action").performClick()
        compose.onNodeWithText("设置钱包名称").assertIsDisplayed()
        compose.onNodeWithTag("wallet-name-input").assertIsDisplayed()
        compose.onNodeWithText("钱包1").assertIsDisplayed()
        compose.onNodeWithText("启用生物识别并保存").assertIsEnabled()
        compose.onNodeWithTag("wallet-name-input").performTextReplacement("   ")
        compose.onNodeWithText("启用生物识别并保存").assertIsNotEnabled()
        compose.onNodeWithTag("wallet-name-input").performTextReplacement("x".repeat(31))
        compose.onNodeWithText("启用生物识别并保存").assertIsNotEnabled()
        compose.onNodeWithTag("wallet-name-input").performTextReplacement("日常钱包")
        compose.onNodeWithText("启用生物识别并保存").assertIsEnabled()
        compose.runOnUiThread {
            MainActivity::class.java.getDeclaredMethod("goBack").apply { isAccessible = true }.invoke(compose.activity)
        }
        compose.onNodeWithTag("backup-verification").assertIsDisplayed()
        compose.onNodeWithTag("backup-verify-action").assertIsDisplayed()
    }

    @Test
    fun failedVerificationStaysOnVerificationPage() {
        compose.runOnUiThread {
            val field = MainActivity::class.java.getDeclaredField("protectedMnemonic")
            field.isAccessible = true
            field.set(compose.activity, ProtectedMnemonic.fromString(MnemonicUtils.generateMnemonic(ByteArray(32))))
            setState("backupPositions", (0 until 10).toList())
            setState("backupAnswers", (0 until 10).associateWith { "zoo" })
            setState("backupKeyboardLetters", "abcdefghijklmnopqrstuvwxyz")
            setState("screen", Screen.BACKUP_VERIFY)
        }
        compose.onNodeWithTag("backup-verify-action").performClick()
        compose.onNodeWithTag("wallet-name-input").assertDoesNotExist()
        compose.onNodeWithText("有 10 个单词不正确，请修改标记的序号后重新验证").assertIsDisplayed()
    }

    private fun <T> setState(name: String, value: T) {
        val field = MainActivity::class.java.getDeclaredField("${name}\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(compose.activity) as MutableState<T>).value = value
    }
}
