package com.yosikou.hagemashi

import android.content.Context

internal fun defaultPersonas(context: Context): List<Persona> = listOf(
    Persona("friend", context.getString(R.string.persona_friend), context.getString(R.string.persona_prompt),true),
    Persona("numbers", context.getString(R.string.persona_numbers), context.getString(R.string.persona_numbers_prompt),true),
    Persona("companion", context.getString(R.string.persona_companion), context.getString(R.string.persona_companion_prompt),true))
private object SettingsAccess { val lock=Any() }
internal class SettingsStore(private val context:Context) {
    private val vault = CredentialVault(context, "app-settings.v1", "hagemashi.settings.v1")
    fun read(): AppSettings = synchronized(SettingsAccess.lock) { try {
        val json=vault.read()
        val language=runCatching{AppLanguage.valueOf(json.optString("language","SYSTEM"))}.getOrDefault(AppLanguage.SYSTEM)
        val target=defaultPersonas(languageContext(context,language))
        val known=AppLanguage.entries.filter{it!=AppLanguage.SYSTEM}.flatMap{defaultPersonas(languageContext(context,it))}.toMutableList()
        val ja=languageContext(context,AppLanguage.JA)
        known+=listOf(Persona("friend",ja.getString(R.string.persona_friend),ja.getString(R.string.persona_prompt_v1)),
            Persona("numbers",ja.getString(R.string.persona_numbers),ja.getString(R.string.persona_numbers_prompt_v1)),
            Persona("companion",ja.getString(R.string.persona_companion),ja.getString(R.string.persona_companion_prompt_v1)))
        val loaded=decodeSettings(json,target)
        val localized=translateDefaultPersonas(loaded,known,target)
        if(localized!=loaded || !json.has("language"))vault.write(encodeSettings(localized))
        saveLanguage(context,localized.language)
        localized
    }catch(_:Exception){throw Problem(Failure.STORAGE)} }
    fun write(value:AppSettings) { synchronized(SettingsAccess.lock){
        val target=defaultPersonas(languageContext(context,value.language))
        val known=AppLanguage.entries.filter{it!=AppLanguage.SYSTEM}.flatMap{defaultPersonas(languageContext(context,it))}
        vault.write(encodeSettings(translateDefaultPersonas(value,known,target)))
        saveLanguage(context,value.language)
        AnswerNotifications(context).refreshChannel()
    } }
}
