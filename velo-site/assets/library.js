/*
 * Velo Library (velo-client.com/library): every tool page. The page's <main data-tool="..."> picks the tool; all data
 * comes from the Library API on the same origin (/v2/...). Community presets sit under each tool.
 */
(() => {
	"use strict";
	const $ = (s, r = document) => r.querySelector(s);
	const $$ = (s, r = document) => [...r.querySelectorAll(s)];
	const root = $("[data-tool]");
	if (!root) return;
	const TOOL = root.dataset.tool;

	// ---------------------------------------------------------------- helpers
	const token = () => { try { return localStorage.getItem("velo.web.token"); } catch (e) { return null; } };
	const qs = new URLSearchParams(location.search);
	function el(tag, attrs, ...kids) {
		const n = document.createElement(tag);
		for (const [k, v] of Object.entries(attrs || {})) {
			if (v == null || v === false) continue;
			if (k === "class") n.className = v;
			else if (k === "text") n.textContent = v;
			else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
			else if (k === "style" && typeof v === "object") Object.assign(n.style, v);
			else n.setAttribute(k, v === true ? "" : v);
		}
		for (const k of kids.flat()) if (k != null && k !== false) n.append(k.nodeType ? k : document.createTextNode(String(k)));
		return n;
	}
	async function api(path, opts = {}) {
		const headers = Object.assign({}, opts.headers || {});
		if (opts.auth !== false && token()) headers.Authorization = "Bearer " + token();
		if (opts.json !== undefined) { headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(opts.json); }
		const r = await fetch("/v2/" + path, { method: opts.method || (opts.body ? "POST" : "GET"), headers, body: opts.body });
		const type = r.headers.get("content-type") || "";
		const data = type.includes("json") ? await r.json().catch(() => ({})) : await r.blob();
		if (!r.ok) {
			const e = new Error((data && data.error) || ("HTTP " + r.status));
			e.status = r.status;
			throw e;
		}
		return data;
	}
	let toastTimer;
	function toast(msg) {
		let t = $(".lib-toast");
		if (!t) { t = el("div", { class: "lib-toast", role: "status" }); document.body.append(t); }
		t.textContent = msg;
		t.classList.add("on");
		clearTimeout(toastTimer);
		toastTimer = setTimeout(() => t.classList.remove("on"), 2200);
	}
	async function copy(text, what = "Copied") {
		try { await navigator.clipboard.writeText(text); toast(what); } catch (e) { prompt("Copy:", text); }
	}
	const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
	const pill = (label, on, click) => el("button", { type: "button", class: "lib-pill", "aria-pressed": on ? "true" : "false", onclick: click }, label);
	function pills(container, options, current, onPick) {
		container.replaceChildren(...options.map(([k, l]) => pill(l, k === current, () => onPick(k))));
	}
	const signedIn = () => !!token();
	const signInLink = () => el("a", { class: "lib-btn red", href: "/store#signin" }, "Sign in with your launcher code");
	const fmtDate = (ms) => ms ? new Date(ms).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : "";
	const ago = (ms) => {
		const s = (Date.now() - ms) / 1000;
		if (s < 3600) return Math.max(1, Math.round(s / 60)) + " min ago";
		if (s < 86400) return Math.round(s / 3600) + " h ago";
		return Math.round(s / 86400) + " days ago";
	};
	const isAddress = (s) => /^[a-z0-9.-]+\.[a-z]{2,}(:\d+)?$/i.test(s.trim());
	const isPlayer = (s) => /^[A-Za-z0-9_]{1,16}$/.test(s.trim()) || /^[0-9a-f-]{32,36}$/i.test(s.trim());

	// ---------------------------------------------------------------- MiniMessage (client side, for live previews)
	const NAMED = { black: "#000000", dark_blue: "#0000AA", dark_green: "#00AA00", dark_aqua: "#00AAAA", dark_red: "#AA0000", dark_purple: "#AA00AA", gold: "#FFAA00",
		gray: "#AAAAAA", dark_gray: "#555555", blue: "#5555FF", green: "#55FF55", aqua: "#55FFFF", red: "#FF5555", light_purple: "#FF55FF", yellow: "#FFFF55", white: "#FFFFFF" };
	const CODES = { black: "0", dark_blue: "1", dark_green: "2", dark_aqua: "3", dark_red: "4", dark_purple: "5", gold: "6", gray: "7", dark_gray: "8", blue: "9",
		green: "a", aqua: "b", red: "c", light_purple: "d", yellow: "e", white: "f" };
	const hx = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
	const lerp = (a, b, t) => "#" + hx(a).map((v, i) => Math.round(v + (hx(b)[i] - v) * t).toString(16).padStart(2, "0")).join("");
	const multi = (stops, t) => {
		if (stops.length === 1) return stops[0];
		const seg = t * (stops.length - 1), i = Math.min(stops.length - 2, Math.floor(seg));
		return lerp(stops[i], stops[i + 1], seg - i);
	};
	/** MiniMessage -> segments [{t, color, bold, italic, under, strike} | {img}] per line. */
	function parseMM(src) {
		const lines = [[]];
		let color = "#AAAAAA", bold = false, italic = false, under = false, strike = false, grad = null;
		const style = () => ({ color, bold, italic, under, strike });
		const pushText = (t) => {
			const parts = t.split("\n");
			parts.forEach((p, i) => {
				if (i > 0) lines.push([]);
				if (!p) return;
				if (grad) grad.buf.push(...[...p].map((ch) => ({ ch, ...style() })));
				else lines[lines.length - 1].push({ t: p, ...style() });
			});
		};
		const flush = () => {
			if (!grad) return;
			const n = grad.buf.length;
			grad.buf.forEach((c, i) => lines[lines.length - 1].push({ t: c.ch, ...c, color: multi(grad.stops, n > 1 ? i / (n - 1) : 0) }));
			grad = null;
		};
		const re = /<(\/?)([a-z_#:0-9/.]+)>/gi;
		let last = 0, m;
		while ((m = re.exec(src))) {
			pushText(src.slice(last, m.index).replace(/\\</g, "<"));
			last = re.lastIndex;
			const close = m[1] === "/", tag = m[2].toLowerCase();
			if (tag.startsWith("sprite:")) { lines[lines.length - 1].push({ img: "/v2/item/" + tag.split("/").pop() + ".png?size=32", alt: tag.split("/").pop() }); continue; }
			if (tag.startsWith("head:")) { lines[lines.length - 1].push({ img: "/v2/head/" + tag.slice(5) + ".png?size=32", alt: tag.slice(5) }); continue; }
			if (tag === "newline" || tag === "br") { flush(); lines.push([]); continue; }
			if (tag.startsWith("gradient") || tag.startsWith("rainbow")) {
				if (close) flush();
				else {
					const p = tag.split(":").slice(1).filter((x) => /^#[0-9a-f]{6}$/.test(x) || NAMED[x]).map((x) => NAMED[x] || x);
					grad = { stops: tag.startsWith("rainbow") ? ["#FF5555", "#FFAA00", "#FFFF55", "#55FF55", "#55FFFF", "#5555FF", "#FF55FF"] : (p.length ? p : ["#ffffff"]), buf: [] };
				}
				continue;
			}
			if (tag === "bold" || tag === "b") { bold = !close; continue; }
			if (tag === "italic" || tag === "i") { italic = !close; continue; }
			if (tag === "underlined" || tag === "u") { under = !close; continue; }
			if (tag === "strikethrough" || tag === "st") { strike = !close; continue; }
			if (tag === "reset") { color = "#AAAAAA"; bold = italic = under = strike = false; continue; }
			if (close) { color = "#AAAAAA"; continue; }
			if (/^#[0-9a-f]{6}$/.test(tag)) { color = tag; continue; }
			if (NAMED[tag]) color = NAMED[tag];
		}
		pushText(src.slice(last).replace(/\\</g, "<"));
		flush();
		return lines;
	}
	function renderLine(segs) {
		const line = el("div", { class: "mc-line" });
		for (const s of segs) {
			if (s.img) line.append(el("img", { src: s.img, alt: s.alt || "", class: "lib-px" }));
			else line.append(el("span", { style: { color: s.color, fontWeight: s.bold ? "800" : "", fontStyle: s.italic ? "italic" : "",
				textDecoration: [s.under ? "underline" : "", s.strike ? "line-through" : ""].join(" ").trim() } }, s.t));
		}
		return line;
	}
	function toLegacy(src) {
		const hexCode = (h) => "§x" + h.slice(1).split("").map((c) => "§" + c).join("");
		return src.replace(/<sprite:[^>]*\/([a-z_]+)>/gi, "[$1]").replace(/<head:([^>]+)>/gi, "[$1]").replace(/<newline>/gi, "\n")
			.replace(/<\/[^>]+>/g, "§r").replace(/<gradient:(#[0-9a-f]{6})[^>]*>/gi, (x, h) => hexCode(h)).replace(/<(#[0-9a-f]{6})>/gi, (x, h) => hexCode(h))
			.replace(/<(bold|b)>/gi, "§l").replace(/<(italic|i)>/gi, "§o").replace(/<(underlined|u)>/gi, "§n").replace(/<(strikethrough|st)>/gi, "§m").replace(/<reset>/gi, "§r")
			.replace(/<([a-z_]+)>/gi, (x, n) => CODES[n.toLowerCase()] ? "§" + CODES[n.toLowerCase()] : "");
	}
	function toComponent(src) {
		const out = [];
		for (const [li, line] of parseMM(src).entries()) {
			if (li > 0) out.push({ text: "\n" });
			for (const s of line) {
				if (s.img) {
					const id = s.alt;
					out.push(s.img.includes("/head/") ? { type: "object", player: { name: id }, fallback: "[" + id + "]" }
						: { type: "object", atlas: "minecraft:items", sprite: "item/" + id, fallback: "[" + id + "]" });
				} else {
					const c = { text: s.t, color: Object.keys(NAMED).find((k) => NAMED[k].toLowerCase() === s.color.toLowerCase()) || s.color.toLowerCase() };
					if (s.bold) c.bold = true;
					if (s.italic) c.italic = true;
					if (s.under) c.underlined = true;
					if (s.strike) c.strikethrough = true;
					out.push(c);
				}
			}
		}
		const merged = [];
		for (const c of out) {
			const p = merged[merged.length - 1];
			if (p && p.text !== undefined && c.text !== undefined && !c.type && !p.type && p.color === c.color && !!p.bold === !!c.bold && !!p.italic === !!c.italic && c.text !== "\n" && p.text !== "\n") p.text += c.text;
			else merged.push(c);
		}
		return { text: "", extra: merged };
	}

	// ---------------------------------------------------------------- community presets (under every tool)
	function presets(tool, { current, apply, preview }) {
		const box = $("[data-presets]");
		if (!box) return { reload() {} };
		box.replaceChildren();
		let sort = "popular";
		const list = el("div", { class: "lib-preset-list" });
		const sorts = el("div", { class: "lib-pills" });
		const publish = el("button", { type: "button", class: "lib-btn red sm" }, "Publish yours");
		publish.addEventListener("click", async () => {
			if (!signedIn()) { location.href = "/store#signin"; return; }
			const name = prompt("Name your preset (3–40 characters):");
			if (!name) return;
			try {
				await api("presets", { json: { tool, name, data: current() } });
				toast("Published - thanks!");
				load();
			} catch (e) { toast(e.message); }
		});
		box.append(el("div", { class: "lib-presets-head" },
			el("div", { style: { display: "flex", flexDirection: "column", gap: "8px" } }, el("span", { class: "lib-label" }, "Community presets"),
				el("h2", {}, "Start from what others made.")),
			el("div", { style: { display: "flex", gap: "10px", alignItems: "center", flexWrap: "wrap" } }, sorts, publish)), list);
		const drawSorts = () => pills(sorts, [["popular", "Popular"], ["new", "New"]], sort, (k) => { sort = k; drawSorts(); load(); });
		drawSorts();
		async function load() {
			try {
				const data = await api(`presets?tool=${tool}&sort=${sort}&limit=12`);
				if (!data.presets.length) {
					list.replaceChildren(el("div", { class: "lib-empty", style: { gridColumn: "1 / -1" } }, "No presets yet - be the first to publish one."));
					return;
				}
				list.replaceChildren(...data.presets.map(card));
			} catch (e) {
				list.replaceChildren(el("div", { class: "lib-empty", style: { gridColumn: "1 / -1" } }, e.status === 503 ? "Presets are paused right now." : "Couldn't load presets."));
			}
		}
		function card(p) {
			const like = el("button", { type: "button", class: "lib-btn sm" + (p.liked ? " liked" : ""), "aria-label": "Like" }, "♥ " + p.likes);
			like.addEventListener("click", async () => {
				if (!signedIn()) { location.href = "/store#signin"; return; }
				try { const r = await api(`presets/${p.id}/like`, { method: "POST" }); like.textContent = "♥ " + r.likes; like.classList.toggle("liked", r.liked); } catch (e) { toast(e.message); }
			});
			const use = el("button", { type: "button", class: "lib-btn sm red" }, "Use");
			use.addEventListener("click", () => {
				apply(p.data);
				api(`presets/${p.id}/use`, { method: "POST" }).catch(() => {});
				window.scrollTo({ top: 0, behavior: "smooth" });
				toast("Loaded “" + p.name + "”");
			});
			const more = el("button", { type: "button", class: "lib-btn sm", title: p.mine ? "Delete" : "Report" }, p.mine ? "Delete" : "Report");
			more.addEventListener("click", async () => {
				if (p.mine) {
					if (!confirm("Delete “" + p.name + "”?")) return;
					try { await api(`presets/${p.id}`, { method: "DELETE" }); load(); } catch (e) { toast(e.message); }
				} else {
					if (!confirm("Report “" + p.name + "” as inappropriate?")) return;
					api(`presets/${p.id}/report`, { method: "POST" }).then(() => toast("Reported - thanks")).catch((e) => toast(e.message));
				}
			});
			const pv = el("div", { class: "pv" });
			try { preview(pv, p.data); } catch (e) { pv.textContent = p.name; }
			return el("article", { class: "lib-preset" }, pv,
				el("div", { style: { display: "flex", justifyContent: "space-between", gap: "8px" } }, el("strong", {}, p.name), el("span", { class: "lib-muted" }, p.uses + " uses")),
				el("div", { class: "meta" }, el("img", { src: `/v2/head/${p.authorUuid}.png?size=32`, alt: "", class: "lib-px", loading: "lazy" }), p.author),
				el("div", { class: "acts" }, use, like, more));
		}
		load();
		return { reload: load };
	}

	// ---------------------------------------------------------------- tools
	const tools = {};

	tools.hub = () => {
		const TOOLS = [
			["Skin & name lookup", "player", "Skin, cape, model, name and skin history for any Java player — plus whether a name is free.", "/library/player", "/v2/head/MHF_Steve.png?size=64", "/v2/player/:name"],
			["Pose renders", "player", "Any player (or your own skin file) in any pose and angle, with 3D skin layers — PNG or animated GIF.", "/library/render", "/v2/item/armor_stand.png?size=64", "/v2/render/:name"],
			["Item icons", "assets", "Every item and block icon by name or old numeric id, straight from the newest game version.", "/library/items", "/v2/item/diamond_sword.png?size=64", "/v2/item/:id.png"],
			["Map art generator", "player", "Any picture as map art - flat or staircase, 1×1 to 4×4 maps, your own block palette, material list, loads straight into Velo.", "/library/mapart", "/v2/item/filled_map.png?size=64", ".litematic"],
			["Pixel art & text", "player", "Pictures and lettering as blocks with real texture colours - wall or floor, any size, wool to stone.", "/library/pixelart", "/v2/item/painting.png?size=64", ".litematic"],
			["Community schematics", "player", "Browse and download builds and redstone from the community - or share your .veloschem / .litematic with an automatic 3D preview.", "/library/schematics", "/v2/item/redstone.png?size=64", "/v1/schematics"],
			["Pack hosting", "server", "Host a resource pack: direct link, SHA-1 and ready server.properties lines.", "/library/packs", "/v2/item/painting.png?size=64", "/v2/packs"],
			["MOTD designer", "server", "MiniMessage with live server-list preview, item and head icons, every export format.", "/library/motd", "/v2/item/name_tag.png?size=64", "/v2/motd/render.png"],
			["HUD designer", "player", "Draw your own crosshair, hearts, hunger and armor icons in the browser - one command puts them in Velo.", "/library/hud", "/v2/sprite/heart/full.png", "/velo import"],
			["Animated TAB & colours", "text", "Gradients and animations for TAB, scoreboards and holograms, and a colour picker with every format.", "/library/tab", "/v2/item/glow_ink_sac.png?size=64", "/v2/gradient"],
			["Banner designer", "player", "Design Minecraft banners layer by layer with every pattern and dye — loom-ready, with /give commands and community designs.", "/library/banner", "/v2/mcbanner.png?base=red&layers=stripe_top.white,circle.yellow,border.black&scale=2", "/v2/mcbanner.png"],
			["Server mappings", "server", "Your server's name, logo and banner in every Velo player's Discord status. Sign up in two minutes.", "/library/servers", "/v2/item/name_tag.png?size=64", "/v2/servers"],
			["Server status", "server", "Ping any Java server: players, version, MOTD, icon, latency and the last 24 hours.", "/library/server", "/v2/item/compass.png?size=64", "/v2/server/:address"],
			["Spark analyzer", "server", "Paste a spark profile link and see which plugins and settings cost your server the most.", "/library/spark", "/v2/item/clock.png?size=64", "/v2/spark/:id"],
			["Flags generator", "server", "JVM start flags for your RAM, server software (Paper, Folia, Purpur, Velocity, Fabric) and OS.", "/library/flags", "/v2/item/redstone.png?size=64", "/v2/flags"],
			["Plugin updates", "server", "Paste your plugin list: newest versions on Modrinth, Hangar and SpigotMC, Folia support, abandoned ones.", "/library/plugins", "/v2/item/enchanted_book.png?size=64", "/v2/plugins/check"],
			["Enchant planner", "player", "The cheapest anvil order for your books — level cost per step, never “Too Expensive!”.", "/library/enchant", "/v2/item/anvil.png?size=64", "client side"],
			["Seed map", "player", "Biomes, structures and slime chunks for any seed, plus the Nether portal calculator.", "/library/seed", "/v2/item/filled_map.png?size=64", "/v2/seed/:seed"],
			["API & keys", "dev", "Every tool as an HTTPS endpoint. Free keys, fair limits.", "/library/api", "/v2/item/command_block.png?size=64", "/v2/…"],
		];
		const grid = $("#tools"), filters = $("#filters"), count = $("#count");
		let cat = "all";
		const cats = [["all", "All"], ["player", "Players"], ["server", "Server owners"], ["text", "Text"], ["assets", "Assets"], ["dev", "Developers"]];
		const draw = () => {
			const shown = TOOLS.filter((t) => cat === "all" || t[1] === cat);
			count.textContent = shown.length + " tools";
			pills(filters, cats.map(([k, l]) => [k, l + " " + (k === "all" ? TOOLS.length : TOOLS.filter((t) => t[1] === k).length)]), cat, (k) => { cat = k; draw(); });
			grid.replaceChildren(...shown.map(([name, c, desc, href, icon, ep]) => el("a", { class: "lib-tool", href },
				el("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "flex-start" } },
					el("div", { class: "ico" }, el("img", { src: icon, alt: "", class: "lib-px", loading: "lazy" })), el("span", { class: "lib-tag" }, c.toUpperCase())),
				el("h3", {}, name), el("p", {}, desc), el("div", { class: "foot" }, el("span", {}, ep), el("span", { class: "go" }, "Open →")))));
		};
		draw();
		$("#lookup").addEventListener("submit", (e) => {
			e.preventDefault();
			const q = $("#q").value.trim();
			if (!q) return;
			if (isAddress(q)) location.href = "/library/server?address=" + encodeURIComponent(q);
			else if (/^\d{1,4}(:\d+)?$|^minecraft:|_/.test(q) && !isPlayer(q.replace("minecraft:", "x"))) location.href = "/library/items?q=" + encodeURIComponent(q);
			else if (isPlayer(q)) location.href = "/library/player?name=" + encodeURIComponent(q);
			else location.href = "/library/items?q=" + encodeURIComponent(q);
		});
		$$("[data-try]").forEach((a) => a.addEventListener("click", (e) => { e.preventDefault(); $("#q").value = a.dataset.try; $("#lookup").requestSubmit(); }));
	};

	tools.player = () => {
		const form = $("#lookup"), q = $("#q"), out = $("#result");
		let viewer = null;
		async function look(name) {
			out.replaceChildren(el("div", { class: "lib-empty" }, "Looking up " + name + "…"));
			history.replaceState(null, "", "?name=" + encodeURIComponent(name));
			try {
				const p = await api("player/" + encodeURIComponent(name));
				draw(p);
			} catch (e) {
				out.replaceChildren(el("div", { class: "lib-empty" }, e.status === 404 ? "No Java account is called “" + name + "”. " : e.message,
					e.status === 404 ? el("a", { href: "#avail", onclick: () => { $("#name").value = name; checkName(); } }, "Is the name free?") : null));
			}
		}
		function draw(p) {
			const stage = el("div", { class: "lib-box", style: { height: "440px", position: "relative", background: "radial-gradient(120% 80% at 50% 100%, #17171d, #0e0e12)" } });
			const poses = el("div", { class: "lib-pills", style: { justifyContent: "center" } });
			const left = el("div", { class: "lib-side", style: { flex: "1 1 340px" } }, stage, poses,
				el("div", { style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px" } },
					el("a", { class: "lib-btn", href: p.skin || "/v2/skin/" + p.uuid + ".png", download: p.name + ".png" }, "Download skin"),
					el("a", { class: "lib-btn", href: "/library/render?name=" + p.name }, "Render in a pose"),
					el("button", { type: "button", class: "lib-btn", onclick: () => copy(p.giveHead, "Command copied") }, "Copy /give head"),
					el("a", { class: "lib-btn red", href: "/store" }, "Try Velo cosmetics")));
			const names = el("div", { class: "lib-box" }, ...p.names.map((n, i) => el("div", { style: { display: "grid", gridTemplateColumns: "40px 1fr auto", gap: "14px", padding: "12px 18px",
				borderTop: i ? "1px solid var(--line)" : "0" } }, el("span", { class: "lib-muted" }, String(p.names.length - i).padStart(2, "0")), el("strong", {}, n.name),
				el("span", { class: "lib-muted" }, i === 0 ? "current" : "seen " + fmtDate(n.seen)))));
			const skins = el("div", { style: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(110px, 1fr))", gap: "10px" } },
				...p.skins.map((s, i) => el("a", { class: "lib-box lib-pad", href: "/v2/texture/" + s.hash + ".png", download: p.name + "-" + i + ".png",
					style: { display: "flex", flexDirection: "column", alignItems: "center", gap: "8px", textDecoration: "none", borderColor: i === 0 ? "var(--red)" : "" } },
				el("img", { src: "/v2/texture/" + s.hash + ".png", alt: "", class: "lib-px", style: { width: "64px", height: "64px" }, loading: "lazy" }),
				el("span", { class: "lib-muted" }, i === 0 ? "current" : fmtDate(s.seen)))));
			const main = el("div", { class: "lib-main" },
				el("div", { style: { display: "flex", gap: "18px", alignItems: "center", flexWrap: "wrap" } },
					el("img", { src: p.head + "?size=128", alt: "", class: "lib-px", style: { width: "72px", height: "72px", borderRadius: "12px" } }),
					el("div", { style: { minWidth: 0 } }, el("h2", { style: { fontSize: "44px" } }, p.name),
						el("button", { type: "button", class: "lib-muted", style: { background: "none", border: 0, padding: 0, cursor: "pointer", font: "500 13px var(--mono)", wordBreak: "break-all" },
							onclick: () => copy(p.uuidDashed, "UUID copied") }, p.uuidDashed + " · copy"))),
				el("div", { class: "lib-grid lib-stats" },
					stat("Model", p.model === "slim" ? "Slim (Alex)" : "Classic (Steve)"), stat("Cape", p.cape ? "Yes" : "None"),
					stat("Lookups here", String(p.lookups)), stat("First seen here", fmtDate(p.firstSeenHere))),
				p.cape ? el("div", { style: { display: "flex", gap: "12px", alignItems: "center" } }, el("img", { src: p.cape, alt: "Cape texture", class: "lib-px", style: { height: "64px" } }),
					el("span", { class: "lib-muted" }, "Cape texture")) : null,
				el("h3", {}, "Name history"), names,
				el("p", { class: "lib-muted", style: { margin: 0 } }, "Mojang removed the public history in 2022 - this is what the Library has seen since it started, and grows with every lookup."),
				el("h3", {}, "Skins seen (" + p.skins.length + ")"), skins);
			out.replaceChildren(el("div", { class: "lib-row" }, left, main));
			const start = () => window.velo.player3d(stage, { skin: "/v2/skin/" + p.uuid + ".png", slim: p.model === "slim" }).then((v) => { viewer = v; }).catch(() => {
				stage.replaceChildren(el("img", { src: p.render + "?size=400", alt: "", style: { display: "block", margin: "20px auto", height: "400px" } }));
			});
			if (window.velo && window.velo.player3d) start(); else addEventListener("velo:skin3d", start, { once: true });
			const onPose = (k) => {
				if (k === "render") stage.replaceChildren(el("img", { src: p.render + "?size=420&pose=wave", alt: "", style: { display: "block", margin: "10px auto", height: "420px" } }));
				else { if (viewer && viewer.dispose) viewer.dispose(); stage.replaceChildren(); start(); }
				pills(poses, [["still", "3D viewer"], ["render", "Pose render"]], k, onPose);
			};
			pills(poses, [["still", "3D viewer"], ["render", "Pose render"]], "still", onPose);
		}
		const stat = (k, v) => el("div", { class: "lib-stat" }, el("span", { class: "lib-label" }, k), el("b", {}, v));
		form.addEventListener("submit", (e) => { e.preventDefault(); if (q.value.trim()) look(q.value.trim()); });
		async function checkName() {
			const n = $("#name").value.trim(), res = $("#avail-result");
			if (!n) return;
			res.textContent = "Checking…";
			try {
				const r = await api("name/" + encodeURIComponent(n));
				res.replaceChildren(el("span", { class: "lib-tag " + (r.status === "available" ? "good" : r.status === "taken" ? "bad" : "warn") }, r.status), " ",
					r.status === "taken" ? el("a", { href: "?name=" + encodeURIComponent(n) }, "see who has it") : r.note || "");
			} catch (e) { res.textContent = e.message; }
		}
		$("#avail").addEventListener("submit", (e) => { e.preventDefault(); checkName(); });

		if (qs.get("name")) { q.value = qs.get("name"); look(qs.get("name")); }
	};

	tools.render = () => {
		const st = { name: qs.get("name") || "Notch", pose: "wave", yaw: -25, pitch: 10, size: 512, bg: "", layers3d: true, cape: true, overlay: true, gif: false, slim: "auto" };
		let custom = null, customUrl = null;
		const img = $("#preview"), urlBox = $("#url"), poses = $("#poses");
		const POSES = ["stand", "walk", "run", "wave", "sit", "crouch", "fight", "dab", "point", "think", "tpose", "float", "custom"];
		// Custom pose: every joint in degrees (x = swing forward/back, y = turn, z = out to the side)
		const JOINTS = [["head", "Head", ["x", "y", "z"]], ["body", "Body", ["x", "y", "z"]], ["rarm", "Right arm", ["x", "y", "z"]],
			["larm", "Left arm", ["x", "y", "z"]], ["rleg", "Right leg", ["x", "y", "z"]], ["lleg", "Left leg", ["x", "y", "z"]]];
		const AXIS = { x: "swing", y: "turn", z: "side" };
		st.j = Object.fromEntries(JOINTS.map(([k]) => [k, [0, 0, 0]]));
		const jointsBox = $("#joints");
		const drawJoints = () => {
			jointsBox.hidden = st.pose !== "custom";
			if (jointsBox.hidden) return;
			jointsBox.replaceChildren(el("span", {}, "Joints"), ...JOINTS.map(([k, label, axes]) => el("div", { class: "lib-joint" },
				el("b", {}, label), ...axes.map((a) => {
					const i = "xyz".indexOf(a);
					const out = el("em", {}, st.j[k][i] + "°");
					const input = el("input", { type: "range", min: k === "body" || k === "head" ? -90 : -180, max: k === "body" || k === "head" ? 90 : 180, step: 5, value: st.j[k][i], "aria-label": label + " " + AXIS[a] });
					input.addEventListener("input", () => { st.j[k][i] = +input.value; out.textContent = input.value + "°"; refresh(); });
					return el("label", {}, el("small", {}, AXIS[a]), input, out);
				}))),
			el("button", { class: "lib-btn", type: "button", onclick: () => { JOINTS.forEach(([k]) => (st.j[k] = [0, 0, 0])); drawJoints(); refresh(); } }, "Reset joints"));
		};
		const url = () => {
			const p = new URLSearchParams({ pose: st.pose, yaw: st.yaw, pitch: st.pitch, size: st.gif ? Math.min(st.size, 384) : st.size });
			if (st.bg) p.set("bg", st.bg.replace("#", ""));
			if (!st.layers3d) p.set("layers3d", "false");
			if (!st.cape) p.set("cape", "false");
			if (!st.overlay) p.set("overlay", "false");
			if (st.slim !== "auto") p.set("slim", st.slim);
			if (st.pose === "custom") for (const [k] of JOINTS) if (st.j[k].some((v) => v)) p.set(k, st.j[k].join(","));
			return `/v2/render/${encodeURIComponent(st.name)}.${st.gif ? "gif" : "png"}?${p}`;
		};
		const refresh = debounce(async () => {
			$("#err").textContent = "";
			if (custom) {
				const p = new URLSearchParams(url().split("?")[1]);
				try {
					const blob = await api("render/custom.png?" + p, { method: "POST", body: custom, auth: false, headers: { "Content-Type": "image/png" } });
					if (customUrl) URL.revokeObjectURL(customUrl);
					customUrl = URL.createObjectURL(blob);
					img.src = customUrl;
				} catch (e) { $("#err").textContent = e.message; }
				urlBox.textContent = "POST https://velo-client.com/v2/render/custom.png?" + p + "  (body: your skin PNG)";
				return;
			}
			img.src = url();
			urlBox.textContent = "https://velo-client.com" + url();
		}, 250);
		img.addEventListener("error", () => { $("#err").textContent = "Couldn't render that - check the name."; });
		const draw = () => {
			pills(poses, POSES.map((p) => [p, p[0].toUpperCase() + p.slice(1)]), st.pose, (k) => { st.pose = k; draw(); refresh(); });
			drawJoints();
			pills($("#sizes"), [[128, "128"], [256, "256"], [512, "512"], [1024, "1024"], [2048, "2048"]], st.size, (k) => { st.size = k; draw(); refresh(); });
			pills($("#bgs"), [["", "None"], ["#0e0e12", "Dark"], ["#e0262e", "Velo red"], ["#7fb6ff", "Sky"], ["#ffffff", "White"]], st.bg, (k) => { st.bg = k; draw(); refresh(); });
			pills($("#model"), [["auto", "From account"], ["false", "Classic"], ["true", "Slim"]], st.slim, (k) => { st.slim = k; draw(); refresh(); });
			$("#yawv").textContent = st.yaw + "°";
			$("#pitchv").textContent = st.pitch + "°";
		};
		$("#name").value = st.name;
		$("#name").addEventListener("input", debounce(() => { st.name = $("#name").value.trim() || "Notch"; custom = null; refresh(); }, 500));
		$("#yaw").addEventListener("input", (e) => { st.yaw = +e.target.value; draw(); refresh(); });
		$("#pitch").addEventListener("input", (e) => { st.pitch = +e.target.value; draw(); refresh(); });
		for (const k of ["layers3d", "cape", "overlay", "gif"]) $("#" + k).addEventListener("change", (e) => { st[k] = e.target.checked; refresh(); });
		$("#file").addEventListener("change", (e) => {
			const f = e.target.files[0];
			if (!f) return;
			if (f.size > 256 * 1024) { toast("Skin files are small - that one is too big"); return; }
			custom = f;
			if (st.slim === "auto") st.slim = "false";
			draw();
			refresh();
		});
		$("#copy").addEventListener("click", () => copy(urlBox.textContent, "URL copied"));
		$("#download").addEventListener("click", () => { const a = el("a", { href: img.src, download: st.name + "-" + st.pose + (st.gif ? ".gif" : ".png") }); a.click(); });
		draw();
		refresh();
		presets("pose", {
			current: () => ({ pose: st.pose, yaw: st.yaw, pitch: st.pitch, bg: st.bg, layers3d: st.layers3d, ...(st.pose === "custom" ? { joints: st.j } : {}) }),
			apply: (d) => { Object.assign(st, { pose: d.pose || "stand", yaw: +d.yaw || 0, pitch: +d.pitch || 0, bg: d.bg || "", layers3d: d.layers3d !== false });
				if (d.joints) for (const [k] of JOINTS) if (Array.isArray(d.joints[k])) st.j[k] = d.joints[k].slice(0, 3).map((v) => Math.max(-180, Math.min(180, +v || 0)));
				$("#yaw").value = st.yaw; $("#pitch").value = st.pitch; $("#layers3d").checked = st.layers3d; draw(); refresh(); },
			preview: (pv, d) => pv.append(el("img", { src: `/v2/render/${encodeURIComponent(st.name)}.png?pose=${encodeURIComponent(d.pose)}&yaw=${d.yaw}&pitch=${d.pitch}&size=160${d.bg ? "&bg=" + d.bg.replace("#", "") : ""}${d.joints ? Object.entries(d.joints).map(([k, v]) => Array.isArray(v) ? `&${encodeURIComponent(k)}=${v.map(Number).join(",")}` : "").join("") : ""}`, alt: "", loading: "lazy" })),
		});
	};

	tools.motd = () => {
		const st = { l1: "<gradient:#ff6b73:#f4b82e><bold>VELO NETWORK</bold></gradient> <gray>· <white>1.8 – 26.3", l2: "<sprite:items:item/mace> <yellow>Mace PvP</yellow> <dark_gray>|</dark_gray> <sprite:items:item/totem_of_undying> <aqua>Season 4 is live", tab: "mm", name: "Velo Network", icon: "grass_block" };
		const l1 = $("#l1"), l2 = $("#l2"), lines = $("#lines"), out = $("#out");
		const draw = () => {
			lines.replaceChildren(...[...parseMM(st.l1).slice(0, 1), ...parseMM(st.l2).slice(0, 1)].map(renderLine));
			$("#sname").textContent = st.name;
			$("#sicon").src = "/v2/item/" + st.icon + ".png?size=64";
			const leg = toLegacy(st.l1) + "\n" + toLegacy(st.l2);
			out.textContent = st.tab === "mm" ? st.l1 + "<newline>" + st.l2
				: st.tab === "legacy" ? leg
				: st.tab === "props" ? "motd=" + leg.replace(/§/g, "\\u00A7").replace(/\n/g, "\\n")
				: st.tab === "velocity" ? "motd = \"" + (st.l1 + "<newline>" + st.l2).replace(/"/g, "\\\"") + "\""
				: JSON.stringify(toComponent(st.l1 + "<newline>" + st.l2), null, 2);
			pills($("#tabs"), [["mm", "MiniMessage"], ["legacy", "Legacy §"], ["props", "server.properties"], ["velocity", "Velocity"], ["json", "JSON (1.21.9+ objects)"]], st.tab, (k) => { st.tab = k; draw(); });
		};
		l1.value = st.l1;
		l2.value = st.l2;
		l1.addEventListener("input", () => { st.l1 = l1.value; draw(); });
		l2.addEventListener("input", () => { st.l2 = l2.value; draw(); });
		$("#name").value = st.name;
		$("#name").addEventListener("input", (e) => { st.name = e.target.value; draw(); });
		const CHIPS = [["<sprite:items:item/diamond_sword>", "diamond_sword", 1], ["<sprite:items:item/mace>", "mace", 1], ["<sprite:items:item/golden_apple>", "golden_apple", 1],
			["<sprite:items:item/ender_pearl>", "ender_pearl", 1], ["<sprite:items:item/totem_of_undying>", "totem", 1], ["<head:Notch>", "head:Notch", 2],
			["<gradient:#5555FF:#FF55FF>text</gradient>", "gradient", 0], ["<rainbow>text</rainbow>", "rainbow", 0], ["<bold>", "bold", 0], ["<gold>", "gold", 0], ["<green>", "green", 0],
			["<aqua>", "aqua", 0], ["<#e0262e>", "#e0262e", 0], ["<reset>", "reset", 0]];
		$("#chips").replaceChildren(...CHIPS.map(([ins, label, kind]) => el("button", { type: "button", class: "lib-pill", onclick: () => {
			const target = document.activeElement === l1 ? l1 : l2;
			const at = target.selectionStart ?? target.value.length;
			target.value = target.value.slice(0, at) + ins + target.value.slice(at);
			st.l1 = l1.value; st.l2 = l2.value; draw(); target.focus();
		} }, kind === 1 ? el("img", { src: "/v2/item/" + (label === "totem" ? "totem_of_undying" : label) + ".png?size=32", alt: "", class: "lib-px", style: { width: "16px", height: "16px", marginRight: "6px", verticalAlign: "-3px" } }) : null,
		kind === 2 ? el("img", { src: "/v2/head/Notch.png?size=32", alt: "", class: "lib-px", style: { width: "16px", height: "16px", marginRight: "6px", verticalAlign: "-3px" } }) : null, label)));
		$("#icon").addEventListener("change", (e) => { st.icon = e.target.value.trim().toLowerCase().replace("minecraft:", "") || "grass_block"; draw(); });
		$("#import").addEventListener("submit", async (e) => {
			e.preventDefault();
			const a = $("#addr").value.trim();
			if (!a) return;
			try {
				const s = await api("server/" + encodeURIComponent(a));
				if (!s.online) { toast("That server is offline"); return; }
				const [x, y = ""] = s.motd.minimessage.split("<newline>");
				st.l1 = x.trim(); st.l2 = y.replace(/^<reset>/, "").trim(); st.name = a;
				l1.value = st.l1; l2.value = st.l2; $("#name").value = a;
				draw();
				toast("Imported " + a);
			} catch (err) { toast(err.message); }
		});
		$("#copy").addEventListener("click", () => copy(out.textContent));
		$("#png").addEventListener("click", () => {
			const p = new URLSearchParams({ mm: st.l1 + "<newline>" + st.l2, name: st.name, icon: st.icon, scale: 3 });
			window.open("/v2/motd/render.png?" + p, "_blank");
		});
		draw();
		presets("motd", {
			current: () => ({ l1: st.l1, l2: st.l2 }),
			apply: (d) => { st.l1 = String(d.l1 || ""); st.l2 = String(d.l2 || ""); l1.value = st.l1; l2.value = st.l2; draw(); },
			preview: (pv, d) => { pv.style.flexDirection = "column"; pv.style.alignItems = "flex-start"; pv.style.background = "#1a140e";
				pv.append(renderLine(parseMM(String(d.l1 || ""))[0] || []), renderLine(parseMM(String(d.l2 || ""))[0] || [])); },
		});
	};

	tools.tab = () => {
		const st = { text: "VELO NETWORK", stops: ["#ff6b73", "#f4b82e", "#e0262e"], mode: "wave", interval: 120, frames: 20, frame: 0, fmt: "tab", pick: "#e0262e", bold: true };
		const at = (t) => {
			t = ((t % 1) + 1) % 1;
			const loop = st.stops.concat([st.stops[0]]);
			const seg = t * (loop.length - 1), i = Math.floor(seg);
			return lerp(loop[i], loop[Math.min(i + 1, loop.length - 1)], seg - i);
		};
		const colors = (fr) => {
			const n = st.text.length, p = fr / st.frames;
			return [...st.text].map((ch, i) => {
				const x = n > 1 ? i / (n - 1) : 0;
				if (st.mode === "shift") return at(p);
				if (st.mode === "pulse") return at(0.5 + 0.25 * Math.sin(p * Math.PI * 2) + x * 0.15);
				if (st.mode === "static") return multi(st.stops, x);
				return at(x * 0.5 - p);
			});
		};
		const stage = $("#stage");
		const paint = () => {
			const c = colors(st.frame);
			stage.replaceChildren(...[...st.text].map((ch, i) => el("span", { style: { color: c[i] } }, ch)));
			$("#frame").textContent = `frame ${st.frame + 1} / ${st.frames} · ${st.interval} ms`;
		};
		let timer;
		const loop = () => { clearTimeout(timer); timer = setTimeout(() => { st.frame = (st.frame + 1) % (st.mode === "static" ? 1 : st.frames); paint(); loop(); }, st.interval); };
		const exportText = () => {
			const n = st.mode === "static" ? 1 : st.frames;
			const frame = (f, kind) => colors(f).map((c, i) => {
				const ch = st.text[i];
				if (kind === "mm") return "<" + c + ">" + (st.bold ? "<b>" : "") + (ch === "<" ? "\\<" : ch);
				if (kind === "legacy") return "§x" + c.slice(1).split("").map((d) => "§" + d).join("") + (st.bold ? "§l" : "") + ch;
				return "&" + c + (st.bold ? "&l" : "") + ch;
			}).join("");
			if (st.fmt === "tab") return "velo-title:\n  change-interval: " + st.interval + "\n  texts:\n" + Array.from({ length: n }, (_, f) => '    - "' + frame(f, "tab") + '"').join("\n");
			if (st.fmt === "mm") return Array.from({ length: n }, (_, f) => frame(f, "mm")).join("\n");
			if (st.fmt === "legacy") return Array.from({ length: n }, (_, f) => frame(f, "legacy")).join("\n");
			if (st.fmt === "static") return "<gradient:" + st.stops.join(":") + ">" + (st.bold ? "<bold>" : "") + st.text + "</gradient>";
			return "https://velo-client.com/v2/gradient?" + new URLSearchParams({ text: st.text, colors: st.stops.map((s) => s.slice(1)).join(","), mode: st.mode, frames: st.frames, format: "tab", bold: st.bold });
		};
		const draw = () => {
			$("#stops").replaceChildren(...st.stops.map((hex, i) => el("label", { class: "lib-box", style: { display: "flex", alignItems: "center", gap: "8px", padding: "6px 10px 6px 6px" } },
				el("input", { type: "color", value: hex, "aria-label": "Colour " + (i + 1), oninput: (e) => { st.stops[i] = e.target.value; st.pick = e.target.value; draw(); } }),
				el("span", { style: { font: "500 13px var(--mono)" } }, hex),
				st.stops.length > 2 ? el("button", { type: "button", class: "lib-btn sm", "aria-label": "Remove colour", onclick: () => { st.stops.splice(i, 1); draw(); } }, "×") : null)),
				st.stops.length < 8 ? el("button", { type: "button", class: "lib-btn", onclick: () => { st.stops.push("#5555ff"); draw(); } }, "+ colour") : null);
			pills($("#modes"), [["wave", "Wave"], ["shift", "Colour shift"], ["pulse", "Pulse"], ["static", "Static gradient"]], st.mode, (k) => { st.mode = k; st.frame = 0; draw(); });
			pills($("#fmts"), [["tab", "TAB plugin"], ["mm", "MiniMessage frames"], ["legacy", "Legacy §x"], ["static", "Static"], ["api", "API URL"]], st.fmt, (k) => { st.fmt = k; draw(); });
			$("#out").textContent = exportText();
			$("#intv").textContent = st.interval + " ms";
			$("#frv").textContent = String(st.frames);
			const rgb = hx(st.pick);
			$("#pick-swatch").style.background = st.pick;
			$("#pick-hex").textContent = st.pick;
			$("#pick-rgb").textContent = rgb.join(", ");
			$("#pick-mm").textContent = "<" + st.pick + ">";
			$("#pick-legacy").textContent = "&x" + st.pick.slice(1).split("").map((c) => "&" + c).join("");
			$("#picker").value = st.pick;
			paint();
		};
		$("#text").value = st.text;
		$("#text").addEventListener("input", (e) => { st.text = e.target.value.slice(0, 64) || " "; draw(); });
		$("#speed").addEventListener("input", (e) => { st.interval = +e.target.value; draw(); loop(); });
		$("#nframes").addEventListener("input", (e) => { st.frames = +e.target.value; st.frame = 0; draw(); });
		$("#bold").addEventListener("change", (e) => { st.bold = e.target.checked; draw(); });
		$("#picker").addEventListener("input", (e) => { st.pick = e.target.value; draw(); });
		$("#copy").addEventListener("click", () => copy($("#out").textContent));
		$$("#pick-box [data-copy]").forEach((b) => b.addEventListener("click", () => copy($("#" + b.dataset.copy).textContent)));
		const VAN = [["black", "0", "#000000"], ["dark_blue", "1", "#0000AA"], ["dark_green", "2", "#00AA00"], ["dark_aqua", "3", "#00AAAA"], ["dark_red", "4", "#AA0000"], ["dark_purple", "5", "#AA00AA"],
			["gold", "6", "#FFAA00"], ["gray", "7", "#AAAAAA"], ["dark_gray", "8", "#555555"], ["blue", "9", "#5555FF"], ["green", "a", "#55FF55"], ["aqua", "b", "#55FFFF"], ["red", "c", "#FF5555"],
			["light_purple", "d", "#FF55FF"], ["yellow", "e", "#FFFF55"], ["white", "f", "#FFFFFF"]];
		$("#vanilla").replaceChildren(...VAN.map(([n, c, h]) => el("button", { type: "button", title: n, "aria-label": n, style: { background: h, color: hx(h)[0] * .3 + hx(h)[1] * .59 + hx(h)[2] * .11 < 140 ? "#fff" : "#000" },
			onclick: () => { st.pick = h.toLowerCase(); draw(); } }, "§" + c)));
		draw();
		loop();
		presets("tab", {
			current: () => ({ text: st.text, stops: st.stops, mode: st.mode, interval: st.interval, frames: st.frames, bold: st.bold }),
			apply: (d) => { Object.assign(st, { text: String(d.text || "Velo").slice(0, 64), stops: (d.stops || []).filter((s) => /^#[0-9a-f]{6}$/i.test(s)).slice(0, 8), mode: d.mode || "wave",
				interval: Math.max(50, Math.min(500, +d.interval || 120)), frames: Math.max(6, Math.min(40, +d.frames || 20)), bold: !!d.bold, frame: 0 });
				if (st.stops.length < 2) st.stops = ["#ff6b73", "#f4b82e"];
				$("#text").value = st.text; $("#speed").value = st.interval; $("#nframes").value = st.frames; $("#bold").checked = st.bold; draw(); },
			preview: (pv, d) => { const s = (d.stops || ["#fff", "#fff"]).filter((x) => /^#[0-9a-f]{6}$/i.test(x)); const t = String(d.text || "");
				pv.append(...[...t].map((ch, i) => el("span", { style: { color: multi(s.length > 1 ? s : ["#ffffff", "#ffffff"], t.length > 1 ? i / (t.length - 1) : 0), fontWeight: "800" } }, ch))); },
		});
	};

	tools.banner = () => {
		const st = { address: qs.get("address") || "", size: "728x90", theme: "dark", c1: "", c2: "", accent: "", angle: 0, texture: "", pattern: "none", glow: true, radius: 12,
			title: "", font: "minecraft", lines: "", icon: "server", players: true, ping: true, version: false, showAddress: true, layout: "left", animate: false, frames: 16 };
		const img = $("#preview");
		const query = () => {
			const p = new URLSearchParams();
			p.set("size", st.size);
			p.set("theme", st.theme);
			for (const k of ["c1", "c2", "accent"]) if (st[k]) p.set(k, st[k].replace("#", ""));
			if (st.angle) p.set("angle", st.angle);
			if (st.texture) p.set("texture", st.texture);
			if (st.pattern !== "none") p.set("pattern", st.pattern);
			if (!st.glow) p.set("glow", "false");
			if (st.radius !== 12) p.set("radius", st.radius);
			if (st.title) p.set("title", st.title);
			if (st.font !== "minecraft") p.set("font", st.font);
			if (st.lines) p.set("lines", st.lines);
			if (st.icon !== "server") p.set("icon", st.icon);
			if (!st.players) p.set("players", "false");
			if (!st.ping) p.set("ping", "false");
			if (st.version) p.set("version", "true");
			if (!st.showAddress) p.set("address", "false");
			if (st.layout !== "left") p.set("layout", st.layout);
			if (st.animate) { p.set("animate", "true"); p.set("frames", st.frames); }
			return p.toString();
		};
		const url = () => "/v2/banner/" + (st.address ? encodeURIComponent(st.address) : "preview") + "." + (st.animate ? "gif" : "png") + "?" + query();
		const refresh = debounce(() => {
			$("#err").textContent = "";
			img.src = url();
			const full = "https://velo-client.com" + url();
			$("#codes").textContent = `URL:\n${full}\n\nHTML:\n<a href="https://velo-client.com/library/server?address=${st.address}"><img src="${full}" alt="${st.address || "server"}"></a>\n\nBBCode:\n[url=https://velo-client.com/library/server?address=${st.address}][img]${full}[/img][/url]\n\nMarkdown:\n[![${st.address || "server"}](${full})](https://velo-client.com/library/server?address=${st.address})`;
		}, 350);
		img.addEventListener("error", () => { $("#err").textContent = "Couldn't draw that banner - check the address and options."; });
		const bind = (id, key, kind = "value", transform = (v) => v) => {
			const n = $("#" + id);
			if (kind === "checked") n.checked = st[key]; else n.value = st[key];
			n.addEventListener(kind === "checked" ? "change" : "input", () => { st[key] = transform(kind === "checked" ? n.checked : n.value); draw(); refresh(); });
		};
		const draw = () => {
			pills($("#sizes"), [["468x60", "468×60"], ["600x100", "600×100"], ["728x90", "728×90"], ["1000x200", "1000×200"], ["1200x300", "1200×300 Discord"], ["1920x480", "1920×480"]], st.size, (k) => { st.size = k; draw(); refresh(); });
			pills($("#themes"), ["dark", "velo", "neon", "sunset", "ocean", "forest", "gold", "mono"].map((t) => [t, t[0].toUpperCase() + t.slice(1)]), st.theme, (k) => { st.theme = k; st.c1 = st.c2 = st.accent = ""; ["c1", "c2", "accent"].forEach((i) => { $("#" + i).value = "#000000"; }); draw(); refresh(); });
			pills($("#patterns"), [["none", "None"], ["grid", "Grid"], ["dots", "Dots"], ["stripes", "Stripes"], ["pixels", "Pixels"]], st.pattern, (k) => { st.pattern = k; draw(); refresh(); });
			pills($("#fonts"), [["minecraft", "Minecraft"], ["inter", "Inter"]], st.font, (k) => { st.font = k; draw(); refresh(); });
			pills($("#layouts"), [["left", "Left"], ["center", "Centered"]], st.layout, (k) => { st.layout = k; draw(); refresh(); });
			$("#anglev").textContent = st.angle + "°";
			$("#radiusv").textContent = st.radius + " px";
		};
		bind("address", "address", "value", (v) => v.trim());
		bind("title", "title");
		bind("lines", "lines");
		bind("texture", "texture", "value", (v) => v.trim().toLowerCase());
		bind("icon", "icon", "value", (v) => v.trim().toLowerCase() || "server");
		bind("angle", "angle", "value", Number);
		bind("radius", "radius", "value", Number);
		for (const k of ["c1", "c2", "accent"]) $("#" + k).addEventListener("input", (e) => { st[k] = e.target.value; refresh(); });
		for (const [id, key] of [["glow", "glow"], ["players", "players"], ["ping", "ping"], ["version", "version"], ["showaddr", "showAddress"], ["animate", "animate"]]) bind(id, key, "checked");
		$$("[data-tex]").forEach((b) => b.addEventListener("click", () => { st.texture = b.dataset.tex; $("#texture").value = st.texture; refresh(); }));
		$("#copy").addEventListener("click", () => copy("https://velo-client.com" + url(), "Banner URL copied"));
		draw();
		refresh();
		presets("banner", {
			current: () => { const q = new URLSearchParams(query()); return { query: q.toString() }; },
			apply: (d) => {
				const p = new URLSearchParams(String(d.query || ""));
				st.size = p.get("size") || "728x90"; st.theme = p.get("theme") || "dark"; st.c1 = p.get("c1") ? "#" + p.get("c1") : ""; st.c2 = p.get("c2") ? "#" + p.get("c2") : "";
				st.accent = p.get("accent") ? "#" + p.get("accent") : ""; st.angle = +(p.get("angle") || 0); st.texture = p.get("texture") || ""; st.pattern = p.get("pattern") || "none";
				st.glow = p.get("glow") !== "false"; st.radius = +(p.get("radius") || 12); st.title = p.get("title") || ""; st.font = p.get("font") || "minecraft"; st.lines = p.get("lines") || "";
				st.icon = p.get("icon") || "server"; st.layout = p.get("layout") || "left"; st.animate = p.get("animate") === "true";
				for (const [id, key] of [["title", "title"], ["lines", "lines"], ["texture", "texture"], ["icon", "icon"], ["angle", "angle"], ["radius", "radius"]]) $("#" + id).value = st[key];
				$("#glow").checked = st.glow; $("#animate").checked = st.animate;
				draw(); refresh();
			},
			preview: (pv, d) => pv.append(el("img", { src: "/v2/banner/preview.png?" + String(d.query || ""), alt: "", loading: "lazy" })),
		});
	};

	tools.mcbanner = async () => {
		const st = { base: "white", layers: [{ p: "stripe_top", c: "red" }, { p: "circle", c: "yellow" }, { p: "border", c: "black" }], sel: 0, pick: "black" };
		let meta;
		try { meta = await api("mcbanner/patterns"); } catch (e) { $("#err").textContent = e.message; return; }
		const tint = {};
		meta.dyes.forEach((d, i) => { tint[d] = meta.tints[i]; });
		const masks = {};
		const loadMask = (id) => masks[id] || (masks[id] = new Promise((res) => { const im = new Image(); im.onload = () => res(im); im.onerror = () => res(null); im.src = "/v2/mcbanner/pattern/" + id + ".png"; }));
		const pixels = async (id) => {
			const im = await loadMask(id);
			if (!im) return null;
			const c = document.createElement("canvas");
			c.width = 20; c.height = 40;
			const g = c.getContext("2d");
			g.imageSmoothingEnabled = false;
			g.drawImage(im, 0, 0, 20, 40);
			return g.getImageData(0, 0, 20, 40).data;
		};
		const cache = {};
		const px = async (id) => cache[id] || (cache[id] = await pixels(id));
		const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
		/** Same maths as the API: tinted masks over the base, then the cloth shading. */
		async function draw(canvas, base, layers, scale) {
			const out = new Uint8ClampedArray(20 * 40 * 4);
			const paint = (mask, hex, opaque) => {
				if (!mask) return;
				const [tr, tg, tb] = rgb(hex);
				for (let i = 0; i < 800; i++) {
					const a = opaque ? 1 : mask[i * 4 + 3] / 255;
					if (a <= 0) continue;
					const l = mask[i * 4] / 255;
					out[i * 4] += (tr * l - out[i * 4]) * a;
					out[i * 4 + 1] += (tg * l - out[i * 4 + 1]) * a;
					out[i * 4 + 2] += (tb * l - out[i * 4 + 2]) * a;
					out[i * 4 + 3] = 255;
				}
			};
			paint(await px("base"), tint[base], true);
			for (const l of layers) paint(await px(l.p), tint[l.c], false);
			const cloth = await px("banner_base");
			if (cloth) for (let i = 0; i < 800; i++) { const sh = cloth[i * 4] / 255; out[i * 4] *= sh; out[i * 4 + 1] *= sh; out[i * 4 + 2] *= sh; }
			const small = document.createElement("canvas");
			small.width = 20; small.height = 40;
			small.getContext("2d").putImageData(new ImageData(out, 20, 40), 0, 0);
			canvas.width = 20 * scale; canvas.height = 40 * scale;
			const g = canvas.getContext("2d");
			g.imageSmoothingEnabled = false;
			g.drawImage(small, 0, 0, 20 * scale, 40 * scale);
		}
		const spec = () => st.layers.map((l) => l.p + "." + l.c).join(",");
		const swatch = (d, on, click, label) => el("button", { type: "button", title: d.replace(/_/g, " "), "aria-label": label || d.replace(/_/g, " "), "aria-pressed": on ? "true" : "false", onclick: click,
			style: { width: "34px", height: "34px", borderRadius: "8px", background: tint[d], border: on ? "2px solid #fff" : "1px solid rgba(255,255,255,.15)", cursor: "pointer", boxShadow: on ? "0 0 0 2px var(--red)" : "" } });
		async function render() {
			await draw($("#big"), st.base, st.layers, 10);
			$("#bases").replaceChildren(...meta.dyes.map((d) => swatch(d, d === st.base, () => { st.base = d; render(); })));
			$("#colors").replaceChildren(...meta.dyes.map((d) => swatch(d, d === st.pick, () => { st.pick = d; if (st.layers[st.sel]) st.layers[st.sel].c = d; render(); })));
			// layer list
			$("#layers").replaceChildren(...st.layers.map((l, i) => {
				const thumb = el("canvas", { class: "lib-px", style: { width: "20px", height: "40px", borderRadius: "2px" } });
				draw(thumb, "white", [l], 1).then(() => { thumb.style.width = "20px"; });
				return el("div", { class: "lib-box", style: { display: "flex", alignItems: "center", gap: "10px", padding: "8px 10px", borderColor: i === st.sel ? "var(--red)" : "", cursor: "pointer" },
					onclick: () => { st.sel = i; st.pick = l.c; render(); } },
					el("span", { class: "lib-muted", style: { width: "18px" } }, String(i + 1)), thumb,
					el("span", { style: { flexGrow: 1, fontSize: "13px" } }, l.p.replace(/_/g, " ") + " · " + l.c.replace(/_/g, " ")),
					el("button", { type: "button", class: "lib-btn sm", "aria-label": "Move up", disabled: i === 0, onclick: (e) => { e.stopPropagation(); [st.layers[i - 1], st.layers[i]] = [st.layers[i], st.layers[i - 1]]; st.sel = i - 1; render(); } }, "↑"),
					el("button", { type: "button", class: "lib-btn sm", "aria-label": "Move down", disabled: i === st.layers.length - 1, onclick: (e) => { e.stopPropagation(); [st.layers[i + 1], st.layers[i]] = [st.layers[i], st.layers[i + 1]]; st.sel = i + 1; render(); } }, "↓"),
					el("button", { type: "button", class: "lib-btn sm", "aria-label": "Remove layer", onclick: (e) => { e.stopPropagation(); st.layers.splice(i, 1); st.sel = Math.max(0, Math.min(st.sel, st.layers.length - 1)); render(); } }, "×"));
			}));
			$("#count").textContent = st.layers.length + " / 16 layers" + (st.layers.length > 6 ? " · more than 6 needs commands (a loom stops at 6)" : "");
			$("#count").style.color = st.layers.length > 6 ? "var(--gold)" : "";
			// commands
			const give = "/give @p minecraft:" + st.base + "_banner[banner_patterns=[" + st.layers.map((l) => `{pattern:"${l.p}",color:"${l.c}"}`).join(",") + "]] 1";
			const legacyOk = st.layers.every((l) => meta.patterns.find((x) => x.id === l.p).legacy);
			const give2 = legacyOk ? "/give @p minecraft:" + st.base + "_banner{BlockEntityTag:{Patterns:[" + st.layers.map((l) => `{Pattern:"${meta.patterns.find((x) => x.id === l.p).legacy}",Color:${meta.dyes.indexOf(l.c)}}`).join(",") + "]}} 1" : "(uses patterns from 1.21 - no older format)";
			const items = [...new Set(st.layers.map((l) => meta.patterns.find((x) => x.id === l.p).item).filter(Boolean))];
			$("#give").textContent = give;
			$("#give2").textContent = give2;
			$("#items-needed").textContent = items.length ? "Loom: you also need " + items.map((x) => x.replace(/_/g, " ")).join(", ") + "." : "Every layer works in a plain loom with dyes.";
			$("#apiurl").textContent = "https://velo-client.com/v2/mcbanner.png?base=" + st.base + "&layers=" + spec() + "&scale=8";
		}
		// pattern grid: each pattern previewed in the chosen colour on the current base
		async function grid() {
			$("#patterns").replaceChildren(...meta.patterns.map((p) => {
				const c = el("canvas", { class: "lib-px", style: { width: "40px", height: "80px" } });
				draw(c, st.base === st.pick ? (st.pick === "white" ? "black" : "white") : st.base, [{ p: p.id, c: st.pick }], 2);
				return el("button", { type: "button", class: "lib-box", title: p.id.replace(/_/g, " ") + (p.item ? " (needs " + p.item.replace(/_/g, " ") + ")" : ""),
					style: { padding: "6px", cursor: "pointer", display: "flex", flexDirection: "column", alignItems: "center", gap: "4px" },
					onclick: () => { if (st.layers.length >= 16) { toast("16 layers is the most a banner can hold"); return; } st.layers.push({ p: p.id, c: st.pick }); st.sel = st.layers.length - 1; render(); } },
					c, el("span", { style: { fontSize: "10px", color: "var(--text-3)", textAlign: "center", lineHeight: 1.2 } }, p.id.replace(/_/g, " ")));
			}));
		}
		$("#regrid").addEventListener("click", grid);
		$("#random").addEventListener("click", () => {
			const pick = (a) => a[Math.floor(Math.random() * a.length)];
			st.base = pick(meta.dyes);
			st.layers = Array.from({ length: 3 + Math.floor(Math.random() * 4) }, () => ({ p: pick(meta.patterns).id, c: pick(meta.dyes) }));
			st.sel = 0;
			render();
		});
		$("#clear").addEventListener("click", () => { st.layers = []; st.sel = 0; render(); });
		$("#copy").addEventListener("click", () => copy($("#give").textContent, "/give copied"));
		$("#copy2").addEventListener("click", () => copy($("#give2").textContent, "/give copied"));
		$("#png").addEventListener("click", () => { const a = el("a", { href: $("#big").toDataURL("image/png"), download: "banner.png" }); a.click(); });
		$("#colors").addEventListener("click", () => setTimeout(grid, 0));
		const hash = new URLSearchParams(location.hash.slice(1));
		if (hash.get("base") && meta.dyes.includes(hash.get("base"))) {
			st.base = hash.get("base");
			st.layers = (hash.get("layers") || "").split(",").filter(Boolean).map((x) => { const [p, c] = x.split("."); return { p, c }; }).filter((l) => meta.dyes.includes(l.c) && meta.patterns.some((x) => x.id === l.p)).slice(0, 16);
		}
		$("#share").addEventListener("click", () => copy(location.origin + location.pathname + "#base=" + st.base + "&layers=" + spec(), "Link copied"));
		await render();
		grid();
		presets("mcbanner", {
			current: () => ({ base: st.base, layers: spec() }),
			apply: (d) => {
				if (!meta.dyes.includes(d.base)) return;
				st.base = d.base;
				st.layers = String(d.layers || "").split(",").filter(Boolean).map((x) => { const [p, c] = x.split("."); return { p, c }; }).filter((l) => meta.dyes.includes(l.c) && meta.patterns.some((x) => x.id === l.p)).slice(0, 16);
				st.sel = 0;
				render();
			},
			preview: (pv, d) => pv.append(el("img", { src: "/v2/mcbanner.png?base=" + encodeURIComponent(d.base) + "&layers=" + encodeURIComponent(d.layers || "") + "&scale=3", alt: "", class: "lib-px", loading: "lazy" })),
		});
	};

	tools.servers = async () => {
		const list = $("#list");
		try {
			const r = await api("servers");
			$("#count").textContent = r.servers.length + " servers";
			list.replaceChildren(...r.servers.map((sv) => el("article", { class: "lib-box", style: { display: "flex", flexDirection: "column" } },
				el("img", { src: "/v2/servers/" + sv.id + "/banner.png", alt: "", loading: "lazy", style: { width: "100%", aspectRatio: "16 / 6", objectFit: "cover", background: "var(--surface)" } }),
				el("div", { class: "lib-pad", style: { display: "flex", gap: "12px", alignItems: "center" } },
					el("img", { src: "/v2/servers/" + sv.id + "/logo.png", alt: "", style: { width: "48px", height: "48px", borderRadius: "10px", marginTop: "-34px", border: "3px solid var(--bg-2)", background: "var(--bg-2)" } }),
					el("div", { style: { minWidth: 0, flexGrow: 1 } }, el("h3", {}, sv.name), el("div", { class: "lib-muted" }, sv.primaryAddress || sv.addresses[0]))),
				sv.description ? el("p", { class: "lib-muted", style: { margin: "0 20px 12px", fontSize: "14px" } }, sv.description) : null,
				el("div", { style: { display: "flex", gap: "6px", flexWrap: "wrap", padding: "0 20px 18px" } },
					el("a", { class: "lib-btn sm", href: "/library/server?address=" + encodeURIComponent(sv.primaryAddress || sv.addresses[0].replace("*.", "")) }, "Status"),
					sv.website ? el("a", { class: "lib-btn sm", href: sv.website, rel: "noopener nofollow", target: "_blank" }, "Website") : null,
					sv.discord ? el("a", { class: "lib-btn sm", href: sv.discord, rel: "noopener nofollow", target: "_blank" }, "Discord") : null))));
		} catch (e) { list.replaceChildren(el("div", { class: "lib-empty" }, e.message)); }

		// ---- add your server
		const step = (n) => $$("[data-step]").forEach((s) => { s.hidden = +s.dataset.step !== n; });
		const need = $("#need-signin");
		if (!signedIn()) { need.hidden = false; need.append(signInLink()); $("#add").hidden = true; return; }
		step(1);
		let address = "", logo = null, banner = null;
		$("#s1").addEventListener("submit", async (e) => {
			e.preventDefault();
			address = $("#addr").value.trim().toLowerCase();
			try {
				const r = await api("servers/submit/start", { json: { address } });
				$("#code").textContent = r.code;
				$("#addr2").textContent = r.address;
				step(2);
			} catch (err) { toast(err.message); }
		});
		$("#copycode").addEventListener("click", () => copy($("#code").textContent, "Code copied"));
		$("#check").addEventListener("click", async () => {
			$("#check").disabled = true;
			$("#s2err").textContent = "Pinging " + address + "…";
			try {
				const r = await api("servers/submit/verify", { json: { address } });
				if (r.verified) {
					$("#s2err").textContent = "";
					$("#f-id").value = address.replace(/^(play|mc|join|hub)\./, "").split(".")[0].replace(/[^a-z0-9-]/g, "");
					step(3);
				} else $("#s2err").textContent = "The code isn't in the MOTD yet. Save it, wait a moment, check again.";
			} catch (err) { $("#s2err").textContent = err.message; }
			$("#check").disabled = false;
		});
		const readImage = (input, kind) => new Promise((resolve) => {
			const f = input.files[0];
			const out = $("#" + kind + "-info");
			if (!f) { resolve(null); return; }
			if (f.type !== "image/png") { out.textContent = "Must be a PNG"; resolve(null); return; }
			const img = new Image();
			img.onload = () => {
				const w = img.naturalWidth, h = img.naturalHeight, ratio = w / h;
				const ok = kind === "logo" ? w === h && w >= 128 && w <= 1024 && f.size <= 512 * 1024 : ratio >= 1.7 && ratio <= 5 && w >= 640 && w <= 2560 && f.size <= 2 * 1024 * 1024;
				out.textContent = w + "×" + h + " · " + Math.round(f.size / 1024) + " KB" + (ok ? " ✓" : kind === "logo" ? " - needs to be square, 128-1024 px, under 512 KB" : " - needs 16:9 to 5:1, 640-2560 px wide, under 2 MB");
				out.style.color = ok ? "var(--good)" : "var(--red-soft)";
				$("#" + kind + "-preview").src = img.src;
				if (!ok) { resolve(null); return; }
				const rd = new FileReader();
				rd.onload = () => resolve(rd.result);
				rd.readAsDataURL(f);
			};
			img.src = URL.createObjectURL(f);
		});
		$("#f-logo").addEventListener("change", async (e) => { logo = await readImage(e.target, "logo"); });
		$("#f-banner").addEventListener("change", async (e) => { banner = await readImage(e.target, "banner"); });
		$("#s3").addEventListener("submit", async (e) => {
			e.preventDefault();
			if (!logo || !banner) { toast("Add a logo and a banner first"); return; }
			const f = (id) => $("#f-" + id).value.trim();
			const types = $$("#f-types input:checked").map((x) => x.value).join(",");
			const regions = $$("#f-regions input:checked").map((x) => x.value).join(",");
			$("#send").disabled = true;
			$("#send").textContent = "Sending…";
			try {
				const r = await api("servers/submit", { json: { address, id: f("id"), name: f("name"), description: f("desc"), addresses: f("addresses"), website: f("website"),
					discord: f("discord"), store: f("store"), primaryColor: $("#f-color").value, version: f("version"), gameTypes: types, regions,
					presenceDetails: f("pdetails"), presenceState: f("pstate"), logo, banner } });
				$("#done-msg").textContent = r.message;
				const link = $("#done-pr");
				if (r.pr) { link.href = r.pr; link.hidden = false; }
				step(4);
			} catch (err) { toast(err.message); }
			$("#send").disabled = false;
			$("#send").textContent = "Send";
		});
		const preview = () => { $("#pv-name").textContent = $("#f-name").value || "Your server"; $("#pv-details").textContent = ($("#f-pdetails").value || "Playing on %name%").replace("%name%", $("#f-name").value || "Your server"); $("#pv-state").textContent = $("#f-pstate").value || ""; };
		["f-name", "f-pdetails", "f-pstate"].forEach((i) => $("#" + i).addEventListener("input", preview));
		$("#f-logo").addEventListener("change", () => setTimeout(() => { $("#pv-logo").src = $("#logo-preview").src; }, 300));
		preview();
	};

	tools.hud = () => {
		const KINDS = {
			crosshair: { label: "Crosshair", sizes: [9, 11, 15, 21, 31], size: 15, states: ["idle"], mirror: "both" },
			heart: { label: "Hearts", sizes: [9, 18], size: 9, states: ["full", "half", "empty"], mirror: "x" },
			food: { label: "Hunger", sizes: [9, 18], size: 9, states: ["full", "half", "empty"], mirror: "none" },
			armor: { label: "Armor", sizes: [9, 18], size: 9, states: ["full", "half", "empty"], mirror: "x" },
		};
		const VANILLA = { crosshair: { idle: ["crosshair"] }, heart: { full: ["heart/container", "heart/full"], half: ["heart/container", "heart/half"], empty: ["heart/container"] },
			food: { full: ["food_full"], half: ["food_half"], empty: ["food_empty"] }, armor: { full: ["armor_full"], half: ["armor_half"], empty: ["armor_empty"] } };
		const st = { kind: qs.get("kind") && KINDS[qs.get("kind")] ? qs.get("kind") : "crosshair", state: "idle", tool: "pen", color: "#ffffff", alpha: 255, mirror: "both", grid: true, name: "My crosshair" };
		const art = {}; // kind -> state -> {size, px: Uint32Array (RGBA packed)}
		const blank = (size) => ({ size, px: new Uint32Array(size * size) });
		const layer = () => {
			const k = KINDS[st.kind];
			art[st.kind] = art[st.kind] || {};
			if (!art[st.kind][st.state]) art[st.kind][st.state] = blank(k.size);
			return art[st.kind][st.state];
		};
		const pack = (hex, a) => { const [r, g, b] = rgb(hex); return ((a & 255) << 24 | b << 16 | g << 8 | r) >>> 0; };
		const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
		const unpack = (v) => [v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255];
		const editor = $("#editor"), g = editor.getContext("2d");
		let undo = [];
		function toCanvas(l, scale = 1) {
			const c = document.createElement("canvas");
			c.width = l.size * scale; c.height = l.size * scale;
			const x = c.getContext("2d");
			const img = x.createImageData(l.size, l.size);
			new Uint32Array(img.data.buffer).set(l.px);
			if (scale === 1) { x.putImageData(img, 0, 0); return c; }
			const t = document.createElement("canvas"); t.width = l.size; t.height = l.size; t.getContext("2d").putImageData(img, 0, 0);
			x.imageSmoothingEnabled = false; x.drawImage(t, 0, 0, c.width, c.height);
			return c;
		}
		function paint() {
			const l = layer(), n = l.size, cell = Math.floor(Math.min(480, editor.clientWidth || 480) / n);
			editor.width = n * cell; editor.height = n * cell;
			for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
				g.fillStyle = (x + y) % 2 ? "#17171d" : "#1e1e26";
				g.fillRect(x * cell, y * cell, cell, cell);
				const [r, gg, b, a] = unpack(l.px[y * n + x]);
				if (a) { g.fillStyle = `rgba(${r},${gg},${b},${a / 255})`; g.fillRect(x * cell, y * cell, cell, cell); }
			}
			if (st.grid) {
				g.strokeStyle = "rgba(255,255,255,.06)"; g.lineWidth = 1;
				for (let i = 0; i <= n; i++) { g.beginPath(); g.moveTo(i * cell + .5, 0); g.lineTo(i * cell + .5, n * cell); g.stroke(); g.beginPath(); g.moveTo(0, i * cell + .5); g.lineTo(n * cell, i * cell + .5); g.stroke(); }
				if (st.kind === "crosshair") { g.strokeStyle = "rgba(224,38,46,.35)"; g.strokeRect(Math.floor(n / 2) * cell + .5, Math.floor(n / 2) * cell + .5, cell, cell); }
			}
			preview();
		}
		function preview() {
			const pv = $("#preview"), x = pv.getContext("2d");
			pv.width = pv.clientWidth * (devicePixelRatio || 1); pv.height = pv.clientHeight * (devicePixelRatio || 1);
			x.imageSmoothingEnabled = false;
			const scale = Math.max(2, Math.round(pv.height / 180));
			if (st.kind === "crosshair") {
				const c = toCanvas(layer(), scale);
				x.drawImage(c, Math.round((pv.width - c.width) / 2), Math.round((pv.height - c.height) / 2));
				return;
			}
			const k = art[st.kind] || {}, unit = 9 * scale;
			const pick = (stt) => k[stt] && k[stt].px.some((v) => v >>> 24) ? k[stt] : null;
			const full = pick("full"), half = pick("half"), empty = pick("empty");
			const values = ["full", "full", "full", "full", "full", "full", "full", "half", "empty", "empty"];
			values.forEach((v, i) => {
				let l = v === "full" ? full : v === "half" ? half || full : empty || full;
				if (!l) return;
				const c = toCanvas(l, 1), xx = Math.round(pv.width / 2 - 5 * (unit - scale)) + i * (unit - scale), yy = Math.round(pv.height / 2 - unit / 2);
				if (v === "empty" && !empty) { x.globalAlpha = .3; }
				if (v === "half" && !half) {
					x.drawImage(c, 0, 0, Math.ceil(l.size * 5 / 9), l.size, xx, yy, Math.ceil(unit * 5 / 9), unit);
				} else x.drawImage(c, xx, yy, unit, unit);
				x.globalAlpha = 1;
			});
		}
		function set(x, y, v) {
			const l = layer(), n = l.size;
			const pts = [[x, y]];
			if (st.mirror === "x" || st.mirror === "both") pts.push([n - 1 - x, y]);
			if (st.mirror === "y" || st.mirror === "both") pts.push([x, n - 1 - y]);
			if (st.mirror === "both") pts.push([n - 1 - x, n - 1 - y]);
			for (const [a, b] of pts) if (a >= 0 && b >= 0 && a < n && b < n) l.px[b * n + a] = v;
		}
		function fill(x, y, v) {
			const l = layer(), n = l.size, from = l.px[y * n + x];
			if (from === v) return;
			const stack = [[x, y]];
			while (stack.length) {
				const [a, b] = stack.pop();
				if (a < 0 || b < 0 || a >= n || b >= n || l.px[b * n + a] !== from) continue;
				l.px[b * n + a] = v;
				stack.push([a + 1, b], [a - 1, b], [a, b + 1], [a, b - 1]);
			}
		}
		let down = false;
		const cellAt = (e) => { const r = editor.getBoundingClientRect(), n = layer().size; return [Math.floor((e.clientX - r.left) / r.width * n), Math.floor((e.clientY - r.top) / r.height * n)]; };
		const act = (e) => {
			const [x, y] = cellAt(e);
			const l = layer();
			if (x < 0 || y < 0 || x >= l.size || y >= l.size) return;
			if (st.tool === "pick") { const [r, gg, b, a] = unpack(l.px[y * l.size + x]); st.color = "#" + [r, gg, b].map((v) => v.toString(16).padStart(2, "0")).join(""); st.alpha = a || 255; st.tool = "pen"; draw(); return; }
			if (st.tool === "fill") { fill(x, y, pack(st.color, st.alpha)); paint(); return; }
			set(x, y, st.tool === "erase" || e.buttons === 2 ? 0 : pack(st.color, st.alpha));
			paint();
		};
		editor.addEventListener("contextmenu", (e) => e.preventDefault());
		editor.addEventListener("pointerdown", (e) => { undo.push({ kind: st.kind, state: st.state, px: layer().px.slice(), size: layer().size }); if (undo.length > 60) undo.shift(); down = true; editor.setPointerCapture(e.pointerId); act(e); });
		editor.addEventListener("pointermove", (e) => { if (down && (st.tool === "pen" || st.tool === "erase")) act(e); });
		editor.addEventListener("pointerup", () => { down = false; });
		$("#undo").addEventListener("click", () => { const u = undo.pop(); if (!u) return; art[u.kind][u.state] = { size: u.size, px: u.px }; st.kind = u.kind; st.state = u.state; draw(); });
		$("#clear").addEventListener("click", () => { undo.push({ kind: st.kind, state: st.state, px: layer().px.slice(), size: layer().size }); layer().px.fill(0); paint(); });
		async function loadVanilla() {
			const spec = VANILLA[st.kind];
			for (const [state, parts] of Object.entries(spec)) {
				const imgs = await Promise.all(parts.map((p) => new Promise((res) => { const im = new Image(); im.onload = () => res(im); im.onerror = () => res(null); im.src = "/v2/sprite/" + p + ".png"; })));
				if (!imgs[0]) continue;
				const n = imgs[0].naturalWidth;
				const c = document.createElement("canvas"); c.width = n; c.height = n;
				const x = c.getContext("2d");
				imgs.forEach((im) => im && x.drawImage(im, 0, 0));
				const data = new Uint32Array(x.getImageData(0, 0, n, n).data.buffer.slice(0));
				art[st.kind] = art[st.kind] || {};
				art[st.kind][state] = { size: n, px: data };
			}
			KINDS[st.kind].size = (art[st.kind][KINDS[st.kind].states[0]] || {}).size || KINDS[st.kind].size;
			draw();
		}
		$("#vanilla").addEventListener("click", loadVanilla);
		const TEMPLATES = {
			plus: (n, put) => { const m = n >> 1; for (let i = 1; i <= Math.max(2, n >> 2); i++) { put(m + i + 0, m); put(m - i, m); put(m, m + i); put(m, m - i); } },
			dot: (n, put) => { const m = n >> 1; put(m, m); },
			circle: (n, put) => { const m = n >> 1, r = Math.max(2, n / 3); for (let a = 0; a < 360; a += 4) put(Math.round(m + r * Math.cos(a * Math.PI / 180)), Math.round(m + r * Math.sin(a * Math.PI / 180))); put(m, m); },
			x: (n, put) => { const m = n >> 1; for (let i = 1; i <= Math.max(2, n >> 2); i++) { put(m + i, m + i); put(m - i, m - i); put(m + i, m - i); put(m - i, m + i); } },
			gap: (n, put) => { const m = n >> 1; for (let i = 2; i <= Math.max(3, (n >> 1) - 1); i++) { put(m + i, m); put(m - i, m); put(m, m + i); put(m, m - i); } put(m, m); },
		};
		$$("[data-tpl]").forEach((b) => b.addEventListener("click", () => {
			const l = layer(); l.px.fill(0);
			TEMPLATES[b.dataset.tpl](l.size, (x, y) => { if (x >= 0 && y >= 0 && x < l.size && y < l.size) l.px[y * l.size + x] = pack(st.color, st.alpha); });
			paint();
		}));
		function draw() {
			const k = KINDS[st.kind];
			if (!k.states.includes(st.state)) st.state = k.states[0];
			pills($("#kinds"), Object.entries(KINDS).map(([id, v]) => [id, v.label]), st.kind, (id) => { st.kind = id; st.mirror = KINDS[id].mirror; st.name = "My " + KINDS[id].label.toLowerCase().replace(/s$/, ""); $("#name").value = st.name; draw(); setupPresets(); history.replaceState(null, "", "?kind=" + id); });
			pills($("#states"), k.states.map((x) => [x, x === "idle" ? "Crosshair" : x[0].toUpperCase() + x.slice(1)]), st.state, (x) => { st.state = x; draw(); });
			$("#states").hidden = k.states.length === 1;
			pills($("#sizes"), k.sizes.map((n) => [n, n + " px" + (n === 18 ? " (HD)" : "")]), layer().size, (n) => {
				k.size = n;
				for (const stt of k.states) { const old = (art[st.kind] || {})[stt]; if (old && old.size !== n) art[st.kind][stt] = resize(old, n); }
				draw();
			});
			pills($("#tools"), [["pen", "Pen"], ["erase", "Eraser"], ["fill", "Fill"], ["pick", "Pick colour"]], st.tool, (t) => { st.tool = t; draw(); });
			pills($("#mirrors"), [["none", "No mirror"], ["x", "Mirror ↔"], ["y", "Mirror ↕"], ["both", "Mirror both"]], st.mirror, (m) => { st.mirror = m; draw(); });
			$("#color").value = st.color;
			$("#alpha").value = st.alpha;
			$("#alphav").textContent = Math.round(st.alpha / 2.55) + "%";
			$("#tpls").hidden = st.kind !== "crosshair";
			$("#hint").textContent = st.kind === "crosshair" ? "The red square is the centre of the screen." : "Draw Full first - Half and Empty are made from it in the game if you leave them blank.";
			paint();
		}
		function resize(l, n) {
			const out = blank(n);
			for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) out.px[y * n + x] = l.px[Math.floor(y * l.size / n) * l.size + Math.floor(x * l.size / n)];
			return out;
		}
		const PALETTE = ["#ffffff", "#000000", "#e0262e", "#ff5555", "#55ff55", "#55ffff", "#ffff55", "#ff55ff", "#5555ff", "#ffaa00", "#aaaaaa", "#3b2a1a"];
		$("#palette").replaceChildren(...PALETTE.map((c) => el("button", { type: "button", "aria-label": c, style: { width: "28px", height: "28px", borderRadius: "7px", background: c, border: "1px solid rgba(255,255,255,.2)", cursor: "pointer" }, onclick: () => { st.color = c; st.tool = st.tool === "erase" ? "pen" : st.tool; draw(); } })));
		$("#color").addEventListener("input", (e) => { st.color = e.target.value; });
		$("#alpha").addEventListener("input", (e) => { st.alpha = +e.target.value; $("#alphav").textContent = Math.round(st.alpha / 2.55) + "%"; });
		$("#gridt").addEventListener("change", (e) => { st.grid = e.target.checked; paint(); });
		$("#name").value = st.name;
		$("#name").addEventListener("input", (e) => { st.name = e.target.value; });
		const design = () => {
			const k = KINDS[st.kind], images = {};
			for (const stt of k.states) {
				const l = (art[st.kind] || {})[stt];
				if (l && l.px.some((v) => v >>> 24)) images[stt] = toCanvas(l).toDataURL("image/png");
			}
			return { type: st.kind, name: st.name, images };
		};
		$("#add").addEventListener("click", async () => {
			const d = design();
			if (!Object.keys(d.images).length) { toast("Draw something first"); return; }
			try {
				const r = await api("designs", { json: d, auth: false });
				$("#cmd").textContent = r.command;
				$("#cmd-box").hidden = false;
				copy(r.command, "Command copied - paste it in the game chat");
			} catch (e) { toast(e.message); }
		});
		$("#copycmd").addEventListener("click", () => copy($("#cmd").textContent, "Command copied"));
		$("#png").addEventListener("click", () => { const a = el("a", { href: toCanvas(layer(), 1).toDataURL("image/png"), download: st.kind + "-" + st.state + ".png" }); a.click(); });
		let presetCtl = null;
		function setupPresets() {
			presetCtl = presets(st.kind === "crosshair" ? "crosshair" : "hud", {
				current: () => design(),
				apply: (d) => {
					if (!d || !d.images || !KINDS[d.type]) return;
					st.kind = d.type;
					for (const [stt, url] of Object.entries(d.images)) {
						const im = new Image();
						im.onload = () => {
							const n = im.naturalWidth, c = document.createElement("canvas"); c.width = n; c.height = n;
							const x = c.getContext("2d"); x.drawImage(im, 0, 0);
							art[d.type] = art[d.type] || {};
							art[d.type][stt] = { size: n, px: new Uint32Array(x.getImageData(0, 0, n, n).data.buffer.slice(0)) };
							KINDS[d.type].size = n;
							draw();
						};
						im.src = url;
					}
					st.name = d.name || st.name;
					$("#name").value = st.name;
				},
				preview: (pv, d) => {
					const first = d && d.images && (d.images.idle || d.images.full);
					if (first) pv.append(el("img", { src: first, alt: "", class: "lib-px", style: { width: "64px", height: "64px" } }));
					if (d && d.type && d.type !== "crosshair") pv.append(el("span", { class: "lib-tag", style: { marginLeft: "10px" } }, KINDS[d.type] ? KINDS[d.type].label : d.type));
				},
			});
		}
		addEventListener("resize", debounce(paint, 100));
		if (st.kind !== "crosshair") { st.mirror = KINDS[st.kind].mirror; loadVanilla(); }
		else { TEMPLATES.gap(15, (x, y) => { layer().px[y * 15 + x] = pack("#ffffff", 255); }); }
		draw();
		setupPresets();
	};

	tools.schematics = () => {
		// ---------- reading schematic files in the browser
		const gunzip = async (buf) => new Uint8Array(await new Response(new Blob([buf]).stream().pipeThrough(new DecompressionStream("gzip"))).arrayBuffer());
		function veloschem(b) {
			const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
			let p = 0;
			const u16 = () => { const v = dv.getUint16(p); p += 2; return v; };
			const i32 = () => { const v = dv.getInt32(p); p += 4; return v; };
			const str = () => { const n = i32(); if (n < 0 || n > 1 << 24) throw new Error("damaged file"); const t = new TextDecoder().decode(b.subarray(p, p + n)); p += n; return t; };
			const magicLen = u16();
			if (new TextDecoder().decode(b.subarray(p, p + magicLen)) !== "VELOSCHEM") throw new Error("not a .veloschem file");
			p += magicLen;
			i32(); // version
			const name = str();
			p += 8; // saved at
			const sx = i32(), sy = i32(), sz = i32();
			if (sx <= 0 || sy <= 0 || sz <= 0 || sx * sy * sz > 50_000_000) throw new Error("schematic too big to preview");
			const palette = [];
			for (let i = i32(); i > 0; i--) palette.push(str());
			const states = new Int32Array(sx * sy * sz);
			for (let i = 0; i < states.length; i++) states[i] = i32();
			return { name, sx, sy, sz, palette, at: (x, y, z) => palette[states[(x * sy + y) * sz + z]] };
		}
		function nbt(b) {
			const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
			let p = 0;
			const name = () => { const n = dv.getUint16(p); p += 2; const t = new TextDecoder().decode(b.subarray(p, p + n)); p += n; return t; };
			const read = (t) => {
				switch (t) {
					case 1: p += 1; return dv.getInt8(p - 1);
					case 2: p += 2; return dv.getInt16(p - 2);
					case 3: p += 4; return dv.getInt32(p - 4);
					case 4: p += 8; return dv.getBigInt64(p - 8);
					case 5: p += 4; return dv.getFloat32(p - 4);
					case 6: p += 8; return dv.getFloat64(p - 8);
					case 7: { const n = dv.getInt32(p); p += 4 + n; return null; }
					case 8: return name();
					case 9: { const et = dv.getUint8(p); const n = dv.getInt32(p + 1); p += 5; const out = []; for (let i = 0; i < n; i++) out.push(read(et)); return out; }
					case 10: { const o = {}; for (;;) { const tt = dv.getUint8(p++); if (tt === 0) return o; const k = name(); o[k] = read(tt); } }
					case 11: { const n = dv.getInt32(p); p += 4; const out = new Int32Array(n); for (let i = 0; i < n; i++) { out[i] = dv.getInt32(p); p += 4; } return out; }
					case 12: { const n = dv.getInt32(p); p += 4; const out = new BigInt64Array(n); for (let i = 0; i < n; i++) { out[i] = dv.getBigInt64(p); p += 8; } return out; }
					default: throw new Error("bad NBT");
				}
			};
			const t = dv.getUint8(p++);
			name();
			return read(t);
		}
		function litematic(root) {
			const regions = Object.values(root.Regions || {});
			if (!regions.length) throw new Error("no regions in that .litematic");
			// all regions into one box (by their positions)
			const parts = regions.map((r) => {
				const sx = Math.abs(r.Size.x), sy = Math.abs(r.Size.y), sz = Math.abs(r.Size.z);
				const ox = r.Position.x + Math.min(0, r.Size.x + 1), oy = r.Position.y + Math.min(0, r.Size.y + 1), oz = r.Position.z + Math.min(0, r.Size.z + 1);
				const pal = r.BlockStatePalette.map((e) => e.Name + (e.Properties ? "[" + Object.entries(e.Properties).map(([k, v]) => k + "=" + v).join(",") + "]" : ""));
				const bits = BigInt(Math.max(2, 32 - Math.clz32(pal.length - 1)));
				const longs = r.BlockStates, mask = (1n << bits) - 1n;
				const get = (i) => {
					const start = BigInt(i) * bits, li = Number(start >> 6n), off = start & 63n;
					let v = (BigInt.asUintN(64, longs[li]) >> off) & mask;
					if (off + bits > 64n) v |= (BigInt.asUintN(64, longs[li + 1]) << (64n - off)) & mask;
					return pal[Number(v)];
				};
				return { sx, sy, sz, ox, oy, oz, get };
			});
			const minX = Math.min(...parts.map((r) => r.ox)), minY = Math.min(...parts.map((r) => r.oy)), minZ = Math.min(...parts.map((r) => r.oz));
			const maxX = Math.max(...parts.map((r) => r.ox + r.sx)), maxY = Math.max(...parts.map((r) => r.oy + r.sy)), maxZ = Math.max(...parts.map((r) => r.oz + r.sz));
			const sx = maxX - minX, sy = maxY - minY, sz = maxZ - minZ;
			if (sx * sy * sz > 50_000_000) throw new Error("schematic too big to preview");
			return { name: (root.Metadata && root.Metadata.Name) || "", sx, sy, sz, at: (x, y, z) => {
				for (const r of parts) {
					const lx = x + minX - r.ox, ly = y + minY - r.oy, lz = z + minZ - r.oz;
					if (lx >= 0 && ly >= 0 && lz >= 0 && lx < r.sx && ly < r.sy && lz < r.sz) return r.get((ly * r.sz + lz) * r.sx + lx);
				}
				return "minecraft:air";
			} };
		}
		async function readSchematic(file, name) {
			const raw = new Uint8Array(file instanceof ArrayBuffer ? file : await file.arrayBuffer());
			const b = await gunzip(raw);
			return (name || file.name).toLowerCase().endsWith(".veloschem") ? veloschem(b) : litematic(nbt(b));
		}
		// ---------- material list (what you need to build it)
		const NO_ITEM = new Set(["air", "cave_air", "void_air", "structure_void", "barrier", "light", "moving_piston", "piston_head", "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway", "bubble_column", "frosted_ice"]);
		function materials(sch) {
			const counts = new Map();
			for (let x = 0; x < sch.sx; x++) for (let y = 0; y < sch.sy; y++) for (let z = 0; z < sch.sz; z++) {
				const state = String(sch.at(x, y, z) || "");
				let id = state.split("[")[0].replace("minecraft:", "");
				if (NO_ITEM.has(id)) continue;
				const props = state.includes("[") ? state.slice(state.indexOf("[") + 1, -1) : "";
				if (/\bhalf=upper\b/.test(props) && /door|tall|sunflower|lilac|rose_bush|peony|large_fern|pitcher/.test(id)) continue; // two-block things count once
				if (/\bpart=head\b/.test(props) && id.endsWith("_bed")) continue;
				let n = /\btype=double\b/.test(props) && id.endsWith("_slab") ? 2 : 1;
				const pickles = props.match(/\b(pickles|candles|eggs|flower_amount|segment_amount)=(\d)/);
				if (pickles) n = +pickles[2];
				if (id === "water" || id === "lava") { if (!/\blevel=0\b/.test(props) && props) continue; id = id + "_bucket"; }
				id = idOf(id);
				counts.set(id, (counts.get(id) || 0) + n);
			}
			return [...counts.entries()].sort((a, b) => b[1] - a[1]);
		}
		function materialList(list, box) {
			const total = list.reduce((a, [, n]) => a + n, 0);
			const fmt = (n) => { const st = Math.floor(n / 64), r = n % 64, sh = n / (64 * 27); return (st ? st + "×64" + (r ? " + " + r : "") : String(n)) + (sh >= 1 ? " · " + sh.toFixed(1) + " shulkers" : ""); };
			box.replaceChildren(
				el("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: "8px" } }, el("h3", {}, "Materials"),
					el("span", { class: "lib-muted" }, total.toLocaleString() + " items · " + list.length + " kinds")),
				el("div", { style: { display: "flex", flexDirection: "column", maxHeight: "380px", overflow: "auto", border: "1px solid var(--line)", borderRadius: "12px" } },
					...list.map(([id, n], i) => el("div", { style: { display: "flex", alignItems: "center", gap: "10px", padding: "7px 12px", borderTop: i ? "1px solid var(--line)" : "0" } },
						el("img", { src: "/v2/item/" + id + ".png?size=32", alt: "", class: "lib-px", style: { width: "24px", height: "24px" }, loading: "lazy", onerror: (e) => { e.target.style.visibility = "hidden"; } }),
						el("span", { style: { flexGrow: 1, fontSize: "13px" } }, id.replace(/_/g, " ")),
						el("span", { style: { font: "500 12px var(--mono)", color: "var(--text-2)", textAlign: "right" } }, n.toLocaleString(), el("br"), el("span", { class: "lib-muted", style: { fontSize: "11px" } }, n >= 64 ? fmt(n) : ""))))),
				el("button", { type: "button", class: "lib-btn sm", style: { alignSelf: "flex-start" }, onclick: () => copy(list.map(([id, n]) => n + " " + id.replace(/_/g, " ")).join("\n"), "Material list copied") }, "Copy list"));
		}
		const ready3d = () => window.velo && window.velo.schem3d ? Promise.resolve() : new Promise((res) => addEventListener("velo:schem3d", res, { once: true }));
		async function show3d(sch, stage, slider) {
			await ready3d();
			if (stage._viewer) stage._viewer.dispose();
			stage.replaceChildren(el("div", { class: "lib-muted", style: { padding: "20px" } }, "Building the 3D view…"));
			const v = await window.velo.schem3d(stage, sch);
			stage._viewer = v;
			if (slider) {
				slider.max = String(sch.sy - 1);
				slider.value = String(sch.sy - 1);
				slider.oninput = () => { v.setLayer(+slider.value); slider.previousElementSibling && (slider.previousElementSibling.textContent = "Layers: up to " + (+slider.value + 1) + " / " + sch.sy); };
				slider.oninput();
			}
			return v;
		}
		// ---------- isometric preview from the Library's block icons
		const AIR = new Set(["air", "cave_air", "void_air", "structure_void", "barrier", "light", "moving_piston", "piston_head"]);
		const ALIAS = { redstone_wire: "redstone", wall_torch: "torch", redstone_wall_torch: "redstone_torch", soul_wall_torch: "soul_torch", tripwire: "string",
			kelp_plant: "kelp", bubble_column: "water", cocoa: "cocoa_beans", carrots: "carrot", potatoes: "potato", beetroots: "beetroot", sweet_berry_bush: "sweet_berries",
			bamboo_sapling: "bamboo", tall_seagrass: "seagrass", big_dripleaf_stem: "big_dripleaf", cave_vines: "glow_berries", cave_vines_plant: "glow_berries", twisting_vines_plant: "twisting_vines",
			weeping_vines_plant: "weeping_vines", pumpkin_stem: "pumpkin_seeds", melon_stem: "melon_seeds", attached_pumpkin_stem: "pumpkin_seeds", attached_melon_stem: "melon_seeds" };
		const FLUID = { water: "rgba(63,118,228,.55)", lava: "rgba(217,100,21,.9)" };
		const idOf = (state) => { let id = String(state || "").split("[")[0].replace("minecraft:", ""); if (id.endsWith("_wall_sign")) id = id.replace("_wall_sign", "_sign"); if (id.endsWith("_wall_hanging_sign")) id = id.replace("_wall_hanging_sign", "_hanging_sign"); if (id.endsWith("_wall_banner")) id = id.replace("_wall_banner", "_banner"); if (id.startsWith("potted_")) id = "flower_pot"; return ALIAS[id] || id; };
		const icons = {};
		const icon = (id) => icons[id] || (icons[id] = new Promise((res) => { const im = new Image(); im.onload = () => res(im); im.onerror = () => res(null); im.src = "/v2/item/" + id + ".png?size=64"; }));
		async function renderPreview(sch, canvas) {
			const { sx, sy, sz } = sch;
			const ids = new Map();
			const solid = (x, y, z) => { if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return false; const id = idOf(sch.at(x, y, z)); return !AIR.has(id) && !FLUID[id] && !/glass|leaves|slab|stairs|fence|wall|door|trapdoor|pane|bars|rail|torch|redstone|button|pressure|carpet|sign|banner|flower|sapling|grass$|fern|vine|ladder|lever|repeater|comparator|candle|chain|rod/.test(id); };
			const blocks = [];
			for (let x = 0; x < sx; x++) for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) {
				const id = idOf(sch.at(x, y, z));
				if (AIR.has(id)) continue;
				if (solid(x + 1, y, z) && solid(x, y + 1, z) && solid(x, y, z + 1)) continue; // hidden from this side
				blocks.push([x, y, z, id]);
				ids.set(id, true);
				if (blocks.length > 120_000) break;
			}
			if (!blocks.length) throw new Error("the schematic is empty");
			blocks.sort((a, b) => (a[0] + a[1] + a[2]) - (b[0] + b[1] + b[2]) || a[1] - b[1]);
			const loaded = {};
			await Promise.all([...ids.keys()].filter((id) => !FLUID[id]).map(async (id) => { loaded[id] = await icon(id); }));
			// geometry of the Library's cube icons: half-width hx, quarter-height hy, block height s (for an icon of size 1)
			const S = 0.49, HX = S * Math.cos(Math.PI / 6), HY = S * 0.5;
			const W = canvas.width, H = canvas.height;
			const spanX = (sx + sz) * HX, spanY = (sx + sz) * HY + sy * S * 2 * 0.5 + 1;
			const size = Math.max(2, Math.min(96, Math.floor(Math.min((W - 24) / spanX, (H - 24) / (spanY + 0.5)))));
			const g = canvas.getContext("2d");
			g.clearRect(0, 0, W, H);
			g.imageSmoothingEnabled = size < 32;
			const ox = W / 2 + (sz - sx) * HX * size / 2, oy = (H - spanY * size) / 2 + sy * S * size;
			for (const [x, y, z, id] of blocks) {
				const px = ox + (x - z) * HX * size - size / 2, py = oy + (x + z) * HY * size - y * S * size - size / 2;
				if (FLUID[id]) {
					g.fillStyle = FLUID[id];
					g.beginPath(); g.moveTo(px + size / 2, py + size * .01); g.lineTo(px + size / 2 + HX * size, py + size * .26); g.lineTo(px + size / 2, py + size * .51); g.lineTo(px + size / 2 - HX * size, py + size * .26); g.fill();
					continue;
				}
				const im = loaded[id];
				if (im) g.drawImage(im, px, py, size, size);
				else { g.fillStyle = "#777"; g.fillRect(px + size * .3, py + size * .3, size * .4, size * .4); }
			}
			return blocks.length;
		}

		// ---------- the community list (velo-server /v1/schematics)
		const st = { sort: "hot", q: "", tag: "", page: 0 };
		const grid = $("#grid");
		async function v1(path, opts = {}) {
			const headers = Object.assign({}, opts.headers || {});
			if (token()) headers.Authorization = "Bearer " + token();
			if (opts.json !== undefined) { headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(opts.json); }
			const r = await fetch("/v1/" + path, { method: opts.method || (opts.body ? "POST" : "GET"), headers, body: opts.body });
			const d = await r.json().catch(() => ({}));
			if (!r.ok) throw new Error(d.error || d.message || "HTTP " + r.status);
			return d;
		}
		async function load() {
			pills($("#sorts"), [["hot", "Hot"], ["new", "New"], ["top", "Top"], ...(signedIn() ? [["mine", "Mine"]] : [])], st.sort, (k) => { st.sort = k; st.page = 0; load(); });
			try {
				const r = await v1(`schematics?sort=${st.sort}&q=${encodeURIComponent(st.q)}&tag=${encodeURIComponent(st.tag)}&page=${st.page}`);
				$("#total").textContent = r.total + " schematics";
				if (!r.items.length) { grid.replaceChildren(el("div", { class: "lib-empty", style: { gridColumn: "1 / -1" } }, "Nothing here yet - share the first one.")); return; }
				grid.replaceChildren(...r.items.map(card));
				$("#more").hidden = (r.page + 1) * r.pageSize >= r.total;
			} catch (e) { grid.replaceChildren(el("div", { class: "lib-empty", style: { gridColumn: "1 / -1" } }, e.message)); }
		}
		function card(it) {
			const vote = el("button", { type: "button", class: "lib-btn sm" + (it.voted ? " liked" : ""), "aria-label": "Upvote" }, "▲ " + it.votes);
			vote.addEventListener("click", async () => {
				if (!signedIn()) { location.href = "/store#signin"; return; }
				try { const r = await v1(`schematics/${it.id}/vote`, { json: { up: !it.voted } }); it.voted = !it.voted; vote.textContent = "▲ " + (r.votes ?? it.votes + (it.voted ? 1 : -1)); vote.classList.toggle("liked", it.voted); } catch (e) { toast(e.message); }
			});
			return el("article", { class: "lib-box", style: { display: "flex", flexDirection: "column" } },
				el("button", { type: "button", "aria-label": "Open " + it.title + " in 3D", style: { padding: 0, border: 0, background: "none", cursor: "pointer" }, onclick: () => detail(it) },
					el("img", { src: `/v1/schematics/${it.id}/preview`, alt: "", loading: "lazy", style: { width: "100%", aspectRatio: "4 / 3", objectFit: "contain", background: "var(--surface)", display: "block" } })),
				el("div", { class: "lib-pad", style: { display: "flex", flexDirection: "column", gap: "8px", flexGrow: 1 } },
					el("div", { style: { display: "flex", justifyContent: "space-between", gap: "8px", alignItems: "baseline" } }, el("h3", {}, it.title), el("span", { class: "lib-tag" }, "." + it.format)),
					it.description ? el("p", { class: "lib-muted", style: { margin: 0, fontSize: "13px" } }, it.description) : null,
					el("div", { class: "lib-pills" }, ...(it.tags || []).map((t) => el("button", { type: "button", class: "lib-pill", style: { minHeight: "26px", fontSize: "11px" }, onclick: () => { st.tag = t; st.page = 0; $("#tag").textContent = "#" + t + " ×"; $("#tag").hidden = false; load(); } }, t))),
					el("div", { class: "lib-muted", style: { display: "flex", gap: "8px", alignItems: "center", marginTop: "auto" } },
						el("img", { src: "/v2/head/" + encodeURIComponent(it.author) + ".png?size=32", alt: "", class: "lib-px", style: { width: "18px", height: "18px", borderRadius: "4px" }, loading: "lazy" }),
						it.author + " · " + it.downloads + " downloads · " + Math.max(1, Math.round(it.fileSize / 1024)) + " KB"),
					el("div", { style: { display: "flex", gap: "6px", flexWrap: "wrap" } },
						el("button", { type: "button", class: "lib-btn sm red", onclick: () => detail(it) }, "3D & materials"),
						el("a", { class: "lib-btn sm", href: `/v1/schematics/${it.id}/file`, download: "" }, "Download"), vote,
						it.mine ? el("button", { type: "button", class: "lib-btn sm", onclick: async () => { if (!confirm("Delete “" + it.title + "”?")) return; try { await v1(`schematics/${it.id}/delete`, { method: "POST" }); load(); } catch (e) { toast(e.message); } } }, "Delete") : null)));
		}
		async function detail(it) {
			const dlg = $("#detail");
			$("#d-title").textContent = it.title;
			$("#d-meta").textContent = "by " + it.author + " · ." + it.format + " · " + it.downloads + " downloads · ▲ " + it.votes;
			$("#d-desc").textContent = it.description || "";
			$("#d-download").href = `/v1/schematics/${it.id}/file`;
			$("#d-cmd").textContent = "/velo schem " + it.id;
			$("#d-load").onclick = () => copy("/velo schem " + it.id, "Command copied - paste it in the game chat");
			$("#d-mats").replaceChildren(el("div", { class: "lib-muted" }, "Counting blocks…"));
			$("#d-size").textContent = "";
			history.replaceState(null, "", "#" + it.id);
			dlg.showModal();
			try {
				const r = await fetch(`/v1/schematics/${it.id}/file?view=1`);
				if (!r.ok) throw new Error("HTTP " + r.status);
				const sch = await readSchematic(await r.arrayBuffer(), "x." + it.format);
				$("#d-size").textContent = sch.sx + " × " + sch.sy + " × " + sch.sz;
				materialList(materials(sch), $("#d-mats"));
				await show3d(sch, $("#d-3d"), $("#d-layer"));
			} catch (e) { $("#d-3d").replaceChildren(el("div", { class: "lib-empty" }, "Couldn't open it here (" + e.message + ") - the download still works.")); $("#d-mats").replaceChildren(); }
		}
		$("#d-close").addEventListener("click", () => $("#detail").close());
		$("#detail").addEventListener("close", () => { const st3 = $("#d-3d"); if (st3._viewer) { st3._viewer.dispose(); st3._viewer = null; } history.replaceState(null, "", location.pathname); });
		$("#search").addEventListener("input", debounce((e) => { st.q = e.target.value.trim(); st.page = 0; load(); }, 300));
		$("#tag").addEventListener("click", () => { st.tag = ""; $("#tag").hidden = true; load(); });
		$("#more").addEventListener("click", async () => {
			st.page++;
			const r = await v1(`schematics?sort=${st.sort}&q=${encodeURIComponent(st.q)}&tag=${encodeURIComponent(st.tag)}&page=${st.page}`).catch(() => null);
			if (r) { grid.append(...r.items.map(card)); $("#more").hidden = (r.page + 1) * r.pageSize >= r.total; }
		});
		load();

		// a schematic by URL straight into the 3D view (shared links / testing): ?schemtest=<url>
		if (qs.get("schemtest") && qs.get("schemtest").startsWith("/")) {
			(async () => {
				const u = qs.get("schemtest");
				const r = await fetch(u);
				const sch = await readSchematic(await r.arrayBuffer(), u);
				$("#up-extra").hidden = false;
				materialList(materials(sch), $("#up-mats"));
				await renderPreview(sch, pv0());
				show3d(sch, $("#up-3d"), $("#up-layer"));
			})().catch((e) => toast(e.message));
		}
		function pv0() { return $("#pv"); }

		// ---------- upload
		if (!signedIn()) { $("#up-signin").hidden = false; $("#up-signin").append(signInLink()); $("#upload").hidden = true; return; }
		let fileData = null, fileName = null, ownPreview = null;
		const pv = $("#pv");
		$("#file").addEventListener("change", async (e) => {
			const f = e.target.files[0];
			fileData = null;
			$("#pinfo").textContent = "";
			if (!f) return;
			if (!/\.(veloschem|litematic)$/i.test(f.name)) { $("#pinfo").textContent = "Only .veloschem and .litematic files."; return; }
			if (f.size > 3 * 1024 * 1024) { $("#pinfo").textContent = "Files can be up to 3 MB."; return; }
			fileName = f.name;
			try {
				$("#pinfo").textContent = "Reading…";
				const sch = await readSchematic(f);
				if (!$("#title").value) $("#title").value = (sch.name || f.name.replace(/\.(veloschem|litematic)$/i, "")).slice(0, 48);
				$("#pinfo").textContent = "Drawing the preview…";
				const n = await renderPreview(sch, pv);
				$("#pinfo").textContent = sch.sx + " × " + sch.sy + " × " + sch.sz + " blocks · " + n.toLocaleString() + " visible";
				$("#up-extra").hidden = false;
				materialList(materials(sch), $("#up-mats"));
				show3d(sch, $("#up-3d"), $("#up-layer"));
			} catch (err) { $("#pinfo").textContent = "Couldn't read it for a preview (" + err.message + ") - you can add your own picture."; }
			const rd = new FileReader();
			rd.onload = () => { fileData = String(rd.result).split(",")[1]; };
			rd.readAsDataURL(f);
		});
		$("#ownpv").addEventListener("change", (e) => {
			const f = e.target.files[0];
			if (!f) { ownPreview = null; return; }
			const rd = new FileReader();
			rd.onload = () => { ownPreview = String(rd.result).split(",")[1]; const im = new Image(); im.onload = () => { const g = pv.getContext("2d"); g.clearRect(0, 0, pv.width, pv.height); const k = Math.min(pv.width / im.width, pv.height / im.height); g.drawImage(im, (pv.width - im.width * k) / 2, (pv.height - im.height * k) / 2, im.width * k, im.height * k); }; im.src = rd.result; };
			rd.readAsDataURL(f);
		});
		$("#upload").addEventListener("submit", async (e) => {
			e.preventDefault();
			if (!fileData) { toast("Pick a schematic file first"); return; }
			$("#send").disabled = true;
			$("#send").textContent = "Uploading…";
			try {
				const preview = ownPreview || pv.toDataURL("image/png").split(",")[1];
				const tags = $("#tags").value.split(",").map((t) => t.trim()).filter(Boolean).slice(0, 6);
				await v1("schematics/upload", { json: { title: $("#title").value.trim(), description: $("#desc").value.trim(), tags, fileName, file: fileData, preview } });
				toast("Shared - thanks!");
				$("#upload").reset();
				fileData = null;
				pv.getContext("2d").clearRect(0, 0, pv.width, pv.height);
				$("#pinfo").textContent = "";
				st.sort = "new"; st.page = 0;
				load();
			} catch (err) { toast(err.message); }
			$("#send").disabled = false;
			$("#send").textContent = "Share";
		});
	};

	tools.server = () => {
		const out = $("#result");
		async function look(a) {
			history.replaceState(null, "", "?address=" + encodeURIComponent(a));
			out.replaceChildren(el("div", { class: "lib-empty" }, "Pinging " + a + "…"));
			try {
				const s = await api("server/" + encodeURIComponent(a));
				if (!s.online) {
					out.replaceChildren(el("div", { class: "lib-empty" }, el("span", { class: "lib-tag bad" }, "offline"), " ", a + " didn't answer (" + (s.error || "no reply") + ")."));
					return;
				}
				const motd = el("div", { class: "mc-dirt lib-box" }, el("div", {}, el("div", { class: "mc-list" },
					el("img", { class: "icon lib-px", src: s.icon || "/v2/item/grass_block.png?size=64", alt: "" }),
					el("div", { style: { flexGrow: 1, minWidth: 0 } }, el("div", { style: { display: "flex", justifyContent: "space-between", gap: "12px" } }, el("span", { style: { color: "#fff" } }, a),
						el("span", { style: { color: "#aaa" } }, s.players.online + "/" + s.players.max)), ...motdLines(s.motd.html)))));
				const chart = el("canvas", { width: 1200, height: 160, style: { width: "100%", height: "160px" }, "aria-label": "Players over the last 24 hours", role: "img" });
				out.replaceChildren(...[motd,
					el("div", { class: "lib-grid lib-stats" }, stat("Status", "Online", "var(--good)"), stat("Players", s.players.online.toLocaleString() + " / " + s.players.max.toLocaleString()),
						stat("Version", s.version.replace(/§./g, "")), stat("Ping (from Germany)", s.ping >= 0 ? s.ping + " ms" : "—"), stat("IP", s.ip + ":" + s.port)),
					el("div", { class: "lib-box lib-pad", style: { display: "flex", flexDirection: "column", gap: "10px" } }, el("span", { class: "lib-label" }, "Players · last 24 h"), chart,
						el("span", { class: "lib-muted" }, s.history.length > 1 ? "Checked every 5 minutes while people look at this server." : "The graph fills in while people look at this server (every 5 minutes).")),
					s.players.sample.length ? el("div", { class: "lib-pills" }, ...s.players.sample.map((n) => el("a", { class: "lib-pill", href: "/library/player?name=" + encodeURIComponent(n), style: { display: "inline-flex", alignItems: "center", textDecoration: "none" } }, n))) : null,
					el("div", { style: { display: "flex", gap: "10px", flexWrap: "wrap" } },
						el("a", { class: "lib-btn red", href: "/library/server-banner?address=" + encodeURIComponent(a) }, "Make a server banner"),
						el("a", { class: "lib-btn", href: "/library/motd" }, "Design a MOTD"),
						el("button", { type: "button", class: "lib-btn", onclick: () => copy(s.motd.minimessage, "MOTD copied as MiniMessage") }, "Copy MOTD (MiniMessage)"),
						el("button", { type: "button", class: "lib-btn", onclick: () => copy(s.motd.legacy, "MOTD copied") }, "Copy MOTD (§)"))].filter(Boolean));
				drawChart(chart, s.history);
			} catch (e) { out.replaceChildren(el("div", { class: "lib-empty" }, e.message)); }
		}
		function motdLines(html) {
			// html comes from the Library (text escaped there, only colour/weight styles)
			const tmp = document.createElement("div");
			tmp.innerHTML = html;
			const parts = tmp.innerHTML.split("<br>");
			return parts.slice(0, 2).map((p) => { const d = el("div", { class: "mc-line" }); d.innerHTML = p.replace(/^(\s|&nbsp;)+/, ""); return d; });
		}
		function drawChart(c, hist) {
			const g = c.getContext("2d");
			g.clearRect(0, 0, c.width, c.height);
			if (hist.length < 2) return;
			const max = Math.max(1, ...hist.map((p) => p[1]));
			const t0 = hist[0][0], t1 = hist[hist.length - 1][0];
			g.strokeStyle = "#e0262e"; g.lineWidth = 3; g.beginPath();
			hist.forEach(([t, v], i) => { const x = (t - t0) / Math.max(1, t1 - t0) * (c.width - 20) + 10, y = c.height - 10 - Math.max(0, v) / max * (c.height - 30); i ? g.lineTo(x, y) : g.moveTo(x, y); });
			g.stroke();
			g.fillStyle = "#8f8f9a"; g.font = "22px monospace"; g.fillText("max " + max.toLocaleString(), 12, 26);
		}
		const stat = (k, v, color) => el("div", { class: "lib-stat" }, el("span", { class: "lib-label" }, k), el("b", { style: { color: color || "" } }, v));
		$("#lookup").addEventListener("submit", (e) => { e.preventDefault(); const a = $("#q").value.trim(); if (a) look(a); });
		if (qs.get("address")) { $("#q").value = qs.get("address"); look(qs.get("address")); }
	};

	tools.spark = () => {
		const out = $("#result");
		$("#lookup").addEventListener("submit", async (e) => {
			e.preventDefault();
			const link = $("#q").value.trim();
			if (!link) return;
			out.replaceChildren(el("div", { class: "lib-empty" }, "Reading the profile… big ones take a few seconds."));
			try {
				const r = await api("spark/x?url=" + encodeURIComponent(link));
				const p = r.platform || {};
				const lvl = { high: "bad", medium: "warn", low: "", info: "good" };
				out.replaceChildren(
					el("div", { class: "lib-grid lib-stats" }, st("Platform", [p.name, p.version].filter(Boolean).join(" ") || "—"), st("Minecraft", p.minecraft || "—"),
						st("TPS (1 min)", p.tps ? String(p.tps["1m"]) : "—"), st("MSPT median", p.mspt ? p.mspt.median + " ms" : "—"),
						st("Players", p.players >= 0 ? String(p.players) : "—"), st("Heap", p.heap ? p.heap.usedMb + " / " + p.heap.maxMb + " MB" : "—")),
					el("h3", {}, "What to do"),
					el("div", { style: { display: "grid", gap: "10px" } }, ...r.advice.map((a) => el("div", { class: "lib-box lib-pad" },
						el("span", { class: "lib-tag " + (lvl[a.level] || "") }, a.level.toUpperCase()), el("div", { style: { fontWeight: 600, margin: "6px 0 4px" } }, a.title),
						el("div", { class: "lib-muted", style: { fontSize: "14px" } }, a.text)))),
					el("h3", {}, "Share of the server thread"),
					el("div", { class: "lib-box" }, ...r.sources.map((s, i) => el("div", { style: { padding: "12px 18px", borderTop: i ? "1px solid var(--line)" : "0", display: "flex", flexDirection: "column", gap: "6px" } },
						el("div", { style: { display: "flex", justifyContent: "space-between", fontSize: "14px" } }, el("span", { style: { fontWeight: 600 } }, s.name + (s.version ? " " + s.version : "")),
							el("span", { class: "lib-muted" }, s.percent + "%")),
						el("div", { style: { height: "6px", background: "var(--surface-2)", borderRadius: "3px", overflow: "hidden" } },
							el("div", { style: { height: "100%", width: Math.min(100, s.percent) + "%", background: s.plugin ? (s.percent > 20 ? "var(--red)" : "var(--gold)") : "#555" } }))))),
					el("h3", {}, "Hottest methods"),
					el("div", { class: "lib-scroll lib-box" }, el("table", { class: "lib-table" }, el("thead", {}, el("tr", {}, el("th", {}, "Method"), el("th", {}, "Source"), el("th", {}, "Self time"))),
						el("tbody", {}, ...r.methods.map((m) => el("tr", {}, el("td", { style: { font: "500 12px var(--mono)", wordBreak: "break-all" } }, m.method), el("td", {}, m.source), el("td", {}, m.percent + "%")))))),
					el("a", { class: "lib-btn", href: r.link, target: "_blank", rel: "noopener" }, "Open in spark viewer ↗"));
			} catch (err) { out.replaceChildren(el("div", { class: "lib-empty" }, err.message)); }
		});
		const st = (k, v) => el("div", { class: "lib-stat" }, el("span", { class: "lib-label" }, k), el("b", {}, v));
	};

	tools.flags = () => {
		const st = { ram: 10, software: "paper", gc: "g1", os: "linux" };
		const gen = () => {
			const big = st.ram >= 12;
			const proxy = st.software === "velocity";
			const list = proxy ? ["-XX:+UseG1GC", "-XX:G1HeapRegionSize=4M", "-XX:+UnlockExperimentalVMOptions", "-XX:+ParallelRefProcEnabled", "-XX:+AlwaysPreTouch", "-XX:MaxInlineLevel=15"]
				: st.gc === "zgc" ? ["-XX:+UseZGC", "-XX:+ZGenerational", "-XX:+AlwaysPreTouch", "-XX:+DisableExplicitGC", "-XX:+PerfDisableSharedMem"]
				: ["-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
					"-XX:G1NewSizePercent=" + (big ? 40 : 30), "-XX:G1MaxNewSizePercent=" + (big ? 50 : 40), "-XX:G1HeapRegionSize=" + (big ? "16M" : "8M"), "-XX:G1ReservePercent=" + (big ? 15 : 20),
					"-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=" + (big ? 20 : 15), "-XX:G1MixedGCLiveThresholdPercent=90",
					"-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1", "-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true"];
			const jar = { paper: "paper.jar", folia: "folia.jar", purpur: "purpur.jar", velocity: "velocity.jar", fabric: "fabric-server-launch.jar" }[st.software];
			const mem = proxy ? Math.min(st.ram, 2) : st.ram;
			const java = "java -Xms" + mem + "G -Xmx" + mem + "G \\\n  " + list.join(" \\\n  ") + " \\\n  -jar " + jar + (proxy ? "" : " --nogui");
			return st.os === "windows" ? "@echo off\n" + java.replace(/ \\\n {2}/g, " ") + "\npause" : st.os === "linux" ? "#!/bin/sh\n" + java : "# Pterodactyl → Startup → Additional Java flags\n" + list.join(" ");
		};
		const draw = () => {
			$("#ramv").textContent = st.ram + " GB";
			pills($("#softs"), [["paper", "Paper"], ["folia", "Folia"], ["purpur", "Purpur"], ["velocity", "Velocity"], ["fabric", "Fabric"]], st.software, (k) => { st.software = k; draw(); });
			pills($("#gcs"), [["g1", "G1 (Aikar)"], ["zgc", "Generational ZGC"]], st.gc, (k) => { st.gc = k; draw(); });
			pills($("#oses"), [["linux", "Linux"], ["windows", "Windows"], ["ptero", "Pterodactyl"]], st.os, (k) => { st.os = k; draw(); });
			$("#out").textContent = gen();
			$("#note").textContent = st.software === "folia" ? "Folia runs regions on several threads - only plugins marked Folia-compatible work (check them in Plugin updates)."
				: st.software === "velocity" ? "Proxies need little memory - 512 MB to 2 GB is plenty." : st.gc === "zgc" ? "Generational ZGC needs Java 21+ and works best with spare RAM on the machine." : "Aikar's flags, the well-known G1 setup for Minecraft servers (with the 12 GB+ variant).";
		};
		$("#ram").addEventListener("input", (e) => { st.ram = +e.target.value; draw(); });
		$("#copy").addEventListener("click", () => copy($("#out").textContent, "Start script copied"));
		draw();
		presets("flags", {
			current: () => ({ ...st }),
			apply: (d) => { Object.assign(st, { ram: Math.max(1, Math.min(64, +d.ram || 8)), software: d.software || "paper", gc: d.gc || "g1", os: d.os || "linux" }); $("#ram").value = st.ram; draw(); },
			preview: (pv, d) => { pv.style.font = "500 12px var(--mono)"; pv.textContent = (d.software || "paper") + " · " + (d.ram || 8) + " GB · " + (d.gc === "zgc" ? "ZGC" : "G1"); },
		});
	};

	tools.plugins = () => {
		const out = $("#result");
		$("#check").addEventListener("click", async () => {
			const text = $("#list").value.trim();
			if (!text) return;
			out.replaceChildren(el("div", { class: "lib-empty" }, "Checking Modrinth, Hangar and SpigotMC…"));
			try {
				const r = await api("plugins/check", { json: { text } });
				const tag = (s) => el("span", { class: "lib-tag " + ({ "up to date": "good", update: "warn", abandoned: "bad" }[s] || "") }, s);
				out.replaceChildren(el("div", { class: "lib-scroll lib-box" }, el("table", { class: "lib-table" },
					el("thead", {}, el("tr", {}, ...["Plugin", "Yours", "Newest", "Source", "Folia", "Updated", "State"].map((h) => el("th", {}, h)))),
					el("tbody", {}, ...r.plugins.map((p) => el("tr", {},
						el("td", { style: { fontWeight: 600 } }, p.url ? el("a", { href: p.url, target: "_blank", rel: "noopener" }, p.project || p.name) : p.name),
						el("td", { style: { font: "500 12px var(--mono)" } }, p.yours || "—"), el("td", { style: { font: "500 12px var(--mono)" } }, p.latest || "—"),
						el("td", {}, p.source || "—"), el("td", {}, p.folia === true ? "yes" : p.folia === false ? "no" : "?"), el("td", { class: "lib-muted" }, p.updated ? fmtDate(p.updated) : "—"),
						el("td", {}, tag(p.state))))))));
			} catch (e) { out.replaceChildren(el("div", { class: "lib-empty" }, e.message)); }
		});
	};

	tools.packs = async () => {
		const mine = $("#mine"), result = $("#result"), up = $("#upload");
		const card = (p) => el("div", { class: "lib-box lib-pad", style: { display: "flex", flexDirection: "column", gap: "12px" } },
			el("div", { style: { display: "flex", gap: "14px", alignItems: "center" } },
				p.icon ? el("img", { src: p.icon, alt: "", class: "lib-px", style: { width: "56px", height: "56px", borderRadius: "8px" } }) : null,
				el("div", { style: { minWidth: 0 } }, el("h3", {}, p.name), el("div", { class: "lib-muted" }, (p.description || "") + " · " + p.versions + " · " + (p.size / 1048576).toFixed(1) + " MB"))),
			el("div", { class: "lib-field" }, el("span", {}, "Download link"), el("code", { class: "lib-code", style: { maxHeight: "none" } }, p.url)),
			el("div", { class: "lib-field" }, el("span", {}, "SHA-1"), el("code", { class: "lib-code", style: { maxHeight: "none" } }, p.sha1)),
			el("div", { class: "lib-field" }, el("span", {}, "server.properties"), el("pre", { class: "lib-code" }, p.serverProperties)),
			el("div", { style: { display: "flex", gap: "8px", flexWrap: "wrap" } },
				el("button", { type: "button", class: "lib-btn red sm", onclick: () => copy(p.serverProperties, "server.properties lines copied") }, "Copy server.properties"),
				el("button", { type: "button", class: "lib-btn sm", onclick: () => copy(p.url, "Link copied") }, "Copy link"),
				el("a", { class: "lib-btn sm", href: "/library/packs?sha1=" + p.sha1 }, "Share page"),
				p.mine !== false && signedIn() ? el("button", { type: "button", class: "lib-btn sm", onclick: async () => {
					if (!confirm("Delete this pack? Servers using the link will stop downloading it.")) return;
					try { await api("packs/" + p.sha1, { method: "DELETE" }); toast("Deleted"); loadMine(); } catch (e) { toast(e.message); }
				} }, "Delete") : null));
		async function loadMine() {
			if (!signedIn()) { mine.replaceChildren(el("div", { class: "lib-empty" }, "Sign in to upload and manage packs. ", signInLink())); return; }
			try {
				const r = await api("packs/mine");
				mine.replaceChildren(...(r.packs.length ? r.packs.map(card) : [el("div", { class: "lib-empty" }, "No packs yet.")]));
			} catch (e) { mine.replaceChildren(el("div", { class: "lib-empty" }, e.message)); }
		}
		up.addEventListener("submit", async (e) => {
			e.preventDefault();
			if (!signedIn()) { location.href = "/store#signin"; return; }
			const f = $("#file").files[0];
			if (!f) return;
			if (f.size > 50 * 1048576) { toast("Packs can be up to 50 MB"); return; }
			$("#up-btn").disabled = true;
			$("#up-btn").textContent = "Uploading…";
			try {
				const p = await api("packs?name=" + encodeURIComponent($("#pname").value || f.name.replace(/\.zip$/i, "")), { method: "POST", body: f, headers: { "Content-Type": "application/zip" } });
				result.replaceChildren(card(p));
				loadMine();
			} catch (err) { result.replaceChildren(el("div", { class: "lib-empty lib-error" }, err.message)); }
			$("#up-btn").disabled = false;
			$("#up-btn").textContent = "Upload";
		});
		if (qs.get("sha1")) {
			try { const p = await api("packs/" + qs.get("sha1")); p.mine = false; result.replaceChildren(card(p)); } catch (e) { result.replaceChildren(el("div", { class: "lib-empty" }, e.message)); }
		}
		loadMine();
	};

	tools.items = () => {
		const grid = $("#grid"), q = $("#q");
		let size = 64;
		const load = debounce(async () => {
			try {
				const r = await api("items?q=" + encodeURIComponent(q.value.trim()) + "&limit=400");
				$("#count").textContent = r.count + " items · " + r.version;
				grid.replaceChildren(...r.items.map((it) => el("button", { type: "button", class: "lib-box", title: it.id, style: { display: "flex", flexDirection: "column", alignItems: "center", gap: "6px",
					padding: "12px 6px 8px", cursor: "pointer", color: "var(--text)" }, onclick: () => copy("https://velo-client.com/v2/item/" + it.id + ".png?size=" + size, "Icon URL copied") },
				el("img", { src: "/v2/item/" + it.id + ".png?size=64", alt: it.name, class: "lib-px", style: { width: "48px", height: "48px" }, loading: "lazy" }),
				el("span", { style: { fontSize: "12px", textAlign: "center", lineHeight: 1.3 } }, it.name),
				el("span", { class: "lib-muted", style: { font: "500 10px var(--mono)", wordBreak: "break-all" } }, it.id))));
			} catch (e) { grid.replaceChildren(el("div", { class: "lib-empty" }, e.message)); }
		}, 250);
		pills($("#sizes"), [[32, "32"], [64, "64"], [128, "128"], [256, "256"]], size, function pick(k) { size = k; pills($("#sizes"), [[32, "32"], [64, "64"], [128, "128"], [256, "256"]], size, pick); });
		q.value = qs.get("q") || "";
		q.addEventListener("input", load);
		load();
	};

	tools.enchant = () => {
		// weight = the book multiplier (item multiplier / 2), group = mutually exclusive set
		const E = { sharpness: ["Sharpness", 5, 1, "dmg"], smite: ["Smite", 5, 1, "dmg"], bane_of_arthropods: ["Bane of Arthropods", 5, 1, "dmg"], knockback: ["Knockback", 2, 1],
			fire_aspect: ["Fire Aspect", 2, 2], looting: ["Looting", 3, 2], sweeping_edge: ["Sweeping Edge", 3, 2], unbreaking: ["Unbreaking", 3, 1], mending: ["Mending", 1, 2, "inf"],
			efficiency: ["Efficiency", 5, 1], fortune: ["Fortune", 3, 2, "silk"], silk_touch: ["Silk Touch", 1, 4, "silk"], power: ["Power", 5, 1], punch: ["Punch", 2, 2], flame: ["Flame", 1, 2],
			infinity: ["Infinity", 1, 4, "inf"], protection: ["Protection", 4, 1, "prot"], fire_protection: ["Fire Protection", 4, 1, "prot"], blast_protection: ["Blast Protection", 4, 2, "prot"],
			projectile_protection: ["Projectile Protection", 4, 1, "prot"], thorns: ["Thorns", 3, 4], respiration: ["Respiration", 3, 2], aqua_affinity: ["Aqua Affinity", 1, 2],
			feather_falling: ["Feather Falling", 4, 1], depth_strider: ["Depth Strider", 3, 2, "walk"], frost_walker: ["Frost Walker", 2, 2, "walk"], soul_speed: ["Soul Speed", 3, 4], swift_sneak: ["Swift Sneak", 3, 4],
			density: ["Density", 5, 1, "mace"], breach: ["Breach", 4, 2, "mace"], wind_burst: ["Wind Burst", 3, 2], loyalty: ["Loyalty", 3, 1, "tri"], riptide: ["Riptide", 3, 2, "tri"],
			channeling: ["Channeling", 1, 4, "tri"], impaling: ["Impaling", 5, 2], multishot: ["Multishot", 1, 2, "xb"], piercing: ["Piercing", 4, 1, "xb"], quick_charge: ["Quick Charge", 3, 1], lunge: ["Lunge", 3, 2] };
		const ITEMS = {
			sword: ["Sword", "netherite_sword", ["sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping_edge", "unbreaking", "mending"]],
			pickaxe: ["Pickaxe", "netherite_pickaxe", ["efficiency", "fortune", "silk_touch", "unbreaking", "mending"]],
			bow: ["Bow", "bow", ["power", "punch", "flame", "infinity", "unbreaking", "mending"]],
			crossbow: ["Crossbow", "crossbow", ["multishot", "piercing", "quick_charge", "unbreaking", "mending"]],
			trident: ["Trident", "trident", ["loyalty", "riptide", "channeling", "impaling", "unbreaking", "mending"]],
			helmet: ["Helmet", "netherite_helmet", ["protection", "fire_protection", "blast_protection", "projectile_protection", "respiration", "aqua_affinity", "thorns", "unbreaking", "mending"]],
			chestplate: ["Chestplate", "netherite_chestplate", ["protection", "fire_protection", "blast_protection", "projectile_protection", "thorns", "unbreaking", "mending"]],
			leggings: ["Leggings", "netherite_leggings", ["protection", "fire_protection", "blast_protection", "projectile_protection", "swift_sneak", "thorns", "unbreaking", "mending"]],
			boots: ["Boots", "netherite_boots", ["protection", "fire_protection", "blast_protection", "projectile_protection", "feather_falling", "depth_strider", "frost_walker", "soul_speed", "thorns", "unbreaking", "mending"]],
			mace: ["Mace", "mace", ["density", "breach", "smite", "bane_of_arthropods", "wind_burst", "fire_aspect", "unbreaking", "mending"]],
			elytra: ["Elytra", "elytra", ["unbreaking", "mending"]],
		};
		const st = { item: "sword", sel: { sharpness: 5, unbreaking: 3, mending: 1, looting: 3, fire_aspect: 2, sweeping_edge: 3, knockback: 2 }, survival: true };
		const pen = (w) => (1 << w) - 1;
		const roman = (n) => ["", "I", "II", "III", "IV", "V"][n] || String(n);
		/** Exact search over every merge tree (books <= 10): best total cost per subset, keeping the best per prior-work level. */
		function plan(books) {
			const n = books.length, T = n; // bit T = the item itself
			const full = (1 << (n + 1)) - 1;
			const value = new Array(full + 1).fill(0);
			for (let s = 1; s <= full; s++) for (let i = 0; i < n; i++) if (s & (1 << i)) value[s] += books[i].value;
			const best = new Array(full + 1);
			for (let i = 0; i <= n; i++) best[1 << i] = new Map([[0, { cost: 0, leaf: i }]]);
			const order = [];
			for (let s = 1; s <= full; s++) if (s & (s - 1)) order.push(s);
			order.sort((a, b) => popcount(a) - popcount(b));
			for (const s of order) {
				const m = new Map();
				for (let a = (s - 1) & s; a > 0; a = (a - 1) & s) {
					const b = s ^ a;
					if (s & (1 << T)) { if (!(a & (1 << T))) continue; } // the item stays on the left
					const A = best[a], B = best[b];
					if (!A || !B) continue;
					for (const [wa, ca] of A) for (const [wb, cb] of B) {
						const step = value[b] + pen(wa) + pen(wb);
						if (st.survival && step > 39) continue;
						const w = Math.max(wa, wb) + 1, cost = ca.cost + cb.cost + step;
						const cur = m.get(w);
						if (!cur || cost < cur.cost) m.set(w, { cost, a, b, wa, wb, step });
					}
				}
				if (m.size) best[s] = m;
			}
			const top = best[full];
			if (!top) return null;
			let bw = null;
			for (const [w, c] of top) if (bw === null || c.cost < top.get(bw).cost) bw = w;
			const steps = [];
			const label = (s) => { if (s === 1 << T) return null; const names = []; for (let i = 0; i < n; i++) if (s & (1 << i)) names.push(books[i].label); return names; };
			(function walk(s, w) {
				const c = best[s].get(w);
				if (c.leaf !== undefined) return;
				walk(c.a, c.wa);
				walk(c.b, c.wb);
				steps.push({ left: c.a, right: c.b, cost: c.step, leftItem: !!(c.a & (1 << T)), leftNames: label(c.a), rightNames: label(c.b) });
			})(full, bw);
			return { steps, total: top.get(bw).cost };
		}
		const popcount = (x) => { let c = 0; while (x) { x &= x - 1; c++; } return c; };
		const xpFor = (l) => Math.round(l <= 16 ? l * l + 6 * l : l <= 31 ? 2.5 * l * l - 40.5 * l + 360 : 4.5 * l * l - 162.5 * l + 2220);
		const draw = () => {
			const it = ITEMS[st.item];
			$("#items").replaceChildren(...Object.entries(ITEMS).map(([k, [name, icon]]) => el("button", { type: "button", class: "lib-box", "aria-pressed": k === st.item ? "true" : "false",
				style: { display: "flex", flexDirection: "column", alignItems: "center", gap: "4px", padding: "10px 4px", cursor: "pointer", color: "var(--text)", borderColor: k === st.item ? "var(--red)" : "" },
				onclick: () => { st.item = k; draw(); } }, el("img", { src: "/v2/item/" + icon + ".png?size=64", alt: "", class: "lib-px", style: { width: "36px", height: "36px" } }), el("span", { style: { fontSize: "11px" } }, name))));
			const sel = {};
			for (const k of Object.keys(st.sel)) if (it[2].includes(k)) sel[k] = st.sel[k];
			const groups = new Set(Object.keys(sel).map((k) => E[k][3]).filter(Boolean));
			$("#enchants").replaceChildren(...it[2].map((id) => {
				const [name, max, w, g] = E[id];
				const on = sel[id] != null, blocked = !on && g && groups.has(g);
				return el("div", { style: { display: "flex", gap: "12px", alignItems: "center", padding: "10px 14px", borderTop: "1px solid var(--line)", opacity: blocked ? ".4" : "1" } },
					el("input", { type: "checkbox", checked: on, disabled: blocked, "aria-label": name, onchange: () => { const s = { ...st.sel }; on ? delete s[id] : (s[id] = max); st.sel = s; draw(); } }),
					el("span", { style: { flexGrow: 1, fontWeight: 600, fontSize: "14px" } }, name),
					el("span", { class: "lib-muted" }, blocked ? "conflicts" : "×" + w),
					max > 1 ? el("div", { class: "lib-pills", style: { flexWrap: "nowrap", gap: "3px" } }, ...Array.from({ length: max }, (_, i) => i + 1).map((l) => {
						const b = pill(roman(l), sel[id] === l, () => { st.sel = { ...st.sel, [id]: l }; draw(); });
						b.style.padding = "0 8px";
						b.style.minHeight = "30px";
						b.style.minWidth = "30px";
						return b;
					})) : null);
			}));
			const books = Object.entries(sel).map(([id, l]) => ({ id, label: E[id][0] + " " + roman(l), value: l * E[id][2] }));
			if (books.length > 10) { $("#steps").replaceChildren(el("div", { class: "lib-empty" }, "Pick up to 10 enchantments.")); return; }
			const r = books.length ? plan(books) : null;
			const icon = "/v2/item/" + it[1] + ".png?size=64", book = "/v2/item/enchanted_book.png?size=64";
			if (!books.length) { $("#steps").replaceChildren(el("div", { class: "lib-empty" }, "Pick at least one enchantment.")); setStats("0", "0", "0"); return; }
			if (!r) { $("#steps").replaceChildren(el("div", { class: "lib-empty lib-error" }, "No order stays under 40 levels per step - turn off “Survival limit” to see the cheapest one anyway.")); setStats("—", "—", "—"); return; }
			const side = (isItem, names) => el("div", { style: { flex: "1 1 180px", minWidth: 0, display: "flex", alignItems: "center", gap: "10px" } },
				el("img", { src: isItem ? icon : book, alt: "", class: "lib-px", style: { width: "32px", height: "32px" } }),
				el("span", { style: { fontSize: "14px" } }, isItem ? it[0] + (names && names.length ? " + " + names.length : "") : names.join(" + ")));
			$("#steps").replaceChildren(...r.steps.map((s, i) => el("div", { class: "lib-box lib-pad", style: { display: "flex", gap: "14px", alignItems: "center", flexWrap: "wrap" } },
				el("span", { class: "lib-muted", style: { width: "26px" } }, String(i + 1).padStart(2, "0")), side(s.leftItem, s.leftItem ? null : s.leftNames), el("span", { class: "lib-muted" }, "+"),
				side(false, s.rightNames), el("span", { class: "lib-tag " + (s.cost >= 40 ? "bad" : "good") }, s.cost + " levels"))));
			setStats(String(r.total), r.steps.reduce((a, s) => a + xpFor(s.cost), 0).toLocaleString(), String(Math.max(...r.steps.map((s) => s.cost))));
		};
		const setStats = (t, x, m) => { $("#total").textContent = t; $("#xp").textContent = x; $("#max").textContent = m; };
		$("#survival").addEventListener("change", (e) => { st.survival = e.target.checked; draw(); });
		draw();
		presets("enchant", {
			current: () => ({ item: st.item, sel: st.sel }),
			apply: (d) => { if (ITEMS[d.item]) st.item = d.item; const s = {}; for (const [k, v] of Object.entries(d.sel || {})) if (E[k]) s[k] = Math.max(1, Math.min(E[k][1], +v || 1)); st.sel = s; draw(); },
			preview: (pv, d) => { pv.style.gap = "10px"; pv.append(el("img", { src: "/v2/item/" + ((ITEMS[d.item] || ITEMS.sword)[1]) + ".png?size=64", alt: "", class: "lib-px", style: { width: "40px" } }),
				el("span", { style: { font: "500 12px Inter", textAlign: "left" } }, Object.entries(d.sel || {}).filter(([k]) => E[k]).map(([k, v]) => E[k][0] + " " + roman(+v)).join(", "))); },
		});
	};

	tools.seed = async () => {
		const st = { seed: qs.get("seed") || "-4172144997902289642", mc: "26.3", dim: 0, cx: 0, cz: 0, zoom: 8, show: { village: true, ancient_city: true, trial_chambers: true, monument: true, mansion: true, outpost: false, slime: false } };
		const map = $("#map"), canvas = el("canvas"), hud = el("div", { class: "hud" });
		map.append(canvas, hud);
		let info = null;
		try { info = await api("seed/0"); } catch (e) { info = { biomes: false }; }
		if (!info.biomes) $("#notice").hidden = false;
		const tiles = new Map(), structs = new Map();
		const ICON = { village: "bell", ancient_city: "echo_shard", trial_chambers: "trial_key", monument: "prismarine_shard", mansion: "totem_of_undying", outpost: "crossbow",
			desert_pyramid: "sandstone", jungle_temple: "mossy_cobblestone", fortress: "nether_bricks", bastion: "gilded_blackstone", end_city: "purpur_block", stronghold: "ender_eye" };
		const icons = {};
		const iconImg = (k) => icons[k] || (icons[k] = Object.assign(new Image(), { src: "/v2/item/" + (ICON[k] || "barrier") + ".png?size=32", onload: () => paint() }));
		const TILE = 512; // biome cells per tile side
		// finest biome scale that still gives at least one cell per device pixel - crisp at every zoom
		function scaleFor(z) { const dev = z / Math.min(2, devicePixelRatio || 1); return dev >= 256 ? 256 : dev >= 64 ? 64 : dev >= 16 ? 16 : dev >= 4 ? 4 : 1; }
		function paint() {
			const dpr = Math.min(2, devicePixelRatio || 1), W = map.clientWidth, H = map.clientHeight;
			canvas.width = W * dpr; canvas.height = H * dpr;
			const g = canvas.getContext("2d");
			g.setTransform(dpr, 0, 0, dpr, 0, 0);
			g.imageSmoothingEnabled = false;
			g.fillStyle = "#0b0b14"; g.fillRect(0, 0, W, H);
			const bpp = st.zoom; // blocks per screen pixel
			const x0 = st.cx - W / 2 * bpp, z0 = st.cz - H / 2 * bpp;
			if (info.biomes) {
				const sc = scaleFor(bpp);
				const span = TILE * sc; // blocks per tile
				for (let tz = Math.floor(z0 / span); tz * span < z0 + H * bpp; tz++) for (let tx = Math.floor(x0 / span); tx * span < x0 + W * bpp; tx++) {
					const key = [st.seed, st.mc, st.dim, sc, tx, tz].join("|");
					let img = tiles.get(key);
					if (!img) {
						img = new Image();
						img.onload = () => paint();
						img.src = `/v2/seed/${encodeURIComponent(st.seed)}/tile.png?mc=${st.mc}&dim=${st.dim}&scale=${sc}&x=${tx * TILE}&z=${tz * TILE}&w=${TILE}&h=${TILE}`;
						tiles.set(key, img);
					}
					if (img.complete && img.naturalWidth) g.drawImage(img, (tx * span - x0) / bpp, (tz * span - z0) / bpp, span / bpp + 1, span / bpp + 1);
					else {
						// coarser tile from before as a placeholder - no black holes while zooming
						const cs = sc * 4, cspan = TILE * cs, ctx = Math.floor(tx * span / cspan), ctz = Math.floor(tz * span / cspan);
						const coarse = tiles.get([st.seed, st.mc, st.dim, cs, ctx, ctz].join("|"));
						if (coarse && coarse.complete && coarse.naturalWidth) {
							const sxp = (tx * span - ctx * cspan) / cs, szp = (tz * span - ctz * cspan) / cs;
							g.drawImage(coarse, sxp, szp, span / cs, span / cs, (tx * span - x0) / bpp, (tz * span - z0) / bpp, span / bpp + 1, span / bpp + 1);
						}
					}
				}
			}
			if (st.show.slime && st.dim === 0 && bpp <= 8) {
				g.fillStyle = "rgba(127,255,80,.45)";
				for (const [x, z] of slimeCache) g.fillRect((x * 16 - x0) / bpp, (z * 16 - z0) / bpp, 16 / bpp, 16 / bpp);
			}
			for (const [key, list] of structs) {
				if (!key.startsWith([st.seed, st.mc, st.dim].join("|") + "|")) continue;
				for (const s of list) {
					if (!st.show[s.type]) continue;
					const sx = (s.x - x0) / bpp, sz = (s.z - z0) / bpp;
					if (sx < -20 || sz < -20 || sx > W + 20 || sz > H + 20) continue;
					const im = iconImg(s.type);
					const r = bpp >= 32 ? 6 : bpp >= 12 ? 9 : 13;
					g.fillStyle = "rgba(11,11,14,.7)"; g.beginPath(); g.arc(sx, sz, r, 0, Math.PI * 2); g.fill();
					if (im.complete && im.naturalWidth) g.drawImage(im, sx - r * 0.77, sz - r * 0.77, r * 1.54, r * 1.54);
				}
			}
			g.strokeStyle = "#fff"; g.lineWidth = 2; g.beginPath(); g.arc((0 - x0) / bpp, (0 - z0) / bpp, 5, 0, Math.PI * 2); g.stroke();
			hud.textContent = `x ${Math.round(st.cx)} · z ${Math.round(st.cz)} · 1 px = ${bpp < 1 ? bpp.toFixed(2) : Math.round(bpp)} blocks`;
		}
		let slimeCache = [];
		const loadData = debounce(async () => {
			const bpp = st.zoom, W = map.clientWidth, H = map.clientHeight;
			const x0 = Math.round(st.cx - W / 2 * bpp), z0 = Math.round(st.cz - H / 2 * bpp), x1 = Math.round(st.cx + W / 2 * bpp), z1 = Math.round(st.cz + H / 2 * bpp);
			if (st.show.slime && st.dim === 0 && bpp <= 8) {
				try { const r = await api(`seed/${encodeURIComponent(st.seed)}/slime?cx=${Math.round(st.cx / 16)}&cz=${Math.round(st.cz / 16)}&r=${Math.min(128, Math.ceil(Math.max(W, H) * bpp / 32) + 2)}`); slimeCache = r.chunks; } catch (e) { /* ignore */ }
			}
			if (info.biomes) {
				const grid = 4096, gx0 = Math.floor(x0 / grid) * grid, gz0 = Math.floor(z0 / grid) * grid;
				const types = Object.keys(st.show).filter((k) => st.show[k] && k !== "slime").join(",");
				for (let gz = gz0; gz < z1; gz += grid) for (let gx = gx0; gx < x1; gx += grid) {
					const key = [st.seed, st.mc, st.dim, gx, gz, types].join("|");
					if (structs.has(key) || !types) continue;
					structs.set(key, []);
					api(`seed/${encodeURIComponent(st.seed)}/structures?mc=${st.mc}&dim=${st.dim}&x0=${gx}&z0=${gz}&x1=${gx + grid}&z1=${gz + grid}&types=${types}`)
						.then((r) => { structs.set(key, r.structures); paint(); }).catch(() => structs.delete(key));
				}
			}
			paint();
		}, 250);
		// pan & zoom
		let drag = null;
		map.addEventListener("pointerdown", (e) => { drag = { x: e.clientX, y: e.clientY, cx: st.cx, cz: st.cz }; map.setPointerCapture(e.pointerId); map.style.cursor = "grabbing"; });
		map.addEventListener("pointermove", (e) => { if (!drag) return; st.cx = drag.cx - (e.clientX - drag.x) * st.zoom; st.cz = drag.cz - (e.clientY - drag.y) * st.zoom; paint(); });
		map.addEventListener("pointerup", () => { drag = null; map.style.cursor = ""; loadData(); });
		map.addEventListener("wheel", (e) => { e.preventDefault(); st.zoom = Math.max(0.25, Math.min(256, st.zoom * (e.deltaY > 0 ? 1.25 : 0.8))); paint(); loadData(); }, { passive: false });
		addEventListener("resize", debounce(paint, 150));
		const draw = () => {
			pills($("#dims"), [[0, "Overworld"], [-1, "Nether"], [1, "End"]], st.dim, (k) => { st.dim = k; draw(); loadData(); });
			$("#layers").replaceChildren(...Object.keys(st.show).map((k) => el("label", { class: "lib-check" }, el("input", { type: "checkbox", checked: st.show[k], onchange: (e) => { st.show[k] = e.target.checked; loadData(); } }),
				k === "slime" ? "Slime chunks (zoom in)" : k.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase()))));
		};
		$("#seed").value = st.seed;
		$("#seed").addEventListener("change", (e) => { st.seed = e.target.value.trim() || "0"; history.replaceState(null, "", "?seed=" + encodeURIComponent(st.seed)); loadData(); });
		$("#mc").addEventListener("change", (e) => { st.mc = e.target.value; loadData(); });
		$("#goto").addEventListener("submit", (e) => { e.preventDefault(); st.cx = +$("#gx").value || 0; st.cz = +$("#gz").value || 0; loadData(); });
		$("#spawn").addEventListener("click", async () => { try { const r = await api(`seed/${encodeURIComponent(st.seed)}/spawn?mc=${st.mc}`); if (r.spawn) { st.cx = r.spawn.x; st.cz = r.spawn.z; loadData(); } } catch (e) { toast(e.message); } });
		const portal = () => { const x = +$("#ox").value || 0, z = +$("#oz").value || 0; $("#nether").textContent = Math.floor(x / 8) + ", " + Math.floor(z / 8); const nx = +$("#nx").value || 0, nz = +$("#nz").value || 0; $("#over").textContent = nx * 8 + ", " + nz * 8; };
		["ox", "oz", "nx", "nz"].forEach((i) => $("#" + i).addEventListener("input", portal));
		portal();
		draw();
		loadData();
		presets("seed", {
			current: () => ({ seed: st.seed, mc: st.mc, x: Math.round(st.cx), z: Math.round(st.cz) }),
			apply: (d) => { st.seed = String(d.seed || "0"); st.mc = d.mc || "26.3"; st.cx = +d.x || 0; st.cz = +d.z || 0; $("#seed").value = st.seed; $("#mc").value = st.mc; loadData(); },
			preview: (pv, d) => { pv.style.font = "500 12px var(--mono)"; pv.textContent = "seed " + d.seed + "\n" + (d.mc || "") + " · " + d.x + ", " + d.z; },
		});
	};

	tools.api = async () => {
		const box = $("#keys");
		async function load() {
			if (!signedIn()) { box.replaceChildren(el("div", { class: "lib-empty" }, "Sign in to get a free API key. ", signInLink())); return; }
			try {
				const r = await api("keys");
				box.replaceChildren(
					el("div", { class: "lib-muted" }, "Your tier: " + r.tier + " · " + r.limits.requests + " requests, " + r.limits.renders + " renders, " + r.limits.pings + " pings per minute"),
					...r.keys.map((k) => el("div", { class: "lib-box lib-pad", style: { display: "flex", gap: "12px", alignItems: "center", flexWrap: "wrap" } },
						el("strong", {}, k.label), el("code", { style: { font: "500 12px var(--mono)", flexGrow: 1, wordBreak: "break-all" } }, k.key),
						el("button", { type: "button", class: "lib-btn sm", onclick: () => copy(k.key, "Key copied") }, "Copy"),
						el("button", { type: "button", class: "lib-btn sm", onclick: async () => { if (!confirm("Delete this key? Apps using it stop working.")) return; await api("keys/" + k.key, { method: "DELETE" }).catch((e) => toast(e.message)); load(); } }, "Delete"))),
					el("button", { type: "button", class: "lib-btn red", onclick: async () => { const label = prompt("Name for the key (what uses it):", "My bot"); if (label === null) return; try { await api("keys", { json: { label } }); load(); } catch (e) { toast(e.message); } } }, "Create a key"));
			} catch (e) { box.replaceChildren(el("div", { class: "lib-empty" }, e.message)); }
		}
		load();
		$$("[data-try-url]").forEach((b) => b.addEventListener("click", async () => {
			const pre = b.parentElement.querySelector("pre[data-result]");
			pre.hidden = false;
			pre.textContent = "…";
			try {
				const r = await fetch(b.dataset.tryUrl);
				const t = r.headers.get("content-type") || "";
				if (t.startsWith("image/")) { pre.replaceChildren(el("img", { src: b.dataset.tryUrl, alt: "", style: { maxWidth: "100%", maxHeight: "260px" } })); }
				else pre.textContent = JSON.stringify(await r.json(), null, 2).slice(0, 3000);
			} catch (e) { pre.textContent = e.message; }
		}));
	};

	// shared with assets/library-art.js (map art & pixel art generators)
	window.veloLib = { $, $$, el, api, toast, copy, debounce, pills, pill, signedIn, signInLink, presets, token, qs };
	(tools[TOOL] || (() => {}))();
})();
