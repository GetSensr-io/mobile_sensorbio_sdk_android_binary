package com.sensorbio.example

import android.content.Context

/** Tiny persisted toggle for the gRPC environment (staging vs prod), like the iOS sample's `envIsDev`. */
object Env {
    private const val PREFS = "example_prefs"
    private const val KEY = "env_is_dev"

    fun isDev(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setDev(context: Context, dev: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, dev).apply()
}
