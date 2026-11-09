package com.yosikou.hagemashi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import android.net.Uri
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun manualConnectionScreenShowsDummyTestAndDisablesGenerationBeforeSignIn() {
        composeRule.onNodeWithText("ChatGPT 手動接続の検証").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with ChatGPT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("診断用：DNS比較付きでサインイン").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ダミーデータで生成").performScrollTo().assertIsNotEnabled()
    }

    @Test fun diagnosticsOpenWithoutSignInOrAutomaticNetworkRequests() {
        composeRule.onNodeWithText("通信診断（認証不要）").performScrollTo().performClick()
        composeRule.onNodeWithText("認証情報なしの通信診断").assertIsDisplayed()
        composeRule.onNodeWithText("DNS名前解決を実行").assertIsDisplayed()
        composeRule.onNodeWithText("HTTPS GETを実行").assertIsDisplayed()
        composeRule.onNodeWithText("認証情報なしの空POSTを実行").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("安全な診断結果をコピー").performScrollTo().assertIsNotEnabled()
    }

    @Test fun installedAppHasInternetAndDisallowsCleartext() {
        val facts = readNetworkFacts(composeRule.activity)
        assertNotNull(facts)
        assertTrue(facts!!.internetGranted)
        assertFalse(facts.cleartextPermitted)
    }

    @Test fun returnLinkResolvesToTheSameActivityWithoutCarryingAnAuthorizationResult() {
        val activity = composeRule.activity
        val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.APPLICATION_ID}://auth-return/"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(BuildConfig.APPLICATION_ID)
        val component = navigation.resolveActivity(activity.packageManager)
        assertNotNull(component)
        assertEquals(MainActivity::class.java.name, component!!.className)
        assertNull(navigation.data?.query)
        assertNull(navigation.extras)
    }

    @Test fun connectionViewModelSurvivesStopAndRecreation() {
        val reference = AtomicReference<ConnectionViewModel>()
        val scenario = composeRule.activityRule.scenario
        scenario.onActivity { reference.set(ViewModelProvider(it)[ConnectionViewModel::class.java]) }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.recreate()
        scenario.onActivity { assertSame(reference.get(), ViewModelProvider(it)[ConnectionViewModel::class.java]) }
    }
}
