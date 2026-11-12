package com.micro.airvault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.micro.airvault.ui.theme.AirVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SigningResultUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun plainJsonSignedTransactionOffersDeepLink() {
        var openCount = 0
        compose.setContent {
            AirVaultTheme {
                SigningResultContent("""{"type":"eth_transaction_result","signedTransaction":"0x1234"}""",
                    emptyList(), { openCount++ }, {})
            }
        }
        compose.onNodeWithText("在本机 AirWallet 中打开").performClick()
        assertEquals(1, openCount)
    }

    @Test
    fun signedQrUsesScreenSpaceAndRawDataRemainsAccessible() {
        var doneCount = 0
        val signed = """{"signedTransaction":"0x1234"}"""
        compose.setContent {
            AirVaultTheme {
                SigningResultContent(signed, listOf("AV1:signed-test"), {}, { doneCount++ })
            }
        }
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val qr = compose.onNodeWithTag("signed-result-qr").assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(qr.right - qr.left >= (root.right - root.left) * 0.8f)

        compose.onNodeWithText("查看签名数据").performClick()
        compose.onNodeWithText(signed).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("完成").performClick()
        assertEquals(1, doneCount)
    }
}
