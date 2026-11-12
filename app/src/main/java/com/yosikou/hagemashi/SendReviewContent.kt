package com.yosikou.hagemashi

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource

@Composable internal fun SendReviewContent(review:ReviewedGeneration) {
    var expanded by remember(review){mutableStateOf(false)}
    SectionCard {
        Text(stringResource(R.string.manual_preview),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.review_destination))
        Text(stringResource(R.string.manual_model,review.modelName,review.model))
        Text(stringResource(R.string.review_persona,review.personaName ?: stringResource(R.string.persona_not_recorded)))
        Text(stringResource(R.string.review_items),style=MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.review_latest),style=MaterialTheme.typography.titleSmall)
        MeasurementTable(latestAnswerMeasurements(review.rows)+review.rows.filter{it.item in setOf(MeasureItem.GOAL,MeasureItem.GOAL_DATE,MeasureItem.WEIGHT_PERIOD,MeasureItem.STEP_PERIOD)}, weightUnit=review.weightUnit)
        Text(stringResource(R.string.review_frozen),style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick={expanded=!expanded}){Text(stringResource(if(expanded)R.string.review_collapse else R.string.review_expand))}
        if(expanded) {
            Text(stringResource(R.string.weight_review_precision, review.weightUnit.symbol))
            MeasurementTable(review.rows,showGroupingNote=true, weightUnit=review.weightUnit, preciseWeight=true)
            Text(stringResource(R.string.review_persona_prompt),style=MaterialTheme.typography.titleSmall)
            Text(review.personaPrompt.ifBlank{review.instructions})
            Text(stringResource(R.string.review_supplement),style=MaterialTheme.typography.titleSmall)
            Text(review.supplement.ifBlank{stringResource(R.string.review_empty)})
            Text(stringResource(R.string.manual_instructions),style=MaterialTheme.typography.titleSmall)
            Text(review.instructions)
        }
    }
}
