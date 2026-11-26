/* 科大课表 · 前端逻辑 */
(() => {
  "use strict";

  const $ = (sel) => document.querySelector(sel);
  const WEEK_CN = ["一", "二", "三", "四", "五", "六", "日"];
  const HUES = [158, 202, 30, 8, 268, 340, 96, 184, 128, 46];

  const state = {
    status: null,
    week: 1,
    data: null,
    tab: "main",
    courses: null,
    scanned: [],
    loading: false,
    scanning: false,
    scannedThrough: 0,
    cid: null,
  };

  /* ---------------- 基础工具 ---------------- */
  async function api(path, options = {}) {
    const res = await fetch(path, {
      headers: { "Content-Type": "application/json" },
      ...options,
    });
    if (!res.ok) throw new Error(`请求失败（${res.status}）`);
    return res.json();
  }

  let toastTimer = null;
  function toast(message) {
    const el = $("#toast");
    el.textContent = message;
    el.hidden = false;
    requestAnimationFrame(() => el.classList.add("show"));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      el.classList.remove("show");
      setTimeout(() => { el.hidden = true; }, 220);
    }, 2400);
  }

  function hashHue(text) {
    let hash = 0;
    for (let i = 0; i < text.length; i += 1) hash = (hash * 31 + text.charCodeAt(i)) % 100000;
    return HUES[hash % HUES.length];
  }

  function colorOf(name) {
    const hue = hashHue(name);
    return {
      bg: `hsl(${hue} 44% 93%)`,
      bar: `hsl(${hue} 42% 48%)`,
      ink: `hsl(${hue} 46% 26%)`,
    };
  }

  function dateParts(value) {
    if (!value) return null;
    const [, m, d] = value.split("-");
    return { m: Number(m), d: Number(d) };
  }

  /* ---------------- 屏幕切换 ---------------- */
  function showLogin(message) {
    $("#screen-login").hidden = false;
    $("#screen-main").hidden = true;
    $("#screen-courses").hidden = true;
    $("#screen-me").hidden = true;
    $("#tabbar").hidden = true;
    if (message) showLoginError(message);
  }

  function showLoginError(message) {
    const el = $("#login-error");
    el.textContent = message;
    el.hidden = false;
  }

  function enterApp() {
    $("#screen-login").hidden = true;
    $("#tabbar").hidden = false;
    switchTab("main");
  }

  function switchTab(tab) {
    state.tab = tab;
    $("#screen-main").hidden = tab !== "main";
    $("#screen-courses").hidden = tab !== "courses";
    $("#screen-me").hidden = tab !== "me";
    document.querySelectorAll(".tab").forEach((el) => {
      el.classList.toggle("is-active", el.dataset.tab === tab);
    });
    if (tab === "courses") loadCourses(!state.courses);
    if (tab === "me") renderMe();
    if (tab === "main" && state.data) requestAnimationFrame(layoutCourses);
  }

  /* ---------------- 课表 ---------------- */
  function setLoading(on, text) {
    state.loading = on;
    const el = $("#grid-loading");
    el.hidden = !on;
    if (text) el.querySelector("p").textContent = text;
    $("#refresh-btn").classList.toggle("spin", on);
  }

  async function loadWeek(week, opts = {}) {
    if (state.loading) return;
    setLoading(true, week ? `正在取第 ${week} 周课表…` : "正在取课表…");
    try {
      const params = [];
      if (week) params.push(`week=${week}`);
      if (opts.force) params.push("refresh=1");
      const query = params.length ? `?${params.join("&")}` : "";
      const data = await api(`/api/curriculum${query}`);
      if (!data.ok) throw Object.assign(new Error(data.error || "取课表失败"), { payload: data });
      state.data = data;
      state.week = data.week;
      renderWeek(data);
      $("#grid-empty").hidden = !data.empty;
      // 这次显示的是本地缓存：后台静默校对一次，有变化再悄悄换上
      if (data.stale) revalidate(week, data);
    } catch (err) {
      const payload = err.payload || {};
      if (payload.kind === "expired") {
        toast("登录状态已过期，请重新登录");
        showLogin("登录状态已过期，请重新登录");
        return;
      }
      toast(err.message || "取课表失败");
    } finally {
      setLoading(false);
    }
    if (opts.after) opts.after();
  }

  /* 静默校对：强制取一次最新课表，画面没被用户换走且内容有变时才重渲染 */
  function revalidate(week, painted) {
    const params = [];
    if (week) params.push(`week=${week}`);
    params.push("refresh=1");
    api(`/api/curriculum?${params.join("&")}`).then((fresh) => {
      if (!fresh || !fresh.ok) {
        if (fresh && fresh.kind === "expired" && state.data === painted) {
          toast("登录状态已过期，请重新登录");
          showLogin("登录状态已过期，请重新登录");
        }
        return;
      }
      if (state.data !== painted) return; // 期间用户已翻周/刷新，作废
      const changed = fresh.week !== painted.week
        || fresh.currentWeek !== painted.currentWeek
        || JSON.stringify(fresh.days) !== JSON.stringify(painted.days);
      state.data = fresh;
      state.week = fresh.week;
      if (changed) {
        renderWeek(fresh);
        $("#grid-empty").hidden = !fresh.empty;
      }
    }).catch(() => { /* 离线就继续用缓存，不打扰 */ });
  }

  function renderWeek(data) {
    const first = dateParts(data.days[0]?.date);
    const last = dateParts(data.days[6]?.date);
    $("#week-title").textContent = `第 ${data.week} 周`;
    $("#week-range").textContent = first && last
      ? `${first.m}月${first.d}日 – ${last.m}月${last.d}日`
      : "日期未知";
    $("#week-tag").hidden = data.week !== data.currentWeek;

    renderWeekTrack(data);
    renderGrid(data);
    renderModeStrip();
    renderFoot(data);
  }

  function renderWeekTrack(data) {
    const total = data.totalWeeks || 20;
    const list = [data.week - 1, data.week, data.week + 1]
      .filter((w) => w >= 1 && w <= total);
    $("#week-track").innerHTML = list.map((w) => {
      const cls = ["week-chip"];
      if (w === data.week) cls.push("is-active");
      if (w === data.currentWeek) cls.push("is-current");
      return `<span class="${cls.join(" ")}" data-w="${w}">${w}</span>`;
    }).join("");
  }

  function renderGrid(data) {
    const head = ['<div class="corner">节</div>'];
    data.days.forEach((day) => {
      const p = dateParts(day.date);
      head.push(
        `<div class="day-head${day.today ? " today" : ""}">
           <b>周${day.weekday}</b><i>${p ? p.d : ""}</i>
         </div>`
      );
    });
    $("#grid-head").innerHTML = head.join("");

    const timeCol = data.sections.map((s, i) => (
      `<div class="time-cell"><b>${i + 1}</b><i>${s.start}</i></div>`
    )).join("");

    const cols = data.days.map((day, index) => {
      const blocks = day.courses.map((course, ci) => {
        const c = colorOf(course.name);
        const room = course.location || "";
        return `<button class="course" data-day="${index}" data-ci="${ci}"
            style="--c-bg:${c.bg};--c-bar:${c.bar};--c-ink:${c.ink};opacity:0"
            data-start="${course.start}" data-end="${course.end}">
            <span class="c-name">${escapeHtml(course.name)}</span>
            ${room ? `<span class="c-room">${escapeHtml(room)}</span>` : ""}
          </button>`;
      }).join("");
      return `<div class="day-col${day.today ? " today" : ""}" data-day="${index}">${blocks}</div>`;
    }).join("");

    $("#grid-body").innerHTML = `<div id="grid-lines"></div><div id="lunch-band"></div><div class="time-col">${timeCol}<div class="lunch-cell">午休</div></div>${cols}`;
    requestAnimationFrame(layoutCourses);
  }

  const LUNCH_AFTER = 4; // 第 4 节之后是午休
  const BREAK_H = 18;    // 午休带高度（px）

  function layoutCourses() {
    const body = $("#grid-body");
    if (!body || !state.data) return;
    const height = body.clientHeight;
    if (!height) return;
    const rowH = (height - BREAK_H) / 12;
    // 第 sec 节（1 起）顶部的 y 坐标：第 5 节起要跳过午休带
    const yOf = (sec) => (sec - 1) * rowH + (sec > LUNCH_AFTER ? BREAK_H : 0);

    body.querySelectorAll(".time-cell").forEach((el, i) => {
      el.style.top = `${yOf(i + 1)}px`;
      el.style.height = `${rowH}px`;
    });
    const lunchCell = body.querySelector(".lunch-cell");
    if (lunchCell) {
      lunchCell.style.top = `${LUNCH_AFTER * rowH}px`;
      lunchCell.style.height = `${BREAK_H}px`;
    }
    const band = body.querySelector("#lunch-band");
    if (band) {
      band.style.top = `${LUNCH_AFTER * rowH}px`;
      band.style.height = `${BREAK_H}px`;
    }
    // 横线按真实行界生成（含午休带上下沿），替代原来均分的渐变线
    const lines = body.querySelector("#grid-lines");
    if (lines) {
      const bounds = [];
      for (let s = 2; s <= 12; s += 1) bounds.push(yOf(s));
      bounds.push(LUNCH_AFTER * rowH + BREAK_H);
      const stops = [];
      bounds.sort((a, b) => a - b).forEach((y) => {
        const yy = Math.round(y);
        stops.push(`transparent ${yy}px`, `rgba(31,42,38,.07) ${yy}px`,
          `rgba(31,42,38,.07) ${yy + 1}px`, `transparent ${yy + 1}px`);
      });
      lines.style.background = `linear-gradient(to bottom, ${stops.join(",")})`;
    }

    const firstCol = body.querySelector(".day-col");
    const colW = firstCol ? firstCol.clientWidth : 48;
    body.querySelectorAll(".course").forEach((el) => {
      const start = Number(el.dataset.start);
      const end = Number(el.dataset.end);
      const top = yOf(start) + 2;
      const h = Math.max(rowH - 4, (yOf(end) + rowH) - yOf(start) - 5);
      el.style.top = `${top}px`;
      el.style.height = `${h}px`;
      el.style.opacity = "1";
      const tight = h < rowH * 2.6;
      el.classList.toggle("is-tight", tight);
      el.classList.toggle("is-tiny", h < rowH * 1.45);
      // 列太窄时，两节以内的块放不下「课程名 + 地点」，只留课程名
      el.classList.toggle("is-narrow", tight && colW < 46);
    });
  }

  function renderFoot(data) {
    const count = data.days.reduce((sum, day) => sum + day.courses.length, 0);
    const names = new Set();
    data.days.forEach((day) => day.courses.forEach((c) => names.add(c.name)));
    const termStart = data.termStart ? `开学 ${data.termStart.replace(/-/g, ".")}` : "";
    $("#grid-foot").textContent = count
      ? `本周 ${count} 次课 · ${names.size} 门课程 · 共 ${data.totalWeeks} 周`
      : `${termStart} · 本学期共 ${data.totalWeeks} 周`;
  }

  function renderModeStrip() {
    const demo = state.status?.demo;
    $("#mode-strip").hidden = !demo;
  }

  function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, (ch) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[ch]));
  }

  function sectionTime(index) {
    return state.data?.sections?.[index - 1]?.start || "";
  }

  function endTime(index) {
    return state.data?.sections?.[index - 1]?.end || "";
  }

  /* ---------------- 课程详情 ---------------- */
  function openSheet(dayIndex, courseIndex) {
    const day = state.data.days[dayIndex];
    const course = day.courses[courseIndex];
    if (!course) return;
    const c = colorOf(course.name);
    $("#sheet-dot").style.background = c.bar;
    $("#sheet-name").textContent = course.name;
    const span = course.start === course.end ? `${course.start}` : `${course.start}-${course.end}`;
    $("#sheet-time").textContent = `周${day.weekday} 第 ${span} 节 · ${sectionTime(course.start)} – ${endTime(course.end)}`;
    $("#sheet-room").textContent = course.location || "未安排教室";
    $("#sheet-teacher").textContent = course.teacher || "—";
    $("#sheet-weeks").textContent = course.weeks || "—";
    $("#sheet-mask").hidden = false;
    $("#sheet").hidden = false;
  }

  function closeSheet() {
    $("#sheet").hidden = true;
    $("#sheet-mask").hidden = true;
  }

  /* ---------------- 课程汇总 ---------------- */
  async function loadCourses(scanAll) {
    if (state.scanning) return;
    if (!scanAll && state.courses) return renderCourses();

    const total = state.data?.totalWeeks || state.status?.totalWeeks || 20;
    const loading = $("#courses-loading");
    const scanBtn = $("#scan-btn");
    loading.hidden = false;
    scanBtn.disabled = true;
    state.scanning = true;
    state.courses = null;

    try {
      let cursor = 1;
      while (cursor <= total) {
        const res = await api(`/api/courses?start=${cursor}&end=${total}&limit=4`);
        if (!res.ok) throw Object.assign(new Error(res.error || "汇总失败"), { payload: res });
        state.courses = res.courses;
        state.scannedThrough = res.scannedThrough;
        state.totalWeeks = res.total;
        renderCourses();
        $("#scan-note").textContent = `正在扫描 ${res.scannedThrough} / ${total} 周…`;
        if (res.done) break;
        cursor = res.next;
      }
      state.scannedThrough = total;
      renderCourses();
    } catch (err) {
      const payload = err.payload || {};
      if (payload.courses) {
        state.courses = payload.courses;
        state.scannedThrough = payload.scannedThrough;
        renderCourses();
      }
      toast(payload.kind === "expired"
        ? "登录状态已过期，请重新登录"
        : (err.message || "汇总课程失败"));
    } finally {
      state.scanning = false;
      loading.hidden = true;
      scanBtn.disabled = false;
    }
  }

  function renderCourses() {
    const list = state.courses || [];
    const total = state.totalWeeks || state.data?.totalWeeks || 20;
    const through = state.scannedThrough || 0;

    $("#courses-sub").textContent = list.length
      ? `本学期共 ${list.length} 门课`
      : "还没有读到课程";

    if (through >= total) {
      $("#scan-note").textContent = `已统计全部 ${total} 周`;
      $("#scan-btn").hidden = true;
    } else {
      $("#scan-note").textContent = through
        ? `已统计 1–${through} 周 / 共 ${total} 周`
        : "尚未统计";
      $("#scan-btn").hidden = false;
    }

    $("#course-list").innerHTML = list.map((course) => {
      const c = colorOf(course.name);
      const slots = course.slots
        .map((s) => `周${s.weekday} ${s.start}${s.end !== s.start ? `-${s.end}` : ""} 节`)
        .join(" · ");
      const teacher = course.teacher || "教师未填";
      const room = course.location ? ` · ${course.location}` : "";
      return `<div class="course-item" style="--c-bg:${c.bg}">
          <span class="dot" style="background:${c.bar}"></span>
          <div class="info">
            <h4>${escapeHtml(course.name)}</h4>
            <p class="meta">${escapeHtml(teacher)}${escapeHtml(room)}</p>
            <p class="meta">${escapeHtml(slots)}</p>
          </div>
          <span class="stat">${course.count} 周</span>
        </div>`;
    }).join("");
  }

  /* ---------------- 我的 ---------------- */
  function renderMe() {
    const s = state.status || {};
    const data = state.data;
    const total = data?.totalWeeks || s.totalWeeks || 20;
    const week = data?.currentWeek || s.currentWeek || 1;
    const isDemo = !!s.demo;
    $("#me-name").textContent = isDemo ? "演示模式" : (s.username || "未登录");
    $("#me-sub").textContent = isDemo
      ? "示例数据 · 登录后可看自己的课表"
      : `${s.campusName || "湖南科技大学"} · 统一身份认证`;
    $("#me-avatar").textContent = isDemo ? "演" : (s.username ? s.username.slice(-2) : "科");
    $("#progress-text").textContent = `${week} / ${total} 周`;
    $("#progress-bar").style.width = `${Math.min(100, Math.round((week / total) * 100))}%`;
    $("#term-start").textContent = data?.termStart ? `开学 ${data.termStart}` : "开学时间未知";
    $("#data-source").textContent = isDemo ? "数据源 · 演示" : "数据源 · 教务系统";
    $("#weeks-value").textContent = total;
    $("#api-host").textContent = (s.campus === "xx" ? "xxfw" : "kdfw") + ".hnust.edu.cn";
  }

  async function updateWeeks(delta) {
    const total = Number($("#weeks-value").textContent) + delta;
    if (total < 1 || total > 30) return;
    try {
      const res = await api("/api/settings", {
        method: "POST",
        body: JSON.stringify({ totalWeeks: total }),
      });
      if (!res.ok) throw new Error(res.error || "保存失败");
      if (state.status) state.status.totalWeeks = res.totalWeeks;
      if (state.data) state.data.totalWeeks = res.totalWeeks;
      renderMe();
      renderWeek(state.data);
      toast(`学期总周数已设为 ${res.totalWeeks} 周`);
    } catch (err) {
      toast(err.message);
    }
  }

  /* ---------------- 登录 ---------------- */
  let campus = "main";

  function setLoginBusy(busy, label) {
    const btn = $("#login-btn");
    btn.disabled = busy;
    btn.textContent = busy ? (label || "登录中…") : "登 录";
  }

  async function doLogin(event) {
    event.preventDefault();
    const username = $("#input-user").value.trim();
    const password = $("#input-pass").value;
    if (!username || !password) {
      showLoginError("请填写学号和密码");
      return;
    }
    $("#login-error").hidden = true;
    setLoginBusy(true);
    try {
      const body = {
        username,
        password,
        campus,
        remember: $("#remember").checked,
        captcha: $("#input-captcha").value.trim() || null,
        cid: state.cid,
      };
      const res = await api("/api/login", { method: "POST", body: JSON.stringify(body) });
      if (!res.ok) {
        if (res.needCaptcha) {
          state.cid = res.cid;
          $("#captcha-row").hidden = false;
          if (res.captchaImage) $("#captcha-img").src = res.captchaImage;
          showLoginError(res.hint || res.error || "请输入验证码");
        } else {
          showLoginError(res.error || "登录失败");
        }
        return;
      }
      state.cid = null;
      $("#captcha-row").hidden = true;
      await refreshStatus();
      enterApp();
      await loadWeek(null);
      toast("登录成功，已加载本学期课表");
    } catch (err) {
      showLoginError(err.message || "网络异常，请稍后再试");
    } finally {
      setLoginBusy(false);
    }
  }

  async function refreshStatus() {
    const res = await api("/api/status");
    state.status = res;
    return res;
  }

  /* ---------------- 事件绑定 ---------------- */
  function bind() {
    $("#login-form").addEventListener("submit", doLogin);
    $("#campus-seg").addEventListener("click", (e) => {
      const btn = e.target.closest(".seg-item");
      if (!btn) return;
      campus = btn.dataset.campus;
      document.querySelectorAll(".seg-item").forEach((el) => {
        el.classList.toggle("is-active", el === btn);
      });
    });

    $("#toggle-pass").addEventListener("click", () => {
      const input = $("#input-pass");
      const show = input.type === "password";
      input.type = show ? "text" : "password";
      $("#toggle-pass").classList.toggle("is-on", show);
    });

    $("#demo-btn").addEventListener("click", async () => {
      try {
        await api("/api/demo", { method: "POST", body: "{}" });
        await refreshStatus();
        enterApp();
        await loadWeek(null);
        toast("已进入演示模式，可随时去登录");
      } catch (err) {
        toast(err.message);
      }
    });

    $("#exit-demo").addEventListener("click", () => {
      showLogin();
      $("#input-user").value = "";
      $("#input-pass").value = "";
    });

    $("#today-btn").addEventListener("click", () => {
      if (!state.data) return;
      if (state.data.week === state.data.currentWeek) {
        toast("已经是本周了");
        return;
      }
      loadWeek(null);
    });
    $("#refresh-btn").addEventListener("click", () => loadWeek(state.week, { force: true }));

    $("#prev-week").addEventListener("click", () => stepWeek(-1));
    $("#next-week").addEventListener("click", () => stepWeek(1));

    $("#week-track").addEventListener("click", (e) => {
      const chip = e.target.closest(".week-chip");
      if (chip) loadWeek(Number(chip.dataset.w));
    });

    $("#grid-body").addEventListener("click", (e) => {
      const block = e.target.closest(".course");
      if (!block) return;
      openSheet(Number(block.dataset.day), Number(block.dataset.ci));
    });

    $("#sheet-mask").addEventListener("click", closeSheet);
    $("#sheet-close").addEventListener("click", closeSheet);

    $("#tabbar").addEventListener("click", (e) => {
      const tab = e.target.closest(".tab");
      if (tab) switchTab(tab.dataset.tab);
    });

    $("#scan-btn").addEventListener("click", () => loadCourses(true));
    $("#scan-note").addEventListener("dblclick", () => loadCourses(true));

    $("#weeks-minus").addEventListener("click", () => updateWeeks(-1));
    $("#weeks-plus").addEventListener("click", () => updateWeeks(1));

    $("#row-refresh").addEventListener("click", async () => {
      state.courses = null;
      await loadWeek(state.week, { force: true });
      toast("课表已刷新");
    });

    // App 内用真实版本号，网页版保留默认值
    try {
      if (window.NativeApi && typeof window.NativeApi.version === "function") {
        const v = window.NativeApi.version();
        if (v) $("#app-ver").textContent = v;
      }
    } catch (err) {
      /* ignore */
    }

    function exportLog() {
      // App 内：交给原生导出（写文件 + 复制 + 唤起分享）
      if (window.NativeApi && typeof window.NativeApi.exportLog === "function") {
        window.NativeApi.exportLog();
        return;
      }
      // 网页版：直接打开文本版日志
      window.open("/api/logs", "_blank");
    }

    $("#row-log").addEventListener("click", exportLog);
    $("#login-log").addEventListener("click", exportLog);

    $("#row-relogin").addEventListener("click", () => {
      showLogin();
      $("#input-pass").value = "";
    });

    $("#row-logout").addEventListener("click", async () => {
      await api("/api/logout", { method: "POST", body: "{}" });
      state.data = null;
      state.courses = null;
      await refreshStatus();
      showLogin();
      $("#input-pass").value = "";
      toast("已退出登录");
    });

    window.addEventListener("resize", () => {
      if (state.tab === "main") requestAnimationFrame(layoutCourses);
    });

    // 左右滑动切周
    let touchX = null;
    let touchY = null;
    const wrap = $("#grid-wrap");
    wrap.addEventListener("touchstart", (e) => {
      touchX = e.touches[0].clientX;
      touchY = e.touches[0].clientY;
    }, { passive: true });
    wrap.addEventListener("touchend", (e) => {
      if (touchX === null) return;
      const dx = e.changedTouches[0].clientX - touchX;
      const dy = e.changedTouches[0].clientY - touchY;
      if (Math.abs(dx) > 62 && Math.abs(dx) > Math.abs(dy) * 1.6) stepWeek(dx > 0 ? -1 : 1);
      touchX = touchY = null;
    }, { passive: true });
  }

  function stepWeek(delta) {
    if (!state.data || state.loading) return;
    const next = state.week + delta;
    const total = state.data.totalWeeks || 20;
    if (next < 1) return toast("已经是第 1 周");
    if (next > total + 2) return toast("已经到学期末了");
    loadWeek(next);
  }

  /* ---------------- 启动 ---------------- */
  async function init() {
    bind();
    try {
      const status = await refreshStatus();
      if (status.loggedIn || status.demo) {
        enterApp();
        await loadWeek(null);
      } else {
        showLogin();
      }
    } catch (err) {
      showLogin("无法连接本地服务，请确认程序仍在运行");
    }

    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/sw.js").catch(() => {});
    }
  }

  init();
})();
