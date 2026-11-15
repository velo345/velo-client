// /features: filters the full module list as you type.
(() => {
	const input = document.querySelector("[data-mods-search]");
	const count = document.querySelector("[data-mods-count]");
	if (!input) return;
	const groups = [...document.querySelectorAll("[data-mods-group]")];
	const total = document.querySelectorAll("[data-mod]").length;
	const update = () => {
		const q = input.value.trim().toLowerCase();
		let shown = 0;
		for (const g of groups) {
			let inGroup = 0;
			for (const li of g.querySelectorAll("[data-mod]")) {
				const hit = !q || li.textContent.toLowerCase().includes(q);
				li.hidden = !hit;
				if (hit) inGroup++;
			}
			g.hidden = inGroup === 0;
			shown += inGroup;
		}
		count.textContent = q ? `${shown} of ${total}` : `${total} modules`;
	};
	input.addEventListener("input", update);
	update();
})();
