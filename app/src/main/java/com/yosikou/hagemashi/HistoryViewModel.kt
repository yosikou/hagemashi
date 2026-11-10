package com.yosikou.hagemashi

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HistoryViewModel(application: Application): AndroidViewModel(application) {
    var answers by mutableStateOf<List<SavedEncouragement>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var hasMore by mutableStateOf(true); private set
    private var cursor: HistoryCursor? = null
    private val store = AnswerStore(application)
    fun load(reset: Boolean = false) {
        if (busy || (!reset && !hasMore)) return
        busy = true; failed = false
        if (reset) { cursor=null; answers=emptyList(); hasMore=true }
        val next = cursor
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { store.page(next) }
                answers = if (reset) page.answers else (answers + page.answers).distinctBy { it.id }
                cursor = page.next; hasMore = cursor != null
            } catch(e: CancellationException) { throw e } catch (_: Exception) { failed=true }
            finally { busy=false }
        }
    }
    fun remove(id: String? = null) {
        if (busy) return
        busy = true; failed=false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (id == null) store.deleteAll() else store.delete(id) }
                answers=emptyList(); cursor=null; hasMore=true
            } catch(e: CancellationException) { throw e } catch (_: Exception) { failed=true }
            finally { busy=false }
            if (!failed) load(true)
        }
    }
}
