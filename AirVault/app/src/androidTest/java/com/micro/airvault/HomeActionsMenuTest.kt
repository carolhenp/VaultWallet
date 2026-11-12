package com.micro.airvault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.micro.airvault.ui.theme.AirVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeActionsMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun recoveryAndDeleteAreAvailableFromHomeMenu() {
        var recoveryCount = 0
        var deleteCount = 0
        var customChainCount = 0
        compose.setContent {
            AirVaultTheme {
                HomeActionsMenu(onRecovery = { recoveryCount++ }, onDelete = { deleteCount++ },
                    onAddCustomChain = { customChainCount++ })
            }
        }

        compose.onNodeWithText("查看助记词").assertDoesNotExist()
        compose.onNodeWithTag("home-actions-menu").assertIsDisplayed().performClick()
        compose.onNodeWithText("添加钱包").assertDoesNotExist()
        compose.onNodeWithText("添加自定义链").assertIsDisplayed().performClick()
        assertEquals(1, customChainCount)
        compose.onNodeWithTag("home-actions-menu").performClick()
        compose.onNodeWithText("查看助记词").assertIsDisplayed().performClick()
        assertEquals(1, recoveryCount)
        compose.onNodeWithText("查看助记词").assertDoesNotExist()

        compose.onNodeWithTag("home-actions-menu").performClick()
        compose.onNodeWithText("销毁钱包").assertIsDisplayed().performClick()
        assertEquals(1, deleteCount)
        compose.onNodeWithText("销毁钱包").assertDoesNotExist()
    }
}
