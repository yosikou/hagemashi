package com.yosikou.hagemashi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class SendReviewTest {
    @get:Rule val rule=createComposeRule()
    @Test fun overviewHidesFullHistoryAndPrivatePromptsUntilExpanded() {
        val review=ReviewedGeneration("synthetic-model","Synthetic Model","synthetic-complete-instructions","unchanged-input",true,"synthetic-persona",listOf(
            MeasurementRow(MeasureItem.WEIGHT,"70","kg","2026-11-01T00:00:00Z"),
            MeasurementRow(MeasureItem.WEIGHT,"69","kg","2026-11-02T00:00:00Z")),personaPrompt="synthetic-persona-prompt",supplement="synthetic-supplement")
        rule.setContent{HagemashiTheme{Column(Modifier.verticalScroll(rememberScrollState())){SendReviewContent(review)}}}
        rule.onNodeWithText("69 kg").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("70 kg").assertDoesNotExist()
        rule.onNodeWithText(review.personaPrompt).assertDoesNotExist()
        rule.onNodeWithText("送信データをすべて見る").performScrollTo().performClick()
        rule.onNodeWithText("70 kg").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(review.personaPrompt).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(review.supplement).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("送信データを閉じる").performScrollTo().performClick()
        rule.onNodeWithText(review.personaPrompt).assertDoesNotExist()
        org.junit.Assert.assertEquals("unchanged-input",review.input)
    }
}
