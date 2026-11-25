/* 离线外壳缓存：界面随时能打开，课表数据始终走网络 */
const SHELL = "keda-shell-v1";
const ASSETS = [
  "/",
  "/static/styles.css",
  "/static/app.js",
  "/static/icon-192.png",
  "/static/icon-512.png",
  "/manifest.webmanifest",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL).then((cache) => cache.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.pathname.startsWith("/api/")) return;   // 数据不缓存

  // 页面走网络优先，避免更新后仍是旧界面
  if (request.mode === "navigate" || url.pathname === "/") {
    event.respondWith(
      fetch(request).then((res) => {
        const copy = res.clone();
        caches.open(SHELL).then((cache) => cache.put("/", copy)).catch(() => {});
        return res;
      }).catch(() => caches.match("/"))
    );
    return;
  }

  event.respondWith(
    caches.match(request).then((hit) => hit || fetch(request).then((res) => {
      const copy = res.clone();
      caches.open(SHELL).then((cache) => cache.put(request, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match("/")))
  );
});
