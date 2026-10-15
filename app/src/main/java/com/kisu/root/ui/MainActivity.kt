package com.kisu.root.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        setContent { KisuTheme { App() } }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun App() {
    val tabs = listOf(
        Tab("主页", Icons.Filled.Home),
        Tab("超级用户", Icons.Filled.Security),
        Tab("模块", Icons.Filled.Extension),
        Tab("日志", Icons.Filled.Terminal),
        Tab("设置", Icons.Filled.Settings),
    )
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(tab == i, { tab = i }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen()
                1 -> SuperuserScreen()
                2 -> ModulesScreen()
                3 -> LogScreen()
                else -> SettingsScreen()
            }
        }
    }
}
