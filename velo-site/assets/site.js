/* velo-client.com - motion and the bits of live data. No dependencies. Everything degrades to a static page. */
"use strict";
document.documentElement.classList.add("js");

const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
const finePointer = matchMedia("(pointer: fine)").matches;
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

/** Small DOM builder - values from the API always go in as text. */
function el(tag, attrs, ...children) {
	const n = document.createElement(tag);
	for (const [k, v] of Object.entries(attrs || {})) {
		if (v === null || v === undefined || v === false) continue;
		if (k === "class") n.className = v;
		else if (k === "style") n.style.cssText = v;
		else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
		else n.setAttribute(k, v === true ? "" : v);
	}
	for (const c of children.flat(Infinity)) if (c !== null && c !== undefined && c !== false) n.append(c instanceof Node ? c : document.createTextNode(String(c)));
	return n;
}
window.velo = { el, $, $$ };

// ---- Navigation ----
const nav = $("#nav");
const onScroll = () => {
	const y = scrollY;
	nav && nav.classList.toggle("scrolled", y > 8);
	const max = document.documentElement.scrollHeight - innerHeight;
	document.documentElement.style.setProperty("--p", max > 0 ? Math.min(1, y / max) : 0);
};
addEventListener("scroll", onScroll, { passive: true });
onScroll();
const toggle = $(".nav-toggle");
if (toggle) {
	toggle.addEventListener("click", () => {
		const open = document.body.classList.toggle("nav-open");
		toggle.setAttribute("aria-expanded", String(open));
	});
	$$(".nav-links a").forEach((a) => a.addEventListener("click", () => document.body.classList.remove("nav-open")));
}

// Signed in on the Store? The nav shows the name instead of "Sign in".
try {
	const name = localStorage.getItem("velo.web.username");
	const acct = $("[data-account]");
	if (name && acct && localStorage.getItem("velo.web.token")) {
		acct.href = "/store#account";
		acct.querySelector("span").textContent = name;
	}
} catch (e) { /* storage blocked - stays "Sign in" */ }

// ---- Reveal on scroll ----
const reveal = new IntersectionObserver((entries) => {
	for (const e of entries) {
		if (e.isIntersecting) {
			e.target.classList.add("in");
			reveal.unobserve(e.target);
		}
	}
}, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });
const observeReveals = (root = document) => $$(".reveal, .reveal-left, .reveal-right", root).forEach((n) => reveal.observe(n));
observeReveals();
window.velo.observeReveals = observeReveals;

// ---- Hero headline: words rise in one after another ----
$$("[data-split]").forEach((h) => {
	let i = 0;
	const walk = (node) => {
		for (const child of [...node.childNodes]) {
			if (child.nodeType === 3) {
				const frag = document.createDocumentFragment();
				child.textContent.split(/(\s+)/).forEach((part) => {
					if (!part) return;
					if (/^\s+$/.test(part)) frag.append(part);
					else frag.append(el("span", { class: "word", style: `--w:${i++}` }, part));
				});
				child.replaceWith(frag);
			} else if (child.nodeType === 1) {
				walk(child);
			}
		}
	};
	walk(h);
});

// ---- Pointer effects: spotlight on cards, magnetic buttons, ripple on click ----
if (finePointer && !reduceMotion) {
	document.addEventListener("pointermove", (e) => {
		const card = e.target.closest && e.target.closest(".spot");
		if (card) {
			const r = card.getBoundingClientRect();
			card.style.setProperty("--mx", e.clientX - r.left + "px");
			card.style.setProperty("--my", e.clientY - r.top + "px");
		}
	}, { passive: true });
	$$(".magnetic").forEach((b) => {
		b.addEventListener("pointermove", (e) => {
			const r = b.getBoundingClientRect();
			b.style.translate = `${(e.clientX - r.left - r.width / 2) * 0.18}px ${(e.clientY - r.top - r.height / 2) * 0.3}px`;
		});
		b.addEventListener("pointerleave", () => (b.style.translate = ""));
	});
	// Cards that lean towards the pointer.
	document.addEventListener("pointermove", (e) => {
		const t = e.target.closest && e.target.closest(".tilt");
		$$(".tilt.tilting").forEach((n) => { if (n !== t) { n.classList.remove("tilting"); n.style.transform = ""; } });
		if (!t) return;
		const r = t.getBoundingClientRect();
		const x = (e.clientX - r.left) / r.width - 0.5;
		const y = (e.clientY - r.top) / r.height - 0.5;
		t.classList.add("tilting");
		t.style.transform = `perspective(900px) rotateX(${(-y * 6).toFixed(2)}deg) rotateY(${(x * 8).toFixed(2)}deg) translateY(-4px)`;
	}, { passive: true });
}
document.addEventListener("click", (e) => {
	const b = e.target.closest && e.target.closest(".btn");
	if (!b || reduceMotion) return;
	const r = b.getBoundingClientRect();
	const size = Math.max(r.width, r.height);
	const dot = el("span", { class: "ripple", style: `width:${size}px;height:${size}px;left:${e.clientX - r.left - size / 2}px;top:${e.clientY - r.top - size / 2}px` });
	b.append(dot);
	setTimeout(() => dot.remove(), 650);
});

// ---- 3D hero stack: leans with the pointer, spreads apart as you scroll ----
const stage = $(".stage");
if (stage && !reduceMotion) {
	let tx = 0, ty = 0, cx = 0, cy = 0, spread = 0, raf = 0;
	const tick = () => {
		cx += (tx - cx) * 0.08;
		cy += (ty - cy) * 0.08;
		const rect = stage.getBoundingClientRect();
		const progress = Math.min(1, Math.max(0, 1 - rect.bottom / (innerHeight + rect.height)));
		spread += (progress - spread) * 0.12;
		stage.style.setProperty("--rx", (18 - spread * 16 - cy * 6).toFixed(2) + "deg");
		stage.style.setProperty("--ry", (cx * 8).toFixed(2) + "deg");
		stage.style.setProperty("--spread", spread.toFixed(3));
		raf = Math.abs(tx - cx) + Math.abs(ty - cy) + Math.abs(progress - spread) > 0.001 ? requestAnimationFrame(tick) : 0;
	};
	const kick = () => { if (!raf) raf = requestAnimationFrame(tick); };
	if (finePointer) {
		addEventListener("pointermove", (e) => {
			tx = e.clientX / innerWidth - 0.5;
			ty = e.clientY / innerHeight - 0.5;
			kick();
		}, { passive: true });
	}
	addEventListener("scroll", kick, { passive: true });
	kick();
}

// ---- Sticky showcase: the screen on the right follows the step you're reading ----
$$("[data-showcase]").forEach((box) => {
	const imgs = $$(".device img", box);
	const label = $(".device-label", box);
	const steps = $$(".step", box);
	const show = (i) => {
		steps.forEach((s, j) => s.classList.toggle("active", i === j));
		imgs.forEach((img, j) => img.classList.toggle("on", i === j));
		if (label && steps[i]) label.textContent = steps[i].dataset.label || "";
	};
	const io = new IntersectionObserver((entries) => {
		for (const e of entries) if (e.isIntersecting) show(steps.indexOf(e.target));
	}, { rootMargin: "-45% 0px -45% 0px" });
	steps.forEach((s) => io.observe(s));
	show(0);
});

// ---- Numbers that count up when they come into view ----
const counter = new IntersectionObserver((entries) => {
	for (const e of entries) {
		if (!e.isIntersecting) continue;
		counter.unobserve(e.target);
		const target = Number(e.target.dataset.count);
		if (reduceMotion || !target) { e.target.textContent = target.toLocaleString("en-US"); continue; }
		const start = performance.now();
		const step = (t) => {
			const p = Math.min(1, (t - start) / 1400);
			e.target.textContent = Math.round(target * (1 - Math.pow(1 - p, 4))).toLocaleString("en-US");
			if (p < 1) requestAnimationFrame(step);
		};
		requestAnimationFrame(step);
	}
}, { threshold: 0.4 });
$$("[data-count]").forEach((n) => counter.observe(n));

// ---- News filters ----
$$("[data-filters]").forEach((bar) => {
	const grid = $(bar.dataset.filters);
	bar.addEventListener("click", (e) => {
		const b = e.target.closest("button");
		if (!b) return;
		$$("button", bar).forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
		const tag = b.dataset.tag;
		$$(".news-card", grid).forEach((c) => {
			const t = (c.querySelector(".tag") || {}).textContent || "";
			c.hidden = tag !== "all" && t.toLowerCase() !== tag;
		});
	});
});

// ---- Cosmetics: live 3D capes, aura animations, wing renders (shared with the Store) ----
const API = "";
const fileUrl = (hash) => `${API}/v1/cosmetics/file/${hash}`;

/** A cape as a small 3D model: its animated GIF mapped onto the cape template's six faces. Drag to turn it. */
function cape3d(url, k = 12, draggable = false) {
	const rig = el("div", { class: "rig" });
	for (const f of ["in", "l", "r", "t", "b", "out"]) {
		const face = el("div", { class: "face " + f });
		face.style.backgroundImage = `url("${url}")`;
		rig.append(face);
	}
	const box = el("div", { class: "cape3d", role: "img", "aria-label": "3D cape preview" }, rig);
	box.style.setProperty("--k", k + "px");
	if (draggable) {
		let dragging = false, startX = 0, yaw = -20;
		const surface = box;
		surface.addEventListener("pointerdown", (e) => { dragging = true; startX = e.clientX; box.classList.add("drag"); surface.setPointerCapture(e.pointerId); });
		surface.addEventListener("pointermove", (e) => {
			if (!dragging) return;
			rig.style.transform = `rotateX(6deg) rotateY(${yaw + (e.clientX - startX) * 0.6}deg)`;
		});
		surface.addEventListener("pointerup", (e) => { dragging = false; yaw += (e.clientX - startX) * 0.6; });
	}
	return box;
}

/** The right picture for any cosmetic, like in the launcher. */
// ---- Emotes: a small shaded player doing the emote on a canvas, its particles as glyphs (like the game's Store cards) ----
const EMOTE_BOXES = [[-4, -8, -4, 8, 8, 8], [-4, 0, -2, 8, 12, 4], [-3, -2, -2, 4, 12, 4], [-1, -2, -2, 4, 12, 4], [-2, 0, -2, 4, 12, 4], [-2, 0, -2, 4, 12, 4]];
const EMOTE_FACES = [[0, 1, 3, 2], [4, 5, 7, 6], [0, 1, 5, 4], [2, 3, 7, 6], [0, 2, 6, 4], [1, 3, 7, 5]];
const EMOTE_REST = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -5, 2, 0, 0, 0, 0, 5, 2, 0, 0, 0, 0, -1.9, 12, 0, 0, 0, 0, 1.9, 12, 0, 0, 0, 0];
// the game's effect sprites (EmoteFx atlas cells) as text glyphs
const EMOTE_GLYPHS = {
	heart: "❤", note: "♪", notes: "♫", smoke: "●", glow: "●", snowflake: "❄", bat: "ᴥ", ghost: "☁", z: "Z", drop: "•", flame: "▲",
	square: "■", skull: "☠", bolt: "⚡", anger: "✖", ring: "○", bubble: "○", candy: "✚", sparkle: "✦", star: "✧",
};

/** An effect of the emote's track reduced to what the previews draw (sounds, music and props only play in game). */
function emoteParticle(e) {
	if (!e.fx) return null;
	const first = (v) => (Array.isArray(v) ? v[0] : v);
	const speed = e.speed || 1;
	return { t: e.t, particle: first(e.shape) || "glow", color: first(e.color) || null, text: e.text || null, count: e.count || 4,
		spread: Math.min(1.2, (e.radius || 0) + speed * 0.12), up: speed * 0.04,
		at: !e.at || e.at[0] === "@" ? "chest" : e.at === "ground" || e.at === "front" ? "feet" : e.at === "above" ? "head" : e.at };
}
const emoteAnims = new Map();

/** The emote's animation (downloaded once per page): frames as one Float32Array (36 numbers each), fps, loop, events. */
function emoteAnim(item) {
	const sha = item && item.files && item.files.anim;
	if (!sha) return Promise.reject(new Error("no animation"));
	if (!emoteAnims.has(sha)) {
		emoteAnims.set(sha, fetch(fileUrl(sha)).then((r) => (r.ok ? r.json() : Promise.reject(new Error("HTTP " + r.status)))).then((j) => {
			const frames = new Float32Array(j.frames.length * 36);
			j.frames.forEach((f, i) => f.forEach((part, p) => part.forEach((v, k) => { frames[i * 36 + p * 6 + k] = v; })));
			return { fps: j.fps, loop: !!j.loop, count: j.frames.length, frames, duration: Math.max(1, j.frames.length - 1) / j.fps,
				events: (j.events || []).map(emoteParticle).filter(Boolean).sort((a, b) => a.t - b.t), credit: j.credit || null };
		}));
	}
	return emoteAnims.get(sha);
}

function emotePose(anim, frame, out) {
	const a = Math.max(0, Math.min(anim.count - 1, Math.floor(frame)));
	const b = Math.min(anim.count - 1, a + 1);
	const f = frame - a;
	for (let i = 0; i < 36; i++) {
		const va = anim.frames[a * 36 + i];
		let d = anim.frames[b * 36 + i] - va;
		if (i % 6 >= 3) d = Math.atan2(Math.sin(d), Math.cos(d));
		out[i] = va + d * f;
	}
	return out;
}

/** A point of a part's own frame into model space (y down; rotation Z * Y * X around the pivot, like ModelPart). */
function emoteTransform(p, part, lx, ly, lz) {
	const o = part * 6;
	const cx = Math.cos(p[o + 3]), sx = Math.sin(p[o + 3]);
	const y1 = ly * cx - lz * sx, z1 = ly * sx + lz * cx;
	const cy = Math.cos(p[o + 4]), sy = Math.sin(p[o + 4]);
	const x2 = lx * cy + z1 * sy, z2 = -lx * sy + z1 * cy;
	const cz = Math.cos(p[o + 5]), sz = Math.sin(p[o + 5]);
	return [x2 * cz - y1 * sz + p[o], x2 * sz + y1 * cz + p[o + 1], z2 + p[o + 2]];
}

function emoteAnchor(p, at) {
	switch (at) {
		case "head": return emoteTransform(p, 0, 0, -10, 0);
		case "rhand": return emoteTransform(p, 2, -1, 10, 0);
		case "lhand": return emoteTransform(p, 3, 1, 10, 0);
		case "feet": return [(p[24] + p[30]) / 2, 23, (p[26] + p[32]) / 2];
		case "ring": return emoteTransform(p, 1, 0, 10, 0);
		default: return emoteTransform(p, 1, 0, 4, 0);
	}
}

function emoteGlyph(particle, color, text) {
	const g = EMOTE_GLYPHS[particle] || (particle.length === 1 ? particle : text ? text[0] : "✧");
	const col = !color || color === "rainbow" ? `hsl(${Math.random() * 360} 90% 70%)` : color;
	return [g, col];
}

function drawEmotePose(ctx, p, cx, footY, s, yaw, accent) {
	const cos = Math.cos(yaw), sin = Math.sin(yaw);
	const colors = ["#e3b08e", accent, "#e3b08e", "#e3b08e", "#3d3f86", "#3d3f86"];
	const faces = [];
	for (let part = 0; part < 6; part++) {
		const b = EMOTE_BOXES[part];
		const c = [];
		for (let i = 0; i < 2; i++) for (let j = 0; j < 2; j++) for (let l = 0; l < 2; l++) {
			const q = emoteTransform(p, part, b[0] + b[3] * i, b[1] + b[4] * j, b[2] + b[5] * l);
			c.push([q[0] * cos + q[2] * sin, q[1], -q[0] * sin + q[2] * cos]);
		}
		const m = c.reduce((acc, q) => [acc[0] + q[0] / 8, acc[1] + q[1] / 8, acc[2] + q[2] / 8], [0, 0, 0]);
		for (const f of EMOTE_FACES) {
			const a = c[f[0]], bb = c[f[1]], d = c[f[3]], e = c[f[2]];
			const u = [bb[0] - a[0], bb[1] - a[1], bb[2] - a[2]], v = [d[0] - a[0], d[1] - a[1], d[2] - a[2]];
			let n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
			const len = Math.hypot(...n);
			if (len < 1e-6) continue;
			n = n.map((x) => x / len);
			const out = [(a[0] + e[0]) / 2 - m[0], (a[1] + e[1]) / 2 - m[1], (a[2] + e[2]) / 2 - m[2]];
			if (n[0] * out[0] + n[1] * out[1] + n[2] * out[2] < 0) n = n.map((x) => -x);
			if (n[2] > 0.02) continue;
			const light = 0.55 + 0.45 * Math.max(0, -0.45 * n[0] - 0.6 * n[1] - 0.65 * n[2]);
			faces.push({ depth: (a[2] + bb[2] + d[2] + e[2]) / 4, pts: f.map((k) => [cx + c[k][0] * s, footY + (c[k][1] - 24) * s]), color: colors[part], light });
		}
	}
	faces.sort((x, y) => y.depth - x.depth);
	for (const f of faces) {
		ctx.beginPath();
		f.pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
		ctx.closePath();
		const shaded = emoteShade(f.color, f.light);
		ctx.fillStyle = shaded;
		ctx.fill();
		ctx.strokeStyle = shaded;
		ctx.lineWidth = 0.6;
		ctx.stroke();
	}
}

/** "#rrggbb" (or "#rgb") times a light factor, as rgb(). */
function emoteShade(hex, light) {
	let h = hex.replace("#", "");
	if (h.length === 3) h = h.split("").map((c) => c + c).join("");
	const n = parseInt(h.slice(0, 6), 16);
	const ch = (shift) => Math.min(255, Math.round(((n >> shift) & 255) * light));
	return `rgb(${ch(16)},${ch(8)},${ch(0)})`;
}

/** A canvas looping the emote (one-shot emotes rest a moment between plays); animates only while on screen. */
function emoteFigure(item) {
	const canvas = el("canvas", { class: "emote-fig", role: "img", "aria-label": item.name + " emote" });
	const ctx = canvas.getContext("2d");
	const accent = item.accent && /^#[0-9a-f]{3,8}$/i.test(item.accent) ? item.accent : "#8b7cf6";
	let anim = null, visible = false, start = performance.now(), fired = -1, raf = 0;
	const sprites = [];
	const pose = EMOTE_REST.slice();
	const draw = (now) => {
		raf = 0;
		const dpr = Math.min(2, devicePixelRatio || 1);
		const w = canvas.clientWidth || 200, h = canvas.clientHeight || 200;
		if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
		ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
		ctx.clearRect(0, 0, w, h);
		const s = Math.min((h - 14) / 40, w / 30), cx = w / 2, footY = h - 8;
		const t = reduceMotion ? 1.2 : (now - start) / 1000;
		for (let i = 0; i < 36; i++) pose[i] = EMOTE_REST[i];
		if (anim) {
			const cycle = anim.duration + (anim.loop ? 0 : 0.9);
			const local = t % cycle;
			if (local <= anim.duration) emotePose(anim, local * anim.fps, pose);
			if (!reduceMotion) {
				for (let k = Math.floor(Math.max(0, fired) / cycle); k <= Math.floor(t / cycle); k++) {
					for (const e of anim.events) {
						const at = k * cycle + e.t;
						if (!e.particle || at <= fired || at > t) continue;
						const q = emoteAnchor(pose, e.at);
						for (let i = 0; i < Math.min(6, Math.max(1, Math.floor((e.count || 3) / 3))); i++) {
							const [g, col] = emoteGlyph(e.particle, e.color, e.text);
							const spread = Math.max(2, (e.spread || 0.3) * 16 * s);
							sprites.push({ g, col, x: cx + q[0] * s + (Math.random() - 0.5) * spread, y: footY + (q[1] - 24) * s + (Math.random() - 0.5) * spread,
								vx: (Math.random() - 0.5) * 10, vy: -12 - (e.up || 0.05) * 60 - Math.random() * 8, born: now, life: 900 + Math.random() * 500 });
						}
					}
				}
				fired = t;
			}
		}
		drawEmotePose(ctx, pose, cx, footY, s, ((28 + Math.sin(t * 0.6) * 12) * Math.PI) / 180, accent);
		ctx.textAlign = "center";
		ctx.font = `bold ${Math.max(10, s * 3.2)}px system-ui, sans-serif`;
		for (let i = sprites.length - 1; i >= 0; i--) {
			const sp = sprites[i], age = now - sp.born;
			if (age > sp.life) { sprites.splice(i, 1); continue; }
			ctx.globalAlpha = 1 - age / sp.life;
			ctx.fillStyle = sp.col;
			ctx.fillText(sp.g, sp.x + (sp.vx * age) / 1000, sp.y + (sp.vy * age) / 1000);
		}
		ctx.globalAlpha = 1;
		if (visible && !reduceMotion && canvas.isConnected) raf = requestAnimationFrame(draw);
	};
	new IntersectionObserver(([entry]) => {
		visible = entry.isIntersecting;
		if (visible && !raf) raf = requestAnimationFrame(draw);
	}).observe(canvas);
	emoteAnim(item).then((a) => { anim = a; if (!raf) raf = requestAnimationFrame(draw); }).catch(() => {});
	return canvas;
}
window.velo.emoteAnim = emoteAnim;
window.velo.emotePose = emotePose;
window.velo.emoteAnchor = emoteAnchor;
window.velo.emoteGlyph = emoteGlyph;

function cosmeticArt(item, k = 12, draggable = false) {
	if (item.type === "emote") return emoteFigure(item);
	if (item.type === "cape" && item.files && item.files.gif) return cape3d(fileUrl(item.files.gif), k, draggable);
	if (item.type === "aura") return el("img", { class: "aura-img", src: `/assets/auras/${item.id.replace(/^aura_/, "")}.webp`, alt: item.name + " aura", loading: "lazy", decoding: "async" });
	if (item.files && item.files.preview) return el("img", { src: fileUrl(item.files.preview), alt: item.name, loading: "lazy", decoding: "async" });
	return el("span", {}, item.name);
}
window.velo.cosmeticArt = cosmeticArt;
window.velo.fileUrl = fileUrl;

let catalogPromise = null;
window.velo.cosmetics = () => (catalogPromise = catalogPromise || fetch(`${API}/v1/cosmetics/catalog`).then((r) => (r.ok ? r.json() : Promise.reject(new Error("HTTP " + r.status)))));

// Home page: one of each kind, live.
$$("[data-cosmetic]").forEach(async (slot) => {
	try {
		const cat = await window.velo.cosmetics();
		const want = slot.dataset.cosmetic.split(",");
		const item = want.map((id) => cat.items.find((i) => i.id === id)).find((i) => i && (i.status || "released") === "released") || cat.items.find((i) => i.type === slot.dataset.type && i.inStore);
		if (!item) return;
		slot.replaceChildren(cosmeticArt(item, 13));
		const label = slot.closest(".cos-card") && slot.closest(".cos-card").querySelector("[data-name]");
		if (label) label.textContent = item.name;
		if (item.accent && /^#[0-9a-f]{3,8}$/i.test(item.accent)) slot.style.setProperty("--accent", item.accent + "66");
	} catch (e) { /* keeps the static fallback picture */ }
});

// ---- Player skins (served by the Velo server, so browsers never talk to Mojang) ----
/** A player's face (with hat layer), served by the Velo server. */
window.velo.headUrl = (who) => `${API}/v1/skins/${encodeURIComponent(who)}/head.png`;
window.velo.skinUrl = (who) => `${API}/v1/skins/${encodeURIComponent(who)}/skin.png`;
window.velo.skinMeta = (who) => fetch(`${API}/v1/skins/${encodeURIComponent(who)}/meta`).then((r) => (r.ok ? r.json() : null)).catch(() => null);

// ---- R-Shift showcase leans towards the pointer ----
$$(".tilt-big").forEach((frame) => {
	if (!finePointer || reduceMotion) return;
	const host = frame.parentElement;
	host.addEventListener("pointermove", (e) => {
		const r = host.getBoundingClientRect();
		host.style.setProperty("--try", (((e.clientX - r.left) / r.width - 0.5) * 8).toFixed(2) + "deg");
		host.style.setProperty("--trx", (4 - ((e.clientY - r.top) / r.height - 0.5) * 6).toFixed(2) + "deg");
	});
	host.addEventListener("pointerleave", () => { host.style.removeProperty("--try"); host.style.removeProperty("--trx"); });
});

// ---- Countdown to Velo Client 1.0 (out of beta) ----
$$("[data-launch]").forEach((box) => {
	const at = Date.parse(box.dataset.launch);
	if (!at) return;
	const set = (k, v) => { const n = box.querySelector(`[data-lc="${k}"]`); if (n) n.textContent = v; };
	const tick = () => {
		const left = at - Date.now();
		if (left <= 0) {
			box.classList.add("done");
			box.querySelector("[data-lc-label]").textContent = "Velo Client 1.0 is here";
			box.querySelector("[data-lc-units]").hidden = true;
			box.querySelector("[data-lc-note]").textContent = "Out of beta - download it free.";
			return false;
		}
		const s = Math.floor(left / 1000);
		set("d", Math.floor(s / 86400));
		set("h", String(Math.floor(s / 3600) % 24).padStart(2, "0"));
		set("m", String(Math.floor(s / 60) % 60).padStart(2, "0"));
		set("s", String(s % 60).padStart(2, "0"));
		return true;
	};
	if (tick()) {
		const id = setInterval(() => { if (!tick()) clearInterval(id); }, 1000);
	}
});

// ---- Launch week gift: hidden once the server says the offer is over ----
(async () => {
	const boxes = $$("[data-launch-gift], .lc-gift");
	if (!boxes.length) return;
	try {
		const r = await fetch(`${API}/v1/store/catalog`);
		if (!r.ok) return;
		const cat = await r.json();
		if (!cat.launchBonus) boxes.forEach((b) => (b.hidden = true));
	} catch (e) { /* offline: keep the static text */ }
})();
