package io.github.aeewws.codexmobile.bridge

import android.webkit.JavascriptInterface
import androidx.lifecycle.LifecycleCoroutineScope
import io.github.aeewws.codexmobile.MainActivity
import io.github.aeewws.codexmobile.runtime.CodexRuntimeController
import kotlinx.coroutines.launch
import org.json.JSONObject

class CodexHostBridge(
    private val activity: MainActivity,
    private val runtimeController: CodexRuntimeController,
    private val scope: LifecycleCoroutineScope,
) {
    @JavascriptInterface
    fun postMessage(rawMessage: String) {
        scope.launch {
            val request = JSONObject(rawMessage)
            val id = request.optString("id")
            val method = request.getString("method")
            val payload = request.optJSONObject("payload") ?: JSONObject()

            try {
                val result = when (method) {
                    "ensureBackendRunning" -> runtimeController.ensureBackendRunning()
                    "getBackendStatus" -> runtimeController.getBackendStatus()
                    "restartBackend" -> runtimeController.restartBackend()
                    "runKeepaliveHardening" -> runtimeController.runKeepaliveHardening()
                    "getKeepaliveStatus" -> runtimeController.getKeepaliveStatus()
                    "pickProjectRoot" -> {
                        val path = activity.showProjectRootPicker(runtimeController.defaultProjectRoots())
                        JSONObject().put("path", path)
                    }
                    "openTermux" -> JSONObject().put("opened", runtimeController.openTermux(activity))
                    "requestBatteryOptimizationIgnore" -> JSONObject()
                        .put("opened", runtimeController.requestBatteryOptimizationIgnore(activity))
                    "getAppPreferences" -> runtimeController.getAppPreferences()
                    "setAutoHardeningEnabled" -> runtimeController.setAutoHardeningEnabled(
                        payload.optBoolean("enabled", true),
                    )
                    "setForegroundSessionActive" -> runtimeController.setForegroundSessionActive(
                        payload.optBoolean("active", false),
                    )
                    else -> throw IllegalArgumentException("Unknown bridge method: $method")
                }

                activity.dispatchToJs(id, ok = true, payloadJson = result.toString())
            } catch (t: Throwable) {
                val errorPayload = JSONObject()
                    .put("message", t.message ?: t.javaClass.simpleName)
                    .put("type", t.javaClass.simpleName)
                activity.dispatchToJs(id, ok = false, payloadJson = errorPayload.toString())
            }
        }
    }

    companion object {
        const val INTERFACE_NAME = "CodexHost"
    }
}
