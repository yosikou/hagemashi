package com.yosikou.hagemashi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun manualConnectionScreenShowsDummyTestAndDisablesGenerationBeforeSignIn() {
        composeRule.onNodeWithText("ChatGPT 手動接続の検証").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
        composeRule.onNodeWithText("ダミーデータで生成").performScrollTo().assertIsNotEnabled()
    }
}
