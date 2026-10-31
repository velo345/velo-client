/* Velo Staff panel (staff.velo-client.com). Plain JS, no dependencies. Every value from the server goes into the page as text (never innerHTML). */
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
	tickets: "M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z M10 5v12",
	chat: "M4 5h16v11H9l-5 4z M8 9h8 M8 12h5",
	cloaks: "M7 3h10l2 18-7-4-7 4z",
	punishments: "M12 3l8 3v6c0 4.5-3.4 8.2-8 9-4.6-.8-8-4.5-8-9V6z M9 12l2 2 4-4",
	team: "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M2 21c0-3.9 3.1-6.5 7-6.5s7 2.6 7 6.5 M16 3.5a4 4 0 0 1 0 7.5 M18.5 14.5c2 .7 3.5 2.6 3.5 6.5",
	phone: "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M11 18h2",
	launcher: "M4 4h16v12H4z M8 20h8 M12 16v4 M9 9l2 2 4-4",
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
	notes: "M6 3h9l4 4v14H6z M15 3v4h4 M9 11h7 M9 15h7 M9 19h4",
	worlds: "M12 3a9 9 0 1 0 .01 0 M3 12h18 M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9 M12 3C9.5 5.6 8.2 8.6 8.2 12s1.3 6.4 3.8 9",
	capes: "M7 3h10l2 18-7-4-7 4z",
	updates: "M20 11a8 8 0 1 0-2.3 5.7 M20 4v7h-7 M12 8v5l3 2",
	partners: "M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M2 20c0-3 2.7-5 6-5s6 2 6 5 M14 15.3c.6-.2 1.3-.3 2-.3 3.3 0 6 2 6 5",
	stafflog: "M5 3h11l3 3v15H5z M9 9h6 M9 13h6 M9 17h4",
	play: "M7 4l13 8-13 8z",
	trash: "M4 7h16 M9 7V4h6v3 M6 7l1 13h10l1-13",
	key: "M8 11a4 4 0 1 0 .01 0 M10.8 12.2 20 3 M17 6l2.5 2.5 M14.5 8.5l2 2",
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
	if (res.status === 401 && !path.startsWith("/v1/staffpanel/")) {
		state.me = null;
		renderLogin("You were signed out - sign in again.");
		throw new ApiError(401, "Signed out");
	}
	const type = res.headers.get("Content-Type") || "";
	const data = type.includes("json") ? await res.json().catch(() => ({})) : await res.text();
	if (!res.ok) throw new ApiError(res.status, (data && data.error) || "HTTP " + res.status);
	return data;
}
const velo = (path, opts) => api("/v1/" + path, opts);

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
		if (e.status !== 401 && e.name !== "NotAllowedError" && e.name !== "AbortError" && e.name !== "InvalidStateError") toast(e.message, "err");
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
	? h("img", { class: "head", src: `/v1/skins/${uuid}/head.png`, alt: "", width: size, height: size, style: `width:${size}px;height:${size}px`, loading: "lazy",
		onerror: (e) => e.target.replaceWith(initials(name)) })
	: initials(name);

function errorBox(e) {
	return h("div", { class: "error-box" }, e.message || String(e));
}


// ---- Moderation: clips, worlds, custom cloaks (shared with the owner panel) ----

const RENDER = {};

const reasonPill = (c) => h("span", { class: "pill " + (["inappropriate", "harassment", "scam"].includes(c) ? "bad" : "warn") }, c || "other");
const blockSelect = () => h("select", { "aria-label": "Also block" }, [["", "Don't block"], [7, "Block 7 days"], [30, "Block 30 days"], [0, "Block for good"]]
	.map(([v, l]) => h("option", { value: v }, l)));

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

// ---- World Notes ----

RENDER.notes = async (root) => {
	let tab = "reports";
	let status = "open";
	let search = "";
	const tabs = h("div", { class: "tabs", role: "tablist" });
	const body = h("div");
	for (const [t, label] of [["reports", "Reports"], ["all", "All shared notes"]]) {
		tabs.append(h("button", { role: "tab", type: "button", "data-t": t, "aria-selected": String(t === tab), onclick: () => {
			tab = t; tabs.querySelectorAll("button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.t === t))); draw();
		} }, label));
	}
	root.append(tabs, body);
	async function draw() {
		if (tab === "reports") {
			const { reports } = await velo("staff/moderation?kind=note&status=" + status);
			if (!root.isConnected) return;
			subtitle(reports.length + (status === "open" ? " open note reports" : " note reports"));
			const filter = seg([["open", "Open"], ["all", "All"]], status, (x) => { status = x; draw(); });
			body.replaceChildren(card("Reported notes", "Read it, then remove it or dismiss the report. One decision closes every report on that note.", filter,
				table(["When", "Note", "Owner", "Reason", "Reports", "Status"], reports.map((r) => [
					when(r.at), h("b", {}, r.title || r.target), r.ownerName || "–",
					h("div", {}, reasonPill(r.category), r.reason ? h("div", { class: "muted small" }, r.reason.slice(0, 120)) : null),
					fmt(r.reportsOnTarget), h("span", { class: "pill " + (r.status === "open" ? "warn" : r.status === "removed" ? "bad" : "good") }, r.exists || r.status !== "open" ? r.status : "deleted")]),
					(i) => openNote(reports[i].target, reports[i], draw))));
		} else {
			const data = await velo("staff/notes?limit=300&q=" + encodeURIComponent(search));
			if (!root.isConnected) return;
			subtitle(`${fmt(data.total)} shared notes · ${fmt(data.openReports)} open reports`);
			const box = h("input", { type: "search", placeholder: "Search a player, title, text, server or note id", value: search, "aria-label": "Search notes" });
			box.addEventListener("keydown", (e) => { if (e.key === "Enter") { search = box.value.trim(); draw(); } });
			body.replaceChildren(card("Shared notes", "Newest change first", box,
				table(["Updated", "Note", "Owner", "Server", "Shared", "Has", "Reports"], data.notes.map((n) => [
					ago(n.updated), h("div", {}, h("b", {}, n.title || "(untitled)"), n.preview ? h("div", { class: "muted small" }, n.preview) : null),
					h("div", { class: "player" }, playerHead(n.owner, n.ownerName), n.ownerName), h("span", { class: "muted small" }, n.server.replace(/^mp:/, "")),
					n.share === "friends" ? "All friends" : fmt(n.sharedWith) + " people",
					[n.checks ? n.checks + " checks" : null, n.strokes ? "drawing" : null, n.image ? "picture" : null].filter(Boolean).join(" · ") || "–",
					n.reports ? h("span", { class: "pill bad" }, n.reports) : "–"]),
					(i) => openNote(data.notes[i].id, null, draw))));
		}
	}
	await draw();
};

/** Draws a note's whiteboard strokes (256x160 board) onto a canvas. */
function boardCanvas(strokes) {
	const c = h("canvas", { width: 512, height: 320, class: "note-board", style: "width:100%;max-width:512px;border-radius:10px;background:#f7f4ea" });
	const g = c.getContext("2d");
	g.scale(2, 2);
	g.lineCap = "round";
	g.lineJoin = "round";
	for (const st of strokes || []) {
		const argb = st.c >>> 0;
		g.strokeStyle = (argb >>> 24) === 0 ? "#f7f4ea" : "#" + (argb & 0xffffff).toString(16).padStart(6, "0");
		g.lineWidth = st.w;
		g.beginPath();
		for (let i = 0; i + 1 < st.p.length; i += 2) (i === 0 ? g.moveTo : g.lineTo).call(g, st.p[i], st.p[i + 1]);
		if (st.p.length === 2) g.lineTo(st.p[0] + 0.1, st.p[1]);
		g.stroke();
	}
	return c;
}

async function openNote(id, report, reload) {
	let view = null;
	try {
		view = await velo("staff/notes/view?id=" + encodeURIComponent(id));
	} catch {
		view = null;
	}
	const n = view && view.note;
	const reason = h("input", { placeholder: "Reason (shown to the owner if blocked)", "aria-label": "Reason" });
	const block = blockSelect();
	const del = h("button", { class: "btn danger", type: "button" }, "Remove note");
	const dismiss = h("button", { class: "btn", type: "button" }, "Dismiss reports");
	const open = report && report.status === "open";
	del.onclick = async () => {
		if (!(await confirmBox("Remove this note?", "It disappears for the owner and everyone it was shared with."))) return;
		await busy(del, async () => {
			const blockDays = block.value === "" ? null : Number(block.value);
			if (report) await velo("staff/moderation/resolve", { body: { kind: "note", target: id, action: "remove", note: reason.value, blockDays } });
			else await velo("staff/notes/remove", { body: { id, note: reason.value, blockDays } });
			toast("Note removed");
			dlg.close();
			reload();
		});
	};
	dismiss.onclick = () => busy(dismiss, async () => {
		await velo("staff/moderation/resolve", { body: { kind: "note", target: id, action: "dismiss", note: reason.value } });
		toast("Reports dismissed");
		dlg.close();
		reload();
	});
	const where = n ? `${n.server.replace(/^mp:/, "")} · ${(n.dim || "").replace("minecraft:", "")} ${Math.round(n.x)}, ${Math.round(n.y)}, ${Math.round(n.z)}` : "";
	const reports = view ? view.reports : [];
	const dlg = dialog(n ? (n.title || "Note") : (report ? report.title : "Note"), [
		n ? h("div", { class: "row" },
			h("div", {}, h("span", { class: "muted small" }, "Owner"), h("div", { class: "player" }, playerHead(n.owner, n.ownerName), h("b", {}, n.ownerName))),
			h("div", {}, h("span", { class: "muted small" }, "Where"), h("div", {}, where)),
			h("div", {}, h("span", { class: "muted small" }, "Shared with"), h("div", {}, n.share === "friends" ? "All their friends" : (Object.values(n.names || {}).join(", ") || "nobody"))),
			h("div", {}, h("span", { class: "muted small" }, "Last change"), h("div", {}, (n.editorName || n.ownerName) + " · " + ago(n.updated)))) : h("div", { class: "notice" }, "This note doesn't exist any more."),
		n && n.text ? h("pre", { style: "white-space:pre-wrap;margin:0;font:inherit;background:var(--panel-2,rgba(255,255,255,.04));padding:12px;border-radius:10px" }, n.text) : null,
		n && n.checks && n.checks.length ? h("ul", { style: "margin:0;padding-left:18px" }, n.checks.map((c) => h("li", {}, (c.done ? "☑ " : "☐ ") + c.text + (c.doneBy ? " — " + c.doneBy : "")))) : null,
		n && n.board && n.board.length ? boardCanvas(n.board) : null,
		view && view.png ? h("img", { src: "data:image/png;base64," + view.png, alt: "Picture on the note", style: "max-width:100%;border-radius:10px;image-rendering:pixelated" }) : null,
		reports.length ? h("div", { class: "stack" }, reports.slice(0, 6).map((r) => h("div", { class: "notice" }, reasonPill(r.category), " ", r.reason || "(no reason given)",
			h("div", { class: "muted small" }, "Reported by " + r.reporterName + " · " + when(r.at) + " · " + r.status)))) : null,
		n ? h("div", { class: "row" }, reason, block) : null,
	], n ? (open ? [dismiss, del] : [del]) : (open ? [dismiss] : []), true);
}

RENDER.cloaks = async (root) => {
	let filter = "unreviewed";
	const body = h("div");
	root.append(body);
	const draw = async () => {
		const data = await velo("staff/capes?filter=" + filter);
		if (!root.isConnected) return;
		subtitle(`${fmt(data.total)} custom cloaks · ${fmt(data.animated)} animated`);
		const pick = seg([["unreviewed", "To review"], ["animated", "Animated"], ["all", "All"]], filter, (f) => { filter = f; draw(); });
		body.replaceChildren(card("Custom cloaks", "Uploaded by players (animated ones are Velo+). Keep marks one reviewed; Remove also bans that exact image.", pick,
			h("div", { class: "clip-grid" }, data.capes.length ? data.capes.map((c) => {
				const keep = h("button", { class: "btn small", type: "button" }, "Keep");
				const del = h("button", { class: "btn small danger", type: "button" }, "Remove");
				keep.onclick = () => busy(keep, async () => { await velo("staff/capes/review", { body: { uuid: c.uuid, action: "keep" } }); toast("Marked reviewed"); draw(); });
				del.onclick = async () => {
					if (!(await confirmBox("Remove " + c.username + "'s cloak?", "It's taken off and that image can't be uploaded again."))) return;
					await busy(del, async () => { await velo("staff/capes/review", { body: { uuid: c.uuid, action: "remove" } }); toast("Cloak removed"); draw(); });
				};
				return h("div", { class: "clip-card cape-card" },
					h("img", { class: "cape-art", src: "/v1/cape/" + c.hash, alt: "Cloak of " + c.username, loading: "lazy" }),
					h("div", { class: "clip-meta" }, h("b", {}, c.username), h("span", { class: "muted small" }, (c.animated ? "Animated · " : "") + bytes(c.size) + (c.at ? " · " + ago(c.at) : "")),
						h("div", { class: "row", style: "gap:6px" }, c.reviewed ? h("span", { class: "pill good" }, "Reviewed") : keep, del)));
			}) : h("p", { class: "muted" }, "Nothing to review"))));
	};
	await draw();
};


// ---- Velo Staff: sign-in, setup, pages ----

const RANKS = { helper: 0, mod: 1, admin: 2, manager: 3 };
const ROLE_NAMES = { helper: "Helper", mod: "Moderator", admin: "Admin", manager: "Manager" };
const rank = () => RANKS[state.me && state.me.role] ?? -1;
const sp = (path, opts) => api("/v1/staffpanel/" + path, opts);
const rolePill = (role) => h("span", { class: "pill role-" + (role || "none") }, ROLE_NAMES[role] || "Not on the team");

/** Solves the server's proof-of-work challenge (the invisible bot check). */
async function solveChallenge() {
	const c = await sp("challenge");
	const enc = new TextEncoder();
	for (let n = 0; n <= c.maxnumber; n++) {
		const digest = await crypto.subtle.digest("SHA-256", enc.encode(c.salt + n));
		const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
		if (hex === c.challenge) return { salt: c.salt, number: n, challenge: c.challenge, signature: c.signature };
	}
	throw new Error("Couldn't finish the security check - reload the page");
}

// ---- Passkeys ----

const b64u = {
	enc: (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""),
	dec: (x) => Uint8Array.from(atob(x.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((x.length + 3) % 4)), (c) => c.charCodeAt(0)),
};
const cancelled = (x) => x && (x.name === "NotAllowedError" || x.name === "AbortError");

async function passkeySignIn() {
	const o = await sp("passkey/login/options", { body: {} });
	const cred = await navigator.credentials.get({ publicKey: {
		challenge: b64u.dec(o.challenge), rpId: o.rpId, timeout: o.timeout, userVerification: o.userVerification, allowCredentials: [],
	} });
	const r = cred.response;
	await sp("passkey/login", { body: {
		id: cred.id, clientDataJSON: b64u.enc(r.clientDataJSON), authenticatorData: b64u.enc(r.authenticatorData), signature: b64u.enc(r.signature),
	} });
}

async function passkeyAdd() {
	const o = await sp("passkey/register/options", { body: {} });
	const cred = await navigator.credentials.create({ publicKey: {
		challenge: b64u.dec(o.challenge), rp: o.rp, timeout: o.timeout, attestation: o.attestation,
		user: { id: b64u.dec(o.user.id), name: o.user.name, displayName: o.user.displayName },
		pubKeyCredParams: o.pubKeyCredParams, authenticatorSelection: o.authenticatorSelection,
		excludeCredentials: o.exclude.map((id) => ({ type: "public-key", id: b64u.dec(id) })),
	} });
	await sp("passkey/register", { body: {
		name: deviceName(), clientDataJSON: b64u.enc(cred.response.clientDataJSON), attestationObject: b64u.enc(cred.response.attestationObject),
	} });
}

function deviceName() {
	const ua = navigator.userAgent;
	const os = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android" : /Mac/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "Device";
	const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "";
	return browser ? os + " · " + browser : os;
}

// ---- Unlock pattern (3x3, mouse and touch) ----

/** A pattern pad; calls onDone("0-4-8-...") when the finger lifts. {@code el.reset()} clears it, {@code el.flash(kind)} colours it. */
function patternPad(onDone) {
	const size = 240, gap = size / 3;
	const svg = s("svg", { viewBox: `0 0 ${size} ${size}`, class: "pattern-pad", role: "img", "aria-label": "Unlock pattern - draw it by connecting the dots" });
	const lines = s("g", { class: "pp-lines" });
	const live = s("line", { class: "pp-live", x1: 0, y1: 0, x2: 0, y2: 0 });
	const dots = [];
	svg.append(lines, live);
	for (let i = 0; i < 9; i++) {
		const cx = gap / 2 + (i % 3) * gap, cy = gap / 2 + Math.floor(i / 3) * gap;
		const ring = s("circle", { class: "pp-ring", cx, cy, r: 22 });
		const dot = s("circle", { class: "pp-dot", cx, cy, r: 7 });
		svg.append(ring, dot);
		dots.push({ cx, cy, ring, dot });
	}
	let path = [];
	let drawing = false;
	const point = (e) => {
		const r = svg.getBoundingClientRect();
		return { x: (e.clientX - r.left) * size / r.width, y: (e.clientY - r.top) * size / r.height };
	};
	const hit = (p) => dots.findIndex((d) => (d.cx - p.x) ** 2 + (d.cy - p.y) ** 2 < 30 * 30);
	const add = (i) => {
		if (i < 0 || path.includes(i)) return;
		const last = path[path.length - 1];
		// Passing over a dot between two picked ones picks it too, like phone lock screens.
		if (last !== undefined) {
			const mid = (last + i) / 2;
			if (Number.isInteger(mid) && (last % 3 + i % 3) % 2 === 0 && (Math.floor(last / 3) + Math.floor(i / 3)) % 2 === 0 && !path.includes(mid)) add2(mid, last);
			lines.append(s("line", { x1: dots[path[path.length - 1]].cx, y1: dots[path[path.length - 1]].cy, x2: dots[i].cx, y2: dots[i].cy }));
		}
		path.push(i);
		dots[i].ring.classList.add("on");
		dots[i].dot.classList.add("on");
		if (navigator.vibrate) navigator.vibrate(8);
	};
	const add2 = (i, from) => {
		lines.append(s("line", { x1: dots[from].cx, y1: dots[from].cy, x2: dots[i].cx, y2: dots[i].cy }));
		path.push(i);
		dots[i].ring.classList.add("on");
		dots[i].dot.classList.add("on");
	};
	svg.addEventListener("pointerdown", (e) => {
		e.preventDefault();
		svg.reset();
		drawing = true;
		svg.setPointerCapture(e.pointerId);
		add(hit(point(e)));
	});
	svg.addEventListener("pointermove", (e) => {
		if (!drawing) return;
		const p = point(e);
		add(hit(p));
		if (path.length) {
			const d = dots[path[path.length - 1]];
			live.setAttribute("x1", d.cx); live.setAttribute("y1", d.cy); live.setAttribute("x2", p.x); live.setAttribute("y2", p.y);
		}
	});
	const end = () => {
		if (!drawing) return;
		drawing = false;
		live.setAttribute("x2", live.getAttribute("x1")); live.setAttribute("y2", live.getAttribute("y1"));
		if (path.length) onDone(path.join("-"));
	};
	svg.addEventListener("pointerup", end);
	svg.addEventListener("pointercancel", end);
	svg.reset = () => {
		path = [];
		lines.replaceChildren();
		svg.classList.remove("ok", "bad");
		dots.forEach((d) => { d.ring.classList.remove("on"); d.dot.classList.remove("on"); });
	};
	svg.flash = (kind) => { svg.classList.add(kind); if (kind === "bad") setTimeout(() => svg.reset(), 700); };
	return svg;
}

// ---- QR code (for the authenticator app) ----

function qrSvg(text) {
	const qr = qrcode(0, "M");
	qr.addData(text);
	qr.make();
	const n = qr.getModuleCount();
	const svg = s("svg", { viewBox: `-2 -2 ${n + 4} ${n + 4}`, class: "qr", role: "img", "aria-label": "QR code for your authenticator app", "shape-rendering": "crispEdges" });
	svg.append(s("rect", { x: -2, y: -2, width: n + 4, height: n + 4, fill: "#fff" }));
	let d = "";
	for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (qr.isDark(y, x)) d += `M${x} ${y}h1v1h-1z`;
	svg.append(s("path", { d, fill: "#0b0b0e" }));
	return svg;
}

// ---- Sign-in screens ----

function authFrame(title, sub, ...body) {
	state.timers.forEach(clearInterval);
	state.timers = [];
	document.title = "Velo Staff";
	$app.replaceChildren(h("main", { class: "login" }, h("div", { class: "login-card" },
		h("div", { class: "login-brand" }, h("img", { src: "/logo.png", alt: "" }), h("div", {}, h("b", {}, "VELO STAFF"), h("span", {}, "staff.velo-client.com"))),
		title ? h("h1", { class: "auth-title" }, title) : null, sub ? h("p", { class: "auth-sub" }, sub) : null, body)));
}

function renderLogin(message) {
	let method = "password";
	const err = h("div", { class: "error-box", hidden: !message }, message || "");
	const fail = (x) => { err.textContent = x.message; err.hidden = false; };

	const passkey = h("button", { class: "btn primary block big", type: "button", hidden: !window.PublicKeyCredential }, icon("key"), "Sign in with passkey");
	passkey.onclick = async () => {
		err.hidden = true;
		try {
			await busy(passkey, passkeySignIn);
			await boot();
		} catch (x) {
			if (!cancelled(x)) fail(x);
		}
	};

	const name = h("input", { id: "n", autocomplete: "username", maxlength: 16, required: true, placeholder: "Your Minecraft name" });
	const pass = h("input", { id: "p", type: "password", autocomplete: "current-password", placeholder: "Password" });
	let pattern = null;
	const pad = patternPad((p) => { pattern = p; code.focus(); });
	const code = h("input", { id: "c", class: "code-input", inputmode: "numeric", autocomplete: "one-time-code", pattern: "[0-9 ]{6,7}", maxlength: 7, placeholder: "000000" });
	const secretBox = h("div");
	const drawSecret = () => secretBox.replaceChildren(method === "password"
		? h("label", { for: "p" }, "Password", pass)
		: h("div", { class: "pad-wrap" }, h("span", { class: "label-text" }, "Unlock pattern"), pad));
	drawSecret();
	const submit = h("button", { class: "btn block", type: "submit" }, "Sign in");
	const form = h("form", {},
		h("label", { for: "n" }, "Minecraft name", name),
		seg([["password", "Password"], ["pattern", "Pattern"]], method, (m) => { method = m; drawSecret(); }),
		secretBox,
		h("label", { for: "c" }, "Code from your authenticator app", code),
		submit);
	form.addEventListener("submit", async (e) => {
		e.preventDefault();
		err.hidden = true;
		try {
			await busy(submit, async () => sp("login", { body: { name: name.value.trim(), method, secret: method === "password" ? pass.value : pattern,
				code: code.value, pow: (await powPromise) || (await solveChallenge()) } }));
			pass.value = "";
			await boot();
		} catch (x) {
			fail(x);
			code.value = "";
			pad.reset();
			pattern = null;
			powPromise = solveChallenge().catch(() => null);
		}
	});
	let powPromise = solveChallenge().catch(() => null);
	const first = h("button", { class: "btn ghost block", type: "button", onclick: () => renderFirstSignIn() }, icon("launcher"), "New device? Get a code in your launcher");
	authFrame("Sign in", null, err, passkey, h("div", { class: "or" }, h("span", {}, "or")), form, first, installHint());
}

/** The first sign-in: a code in the launcher. */
function renderFirstSignIn(prefill) {
	const err = h("div", { class: "error-box", hidden: true });
	const name = h("input", { id: "fn", autocomplete: "username", maxlength: 16, placeholder: "Your Minecraft name", value: prefill || "" });
	const send = h("button", { class: "btn primary block", type: "submit" }, icon("launcher"), "Send a code to my launcher");
	const form = h("form", {}, h("label", { for: "fn" }, "Minecraft name", name), send);
	form.addEventListener("submit", async (e) => {
		e.preventDefault();
		err.hidden = true;
		if (!name.value.trim()) return;
		try {
			await busy(send, async () => sp("request-code", { body: { name: name.value.trim(), pow: await solveChallenge() } }));
			renderCodeEntry(name.value.trim());
		} catch (x) {
			err.textContent = x.message;
			err.hidden = false;
		}
	});
	authFrame("Set up your sign-in", "Your Velo launcher proves who you are once. Then you pick a passkey, a password or a pattern for next time.",
		err, form,
		h("ol", { class: "steps" },
			h("li", {}, "Open the Velo launcher and sign in with the account that's on the team."),
			h("li", {}, "Ask for a code here - it pops up at the top of your launcher."),
			h("li", {}, "Type it in and choose how you want to sign in.")),
		h("p", { class: "hint" }, "Already set up somewhere else? Sign out of the staff panel in your launcher first (Profile → Connections → Staff panel). One device per account, so nobody can share a sign-in."),
		h("button", { class: "btn ghost block", type: "button", onclick: () => renderLogin() }, "Back to sign-in"));
}

function renderCodeEntry(name) {
	const err = h("div", { class: "error-box", hidden: true });
	const code = h("input", { id: "cc", class: "code-input big-code", inputmode: "numeric", autocomplete: "one-time-code", maxlength: 7, placeholder: "000 000" });
	const go = h("button", { class: "btn primary block", type: "submit" }, "Continue");
	const form = h("form", {}, h("label", { for: "cc" }, "Code from your launcher", code), go);
	form.addEventListener("submit", async (e) => {
		e.preventDefault();
		err.hidden = true;
		try {
			await busy(go, async () => sp("redeem", { body: { name, code: code.value, pow: await solveChallenge() } }));
			await boot();
		} catch (x) {
			err.textContent = x.message;
			err.hidden = false;
			code.value = "";
		}
	});
	authFrame("Check your launcher", `If ${name} is on the team and isn't set up yet, the code is now at the top of that launcher. It works once, for 5 minutes.`,
		err, form,
		h("p", { class: "hint" }, "Older launchers show it as \"Your website sign-in code\" - that's the one, type it here."),
		h("button", { class: "btn ghost block", type: "button", onclick: () => renderFirstSignIn(name) }, "Didn't get one? Try again"));
	code.focus();
}

/** Choosing sign-in methods right after the launcher code. */
async function renderSetup() {
	const me = await sp("me");
	state.me = me;
	state.csrf = me.csrf;
	const m = me.methods;
	const err = h("div", { class: "error-box", hidden: true });
	const fail = (x) => { if (!cancelled(x)) { err.textContent = x.message; err.hidden = false; } };
	const done = (ok) => h("span", { class: "pill " + (ok ? "good" : "") }, ok ? "Done" : "Not yet");

	const addKey = h("button", { class: "btn primary", type: "button", hidden: !window.PublicKeyCredential }, icon("key"), m.passkeys.length ? "Add another passkey" : "Add a passkey");
	addKey.onclick = async () => { try { await busy(addKey, passkeyAdd); toast("Passkey added"); renderSetup(); } catch (x) { fail(x); } };
	const secretBtn = h("button", { class: "btn", type: "button", onclick: () => secretDialog("password", renderSetup) }, m.password ? "Change password" : "Set a password");
	const patternBtn = h("button", { class: "btn", type: "button", onclick: () => secretDialog("pattern", renderSetup) }, m.pattern ? "Change pattern" : "Draw a pattern");
	const totpBtn = h("button", { class: "btn", type: "button", onclick: () => totpDialog(renderSetup) }, m.totp ? "Replace authenticator" : "Connect authenticator app");
	const finish = h("button", { class: "btn primary block big", type: "button", disabled: !me.complete }, "Finish and open the panel");
	finish.onclick = async () => { try { await busy(finish, () => sp("setup/finish", { body: {} })); await boot(); } catch (x) { fail(x); } };

	authFrame("Welcome, " + me.name, "Choose how you sign in from now on. A passkey alone is enough; a password or pattern always comes with an authenticator code.",
		err,
		h("div", { class: "choice recommended" },
			h("div", { class: "choice-head" }, h("div", {}, h("b", {}, "Passkey"), h("span", { class: "pill red" }, "Recommended")), done(m.passkeys.length > 0)),
			h("p", {}, "Fingerprint, face or your screen lock. One tap, nothing to remember, can't be phished."),
			m.passkeys.length ? h("p", { class: "muted small" }, m.passkeys.map((k) => k.name).join(", ")) : null,
			addKey),
		h("div", { class: "choice" },
			h("div", { class: "choice-head" }, h("b", {}, "Password or pattern"), done(m.password || m.pattern)),
			h("p", {}, "Something you know - a password (10+ characters) or a phone-style unlock pattern."),
			h("div", { class: "row" }, secretBtn, patternBtn)),
		h("div", { class: "choice" },
			h("div", { class: "choice-head" }, h("b", {}, "Authenticator app"), done(m.totp)),
			h("p", {}, "Needed with a password or pattern: Google Authenticator, 2FAS, Aegis, 1Password..."),
			totpBtn),
		finish,
		h("p", { class: "hint" }, "This setup closes after 30 minutes. Your session stays on this network - switch networks and you sign in again."));
}

function secretDialog(kind, then) {
	const err = h("div", { class: "error-box", hidden: true });
	const save = h("button", { class: "btn primary", type: "button" }, "Save");
	let body;
	let value = () => null;
	if (kind === "password") {
		const a = h("input", { type: "password", autocomplete: "new-password", placeholder: "At least 10 characters" });
		const b = h("input", { type: "password", autocomplete: "new-password", placeholder: "Once more" });
		body = [h("label", {}, "New password", a), h("label", {}, "Repeat it", b)];
		value = () => {
			if (a.value.length < 10) throw new Error("Use at least 10 characters");
			if (a.value !== b.value) throw new Error("The two passwords don't match");
			return { password: a.value };
		};
	} else {
		let first = null, second = null;
		const label = h("p", { class: "muted", style: "margin:0;text-align:center" }, "Connect at least 4 dots");
		const pad = patternPad((p) => {
			if (p.split("-").length < 4) { label.textContent = "Too short - connect at least 4 dots"; pad.flash("bad"); return; }
			if (!first) { first = p; label.textContent = "Draw it once more to confirm"; setTimeout(() => pad.reset(), 350); return; }
			second = p;
			if (second !== first) { label.textContent = "That didn't match - start over"; first = null; second = null; pad.flash("bad"); return; }
			label.textContent = "Pattern confirmed";
			pad.flash("ok");
		});
		body = [label, h("div", { class: "pad-wrap" }, pad)];
		value = () => {
			if (!first || first !== second) throw new Error("Draw the pattern twice");
			return { pattern: first };
		};
	}
	save.onclick = async () => {
		err.hidden = true;
		try {
			const v = value();
			await busy(save, () => sp("method/secret", { body: v }));
			toast(kind === "password" ? "Password saved" : "Pattern saved");
			dlg.close();
			then();
		} catch (x) {
			err.textContent = x.message;
			err.hidden = false;
		}
	};
	const dlg = dialog(kind === "password" ? "Password" : "Unlock pattern", [err, ...body], [save]);
}

async function totpDialog(then) {
	const t = await sp("method/totp", { body: {} });
	const err = h("div", { class: "error-box", hidden: true });
	const code = h("input", { class: "code-input", inputmode: "numeric", autocomplete: "one-time-code", maxlength: 7, placeholder: "000000" });
	const ok = h("button", { class: "btn primary", type: "button" }, "Confirm");
	const secret = t.secret.replace(/(.{4})/g, "$1 ").trim();
	ok.onclick = async () => {
		err.hidden = true;
		try {
			await busy(ok, () => sp("method/totp/confirm", { body: { code: code.value } }));
			toast("Authenticator connected");
			dlg.close();
			then();
		} catch (x) {
			err.textContent = x.message;
			err.hidden = false;
		}
	};
	const dlg = dialog("Authenticator app", [
		h("p", { class: "muted", style: "margin:0" }, "Scan this with your authenticator app - or, on this phone, tap the button. Then type the 6-digit code it shows."),
		h("div", { class: "qr-wrap" }, qrSvg(t.uri)),
		h("a", { class: "btn block", href: t.uri }, "Open in my authenticator app"),
		h("div", { class: "secret" }, h("span", { class: "muted small" }, "Or type this key"), h("code", {}, secret)),
		err,
		h("label", {}, "Code from the app", code),
	], [ok]);
}

function installHint() {
	if (window.matchMedia("(display-mode: standalone)").matches || navigator.standalone) return null;
	const ios = /iPhone|iPad/.test(navigator.userAgent);
	return h("p", { class: "hint install" }, icon("phone"), ios ? "On your iPhone: Share → \"Add to Home Screen\" for a Velo Staff app." : "On your phone: browser menu → \"Install app\" / \"Add to Home screen\" for a Velo Staff app.");
}

// ---- Shell ----

const PAGES = [
	["", [["overview", "Overview", 0], ["tickets", "Tickets", 0]]],
	["Reports", [["chat", "Chat reports", 2], ["clips", "Clips", 0], ["worlds", "Public worlds", 0], ["notes", "World notes", 0], ["cloaks", "Custom cloaks", 0]]],
	["Players", [["players", "Look up a player", 1], ["punishments", "Punishments", 3], ["stafflog", "Staff log", 2]]],
	["Team", [["team", "Team", 3], ["account", "My account", 0]]],
];
const TITLES = Object.fromEntries(PAGES.flatMap(([, list]) => list.map(([id, label]) => [id, label])));

async function boot() {
	try {
		const me = await sp("me");
		state.me = me;
		state.csrf = me.csrf;
		if (me.kind === "setup") {
			await renderSetup();
			return;
		}
		shell();
	} catch (e) {
		if (e.status === 401) renderLogin();
		else authFrame("Velo Staff", null, errorBox(new Error("The staff panel isn't answering: " + e.message)), h("button", { class: "btn block", onclick: () => boot() }, "Try again"));
	}
}

function shell() {
	const nav = h("nav", { class: "nav", "aria-label": "Staff panel" });
	for (const [group, list] of PAGES) {
		const visible = list.filter(([, , min]) => rank() >= min);
		if (!visible.length) continue;
		if (group) nav.append(h("div", { class: "nav-group" }, group));
		for (const [id, label] of visible) nav.append(h("a", { href: "#/" + id, "data-id": id }, icon(id), label, h("span", { class: "nav-badge", "data-badge": id, hidden: true })));
	}
	const side = h("aside", { class: "side" },
		h("div", { class: "brand" }, h("img", { src: "/logo.png", alt: "" }), h("div", {}, h("b", {}, "VELO"), h("small", {}, "Staff panel"))),
		nav,
		h("div", { class: "side-foot" },
			h("div", { class: "me" }, playerHead(state.me.uuid, state.me.name, 30), h("div", {}, h("div", {}, state.me.name), rolePill(state.me.role))),
			h("button", { class: "btn small ghost", type: "button", onclick: async () => { await sp("logout", { method: "POST" }); renderLogin("Signed out."); } }, icon("logout"), "Sign out")));
	const main = h("main", { class: "main", id: "main" });
	$app.replaceChildren(h("div", { class: "shell" }, side, main));
	window.onhashchange = () => route(side, main);
	route(side, main);
	refreshBadges();
	setInterval(refreshBadges, 60000);
}

async function refreshBadges() {
	try {
		const o = await velo("staff/overview");
		const set = (id, n) => document.querySelectorAll(`[data-badge="${id}"]`).forEach((el) => { el.hidden = !n; el.textContent = n > 99 ? "99+" : n; });
		set("chat", o.reports.chat);
		set("clips", o.reports.clips);
		set("worlds", o.reports.worlds);
		set("notes", o.reports.notes || 0);
		set("cloaks", o.capes.unreviewed);
		const t = await velo("staff/tickets").catch(() => null);
		if (t) set("tickets", t.tickets.filter((x) => !x.claimedBy).length);
	} catch {
		// Badges are a nicety.
	}
}

function route(side, main) {
	const id = (location.hash.replace(/^#\//, "").split("?")[0]) || "overview";
	const allowed = PAGES.flatMap(([, l]) => l).filter(([, , min]) => rank() >= min).map(([x]) => x);
	const page = RENDER[id] && allowed.includes(id) ? id : "overview";
	state.timers.forEach(clearInterval);
	state.timers = [];
	side.classList.remove("open");
	side.querySelectorAll(".nav a").forEach((a) => { if (a.dataset.id !== page) a.removeAttribute("aria-current"); else a.setAttribute("aria-current", "page"); });
	const menu = h("button", { class: "icon-btn menu-btn", "aria-label": "Menu", type: "button", onclick: () => side.classList.toggle("open") }, icon("menu"));
	const live = h("span", { class: "live", id: "live" }, h("i"), "Live");
	const body = h("div", { class: "page" });
	main.replaceChildren(h("header", { class: "top" }, h("div", { style: "display:flex;gap:12px;align-items:center" }, menu, h("div", {}, h("h1", {}, TITLES[page]), h("p", { id: "subtitle" }, ""))), live), body);
	document.title = TITLES[page] + " · Velo Staff";
	Promise.resolve(RENDER[page](body)).catch((e) => { if (e.status !== 401) body.replaceChildren(errorBox(e)); });
}

function setLive(ok, text) {
	const el = document.getElementById("live");
	if (!el) return;
	el.classList.toggle("off", !ok);
	el.replaceChildren(h("i"), text || (ok ? "Live" : "Offline"));
}
const subtitle = (t) => { const el = document.getElementById("subtitle"); if (el) el.textContent = t; };
const reload = () => route(document.querySelector(".side"), document.getElementById("main"));

// ---- Overview ----

RENDER.overview = async (root) => {
	const kpis = h("div", { class: "grid kpis" });
	const rest = h("div", { class: "stack" });
	root.append(kpis, rest);
	const draw = async () => {
		const [o, t] = await Promise.all([velo("staff/overview"), velo("staff/tickets").catch(() => null)]);
		if (!root.isConnected) return;
		setLive(true, `Live · ${o.online.players} online`);
		subtitle("Hi " + state.me.name + " - here's what's waiting");
		const tickets = t ? t.tickets : [];
		const unclaimed = tickets.filter((x) => !x.claimedBy).length;
		kpis.replaceChildren(
			kpi("Players online", "online", o.online.players, fmt, `${o.online.game} in game · ${o.online.launcher} launcher`, true),
			kpi("Open tickets", "tickets", tickets.length, fmt, t ? `${unclaimed} not claimed yet` : "Discord bot not reachable"),
			kpi("Open reports", "reports", o.reports.clips + o.reports.worlds + (o.reports.notes || 0) + o.reports.chat, fmt,
				`${o.reports.clips} clips · ${o.reports.worlds} worlds · ${o.reports.notes || 0} notes · ${o.reports.chat} chat`),
			kpi("Cloaks to review", "cloaks", o.capes.unreviewed, fmt, `${o.capes.custom} custom cloaks · ${o.capes.animated} animated`),
			kpi("Hosted worlds", "worlds", o.worlds.open, fmt, `${o.worlds.public} public`));
		const todo = [
			["#/tickets", "Tickets nobody claimed", unclaimed, 0],
			["#/chat", "Chat reports", o.reports.chat, 2],
			["#/clips", "Clip reports", o.reports.clips, 0],
			["#/worlds", "World reports", o.reports.worlds, 0],
			["#/notes", "World note reports", o.reports.notes || 0, 0],
			["#/cloaks", "Custom cloaks to review", o.capes.unreviewed, 0],
		].filter(([, , , min]) => rank() >= min);
		rest.replaceChildren(h("div", { class: "grid cols-2" },
			card("Needs a look", "Tap one to start", null, h("div", { class: "todo" }, todo.map(([href, label, n]) =>
				h("a", { class: "todo-row", href }, h("span", {}, label), h("span", { class: "pill " + (n ? "warn" : "good") }, n ? fmt(n) + " open" : "All clear"))))),
			card("Latest tickets", t ? "From the Velo Discord" : "The Discord bot isn't answering", h("a", { class: "btn small", href: "#/tickets" }, "Open"),
				tickets.length ? h("div", { class: "todo" }, tickets.slice(0, 5).map((x) => h("a", { class: "todo-row", href: "#/tickets?id=" + x.id },
					h("span", {}, (x.emoji ? x.emoji + " " : "") + "#" + String(x.number).padStart(4, "0") + " · " + x.subject),
					h("span", { class: "muted small" }, ago(x.lastMessageAt))))) : h("p", { class: "muted" }, "No open tickets"))));
	};
	await draw();
	every(20000, () => draw().catch(() => setLive(false, "Server unreachable")));
};

// ---- Tickets ----

RENDER.tickets = async (root) => {
	const list = h("div", { class: "ticket-list" });
	const view = h("div", { class: "ticket-view" }, h("div", { class: "empty" }, "Pick a ticket"));
	const wrap = h("div", { class: "tickets" }, list, view);
	root.append(wrap);
	let current = new URLSearchParams(location.hash.split("?")[1] || "").get("id");
	let tickets = [];
	const drawList = async () => {
		tickets = (await velo("staff/tickets")).tickets;
		if (!root.isConnected) return;
		setLive(true, `Live · ${tickets.length} open`);
		subtitle(`${tickets.length} open · ${tickets.filter((t) => !t.claimedBy).length} not claimed`);
		list.replaceChildren(...(tickets.length ? tickets.map((t) => h("button", { type: "button", class: "ticket-row" + (t.id === current ? " active" : ""), onclick: () => open(t.id) },
			h("div", { class: "ticket-top" }, h("b", {}, (t.emoji ? t.emoji + " " : "") + "#" + String(t.number).padStart(4, "0")), h("span", { class: "muted small" }, ago(t.lastMessageAt))),
			h("div", { class: "ticket-subject" }, t.subject),
			h("div", { class: "ticket-meta" }, h("span", { class: "muted small" }, t.type + " · " + t.user),
				t.claimedBy ? h("span", { class: "pill blue" }, t.claimedBy) : h("span", { class: "pill warn" }, "Unclaimed"))))
			: [h("div", { class: "empty" }, "No open tickets - nice.")]));
	};
	const open = async (id) => {
		current = id;
		history.replaceState(null, "", "#/tickets?id=" + id);
		wrap.classList.add("reading");
		list.querySelectorAll(".ticket-row").forEach((b, i) => b.classList.toggle("active", tickets[i] && tickets[i].id === id));
		await drawTicket(true);
	};
	const text = h("textarea", { class: "reply-box", rows: 3, maxlength: 1900, placeholder: "Reply in the ticket (posted by the bot with your name)" });
	const drawTicket = async (scroll) => {
		if (!current) return;
		let data;
		try {
			data = await velo("staff/tickets/messages?id=" + current);
		} catch (e) {
			view.replaceChildren(errorBox(e));
			return;
		}
		if (!root.isConnected) return;
		const t = data.ticket || {};
		const msgs = h("div", { class: "msgs" }, data.messages.map((m) => h("div", { class: "msg" + (m.bot ? " bot" : "") + (m.opener ? " opener" : "") },
			h("img", { class: "msg-avatar", src: m.avatar, alt: "", loading: "lazy" }),
			h("div", { class: "msg-body" },
				h("div", { class: "msg-head" }, h("b", {}, m.author), m.opener ? h("span", { class: "pill aqua" }, "Opened it") : m.bot ? h("span", { class: "pill" }, "Bot") : null,
					h("span", { class: "muted small" }, when(m.at))),
				m.text ? h("div", { class: "msg-text" }, m.text) : null,
				m.embeds.map((e) => h("div", { class: "msg-embed" }, e.title ? h("b", {}, e.title) : null, e.description ? h("div", {}, e.description) : null,
					e.fields ? h("div", { class: "muted small pre" }, e.fields) : null)),
				m.files.map((f) => f.image === "true" ? h("a", { href: f.url, target: "_blank", rel: "noopener" }, h("img", { class: "msg-img", src: f.url, alt: f.name, loading: "lazy" }))
					: h("a", { class: "btn small ghost", href: f.url, target: "_blank", rel: "noopener" }, icon("file"), f.name))))));
		const send = h("button", { class: "btn primary", type: "button" }, "Send");
		send.onclick = () => busy(send, async () => {
			if (!text.value.trim()) return;
			await velo("staff/tickets/reply", { body: { id: current, text: text.value } });
			text.value = "";
			await drawTicket(true);
		});
		text.onkeydown = (e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) send.click(); };
		const claim = h("button", { class: "btn small", type: "button", hidden: !!t.claimedBy }, "Claim");
		claim.onclick = () => busy(claim, async () => { await velo("staff/tickets/claim", { body: { id: current } }); toast("You're on it"); drawList(); drawTicket(); });
		const close = h("button", { class: "btn small danger", type: "button" }, "Close");
		close.onclick = () => {
			const reason = h("input", { placeholder: "Reason (optional - the player sees it)", maxlength: 200 });
			const ok = h("button", { class: "btn danger", type: "button" }, "Close ticket");
			const dlg = dialog("Close ticket #" + String(t.number).padStart(4, "0") + "?", [h("p", { class: "muted", style: "margin:0" }, "The transcript goes to the ticket log and to the player, then the channel is deleted."), reason], [ok]);
			ok.onclick = () => busy(ok, async () => {
				await velo("staff/tickets/close", { body: { id: current, reason: reason.value } });
				toast("Ticket closed");
				dlg.close();
				current = null;
				wrap.classList.remove("reading");
				view.replaceChildren(h("div", { class: "empty" }, "Pick a ticket"));
				setTimeout(drawList, 1500);
			});
		};
		const back = h("button", { class: "btn small ghost back-btn", type: "button", onclick: () => wrap.classList.remove("reading") }, "← Tickets");
		const answers = t.answers ? Object.entries(t.answers) : [];
		view.replaceChildren(
			h("div", { class: "ticket-head" }, back, h("div", { class: "grow" }, h("h2", {}, (t.emoji ? t.emoji + " " : "") + "#" + String(t.number || "").padStart(4, "0") + " · " + (t.subject || "")),
				h("p", { class: "muted small" }, (t.type || "") + " · opened by " + (t.user || "?") + " · " + when(t.createdAt) + (t.claimedBy ? " · claimed by " + t.claimedBy : ""))),
				h("div", { class: "row shrink" }, claim, close)),
			answers.length ? h("div", { class: "answers" }, answers.map(([q, a]) => h("div", {}, h("span", { class: "muted small" }, q), h("div", {}, a)))) : null,
			msgs,
			h("div", { class: "reply" }, text, send));
		if (scroll) msgs.scrollTop = msgs.scrollHeight;
	};
	await drawList();
	if (current) await open(current);
	every(10000, () => { drawList().catch(() => setLive(false, "Bot unreachable")); if (current && !text.value) drawTicket(false); });
};

// ---- Chat reports ----

RENDER.chat = async (root) => {
	let status = "open";
	const body = h("div");
	root.append(body);
	const draw = async () => {
		const { reports } = await velo("staff/reports?status=" + status);
		if (!root.isConnected) return;
		subtitle(reports.length + (status === "open" ? " open chat reports" : " chat reports"));
		const filter = seg([["open", "Open"], ["all", "All"]], status, (x) => { status = x; draw(); });
		body.replaceChildren(card("Chat reports", "Friends messages and party chat that players reported", filter,
			table(["When", "Reported", "By", "Category", "Reason", "Status"], reports.map((r) => [
				when(r.at), h("b", {}, r.targetName || r.target), r.reporterName || "–", reasonPill(r.category),
				h("span", { class: "small" }, (r.reason || "").slice(0, 120)),
				h("span", { class: "pill " + (r.status === "open" ? "warn" : r.status === "actioned" ? "bad" : "good") }, r.status)]), (i) => openChatReport(reports[i], draw))));
	};
	await draw();
};

function openChatReport(r, reload) {
	const days = h("select", { "aria-label": "Ban length" }, [[1, "1 day"], [7, "7 days"], [30, "30 days"], [0, "Permanent"]].map(([d, l]) => h("option", { value: d, selected: d === 7 }, l)));
	const note = h("input", { placeholder: "Note (only staff see it)", "aria-label": "Note" });
	const dismiss = h("button", { class: "btn", type: "button" }, "Dismiss");
	const action = h("button", { class: "btn danger", type: "button" }, "Ban from friends & parties");
	const player = h("button", { class: "btn ghost", type: "button", hidden: rank() < 1 }, "Open player");
	const resolve = (btn, ban) => busy(btn, async () => {
		await velo("staff/reports/resolve", { body: { id: r.id, status: ban ? "actioned" : "dismissed", note: note.value,
			ban: ban ? { services: ["friends", "parties"], days: Number(days.value), reason: "Reported chat" + (r.category ? " (" + r.category + ")" : "") } : null } });
		toast(ban ? "Report actioned, player banned" : "Report dismissed");
		dlg.close();
		reload();
	});
	dismiss.onclick = () => resolve(dismiss, false);
	action.onclick = () => resolve(action, true);
	player.onclick = () => { dlg.close(); location.hash = "#/players?q=" + encodeURIComponent(r.targetName || r.target); };
	const dlg = dialog("Report against " + (r.targetName || r.target), [
		h("div", { class: "row" }, h("div", {}, h("span", { class: "muted small" }, "Reported by"), h("div", {}, h("b", {}, r.reporterName || "–"))),
			h("div", {}, h("span", { class: "muted small" }, "Where"), h("div", {}, r.context === "party" ? "Party chat" : "Direct messages")),
			h("div", {}, h("span", { class: "muted small" }, "When"), h("div", {}, when(r.at)))),
		r.reason ? h("div", { class: "notice" }, h("b", {}, (r.category || "Reason") + ": "), r.reason) : null,
		(r.messages || []).length ? h("div", { class: "msgs flat" }, r.messages.map((m) => h("div", { class: "msg" + (m.fromName === r.targetName ? " flagged" : "") },
			h("div", { class: "msg-body" }, h("div", { class: "msg-head" }, h("b", {}, m.fromName || "?"), h("span", { class: "muted small" }, when(m.time))), h("div", { class: "msg-text" }, m.text || "")))))
			: h("p", { class: "muted" }, "The reporter didn't attach the chat."),
		r.status === "open" ? h("div", { class: "row" }, note, days) : h("div", { class: "notice" }, "Handled by " + (r.handledBy || "?") + " · " + when(r.handledAt) + " · " + r.status),
	], r.status === "open" ? [player, dismiss, action] : [player], true);
}

// ---- Players ----

RENDER.players = async (root) => {
	const q = new URLSearchParams(location.hash.split("?")[1] || "").get("q") || "";
	const input = h("input", { type: "search", placeholder: "Minecraft name or UUID", value: q, autocomplete: "off", "aria-label": "Player" });
	const go = h("button", { class: "btn primary", type: "submit" }, "Look up");
	const out = h("div", { class: "stack" });
	const form = h("form", { class: "row search-row" }, input, go);
	form.addEventListener("submit", (e) => { e.preventDefault(); if (input.value.trim()) lookup(input.value.trim()); });
	root.append(card("Look up a player", "Anyone who ever used Velo Client", null, form), out);
	subtitle(rank() >= 2 ? "Look up, censor cloaks, ban and block" : "Look up, censor cloaks and block");
	async function lookup(target) {
		history.replaceState(null, "", "#/players?q=" + encodeURIComponent(target));
		out.replaceChildren(h("div", { class: "card skeleton", style: "height:160px" }));
		try {
			const p = await velo("staff/lookup", { body: { target } });
			out.replaceChildren(playerCard(p, () => lookup(target)));
		} catch (e) {
			out.replaceChildren(errorBox(e));
		}
	}
	if (q) lookup(q);
};

function playerCard(p, refresh) {
	const ban = p.ban;
	const banActive = ban && (!ban.until || ban.until > Date.now());
	const actions = h("div", { class: "row" });
	if (p.customCape && rank() >= 1) {
		const censor = h("button", { class: "btn danger", type: "button" }, icon("trash"), "Remove custom cloak");
		censor.onclick = async () => {
			if (!(await confirmBox("Remove " + p.username + "'s cloak?", "It's taken off and that exact image can't be uploaded again."))) return;
			await busy(censor, () => velo("staff/censor-cape", { body: { target: p.uuid } }));
			toast("Cloak removed");
			refresh();
		};
		actions.append(censor);
	}
	if (rank() >= 1) actions.append(h("button", { class: "btn", type: "button", onclick: () => blockDialog(p, refresh) }, "Block clips / worlds"));
	if (rank() >= 2 && !p.level) {
		actions.append(h("button", { class: "btn danger", type: "button", onclick: () => banDialog(p, refresh) }, banActive ? "Change ban" : "Ban"));
		if (banActive) {
			const lift = h("button", { class: "btn", type: "button" }, "Lift ban");
			lift.onclick = () => busy(lift, async () => { await velo("staff/unban", { body: { target: p.uuid } }); toast("Ban lifted"); refresh(); });
			actions.append(lift);
		}
	}
	const activity = p.activity ? Object.entries(p.activity).filter(([, v]) => v !== null && v !== "" && typeof v !== "object").map(([k, v]) => k + ": " + v).join(" · ") : "";
	return h("section", { class: "card player-card" },
		h("div", { class: "player-top" }, playerHead(p.uuid, p.username, 64),
			h("div", { class: "grow" }, h("h2", {}, p.username), h("div", { class: "row", style: "gap:6px" },
				p.online ? h("span", { class: "pill good" }, h("i"), "Online") : h("span", { class: "pill" }, "Offline"),
				p.level ? rolePill(p.level) : null,
				p.reports ? h("span", { class: "pill warn" }, p.reports + " chat reports") : null,
				banActive ? h("span", { class: "pill bad" }, "Banned") : null),
				h("p", { class: "muted small mono" }, p.uuid))),
		activity ? h("p", { class: "muted small" }, activity) : null,
		banActive ? h("div", { class: "notice" }, h("b", {}, "Banned from " + ban.services.join(", ") + ": "), ban.reason,
			h("div", { class: "muted small" }, "by " + ban.by + " · " + when(ban.at) + " · " + (ban.until ? "until " + when(ban.until) : "until lifted"))) : null,
		p.customCape ? h("div", { class: "cloak-preview" }, h("span", { class: "muted small" }, "Custom cloak"), h("img", { class: "cape-art", src: "/v1/cape/" + p.customCape, alt: "Custom cloak" })) : null,
		actions.childNodes.length ? actions : null);
}

function banDialog(p, refresh) {
	const services = [["friends", "Friends & messages"], ["parties", "Parties"], ["capes", "Custom cloaks"], ["network", "Whole Velo Network (sign-in refused)"]];
	const checks = services.map(([v, l]) => h("label", { class: "check" }, h("input", { type: "checkbox", value: v, checked: v !== "network" && v !== "capes" }), l));
	const days = h("select", {}, [[1, "1 day"], [3, "3 days"], [7, "7 days"], [30, "30 days"], [0, "Permanent"]].map(([d, l]) => h("option", { value: d, selected: d === 7 }, l)));
	const reason = h("input", { placeholder: "Reason (the player sees it)", maxlength: 200 });
	const ok = h("button", { class: "btn danger", type: "button" }, "Ban");
	ok.onclick = () => busy(ok, async () => {
		const picked = checks.map((c) => c.querySelector("input")).filter((i) => i.checked).map((i) => i.value);
		if (!picked.length) throw new Error("Pick at least one thing to ban from");
		await velo("staff/ban", { body: { target: p.uuid, services: picked, days: Number(days.value), reason: reason.value } });
		toast(p.username + " banned");
		dlg.close();
		refresh();
	});
	const dlg = dialog("Ban " + p.username, [h("div", { class: "checks" }, checks), h("div", { class: "row" }, days, reason)], [ok]);
}

function blockDialog(p, refresh) {
	const what = h("select", {}, [["clip", "Sharing clips"], ["world", "Listing public worlds"], ["note", "World notes"]].map(([v, l]) => h("option", { value: v }, l)));
	const days = h("select", {}, [[7, "7 days"], [30, "30 days"], [0, "Until lifted"], [-1, "Lift the block"]].map(([d, l]) => h("option", { value: d }, l)));
	const reason = h("input", { placeholder: "Reason", maxlength: 200 });
	const ok = h("button", { class: "btn primary", type: "button" }, "Save");
	ok.onclick = () => busy(ok, async () => {
		await velo("staff/blocks/set", { body: { what: what.value, target: p.uuid, days: Number(days.value), reason: reason.value } });
		toast(Number(days.value) < 0 ? "Block lifted" : "Blocked");
		dlg.close();
		refresh();
	});
	const dlg = dialog("Block " + p.username, [h("div", { class: "row" }, what, days), reason], [ok]);
}

// ---- Punishments (managers) ----

RENDER.punishments = async (root) => {
	const d = await velo("staff/punishments");
	let tab = "bans";
	const tabs = h("div", { class: "tabs", role: "tablist" });
	const body = h("div");
	const counts = { bans: d.bans.length, blocks: d.blocks.length, reports: d.reports.length, log: d.log.length };
	for (const [t, label] of [["bans", "Bans"], ["blocks", "Blocks"], ["reports", "Actioned reports"], ["log", "Everything staff did"]]) {
		tabs.append(h("button", { role: "tab", type: "button", "data-t": t, "aria-selected": String(t === tab), onclick: () => {
			tab = t; tabs.querySelectorAll("button").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.t === t))); draw();
		} }, label, h("span", { class: "tab-count" }, counts[t])));
	}
	root.append(tabs, body);
	subtitle(`${d.bans.length} bans · ${d.blocks.length} blocks in force`);
	const until = (x) => x ? when(x) : h("span", { class: "pill bad" }, "Permanent");
	function draw() {
		if (tab === "bans") {
			body.replaceChildren(card("Bans", "Online-service bans in force, newest first", null, table(["Player", "From", "Reason", "By", "Since", "Until", ""], d.bans.map((b) => {
				const lift = h("button", { class: "btn small ghost", type: "button" }, "Lift");
				lift.onclick = () => busy(lift, async () => { await velo("staff/unban", { body: { target: b.uuid } }); toast("Ban lifted"); reload(); });
				return [h("div", { class: "player" }, playerHead(b.uuid, b.username), b.username), b.services.join(", "), b.reason, b.by, when(b.at), until(b.until), lift];
			}))));
		} else if (tab === "blocks") {
			body.replaceChildren(card("Blocks", "Players stopped from sharing clips, listing public worlds or using World Notes", null, table(["Player", "From", "Reason", "By", "Since", "Until", ""], d.blocks.map((b) => {
				const lift = h("button", { class: "btn small ghost", type: "button" }, "Lift");
				lift.onclick = () => busy(lift, async () => { await velo("staff/blocks/set", { body: { what: b.what, target: b.uuid, days: -1 } }); toast("Block lifted"); reload(); });
				return [h("div", { class: "player" }, playerHead(b.uuid, b.username), b.username), b.what === "clip" ? "Clip sharing" : b.what === "note" ? "World notes" : "Public worlds", b.reason || "–", b.by, when(b.at), until(b.until), lift];
			}))));
		} else if (tab === "reports") {
			body.replaceChildren(card("Actioned chat reports", "Reports that ended in a ban", null, table(["When", "Player", "Category", "By", "Note"],
				d.reports.map((r) => [when(r.at), h("b", {}, r.target), reasonPill(r.category), r.by, h("span", { class: "muted small" }, r.note || "")]))));
		} else {
			body.replaceChildren(card("Staff log", "Every removal, takedown, block, ticket and team change", null, table(["When", "Who", "Did", "What", "Note"],
				d.log.map((a) => [when(a.at), h("b", {}, a.by), a.action, a.target, h("span", { class: "muted small" }, a.note || "")]))));
		}
	}
	draw();
};

// ---- Team (managers) ----

RENDER.team = async (root) => {
	const [{ team, botOnline }, { log }] = await Promise.all([velo("staff/team"), velo("staff/signins")]);
	subtitle(`${team.length} on the team · ${team.filter((m) => m.signedIn).length} signed in now`);
	const add = h("button", { class: "btn primary", type: "button", onclick: () => levelDialog(null) }, icon("plus"), "Add to the team");
	const methodsOf = (m) => !m.methods ? "–" : [m.methods.passkeys.length ? m.methods.passkeys.length + " passkey" + (m.methods.passkeys.length > 1 ? "s" : "") : null,
		m.methods.password ? "password" : null, m.methods.pattern ? "pattern" : null, m.methods.totp ? "authenticator" : null].filter(Boolean).join(" · ") || "–";
	if (!botOnline) root.append(h("div", { class: "notice" }, "The Discord bot isn't answering, so levels can't be changed right now (they're Discord roles)."));
	root.append(
		card("Team", "Levels are Discord roles - changing one here changes the role. Tap someone to manage them.", add,
			table(["Member", "Level", "Discord", "Panel sign-in", "Last sign-in", ""], team.map((m) => [
				h("div", { class: "player" }, playerHead(m.uuid, m.name), h("b", {}, m.name)), rolePill(m.level), m.discordName || h("span", { class: "muted" }, "not linked"),
				m.enrolled ? h("span", { class: "small" }, methodsOf(m)) : h("span", { class: "pill" }, "Not set up"),
				m.lastSignInAt ? h("div", {}, ago(m.lastSignInAt), h("div", { class: "muted small mono" }, m.lastSignInIp || "")) : "–",
				m.signedIn ? h("span", { class: "pill good" }, h("i"), "Signed in") : ""]), (i) => memberDialog(team[i]))),
		card("Sign-in log", "Codes, sign-ins, failures and device changes", null,
			table(["When", "Who", "What", "Address", "Detail"], log.map((e) => [when(e.at), h("b", {}, e.name), h("span", { class: "pill " + (/fail|refused/.test(e.event) ? "bad" : /signed in|set up/.test(e.event) ? "good" : "") }, e.event),
				h("span", { class: "mono small" }, e.ip || ""), h("span", { class: "muted small" }, e.detail || "")]))));

	function memberDialog(m) {
		const reset = h("button", { class: "btn danger", type: "button", disabled: !m.enrolled }, "Reset panel sign-in");
		reset.onclick = async () => {
			if (!(await confirmBox("Reset " + m.name + "'s sign-in?", "Their passkeys, password, pattern and authenticator are removed and they're signed out. They set it up again with a launcher code."))) return;
			await busy(reset, () => velo("staff/team/reset-signin", { body: { uuid: m.uuid } }));
			toast("Sign-in reset");
			dlg.close();
			reload();
		};
		const end = h("button", { class: "btn", type: "button", disabled: !m.signedIn }, "Sign them out");
		end.onclick = () => busy(end, async () => { await velo("staff/team/end-session", { body: { uuid: m.uuid } }); toast("Signed out"); dlg.close(); reload(); });
		const level = h("button", { class: "btn primary", type: "button" }, "Change level");
		level.onclick = () => { dlg.close(); levelDialog(m); };
		const dlg = dialog(m.name, [
			h("div", { class: "player-top" }, playerHead(m.uuid, m.name, 56), h("div", {}, h("h2", { style: "margin:0" }, m.name), h("div", { class: "row", style: "gap:6px" }, rolePill(m.level), m.signedIn ? h("span", { class: "pill good" }, "Signed in") : null))),
			h("div", { class: "facts" },
				fact("On the team since", m.since ? when(m.since) : "–"), fact("Discord", m.discordName || "not linked"),
				fact("Panel sign-in", m.enrolled ? methodsOf(m) : "not set up"), fact("Last sign-in", m.lastSignInAt ? when(m.lastSignInAt) + " · " + (m.lastSignInIp || "") : "never"),
				fact("Device", m.lastAgent ? m.lastAgent.slice(0, 90) : "–")),
		], [end, reset, level], true);
	}

	function levelDialog(m) {
		const name = h("input", { placeholder: "Minecraft name", value: m ? m.name : "", disabled: !!m });
		const pick = h("select", {}, [["helper", "Helper - tickets, clips, worlds, cloaks"], ["mod", "Moderator - also look up, block, censor"], ["admin", "Admin - also bans and chat reports"],
			["manager", "Manager - everything + team & punishments"], ...(m ? [["none", "Remove from the team"]] : [])].map(([v, l]) => h("option", { value: v, selected: m && m.level === v }, l)));
		const ok = h("button", { class: "btn primary", type: "button" }, m ? "Save" : "Add");
		ok.onclick = () => busy(ok, async () => {
			const r = await velo("staff/team/level", { body: { target: m ? m.uuid : name.value.trim(), level: pick.value } });
			toast(r.note || "Saved");
			dlg.close();
			setTimeout(reload, 4000);
		});
		const dlg = dialog(m ? "Level of " + m.name : "Add to the team", [
			m ? null : name, pick,
			h("p", { class: "muted small", style: "margin:0" }, "They need their Minecraft account linked on the Velo Discord. Managers can be given or taken only by the owner."),
		], [ok]);
	}
};

const fact = (label, value) => h("div", { class: "fact" }, h("span", { class: "muted small" }, label), h("div", {}, value));

// ---- Account ----

RENDER.account = async (root) => {
	const me = await sp("me");
	state.me = me;
	const m = me.methods;
	subtitle(ROLE_NAMES[me.role] + " · signed in from " + me.ip);
	const keys = table(["Passkey", "Added", "Last used", ""], m.passkeys.map((k) => {
		const del = h("button", { class: "btn small danger", type: "button" }, "Remove");
		del.onclick = async () => {
			if (!(await confirmBox("Remove this passkey?", k.name + " can't sign in any more."))) return;
			await busy(del, () => sp("passkey/delete", { body: { id: k.id } }));
			toast("Passkey removed");
			reload();
		};
		return [h("b", {}, k.name), when(k.createdAt), k.lastUsed ? ago(k.lastUsed) : h("span", { class: "muted" }, "never"), del];
	}));
	const addKey = h("button", { class: "btn primary", type: "button", hidden: !window.PublicKeyCredential }, icon("key"), "Add passkey on this device");
	addKey.onclick = async () => {
		try { await busy(addKey, passkeyAdd); toast("Passkey added"); reload(); } catch (x) { if (x.name === "InvalidStateError") toast("This device already has a passkey for Velo Staff", "err"); }
	};
	const method = (label, on, setLabel, set, kind) => {
		const remove = h("button", { class: "btn small ghost", type: "button", hidden: !on }, "Remove");
		remove.onclick = async () => {
			if (!(await confirmBox("Remove your " + label.toLowerCase() + "?", "You can add it again any time."))) return;
			await busy(remove, () => sp("method/remove", { body: { method: kind } }));
			toast(label + " removed");
			reload();
		};
		return h("div", { class: "method-row" }, h("div", { class: "grow" }, h("b", {}, label), h("div", { class: "muted small" }, on ? "Set" : "Not set")),
			h("button", { class: "btn small", type: "button", onclick: set }, on ? "Change" : setLabel), remove);
	};
	root.append(h("div", { class: "grid cols-2" },
		card("Passkeys", "Fingerprint, face or screen lock - the quickest way in", addKey, keys),
		card("Password, pattern & authenticator", "A password or pattern always needs an authenticator code too", null, h("div", { class: "methods" },
			method("Password", m.password, "Set", () => secretDialog("password", reload), "password"),
			method("Unlock pattern", m.pattern, "Draw", () => secretDialog("pattern", reload), "pattern"),
			method("Authenticator app", m.totp, "Connect", () => totpDialog(reload), "totp")))),
		card("This session", "One device at a time, tied to this network", null, h("div", { class: "facts" },
			fact("Signed in as", me.name), fact("Level", ROLE_NAMES[me.role]), fact("Since", when(me.since)), fact("Network", me.ip)),
			h("ul", { class: "rules" },
				h("li", {}, "Signing in somewhere else signs you out here."),
				h("li", {}, "Changing networks (Wi-Fi ↔ mobile data) asks you to sign in again - a passkey makes that one tap."),
				h("li", {}, "New device? Sign out of the staff panel in your launcher (Profile → Connections), then set it up there with a launcher code."),
				h("li", {}, "Everything you do is in the staff log.")),
			installHint()));
};

// ---- Staff log (admins) ----

RENDER.stafflog = async (root) => {
	const { log } = await velo("staff/log");
	subtitle(log.length + " recent staff actions");
	root.append(card("Staff log", "Every removal, takedown, dismissal, block and ticket action", null,
		table(["When", "Who", "Did", "What", "Note"], log.map((a) => [when(a.at), h("b", {}, a.by), a.action, a.target, h("span", { class: "muted small" }, a.note || "")]))));
};

boot();
if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => null);
