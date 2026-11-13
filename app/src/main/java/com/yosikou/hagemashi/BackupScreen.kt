package com.yosikou.hagemashi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable internal fun BackupScreen(model:BackupViewModel,app:AppViewModel,connection:ConnectionViewModel,open:(String)->Unit,back:()->Unit) {
    val context=LocalContext.current
    var includeHistory by remember{mutableStateOf(false)}
    var password by remember{mutableStateOf("")}
    var confirm by remember{mutableStateOf("")}
    var checked by remember{mutableStateOf(false)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"),model::write)
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(),model::read)
    LaunchedEffect(model.needsDestination){if(model.needsDestination){model.destinationLaunched();runCatching{export.launch(context.getString(R.string.backup_filename))}.onFailure{model.pickerFailed(true)}}}
    val locked=model.busy || app.saving
    Page(R.string.backup_title,{if(!model.applying && !app.saving){model.reset();back()}}) {
        Text(stringResource(R.string.backup_scope))
        if(model.busy)Text(stringResource(R.string.backup_busy))
        if(model.busy && !model.applying)OutlinedButton(onClick=model::cancel){Text(stringResource(R.string.cancel))}
        model.failure?.let{Text(stringResource(backupErrorText(it)),color=MaterialTheme.colorScheme.error)}
        if(model.status==BackupStatus.EXPORTED)Text(stringResource(R.string.backup_exported))
        if(model.status==BackupStatus.RESTORED && app.settings?.resumeRequired!=false)Text(stringResource(R.string.backup_restored))
        if(model.status==BackupStatus.CANCELLED)Text(stringResource(R.string.backup_cancelled))
        if(app.settings?.resumeRequired==true)SectionCard {
            Text(stringResource(R.string.backup_resume_title),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.backup_setup_guide))
            val cfg=app.settings!!
            Text(stringResource(R.string.backup_resume_mode,stringResource(if(cfg.resumeAutomatic)R.string.backup_on else R.string.backup_off)))
            Text(stringResource(R.string.backup_slot_summary,generationKindLabel(GenerationKind.MORNING),cfg.morning.time.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(LocalAppLocale.current)),stringResource(if(cfg.resumeMorning)R.string.backup_on else R.string.backup_off)))
            Text(stringResource(R.string.backup_slot_summary,generationKindLabel(GenerationKind.EVENING),cfg.evening.time.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(LocalAppLocale.current)),stringResource(if(cfg.resumeEvening)R.string.backup_on else R.string.backup_off)))
            listOf("connection" to R.string.chatgpt_settings,"health" to R.string.health_settings,"notice" to R.string.notification_settings_title,"goal" to R.string.goal_settings,"agent" to R.string.agent_settings).forEach{(route,label)->OutlinedButton(onClick={open(route)},enabled=!locked){Text(stringResource(label))}}
            Row{Checkbox(checked,onCheckedChange={checked=it},enabled=!locked);Text(stringResource(R.string.backup_resume_checked))}
            val anySlot=cfg.resumeMorning || cfg.resumeEvening
            val ready=(!anySlot || !cfg.resumeAutomatic || (connection.ui.permitted && cfg.goal!=null && cfg.modelId!=null && (!connection.ui.modelsLoaded || connection.ui.models.any{it.slug==cfg.modelId}))) && (!anySlot || AnswerNotifications(context).allowed())
            Button(onClick={app.change{resumeRestoredSettings(it)}},enabled=checked && ready && !locked){Text(stringResource(R.string.backup_resume))}
            if(!ready)Text(stringResource(R.string.backup_resume_needs_setup))
        }
        model.preview?.let{BackupContentsPreview(it,locked,{model.restore(app)},model::cancel)}
        if(diagnosticUi() && model.preview!=null)SectionCard {
            Text(stringResource(R.string.backup_fault_title))
            listOf(RestoreFault.AFTER_SETTINGS to R.string.backup_fault_settings,RestoreFault.AFTER_HISTORY to R.string.backup_fault_history,RestoreFault.BEFORE_COMMIT to R.string.backup_fault_commit).forEach{(fault,label)->
                OutlinedButton(onClick={model.restore(app,fault)},enabled=!locked){Text(stringResource(label))}
            }
        }
        if(model.preview==null) {
            if(!model.fileSelected)SectionCard {
                Text(stringResource(R.string.backup_create),style=MaterialTheme.typography.titleMedium)
                Row{Checkbox(includeHistory,onCheckedChange={includeHistory=it},enabled=!locked);Text(stringResource(R.string.backup_include_history))}
                OutlinedTextField(password,{password=it},label={Text(stringResource(R.string.backup_passphrase))},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!locked)
                OutlinedTextField(confirm,{confirm=it},label={Text(stringResource(R.string.backup_passphrase_confirm))},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!locked)
                Text(stringResource(R.string.backup_passphrase_help))
                Button(onClick={val chars=password.toCharArray();if(password!=confirm || !BackupCrypto.validPassphrase(chars)){chars.fill('\u0000');model.rejectPassphrase()}else{password="";confirm="";model.create(includeHistory,chars)}},enabled=!locked){Text(stringResource(R.string.backup_create))}
            }
            SectionCard {
                Text(stringResource(R.string.backup_restore),style=MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick={password="";confirm="";runCatching{import.launch(arrayOf("*/*"))}.onFailure{model.pickerFailed(false)}},enabled=!locked){Text(stringResource(R.string.backup_choose))}
                if(model.fileSelected) {
                    OutlinedTextField(password,{password=it},label={Text(stringResource(R.string.backup_passphrase))},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!locked)
                    Button(onClick={val chars=password.toCharArray();password="";confirm="";model.decrypt(chars)},enabled=!locked){Text(stringResource(R.string.backup_decrypt))}
                    OutlinedButton(onClick=model::cancel,enabled=!locked){Text(stringResource(R.string.cancel))}
                }
            }
        }
    }
}
internal fun backupErrorText(error:BackupError)=when(error) {
    BackupError.PASSPHRASE->R.string.backup_error_passphrase;BackupError.AUTH_OR_DAMAGE->R.string.backup_error_auth
    BackupError.VERSION->R.string.backup_error_version;BackupError.FORMAT->R.string.backup_error_format;BackupError.TOO_LARGE->R.string.backup_error_large
    BackupError.READ->R.string.backup_error_read;BackupError.WRITE->R.string.backup_error_write;BackupError.SAVE->R.string.backup_error_save
}

@Composable internal fun BackupContentsPreview(contents:BackupContents,busy:Boolean,apply:()->Unit,cancel:()->Unit) {
    SectionCard {
            Text(stringResource(R.string.backup_preview),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.backup_replace_notice))
            val cfg=contents.settings
            Text(stringResource(R.string.backup_resume_mode,stringResource(if(cfg.automaticGeneration)R.string.backup_on else R.string.backup_off)))
            Text(stringResource(R.string.backup_history_count,contents.answers.size))
            Text(stringResource(R.string.backup_persona_count,cfg.personas.size))
            Text(stringResource(R.string.goal_value_unit,cfg.goal?.let{localizedNumber(cfg.weightUnit.display(it),LocalAppLocale.current,2)} ?: stringResource(R.string.review_empty),cfg.weightUnit.symbol))
            Text(stringResource(R.string.goal_date_format,cfg.goalDate?.let{localizedDay(it,LocalAppLocale.current)} ?: stringResource(R.string.goal_no_deadline)))
            Text(stringResource(R.string.backup_language,stringResource(languageLabelResource(cfg.language))))
            Text(stringResource(R.string.saved_agent,cfg.modelId ?: stringResource(R.string.not_selected)))
            Text(stringResource(R.string.review_persona,cfg.persona().name))
            Text(stringResource(R.string.backup_slot_summary,generationKindLabel(GenerationKind.MORNING),cfg.morning.time.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(LocalAppLocale.current)),stringResource(if(cfg.morning.enabled)R.string.backup_on else R.string.backup_off)))
            Text(stringResource(R.string.backup_slot_summary,generationKindLabel(GenerationKind.EVENING),cfg.evening.time.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(LocalAppLocale.current)),stringResource(if(cfg.evening.enabled)R.string.backup_on else R.string.backup_off)))
            Text(stringResource(R.string.backup_shared_included))
            Button(onClick={apply()},enabled=!busy){Text(stringResource(R.string.backup_apply))}
            OutlinedButton(onClick=cancel,enabled=!busy){Text(stringResource(R.string.cancel))}
            }
}
