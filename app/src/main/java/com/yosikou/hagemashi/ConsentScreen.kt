package com.yosikou.hagemashi

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource

@Composable internal fun ConsentScreen(app:AppViewModel,privacy:()->Unit,close:()->Unit) {
    LaunchedEffect(Unit) { if(app.settings?.consentShownVersion!=DATA_CONSENT_VERSION)app.markConsentShown() }
    Page(R.string.consent_title,close) {
        SectionCard {
            Text(stringResource(R.string.consent_body))
            TextButton(onClick=privacy){Text(stringResource(R.string.consent_privacy))}
            val cfg=app.settings.takeUnless{app.failure}
            Text(stringResource(if(cfg==null)R.string.consent_unknown else if(hasDataConsent(cfg))R.string.consent_active else R.string.consent_inactive))
            if(cfg?.let(::hasDataConsent)!=true) {
                Button(onClick={app.recordConsent(true){ok->if(ok)close()}},enabled=cfg!=null && !app.saving){Text(stringResource(R.string.consent_accept))}
                OutlinedButton(onClick={app.recordConsent(false){ok->if(ok)close()}},enabled=cfg!=null && !app.saving){Text(stringResource(R.string.consent_later))}
            }else OutlinedButton(onClick={app.recordConsent(false){}},enabled=!app.saving){Text(stringResource(R.string.consent_revoke))}
            OutlinedButton(onClick={app.change{it.copy(automaticGeneration=false,resumeAutomatic=false)}},enabled=cfg!=null && !app.saving){Text(stringResource(R.string.consent_stop_auto))}
            SettingsStatus(app)
            if(app.failure)OutlinedButton(onClick=app::refresh,enabled=!app.saving){Text(stringResource(R.string.detail_retry))}
        }
    }
}
