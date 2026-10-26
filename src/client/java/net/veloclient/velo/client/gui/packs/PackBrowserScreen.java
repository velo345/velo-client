package net.veloclient.velo.client.gui.packs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.FileManagerOpener;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The pack editor's home: everything in a folder pack sorted into categories (blocks, items, entities,
 * interface, particles... models, sounds, languages, shaders), textures as a thumbnail grid, other files as a
 * list that opens them in the system's editor. "From the game" lists every loaded asset so any texture or file
 * can be overridden with one click. Textures open in {@link TextureEditorScreen}.
 */
public final class PackBrowserScreen extends VeloWindow {

	private static final int RAIL_W = 156;
	private static final int[] TILE_SIZES = {36, 48, 64};

	private final VeloPacksScreen packs;
	private final Path root;
	private final String packId;
	private final String packTitle;
	private final ThumbAtlas thumbs = new ThumbAtlas();
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private volatile PackContents contents;
	private volatile Map<PackContents.Category, List<PackContents.Item>> game;
	private boolean gameLoading;
	private boolean gameMode;
	private PackContents.Category category;
	private boolean infoPage = true;
	private TextFieldWidget search;
	private TextFieldWidget descriptionField;
	private String query = "";
	private String description;
	private int tileSizeIndex = 1;
	private float scroll;
	private float scrollTarget;
	private float maxScroll;
	private int[] gridArea = new int[4];
	private String status = "";
	private long statusAt;
	private List<String> tooltip = List.of();
	private Set<String> packKeys = new HashSet<>();
	private boolean finished;

	public PackBrowserScreen(VeloPacksScreen packs, Path root, String packId, String title) {
		super(Text.literal(title), 940, 540);
		this.packs = packs;
		this.root = root;
		this.packId = packId;
		this.packTitle = title;
		returnTo(packs);
		rescan();
		this.description = readDescription();
		PreviewModels.warmUp();
	}

	private void rescan() {
		CompletableFuture.runAsync(() -> {
			PackContents scanned = PackContents.scan(root);
			Set<String> keys = new HashSet<>();
			for (List<PackContents.Item> list : scanned.items.values()) {
				for (PackContents.Item item : list) {
					keys.add(item.key());
				}
			}
			MinecraftClient.getInstance().execute(() -> {
				contents = scanned;
				packKeys = keys;
			});
		});
	}

	private void loadGame() {
		if (game != null || gameLoading) {
			return;
		}
		gameLoading = true;
		CompletableFuture.runAsync(() -> {
			Map<PackContents.Category, List<PackContents.Item>> out = PackContents.gameTextures();
			addGameFiles(out, PackContents.Category.MODELS, "models", ".json");
			addGameFiles(out, PackContents.Category.BLOCKSTATES, "blockstates", ".json");
			addGameFiles(out, PackContents.Category.ITEM_DEFINITIONS, "items", ".json");
			addGameFiles(out, PackContents.Category.LANGUAGES, "lang", ".json");
			addGameFiles(out, PackContents.Category.FONTS, "font", ".json");
			addGameFiles(out, PackContents.Category.SHADERS, "shaders", "");
			addGameFiles(out, PackContents.Category.SHADERS, "post_effect", ".json");
			addGameFiles(out, PackContents.Category.SPLASHES, "texts", "");
			addGameFiles(out, PackContents.Category.SOUNDS, "sounds", ".ogg");
			if (PackBridge.readGame("minecraft:sounds.json") != null) {
				out.get(PackContents.Category.SOUNDS).add(0, new PackContents.Item("minecraft:sounds.json", PackContents.Category.SOUNDS, "Sounds", false, false, 0));
			}
			for (String folder : new String[] {"atlases", "equipment", "particles", "waypoint_style"}) {
				addGameFiles(out, PackContents.Category.OTHER, folder, ".json");
			}
			MinecraftClient.getInstance().execute(() -> {
				game = out;
				gameLoading = false;
			});
		});
	}

	private static void addGameFiles(Map<PackContents.Category, List<PackContents.Item>> out, PackContents.Category category, String folder, String suffix) {
		List<PackContents.Item> list = out.get(category);
		for (String key : PackBridge.listGame(folder, suffix)) {
			if (key.endsWith(".mcmeta") && suffix.isEmpty()) {
				continue;
			}
			String path = key.substring(key.indexOf(':') + 1);
			list.add(new PackContents.Item(key, category, PackContents.displayName(path), false, false, 0));
		}
		list.sort(java.util.Comparator.comparing(PackContents.Item::key));
	}

	@Override
	protected boolean contentReady() {
		return contents != null;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int x = contentX() + RAIL_W + 14;
		search = new TextFieldWidget(this.textRenderer, x + 22, contentY() + 6, Math.min(240, contentWidth() / 3) - 28, 12, Text.literal("Search"));
		search.setPlaceholder(VeloUi.placeholder(gameMode ? "Search the game's assets" : "Search this pack"));
		search.setDrawsBackground(false);
		search.setText(query);
		search.setChangedListener(v -> {
			query = v;
			scrollTarget = 0;
		});
		addDrawableChild(search);
		descriptionField = null;
		if (infoPage) {
			int fieldX = x + 96;
			descriptionField = new TextFieldWidget(this.textRenderer, fieldX + 6, contentY() + 92, contentWidth() - RAIL_W - 14 - 96 - 12, 12,
					Text.literal("Description"));
			descriptionField.setDrawsBackground(false);
			descriptionField.setMaxLength(200);
			descriptionField.setText(description == null ? "" : description);
			descriptionField.setChangedListener(v -> description = v);
			addDrawableChild(descriptionField);
		}
	}

	private void setStatus(String s) {
		status = s;
		statusAt = System.currentTimeMillis();
	}

	// ---- Drawing ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		PackUi.frame();
		thumbs.flush();
		hits.clear();
		tooltip = List.of();
		if (contents == null) {
			return;
		}
		int x = contentX();
		int y = contentY();
		int bottom = contentBottom();
		drawRail(context, x, y, RAIL_W, bottom - y, mouseX, mouseY);
		int mainX = x + RAIL_W + 14;
		int mainW = contentWidth() - RAIL_W - 14;

		// Top bar: search, mode switch, tile size.
		int searchW = Math.min(240, contentWidth() / 3);
		VeloStyle.drawInputField(context, mainX, y, searchW, 24, search != null && search.isFocused(), VeloUi.inside(mouseX, mouseY, mainX, y, searchW, 24));
		VeloDraw.searchGlyph(context, mainX + 11, y + 12, 10, MenuKit.TEXT_3);
		int modeX = mainX + searchW + 10;
		hits.add(PackUi.button(context, modeX, y + 3, 92, 18, "In this pack", gameMode ? 0 : 1, true, mouseX, mouseY, () -> setMode(false)));
		hits.add(PackUi.button(context, modeX + 96, y + 3, 108, 18, "From the game", gameMode ? 1 : 0, true, mouseX, mouseY, () -> setMode(true)));
		int sizeX = mainX + mainW - 3 * 22;
		for (int i = 0; i < TILE_SIZES.length; i++) {
			int index = i;
			String glyph = i == 0 ? "small" : i == 1 ? "medium" : "large";
			hits.add(PackUi.iconButton(context, sizeX + i * 22, y + 3, 18, glyph, tileSizeIndex == i, mouseX, mouseY, () -> tileSizeIndex = index));
		}

		int top = y + 34;
		int footer = bottom - 24;
		if (!infoPage && category == PackContents.Category.SOUNDS) {
			drawSounds(context, mainX, top + 2, mainW, footer - top - 10, mouseX, mouseY);
		} else if (infoPage && query.isBlank()) {
			drawInfo(context, mainX, top, mainW, footer - top - 8, mouseX, mouseY);
		} else {
			drawItems(context, mainX, top, mainW, footer - top - 8, mouseX, mouseY);
		}

		// Footer.
		String hint = !status.isEmpty() && System.currentTimeMillis() - statusAt < 6000 ? status
				: gameMode ? "Click anything to copy it into your pack and edit it." : "Click a texture to edit it. Other files open in your system's editor.";
		MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(hint, mainW - 300)), mainX, bottom - 13, MenuKit.TEXT_3);
		int bx = x + contentWidth();
		bx -= 70;
		hits.add(PackUi.button(context, bx, bottom - 19, 70, 19, "Done", 0, true, mouseX, mouseY, this::requestClose));
		bx -= 114;
		hits.add(PackUi.button(context, bx, bottom - 19, 110, 19, "Apply & reload", 1, true, mouseX, mouseY, this::applyAndReload));
		bx -= 96;
		hits.add(PackUi.button(context, bx, bottom - 19, 92, 19, "Open folder", 0, true, mouseX, mouseY, () -> FileManagerOpener.open(folderFor(category).toFile(),
				this::setStatus)));
	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (!tooltip.isEmpty()) {
			PackUi.tooltip(context, tooltip, mouseX, mouseY, this.width, this.height);
		}
	}

	private void drawRail(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		MenuKit.panel(context, x, y, w, h, 8);
		ThumbAtlas.Thumb icon = thumbs.get("root|pack.png", this::read);
		VeloDraw.fillRounded(context, x + 8, y + 8, 28, 28, 5, 0x14FFFFFF);
		thumbs.draw(context, icon, x + 8, y + 8, 28);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(packTitle), w - 52), x + 42, y + 11, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.small(contents.count() + " files"), x + 42, y + 23, MenuKit.TEXT_3);
		int ry = y + 44;
		ry = railItem(context, x, ry, w, "Pack info", -1, infoPage && query.isBlank(), mouseX, mouseY, () -> {
			infoPage = true;
			category = null;
			search.setText("");
			restartPageIn();
			layoutContent();
		});
		ry += 4;
		Map<PackContents.Category, List<PackContents.Item>> source = gameMode ? game : contents.items;
		if (source == null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Loading..."), x + 12, ry + 6, MenuKit.TEXT_3);
			return;
		}
		for (PackContents.Category c : PackContents.Category.values()) {
			int count = source.get(c).size();
			if (count == 0 && (gameMode || !c.textures) && c != PackContents.Category.SOUNDS) {
				continue;
			}
			if (ry + 18 > y + h - 4) {
				break;
			}
			ry = railItem(context, x, ry, w, c.label, count, !infoPage && c == category && query.isBlank(), mouseX, mouseY, () -> openCategory(c));
		}
	}

	private int railItem(DrawContext context, int x, int y, int w, String label, int count, boolean active, int mouseX, int mouseY, Runnable action) {
		boolean hovered = VeloUi.inside(mouseX, mouseY, x + 6, y, w - 12, 17);
		float h = PackUi.hover("rail:" + label, hovered || active);
		if (h > 0.01f) {
			VeloDraw.fillRounded(context, x + 6, y, w - 12, 17, 5, active ? VeloUi.withAlpha(VeloStyle.accent(), 0x40) : VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x14 * h)));
		}
		if (active) {
			VeloDraw.fillRounded(context, x + 6, y + 4, 2, 9, 1, VeloStyle.accent());
		}
		MenuKit.text(context, this.textRenderer, MenuKit.ui(label), x + 14 + Math.round(h * 2), y + 5, active ? MenuKit.TEXT : VeloAnim.lerpArgb(MenuKit.TEXT_2, MenuKit.TEXT, h));
		if (count >= 0) {
			Text n = MenuKit.small(String.valueOf(count));
			MenuKit.text(context, this.textRenderer, n, x + w - 14 - this.textRenderer.getWidth(n), y + 6, MenuKit.TEXT_3);
		}
		hits.add(new VeloUi.Hit(x + 6, y, w - 12, 17, action));
		return y + 19;
	}

	private void openCategory(PackContents.Category c) {
		boolean wasInfo = infoPage;
		infoPage = false;
		category = c;
		scroll = 0;
		scrollTarget = 0;
		if (!query.isBlank()) {
			search.setText("");
		}
		restartPageIn();
		if (wasInfo) {
			layoutContent();
		}
	}

	private void setMode(boolean toGame) {
		if (gameMode == toGame) {
			return;
		}
		gameMode = toGame;
		if (toGame) {
			loadGame();
		}
		scroll = 0;
		scrollTarget = 0;
		if (infoPage) {
			infoPage = false;
			category = PackContents.Category.BLOCKS;
		}
		restartPageIn();
		layoutContent();
	}

	private List<PackContents.Item> shownItems() {
		Map<PackContents.Category, List<PackContents.Item>> source = gameMode ? game : contents.items;
		if (source == null) {
			return List.of();
		}
		List<PackContents.Item> out = new ArrayList<>();
		if (!query.isBlank()) {
			String q = query.toLowerCase(Locale.ROOT);
			for (List<PackContents.Item> list : source.values()) {
				for (PackContents.Item item : list) {
					if (item.key().toLowerCase(Locale.ROOT).contains(q) || item.name().toLowerCase(Locale.ROOT).contains(q)) {
						out.add(item);
					}
				}
				if (out.size() > 2000) {
					break;
				}
			}
			out.sort(java.util.Comparator.comparing((PackContents.Item i) -> !i.texture()));
			return out;
		}
		if (category == null) {
			category = PackContents.Category.BLOCKS;
		}
		return source.get(category);
	}

	private void drawItems(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		gridArea = new int[] {x, y, w, h};
		List<PackContents.Item> items = shownItems();
		String heading = !query.isBlank() ? items.size() + " results" : (category != null ? category.label : "") + "  " + items.size();
		PackUi.heading(context, heading, x + 2, y);
		int top = y + 12;
		int height = h - 12;
		VeloDraw.fillRounded(context, x, top, w, height, 8, 0x0AFFFFFF);
		if (gameMode && game == null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Reading the game's assets..."), x + 12, top + 12, MenuKit.TEXT_3);
			return;
		}
		if (items.isEmpty()) {
			String empty = !query.isBlank() ? "Nothing matches." : gameMode ? "Nothing here." : "Nothing in this category yet - switch to \"From the game\" to add some.";
			MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(empty, w - 24)), x + 12, top + 12, MenuKit.TEXT_3);
			return;
		}
		scroll += (scrollTarget - scroll) * 0.35f;
		context.enableScissor(x + 1, top + 1, x + w - 1, top + height - 1);
		int tile = TILE_SIZES[tileSizeIndex];
		int cellW = tile + 16;
		int cellH = tile + 24;
		int columns = Math.max(1, (w - 12) / cellW);
		int pad = (w - columns * cellW) / 2;
		int textures = 0;
		for (PackContents.Item item : items) {
			if (item.texture()) {
				textures++;
			}
		}
		int rows = (textures + columns - 1) / columns;
		int fileTop = top + 8 + rows * cellH + (rows > 0 ? 8 : 0);
		int files = items.size() - textures;
		maxScroll = Math.max(0, fileTop - top + files * 24 + 8 - height);
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		int s = Math.round(scroll);
		int i = 0;
		int fileIndex = 0;
		for (PackContents.Item item : items) {
			if (item.texture()) {
				int cx = x + pad + (i % columns) * cellW;
				int cy = top + 8 + (i / columns) * cellH - s;
				i++;
				if (cy + cellH < top || cy > top + height) {
					continue;
				}
				drawTile(context, item, cx, cy, tile, mouseX, mouseY, VeloUi.inside(mouseX, mouseY, x, top, w, height));
			} else {
				int ry = fileTop + fileIndex * 24 - s;
				fileIndex++;
				if (ry + 24 < top || ry > top + height) {
					continue;
				}
				drawFileRow(context, item, x + 8, ry, w - 16, mouseX, mouseY, VeloUi.inside(mouseX, mouseY, x, top, w, height));
			}
		}
		context.disableScissor();
		if (maxScroll > 0) {
			int barH = Math.max(20, Math.round(height * height / (float) (height + maxScroll)));
			int barY = top + Math.round((height - barH) * (scroll / maxScroll));
			VeloDraw.fillRounded(context, x + w - 5, barY, 3, barH, 1, 0x40FFFFFF);
		}
	}

	private void drawTile(DrawContext context, PackContents.Item item, int x, int y, int tile, int mouseX, int mouseY, boolean inArea) {
		int w = tile + 12;
		boolean hovered = inArea && VeloUi.inside(mouseX, mouseY, x, y, w, tile + 20);
		float h = PackUi.hover("tile:" + item.key(), hovered);
		int lift = Math.round(h * 2);
		int ty = y - lift;
		if (h > 0.01f) {
			VeloDraw.shadow(context, x, ty, w, tile + 12, 7, 8, 3, VeloUi.withAlpha(0xFF000000, Math.round(0x60 * h)));
		}
		VeloDraw.fillRounded(context, x, ty, w, tile + 12, 7, VeloAnim.lerpArgb(0x10FFFFFF, 0x22FFFFFF, h));
		if (h > 0.01f) {
			VeloDraw.strokeRounded(context, x, ty, w, tile + 12, 7, VeloUi.withAlpha(VeloStyle.accent(), Math.round(0xB0 * h)));
		}
		String thumbKey = (gameMode ? "game|" : "pack|") + item.key();
		ThumbAtlas.Thumb thumb = thumbs.get(thumbKey, this::read);
		if (thumb == null) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 2.5e8);
			VeloDraw.fillRounded(context, x + 6 + tile / 4, ty + 6 + tile / 4, tile / 2, tile / 2, 4, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x08 + 0x10 * pulse)));
		} else {
			thumbs.draw(context, thumb, x + 6, ty + 6, tile);
		}
		if (item.animated()) {
			PackUi.badge(context, x + 2, ty + 2, "ANIM", MenuKit.GOLD);
		}
		if (gameMode && packKeys.contains(item.key())) {
			VeloDraw.fillCircle(context, x + w - 6f, ty + 6f, 4f, MenuKit.GREEN);
		}
		Text name = MenuKit.fit(this.textRenderer, MenuKit.small(item.name()), w);
		MenuKit.text(context, this.textRenderer, name, x + (w - this.textRenderer.getWidth(name)) / 2, y + tile + 15, hovered ? MenuKit.TEXT : MenuKit.TEXT_2);
		if (hovered) {
			List<String> lines = new ArrayList<>();
			lines.add(item.name());
			lines.add(item.key());
			if (thumb != null && thumb.sourceWidth() > 0) {
				lines.add(thumb.sourceWidth() + " x " + thumb.sourceHeight() + (item.animated() ? " - animated" : ""));
			}
			if (gameMode) {
				lines.add(packKeys.contains(item.key()) ? "Already in your pack - click to edit" : "Click to copy into your pack and edit");
			}
			tooltip = lines;
			hits.add(new VeloUi.Hit(x, y, w, tile + 20, () -> openTexture(item)));
		}
	}

	private void drawFileRow(DrawContext context, PackContents.Item item, int x, int y, int w, int mouseX, int mouseY, boolean inArea) {
		boolean hovered = inArea && VeloUi.inside(mouseX, mouseY, x, y, w, 22);
		float h = PackUi.hover("file:" + item.key(), hovered);
		VeloDraw.fillRounded(context, x, y, w, 22, 6, VeloAnim.lerpArgb(0x0AFFFFFF, 0x1CFFFFFF, h));
		String ext = item.path().contains(".") ? item.path().substring(item.path().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "FILE";
		int bw = PackUi.badge(context, x + 6, y + 6, ext, ext.equals("JSON") ? MenuKit.BLUE : ext.equals("OGG") ? MenuKit.GOLD : MenuKit.TEXT_2);
		MenuKit.text(context, this.textRenderer, MenuKit.ui(item.name()), x + 12 + bw, y + 7, MenuKit.TEXT);
		int nameW = this.textRenderer.getWidth(MenuKit.ui(item.name()));
		MenuKit.text(context, this.textRenderer, MenuKit.small(VeloUi.trim(item.key(), w - nameW - bw - 220)), x + 20 + bw + nameW, y + 8, MenuKit.TEXT_3);
		boolean editable = CodeEditorScreen.canEdit(item.path());
		boolean sound = item.path().endsWith(".ogg");
		if (gameMode) {
			boolean inPack = packKeys.contains(item.key());
			if (hovered || inPack) {
				String label = inPack ? (editable ? "Edit (in pack)" : "Open (in pack)") : editable ? "Copy to pack & edit" : "Copy to pack & open";
				hits.add(PackUi.button(context, x + w - 132, y + 2, 128, 18, label, inPack ? 0 : 1, true, mouseX, mouseY, () -> copyFromGame(item, true)));
			}
			if (hovered && sound) {
				hits.add(playButton(context, item.key(), x + w - 156, y + 2, mouseX, mouseY));
			}
		} else if (hovered) {
			int bx = x + w - 64;
			hits.add(PackUi.button(context, bx, y + 2, 60, 18, "Folder", 0, true, mouseX, mouseY,
					() -> FileManagerOpener.open(PackContents.file(root, item.key()).getParent().toFile(), this::setStatus)));
			bx -= 64;
			hits.add(PackUi.button(context, bx, y + 2, 60, 18, editable ? "System" : "Open", editable ? 0 : 1, true, mouseX, mouseY,
					() -> FileManagerOpener.open(PackContents.file(root, item.key()).toFile(), this::setStatus)));
			if (editable) {
				bx -= 64;
				hits.add(PackUi.button(context, bx, y + 2, 60, 18, "Edit", 1, true, mouseX, mouseY, () -> openFile(item)));
			}
			if (sound) {
				bx -= 24;
				hits.add(playButton(context, item.key(), bx, y + 2, mouseX, mouseY));
			}
		}
		if (hovered) {
			hits.add(new VeloUi.Hit(x, y, w, 22, () -> {
				if (gameMode) {
					copyFromGame(item, true);
				} else if (editable) {
					openFile(item);
				}
			}));
		}
		if (!gameMode && item.bytes() > 0 && !hovered) {
			Text size = MenuKit.small(item.bytes() < 10240 ? item.bytes() + " B" : (item.bytes() / 1024) + " KB");
			MenuKit.text(context, this.textRenderer, size, x + w - 8 - this.textRenderer.getWidth(size), y + 8, MenuKit.TEXT_3);
		}
	}

	private void drawInfo(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		PackUi.heading(context, "Pack info", x + 2, y);
		int top = y + 12;
		MenuKit.panel(context, x, top, w, h - 12, 8);
		ThumbAtlas.Thumb icon = thumbs.get("root|pack.png", this::read);
		VeloDraw.fillRounded(context, x + 12, top + 12, 72, 72, 8, 0x14FFFFFF);
		thumbs.draw(context, icon, x + 16, top + 16, 64);
		boolean iconHovered = VeloUi.inside(mouseX, mouseY, x + 12, top + 12, 72, 72);
		if (iconHovered) {
			VeloDraw.fillRounded(context, x + 12, top + 12, 72, 72, 8, 0x66000000);
			PackUi.glyph(context, "edit", x + 48, top + 48, 18, 0xFFFFFFFF);
			tooltip = List.of("Edit the pack icon");
		}
		hits.add(new VeloUi.Hit(x + 12, top + 12, 72, 72, this::editIcon));
		int fx = x + 96;
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(packTitle), fx, top + 14, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.small("Description"), fx, top + 30, MenuKit.TEXT_3);
		VeloStyle.drawInputField(context, fx, top + 40, w - 96 - 12, 22, descriptionField != null && descriptionField.isFocused(), false);
		int sy = top + 72;
		hits.add(PackUi.button(context, fx, sy, 120, 18, "Save description", 1, true, mouseX, mouseY, this::saveDescription));
		hits.add(PackUi.button(context, fx + 126, sy, 120, 18, "Choose cover...", 0, true, mouseX, mouseY, this::chooseCover));
		hits.add(PackUi.button(context, fx + 252, sy, 100, 18, "Edit cover", 0, true, mouseX, mouseY, this::editIcon));
		hits.add(PackUi.button(context, fx + 358, sy, 120, 18, "Edit pack.mcmeta", 0, true, mouseX, mouseY,
				() -> this.client.setScreen(new CodeEditorScreen(this, root.resolve("pack.mcmeta"), null))));
		if (VeloUi.inside(mouseX, mouseY, fx + 126, sy, 120, 18)) {
			tooltip = List.of("Set the pack's cover", "Any PNG or JPG - cropped to a square", "and saved as pack.png");
		}
		int line = top + 104;
		MenuKit.text(context, this.textRenderer, MenuKit.small("Pack format " + readFormat() + "  -  this game uses " + PackBridge.packFormat()), x + 12, line, MenuKit.TEXT_3);
		MenuKit.text(context, this.textRenderer, MenuKit.small(VeloUi.trim(root.toString(), w - 24)), x + 12, line + 12, MenuKit.TEXT_3);
		int cy = line + 34;
		PackUi.heading(context, "In this pack", x + 12, cy);
		cy += 14;
		int col = 0;
		int colW = Math.max(120, (w - 24) / 4);
		for (PackContents.Category c : PackContents.Category.values()) {
			int count = contents.items.get(c).size();
			if (count == 0) {
				continue;
			}
			int cx = x + 12 + (col % 4) * colW;
			int ry = cy + (col / 4) * 30;
			if (ry + 26 > top + h - 12) {
				break;
			}
			boolean hovered = VeloUi.inside(mouseX, mouseY, cx, ry, colW - 8, 26);
			float hv = PackUi.hover("info:" + c, hovered);
			VeloDraw.fillRounded(context, cx, ry, colW - 8, 26, 6, VeloAnim.lerpArgb(0x10FFFFFF, 0x24FFFFFF, hv));
			MenuKit.text(context, this.textRenderer, MenuKit.ui(c.label), cx + 8, ry + 5, MenuKit.TEXT);
			MenuKit.text(context, this.textRenderer, MenuKit.small(count + (count == 1 ? " file" : " files")), cx + 8, ry + 16, MenuKit.TEXT_3);
			hits.add(new VeloUi.Hit(cx, ry, colW - 8, 26, () -> openCategory(c)));
			col++;
		}
		if (col == 0) {
			List<String> lines = VeloUi.wrap("This pack is empty so far. Switch to \"From the game\" at the top, pick any block, item, entity or "
					+ "interface texture and it's copied into this pack, ready to paint.", w - 24);
			for (String l : lines) {
				MenuKit.text(context, this.textRenderer, MenuKit.ui(l), x + 12, cy, MenuKit.TEXT_2);
				cy += 11;
			}
			hits.add(PackUi.button(context, x + 12, cy + 6, 140, 18, "Browse the game", 1, true, mouseX, mouseY, () -> setMode(true)));
		}
	}

	// ---- Files ----

	/** Thumbnail reader: "pack|ns:path", "game|ns:path" or "root|file". */
	private byte[] read(String key) {
		try {
			if (key.startsWith("root|")) {
				Path file = root.resolve(key.substring(5));
				return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
			}
			if (key.startsWith("pack|")) {
				Path file = PackContents.file(root, key.substring(5));
				return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
			}
			if (key.startsWith("game|")) {
				return PackBridge.readGame(key.substring(5));
			}
		} catch (IOException ignored) {
			// No thumbnail.
		}
		return null;
	}

	private Path folderFor(PackContents.Category c) {
		if (c == null || infoPage) {
			return root;
		}
		Path assets = root.resolve("assets/minecraft");
		String sub = switch (c) {
			case BLOCKS -> "textures/block";
			case ITEMS -> "textures/item";
			case ENTITIES -> "textures/entity";
			case INTERFACE -> "textures/gui";
			case PARTICLES -> "textures/particle";
			case ENVIRONMENT -> "textures/environment";
			case PAINTINGS -> "textures/painting";
			case EFFECTS -> "textures/mob_effect";
			case ARMOR -> "textures/trims";
			case FONTS -> "font";
			case MODELS -> "models";
			case BLOCKSTATES -> "blockstates";
			case ITEM_DEFINITIONS -> "items";
			case SOUNDS -> "sounds";
			case LANGUAGES -> "lang";
			case SHADERS -> "shaders";
			case SPLASHES -> "texts";
			default -> "";
		};
		Path dir = assets.resolve(sub);
		while (!Files.isDirectory(dir) && !dir.equals(root)) {
			dir = dir.getParent();
		}
		return dir;
	}

	/** Text files open in the in-game editor, everything else in the system's program. */
	private void openFile(PackContents.Item item) {
		Path file = PackContents.file(root, item.key());
		if (CodeEditorScreen.canEdit(item.path())) {
			this.client.setScreen(new CodeEditorScreen(this, file, item.key()));
		} else {
			FileManagerOpener.open(file.toFile(), this::setStatus);
		}
	}

	// ---- Sounds & music ----

	private volatile Map<String, SoundIndex.Event> packSounds;
	private boolean soundsLoading;
	private SoundIndex.Group soundGroup;
	private AudioData quickAudio;
	private String quickKey;
	private String quickLoading;

	private void loadSounds() {
		if (soundsLoading) {
			return;
		}
		soundsLoading = true;
		CompletableFuture.runAsync(() -> {
			SoundIndex.game();
			Map<String, SoundIndex.Event> changed = SoundIndex.changedBy(root);
			MinecraftClient.getInstance().execute(() -> {
				packSounds = changed;
				soundsLoading = false;
			});
		});
	}

	/** A play / stop button for one .ogg (pack first, then the game). */
	private VeloUi.Hit playButton(DrawContext context, String key, int x, int y, int mouseX, int mouseY) {
		boolean playing = key.equals(quickKey) && quickAudio != null && AudioPreview.playing(quickAudio);
		boolean loading = key.equals(quickLoading);
		return PackUi.iconButton(context, x, y, 18, playing ? "stop" : loading ? "waves" : "play", playing, mouseX, mouseY, () -> quickPlay(key));
	}

	private void quickPlay(String key) {
		if (key.equals(quickKey) && quickAudio != null && AudioPreview.playing(quickAudio)) {
			AudioPreview.stop();
			return;
		}
		quickLoading = key;
		CompletableFuture.runAsync(() -> {
			try {
				byte[] ogg = PackContents.read(root, key);
				AudioData audio = ogg == null ? null : AudioData.decodeOgg(ogg);
				MinecraftClient.getInstance().execute(() -> {
					if (!key.equals(quickLoading)) {
						return;
					}
					quickLoading = null;
					if (audio == null) {
						setStatus("Couldn't find " + key);
						return;
					}
					quickAudio = audio;
					quickKey = key;
					AudioPreview.play(audio);
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> {
					quickLoading = null;
					setStatus("Couldn't play it: " + e.getMessage());
				});
			}
		});
	}

	private void drawSounds(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		gridArea = new int[] {x, y, w, h};
		if (!SoundIndex.gameReady() || packSounds == null) {
			loadSounds();
		}
		Map<String, SoundIndex.Event> all = gameMode ? (SoundIndex.gameReady() ? SoundIndex.game() : null) : packSounds;
		// Group filter pills.
		int px = x;
		hits.add(PackUi.button(context, px, y - 2, 40, 16, "All", soundGroup == null ? 1 : 0, true, mouseX, mouseY, () -> {
			soundGroup = null;
			scrollTarget = 0;
		}));
		px += 44;
		for (SoundIndex.Group g : SoundIndex.Group.values()) {
			int gw = this.textRenderer.getWidth(MenuKit.ui(g.label)) + 14;
			if (px + gw > x + w - 130) {
				break;
			}
			hits.add(PackUi.button(context, px, y - 2, gw, 16, g.label, soundGroup == g ? 1 : 0, true, mouseX, mouseY, () -> {
				soundGroup = g;
				scrollTarget = 0;
			}));
			px += gw + 4;
		}
		Path soundsJson = root.resolve("assets/minecraft/sounds.json");
		if (!gameMode && Files.exists(soundsJson)) {
			hits.add(PackUi.button(context, x + w - 124, y - 2, 124, 16, "Edit sounds.json", 0, true, mouseX, mouseY,
					() -> this.client.setScreen(new CodeEditorScreen(this, soundsJson, "minecraft:sounds.json"))));
		}
		int top = y + 20;
		int height = h - 20;
		VeloDraw.fillRounded(context, x, top, w, height, 8, 0x0AFFFFFF);
		if (all == null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Reading the game's sounds..."), x + 12, top + 12, MenuKit.TEXT_3);
			return;
		}
		String q = query.toLowerCase(Locale.ROOT);
		List<SoundIndex.Event> events = new ArrayList<>();
		for (SoundIndex.Event e : all.values()) {
			if ((soundGroup == null || e.group() == soundGroup) && (q.isBlank() || e.id().contains(q) || SoundIndex.label(e.id()).toLowerCase(Locale.ROOT)
					.contains(q))) {
				events.add(e);
			}
		}
		if (events.isEmpty()) {
			String empty = gameMode ? "No sounds match." : "This pack doesn't change any sounds yet - switch to \"From the game\" and pick one.";
			MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(empty, w - 24)), x + 12, top + 12, MenuKit.TEXT_3);
			return;
		}
		scroll += (scrollTarget - scroll) * 0.35f;
		maxScroll = Math.max(0, events.size() * 26 + 8 - height);
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		int first = Math.max(0, (int) (scroll / 26) - 1);
		context.enableScissor(x + 1, top + 1, x + w - 1, top + height - 1);
		for (int i = first; i < events.size(); i++) {
			int ry = top + 6 + i * 26 - Math.round(scroll);
			if (ry > top + height) {
				break;
			}
			SoundIndex.Event e = events.get(i);
			boolean inArea = VeloUi.inside(mouseX, mouseY, x, top, w, height);
			boolean hovered = inArea && VeloUi.inside(mouseX, mouseY, x + 6, ry, w - 12, 24);
			float hv = PackUi.hover("sound:" + e.id(), hovered);
			VeloDraw.fillRounded(context, x + 6, ry, w - 12, 24, 6, VeloAnim.lerpArgb(0x0AFFFFFF, 0x1CFFFFFF, hv));
			if (!e.variants().isEmpty() && inArea) {
				hits.add(playButton(context, e.variants().get(0).assetKey(), x + 10, ry + 3, mouseX, mouseY));
			} else {
				PackUi.glyph(context, e.group() == SoundIndex.Group.MUSIC ? "music" : "sound", x + 19, ry + 12, 11, MenuKit.TEXT_3);
			}
			PackUi.glyph(context, e.group() == SoundIndex.Group.MUSIC ? "music" : "sound", x + 40, ry + 12, 10,
					e.group() == SoundIndex.Group.MUSIC ? MenuKit.GOLD : MenuKit.TEXT_3);
			Text label = MenuKit.ui(SoundIndex.label(e.id()));
			MenuKit.text(context, this.textRenderer, label, x + 52, ry + 8, MenuKit.TEXT);
			int lw = this.textRenderer.getWidth(label);
			MenuKit.text(context, this.textRenderer, MenuKit.small(VeloUi.trim(e.id(), w - lw - 260)), x + 60 + lw, ry + 9, MenuKit.TEXT_3);
			int bx = x + w - 18;
			Text count = MenuKit.small(e.variants().size() + (e.variants().size() == 1 ? " file" : " files"));
			bx -= this.textRenderer.getWidth(count);
			if (!hovered) {
				MenuKit.text(context, this.textRenderer, count, bx, ry + 9, MenuKit.TEXT_3);
			}
			if (gameMode && packSounds != null && packSounds.containsKey(e.id())) {
				PackUi.badge(context, bx - 44, ry + 7, "PACK", MenuKit.GREEN);
			}
			if (hovered) {
				hits.add(PackUi.button(context, x + w - 86, ry + 3, 74, 18, "Edit", 1, true, mouseX, mouseY, () -> openSound(e.id())));
				hits.add(new VeloUi.Hit(x + 34, ry, w - 130, 24, () -> openSound(e.id())));
			}
		}
		context.disableScissor();
		if (maxScroll > 0) {
			int barH = Math.max(20, Math.round(height * height / (float) (height + maxScroll)));
			int barY = top + Math.round((height - barH) * (scroll / maxScroll));
			VeloDraw.fillRounded(context, x + w - 5, barY, 3, barH, 1, 0x40FFFFFF);
		}
	}

	private void openSound(String eventId) {
		AudioPreview.stop();
		this.client.setScreen(new SoundEditorScreen(this, root, eventId));
	}

	private void openTexture(PackContents.Item item) {
		if (gameMode && !packKeys.contains(item.key())) {
			if (!copyFromGame(item, false)) {
				return;
			}
		}
		this.client.setScreen(new TextureEditorScreen(this, root, item.key(), PackContents.file(root, item.key())));
	}

	/** Copies a game asset (and its .mcmeta) into the pack; opens non-textures in the system's editor. */
	private boolean copyFromGame(PackContents.Item item, boolean open) {
		Path target = PackContents.file(root, item.key());
		try {
			if (!Files.exists(target)) {
				byte[] data = PackBridge.readGame(item.key());
				if (data == null) {
					setStatus("Couldn't read " + item.key());
					return false;
				}
				Files.createDirectories(target.getParent());
				Files.write(target, data);
				byte[] meta = PackBridge.readGame(item.key() + ".mcmeta");
				if (meta != null) {
					Files.write(target.resolveSibling(target.getFileName() + ".mcmeta"), meta);
				}
				packKeys.add(item.key());
				rescan();
				setStatus("Copied " + item.name() + " into the pack.");
			}
			if (open) {
				openFile(item);
			}
			return true;
		} catch (IOException e) {
			setStatus("Couldn't copy: " + e.getMessage());
			return false;
		}
	}

	private void editIcon() {
		Path icon = root.resolve("pack.png");
		if (!Files.exists(icon)) {
			try {
				TexDoc.blank(64, 64).save(icon);
			} catch (IOException e) {
				setStatus("Couldn't create pack.png: " + e.getMessage());
				return;
			}
		}
		this.client.setScreen(new TextureEditorScreen(this, root, null, icon));
	}

	/** Picks any image as the pack cover: centre-cropped to a square, scaled smoothly, saved as pack.png. */
	private void chooseCover() {
		setStatus("Opening file picker...");
		java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path picked = net.veloclient.velo.client.gui.NativeFileDialog.pickFile("Choose a cover for " + packTitle, "Images", "png", "jpg", "jpeg");
				if (picked == null) {
					MinecraftClient.getInstance().execute(() -> setStatus(""));
					return;
				}
				java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(picked.toFile());
				if (source == null) {
					throw new IOException("not an image");
				}
				int side = Math.min(source.getWidth(), source.getHeight());
				int out = Math.min(256, side);
				java.awt.image.BufferedImage cover = new java.awt.image.BufferedImage(out, out, java.awt.image.BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics2D g = cover.createGraphics();
				// Pixel art stays crisp, photos get smooth scaling.
				boolean pixelArt = side <= 128;
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, pixelArt ? java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
						: java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
				int sx = (source.getWidth() - side) / 2;
				int sy = (source.getHeight() - side) / 2;
				g.drawImage(source, 0, 0, out, out, sx, sy, sx + side, sy + side, null);
				g.dispose();
				Path target = root.resolve("pack.png");
				javax.imageio.ImageIO.write(cover, "png", target.toFile());
				MinecraftClient.getInstance().execute(() -> {
					onSaved(null);
					setStatus("Cover set from " + picked.getFileName());
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> setStatus("Couldn't set the cover: " + e.getMessage()));
			}
		});
	}

	private JsonObject readMeta() {
		try {
			return JsonParser.parseString(Files.readString(root.resolve("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			return new JsonObject();
		}
	}

	private String readDescription() {
		JsonObject meta = readMeta();
		if (meta.has("pack") && meta.getAsJsonObject("pack").has("description")) {
			var d = meta.getAsJsonObject("pack").get("description");
			return d.isJsonPrimitive() ? d.getAsString() : d.toString();
		}
		return "";
	}

	private String readFormat() {
		JsonObject meta = readMeta();
		if (meta.has("pack")) {
			JsonObject pack = meta.getAsJsonObject("pack");
			if (pack.has("min_format") || pack.has("max_format")) {
				return (pack.has("min_format") ? pack.get("min_format").toString() : "?") + "-" + (pack.has("max_format") ? pack.get("max_format").toString() : "?");
			}
			if (pack.has("pack_format")) {
				return pack.get("pack_format").toString();
			}
		}
		return "?";
	}

	private void saveDescription() {
		JsonObject meta = readMeta();
		JsonObject pack = meta.has("pack") ? meta.getAsJsonObject("pack") : new JsonObject();
		pack.addProperty("description", description == null ? "" : description);
		if (!pack.has("pack_format") && !pack.has("min_format")) {
			int format = PackBridge.packFormat();
			pack.addProperty("pack_format", format);
			pack.addProperty("min_format", format);
			pack.addProperty("max_format", format);
		}
		meta.add("pack", pack);
		try {
			Files.writeString(root.resolve("pack.mcmeta"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(meta), StandardCharsets.UTF_8);
			setStatus("Description saved.");
		} catch (IOException e) {
			setStatus("Couldn't save: " + e.getMessage());
		}
	}

	/** Called by the editor after it saved a file. */
	void onSaved(String key) {
		packSounds = null;
		if (key != null) {
			thumbs.invalidate("pack|" + key);
			packKeys.add(key);
		} else {
			thumbs.invalidate("root|pack.png");
			packs.refreshIcons();
		}
		rescan();
	}

	/** Selects this pack (if needed) and reloads the game's resources so edits show in the world. */
	void applyAndReload() {
		packs.selectAndReload(packId);
		setStatus("Reloading - your changes are applied in a moment.");
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
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (VeloUi.inside(mouseX, mouseY, gridArea[0], gridArea[1], gridArea[2], gridArea[3])) {
			scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (float) verticalAmount * 40));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	protected void requestClose() {
		finished = true;
		AudioPreview.stop();
		super.requestClose();
	}

	@Override
	public void removed() {
		if (finished) {
			thumbs.release();
		}
		super.removed();
	}

	/** Categories with counts, for the editor's breadcrumbs. */
	Map<PackContents.Category, Integer> counts() {
		Map<PackContents.Category, Integer> out = new EnumMap<>(PackContents.Category.class);
		if (contents != null) {
			contents.items.forEach((c, l) -> out.put(c, l.size()));
		}
		return out;
	}

	/** Dev-only (screenshot tour). */
	public void showForTour(boolean fromGame, PackContents.Category c) {
		setMode(fromGame);
		openCategory(c);
	}

	VeloPacksScreen packs() {
		return packs;
	}

	String packId() {
		return packId;
	}
}
