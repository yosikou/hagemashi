package com.yosikou.hagemashi

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

// The pointer is the sole commit point. Staging failures cannot mutate the active generation.
internal class GenerationStore(private val root:File) {
    private val pointer=File(root,"data-generation.v1")
    private val generations=File(root,"data-generations")
    fun active():File {
        if(!pointer.exists())return root
        val id=pointer.readText(Charsets.US_ASCII);require(isAnswerId(id))
        return File(generations,id).also{require(it.isDirectory)}
    }
    fun stage():File=File(generations,UUID.randomUUID().toString()).also{check(it.mkdirs())}
    fun publish(stage:File,beforeMove:()->Unit={}) {
        require(stage.parentFile.canonicalFile==generations.canonicalFile && isAnswerId(stage.name) && stage.isDirectory)
        val next=File(root,"data-generation.next")
        FileOutputStream(next).use{it.write(stage.name.toByteArray(Charsets.US_ASCII));it.fd.sync()}
        beforeMove() // Test fault point; no data is visible before the atomic move.
        Files.move(next.toPath(),pointer.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    fun prune() {
        val current=active().canonicalFile
        generations.listFiles().orEmpty().filter{it.isDirectory && it.canonicalFile!=current}.forEach{it.deleteRecursively()}
    }
    fun transaction(prepare:(File,File)->Unit,verify:(File)->Unit,beforeMove:()->Unit={}):File {
        val previous=active();val staged=stage()
        try {prepare(previous,staged);verify(staged);publish(staged,beforeMove);return staged}
        catch(e:Exception){if(runCatching{active().canonicalFile==staged.canonicalFile}.getOrDefault(false))return staged
            staged.deleteRecursively();throw e}
    }
}
