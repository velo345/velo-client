/* Home page, before the first paint: if the opening will play, keep the page covered until the hero picture is ready. */
try {
	if (!matchMedia("(prefers-reduced-motion: reduce)").matches && sessionStorage.getItem("velo.intro") !== "1") {
		document.documentElement.classList.add("intro-pending");
	}
} catch (e) { /* storage blocked: no intro cover */ }
