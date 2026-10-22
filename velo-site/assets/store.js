/* The web Store: sign in with a code from the launcher, buy with Velo Coins, coin packs and Velo+ through Tebex. */
"use strict";
(() => {
	const { el, $, $$, cosmeticArt, observeReveals } = window.velo;
	const TOKEN = "velo.web.token";
	const NAME = "velo.web.username";
	const UUID = "velo.web.uuid";
	const LAST = "velo.web.lastName";
	const DEMO_SKIN = "Velo2k";
	const store = { token: null, username: null, uuid: null, wallet: null, catalog: null, cosmetics: null, byId: {} };
	// The WebGL try-on viewer (skin3d.js, a module) may load after this script.
	window.velo.skin3dReady = window.velo.player3d ? Promise.resolve()
		: new Promise((resolve) => addEventListener("velo:skin3d", resolve, { once: true }));
	const remember = (k, v) => { try { v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) { /* storage blocked */ } };
	const recall = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };
	const head = (who, size = 32) => el("img", { class: "head-img", src: window.velo.headUrl(who), alt: "", width: size, height: size, style: `width:${size}px;height:${size}px`,
		onerror: (e) => { e.target.replaceWith(el("span", { class: "avatar", "aria-hidden": "true", style: `width:${size}px;height:${size}px` }, String(who || "?").slice(0, 1).toUpperCase())); } });
	const nf = new Intl.NumberFormat("en-US");
	const coins = (n) => nf.format(n);

	try {
		store.token = localStorage.getItem(TOKEN);
		store.username = localStorage.getItem(NAME);
		store.uuid = localStorage.getItem(UUID);
	} catch (e) { /* private mode: sign-in lasts for this page only */ }

	// ---- API ----
	async function api(path, opts = {}) {
		const headers = { Accept: "application/json" };
		if (opts.body !== undefined) headers["Content-Type"] = "application/json";
		if (store.token && opts.auth !== false) headers.Authorization = "Bearer " + store.token;
		const res = await fetch(path, { method: opts.method || (opts.body !== undefined ? "POST" : "GET"), headers,
			body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined, credentials: "omit", cache: "no-store" });
		const data = await res.json().catch(() => ({}));
		if (res.status === 401 && store.token && opts.auth !== false) {
			signOut(true);
			throw new Error("You were signed out - sign in again with a new code.");
		}
		if (!res.ok) throw Object.assign(new Error(data.error || "Something went wrong (HTTP " + res.status + ")"), { status: res.status });
		return data;
	}

	function toast(text, kind = "ok") {
		const box = $("#toasts");
		const t = el("div", { class: "toast " + kind, role: kind === "err" ? "alert" : "status" }, text);
		box.append(t);
		setTimeout(() => t.remove(), kind === "err" ? 7000 : 4000);
	}

	function confetti(x, y) {
		if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
		const colors = ["#f0353d", "#ff6b73", "#f4b82e", "#ffffff", "#ff9aa0"];
		for (let i = 0; i < 46; i++) {
			const a = Math.random() * Math.PI * 2;
			const d = 120 + Math.random() * 220;
			const p = el("i", { class: "confetti", style: `left:${x}px;top:${y}px;background:${colors[i % colors.length]};--x:${Math.cos(a) * d}px;--y:${Math.sin(a) * d - 120}px;--r:${Math.random() * 720 - 360}deg;animation-delay:${Math.random() * 80}ms` });
			document.body.append(p);
			setTimeout(() => p.remove(), 1600);
		}
	}

	async function busy(button, fn) {
		const old = [...button.childNodes];
		button.disabled = true;
		button.replaceChildren(el("span", { class: "spin" }), "Working…");
		try {
			return await fn();
		} finally {
			button.disabled = false;
			button.replaceChildren(...old);
		}
	}

	function dialog(content, small) {
		const close = el("button", { class: "dlg-close", type: "button", "aria-label": "Close" },
			el("span", { "aria-hidden": "true" }, "✕"));
		const d = el("dialog", { class: small ? "small" : "" }, close, content);
		close.addEventListener("click", () => d.close());
		d.addEventListener("click", (e) => { if (e.target === d) d.close(); });
		d.addEventListener("close", () => d.remove());
		document.body.append(d);
		d.showModal();
		return d;
	}

	// ---- Sign-in (code from the launcher + the invisible bot check) ----
	async function solveChallenge() {
		const c = await api("/v1/web/challenge", { auth: false });
		const enc = new TextEncoder();
		for (let n = 0; n <= c.maxnumber; n++) {
			const digest = await crypto.subtle.digest("SHA-256", enc.encode(c.salt + n));
			const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
			if (hex === c.challenge) return { salt: c.salt, number: n, challenge: c.challenge, signature: c.signature };
		}
		throw new Error("The security check didn't finish - reload the page.");
	}

	function openSignIn(then) {
		let pow = solveChallenge().catch(() => null);
		const proof = async () => (await pow) || (await solveChallenge());
		const name = el("input", { class: "field-input", placeholder: "Your Minecraft name", autocomplete: "username", maxlength: "16", value: recall(LAST) || "", "aria-label": "Minecraft name" });
		const send = el("button", { class: "btn btn-primary", type: "submit" }, "Send code to my launcher");
		const sent = el("div", { class: "notice", hidden: true });
		const input = el("input", { class: "code-input", inputmode: "numeric", autocomplete: "one-time-code", maxlength: "7", placeholder: "000 000", "aria-label": "Sign-in code", required: true });
		const err = el("div", { class: "error", hidden: true });
		const submit = el("button", { class: "btn btn-primary btn-lg", type: "submit" }, "Sign in");
		const sendForm = el("form", { class: "send-row" }, el("span", { class: "send-head" }, name.value ? head(name.value, 36) : el("span", { class: "avatar", style: "width:36px;height:36px" }, "?")), name, send);
		const codeForm = el("form", { style: "display:grid;gap:12px" }, el("label", { class: "field" }, "The 6-digit code from your launcher", input), err, submit);
		const box = el("div", { class: "dlg-pad" },
			el("h2", {}, "Sign in with the launcher"),
			el("p", {}, "Purchases go to the Minecraft account you play with. No password is ever typed here."),
			el("div", { class: "signin-step" }, el("b", {}, "1 · Get a code"),
				el("p", { class: "small" }, "Open the Velo launcher (or keep it open) and send the code there - it pops up right away."),
				sendForm, sent,
				el("p", { class: "mono", style: "font-size:12px" }, "Or in the launcher: Settings › Account › Website sign-in › Get a code.")),
			el("div", { class: "signin-step" }, el("b", {}, "2 · Type it here"), codeForm),
			el("p", { class: "mono", style: "font-size:12px" }, "You stay signed in on this browser for 14 days. An invisible bot check runs in your browser - no cookies, no tracking."));
		const d = dialog(box, true);
		setTimeout(() => (name.value ? input : name).focus(), 50);
		let headTimer = 0;
		name.addEventListener("input", () => {
			clearTimeout(headTimer);
			headTimer = setTimeout(() => {
				if (/^[A-Za-z0-9_]{3,16}$/.test(name.value.trim())) sendForm.querySelector(".send-head").replaceChildren(head(name.value.trim(), 36));
			}, 450);
		});
		sendForm.addEventListener("submit", async (e) => {
			e.preventDefault();
			err.hidden = true;
			try {
				await busy(send, async () => {
					const r = await api("/v1/web/request", { auth: false, body: { name: name.value.trim(), pow: await proof() } });
					remember(LAST, r.username);
					sent.textContent = `Sent! Your launcher shows the code now${"\u00a0"}- or as soon as you open it (within 5 minutes). Signed in as ${r.username} there?`;
					sent.hidden = false;
				});
				pow = solveChallenge().catch(() => null);
				input.focus();
			} catch (x) {
				err.textContent = x.message;
				err.hidden = false;
				pow = solveChallenge().catch(() => null);
			}
		});
		input.addEventListener("input", () => {
			const digits = input.value.replace(/\D/g, "").slice(0, 6);
			input.value = digits.length > 3 ? digits.slice(0, 3) + " " + digits.slice(3) : digits;
			if (digits.length === 6) codeForm.requestSubmit();
		});
		codeForm.addEventListener("submit", async (e) => {
			e.preventDefault();
			err.hidden = true;
			try {
				await busy(submit, async () => {
					const r = await api("/v1/web/login", { auth: false, body: { code: input.value.replace(/\s/g, ""), pow: await proof() } });
					store.token = r.token;
					store.username = r.username;
					store.uuid = r.uuid;
					remember(TOKEN, r.token);
					remember(NAME, r.username);
					remember(UUID, r.uuid);
					remember(LAST, r.username);
				});
				d.close();
				toast("Signed in as " + store.username);
				await loadWallet();
				render();
				if (then) then();
			} catch (x) {
				err.textContent = x.message;
				err.hidden = false;
				pow = solveChallenge().catch(() => null);
			}
		});
	}

	function signOut(quiet) {
		const token = store.token;
		store.token = null;
		store.username = null;
		store.uuid = null;
		store.wallet = null;
		remember(TOKEN, null);
		remember(NAME, null);
		remember(UUID, null);
		if (token && !quiet) fetch("/v1/web/logout", { method: "POST", headers: { Authorization: "Bearer " + token } }).catch(() => {});
		renderWallet();
		render();
		const acct = $("[data-account]");
		if (acct) { acct.href = "/store#signin"; acct.querySelector("span").textContent = "Sign in"; }
	}

	async function loadWallet() {
		if (!store.token) return;
		try {
			store.wallet = await api("/v1/store/wallet");
		} catch (e) {
			store.wallet = null;
		}
		renderWallet();
	}

	function renderWallet() {
		const box = $("#wallet");
		if (!store.token || !store.wallet) {
			box.replaceChildren(el("button", { class: "btn btn-primary magnetic", type: "button", onclick: () => openSignIn() }, "Sign in to buy"));
			return;
		}
		const w = store.wallet;
		box.replaceChildren(
			el("span", { class: "coins", title: "Your Velo Coins" }, coins(w.balance)),
			el("button", { class: "who", type: "button", title: "Account", onclick: openAccount },
				head(store.uuid || store.username, 32),
				store.username, w.plus ? el("span", { class: "plus-badge", style: "padding:2px 6px;font-size:10px" }, "+") : null));
		const acct = $("[data-account]");
		if (acct) { acct.href = "/store#account"; acct.querySelector("span").textContent = store.username; }
	}

	function openAccount() {
		const w = store.wallet || { owned: [], balance: 0 };
		const owned = w.owned.map((id) => store.byId[id]).filter(Boolean);
		const out = el("button", { class: "btn", type: "button" }, "Sign out");
		const d = dialog(el("div", { class: "dlg-pad" },
			el("div", { style: "display:flex;gap:14px;align-items:center" }, head(store.uuid || store.username, 56), el("h2", {}, store.username)),
			el("p", {}, `${coins(w.balance)} Velo Coins · ${owned.length} items` + (w.plus ? ` · Velo+ until ${new Date(w.plusUntil).toLocaleDateString()}` : "")),
			owned.length ? el("div", { class: "items", style: "grid-template-columns:repeat(auto-fill,minmax(130px,1fr))" }, owned.map((i) => itemCard(i, 7))) : el("p", {}, "Nothing yet - everything you buy shows up in the Locker in game and in the launcher."),
			el("div", { class: "detail-actions" }, out)), false);
		out.addEventListener("click", () => { signOut(); d.close(); toast("Signed out"); });
	}

	// ---- Catalog ----
	const owns = (id) => !!(store.wallet && store.wallet.owned.includes(id));
	const priceOf = (id) => {
		const c = store.catalog.items.find((i) => i.id === id);
		return c ? { price: c.price, full: c.fullPrice } : null;
	};

	function itemCard(item, k = 9) {
		const p = priceOf(item.id);
		const art = el("span", { class: "art" }, cosmeticArt(item, k));
		if (item.accent && /^#[0-9a-f]{3,8}$/i.test(item.accent)) art.style.setProperty("--accent", item.accent + "55");
		if (owns(item.id)) art.append(el("span", { class: "ribbon owned" }, "OWNED"));
		else if (p && p.full) art.append(el("span", { class: "ribbon" }, "-" + Math.round((1 - p.price / p.full) * 100) + "%"));
		const card = el("button", { class: "card item tilt", type: "button", "aria-label": item.name },
			art,
			el("span", { class: "body" },
				el("b", {}, item.name),
				el("span", { class: "meta" },
					el("span", { class: "kind" }, item.type === "cape" ? "Animated cape" : item.type),
					p ? el("span", { class: "price-tag" }, el("i", { class: "coin-dot" }), coins(p.price), p.full ? el("s", {}, coins(p.full)) : null)
						: item.unlock === "discord" ? el("span", { class: "kind" }, "Link Discord") : el("span", { class: "kind" }, "Not for sale"))));
		card.addEventListener("click", () => openItem(item));
		return card;
	}

	function openItem(item) {
		const p = priceOf(item.id);
		const art = el("div", { class: "detail-art" });
		if (item.accent && /^#[0-9a-f]{3,8}$/i.test(item.accent)) art.style.setProperty("--accent", item.accent + "66");
		const stage = el("div", { class: "tryon-stage" });
		const who = el("input", { placeholder: "Any Minecraft name", maxlength: "16", "aria-label": "Try it on another player's skin" });
		const modes = el("div", { class: "seg-mini", role: "group", "aria-label": "Preview" });
		let viewer = null;
		const showArt = () => { if (viewer) { viewer.dispose(); viewer = null; } stage.replaceChildren(cosmeticArt(item, 20, true)); };
		const showSkin = async (name) => {
			const target = name || store.uuid || store.username || DEMO_SKIN;
			stage.replaceChildren(el("span", { class: "hint", style: "position:static" }, "Loading your skin…"));
			try {
				await window.velo.skin3dReady;
				const meta = await window.velo.skinMeta(target);
				if (!meta || !meta.hasSkin) throw new Error("no skin");
				if (viewer) viewer.dispose();
				stage.replaceChildren();
				viewer = await window.velo.player3d(stage, { skin: window.velo.skinUrl(meta.uuid), slim: meta.slim, item,
					fileUrl: window.velo.fileUrl, auraUrl: (id, layer) => `/assets/auras/${id.replace(/^aura_/, "")}${layer ? "-" + layer : ""}.webp` });
			} catch (e) {
				stage.replaceChildren(el("span", { class: "hint", style: "position:static" }, "Couldn't load that skin - try another name."));
			}
		};
		const mode = (label, fn) => {
			const b = el("button", { type: "button", "aria-pressed": "false" }, label);
			b.addEventListener("click", () => { modes.querySelectorAll("button").forEach((x) => x.setAttribute("aria-pressed", String(x === b))); fn(); });
			modes.append(b);
			return b;
		};
		const skinBtn = mode(store.token ? "On my skin" : "On a skin", () => showSkin(who.value.trim()));
		const artBtn = mode("Item", showArt);
		who.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); skinBtn.click(); } });
		art.append(stage, el("div", { class: "tryon-bar" }, modes, who), el("span", { class: "hint tryon-hint" }, "Drag to turn"));
		skinBtn.click();
		const buy = el("button", { class: "btn btn-primary btn-lg", type: "button" });
		const gift = el("button", { class: "btn btn-lg", type: "button" }, "Gift to a friend");
		const coll = (store.cosmetics.collections || []).find((c) => c.id === item.collection);
		const update = () => {
			if (owns(item.id)) {
				buy.replaceChildren("Owned - equip it in the Locker");
				buy.disabled = true;
			} else if (!p) {
				buy.replaceChildren(item.unlock === "discord" ? "Free when you link Discord" : "Not for sale right now");
				buy.disabled = true;
			} else {
				buy.disabled = false;
				buy.replaceChildren(store.token ? `Buy for ${coins(p.price)} coins` : "Sign in to buy");
			}
			gift.hidden = !p;
		};
		update();
		const d = dialog(el("div", { class: "detail" }, art, el("div", { class: "detail-body" },
			el("span", { class: "eyebrow" }, item.type === "cape" ? "Animated cape" : item.type === "aura" ? "Aura" : "3D wings"),
			el("h2", {}, item.name),
			coll ? el("span", { class: "pill" }, coll.name) : null,
			el("p", {}, item.description || ""),
			p ? el("span", { class: "price", style: "font-size:34px" }, el("i", { class: "coin-dot", style: "width:22px;height:22px;margin-right:10px;vertical-align:-2px" }), coins(p.price), p.full ? el("s", { style: "font-size:18px;color:var(--text-3);margin-left:10px" }, coins(p.full)) : null) : null,
			item.availableUntil ? el("p", { class: "mono" }, "Leaves the Store " + new Date(item.availableUntil).toLocaleDateString()) : null,
			el("div", { class: "detail-actions" }, buy, gift),
			el("p", { class: "mono", style: "font-size:12px" }, "Every Velo player sees it on you. Cosmetic only - no gameplay advantage."))));
		d.addEventListener("close", () => { if (viewer) viewer.dispose(); });
		const url = new URL(location.href);
		url.searchParams.set("item", item.id);
		history.replaceState(null, "", url);
		d.addEventListener("close", () => { const u = new URL(location.href); u.searchParams.delete("item"); history.replaceState(null, "", u); });
		buy.addEventListener("click", async (e) => {
			if (!store.token) return openSignIn(() => openItem(item));
			const { clientX: x, clientY: y } = e;
			try {
				await busy(buy, async () => {
					store.wallet = await api("/v1/store/buy", { body: { itemId: item.id } });
				});
				confetti(x, y);
				toast(item.name + " is yours. Equip it in the Locker.");
				renderWallet();
				render();
				update();
			} catch (x) {
				toast(x.status === 402 ? x.message + ". Get more below." : x.message, "err");
			}
		});
		gift.addEventListener("click", () => {
			if (!store.token) return openSignIn(() => openItem(item));
			d.close();
			openGift(item, p);
		});
	}

	function openGift(item, p) {
		const to = el("input", { placeholder: "Their Minecraft name", "aria-label": "Friend's Minecraft name", required: true, maxlength: "16" });
		const send = el("button", { class: "btn btn-primary btn-lg", type: "submit" }, `Send for ${coins(p.price)} coins`);
		const err = el("div", { class: "error", hidden: true });
		const face = el("span", { class: "send-head" }, el("span", { class: "avatar" }, "?"));
		let t = 0;
		to.addEventListener("input", () => { clearTimeout(t); t = setTimeout(() => { if (/^[A-Za-z0-9_]{3,16}$/.test(to.value.trim())) face.replaceChildren(head(to.value.trim(), 36)); }, 450); });
		const form = el("form", { class: "dlg-pad" }, el("h2", {}, "Gift " + item.name),
			el("p", {}, "It goes straight to their Locker. They need to have used Velo Client before."),
			el("label", { class: "field" }, "Minecraft name", el("span", { class: "send-row", style: "grid-template-columns:auto 1fr" }, face, to)), err, send);
		const d = dialog(form, true);
		form.addEventListener("submit", async (e) => {
			e.preventDefault();
			err.hidden = true;
			try {
				await busy(send, async () => { store.wallet = await api("/v1/store/gift", { body: { itemId: item.id, to: to.value.trim() } }); });
				d.close();
				confetti(innerWidth / 2, innerHeight / 2);
				toast("Gift sent to " + to.value.trim());
				renderWallet();
			} catch (x) {
				err.textContent = x.message;
				err.hidden = false;
			}
		});
	}

	// ---- Rendering ----
	const ui = { search: $("#store-search"), sort: $("#store-sort"), owned: $("#store-owned") };

	function sorted(list) {
		const q = ui.search.value.trim().toLowerCase();
		let out = list.filter((i) => !q || i.name.toLowerCase().includes(q) || (i.description || "").toLowerCase().includes(q) || (i.collection || "").includes(q));
		if (ui.owned.value === "owned") out = out.filter((i) => owns(i.id));
		if (ui.owned.value === "unowned") out = out.filter((i) => !owns(i.id));
		const price = (i) => (priceOf(i.id) || { price: 1e9 }).price;
		if (ui.sort.value === "low") out.sort((a, b) => price(a) - price(b));
		else if (ui.sort.value === "high") out.sort((a, b) => price(b) - price(a));
		else if (ui.sort.value === "name") out.sort((a, b) => a.name.localeCompare(b.name));
		return out;
	}

	function render() {
		if (!store.catalog) return;
		const shown = store.cosmetics.items.filter((i) => i.inStore || owns(i.id) || i.unlock === "discord");
		for (const type of ["wings", "cape", "aura"]) {
			const grid = $(`[data-grid="${type}"]`);
			const list = sorted(shown.filter((i) => i.type === type));
			grid.replaceChildren(...(list.length ? list.map((i) => itemCard(i, type === "cape" ? 10 : 9)) : [el("p", { class: "kind" }, "Nothing matches.")]));
		}
		// Daily picks
		const daily = $('[data-grid="daily"]');
		daily.replaceChildren(...store.catalog.daily.items.map((id) => store.byId[id]).filter(Boolean).map((i) => itemCard(i, 10)));
		// Bundles
		const bundles = $('[data-grid="bundles"]');
		bundles.replaceChildren(...(store.catalog.bundles.length ? store.catalog.bundles.map(bundleCard) : [el("p", { class: "kind" }, "No bundles right now - check back soon.")]));
		$("#bundles").hidden = !store.catalog.bundles.length;
		renderPacks();
		renderPlus();
	}

	function bundleCard(b) {
		const items = b.items.map((id) => store.byId[id]).filter(Boolean);
		const full = items.reduce((a, i) => a + ((priceOf(i.id) || {}).price || 0), 0);
		const allOwned = items.every((i) => owns(i.id));
		const buy = el("button", { class: "btn btn-primary", type: "button", disabled: allOwned }, allOwned ? "Owned" : `Buy bundle · ${coins(b.price)}`);
		buy.addEventListener("click", async (e) => {
			if (!store.token) return openSignIn();
			const { clientX: x, clientY: y } = e;
			try {
				await busy(buy, async () => { store.wallet = await api("/v1/store/buy", { body: { itemId: "bundle:" + b.id } }); });
				confetti(x, y);
				toast(b.name + " is yours.");
				renderWallet();
				render();
			} catch (x) { toast(x.message, "err"); }
		});
		return el("article", { class: "card bundle spot" },
			el("span", { class: "eyebrow" }, `Bundle · ${items.length} items`),
			el("h3", { style: "font-size:22px" }, b.name),
			el("p", { style: "margin:0;color:var(--text-2)" }, b.description),
			el("div", { class: "bundle-items" }, items.map((i) => { const a = el("button", { class: "art", type: "button", "aria-label": i.name, style: "border:0;cursor:pointer" }, cosmeticArt(i, 6)); a.onclick = () => openItem(i); return a; })),
			el("div", { style: "display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between" },
				el("span", { class: "price-tag", style: "font-size:20px" }, el("i", { class: "coin-dot" }), coins(b.price), full > b.price ? el("s", {}, coins(full)) : null,
					full > b.price ? el("span", { class: "pill", style: "height:24px;color:#8fe3b6" }, "Save " + Math.round((1 - b.price / full) * 100) + "%") : null),
				buy));
	}

	function renderPacks() {
		const grid = $('[data-grid="packs"]');
		const on = store.catalog.checkoutEnabled;
		const cur = store.catalog.currency || "EUR";
		const money = new Intl.NumberFormat(undefined, { style: "currency", currency: cur });
		grid.replaceChildren(...store.catalog.coinPacks.map((pack, i) => {
			const b = el("button", { class: "btn " + (i === 2 ? "btn-primary" : ""), type: "button", disabled: !on }, on ? money.format(pack.price) : "Opens soon");
			b.addEventListener("click", async () => {
				if (!store.token) return openSignIn();
				try {
					await busy(b, async () => {
						const r = await api("/v1/store/checkout", { body: { packId: pack.id } });
						location.href = r.checkoutUrl;
					});
				} catch (x) { toast(x.message, "err"); }
			});
			return el("article", { class: "card pack spot" + (i === 2 ? " popular" : "") },
				i === 2 ? el("span", { class: "plus-badge", style: "font-size:11px;padding:4px 8px" }, "MOST POPULAR") : null,
				el("i", { class: "coin-dot", style: "width:34px;height:34px" }),
				el("span", { class: "big" }, coins(pack.coins + (pack.bonus || 0))),
				pack.bonus ? el("span", { class: "bonus" }, "+" + coins(pack.bonus) + " bonus") : el("span", { class: "kind" }, "Velo Coins"),
				el("span", { class: "kind" }, money.format(pack.price)), b);
		}));
		$("[data-checkout-note]").textContent = on
			? "Secure checkout by Tebex (our reseller). Coins land on your account right after payment."
			: "Coin purchases open here soon. Until then you can earn coins for free below.";
	}

	function renderPlus() {
		const plus = store.catalog.plus;
		const cur = store.catalog.currency || "EUR";
		if (plus) {
			const price = new Intl.NumberFormat(undefined, { style: "currency", currency: cur }).format(plus.price);
			$$("[data-plus-price]").forEach((n) => n.replaceChildren(price, el("small", {}, " / month")));
		}
		const btn = $("[data-plus-buy]");
		const state = $("[data-plus-state]");
		const w = store.wallet;
		if (w && w.plus) {
			btn.textContent = "You have Velo+";
			btn.disabled = true;
			state.textContent = "Active until " + new Date(w.plusUntil).toLocaleDateString();
		} else if (!store.catalog.checkoutEnabled) {
			btn.textContent = "Opens soon";
			btn.disabled = true;
			state.textContent = "Velo+ checkout opens here soon. Cancel any time once it's live.";
		} else {
			btn.textContent = store.token ? "Get Velo+" : "Sign in to get Velo+";
			btn.disabled = false;
			state.textContent = "Billed monthly by Tebex · cancel any time";
		}
	}
	$("[data-plus-buy]").addEventListener("click", async (e) => {
		if (!store.token) return openSignIn();
		const b = e.currentTarget;
		try {
			await busy(b, async () => { const r = await api("/v1/plus/checkout", { body: {} }); location.href = r.checkoutUrl; });
		} catch (x) { toast(x.message, "err"); }
	});

	function renderFeatured() {
		const box = $("#featured");
		const coll = (store.cosmetics.collections || []).find((c) => c.id === store.cosmetics.featured);
		const items = coll ? store.cosmetics.items.filter((i) => i.collection === coll.id && i.inStore) : [];
		if (!coll || !items.length) { box.hidden = true; return; }
		const from = Math.min(...items.map((i) => (priceOf(i.id) || { price: 1e9 }).price));
		const art = el("div", { class: "featured-art" }, items.slice(0, 3).map((i) => {
			const a = el("button", { type: "button", style: "background:none;border:0;cursor:pointer;display:grid;place-items:center", "aria-label": i.name }, cosmeticArt(i, 9));
			a.onclick = () => openItem(i);
			return a;
		}));
		const anchor = items[0].type === "cape" ? "#capes" : items[0].type === "aura" ? "#auras" : "#wings";
		box.replaceChildren(el("div", { class: "featured reveal" },
			el("div", {},
				el("span", { class: "eyebrow" }, coll.tag || "Collection"),
				el("h2", {}, coll.name),
				el("p", {}, coll.description),
				el("div", { style: "display:flex;flex-wrap:wrap;gap:14px;align-items:center" },
					el("a", { class: "btn btn-primary magnetic", href: anchor }, "Shop the collection"),
					from < 1e9 ? el("span", { class: "kind" }, "from ", el("b", { style: "color:#fff" }, coins(from)), " coins") : null)),
			art));
		observeReveals(box);
	}

	function dailyCountdown() {
		const n = $("[data-daily-ends]");
		const tick = () => {
			const ms = store.catalog.daily.endsAt - Date.now();
			if (ms <= 0) { n.textContent = "new picks are here - refresh"; return; }
			const hrs = Math.floor(ms / 3.6e6), m = Math.floor((ms % 3.6e6) / 6e4), s = Math.floor((ms % 6e4) / 1000);
			n.textContent = `refreshes in ${hrs}h ${String(m).padStart(2, "0")}m ${String(s).padStart(2, "0")}s`;
		};
		tick();
		setInterval(tick, 1000);
	}

	// Tabs follow the section you're looking at.
	const tabs = $$("#store-tabs a");
	const spy = new IntersectionObserver((entries) => {
		for (const e of entries) {
			if (!e.isIntersecting) continue;
			tabs.forEach((t) => t.classList.toggle("on", t.getAttribute("href") === "#" + e.target.id));
		}
	}, { rootMargin: "-30% 0px -60% 0px" });
	$$(".store-section").forEach((s) => spy.observe(s));

	ui.search.addEventListener("input", () => render());
	ui.sort.addEventListener("change", () => render());
	ui.owned.addEventListener("change", () => render());

	// ---- Start ----
	(async () => {
		try {
			const [catalog, cosmetics] = await Promise.all([api("/v1/store/catalog", { auth: false }), window.velo.cosmetics()]);
			store.catalog = catalog;
			store.cosmetics = cosmetics;
			cosmetics.items.forEach((i) => (store.byId[i.id] = i));
			await loadWallet();
			renderWallet();
			renderFeatured();
			render();
			dailyCountdown();
			const params = new URLSearchParams(location.search);
			if (params.get("item") && store.byId[params.get("item")]) openItem(store.byId[params.get("item")]);
			if (location.hash === "#signin" && !store.token) openSignIn();
			if (location.hash === "#account" && store.token) openAccount();
			if (params.get("checkout") === "done") toast("Thanks! Your purchase is on its way to your account.");
		} catch (e) {
			$("#featured").replaceChildren(el("div", { class: "error" }, "The Store couldn't load right now (" + e.message + "). Try again in a minute - or buy in the launcher."));
		}
	})();
	$$("[data-signin]").forEach((b) => b.addEventListener("click", () => openSignIn()));
	addEventListener("hashchange", () => {
		if (location.hash === "#signin" && !store.token) openSignIn();
		if (location.hash === "#account" && store.token) openAccount();
	});
})();
