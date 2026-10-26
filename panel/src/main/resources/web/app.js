/* Velo Panel. Plain JS, no dependencies. Every value from the server goes into the page as text (never innerHTML). */
"use strict";

const state = { me: null, csrf: null, timers: [], route: "", catalog: null };
const $app = document.getElementById("app");

// ---- DOM helpers ----

function h(tag, attrs, ...children) {
	const el = document.createElement(tag);
	for (const [k, v] of Object.entries(attrs || {})) {
		if (v === null || v === undefined || v === false) continue;
		if (k === "class") el.className = v;
		// CSSOM, not the style attribute: the panel's CSP (style-src 'self') blocks inline style attributes.
		else if (k === "style") el.style.cssText = v;
		else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
		else if (k === "value") el.value = v;
		else if (v === true) el.setAttribute(k, "");
		else el.setAttribute(k, v);
	}
	for (const c of children.flat(Infinity)) {
		if (c === null || c === undefined || c === false) continue;
		el.append(c instanceof Node ? c : document.createTextNode(String(c)));
	}
	return el;
}

const SVG_NS = "http://www.w3.org/2000/svg";
function s(tag, attrs, ...children) {
	const el = document.createElementNS(SVG_NS, tag);
	for (const [k, v] of Object.entries(attrs || {})) {
		if (v === null || v === undefined) continue;
		if (k === "style") el.style.cssText = v;
		else el.setAttribute(k, v);
	}
	for (const c of children.flat()) if (c) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
	return el;
}

const ICONS = {
	overview: "M3 13h8V3H3zM13 21h8V11h-8zM3 21h8v-6H3zM13 3v6h8V3z",
	players: "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M2 21c0-3.9 3.1-6.5 7-6.5s7 2.6 7 6.5 M16 3.5a4 4 0 0 1 0 7.5 M18.5 14.5c2 .8 3.5 3 3.5 6.5",
	store: "M3 7h18l-1.5 12.5a2 2 0 0 1-2 1.5h-11a2 2 0 0 1-2-1.5z M8 7V5a4 4 0 0 1 8 0v2",
	cosmetics: "M12 3l2.4 5.1 5.6.7-4.1 3.9 1 5.5L12 15.6l-4.9 2.6 1-5.5L4 8.8l5.6-.7z",
	socials: "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z M10 9.5v5l4.5-2.5z",
	tiktok: "M14 3v10.5a3.5 3.5 0 1 1-3.5-3.5 M14 3c.4 2.6 2.1 4.2 5 4.5",
	instagram: "M7 3h10a4 4 0 0 1 4 4v10a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4z M12 8.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z M17.2 6.8h.01",
	youtube: "M3 8.5c0-1.7 1.3-3 3-3h12c1.7 0 3 1.3 3 3v7c0 1.7-1.3 3-3 3H6c-1.7 0-3-1.3-3-3z M10 9.5v5l4.5-2.5z",
	news: "M4 5h13v14H6a2 2 0 0 1-2-2z M17 9h3v8a2 2 0 0 1-2 2 M8 9h5 M8 13h5",
	reports: "M12 9v4 M12 17h.01 M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
	addons: "M12 2 3 7v10l9 5 9-5V7z M3 7l9 5 9-5 M12 12v10",
	server: "M4 4h16v6H4z M4 14h16v6H4z M8 7h.01 M8 17h.01",
	files: "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
	file: "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M14 3v6h6",
	activity: "M3 12h4l3 8 4-16 3 8h4",
	account: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M4 21c0-4 3.6-6 8-6s8 2 8 6",
	close: "M6 6l12 12 M18 6 6 18",
	menu: "M4 6h16 M4 12h16 M4 18h16",
	refresh: "M20 11a8 8 0 1 0-2.3 5.7 M20 4v7h-7",
	download: "M12 4v11 M7 10l5 5 5-5 M5 20h14",
	upload: "M12 20V9 M7 14l5-5 5 5 M5 4h14",
	logout: "M15 4h4v16h-4 M10 8l-4 4 4 4 M6 12h10",
	coins: "M12 3a9 9 0 1 0 .01 0 M9.5 9.5c0-1.4 1.1-2 2.5-2s2.5.8 2.5 2c0 2.5-5 2-5 4.5 0 1.2 1.1 2 2.5 2s2.5-.6 2.5-2",
	online: "M2 12h3l2-5 4 10 3-7 2 2h6",
	plus: "M12 5v14 M5 12h14",
	trend: "M4 17l5-5 4 4 7-8 M15 8h5v5",
	cpu: "M9 9h6v6H9z M4 9h2 M4 15h2 M18 9h2 M18 15h2 M9 4v2 M15 4v2 M9 18v2 M15 18v2 M6 6h12v12H6z",
	memory: "M3 8h18v8H3z M7 8v8 M11 8v8 M15 8v8 M3 19h18",
	disk: "M12 3c4.4 0 8 1.3 8 3v12c0 1.7-3.6 3-8 3s-8-1.3-8-3V6c0-1.7 3.6-3 8-3z M4 6c0 1.7 3.6 3 8 3s8-1.3 8-3",
	monitor: "M3 4h18v12H3z M8 20h8 M12 16v4 M6 12l3-3 2 2 4-4 3 3",
	clips: "M4 6h12v12H4z M16 10l5-3v10l-5-3",
	worlds: "M12 3a9 9 0 1 0 .01 0 M3 12h18 M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9 M12 3C9.5 5.6 8.2 8.6 8.2 12s1.3 6.4 3.8 9",
	capes: "M7 3h10l2 18-7-4-7 4z",
	updates: "M20 11a8 8 0 1 0-2.3 5.7 M20 4v7h-7 M12 8v5l3 2",
	partners: "M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M2 20c0-3 2.7-5 6-5s6 2 6 5 M14 15.3c.6-.2 1.3-.3 2-.3 3.3 0 6 2 6 5",
	stafflog: "M5 3h11l3 3v15H5z M9 9h6 M9 13h6 M9 17h4",
	play: "M7 4l13 8-13 8z",
	trash: "M4 7h16 M9 7V4h6v3 M6 7l1 13h10l1-13",
};

function icon(name) {
	return s("svg", { viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "stroke-width": "1.8", "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true" },
		s("path", { d: ICONS[name] || ICONS.file }));
}

// ---- Formatting ----

const nf = new Intl.NumberFormat("en-US");
const fmt = (n) => nf.format(Math.round(n || 0));
const compact = (n) => (Math.abs(n) >= 1000 ? new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 }).format(n) : fmt(n));
const euro = (n) => new Intl.NumberFormat("de-DE", { style: "currency", currency: "EUR" }).format(n || 0);
function bytes(n) {
	if (!n) return "0 B";
	const u = ["B", "KB", "MB", "GB", "TB"];
	const i = Math.min(u.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
	return (n / 1024 ** i).toFixed(i ? 1 : 0) + " " + u[i];
}
const rate = (n) => bytes(n) + "/s";
function when(ms) {
	if (!ms) return "–";
	const d = new Date(ms);
	return d.toLocaleString(undefined, { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}
function ago(ms) {
	if (!ms) return "never";
	const s = Math.max(0, (Date.now() - ms) / 1000);
	if (s < 60) return "just now";
	if (s < 3600) return Math.floor(s / 60) + " min ago";
	if (s < 86400) return Math.floor(s / 3600) + " h ago";
	return Math.floor(s / 86400) + " d ago";
}
function duration(sec) {
	if (sec < 60) return Math.round(sec) + "s";
	const d = Math.floor(sec / 86400), hr = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
	return (d ? d + "d " : "") + (hr || d ? hr + "h " : "") + m + "m";
}
const dayLabel = (iso) => new Date(iso + "T12:00:00Z").toLocaleDateString(undefined, { day: "numeric", month: "short" });

/** Counts a number up to its value (an easing tween), for the KPI tiles. */
function countUp(el, value, format) {
	const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
	const from = Number(el.dataset.v || 0);
	el.dataset.v = value;
	if (reduce || from === value) {
		el.textContent = format(value);
		return;
	}
	const start = performance.now();
	const step = (t) => {
		const p = Math.min(1, (t - start) / 900);
		const e = 1 - Math.pow(1 - p, 3);
		el.textContent = format(from + (value - from) * e);
		if (p < 1) requestAnimationFrame(step);
	};
	requestAnimationFrame(step);
}

// ---- API ----

class ApiError extends Error {
	constructor(status, message) {
		super(message);
		this.status = status;
	}
}

async function api(path, opts = {}) {
	const headers = { ...(opts.headers || {}) };
	let body = opts.body;
	if (body !== undefined && !(body instanceof Blob) && !(body instanceof ArrayBuffer) && typeof body !== "string") {
		body = JSON.stringify(body);
		headers["Content-Type"] = "application/json";
	}
	const method = opts.method || (body !== undefined ? "POST" : "GET");
	if (method !== "GET" && state.csrf) headers["X-CSRF-Token"] = state.csrf;
	const res = await fetch(path, { method, headers, body, credentials: "same-origin", cache: "no-store" });
	if (res.status === 401 && path !== "/api/login") {
		state.me = null;
		renderLogin("You were signed out - sign in again.");
		throw new ApiError(401, "Signed out");
	}
	const type = res.headers.get("Content-Type") || "";
	const data = type.includes("json") ? await res.json().catch(() => ({})) : await res.text();
	if (!res.ok) throw new ApiError(res.status, (data && data.error) || "HTTP " + res.status);
	return data;
}
const velo = (path, opts) => api("/api/velo/" + path, opts);

function toast(message, kind = "ok") {
	const el = h("div", { class: "toast " + kind }, h("i"), h("div", {}, message));
	document.getElementById("toasts").append(el);
	setTimeout(() => el.remove(), kind === "err" ? 7000 : 3800);
}

async function busy(button, fn) {
	const old = [...button.childNodes];
	button.disabled = true;
	button.replaceChildren(h("span", { class: "spin" }), "Working…");
	try {
		return await fn();
	} catch (e) {
		if (e.status !== 401) toast(e.message, "err");
		throw e;
	} finally {
		button.disabled = false;
		button.replaceChildren(...old);
	}
}

function every(ms, fn) {
	const id = setInterval(() => {
		if (!document.hidden) fn();
	}, ms);
	state.timers.push(id);
}

// ---- Dialogs ----

function dialog(title, body, actions, wide) {
	const close = h("button", { class: "icon-btn", "aria-label": "Close", type: "button" }, icon("close"));
	const dlg = h("dialog", { class: wide ? "wide" : "" },
		h("div", { class: "dlg-head" }, h("h2", {}, title), close),
		h("div", { class: "dlg-body" }, body),
		actions && actions.length ? h("div", { class: "dlg-foot" }, actions) : null);
	close.addEventListener("click", () => dlg.close());
	dlg.addEventListener("close", () => dlg.remove());
	document.body.append(dlg);
	dlg.showModal();
	return dlg;
}

function confirmBox(title, text, okLabel = "Confirm", danger = true) {
	return new Promise((resolve) => {
		const ok = h("button", { class: "btn " + (danger ? "danger" : "primary"), type: "button" }, okLabel);
		const cancel = h("button", { class: "btn ghost", type: "button" }, "Cancel");
		const dlg = dialog(title, h("p", { style: "margin:0;color:var(--text-2)" }, text), [cancel, ok]);
		let answer = false;
		ok.addEventListener("click", () => { answer = true; dlg.close(); });
		cancel.addEventListener("click", () => dlg.close());
		dlg.addEventListener("close", () => resolve(answer));
	});
}

// ---- Charts (SVG, drawn in, crosshair + tooltip) ----

function niceMax(v) {
	if (v <= 0) return 1;
	const p = Math.pow(10, Math.floor(Math.log10(v)));
	for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
	return 10 * p;
}

/** Renders {@code draw(width, animate)} at the container's real width, again when it's resized (crisp text at any size). */
function responsive(wrap, draw) {
	let last = 0;
	let first = true;
	const run = () => {
		const w = Math.round(wrap.clientWidth);
		if (!w || Math.abs(w - last) < 4) return;
		last = w;
		draw(Math.max(280, w), first);
		first = false;
	};
	new ResizeObserver(run).observe(wrap);
	requestAnimationFrame(run);
	// Live data: draws again at the same size, without the entry animation.
	wrap.redraw = () => { if (last) draw(Math.max(280, last), false); };
	return wrap;
}

/** Integer counts get a top of at least 4, so the five gridlines never repeat a label. */
const yTop = (max, format) => niceMax(format === fmt || format === compact ? Math.max(4, max) : max);

/**
 * A line chart: series [{name, color, points: [[x, y], ...]}] sharing the x values. One y axis, gridlines in
 * a recessive gray, 2px lines, an area wash for single-series charts, a crosshair tooltip on hover.
 */
function lineChart({ series, height = 220, yFormat = fmt, xFormat = (x) => when(x), area = series.length === 1, yMax }) {
	const wrap = h("div", { class: "chart" });
	/** New points for the same series (and optionally a new top) - redrawn in place. */
	wrap.update = (next) => {
		series = next.series || series;
		if (next.yMax !== undefined) yMax = next.yMax;
		wrap.redraw();
	};
	return responsive(wrap, (W, animate) => {
	const all = series.flatMap((s) => s.points);
	if (!all.length) {
		wrap.replaceChildren(h("div", { class: "empty" }, "No data yet - it fills in as players come online."));
		return;
	}
	const H = height, L = 52, R = 12, T = 12, B = 26;
	const xs = series[0].points.map((p) => p[0]);
	const x0 = Math.min(...xs), x1 = Math.max(...xs);
	const max = yMax || yTop(Math.max(1, ...all.map((p) => p[1])), yFormat);
	const X = (x) => L + (x1 === x0 ? (W - L - R) / 2 : ((x - x0) / (x1 - x0)) * (W - L - R));
	const Y = (y) => T + (1 - y / max) * (H - T - B);
	const svg = s("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": series.map((s) => s.name).join(", ") + " chart" });
	for (let i = 0; i <= 4; i++) {
		const v = (max / 4) * i;
		svg.append(s("line", { class: "grid-line", x1: L, x2: W - R, y1: Y(v), y2: Y(v) }),
			s("text", { class: "axis", x: L - 8, y: Y(v) + 4, "text-anchor": "end" }, yFormat(v)));
	}
	const ticks = Math.min(Math.max(2, Math.floor(W / 130)), xs.length);
	for (let i = 0; i < ticks; i++) {
		const idx = Math.round((i / Math.max(1, ticks - 1)) * (xs.length - 1));
		svg.append(s("text", { class: "axis", x: X(xs[idx]), y: H - 6, "text-anchor": i === 0 ? "start" : i === ticks - 1 ? "end" : "middle" }, xFormat(xs[idx])));
	}
	series.forEach((ser, si) => {
		const d = ser.points.map((p, i) => (i ? "L" : "M") + X(p[0]).toFixed(1) + " " + Y(p[1]).toFixed(1)).join(" ");
		if (area) {
			const gid = "g" + Math.random().toString(36).slice(2);
			svg.append(s("defs", {}, s("linearGradient", { id: gid, x1: 0, y1: 0, x2: 0, y2: 1 },
				s("stop", { offset: "0%", "stop-color": ser.color, "stop-opacity": ".28" }), s("stop", { offset: "100%", "stop-color": ser.color, "stop-opacity": "0" }))));
			svg.append(s("path", { class: animate ? "area" : "", d: d + ` L${X(x1)} ${Y(0)} L${X(x0)} ${Y(0)} Z`, fill: `url(#${gid})` }));
		}
		const path = s("path", { class: "line" + (animate ? " draw" : ""), d, stroke: ser.color, style: `animation-delay:${si * 120}ms` });
		if (si === 0) path.style.filter = "drop-shadow(0 0 6px rgba(240, 53, 61, .45))";
		svg.append(path);
		requestAnimationFrame(() => path.style.setProperty("--len", Math.ceil(path.getTotalLength ? path.getTotalLength() : 2000)));
		const last = ser.points[ser.points.length - 1];
		svg.append(s("circle", { class: "dot", cx: X(last[0]), cy: Y(last[1]), r: 4, fill: ser.color }));
	});
	const cursor = s("line", { class: "cursor", y1: T, y2: H - B, x1: 0, x2: 0, visibility: "hidden" });
	const dots = series.map((ser) => s("circle", { class: "dot", r: 4, fill: ser.color, cx: 0, cy: 0, visibility: "hidden" }));
	const hit = s("rect", { class: "hit", x: L, y: 0, width: W - L - R, height: H });
	svg.append(cursor, ...dots, hit);
	const tip = h("div", { class: "tip", hidden: true });
	wrap.replaceChildren(svg, tip);
	const show = (v) => [cursor, ...dots].forEach((el) => el.setAttribute("visibility", v));
	const move = (ev) => {
		show("visible");
		const r = svg.getBoundingClientRect();
		const px = ((ev.clientX - r.left) / r.width) * W;
		let best = 0;
		xs.forEach((x, i) => { if (Math.abs(X(x) - px) < Math.abs(X(xs[best]) - px)) best = i; });
		const cx = X(xs[best]);
		cursor.setAttribute("x1", cx);
		cursor.setAttribute("x2", cx);
		series.forEach((ser, i) => { dots[i].setAttribute("cx", cx); dots[i].setAttribute("cy", Y(ser.points[best][1])); });
		tip.replaceChildren(h("b", {}, xFormat(xs[best])), ...series.map((ser) =>
			h("span", {}, h("em", {}, h("i", { class: "swatch", style: "background:" + ser.color }), ser.name), h("strong", {}, yFormat(ser.points[best][1])))));
		tip.hidden = false;
		tip.style.left = (cx / W) * r.width + "px";
		tip.style.top = (Math.min(...series.map((ser) => Y(ser.points[best][1]))) / H) * r.height + "px";
	};
	hit.addEventListener("pointermove", move);
	hit.addEventListener("pointerleave", () => { tip.hidden = true; show("hidden"); });
	});
}

/** Columns from a baseline: [{label, value}], one series, value on hover. */
function barChart({ data, color = "var(--series-1)", height = 200, yFormat = fmt, name = "" }) {
	const wrap = h("div", { class: "chart" });
	wrap.update = (next) => {
		data = next.data || data;
		wrap.redraw();
	};
	return responsive(wrap, (W, animate) => {
	if (!data.length) {
		wrap.replaceChildren(h("div", { class: "empty" }, "No data yet."));
		return;
	}
	const H = height, L = 52, R = 8, T = 10, B = 26;
	const max = yTop(Math.max(1, ...data.map((d) => d.value)), yFormat);
	const band = (W - L - R) / data.length;
	const bw = Math.min(24, Math.max(3, band - 2));
	const Y = (y) => T + (1 - y / max) * (H - T - B);
	const svg = s("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": name + " chart" });
	for (let i = 0; i <= 4; i++) {
		const v = (max / 4) * i;
		svg.append(s("line", { class: "grid-line", x1: L, x2: W - R, y1: Y(v), y2: Y(v) }), s("text", { class: "axis", x: L - 8, y: Y(v) + 4, "text-anchor": "end" }, yFormat(v)));
	}
	const tip = h("div", { class: "tip", hidden: true });
	const labelEvery = Math.ceil(data.length / Math.max(2, Math.floor(W / 90)));
	data.forEach((d, i) => {
		const x = L + band * i + (band - bw) / 2;
		const y = Y(d.value);
		const hgt = Math.max(0, Y(0) - y);
		const r = Math.min(4, bw / 2, hgt);
		const path = hgt <= 0 ? null : s("path", { class: animate ? "bar" : "", fill: color, style: `animation-delay:${Math.min(i * 18, 600)}ms`,
			d: `M${x} ${Y(0)} V${y + r} Q${x} ${y} ${x + r} ${y} H${x + bw - r} Q${x + bw} ${y} ${x + bw} ${y + r} V${Y(0)} Z` });
		if (path) svg.append(path);
		if (i % labelEvery === 0) svg.append(s("text", { class: "axis", x: x + bw / 2, y: H - 6, "text-anchor": "middle" }, d.label));
		const hit = s("rect", { class: "hit", x: L + band * i, y: T, width: band, height: H - T - B });
		hit.addEventListener("pointerenter", () => {
			tip.replaceChildren(h("b", {}, d.label), h("span", {}, h("em", {}, h("i", { class: "swatch", style: "background:" + color }), name), h("strong", {}, yFormat(d.value))));
			tip.hidden = false;
			const box = svg.getBoundingClientRect();
			tip.style.left = ((x + bw / 2) / W) * box.width + "px";
			tip.style.top = (y / H) * box.height + "px";
			if (path) path.style.filter = "brightness(1.25) drop-shadow(0 0 8px rgba(240,53,61,.6))";
		});
		hit.addEventListener("pointerleave", () => { tip.hidden = true; if (path) path.style.filter = ""; });
		svg.append(hit);
	});
	wrap.replaceChildren(svg, tip);
	});
}

function legend(items) {
	return h("div", { class: "legend" }, items.map(([name, color]) => h("span", {}, h("i", { class: "swatch", style: "background:" + color }), name)));
}

/** A usage bar; {@code .set(used, total, text)} moves it smoothly instead of rebuilding it. */
function meter(label, used, total, format = bytes, text) {
	const value = h("b");
	const fill = h("i");
	const bar = h("div", { class: "meter", role: "meter", "aria-valuemin": 0, "aria-valuemax": 100, "aria-label": label }, fill);
	const row = h("div", { class: "meter-row" }, h("div", {}, h("span", {}, label), value), bar);
	row.set = (u, t, txt) => {
		const pct = t ? Math.min(100, (u / t) * 100) : 0;
		value.textContent = txt || format(u) + " / " + format(t) + " · " + pct.toFixed(0) + "%";
		bar.className = "meter" + (pct > 90 ? " bad" : pct > 75 ? " warn" : "");
		bar.setAttribute("aria-valuenow", pct.toFixed(0));
		fill.style.width = pct + "%";
	};
	row.set(used, total, text);
	return row;
}

/** A stat tile; {@code .set(value, sub)} counts to the new value in place. */
function kpi(label, iconName, value, format, sub, hero) {
	const v = h("div", { class: "value" }, "0");
	const subEl = h("div", { class: "sub" }, sub || "");
	countUp(v, value, format);
	const el = h("div", { class: "card kpi" + (hero ? " hero" : "") }, h("div", { class: "label" }, icon(iconName), label), v, subEl);
	el.set = (next, nextSub) => {
		if (Number(v.dataset.v) !== next) countUp(v, next, format);
		if (nextSub !== undefined && subEl.textContent !== nextSub) subEl.textContent = nextSub;
	};
	return el;
}

function card(title, sub, right, ...body) {
	return h("section", { class: "card" }, h("div", { class: "card-head" }, h("div", {}, h("h2", {}, title), sub ? h("p", {}, sub) : null), right || null), body);
}

function seg(options, current, onPick) {
	const el = h("div", { class: "seg", role: "group" });
	for (const [value, label] of options) {
		const b = h("button", { type: "button", "aria-pressed": String(value === current) }, label);
		b.addEventListener("click", () => {
			el.querySelectorAll("button").forEach((x) => x.setAttribute("aria-pressed", "false"));
			b.setAttribute("aria-pressed", "true");
			onPick(value);
		});
		el.append(b);
	}
	return el;
}

function table(headers, rows, onRow) {
	return h("div", { class: "table-wrap" }, h("table", {},
		h("thead", {}, h("tr", {}, headers.map((x) => h("th", { class: /^#|num:/.test(x) ? "num" : "" }, x.replace(/^num:/, ""))))),
		h("tbody", {}, rows.length ? rows.map((r, i) => {
			const tr = h("tr", { class: onRow ? "clickable" : "", tabindex: onRow ? "0" : null }, r.map((c, j) => h("td", { class: /^num:/.test(headers[j]) ? "num" : "" }, c)));
			if (onRow) {
				tr.addEventListener("click", () => onRow(i));
				tr.addEventListener("keydown", (e) => { if (e.key === "Enter") onRow(i); });
			}
			return tr;
		}) : h("tr", {}, h("td", { colspan: headers.length, class: "muted" }, "Nothing here yet")))));
}

const kindPill = (kind) => h("span", { class: "pill " + (kind === "game" ? "red" : kind === "launcher" ? "blue" : "aqua") }, kind === "game" ? "In game" : kind === "launcher" ? "Launcher" : "Website");
const initials = (name) => h("span", { class: "avatar", style: "width:28px;height:28px;font-size:12px;border-radius:8px", "aria-hidden": "true" }, (name || "?").slice(0, 1).toUpperCase());
/** The player's face from their skin (the Velo server fetches and caches it); their initial if that fails. */
const playerHead = (uuid, name, size = 28) => /^[0-9a-f]{32}$/.test(uuid || "")
	? h("img", { class: "head", src: `/api/velo/skins/${uuid}/head.png`, alt: "", width: size, height: size, style: `width:${size}px;height:${size}px`, loading: "lazy",
		onerror: (e) => e.target.replaceWith(initials(name)) })
	: initials(name);

function errorBox(e) {
	return h("div", { class: "error-box" }, e.message || String(e));
}

// ---- Sign-in ----

/** Solves the server's proof-of-work challenge (the invisible bot check) - SHA-256 of salt + n until it matches. */
async function solveChallenge() {
	const c = await api("/api/challenge");
	const enc = new TextEncoder();
	for (let n = 0; n <= c.maxnumber; n++) {
		const digest = await crypto.subtle.digest("SHA-256", enc.encode(c.salt + n));
		const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
		if (hex === c.challenge) return { salt: c.salt, number: n, challenge: c.challenge, signature: c.signature };
	}
	throw new Error("Couldn't finish the security check - reload the page");
}

function renderLogin(message) {
	state.timers.forEach(clearInterval);
	state.timers = [];
	const user = h("input", { id: "u", autocomplete: "username", required: true, maxlength: 32, autofocus: true });
	const pass = h("input", { id: "p", type: "password", autocomplete: "current-password", required: true });
	const code = h("input", { id: "c", class: "code-input", inputmode: "numeric", autocomplete: "one-time-code", pattern: "[0-9 ]{6,7}", maxlength: 7, required: true, placeholder: "000000" });
	const err = h("div", { class: "error-box", hidden: !message }, message || "");
	const submit = h("button", { class: "btn primary block", type: "submit" }, "Sign in");
	const form = h("form", { novalidate: false },
		h("label", { for: "u" }, "Name", user),
		h("label", { for: "p" }, "Password", pass),
		h("label", { for: "c" }, "Code from your authenticator app", code),
		err, submit,
		h("p", { class: "hint" }, "Two-step sign-in is required. An invisible bot check runs in your browser; after 5 wrong tries the panel blocks your network for 15 minutes."));
	form.addEventListener("submit", async (e) => {
		e.preventDefault();
		err.hidden = true;
		try {
			await busy(submit, async () => api("/api/login", { body: { username: user.value, password: pass.value, code: code.value, pow: (await powPromise) || (await solveChallenge()) } }));
			pass.value = "";
			await boot();
		} catch (x) {
			err.textContent = x.message;
			err.hidden = false;
			code.value = "";
			code.focus();
			powPromise = solveChallenge().catch(() => null);
		}
	});
	// Solved in the background while you type, so signing in doesn't wait for it.
	let powPromise = solveChallenge().catch(() => null);
	$app.replaceChildren(h("main", { class: "login" }, h("div", { class: "login-card" },
		h("div", { class: "login-brand" }, h("img", { src: "/logo.png", alt: "" }), h("div", {}, h("b", {}, "VELO PANEL"), h("span", {}, "Owner sign-in · panel.velo-client.com"))),
		form)));
}

// ---- Shell & routing ----

const PAGES = [
	["Velo", [["overview", "Overview"], ["players", "Players"], ["store", "Store"], ["socials", "Socials"]]],
	["Moderation", [["monitor", "Monitoring"], ["clips", "Clips"], ["worlds", "Public worlds"], ["capes", "Custom capes"], ["stafflog", "Staff log"]]],
	["Owner tools", [["cosmetics", "Cosmetics"], ["news", "News"], ["reports", "Reports"], ["partners", "Partner servers"], ["updates", "Required update"], ["addons", "Addons"]]],
	["Machine", [["server", "Server"], ["files", "Files"], ["activity", "Activity log"]]],
	["", [["account", "Account"]]],
];
const TITLES = Object.fromEntries(PAGES.flatMap(([, list]) => list));

async function boot() {
	try {
		const me = await api("/api/me");
		state.me = me;
		state.csrf = me.csrf;
		shell();
	} catch (e) {
		if (e.status !== 401) renderLogin("The panel isn't answering: " + e.message);
	}
}

function shell() {
	const nav = h("nav", { class: "nav", "aria-label": "Panel" });
	for (const [group, list] of PAGES) {
		if (group) nav.append(h("div", { class: "nav-group" }, group));
		for (const [id, label] of list) nav.append(h("a", { href: "#/" + id, "data-id": id }, icon(id), label));
	}
	const side = h("aside", { class: "side" },
		h("div", { class: "brand" }, h("img", { src: "/logo.png", alt: "" }), h("div", {}, h("b", {}, "VELO"), h("small", {}, "Owner panel"))),
		nav,
		h("div", { class: "side-foot" },
			h("div", { class: "me" }, h("span", { class: "avatar" }, state.me.username.slice(0, 1).toUpperCase()), state.me.username),
			h("button", { class: "btn small ghost", type: "button", onclick: async () => { await api("/api/logout", { method: "POST" }); renderLogin("Signed out."); } }, icon("logout"), "Sign out")));
	const main = h("main", { class: "main", id: "main" });
	$app.replaceChildren(h("div", { class: "shell" }, side, main));
	window.onhashchange = () => route(side, main);
	route(side, main);
}

const RENDER = {};

function route(side, main) {
	const id = (location.hash.replace(/^#\//, "").split("?")[0]) || "overview";
	const page = RENDER[id] ? id : "overview";
	state.timers.forEach(clearInterval);
	state.timers = [];
	side.classList.remove("open");
	side.querySelectorAll(".nav a").forEach((a) => { if (a.dataset.id !== page) a.removeAttribute("aria-current"); else a.setAttribute("aria-current", "page"); });
	const menu = h("button", { class: "icon-btn menu-btn", "aria-label": "Menu", type: "button", onclick: () => side.classList.toggle("open") }, icon("menu"));
	const live = h("span", { class: "live", id: "live" }, h("i"), "Live");
	const body = h("div", { class: "page" });
	main.replaceChildren(h("header", { class: "top" }, h("div", { style: "display:flex;gap:12px;align-items:center" }, menu, h("div", {}, h("h1", {}, TITLES[page]), h("p", { id: "subtitle" }, ""))), live), body);
	document.title = TITLES[page] + " · Velo Panel";
	Promise.resolve(RENDER[page](body)).catch((e) => { if (e.status !== 401) body.replaceChildren(errorBox(e)); });
}

function setLive(ok, text) {
	const el = document.getElementById("live");
	if (!el) return;
	el.classList.toggle("off", !ok);
	el.replaceChildren(h("i"), text || (ok ? "Live" : "Offline"));
}
const subtitle = (t) => { const el = document.getElementById("subtitle"); if (el) el.textContent = t; };

// ---- Overview ----

let overviewDays = 30;
RENDER.overview = async (root) => {
	// Built on the first load (and when the range changes); the 20-second refresh only updates values and lines.
	const kpis = h("div", { class: "grid kpis" }, [0, 1, 2, 3, 4, 5].map(() => h("div", { class: "card skeleton", style: "height:116px" })));
	const charts = h("div", { class: "stack" });
	const health = h("div", { class: "stack" });
	root.append(kpis, charts, health);
	const ui = {};
	const series = (st, money) => ({
		online: [
			{ name: "In game", color: "var(--series-1)", points: st.online.map((x) => [x.ts, x.game]) },
			{ name: "Launcher", color: "var(--series-2)", points: st.online.map((x) => [x.ts, x.launcher]) },
			{ name: "Website", color: "var(--series-3)", points: st.online.map((x) => [x.ts, x.web]) },
		],
		total: [{ name: "Unique players", color: "var(--series-1)", points: st.daily.map((d) => [Date.parse(d.day + "T12:00:00Z"), d.totalPlayers]) }],
		active: st.daily.map((d) => ({ label: dayLabel(d.day), value: d.active })),
		fresh: st.daily.map((d) => ({ label: dayLabel(d.day), value: d.newPlayers })),
		coins: money ? money.daily.map((d) => ({ label: dayLabel(d.day), value: d.coinsSpent })) : [],
	});
	const texts = (st, money) => {
		const newIn = st.daily.reduce((a, d) => a + d.newPlayers, 0);
		return {
			players: `+${fmt(st.newToday)} today · +${fmt(newIn)} in ${overviewDays} days · each account counted once`,
			online: `${st.onlineNow.launcher} in the launcher · ${st.onlineNow.web} on the website`,
			active: `${fmt(st.wau)} this week · ${fmt(st.mau)} this month`,
			peak: `${fmt(st.peakOnline)} all-time peak`,
			plus: money ? fmt(money.itemPurchases) + " Store purchases" : "",
			revenue: money ? `${euro(money.revenueInRange)} in ${overviewDays} days · ${fmt(money.paidOrders)} orders` : "",
		};
	};
	const build = (st, money, sys, svc) => {
		const t = texts(st, money);
		ui.kpi = {
			players: kpi("Unique players", "players", st.players, fmt, t.players, true),
			online: kpi("Online now", "online", st.onlineNow.game, fmt, t.online),
			active: kpi("Active today", "trend", st.dau, fmt, t.active),
			peak: kpi("Peak online", "overview", st.peakOnline24h, fmt, t.peak),
			plus: kpi("Velo+ members", "plus", money ? money.plusMembers : 0, fmt, t.plus),
			revenue: kpi("Revenue", "coins", money ? money.revenue : 0, euro, t.revenue),
		};
		kpis.replaceChildren(...Object.values(ui.kpi));
		const sr = series(st, money);
		ui.online = lineChart({ series: sr.online, xFormat: (x) => new Date(x).toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" }) });
		ui.total = lineChart({ series: sr.total, xFormat: (x) => dayLabel(new Date(x).toISOString().slice(0, 10)) });
		ui.active = barChart({ name: "Active players", data: sr.active });
		ui.fresh = barChart({ name: "New players", data: sr.fresh });
		ui.coins = barChart({ name: "Coins spent", data: sr.coins, yFormat: compact });
		const range = seg([[7, "7d"], [30, "30d"], [90, "90d"], [365, "1y"]], overviewDays, (d) => { overviewDays = d; load(true).catch(() => {}); });
		charts.replaceChildren(
			card("Players online", "Last 48 hours, sampled every 5 minutes", legend([["In game", "var(--series-1)"], ["Launcher", "var(--series-2)"], ["Website", "var(--series-3)"]]), ui.online),
			h("div", { class: "grid cols-2" },
				card("Unique players over time", "Every Minecraft account that ever signed in, counted once", range, ui.total),
				card("Daily active players", "Players who used Velo that day (game or launcher)", null, ui.active)),
			h("div", { class: "grid cols-2" },
				card("New players per day", "First sign-in with Velo", null, ui.fresh),
				card("Coins spent in the Store", "Per day, in Velo Coins", null, ui.coins)));
		const c = sys.current || { cpu: 0, memUsed: 0, memTotal: 1 };
		ui.cpu = meter("CPU", c.cpu, 100, null, c.cpu.toFixed(0) + "% of " + sys.cores + " cores");
		ui.mem = meter("Memory", c.memUsed, c.memTotal);
		ui.disks = sys.disks.map((d) => meter("Disk " + d.mount, d.total - d.free, d.total));
		ui.svc = {};
		const svcRows = svc.services.map((x) => {
			ui.svc[x.unit] = { pill: statusPill(x), state: x.ActiveState };
			return h("div", { class: "svc-head" }, h("span", { style: "font-weight:600" }, x.unit), ui.svc[x.unit].pill);
		});
		ui.uptime = h("p", {}, `${sys.hostname} · up ${duration(sys.uptimeSeconds)}`);
		health.replaceChildren(h("div", { class: "grid cols-2" },
			h("section", { class: "card" }, h("div", { class: "card-head" }, h("div", {}, h("h2", {}, "Server health"), ui.uptime), h("a", { class: "btn small", href: "#/server" }, "Open")),
				h("div", { class: "grid", style: "gap:14px" }, ui.cpu, ui.mem, ...ui.disks)),
			card("Services", "systemd units the panel controls", h("a", { class: "btn small", href: "#/server" }, "Manage"), h("div", { class: "grid", style: "gap:10px" }, svcRows))));
	};
	const update = (st, money, sys, svc) => {
		const t = texts(st, money);
		ui.kpi.players.set(st.players, t.players);
		ui.kpi.online.set(st.onlineNow.game, t.online);
		ui.kpi.active.set(st.dau, t.active);
		ui.kpi.peak.set(st.peakOnline24h, t.peak);
		ui.kpi.plus.set(money ? money.plusMembers : 0, t.plus);
		ui.kpi.revenue.set(money ? money.revenue : 0, t.revenue);
		const sr = series(st, money);
		ui.online.update({ series: sr.online });
		ui.total.update({ series: sr.total });
		ui.active.update({ data: sr.active });
		ui.fresh.update({ data: sr.fresh });
		ui.coins.update({ data: sr.coins });
		const c = sys.current || { cpu: 0, memUsed: 0, memTotal: 1 };
		ui.cpu.set(c.cpu, 100, c.cpu.toFixed(0) + "% of " + sys.cores + " cores");
		ui.mem.set(c.memUsed, c.memTotal);
		sys.disks.forEach((d, i) => ui.disks[i] && ui.disks[i].set(d.total - d.free, d.total));
		ui.uptime.textContent = `${sys.hostname} · up ${duration(sys.uptimeSeconds)}`;
		for (const x of svc.services) {
			const row = ui.svc[x.unit];
			if (row && row.state !== x.ActiveState) {
				row.state = x.ActiveState;
				row.pill.replaceWith(row.pill = statusPill(x));
			}
		}
	};
	const load = async (rebuild) => {
		const [st, money, sys, svc] = await Promise.all([
			velo("admin/panel/stats?days=" + overviewDays + "&hours=48"),
			velo("admin/panel/store?days=" + overviewDays).catch(() => null),
			api("/api/system"),
			api("/api/services").catch(() => ({ services: [] })),
		]).catch((e) => { setLive(false, "Velo server unreachable"); throw e; });
		if (!root.isConnected) return; // the page was left while this loaded
		setLive(true, `Live · ${st.onlineNow.game} in game`);
		subtitle("Updated " + new Date().toLocaleTimeString());
		if (rebuild || !ui.kpi) build(st, money, sys, svc);
		else update(st, money, sys, svc);
	};
	await load(true);
	every(20000, () => load(false).catch(() => {}));
};

function statusPill(x) {
	const st = x.ActiveState;
	const cls = st === "active" ? "good" : st === "failed" ? "bad" : st === "activating" || st === "reloading" ? "warn" : "";
	return h("span", { class: "pill " + cls }, h("i"), st === "active" ? "Running" : st === "failed" ? "Failed" : st === "inactive" ? "Stopped" : st || "Unknown");
}

// ---- Players ----

RENDER.players = async (root) => {
	let tab = "online";
	let lastSessions = "";
	const tabs = h("div", { class: "tabs", role: "tablist" });
	const body = h("div");
	const search = h("input", { type: "search", placeholder: "Search a name or UUID", "aria-label": "Search players" });
	const lookup = h("button", { class: "btn primary shrink", type: "button" }, "Open player");
	lookup.addEventListener("click", () => { if (search.value.trim()) openPlayer(search.value.trim()); });
	search.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); tab === "all" ? draw() : openPlayer(search.value.trim()); } });
	const setTab = (t) => { tab = t; tabs.querySelectorAll("button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.t === t))); draw(); };
	for (const [t, label] of [["online", "Online now"], ["all", "All players"]]) tabs.append(h("button", { role: "tab", "data-t": t, "aria-selected": String(t === tab), type: "button", onclick: () => setTab(t) }, label));
	root.append(card("Players", "Live sessions and everyone who ever signed in", null, h("div", { class: "row", style: "margin-bottom:14px" }, search, lookup), tabs, body));
	async function draw() {
		if (tab === "online") {
			const { sessions } = await velo("admin/panel/sessions");
			if (!root.isConnected) return;
			setLive(true, `Live · ${sessions.filter((x) => x.kind === "game").length} in game`);
			sessions.sort((a, b) => a.kind.localeCompare(b.kind) || a.username.localeCompare(b.username));
			// Nothing changed since the last refresh: leave the table exactly as it is.
			const key = JSON.stringify(sessions);
			if (key === lastSessions && body.firstChild) return;
			lastSessions = key;
			body.replaceChildren(table(["Player", "App", "Status", "Cosmetics", "Velo+"], sessions.map((x) => [
				h("span", { class: "player" }, playerHead(x.uuid, x.username), x.username),
				kindPill(x.kind),
				x.idle ? h("span", { class: "pill" }, "Idle") : h("span", { class: "pill good" }, h("i"), "Active"),
				h("span", { class: "muted small" }, [x.capeId, x.wingsId && "wings " + x.wingsId.split(";")[0], x.auraId && "aura " + x.auraId.split(";")[0]].filter(Boolean).join(" · ") || "–"),
				x.plus ? h("span", { class: "pill red" }, "Velo+") : "–",
			]), (i) => openPlayer(sessions[i].uuid)));
		} else {
			lastSessions = "";
			const { players } = await velo("admin/panel/players?limit=300&q=" + encodeURIComponent(search.value.trim()));
			subtitle(players.length + " players shown");
			body.replaceChildren(table(["Player", "First seen", "Last seen", "Apps", "num:Sign-ins"], players.map((p) => [
				h("span", { class: "player" }, playerHead(p.uuid, p.username), p.username || p.uuid),
				when(p.firstSeen), ago(p.lastSeen),
				h("span", { style: "display:flex;gap:6px" }, p.game ? kindPill("game") : null, p.launcher ? kindPill("launcher") : null),
				fmt(p.logins),
			]), (i) => openPlayer(players[i].uuid)));
		}
	}
	await draw();
	every(15000, () => { if (tab === "online") draw().catch(() => {}); });
};

async function catalog() {
	if (!state.catalog) state.catalog = await velo("admin/cosmetics");
	return state.catalog;
}

const BAN_SERVICES = [["network", "Whole Velo Network", "Can't sign in at all: no friends, cosmetics, Store or rewards"], ["friends", "Friends & chat", "Friends, messages and presence"],
	["parties", "Parties", "Creating and joining parties"], ["capes", "Custom capes", "Uploading and showing a custom cape"]];

async function openPlayer(target) {
	let view, staffView;
	try {
		[view, staffView] = await Promise.all([
			velo("admin/store/lookup", { body: { target } }),
			velo("staff/lookup", { body: { target } }).catch(() => null),
		]);
	} catch (e) {
		toast(e.message, "err");
		return;
	}
	const cat = await catalog().catch(() => ({ items: [] }));
	const byId = Object.fromEntries((cat.items || []).map((i) => [i.id, i]));
	const live = await velo("admin/panel/sessions").then((r) => r.sessions.filter((x) => x.uuid === view.uuid)).catch(() => []);
	const body = h("div", { class: "grid", style: "gap:16px" });
	const dlg = dialog(view.username || view.uuid, body, null, true);
	dlg.querySelector(".dlg-head h2").prepend(playerHead(view.uuid, view.username, 34));
	dlg.querySelector(".dlg-head h2").style.cssText = "display:flex;align-items:center;gap:12px";
	const refreshStaff = async () => { staffView = await velo("staff/lookup", { body: { target: view.uuid } }); render(view); };

	const render = (v) => {
		view = v;
		const plus = v.plusUntil > Date.now();
		// ---- Coins ----
		const amount = h("input", { type: "number", value: "1000", "aria-label": "Coins" });
		const note = h("input", { placeholder: "Reason (shown in the ledger)", "aria-label": "Reason" });
		const coinsBtn = h("button", { class: "btn primary shrink", type: "button" }, "Add coins");
		const takeBtn = h("button", { class: "btn danger shrink", type: "button" }, "Take coins");
		const doCoins = (sign, btn) => busy(btn, async () => {
			const n = Math.abs(parseInt(amount.value, 10) || 0) * sign;
			if (!n) throw new Error("Enter an amount");
			render(await velo("admin/store/coins", { body: { target: v.uuid, amount: n, note: note.value || "Owner panel" } }));
			toast((n > 0 ? "Added " : "Took ") + fmt(Math.abs(n)) + " coins");
		});
		coinsBtn.onclick = () => doCoins(1, coinsBtn);
		takeBtn.onclick = () => doCoins(-1, takeBtn);
		// ---- Items ----
		const itemSel = h("select", { "aria-label": "Item" }, (cat.items || []).filter((i) => !v.owned.includes(i.id)).map((i) => h("option", { value: i.id }, `${i.name} (${i.type})`)));
		const grantBtn = h("button", { class: "btn shrink", type: "button" }, "Give item");
		grantBtn.onclick = () => busy(grantBtn, async () => { render(await velo("admin/store/item", { body: { target: v.uuid, itemId: itemSel.value, grant: true } })); toast("Item given"); });
		const ownedTiles = v.owned.map((id) => {
			const it = byId[id] || { id, name: id, type: id.split("_")[0] };
			const take = h("button", { class: "btn small danger", type: "button" }, "Take back");
			take.onclick = async (e) => {
				e.stopPropagation();
				if (!(await confirmBox("Take back " + it.name + "?", "The player loses this item."))) return;
				render(await velo("admin/store/item", { body: { target: v.uuid, itemId: id, grant: false } }));
				toast("Item taken back");
			};
			return h("div", { class: "card item", style: "cursor:default;animation:none" },
				h("div", { class: "art", style: "aspect-ratio:1/.8" }, cosmeticArt(it, 6)),
				h("div", { class: "body" }, h("b", { style: "font-size:13px" }, it.name), h("div", { class: "meta" }, h("span", { class: "muted small" }, it.type), take)));
		});
		// ---- Velo+ ----
		const days = h("input", { type: "number", value: "30", "aria-label": "Days" });
		const plusBtn = h("button", { class: "btn primary shrink", type: "button" }, "Give Velo+ days");
		const endBtn = h("button", { class: "btn danger shrink", type: "button" }, "End Velo+");
		plusBtn.onclick = () => busy(plusBtn, async () => { await velo("admin/store/plus", { body: { target: v.uuid, days: parseInt(days.value, 10) || 30 } }); render(await velo("admin/store/lookup", { body: { target: v.uuid } })); toast("Velo+ extended"); });
		endBtn.onclick = async () => {
			if (!(await confirmBox("End Velo+?", "This ends " + (v.username || "the player") + "'s membership right now."))) return;
			await busy(endBtn, async () => { await velo("admin/store/plus", { body: { target: v.uuid, days: 0 } }); render(await velo("admin/store/lookup", { body: { target: v.uuid } })); toast("Velo+ ended"); });
		};
		// ---- Addons (invite only, separate from Velo+) ----
		const addonsBtn = h("button", { class: v.addons ? "btn danger shrink" : "btn primary shrink", type: "button" }, v.addons ? "Take away access" : "Give access");
		addonsBtn.onclick = async () => {
			if (v.addons && !(await confirmBox("Take away the Addons?", "They're switched off in " + (v.username || "the player") + "'s profiles at the next launch."))) return;
			await busy(addonsBtn, async () => { render(await velo("admin/store/addons", { body: { target: v.uuid, granted: !v.addons } })); toast(v.addons ? "Addons access removed" : "Addons access given"); });
		};

		body.replaceChildren(
			h("div", { class: "grid kpis" },
				kpi("Coins", "coins", v.balance, fmt),
				kpi("Items owned", "cosmetics", v.owned.length, fmt),
				h("div", { class: "card kpi" }, h("div", { class: "label" }, icon("plus"), "Velo+"), h("div", { class: "value", style: "font-size:20px" }, plus ? "Member" : "No"), h("div", { class: "sub" }, plus ? "until " + when(v.plusUntil) : "–")),
				h("div", { class: "card kpi" }, h("div", { class: "label" }, icon("online"), "Status"),
					h("div", { class: "value", style: "font-size:20px" }, live.length ? "Online" : "Offline"),
					h("div", { class: "sub" }, live.length ? live.map((x) => x.kind).join(" + ") : staffView && staffView.level ? "Staff: " + staffView.level : "–"))),
			h("div", { class: "muted small mono" }, v.uuid + (staffView && staffView.level ? " · staff level " + staffView.level : "")),
			staffCard(v, staffView, refreshStaff),
			cosmeticsCard(live, byId, staffView, refreshStaff),
			card("Coins", "Positive adds, the ledger keeps the reason", null, h("div", { class: "row" }, amount, note, coinsBtn, takeBtn)),
			card("Owned items", v.owned.length + " cosmetics", null,
				v.owned.length ? h("div", { class: "items", style: "grid-template-columns:repeat(auto-fill,minmax(150px,1fr));margin-bottom:14px" }, ownedTiles) : h("p", { class: "muted" }, "None yet"),
				h("div", { class: "row" }, itemSel, grantBtn)),
			card("Velo+", plus ? "Active until " + when(v.plusUntil) : "Not a member", null, h("div", { class: "row" }, days, plusBtn, plus ? endBtn : null)),
			card("Addons", v.addons ? "Has access" : "No access - only players you pick see the Addons anywhere", null, h("div", { class: "row" }, addonsBtn)),
			staffView && staffView.reports > 0 ? h("div", { class: "notice" }, `${staffView.reports} player report(s) against ${v.username} - `, h("a", { href: "#/reports", onclick: () => dlg.close() }, "open Reports")) : null,
			card("Ledger", "Last 30 coin changes", null, table(["When", "Reason", "Ref", "num:Change", "num:Balance"], v.ledger.map((l) => [
				when(l.createdAt), l.reason, h("span", { class: "muted small" }, l.ref || l.actor || ""), (l.delta > 0 ? "+" : "") + fmt(l.delta), fmt(l.balanceAfter)]))));
	};
	render(view);
	return dlg;
}

/** Ban status and the ban form: which online services, how long, why. */
function staffCard(v, sv, refresh) {
	if (!sv) return card("Moderation", "Staff tools aren't available (server too old?)", null);
	const ban = sv.ban;
	const active = ban && (ban.until === 0 || ban.until > Date.now());
	const checks = BAN_SERVICES.map(([id, label, hint]) => {
		const box = h("input", { type: "checkbox", value: id, checked: active && ban.services.includes(id) });
		return h("label", { class: "check", style: "align-items:flex-start" }, box, h("span", {}, h("b", { style: "display:block;color:var(--text)" }, label), h("span", { class: "muted small" }, hint)));
	});
	const days = h("select", { "aria-label": "Duration" }, [[1, "1 day"], [3, "3 days"], [7, "7 days"], [30, "30 days"], [90, "90 days"], [0, "Permanent"]].map(([d, l]) => h("option", { value: d, selected: d === 7 }, l)));
	const reason = h("input", { placeholder: "Reason the player is told", value: active ? ban.reason || "" : "", "aria-label": "Reason" });
	const banBtn = h("button", { class: "btn danger shrink", type: "button" }, active ? "Update ban" : "Ban");
	const liftBtn = active ? h("button", { class: "btn shrink", type: "button" }, "Lift ban") : null;
	banBtn.onclick = async () => {
		const services = checks.map((c) => c.querySelector("input")).filter((i) => i.checked).map((i) => i.value);
		if (!services.length) return toast("Pick at least one service", "err");
		const len = days.options[days.selectedIndex].text.toLowerCase();
		if (!(await confirmBox(`Ban ${v.username}?`, `From ${services.map((x) => BAN_SERVICES.find((b) => b[0] === x)[1]).join(", ")} for ${len}.` + (services.includes("network") ? " They're signed out right away." : ""), "Ban"))) return;
		await busy(banBtn, async () => { await velo("staff/ban", { body: { target: v.uuid, services, days: Number(days.value), reason: reason.value } }); await refresh(); toast(v.username + " banned"); });
	};
	if (liftBtn) liftBtn.onclick = async () => {
		if (!(await confirmBox("Lift the ban?", v.username + " gets all services back right away.", "Lift ban", false))) return;
		await busy(liftBtn, async () => { await velo("staff/unban", { body: { target: v.uuid } }); await refresh(); toast("Ban lifted"); });
	};
	const status = active
		? h("div", { class: "error-box" }, h("b", {}, "Banned from " + ban.services.map((x) => (BAN_SERVICES.find((b) => b[0] === x) || [x, x])[1]).join(", ")),
			h("div", { class: "small" }, (ban.until ? "until " + when(ban.until) : "permanently") + " · by " + (ban.by || "?") + " · " + when(ban.at) + (ban.reason ? " · “" + ban.reason + "”" : "")))
		: h("div", { class: "notice" }, "No active ban.");
	return card("Moderation", "Bans from Velo online services", h("span", { class: "pill " + (active ? "bad" : "good") }, h("i"), active ? "Banned" : "In good standing"),
		h("div", { class: "grid", style: "gap:14px" }, status,
			h("div", { style: "display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px" }, checks),
			h("div", { class: "row" }, days, reason, banBtn, liftBtn)));
}

/** What the player wears right now, and their custom cape for review (remove + block uploads). */
function cosmeticsCard(live, byId, sv, refresh) {
	const game = live.find((x) => x.kind === "game");
	const worn = [];
	if (game) {
		if (game.capeId && !game.capeId.startsWith("custom:")) worn.push(byId[game.capeId] || { id: game.capeId, name: game.capeId, type: "cape" });
		if (game.wingsId) { const id = "wings_" + game.wingsId.split(";")[0]; worn.push(byId[id] || { id, name: id, type: "wings" }); }
		if (game.auraId) { const id = "aura_" + game.auraId.split(";")[0]; worn.push(byId[id] || { id, name: id, type: "aura" }); }
	}
	const hash = sv && sv.customCape;
	let capeBox;
	if (hash) {
		const url = "/api/velo/cape/" + hash;
		const remove = h("button", { class: "btn danger", type: "button" }, "Remove cape & block uploads");
		remove.onclick = async () => {
			if (!(await confirmBox("Remove this custom cape?", "It disappears for everyone, and the player can't upload custom capes any more (a 'custom capes' ban you can lift in Moderation).", "Remove & block"))) return;
			await busy(remove, async () => { await velo("staff/censor-cape", { body: { target: sv.uuid } }); await refresh(); toast("Custom cape removed"); });
		};
		capeBox = h("div", { style: "display:grid;grid-template-columns:auto 1fr;gap:20px;align-items:center" },
			h("div", { class: "art", style: "display:grid;place-items:center;width:200px;height:220px;border-radius:14px;background:radial-gradient(circle at 50% 70%, rgba(224,38,46,.3), transparent 65%), #0c0c10" }, cape3d(url, 11)),
			h("div", { class: "grid", style: "gap:10px" },
				h("b", {}, "Custom cape (uploaded by the player)"),
				h("img", { src: url, alt: "Custom cape texture", style: "max-width:320px;width:100%;image-rendering:pixelated;border-radius:8px;border:1px solid var(--line-2);background:repeating-conic-gradient(#222 0 25%, #2a2a2a 0 50%) 0 0/16px 16px" }),
				h("span", { class: "muted small mono" }, hash.slice(0, 24) + "…" + (game && game.capeId && game.capeId.startsWith("custom:") ? " · wearing it now" : "")),
				h("div", {}, remove)));
	} else {
		capeBox = h("p", { class: "muted", style: "margin:0" }, "No custom cape uploaded.");
	}
	return card("Cosmetics", game ? "Wearing right now in game" : "Not in game right now", null,
		h("div", { class: "grid", style: "gap:16px" },
			worn.length ? h("div", { class: "items", style: "grid-template-columns:repeat(auto-fill,minmax(150px,1fr))" }, worn.map((it) =>
				h("div", { class: "card item", style: "cursor:default;animation:none" }, h("div", { class: "art", style: "aspect-ratio:1/.8" }, cosmeticArt(it, 6)),
					h("div", { class: "body" }, h("b", { style: "font-size:13px" }, it.name), h("span", { class: "muted small" }, "equipped " + it.type))))) : null,
			capeBox));
}

// ---- Store & money ----

let storeDays = 30;
RENDER.store = async (root) => {
	const draw = async () => {
		const m = await velo("admin/panel/store?days=" + storeDays);
		const cat = await catalog().catch(() => ({ items: [] }));
		const names = Object.fromEntries((cat.items || []).map((i) => [i.id, i.name]));
		const days = [];
		for (let i = storeDays - 1; i >= 0; i--) days.push(new Date(Date.now() - i * 86400000).toISOString().slice(0, 10));
		const byDay = Object.fromEntries(m.daily.map((d) => [d.day, d]));
		root.replaceChildren(
			h("div", { class: "grid kpis" },
				kpi("Revenue", "coins", m.revenue, euro, `${euro(m.revenueInRange)} in ${storeDays} days`, true),
				kpi("Paid orders", "store", m.paidOrders, fmt, m.reversedOrders ? m.reversedOrders + " refunded or charged back" : "No refunds"),
				kpi("Velo+ members", "plus", m.plusMembers, fmt),
				kpi("Store purchases", "cosmetics", m.itemPurchases, fmt, compact(m.coinsSpent) + " coins spent"),
				kpi("Coins in wallets", "coins", m.coinsInCirculation, compact, fmt(m.wallets) + " player wallets · owners not counted"),
				kpi("Coins sold", "trend", m.coinsSold, compact)),
			h("div", { class: "stack" },
				h("div", { class: "grid cols-2" },
					card("Revenue per day", "Paid coin orders (Tebex)", seg([[7, "7d"], [30, "30d"], [90, "90d"], [365, "1y"]], storeDays, (d) => { storeDays = d; draw(); }),
						barChart({ name: "Revenue", data: days.map((d) => ({ label: dayLabel(d), value: byDay[d] ? byDay[d].revenue : 0 })), yFormat: euro })),
					card("Coins spent per day", "Items and bundles bought with coins", null,
						barChart({ name: "Coins spent", color: "var(--series-2)", data: days.map((d) => ({ label: dayLabel(d), value: byDay[d] ? byDay[d].coinsSpent : 0 })), yFormat: compact }))),
				h("div", { class: "grid cols-2" },
					card("Best sellers", "Bought or gifted", null, table(["Item", "num:Sales"], m.topItems.map((t) => [names[t.itemId] || t.itemId, fmt(t.sales)]))),
					card("Latest purchases", "Store buys and coin packs", null, table(["When", "Player", "What", "num:Coins"], m.recentPurchases.slice(0, 15).map((p) => [
						when(p.createdAt), p.username || p.uuid.slice(0, 8), p.reason === "purchase_coins" ? "Coin pack" : names[(p.ref || "").split(":")[0]] || p.ref || p.reason,
						(p.coins > 0 ? "+" : "") + fmt(p.coins)]), (i) => openPlayer(m.recentPurchases[i].uuid)))),
				card("Orders", "Real-money coin orders", null, table(["When", "Player", "Pack", "num:Coins", "num:Price", "Status"], m.recentOrders.map((o) => [
					when(o.createdAt), o.username || o.uuid.slice(0, 8), o.packId, fmt(o.coins), euro(o.price),
					h("span", { class: "pill " + (o.status === "paid" ? "good" : o.status === "pending" ? "warn" : "bad") }, o.status)]), (i) => openPlayer(m.recentOrders[i].uuid)))));
	};
	await draw();
};

// ---- Cosmetics ----

/** A cape as a small 3D model, its animated GIF mapped onto the cape template's faces (it plays like in game). */
function cape3d(url, k = 9) {
	const rig = h("div", { class: "rig" });
	for (const f of ["in", "l", "r", "t", "b", "out"]) {
		const face = h("div", { class: "face " + f });
		face.style.backgroundImage = `url("${url}")`;
		rig.append(face);
	}
	const el = h("div", { class: "cape3d", role: "img", "aria-label": "3D cape preview" }, rig);
	el.style.setProperty("--k", k + "px");
	return el;
}

/** The right picture for any cosmetic: wings' rendered preview, a live 3D cape, or the aura's animation. */
function cosmeticArt(i, k) {
	if (i.type === "cape" && i.files && i.files.gif) return cape3d("/api/velo/cosmetics/file/" + i.files.gif, k);
	if (i.type === "aura") return h("img", { class: "aura-art", src: "/auras/" + i.id.replace(/^aura_/, "") + ".webp", alt: "", loading: "lazy" });
	if (i.type === "emote") return h("span", { class: "muted" }, "✦ emote");
	if (i.files && i.files.preview) return h("img", { src: "/api/velo/cosmetics/file/" + i.files.preview, alt: "", loading: "lazy" });
	return h("span", { class: "muted" }, i.type);
}

const STATUS = [["released", "Released"], ["testing", "Testing"], ["hidden", "Hidden"]];
const toDate = (ms) => (ms ? new Date(ms - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16) : "");
const fromDate = (v) => (v ? new Date(v).getTime() : null);

RENDER.cosmetics = async (root) => {
	state.catalog = null;
	const data = await catalog();
	let tab = "items";
	const tabs = h("div", { class: "tabs", role: "tablist" });
	const body = h("div");
	for (const [t, label] of [["items", "Items"], ["bundles", "Bundles"], ["collections", "Collections"]]) {
		tabs.append(h("button", { role: "tab", type: "button", "data-t": t, "aria-selected": String(t === tab), onclick: () => { tab = t; tabs.querySelectorAll("button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.t === t))); draw(); } }, label));
	}
	const filter = h("select", { "aria-label": "Filter" }, h("option", { value: "" }, "All types"), ["wings", "cape", "aura", "emote"].map((t) => h("option", { value: t }, t)));
	const statusFilter = h("select", { "aria-label": "Status" }, h("option", { value: "" }, "Any status"), STATUS.map(([v, l]) => h("option", { value: v }, l)));
	filter.onchange = statusFilter.onchange = () => draw();
	root.append(tabs, body);
	subtitle(data.items.length + " items · featured: " + (data.featured || "none"));
	const reload = async () => { state.catalog = null; Object.assign(data, await catalog()); draw(); };
	function draw() {
		if (tab === "items") {
			const items = data.items.filter((i) => (!filter.value || i.type === filter.value) && (!statusFilter.value || (i.status || "released") === statusFilter.value));
			body.replaceChildren(h("div", { class: "row", style: "margin-bottom:14px;max-width:520px" }, filter, statusFilter), h("div", { class: "items" }, items.map((i) => {
				const st = i.status || "released";
				const art = cosmeticArt(i, 10);
				const tile = h("button", { class: "card item", type: "button", style: i.accent && /^#[0-9a-f]{3,8}$/i.test(i.accent) ? "--accent:" + i.accent + "55" : null },
					h("div", { class: "art" }, art),
					h("div", { class: "body" }, h("b", {}, i.name),
						h("div", { class: "meta" }, h("span", { class: "coin" }, fmt(i.price)), h("span", { class: "pill " + (st === "released" ? "good" : st === "testing" ? "warn" : "") }, h("i"), st)),
						h("span", { class: "muted small" }, i.type + (i.collection ? " · " + i.collection : "") + (i.unlock ? " · unlock: " + i.unlock : ""))));
				tile.onclick = () => editItem(i, data, reload);
				return tile;
			})));
		} else if (tab === "bundles") {
			const add = h("button", { class: "btn primary", type: "button", onclick: () => editBundle(null, data, reload) }, icon("plus"), "New bundle");
			body.replaceChildren(card("Bundles", "Several items for one price", add, table(["Name", "Items", "num:Price", "Ends", "State"], (data.bundles || []).map((b) => [
				b.name, b.items.length + " items", fmt(b.price), b.availableUntil ? when(b.availableUntil) : "–",
				h("span", { class: "pill " + (b.active ? "good" : "") }, b.active ? "Active" : "Off")]), (i) => editBundle(data.bundles[i], data, reload))));
		} else {
			const add = h("button", { class: "btn primary", type: "button", onclick: () => editCollection(null, data, reload) }, icon("plus"), "New collection");
			const featured = h("select", { "aria-label": "Featured collection" }, h("option", { value: "" }, "None"), data.collections.map((c) => h("option", { value: c.id, selected: c.id === data.featured }, c.name)));
			const save = h("button", { class: "btn shrink", type: "button" }, "Set featured");
			save.onclick = () => busy(save, async () => { await velo("admin/cosmetics/featured", { body: { id: featured.value || null } }); toast("Featured collection set"); reload(); });
			body.replaceChildren(card("Featured", "The collection the Store opens on", null, h("div", { class: "row", style: "max-width:520px" }, featured, save)),
				h("div", { class: "stack" }, card("Collections", "Themed drops", add, table(["Name", "Tag", "Items", "Description"], data.collections.map((c) => [
					c.name, c.tag, data.items.filter((i) => i.collection === c.id).length, h("span", { class: "muted small" }, c.description)]), (i) => editCollection(data.collections[i], data, reload)))));
		}
	}
	draw();
};

function field(label, input) {
	return h("label", {}, label, input);
}

function editItem(item, data, reload) {
	const name = h("input", { value: item.name });
	const desc = h("textarea", {}, item.description || "");
	const price = h("input", { type: "number", min: 0, value: item.price });
	const status = h("select", {}, STATUS.map(([v, l]) => h("option", { value: v, selected: (item.status || "released") === v }, l)));
	const coll = h("select", {}, h("option", { value: "" }, "None"), data.collections.map((c) => h("option", { value: c.id, selected: c.id === item.collection }, c.name)));
	const from = h("input", { type: "datetime-local", value: toDate(item.availableFrom) });
	const until = h("input", { type: "datetime-local", value: toDate(item.availableUntil) });
	const clear = h("input", { type: "checkbox" });
	const save = h("button", { class: "btn primary", type: "button" }, "Save");
	const dlg = dialog(item.name, [
		h("div", { class: "item", style: "pointer-events:none" }, h("div", { class: "art", style: "border-radius:14px;aspect-ratio:16/8" }, cosmeticArt(item, 11))),
		h("div", { class: "row" }, field("Name", name), field("Price (coins)", price)),
		field("Description", desc),
		h("div", { class: "row" }, field("Status", status), field("Collection", coll)),
		h("div", { class: "row" }, field("In the Store from", from), field("Until", until)),
		h("label", { class: "check" }, clear, "Remove the time window (always available while released)"),
		h("p", { class: "muted small", style: "margin:0" }, "Testing items are only shown to players you give them to. Hidden items aren't shown anywhere."),
	], [h("button", { class: "btn ghost", type: "button", onclick: () => dlg.close() }, "Cancel"), save]);
	save.onclick = () => busy(save, async () => {
		await velo("admin/cosmetics/item", { body: { clearWindow: clear.checked, item: {
			id: item.id, name: name.value, description: desc.value, price: parseInt(price.value, 10) || 0, status: status.value,
			collection: coll.value || null, availableFrom: clear.checked ? null : fromDate(from.value), availableUntil: clear.checked ? null : fromDate(until.value) } } });
		toast("Saved " + name.value);
		dlg.close();
		reload();
	});
}

function editBundle(bundle, data, reload) {
	const b = bundle || { id: "", name: "", description: "", items: [], price: 0, active: false, availableUntil: null };
	const id = h("input", { value: b.id, pattern: "[a-z0-9_]+", disabled: !!bundle, placeholder: "halloween_2026" });
	const name = h("input", { value: b.name });
	const desc = h("textarea", {}, b.description || "");
	const price = h("input", { type: "number", min: 0, value: b.price });
	const active = h("input", { type: "checkbox", checked: b.active });
	const until = h("input", { type: "datetime-local", value: toDate(b.availableUntil) });
	const picks = h("div", { style: "display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:6px;max-height:260px;overflow:auto" },
		data.items.map((i) => h("label", { class: "check" }, h("input", { type: "checkbox", value: i.id, checked: b.items.includes(i.id) }), `${i.name} · ${fmt(i.price)}`)));
	const full = h("span", { class: "muted small" });
	const sum = () => { const ids = [...picks.querySelectorAll("input:checked")].map((x) => x.value); full.textContent = "Items bought one by one: " + fmt(data.items.filter((i) => ids.includes(i.id)).reduce((a, i) => a + i.price, 0)) + " coins"; };
	picks.addEventListener("change", sum);
	sum();
	const save = h("button", { class: "btn primary", type: "button" }, "Save bundle");
	const del = bundle ? h("button", { class: "btn danger", type: "button" }, "Delete") : null;
	const dlg = dialog(bundle ? b.name : "New bundle", [
		h("div", { class: "row" }, field("Id", id), field("Name", name)), field("Description", desc),
		h("div", { class: "row" }, field("Price (coins)", price), field("Ends", until)),
		h("label", { class: "check" }, active, "Active (shown in the Store)"),
		h("div", {}, h("h3", { style: "margin-bottom:8px" }, "Items"), picks), full,
	], [del, h("button", { class: "btn ghost", type: "button", onclick: () => dlg.close() }, "Cancel"), save].filter(Boolean), true);
	save.onclick = () => busy(save, async () => {
		await velo("admin/cosmetics/bundle", { body: { id: id.value.trim(), name: name.value, description: desc.value, price: parseInt(price.value, 10) || 0,
			active: active.checked, availableUntil: fromDate(until.value), items: [...picks.querySelectorAll("input:checked")].map((x) => x.value) } });
		toast("Bundle saved");
		dlg.close();
		reload();
	});
	if (del) del.onclick = async () => {
		if (!(await confirmBox("Delete " + b.name + "?", "Players who bought it keep their items."))) return;
		await velo("admin/cosmetics/bundle/delete", { body: { id: b.id } });
		toast("Bundle deleted");
		dlg.close();
		reload();
	};
}

function editCollection(c, data, reload) {
	const x = c || { id: "", name: "", tag: "COLLECTION", description: "" };
	const id = h("input", { value: x.id, disabled: !!c, placeholder: "afterlife" });
	const name = h("input", { value: x.name });
	const tag = h("input", { value: x.tag });
	const desc = h("textarea", {}, x.description || "");
	const save = h("button", { class: "btn primary", type: "button" }, "Save");
	const dlg = dialog(c ? x.name : "New collection", [h("div", { class: "row" }, field("Id", id), field("Name", name)), field("Tag", tag), field("Description", desc)],
		[h("button", { class: "btn ghost", type: "button", onclick: () => dlg.close() }, "Cancel"), save]);
	save.onclick = () => busy(save, async () => {
		await velo("admin/cosmetics/collection", { body: { id: id.value.trim(), name: name.value, tag: tag.value, description: desc.value } });
		toast("Collection saved");
		dlg.close();
		reload();
	});
}

// ---- News ----

RENDER.news = async (root) => {
	const draw = async () => {
		const feed = await velo("news");
		const add = h("button", { class: "btn primary", type: "button", onclick: () => editPost(null, draw) }, icon("plus"), "New post");
		const addPoll = h("button", { class: "btn", type: "button", onclick: () => editPoll(draw) }, icon("plus"), "New poll");
		root.replaceChildren(
			card("Posts", "Shown in the launcher's News and on velo-client.com", add, table(["Title", "Tag", "Version", "State", "Updated"], feed.posts.map((p) => [
				h("b", {}, p.title), p.tag || "–", p.version || "–",
				h("span", { style: "display:flex;gap:6px" }, h("span", { class: "pill " + (p.published ? "good" : "warn") }, p.published ? "Published" : "Draft"), p.pinned ? h("span", { class: "pill red" }, "Pinned") : null),
				ago(p.updatedAt || p.createdAt)]), (i) => editPost(feed.posts[i], draw))),
			h("div", { class: "stack" }, card("Polls", "Players vote in the launcher", addPoll, table(["Question", "num:Votes", "State", ""], feed.polls.map((p) => {
				const del = h("button", { class: "btn small danger", type: "button" }, "Delete");
				del.onclick = async (e) => {
					e.stopPropagation();
					if (!(await confirmBox("Delete this poll?", p.question))) return;
					await velo("admin/news/poll/delete", { body: { id: p.id } });
					toast("Poll deleted");
					draw();
				};
				return [h("div", {}, h("b", {}, p.question), h("div", { class: "muted small" }, p.options.map((o) => o.text + (o.votes >= 0 ? " (" + o.votes + ")" : "")).join(" · "))),
					p.totalVotes >= 0 ? fmt(p.totalVotes) : "–", h("span", { class: "pill " + (p.closed ? "" : "good") }, p.closed ? "Closed" : "Open"), del];
			})))));
	};
	await draw();
};

const BLOCK_TYPES = ["heading", "text", "list", "callout", "image", "divider"];

function editPost(post, reload) {
	const p = post ? JSON.parse(JSON.stringify(post)) : { title: "", summary: "", tag: "Update", version: "", blocks: [{ type: "text", text: "" }], published: false, pinned: false };
	const title = h("input", { value: p.title });
	const summary = h("textarea", { style: "min-height:60px" }, p.summary || "");
	const tag = h("input", { value: p.tag || "", list: "tags" });
	const version = h("input", { value: p.version || "", placeholder: "0.9.8" });
	const published = h("input", { type: "checkbox", checked: p.published });
	const pinned = h("input", { type: "checkbox", checked: p.pinned });
	let cover = p.cover || null;
	const coverImg = h("img", { alt: "", style: "max-width:100%;border-radius:12px;display:" + (cover ? "block" : "none") });
	if (cover) coverImg.src = "/api/velo/news/image/" + cover;
	const coverFile = h("input", { type: "file", accept: "image/png,image/jpeg" });
	coverFile.onchange = async () => {
		const f = coverFile.files[0];
		if (!f) return;
		const r = await velo("admin/news/image", { body: f, headers: { "Content-Type": "application/octet-stream" } });
		cover = r.id;
		coverImg.src = "/api/velo/news/image/" + cover;
		coverImg.style.display = "block";
		toast("Cover uploaded");
	};
	const blocks = h("div", { class: "grid", style: "gap:10px" });
	const drawBlocks = () => blocks.replaceChildren(...p.blocks.map((b, i) => {
		const type = h("select", { "aria-label": "Block type" }, BLOCK_TYPES.map((t) => h("option", { value: t, selected: b.type === t }, t)));
		type.onchange = () => { b.type = type.value; drawBlocks(); };
		const text = b.type === "divider" ? null : b.type === "list" ? h("textarea", { placeholder: "One item per line" }, (b.items || []).join("\n"))
			: b.type === "image" ? h("input", { value: b.caption || "", placeholder: "Caption" }) : h("textarea", { style: "min-height:" + (b.type === "heading" ? "44px" : "80px") }, b.text || "");
		if (text) text.oninput = () => { if (b.type === "list") b.items = text.value.split("\n").filter(Boolean); else if (b.type === "image") b.caption = text.value; else b.text = text.value; };
		const img = b.type === "image" ? h("input", { type: "file", accept: "image/png,image/jpeg", "aria-label": "Image" }) : null;
		if (img) img.onchange = async () => { const r = await velo("admin/news/image", { body: img.files[0], headers: { "Content-Type": "application/octet-stream" } }); b.image = r.id; toast("Image uploaded"); };
		const up = h("button", { class: "icon-btn", type: "button", "aria-label": "Move up", onclick: () => { if (i) { p.blocks.splice(i - 1, 0, p.blocks.splice(i, 1)[0]); drawBlocks(); } } }, "↑");
		const rm = h("button", { class: "icon-btn", type: "button", "aria-label": "Remove block", onclick: () => { p.blocks.splice(i, 1); drawBlocks(); } }, icon("close"));
		return h("div", { class: "card", style: "padding:12px;display:grid;gap:8px;animation:none" }, h("div", { class: "row" }, type, h("span", { class: "shrink", style: "display:flex;gap:6px" }, up, rm)), text, img,
			b.type === "image" && b.image ? h("span", { class: "muted small" }, "Image: " + b.image) : null);
	}));
	drawBlocks();
	const addBlock = h("button", { class: "btn small", type: "button", onclick: () => { p.blocks.push({ type: "text", text: "" }); drawBlocks(); } }, icon("plus"), "Add block");
	const save = h("button", { class: "btn primary", type: "button" }, "Save");
	const del = post ? h("button", { class: "btn danger", type: "button" }, "Delete") : null;
	const dlg = dialog(post ? "Edit post" : "New post", [
		field("Title", title), field("Summary", summary),
		h("div", { class: "row" }, field("Tag", tag), field("Version", version)),
		h("datalist", { id: "tags" }, ["Update", "Store", "Community", "Event"].map((t) => h("option", { value: t }))),
		h("div", { class: "row" }, h("label", { class: "check" }, published, "Published"), h("label", { class: "check" }, pinned, "Pinned to the top")),
		field("Cover image", coverFile), coverImg,
		h("div", { class: "card-head", style: "margin:6px 0 0" }, h("h3", {}, "Content"), addBlock), blocks,
	], [del, h("button", { class: "btn ghost", type: "button", onclick: () => dlg.close() }, "Cancel"), save].filter(Boolean), true);
	save.onclick = () => busy(save, async () => {
		await velo("admin/news/post", { body: { ...p, title: title.value, summary: summary.value, tag: tag.value, version: version.value || null, cover, published: published.checked, pinned: pinned.checked } });
		toast("Post saved");
		dlg.close();
		reload();
	});
	if (del) del.onclick = async () => {
		if (!(await confirmBox("Delete this post?", p.title))) return;
		await velo("admin/news/post/delete", { body: { id: p.id } });
		toast("Post deleted");
		dlg.close();
		reload();
	};
}

function editPoll(reload) {
	const q = h("input", {});
	const desc = h("input", { placeholder: "Optional" });
	const opts = h("textarea", { placeholder: "One option per line" });
	const multi = h("input", { type: "checkbox" });
	const closes = h("input", { type: "datetime-local" });
	const show = h("select", {}, h("option", { value: "after_vote" }, "After voting"), h("option", { value: "always" }, "Always"));
	const save = h("button", { class: "btn primary", type: "button" }, "Create poll");
	const dlg = dialog("New poll", [field("Question", q), field("Description", desc), field("Options", opts), h("div", { class: "row" }, field("Closes", closes), field("Show results", show)),
		h("label", { class: "check" }, multi, "Allow several answers")], [h("button", { class: "btn ghost", type: "button", onclick: () => dlg.close() }, "Cancel"), save]);
	save.onclick = () => busy(save, async () => {
		await velo("admin/news/poll", { body: { question: q.value, description: desc.value, options: opts.value.split("\n").map((x) => x.trim()).filter(Boolean), multi: multi.checked, closesAt: fromDate(closes.value) || 0, showResults: show.value } });
		toast("Poll created");
		dlg.close();
		reload();
	});
}

// ---- Reports ----

RENDER.reports = async (root) => {
	let tab = "players";
	let status = "open";
	const tabs = h("div", { class: "tabs", role: "tablist" });
	const body = h("div");
	for (const [t, label] of [["players", "Player reports"], ["bugs", "Bug reports"]]) {
		tabs.append(h("button", { role: "tab", type: "button", "data-t": t, "aria-selected": String(t === tab), onclick: () => {
			tab = t; tabs.querySelectorAll("button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.t === t))); draw();
		} }, label));
	}
	root.append(tabs, body);
	async function draw() {
		if (tab === "players") {
			const { reports } = await velo("staff/reports?status=" + status);
			subtitle(reports.length + (status === "open" ? " open player reports" : " player reports"));
			const filter = seg([["open", "Open"], ["all", "All"]], status, (s) => { status = s; draw(); });
			body.replaceChildren(card("Player reports", "Chat reported by players (DMs and parties)", filter,
				table(["When", "Reported", "By", "Category", "Reason", "Status"], reports.map((r) => [
					when(r.at), h("b", {}, r.targetName || r.target), r.reporterName || "–", h("span", { class: "pill" }, r.category || "–"),
					h("span", { class: "small" }, (r.reason || "").slice(0, 120)),
					h("span", { class: "pill " + (r.status === "open" ? "warn" : r.status === "actioned" ? "bad" : "good") }, r.status)]), (i) => openPlayerReport(reports[i], draw))));
		} else {
			const { reports } = await velo("admin/reports");
			const open = reports.filter((r) => r.status !== "closed" && r.status !== "resolved").length;
			subtitle(open + " open bug reports");
			body.replaceChildren(card("Bug reports", "From the launcher and the game", null, table(["When", "From", "Title", "Kind", "Version", "Status"], reports.map((r) => [
				when(r.createdAt), r.username || "–", h("div", {}, h("b", {}, r.title || "(no title)"), h("div", { class: "muted small" }, (r.message || "").slice(0, 120))),
				h("span", { class: "pill" }, r.groupLabel || r.kind), h("span", { class: "muted small" }, [r.version, r.minecraft].filter(Boolean).join(" · ")),
				h("span", { class: "pill " + (r.status === "open" || r.status === "new" ? "warn" : "good") }, r.status)]), (i) => openReport(reports[i].id, draw))));
		}
	}
	await draw();
};

function openPlayerReport(r, reload) {
	const days = h("select", { "aria-label": "Ban length" }, [[1, "1 day"], [7, "7 days"], [30, "30 days"], [0, "Permanent"]].map(([d, l]) => h("option", { value: d, selected: d === 7 }, l)));
	const note = h("input", { placeholder: "Note (only staff see it)", "aria-label": "Note" });
	const dismiss = h("button", { class: "btn", type: "button" }, "Dismiss");
	const action = h("button", { class: "btn danger", type: "button" }, "Ban from friends & parties");
	const player = h("button", { class: "btn ghost", type: "button" }, "Open player");
	const resolve = (btn, ban) => busy(btn, async () => {
		await velo("staff/reports/resolve", { body: { id: r.id, status: ban ? "actioned" : "dismissed", note: note.value,
			ban: ban ? { services: ["friends", "parties"], days: Number(days.value), reason: "Reported chat" + (r.category ? " (" + r.category + ")" : "") } : null } });
		toast(ban ? "Report actioned, player banned" : "Report dismissed");
		dlg.close();
		reload();
	});
	dismiss.onclick = () => resolve(dismiss, false);
	action.onclick = () => resolve(action, true);
	player.onclick = () => { dlg.close(); openPlayer(r.target); };
	const dlg = dialog("Report against " + (r.targetName || r.target), [
		h("div", { class: "row" }, h("div", {}, h("span", { class: "muted small" }, "Reported by"), h("div", {}, h("b", {}, r.reporterName || "–"))),
			h("div", {}, h("span", { class: "muted small" }, "Where"), h("div", {}, r.context === "party" ? "Party chat" : "Direct messages")),
			h("div", {}, h("span", { class: "muted small" }, "When"), h("div", {}, when(r.at)))),
		r.reason ? h("div", { class: "notice" }, h("b", {}, (r.category || "Reason") + ": "), r.reason) : null,
		h("pre", { class: "logs", style: "height:auto;max-height:46vh" }, (r.messages || []).map((m) =>
			h("span", { class: m.fromName === r.targetName ? "err" : "" }, `[${new Date(m.time || 0).toLocaleString()}] ${m.fromName || "?"}: ${m.text || ""}\n`))),
		r.status === "open" ? h("div", { class: "row" }, note, days) : h("div", { class: "notice" }, "Handled by " + (r.handledBy || "?") + " · " + when(r.handledAt) + " · " + r.status),
	], r.status === "open" ? [player, dismiss, action] : [player], true);
}

async function openReport(id, reload) {
	const r = await velo("admin/reports/get?id=" + encodeURIComponent(id));
	const status = h("select", {}, ["new", "open", "in_progress", "resolved", "closed"].map((x) => h("option", { value: x, selected: r.status === x }, x.replace("_", " "))));
	const save = h("button", { class: "btn primary", type: "button" }, "Set status");
	const del = h("button", { class: "btn danger", type: "button" }, "Delete");
	const details = Object.entries(r).filter(([k, v]) => typeof v !== "object" && !["message", "title"].includes(k));
	const dlg = dialog(r.title || "Report", [
		h("p", { style: "margin:0;white-space:pre-wrap" }, r.message || ""),
		table(["Field", "Value"], details.map(([k, v]) => [k, k.endsWith("At") ? when(v) : String(v)])),
		...Object.entries(r).filter(([, v]) => v && typeof v === "object").map(([k, v]) => h("details", {}, h("summary", { style: "cursor:pointer;font-weight:600" }, k), h("pre", { class: "logs", style: "height:auto;max-height:360px" }, typeof v === "string" ? v : JSON.stringify(v, null, 2)))),
		h("div", { class: "row" }, status, save),
	], [del], true);
	save.onclick = () => busy(save, async () => { await velo("admin/reports/status", { body: { id, status: status.value } }); toast("Status saved"); reload(); });
	del.onclick = async () => {
		if (!(await confirmBox("Delete this report?", "It can't be restored."))) return;
		await velo("admin/reports/delete", { body: { id } });
		toast("Report deleted");
		dlg.close();
		reload();
	};
}

// ---- Addons ----

RENDER.addons = async (root) => {
	const dataRoot = state.me.roots.find((r) => /server/i.test(r.label));
	const draw = async () => {
		let rows = [];
		if (dataRoot) {
			const { entries } = await api(`/api/files/list?root=${dataRoot.id}&path=addons`).catch(() => ({ entries: [] }));
			for (const e of entries.filter((x) => x.name.endsWith(".json"))) {
				const f = await api(`/api/files/read?root=${dataRoot.id}&path=${encodeURIComponent("addons/" + e.name)}`);
				try { rows.push(JSON.parse(f.text)); } catch { /* not ours */ }
			}
		}
		rows.sort((a, b) => String(b.mc).localeCompare(String(a.mc), undefined, { numeric: true }));
		const mc = h("select", { "aria-label": "Minecraft version" }, ["1.21.11", "26.1", "26.2", "26.3"].map((v) => h("option", { value: v }, v)));
		const version = h("input", { placeholder: "Addons version, e.g. 0.9.8", "aria-label": "Version" });
		const file = h("input", { type: "file", accept: ".jar", "aria-label": "Jar file" });
		const up = h("button", { class: "btn primary shrink", type: "button" }, icon("upload"), "Upload");
		up.onclick = () => busy(up, async () => {
			if (!file.files[0] || !version.value.trim()) throw new Error("Pick a jar and enter its version");
			await velo(`admin/addons?mc=${encodeURIComponent(mc.value)}&version=${encodeURIComponent(version.value.trim())}`, { body: file.files[0], headers: { "Content-Type": "application/java-archive" } });
			toast("Uploaded Addons " + version.value + " for " + mc.value);
			draw();
		});
		const sync = h("button", { class: "btn", type: "button" }, icon("refresh"), "Sync from GitHub");
		sync.onclick = () => busy(sync, async () => { const r = await velo("admin/addons/sync", { method: "POST", body: {} }); toast("Synced: " + JSON.stringify(r.stored)); draw(); });
		root.replaceChildren(
			card("Addons on the server", "One jar per Minecraft version - only players you gave access (Players) download it", sync,
				table(["Minecraft", "Version", "num:Size", "Uploaded", "SHA-256"], rows.map((r) => [h("b", {}, r.mc), r.version, bytes(r.size), when(r.uploadedAt), h("span", { class: "mono muted" }, (r.sha256 || "").slice(0, 16) + "…")]))),
			h("div", { class: "stack" }, card("Upload a new build", "Replaces that Minecraft version's jar right away", null, h("div", { class: "row" }, mc, version, file, up))));
	};
	await draw();
};

// ---- Server ----

let sysRange = "1h";
RENDER.server = async (root) => {
	// Built once; every refresh only changes numbers, chart lines and service states in place.
	const tf = (x) => new Date(x).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
	const tiles = {
		cpu: kpi("CPU", "cpu", 0, (v) => v.toFixed(1) + "%", ""),
		mem: kpi("Memory", "memory", 0, bytes, ""),
		net: kpi("Network in", "download", 0, rate, ""),
		disk: kpi("Disk", "disk", 0, bytes, ""),
	};
	const charts = {};
	const chartBox = h("div", { class: "stack" });
	const storage = h("div", { class: "grid", style: "gap:14px" });
	const disks = {};
	const svcBox = h("div", { class: "grid cols-3" });
	const procBody = h("tbody");
	root.append(h("div", { class: "grid kpis" }, Object.values(tiles)), chartBox,
		h("div", { class: "stack" }, card("Storage", "", null, storage)),
		h("div", { class: "stack" }, h("h2", {}, "Services"), svcBox),
		h("div", { class: "stack" }, card("Top processes", "By memory", null, h("div", { class: "table-wrap" }, h("table", {},
			h("thead", {}, h("tr", {}, ["PID", "Name", "num:Memory", "num:CPU time", "Command"].map((x) => h("th", { class: x.startsWith("num:") ? "num" : "" }, x.replace("num:", ""))))),
			procBody)))));

	const buildCharts = (sys) => {
		const hist = sys.history;
		const memTotal = (sys.current || {}).memTotal || 1;
		const pts = (f) => hist.map((x) => [x.ts, x[f]]);
		const rangeSeg = seg([["1h", "1 hour"], ["24h", "24 hours"]], sysRange, (r) => { sysRange = r; draw(true).catch(() => {}); });
		charts.cpu = lineChart({ series: [{ name: "CPU", color: "var(--series-1)", points: pts("cpu") }], yFormat: (v) => v.toFixed(0) + "%", yMax: 100, xFormat: tf });
		charts.mem = lineChart({ series: [{ name: "Used", color: "var(--series-1)", points: pts("memUsed") }], yFormat: bytes, yMax: memTotal, xFormat: tf });
		charts.net = lineChart({ series: [{ name: "In", color: "var(--series-1)", points: pts("rx") }, { name: "Out", color: "var(--series-2)", points: pts("tx") }], yFormat: rate, xFormat: tf });
		charts.disk = lineChart({ series: [{ name: "Read", color: "var(--series-1)", points: pts("diskRead") }, { name: "Write", color: "var(--series-2)", points: pts("diskWrite") }], yFormat: rate, xFormat: tf });
		chartBox.replaceChildren(
			h("div", { class: "grid cols-2" },
				card("CPU", "Share of all cores in use", rangeSeg, charts.cpu),
				card("Memory", "Used (without caches) of " + bytes(memTotal), null, charts.mem)),
			h("div", { class: "grid cols-2" },
				card("Network", "All interfaces but loopback and containers", legend([["In", "var(--series-1)"], ["Out", "var(--series-2)"]]), charts.net),
				card("Disk throughput", "Reads and writes", legend([["Read", "var(--series-1)"], ["Write", "var(--series-2)"]]), charts.disk)));
	};

	const draw = async (rebuild) => {
		const sys = await api("/api/system?range=" + sysRange);
		if (!root.isConnected) return;
		const c = sys.current || { cpu: 0, memUsed: 0, memTotal: 1, rx: 0, tx: 0, swapUsed: 0, swapTotal: 0 };
		setLive(true, "Live · " + sys.hostname);
		subtitle(`${sys.kernel} · ${sys.cores} cores · up ${duration(sys.uptimeSeconds)} · load ${sys.load.map((x) => x.toFixed(2)).join(" / ")}`);
		tiles.cpu.set(c.cpu, sys.cores + " cores · load " + sys.load[0].toFixed(2));
		tiles.mem.set(c.memUsed, "of " + bytes(c.memTotal) + (c.swapTotal ? " · swap " + bytes(c.swapUsed) : ""));
		tiles.net.set(c.rx, "out " + rate(c.tx));
		const d0 = sys.disks[0];
		if (d0) tiles.disk.set(d0.total - d0.free, bytes(d0.free) + " free of " + bytes(d0.total));
		for (const d of sys.disks) {
			const key = d.mount;
			if (!disks[key]) storage.append(disks[key] = meter(d.mount + " (" + d.name + ")", d.total - d.free, d.total));
			else disks[key].set(d.total - d.free, d.total);
		}
		if (rebuild || !charts.cpu) {
			buildCharts(sys);
			return;
		}
		const pts = (f) => sys.history.map((x) => [x.ts, x[f]]);
		charts.cpu.update({ series: [{ name: "CPU", color: "var(--series-1)", points: pts("cpu") }] });
		charts.mem.update({ series: [{ name: "Used", color: "var(--series-1)", points: pts("memUsed") }], yMax: c.memTotal });
		charts.net.update({ series: [{ name: "In", color: "var(--series-1)", points: pts("rx") }, { name: "Out", color: "var(--series-2)", points: pts("tx") }] });
		charts.disk.update({ series: [{ name: "Read", color: "var(--series-1)", points: pts("diskRead") }, { name: "Write", color: "var(--series-2)", points: pts("diskWrite") }] });
	};

	// One card per service, kept; only its pill, numbers and buttons change.
	const svcCards = {};
	const drawServices = async () => {
		const { services } = await api("/api/services");
		if (!root.isConnected) return;
		for (const x of services) {
			let c = svcCards[x.unit];
			if (!c) {
				c = svcCards[x.unit] = { pill: h("span"), mem: h("b"), cpu: h("b"), since: h("b"), actions: h("div", { class: "svc-actions" }), state: null };
				svcBox.append(h("section", { class: "card svc" },
					h("div", { class: "svc-head" }, h("div", {}, h("h3", {}, x.unit), h("span", { class: "muted small" }, x.Description)), c.pill),
					h("div", { class: "svc-stats" },
						h("div", {}, h("small", {}, "Memory"), c.mem), h("div", {}, h("small", {}, "CPU time"), c.cpu), h("div", {}, h("small", {}, "Since"), c.since)),
					c.actions));
			}
			const mem = Number(x.MemoryCurrent);
			const cpu = Number(x.CPUUsageNSec);
			c.mem.textContent = mem > 0 && mem < 1e15 ? bytes(mem) : "–";
			c.cpu.textContent = cpu > 0 && cpu < 1e18 ? duration(cpu / 1e9) : "–";
			c.since.textContent = /^@\d+/.test(x.ActiveEnterTimestamp || "") && x.ActiveState === "active" ? ago(Number(x.ActiveEnterTimestamp.slice(1)) * 1000) : "–";
			if (c.state === x.ActiveState) continue;
			c.state = x.ActiveState;
			c.pill.replaceWith(c.pill = statusPill(x));
			c.actions.replaceChildren(
				x.ActiveState === "active" ? serviceButton(x, "restart", "primary", drawServices) : serviceButton(x, "start", "primary", drawServices),
				x.ActiveState === "active" && !x.self ? serviceButton(x, "stop", "danger", drawServices) : null,
				h("button", { class: "btn small", type: "button", onclick: () => showLogs(x.unit) }, "Logs"));
		}
	};

	// Process rows are reused by position; only cells whose text changed are touched.
	const drawProcs = async () => {
		const { processes } = await api("/api/processes");
		if (!root.isConnected) return;
		processes.forEach((p, i) => {
			const cells = [String(p.pid), p.name, bytes(p.rss), duration(p.cpuSeconds), p.command];
			let tr = procBody.children[i];
			if (!tr) {
				tr = h("tr", {}, h("td", { class: "mono" }), h("td", { style: "font-weight:600" }), h("td", { class: "num" }), h("td", { class: "num" }), h("td", { class: "mono muted", style: "font-size:11.5px" }));
				procBody.append(tr);
			}
			cells.forEach((text, j) => { if (tr.children[j].textContent !== text) tr.children[j].textContent = text; });
		});
		while (procBody.children.length > processes.length) procBody.lastChild.remove();
	};

	await Promise.all([draw(true), drawServices(), drawProcs()]);
	every(5000, () => draw(false).catch(() => setLive(false)));
	every(10000, () => drawServices().catch(() => {}));
	every(15000, () => drawProcs().catch(() => {}));
};

function serviceButton(x, action, cls, refresh) {
	const label = action[0].toUpperCase() + action.slice(1);
	const b = h("button", { class: "btn small " + (cls || ""), type: "button" }, label);
	b.onclick = async () => {
		if (action !== "start" && !(await confirmBox(label + " " + x.unit + "?",
			x.unit === "velo-server" ? "Players lose their connection to Velo for a few seconds." : x.self ? "The panel reloads in a few seconds." : "The service will be " + action + "ed.", label))) return;
		await busy(b, async () => { const r = await api("/api/services/action", { body: { unit: x.unit, action } }); toast(x.unit + ": " + r.result); });
		setTimeout(() => refresh().catch(() => {}), x.self ? 4000 : 800);
	};
	return b;
}

function showLogs(unit) {
	const pre = h("pre", { class: "logs", tabindex: "0" }, "Loading…");
	const grep = h("input", { placeholder: "Filter (e.g. error)", "aria-label": "Filter" });
	const lines = h("select", { "aria-label": "Lines" }, [200, 500, 1000, 2000].map((n) => h("option", { value: n }, n + " lines")));
	const follow = h("input", { type: "checkbox", checked: true });
	const load = async () => {
		const { text } = await api(`/api/logs?unit=${encodeURIComponent(unit)}&lines=${lines.value}&grep=${encodeURIComponent(grep.value)}`);
		const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
		pre.replaceChildren(...text.split("\n").map((l) => h("span", { class: /error|exception|severe|failed/i.test(l) ? "err" : /warn/i.test(l) ? "warn" : "" }, l + "\n")));
		if (atBottom || follow.checked) pre.scrollTop = pre.scrollHeight;
	};
	grep.addEventListener("keydown", (e) => { if (e.key === "Enter") load(); });
	lines.onchange = load;
	const dlg = dialog("Logs · " + unit, [h("div", { class: "row" }, grep, lines, h("label", { class: "check shrink" }, follow, "Follow")), pre], null, true);
	load().catch((e) => pre.replaceChildren(e.message));
	const id = setInterval(() => { if (follow.checked && !document.hidden) load().catch(() => {}); }, 3000);
	dlg.addEventListener("close", () => clearInterval(id));
}

// ---- Files ----

const fileState = { root: null, path: "" };
RENDER.files = async (root) => {
	const roots = state.me.roots;
	if (!roots.length) {
		root.append(h("div", { class: "notice" }, "No folders are configured (PANEL_FILE_ROOTS)."));
		return;
	}
	if (!fileState.root || !roots.some((r) => r.id === fileState.root)) fileState.root = roots[0].id;
	const draw = async () => {
		const r = roots.find((x) => x.id === fileState.root);
		const { entries } = await api(`/api/files/list?root=${r.id}&path=${encodeURIComponent(fileState.path)}`);
		const parts = fileState.path ? fileState.path.split("/") : [];
		const crumbs = h("div", { class: "crumbs" }, h("button", { type: "button", onclick: () => { fileState.path = ""; draw(); } }, r.label),
			parts.map((p, i) => [h("span", {}, "/"), h("button", { type: "button", onclick: () => { fileState.path = parts.slice(0, i + 1).join("/"); draw(); } }, p)]));
		const rootSel = h("select", { "aria-label": "Folder" }, roots.map((x) => h("option", { value: x.id, selected: x.id === r.id }, x.label + (x.readOnly ? " (read-only)" : ""))));
		rootSel.onchange = () => { fileState.root = rootSel.value; fileState.path = ""; draw(); };
		const join = (name) => (fileState.path ? fileState.path + "/" : "") + name;
		const upload = h("input", { type: "file", multiple: true, class: "sr-only", id: "up" });
		upload.onchange = async () => {
			for (const f of upload.files) {
				await api(`/api/files/upload?root=${r.id}&path=${encodeURIComponent(join(f.name))}`, { body: f, headers: { "Content-Type": "application/octet-stream" } }).then(() => toast("Uploaded " + f.name), (e) => toast(e.message, "err"));
			}
			draw();
		};
		const actions = r.readOnly ? [] : [
			h("label", { class: "btn small", for: "up" }, icon("upload"), "Upload"), upload,
			h("button", { class: "btn small", type: "button", onclick: async () => {
				const name = prompt("New folder name");
				if (!name) return;
				await api("/api/files/mkdir", { body: { root: r.id, path: join(name) } }).catch((e) => toast(e.message, "err"));
				draw();
			} }, icon("plus"), "Folder"),
			h("button", { class: "btn small", type: "button", onclick: () => openFile(r, join(prompt("New file name") || ""), draw, true) }, icon("file"), "New file"),
		];
		root.replaceChildren(
			h("div", { class: "notice", style: "margin-bottom:16px" }, "Edits go live straight away. The Velo server keeps some files in memory (social.json, staff.json, store.json…) - restart velo-server after editing those, and download a backup first."),
			card("Files", r.path, h("div", { style: "display:flex;gap:8px;flex-wrap:wrap;align-items:center" }, rootSel, ...actions),
				h("div", { style: "margin-bottom:12px" }, crumbs),
				table(["Name", "num:Size", "Modified", "Owner", ""], entries.map((e) => {
					const dl = e.dir ? null : h("a", { class: "icon-btn", href: `/api/files/download?root=${r.id}&path=${encodeURIComponent(join(e.name))}`, "aria-label": "Download " + e.name, onclick: (ev) => ev.stopPropagation() }, icon("download"));
					const ren = r.readOnly ? null : h("button", { class: "btn small ghost", type: "button", onclick: async (ev) => {
						ev.stopPropagation();
						const to = prompt("Rename to", e.name);
						if (!to || to === e.name) return;
						await api("/api/files/rename", { body: { root: r.id, from: join(e.name), to: join(to) } }).then(() => toast("Renamed"), (x) => toast(x.message, "err"));
						draw();
					} }, "Rename");
					const del = r.readOnly ? null : h("button", { class: "btn small danger", type: "button", onclick: async (ev) => {
						ev.stopPropagation();
						if (!(await confirmBox("Delete " + e.name + "?", e.dir ? "The folder and everything in it will be deleted." : "The file will be deleted. This can't be undone."))) return;
						await api("/api/files/delete", { body: { root: r.id, path: join(e.name), recursive: e.dir } }).then(() => toast("Deleted " + e.name), (x) => toast(x.message, "err"));
						draw();
					} }, "Delete");
					return [h("span", { class: "file-name" + (e.dir ? " dir" : "") }, icon(e.dir ? "files" : "file"), h("b", {}, e.name), e.link ? h("span", { class: "pill" }, "link") : null),
						e.dir ? "–" : bytes(e.size), when(e.modified), h("span", { class: "muted small" }, e.owner || ""), h("span", { style: "display:flex;gap:6px;justify-content:flex-end" }, dl, ren, del)];
				}), (i) => { const e = entries[i]; if (e.dir) { fileState.path = join(e.name); draw(); } else openFile(r, join(e.name), draw); })));
	};
	await draw();
};

async function openFile(r, path, reload, create) {
	if (!path || path.endsWith("/")) return;
	let f = { text: "", editable: !r.readOnly, size: 0, modified: null };
	if (!create) f = await api(`/api/files/read?root=${r.id}&path=${encodeURIComponent(path)}`).catch((e) => { toast(e.message, "err"); return null; });
	if (!f) return;
	const download = h("a", { class: "btn", href: `/api/files/download?root=${r.id}&path=${encodeURIComponent(path)}` }, icon("download"), "Download");
	if (f.text === null) {
		dialog(path, h("p", { class: "muted", style: "margin:0" }, `Binary or too large to edit (${bytes(f.size)}).`), [download]);
		return;
	}
	const editor = h("textarea", { class: "editor", spellcheck: "false", readonly: !f.editable, "aria-label": "File content" }, f.text);
	editor.addEventListener("keydown", (e) => {
		if (e.key === "Tab") { e.preventDefault(); const p = editor.selectionStart; editor.setRangeText("\t", p, editor.selectionEnd, "end"); }
		if ((e.ctrlKey || e.metaKey) && e.key === "s") { e.preventDefault(); save.click(); }
	});
	const info = h("span", { class: "muted small", style: "margin-right:auto" }, (create ? "New file" : bytes(f.size) + " · " + when(f.modified)) + (path.endsWith(".json") ? " · JSON is checked before saving" : ""));
	const save = h("button", { class: "btn primary", type: "button", disabled: !f.editable }, "Save");
	save.onclick = () => busy(save, async () => {
		if (path.endsWith(".json")) {
			try { JSON.parse(editor.value); } catch (e) { throw new Error("Not valid JSON: " + e.message); }
		}
		await api("/api/files/write", { body: { root: r.id, path, text: editor.value, expectedModified: create ? null : f.modified } });
		const fresh = await api(`/api/files/read?root=${r.id}&path=${encodeURIComponent(path)}`);
		f.modified = fresh.modified;
		create = false;
		toast("Saved " + path);
		reload();
	});
	dialog(path, editor, [info, create ? null : download, save].filter(Boolean), true);
}

// ---- Activity ----

RENDER.activity = async (root) => {
	const { entries } = await api("/api/audit?limit=300");
	root.append(card("Activity log", "Every sign-in and every change made in the panel", null, table(["When", "Who", "Address", "Action", "Detail"], entries.map((e) => [
		when(e.time), h("b", {}, e.user), h("span", { class: "mono muted" }, e.ip),
		h("span", { class: "pill " + (/failed|locked|reused/.test(e.action + e.detail) ? "bad" : /sign-in/.test(e.action) ? "good" : "") }, e.action),
		h("span", { class: "muted small", style: "word-break:break-all" }, e.detail)]))));
};

// ---- Account ----

RENDER.account = async (root) => {
	const me = await api("/api/me");
	const all = h("button", { class: "btn danger", type: "button" }, icon("logout"), "Sign out everywhere");
	all.onclick = async () => {
		if (!(await confirmBox("Sign out everywhere?", "Every session of this account ends, this one too."))) return;
		await api("/api/logout-all", { method: "POST" });
		renderLogin("Signed out on every device.");
	};
	root.append(
		card("Signed in as " + me.username, "Sessions end after 30 minutes without activity and after 12 hours at most", all,
			table(["Address", "Browser", "Signed in", "Last active"], me.sessions.map((x) => [h("span", { class: "mono" }, x.ip), h("span", { class: "muted small" }, x.userAgent), when(x.createdAt), ago(x.lastSeen)]))),
		h("div", { class: "stack" }, card("Security", "", null, h("ul", { style: "margin:0;padding-left:18px;color:var(--text-2);display:grid;gap:6px" },
			h("li", {}, "Sign-in needs your password and a one-time code from your authenticator app; each code works once."),
			h("li", {}, "5 failed tries block the network for 15 minutes; 10 lock the account for 15 minutes."),
			h("li", {}, "Add or reset an account on the server: sudo velo-panel-adduser <name>."),
			h("li", {}, "Every change is written to the activity log.")))));
};

boot();


// ---- Moderation: monitoring, clips, worlds, custom capes, staff log ----

const reasonPill = (c) => h("span", { class: "pill " + (["inappropriate", "harassment", "scam"].includes(c) ? "bad" : "warn") }, c || "other");
const blockSelect = () => h("select", { "aria-label": "Also block" }, [["", "Don't block"], [7, "Block 7 days"], [30, "Block 30 days"], [0, "Block for good"]]
	.map(([v, l]) => h("option", { value: v }, l)));

RENDER.monitor = async (root) => {
	const kpis = h("div", { class: "grid kpis" });
	const rows = h("div", { class: "stack" });
	root.append(kpis, rows);
	const ui = {};
	const draw = async () => {
		const o = await velo("staff/overview");
		if (!root.isConnected) return;
		setLive(true, `Live · ${o.online.game} in game`);
		subtitle("Updated " + new Date().toLocaleTimeString());
		const openReports = o.reports.clips + o.reports.worlds + o.reports.chat;
		if (!ui.kpi) {
			ui.kpi = {
				online: kpi("Players online", "online", o.online.players, fmt, `${o.online.game} game · ${o.online.launcher} launcher · ${o.online.web} web`, true),
				worlds: kpi("Hosted worlds", "worlds", o.worlds.open, fmt, `${o.worlds.public} public · ${o.worlds.guests} guests`),
				clips: kpi("Shared clips", "clips", o.clips.kept, fmt, bytes(o.clips.usedBytes) + " of " + bytes(o.clips.maxBytes)),
				reports: kpi("Open reports", "reports", openReports, fmt, `${o.reports.clips} clips · ${o.reports.worlds} worlds · ${o.reports.chat} chat`),
				capes: kpi("Custom capes", "capes", o.capes.custom, fmt, `${o.capes.animated} animated · ${o.capes.unreviewed} not reviewed`),
				bugs: kpi("New bug reports", "file", o.reports.bugs, fmt, "From the game and the launcher"),
			};
			kpis.replaceChildren(...Object.values(ui.kpi));
		} else {
			ui.kpi.online.set(o.online.players, `${o.online.game} game · ${o.online.launcher} launcher · ${o.online.web} web`);
			ui.kpi.worlds.set(o.worlds.open, `${o.worlds.public} public · ${o.worlds.guests} guests`);
			ui.kpi.clips.set(o.clips.kept, bytes(o.clips.usedBytes) + " of " + bytes(o.clips.maxBytes));
			ui.kpi.reports.set(openReports, `${o.reports.clips} clips · ${o.reports.worlds} worlds · ${o.reports.chat} chat`);
			ui.kpi.capes.set(o.capes.custom, `${o.capes.animated} animated · ${o.capes.unreviewed} not reviewed`);
			ui.kpi.bugs.set(o.reports.bugs);
		}
		const m = o.machine;
		rows.replaceChildren(h("div", { class: "grid cols-2" },
			card("Velo server", `Up ${duration(m.uptimeMs / 1000)} · ${m.threads} threads · load ${m.load >= 0 ? m.load.toFixed(2) : "–"} on ${m.cpus} CPUs`, null,
				h("div", { class: "grid", style: "gap:14px" }, meter("Java heap", m.heapUsed, m.heapMax), meter("Clip storage", o.clips.usedBytes, o.clips.maxBytes),
					h("p", { class: "muted small" }, bytes(m.diskFree) + " free on the data disk"))),
			card("Needs a look", "Everything waiting for staff", null, h("div", { class: "grid", style: "gap:10px" },
				[["#/clips", "Clip reports", o.reports.clips], ["#/worlds", "World reports", o.reports.worlds], ["#/reports", "Chat reports", o.reports.chat],
					["#/capes", "Custom capes to review", o.capes.unreviewed], ["#/reports", "New bug reports", o.reports.bugs]].map(([href, label, n]) =>
					h("a", { class: "svc-head", href }, h("span", { style: "font-weight:600" }, label), h("span", { class: "pill " + (n ? "warn" : "good") }, n ? fmt(n) + " open" : "All clear")))))),
			h("div", { class: "grid cols-2" },
				card("World hosting relay", "Traffic through the relay for worlds open right now", null, h("div", { class: "value-big" }, bytes(o.worlds.relayBytes))),
				card("Clips", `${fmt(o.clips.uploadsTotal)} shared ever · kept ${o.clips.retentionDays} days`, h("a", { class: "btn small", href: "#/clips" }, "Open"),
					h("p", { class: "muted small" }, `${fmt(o.clips.last24h)} today · ${fmt(o.clips.last7d)} this week · ${fmt(o.clips.last30d)} this month · ${fmt(o.clips.views)} views`))));
	};
	await draw();
	every(15000, () => draw().catch(() => setLive(false, "Velo server unreachable")));
};

RENDER.clips = async (root) => {
	let tab = "reports";
	let status = "open";
	let search = "";
	const tabs = h("div", { class: "tabs", role: "tablist" });
	const stats = h("div", { class: "stack" });
	const body = h("div");
	for (const [t, label] of [["reports", "Reports"], ["all", "All shared clips"]]) {
		tabs.append(h("button", { role: "tab", type: "button", "data-t": t, "aria-selected": String(t === tab), onclick: () => {
			tab = t; tabs.querySelectorAll("button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.t === t))); draw();
		} }, label));
	}
	root.append(stats, tabs, body);
	const drawStats = (st) => {
		stats.replaceChildren(h("div", { class: "grid kpis" },
			kpi("Clips kept", "clips", st.kept, fmt, `${fmt(st.uploaders)} players · ${fmt(st.views)} views`, true),
			kpi("Storage used", "disk", st.usedBytes, bytes, "of " + bytes(st.maxBytes) + " · " + bytes(st.diskFree) + " disk free"),
			kpi("Shared ever", "trend", st.uploadsTotal, fmt, bytes(st.uploadedBytesTotal) + " uploaded in total"),
			kpi("Last 7 days", "upload", st.last7d, fmt, `${fmt(st.last24h)} in the last 24 h · ${fmt(st.last30d)} in 30 days`),
			kpi("Removed", "trash", st.expired + st.evicted + st.removedByStaff, fmt, `${fmt(st.expired)} expired (${st.retentionDays} d) · ${fmt(st.evicted)} for space · ${fmt(st.removedByStaff)} by staff`),
			kpi("Expiring this week", "refresh", st.expiringThisWeek, fmt, `Clips are deleted ${st.retentionDays} days after sharing`)),
			h("div", { class: "grid cols-2" },
				card("Clips shared per day", "Last 30 days", null, barChart({ name: "Clips", data: st.byDay.map((d) => ({ label: dayLabel(d.day), value: d.clips })) })),
				card("Who uses the most space", "Top 10 by storage", null, table(["Player", "num:Clips", "num:Space"], st.topUploaders.map((u) =>
					[h("div", { class: "row", style: "gap:8px;align-items:center;flex-wrap:nowrap" }, playerHead(u.uuid, u.username), u.username), fmt(u.clips), bytes(u.bytes)]),
					(i) => { search = st.topUploaders[i].username; tab = "all"; tabs.querySelectorAll("button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.t === "all"))); draw(); }))));
	};
	async function draw() {
		const data = await velo("staff/clips?limit=200&q=" + encodeURIComponent(search));
		if (!root.isConnected) return;
		drawStats(data.stats);
		if (tab === "reports") {
			const { reports } = await velo("staff/moderation?kind=clip&status=" + status);
			subtitle(reports.length + (status === "open" ? " open clip reports" : " clip reports"));
			const filter = seg([["open", "Open"], ["all", "All"]], status, (x) => { status = x; draw(); });
			body.replaceChildren(card("Reported clips", "Watch it, then remove it or dismiss the report. One decision closes every report on that clip.", filter,
				table(["When", "Clip", "Owner", "Reason", "Reports", "Status"], reports.map((r) => [
					when(r.at), h("b", {}, r.title || r.target), r.ownerName || "–", h("div", {}, reasonPill(r.category), r.reason ? h("div", { class: "muted small" }, r.reason.slice(0, 120)) : null),
					fmt(r.reportsOnTarget), h("span", { class: "pill " + (r.status === "open" ? "warn" : r.status === "removed" ? "bad" : "good") }, r.exists || r.status !== "open" ? r.status : "deleted")]),
					(i) => openClip(reports[i].clip, reports[i], draw))));
		} else {
			const box = h("input", { type: "search", placeholder: "Search a player, title or clip id", value: search, "aria-label": "Search clips" });
			box.addEventListener("keydown", (e) => { if (e.key === "Enter") { search = box.value.trim(); draw(); } });
			subtitle(data.clips.length + " clips");
			body.replaceChildren(card("Shared clips", "Newest first", box, h("div", { class: "clip-grid" }, data.clips.length ? data.clips.map((c) =>
				h("button", { class: "clip-card", type: "button", onclick: () => openClip(c, null, draw) },
					c.thumb ? h("img", { src: c.url + ".jpg", alt: "", loading: "lazy" }) : h("div", { class: "clip-blank" }, icon("clips")),
					h("div", { class: "clip-meta" }, h("b", {}, c.title), h("span", { class: "muted small" }, `${c.username} · ${Math.round(c.seconds)}s · ${bytes(c.size)} · ${ago(c.createdAt)}`),
						c.reports ? h("span", { class: "pill bad" }, c.reports + " reports") : null))) : h("p", { class: "muted" }, "No clips"))));
		}
	}
	await draw();
};

function openClip(c, report, reload) {
	const note = h("input", { placeholder: "Note (only staff see it)", "aria-label": "Note" });
	const block = blockSelect();
	const remove = h("button", { class: "btn danger", type: "button" }, icon("trash"), "Remove clip");
	const dismiss = h("button", { class: "btn", type: "button" }, "Dismiss reports");
	const open = report && report.status === "open";
	remove.onclick = () => busy(remove, async () => {
		const blockDays = block.value === "" ? null : Number(block.value);
		if (report) await velo("staff/moderation/resolve", { body: { kind: "clip", target: report.target, action: "remove", note: note.value, blockDays } });
		else await velo("staff/clips/remove", { body: { id: c.id, note: note.value, blockDays } });
		toast("Clip removed");
		dlg.close();
		reload();
	});
	dismiss.onclick = () => busy(dismiss, async () => {
		await velo("staff/moderation/resolve", { body: { kind: "clip", target: report.target, action: "dismiss", note: note.value } });
		toast("Reports dismissed");
		dlg.close();
		reload();
	});
	const dlg = dialog(c ? c.title : (report.title || "Clip"), [
		c ? h("video", { class: "clip-video", src: c.url + ".mp4", poster: c.thumb ? c.url + ".jpg" : null, controls: true, autoplay: true, playsinline: true })
			: h("div", { class: "notice" }, "This clip was already deleted."),
		c ? h("div", { class: "row" },
			h("div", {}, h("span", { class: "muted small" }, "Clipped by"), h("div", {}, h("b", {}, c.username))),
			h("div", {}, h("span", { class: "muted small" }, "Shared"), h("div", {}, when(c.createdAt))),
			h("div", {}, h("span", { class: "muted small" }, "Deleted on its own"), h("div", {}, when(c.expiresAt))),
			h("div", {}, h("span", { class: "muted small" }, "Size"), h("div", {}, `${bytes(c.size)} · ${c.height}p${c.fps || ""} · ${fmt(c.views)} views`))) : null,
		report ? h("div", { class: "notice" }, h("b", {}, report.category + ": "), report.reason || "(no reason given)", h("div", { class: "muted small" }, "Reported by " + report.reporterName + " · " + when(report.at))) : null,
		report && !open ? h("div", { class: "notice" }, "Handled by " + (report.handledBy || "?") + " · " + when(report.handledAt) + " · " + report.status) : null,
		c ? h("div", { class: "row" }, note, block) : null,
		c ? h("a", { class: "btn small ghost", href: c.url, target: "_blank", rel: "noopener" }, "Open share page") : null,
	], c ? (open ? [dismiss, remove] : [remove]) : (open ? [dismiss] : []), true);
}

RENDER.worlds = async (root) => {
	const body = h("div", { class: "stack" });
	root.append(body);
	const draw = async () => {
		const [{ worlds, relayBytes }, { reports }] = await Promise.all([velo("staff/worlds"), velo("staff/moderation?kind=world&status=open")]);
		if (!root.isConnected) return;
		setLive(true, `Live · ${worlds.length} worlds open`);
		subtitle(`${worlds.filter((w) => w.visibility !== "friends").length} public · ${worlds.length} open · ${bytes(relayBytes)} through the relay`);
		const visPill = (v) => h("span", { class: "pill " + (v === "public" ? "red" : v === "request" ? "blue" : "") }, v === "request" ? "Ask to join" : v === "public" ? "Public" : "Friends");
		body.replaceChildren(
			card("Reported worlds", "Players reported these while they were listed", null, table(["When", "World", "Host", "Reason", "Reports", "Live"], reports.map((r) => [
				when(r.at), h("b", {}, r.title), r.ownerName, h("div", {}, reasonPill(r.category), r.reason ? h("div", { class: "muted small" }, r.reason.slice(0, 120)) : null),
				fmt(r.reportsOnTarget), r.exists ? h("span", { class: "pill good" }, "Open") : h("span", { class: "pill" }, "Closed")]), (i) => openWorld(reports[i].world, reports[i], draw))),
			card("Open worlds", "Every world hosted right now, busiest first", null, table(["World", "Host", "Visibility", "num:Players", "num:Visitors", "num:Traffic", "Open since", "Reports"],
				worlds.map((w) => [h("div", {}, h("b", {}, w.name), w.description ? h("div", { class: "muted small" }, w.description) : null),
					h("div", { class: "row", style: "gap:8px;align-items:center;flex-wrap:nowrap" }, playerHead(w.host, w.hostName), w.hostName), visPill(w.visibility),
					`${w.guests}/${w.maxPlayers}`, fmt(w.visitors), bytes(w.bytes), ago(w.openedAt), w.reports ? h("span", { class: "pill bad" }, w.reports) : "–"]),
				(i) => openWorld(worlds[i], null, draw))));
	};
	await draw();
	every(15000, () => draw().catch(() => {}));
};

function openWorld(w, report, reload) {
	const note = h("input", { placeholder: "Reason (the host is told)", "aria-label": "Reason" });
	const block = blockSelect();
	const down = h("button", { class: "btn danger", type: "button" }, "Take down");
	const dismiss = h("button", { class: "btn", type: "button" }, "Dismiss reports");
	const open = report && report.status === "open";
	down.onclick = async () => {
		if (!(await confirmBox("Take this world down?", "The host and every guest are disconnected and it leaves Discover."))) return;
		await busy(down, async () => {
			const blockDays = block.value === "" ? null : Number(block.value);
			if (report) await velo("staff/moderation/resolve", { body: { kind: "world", target: report.target, action: "remove", note: note.value, blockDays } });
			else await velo("staff/worlds/takedown", { body: { id: w.id, note: note.value, blockDays } });
			toast("World taken down");
			dlg.close();
			reload();
		});
	};
	dismiss.onclick = () => busy(dismiss, async () => {
		await velo("staff/moderation/resolve", { body: { kind: "world", target: report.target, action: "dismiss", note: note.value } });
		toast("Reports dismissed");
		dlg.close();
		reload();
	});
	const dlg = dialog(w ? w.name : report.title, [
		w ? h("div", { class: "row" },
			h("div", {}, h("span", { class: "muted small" }, "Host"), h("div", {}, h("b", {}, w.hostName))),
			h("div", {}, h("span", { class: "muted small" }, "Version"), h("div", {}, w.version || "–")),
			h("div", {}, h("span", { class: "muted small" }, "Players"), h("div", {}, `${w.guests}/${w.maxPlayers} now · ${w.visitors} visited`)),
			h("div", {}, h("span", { class: "muted small" }, "Mods"), h("div", {}, fmt(w.mods)))) : h("div", { class: "notice" }, "This world isn't open any more."),
		w && w.description ? h("p", { style: "margin:0" }, w.description) : null,
		report ? h("div", { class: "notice" }, h("b", {}, report.category + ": "), report.reason || "(no reason given)", h("div", { class: "muted small" }, "Reported by " + report.reporterName + " · " + when(report.at))) : null,
		w ? h("div", { class: "row" }, note, block) : null,
	], w ? (open ? [dismiss, down] : [down]) : (open ? [dismiss] : []), true);
}

RENDER.capes = async (root) => {
	let filter = "unreviewed";
	const body = h("div");
	root.append(body);
	const draw = async () => {
		const data = await velo("staff/capes?filter=" + filter);
		if (!root.isConnected) return;
		subtitle(`${fmt(data.total)} custom capes · ${fmt(data.animated)} animated`);
		const pick = seg([["unreviewed", "To review"], ["animated", "Animated"], ["all", "All"]], filter, (f) => { filter = f; draw(); });
		body.replaceChildren(card("Custom capes", "Uploaded by players (animated ones are Velo+). Keep marks one reviewed; Remove also bans that exact image.", pick,
			h("div", { class: "clip-grid" }, data.capes.length ? data.capes.map((c) => {
				const keep = h("button", { class: "btn small", type: "button" }, "Keep");
				const del = h("button", { class: "btn small danger", type: "button" }, "Remove");
				keep.onclick = () => busy(keep, async () => { await velo("staff/capes/review", { body: { uuid: c.uuid, action: "keep" } }); toast("Marked reviewed"); draw(); });
				del.onclick = async () => {
					if (!(await confirmBox("Remove " + c.username + "'s cape?", "It's taken off and that image can't be uploaded again."))) return;
					await busy(del, async () => { await velo("staff/capes/review", { body: { uuid: c.uuid, action: "remove" } }); toast("Cape removed"); draw(); });
				};
				return h("div", { class: "clip-card cape-card" },
					h("img", { class: "cape-art", src: "/api/velo/cape/" + c.hash, alt: "Cape of " + c.username, loading: "lazy" }),
					h("div", { class: "clip-meta" }, h("b", {}, c.username), h("span", { class: "muted small" }, (c.animated ? "Animated · " : "") + bytes(c.size) + (c.at ? " · " + ago(c.at) : "")),
						h("div", { class: "row", style: "gap:6px" }, c.reviewed ? h("span", { class: "pill good" }, "Reviewed") : keep, del)));
			}) : h("p", { class: "muted" }, "Nothing to review"))));
	};
	await draw();
};

// Partner servers: the whole list is saved at once (in order), like the launcher's Owner panel does.
const partnerFields = (p) => ({ id: p.id || null, name: p.name, address: p.address, description: p.description || "", versions: p.versions || "", accent: p.accent || null });
const partnerIcon = (p, size = 256) => p.icon
	? h("img", { class: "partner-icon" + (p.iconSize > 64 ? " hd" : ""), src: "/api/velo/partners/icon/" + p.id + "?s=" + size + "&v=" + p.icon, alt: "" })
	: h("div", { class: "partner-icon blank" });

function editPartner(p, list, save) {
	const isNew = !p;
	p = p || { name: "", address: "", description: "", versions: "", accent: null };
	const name = h("input", { value: p.name, maxlength: 40, placeholder: "Example Network" });
	const address = h("input", { value: p.address, maxlength: 259, placeholder: "play.example.net" });
	const desc = h("textarea", { maxlength: 140, placeholder: "One line about the server (shown in Discover)" }, p.description || "");
	const versions = h("input", { value: p.versions || "", maxlength: 30, placeholder: "1.21.11 - 26.2" });
	const useAccent = h("input", { type: "checkbox", checked: !!p.accent });
	const accent = h("input", { type: "color", value: p.accent || "#e5484d", "aria-label": "Card colour" });
	const ok = h("button", { class: "btn primary", type: "button" }, isNew ? "Add partner" : "Save");
	const dlg = dialog(isNew ? "New partner server" : p.name, [
		h("div", { class: "row" }, field("Name", name), field("Server address", address)),
		field("Description", desc),
		h("div", { class: "row" }, field("Versions (shown as is)", versions),
			h("label", {}, "Card colour", h("div", { class: "row", style: "gap:10px;align-items:center" }, h("label", { class: "check" }, useAccent, "Own colour"), accent))),
		h("p", { class: "muted small", style: "margin:0" }, "Players join by this address straight from the title screen and Discover. After adding it you can upload a high-resolution icon."),
	], [h("button", { class: "btn ghost", type: "button", onclick: () => dlg.close() }, "Cancel"), ok]);
	ok.onclick = () => busy(ok, async () => {
		const entry = { ...partnerFields(p), name: name.value.trim(), address: address.value.trim(), description: desc.value.trim(), versions: versions.value.trim(),
			accent: useAccent.checked ? accent.value : null };
		if (!entry.name || !entry.address) throw new Error("A partner needs a name and an address");
		const next = list.map(partnerFields);
		if (isNew) next.push(entry);
		else next[list.indexOf(p)] = entry;
		await save(next, isNew ? "Added " + entry.name : "Saved " + entry.name);
		dlg.close();
	});
	name.focus();
}

function partnerIconDialog(p, reload) {
	const file = h("input", { type: "file", accept: "image/png,image/jpeg", class: "sr-only" });
	const preview = h("div", { class: "partner-preview" }, partnerIcon(p, 512));
	const info = h("p", { class: "muted small", style: "margin:0" }, p.customIcon
		? "Your upload, " + p.iconSize + "×" + p.iconSize + "."
		: p.icon ? "The server's own icon (" + p.iconSize + "×" + p.iconSize + ", from its server list ping)." : "No icon yet - the server doesn't send one.");
	const pick = h("button", { class: "btn primary", type: "button", onclick: () => file.click() }, icon("upload"), "Upload icon");
	const reset = p.customIcon ? h("button", { class: "btn ghost", type: "button" }, "Use the server's icon") : null;
	const dlg = dialog("Icon · " + p.name, [preview, info,
		h("p", { class: "muted small", style: "margin:0" }, "Square PNG or JPEG, up to 4 MB. It's kept at up to 512×512 (server list icons are only 64×64) and every client gets a sharp copy at the size it draws."), file],
		[reset, pick]);
	file.onchange = () => busy(pick, async () => {
		const f = file.files[0];
		if (!f) return;
		const size = await new Promise((resolve) => { const img = new Image(); img.onload = () => resolve([img.naturalWidth, img.naturalHeight]); img.onerror = () => resolve(null); img.src = URL.createObjectURL(f); });
		if (!size) throw new Error("That isn't a PNG or JPEG image");
		if (Math.min(...size) < 128) toast("That image is only " + size[0] + "×" + size[1] + " - 512×512 looks best", "warn");
		await velo("admin/partners/icon?id=" + encodeURIComponent(p.id), { body: f, headers: { "Content-Type": "application/octet-stream" } });
		toast("Icon saved");
		dlg.close();
		reload();
	});
	if (reset) reset.onclick = () => busy(reset, async () => {
		await velo("admin/partners/icon?id=" + encodeURIComponent(p.id), { body: new Blob([]), headers: { "Content-Type": "application/octet-stream" } });
		toast("Using the server's own icon");
		dlg.close();
		reload();
	});
}

RENDER.partners = async (root) => {
	const body = h("div");
	root.append(body);
	let partners = [];
	const save = async (next, message) => {
		partners = (await velo("admin/partners", { body: { partners: next } })).partners;
		toast(message);
		draw();
	};
	const load = async () => {
		partners = (await velo("admin/partners")).partners;
		draw();
	};
	const draw = () => {
		if (!root.isConnected) return;
		subtitle(partners.length + " partner server" + (partners.length === 1 ? "" : "s") + " · shown on the title screen and in Discover");
		const add = h("button", { class: "btn primary small", type: "button", onclick: () => editPartner(null, partners, save) }, "Add partner");
		const refresh = h("button", { class: "btn ghost small", type: "button", onclick: (e) => busy(e.currentTarget, load) }, icon("refresh"), "Refresh");
		const move = (p, by) => (e) => busy(e.currentTarget, async () => {
			const next = partners.map(partnerFields);
			const i = partners.indexOf(p);
			[next[i], next[i + by]] = [next[i + by], next[i]];
			await save(next, "Order saved");
		});
		const rows = partners.map((p, i) => {
			const iconBtn = h("button", { class: "icon-tile", type: "button", title: "Change icon", "aria-label": "Change the icon of " + p.name, onclick: () => partnerIconDialog(p, load) }, partnerIcon(p, 128));
			const up = h("button", { class: "btn small ghost", type: "button", disabled: i === 0, "aria-label": "Move up", onclick: move(p, -1) }, "↑");
			const down = h("button", { class: "btn small ghost", type: "button", disabled: i === partners.length - 1, "aria-label": "Move down", onclick: move(p, 1) }, "↓");
			const edit = h("button", { class: "btn small", type: "button", onclick: () => editPartner(p, partners, save) }, "Edit");
			const del = h("button", { class: "btn small danger", type: "button" }, "Remove");
			del.onclick = async () => {
				if (!(await confirmBox("Remove " + p.name + "?", "It disappears from the title screen and Discover for everyone (its uploaded icon is deleted too).", "Remove"))) return;
				busy(del, () => save(partners.filter((x) => x !== p).map(partnerFields), "Removed " + p.name));
			};
			return [iconBtn,
				h("div", {}, h("b", {}, p.name, p.accent ? h("span", { class: "accent-dot", style: "background:" + p.accent, title: p.accent }) : null),
					h("div", { class: "muted small" }, p.address + (p.versions ? " · " + p.versions : "")),
					p.description ? h("div", { class: "muted small" }, p.description) : null),
				h("div", {}, p.online ? h("span", { class: "pill good" }, h("i"), `${fmt(p.players)}/${fmt(p.max)} online`) : h("span", { class: "pill bad" }, h("i"), "Not answering"),
					h("div", { class: "muted small" }, p.customIcon ? "HD icon " + p.iconSize + "px" : p.icon ? "Server icon " + p.iconSize + "px" : "No icon")),
				h("div", { class: "row", style: "gap:6px;flex-wrap:nowrap" }, up, down, edit, del)];
		});
		body.replaceChildren(card("Partner servers", "Order here is the order players see. Click an icon to upload a high-resolution one.", h("div", { class: "row", style: "gap:6px" }, refresh, add),
			partners.length ? table(["Icon", "Server", "Status", ""], rows) : h("p", { class: "muted" }, "No partner servers yet - add the first one.")));
	};
	await load();
};

RENDER.updates = async (root) => {
	const p = await velo("admin/update-policy");
	const min = h("input", { value: p.minimum || "", placeholder: "0.9.91", "aria-label": "Minimum version" });
	const msg = h("input", { value: p.message || "", maxlength: 200, placeholder: "What the update fixes (shown in the dialog)" });
	const save = h("button", { class: "btn primary", type: "button" }, "Save");
	const clear = h("button", { class: "btn ghost", type: "button" }, "Don't require anything");
	const now = h("p", { class: "muted small", style: "margin:0" });
	const show = (x) => { now.textContent = x.minimum ? "Launchers older than " + x.minimum + " open the update dialog on start and before every Play." : "Nothing is required - updates show in the launcher's bell only."; subtitle(x.minimum ? "Required: " + x.minimum + " or newer" : "No required update"); };
	show(p);
	save.onclick = () => busy(save, async () => { const x = await velo("admin/update-policy", { body: { minimum: min.value.trim(), message: msg.value.trim() } }); show(x); toast("Saved"); });
	clear.onclick = () => busy(clear, async () => { const x = await velo("admin/update-policy", { body: { minimum: "", message: "" } }); min.value = ""; msg.value = ""; show(x); toast("Nothing required any more"); });
	root.append(card("Required update", "For hotfixes everyone needs. Set it only after the GitHub release has its installers - players get pushed to the newest release.", null,
		h("div", { class: "row" }, field("Minimum version", min), field("Note", msg)), now, h("div", { class: "row", style: "gap:8px" }, save, clear),
		h("p", { class: "muted small", style: "margin:0" }, "Works for launchers 0.9.91 and newer. World Hosting always requires 0.9.91 (older versions couldn't connect).")));
};

RENDER.stafflog = async (root) => {
	const { log } = await velo("staff/log");
	const blocks = await velo("staff/blocks");
	subtitle(log.length + " recent staff actions");
	const blockRows = (list, what) => list.map((b) => {
		const lift = h("button", { class: "btn small ghost", type: "button" }, "Lift");
		lift.onclick = () => busy(lift, async () => { await velo("staff/blocks/set", { body: { what, target: b.uuid, days: -1 } }); toast("Block lifted"); route(document.querySelector(".side"), document.getElementById("main")); });
		return [b.username, what === "clip" ? "Clip sharing" : "Public worlds", b.reason, b.by, b.until ? when(b.until) : "Until lifted", lift];
	});
	root.append(
		card("Blocks", "Players stopped from sharing clips or listing public worlds", null,
			table(["Player", "From", "Reason", "By", "Until", ""], [...blockRows(blocks.clips, "clip"), ...blockRows(blocks.worlds, "world")])),
		card("Staff log", "Every removal, takedown, dismissal and block", null,
			table(["When", "Who", "Did", "What", "Note"], log.map((a) => [when(a.at), h("b", {}, a.by), a.action, a.target, h("span", { class: "muted small" }, a.note || "")]))));
};


// ---- Socials: TikTok / YouTube Shorts / Instagram Reels views ----

const PLATFORM_NAMES = { tiktok: "TikTok", youtube: "YouTube", instagram: "Instagram" };

RENDER.socials = async (root) => {
	const input = h("input", { type: "url", placeholder: "TikTok profile, YouTube channel, Instagram profile or a single video / Reel link", class: "grow" });
	const addBtn = h("button", { class: "btn primary", type: "button" }, "Track");
	const refreshBtn = h("button", { class: "btn", type: "button" }, "Refresh now");
	const body = h("div", { class: "stack" });
	const igInput = h("input", { type: "password", placeholder: "or paste an Instagram access token", class: "grow", autocomplete: "off" });
	const igBtn = h("button", { class: "btn", type: "button" }, "Connect Instagram");
	const igState = h("small", { class: "muted" });
	const igLogin = h("button", { class: "btn primary", type: "button" }, "Log in with Instagram");
	igLogin.onclick = () => busy(igLogin, async () => { location.href = (await velo("admin/panel/socials/edit", { body: { instagramLogin: true } })).url; });
	root.append(card("Track your videos", "YouTube channels and your connected Instagram pick up new videos by themselves · add TikToks by video link · numbers refresh every 3 hours", null,
		h("div", { class: "row" }, input, addBtn, refreshBtn), h("div", { class: "row" }, igLogin, igInput, igBtn), igState), body);
	const igResult = new URLSearchParams(location.hash.split("?")[1] || "").get("instagram");
	if (igResult) {
		toast(igResult === "ok" ? "Instagram connected - Reels show up in a minute" : igResult);
		history.replaceState(null, "", "#/socials");
	}
	const draw = (d) => {
		const t = d.totals;
		const ig = d.instagram || {};
		igState.textContent = ig.connected ? `Instagram connected as @${ig.username}` + (ig.error ? " · " + ig.error : " · Reels are picked up by themselves")
			: "Instagram blocks tools, so Reels need your account's token (Instagram API with Instagram Login) - it renews itself";
		igBtn.textContent = ig.connected ? "Replace token" : "Use token";
		igLogin.textContent = ig.connected ? "Log in again" : "Log in with Instagram";
		igLogin.hidden = !ig.login;
		subtitle(d.lastRun ? "Last measured " + ago(d.lastRun) + (d.running ? " · measuring now..." : "") : "Not measured yet - add a profile or video");
		const pf = d.platforms || {};
		const share = (k) => (t.views ? Math.round(((pf[k] || {}).views || 0) / t.views * 100) : 0);
		const kpis = h("div", { class: "grid kpis" },
			kpi("Views", "socials", t.views, compact, "+" + compact(t.views24h) + " in 24 h", true),
			kpi("Likes", "cosmetics", t.likes, compact, "+" + compact(t.likes24h) + " in 24 h"),
			kpi("Comments", "players", t.comments, compact, ""),
			kpi("Shares", "socials", t.shares, compact, "TikTok + Instagram"),
			kpi("Videos", "socials", t.videos, fmt, `TikTok ${share("tiktok")}% · YouTube ${share("youtube")}% · Instagram ${share("instagram")}% of views`));
		const chart = card("Total views", "All tracked videos, last 30 days", null,
			lineChart({ series: [{ name: "Views", color: "#ff4d57", points: d.curve }], yFormat: compact, xFormat: (x) => new Date(x).toLocaleDateString() }));
		const accounts = card("Tracked profiles", d.accounts.length ? "" : "None yet - add your TikTok, YouTube or Instagram profile above", null,
			d.accounts.length ? table(["Platform", "Profile", "Checked", ""], d.accounts.map((a) => [
				h("span", { class: "chip" }, icon(a.platform), PLATFORM_NAMES[a.platform] || a.platform),
				h("a", { href: a.url, target: "_blank", rel: "noopener" }, a.name || a.url),
				a.error ? h("span", { class: "bad" }, a.error) : a.checkedAt ? ago(a.checkedAt) + (a.note ? " · " + a.note : "") : "pending",
				removeBtn(a.url)])) : null);
		const rows = d.videos.map((v) => {
			const er = v.views ? ((v.likes + v.comments + (v.shares || 0)) / v.views * 100).toFixed(1) + "%" : "-";
			const thumb = v.platform === "youtube" && v.url ? youtubeThumb(v.url) : null;
			return [
				h("div", { class: "vid" }, thumb ? h("img", { src: thumb, alt: "", loading: "lazy" }) : h("span", { class: "vid-ph" }, icon(v.platform)),
					h("div", {}, h("a", { href: v.url, target: "_blank", rel: "noopener" }, v.title || v.url), h("small", { class: "muted" },
						(PLATFORM_NAMES[v.platform] || v.platform) + (v.published ? " · " + ago(v.published) : "") + (v.error ? " · " + v.error : "")))),
				v.views != null ? compact(v.views) : "-", v.views24h != null ? "+" + compact(v.views24h) : "-",
				v.likes != null ? compact(v.likes) : "-", v.comments != null ? compact(v.comments) : "-", v.shares ? compact(v.shares) : "-", er, removeBtn(v.id)];
		});
		const videos = card("Videos", "Sorted by views · engagement = (likes + comments + shares) / views", null,
			table(["Video", "num:Views", "num:24 h", "num:Likes", "num:Comments", "num:Shares", "num:Engagement", ""], rows));
		body.replaceChildren(kpis, chart, accounts, videos);
	};
	const removeBtn = (key) => {
		const b = h("button", { class: "btn small ghost", type: "button", title: "Stop tracking" }, "Remove");
		b.onclick = (e) => { e.stopPropagation(); busy(b, async () => draw(await velo("admin/panel/socials/edit", { body: { remove: key } }))); };
		return b;
	};
	const youtubeThumb = (url) => { const m = url.match(/(?:shorts\/|v=|youtu\.be\/)([A-Za-z0-9_-]{11})/); return m ? `https://i.ytimg.com/vi/${m[1]}/mqdefault.jpg` : null; };
	const load = async () => draw(await velo("admin/panel/socials"));
	addBtn.onclick = () => busy(addBtn, async () => {
		if (!input.value.trim()) return;
		draw(await velo("admin/panel/socials/edit", { body: { url: input.value.trim() } }));
		input.value = "";
		toast("Tracking - first numbers in a minute");
		setTimeout(() => load().catch(() => {}), 45_000);
	});
	input.addEventListener("keydown", (e) => { if (e.key === "Enter") addBtn.click(); });
	igBtn.onclick = () => busy(igBtn, async () => {
		if (!igInput.value.trim()) return;
		draw(await velo("admin/panel/socials/edit", { body: { instagramToken: igInput.value.trim() } }));
		igInput.value = "";
		toast("Instagram connected - Reels show up in a minute");
		setTimeout(() => load().catch(() => {}), 45_000);
	});
	refreshBtn.onclick = () => busy(refreshBtn, async () => { draw(await velo("admin/panel/socials/edit", { body: { refresh: true } })); toast("Measuring - give it a minute"); });
	await load();
	state.timers.push(setInterval(() => load().catch(() => {}), 60_000));
};
