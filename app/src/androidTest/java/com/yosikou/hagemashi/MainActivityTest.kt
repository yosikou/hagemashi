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
import android.content.pm.PackageManager
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsSettingsAndRoutesToGoalWithoutAutomaticallySending() {
        composeRule.onNodeWithText("hagemashi").assertIsDisplayed()
        composeRule.onNodeWithText("まだ励ましがありません。目標を設定して始めましょう。").assertIsDisplayed()
        composeRule.onNodeWithText("表示内容を確認してChatGPTへ送信").assertDoesNotExist()
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("設定")).performClick()
        composeRule.onNodeWithText("人格").performScrollTo().performClick()
        composeRule.onNodeWithText("友人のようなトレーナー").assertIsDisplayed()
        composeRule.onNodeWithText("＋ 人格を追加").performScrollTo().assertIsDisplayed()
    }

    @Test fun diagnosticsRemainUnderSettingsWithoutAutomaticNetworkRequests() {
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("設定")).performClick()
        composeRule.onNodeWithText("通信診断（認証不要）").performScrollTo().performClick()
        composeRule.onNodeWithText("認証情報なしの通信診断").assertIsDisplayed()
        composeRule.onNodeWithText("安全な診断結果をコピー").performScrollTo().assertIsNotEnabled()
    }

    @Test fun reservationDiagnosticScreenShowsSafePersistedReportWithoutSchedulingOrAI() {
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("設定")).performClick()
        composeRule.onNodeWithText("予約通知の診断").performScrollTo().performClick()
        composeRule.waitUntil(5000) { composeRule.onAllNodes(androidx.compose.ui.test.hasText("schema=HAGEMASHI_RESERVATION_V1",substring=true)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("予約診断をコピー").performScrollTo().assertIsDisplayed()
    }

    @Test fun installedAppHasInternetAndDisallowsCleartext() {
        val facts = readNetworkFacts(composeRule.activity)
        assertNotNull(facts)
        assertTrue(facts!!.internetGranted)
        assertFalse(facts.cleartextPermitted)
    }

    @Test fun healthManifestRequestsThreeKindsAndExplicitBackgroundRead() {
        val activity = composeRule.activity
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
        val health = info.requestedPermissions.orEmpty().filter { it.startsWith("android.permission.health.") }.toSet()
        assertEquals(setOf("android.permission.health.READ_WEIGHT", "android.permission.health.READ_BODY_FAT", "android.permission.health.READ_STEPS", "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"), health)
        val rationale = Intent("android.intent.action.VIEW_PERMISSION_USAGE")
            .addCategory("android.intent.category.HEALTH_PERMISSIONS").setPackage(activity.packageName)
        @Suppress("DEPRECATION")
        val handlers = activity.packageManager.queryIntentActivities(rationale, 0)
        assertTrue(handlers.any { it.activityInfo.name.endsWith(".ViewHealthPermissionUsageActivity") })
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
