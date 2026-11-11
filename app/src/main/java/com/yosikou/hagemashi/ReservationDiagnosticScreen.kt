package com.yosikou.hagemashi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun ReservationDiagnosticScreen(back:()->Unit) {
    val context=LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf("") }
    LaunchedEffect(refresh) { report=withContext(Dispatchers.IO){ReservationDiagnostics.report(context)} }
    Page(R.string.reservation_diagnostics,back) {
        Text(stringResource(R.string.reservation_diagnostic_explanation))
        OutlinedButton(onClick={refresh++}){Text(stringResource(R.string.reservation_diagnostic_refresh))}
        OutlinedButton(onClick={context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.reservation_diagnostics),report))},enabled=report.isNotEmpty()){
            Text(stringResource(R.string.reservation_diagnostic_copy))
        }
        SelectionContainer { Text(report,style=MaterialTheme.typography.bodySmall) }
    }
}
