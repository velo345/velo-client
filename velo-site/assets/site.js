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
function cosmeticArt(item, k = 12, draggable = false) {
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
