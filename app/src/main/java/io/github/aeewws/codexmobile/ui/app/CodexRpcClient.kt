package io.github.aeewws.codexmobile.ui.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class CodexRpcClient(
    private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val suppressCloseCallback = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    suspend fun connect(
        url: String,
        onRequest: suspend (id: Any, method: String, params: JSONObject) -> Unit,
        onNotification: suspend (method: String, params: JSONObject) -> Unit,
        onClosed: (String?) -> Unit,
    ) {
        close()

        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                if (!opened.isCompleted) {
                    opened.complete(Unit)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text, onRequest, onNotification)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!opened.isCompleted) {
                    opened.completeExceptionally(IllegalStateException(reason.ifBlank { "closed($code)" }))
                }
                handleSocketClosed(reason.ifBlank { "closed($code)" }, onClosed)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = response?.message?.takeIf { it.isNotBlank() } ?: t.message ?: "socket failure"
                if (!opened.isCompleted) {
                    opened.completeExceptionally(IllegalStateException(message))
                }
                handleSocketClosed(message, onClosed)
            }
        }

        client.newWebSocket(request, listener)
        opened.await()
    }

    suspend fun request(method: String, params: JSONObject = JSONObject()): Any? {
        val id = "rpc-${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<Any?>()
        pending[id] = deferred

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        send(payload)
        return deferred.await()
    }

    fun respond(id: Any, result: Any?) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
        send(payload)
    }

    fun isOpen(): Boolean = socket != null

    fun close(notifyClosure: Boolean = false) {
        suppressCloseCallback.set(!notifyClosure)
        val current = socket
        socket = null
        current?.close(1000, "client-close")
        rejectPending("socket closed")
    }

    private fun send(payload: JSONObject) {
        val current = socket ?: error("RPC socket not connected")
        current.send(payload.toString())
    }

    private fun handleIncoming(
        text: String,
        onRequest: suspend (id: Any, method: String, params: JSONObject) -> Unit,
        onNotification: suspend (method: String, params: JSONObject) -> Unit,
    ) {
        val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return

        val hasId = envelope.has("id")
        val hasMethod = envelope.has("method")
        val hasResult = envelope.has("result") || envelope.has("error")

        if (hasId && hasResult && !hasMethod) {
            val id = envelope.get("id").toString()
            val deferred = pending.remove(id) ?: return
            val error = envelope.optJSONObject("error")
            if (error != null) {
                deferred.completeExceptionally(IllegalStateException(error.optString("message").ifBlank { error.toString() }))
            } else {
                deferred.complete(envelope.opt("result"))
            }
            return
        }

        val method = envelope.optString("method")
        if (method.isBlank()) {
            return
        }

        val params = envelope.optJSONObject("params") ?: JSONObject()
        if (hasId) {
            val id = envelope.get("id")
            scope.launch { onRequest(id, method, params) }
            return
        }

        scope.launch { onNotification(method, params) }
    }

    private fun handleSocketClosed(reason: String?, onClosed: (String?) -> Unit) {
        socket = null
        rejectPending(reason ?: "socket closed")
        if (suppressCloseCallback.getAndSet(false)) {
            return
        }
        onClosed(reason)
    }

    private fun rejectPending(message: String) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (_, deferred) = iterator.next()
            iterator.remove()
            if (!deferred.isCompleted) {
                deferred.completeExceptionally(IllegalStateException(message))
            }
        }
    }
}
