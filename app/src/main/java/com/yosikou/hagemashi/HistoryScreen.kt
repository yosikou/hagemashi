package com.yosikou.hagemashi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource

@Composable internal fun HistoryScreen(model: HistoryViewModel, open: (String)->Unit, back:()->Unit) {
    LaunchedEffect(Unit) { model.load(true) }
    var confirm by remember { mutableStateOf(false) }
    Page(R.string.history_title, back) {
        if(model.failed) Text(stringResource(R.string.history_error))
        if(model.answers.isEmpty() && !model.busy) Text(stringResource(R.string.history_empty))
        model.answers.forEach { answer ->
            SectionCard(onClick = if (model.busy) null else ({ open(answer.id) })) {
                AnswerMetadata(answer)
                Text(answer.short, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick={open(answer.id)},enabled=!model.busy) { Text(stringResource(R.string.read_full)) }
                OutlinedButton(onClick={model.remove(answer.id)},enabled=!model.busy) { Text(stringResource(R.string.history_delete)) }
            }
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
