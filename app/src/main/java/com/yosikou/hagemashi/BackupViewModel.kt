package com.yosikou.hagemashi

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

internal enum class BackupStatus { IDLE, EXPORTED, RESTORED, CANCELLED }
internal class BackupViewModel(application:Application):AndroidViewModel(application) {
    var busy by mutableStateOf(false);private set
    var applying by mutableStateOf(false);private set
    var failure by mutableStateOf<BackupError?>(null);private set
    var status by mutableStateOf(BackupStatus.IDLE);private set
    var preview by mutableStateOf<BackupContents?>(null);private set
    var fileSelected by mutableStateOf(false);private set
    var needsDestination by mutableStateOf(false);private set
    private var encrypted:ByteArray?=null
    private val repository=BackupRepository(application)
    private var job:Job?=null
    private var epoch=0L
    fun rejectPassphrase(){failure=BackupError.PASSPHRASE}
    fun create(includeHistory:Boolean,password:CharArray) {
        if(busy){password.fill('\u0000');return}
        reset();busy=true;val generation=epoch
        job=viewModelScope.launch {
            try {
                val bytes=withContext(Dispatchers.IO){val plain=encodeBackup(repository.snapshot(includeHistory))
                    try{BackupCrypto.encrypt(plain,password)}finally{plain.fill(0);password.fill('\u0000')}}
                if(generation==epoch){encrypted=bytes;needsDestination=true}
            }catch(e:CancellationException){throw e}catch(e:BackupProblem){if(generation==epoch)failure=e.reason}
            catch(_:Exception){if(generation==epoch)failure=BackupError.READ}
            finally{password.fill('\u0000');if(generation==epoch)busy=false}
        }
    }
    fun destinationLaunched(){needsDestination=false}
    fun write(uri:Uri?) {
        if(uri==null){reset();status=BackupStatus.CANCELLED;return}
        val bytes=encrypted ?: run{failure=BackupError.WRITE;return}
        busy=true;val generation=epoch
        job=viewModelScope.launch {
            try {withContext(Dispatchers.IO){require(uri.scheme=="content");requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")).use{it.write(bytes);it.flush()}}
                if(generation==epoch){status=BackupStatus.EXPORTED;encrypted=null}}
            catch(e:CancellationException){throw e}catch(_:Exception){if(generation==epoch)failure=BackupError.WRITE}
            finally{if(generation==epoch)busy=false}
        }
    }
    fun read(uri:Uri?) {
        if(uri==null){reset();status=BackupStatus.CANCELLED;return}
        reset();busy=true;val generation=epoch
        job=viewModelScope.launch {
            try {val bytes=withContext(Dispatchers.IO){require(uri.scheme=="content");requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use(::readBackupBytes)}
                if(generation==epoch){encrypted=bytes;fileSelected=true}}
            catch(e:CancellationException){throw e}catch(e:BackupProblem){if(generation==epoch)failure=e.reason}
            catch(_:Exception){if(generation==epoch)failure=BackupError.READ}
            finally{if(generation==epoch)busy=false}
        }
    }
    fun decrypt(password:CharArray) {
        val bytes=encrypted ?: run{password.fill('\u0000');return}
        if(busy){password.fill('\u0000');return}
        busy=true;failure=null;val generation=epoch
        job=viewModelScope.launch {
            try {val contents=withContext(Dispatchers.IO){val plain=BackupCrypto.decrypt(bytes,password)
                    try{decodeBackup(plain)}finally{plain.fill(0)}}
                if(generation==epoch)preview=contents}
            catch(e:CancellationException){throw e}catch(e:BackupProblem){if(generation==epoch)failure=e.reason}
            catch(_:Exception){if(generation==epoch)failure=BackupError.FORMAT}
            finally{password.fill('\u0000');if(generation==epoch)busy=false}
        }
    }
    fun restore(app:AppViewModel,fault:RestoreFault=RestoreFault.NONE) {
        val contents=preview ?: return
        if(busy || app.saving)return
        busy=true;applying=true;failure=null
        // Once confirmed, this local transaction is intentionally not cancelled by navigation.
        job=viewModelScope.launch {
            try {withContext(Dispatchers.IO+NonCancellable){repository.restore(contents,fault)}
                preview=null;encrypted=null;fileSelected=false;status=BackupStatus.RESTORED;app.refresh()}
            catch(e:BackupProblem){failure=e.reason}catch(_:Exception){failure=BackupError.SAVE}
            finally{busy=false;applying=false}
        }
    }
    fun pickerFailed(write:Boolean){failure=if(write)BackupError.WRITE else BackupError.READ;needsDestination=false;busy=false}
    fun reset(){if(applying)return;++epoch;job?.cancel();encrypted?.fill(0);encrypted=null;preview=null;fileSelected=false;needsDestination=false;failure=null;status=BackupStatus.IDLE;busy=false}
    fun cancel(){if(!applying){reset();status=BackupStatus.CANCELLED}}
}
