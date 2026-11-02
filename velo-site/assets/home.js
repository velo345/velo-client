/* Home page: the opening (blocks break away from the cloak), hero parallax, and the real-screenshot tabs. */
"use strict";
(() => {
	const $ = (s, r = document) => r.querySelector(s);
	const $$ = (s, r = document) => [...r.querySelectorAll(s)];
	const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
	const fine = matchMedia("(pointer: fine)").matches;
	const hero = $("[data-hero]");

	// ---- Opening: once per visit, a wall of dark blocks breaks apart from the cloak outwards ----
	// intro-gate.js (in <head>) already covers the page; the blocks only start once the hero picture is decoded, so the
	// reveal never shows a half-loaded image.
	const intro = $("[data-intro]");
	const root = document.documentElement;
	const play = root.classList.contains("intro-pending");
	try { sessionStorage.setItem("velo.intro", "1"); } catch (e) { /* storage blocked */ }
	const ready = () => hero && hero.classList.add("h-ready");
	const heroImg = $("[data-hero-bg] img");
	const decoded = (img, cap) => Promise.race([
		img.complete && img.naturalWidth ? (img.decode ? img.decode().catch(() => {}) : Promise.resolve()) : new Promise((r) => { img.addEventListener("load", r, { once: true }); img.addEventListener("error", r, { once: true }); }),
		new Promise((r) => setTimeout(r, cap)),
	]);
	if (intro && play && !reduce) {
		const grid = $("[data-intro-grid]", intro);
		const cell = innerWidth < 700 ? 46 : 72;
		const cols = Math.ceil(innerWidth / cell), rows = Math.ceil(innerHeight / cell);
		grid.style.gridTemplateColumns = `repeat(${cols}, 1fr)`;
		grid.style.gridTemplateRows = `repeat(${rows}, 1fr)`;
		// Where the cloak is in the hero picture.
		const ox = (innerWidth < 760 ? 0.5 : 0.74) * cols, oy = (innerWidth < 760 ? 0.36 : 0.46) * rows;
		const far = Math.hypot(Math.max(ox, cols - ox), Math.max(oy, rows - oy));
		const frag = document.createDocumentFragment();
		for (let y = 0; y < rows; y++) {
			for (let x = 0; x < cols; x++) {
				const i = document.createElement("i");
				const d = Math.hypot(x + 0.5 - ox, y + 0.5 - oy) / far;
				i.style.setProperty("--t", `${(0.5 + d * 0.7 + Math.random() * 0.08).toFixed(3)}s`);
				i.style.setProperty("--r", `${(Math.random() * 20 - 10).toFixed(1)}deg`);
				i.style.setProperty("--fy", `${(Math.random() * 22).toFixed(0)}px`);
				frag.append(i);
			}
		}
		grid.append(frag);
		grid.classList.add("hold");
		intro.classList.add("run", "hold");
		root.classList.remove("intro-pending");
		decoded(heroImg, 1600).then(() => requestAnimationFrame(() => {
			grid.classList.remove("hold");
			intro.classList.remove("hold");
			root.classList.add("intro-running");
			setTimeout(ready, 950);
			setTimeout(() => { intro.remove(); root.classList.remove("intro-running"); }, 2200);
		}));
	} else {
		root.classList.remove("intro-pending");
		if (intro) intro.remove();
		requestAnimationFrame(() => requestAnimationFrame(ready));
	}

	// ---- Hero: the picture leans away from the pointer and sinks a little as you scroll ----
	const bg = $("[data-hero-bg] img");
	if (bg && !reduce) {
		let mx = 0, my = 0, sy = 0, queued = false;
		const paint = () => {
			queued = false;
			bg.style.setProperty("--px", `${mx}px`);
			bg.style.setProperty("--py", `${my + sy * 0.22}px`);
			bg.style.setProperty("--hs", String(1 + Math.min(sy, 900) * 0.00012));
		};
		const queue = () => { if (!queued) { queued = true; requestAnimationFrame(paint); } };
		if (fine) {
			hero.addEventListener("pointermove", (e) => {
				mx = (e.clientX / innerWidth - 0.5) * -18;
				my = (e.clientY / innerHeight - 0.5) * -12;
				queue();
			});
		}
		addEventListener("scroll", () => { sy = scrollY < innerHeight * 1.2 ? scrollY : sy; queue(); }, { passive: true });
	}

	// ---- Tabs: [data-tabs] holds the panes; its buttons (or those in [data-tabs-control] of the same name) switch them ----
	const captions = {
		launcher: [
			["HOME", "Continue where you left off", "Your last server with its mods and hours played up front. Connect starts the game straight onto it. Tap the numbered points."],
			["LIBRARY", "Every profile, every version", "Fabric profiles from 1.21.11 to 26.3 side by side, each with its own mods, packs and shaders. Move a whole profile to another version in one step."],
			["SERVERS", "Live server list", "Ping, players online and which profile each server launches with. Partner servers and your friends' worlds show up here too."],
			["STORE", "Cosmetics, tried on first", "Try wings, cloaks and auras on your own skin in 3D before you buy. New collections every few weeks."],
			["LOCKER", "Your look, saved as loadouts", "Skin, cloak, wings, aura, name style, emotes and kill effect on a live model. Switch loadouts in one click, in the launcher or in game."],
			["CLIPS", "Every clip in one place", "Clips you save in game land here, ready to watch and share."],
		],
		spots: [
			["HOME · 1 / 6", "Continue playing", "The server you played last, with its profile, mods and playtime. Mods & packs opens the profile right there."],
			["HOME · 2 / 6", "Search everything", "Ctrl K finds profiles, servers, settings and Store items from anywhere in the launcher."],
			["HOME · 3 / 6", "Velo Coins", "Earned free from daily rewards and quests, or every month with Velo+. Spend them in the Store."],
			["HOME · 4 / 6", "What's new", "Release notes and news from the Velo team, straight on Home."],
			["HOME · 5 / 6", "Jump back in", "Your profiles, or import your setup from Lunar, Modrinth or CurseForge in a few clicks."],
			["HOME · 6 / 6", "Connect", "Starts the selected profile straight onto its server. Velo checks the profile first and offers a fix if something would crash."],
		],
	};

	const setCaption = (box, [k, t, p]) => {
		const cap = $(".h-caption", box);
		if (!cap) return;
		$("[data-cap-k]", cap).textContent = k;
		$("[data-cap-t]", cap).textContent = t;
		$("[data-cap-p]", cap).textContent = p;
		cap.classList.remove("swap");
		void cap.offsetWidth;
		cap.classList.add("swap");
	};

	$$("[data-tabs]").forEach((box) => {
		const name = box.dataset.tabs;
		const control = $(`[data-tabs-control="${name}"]`) || box;
		const tabs = $$("[role=tab]", control);
		const panes = $$("[data-pane]", box);
		const spots = $("[data-spots]", box);
		let current = 0;
		let wanted = 0;
		// Load every pane of this box once it comes near, so switching never waits on the network.
		new IntersectionObserver((es, o) => {
			if (es.some((e) => e.isIntersecting)) {
				panes.forEach((p) => { p.loading = "eager"; if (p.decode) p.decode().catch(() => {}); });
				o.disconnect();
			}
		}, { rootMargin: "600px 0px" }).observe(box);
		const show = (i, focus) => {
			wanted = i;
			if (i === current || !panes[i]) return;
			tabs.forEach((t, k) => t.setAttribute("aria-selected", String(k === i)));
			if (focus) tabs[i].focus();
			const img = panes[i];
			img.loading = "eager";
			decoded(img, 700).then(() => { if (wanted === i) swap(i); });
		};
		const swap = (i) => {
			if (i === current) return;
			const old = panes[current];
			panes.forEach((p) => p.classList.remove("was"));
			old.classList.remove("on");
			old.classList.add("was");
			panes[i].classList.add("on");
			setTimeout(() => old.classList.remove("was"), 820);
			current = i;
			if (spots) {
				spots.classList.toggle("off", i !== 0);
				$$("button", spots).forEach((b) => b.setAttribute("aria-pressed", "false"));
			}
			if (captions[name]) setCaption(box, captions[name][i]);
		};
		tabs.forEach((t, i) => {
			t.addEventListener("click", () => show(i));
			t.addEventListener("keydown", (e) => {
				const step = e.key === "ArrowRight" || e.key === "ArrowDown" ? 1 : e.key === "ArrowLeft" || e.key === "ArrowUp" ? -1 : 0;
				if (step) { e.preventDefault(); show((current + step + tabs.length) % tabs.length, true); }
			});
		});
		if (spots) {
			$$("button", spots).forEach((b) => b.addEventListener("click", () => {
				const on = b.getAttribute("aria-pressed") === "true";
				$$("button", spots).forEach((o) => o.setAttribute("aria-pressed", "false"));
				b.setAttribute("aria-pressed", String(!on));
				setCaption(box, on ? captions.launcher[0] : captions.spots[Number(b.dataset.spot)]);
			}));
		}
	});
})();
