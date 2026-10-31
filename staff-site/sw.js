// Velo Staff service worker: makes the panel installable and keeps the app shell for a fast start.
// The API (/v1/) is never cached - every request goes straight to the network.
const SHELL = "velo-staff-v1";
const FILES = ["/", "/app.css", "/staff.css", "/app.js", "/vendor/qrcode.min.js", "/logo.png", "/favicon.png", "/fonts/inter-regular.woff2"];

self.addEventListener("install", (e) => {
	e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).catch(() => null).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
	e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
		.then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
	const url = new URL(e.request.url);
	if (e.request.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/v1/")) return;
	e.respondWith(fetch(e.request).then((res) => {
		if (res.ok && FILES.includes(url.pathname)) {
			const copy = res.clone();
			caches.open(SHELL).then((c) => c.put(url.pathname, copy));
		}
		return res;
	}).catch(() => caches.match(url.pathname).then((hit) => hit || caches.match("/"))));
});
