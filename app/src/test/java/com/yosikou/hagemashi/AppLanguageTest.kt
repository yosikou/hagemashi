package com.yosikou.hagemashi

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class AppLanguageTest {
    private val old=listOf(Persona("friend","old friend","old prompt"),Persona("numbers","old coach","old numbers"),Persona("companion","old companion","old companion prompt"))
    private fun translated(tag:String)=old.map{it.copy(name=tag+it.id,prompt=tag+it.prompt,defaultContent=true)}
    @Test fun deviceLanguageResolutionHonorsOrderAndFallsBackToEnglish() {
        assertEquals(AppLanguage.EN,resolveDeviceLanguage(listOf(Locale("es"),Locale("ar"))))
        assertEquals(AppLanguage.FR,resolveDeviceLanguage(listOf(Locale("es"),Locale.FRANCE,Locale.JAPAN)))
        assertEquals(AppLanguage.EN,resolveDeviceLanguage(listOf(Locale.TAIWAN)))
        assertEquals(AppLanguage.ZH,resolveDeviceLanguage(listOf(Locale.forLanguageTag("zh-Hans"))))
        assertEquals(AppLanguage.DE,resolveDeviceLanguage(listOf(Locale.forLanguageTag("zh-Hant"),Locale.GERMAN)))
        for(lang in AppLanguage.entries.filter{it!=AppLanguage.SYSTEM})assertEquals(lang.tag,lang.locale(emptyList()).toLanguageTag())
    }
    @Test fun systemIsDefaultAndOldSettingsAndUnitAreIndependent() {
        val original=AppSettings(goal="68.123456",personas=old,weightUnit=WeightUnit.LB)
        val json=encodeSettings(original).apply{remove("language")}
        assertEquals(AppLanguage.SYSTEM,decodeSettings(json,old).language)
        var cfg=original
        for(lang in AppLanguage.entries)for(unit in WeightUnit.entries) {
            cfg=decodeSettings(encodeSettings(cfg.copy(language=lang,weightUnit=unit)),old)
            assertEquals(lang,cfg.language);assertEquals(unit,cfg.weightUnit);assertEquals(original.goal,cfg.goal)
        }
    }
    @Test fun editedInitialAndCustomPersonasKeepOriginalTextAndSelection() {
        val edited=old[1].copy(prompt=old[1].prompt+" ")
        val custom=Persona("custom","私の人格","mon texte",false)
        val cfg=AppSettings(personas=listOf(old[0],edited,old[2],custom),personaId="numbers",supplement="私の補足")
        val en=translateDefaultPersonas(cfg,old,translated("en"))
        assertEquals("enfriend",en.personas[0].name);assertEquals(true,en.personas[0].defaultContent)
        assertEquals(edited.copy(defaultContent=false),en.personas[1]);assertEquals(custom,en.personas[3])
        assertEquals(cfg.personaId,en.personaId);assertEquals(cfg.supplement,en.supplement)
        val fr=translateDefaultPersonas(decodeSettings(encodeSettings(en),old),old+translated("en"),translated("fr"))
        assertEquals("frfriend",fr.personas[0].name);assertEquals(edited.copy(defaultContent=false),fr.personas[1])
        // Explicit edits stay edited even if the text later exactly matches a built-in default.
        val explicit=cfg.copy(personas=old.map{it.copy(defaultContent=false)})
        assertEquals(explicit,translateDefaultPersonas(explicit,old,translated("en")))
    }
    @Test fun localeDecimalsAreAcceptedWithoutAmbiguousGroupingOrUnitDrift() {
        for(locale in listOf(Locale.FRANCE,Locale.GERMANY)) {
            assertEquals("68.123456",localizedWeightInput("68,123456",WeightUnit.KG,locale))
            assertEquals("68.0388555",localizedWeightInput("150,0",WeightUnit.LB,locale))
            assertNull(localizedWeightInput("1.234,5",WeightUnit.KG,locale))
            assertEquals("68,25",localizedNumber("68.25",locale))
        }
        assertNull(localizedWeightInput("68,5",WeightUnit.KG,Locale.ENGLISH))
        assertEquals("68.25",localizedWeightInput("६८.२५",WeightUnit.KG,Locale.forLanguageTag("hi")))
    }
    @Test fun safeInternationalNotificationsAreNotRejectedForLatinOrIndicLetters() {
        val examples=listOf("Keep going at your own pace. Your efforts matter.","Avancez à votre rythme. Vos efforts comptent.","Bleiben Sie in Ihrem Tempo dabei. Ihr Einsatz zählt.","अपनी गति से आगे बढ़ें। आपकी कोशिशें मायने रखती हैं।")
        for(text in examples)assertEquals(text,notificationShort(text,"REJECTED"))
        for(value in listOf("150 lb","seventy kg","70,5 kg","soixante kilos","siebzig kg","७० किलो","七十公斤")) {
            val text="Keep going at your own pace. "+value+" matters today."
            assertEquals(value,"SAFE",notificationShort(text,"SAFE"))
        }
    }
}
