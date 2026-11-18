package com.yosikou.hagemashi

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

internal const val PRIVACY_POLICY_URL = "https://yosikou.github.io/hagemashi/privacy.html"

@Composable internal fun PrivacyPolicyButton() {
    val context = LocalContext.current
    val locale = LocalAppLocale.current
    var failed by remember { mutableStateOf(false) }
    OutlinedButton(onClick = {
        // Only a public language fragment is supplied. Never send app/user data in a policy URL.
        val uri = Uri.parse(PRIVACY_POLICY_URL).buildUpon().fragment(locale.toLanguageTag()).build()
        failed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isFailure
    }) { Text(stringResource(R.string.consent_privacy)) }
    if (failed) Text(stringResource(R.string.privacy_open_failed))
}
