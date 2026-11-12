package com.yosikou.hagemashi

import android.app.Notification
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AnswerDetailTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private fun answer(full: String, used: String) = SavedEncouragement(UUID.randomUUID().toString(), Instant.EPOCH,
        "synthetic-private-69.875 kg", full, used, true, "synthetic-persona", listOf(
            MeasurementRow(MeasureItem.WEIGHT, "69.875", "kg", "2026-11-09T00:00:00Z"),
            MeasurementRow(MeasureItem.WEIGHT, "68.125", "kg", "2026-11-10T00:00:00Z"),
            MeasurementRow(MeasureItem.WEIGHT, "68.125", "kg", "2026-11-10T01:00:00Z"),
            MeasurementRow(MeasureItem.BODY_FAT, null, "%", "", MeasureState.UNPERMITTED),
            MeasurementRow(MeasureItem.STEPS, "1234", "歩", "2026-11-09T00:00:00Z 〜 2026-11-10T00:00:00Z")
        ))

    private fun text(id:Int)=languageContext(composeRule.activity).getString(id)
    private fun value(kg:String):String {
        val cfg=SettingsStore(composeRule.activity).read()
        return localizedNumber(cfg.weightUnit.display(kg),appLocale(cfg.language),2)+" "+cfg.weightUnit.symbol
    }
    @Test fun encryptedAnswersSurviveANewStoreInstanceAndKeepTheirOwnIdentity() {
        val context = composeRule.activity
        val dir = "test-answers-${UUID.randomUUID()}"
        val alias = "hagemashi.test.answers.${UUID.randomUUID()}"
        val a = answer("synthetic-full-A", "synthetic-weight=69.875 at time-A")
        val b = answer("synthetic-full-B", "synthetic-weight=68.125 at time-B")
        try {
            AnswerStore(context, dir, alias).save(a)
            AnswerStore(context, dir, alias).save(b)
            assertEquals(a.full, AnswerStore(context, dir, alias).load(a.id).full)
            assertEquals(a.usedData, AnswerStore(context, dir, alias).load(a.id).usedData)
            assertEquals(b.full, AnswerStore(context, dir, alias).load(b.id).full)
            assertThrows(Problem::class.java) { AnswerStore(context, dir, alias).save(a) }
            val bytes = File(context.noBackupFilesDir, "$dir/${a.id}.v1").readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("69.875"))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains(a.full))
        } finally {
            File(context.noBackupFilesDir, dir).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun notificationExtrasContainOnlySafeShortTextAndNoFullOrUsedData() {
        val record = answer("synthetic-full-69.875", "synthetic-used-data-23.125")
        val notification = AnswerNotifications(composeRule.activity).build(record)
        val fallback = text(R.string.notification_fallback)
        assertEquals(fallback, notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(fallback, notification.extras.getString(Notification.EXTRA_BIG_TEXT))
        assertNotNull(notification.contentIntent)
        assertFalse(notification.extras.toString().contains(record.full))
        assertFalse(notification.extras.toString().contains(record.usedData))
        assertFalse(notification.extras.toString().contains("69.875"))
    }

    @Test fun tappingEarlierNotificationAfterSavingAnotherAnswerOpensEarlierDetailAcrossRecreation() {
        val context = composeRule.activity
        val store = AnswerStore(context)
        val a = answer("synthetic-full-A", "synthetic-used-data-A")
        val b = answer("synthetic-full-B", "synthetic-used-data-B")
        try {
            store.save(a)
            val noticeA = AnswerNotifications(context).build(a)
            store.save(b)
            noticeA.contentIntent.send()
            composeRule.waitUntil(5000) { composeRule.onAllNodes(androidx.compose.ui.test.hasText(a.full)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText(a.full).performScrollTo().assertIsDisplayed()
            composeRule.activityRule.scenario.recreate()
            composeRule.waitUntil(5000) { composeRule.onAllNodes(androidx.compose.ui.test.hasText(a.full)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText(b.full).assertDoesNotExist()
            composeRule.onNodeWithText(noticeA.extras.getString(Notification.EXTRA_TEXT)!!).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.build_identifier, BuildConfig.BUILD_IDENTIFIER)).assertDoesNotExist()
            composeRule.onNodeWithText(value("68.125")).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(value("69.875")).assertDoesNotExist()
            composeRule.onNodeWithText(text(R.string.table_unpermitted)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.detail_expand_data)).performScrollTo().performClick()
            composeRule.onNodeWithText(value("69.875")).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.detail_collapse_data)).performScrollTo().performClick()
            composeRule.onNodeWithText(value("69.875")).assertDoesNotExist()
        } finally {
            for (record in listOf(a, b)) File(context.noBackupFilesDir, "encouragements/${record.id}.v1").delete()
        }
    }
}
