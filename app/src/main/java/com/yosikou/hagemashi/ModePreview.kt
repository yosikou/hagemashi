package com.yosikou.hagemashi
import android.app.Application
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId

internal class ModePreviewViewModel(application: Application): AndroidViewModel(application) {
    var rows by mutableStateOf<List<MeasurementRow>>(emptyList()); private set
    var failed by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    private var epoch=0L
    private var job: Job?=null
    fun clear() { ++epoch; job?.cancel(); rows=emptyList(); loading=false }
    fun load(kind: GenerationKind, settings: AppSettings?) {
        clear(); val generation=epoch; loading=true;failed=false
        job=viewModelScope.launch {
            try {
                val zone=ZoneId.systemDefault(); val now=Instant.now()
                val snapshot=withContext(Dispatchers.IO) {
                    val context=getApplication<Application>()
                    check(Build.VERSION.SDK_INT>=28 && HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE)
                    HealthReader(HealthConnectSource(HealthConnectClient.getOrCreate(context))).read(now,zone,HealthReadPlan.GENERATION,kind)
                }
                if(generation==epoch) rows=measurementRows(snapshot,settings?.goal,settings?.goalDate?.toString(),zone)
            } catch(e:CancellationException){throw e} catch(_:Exception){if(generation==epoch)failed=true} finally{if(generation==epoch)loading=false}
        }
    }
}
@Composable internal fun ModePreviewScreen(kind:GenerationKind, model:ModePreviewViewModel, settings:AppSettings?, back:()->Unit) {
    LaunchedEffect(kind){model.load(kind,settings)}
    DisposableEffect(Unit){onDispose{model.clear()}}
    Page(if(kind==GenerationKind.MORNING) R.string.preview_morning else R.string.preview_evening,back){
        Text(stringResource(if(kind==GenerationKind.MORNING) R.string.morning_rules else R.string.manual_reflection_rules))
        Text(stringResource(R.string.preview_only_no_send))
        if(model.loading) Text(stringResource(R.string.loading))
        if(model.failed) Text(stringResource(R.string.health_read_error))
        MeasurementTable(model.rows)
        OutlinedButton(onClick={model.load(kind,settings)},enabled=!model.loading){Text(stringResource(R.string.health_refresh))}
    }
}
