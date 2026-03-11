package com.example.myapplication.ui.app

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.runtime.CodexRuntimeController
import com.example.myapplication.runtime.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CodexMobileViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val runtimeController = CodexRuntimeController(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
    private val rpcClient = CodexRpcClient(viewModelScope)
    private val localDraftsByThread = linkedMapOf<String, MutableList<LocalDraftMessage>>().apply {
        loadPersistedLocalDrafts().forEach { draft ->
            getOrPut(draft.threadId) { mutableListOf() }.add(draft)
        }
    }
    private val archivedThreadIds = loadArchivedThreadIds().toMutableSet()
    private val deletedThreadIds = loadDeletedThreadIds().toMutableSet()
    private val customThreadTitles = loadCustomThreadTitles().toMutableMap()

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<CodexMobileUiState> = _uiState

    private val localHistoryById = linkedMapOf<String, ThreadSummary>()
    private val remoteHistoryById = linkedMapOf<String, ThreadSummary>()
    private val lastUserTextByThread = mutableMapOf<String, String>()

    private var activeThread: MutableThread? = null
    private val runningTurnIds = linkedSetOf<String>()
    private val pendingTurns = linkedMapOf<String, PendingTurn>()
    private val pendingTurnJobs = mutableMapOf<String, Job>()
    private val resumedThreadIds = linkedSetOf<String>()
    private var transientStatusJob: Job? = null
    private var reconnectJob: Job? = null
    private val reconnectMutex = Mutex()

    init {
        sanitizeRestoredState()
    }

    fun onAppResume() {
        viewModelScope.launch {
            if (rpcClient.isOpen()) {
                refreshSupplementalState()
                refreshActiveThreadSnapshot()
                reconcilePersistedDraftsWithBackend()
                syncForegroundService()
            } else {
                recoverAndConnect()
            }
        }
    }

    fun onAppPause() {
        syncForegroundService()
    }

    fun onUiClosed() {
        reconnectJob?.cancel()
        reconnectJob = null
        resumedThreadIds.clear()
        rpcClient.close(notifyClosure = false)
        viewModelScope.launch {
            runCatching { runtimeController.shutdownManagedSession() }
        }
    }

    fun onClearedFromUi() {
        reconnectJob?.cancel()
        resumedThreadIds.clear()
        rpcClient.close(notifyClosure = false)
    }

    fun switchTab(tab: BottomDestination) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun startNewConversation() {
        showNewConversation()
        switchTab(BottomDestination.CHAT)
    }

    fun updateDraft(text: String) {
        _uiState.update { state ->
            state.copy(composer = state.composer.copy(text = text))
        }
    }

    fun sendPrompt() {
        val prompt = _uiState.value.composer.text.trim()
        if (prompt.isEmpty()) {
            return
        }

        viewModelScope.launch {
            runCatching {
                startTurn(prompt)
            }.onFailure { error ->
                setError(error.message ?: "发送失败")
            }
        }
    }

    fun retryLocalDraft(localMessageId: String) {
        val draft = findLocalDraft(localMessageId) ?: return
        updateLocalDraftStatus(localMessageId, LocalMessageStatus.PENDING)
        viewModelScope.launch {
            runCatching {
                startTurn(
                    prompt = draft.text,
                    targetThreadId = draft.threadId,
                    localDraftId = draft.id,
                    clearDraft = false,
                )
            }.onFailure { error ->
                setError(error.message ?: "重发失败")
            }
        }
    }

    fun continueGeneration() {
        viewModelScope.launch {
            runCatching {
                ensureConnected()
                val threadId = _uiState.value.activeThreadId ?: error("还没有活动会话")
                val runningTurnId = latestRunningTurnId()
                if (!runningTurnId.isNullOrBlank()) {
                    rpcClient.request(
                        method = "turn/steer",
                        params = JSONObject()
                            .put("threadId", threadId)
                            .put("expectedTurnId", runningTurnId)
                            .put("input", buildTextInput("继续上一段输出。")),
                    )
                    clearStatus()
                    refreshComposerActivity()
                } else {
                    startTurn("继续上一段输出。")
                }
            }.onFailure { error ->
                setError(error.message ?: "继续失败")
            }
        }
    }

    fun retryLastPrompt() {
        viewModelScope.launch {
            runCatching {
                val threadId = _uiState.value.activeThreadId ?: error("还没有活动会话")
                val lastText = lastUserTextByThread[threadId]?.trim().orEmpty()
                if (lastText.isEmpty()) {
                    error("没有找到上一条用户消息")
                }
                startTurn(lastText)
            }.onFailure { error ->
                setError(error.message ?: "重试失败")
            }
        }
    }

    fun interruptTurn() {
        val threadId = _uiState.value.activeThreadId ?: return
        val turnId = latestRunningTurnId() ?: return
        viewModelScope.launch {
            runCatching {
                ensureConnected()
                rpcClient.request(
                    method = "turn/interrupt",
                    params = JSONObject()
                        .put("threadId", threadId)
                        .put("turnId", turnId),
                )
                noteTurnFinished(turnId)
                clearStatus()
                syncForegroundService()
            }.onFailure { error ->
                setError(error.message ?: "停止失败")
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            if (!rpcClient.isOpen()) {
                recoverAndConnect(forceReconnect = true, manual = true)
                return@launch
            }

            runCatching {
                val latestBackend = runtimeController.getBackendStatus().toStatusSnapshot()
                applyBackendStatus(latestBackend)
                refreshSupplementalState()
                if (!latestBackend.backendListening) {
                    error("本机后端未启动")
                }
                loadRemoteData()
                clearStatus()
                setError("")
            }.onFailure { error ->
                if (error.isInternalCancellation()) {
                    Log.d(TAG, "refreshNow ignored internal error: ${error.message}")
                    return@onFailure
                }
                Log.d(TAG, "refreshNow fallback to reconnect: ${error.message}")
                recoverAndConnect(forceReconnect = true, manual = true)
            }
        }
    }

    fun selectModel(modelId: String) {
        _uiState.update { state ->
            val selectedModel = state.availableModels.find { it.id == modelId }
            state.copy(
                selectedModel = modelId,
                selectedReasoning = selectedModel?.let { chooseReasoning(state.availableModels, modelId, state.selectedReasoning) }
                    ?: state.selectedReasoning,
            )
        }
        persistPreferences()
    }

    fun selectReasoningEffort(effort: String) {
        _uiState.update { state -> state.copy(selectedReasoning = effort) }
        persistPreferences()
    }

    fun selectFastMode(enabled: Boolean) {
        _uiState.update { state -> state.copy(fastModeEnabled = enabled) }
        if (!enabled) {
            setError("")
        }
        persistPreferences()
    }

    fun selectPermissionMode(mode: PermissionMode) {
        _uiState.update { state -> state.copy(permissionMode = mode) }
        persistPreferences()
    }

    fun selectFontScale(option: FontScaleOption) {
        _uiState.update { state -> state.copy(fontScale = option) }
        persistPreferences()
    }

    fun openHistoryThread(threadId: String) {
        viewModelScope.launch {
            runCatching {
                loadThreadIntoChat(threadId, switchToChat = true, showStatusHint = true)
                _uiState.update { it.copy(activeTab = BottomDestination.CHAT) }
            }.onFailure { error ->
                val fallback = allMergedHistory().firstOrNull { it.id == threadId }
                if (fallback != null) {
                    activeThread = null
                    resetTurnTracking()
                    persistThreadSelection(threadId, fallback.title)
                    _uiState.update { state ->
                        state.copy(
                            activeThreadId = threadId,
                            activeThreadTitle = fallback.title,
                            messages = emptyList(),
                            activeTab = BottomDestination.CHAT,
                        )
                    }
                }
                setError(error.message ?: "读取历史会话失败")
            }
        }
    }

    fun archiveHistoryThread(threadId: String) {
        viewModelScope.launch {
            runCatching {
                ensureConnected()
                rpcClient.request(
                    method = "thread/archive",
                    params = JSONObject().put("threadId", threadId),
                )
                hideArchivedThread(threadId)
                if (_uiState.value.activeThreadId == threadId) {
                    showNewConversation()
                } else {
                    publishMergedHistory()
                }
                setError("")
                showTransientStatus("已归档，会话已从历史列表隐藏")
            }.onFailure { error ->
                if (error.message.orEmpty().contains("no rollout found for thread id", ignoreCase = true)) {
                    hideArchivedThread(threadId)
                    if (_uiState.value.activeThreadId == threadId) {
                        showNewConversation()
                    } else {
                        publishMergedHistory()
                    }
                    setError("")
                    showTransientStatus("已本地归档，会话已从历史列表隐藏")
                } else {
                    setError(error.message ?: "归档会话失败")
                }
            }
        }
    }

    fun archiveCurrentConversation() {
        val threadId = _uiState.value.activeThreadId
        if (threadId.isNullOrBlank() || isLocalConversationId(threadId)) {
            setError("当前没有可归档的历史会话")
            return
        }
        archiveHistoryThread(threadId)
    }

    fun unarchiveHistoryThread(threadId: String) {
        viewModelScope.launch {
            runCatching {
                ensureConnected()
                rpcClient.request(
                    method = "thread/unarchive",
                    params = JSONObject().put("threadId", threadId),
                )
                showArchivedThread(threadId)
                publishMergedHistory()
                setError("")
                showTransientStatus("已恢复，会话重新回到历史列表")
            }.onFailure { error ->
                if (error.message.orEmpty().contains("no archived rollout found for thread id", ignoreCase = true)) {
                    showArchivedThread(threadId)
                    publishMergedHistory()
                    setError("")
                    showTransientStatus("已本地恢复，会话重新回到历史列表")
                } else {
                    setError(error.message ?: "恢复归档会话失败")
                }
            }
        }
    }

    fun deleteHistoryThread(threadId: String) {
        if (threadId.isBlank()) {
            return
        }
        viewModelScope.launch {
            runCatching {
                val deletedFiles = physicallyDeleteThreadArtifacts(threadId)
                deletedThreadIds += threadId
                archivedThreadIds.remove(threadId)
                customThreadTitles.remove(threadId)
                persistDeletedThreadIds()
                persistArchivedThreadIds()
                persistCustomThreadTitles()
                localHistoryById.remove(threadId)
                remoteHistoryById.remove(threadId)
                if (_uiState.value.activeThreadId == threadId || activeThread?.id == threadId) {
                    showNewConversation()
                } else {
                    publishMergedHistory()
                }
                setError("")
                showTransientStatus(
                    if (deletedFiles > 0) {
                        "已彻底删除，会话文件已从本机移除"
                    } else {
                        "已从当前列表删除，但没有找到对应会话文件"
                    },
                )
            }.onFailure { error ->
                setError(error.message ?: "彻底删除会话失败")
            }
        }
    }

    fun renameHistoryThread(threadId: String, rawTitle: String) {
        val title = rawTitle.trim()
        if (threadId.isBlank()) {
            return
        }
        if (title.isBlank()) {
            setError("会话名称不能为空")
            return
        }
        customThreadTitles[threadId] = title
        persistCustomThreadTitles()
        activeThread = activeThread?.let { thread ->
            if (thread.id == threadId) thread.copy(customTitle = title) else thread
        }
        if (_uiState.value.activeThreadId == threadId) {
            persistThreadSelection(threadId, title)
        }
        publishMergedHistory()
        if (_uiState.value.activeThreadId == threadId) {
            publishActiveThread()
        }
        setError("")
        showTransientStatus("已重命名")
    }

    fun answerApproval(accept: Boolean) {
        val approval = _uiState.value.activeApproval ?: return
        viewModelScope.launch {
            runCatching {
                val result = JSONObject().put("decision", if (accept) "accept" else "decline")
                rpcClient.respond(approval.requestIdRaw, result)
                _uiState.update { it.copy(activeApproval = null) }
                syncForegroundService()
            }.onFailure { error ->
                setError(error.message ?: "处理确认失败")
            }
        }
    }

    fun openTermux(activity: Activity) {
        runtimeController.openTermux(activity)
    }

    override fun onCleared() {
        onClearedFromUi()
        super.onCleared()
    }

    private suspend fun startTurn(
        prompt: String,
        targetThreadId: String? = null,
        localDraftId: String? = null,
        clearDraft: Boolean = true,
    ) {
        setError("")
        val normalizedTargetThreadId = targetThreadId?.takeUnless(::isLocalConversationId)
        var attachedDraftId = localDraftId
        val conversationKey = normalizedTargetThreadId ?: currentDraftThreadId()
        if (attachedDraftId == null) {
            attachedDraftId = appendLocalDraft(
                conversationKey,
                prompt,
            ).id
        }
        val pendingTurn = registerPendingTurn(
            localDraftId = attachedDraftId,
            prompt = prompt,
            conversationKey = conversationKey,
            clearDraft = clearDraft,
        )

        try {
            ensureConnected()
            val threadId = ensureThreadForChat(normalizedTargetThreadId)
            attachedDraftId?.let { moveLocalDraftToThread(it, threadId) }
            bindPendingTurnToThread(pendingTurn.requestId, threadId)
            lastUserTextByThread[threadId] = prompt.trim()
            val response = performTurnStartWithResumeFallback(threadId, prompt) as? JSONObject
            markPendingTurnRunning(
                requestId = pendingTurn.requestId,
                threadId = threadId,
                turnId = response
                    ?.optJSONObject("turn")
                    ?.optString("id")
                    ?.takeIf { it.isNotBlank() },
            )
            syncForegroundService()
        } catch (error: Throwable) {
            val failedThreadId = normalizedTargetThreadId ?: pendingTurns[pendingTurn.requestId]?.threadId
            failPendingTurn(pendingTurn.requestId)
            if (isMissingThreadError(error.message)) {
                if (!failedThreadId.isNullOrBlank()) {
                    preserveThreadAfterWriteFailure(failedThreadId)
                    return
                }
            }
            throw error
        }
    }

    private suspend fun recoverAndConnect(
        forceReconnect: Boolean = false,
        manual: Boolean = false,
    ) {
        reconnectJob?.cancel()
        reconnectJob = null

        reconnectMutex.withLock {
            try {
                Log.d(TAG, "recoverAndConnect enter forceReconnect=$forceReconnect manual=$manual rpcOpen=${rpcClient.isOpen()}")
                setError("")
                if (!rpcClient.isOpen() || forceReconnect || manual) {
                    setConnection(
                        code = if (forceReconnect || manual) "reconnecting" else "starting",
                        title = if (forceReconnect || manual) "正在恢复连接" else "正在读取本机状态",
                        detail = if (forceReconnect || manual) {
                            "App 正在重新检查 root、后端和认证状态。"
                        } else {
                            "正在读取 Termux、root、后端和认证状态。"
                        },
                        tone = BannerTone.INFO,
                    )
                }

                Log.d(TAG, "recoverAndConnect reapplyHardeningIfEnabled start")
                runtimeController.reapplyHardeningIfEnabled()
                Log.d(TAG, "recoverAndConnect reapplyHardeningIfEnabled done")

                var latestBackend = runtimeController.getBackendStatus().toStatusSnapshot()
                Log.d(TAG, "recoverAndConnect backend status listening=${latestBackend.backendListening} auth=${latestBackend.authPresent} root=${latestBackend.rootAvailable} termux=${latestBackend.termuxInstalled}")
                applyBackendStatus(latestBackend)
                refreshSupplementalState()

                if (latestBackend.termuxInstalled && latestBackend.rootAvailable && !latestBackend.backendListening) {
                    Log.d(TAG, "recoverAndConnect ensureBackendRunning start")
                    latestBackend = runtimeController.ensureBackendRunning().toStatusSnapshot()
                    Log.d(TAG, "recoverAndConnect ensureBackendRunning done listening=${latestBackend.backendListening} detail=${latestBackend.backendStatusDetail}")
                    applyBackendStatus(latestBackend)
                    refreshSupplementalState()
                }

                if (!latestBackend.backendListening) {
                    Log.d(TAG, "recoverAndConnect abort because backend still not listening")
                    _uiState.update { state ->
                        state.copy(connection = deriveConnectionBanner(latestBackend, state.connection.code))
                    }
                    syncForegroundService()
                    return
                }

                if (!forceReconnect && rpcClient.isOpen()) {
                    Log.d(TAG, "recoverAndConnect backend healthy and rpc already open")
                    _uiState.update { state ->
                        state.copy(
                            status = state.status.copy(
                                backendListening = true,
                                authPresent = true,
                            ),
                            connection = ConnectionBanner(
                                code = "connected",
                                title = "Codex 后端已连接",
                                detail = "模型、历史会话和流式回复都来自本机后端。",
                                tone = BannerTone.OK,
                            ),
                        )
                    }
                    syncForegroundService()
                    return
                }

                Log.d(TAG, "recoverAndConnect connectRpc start")
                connectRpc(forceReconnect)
                Log.d(TAG, "recoverAndConnect connectRpc done")
            } catch (error: Throwable) {
                if (error is CancellationException || error.isInternalCancellation()) {
                    Log.d(TAG, "recoverAndConnect ignored cancellation-like error: ${error.message}")
                    return
                }
                Log.d(TAG, "recoverAndConnect failed: ${error.message}", error)
                setError(error.message ?: "恢复连接失败")
            }
        }
    }

    private fun refreshSupplementalState() {
        viewModelScope.launch {
            runCatching {
                runtimeController.getKeepaliveStatus().toKeepaliveSnapshot()
            }.onSuccess { keepalive ->
                _uiState.update { state -> state.copy(keepalive = keepalive) }
            }.onFailure { error ->
                Log.d(TAG, "refreshSupplementalState keepalive failed: ${error.message}")
            }

            runCatching {
                runtimeController.getLocalThreadIndex()
            }.onSuccess { localHistory ->
                mergeLocalHistory(localHistory)
            }.onFailure { error ->
                Log.d(TAG, "refreshSupplementalState localHistory failed: ${error.message}")
            }
        }
    }

    private suspend fun connectRpc(forceReconnect: Boolean) {
        if (forceReconnect) {
            resumedThreadIds.clear()
            rpcClient.close(notifyClosure = false)
        }
        if (rpcClient.isOpen()) {
            return
        }

        setConnection(
            code = if (forceReconnect) "reconnecting" else "starting",
            title = "正在连接本机 Codex",
            detail = "App 正在建立与 app-server 的本地连接。",
            tone = BannerTone.INFO,
        )

        rpcClient.connect(
            url = RPC_URL,
            onRequest = ::handleRpcRequest,
            onNotification = ::handleRpcNotification,
            onClosed = { reason ->
                resumedThreadIds.clear()
                _uiState.update { state ->
                    state.copy(
                        connection = deriveConnectionBanner(state.status, "reconnecting").copy(
                            code = "reconnecting",
                            title = "连接已断开，正在恢复",
                            detail = reason ?: "本地连接已关闭，准备重新恢复。",
                            tone = BannerTone.WARN,
                        ),
                    )
                }
                scheduleReconnect()
            },
        )

        val initResult = rpcClient.request(
            method = "initialize",
            params = JSONObject()
                .put("clientInfo", JSONObject().put("name", "Codex Mobile").put("version", "0.2.0"))
                .put("capabilities", JSONObject().put("experimentalApi", true)),
        ) as? JSONObject

        loadRemoteData()

        _uiState.update { state ->
            state.copy(
                status = state.status.copy(
                    backendListening = true,
                    authPresent = true,
                ),
                connection = ConnectionBanner(
                    code = "connected",
                    title = "Codex 后端已连接",
                    detail = initResult?.optString("userAgent").orEmpty().ifBlank {
                        "模型、历史会话和流式回复都来自本机 Codex 后端。"
                    },
                    tone = BannerTone.OK,
                ),
            )
        }
        syncForegroundService()
    }

    private suspend fun ensureConnected() {
        if (!rpcClient.isOpen()) {
            recoverAndConnect(forceReconnect = true)
        }
        if (!rpcClient.isOpen()) {
            error("本机后端仍未连接")
        }
    }

    private suspend fun loadRemoteData() {
        val modelResponse = rpcClient.request("model/list", JSONObject()) as? JSONObject
        val threadResponse = rpcClient.request(
            "thread/list",
            JSONObject().put("limit", THREAD_LIMIT),
        ) as? JSONObject
        val configResponse = runCatching {
            rpcClient.request(
                "config/read",
                JSONObject()
                    .put("includeLayers", false)
                    .put("cwd", TERMUX_HOME),
            ) as? JSONObject
        }.getOrNull()

        val models = parseModels(modelResponse?.optJSONArray("data"))
        val remoteThreads = parseThreadSummaries(threadResponse?.optJSONArray("data"))
        val config = configResponse?.optJSONObject("config")

        mergeRemoteHistory(remoteThreads)

        val remoteModelPreference = config?.optStringOrNull("model")
        val preferredModel = chooseBestModel(models, _uiState.value.selectedModel, remoteModelPreference)
        val preferredReasoning = chooseReasoning(models, preferredModel, _uiState.value.selectedReasoning)
        val preferredFastMode = _uiState.value.fastModeEnabled

        _uiState.update { state ->
            state.copy(
                availableModels = models,
                selectedModel = preferredModel,
                selectedReasoning = preferredReasoning,
                fastModeEnabled = preferredFastMode,
            )
        }

        persistPreferences()
        val restoreThreadId = _uiState.value.activeThreadId
        if (restoreThreadId.isNullOrBlank()) {
            showNewConversation()
        } else if (archivedThreadIds.contains(restoreThreadId)) {
            showNewConversation()
        } else if (isLocalConversationId(restoreThreadId)) {
            showNewConversation(restoreThreadId)
        } else {
            runCatching {
                loadThreadIntoChat(restoreThreadId)
            }.onFailure { error ->
                Log.d(TAG, "loadRemoteData restore thread failed: ${error.message}")
                showNewConversation()
            }
        }

        reconcilePersistedDraftsWithBackend()
    }

    private suspend fun handleRpcRequest(id: Any, method: String, params: JSONObject) {
        when (method) {
            "item/commandExecution/requestApproval" -> handleApprovalRequest(
                id = id,
                kind = "command",
                title = "命令执行需要确认",
                subtitle = params.optString("command").ifBlank { "命令详情" },
                body = buildApprovalBody(params, params.optString("command")),
            )

            "item/fileChange/requestApproval" -> handleApprovalRequest(
                id = id,
                kind = "file",
                title = "文件改动需要确认",
                subtitle = "这次回复准备写入文件",
                body = buildApprovalBody(params, params.optString("summary")),
            )

            else -> rpcClient.respond(id, JSONObject())
        }
    }

    private fun handleApprovalRequest(
        id: Any,
        kind: String,
        title: String,
        subtitle: String,
        body: String,
    ) {
        when (_uiState.value.permissionMode) {
            PermissionMode.FULL_ACCESS -> rpcClient.respond(id, JSONObject().put("decision", "accept"))
            PermissionMode.DEFAULT_DENY -> rpcClient.respond(id, JSONObject().put("decision", "decline"))
            PermissionMode.ASK_EACH_TIME -> {
                _uiState.update { state ->
                    state.copy(
                        activeApproval = ApprovalRequestUi(
                            requestId = id.toString(),
                            requestIdRaw = id,
                            title = title,
                            subtitle = subtitle,
                            body = body,
                            kind = kind,
                        ),
                        activeTab = BottomDestination.CHAT,
                    )
                }
                syncForegroundService()
            }
        }
    }

    private suspend fun handleRpcNotification(method: String, params: JSONObject) {
        when (method) {
            "thread/started" -> {
                val thread = params.optJSONObject("thread") ?: return
                mergeRemoteHistory(parseThreadSummaries(JSONArray().put(thread)))
            }

            "thread/archived" -> {
                val threadId = params.optString("threadId").ifBlank {
                    params.optJSONObject("thread")?.optString("id").orEmpty()
                }
                if (threadId.isBlank()) return
                hideArchivedThread(threadId)
                if (_uiState.value.activeThreadId == threadId) {
                    showNewConversation()
                } else {
                    publishMergedHistory()
                }
            }

            "thread/status/changed" -> {
                val threadId = params.optString("threadId").ifBlank { return }
                updateThreadStatus(threadId, params.optString("status"))
            }

            "turn/started" -> {
                val turnId = params.optJSONObject("turn")?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                    ?: params.optString("turnId").takeIf { it.isNotBlank() }
                val threadId = params.optString("threadId").takeIf { it.isNotBlank() }
                    ?: params.optJSONObject("turn")?.optString("threadId")?.takeIf { it.isNotBlank() }
                noteTurnStarted(turnId = turnId, threadId = threadId)
                syncForegroundService()
            }

            "turn/completed" -> {
                val turnId = params.optString("turnId").takeIf { it.isNotBlank() }
                    ?: params.optJSONObject("turn")?.optString("id")?.takeIf { it.isNotBlank() }
                val threadId = params.optString("threadId").takeIf { it.isNotBlank() }
                    ?: params.optJSONObject("turn")?.optString("threadId")?.takeIf { it.isNotBlank() }
                noteTurnFinished(turnId = turnId, threadId = threadId)
                if (!threadId.isNullOrBlank() && threadId == _uiState.value.activeThreadId) {
                    runCatching {
                        loadThreadIntoChat(threadId)
                    }.onFailure { error ->
                        Log.d(TAG, "turn/completed reload failed: ${error.message}")
                        markThreadPendingDraftsFailed(threadId)
                        publishActiveThread()
                    }
                } else {
                    clearStatus()
                }
                syncForegroundService()
            }

            "item/started",
            "item/completed" -> {
                val threadId = params.optString("threadId")
                val turnId = params.optString("turnId")
                markPendingTurnRunning(threadId = threadId.takeIf { it.isNotBlank() }, turnId = turnId.takeIf { it.isNotBlank() })
                val item = params.optJSONObject("item") ?: return
                upsertTurnItem(threadId, turnId, item)
            }

            "item/agentMessage/delta" -> updateTurnDelta(
                threadId = params.optString("threadId"),
                turnId = params.optString("turnId"),
                itemId = params.optString("itemId"),
                type = "agentMessage",
                delta = params.optString("delta"),
            )

            "item/commandExecution/outputDelta" -> updateTurnDelta(
                threadId = params.optString("threadId"),
                turnId = params.optString("turnId"),
                itemId = params.optString("itemId"),
                type = "commandExecution",
                delta = params.optString("delta"),
            )

            "item/fileChange/outputDelta" -> updateTurnDelta(
                threadId = params.optString("threadId"),
                turnId = params.optString("turnId"),
                itemId = params.optString("itemId"),
                type = "fileChange",
                delta = params.optString("delta"),
            )

            "turn/plan/updated" -> {
                val threadId = params.optString("threadId")
                val turnId = params.optString("turnId")
                val text = buildPlanText(params.optString("explanation"), params.optJSONArray("plan"))
                upsertSyntheticItem(
                    threadId = threadId,
                    turnId = turnId,
                    itemId = "plan-$turnId",
                    type = "plan",
                    primary = text,
                )
            }

            "serverRequest/resolved" -> {
                _uiState.update { it.copy(activeApproval = null) }
                syncForegroundService()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) {
            return
        }
        reconnectJob = viewModelScope.launch {
            delay(1_500L)
            recoverAndConnect(forceReconnect = true)
        }
    }

    private suspend fun loadThreadIntoChat(
        threadId: String,
        switchToChat: Boolean = false,
        showStatusHint: Boolean = false,
    ) {
        if (showStatusHint) {
            setStatus("正在读取历史会话…")
        }
        ensureConnected()
        val response = rpcClient.request(
            method = "thread/read",
            params = JSONObject()
                .put("threadId", threadId)
                .put("includeTurns", true),
        ) as? JSONObject ?: error("线程读取返回为空")
        val threadJson = response.optJSONObject("thread") ?: error("缺少线程数据")
        activeThread = parseThread(threadJson)
        resetTurnTracking(activeThread?.runningTurnIds().orEmpty())
        reconcileDraftsWithThread(activeThread)
        publishActiveThread()
        mergeRemoteHistory(
            listOf(
                ThreadSummary(
                    id = activeThread!!.id,
                    title = activeThread!!.displayName,
                    updatedAtLabel = formatEpoch(activeThread!!.updatedAtEpochSeconds),
                    updatedAtEpochSeconds = activeThread!!.updatedAtEpochSeconds,
                    source = "remote",
                ),
            ),
        )
        if (switchToChat) {
            _uiState.update { it.copy(activeTab = BottomDestination.CHAT) }
        }
        clearStatus()
        syncForegroundService()
    }

    private suspend fun ensureThreadForChat(preferredThreadId: String? = null): String {
        val existing = preferredThreadId
            ?: activeThread?.id
            ?: _uiState.value.activeThreadId?.takeUnless(::isLocalConversationId)
        if (!existing.isNullOrBlank()) {
            return ensureExistingThreadReadyForWrite(existing)
        }

        val response = rpcClient.request(
            method = "thread/start",
            params = JSONObject()
                .put("model", _uiState.value.selectedModel)
                .put("modelProvider", "openai")
                .put("cwd", effectiveWorkingDirectory())
                .put("approvalPolicy", _uiState.value.permissionMode.approvalPolicy)
                .put("sandbox", _uiState.value.permissionMode.sandboxCliValue)
                .put("persistExtendedHistory", true)
                .apply {
                    currentServiceTierOrNull()?.let { put("serviceTier", it) }
                },
        ) as? JSONObject ?: error("创建线程失败")

        val threadJson = response.optJSONObject("thread") ?: error("创建线程结果为空")
        activeThread = parseThread(threadJson)
        resumedThreadIds += activeThread!!.id
        publishActiveThread()
        mergeRemoteHistory(parseThreadSummaries(JSONArray().put(threadJson)))
        return activeThread!!.id
    }

    private suspend fun ensureExistingThreadReadyForWrite(
        threadId: String,
        forceResume: Boolean = false,
    ): String {
        if (!forceResume && resumedThreadIds.contains(threadId)) {
            return threadId
        }

        Log.d(TAG, "ensureExistingThreadReadyForWrite resume thread=$threadId force=$forceResume")
        val response = rpcClient.request(
            method = "thread/resume",
            params = JSONObject()
                .put("threadId", threadId)
                .put("model", _uiState.value.selectedModel)
                .put("modelProvider", "openai")
                .put("cwd", effectiveWorkingDirectory(threadId))
                .put("approvalPolicy", _uiState.value.permissionMode.approvalPolicy)
                .put("sandbox", _uiState.value.permissionMode.sandboxCliValue)
                .apply {
                    currentServiceTierOrNull()?.let { put("serviceTier", it) }
                },
        ) as? JSONObject ?: error("恢复线程失败")

        response.optJSONObject("thread")?.let { threadJson ->
            val resumedThread = parseThread(threadJson)
            mergeRemoteHistory(
                listOf(
                    ThreadSummary(
                        id = resumedThread.id,
                        title = resumedThread.displayName,
                        updatedAtLabel = formatEpoch(resumedThread.updatedAtEpochSeconds),
                        updatedAtEpochSeconds = resumedThread.updatedAtEpochSeconds,
                        source = "remote",
                    ),
                ),
            )
            if (_uiState.value.activeThreadId == threadId || activeThread?.id == threadId) {
                activeThread = resumedThread
                resetTurnTracking(resumedThread.runningTurnIds())
                reconcileDraftsWithThread(resumedThread)
                publishActiveThread()
            } else {
                reconcileDraftsWithThread(resumedThread)
            }
        }

        resumedThreadIds += threadId
        return threadId
    }

    private suspend fun performTurnStartWithResumeFallback(threadId: String, prompt: String): Any? {
        return try {
            performTurnStart(threadId, prompt)
        } catch (error: Throwable) {
            if (!isMissingThreadError(error.message)) {
                throw error
            }
            Log.d(TAG, "turn/start missing thread for $threadId, force resume and retry")
            resumedThreadIds.remove(threadId)
            ensureExistingThreadReadyForWrite(threadId, forceResume = true)
            performTurnStart(threadId, prompt)
        }
    }

    private suspend fun performTurnStart(threadId: String, prompt: String): Any? =
        rpcClient.request(
            method = "turn/start",
            params = JSONObject()
                .put("threadId", threadId)
                .put("input", buildTextInput(prompt))
                .put("model", _uiState.value.selectedModel)
                .put("modelReasoningEffort", _uiState.value.selectedReasoning)
                .put("approvalPolicy", _uiState.value.permissionMode.approvalPolicy)
                .put("sandboxPolicy", buildSandboxPolicy(_uiState.value.permissionMode))
                .put("cwd", effectiveWorkingDirectory(threadId))
                .apply {
                    currentServiceTierOrNull()?.let { put("serviceTier", it) }
                },
        )

    private fun showNewConversation(localConversationId: String = newLocalConversationId()) {
        activeThread = null
        resetTurnTracking()
        persistThreadSelection(
            activeThreadId = localConversationId,
            activeThreadTitle = "新会话",
            rememberLastOpened = false,
        )
        val messages = buildChatItems(
            thread = null,
            conversationKey = localConversationId,
        )
        _uiState.update { state ->
            state.copy(
                activeThreadId = localConversationId,
                activeThreadTitle = "新会话",
                messages = messages,
            )
        }
        setError("")
        clearStatus()
        syncForegroundService()
    }

    private fun applyRuntimeState(status: StatusSnapshot, keepalive: KeepaliveSnapshot) {
        _uiState.update { state ->
            state.copy(
                status = status,
                keepalive = keepalive,
                connection = deriveConnectionBanner(status, state.connection.code),
            )
        }
    }

    private fun applyBackendStatus(status: StatusSnapshot) {
        val effectiveStatus = if (rpcClient.isOpen()) {
            status.copy(
                backendListening = true,
                authPresent = true,
                backendStatusDetail = "app-server 已在 127.0.0.1:8765 监听。",
            )
        } else {
            status
        }
        _uiState.update { state ->
            state.copy(
                status = effectiveStatus,
                connection = deriveConnectionBanner(effectiveStatus, state.connection.code),
            )
        }
    }

    private fun mergeLocalHistory(index: JSONArray) {
        localHistoryById.clear()
        for (i in 0 until index.length()) {
            val item = index.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { continue }
            val updatedAt = item.optEpochSeconds("updated_at", "updatedAt")
            localHistoryById[id] = applyCustomTitle(
                ThreadSummary(
                    id = id,
                    title = displayThreadName(
                        item.optString("thread_name"),
                        item.optString("preview"),
                        threadId = id,
                    ),
                    updatedAtLabel = formatEpoch(updatedAt),
                    updatedAtEpochSeconds = updatedAt,
                    source = "local",
                ),
            )
        }
        publishMergedHistory()
    }

    private fun mergeRemoteHistory(items: List<ThreadSummary>) {
        items.forEach { summary ->
            val local = localHistoryById[summary.id]
            remoteHistoryById[summary.id] = applyCustomTitle(
                summary.copy(
                    title = if (summary.title == "新会话") local?.title ?: summary.title else summary.title,
                    updatedAtEpochSeconds = summary.updatedAtEpochSeconds ?: local?.updatedAtEpochSeconds,
                    updatedAtLabel = formatEpoch(summary.updatedAtEpochSeconds ?: local?.updatedAtEpochSeconds),
                ),
            )
        }
        publishMergedHistory()
    }

    private fun allMergedHistory(): List<ThreadSummary> {
        val merged = linkedMapOf<String, ThreadSummary>()
        localHistoryById.values.forEach { merged[it.id] = applyCustomTitle(it) }
        remoteHistoryById.values.forEach { merged[it.id] = applyCustomTitle(it) }
        return merged.values
            .sortedByDescending { it.updatedAtEpochSeconds ?: 0L }
    }

    private fun publishMergedHistory() {
        val merged = allMergedHistory()
            .filterNot { deletedThreadIds.contains(it.id) }
        _uiState.update { state ->
            state.copy(
                historyThreads = merged.filterNot { archivedThreadIds.contains(it.id) },
                archivedThreads = merged.filter { archivedThreadIds.contains(it.id) },
            )
        }
    }

    private fun publishActiveThread() {
        val thread = activeThread
        val items = buildChatItems(thread = thread)
        if (thread != null) {
            persistThreadSelection(thread.id, thread.displayName)
        }
        _uiState.update { state ->
            state.copy(
                activeThreadId = thread?.id,
                activeThreadTitle = thread?.displayName ?: "新会话",
                messages = items,
            )
        }
        syncForegroundService()
    }

    private fun buildChatItems(
        thread: MutableThread?,
        conversationKey: String = thread?.id ?: _uiState.value.activeThreadId ?: LOCAL_DRAFT_THREAD_ID,
    ): List<ChatItemUi> {
        val items = thread?.flattenToUiItems().orEmpty().toMutableList()
        draftsForThread(conversationKey)
            .sortedBy { it.createdAt }
            .forEachIndexed { index, draft ->
                if (shouldSuppressLocalDraft(thread, conversationKey, draft)) {
                    return@forEachIndexed
                }
                items += ChatItemUi.LocalUser(
                    id = draft.id,
                    sortKey = "zz-local-${draft.createdAt.toString().padStart(16, '0')}-$index",
                    text = draft.text,
                    status = draft.status,
                )
            }
        if (shouldShowGeneratingPlaceholder(thread = thread, conversationKey = conversationKey)) {
            items += ChatItemUi.AgentPlaceholder(
                id = "agent-placeholder",
                sortKey = "zzzz-agent-placeholder",
                text = "Codex 正在生成…",
            )
        }
        return items
    }

    private fun shouldShowGeneratingPlaceholder(
        thread: MutableThread?,
        conversationKey: String,
    ): Boolean {
        val activePending = activePendingTurnsForConversation(conversationKey)
        val hasPending = activePending.isNotEmpty()
        val hasRunning = thread?.runningTurnIds()?.isNotEmpty() == true || (thread != null && runningTurnIds.isNotEmpty())
        if (!hasPending && !hasRunning) {
            return false
        }
        if (thread == null) {
            return true
        }
        if (hasPending) {
            return activePending.any { pending ->
                val matchedTurn = findLatestTurnForPrompt(thread, pending.prompt)
                matchedTurn?.let(::turnHasVisibleAssistantOutput) != true
            }
        }
        val latestRunningTurn = thread.turns.lastOrNull { it.isRunning() }
        return latestRunningTurn?.let(::turnHasVisibleAssistantOutput)?.not() ?: true
    }

    private fun shouldSuppressLocalDraft(
        thread: MutableThread?,
        conversationKey: String,
        draft: LocalDraftMessage,
    ): Boolean {
        if (thread == null || draft.status != LocalMessageStatus.PENDING) {
            return false
        }
        val pending = activePendingTurnsForConversation(conversationKey)
            .firstOrNull { it.localDraftId == draft.id }
            ?: return false
        val threadUpdatedAtMillis = (thread.updatedAtEpochSeconds ?: 0L) * 1000L
        return findLatestTurnForPrompt(thread, draft.text) != null &&
            threadUpdatedAtMillis + THREAD_TIMESTAMP_SKEW_MS >= pending.createdAt
    }

    private fun updateThreadStatus(threadId: String, status: String) {
        val thread = activeThread ?: return
        if (thread.id != threadId) {
            return
        }
        activeThread = thread.copy(status = status.ifBlank { thread.status })
        publishActiveThread()
    }

    private fun upsertTurnItem(threadId: String, turnId: String, item: JSONObject) {
        val parsedItem = parseItem(item)
        if (parsedItem.type == "userMessage" && parsedItem.primaryText.isNotBlank()) {
            lastUserTextByThread[threadId] = parsedItem.primaryText
            reconcilePendingDraft(threadId, parsedItem.primaryText)?.let { draftId ->
                onPendingDraftConfirmed(threadId, draftId)
            }
        } else if (parsedItem.type in BACKEND_OUTPUT_TYPES) {
            markPendingTurnRunning(
                threadId = threadId.takeIf { it.isNotBlank() },
                turnId = turnId.takeIf { it.isNotBlank() },
            )
        }

        val thread = activeThread ?: return
        if (thread.id != threadId) {
            return
        }
        val turn = thread.ensureTurn(turnId)
        turn.upsert(parsedItem)
        publishActiveThread()
    }

    private fun upsertSyntheticItem(
        threadId: String,
        turnId: String,
        itemId: String,
        type: String,
        primary: String,
    ) {
        val thread = activeThread ?: return
        if (thread.id != threadId) {
            return
        }
        val turn = thread.ensureTurn(turnId)
        turn.upsert(
            MutableItem(
                id = itemId,
                type = type,
                primaryText = primary,
            ),
        )
        publishActiveThread()
    }

    private fun updateTurnDelta(
        threadId: String,
        turnId: String,
        itemId: String,
        type: String,
        delta: String,
    ) {
        if (type in BACKEND_OUTPUT_TYPES) {
            markPendingTurnRunning(
                threadId = threadId.takeIf { it.isNotBlank() },
                turnId = turnId.takeIf { it.isNotBlank() },
            )
        }
        val thread = activeThread ?: return
        if (thread.id != threadId) {
            return
        }
        val turn = thread.ensureTurn(turnId)
        val current = turn.find(itemId)
            ?: MutableItem(id = itemId, type = type).also { turn.upsert(it) }
        current.type = type
        current.primaryText += delta
        publishActiveThread()
    }

    private fun parseModels(array: JSONArray?): List<ModelOption> {
        if (array == null) {
            return emptyList()
        }
        val models = mutableListOf<ModelOption>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("model").ifBlank {
                item.optString("slug").ifBlank {
                    item.optString("id")
                }
            }
            if (id.isBlank()) {
                continue
            }
            val displayName = item.optString("displayName").ifBlank {
                item.optString("display_name").ifBlank { id }
            }
            val rawOptions = item.optJSONArray("supportedReasoningLevels")
                ?: item.optJSONArray("supported_reasoning_levels")
                ?: item.optJSONArray("reasoningEfforts")
            val reasoningOptions = parseReasoningOptions(rawOptions)
            val defaultReasoning = item.optString("defaultReasoningLevel").ifBlank {
                item.optString("default_reasoning_level").ifBlank {
                    reasoningOptions.firstOrNull()?.value ?: DEFAULT_REASONING
                }
            }
            models += ModelOption(
                id = id,
                displayName = displayName,
                reasoningOptions = if (reasoningOptions.isEmpty()) defaultReasoningOptions() else reasoningOptions,
                defaultReasoning = defaultReasoning,
            )
        }
        return models
    }

    private fun parseReasoningOptions(array: JSONArray?): List<ReasoningEffort> {
        if (array == null) {
            return emptyList()
        }
        val options = mutableListOf<ReasoningEffort>()
        for (index in 0 until array.length()) {
            when (val entry = array.opt(index)) {
                is JSONObject -> {
                    val effort = entry.optString("effort").ifBlank { continue }
                    options += ReasoningEffort(effort, reasoningLabel(effort))
                }

                is String -> {
                    val effort = entry.ifBlank { continue }
                    options += ReasoningEffort(effort, reasoningLabel(effort))
                }
            }
        }
        return options.distinctBy { it.value }
    }

    private fun parseThreadSummaries(array: JSONArray?): List<ThreadSummary> {
        if (array == null) {
            return emptyList()
        }
        val items = mutableListOf<ThreadSummary>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { continue }
            val updatedAt = item.optEpochSeconds("updatedAt", "updated_at")
            items += applyCustomTitle(
                ThreadSummary(
                    id = id,
                    title = displayThreadName(
                        item.optString("name"),
                        item.optString("summary").ifBlank {
                            item.optString("preview").ifBlank { item.optString("thread_name") }
                        },
                        threadId = id,
                    ),
                    updatedAtLabel = formatEpoch(updatedAt),
                    updatedAtEpochSeconds = updatedAt,
                    source = "remote",
                ),
            )
        }
        return items
    }

    private fun parseThread(threadJson: JSONObject): MutableThread {
        val thread = MutableThread(
            id = threadJson.optString("id").ifBlank { error("线程缺少 id") },
            name = threadJson.optStringOrNull("name"),
            preview = threadJson.optStringOrNull("summary")
                ?: threadJson.optStringOrNull("preview")
                ?: threadJson.optStringOrNull("thread_name"),
            cwd = threadJson.optStringOrNull("cwd"),
            status = threadJson.optString("status").ifBlank { "idle" },
            updatedAtEpochSeconds = threadJson.optEpochSeconds("updatedAt", "updated_at"),
            customTitle = customTitleFor(threadJson.optString("id")),
        )
        val turns = threadJson.optJSONArray("turns") ?: JSONArray()
        for (turnIndex in 0 until turns.length()) {
            val turnJson = turns.optJSONObject(turnIndex) ?: continue
            val turn = MutableTurn(
                id = turnJson.optString("id").ifBlank { "turn-$turnIndex" },
                status = turnJson.optString("status").ifBlank { "completed" },
            )
            val items = turnJson.optJSONArray("items") ?: JSONArray()
            for (itemIndex in 0 until items.length()) {
                val itemJson = items.optJSONObject(itemIndex) ?: continue
                val parsedItem = parseItem(itemJson)
                turn.upsert(parsedItem)
                if (parsedItem.type == "userMessage" && parsedItem.primaryText.isNotBlank()) {
                    lastUserTextByThread[thread.id] = parsedItem.primaryText
                }
            }
            thread.turns += turn
        }
        return thread
    }

    private fun parseItem(item: JSONObject): MutableItem {
        val type = item.optString("type").ifBlank { "unknown" }
        return when (type) {
            "userMessage" -> MutableItem(
                id = item.optString("id").ifBlank { "user-${System.nanoTime()}" },
                type = type,
                primaryText = extractText(item.optJSONArray("content")),
            )

            "agentMessage" -> MutableItem(
                id = item.optString("id").ifBlank { "agent-${System.nanoTime()}" },
                type = type,
                primaryText = item.optString("text").ifBlank { extractText(item.optJSONArray("content")) },
                status = item.optString("phase").ifBlank { item.optString("status") },
            )

            "reasoning" -> MutableItem(
                id = item.optString("id").ifBlank { "reasoning-${System.nanoTime()}" },
                type = type,
                primaryText = extractText(item.optJSONArray("summary"))
                    .ifBlank { extractText(item.optJSONArray("content")) },
                status = item.optString("status"),
            )

            "commandExecution" -> MutableItem(
                id = item.optString("id").ifBlank { "command-${System.nanoTime()}" },
                type = type,
                primaryText = item.optString("aggregatedOutput"),
                status = item.optString("status"),
                command = item.optString("command"),
                cwd = item.optString("cwd"),
            )

            "fileChange" -> MutableItem(
                id = item.optString("id").ifBlank { "file-${System.nanoTime()}" },
                type = type,
                primaryText = buildFileDiffText(item.optJSONArray("changes")),
                secondaryText = summarizeChanges(item.optJSONArray("changes")),
                status = item.optString("status"),
            )

            "plan" -> MutableItem(
                id = item.optString("id").ifBlank { "plan-${System.nanoTime()}" },
                type = type,
                primaryText = item.optString("text").ifBlank {
                    buildPlanText(item.optString("explanation"), item.optJSONArray("plan"))
                },
            )

            else -> MutableItem(
                id = item.optString("id").ifBlank { "item-${System.nanoTime()}" },
                type = type,
                primaryText = item.toString(2),
                status = item.optString("status"),
            )
        }
    }

    private fun extractText(array: JSONArray?): String {
        if (array == null) {
            return ""
        }
        val pieces = mutableListOf<String>()
        for (index in 0 until array.length()) {
            when (val entry = array.opt(index)) {
                is JSONObject -> {
                    val text = entry.optString("text").ifBlank {
                        entry.optString("content").ifBlank { entry.toString() }
                    }
                    if (text.isNotBlank()) {
                        pieces += text
                    }
                }

                is String -> if (entry.isNotBlank()) {
                    pieces += entry
                }
            }
        }
        return pieces.joinToString("\n").trim()
    }

    private fun summarizeChanges(changes: JSONArray?): String {
        if (changes == null || changes.length() == 0) {
            return ""
        }
        val lines = mutableListOf<String>()
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            val kind = change.optString("kind").ifBlank { "edit" }
            val path = change.optString("path").ifBlank { "unknown" }
            lines += "$kind $path"
        }
        return lines.joinToString("\n")
    }

    private fun buildFileDiffText(changes: JSONArray?): String {
        if (changes == null || changes.length() == 0) {
            return ""
        }
        val chunks = mutableListOf<String>()
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            val kind = change.optString("kind").ifBlank { "edit" }
            val path = change.optString("path").ifBlank { "unknown" }
            val diff = change.optString("diff")
            chunks += buildString {
                append("$kind: $path")
                if (diff.isNotBlank()) {
                    append("\n")
                    append(diff)
                }
            }
        }
        return chunks.joinToString("\n\n").trim()
    }

    private fun buildApprovalBody(params: JSONObject, primary: String): String {
        val lines = mutableListOf<String>()
        primary.ifBlank { "" }.takeIf { it.isNotBlank() }?.let { lines += it }
        params.optStringOrNull("cwd")?.let { lines += "目录: $it" }
        summarizeChanges(params.optJSONArray("changes")).takeIf { it.isNotBlank() }?.let { lines += it }
        params.optStringOrNull("summary")?.takeIf { it != primary }?.let { lines += it }
        return lines.joinToString("\n\n").ifBlank { "Codex 请求一次确认。" }
    }

    private fun buildTextInput(text: String): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text.trim())
                .put("text_elements", JSONArray()),
        )

    private fun buildSandboxPolicy(mode: PermissionMode): JSONObject =
        JSONObject().put("type", mode.sandboxPolicyType)

    private fun appendLocalDraft(threadId: String, text: String): LocalDraftMessage {
        val draft = LocalDraftMessage(
            id = "local-${System.currentTimeMillis()}-${(1000..9999).random()}",
            threadId = threadId,
            text = text.trim(),
            status = LocalMessageStatus.PENDING,
            createdAt = System.currentTimeMillis(),
        )
        localDraftsByThread.getOrPut(threadId) { mutableListOf() }.add(draft)
        persistLocalDrafts()
        publishActiveThread()
        return draft
    }

    private fun draftsForThread(threadId: String): List<LocalDraftMessage> =
        localDraftsByThread[threadId].orEmpty()

    private fun findLocalDraft(id: String): LocalDraftMessage? =
        localDraftsByThread.values.asSequence().flatten().firstOrNull { it.id == id }

    private fun currentDraftThreadId(): String =
        activeThread?.id ?: _uiState.value.activeThreadId?.takeIf(::isLocalConversationId) ?: LOCAL_DRAFT_THREAD_ID

    private fun activeConversationKey(): String? =
        activeThread?.id ?: _uiState.value.activeThreadId

    private fun effectiveWorkingDirectory(threadId: String? = activeThread?.id): String =
        activeThread
            ?.takeIf { !threadId.isNullOrBlank() && it.id == threadId }
            ?.cwd
            ?.takeIf { it.isNotBlank() }
            ?: TERMUX_HOME

    private fun isLocalConversationId(id: String?): Boolean {
        val value = id.orEmpty()
        return value == LOCAL_DRAFT_THREAD_ID || value.startsWith(LOCAL_DRAFT_THREAD_PREFIX)
    }

    private fun currentServiceTierOrNull(): String? =
        if (_uiState.value.fastModeEnabled) FAST_SERVICE_TIER else null

    private fun isFastServiceTier(value: String): Boolean =
        value.equals(FAST_SERVICE_TIER, ignoreCase = true)

    private fun newLocalConversationId(): String =
        "$LOCAL_DRAFT_THREAD_PREFIX${System.currentTimeMillis()}"

    private fun activePendingTurnsForConversation(conversationKey: String?): List<PendingTurn> {
        if (conversationKey.isNullOrBlank()) {
            return emptyList()
        }
        return pendingTurns.values
            .filter { pending ->
                pending.threadId == conversationKey || pending.conversationKey == conversationKey
            }
            .sortedBy { it.createdAt }
    }

    private fun registerPendingTurn(
        localDraftId: String,
        prompt: String,
        conversationKey: String,
        clearDraft: Boolean,
    ): PendingTurn {
        val pending = PendingTurn(
            requestId = "turn-${System.currentTimeMillis()}-${(1000..9999).random()}",
            localDraftId = localDraftId,
            prompt = prompt,
            conversationKey = conversationKey,
            createdAt = System.currentTimeMillis(),
        )
        pendingTurns[pending.requestId] = pending
        schedulePendingTurnRecovery(pending.requestId)
        refreshComposerActivity(clearText = clearDraft)
        return pending
    }

    private fun bindPendingTurnToThread(requestId: String, threadId: String) {
        val existing = pendingTurns[requestId] ?: return
        pendingTurns[requestId] = existing.copy(threadId = threadId)
        refreshComposerActivity()
    }

    private fun markPendingTurnRunning(
        requestId: String? = null,
        threadId: String? = null,
        turnId: String? = null,
    ) {
        val resolvedId = requestId
            ?: findMatchingPendingTurnId(threadId = threadId, turnId = turnId)

        if (resolvedId == null) {
            turnId?.takeIf { it.isNotBlank() }?.let { runningTurnIds += it }
            refreshComposerActivity()
            return
        }

        val existing = pendingTurns[resolvedId] ?: return
        pendingTurns[resolvedId] = existing.copy(
            threadId = threadId ?: existing.threadId,
            turnId = turnId ?: existing.turnId,
            phase = PendingTurnPhase.RUNNING,
        )
        turnId?.takeIf { it.isNotBlank() }?.let { runningTurnIds += it }
        refreshComposerActivity()
    }

    private fun completePendingTurn(requestId: String) {
        val removed = pendingTurns.remove(requestId) ?: return
        pendingTurnJobs.remove(requestId)?.cancel()
        removed.turnId?.takeIf { it.isNotBlank() }?.let { turnId ->
            if (pendingTurns.values.none { it.turnId == turnId }) {
                runningTurnIds.remove(turnId)
            }
        }
        removeLocalDraft(removed.localDraftId)
        refreshComposerActivity()
    }

    private fun failPendingTurn(requestId: String) {
        val removed = pendingTurns.remove(requestId) ?: return
        pendingTurnJobs.remove(requestId)?.cancel()
        removed.turnId?.takeIf { it.isNotBlank() }?.let { runningTurnIds.remove(it) }
        updateLocalDraftStatus(removed.localDraftId, LocalMessageStatus.FAILED)
        refreshComposerActivity()
    }

    private fun findMatchingPendingTurnId(
        threadId: String? = null,
        turnId: String? = null,
    ): String? {
        if (!turnId.isNullOrBlank()) {
            pendingTurns.values.firstOrNull { it.turnId == turnId }?.let { return it.requestId }
        }
        if (!threadId.isNullOrBlank()) {
            pendingTurns.values
                .filter { it.threadId == threadId }
                .minByOrNull { it.createdAt }
                ?.let { return it.requestId }
        }
        return activePendingTurnsForConversation(activeConversationKey()).firstOrNull()?.requestId
    }

    private fun schedulePendingTurnRecovery(requestId: String) {
        pendingTurnJobs.remove(requestId)?.cancel()
        pendingTurnJobs[requestId] = viewModelScope.launch {
            delay(PENDING_TURN_RECOVERY_MS)
            attemptPendingTurnRecovery(requestId)
            delay(PENDING_TURN_FAIL_MS - PENDING_TURN_RECOVERY_MS)
            attemptPendingTurnRecovery(requestId, allowFailure = true)
            val pending = pendingTurns[requestId] ?: return@launch
            when (pending.phase) {
                PendingTurnPhase.AWAITING_ACK -> failPendingTurn(requestId)
                PendingTurnPhase.RUNNING -> schedulePendingTurnRecovery(requestId)
                PendingTurnPhase.FAILED -> Unit
            }
        }
    }

    private suspend fun attemptPendingTurnRecovery(requestId: String, allowFailure: Boolean = false) {
        val pending = pendingTurns[requestId] ?: return
        if (!allowFailure && pending.phase != PendingTurnPhase.AWAITING_ACK) {
            return
        }
        if (allowFailure && pending.phase == PendingTurnPhase.FAILED) {
            return
        }
        val threadId = pending.threadId ?: return
        if (!rpcClient.isOpen()) {
            if (allowFailure && pending.phase == PendingTurnPhase.AWAITING_ACK) {
                failPendingTurn(requestId)
            }
            return
        }
        runCatching {
            rpcClient.request(
                method = "thread/read",
                params = JSONObject()
                    .put("threadId", threadId)
                    .put("includeTurns", true),
            ) as? JSONObject
        }.onSuccess { response ->
            val threadJson = response?.optJSONObject("thread") ?: return@onSuccess
            val recoveredThread = parseThread(threadJson)
            mergeRemoteHistory(
                listOf(
                    ThreadSummary(
                        id = recoveredThread.id,
                        title = recoveredThread.displayName,
                        updatedAtLabel = formatEpoch(recoveredThread.updatedAtEpochSeconds),
                        updatedAtEpochSeconds = recoveredThread.updatedAtEpochSeconds,
                        source = "remote",
                    ),
                ),
            )

            if (_uiState.value.activeThreadId == threadId) {
                activeThread = recoveredThread
                resetTurnTracking(recoveredThread.runningTurnIds())
                reconcileDraftsWithThread(recoveredThread)
                publishActiveThread()
            } else {
                reconcileDraftsWithThread(recoveredThread)
            }

            val matchedTurn = findLatestTurnForPrompt(recoveredThread, pending.prompt)
            val hasPrompt = matchedTurn != null
            val threadUpdatedAtMillis = (recoveredThread.updatedAtEpochSeconds ?: 0L) * 1000L
            val runningTurnId = when {
                matchedTurn?.isRunning() == true -> matchedTurn.id
                else -> recoveredThread.currentRunningTurnId()
            }
            val hasVisibleOutput = matchedTurn?.let(::turnHasVisibleAssistantOutput) == true
            val promptCanBelongToPending =
                hasPrompt && threadUpdatedAtMillis + THREAD_TIMESTAMP_SKEW_MS >= pending.createdAt

            when {
                promptCanBelongToPending && !runningTurnId.isNullOrBlank() -> {
                    markPendingTurnRunning(
                        requestId = requestId,
                        threadId = threadId,
                        turnId = runningTurnId,
                    )
                }

                promptCanBelongToPending && hasVisibleOutput -> completePendingTurn(requestId)

                allowFailure && !promptCanBelongToPending && recoveredThread.runningTurnIds().isEmpty() -> failPendingTurn(requestId)

                allowFailure && promptCanBelongToPending && !hasVisibleOutput && recoveredThread.runningTurnIds().isEmpty() ->
                    failPendingTurn(requestId)
            }
        }.onFailure { error ->
            Log.d(TAG, "attemptPendingTurnRecovery failed: ${error.message}")
            if (allowFailure && pending.phase == PendingTurnPhase.AWAITING_ACK) {
                failPendingTurn(requestId)
            }
        }
    }

    private suspend fun refreshActiveThreadSnapshot() {
        val activeThreadId = _uiState.value.activeThreadId
        if (activeThreadId.isNullOrBlank() || isLocalConversationId(activeThreadId) || archivedThreadIds.contains(activeThreadId)) {
            return
        }
        runCatching {
            loadThreadIntoChat(activeThreadId)
        }.onFailure { error ->
            Log.d(TAG, "refreshActiveThreadSnapshot failed: ${error.message}")
            if (isMissingThreadError(error.message)) {
                preserveThreadAfterWriteFailure(activeThreadId)
            }
        }
    }

    private suspend fun reconcilePersistedDraftsWithBackend(limit: Int = 12) {
        if (!rpcClient.isOpen()) {
            return
        }
        localDraftsByThread.keys
            .filter { threadId ->
                !isLocalConversationId(threadId) &&
                    (archivedThreadIds.contains(threadId) || deletedThreadIds.contains(threadId))
            }
            .forEach(::markThreadPendingDraftsFailed)

        val threadIds = localDraftsByThread
            .asSequence()
            .filter { (threadId, drafts) ->
                !isLocalConversationId(threadId) &&
                    !archivedThreadIds.contains(threadId) &&
                    !deletedThreadIds.contains(threadId) &&
                    drafts.any { it.status == LocalMessageStatus.PENDING }
            }
            .map { it.key }
            .distinct()
            .take(limit)
            .toList()

        threadIds.forEach { threadId ->
            runCatching {
                rpcClient.request(
                    method = "thread/read",
                    params = JSONObject()
                        .put("threadId", threadId)
                        .put("includeTurns", true),
                ) as? JSONObject
            }.onSuccess { response ->
                val threadJson = response?.optJSONObject("thread") ?: return@onSuccess
                val recoveredThread = parseThread(threadJson)
                mergeRemoteHistory(
                    listOf(
                        ThreadSummary(
                            id = recoveredThread.id,
                            title = recoveredThread.displayName,
                            updatedAtLabel = formatEpoch(recoveredThread.updatedAtEpochSeconds),
                            updatedAtEpochSeconds = recoveredThread.updatedAtEpochSeconds,
                            source = "remote",
                        ),
                    ),
                )

                val isActiveThread = _uiState.value.activeThreadId == threadId
                if (isActiveThread) {
                    activeThread = recoveredThread
                    resetTurnTracking(recoveredThread.runningTurnIds())
                }

                reconcileDraftsWithThread(recoveredThread)

                if (activePendingTurnsForConversation(threadId).isEmpty() && recoveredThread.runningTurnIds().isEmpty()) {
                    markThreadPendingDraftsFailed(threadId)
                }

                if (isActiveThread) {
                    publishActiveThread()
                }
            }.onFailure { error ->
                Log.d(TAG, "reconcilePersistedDraftsWithBackend failed for $threadId: ${error.message}")
                if (isMissingThreadError(error.message)) {
                    markThreadPendingDraftsFailed(threadId)
                    if (_uiState.value.activeThreadId == threadId) {
                        preserveThreadAfterWriteFailure(threadId)
                    }
                }
            }
        }
    }

    private fun moveLocalDraftToThread(id: String, threadId: String) {
        var moved = false
        val entries = localDraftsByThread.entries.toList()
        entries.forEach { (sourceThreadId, drafts) ->
            val draftIndex = drafts.indexOfFirst { it.id == id }
            if (draftIndex < 0) {
                return@forEach
            }
            val draft = drafts.removeAt(draftIndex)
            if (drafts.isEmpty()) {
                localDraftsByThread.remove(sourceThreadId)
            }
            localDraftsByThread.getOrPut(threadId) { mutableListOf() }.add(
                draft.copy(threadId = threadId),
            )
            moved = true
        }
        if (moved) {
            persistLocalDrafts()
            publishActiveThread()
        }
    }

    private fun updateLocalDraftStatus(id: String, status: LocalMessageStatus) {
        var changed = false
        localDraftsByThread.forEach { (_, drafts) ->
            drafts.replaceAll { draft ->
                if (draft.id == id && draft.status != status) {
                    changed = true
                    draft.copy(status = status)
                } else {
                    draft
                }
            }
        }
        if (changed) {
            persistLocalDrafts()
            publishActiveThread()
        }
    }

    private fun removeLocalDraft(id: String) {
        var changed = false
        localDraftsByThread.entries.toList().forEach { (threadId, drafts) ->
            val removed = drafts.removeAll { it.id == id }
            if (removed) {
                changed = true
            }
            if (drafts.isEmpty()) {
                localDraftsByThread.remove(threadId)
            }
        }
        if (changed) {
            persistLocalDrafts()
            publishActiveThread()
        }
    }

    private fun reconcilePendingDraft(threadId: String, text: String): String? {
        val drafts = localDraftsByThread[threadId] ?: return null
        val activeDraftIds = activePendingTurnsForConversation(threadId)
            .map { it.localDraftId }
            .toSet()
        if (activeDraftIds.isEmpty()) {
            return null
        }
        val normalized = normalizeDraftText(text)
        val matchIndex = drafts.indexOfFirst {
            it.status == LocalMessageStatus.PENDING &&
                it.id in activeDraftIds &&
                normalizeDraftText(it.text) == normalized
        }
        if (matchIndex >= 0) {
            val removed = drafts.removeAt(matchIndex)
            if (drafts.isEmpty()) {
                localDraftsByThread.remove(threadId)
            }
            persistLocalDrafts()
            return removed.id
        }
        return null
    }

    private fun reconcileDraftsWithThread(thread: MutableThread?) {
        if (thread == null) {
            return
        }
        val threadUpdatedAtMillis = (thread.updatedAtEpochSeconds ?: 0L) * 1000L
        activePendingTurnsForConversation(thread.id).forEach { pending ->
            if (threadUpdatedAtMillis + THREAD_TIMESTAMP_SKEW_MS < pending.createdAt) {
                return@forEach
            }
            if (findLatestTurnForPrompt(thread, pending.prompt) == null) {
                return@forEach
            }
            onPendingDraftConfirmed(thread.id, pending.localDraftId)
        }
        if (activePendingTurnsForConversation(thread.id).isEmpty() && thread.runningTurnIds().isEmpty()) {
            markThreadPendingDraftsFailed(thread.id)
        }
    }

    private fun markThreadPendingDraftsFailed(threadId: String) {
        val drafts = localDraftsByThread[threadId] ?: return
        var changed = false
        drafts.replaceAll { draft ->
            if (draft.status == LocalMessageStatus.PENDING) {
                changed = true
                draft.copy(status = LocalMessageStatus.FAILED)
            } else {
                draft
            }
        }
        if (changed) {
            persistLocalDrafts()
        }
    }

    private fun preserveThreadAfterWriteFailure(threadId: String) {
        if (threadId.isBlank()) {
            return
        }
        markThreadPendingDraftsFailed(threadId)
        if (_uiState.value.activeThreadId == threadId) {
            publishActiveThread()
            showTransientStatus("当前会话暂时不可写，已保留原内容")
        }
    }

    private fun sanitizeRestoredState() {
        sanitizeRestoredDrafts()
        sanitizeRestoredThreadSelection()
    }

    private fun sanitizeRestoredDrafts() {
        val hiddenThreadIds = buildSet {
            addAll(archivedThreadIds)
            addAll(deletedThreadIds)
        }
        if (hiddenThreadIds.isEmpty()) {
            return
        }
        hiddenThreadIds.forEach(::markThreadPendingDraftsFailed)
    }

    private fun sanitizeRestoredThreadSelection() {
        val state = _uiState.value
        val activeThreadId = state.activeThreadId ?: return
        if (isLocalConversationId(activeThreadId)) {
            return
        }
        if (!archivedThreadIds.contains(activeThreadId) && !deletedThreadIds.contains(activeThreadId)) {
            return
        }
        persistThreadSelection(
            activeThreadId = null,
            activeThreadTitle = "新会话",
            rememberLastOpened = false,
        )
        _uiState.value = state.copy(
            activeThreadId = null,
            activeThreadTitle = "新会话",
        )
    }

    private fun isMissingThreadError(message: String?): Boolean {
        val normalized = message.orEmpty()
        return normalized.contains("thread not found", ignoreCase = true) ||
            normalized.contains("no rollout found for thread id", ignoreCase = true)
    }

    private fun loadPersistedLocalDrafts(): List<LocalDraftMessage> {
        val encoded = prefs.getString(PREF_LOCAL_DRAFTS, null).orEmpty()
        if (encoded.isBlank()) {
            return emptyList()
        }
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptyList()
        val drafts = mutableListOf<LocalDraftMessage>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { continue }
            val threadId = item.optString("threadId").ifBlank { continue }
            val text = item.optString("text").ifBlank { continue }
            val status = runCatching {
                LocalMessageStatus.valueOf(item.optString("status"))
            }.getOrDefault(LocalMessageStatus.PENDING)
            drafts += LocalDraftMessage(
                id = id,
                threadId = threadId,
                text = text,
                status = status,
                createdAt = item.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        return drafts
    }

    private fun persistLocalDrafts() {
        val payload = JSONArray()
        localDraftsByThread.values.flatten()
            .sortedBy { it.createdAt }
            .forEach { draft ->
                payload.put(
                    JSONObject()
                        .put("id", draft.id)
                        .put("threadId", draft.threadId)
                        .put("text", draft.text)
                        .put("status", draft.status.name)
                        .put("createdAt", draft.createdAt),
                )
            }
        prefs.edit().putString(PREF_LOCAL_DRAFTS, payload.toString()).apply()
    }

    private fun loadArchivedThreadIds(): Set<String> {
        val encoded = prefs.getString(PREF_ARCHIVED_THREAD_IDS, null).orEmpty()
        if (encoded.isBlank()) {
            return emptySet()
        }
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun loadDeletedThreadIds(): Set<String> {
        val encoded = prefs.getString(PREF_DELETED_THREAD_IDS, null).orEmpty()
        if (encoded.isBlank()) {
            return emptySet()
        }
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun loadCustomThreadTitles(): Map<String, String> {
        val encoded = prefs.getString(PREF_CUSTOM_THREAD_TITLES, null).orEmpty()
        if (encoded.isBlank()) {
            return emptyMap()
        }
        val payload = runCatching { JSONObject(encoded) }.getOrNull() ?: return emptyMap()
        return buildMap {
            payload.keys().forEach { key ->
                normalizeDisplayText(payload.optString(key))
                    ?.takeIf { key.isNotBlank() }
                    ?.let { put(key, it) }
            }
        }
    }

    private fun persistArchivedThreadIds() {
        val payload = JSONArray()
        archivedThreadIds.sorted().forEach(payload::put)
        prefs.edit().putString(PREF_ARCHIVED_THREAD_IDS, payload.toString()).apply()
    }

    private fun persistDeletedThreadIds() {
        val payload = JSONArray()
        deletedThreadIds.sorted().forEach(payload::put)
        prefs.edit().putString(PREF_DELETED_THREAD_IDS, payload.toString()).apply()
    }

    private fun persistCustomThreadTitles() {
        val payload = JSONObject()
        customThreadTitles.toSortedMap().forEach { (threadId, title) ->
            normalizeDisplayText(title)?.let { payload.put(threadId, it) }
        }
        prefs.edit().putString(PREF_CUSTOM_THREAD_TITLES, payload.toString()).apply()
    }

    private suspend fun physicallyDeleteThreadArtifacts(threadId: String): Int {
        val command = """
            count=${'$'}(find /data/data/com.termux/files/home/.codex/archived_sessions /data/data/com.termux/files/home/.codex/sessions -type f -name "*$threadId*.jsonl" 2>/dev/null | wc -l)
            find /data/data/com.termux/files/home/.codex/archived_sessions /data/data/com.termux/files/home/.codex/sessions -type f -name "*$threadId*.jsonl" -exec rm -f {} + 2>/dev/null
            echo "${'$'}count"
        """.trimIndent()
        val result = RootShell.run(command = command, timeoutMillis = 12_000L)
        if (result.exitCode != 0) {
            error(result.stderr.ifBlank { "删除会话文件失败" })
        }
        return result.stdout.trim().toIntOrNull() ?: 0
    }

    private fun hideArchivedThread(threadId: String) {
        if (threadId.isBlank()) {
            return
        }
        archivedThreadIds += threadId
        persistArchivedThreadIds()
        if (activeThread?.id == threadId) {
            activeThread = null
        }
    }

    private fun showArchivedThread(threadId: String) {
        if (threadId.isBlank()) {
            return
        }
        archivedThreadIds.remove(threadId)
        persistArchivedThreadIds()
    }

    private fun customTitleFor(threadId: String?): String? =
        threadId?.takeIf { it.isNotBlank() }?.let(customThreadTitles::get)?.let(::normalizeDisplayText)

    private fun applyCustomTitle(summary: ThreadSummary): ThreadSummary =
        customTitleFor(summary.id)?.let { summary.copy(title = it) } ?: summary

    private fun normalizeDraftText(value: String): String =
        value.trim().replace("\r\n", "\n")

    private fun onPendingDraftConfirmed(threadId: String, localDraftId: String) {
        val requestId = pendingTurns.values
            .filter { pending ->
                pending.localDraftId == localDraftId &&
                    (pending.threadId == null || pending.threadId == threadId || pending.conversationKey == threadId)
            }
            .minByOrNull { it.createdAt }
            ?.requestId
            ?: return

        val visibleThread = activeThread?.takeIf { it.id == threadId }
        val matchedTurn = visibleThread?.let { findLatestTurnForPrompt(it, pendingTurns[requestId]?.prompt.orEmpty()) }
        val runningTurnId = when {
            matchedTurn?.isRunning() == true -> matchedTurn.id
            else -> visibleThread?.currentRunningTurnId()
        }
        if (!runningTurnId.isNullOrBlank()) {
            markPendingTurnRunning(
                requestId = requestId,
                threadId = threadId,
                turnId = runningTurnId,
            )
        } else if (matchedTurn != null && turnHasVisibleAssistantOutput(matchedTurn)) {
            completePendingTurn(requestId)
        } else {
            // The backend has echoed the user message, but this turn has not
            // produced visible assistant output yet. Keep the turn active so the
            // generating placeholder and recovery flow stay alive until the
            // reply actually starts or definitively fails.
            markPendingTurnRunning(
                requestId = requestId,
                threadId = threadId,
            )
        }
    }

    private fun hasVisibleAssistantOutput(thread: MutableThread): Boolean =
        thread.turns.flatMap { it.items }.any { item ->
            when (item.type) {
                "agentMessage",
                "reasoning",
                "fileChange",
                "plan" -> item.primaryText.isNotBlank()
                "commandExecution" -> item.primaryText.isNotBlank() || item.command.isNotBlank()
                else -> false
            }
        }

    private fun turnHasVisibleAssistantOutput(turn: MutableTurn): Boolean =
        turn.items.any { item ->
            when (item.type) {
                "agentMessage",
                "reasoning",
                "fileChange",
                "plan" -> item.primaryText.isNotBlank()
                "commandExecution" -> item.primaryText.isNotBlank() || item.command.isNotBlank()
                else -> false
            }
        }

    private fun findLatestTurnForPrompt(thread: MutableThread, prompt: String): MutableTurn? {
        val normalizedPrompt = normalizeDraftText(prompt)
        return thread.turns.lastOrNull { turn ->
            turn.items.any { item ->
                item.type == "userMessage" && normalizeDraftText(item.primaryText) == normalizedPrompt
            }
        }
    }

    private fun threadHasUserPrompt(thread: MutableThread, prompt: String): Boolean {
        val normalizedPrompt = normalizeDraftText(prompt)
        return thread.turns.any { turn ->
            turn.items.any { item ->
                item.type == "userMessage" && normalizeDraftText(item.primaryText) == normalizedPrompt
            }
        }
    }

    private fun loadInitialState(): CodexMobileUiState {
        val model = prefs.getString(PREF_SELECTED_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        val reasoning = prefs.getString(PREF_SELECTED_REASONING, DEFAULT_REASONING).orEmpty().ifBlank { DEFAULT_REASONING }
        val activeThreadId = prefs.getString(PREF_ACTIVE_THREAD_ID, null).orEmpty().ifBlank { null }
        val activeThreadTitle = customTitleFor(activeThreadId)
            ?: prefs.getString(PREF_ACTIVE_THREAD_TITLE, null).orEmpty().ifBlank { "新会话" }
        val fastModeEnabled = prefs.getBoolean(PREF_FAST_MODE_ENABLED, false)
        val permissionMode = PermissionMode.entries.firstOrNull {
            it.name == prefs.getString(PREF_PERMISSION_MODE, PermissionMode.ASK_EACH_TIME.name)
        } ?: PermissionMode.ASK_EACH_TIME
        val fontScale = FontScaleOption.entries.firstOrNull {
            it.name == prefs.getString(PREF_FONT_SCALE, FontScaleOption.NORMAL.name)
        } ?: FontScaleOption.NORMAL
        return CodexMobileUiState(
            selectedModel = model,
            selectedReasoning = reasoning,
            fastModeEnabled = fastModeEnabled,
            permissionMode = permissionMode,
            fontScale = fontScale,
            activeThreadId = activeThreadId,
            activeThreadTitle = if (activeThreadId == null) "新会话" else activeThreadTitle,
        )
    }

    private fun persistPreferences() {
        val state = _uiState.value
        prefs.edit()
            .putString(PREF_SELECTED_MODEL, state.selectedModel)
            .putString(PREF_SELECTED_REASONING, state.selectedReasoning)
            .putBoolean(PREF_FAST_MODE_ENABLED, state.fastModeEnabled)
            .putString(PREF_PERMISSION_MODE, state.permissionMode.name)
            .putString(PREF_FONT_SCALE, state.fontScale.name)
            .apply()
    }

    private fun persistThreadSelection(
        activeThreadId: String?,
        activeThreadTitle: String?,
        rememberLastOpened: Boolean = !activeThreadId.isNullOrBlank(),
    ) {
        val rememberRealThread = rememberLastOpened &&
            !activeThreadId.isNullOrBlank() &&
            !isLocalConversationId(activeThreadId)
        prefs.edit().apply {
            putString(PREF_ACTIVE_THREAD_ID, activeThreadId)
            putString(PREF_ACTIVE_THREAD_TITLE, activeThreadTitle ?: "新会话")
            if (rememberRealThread) {
                putString(PREF_LAST_OPENED_THREAD_ID, activeThreadId)
                putString(PREF_LAST_OPENED_THREAD_TITLE, activeThreadTitle ?: "新会话")
            }
        }.apply()
    }

    private fun chooseBestModel(
        models: List<ModelOption>,
        current: String,
        remotePreferred: String? = null,
    ): String {
        if (models.isEmpty()) {
            return current.ifBlank { remotePreferred ?: DEFAULT_MODEL }
        }
        if (models.any { it.id == current }) {
            return current
        }
        if (!remotePreferred.isNullOrBlank() && models.any { it.id == remotePreferred }) {
            return remotePreferred
        }
        return models.firstOrNull { it.id == DEFAULT_MODEL }?.id ?: models.first().id
    }

    private fun chooseReasoning(
        models: List<ModelOption>,
        modelId: String,
        current: String,
    ): String {
        val model = models.firstOrNull { it.id == modelId } ?: return current
        if (model.reasoningOptions.any { it.value == current }) {
            return current
        }
        return model.defaultReasoning
    }

    private fun deriveConnectionBanner(
        status: StatusSnapshot,
        previousCode: String,
    ): ConnectionBanner {
        return when {
            !status.termuxInstalled -> ConnectionBanner(
                code = "backend_missing",
                title = "未检测到 Termux",
                detail = "这套手机端 Codex 依赖 Termux 作为本地底座。",
                tone = BannerTone.DANGER,
            )

            !status.rootAvailable -> ConnectionBanner(
                code = "root_missing",
                title = "Root 尚未授权",
                detail = "App 需要 root 来补保活并自动拉起 Termux 后端。",
                tone = BannerTone.WARN,
            )

            !status.backendListening -> ConnectionBanner(
                code = "backend_missing",
                title = if (status.authPresent) "Termux 已登录，但 app-server 未运行" else "本机后端未启动",
                detail = status.backendStatusDetail.ifBlank { "App 正在尝试自动拉起 codex app-server。" },
                tone = BannerTone.WARN,
            )

            !status.authPresent -> ConnectionBanner(
                code = "auth_missing",
                title = "Codex 还没登录",
                detail = status.backendStatusDetail.ifBlank { "Termux 里的认证文件不存在，先在 Termux 中完成登录。" },
                tone = BannerTone.WARN,
            )

            previousCode == "reconnecting" -> ConnectionBanner(
                code = "connected",
                title = "连接已恢复",
                detail = "本机 Codex 后端重新可用。",
                tone = BannerTone.OK,
            )

            else -> ConnectionBanner(
                code = "connected",
                title = "Codex 后端已连接",
                detail = status.backendStatusDetail.ifBlank { "模型、历史会话和流式回复都来自本机后端。" },
                tone = BannerTone.OK,
            )
        }
    }

    private fun formatEpoch(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0L) {
            return "刚刚"
        }
        return runCatching {
            TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds))
        }.getOrElse { "刚刚" }
    }

    private fun setConnection(
        code: String,
        title: String,
        detail: String,
        tone: BannerTone,
    ) {
        _uiState.update {
            it.copy(connection = ConnectionBanner(code = code, title = title, detail = detail, tone = tone))
        }
        syncForegroundService()
    }

    private fun refreshComposerActivity(clearText: Boolean = false) {
        val conversationKey = activeConversationKey()
        val sending = conversationKey != null &&
            (activePendingTurnsForConversation(conversationKey).isNotEmpty() || runningTurnIds.isNotEmpty())
        _uiState.update { state ->
            state.copy(
                composer = state.composer.copy(
                    sending = sending,
                    canInterrupt = latestRunningTurnId() != null,
                    text = if (clearText) "" else state.composer.text,
                ),
            )
        }
        syncForegroundService()
    }

    private fun noteTurnRequestStarted(clearDraft: Boolean = false) {
        refreshComposerActivity(clearText = clearDraft)
    }

    private fun noteTurnStarted(turnId: String?, threadId: String? = activeThread?.id) {
        markPendingTurnRunning(
            threadId = threadId?.takeIf { it.isNotBlank() },
            turnId = turnId?.takeIf { it.isNotBlank() },
        )
        turnId?.takeIf { it.isNotBlank() }?.let { runningTurnIds += it }
        refreshComposerActivity()
    }

    private fun noteTurnFinished(turnId: String?, threadId: String? = activeThread?.id) {
        turnId?.takeIf { it.isNotBlank() }?.let { runningTurnIds.remove(it) }
        val completedIds = pendingTurns.values
            .filter { pending ->
                when {
                    !turnId.isNullOrBlank() -> pending.turnId == turnId
                    !threadId.isNullOrBlank() -> pending.threadId == threadId
                    else -> false
                }
            }
            .map { it.requestId }
        completedIds.forEach { completePendingTurn(it) }
        if (turnId.isNullOrBlank() && !threadId.isNullOrBlank() && activeThread?.id == threadId) {
            runningTurnIds.clear()
        }
        refreshComposerActivity()
    }

    private fun resetTurnTracking(activeTurnIds: Collection<String> = emptyList()) {
        runningTurnIds.clear()
        runningTurnIds += activeTurnIds.filter { it.isNotBlank() }
        refreshComposerActivity()
    }

    private fun onTurnRequestFailed(prompt: String) {
        _uiState.update { state ->
            val nextText = if (state.composer.text.isBlank()) prompt else state.composer.text
            state.copy(
                composer = state.composer.copy(
                    sending = activePendingTurnsForConversation(activeConversationKey()).isNotEmpty() || runningTurnIds.isNotEmpty(),
                    text = nextText,
                ),
            )
        }
        syncForegroundService()
    }

    private fun latestRunningTurnId(): String? = runningTurnIds.lastOrNull()

    private fun setStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun showTransientStatus(message: String, durationMs: Long = 2_200L) {
        transientStatusJob?.cancel()
        setStatus(message)
        transientStatusJob = viewModelScope.launch {
            delay(durationMs)
            if (_uiState.value.statusMessage == message) {
                clearStatus()
            }
        }
    }

    private fun clearStatus() {
        transientStatusJob?.cancel()
        transientStatusJob = null
        _uiState.update { it.copy(statusMessage = "") }
    }

    private fun setError(message: String) {
        _uiState.update { state ->
            state.copy(errorMessage = message, statusMessage = if (message.isBlank()) state.statusMessage else "")
        }
    }

    private fun syncForegroundService() {
        viewModelScope.launch {
            runCatching {
                val connectionCode = _uiState.value.connection.code
                val active = (
                    connectionCode == "connected" ||
                    pendingTurns.isNotEmpty() ||
                    runningTurnIds.isNotEmpty() ||
                        _uiState.value.activeApproval != null ||
                        connectionCode == "starting" ||
                        connectionCode == "reconnecting"
                    )
                runtimeController.setForegroundSessionActive(active)
            }
        }
    }

    private fun Throwable.isInternalCancellation(): Boolean {
        val message = message.orEmpty()
        return this is CancellationException ||
            message.contains("StandaloneCoroutine was cancelled", ignoreCase = true) ||
            message.contains("socket closed", ignoreCase = true)
    }

    private fun buildPlanText(explanation: String, plan: JSONArray?): String {
        val lines = mutableListOf<String>()
        explanation.takeIf { it.isNotBlank() }?.let { lines += it }
        if (plan != null) {
            for (index in 0 until plan.length()) {
                val step = plan.optJSONObject(index) ?: continue
                val label = step.optString("step").ifBlank { continue }
                val status = step.optString("status").ifBlank { "pending" }
                lines += "[$status] $label"
            }
        }
        return lines.joinToString("\n").ifBlank { "计划更新已到达。" }
    }

    private fun JSONObject.toStatusSnapshot(): StatusSnapshot = StatusSnapshot(
        rootAvailable = optBoolean("rootAvailable"),
        termuxInstalled = optBoolean("termuxInstalled"),
        backendListening = optBoolean("backendListening"),
        authPresent = optBoolean("authPresent"),
        autoHardeningEnabled = optBoolean("autoHardeningEnabled", true),
        termuxUid = if (has("termuxUid") && !isNull("termuxUid")) optInt("termuxUid") else null,
        backendStatusDetail = optString("backendStatusDetail").ifBlank { "" },
    )

    private fun JSONObject.toKeepaliveSnapshot(): KeepaliveSnapshot = KeepaliveSnapshot(
        deviceIdleWhitelisted = optBoolean("deviceIdleWhitelisted"),
        restrictBackgroundWhitelisted = optBoolean("restrictBackgroundWhitelisted"),
        standbyBucket = optString("standbyBucket").ifBlank { "unknown" },
        batteryOptimizationIgnoredForUiApp = optBoolean("batteryOptimizationIgnoredForUiApp"),
    )

    private fun defaultReasoningOptions(): List<ReasoningEffort> = listOf(
        ReasoningEffort("low", "低"),
        ReasoningEffort("medium", "中"),
        ReasoningEffort("high", "高"),
        ReasoningEffort("xhigh", "超高"),
    )

    private data class LocalDraftMessage(
        val id: String,
        val threadId: String,
        val text: String,
        val status: LocalMessageStatus,
        val createdAt: Long,
    )

    private enum class PendingTurnPhase {
        AWAITING_ACK,
        RUNNING,
        FAILED,
    }

    private data class PendingTurn(
        val requestId: String,
        val localDraftId: String,
        val prompt: String,
        val conversationKey: String,
        val threadId: String? = null,
        val turnId: String? = null,
        val createdAt: Long,
        val phase: PendingTurnPhase = PendingTurnPhase.AWAITING_ACK,
    )

    private data class MutableThread(
        val id: String,
        val name: String?,
        val preview: String?,
        val cwd: String?,
        val status: String,
        val updatedAtEpochSeconds: Long?,
        val customTitle: String? = null,
        val turns: MutableList<MutableTurn> = mutableListOf(),
    ) {
        val displayName: String
            get() = customTitle ?: displayThreadName(name, preview, threadId = id)

        fun ensureTurn(turnId: String): MutableTurn {
            val existing = turns.firstOrNull { it.id == turnId }
            if (existing != null) {
                return existing
            }
            return MutableTurn(id = turnId).also { turns += it }
        }

        fun currentRunningTurnId(): String? =
            turns.lastOrNull { it.status.contains("progress", ignoreCase = true) }?.id

        fun runningTurnIds(): List<String> =
            turns.filter { it.isRunning() }.map { it.id }

        fun flattenToUiItems(): List<ChatItemUi> {
            val uiItems = mutableListOf<ChatItemUi>()
            turns.forEachIndexed { turnIndex, turn ->
                turn.items.forEachIndexed { itemIndex, item ->
                    uiItems += item.toUi(
                        sortKey = "${turnIndex.toString().padStart(4, '0')}-${itemIndex.toString().padStart(4, '0')}",
                        turnStatus = turn.status,
                    )
                }
            }
            return uiItems
        }
    }

    private data class MutableTurn(
        val id: String,
        var status: String = "inProgress",
        val items: MutableList<MutableItem> = mutableListOf(),
    ) {
        fun isRunning(): Boolean {
            val value = status.trim()
            return value.equals("inProgress", ignoreCase = true) ||
                value.contains("progress", ignoreCase = true) ||
                value.equals("running", ignoreCase = true)
        }

        fun upsert(item: MutableItem) {
            val existing = items.indexOfFirst { it.id == item.id }
            if (existing >= 0) {
                items[existing] = item
            } else {
                items += item
            }
        }

        fun find(itemId: String): MutableItem? = items.firstOrNull { it.id == itemId }
    }

    private data class MutableItem(
        val id: String,
        var type: String,
        var primaryText: String = "",
        var secondaryText: String = "",
        var status: String = "",
        var command: String = "",
        var cwd: String = "",
    ) {
        fun toUi(sortKey: String, turnStatus: String): ChatItemUi = when (type) {
            "userMessage" -> ChatItemUi.User(
                id = id,
                sortKey = sortKey,
                text = primaryText,
            )

            "agentMessage" -> ChatItemUi.Agent(
                id = id,
                sortKey = sortKey,
                text = primaryText.ifBlank { "Codex 正在生成…" },
                status = status.ifBlank { turnStatus },
            )

            "reasoning" -> ChatItemUi.Reasoning(
                id = id,
                sortKey = sortKey,
                text = primaryText.ifBlank { "推理摘要还在生成中…" },
                status = status.ifBlank { turnStatus },
            )

            "commandExecution" -> ChatItemUi.Command(
                id = id,
                sortKey = sortKey,
                command = command.ifBlank { "pending" },
                output = primaryText,
                status = status.ifBlank { turnStatus },
            )

            "fileChange" -> ChatItemUi.FileChange(
                id = id,
                sortKey = sortKey,
                summary = secondaryText.ifBlank { "文件变更" },
                output = primaryText,
                status = status.ifBlank { turnStatus },
            )

            "plan" -> ChatItemUi.Plan(
                id = id,
                sortKey = sortKey,
                text = primaryText,
            )

            else -> ChatItemUi.Agent(
                id = id,
                sortKey = sortKey,
                text = primaryText.ifBlank { "收到一条暂未单独适配的事件。" },
                status = type,
            )
        }
    }

    companion object {
        private const val TAG = "CodexMobileVm"
        private const val PREFS_NAME = "codex_mobile_ui"
        private const val PREF_SELECTED_MODEL = "selected_model"
        private const val PREF_SELECTED_REASONING = "selected_reasoning"
        private const val PREF_FAST_MODE_ENABLED = "fast_mode_enabled"
        private const val PREF_PERMISSION_MODE = "permission_mode"
        private const val PREF_FONT_SCALE = "font_scale"
        private const val PREF_ACTIVE_THREAD_ID = "active_thread_id"
        private const val PREF_ACTIVE_THREAD_TITLE = "active_thread_title"
        private const val PREF_LAST_OPENED_THREAD_ID = "last_opened_thread_id"
        private const val PREF_LAST_OPENED_THREAD_TITLE = "last_opened_thread_title"
        private const val PREF_LOCAL_DRAFTS = "local_drafts"
        private const val PREF_ARCHIVED_THREAD_IDS = "archived_thread_ids"
        private const val PREF_DELETED_THREAD_IDS = "deleted_thread_ids"
        private const val PREF_CUSTOM_THREAD_TITLES = "custom_thread_titles"
        private const val LOCAL_DRAFT_THREAD_ID = "__local_new_thread__"
        private const val LOCAL_DRAFT_THREAD_PREFIX = "__local_new_thread__-"

        private const val DEFAULT_MODEL = "gpt-5.4"
        private const val DEFAULT_REASONING = "xhigh"
        private const val FAST_SERVICE_TIER = "fast"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val THREAD_LIMIT = 60
        private const val RPC_URL = "ws://127.0.0.1:8765"
        private const val PENDING_TURN_RECOVERY_MS = 3_000L
        private const val PENDING_TURN_FAIL_MS = 12_000L
        private const val THREAD_TIMESTAMP_SKEW_MS = 1_500L
        private val BACKEND_OUTPUT_TYPES = setOf(
            "agentMessage",
            "reasoning",
            "commandExecution",
            "fileChange",
            "plan",
        )

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("M/d HH:mm", Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.systemDefault())

        fun displayThreadName(name: String?, preview: String?, threadId: String? = null): String {
            return normalizeDisplayText(name)
                ?: normalizeDisplayText(preview)?.lineSequence()?.firstOrNull()?.let(::normalizeDisplayText)
                ?: "新会话"
        }

        private fun normalizeDisplayText(value: String?): String? {
            val text = value?.trim().orEmpty()
            if (text.isBlank()) {
                return null
            }
            return when (text.lowercase()) {
                "null",
                "undefined" -> null
                else -> text
            }
        }
    }
}
