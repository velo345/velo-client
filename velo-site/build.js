#!/usr/bin/env node
/**
 * Builds velo-client.com from src/*.html + site.config.json + ../news/posts into dist/. No dependencies - Node only.
 *
 * - Every page starts with <!--page {...}--> (title, description, path, active, schema, priority, noindex, lastmod,
 *   ogImage, bodyClass). Its body is placed into src/_layout.html ({{page.body}}), which carries the <head> (title,
 *   description, canonical, Open Graph, Twitter, JSON-LD), the navigation and the footer.
 * - {{> name}} pastes src/partials/name.html, {{#if a.b}}..{{/if}} / {{#ifnot a.b}}..{{/ifnot}} toggle on config
 *   values, {{a.b}} inserts a config or page value. {{asset.css}} / {{asset.js}} are cache-busted asset URLs.
 * - The launcher's news posts (news/posts/<version>/post.json + images) become /news/<slug> articles, the /news
 *   index and the "What's new" cards on the home page.
 * - Writes sitemap.xml (with images), robots.txt, site.webmanifest, llms.txt and the IndexNow key file.
 *   Files starting with "_" in src/ are templates, not pages.
 */
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { execSync } = require("child_process");

const ROOT = __dirname;
const SRC = path.join(ROOT, "src");
const DIST = path.join(ROOT, "dist");
const NEWS = path.join(ROOT, "..", "news", "posts");
const config = JSON.parse(fs.readFileSync(path.join(ROOT, "site.config.json"), "utf8"));

function flatten(obj, prefix, out) {
	for (const [key, value] of Object.entries(obj)) {
		if (key.startsWith("_")) continue;
		const k = prefix ? `${prefix}.${key}` : key;
		if (value !== null && typeof value === "object" && !Array.isArray(value)) flatten(value, k, out);
		else out[k] = String(value);
	}
	return out;
}

const truthy = (v) => v !== undefined && !["false", "0", ""].includes(v);
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** **bold**, *italic* and `code` in news text (the launcher's own small markdown). */
function inline(s) {
	return esc(s)
		.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
		.replace(/\*(.+?)\*/g, "<em>$1</em>")
		.replace(/`(.+?)`/g, "<code>$1</code>");
}

function render(content, tokens, file) {
	for (let i = 0; i < 3 && /\{\{>\s*[\w-]+\s*\}\}/.test(content); i++) {
		content = content.replace(/\{\{>\s*([\w-]+)\s*\}\}/g, (m, name) => {
			const p = path.join(SRC, "partials", `${name}.html`);
			return fs.existsSync(p) ? fs.readFileSync(p, "utf8") : (console.warn(`  ! ${file}: no partial ${name}`), m);
		});
	}
	content = content.replace(/\{\{#if\s+([\w.]+)\}\}([\s\S]*?)\{\{\/if\}\}/g, (_, k, inner) => (truthy(tokens[k]) ? inner : ""));
	content = content.replace(/\{\{#ifnot\s+([\w.]+)\}\}([\s\S]*?)\{\{\/ifnot\}\}/g, (_, k, inner) => (truthy(tokens[k]) ? "" : inner));
	return content.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (m, k) => {
		if (!(k in tokens)) {
			console.warn(`  ! ${file}: no value for {{${k}}}`);
			return m;
		}
		return tokens[k];
	});
}

/** Reads the <!--page {...}--> header of a page; returns [meta, rest]. */
function frontMatter(raw, file) {
	const m = raw.match(/^<!--page\s+([\s\S]*?)-->\s*/);
	if (!m) {
		console.warn(`  ! ${file}: no <!--page {...}--> header`);
		return [{}, raw];
	}
	return [JSON.parse(m[1]), raw.slice(m[0].length)];
}

function copyDir(src, dest) {
	if (!fs.existsSync(src)) return;
	fs.mkdirSync(dest, { recursive: true });
	for (const e of fs.readdirSync(src, { withFileTypes: true })) {
		const s = path.join(src, e.name);
		const d = path.join(dest, e.name);
		if (e.isDirectory()) copyDir(s, d);
		else fs.copyFileSync(s, d);
	}
}

const hashOf = (file) => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex").slice(0, 10);

// ---- News ----

function postDate(dir) {
	try {
		const d = execSync(`git log --diff-filter=A --format=%cs -- "${dir}/post.json"`, { cwd: ROOT, stdio: ["ignore", "pipe", "ignore"] }).toString().trim().split("\n").pop();
		if (d) return d;
	} catch (e) {
		// Not a git checkout: fall back to the file's date.
	}
	return fs.statSync(path.join(dir, "post.json")).mtime.toISOString().slice(0, 10);
}

function readPosts() {
	if (!fs.existsSync(NEWS)) return [];
	const posts = [];
	for (const name of fs.readdirSync(NEWS)) {
		const dir = path.join(NEWS, name);
		const file = path.join(dir, "post.json");
		if (!fs.existsSync(file)) continue;
		const post = JSON.parse(fs.readFileSync(file, "utf8"));
		if (post.hidden) continue; // a bundle that never shipped on its own (its content is in a later post)
		post.slug = name.replace(/\./g, "-");
		post.dir = dir;
		post.date = post.date || postDate(dir);
		post.image = (v) => (v && v.startsWith("file:") ? `/news/${post.slug}/${v.slice(5)}` : v);
		posts.push(post);
	}
	posts.sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : b.slug.localeCompare(a.slug, undefined, { numeric: true })));
	return posts;
}

function prettyDate(iso) {
	return new Date(iso + "T12:00:00Z").toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric", timeZone: "UTC" });
}

function postBody(post) {
	const out = [];
	for (const b of post.blocks || []) {
		switch (b.type) {
			case "heading": out.push(`<h2>${inline(b.text)}</h2>`); break;
			case "text": out.push(`<p>${inline(b.text)}</p>`); break;
			case "list": out.push(`<ul>${(b.items || []).map((i) => `<li>${inline(i)}</li>`).join("")}</ul>`); break;
			case "callout": out.push(`<aside class="callout callout-${esc(b.style || "info")}"><p>${inline(b.text)}</p></aside>`); break;
			case "divider": out.push("<hr>"); break;
			case "image":
				out.push(`<figure class="reveal"><img src="${esc(post.image(b.image))}" alt="${esc(b.caption || post.title)}" loading="lazy" decoding="async">`
					+ (b.caption ? `<figcaption>${inline(b.caption)}</figcaption>` : "") + "</figure>");
				break;
			default: break;
		}
	}
	return out.join("\n");
}

function postCard(post, i) {
	const tag = esc(post.tag || "News");
	return `<a class="news-card reveal tilt" style="--i:${i % 6}" href="/news/${post.slug}">`
		+ (post.cover ? `<span class="news-card-img"><img src="${esc(post.image(post.cover))}" alt="" loading="lazy" decoding="async" width="800" height="488"></span>` : "")
		+ `<span class="news-card-body"><span class="news-meta"><span class="tag tag-${tag.toLowerCase()}">${tag}</span>`
		+ `<time datetime="${post.date}">${prettyDate(post.date)}</time></span>`
		+ `<span class="news-title">${esc(post.title)}</span><span class="news-summary">${esc(post.summary || "")}</span></span></a>`;
}

// ---- Build ----

function main() {
	const tokens = flatten(config, "", {});
	const siteUrl = tokens["site.url"].replace(/\/$/, "");
	const posts = readPosts();
	tokens["news.latest"] = posts.slice(0, 3).map(postCard).join("\n");
	tokens["news.list"] = posts.map(postCard).join("\n");
	tokens["news.count"] = String(posts.length);
	tokens["build.year"] = String(new Date().getFullYear());
	tokens["asset.css"] = `/assets/site.css?v=${hashOf(path.join(ROOT, "assets", "site.css"))}`;
	tokens["asset.js"] = `/assets/site.js?v=${hashOf(path.join(ROOT, "assets", "site.js"))}`;
	tokens["asset.store"] = `/assets/store.js?v=${hashOf(path.join(ROOT, "assets", "store.js"))}`;
	tokens["asset.skin3d"] = `/assets/skin3d.js?v=${hashOf(path.join(ROOT, "assets", "skin3d.js"))}`;
	const rel = config.release;
	const dl = (file) => `${config.links.releases}/download/v${rel.version}/${file}`;
	tokens["download.exe"] = dl(rel.launcherExe);
	tokens["download.deb"] = dl(rel.launcherDeb);
	for (const mc of config.minecraft.list) tokens[`download.mod.${mc.replace(/\./g, "_")}`] = dl(`velo-client-${rel.version}+${mc}.jar`);

	fs.rmSync(DIST, { recursive: true, force: true });
	fs.mkdirSync(DIST, { recursive: true });
	const layout = fs.readFileSync(path.join(SRC, "_layout.html"), "utf8");
	const sitemap = [];

	const writePage = (relPath, meta, body, file) => {
		const page = {
			title: meta.title, description: meta.description, path: meta.path,
			canonical: siteUrl + (meta.path === "/" ? "/" : meta.path),
			ogImage: meta.ogImage ? (meta.ogImage.startsWith("http") ? meta.ogImage : siteUrl + meta.ogImage) : `${siteUrl}/assets/og-image.jpg`,
			ogType: meta.ogType || "website",
			schema: meta.schema ? JSON.stringify(meta.schema).replace(/</g, "\\u003c") : "",
			robots: meta.noindex ? "noindex, follow" : "index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1",
			active: meta.active || "",
			bodyClass: meta.bodyClass || "",
			published: meta.published || "",
		};
		const pageTokens = { ...tokens };
		for (const [k, v] of Object.entries(page)) pageTokens[`page.${k}`] = v;
		for (const id of ["home", "features", "plus", "store", "news", "download"]) pageTokens[`nav.${id}`] = page.active === id ? "aria-current=\"page\"" : "";
		const html = render(layout.replace("{{page.body}}", body), pageTokens, file);
		const out = path.join(DIST, relPath);
		fs.mkdirSync(path.dirname(out), { recursive: true });
		fs.writeFileSync(out, html);
		if (!meta.noindex) {
			sitemap.push({ loc: page.canonical, lastmod: meta.lastmod || tokens["legal.lastUpdated"], priority: meta.priority || "0.6",
				changefreq: meta.changefreq || "monthly", images: (meta.images || []).map((i) => siteUrl + i) });
		}
	};

	for (const file of fs.readdirSync(SRC).filter((f) => f.endsWith(".html") && !f.startsWith("_"))) {
		const [meta, body] = frontMatter(fs.readFileSync(path.join(SRC, file), "utf8"), file);
		if (meta.lastmod === "latest-post" && posts[0]) meta.lastmod = posts[0].date;
		writePage(file, meta, body, file);
	}

	// One page per news post, with its images next to it.
	const template = fs.readFileSync(path.join(SRC, "_news-post.html"), "utf8");
	posts.forEach((post, index) => {
		copyDir(path.join(post.dir, "images"), path.join(DIST, "news", post.slug, "images"));
		const cover = post.cover ? post.image(post.cover) : null;
		const schema = {
			"@context": "https://schema.org", "@type": "BlogPosting", headline: post.title, description: post.summary || "",
			datePublished: post.date, dateModified: post.date, image: cover ? [siteUrl + cover] : undefined,
			author: { "@type": "Organization", name: "Velo Client", url: siteUrl },
			publisher: { "@id": `${siteUrl}/#org` },
			mainEntityOfPage: `${siteUrl}/news/${post.slug}`,
			inLanguage: "en",
		};
		const crumbs = {
			"@context": "https://schema.org", "@type": "BreadcrumbList", itemListElement: [
				{ "@type": "ListItem", position: 1, name: "Home", item: `${siteUrl}/` },
				{ "@type": "ListItem", position: 2, name: "News", item: `${siteUrl}/news` },
				{ "@type": "ListItem", position: 3, name: post.title, item: `${siteUrl}/news/${post.slug}` }],
		};
		const newer = posts[index - 1];
		const older = posts[index + 1];
		const body = template
			.replace("{{post.body}}", postBody(post))
			.replace(/\{\{post\.title\}\}/g, esc(post.title))
			.replace(/\{\{post\.summary\}\}/g, esc(post.summary || ""))
			.replace(/\{\{post\.tag\}\}/g, esc(post.tag || "News"))
			.replace(/\{\{post\.tagClass\}\}/g, esc((post.tag || "news").toLowerCase()))
			.replace(/\{\{post\.date\}\}/g, post.date)
			.replace(/\{\{post\.prettyDate\}\}/g, prettyDate(post.date))
			.replace(/\{\{post\.cover\}\}/g, cover ? esc(cover) : "/assets/og-image.jpg")
			.replace("{{post.more}}", [newer, older].filter(Boolean).map(postCard).join("\n"));
		writePage(path.join("news", `${post.slug}.html`), {
			title: `${post.title} · Velo Client`, description: (post.summary || post.title).slice(0, 158),
			path: `/news/${post.slug}`, ogImage: cover || undefined, ogType: "article", schema: [schema, crumbs],
			lastmod: post.date, priority: "0.5", active: "news", images: cover ? [cover] : [], published: post.date,
		}, body, `news/${post.slug}`);
	});

	copyDir(path.join(ROOT, "assets"), path.join(DIST, "assets"));
	// Favicons where browsers look for them.
	fs.copyFileSync(path.join(ROOT, "assets", "favicon.ico"), path.join(DIST, "favicon.ico"));
	fs.copyFileSync(path.join(ROOT, "assets", "apple-touch-icon.png"), path.join(DIST, "apple-touch-icon.png"));

	const xmlEsc = (s) => s.replace(/&/g, "&amp;");
	fs.writeFileSync(path.join(DIST, "sitemap.xml"), '<?xml version="1.0" encoding="UTF-8"?>\n'
		+ '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">\n'
		+ sitemap.map((u) => `  <url><loc>${xmlEsc(u.loc)}</loc><lastmod>${u.lastmod}</lastmod><changefreq>${u.changefreq}</changefreq><priority>${u.priority}</priority>`
			+ u.images.map((i) => `<image:image><image:loc>${xmlEsc(i)}</image:loc></image:image>`).join("") + "</url>").join("\n")
		+ "\n</urlset>\n");
	fs.writeFileSync(path.join(DIST, "robots.txt"), `User-agent: *\nAllow: /\nDisallow: /v1/\nDisallow: /store/done\nDisallow: /rewards/\n\nSitemap: ${siteUrl}/sitemap.xml\n`);
	fs.writeFileSync(path.join(DIST, "site.webmanifest"), JSON.stringify({
		name: "Velo Client", short_name: "Velo", description: config.site.description, start_url: "/", scope: "/", display: "standalone",
		background_color: "#0b0b0e", theme_color: config.site.themeColor, categories: ["games", "utilities"],
		icons: [
			{ src: "/assets/icon-192.png", sizes: "192x192", type: "image/png" },
			{ src: "/assets/icon-512.png", sizes: "512x512", type: "image/png" },
			{ src: "/assets/icon-maskable.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
		],
	}, null, 2));
	fs.writeFileSync(path.join(DIST, "llms.txt"), [
		"# Velo Client", "",
		`> ${config.site.description}`, "",
		`Velo Client is free. Current version ${rel.version} for Minecraft Java ${config.minecraft.version} (Fabric). Windows and Linux launcher; the mod also runs in any Fabric launcher.`, "",
		"## Pages", "",
		`- [Features](${siteUrl}/features): ${config.counts.modules} free modules (HUD, PvP, performance, quality of life, server tools) and Velo+`,
		`- [Download](${siteUrl}/download): launcher for Windows (.exe) and Linux (.deb), mod jars for every version`,
		`- [Store](${siteUrl}/store): cosmetic wings, capes and auras bought with Velo Coins`,
		`- [News](${siteUrl}/news): release notes`,
		`- [FAQ](${siteUrl}/#faq)`, "",
		"## Legal", "",
		`- [Terms](${siteUrl}/terms)`, `- [Privacy](${siteUrl}/privacy)`, `- [Imprint](${siteUrl}/imprint)`, "",
	].join("\n"));
	if (config.seo && config.seo.indexNowKey) fs.writeFileSync(path.join(DIST, `${config.seo.indexNowKey}.txt`), config.seo.indexNowKey);
	fs.writeFileSync(path.join(DIST, "urls.txt"), sitemap.map((u) => u.loc).join("\n") + "\n");

	const placeholders = Object.entries(tokens).filter(([k, v]) => k.startsWith("legal.") && /YOUR |\[|example\.com|^$/.test(v));
	console.log(`Built ${sitemap.length} indexed pages (${posts.length} news posts) -> dist/`);
	if (placeholders.length) {
		console.warn("\nStill placeholder legal info - fix before going live:");
		for (const [k, v] of placeholders) console.warn(`  - ${k}: "${v}"`);
	}
}

main();
