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
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
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
	private static final int CARD_H = 124;
	private static final int CARD_GAP = 8;

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
		super(Text.literal("Locker"), 940, 580);
		returnTo(parent);
		onClosed(net.veloclient.velo.client.skins.SkinTryOn::clear);
	}

	// The Locker's Skin tab: the other tabs are CapeEquipScreen.

	@Override
	protected String[] headerTabs() {
		return CapeEquipScreen.LOCKER_TABS;
	}

	@Override
	protected int headerTab() {
		return 0;
	}

	@Override
	protected void selectHeaderTab(int index) {
		if (index != 0) {
			net.veloclient.velo.client.skins.SkinTryOn.clear();
			this.client.setScreen(CapeEquipScreen.open(returnScreen(), index));
		}
	}

	private int showcaseX() {
		return contentX() + CapeEquipScreen.SLOTS_W + 12;
	}

	private int gridX() {
		return showcaseX() + CapeEquipScreen.SHOWCASE_WIDTH + 14;
	}

	private int gridW() {
		return contentX() + contentWidth() - gridX();
	}

	/** The toolbar under the heading: username box, Add, then arms + Import PNG on the right. */
	private int toolbarY() {
		return contentY() + 22;
	}

	private int fieldWidth() {
		return Math.max(90, Math.min(190, gridW() - 64 - 96 - 90 - 24));
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		String previous = playerBox != null ? playerBox.getText() : "";
		playerBox = new TextFieldWidget(this.textRenderer, gridX() + 22, toolbarY() + 6, fieldWidth() - 28, 12, Text.literal("Player"));
		playerBox.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder("Add by username or UUID"));
		playerBox.setDrawsBackground(false);
		playerBox.setText(previous);
		addDrawableChild(playerBox);
		int x = showcaseX() + 6;
		int y = contentY() + 6;
		int w = CapeEquipScreen.SHOWCASE_WIDTH - 12;
		int h = contentBottom() - contentY() - 76;
		// Your player wearing the skin, with your cape, wings and aura (on the title screen too).
		preview = new net.veloclient.velo.client.gui.widget.EntityPreviewWidget(x, y, w, h).noStage().zoom(1.35f).spin(0f, 20f);
		addDrawableChild(preview);
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
		CapeEquipScreen.drawSlots(context, this.textRenderer, contentX(), contentY(), 0, mouseX, mouseY);
		CapeEquipScreen.drawLoadouts(context, this.textRenderer, contentX(), contentY(), contentBottom(), mouseX, mouseY);
		renderGrid(context, mouseX, mouseY);
		// Try the hovered / selected skin on your player for the live preview.
		SkinLibrary.Skin shown = previewSkin();
		if (shown == null) {
			net.veloclient.velo.client.skins.SkinTryOn.clear();
		} else {
			net.veloclient.velo.client.skins.SkinTryOn.set(texture(shown), shown.slim());
		}
		renderShowcase(context, mouseX, mouseY);
	}

	/** Heading, toolbar and the skin cards. */
	private void renderGrid(DrawContext context, int mouseX, int mouseY) {
		int x = gridX();
		int width = gridW();
		List<SkinLibrary.Skin> library = SkinLibrary.all();
		Text heading = MenuKit.display("Skins");
		MenuKit.text(context, this.textRenderer, heading, x, contentY() + 1, MenuKit.TEXT);
		Text count = MenuKit.small(library.size() + " SAVED");
		int hx = x + this.textRenderer.getWidth(heading) + 8;
		MenuKit.text(context, this.textRenderer, count, hx, contentY() + 4, MenuKit.TEXT_3);
		Text hint = MenuKit.ui("hover or click one to try it on");
		if (hx + this.textRenderer.getWidth(count) + 16 + this.textRenderer.getWidth(hint) <= x + width) {
			MenuKit.text(context, this.textRenderer, hint, x + width - this.textRenderer.getWidth(hint), contentY() + 2, MenuKit.TEXT_3);
		}

		// Toolbar: [search icon | username box] [Add]   ...   [Classic | Slim] [Import PNG]
		int ty = toolbarY();
		int fw = fieldWidth();
		boolean focused = playerBox != null && playerBox.isFocused();
		VeloStyle.drawInputField(context, x, ty, fw, 24, focused, MenuKit.inside(mouseX, mouseY, x, ty, fw, 24));
		MenuKit.icon(context, "friends", x + 7, ty + 7, 10, MenuKit.TEXT_3);
		hits.add(button(context, x + fw + 6, ty, 56, "Add", true, mouseX, mouseY, this::addPlayer));
		int importW = 88;
		int ix = x + width - importW;
		hits.add(button(context, ix, ty, importW, "Import PNG", false, mouseX, mouseY, this::importPng));
		// Arms for imported PNGs: a two-way segmented switch.
		int segW = 46;
		int sx = ix - 8 - segW * 2;
		VeloDraw.fillRounded(context, sx, ty, segW * 2, 24, 7, 0xFF131318);
		VeloDraw.strokeRounded(context, sx, ty, segW * 2, 24, 7, 0x17FFFFFF);
		VeloDraw.fillRounded(context, sx + 2 + (slimImport ? segW : 0), ty + 2, segW - 4, 20, 5, VeloUi.withAlpha(MenuKit.accent(), 0x40));
		for (int i = 0; i < 2; i++) {
			Text label = MenuKit.ui(i == 0 ? "Classic" : "Slim");
			boolean on = (i == 1) == slimImport;
			MenuKit.text(context, this.textRenderer, label, sx + i * segW + (segW - this.textRenderer.getWidth(label)) / 2, ty + 8, on ? MenuKit.TEXT : MenuKit.TEXT_3);
			boolean slim = i == 1;
			hits.add(new VeloUi.Hit(sx + i * segW, ty, segW, 24, () -> slimImport = slim));
		}

		// Cards: "your account" first, then the library.
		List<SkinLibrary.Skin> skins = new ArrayList<>();
		skins.add(null);
		skins.addAll(library);
		String equipped = SkinLibrary.equippedId();
		int top = ty + 34;
		int bottom = contentBottom() - 16;
		int columns = Math.max(3, (width + CARD_GAP) / (112 + CARD_GAP));
		int cardW = (width - (columns - 1) * CARD_GAP) / columns;
		int rows = (skins.size() + columns - 1) / columns;
		float max = Math.max(0, rows * (CARD_H + CARD_GAP) - CARD_GAP - (bottom - top));
		scroll = Math.clamp(scroll, 0, max);
		context.enableScissor(x, top, x + width, bottom);
		for (int i = 0; i < skins.size(); i++) {
			SkinLibrary.Skin skin = skins.get(i);
			int cx = x + (i % columns) * (cardW + CARD_GAP);
			int cy = top + (i / columns) * (CARD_H + CARD_GAP) - Math.round(scroll);
			if (cy + CARD_H < top || cy > bottom) {
				continue;
			}
			boolean hovered = VeloUi.inside(mouseX, mouseY, cx, cy, cardW, CARD_H) && mouseY >= top && mouseY < bottom;
			boolean selected = skin == null ? selectedId == null : skin.id().equals(selectedId);
			boolean isEquipped = skin != null && skin.id().equals(equipped);
			if (hovered) {
				if (skin == null) {
					hoveringCurrent = true;
				} else {
					hoveredSkin = skin;
				}
			}
			float t = MenuKit.hover(skin == null ? "skin:current" : "skin:" + skin.id(), hovered);
			int edge = selected ? MenuKit.accent() : VeloAnim.lerpArgb(0x17FFFFFF, 0x38FFFFFF, t);
			VeloDraw.fillRounded(context, cx, cy, cardW, CARD_H, 9, VeloAnim.lerpArgb(0xFF131318, 0xFF1A1A21, t));
			if (selected) {
				VeloDraw.fillRoundedGradient(context, cx, cy, cardW, CARD_H, 9, VeloUi.withAlpha(MenuKit.accent(), 0x1C), 0x00131318);
			}
			VeloDraw.strokeRounded(context, cx, cy, cardW, CARD_H, 9, edge);
			// The skin, flat front view, on a soft stage.
			VeloDraw.fillRounded(context, cx + 6, cy + 6, cardW - 12, 82, 7, 0xFF0E0E12);
			Identifier tex = skin == null ? net.veloclient.velo.client.gui.widget.SkinModelPreview.currentTexture() : texture(skin);
			boolean slim = skin == null ? net.veloclient.velo.client.gui.widget.SkinModelPreview.currentSlim() : skin.slim();
			if (tex != null) {
				drawFront(context, tex, slim, cx + cardW / 2 - 16, cy + 6 + 9 - Math.round(2 * t), 2);
			}
			String name = skin == null ? "Your account" : skin.name();
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(name), cardW - 16), cx + 8, cy + 95, MenuKit.TEXT);
			String sub = skin == null ? "Wearing now" : slim ? "Slim arms" : "Classic arms";
			MenuKit.text(context, this.textRenderer, MenuKit.small(sub.toUpperCase(java.util.Locale.ROOT)), cx + 8, cy + 108, MenuKit.TEXT_3);
			if (isEquipped) {
				Text tag = MenuKit.small("EQUIPPED");
				int tw = this.textRenderer.getWidth(tag) + 10;
				VeloDraw.fillRounded(context, cx + cardW - tw - 10, cy + 12, tw, 12, 4, VeloUi.withAlpha(MenuKit.accent(), 0xD0));
				MenuKit.text(context, this.textRenderer, tag, cx + cardW - tw - 5, cy + 14, 0xFFFFFFFF);
			}
			int ay = Math.max(cy, top);
			int ah = Math.min(cy + CARD_H, bottom) - ay;
			if (skin == null) {
				// Keep a copy of what your account wears, so you can switch back later.
				if (hovered) {
					hits.add(cornerBadge(context, cx + cardW - 26, cy + 12, "+", false, mouseX, mouseY, () -> {
						if (!busy) {
							saveCurrent();
						}
					}));
				}
				hits.add(new VeloUi.Hit(cx, ay, cardW, ah, () -> selectedId = null));
				continue;
			}
			if (hovered && !isEquipped) {
				hits.add(cornerBadge(context, cx + cardW - 26, cy + 12, "x", true, mouseX, mouseY, () ->
						this.client.setScreen(new VeloConfirmScreen(this, "Delete \"" + skin.name() + "\"?",
								"It's removed from your skin library (your current Minecraft skin stays).", "Delete", () -> {
									try {
										SkinLibrary.delete(skin);
									} catch (Exception e) {
										status = "Couldn't delete: " + e.getMessage();
									}
								}))));
			}
			hits.add(new VeloUi.Hit(cx, ay, cardW, ah, () -> selectedId = skin.id()));
		}
		context.disableScissor();
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), width), x, contentBottom() - 9, MenuKit.TEXT_3);
	}

	/** Round badge in a card's corner: + (save a copy) or × (delete). */
	private VeloUi.Hit cornerBadge(DrawContext context, int x, int y, String kind, boolean danger, int mouseX, int mouseY, Runnable action) {
		boolean hover = VeloUi.inside(mouseX, mouseY, x, y, 16, 16);
		VeloDraw.fillCircle(context, x + 8f, y + 8f, 8f, hover ? (danger ? 0xFFFF4D5A : MenuKit.accent()) : 0xE0202027);
		if (kind.equals("x")) {
			VeloDraw.cross(context, x + 8f, y + 8f, 4f, 1.2f, 0xFFFFFFFF);
		} else {
			context.fill(x + 4, y + 7, x + 12, y + 9, 0xFFFFFFFF);
			context.fill(x + 7, y + 4, x + 9, y + 12, 0xFFFFFFFF);
		}
		return new VeloUi.Hit(x, y, 16, 16, action);
	}

	/** A 24px toolbar button (accent or quiet). */
	private VeloUi.Hit button(DrawContext context, int x, int y, int w, String label, boolean primary, int mouseX, int mouseY, Runnable action) {
		boolean hover = VeloUi.inside(mouseX, mouseY, x, y, w, 24);
		int fill = primary ? (hover ? VeloAnim.lerpArgb(MenuKit.accent(), 0xFFFFFFFF, 0.15f) : MenuKit.accent()) : hover ? 0x1AFFFFFF : 0x0DFFFFFF;
		VeloDraw.fillRounded(context, x, y, w, 24, 7, fill);
		if (!primary) {
			VeloDraw.strokeRounded(context, x, y, w, 24, 7, hover ? 0x38FFFFFF : 0x17FFFFFF);
		}
		Text t = MenuKit.uiBold(label);
		MenuKit.text(context, this.textRenderer, t, x + (w - this.textRenderer.getWidth(t)) / 2, y + 8, primary ? MenuKit.onAccent() : MenuKit.TEXT);
		return new VeloUi.Hit(x, y, w, 24, action);
	}

	/** The stage with the live preview, the skin's name and the Equip button. */
	private void renderShowcase(DrawContext context, int mouseX, int mouseY) {
		int x = showcaseX();
		int y = contentY();
		int w = CapeEquipScreen.SHOWCASE_WIDTH;
		int h = contentBottom() - y;
		VeloDraw.fillRounded(context, x, y, w, h, 10, 0xFF111116);
		VeloDraw.fillRoundedGradient(context, x, y, w, h, 10, VeloUi.withAlpha(MenuKit.accent(), 0x14), 0x00111116);
		VeloDraw.strokeRounded(context, x, y, w, h, 10, 0x17FFFFFF);
		SkinLibrary.Skin skin = previewSkin();
		boolean slim = skin == null ? net.veloclient.velo.client.gui.widget.SkinModelPreview.currentSlim() : skin.slim();
		String name = skin == null ? "Your account's skin" : skin.name();
		int by = contentBottom() - 30;
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.displaySm(name), w - 16), x + 10, by - 34, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.ui((slim ? "Slim" : "Classic") + " arms  ·  drag to turn"), x + 10, by - 19, MenuKit.TEXT_3);
		if (skin == null) {
			hits.add(button(context, x + 8, by, w - 16, busy ? "Saving..." : "Save a copy", false, mouseX, mouseY, () -> {
				if (!busy) {
					saveCurrent();
				}
			}));
			return;
		}
		boolean isEquipped = skin.id().equals(SkinLibrary.equippedId());
		hits.add(button(context, x + 8, by, w - 16, isEquipped ? "Equipped" : busy ? "Uploading..." : "Equip this skin", !isEquipped, mouseX, mouseY, () -> {
			if (!isEquipped && !busy) {
				equip(skin);
			}
		}));
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
		if (CapeEquipScreen.loadoutClick(click.x(), click.y(), click.button(), contentX(), contentY(), contentBottom())) {
			return true;
		}
		int slot = CapeEquipScreen.slotAt((int) click.x(), (int) click.y(), contentX(), contentY());
		if (slot > 0) {
			selectHeaderTab(slot);
			return true;
		}
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
		if (playerBox != null && playerBox.isFocused() && (input.key() == net.veloclient.velo.client.util.Keys.ENTER
				|| input.key() == net.veloclient.velo.client.util.Keys.KP_ENTER)) {
			addPlayer();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX < gridX()) {
			// Over the stage: zoom the preview.
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		scroll -= (float) verticalAmount * 30;
		return true;
	}
}
