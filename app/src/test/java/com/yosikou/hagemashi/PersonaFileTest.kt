package com.yosikou.hagemashi

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonaFileTest {
    private val shared=SharedPersona(" Énergie 友人 ","一行目\nDeuxième ligne\r\nतीसरी पंक्ति\n  \"quoted\" \\ prompt\n")
    private val defaults=listOf(Persona("friend","友人","friend",true),Persona("numbers","コーチ","numbers",true),Persona("companion","伴走","companion",true))
    private fun fail(reason:PersonaFileError,text:String) {
        val exception=assertThrows(PersonaFileProblem::class.java){decodePersonaFile(text.toByteArray(Charsets.UTF_8))}
        assertEquals(text,reason,exception.reason);assertNull(exception.message)
    }
    @Test fun exportHasExactlyThreeFieldsAndRoundTripsOriginalWhitespaceLanguagesAndNewlines() {
        val bytes=encodePersonaFile(shared)
        val json=JSONObject(String(bytes,Charsets.UTF_8))
        assertEquals(setOf("formatVersion","name","prompt"),json.keys().asSequence().toSet())
        assertEquals(1,json.getInt("formatVersion"))
        assertEquals(shared,decodePersonaFile(bytes))
        assertEquals(shared,readPersonaFile(ByteArrayInputStream(bytes)))
        assertEquals(shared,decodePersonaFile(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+bytes))
    }
    @Test fun duplicateNamesGetFreshCustomIdsAndAllOtherSettingsStayExactlyTheSame() {
        val cfg=AppSettings(goal="68.123456",personas=defaults,personaId="numbers",supplement="private supplement",modelId="private model",
            language=AppLanguage.DE,weightUnit=WeightUnit.LB,automaticGeneration=true,morningVersion=8,eveningVersion=9)
        val record=SharedPersona(defaults[0].name,shared.prompt)
        val candidate=Persona(UUID.randomUUID().toString(),record.name,record.prompt,false)
        val added=appendImportedPersona(cfg,candidate)
        assertEquals(2,added.personas.count{it.name==record.name});assertEquals("numbers",added.personaId)
        assertEquals(cfg,added.copy(personas=cfg.personas))
        assertEquals(candidate,added.personas.last());assertEquals(false,added.personas.last().defaultContent)
        assertSame(added,appendImportedPersona(added,candidate))
        val again=appendImportedPersona(added,candidate.copy(id=UUID.randomUUID().toString()))
        assertEquals(3,again.personas.count{it.name==record.name})
        val translated=translateDefaultPersonas(added,defaults,defaults.map{it.copy(name="French "+it.id,prompt="French instructions")})
        assertEquals(candidate,translated.personas.last())
        val exported=String(encodePersonaFile(SharedPersona(candidate.name,candidate.prompt)),Charsets.UTF_8)
        for(privateField in listOf("private supplement","private model",cfg.goal!!,"personaId","defaultContent","language","weightUnit","token","history"))assertFalse(privateField,exported.contains(privateField))
    }
    @Test fun parsingWithoutConfirmationAndRejectingBadFilesDoesNotChangeSettings() {
        val cfg=AppSettings(goal="68",personas=defaults,personaId="numbers",supplement="keep")
        val before=encodeSettings(cfg).toString()
        decodePersonaFile(encodePersonaFile(shared)) // Preview or cancellation never appends a persona.
        fail(PersonaFileError.INVALID_JSON,"{broken}")
        assertEquals(before,encodeSettings(cfg).toString())
    }
    @Test fun failuresDistinguishVersionMissingFieldsAndInvalidValues() {
        fail(PersonaFileError.UNSUPPORTED_VERSION,"""{"formatVersion":2,"name":"n","prompt":"p"}""")
        fail(PersonaFileError.MISSING_FIELDS,"""{"name":"n","prompt":"p"}""")
        fail(PersonaFileError.MISSING_FIELDS,"""{"formatVersion":1,"name":"n"}""")
        for(value in listOf("\"1\"","1.0","1e0","true","null"))fail(PersonaFileError.INVALID_FIELDS,"""{"formatVersion":$value,"name":"n","prompt":"p"}""")
        for(value in listOf("null","123","[]","{}","\"  \""))fail(PersonaFileError.INVALID_FIELDS,"""{"formatVersion":1,"name":"n","prompt":$value}""")
        fail(PersonaFileError.INVALID_FIELDS,"""{"formatVersion":1,"name":"n","prompt":"p","goal":68}""")
        fail(PersonaFileError.INVALID_FIELDS,"[]")
    }
    @Test fun strictJsonRejectsCommentsSingleQuotesTrailingDataDuplicateKeysAndBrokenEscapes() {
        for(text in listOf("{'formatVersion':1,'name':'n','prompt':'p'}","""{formatVersion:1,"name":"n","prompt":"p"}""",
            """{"formatVersion":1,"name":"n","prompt":"p",}""", """{"formatVersion":1,"name":"n","prompt":"p"} true""",
            """{/*comment*/"formatVersion":1,"name":"n","prompt":"p"}""",
            """{"formatVersion":01,"name":"n","prompt":"p"}""", """{"formatVersion":1,"name":"n","name":"other","prompt":"p"}""",
            """{"formatVersion":1,"name":"n","prompt":"\q"}""", """{"formatVersion":1,"name":"n","prompt":"\uD800"}""",
            """{"formatVersion":1,"name":"n","prompt":"\uDC00"}"""))fail(PersonaFileError.INVALID_JSON,text)
        fail(PersonaFileError.INVALID_JSON,"[".repeat(18)+"0"+"]".repeat(18))
        val bad=assertThrows(PersonaFileProblem::class.java){decodePersonaFile(byteArrayOf(0xc3.toByte(),0x28))}
        assertEquals(PersonaFileError.INVALID_JSON,bad.reason)
    }
    @Test fun sizeChecksUseUtf8BytesAndCodePointsBeforeAnySave() {
        val invalid=assertThrows(PersonaFileProblem::class.java){decodePersonaFile(ByteArray(PERSONA_FILE_MAX_BYTES+1){32})}
        assertEquals(PersonaFileError.TOO_LARGE,invalid.reason)
        val many=JSONObject().put("formatVersion",1).put("name","n").put("prompt","p").toString().toByteArray()
        val exact=many+ByteArray(PERSONA_FILE_MAX_BYTES-many.size){32}
        assertEquals(SharedPersona("n","p"),decodePersonaFile(exact))
        assertEquals(SharedPersona("n","p"),readPersonaFile(ByteArrayInputStream(exact)))
        for(value in listOf(SharedPersona("n".repeat(121),"p"),SharedPersona("n","p".repeat(12001)),SharedPersona("n","\u0001".repeat(12000)))) {
            val error=assertThrows(PersonaFileProblem::class.java){encodePersonaFile(value)}
            assertEquals(PersonaFileError.TOO_LARGE,error.reason)
        }
        val unicode=SharedPersona("😀".repeat(120),"नमस्ते\nこんにちは")
        assertEquals(unicode,decodePersonaFile(encodePersonaFile(unicode)))
    }
    @Test fun aStreamIsBoundedEvenWithoutLengthMetadata() {
        var consumed=0
        val input=object:InputStream(){override fun read():Int{consumed++;return 'x'.code}}
        val error=assertThrows(PersonaFileProblem::class.java){readPersonaFile(input)}
        assertEquals(PersonaFileError.TOO_LARGE,error.reason)
        assertEquals(PERSONA_FILE_MAX_BYTES+1,consumed)
    }
}
