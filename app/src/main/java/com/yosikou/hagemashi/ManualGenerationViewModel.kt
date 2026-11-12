package com.yosikou.hagemashi

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ManualGenerationViewModel(application: Application) : AndroidViewModel(application) {
    var goal by mutableStateOf("")
        private set
    var review by mutableStateOf<ReviewedGeneration?>(null)
        private set
    var reading by mutableStateOf(false)
        private set
    var goalInvalid by mutableStateOf(false)
        private set
    var readUnavailable by mutableStateOf(false)
        private set
    var prepareFailed by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var epoch = 0L

    fun editGoal(value: String) { if (!reading && review == null) { goal = value; goalInvalid = false } }
    fun reset() { ++epoch; job?.cancel(); reading = false; review = null; readUnavailable = false; prepareFailed = false }
    fun prepare(model: Model) {
        if (reading || review != null) return
        val target = canonicalGoal(goal)
        goalInvalid = target == null
        if (target == null) return
        val generation = ++epoch
        reading = true
        prepareFailed = false
        job = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val now = Instant.now()
                val zone = ZoneId.systemDefault()
                var unavailable = false
                val snapshot = withContext(Dispatchers.IO) {
                    try {
                        check(Build.VERSION.SDK_INT >= 28 && HealthConnectClient.getSdkStatus(app) == HealthConnectClient.SDK_AVAILABLE)
                        HealthReader(HealthConnectSource(HealthConnectClient.getOrCreate(app))).read(now, zone, HealthReadPlan.GENERATION)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        unavailable = true
                        val empty = object : HealthDataSource {
                            override suspend fun permissions() = emptySet<HealthKind>()
                            override suspend fun samples(kind: HealthKind, window: HealthWindow): HealthPage = error("unreachable")
                            override suspend fun steps(window: HealthWindow): HealthSteps? = error("unreachable")
                        }
                        HealthReader(empty).read(now, zone, HealthReadPlan.GENERATION).let { snapshot ->
                            snapshot.copy(results = snapshot.results.map { it.copy(status = HealthReadStatus.ERROR) })
                        }
                    }
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
                val instructions = string(R.string.persona_prompt) + "\n" + string(R.string.manual_generation_rules)
                val frozen = ReviewedGeneration(model.slug, model.name, instructions, healthGenerationInput(target, snapshot, zone, labels),
                    snapshot.results.any { it.samples.isNotEmpty() || it.steps != null || it.days.any { day -> day.value != null } })
                if (generation == epoch) { review = frozen; readUnavailable = unavailable }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == epoch) prepareFailed = true }
            finally { if (generation == epoch) reading = false }
        }
    }
}
