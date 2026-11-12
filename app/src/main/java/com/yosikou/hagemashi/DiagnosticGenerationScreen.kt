package com.yosikou.hagemashi

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource

@Composable internal fun DiagnosticGenerationScreen(model:ConnectionViewModel,back:()->Unit) {
    if(!diagnosticUi())return
    val ui=model.ui
    Page(R.string.diagnostic_generation,back) {
        Text(stringResource(R.string.build_identifier,BuildConfig.BUILD_IDENTIFIER))
        Text(stringResource(R.string.dummy_prompt))
        Text(stringResource(R.string.persona_prompt))
        OutlinedButton(onClick=model::loadModels,enabled=ui.permitted && !ui.busy){Text(stringResource(R.string.fetch_models))}
        ui.models.forEach{item->TextButton(onClick={model.selectModel(item.slug)},enabled=!ui.busy){Text(stringResource(R.string.manual_model,item.name,item.slug))}}
        Button(onClick=model::generate,enabled=ui.permitted && !ui.busy && ui.model!=null){Text(stringResource(R.string.review_generate))}
        Text(stringResource(ui.status))
        ui.failure?.let{Text(stringResource(failureText(it)))}
        if(ui.answerContext==AnswerContext.DUMMY)Text(ui.answer)
    }
}
