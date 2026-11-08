package com.micro.airvault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.micro.airvault.ui.theme.AirVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChainSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun opensBottomSheetAndSelectsChainFromScrollableList() {
        var selected by mutableStateOf(WalletChain.ETHEREUM)
        compose.setContent {
            AirVaultTheme { ChainSelector(selected) { selected = it } }
        }

        compose.onNodeWithText("Ethereum").performClick()
        compose.onNodeWithText("选择网络").assertIsDisplayed()
        compose.onNodeWithTag("chain-list").performScrollToIndex(WalletChain.entries.indexOf(WalletChain.SONIC))
        compose.onNodeWithText("Sonic").performClick()
        compose.waitForIdle()
        assertEquals(WalletChain.SONIC, selected)
        compose.onNodeWithText("Sonic").assertIsDisplayed()
    }
}
