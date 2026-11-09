/*
 * Velo Bridge owner panel. Login: the one-time code in the URL hash (from /velobridge panel) becomes a 12 h session.
 * The page edits a copy of the server's config.yml (as JSON) and saves it; the plugin picks it up on its next sync
 * (every 5 s while the panel is open) and acknowledges - the save bar shows when it landed.
 */
(() => {
	"use strict";
	const $ = (s, el = document) => el.querySelector(s);
	const $$ = (s, el = document) => [...el.querySelectorAll(s)];
	const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
	const clone = (o) => JSON.parse(JSON.stringify(o ?? {}));
	const fmt = (n) => Number(n || 0).toLocaleString("en-US");
	const ICON = (id) => `https://velo-client.com/v2/item/${encodeURIComponent(String(id || "").replace(/^minecraft:/, ""))}.png`;
	const SVG_X = '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>';
	const SVG_UP = '<svg viewBox="0 0 24 24"><path d="m6 15 6-6 6 6"/></svg>';
	const SVG_DOWN = '<svg viewBox="0 0 24 24"><path d="m6 9 6 6 6-6"/></svg>';

	let token = null;
	let ov = null; // overview from the server
	let base = null; // the config as last loaded (to see what changed)
	let cfg = null; // the config being edited
	let waiting = 0; // version we're waiting for the plugin to apply
	let modules = [];
	let catalog = null;
	const ui = { tab: "overview", range: "24h", menu: null, hud: null, cut: null, zone: null, npc: null, npcType: "cape", npcSearch: "", noteFilter: "", picked: new Set() };

	const store = {
		get(k) { try { return sessionStorage.getItem(k); } catch { return null; } },
		set(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch { /* private mode */ } },
	};

	// ---------------------------------------------------------------- API

	async function api(path, body) {
		const r = await fetch(path, {
			method: body === undefined ? "GET" : "POST",
			headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
			body: body === undefined ? undefined : JSON.stringify(body),
		});
		const data = await r.json().catch(() => ({}));
		if (!r.ok) {
			const e = new Error(data.error || "HTTP " + r.status);
			e.status = r.status;
			throw e;
		}
		return data;
	}

	function toast(text, bad) {
		const t = $("#toast");
		t.textContent = text;
		t.classList.toggle("bad", !!bad);
		t.hidden = false;
		clearTimeout(toast.t);
		toast.t = setTimeout(() => (t.hidden = true), 4200);
	}

	function gate(title, text) {
		$("#app").hidden = true;
		$("#gate").hidden = false;
		$("#gate-title").textContent = title;
		$("#gate-text").innerHTML = text;
	}

	async function boot() {
		const code = location.hash.replace(/^#/, "").trim();
		if (code) {
			history.replaceState(null, "", location.pathname);
			try {
				const r = await api("/v1/bridge/panel/login", { code });
				token = r.token;
				store.set("vb-token", token);
			} catch (e) {
				gate("Link used or expired", esc(e.message) + "<br><br>Run <code>/velobridge panel</code> on your server for a fresh link.");
				return;
			}
		} else {
			token = store.get("vb-token");
		}
		if (!token) {
			gate("Owner panel", "Open the link from <code>/velobridge panel</code> on your server. Each link works once, for 15 minutes.");
			return;
		}
		try {
			await load(true);
		} catch (e) {
			store.set("vb-token", null);
			gate("Session ended", esc(e.message));
			return;
		}
		$("#gate").hidden = true;
		$("#app").hidden = false;
		fetch("/modules.json").then((r) => r.json()).then((m) => { modules = m; if (ui.tab === "settings") render(); }).catch(() => {});
		setInterval(() => load(false).catch(() => {}), 15000);
		wire();
		render();
	}

	async function load(first) {
		const fresh = await api("/v1/bridge/panel/overview");
		ov = fresh;
		$("#srv-name").textContent = ov.name || "Your server";
		$("#srv-dot").classList.toggle("live", !!ov.online);
		$("#srv-sync").textContent = ov.online ? "online · synced " + ago(ov.lastSync) : ov.lastSync ? "offline · " + ago(ov.lastSync) : "waiting for first sync";
		if (first || !dirty()) {
			base = clone(ov.config);
			cfg = clone(ov.config);
		}
		if (waiting) {
			if (ov.applyError) {
				saveState("error", "Your server couldn't apply it: " + ov.applyError);
				waiting = 0;
			} else if (ov.appliedVersion >= waiting) {
				waiting = 0;
				base = clone(ov.config);
				cfg = clone(ov.config);
				saveState("done");
			}
		}
		if (!first && !isEditing()) render();
	}

	/** Don't re-render under someone typing. */
	function isEditing() {
		const a = document.activeElement;
		return a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA" || a.tagName === "SELECT");
	}

	function ago(t) {
		if (!t) return "never";
		const s = Math.max(0, (Date.now() - t) / 1000);
		return s < 60 ? Math.round(s) + "s ago" : s < 3600 ? Math.round(s / 60) + "m ago" : s < 86400 ? Math.round(s / 3600) + "h ago" : Math.round(s / 86400) + "d ago";
	}

	// ---------------------------------------------------------------- config helpers

	function get(path, fallback) {
		let o = cfg;
		for (const k of path.split(".")) {
			if (o == null || typeof o !== "object") return fallback;
			o = o[k];
		}
		return o === undefined || o === null ? fallback : o;
	}

	function set(path, value) {
		const keys = path.split(".");
		let o = cfg;
		for (let i = 0; i < keys.length - 1; i++) {
			if (o[keys[i]] == null || typeof o[keys[i]] !== "object" || Array.isArray(o[keys[i]])) o[keys[i]] = {};
			o = o[keys[i]];
		}
		if (value === undefined) delete o[keys[keys.length - 1]];
		else o[keys[keys.length - 1]] = value;
		changed();
	}

	function section(path) {
		const v = get(path);
		if (v == null || typeof v !== "object" || Array.isArray(v)) {
			set(path, {});
			return get(path);
		}
		return v;
	}

	const dirty = () => cfg && base && JSON.stringify(cfg) !== JSON.stringify(base);

	function changed() {
		if (waiting) return;
		saveState(dirty() ? "dirty" : "clean");
	}

	function saveState(state, text) {
		const bar = $("#savebar");
		bar.classList.remove("wait");
		$("#save").disabled = false;
		$("#discard").hidden = false;
		if (state === "clean") { bar.hidden = true; return; }
		bar.hidden = false;
		if (state === "dirty") $("#save-text").textContent = "Unsaved changes";
		if (state === "wait") {
			bar.classList.add("wait");
			$("#save-text").textContent = ov.online ? "Sending to your server…" : "Saved - your server picks it up when it's online";
			$("#save").disabled = true;
			$("#discard").hidden = true;
		}
		if (state === "done") {
			$("#save-text").textContent = "Live on your server ✓";
			$("#save").disabled = true;
			$("#discard").hidden = true;
			setTimeout(() => { if (!dirty() && !waiting) bar.hidden = true; }, 2600);
		}
		if (state === "error") $("#save-text").textContent = text;
	}

	async function save() {
		try {
			const r = await api("/v1/bridge/panel/config", { config: cfg });
			waiting = r.version;
			saveState("wait");
			const poll = setInterval(async () => {
				if (!waiting) { clearInterval(poll); render(); return; }
				await load(false).catch(() => {});
			}, 3000);
		} catch (e) {
			toast(e.message, true);
		}
	}

	// ---------------------------------------------------------------- form builders (return HTML; wired by data-*)

	const field = (label, input, hint) => `<label class="field"><span>${esc(label)}</span>${input}${hint ? `<small>${hint}</small>` : ""}</label>`;
	const text = (path, label, hint, ph) => field(label, `<input class="inp" data-p="${path}" value="${esc(get(path, ""))}" placeholder="${esc(ph || "")}">`, hint);
	const mono = (path, label, hint, ph) => field(label, `<input class="inp mono" data-p="${path}" value="${esc(get(path, ""))}" placeholder="${esc(ph || "")}">`, hint);
	const num = (path, label, hint, min, max, step) => field(label, `<input class="inp" type="number" data-p="${path}" data-num value="${esc(get(path, ""))}" ${min != null ? `min="${min}"` : ""} ${max != null ? `max="${max}"` : ""} step="${step || "any"}">`, hint);
	const area = (path, label, hint) => field(label, `<textarea class="inp" data-p="${path}">${esc(get(path, ""))}</textarea>`, hint);
	const color = (path, label, hint) => {
		const v = get(path, "#5aa9ff");
		return field(label, `<div class="inp-color"><input type="color" data-p="${path}" data-color value="${esc(/^#[0-9a-f]{6}$/i.test(v) ? v : "#5aa9ff")}"><input class="inp mono" data-p="${path}" value="${esc(v)}"></div>`, hint);
	};
	const toggle = (path, label, hint, def = false) => `<label class="switch"><input type="checkbox" data-p="${path}" data-bool ${get(path, def) ? "checked" : ""}><span class="knob"></span><span><b>${esc(label)}</b>${hint ? `<small>${hint}</small>` : ""}</span></label>`;
	const select = (path, label, options, hint, def) => field(label, `<select class="inp" data-p="${path}">${options.map((o) => {
		const [v, l] = Array.isArray(o) ? o : [o, o];
		return `<option value="${esc(v)}" ${String(get(path, def ?? "")) === String(v) ? "selected" : ""}>${esc(l)}</option>`;
	}).join("")}</select>`, hint);
	const chips = (path, label, hint, ph) => field(label, `<div class="chips" data-chips="${path}">${get(path, []).map((v, i) => `<span class="chip">${esc(v)}<button type="button" data-chip-del="${i}" aria-label="Remove">×</button></span>`).join("")}<input placeholder="${esc(ph || "Type and press Enter")}"></div>`, hint);
	/** A list of objects: one row per entry. cols: [key, label, kind(text|num|bool|select:..), placeholder] */
	function rows(path, cols, addLabel, def) {
		const list = get(path, []) || [];
		return `<div class="rows" data-rows="${path}">${list.map((r, i) => `<div class="row-ed">${cols.map(([k, l, kind, ph]) => {
			const v = r?.[k] ?? "";
			const p = `${path}.${i}.${k}`;
			if (kind === "num") return field(l, `<input class="inp" type="number" step="any" data-p="${p}" data-num value="${esc(v)}">`);
			if (kind === "bool") return field(l, `<select class="inp" data-p="${p}" data-boolsel><option value="true" ${v === true ? "selected" : ""}>yes</option><option value="false" ${v !== true ? "selected" : ""}>no</option></select>`);
			if (kind?.startsWith("select:")) return field(l, `<select class="inp" data-p="${p}">${kind.slice(7).split("|").map((o) => `<option ${String(v) === o ? "selected" : ""}>${esc(o)}</option>`).join("")}</select>`);
			return field(l, `<input class="inp mono" data-p="${p}" value="${esc(v)}" placeholder="${esc(ph || "")}">`);
		}).join("")}<button class="icon-btn" type="button" data-row-del="${path}" data-i="${i}" aria-label="Remove">${SVG_X}</button></div>`).join("")}
		<div><button class="btn btn-sm" type="button" data-row-add="${path}" data-def='${esc(JSON.stringify(def || {}))}'>+ ${esc(addLabel)}</button></div></div>`;
	}
	/** key -> value map (transfer targets, world music). */
	function kv(path, kLabel, vLabel, addLabel, kPh, vPh) {
		const m = get(path, {}) || {};
		return `<div class="rows">${Object.entries(m).map(([k, v]) => `<div class="row-ed">${field(kLabel, `<input class="inp mono" data-kv-key="${path}" data-old="${esc(k)}" value="${esc(k)}">`)}${field(vLabel, `<input class="inp mono" data-p="${path}.${k}" value="${esc(v)}">`)}<button class="icon-btn" type="button" data-kv-del="${path}" data-k="${esc(k)}" aria-label="Remove">${SVG_X}</button></div>`).join("")}
		<div class="list-add"><input class="inp mono" placeholder="${esc(kPh)}" data-kv-new-k="${path}"><input class="inp mono" placeholder="${esc(vPh)}" data-kv-new-v="${path}"><button class="btn btn-sm" type="button" data-kv-add="${path}">+ ${esc(addLabel)}</button></div></div>`;
	}

	function bindForms(root) {
		$$("[data-p]", root).forEach((el) => {
			const path = el.dataset.p;
			const ev = el.tagName === "SELECT" || el.type === "checkbox" || el.type === "color" ? "change" : "input";
			el.addEventListener(ev, () => {
				let v = el.type === "checkbox" ? el.checked : el.value;
				if ("num" in el.dataset) v = el.value === "" ? undefined : Number(el.value);
				if ("boolsel" in el.dataset) v = el.value === "true";
				set(path, v);
				if ("color" in el.dataset) { const twin = el.parentElement.querySelector(".inp"); if (twin) twin.value = v; }
				if (el.closest("[data-live]")) livePreview();
			});
			if (ev === "input") el.addEventListener("change", () => { if (el.closest("[data-rerender]")) render(); });
		});
		$$("[data-chips]", root).forEach((box) => {
			const path = box.dataset.chips;
			const input = box.querySelector("input");
			input.addEventListener("keydown", (e) => {
				if ((e.key === "Enter" || e.key === ",") && input.value.trim()) {
					e.preventDefault();
					set(path, [...get(path, []), input.value.trim()]);
					render();
					$(`[data-chips="${path}"] input`)?.focus();
				} else if (e.key === "Backspace" && !input.value && get(path, []).length) {
					set(path, get(path, []).slice(0, -1));
					render();
					$(`[data-chips="${path}"] input`)?.focus();
				}
			});
			$$("[data-chip-del]", box).forEach((b) => b.addEventListener("click", () => {
				const l = [...get(path, [])];
				l.splice(+b.dataset.chipDel, 1);
				set(path, l);
				render();
			}));
		});
		$$("[data-row-add]", root).forEach((b) => b.addEventListener("click", () => {
			const path = b.dataset.rowAdd;
			set(path, [...(get(path, []) || []), JSON.parse(b.dataset.def || "{}")]);
			render();
		}));
		$$("[data-row-del]", root).forEach((b) => b.addEventListener("click", () => {
			const path = b.dataset.rowDel;
			const l = [...(get(path, []) || [])];
			l.splice(+b.dataset.i, 1);
			set(path, l);
			render();
		}));
		$$("[data-kv-add]", root).forEach((b) => b.addEventListener("click", () => {
			const path = b.dataset.kvAdd;
			const k = $(`[data-kv-new-k="${path}"]`).value.trim();
			const v = $(`[data-kv-new-v="${path}"]`).value.trim();
			if (!k) return;
			set(path, { ...(get(path, {}) || {}), [k]: v });
			render();
		}));
		$$("[data-kv-del]", root).forEach((b) => b.addEventListener("click", () => {
			const path = b.dataset.kvDel;
			const m = { ...(get(path, {}) || {}) };
			delete m[b.dataset.k];
			set(path, m);
			render();
		}));
		$$("[data-kv-key]", root).forEach((el) => el.addEventListener("change", () => {
			const path = el.dataset.kvKey;
			const m = get(path, {}) || {};
			const old = el.dataset.old, k = el.value.trim();
			if (!k || k === old) return;
			const out = {};
			for (const [a, v] of Object.entries(m)) out[a === old ? k : a] = v;
			set(path, out);
			render();
		}));
	}

	// ---------------------------------------------------------------- tooltip

	function showTip(html, x, y) {
		const t = $("#tip");
		t.innerHTML = html;
		t.hidden = false;
		const r = t.getBoundingClientRect();
		t.style.left = Math.min(innerWidth - r.width - 8, x + 14) + "px";
		t.style.top = Math.max(8, y - r.height - 10) + "px";
	}
	const hideTip = () => ($("#tip").hidden = true);

	// ---------------------------------------------------------------- charts

	/** Lines over time: series [{name, color, pts: [[t, v]]}]; one y axis, crosshair + tooltip, labels at the line ends. */
	function lineChart(el, series, opts = {}) {
		const W = 720, H = 220, L = 34, R = 84, T = 10, B = 24;
		const all = series.flatMap((s) => s.pts);
		if (all.length < 2) { el.innerHTML = `<div class="empty">Not enough data yet - the plugin sends numbers every minute.</div>`; return; }
		const t0 = Math.min(...all.map((p) => p[0])), t1 = Math.max(...all.map((p) => p[0]));
		const vmax = Math.max(4, ...all.map((p) => p[1]));
		const step = niceStep(vmax / 4);
		const ymax = Math.ceil(vmax / step) * step;
		const x = (t) => L + ((t - t0) / Math.max(1, t1 - t0)) * (W - L - R);
		const y = (v) => T + (1 - v / ymax) * (H - T - B);
		let g = "";
		for (let v = 0; v <= ymax; v += step) g += `<line class="grid-l" x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}"/><text class="axis-t" x="${L - 6}" y="${y(v) + 3}" text-anchor="end">${v}</text>`;
		const ticks = 5;
		for (let i = 0; i <= ticks; i++) {
			const t = t0 + ((t1 - t0) * i) / ticks;
			g += `<text class="axis-t" x="${x(t)}" y="${H - 6}" text-anchor="middle">${opts.tick ? opts.tick(t) : ""}</text>`;
		}
		const paths = series.map((s) => {
			const d = s.pts.map((p, i) => (i ? "L" : "M") + x(p[0]).toFixed(1) + " " + y(p[1]).toFixed(1)).join("");
			const last = s.pts[s.pts.length - 1];
			return `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>
				<circle cx="${x(last[0])}" cy="${y(last[1])}" r="4" fill="${s.color}" stroke="#111113" stroke-width="2"/>
				<text class="lbl" x="${x(last[0]) + 9}" y="${y(last[1]) + 4}">${esc(s.name)} ${fmt(last[1])}</text>`;
		});
		// keep end labels apart
		el.innerHTML = `<div class="legend">${series.map((s) => `<span><i style="background:${s.color}"></i>${esc(s.name)}</span>`).join("")}</div>
			<div class="chart"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(opts.label || "chart")}">${g}${paths.join("")}
			<line class="xh" x1="0" x2="0" y1="${T}" y2="${H - B}" stroke="#8a8a92" stroke-dasharray="3 3" visibility="hidden"/>
			${series.map((s, i) => `<circle class="xd${i}" r="4" fill="${s.color}" stroke="#111113" stroke-width="2" visibility="hidden"/>`).join("")}
			<rect x="${L}" y="${T}" width="${W - L - R}" height="${H - T - B}" fill="transparent" class="hit"/></svg></div>`;
		fixLabels(el.querySelector("svg"));
		const svg = el.querySelector("svg"), hit = svg.querySelector(".hit"), xh = svg.querySelector(".xh");
		hit.addEventListener("pointermove", (e) => {
			const r = svg.getBoundingClientRect();
			const px = ((e.clientX - r.left) / r.width) * W;
			const t = t0 + ((px - L) / (W - L - R)) * (t1 - t0);
			let html = `<b>${esc(opts.tipTime ? opts.tipTime(t) : "")}</b>`;
			let cx = null;
			series.forEach((s, i) => {
				let best = s.pts[0];
				for (const p of s.pts) if (Math.abs(p[0] - t) < Math.abs(best[0] - t)) best = p;
				cx = x(best[0]);
				const dot = svg.querySelector(".xd" + i);
				dot.setAttribute("cx", x(best[0]));
				dot.setAttribute("cy", y(best[1]));
				dot.setAttribute("visibility", "visible");
				html += `<div class="row"><i style="background:${s.color}"></i>${esc(s.name)}<span>${fmt(best[1])}</span></div>`;
				if (i === 0 && opts.tipTime) html = html.replace(/<b>.*?<\/b>/, `<b>${esc(opts.tipTime(best[0]))}</b>`);
			});
			xh.setAttribute("x1", cx);
			xh.setAttribute("x2", cx);
			xh.setAttribute("visibility", "visible");
			showTip(html, e.clientX, e.clientY);
		});
		hit.addEventListener("pointerleave", () => {
			hideTip();
			$$("[class^=xd], .xh", svg).forEach((n) => n.setAttribute("visibility", "hidden"));
		});
	}

	function fixLabels(svg) {
		const labels = [...svg.querySelectorAll(".lbl")].sort((a, b) => +a.getAttribute("y") - +b.getAttribute("y"));
		for (let i = 1; i < labels.length; i++) {
			const py = +labels[i - 1].getAttribute("y"), cy = +labels[i].getAttribute("y");
			if (cy - py < 14) labels[i].setAttribute("y", py + 14);
		}
	}

	function niceStep(raw) {
		const p = Math.pow(10, Math.floor(Math.log10(Math.max(1, raw))));
		for (const m of [1, 2, 5, 10]) if (m * p >= raw) return m * p;
		return 10 * p;
	}

	/** Ranked horizontal bars, one hue (magnitude, not identity). */
	function barList(entries, max = 12, valueFmt = fmt) {
		const list = entries.filter((e) => e[1] > 0).sort((a, b) => b[1] - a[1]).slice(0, max);
		if (!list.length) return `<div class="empty">Nothing yet.</div>`;
		const top = list[0][1];
		return `<div class="bars">${list.map(([k, v]) => `<div class="bar-row" data-tip="${esc(k)}: ${esc(valueFmt(v))}"><span class="name" title="${esc(k)}">${esc(k)}</span><span class="track"><span class="fill" style="width:${Math.max(1, (v / top) * 100)}%"></span></span><span class="val">${esc(valueFmt(v))}</span></div>`).join("")}</div>`;
	}

	/** Joins per weekday x hour: a heat grid in one sequential hue. */
	function heatmap(week) {
		const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
		const max = Math.max(1, ...week);
		const steps = ["--seq-0", "--seq-1", "--seq-2", "--seq-3", "--seq-4", "--seq-5", "--seq-6", "--seq-7"];
		const cell = (v) => (v <= 0 ? steps[0] : steps[Math.min(7, 1 + Math.floor((v / max) * 6.999))]);
		let h = `<div class="heat"><span></span>${Array.from({ length: 24 }, (_, i) => `<span class="h">${i % 3 === 0 ? i : ""}</span>`).join("")}`;
		days.forEach((d, di) => {
			h += `<span class="d">${d}</span>`;
			for (let hr = 0; hr < 24; hr++) {
				const v = week[di * 24 + hr] || 0;
				h += `<span class="cell" style="background:var(${cell(v)})" data-tip="${d} ${String(hr).padStart(2, "0")}:00 - ${fmt(v)} join${v === 1 ? "" : "s"}"></span>`;
			}
		});
		h += `</div><div class="ramp">fewer ${steps.map((s) => `<i style="background:var(${s})"></i>`).join("")} more · server time</div>`;
		const best = week.indexOf(Math.max(...week));
		h += `<details class="table-view"><summary>As a table</summary><div class="scroll-x"><table class="tbl"><tr><th></th>${Array.from({ length: 24 }, (_, i) => `<th>${i}</th>`).join("")}</tr>${days.map((d, di) => `<tr><td class="t">${d}</td>${Array.from({ length: 24 }, (_, hr) => `<td class="num">${week[di * 24 + hr] || 0}</td>`).join("")}</tr>`).join("")}</table></div></details>`;
		return { html: h, best: week[best] > 0 ? `${days[Math.floor(best / 24)]} ${String(best % 24).padStart(2, "0")}:00` : null };
	}

	function bindTips(root) {
		$$("[data-tip]", root).forEach((el) => {
			el.addEventListener("pointermove", (e) => showTip(esc(el.dataset.tip), e.clientX, e.clientY));
			el.addEventListener("pointerleave", hideTip);
		});
	}

	// ---------------------------------------------------------------- pages

	const pages = {
		overview() {
			const live = ov.live || {};
			const share = live.online ? Math.round((live.velo / live.online) * 100) : 0;
			const heat = heatmap(ov.week || []);
			return `<div class="head"><div><span class="kicker">Live · ${esc(live.software || "")} ${esc(live.minecraft || "")}</span><h2>${esc(ov.name || "Your server")}</h2>
				<p>${ov.online ? "Your server is connected." : "Your server hasn't synced for a few minutes - numbers below are from " + ago(ov.lastSync) + "."} Velo Bridge ${esc(live.plugin || "")}.</p></div></div>
			<div class="grid">
				<div class="card c3 stat"><div class="label">Online now</div><div class="value">${fmt(live.online)}</div><div class="sub">of ${fmt(live.max)} slots</div></div>
				<div class="card c3 stat"><div class="label">On Velo now</div><div class="value">${fmt(live.velo)}</div><div class="sub">${share}% of online players</div></div>
				<div class="card c3 stat"><div class="label">Joins tracked</div><div class="value">${fmt(ov.totalJoins)}</div><div class="sub">${fmt(live.seen)} different Velo players ever</div></div>
				<div class="card c3 stat"><div class="label">Mod detections</div><div class="value">${fmt((live.detections || []).length)}</div><div class="sub">recent joins with forbidden mods</div></div>
				<div class="card c8"><div class="card-h"><h3>Players online</h3><div class="seg" id="range"><button data-r="24h" class="${ui.range === "24h" ? "on" : ""}">24 h</button><button data-r="30d" class="${ui.range === "30d" ? "on" : ""}">30 days</button></div></div><div id="line"></div></div>
				<div class="card c4"><div class="card-h"><h3>Velo versions</h3><span class="kicker">online now</span></div>${barList(Object.entries(live.versions || {}), 8)}
					<hr class="sep"><div class="card-h" style="margin-top:12px"><h3>Minecraft versions</h3></div>${barList(Object.entries(live.mcVersions || {}), 6)}</div>
				<div class="card c7"><div class="card-h"><h3>When players join</h3><span class="kicker">${heat.best ? "busiest: " + heat.best : "last weeks"}</span></div>${heat.html}</div>
				<div class="card c5"><div class="card-h"><h3>Velo modules in use</h3><span class="kicker">online now</span></div>${barList(Object.entries(live.modules || {}), 10)}</div>
				<div class="card"><div class="card-h"><h3>Recent joins</h3><span class="kicker">last ${(ov.joins || []).length}</span></div>${joinsTable((ov.joins || []).slice(0, 12))}</div>
			</div>`;
		},
		players() {
			const live = ov.live || {};
			const mods = Object.entries(live.mods || {}).map(([k, v]) => [k.split("|")[1] || k, v]);
			return `<div class="head"><div><span class="kicker">Velo players</span><h2>Players &amp; mods</h2><p>Velo reports each player's mod list when they join (players can switch that off). Mod lists are also written to <code>plugins/VeloBridge/mod-logs/</code>.</p></div></div>
			<div class="grid">
				<div class="card c6"><div class="card-h"><h3>On Velo right now</h3><span class="kicker">${(live.players || []).length}</span></div>${(live.players || []).length ? `<div class="scroll-x"><table class="tbl"><tr><th>Player</th><th>Velo</th><th>Minecraft</th><th>Online for</th></tr>${live.players.map((p) => `<tr><td class="t">${esc(p.name)}</td><td>${esc(p.v)}</td><td>${esc(p.mc)}</td><td>${ago(p.since).replace(" ago", "")}</td></tr>`).join("")}</table></div>` : `<div class="empty">Nobody on Velo right now.</div>`}</div>
				<div class="card c6"><div class="card-h"><h3>Mod detections</h3><span class="kicker">forbidden mods at join</span></div>${(live.detections || []).length ? `<div class="scroll-x"><table class="tbl"><tr><th>Player</th><th>Detected</th><th>When</th></tr>${live.detections.map((d) => `<tr><td class="t">${esc(d.player)}</td><td><span class="pill bad">${esc(d.mods)}</span></td><td>${ago(d.t)}</td></tr>`).join("")}</table></div>` : `<div class="empty">No forbidden mods seen since the server started.</div>`}
					<p class="note">Want a chat line for staff on each detection? Settings → Staff messages → <b>Mod detections</b>.</p></div>
				<div class="card c6"><div class="card-h"><h3>Mods used</h3><span class="kicker">since server start</span></div>${barList(mods, 20)}</div>
				<div class="card c6"><div class="card-h"><h3>Recent joins</h3></div>${joinsTable(ov.joins || [])}</div>
			</div>`;
		},
		settings() {
			const mods = modules.length ? modules : [];
			const dis = new Set(get("disabled-modules", []) || []);
			return `<div class="head"><div><span class="kicker">config.yml</span><h2>Settings</h2><p>Everything in your config, by topic. Saving writes it to your server's config.yml (comments stay) and applies it right away.</p></div></div>
			<div class="grid">
			<div class="card c6"><div class="card-h"><h3>Your server</h3></div><div class="form">
				${text("server.name", "Server name", "Shown on everything your server sends to Velo players (\"From …\").")}
				${color("server.color", "Accent colour", "Toasts, countdowns, progress bars and menus.")}
			</div></div>
			<div class="card c6"><div class="card-h"><h3>Staff messages</h3><span class="kicker">permission velobridge.notify</span></div><div class="form">
				${toggle("notify.on-detection", "Mod detections", "A chat line when someone joins with a forbidden mod.")}
				${toggle("notify.on-join", "Every Velo join", "A chat line for every player joining with Velo.")}
				${toggle("notify.on-disabled-modules", "Switched-off modules", "When someone joins with modules you turned off.")}
				${toggle("notify.log-joins", "Console line per join")}
				${toggle("mod-log.enabled", "Mod log", "Write each Velo player's mods to mod-logs/ and show them here.", true)}
			</div></div>
			<div class="card c6"><div class="card-h"><h3>Discord &amp; friends status</h3></div><div class="form">
				${toggle("presence.enabled", "Server status in Discord", "", true)}
				<div class="fgrid">${text("presence.details", "First line", "%server% %online% %max% %world%")}${text("presence.state", "Second line")}</div>
			</div></div>
			<div class="card c6"><div class="card-h"><h3>Welcome</h3></div><div class="form">
				${toggle("welcome-toast.enabled", "Welcome card", "Shown once to each Velo player.")}
				<div class="fgrid">${text("welcome-toast.title", "Title")}${text("welcome-toast.text", "Text")}</div>
				${select("on-first-join-cutscene", "First-join cutscene", [["", "None"], ...Object.keys(get("cutscenes", {}) || {})], "Played for players new to the server.")}
			</div></div>
			<div class="card c6"><div class="card-h"><h3>World Notes</h3></div><div class="form">
				${toggle("notes.enabled", "World Notes allowed", "", true)}
				${toggle("notes.images", "Pictures on notes", "", true)}
				${toggle("notes.whiteboards", "Whiteboards", "", true)}
				${num("notes.max-per-player", "Most notes per player", "", 0, 500, 1)}
				<p class="section-t">No-note areas</p>
				${rows("notes.blocked-areas", [["name", "Name"], ["world", "World", "text", "world"], ["x1", "x1", "num"], ["z1", "z1", "num"], ["x2", "x2", "num"], ["z2", "z2", "num"]], "Area", { name: "Spawn", world: "world", x1: -100, z1: -100, x2: 100, z2: 100 })}
				<p class="note">Server-wide notes (everyone sees them) are under <b>Server notes</b>.</p>
			</div></div>
			<div class="card c6"><div class="card-h"><h3>Transfers</h3></div><div class="form">
				${kv("transfer-targets", "Name", "Address", "Target", "lobby", "lobby.example.com:25565")}
				<p class="note">Used by <code>/velobridge transfer</code>, menu actions <code>transfer:name</code> and the API. Velo warms up these connections.</p>
			</div></div>
			<div class="card"><div class="card-h"><h3>Resource packs</h3><span class="kicker">downloaded before players join</span></div>
				${rows("precache-packs", [["id", "Pack UUID"], ["url", "URL"], ["sha1", "SHA-1"]], "Pack", { id: "", url: "", sha1: "" })}</div>
			<div class="card"><div class="card-h"><h3>Velo modules switched off here</h3><span class="kicker">${dis.size} off</span></div>
				<p class="note" style="margin:0 0 10px">Enforced while playing on your server; players see which ones you turned off.</p>
				${mods.length ? `<input class="inp search" id="mod-search" placeholder="Search modules"><div class="check-grid" id="mod-grid">${mods.map((m) => `<label class="check" data-name="${esc((m.name + " " + m.id).toLowerCase())}"><input type="checkbox" data-mod="${esc(m.id)}" ${dis.has(m.id) ? "checked" : ""}><span>${esc(m.name)}<br><small class="mute">${esc(m.id)}</small></span></label>`).join("")}</div>` : chips("disabled-modules", "Module ids", "", "freelook")}</div>
			<div class="card c6"><div class="card-h"><h3>Panel &amp; statistics</h3></div><div class="form">
				${toggle("stats", "Velo partner statistics", "Anonymous numbers for the Velo partner list.", true)}
				<p class="note">Console actions in menus (<code>menus.allow-console-actions</code>) can only be switched on in the file on your server.</p>
			</div></div>
			</div>`;
		},
		rules() {
			const fp = new Set(get("mod-rules.forbid-presets", []) || []);
			const rp = new Set(get("mod-rules.recommend-presets", []) || []);
			const preset = (kind, id, label, desc, on) => `<label class="check"><input type="checkbox" data-preset="${kind}" value="${id}" ${on ? "checked" : ""}><span><b>${esc(label)}</b><br><small class="mute">${esc(desc)}</small></span></label>`;
			const cols = [["match", "Match", "select:id|modrinth|name"], ["value", "Value", "text", "freecam"], ["label", "Label", "text", "Freecam"]];
			return `<div class="head"><div><span class="kicker">Mod hints</span><h2>Mod rules</h2><p>Velo checks mods on the player's PC and shows a hint - with one click to remove forbidden mods or add recommended ones (the game restarts and rejoins). Nothing is enforced, nobody gets kicked.</p></div></div>
			<div class="grid">
				<div class="card c6"><div class="card-h"><h3>Not allowed</h3></div><div class="form">
					<div class="check-grid">${preset("forbid", "cheat-clients", "Cheat clients", "Wurst, Meteor, LiquidBounce, Glazed…", fp.has("cheat-clients"))}${preset("forbid", "often-banned", "Often banned", "X-Ray, Freecam, Baritone, auto clickers…", fp.has("often-banned"))}${preset("forbid", "radar", "Radar", "Minimap entity radar", fp.has("radar"))}</div>
					<p class="section-t">Your own</p>${rows("mod-rules.forbid", cols, "Rule", { match: "id", value: "", label: "" })}
					${text("mod-rules.message", "Message", "Shown next to the forbidden mods.")}
				</div></div>
				<div class="card c6"><div class="card-h"><h3>Recommended</h3></div><div class="form">
					<div class="check-grid">${preset("recommend", "performance", "Performance", "Sodium, Lithium…", rp.has("performance"))}${preset("recommend", "voice-chat", "Voice chat", "Simple Voice Chat", rp.has("voice-chat"))}${preset("recommend", "emotes", "Emotes", "Emotecraft", rp.has("emotes"))}${preset("recommend", "shaders", "Shaders", "Iris + Sodium", rp.has("shaders"))}</div>
					<p class="section-t">Your own</p>${rows("mod-rules.recommend", cols, "Recommendation", { match: "modrinth", value: "", label: "" })}
					<p class="note">Players only see recommendations they don't have yet. Use <code>modrinth</code> so Velo can install them.</p>
				</div></div>
			</div>`;
		},
		menus: () => menusPage(),
		hud: () => hudPage(),
		cutscenes: () => cutscenesPage(),
		sound: () => soundPage(),
		notes: () => notesPage(),
		npcs: () => npcsPage(),
	};

	function joinsTable(joins) {
		if (!joins.length) return `<div class="empty">No joins yet.</div>`;
		return `<div class="scroll-x"><table class="tbl"><tr><th>Player</th><th>Client</th><th>Minecraft</th><th>When</th></tr>${joins.map((j) => `<tr><td class="t">${esc(j.name)} ${j.first ? '<span class="pill new">new</span>' : ""}</td><td>${j.version ? `<span class="pill velo">Velo ${esc(j.version)}</span>` : '<span class="mute">other</span>'}</td><td>${esc(j.minecraft || "")}</td><td>${ago(j.t)}</td></tr>`).join("")}</table></div>`;
	}

	// ---------------------------------------------------------------- menus

	function menusPage() {
		const list = section("menus.list");
		const ids = Object.keys(list);
		if (!ui.menu || !list[ui.menu]) ui.menu = ids[0] || null;
		const m = ui.menu ? list[ui.menu] : null;
		const p = `menus.list.${ui.menu}`;
		const types = "button|entry|text|divider|input|toggle|slider";
		return `<div class="head"><div><span class="kicker">Velo windows · chest menus for everyone else</span><h2>Menus</h2><p>Velo players get a real Velo window; players without Velo get a normal chest menu. Open one with its command, <code>/velomenu id</code>, an action <code>menu:id</code> or the API.</p></div></div>
		<div class="split3">
			<div class="card"><div class="list">${ids.map((id) => `<button data-pick-menu="${esc(id)}" class="${id === ui.menu ? "on" : ""}"><span>${esc(list[id]?.title || id)}</span><small>${esc(id)}</small></button>`).join("") || '<div class="empty">No menus yet.</div>'}</div>
				<div class="list-add"><input class="inp mono" id="new-menu" placeholder="new-menu-id"><button class="btn btn-sm" id="add-menu" type="button">Add</button></div></div>
			${m ? `<div class="card form" data-live>
				<div class="card-h"><h3>${esc(ui.menu)}</h3><button class="btn btn-sm btn-ghost" id="del-menu" type="button">Delete menu</button></div>
				<div class="fgrid">${text(p + ".title", "Title")}${text(p + ".subtitle", "Subtitle")}${mono(p + ".command", "Command", "Players type /command to open it.", "warps")}${mono(p + ".permission", "Permission", "Empty = everyone.")}${color(p + ".color", "Accent")}</div>
				<p class="section-t">Items</p>
				<div class="rows">${(m.items || []).map((it, i) => menuItemRow(p, it, i, types)).join("")}</div>
				<div><button class="btn btn-sm" id="add-item" type="button">+ Item</button></div>
				<p class="note">Actions: <code>command:/spawn</code> · <code>console:/give %player% diamond</code> · <code>menu:shop</code> · <code>close</code> · <code>message:Hi</code> · <code>server:lobby</code> · <code>transfer:lobby</code> · <code>cutscene:intro</code> · <code>sound:…</code> · <code>music:…</code>. Several: <code>command:/spawn ; close</code>. Field values: <code>{field-id}</code>.</p>
			</div>
			<div class="card"><div class="card-h"><h3>Preview</h3><span class="kicker">in Velo</span></div><div class="mc"><div id="menu-preview"></div></div></div>` : `<div class="card"><div class="empty">Add a menu on the left.</div></div>`}
		</div>`;
	}

	function menuItemRow(p, it, i, types) {
		const ip = `${p}.items.${i}`;
		const t = it.type || "button";
		const f = [];
		f.push(field("Type", `<select class="inp" data-p="${ip}.type" data-rerender>${types.split("|").map((o) => `<option ${o === t ? "selected" : ""}>${o}</option>`).join("")}</select>`));
		if (t !== "text" && t !== "divider") f.push(field("Id", `<input class="inp mono" data-p="${ip}.id" value="${esc(it.id || "")}">`), field("Label", `<input class="inp" data-p="${ip}.label" value="${esc(it.label || "")}">`));
		if (t === "text") f.push(field("Text", `<input class="inp" data-p="${ip}.text" value="${esc(it.text || "")}">`));
		if (t === "entry") f.push(field("Detail", `<input class="inp" data-p="${ip}.detail" value="${esc(it.detail || "")}">`));
		if (t === "entry" || t === "button") f.push(field("Icon (item)", `<input class="inp mono" data-p="${ip}.icon" value="${esc(it.icon || "")}" placeholder="compass">`));
		if (t === "button") f.push(field("Style", `<select class="inp" data-p="${ip}.style"><option value="">normal</option><option ${it.style === "primary" ? "selected" : ""}>primary</option></select>`));
		if (t === "entry" || t === "button") f.push(field("Action", `<input class="inp mono" data-p="${ip}.action" value="${esc(it.action || "")}" placeholder="command:/spawn ; close">`));
		if (t === "input") f.push(field("Placeholder", `<input class="inp" data-p="${ip}.placeholder" value="${esc(it.placeholder || "")}">`));
		if (t === "toggle") f.push(field("On", `<select class="inp" data-p="${ip}.value" data-boolsel><option value="true" ${it.value === true ? "selected" : ""}>yes</option><option value="false" ${it.value !== true ? "selected" : ""}>no</option></select>`));
		if (t === "slider") ["min", "max", "step", "value"].forEach((k) => f.push(field(k, `<input class="inp" type="number" step="any" data-num data-p="${ip}.${k}" value="${esc(it[k] ?? "")}">`)));
		if (t !== "text" && t !== "divider") f.push(field("Permission", `<input class="inp mono" data-p="${ip}.permission" value="${esc(it.permission || "")}">`));
		return `<div class="row-ed">${f.join("")}<div style="display:flex;gap:4px"><button class="icon-btn" type="button" data-item-move="${i}" data-d="-1" aria-label="Up">${SVG_UP}</button><button class="icon-btn" type="button" data-item-move="${i}" data-d="1" aria-label="Down">${SVG_DOWN}</button><button class="icon-btn" type="button" data-item-del="${i}" aria-label="Remove">${SVG_X}</button></div></div>`;
	}

	const plain = (s) => String(s ?? "").replace(/[&§][0-9a-fk-or]/gi, "");

	function menuPreview() {
		const el = $("#menu-preview");
		const m = get(`menus.list.${ui.menu}`);
		if (!el || !m) return;
		const acc = m.color || get("server.color", "#5aa9ff");
		el.innerHTML = `<div class="velo-win" style="--acc:${esc(acc)}"><div class="wh">${esc(plain(m.title || ui.menu))}<i>×</i></div><div class="wb">
			<div class="from">FROM ${esc(String(get("server.name", "Server")).toUpperCase())}</div>${m.subtitle ? `<div class="sub">${esc(plain(m.subtitle))}</div>` : ""}
			${(m.items || []).map((it) => {
				const ic = it.icon ? `<span class="ic"><img src="${ICON(it.icon)}" alt="" loading="lazy" data-hide-broken></span>` : "";
				switch (it.type || "button") {
					case "text": return `<div class="tx">${esc(plain(it.text))}</div>`;
					case "divider": return `<div class="dv"></div>`;
					case "entry": return `<div class="en">${ic}<div><b>${esc(plain(it.label))}</b>${it.detail ? `<small>${esc(plain(it.detail))}</small>` : ""}</div><em>›</em></div>`;
					case "input": return `<div class="in">${esc(plain(it.label))}<div>${esc(it.placeholder || "")}</div></div>`;
					case "toggle": return `<div class="tg">${esc(plain(it.label))}<i class="${it.value ? "on" : ""}"></i></div>`;
					case "slider": {
						const pct = ((Number(it.value) || 0) - (Number(it.min) || 0)) / Math.max(1e-9, (Number(it.max) || 10) - (Number(it.min) || 0));
						return `<div class="sl">${esc(plain(it.label))}: ${esc(it.value ?? 0)}<i><b style="width:${Math.max(0, Math.min(1, pct)) * 100}%"></b></i></div>`;
					}
					default: return `<div class="bt ${it.style === "primary" ? "primary" : ""}">${esc(plain(it.label))}</div>`;
				}
			}).join("")}</div></div>`;
	}

	function bindMenus(root) {
		$$("[data-pick-menu]", root).forEach((b) => b.addEventListener("click", () => { ui.menu = b.dataset.pickMenu; render(); }));
		$("#add-menu", root)?.addEventListener("click", () => {
			const id = $("#new-menu").value.trim().toLowerCase().replace(/[^a-z0-9_-]/g, "-");
			if (!id) return;
			section("menus.list")[id] = { title: id[0].toUpperCase() + id.slice(1), command: id, items: [{ type: "text", text: "Hello %player%!" }, { type: "button", id: "close", label: "Close", action: "close" }] };
			ui.menu = id;
			changed();
			render();
		});
		$("#del-menu", root)?.addEventListener("click", () => {
			if (!confirm(`Delete the menu "${ui.menu}"?`)) return;
			delete section("menus.list")[ui.menu];
			ui.menu = null;
			changed();
			render();
		});
		const items = () => get(`menus.list.${ui.menu}.items`, []);
		$("#add-item", root)?.addEventListener("click", () => {
			set(`menus.list.${ui.menu}.items`, [...items(), { type: "button", id: "b" + (items().length + 1), label: "Button", action: "" }]);
			render();
		});
		$$("[data-item-del]", root).forEach((b) => b.addEventListener("click", () => {
			const l = [...items()];
			l.splice(+b.dataset.itemDel, 1);
			set(`menus.list.${ui.menu}.items`, l);
			render();
		}));
		$$("[data-item-move]", root).forEach((b) => b.addEventListener("click", () => {
			const l = [...items()], i = +b.dataset.itemMove, j = i + +b.dataset.d;
			if (j < 0 || j >= l.length) return;
			[l[i], l[j]] = [l[j], l[i]];
			set(`menus.list.${ui.menu}.items`, l);
			render();
		}));
		menuPreview();
	}

	// ---------------------------------------------------------------- HUD

	function hudPage() {
		const hud = section("hud");
		const ids = Object.keys(hud);
		if (!ui.hud || !hud[ui.hud]) ui.hud = ids[0] || null;
		const e = ui.hud ? hud[ui.hud] : null;
		const p = `hud.${ui.hud}`;
		const t = e?.type || "text";
		return `<div class="head"><div><span class="kicker">Server HUD</span><h2>HUD elements</h2><p>Text, bars, timers and pictures in every Velo player's HUD - they move the stack in Velo's HUD editor. Placeholders: <code>%player%</code> <code>%health%</code> <code>%max_health%</code> <code>%food%</code> <code>%level%</code> <code>%online%</code> <code>%world%</code> … and PlaceholderAPI.</p></div></div>
		<div class="split3">
			<div class="card"><div class="list">${ids.map((id) => `<button data-pick-hud="${esc(id)}" class="${id === ui.hud ? "on" : ""}"><span>${esc(hud[id]?.title || id)}</span><small>${esc(hud[id]?.type || "text")}</small></button>`).join("") || '<div class="empty">No elements yet.</div>'}</div>
				<div class="list-add"><input class="inp mono" id="new-hud" placeholder="element-id"><button class="btn btn-sm" id="add-hud" type="button">Add</button></div></div>
			${e ? `<div class="card form" data-live>
				<div class="card-h"><h3>${esc(ui.hud)}</h3><button class="btn btn-sm btn-ghost" id="del-hud" type="button">Delete</button></div>
				<div class="fgrid">${select(p + ".type", "Type", ["text", "bar", "timer", "image"], "", "text")}${text(p + ".title", "Title")}${color(p + ".color", "Colour")}${num(p + ".order", "Order", "Lower = higher up.", -99, 99, 1)}</div>
				${t === "text" ? `<div class="fgrid">${text(p + ".text", "Text", "", "%vault_eco_balance%")}${mono(p + ".icon", "Icon (item id)", "", "gold_nugget")}</div>` : ""}
				${t === "bar" ? `<div class="fgrid">${mono(p + ".value", "Value", "", "%health%")}${mono(p + ".max", "Max", "", "%max_health%")}${text(p + ".text", "Text (optional)", "Default: value / max")}</div>` : ""}
				${t === "timer" ? `<div class="fgrid">${field("Ends at", `<input class="inp" type="datetime-local" id="hud-ends" value="${esc(toLocal(e.ends))}">`, "Your local time; counts down on every client.")}</div>` : ""}
				${t === "image" ? `<div class="fgrid">${mono(p + ".url", "Picture URL (https, PNG)", "", "https://…/logo.png")}${num(p + ".width", "Width", "", 4, 256, 1)}${num(p + ".height", "Height", "", 4, 160, 1)}</div>` : ""}
				<div class="fgrid">${chips(p + ".worlds", "Only in worlds", "Empty = everywhere.", "world")}${mono(p + ".permission", "Permission", "Empty = everyone.")}</div>
				${toggle(p + ".enabled", "Shown", "", true)}
			</div>
			<div class="card"><div class="card-h"><h3>Preview</h3><span class="kicker">placeholders as samples</span></div><div class="mc"><div id="hud-preview"></div></div></div>` : `<div class="card"><div class="empty">Add an element on the left.</div></div>`}
		</div>`;
	}

	function toLocal(iso) {
		if (!iso) return "";
		const d = new Date(iso);
		if (isNaN(d)) return "";
		const p = (n) => String(n).padStart(2, "0");
		return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
	}

	const sample = (s) => String(s ?? "").replace(/%health%/g, "16").replace(/%max_health%/g, "20").replace(/%food%/g, "18").replace(/%level%/g, "12").replace(/%online%/g, String(ov.live?.online ?? 24)).replace(/%max%/g, String(ov.live?.max ?? 100)).replace(/%player%/g, "Steve").replace(/%server%/g, get("server.name", "Server")).replace(/%world%/g, "world").replace(/%[a-z0-9_]+%/gi, "1,234");

	function hudPreview() {
		const el = $("#hud-preview");
		if (!el) return;
		const hud = get("hud", {}) || {};
		const list = Object.entries(hud).filter(([, e]) => e && e.enabled !== false).sort((a, b) => (a[1].order || 0) - (b[1].order || 0));
		el.innerHTML = `<div class="hud-stack">${list.map(([id, e]) => {
			const acc = e.color || "#5aa9ff";
			const sel = id === ui.hud ? ' style="outline:1px dashed rgba(255,255,255,.4);outline-offset:2px"' : "";
			switch (e.type) {
				case "image": return `<div class="hud-img" style="width:${Math.min(180, e.width || 64)}px;height:${Math.min(160, e.height || 64)}px"${sel}>${e.url ? `<img src="${esc(e.url)}" alt="" data-hide-broken>` : "picture"}</div>`;
				case "bar": {
					const v = parseFloat(sample(e.value || "0")) || 0, m = parseFloat(sample(e.max || "1")) || 1;
					return `<div class="hud-card" style="--acc:${esc(acc)}"${sel}><div class="t">${esc(plain(e.title))}<span style="color:#fff">${esc(e.text ? sample(e.text) : v + " / " + m)}</span></div><div class="bar"><b style="width:${Math.max(0, Math.min(1, v / m)) * 100}%"></b></div></div>`;
				}
				case "timer": {
					const left = Math.max(0, (Date.parse(e.ends) || 0) - Date.now()) / 1000;
					const tx = left >= 86400 ? Math.floor(left / 86400) + "d " + Math.floor((left % 86400) / 3600) + "h" : `${Math.floor(left / 3600)}:${String(Math.floor(left / 60) % 60).padStart(2, "0")}:${String(Math.floor(left % 60)).padStart(2, "0")}`;
					return `<div class="hud-card" style="--acc:${esc(acc)}"${sel}><div class="t">${esc(plain(e.title))}</div><div class="v" style="color:${esc(acc)}">${esc(tx)}</div></div>`;
				}
				default: return `<div class="hud-card" style="--acc:${esc(acc)}"${sel}>${e.title ? `<div class="t">${esc(plain(e.title))}</div>` : ""}<div class="v">${e.icon ? `<img src="${ICON(e.icon)}" alt="" data-hide-broken>` : ""}${esc(plain(sample(e.text)))}</div></div>`;
			}
		}).join("")}</div>`;
	}

	function bindHud(root) {
		$$("[data-pick-hud]", root).forEach((b) => b.addEventListener("click", () => { ui.hud = b.dataset.pickHud; render(); }));
		$("#add-hud", root)?.addEventListener("click", () => {
			const id = $("#new-hud").value.trim().toLowerCase().replace(/[^a-z0-9_-]/g, "-");
			if (!id) return;
			section("hud")[id] = { type: "text", title: id[0].toUpperCase() + id.slice(1), text: "", color: "#5aa9ff" };
			ui.hud = id;
			changed();
			render();
		});
		$("#del-hud", root)?.addEventListener("click", () => { delete section("hud")[ui.hud]; ui.hud = null; changed(); render(); });
		$("#hud-ends", root)?.addEventListener("change", (e) => { set(`hud.${ui.hud}.ends`, e.target.value ? new Date(e.target.value).toISOString().replace(/\.\d+Z$/, "Z") : undefined); hudPreview(); });
		$(`[data-p="hud.${ui.hud}.type"]`, root)?.addEventListener("change", () => render());
		hudPreview();
	}

	// ---------------------------------------------------------------- cutscenes

	function cutscenesPage() {
		const all = section("cutscenes");
		const ids = Object.keys(all);
		if (!ui.cut || !all[ui.cut]) ui.cut = ids[0] || null;
		const c = ui.cut ? all[ui.cut] : null;
		const p = `cutscenes.${ui.cut}`;
		return `<div class="head"><div><span class="kicker">Camera flights</span><h2>Cutscenes</h2><p>Record in game: <code>/vb cutscene record intro</code>, fly to each spot and <code>/vb cutscene point</code>, then <code>/vb cutscene save</code>. Fine-tune here. The camera glides through the points on a smooth curve; players without Velo see the title.</p></div></div>
		<div class="split">
			<div class="card"><div class="list">${ids.map((id) => `<button data-pick-cut="${esc(id)}" class="${id === ui.cut ? "on" : ""}"><span>${esc(id)}</span><small>${(all[id]?.points || []).length} pts</small></button>`).join("") || '<div class="empty">No cutscenes yet - record one in game.</div>'}</div>
				<div class="list-add"><input class="inp mono" id="new-cut" placeholder="cutscene-id"><button class="btn btn-sm" id="add-cut" type="button">Add</button></div></div>
			${c ? `<div class="grid"><div class="card c7 form">
				<div class="card-h"><h3>${esc(ui.cut)}</h3><button class="btn btn-sm btn-ghost" id="del-cut" type="button">Delete</button></div>
				<div class="fgrid">${num(p + ".duration", "Length (seconds)", "", 1, 600, 0.5)}${text(p + ".title", "Title", "&-colours, placeholders")}${text(p + ".subtitle", "Subtitle")}${mono(p + ".world", "World")}${mono(p + ".end-action", "When it ends", "An action, e.g. menu:start", "")}</div>
				${toggle(p + ".letterbox", "Letterbox bars", "", true)}${toggle(p + ".lock", "Hold the player still", "Their real position never changes either way.", true)}
				<p class="section-t">Points <small class="mute">x, y, z, yaw, pitch</small></p>
				<div class="rows">${(c.points || []).map((pt, i) => `<div class="row-ed">${["x", "y", "z", "yaw", "pitch"].map((k, j) => field(k, `<input class="inp" type="number" step="any" data-pt="${i}" data-j="${j}" value="${esc(pt?.[j] ?? 0)}">`)).join("")}<div style="display:flex;gap:4px"><button class="icon-btn" type="button" data-pt-move="${i}" data-d="-1" aria-label="Up">${SVG_UP}</button><button class="icon-btn" type="button" data-pt-move="${i}" data-d="1" aria-label="Down">${SVG_DOWN}</button><button class="icon-btn" type="button" data-pt-del="${i}" aria-label="Remove">${SVG_X}</button></div></div>`).join("")}</div>
				<div><button class="btn btn-sm" id="add-pt" type="button">+ Point</button></div>
				<p class="note">Try it: <code>/vb cutscene play ${esc(ui.cut)}</code> · for everyone: <code>/vb cutscene play ${esc(ui.cut)} *</code></p>
			</div>
			<div class="card c5"><div class="card-h"><h3>Path from above</h3><span class="kicker">x → · z ↓</span></div><div class="map" id="cut-map"></div><p class="note">Dots are your points, the line is the curve the camera flies; ticks show where it looks.</p></div></div>` : `<div class="card"><div class="empty">Pick or add a cutscene.</div></div>`}
		</div>`;
	}

	function catmull(a, b, c, d, u) {
		const u2 = u * u, u3 = u2 * u;
		return 0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3);
	}

	function cutMap() {
		const el = $("#cut-map");
		const pts = (get(`cutscenes.${ui.cut}.points`, []) || []).map((p) => p.map(Number));
		if (!el) return;
		if (pts.length < 2) { el.innerHTML = `<div class="empty" style="padding:24px">Two points or more draw the path.</div>`; return; }
		const xs = pts.map((p) => p[0]), zs = pts.map((p) => p[2]);
		const minX = Math.min(...xs), maxX = Math.max(...xs), minZ = Math.min(...zs), maxZ = Math.max(...zs);
		const span = Math.max(8, maxX - minX, maxZ - minZ) * 1.25;
		const cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
		const S = 320, px = (x) => ((x - cx) / span + 0.5) * S, pz = (z) => ((z - cz) / span + 0.5) * S;
		let d = "";
		for (let i = 0; i < pts.length - 1; i++) {
			const p0 = pts[Math.max(0, i - 1)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[Math.min(pts.length - 1, i + 2)];
			for (let s = 0; s <= 16; s++) {
				const u = s / 16;
				d += (i || s ? "L" : "M") + px(catmull(p0[0], p1[0], p2[0], p3[0], u)).toFixed(1) + " " + pz(catmull(p0[2], p1[2], p2[2], p3[2], u)).toFixed(1);
			}
		}
		const look = pts.map((p) => {
			const yaw = (p[3] * Math.PI) / 180, l = 16;
			return `<line x1="${px(p[0])}" y1="${pz(p[2])}" x2="${px(p[0]) - Math.sin(yaw) * l}" y2="${pz(p[2]) + Math.cos(yaw) * l}" stroke="#8a8a92" stroke-width="1.5" stroke-linecap="round"/>`;
		}).join("");
		el.innerHTML = `<svg viewBox="0 0 ${S} ${S}" role="img" aria-label="Camera path from above"><path d="${d}" fill="none" stroke="#3987e5" stroke-width="2" stroke-linecap="round"/>${look}${pts.map((p, i) => `<circle cx="${px(p[0])}" cy="${pz(p[2])}" r="5" fill="#3987e5" stroke="#0d0d0f" stroke-width="2" data-tip="Point ${i + 1}: ${p.slice(0, 3).map((v) => Math.round(v)).join(", ")}"/><text x="${px(p[0]) + 8}" y="${pz(p[2]) - 7}" fill="#b8b8be" font-size="11" font-family="JetBrains Mono">${i + 1}</text>`).join("")}</svg>`;
		bindTips(el);
	}

	function bindCuts(root) {
		$$("[data-pick-cut]", root).forEach((b) => b.addEventListener("click", () => { ui.cut = b.dataset.pickCut; render(); }));
		$("#add-cut", root)?.addEventListener("click", () => {
			const id = $("#new-cut").value.trim().toLowerCase().replace(/[^a-z0-9_-]/g, "-");
			if (!id) return;
			section("cutscenes")[id] = { duration: 10, letterbox: true, lock: true, title: "", subtitle: "", world: "world", points: [[0, 80, 0, 0, 10], [20, 85, 20, 90, 15]] };
			ui.cut = id;
			changed();
			render();
		});
		$("#del-cut", root)?.addEventListener("click", () => { delete section("cutscenes")[ui.cut]; ui.cut = null; changed(); render(); });
		const pts = () => (get(`cutscenes.${ui.cut}.points`, []) || []).map((p) => [...p]);
		$$("[data-pt]", root).forEach((el) => el.addEventListener("input", () => {
			const l = pts();
			l[+el.dataset.pt][+el.dataset.j] = Number(el.value) || 0;
			set(`cutscenes.${ui.cut}.points`, l);
			cutMap();
		}));
		$("#add-pt", root)?.addEventListener("click", () => { const l = pts(); const last = l[l.length - 1] || [0, 80, 0, 0, 0]; l.push([last[0] + 10, last[1], last[2] + 10, last[3], last[4]]); set(`cutscenes.${ui.cut}.points`, l); render(); });
		$$("[data-pt-del]", root).forEach((b) => b.addEventListener("click", () => { const l = pts(); l.splice(+b.dataset.ptDel, 1); set(`cutscenes.${ui.cut}.points`, l); render(); }));
		$$("[data-pt-move]", root).forEach((b) => b.addEventListener("click", () => {
			const l = pts(), i = +b.dataset.ptMove, j = i + +b.dataset.d;
			if (j < 0 || j >= l.length) return;
			[l[i], l[j]] = [l[j], l[i]];
			set(`cutscenes.${ui.cut}.points`, l);
			render();
		}));
		cutMap();
	}

	// ---------------------------------------------------------------- sound zones

	function soundPage() {
		const zones = section("sound-zones");
		const ids = Object.keys(zones);
		if (!ui.zone || !zones[ui.zone]) ui.zone = ids[0] || null;
		const z = ui.zone ? zones[ui.zone] : null;
		const p = `sound-zones.${ui.zone}`;
		const xyz = (k, label) => field(label, `<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:6px">${[0, 1, 2].map((j) => `<input class="inp" type="number" step="1" data-xyz="${k}" data-j="${j}" value="${esc((z?.[k] || [])[j] ?? 0)}" aria-label="${"xyz"[j]}">`).join("")}</div>`, "x, y, z");
		return `<div class="head"><div><span class="kicker">Music by area</span><h2>Music &amp; zones</h2><p>When a Velo player walks into a zone, its music fades in; when they leave it fades out or the next zone's takes over. Use sound event ids - vanilla ones or your resource pack's (put an .ogg under a sound event). Quick setup in game: <code>/vb zone pos1</code>, <code>/vb zone pos2</code>, <code>/vb zone create spawn minecraft:music_disc.cat</code>.</p></div></div>
		<div class="split">
			<div class="card"><div class="list">${ids.map((id) => `<button data-pick-zone="${esc(id)}" class="${id === ui.zone ? "on" : ""}"><span>${esc(id)}</span><small>${esc(zones[id]?.world || "")}</small></button>`).join("") || '<div class="empty">No zones yet.</div>'}</div>
				<div class="list-add"><input class="inp mono" id="new-zone" placeholder="zone-id"><button class="btn btn-sm" id="add-zone" type="button">Add</button></div></div>
			<div class="grid">
			${z ? `<div class="card c7 form">
				<div class="card-h"><h3>${esc(ui.zone)}</h3><button class="btn btn-sm btn-ghost" id="del-zone" type="button">Delete</button></div>
				<div class="fgrid">${mono(p + ".world", "World")}${mono(p + ".sound", "Sound", "", "minecraft:music_disc.cat")}${num(p + ".volume", "Volume", "0 - 1", 0, 1, 0.05)}${num(p + ".priority", "Priority", "Overlapping zones: higher wins.", -99, 99, 1)}</div>
				<div class="fgrid">${xyz("from", "Corner 1")}${xyz("to", "Corner 2")}</div>
				${toggle(p + ".loop", "Loop", "", true)}${toggle(p + ".enabled", "Active", "", true)}
			</div>
			<div class="card c5"><div class="card-h"><h3>Zones in ${esc(z.world || "world")}</h3><span class="kicker">from above</span></div><div class="map" id="zone-map"></div></div>` : ""}
			<div class="card form"><div class="card-h"><h3>World music</h3><span class="kicker">when no zone applies</span></div>
				${kv("world-music", "World", "Sound", "World music", "world_nether", "minecraft:music.nether.basalt_deltas")}</div>
			</div>
		</div>`;
	}

	function zoneMap() {
		const el = $("#zone-map");
		const zones = get("sound-zones", {}) || {};
		const cur = zones[ui.zone];
		if (!el || !cur) return;
		const list = Object.entries(zones).filter(([, z]) => z && (z.world || "world") === (cur.world || "world") && z.from?.length >= 3 && z.to?.length >= 3);
		const xs = list.flatMap(([, z]) => [z.from[0], z.to[0]]).map(Number), zs = list.flatMap(([, z]) => [z.from[2], z.to[2]]).map(Number);
		if (!xs.length) { el.innerHTML = '<div class="empty" style="padding:24px">Set the corners.</div>'; return; }
		const minX = Math.min(...xs), maxX = Math.max(...xs), minZ = Math.min(...zs), maxZ = Math.max(...zs);
		const span = Math.max(16, maxX - minX, maxZ - minZ) * 1.2, cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2, S = 320;
		const px = (x) => ((x - cx) / span + 0.5) * S, pz = (z) => ((z - cz) / span + 0.5) * S;
		el.innerHTML = `<svg viewBox="0 0 ${S} ${S}" role="img" aria-label="Zones from above">${list.map(([id, z]) => {
			const x1 = px(Math.min(z.from[0], z.to[0])), x2 = px(Math.max(z.from[0], z.to[0]) + 1), y1 = pz(Math.min(z.from[2], z.to[2])), y2 = pz(Math.max(z.from[2], z.to[2]) + 1);
			const on = id === ui.zone;
			return `<rect x="${x1}" y="${y1}" width="${Math.max(2, x2 - x1)}" height="${Math.max(2, y2 - y1)}" rx="3" fill="${on ? "rgba(57,135,229,.22)" : "rgba(255,255,255,.05)"}" stroke="${on ? "#3987e5" : "#2c2c31"}" stroke-width="${on ? 2 : 1}" data-tip="${esc(id)} · ${esc(z.sound || "")}"/><text x="${x1 + 6}" y="${y1 + 15}" fill="#b8b8be" font-size="11" font-family="Inter">${esc(id)}</text>`;
		}).join("")}</svg>`;
		bindTips(el);
	}

	function bindSound(root) {
		$$("[data-pick-zone]", root).forEach((b) => b.addEventListener("click", () => { ui.zone = b.dataset.pickZone; render(); }));
		$("#add-zone", root)?.addEventListener("click", () => {
			const id = $("#new-zone").value.trim().toLowerCase().replace(/[^a-z0-9_-]/g, "-");
			if (!id) return;
			section("sound-zones")[id] = { world: "world", from: [-20, 0, -20], to: [20, 320, 20], sound: "minecraft:music_disc.cat", volume: 0.8, loop: true, priority: 0 };
			ui.zone = id;
			changed();
			render();
		});
		$("#del-zone", root)?.addEventListener("click", () => { delete section("sound-zones")[ui.zone]; ui.zone = null; changed(); render(); });
		$$("[data-xyz]", root).forEach((el) => el.addEventListener("input", () => {
			const k = el.dataset.xyz;
			const v = [...(get(`sound-zones.${ui.zone}.${k}`, [0, 0, 0]) || [0, 0, 0])];
			v[+el.dataset.j] = Number(el.value) || 0;
			set(`sound-zones.${ui.zone}.${k}`, v);
			zoneMap();
		}));
		zoneMap();
	}

	// ---------------------------------------------------------------- server notes

	function notesPage() {
		const notes = ov.notes || [];
		const f = ui.noteFilter.toLowerCase();
		const shown = notes.filter((n) => !f || (n.ownerName + " " + n.title + " " + n.text).toLowerCase().includes(f));
		const owners = {};
		notes.forEach((n) => (owners[n.ownerName] = (owners[n.ownerName] || 0) + 1));
		const on = get("notes.server-wide.enabled", false);
		return `<div class="head"><div><span class="kicker">World Notes for everyone</span><h2>Server notes</h2><p>With server notes on, every Velo player can pin a note that everyone on your server sees - only in the areas you allow. They're stored on your server; remove any of them here or with <code>/vb notes</code>. Staff with <code>velobridge.notes.manage</code> can also change and remove them in Velo.</p></div></div>
		<div class="grid">
			<div class="card c5 form"><div class="card-h"><h3>Settings</h3><span class="kicker">${on ? "on" : "off"}</span></div>
				${toggle("notes.server-wide.enabled", "Server notes", "Players can share notes with everyone here.")}
				<div class="fgrid">${num("notes.server-wide.max-per-player", "Most per player", "0 = no limit", 0, 500, 1)}${mono("notes.server-wide.permission", "Who may place", "Empty = everyone.")}</div>
				${toggle("notes.server-wide.whiteboards", "Whiteboards on server notes", "", true)}
				<p class="section-t">Allowed areas <small class="mute">none = everywhere outside no-note areas</small></p>
				${rows("notes.server-wide.areas", [["name", "Name"], ["world", "World", "text", "world"], ["x1", "x1", "num"], ["z1", "z1", "num"], ["x2", "x2", "num"], ["z2", "z2", "num"]], "Area", { name: "Market", world: "world", x1: 100, z1: 100, x2: 200, z2: 200 })}
			</div>
			<div class="card c7"><div class="card-h"><h3>Notes on your server</h3><span class="kicker">${notes.length}</span></div>
				<div class="list-add" style="margin:0 0 12px"><input class="inp" id="note-filter" placeholder="Search player, title, text" value="${esc(ui.noteFilter)}"><button class="btn btn-sm" id="note-remove" type="button" ${ui.picked.size ? "" : "disabled"}>Remove ${ui.picked.size || ""} selected</button></div>
				${shown.length ? `<div class="scroll-x"><table class="tbl"><tr><th><input type="checkbox" id="note-all" aria-label="Select all"></th><th>Note</th><th>By</th><th>Where</th><th>Changed</th><th></th></tr>${shown.map((n) => `<tr><td><input type="checkbox" data-note="${esc(n.id)}" ${ui.picked.has(n.id) ? "checked" : ""} aria-label="Select"></td><td class="t"><span style="display:inline-block;width:8px;height:8px;border-radius:2px;margin-right:6px;background:#${(n.color >>> 0).toString(16).padStart(6, "0").slice(-6)}"></span>${esc(n.title || "(no title)")}<br><small class="mute">${esc((n.text || "").slice(0, 90))}${n.checks ? ` · ${n.checks} checks` : ""}${n.board ? " · drawing" : ""}</small></td><td>${esc(n.ownerName)}</td><td><code>${esc(n.bw)} ${n.x} ${n.y} ${n.z}</code></td><td>${ago(n.updated)}</td><td><button class="icon-btn" type="button" data-note-del="${esc(n.id)}" aria-label="Remove">${SVG_X}</button></td></tr>`).join("")}</table></div>` : `<div class="empty">${notes.length ? "No match." : on ? "No server notes yet." : "Switch server notes on to let players share notes with everyone."}</div>`}
				${Object.keys(owners).length ? `<p class="section-t" style="margin-top:16px">By player</p><div style="display:flex;flex-wrap:wrap;gap:6px;margin-top:10px">${Object.entries(owners).sort((a, b) => b[1] - a[1]).map(([o, c]) => `<button class="btn btn-sm" type="button" data-clear-owner="${esc(o)}">Remove all ${c} of ${esc(o)}</button>`).join("")}</div>` : ""}
			</div>
		</div>`;
	}

	async function removeNotes(ids) {
		if (!ids.length) return;
		try {
			await api("/v1/bridge/panel/notes/remove", { ids });
			ov.notes = (ov.notes || []).filter((n) => !ids.includes(n.id));
			ids.forEach((i) => ui.picked.delete(i));
			toast(`Removing ${ids.length} note${ids.length === 1 ? "" : "s"} - gone on your server within seconds.`);
			render();
		} catch (e) {
			toast(e.message, true);
		}
	}

	function bindNotes(root) {
		$("#note-filter", root)?.addEventListener("input", (e) => { ui.noteFilter = e.target.value; clearTimeout(bindNotes.t); bindNotes.t = setTimeout(() => { render(); $("#note-filter")?.focus(); }, 250); });
		$$("[data-note]", root).forEach((c) => c.addEventListener("change", () => { c.checked ? ui.picked.add(c.dataset.note) : ui.picked.delete(c.dataset.note); render(); }));
		$("#note-all", root)?.addEventListener("change", (e) => { $$("[data-note]").forEach((c) => (e.target.checked ? ui.picked.add(c.dataset.note) : ui.picked.delete(c.dataset.note))); render(); });
		$("#note-remove", root)?.addEventListener("click", () => { if (confirm(`Remove ${ui.picked.size} note(s) from your server?`)) removeNotes([...ui.picked]); });
		$$("[data-note-del]", root).forEach((b) => b.addEventListener("click", () => removeNotes([b.dataset.noteDel])));
		$$("[data-clear-owner]", root).forEach((b) => b.addEventListener("click", () => {
			const o = b.dataset.clearOwner;
			if (confirm(`Remove every server note of ${o}?`)) removeNotes((ov.notes || []).filter((n) => n.ownerName === o).map((n) => n.id));
		}));
	}

	// ---------------------------------------------------------------- NPC cosmetics (like the Locker)

	const TYPES = [["cape", "Cloaks"], ["wings", "Wings"], ["wear", "Bodywear"], ["aura", "Auras"], ["pet", "Pets"]];
	let viewer = null, viewerFor = null;

	async function loadCatalog() {
		if (catalog) return catalog;
		const r = await fetch("/v1/cosmetics/catalog");
		const d = await r.json();
		catalog = (d.items || []).filter((i) => TYPES.some(([t]) => t === i.type) && i.status !== "hidden");
		return catalog;
	}

	const thumb = (i) => i.custom ? `/v1/cape/${i.id.slice(7)}` : i.type === "aura" ? `https://velo-client.com/assets/auras/${i.id.replace(/^aura_/, "")}.webp`
		: i.files?.card ? `/v1/cosmetics/file/${i.files.card}` : i.files?.preview ? `/v1/cosmetics/file/${i.files.preview}` : "";

	/** The NPC's look as {cape, wings, aura, wear: [{id, color}]} from its config entry. */
	function npcLook(name) {
		const c = get(`npc-cosmetics.${name}`, {}) || {};
		const wear = String(c.wear || "").split(",").map((s) => s.trim()).filter(Boolean).map((s) => { const [id, color] = s.split(":"); return { id, color: color || "" }; });
		return { cape: c.cape || "", wings: c.wings || "", aura: String(c.aura || "").split(";")[0], wear, pet: c.pet ? "pet_" + String(c.pet).replace(/^pet_/, "") : "", skin: c["preview-skin"] || "" };
	}

	function setNpcLook(name, look) {
		const out = {};
		if (look.cape) out.cape = look.cape;
		if (look.wings) out.wings = look.wings;
		if (look.aura) out.aura = look.aura;
		if (look.pet) out.pet = look.pet.replace(/^pet_/, "");
		if (look.wear.length) out.wear = look.wear.map((w) => (w.color ? `${w.id}:${w.color.replace("#", "")}` : w.id)).join(",");
		if (look.skin) out["preview-skin"] = look.skin;
		set(`npc-cosmetics.${name}`, out);
	}

	function npcsPage() {
		const configured = Object.keys(get("npc-cosmetics", {}) || {});
		const found = (ov.npcs || []).map(String);
		const names = [...new Set([...found, ...configured])];
		if (!ui.npc || !names.includes(ui.npc)) ui.npc = names[0] || null;
		const look = ui.npc ? npcLook(ui.npc) : null;
		const worn = look ? [look.cape, look.wings, look.aura, look.pet, ...look.wear.map((w) => w.id)].filter(Boolean) : [];
		return `<div class="head"><div><span class="kicker">FancyNpcs · Citizens</span><h2>NPC cosmetics</h2><p>Dress your NPCs in Velo cloaks (or your own), wings, auras, bodywear and pets - like the Locker. Velo players see them on your server. NPCs come from FancyNpcs or Citizens automatically; any other player NPC works by its UUID.</p></div></div>
		<div class="split3">
			<div class="card"><div class="list">${names.map((n) => `<button data-pick-npc="${esc(n)}" class="${n === ui.npc ? "on" : ""}"><span>${esc(n)}</span><small>${configured.includes(n) ? "dressed" : found.includes(n) ? "" : "missing"}</small></button>`).join("") || '<div class="empty">No NPCs found. Install FancyNpcs or Citizens, or add one by UUID.</div>'}</div>
				<div class="list-add"><input class="inp mono" id="new-npc" placeholder="NPC name or UUID"><button class="btn btn-sm" id="add-npc" type="button">Add</button></div>
				${found.length ? "" : '<p class="note">The NPC list comes with your server\'s next sync (once a minute).</p>'}</div>
			${look ? `<div class="card"><div class="card-h"><h3>${esc(ui.npc)}</h3><span class="kicker">${worn.length} worn</span></div>
				<div class="stage" id="stage"><span class="stage-cap">Preview</span></div>
				<div class="fgrid" style="margin-top:12px">${field("Preview skin", `<input class="inp mono" id="npc-skin" value="${esc(look.skin)}" placeholder="Player name for the preview">`, "Only for this preview - the NPC keeps its own skin.")}</div>
				<div class="worn">${wornRows(look)}</div>
				${worn.length ? `<div style="margin-top:10px"><button class="btn btn-sm btn-ghost" id="npc-clear" type="button">Take everything off</button></div>` : ""}
			</div>
			<div class="card"><div class="card-h"><div class="seg" id="npc-types" style="margin:0">${TYPES.map(([t, l]) => `<button data-t="${t}" class="${ui.npcType === t ? "on" : ""}">${l}</button>`).join("")}</div></div>
				<input class="inp search" id="npc-search" placeholder="Search" value="${esc(ui.npcSearch)}">
				<div class="cat" id="cat"><div class="empty">Loading the Velo catalog…</div></div></div>` : `<div class="card"><div class="empty">Pick an NPC.</div></div>`}
		</div>`;
	}

	/** A catalog item, or a custom cloak ("custom:<hash>") uploaded here. */
	function byId(id) {
		if (String(id).startsWith("custom:")) {
			return { id, type: "cape", name: "Own cloak", custom: true, files: { gif: id } };
		}
		return (catalog || []).find((i) => i.id === id);
	}

	function wornRows(look) {
		const row = (slot, id, extra = "") => {
			const i = byId(id);
			return `<div class="worn-row">${i ? `<img src="${esc(thumb(i))}" alt="">` : '<img alt="">'}<span>${esc(i?.name || id)}</span><small>${slot}</small>${extra}<button class="icon-btn" type="button" data-unwear="${esc(id)}" aria-label="Take off">${SVG_X}</button></div>`;
		};
		let h = "";
		if (look.cape) h += row("cloak", look.cape);
		if (look.wings) h += row("wings", look.wings);
		if (look.aura) h += row("aura", look.aura);
		if (look.pet) h += row("pet", look.pet);
		look.wear.forEach((w) => (h += row(byId(w.id)?.slot || "wear", w.id, `<input type="color" data-tint="${esc(w.id)}" value="${esc(w.color ? "#" + w.color.replace("#", "") : byId(w.id)?.accent || "#ffffff")}" title="Colour">`)));
		return h || '<div class="empty">Nothing worn yet - pick from the catalog.</div>';
	}

	function renderCatalog() {
		const el = $("#cat");
		if (!el || !catalog) return;
		const look = npcLook(ui.npc);
		const on = new Set([look.cape, look.wings, look.aura, look.pet, ...look.wear.map((w) => w.id)]);
		const q = ui.npcSearch.toLowerCase();
		const list = catalog.filter((i) => i.type === ui.npcType && (!q || i.name.toLowerCase().includes(q)));
		const own = look.cape.startsWith("custom:");
		const upload = ui.npcType === "cape" ? `<label class="item ${own ? "on" : ""}" title="Upload a cloak PNG">${own ? `<img src="/v1/cape/${esc(look.cape.slice(7))}" alt="">` : '<span class="ph" style="display:grid;place-items:center;font-size:26px;color:#8a8a92">+</span>'}<span>Own cloak (PNG)</span><input type="file" accept="image/png" id="cape-file" hidden></label>` : "";
		const petNote = ui.npcType === "pet" ? '<p class="note" style="grid-column:1/-1;margin:0 0 4px">Pets follow the NPC in game - the preview here shows the body only.</p>' : "";
		el.innerHTML = petNote + upload + list.map((i) => `<button class="item ${on.has(i.id) ? "on" : ""}" type="button" data-wear="${esc(i.id)}">${thumb(i) ? `<img src="${esc(thumb(i))}" alt="" loading="lazy">` : '<span class="ph"></span>'}<span>${esc(i.name)}</span></button>`).join("") || '<div class="empty">Nothing here.</div>';
		$$("[data-wear]", el).forEach((b) => b.addEventListener("click", () => toggleWear(b.dataset.wear)));
		$("#cape-file", el)?.addEventListener("change", async (e) => {
			const f = e.target.files[0];
			if (!f) return;
			if (f.size > 6 * 1024 * 1024) { toast("That picture is over 6 MB.", true); return; }
			toast("Uploading the cloak…");
			try {
				const r = await fetch(`/v1/bridge/panel/cape?npc=${encodeURIComponent(ui.npc)}`, { method: "POST", headers: { Authorization: "Bearer " + token, "Content-Type": "image/png" }, body: f });
				const d = await r.json().catch(() => ({}));
				if (!r.ok) throw new Error(d.error || "HTTP " + r.status);
				const l = npcLook(ui.npc);
				l.cape = d.capeId;
				setNpcLook(ui.npc, l);
				toast("Cloak uploaded - save to put it on your NPC.");
				render();
			} catch (err) {
				toast(err.message, true);
			}
		});
	}

	function toggleWear(id) {
		const item = byId(id);
		if (!item) return;
		const look = npcLook(ui.npc);
		if (item.type === "cape") look.cape = look.cape === id ? "" : id;
		if (item.type === "wings") look.wings = look.wings === id ? "" : id;
		if (item.type === "aura") look.aura = look.aura === id ? "" : id;
		if (item.type === "pet") look.pet = look.pet === id ? "" : id;
		if (item.type === "wear") {
			if (look.wear.some((w) => w.id === id)) look.wear = look.wear.filter((w) => w.id !== id);
			else look.wear = [...look.wear.filter((w) => (byId(w.id)?.slot || "x") !== (item.slot || "y")), { id, color: "" }];
		}
		setNpcLook(ui.npc, look);
		const scroll = $("#cat")?.scrollTop;
		render();
		if ($("#cat")) $("#cat").scrollTop = scroll || 0;
	}

	async function updateViewer() {
		const stage = $("#stage");
		if (!stage) return;
		const look = npcLook(ui.npc);
		const items = [look.cape, look.wings, ...look.wear.map((w) => w.id), look.aura].map(byId).filter(Boolean);
		const skin = `/v1/skins/${encodeURIComponent(look.skin || "MHF_Steve")}/skin.png`;
		if (!window.velo?.player3d) {
			await new Promise((r) => addEventListener("velo:skin3d", r, { once: true }));
		}
		if (!viewer || viewerFor !== stage) {
			if (viewer) viewer.dispose();
			viewer = await window.velo.player3d(stage, { skin, slim: false, item: null, fileUrl: (h) => (String(h).startsWith("custom:") ? `/v1/cape/${h.slice(7)}` : `/v1/cosmetics/file/${h}`),
				auraUrl: (id, layer) => `https://velo-client.com/assets/auras/${id.replace(/^aura_/, "")}${layer ? "-" + layer : ""}.webp` });
			viewerFor = stage;
			viewer.skinUrl = skin;
		} else if (viewer.skinUrl !== skin) {
			viewer.skinUrl = skin;
			await viewer.setSkin(skin, false).catch(() => {});
		}
		await viewer.setItems(items);
	}

	function bindNpcs(root) {
		$$("[data-pick-npc]", root).forEach((b) => b.addEventListener("click", () => { ui.npc = b.dataset.pickNpc; render(); }));
		$("#add-npc", root)?.addEventListener("click", () => {
			const n = $("#new-npc").value.trim();
			if (!n) return;
			if (!get(`npc-cosmetics.${n}`)) set(`npc-cosmetics.${n}`, {});
			ui.npc = n;
			render();
		});
		$$("[data-t]", $("#npc-types") || document.createElement("div")).forEach((b) => b.addEventListener("click", () => { ui.npcType = b.dataset.t; render(); }));
		$("#npc-search", root)?.addEventListener("input", (e) => { ui.npcSearch = e.target.value; renderCatalog(); });
		$("#npc-skin", root)?.addEventListener("change", (e) => { const l = npcLook(ui.npc); l.skin = e.target.value.trim(); setNpcLook(ui.npc, l); updateViewer(); });
		$("#npc-clear", root)?.addEventListener("click", () => { set(`npc-cosmetics.${ui.npc}`, {}); render(); });
		$$("[data-unwear]", root).forEach((b) => b.addEventListener("click", () => toggleWear(b.dataset.unwear)));
		$$("[data-tint]", root).forEach((c) => c.addEventListener("change", () => {
			const l = npcLook(ui.npc);
			l.wear = l.wear.map((w) => (w.id === c.dataset.tint ? { ...w, color: c.value.replace("#", "") } : w));
			setNpcLook(ui.npc, l);
		}));
		if (!ui.npc) return;
		loadCatalog().then(() => {
			renderCatalog();
			const w = $(".worn");
			if (w) { w.innerHTML = wornRows(npcLook(ui.npc)); $$("[data-unwear]", w).forEach((b) => b.addEventListener("click", () => toggleWear(b.dataset.unwear))); }
			updateViewer().catch((e) => console.warn(e));
		}).catch(() => ($("#cat").innerHTML = '<div class="empty">Couldn\'t load the catalog.</div>'));
	}

	// ---------------------------------------------------------------- render & wiring

	function livePreview() {
		if (ui.tab === "menus") menuPreview();
		if (ui.tab === "hud") hudPreview();
	}

	function render() {
		if (!ov) return;
		const page = $(`[data-page="${ui.tab}"]`);
		if (ui.tab !== "npcs" && viewer) { viewer.dispose(); viewer = null; viewerFor = null; }
		const y = scrollY;
		page.innerHTML = pages[ui.tab]();
		bindForms(page);
		bindTips(page);
		if (ui.tab === "overview") {
			const live = ov.live || {};
			const series = ui.range === "24h"
				? [{ name: "All players", color: "#d95926", pts: (ov.minutes || []).map((m) => [m[0] * 60000, m[1]]) }, { name: "On Velo", color: "#3987e5", pts: (ov.minutes || []).map((m) => [m[0] * 60000, m[2]]) }]
				: [{ name: "All players", color: "#d95926", pts: (ov.hours || []).map((h) => [h[0] * 3600000, h[1]]) }, { name: "On Velo", color: "#3987e5", pts: (ov.hours || []).map((h) => [h[0] * 3600000, h[2]]) }];
			const day = ui.range === "24h";
			lineChart($("#line"), series, {
				label: "Players online over time",
				tick: (t) => day ? new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : new Date(t).toLocaleDateString([], { day: "numeric", month: "short" }),
				tipTime: (t) => day ? new Date(t).toLocaleString([], { weekday: "short", hour: "2-digit", minute: "2-digit" }) : new Date(t).toLocaleString([], { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) + (day ? "" : " (peak)"),
			});
			$$("#range button").forEach((b) => b.addEventListener("click", () => { ui.range = b.dataset.r; render(); }));
			void live;
		}
		if (ui.tab === "settings") {
			$$("[data-mod]", page).forEach((c) => c.addEventListener("change", () => {
				const s = new Set(get("disabled-modules", []) || []);
				c.checked ? s.add(c.dataset.mod) : s.delete(c.dataset.mod);
				set("disabled-modules", [...s]);
			}));
			$("#mod-search", page)?.addEventListener("input", (e) => { const q = e.target.value.toLowerCase(); $$("#mod-grid .check").forEach((l) => (l.hidden = q && !l.dataset.name.includes(q))); });
		}
		if (ui.tab === "rules") {
			$$("[data-preset]", page).forEach((c) => c.addEventListener("change", () => {
				const path = c.dataset.preset === "forbid" ? "mod-rules.forbid-presets" : "mod-rules.recommend-presets";
				const s = new Set(get(path, []) || []);
				c.checked ? s.add(c.value) : s.delete(c.value);
				set(path, [...s]);
			}));
		}
		if (ui.tab === "menus") bindMenus(page);
		if (ui.tab === "hud") bindHud(page);
		if (ui.tab === "cutscenes") bindCuts(page);
		if (ui.tab === "sound") bindSound(page);
		if (ui.tab === "notes") bindNotes(page);
		if (ui.tab === "npcs") bindNpcs(page);
		scrollTo(0, y);
	}

	function wire() {
		$$("#tabs button").forEach((b) => b.addEventListener("click", () => {
			ui.tab = b.dataset.tab;
			$$("#tabs button").forEach((x) => x.classList.toggle("on", x === b));
			$$(".page").forEach((p) => p.classList.toggle("on", p.dataset.page === ui.tab));
			$("#app").classList.remove("nav-open");
			scrollTo(0, 0);
			render();
		}));
		$("#side-toggle").addEventListener("click", () => {
			const open = $("#app").classList.toggle("nav-open");
			$("#side-toggle").setAttribute("aria-expanded", String(open));
		});
		$("#save").addEventListener("click", save);
		$("#discard").addEventListener("click", () => { cfg = clone(base); changed(); render(); });
		addEventListener("beforeunload", (e) => { if (dirty()) { e.preventDefault(); e.returnValue = ""; } });
		setInterval(() => { if (ui.tab === "hud" && !isEditing()) hudPreview(); }, 1000);
	}

	// broken item icons / pictures just disappear (no inline handlers: CSP)
	document.addEventListener("error", (e) => { if (e.target?.hasAttribute?.("data-hide-broken")) e.target.remove(); }, true);
	boot();
})();
