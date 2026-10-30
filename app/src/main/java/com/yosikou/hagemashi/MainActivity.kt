package com.yosikou.hagemashi

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[ConnectionViewModel::class.java]
        setContent {
            val url = model.ui.browserUrl
            LaunchedEffect(url) {
                if (url != null) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                        model.browserOpened()
                    } catch (_: Exception) { model.browserFailed() }
                }
            }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConnectionScreen(model) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionScreen(model: ConnectionViewModel, openUsage: () -> Unit) {
    val ui = model.ui
    var confirmErase by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.screen_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.preview_notice))
        Text(stringResource(if (ui.connected) R.string.connected else R.string.disconnected), style = MaterialTheme.typography.titleMedium)
        if (ui.name.isNotEmpty()) Text(stringResource(R.string.account_name, ui.name))
        if (ui.email.isNotEmpty()) Text(stringResource(R.string.account_email, ui.email))
        if (ui.connected) Text(stringResource(if (ui.permitted) R.string.using_plan else R.string.no_permission))
        if (!ui.connected && ui.accounts.isNotEmpty()) {
            Text(stringResource(R.string.saved_accounts))
            ui.accounts.forEach { account ->
                OutlinedButton(onClick = { model.selectAccount(account.id) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (ui.selectedAccount == account.id) R.string.selected_option else R.string.option, account.label))
                }
            }
            OutlinedButton(onClick = { model.selectAccount(null) }, enabled = !ui.busy) { Text(stringResource(R.string.new_account)) }
        }
        Button(onClick = model::signIn, enabled = !ui.busy && !ui.connected && ui.failure != Failure.STORAGE) { Text(stringResource(R.string.continue_chatgpt)) }
        OutlinedButton(onClick = model::disconnect, enabled = ui.connected) { Text(stringResource(R.string.disconnect)) }
        if (ui.busy) OutlinedButton(onClick = { model.cancel() }) { Text(stringResource(R.string.cancel)) }
        Text(stringResource(ui.status))
        ui.failure?.let { Text(stringResource(failureText(it)), color = MaterialTheme.colorScheme.error) }
        if (ui.diagnostic.isNotEmpty()) Text(ui.diagnostic)
        TextButton(onClick = openUsage) { Text(stringResource(R.string.manage_usage)) }
        if (ui.failure == Failure.STORAGE) OutlinedButton(onClick = { confirmErase = true }, enabled = !ui.busy) { Text(stringResource(R.string.erase_storage)) }
        HorizontalDivider()
        Text(stringResource(R.string.dummy_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dummy_prompt))
        Text(stringResource(R.string.persona_prompt))
        OutlinedButton(onClick = model::loadModels, enabled = !ui.busy && ui.permitted) { Text(stringResource(R.string.fetch_models)) }
        ui.models.forEach { item ->
            TextButton(onClick = { model.selectModel(item.slug) }, enabled = !ui.busy) {
                Text(stringResource(if (ui.model == item.slug) R.string.selected_option else R.string.option, item.name))
            }
        }
        Button(onClick = model::generate, enabled = !ui.busy && ui.permitted && ui.model != null) { Text(stringResource(R.string.generate)) }
        if (ui.answer.isNotEmpty()) {
            Text(stringResource(R.string.answer_title), style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(ui.answer) }
        }
    }
    if (ui.welcome && !ui.busy) AlertDialog(onDismissRequest = model::dismissWelcome,
        title = { Text(stringResource(R.string.plan_welcome_title)) }, text = { Text(stringResource(R.string.plan_welcome)) },
        confirmButton = { TextButton(onClick = model::dismissWelcome) { Text(stringResource(R.string.understood)) } })
    if (confirmErase) AlertDialog(onDismissRequest = { confirmErase = false },
        title = { Text(stringResource(R.string.erase_storage)) }, text = { Text(stringResource(R.string.erase_warning)) },
        confirmButton = { TextButton(onClick = { confirmErase = false; model.eraseUnrecoverable() }) { Text(stringResource(R.string.erase)) } },
        dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } })
}

private fun failureText(failure: Failure): Int = when (failure) {
    Failure.NETWORK -> R.string.error_network
    Failure.PROTOCOL -> R.string.error_protocol
    Failure.IDENTITY -> R.string.error_identity
    Failure.STORAGE -> R.string.error_storage
    Failure.DENIED -> R.string.error_denied
    Failure.EXPIRED -> R.string.error_expired
    Failure.PERMISSION -> R.string.error_permission
    Failure.LIMIT -> R.string.error_limit
    Failure.POLICY -> R.string.error_policy
    Failure.SERVER -> R.string.error_server
    Failure.TIMEOUT -> R.string.error_timeout
}
