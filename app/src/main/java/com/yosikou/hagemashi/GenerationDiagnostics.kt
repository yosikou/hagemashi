package com.yosikou.hagemashi

import java.util.Locale
import java.util.concurrent.CancellationException

internal enum class StreamMedia { NOT_RECEIVED, EVENT_STREAM, JSON, HTML, OTHER, MISSING }
internal fun streamMedia(value: String?): StreamMedia = when (value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
    null, "" -> StreamMedia.MISSING
    "text/event-stream" -> StreamMedia.EVENT_STREAM
    "application/json" -> StreamMedia.JSON
    "text/html" -> StreamMedia.HTML
    else -> StreamMedia.OTHER
}
internal enum class BodyPresence { UNKNOWN, PRESENT, ABSENT }
internal enum class GenerationStep { REQUEST, CONTENT_TYPE, EVENTS, COMPLETION, UI }
internal enum class StreamTerminal { NONE, COMPLETED, INCOMPLETE, FAILED, ERROR }
internal enum class StreamEnd { NOT_STARTED, READING, COMPLETED, INCOMPLETE, FAILED, ERROR, EOF_WITHOUT_COMPLETION,
    EOF_IN_EVENT, IO_INTERRUPTED, TIMED_OUT, CANCELLED, FORMAT_REJECTED, MEDIA_REJECTED, LIMIT_EXCEEDED, HTTP_REJECTED, RESULT_WITHHELD }
internal enum class GenerationReason { NONE, CONTENT_TYPE_REJECTED, EMPTY_BODY, NO_COMPLETION, UNTERMINATED_EVENT,
    INCOMPLETE, FAILED, SERVER_ERROR, INVALID_COMPLETION, EMPTY_ANSWER, REFUSAL, INVALID_JSON, INVALID_EVENT_DATA,
    INPUT_LIMIT, TRANSPORT_ERROR, TIMEOUT, CANCELLED, HTTP_REJECTED, RESULT_NOT_DISPLAYED }
internal enum class KnownStreamEvent(val wireName: String) {
    NONE("NONE"), CREATED("response.created"), QUEUED("response.queued"), IN_PROGRESS("response.in_progress"),
    ITEM_ADDED("response.output_item.added"), ITEM_DONE("response.output_item.done"),
    PART_ADDED("response.content_part.added"), PART_DONE("response.content_part.done"),
    TEXT_DELTA("response.output_text.delta"), TEXT_DONE("response.output_text.done"),
    REFUSAL_DELTA("response.refusal.delta"), REFUSAL_DONE("response.refusal.done"),
    SUMMARY_DELTA("response.reasoning_summary_text.delta"), SUMMARY_DONE("response.reasoning_summary_text.done"),
    COMPLETED("response.completed"), INCOMPLETE("response.incomplete"), FAILED("response.failed"), ERROR("error")
}

// This immutable allowlist is the entire generation diagnostic. Never add request/response strings here.
internal data class GenerationDiagnostic(
    val step: GenerationStep = GenerationStep.REQUEST, val httpStatus: Int? = null,
    val media: StreamMedia = StreamMedia.NOT_RECEIVED, val body: BodyPresence = BodyPresence.UNKNOWN,
    val lastKnown: KnownStreamEvent = KnownStreamEvent.NONE, val unknownEventSeen: Boolean = false,
    val terminal: StreamTerminal = StreamTerminal.NONE, val end: StreamEnd = StreamEnd.NOT_STARTED,
    val reason: GenerationReason = GenerationReason.NONE, val answerPresent: Boolean = false,
    val completionValidated: Boolean = false, val answerInUi: Boolean = false
)

internal class GenerationTrace(private val observe: (GenerationDiagnostic) -> Unit = {}) {
    var value = GenerationDiagnostic()
        private set
    init { observe(value) }
    fun update(change: (GenerationDiagnostic) -> GenerationDiagnostic) { value = change(value); observe(value) }
    fun reject(reason: GenerationReason, end: StreamEnd = StreamEnd.FORMAT_REJECTED): Nothing {
        update { it.copy(reason = reason, end = end) }
        throw Problem(Failure.PROTOCOL)
    }
    fun interrupted(error: Exception) {
        if (value.reason != GenerationReason.NONE) return
        val timeout = error !is CancellationException && safeProblem(error).kind == Failure.TIMEOUT
        val protocol = error !is CancellationException && safeProblem(error).kind == Failure.PROTOCOL
        update { it.copy(reason = if (error is CancellationException) GenerationReason.CANCELLED else if (timeout) GenerationReason.TIMEOUT else GenerationReason.TRANSPORT_ERROR,
            end = if (error is CancellationException) StreamEnd.CANCELLED else if (timeout) StreamEnd.TIMED_OUT else StreamEnd.IO_INTERRUPTED) }
        if (protocol) update { it.copy(reason = GenerationReason.INVALID_EVENT_DATA, end = StreamEnd.FORMAT_REJECTED) }
    }
}

internal fun generationReport(build: String, applicationId: String, variant: String, state: GenerationDiagnostic): String {
    fun identifier(value: String) = value.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,150}")) } ?: "UNKNOWN"
    return buildString {
        appendLine("schema=HAGEMASHI_GENERATION_V1")
        appendLine("build=${identifier(build)}")
        appendLine("applicationId=${identifier(applicationId)}")
        appendLine("variant=${identifier(variant)}")
        appendLine("host=${TargetHost.API.hostname}")
        appendLine("step=${state.step.name}")
        appendLine("http=${state.httpStatus?.takeIf { it in 100..599 }?.toString() ?: "NOT_RECEIVED"}")
        appendLine("content_type=${state.media.name}")
        appendLine("body=${state.body.name}")
        appendLine("last_known_event=${state.lastKnown.wireName}")
        appendLine("unknown_event_seen=${state.unknownEventSeen}")
        appendLine("terminal=${state.terminal.name}")
        appendLine("end=${state.end.name}")
        appendLine("reason=${state.reason.name}")
        appendLine("answer_present=${state.answerPresent}")
        appendLine("completion_validated=${state.completionValidated}")
        appendLine("answer_in_ui=${state.answerInUi}")
    }
}
