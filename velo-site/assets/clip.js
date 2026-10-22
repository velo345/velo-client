// Clips Pro share page (rendered by the Velo server at /c/<id>): the Copy link button.
document.getElementById("copy")?.addEventListener("click", async (e) => {
	const button = e.currentTarget;
	try {
		await navigator.clipboard.writeText(location.href);
		button.textContent = "Copied!";
	} catch {
		button.textContent = location.href;
	}
	setTimeout(() => (button.textContent = "Copy link"), 2200);
});

// Shared clips are AV1 (tiny files); a few older browsers (Safari before 17 on older Macs/iPhones) can't play it.
const video = document.querySelector(".player video");
video?.addEventListener("error", () => {
	const note = document.createElement("p");
	note.className = "by";
	note.style.padding = "18px";
	note.textContent = "Your browser can't play this clip. Open it in Chrome, Edge or Firefox - or download it.";
	video.replaceWith(note);
}, true);

// Report: the dialog posts to the API (signed-out viewers too - the server rate-limits by address).
const reportButton = document.getElementById("report");
const dialog = document.getElementById("report-dialog");
const form = document.getElementById("report-form");
reportButton?.addEventListener("click", () => dialog?.showModal());
form?.addEventListener("submit", async (e) => {
	if (e.submitter?.value !== "send") return;
	e.preventDefault();
	const status = document.getElementById("report-status");
	const data = new FormData(form);
	try {
		const res = await fetch("/v1/clips/report", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ id: reportButton.dataset.clip, category: data.get("category"), reason: data.get("reason") }),
		});
		const body = await res.json().catch(() => ({}));
		if (!res.ok) throw new Error(body.error || "Couldn't send the report");
		status.textContent = "Thanks - staff will take a look.";
		reportButton.textContent = "Reported";
		reportButton.disabled = true;
		setTimeout(() => dialog.close(), 1400);
	} catch (err) {
		status.textContent = err.message;
	}
});
