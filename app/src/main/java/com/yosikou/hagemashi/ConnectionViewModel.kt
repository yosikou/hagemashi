package com.yosikou.hagemashi

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

internal data class ConnectionUi(
    val busy: Boolean = true, val connected: Boolean = false, val permitted: Boolean = false,
    val name: String = "", val email: String = "", val accounts: List<Account> = emptyList(),
    val selectedAccount: String? = null, val forceNew: Boolean = false, val models: List<Model> = emptyList(), val model: String? = null,
    val answer: String = "", val status: Int = R.string.loading, val failure: Failure? = null,
    val diagnostic: String = "", val browserUrl: String? = null, val welcome: Boolean = false
)

internal class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    var ui by mutableStateOf(ConnectionUi())
        private set
    private val repository = ConnectionRepository(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var epoch = 0L
    private var operation: Operation? = null
    private data class Result(val status: Int = 0, val answer: String? = null, val models: List<Model>? = null)

    init { run(R.string.loading) { Result() } }
    fun selectAccount(id: String?) { if (!ui.busy && !ui.connected) ui = ui.copy(selectedAccount = id, forceNew = id == null) }
    fun selectModel(id: String) { if (!ui.busy && ui.models.any { it.slug == id }) ui = ui.copy(model = id) }
    fun browserOpened() { ui = ui.copy(browserUrl = null) }
    fun browserFailed() { cancel(R.string.browser_failed) }

    fun signIn() {
        if (ui.busy || ui.connected) return
        val selected = ui.selectedAccount
        val forceNew = ui.forceNew
        val generation = epoch + 1
        run(R.string.signing_in, 300000) { op ->
            val attempt = repository.prepareLogin(selected, getApplication<Application>().getString(R.string.app_name), op, forceNew)
            main.post { if (epoch == generation) ui = ui.copy(browserUrl = attempt.url, forceNew = false) }
            repository.finishLogin(attempt, op, getApplication<Application>().getString(R.string.browser_reply))
            Result(R.string.sign_in_done)
        }
    }

    fun loadModels() {
        if (ui.busy || !ui.permitted) return
        run(R.string.loading_models) { op ->
            val models = repository.models(op)
            Result(if (models.isEmpty()) R.string.no_models else R.string.models_loaded, models = models)
        }
    }

    fun generate() {
        val model = ui.model ?: return
        if (ui.busy || !ui.permitted) return
        ui = ui.copy(answer = "")
        run(R.string.generating) { op ->
            val app = getApplication<Application>()
            Result(R.string.generation_success, answer = repository.generate(model, app.getString(R.string.persona_prompt), app.getString(R.string.dummy_prompt), op))
        }
    }

    fun disconnect() {
        ui = ui.copy(answer = "", models = emptyList(), model = null)
        run(R.string.disconnecting, 60000) { op ->
            Result(if (repository.disconnect(op)) R.string.disconnected_confirmed else R.string.disconnected_unconfirmed)
        }
    }

    fun dismissWelcome() {
        if (ui.busy) return
        run(R.string.saving) { op -> repository.dismissWelcome(op); Result() }
    }

    fun eraseUnrecoverable() {
        ui = ui.copy(answer = "", models = emptyList(), model = null, selectedAccount = null)
        run(R.string.saving) { repository.eraseUnrecoverable(); Result(R.string.disconnected_unconfirmed) }
    }

    fun cancel(status: Int = R.string.cancelled) { run(R.string.cancelling) { Result(status) } }

    // Storage, code exchange and rotating refresh tokens share one serialized executor.
    private fun run(status: Int, timeout: Long = 180000, work: (Operation) -> Result) {
        operation?.cancel()
        val generation = ++epoch
        val op = Operation(timeout)
        operation = op
        ui = ui.copy(busy = true, status = status, failure = null, diagnostic = "", browserUrl = null)
        executor.execute {
            try {
                val result = work(op)
                val snapshot = repository.snapshot()
                op.check()
                main.post {
                    if (generation == epoch) {
                        val models = if (!snapshot.connected) emptyList() else result.models ?: ui.models
                        ui = ui.copy(busy = false, connected = snapshot.connected, permitted = snapshot.permitted,
                            name = snapshot.name, email = snapshot.email, accounts = snapshot.accounts,
                            models = models, model = ui.model?.takeIf { selected -> models.any { it.slug == selected } } ?: models.firstOrNull()?.slug,
                            answer = result.answer ?: ui.answer, status = result.status.takeIf { it != 0 } ?: if (snapshot.connected) R.string.connected else R.string.disconnected,
                            welcome = snapshot.welcome, browserUrl = null)
                    }
                }
            } catch (e: Exception) {
                val failure = when (e) {
                    is Problem -> e.kind
                    is SocketTimeoutException -> Failure.TIMEOUT
                    is IOException -> Failure.NETWORK
                    is CancellationException -> null
                    else -> Failure.PROTOCOL
                }
                if (failure == Failure.EXPIRED) runCatching { op.commit { repository.clearExpired() } }
                val snapshot = runCatching { repository.snapshot() }.getOrNull()
                main.post {
                    if (generation == epoch) {
                        ui = ui.copy(busy = false, failure = failure, status = if (failure == null) R.string.cancelled else R.string.operation_failed,
                            connected = snapshot?.connected ?: false, permitted = snapshot?.permitted ?: false,
                            name = snapshot?.name.orEmpty(), email = snapshot?.email.orEmpty(), accounts = snapshot?.accounts ?: ui.accounts,
                            welcome = snapshot?.welcome ?: false, browserUrl = null,
                            models = if (snapshot?.connected == true) ui.models else emptyList(), model = if (snapshot?.connected == true) ui.model else null,
                            diagnostic = if (e is Problem) listOfNotNull(e.status?.let { "HTTP $it" }, e.code).joinToString(" / ") else "")
                    }
                }
            }
        }
    }

    override fun onCleared() {
        ++epoch
        operation?.cancel()
        executor.shutdownNow()
    }
}
