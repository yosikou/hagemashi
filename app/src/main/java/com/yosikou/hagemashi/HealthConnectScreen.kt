package com.yosikou.hagemashi

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun healthTime(value: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
    .withZone(ZoneId.systemDefault()).format(value)

@Composable
internal fun HealthConnectScreen(model: HealthConnectViewModel, lifecycle: Lifecycle, privacy: () -> Unit, preview: (GenerationKind)->Unit, back: () -> Unit) {
    BackHandler(onBack = back)
    val ui = model.ui
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { model.refresh() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
            if (event == Lifecycle.Event.ON_STOP) model.stopRead()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.stopRead() }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.health_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.build_identifier, BuildConfig.BUILD_IDENTIFIER))
        Text(stringResource(R.string.health_notice))
        Text(stringResource(when (ui.provider) {
            HealthProvider.CHECKING -> R.string.health_checking
            HealthProvider.AVAILABLE -> R.string.health_available
            HealthProvider.UPDATE_REQUIRED -> R.string.health_update_required
            HealthProvider.UNAVAILABLE -> R.string.health_unavailable
            HealthProvider.ERROR -> R.string.health_provider_error
        }))
        if (ui.busy) Text(stringResource(R.string.health_loading))
        if (ui.provider == HealthProvider.AVAILABLE) {
            OutlinedButton(onClick = { permissions.launch(healthReadPermissions.values.toSet()) },
                enabled = !ui.busy && ui.snapshot?.results?.any { it.status == HealthReadStatus.PERMISSION_REQUIRED || it.status == HealthReadStatus.ACCESS_DENIED } == true) {
                Text(stringResource(R.string.health_grant))
            }
        }
        OutlinedButton(onClick = model::refresh, enabled = !ui.busy) { Text(stringResource(R.string.health_refresh)) }
        OutlinedButton(onClick = {
            runCatching { context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context)) }
                .onFailure { model.settingsFailed() }
        }) { Text(stringResource(R.string.health_open_settings)) }
        if (ui.provider == HealthProvider.UPDATE_REQUIRED) OutlinedButton(onClick = {
            val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }.isSuccess
            if (!opened) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))) }.onFailure { model.settingsFailed() }
        }) { Text(stringResource(R.string.health_open_store)) }
        if (ui.settingsFailed) Text(stringResource(R.string.health_settings_failed))
        OutlinedButton(onClick={preview(GenerationKind.MORNING)}){Text(stringResource(R.string.preview_morning))}
        OutlinedButton(onClick={preview(GenerationKind.EVENING)}){Text(stringResource(R.string.preview_evening))}
        ui.snapshot?.let { snapshot -> MeasurementTable(measurementRows(snapshot,null,null,ZoneId.systemDefault())) }
        OutlinedButton(onClick = privacy) { Text(stringResource(R.string.health_privacy)) }
        OutlinedButton(onClick = back) { Text(stringResource(R.string.probe_back)) }
    }
}

@Composable
internal fun HealthPrivacyScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.health_privacy), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.health_privacy_body))
        OutlinedButton(onClick = back) { Text(stringResource(R.string.health_privacy_back)) }
    }
}
