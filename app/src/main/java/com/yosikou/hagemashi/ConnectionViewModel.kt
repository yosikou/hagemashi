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

internal data class ConnectionUi(
    val busy: Boolean = true, val connected: Boolean = false, val permitted: Boolean = false,
    val name: String = "", val email: String = "", val accounts: List<Account> = emptyList(),
    val selectedAccount: String? = null, val forceNew: Boolean = false, val models: List<Model> = emptyList(), val model: String? = null,
    val answer: String = "", val status: Int = R.string.loading, val failure: Failure? = null,
    val diagnostic: SafeDiagnostic? = null, val browserUrl: String? = null, val welcome: Boolean = false,
    val progress: Progress = Progress(), val savedConnection: SavedConnection = SavedConnection.UNKNOWN,
    val lastWhileAway: Stage = Stage.NONE, val networkFacts: NetworkFacts? = null,
    val dnsObservations: List<DnsObservation> = emptyList(),
    val generationDiagnostic: GenerationDiagnostic? = null
)

internal class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    var ui by mutableStateOf(ConnectionUi(networkFacts = readNetworkFacts(application)))
        private set
    private val repository = ConnectionRepository(application)
    private val loginReturn = LoginReturn()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var epoch = 0L
    private var operation: Operation? = null
    private val dnsComparison = DnsComparison()
    private val comparisonCoordinator = Executors.newSingleThreadExecutor()
    private var comparisonGeneration = -1L
    private var comparisonOperation: Operation? = null
    private var comparisonAway = false
    private var returnSampleQueued = false
    @Volatile private var foreground = false
    private data class Result(val status: Int = 0, val answer: String? = null, val models: List<Model>? = null)

    init { run(R.string.loading) { Result() } }
    fun setResumed(value: Boolean) { loginReturn.setResumed(value) }
    fun setForeground(value: Boolean) {
        val wasVisible = foreground
        foreground = value
        if (!value && comparisonGeneration == epoch) comparisonAway = true
        if (value && !wasVisible && comparisonAway && !returnSampleQueued && comparisonGeneration == epoch) {
            val op = comparisonOperation ?: return
            val generation = comparisonGeneration
            returnSampleQueued = true
            comparisonCoordinator.execute { sampleDns(DnsPoint.AFTER_RESUME, op, generation) }
        }
    }
    fun selectAccount(id: String?) { if (!ui.busy && !ui.connected) ui = ui.copy(selectedAccount = id, forceNew = id == null) }
    fun selectModel(id: String) { if (!ui.busy && ui.models.any { it.slug == id }) ui = ui.copy(model = id) }
    fun browserOpened() { ui = ui.copy(browserUrl = null) }
    fun browserFailed() { cancel(R.string.browser_failed) }

    fun signIn(compareDns: Boolean = false) {
        if (ui.busy || ui.connected) return
        val selected = ui.selectedAccount
        val forceNew = ui.forceNew
        val networkFacts = ui.networkFacts
        val generation = epoch + 1
        comparisonGeneration = if (compareDns) generation else -1L
        comparisonAway = false
        returnSampleQueued = false
        ui = ui.copy(dnsObservations = emptyList())
        run(R.string.signing_in, 300000) { op ->
            val app = getApplication<Application>()
            val attempt = op.step(Stage.PREPARE_LOGIN) {
                if (networkFacts?.internetGranted == false) throw Problem(Failure.PERMISSION, network = NetworkFailure.PERMISSION)
                repository.prepareLogin(selected, app.getString(R.string.app_name), op, forceNew)
            }
            if (compareDns) sampleDns(DnsPoint.BEFORE_BROWSER, op, generation)
            op.check()
            main.post { if (epoch == generation) ui = ui.copy(browserUrl = attempt.url, forceNew = false) }
            repository.finishLogin(attempt, op, app.getString(R.string.browser_reply), app.getString(R.string.browser_rejected),
                beforeNetwork = { loginReturn.await(op) }) {
                if (compareDns) sampleDns(DnsPoint.AFTER_CALLBACK, op, generation)
                op.check()
            }
            Result(R.string.sign_in_done)
        }
        comparisonOperation = if (compareDns) operation else null
    }

    private fun sampleDns(point: DnsPoint, op: Operation, generation: Long) {
        try {
            op.check()
            val observation = dnsComparison.sample(point) { foreground }
            op.check()
            main.post {
                if (generation == epoch) ui = ui.copy(dnsObservations =
                    (ui.dnsObservations.filterNot { it.point == point } + observation).sortedBy { it.point.ordinal })
            }
        } catch (_: CancellationException) { /* Old trials cannot update the UI. */ }
        catch (_: Problem) { /* The main authentication operation reports its own deadline. */ }
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
        ui = ui.copy(answer = "", generationDiagnostic = null)
        run(R.string.generating) { op ->
            val app = getApplication<Application>()
            Result(R.string.generation_success, answer = repository.generate(model, app.getString(R.string.persona_prompt), app.getString(R.string.dummy_prompt), op))
        }
    }

    fun disconnect() {
        ui = ui.copy(answer = "", models = emptyList(), model = null, generationDiagnostic = null)
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

    fun cancel(status: Int = R.string.cancelled) {
        operation?.generationDiagnostic?.let { ui = ui.copy(generationDiagnostic = it.copy(end = StreamEnd.CANCELLED,
            reason = GenerationReason.CANCELLED, answerInUi = false)) }
        run(R.string.cancelling) { Result(status) }
    }

    // Storage, code exchange and rotating refresh tokens share one serialized executor.
    private fun run(status: Int, timeout: Long = 180000, work: (Operation) -> Result) {
        operation?.cancel()
        val generation = ++epoch
        val op = Operation(timeout) { progress ->
            val away = !foreground && progress.stage in setOf(Stage.WAIT_CALLBACK, Stage.SAVE_REGISTRATION,
                Stage.CODE_EXCHANGE, Stage.DISCOVERY, Stage.SIGNING_KEYS, Stage.ID_TOKEN, Stage.TOKEN_FIELDS, Stage.SAVE_CREDENTIALS)
            main.post {
                if (generation == epoch) ui = ui.copy(progress = progress,
                    lastWhileAway = if (away) progress.stage else ui.lastWhileAway)
            }
        }
        operation = op
        ui = ui.copy(busy = true, status = status, failure = null, diagnostic = null, browserUrl = null,
            progress = Progress(), lastWhileAway = Stage.NONE)
        executor.execute {
            try {
                val result = work(op)
                val snapshot = if (op.progress.stage == Stage.NONE) op.step(Stage.READ_SAVED_STATE) { repository.snapshot() }
                    else try { repository.snapshot() } catch (e: Exception) { op.step(Stage.READ_SAVED_STATE) { throw e } }
                op.check()
                main.post {
                    if (generation == epoch) {
                        val models = if (!snapshot.connected) emptyList() else result.models ?: ui.models
                        ui = ui.copy(busy = false, connected = snapshot.connected, permitted = snapshot.permitted,
                            name = snapshot.name, email = snapshot.email, accounts = snapshot.accounts,
                            models = models, model = ui.model?.takeIf { selected -> models.any { it.slug == selected } } ?: models.firstOrNull()?.slug,
                            answer = result.answer ?: ui.answer, status = result.status.takeIf { it != 0 } ?: if (snapshot.connected) R.string.connected else R.string.disconnected,
                            welcome = snapshot.welcome, browserUrl = null, savedConnection = savedConnection(snapshot.connected),
                            progress = op.progress, diagnostic = op.lastDiagnostic, failure = op.lastDiagnostic?.failure,
                            generationDiagnostic = op.generationDiagnostic?.let {
                                if (!result.answer.isNullOrBlank()) it.copy(step = GenerationStep.UI, answerInUi = true) else it
                            } ?: ui.generationDiagnostic)
                    }
                }
            } catch (e: Exception) {
                val diagnostic = if (e is CancellationException) null else diagnosticOf(e)
                val failure = diagnostic?.failure
                if (failure == Failure.EXPIRED) runCatching { op.commit { repository.clearExpired() } }
                val snapshot = runCatching { repository.snapshot() }.getOrNull()
                main.post {
                    if (generation == epoch) {
                        ui = ui.copy(busy = false, failure = failure, status = if (failure == null) R.string.cancelled else R.string.operation_failed,
                            connected = snapshot?.connected ?: false, permitted = snapshot?.permitted ?: false,
                            name = snapshot?.name.orEmpty(), email = snapshot?.email.orEmpty(), accounts = snapshot?.accounts ?: ui.accounts,
                            welcome = snapshot?.welcome ?: false, browserUrl = null,
                            models = if (snapshot?.connected == true) ui.models else emptyList(), model = if (snapshot?.connected == true) ui.model else null,
                            diagnostic = diagnostic, progress = op.progress, savedConnection = savedConnection(snapshot?.connected),
                            generationDiagnostic = op.generationDiagnostic?.let {
                                if (it.completionValidated) it.copy(end = StreamEnd.RESULT_WITHHELD, reason = GenerationReason.RESULT_NOT_DISPLAYED) else it
                            } ?: ui.generationDiagnostic)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        ++epoch
        operation?.cancel()
        dnsComparison.close()
        comparisonCoordinator.shutdownNow()
        executor.shutdownNow()
    }
}
