package com.yosikou.hagemashi

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable internal fun PersonaComparisonScreen(connection:ConnectionViewModel,settings:AppSettings?,back:()->Unit) {
    if(BuildConfig.BUILD_TYPE!="diagnostic")return
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var kind by remember{mutableStateOf(GenerationKind.MANUAL)}
    var selected by remember{mutableStateOf(settings?.modelId)}
    var rows by remember{mutableStateOf<List<MeasurementRow>>(emptyList())}
    var input by remember{mutableStateOf("")}
    var outputs by remember{mutableStateOf<Map<String,String>>(emptyMap())}
    var running by remember{mutableStateOf(false)}
    var job by remember{mutableStateOf<Job?>(null)}
    val personas=remember{defaultPersonas(context)}
    LaunchedEffect(kind){
        val snapshot=personaComparisonSnapshot(kind)
        rows=measurementRows(snapshot,"68",null,comparisonZone)
        input=context.getString(R.string.persona_comparison_fictional)+"\n"+healthGenerationInput("68",snapshot,comparisonZone,comparisonLabels(context))
    }
    DisposableEffect(Unit){onDispose{job?.cancel();if(running)connection.cancel()}}
    Page(R.string.persona_comparison_title,back) {
        Text(stringResource(R.string.persona_comparison_notice))
        Text(stringResource(R.string.build_identifier,BuildConfig.BUILD_IDENTIFIER))
        GenerationKind.entries.forEach{value->TextButton(onClick={kind=value},enabled=!running){Text(generationKindLabel(value))}}
        Text(generationKindLabel(kind),style=MaterialTheme.typography.titleMedium)
        MeasurementTable(rows,showGroupingNote=true)
        OutlinedButton(onClick=connection::loadModels,enabled=connection.ui.permitted && !connection.ui.busy && !running){Text(stringResource(R.string.fetch_models))}
        connection.ui.models.forEach{model->TextButton(onClick={selected=model.slug},enabled=!running && !connection.ui.busy){
            Text(stringResource(if(selected==model.slug)R.string.selected_option else R.string.option,model.name))
        }}
        Button(onClick={
            val model=selected ?: return@Button
            val frozenInput=input
            val common=context.getString(R.string.manual_generation_rules)+"\n"+context.getString(if(kind==GenerationKind.MORNING)R.string.morning_rules else R.string.manual_reflection_rules)
            outputs=emptyMap();running=true
            job=scope.launch {
                try {
                    for(persona in personas) {
                        connection.comparePersona(model,persona.prompt+"\n"+common,frozenInput)
                        val result=snapshotFlow{connection.ui}.first{!it.busy}
                        val failure=result.failure
                        val text=if(failure!=null)context.getString(failureText(failure)) else runCatching{encouragementText(result.answer,context.getString(R.string.notification_fallback)).full}.getOrDefault(context.getString(R.string.operation_failed))
                        outputs=outputs+(persona.id to text)
                        if(failure!=null)break
                    }
                }finally{running=false}
            }
        },enabled=!running && !connection.ui.busy && connection.ui.permitted && connection.ui.models.any{it.slug==selected} && input.isNotBlank()){
            Text(stringResource(R.string.persona_comparison_start))
        }
        if(running)OutlinedButton(onClick={job?.cancel();connection.cancel();running=false}){Text(stringResource(R.string.persona_comparison_stop))}
        personas.forEach{persona->SectionCard{
            Text(persona.name,style=MaterialTheme.typography.titleMedium)
            Text(outputs[persona.id] ?: stringResource(R.string.persona_comparison_not_run))
        }}
    }
}
private fun comparisonLabels(context:Context):HealthPromptLabels {
    fun s(id:Int)=context.getString(id)
    return HealthPromptLabels(s(R.string.manual_goal_format),s(R.string.manual_read_format),s(R.string.manual_period_format),
        s(R.string.manual_measurements),s(R.string.manual_steps),s(R.string.manual_sample_format),s(R.string.manual_day_format),
        s(R.string.manual_step_count),s(R.string.manual_missing_format),s(R.string.health_weight),s(R.string.health_body_fat),s(R.string.manual_kg),s(R.string.manual_percent),
        s(R.string.health_origin_unknown),s(R.string.manual_partial),mapOf(HealthReadStatus.PERMISSION_REQUIRED to s(R.string.manual_missing_permission),
            HealthReadStatus.NO_RECORDS to s(R.string.manual_missing_record),HealthReadStatus.ACCESS_DENIED to s(R.string.manual_missing_denied),
            HealthReadStatus.ERROR to s(R.string.manual_missing_error),HealthReadStatus.HAS_RECORDS to s(R.string.health_has_records)))
}
