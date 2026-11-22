/**
 * Post-build speed pass over dist/ (called by build.js after every page is written):
 *
 * - CSS: each page's stylesheets (site.css + its own) are purged of rules for classes/ids that neither the page's HTML
 *   nor the scripts it loads ever mention, minified, and put inline in <style> - no render-blocking CSS request.
 *   Conservative: a selector is dropped only when one of its class or id names appears nowhere in that text (class
 *   names in the scripts are always whole string literals, so they're found).
 * - JS: every first-party script is minified (esbuild); vendor/*.min.js stay as they are.
 * - Every /assets/... file a page links (src, href, srcset, inline url()) gets ?v=<content hash>, so nginx can cache it
 *   for a year (immutable) and a changed file still shows up at once.
 * - A .gz (level 9) next to every text file for nginx's gzip_static.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const zlib = require("zlib");

let esbuild = null;
try {
	esbuild = require("esbuild");
} catch (e) {
	console.warn("  ! esbuild missing (npm install in velo-site) - CSS/JS stay unminified");
}

function walk(dir, out = []) {
	for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
		const p = path.join(dir, e.name);
		if (e.isDirectory()) walk(p, out); else out.push(p);
	}
	return out;
}

// ---- CSS purge ----

/** Splits a CSS text into top-level blocks: {prelude, body} (body null for statements like @import). */
function blocks(css) {
	const out = [];
	let i = 0;
	while (i < css.length) {
		const open = css.indexOf("{", i);
		const semi = css.indexOf(";", i);
		if (open < 0) break;
		if (semi >= 0 && semi < open && css.slice(i, semi).trim().startsWith("@")) {
			out.push({ prelude: css.slice(i, semi + 1).trim(), body: null });
			i = semi + 1;
			continue;
		}
		let depth = 0;
		let j = open;
		let quote = null;
		for (; j < css.length; j++) {
			const c = css[j];
			if (quote) {
				if (c === "\\") j++;
				else if (c === quote) quote = null;
				continue;
			}
			if (c === '"' || c === "'") quote = c;
			else if (c === "{") depth++;
			else if (c === "}" && --depth === 0) break;
		}
		out.push({ prelude: css.slice(i, open).trim(), body: css.slice(open + 1, j) });
		i = j + 1;
	}
	return out;
}

/** Splits a selector list on top-level commas (not inside :is(), :not(), [attr="a,b"]). */
function splitSelectors(s) {
	const out = [];
	let depth = 0;
	let start = 0;
	let quote = null;
	for (let i = 0; i < s.length; i++) {
		const c = s[i];
		if (quote) {
			if (c === quote) quote = null;
			continue;
		}
		if (c === '"' || c === "'") quote = c;
		else if (c === "(" || c === "[") depth++;
		else if (c === ")" || c === "]") depth--;
		else if (c === "," && depth === 0) {
			out.push(s.slice(start, i).trim());
			start = i + 1;
		}
	}
	out.push(s.slice(start).trim());
	return out;
}

function selectorUsed(sel, words) {
	// Names inside :not(...) don't have to exist for the rule to match.
	const plain = sel.replace(/:not\((?:[^()]|\([^()]*\))*\)/g, "").replace(/\[[^\]]*\]/g, "");
	const names = plain.match(/[.#](-?[_a-zA-Z][\w-]*)/g) || [];
	return names.every((n) => words.has(n.slice(1)));
}

function purge(css, words) {
	let out = "";
	for (const b of blocks(css)) {
		if (b.body === null) {
			out += b.prelude;
			continue;
		}
		if (b.prelude.startsWith("@")) {
			if (/^@(media|supports|container|layer)\b/.test(b.prelude)) {
				const inner = purge(b.body, words);
				if (inner.trim()) out += `${b.prelude}{${inner}}`;
			} else {
				out += `${b.prelude}{${b.body}}`; // @font-face, @keyframes, @view-transition, @property...
			}
			continue;
		}
		const kept = splitSelectors(b.prelude).filter((sel) => selectorUsed(sel, words));
		if (kept.length) out += `${kept.join(",")}{${b.body}}`;
	}
	return out;
}

/** Every word that could be a class or id name in this text. */
function wordsOf(text, into) {
	for (const w of text.match(/[_a-zA-Z][\w-]*/g) || []) into.add(w);
	return into;
}

/** Class and id names a page's HTML uses (class="..." / id="..." values only, not the page text). */
function htmlNames(html, into) {
	for (const m of html.matchAll(/\s(?:class|id)="([^"]*)"/g)) wordsOf(m[1], into);
	return into;
}

/** Words inside a script's string literals - where its class names live (never in identifiers). */
function scriptNames(js, into) {
	for (const m of js.matchAll(/"((?:[^"\\\n]|\\.)*)"|'((?:[^'\\\n]|\\.)*)'|`((?:[^`\\]|\\.)*)`/g)) wordsOf(m[1] || m[2] || m[3] || "", into);
	return into;
}

function minCss(css) {
	return esbuild ? esbuild.transformSync(css, { loader: "css", minify: true }).code.trim() : css;
}

// ---- main pass ----

function optimize(dist) {
	const assets = path.join(dist, "assets");
	const hashCache = new Map();
	const hashOf = (file) => {
		if (!hashCache.has(file)) hashCache.set(file, crypto.createHash("sha1").update(fs.readFileSync(file)).digest("hex").slice(0, 10));
		return hashCache.get(file);
	};
	const local = (url) => {
		const clean = url.split(/[?#]/)[0];
		const file = path.join(dist, decodeURIComponent(clean));
		return clean.startsWith("/assets/") && fs.existsSync(file) && fs.statSync(file).isFile() ? file : null;
	};

	// 1. Minify first-party scripts (their ?v= stays the source hash from build.js - still unique per version).
	if (esbuild) {
		for (const file of walk(assets).filter((f) => f.endsWith(".js") && !f.includes(`${path.sep}vendor${path.sep}`) && !f.endsWith(".min.js"))) {
			const src = fs.readFileSync(file, "utf8");
			try {
				const res = esbuild.transformSync(src, { loader: "js", minify: true, target: "es2020", legalComments: "none" });
				fs.writeFileSync(file, res.code);
			} catch (e) {
				console.warn(`  ! couldn't minify ${path.relative(dist, file)}: ${e.message.split("\n")[0]}`);
			}
		}
		for (const file of walk(assets).filter((f) => f.endsWith(".css"))) {
			fs.writeFileSync(file, minCss(fs.readFileSync(file, "utf8")));
		}
	}
	const jsText = new Map();
	const scriptText = (url) => {
		const file = local(url);
		if (!file) return "";
		if (!jsText.has(file)) jsText.set(file, fs.readFileSync(file, "utf8"));
		return jsText.get(file);
	};

	let realWidths = {};
	try {
		realWidths = JSON.parse(fs.readFileSync(path.join(dist, ".image-widths.json"), "utf8"));
		fs.unlinkSync(path.join(dist, ".image-widths.json"));
	} catch (e) {
		// no images.py run
	}
	// 2. Pages: inline purged CSS, version every asset link.
	let before = 0;
	let after = 0;
	for (const file of walk(dist).filter((f) => f.endsWith(".html"))) {
		let html = fs.readFileSync(file, "utf8");
		const sheets = [...html.matchAll(/<link rel="stylesheet" href="(\/assets\/[^"]+\.css)(?:\?[^"]*)?">/g)];
		if (sheets.length) {
			const words = htmlNames(html, new Set());
			// The scripts this page loads (and the ones it loads later, named in data-lazy-script) build the rest.
			const scripts = [...html.matchAll(/(?:src|data-lazy-script)="(\/assets\/[^"]+\.js)[^"]*"/g)].map((m) => m[1]);
			for (const s of scripts) scriptNames(scriptText(s), words);
			let css = "";
			for (const m of sheets) {
				const f = local(m[1]);
				if (f) css += fs.readFileSync(f, "utf8") + "\n";
			}
			before += css.length;
			css = minCss(purge(minCss(css), words));
			after += css.length;
			// The first sheet's link becomes the <style>; the others go.
			html = html.replace(sheets[0][0], `<style>${css.replace(/<\/style/gi, "<\\/style")}</style>`);
			for (const m of sheets.slice(1)) html = html.replace(m[0], "");
		}
		// Photos with WebP variants next to them (images.py): srcset + sizes, so phones get the small one.
		html = html.replace(/<img\b([^>]*?)\ssrc="(\/(?:news|assets\/partners)\/[^"?]+)\.(jpe?g|png|webp)"([^>]*)>/g, (m, pre, base, ext, post) => {
			if (/\ssrcset=/.test(m)) return m;
			const widths = [160, 480, 960].filter((w) => fs.existsSync(path.join(dist, `${base}-${w}.webp`)));
			if (!widths.length) return m;
			const orig = path.join(dist, `${base}.${ext}`);
			const intrinsic = Number((m.match(/\swidth="(\d+)"/) || [])[1]) || 1600;
			const list = widths.map((w) => `${base}-${w}.webp?v=${hashOf(path.join(dist, `${base}-${w}.webp`))} ${w}w`);
			const real = realWidths[`${base}.${ext}`] || intrinsic;
			if (real > 960 || !widths.includes(960)) list.push(`${base}.${ext}?v=${hashOf(orig)} ${real}w`); // the original stays the largest choice
			const sizes = /\ssizes=/.test(m) ? "" : ` sizes="${intrinsic <= 200 ? intrinsic + "px" : `(max-width: 760px) 100vw, ${Math.min(intrinsic, 1240)}px`}"`;
			return `<img${pre} src="${base}.${ext}?v=${hashOf(orig)}" srcset="${list.join(", ")}"${sizes}${post}>`;
		});
		// ?v=<hash> on every local asset that has none yet.
		const stamp = (url) => {
			if (url.includes("?")) return url;
			const f = local(url);
			return f ? `${url}?v=${hashOf(f)}` : url;
		};
		html = html.replace(/(\s(?:src|href|poster|data-src)=")(\/assets\/[^"?#]+)(")/g, (m, a, url, b) => a + stamp(url) + b);
		html = html.replace(/(\s(?:srcset|imagesrcset)=")([^"]+)(")/g, (m, a, list, b) => a + list.split(",").map((part) => {
			const [url, ...rest] = part.trim().split(/\s+/);
			return [stamp(url), ...rest].join(" ");
		}).join(", ") + b);
		html = html.replace(/url\((["']?)(\/assets\/[^"')?#]+)\1\)/g, (m, q, url) => `url(${q}${stamp(url)}${q})`);
		fs.writeFileSync(file, html);
	}

	// 3. gzip_static copies.
	let gz = 0;
	for (const file of walk(dist).filter((f) => /\.(html|css|js|mjs|svg|json|xml|txt|webmanifest)$/.test(f))) {
		const data = fs.readFileSync(file);
		if (data.length < 512) continue;
		fs.writeFileSync(file + ".gz", zlib.gzipSync(data, { level: 9 }));
		gz++;
	}
	console.log(`Optimized: CSS ${Math.round(before / 1024)} KB -> ${Math.round(after / 1024)} KB inline (all pages), ${gz} .gz files`);
}

module.exports = { optimize };
