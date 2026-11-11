package com.yosikou.hagemashi

import java.net.URI
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

internal class EncouragementText(val short: String, val full: String)
internal class SavedEncouragement(val id: String, val generatedAt: Instant, val short: String,
    val full: String, val usedData: String, val hasHealthData: Boolean, val personaName: String? = null, val rows: List<MeasurementRow> = emptyList(), val kind: GenerationKind = GenerationKind.MANUAL)
internal fun isAnswerId(value: String): Boolean = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

// Deliberately conservative: reject numeric glyphs, kanji numbers, Latin text, units and health labels.
// A rejected short never enters Notification extras; the full answer is never used as a substitute.
internal fun notificationShort(candidate: String?, fallback: String): String {
    val text = candidate?.trim().orEmpty()
    val length = text.codePointCount(0, text.length)
    val kanjiNumbers = "〇零一二三四五六七八九十百千万億兆壱弐参拾廿卅丗两兩貳參肆伍陸柒捌玖佰仟萬点點"
    val inspected = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
    val risky = Regex("[A-Za-z]|体重|体脂肪|歩数|キロ|きろ|グラム|ぐらむ|パーセント|ぱーせんと|センチ|せんち|メートル|めーとる|％|%|ぜろ|れい|いち|しち|ろく|なな|はち|きゅう|じゅう|ひゃく|びゃく|ぴゃく|せん|まん")
    val invalid = inspected.codePoints().anyMatch { cp -> Character.isDigit(cp) ||
        Character.getType(cp) in setOf(Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(), Character.OTHER_SYMBOL.toInt(), Character.MATH_SYMBOL.toInt(), Character.CURRENCY_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt()) ||
        (cp <= Char.MAX_VALUE.code && cp.toChar() in kanjiNumbers) || Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() }
    return if (length in 40..60 && !invalid && !risky.containsMatchIn(inspected)) text else fallback
}
internal fun encouragementText(answer: String, fallback: String): EncouragementText {
    val raw = answer.trim()
    if (raw.isEmpty()) throw Problem(Failure.PROTOCOL)
    val payload = if (raw.startsWith("```")) raw.substringAfter('\n', "").substringBeforeLast("```").trim() else raw
    if (!payload.startsWith("{")) return EncouragementText(fallback, raw)
    try {
        val json = JSONObject(payload)
        val full = (json.opt("full") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: throw Problem(Failure.PROTOCOL)
        return EncouragementText(notificationShort(json.opt("short") as? String, fallback), full)
    } catch (_: Exception) { throw Problem(Failure.PROTOCOL) }
}
internal fun newSavedEncouragement(text: EncouragementText, review: ReviewedGeneration, at: Instant): SavedEncouragement =
    SavedEncouragement(UUID.randomUUID().toString(), at, text.short, text.full, review.input, review.hasHealthData, review.personaName, review.rows.toList(), review.kind)
internal fun encodeEncouragement(value: SavedEncouragement): JSONObject {
    require(isAnswerId(value.id))
    return JSONObject().put("schema", 3).put("id", value.id).put("generatedAt", value.generatedAt.toString())
        .put("short", value.short).put("full", value.full).put("usedData", value.usedData).put("hasHealthData", value.hasHealthData).put("personaName", value.personaName ?: JSONObject.NULL).put("rows", encodeRows(value.rows)).put("kind", value.kind.name)
}
internal fun decodeEncouragement(id: String, json: JSONObject): SavedEncouragement {
    try {
        if (!isAnswerId(id) || json.getInt("schema") !in 1..3 || json.getString("id") != id) throw Problem(Failure.STORAGE)
        val full = json.getString("full").takeIf { it.isNotBlank() } ?: throw Problem(Failure.STORAGE)
        return SavedEncouragement(id, Instant.parse(json.getString("generatedAt")), json.optString("short", ""), full,
            json.getString("usedData"), json.getBoolean("hasHealthData"),
            json.optString("personaName").takeUnless { json.isNull("personaName") || it.isEmpty() },
            json.optJSONArray("rows")?.let(::decodeRows).orEmpty(), json.optString("kind").takeIf { it.isNotEmpty() }?.let(GenerationKind::valueOf) ?: GenerationKind.MANUAL)
    } catch (_: Exception) { throw Problem(Failure.STORAGE) }
}
internal fun answerNavigation(id: String, applicationId: String): String {
    require(isAnswerId(id))
    require(applicationId in setOf("com.yosikou.hagemashi", "com.yosikou.hagemashi.diagnostic"))
    return "$applicationId://answer/$id"
}
internal fun answerIdFromNavigation(action: String?, data: String?, applicationId: String): String? = try {
    val uri = URI(data ?: "")
    val id = uri.rawPath?.removePrefix("/").orEmpty()
    id.takeIf { action == "$applicationId.OPEN_ANSWER" && uri.scheme == applicationId && uri.rawAuthority == "answer" &&
        uri.rawQuery == null && uri.rawFragment == null && isAnswerId(id) }
} catch (_: Exception) { null }

internal fun scheduledAnswerId(day:java.time.LocalDate,slot:NoticeSlot):String =
    UUID.nameUUIDFromBytes("hagemashi:auto:$day:${slot.name}".toByteArray(Charsets.UTF_8)).toString()
internal fun answerTitle(answer:SavedEncouragement,fallback:String,legacy:String):String =
    if(answer.short.isBlank())legacy else notificationShort(answer.short,fallback)
