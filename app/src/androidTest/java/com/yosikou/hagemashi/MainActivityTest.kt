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
import org.junit.Before
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

    @Before fun resetLocalConsentForThisTest() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val store=SettingsStore(activity)
            store.write(store.read().copy(consentControlsVersion=DATA_CONSENT_VERSION,consentShownVersion=0,consentAcceptedVersion=0,automaticGeneration=false))
            ViewModelProvider(activity)[AppViewModel::class.java].refresh()
        }
        composeRule.waitUntil(5000) { ViewModelProvider(composeRule.activity)[AppViewModel::class.java].settings?.consentShownVersion==0 }
    }
    private fun openSettingsAndDecline() {
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription(text(R.string.settings_title))).performClick()
        composeRule.waitUntil(5000) { !ViewModelProvider(composeRule.activity)[AppViewModel::class.java].saving }
        composeRule.onNodeWithText(text(R.string.consent_later)).performScrollTo().performClick()
        composeRule.waitUntil(5000) { !ViewModelProvider(composeRule.activity)[AppViewModel::class.java].saving }
    }
    @Test fun consentOnlyAppearsInSettingsAndDecliningKeepsSettingsUsable() {
        composeRule.onNodeWithText(text(R.string.consent_accept)).assertDoesNotExist()
        openSettingsAndDecline()
        composeRule.onNodeWithText(text(R.string.persona_settings)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.consent_title)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.consent_accept)).performScrollTo().assertIsDisplayed()
        assertFalse(hasDataConsent(SettingsStore(composeRule.activity).read()))
    }

    private fun text(id:Int)=languageContext(composeRule.activity).getString(id)

    @Test fun homeShowsSettingsAndRoutesToGoalWithoutAutomaticallySending() {
        composeRule.onNodeWithText(text(R.string.home_name)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.no_encouragement)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.manual_send)).assertDoesNotExist()
        openSettingsAndDecline()
        composeRule.onNodeWithText(text(R.string.persona_settings)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.persona_friend)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.add_persona)).performScrollTo().assertIsDisplayed()
    }

    @Test fun diagnosticsRemainUnderSettingsWithoutAutomaticNetworkRequests() {
        openSettingsAndDecline()
        if(!diagnosticUi()) {
            composeRule.onNodeWithText(text(R.string.open_probes)).assertDoesNotExist()
            composeRule.onNodeWithText(text(R.string.diagnostic_generation)).assertDoesNotExist()
            return
        }
        composeRule.onNodeWithText(text(R.string.open_probes)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.probe_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.probe_copy)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun reservationDiagnosticScreenShowsSafePersistedReportWithoutSchedulingOrAI() {
        openSettingsAndDecline()
        if(!diagnosticUi()) {composeRule.onNodeWithText(text(R.string.reservation_diagnostics)).assertDoesNotExist();return}
        composeRule.onNodeWithText(text(R.string.reservation_diagnostics)).performScrollTo().performClick()
        composeRule.waitUntil(5000) { composeRule.onAllNodes(androidx.compose.ui.test.hasText("schema=HAGEMASHI_RESERVATION_V1",substring=true)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(text(R.string.reservation_diagnostic_copy)).performScrollTo().assertIsDisplayed()
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
