package com.yosikou.hagemashi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AnswerDetailScreen(id: String, model: AnswerDetailViewModel, back: () -> Unit) {
    BackHandler(onBack = back)
    LaunchedEffect(id) { model.load(id) }
    DisposableEffect(Unit) { onDispose { model.close() } }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::permissionResult)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.detail_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.build_identifier, BuildConfig.BUILD_IDENTIFIER))
        if (model.loading) Text(stringResource(R.string.loading))
        if(model.deleted) Text(stringResource(R.string.history_deleted))
        if (model.loadFailed) {
            Text(stringResource(R.string.detail_load_failed))
            OutlinedButton(onClick = { model.load(id) }) { Text(stringResource(R.string.detail_retry)) }
        }
        model.answer?.takeIf { it.id == id }?.let { answer ->
            Text(stringResource(R.string.detail_generated_at, DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault()).format(answer.generatedAt)))
            Text(answer.personaName?.let { stringResource(R.string.generated_persona, it) } ?: stringResource(R.string.persona_not_recorded))
            Text(stringResource(R.string.answer_title), style = MaterialTheme.typography.titleMedium)
            Text(answer.full)
            Text(stringResource(R.string.detail_used_data), style = MaterialTheme.typography.titleMedium)
            if (!answer.hasHealthData) Text(stringResource(R.string.detail_health_missing))
            Text(generationKindLabel(answer.kind))
            MeasurementTable(answer.rows.ifEmpty { legacyMeasurementRows(answer.usedData) })
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
        OutlinedButton(onClick = back) { Text(stringResource(R.string.detail_back)) }
    }
}
