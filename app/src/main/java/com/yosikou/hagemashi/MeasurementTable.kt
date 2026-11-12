package com.yosikou.hagemashi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable internal fun MeasurementTable(rows: List<MeasurementRow>, showGroupingNote: Boolean = false, compactStepDates: Boolean = false, weightUnit: WeightUnit = LocalWeightUnit.current, preciseWeight: Boolean = false) {
    val zone = java.time.ZoneId.systemDefault()
    val display = measurementDisplay(rows, zone)
    if (showGroupingNote && display.combined) Text(stringResource(R.string.table_grouping_note))
    MeasureItem.entries.forEach { item ->
        val selected = display.rows.filter { it.item == item }.map { weightDisplayRow(it, weightUnit, preciseWeight) }
        if (selected.isNotEmpty()) {
            val label = stringResource(when(item) {
                MeasureItem.WEIGHT -> R.string.health_weight; MeasureItem.BODY_FAT -> R.string.health_body_fat
                MeasureItem.STEPS -> R.string.table_steps; MeasureItem.GOAL -> R.string.goal_settings
                MeasureItem.GOAL_DATE -> R.string.goal_date_label; MeasureItem.READ_AT -> R.string.table_read_at
                MeasureItem.WEIGHT_PERIOD -> R.string.table_weight_period; MeasureItem.STEP_PERIOD -> R.string.table_step_period
                MeasureItem.LEGACY -> R.string.table_legacy
            })
            Text(label, style=MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.table_item),Modifier.weight(.25f)); Text(stringResource(R.string.table_value),Modifier.weight(.25f))
                Text(stringResource(R.string.table_date),Modifier.weight(.5f))
            }
            HorizontalDivider()
            selected.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(label,Modifier.weight(.25f))
                    Text(if(row.state != MeasureState.VALUE) stringResource(when(row.state) {
                        MeasureState.UNPERMITTED -> R.string.table_unpermitted; MeasureState.NO_RECORDS -> R.string.table_no_records
                        else -> R.string.health_read_error
                    }) else row.value?.let { measurementValueText(row,LocalAppLocale.current,stringResource(R.string.step_unit)) } ?: stringResource(R.string.table_not_applicable),Modifier.weight(.25f))
                    Text(if (row.at.isBlank()) stringResource(if (row.state == MeasureState.VALUE) R.string.table_setting_at_generation else R.string.table_not_applicable) else (if (compactStepDates && row.item == MeasureItem.STEPS) measurementDisplayStepDates(row.at, zone,LocalAppLocale.current) else measurementDisplayTime(row.at, zone,LocalAppLocale.current)) ?: stringResource(R.string.table_unknown_time),Modifier.weight(.5f))
                }
                if(row.origin.isNotBlank()) Text(row.origin,style=MaterialTheme.typography.labelSmall)
                HorizontalDivider()
            }
        }
    }
}
@Composable internal fun generationKindLabel(kind: GenerationKind): String = stringResource(when(kind) {
    GenerationKind.MORNING -> R.string.kind_morning; GenerationKind.EVENING -> R.string.kind_evening; GenerationKind.MANUAL -> R.string.kind_manual
})
