package com.yosikou.hagemashi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.*
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun BackgroundHealthPermissionControl() {
    val context=LocalContext.current
    var refresh by remember{mutableIntStateOf(0)}
    var available by remember{mutableStateOf(false)}
    var granted by remember{mutableStateOf(false)}
    val request=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){refresh++}
    LaunchedEffect(refresh){withContext(Dispatchers.IO){runCatching{
        check(android.os.Build.VERSION.SDK_INT>=28)
        val client=HealthConnectClient.getOrCreate(context)
        val supported=client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)==HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        supported to (supported && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in client.permissionController.getGrantedPermissions())
    }.getOrDefault(false to false)}.let{available=it.first;granted=it.second}}
    Text(stringResource(if(!available)R.string.background_health_unsupported else if(granted)R.string.background_health_granted else R.string.background_health_missing))
    if(available && !granted)OutlinedButton(onClick={runCatching{request.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND))}.onFailure{refresh++}}){Text(stringResource(R.string.background_health_request))}
}
