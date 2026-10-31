// Velo Panel service worker: makes the panel installable ("Add to Home Screen") and keeps the app shell for a
// faster start. The API is never cached - every /api/ request goes straight to the network.
const SHELL = "velo-panel-v1";
const FILES = ["/", "/app.css", "/app.js", "/logo.png", "/favicon.png", "/fonts/inter-regular.woff2"];

self.addEventListener("install", (e) => {
	e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).catch(() => null).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
	e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
		.then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
	const url = new URL(e.request.url);
	if (e.request.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/api/")) return;
	// Network first (a panel update shows up right away), the cached shell only when offline.
	e.respondWith(fetch(e.request).then((res) => {
		if (res.ok && FILES.includes(url.pathname)) {
			const copy = res.clone();
			caches.open(SHELL).then((c) => c.put(url.pathname, copy));
		}
		return res;
	}).catch(() => caches.match(url.pathname).then((hit) => hit || caches.match("/"))));
});
