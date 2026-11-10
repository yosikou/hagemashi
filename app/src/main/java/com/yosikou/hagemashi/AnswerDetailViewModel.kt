package com.yosikou.hagemashi

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Local encrypted storage and Android notifications only. No AI/Health Connect/network dependency.
internal class AnswerDetailViewModel(application: Application) : AndroidViewModel(application) {
    var answer by mutableStateOf<SavedEncouragement?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var deleted by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var notice by mutableStateOf(NoticeState.IDLE)
        private set
    var noticeId by mutableStateOf<String?>(null)
        private set
    var notifying by mutableStateOf(false)
        private set
    private var permissionId: String? = null
    private val store = AnswerStore(application)
    private var loadJob: Job? = null
    private var epoch = 0L
    fun load(id: String) {
        loadJob?.cancel()
        val generation = ++epoch
        answer = null; loading = true; loadFailed = false; deleted=false
        loadJob = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.load(id) }
                if (generation == epoch) answer = saved
            } catch (e: CancellationException) { throw e }
            catch (_: AnswerDeleted) { if(generation==epoch) deleted=true }
            catch (_: Exception) { if (generation == epoch) loadFailed = true }
            finally { if (generation == epoch) loading = false }
        }
    }
    fun close() { ++epoch; loadJob?.cancel(); answer = null; loading = false; loadFailed = false; deleted=false }
    fun awaitPermission(id: String) { permissionId = id; noticeId = id; notice = NoticeState.IDLE }
    fun permissionResult(granted: Boolean) {
        val id = permissionId ?: return
        permissionId = null
        if (granted) testNotification(id) else { noticeId = id; notice = NoticeState.PERMISSION_DENIED }
    }
    fun testNotification(id: String) {
        if (notifying || !isAnswerId(id)) return
        notifying = true; noticeId = id; notice = NoticeState.SENDING
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.load(id) }
                notice = AnswerNotifications(getApplication<Application>()).post(saved)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = NoticeState.FAILED }
            finally { notifying = false }
        }
    }
}
