/* Partners page: live partner list (player counts, codes), copy buttons, screenshot zoom. */
(() => {
	const $ = (s, r = document) => r.querySelector(s);
	const $$ = (s, r = document) => [...r.querySelectorAll(s)];
	const el = (tag, attrs = {}, ...kids) => {
		const n = document.createElement(tag);
		for (const [k, v] of Object.entries(attrs)) {
			if (v == null || v === false) continue;
			if (k === "class") n.className = v;
			else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
			else n.setAttribute(k, v);
		}
		for (const c of kids.flat()) if (c != null && c !== false) n.append(c instanceof Node ? c : document.createTextNode(String(c)));
		return n;
	};
	const nf = new Intl.NumberFormat();

	async function copy(button, text) {
		try {
			await navigator.clipboard.writeText(text);
		} catch {
			const t = el("textarea", {}, text);
			document.body.append(t);
			t.select();
			document.execCommand("copy");
			t.remove();
		}
		const old = button.textContent;
		button.textContent = "Copied";
		button.classList.add("done");
		setTimeout(() => { button.textContent = old; button.classList.remove("done"); }, 1600);
	}
	const copyButton = (text) => el("button", { class: "pt-copy", type: "button", onclick: (e) => copy(e.currentTarget, text) }, "Copy");
	$$("[data-copy]").forEach((b) => b.addEventListener("click", () => copy(b, b.dataset.copy)));

	// Screenshot zoom
	const zoom = $("[data-zoom-dialog]");
	if (zoom) {
		const img = $("img", zoom);
		const caption = $("p", zoom);
		$$("[data-zoom]").forEach((b) => b.addEventListener("click", () => {
			const src = $("img", b);
			img.src = src.currentSrc || src.src;
			img.alt = src.alt;
			caption.textContent = b.closest("figure").querySelector("figcaption")?.textContent.trim() || src.alt;
			zoom.showModal();
		}));
		$(".pt-zoom-close", zoom).addEventListener("click", () => zoom.close());
		zoom.addEventListener("click", (e) => { if (e.target === zoom) zoom.close(); });
	}

	const accentOf = (p) => (/^#[0-9a-f]{6}$/i.test(p.accent || "") ? p.accent : "#e0262e");
	const codeLink = (code) => "/store?code=" + encodeURIComponent(code) + "#coins";

	function card(p) {
		const icon = p.icon
			? el("img", { class: "pt-card-icon", src: "/v1/partners/icon/" + encodeURIComponent(p.id) + "?s=128&v=" + p.icon, alt: "", width: "60", height: "60", loading: "lazy" })
			: el("span", { class: "pt-card-icon", "aria-hidden": "true" }, p.name.slice(0, 1).toUpperCase());
		const live = el("span", { class: "pt-card-live" + (p.online ? " on" : "") }, el("i"),
			p.online ? nf.format(p.players) + " online" : "Offline", p.versions ? " · " + p.versions : null);
		const code = (p.codes || [])[0];
		const c = el("article", { class: "pt-card" },
			el("div", { class: "pt-card-top" }, icon, el("div", {}, el("h3", {}, p.name), live)),
			p.description ? el("p", {}, p.description) : null,
			code ? el("a", { class: "pt-card-code", href: codeLink(code) }, "Code " + code + " · 10% off Velo Coins") : null,
			el("div", { class: "pt-ip" }, el("code", {}, p.address), copyButton(p.address)));
		c.style.setProperty("--a", accentOf(p));
		return c;
	}

	fetch("/v1/partners", { headers: { Accept: "application/json" }, cache: "no-store" })
		.then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
		.then(({ partners }) => {
			if (!partners || !partners.length) return;
			// Featured partner: live player count and its code.
			$$("[data-partner]").forEach((box) => {
				const p = partners.find((x) => x.id === box.dataset.partner || x.address === String(box.dataset.address).toLowerCase());
				if (!p) return;
				const live = $("[data-live]", box);
				if (live) {
					live.textContent = p.online ? nf.format(p.players) + " online" : "Offline";
					live.classList.toggle("on", !!p.online);
				}
				const code = (p.codes || [])[0];
				const ticket = $("[data-code]", box);
				if (code && ticket) {
					$("[data-code-value]", ticket).textContent = code;
					$("[data-code-link]", ticket).href = codeLink(code);
					ticket.hidden = false;
				}
			});
			const section = $("[data-all]");
			const grid = $("[data-cards]");
			// The list only says something once there's more than the featured partner.
			if (section && grid && partners.length > 1) {
				grid.replaceChildren(...partners.map(card));
				section.hidden = false;
			}
		})
		.catch(() => { /* the static page still works without the live list */ });
})();
