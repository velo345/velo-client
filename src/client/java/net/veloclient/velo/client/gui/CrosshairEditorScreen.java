package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.text.Text;
import net.veloclient.velo.client.crosshair.CrosshairDefinition;
import net.veloclient.velo.client.crosshair.CrosshairManager;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Full pixel-art editor for one crosshair: a zoomable/pannable drawing
 * canvas (pencil/eraser, any ARGB color including alpha), PNG import, and
 * the "hit" state - either a wholly separate second image, or a per-color
 * remap applied to the idle image while a reachable entity is targeted.
 * Opened either from the "+" tile (blank canvas) or by clicking an existing
 * crosshair's icon in {@link CrosshairSelectScreen}.
 */
public final class CrosshairEditorScreen extends VeloWindow {

	private enum Tool { PENCIL, ERASER }

	private static final int VIEWPORT_SIZE = 200;
	private static final int MAX_CANVAS_SIZE = 64;
	private static final int CHECKER = 0xFF3A3A3A;
	private static final int CHECKER_ALT = 0xFF2A2A2A;

	private final String id;
	private int canvasSize;
	private NativeImage idleImage;
	private NativeImage hitImage;
	private boolean editingHit;
	private CrosshairDefinition.HitMode hitMode;
	private final Map<Integer, Integer> colorSwap;
	private String name;

	private Tool tool = Tool.PENCIL;
	private int drawColor = 0xFFFFFFFF;
	private float zoom;
	private float panX;
	private float panY;
	private boolean painting;
	private boolean erasingDrag;
	private boolean panning;

	private TextFieldWidget nameBox;
	private Text status = Text.literal("");
	private boolean trulyClosing;
	private boolean imagesClosed;

	public CrosshairEditorScreen(Screen parent, CrosshairDefinition definition) {
		super(Text.literal("Edit Crosshair"), 440, 470);
		this.id = definition.id();
		this.canvasSize = definition.canvasSize();
		this.name = definition.name();
		this.hitMode = definition.hitMode();
		this.colorSwap = new LinkedHashMap<>(definition.colorSwap());
		this.idleImage = CrosshairManager.loadIdleImage(id);
		this.hitImage = hitMode == CrosshairDefinition.HitMode.SEPARATE_IMAGE ? tryLoadHit() : null;
		this.zoom = Math.max(4f, VIEWPORT_SIZE / (float) canvasSize);
		returnTo(parent);
	}

	private NativeImage tryLoadHit() {
		try {
			return CrosshairManager.loadHitImage(id);
		} catch (RuntimeException e) {
			NativeImage blank = new NativeImage(canvasSize, canvasSize, false);
			blank.fillRect(0, 0, canvasSize, canvasSize, 0);
			return blank;
		}
	}

	private NativeImage activeImage() {
		return editingHit ? hitImage : idleImage;
	}

	private int canvasViewX() {
		return contentX();
	}

	private int canvasViewY() {
		// nameBox (18px) + an actual gap for the instructions label drawn
		// just above the canvas in render() - that label used to sit at
		// canvasViewY()-11, which with zero gap here landed inside the name
		// box's own bounds instead of above the canvas.
		return contentY() + 18 + 14;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();

		nameBox = new TextFieldWidget(this.textRenderer, contentX(), contentY(), contentWidth() - 90, 18, Text.literal("Name"));
		nameBox.setDrawsBackground(true);
		nameBox.setText(name);
		nameBox.setChangedListener(v -> name = v);
		addDrawableChild(nameBox);

		int toolbarY = canvasViewY() + VIEWPORT_SIZE + 8;
		addDrawableChild(new VeloButton(contentX(), toolbarY, 70, 20, Text.literal(tool == Tool.PENCIL ? "● Pencil" : "Pencil"),
				b -> { tool = Tool.PENCIL; layoutContent(); }).selected(tool == Tool.PENCIL));
		addDrawableChild(new VeloButton(contentX() + 74, toolbarY, 70, 20, Text.literal(tool == Tool.ERASER ? "● Eraser" : "Eraser"),
				b -> { tool = Tool.ERASER; layoutContent(); }).selected(tool == Tool.ERASER));
		Text colorSwatch = Text.literal("Color: ").append(Text.literal("●").styled(s -> s.withColor(drawColor)));
		addDrawableChild(new VeloButton(contentX() + 148, toolbarY, 100, 20, colorSwatch,
				b -> this.client.setScreen(new VeloColorPickerScreen(this, "Draw Color", drawColor, true, c -> drawColor = c))));
		addDrawableChild(new VeloButton(contentX() + 252, toolbarY, 90, 20, Text.literal("Import PNG"), b -> importPng()));

		int clearY = toolbarY + 24;
		addDrawableChild(new VeloButton(contentX(), clearY, contentWidth(), 20, Text.literal("Clear Canvas"), b -> clearCanvas()));

		int hitY = clearY + 26;
		addDrawableChild(new VeloButton(contentX(), hitY, contentWidth(), 20,
				Text.literal("Hit Effect: " + hitModeLabel() + "  ▸"), b -> cycleHitMode()));

		int belowHitY = hitY + 24;
		if (hitMode == CrosshairDefinition.HitMode.SEPARATE_IMAGE) {
			addDrawableChild(new VeloButton(contentX(), belowHitY, contentWidth() / 2 - 4, 20,
					Text.literal(editingHit ? "Editing: Hit Image" : "Editing: Idle Image"),
					b -> { editingHit = !editingHit; layoutContent(); }));
			addDrawableChild(new VeloButton(contentX() + contentWidth() / 2 + 4, belowHitY, contentWidth() / 2 - 4, 20,
					Text.literal("Copy Idle -> Hit"), b -> {
						hitImage.close();
						hitImage = copyOf(idleImage);
						status = Text.literal("Copied idle image as a starting point for the hit image.");
					}));
			belowHitY += 24;
		} else if (hitMode == CrosshairDefinition.HitMode.COLOR_SWAP) {
			int[] uniqueColors = uniqueColors(idleImage, 8);
			int cx = contentX();
			for (int color : uniqueColors) {
				int target = colorSwap.getOrDefault(color, color);
				Text label = Text.literal("").append(Text.literal("●").styled(s -> s.withColor(color | 0xFF000000)))
						.append(Text.literal(" -> ")).append(Text.literal("●").styled(s -> s.withColor(target | 0xFF000000)));
				int finalColor = color;
				addDrawableChild(new VeloButton(cx, belowHitY, 90, 18, label,
						b -> this.client.setScreen(new VeloColorPickerScreen(this, "Hit Color", target, true, c -> {
							colorSwap.put(finalColor, c);
							layoutContent();
						}))));
				cx += 94;
				if (cx + 90 > contentX() + contentWidth()) {
					cx = contentX();
					belowHitY += 22;
				}
			}
			belowHitY += 26;
		}

		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, contentWidth() / 2 - 4, 20,
				Text.literal("Save"), b -> save()).primary());
		addDrawableChild(new VeloButton(contentX() + contentWidth() / 2 + 4, contentBottom() - 20, contentWidth() / 2 - 4, 20,
				Text.literal("Cancel"), b -> requestClose()));
	}

	private String hitModeLabel() {
		return switch (hitMode) {
			case NONE -> "None";
			case SEPARATE_IMAGE -> "Separate Image";
			case COLOR_SWAP -> "Color Swap";
		};
	}

	private void cycleHitMode() {
		hitMode = switch (hitMode) {
			case NONE -> CrosshairDefinition.HitMode.SEPARATE_IMAGE;
			case SEPARATE_IMAGE -> CrosshairDefinition.HitMode.COLOR_SWAP;
			case COLOR_SWAP -> CrosshairDefinition.HitMode.NONE;
		};
		if (hitMode == CrosshairDefinition.HitMode.SEPARATE_IMAGE && hitImage == null) {
			hitImage = copyOf(idleImage);
		}
		editingHit = false;
		layoutContent();
	}

	private static NativeImage blankCanvas(int size) {
		NativeImage image = new NativeImage(size, size, false);
		image.fillRect(0, 0, size, size, 0);
		return image;
	}

	/** Box-averages {@code source} down to {@code targetSize}x{@code targetSize} - simple nearest/area sampling, each destination pixel averages every source pixel that maps into its cell. */
	private static NativeImage downscale(NativeImage source, int targetSize) {
		int srcSize = source.getWidth();
		NativeImage result = new NativeImage(targetSize, targetSize, false);
		for (int dx = 0; dx < targetSize; dx++) {
			int sx0 = dx * srcSize / targetSize;
			int sx1 = Math.max(sx0 + 1, (dx + 1) * srcSize / targetSize);
			for (int dy = 0; dy < targetSize; dy++) {
				int sy0 = dy * srcSize / targetSize;
				int sy1 = Math.max(sy0 + 1, (dy + 1) * srcSize / targetSize);
				long a = 0;
				long r = 0;
				long g = 0;
				long b = 0;
				int count = 0;
				for (int sx = sx0; sx < sx1; sx++) {
					for (int sy = sy0; sy < sy1; sy++) {
						int argb = source.getColorArgb(sx, sy);
						a += (argb >>> 24) & 0xFF;
						r += (argb >>> 16) & 0xFF;
						g += (argb >>> 8) & 0xFF;
						b += argb & 0xFF;
						count++;
					}
				}
				int avgA = (int) (a / count);
				int avgR = (int) (r / count);
				int avgG = (int) (g / count);
				int avgB = (int) (b / count);
				result.setColorArgb(dx, dy, (avgA << 24) | (avgR << 16) | (avgG << 8) | avgB);
			}
		}
		return result;
	}

	private static NativeImage copyOf(NativeImage source) {
		NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), false);
		for (int x = 0; x < source.getWidth(); x++) {
			for (int y = 0; y < source.getHeight(); y++) {
				copy.setColorArgb(x, y, source.getColorArgb(x, y));
			}
		}
		return copy;
	}

	private static int[] uniqueColors(NativeImage image, int max) {
		java.util.LinkedHashSet<Integer> colors = new java.util.LinkedHashSet<>();
		for (int x = 0; x < image.getWidth() && colors.size() < max; x++) {
			for (int y = 0; y < image.getHeight() && colors.size() < max; y++) {
				int argb = image.getColorArgb(x, y);
				if ((argb >>> 24) != 0) {
					colors.add(argb | 0xFF000000);
				}
			}
		}
		return colors.stream().mapToInt(Integer::intValue).toArray();
	}

	private void importPng() {
		status = Text.literal("Opening file picker...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path file = NativeFileDialog.pickPngFile("Choose a crosshair PNG");
				if (file == null) {
					MinecraftClient.getInstance().execute(() -> status = Text.literal(""));
					return;
				}
				NativeImage rawImported = NativeImage.read(java.nio.file.Files.newInputStream(file));
				if (rawImported.getWidth() != rawImported.getHeight()) {
					int w = rawImported.getWidth();
					int h = rawImported.getHeight();
					rawImported.close();
					MinecraftClient.getInstance().execute(() -> status = Text.literal("Crosshair images must be square (that PNG is " + w + "x" + h + ")"));
					return;
				}
				int originalSize = rawImported.getWidth();
				// Canvas sizes above MAX_CANVAS_SIZE never actually fit the
				// 200px viewport: zoom is clamped to a 4px-per-cell minimum
				// (see the zoom formula below), so a big import just showed a
				// tiny, often entirely-blank corner of itself instead of
				// looking "not imported" - scaling down here keeps every
				// import in the same size range the working blank-canvas
				// sizes (8/16/32/64) already use.
				NativeImage imported;
				if (originalSize > MAX_CANVAS_SIZE) {
					imported = downscale(rawImported, MAX_CANVAS_SIZE);
					rawImported.close();
				} else {
					imported = rawImported;
				}
				MinecraftClient.getInstance().execute(() -> {
					// canvasSize used to stay whatever it was before the
					// import regardless of the imported image's real size -
					// every pixel read/write and the eventual save() kept
					// using the old, now-wrong dimension, which either cut
					// the import off, read out of bounds on it, or saved a
					// meta.json whose canvasSize didn't match idle.png's
					// actual size on disk (silently corrupting the
					// crosshair for every *other* piece of code that reads
					// canvasSize back out of that file later). Resizing the
					// other (non-imported) buffer to match keeps both
					// halves of a hit-mode crosshair the same size too.
					boolean sizeChanged = imported.getWidth() != canvasSize;
					canvasSize = imported.getWidth();
					if (editingHit) {
						hitImage.close();
						hitImage = imported;
						if (sizeChanged && idleImage.getWidth() != canvasSize) {
							idleImage.close();
							idleImage = blankCanvas(canvasSize);
						}
					} else {
						idleImage.close();
						idleImage = imported;
						if (sizeChanged && hitImage != null && hitImage.getWidth() != canvasSize) {
							hitImage.close();
							hitImage = blankCanvas(canvasSize);
						}
					}
					zoom = Math.max(4f, VIEWPORT_SIZE / (float) canvasSize);
					panX = 0;
					panY = 0;
					status = Text.literal("Imported " + file.getFileName()
							+ (originalSize > MAX_CANVAS_SIZE
									? " (scaled down from " + originalSize + "x" + originalSize + " to " + canvasSize + "x" + canvasSize + ")"
									: (sizeChanged ? " - canvas is now " + canvasSize + "x" + canvasSize : "")));
					layoutContent();
				});
			} catch (IOException | InterruptedException t) {
				String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
				MinecraftClient.getInstance().execute(() -> status = Text.literal("Import failed: " + message));
			}
		});
	}

	private void save() {
		if (name.isBlank()) {
			status = Text.literal("Type a name first.");
			return;
		}
		CrosshairDefinition definition = new CrosshairDefinition(id, name, canvasSize, hitMode, Map.copyOf(colorSwap));
		CrosshairManager.save(definition, idleImage, hitMode == CrosshairDefinition.HitMode.SEPARATE_IMAGE ? hitImage : null);
		requestClose();
	}

	@Override
	protected void requestClose() {
		// Only Save/Cancel call this (not opening the color picker, which
		// goes through client.setScreen() directly) - it's the one place
		// that means "the user is actually done with this editor," as
		// opposed to removed(), which vanilla also fires when this screen
		// is merely covered by a sub-dialog it expects to return to.
		trulyClosing = true;
		super.requestClose();
	}

	@Override
	public void removed() {
		// removed() fires both when this screen is genuinely done (Save/
		// Cancel -> requestClose() -> the close animation finishes) *and*
		// when it's just temporarily replaced by a sub-screen it opened
		// itself (e.g. the color picker) and fully expects to return to.
		// Closing the images unconditionally here closed them in the
		// second case too, crashing the very next render() the moment the
		// user came back from picking a color - only close them once
		// requestClose() has actually run.
		if (!trulyClosing) {
			super.removed();
			return;
		}
		idleImage.close();
		if (hitImage != null) {
			hitImage.close();
		}
		imagesClosed = true;
		super.removed();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// VeloWindow.render() (just below) synchronously calls
		// client.setScreen(returnScreen) on its last close-fade frame, which
		// calls our removed() *before returning* - so by the time control
		// comes back here, trulyClosing may already have closed the images.
		// Reading them below without this check crashed with "Image is not
		// allocated" on the very last frame of every Save/Cancel.
		super.render(context, mouseX, mouseY, delta);
		if (nameBox == null || imagesClosed) {
			return;
		}
		Theme theme = ThemeManager.active();
		int vx = canvasViewX();
		int vy = canvasViewY();
		VeloDraw.strokeRect(context, vx - 1, vy - 1, VIEWPORT_SIZE + 2, VIEWPORT_SIZE + 2, 0xFF000000);
		context.enableScissor(vx, vy, vx + VIEWPORT_SIZE, vy + VIEWPORT_SIZE);
		NativeImage image = activeImage();
		int cell = Math.max(1, Math.round(zoom));
		for (int cx = 0; cx < canvasSize; cx++) {
			int sx = vx + Math.round(panX + cx * zoom);
			if (sx + cell < vx || sx > vx + VIEWPORT_SIZE) {
				continue;
			}
			for (int cy = 0; cy < canvasSize; cy++) {
				int sy = vy + Math.round(panY + cy * zoom);
				if (sy + cell < vy || sy > vy + VIEWPORT_SIZE) {
					continue;
				}
				boolean dark = (cx + cy) % 2 == 0;
				context.fill(sx, sy, sx + cell, sy + cell, dark ? CHECKER : CHECKER_ALT);
				int argb = image.getColorArgb(cx, cy);
				if ((argb >>> 24) != 0) {
					context.fill(sx, sy, sx + cell, sy + cell, argb);
				}
			}
		}
		context.disableScissor();

		context.drawTextWithShadow(this.textRenderer, "Canvas: " + canvasSize + "x" + canvasSize + " - left-drag draws, right-drag pans, scroll zooms",
				contentX(), canvasViewY() - 11, theme.text());
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 34, theme.text());
	}

	private boolean insideCanvas(double mouseX, double mouseY) {
		return mouseX >= canvasViewX() && mouseX < canvasViewX() + VIEWPORT_SIZE
				&& mouseY >= canvasViewY() && mouseY < canvasViewY() + VIEWPORT_SIZE;
	}

	private int cellXAt(double mouseX) {
		return (int) Math.floor((mouseX - canvasViewX() - panX) / zoom);
	}

	private int cellYAt(double mouseY) {
		return (int) Math.floor((mouseY - canvasViewY() - panY) / zoom);
	}

	private void paintAt(double mouseX, double mouseY, boolean erase) {
		int cx = cellXAt(mouseX);
		int cy = cellYAt(mouseY);
		if (cx < 0 || cy < 0 || cx >= canvasSize || cy >= canvasSize) {
			return;
		}
		activeImage().setColorArgb(cx, cy, erase ? 0 : drawColor);
	}

	private void clearCanvas() {
		activeImage().fillRect(0, 0, canvasSize, canvasSize, 0);
		status = Text.literal("Cleared the " + (editingHit ? "hit" : "idle") + " canvas.");
		if (hitMode == CrosshairDefinition.HitMode.COLOR_SWAP) {
			layoutContent();
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (insideCanvas(click.x(), click.y())) {
			if (click.button() == 1) {
				panning = true;
				return true;
			}
			boolean erase = tool == Tool.ERASER || click.button() == 2;
			painting = true;
			erasingDrag = erase;
			paintAt(click.x(), click.y(), erase);
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (panning) {
			panX += offsetX;
			panY += offsetY;
			return true;
		}
		if (painting) {
			paintAt(click.x(), click.y(), erasingDrag);
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		boolean wasPainting = painting;
		painting = false;
		panning = false;
		// The Color Swap buttons are generated from whatever unique colors
		// were in the idle image the last time layoutContent() ran, so
		// drawing a brand-new color onto it left that list stale until
		// something else happened to rebuild the screen. Refresh once per
		// stroke (not per pixel dragged) so it stays live without rebuilding
		// every widget mid-drag.
		if (wasPainting && hitMode == CrosshairDefinition.HitMode.COLOR_SWAP) {
			layoutContent();
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (insideCanvas(mouseX, mouseY)) {
			float oldZoom = zoom;
			zoom = Math.clamp(zoom + (float) verticalAmount * 1.5f, 2f, 48f);
			// Zoom toward the cursor instead of the canvas corner, so
			// zooming in while pointed at a detail keeps it under the mouse.
			panX = (float) (mouseX - canvasViewX() - (mouseX - canvasViewX() - panX) * (zoom / oldZoom));
			panY = (float) (mouseY - canvasViewY() - (mouseY - canvasViewY() - panY) * (zoom / oldZoom));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}
}
