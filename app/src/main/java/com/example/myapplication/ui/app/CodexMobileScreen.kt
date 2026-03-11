package com.example.myapplication.ui.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private enum class CommandsPanel {
    HOME,
    MODEL,
    PERMISSION,
    HISTORY,
    ARCHIVED,
}

@Composable
fun CodexMobileApp(
    viewModel: CodexMobileViewModel,
    onOpenTermux: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = density.fontScale * state.fontScale.scale,
        ),
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                CompactBottomBar(
                    activeTab = state.activeTab,
                    onSelect = viewModel::switchTab,
                )
            },
        ) { padding ->
            when (state.activeTab) {
                BottomDestination.CHAT -> ChatTab(
                    state = state,
                    padding = padding,
                    onDraftChange = viewModel::updateDraft,
                    onSend = viewModel::sendPrompt,
                    onInterrupt = viewModel::interruptTurn,
                    onRetryLocal = viewModel::retryLocalDraft,
                    onApproval = viewModel::answerApproval,
                )

                BottomDestination.COMMANDS -> CommandsTab(
                    state = state,
                    padding = padding,
                    onCreateNewConversation = viewModel::startNewConversation,
                    onSelectModel = viewModel::selectModel,
                    onSelectReasoning = viewModel::selectReasoningEffort,
                    onSelectFastMode = viewModel::selectFastMode,
                    onSelectPermission = viewModel::selectPermissionMode,
                    onOpenHistory = viewModel::openHistoryThread,
                    onArchiveHistory = viewModel::archiveHistoryThread,
                    onUnarchiveHistory = viewModel::unarchiveHistoryThread,
                    onDeleteHistory = viewModel::deleteHistoryThread,
                    onRenameHistory = viewModel::renameHistoryThread,
                    onSwitchToChat = { viewModel.switchTab(BottomDestination.CHAT) },
                )

                BottomDestination.SETTINGS -> SettingsTab(
                    state = state,
                    padding = padding,
                    onRefresh = viewModel::refreshNow,
                    onSelectFontScale = viewModel::selectFontScale,
                    onOpenTermux = onOpenTermux,
                )
            }
        }
    }
}

@Composable
private fun ChatTab(
    state: CodexMobileUiState,
    padding: PaddingValues,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onRetryLocal: (String) -> Unit,
    onApproval: (Boolean) -> Unit,
) {
    val listState = remember(state.activeThreadId) { LazyListState() }
    val scope = rememberCoroutineScope()
    var autoFollow by rememberSaveable(state.activeThreadId) { mutableStateOf(true) }
    val isNearBottom by remember(listState) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress, isNearBottom) {
        if (listState.isScrollInProgress && !isNearBottom) {
            autoFollow = false
        } else if (isNearBottom) {
            autoFollow = true
        }
    }

    LaunchedEffect(state.activeThreadId) {
        autoFollow = true
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(state.messages.lastOrNull()?.id, state.messages.size, autoFollow) {
        if (autoFollow && state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatHeader(
            title = state.activeThreadTitle,
            connection = state.connection,
            modelLabel = state.currentModelBadgeLabel(),
            showStop = state.composer.canInterrupt,
            onInterrupt = onInterrupt,
        )

        if (state.connection.code != "connected" || state.errorMessage.isNotBlank() || state.statusMessage.isNotBlank()) {
            ChatStatusStrip(
                connection = state.connection,
                errorMessage = state.errorMessage,
                statusMessage = state.statusMessage,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                if (state.messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("开始新聊天", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "这里保持干净。历史会话放在第二页，当前页只负责聊天。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = state.messages,
                            key = { "${state.activeThreadId}-${it.id}" },
                        ) { item ->
                            ChatBubble(
                                item = item,
                                onRetryLocal = onRetryLocal,
                            )
                        }
                    }
                }
            }

            if (!autoFollow && state.messages.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(state.messages.lastIndex)
                            autoFollow = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                ) {
                    Text("回到底部")
                }
            }
        }

        state.activeApproval?.let { approval ->
            ApprovalCard(
                approval = approval,
                onApprove = { onApproval(true) },
                onReject = { onApproval(false) },
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.composer.text,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("给 Codex 发消息", style = MaterialTheme.typography.bodyMedium) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(20.dp),
                )
                Button(
                    onClick = onSend,
                    enabled = state.composer.text.isNotBlank(),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Text("发送")
                }
            }
        }
    }
}

@Composable
private fun CommandsTab(
    state: CodexMobileUiState,
    padding: PaddingValues,
    onCreateNewConversation: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectReasoning: (String) -> Unit,
    onSelectFastMode: (Boolean) -> Unit,
    onSelectPermission: (PermissionMode) -> Unit,
    onOpenHistory: (String) -> Unit,
    onArchiveHistory: (String) -> Unit,
    onUnarchiveHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onRenameHistory: (String, String) -> Unit,
    onSwitchToChat: () -> Unit,
) {
    var panel by rememberSaveable { mutableStateOf(CommandsPanel.HOME) }
    var renameTarget by remember { mutableStateOf<ThreadSummary?>(null) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    val selectedModel = state.availableModels.firstOrNull { it.id == state.selectedModel }
    val reasoningOptions = selectedModel?.reasoningOptions.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.errorMessage.isNotBlank() || state.statusMessage.isNotBlank()) {
            ChatStatusStrip(
                connection = state.connection,
                errorMessage = state.errorMessage,
                statusMessage = state.statusMessage,
            )
        }

        when (panel) {
            CommandsPanel.HOME -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CommandEntryCard(
                            title = "新消息",
                            subtitle = "开始一条干净的新会话，不删除旧历史。",
                            onClick = {
                                onCreateNewConversation()
                                panel = CommandsPanel.HOME
                                onSwitchToChat()
                            },
                        )
                    }
                    item {
                        CommandEntryCard(
                            title = "历史会话",
                            subtitle = if (state.historyThreads.isEmpty()) "还没有会话索引" else "共 ${state.historyThreads.size} 条，按最近更新排序",
                            onClick = { panel = CommandsPanel.HISTORY },
                        )
                    }
                    item {
                        CommandEntryCard(
                            title = "已归档",
                            subtitle = if (state.archivedThreads.isEmpty()) "还没有归档会话" else "共 ${state.archivedThreads.size} 条，可恢复到历史列表",
                            onClick = { panel = CommandsPanel.ARCHIVED },
                        )
                    }
                    item {
                        CommandEntryCard(
                            title = "模型",
                            subtitle = state.currentModelBadgeLabel(),
                            onClick = { panel = CommandsPanel.MODEL },
                        )
                    }
                    item {
                        CommandEntryCard(
                            title = "权限",
                            subtitle = state.permissionMode.label,
                            onClick = { panel = CommandsPanel.PERMISSION },
                        )
                    }
                }
            }

            CommandsPanel.MODEL -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CommandPanelHeader(
                            title = "模型",
                            subtitle = "先换模型，再切智力和速度。",
                            onBack = { panel = CommandsPanel.HOME },
                        )
                    }
                    item {
                        SectionCard(
                            title = "模型列表",
                            subtitle = "这里展示本机后端返回的真实模型。",
                        ) {
                            if (state.availableModels.isEmpty()) {
                                Text("还没拿到真实模型列表。等本机后端连上后，这里会自动刷新。")
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(state.availableModels, key = { it.id }) { model ->
                                        FilterChip(
                                            selected = model.id == state.selectedModel,
                                            onClick = { onSelectModel(model.id) },
                                            label = { Text(model.displayName) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SectionCard(
                            title = "智力",
                            subtitle = "按当前模型支持的推理档位展示。",
                        ) {
                            if (reasoningOptions.isEmpty()) {
                                Text("当前模型没有返回可切换的智力档位。")
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(reasoningOptions, key = { it.value }) { effort ->
                                        FilterChip(
                                            selected = effort.value == state.selectedReasoning,
                                            onClick = { onSelectReasoning(effort.value) },
                                            label = { Text(effort.label) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SectionCard(
                            title = "速度",
                            subtitle = "Fast 会优先拿更快的服务层，但额度消耗更高。",
                        ) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = !state.fastModeEnabled,
                                        onClick = { onSelectFastMode(false) },
                                        label = { Text("标准") },
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = state.fastModeEnabled,
                                        onClick = { onSelectFastMode(true) },
                                        label = { Text("Fast") },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            CommandsPanel.PERMISSION -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CommandPanelHeader(
                            title = "权限",
                            subtitle = "这是一组全局模式，不是逐条审批列表。",
                            onBack = { panel = CommandsPanel.HOME },
                        )
                    }
                    item {
                        SectionCard(
                            title = "权限模式",
                            subtitle = "只保留三档：默认不同意、按次同意、全部放开。",
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                PermissionMode.entries.forEach { mode ->
                                    PermissionModeRow(
                                        mode = mode,
                                        selected = mode == state.permissionMode,
                                        onClick = { onSelectPermission(mode) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            CommandsPanel.HISTORY -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CommandPanelHeader(
                            title = "历史会话",
                            subtitle = "整页列表。点一条后回到聊天页继续。",
                            onBack = { panel = CommandsPanel.HOME },
                        )
                    }
                    if (state.historyThreads.isEmpty()) {
                        item {
                            SectionCard(
                                title = "还没有历史索引",
                                subtitle = "等本机状态和后端都读到后，会话会出现在这里。",
                            ) {
                                Text("当前没有可供打开的历史会话。")
                            }
                        }
                    } else {
                        items(state.historyThreads, key = { it.id }) { thread ->
                            HistoryRow(
                                thread = thread,
                                selected = thread.id == state.activeThreadId,
                                onOpen = {
                                    onOpenHistory(thread.id)
                                    panel = CommandsPanel.HOME
                                    onSwitchToChat()
                                },
                                actionLabel = "归档",
                                onAction = { onArchiveHistory(thread.id) },
                                onRename = {
                                    renameTarget = thread
                                    renameDraft = thread.title
                                },
                            )
                        }
                    }
                }
            }

            CommandsPanel.ARCHIVED -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CommandPanelHeader(
                            title = "已归档",
                            subtitle = "这里显示已经归档隐藏的会话。可随时恢复。",
                            onBack = { panel = CommandsPanel.HOME },
                        )
                    }
                    if (state.archivedThreads.isEmpty()) {
                        item {
                            SectionCard(
                                title = "还没有归档会话",
                                subtitle = "从历史会话或当前会话里点归档后，会出现在这里。",
                            ) {
                                Text("当前没有可恢复的归档会话。")
                            }
                        }
                    } else {
                        items(state.archivedThreads, key = { it.id }) { thread ->
                            HistoryRow(
                                thread = thread,
                                selected = false,
                                onOpen = {
                                    onOpenHistory(thread.id)
                                    panel = CommandsPanel.HOME
                                    onSwitchToChat()
                                },
                                actionLabel = "恢复",
                                onAction = { onUnarchiveHistory(thread.id) },
                                secondaryActionLabel = "删除",
                                onSecondaryAction = { onDeleteHistory(thread.id) },
                                onRename = {
                                    renameTarget = thread
                                    renameDraft = thread.title
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                renameTarget = null
                renameDraft = ""
            },
            title = { Text("重命名会话") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "长按会话后可以直接改名。保存后，历史和已归档都会同步显示这个名字。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("会话名称") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameHistory(target.id, renameDraft)
                        renameTarget = null
                        renameDraft = ""
                    },
                    enabled = renameDraft.trim().isNotEmpty(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        renameDraft = ""
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SettingsTab(
    state: CodexMobileUiState,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onSelectFontScale: (FontScaleOption) -> Unit,
    onOpenTermux: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "设备状态",
                subtitle = "root 保活、后端拉起和自动恢复都已经嵌进生命周期里，这里只看结果。",
            ) {
                StatusLine("连接", state.connection.title)
                StatusLine("Root", if (state.status.rootAvailable) "已授权" else "未授权")
                StatusLine("Termux", if (state.status.termuxInstalled) "已检测到" else "未检测到")
                StatusLine("后端", if (state.status.backendListening) "8765 已监听" else "未监听")
                StatusLine("认证", if (state.status.authPresent) "已存在" else "缺少 auth.json")
                StatusLine("保活", if (state.status.autoHardeningEnabled) "自动补齐已启用" else "未启用")
                StatusLine("Doze 白名单", if (state.keepalive.deviceIdleWhitelisted) "已加入" else "未加入")
                StatusLine("后台白名单", if (state.keepalive.restrictBackgroundWhitelisted) "已加入" else "未加入")
                StatusLine("待机桶", state.keepalive.standbyBucket)
                state.status.backendStatusDetail.takeIf { it.isNotBlank() }?.let { detail ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "显示",
                subtitle = "先保留最必要的一项：字号。",
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FontScaleOption.entries, key = { it.name }) { option ->
                        FilterChip(
                            selected = option == state.fontScale,
                            onClick = { onSelectFontScale(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "恢复入口",
                subtitle = "聊天页保持干净，手动重检只留在这里。",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh) {
                        Text("重新检查")
                    }
                    Button(onClick = onOpenTermux) {
                        Text("打开 Termux")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(
    title: String,
    connection: ConnectionBanner,
    modelLabel: String,
    showStop: Boolean,
    onInterrupt: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (connection.code) {
                    "connected" -> "已连接"
                    "reconnecting" -> "恢复中"
                    "starting" -> "启动中"
                    "auth_missing" -> "未登录"
                    "root_missing" -> "缺少 root"
                    else -> "未连接"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showStop) {
                TextButton(
                    onClick = onInterrupt,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("停止")
                }
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = modelLabel,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when (connection.tone) {
                            BannerTone.OK -> MaterialTheme.colorScheme.primary
                            BannerTone.INFO -> MaterialTheme.colorScheme.secondary
                            BannerTone.WARN -> MaterialTheme.colorScheme.tertiary
                            BannerTone.DANGER -> MaterialTheme.colorScheme.error
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun ChatStatusStrip(
    connection: ConnectionBanner,
    errorMessage: String,
    statusMessage: String,
) {
    val containerColor = when {
        errorMessage.isNotBlank() -> MaterialTheme.colorScheme.errorContainer
        connection.tone == BannerTone.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        connection.tone == BannerTone.DANGER -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val message = when {
        errorMessage.isNotBlank() -> errorMessage
        statusMessage.isNotBlank() -> statusMessage
        else -> connection.detail
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when {
                            errorMessage.isNotBlank() -> MaterialTheme.colorScheme.error
                            connection.tone == BannerTone.WARN -> MaterialTheme.colorScheme.tertiary
                            connection.tone == BannerTone.DANGER -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.secondary
                        },
                        shape = CircleShape,
                    ),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompactBottomBar(
    activeTab: BottomDestination,
    onSelect: (BottomDestination) -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomDestination.entries.forEach { tab ->
                val selected = activeTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(tab) }
                        .padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (selected) 8.dp else 7.dp)
                            .background(
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            ),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandPanelHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.padding(0.dp)) {
                Text("返回")
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CommandEntryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun ChatBubble(
    item: ChatItemUi,
    onRetryLocal: (String) -> Unit,
) {
    val isUser = item is ChatItemUi.User || item is ChatItemUi.LocalUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.9f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (item) {
                    is ChatItemUi.User -> {
                        Text("你", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer {
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    is ChatItemUi.LocalUser -> {
                        val label = when (item.status) {
                            LocalMessageStatus.PENDING -> "你 · 发送中"
                            LocalMessageStatus.FAILED -> "你 · 发送失败"
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (item.status == LocalMessageStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        SelectionContainer {
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (item.status == LocalMessageStatus.FAILED) {
                            TextButton(
                                onClick = { onRetryLocal(item.id) },
                                modifier = Modifier.padding(0.dp),
                            ) {
                                Text("重发")
                            }
                        }
                    }

                    is ChatItemUi.Agent -> {
                        Text(
                            assistantBubbleLabel(item.status),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        SelectionContainer {
                            AssistantMessageBody(
                                text = item.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    is ChatItemUi.AgentPlaceholder -> {
                        Text("Codex", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(
                                item.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is ChatItemUi.Reasoning -> {
                        Text("推理 · ${item.status}", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            AssistantMessageBody(
                                text = item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is ChatItemUi.Command -> {
                        Text("命令 · ${item.status}", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(item.command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                        if (item.output.isNotBlank()) {
                            SelectionContainer {
                                CodePanel(item.output)
                            }
                        }
                    }

                    is ChatItemUi.FileChange -> {
                        Text("补丁 · ${item.status}", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(item.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (item.output.isNotBlank()) {
                            SelectionContainer {
                                CodePanel(item.output)
                            }
                        }
                    }

                    is ChatItemUi.Plan -> {
                        Text("计划", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodePanel(text: String) {
    val scrollState = rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(scrollState)
                .padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun AssistantMessageBody(
    text: String,
    style: TextStyle,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseAssistantBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is AssistantBlock.Paragraph -> Text(
                    text = block.text,
                    style = style,
                    color = color,
                )

                is AssistantBlock.Code -> CodePanel(block.code)
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: ApprovalRequestUi,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(approval.title, style = MaterialTheme.typography.titleMedium)
            Text(approval.subtitle, style = MaterialTheme.typography.bodyMedium)
            if (approval.body.isNotBlank()) {
                CodePanel(approval.body)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReject) {
                    Text("拒绝")
                }
                Button(onClick = onApprove) {
                    Text("同意")
                }
            }
        }
    }
}

@Composable
private fun PermissionModeRow(
    mode: PermissionMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(mode.label, style = MaterialTheme.typography.titleMedium)
            Text(mode.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    thread: ThreadSummary,
    selected: Boolean,
    onOpen: () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { onRename?.invoke() },
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(thread.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${thread.updatedAtLabel} · ${thread.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                secondaryActionLabel?.let { label ->
                    TextButton(
                        onClick = { onSecondaryAction?.invoke() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(label)
                    }
                }
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

private fun CodexMobileUiState.currentModelBadgeLabel(): String {
    val selected = availableModels.firstOrNull { it.id == selectedModel }
    val modelName = selected?.displayName ?: selectedModel
    val effortLabel = selected
        ?.reasoningOptions
        ?.firstOrNull { it.value == selectedReasoning }
        ?.label
        ?: reasoningLabel(selectedReasoning)
    return buildString {
        append(modelName)
        append(" · ")
        append(effortLabel)
        if (fastModeEnabled) {
            append(" · Fast")
        }
    }
}

private fun assistantBubbleLabel(status: String): String {
    val normalized = status.trim().lowercase()
    if (normalized.isBlank() || normalized == "final_answer" || normalized == "agentmessage") {
        return "Codex"
    }
    return "Codex · ${status.replace('_', ' ')}"
}


private sealed interface AssistantBlock {
    data class Paragraph(val text: String) : AssistantBlock
    data class Code(val code: String) : AssistantBlock
}

private fun parseAssistantBlocks(raw: String): List<AssistantBlock> {
    val source = raw.replace("\r\n", "\n")
    if (!source.contains("```")) {
        return paragraphBlocks(source)
    }

    val blocks = mutableListOf<AssistantBlock>()
    var cursor = 0
    while (cursor < source.length) {
        val fenceStart = source.indexOf("```", cursor)
        if (fenceStart < 0) {
            blocks += paragraphBlocks(source.substring(cursor))
            break
        }

        if (fenceStart > cursor) {
            blocks += paragraphBlocks(source.substring(cursor, fenceStart))
        }

        val fenceEnd = source.indexOf("```", fenceStart + 3)
        if (fenceEnd < 0) {
            blocks += paragraphBlocks(source.substring(fenceStart))
            break
        }

        val fenced = source.substring(fenceStart + 3, fenceEnd).trim('\n')
        val code = fenced.substringAfter('\n', fenced).trim()
        if (code.isNotBlank()) {
            blocks += AssistantBlock.Code(code)
        }
        cursor = fenceEnd + 3
    }

    return blocks.ifEmpty { listOf(AssistantBlock.Paragraph(cleanInlineMarkdown(source))) }
}

private fun paragraphBlocks(chunk: String): List<AssistantBlock.Paragraph> =
    chunk
        .split(Regex("\n{2,}"))
        .map(::cleanInlineMarkdown)
        .filter { it.isNotBlank() }
        .map(AssistantBlock::Paragraph)

private fun cleanInlineMarkdown(text: String): String =
    text
        .replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "$1")
        .replace("`", "")
        .trim()

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
