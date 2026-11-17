package com.yosikou.hagemashi

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

// Only UMP owns ad privacy choices. Never read OpenAI consent, app settings or health records.
internal class AdvertisingPrivacyController(private val activity:Activity?) {
    private val live=BuildConfig.AD_MODE=="production"
    private val info=if(live && activity!=null)runCatching{UserMessagingPlatform.getConsentInformation(activity.applicationContext)}.getOrNull() else null
    var allowed by mutableStateOf(!live);private set
    var busy by mutableStateOf(false);private set
    var failed by mutableStateOf(false);private set
    var optionsRequired by mutableStateOf(false);private set
    private var closed=false
    private var updated=false
    fun requestAllowed():Boolean = !closed && allowed && (!live || info?.canRequestAds()==true)
    fun update() {
        if(!live || closed || busy)return
        allowed=false;failed=false;busy=true;updated=false
        val owner=activity;val consent=info
        if(owner==null || consent==null){finish(false);return}
        try {
            consent.requestConsentInfoUpdate(owner,ConsentRequestParameters.Builder().build(),{
                if(!closed)try {
                    updated=true
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(owner){error->finish(error==null)}
                }catch(_:Exception){finish(false)}
            },{finish(false)})
        }catch(_:Exception){finish(false)}
    }
    fun options() {
        if(closed || busy || !optionsRequired || activity==null)return
        allowed=false;busy=true;failed=false
        try {UserMessagingPlatform.showPrivacyOptionsForm(activity){error->finish(error==null)}}catch(_:Exception){finish(false)}
    }
    private fun finish(success:Boolean) {
        if(closed)return
        busy=false;failed=!success
        optionsRequired=updated && info?.privacyOptionsRequirementStatus==ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        allowed=success && updated && info?.canRequestAds()==true
    }
    fun close(){closed=true;allowed=false}
}
internal val LocalAdvertisingPrivacy=staticCompositionLocalOf { AdvertisingPrivacyController(null) }
private fun Context.activity():Activity? {
    var candidate:Context=this
    while(candidate is ContextWrapper){if(candidate is Activity)return candidate;candidate=candidate.baseContext}
    return candidate as? Activity
}
@Composable internal fun AdvertisingPrivacyHost(content:@Composable ()->Unit) {
    val activity=LocalContext.current.activity()
    val controller=remember(activity){AdvertisingPrivacyController(activity)}
    LaunchedEffect(controller){controller.update()}
    DisposableEffect(controller){onDispose{controller.close()}}
    CompositionLocalProvider(LocalAdvertisingPrivacy provides controller,content=content)
}
@Composable internal fun AdvertisingPrivacyOptions() {
    if(BuildConfig.AD_MODE!="production")return
    val controller=LocalAdvertisingPrivacy.current
    if(controller.optionsRequired)OutlinedButton(onClick=controller::options,enabled=!controller.busy){Text(stringResource(R.string.ads_privacy_options))}
    if(controller.failed) {
        Text(stringResource(R.string.ads_privacy_unavailable))
        OutlinedButton(onClick=controller::update,enabled=!controller.busy){Text(stringResource(R.string.ads_privacy_retry))}
    }
}
