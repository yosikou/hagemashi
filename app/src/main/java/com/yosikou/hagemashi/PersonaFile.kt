package com.yosikou.hagemashi

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

internal const val PERSONA_FILE_MAX_BYTES = 65536
internal const val PERSONA_NAME_MAX_POINTS = 120
internal const val PERSONA_PROMPT_MAX_POINTS = 12000
internal data class SharedPersona(val name:String,val prompt:String)
internal enum class PersonaFileError { INVALID_JSON, UNSUPPORTED_VERSION, MISSING_FIELDS, INVALID_FIELDS, TOO_LARGE, READ_FAILED, WRITE_FAILED, SAVE_FAILED }
internal class PersonaFileProblem(val reason:PersonaFileError):Exception()
private fun fileProblem(reason:PersonaFileError):Nothing = throw PersonaFileProblem(reason)
private fun validateSharedPersona(value:SharedPersona) {
    for(text in listOf(value.name,value.prompt)) {
        var i=0
        while(i<text.length) {
            val c=text[i++]
            if(Character.isHighSurrogate(c)){if(i>=text.length || !Character.isLowSurrogate(text[i++]))fileProblem(PersonaFileError.INVALID_FIELDS)}
            else if(Character.isLowSurrogate(c))fileProblem(PersonaFileError.INVALID_FIELDS)
        }
    }
    if(value.name.isBlank() || value.prompt.isBlank())fileProblem(PersonaFileError.INVALID_FIELDS)
    if(value.name.codePointCount(0,value.name.length)>PERSONA_NAME_MAX_POINTS || value.prompt.codePointCount(0,value.prompt.length)>PERSONA_PROMPT_MAX_POINTS)
        fileProblem(PersonaFileError.TOO_LARGE)
}
// This allowlist is the entire file format. No ID, metadata or application settings are serialized.
internal fun encodePersonaFile(persona:SharedPersona):ByteArray {
    validateSharedPersona(persona)
    val bytes=JSONObject().put("formatVersion",1).put("name",persona.name).put("prompt",persona.prompt).toString(2).toByteArray(Charsets.UTF_8)
    if(bytes.size>PERSONA_FILE_MAX_BYTES)fileProblem(PersonaFileError.TOO_LARGE)
    return bytes
}
internal fun readPersonaFile(input:InputStream):SharedPersona {
    val bytes=ByteArrayOutputStream()
    val buffer=ByteArray(4096)
    while(true) {
        val count=input.read(buffer,0,minOf(buffer.size,PERSONA_FILE_MAX_BYTES+1-bytes.size()))
        if(count<0)break
        if(count==0) { // Handle unusual providers without spinning indefinitely.
            val next=input.read();if(next<0)break;bytes.write(next)
        } else bytes.write(buffer,0,count)
        if(bytes.size()>PERSONA_FILE_MAX_BYTES)fileProblem(PersonaFileError.TOO_LARGE)
    }
    return decodePersonaFile(bytes.toByteArray())
}
internal fun decodePersonaFile(bytes:ByteArray):SharedPersona {
    if(bytes.size>PERSONA_FILE_MAX_BYTES)fileProblem(PersonaFileError.TOO_LARGE)
    val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }catch(_:Exception){fileProblem(PersonaFileError.INVALID_JSON)}
    val value=StrictPersonaJson(text).read() as? Map<*,*> ?: fileProblem(PersonaFileError.INVALID_FIELDS)
    val keys=setOf("formatVersion","name","prompt")
    if(!value.keys.containsAll(keys))fileProblem(PersonaFileError.MISSING_FIELDS)
    if(value.keys!=keys)fileProblem(PersonaFileError.INVALID_FIELDS)
    val version=value["formatVersion"] as? JsonNumber ?: fileProblem(PersonaFileError.INVALID_FIELDS)
    if(!version.text.matches(Regex("-?(?:0|[1-9][0-9]*)")))fileProblem(PersonaFileError.INVALID_FIELDS)
    if(version.text!="1")fileProblem(PersonaFileError.UNSUPPORTED_VERSION)
    val persona=SharedPersona(value["name"] as? String ?: fileProblem(PersonaFileError.INVALID_FIELDS),
        value["prompt"] as? String ?: fileProblem(PersonaFileError.INVALID_FIELDS))
    validateSharedPersona(persona)
    return persona
}
internal fun appendImportedPersona(settings:AppSettings,persona:Persona):AppSettings {
    require(persona.id !in builtInPersonaIds && isAnswerId(persona.id) && persona.defaultContent==false)
    validateSharedPersona(SharedPersona(persona.name,persona.prompt))
    // The preview owns a fresh ID. Reusing that same preview after an uncertain save is idempotent.
    val existing=settings.personas.find{it.id==persona.id}
    if(existing!=null) { require(existing==persona);return settings }
    return settings.copy(personas=settings.personas+persona)
}
private data class JsonNumber(val text:String)
// Android's JSONTokener accepts comments, single quotes and trailing commas. Shared files must be
// strict JSON, so validate the bounded input independently of platform-specific org.json leniency.
private class StrictPersonaJson(private val source:String) {
    private var at=0
    private fun invalid():Nothing=fileProblem(PersonaFileError.INVALID_JSON)
    private fun space(){while(at<source.length && source[at] in " \t\r\n")at++}
    private fun take(c:Char):Boolean { space();return if(at<source.length && source[at]==c){at++;true}else false }
    fun read():Any? { val value=value(0);space();if(at!=source.length)invalid();return value }
    private fun value(depth:Int):Any? {
        if(depth>16)invalid();space();if(at>=source.length)invalid()
        return when(source[at]) {
            '"'->string()
            '{'->{
                at++;val map=linkedMapOf<String,Any?>()
                if(!take('}'))while(true) {
                    space();if(at>=source.length || source[at]!='"')invalid()
                    val key=string();if(map.containsKey(key) || !take(':'))invalid()
                    map[key]=value(depth+1)
                    if(take('}'))break
                    if(!take(','))invalid()
                };map
            }
            '['->{at++;val list=mutableListOf<Any?>();if(!take(']'))while(true){list+=value(depth+1);if(take(']'))break;if(!take(','))invalid()};list}
            't'->{literal("true");true};'f'->{literal("false");false};'n'->{literal("null");null}
            else->{val start=at;while(at<source.length && source[at] in "-+0123456789.eE")at++
                val number=source.substring(start,at)
                if(!number.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))invalid()
                JsonNumber(number)}
        }
    }
    private fun literal(text:String){if(!source.startsWith(text,at))invalid();at+=text.length}
    private fun string():String {
        if(source[at++]!='"')invalid()
        val result=StringBuilder()
        while(at<source.length) {
            val c=source[at++]
            if(c=='"') {
                val text=result.toString();var i=0
                while(i<text.length) {
                    val char=text[i++]
                    if(Character.isHighSurrogate(char)){if(i>=text.length || !Character.isLowSurrogate(text[i++]))invalid()}
                    else if(Character.isLowSurrogate(char))invalid()
                }
                return text
            }
            if(c.code<32)invalid()
            if(c!='\\'){result.append(c);continue}
            if(at>=source.length)invalid()
            when(val escaped=source[at++]) {
                '"','\\','/'->result.append(escaped)
                'b'->result.append('\b');'f'->result.append('\u000C');'n'->result.append('\n');'r'->result.append('\r');'t'->result.append('\t')
                'u'->{if(at+4>source.length)invalid();val hex=source.substring(at,at+4);if(!hex.all{it in "0123456789abcdefABCDEF"})invalid();result.append(hex.toInt(16).toChar());at+=4}
                else->invalid()
            }
        }
        invalid()
    }
}
