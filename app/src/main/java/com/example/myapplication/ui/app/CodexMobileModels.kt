package com.example.myapplication.ui.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class BottomDestination(val label: String) {
    CHAT("聊天"),
    COMMANDS("命令"),
    SETTINGS("设置"),
}

enum class PermissionMode(
    val label: String,
    val detail: String,
    val approvalPolicy: String,
    val sandboxCliValue: String,
    val sandboxPolicyType: String,
) {
    DEFAULT_DENY(
        label = "默认不同意",
        detail = "只允许安全阅读；不默认给写入和执行能力。",
        approvalPolicy = "never",
        sandboxCliValue = "read-only",
        sandboxPolicyType = "readOnly",
    ),
    ASK_EACH_TIME(
        label = "按次同意",
        detail = "允许工作区写入，但命令和改动需要逐次确认。",
        approvalPolicy = "untrusted",
        sandboxCliValue = "workspace-write",
        sandboxPolicyType = "workspaceWrite",
    ),
    FULL_ACCESS(
        label = "全部放开",
        detail = "允许完整写入和执行，不再逐次确认。",
        approvalPolicy = "never",
        sandboxCliValue = "danger-full-access",
        sandboxPolicyType = "dangerFullAccess",
    ),
}

enum class FontScaleOption(
    val label: String,
    val scale: Float,
) {
    COMPACT("紧凑", 0.92f),
    NORMAL("标准", 1.0f),
    LARGE("大字", 1.12f),
}

data class ReasoningEffort(
    val value: String,
    val label: String,
)

data class ModelOption(
    val id: String,
    val displayName: String,
    val reasoningOptions: List<ReasoningEffort>,
    val defaultReasoning: String,
)

data class ThreadSummary(
    val id: String,
    val title: String,
    val updatedAtLabel: String,
    val updatedAtEpochSeconds: Long?,
    val source: String,
)

data class StatusSnapshot(
    val rootAvailable: Boolean = false,
    val termuxInstalled: Boolean = false,
    val backendListening: Boolean = false,
    val authPresent: Boolean = false,
    val autoHardeningEnabled: Boolean = true,
    val termuxUid: Int? = null,
    val backendStatusDetail: String = "",
)

data class KeepaliveSnapshot(
    val deviceIdleWhitelisted: Boolean = false,
    val restrictBackgroundWhitelisted: Boolean = false,
    val standbyBucket: String = "unknown",
    val batteryOptimizationIgnoredForUiApp: Boolean = false,
)

data class ApprovalRequestUi(
    val requestId: String,
    val requestIdRaw: Any,
    val title: String,
    val subtitle: String,
    val body: String,
    val kind: String,
)

enum class LocalMessageStatus {
    PENDING,
    FAILED,
}

enum class AttachmentKind(
    val label: String,
) {
    IMAGE("图片"),
    DOCUMENT("文件"),
}

data class ChatAttachmentUi(
    val kind: AttachmentKind,
    val displayName: String,
    val mimeType: String,
    val previewPath: String? = null,
    val backendPath: String? = null,
    val extractedTextPath: String? = null,
)

sealed interface ChatItemUi {
    val id: String
    val sortKey: String

    data class User(
        override val id: String,
        override val sortKey: String,
        val text: String,
        val attachments: List<ChatAttachmentUi> = emptyList(),
    ) : ChatItemUi

    data class LocalUser(
        override val id: String,
        override val sortKey: String,
        val text: String,
        val status: LocalMessageStatus,
        val attachments: List<ChatAttachmentUi> = emptyList(),
    ) : ChatItemUi

    data class Agent(
        override val id: String,
        override val sortKey: String,
        val text: String,
        val status: String,
    ) : ChatItemUi

    data class AgentPlaceholder(
        override val id: String,
        override val sortKey: String,
        val text: String,
    ) : ChatItemUi

    data class Reasoning(
        override val id: String,
        override val sortKey: String,
        val text: String,
        val status: String,
    ) : ChatItemUi

    data class Command(
        override val id: String,
        override val sortKey: String,
        val command: String,
        val output: String,
        val status: String,
    ) : ChatItemUi

    data class FileChange(
        override val id: String,
        override val sortKey: String,
        val summary: String,
        val output: String,
        val status: String,
    ) : ChatItemUi

    data class Plan(
        override val id: String,
        override val sortKey: String,
        val text: String,
    ) : ChatItemUi
}

data class ChatComposerState(
    val text: String = "",
    val sending: Boolean = false,
    val canInterrupt: Boolean = false,
    val attachments: List<ChatAttachmentUi> = emptyList(),
)

data class ConnectionBanner(
    val code: String = "starting",
    val title: String = "正在读取本机状态",
    val detail: String = "等待读取 Termux、root、后端和认证状态。",
    val tone: BannerTone = BannerTone.INFO,
)

enum class BannerTone {
    INFO,
    OK,
    WARN,
    DANGER,
}

data class CodexMobileUiState(
    val activeTab: BottomDestination = BottomDestination.CHAT,
    val connection: ConnectionBanner = ConnectionBanner(),
    val status: StatusSnapshot = StatusSnapshot(),
    val keepalive: KeepaliveSnapshot = KeepaliveSnapshot(),
    val availableModels: List<ModelOption> = emptyList(),
    val selectedModel: String = "gpt-5.4",
    val selectedReasoning: String = "xhigh",
    val fastModeEnabled: Boolean = false,
    val permissionMode: PermissionMode = PermissionMode.ASK_EACH_TIME,
    val fontScale: FontScaleOption = FontScaleOption.NORMAL,
    val historyThreads: List<ThreadSummary> = emptyList(),
    val archivedThreads: List<ThreadSummary> = emptyList(),
    val activeThreadId: String? = null,
    val activeThreadTitle: String = "新会话",
    val messages: List<ChatItemUi> = emptyList(),
    val composer: ChatComposerState = ChatComposerState(),
    val activeApproval: ApprovalRequestUi? = null,
    val statusMessage: String = "",
    val errorMessage: String = "",
)

internal fun reasoningLabel(value: String): String = when (value) {
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "超高"
    else -> value
}

internal fun JSONObject.optEpochSeconds(vararg keys: String): Long? {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            is Number -> return value.toLong()
            is String -> {
                value.toLongOrNull()?.let { return it }
                runCatching { Instant.parse(value).epochSecond }.getOrNull()?.let { return it }
            }
        }
    }
    return null
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.optJsonArray(key: String): JSONArray? =
    optJSONArray(key) ?: optJSONObject(key)?.optJSONArray(key)

internal fun JSONArray.toReasoningOptions(): List<ReasoningEffort> {
    val items = mutableListOf<ReasoningEffort>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val effort = item.optString("effort").ifBlank { continue }
        items += ReasoningEffort(
            value = effort,
            label = reasoningLabel(effort),
        )
    }
    return items
}
