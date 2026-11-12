package com.yosikou.hagemashi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class WeightUnitUiTest {
    @get:Rule val rule=createComposeRule()
    @Test fun reviewedUnitIsFrozenEvenIfCurrentPreferenceDiffers() {
        val review=ReviewedGeneration("model","model","rules","input remains frozen",true,"persona",listOf(
            MeasurementRow(MeasureItem.WEIGHT,"68.0388555","kg","2026-11-01T00:00:00Z")),weightUnit=WeightUnit.LB)
        rule.setContent { HagemashiTheme { CompositionLocalProvider(LocalWeightUnit provides WeightUnit.KG) {
            Column(Modifier.verticalScroll(rememberScrollState())) { SendReviewContent(review) }
        } } }
        rule.onNodeWithText("150 lb").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("68.04 kg").assertDoesNotExist()
        rule.onNodeWithText("送信データをすべて見る").performScrollTo().performClick()
        rule.onAllNodesWithText("150 lb").assertCountEquals(2)
        assertEquals("input remains frozen",review.input)
        assertEquals("68.0388555",review.rows.single().value)
    }
}
