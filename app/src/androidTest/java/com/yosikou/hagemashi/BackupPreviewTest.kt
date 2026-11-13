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

class BackupPreviewTest {
    @get:Rule val rule=createComposeRule()
    @Test fun cancelPreviewNeverAppliesSettingsOrHistory() {
        val cfg=AppSettings(goal="68",personas=listOf(Persona("friend","friend","prompt"),Persona("numbers","coach","prompt"),Persona("companion","companion","prompt")))
        var visible by mutableStateOf(true)
        var applied=0
        rule.setContent { LanguageContent(AppLanguage.EN) { HagemashiTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { if(visible)BackupContentsPreview(BackupContents(cfg,emptyList(),false),false,{applied++},{visible=false}) }
        } } }
        rule.onNodeWithText("History: 0 responses").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Replace settings and restore").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(0,applied);assertFalse(visible)}
    }
}
