package com.yosikou.hagemashi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun HistoryScreen(model: HistoryViewModel, open: (String)->Unit, back:()->Unit) {
    LaunchedEffect(Unit) { model.load(true) }
    var confirm by remember { mutableStateOf(false) }
    Page(R.string.history_title, back) {
        if(model.failed) Text(stringResource(R.string.history_error))
        if(model.answers.isEmpty() && !model.busy) Text(stringResource(R.string.history_empty))
        model.answers.forEach { answer ->
            Text(DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault()).format(answer.generatedAt))
            Text(generationKindLabel(answer.kind))
            Text(answer.personaName?.let { stringResource(R.string.generated_persona,it) } ?: stringResource(R.string.persona_not_recorded))
            Text(answer.short)
            TextButton(onClick={open(answer.id)}) { Text(stringResource(R.string.read_full)) }
            OutlinedButton(onClick={model.remove(answer.id)},enabled=!model.busy) { Text(stringResource(R.string.history_delete)) }
            HorizontalDivider()
        }
        if(model.busy) Text(stringResource(R.string.loading))
        if(model.hasMore || model.failed) OutlinedButton(onClick={model.load()},enabled=!model.busy) { Text(stringResource(R.string.history_more)) }
        OutlinedButton(onClick={confirm=true},enabled=!model.busy) { Text(stringResource(R.string.history_delete_all)) }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false}, title={Text(stringResource(R.string.history_delete_all))},
        text={Text(stringResource(R.string.history_delete_confirm))},
        confirmButton={TextButton(onClick={confirm=false;model.remove()}){Text(stringResource(R.string.history_delete_all))}},
        dismissButton={TextButton(onClick={confirm=false}){Text(stringResource(R.string.cancel))}})
}
