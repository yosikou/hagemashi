package com.yosikou.hagemashi

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

// Only the non-sensitive locale preference is mirrored here for resource bootstrap and cold jobs.
// Goals, personas, health values and credentials remain in their existing protected stores.
internal fun savedLanguage(context:Context):AppLanguage = runCatching {
    AppLanguage.valueOf(context.getSharedPreferences("app-language",Context.MODE_PRIVATE).getString("language","SYSTEM")!!)
}.getOrDefault(AppLanguage.SYSTEM)
internal fun saveLanguage(context:Context,language:AppLanguage) {
    check(context.getSharedPreferences("app-language",Context.MODE_PRIVATE).edit().putString("language",language.name).commit())
}
internal fun appLocale(language:AppLanguage):Locale {
    val list=Resources.getSystem().configuration.locales
    return language.locale((0 until list.size()).map{list[it]})
}
internal fun languageContext(context:Context,language:AppLanguage=savedLanguage(context)):Context {
    val locale=appLocale(language)
    val config=Configuration(context.resources.configuration).apply { setLocales(LocaleList(locale));setLayoutDirection(locale) }
    return context.createConfigurationContext(config)
}
internal val LocalAppLocale=staticCompositionLocalOf { Locale.ENGLISH }
@Composable internal fun LanguageContent(language:AppLanguage,content:@Composable ()->Unit) {
    val context=LocalContext.current
    val configuration=LocalConfiguration.current // Also observes device locale changes in system mode.
    val localized=remember(context,language,configuration){languageContext(context,language)}
    LaunchedEffect(localized) { runCatching { AnswerNotifications(localized).refreshChannel() } }
    CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides localized.resources.configuration,
        LocalAppLocale provides localized.resources.configuration.locales[0],content=content)
}
