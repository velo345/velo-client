/* Sales (Black Friday): the bar and the sale cards are already in the HTML (built from site.config.json "sale", so
 * nothing shifts when the page loads), and the inline script in <head> picked teaser / live / over before the first
 * paint. This only runs the countdowns, flips to "live" when the sale starts while the page is open, and asks the
 * server's catalog: a sale the server doesn't run (Tebex didn't take the coupon) is hidden rather than promised. */
(() => {
	"use strict";
	const root = document.documentElement;
	const from = Number(root.dataset.saleFrom);
	const until = Number(root.dataset.saleUntil);
	if (!until) return;
	const pad = (n) => String(Math.max(0, n)).padStart(2, "0");
	const clocks = [...document.querySelectorAll("[data-sale-clock]")];
	const tiles = [...document.querySelectorAll("[data-sale-tiles]")].map((box) => [...box.querySelectorAll("b")]);

	const state = (now) => (now >= until ? "sale-over" : now >= from ? "sale-live" : "sale-soon");
	let shown = state(Date.now());

	function tick() {
		const now = Date.now();
		const s = state(now);
		if (s !== shown) {
			root.classList.remove("sale-soon", "sale-live", "sale-over");
			root.classList.add(s);
			shown = s;
		}
		if (s === "sale-over") return;
		const left = Math.max(0, Math.floor(((s === "sale-live" ? until : from) - now) / 1000));
		const d = Math.floor(left / 86400), h = Math.floor(left / 3600) % 24, m = Math.floor(left / 60) % 60, sec = left % 60;
		const text = (s === "sale-live" ? "ENDS IN " : "IN ") + (d ? d + "D " : "") + pad(h) + ":" + pad(m) + ":" + pad(sec);
		for (const c of clocks) c.textContent = text;
		for (const t of tiles) {
			[d, h, m, sec].forEach((v, i) => {
				const b = t[i];
				const next = pad(v);
				if (!b || b.textContent === next) return;
				b.textContent = next;
				// a small drop-in on change (Web Animations: no forced layout)
				if (b.animate) b.animate([{ transform: "translateY(-6px)", opacity: 0.35 }, { transform: "none", opacity: 1 }], { duration: 380, easing: "cubic-bezier(.34,1.56,.64,1)" });
			});
		}
	}
	tick();
	setInterval(tick, 1000);

	// The server has the last word: started, but no sale in the catalog = it isn't running (yet) - promise nothing.
	const check = () => fetch("/v1/store/catalog", { headers: { Accept: "application/json" } })
		.then((r) => (r.ok ? r.json() : null))
		.then((cat) => {
			if (cat && !cat.sale && Date.now() >= from) {
				root.classList.remove("sale-soon", "sale-live");
				root.classList.add("sale-over");
				shown = "sale-over";
			}
		})
		.catch(() => {});
	if ("requestIdleCallback" in window) requestIdleCallback(check, { timeout: 4000 }); else setTimeout(check, 1500);
})();
