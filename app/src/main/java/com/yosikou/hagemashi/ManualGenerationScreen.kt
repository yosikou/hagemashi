package com.yosikou.hagemashi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
internal fun ManualGenerationScreen(model: ManualGenerationViewModel, connection: ConnectionViewModel,
    lifecycle: Lifecycle, openDetail: (String) -> Unit, back: () -> Unit) {
    BackHandler(onBack = back)
    DisposableEffect(lifecycle) {
        fun discard() { model.reset(); connection.clearHealthResult() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) discard() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); discard() }
    }
    val ui = connection.ui
    val review = model.review
    val busy = model.reading || ui.busy
    val isHealth = ui.answerContext == AnswerContext.HEALTH
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.manual_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.build_identifier, BuildConfig.BUILD_IDENTIFIER))
        Text(stringResource(R.string.manual_notice))
        if (!ui.permitted) Text(stringResource(R.string.manual_need_connection))
        OutlinedTextField(value = model.goal, onValueChange = model::editGoal,
            label = { Text(stringResource(R.string.manual_goal)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            enabled = !busy && review == null, isError = model.goalInvalid)
        if (model.goalInvalid) Text(stringResource(R.string.manual_goal_invalid))
        if (review == null) {
            OutlinedButton(onClick = connection::loadModels, enabled = !busy && ui.permitted) {
                Text(stringResource(R.string.fetch_models))
            }
            ui.models.forEach { item ->
                TextButton(onClick = { connection.selectModel(item.slug) }, enabled = !busy) {
                    Text(stringResource(if (ui.model == item.slug) R.string.selected_option else R.string.option, item.name))
                }
            }
            OutlinedButton(onClick = {
                val selected = connection.ui.models.find { it.slug == connection.ui.model }
                if (selected != null) { connection.clearHealthResult(); model.prepare(selected) }
            }, enabled = !busy && ui.model != null) { Text(stringResource(R.string.manual_prepare)) }
            if (model.reading) Text(stringResource(R.string.health_loading))
            if (model.prepareFailed) Text(stringResource(R.string.manual_prepare_failed))
        } else {
            HorizontalDivider()
            Text(stringResource(R.string.manual_preview), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.manual_model, review.modelName, review.model))
            if (model.readUnavailable) Text(stringResource(R.string.manual_unavailable))
            Text(stringResource(R.string.manual_preview_notice))
            Text(stringResource(R.string.manual_instructions))
            Text(review.instructions)
            Text(stringResource(R.string.manual_input))
            Text(review.input)
            Button(onClick = { connection.generateReviewed(review) }, enabled = !busy && ui.permitted) {
                Text(stringResource(if (isHealth && ui.failure != null) R.string.manual_retry else R.string.manual_send))
            }
            OutlinedButton(onClick = { connection.clearHealthResult(); model.reset() }, enabled = !busy) {
                Text(stringResource(R.string.manual_edit))
            }
        }
        if (isHealth) {
            Text(stringResource(ui.status))
            if (ui.failure != null) Text(stringResource(failureText(ui.failure)))
            if (ui.answer.isNotBlank()) {
                Text(stringResource(R.string.answer_title), style = MaterialTheme.typography.titleMedium)
                Text(if (ui.savedAnswerId != null) ui.answerShort else ui.answer)
                ui.savedAnswerId?.let { id ->
                    OutlinedButton(onClick = { openDetail(id) }) { Text(stringResource(R.string.detail_open)) }
                }
                if (ui.answerSaveFailed) Text(stringResource(R.string.detail_save_failed))
            }
            if (ui.busy) OutlinedButton(onClick = connection::cancel) { Text(stringResource(R.string.cancel)) }
        } else if (ui.failure != null) Text(stringResource(failureText(ui.failure)))
        OutlinedButton(onClick = back) { Text(stringResource(R.string.probe_back)) }
    }
}
