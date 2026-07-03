const API_BASE = "https://approval.example.com";
const TEMPLATES = window.STUDENT_TEMPLATES || {};
const app = document.getElementById("app");

const state = {
  route: "boot",
  params: {},
  history: [],
  selectedGuardMinutes: 60,
  selectedReleaseMinutes: 30,
  selectedReleaseApp: null,
  lastReleaseRequestId: "",
  bindingPollTimer: null,
  installedApps: [],
  installedAppMap: new Map(),
  installedAppsLoaded: false,
  pendingPermissionRefresh: false,
  profileSnapshot: null,
  routeCache: new Map(),
  installedAppsPromise: null,
  iconCache: new Map(),
  iconRequestKeys: new Set(),
  lastHomeOverview: null,
  lastWhitelistData: null
};

const PERF_PREFIX = "StudentPerf";
const CACHE_PREFIX = "student_p0_cache:";

function perfLog(name, data = {}) {
  try {
    console.log(PERF_PREFIX, JSON.stringify({
      name,
      t: Math.round(performance.now()),
      ...data
    }));
  } catch {}
}

function readCache(key) {
  try {
    const raw = localStorage.getItem(CACHE_PREFIX + key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed && parsed.data ? parsed.data : null;
  } catch {
    return null;
  }
}

function writeCache(key, data) {
  try {
    localStorage.setItem(CACHE_PREFIX + key, JSON.stringify({
      updatedAt: Date.now(),
      data
    }));
  } catch {}
}

perfLog("js_ready");

const dataFirstRoutes = new Set([
  "home",
  "guardDetail",
  "whitelist",
  "whitelistEdit",
  "permissions",
  "onboarding",
  "temporaryAccess",
  "approvalResult",
  "profile"
]);

const cacheableRoutes = new Set([
  "home",
  "whitelist",
  "whitelistEdit",
  "permissions",
  "profile"
]);

const routeByFile = {
  "01-bind-guardian.html": "bind",
  "02-permission-onboarding.html": "onboarding",
  "03-home.html": "home",
  "04-guard-mode.html": "guardMode",
  "05-guard-detail.html": "guardDetail",
  "06-whitelist.html": "whitelist",
  "07-whitelist-edit.html": "whitelistEdit",
  "08-permissions.html": "permissions",
  "09-temporary-access.html": "temporaryAccess",
  "10-approval-result.html": "approvalResult",
  "11-profile.html": "profile"
};

const routeByHash = {
  "binding": "bind",
  "permission-onboarding": "onboarding",
  "guard-mode": "guardMode",
  "guard-detail": "guardDetail",
  "whitelist-edit": "whitelistEdit",
  "temporary-access": "temporaryAccess",
  "approval-result": "approvalResult",
  "my": "profile"
};

const permissionKeyMap = {
  accessibility: "accessibility",
  floatingWindow: "overlay",
  overlay: "overlay",
  usageAccess: "usage_access",
  usage_access: "usage_access",
  notification: "notification",
  battery: "battery_background",
  battery_background: "battery_background",
  startup: "auto_start",
  auto_start: "auto_start"
};

const permissionIconMap = {
  accessibility: "assets/icons/perm-accessibility.svg",
  overlay: "assets/icons/perm-window.svg",
  usage_access: "assets/icons/perm-chart.svg",
  notification: "assets/icons/perm-bell.svg",
  battery_background: "assets/icons/perm-battery.svg",
  auto_start: "assets/icons/perm-startup.svg"
};

const appIconClassMap = {
  "微信": "wechat",
  "QQ": "qq",
  "学习通": "study",
  "钉钉": "ding",
  "相机": "camera",
  "计算器": "calc",
  "词典": "dict",
  "浏览器": "browser",
  "哔哩哔哩": "video",
  "WPS Office": "wps",
  "网易有道词典": "dict",
  "抖音": "video"
};

function bridgeCall(methodName, payload) {
  return new Promise((resolve) => {
    const start = performance.now();
    try {
      const bridge = window.AppLockBridge;
      if (!bridge || typeof bridge[methodName] !== "function") {
        perfLog("bridge_end", { methodName, success: false, durationMs: Math.round(performance.now() - start), reason: "missing_bridge" });
        resolve({ success: false, message: "请在学生端 App 内打开后重试", data: null });
        return;
      }

      perfLog("bridge_start", { methodName });
      let raw;
      if (methodName === "startGuardMode") {
        raw = bridge.startGuardMode(Number(payload && payload.durationMinutes !== undefined ? payload.durationMinutes : payload));
      } else if (methodName === "updateWhitelist") {
        raw = bridge.updateWhitelist(JSON.stringify(payload || []));
      } else if (methodName === "openPermissionSettings") {
        raw = bridge.openPermissionSettings(String(payload && payload.permissionKey ? payload.permissionKey : payload || ""));
      } else if (methodName === "getAppIcons") {
        raw = bridge.getAppIcons(JSON.stringify(payload || {}));
      } else {
        raw = bridge[methodName]();
      }

      const parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
      const data = parsed && parsed.data ? parsed.data : {};
      perfLog("bridge_end", {
        methodName,
        success: !!parsed.success,
        durationMs: Math.round(performance.now() - start),
        jsonChars: typeof raw === "string" ? raw.length : 0,
        appCount: (methodName === "getInstalledApps" || methodName === "getInstalledAppsMeta") && Array.isArray(data.apps) ? data.apps.length : undefined,
        totalAvailable: methodName === "getInstalledApps" || methodName === "getInstalledAppsMeta" ? data.totalAvailable : undefined,
        iconRequested: methodName === "getAppIcons" ? data.requestedCount : undefined,
        iconHandled: methodName === "getAppIcons" ? data.handledCount : undefined,
        iconCacheHits: methodName === "getAppIcons" ? data.cacheHits : undefined,
        iconConverted: methodName === "getAppIcons" ? data.convertedCount : undefined
      });
      resolve({
        success: !!parsed.success,
        message: parsed.message || (parsed.success ? "操作成功" : "操作失败"),
        data: parsed.data || null
      });
    } catch (error) {
      perfLog("bridge_end", { methodName, success: false, durationMs: Math.round(performance.now() - start), error: error.message });
      resolve({ success: false, message: "本机能力调用失败：" + error.message, data: null });
    }
  });
}
window.bridgeCall = bridgeCall;

async function apiGet(path) {
  const start = performance.now();
  perfLog("api_start", { method: "GET", path });
  try {
    const response = await fetch(API_BASE + path, { headers: { Accept: "application/json" } });
    const json = await response.json();
    if (!response.ok || json.ok === false) throw new Error(json.message || json.error || "同步失败，请稍后重试");
    perfLog("api_end", { method: "GET", path, success: true, durationMs: Math.round(performance.now() - start) });
    return json;
  } catch (error) {
    perfLog("api_end", { method: "GET", path, success: false, durationMs: Math.round(performance.now() - start), error: error.message });
    throw error;
  }
}

async function apiPost(path, body) {
  const start = performance.now();
  perfLog("api_start", { method: "POST", path });
  try {
    const response = await fetch(API_BASE + path, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body || {})
    });
    const json = await response.json();
    if (!response.ok || json.ok === false) throw new Error(json.message || json.error || "同步失败，请稍后重试");
    perfLog("api_end", { method: "POST", path, success: true, durationMs: Math.round(performance.now() - start) });
    return json;
  } catch (error) {
    perfLog("api_end", { method: "POST", path, success: false, durationMs: Math.round(performance.now() - start), error: error.message });
    throw error;
  }
}

function escapeHtml(value) {
  return String(value === undefined || value === null ? "" : value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;");
}

function toast(message) {
  const old = document.querySelector(".api-toast");
  if (old) old.remove();
  const node = document.createElement("div");
  node.className = "api-toast";
  node.textContent = message || "操作完成";
  document.body.appendChild(node);
  setTimeout(() => node.remove(), 2200);
}

function isBindingBound(data) {
  const status = data && (data.bindingStatus || data.status || data.binding_status || data.data?.bindingStatus || data.data?.status);
  return status === "bound";
}

function bindingStatusOf(data) {
  const status = data && (data.bindingStatus || data.status || data.binding_status || data.data?.bindingStatus || data.data?.status);
  return ["bound", "pending", "unbound"].includes(status) ? status : "unbound";
}

function cacheBindingSnapshot(data) {
  const previous = readCache("bindingSnapshot") || {};
  const snapshot = {
    bindingStatus: bindingStatusOf(data),
    guardianName: data?.guardianName || data?.guardian?.name || data?.data?.guardianName || previous.guardianName || "",
    lastBindingCheckedAt: Date.now()
  };
  writeCache("bindingSnapshot", snapshot);
  return snapshot;
}

function cachedBindingSnapshot() {
  const start = performance.now();
  let snapshot = readCache("bindingSnapshot");
  if (!snapshot) {
    const profile = readCache("profileSnapshot");
    if (profile?.profile?.guardian?.name) {
      snapshot = {
        bindingStatus: "bound",
        guardianName: profile.profile.guardian.name,
        lastBindingCheckedAt: 0
      };
    }
  }
  perfLog("startup_binding_cache_read", {
    durationMs: Math.round(performance.now() - start),
    status: snapshot?.bindingStatus || "none"
  });
  return snapshot;
}

function cachePermissionSnapshot(report) {
  if (report && Array.isArray(report.permissions)) {
    writeCache("permissionSnapshot", {
      ...report,
      lastPermissionCheckedAt: Date.now()
    });
  }
}

function corePermissionsEnabled(report) {
  const list = report && Array.isArray(report.permissions) ? report.permissions : [];
  const usage = list.find((item) => item.key === "usage_access");
  const accessibility = list.find((item) => item.key === "accessibility");
  return !!(usage && usage.status === "enabled" && accessibility && accessibility.status === "enabled");
}

function currentBindCardLooksBound() {
  const title = app.querySelector(".binding-status-card h3")?.textContent || "";
  return title.includes("已绑定") || title.includes("绑定成功");
}

function updateBindRefreshVisibility(bound) {
  const button = app.querySelector("[data-refresh-code]");
  if (!button) return;
  button.hidden = !!bound;
  button.disabled = !!bound;
}

function statusText(status, type) {
  if (type === "guard") {
    return ({ active: "守门中", inactive: "未开启", ended: "已结束", failed: "启动失败", error: "守门异常" })[status] || "状态未知";
  }
  if (type === "binding") {
    return ({ unbound: "未绑定", pending: "等待绑定", bound: "已绑定" })[status] || "等待绑定";
  }
  if (type === "permission") {
    return ({ enabled: "已开启", disabled: "去开启", recommended: "建议确认", unknown: "检查中" })[status] || "检查中";
  }
  if (type === "release") {
    return ({ pending: "等待审批中", approved: "已同意", rejected: "已拒绝", cancelled: "已撤回", expired: "已过期" })[status] || "等待审批中";
  }
  return "";
}

function statusTone(status) {
  if (["enabled", "approved", "active", "bound"].includes(status)) return "success";
  if (["disabled", "rejected", "failed", "error", "recommended", "pending"].includes(status)) return "warn";
  return "blue";
}

function durationText(seconds) {
  const s = Math.max(0, Number(seconds || 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return h + "小时" + (m ? m + "分钟" : "");
  return Math.max(0, m) + "分钟";
}

function progressFromGuard(guard) {
  if (!guard) return 0;
  if (typeof guard.progressPercent === "number") return Math.max(0, Math.min(100, guard.progressPercent));
  const total = Number(guard.durationMinutes || 0) * 60;
  const remaining = Number(guard.remainingSeconds || 0);
  return total > 0 ? Math.round(Math.max(0, Math.min(100, ((total - remaining) * 100) / total))) : 0;
}

function packageKey(value) {
  return String(value || "").trim();
}

function appIcon(name) {
  const appData = typeof name === "object" ? name : { name };
  const installed = getInstalledApp(appData);
  const label = appData.name || installed?.name || "应用";
  const pkg = packageKey(appPackage(appData) || installed?.packageName);
  const icon = appData.iconDataUrl || (pkg ? state.iconCache.get(pkg) : "") || installed?.iconDataUrl || "";
  if (icon) {
    return `<div class="app-icon real-icon" ${pkg ? `data-icon-package="${escapeHtml(pkg)}"` : ""}><img src="${escapeHtml(icon)}" alt=""></div>`;
  }
  return `<div class="app-icon default-icon" ${pkg ? `data-icon-package="${escapeHtml(pkg)}"` : ""}>${escapeHtml(label.slice(0, 1))}</div>`;
}

function appPackage(item) {
  return item?.packageName || item?.appId || "";
}

function getInstalledApp(item) {
  const pkg = appPackage(item);
  if (pkg && state.installedAppMap.has(pkg)) return state.installedAppMap.get(pkg);
  const name = item?.name || "";
  return state.installedApps.find((appItem) => appItem.name === name) || null;
}

async function ensureInstalledApps() {
  if (state.installedAppsLoaded) return state.installedApps;
  if (state.installedAppsPromise) return state.installedAppsPromise;
  state.installedAppsPromise = bridgeCall("getInstalledAppsMeta");
  const result = await state.installedAppsPromise;
  if (result.success && result.data && Array.isArray(result.data.apps)) {
    state.installedApps = result.data.apps;
    state.installedAppMap = new Map(state.installedApps.map((item) => [item.packageName, item]));
    writeCache("installedAppsMeta", { apps: state.installedApps });
  }
  state.installedAppsLoaded = true;
  state.installedAppsPromise = null;
  return state.installedApps;
}

function loadInstalledAppsCache() {
  if (state.installedAppsLoaded || state.installedApps.length) return;
  const cached = readCache("installedAppsMeta");
  if (cached && Array.isArray(cached.apps)) {
    state.installedApps = cached.apps;
    state.installedAppMap = new Map(state.installedApps.map((item) => [item.packageName, item]));
  }
}

function refreshInstalledAppsInBackground(callback) {
  ensureInstalledApps()
    .then(() => {
      if (typeof callback === "function") callback();
    })
    .catch(() => {});
}

function rememberIcons(icons) {
  if (!icons || typeof icons !== "object") return 0;
  let count = 0;
  Object.entries(icons).forEach(([packageName, iconDataUrl]) => {
    const pkg = packageKey(packageName);
    if (pkg && typeof iconDataUrl === "string" && iconDataUrl.startsWith("data:image/")) {
      state.iconCache.set(pkg, iconDataUrl);
      count++;
    }
  });
  return count;
}

function applyLoadedIcons(packageNames) {
  (packageNames || []).forEach((packageName) => {
    const pkg = packageKey(packageName);
    const icon = pkg ? state.iconCache.get(pkg) : "";
    if (!pkg || !icon) return;
    app.querySelectorAll("[data-icon-package]").forEach((node) => {
      if (node.dataset.iconPackage !== pkg) return;
      node.className = "app-icon real-icon";
      node.innerHTML = `<img src="${escapeHtml(icon)}" alt="">`;
    });
  });
}

function iconTargetsFromApps(apps, limit) {
  const packages = [];
  const seen = new Set();
  (apps || []).forEach((item) => {
    const pkg = packageKey(appPackage(item));
    if (!pkg || seen.has(pkg) || state.iconCache.has(pkg)) return;
    seen.add(pkg);
    packages.push(pkg);
  });
  return packages.slice(0, limit || 20);
}

async function requestIconsForApps(apps, source, limit = 20) {
  const packageNames = iconTargetsFromApps(apps, limit);
  if (!packageNames.length) return;
  const requestKey = packageNames.join("|");
  if (state.iconRequestKeys.has(requestKey)) return;
  state.iconRequestKeys.add(requestKey);
  const start = performance.now();
  perfLog("icon_request_start", { source, count: packageNames.length });
  const result = await bridgeCall("getAppIcons", { packageNames });
  if (result.success && result.data) {
    const remembered = rememberIcons(result.data.icons);
    applyLoadedIcons(packageNames);
    perfLog("icon_request_done", {
      source,
      requested: packageNames.length,
      remembered,
      cacheHits: result.data.cacheHits,
      converted: result.data.convertedCount,
      durationMs: Math.round(performance.now() - start)
    });
  } else {
    perfLog("icon_request_done", { source, requested: packageNames.length, success: false, durationMs: Math.round(performance.now() - start) });
  }
}

function scheduleIconRequest(apps, source, limit = 20) {
  window.setTimeout(() => {
    requestIconsForApps(apps, source, limit);
  }, 80);
}

function enrichApp(item) {
  const installed = getInstalledApp(item);
  if (!installed) return item;
  return {
    ...item,
    name: item.name || installed.name,
    packageName: item.packageName || installed.packageName
  };
}

function mergeInstalledAddable(allowed, addable) {
  const seen = new Set();
  [...allowed, ...addable].forEach((item) => {
    const pkg = appPackage(item);
    if (pkg) seen.add(pkg);
  });
  const installedAddable = state.installedApps
    .filter((item) => item.packageName && !seen.has(item.packageName))
    .slice(0, 60)
    .map((item) => ({
      appId: item.packageName,
      packageName: item.packageName,
      name: item.name,
      category: "本机应用",
      allowed: false
    }));
  return [...addable, ...installedAddable];
}

function loadingHtml() {
  return `<div class="inline-skeleton-block"><span></span><span></span><span></span></div>`;
}

function appIconSkeleton(count = 5) {
  return Array.from({ length: count }).map(() => (
    '<div class="app-icon-skeleton"><i></i><span></span></div>'
  )).join("");
}

function listRowSkeleton(count = 3) {
  return Array.from({ length: count }).map(() => (
    '<div class="list-row skeleton-row"><div class="app-icon default-icon"></div><div class="main"><h3></h3><p></p></div><span></span></div>'
  )).join("");
}

function mountRouteTemplate() {
  const template = TEMPLATES[state.route] || TEMPLATES.home;
  app.className = template.className;
  app.innerHTML = template.html;
}

function cacheCurrentRoute() {
  if (cacheableRoutes.has(state.route) && !app.querySelector(".app-loading") && !app.querySelector(".app-error")) {
    state.routeCache.set(state.route, {
      className: app.className,
      html: app.innerHTML
    });
  }
}

function showRouteError(message) {
  if (state.routeCache.has(state.route)) {
    toast(message || "刷新失败，已保留上次数据");
    return;
  }
  const text = message || "数据暂时没有同步成功，请稍后重试";
  toast(text);
  const old = app.querySelector("[data-route-error]");
  if (old) old.remove();
  const node = document.createElement("div");
  node.className = "app-error route-inline-error";
  node.dataset.routeError = "true";
  node.innerHTML = `<p>${escapeHtml(text)}</p><button class="text-btn" type="button" data-route="${escapeHtml(state.route)}">重试</button>`;
  const target = app.querySelector(".card, .profile-user-card, .permission-summary, .home-state-card") || app.firstElementChild || app;
  target.insertAdjacentElement(target.classList && target.classList.contains("card") ? "afterend" : "beforebegin", node);
}

function routeFromHash() {
  const raw = location.hash.replace(/^#/, "");
  if (!raw) return null;
  const parts = raw.split("/");
  const route = routeByHash[parts[0]] || routeByFile[parts[0]] || parts[0];
  if (!TEMPLATES[route]) return null;
  return { route, params: { requestId: parts[1] ? decodeURIComponent(parts[1]) : "" } };
}

function setRoute(route, params = {}, replace = false) {
  if (!TEMPLATES[route]) route = "home";
  if (!replace && state.route && state.route !== "boot" && state.route !== route) {
    state.history.push({ route: state.route, params: state.params });
  }
  state.route = route;
  state.params = params;
  const suffix = params.requestId ? "/" + encodeURIComponent(params.requestId) : "";
  if (location.hash !== "#" + route + suffix) history.replaceState(null, "", "#" + route + suffix);
  renderRoute();
}

function goBack() {
  const previous = state.history.pop();
  if (previous) {
    setRoute(previous.route, previous.params, true);
    return;
  }
  if (state.route === "onboarding") setRoute("bind", {}, true);
  else if (state.route === "approvalResult") setRoute("temporaryAccess", {}, true);
  else if (state.route === "bind") setRoute("bind", {}, true);
  else setRoute("home", {}, true);
}

async function chooseInitialRoute() {
  const decisionStart = performance.now();
  const hashRoute = routeFromHash();
  if (hashRoute) {
    setRoute(hashRoute.route, hashRoute.params, true);
    perfLog("startup_route_decision", { route: hashRoute.route, reason: "explicit_hash", durationMs: Math.round(performance.now() - decisionStart) });
    return;
  }

  const bindingSnapshot = cachedBindingSnapshot();
  const permissionSnapshot = readCache("permissionSnapshot");
  const previousRoute = readCache("lastRouteDecision");
  perfLog("startup_permission_cache_read", {
    status: permissionSnapshot ? "available" : "none",
    coreReady: permissionSnapshot ? corePermissionsEnabled(permissionSnapshot) : undefined,
    previousRoute: previousRoute?.route || "none"
  });

  const localGuard = await bridgeCall("getLocalGuardStatus");
  if (localGuard.success && localGuard.data) {
    writeCache("guardStatusSnapshot", { ...localGuard.data, checkedAt: Date.now() });
  }
  if (localGuard.success && localGuard.data && localGuard.data.guardStatus === "active") {
    finishInitialRoute("home", "local_guard_active", decisionStart);
    refreshStartupSnapshotsInBackground({ keepActiveGuard: true });
    return;
  }

  if (!bindingSnapshot || bindingSnapshot.bindingStatus !== "bound") {
    finishInitialRoute("bind", "no_bound_cache", decisionStart);
    refreshStartupSnapshotsInBackground({ keepActiveGuard: false });
    return;
  }

  const perms = await bridgeCall("getLocalPermissions");
  if (perms.success && perms.data) cachePermissionSnapshot(perms.data);
  finishInitialRoute(perms.success && corePermissionsEnabled(perms.data) ? "home" : "onboarding", "bound_live_permissions", decisionStart);
  refreshStartupSnapshotsInBackground({ keepActiveGuard: false });
}

function finishInitialRoute(route, reason, startedAt) {
  writeCache("lastRouteDecision", { route, reason, decidedAt: Date.now() });
  setRoute(route, {}, true);
  perfLog("startup_route_decision", {
    route,
    reason,
    durationMs: Math.round(performance.now() - startedAt)
  });
}

function refreshStartupSnapshotsInBackground(options = {}) {
  let completed = false;
  const timeout = setTimeout(() => {
    if (!completed) perfLog("startup_binding_background_pending", { thresholdMs: 900 });
  }, 900);
  apiGet("/api/student/binding/status")
    .then(async (binding) => {
      completed = true;
      clearTimeout(timeout);
      const snapshot = cacheBindingSnapshot(binding);
      perfLog("startup_binding_background_done", { status: snapshot.bindingStatus });
      if (snapshot.bindingStatus === "bound") {
        if (state.route === "bind") await routeAfterBinding();
        return;
      }
      if (!options.keepActiveGuard && state.route !== "bind") {
        toast("监管绑定状态已变化，请重新绑定");
        setRoute("bind", {}, true);
      }
    })
    .catch(() => {
      completed = true;
      clearTimeout(timeout);
      perfLog("startup_binding_background_failed");
    });

  bridgeCall("getLocalPermissions").then((perms) => {
    if (!perms.success || !perms.data) return;
    cachePermissionSnapshot(perms.data);
    perfLog("startup_permissions_background_done", { coreReady: corePermissionsEnabled(perms.data) });
    if (state.route === "home" && !options.keepActiveGuard && !corePermissionsEnabled(perms.data)) {
      toast("请先完成必要权限设置");
      setRoute("onboarding", {}, true);
    }
  }).catch(() => {});
}

function stopBindingPoll() {
  if (state.bindingPollTimer) {
    clearInterval(state.bindingPollTimer);
    state.bindingPollTimer = null;
  }
}

async function routeAfterBinding(showSuccessToast = true) {
  cacheBindingSnapshot({ bindingStatus: "bound" });
  if (showSuccessToast) toast("绑定成功");
  const perms = await bridgeCall("getLocalPermissions");
  if (perms.success && perms.data) cachePermissionSnapshot(perms.data);
  if (perms.success && corePermissionsEnabled(perms.data)) setRoute("home");
  else setRoute("onboarding");
}

function startBindingPoll() {
  stopBindingPoll();
  state.bindingPollTimer = setInterval(async () => {
    if (state.route !== "bind") {
      stopBindingPoll();
      return;
    }
    try {
      const data = await apiGet("/api/student/binding/status");
      if (isBindingBound(data)) {
        cacheBindingSnapshot(data);
        const statusCard = app.querySelector(".binding-status-card h3");
        const statusDesc = app.querySelector(".binding-status-card p");
        if (statusCard) statusCard.textContent = "绑定成功";
        if (statusDesc) statusDesc.textContent = data.guardianName ? "监管人：" + data.guardianName : "监管人已绑定";
        updateBindRefreshVisibility(true);
        stopBindingPoll();
        await routeAfterBinding();
      }
    } catch {
      // Binding page should stay usable when the network is temporarily unavailable.
    }
  }, 5000);
}

function renderRoute() {
  if (state.route !== "bind") stopBindingPoll();
  const template = TEMPLATES[state.route] || TEMPLATES.home;
  const cached = cacheableRoutes.has(state.route) ? state.routeCache.get(state.route) : null;
  if (cached) {
    app.className = cached.className;
    app.innerHTML = cached.html;
  } else {
    app.className = template.className;
    app.innerHTML = template.html;
    applyRoutePlaceholders(state.route);
  }
  window.scrollTo(0, 0);
  hydrateCurrentRoute();
}

function applyRoutePlaceholders(route) {
  if (route === "home") {
    const greeting = app.querySelector(".hello h1");
    if (greeting) greeting.textContent = "晚上好";
    const statusLine = app.querySelector(".status-line");
    if (statusLine) statusLine.lastChild.textContent = " --";
    const pill = app.querySelector(".home-state-card .pill");
    if (pill) pill.textContent = "检查中";
    const time = app.querySelector(".home-state-card .time-big");
    if (time) time.innerHTML = "--<small>分钟</small>";
    const progressNumber = app.querySelector("[data-progress-number]");
    if (progressNumber) progressNumber.textContent = "--";
    const progress = app.querySelector("[data-progress-bar]");
    if (progress) progress.style.width = "0%";
    app.querySelectorAll(".home-metrics strong").forEach((node) => { node.textContent = "--"; });
    const appRow = app.querySelector(".home-app-card .app-row");
    if (appRow) appRow.innerHTML = appIconSkeleton(5);
    const permissionTitle = app.querySelector(".home-permission-card h3");
    if (permissionTitle) permissionTitle.textContent = "权限状态";
    const permissionDesc = app.querySelector(".home-permission-card p");
    if (permissionDesc) permissionDesc.textContent = "--";
  }
  if (route === "whitelist" || route === "whitelistEdit") {
    const grid = app.querySelector(".app-grid");
    if (grid) grid.innerHTML = appIconSkeleton(4);
    const addCard = app.querySelector(".add-card");
    if (addCard) addCard.innerHTML = '<h2 class="section-title">可添加应用</h2>' + listRowSkeleton(3);
  }
  if (route === "permissions" || route === "onboarding") {
    const title = app.querySelector(".permission-summary h2, .reference-summary h2");
    if (title) title.textContent = "权限状态";
    const summaryText = app.querySelector(".permission-summary .muted, .reference-summary .muted");
    if (summaryText) summaryText.textContent = "--";
    const progress = app.querySelector("[data-permission-progress], .reference-summary .progress span");
    if (progress) progress.style.width = "0%";
    app.querySelectorAll(".permission-row .row-value").forEach((node) => {
      node.textContent = "检查中";
      node.className = "row-value";
    });
  }
  if (route === "profile") {
    const name = app.querySelector(".profile-user-main h2");
    if (name) name.textContent = "--";
    const badge = app.querySelector(".profile-user-main .pill");
    if (badge) badge.textContent = "学生端";
    app.querySelectorAll(".profile-row p").forEach((node) => { node.textContent = "--"; });
    app.querySelectorAll(".profile-row .row-value").forEach((node) => { node.textContent = "--"; });
  }
  if (route === "guardDetail") {
    const time = app.querySelector(".time-big");
    if (time) time.innerHTML = "--<small>分钟</small>";
  }
  if (route === "temporaryAccess") {
    const appRow = app.querySelector(".request-app-row");
    if (appRow) appRow.innerHTML = listRowSkeleton(1);
  }
}

async function hydrateCurrentRoute() {
  try {
    if (state.route === "bind") await hydrateBind();
    else if (state.route === "onboarding") await hydratePermissions();
    else if (state.route === "home") await hydrateHome();
    else if (state.route === "guardMode") hydrateGuardMode();
    else if (state.route === "guardDetail") await hydrateGuardDetailV2();
    else if (state.route === "whitelist" || state.route === "whitelistEdit") await hydrateWhitelist();
    else if (state.route === "permissions") await hydratePermissions();
    else if (state.route === "temporaryAccess") await hydrateTemporaryAccess();
    else if (state.route === "approvalResult") await hydrateApprovalResult();
    else if (state.route === "profile") await hydrateProfile();
    cacheCurrentRoute();
  } catch (error) {
    toast(error.message || "页面刷新失败");
  }
}

async function hydrateBind() {
  try {
    const data = await apiGet("/api/student/binding/code");
    const code = app.querySelector(".binding-code");
    if (code) code.textContent = data.bindingCode || "请刷新";
    const statusCard = app.querySelector(".binding-status-card h3");
    const statusDesc = app.querySelector(".binding-status-card p");
    const bound = isBindingBound(data);
    cacheBindingSnapshot(data);
    if (statusCard) statusCard.textContent = bound ? "已绑定监管人" : "等待监管人绑定";
    if (statusDesc) statusDesc.textContent = data.guardianName ? "监管人：" + data.guardianName : "绑定完成后，将自动进入权限检查";
    updateBindRefreshVisibility(bound);
    if (bound) await routeAfterBinding();
    else startBindingPoll();
  } catch {
    toast("绑定码获取失败，请稍后重试");
  }
}

async function refreshBindingCode(button) {
  if (currentBindCardLooksBound()) {
    toast("已绑定，无需刷新");
    return;
  }
  const label = button.querySelector(".refresh-label");
  button.disabled = true;
  if (label) label.textContent = "刷新中...";
  try {
    const data = await apiPost("/api/student/binding/refresh", {});
    const code = app.querySelector(".binding-code");
    if (code) code.textContent = data.bindingCode || "请刷新";
    toast("绑定码已刷新");
  } catch {
    toast("刷新失败，请稍后重试");
  } finally {
    button.disabled = false;
    if (label) label.textContent = "刷新绑定码";
  }
}

async function checkBindingStatus() {
  try {
    const data = await apiGet("/api/student/binding/status");
    cacheBindingSnapshot(data);
    if (isBindingBound(data) || currentBindCardLooksBound()) {
      stopBindingPoll();
      await routeAfterBinding();
    }
    else toast("暂未检测到绑定");
  } catch {
    if (currentBindCardLooksBound()) {
      stopBindingPoll();
      await routeAfterBinding();
    } else {
      toast("绑定状态刷新失败");
    }
  }
}

async function hydrateHome() {
  perfLog("home_skeleton_visible");
  loadInstalledAppsCache();
  const cached = readCache("homeOverview");
  if (cached) {
    state.lastHomeOverview = cached;
    renderHomeData(cached, null, { animate: false });
  }

  let overview = cached || null;
  let localGuard = null;
  try {
    const [overviewResult, guardResult] = await Promise.all([
      apiGet("/api/student/home/overview").catch((error) => {
        if (cached) toast("首页同步失败，已保留上次数据");
        else showRouteError(error.message || "首页数据同步失败");
        return null;
      }),
      bridgeCall("getLocalGuardStatus").catch(() => null)
    ]);
    if (overviewResult) {
      overview = overviewResult;
      state.lastHomeOverview = overviewResult;
      writeCache("homeOverview", overviewResult);
    }
    if (guardResult && guardResult.success) localGuard = guardResult.data;
  } catch {}

  renderHomeData(overview, localGuard, { animate: true });
  perfLog("home_data_done", { cached: !overview || overview === cached });
}

function renderHomeData(overview, localGuard, options = {}) {
  const guardStatus = (localGuard && localGuard.guardStatus) || (overview && overview.guardStatus) || "inactive";
  const remainingSeconds = (localGuard && localGuard.remainingSeconds) || (overview && overview.remainingSeconds) || 0;
  const progress = progressFromGuard(localGuard || overview || {});

  const greeting = app.querySelector(".hello h1");
  if (greeting) greeting.textContent = "晚上好，" + ((overview && overview.greetingName) || "辛斯繁");
  const statusLine = app.querySelector(".status-line");
  if (statusLine) statusLine.lastChild.textContent = guardStatus === "active" ? "守门中 · 运行正常" : "未开启守门";
  const pill = app.querySelector(".state-card .pill");
  if (pill) {
    pill.textContent = statusText(guardStatus, "guard");
    pill.className = "pill " + (guardStatus === "active" ? "success" : "blue");
  }
  const timeBig = app.querySelector(".time-big");
  if (timeBig) timeBig.innerHTML = durationText(remainingSeconds).replace("小时", "<small>小时</small>").replace("分钟", "<small>分钟</small>");
  const percent = app.querySelector(".state-percent");
  if (percent) {
    percent.dataset.progressNumber = String(progress);
    percent.textContent = progress + "%";
  }
  const bar = app.querySelector("[data-progress-bar]");
  if (bar) bar.style.width = progress + "%";
  const done = app.querySelector(".done-text");
  if (done) done.textContent = "已完成 " + progress + "%";
  const metrics = app.querySelectorAll(".home-metrics strong");
  if (metrics[0]) metrics[0].textContent = overview && overview.todayRemainingText ? overview.todayRemainingText : durationText(remainingSeconds);
  if (metrics[1]) metrics[1].textContent = ((overview && overview.whitelistCount) || (localGuard && localGuard.whitelistCount) || 0) + " 个";
  if (metrics[2]) metrics[2].textContent = (overview && overview.lastSyncText) || "刚刚";
  const row = app.querySelector(".app-row");
  const apps = overview && Array.isArray(overview.availableApps) ? overview.availableApps.slice(0, 5).map(enrichApp) : [];
  renderHomeApps(apps);
  if (options.animate !== false) animateProgress();
}

function renderHomeApps(apps) {
  const row = app.querySelector(".app-row");
  const list = (apps || []).slice(0, 5).map(enrichApp);
  if (row) {
    row.innerHTML = list.length
      ? list.map((item) => `<div>${appIcon(item)}<span>${escapeHtml(item.name)}</span></div>`).join("")
      : '<div class="app-empty">暂无可用应用</div>';
  }
  scheduleIconRequest(list, "home", 8);
}

function animateProgress() {
  const progressNumber = app.querySelector("[data-progress-number]");
  if (!progressNumber) return;
  const target = Number(progressNumber.dataset.progressNumber || progressNumber.textContent.replace("%", "") || 0);
  const start = performance.now();
  const duration = 700;
  const tick = (now) => {
    const p = Math.min(1, (now - start) / duration);
    const eased = 1 - Math.pow(1 - p, 3);
    progressNumber.textContent = Math.round(target * eased) + "%";
    if (p < 1) requestAnimationFrame(tick);
  };
  progressNumber.textContent = "0%";
  requestAnimationFrame(tick);
}

function hydrateGuardMode() {
  state.selectedGuardMinutes = 60;
  const stack = app.querySelector("[data-choice-group]");
  if (stack && !app.querySelector("[data-custom-duration]")) {
    const custom = document.createElement("div");
    custom.className = "custom-duration injected";
    custom.dataset.customDuration = "";
    custom.hidden = true;
    custom.innerHTML = '<label class="duration-input"><input type="number" min="5" max="120" inputmode="numeric" placeholder="请输入时长" aria-label="自定义守门时长"><span>分钟</span></label><p>建议填写 5–120 分钟</p>';
    stack.insertAdjacentElement("afterend", custom);
  }
}

async function startGuardFromPage(button) {
  const custom = app.querySelector("[data-custom-duration]");
  let minutes = state.selectedGuardMinutes || 60;
  if (custom && !custom.hidden) {
    minutes = Number(custom.querySelector("input")?.value || 0);
    if (!minutes || minutes < 5 || minutes > 120) {
      toast("请输入 5–120 分钟的守门时长");
      return;
    }
  }
  button.disabled = true;
  const original = button.textContent;
  button.textContent = "正在开启...";
  const result = await bridgeCall("startGuardMode", { durationMinutes: minutes });
  button.disabled = false;
  button.textContent = original;
  if (!result.success) {
    toast(result.message || "开启失败，请检查权限后重试");
    return;
  }
  toast("守门模式已开启");
  setRoute("home");
}

async function hydrateGuardDetail() {
  let detail = null;
  let local = null;
  try { detail = await apiGet("/api/student/guard/detail"); } catch {}
  try {
    const result = await bridgeCall("getLocalGuardStatus");
    if (result.success) local = result.data;
  } catch {}
  mountRouteTemplate();
  const status = (local && local.guardStatus) || (detail && detail.status) || "inactive";
  const remaining = local && local.remainingSeconds !== undefined ? durationText(local.remainingSeconds) : ((detail && detail.remainingText) || "0分钟");
  const progress = progressFromGuard(local || detail || {});
  const pill = app.querySelector(".detail-hero .pill");
  if (pill) {
    pill.textContent = status === "active" ? "守门进行中" : "未开启守门";
    pill.className = "pill " + (status === "active" ? "success" : "blue");
  }
  const big = app.querySelector(".time-big");
  if (big) big.innerHTML = remaining.replace("小时", "<small>小时</small>").replace("分钟", "<small>分钟</small>");
  const percent = app.querySelector(".state-percent");
  if (percent) percent.textContent = progress + "%";
  const bar = app.querySelector("[data-progress-bar], .detail-hero .progress span");
  if (bar) bar.style.width = progress + "%";
  const doneText = app.querySelector(".detail-hero .progress + p, .detail-hero p:last-of-type");
  if (doneText && doneText.textContent.includes("完成")) doneText.innerHTML = `本次已完成 <b>${progress}%</b>`;
  const rows = app.querySelectorAll(".detail-list .list-row, .card.list .list-row");
  const values = [
    detail?.startTimeText || "未开始",
    detail?.endTimeText || (status === "active" ? "进行中" : "未开启"),
    detail?.modeName || local?.modeName || "守门模式",
    String(detail?.availableAppCount ?? local?.whitelistCount ?? 0) + " 个",
    detail?.guardianConnectionText || "正常",
    detail?.lastSyncText || "刚刚"
  ];
  rows.forEach((row, index) => {
    const value = row.querySelector(".row-value");
    if (value && values[index]) value.textContent = values[index];
  });
}

async function hydrateGuardDetailV2() {
  mountRouteTemplate();
  let local = null;
  try {
    const result = await bridgeCall("getLocalGuardStatus");
    if (result.success) local = result.data;
  } catch {}
  let detail = null;
  try { detail = await apiGet("/api/student/guard/detail"); } catch {}
  const status = (local && local.guardStatus) || (detail && (detail.guardStatus || detail.status)) || "inactive";
  const active = status === "active";
  const remainingSeconds = active ? Number((local && local.remainingSeconds) ?? (detail && detail.remainingSeconds) ?? 0) : 0;
  const remaining = active ? durationText(remainingSeconds) : "0分钟";
  const progress = active ? progressFromGuard(local || detail || {}) : 0;
  const pill = app.querySelector(".detail-hero .pill");
  if (pill) {
    pill.textContent = active ? "守门进行中" : "未开启守门";
    pill.className = "pill " + (active ? "success" : "blue");
  }
  const big = app.querySelector(".time-big");
  if (big) big.innerHTML = remaining.replace("小时", "<small>小时</small>").replace("分钟", "<small>分钟</small>");
  const percent = app.querySelector(".state-percent");
  if (percent) percent.textContent = progress + "%";
  const bar = app.querySelector("[data-progress-bar], .detail-hero .progress span");
  if (bar) bar.style.width = progress + "%";
  const doneText = app.querySelector(".detail-hero .progress + p, .detail-hero p:last-of-type");
  if (doneText) doneText.innerHTML = `本次已完成 <b style="color:var(--primary)">${progress}%</b>`;
  const rows = app.querySelectorAll(".detail-list .list-row, .card.list .list-row");
  const values = [
    active ? formatDetailTime(local?.startTimeMs || detail?.startTimestamp || detail?.startTime) : "--",
    active ? formatDetailTime(local?.endTimeMs || detail?.endTimestamp || detail?.endTime) : "--",
    active ? (local?.modeName || detail?.modeName || "守门模式") : "未开启",
    String(local?.whitelistCount ?? detail?.availableAppCount ?? 0) + " 个",
    detail?.guardianConnectionText || "正常",
    detail?.lastSyncText || "刚刚"
  ];
  rows.forEach((row, index) => {
    const value = row.querySelector(".row-value");
    if (value && values[index]) value.textContent = values[index];
  });
}

function formatDetailTime(value) {
  if (!value) return "--";
  const date = typeof value === "number" ? new Date(value) : new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

async function loadPermissionReport() {
  const local = await bridgeCall("getLocalPermissions");
  if (local.success && local.data) return local.data;
  throw new Error(local.message || "权限状态获取失败，请稍后重试");
}

async function hydratePermissions() {
  try {
    const report = await loadPermissionReport();
    cachePermissionSnapshot(report);
    mountRouteTemplate();
    const total = 6;
    const enabled = report.enabledCount || 0;
    const needOpen = Math.max(0, total - enabled);
    const completion = app.querySelector(".completion-number");
    if (completion) completion.textContent = enabled + "/" + total;
    const title = app.querySelector(".permission-summary h2, .reference-summary h2");
    if (title) {
      if (completion) {
        const label = enabled >= total ? "权限状态良好 " : "权限需要开启 ";
        title.innerHTML = `${label}<span class="completion-number">${enabled}/${total}</span>`;
      } else {
        title.textContent = enabled >= total ? "权限状态良好" : "权限需要开启";
      }
    }
    const summaryText = app.querySelector(".permission-summary .muted, .reference-summary .muted");
    if (summaryText) summaryText.textContent = enabled >= total ? "已开启 6 项" : "已开启 " + enabled + " 项，建议开启 " + needOpen + " 项";
    const progress = app.querySelector("[data-permission-progress], .reference-summary .progress span");
    if (progress) progress.style.width = Math.round((enabled / Math.max(1, total)) * 100) + "%";
    for (const item of report.permissions || []) updatePermissionRow(item);
  } catch (error) {
    mountRouteTemplate();
    const title = app.querySelector(".permission-summary h2, .reference-summary h2");
    if (title) title.textContent = "权限状态获取失败";
    const summaryText = app.querySelector(".permission-summary .muted, .reference-summary .muted");
    if (summaryText) summaryText.textContent = "请稍后重试";
    toast(error.message || "权限状态获取失败，请稍后重试");
  }
}

function updatePermissionRow(item) {
  const aliases = Object.entries(permissionKeyMap).filter(([, value]) => value === item.key).map(([key]) => key);
  const selector = aliases.map((key) => `[data-permission="${key}"]`).join(",");
  const row = selector ? app.querySelector(selector) : null;
  if (!row) return;
  row.dataset.permission = item.key;
  const h3 = row.querySelector("h3");
  const p = row.querySelector("p");
  const value = row.querySelector(".row-value");
  const icon = row.querySelector("img");
  if (h3) h3.textContent = item.name;
  if (p) p.textContent = item.description;
  if (value) {
    value.textContent = statusText(item.status, "permission");
    value.className = "row-value " + (item.status === "enabled" ? "success" : "warn");
  }
  if (icon && permissionIconMap[item.key]) icon.src = permissionIconMap[item.key];
}

async function openPermissionDirect(key) {
  const permissionKey = permissionKeyMap[key] || key;
  const result = await bridgeCall("openPermissionSettings", { permissionKey });
  toast(result.success ? "已打开权限设置" : "无法打开系统设置，请手动前往设置开启权限");
  state.pendingPermissionRefresh = true;
}

function showPermissionSheet(key) {
  const permissionKey = permissionKeyMap[key] || key;
  const sheet = app.querySelector("[data-profile-sheet]");
  const title = app.querySelector("#profile-sheet-title");
  const body = app.querySelector("[data-sheet-body]");
  if (!sheet || !body || !title) return;
  const row = app.querySelector(`[data-permission="${key}"], [data-permission="${permissionKey}"]`);
  const name = row?.querySelector("h3")?.textContent || "权限说明";
  const desc = row?.querySelector("p")?.textContent || "用于保证守门服务稳定运行。";
  title.textContent = name;
  body.innerHTML = `<section class="permission-sheet-section"><h3>用途</h3><p>${escapeHtml(desc)}</p></section><section class="permission-sheet-section"><h3>开启步骤</h3><p>点击下方按钮后，按系统页面提示开启对应权限。不同手机入口名称可能略有不同。</p></section><button class="primary-btn" data-open-permission="${escapeHtml(permissionKey)}">${row?.querySelector(".row-value")?.textContent === "已开启" ? "我知道了" : "去开启"}</button>`;
  sheet.hidden = false;
  requestAnimationFrame(() => sheet.classList.add("is-open"));
}

async function hydrateWhitelist() {
  perfLog("whitelist_skeleton_visible");
  loadInstalledAppsCache();
  const cached = readCache("whitelist");
  if (cached) {
    state.lastWhitelistData = cached;
    renderWhitelistFromData(cached, false);
  }
  try {
    const data = await apiGet("/api/student/whitelist");
    state.lastWhitelistData = data;
    writeCache("whitelist", data);
    renderWhitelistFromData(data, false);
    perfLog("whitelist_data_done", { cached: false });
    window.setTimeout(() => {
      refreshInstalledAppsInBackground(() => {
        if (state.lastWhitelistData && (state.route === "whitelist" || state.route === "whitelistEdit")) {
          renderWhitelistFromData(state.lastWhitelistData, true);
        }
      });
    }, 600);
  } catch (error) {
    if (cached) {
      toast("白名单同步失败，已保留上次数据");
      perfLog("whitelist_data_done", { cached: true, success: false });
    } else {
      showRouteError(error.message || "白名单加载失败");
      toast("白名单加载失败");
    }
  }
}

function renderWhitelistFromData(data, includeInstalled) {
  const allowed = (data.allowedApps || []).map(enrichApp);
  const remoteAddable = (data.addableApps || []).map(enrichApp);
  const addable = includeInstalled ? mergeInstalledAddable(allowed, remoteAddable).map(enrichApp) : remoteAddable.map(enrichApp);
  renderWhitelistLists(allowed, addable);
  scheduleIconRequest([...allowed, ...addable.slice(0, 20)], includeInstalled ? "whitelist_meta" : "whitelist_remote", includeInstalled ? 30 : 20);
}

function renderWhitelistLists(allowed, addable) {
  const grid = app.querySelector(".app-grid");
  if (grid) {
    grid.innerHTML = allowed.length
      ? allowed.map((item) => `<div data-allowed-app data-package="${escapeHtml(appPackage(item))}" data-app-row="${escapeHtml(item.name)}">${appIcon(item)}<span>${escapeHtml(item.name)}</span><span class="checkmark">✓</span><button class="remove-app" type="button" data-remove-app aria-label="移除${escapeHtml(item.name)}"></button></div>`).join("")
      : '<div class="app-empty">暂无已允许应用</div>';
  }
  const addCard = app.querySelector(".add-card");
  if (addCard) {
    addCard.innerHTML = '<h2 class="section-title">可添加应用</h2><label class="app-search"><input type="search" data-search placeholder="搜索应用名称"></label>' + (addable.length
      ? addable.map((item) => `<div class="list-row" data-app-row="${escapeHtml(item.name)}" data-package="${escapeHtml(appPackage(item))}">${appIcon(item)}<div class="main"><h3>${escapeHtml(item.name)}</h3><p>${escapeHtml(item.category || "本机应用")}</p></div><button class="add-btn" data-add-app aria-label="添加${escapeHtml(item.name)}">+</button></div>`).join("")
      : '<div class="app-empty">暂无可添加应用</div>');
  }
}

async function saveWhitelist(button) {
  const packages = Array.from(app.querySelectorAll("[data-allowed-app][data-package]")).map((node) => node.dataset.package).filter(Boolean);
  button.disabled = true;
  const result = await bridgeCall("updateWhitelist", packages);
  button.disabled = false;
  if (!result.success) {
    toast(result.message || "保存失败，请稍后重试");
    return;
  }
  toast("白名单已更新");
  app.classList.remove("is-managing");
  button.textContent = "管理";
}

async function hydrateTemporaryAccess() {
  try {
    const data = await apiGet("/api/student/release/apps");
    loadInstalledAppsCache();
    mountRouteTemplate();
    const apps = (data.apps || []).map(enrichApp);
    state.selectedReleaseApp = apps[0] || null;
    const card = app.querySelector("section.card.pad");
    if (card && state.selectedReleaseApp) {
      card.innerHTML = `<h2 class="section-title">选择应用</h2><div class="request-app-row">${appIcon(state.selectedReleaseApp)}<div class="main"><h2>${escapeHtml(state.selectedReleaseApp.name)}</h2><p class="muted">${escapeHtml(state.selectedReleaseApp.category || "当前不可用")} · 当前不可用</p></div><i class="chev"></i></div>`;
    }
    scheduleIconRequest(apps.slice(0, 10), "release", 10);
  } catch (error) {
    mountRouteTemplate();
    toast("可申请应用加载失败");
  }
}

async function submitReleaseRequest(button) {
  if (!state.selectedReleaseApp) {
    toast("请选择申请应用");
    return;
  }
  const custom = app.querySelector("[data-custom-duration]");
  let minutes = state.selectedReleaseMinutes || 30;
  if (custom && !custom.hidden) {
    minutes = Number(custom.querySelector("input")?.value || 0);
    if (!minutes || minutes < 5 || minutes > 120) {
      toast("请输入 5–120 分钟的申请时长");
      return;
    }
  }
  const reason = app.querySelector("[data-reason]")?.value.trim() || "";
  if (!reason) {
    toast("请填写申请原因");
    return;
  }
  button.disabled = true;
  button.textContent = "发送中...";
  try {
    const res = await apiPost("/api/student/release/request", {
      appId: state.selectedReleaseApp.appId || state.selectedReleaseApp.packageName,
      durationMinutes: minutes,
      reason
    });
    state.lastReleaseRequestId = res.requestId || "";
    toast("申请已发送");
    setRoute("approvalResult", { requestId: state.lastReleaseRequestId });
  } catch {
    toast("发送失败，请稍后重试");
  } finally {
    button.disabled = false;
    button.textContent = "发送审批申请";
  }
}

async function hydrateApprovalResult() {
  const requestId = state.params.requestId || state.lastReleaseRequestId;
  mountRouteTemplate();
  if (!requestId) {
    const title = app.querySelector("section.card h2");
    if (title) title.textContent = "暂无审批记录";
    const desc = app.querySelector("section.card p.muted");
    if (desc) desc.textContent = "暂时没有正在等待处理的申请";
    const detail = app.querySelector("section.card.list");
    if (detail) detail.innerHTML = '<h2 class="section-title" style="padding:18px 0 4px">申请详情</h2><div class="app-empty">暂无申请详情</div>';
    return;
  }
  try {
    const data = await apiGet("/api/student/release/request/" + encodeURIComponent(requestId));
    renderApprovalData(data);
  } catch {
    toast("审批状态刷新失败");
  }
}

function renderApprovalData(data) {
  const status = data.status || "pending";
  const pill = app.querySelector(".pill");
  if (pill) {
    pill.textContent = statusText(status, "release");
    pill.className = "pill " + statusTone(status);
  }
  const title = app.querySelector("section.card h2");
  if (title) title.textContent = data.statusText || statusText(status, "release");
  const desc = app.querySelector("section.card p.muted");
  if (desc) desc.textContent = releaseDescriptionText(data, status);
  const rows = app.querySelectorAll(".list-row .row-value, .list-row b");
  if (rows[0]) rows[0].textContent = data.appName || "应用";
  if (rows[1]) rows[1].textContent = (data.durationMinutes || 0) + "分钟";
  if (rows[2]) rows[2].textContent = data.reason || "已提交申请";
  if (rows[3]) rows[3].textContent = data.submittedAtText || "刚刚";
  if (rows[4]) rows[4].textContent = data.guardianName || "监管人";
}

function releaseDescriptionText(data, status) {
  if (status === "approved") {
    return "监管人已同意申请。本阶段不会自动放行，实际使用权限仍以当前守门状态为准。";
  }
  if (status === "rejected") {
    return data.rejectReason ? "监管人未同意：" + data.rejectReason : "监管人暂未同意本次申请。";
  }
  if (status === "cancelled") return "你已撤回本次申请。";
  if (status === "expired") return "申请已过期。";
  return data.description || "申请已发送给监管人。";
}

async function cancelReleaseRequest() {
  const requestId = state.params.requestId || state.lastReleaseRequestId;
  if (!requestId) {
    setRoute("temporaryAccess");
    return;
  }
  try {
    await apiPost("/api/student/release/request/" + encodeURIComponent(requestId) + "/cancel", {});
    toast("申请已撤回");
    hydrateApprovalResult();
  } catch {
    toast("撤回失败，请稍后重试");
  }
}

async function hydrateProfile() {
  perfLog("profile_skeleton_visible");
  const cached = readCache("profileSnapshot");
  if (cached) {
    state.profileSnapshot = cached;
    renderProfileSnapshot(cached);
  }
  const deviceResult = await bridgeCall("getDeviceInfo").catch(() => null);
  if (deviceResult && deviceResult.success) {
    const base = state.profileSnapshot || {};
    state.profileSnapshot = {
      profile: base.profile || {},
      device: deviceResult.data || {},
      updatedAt: new Date().toISOString()
    };
    renderProfileSnapshot(state.profileSnapshot);
  }
  try {
    const profile = await apiGet("/api/student/profile");
    state.profileSnapshot = {
      profile: profile || {},
      device: (state.profileSnapshot && state.profileSnapshot.device) || {},
      updatedAt: new Date().toISOString()
    };
    writeCache("profileSnapshot", state.profileSnapshot);
    renderProfileSnapshot(state.profileSnapshot);
    perfLog("profile_data_done", { cached: false });
  } catch (error) {
    if (state.profileSnapshot) {
      toast("我的页面同步失败，已保留上次数据");
      perfLog("profile_data_done", { cached: true, success: false });
    } else {
      showRouteError(error.message || "我的页面信息刷新失败");
      toast("我的页面信息刷新失败");
    }
  }
}

function renderProfileSnapshot(snapshot) {
  const profile = snapshot?.profile || {};
  const device = snapshot?.device || {};
  const name = profile.studentName || "辛斯繁";
  const h2 = app.querySelector(".profile-user-main h2");
  if (h2) h2.textContent = name;
  const avatar = app.querySelector(".avatar");
  if (avatar) avatar.textContent = name.slice(0, 1);
  const rows = app.querySelectorAll(".profile-row");
  if (rows[0]) rows[0].querySelector("p").textContent = device.deviceModel || profile.device?.deviceModel || "本机设备";
  if (rows[1]) {
    rows[1].querySelector("p").textContent = profile.guardian ? profile.guardian.name : "等待绑定";
    rows[1].querySelector(".row-value").textContent = profile.guardian ? "已绑定" : "未绑定";
  }
  if (rows[2]) rows[2].querySelector("p").textContent = profile.connection ? profile.connection.text : "正常";
  if (rows[3]) {
    rows[3].querySelector("p").textContent = profile.lastSyncText || "刚刚";
    rows[3].querySelector(".row-value").textContent = profile.lastSyncText || "刚刚";
  }
}

function plainStatus(value, type = "generic") {
  const status = String(value || "").toLowerCase();
  if (type === "binding") {
    if (status === "bound") return "已绑定";
    if (status === "pending") return "等待绑定";
    return "未绑定";
  }
  if (type === "connection") {
    if (status === "normal" || status === "online") return "正常";
    if (status === "offline") return "离线";
    if (status === "error") return "异常";
    return "正常";
  }
  if (status === "active") return "守门中";
  if (status === "inactive" || status === "ended" || status === "paused") return "未开启";
  if (status === "success") return "成功";
  if (status === "failed" || status === "error") return "异常";
  return value || "正常";
}

function profileContext() {
  const snapshot = state.profileSnapshot || {};
  const profile = snapshot.profile || {};
  const device = snapshot.device || {};
  const guardian = profile.guardian || {};
  const connection = profile.connection || {};
  const profileDevice = profile.device || {};
  const studentName = profile.studentName || "辛斯繁";
  const deviceName = profileDevice.deviceName || device.deviceName || device.deviceModel || "本机设备";
  const deviceModel = device.deviceModel || profileDevice.deviceModel || "本机设备";
  const bindingText = guardian.name ? "已绑定" : plainStatus(guardian.bindingStatus, "binding");
  const connectionText = connection.text || plainStatus(connection.status, "connection");
  const lastSyncText = profile.lastSyncText || "刚刚";
  const rawCode = String(profileDevice.deviceId || device.deviceId || "7PCQHMKNUWU469C6").replace(/[^A-Za-z0-9]/g, "").toUpperCase();
  const displayCode = rawCode.length >= 8 ? `SG-${rawCode.slice(0, 4)}-${rawCode.slice(-5)}` : "SG-LOCAL-DEVICE";
  return {
    studentName,
    avatarText: (profile.avatarText || studentName.slice(0, 1) || "学").slice(0, 1),
    deviceName,
    deviceModel,
    appVersion: device.appVersionName ? `v${device.appVersionName}` : "当前版本",
    bindingText,
    guardianName: guardian.name || "等待绑定",
    guardianRelation: guardian.relation || "监管人",
    connectionText,
    lastSyncText,
    displayCode
  };
}

function renderSheetRows(rows) {
  return rows.map(([label, value, tone = ""]) => (
    `<div class="sheet-info-row ${escapeHtml(tone)}"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`
  )).join("");
}

function renderSheetNote(text, tone = "") {
  return `<p class="sheet-note ${escapeHtml(tone)}">${escapeHtml(text)}</p>`;
}

function renderSheetLinkRow(label, value, sheetName) {
  return `<button class="sheet-link-row" type="button" data-open-sheet="${escapeHtml(sheetName)}">
    <span>${escapeHtml(label)}</span>
    <strong>${escapeHtml(value)}</strong>
    <i class="chev" aria-hidden="true"></i>
  </button>`;
}

function renderSheetSwitch(title, desc, checked = true) {
  return `<button class="sheet-switch-row" type="button" data-sheet-feedback="通知设置">
    <span><strong>${escapeHtml(title)}</strong><em>${escapeHtml(desc)}</em></span>
    <span class="switch ${checked ? "on" : ""}" aria-hidden="true"></span>
  </button>`;
}

function renderPolicySection(title, body) {
  return `<section class="policy-section">
    <h3>${escapeHtml(title)}</h3>
    <p>${escapeHtml(body)}</p>
  </section>`;
}

function profileSheets() {
  const info = profileContext();
  return {
    profile: {
      title: "个人信息",
      html: `
        <div class="sheet-profile-hero">
          <div class="sheet-avatar-preview">${escapeHtml(info.avatarText)}</div>
          <div>
            <strong>${escapeHtml(info.studentName)}</strong>
            <p>个人信息由绑定关系同步，暂不支持在学生端修改</p>
          </div>
        </div>
        ${renderSheetRows([
          ["当前身份", "学生端", "readonly"],
          ["绑定状态", info.bindingText, info.bindingText === "已绑定" ? "readonly success" : "readonly"],
          ["监管人", info.guardianName, "readonly"]
        ])}
        ${renderSheetNote("姓名、身份和监管关系来自当前绑定信息。如需调整，请由监管人在家长端处理。")}
      `
    },
    device: {
      title: "我的设备",
      html: `
        <div class="sheet-status-hero success">
          <span class="sheet-status-icon">✓</span>
          <div><strong>设备运行正常</strong><p>当前设备已连接学生端守门服务</p></div>
        </div>
        ${renderSheetRows([
          ["设备名称", info.deviceName],
          ["设备型号", info.deviceModel],
          ["设备状态", "正常运行", "success"],
          ["最近同步", info.lastSyncText],
          ["App 版本", info.appVersion]
        ])}
      `
    },
    guardian: {
      title: "绑定监管人",
      html: `
        ${renderSheetRows([
          ["监管人", info.guardianName],
          ["关系", info.guardianRelation],
          ["绑定状态", info.bindingText, info.bindingText === "已绑定" ? "success" : ""],
          ["绑定方式", "绑定码确认"]
        ])}
        ${renderSheetNote("监管人可接收临时放行申请，并协助同步守门状态。当前阶段不支持学生端自行更换监管人。", "green")}
      `
    },
    connection: {
      title: "监管连接状态",
      html: `
        <div class="sheet-status-hero success">
          <span class="sheet-status-icon">✓</span>
          <div><strong>当前状态：${escapeHtml(info.connectionText)}</strong><p>守门状态、权限状态和白名单会同步给监管端</p></div>
        </div>
        ${renderSheetRows([
          ["连接状态", info.connectionText, info.connectionText === "正常" ? "success" : ""],
          ["最近同步", info.lastSyncText],
          ["状态说明", "审批结果和守门配置可正常同步"]
        ])}
        <button class="sheet-action-primary" type="button" data-sync-now>立即同步</button>
      `
    },
    sync: {
      title: "最近同步",
      html: `
        ${renderSheetRows([
          ["最近同步时间", info.lastSyncText],
          ["同步状态", "等待下一次确认", "readonly"],
          ["同步内容", "守门状态 / 权限状态 / 白名单状态"]
        ])}
        ${renderSheetNote("云端只是当前状态快照，真正守门能力仍以本机状态为准。")}
        <button class="sheet-action-primary" type="button" data-sync-now>立即同步</button>
      `
    },
    deviceCode: {
      title: "设备编号",
      html: `
        <div class="sheet-code">${escapeHtml(info.displayCode)}</div>
        ${renderSheetNote("设备编号用于家长端识别当前学生设备，请不要随意分享给无关人员。")}
        <button class="sheet-copy" type="button" data-copy-device="${escapeHtml(info.displayCode)}">复制设备编号</button>
      `
    },
    notifications: {
      title: "通知设置",
      html: [
        renderSheetSwitch("审批结果通知", "监管人同意或拒绝申请时提醒我", true),
        renderSheetSwitch("守门状态提醒", "守门开始、结束或异常时提醒我", true),
        renderSheetSwitch("权限异常提醒", "关键权限异常时提醒我", false),
        renderSheetNote("当前阶段通知设置跟随系统通知权限，独立开关后续开放。")
      ].join("")
    },
    privacy: {
      title: "隐私与安全",
      html: `
        ${renderSheetRows([
          ["数据同步", "仅同步守门所需状态"],
          ["权限用途", "识别应用、显示守门提醒"],
          ["隐私范围", "不读取聊天、短信和相册内容"]
        ])}
        ${renderSheetLinkRow("隐私政策", "查看", "privacyPolicy")}
      `
    },
    about: {
      title: "关于守门应用锁",
      html: `
        ${renderSheetRows([
          ["应用名称", "守门应用锁"],
          ["当前版本", info.appVersion],
          ["产品说明", "学生端用于查看本机守门状态、白名单、权限与临时放行申请"],
          ["当前形态", "学生端 App"]
        ])}
      `
    },
    privacyPolicy: {
      title: "隐私政策",
      html: `
        <div class="policy-scroll">
          ${renderPolicySection("我们使用哪些信息", "为实现守门模式、白名单、权限检查和临时放行申请，学生端会使用本机应用列表、权限状态、守门状态、白名单配置和审批状态。")}
          ${renderPolicySection("这些信息用来做什么", "这些信息只用于守门保护、状态展示、规则同步和临时放行申请，不用于广告推荐或商业画像。")}
          ${renderPolicySection("监管人能看到什么", "监管人可以看到设备守门状态、权限摘要、白名单和临时放行申请结果，不会看到聊天内容、短信内容、相册内容或具体浏览内容。")}
          ${renderPolicySection("权限用途", "无障碍、使用情况访问、悬浮窗、通知、电池后台和自启动权限只用于保持守门能力稳定运行。")}
        </div>
        <button class="sheet-action-primary policy-confirm" type="button" data-close-sheet-action>我知道了</button>
      `
    },
    unbind: {
      title: "确认退出绑定",
      html: `
        <div class="sheet-danger-note">
          <strong>退出绑定会停止当前监管关系。</strong>
          <p>当前阶段暂不支持学生端自行解绑。如果确实需要解绑，请先联系监管人处理。</p>
        </div>
        <div class="sheet-actions danger-actions">
          <button class="sheet-action-secondary" type="button" data-close-sheet-action>取消</button>
          <button class="sheet-action-danger" type="button" data-sheet-feedback="退出绑定">确认退出</button>
        </div>
      `
    }
  };
}

function showProfileSheet(name) {
  const sheet = app.querySelector("[data-profile-sheet]");
  const title = app.querySelector("#profile-sheet-title");
  const body = app.querySelector("[data-sheet-body]");
  if (!sheet || !title || !body) return;
  const sheets = profileSheets();
  const item = sheets[name] || sheets.profile;
  title.textContent = item.title;
  body.innerHTML = item.html;
  sheet.hidden = false;
  requestAnimationFrame(() => sheet.classList.add("is-open"));
}

function closeSheet() {
  const sheet = app.querySelector("[data-profile-sheet]");
  if (sheet) {
    sheet.classList.remove("is-open");
    window.setTimeout(() => {
      if (!sheet.classList.contains("is-open")) sheet.hidden = true;
    }, 180);
  }
}

function routeFromElement(target) {
  const explicit = target.closest("[data-route]");
  if (explicit) return explicit.dataset.route;
  const link = target.closest("a[href]");
  if (!link) return "";
  const file = link.getAttribute("href").split("/").pop();
  return routeByFile[file] || "";
}

document.addEventListener("click", async (event) => {
  if (event.target.closest("[data-close-sheet], [data-close-sheet-action]")) {
    event.preventDefault();
    closeSheet();
    return;
  }
  if (event.target.matches("[data-profile-sheet]")) {
    closeSheet();
    return;
  }
  if (event.target.closest(".back")) {
    event.preventDefault();
    goBack();
    return;
  }
  const nestedSheet = event.target.closest("[data-open-sheet]");
  if (nestedSheet) {
    event.preventDefault();
    showProfileSheet(nestedSheet.dataset.openSheet);
    return;
  }
  const syncNow = event.target.closest("[data-sync-now]");
  if (syncNow) {
    event.preventDefault();
    syncNow.disabled = true;
    const oldText = syncNow.textContent;
    syncNow.textContent = "同步中...";
    const result = await bridgeCall("syncNow");
    toast(result.success ? "已发起同步" : (result.message || "同步失败，请稍后重试"));
    syncNow.textContent = oldText;
    syncNow.disabled = false;
    return;
  }
  const copyDevice = event.target.closest("[data-copy-device]");
  if (copyDevice) {
    event.preventDefault();
    const code = copyDevice.dataset.copyDevice || profileContext().displayCode;
    try {
      await navigator.clipboard?.writeText(code);
      copyDevice.textContent = "已复制";
      toast("设备编号已复制");
    } catch {
      toast("复制失败，请手动记录设备编号");
    }
    return;
  }
  const sheetSwitch = event.target.closest(".sheet-switch-row");
  if (sheetSwitch) {
    event.preventDefault();
    toast("当前阶段通知设置跟随系统通知权限，后续会开放独立开关");
    return;
  }
  const sheetFeedback = event.target.closest("[data-sheet-feedback]");
  if (sheetFeedback) {
    event.preventDefault();
    toast(sheetFeedback.dataset.sheetFeedback === "确认退出"
      ? "当前阶段暂不支持学生端自行解绑"
      : "当前阶段暂不支持在学生端修改");
    return;
  }
  const refresh = event.target.closest("[data-refresh-code]");
  if (refresh) {
    event.preventDefault();
    refreshBindingCode(refresh);
    return;
  }
  if (event.target.closest(".bind-status-link a")) {
    event.preventDefault();
    checkBindingStatus();
    return;
  }
  const permissionRefresh = event.target.closest("[data-permission-refresh]");
  if (permissionRefresh) {
    event.preventDefault();
    permissionRefresh.classList.add("is-loading");
    await hydratePermissions();
    permissionRefresh.classList.remove("is-loading");
    toast("权限状态已刷新");
    return;
  }
  const permissionRow = event.target.closest("[data-permission]");
  if (permissionRow) {
    event.preventDefault();
    await openPermissionDirect(permissionRow.dataset.permission);
    return;
  }
  const openPerm = event.target.closest("[data-open-permission]");
  if (openPerm) {
    event.preventDefault();
    if (openPerm.textContent.includes("我知道")) closeSheet();
    else {
      const result = await bridgeCall("openPermissionSettings", { permissionKey: openPerm.dataset.openPermission });
      toast(result.message);
      closeSheet();
    }
    return;
  }
  const guardStart = event.target.closest("button");
  if (guardStart && guardStart.textContent.includes("开启守门模式")) {
    event.preventDefault();
    startGuardFromPage(guardStart);
    return;
  }
  const sendRequest = event.target.closest("[data-send-request]");
  if (sendRequest) {
    event.preventDefault();
    submitReleaseRequest(sendRequest);
    return;
  }
  const cancelRequest = event.target.closest("button.text-btn");
  if (cancelRequest && cancelRequest.textContent.includes("撤回")) {
    event.preventDefault();
    cancelReleaseRequest();
    return;
  }
  const profileRow = event.target.closest("[data-sheet]");
  if (profileRow) {
    event.preventDefault();
    showProfileSheet(profileRow.dataset.sheet);
    return;
  }
  const homeStateCard = event.target.closest(".home-state-card");
  if (homeStateCard && state.route === "home") {
    event.preventDefault();
    setRoute("guardDetail");
    return;
  }
  const homePermissionCard = event.target.closest(".home-permission-card");
  if (homePermissionCard && state.route === "home") {
    event.preventDefault();
    setRoute("permissions");
    return;
  }
  const manage = event.target.closest("[data-whitelist-manage]");
  if (manage) {
    event.preventDefault();
    const active = !app.classList.contains("is-managing");
    app.classList.toggle("is-managing", active);
    manage.textContent = active ? "完成" : "管理";
    if (!active) await saveWhitelist(manage);
    return;
  }
  const addApp = event.target.closest("[data-add-app]");
  if (addApp) {
    event.preventDefault();
    const row = addApp.closest("[data-package]");
    const pkg = row?.dataset.package;
    const name = row?.querySelector("h3")?.textContent || "应用";
    const iconHtml = appIcon({ packageName: pkg, name });
    const grid = app.querySelector(".app-grid");
    if (grid && pkg) {
      const empty = grid.querySelector(".app-empty");
      if (empty) empty.remove();
      grid.insertAdjacentHTML("beforeend", `<div data-allowed-app data-package="${escapeHtml(pkg)}" data-app-row="${escapeHtml(name)}">${iconHtml}<span>${escapeHtml(name)}</span><span class="checkmark">✓</span><button class="remove-app" type="button" data-remove-app aria-label="移除"></button></div>`);
    }
    scheduleIconRequest([{ packageName: pkg, name }], "whitelist_add", 1);
    row?.remove();
    toast("已加入白名单");
    return;
  }
  const removeApp = event.target.closest("[data-remove-app]");
  if (removeApp) {
    event.preventDefault();
    if (app.classList.contains("is-managing")) removeApp.closest("[data-allowed-app]")?.remove();
    return;
  }
  const choice = event.target.closest("[data-choice]");
  if (choice) {
    event.preventDefault();
    const group = choice.closest("[data-choice-group]");
    group?.querySelectorAll("[data-choice]").forEach((node) => {
      node.classList.toggle("active", node === choice);
      node.classList.toggle("selected", node === choice);
    });
    const text = choice.dataset.choice || choice.textContent.trim();
    const custom = app.querySelector("[data-custom-duration]");
    if (custom) custom.hidden = !text.includes("自定义");
    if (state.route === "guardMode") state.selectedGuardMinutes = text.includes("30") ? 30 : text.includes("2") ? 120 : text.includes("自定义") ? 0 : 60;
    if (state.route === "temporaryAccess") state.selectedReleaseMinutes = text.includes("10") ? 10 : text.includes("1 小时") ? 60 : text.includes("自定义") ? 0 : 30;
    const output = app.querySelector("[data-selection-output]");
    if (output) output.textContent = text;
    return;
  }
  const route = routeFromElement(event.target);
  if (route) {
    event.preventDefault();
    setRoute(route);
  }
});

document.addEventListener("input", (event) => {
  const search = event.target.closest("[data-search]");
  if (search) {
    const term = search.value.trim().toLowerCase();
    search.closest(".add-card")?.querySelectorAll("[data-app-row]").forEach((row) => {
      row.style.display = row.dataset.appRow.toLowerCase().includes(term) ? "" : "none";
    });
  }
  const reason = event.target.closest("[data-reason]");
  if (reason) {
    const counter = app.querySelector("[data-counter]");
    if (counter) counter.textContent = Math.min(reason.value.length, 100) + "/100";
  }
});

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && state.pendingPermissionRefresh && (state.route === "permissions" || state.route === "onboarding")) {
    state.pendingPermissionRefresh = false;
    hydratePermissions();
  }
});

window.addEventListener("focus", () => {
  if (state.pendingPermissionRefresh && (state.route === "permissions" || state.route === "onboarding")) {
    state.pendingPermissionRefresh = false;
    hydratePermissions();
  }
});

window.addEventListener("hashchange", () => {
  const parsed = routeFromHash();
  if (parsed && parsed.route !== state.route) setRoute(parsed.route, parsed.params, true);
});

chooseInitialRoute();
