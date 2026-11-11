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
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AppViewModel(application: Application) : AndroidViewModel(application) {
    var settings by mutableStateOf<AppSettings?>(null); private set
    var latest by mutableStateOf<SavedEncouragement?>(null); private set
    var weight by mutableStateOf<HealthSample?>(null); private set
    var slotStatuses by mutableStateOf<Map<NoticeSlot,Int>>(emptyMap()); private set
    var reservationStatus by mutableStateOf(R.string.reservation_unavailable); private set
    var failure by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    private val store = SettingsStore(application)
    private val mutex = Mutex()
    private var startupTokensCaptured=false
    init { refresh() }
    fun refresh() { viewModelScope.launch {
        mutex.withLock {
            try {
                var schedulingFailed=false
                val loaded = withContext(Dispatchers.IO) { synchronized(ReservationSupport.lock) {
                    store.read().also { cfg ->
                        if(!startupTokensCaptured){startupTokensCaptured=true;ReservationSupport.captureTokensBeforeRefresh(getApplication<Application>(),cfg)}
                        schedulingFailed=runCatching{ReservationSupport.changed(getApplication<Application>(),cfg,cfg)}.isFailure }
                } }
                settings = loaded; failure = false
                refreshDeliveryStatus(loaded,schedulingFailed)
                latest = withContext(Dispatchers.IO) { AnswerStore(getApplication<Application>()).latest() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure = true }
        }
        weight = withContext(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                if (Build.VERSION.SDK_INT < 28 || HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) null
                else {
                    val source = HealthConnectSource(HealthConnectClient.getOrCreate(context))
                    if (HealthKind.WEIGHT !in source.permissions()) null else {
                        val now = Instant.now()
                        val value = source.samples(HealthKind.WEIGHT, HealthWindow(now.minus(Duration.ofDays(30)), now)).samples.maxByOrNull { it.measuredAt }
                        if (HealthKind.WEIGHT in source.permissions()) value else null
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    } }
    fun change(transform: (AppSettings) -> AppSettings) {
        if (settings == null || saving) return
        saving = true
        viewModelScope.launch {
            try {
                var schedulingFailed=false
                val value = mutex.withLock {
                    withContext(Dispatchers.IO) { synchronized(ReservationSupport.lock) {
                        val old = store.read(); val next = nextSettings(old, transform(old))
                        store.write(next)
                        schedulingFailed=runCatching{ReservationSupport.changed(getApplication<Application>(), old, next)}.isFailure
                        next
                    } }
                }
                settings = value; failure = false
                refreshDeliveryStatus(value,schedulingFailed)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure = true }
            finally { saving = false }
        }
    }
    private suspend fun refreshDeliveryStatus(cfg:AppSettings,failed:Boolean) {
        val result=withContext(Dispatchers.IO) {
            try {
                if(failed) throw Problem(Failure.STORAGE)
                ReservationSupport.status(getApplication<Application>(),cfg) to ReservationSupport.slotStatuses(getApplication<Application>(),cfg)
            } catch(e:CancellationException){throw e}
            catch(_:Exception){R.string.reservation_state_error to NoticeSlot.entries.associateWith{R.string.reservation_state_error}}
        }
        reservationStatus=result.first;slotStatuses=result.second
    }

}
