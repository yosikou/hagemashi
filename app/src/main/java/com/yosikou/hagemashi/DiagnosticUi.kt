package com.yosikou.hagemashi

internal fun diagnosticUi():Boolean = BuildConfig.BUILD_TYPE == "diagnostic"
internal fun userFailureText(failure:Failure):Int = when(failure) {
    Failure.NETWORK,Failure.TIMEOUT,Failure.SERVER -> R.string.user_network_error
    Failure.EXPIRED,Failure.PERMISSION,Failure.DENIED,Failure.IDENTITY -> R.string.user_connection_error
    Failure.POLICY -> R.string.agent_unavailable
    Failure.LIMIT -> R.string.user_limit_error
    Failure.STORAGE -> R.string.user_storage_error
    Failure.PROTOCOL -> R.string.user_answer_error
}
