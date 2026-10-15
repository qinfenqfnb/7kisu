package com.kisu.root.core

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class CmdResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

object Sh {
    val granted: Boolean get() = Shell.isAppGrantedRoot() == true

    fun exec(vararg cmds: String): CmdResult {
        val r = Shell.cmd(*cmds).exec()
        return CmdResult(r.code, r.out.joinToString("\n"), r.err.joinToString("\n"))
    }
    suspend fun su(vararg cmds: String): CmdResult = withContext(Dispatchers.IO) { exec(*cmds) }
    suspend fun line(cmd: String): String = withContext(Dispatchers.IO) {
        exec(cmd).out.trim().lines().firstOrNull { it.isNotBlank() } ?: ""
    }
}

class KisuApp : Application() {
    companion object {
        init {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(15)
            )
        }
    }
}

data class DeviceStatus(
    val granted: Boolean, val provider: String, val suVersion: String,
    val selinux: String, val kernel: String, val model: String,
    val android: String, val abi: String,
)

data class ModuleInfo(
    val id: String, val name: String, val version: String,
    val author: String, val enabled: Boolean, val markedRemove: Boolean,
)

data class AppPolicy(
    val pkg: String, val label: String, val uid: Int,
    val icon: Drawable?, val allowed: Boolean?,
)

enum class LogSrc(val label: String, val cmd: String) {
    DMESG("内核", "dmesg 2>/dev/null"),
    LOGCAT("logcat", "logcat -d -t 800 2>/dev/null"),
    KSUD("ksud", "cat /data/adb/ksu/log/* 2>/dev/null | tail -n 800"),
    MAGISK("Magisk", "cat /cache/magisk.log /data/cache/magisk.log /data/adb/magisk.log 2>/dev/null | tail -n 800"),
}

object Repo {

    suspend fun status(): DeviceStatus = withContext(Dispatchers.IO) {
        val granted = Sh.exec("id -u").out.trim() == "0"
        val isKsu = Sh.exec("[ -x /data/adb/ksud ] || command -v ksud").ok
        val isMagisk = Sh.exec("command -v magisk").ok
        val provider = when { isKsu -> "KernelSU"; isMagisk -> "Magisk"; else -> "未检测" }
        val suVer = when {
            isKsu -> Sh.line("ksud -V 2>/dev/null || /data/adb/ksud -V 2>/dev/null").ifBlank { "已安装" }
            isMagisk -> Sh.line("magisk -V 2>/dev/null").ifBlank { "已安装" }
            else -> "-"
        }
        DeviceStatus(
            granted, provider, suVer,
            selinux = Sh.line("getenforce 2>/dev/null").ifBlank { "未知" },
            kernel = System.getProperty("os.version") ?: "-",
            model = "${Build.BRAND} ${Build.MODEL}",
            android = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "-",
        )
    }

    suspend fun modules(): List<ModuleInfo> = withContext(Dispatchers.IO) {
        val dirs = Sh.exec("ls /data/adb/modules 2>/dev/null").out.lines().filter { it.isNotBlank() }
        if (dirs.isEmpty()) return@withContext emptyList()
        val disabled = Sh.exec("ls /data/adb/modules/*/disable 2>/dev/null").out.lines()
            .map { it.substringAfterLast('/') }.toSet()
        val removed = Sh.exec("ls /data/adb/modules/*/remove 2>/dev/null").out.lines()
            .map { it.substringAfterLast('/') }.toSet()
        dirs.map { id ->
            val prop = Sh.exec("cat '/data/adb/modules/$id/module.prop' 2>/dev/null").out
            val m = prop.lines().mapNotNull { l ->
                val i = l.indexOf('=')
                if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
            }.toMap()
            ModuleInfo(id, m["name"] ?: id, m["version"] ?: "-", m["author"] ?: "-",
                id !in disabled, id in removed)
        }
    }

    suspend fun moduleCount(): Int =
        Sh.line("ls -1 /data/adb/modules 2>/dev/null | wc -l").toIntOrNull() ?: 0

    suspend fun disabledCount(): Int =
        Sh.line("ls /data/adb/modules/*/disable 2>/dev/null | wc -l").toIntOrNull() ?: 0

    suspend fun setModuleEnabled(id: String, enabled: Boolean) =
        Sh.su(if (enabled) "rm -f '/data/adb/modules/$id/disable'"
              else "touch '/data/adb/modules/$id/disable'")

    suspend fun markRemove(id: String) = Sh.su("touch '/data/adb/modules/$id/remove'")
    suspend fun unmarkRemove(id: String) = Sh.su("rm -f '/data/adb/modules/$id/remove'")

    suspend fun setAllModules(enabled: Boolean) =
        Sh.su(if (enabled) "for d in /data/adb/modules/*/; do rm -f \"\$d/disable\"; done"
              else "for d in /data/adb/modules/*/; do touch \"\$d/disable\"; done")

    suspend fun downloadZips(): List<String> =
        Sh.exec("ls -1 /sdcard/Download/*.zip 2>/dev/null").out.lines().filter { it.isNotBlank() }

    suspend fun installZip(path: String): CmdResult {
        val useKsu = Sh.exec("command -v ksud || [ -x /data/adb/ksud ]").ok
        return if (useKsu) Sh.su("ksud module install '$path' || /data/adb/ksud module install '$path'")
               else Sh.su("magisk --install-module '$path'")
    }

    suspend fun installedApps(ctx: Context): List<AppPolicy> = withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val store = loadPolicies(ctx)
        val magiskPol = if (Sh.exec("command -v magisk").ok) magiskPolicies() else emptyMap()
        pm.getInstalledPackages(PackageManager.GET_META_DATA).asSequence()
            .filter { it.applicationInfo != null &&
                      it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { ai ->
                val info = ai.applicationInfo
                AppPolicy(
                    pkg = ai.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    uid = info.uid,
                    icon = pm.getApplicationIcon(info),
                    allowed = store[ai.packageName] ?: magiskPol[info.uid]?.let { it == 2 },
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun setPolicy(ctx: Context, app: AppPolicy, allow: Boolean?) = withContext(Dispatchers.IO) {
        val m = loadPolicies(ctx).toMutableMap(); m[app.pkg] = allow; savePolicies(ctx, m)
        if (Sh.exec("command -v magisk").ok && Sh.granted) {
            when (allow) {
                true -> Sh.exec("magisk --sqlite \"INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (${app.uid},2,0,1,1)\"")
                false -> Sh.exec("magisk --sqlite \"INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (${app.uid},1,0,1,1)\"")
                null -> Sh.exec("magisk --sqlite \"DELETE FROM policies WHERE uid=${app.uid}\"")
            }
        }
    }

    private fun magiskPolicies(): Map<Int, Int> =
        Sh.exec("magisk --sqlite \"SELECT uid,policy FROM policies\"").out.lines()
            .mapNotNull { l ->
                val u = Regex("uid=(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
                val p = Regex("policy=(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
                if (u != null && p != null) u to p else null
            }.toMap()

    private fun polFile(ctx: Context) = File(ctx.filesDir, "policies.json")

    fun loadPolicies(ctx: Context): Map<String, Boolean?> = try {
        val o = JSONObject(polFile(ctx).readText())
        o.keys().asSequence().associateWith { k ->
            when (o.optString(k)) { "true" -> true; "false" -> false; else -> null }
        }
    } catch (e: Exception) { emptyMap() }

    private fun savePolicies(ctx: Context, m: Map<String, Boolean?>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        polFile(ctx).writeText(o.toString())
    }

    suspend fun log(src: LogSrc): String = Sh.su(src.cmd).out.trim().ifBlank { "(空)" }
    suspend fun sync() = Sh.su("sync")
    suspend fun restartUi() = Sh.su("pkill -f com.android.systemui || killall com.android.systemui")
    suspend fun reboot() = Sh.su("reboot")
}
