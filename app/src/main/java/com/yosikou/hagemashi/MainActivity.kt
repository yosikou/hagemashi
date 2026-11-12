package com.yosikou.hagemashi

import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var history: HistoryViewModel
    private lateinit var modePreview: ModePreviewViewModel
    private lateinit var app: AppViewModel
    private lateinit var model: ConnectionViewModel
    private lateinit var probes: NetworkProbeViewModel
    private lateinit var details: AnswerDetailViewModel
    private var goalReview by mutableStateOf(false)
    private var detailId by mutableStateOf<String?>(null)
    private lateinit var manual: ManualGenerationViewModel
    private lateinit var health: HealthConnectViewModel
    private var healthPrivacy by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReservationDiagnostics.record(this,ReservationEvent.APP_OPEN)
        history = ViewModelProvider(this)[HistoryViewModel::class.java]
        modePreview = ViewModelProvider(this)[ModePreviewViewModel::class.java]
        app = ViewModelProvider(this)[AppViewModel::class.java]
        model = ViewModelProvider(this)[ConnectionViewModel::class.java]
        probes = ViewModelProvider(this)[NetworkProbeViewModel::class.java]
        health = ViewModelProvider(this)[HealthConnectViewModel::class.java]
        manual = ViewModelProvider(this)[ManualGenerationViewModel::class.java]
        details = ViewModelProvider(this)[AnswerDetailViewModel::class.java]
        detailId = answerId(intent) ?: savedInstanceState?.getString("detailId")?.takeIf(::isAnswerId)
        goalReview = isGoalReviewNavigation(intent?.action,intent?.dataString,packageName)
        if(goalReview) detailId=null
        healthPrivacy = isHealthPrivacyIntent(intent)
        if (healthPrivacy) detailId = null
        setContent {
            var route by rememberSaveable { mutableStateOf("home") }
            LaunchedEffect(goalReview) {
                if(goalReview) { route="goal";detailId=null;goalReview=false;setIntent(Intent(this@MainActivity,MainActivity::class.java)) }
            }
            val url = model.ui.browserUrl
            LaunchedEffect(url) {
                if (url != null) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                        model.browserOpened()
                    } catch (_: Exception) { model.browserFailed() }
                }
            }
            HagemashiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val selectedDetail = detailId
                    if (selectedDetail != null) AnswerDetailScreen(selectedDetail, details) { closeDetail() }
                    else if (healthPrivacy) HealthPrivacyScreen { healthPrivacy = false }
                    else when (route) {
                        "home" -> HomeScreen(app, model, { route = it }, { route = "manual" }, { detailId = it })
                        "settings" -> SettingsMenu({ route = it }) { route = "home" }
                        "connection" -> ConnectionScreen(model, { route = "settings" }) {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }
                        }
                        "health" -> HealthConnectScreen(health, lifecycle, privacy = { healthPrivacy = true }, preview={route=if(it==GenerationKind.MORNING) "morning-preview" else "evening-preview"}) { route = "settings" }
                        "history" -> HistoryScreen(history,{detailId=it}){route="home";app.refresh()}
                        "morning-preview", "evening-preview" -> ModePreviewScreen(if(route=="morning-preview") GenerationKind.MORNING else GenerationKind.EVENING, modePreview, app.settings){route="health"}
                        "reservation-diagnostics" -> ReservationDiagnosticScreen { route = "settings" }
                        "probes" -> ProbeScreen(probes) { route = "settings" }
                        "manual" -> ManualGenerationScreen(manual, model, app.settings, lifecycle, openDetail = { detailId = it }) { route = "home"; app.refresh() }
                        else -> app.settings?.let { cfg -> when (route) {
                            "goal" -> GoalSettings(app, cfg) { route = "settings" }
                            "agent" -> AgentSettings(app, cfg, model) { route = "settings" }
                            "personas" -> PersonaSettings(app, cfg) { route = "settings" }
                            "notice" -> NotificationSettings(app, cfg) { route = "settings" }
                        } } ?: Page(R.string.settings_title, { route = "settings" }) {
                            Text(stringResource(R.string.settings_error))
                            OutlinedButton(onClick = app::refresh) { Text(stringResource(R.string.detail_retry)) }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); ReservationDiagnostics.record(this,ReservationEvent.APP_VISIBLE); model.setForeground(true); probes.setForeground(true) }
    override fun onStop() { ReservationDiagnostics.record(this,ReservationEvent.APP_HIDDEN); model.setForeground(false); probes.setForeground(false); super.onStop() }
    override fun onResume() { super.onResume(); model.setResumed(true); app.refresh() }
    override fun onPause() { model.setResumed(false); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        answerId(intent)?.let { detailId = it }
        if(isGoalReviewNavigation(intent.action,intent.dataString,packageName)){detailId=null;goalReview=true}
        if (isHealthPrivacyIntent(intent)) { detailId = null; healthPrivacy = true }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        detailId?.let { outState.putString("detailId", it) }
        super.onSaveInstanceState(outState)
    }
    private fun closeDetail() { detailId = null; setIntent(Intent(this, MainActivity::class.java)) }
    private fun answerId(intent: Intent?): String? = answerIdFromNavigation(intent?.action, intent?.dataString, packageName)

    private fun isHealthPrivacyIntent(intent: Intent?): Boolean = intent?.action in setOf(
        "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", "android.intent.action.VIEW_PERMISSION_USAGE")
}

@Composable
private fun ConnectionScreen(model: ConnectionViewModel, back: () -> Unit, openUsage: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = back)
    val ui = model.ui
    val context = LocalContext.current
    var confirmErase by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.chatgpt_settings), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.build_identifier, BuildConfig.BUILD_IDENTIFIER))
        Text(stringResource(R.string.preview_notice))
        Text(stringResource(if (ui.savedConnection == SavedConnection.UNKNOWN) R.string.connection_unknown
            else if (ui.connected) R.string.connected else R.string.disconnected), style = MaterialTheme.typography.titleMedium)
        if (ui.name.isNotEmpty()) Text(stringResource(R.string.account_name, ui.name))
        if (ui.email.isNotEmpty()) Text(stringResource(R.string.account_email, ui.email))
        if (ui.connected) Text(stringResource(if (ui.permitted) R.string.using_plan else R.string.no_permission))
        if (!ui.connected && ui.accounts.isNotEmpty()) {
            Text(stringResource(R.string.saved_accounts))
            ui.accounts.forEach { account ->
                OutlinedButton(onClick = { model.selectAccount(account.id) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (ui.selectedAccount == account.id) R.string.selected_option else R.string.option, account.label))
                }
            }
            OutlinedButton(onClick = { model.selectAccount(null) }, enabled = !ui.busy) { Text(stringResource(R.string.new_account)) }
        }
        Button(onClick = { model.signIn() }, enabled = !ui.busy && !ui.connected && ui.failure != Failure.STORAGE) { Text(stringResource(R.string.continue_chatgpt)) }
        OutlinedButton(onClick = { model.signIn(compareDns = true) }, enabled = !ui.busy && !ui.connected && ui.failure != Failure.STORAGE) {
            Text(stringResource(R.string.dns_compare_sign_in))
        }
        OutlinedButton(onClick = model::disconnect, enabled = ui.connected) { Text(stringResource(R.string.disconnect)) }
        if (ui.busy) OutlinedButton(onClick = { model.cancel() }) { Text(stringResource(R.string.cancel)) }
        Text(stringResource(ui.status))
        if (ui.progress.stage == Stage.WAIT_APP_RETURN && ui.progress.phase == Phase.RUNNING) Text(stringResource(R.string.wait_app_return))
        ui.failure?.let { Text(stringResource(failureText(it)), color = MaterialTheme.colorScheme.error) }
        if (ui.progress.stage != Stage.NONE) {
            Text(stringResource(R.string.diagnostic_stage, stringResource(stageText(ui.progress.stage)), stringResource(phaseText(ui.progress.phase))))
            Text(stringResource(R.string.diagnostic_completed, stringResource(stageText(ui.progress.completed))))
        }
        ui.diagnostic?.let { diagnostic ->
            Text(stringResource(R.string.diagnostic_host, diagnostic.host?.hostname ?: stringResource(R.string.host_unconfirmed)))
            Text(stringResource(R.string.diagnostic_error_class, diagnostic.errorClass.name))
            diagnostic.network?.let { Text(stringResource(R.string.diagnostic_network, stringResource(networkText(it)))) }
            if (diagnostic.httpStatus != null) Text(stringResource(R.string.diagnostic_http, diagnostic.httpStatus))
            else if (diagnostic.network != null) Text(stringResource(R.string.diagnostic_no_http))
            Text(stringResource(R.string.diagnostic_exceptions, exceptionNames(diagnostic.exceptionClasses)))
        }
        if (ui.dnsObservations.isNotEmpty()) {
            Text(stringResource(R.string.dns_comparison_notice))
            ui.dnsObservations.forEach { sample ->
                Text(stringResource(R.string.dns_observation, stringResource(dnsPointText(sample.point)),
                    stringResource(visibilityText(sample.visibility)),
                    if (sample.resolved) stringResource(R.string.dns_resolved) else sample.diagnostic?.errorClass?.name.orEmpty()))
                Text(stringResource(R.string.diagnostic_exceptions, exceptionNames(sample.diagnostic?.exceptionClasses.orEmpty())))
            }
        }
        if (ui.failure != null) Text(stringResource(savedText(ui.savedConnection)))
        if (ui.lastWhileAway != Stage.NONE) Text(stringResource(R.string.diagnostic_away, stringResource(stageText(ui.lastWhileAway))))
        ui.networkFacts?.let {
            Text(stringResource(if (it.internetGranted) R.string.internet_granted else R.string.internet_missing))
            Text(stringResource(if (it.cleartextPermitted) R.string.cleartext_allowed else R.string.https_only))
        } ?: Text(stringResource(R.string.network_settings_unknown))
        TextButton(onClick = openUsage) { Text(stringResource(R.string.manage_usage)) }
        if (ui.failure == Failure.STORAGE) OutlinedButton(onClick = { confirmErase = true }, enabled = !ui.busy) { Text(stringResource(R.string.erase_storage)) }
        OutlinedButton(onClick = back) { Text(stringResource(R.string.app_back)) }

    }
    if (ui.welcome && !ui.busy) AlertDialog(onDismissRequest = model::dismissWelcome,
        title = { Text(stringResource(R.string.plan_welcome_title)) }, text = { Text(stringResource(R.string.plan_welcome)) },
        confirmButton = { TextButton(onClick = model::dismissWelcome) { Text(stringResource(R.string.understood)) } })
    if (confirmErase) AlertDialog(onDismissRequest = { confirmErase = false },
        title = { Text(stringResource(R.string.erase_storage)) }, text = { Text(stringResource(R.string.erase_warning)) },
        confirmButton = { TextButton(onClick = { confirmErase = false; model.eraseUnrecoverable() }) { Text(stringResource(R.string.erase)) } },
        dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ProbeScreen(model: NetworkProbeViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val report = model.report()
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.probe_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.probe_notice))
        Text(stringResource(R.string.build_identifier, BuildConfig.BUILD_IDENTIFIER))
        OutlinedButton(onClick = { model.runProbe(ProbeKind.DNS) }, enabled = !model.busy) { Text(stringResource(R.string.probe_dns)) }
        OutlinedButton(onClick = { model.runProbe(ProbeKind.HTTPS_GET) }, enabled = !model.busy) { Text(stringResource(R.string.probe_get)) }
        OutlinedButton(onClick = { model.runProbe(ProbeKind.EMPTY_POST) }, enabled = !model.busy) { Text(stringResource(R.string.probe_post)) }
        if (model.busy) OutlinedButton(onClick = model::cancel) { Text(stringResource(R.string.cancel)) }
        Text(stringResource(R.string.diagnostic_stage, stringResource(stageText(model.progress.stage)), stringResource(phaseText(model.progress.phase))))
        SelectionContainer { Text(report) }
        OutlinedButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.probe_copy), report))
        }, enabled = !model.busy && model.results.isNotEmpty()) { Text(stringResource(R.string.probe_copy)) }
        OutlinedButton(onClick = back, enabled = !model.busy) { Text(stringResource(R.string.probe_back)) }
    }
}

@Composable
private fun exceptionNames(classes: List<SafeException>): String = if (classes.isEmpty()) stringResource(R.string.no_exception_classes)
    else classes.joinToString(" → ") { it.displayName }

private fun dnsPointText(point: DnsPoint): Int = when (point) {
    DnsPoint.BEFORE_BROWSER -> R.string.dns_before_browser
    DnsPoint.AFTER_CALLBACK -> R.string.dns_after_callback
    DnsPoint.AFTER_RESUME -> R.string.dns_after_resume
}

private fun visibilityText(visibility: AppVisibility): Int = when (visibility) {
    AppVisibility.VISIBLE -> R.string.app_visible
    AppVisibility.HIDDEN -> R.string.app_hidden
    AppVisibility.CHANGED -> R.string.app_visibility_changed
}

internal fun failureText(failure: Failure): Int = when (failure) {
    Failure.NETWORK -> R.string.error_network
    Failure.PROTOCOL -> R.string.error_protocol
    Failure.IDENTITY -> R.string.error_identity
    Failure.STORAGE -> R.string.error_storage
    Failure.DENIED -> R.string.error_denied
    Failure.EXPIRED -> R.string.error_expired
    Failure.PERMISSION -> R.string.error_permission
    Failure.LIMIT -> R.string.error_limit
    Failure.POLICY -> R.string.error_policy
    Failure.SERVER -> R.string.error_server
    Failure.TIMEOUT -> R.string.error_timeout
}

private fun savedText(state: SavedConnection): Int = when (state) {
    SavedConnection.NONE -> R.string.saved_none
    SavedConnection.PRESENT -> R.string.saved_present
    SavedConnection.UNKNOWN -> R.string.saved_unknown
}

private fun phaseText(phase: Phase): Int = when (phase) {
    Phase.IDLE -> R.string.phase_idle
    Phase.RUNNING -> R.string.phase_running
    Phase.SUCCEEDED -> R.string.phase_succeeded
    Phase.FAILED -> R.string.phase_failed
    Phase.CANCELLED -> R.string.phase_cancelled
}

private fun networkText(network: NetworkFailure): Int = when (network) {
    NetworkFailure.HTTP -> R.string.network_http
    NetworkFailure.DNS -> R.string.network_dns
    NetworkFailure.TLS -> R.string.network_tls
    NetworkFailure.TIMEOUT -> R.string.network_timeout
    NetworkFailure.CONNECTION -> R.string.network_connection
    NetworkFailure.IO -> R.string.network_io
    NetworkFailure.PERMISSION -> R.string.network_permission
}

private fun stageText(stage: Stage): Int = when (stage) {
    Stage.NONE -> R.string.stage_none
    Stage.READ_SAVED_STATE -> R.string.stage_read_saved
    Stage.PREPARE_LOGIN -> R.string.stage_prepare
    Stage.WAIT_CALLBACK -> R.string.stage_callback
    Stage.WAIT_APP_RETURN -> R.string.wait_app_return
    Stage.SAVE_REGISTRATION -> R.string.stage_registration
    Stage.CODE_EXCHANGE -> R.string.stage_exchange
    Stage.DISCOVERY -> R.string.stage_discovery
    Stage.SIGNING_KEYS -> R.string.stage_keys
    Stage.ID_TOKEN -> R.string.stage_identity
    Stage.TOKEN_FIELDS -> R.string.stage_token_fields
    Stage.SAVE_CREDENTIALS -> R.string.stage_save
    Stage.MODELS -> R.string.stage_models
    Stage.GENERATION -> R.string.stage_generation
    Stage.REFRESH -> R.string.stage_refresh
    Stage.REVOCATION -> R.string.stage_revocation
    Stage.PROBE_DNS -> R.string.probe_dns
    Stage.PROBE_GET -> R.string.probe_get
    Stage.PROBE_POST -> R.string.probe_post
}
