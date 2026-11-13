package com.yosikou.hagemashi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PersonaImportPreviewTest {
    @get:Rule val rule=createComposeRule()
    @Test fun previewKeepsForeignTextAndCancelDoesNotConfirm() {
        val candidate=Persona("00000000-0000-0000-0000-000000000003","Énergie 友人","一行目\nDeuxième ligne\r\nतीसरी पंक्ति\n last line ",false)
        var language by mutableStateOf(AppLanguage.JA)
        var pending by mutableStateOf<Persona?>(candidate)
        var confirmed=0
        rule.setContent { LanguageContent(language) { HagemashiTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                pending?.let { PersonaImportPreview(it,true,{confirmed++},{pending=null}) }
            }
        } } }
        rule.onNodeWithText(candidate.name).assertIsDisplayed()
        rule.onNodeWithText(candidate.prompt).performScrollTo().assertIsDisplayed()
        rule.runOnIdle{language=AppLanguage.EN}
        rule.onNodeWithText(candidate.prompt).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel import").performScrollTo().performClick()
        rule.onNodeWithText(candidate.prompt).assertDoesNotExist()
        rule.runOnIdle{assertEquals(0,confirmed);assertNull(pending)}
    }
}
