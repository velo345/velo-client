package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.skins.SkinLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Manage and switch skins in game (Options > Skin Customization > Velo Skins): every saved skin as a
 * card, equip with one click (uploads it to your Minecraft account), add from a username/UUID or a
 * PNG. Same library as the launcher's profile page.
 */
public final class VeloSkinsScreen extends VeloWindow {

	private static final Map<String, Identifier> TEXTURES = new HashMap<>();
	private static final int PREVIEW_W = 132;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget playerBox;
	private boolean slimImport;
	private String status = "Equip changes your skin on your Minecraft account. Rejoin a server to see it there.";
	private boolean busy;
	private float scroll;
	/** Skin shown in the 3D preview (clicked card); hovering a card previews it temporarily. */
	private String selectedId;
	private SkinLibrary.Skin hoveredSkin;
	private boolean hoveringCurrent;
	private net.minecraft.client.gui.widget.ClickableWidget preview;

	public VeloSkinsScreen(Screen parent) {
		super(Text.literal("Skins"), 700, 430);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		String previous = playerBox != null ? playerBox.getText() : "";
		playerBox = new TextFieldWidget(this.textRenderer, contentX() + PREVIEW_W + 10, contentY(), fieldWidth(), 16, Text.literal("Player"));
		playerBox.setPlaceholder(Text.literal("Username or UUID"));
		playerBox.setText(previous);
		addDrawableChild(playerBox);
		int stageH = previewStageHeight();
		preview = net.veloclient.velo.client.gui.widget.SkinModelPreview.create(PREVIEW_W - 16, stageH - 16, () -> {
			SkinLibrary.Skin skin = previewSkin();
			return skin == null ? null : texture(skin);
		}, () -> {
			SkinLibrary.Skin skin = previewSkin();
			return skin != null && skin.slim();
		});
		preview.setX(contentX() + 8);
		preview.setY(contentY() + 8);
		addDrawableChild(preview);
	}

	/** Username box width: whatever's left of the toolbar after the three buttons. */
	private int fieldWidth() {
		return Math.max(70, Math.min(170, contentWidth() - PREVIEW_W - 10 - 64 - 74 - 74 - 18));
	}

	private int previewStageHeight() {
		return Math.max(80, contentBottom() - 14 - contentY() - 66);
	}

	private SkinLibrary.Skin previewSkin() {
		if (hoveredSkin != null) {
			return hoveredSkin;
		}
		if (hoveringCurrent || selectedId == null) {
			// null = the skin your account has right now.
			return null;
		}
		for (SkinLibrary.Skin skin : SkinLibrary.all()) {
			if (skin.id().equals(selectedId)) {
				return skin;
			}
		}
		return null;
	}

	private static Identifier texture(SkinLibrary.Skin skin) {
		return TEXTURES.computeIfAbsent(skin.id(), id -> {
			try (var in = Files.newInputStream(SkinLibrary.png(skin))) {
				NativeImage image = modernize(NativeImage.read(in));
				Identifier tex = Identifier.of("velo-client", "skin_" + id);
				MinecraftClient.getInstance().getTextureManager().registerTexture(tex, new NativeImageBackedTexture(() -> "skin " + id, image));
				return tex;
			} catch (Exception e) {
				return null;
			}
		});
	}

	/**
	 * Converts an old 64x32 skin to the 64x64 layout the player model expects (mirrored left
	 * limbs, see-through hat if it was fully opaque), the same way vanilla loads them.
	 */
	private static NativeImage modernize(NativeImage source) {
		if (source.getHeight() != 32) {
			return source;
		}
		NativeImage image = new NativeImage(64, 64, true);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				image.setColorArgb(x, y, source.getColorArgb(x, y));
			}
		}
		source.close();
		copy(image, 4, 16, 16, 32, 4, 4);
		copy(image, 8, 16, 16, 32, 4, 4);
		copy(image, 0, 20, 24, 32, 4, 12);
		copy(image, 4, 20, 16, 32, 4, 12);
		copy(image, 8, 20, 8, 32, 4, 12);
		copy(image, 12, 20, 16, 32, 4, 12);
		copy(image, 44, 16, -8, 32, 4, 4);
		copy(image, 48, 16, -8, 32, 4, 4);
		copy(image, 40, 20, 0, 32, 4, 12);
		copy(image, 44, 20, -8, 32, 4, 12);
		copy(image, 48, 20, -16, 32, 4, 12);
		copy(image, 52, 20, -8, 32, 4, 12);
		boolean hatHasAlpha = false;
		for (int y = 0; y < 16 && !hatHasAlpha; y++) {
			for (int x = 32; x < 64; x++) {
				if ((image.getColorArgb(x, y) >>> 24) < 128) {
					hatHasAlpha = true;
					break;
				}
			}
		}
		if (!hatHasAlpha) {
			for (int y = 0; y < 16; y++) {
				for (int x = 32; x < 64; x++) {
					image.setColorArgb(x, y, 0);
				}
			}
		}
		return image;
	}

	/** Horizontally mirrored copy of a w x h block, offset by (dx, dy). */
	private static void copy(NativeImage image, int x, int y, int dx, int dy, int w, int h) {
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				image.setColorArgb(x + dx + (w - 1 - i), y + dy + j, image.getColorArgb(x + i, y + j));
			}
		}
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		hoveredSkin = null;
		hoveringCurrent = false;
		renderPreviewPanel(context, mouseX, mouseY);
		int x = contentX() + PREVIEW_W + 10;
		int y = contentY();
		int width = contentWidth() - PREVIEW_W - 10;
		hits.add(VeloUi.pill(context, x + fieldWidth() + 6, y, 64, 16, "Add skin", 1, mouseX, mouseY, this::addPlayer));
		hits.add(VeloUi.pill(context, x + width - 74, y, 74, 16, "Import PNG", 0, mouseX, mouseY, this::importPng));
		hits.add(VeloUi.pill(context, x + width - 152, y, 74, 16, slimImport ? "Slim arms" : "Classic arms", slimImport ? 1 : 0,
				mouseX, mouseY, () -> slimImport = !slimImport));

		List<SkinLibrary.Skin> skins = new ArrayList<>();
		// Slot 0 is "Current skin" (what your account wears right now), then the library.
		skins.add(null);
		skins.addAll(SkinLibrary.all());
		String equipped = SkinLibrary.equippedId();
		int top = y + 24;
		int bottom = contentBottom() - 14;
		int columns = Math.max(1, (width + 8) / (88 + 8));
		int cardW = (width + 8) / columns - 8;
		int cardH = 118;
		int rows = (skins.size() + columns - 1) / columns;
		float max = Math.max(0, rows * (cardH + 8) - (bottom - top));
		scroll = Math.clamp(scroll, 0, max);
		context.enableScissor(x, top, x + width, bottom);
		for (int i = 0; i < skins.size(); i++) {
			SkinLibrary.Skin skin = skins.get(i);
			int cx = x + (i % columns) * (cardW + 8);
			int cy = top + (i / columns) * (cardH + 8) - Math.round(scroll);
			if (skin == null) {
				renderCurrentCard(context, cx, cy, cardW, cardH, top, bottom, mouseX, mouseY);
				continue;
			}
			boolean isEquipped = skin.id().equals(equipped);
			boolean hovered = VeloUi.inside(mouseX, mouseY, cx, cy, cardW, cardH) && mouseY >= top && mouseY < bottom;
			boolean selected = skin.id().equals(selectedId) && !hovered;
			if (hovered) {
				hoveredSkin = skin;
			}
			VeloDraw.fillRounded(context, cx, cy, cardW, cardH, 8, hovered || selected ? VeloStyle.cardHover() : VeloStyle.card());
			if (isEquipped) {
				VeloDraw.strokeRounded(context, cx, cy, cardW, cardH, 8, VeloStyle.accent());
			} else if (selected) {
				VeloDraw.strokeRounded(context, cx, cy, cardW, cardH, 8, VeloUi.withAlpha(VeloStyle.text(), 0x40));
			}
			Identifier tex = texture(skin);
			if (tex != null) {
				drawFront(context, tex, skin.slim(), cx + cardW / 2 - 16, cy + 8, 2);
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(skin.name(), cardW - 8), cx + 6, cy + 76, VeloStyle.text());
			context.drawTextWithShadow(this.textRenderer, skin.slim() ? "Slim" : "Classic", cx + 6, cy + 86, VeloStyle.textFaint());
			boolean visible = cy + cardH > top && cy < bottom;
			VeloUi.Hit equip = VeloUi.pill(context, cx + 4, cy + cardH - 20, cardW - 26, 16,
					isEquipped ? "Equipped" : busy ? "..." : "Equip", isEquipped ? 2 : 1, mouseX, mouseY, () -> {
						if (!isEquipped && !busy) {
							equip(skin);
						}
					});
			VeloUi.Hit delete = VeloUi.pill(context, cx + cardW - 20, cy + cardH - 20, 16, 16, "x", 0, mouseX, mouseY, () ->
					this.client.setScreen(new VeloConfirmScreen(this, "Delete \"" + skin.name() + "\"?",
							"It's removed from your skin library (your current Minecraft skin stays).", "Delete", () -> {
								try {
									SkinLibrary.delete(skin);
								} catch (Exception e) {
									status = "Couldn't delete: " + e.getMessage();
								}
							})));
			if (visible) {
				hits.add(equip);
				hits.add(delete);
				hits.add(new VeloUi.Hit(cx, Math.max(cy, top), cardW, Math.min(cy + cardH, bottom) - Math.max(cy, top), () -> selectedId = skin.id()));
			}
		}
		context.disableScissor();
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, contentWidth()), contentX(), contentBottom() - 9, VeloStyle.textMuted());
	}

	/** The "Current skin" card: your account's skin right now, with a button to keep a copy in the library. */
	private void renderCurrentCard(DrawContext context, int cx, int cy, int cardW, int cardH, int top, int bottom, int mouseX, int mouseY) {
		boolean hovered = VeloUi.inside(mouseX, mouseY, cx, cy, cardW, cardH) && mouseY >= top && mouseY < bottom;
		boolean selected = selectedId == null && !hovered;
		if (hovered) {
			hoveringCurrent = true;
		}
		VeloDraw.fillRounded(context, cx, cy, cardW, cardH, 8, hovered || selected ? VeloStyle.cardHover() : VeloStyle.card());
		VeloDraw.strokeRounded(context, cx, cy, cardW, cardH, 8, selected ? VeloUi.withAlpha(VeloStyle.text(), 0x40) : VeloStyle.border());
		Identifier tex = net.veloclient.velo.client.gui.widget.SkinModelPreview.currentTexture();
		if (tex != null) {
			drawFront(context, tex, net.veloclient.velo.client.gui.widget.SkinModelPreview.currentSlim(), cx + cardW / 2 - 16, cy + 8, 2);
		}
		context.drawTextWithShadow(this.textRenderer, "Current skin", cx + 6, cy + 76, VeloStyle.text());
		context.drawTextWithShadow(this.textRenderer, "On your account", cx + 6, cy + 86, VeloStyle.textFaint());
		VeloUi.Hit save = VeloUi.pill(context, cx + 4, cy + cardH - 20, cardW - 8, 16, busy ? "..." : "Save copy", 0, mouseX, mouseY, () -> {
			if (!busy) {
				saveCurrent();
			}
		});
		if (cy + cardH > top && cy < bottom) {
			hits.add(save);
			hits.add(new VeloUi.Hit(cx, Math.max(cy, top), cardW, Math.min(cy + cardH, bottom) - Math.max(cy, top), () -> selectedId = null));
		}
	}

	/** Copies your current skin into the library (so you can switch back to it later). */
	private void saveCurrent() {
		busy = true;
		status = "Saving your current skin...";
		//? if <26.1 {
		String name = MinecraftClient.getInstance().getSession().getUsername();
		//?} else {
		/*String name = net.minecraft.client.Minecraft.getInstance().getUser().getName();
		*///?}
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				SkinLibrary.Skin skin = SkinLibrary.addFromPlayer(name);
				done("Saved your current skin as \"" + skin.name() + "\".");
			} catch (Exception e) {
				done(e.getMessage());
			}
		});
	}

	private void renderPreviewPanel(DrawContext context, int mouseX, int mouseY) {
		int x = contentX();
		int y = contentY();
		int bottom = contentBottom() - 14;
		VeloDraw.fillRounded(context, x, y, PREVIEW_W, bottom - y, 10, VeloStyle.card());
		int stageH = previewStageHeight();
		net.veloclient.velo.client.gui.widget.VeloCapeTile.drawStage(context, x + 4, y + 4, PREVIEW_W - 8, stageH - 8, 0f, false);
		SkinLibrary.Skin skin = previewSkin();
		int textY = y + stageH + 4;
		if (skin == null) {
			boolean slim = net.veloclient.velo.client.gui.widget.SkinModelPreview.currentSlim();
			context.drawCenteredTextWithShadow(this.textRenderer, "Your current skin", x + PREVIEW_W / 2, textY, VeloStyle.text());
			context.drawCenteredTextWithShadow(this.textRenderer, (slim ? "Slim" : "Classic") + " arms  -  drag to turn",
					x + PREVIEW_W / 2, textY + 11, VeloStyle.textFaint());
			VeloUi.pill(context, x + 8, bottom - 24, PREVIEW_W - 16, 16, "Wearing it", 2, mouseX, mouseY, () -> { });
			return;
		}
		boolean isEquipped = skin.id().equals(SkinLibrary.equippedId());
		context.drawCenteredTextWithShadow(this.textRenderer, VeloUi.trim(skin.name(), PREVIEW_W - 12), x + PREVIEW_W / 2, textY, VeloStyle.text());
		context.drawCenteredTextWithShadow(this.textRenderer, (skin.slim() ? "Slim" : "Classic") + " arms  -  drag to turn",
				x + PREVIEW_W / 2, textY + 11, VeloStyle.textFaint());
		hits.add(VeloUi.pill(context, x + 8, bottom - 24, PREVIEW_W - 16, 16,
				isEquipped ? "Equipped" : busy ? "..." : "Equip this skin", isEquipped ? 2 : 1, mouseX, mouseY, () -> {
					if (!isEquipped && !busy) {
						equip(skin);
					}
				}));
	}

	/** Flat front view of a 64x64 skin texture at {@code scale} GUI px per skin pixel (16x32 skin px). */
	private static void drawFront(DrawContext context, Identifier tex, boolean slim, int x, int y, int scale) {
		int armW = slim ? 3 : 4;
		int texH = 64;
		part(context, tex, 8, 8, 8, 8, x + 4 * scale, y, scale, texH);
		part(context, tex, 20, 20, 8, 12, x + 4 * scale, y + 8 * scale, scale, texH);
		part(context, tex, 44, 20, armW, 12, x + (4 - armW) * scale, y + 8 * scale, scale, texH);
		part(context, tex, 4, 20, 4, 12, x + 4 * scale, y + 20 * scale, scale, texH);
		part(context, tex, 36, 52, armW, 12, x + 12 * scale, y + 8 * scale, scale, texH);
		part(context, tex, 20, 52, 4, 12, x + 8 * scale, y + 20 * scale, scale, texH);
		// Second layer (hat, jacket, sleeves, pants).
		part(context, tex, 40, 8, 8, 8, x + 4 * scale, y, scale, texH);
		part(context, tex, 20, 36, 8, 12, x + 4 * scale, y + 8 * scale, scale, texH);
		part(context, tex, 44, 36, armW, 12, x + (4 - armW) * scale, y + 8 * scale, scale, texH);
		part(context, tex, 52, 52, armW, 12, x + 12 * scale, y + 8 * scale, scale, texH);
		part(context, tex, 4, 36, 4, 12, x + 4 * scale, y + 20 * scale, scale, texH);
		part(context, tex, 4, 52, 4, 12, x + 8 * scale, y + 20 * scale, scale, texH);
	}

	private static void part(DrawContext context, Identifier tex, int u, int v, int w, int h, int x, int y, int scale, int texH) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x, y, (float) u, (float) v, w * scale, h * scale, w, h, 64, texH);
	}

	private void addPlayer() {
		String query = playerBox.getText().trim();
		if (query.isEmpty() || busy) {
			status = "Type a username or UUID first.";
			return;
		}
		busy = true;
		status = "Looking up " + query + "...";
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				SkinLibrary.Skin skin = SkinLibrary.addFromPlayer(query);
				done("Added " + skin.name() + "'s skin.");
				MinecraftClient.getInstance().execute(() -> playerBox.setText(""));
			} catch (Exception e) {
				done(e.getMessage());
			}
		});
	}

	private void importPng() {
		boolean slim = slimImport;
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path file = NativeFileDialog.pickPngFile("Choose a skin PNG (64x64)");
				if (file == null) {
					return;
				}
				String name = file.getFileName().toString().replaceFirst("\\.png$", "");
				SkinLibrary.Skin skin = SkinLibrary.addPng(name, Files.readAllBytes(file), slim);
				done("Added " + skin.name() + ".");
			} catch (Exception e) {
				done("Import failed: " + e.getMessage());
			}
		});
	}

	private void equip(SkinLibrary.Skin skin) {
		busy = true;
		status = "Uploading " + skin.name() + "...";
		String token = SkinLibrary.gameAccessToken();
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				SkinLibrary.equip(skin, token);
				net.veloclient.velo.client.gui.widget.SkinModelPreview.refreshCurrent();
				done("Skin changed to " + skin.name() + " - rejoin a server (or reopen your world) to see it.");
			} catch (Exception e) {
				done(e.getMessage());
			}
		});
	}

	private void done(String message) {
		MinecraftClient.getInstance().execute(() -> {
			busy = false;
			status = message == null ? "Something went wrong" : message;
		});
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
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (playerBox != null && playerBox.isFocused() && (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
				|| input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
			addPlayer();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= (float) verticalAmount * 30;
		return true;
	}
}
