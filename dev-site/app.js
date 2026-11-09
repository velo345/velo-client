/* dev.velo-client.com - the real config, module list, protocol table and the interactive bits. No dependencies. */
"use strict";
document.documentElement.classList.add("js");
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// ── Nav (burger on small screens) ──
const toggle = $(".nav-toggle");
toggle.addEventListener("click", () => {
	const open = document.body.classList.toggle("nav-open");
	toggle.setAttribute("aria-expanded", String(open));
});
$$(".nav-links a").forEach((a) => a.addEventListener("click", () => document.body.classList.remove("nav-open")));

// ── Entrance + reveal on scroll (whatever is on screen at load shows right away; a timeout covers headless shots) ──
requestAnimationFrame(() => requestAnimationFrame(() => document.body.classList.add("ready")));
const io = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { e.target.classList.add("on"); io.unobserve(e.target); } }), { rootMargin: "0px 0px -6% 0px" });
$$(".reveal").forEach((n) => (n.getBoundingClientRect().top < innerHeight ? n.classList.add("on") : io.observe(n)));
setTimeout(() => $$(".reveal").forEach((n) => n.classList.add("on")), 4000);

// ── Copy ──
async function copy(text, button, done = "Copied") {
	try {
		await navigator.clipboard.writeText(text);
		if (button) { const old = button.textContent; button.textContent = done; setTimeout(() => { button.textContent = old; }, 1400); }
	} catch { /* no clipboard: nothing to do */ }
}
$$(".copy").forEach((b) => b.addEventListener("click", () => { const t = $("#" + b.dataset.copyTarget); copy(t.dataset.raw || t.textContent, b); }));

/** Stacked panes that wipe in from the right; returns show(i). */
function panes(box, onShow) {
	const list = $$("[data-pane]", box);
	let current = 0;
	return (i) => {
		if (i === current || !list[i]) return;
		const old = list[current];
		if (old.tagName === "IMG") {
			list.forEach((p) => p.classList.remove("was"));
			old.classList.remove("on"); old.classList.add("was");
			list[i].classList.add("on");
			setTimeout(() => old.classList.remove("was"), 820);
		} else {
			old.hidden = true; list[i].hidden = false;
		}
		current = i;
		if (onShow) onShow(i);
	};
}
function tabs(bar, show) {
	const bs = $$("[role=tab]", bar);
	bs.forEach((b, i) => {
		b.addEventListener("click", () => { bs.forEach((x) => x.setAttribute("aria-selected", String(x === b))); show(i); });
		b.addEventListener("keydown", (e) => {
			const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
			if (step) { e.preventDefault(); const n = bs[(i + step + bs.length) % bs.length]; n.focus(); n.click(); }
		});
	});
}

// ── Hero console: the exact commands that made the captures, typed out ──
const CONSOLE = [
	["velobridge toast * Round 2 | The island shrinks in 30 seconds. Grab your gear!\nvelobridge progress * 0.65 Island capture\nvelobridge waypointat * 128 71 -40 Diamond vault", "[VeloBridge] Sent to 1 Velo player"],
	["velobridge countdown * 8 Round starts", "[VeloBridge] Sent to 1 Velo player"],
	["velobridge tint * #ff3b3b 7\nvelobridge vignette * #ff1e32 7\nvelobridge shake * 0.5", "[VeloBridge] Sent to 1 Velo player"],
	["velobridge reload   # mod-rules in config.yml: forbid modmenu", "[VeloBridge] Reloaded - checked on the player's PC, nothing sent back"],
];
const consoleBox = $("[data-tabs=console]");
const typed = $("[data-typed]");
const out = $("[data-out]");
let typing = 0;
function type(i) {
	const run = ++typing;
	const [cmd, result] = CONSOLE[i];
	out.classList.remove("show");
	if (reduce) { typed.textContent = cmd; out.textContent = result; out.classList.add("show"); return; }
	let k = 0;
	typed.textContent = "";
	const step = () => {
		if (run !== typing) return;
		k = Math.min(cmd.length, k + 2);
		typed.textContent = cmd.slice(0, k);
		if (k < cmd.length) setTimeout(step, cmd[k] === "\n" ? 160 : 14);
		else setTimeout(() => { if (run === typing) { out.textContent = result; out.classList.add("show"); } }, 220);
	};
	step();
}
const showConsole = panes($(".stage", consoleBox), type);
tabs($(".win-bar", consoleBox), showConsole);
setTimeout(() => type(0), 700);

// ── Feature explorer ──
const FEATURES = [
	{ n: "Teammates", t: "Outlines through walls in your team colour, names with distance, and an arrow at the screen edge when they're behind you. Set them per round, or for a time window.",
		java: 'VeloBridge.teammates(player, team, "#4ade80", 600);', cmd: "velobridge team #4ade80 600 Steve Alex", cfg: "# Per round from your plugin - nothing to configure." },
	{ n: "Waypoints & pings", t: "Markers in the world with a breathing ring, label and live distance: objectives, beds, the flag. The same id moves it, a timer removes it.", shot: "tint",
		java: 'VeloBridge.waypoint(player, "flag", location, "Flag", "#f4b82e", 0);', cmd: "velobridge waypointat * 128 71 -40 Diamond vault", cfg: "# From your plugin or the command." },
	{ n: "Toasts", t: "A short notice with your server's name on top, a title, a line of text and a hairline that drains with the time left. They move below your progress bars when space is tight.", shot: "toast",
		java: 'VeloBridge.toast(player, "Round 2", "The island shrinks in 30 seconds", 6);', cmd: "velobridge toast * Round 2 | The island shrinks in 30 seconds.",
		cfg: 'welcome-toast:\n  enabled: true\n  title: "Welcome!"\n  text: "This server supports Velo Client features."' },
	{ n: "Progress bars", t: "Labelled bars for captures, events and loading. Update them as often as you like, remove them by id.", shot: "toast",
		java: 'VeloBridge.progress(player, "capture", "Island capture", 0.65);\nVeloBridge.removeProgress(player, "capture");', cmd: "velobridge progress * 0.65 Island capture", cfg: "# From your plugin or the command." },
	{ n: "Countdown", t: "A ring in the middle of the screen; each digit settles in, then GO. Markers behind it step aside.", shot: "countdown",
		java: 'VeloBridge.countdown(player, "Round starts", 8);', cmd: "velobridge countdown * 8 Round starts", cfg: "# From your plugin or the command." },
	{ n: "Camera & mood", t: "Screen shake for explosions and boss stomps (the view moves, never the aim), coloured vignettes and full-screen tints for events.", shot: "tint",
		java: 'VeloBridge.tint(player, "#ff3b3b", 0.35, 7000);\nVeloBridge.vignette(player, "#ff1e32", 0.5, 7000);\nVeloBridge.shake(player, 0.5, 800);', cmd: "velobridge tint * #ff3b3b 7\nvelobridge vignette * #ff1e32 7\nvelobridge shake * 0.5", cfg: "# From your plugin or the command." },
	{ n: "Discord presence", t: "Your server writes the status friends see on Discord: \"In BedWars · Team Red\". Placeholders in the config, or per player from code.",
		java: 'VeloBridge.presence(player, "In BedWars", "Team Red");', cmd: "# config.yml or the API",
		cfg: 'presence:\n  enabled: true\n  details: "Playing on %server%"\n  state: "%online%/%max% online"' },
	{ n: "Mod hints", t: "Tell players which mods you don't allow. Wurst, Meteor, Glazed, X-Ray, Baritone and more come as ready lists. Checked on the player's PC and shown as a friendly heads-up, never enforced.", shot: "modhints",
		java: "// Set in config.yml - hints are checked on the player's PC; with one click they remove the mod and rejoin.", cmd: "velobridge reload",
		cfg: 'mod-rules:\n  forbid-presets: [cheat-clients, often-banned]\n  forbid:\n    - { match: id, value: "freecam", label: "Freecam" }\n  message: "Using these can get you banned here."' },
	{ n: "Module control", t: "Switch specific Velo modules off on your server: Freelook in hide-and-seek, the minimap in UHC. Enforced while they play there, back on when they leave. Players see what you turned off.",
		java: 'VeloBridge.disableModules(player, List.of("freelook"), "Not in hide & seek");', cmd: "# config.yml or the API", cfg: "disabled-modules:\n  - freelook\n  - minimap-radar" },
	{ n: "Anti-cheat insight", t: "Velo tells your server which of its modules are on, live. Instead of banning a whole client, react to exactly the module that matters.",
		java: '@EventHandler\npublic void onModules(VeloModulesChangeEvent e) {\n    if (e.getActive().contains("freelook")) { /* ... */ }\n}', cmd: "velobridge players", cfg: "# Events in the Java API." },
	{ n: "Transfers & pings", t: "Move players between servers with the 1.20.5+ transfer packet, no proxy. Ask the client its ping to each server and send it to the closest. Velo warms the target up first.",
		java: 'VeloBridge.pings(player, lobbies).thenAccept(ms -> { /* pick the fastest */ });\nVeloBridge.transfer(player, "eu.lobby.gg", 25565);', cmd: "velobridge ping Steve\nvelobridge transfer Steve lobby",
		cfg: 'transfer-targets:\n  lobby: "lobby.example.com:25565"\n  survival: "survival.example.com"' },
	{ n: "Instant resource packs", t: "Velo downloads your pack in the background before the prompt shows, straight into Minecraft's own cache, so the pack screen finishes at once.",
		java: "// Set in config.yml - the id is the UUID you use for setResourcePack.", cmd: "velobridge reload",
		cfg: 'precache-packs:\n  - { id: "7a5d6c1e-…", url: "https://example.com/pack.zip", sha1: "0123…" }' },
	{ n: "World Notes rules", t: "Velo's floating notes on your server: switch them off, block areas like spawn, or cap how many each player may place.",
		java: "// Set in config.yml.", cmd: "velobridge reload",
		cfg: 'notes:\n  enabled: true\n  max-per-player: 20\n  blocked-areas:\n    - { name: "Spawn", world: "world", x1: -100, z1: -100, x2: 100, z2: 100 }' },
	{ n: "Owner panel", t: "Live numbers - Velo players over time, versions, modules, when people join - and every setting of config.yml in one page. Run the command, open the link, change things; your server has them seconds later.",
		java: "// Nothing to call.", cmd: "velobridge panel", cfg: "panel:\n  enabled: true" },
	{ n: "Server menus", t: "Buttons, list entries with item icons, input fields, toggles and sliders - shown as a real Velo window. Players without Velo get the same menu as a chest. Build them in the panel with a live preview, or in code.",
		java: 'VeloBridge.openMenu(player, VeloMenu.create("warps", "Warps")\n    .entry("spawn", "Spawn", "Back to the start", "compass", c -> c.player().performCommand("spawn"))\n    .input("home", "Home name", "base")\n    .button("set", "Set home", "green_bed", "primary", c -> setHome(c.player(), c.values().get("home"))));',
		cmd: "velomenu warps", cfg: 'menus:\n  list:\n    warps:\n      title: "Warps"\n      command: warps\n      items:\n        - { type: entry, id: spawn, label: "Spawn", icon: compass, action: "command:/spawn ; close" }' },
	{ n: "HUD elements", t: "Text, bars, timers and pictures in the player's HUD - coins, boss health, quest progress, an event countdown. PlaceholderAPI works. Players move them in Velo's HUD editor.",
		java: 'VeloBridge.hudBar(player, "boss", "Wither King", 0.42, "840 / 2000", "#ff5555");\nVeloBridge.hudTimer(player, "event", "Event starts in", endsAt, "#f4b82e");', cmd: "# config.yml or the panel",
		cfg: 'hud:\n  coins:\n    type: text\n    title: "Coins"\n    text: "%vault_eco_balance%"\n    icon: gold_nugget' },
	{ n: "Cutscenes", t: "Camera flights for intros and events on a smooth curve, with letterbox bars and a title - Velo Rewind's camera. Record them by flying to each spot. The player's real position never changes; players without Velo see the title.",
		java: 'VeloBridge.cutscene(player, "intro", points, 12, true, "Welcome", "to the island");', cmd: "velobridge cutscene record intro\nvelobridge cutscene point\nvelobridge cutscene save\nvelobridge cutscene play intro *",
		cfg: 'on-first-join-cutscene: intro' },
	{ n: "Music & sound zones", t: "Music by area that fades in and out as players walk around, world music for the nether, or music from code. Vanilla sounds or your resource pack's.",
		java: 'VeloBridge.music(player, "myserver:boss_theme", 0.8, true);\nVeloBridge.stopMusic(player);', cmd: "velobridge zone pos1\nvelobridge zone pos2\nvelobridge zone create spawn minecraft:music_disc.cat",
		cfg: 'world-music:\n  world_nether: "minecraft:music.nether.basalt_deltas"' },
	{ n: "Server notes", t: "Opt in and every Velo player can pin World Notes for everyone on your server, only in the areas you allow. They live on your server - remove any of them in the panel or with a command.",
		java: "// Set in config.yml or the panel.", cmd: "velobridge notes list\nvelobridge notes clear Steve",
		cfg: 'notes:\n  server-wide:\n    enabled: true\n    areas:\n      - { name: "Market", world: "world", x1: 100, z1: 100, x2: 200, z2: 200 }\n    max-per-player: 5' },
	{ n: "NPC cosmetics", t: "Dress your FancyNpcs or Citizens NPCs in Velo cloaks, wings, auras and bodywear - pick them in the panel like in Velo's Locker, with a 3D preview.",
		java: "// Set in the panel or config.yml.", cmd: "velobridge panel",
		cfg: 'npc-cosmetics:\n  shopkeeper:\n    cape: cape_wildfire\n    wings: wings_angelic' },
	{ n: "Partner listing", t: "Anonymous numbers (Velo players online, versions) put your server on the Velo partner radar, the way to get featured in the launcher. No names, opt out with one line.",
		java: "// Nothing to call.", cmd: "# config.yml", cfg: "stats: true" },
];
function highlight(src, kind) {
	let s = esc(src);
	if (kind === "cfg") {
		// Strings and numbers before keys, so the key's own span (class="k") is never taken for a string.
		return s.split("\n").map((l) => (/^\s*#/.test(l) ? `<span class="c">${l}</span>` : l
			.replace(/\b(true|false|\d+)\b(?=[^"]*(?:"[^"]*"[^"]*)*$)/g, '\u0001$1\u0002').replace(/"([^"]*)"/g, '\u0003"$1"\u0004')
			.replace(/^(\s*-?\s*\{?\s*)([\w-]+)(:)/, '$1\u0005$2\u0006$3')
			.replace(/\u0001/g, '<span class="n">').replace(/\u0003/g, '<span class="s">').replace(/\u0005/g, '<span class="k">').replace(/[\u0002\u0004\u0006]/g, "</span>"))).join("\n");
	}
	if (kind === "cmd") return s.split("\n").map((l) => (l.startsWith("#") ? `<span class="c">${l}</span>` : `<span class="p">&gt;</span> ${l}`)).join("\n");
	return s.split("\n").map((l) => (/^\s*\/\//.test(l) ? `<span class="c">${l}</span>` : l
		.replace(/\.(\w+)\(/g, ".\u0001$1\u0002(").replace(/(@\w+)/g, "\u0003$1\u0002").replace(/\b(public|void|if)\b/g, "\u0005$1\u0002")
		.replace(/"([^"]*)"/g, '\u0007"$1"\u0002')
		.replace(/\u0001/g, '<span class="f">').replace(/\u0003/g, '<span class="a">').replace(/\u0005/g, '<span class="k">').replace(/\u0007/g, '<span class="s">')
		.replace(/\u0002/g, "</span>"))).join("\n");
}
const ex = $("[data-explorer]");
const exList = $("[data-ex-list]", ex);
let fi = 0, code = "java";
function drawFeature(animate) {
	const f = FEATURES[fi];
	$("[data-ex-name]", ex).textContent = f.n;
	$("[data-ex-text]", ex).textContent = f.t;
	const c = $("[data-ex-code]", ex);
	c.innerHTML = highlight(f[code], code);
	const shot = $("[data-ex-shot]", ex);
	shot.hidden = !f.shot;
	if (f.shot) {
		const img = $("[data-ex-img]", ex);
		img.src = `/shots/${f.shot}-v2.webp`;
		img.srcset = `/shots/${f.shot}-v2-800.webp 800w, /shots/${f.shot}-v2.webp 1600w`;
		img.sizes = "(max-width: 860px) 100vw, 900px";
		img.alt = `${f.n} on a Velo client`;
	}
	if (animate) for (const n of [$(".ex-head", ex), c]) { n.classList.remove("swap"); void n.offsetWidth; n.classList.add("swap"); }
}
FEATURES.forEach((f, i) => {
	const b = document.createElement("button");
	b.type = "button";
	b.setAttribute("role", "tab");
	b.setAttribute("aria-selected", String(i === 0));
	b.append(f.n);
	if (f.shot) { const tag = document.createElement("i"); tag.textContent = "SHOT"; b.append(tag); }
	b.addEventListener("click", () => { fi = i; $$("button", exList).forEach((x) => x.setAttribute("aria-selected", String(x === b))); drawFeature(true); });
	exList.append(b);
});
$$("[data-ex-code-tabs] [role=tab]", ex).forEach((b) => b.addEventListener("click", () => {
	code = b.dataset.code;
	$$("[data-ex-code-tabs] [role=tab]", ex).forEach((x) => x.setAttribute("aria-selected", String(x === b)));
	drawFeature(true);
}));
$("[data-ex-copy]", ex).addEventListener("click", (e) => copy(FEATURES[fi][code], e.currentTarget));
drawFeature(false);

// ── Setup: per server software ──
const PNOTE = {
	Paper: "Uses Paper's schedulers - the same code path Folia needs.",
	Purpur: "Purpur is Paper underneath - nothing extra to do.",
	Folia: "Region and entity schedulers: every call is safe from any region thread.",
	Spigot: "* Falls back to Bukkit's scheduler. Transfers need 1.20.5+.",
};
$$("[data-platforms] [role=tab]").forEach((b, _, all) => b.addEventListener("click", () => {
	all.forEach((x) => x.setAttribute("aria-selected", String(x === b)));
	$("[data-pname]").textContent = b.dataset.p;
	$("[data-pnote]").textContent = PNOTE[b.dataset.p];
}));
$("[data-pnote]").textContent = PNOTE.Paper;

// ── API examples ──
const api = $("[data-tabs=api]");
tabs($(".win-bar", api), panes($(".api-panes", api)));

// ── Protocol: filter by text and direction ──
const PROTO = [
	["hello", "c2s", "protocol, Velo version, Minecraft version, active module ids. Sent once on join."],
	["menu_action", "c2s", "menu, id (button / entry), values { field: value }"],
	["menu_closed", "c2s", "menu"],
	["cutscene_done", "c2s", "id"],
	["note_put", "c2s", "ref, note { id?, x, y, z, title, text, color, icon, style, size, face, yaw, checks, board } · note_check · note_delete"],
	["modules", "c2s", "The active module ids, whenever they change."],
	["ping_result", "c2s", "nonce, results { \"host\": ms } (-1 = unreachable)."],
	["welcome", "s2c", "name, color - shown on everything you send."],
	["toast", "s2c", "title, text, seconds"],
	["countdown", "s2c", "label, seconds"],
	["progress", "s2c", "id, label, value 0-1 · progress_remove"],
	["shake", "s2c", "intensity 0-1, millis"],
	["vignette", "s2c", "color, strength 0-1, millis"],
	["tint", "s2c", "color, strength 0-1, millis · effects_clear"],
	["teammates", "s2c", "list [ { uuid, name } ], color, seconds"],
	["waypoint", "s2c", "id, x, y, z, label, color, seconds · waypoint_remove · waypoints_clear"],
	["presence", "s2c", "details, state"],
	["mod_rules", "s2c", "forbid / recommend [ { match: id|modrinth|name, value, label } ], message"],
	["lock_modules", "s2c", "modules [ ids ], reason"],
	["ping", "s2c", "nonce, servers [ \"host:port\" ]"],
	["precache", "s2c", "packs [ { id (pack UUID), url, sha1 } ]"],
	["preconnect", "s2c", "hosts [ \"host:port\" ]"],
	["notes", "s2c", "enabled, maxPerPlayer, blocked [ { world, x1, z1, x2, z2 } ]"],
	["menu", "s2c", "id, title, subtitle, color, width, items [ { type: text|divider|button|entry|input|toggle|slider, id, label, … } ] · menu_close"],
	["hud_set", "s2c", "id, type text|bar|timer|image, title, text, value 0-1, endsAt, color, icon, url, w, h, order · hud_remove · hud_clear"],
	["cutscene", "s2c", "id, points [ [x, y, z, yaw, pitch] ], duration ms, letterbox, lock, title, subtitle · cutscene_stop"],
	["music", "s2c", "sound, volume 0-1, loop · music_stop"],
	["server_notes", "s2c", "enabled, canPlace, max, manage, whiteboards, notes [ … ] · server_note · server_note_remove · note_result"],
	["npc_cosmetics", "s2c", "npcs [ { entity, uuid?, cape, wings, aura, wear } ]"],
];
const pq = $("[data-proto-q]");
const rows = $("[data-proto-rows]");
let dir = "";
function drawProto() {
	const q = pq.value.trim().toLowerCase();
	const shown = PROTO.filter(([t, d, text]) => (!dir || d === dir) && (!q || `${t} ${d} ${text}`.toLowerCase().includes(q)));
	rows.innerHTML = shown.length
		? shown.map(([t, d, text], i) => `<div style="animation-delay:${Math.min(i, 12) * 18}ms"><code>${esc(t)}</code><span class="dir ${d}">${d === "s2c" ? "→ client" : "→ server"}</span><span>${esc(text)}</span></div>`).join("")
		: `<span class="none">Nothing matches “${esc(q)}”.</span>`;
	$("[data-proto-count]").textContent = `${shown.length} of ${PROTO.length} messages`;
}
pq.addEventListener("input", drawProto);
$$("[data-proto-dir] [role=tab]").forEach((b, _, all) => b.addEventListener("click", () => {
	dir = b.dataset.d;
	all.forEach((x) => x.setAttribute("aria-selected", String(x === b)));
	drawProto();
}));
drawProto();

// ── The real config.yml, highlighted ──
function yaml(text) {
	return text.split("\n").map((line) => {
		if (/^\s*#/.test(line)) return '<span class="c">' + esc(line) + "</span>";
		const m = line.match(/^(\s*-?\s*)([\w-]+)(:)(.*)$/);
		if (!m) return esc(line);
		const value = esc(m[4]).replace(/"([^"]*)"/g, '<span class="s">"$1"</span>')
			.replace(/\b(true|false)\b/g, '<span class="n">$1</span>').replace(/(^|\s)(\d+)\b/g, '$1<span class="n">$2</span>');
		return esc(m[1]) + '<span class="k">' + m[2] + "</span>" + m[3] + value;
	}).join("\n");
}
fetch("/config.yml").then((r) => r.text()).then((t) => {
	const el = $("#config-code");
	el.innerHTML = yaml(t);
	el.dataset.raw = t;
}).catch(() => { $("#config-code").textContent = "Couldn't load config.yml - it's inside the jar too."; });

// ── Module list: search, categories, click to copy the id ──
const CAT = { hud: "HUD", qol: "Quality of life", pvp: "PvP", performance: "Performance", rendering: "Rendering", cosmetics: "Cosmetics", server_tools: "Server tools", debug: "Debug", other: "Other" };
fetch("/modules.json").then((r) => r.json()).then((mods) => {
	const list = $("#mod-list");
	const search = $("#mod-search");
	const cats = $("#mod-cats");
	let cat = "all";
	const present = ["all", ...Object.keys(CAT).filter((c) => mods.some((m) => m.category === c))];
	for (const c of present) {
		const b = document.createElement("button");
		b.type = "button";
		b.textContent = c === "all" ? "All " + mods.length : CAT[c];
		b.className = c === cat ? "on" : "";
		b.addEventListener("click", () => { cat = c; $$("button", cats).forEach((x) => x.classList.toggle("on", x === b)); draw(); });
		cats.append(b);
	}
	function draw() {
		const q = search.value.trim().toLowerCase();
		const shown = mods.filter((m) => (cat === "all" || m.category === cat) && (!q || (m.id + " " + m.name + " " + m.description).toLowerCase().includes(q)));
		list.replaceChildren(...shown.map((m, i) => {
			const card = document.createElement("div");
			card.className = "mod";
			card.style.animationDelay = Math.min(i, 20) * 15 + "ms";
			card.title = "Click to copy the id";
			const name = document.createElement("b");
			name.textContent = m.name;
			const id = document.createElement("code");
			id.textContent = m.id;
			const p = document.createElement("p");
			p.textContent = m.description;
			card.append(name, id, p);
			card.addEventListener("click", () => { copy(m.id); card.classList.add("copied"); id.textContent = "copied ✓"; setTimeout(() => { card.classList.remove("copied"); id.textContent = m.id; }, 1100); });
			return card;
		}));
		if (!shown.length) list.textContent = "No module matches - try another word.";
	}
	search.addEventListener("input", draw);
	draw();
});
