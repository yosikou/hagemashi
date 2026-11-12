package com.yosikou.hagemashi

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LanguageUiTest {
    @get:Rule val rule=createComposeRule()
    @Test fun resourcesSwitchWithoutRestartAndLongLabelsWrap() {
        var language by mutableStateOf(AppLanguage.EN)
        var overflow=false
        rule.setContent { LanguageContent(language) { HagemashiTheme {
            Column(Modifier.width(180.dp)) {
                Text(stringResource(R.string.settings_title))
                Text(stringResource(R.string.common_prompt),onTextLayout={overflow=it.hasVisualOverflow})
            }
        } } }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        for(lang in AppLanguage.entries.filter{it!=AppLanguage.SYSTEM}) {
            rule.runOnIdle{language=lang}
            val expected=languageContext(InstrumentationRegistry.getInstrumentation().targetContext,lang).getString(R.string.settings_title)
            rule.onNodeWithText(expected).assertIsDisplayed()
            rule.runOnIdle{assertFalse("Label overflow: $lang",overflow)}
        }
    }
    @Test fun coldStoreReadKeepsLanguageUnitSelectionAndEditedPersona() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="locale-test-"+UUID.randomUUID()
        val dir=File(base.cacheDir,name).apply{mkdirs()}
        val context=object:ContextWrapper(base){
            override fun getNoBackupFilesDir()=dir
            override fun getSharedPreferences(n:String,m:Int)=base.getSharedPreferences(name+"-"+n,m)
        }
        try {
            val ja=defaultPersonas(languageContext(context,AppLanguage.JA)).map{it.copy(defaultContent=null)}
            val edited=ja[1].copy(prompt=ja[1].prompt+" custom text")
            val legacy=AppSettings(goal="68.123456",personas=listOf(ja[0],edited,ja[2]),personaId="numbers",supplement="原文",weightUnit=WeightUnit.LB)
            val json=encodeSettings(legacy).apply{remove("language")}
            CredentialVault(context,"app-settings.v1","hagemashi.settings.v1").write(json)
            val store=SettingsStore(context)
            val loaded=store.read()
            assertEquals(AppLanguage.SYSTEM,loaded.language)
            for(lang in AppLanguage.entries.filter{it!=AppLanguage.SYSTEM}) {
                store.write(store.read().copy(language=lang))
                val restarted=SettingsStore(context).read()
                val target=defaultPersonas(languageContext(context,lang))
                assertEquals(target[0],restarted.personas[0])
                assertEquals(edited.copy(defaultContent=false),restarted.personas[1])
                assertEquals("numbers",restarted.personaId);assertEquals("原文",restarted.supplement)
                assertEquals("68.123456",restarted.goal);assertEquals(WeightUnit.LB,restarted.weightUnit)
                assertEquals(lang,savedLanguage(context))
                assertEquals(languageContext(context,lang).getString(R.string.persona_friend),restarted.personas[0].name)
            }
        }finally{dir.deleteRecursively();context.getSharedPreferences("app-language",Context.MODE_PRIVATE).edit().clear().commit()}
    }
}
