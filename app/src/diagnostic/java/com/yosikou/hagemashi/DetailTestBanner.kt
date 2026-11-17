package com.yosikou.hagemashi

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlin.coroutines.resume
import kotlinx.coroutines.*

private const val TEST_BANNER_UNIT = "ca-app-pub-3940256099942544/9214589741"

// No settings, answers, identifiers, health records or authentication enter this boundary.
// OpenAI consent is never read by this component and is not advertising consent.
@Composable internal fun DetailTestBanner() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val context=LocalContext.current.applicationContext
        val direction=LocalLayoutDirection.current
        val insets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom).asPaddingValues()
        val width=(maxWidth-insets.calculateLeftPadding(direction)-insets.calculateRightPadding(direction)).value.toInt()
        if(width>0) key(context,width) { TestBannerView(context,width,insets) }
    }
}

@Composable private fun TestBannerView(context:Context,width:Int,insets:PaddingValues) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var disposed by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<AdView?>(null) }
    val size=remember(context,width){runCatching{AdSize.getLargeAnchoredAdaptiveBannerAdSize(context,width)}.getOrNull()}
    LaunchedEffect(Unit) { ready=TestAdsInitialization.ready(context);if(!ready)failed=true }
    // Initialization and loading have independent bounded waits; neither delays the answer.
    LaunchedEffect(ready,loaded,failed) {
        if(ready && !loaded && !failed) { delay(45000);failed=true }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event ->
            when(event) {
                Lifecycle.Event.ON_RESUME->runCatching{view?.resume()}
                Lifecycle.Event.ON_PAUSE->runCatching{view?.pause()}
                else->Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            disposed=true
            lifecycle.removeObserver(observer)
            // AndroidView.onRelease is the single owner of destruction, including failures.
            runCatching{view?.pause()}
        }
    }
    if(ready && !failed && size!=null && size.height>0) {
        AndroidView(
            modifier=if(loaded)Modifier.fillMaxWidth().padding(insets).height(size.height.dp)
                else Modifier.fillMaxWidth().height(0.dp),
            factory={
                FrameLayout(context).also { container ->
                    try {
                        val ad=AdView(context)
                        view=ad
                        ad.visibility=View.INVISIBLE
                        ad.adUnitId=TEST_BANNER_UNIT
                        ad.setAdSize(size)
                        ad.adListener=object:AdListener() {
                            override fun onAdLoaded() { if(!disposed && !failed)loaded=true }
                            override fun onAdFailedToLoad(error:LoadAdError) { if(!disposed){loaded=false;failed=true} }
                        }
                        container.addView(ad)
                        // Deliberately empty: no keywords, content URL, custom targeting,
                        // extras, publisher ID, app text, health values or credentials.
                        ad.loadAd(AdRequest.Builder().build())
                        if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))ad.pause()
                    } catch(_:Exception) { failed=true }
                }
            },
            update={view?.visibility=if(loaded)View.VISIBLE else View.INVISIBLE},
            onReset=null,
            onRelease={container->
                val ad=view
                view=null
                ad?.adListener=object:AdListener(){}
                container.removeAllViews()
                runCatching{ad?.destroy()}
            }
        )
    }
}

private object TestAdsInitialization {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var pending:Deferred<Boolean>?=null
    suspend fun ready(context:Context):Boolean {
        val task=synchronized(this) { pending ?: scope.async {
            try {
                // A constant privacy flag, not a signal derived from user settings/health data.
                MobileAds.setRequestConfiguration(RequestConfiguration.Builder()
                    .setPublisherPrivacyPersonalizationState(RequestConfiguration.PublisherPrivacyPersonalizationState.DISABLED)
                    .build())
                withTimeoutOrNull(35000) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        MobileAds.initialize(context.applicationContext) {
                            if(continuation.isActive)continuation.resume(Unit)
                        }
                    }
                }!=null
            } catch(_:Exception) { false }
        }.also{pending=it} }
        return task.await()
    }
}
