package com.micro.airvault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.micro.airvault.ui.theme.AirVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ExportAddressDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun qrUsesAvailableDialogWidthAndCanBeClosed() {
        var closed = 0
        compose.setContent {
            AirVaultTheme {
                ExportAddressDialog(listOf("airvault-public-address-test"), onDismiss = { closed++ })
            }
        }

        val dialog = compose.onNodeWithTag("address-export-dialog").assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val qr = compose.onNodeWithTag("address-export-qr").assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val size = qr.right - qr.left
        assertTrue(size > 250.dp)
        assertTrue(abs(size.value - (qr.bottom - qr.top).value) < 1f)
        assertTrue(size >= (dialog.right - dialog.left) - 48.dp)

        compose.onNodeWithContentDescription("关闭").performClick()
        assertEquals(1, closed)
    }
}
