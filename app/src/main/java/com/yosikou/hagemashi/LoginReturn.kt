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

internal fun loginReplyPage(message: String): String {
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
    // Static close-browser instructions only. Callback data never enters the page.
    return "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>" +
        "<p>${escape(message)}</p></body></html>"
}
