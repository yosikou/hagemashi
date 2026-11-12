package com.yosikou.hagemashi

import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject

internal data class Persona(val id: String, val name: String, val prompt: String)
internal val builtInPersonaIds = setOf("friend", "numbers", "companion")
internal enum class NoticeSlot { MORNING, EVENING }
internal data class SlotSetting(val enabled: Boolean, val time: LocalTime)
internal data class AppSettings(val goal: String? = null, val goalDate: LocalDate? = null, val maintaining: Boolean = false,
    val modelId: String? = null, val personas: List<Persona>, val personaId: String = "friend", val supplement: String = "",
    val morning: SlotSetting = SlotSetting(false, LocalTime.of(9,0)), val evening: SlotSetting = SlotSetting(false, LocalTime.of(20,0)),
    val testAnswerId: String? = null, val reservationVersion: Long = 0,
    val morningVersion: Long = 0, val eveningVersion: Long = 0, val goalCycle: Long = 0) {
    fun persona(): Persona = personas.single { it.id == personaId }
    fun slot(slot: NoticeSlot): SlotSetting = if(slot == NoticeSlot.MORNING) morning else evening
    fun version(slot: NoticeSlot): Long = if(slot == NoticeSlot.MORNING) morningVersion else eveningVersion
    fun withSlot(slot: NoticeSlot, value: SlotSetting): AppSettings = if(slot == NoticeSlot.MORNING) copy(morning=value) else copy(evening=value)
    fun validTimes(): Boolean = !morning.enabled || !evening.enabled || morning.time < evening.time
    fun deletePersona(id: String): AppSettings {
        require(id !in builtInPersonaIds)
        return copy(personas = personas.filterNot { it.id == id }, personaId = if (personaId == id) "friend" else personaId)
    }
}
internal fun goalNeedsReset(cfg: AppSettings, today: LocalDate): Boolean = cfg.goal != null && !cfg.maintaining && cfg.goalDate?.isBefore(today) == true
internal fun nextSettings(old: AppSettings, changed: AppSettings, today: LocalDate = LocalDate.now()): AppSettings {
    require(changed.validTimes())
    val targetChanged = old.testAnswerId != changed.testAnswerId
    val resetChanged = goalNeedsReset(old,today) != goalNeedsReset(changed,today)
    val m = old.morning != changed.morning || targetChanged || resetChanged
    val e = old.evening != changed.evening || targetChanged || resetChanged
    return changed.copy(reservationVersion = old.reservationVersion + if(m || e) 1 else 0,
        morningVersion = old.morningVersion + if(m) 1 else 0, eveningVersion = old.eveningVersion + if(e) 1 else 0,
        goalCycle = old.goalCycle + if(goalNeedsReset(old,today) && !goalNeedsReset(changed,today)) 1 else 0)
}
internal fun encodeSettings(value: AppSettings): JSONObject = JSONObject().put("schema", 2)
    .put("goal", value.goal ?: JSONObject.NULL).put("goalDate", value.goalDate?.toString() ?: JSONObject.NULL)
    .put("maintaining", value.maintaining).put("modelId", value.modelId ?: JSONObject.NULL)
    .put("personas", JSONArray().also { a -> value.personas.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt)) } })
    .put("personaId", value.personaId).put("supplement", value.supplement)
    .put("morningEnabled", value.morning.enabled).put("morningTime", value.morning.time.toString())
    .put("eveningEnabled", value.evening.enabled).put("eveningTime", value.evening.time.toString())
    .put("morningVersion", value.morningVersion).put("eveningVersion", value.eveningVersion).put("goalCycle", value.goalCycle).put("testAnswerId", value.testAnswerId ?: JSONObject.NULL)
    .put("reservationVersion", value.reservationVersion)
internal fun decodeSettings(json: JSONObject, defaults: List<Persona>): AppSettings {
    if (!json.has("schema")) { require(json.keys().asSequence().toSet() == setOf("host")); return AppSettings(personas = defaults) }
    require(json.getInt("schema") in 1..2)
    val array = json.getJSONArray("personas")
    val personas = (0 until array.length()).map { i -> array.getJSONObject(i).let { Persona(it.getString("id"), it.getString("name"), it.getString("prompt")) } }
    require(personas.map { it.id }.distinct().size == personas.size && personas.map { it.id }.containsAll(builtInPersonaIds))
    require(personas.all { it.name.isNotBlank() && it.prompt.isNotBlank() })
    val selected = json.getString("personaId"); require(personas.any { it.id == selected })
    val goal = json.optString("goal").takeUnless { json.isNull("goal") || it.isEmpty() }
    require(goal == null || canonicalGoal(goal) == goal)
    val legacy = json.getInt("schema") == 1
    val morning = if(legacy) SlotSetting(json.optBoolean("notificationEnabled"), LocalTime.parse(json.getString("notificationTime")))
        else SlotSetting(json.getBoolean("morningEnabled"), LocalTime.parse(json.getString("morningTime")))
    val evening = if(legacy) SlotSetting(false, LocalTime.of(20,0))
        else SlotSetting(json.getBoolean("eveningEnabled"), LocalTime.parse(json.getString("eveningTime")))
    return AppSettings(goal=goal, goalDate=json.optString("goalDate").takeUnless { json.isNull("goalDate") || it.isEmpty() }?.let(LocalDate::parse),
        maintaining=json.optBoolean("maintaining"), modelId=json.optString("modelId").takeUnless { json.isNull("modelId") || it.isEmpty() },
        personas=personas, personaId=selected, supplement=json.optString("supplement"), morning=morning, evening=evening,
        testAnswerId=json.optString("testAnswerId").takeUnless { json.isNull("testAnswerId") || it.isEmpty() }?.also { require(isAnswerId(it)) },
        reservationVersion=json.optLong("reservationVersion"), morningVersion=if(legacy) json.optLong("reservationVersion") else json.optLong("morningVersion"),
        eveningVersion=json.optLong("eveningVersion"), goalCycle=json.optLong("goalCycle")).also { require(it.validTimes()) }

}
internal enum class HomeAction { SET_GOAL, RESET_GOAL, CONNECT, SELECT_AGENT, GENERATE }
internal fun homeAction(settings: AppSettings, connected: Boolean, modelUnavailable: Boolean, today: LocalDate): HomeAction = when {
    settings.goal == null -> HomeAction.SET_GOAL
    !settings.maintaining && settings.goalDate?.isBefore(today) == true -> HomeAction.RESET_GOAL
    !connected -> HomeAction.CONNECT
    settings.modelId == null || modelUnavailable -> HomeAction.SELECT_AGENT
    else -> HomeAction.GENERATE
}
