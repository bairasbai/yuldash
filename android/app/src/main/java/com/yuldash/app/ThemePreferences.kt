package com.yuldash.app

import android.content.Context

/** The settings picker and cold start share the existing header-toggle preference. */
internal fun persistThemeChoice(context: Context, dark: Boolean?) {
    ThemePrefs.darkOverride = dark
    val edit = context.getSharedPreferences("yuldash_theme", Context.MODE_PRIVATE).edit()
    if (dark == null) edit.remove("dark_override") else edit.putBoolean("dark_override", dark)
    edit.apply()
}
