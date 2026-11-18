package com.yosikou.hagemashi

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import java.util.UUID

/** Opt-in only: runs on a disposable emulator, never on a user's populated installation. */
@RunWith(AndroidJUnit4::class)
class StorePhoneScreenshotsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun captureImplementedPhoneScreensWithFictionalRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("storeScreenshots") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("com.yosikou.hagemashi", context.packageName)
        assertFalse(BuildConfig.BUILD_TYPE == "diagnostic")
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val settings = SettingsStore(context)
        val initial = settings.read()
        settings.write(initial.copy(goal="68", goalDate=LocalDate.of(2026,12,31), language=AppLanguage.JA,
            weightUnit=WeightUnit.KG, personas=defaultPersonas(languageContext(context,AppLanguage.JA)),personaId="friend",
            automaticGeneration=false, resumeRequired=false, consentControlsVersion=DATA_CONSENT_VERSION,
            consentShownVersion=DATA_CONSENT_VERSION, consentAcceptedVersion=0))
        val strings = languageContext(context,AppLanguage.JA)
        val short = "今日も記録を続けた自分に、やさしい言葉を。できたことを大切にしながら、無理のないペースで進みましょう。"
        assertEquals(short,notificationShort(short,strings.getString(R.string.notification_fallback)))
        val full = "今日も記録を残せましたね。忙しい日にも自分の体調に目を向けられたことは、続ける力につながっています。\n\n体重には日々の揺れがあります。今日の数字だけで良し悪しを決めず、これまでの流れをゆっくり見ていきましょう。歩いたことや休めたことにも目を向けて、自分のペースを大切にしてくださいね。\n\n今夜は無理をせず、明日の自分が取り組みやすい小さな準備をしてみましょう。\n\n※架空の記録を使った表示例です。"
        val rows = listOf(
            MeasurementRow(MeasureItem.GOAL,"68","kg",""),
            MeasurementRow(MeasureItem.WEIGHT,"69.2","kg","2026-11-16T07:30:00+09:00",origin="com.example.sample"),
            MeasurementRow(MeasureItem.WEIGHT,"68.9","kg","2026-11-18T07:30:00+09:00",origin="com.example.sample"),
            MeasurementRow(MeasureItem.BODY_FAT,"22.5","%","2026-11-18T07:30:00+09:00",origin="com.example.sample"),
            MeasurementRow(MeasureItem.STEPS,"6420","歩","2026-11-18T00:00:00+09:00 〜 2026-11-18T20:40:00+09:00",origin="com.example.sample"))
        val store=AnswerStore(context)
        store.deleteAll()
        val latest=SavedEncouragement(UUID.randomUUID().toString(),Instant.parse("2026-11-18T11:45:00Z"),short,full,
            "撮影用の架空データです。実際の測定記録やAIへの送信結果ではありません。",true,
            strings.getString(R.string.persona_friend),rows,GenerationKind.MANUAL)
        store.save(SavedEncouragement(UUID.randomUUID().toString(),Instant.parse("2026-11-17T12:00:00Z"),
            "続けられたことを大切にして、今夜は自分のペースでゆっくり休みましょう。明日も気負わず始めていけます。",full,"架空データ",true,
            strings.getString(R.string.persona_companion),rows,GenerationKind.EVENING))
        store.save(SavedEncouragement(UUID.randomUUID().toString(),Instant.parse("2026-11-17T22:00:00Z"),
            "今日の予定に合わせて、できそうなことから始めましょう。無理なく続ける気持ちが、これからの力になります。",full,"架空データ",true,
            strings.getString(R.string.persona_numbers),rows,GenerationKind.MORNING))
        store.save(latest)
        val output=requireNotNull(context.getExternalFilesDir("store-screenshots"))
        output.mkdirs()
        fun text(id:Int)=strings.getString(id)
        fun capture(name:String) {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertEquals(1080,image.width);assertEquals(1920,image.height)
            File(output,name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            image.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(15000){compose.onAllNodesWithText(short).fetchSemanticsNodes().isNotEmpty()}
            scenario.onActivity {
                assertFalse(ViewModelProvider(it)[ConnectionViewModel::class.java].ui.permitted)
                assertFalse(hasDataConsent(SettingsStore(it).read()))
            }
            capture("01-home.png")
            compose.onNodeWithText(text(R.string.read_full)).performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText(full).fetchSemanticsNodes().isNotEmpty()}
            capture("02-answer-detail.png")
            compose.onNodeWithText(text(R.string.app_back)).performClick()
            compose.onNodeWithText(text(R.string.history_title)).performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText(text(R.string.generated_persona).format(strings.getString(R.string.persona_numbers))).fetchSemanticsNodes().isNotEmpty()}
            capture("03-history.png")
            compose.onNodeWithText(text(R.string.app_back)).performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_title)).performClick()
            compose.onNodeWithText(text(R.string.persona_settings)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.persona_friend)).assertIsDisplayed()
            capture("04-personas.png")
        }
    }
}
