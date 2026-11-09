/*
 * Velo Library: map art and pixel art / text generators. Everything runs in the browser - the picture never leaves it
 * unless you press "Load in Velo" or "Share". Output is a real .litematic (NBT + gzip written here).
 */
(() => {
	"use strict";
	const L = window.veloLib;
	const root = document.querySelector("[data-tool]");
	if (!L || !root || !["mapart", "pixelart"].includes(root.dataset.tool)) return;
	const { $, $$, el, toast, copy, debounce, pills, signedIn } = L;

	// ------------------------------------------------------------------ map colours (Minecraft MapColor, base RGB)
	// [id, name, rgb, blocks that give this colour on a map (first = default)]
	const MAP = [
		[1, "Grass", [127, 178, 56], ["grass_block", "slime_block"]],
		[2, "Sand", [247, 233, 163], ["sandstone", "birch_planks", "sand", "glowstone", "end_stone"]],
		[3, "Wool", [199, 199, 199], ["mushroom_stem", "cobweb"]],
		[4, "Fire", [255, 0, 0], ["redstone_block", "tnt"]],
		[5, "Ice", [160, 160, 255], ["packed_ice", "ice", "blue_ice"]],
		[6, "Metal", [167, 167, 167], ["iron_block", "heavy_weighted_pressure_plate", "brewing_stand"]],
		[7, "Plant", [0, 124, 0], ["oak_leaves", "birch_leaves", "jungle_leaves", "azalea_leaves", "cactus"]],
		[8, "Snow", [255, 255, 255], ["white_wool", "white_concrete", "white_carpet", "snow_block", "white_concrete_powder"]],
		[9, "Clay", [164, 168, 184], ["clay"]],
		[10, "Dirt", [151, 109, 77], ["dirt", "jungle_planks", "granite", "coarse_dirt", "brown_mushroom_block"]],
		[11, "Stone", [112, 112, 112], ["stone", "cobblestone", "andesite", "stone_bricks", "gravel"]],
		[13, "Wood", [143, 119, 72], ["oak_planks", "oak_log"]],
		[14, "Quartz", [255, 252, 245], ["quartz_block", "diorite", "sea_lantern"]],
		[15, "Orange", [216, 127, 51], ["orange_wool", "orange_concrete", "orange_carpet", "acacia_planks", "pumpkin", "orange_concrete_powder"]],
		[16, "Magenta", [178, 76, 216], ["magenta_wool", "magenta_concrete", "magenta_carpet", "purpur_block", "magenta_concrete_powder"]],
		[17, "Light blue", [102, 153, 216], ["light_blue_wool", "light_blue_concrete", "light_blue_carpet", "light_blue_concrete_powder"]],
		[18, "Yellow", [229, 229, 51], ["yellow_wool", "yellow_concrete", "yellow_carpet", "hay_block", "yellow_concrete_powder"]],
		[19, "Lime", [127, 204, 25], ["lime_wool", "lime_concrete", "lime_carpet", "melon", "lime_concrete_powder"]],
		[20, "Pink", [242, 127, 165], ["pink_wool", "pink_concrete", "pink_carpet", "pink_concrete_powder"]],
		[21, "Gray", [76, 76, 76], ["gray_wool", "gray_concrete", "gray_carpet", "gray_concrete_powder"]],
		[22, "Light gray", [153, 153, 153], ["light_gray_wool", "light_gray_concrete", "light_gray_carpet", "light_gray_concrete_powder"]],
		[23, "Cyan", [76, 127, 153], ["cyan_wool", "cyan_concrete", "cyan_carpet", "prismarine", "cyan_concrete_powder"]],
		[24, "Purple", [127, 63, 178], ["purple_wool", "purple_concrete", "purple_carpet", "purple_concrete_powder"]],
		[25, "Blue", [51, 76, 178], ["blue_wool", "blue_concrete", "blue_carpet", "blue_concrete_powder"]],
		[26, "Brown", [102, 76, 51], ["brown_wool", "brown_concrete", "brown_carpet", "dark_oak_planks", "soul_sand", "brown_concrete_powder"]],
		[27, "Green", [102, 127, 51], ["green_wool", "green_concrete", "green_carpet", "dried_kelp_block", "green_concrete_powder"]],
		[28, "Red", [153, 51, 51], ["red_wool", "red_concrete", "red_carpet", "bricks", "nether_wart_block", "red_concrete_powder"]],
		[29, "Black", [25, 25, 25], ["black_wool", "black_concrete", "black_carpet", "obsidian", "coal_block", "black_concrete_powder"]],
		[30, "Gold", [250, 238, 77], ["gold_block", "light_weighted_pressure_plate"]],
		[31, "Diamond", [92, 219, 213], ["diamond_block", "prismarine_bricks"]],
		[32, "Lapis", [74, 128, 255], ["lapis_block"]],
		[33, "Emerald", [0, 217, 58], ["emerald_block"]],
		[34, "Podzol", [129, 86, 49], ["spruce_planks", "podzol"]],
		[35, "Nether", [112, 2, 0], ["netherrack", "nether_bricks"]],
		[36, "White terracotta", [209, 177, 161], ["white_terracotta"]],
		[37, "Orange terracotta", [159, 82, 36], ["orange_terracotta", "red_sand"]],
		[38, "Magenta terracotta", [149, 87, 108], ["magenta_terracotta"]],
		[39, "Light blue terracotta", [112, 108, 138], ["light_blue_terracotta"]],
		[40, "Yellow terracotta", [186, 133, 36], ["yellow_terracotta"]],
		[41, "Lime terracotta", [103, 117, 53], ["lime_terracotta"]],
		[42, "Pink terracotta", [160, 77, 78], ["pink_terracotta"]],
		[43, "Gray terracotta", [57, 41, 35], ["gray_terracotta"]],
		[44, "Light gray terracotta", [135, 107, 98], ["light_gray_terracotta"]],
		[45, "Cyan terracotta", [87, 92, 92], ["cyan_terracotta"]],
		[46, "Purple terracotta", [122, 73, 88], ["purple_terracotta"]],
		[47, "Blue terracotta", [76, 62, 92], ["blue_terracotta"]],
		[48, "Brown terracotta", [76, 50, 35], ["brown_terracotta"]],
		[49, "Green terracotta", [76, 82, 42], ["green_terracotta"]],
		[50, "Red terracotta", [142, 60, 46], ["red_terracotta"]],
		[51, "Black terracotta", [37, 22, 16], ["black_terracotta"]],
		[52, "Crimson nylium", [189, 48, 49], ["crimson_nylium"]],
		[53, "Crimson stem", [148, 63, 97], ["crimson_planks"]],
		[54, "Crimson hyphae", [92, 25, 29], ["crimson_hyphae"]],
		[55, "Warped nylium", [22, 126, 134], ["warped_nylium"]],
		[56, "Warped stem", [58, 142, 140], ["warped_planks"]],
		[57, "Warped hyphae", [86, 44, 62], ["warped_hyphae"]],
		[58, "Warped wart", [20, 180, 133], ["warped_wart_block"]],
		[59, "Deepslate", [100, 100, 100], ["cobbled_deepslate", "deepslate"]],
		[60, "Raw iron", [216, 175, 147], ["raw_iron_block"]],
		[61, "Glow lichen", [127, 167, 150], ["verdant_froglight"]],
	];
	const SHADE = [180, 220, 255]; // dark (lower than north), normal (same), bright (higher)
	const NEEDS_SUPPORT = /carpet|concrete_powder|^sand$|^red_sand$|^gravel$|pressure_plate|^snow$/;
	const PRESETS_MAP = {
		all: () => MAP.map((m) => m[0]),
		wool: () => [8, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29],
		carpet: () => [8, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29],
		concrete: () => [8, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29],
		terracotta: () => MAP.filter((m) => m[0] >= 36 && m[0] <= 51).map((m) => m[0]),
		easy: () => [1, 2, 7, 8, 10, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 34, 35, 36, 37, 51, 59],
	};
	const preferFor = (preset, blocks) => {
		const want = preset === "carpet" ? "_carpet" : preset === "concrete" ? "_concrete" : preset === "wool" ? "_wool" : null;
		return (want && blocks.find((b) => b.endsWith(want))) || blocks[0];
	};

	// ------------------------------------------------------------------ colour maths
	const lin = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
	function lab([r, g, b]) {
		const R = lin(r), G = lin(g), B = lin(b);
		let x = (R * 0.4124 + G * 0.3576 + B * 0.1805) / 0.95047, y = R * 0.2126 + G * 0.7152 + B * 0.0722, z = (R * 0.0193 + G * 0.1192 + B * 0.9505) / 1.08883;
		const f = (t) => t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116;
		x = f(x); y = f(y); z = f(z);
		return [116 * y - 16, 500 * (x - y), 200 * (y - z)];
	}
	function nearest(palette, rgb) {
		const L1 = lab(rgb);
		let best = 0, bd = Infinity;
		for (let i = 0; i < palette.length; i++) {
			const p = palette[i].lab, d = (p[0] - L1[0]) ** 2 + (p[1] - L1[1]) ** 2 + (p[2] - L1[2]) ** 2;
			if (d < bd) { bd = d; best = i; }
		}
		return best;
	}
	const BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];
	/** Image data -> palette index per pixel, with optional dithering. */
	function quantize(img, w, h, palette, dither) {
		const px = new Float32Array(w * h * 3);
		for (let i = 0; i < w * h; i++) { px[i * 3] = img.data[i * 4]; px[i * 3 + 1] = img.data[i * 4 + 1]; px[i * 3 + 2] = img.data[i * 4 + 2]; }
		const alpha = (i) => img.data[i * 4 + 3];
		const out = new Int32Array(w * h);
		for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
			const i = y * w + x;
			if (alpha(i) < 128) { out[i] = -1; continue; }
			let r = px[i * 3], g = px[i * 3 + 1], b = px[i * 3 + 2];
			if (dither === "ordered") { const t = (BAYER[y & 3][x & 3] / 16 - 0.5) * 48; r += t; g += t; b += t; }
			const k = nearest(palette, [Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b))]);
			out[i] = k;
			if (dither === "floyd") {
				const c = palette[k].rgb, er = r - c[0], eg = g - c[1], eb = b - c[2];
				const add = (xx, yy, f) => { if (xx < 0 || xx >= w || yy >= h) return; const j = (yy * w + xx) * 3; px[j] += er * f; px[j + 1] += eg * f; px[j + 2] += eb * f; };
				add(x + 1, y, 7 / 16); add(x - 1, y + 1, 3 / 16); add(x, y + 1, 5 / 16); add(x + 1, y + 1, 1 / 16);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ .litematic writer
	function nbtWriter() {
		const parts = [];
		let size = 0;
		const push = (u8) => { parts.push(u8); size += u8.length; };
		const b = (v) => push(new Uint8Array([v & 255]));
		const s16 = (v) => { const a = new Uint8Array(2); new DataView(a.buffer).setInt16(0, v); push(a); };
		const s32 = (v) => { const a = new Uint8Array(4); new DataView(a.buffer).setInt32(0, v); push(a); };
		const s64 = (v) => { const a = new Uint8Array(8); new DataView(a.buffer).setBigInt64(0, BigInt.asIntN(64, BigInt(v))); push(a); };
		const str = (t) => { const e = new TextEncoder().encode(t); s16(e.length); push(e); };
		const tag = (type, name) => { b(type); str(name); };
		return {
			compound(name, fn) { tag(10, name); fn(); b(0); },
			end() { b(0); },
			int(name, v) { tag(3, name); s32(v); },
			long(name, v) { tag(4, name); s64(v); },
			string(name, v) { tag(8, name); str(v); },
			listOfCompounds(name, items, fn) { tag(9, name); b(items.length ? 10 : 0); s32(items.length); for (const it of items) { fn(it); b(0); } },
			emptyList(name) { tag(9, name); b(0); s32(0); },
			longArray(name, arr) { tag(12, name); s32(arr.length); for (const v of arr) s64(v); },
			raw: { str, b },
			bytes() { const out = new Uint8Array(size); let o = 0; for (const p of parts) { out.set(p, o); o += p.length; } return out; },
		};
	}
	/** blocks: Map "x,y,z" -> block id; returns gzip'd .litematic bytes. */
	async function litematic(name, sx, sy, sz, blockAt, author) {
		const palette = ["minecraft:air"], index = new Map([["minecraft:air", 0]]);
		const n = sx * sy * sz, ids = new Uint16Array(n);
		let total = 0;
		for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
			const id = blockAt(x, y, z);
			if (!id) continue;
			const full = "minecraft:" + id;
			if (!index.has(full)) { index.set(full, palette.length); palette.push(full); }
			ids[(y * sz + z) * sx + x] = index.get(full);
			total++;
		}
		const bits = BigInt(Math.max(2, 32 - Math.clz32(palette.length - 1)));
		const longs = new BigInt64Array(Math.ceil(n * Number(bits) / 64));
		for (let i = 0; i < n; i++) {
			const v = BigInt(ids[i]);
			if (!v) continue;
			const start = BigInt(i) * bits, li = Number(start >> 6n), off = start & 63n;
			longs[li] = BigInt.asIntN(64, longs[li] | (v << off));
			if (off + bits > 64n) longs[li + 1] = BigInt.asIntN(64, longs[li + 1] | (v >> (64n - off)));
		}
		const w = nbtWriter();
		w.raw.b(10); w.raw.str("");
		const now = Date.now();
		w.int("Version", 6);
		w.int("SubVersion", 1);
		w.int("MinecraftDataVersion", 4325);
		w.compound("Metadata", () => {
			w.string("Name", name); w.string("Author", author || "velo-client.com"); w.string("Description", "Made with velo-client.com/library");
			w.int("RegionCount", 1); w.int("TotalVolume", n); w.int("TotalBlocks", total); w.long("TimeCreated", now); w.long("TimeModified", now);
			w.compound("EnclosingSize", () => { w.int("x", sx); w.int("y", sy); w.int("z", sz); });
		});
		w.compound("Regions", () => {
			w.compound(name.replace(/[^\w -]/g, "") || "Region", () => {
				w.compound("Position", () => { w.int("x", 0); w.int("y", 0); w.int("z", 0); });
				w.compound("Size", () => { w.int("x", sx); w.int("y", sy); w.int("z", sz); });
				w.listOfCompounds("BlockStatePalette", palette, (p) => { w.raw.b(8); w.raw.str("Name"); w.raw.str(p); });
				w.longArray("BlockStates", longs);
				w.emptyList("TileEntities"); w.emptyList("Entities"); w.emptyList("PendingBlockTicks"); w.emptyList("PendingFluidTicks");
			});
		});
		w.end();
		const gz = await new Response(new Blob([w.bytes()]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
		return new Uint8Array(gz);
	}

	// ------------------------------------------------------------------ shared UI: output buttons, materials, 3D
	let last = null; // {name, bytes, sx, sy, sz, at, counts}
	function materialsBox(counts) {
		const list = [...counts.entries()].sort((a, b) => b[1] - a[1]);
		const total = list.reduce((a, [, n]) => a + n, 0);
		const fmt = (n) => { const st = Math.floor(n / 64), r = n % 64, sh = n / 1728; return (st ? st + "×64" + (r ? " + " + r : "") : "") + (sh >= 1 ? " · " + sh.toFixed(1) + " shulkers" : ""); };
		$("#mats").replaceChildren(
			el("div", { style: { display: "flex", justifyContent: "space-between", alignItems: "baseline" } }, el("h3", {}, "Materials"), el("span", { class: "lib-muted" }, total.toLocaleString() + " blocks · " + list.length + " kinds")),
			el("div", { style: { maxHeight: "420px", overflow: "auto", border: "1px solid var(--line)", borderRadius: "12px" } },
				...list.map(([id, n], i) => el("div", { style: { display: "flex", alignItems: "center", gap: "10px", padding: "7px 12px", borderTop: i ? "1px solid var(--line)" : "0" } },
					el("img", { src: "/v2/item/" + id + ".png?size=32", alt: "", class: "lib-px", style: { width: "24px", height: "24px" }, loading: "lazy" }),
					el("span", { style: { flexGrow: 1, fontSize: "13px" } }, id.replace(/_/g, " ")),
					el("span", { style: { font: "500 12px var(--mono)", textAlign: "right" } }, n.toLocaleString(), el("br"), el("span", { class: "lib-muted", style: { fontSize: "11px" } }, fmt(n)))))),
			el("button", { type: "button", class: "lib-btn sm", style: { alignSelf: "flex-start" }, onclick: () => copy(list.map(([id, n]) => n + " " + id.replace(/_/g, " ")).join("\n"), "Material list copied") }, "Copy list"));
	}
	let viewer = null;
	async function show3d() {
		if (!last) return;
		if (!window.velo || !window.velo.schem3d) await new Promise((res) => addEventListener("velo:schem3d", res, { once: true }));
		if (viewer) viewer.dispose();
		const box = $("#view3d");
		box.hidden = false;
		viewer = await window.velo.schem3d(box, { sx: last.sx, sy: last.sy, sz: last.sz, at: (x, y, z) => last.at(x, y, z) || "air" });
	}
	function wireOutput() {
		$("#dl").addEventListener("click", () => {
			if (!last) return;
			const a = el("a", { href: URL.createObjectURL(new Blob([last.bytes])), download: last.name + ".litematic" });
			a.click();
		});
		$("#load").addEventListener("click", async () => {
			if (!last) return;
			try {
				const r = await fetch("/v2/schem?name=" + encodeURIComponent(last.name), { method: "POST", body: last.bytes, headers: { "Content-Type": "application/octet-stream" } });
				const d = await r.json();
				if (!r.ok) throw new Error(d.error || "HTTP " + r.status);
				$("#cmd").textContent = d.command;
				$("#cmdbox").hidden = false;
				copy(d.command, "Command copied - paste it in the game chat");
			} catch (e) { toast(e.message); }
		});
		$("#show3d").addEventListener("click", show3d);
		$("#share").addEventListener("click", async () => {
			if (!last) return;
			if (!signedIn()) { location.href = "/store#signin"; return; }
			const title = prompt("Title for the community:", last.name);
			if (!title) return;
			try {
				let bin = "";
				for (let i = 0; i < last.bytes.length; i += 32768) bin += String.fromCharCode(...last.bytes.subarray(i, i + 32768));
				const preview = $("#result").toDataURL("image/png").split(",")[1];
				const r = await fetch("/v1/schematics/upload", { method: "POST", headers: { "Content-Type": "application/json", Authorization: "Bearer " + L.token() },
					body: JSON.stringify({ title, description: root.dataset.tool === "mapart" ? "Map art made with velo-client.com/library/mapart" : "Pixel art made with velo-client.com/library/pixelart",
						tags: [root.dataset.tool === "mapart" ? "map art" : "pixel art"], fileName: last.name + ".litematic", file: btoa(bin), preview }) });
				const d = await r.json().catch(() => ({}));
				if (!r.ok) throw new Error(d.error || "HTTP " + r.status);
				toast("Shared with the community");
			} catch (e) { toast(e.message); }
		});
	}
	const loadImage = (file) => new Promise((res, rej) => { const im = new Image(); im.onload = () => res(im); im.onerror = () => rej(new Error("can't read that picture")); im.src = URL.createObjectURL(file); });
	function scaled(im, w, h, fit) {
		const c = document.createElement("canvas");
		c.width = w; c.height = h;
		const g = c.getContext("2d");
		g.imageSmoothingQuality = "high";
		const r = im.width / im.height, R = w / h;
		let dw = w, dh = h, dx = 0, dy = 0;
		if (fit === "contain") { if (r > R) { dh = w / r; dy = (h - dh) / 2; } else { dw = h * r; dx = (w - dw) / 2; } }
		else if (fit === "cover") { if (r > R) { dw = h * r; dx = (w - dw) / 2; } else { dh = w / r; dy = (h - dh) / 2; } }
		g.drawImage(im, dx, dy, dw, dh);
		return g.getImageData(0, 0, w, h);
	}

	// ================================================================== MAP ART
	function mapart() {
		const st = { mapsX: 1, mapsY: 1, mode: "staircase", dither: "floyd", fit: "cover", preset: "easy", support: "cobblestone", enabled: new Set(PRESETS_MAP.easy()), block: {} };
		MAP.forEach((m) => { st.block[m[0]] = m[3][0]; });
		let image = null;
		const run = debounce(generate, 250);
		function palette() {
			const out = [];
			for (const m of MAP) {
				if (!st.enabled.has(m[0])) continue;
				const shades = st.mode === "flat" ? [1] : [0, 1, 2];
				for (const s of shades) {
					const rgb = m[2].map((c) => Math.floor(c * SHADE[s] / 255));
					out.push({ id: m[0], shade: s, rgb, lab: lab(rgb), block: st.block[m[0]] });
				}
			}
			return out;
		}
		async function generate() {
			if (!image) return;
			const W = st.mapsX * 128, H = st.mapsY * 128;
			const pal = palette();
			if (!pal.length) { toast("Turn on some colours"); return; }
			const q = quantize(scaled(image, W, H, st.fit), W, H, pal, st.dither);
			// preview as it looks on the map
			const c = $("#result");
			c.width = W; c.height = H;
			c.style.width = Math.min(768, W * Math.max(1, Math.floor(768 / W))) + "px";
			const g = c.getContext("2d"), out = g.createImageData(W, H);
			for (let i = 0; i < W * H; i++) { const p = q[i] < 0 ? null : pal[q[i]]; const rgb = p ? p.rgb : [0, 0, 0]; out.data.set([rgb[0], rgb[1], rgb[2], p ? 255 : 0], i * 4); }
			g.putImageData(out, 0, 0);
			// build the blocks: one row of extra blocks on the north side (z = 0) sets the first row's shade
			const heights = new Int32Array(W * (H + 1));
			let maxH = 0;
			for (let x = 0; x < W; x++) {
				let h = 0, min = 0, max = 0;
				const col = [0];
				for (let z = 0; z < H; z++) {
					const p = q[z * W + x] < 0 ? null : pal[q[z * W + x]];
					if (st.mode !== "flat" && p) h += p.shade === 2 ? 1 : p.shade === 0 ? -1 : 0;
					col.push(h);
					min = Math.min(min, h); max = Math.max(max, h);
				}
				for (let z = 0; z <= H; z++) heights[z * W + x] = col[z] - min;
				maxH = Math.max(maxH, max - min);
			}
			const supportNeeded = pal.some((p) => NEEDS_SUPPORT.test(p.block));
			const base = supportNeeded ? 1 : 0, SY = maxH + 1 + base, SZ = H + 1;
			const counts = new Map();
			const cells = new Map();
			const put = (x, y, z, id) => { cells.set((y * SZ + z) * W + x, id); counts.set(id, (counts.get(id) || 0) + 1); };
			for (let x = 0; x < W; x++) {
				// the north row: any solid block
				put(x, heights[x] + base, 0, st.support);
				for (let z = 0; z < H; z++) {
					const p = q[z * W + x] < 0 ? null : pal[q[z * W + x]];
					if (!p) continue;
					const y = heights[(z + 1) * W + x] + base;
					put(x, y, z + 1, p.block);
					if (NEEDS_SUPPORT.test(p.block)) put(x, y - 1, z + 1, st.support);
				}
			}
			const name = ($("#name").value || "map art").slice(0, 40);
			const at = (x, y, z) => cells.get((y * SZ + z) * W + x);
			const bytes = await litematic(name, W, SY, SZ, at);
			last = { name, bytes, sx: W, sy: SY, sz: SZ, at, counts };
			$("#info").textContent = `${W} × ${H} blocks (${st.mapsX} × ${st.mapsY} map${st.mapsX * st.mapsY > 1 ? "s" : ""}) · ${st.mode === "flat" ? "flat" : "staircase, up to " + maxH + " blocks high"} · ${pal.length} colours · ${(bytes.length / 1024).toFixed(0)} KB`;
			materialsBox(counts);
			$("#output").hidden = false;
			if (viewer) show3d();
		}
		function drawPalette() {
			$("#palette").replaceChildren(...MAP.map((m) => {
				const on = st.enabled.has(m[0]);
				const sel = el("select", { "aria-label": m[1] + " block", style: { minHeight: "32px", padding: "0 6px", fontSize: "12px" }, onchange: (e) => { st.block[m[0]] = e.target.value; drawPalette(); run(); } },
					...m[3].map((b) => el("option", { value: b, selected: st.block[m[0]] === b }, b.replace(/_/g, " "))));
				return el("label", { class: "lib-box", style: { display: "flex", alignItems: "center", gap: "8px", padding: "6px 8px", opacity: on ? "1" : ".45" } },
					el("input", { type: "checkbox", checked: on, onchange: (e) => { e.target.checked ? st.enabled.add(m[0]) : st.enabled.delete(m[0]); drawPalette(); run(); } }),
					el("span", { style: { width: "18px", height: "18px", borderRadius: "4px", background: `rgb(${m[2].join(",")})`, flexShrink: 0 } }),
					el("img", { src: "/v2/item/" + st.block[m[0]] + ".png?size=32", alt: "", class: "lib-px", style: { width: "20px", height: "20px" } }),
					sel);
			}));
		}
		function draw() {
			pills($("#modes"), [["staircase", "Staircase (3× colours)"], ["flat", "Flat (one height)"]], st.mode, (k) => { st.mode = k; draw(); run(); });
			pills($("#sizes"), [["1x1", "1×1"], ["2x1", "2×1"], ["2x2", "2×2"], ["3x2", "3×2"], ["3x3", "3×3"], ["4x4", "4×4"]], st.mapsX + "x" + st.mapsY, (k) => { [st.mapsX, st.mapsY] = k.split("x").map(Number); draw(); run(); });
			pills($("#dithers"), [["floyd", "Smooth (Floyd–Steinberg)"], ["ordered", "Pattern"], ["none", "None"]], st.dither, (k) => { st.dither = k; draw(); run(); });
			pills($("#fits"), [["cover", "Fill"], ["contain", "Fit"], ["stretch", "Stretch"]], st.fit, (k) => { st.fit = k; draw(); run(); });
			pills($("#presets"), [["easy", "Easy blocks"], ["all", "Every colour"], ["wool", "Wool"], ["carpet", "Carpet only"], ["concrete", "Concrete"], ["terracotta", "Terracotta"]], st.preset, (k) => {
				st.preset = k; st.enabled = new Set(PRESETS_MAP[k]());
				MAP.forEach((m) => { st.block[m[0]] = preferFor(k, m[3]); });
				if (k === "carpet") st.mode = "flat";
				draw(); drawPalette(); run();
			});
		}
		$("#file").addEventListener("change", async (e) => { const f = e.target.files[0]; if (!f) return; try { image = await loadImage(f); $("#name").value = $("#name").value || f.name.replace(/\.[a-z]+$/i, "").slice(0, 40); $("#orig").src = image.src; generate(); } catch (err) { toast(err.message); } });
		$("#support").addEventListener("change", (e) => { st.support = e.target.value; run(); });
		if (L.qs.get("img") && L.qs.get("img").startsWith("/")) fetch(L.qs.get("img")).then((r) => r.blob()).then(loadImage).then((im) => { image = im; $("#orig").src = im.src; generate(); });
		$("#name").addEventListener("input", run);
		draw(); drawPalette(); wireOutput();
	}

	// ================================================================== PIXEL ART & TEXT
	const SETS = {
		wool: ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"].map((c) => c + "_wool"),
		concrete: ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"].map((c) => c + "_concrete"),
		terracotta: ["terracotta", ...["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"].map((c) => c + "_terracotta")],
		wood: ["oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks", "bamboo_planks", "crimson_planks", "warped_planks", "stripped_oak_log", "stripped_birch_log"],
		stone: ["stone", "cobblestone", "smooth_stone", "andesite", "diorite", "granite", "polished_andesite", "polished_diorite", "polished_granite", "deepslate", "cobbled_deepslate", "tuff", "calcite", "blackstone", "basalt", "sandstone", "red_sandstone", "end_stone", "quartz_block", "obsidian"],
		bright: ["gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block", "iron_block", "copper_block", "amethyst_block", "glowstone", "sea_lantern", "shroomlight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight", "honeycomb_block", "slime_block"],
	};
	async function blockColor(id, face) {
		const f = await fetch("/v2/blockfaces?ids=" + id).then((r) => r.json()).catch(() => ({}));
		const info = f[id];
		if (!info) return null;
		const tex = face === "top" ? info.top : info.side;
		return new Promise((res) => {
			const im = new Image();
			im.crossOrigin = "anonymous";
			im.onload = () => {
				const c = document.createElement("canvas"); c.width = im.width; c.height = im.width;
				const g = c.getContext("2d"); g.drawImage(im, 0, 0);
				const d = g.getImageData(0, 0, im.width, im.width).data;
				let r = 0, gg = 0, b = 0, n = 0;
				for (let i = 0; i < d.length; i += 4) if (d[i + 3] > 200) { r += d[i]; gg += d[i + 1]; b += d[i + 2]; n++; }
				res(n ? [r / n, gg / n, b / n] : null);
			};
			im.onerror = () => res(null);
			im.src = "/v2/blocktex/" + tex + ".png";
		});
	}
	let pixelRun = 0;
	function pixelart() {
		const st = { source: "image", width: 64, orient: "wall", dither: "none", sets: new Set(["wool", "concrete"]), text: "VELO", font: "800 64px Archivo", textBlock: "red_concrete", bg: "", outline: "black_concrete" };
		const colorCache = {};
		let image = null;
		const run = debounce(generate, 300);
		async function palette() {
			const ids = [...new Set([...st.sets].flatMap((s) => SETS[s]))];
			const face = st.orient === "floor" ? "top" : "side";
			const out = [];
			await Promise.all(ids.map(async (id) => {
				const k = id + "|" + face;
				if (!(k in colorCache)) colorCache[k] = await blockColor(id, face);
				if (colorCache[k]) out.push({ block: id, rgb: colorCache[k], lab: lab(colorCache[k]) });
			}));
			return out.sort((a, b) => a.block.localeCompare(b.block));
		}
		function textImage() {
			const c = document.createElement("canvas");
			const g = c.getContext("2d");
			const fontPx = Math.max(6, Math.round(st.width / Math.max(1, st.text.length) * 1.6));
			const font = st.font.replace(/\d+px/, fontPx + "px");
			g.font = font;
			const lines = st.text.split("\n");
			const w = Math.ceil(Math.max(...lines.map((l) => g.measureText(l).width))) + 4, h = Math.ceil(fontPx * 1.15 * lines.length) + 4;
			c.width = w; c.height = h;
			g.font = font; g.textBaseline = "top"; g.fillStyle = "#fff";
			lines.forEach((l, i) => g.fillText(l, 2, 2 + i * fontPx * 1.15));
			return { c, w, h };
		}
		async function generate() {
			let W, H, q, pal, mask;
			if (st.source === "text") {
				const t = textImage();
				W = t.w; H = t.h;
				const d = t.c.getContext("2d").getImageData(0, 0, W, H).data;
				mask = new Uint8Array(W * H);
				for (let i = 0; i < W * H; i++) mask[i] = d[i * 4 + 3] > 110 ? 1 : 0;
				// outline: empty cells next to text
				const out = new Uint8Array(W * H);
				if (st.outline) for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
					if (mask[y * W + x]) continue;
					for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) { const xx = x + dx, yy = y + dy; if (xx >= 0 && yy >= 0 && xx < W && yy < H && mask[yy * W + xx]) out[y * W + x] = 1; }
				}
				pal = [{ block: st.textBlock }, { block: st.outline }, { block: st.bg }];
				q = new Int32Array(W * H);
				for (let i = 0; i < W * H; i++) q[i] = mask[i] ? 0 : out[i] && st.outline ? 1 : st.bg ? 2 : -1;
			} else {
				if (!image) return;
				W = st.width; H = Math.max(1, Math.round(st.width * image.height / image.width));
				if (W * H > 256 * 256) { toast("Up to 256 × 256 blocks"); return; }
				pal = await palette();
				if (!pal.length) { toast("Pick at least one block set"); return; }
				q = quantize(scaled(image, W, H, "stretch"), W, H, pal, st.dither);
			}
			// preview with block textures (top or side) - drawn off screen, shown only if no newer run started meanwhile
			const runId = ++pixelRun;
			const c = document.createElement("canvas"), cell = Math.max(2, Math.min(12, Math.floor(900 / Math.max(W, H))));
			c.width = W * cell; c.height = H * cell;
			const g = c.getContext("2d");
			g.imageSmoothingEnabled = false;
			const face = st.orient === "floor" ? "top" : "side";
			const texFor = {};
			const faces = await fetch("/v2/blockfaces?ids=" + [...new Set(pal.map((p) => p.block).filter(Boolean))].join(",")).then((r) => r.json()).catch(() => ({}));
			await Promise.all(pal.filter((p) => p.block).map((p) => new Promise((res) => { const f = faces[p.block]; if (!f) return res(); const im = new Image(); im.onload = () => { texFor[p.block] = im; res(); }; im.onerror = res; im.src = "/v2/blocktex/" + (face === "top" ? f.top : f.side) + ".png"; })));
			const counts = new Map(), cells = new Map();
			for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
				const k = q[y * W + x];
				if (k < 0 || !pal[k].block) continue;
				const id = pal[k].block, im = texFor[id];
				if (im) g.drawImage(im, x * cell, y * cell, cell, cell);
				counts.set(id, (counts.get(id) || 0) + 1);
				// wall: x across, y up (picture's top row = highest); floor: x across, z down
				if (st.orient === "wall") cells.set(((H - 1 - y) * 1 + 0) * W + x, id); else cells.set(y * W + x, id);
			}
			if (runId !== pixelRun) return;
			const shown = $("#result");
			shown.width = c.width; shown.height = c.height;
			shown.getContext("2d").drawImage(c, 0, 0);
			const name = ($("#name").value || (st.source === "text" ? st.text.replace(/\s+/g, " ").slice(0, 30) : "pixel art")).slice(0, 40);
			const sx = W, sy = st.orient === "wall" ? H : 1, sz = st.orient === "wall" ? 1 : H;
			const at = (x, y, z) => st.orient === "wall" ? (z === 0 ? cells.get(y * W + x) : undefined) : (y === 0 ? cells.get(z * W + x) : undefined);
			const bytes = await litematic(name, sx, sy, sz, at);
			last = { name, bytes, sx, sy, sz, at, counts };
			$("#info").textContent = `${W} × ${H} blocks · ${st.orient === "wall" ? "wall" : "floor"} · ${counts.size} block kinds · ${(bytes.length / 1024).toFixed(0)} KB`;
			materialsBox(counts);
			$("#output").hidden = false;
			if (viewer) show3d();
		}
		function draw() {
			pills($("#sources"), [["image", "Picture"], ["text", "Text"]], st.source, (k) => { st.source = k; draw(); run(); });
			$("#src-image").hidden = st.source !== "image";
			$("#src-text").hidden = st.source !== "text";
			pills($("#orients"), [["wall", "Wall (stands up)"], ["floor", "Floor (lies flat)"]], st.orient, (k) => { st.orient = k; draw(); run(); });
			pills($("#dithers"), [["none", "None"], ["floyd", "Smooth"], ["ordered", "Pattern"]], st.dither, (k) => { st.dither = k; draw(); run(); });
			$("#sets").replaceChildren(...Object.keys(SETS).map((k) => el("button", { type: "button", class: "lib-pill", "aria-pressed": st.sets.has(k) ? "true" : "false", onclick: () => { st.sets.has(k) ? st.sets.delete(k) : st.sets.add(k); draw(); run(); } }, k[0].toUpperCase() + k.slice(1))));
			$("#widthv").textContent = st.width + " blocks";
		}
		const blockSelect = (id, key, allowNone) => {
			const all = [...new Set(Object.values(SETS).flat())].sort();
			const s = $("#" + id);
			s.replaceChildren(...(allowNone ? [el("option", { value: "" }, "none")] : []), ...all.map((b) => el("option", { value: b, selected: st[key] === b }, b.replace(/_/g, " "))));
			s.addEventListener("change", () => { st[key] = s.value; run(); });
		};
		blockSelect("tblock", "textBlock", false);
		blockSelect("oblock", "outline", true);
		blockSelect("bblock", "bg", true);
		$("#file").addEventListener("change", async (e) => { const f = e.target.files[0]; if (!f) return; try { image = await loadImage(f); $("#orig").src = image.src; $("#name").value = $("#name").value || f.name.replace(/\.[a-z]+$/i, "").slice(0, 40); generate(); } catch (err) { toast(err.message); } });
		$("#width").addEventListener("input", (e) => { st.width = +e.target.value; draw(); run(); });
		$("#text").addEventListener("input", (e) => { st.text = e.target.value || " "; run(); });
		$("#font").addEventListener("change", (e) => { st.font = e.target.value; run(); });
		$("#name").addEventListener("input", run);
		if (L.qs.get("text")) { st.source = "text"; st.text = L.qs.get("text"); $("#text").value = st.text; }
		draw(); wireOutput();
		if (L.qs.get("img") && L.qs.get("img").startsWith("/")) fetch(L.qs.get("img")).then((r) => r.blob()).then(loadImage).then((im) => { image = im; $("#orig").src = im.src; generate(); });
		if (st.source === "text") run();
	}

	(root.dataset.tool === "mapart" ? mapart : pixelart)();
})();
