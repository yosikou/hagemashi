package com.yosikou.hagemashi

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun Page(title: Int, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        content()
        OutlinedButton(onClick = back) { Text(stringResource(R.string.app_back)) }
    }
}
@Composable
internal fun HomeScreen(app: AppViewModel, connection: ConnectionViewModel, settings: (String) -> Unit,
    generate: () -> Unit, detail: (String) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.home_name), style = MaterialTheme.typography.headlineLarge)
            IconButton(onClick = { settings("settings") }) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title)) }
        }
        app.latest?.let { answer ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(notificationShort(answer.short, stringResource(R.string.notification_fallback)), style = MaterialTheme.typography.titleLarge)
                Text(answer.personaName?.let { stringResource(R.string.generated_persona, it) } ?: stringResource(R.string.persona_not_recorded))
                TextButton(onClick = { detail(answer.id) }) { Text(stringResource(R.string.read_full)) }
            } }
        } ?: Text(stringResource(R.string.no_encouragement))
        val cfg = app.settings
        if (cfg == null) Text(stringResource(R.string.loading))
        if (app.failure) {
            Text(stringResource(R.string.settings_error))
            OutlinedButton(onClick = app::refresh) { Text(stringResource(R.string.detail_retry)) }
        }
        if (cfg != null) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_weight), style = MaterialTheme.typography.titleMedium)
                val weight = app.weight
                if (weight == null) Text(stringResource(R.string.home_weight_missing)) else {
                    Text(stringResource(R.string.health_weight_value, weight.value), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.health_measured_at, DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault()).format(weight.measuredAt)))
                    cfg.goal?.toDoubleOrNull()?.let { target -> Text(stringResource(R.string.goal_difference, weight.value - target)) }
                }
                cfg.goal?.let { Text(stringResource(R.string.manual_goal_format, it)) }
                TextButton(onClick = { settings("health") }) { Text(stringResource(R.string.health_settings)) }
            } }
            if (!cfg.maintaining && cfg.goal != null && app.weight?.let { it.value <= cfg.goal.toDouble() } == true) {
                Text(stringResource(R.string.goal_reached))
                OutlinedButton(onClick = { app.change { it.copy(maintaining = true, goalDate = null) } }, enabled = !app.saving) { Text(stringResource(R.string.goal_maintain)) }
                OutlinedButton(onClick = { settings("goal") }) { Text(stringResource(R.string.goal_review)) }
            }
            if (cfg.maintaining) Text(stringResource(R.string.goal_maintaining))
            val action = homeAction(cfg, connection.ui.permitted,
                (cfg.modelId != null && connection.ui.unavailableModel == cfg.modelId) || (connection.ui.modelsLoaded && connection.ui.models.none { it.slug == cfg.modelId }), LocalDate.now())
            Button(onClick = { when (action) {
                HomeAction.SET_GOAL, HomeAction.RESET_GOAL -> settings("goal")
                HomeAction.CONNECT -> settings("connection")
                HomeAction.SELECT_AGENT -> settings("agent")
                HomeAction.GENERATE -> generate()
            } }, enabled = !app.saving && !connection.ui.busy && !app.failure, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(when (action) {
                    HomeAction.SET_GOAL -> R.string.set_goal
                    HomeAction.RESET_GOAL -> R.string.reset_goal
                    HomeAction.CONNECT -> R.string.connect_chatgpt
                    HomeAction.SELECT_AGENT -> R.string.select_agent
                    HomeAction.GENERATE -> R.string.generate_encouragement
                }))
            }
        }
    }
}
@Composable
internal fun SettingsMenu(open: (String) -> Unit, back: () -> Unit) {
    Page(R.string.settings_title, back) {
        listOf("goal" to R.string.goal_settings, "connection" to R.string.chatgpt_settings, "agent" to R.string.agent_settings,
            "personas" to R.string.persona_settings, "health" to R.string.health_settings, "notice" to R.string.notification_settings_title,
            "probes" to R.string.open_probes).forEach { (route, label) ->
            OutlinedButton(onClick = { open(route) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
        }
    }
}
@Composable
internal fun GoalSettings(app: AppViewModel, cfg: AppSettings, back: () -> Unit) {
    var goal by remember(cfg.goal) { mutableStateOf(cfg.goal.orEmpty()) }
    var date by remember(cfg.goalDate) { mutableStateOf(cfg.goalDate?.toString().orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    Page(R.string.goal_settings, back) {
        OutlinedTextField(goal, { goal = it }, label = { Text(stringResource(R.string.goal_input)) })
        OutlinedTextField(date, { date = it }, label = { Text(stringResource(R.string.goal_date_input)) })
        if (invalid) Text(stringResource(R.string.goal_invalid))
        Button(onClick = {
            val value = canonicalGoal(goal)
            val day = if (date.isBlank()) null else runCatching { LocalDate.parse(date.trim()) }.getOrNull()
            invalid = value == null || (date.isNotBlank() && day == null)
            if (!invalid) app.change { it.copy(goal = value, goalDate = day, maintaining = false) }
        }, enabled = !app.saving) { Text(stringResource(R.string.save_settings)) }
        SettingsStatus(app)
    }
}
@Composable
internal fun AgentSettings(app: AppViewModel, cfg: AppSettings, connection: ConnectionViewModel, back: () -> Unit) {
    Page(R.string.agent_settings, back) {
        Text(stringResource(R.string.saved_agent, cfg.modelId ?: stringResource(R.string.not_selected)))
        OutlinedButton(onClick = connection::loadModels, enabled = connection.ui.permitted && !connection.ui.busy) { Text(stringResource(R.string.fetch_models)) }
        if (!connection.ui.permitted) Text(stringResource(R.string.manual_need_connection))
        if (cfg.modelId != null && (connection.ui.unavailableModel == cfg.modelId || (connection.ui.modelsLoaded && connection.ui.models.none { it.slug == cfg.modelId }))) Text(stringResource(R.string.agent_unavailable))
        if (connection.ui.failure != null) Text(stringResource(failureText(connection.ui.failure!!)))
        connection.ui.models.forEach { model ->
            Row {
                RadioButton(selected = cfg.modelId == model.slug, onClick = { app.change { it.copy(modelId = model.slug) } }, enabled = !app.saving)
                Text(stringResource(R.string.manual_model, model.name, model.slug))
            }
        }
        SettingsStatus(app)
    }
}
@Composable
internal fun PersonaSettings(app: AppViewModel, cfg: AppSettings, back: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember(editing) { mutableStateOf(cfg.personas.find { it.id == editing }?.name.orEmpty()) }
    var prompt by remember(editing) { mutableStateOf(cfg.personas.find { it.id == editing }?.prompt.orEmpty()) }
    var supplement by remember(cfg.supplement) { mutableStateOf(cfg.supplement) }
    Page(R.string.persona_settings, back) {
        cfg.personas.forEach { persona ->
            Row {
                RadioButton(cfg.personaId == persona.id, onClick = { app.change { it.copy(personaId = persona.id) } }, enabled = !app.saving)
                Column {
                    Text(persona.name, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { editing = persona.id }) { Text(stringResource(R.string.edit_persona)) }
                }
            }
        }
        OutlinedButton(onClick = { editing = "new" }) { Text(stringResource(R.string.add_persona)) }
        if (editing != null) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.persona_name_input)) })
            OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.persona_prompt_input)) }, minLines = 3)
            Button(onClick = {
                val id = editing!!
                val persona = Persona(if (id == "new") UUID.randomUUID().toString() else id, name.trim(), prompt.trim())
                app.change { it.copy(personas = if (id == "new") it.personas + persona else it.personas.map { old -> if (old.id == id) persona else old }) }
                editing = null
            }, enabled = !app.saving && name.isNotBlank() && prompt.isNotBlank()) { Text(stringResource(R.string.save_settings)) }
            if (editing in builtInPersonaIds) OutlinedButton(onClick = {
                val original = defaultPersonas(context).single { it.id == editing }
                app.change { it.copy(personas = it.personas.map { old -> if (old.id == original.id) original else old }) }; editing = null
            }, enabled = !app.saving) { Text(stringResource(R.string.reset_persona)) }
            else if (editing != "new") OutlinedButton(onClick = { val id = editing!!; app.change { it.deletePersona(id) }; editing = null }, enabled = !app.saving) { Text(stringResource(R.string.delete_persona)) }
        }
        HorizontalDivider()
        OutlinedTextField(supplement, { supplement = it }, label = { Text(stringResource(R.string.common_prompt)) }, minLines = 2)
        Button(onClick = { app.change { it.copy(supplement = supplement) } }, enabled = !app.saving) { Text(stringResource(R.string.save_supplement)) }
        SettingsStatus(app)
    }
}
@Composable
internal fun NotificationSettings(app: AppViewModel, cfg: AppSettings, back: () -> Unit) {
    val context = LocalContext.current
    Page(R.string.notification_settings_title, back) {
        Row {
            Switch(cfg.notificationEnabled, onCheckedChange = { enabled -> app.change { it.copy(notificationEnabled = enabled) } }, enabled = !app.saving)
            Text(stringResource(R.string.notification_enabled_setting))
        }
        OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> app.change { it.copy(notificationTime = LocalTime.of(h, m)) } },
            cfg.notificationTime.hour, cfg.notificationTime.minute, true).show() }, enabled = !app.saving) {
            Text(stringResource(R.string.notification_time_setting, cfg.notificationTime.toString()))
        }
        Text(stringResource(app.reservationStatus))
        ReservationSettings(app, cfg)
        Text(stringResource(R.string.no_automatic_ai))
        SettingsStatus(app)
    }
}
@Composable internal fun ReservationSettings(app: AppViewModel, cfg: AppSettings) {
    val context = LocalContext.current
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { app.refresh() }
    Text(stringResource(R.string.reservation_target, cfg.testAnswerId ?: stringResource(R.string.not_selected)))
    OutlinedButton(onClick = { app.latest?.let { answer -> app.change { it.copy(testAnswerId = answer.id) } } }, enabled = app.latest != null && !app.saving) {
        Text(stringResource(R.string.reservation_use_latest))
    }
    if (android.os.Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) {
        Text(stringResource(R.string.reservation_grant))
    }
    OutlinedButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) } }) {
        Text(stringResource(R.string.notification_settings))
    }
    Text(stringResource(R.string.reservation_test_only))
}
@Composable private fun SettingsStatus(app: AppViewModel) {
    Text(stringResource(if (app.failure) R.string.settings_error else if (app.saving) R.string.saving else R.string.settings_saved))
}
