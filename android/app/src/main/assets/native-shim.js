/* App 内的桥接层：把 /api/* 请求转给原生实现。
   在浏览器里（没有 NativeApi）这段完全不生效，页面照常用 HTTP 接口。 */
(() => {
  const bridge = window.NativeApi;
  if (!bridge) return;

  const pending = new Map();
  let seq = 0;

  window.__nativeResolve = (id, payload) => {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    clearTimeout(entry.timer);
    let data;
    try {
      data = JSON.parse(payload);
    } catch (err) {
      data = { ok: false, error: "返回数据格式异常", kind: "error" };
    }
    entry.resolve(data);
  };

  const original = window.fetch ? window.fetch.bind(window) : null;

  window.fetch = (input, options = {}) => {
    const url = typeof input === "string" ? input : (input && input.url) || "";
    if (!url.startsWith("/api/")) {
      return original ? original(input, options) : Promise.reject(new Error("unsupported request"));
    }

    let body = "{}";
    if (options.body) {
      body = typeof options.body === "string" ? options.body : JSON.stringify(options.body);
    }

    return new Promise((resolve) => {
      const id = `r${++seq}`;
      const timer = setTimeout(() => {
        if (!pending.has(id)) return;
        pending.delete(id);
        resolve({ ok: false, error: "请求超时，请检查网络后重试", kind: "network" });
      }, 60000);

      pending.set(id, { resolve, timer });
      try {
        bridge.request(url, body, id);
      } catch (err) {
        pending.delete(id);
        clearTimeout(timer);
        resolve({ ok: false, error: "原生接口调用失败", kind: "error" });
      }
    }).then((data) => new Response(JSON.stringify(data), {
      status: 200,
      headers: { "Content-Type": "application/json; charset=utf-8" },
    }));
  };

  // App 内不需要 Service Worker
  if (navigator.serviceWorker) {
    try {
      navigator.serviceWorker.register = () => Promise.reject(new Error("disabled"));
    } catch (err) {
      /* ignore */
    }
  }
})();
