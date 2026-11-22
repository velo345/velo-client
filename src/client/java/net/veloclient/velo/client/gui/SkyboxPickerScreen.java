package net.veloclient.velo.client.gui;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Custom Skybox: every preset and imported picture as a preview card - click one to wear it. Presets bring a rendered
 * preview (textures/skybox/preview); imported pictures get a small thumbnail made from the picture itself.
 */
public final class SkyboxPickerScreen extends VeloWindow {

	private static final int COLUMNS = 3;
	/** Imported pictures: name -> thumbnail texture once made. */
	private static final Map<String, Identifier> IMPORTED_THUMBS = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> REQUESTED = new ConcurrentHashMap<>();

	private final List<String> names;
	private final Function<String, Identifier> presetPreview;
	private final Function<String, byte[]> importedThumbnail;
	private final Supplier<String> current;
	private final Consumer<String> pick;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private double scroll;

	/**
	 * @param presetPreview the preview texture of a preset, or null for an imported picture
	 * @param importedThumbnail PNG bytes of a small thumbnail for an imported picture (called off the render thread)
	 */
	public SkyboxPickerScreen(List<String> names, Function<String, Identifier> presetPreview, Function<String, byte[]> importedThumbnail,
			Supplier<String> current, Consumer<String> pick) {
		super(Text.literal("Choose a Skybox"), 560, 400);
		this.names = List.copyOf(names);
		this.presetPreview = presetPreview;
		this.importedThumbnail = importedThumbnail;
		this.current = current;
		this.pick = pick;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
	}

	private Identifier thumbnail(String name) {
		Identifier preset = presetPreview.apply(name);
		if (preset != null) {
			return preset;
		}
		Identifier made = IMPORTED_THUMBS.get(name);
		if (made == null && REQUESTED.putIfAbsent(name, true) == null) {
			Thread.ofVirtual().start(() -> {
				byte[] png = importedThumbnail.apply(name);
				if (png != null) {
					RemoteTextureLoader.fromBytes("skybox-thumb-" + name, png, false, id -> IMPORTED_THUMBS.put(name, id));
				}
			});
		}
		return made;
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int top = contentY();
		int bottom = contentBottom();
		int gap = 8;
		int cardW = (contentWidth() - gap * (COLUMNS - 1)) / COLUMNS;
		int thumbH = cardW * 9 / 16;
		int cardH = thumbH + 18;
		int rows = (names.size() + COLUMNS - 1) / COLUMNS;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows * (cardH + gap) - gap - (bottom - top))));
		String selected = current.get();
		context.enableScissor(x, top, x + contentWidth(), bottom);
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			int cx = x + (i % COLUMNS) * (cardW + gap);
			int cy = top + (i / COLUMNS) * (cardH + gap) - (int) scroll;
			if (cy + cardH < top || cy > bottom) {
				continue;
			}
			boolean hover = VeloUi.inside(mouseX, mouseY, cx, cy, cardW, cardH) && mouseY >= top && mouseY < bottom;
			boolean active = name.equals(selected);
			VeloDraw.fillRounded(context, cx, cy, cardW, cardH, 6, hover ? 0xFF2C2A30 : 0xFF1C1A1E);
			Identifier thumb = thumbnail(name);
			if (thumb != null) {
				boolean preset = presetPreview.apply(name) != null;
				// presets: 320x180 previews; imported thumbnails: 320x160 (2:1 panoramas), shown cropped to the card
				context.drawTexture(RenderPipelines.GUI_TEXTURED, thumb, cx + 3, cy + 3, 0f, 0f, cardW - 6, thumbH - 3,
						preset ? 320 : 288, preset ? 180 : 162, 320, preset ? 180 : 160);
			} else {
				context.drawCenteredTextWithShadow(this.textRenderer, "...", cx + cardW / 2, cy + thumbH / 2 - 4, 0xFF9A9098);
			}
			if (active || hover) {
				VeloDraw.strokeRounded(context, cx, cy, cardW, cardH, 6, active ? 0xFFD7262D : 0x55FFFFFF);
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(name, cardW - 10), cx + 6, cy + thumbH + 5, active ? 0xFFFFFFFF : 0xFFCFC6C4);
			int clipTop = Math.max(cy, top);
			int clipBottom = Math.min(cy + cardH, bottom);
			if (clipBottom > clipTop) {
				hits.add(new VeloUi.Hit(cx, clipTop, cardW, clipBottom - clipTop, () -> pick.accept(name)));
			}
		}
		context.disableScissor();
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= verticalAmount * 24;
		return true;
	}
}
