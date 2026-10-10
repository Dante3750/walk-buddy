package com.walkbuddy.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.walkbuddy.domain.AppLanguages
import java.util.Locale

/**
 * The in-app language (alpha 2.0). On Android 13+ this is the system's own per-app language (LocaleManager, also visible in the phone's
 * Settings). Below that the choice is kept in a private preference and applied in attachBaseContext. "" means follow the system.
 */
object AppLocale {
    private const val PREFS = "wb_locale"
    private const val KEY = "tag"

    private fun stored(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun current(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val tag = runCatching {
                val l = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
                if (l == null || l.isEmpty) "" else l.get(0).language
            }.getOrDefault("")
            return AppLanguages.normalize(tag)
        }
        return AppLanguages.normalize(stored(ctx))
    }

    fun set(activity: Activity, tag: String) {
        val t = AppLanguages.normalize(tag)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = if (t.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(t)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t).apply()
            activity.recreate()
        }
    }

    /** For attachBaseContext on Android 12 and below. A no-op when following the system or on 13+ (the system applies it). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = AppLanguages.normalize(stored(base))
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}
