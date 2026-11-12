package com.yosikou.hagemashi

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

internal data class PreparedGeneration(val review:ReviewedGeneration,val health:ReservationEvent)
internal suspend fun prepareGeneration(app:Context,settings:AppSettings,model:Model,kind:GenerationKind,
    now:Instant=Instant.now(),background:Boolean=false):PreparedGeneration {
    val target=settings.goal?.let(::canonicalGoal) ?: throw Problem(Failure.POLICY)
    val zone=ZoneId.systemDefault()
    var status=ReservationEvent.HEALTH_READ
    val snapshot=try {
        if(Build.VERSION.SDK_INT<28 || HealthConnectClient.getSdkStatus(app)!=HealthConnectClient.SDK_AVAILABLE) {
            status=ReservationEvent.HEALTH_UNSUPPORTED
            emptyGenerationSnapshot(now,zone,kind,HealthReadStatus.ERROR)
        }else {
            val client=HealthConnectClient.getOrCreate(app)
            val supported=!background || client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)==HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            val allowed=!background || HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in client.permissionController.getGrantedPermissions()
            if(!supported || !allowed) {
                status=if(supported)ReservationEvent.HEALTH_UNPERMITTED else ReservationEvent.HEALTH_UNSUPPORTED
                emptyGenerationSnapshot(now,zone,kind,if(supported)HealthReadStatus.PERMISSION_REQUIRED else HealthReadStatus.ERROR)
            }else HealthReader(HealthConnectSource(client)).read(now,zone,HealthReadPlan.GENERATION,kind).let {
                if(background && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in client.permissionController.getGrantedPermissions()) {
                    status=ReservationEvent.HEALTH_UNPERMITTED
                    emptyGenerationSnapshot(now,zone,kind,HealthReadStatus.PERMISSION_REQUIRED)
                }else it
            }
        }
    }catch(e:CancellationException){throw e}catch(_:SecurityException){
        status=ReservationEvent.HEALTH_UNPERMITTED
        emptyGenerationSnapshot(now,zone,kind,HealthReadStatus.PERMISSION_REQUIRED)
    }catch(_:Exception){
        status=ReservationEvent.HEALTH_FAILED
        emptyGenerationSnapshot(now,zone,kind,HealthReadStatus.ERROR)
    }
    fun string(id: Int) = app.getString(id)
    val labels = HealthPromptLabels(
        string(R.string.manual_goal_format), string(R.string.manual_read_format), string(R.string.manual_period_format),
        string(R.string.manual_measurements), string(R.string.manual_steps), string(R.string.manual_sample_format),
        string(R.string.manual_day_format), string(R.string.manual_step_count), string(R.string.manual_missing_format), string(R.string.health_weight),
        string(R.string.health_body_fat), string(R.string.manual_kg), string(R.string.manual_percent),
        string(R.string.health_origin_unknown), string(R.string.manual_partial),
        mapOf(HealthReadStatus.PERMISSION_REQUIRED to string(R.string.manual_missing_permission),
            HealthReadStatus.NO_RECORDS to string(R.string.manual_missing_record),
            HealthReadStatus.ACCESS_DENIED to string(R.string.manual_missing_denied),
            HealthReadStatus.ERROR to string(R.string.manual_missing_error),
            HealthReadStatus.HAS_RECORDS to string(R.string.health_has_records)))
    val persona = settings.persona()
    val instructions = listOf(persona.prompt, settings.supplement,
        if (settings.maintaining) string(R.string.maintain_prompt) else "", string(R.string.manual_generation_rules), string(if(kind==GenerationKind.MORNING)R.string.morning_rules else R.string.manual_reflection_rules))
        .filter { it.isNotBlank() }.joinToString("\n")
    val input = healthGenerationInput(target, snapshot, zone, labels) +
        (settings.goalDate?.let { "\n" + app.getString(R.string.goal_date_format, it.toString()) } ?: "")
    val frozen = ReviewedGeneration(model.slug, model.name, instructions, input,
        snapshot.results.any { it.samples.isNotEmpty() || it.steps != null || it.days.any { day -> day.value != null } }, persona.name,
        measurementRows(snapshot, target, settings.goalDate?.toString(), zone), kind)
    if(status==ReservationEvent.HEALTH_READ)status=when {
        frozen.hasHealthData->ReservationEvent.HEALTH_READ
        snapshot.results.any{it.status==HealthReadStatus.ERROR}->ReservationEvent.HEALTH_FAILED
        snapshot.results.any{it.status in setOf(HealthReadStatus.PERMISSION_REQUIRED,HealthReadStatus.ACCESS_DENIED)}->ReservationEvent.HEALTH_UNPERMITTED
        else->ReservationEvent.HEALTH_EMPTY
    }
    return PreparedGeneration(frozen,status)
}
private suspend fun emptyGenerationSnapshot(now:Instant,zone:ZoneId,kind:GenerationKind,status:HealthReadStatus):HealthSnapshot {
    val empty=object:HealthDataSource {
        override suspend fun permissions()=emptySet<HealthKind>()
        override suspend fun samples(kind:HealthKind,window:HealthWindow):HealthPage=error("unreachable")
        override suspend fun steps(window:HealthWindow):HealthSteps?=error("unreachable")
    }
    return HealthReader(empty).read(now,zone,HealthReadPlan.GENERATION,kind).let{s->s.copy(results=s.results.map{it.copy(status=status)})}
}
