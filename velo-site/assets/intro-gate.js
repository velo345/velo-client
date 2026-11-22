/* Home page, before the first paint: if the opening will play, keep the page covered until the hero picture is ready. */
try {
	// Desktop only: on phones the page should be there at once (the intro held it back ~2 s on slow devices).
	if (!matchMedia("(prefers-reduced-motion: reduce)").matches && matchMedia("(pointer: fine) and (min-width: 900px)").matches
			&& sessionStorage.getItem("velo.intro") !== "1") {
		document.documentElement.classList.add("intro-pending");
	}
} catch (e) { /* storage blocked: no intro cover */ }
