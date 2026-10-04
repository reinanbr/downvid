package com.downvid.downvid

import android.content.Context

/** App settings shared by every Flutter engine and the services. */
object Settings {
    private const val PREFS = "downvid_settings"

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun maxConcurrent(context: Context) = prefs(context).getInt("maxConcurrent", 2).coerceIn(1, 4)

    fun all(context: Context): Map<String, Any?> = prefs(context).all

    fun set(context: Context, key: String, value: Any?) {
        prefs(context).edit().apply {
            when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putInt(key, value.toInt())
                is String -> putString(key, value)
                else -> putString(key, value.toString())
            }
        }.apply()
    }
}
