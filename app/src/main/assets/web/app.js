const DEFAULT_PROJECT_ROOT = "/data/data/com.termux/files/home";
const DEFAULT_MODEL = "gpt-5.4";
const APPROVAL_POLICY = "untrusted";
const THREAD_LIMIT = 24;
const RPC_URL = "ws://127.0.0.1:8765";
const STORAGE_KEYS = {
  currentProjectRoot: "codex-mobile.current-project-root",
  recentProjects: "codex-mobile.recent-projects",
  favoriteProjects: "codex-mobile.favorite-projects",
  projectModels: "codex-mobile.project-models",
  autoScroll: "codex-mobile.auto-scroll",
  activeTab: "codex-mobile.active-tab",
};

function hasNativeBridge() {
  return Boolean(window.CodexHost && typeof window.CodexHost.postMessage === "function");
}

const state = {
  nativeReady: false,
  backendStatus: null,
  keepaliveStatus: null,
  appPreferences: { autoHardeningEnabled: true },
  connection: {
    status: "idle",
    userAgent: "",
    error: "",
    retryCount: 0,
  },
  models: [],
  threads: [],
  activeThreadId: null,
  activeThread: null,
  currentTurnId: null,
  approvals: [],
  approvalDrafts: {},
  config: null,
  currentProjectRoot:
    loadJson(STORAGE_KEYS.currentProjectRoot, DEFAULT_PROJECT_ROOT) || DEFAULT_PROJECT_ROOT,
  recentProjects: loadJson(STORAGE_KEYS.recentProjects, []),
  favoriteProjects: loadJson(STORAGE_KEYS.favoriteProjects, []),
  projectModels: loadJson(STORAGE_KEYS.projectModels, {}),
  draft: "",
  statusMessage: "",
  errorMessage: "",
  lastUserTextByThread: {},
  autoScroll: loadJson(STORAGE_KEYS.autoScroll, true) !== false,
  ui: {
    tab: loadJson(STORAGE_KEYS.activeTab, "chat") || "chat",
    sheet: null,
    activeApprovalId: null,
    expandedBlocks: {},
  },
};

const nativePending = new Map();
const rpcPending = new Map();
let nativeSeq = 1;
let rpcSeq = 1;
let rpcSocket = null;
let connectPromise = null;
let reconnectTimer = null;
let renderQueued = false;
let foregroundSessionActive = false;

const app = document.getElementById("app");

window.__nativeResolve = function resolveNative(envelope) {
  const entry = nativePending.get(String(envelope.id));
  if (!entry) {
    return;
  }
  nativePending.delete(String(envelope.id));
  clearTimeout(entry.timeoutId);
  if (envelope.ok) {
    entry.resolve(envelope.payload);
  } else {
    entry.reject(new Error(envelope.payload?.message || "Native bridge failed"));
  }
};

window.__hostLifecycle = function onHostLifecycle(name) {
  if (name === "resume") {
    refreshNativePanels(true).catch(handleError);
  }
};

document.addEventListener("click", handleClick);
document.addEventListener("input", handleInput);
document.addEventListener("change", handleChange);
window.addEventListener("load", () => {
  state.nativeReady = hasNativeBridge();
  console.log(`CodexMobile load nativeReady=${state.nativeReady}`);
  if (state.nativeReady) {
    refreshNativePanels(true).catch(handleError);
  } else {
    scheduleRender();
  }
});

boot().catch(handleError);

function loadJson(key, fallbackValue) {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallbackValue;
  } catch (_error) {
    return fallbackValue;
  }
}

function saveJson(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch (_error) {
    // Ignore quota and storage failures in WebView.
  }
}

function persistUiState() {
  saveJson(STORAGE_KEYS.currentProjectRoot, state.currentProjectRoot);
  saveJson(STORAGE_KEYS.recentProjects, state.recentProjects);
  saveJson(STORAGE_KEYS.favoriteProjects, state.favoriteProjects);
  saveJson(STORAGE_KEYS.projectModels, state.projectModels);
  saveJson(STORAGE_KEYS.autoScroll, state.autoScroll);
  saveJson(STORAGE_KEYS.activeTab, state.ui.tab);
}

function scheduleRender() {
  if (renderQueued) {
    return;
  }
  renderQueued = true;
  window.requestAnimationFrame(() => {
    renderQueued = false;
    renderApp();
  });
}

function handleError(error) {
  state.errorMessage = error instanceof Error ? error.message : String(error);
  scheduleRender();
  console.error(error);
}

function clearError() {
  if (!state.errorMessage) {
    return;
  }
  state.errorMessage = "";
  scheduleRender();
}

function setStatus(message) {
  state.statusMessage = message;
  scheduleRender();
}

function clearStatus() {
  if (!state.statusMessage) {
    return;
  }
  state.statusMessage = "";
  scheduleRender();
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function basename(path) {
  if (!path) {
    return "";
  }
  const normalized = path.replace(/\/+$/, "");
  const parts = normalized.split("/");
  return parts[parts.length - 1] || normalized;
}

function projectLabel(path) {
  if (!path) {
    return "未选择项目";
  }
  if (path === DEFAULT_PROJECT_ROOT) {
    return "Termux Home";
  }
  return basename(path);
}

function formatTime(unixSeconds) {
  if (!unixSeconds) {
    return "刚刚";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(unixSeconds * 1000));
}

function threadDisplayName(thread) {
  return (
    thread?.name ||
    thread?.preview ||
    (thread?.cwd ? basename(thread.cwd) : null) ||
    "新会话"
  );
}

function threadStatusLabel(status) {
  if (!status) {
    return "unknown";
  }
  if (typeof status === "string") {
    return status;
  }
  return status.type || "unknown";
}

function connectionStatusLabel(status) {
  switch (status) {
    case "connected":
      return "已连接";
    case "connecting":
      return "连接中";
    case "initializing":
      return "初始化中";
    case "starting":
      return "启动中";
    case "disconnected":
      return "已断开";
    case "error":
      return "异常";
    default:
      return "待机";
  }
}

function pill(label, kind = "") {
  const cls = kind ? `pill ${kind}` : "pill";
  return `<span class="${cls}">${escapeHtml(label)}</span>`;
}

function currentModel() {
  if (state.projectModels[state.currentProjectRoot]) {
    return state.projectModels[state.currentProjectRoot];
  }
  const explicitDefault = state.models.find((model) => model.isDefault);
  if (explicitDefault?.model) {
    return explicitDefault.model;
  }
  const gpt54 = state.models.find((model) => model.model === DEFAULT_MODEL);
  return gpt54?.model || state.models[0]?.model || DEFAULT_MODEL;
}

function savedModelPreference() {
  return state.projectModels[state.currentProjectRoot] || null;
}

function currentModelDisplayName() {
  const selected = state.models.find((model) => model.model === currentModel());
  return selected?.displayName || selected?.model || savedModelPreference() || "";
}

function hasLiveModelList() {
  return state.connection.status === "connected" && state.models.length > 0;
}

function setCurrentModel(model) {
  state.projectModels[state.currentProjectRoot] = model;
  persistUiState();
  scheduleRender();
}

function addRecentProject(path) {
  if (!path) {
    return;
  }
  state.recentProjects = [path, ...state.recentProjects.filter((item) => item !== path)].slice(0, 10);
  persistUiState();
}

function toggleFavoriteProject(path) {
  if (!path) {
    return;
  }
  if (state.favoriteProjects.includes(path)) {
    state.favoriteProjects = state.favoriteProjects.filter((item) => item !== path);
  } else {
    state.favoriteProjects = [path, ...state.favoriteProjects].slice(0, 8);
  }
  persistUiState();
  scheduleRender();
}

function isFavoriteProject(path) {
  return state.favoriteProjects.includes(path);
}

function setCurrentProjectRoot(path) {
  state.currentProjectRoot = path || DEFAULT_PROJECT_ROOT;
  addRecentProject(state.currentProjectRoot);
  persistUiState();
}

function setActiveTab(tab) {
  state.ui.tab = tab;
  persistUiState();
  scheduleRender();
}

function setSheet(name) {
  state.ui.sheet = name;
  scheduleRender();
}

function closeSheet() {
  state.ui.sheet = null;
  scheduleRender();
}

function currentApproval() {
  if (!state.approvals.length) {
    return null;
  }
  return (
    state.approvals.find((item) => item.requestId === state.ui.activeApprovalId) || state.approvals[0]
  );
}

function setActiveApproval(requestId) {
  state.ui.activeApprovalId = requestId;
  scheduleRender();
}

function toggleExpanded(key) {
  state.ui.expandedBlocks[key] = !state.ui.expandedBlocks[key];
  scheduleRender();
}

function findThreadSummary(threadId) {
  return state.threads.find((thread) => thread.id === threadId) || null;
}

function upsertThreadSummary(thread) {
  if (!thread?.id) {
    return;
  }
  const nextThread = {
    ...findThreadSummary(thread.id),
    ...thread,
  };
  state.threads = [
    nextThread,
    ...state.threads.filter((item) => item.id !== thread.id),
  ]
    .sort((left, right) => (right.updatedAt || 0) - (left.updatedAt || 0))
    .slice(0, THREAD_LIMIT);
}

function orderedThreads() {
  return [...state.threads].sort((left, right) => (right.updatedAt || 0) - (left.updatedAt || 0));
}

function flattenThreadItems(thread) {
  if (!thread?.turns) {
    return [];
  }
  return thread.turns.flatMap((turn) =>
    (turn.items || []).map((item) => ({
      ...item,
      __turnId: turn.id,
      __turnStatus: turn.status,
    })),
  );
}

function inferLastUserText(thread) {
  const userItem = [...flattenThreadItems(thread)]
    .reverse()
    .find((item) => item.type === "userMessage");
  if (!userItem) {
    return "";
  }
  const textPart = (userItem.content || []).find((entry) => entry.type === "text");
  return textPart?.text || "";
}

function ensureTurn(threadId, turnId) {
  if (!state.activeThread || state.activeThread.id !== threadId) {
    return null;
  }
  let turn = state.activeThread.turns.find((item) => item.id === turnId);
  if (!turn) {
    turn = { id: turnId, items: [], status: "inProgress", error: null };
    state.activeThread.turns = [...state.activeThread.turns, turn];
  }
  return turn;
}

function upsertTurnItem(threadId, turnId, item) {
  const turn = ensureTurn(threadId, turnId);
  if (!turn || !item?.id) {
    return;
  }
  const existing = turn.items.find((entry) => entry.id === item.id);
  if (existing) {
    Object.assign(existing, item);
  } else {
    turn.items = [...turn.items, item];
  }
  if (item.type === "userMessage") {
    const textPart = (item.content || []).find((entry) => entry.type === "text");
    if (textPart?.text) {
      state.lastUserTextByThread[threadId] = textPart.text;
    }
  }
}

function updateTurnDelta(threadId, turnId, itemId, kind, delta) {
  const turn = ensureTurn(threadId, turnId);
  if (!turn) {
    return;
  }
  let item = turn.items.find((entry) => entry.id === itemId);
  if (!item) {
    item =
      kind === "agent"
        ? { type: "agentMessage", id: itemId, text: "", phase: null }
        : kind === "command"
          ? {
              type: "commandExecution",
              id: itemId,
              command: "pending",
              cwd: state.currentProjectRoot,
              processId: null,
              status: "inProgress",
              commandActions: [],
              aggregatedOutput: "",
              exitCode: null,
              durationMs: null,
            }
          : {
              type: "fileChange",
              id: itemId,
              changes: [],
              status: "inProgress",
            };
    turn.items = [...turn.items, item];
  }
  if (kind === "agent") {
    item.text = `${item.text || ""}${delta}`;
  } else {
    item.__deltaBuffer = `${item.__deltaBuffer || ""}${delta}`;
  }
}

async function nativeCall(method, payload = {}) {
  state.nativeReady = hasNativeBridge();
  if (!state.nativeReady) {
    console.warn(`Native bridge unavailable for ${method}`);
    throw new Error("Native bridge unavailable");
  }
  const id = `native-${nativeSeq++}`;
  const message = JSON.stringify({ id, method, payload });
  return new Promise((resolve, reject) => {
    const timeoutId = window.setTimeout(() => {
      nativePending.delete(id);
      reject(new Error(`Native bridge timeout: ${method}`));
    }, 20000);
    nativePending.set(id, { resolve, reject, timeoutId });
    try {
      window.CodexHost.postMessage(message);
    } catch (error) {
      nativePending.delete(id);
      clearTimeout(timeoutId);
      reject(error);
    }
  });
}

function isSocketOpen() {
  return Boolean(rpcSocket && rpcSocket.readyState === WebSocket.OPEN);
}

function closeSocket(manual = false) {
  if (!rpcSocket) {
    return;
  }
  const socket = rpcSocket;
  rpcSocket = null;
  try {
    socket.close(1000, manual ? "manual-close" : "reconnect");
  } catch (_error) {
    // Ignore close races.
  }
}

function rejectPendingRpc(reason) {
  for (const [id, entry] of rpcPending.entries()) {
    clearTimeout(entry.timeoutId);
    entry.reject(reason);
    rpcPending.delete(id);
  }
}

async function ensureRpcReady() {
  if (isSocketOpen()) {
    return;
  }
  if (hasNativeBridge()) {
    if (!state.backendStatus) {
      await refreshNativePanels(false);
    }
    if (!state.backendStatus?.backendListening && state.backendStatus?.rootAvailable) {
      state.connection.status = "starting";
      scheduleRender();
      state.backendStatus = await nativeCall("ensureBackendRunning");
      scheduleRender();
    }
  }
  await connectRpc();
}

async function connectRpc(force = false) {
  if (isSocketOpen() && !force) {
    return;
  }
  if (connectPromise && !force) {
    return connectPromise;
  }
  if (force) {
    closeSocket(true);
  }

  connectPromise = new Promise((resolve, reject) => {
    const socket = new WebSocket(RPC_URL);
    rpcSocket = socket;
    state.connection.status = "connecting";
    state.connection.error = "";
    scheduleRender();

    socket.addEventListener("open", async () => {
      try {
        state.connection.status = "initializing";
        scheduleRender();
        const initResponse = await rpcRequestInternal("initialize", {
          clientInfo: { name: "Codex Mobile", version: "0.1.0" },
          capabilities: { experimentalApi: true },
        });
        state.connection.status = "connected";
        state.connection.userAgent = initResponse.userAgent || "";
        state.connection.retryCount = 0;
        await refreshRemoteData();
        resolve();
      } catch (error) {
        state.connection.status = "error";
        state.connection.error = error.message || String(error);
        reject(error);
        closeSocket(true);
      } finally {
        scheduleRender();
        syncForegroundState().catch(console.error);
      }
    });

    socket.addEventListener("message", (event) => {
      handleRpcMessage(event.data);
    });

    socket.addEventListener("error", () => {
      state.connection.error = "WebSocket 连接失败";
      scheduleRender();
    });

    socket.addEventListener("close", () => {
      if (rpcSocket === socket) {
        rpcSocket = null;
      }
      rejectPendingRpc(new Error("RPC socket closed"));
      if (state.connection.status !== "idle") {
        state.connection.status = "disconnected";
      }
      scheduleRender();
      syncForegroundState().catch(console.error);
      scheduleReconnect();
      if (connectPromise) {
        connectPromise = null;
      }
    });
  });

  try {
    await connectPromise;
  } finally {
    connectPromise = null;
  }
}

function scheduleReconnect() {
  if (reconnectTimer || !hasNativeBridge()) {
    return;
  }
  if (!state.backendStatus?.termuxInstalled) {
    return;
  }
  if (!state.backendStatus?.backendListening && !state.backendStatus?.rootAvailable) {
    return;
  }
  const delay = Math.min(8000, 1500 + state.connection.retryCount * 1200);
  reconnectTimer = window.setTimeout(async () => {
    reconnectTimer = null;
    state.connection.retryCount += 1;
    try {
      await refreshNativePanels(false);
      if (!state.backendStatus?.backendListening && state.backendStatus?.rootAvailable) {
        await nativeCall("ensureBackendRunning");
        await refreshNativePanels(false);
      }
      if (state.backendStatus?.backendListening) {
        await connectRpc();
      }
    } catch (error) {
      state.connection.error = error.message || String(error);
      scheduleRender();
      scheduleReconnect();
    }
  }, delay);
}

function sendRpcEnvelope(envelope) {
  if (!isSocketOpen()) {
    throw new Error("RPC socket not open");
  }
  rpcSocket.send(JSON.stringify(envelope));
}

function rpcRequestInternal(method, params = {}, timeoutMs = 25000) {
  const id = `rpc-${rpcSeq++}`;
  sendRpcEnvelope({
    jsonrpc: "2.0",
    id,
    method,
    params,
  });
  return new Promise((resolve, reject) => {
    const timeoutId = window.setTimeout(() => {
      rpcPending.delete(id);
      reject(new Error(`RPC timeout: ${method}`));
    }, timeoutMs);
    rpcPending.set(id, { resolve, reject, timeoutId, method });
  });
}

async function rpcRequest(method, params = {}, timeoutMs = 25000) {
  if (!isSocketOpen()) {
    await ensureRpcReady();
  }
  return rpcRequestInternal(method, params, timeoutMs);
}

function rpcRespond(id, result) {
  sendRpcEnvelope({
    jsonrpc: "2.0",
    id,
    result,
  });
}

function handleRpcMessage(rawMessage) {
  let message;
  try {
    message = JSON.parse(rawMessage);
  } catch (_error) {
    return;
  }

  if (
    message.id &&
    (Object.prototype.hasOwnProperty.call(message, "result") ||
      Object.prototype.hasOwnProperty.call(message, "error")) &&
    !message.method
  ) {
    const pending = rpcPending.get(String(message.id));
    if (!pending) {
      return;
    }
    rpcPending.delete(String(message.id));
    clearTimeout(pending.timeoutId);
    if (message.error) {
      pending.reject(new Error(message.error.message || pending.method || "RPC failed"));
    } else {
      pending.resolve(message.result);
    }
    return;
  }

  if (message.method?.startsWith("codex/event/")) {
    return;
  }

  if (message.id && message.method) {
    handleServerRequest(message).catch(handleError);
    return;
  }

  if (message.method) {
    handleNotification(message);
  }
}

async function handleServerRequest(message) {
  const requestId = String(message.id);
  const params = message.params || {};

  if (message.method === "item/commandExecution/requestApproval") {
    enqueueApproval({
      requestId,
      requestIdRaw: message.id,
      kind: "command",
      createdAt: Date.now(),
      ...params,
    });
    return;
  }

  if (message.method === "item/fileChange/requestApproval") {
    enqueueApproval({
      requestId,
      requestIdRaw: message.id,
      kind: "file",
      createdAt: Date.now(),
      ...params,
    });
    return;
  }

  if (message.method === "item/tool/requestUserInput") {
    enqueueApproval({
      requestId,
      requestIdRaw: message.id,
      kind: "user-input",
      createdAt: Date.now(),
      ...params,
    });
    return;
  }

  rpcRespond(requestId, {});
}

function enqueueApproval(approval) {
  state.approvals = [
    approval,
    ...state.approvals.filter((item) => item.requestId !== approval.requestId),
  ];
  state.ui.activeApprovalId = approval.requestId;
  state.ui.sheet = "approval";
  scheduleRender();
  syncForegroundState().catch(console.error);
}

function removeApproval(requestId) {
  state.approvals = state.approvals.filter((item) => item.requestId !== requestId);
  delete state.approvalDrafts[requestId];
  if (state.ui.activeApprovalId === requestId) {
    state.ui.activeApprovalId = state.approvals[0]?.requestId || null;
  }
  if (!state.approvals.length && state.ui.sheet === "approval") {
    state.ui.sheet = null;
  }
  scheduleRender();
  syncForegroundState().catch(console.error);
}

function handleNotification(message) {
  const params = message.params || {};

  switch (message.method) {
    case "thread/started":
      upsertThreadSummary(params.thread);
      break;
    case "thread/status/changed":
      upsertThreadSummary({
        id: params.threadId,
        status: params.status,
        updatedAt: Math.floor(Date.now() / 1000),
      });
      if (state.activeThread?.id === params.threadId) {
        state.activeThread.status = params.status;
      }
      break;
    case "turn/started":
      state.currentTurnId = params.turn?.id || state.currentTurnId;
      if (state.activeThread?.id === params.threadId && params.turn) {
        ensureTurn(params.threadId, params.turn.id);
      }
      scheduleRender();
      syncForegroundState().catch(console.error);
      break;
    case "turn/completed":
      if (params.turn) {
        state.currentTurnId = null;
        const turn = ensureTurn(params.threadId, params.turn.id);
        if (turn) {
          Object.assign(turn, params.turn);
        }
        refreshThreadDetails(params.threadId, true).catch(handleError);
      }
      scheduleRender();
      syncForegroundState().catch(console.error);
      break;
    case "item/started":
    case "item/completed":
      upsertTurnItem(params.threadId, params.turnId, params.item);
      scheduleRender();
      break;
    case "item/agentMessage/delta":
      updateTurnDelta(params.threadId, params.turnId, params.itemId, "agent", params.delta || "");
      scheduleRender();
      break;
    case "item/commandExecution/outputDelta":
      updateTurnDelta(params.threadId, params.turnId, params.itemId, "command", params.delta || "");
      scheduleRender();
      break;
    case "item/fileChange/outputDelta":
      updateTurnDelta(params.threadId, params.turnId, params.itemId, "file", params.delta || "");
      scheduleRender();
      break;
    case "turn/plan/updated":
      upsertTurnItem(params.threadId, params.turnId, {
        type: "plan",
        id: `plan-${params.turnId}`,
        text: formatPlan(params.explanation, params.plan || []),
      });
      scheduleRender();
      break;
    case "serverRequest/resolved":
      removeApproval(String(params.requestId));
      break;
    default:
      break;
  }
}

function formatPlan(explanation, plan) {
  const lines = [];
  if (explanation) {
    lines.push(explanation);
  }
  for (const step of plan || []) {
    lines.push(`[${step.status}] ${step.step}`);
  }
  return lines.join("\n");
}

async function refreshNativePanels(tryConnect = false) {
  state.nativeReady = hasNativeBridge();
  if (!state.nativeReady) {
    console.warn("refreshNativePanels skipped: no native bridge");
    scheduleRender();
    return;
  }
  console.log(`refreshNativePanels tryConnect=${tryConnect}`);
  const [backendStatus, keepaliveStatus, appPreferences] = await Promise.all([
    nativeCall("getBackendStatus"),
    nativeCall("getKeepaliveStatus"),
    nativeCall("getAppPreferences"),
  ]);
  state.backendStatus = backendStatus;
  state.keepaliveStatus = keepaliveStatus;
  state.appPreferences = appPreferences;
  scheduleRender();

  if (tryConnect && backendStatus.termuxInstalled) {
    if (!backendStatus.backendListening && backendStatus.rootAvailable) {
      const ensured = await nativeCall("ensureBackendRunning");
      state.backendStatus = ensured;
      scheduleRender();
    }
    if (state.backendStatus?.backendListening) {
      await connectRpc();
    }
  }
}

async function refreshRemoteData() {
  const [modelsResult, threadsResult, configResult] = await Promise.allSettled([
    rpcRequest("model/list", {}),
    rpcRequest("thread/list", {
      limit: THREAD_LIMIT,
      cwd: state.currentProjectRoot,
    }),
    rpcRequest("config/read", {
      includeLayers: false,
      cwd: state.currentProjectRoot,
    }),
  ]);

  if (modelsResult.status === "fulfilled") {
    state.models = modelsResult.value.data || [];
  }
  if (threadsResult.status === "fulfilled") {
    state.threads = threadsResult.value.data || [];
  }
  if (configResult.status === "fulfilled") {
    state.config = configResult.value.config || null;
  }

  if (modelsResult.status === "rejected" && threadsResult.status === "rejected") {
    throw modelsResult.reason;
  }

  if (state.activeThreadId && !state.threads.some((thread) => thread.id === state.activeThreadId)) {
    state.activeThreadId = null;
    state.activeThread = null;
  }

  if (!state.activeThreadId && state.threads.length > 0) {
    await refreshThreadDetails(state.threads[0].id, false);
  } else if (state.activeThreadId) {
    await refreshThreadDetails(state.activeThreadId, true);
  } else {
    scheduleRender();
  }
}

async function refreshThreadDetails(threadId, keepSelection) {
  const response = await rpcRequest("thread/read", {
    threadId,
    includeTurns: true,
  });
  state.activeThread = response.thread;
  state.activeThreadId = keepSelection === false ? threadId : state.activeThreadId || threadId;
  if (!state.activeThreadId) {
    state.activeThreadId = threadId;
  }
  upsertThreadSummary(response.thread);
  if (response.thread?.cwd) {
    state.currentProjectRoot = response.thread.cwd;
    addRecentProject(response.thread.cwd);
    persistUiState();
  }
  const inferred = inferLastUserText(response.thread);
  if (inferred) {
    state.lastUserTextByThread[threadId] = inferred;
  }
  scheduleRender();
}

async function boot() {
  state.nativeReady = hasNativeBridge();
  console.log(`boot nativeReady=${state.nativeReady}`);
  addRecentProject(state.currentProjectRoot);
  scheduleRender();
  if (state.nativeReady) {
    await refreshNativePanels(true);
  }
}

function buildTextInput(text) {
  return [{ type: "text", text, text_elements: [] }];
}

async function ensureThreadForCurrentProject() {
  if (state.activeThreadId && state.activeThread?.cwd === state.currentProjectRoot) {
    return state.activeThreadId;
  }
  const response = await rpcRequest("thread/start", {
    model: currentModel(),
    modelProvider: "openai",
    cwd: state.currentProjectRoot,
    approvalPolicy: APPROVAL_POLICY,
    sandbox: "danger-full-access",
    experimentalRawEvents: false,
    persistExtendedHistory: true,
  });
  const thread = response.thread;
  state.activeThreadId = thread.id;
  state.activeThread = { ...thread, turns: [] };
  upsertThreadSummary(thread);
  addRecentProject(state.currentProjectRoot);
  scheduleRender();
  return thread.id;
}

async function sendPrompt(promptText) {
  if (!promptText.trim()) {
    return;
  }
  clearError();
  setStatus("正在发送给 Codex…");
  const threadId = await ensureThreadForCurrentProject();
  state.lastUserTextByThread[threadId] = promptText.trim();
  state.draft = "";
  scheduleRender();
  const response = await rpcRequest("turn/start", {
    threadId,
    input: buildTextInput(promptText.trim()),
    model: currentModel(),
    approvalPolicy: APPROVAL_POLICY,
    sandboxPolicy: { type: "dangerFullAccess" },
    cwd: state.currentProjectRoot,
  });
  state.currentTurnId = response.turn?.id || state.currentTurnId;
  clearStatus();
  scheduleRender();
  syncForegroundState().catch(console.error);
}

async function continueGeneration() {
  if (!state.activeThreadId) {
    throw new Error("还没有活动会话");
  }
  const promptText = "继续上一段输出。";
  if (state.currentTurnId) {
    await rpcRequest("turn/steer", {
      threadId: state.activeThreadId,
      expectedTurnId: state.currentTurnId,
      input: buildTextInput(promptText),
    });
    return;
  }
  await sendPrompt(promptText);
}

async function retryLastUserMessage() {
  if (!state.activeThreadId) {
    throw new Error("还没有可重试的会话");
  }
  const lastText = state.lastUserTextByThread[state.activeThreadId];
  if (!lastText) {
    throw new Error("没有找到上一条用户消息");
  }
  await sendPrompt(lastText);
}

async function interruptTurn() {
  if (!state.activeThreadId || !state.currentTurnId) {
    return;
  }
  await rpcRequest("turn/interrupt", {
    threadId: state.activeThreadId,
    turnId: state.currentTurnId,
  });
  state.currentTurnId = null;
  scheduleRender();
  syncForegroundState().catch(console.error);
}

async function answerApproval(requestId, payload) {
  clearError();
  const approval = state.approvals.find((item) => item.requestId === requestId);
  rpcRespond(approval?.requestIdRaw ?? requestId, payload);
  removeApproval(requestId);
}

async function answerUserInputApproval(requestId) {
  const approval = state.approvals.find((item) => item.requestId === requestId);
  if (!approval) {
    return;
  }
  const draft = { ...(state.approvalDrafts[requestId] || {}) };
  for (const question of approval.questions || []) {
    if (!draft[question.id]) {
      throw new Error("需要先回答全部问题");
    }
  }
  const answers = {};
  for (const question of approval.questions || []) {
    answers[question.id] = { answers: [draft[question.id]] };
  }
  await answerApproval(requestId, { answers });
}

function canAttemptRemoteRefresh() {
  return Boolean(state.backendStatus?.backendListening || state.backendStatus?.rootAvailable || isSocketOpen());
}

async function chooseProjectRoot() {
  const result = await nativeCall("pickProjectRoot");
  if (!result?.path) {
    return;
  }
  closeSheet();
  setCurrentProjectRoot(result.path);
  state.activeThreadId = null;
  state.activeThread = null;
  if (canAttemptRemoteRefresh()) {
    await refreshRemoteData();
  } else {
    scheduleRender();
  }
}

async function selectProject(path) {
  closeSheet();
  setCurrentProjectRoot(path);
  state.activeThreadId = null;
  state.activeThread = null;
  setActiveTab("chat");
  if (canAttemptRemoteRefresh()) {
    await refreshRemoteData();
  } else {
    scheduleRender();
  }
}

async function openThread(threadId) {
  clearError();
  setStatus("正在读取线程…");
  state.activeThreadId = threadId;
  setActiveTab("chat");
  await refreshThreadDetails(threadId, false);
  clearStatus();
}

function syncForegroundState() {
  const shouldBeActive = Boolean(state.currentTurnId || state.approvals.length > 0);
  state.nativeReady = hasNativeBridge();
  if (shouldBeActive === foregroundSessionActive || !state.nativeReady) {
    return Promise.resolve();
  }
  foregroundSessionActive = shouldBeActive;
  return nativeCall("setForegroundSessionActive", { active: shouldBeActive });
}

function canCompose() {
  const backend = state.backendStatus || {};
  if (!backend.termuxInstalled) {
    return false;
  }
  const status = deriveConnectionState().code;
  if (status === "root_missing" || status === "auth_missing") {
    return false;
  }
  return Boolean(backend.backendListening || backend.rootAvailable || state.connection.status === "connected");
}

function composerPlaceholder() {
  const status = deriveConnectionState().code;
  if (status === "root_missing") {
    return "先给 Codex Mobile root 授权，App 才能自行拉起手机上的 Codex 后端。";
  }
  if (status === "auth_missing") {
    return "先回 Termux 确认 Codex 已登录，再从这里继续聊天。";
  }
  if (status === "error") {
    return "连接有异常，先点上方状态卡片里的恢复动作。";
  }
  return "直接给本机 Codex 下指令，命令审批和补丁审批会以底部弹层出现。";
}

function deriveConnectionState() {
  const backend = state.backendStatus || {};

  if (!backend.termuxInstalled && state.nativeReady) {
    return {
      code: "error",
      tone: "danger",
      shortLabel: "未检测到 Termux",
      title: "没有检测到 Termux",
      detail: "这套 UI 需要复用你手机里的 Termux 环境和现有 Codex 登录状态。",
      actions: ["refresh-status"],
    };
  }

  if (state.connection.status === "connected") {
    return {
      code: "connected",
      tone: "ok",
      shortLabel: "已连接",
      title: "Codex 后端已连接",
      detail: state.connection.userAgent
        ? `已连上 ${state.connection.userAgent}，模型和线程数据都走真实后端。`
        : "模型列表、线程列表和审批都会从本机 Codex 服务实时同步。",
      actions: ["refresh-status"],
    };
  }

  if (
    state.connection.status === "connecting" ||
    state.connection.status === "initializing" ||
    state.connection.status === "starting"
  ) {
    return {
      code: "starting",
      tone: "info",
      shortLabel: "连接中",
      title: "正在连接本机 Codex",
      detail: "App 正在检查 Termux、拉起 app-server，并等待模型与线程同步完成。",
      actions: ["refresh-status"],
    };
  }

  if (backend.backendListening && backend.rootAvailable && backend.authPresent === false) {
    return {
      code: "auth_missing",
      tone: "warn",
      shortLabel: "缺少认证",
      title: "没有检测到 Codex 认证",
      detail: "需要回 Termux 完成登录，App 才能读取真实模型和线程。",
      actions: ["open-termux", "refresh-status"],
    };
  }

  if (backend.backendListening && state.connection.error) {
    return {
      code: "error",
      tone: "danger",
      shortLabel: "连接异常",
      title: "后端在线，但连接初始化失败",
      detail: state.connection.error,
      actions: ["restart-backend", "refresh-status"],
    };
  }

  if (backend.backendListening) {
    return {
      code: "starting",
      tone: "info",
      shortLabel: "后端在线",
      title: "本机后端已在线",
      detail: "App 正在尝试重新建立 WebSocket 连接并恢复当前线程。",
      actions: ["refresh-status", "restart-backend"],
    };
  }

  if (backend.termuxInstalled && !backend.rootAvailable) {
    return {
      code: "root_missing",
      tone: "warn",
      shortLabel: "缺少 Root",
      title: "还没有拿到 root 授权",
      detail: "没有 root 时，App 不能自己拉起 Termux 里的 Codex 后端，也不能执行保活加固。",
      actions: ["refresh-status", "open-termux"],
    };
  }

  if (backend.termuxInstalled && backend.rootAvailable && !backend.backendListening) {
    return {
      code: "backend_missing",
      tone: "warn",
      shortLabel: "后端未启动",
      title: "Codex app-server 还没起来",
      detail: "可以直接在这里尝试拉起后端，或者回 Termux 手动确认服务状态。",
      actions: ["ensure-backend", "restart-backend", "open-termux"],
    };
  }

  if (state.connection.error) {
    return {
      code: "error",
      tone: "danger",
      shortLabel: "异常",
      title: "连接状态异常",
      detail: state.connection.error,
      actions: ["refresh-status"],
    };
  }

  return {
    code: "starting",
    tone: "info",
    shortLabel: "检查中",
    title: "正在读取本机状态",
    detail: "等待读取 Termux、root、后端和认证状态。",
    actions: ["refresh-status"],
  };
}

function actionLabel(action) {
  switch (action) {
    case "refresh-status":
      return "刷新状态";
    case "ensure-backend":
      return "启动后端";
    case "restart-backend":
      return "重启后端";
    case "open-termux":
      return "打开 Termux";
    default:
      return action;
  }
}

function actionClass(action) {
  switch (action) {
    case "ensure-backend":
    case "refresh-status":
      return "primary-btn";
    case "restart-backend":
      return "secondary-btn";
    default:
      return "ghost-btn";
  }
}

function renderDecisionLabel(decision) {
  switch (decision) {
    case "accept":
      return "批准";
    case "acceptForSession":
      return "本会话放行";
    case "decline":
      return "拒绝";
    case "cancel":
      return "取消";
    default:
      return decision;
  }
}

function renderTag(label, tone) {
  return `<span class="tag ${tone}">${escapeHtml(label)}</span>`;
}

function renderMetric(label, value, tone) {
  return `
    <div class="metric-card ${tone}">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
    </div>
  `;
}

function renderApp() {
  const connectionState = deriveConnectionState();
  app.innerHTML = `
    <div class="mobile-shell">
      <main class="page-stack">
        ${renderBannerStack()}
        ${renderCurrentPage(connectionState)}
      </main>
      ${state.approvals.length ? renderApprovalDock() : ""}
      ${renderBottomNav()}
      ${renderBottomSheet()}
    </div>
  `;

  if (state.autoScroll) {
    const stream = document.querySelector("[data-role='conversation-stream']");
    if (stream) {
      stream.scrollTop = stream.scrollHeight;
    }
  }
}

function renderBannerStack() {
  const notices = [];
  if (state.errorMessage) {
    notices.push(`
      <section class="notice-banner danger">
        <div>
          <strong>连接提示</strong>
          <p>${escapeHtml(state.errorMessage)}</p>
        </div>
        <button type="button" class="ghost-btn compact" data-action="dismiss-error">关闭</button>
      </section>
    `);
  }
  if (state.statusMessage) {
    notices.push(`
      <section class="notice-banner info">
        <div>
          <strong>状态</strong>
          <p>${escapeHtml(state.statusMessage)}</p>
        </div>
        <button type="button" class="ghost-btn compact" data-action="dismiss-status">收起</button>
      </section>
    `);
  }
  return notices.join("");
}

function renderCurrentPage(connectionState) {
  switch (state.ui.tab) {
    case "sessions":
      return renderSessionsPage();
    case "settings":
      return renderSettingsPage(connectionState);
    case "chat":
    default:
      return renderChatPage(connectionState);
  }
}

function renderChatPage(connectionState) {
  const activeThreadItems = flattenThreadItems(state.activeThread);
  const threadMeta = state.activeThread
    ? `
        <section class="surface-card thread-summary">
          <div>
            <div class="section-kicker">当前会话</div>
            <h2>${escapeHtml(threadDisplayName(state.activeThread))}</h2>
            <p>${escapeHtml(state.activeThread.cwd || state.currentProjectRoot)}</p>
          </div>
          <div class="stack-tags">
            ${renderTag(threadStatusLabel(state.activeThread.status), "plain")}
            ${renderTag(`${activeThreadItems.length} 条内容`, "plain")}
          </div>
        </section>
      `
    : `
        <section class="surface-card welcome-card">
          <div class="section-kicker">聊天</div>
          <h2>从一个项目开始</h2>
          <p>点顶部项目芯片切换目录，然后直接在下方输入框里对本机 Codex 发指令。</p>
        </section>
      `;

  return `
    <section class="page chat-page">
      <header class="surface-card page-header compact-header">
        <div class="title-block">
          <div class="section-kicker">Codex Mobile</div>
          <button type="button" class="project-chip" data-action="open-project-sheet">
            <span class="project-chip-title">${escapeHtml(projectLabel(state.currentProjectRoot))}</span>
            <span class="project-chip-subtitle">${escapeHtml(state.currentProjectRoot)}</span>
          </button>
        </div>
        <div class="header-actions">
          ${renderStatusPill(connectionState)}
          <button type="button" class="ghost-btn compact" data-action="new-thread">新会话</button>
        </div>
      </header>

      ${renderConnectionCard(connectionState)}
      ${state.approvals.length ? renderApprovalPromptBar() : ""}
      ${threadMeta}
      ${renderConversationArea(activeThreadItems)}
      ${renderComposer(connectionState)}
    </section>
  `;
}

function renderSessionsPage() {
  const threads = orderedThreads();
  const items = threads.length
    ? threads
        .map((thread) => {
          const isCurrent = thread.id === state.activeThreadId;
          const isSameProject = thread.cwd === state.currentProjectRoot;
          return `
            <button
              type="button"
              class="thread-row ${isCurrent ? "active" : ""}"
              data-action="open-thread"
              data-thread-id="${escapeHtml(thread.id)}"
            >
              <div class="thread-row-main">
                <div class="thread-title-line">
                  <strong>${escapeHtml(threadDisplayName(thread))}</strong>
                  ${isCurrent ? renderTag("当前", "accent") : ""}
                  ${isSameProject ? renderTag("同项目", "plain") : ""}
                </div>
                <p>${escapeHtml(thread.cwd || state.currentProjectRoot)}</p>
              </div>
              <div class="thread-row-meta">
                <span>${escapeHtml(formatTime(thread.updatedAt))}</span>
                <span>${escapeHtml(threadStatusLabel(thread.status))}</span>
              </div>
            </button>
          `;
        })
        .join("")
    : `
        <section class="surface-card empty-state">
          <div class="section-kicker">会话</div>
          <h2>当前项目还没有线程</h2>
          <p>回到聊天页发送第一条消息后，这里会自动出现可继续的线程。</p>
          <div class="inline-actions">
            <button type="button" class="primary-btn" data-action="set-tab" data-tab="chat">去聊天页</button>
          </div>
        </section>
      `;

  return `
    <section class="page sessions-page">
      <header class="surface-card page-header">
        <div class="title-block">
          <div class="section-kicker">会话</div>
          <h2>最近线程</h2>
          <p>当前项目：${escapeHtml(projectLabel(state.currentProjectRoot))}</p>
        </div>
        <button type="button" class="ghost-btn compact" data-action="refresh-status">刷新</button>
      </header>
      <section class="surface-card list-card">
        <div class="section-head">
          <div>
            <strong>${escapeHtml(String(threads.length))} 条线程</strong>
            <p>优先显示当前项目相关内容。</p>
          </div>
        </div>
        <div class="thread-list">${items}</div>
      </section>
    </section>
  `;
}

function renderSettingsPage(connectionState) {
  const backend = state.backendStatus || {};
  const keepalive = state.keepaliveStatus || {};
  const modelList = hasLiveModelList()
    ? state.models
        .map(
          (model) => `
            <button
              type="button"
              class="model-option ${model.model === currentModel() ? "selected" : ""}"
              data-action="select-model"
              data-model="${escapeHtml(model.model)}"
            >
              <div>
                <strong>${escapeHtml(model.displayName || model.model)}</strong>
                <p>${escapeHtml(model.model)}</p>
              </div>
              <div class="stack-tags">
                ${model.isDefault ? renderTag("默认", "plain") : ""}
                ${model.model === currentModel() ? renderTag("当前项目", "accent") : ""}
              </div>
            </button>
          `,
        )
        .join("")
    : `
        <div class="inline-status-card ${connectionState.tone}">
          <strong>模型列表暂不可用</strong>
          <p>只有拿到真实的 model.list 结果后，这里才会显示模型，不再展示假兜底值。</p>
          ${savedModelPreference() ? `<p>当前项目已保存偏好：${escapeHtml(savedModelPreference())}</p>` : ""}
        </div>
      `;

  return `
    <section class="page settings-page">
      <header class="surface-card page-header">
        <div class="title-block">
          <div class="section-kicker">设置</div>
          <h2>模型与设备</h2>
          <p>把模型入口收进设置页，首页专注聊天和审批。</p>
        </div>
      </header>

      <section class="surface-card settings-group">
        <div class="section-head">
          <div>
            <strong>模型与行为</strong>
            <p>模型只显示真实后端返回的列表，按项目记住选择。</p>
          </div>
        </div>
        <div class="settings-grid">
          <div class="settings-row">
            <div>
              <strong>当前项目</strong>
              <p>${escapeHtml(projectLabel(state.currentProjectRoot))}</p>
            </div>
            <span>${escapeHtml(state.currentProjectRoot)}</span>
          </div>
          <div class="settings-row">
            <div>
              <strong>自动滚动</strong>
              <p>新消息到达时自动把聊天区滚到底部。</p>
            </div>
            <button type="button" class="toggle-btn ${state.autoScroll ? "on" : ""}" data-action="toggle-auto-scroll">
              ${state.autoScroll ? "已开启" : "已关闭"}
            </button>
          </div>
          <div class="settings-row">
            <div>
              <strong>审批策略</strong>
              <p>当前保持移动端更稳的审批模式。</p>
            </div>
            <span>${escapeHtml(state.config?.approval_policy || APPROVAL_POLICY)}</span>
          </div>
          <div class="settings-row">
            <div>
              <strong>当前模型</strong>
              <p>${hasLiveModelList() ? "来自真实后端模型列表。" : "等待后端连接后加载。"}</p>
            </div>
            <span>${escapeHtml(currentModelDisplayName() || "未加载")}</span>
          </div>
        </div>
        <div class="model-list">${modelList}</div>
      </section>

      <section class="surface-card settings-group">
        <div class="section-head">
          <div>
            <strong>设备与保活</strong>
            <p>这里统一处理 Termux、root、后端状态和一键加固。</p>
          </div>
        </div>
        <div class="stats-grid">
          ${renderMetric("Termux", backend.termuxInstalled ? "已安装" : "缺失", backend.termuxInstalled ? "ok" : "danger")}
          ${renderMetric("Root", backend.rootAvailable ? "已授权" : "未授权", backend.rootAvailable ? "ok" : "warn")}
          ${renderMetric("后端", backend.backendListening ? "8765 在线" : "未监听", backend.backendListening ? "ok" : "warn")}
          ${renderMetric("认证", backend.authPresent ? "已检测" : "未检测", backend.authPresent ? "ok" : "warn")}
          ${renderMetric("Doze", keepalive.deviceIdleWhitelisted ? "已白名单" : "未加固", keepalive.deviceIdleWhitelisted ? "ok" : "warn")}
          ${renderMetric("后台白名单", keepalive.restrictBackgroundWhitelisted ? "已放行" : "未放行", keepalive.restrictBackgroundWhitelisted ? "ok" : "warn")}
          ${renderMetric("Bucket", keepalive.standbyBucket || "unknown", keepalive.standbyBucket === "active" ? "ok" : "warn")}
          ${renderMetric("UI 省电", keepalive.batteryOptimizationIgnoredForUiApp ? "已豁免" : "未豁免", keepalive.batteryOptimizationIgnoredForUiApp ? "ok" : "warn")}
        </div>
        <div class="action-grid">
          <button type="button" class="primary-btn" data-action="ensure-backend">启动后端</button>
          <button type="button" class="secondary-btn" data-action="restart-backend">重启后端</button>
          <button type="button" class="secondary-btn" data-action="run-hardening">一键加固</button>
          <button type="button" class="ghost-btn" data-action="toggle-auto-hardening">
            ${state.appPreferences.autoHardeningEnabled ? "关闭自动补齐" : "开启自动补齐"}
          </button>
          <button type="button" class="ghost-btn" data-action="request-battery-ignore">请求 UI 省电豁免</button>
          <button type="button" class="ghost-btn" data-action="open-termux">打开 Termux</button>
        </div>
      </section>
    </section>
  `;
}

function renderConnectionCard(connectionState) {
  const buttons = connectionState.actions
    .map(
      (action) => `
        <button type="button" class="${actionClass(action)} compact" data-action="${escapeHtml(action)}">
          ${escapeHtml(actionLabel(action))}
        </button>
      `,
    )
    .join("");

  return `
    <section class="surface-card connection-card ${connectionState.tone}">
      <div class="section-head">
        <div>
          <div class="section-kicker">连接状态</div>
          <strong>${escapeHtml(connectionState.title)}</strong>
          <p>${escapeHtml(connectionState.detail)}</p>
        </div>
        ${renderStatusPill(connectionState)}
      </div>
      <div class="inline-actions">${buttons}</div>
    </section>
  `;
}

function renderStatusPill(connectionState) {
  return `
    <span class="status-pill ${connectionState.tone}">
      <span class="status-dot"></span>
      ${escapeHtml(connectionState.shortLabel)}
    </span>
  `;
}

function renderApprovalPromptBar() {
  const approval = currentApproval();
  const label =
    approval?.kind === "command"
      ? "命令审批"
      : approval?.kind === "file"
        ? "补丁审批"
        : "工具提问";
  return `
    <section class="approval-strip">
      <div>
        <strong>${escapeHtml(String(state.approvals.length))} 个待处理审批</strong>
        <p>${escapeHtml(label)} 会以底部弹层显示，不再挤在主聊天区。</p>
      </div>
      <button type="button" class="primary-btn compact" data-action="open-approval-sheet">立即处理</button>
    </section>
  `;
}

function renderConversationArea(items) {
  if (!state.activeThreadId || !state.activeThread) {
    return `
      <section class="surface-card conversation-card empty-conversation">
        <div class="empty-state">
          <h2>还没有活动线程</h2>
          <p>发送第一条消息时，App 会自动用当前项目创建线程并开始流式显示。</p>
        </div>
      </section>
    `;
  }

  const messageHtml = items.length
    ? items.map((item) => renderItemBubble(item)).join("")
    : `<div class="empty-state compact-empty"><p>这条线程还没有历史内容，第一条消息发出后会开始显示。</p></div>`;

  return `
    <section class="surface-card conversation-card">
      <div class="conversation-head">
        <div>
          <strong>${escapeHtml(threadDisplayName(state.activeThread))}</strong>
          <p>${escapeHtml(formatTime(state.activeThread.updatedAt || 0))}</p>
        </div>
        <div class="stack-tags">
          ${renderTag(connectionStatusLabel(state.connection.status), "plain")}
          ${renderTag(threadStatusLabel(state.activeThread.status), "plain")}
        </div>
      </div>
      <article class="conversation-stream" data-role="conversation-stream">
        ${messageHtml}
      </article>
    </section>
  `;
}

function renderItemBubble(item) {
  if (item.type === "userMessage") {
    const textPart = (item.content || []).find((entry) => entry.type === "text");
    return `
      <article class="message-bubble user">
        <div class="bubble-meta"><span>你</span><span>${escapeHtml(item.__turnId || "")}</span></div>
        <div class="bubble-text">${escapeHtml(textPart?.text || "")}</div>
      </article>
    `;
  }

  if (item.type === "agentMessage") {
    return `
      <article class="message-bubble agent">
        <div class="bubble-meta"><span>Codex</span><span>${escapeHtml(item.phase || "streaming")}</span></div>
        <div class="bubble-text">${escapeHtml(item.text || "")}</div>
      </article>
    `;
  }

  if (item.type === "plan") {
    return `
      <article class="message-bubble plan">
        <div class="bubble-meta"><span>Plan</span><span>${escapeHtml(item.__turnId || "")}</span></div>
        ${renderExpandableBlock(item.text || "", `plan-${item.id}`, "计划详情")}
      </article>
    `;
  }

  if (item.type === "reasoning") {
    const parts = [...(item.summary || []), ...(item.content || [])].join("\n");
    return `
      <article class="message-bubble system">
        <div class="bubble-meta"><span>Reasoning</span><span>${escapeHtml(item.__turnStatus || "")}</span></div>
        ${renderExpandableBlock(parts || "推理摘要还在生成中…", `reasoning-${item.id}`, "推理摘要")}
      </article>
    `;
  }

  if (item.type === "commandExecution") {
    const output = item.aggregatedOutput || item.__deltaBuffer || "";
    return `
      <article class="message-bubble command">
        <div class="bubble-meta"><span>命令</span><span>${escapeHtml(item.status || "")}</span></div>
        <div class="detail-stack">
          <div class="mono-line">${escapeHtml(item.command || "pending")}</div>
          <p>${escapeHtml(item.cwd || state.currentProjectRoot)}</p>
          ${output ? renderExpandableBlock(output, `command-${item.id}`, "命令输出") : ""}
        </div>
      </article>
    `;
  }

  if (item.type === "fileChange") {
    const changes = (item.changes || [])
      .map((change) => `${change.kind}: ${change.path}\n${change.diff}`)
      .join("\n\n");
    const output = changes || item.__deltaBuffer || "";
    return `
      <article class="message-bubble file">
        <div class="bubble-meta"><span>补丁</span><span>${escapeHtml(item.status || "")}</span></div>
        ${output ? renderExpandableBlock(output, `file-${item.id}`, "Diff") : `<p>等待 diff…</p>`}
      </article>
    `;
  }

  return `
    <article class="message-bubble system">
      <div class="bubble-meta"><span>${escapeHtml(item.type || "item")}</span><span>${escapeHtml(item.__turnId || "")}</span></div>
      ${renderExpandableBlock(JSON.stringify(item, null, 2), `raw-${item.id || item.__turnId}`, "原始内容")}
    </article>
  `;
}

function renderComposer(connectionState) {
  const disabled = !canCompose();
  const savedModel = currentModelDisplayName();
  return `
    <section class="surface-card composer-card">
      <div class="composer-toolbar">
        <div class="stack-tags">
          ${renderTag(state.currentTurnId ? "生成中" : "空闲", state.currentTurnId ? "warn" : "ok")}
          ${renderTag(connectionState.shortLabel, connectionState.tone)}
          ${savedModel && hasLiveModelList() ? renderTag(savedModel, "plain") : ""}
        </div>
        <div class="inline-actions">
          <button type="button" class="ghost-btn compact" data-action="continue-turn" ${!state.activeThreadId ? "disabled" : ""}>继续</button>
          <button type="button" class="ghost-btn compact" data-action="retry-turn" ${!state.activeThreadId ? "disabled" : ""}>重试</button>
          <button type="button" class="danger-btn compact" data-action="interrupt-turn" ${!state.currentTurnId ? "disabled" : ""}>停止</button>
        </div>
      </div>
      <textarea
        data-role="composer"
        ${disabled ? "disabled" : ""}
        placeholder="${escapeHtml(composerPlaceholder())}"
      >${escapeHtml(state.draft)}</textarea>
      <div class="composer-footer">
        <div class="composer-hint">
          <span>${escapeHtml(projectLabel(state.currentProjectRoot))}</span>
          <span>${escapeHtml(state.currentProjectRoot)}</span>
        </div>
        <div class="inline-actions">
          <button type="button" class="ghost-btn" data-action="open-termux">打开 Termux</button>
          <button type="button" class="primary-btn" data-action="send-prompt" ${disabled ? "disabled" : ""}>发送</button>
        </div>
      </div>
    </section>
  `;
}

function renderBottomNav() {
  const tabs = [
    { id: "chat", label: "聊天" },
    { id: "sessions", label: "会话" },
    { id: "settings", label: "设置" },
  ];
  return `
    <nav class="bottom-nav">
      ${tabs
        .map(
          (tab) => `
            <button
              type="button"
              class="nav-item ${state.ui.tab === tab.id ? "active" : ""}"
              data-action="set-tab"
              data-tab="${escapeHtml(tab.id)}"
            >
              <span>${escapeHtml(tab.label)}</span>
            </button>
          `,
        )
        .join("")}
    </nav>
  `;
}

function renderApprovalDock() {
  return `
    <button type="button" class="approval-dock" data-action="open-approval-sheet">
      <span>${escapeHtml(String(state.approvals.length))} 个审批待处理</span>
      <span>点此展开</span>
    </button>
  `;
}

function renderBottomSheet() {
  if (state.ui.sheet === "project") {
    return renderProjectSheet();
  }
  if (state.ui.sheet === "approval" && state.approvals.length) {
    return renderApprovalSheet();
  }
  return "";
}

function renderProjectSheet() {
  const favorites = state.favoriteProjects.length
    ? state.favoriteProjects
        .map(
          (path) => `
            <button type="button" class="sheet-row" data-action="open-project" data-path="${escapeHtml(path)}">
              <div>
                <strong>${escapeHtml(projectLabel(path))}</strong>
                <p>${escapeHtml(path)}</p>
              </div>
              ${path === state.currentProjectRoot ? renderTag("当前", "accent") : ""}
            </button>
          `,
        )
        .join("")
    : `<div class="sheet-empty">还没有收藏目录，先把常用项目收进来。</div>`;

  const recent = state.recentProjects.length
    ? state.recentProjects
        .map(
          (path) => `
            <button type="button" class="sheet-row" data-action="open-project" data-path="${escapeHtml(path)}">
              <div>
                <strong>${escapeHtml(projectLabel(path))}</strong>
                <p>${escapeHtml(path)}</p>
              </div>
            </button>
          `,
        )
        .join("")
    : `<div class="sheet-empty">最近项目会在你切换目录后自动记录。</div>`;

  return `
    <div class="sheet-scrim" data-action="close-sheet"></div>
    <section class="bottom-sheet project-sheet">
      <div class="sheet-handle"></div>
      <div class="sheet-header">
        <div>
          <div class="section-kicker">项目</div>
          <strong>${escapeHtml(projectLabel(state.currentProjectRoot))}</strong>
          <p>${escapeHtml(state.currentProjectRoot)}</p>
        </div>
        <button type="button" class="ghost-btn compact" data-action="close-sheet">关闭</button>
      </div>
      <div class="sheet-actions">
        <button type="button" class="primary-btn" data-action="choose-project">选择常用目录</button>
        <button type="button" class="secondary-btn" data-action="favorite-project">
          ${isFavoriteProject(state.currentProjectRoot) ? "取消收藏当前目录" : "收藏当前目录"}
        </button>
      </div>
      <div class="sheet-section">
        <div class="section-head">
          <div>
            <strong>收藏目录</strong>
            <p>适合长期保留和反复进入的项目。</p>
          </div>
        </div>
        <div class="sheet-list">${favorites}</div>
      </div>
      <div class="sheet-section">
        <div class="section-head">
          <div>
            <strong>最近项目</strong>
            <p>聊天页顶部项目芯片会从这里快速恢复上下文。</p>
          </div>
        </div>
        <div class="sheet-list">${recent}</div>
      </div>
    </section>
  `;
}

function renderApprovalSheet() {
  const approval = currentApproval();
  if (!approval) {
    return "";
  }
  const queue = state.approvals
    .map(
      (item, index) => `
        <button
          type="button"
          class="queue-pill ${item.requestId === approval.requestId ? "active" : ""}"
          data-action="select-approval"
          data-request-id="${escapeHtml(item.requestId)}"
        >
          ${escapeHtml(`${index + 1}`)}
        </button>
      `,
    )
    .join("");

  return `
    <div class="sheet-scrim" data-action="close-sheet"></div>
    <section class="bottom-sheet approval-sheet">
      <div class="sheet-handle"></div>
      <div class="sheet-header">
        <div>
          <div class="section-kicker">审批</div>
          <strong>${escapeHtml(approvalTitle(approval))}</strong>
          <p>${escapeHtml(approvalSubtitle(approval))}</p>
        </div>
        <button type="button" class="ghost-btn compact" data-action="close-sheet">收起</button>
      </div>
      <div class="queue-strip">${queue}</div>
      ${renderApprovalBody(approval)}
    </section>
  `;
}

function approvalTitle(approval) {
  if (approval.kind === "command") {
    return "命令审批";
  }
  if (approval.kind === "file") {
    return "补丁审批";
  }
  return "工具提问";
}

function approvalSubtitle(approval) {
  if (approval.kind === "command") {
    return approval.command || approval.reason || "命令执行请求";
  }
  if (approval.kind === "file") {
    return approval.reason || "文件修改请求";
  }
  return `${approval.questions?.length || 0} 个问题等待回答`;
}

function renderApprovalBody(approval) {
  if (approval.kind === "user-input") {
    const questionsHtml = (approval.questions || [])
      .map((question) => renderQuestionCard(approval.requestId, question))
      .join("");
    return `
      <div class="sheet-section">
        <div class="sheet-list">${questionsHtml}</div>
        <div class="sheet-actions">
          <button type="button" class="primary-btn" data-action="approval-submit-user-input" data-request-id="${escapeHtml(approval.requestId)}">提交答案</button>
          <button type="button" class="danger-btn" data-action="approval-reject" data-request-id="${escapeHtml(approval.requestId)}" data-kind="cancel">取消</button>
        </div>
      </div>
    `;
  }

  const details = approvalDetails(approval);
  const decisions =
    approval.kind === "command"
      ? (approval.availableDecisions || ["accept", "acceptForSession", "decline"]).filter(
          (item) => typeof item === "string",
        )
      : ["accept", "acceptForSession", "decline"];

  const buttons = decisions
    .map((decision) => {
      const cls =
        decision === "accept"
          ? "primary-btn"
          : decision === "decline" || decision === "cancel"
            ? "danger-btn"
            : "secondary-btn";
      return `
        <button
          type="button"
          class="${cls}"
          data-action="approval-answer"
          data-request-id="${escapeHtml(approval.requestId)}"
          data-kind="${escapeHtml(decision)}"
        >
          ${escapeHtml(renderDecisionLabel(decision))}
        </button>
      `;
    })
    .join("");

  return `
    <div class="sheet-section">
      ${approval.cwd ? `<div class="meta-block"><strong>cwd</strong><p>${escapeHtml(approval.cwd)}</p></div>` : ""}
      ${details ? renderExpandableBlock(details, `approval-${approval.requestId}`, "审批详情") : ""}
      <div class="sheet-actions">${buttons}</div>
    </div>
  `;
}

function renderQuestionCard(requestId, question) {
  const draft = state.approvalDrafts[requestId] || {};
  const options = (question.options || [])
    .map((option) => {
      const selected = draft[question.id] === option.label;
      return `
        <button
          type="button"
          class="${selected ? "primary-btn compact" : "secondary-btn compact"}"
          data-action="approval-option"
          data-request-id="${escapeHtml(requestId)}"
          data-question-id="${escapeHtml(question.id)}"
          data-answer="${escapeHtml(option.label)}"
        >
          ${escapeHtml(option.label)}
        </button>
      `;
    })
    .join("");

  return `
    <article class="question-card">
      <div>
        <strong>${escapeHtml(question.header || "提问")}</strong>
        <p>${escapeHtml(question.question)}</p>
      </div>
      ${options ? `<div class="inline-actions wrap">${options}</div>` : ""}
      <textarea
        class="question-input"
        data-role="approval-input"
        data-request-id="${escapeHtml(requestId)}"
        data-question-id="${escapeHtml(question.id)}"
        placeholder="也可以直接手输答案"
      >${escapeHtml(draft[question.id] || "")}</textarea>
    </article>
  `;
}

function approvalDetails(approval) {
  const copy = { ...approval };
  delete copy.requestId;
  delete copy.requestIdRaw;
  delete copy.createdAt;
  delete copy.kind;
  return JSON.stringify(copy, null, 2);
}

function renderExpandableBlock(content, key, label) {
  const expanded = Boolean(state.ui.expandedBlocks[key]);
  const cls = expanded ? "expandable-block expanded" : "expandable-block";
  return `
    <div class="${cls}">
      <div class="expandable-head">
        <strong>${escapeHtml(label)}</strong>
        <button type="button" class="ghost-btn compact" data-action="toggle-expand" data-key="${escapeHtml(key)}">
          ${expanded ? "收起" : "展开"}
        </button>
      </div>
      <pre>${escapeHtml(content)}</pre>
    </div>
  `;
}

async function handleAction(action, button) {
  switch (action) {
    case "set-tab":
      setActiveTab(button.dataset.tab || "chat");
      break;
    case "dismiss-error":
      clearError();
      break;
    case "dismiss-status":
      clearStatus();
      break;
    case "open-project-sheet":
      setSheet("project");
      break;
    case "open-approval-sheet":
      setSheet("approval");
      break;
    case "close-sheet":
      closeSheet();
      break;
    case "send-prompt":
      await sendPrompt(state.draft);
      break;
    case "continue-turn":
      await continueGeneration();
      break;
    case "retry-turn":
      await retryLastUserMessage();
      break;
    case "interrupt-turn":
      await interruptTurn();
      break;
    case "choose-project":
      await chooseProjectRoot();
      break;
    case "favorite-project":
      toggleFavoriteProject(state.currentProjectRoot);
      break;
    case "open-project":
      await selectProject(button.dataset.path || DEFAULT_PROJECT_ROOT);
      break;
    case "open-thread":
      await openThread(button.dataset.threadId);
      break;
    case "new-thread":
      state.activeThreadId = null;
      state.activeThread = null;
      state.currentTurnId = null;
      setActiveTab("chat");
      break;
    case "open-termux":
      await nativeCall("openTermux");
      break;
    case "run-hardening":
      state.keepaliveStatus = await nativeCall("runKeepaliveHardening");
      scheduleRender();
      break;
    case "toggle-auto-hardening":
      state.appPreferences = await nativeCall("setAutoHardeningEnabled", {
        enabled: !state.appPreferences.autoHardeningEnabled,
      });
      await refreshNativePanels(false);
      break;
    case "request-battery-ignore":
      await nativeCall("requestBatteryOptimizationIgnore");
      break;
    case "refresh-status":
      clearError();
      await refreshNativePanels(true);
      break;
    case "ensure-backend":
      clearError();
      state.connection.status = "starting";
      scheduleRender();
      state.backendStatus = await nativeCall("ensureBackendRunning");
      await refreshNativePanels(false);
      if (state.backendStatus?.backendListening) {
        await connectRpc(true);
      }
      break;
    case "restart-backend":
      state.backendStatus = await nativeCall("restartBackend");
      await refreshNativePanels(false);
      if (state.backendStatus?.backendListening) {
        await connectRpc(true);
      }
      break;
    case "select-model":
      setCurrentModel(button.dataset.model);
      break;
    case "toggle-auto-scroll":
      state.autoScroll = !state.autoScroll;
      persistUiState();
      scheduleRender();
      break;
    case "approval-answer":
      await answerApproval(button.dataset.requestId, { decision: button.dataset.kind });
      break;
    case "approval-reject":
      await answerApproval(button.dataset.requestId, { decision: button.dataset.kind || "cancel" });
      break;
    case "approval-option": {
      const requestId = button.dataset.requestId;
      const questionId = button.dataset.questionId;
      const answer = button.dataset.answer;
      state.approvalDrafts[requestId] = {
        ...(state.approvalDrafts[requestId] || {}),
        [questionId]: answer,
      };
      scheduleRender();
      break;
    }
    case "approval-submit-user-input":
      await answerUserInputApproval(button.dataset.requestId);
      break;
    case "toggle-expand":
      toggleExpanded(button.dataset.key);
      break;
    case "select-approval":
      setActiveApproval(button.dataset.requestId);
      break;
    default:
      break;
  }
}

function handleClick(event) {
  const target = event.target;
  if (target.matches(".sheet-scrim")) {
    closeSheet();
    return;
  }
  const button = target.closest("button[data-action]");
  if (!button) {
    return;
  }
  handleAction(button.dataset.action, button).catch(handleError);
}

function handleInput(event) {
  if (event.target.matches("[data-role='composer']")) {
    state.draft = event.target.value;
    return;
  }

  if (event.target.matches("[data-role='approval-input']")) {
    const requestId = event.target.dataset.requestId;
    const questionId = event.target.dataset.questionId;
    state.approvalDrafts[requestId] = {
      ...(state.approvalDrafts[requestId] || {}),
      [questionId]: event.target.value,
    };
  }
}

function handleChange(_event) {
  // Reserved for future input controls.
}
