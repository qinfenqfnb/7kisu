package com.kisu.root.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object Prefs {
    var theme by mutableStateOf("system")
    var accent by mutableStateOf(0xFF6750A4L)
    var dynamic by mutableStateOf(false)

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        theme = sp.getString("theme", "system") ?: "system"
        accent = sp.getLong("accent", 0xFF6750A4L)
        dynamic = sp.getBoolean("dynamic", false)
    }
    fun save(ctx: Context) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .putString("theme", theme).putLong("accent", accent)
            .putBoolean("dynamic", dynamic).apply()
    }
}

@Composable
fun KisuTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.theme) {
        "dark" -> true; "light" -> false; else -> isSystemInDarkTheme()
    }
    val scheme = if (Prefs.dynamic && Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val accent = Color(Prefs.accent)
        if (dark) darkColorScheme(primary = accent, secondary = accent)
        else lightColorScheme(primary = accent, secondary = accent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
