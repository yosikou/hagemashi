package com.yosikou.hagemashi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId

@Composable
internal fun AnswerDetailScreen(id: String, model: AnswerDetailViewModel, back: () -> Unit) {
    LaunchedEffect(id) { model.load(id) }
    DisposableEffect(Unit) { onDispose { model.close() } }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::permissionResult)
    Page(R.string.detail_title, back, titleText=model.answer?.takeIf{it.id==id}?.let{
        answerTitle(it,stringResource(R.string.notification_fallback),stringResource(R.string.detail_title))
    }) {
        if (model.loading) Text(stringResource(R.string.loading))
        if(model.deleted) Text(stringResource(R.string.history_deleted))
        if (model.loadFailed) {
            Text(stringResource(R.string.detail_load_failed))
            OutlinedButton(onClick = { model.load(id) }) { Text(stringResource(R.string.detail_retry)) }
        }
        model.answer?.takeIf { it.id == id }?.let { answer ->
            SavedAnswerContent(answer)
            if(diagnosticUi()) SectionCard {
                Text(stringResource(R.string.notification_test_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.notification_test_notice))
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        model.awaitPermission(answer.id)
                        runCatching { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure { model.permissionResult(false) }
                    } else model.testNotification(answer.id)
                }, enabled = !model.notifying) { Text(stringResource(R.string.notification_test)) }
                if (model.noticeId == id && model.notice != NoticeState.IDLE) Text(stringResource(when (model.notice) {
                    NoticeState.POSTED -> R.string.notification_posted
                    NoticeState.PERMISSION_DENIED -> R.string.notification_denied
                    NoticeState.DISABLED -> R.string.notification_disabled
                    NoticeState.FAILED -> R.string.notification_failed
                    else -> R.string.notification_sending
                }))
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) } }) {
                    Text(stringResource(R.string.notification_settings))
                }
            }
        }
    }
}


@Composable internal fun SavedAnswerContent(answer: SavedEncouragement) {
    var expanded by rememberSaveable(answer.id) { mutableStateOf(false) }
    val rows = answer.rows.ifEmpty { legacyMeasurementRows(answer.usedData) }
    val latest = latestAnswerMeasurements(rows)
    SectionCard {
        AnswerMetadata(answer)
        HorizontalDivider()
        SelectionContainer { Text(answer.full, style = MaterialTheme.typography.bodyLarge) }
    }
    SectionCard {
        Text(stringResource(R.string.detail_used_data), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.detail_saved_snapshot), style = MaterialTheme.typography.bodyMedium)
        if (!answer.hasHealthData) Text(stringResource(R.string.detail_health_missing))
        latest.forEach { original ->
            val row = weightDisplayRow(original, LocalWeightUnit.current)
            val label = stringResource(when (row.item) {
                MeasureItem.WEIGHT -> R.string.health_weight
                MeasureItem.BODY_FAT -> R.string.health_body_fat
                else -> R.string.table_steps
            })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(if (row.state == MeasureState.VALUE && row.value != null) "${row.value} ${row.unit}" else stringResource(when (row.state) {
                        MeasureState.UNPERMITTED -> R.string.table_unpermitted
                        MeasureState.ERROR -> R.string.table_status_unknown
                        else -> R.string.table_no_records
                    }), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                if (row.state == MeasureState.VALUE && row.value != null) Text((if (row.item == MeasureItem.STEPS) measurementDisplayStepDates(row.at, ZoneId.systemDefault())
                    else measurementDisplayTime(row.at, ZoneId.systemDefault())) ?: stringResource(R.string.table_unknown_time),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        if (rows.any { it !in latest }) {
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (expanded) R.string.detail_collapse_data else R.string.detail_expand_data))
            }
            if (expanded) MeasurementTable(rows)
        }
    }
}
