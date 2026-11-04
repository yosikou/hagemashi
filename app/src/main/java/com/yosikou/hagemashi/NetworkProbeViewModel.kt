package com.yosikou.hagemashi

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

internal class NetworkProbeViewModel(application: Application) : AndroidViewModel(application) {
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf(Progress())
        private set
    var results by mutableStateOf(emptyList<ProbeResult>())
        private set
    private val probes = NetworkProbes()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var operation: Operation? = null
    private var epoch = 0L
    @Volatile private var foreground = false
    fun setForeground(value: Boolean) { foreground = value }
    fun report(): String = probeReport(BuildConfig.BUILD_IDENTIFIER, BuildConfig.APPLICATION_ID, BuildConfig.BUILD_TYPE,
        readNetworkFacts(getApplication())?.internetGranted, results)
    fun runProbe(kind: ProbeKind) {
        if (busy) return
        val generation = ++epoch
        val op = Operation(60000) { value -> main.post { if (generation == epoch) progress = value } }
        operation = op
        busy = true
        results = results.filterNot { it.kind == kind }
        progress = Progress(kind.stage, Phase.RUNNING)
        executor.execute {
            try {
                val result = probes.execute(kind, op) { foreground }
                main.post { if (generation == epoch) {
                    results = (results + result).sortedBy { it.kind.ordinal }
                    progress = result.progress
                    busy = false
                } }
            } catch (_: CancellationException) { /* User cancelled; old results are not published. */ }
        }
    }
    fun cancel() {
        ++epoch
        operation?.cancel()
        busy = false
        progress = progress.copy(phase = Phase.CANCELLED)
    }
    override fun onCleared() {
        ++epoch
        operation?.cancel()
        probes.close()
        executor.shutdownNow()
    }
}
