#!/usr/bin/env node
/**
 * Builds the static site from src/*.html + site.config.json into dist/.
 * Plain token substitution ({{dot.path}}) - no templating engine, no deps,
 * so `npm run build` works with nothing but Node itself installed.
 */
const fs = require("fs");
const path = require("path");

const ROOT = __dirname;
const SRC_DIR = path.join(ROOT, "src");
const ASSETS_DIR = path.join(ROOT, "assets");
const DOWNLOADS_DIR = path.join(ROOT, "downloads");
const DIST_DIR = path.join(ROOT, "dist");
const CONFIG_FILE = path.join(ROOT, "site.config.json");

function flatten(obj, prefix, out) {
	for (const [key, value] of Object.entries(obj)) {
		if (key.startsWith("_")) continue; // _comment fields etc. - not real config
		const flatKey = prefix ? `${prefix}.${key}` : key;
		if (value !== null && typeof value === "object" && !Array.isArray(value)) {
			flatten(value, flatKey, out);
		} else {
			out[flatKey] = String(value);
		}
	}
	return out;
}

function isTruthy(value) {
	return value !== undefined && !["false", "0", ""].includes(value);
}

/** Resolves {{#if a.b}}...{{/if}} and {{#ifnot a.b}}...{{/ifnot}} blocks against the flattened config, before plain {{token}} substitution runs. Not nestable - this is a static site with a handful of toggles, not a template language. */
function applyConditionals(content, tokens) {
	content = content.replace(/\{\{#if\s+([\w.]+)\}\}([\s\S]*?)\{\{\/if\}\}/g, (_, key, inner) =>
		isTruthy(tokens[key]) ? inner : ""
	);
	content = content.replace(/\{\{#ifnot\s+([\w.]+)\}\}([\s\S]*?)\{\{\/ifnot\}\}/g, (_, key, inner) =>
		isTruthy(tokens[key]) ? "" : inner
	);
	return content;
}

function rmrf(dir) {
	if (fs.existsSync(dir)) fs.rmSync(dir, { recursive: true, force: true });
}

function copyDir(src, dest) {
	if (!fs.existsSync(src)) return;
	fs.mkdirSync(dest, { recursive: true });
	for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
		const s = path.join(src, entry.name);
		const d = path.join(dest, entry.name);
		if (entry.isDirectory()) copyDir(s, d);
		else fs.copyFileSync(s, d);
	}
}

function main() {
	const config = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf8"));
	const tokens = flatten(config, "", {});

	// Flag anything that still looks like a placeholder, so it's obvious
	// before deploying - a real name/address is legally required for the
	// Imprint page to actually mean anything.
	const placeholderish = Object.entries(tokens).filter(
		([k, v]) => /YOUR |example\.com|^$/.test(v) && k.startsWith("legal.")
	);

	rmrf(DIST_DIR);
	fs.mkdirSync(DIST_DIR, { recursive: true });

	const htmlFiles = fs.readdirSync(SRC_DIR).filter((f) => f.endsWith(".html"));
	for (const file of htmlFiles) {
		let content = fs.readFileSync(path.join(SRC_DIR, file), "utf8");
		content = applyConditionals(content, tokens);
		content = content.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (match, key) => {
			if (!(key in tokens)) {
				console.warn(`  ! ${file}: no config value for {{${key}}}`);
				return match;
			}
			return tokens[key];
		});
		fs.writeFileSync(path.join(DIST_DIR, file), content);
	}

	copyDir(ASSETS_DIR, path.join(DIST_DIR, "assets"));
	// Only publish the jar once downloads are actually turned on - an
	// unlinked-but-served file at a guessable URL isn't really hidden.
	if (isTruthy(tokens["status.downloadsEnabled"])) {
		copyDir(DOWNLOADS_DIR, path.join(DIST_DIR, "downloads"));
	}

	console.log(`Built ${htmlFiles.length} page(s) -> dist/`);
	if (placeholderish.length) {
		console.warn("\nStill using placeholder legal info (fine for a preview, fix before going to production):");
		for (const [k, v] of placeholderish) console.warn(`  - ${k}: "${v}"`);
	}
}

main();
