package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.hud.StatusBarIcons;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The one pixel-art editor behind every drawable icon (crosshairs, hearts, food, armor): a canvas
 * that always fits the window, one tab per state (idle / hit, full / half / empty...), draw / erase
 * / fill / color-pick tools with an optional mirror, undo & redo, a palette with recent colors,
 * PNG import into the current state, and live previews at real size. What it edits and how it's
 * saved comes from a {@link Host}.
 *
 * <p>Mouse: left = current tool, right = erase. Keys: B draw, E erase, G fill, I pick, M mirror,
 * Ctrl+Z / Ctrl+Y undo & redo.
 */
public final class PixelEditorScreen extends VeloWindow {

	/** One editable state. {@code optional} states can be switched off ({@code offHint} says what's used instead). */
	public record StateSpec(String key, String label, boolean optional, String offHint) {
	}

	/** What the editor edits. */
	public interface Host {
		List<StateSpec> states();

		/** Allowed canvas sizes (the first is the default for new drawings). */
		List<Integer> sizes();

		/** The saved image of a state, or null (blank for required states / "off" for optional ones). Ownership passes to the editor. */
		NativeImage load(String key);

		/** Something to start from when a state is first drawn - e.g. vanilla-like art - or null for blank. */
		default NativeImage starter(String key, int size) {
			return null;
		}

		/** Saves; {@code images} has null for optional states that are off. Return an error to show, or null. */
		String save(Map<String, NativeImage> images, String name);

		/** A name field is shown when this is non-null. */
		default String name() {
			return null;
		}

		/** Extra "in use" preview (e.g. a row of hearts); {@code editor} gives access to the current images. */
		default void drawContextPreview(DrawContext context, PixelEditorScreen editor, int x, int y, int width, int height) {
		}
	}

	private enum Tool { DRAW, ERASE, FILL, PICK }

	private static final int[] PALETTE = {
			0xFFFFFFFF, 0xFFBFBFBF, 0xFF7F7F7F, 0xFF3F3F3F, 0xFF000000, 0xFFE3262B, 0xFFFF8A2B, 0xFFFFD43B,
			0xFF7ED957, 0xFF2E9E5B, 0xFF3DD6D0, 0xFF3D8BFF, 0xFF7B3FF2, 0xFFE040C0, 0xFFFF9EC7, 0xFF8B5A2B};
	private static final int CHECKER_A = 0xFF34343A;
	private static final int CHECKER_B = 0xFF2A2A2F;

	private final Host host;
	private final List<StateSpec> states;
	private final Map<String, NativeImage> images = new LinkedHashMap<>();
	private final Map<String, Deque<int[]>> undo = new HashMap<>();
	private final Map<String, Deque<int[]>> redo = new HashMap<>();
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final Deque<Integer> recentColors = new ArrayDeque<>();
	private String current;
	private int size;
	private Tool tool = Tool.DRAW;
	private boolean mirror;
	private int color = 0xFFFFFFFF;
	private boolean painting;
	private boolean erasingStroke;
	private int hoverX = -1;
	private int hoverY = -1;
	private String status = "";
	private TextFieldWidget nameBox;
	private String name;
	private boolean trulyClosing;
	private boolean closed;

	// Canvas placement (computed each frame).
	private int canvasX;
	private int canvasY;
	private int cell;

	public PixelEditorScreen(Screen parent, String title, Host host) {
		super(Text.literal(title), 620, 420);
		this.host = host;
		this.states = host.states();
		this.name = host.name();
		this.size = host.sizes().get(0);
		for (StateSpec state : states) {
			NativeImage image = host.load(state.key());
			if (image != null) {
				size = image.getWidth();
			}
			images.put(state.key(), image);
		}
		for (StateSpec state : states) {
			NativeImage image = images.get(state.key());
			if (image == null && !state.optional()) {
				images.put(state.key(), startImage(state.key()));
			} else if (image != null && image.getWidth() != size) {
				images.put(state.key(), resized(image, size));
			}
		}
		this.current = states.get(0).key();
		returnTo(parent);
	}

	// ---- Public bits for hosts' previews ----

	/** The current drawing of a state, or null if it's an optional state that's off. */
	public NativeImage image(String key) {
		return images.get(key);
	}

	public int canvasSize() {
		return size;
	}

	/**
	 * Draws {@code image} at (x, y) scaled to {@code displaySize} GUI px. {@code tint} != 0 replaces
	 * every pixel's color (keeping its alpha) - for silhouettes. {@code fraction} < 1 draws only that
	 * much of the left side (half hearts).
	 */
	public static void drawImage(DrawContext context, NativeImage image, float x, float y, float displaySize, int tint, float fraction) {
		if (image == null) {
			return;
		}
		int n = image.getWidth();
		int columns = Math.max(1, Math.round(n * fraction));
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x, y);
		context.getMatrices().scale(displaySize / n, displaySize / n);
		for (int py = 0; py < n; py++) {
			for (int px = 0; px < columns; px++) {
				int argb = image.getColorArgb(px, py);
				int alpha = argb >>> 24;
				if (alpha == 0) {
					continue;
				}
				int shown = tint == 0 ? argb : ((tint >>> 24) * alpha / 255) << 24 | (tint & 0xFFFFFF);
				context.fill(px, py, px + 1, py + 1, shown);
			}
		}
		context.getMatrices().popMatrix();
	}

	// ---- Layout ----

	@Override
	protected void layoutContent() {
		this.clearChildren();
		if (name != null) {
			nameBox = new TextFieldWidget(this.textRenderer, contentX(), contentY(), 150, 16, Text.literal("Name"));
			nameBox.setText(name);
			nameBox.setPlaceholder(Text.literal("Name..."));
			nameBox.setChangedListener(v -> name = v);
			addDrawableChild(nameBox);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (closed) {
			return;
		}
		hits.clear();
		int x = contentX();
		int y = contentY();
		int right = x + contentWidth();
		int bottom = contentBottom();

		// Top row: state tabs + size.
		int tabX = x + (name != null ? 158 : 0);
		for (StateSpec state : states) {
			String label = state.label() + (state.optional() && images.get(state.key()) == null ? " (off)" : "");
			int w = this.textRenderer.getWidth(label) + 18;
			boolean active = state.key().equals(current);
			hits.add(VeloUi.pill(context, tabX, y, w, 16, label, active ? 1 : 0, mouseX, mouseY, () -> {
				current = state.key();
				status = "";
			}));
			tabX += w + 4;
		}
		if (host.sizes().size() > 1) {
			String sizeLabel = "Size " + size + "x" + size;
			int w = this.textRenderer.getWidth(sizeLabel) + 18;
			hits.add(VeloUi.pill(context, right - w, y, w, 16, sizeLabel, 0, mouseX, mouseY, this::cycleSize));
		}

		// Left: tools.
		int toolY = y + 24;
		int toolW = 58;
		toolY = toolPill(context, x, toolY, toolW, "Draw  B", Tool.DRAW, mouseX, mouseY);
		toolY = toolPill(context, x, toolY, toolW, "Erase  E", Tool.ERASE, mouseX, mouseY);
		toolY = toolPill(context, x, toolY, toolW, "Fill  G", Tool.FILL, mouseX, mouseY);
		toolY = toolPill(context, x, toolY, toolW, "Pick  I", Tool.PICK, mouseX, mouseY);
		toolY += 6;
		hits.add(VeloUi.pill(context, x, toolY, toolW, 16, mirror ? "Mirror on" : "Mirror", mirror ? 1 : 0, mouseX, mouseY,
				() -> mirror = !mirror));
		toolY += 26;
		hits.add(VeloUi.pill(context, x, toolY, toolW, 16, "Undo", 0, mouseX, mouseY, this::undo));
		toolY += 20;
		hits.add(VeloUi.pill(context, x, toolY, toolW, 16, "Redo", 0, mouseX, mouseY, this::redo));
		toolY += 26;
		hits.add(VeloUi.pill(context, x, toolY, toolW, 16, "Flip", 0, mouseX, mouseY, this::flip));
		toolY += 20;
		hits.add(VeloUi.pill(context, x, toolY, toolW, 16, "Clear", 3, mouseX, mouseY, this::clear));
		StateSpec currentSpec = spec(current);
		if (currentSpec.optional() && images.get(current) != null) {
			toolY += 20;
			hits.add(VeloUi.pill(context, x, toolY, toolW, 16, "Turn off", 0, mouseX, mouseY, () -> {
				NativeImage removed = images.put(current, null);
				if (removed != null) {
					removed.close();
				}
				undo.remove(current);
				redo.remove(current);
				status = currentSpec.label() + " is off: " + (currentSpec.offHint() != null ? currentSpec.offHint() : "not used");
			}));
		}

		// Center: canvas, as big as fits.
		int area = Math.min(bottom - 26 - (y + 24), 300);
		cell = Math.max(1, area / size);
		int canvasPx = cell * size;
		canvasX = x + toolW + 12 + (area - canvasPx) / 2;
		canvasY = y + 24 + (area - canvasPx) / 2;
		drawCanvas(context, mouseX, mouseY);

		// Right panel.
		int panelX = x + toolW + 12 + area + 14;
		int panelW = right - panelX;
		drawColorPanel(context, panelX, y + 24, panelW, mouseX, mouseY);
		drawPreviews(context, panelX, y + 126, panelW, bottom - 30 - (y + 126), mouseX, mouseY);

		// Bottom: status + actions.
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, panelX - x - 10), x, bottom - 12, VeloStyle.textMuted());
		hits.add(VeloUi.pill(context, right - 70, bottom - 18, 70, 18, "Save", 1, mouseX, mouseY, this::save));
		hits.add(VeloUi.pill(context, right - 144, bottom - 18, 70, 18, "Cancel", 0, mouseX, mouseY, this::requestClose));
		hits.add(VeloUi.pill(context, panelX, bottom - 18, Math.min(92, right - 150 - panelX), 18, "Import", 0, mouseX, mouseY,
				this::importPng));
	}

	private int toolPill(DrawContext context, int x, int y, int w, String label, Tool which, int mouseX, int mouseY) {
		hits.add(VeloUi.pill(context, x, y, w, 16, label, tool == which ? 1 : 0, mouseX, mouseY, () -> tool = which));
		return y + 20;
	}

	private void drawCanvas(DrawContext context, int mouseX, int mouseY) {
		int canvasPx = cell * size;
		VeloDraw.fillRounded(context, canvasX - 4, canvasY - 4, canvasPx + 8, canvasPx + 8, 6, VeloStyle.sunken());
		NativeImage image = images.get(current);
		StateSpec spec = spec(current);
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				int sx = canvasX + px * cell;
				int sy = canvasY + py * cell;
				context.fill(sx, sy, sx + cell, sy + cell, (px + py) % 2 == 0 ? CHECKER_A : CHECKER_B);
			}
		}
		if (image == null) {
			// Optional state that's off: show what's used instead, dimmed, plus a way to turn it on.
			NativeImage full = images.get(states.get(0).key());
			if (full != null) {
				drawImage(context, full, canvasX, canvasY, canvasPx, 0x40FFFFFF, 1f);
			}
			String hint = spec.offHint() != null ? spec.offHint() : "Not used";
			int tw = this.textRenderer.getWidth(hint);
			context.drawTextWithShadow(this.textRenderer, hint, canvasX + (canvasPx - tw) / 2, canvasY + canvasPx / 2 - 22, VeloStyle.text());
			hits.add(VeloUi.pill(context, canvasX + canvasPx / 2 - 60, canvasY + canvasPx / 2 - 8, 120, 18,
					"Draw my own " + spec.label().toLowerCase(Locale.ROOT), 1, mouseX, mouseY, () -> enable(current)));
			return;
		}
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				int argb = image.getColorArgb(px, py);
				if ((argb >>> 24) != 0) {
					context.fill(canvasX + px * cell, canvasY + py * cell, canvasX + (px + 1) * cell, canvasY + (py + 1) * cell, argb);
				}
			}
		}
		if (cell >= 8) {
			for (int i = 1; i < size; i++) {
				context.fill(canvasX + i * cell, canvasY, canvasX + i * cell + 1, canvasY + canvasPx, 0x18FFFFFF);
				context.fill(canvasX, canvasY + i * cell, canvasX + canvasPx, canvasY + i * cell + 1, 0x18FFFFFF);
			}
		}
		if (mirror) {
			int mid = canvasX + canvasPx / 2;
			context.fill(mid, canvasY, mid + 1, canvasY + canvasPx, 0x80FFFFFF & VeloStyle.accent() | 0x80000000);
		}
		// Hover cell (and its mirror twin).
		hoverX = (mouseX - canvasX) / Math.max(1, cell);
		hoverY = (mouseY - canvasY) / Math.max(1, cell);
		if (mouseX >= canvasX && mouseY >= canvasY && hoverX < size && hoverY < size) {
			outlineCell(context, hoverX, hoverY);
			if (mirror) {
				outlineCell(context, size - 1 - hoverX, hoverY);
			}
		} else {
			hoverX = -1;
		}
	}

	private void outlineCell(DrawContext context, int px, int py) {
		VeloDraw.strokeRect(context, canvasX + px * cell, canvasY + py * cell, cell, cell, 0xC0FFFFFF);
	}

	private void drawColorPanel(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
		context.drawTextWithShadow(this.textRenderer, "Color", x, y, VeloStyle.textMuted());
		int swatchY = y + 12;
		drawSwatch(context, x, swatchY, 26, color);
		String hex = String.format(Locale.ROOT, "#%08X", color);
		context.drawTextWithShadow(this.textRenderer, hex, x + 32, swatchY + 2, VeloStyle.text());
		hits.add(VeloUi.pill(context, x + 32, swatchY + 13, 64, 13, "Pick...", 0, mouseX, mouseY, () ->
				this.client.setScreen(new VeloColorPickerScreen(this, "Draw Color", color, true, c -> setColor(c)))));
		hits.add(new VeloUi.Hit(x, swatchY, 26, 26, () ->
				this.client.setScreen(new VeloColorPickerScreen(this, "Draw Color", color, true, c -> setColor(c)))));
		int sw = Math.max(10, Math.min(16, (width - 7 * 3) / 8));
		int py = swatchY + 32;
		for (int i = 0; i < PALETTE.length; i++) {
			int sx = x + (i % 8) * (sw + 3);
			int sy = py + (i / 8) * (sw + 3);
			int c = PALETTE[i];
			drawSwatch(context, sx, sy, sw, c);
			hits.add(new VeloUi.Hit(sx, sy, sw, sw, () -> setColor(c)));
		}
		int ry = py + 2 * (sw + 3) + 3;
		int i = 0;
		for (int c : recentColors) {
			int sx = x + i * (sw + 3);
			drawSwatch(context, sx, ry, sw, c);
			hits.add(new VeloUi.Hit(sx, ry, sw, sw, () -> setColor(c)));
			i++;
		}
	}

	private void drawSwatch(DrawContext context, int x, int y, int s, int c) {
		context.fill(x, y, x + s, y + s, CHECKER_A);
		context.fill(x, y, x + s / 2, y + s / 2, CHECKER_B);
		context.fill(x + s / 2, y + s / 2, x + s, y + s, CHECKER_B);
		context.fill(x, y, x + s, y + s, c);
		VeloDraw.strokeRect(context, x, y, s, s, c == color ? 0xFFFFFFFF : 0x50FFFFFF);
	}

	private void drawPreviews(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY) {
		context.drawTextWithShadow(this.textRenderer, "Preview", x, y, VeloStyle.textMuted());
		int py = y + 12;
		int px = x;
		for (StateSpec state : states) {
			NativeImage image = images.get(state.key());
			VeloDraw.fillRounded(context, px, py, 36, 36, 5, VeloStyle.sunken());
			if (image != null) {
				drawImage(context, image, px + 4, py + 4, 28, 0, 1f);
			} else {
				context.drawTextWithShadow(this.textRenderer, "off", px + 10, py + 14, VeloStyle.textFaint());
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(state.label(), 36), px, py + 39, VeloStyle.textFaint());
			px += 40;
		}
		int contextY = py + 52;
		int contextH = Math.max(0, y + height - contextY);
		if (contextH > 20) {
			VeloDraw.fillRounded(context, x, contextY, width, contextH, 6, VeloStyle.sunken());
			host.drawContextPreview(context, this, x, contextY, width, contextH);
		}
	}

	// ---- Editing ----

	private StateSpec spec(String key) {
		return states.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
	}

	private void setColor(int c) {
		color = c;
		recentColors.remove(c);
		recentColors.addFirst(c);
		while (recentColors.size() > 8) {
			recentColors.removeLast();
		}
		if (tool == Tool.ERASE || tool == Tool.PICK) {
			tool = Tool.DRAW;
		}
	}

	private NativeImage startImage(String key) {
		NativeImage starter = host.starter(key, size);
		if (starter != null) {
			return starter.getWidth() == size ? starter : resized(starter, size);
		}
		return blank(size);
	}

	private void enable(String key) {
		NativeImage full = images.get(states.get(0).key());
		NativeImage starter = host.starter(key, size);
		images.put(key, starter != null ? (starter.getWidth() == size ? starter : resized(starter, size)) : full != null ? copy(full) : blank(size));
		status = spec(key).label() + " now uses its own drawing.";
	}

	private void snapshot() {
		NativeImage image = images.get(current);
		if (image == null) {
			return;
		}
		undo.computeIfAbsent(current, k -> new ArrayDeque<>()).push(pixels(image));
		redo.computeIfAbsent(current, k -> new ArrayDeque<>()).clear();
		while (undo.get(current).size() > 60) {
			undo.get(current).removeLast();
		}
	}

	private void undo() {
		Deque<int[]> stack = undo.get(current);
		NativeImage image = images.get(current);
		if (stack == null || stack.isEmpty() || image == null) {
			status = "Nothing to undo";
			return;
		}
		redo.computeIfAbsent(current, k -> new ArrayDeque<>()).push(pixels(image));
		restore(image, stack.pop());
	}

	private void redo() {
		Deque<int[]> stack = redo.get(current);
		NativeImage image = images.get(current);
		if (stack == null || stack.isEmpty() || image == null) {
			status = "Nothing to redo";
			return;
		}
		undo.computeIfAbsent(current, k -> new ArrayDeque<>()).push(pixels(image));
		restore(image, stack.pop());
	}

	private int[] pixels(NativeImage image) {
		int[] out = new int[size * size + 1];
		out[0] = image.getWidth();
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				out[1 + y * image.getWidth() + x] = image.getColorArgb(x, y);
			}
		}
		return out;
	}

	private void restore(NativeImage image, int[] data) {
		if (data[0] != image.getWidth()) {
			status = "Can't undo across a size change";
			return;
		}
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setColorArgb(x, y, data[1 + y * image.getWidth() + x]);
			}
		}
	}

	private void apply(int px, int py, boolean erase) {
		NativeImage image = images.get(current);
		if (image == null || px < 0 || py < 0 || px >= size || py >= size) {
			return;
		}
		int value = erase ? 0 : color;
		image.setColorArgb(px, py, value);
		if (mirror) {
			image.setColorArgb(size - 1 - px, py, value);
		}
	}

	private void fill(int px, int py) {
		NativeImage image = images.get(current);
		if (image == null) {
			return;
		}
		int target = image.getColorArgb(px, py);
		if (target == color) {
			return;
		}
		Deque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[] {px, py});
		while (!queue.isEmpty()) {
			int[] p = queue.poll();
			if (p[0] < 0 || p[1] < 0 || p[0] >= size || p[1] >= size || image.getColorArgb(p[0], p[1]) != target) {
				continue;
			}
			image.setColorArgb(p[0], p[1], color);
			queue.add(new int[] {p[0] + 1, p[1]});
			queue.add(new int[] {p[0] - 1, p[1]});
			queue.add(new int[] {p[0], p[1] + 1});
			queue.add(new int[] {p[0], p[1] - 1});
		}
	}

	private void flip() {
		NativeImage image = images.get(current);
		if (image == null) {
			return;
		}
		snapshot();
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size / 2; x++) {
				int a = image.getColorArgb(x, y);
				image.setColorArgb(x, y, image.getColorArgb(size - 1 - x, y));
				image.setColorArgb(size - 1 - x, y, a);
			}
		}
	}

	private void clear() {
		NativeImage image = images.get(current);
		if (image == null) {
			return;
		}
		snapshot();
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				image.setColorArgb(x, y, 0);
			}
		}
		status = "Cleared.";
	}

	private void cycleSize() {
		List<Integer> sizes = host.sizes();
		int next = sizes.get((sizes.indexOf(size) + 1) % sizes.size());
		for (Map.Entry<String, NativeImage> entry : images.entrySet()) {
			if (entry.getValue() != null) {
				entry.setValue(resized(entry.getValue(), next));
			}
		}
		size = next;
		undo.clear();
		redo.clear();
		status = "Canvas is now " + size + "x" + size;
	}

	private void importPng() {
		status = "Opening file picker...";
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path file = NativeFileDialog.pickPngFile("Choose a PNG for " + spec(current).label());
				if (file == null) {
					MinecraftClient.getInstance().execute(() -> status = "");
					return;
				}
				NativeImage raw;
				try (var in = Files.newInputStream(file)) {
					raw = NativeImage.read(in);
				}
				MinecraftClient.getInstance().execute(() -> {
					if (closed) {
						raw.close();
						return;
					}
					if (images.get(current) == null) {
						enable(current);
					}
					snapshot();
					NativeImage fitted = StatusBarIcons.fit(raw, size);
					NativeImage target = images.get(current);
					for (int y = 0; y < size; y++) {
						for (int x = 0; x < size; x++) {
							target.setColorArgb(x, y, fitted.getColorArgb(x, y));
						}
					}
					fitted.close();
					status = "Imported " + file.getFileName() + " (fitted to " + size + "x" + size + ")";
				});
			} catch (Throwable t) {
				MinecraftClient.getInstance().execute(() -> status = "Import failed: " + t.getMessage());
			}
		});
	}

	private void save() {
		if (name != null && name.isBlank()) {
			status = "Type a name first.";
			return;
		}
		String error = host.save(images, name);
		if (error != null) {
			status = error;
			return;
		}
		requestClose();
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		int px = (int) Math.floor((click.x() - canvasX) / Math.max(1, cell));
		int py = (int) Math.floor((click.y() - canvasY) / Math.max(1, cell));
		if (click.x() >= canvasX && click.y() >= canvasY && px < size && py < size && images.get(current) != null) {
			if (tool == Tool.PICK && click.button() == 0) {
				int picked = images.get(current).getColorArgb(px, py);
				if ((picked >>> 24) != 0) {
					setColor(picked);
				}
				return true;
			}
			snapshot();
			if (tool == Tool.FILL && click.button() == 0) {
				fill(px, py);
				return true;
			}
			painting = true;
			erasingStroke = tool == Tool.ERASE || click.button() == 1;
			apply(px, py, erasingStroke);
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (painting) {
			int px = (int) Math.floor((click.x() - canvasX) / Math.max(1, cell));
			int py = (int) Math.floor((click.y() - canvasY) / Math.max(1, cell));
			apply(px, py, erasingStroke);
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		painting = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (nameBox != null && nameBox.isFocused()) {
			return super.keyPressed(input);
		}
		boolean ctrl = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shift = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
		switch (input.key()) {
			case GLFW.GLFW_KEY_Z -> {
				if (ctrl) {
					if (shift) {
						redo();
					} else {
						undo();
					}
					return true;
				}
			}
			case GLFW.GLFW_KEY_Y -> {
				if (ctrl) {
					redo();
					return true;
				}
			}
			case GLFW.GLFW_KEY_B -> {
				tool = Tool.DRAW;
				return true;
			}
			case GLFW.GLFW_KEY_E -> {
				tool = Tool.ERASE;
				return true;
			}
			case GLFW.GLFW_KEY_G -> {
				tool = Tool.FILL;
				return true;
			}
			case GLFW.GLFW_KEY_I -> {
				tool = Tool.PICK;
				return true;
			}
			case GLFW.GLFW_KEY_M -> {
				mirror = !mirror;
				return true;
			}
			default -> {
			}
		}
		return super.keyPressed(input);
	}

	// ---- Lifecycle (images are closed only when the editor is really done, not when the color picker covers it) ----

	@Override
	protected void requestClose() {
		trulyClosing = true;
		super.requestClose();
	}

	@Override
	public void removed() {
		if (trulyClosing && !closed) {
			closed = true;
			for (NativeImage image : images.values()) {
				if (image != null) {
					image.close();
				}
			}
		}
		super.removed();
	}

	// ---- Image helpers ----

	public static NativeImage blank(int size) {
		NativeImage image = new NativeImage(size, size, true);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				image.setColorArgb(x, y, 0);
			}
		}
		return image;
	}

	public static NativeImage copy(NativeImage source) {
		NativeImage out = new NativeImage(source.getWidth(), source.getHeight(), true);
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				out.setColorArgb(x, y, source.getColorArgb(x, y));
			}
		}
		return out;
	}

	/** Nearest-neighbour resize (pixel art stays crisp); closes {@code source}. */
	public static NativeImage resized(NativeImage source, int size) {
		NativeImage out = new NativeImage(size, size, true);
		int n = source.getWidth();
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				out.setColorArgb(x, y, source.getColorArgb(Math.min(n - 1, x * n / size), Math.min(source.getHeight() - 1, y * source.getHeight() / size)));
			}
		}
		source.close();
		return out;
	}
}
