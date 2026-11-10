package com.yosikou.hagemashi

import android.content.Context

internal fun defaultPersonas(context: Context): List<Persona> = listOf(
    Persona("friend", context.getString(R.string.persona_friend), context.getString(R.string.persona_prompt)),
    Persona("numbers", context.getString(R.string.persona_numbers), context.getString(R.string.persona_numbers_prompt)),
    Persona("companion", context.getString(R.string.persona_companion), context.getString(R.string.persona_companion_prompt)))
internal class SettingsStore(context: Context) {
    private val defaults = defaultPersonas(context)
    private val vault = CredentialVault(context, "app-settings.v1", "hagemashi.settings.v1")
    fun read(): AppSettings = try { decodeSettings(vault.read(), defaults) } catch (_: Exception) { throw Problem(Failure.STORAGE) }
    fun write(value: AppSettings) { vault.write(encodeSettings(value)) }
}
