package com.yosikou.hagemashi

import android.app.Application
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal val healthReadPermissions = mapOf(
    HealthKind.WEIGHT to HealthPermission.getReadPermission(WeightRecord::class),
    HealthKind.BODY_FAT to HealthPermission.getReadPermission(BodyFatRecord::class),
    HealthKind.STEPS to HealthPermission.getReadPermission(StepsRecord::class)
)
internal enum class HealthProvider { CHECKING, AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE, ERROR }
internal data class HealthUi(val provider: HealthProvider = HealthProvider.CHECKING, val busy: Boolean = false,
    val snapshot: HealthSnapshot? = null, val settingsFailed: Boolean = false)

internal class HealthConnectSource(private val client: HealthConnectClient) : HealthDataSource {
    override suspend fun permissions(): Set<HealthKind> {
        val granted = client.permissionController.getGrantedPermissions()
        return healthReadPermissions.filterValues { it in granted }.keys
    }
    override suspend fun samples(kind: HealthKind, window: HealthWindow): HealthPage {
        val range = TimeRangeFilter.between(window.start, window.end)
        return when (kind) {
            HealthKind.WEIGHT -> {
                val samples = mutableListOf<HealthSample>()
                var token: String? = null
                do {
                    val page = client.readRecords(ReadRecordsRequest(WeightRecord::class, range, ascendingOrder = false, pageSize = 1000, pageToken = token))
                    samples += page.records.map { HealthSample(it.weight.inKilograms, it.time, it.metadata.dataOrigin.packageName) }
                    token = page.pageToken
                } while (token != null)
                HealthPage(samples)
            }
            HealthKind.BODY_FAT -> {
                val samples = mutableListOf<HealthSample>()
                var token: String? = null
                do {
                    val page = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range, ascendingOrder = false, pageSize = 1000, pageToken = token))
                    samples += page.records.map { HealthSample(it.percentage.value, it.time, it.metadata.dataOrigin.packageName) }
                    token = page.pageToken
                } while (token != null)
                HealthPage(samples)
            }
            HealthKind.STEPS -> throw IllegalArgumentException()
        }
    }
    override suspend fun steps(window: HealthWindow): HealthSteps? {
        val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(window.start, window.end)))
        val count = result[StepsRecord.COUNT_TOTAL] ?: return null
        return HealthSteps(count, result.dataOrigins.map { it.packageName }.toSet())
    }
}

internal class HealthConnectViewModel(application: Application) : AndroidViewModel(application) {
    var ui by mutableStateOf(HealthUi())
        private set
    private var job: Job? = null
    private var epoch = 0L
    fun refresh() {
        job?.cancel()
        val generation = ++epoch
        ui = HealthUi(busy = true)
        job = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val provider = if (Build.VERSION.SDK_INT < 28) HealthProvider.UNAVAILABLE else when (HealthConnectClient.getSdkStatus(app)) {
                    HealthConnectClient.SDK_AVAILABLE -> HealthProvider.AVAILABLE
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthProvider.UPDATE_REQUIRED
                    else -> HealthProvider.UNAVAILABLE
                }
                val now = Instant.now()
                val zone = ZoneId.systemDefault()
                val snapshot = if (provider == HealthProvider.AVAILABLE) withContext(Dispatchers.IO) {
                    HealthReader(HealthConnectSource(HealthConnectClient.getOrCreate(app))).read(now, zone)
                } else null
                if (generation == epoch) ui = HealthUi(provider = provider, snapshot = snapshot)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == epoch) ui = HealthUi(provider = HealthProvider.ERROR) }
        }
    }
    fun stopRead() {
        ++epoch
        job?.cancel()
        ui = ui.copy(busy = false, snapshot = null)
    }
    fun settingsFailed() { ui = ui.copy(settingsFailed = true) }
}
