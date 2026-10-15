package com.kisu.root.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kisu.root.core.*
import kotlinx.coroutines.launch

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val w = intrinsicWidth.coerceAtLeast(48); val h = intrinsicHeight.coerceAtLeast(48)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); setBounds(0, 0, w, h); draw(c)
    return bmp.asImageBitmap()
}

@Composable
fun HomeScreen() {
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<DeviceStatus?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var disabledN by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var askReboot by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        busy = true
        st = Repo.status(); total = Repo.moduleCount(); disabledN = Repo.disabledCount()
        busy = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        SectionCard("状态") {
            st?.let { s ->
                StatusRow("Root", if (s.granted) "已获取" else "未授权")
                StatusRow("管理器", s.provider)
                StatusRow("版本", s.suVersion)
                StatusRow("SELinux", when (s.selinux) {
                    "Enforcing" -> "强制"; "Permissive" -> "宽容"; else -> s.selinux })
                StatusRow("内核", s.kernel)
                StatusRow("设备", s.model)
                StatusRow("系统", s.android)
                StatusRow("ABI", s.abi)
            } ?: Text("正在获取…（首次会弹出 Root 授权请求）", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("模块安全开关") {
            val safeOn = total > 0 && disabledN == total
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("禁用全部模块")
                    Text("等于安全模式，重启后生效", style = MaterialTheme.typography.bodySmall)
                }
                Switch(safeOn, { on ->
                    scope.launch {
                        busy = true; Repo.setAllModules(!on)
                        disabledN = Repo.disabledCount(); busy = false
                    }
                })
            }
        }

        SectionCard("操作") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ scope.launch { busy = true; Repo.sync(); busy = false } }) { Text("sync") }
                OutlinedButton({ scope.launch { busy = true; Repo.restartUi(); busy = false } }) { Text("重启 SystemUI") }
                Button({ askReboot = true }) { Text("重启设备") }
            }
        }
    }

    if (askReboot) AlertDialog(
        onDismissRequest = { askReboot = false },
        title = { Text("重启设备") },
        text = { Text("确定要立即重启吗？") },
        confirmButton = { TextButton({ askReboot = false; scope.launch { Repo.reboot() } }) { Text("重启") } },
        dismissButton = { TextButton({ askReboot = false }) { Text("取消") } },
    )
}

@Composable
fun SuperuserScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppPolicy>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { apps = Repo.installedApps(ctx); loading = false }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(),
            placeholder = { Text("搜索应用") }, singleLine = true)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(apps.filter { it.pkg.contains(q, true) || it.label.contains(q, true) },
                key = { it.pkg }) { app ->
                SuRow(app) { policy ->
                    scope.launch { Repo.setPolicy(ctx, app, policy) }
                    apps = apps.map { if (it.pkg == app.pkg) it.copy(allowed = policy) else it }
                }
            }
        }
    }
}

@Composable
private fun SuRow(app: AppPolicy, onSet: (Boolean?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val bmp = remember(app.pkg) { app.icon?.toImageBitmap() }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            bmp?.let { Image(it, null, Modifier.size(42.dp)) }
                ?: Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("${app.pkg} · uid ${app.uid}", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton({ menu = true }) {
                    Text(when (app.allowed) { true -> "允许"; false -> "拒绝"; null -> "默认" })
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("允许") }, onClick = { menu = false; onSet(true) })
                    DropdownMenuItem(text = { Text("拒绝") }, onClick = { menu = false; onSet(false) })
                    DropdownMenuItem(text = { Text("恢复默认") }, onClick = { menu = false; onSet(null) })
                }
            }
        }
    }
}

@Composable
fun ModulesScreen() {
    val scope = rememberCoroutineScope()
    var mods by remember { mutableStateOf<List<ModuleInfo>>(emptyList()) }
    var zips by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val refresh = {
        scope.launch {
            busy = true; mods = Repo.modules(); zips = Repo.downloadZips(); busy = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        SectionCard("已安装（重启后生效）") {
            if (mods.isEmpty()) Text("未安装模块", style = MaterialTheme.typography.bodySmall)
            mods.forEach { m -> ModuleRow(m,
                onToggle = { on -> scope.launch { Repo.setModuleEnabled(m.id, on); refresh() } },
                onRemove = { scope.launch { Repo.markRemove(m.id); refresh() } },
                onRestore = { scope.launch { Repo.unmarkRemove(m.id); refresh() } })
            }
        }

        SectionCard("从 Download 安装") {
            if (zips.isEmpty()) Text("没有找到 zip", style = MaterialTheme.typography.bodySmall)
            zips.forEach { path ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(path.substringAfterLast('/'), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    Button({ scope.launch {
                        busy = true
                        val r = Repo.installZip(path); busy = false
                        result = (if (r.ok) "安装成功" else "失败 code=${r.code}") + "\n" +
                                 r.out.takeLast(1500)
                    } }) { Text("安装") }
                }
            }
        }
    }

    result?.let { r ->
        AlertDialog(onDismissRequest = { result = null },
            title = { Text("模块安装") },
            text = { Text(r, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp)) },
            confirmButton = { TextButton({ result = null }) { Text("好的") } })
    }
}

@Composable
private fun ModuleRow(m: ModuleInfo, onToggle: (Boolean) -> Unit,
                      onRemove: () -> Unit, onRestore: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${m.name}  ${m.version}", fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("by ${m.author} · ${m.id}", style = MaterialTheme.typography.bodySmall)
                    if (m.markedRemove) Text("已标记卸载，重启后移除",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(m.enabled, onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.markedRemove) {
                    OutlinedButton(onRestore) { Text("取消卸载") }
                } else {
                    OutlinedButton({ ask = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error)) { Text("卸载") }
                }
            }
        }
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false },
        title = { Text("卸载模块") },
        text = { Text("确定卸载 ${m.name} 吗？重启后移除。") },
        confirmButton = { TextButton({ ask = false; onRemove() }) { Text("卸载") } },
        dismissButton = { TextButton({ ask = false }) { Text("取消") } })
}

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf(LogSrc.DMESG) }
    var text by remember { mutableStateOf("选择日志源并刷新") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(src) { busy = true; text = Repo.log(src); busy = false }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogSrc.entries.forEach { s ->
                FilterChip(selected = src == s, onClick = { src = s }, label = { Text(s.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ scope.launch { busy = true; text = Repo.log(src); busy = false } }) { Text("刷新") }
            OutlinedButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", text))
            }) { Text("复制") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        SelectionContainer {
            Text(text,
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        SectionCard("主题") {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { Prefs.theme = v; Prefs.save(ctx) },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(Prefs.theme == v, { Prefs.theme = v; Prefs.save(ctx) })
                    Text(label)
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                Row(Modifier.fillMaxWidth().clickable { Prefs.dynamic = !Prefs.dynamic; Prefs.save(ctx) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(Prefs.dynamic, { Prefs.dynamic = it; Prefs.save(ctx) })
                    Text("动态取色")
                }
            }
        }

        SectionCard("主色调") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0xFF6750A4L to "紫", 0xFF1565C0L to "蓝", 0xFF2E7D32L to "绿",
                       0xFFEF6C00L to "橙", 0xFFAD1457L to "粉", 0xFF00838FL to "青").forEach { (v, _) ->
                    val c = Color(v)
                    Box(Modifier.size(30.dp).background(c, CircleShape)
                        .border(if (Prefs.accent == v) 3.dp else 1.dp,
                            if (Prefs.accent == v) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .clickable { Prefs.accent = v; Prefs.save(ctx) })
                }
            }
        }

        SectionCard("关于") {
            StatusRow("应用", "7Kisu Root v1.0")
            StatusRow("实现", "Kotlin + Compose 原生，libsu Root Shell")
            StatusRow("授权", "真实授权需配合 KernelSU / Magisk 管理器")
        }
    }
}
