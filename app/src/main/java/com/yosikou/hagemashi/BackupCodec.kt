package com.yosikou.hagemashi

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal const val BACKUP_MAX_BYTES=16*1024*1024
internal const val BACKUP_MAX_ANSWERS=5000
internal enum class BackupError { PASSPHRASE, AUTH_OR_DAMAGE, VERSION, FORMAT, TOO_LARGE, READ, WRITE, SAVE }
internal class BackupProblem(val reason:BackupError):Exception()
internal data class BackupContents(val settings:AppSettings,val answers:List<SavedEncouragement>,val includesHistory:Boolean)
private val portableKeys=setOf("goal","goalDate","maintaining","language","weightUnit","modelId","personas","personaId","supplement","morningEnabled","morningTime","eveningEnabled","eveningTime","automaticGeneration")
internal fun portableSettings(settings:AppSettings):JSONObject {
    val cfg=if(settings.resumeRequired)settings.copy(morning=settings.morning.copy(enabled=settings.resumeMorning),evening=settings.evening.copy(enabled=settings.resumeEvening),automaticGeneration=settings.resumeAutomatic) else settings
    val json=encodeSettings(cfg)
    return JSONObject().also{out->portableKeys.forEach{out.put(it,json.get(it))}}
}
internal fun restoredSettings(source:AppSettings,old:AppSettings):AppSettings {
    val counter=maxOf(old.reservationVersion,old.morningVersion,old.eveningVersion)
    require(counter<Long.MAX_VALUE-4)
    // New local epochs prevent old OS jobs and imported automatic-answer IDs from being reused.
    val epoch=maxOf(counter+1,(java.util.UUID.randomUUID().leastSignificantBits ushr 1).coerceAtMost(Long.MAX_VALUE-4))
    return source.copy(morning=source.morning.copy(enabled=false),evening=source.evening.copy(enabled=false),automaticGeneration=false,testAnswerId=null,
        reservationVersion=epoch,morningVersion=epoch,eveningVersion=epoch,goalCycle=Math.addExact(old.goalCycle,1),
        resumeRequired=true,resumeMorning=source.morning.enabled,resumeEvening=source.evening.enabled,resumeAutomatic=source.automaticGeneration)
}
internal fun resumeRestoredSettings(cfg:AppSettings):AppSettings=cfg.copy(resumeRequired=false,
    morning=cfg.morning.copy(enabled=cfg.resumeMorning),evening=cfg.evening.copy(enabled=cfg.resumeEvening),automaticGeneration=cfg.resumeAutomatic,
    resumeMorning=false,resumeEvening=false,resumeAutomatic=false).also{require(it.validTimes())}
internal fun newBackupAnswers(existingIds:Set<String>,incoming:List<SavedEncouragement>):List<SavedEncouragement> =
    incoming.distinctBy{it.id}.filterNot{it.id in existingIds}
internal fun encodeBackup(contents:BackupContents):ByteArray {
    if(contents.answers.size>BACKUP_MAX_ANSWERS || (!contents.includesHistory && contents.answers.isNotEmpty()))throw BackupProblem(BackupError.TOO_LARGE)
    var size=0
    val history=JSONArray()
    contents.answers.forEach { answer -> val json=encodeEncouragement(answer);size+=json.toString().toByteArray(Charsets.UTF_8).size
        if(size>BACKUP_MAX_BYTES)throw BackupProblem(BackupError.TOO_LARGE);history.put(json) }
    val bytes=JSONObject().put("formatVersion",1).put("settings",portableSettings(contents.settings)).put("historyIncluded",contents.includesHistory).put("history",history).toString().toByteArray(Charsets.UTF_8)
    if(bytes.size>BACKUP_MAX_BYTES-56)throw BackupProblem(BackupError.TOO_LARGE)
    return bytes
}
internal fun decodeBackup(bytes:ByteArray):BackupContents {
    if(bytes.size>BACKUP_MAX_BYTES)throw BackupProblem(BackupError.TOO_LARGE)
    try {
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val json=JSONObject(text)
        if(json.optInt("formatVersion",-1)!=1)throw BackupProblem(BackupError.VERSION)
        require(json.keys().asSequence().toSet()==setOf("formatVersion","settings","historyIncluded","history"))
        val cfg=json.getJSONObject("settings");require(cfg.keys().asSequence().toSet()==portableKeys)
        require(cfg.get("language") is String && cfg.get("weightUnit") is String)
        AppLanguage.valueOf(cfg.getString("language"));WeightUnit.valueOf(cfg.getString("weightUnit"))
        val full=JSONObject(cfg.toString()).put("schema",2)
        val settings=decodeSettings(full,emptyList())
        val array=json.getJSONArray("history");if(array.length()>BACKUP_MAX_ANSWERS)throw BackupProblem(BackupError.TOO_LARGE)
        val answers=(0 until array.length()).map{array.getJSONObject(it).let{value->decodeEncouragement(value.getString("id"),value)}}
        require(answers.map{it.id}.distinct().size==answers.size)
        val included=json.getBoolean("historyIncluded");require(included || answers.isEmpty())
        return BackupContents(settings,answers,included)
    }catch(e:BackupProblem){throw e}catch(_:Exception){throw BackupProblem(BackupError.FORMAT)}
}
internal object BackupCrypto {
    private val magic="HGMBACK!".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS=600000
    fun validPassphrase(value:CharArray):Boolean = Character.codePointCount(value,0,value.size) in 12..256
    private fun key(passphrase:CharArray,salt:ByteArray):ByteArray {
        val spec=PBEKeySpec(passphrase,salt,ITERATIONS,256)
        return try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}finally{spec.clearPassword()}
    }
    fun encrypt(plain:ByteArray,passphrase:CharArray,random:SecureRandom=SecureRandom()):ByteArray {
        if(!validPassphrase(passphrase))throw BackupProblem(BackupError.PASSPHRASE)
        if(plain.size>BACKUP_MAX_BYTES-56)throw BackupProblem(BackupError.TOO_LARGE)
        val salt=ByteArray(16).also(random::nextBytes);val nonce=ByteArray(12).also(random::nextBytes)
        val header=ByteBuffer.allocate(40).put(magic).putInt(1).put(salt).put(nonce).array()
        val derived=key(passphrase,salt)
        return try {val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(derived,"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(header);header+cipher.doFinal(plain)}
        finally{derived.fill(0)}
    }
    fun decrypt(bytes:ByteArray,passphrase:CharArray):ByteArray {
        if(bytes.size>BACKUP_MAX_BYTES)throw BackupProblem(BackupError.TOO_LARGE)
        if(bytes.size<56 || !bytes.copyOfRange(0,8).contentEquals(magic))throw BackupProblem(BackupError.FORMAT)
        val buffer=ByteBuffer.wrap(bytes);(buffer as java.nio.Buffer).position(8)
        if(buffer.int!=1)throw BackupProblem(BackupError.VERSION)
        if(!validPassphrase(passphrase))throw BackupProblem(BackupError.PASSPHRASE)
        val salt=ByteArray(16).also(buffer::get);val nonce=ByteArray(12).also(buffer::get)
        val derived=key(passphrase,salt)
        return try {val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(derived,"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(bytes,0,40);cipher.doFinal(bytes,40,bytes.size-40)}
        catch(_:Exception){throw BackupProblem(BackupError.AUTH_OR_DAMAGE)}finally{derived.fill(0)}
    }
}
internal fun readBackupBytes(input:InputStream):ByteArray {
    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val n=input.read(buffer,0,minOf(buffer.size,BACKUP_MAX_BYTES+1-output.size()));if(n<0)break
        if(n==0){val value=input.read();if(value<0)break;output.write(value)}else output.write(buffer,0,n)
        if(output.size()>BACKUP_MAX_BYTES)throw BackupProblem(BackupError.TOO_LARGE)}
    return output.toByteArray()
}
