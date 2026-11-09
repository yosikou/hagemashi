package com.yosikou.hagemashi

// A lifecycle handoff, not a retry: never spend a single-use authorization code while Chrome owns the foreground.
internal class LoginReturn {
    private val monitor = Object()
    @Volatile private var resumed = false
    fun setResumed(value: Boolean) = synchronized(monitor) {
        resumed = value
        monitor.notifyAll()
    }
    fun await(operation: Operation) {
        operation.check()
        if (resumed) return
        operation.step(Stage.WAIT_APP_RETURN) {
            synchronized(monitor) {
                while (!resumed) {
                    operation.check()
                    monitor.wait(100)
                }
            }
            operation.check()
        }
    }
}

internal fun loginReturnLink(applicationId: String): String {
    require(applicationId in setOf("com.yosikou.hagemashi", "com.yosikou.hagemashi.diagnostic"))
    return "intent://auth-return/#Intent;scheme=$applicationId;package=$applicationId;end"
}

internal fun loginReplyPage(message: String, label: String, applicationId: String): String {
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
    // Only localized static strings and the fixed applicationId. Callback data never enters this page or link.
    return "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>" +
        "<p>${escape(message)}</p><p><a href=\"${escape(loginReturnLink(applicationId))}\">${escape(label)}</a></p></body></html>"
}
