package com.example.myapplication.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapplication.service.BackendForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

data class ProjectRootOption(
    val label: String,
    val path: String,
    val note: String? = null,
)

class CodexRuntimeController(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val packageManager = context.packageManager
    private val powerManager = context.getSystemService(PowerManager::class.java)

    suspend fun ensureBackendRunning(): JSONObject {
        val status = getBackendStatus()
        if (!status.optBoolean("termuxInstalled")) {
            return status.put("error", "Termux 未安装")
        }
        if (!status.optBoolean("rootAvailable")) {
            return status.put("error", "root 不可用或尚未授权")
        }
        if (status.optBoolean("backendListening")) {
            return status
        }

        val uid = status.optInt("termuxUid", -1)
        if (uid <= 0) {
            return status.put("error", "无法解析 Termux uid")
        }

        val attempts = listOf(
            "termux-uid" to RootShell.run(command = buildBootstrapScript(), uid = uid, timeoutMillis = 8_000L),
            "root-fallback" to RootShell.run(command = buildBootstrapScript(), timeoutMillis = 8_000L),
        )

        var lastResult = attempts.last().second
        attempts.forEachIndexed { index, (label, result) ->
            lastResult = result
            Log.d(
                TAG,
                "ensureBackendRunning attempt=$label exit=${result.exitCode} stdout=${result.stdout.trim()} stderr=${result.stderr.trim()}",
            )
            if (result.exitCode == 0) {
                repeat(10) {
                    if (isBackendListening()) {
                        return getBackendStatus()
                            .put("startedNow", true)
                            .put("startedBy", label)
                    }
                    delay(700L)
                }
            }
            if (index != attempts.lastIndex) {
                delay(500L)
            }
        }

        return getBackendStatus()
            .put("error", "端口 8765 没有起来")
            .put("backendStatusDetail", buildBackendFailureDetail("app-server 没有成功监听 8765", lastResult.stdout, lastResult.stderr))
    }

    suspend fun restartBackend(): JSONObject {
        val uid = resolveTermuxUid()
        if (uid == null) {
            return getBackendStatus().put("error", "找不到 Termux uid")
        }

        val restartScript = """
            export PATH="${termuxPrefixPath()}/bin:/system/bin:/system/xbin"; if [ -x "${termuxPrefixPath()}/bin/pkill" ]; then "${termuxPrefixPath()}/bin/pkill" -f 'codex app-server --listen ws://127.0.0.1:$PORT' >/dev/null 2>&1 || true; else pkill -f 'codex app-server --listen ws://127.0.0.1:$PORT' >/dev/null 2>&1 || true; fi; sleep 1; ${buildBootstrapScript()}
        """.trimIndent()

        val result = RootShell.run(command = restartScript, uid = uid, timeoutMillis = 10_000L)
        if (result.exitCode != 0) {
            return getBackendStatus()
                .put("error", "重启 app-server 失败")
                .put("stderr", result.stderr)
                .put("backendStatusDetail", buildBackendFailureDetail("重启 app-server 失败", result.stdout, result.stderr))
        }

        repeat(10) {
            if (isBackendListening()) {
                return getBackendStatus().put("restartedNow", true)
            }
            delay(700L)
        }

        return getBackendStatus()
            .put("error", "重启后端口 8765 仍不可达")
            .put("backendStatusDetail", buildBackendFailureDetail("重启后 app-server 仍未监听 8765", "", ""))
    }

    suspend fun stopBackend(): JSONObject {
        val status = getBackendStatus()
        if (!status.optBoolean("termuxInstalled")) {
            return status.put("error", "Termux 未安装")
        }
        if (!status.optBoolean("rootAvailable")) {
            return status.put("error", "root 不可用或尚未授权")
        }
        if (!status.optBoolean("backendListening")) {
            return status.put("stoppedNow", false)
        }

        val uid = status.optInt("termuxUid", -1).takeIf { it > 0 }
        val attempts = buildList {
            if (uid != null) {
                add("termux-uid" to RootShell.run(command = buildStopScript(), uid = uid, timeoutMillis = 8_000L))
            }
            add("root-fallback" to RootShell.run(command = buildStopScript(), timeoutMillis = 8_000L))
        }

        var lastResult = attempts.last().second
        attempts.forEachIndexed { index, (label, result) ->
            lastResult = result
            Log.d(
                TAG,
                "stopBackend attempt=$label exit=${result.exitCode} stdout=${result.stdout.trim()} stderr=${result.stderr.trim()}",
            )
            if (result.exitCode == 0) {
                repeat(8) {
                    if (!isBackendListening()) {
                        return getBackendStatus()
                            .put("stoppedNow", true)
                            .put("stoppedBy", label)
                    }
                    delay(400L)
                }
            }
            if (index != attempts.lastIndex) {
                delay(300L)
            }
        }

        return getBackendStatus()
            .put("error", "app-server 停止失败")
            .put("backendStatusDetail", buildBackendFailureDetail("app-server 没有成功停止", lastResult.stdout, lastResult.stderr))
    }

    suspend fun getBackendStatus(): JSONObject {
        val termuxUid = resolveTermuxUid()
        val rootAvailable = isRootAvailable()
        val backendListening = isBackendListening()
        val authPresent = isCodexAuthPresent(rootAvailable, termuxUid)

        Log.d(
            TAG,
            "getBackendStatus root=$rootAvailable termuxUid=${termuxUid ?: -1} backend=$backendListening auth=$authPresent",
        )

        return JSONObject()
            .put("rootAvailable", rootAvailable)
            .put("termuxInstalled", termuxUid != null)
            .put("termuxUid", termuxUid)
            .put("backendListening", backendListening)
            .put("authPresent", authPresent)
            .put(
                "backendStatusDetail",
                buildBackendStatusDetail(
                    termuxInstalled = termuxUid != null,
                    rootAvailable = rootAvailable,
                    backendListening = backendListening,
                    authPresent = authPresent,
                ),
            )
            .put("port", PORT)
            .put("autoHardeningEnabled", isAutoHardeningEnabled())
    }

    suspend fun getKeepaliveStatus(): JSONObject {
        val uid = resolveTermuxUid()
        val rootAvailable = isRootAvailable()

        val deviceIdleOutput = if (rootAvailable) {
            RootShell.run("cmd deviceidle whitelist", timeoutMillis = 6_000L).stdout
        } else {
            ""
        }
        val restrictBackgroundOutput = if (rootAvailable) {
            RootShell.run("cmd netpolicy list restrict-background-whitelist", timeoutMillis = 6_000L).stdout
        } else {
            ""
        }
        val standbyBucketRaw = if (rootAvailable) {
            RootShell.run("am get-standby-bucket $TERMUX_PACKAGE", timeoutMillis = 6_000L).stdout.trim()
        } else {
            "unknown"
        }
        val standbyBucket = normalizeStandbyBucket(standbyBucketRaw)

        return JSONObject()
            .put("rootAvailable", rootAvailable)
            .put("termuxInstalled", uid != null)
            .put("termuxUid", uid)
            .put("deviceIdleWhitelisted", deviceIdleOutput.lineSequence().any { it.contains(TERMUX_PACKAGE) })
            .put(
                "restrictBackgroundWhitelisted",
                uid != null && restrictBackgroundOutput.split(Regex("\\s+")).any { it == uid.toString() },
            )
            .put("standbyBucket", standbyBucket)
            .put("standbyBucketRaw", standbyBucketRaw.ifBlank { "unknown" })
            .put("backendListening", isBackendListening())
            .put("batteryOptimizationIgnoredForUiApp", powerManager.isIgnoringBatteryOptimizations(context.packageName))
            .put("autoHardeningEnabled", isAutoHardeningEnabled())
    }

    suspend fun runKeepaliveHardening(): JSONObject {
        val uid = resolveTermuxUid()
        if (uid == null) {
            return getKeepaliveStatus().put("error", "Termux 未安装")
        }
        if (!isRootAvailable()) {
            return getKeepaliveStatus().put("error", "root 不可用或尚未授权")
        }

        val actions = JSONArray()
        actions.put(runAction("deviceidle", "cmd deviceidle whitelist +$TERMUX_PACKAGE"))
        actions.put(runAction("restrictBackground", "cmd netpolicy add restrict-background-whitelist $uid"))
        actions.put(runAction("standbyBucket", "am set-standby-bucket $TERMUX_PACKAGE active"))

        prefs.edit().putBoolean(PREF_AUTO_HARDENING, true).apply()
        return getKeepaliveStatus().put("actions", actions)
    }

    suspend fun reapplyHardeningIfEnabled() {
        if (!isAutoHardeningEnabled()) return

        val status = getKeepaliveStatus()
        if (!status.optBoolean("rootAvailable") || !status.optBoolean("termuxInstalled")) return

        val needsReapply =
            !status.optBoolean("deviceIdleWhitelisted") ||
            !status.optBoolean("restrictBackgroundWhitelisted") ||
            !isHealthyStandbyBucket(status.optString("standbyBucket"))
        if (needsReapply) {
            runKeepaliveHardening()
        }
    }

    fun getAppPreferences(): JSONObject = JSONObject()
        .put("autoHardeningEnabled", isAutoHardeningEnabled())

    fun setAutoHardeningEnabled(enabled: Boolean): JSONObject {
        prefs.edit().putBoolean(PREF_AUTO_HARDENING, enabled).apply()
        return getAppPreferences()
    }

    fun setForegroundSessionActive(active: Boolean): JSONObject {
        val intent = Intent(context, BackendForegroundService::class.java)
        if (active) {
            ContextCompat.startForegroundService(
                context,
                intent.setAction(BackendForegroundService.ACTION_START),
            )
        } else {
            context.startService(intent.setAction(BackendForegroundService.ACTION_STOP))
        }
        return JSONObject().put("active", active)
    }

    fun shutdownManagedSession(): JSONObject {
        val intent = Intent(context, BackendForegroundService::class.java)
        context.startService(intent.setAction(BackendForegroundService.ACTION_SHUTDOWN))
        return JSONObject().put("shutdownRequested", true)
    }

    fun openTermux(activity: Activity): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) ?: return false
        activity.startActivity(launchIntent)
        return true
    }

    fun requestBatteryOptimizationIgnore(activity: Activity): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
            activity.startActivity(intent)
            true
        } catch (_: Throwable) {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            true
        }
    }

    fun defaultProjectRoots(): List<ProjectRootOption> = listOf(
        ProjectRootOption(
            label = "Termux Home",
            path = termuxHomePath(),
            note = "最适合直接让 Codex 操作本地仓库和脚本。",
        ),
        ProjectRootOption(
            label = "共享存储根目录",
            path = "/storage/emulated/0",
            note = "适合下载目录、文档目录和你手机常用文件。",
        ),
        ProjectRootOption(
            label = "Download",
            path = "/storage/emulated/0/Download",
            note = "适合临时项目、压缩包、生成文件。",
        ),
        ProjectRootOption(
            label = "Documents",
            path = "/storage/emulated/0/Documents",
            note = "适合长期保存的项目文档和脚本。",
        ),
    )

    suspend fun getLocalThreadIndex(limit: Int = 80): JSONArray = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) {
            return@withContext JSONArray()
        }

        val result = RootShell.run(
            command = """
                for home in "$LEGACY_TERMUX_HOME" "$ALT_TERMUX_HOME"; do
                  if [ -f "${'$'}home/.codex/session_index.jsonl" ]; then
                    tail -n $limit "${'$'}home/.codex/session_index.jsonl"
                    break
                  fi
                done
            """.trimIndent(),
            timeoutMillis = 5_000L,
        )
        if (result.exitCode != 0) {
            return@withContext JSONArray()
        }

        val items = JSONArray()
        result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                try {
                    items.put(JSONObject(line))
                } catch (_: JSONException) {
                    // Skip malformed lines from partially-written session index records.
                }
            }
        items
    }

    private suspend fun runAction(name: String, command: String): JSONObject {
        val result = RootShell.run(command = command, timeoutMillis = 8_000L)
        return JSONObject()
            .put("name", name)
            .put("command", command)
            .put("success", result.exitCode == 0)
            .put("stdout", result.stdout.trim())
            .put("stderr", result.stderr.trim())
    }

    private suspend fun isRootAvailable(): Boolean {
        val result = RootShell.run("id", timeoutMillis = 4_000L)
        return result.exitCode == 0 && result.stdout.contains("uid=0")
    }

    private fun resolveTermuxUid(): Int? = try {
        packageManager.getApplicationInfo(TERMUX_PACKAGE, 0).uid
    } catch (_: Throwable) {
        null
    }

    private suspend fun isCodexAuthPresent(rootAvailable: Boolean, termuxUid: Int?): Boolean {
        if (!rootAvailable) {
            return false
        }

        val rootResult = RootShell.run(
            command = "for home in \"$LEGACY_TERMUX_HOME\" \"$ALT_TERMUX_HOME\"; do if [ -f \"${'$'}home/.codex/auth.json\" ]; then echo 1; exit 0; fi; done; echo 0",
            timeoutMillis = 4_000L,
        )
        if (rootResult.stdout.trim() == "1") {
            Log.d(
                TAG,
                "authProbe root exit=${rootResult.exitCode} stdout=${rootResult.stdout.trim()} stderr=${rootResult.stderr.trim()} present=true",
            )
            return true
        }

        val uidResult = if (termuxUid != null) {
            RootShell.run(
                command = "for home in \"$LEGACY_TERMUX_HOME\" \"$ALT_TERMUX_HOME\"; do if [ -f \"${'$'}home/.codex/auth.json\" ]; then echo 1; exit 0; fi; done; echo 0",
                uid = termuxUid,
                timeoutMillis = 4_000L,
            )
        } else {
            null
        }
        val present = uidResult?.stdout?.trim() == "1"
        Log.d(
            TAG,
            "authProbe root exit=${rootResult.exitCode} stdout=${rootResult.stdout.trim()} stderr=${rootResult.stderr.trim()} uidExit=${uidResult?.exitCode} uidStdout=${uidResult?.stdout?.trim()} uidStderr=${uidResult?.stderr?.trim()} present=$present",
        )
        return present
    }

    private fun normalizeStandbyBucket(raw: String): String {
        return when (raw.trim().lowercase()) {
            "", "unknown" -> "unknown"
            "5", "exempted" -> "exempted"
            "10", "active" -> "active"
            "20", "working_set" -> "working_set"
            "30", "frequent" -> "frequent"
            "40", "rare" -> "rare"
            "45", "restricted" -> "restricted"
            else -> raw.trim()
        }
    }

    private fun isHealthyStandbyBucket(bucket: String): Boolean =
        bucket == "active" || bucket == "exempted"

    private fun isAutoHardeningEnabled(): Boolean =
        prefs.getBoolean(PREF_AUTO_HARDENING, true)

    private suspend fun isBackendListening(): Boolean {
        val rootResult = RootShell.run(
            command = "ss -ltn 2>/dev/null | grep -q '127.0.0.1:$PORT' && echo 1 || echo 0",
            timeoutMillis = 4_000L,
        )
        if (rootResult.stdout.trim() == "1") {
            Log.d(
                TAG,
                "backendProbe root exit=${rootResult.exitCode} stdout=${rootResult.stdout.trim()} stderr=${rootResult.stderr.trim()}",
            )
            return true
        }
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(HOST, PORT), 600)
                Log.d(
                    TAG,
                    "backendProbe root exit=${rootResult.exitCode} stdout=${rootResult.stdout.trim()} stderr=${rootResult.stderr.trim()} fallback=socket",
                )
                true
            }
        } catch (_: Throwable) {
            Log.d(
                TAG,
                "backendProbe root exit=${rootResult.exitCode} stdout=${rootResult.stdout.trim()} stderr=${rootResult.stderr.trim()} fallback=failed",
            )
            false
        }
    }

    private fun buildBootstrapScript(): String = """
        TERMUX_HOME=""; for candidate in "$LEGACY_TERMUX_HOME" "$ALT_TERMUX_HOME"; do if [ -d "${'$'}candidate" ]; then TERMUX_HOME="${'$'}candidate"; break; fi; done; TERMUX_PREFIX=""; for candidate in "$LEGACY_TERMUX_PREFIX" "$ALT_TERMUX_PREFIX"; do if [ -d "${'$'}candidate" ]; then TERMUX_PREFIX="${'$'}candidate"; break; fi; done; if [ -z "${'$'}TERMUX_HOME" ] || [ -z "${'$'}TERMUX_PREFIX" ]; then echo "termux paths not found" >&2; exit 1; fi; PREFIX="${'$'}TERMUX_PREFIX"; HOME="${'$'}TERMUX_HOME"; export PREFIX HOME TMPDIR="${'$'}TERMUX_PREFIX/tmp"; export PATH="${'$'}TERMUX_PREFIX/bin:/system/bin:/system/xbin"; export CODEX_MANAGED_BY_NPM=1; APP_SERVER_BIN="${'$'}TERMUX_PREFIX/bin/codex"; [ -f "${'$'}TERMUX_HOME/.codex-termux-proxy.sh" ] && . "${'$'}TERMUX_HOME/.codex-termux-proxy.sh"; if ss -ltn 2>/dev/null | grep -q '127.0.0.1:$PORT'; then exit 0; fi; if [ ! -x "${'$'}APP_SERVER_BIN" ]; then echo "codex binary not found: ${'$'}APP_SERVER_BIN" >&2; exit 1; fi; nohup "${'$'}APP_SERVER_BIN" app-server --listen ws://127.0.0.1:$PORT >/dev/null 2>&1 &
    """.trimIndent()

    private fun buildStopScript(): String = """
        TERMUX_PREFIX=""; for candidate in "$LEGACY_TERMUX_PREFIX" "$ALT_TERMUX_PREFIX"; do if [ -d "${'$'}candidate" ]; then TERMUX_PREFIX="${'$'}candidate"; break; fi; done; if [ -z "${'$'}TERMUX_PREFIX" ]; then echo "termux prefix not found" >&2; exit 1; fi; export PATH="${'$'}TERMUX_PREFIX/bin:/system/bin:/system/xbin"; if [ -x "${'$'}TERMUX_PREFIX/bin/pkill" ]; then "${'$'}TERMUX_PREFIX/bin/pkill" -f 'codex app-server --listen ws://127.0.0.1:$PORT' >/dev/null 2>&1 || true; else pkill -f 'codex app-server --listen ws://127.0.0.1:$PORT' >/dev/null 2>&1 || true; fi; if ss -ltn 2>/dev/null | grep -q '127.0.0.1:$PORT'; then if [ -x "${'$'}TERMUX_PREFIX/bin/fuser" ]; then "${'$'}TERMUX_PREFIX/bin/fuser" -k -n tcp $PORT >/dev/null 2>&1 || true; fi; fi; exit 0
    """.trimIndent()

    private fun buildBackendStatusDetail(
        termuxInstalled: Boolean,
        rootAvailable: Boolean,
        backendListening: Boolean,
        authPresent: Boolean,
    ): String = when {
        !termuxInstalled -> "未检测到 Termux。"
        !rootAvailable -> "root 不可用或尚未授权。"
        backendListening -> "app-server 已在 127.0.0.1:$PORT 监听。"
        !authPresent -> "Termux 已检测到，但 Codex 登录凭证不存在。"
        else -> "Termux 已登录，但 app-server 未运行。"
    }

    private suspend fun buildBackendFailureDetail(
        prefix: String,
        stdout: String,
        stderr: String,
    ): String {
        val parts = mutableListOf(prefix)
        stdout.trim().takeIf { it.isNotBlank() }?.let { parts += "stdout:\n$it" }
        stderr.trim().takeIf { it.isNotBlank() }?.let { parts += "stderr:\n$it" }
        readBackendLogTail().takeIf { it.isNotBlank() }?.let { parts += "log:\n$it" }
        return parts.joinToString("\n\n")
    }

    private suspend fun readBackendLogTail(): String {
        val result = RootShell.run(
            command = "tail -n 60 ${appServerLogPath()} 2>/dev/null",
            timeoutMillis = 4_000L,
        )
        return result.stdout.trim()
    }

    private fun termuxHomePath(): String = LEGACY_TERMUX_HOME

    private fun termuxPrefixPath(): String = LEGACY_TERMUX_PREFIX

    private fun appServerLogPath(): String = "${termuxHomePath()}/.codex-app-server.log"

    companion object {
        private const val TAG = "CodexRuntime"
        private const val PREFS_NAME = "codex_mobile_prefs"
        private const val PREF_AUTO_HARDENING = "auto_hardening_enabled"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val LEGACY_TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val ALT_TERMUX_HOME = "/data/user/0/com.termux/files/home"
        private const val LEGACY_TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        private const val ALT_TERMUX_PREFIX = "/data/user/0/com.termux/files/usr"
        private const val HOST = "127.0.0.1"
        private const val PORT = 8765
    }
}
