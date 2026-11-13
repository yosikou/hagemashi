package com.yosikou.hagemashi

import android.content.Context
import android.content.ContextWrapper
import java.io.File

internal class GenerationContext(base:Context,private val directory:File):ContextWrapper(base) {
    override fun getNoBackupFilesDir()=directory
}
internal object AppDataRoot {
    val lock=Any()
    fun <T> access(context:Context,block:(Context)->T):T=synchronized(lock) {
        if(context is GenerationContext)block(context)
        else block(GenerationContext(context,GenerationStore(context.noBackupFilesDir).active()))
    }
}
