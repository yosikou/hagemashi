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
internal fun Page(title: Int, back: () -> Unit, titleText:String?=null, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(titleText ?: stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = back) { Text(stringResource(R.string.app_back)) }
        }
        content()
    }
}
@Composable
internal fun HomeScreen(app: AppViewModel, connection: ConnectionViewModel, settings: (String) -> Unit,
    generate: () -> Unit, detail: (String) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.home_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { settings("history") }) { Text(stringResource(R.string.history_title)) }
            IconButton(onClick = { settings("settings") }) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title)) }
        }
        SectionCard {
            Text(stringResource(R.string.home_latest), style = MaterialTheme.typography.titleMedium)
            app.latest?.let { answer ->
                Text(notificationShort(answer.short, stringResource(R.string.notification_fallback)), style = MaterialTheme.typography.titleLarge)
                AnswerMetadata(answer)
                TextButton(onClick = { detail(answer.id) }) { Text(stringResource(R.string.read_full)) }
            } ?: Text(stringResource(R.string.no_encouragement))
        }
        val cfg = app.settings
        if (cfg == null) Text(stringResource(R.string.loading))
        if (app.failure) {
            Text(stringResource(R.string.settings_error))
            OutlinedButton(onClick = app::refresh) { Text(stringResource(R.string.detail_retry)) }
        }
        if (cfg != null) {
            val action = homeAction(cfg, connection.ui.permitted,
                (cfg.modelId != null && connection.ui.unavailableModel == cfg.modelId) || (connection.ui.modelsLoaded && connection.ui.models.none { it.slug == cfg.modelId }), LocalDate.now())
            Button(onClick = { when (action) {
                HomeAction.SET_GOAL, HomeAction.RESET_GOAL -> settings("goal")
                HomeAction.CONNECT -> settings("connection")
                HomeAction.SELECT_AGENT -> settings("agent")
                HomeAction.GENERATE -> generate()
            } }, enabled = !app.saving && !connection.ui.busy && !app.failure, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(when (action) {
                    HomeAction.SET_GOAL -> R.string.set_goal
                    HomeAction.RESET_GOAL -> R.string.reset_goal
                    HomeAction.CONNECT -> R.string.connect_chatgpt
                    HomeAction.SELECT_AGENT -> R.string.select_agent
                    HomeAction.GENERATE -> R.string.generate_encouragement
                }))
            }
            SectionCard {
                Text(stringResource(R.string.home_weight), style = MaterialTheme.typography.titleMedium)
                val weight = app.weight
                if (weight == null) Text(stringResource(R.string.home_weight_missing)) else {
                    Text(stringResource(R.string.health_weight_value, weight.value), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.health_measured_at, measurementDisplayTime(weight.measuredAt.toString(), ZoneId.systemDefault()).orEmpty()), style = MaterialTheme.typography.bodyMedium)
                    cfg.goal?.toDoubleOrNull()?.let { target -> Text(stringResource(R.string.goal_difference, weight.value - target)) }
                }
                cfg.goal?.let { Text(stringResource(R.string.manual_goal_format, it)) }
                TextButton(onClick = { settings("health") }) { Text(stringResource(R.string.health_settings)) }
            }
            if (!cfg.maintaining && cfg.goal != null && app.weight?.let { it.value <= cfg.goal.toDouble() } == true) {
                SectionCard {
                    Text(stringResource(R.string.goal_reached))
                    OutlinedButton(onClick = { app.change { it.copy(maintaining = true, goalDate = null) } }, enabled = !app.saving) { Text(stringResource(R.string.goal_maintain)) }
                    OutlinedButton(onClick = { settings("goal") }) { Text(stringResource(R.string.goal_review)) }
                }
            }
            if (cfg.maintaining) Text(stringResource(R.string.goal_maintaining))
            if(goalNeedsReset(cfg,LocalDate.now()) && connection.ui.permitted && cfg.modelId!=null) {
                Text(stringResource(R.string.goal_wait_manual_notice))
                OutlinedButton(onClick=generate,enabled=!app.saving && !connection.ui.busy){Text(stringResource(R.string.manual_while_waiting))}
            }
        }
    }
}
@Composable
internal fun SettingsMenu(open: (String) -> Unit, back: () -> Unit) {
    Page(R.string.settings_title, back) {
        listOf(
            R.string.settings_encouragement_group to listOf("goal" to R.string.goal_settings, "agent" to R.string.agent_settings, "personas" to R.string.persona_settings),
            R.string.settings_connections_group to listOf("connection" to R.string.chatgpt_settings, "health" to R.string.health_settings),
            R.string.notification_settings_title to listOf("notice" to R.string.notification_settings_title),
            R.string.settings_diagnostics_group to listOf("probes" to R.string.open_probes, "reservation-diagnostics" to R.string.reservation_diagnostics)
        ).forEach { (heading, entries) ->
            SectionCard {
                Text(stringResource(heading), style = MaterialTheme.typography.titleMedium)
                entries.forEach { (route, label) ->
                    OutlinedButton(onClick = { open(route) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
                }
            }
        }
    }
}
@Composable
internal fun GoalSettings(app: AppViewModel, cfg: AppSettings, back: () -> Unit) {
    var goal by remember(cfg.goal) { mutableStateOf(cfg.goal.orEmpty()) }
    var date by remember(cfg.goalDate) { mutableStateOf(cfg.goalDate) }
    var invalid by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Page(R.string.goal_settings, back) {
        SectionCard {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(goal, { goal = it }, label = { Text(stringResource(R.string.goal_input)) }, modifier=Modifier.weight(1f),
                    singleLine=true, isError=invalid, keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))
                Text(stringResource(R.string.manual_kg))
            }
            Text(date?.toString() ?: stringResource(R.string.goal_no_deadline))
            OutlinedButton(onClick = {
                val selected=date ?: LocalDate.now()
                android.app.DatePickerDialog(context, { _, y, m, d -> date=LocalDate.of(y,m+1,d) }, selected.year,selected.monthValue-1,selected.dayOfMonth).show()
            }) { Text(stringResource(R.string.goal_choose_deadline)) }
            if(date!=null) TextButton(onClick={date=null}){Text(stringResource(R.string.goal_clear_deadline))}
            if (invalid) Text(stringResource(R.string.goal_invalid))
            Button(onClick = {
                val value=canonicalGoal(goal); invalid=value==null
                if(value!=null) app.change { it.copy(goal=value, goalDate=date, maintaining=false) }
            }, enabled = !app.saving) { Text(stringResource(R.string.save_settings)) }
            SettingsStatus(app)
        }
    }
}
@Composable
internal fun AgentSettings(app: AppViewModel, cfg: AppSettings, connection: ConnectionViewModel, back: () -> Unit) {
    Page(R.string.agent_settings, back) {
        SectionCard {
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
}
@Composable
internal fun PersonaSettings(app: AppViewModel, cfg: AppSettings, compare:()->Unit={}, back: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember(editing) { mutableStateOf(cfg.personas.find { it.id == editing }?.name.orEmpty()) }
    var prompt by remember(editing) { mutableStateOf(cfg.personas.find { it.id == editing }?.prompt.orEmpty()) }
    var supplement by remember(cfg.supplement) { mutableStateOf(cfg.supplement) }
    Page(R.string.persona_settings, back) {
        cfg.personas.forEach { persona ->
            SectionCard {
                Row {
                    RadioButton(cfg.personaId == persona.id, onClick = { app.change { it.copy(personaId = persona.id) } }, enabled = !app.saving)
                    Column {
                        Text(persona.name, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { editing = persona.id }) { Text(stringResource(R.string.edit_persona)) }
                    }
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
        if(BuildConfig.BUILD_TYPE=="diagnostic")OutlinedButton(onClick=compare){Text(stringResource(R.string.persona_comparison_title))}
        SettingsStatus(app)
    }
}
@Composable
internal fun NotificationSettings(app: AppViewModel, cfg: AppSettings, back: () -> Unit) {
    val context = LocalContext.current
    Page(R.string.notification_settings_title, back) {
        SectionCard {
            Text(stringResource(R.string.automatic_title),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.automatic_notice))
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Switch(cfg.automaticGeneration,onCheckedChange={enabled->app.change{it.copy(automaticGeneration=enabled)}},enabled=!app.saving)
                Text(stringResource(R.string.automatic_enabled))
            }
            BackgroundHealthPermissionControl()
        }
        var invalid by remember { mutableStateOf(false) }
        NoticeSlot.entries.forEach { slot ->
            SectionCard {
                val value=cfg.slot(slot)
                Text(stringResource(if(slot==NoticeSlot.MORNING) R.string.kind_morning else R.string.kind_evening),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(app.slotStatuses[slot] ?: app.reservationStatus))
                fun update(changed:SlotSetting) {
                    val candidate=cfg.withSlot(slot,changed); invalid=!candidate.validTimes()
                    if(!invalid) app.change { it.withSlot(slot,changed) }
                }
                Row {
                    Switch(value.enabled,onCheckedChange={update(value.copy(enabled=it))},enabled=!app.saving)
                    Text(stringResource(R.string.notification_enabled_setting))
                }
                OutlinedButton(onClick={TimePickerDialog(context,{_,h,m->update(value.copy(time=LocalTime.of(h,m)))},value.time.hour,value.time.minute,true).show()},enabled=!app.saving){
                    Text(stringResource(R.string.notification_time_setting,value.time.toString()))
                }
            }
        }
        if(invalid) Text(stringResource(R.string.slot_time_invalid))
        Text(stringResource(app.reservationStatus))
        if(!cfg.automaticGeneration)ReservationSettings(app, cfg)
        Text(stringResource(if(cfg.automaticGeneration)R.string.automatic_active else R.string.no_automatic_ai))
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
