package com.yosikou.hagemashi

import android.content.Context

internal fun defaultPersonas(context: Context): List<Persona> = listOf(
    Persona("friend", context.getString(R.string.persona_friend), context.getString(R.string.persona_prompt)),
    Persona("numbers", context.getString(R.string.persona_numbers), context.getString(R.string.persona_numbers_prompt)),
    Persona("companion", context.getString(R.string.persona_companion), context.getString(R.string.persona_companion_prompt)))
private object SettingsAccess { val lock=Any() }
internal class SettingsStore(context: Context) {
    private val defaults = defaultPersonas(context)
    private val previous=listOf(
        Persona("friend",context.getString(R.string.persona_friend),context.getString(R.string.persona_prompt_v1)),
        Persona("numbers",context.getString(R.string.persona_numbers),context.getString(R.string.persona_numbers_prompt_v1)),
        Persona("companion",context.getString(R.string.persona_companion),context.getString(R.string.persona_companion_prompt_v1)))
    private val vault = CredentialVault(context, "app-settings.v1", "hagemashi.settings.v1")
    fun read(): AppSettings = synchronized(SettingsAccess.lock) { try {
        val loaded=decodeSettings(vault.read(),defaults)
        upgradeDefaultPersonas(loaded,previous,defaults).also{if(it!=loaded)vault.write(encodeSettings(it))}
    }catch(_:Exception){throw Problem(Failure.STORAGE)} }
    fun write(value: AppSettings) { synchronized(SettingsAccess.lock){vault.write(encodeSettings(value))} }
}
