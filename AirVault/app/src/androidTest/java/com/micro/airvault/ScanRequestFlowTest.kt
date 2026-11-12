package com.micro.airvault

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanRequestFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun invalidScanShowsDialogAndValidScanOpensApprovalWithoutManualEntry() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            "com.micro.airvault", Manifest.permission.CAMERA
        )
        compose.runOnUiThread {
            compose.activity.setScreen(Screen.SCAN)
            compose.activity.acceptScannedPayload("not-a-signing-request")
        }
        compose.onNodeWithText("无法读取签名请求").assertIsDisplayed()
        compose.runOnUiThread { assertEquals(Screen.SCAN, compose.activity.screenState()) }
        compose.onNodeWithText("继续扫描").performClick()
        compose.runOnUiThread { compose.activity.acceptScannedPayload("not-a-signing-request") }
        compose.onNodeWithText("无法读取签名请求").assertDoesNotExist()

        compose.runOnUiThread {
            compose.activity.acceptScannedPayload(
                """{"type":"eth_personal_sign","chainId":999,"message":"test"}"""
            )
        }
        compose.onNodeWithText("不支持的网络").assertIsDisplayed()
        compose.runOnUiThread { assertEquals(Screen.SCAN, compose.activity.screenState()) }
        compose.onNodeWithText("继续扫描").performClick()

        compose.runOnUiThread {
            compose.activity.acceptScannedPayload(
                """{"type":"eth_transaction","chainId":1,"nonce":"2","to":"0x000000000000000000000000000000000000dEaD","value":"1000000000000000000","gasLimit":"21000","gasPrice":"1000000000","data":"0x"}"""
            )
        }
        compose.onNodeWithText("0x000000000000000000000000000000000000dEaD").assertIsDisplayed()
        compose.onNodeWithText("1 ETH").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { assertEquals(Screen.REVIEW, compose.activity.screenState()) }

        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnUiThread { assertEquals(Screen.SCAN, compose.activity.screenState()) }
    }

    private fun MainActivity.setScreen(value: Screen) {
        val field = MainActivity::class.java.getDeclaredField("screen\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as MutableState<Screen>).value = value
    }

    private fun MainActivity.screenState(): Screen {
        val field = MainActivity::class.java.getDeclaredField("screen\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(this) as MutableState<Screen>).value
    }

    private fun MainActivity.acceptScannedPayload(payload: String) {
        val method = MainActivity::class.java.getDeclaredMethod(
            "acceptPayload", String::class.java, Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(this, payload, false)
    }
}
