(function () {
  console.info("[GuardianPWA] build=20260529-rules-core");
  const root = document.querySelector(".phone");
  if (!root) return;
  const AUTH_KEY = "parent_logged_in";
  const PROTECTED_PATHS = new Set([
    "/", "/home", "/index", "/index.html", "/home.html",
    "/requests", "/approval", "/approval.html",
    "/rules", "/rules.html",
    "/report", "/report.html",
    "/me", "/profile", "/profile.html"
  ]);
  const THEME_KEY = "parentAppTheme";
  const THEME_NAMES = {
    blue: "柔和蓝主题",
    green: "守护绿主题",
    warm: "暖米色主题",
    slate: "高级灰主题"
  };

  function isLoggedIn() {
    try {
      return localStorage.getItem(AUTH_KEY) === "true";
    } catch (error) {
      return document.cookie.split("; ").some((item) => item === AUTH_KEY + "=true");
    }
  }

  function canonicalNextPath(pathname) {
    const map = {
      "/index.html": "/",
      "/home.html": "/",
      "/approval.html": "/requests",
      "/rules.html": "/rules",
      "/report.html": "/report",
      "/profile.html": "/me"
    };
    return map[pathname] || pathname || "/";
  }

  function requireMockLogin() {
    const pathname = window.location.pathname || "/";
    if (!PROTECTED_PATHS.has(pathname)) return true;
    if (isLoggedIn()) return true;
    const next = canonicalNextPath(pathname) + (window.location.search || "");
    window.location.replace("/login?next=" + encodeURIComponent(next));
    return false;
  }

  function clearMockLogin() {
    try {
      localStorage.removeItem(AUTH_KEY);
    } catch (error) {
      // Cookie fallback below covers restricted storage environments.
    }
    document.cookie = AUTH_KEY + "=; path=/; max-age=0";
  }

  function performMockLogout() {
    clearMockLogin();
    window.location.href = "/login";
  }

  if (!requireMockLogin()) return;

  function normalizeTheme(themeName) {
    return THEME_NAMES[themeName] ? themeName : "blue";
  }

  function readStoredTheme() {
    try {
      const stored = localStorage.getItem(THEME_KEY);
      if (stored) return stored;
    } catch (error) {
      // Fall back to cookies for preview environments that restrict localStorage.
    }
    if (window.name && window.name.indexOf(THEME_KEY + "=") === 0) {
      return window.name.slice(THEME_KEY.length + 1);
    }
    const cookie = document.cookie
      .split("; ")
      .find((item) => item.startsWith(THEME_KEY + "="));
    return cookie ? decodeURIComponent(cookie.split("=")[1] || "") : "";
  }

  function writeStoredTheme(themeName) {
    window.name = THEME_KEY + "=" + themeName;
    try {
      localStorage.setItem(THEME_KEY, themeName);
    } catch (error) {
      // Some previews restrict localStorage, so cookie remains the durable fallback.
    }
    try {
      document.cookie = THEME_KEY + "=" + encodeURIComponent(themeName) + "; path=/; max-age=31536000";
    } catch (error) {
      // File previews may not allow cookies; URL propagation still carries the theme.
    }
  }

  function readThemeFromUrl() {
    const params = new URLSearchParams(window.location.search);
    return params.get("theme") || "";
  }

  function updateCurrentUrlTheme(themeName) {
    if (!window.history || !window.history.replaceState) return;
    const url = new URL(window.location.href);
    url.searchParams.set("theme", themeName);
    window.history.replaceState({}, "", url.pathname.split("/").pop() + url.search + url.hash);
  }

  function updateThemeLinks(themeName) {
    root.querySelectorAll('a[href$=".html"], a[href*=".html?"]').forEach((link) => {
      const href = link.getAttribute("href") || "";
      if (!href || href.startsWith("http")) return;
      const url = new URL(href, window.location.href);
      url.searchParams.set("theme", themeName);
      link.setAttribute("href", url.pathname.split("/").pop() + url.search + url.hash);
    });
  }

  function toast(message) {
    let node = root.querySelector(".app-toast");
    if (!node) {
      node = document.createElement("div");
      node.className = "app-toast";
      root.appendChild(node);
    }
    node.textContent = message;
    node.classList.add("show");
    clearTimeout(node._timer);
    node._timer = setTimeout(() => node.classList.remove("show"), 1800);
  }

  function setSwitchState(switcher, enabled) {
    switcher.classList.toggle("on", enabled);
    switcher.setAttribute("aria-label", enabled ? "已开启" : "已关闭");
    switcher.setAttribute("aria-checked", String(enabled));
  }

  function setReminder(enabled) {
    root.querySelectorAll(".reminder-toggle").forEach((button) => {
      button.classList.toggle("is-off", !enabled);
      button.dataset.reminderState = enabled ? "on" : "off";
      button.setAttribute("aria-pressed", String(enabled));
      button.setAttribute("aria-label", enabled ? "提醒已开启" : "提醒已关闭");
    });
    root.querySelectorAll('[data-action="toggle-reminder"] .switch, .settings-row[data-action="toggle-reminder"] .switch').forEach((switcher) => {
      setSwitchState(switcher, enabled);
    });
    const label = root.querySelector("[data-reminder-label]");
    if (label) label.textContent = enabled ? "已开启提醒" : "已关闭提醒";
    toast(enabled ? "已开启提醒" : "已关闭提醒");
  }

  function applyTheme(themeName, options) {
    const settings = Object.assign({ persist: true, notify: true }, options);
    const nextTheme = normalizeTheme(themeName);
    document.documentElement.dataset.theme = nextTheme;
    root.dataset.theme = nextTheme;
    root.querySelectorAll(".theme-option").forEach((option) => {
      const active = option.dataset.theme === nextTheme;
      option.classList.toggle("active", active);
      option.setAttribute("aria-pressed", String(active));
    });
    updateCurrentUrlTheme(nextTheme);
    updateThemeLinks(nextTheme);
    if (settings.persist) {
      writeStoredTheme(nextTheme);
    }
    if (settings.notify) {
      toast("已切换为" + THEME_NAMES[nextTheme]);
    }
  }

  function openSettings() {
    const drawer = root.querySelector("[data-settings-drawer]");
    if (!drawer) return;
    drawer.classList.add("show");
    drawer.setAttribute("aria-hidden", "false");
  }

  function closeSettings() {
    const drawer = root.querySelector("[data-settings-drawer]");
    if (!drawer) return;
    drawer.classList.remove("show");
    drawer.setAttribute("aria-hidden", "true");
  }

  function closeSideDrawer() {
    const drawer = root.querySelector("[data-side-drawer]");
    if (!drawer) return;
    drawer.classList.remove("show");
    drawer.setAttribute("aria-hidden", "true");
    setTimeout(() => drawer.remove(), 220);
  }

  function sideDrawerIcon(name) {
    const icons = {
      bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/>',
      phone: '<rect x="7" y="3" width="10" height="18" rx="2.4"/><path d="M10 18h4"/>',
      sync: '<path d="M20 7v5h-5"/><path d="M4 17v-5h5"/><path d="M18.4 9A7 7 0 0 0 6 7.5"/><path d="M5.6 15A7 7 0 0 0 18 16.5"/>',
      chart: '<path d="M4 19V5"/><path d="M4 19h16"/><path d="M8 16v-5"/><path d="M12 16V8"/><path d="M16 16v-3"/>',
      user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
      shield: '<path d="M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6l-7-3Z"/><path d="M9.5 12.2 11.2 14l3.4-4"/>',
      moon: '<path d="M20 15.4A7.8 7.8 0 0 1 8.6 4a7 7 0 1 0 11.4 11.4Z"/>',
      lock: '<rect x="6" y="10" width="12" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
      privacy: '<path d="M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6l-7-3Z"/><path d="M9.5 12.2 11.2 14l3.4-4"/>',
      logout: '<path d="M10 17l5-5-5-5"/><path d="M15 12H3"/><path d="M21 4v16"/>'
    };
    return '<span class="drawer-row-icon" aria-hidden="true"><svg viewBox="0 0 24 24">' + (icons[name] || icons.bell) + '</svg></span>';
  }

  function openSideDrawer(config) {
    closeSideDrawer();
    const drawer = document.createElement("div");
    drawer.className = "settings-drawer dynamic-side-drawer";
    drawer.setAttribute("data-side-drawer", "");
    drawer.setAttribute("aria-hidden", "true");
    const groups = (config.groups || [{ title: "", rows: config.rows || [] }]).map((group) => {
      const rows = group.rows.map((item) => [
        '<button class="settings-row drawer-row" type="button" ' + (item.action || "") + '>',
        '<span class="drawer-row-main">',
        sideDrawerIcon(item.icon),
        '<span><strong>' + item.title + '</strong>' + (item.note ? '<small>' + item.note + '</small>' : '') + '</span>',
        '</span>',
        item.tail ? '<span class="drawer-row-tail">' + item.tail + '</span>' : '',
        '</button>'
      ].join("")).join("");
      return '<section class="setting-group">' + (group.title ? '<h3>' + group.title + '</h3>' : '') + rows + '</section>';
    }).join("");
    drawer.innerHTML = [
      '<div class="settings-scrim" data-side-close></div>',
      '<aside class="settings-panel" role="dialog" aria-modal="true" aria-label="' + config.title + '">',
      '<header class="settings-panel-head">',
      '<button class="icon-btn compact" type="button" aria-label="关闭" data-side-close><svg class="icon" viewBox="0 0 24 24"><path d="m15 18-6-6 6-6"/></svg></button>',
      '<div><p class="settings-kicker">' + (config.kicker || "设置") + '</p><h2>' + config.title + '</h2>' + (config.subtitle ? '<p class="drawer-subtitle">' + config.subtitle + '</p>' : '') + '</div>',
      '</header>',
      '<div class="settings-panel-body">' + groups + '</div>',
      '</aside>'
    ].join("");
    root.appendChild(drawer);
    requestAnimationFrame(() => {
      drawer.classList.add("show");
      drawer.setAttribute("aria-hidden", "false");
    });
    drawer.addEventListener("click", (event) => {
      if (event.target.closest("[data-side-close]")) {
        closeSideDrawer();
        return;
      }
      if (event.target.closest("[data-side-logout]")) {
        closeSideDrawer();
        setTimeout(performMockLogout, 180);
      }
    });
  }

  function homeIcon(name) {
    const icons = {
      bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/>',
      phone: '<rect x="7" y="3" width="10" height="18" rx="2.4"/><path d="M10 18h4"/>',
      sync: '<path d="M20 7v5h-5"/><path d="M4 17v-5h5"/><path d="M18.4 9A7 7 0 0 0 6 7.5"/><path d="M5.6 15A7 7 0 0 0 18 16.5"/>',
      chart: '<path d="M4 19V5"/><path d="M4 19h16"/><path d="M8 16v-5"/><path d="M12 16V8"/><path d="M16 16v-3"/>',
      user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
      shield: '<path d="M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6l-7-3Z"/><path d="M9.5 12.2 11.2 14l3.4-4"/>',
      moon: '<path d="M20 15.4A7.8 7.8 0 0 1 8.6 4a7 7 0 1 0 11.4 11.4Z"/>',
      lock: '<rect x="6" y="10" width="12" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>'
    };
    return '<span class="home-sheet-icon" aria-hidden="true"><svg viewBox="0 0 24 24">' + (icons[name] || icons.bell) + '</svg></span>';
  }

  function openHomeSheet(config) {
    let backdrop = root.querySelector(".home-sheet-backdrop");
    if (backdrop) backdrop.remove();
    backdrop = document.createElement("div");
    backdrop.className = "home-sheet-backdrop";
    const rows = config.rows.map((item) => [
      '<div class="home-sheet-row">',
      homeIcon(item.icon),
      '<span><strong>' + item.title + '</strong>' + (item.note ? '<small>' + item.note + '</small>' : '') + '</span>',
      item.tail ? '<span class="home-sheet-pill">' + item.tail + '</span>' : '',
      '</div>'
    ].join("")).join("");
    backdrop.innerHTML = [
      '<section class="home-sheet" role="dialog" aria-modal="true">',
      '<header class="home-sheet-head"><div><h2 class="home-sheet-title">' + config.title + '</h2>',
      config.subtitle ? '<p class="home-sheet-subtitle">' + config.subtitle + '</p>' : '',
      '</div><button class="home-sheet-close" type="button" aria-label="关闭" data-home-sheet-close>×</button></header>',
      '<div class="home-sheet-list">' + rows + '</div>',
      '</section>'
    ].join("");
    root.appendChild(backdrop);
    requestAnimationFrame(() => backdrop.classList.add("show"));
    backdrop.addEventListener("click", (event) => {
      if (event.target === backdrop || event.target.closest("[data-home-sheet-close]")) {
        backdrop.classList.remove("show");
        setTimeout(() => backdrop.remove(), 180);
      }
    });
  }

  function openHomeNotifications() {
    openHomeSheet({
      title: "通知中心",
      subtitle: "查看审批提醒、设备状态和报告更新",
      rows: [
        { icon: "bell", title: "审批提醒", note: "暂无新的审批请求", tail: "暂无" },
        { icon: "phone", title: "设备状态", note: "繁大师的手机在线", tail: "在线" },
        { icon: "sync", title: "规则同步", note: "今日规则已同步", tail: "正常" },
        { icon: "chart", title: "每日报告", note: "今日使用数据已更新", tail: "已更新" }
      ]
    });
  }

  function openHomeSettingsSheet() {
    openHomeSheet({
      title: "首页设置",
      subtitle: "管理首页提醒、安全确认和展示偏好",
      rows: [
        { icon: "bell", title: "重要提醒", note: "审批和异常状态会及时提醒", tail: "已开启" },
        { icon: "moon", title: "安静时段", note: "22:00 - 07:00 仅保留紧急提醒", tail: "已开启" },
        { icon: "shield", title: "操作前确认", note: "高风险操作前需要二次确认", tail: "已开启" },
        { icon: "lock", title: "二次确认密码", note: "用于锁定设备和修改时长", tail: "更改" }
      ]
    });
  }

  function ensureSheet() {
    let backdrop = root.querySelector(".sheet-backdrop");
    if (backdrop) return backdrop;
    backdrop = document.createElement("div");
    backdrop.className = "sheet-backdrop";
    backdrop.innerHTML = [
      '<section class="action-sheet" role="dialog" aria-modal="true" aria-label="确认锁定设备">',
      '<h2 class="sheet-title">确认锁定繁大帅的手机？</h2>',
      '<p class="sheet-copy">锁定后娱乐和社交应用会立即暂停，学习类应用仍可按规则使用。你可以随时在首页解除。</p>',
      '<div class="sheet-actions">',
      '<button class="btn btn-reject" data-sheet-close>取消</button>',
      '<button class="btn btn-approve" data-confirm-lock>确认锁定</button>',
      '</div>',
      '</section>'
    ].join("");
    root.appendChild(backdrop);
    backdrop.addEventListener("click", (event) => {
      if (event.target === backdrop || event.target.matches("[data-sheet-close]")) {
        backdrop.classList.remove("show");
      }
    });
    backdrop.querySelector("[data-confirm-lock]").addEventListener("click", () => {
      const card = document.querySelector(".device-card");
      if (card) {
        card.classList.add("is-locked");
        const badge = card.querySelector(".badge");
        const state = card.querySelector(".metric-value");
        if (badge) badge.textContent = "已锁定";
        if (state) state.textContent = "已暂停使用";
      }
      backdrop.classList.remove("show");
      toast("已发送锁定指令");
    });
    return backdrop;
  }

  function ensurePasswordSheet() {
    let backdrop = root.querySelector(".password-backdrop");
    if (backdrop) return backdrop;
    backdrop = document.createElement("div");
    backdrop.className = "sheet-backdrop password-backdrop";
    backdrop.innerHTML = [
      '<section class="action-sheet password-sheet" role="dialog" aria-modal="true" aria-label="设置二次确认密码">',
      '<h2 class="sheet-title">设置二次确认密码</h2>',
      '<p class="sheet-copy">用于锁定设备、延长时间等高风险操作。请设置 4 位以上确认码。</p>',
      '<label class="password-field">',
      '<span>二次确认密码</span>',
      '<input type="password" inputmode="numeric" autocomplete="new-password" maxlength="12" placeholder="输入 4 位以上数字" data-password-input>',
      '</label>',
      '<div class="sheet-actions">',
      '<button class="btn btn-reject" data-password-close>取消</button>',
      '<button class="btn btn-approve" data-password-save>保存密码</button>',
      '</div>',
      '</section>'
    ].join("");
    root.appendChild(backdrop);
    backdrop.addEventListener("click", (event) => {
      if (event.target === backdrop || event.target.matches("[data-password-close]")) {
        backdrop.classList.remove("show");
      }
      if (event.target.matches("[data-password-save]")) {
        const input = backdrop.querySelector("[data-password-input]");
        const value = input ? input.value.trim() : "";
        if (value.length < 4) {
          toast("请输入至少 4 位密码");
          if (input) input.focus();
          return;
        }
        const title = root.querySelector("[data-password-title]");
        const hint = root.querySelector("[data-password-hint]");
        if (title) title.textContent = "更改二次确认密码";
        if (hint) hint.textContent = "已设置 · 高风险操作前需要输入";
        root.dataset.confirmPasswordSet = "true";
        input.value = "";
        backdrop.classList.remove("show");
        toast("二次确认密码已设置");
      }
    });
    return backdrop;
  }

  function profileToast(message) {
    let node = root.querySelector(".profile-toast");
    if (!node) {
      node = document.createElement("div");
      node.className = "profile-toast";
      root.appendChild(node);
    }
    node.textContent = message;
    node.classList.add("show");
    clearTimeout(node._timer);
    node._timer = setTimeout(() => node.classList.remove("show"), 1500);
  }

  function closeProfileSheet() {
    const backdrop = root.querySelector(".profile-sheet-backdrop");
    if (!backdrop) return;
    backdrop.classList.remove("show");
    setTimeout(() => backdrop.remove(), 240);
  }

  const PROFILE_ICONS = {
    bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/>',
    phone: '<rect x="7" y="2.8" width="10" height="18.4" rx="2.4"/><path d="M10.5 18.2h3"/>',
    sync: '<path d="M20 6v5h-5"/><path d="M4 18v-5h5"/><path d="M18.2 10A6.5 6.5 0 0 0 6.1 7.6L4 11"/><path d="M5.8 14A6.5 6.5 0 0 0 17.9 16.4L20 13"/>',
    chart: '<path d="M4 19V5"/><path d="M8 17v-5"/><path d="M13 17V8"/><path d="M18 17v-8"/>',
    user: '<path d="M12 12.5a4.2 4.2 0 1 0 0-8.4 4.2 4.2 0 0 0 0 8.4Z"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/>',
    privacy: '<path d="M12 3 5.5 5.7v5.4c0 4.1 2.6 7.2 6.5 9.4 3.9-2.2 6.5-5.3 6.5-9.4V5.7L12 3Z"/><path d="m9.3 12.1 1.8 1.9 3.7-4"/>',
    logout: '<path d="M10 5H6.8A1.8 1.8 0 0 0 5 6.8v10.4A1.8 1.8 0 0 0 6.8 19H10"/><path d="M14 8l4 4-4 4"/><path d="M18 12H9"/>',
    check: '<circle cx="12" cy="12" r="8"/><path d="m8.8 12.2 2.1 2.2 4.4-5"/>',
    wifi: '<path d="M5 10.5a10.5 10.5 0 0 1 14 0"/><path d="M8.5 14a5.2 5.2 0 0 1 7 0"/><path d="M12 18h.01"/>',
    shield: '<path d="M12 3 5.5 5.7v5.4c0 4.1 2.6 7.2 6.5 9.4 3.9-2.2 6.5-5.3 6.5-9.4V5.7L12 3Z"/>',
    clock: '<circle cx="12" cy="12" r="8"/><path d="M12 7.5V12l3 2"/>',
    timer: '<circle cx="12" cy="13" r="7"/><path d="M9 2h6"/><path d="M12 6v7l4 2"/>',
    scan: '<path d="M4 8V5.8A1.8 1.8 0 0 1 5.8 4H8"/><path d="M16 4h2.2A1.8 1.8 0 0 1 20 5.8V8"/><path d="M20 16v2.2a1.8 1.8 0 0 1-1.8 1.8H16"/><path d="M8 20H5.8A1.8 1.8 0 0 1 4 18.2V16"/><path d="M7 12h10"/>',
    code: '<path d="m8 9-3 3 3 3"/><path d="m16 9 3 3-3 3"/><path d="m13.5 7-3 10"/>',
    link: '<path d="M10.5 13.5a3.2 3.2 0 0 0 4.5 0l2.6-2.6a3.2 3.2 0 0 0-4.5-4.5l-1.2 1.2"/><path d="M13.5 10.5a3.2 3.2 0 0 0-4.5 0l-2.6 2.6a3.2 3.2 0 0 0 4.5 4.5l1.2-1.2"/>',
    users: '<path d="M16 11a4 4 0 1 0-8 0"/><path d="M3.5 20a6.5 6.5 0 0 1 13 0"/><path d="M19 8v6M16 11h6"/>',
    plus: '<path d="M12 5v14"/><path d="M5 12h14"/>',
    key: '<circle cx="8" cy="12" r="3"/><path d="M11 12h8"/><path d="M16 12v3"/><path d="M19 12v2"/>',
    alert: '<path d="M12 5v8"/><path d="M12 17h.01"/><path d="M10.4 3.8a1.9 1.9 0 0 1 3.2 0l7.2 12.7A1.9 1.9 0 0 1 19.2 19H4.8a1.9 1.9 0 0 1-1.6-2.5l7.2-12.7Z"/>',
    moon: '<path d="M18.5 15.4A7.3 7.3 0 0 1 8.6 5.5a7.3 7.3 0 1 0 9.9 9.9Z"/>',
    doc: '<path d="M7 3.5h6l4 4V20a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1Z"/><path d="M13 3.5V8h4"/><path d="M9 12h6"/><path d="M9 16h4"/>',
    help: '<circle cx="12" cy="12" r="8"/><path d="M9.8 9a2.4 2.4 0 1 1 4.2 1.6c-.8.7-1.8 1.2-1.8 2.7"/><path d="M12 17h.01"/>',
    headset: '<path d="M5 13a7 7 0 0 1 14 0"/><path d="M5 13v3a2 2 0 0 0 2 2h1v-6H7a2 2 0 0 0-2 2Z"/><path d="M19 13v3a2 2 0 0 1-2 2h-1v-6h1a2 2 0 0 1 2 2Z"/><path d="M13 20h2a4 4 0 0 0 4-4"/>',
    edit: '<path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17v3Z"/><path d="m14 8 2 2"/>',
    info: '<circle cx="12" cy="12" r="8"/><path d="M12 11v5"/><path d="M12 8h.01"/>'
  };

  function profileIcon(name, tone) {
    const path = PROFILE_ICONS[name] || PROFILE_ICONS.info;
    return '<span class="profile-sheet-icon ' + (tone || "blue") + '" aria-hidden="true"><svg viewBox="0 0 24 24">' + path + "</svg></span>";
  }

  function profilePill(text, tone) {
    return '<span class="profile-sheet-pill ' + (tone || "blue") + '">' + text + "</span>";
  }

  function openProfileSheet(config) {
    closeProfileSheet();
    const backdrop = document.createElement("div");
    backdrop.className = "profile-sheet-backdrop";
    const templateClass = config.template === "detail" ? "profile-sheet--detail" : "profile-sheet--list";
    backdrop.innerHTML = [
      '<section class="profile-sheet ' + templateClass + ' ' + (config.size || "") + '" role="dialog" aria-modal="true">',
      '<header class="profile-sheet-head">',
      '<div><h2 class="profile-sheet-title">' + config.title + '</h2>',
      config.subtitle ? '<p class="profile-sheet-subtitle">' + config.subtitle + '</p>' : '',
      '</div><button class="profile-sheet-close" type="button" aria-label="关闭" data-profile-close>×</button>',
      '</header>',
      config.body || '',
      config.actions || '',
      '</section>'
    ].join("");
    root.appendChild(backdrop);
    requestAnimationFrame(() => backdrop.classList.add("show"));
    backdrop.addEventListener("click", (event) => {
      if (event.target === backdrop || event.target.closest("[data-profile-close]")) {
        closeProfileSheet();
        return;
      }
      const switcher = event.target.closest(".profile-modal-switch");
      if (switcher) {
        const enabled = !switcher.classList.contains("on");
        switcher.classList.toggle("on", enabled);
        switcher.setAttribute("aria-checked", String(enabled));
        profileToast(enabled ? "已开启" : "已关闭");
        return;
      }
      if (event.target.closest("[data-profile-confirm-logout]")) {
        closeProfileSheet();
        performMockLogout();
        return;
      }
      if (event.target.closest("[data-profile-lock-confirm]")) {
        closeProfileSheet();
        profileToast("已发送锁定指令");
        return;
      }
      const timeChoice = event.target.closest("[data-profile-time-choice]");
      if (timeChoice) {
        closeProfileSheet();
        profileToast("今日时长已设为 " + timeChoice.textContent.trim());
        return;
      }
      const bindOption = event.target.closest("[data-profile-bind-option]");
      if (bindOption) {
        const option = bindOption.getAttribute("data-profile-bind-option");
        if (option === "code") {
          openProfileBindingCodeSheet();
        } else {
          profileToast("当前先支持输入绑定码。");
        }
        return;
      }
      const bindConfirm = event.target.closest("[data-profile-bind-confirm]");
      if (bindConfirm) {
        confirmParentBinding(bindConfirm);
        return;
      }
      if (event.target.closest("[data-profile-members-option]")) {
        profileToast("家庭成员管理功能稍后开放。");
        return;
      }
      if (event.target.closest("[data-profile-feedback-open]")) {
        openProfileFeedbackSheet();
        return;
      }
      if (event.target.closest("[data-profile-feedback-submit]")) {
        closeProfileSheet();
        profileToast("反馈已提交，感谢你的建议。");
      }
    });
  }

  function rows(items, options) {
    const settings = Object.assign({ detail: false }, options);
    return '<div class="profile-sheet-list ' + (settings.detail ? "profile-sheet-list--detail" : "") + '">' + items.map((item) => (
      '<div class="profile-sheet-row ' + (item.danger ? "danger" : "") + '">' +
      profileIcon(item.icon, item.tone) +
      '<div><div>' + item.title + '</div>' +
      (item.note ? '<small>' + item.note + '</small>' : '') +
      '</div>' +
      (item.value ? '<span class="profile-sheet-value">' + item.value + '</span>' : item.tail || '') +
      '</div>'
    )).join("") + "</div>";
  }

  function openProfileConfirmLogout() {
    openProfileSheet({
      template: "detail",
      title: "确定退出当前账号吗？",
      subtitle: "退出后仍可重新登录继续管理家庭设备。",
      body: '<div class="profile-sheet-actions"><button class="profile-sheet-button secondary" data-profile-close>取消</button><button class="profile-sheet-button danger" data-profile-confirm-logout>退出登录</button></div>'
    });
  }

  function openProfileTimeSheet() {
    openProfileSheet({
      template: "list",
      title: "修改今日时长",
      subtitle: "选择后会用于后续的反馈处理。",
      body: rows([
        { icon: "timer", tone: "blue", title: "30分钟", tail: '<button class="profile-sheet-action" data-profile-time-choice>选择</button>' },
        { icon: "timer", tone: "blue", title: "1小时", tail: '<button class="profile-sheet-action" data-profile-time-choice>选择</button>' },
        { icon: "timer", tone: "blue", title: "1.5小时", tail: '<button class="profile-sheet-action" data-profile-time-choice>选择</button>' },
        { icon: "edit", tone: "purple", title: "自定义", tail: '<button class="profile-sheet-action" data-profile-time-choice>选择</button>' }
      ])
    });
  }

  function openProfileFeedbackSheet() {
    openProfileSheet({
      template: "list",
      title: "提交反馈",
      subtitle: "你的建议会帮助我们继续完善家长端体验。",
      body: '<textarea class="profile-feedback-input" placeholder="请输入你的反馈"></textarea><div class="profile-sheet-actions"><button class="profile-sheet-button secondary" data-profile-close>取消</button><button class="profile-sheet-button" data-profile-feedback-submit>提交</button></div>'
    });
  }

  function openProfileBindingCodeSheet() {
    openProfileSheet({
      template: "detail",
      title: "输入绑定码",
      subtitle: "请输入学生端绑定页显示的 6 位绑定码。",
      body: [
        '<label class="profile-bind-code-field">',
        '<span>绑定码</span>',
        '<input type="text" inputmode="text" maxlength="6" autocomplete="one-time-code" placeholder="例如 A7K9Q2" data-parent-binding-code>',
        '</label>',
        '<p class="profile-bind-message" data-parent-binding-message>绑定成功后，孩子设备会出现在家庭设备列表中。</p>',
        '<div class="profile-sheet-actions">',
        '<button class="profile-sheet-button secondary" data-profile-close>取消</button>',
        '<button class="profile-sheet-button" data-profile-bind-confirm>确认绑定</button>',
        '</div>'
      ].join("")
    });
    const input = root.querySelector("[data-parent-binding-code]");
    if (input) input.focus();
  }

  async function confirmParentBinding(button) {
    const sheet = button.closest(".profile-sheet");
    const input = sheet && sheet.querySelector("[data-parent-binding-code]");
    const message = sheet && sheet.querySelector("[data-parent-binding-message]");
    const bindingCode = input ? input.value.trim().toUpperCase() : "";
    if (!/^[A-Z0-9]{6}$/.test(bindingCode)) {
      if (message) message.textContent = "请输入学生端显示的 6 位绑定码。";
      if (input) input.focus();
      return;
    }
    button.disabled = true;
    const previousText = button.textContent;
    button.textContent = "绑定中...";
    if (message) message.textContent = "正在确认绑定码...";
    try {
      const result = await postJson("/api/parent/binding/confirm", { bindingCode });
      const data = result.data || {};
      if (message) message.textContent = result.message || "绑定成功";
      profileToast("绑定成功");
      await Promise.allSettled([loadProfile(), loadHome()]);
      setTimeout(() => closeProfileSheet(), 650);
      if (data.deviceName) profileToast(data.deviceName + " 已绑定");
    } catch (error) {
      if (message) message.textContent = error.message || "绑定失败，请稍后重试";
      button.disabled = false;
      button.textContent = previousText;
    }
  }

  function handleProfileAction(action, event) {
    const actionName = action.getAttribute("data-profile-action");
    if (!actionName) return false;
    if (actionName === "notifications") {
      openProfileSheet({
        template: "list",
        title: "通知中心",
        subtitle: "查看审批提醒、设备状态和报告更新",
        body: rows([
          { icon: "bell", tone: "orange", title: "审批提醒", tail: profilePill("暂无", "gray") },
          { icon: "phone", tone: "green", title: "设备状态", note: "繁大师的手机在线", tail: profilePill("正常", "green") },
          { icon: "sync", tone: "cyan", title: "规则同步", note: "今日规则已同步", tail: profilePill("已同步", "blue") },
          { icon: "chart", tone: "purple", title: "每日报告", note: "今日使用数据已更新", tail: profilePill("已更新", "purple") }
        ])
      });
      return true;
    }
    if (actionName === "system-settings") {
      openProfileSheet({
        template: "list",
        title: "系统设置",
        subtitle: "账号、通知和隐私相关设置",
        body: rows([
          { icon: "user", tone: "blue", title: "账号设置", tail: '<span class="profile-sheet-chevron">›</span>' },
          { icon: "bell", tone: "orange", title: "通知设置", tail: '<span class="profile-sheet-chevron">›</span>' },
          { icon: "privacy", tone: "cyan", title: "隐私设置", tail: '<span class="profile-sheet-chevron">›</span>' },
          { icon: "logout", tone: "red", title: "退出登录", danger: true, tail: '<button class="profile-sheet-action danger" data-profile-open-logout>退出</button>' }
        ])
      });
      const sheet = root.querySelector(".profile-sheet-backdrop");
      if (sheet) {
        sheet.querySelector("[data-profile-open-logout]")?.addEventListener("click", (clickEvent) => {
          clickEvent.stopPropagation();
          openProfileConfirmLogout();
        });
      }
      return true;
    }
    if (actionName === "account") {
      openProfileSheet({
        template: "detail",
        title: "家庭账号资料",
        body: '<div class="profile-account-summary"><div class="profile-detail-avatar">辛</div><strong>辛斯繁</strong><span>家庭账号管理员</span></div>' + rows([
          { icon: "phone", tone: "blue", title: "已绑定", value: "1 个孩子设备" },
          { icon: "users", tone: "cyan", title: "家庭成员", value: "2 位" },
          { icon: "info", tone: "gray", title: "手机号", value: "138 0000 2468" }
        ], { detail: true }) + '<div class="profile-sheet-actions single"><button class="profile-sheet-button secondary" data-profile-close>关闭</button></div>'
      });
      return true;
    }
    if (actionName === "guardian-status") {
      openProfileSheet({
        template: "detail",
        title: "守护运行详情",
        body: rows([
          { icon: "check", tone: "green", title: "今日规则运行正常" },
          { icon: "wifi", tone: "green", title: "设备在线" },
          { icon: "sync", tone: "cyan", title: "规则已同步" },
          { icon: "shield", tone: "blue", title: "暂无异常使用" },
          { icon: "clock", tone: "gray", title: "最后同步", value: "1 分钟前" }
        ], { detail: true }) + '<div class="profile-sheet-actions single"><button class="profile-sheet-button secondary" data-profile-close>知道了</button></div>'
      });
      return true;
    }
    if (actionName === "device-detail") {
      openProfileSheet({
        template: "detail",
        title: "设备详情",
        body: rows([
          { icon: "phone", tone: "blue", title: "设备名称", value: "繁大师的手机" },
          { icon: "wifi", tone: "green", title: "状态", value: "在线 · 运行正常" },
          { icon: "clock", tone: "purple", title: "今日已用", value: "2h10" },
          { icon: "timer", tone: "cyan", title: "剩余可用", value: "1h25m" },
          { icon: "sync", tone: "blue", title: "规则状态", value: "已同步" }
        ], { detail: true }) + '<div class="profile-sheet-actions"><button class="profile-sheet-button danger-light" data-profile-open-lock>一键锁定</button><button class="profile-sheet-button secondary" data-profile-open-time>修改今日时长</button></div>'
      });
      const sheet = root.querySelector(".profile-sheet-backdrop");
      if (sheet) {
        sheet.querySelector("[data-profile-open-lock]")?.addEventListener("click", (clickEvent) => {
          clickEvent.stopPropagation();
          openProfileSheet({
            template: "detail",
            title: "确定锁定该设备吗？",
            subtitle: "锁定后孩子端会立即进入受限状态。",
            body: '<div class="profile-sheet-actions"><button class="profile-sheet-button secondary" data-profile-close>取消</button><button class="profile-sheet-button danger" data-profile-lock-confirm>确认锁定</button></div>'
          });
        });
        sheet.querySelector("[data-profile-open-time]")?.addEventListener("click", (clickEvent) => {
          clickEvent.stopPropagation();
          openProfileTimeSheet();
        });
      }
      return true;
    }
    if (actionName === "online-toast") {
      if (event) event.stopPropagation();
      profileToast("设备当前在线，规则正在正常运行。");
      return true;
    }
    if (actionName === "bind-device") {
      openProfileSheet({
        template: "list",
        title: "绑定孩子设备",
        subtitle: "完成授权后即可查看设备状态并设置规则",
        body: rows([
          { icon: "scan", tone: "blue", title: "扫码安装孩子端", tail: '<button class="profile-sheet-action" data-profile-bind-option="scan">选择</button>' },
          { icon: "code", tone: "cyan", title: "输入绑定码", tail: '<button class="profile-sheet-action" data-profile-bind-option="code">选择</button>' },
          { icon: "link", tone: "purple", title: "发送邀请链接", tail: '<button class="profile-sheet-action" data-profile-bind-option="link">选择</button>' }
        ])
      });
      return true;
    }
    if (actionName === "family-members") {
      openProfileSheet({
        template: "list",
        title: "家庭成员管理",
        body: rows([
          { icon: "user", tone: "blue", title: "辛斯繁", note: "管理员" },
          { icon: "users", tone: "cyan", title: "家庭成员 A", note: "成员" },
          { icon: "plus", tone: "green", title: "添加家长", tail: '<button class="profile-sheet-action" data-profile-members-option>添加</button>' },
          { icon: "key", tone: "purple", title: "设置成员权限", tail: '<button class="profile-sheet-action" data-profile-members-option>设置</button>' }
        ])
      });
      return true;
    }
    if (actionName === "notification-preferences") {
      openProfileSheet({
        template: "list",
        title: "通知与审批偏好",
        subtitle: "设置你希望接收的提醒类型",
        body: rows([
          { icon: "bell", tone: "orange", title: "审批请求提醒", tail: '<button class="profile-modal-switch on" role="switch" aria-checked="true"></button>' },
          { icon: "chart", tone: "purple", title: "每日使用报告", tail: '<button class="profile-modal-switch on" role="switch" aria-checked="true"></button>' },
          { icon: "alert", tone: "red", title: "异常使用提醒", tail: '<button class="profile-modal-switch on" role="switch" aria-checked="true"></button>' },
          { icon: "moon", tone: "gray", title: "免打扰时间", tail: '<button class="profile-modal-switch" role="switch" aria-checked="false"></button>' }
        ])
      });
      return true;
    }
    if (actionName === "privacy") {
      openProfileSheet({
        template: "list",
        title: "隐私与数据说明",
        body: rows([
          { icon: "privacy", tone: "cyan", title: "我们仅展示家庭设备管理所需的数据" },
          { icon: "shield", tone: "blue", title: "孩子使用数据仅用于家长查看与规则设置" },
          { icon: "check", tone: "green", title: "不会用于无关用途" },
          { icon: "doc", tone: "purple", title: "可查看《隐私政策》", tail: '<button class="profile-sheet-action">查看</button>' }
        ])
      });
      return true;
    }
    if (actionName === "help") {
      openProfileSheet({
        template: "list",
        title: "帮助与反馈",
        body: rows([
          { icon: "help", tone: "purple", title: "常见问题", tail: '<span class="profile-sheet-chevron">›</span>' },
          { icon: "headset", tone: "blue", title: "联系客服", tail: '<span class="profile-sheet-chevron">›</span>' },
          { icon: "edit", tone: "cyan", title: "提交反馈", tail: '<button class="profile-sheet-action" data-profile-feedback-open>填写</button>' },
          { icon: "info", tone: "gray", title: "当前版本", note: "v1.0.0" }
        ])
      });
      return true;
    }
    return false;
  }

  openHomeNotifications = function () {
    openSideDrawer({
      kicker: "通知",
      title: "通知中心",
      subtitle: "查看审批提醒、设备状态和报告更新",
      rows: [
        { icon: "bell", title: "审批提醒", note: "暂无新的审批请求", tail: "暂无" },
        { icon: "phone", title: "设备状态", note: "繁大师的手机在线", tail: "在线" },
        { icon: "sync", title: "规则同步", note: "今日规则已同步", tail: "正常" },
        { icon: "chart", title: "每日报告", note: "今日使用数据已更新", tail: "已更新" }
      ]
    });
  };

  openHomeSettingsSheet = function () {
    openSideDrawer({
      kicker: "设置",
      title: "首页设置",
      subtitle: "管理首页提醒、安全确认和展示偏好",
      groups: [
        {
          title: "提醒",
          rows: [
            { icon: "bell", title: "重要提醒", note: "审批和异常状态会及时提醒", tail: "已开启" },
            { icon: "moon", title: "安静时段", note: "22:00 - 07:00 仅保留紧急提醒", tail: "已开启" }
          ]
        },
        {
          title: "安全",
          rows: [
            { icon: "shield", title: "操作前确认", note: "高风险操作前需要二次确认", tail: "已开启" },
            { icon: "lock", title: "二次确认密码", note: "用于锁定设备和修改时长", tail: "更改" }
          ]
        }
      ]
    });
  };

  const originalHandleProfileAction = handleProfileAction;
  handleProfileAction = function (action, event) {
    const actionName = action.getAttribute("data-profile-action");
    if (actionName === "notifications") {
      openSideDrawer({
        kicker: "通知",
        title: "通知中心",
        subtitle: "查看审批提醒、设备状态和报告更新",
        rows: [
          { icon: "bell", title: "审批提醒", note: "暂无新的审批请求", tail: "暂无" },
          { icon: "phone", title: "设备状态", note: "繁大师的手机在线", tail: "在线" },
          { icon: "sync", title: "规则同步", note: "今日规则已同步", tail: "正常" },
          { icon: "chart", title: "每日报告", note: "今日使用数据已更新", tail: "已更新" }
        ]
      });
      return true;
    }
    if (actionName === "system-settings") {
      openSideDrawer({
        kicker: "设置",
        title: "系统设置",
        subtitle: "账号、通知和隐私相关设置",
        rows: [
          { icon: "user", title: "账号设置", tail: "›" },
          { icon: "bell", title: "通知设置", tail: "›" },
          { icon: "privacy", title: "隐私设置", tail: "›" },
          { icon: "logout", title: "退出登录", tail: "退出", action: "data-side-logout" }
        ]
      });
      return true;
    }
    return originalHandleProfileAction(action, event);
  };

  const API_BASE = "https://approval.example.com";
  const parentState = {
    dashboard: null,
    commands: null,
    pending: null,
    recent: null,
    rules: null,
    profile: null,
    blockedDraft: null,
    whitelistDraft: null
  };

  function currentPage() {
    const page = root.querySelector(".page");
    return page ? page.dataset.page || "" : "";
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  async function apiRequest(path, options) {
    const response = await fetch(API_BASE + path, Object.assign({ headers: { "Content-Type": "application/json" } }, options || {}));
    let data = null;
    try {
      data = await response.json();
    } catch (error) {
      data = { ok: false, message: "接口返回格式不正确" };
    }
    if (!response.ok || data.ok === false) {
      throw new Error(data.message || data.error || "接口请求失败");
    }
    return data;
  }

  function getJson(path) {
    return apiRequest(path);
  }

  function postJson(path, body) {
    return apiRequest(path, { method: "POST", body: JSON.stringify(body || {}) });
  }

  function setApiState(container, type, message) {
    const scope = container || root.querySelector(".screen") || root;
    let node = scope.querySelector(":scope > .api-state");
    if (!node) {
      node = document.createElement("div");
      node.className = "api-state subtle";
      scope.prepend(node);
    }
    node.hidden = !message;
    node.dataset.state = type || "normal";
    node.textContent = message || "";
  }

  function formatDuration(seconds) {
    const total = Math.max(0, Math.round(Number(seconds || 0)));
    if (!total) return "0 分钟";
    const minutes = Math.ceil(total / 60);
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    if (hours && rest) return hours + "小时" + rest + "分钟";
    if (hours) return hours + "小时";
    return minutes + "分钟";
  }

  function formatMinutes(minutes) {
    const value = Math.max(0, Math.round(Number(minutes || 0)));
    const hours = Math.floor(value / 60);
    const rest = value % 60;
    if (hours && rest) return hours + "h " + rest + "m";
    if (hours) return hours + "h";
    return rest + "m";
  }

  function statusText(status) {
    const map = {
      guarding: "守护中", locked: "已锁定", paused: "未开启守门", offline: "离线", error: "状态异常",
      active: "守护中", inactive: "未开启守门", ended: "未开启守门",
      pending: "待审批", approved: "已同意", rejected: "已拒绝", cancelled: "已撤回", expired: "已过期",
      success: "成功", failed: "失败", timeout: "超时", pulled: "已拉取"
    };
    return map[status] || status || "未知";
  }

  function guardStatusLine(status) {
    if (status === "active" || status === "guarding") return "守护中 · 运行正常";
    if (status === "locked") return "设备已锁定";
    if (status === "error") return "状态异常，请检查权限";
    return "未开启守门";
  }

  function deviceStatusLine(item) {
    const status = item && (item.onlineStatus || item.deviceStatus || item.guardStatus);
    if (status === "error") return "异常 · 请检查设备状态";
    if (status === "online" || (item && item.deviceOnline === true)) return "在线 · 运行正常";
    return "离线 · 最近同步 " + escapeHtml((item && item.lastSyncText) || "未知");
  }

  function deviceStatusPill(item) {
    const status = item && (item.onlineStatus || item.deviceStatus || item.guardStatus);
    if (status === "error") return "异常";
    if (status === "online" || (item && item.deviceOnline === true)) return "在线";
    return "离线";
  }

  function displayDeviceName(item, fallbackChildName) {
    const raw = String((item && item.deviceName) || "").trim();
    const childName = fallbackChildName || (item && item.childName) || "孩子";
    if (!raw || /^xiaomi\b/i.test(raw) || /25060RK16C/i.test(raw)) return childName + "的手机";
    return raw;
  }

  function appMark(name) {
    return escapeHtml((name || "?").trim().slice(0, 1) || "?");
  }

  function ensureSection(afterNode, className, title) {
    let section = root.querySelector("." + className);
    if (!section) {
      section = document.createElement("section");
      section.className = "section " + className;
      section.innerHTML = '<div class="section-title"><h2>' + title + '</h2></div><div class="card list-gap"></div>';
      if (afterNode && afterNode.parentNode) {
        afterNode.parentNode.insertBefore(section, afterNode.nextSibling);
      } else {
        (root.querySelector(".screen") || root).appendChild(section);
      }
    }
    return section;
  }

  function recordRow(request) {
    const appName = request.appName || "应用";
    const minutes = request.durationMinutes || request.requestMinutes || 0;
    const status = request.status || "";
    return '<div class="row-between"><div><strong>' + escapeHtml(appName) + ' · ' + escapeHtml(minutes) + ' 分钟</strong><p class="subtle">' + escapeHtml(statusText(status)) + ' · ' + escapeHtml(request.submittedAtText || request.requestTime || "") + '</p></div><span class="record-chip ' + (status === "approved" ? "strong" : "") + '">' + escapeHtml(statusText(status)) + '</span></div>';
  }

  function commandRow(command) {
    const status = command.commandStatus || command.resultStatus;
    const message = command.resultMessage || (status === "timeout" ? "命令已超时，请确认学生端是否在线" : "等待学生端执行");
    return '<div class="row-between"><div><strong>' + escapeHtml(command.commandLabel || "设备命令") + '</strong><p class="subtle">' + escapeHtml(message) + '</p></div><span class="record-chip ' + (status === "success" ? "strong" : "") + '">' + escapeHtml(statusText(status)) + '</span></div>';
  }

  function requestCard(request) {
    const id = escapeHtml(request.requestId || request.id || "");
    const appName = request.appName || "应用";
    const minutes = request.durationMinutes || request.requestMinutes || 0;
    const category = request.appCategory || request.category || "应用";
    const reason = request.reason || request.requestReason || "未填写原因";
    const time = request.submittedAtText || request.requestTime || request.submittedAt || "";
    return '<article class="request-card approval-request" data-request-id="' + id + '"><div class="request-top"><div class="app-mark">' + appMark(appName) + '</div><div><p class="eyebrow-line">' + escapeHtml(category) + ' · 待审批</p><h3 class="request-title">' + escapeHtml(appName) + ' · 使用 ' + escapeHtml(minutes) + ' 分钟</h3><p class="request-meta">用途：' + escapeHtml(reason) + ' · 来自 ' + escapeHtml(request.childName || "孩子") + ' · ' + escapeHtml(time) + '</p></div><span class="chevron">›</span></div><div class="request-actions"><button class="btn btn-reject" data-parent-approval-action="reject" data-request-id="' + id + '">拒绝</button><button class="btn btn-approve" data-parent-approval-action="approve" data-request-id="' + id + '">同意</button></div></article>';
  }

  function renderHomeDashboard(data) {
    parentState.dashboard = data;
    const top = root.querySelector(".top");
    const greeting = root.querySelector(".greeting");
    if (greeting) greeting.textContent = "晚上好，" + (data.parentName || data.guardianName || "家长");
    const statusLine = top && top.querySelector(".subtle");
    if (statusLine) statusLine.innerHTML = '<span class="online-dot"></span>' + escapeHtml(deviceStatusLine(data));

    const heroStrong = root.querySelector(".hero-ring-core strong");
    const heroSpan = root.querySelector(".hero-ring-core span");
    if (heroStrong) heroStrong.textContent = formatMinutes(data.todayUsage && data.todayUsage.usedMinutes);
    const usage = data.todayUsage || {};
    const usagePercent = usage.totalMinutes ? Math.round((Number(usage.usedMinutes || 0) / Number(usage.totalMinutes || 1)) * 100) : 68;
    if (heroSpan) heroSpan.textContent = "今日已用 · " + Math.max(0, Math.min(100, usagePercent || 68)) + "%";
    const statCards = root.querySelectorAll(".hero-stat-card");
    if (statCards[0]) {
      statCards[0].querySelector("span:nth-of-type(2)").textContent = "今日剩余";
      statCards[0].querySelector("strong").textContent = formatMinutes(data.todayUsage && data.todayUsage.remainingMinutes);
    }
    if (statCards[1]) {
      statCards[1].querySelector("span:nth-of-type(2)").textContent = "解锁次数";
      statCards[1].querySelector("strong").textContent = ((data.todayUsage && data.todayUsage.unlockCount) || 0) + "次";
    }
    if (statCards[2]) {
      const recent = data.recentApps && data.recentApps[0];
      statCards[2].querySelector("span:nth-of-type(2)").textContent = "最近使用";
      statCards[2].querySelector("strong").textContent = (recent && (recent.name || recent.appName)) || "暂无";
    }

    const childName = root.querySelector(".child-name");
    if (childName) childName.textContent = displayDeviceName(data, data.childName);
    const deviceName = root.querySelector(".device-name");
    if (deviceName) deviceName.innerHTML = '<span class="online-dot"></span>' + escapeHtml(deviceStatusLine(data));
    const badge = root.querySelector(".device-card .badge");
    if (badge) badge.textContent = statusText(data.studentGuardStatus || data.guardStatus);
    const metricValue = root.querySelector(".device-progress-card .metric-value");
    if (metricValue) metricValue.textContent = formatDuration(data.remainingSeconds);
    const metricLabel = root.querySelector(".device-progress-card .metric-label");
    if (metricLabel) metricLabel.textContent = "守门剩余";
    const progress = root.querySelector(".home-progress i");
    if (progress) progress.style.inlineSize = Math.max(0, Math.min(100, Number(data.progressPercent || 0))) + "%";

    const quickLock = Array.from(root.querySelectorAll(".quick-card")).find((button) => button.textContent.includes("一键锁定") || button.textContent.includes("临时锁定") || button.textContent.includes("取消锁定"));
    if (quickLock) {
      const locked = isDashboardLocked(data);
      quickLock.removeAttribute("data-parent-command");
      quickLock.removeAttribute("data-parent-cancel-lock");
      quickLock.setAttribute(locked ? "data-parent-cancel-lock" : "data-parent-command", locked ? "true" : "one_key_lock");
      const strong = quickLock.querySelector("strong");
      const span = quickLock.querySelector("span");
      if (strong) strong.textContent = locked ? "取消锁定设备" : "临时锁定设备";
      if (span) span.textContent = locked ? "结束当前守门" : "选择时长后锁定";
    }
    const quickCards = root.querySelectorAll('[data-od-id="quick-actions"] .quick-card');
    if (quickCards[0]) {
      quickCards[0].setAttribute("data-open-blocked-ranges", "true");
      const strong = quickCards[0].querySelector("strong");
      const span = quickCards[0].querySelector("span");
      if (strong) strong.textContent = "今日禁止时段";
      if (span) span.textContent = "设置今天不能使用手机的时间";
    }

    const quickActions = root.querySelector('[data-od-id="quick-actions"]');
    const section = ensureSection(quickActions, "parent-dashboard-live", "当前联动状态");
    section.querySelector(".card").innerHTML = [
      '<div class="row-between"><div><strong>待审批请求</strong><p class="subtle">来自学生端临时放行申请</p></div><span class="record-chip strong">' + escapeHtml(data.pendingApprovalCount || 0) + ' 条</span></div>',
      '<div class="row-between"><div><strong>白名单应用</strong><p class="subtle">来自学生端最近同步</p></div><span class="record-chip">' + escapeHtml(data.whitelistCount || 0) + ' 个</span></div>',
      '<div class="row-between"><div><strong>使用建议</strong><p class="subtle">周末娱乐时间偏集中。建议提前设置两个固定娱乐时段，减少临时审批。</p></div><span class="record-chip">建议</span></div>'
    ].join("");
  }

  function renderCommands(data) {
    const quickActions = root.querySelector('[data-od-id="quick-actions"]') || root.querySelector(".parent-dashboard-live");
    const section = ensureSection(quickActions, "parent-command-list", "最近命令");
    const list = section.querySelector(".card");
    const commands = (data.commands || []).slice(0, 5);
    list.innerHTML = commands.length
      ? commands.map(commandRow).join("")
      : '<div class="empty-state"><strong>暂无命令记录</strong><span>一键锁定后会显示执行状态。</span></div>';
  }

  function isDashboardLocked(data) {
    const guard = String((data && (data.studentGuardStatus || data.guardStatus)) || "").toLowerCase();
    return guard === "active" || guard === "locked" || guard === "guarding" || data.guardModeEnabled === true || data.globalGuard === true || data.guard_mode_enabled === true || data.global_guard === true;
  }

  async function loadHome() {
    const screen = root.querySelector(".screen");
    setApiState(screen, "loading", "正在同步家长端数据...");
    try {
      const dashboard = await getJson("/api/parent/dashboard");
      renderHomeDashboard(dashboard);
      if (dashboard.deviceId) {
        const commands = await getJson("/api/parent/devices/" + encodeURIComponent(dashboard.deviceId) + "/commands");
        parentState.commands = commands;
        renderCommands(commands);
      }
      setApiState(screen, "normal", "");
    } catch (error) {
      setApiState(screen, "error", "同步失败：" + error.message);
    }
  }

  async function loadApprovals() {
    const page = root.querySelector('[data-page="approval"]');
    setApiState(page, "loading", "正在读取审批请求...");
    try {
      const pending = await getJson("/api/parent/approvals/pending");
      const recent = await getJson("/api/parent/approvals/recent");
      parentState.pending = pending;
      parentState.recent = recent;
      const pill = root.querySelector(".page-action-pill");
      if (pill) pill.textContent = (pending.count || 0) + " 个待审批";
      const tiles = root.querySelectorAll(".summary-tile strong");
      if (tiles[0]) tiles[0].textContent = String((pending.count || 0) + (recent.count || 0));
      if (tiles[1]) tiles[1].textContent = String((recent.requests || []).filter((item) => item.status === "approved").length);
      if (tiles[2]) tiles[2].textContent = "较快";
      const approvalSection = root.querySelector(".approval-hero");
      if (approvalSection) {
        const requests = pending.requests || [];
        const titleLink = approvalSection.querySelector(".section-title a");
        if (titleLink) titleLink.textContent = requests.length ? "实时数据" : "暂无待处理";
        approvalSection.querySelectorAll(".request-card, .empty-state").forEach((node) => node.remove());
        approvalSection.insertAdjacentHTML("beforeend", requests.length ? requests.map(requestCard).join("") : '<div class="empty-state"><strong>当前没有待处理请求</strong><span>新的临时放行申请会在这里出现。</span></div>');
      }
      const recordList = root.querySelector(".record-list");
      if (recordList) {
        const rows = recent.requests || [];
        recordList.innerHTML = rows.length ? rows.slice(0, 8).map(recordRow).join("") : '<div class="empty-state"><strong>暂无最近记录</strong><span>同意或拒绝后会出现在这里。</span></div>';
      }
      setApiState(page, "normal", "");
    } catch (error) {
      setApiState(page, "error", "审批数据加载失败：" + error.message);
    }
  }

  function appPackage(app) {
    return app.packageName || app.appPackage || app.appId || "";
  }

  function appDisplayName(app) {
    return app.name || app.appName || "应用";
  }

  function appCategory(app) {
    return app.category || app.appCategory || "本机应用";
  }

  function commandLabel(commandType) {
    if (commandType === "app_block") return "限制单个应用";
    if (commandType === "app_unblock") return "解除单个应用限制";
    if (commandType === "set_guard_whitelist") return "同步白名单";
    if (commandType === "set_daily_time_ranges") return "设置每日可用时间段";
    if (commandType === "set_blocked_time_ranges") return "设置今日禁止时段";
    if (commandType === "stop_guard_mode") return "结束守门";
    if (commandType === "temporary_lock" || commandType === "start_guard_mode_custom") return "临时锁定";
    return commandType || "设备命令";
  }

  function currentRules() {
    return parentState.rules && parentState.rules.rules ? parentState.rules.rules : {};
  }

  function rulesApps() {
    const rules = currentRules();
    return Array.isArray(rules.apps) ? rules.apps : [];
  }

  function rulesWhitelist() {
    const rules = currentRules();
    return Array.isArray(rules.whitelist) ? rules.whitelist : [];
  }

  function rulesBlockedApps() {
    const rules = currentRules();
    return Array.isArray(rules.blockedApps) ? rules.blockedApps : [];
  }

  function findAppByPackage(packageName) {
    const pkg = String(packageName || "");
    return rulesApps().find((app) => appPackage(app) === pkg)
      || rulesWhitelist().find((app) => appPackage(app) === pkg)
      || rulesBlockedApps().find((app) => appPackage(app) === pkg)
      || null;
  }

  function appSummary(apps, emptyText) {
    const names = (apps || []).map(appDisplayName).filter(Boolean);
    if (!names.length) return emptyText;
    if (names.length <= 3) return names.join("、");
    return names.slice(0, 3).join("、") + " 等 " + names.length + " 个";
  }

  function renderRangeList(ranges) {
    if (!ranges || !ranges.length) return '<div class="empty-state compact"><strong>暂无禁止时段</strong><span>其他时间默认可用。</span></div>';
    return ranges.map((range, index) => [
      '<div class="usage-row rules-list-row">',
      '<div><p class="usage-name">' + escapeHtml(range.startTime || "--:--") + ' - ' + escapeHtml(range.endTime || "--:--") + '</p><p class="request-meta">禁止时段 ' + (index + 1) + '</p></div>',
      '<div class="rules-row-actions"><button class="status-chip permission-chip" type="button" data-blocked-range-edit="' + index + '">编辑</button><button class="status-chip permission-chip" type="button" data-blocked-range-delete="' + index + '">删除</button></div>',
      '</div>'
    ]).join("");
  }

  function renderRules(data) {
    const rules = data.rules || {};
    const pill = root.querySelector(".page-action-pill");
    if (pill) pill.textContent = rules.guardMode && rules.guardMode.enabled ? "守门中" : "未开启";
    const page = root.querySelector('[data-page="rules"]');
    if (!page) return;
    page.querySelectorAll(":scope > section.section").forEach((section) => section.remove());
    const ranges = rules.blockedTimeRanges && Array.isArray(rules.blockedTimeRanges.timeRanges) ? rules.blockedTimeRanges.timeRanges : [];
    const whitelist = rules.whitelist || [];
    const blockedApps = rules.blockedApps || [];
    page.insertAdjacentHTML("beforeend", [
      '<section class="section rules-core-section"><div class="section-title"><h2>今日禁止时段</h2></div>',
      '<article class="rule-card rules-core-card"><div class="row-between"><div><h3 class="request-title">今日禁止时段</h3><p class="request-meta">设置孩子今天哪些时间不能使用手机，其他时间默认可用</p></div><span class="record-chip">' + (ranges.length ? ranges.length + ' 段' : '未设置') + '</span></div>',
      '<div class="rules-summary">' + escapeHtml(blockedRangesText(rules.blockedTimeRanges)) + '</div>',
      '<button class="primary-btn rules-full-btn" type="button" data-open-blocked-ranges>编辑今日禁止时段</button></article></section>',
      '<section class="section rules-core-section"><div class="section-title"><h2>单 App 封锁</h2></div>',
      '<article class="rule-card rules-core-card"><div class="row-between"><div><h3 class="request-title">单 App 封锁</h3><p class="request-meta">只限制指定应用，不影响其他应用正常使用</p></div><span class="record-chip">' + blockedApps.length + ' 个</span></div>',
      '<div class="rules-summary">' + escapeHtml(appSummary(blockedApps, "暂无封锁 App")) + '</div>',
      '<button class="primary-btn rules-full-btn" type="button" data-open-app-block-manager>管理单 App 封锁</button></article></section>',
      '<section class="section rules-core-section"><div class="section-title"><h2>守门白名单</h2></div>',
      '<article class="rule-card rules-core-card"><div class="row-between"><div><h3 class="request-title">守门白名单</h3><p class="request-meta">守门中仍允许使用的联系、学习和工具应用</p></div><span class="record-chip">' + whitelist.length + ' 个</span></div>',
      '<div class="rules-summary">' + escapeHtml(appSummary(whitelist, "暂无白名单 App")) + '</div>',
      '<button class="primary-btn rules-full-btn" type="button" data-open-whitelist-manager>管理白名单</button></article></section>'
    ].join(""));
  }

  function dailyRangesText(rule) {
    if (!rule || !Array.isArray(rule.timeRanges) || !rule.timeRanges.length) return "未设置，保存后会下发到学生端本机执行";
    if (rule.commandStatus === "failed") return "上次下发失败，请确认学生端已安装最新版本后重新保存";
    if (rule.commandStatus === "pending") return "已保存，等待学生端执行";
    return rule.timeRanges.map((item) => item.startTime + "-" + item.endTime).join("、");
  }

  function blockedRangesText(rule) {
    if (!rule || !Array.isArray(rule.timeRanges) || !rule.timeRanges.length) return "未设置，其他时间默认可用";
    if (rule.commandStatus === "failed") return "上次下发失败，请确认学生端已安装最新版本后重新保存";
    if (rule.commandStatus === "pending") return "已保存，等待学生端执行";
    return rule.timeRanges.map((item) => item.startTime + "-" + item.endTime).join("、");
  }

  function renderDailyRangesEditor(rule) {
    const ranges = rule && Array.isArray(rule.timeRanges) && rule.timeRanges.length
      ? rule.timeRanges.slice(0, 2)
      : [{ startTime: "12:00", endTime: "13:00" }, { startTime: "18:30", endTime: "21:30" }];
    while (ranges.length < 2) ranges.push({ startTime: "", endTime: "" });
    return '<div class="section-title"><h2>每日可用时间段</h2></div>' +
      '<div class="request-card">' +
      '<p class="request-meta">' + escapeHtml((rule && rule.message) || "设置今天允许使用手机的时间段。") + '</p>' +
      '<div class="usage-row"><div><p class="usage-name">时段 1</p></div><input class="sheet-input" type="time" data-daily-start value="' + escapeHtml(ranges[0].startTime || "") + '"><input class="sheet-input" type="time" data-daily-end value="' + escapeHtml(ranges[0].endTime || "") + '"></div>' +
      '<div class="usage-row"><div><p class="usage-name">时段 2</p></div><input class="sheet-input" type="time" data-daily-start value="' + escapeHtml(ranges[1].startTime || "") + '"><input class="sheet-input" type="time" data-daily-end value="' + escapeHtml(ranges[1].endTime || "") + '"></div>' +
      '<button class="status-chip strong permission-chip" type="button" data-parent-save-daily-ranges>保存今日时段</button>' +
      '</div>';
  }

  function renderBlockedRangesEditor(rule) {
    const ranges = rule && Array.isArray(rule.timeRanges)
      ? rule.timeRanges.slice(0, 8)
      : [];
    const rows = ranges.map((range, index) => blockedRangeRow(range, index + 1)).join("");
    return '<div class="section-title"><h2>今日禁止使用时间段</h2></div>' +
      '<div class="request-card blocked-ranges-editor">' +
      '<p class="request-meta">' + escapeHtml((rule && rule.message) || "禁止时段内学生端会进入守门，其他时间默认可用。") + '</p>' +
      '<div data-blocked-range-list>' + (rows || '<div class="empty-state compact"><strong>暂无禁止时段</strong><span>添加后会下发到学生端本机执行。</span></div>') + '</div>' +
      '<div class="sheet-actions"><button class="secondary-btn" type="button" data-parent-add-blocked-range>添加禁止时段</button><button class="status-chip strong permission-chip" type="button" data-parent-save-blocked-ranges>保存今日禁止时段</button></div>' +
      '</div>';
  }

  function blockedRangeRow(range, index) {
    return '<div class="usage-row blocked-range-row"><div><p class="usage-name">禁止时段 ' + index + '</p></div><input class="sheet-input" type="time" data-blocked-start value="' + escapeHtml((range && range.startTime) || "") + '"><input class="sheet-input" type="time" data-blocked-end value="' + escapeHtml((range && range.endTime) || "") + '"><button class="status-chip permission-chip" type="button" data-parent-remove-blocked-range>删除</button></div>';
  }

  async function loadRules() {
    const page = root.querySelector('[data-page="rules"]');
    setApiState(page, "loading", "正在读取规则...");
    try {
      const dashboard = parentState.dashboard || await getJson("/api/parent/dashboard");
      parentState.dashboard = dashboard;
      const data = await getJson("/api/parent/devices/" + encodeURIComponent(dashboard.deviceId) + "/rules");
      parentState.rules = data;
      renderRules(data);
      setApiState(page, "normal", "");
    } catch (error) {
      setApiState(page, "error", "规则加载失败：" + error.message);
    }
  }

  async function loadReport() {
    const page = root.querySelector('[data-page="report"]');
    setApiState(page, "loading", "正在读取报告摘要...");
    try {
      const dashboard = await getJson("/api/parent/dashboard");
      const pending = await getJson("/api/parent/approvals/pending");
      const recent = await getJson("/api/parent/approvals/recent");
      const subtitle = root.querySelector(".app-page-head .subtle");
      if (subtitle) subtitle.textContent = "本周用机趋势与审批情况";
      const card = root.querySelector(".insight-card");
      if (card) card.innerHTML = '<span class="status-chip strong">建议</span><p>周末娱乐时间偏集中。建议提前设置两个固定娱乐时段，减少临时审批。</p>';
      setApiState(page, "normal", "");
    } catch (error) {
      setApiState(page, "error", "报告摘要加载失败：" + error.message);
    }
  }

  function renderProfile(data) {
    const profileName = root.querySelector(".profile-card h2, .profile-card strong, .profile-name, .profile-title");
    if (profileName) profileName.textContent = data.parentName || data.guardianName || "家长";
    const meta = root.querySelector(".profile-meta");
    if (meta) meta.textContent = "已绑定 " + ((data.children || []).length || 0) + " 个孩子设备 · 2 位家庭成员";
    const deviceSection = Array.from(root.querySelectorAll(".section")).find((section) => section.textContent.includes("家庭设备"));
    const target = root.querySelector(".family-device-card") || (deviceSection && deviceSection.querySelector(".card"));
    if (target) {
      const children = data.children || [];
      const rows = children.map((child) => '<div class="profile-device-row" data-profile-action="device-detail"><div class="device-outline-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/></svg></div><div><p class="device-title">' + escapeHtml(displayDeviceName(child, child.childName)) + '</p><p class="device-sub">' + escapeHtml(deviceStatusLine(child)) + '</p></div><span class="online-pill">' + escapeHtml(deviceStatusPill(child)) + '</span></div>').join("");
      target.innerHTML = rows + '<div class="profile-device-row" data-profile-action="bind-device"><div class="add-outline-icon" aria-hidden="true">+</div><div><p class="device-title">添加孩子设备</p><p class="device-sub">扫码安装并完成授权</p></div><span class="row-arrow">›</span></div>';
    }
  }

  async function loadProfile() {
    const page = root.querySelector('[data-page="profile"]');
    setApiState(page, "loading", "正在读取我的信息...");
    try {
      const data = await getJson("/api/parent/profile");
      parentState.profile = data;
      renderProfile(data);
      setApiState(page, "normal", "");
    } catch (error) {
      setApiState(page, "error", "我的页面加载失败：" + error.message);
    }
  }

  async function handleApprovalAction(button) {
    const requestId = button.dataset.requestId;
    const action = button.dataset.parentApprovalAction;
    if (!requestId || !action) return;
    button.disabled = true;
    button.textContent = action === "approve" ? "同意中..." : "拒绝中...";
    try {
      await postJson("/api/parent/approvals/" + encodeURIComponent(requestId) + "/" + action);
      toast(action === "approve" ? "已同意申请" : "已拒绝申请");
      await loadApprovals();
    } catch (error) {
      toast("操作失败：" + error.message);
      button.disabled = false;
      button.textContent = action === "approve" ? "同意" : "拒绝";
    }
  }

  async function handleOneKeyLock(button) {
    openLockDurationSheet(button);
  }

  function openCancelLockSheet(sourceButton) {
    const existing = root.querySelector('[data-cancel-lock-sheet]');
    if (existing) existing.remove();
    const sheet = document.createElement('div');
    sheet.className = 'sheet-backdrop show';
    sheet.setAttribute('data-cancel-lock-sheet', 'true');
    sheet.innerHTML = '<div class="bottom-sheet"><div class="sheet-handle"></div><div class="sheet-head"><h3>取消锁定设备？</h3><button class="sheet-close" type="button" data-cancel-lock-close>×</button></div><p class="sheet-copy">取消后，辛斯繁的手机将退出当前守门状态。已设置的白名单和规则不会被删除。</p><div class="sheet-actions"><button class="secondary-btn" type="button" data-cancel-lock-close>继续锁定</button><button class="primary-btn" type="button" data-cancel-lock-confirm>确认取消</button></div></div>';
    root.appendChild(sheet);
    sheet.addEventListener('click', (event) => {
      if (event.target === sheet || event.target.closest('[data-cancel-lock-close]')) sheet.remove();
      const confirm = event.target.closest('[data-cancel-lock-confirm]');
      if (confirm) sendStopGuardMode(sourceButton, sheet, confirm);
    });
  }

  async function sendStopGuardMode(sourceButton, sheet, confirmButton) {
    const dashboard = parentState.dashboard || await getJson('/api/parent/dashboard');
    if (!dashboard.deviceId) {
      toast('没有可控制的学生设备');
      return;
    }
    if (sourceButton) sourceButton.disabled = true;
    if (confirmButton) {
      confirmButton.disabled = true;
      confirmButton.textContent = '正在取消...';
    }
    try {
      const existingCommands = await getJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands');
      const pendingStop = (existingCommands.commands || []).some((command) => {
        const type = command.commandType || command.rawType;
        const status = command.commandStatus || command.rawStatus || command.resultStatus;
        return type === 'stop_guard_mode' && status === 'pending';
      });
      if (pendingStop) {
        parentState.commands = existingCommands;
        renderCommands(existingCommands);
        toast('已有取消锁定命令等待学生端执行，请不要重复点击');
        if (sheet) sheet.remove();
        return;
      }
      await postJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands', {
        commandType: 'stop_guard_mode'
      });
      toast('已发送取消锁定命令');
      if (sheet) sheet.remove();
      const nextDashboard = await getJson('/api/parent/dashboard');
      parentState.dashboard = nextDashboard;
      renderHomeDashboard(nextDashboard);
      const commands = await getJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands');
      renderCommands(commands);
    } catch (error) {
      toast('取消失败：' + error.message);
    } finally {
      if (sourceButton) sourceButton.disabled = false;
      if (confirmButton) confirmButton.disabled = false;
    }
  }

  function todayLocalDate() {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    return year + '-' + month + '-' + day;
  }

  function openLockDurationSheet(sourceButton) {
    const existing = root.querySelector('[data-lock-duration-sheet]');
    if (existing) existing.remove();
    const sheet = document.createElement('div');
    sheet.className = 'sheet-backdrop show';
    sheet.setAttribute('data-lock-duration-sheet', 'true');
    sheet.innerHTML = '<div class="bottom-sheet"><div class="sheet-handle"></div><div class="sheet-head"><h3>选择临时锁定时长</h3><button class="sheet-close" type="button" data-lock-duration-close>×</button></div><p class="sheet-copy">学生端会进入守门模式，到时间后自动恢复。</p><div class="sheet-actions"><button class="primary-btn" type="button" data-lock-duration="15">15 分钟</button><button class="primary-btn" type="button" data-lock-duration="30">30 分钟</button><button class="primary-btn" type="button" data-lock-duration="60">1 小时</button></div><label class="sheet-field"><span>自定义分钟数</span><input inputmode="numeric" pattern="[0-9]*" placeholder="1-1440" data-lock-custom-minutes></label><button class="secondary-btn" type="button" data-lock-duration-custom>按自定义时长锁定</button></div>';
    root.appendChild(sheet);
    sheet.addEventListener('click', (event) => {
      if (event.target === sheet || event.target.closest('[data-lock-duration-close]')) sheet.remove();
      const preset = event.target.closest('[data-lock-duration]');
      if (preset) sendTemporaryLock(Number(preset.dataset.lockDuration), sourceButton, sheet);
      const custom = event.target.closest('[data-lock-duration-custom]');
      if (custom) {
        const input = sheet.querySelector('[data-lock-custom-minutes]');
        sendTemporaryLock(Number(input && input.value), sourceButton, sheet);
      }
    });
  }

  async function sendTemporaryLock(minutes, sourceButton, sheet) {
    if (!Number.isFinite(minutes) || minutes < 1 || minutes > 1440) {
      toast('请输入 1 到 1440 分钟之间的时长');
      return;
    }
    const dashboard = parentState.dashboard || await getJson('/api/parent/dashboard');
    if (!dashboard.deviceId) {
      toast('没有可控制的学生设备');
      return;
    }
    if (sourceButton) sourceButton.disabled = true;
    try {
      const result = await postJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands', {
        commandType: 'temporary_lock',
        durationMinutes: minutes
      });
      toast('已发送临时锁定 ' + minutes + ' 分钟');
      if (sheet) sheet.remove();
      const commands = await getJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands');
      renderCommands(commands);
      const subtitle = sourceButton && sourceButton.querySelector('span');
      if (subtitle) subtitle.textContent = '命令已生成：' + statusText(result.command && result.command.commandStatus);
    } catch (error) {
      toast('发送失败：' + error.message);
    } finally {
      if (sourceButton) sourceButton.disabled = false;
    }
  }

  async function sendRulesCommand(commandType, targetPackage, payloadText) {
    const dashboard = parentState.dashboard || await getJson('/api/parent/dashboard');
    if (!dashboard.deviceId) {
      toast('没有可控制的学生设备');
      return;
    }
    await postJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands', {
      commandType,
      targetPackage,
      payloadText
    });
    toast('已下发' + commandLabel(commandType) + '命令');
    await loadRules();
    const commands = await getJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands');
    renderCommands(commands);
  }

  async function updateParentWhitelist(packageName, allow) {
    const rules = parentState.rules && parentState.rules.rules ? parentState.rules.rules : {};
    const whitelist = (rules.whitelist || []).map(appPackage).filter(Boolean);
    const next = new Set(whitelist);
    if (allow) next.add(packageName); else next.delete(packageName);
    await sendRulesCommand('set_guard_whitelist', null, Array.from(next).join('\n'));
  }

  async function saveDailyTimeRanges() {
    const starts = Array.from(root.querySelectorAll("[data-daily-start]"));
    const ends = Array.from(root.querySelectorAll("[data-daily-end]"));
    const timeRanges = [];
    for (let i = 0; i < starts.length; i += 1) {
      const startTime = (starts[i].value || "").trim();
      const endTime = (ends[i] && ends[i].value || "").trim();
      if (startTime && endTime) timeRanges.push({ startTime, endTime });
    }
    if (!timeRanges.length) {
      toast("请至少填写一个可用时间段");
      return;
    }
    const today = todayLocalDate();
    const dashboard = parentState.dashboard || await getJson('/api/parent/dashboard');
    await postJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands', {
      commandType: 'set_daily_time_ranges',
      date: today,
      timeRanges
    });
    toast("今日可用时间段已保存并下发");
    await loadRules();
    const commands = await getJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands');
    renderCommands(commands);
  }

  function addBlockedRangeRow() {
    const list = root.querySelector("[data-blocked-range-list]");
    if (!list) return;
    const currentRows = list.querySelectorAll(".blocked-range-row").length;
    if (currentRows >= 8) {
      toast("今日禁止时段最多设置 8 段");
      return;
    }
    const empty = list.querySelector(".empty-state");
    if (empty) empty.remove();
    list.insertAdjacentHTML("beforeend", blockedRangeRow({ startTime: "", endTime: "" }, currentRows + 1));
  }

  function refreshBlockedRangeLabels() {
    root.querySelectorAll(".blocked-range-row .usage-name").forEach((node, index) => {
      node.textContent = "禁止时段 " + (index + 1);
    });
    const list = root.querySelector("[data-blocked-range-list]");
    if (list && !list.querySelector(".blocked-range-row")) {
      list.innerHTML = '<div class="empty-state compact"><strong>暂无禁止时段</strong><span>保存后会清空今天的禁止时段。</span></div>';
    }
  }

  async function saveBlockedTimeRanges() {
    const starts = Array.from(root.querySelectorAll("[data-blocked-start]"));
    const ends = Array.from(root.querySelectorAll("[data-blocked-end]"));
    const blockedTimeRanges = [];
    for (let i = 0; i < starts.length; i += 1) {
      const startTime = (starts[i].value || "").trim();
      const endTime = (ends[i] && ends[i].value || "").trim();
      if (startTime && endTime) blockedTimeRanges.push({ startTime, endTime });
    }
    const today = todayLocalDate();
    const dashboard = parentState.dashboard || await getJson('/api/parent/dashboard');
    await postJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands', {
      commandType: 'set_blocked_time_ranges',
      date: today,
      blockedTimeRanges
    });
    toast(blockedTimeRanges.length ? "今日禁止时段已保存并下发" : "今日禁止时段已清空并下发");
    await loadRules();
    const commands = await getJson('/api/parent/devices/' + encodeURIComponent(dashboard.deviceId) + '/commands');
    renderCommands(commands);
  }

  async function ensureRulesLoaded() {
    if (parentState.rules && parentState.rules.rules) return parentState.rules;
    const dashboard = parentState.dashboard || await getJson("/api/parent/dashboard");
    parentState.dashboard = dashboard;
    if (!dashboard.deviceId) throw new Error("没有可控制的学生设备");
    const data = await getJson("/api/parent/devices/" + encodeURIComponent(dashboard.deviceId) + "/rules");
    parentState.rules = data;
    return data;
  }

  function closeRulesSheet() {
    const sheet = root.querySelector("[data-rules-core-sheet]");
    if (sheet) sheet.remove();
  }

  function openRulesSheet(title, bodyHtml, options) {
    closeRulesSheet();
    const sheet = document.createElement("div");
    sheet.className = "sheet-backdrop show rules-core-backdrop";
    sheet.setAttribute("data-rules-core-sheet", "true");
    sheet.innerHTML = [
      '<div class="bottom-sheet rules-core-sheet">',
      '<div class="sheet-handle"></div>',
      '<div class="sheet-head"><h3>' + escapeHtml(title) + '</h3><button class="sheet-close" type="button" data-rules-sheet-close>×</button></div>',
      options && options.copy ? '<p class="sheet-copy">' + escapeHtml(options.copy) + '</p>' : '',
      '<div class="rules-sheet-body">' + bodyHtml + '</div>',
      '</div>'
    ].join("");
    root.appendChild(sheet);
  }

  function currentBlockedDraft() {
    if (!Array.isArray(parentState.blockedDraft)) {
      const rule = currentRules().blockedTimeRanges || {};
      parentState.blockedDraft = Array.isArray(rule.timeRanges) ? rule.timeRanges.map((item) => ({
        startTime: item.startTime,
        endTime: item.endTime
      })) : [];
    }
    return parentState.blockedDraft;
  }

  async function openBlockedRangesManager() {
    await ensureRulesLoaded();
    const draft = currentBlockedDraft();
    openRulesSheet("今日禁止时段", [
      '<div class="rules-panel-list">' + renderRangeList(draft) + '</div>',
      '<div class="sheet-actions stacked"><button class="secondary-btn" type="button" data-blocked-range-new>添加禁止时段</button><button class="secondary-btn" type="button" data-blocked-range-clear>清空今日禁止时段</button><button class="primary-btn" type="button" data-blocked-range-save>保存</button></div>'
    ].join(""), {
      copy: "设置孩子今天哪些时间不能使用手机，其他时间默认可用。"
    });
  }

  function openBlockedRangeEditor(index) {
    const draft = currentBlockedDraft();
    const editing = Number.isFinite(index) && index >= 0;
    const current = editing ? draft[index] || {} : {};
    openRulesSheet(editing ? "编辑禁止时段" : "添加禁止时段", [
      '<label class="sheet-field"><span>开始时间</span><input type="time" data-range-edit-start value="' + escapeHtml(current.startTime || "") + '"></label>',
      '<label class="sheet-field"><span>结束时间</span><input type="time" data-range-edit-end value="' + escapeHtml(current.endTime || "") + '"></label>',
      '<div class="sheet-actions"><button class="secondary-btn" type="button" data-open-blocked-ranges>取消</button><button class="primary-btn" type="button" data-range-edit-confirm="' + (editing ? index : "new") + '">确认添加</button></div>'
    ].join(""), {
      copy: "禁止时段内学生端会进入守门，其他时间默认可用。"
    });
  }

  async function saveBlockedDraft(button) {
    const dashboard = parentState.dashboard || await getJson("/api/parent/dashboard");
    if (!dashboard.deviceId) throw new Error("没有可控制的学生设备");
    const blockedTimeRanges = currentBlockedDraft().filter((item) => item.startTime && item.endTime);
    if (button) {
      button.disabled = true;
      button.textContent = "保存中...";
    }
    await postJson("/api/parent/devices/" + encodeURIComponent(dashboard.deviceId) + "/commands", {
      commandType: "set_blocked_time_ranges",
      date: todayLocalDate(),
      blockedTimeRanges
    });
    toast(blockedTimeRanges.length ? "今日禁止时段已保存" : "今日禁止时段已清空");
    closeRulesSheet();
    parentState.blockedDraft = null;
    await loadRules();
    const commands = await getJson("/api/parent/devices/" + encodeURIComponent(dashboard.deviceId) + "/commands");
    parentState.commands = commands;
    renderCommands(commands);
  }

  function appSearchMatches(app, query) {
    const value = String(query || "").trim().toLowerCase();
    if (!value) return true;
    return [appDisplayName(app), appCategory(app), appPackage(app)].some((text) => String(text || "").toLowerCase().includes(value));
  }

  function openWhitelistManager(query) {
    const apps = rulesApps();
    const current = rulesWhitelist().map(appPackage).filter(Boolean);
    if (!parentState.whitelistDraft) parentState.whitelistDraft = new Set(current);
    parentState.whitelistDraft.add("com.tencent.mm");
    const selected = apps.filter((app) => parentState.whitelistDraft.has(appPackage(app)));
    const candidates = apps.filter((app) => appPackage(app) && appSearchMatches(app, query)).slice(0, 40);
    const selectedRows = selected.length
      ? selected.map((app) => {
          const pkg = appPackage(app);
          const protectedApp = pkg === "com.tencent.mm";
          return '<div class="usage-row rules-list-row"><div class="app-icon">' + appMark(appDisplayName(app)) + '</div><div><p class="usage-name">' + escapeHtml(appDisplayName(app)) + '</p><p class="request-meta">' + escapeHtml(appCategory(app)) + '</p></div><button class="status-chip permission-chip" type="button" data-whitelist-remove="' + escapeHtml(pkg) + '"' + (protectedApp ? ' disabled' : '') + '>' + (protectedApp ? '保留' : '移出') + '</button></div>';
        }).join("")
      : '<div class="empty-state compact"><strong>暂无白名单 App</strong><span>搜索后加入允许使用的应用。</span></div>';
    const resultRows = candidates.length
      ? candidates.map((app) => {
          const pkg = appPackage(app);
          const selectedNow = parentState.whitelistDraft.has(pkg);
          return '<div class="usage-row rules-list-row"><div class="app-icon">' + appMark(appDisplayName(app)) + '</div><div><p class="usage-name">' + escapeHtml(appDisplayName(app)) + '</p><p class="request-meta">' + escapeHtml(appCategory(app)) + '</p></div><button class="status-chip ' + (selectedNow ? '' : 'strong') + ' permission-chip" type="button" data-whitelist-' + (selectedNow ? 'remove' : 'add') + '="' + escapeHtml(pkg) + '">' + (selectedNow ? '已加入' : '加入') + '</button></div>';
        }).join("")
      : '<div class="empty-state compact"><strong>没有匹配应用</strong><span>换个关键词再试。</span></div>';
    openRulesSheet("管理白名单", [
      '<label class="temp-app-search"><svg class="icon" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg><input data-whitelist-search placeholder="搜索应用名称" value="' + escapeHtml(query || "") + '"></label>',
      '<div class="section-title compact-title"><h2>已选白名单</h2></div><div class="rules-panel-list">' + selectedRows + '</div>',
      '<div class="section-title compact-title"><h2>搜索结果</h2></div><div class="rules-panel-list">' + resultRows + '</div>',
      '<button class="primary-btn rules-full-btn" type="button" data-whitelist-save>保存白名单</button>'
    ].join(""), {
      copy: "微信会默认保留，避免误删必要联系应用。"
    });
    const input = root.querySelector("[data-whitelist-search]");
    if (input) {
      input.focus();
      input.setSelectionRange(input.value.length, input.value.length);
    }
  }

  async function saveWhitelistDraft(button) {
    const dashboard = parentState.dashboard || await getJson("/api/parent/dashboard");
    if (!dashboard.deviceId) throw new Error("没有可控制的学生设备");
    const packages = Array.from(parentState.whitelistDraft || []);
    if (!packages.includes("com.tencent.mm")) packages.push("com.tencent.mm");
    if (button) {
      button.disabled = true;
      button.textContent = "保存中...";
    }
    await postJson("/api/parent/devices/" + encodeURIComponent(dashboard.deviceId) + "/commands", {
      commandType: "set_guard_whitelist",
      payloadText: packages.join("\n")
    });
    toast("白名单已保存");
    closeRulesSheet();
    parentState.whitelistDraft = null;
    await loadRules();
    const commands = await getJson("/api/parent/devices/" + encodeURIComponent(dashboard.deviceId) + "/commands");
    parentState.commands = commands;
    renderCommands(commands);
  }

  function openAppBlockManager(query) {
    const whitelistPackages = new Set(rulesWhitelist().map(appPackage).filter(Boolean));
    const blockedPackages = new Set(rulesBlockedApps().map(appPackage).filter(Boolean));
    const apps = rulesApps();
    const blocked = apps.filter((app) => blockedPackages.has(appPackage(app)));
    const candidates = apps
      .filter((app) => appPackage(app) && !whitelistPackages.has(appPackage(app)) && appSearchMatches(app, query))
      .slice(0, 40);
    const blockedRows = blocked.length
      ? blocked.map((app) => '<div class="usage-row rules-list-row"><div class="app-icon">' + appMark(appDisplayName(app)) + '</div><div><p class="usage-name">' + escapeHtml(appDisplayName(app)) + '</p><p class="request-meta">已封锁</p></div><button class="status-chip permission-chip" type="button" data-app-unblock="' + escapeHtml(appPackage(app)) + '">解除封锁</button></div>').join("")
      : '<div class="empty-state compact"><strong>暂无封锁 App</strong><span>添加后只限制选中的应用。</span></div>';
    const resultRows = candidates.length
      ? candidates.map((app) => {
          const pkg = appPackage(app);
          const blockedNow = blockedPackages.has(pkg);
          return '<div class="usage-row rules-list-row"><div class="app-icon">' + appMark(appDisplayName(app)) + '</div><div><p class="usage-name">' + escapeHtml(appDisplayName(app)) + '</p><p class="request-meta">' + (blockedNow ? '已封锁' : '可封锁') + '</p></div><button class="status-chip ' + (blockedNow ? '' : 'strong') + ' permission-chip" type="button" data-app-' + (blockedNow ? 'unblock' : 'block') + '="' + escapeHtml(pkg) + '">' + (blockedNow ? '解除' : '确认封锁') + '</button></div>';
        }).join("")
      : '<div class="empty-state compact"><strong>没有匹配应用</strong><span>白名单应用不会出现在封锁候选里。</span></div>';
    openRulesSheet("单 App 封锁", [
      '<label class="temp-app-search"><svg class="icon" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg><input data-app-block-search placeholder="搜索要封锁的应用" value="' + escapeHtml(query || "") + '"></label>',
      '<div class="section-title compact-title"><h2>已封锁 App</h2></div><div class="rules-panel-list">' + blockedRows + '</div>',
      '<div class="section-title compact-title"><h2>选择要封锁的 App</h2></div><div class="rules-panel-list">' + resultRows + '</div>'
    ].join(""), {
      copy: "本轮只做永久封锁 / 解除，不做按时间段封锁。"
    });
    const input = root.querySelector("[data-app-block-search]");
    if (input) {
      input.focus();
      input.setSelectionRange(input.value.length, input.value.length);
    }
  }

  function initParentApi() {
    const page = currentPage();
    root.addEventListener("click", (event) => {
      const approvalButton = event.target.closest("[data-parent-approval-action]");
      if (approvalButton) {
        event.preventDefault();
        event.stopPropagation();
        handleApprovalAction(approvalButton);
        return;
      }
      const closeSheet = event.target.closest("[data-rules-sheet-close]");
      if (closeSheet || event.target.matches("[data-rules-core-sheet]")) {
        event.preventDefault();
        event.stopPropagation();
        closeRulesSheet();
        return;
      }
      const openBlocked = event.target.closest("[data-open-blocked-ranges]");
      if (openBlocked) {
        event.preventDefault();
        event.stopPropagation();
        ensureRulesLoaded().then(openBlockedRangesManager).catch((error) => toast("打开失败：" + error.message));
        return;
      }
      const rangeNew = event.target.closest("[data-blocked-range-new]");
      if (rangeNew) {
        event.preventDefault();
        event.stopPropagation();
        openBlockedRangeEditor(null);
        return;
      }
      const rangeEdit = event.target.closest("[data-blocked-range-edit]");
      if (rangeEdit) {
        event.preventDefault();
        event.stopPropagation();
        openBlockedRangeEditor(Number(rangeEdit.dataset.blockedRangeEdit));
        return;
      }
      const rangeDelete = event.target.closest("[data-blocked-range-delete]");
      if (rangeDelete) {
        event.preventDefault();
        event.stopPropagation();
        const index = Number(rangeDelete.dataset.blockedRangeDelete);
        currentBlockedDraft().splice(index, 1);
        openBlockedRangesManager().catch((error) => toast("更新失败：" + error.message));
        return;
      }
      const rangeClear = event.target.closest("[data-blocked-range-clear]");
      if (rangeClear) {
        event.preventDefault();
        event.stopPropagation();
        parentState.blockedDraft = [];
        openBlockedRangesManager().catch((error) => toast("更新失败：" + error.message));
        return;
      }
      const rangeConfirm = event.target.closest("[data-range-edit-confirm]");
      if (rangeConfirm) {
        event.preventDefault();
        event.stopPropagation();
        const start = (root.querySelector("[data-range-edit-start]") || {}).value || "";
        const end = (root.querySelector("[data-range-edit-end]") || {}).value || "";
        if (!start || !end) {
          toast("请填写开始和结束时间");
          return;
        }
        const draft = currentBlockedDraft();
        const marker = rangeConfirm.dataset.rangeEditConfirm;
        if (marker === "new") draft.push({ startTime: start, endTime: end });
        else draft[Number(marker)] = { startTime: start, endTime: end };
        openBlockedRangesManager().catch((error) => toast("更新失败：" + error.message));
        return;
      }
      const rangeSave = event.target.closest("[data-blocked-range-save]");
      if (rangeSave) {
        event.preventDefault();
        event.stopPropagation();
        saveBlockedDraft(rangeSave).catch((error) => {
          rangeSave.disabled = false;
          rangeSave.textContent = "保存";
          toast("保存失败：" + error.message);
        });
        return;
      }
      const openWhitelist = event.target.closest("[data-open-whitelist-manager]");
      if (openWhitelist) {
        event.preventDefault();
        event.stopPropagation();
        ensureRulesLoaded().then(() => {
          parentState.whitelistDraft = null;
          openWhitelistManager("");
        }).catch((error) => toast("打开失败：" + error.message));
        return;
      }
      const whitelistAdd = event.target.closest("[data-whitelist-add]");
      if (whitelistAdd) {
        event.preventDefault();
        event.stopPropagation();
        if (!parentState.whitelistDraft) parentState.whitelistDraft = new Set(rulesWhitelist().map(appPackage).filter(Boolean));
        parentState.whitelistDraft.add(whitelistAdd.dataset.whitelistAdd);
        openWhitelistManager((root.querySelector("[data-whitelist-search]") || {}).value || "");
        return;
      }
      const whitelistRemoveNew = event.target.closest("[data-whitelist-remove]");
      if (whitelistRemoveNew) {
        event.preventDefault();
        event.stopPropagation();
        const pkg = whitelistRemoveNew.dataset.whitelistRemove;
        if (pkg === "com.tencent.mm") {
          toast("微信是必要联系应用，当前不能移出白名单");
          return;
        }
        if (!parentState.whitelistDraft) parentState.whitelistDraft = new Set(rulesWhitelist().map(appPackage).filter(Boolean));
        parentState.whitelistDraft.delete(pkg);
        openWhitelistManager((root.querySelector("[data-whitelist-search]") || {}).value || "");
        return;
      }
      const whitelistSave = event.target.closest("[data-whitelist-save]");
      if (whitelistSave) {
        event.preventDefault();
        event.stopPropagation();
        saveWhitelistDraft(whitelistSave).catch((error) => {
          whitelistSave.disabled = false;
          whitelistSave.textContent = "保存白名单";
          toast("保存失败：" + error.message);
        });
        return;
      }
      const openAppBlock = event.target.closest("[data-open-app-block-manager]");
      if (openAppBlock) {
        event.preventDefault();
        event.stopPropagation();
        ensureRulesLoaded().then(() => openAppBlockManager("")).catch((error) => toast("打开失败：" + error.message));
        return;
      }
      const appBlockNew = event.target.closest("[data-app-block]");
      if (appBlockNew) {
        event.preventDefault();
        event.stopPropagation();
        const pkg = appBlockNew.dataset.appBlock;
        appBlockNew.disabled = true;
        appBlockNew.textContent = "下发中...";
        sendRulesCommand("app_block", pkg, null).then(() => closeRulesSheet()).catch((error) => {
          appBlockNew.disabled = false;
          appBlockNew.textContent = "确认封锁";
          toast("封锁失败：" + error.message);
        });
        return;
      }
      const appUnblockNew = event.target.closest("[data-app-unblock]");
      if (appUnblockNew) {
        event.preventDefault();
        event.stopPropagation();
        const pkg = appUnblockNew.dataset.appUnblock;
        appUnblockNew.disabled = true;
        appUnblockNew.textContent = "下发中...";
        sendRulesCommand("app_unblock", pkg, null).then(() => closeRulesSheet()).catch((error) => {
          appUnblockNew.disabled = false;
          appUnblockNew.textContent = "解除封锁";
          toast("解除失败：" + error.message);
        });
        return;
      }
      const legacyWhitelistAdd = event.target.closest("[data-parent-whitelist-add]");
      if (legacyWhitelistAdd) {
        event.preventDefault();
        event.stopPropagation();
        updateParentWhitelist(legacyWhitelistAdd.dataset.parentWhitelistAdd, true).catch((error) => toast("白名单更新失败：" + error.message));
        return;
      }
      const legacyWhitelistRemove = event.target.closest("[data-parent-whitelist-remove]");
      if (legacyWhitelistRemove) {
        event.preventDefault();
        event.stopPropagation();
        updateParentWhitelist(legacyWhitelistRemove.dataset.parentWhitelistRemove, false).catch((error) => toast("白名单更新失败：" + error.message));
        return;
      }
      const appBlock = event.target.closest("[data-parent-app-block]");
      if (appBlock) {
        event.preventDefault();
        event.stopPropagation();
        sendRulesCommand("app_block", appBlock.dataset.parentAppBlock, null).catch((error) => toast("限制应用失败：" + error.message));
        return;
      }
      const appUnblock = event.target.closest("[data-parent-app-unblock]");
      if (appUnblock) {
        event.preventDefault();
        event.stopPropagation();
        sendRulesCommand("app_unblock", appUnblock.dataset.parentAppUnblock, null).catch((error) => toast("解除限制失败：" + error.message));
        return;
      }
      const dailySave = event.target.closest("[data-parent-save-daily-ranges]");
      if (dailySave) {
        event.preventDefault();
        event.stopPropagation();
        saveDailyTimeRanges().catch((error) => toast("保存时间段失败：" + error.message));
        return;
      }
      const blockedAdd = event.target.closest("[data-parent-add-blocked-range]");
      if (blockedAdd) {
        event.preventDefault();
        event.stopPropagation();
        addBlockedRangeRow();
        return;
      }
      const blockedRemove = event.target.closest("[data-parent-remove-blocked-range]");
      if (blockedRemove) {
        event.preventDefault();
        event.stopPropagation();
        const row = blockedRemove.closest(".blocked-range-row");
        if (row) row.remove();
        refreshBlockedRangeLabels();
        return;
      }
      const blockedSave = event.target.closest("[data-parent-save-blocked-ranges]");
      if (blockedSave) {
        event.preventDefault();
        event.stopPropagation();
        saveBlockedTimeRanges().catch((error) => toast("保存禁止时段失败：" + error.message));
        return;
      }
      const cancelLockButton = event.target.closest("[data-parent-cancel-lock]");
      if (cancelLockButton) {
        event.preventDefault();
        event.stopPropagation();
        openCancelLockSheet(cancelLockButton);
        return;
      }
      const commandButton = event.target.closest("[data-parent-command]");
      if (commandButton) {
        event.preventDefault();
        event.stopPropagation();
        handleOneKeyLock(commandButton);
        return;
      }
      const placeholder = event.target.closest('[data-parent-placeholder="add-child"]');
      if (placeholder) {
        event.preventDefault();
        event.stopPropagation();
        openProfileBindingCodeSheet();
      }
    }, true);

    root.addEventListener("input", (event) => {
      const whitelistSearch = event.target.closest("[data-whitelist-search]");
      if (whitelistSearch) {
        openWhitelistManager(whitelistSearch.value || "");
        return;
      }
      const appBlockSearch = event.target.closest("[data-app-block-search]");
      if (appBlockSearch) {
        openAppBlockManager(appBlockSearch.value || "");
      }
    });

    if (page === "home") loadHome();
    if (page === "approval") loadApprovals();
    if (page === "rules") loadRules();
    if (page === "report") loadReport();
    if (page === "profile") loadProfile();
  }

  document.addEventListener("click", (event) => {
    const profileAction = event.target.closest("[data-profile-action]");
    if (profileAction && root.querySelector('[data-page="profile"]')) {
      if (handleProfileAction(profileAction, event)) {
        event.preventDefault();
        event.stopPropagation();
        return;
      }
    }

    const action = event.target.closest("[data-action]");
    if (action) {
      const actionName = action.getAttribute("data-action");
      if (actionName === "toggle-reminder") {
        const topButton = root.querySelector(".reminder-toggle");
        const currentState = topButton ? topButton.dataset.reminderState : "";
        const nextEnabled = currentState ? currentState !== "on" : true;
        setReminder(nextEnabled);
        return;
      }
      if (actionName === "open-home-notifications") {
        openHomeNotifications();
        return;
      }
      if (actionName === "open-home-settings") {
        openHomeSettingsSheet();
        return;
      }
      if (actionName === "toggle-parent-confirm") {
        const rowSwitch = action.querySelector(".switch");
        if (rowSwitch) {
          const enabled = !rowSwitch.classList.contains("on");
          setSwitchState(rowSwitch, enabled);
          toast(enabled ? "二次确认已开启" : "二次确认已关闭");
        }
        return;
      }
      if (actionName === "set-confirm-password") {
        const sheet = ensurePasswordSheet();
        sheet.classList.add("show");
        setTimeout(() => {
          const input = sheet.querySelector("[data-password-input]");
          if (input) input.focus();
        }, 80);
        return;
      }
    }

    if (event.target.closest("[data-close-settings]")) {
      closeSettings();
      return;
    }

    const settingsRow = event.target.closest(".settings-row");
    if (settingsRow) {
      const rowSwitch = settingsRow.querySelector(".switch");
      if (rowSwitch) {
        const enabled = !rowSwitch.classList.contains("on");
        setSwitchState(rowSwitch, enabled);
        toast(enabled ? "已开启" : "已关闭");
      } else {
        toast(settingsRow.textContent.trim().replace(/\s+/g, " · "));
      }
      return;
    }

    const switcher = event.target.closest(".switch");
    if (switcher) {
      const enabled = !switcher.classList.contains("on");
      setSwitchState(switcher, enabled);
      toast(enabled ? "规则已开启" : "规则已关闭");
      return;
    }

    const themeOption = event.target.closest(".theme-option");
    if (themeOption) {
      applyTheme(themeOption.dataset.theme || "blue");
      return;
    }

    const actionButton = event.target.closest(".btn");
    if (actionButton && actionButton.closest(".request-card")) {
      const card = actionButton.closest(".request-card");
      const approved = actionButton.classList.contains("btn-approve");
      card.classList.add("is-resolving");
      setTimeout(() => {
        const list = card.parentElement;
        card.remove();
        toast(approved ? "已同意这条请求" : "已拒绝这条请求");
        if (list && !list.querySelector(".request-card")) {
          const empty = document.createElement("div");
          empty.className = "empty-state";
          empty.innerHTML = "<strong>当前没有待处理请求</strong><span>新的申请会在这里出现。</span>";
          list.appendChild(empty);
        }
      }, 180);
      return;
    }

    const quick = event.target.closest(".quick-card");
    if (quick) {
      const label = quick.textContent.replace(/\s+/g, "");
      if (label.includes("一键锁定")) {
        ensureSheet().classList.add("show");
      } else if (label.includes("学习模式")) {
        toast("学习模式已准备，可在规则页调整");
      } else if (label.includes("报告")) {
        window.location.href = "report.html?theme=" + encodeURIComponent(document.documentElement.dataset.theme || "blue");
      } else if (label.includes("今日禁止时段")) {
        ensureRulesLoaded().then(openBlockedRangesManager).catch((error) => toast("打开失败：" + error.message));
      }
      return;
    }

    const row = event.target.closest(".usage-row, .menu-row");
    if (row) {
      toast("已选中：" + row.textContent.trim().replace(/\s+/g, " · "));
      return;
    }

    if (event.target.closest(".icon-btn")) {
      toast("暂无新的系统提醒");
    }
  });

  function initHomeHeroRing() {
    const shell = root.querySelector('[data-page="home"] .hero-ring-shell');
    const ring = root.querySelector('[data-page="home"] .hero-ring');
    const value = root.querySelector('[data-page="home"] .hero-ring-core strong');
    if (!shell || !ring || !value) return;

    const targetProgress = 68;
    const targetMinutes = 130;
    const duration = 1350;
    const prefersReduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let lastPointer = null;
    let lastParticleAt = -1;

    function getRingState(progress) {
      if (progress > 90) return "alert";
      if (progress >= 70) return "medium";
      return "normal";
    }

    shell.dataset.ringState = getRingState(targetProgress);

    function formatMinutes(totalMinutes) {
      const minutes = Math.max(0, Math.round(totalMinutes));
      const hours = Math.floor(minutes / 60);
      const remainder = String(minutes % 60).padStart(2, "0");
      return hours + "h" + remainder;
    }

    function cubicBezier(x1, y1, x2, y2, x) {
      function sampleCurve(a1, a2, t) {
        const inv = 1 - t;
        return 3 * a1 * inv * inv * t + 3 * a2 * inv * t * t + t * t * t;
      }
      function sampleDerivative(a1, a2, t) {
        return 3 * a1 * (1 - t) * (1 - t) + 6 * (a2 - a1) * (1 - t) * t + 3 * (1 - a2) * t * t;
      }

      let t = x;
      for (let i = 0; i < 6; i += 1) {
        const currentX = sampleCurve(x1, x2, t) - x;
        const derivative = sampleDerivative(x1, x2, t);
        if (Math.abs(currentX) < 0.0001 || derivative === 0) break;
        t -= currentX / derivative;
      }
      if (t < 0 || t > 1) {
        let lower = 0;
        let upper = 1;
        t = x;
        for (let i = 0; i < 10; i += 1) {
          const currentX = sampleCurve(x1, x2, t);
          if (Math.abs(currentX - x) < 0.0001) break;
          if (x > currentX) lower = t;
          else upper = t;
          t = (upper + lower) / 2;
        }
      }
      return sampleCurve(y1, y2, Math.max(0, Math.min(1, t)));
    }

    function setProgress(progressRatio) {
      const clamped = Math.max(0, Math.min(1, progressRatio));
      const progress = targetProgress * clamped;
      ring.style.setProperty("--ring-progress", progress.toFixed(2) + "%");
      ring.style.setProperty("--ring-mid", (progress * 0.62).toFixed(2) + "%");
      value.textContent = formatMinutes(targetMinutes * clamped);
    }

    function addParticle(progressRatio, variant) {
      if (prefersReduced) return;
      const rect = shell.getBoundingClientRect();
      if (!rect.width || !rect.height) return;

      const particle = document.createElement("span");
      const angle = (-92 + 360 * Math.max(0, Math.min(1, progressRatio))) * Math.PI / 180;
      const radius = Math.min(rect.width, rect.height) * (variant === "number" ? 0.19 : 0.42);
      const centerX = rect.width / 2;
      const centerY = rect.height / 2;
      const x = centerX + Math.cos(angle) * radius;
      const y = centerY + Math.sin(angle) * radius;
      const outward = variant === "number" ? 12 : 20;
      const dx = Math.cos(angle) * outward;
      const dy = Math.sin(angle) * outward;

      particle.className = "ring-particle";
      particle.style.left = x.toFixed(1) + "px";
      particle.style.top = y.toFixed(1) + "px";
      particle.style.setProperty("--particle-dx", dx.toFixed(1) + "px");
      particle.style.setProperty("--particle-dy", dy.toFixed(1) + "px");
      particle.style.setProperty("--particle-size", (variant === "number" ? 3 : 4).toString() + "px");
      shell.appendChild(particle);
      window.setTimeout(() => particle.remove(), 820);
    }

    function maybeEmitLoadParticles(raw, eased) {
      if (prefersReduced || raw >= 1) return;
      const bucket = Math.floor(raw * 16);
      if (bucket === lastParticleAt) return;
      lastParticleAt = bucket;
      addParticle(eased, "ring");
      if (bucket % 2 === 0) addParticle(eased * 0.92, "number");
    }

    function finish() {
      setProgress(1);
      ring.classList.add("is-ready");
    }

    if (prefersReduced) {
      finish();
    } else {
      setProgress(0);
      const start = performance.now();
      function tick(now) {
        const raw = Math.min(1, (now - start) / duration);
        const eased = cubicBezier(0.25, 1, 0.5, 1, raw);
        setProgress(eased);
        maybeEmitLoadParticles(raw, eased);
        if (raw < 1) {
          requestAnimationFrame(tick);
        } else {
          finish();
        }
      }
      requestAnimationFrame(tick);
    }

    function triggerShieldLatch() {
      ring.classList.remove("shield-surge");
      void ring.offsetWidth;
      ring.classList.add("shield-surge");
      window.setTimeout(() => ring.classList.remove("shield-surge"), 620);
    }

    function addRipple(event) {
      if (prefersReduced) return;
      const rect = shell.getBoundingClientRect();
      const x = event && typeof event.clientX === "number" ? event.clientX - rect.left : rect.width / 2;
      const y = event && typeof event.clientY === "number" ? event.clientY - rect.top : rect.height / 2;
      const ripple = document.createElement("span");
      ripple.className = "hero-ring-ripple";
      ripple.style.left = Math.max(0, Math.min(rect.width, x)).toFixed(1) + "px";
      ripple.style.top = Math.max(0, Math.min(rect.height, y)).toFixed(1) + "px";
      shell.appendChild(ripple);
      window.setTimeout(() => ripple.remove(), 680);
    }

    function release() {
      shell.classList.remove("is-pressing");
      shell.classList.remove("is-releasing");
      void shell.offsetWidth;
      shell.classList.add("is-releasing");
      addRipple(lastPointer);
      window.setTimeout(() => shell.classList.remove("is-releasing"), 480);
    }

    shell.addEventListener("pointerdown", (event) => {
      lastPointer = event;
      shell.classList.add("is-pressing");
      triggerShieldLatch();
    });
    shell.addEventListener("pointerup", (event) => {
      lastPointer = event;
      release();
    });
    shell.addEventListener("pointercancel", (event) => {
      lastPointer = event;
      release();
    });
    shell.addEventListener("pointerleave", () => {
      if (shell.classList.contains("is-pressing")) release();
    });
  }

  window.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll(".trend-bar").forEach((bar) => {
      bar.animate(
        [{ transform: "scaleY(0.35)", opacity: 0.4 }, { transform: "scaleY(1)", opacity: 1 }],
        { duration: 520, easing: "cubic-bezier(.2,.8,.2,1)", fill: "both" }
      );
    });
    initHomeHeroRing();
    initParentApi();
  });

  const urlTheme = readThemeFromUrl();
  applyTheme(urlTheme || readStoredTheme() || document.documentElement.dataset.theme || "blue", {
    persist: Boolean(urlTheme),
    notify: false
  });
})();
