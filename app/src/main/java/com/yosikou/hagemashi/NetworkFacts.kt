package com.yosikou.hagemashi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.security.NetworkSecurityPolicy

internal data class NetworkFacts(val internetGranted: Boolean, val cleartextPermitted: Boolean)

internal fun readNetworkFacts(context: Context): NetworkFacts? = runCatching {
    NetworkFacts(context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED,
        NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
}.getOrNull()
