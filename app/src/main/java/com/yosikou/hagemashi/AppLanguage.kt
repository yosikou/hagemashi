package com.yosikou.hagemashi

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", ""), JA("ja", "日本語"), EN("en", "English"), FR("fr", "Français"), DE("de", "Deutsch"), HI("hi", "हिन्दी"), ZH("zh-Hans", "简体中文");
    fun locale(device: List<Locale>): Locale = if (this == SYSTEM) resolveDeviceLanguage(device).locale(emptyList()) else Locale.forLanguageTag(tag)
}
internal fun resolveDeviceLanguage(locales: List<Locale>): AppLanguage {
    for(locale in locales) {
        when(locale.language) {
            "ja"->return AppLanguage.JA; "en"->return AppLanguage.EN; "fr"->return AppLanguage.FR
            "de"->return AppLanguage.DE; "hi"->return AppLanguage.HI
            "zh"->if(locale.script!="Hant" && (locale.script=="Hans" || locale.country !in setOf("TW","HK","MO")))return AppLanguage.ZH
        }
    }
    return AppLanguage.EN
}
internal fun localizedNumber(value: String, locale: Locale, maximumFractionDigits: Int = 12): String =
    value.toBigDecimalOrNull()?.let { number -> NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed=false; this.maximumFractionDigits=maximumFractionDigits
        roundingMode=java.math.RoundingMode.HALF_UP
    }.format(number) } ?: value
internal fun localizedWeightInput(text:String, unit:WeightUnit, locale:Locale):String? {
    val normalized=Normalizer.normalize(text.trim(),Normalizer.Form.NFKC)
    val separator=DecimalFormatSymbols.getInstance(locale).decimalSeparator
    // Do not interpret a grouping separator as a decimal point in dot-decimal languages.
    if(separator!='.' && normalized.contains('.') && normalized.contains(separator))return null
    val decimal=if(separator!='.')normalized.replace(separator,'.') else normalized
    val ascii=buildString { for(c in decimal)append(if(Character.isDigit(c))Character.digit(c,10).toString() else c) }
    return weightInputKg(ascii,unit)
}
internal fun localizedMoment(value:Instant,zone:ZoneId,locale:Locale,seconds:Boolean):String =
    if(locale.language=="ja")DateTimeFormatter.ofPattern(if(seconds)"yyyy/MM/dd HH:mm:ss" else "yyyy/MM/dd HH:mm",locale).withZone(zone).format(value)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT,if(seconds)FormatStyle.MEDIUM else FormatStyle.SHORT).withLocale(locale).withZone(zone).format(value)
internal fun localizedDay(value:java.time.LocalDate,locale:Locale):String =
    if(locale.language=="ja")value.format(DateTimeFormatter.ofPattern("yyyy/MM/dd",locale)) else value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
internal fun translateDefaultPersonas(value:AppSettings, known:List<Persona>, target:List<Persona>):AppSettings = value.copy(personas=value.personas.map { saved ->
    if(saved.id !in builtInPersonaIds)saved.copy(defaultContent=false) else {
        val untouched=saved.defaultContent ?: known.any { it.id==saved.id && it.name==saved.name && it.prompt==saved.prompt }
        if(untouched)target.single{it.id==saved.id}.copy(defaultContent=true) else saved.copy(defaultContent=false)
    }
})

internal fun measurementValueText(row:MeasurementRow,locale:Locale,stepUnit:String):String {
    val value=row.value ?: return ""
    if(row.item==MeasureItem.GOAL_DATE)return runCatching{localizedDay(java.time.LocalDate.parse(value),locale)}.getOrDefault(value)
    return localizedNumber(value,locale,64)+" "+if(row.unit=="歩")stepUnit else row.unit
}
