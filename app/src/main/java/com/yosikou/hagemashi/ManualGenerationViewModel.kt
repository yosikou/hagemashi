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
    fun prepare(model: Model, settings: AppSettings) {
        if (reading || review != null) return
        val target = settings.goal?.let(::canonicalGoal)
        goalInvalid = target == null
        if (target == null) return
        val generation = ++epoch
        reading = true
        prepareFailed = false
        job = viewModelScope.launch {
            try {
                val prepared=withContext(Dispatchers.IO){prepareGeneration(getApplication<Application>(),settings,model,GenerationKind.MANUAL)}
                if(generation==epoch){review=prepared.review;readUnavailable=prepared.health in setOf(ReservationEvent.HEALTH_UNSUPPORTED,ReservationEvent.HEALTH_FAILED)}
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == epoch) prepareFailed = true }
            finally { if (generation == epoch) reading = false }
        }
    }
}
