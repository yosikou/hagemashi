package com.yosikou.hagemashi

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*

internal enum class PersonaShareStatus { IDLE, EXPORTED, IMPORTED, CANCELLED }
internal class PersonaShareViewModel(application:Application):AndroidViewModel(application) {
    var pending by mutableStateOf<Persona?>(null);private set
    var busy by mutableStateOf(false);private set
    var failure by mutableStateOf<PersonaFileError?>(null);private set
    var status by mutableStateOf(PersonaShareStatus.IDLE);private set
    private var exportBytes:ByteArray?=null
    private var job:Job?=null
    private var epoch=0L
    fun prepareExport(persona:Persona):Boolean {
        if(busy)return false
        failure=null;status=PersonaShareStatus.IDLE
        return try { exportBytes=encodePersonaFile(SharedPersona(persona.name,persona.prompt));busy=true;true }
        catch(e:PersonaFileProblem){failure=e.reason;false}
    }
    fun finishExport(uri:Uri?) {
        val bytes=exportBytes;exportBytes=null
        if(uri==null){busy=false;status=PersonaShareStatus.CANCELLED;return}
        if(bytes==null){busy=false;failure=PersonaFileError.WRITE_FAILED;return}
        val generation=++epoch
        job=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){
                    val resolver=getApplication<Application>().contentResolver
                    requireNotNull(resolver.openOutputStream(uri,"wt")).use{it.write(bytes);it.flush()}
                }
                if(generation==epoch)status=PersonaShareStatus.EXPORTED
            }catch(e:CancellationException){throw e}catch(_:Exception){if(generation==epoch)failure=PersonaFileError.WRITE_FAILED}
            finally{if(generation==epoch)busy=false}
        }
    }
    fun pickerFailed(export:Boolean){busy=false;exportBytes=null;failure=if(export)PersonaFileError.WRITE_FAILED else PersonaFileError.READ_FAILED}
    fun startImport(){if(!busy){pending=null;failure=null;status=PersonaShareStatus.IDLE}}
    fun readImport(uri:Uri?) {
        if(uri==null){status=PersonaShareStatus.CANCELLED;return}
        if(busy)return
        busy=true;failure=null;val generation=++epoch
        job=viewModelScope.launch {
            try {
                val shared=withContext(Dispatchers.IO){requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use(::readPersonaFile)}
                if(generation==epoch)pending=Persona(UUID.randomUUID().toString(),shared.name,shared.prompt,false)
            }catch(e:CancellationException){throw e}catch(e:PersonaFileProblem){if(generation==epoch)failure=e.reason}
            catch(_:Exception){if(generation==epoch)failure=PersonaFileError.READ_FAILED}
            finally{if(generation==epoch)busy=false}
        }
    }
    fun cancelPreview(){++epoch;job?.cancel();pending=null;exportBytes=null;failure=null;busy=false;status=PersonaShareStatus.CANCELLED}
    fun closeScreen(){cancelPreview();status=PersonaShareStatus.IDLE}
    fun confirmImport(app:AppViewModel) {
        val candidate=pending ?: return
        if(busy || app.saving)return
        busy=true;failure=null
        app.importPersona(candidate){success->
            busy=false
            if(success){pending=null;status=PersonaShareStatus.IMPORTED}else failure=PersonaFileError.SAVE_FAILED
        }
    }
}
