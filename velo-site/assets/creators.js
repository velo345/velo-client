// /creators: the list comes live from the Velo server (channel numbers, pictures and latest videos refresh themselves
// every few hours there). The creators already in the HTML stay as they are if the server can't be reached.
(() => {
	const list = document.querySelector("[data-creators]");
	const note = document.querySelector("[data-creators-count]");
	if (!list) return;
	const el = (tag, cls, text) => {
		const e = document.createElement(tag);
		if (cls) e.className = cls;
		if (text != null) e.textContent = text;
		return e;
	};
	const img = (src, alt, w, h, lazy) => {
		const i = document.createElement("img");
		i.src = src; i.alt = alt; i.width = w; i.height = h;
		if (lazy) i.loading = "lazy";
		return i;
	};
	const link = (href, cls) => {
		const a = el("a", cls);
		a.href = href; a.rel = "noopener"; a.target = "_blank";
		return a;
	};
	const subs = (n) => n >= 1e6 ? (n / 1e6).toFixed(n >= 1e7 ? 0 : 1).replace(/\.0$/, "") + "M" : n >= 1e3 ? (n / 1e3).toFixed(n >= 1e4 ? 0 : 1).replace(/\.0$/, "") + "K" : String(n);
	const YT_ICON = '<svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M23 7.2a3 3 0 0 0-2.1-2.1C19 4.6 12 4.6 12 4.6s-7 0-8.9.5A3 3 0 0 0 1 7.2 31 31 0 0 0 .5 12a31 31 0 0 0 .5 4.8 3 3 0 0 0 2.1 2.1c1.9.5 8.9.5 8.9.5s7 0 8.9-.5a3 3 0 0 0 2.1-2.1 31 31 0 0 0 .5-4.8 31 31 0 0 0-.5-4.8zM9.7 15.1V8.9l5.8 3.1z"/></svg>';
	const safeUrl = (u) => /^https:\/\//.test(u) ? u : "#";
	const card = (c) => {
		const a = el("article", "creator");
		a.id = c.id;
		a.style.setProperty("--c", c.color);
		const banner = link(safeUrl(c.channel), "creator-banner");
		banner.setAttribute("aria-label", `${c.name} on YouTube`);
		if (c.banner) { const b = img(c.banner, `${c.name} channel banner`, 1600, 900); b.fetchPriority = "high"; banner.append(b); }
		const body = el("div", "creator-body");
		const id = el("div", "creator-id");
		if (c.avatar) { const av = img(c.avatar, c.name, 320, 320); av.className = "creator-avatar"; id.append(av); }
		const who = el("div");
		who.append(el("h3", null, c.name));
		if (c.subscribers) {
			const n = el("span", "creator-subs");
			n.append(el("b", null, subs(c.subscribers)), document.createTextNode(" subscribers"));
			who.append(n);
		}
		id.append(who);
		body.append(id);
		const cta = el("div", "creator-cta");
		const sub = link(safeUrl(c.channel) + (c.channel.includes("youtube.com") ? "?sub_confirmation=1" : ""), "cr-btn cr-btn-red");
		sub.innerHTML = YT_ICON;
		sub.append(document.createTextNode(" Subscribe"));
		cta.append(sub);
		body.append(cta);
		a.append(banner, body);
		if (c.videos && c.videos.length) {
			const vids = el("div", "creator-videos");
			vids.append(el("span", "cr-label", "Latest videos"));
			const grid = el("div", "creator-grid");
			for (const v of c.videos) {
				const va = link(safeUrl(v.url));
				va.append(img(v.thumb, "", 320, 180, true), el("span", null, v.title));
				grid.append(va);
			}
			vids.append(grid);
			a.append(vids);
		}
		return a;
	};
	fetch("/v1/creators", { headers: { Accept: "application/json" } })
		.then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
		.then((data) => {
			const creators = (data && data.creators) || [];
			if (!creators.length) return;
			// Creators already in the HTML keep its pictures (small WebPs, already on screen) instead of loading the
			// server's JPEGs again - the swap made the page fetch every picture twice and moved its main image late.
			for (const c of creators) {
				const known = document.getElementById(c.id);
				if (!known) continue;
				const b = known.querySelector(".creator-banner img");
				const av = known.querySelector(".creator-avatar");
				if (b) c.banner = b.getAttribute("src");
				if (av) c.avatar = av.getAttribute("src");
			}
			list.replaceChildren(...creators.map(card));
			if (note) note.textContent = creators.length === 1 ? "1 creator" : `${creators.length} creators`;
		})
		.catch(() => { /* keep the creators in the HTML */ });
})();
