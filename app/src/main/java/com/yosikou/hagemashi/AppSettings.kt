package com.yosikou.hagemashi

import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject

internal data class Persona(val id: String, val name: String, val prompt: String)
internal val builtInPersonaIds = setOf("friend", "numbers", "companion")
internal data class AppSettings(val goal: String? = null, val goalDate: LocalDate? = null, val maintaining: Boolean = false,
    val modelId: String? = null, val personas: List<Persona>, val personaId: String = "friend", val supplement: String = "",
    val notificationEnabled: Boolean = false, val notificationTime: LocalTime = LocalTime.of(9, 0),
    val testAnswerId: String? = null, val reservationVersion: Long = 0) {
    fun persona(): Persona = personas.single { it.id == personaId }
    fun deletePersona(id: String): AppSettings {
        require(id !in builtInPersonaIds)
        return copy(personas = personas.filterNot { it.id == id }, personaId = if (personaId == id) "friend" else personaId)
    }
}
internal fun nextSettings(old: AppSettings, changed: AppSettings): AppSettings = changed.copy(reservationVersion =
    if (old.notificationEnabled != changed.notificationEnabled || old.notificationTime != changed.notificationTime || old.testAnswerId != changed.testAnswerId)
        old.reservationVersion + 1 else old.reservationVersion)
internal fun encodeSettings(value: AppSettings): JSONObject = JSONObject().put("schema", 1)
    .put("goal", value.goal ?: JSONObject.NULL).put("goalDate", value.goalDate?.toString() ?: JSONObject.NULL)
    .put("maintaining", value.maintaining).put("modelId", value.modelId ?: JSONObject.NULL)
    .put("personas", JSONArray().also { a -> value.personas.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt)) } })
    .put("personaId", value.personaId).put("supplement", value.supplement).put("notificationEnabled", value.notificationEnabled)
    .put("notificationTime", value.notificationTime.toString()).put("testAnswerId", value.testAnswerId ?: JSONObject.NULL)
    .put("reservationVersion", value.reservationVersion)
internal fun decodeSettings(json: JSONObject, defaults: List<Persona>): AppSettings {
    if (!json.has("schema")) { require(json.keys().asSequence().toSet() == setOf("host")); return AppSettings(personas = defaults) }
    require(json.getInt("schema") == 1)
    val array = json.getJSONArray("personas")
    val personas = (0 until array.length()).map { i -> array.getJSONObject(i).let { Persona(it.getString("id"), it.getString("name"), it.getString("prompt")) } }
    require(personas.map { it.id }.distinct().size == personas.size && personas.map { it.id }.containsAll(builtInPersonaIds))
    require(personas.all { it.name.isNotBlank() && it.prompt.isNotBlank() })
    val selected = json.getString("personaId"); require(personas.any { it.id == selected })
    val goal = json.optString("goal").takeUnless { json.isNull("goal") || it.isEmpty() }
    require(goal == null || canonicalGoal(goal) == goal)
    return AppSettings(goal, json.optString("goalDate").takeUnless { json.isNull("goalDate") || it.isEmpty() }?.let(LocalDate::parse),
        json.optBoolean("maintaining"), json.optString("modelId").takeUnless { json.isNull("modelId") || it.isEmpty() }, personas, selected,
        json.optString("supplement"), json.optBoolean("notificationEnabled"), LocalTime.parse(json.getString("notificationTime")),
        json.optString("testAnswerId").takeUnless { json.isNull("testAnswerId") || it.isEmpty() }?.also { require(isAnswerId(it)) },
        json.optLong("reservationVersion"))
}
internal enum class HomeAction { SET_GOAL, RESET_GOAL, CONNECT, SELECT_AGENT, GENERATE }
internal fun homeAction(settings: AppSettings, connected: Boolean, modelUnavailable: Boolean, today: LocalDate): HomeAction = when {
    settings.goal == null -> HomeAction.SET_GOAL
    !settings.maintaining && settings.goalDate?.isBefore(today) == true -> HomeAction.RESET_GOAL
    !connected -> HomeAction.CONNECT
    settings.modelId == null || modelUnavailable -> HomeAction.SELECT_AGENT
    else -> HomeAction.GENERATE
}
