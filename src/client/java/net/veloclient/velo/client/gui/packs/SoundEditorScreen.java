package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.clips.FfmpegTool;
import net.veloclient.velo.client.gui.NativeFileDialog;
import net.veloclient.velo.client.gui.VeloConfirmScreen;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.util.Keys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Edits one sound event of a pack - any game sound or piece of music: every variant can be played, replaced with
 * your own file (ogg, mp3, wav, flac... through FFmpeg), trimmed on its waveform, made louder / quieter, faster /
 * slower, faded in and out, reversed or normalized; the event's sounds.json settings (volume, pitch, weight,
 * streaming) and extra variants are written to the pack's sounds.json.
 */
public final class SoundEditorScreen extends VeloWindow {

	/** Everything about one variant while it's being edited. */
	private static final class State {
		SoundIndex.Variant variant;
		AudioData source;
		byte[] sourceOgg;
		boolean loading;
		String error;
		boolean replaced;
		boolean added;
		double start;
		double end = -1;
		double gain = 1;
		double speed = 1;
		double fadeIn;
		double fadeOut;
		boolean reverse;
		boolean normalize;
		boolean mono;
		float[] peaks;
		int peaksWidth;
		AudioData previewOf;

		AudioData.Edits edits() {
			double length = source == null ? 0 : source.seconds();
			return new AudioData.Edits(start, end < 0 ? length : end, gain, speed, fadeIn, fadeOut, reverse, normalize, mono);
		}

		boolean audioChanged() {
			return replaced || (source != null && !edits().isNone(source.seconds()));
		}
	}

	private final PackBrowserScreen browser;
	private final Path root;
	private final String eventId;
	private SoundIndex.Event event;
	private final List<State> states = new ArrayList<>();
	private final Map<String, State> byName = new HashMap<>();
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private int selected;
	private int variantScroll;
	private int[] listArea = new int[4];
	private boolean loop;
	private boolean eventChanged;
	private PackUi.SliderDrag slider;
	private int handleDrag = -1;
	private int[] wave = new int[4];
	private String status = "";
	private long statusAt;
	private List<String> tooltip = List.of();
	private boolean saving;
	private boolean discardConfirmed;

	public SoundEditorScreen(PackBrowserScreen browser, Path root, String eventId) {
		super(Text.literal(SoundIndex.label(eventId)), 900, 520);
		this.browser = browser;
		this.root = root;
		this.eventId = eventId;
		returnTo(browser);
		this.event = SoundIndex.withPack(eventId, root);
		for (SoundIndex.Variant v : event.variants()) {
			State s = new State();
			s.variant = v;
			states.add(s);
			byName.put(v.name(), s);
		}
		if (!states.isEmpty()) {
			load(states.get(0));
		}
	}

	private State current() {
		return states.isEmpty() ? null : states.get(Math.max(0, Math.min(selected, states.size() - 1)));
	}

	private void load(State s) {
		if (s.source != null || s.loading) {
			return;
		}
		s.loading = true;
		String key = s.variant.assetKey();
		CompletableFuture.runAsync(() -> {
			try {
				byte[] ogg = PackContents.read(root, key);
				if (ogg == null) {
					throw new java.io.IOException("Sound file not found: " + key);
				}
				AudioData audio = AudioData.decodeOgg(ogg);
				MinecraftClient.getInstance().execute(() -> {
					s.source = audio;
					s.sourceOgg = ogg;
					s.end = audio.seconds();
					s.loading = false;
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> {
					s.error = e.getMessage();
					s.loading = false;
				});
			}
		});
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
	}

	private void setStatus(String s) {
		status = s;
		statusAt = System.currentTimeMillis();
	}

	private boolean dirty() {
		if (eventChanged) {
			return true;
		}
		for (State s : states) {
			if (s.audioChanged()) {
				return true;
			}
		}
		return false;
	}

	// ---- Drawing ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		PackUi.frame();
		hits.clear();
		tooltip = List.of();
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		int bottom = contentBottom();

		// Header row.
		SoundIndex.Group group = SoundIndex.groupOf(eventId);
		int bw = PackUi.badge(context, x, y + 4, group.label.toUpperCase(Locale.ROOT), group == SoundIndex.Group.MUSIC ? MenuKit.GOLD : MenuKit.BLUE);
		MenuKit.text(context, this.textRenderer, MenuKit.small(eventId + (event.subtitle() != null ? "   -   " + event.subtitle() : "")), x + bw + 8, y + 6,
				MenuKit.TEXT_3);
		int bx = x + w - 112;
		hits.add(PackUi.button(context, bx, y, 112, 18, "Save & reload", 1, dirty() && !saving, mouseX, mouseY, () -> save(true)));
		bx -= 78;
		hits.add(PackUi.button(context, bx, y, 74, 18, saving ? "Saving..." : dirty() ? "Save" : "Saved", 0, dirty() && !saving, mouseX, mouseY,
				() -> save(false)));

		int top = y + 28;
		int listW = Math.min(230, w / 3);
		drawVariants(context, x, top, listW, bottom - top, mouseX, mouseY);
		int mx = x + listW + 14;
		int mw = w - listW - 14;
		State s = current();
		if (s == null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("This event has no sound files - add one on the left."), mx, top + 10, MenuKit.TEXT_2);
			return;
		}
		load(s);
		drawWave(context, s, mx, top, mw, 120, mouseX, mouseY);
		int cy = top + 128;
		drawTransport(context, s, mx, cy, mw, mouseX, mouseY);
		cy += 28;
		drawEdits(context, s, mx, cy, mw, bottom - cy, mouseX, mouseY);
	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (!tooltip.isEmpty()) {
			PackUi.tooltip(context, tooltip, mouseX, mouseY, this.width, this.height);
		}
	}

	private void drawVariants(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		PackUi.heading(context, "Sound files  " + states.size(), x + 2, y);
		int ry = y + 14;
		int listBottom = y + h - 48;
		int fits = Math.max(1, (listBottom - ry) / 32);
		variantScroll = Math.max(0, Math.min(Math.max(0, states.size() - fits), variantScroll));
		listArea = new int[] {x, ry, w, listBottom - ry};
		if (states.size() > fits) {
			int barH = Math.max(16, (listBottom - ry) * fits / states.size());
			int barY = ry + (listBottom - ry - barH) * variantScroll / Math.max(1, states.size() - fits);
			VeloDraw.fillRounded(context, x + w + 4, barY, 3, barH, 1, 0x40FFFFFF);
		}
		for (int i = variantScroll; i < Math.min(states.size(), variantScroll + fits); i++) {
			State s = states.get(i);
			int index = i;
			boolean active = i == selected;
			boolean hovered = VeloUi.inside(mouseX, mouseY, x, ry, w, 28);
			float hv = PackUi.hover("variant:" + i, hovered || active);
			VeloDraw.fillRounded(context, x, ry, w, 28, 6, active ? VeloUi.withAlpha(VeloStyle.accent(), 0x30) : VeloAnim.lerpArgb(0x0AFFFFFF, 0x1CFFFFFF, hv));
			if (active) {
				VeloDraw.fillRounded(context, x, ry + 7, 2, 14, 1, VeloStyle.accent());
			}
			boolean playing = s.previewOf != null && AudioPreview.playing(s.previewOf);
			hits.add(PackUi.iconButton(context, x + 5, ry + 5, 18, playing ? "stop" : "play", playing, mouseX, mouseY, () -> {
				selected = index;
				togglePlay(states.get(index));
			}));
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(s.variant.shortName()), w - 80), x + 28, ry + 5, MenuKit.TEXT);
			String sub = s.source != null ? time(s.source.seconds()) : s.loading ? "loading..." : s.error != null ? "missing" : "";
			MenuKit.text(context, this.textRenderer, MenuKit.small(sub), x + 28, ry + 16, s.error != null ? MenuKit.RED_TEXT : MenuKit.TEXT_3);
			int badgeX = x + w - 6;
			if (s.audioChanged() || s.added) {
				badgeX -= this.textRenderer.getWidth(MenuKit.small("EDITED")) + 8;
				PackUi.badge(context, badgeX, ry + 9, "EDITED", MenuKit.GOLD);
			} else if (Files.exists(PackContents.file(root, s.variant.assetKey()))) {
				badgeX -= this.textRenderer.getWidth(MenuKit.small("PACK")) + 8;
				PackUi.badge(context, badgeX, ry + 9, "PACK", MenuKit.GREEN);
			}
			if (hovered) {
				tooltip = List.of(s.variant.name(), s.variant.assetKey());
				hits.add(new VeloUi.Hit(x + 26, ry, w - 26, 28, () -> {
					selected = index;
					load(states.get(index));
				}));
			}
			ry += 32;
		}
		int by = y + h - 42;
		hits.add(PackUi.button(context, x, by, w, 18, "Add a sound file...", 0, true, mouseX, mouseY, this::addVariant));
		if (VeloUi.inside(mouseX, mouseY, x, by, w, 18)) {
			tooltip = List.of("Add a variant", "The game picks one of the files at random each time");
		}
		hits.add(PackUi.button(context, x, by + 22, w, 18, "Remove this file", 0, states.size() > 1, mouseX, mouseY, this::removeVariant));
	}

	private void drawWave(DrawContext context, State s, int x, int y, int w, int h, int mouseX, int mouseY) {
		wave = new int[] {x, y, w, h};
		VeloDraw.fillRounded(context, x, y, w, h, 8, 0xFF141418);
		if (s.source == null) {
			String msg = s.error != null ? "Couldn't load: " + s.error : "Loading sound...";
			MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(msg, w - 20)), x + 10, y + h / 2 - 4, s.error != null ? MenuKit.RED_TEXT
					: MenuKit.TEXT_3);
			return;
		}
		int inner = w - 16;
		if (s.peaks == null || s.peaksWidth != inner) {
			s.peaks = s.source.peaks(inner);
			s.peaksWidth = inner;
		}
		double length = s.source.seconds();
		int trimA = x + 8 + (int) Math.round(s.start / length * inner);
		int trimB = x + 8 + (int) Math.round((s.end < 0 ? length : s.end) / length * inner);
		int mid = y + h / 2;
		int accent = VeloStyle.accent() | 0xFF000000;
		for (int i = 0; i < inner; i++) {
			int px = x + 8 + i;
			int half = Math.max(1, Math.round(s.peaks[i] * (h / 2f - 10)));
			boolean in = px >= trimA && px <= trimB;
			context.fill(px, mid - half, px + 1, mid + half, in ? VeloAnim.lerpArgb(accent, 0xFFFFFFFF, 0.15f) : 0x40FFFFFF);
		}
		context.fill(x + 8, mid, x + 8 + inner, mid + 1, 0x20FFFFFF);
		// Fade envelopes.
		if (s.fadeIn > 0) {
			int fx = trimA + (int) Math.round(s.fadeIn / length * inner);
			VeloDraw.line(context, trimA, y + h - 8, fx, y + 8, 1f, 0xA0FFFFFF);
		}
		if (s.fadeOut > 0) {
			int fx = trimB - (int) Math.round(s.fadeOut / length * inner);
			VeloDraw.line(context, fx, y + 8, trimB, y + h - 8, 1f, 0xA0FFFFFF);
		}
		// Trim handles.
		for (int k = 0; k < 2; k++) {
			int hx = k == 0 ? trimA : trimB;
			boolean hovered = Math.abs(mouseX - hx) <= 4 && VeloUi.inside(mouseX, mouseY, x, y, w, h);
			context.fill(hx - 1, y + 4, hx + 1, y + h - 4, hovered || handleDrag == k ? 0xFFFFFFFF : 0xC0FFFFFF);
			VeloDraw.fillRounded(context, hx - 4, y + 4, 8, 12, 3, hovered || handleDrag == k ? 0xFFFFFFFF : accent);
		}
		// Playhead.
		double pos = AudioPreview.position();
		if (pos >= 0 && s.previewOf != null && AudioPreview.playing(s.previewOf)) {
			double inSource = s.reverse ? (s.end < 0 ? length : s.end) - pos * s.speed : s.start + pos * s.speed;
			int px = x + 8 + (int) Math.round(Math.max(0, Math.min(length, inSource)) / length * inner);
			context.fill(px, y + 2, px + 1, y + h - 2, 0xFFFFD43B);
		}
		MenuKit.text(context, this.textRenderer, MenuKit.small(time(s.start)), x + 8, y + h - 10, MenuKit.TEXT_3);
		Text endText = MenuKit.small(time(s.end < 0 ? length : s.end));
		MenuKit.text(context, this.textRenderer, endText, x + w - 8 - this.textRenderer.getWidth(endText), y + h - 10, MenuKit.TEXT_3);
		if (VeloUi.inside(mouseX, mouseY, x, y, w, h)) {
			tooltip = List.of("Waveform", "Drag the handles to trim", "Click to play from there");
		}
	}

	private void drawTransport(DrawContext context, State s, int x, int y, int w, int mouseX, int mouseY) {
		boolean playing = s.previewOf != null && AudioPreview.playing(s.previewOf);
		hits.add(PackUi.iconButton(context, x, y, 22, playing ? "stop" : "play", playing, mouseX, mouseY, () -> togglePlay(s)));
		hits.add(PackUi.iconButton(context, x + 26, y, 22, "repeat", loop, mouseX, mouseY, () -> loop = !loop));
		if (VeloUi.inside(mouseX, mouseY, x + 26, y, 22, 22)) {
			tooltip = List.of("Loop");
		}
		String info;
		if (s.source != null) {
			double result = (s.edits().end() - s.edits().start()) / Math.max(0.1, s.speed);
			info = time(result) + "  -  " + s.source.rate + " Hz  -  " + (s.mono || s.source.channels == 1 ? "mono" : "stereo")
					+ (s.audioChanged() ? "  -  edited" : "");
		} else {
			info = "";
		}
		int rx = x + w;
		rx -= 96;
		hits.add(PackUi.button(context, rx, y + 2, 96, 18, "Export WAV...", 0, s.source != null, mouseX, mouseY, () -> exportWav(s)));
		rx -= 100;
		boolean inPack = Files.exists(PackContents.file(root, s.variant.assetKey()));
		hits.add(PackUi.button(context, rx, y + 2, 96, 18, "Use original", 0, inPack || s.audioChanged(), mouseX, mouseY, () -> revert(s)));
		if (VeloUi.inside(mouseX, mouseY, rx, y + 2, 96, 18)) {
			tooltip = List.of("Back to the game's sound", "Removes this file from the pack");
		}
		rx -= 130;
		hits.add(PackUi.button(context, rx, y + 2, 126, 18, "Replace with file...", 1, true, mouseX, mouseY, () -> replace(s)));
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(info), rx - 8 - (x + 56)), x + 56, y + 7, MenuKit.TEXT_2);
		if (VeloUi.inside(mouseX, mouseY, rx, y + 2, 126, 18)) {
			tooltip = List.of("Use your own sound or music", "ogg works as is; mp3, wav, flac, m4a...", "are converted with FFmpeg");
		}
	}

	private void drawEdits(DrawContext context, State s, int x, int y, int w, int h, int mouseX, int mouseY) {
		int colW = (w - 16) / 2;
		PackUi.heading(context, "Edit the sound", x, y);
		PackUi.heading(context, "In game (sounds.json)", x + colW + 16, y);
		int cy = y + 14;
		double length = s.source == null ? 1 : s.source.seconds();
		cy = slider(context, "gain", x, cy, colW, "Volume", Math.round(s.gain * 100) + "%", s.gain, 0, 3, v -> s.gain = round(v, 0.05), mouseX, mouseY);
		cy = slider(context, "speed", x, cy, colW, "Speed & pitch", Math.round(s.speed * 100) + "%", s.speed, 0.5, 2, v -> s.speed = round(v, 0.05),
				mouseX, mouseY);
		cy = slider(context, "fadein", x, cy, colW, "Fade in", String.format(Locale.ROOT, "%.1f s", s.fadeIn), s.fadeIn, 0, Math.min(10, length),
				v -> s.fadeIn = round(v, 0.1), mouseX, mouseY);
		cy = slider(context, "fadeout", x, cy, colW, "Fade out", String.format(Locale.ROOT, "%.1f s", s.fadeOut), s.fadeOut, 0, Math.min(10, length),
				v -> s.fadeOut = round(v, 0.1), mouseX, mouseY);
		int tw = (colW - 8) / 3;
		hits.add(PackUi.button(context, x, cy, tw, 18, "Reverse", s.reverse ? 1 : 0, true, mouseX, mouseY, () -> {
			s.reverse = !s.reverse;
			restartIfPlaying(s);
		}));
		hits.add(PackUi.button(context, x + tw + 4, cy, tw, 18, "Normalize", s.normalize ? 1 : 0, true, mouseX, mouseY, () -> {
			s.normalize = !s.normalize;
			restartIfPlaying(s);
		}));
		hits.add(PackUi.button(context, x + 2 * tw + 8, cy, colW - 2 * tw - 8, 18, "Mono", s.mono ? 1 : 0, true, mouseX, mouseY, () -> {
			s.mono = !s.mono;
			restartIfPlaying(s);
		}));
		cy += 24;
		hits.add(PackUi.button(context, x, cy, colW, 18, "Reset edits", 0, s.source != null && !s.edits().isNone(s.source.seconds()), mouseX, mouseY, () -> {
			s.start = 0;
			s.end = s.source.seconds();
			s.gain = 1;
			s.speed = 1;
			s.fadeIn = 0;
			s.fadeOut = 0;
			s.reverse = false;
			s.normalize = false;
			s.mono = false;
		}));

		int gx = x + colW + 16;
		int gy = y + 14;
		SoundIndex.Variant v = s.variant;
		gy = slider(context, "gvol", gx, gy, colW, "Volume in game", Math.round(v.volume() * 100) + "%", v.volume(), 0, 1,
				val -> setVariant(s, new SoundIndex.Variant(v.name(), (float) round(val, 0.05), v.pitch(), v.weight(), v.stream())), mouseX, mouseY);
		gy = slider(context, "gpitch", gx, gy, colW, "Pitch in game", String.format(Locale.ROOT, "%.2f", v.pitch()), v.pitch(), 0.5, 2,
				val -> setVariant(s, new SoundIndex.Variant(v.name(), v.volume(), (float) round(val, 0.05), v.weight(), v.stream())), mouseX, mouseY);
		gy = slider(context, "gweight", gx, gy, colW, "How often (weight)", String.valueOf(v.weight()), v.weight(), 1, 20,
				val -> setVariant(s, new SoundIndex.Variant(v.name(), v.volume(), v.pitch(), (int) Math.round(val), v.stream())), mouseX, mouseY);
		hits.add(PackUi.button(context, gx, gy, colW, 18, v.stream() ? "Streamed (for long music)" : "Loaded at once (short sounds)", v.stream() ? 1 : 0,
				true, mouseX, mouseY, () -> setVariant(s, new SoundIndex.Variant(v.name(), v.volume(), v.pitch(), v.weight(), !v.stream()))));
		gy += 26;
		if (FfmpegTool.binary() == null && dirtyAudioNeedsFfmpeg()) {
			List<String> lines = VeloUi.wrap(FfmpegTool.downloading() ? "Downloading FFmpeg (needed to save edited sounds)..."
					: "Saving edited sounds needs FFmpeg - it's downloaded once when you save.", colW);
			for (String line : lines) {
				MenuKit.text(context, this.textRenderer, MenuKit.small(line), gx, gy, MenuKit.GOLD);
				gy += 10;
			}
		}
		String shown = !status.isEmpty() && System.currentTimeMillis() - statusAt < 6000 ? status : "Space plays, Ctrl+S saves.";
		MenuKit.text(context, this.textRenderer, MenuKit.small(VeloUi.trim(shown, w)), x, y + h - 10, MenuKit.TEXT_3);
	}

	private int slider(DrawContext context, String id, int x, int y, int w, String label, String value, double v, double min, double max,
			java.util.function.DoubleConsumer set, int mouseX, int mouseY) {
		hits.add(PackUi.slider(context, "sound:" + id, x, y, w, label, value, v, min, max, mouseX, mouseY, slider, set, s -> slider = s));
		return y + 24;
	}

	private static double round(double v, double step) {
		return Math.round(v / step) * step;
	}

	private static String time(double seconds) {
		int m = (int) (seconds / 60);
		double s = seconds - m * 60;
		return m > 0 ? String.format(Locale.ROOT, "%d:%04.1f", m, s) : String.format(Locale.ROOT, "%.1f s", s);
	}

	// ---- Actions ----

	private void setVariant(State s, SoundIndex.Variant v) {
		s.variant = v;
		eventChanged = true;
	}

	private boolean dirtyAudioNeedsFfmpeg() {
		for (State s : states) {
			if (s.source != null && !s.edits().isNone(s.source.seconds())) {
				return true;
			}
			if (s.replaced && s.sourceOgg == null) {
				return true;
			}
		}
		return false;
	}

	private void togglePlay(State s) {
		if (s.previewOf != null && AudioPreview.playing(s.previewOf)) {
			AudioPreview.stop();
			return;
		}
		playFrom(s, -1);
	}

	/** Plays the edited sound (from {@code atSeconds} of the source when >= 0). */
	private void playFrom(State s, double atSeconds) {
		load(s);
		if (s.source == null) {
			setStatus("Still loading...");
			return;
		}
		AudioData.Edits e = s.edits();
		AudioData result = e.isNone(s.source.seconds()) ? s.source : s.source.apply(e);
		s.previewOf = result;
		int startFrame = 0;
		if (atSeconds >= 0 && !s.reverse) {
			startFrame = (int) Math.max(0, Math.min(result.frames() - 1, (atSeconds - e.start()) / Math.max(0.1, e.speed()) * result.rate));
		}
		AudioPreview.play(result, startFrame, result.frames(), loop, 1f);
	}

	private void restartIfPlaying(State s) {
		if (s.previewOf != null && AudioPreview.playing(s.previewOf)) {
			playFrom(s, -1);
		}
	}

	private void pickAudio(java.util.function.Consumer<Path> picked) {
		setStatus("Opening file picker...");
		Thread.ofVirtual().start(() -> {
			try {
				Path file = NativeFileDialog.pickFile("Choose a sound or song", "Audio files", "ogg", "mp3", "wav", "flac", "m4a", "aac", "opus", "webm");
				MinecraftClient.getInstance().execute(() -> {
					setStatus("");
					if (file != null) {
						picked.accept(file);
					}
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> setStatus("Couldn't open the file picker: " + e.getMessage()));
			}
		});
	}

	/** Decodes a picked file (fetching FFmpeg first if it isn't an ogg), then hands it over on the render thread. */
	private void decodePicked(Path file, java.util.function.BiConsumer<AudioData, byte[]> done) {
		boolean ogg = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg");
		Runnable decode = () -> Thread.ofVirtual().start(() -> {
			try {
				AudioData audio = AudioData.decodeFile(file);
				byte[] raw = ogg ? Files.readAllBytes(file) : null;
				MinecraftClient.getInstance().execute(() -> done.accept(audio, raw));
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> setStatus("Couldn't read " + file.getFileName() + ": " + e.getMessage()));
			}
		});
		if (ogg || FfmpegTool.binary() != null) {
			setStatus("Reading " + file.getFileName() + "...");
			decode.run();
		} else {
			setStatus("Downloading FFmpeg to read " + file.getFileName() + "...");
			FfmpegTool.ensure(problem -> MinecraftClient.getInstance().execute(() -> {
				if (problem != null) {
					setStatus(problem);
				} else {
					decode.run();
				}
			}));
		}
	}

	private void replace(State s) {
		pickAudio(file -> decodePicked(file, (audio, raw) -> {
			AudioPreview.stop();
			s.source = audio;
			s.sourceOgg = raw;
			s.replaced = true;
			s.error = null;
			s.start = 0;
			s.end = audio.seconds();
			s.peaks = null;
			if (audio.seconds() > 30 && !s.variant.stream()) {
				setVariant(s, new SoundIndex.Variant(s.variant.name(), s.variant.volume(), s.variant.pitch(), s.variant.weight(), true));
			}
			setStatus("Replaced with " + file.getFileName() + " - save to put it in the pack");
		}));
	}

	private void addVariant() {
		pickAudio(file -> decodePicked(file, (audio, raw) -> {
			String base = "custom/" + eventId.substring(eventId.indexOf(':') + 1).replace('.', '/');
			int n = 1;
			while (byName.containsKey(base + "_" + n) || Files.exists(PackContents.file(root, "minecraft:sounds/" + base + "_" + n + ".ogg"))) {
				n++;
			}
			State s = new State();
			s.variant = new SoundIndex.Variant(base + "_" + n, 1, 1, 1, audio.seconds() > 30);
			s.source = audio;
			s.sourceOgg = raw;
			s.replaced = true;
			s.added = true;
			s.end = audio.seconds();
			states.add(s);
			byName.put(s.variant.name(), s);
			selected = states.size() - 1;
			eventChanged = true;
			setStatus("Added " + file.getFileName() + " - save to put it in the pack");
		}));
	}

	private void removeVariant() {
		if (states.size() <= 1) {
			return;
		}
		State s = states.remove(selected);
		byName.remove(s.variant.name());
		selected = Math.min(selected, states.size() - 1);
		eventChanged = true;
		AudioPreview.stop();
	}

	private void revert(State s) {
		AudioPreview.stop();
		try {
			Files.deleteIfExists(PackContents.file(root, s.variant.assetKey()));
		} catch (Exception e) {
			setStatus("Couldn't remove it: " + e.getMessage());
			return;
		}
		s.source = null;
		s.sourceOgg = null;
		s.replaced = false;
		s.peaks = null;
		s.error = null;
		s.start = 0;
		s.end = -1;
		s.gain = 1;
		s.speed = 1;
		s.fadeIn = 0;
		s.fadeOut = 0;
		s.reverse = false;
		s.normalize = false;
		s.mono = false;
		load(s);
		browser.onSaved(null);
		setStatus("Back to the game's sound");
	}

	private void exportWav(State s) {
		if (s.source == null) {
			return;
		}
		AudioData result = s.source.apply(s.edits());
		Thread.ofVirtual().start(() -> {
			try {
				Path wav = NativeFileDialog.saveFile("Export " + s.variant.shortName(), s.variant.shortName() + ".wav", "WAV audio", "wav");
				if (wav == null) {
					return;
				}
				result.writeWav(wav);
				MinecraftClient.getInstance().execute(() -> setStatus("Exported " + wav.getFileName()));
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> setStatus("Export failed: " + e.getMessage()));
			}
		});
	}

	private void save(boolean reload) {
		if (saving || !dirty()) {
			return;
		}
		if (dirtyAudioNeedsFfmpeg() && FfmpegTool.binary() == null) {
			setStatus("Downloading FFmpeg to save the sound...");
			FfmpegTool.ensure(problem -> MinecraftClient.getInstance().execute(() -> {
				if (problem != null) {
					setStatus(problem);
				} else {
					save(reload);
				}
			}));
			return;
		}
		saving = true;
		setStatus("Saving...");
		List<State> snapshot = new ArrayList<>(states);
		boolean writeEvent = eventChanged;
		List<SoundIndex.Variant> variants = snapshot.stream().map(st -> st.variant).toList();
		Thread.ofVirtual().start(() -> {
			String error = null;
			try {
				for (State st : snapshot) {
					if (!st.audioChanged() || st.source == null) {
						continue;
					}
					Path target = PackContents.file(root, st.variant.assetKey());
					Files.createDirectories(target.getParent());
					AudioData.Edits e = st.edits();
					if (e.isNone(st.source.seconds()) && st.sourceOgg != null) {
						Files.write(target, st.sourceOgg);
					} else {
						st.source.apply(e).writeOgg(target);
					}
				}
				if (writeEvent) {
					SoundIndex.writeEvent(root, new SoundIndex.Event(eventId, variants, event.subtitle()));
				}
			} catch (Exception e) {
				error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			}
			String problem = error;
			MinecraftClient.getInstance().execute(() -> {
				saving = false;
				if (problem != null) {
					setStatus("Couldn't save: " + problem);
					return;
				}
				// What's saved is now the new original.
				for (State st : snapshot) {
					if (st.audioChanged() && st.source != null) {
						AudioData saved = st.source.apply(st.edits());
						st.source = saved;
						st.sourceOgg = null;
						st.replaced = false;
						st.added = false;
						st.start = 0;
						st.end = saved.seconds();
						st.gain = 1;
						st.speed = 1;
						st.fadeIn = 0;
						st.fadeOut = 0;
						st.reverse = false;
						st.normalize = false;
						st.mono = false;
						st.peaks = null;
					}
				}
				eventChanged = false;
				event = SoundIndex.withPack(eventId, root);
				browser.onSaved(null);
				setStatus(reload ? "Saved - reloading resources..." : "Saved into the pack");
				if (reload) {
					browser.applyAndReload();
				}
			});
		});
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mx = click.x();
		double my = click.y();
		State s = current();
		if (s != null && s.source != null && VeloUi.inside(mx, my, wave[0], wave[1], wave[2], wave[3])) {
			int inner = wave[2] - 16;
			double length = s.source.seconds();
			int trimA = wave[0] + 8 + (int) Math.round(s.start / length * inner);
			int trimB = wave[0] + 8 + (int) Math.round((s.end < 0 ? length : s.end) / length * inner);
			if (Math.abs(mx - trimA) <= 5) {
				handleDrag = 0;
				return true;
			}
			if (Math.abs(mx - trimB) <= 5) {
				handleDrag = 1;
				return true;
			}
			double at = Math.max(0, Math.min(1, (mx - wave[0] - 8) / inner)) * length;
			playFrom(s, Math.max(s.start, Math.min(s.end < 0 ? length : s.end, at)));
			return true;
		}
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(mx, my)) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (slider != null) {
			slider.update(click.x());
			return true;
		}
		State s = current();
		if (handleDrag >= 0 && s != null && s.source != null) {
			int inner = wave[2] - 16;
			double length = s.source.seconds();
			double at = Math.max(0, Math.min(1, (click.x() - wave[0] - 8) / inner)) * length;
			if (handleDrag == 0) {
				s.start = Math.min(at, (s.end < 0 ? length : s.end) - 0.05);
				s.start = Math.max(0, s.start);
			} else {
				s.end = Math.max(at, s.start + 0.05);
				s.end = Math.min(length, s.end);
			}
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		boolean was = slider != null || handleDrag >= 0;
		State s = current();
		if (was && s != null) {
			restartIfPlaying(s);
		}
		slider = null;
		handleDrag = -1;
		return was || super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (VeloUi.inside(mouseX, mouseY, listArea[0], listArea[1], listArea[2], listArea[3])) {
			variantScroll -= (int) Math.signum(verticalAmount);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		boolean ctrl = (input.modifiers() & Keys.MOD_CONTROL) != 0;
		if (input.key() == Keys.SPACE && current() != null) {
			togglePlay(current());
			return true;
		}
		if (ctrl && input.key() == Keys.letter('S')) {
			save((input.modifiers() & Keys.MOD_SHIFT) != 0);
			return true;
		}
		if (input.key() == Keys.UP || input.key() == Keys.DOWN) {
			if (!states.isEmpty()) {
				selected = Math.floorMod(selected + (input.key() == Keys.UP ? -1 : 1), states.size());
				load(states.get(selected));
				int fits = Math.max(1, listArea[3] / 32);
				if (selected < variantScroll) {
					variantScroll = selected;
				} else if (selected >= variantScroll + fits) {
					variantScroll = selected - fits + 1;
				}
			}
			return true;
		}
		return super.keyPressed(input);
	}

	// ---- Lifecycle ----

	@Override
	protected void requestClose() {
		if (dirty() && !discardConfirmed) {
			this.client.setScreen(new VeloConfirmScreen(this, "Unsaved changes", "Close without saving your sound changes?", "Discard", () -> {
				discardConfirmed = true;
				requestClose();
			}));
			return;
		}
		AudioPreview.stop();
		super.requestClose();
	}
}
