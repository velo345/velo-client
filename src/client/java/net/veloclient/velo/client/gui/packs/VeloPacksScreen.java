package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.FileManagerOpener;
import net.veloclient.velo.client.gui.VeloConfirmScreen;
import net.veloclient.velo.client.gui.VeloScreens;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Velo's resource pack screen, replacing vanilla's (Settings > Velo screens can switch back): available and
 * selected packs side by side with icons, descriptions and badges; select / remove / reorder with the row
 * buttons, a double click or by dragging; search; incompatible packs ask first; drop files onto the window to
 * add packs; the folder is watched like vanilla does. On top: a details panel, new packs, editable copies of
 * zips, a Reload button - and Edit, which opens the in-game pack and texture editor.
 */
public final class VeloPacksScreen extends VeloWindow {

	private static final int ROW_H = 40;
	private static final int ROW_GAP = 4;
	private static Screen lastOther;
	private static boolean openVanillaOnce;

	private final PackBridge.Model model;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final List<VeloUi.Hit> rowHits = new ArrayList<>();
	private List<Object> available = List.of();
	private List<Object> selected = List.of();
	private boolean rebuild = true;
	private TextFieldWidget search;
	private String query = "";
	private String focusedId;
	private final float[] scroll = new float[2];
	private final float[] scrollTarget = new float[2];
	private final Map<String, Float> rowY = new HashMap<>();
	private final int[][] listArea = new int[2][4];
	private String status = "";
	private long statusAt;
	private boolean changed;
	private boolean finished;
	private WatchService watcher;
	private int rescanIn;
	private int watchPoll;
	private String openEditorFor;
	private List<String> tooltip = List.of();

	// Dragging a row.
	private Object pressEntry;
	private int pressList;
	private double pressX;
	private double pressY;
	private boolean dragging;
	private double dragX;
	private double dragY;

	public VeloPacksScreen(Screen parent) {
		super(Text.literal("Resource Packs"), 860, 500);
		returnTo(parent);
		this.model = PackBridge.newModel(() -> rebuild = true);
		// Like vanilla's screen: look for packs added since the game started.
		PackBridge.rescan(model);
		try {
			watcher = FileSystems.getDefault().newWatchService();
			PackBridge.packDir().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
					StandardWatchEventKinds.ENTRY_MODIFY);
		} catch (Exception e) {
			watcher = null;
		}
		PreviewModels.warmUp();
	}

	/** Swaps vanilla's resource pack screen for this one (Settings > Velo screens can turn that off). */
	public static void maybeReplace(MinecraftClient client, Screen screen) {
		if (!PackBridge.isResourcePackScreen(screen)) {
			if (!(screen instanceof VeloWindow) && screen != null) {
				lastOther = screen;
			}
			return;
		}
		if (openVanillaOnce) {
			openVanillaOnce = false;
			return;
		}
		if (!VeloScreens.resourcePacks()) {
			return;
		}
		Screen parent = lastOther;
		client.execute(() -> {
			// Vanilla's screen closes properly first (its folder watcher), then ours takes over.
			PackBridge.closeVanillaScreen(screen);
			client.setScreen(new VeloPacksScreen(parent));
		});
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		search = new TextFieldWidget(this.textRenderer, contentX() + 22, contentY() + 6, searchWidth() - 28, 12, Text.literal("Search"));
		search.setPlaceholder(VeloUi.placeholder("Search packs"));
		search.setDrawsBackground(false);
		search.setText(query);
		search.setChangedListener(v -> {
			query = v;
			scrollTarget[0] = 0;
			scrollTarget[1] = 0;
		});
		addDrawableChild(search);
	}

	private int searchWidth() {
		return Math.max(120, Math.min(260, contentWidth() / 3));
	}

	// ---- Model ----

	private void refreshLists() {
		if (!rebuild) {
			return;
		}
		rebuild = false;
		available = PackBridge.available(model);
		selected = PackBridge.selected(model);
	}

	private boolean matches(Object entry) {
		if (query.isBlank()) {
			return true;
		}
		String q = query.toLowerCase(Locale.ROOT);
		return PackBridge.id(entry).toLowerCase(Locale.ROOT).contains(q)
				|| PackBridge.title(entry).getString().toLowerCase(Locale.ROOT).contains(q)
				|| PackBridge.description(entry).getString().toLowerCase(Locale.ROOT).contains(q);
	}

	private Object find(String id) {
		refreshLists();
		for (Object e : selected) {
			if (PackBridge.id(e).equals(id)) {
				return e;
			}
		}
		for (Object e : available) {
			if (PackBridge.id(e).equals(id)) {
				return e;
			}
		}
		return null;
	}

	/** The folder or zip behind a pack from the packs folder, or null for built-in packs. */
	static Path fileOf(String id) {
		if (!id.startsWith("file/")) {
			return null;
		}
		Path path = PackBridge.packDir().resolve(id.substring("file/".length()));
		return Files.exists(path) ? path : null;
	}

	private void select(Object entry) {
		if (!PackBridge.canSelect(entry)) {
			return;
		}
		if (!PackBridge.compatible(entry)) {
			String id = PackBridge.id(entry);
			this.client.setScreen(new VeloConfirmScreen(this, "Incompatible pack", PackBridge.compatibilityQuestion(entry).getString(), "Use anyway",
					() -> {
						Object fresh = find(id);
						if (fresh != null) {
							PackBridge.select(fresh);
							changed = true;
						}
					}));
			return;
		}
		PackBridge.select(entry);
		changed = true;
	}

	private void unselect(Object entry) {
		if (PackBridge.canUnselect(entry)) {
			PackBridge.unselect(entry);
			changed = true;
		}
	}

	private void move(Object entry, boolean up) {
		if (up ? PackBridge.canMoveUp(entry) : PackBridge.canMoveDown(entry)) {
			if (up) {
				PackBridge.moveUp(entry);
			} else {
				PackBridge.moveDown(entry);
			}
			changed = true;
		}
	}

	private void toggle(Object entry) {
		if (PackBridge.isSelected(entry)) {
			unselect(entry);
		} else {
			select(entry);
		}
	}

	/** Applies the selection now; reloads resources either way (picks up edited files). */
	public void applyAndReload() {
		if (changed) {
			changed = false;
			PackBridge.commit(model);
		} else {
			PackBridge.reloadResources();
		}
		setStatus("Reloading resources...");
	}

	/** Selects the pack (if it isn't) and reloads - the editor's "Save & reload". */
	public void selectAndReload(String id) {
		Object entry = find(id);
		if (entry != null && !PackBridge.isSelected(entry)) {
			PackBridge.select(entry);
			changed = true;
		}
		applyAndReload();
	}

	/** Reloads pack icons (after a cover changed). */
	void refreshIcons() {
		PackBridge.releaseIcons(model);
		rebuild = true;
	}

	private void setStatus(String s) {
		status = s;
		statusAt = System.currentTimeMillis();
	}

	// ---- Drawing ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		PackUi.frame();
		refreshLists();
		hits.clear();
		rowHits.clear();
		tooltip = List.of();
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		int bottom = contentBottom();

		// Search + actions.
		VeloStyle.drawInputField(context, x, y, searchWidth(), 24, search != null && search.isFocused(),
				VeloUi.inside(mouseX, mouseY, x, y, searchWidth(), 24));
		VeloDraw.searchGlyph(context, x + 11, y + 12, 10, MenuKit.TEXT_3);
		int bx = x + w;
		bx -= 86;
		hits.add(PackUi.button(context, bx, y + 3, 86, 18, "Reload", 0, true, mouseX, mouseY, this::applyAndReload));
		bx -= 92;
		hits.add(PackUi.button(context, bx, y + 3, 88, 18, "New pack", 0, true, mouseX, mouseY, this::promptNewPack));
		bx -= 92;
		hits.add(PackUi.button(context, bx, y + 3, 88, 18, "Open folder", 0, true, mouseX, mouseY,
				() -> FileManagerOpener.open(PackBridge.packDir().toFile(), this::setStatus)));

		// Two lists + details.
		int listTop = y + 34;
		int listBottom = bottom - 28;
		int detailsW = w >= 640 ? Math.min(230, w / 4) : 0;
		int gap = 12;
		int listW = (w - detailsW - (detailsW > 0 ? gap : 0) - gap) / 2;
		List<Object> shownAvailable = available.stream().filter(this::matches).toList();
		List<Object> shownSelected = selected.stream().filter(this::matches).toList();
		drawList(context, 0, "Available", shownAvailable, x, listTop, listW, listBottom - listTop, mouseX, mouseY);
		drawList(context, 1, "Selected", shownSelected, x + listW + gap, listTop, listW, listBottom - listTop, mouseX, mouseY);
		if (detailsW > 0) {
			drawDetails(context, x + w - detailsW, listTop, detailsW, listBottom - listTop, mouseX, mouseY);
		}

		// Footer.
		String hint = !status.isEmpty() && System.currentTimeMillis() - statusAt < 6000 ? status
				: "Drag packs between the lists, or drop .zip files and folders onto this window to add them.";
		MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(hint, w - 200)), x, bottom - 13, MenuKit.TEXT_3);
		hits.add(PackUi.button(context, x + w - 86, bottom - 19, 86, 19, "Done", 1, true, mouseX, mouseY, this::requestClose));
		hits.add(PackUi.button(context, x + w - 178, bottom - 19, 86, 19, "Classic screen", 0, true, mouseX, mouseY, this::openVanilla));
	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (dragging && pressEntry != null) {
			int w = listArea[0][2] - 12;
			int x = (int) dragX - 20;
			int y = (int) dragY - ROW_H / 2;
			VeloDraw.shadow(context, x, y, w, ROW_H, 8, 12, 4, 0x80000000);
			drawRow(context, pressEntry, x, y, w, 1f, true, false, -1, -1);
		}
		if (!tooltip.isEmpty() && !dragging) {
			PackUi.tooltip(context, tooltip, mouseX, mouseY, this.width, this.height);
		}
	}

	private void drawList(DrawContext context, int list, String title, List<Object> entries, int x, int y, int w, int h, int mouseX, int mouseY) {
		PackUi.heading(context, title + "  " + entries.size(), x + 2, y);
		int top = y + 12;
		int height = h - 12;
		listArea[list] = new int[] {x, top, w, height};
		boolean dropHere = dragging && VeloUi.inside(dragX, dragY, x, top, w, height) && (list == 1 || pressList == 1);
		VeloDraw.fillRounded(context, x, top, w, height, 8, dropHere ? VeloUi.withAlpha(VeloStyle.accent(), 0x20) : 0x0CFFFFFF);
		VeloDraw.strokeRounded(context, x, top, w, height, 8, dropHere ? VeloUi.withAlpha(VeloStyle.accent(), 0x90) : 0x14FFFFFF);
		float maxScroll = Math.max(0, entries.size() * (ROW_H + ROW_GAP) + 8 - height);
		scrollTarget[list] = Math.max(0, Math.min(maxScroll, scrollTarget[list]));
		scroll[list] += (scrollTarget[list] - scroll[list]) * 0.35f;
		if (entries.isEmpty()) {
			String empty = query.isBlank() ? (list == 1 ? "No packs selected" : "Every pack is selected") : "No match";
			int tw = this.textRenderer.getWidth(empty);
			MenuKit.text(context, this.textRenderer, MenuKit.ui(empty), x + (w - tw) / 2, top + height / 2 - 4, MenuKit.TEXT_3);
			return;
		}
		int dropIndex = dragging && dropHere && list == 1 ? dropIndex(entries, top) : -1;
		context.enableScissor(x + 1, top + 1, x + w - 1, top + height - 1);
		int index = 0;
		for (Object entry : entries) {
			String id = PackBridge.id(entry);
			float target = top + 6 + index * (ROW_H + ROW_GAP) - scroll[list];
			String key = list + ":" + id;
			float current = rowY.getOrDefault(key, target + 8);
			current += (target - current) * 0.3f;
			if (Math.abs(target - current) < 0.3f) {
				current = target;
			}
			rowY.put(key, current);
			int ry = Math.round(current);
			if (dropIndex == index) {
				VeloDraw.fillRounded(context, x + 8, ry - 3, w - 16, 2, 1, VeloStyle.accent());
			}
			if (ry + ROW_H >= top && ry <= top + height) {
				boolean hovered = !dragging && VeloUi.inside(mouseX, mouseY, x + 6, ry, w - 12, ROW_H)
						&& VeloUi.inside(mouseX, mouseY, x, top, w, height);
				boolean beingDragged = dragging && pressEntry != null && PackBridge.id(pressEntry).equals(id);
				drawRow(context, entry, x + 6, ry, w - 12, beingDragged ? 0.3f : 1f, hovered, id.equals(focusedId), mouseX, mouseY);
				if (hovered) {
					rowActions(context, entry, list, x + 6, ry, w - 12, mouseX, mouseY);
					rowHits.add(new VeloUi.Hit(x + 6, ry, w - 12, ROW_H, () -> {
						pressEntry = entry;
						pressList = list;
					}));
				}
			}
			index++;
		}
		if (dropIndex == entries.size()) {
			int ry = Math.round(top + 6 + entries.size() * (ROW_H + ROW_GAP) - scroll[list]);
			VeloDraw.fillRounded(context, x + 8, ry - 3, w - 16, 2, 1, VeloStyle.accent());
		}
		context.disableScissor();
	}

	private int dropIndex(List<Object> entries, int top) {
		int i = (int) Math.floor((dragY - top - 6 + scroll[1] + (ROW_H + ROW_GAP) / 2f) / (ROW_H + ROW_GAP));
		return Math.max(0, Math.min(entries.size(), i));
	}

	private void drawRow(DrawContext context, Object entry, int x, int y, int w, float alpha, boolean hovered, boolean focused, int mouseX,
			int mouseY) {
		String id = PackBridge.id(entry);
		float h = PackUi.hover("row:" + id, hovered);
		int bg = VeloAnim.lerpArgb(0x0AFFFFFF, 0x1CFFFFFF, h);
		VeloDraw.fillRounded(context, x, y, w, ROW_H, 7, VeloUi.withAlpha(bg, Math.round((bg >>> 24) * alpha)));
		if (focused) {
			VeloDraw.strokeRounded(context, x, y, w, ROW_H, 7, VeloUi.withAlpha(VeloStyle.accent(), Math.round(0xC0 * alpha)));
		}
		Identifier icon = PackBridge.icon(entry);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + 4, y + 4, 0f, 0f, 32, 32, 32, 32, 32, 32);
		int textX = x + 42;
		int textW = w - 48 - Math.round(h * 74);
		List<String> badges = badges(entry);
		int badgeW = 0;
		for (String b : badges) {
			badgeW += this.textRenderer.getWidth(MenuKit.small(b)) + 12;
		}
		Text title = MenuKit.fit(this.textRenderer, MenuKit.uiBold(PackBridge.title(entry).getString()), Math.max(30, textW - badgeW));
		int textAlpha = Math.round(255 * alpha);
		MenuKit.text(context, this.textRenderer, title, textX, y + 6, VeloUi.withAlpha(MenuKit.TEXT, textAlpha));
		int bx = textX + this.textRenderer.getWidth(title) + 6;
		for (String b : badges) {
			bx += PackUi.badge(context, bx, y + 5, b, b.equals("Incompatible") ? 0xFFFF7A7E : b.equals("Folder") ? MenuKit.GREEN : MenuKit.TEXT_2) + 4;
		}
		List<String> lines = VeloUi.wrap(PackBridge.description(entry).getString(), Math.max(30, textW));
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			String line = i == 1 && lines.size() > 2 ? VeloUi.trim(lines.get(i) + "...", textW) : lines.get(i);
			context.drawTextWithShadow(this.textRenderer, line, textX, y + 18 + i * 10, VeloUi.withAlpha(MenuKit.TEXT_2, textAlpha));
		}
		if (!PackBridge.compatible(entry) && hovered && mouseX >= 0) {
			tooltip = List.of(PackBridge.title(entry).getString(), PackBridge.compatibilityNote(entry).getString());
		}
	}

	private List<String> badges(Object entry) {
		List<String> out = new ArrayList<>();
		if (!PackBridge.compatible(entry)) {
			out.add("Incompatible");
		}
		String id = PackBridge.id(entry);
		Path file = fileOf(id);
		if (file != null) {
			out.add(Files.isDirectory(file) ? "Folder" : "Zip");
		} else if (!id.startsWith("file/")) {
			out.add("Built-in");
		}
		return out;
	}

	/** The row's hover buttons: add / remove, move up / down, edit. */
	private void rowActions(DrawContext context, Object entry, int list, int x, int y, int w, int mouseX, int mouseY) {
		int size = 16;
		int bx = x + w - size - 6;
		int by = y + (ROW_H - size) / 2;
		if (list == 0) {
			if (PackBridge.canSelect(entry)) {
				hits.add(PackUi.iconButton(context, bx, by, size, "right", true, mouseX, mouseY, () -> select(entry)));
				bx -= size + 4;
			}
		} else {
			if (PackBridge.canUnselect(entry)) {
				hits.add(PackUi.iconButton(context, bx, by, size, "left", false, mouseX, mouseY, () -> unselect(entry)));
				bx -= size + 4;
			}
			if (PackBridge.canMoveDown(entry)) {
				hits.add(PackUi.iconButton(context, bx, by, size, "down", false, mouseX, mouseY, () -> move(entry, false)));
				bx -= size + 4;
			}
			if (PackBridge.canMoveUp(entry)) {
				hits.add(PackUi.iconButton(context, bx, by, size, "up", false, mouseX, mouseY, () -> move(entry, true)));
				bx -= size + 4;
			}
		}
		Path file = fileOf(PackBridge.id(entry));
		if (file != null && Files.isDirectory(file)) {
			String id = PackBridge.id(entry);
			hits.add(PackUi.iconButton(context, bx, by, size, "edit", false, mouseX, mouseY, () -> openEditor(id)));
		}
	}

	private void drawDetails(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		PackUi.heading(context, "Details", x + 2, y);
		int top = y + 12;
		MenuKit.panel(context, x, top, w, h - 12, 8);
		Object entry = focusedId != null ? find(focusedId) : null;
		if (entry == null) {
			List<String> lines = VeloUi.wrap("Click a pack to see its details. Folder packs can be edited right here in the game - "
					+ "every texture, with a full pixel editor and 3D preview.", w - 20);
			int ty = top + 12;
			for (String line : lines) {
				MenuKit.text(context, this.textRenderer, MenuKit.ui(line), x + 10, ty, MenuKit.TEXT_3);
				ty += 11;
			}
			hits.add(PackUi.button(context, x + 10, ty + 6, w - 20, 18, "Create a new pack", 1, true, mouseX, mouseY, this::promptNewPack));
			return;
		}
		String id = PackBridge.id(entry);
		int iconSize = Math.min(64, w - 20);
		VeloDraw.fillRounded(context, x + 10, top + 10, iconSize, iconSize, 6, 0x14FFFFFF);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, PackBridge.icon(entry), x + 10, top + 10, 0f, 0f, iconSize, iconSize, 64, 64, 64, 64);
		int ty = top + 18 + iconSize;
		for (String line : VeloUi.wrap(PackBridge.title(entry).getString(), w - 20)) {
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(line), x + 10, ty, MenuKit.TEXT);
			ty += 11;
		}
		ty += 3;
		int badgeX = x + 10;
		for (String b : badges(entry)) {
			badgeX += PackUi.badge(context, badgeX, ty, b, b.equals("Incompatible") ? 0xFFFF7A7E : b.equals("Folder") ? MenuKit.GREEN : MenuKit.TEXT_2) + 4;
		}
		if (PackBridge.isSelected(entry)) {
			PackUi.badge(context, badgeX, ty, "Selected", VeloStyle.accent() | 0xFF000000);
		}
		ty += 16;
		int buttonsH = 3 * 22 + 4;
		for (String line : VeloUi.wrap(PackBridge.description(entry).getString(), w - 20)) {
			if (ty > top + h - 12 - buttonsH - 12) {
				break;
			}
			context.drawTextWithShadow(this.textRenderer, line, x + 10, ty, MenuKit.TEXT_2);
			ty += 10;
		}
		if (!PackBridge.compatible(entry)) {
			for (String line : VeloUi.wrap(PackBridge.compatibilityNote(entry).getString(), w - 20)) {
				MenuKit.text(context, this.textRenderer, MenuKit.ui(line), x + 10, ty + 2, MenuKit.RED_TEXT);
				ty += 10;
			}
		}
		int by = top + h - 12 - buttonsH;
		Path file = fileOf(id);
		if (file != null && Files.isDirectory(file)) {
			hits.add(PackUi.button(context, x + 10, by, w - 20, 18, "Edit pack", 1, true, mouseX, mouseY, () -> openEditor(id)));
		} else if (file != null) {
			hits.add(PackUi.button(context, x + 10, by, w - 20, 18, "Make editable copy", 1, true, mouseX, mouseY, () -> makeEditable(id, file)));
		} else {
			hits.add(PackUi.button(context, x + 10, by, w - 20, 18, "Built in - not editable", 0, false, mouseX, mouseY, () -> {
			}));
		}
		by += 22;
		boolean isSelected = PackBridge.isSelected(entry);
		hits.add(PackUi.button(context, x + 10, by, w - 20, 18, isSelected ? "Remove from selected" : "Add to selected", 0,
				isSelected ? PackBridge.canUnselect(entry) : PackBridge.canSelect(entry), mouseX, mouseY, () -> toggle(entry)));
		by += 22;
		hits.add(PackUi.button(context, x + 10, by, w - 20, 18, "Show in folder", 0, file != null, mouseX, mouseY,
				() -> FileManagerOpener.open(file.toFile(), this::setStatus)));
	}

	// ---- Actions ----

	private void openEditor(String id) {
		Path file = fileOf(id);
		if (file == null || !Files.isDirectory(file)) {
			setStatus("Only folder packs can be edited - make an editable copy first.");
			return;
		}
		Object entry = find(id);
		String title = entry != null ? PackBridge.title(entry).getString() : file.getFileName().toString();
		this.client.setScreen(new PackBrowserScreen(this, file, id, title));
	}

	private void promptNewPack() {
		this.client.setScreen(new PackPromptScreen(this, "New Pack", "Name of the new resource pack", "My Pack", "Create", name -> {
			String folder = name.replaceAll("[\\\\/:*?\"<>|]", "").trim();
			if (folder.isEmpty()) {
				return "Type a name first.";
			}
			Path dir = PackBridge.packDir().resolve(folder);
			if (Files.exists(dir)) {
				return "A pack with that name already exists.";
			}
			try {
				createPack(dir, name);
			} catch (IOException e) {
				return "Couldn't create it: " + e.getMessage();
			}
			rescanNow();
			openEditorFor = "file/" + folder;
			setStatus("Created " + folder + ".");
			return null;
		}));
	}

	/** A new, empty folder pack: pack.mcmeta for this game version and a generated icon. */
	static void createPack(Path dir, String description) throws IOException {
		Files.createDirectories(dir.resolve("assets/minecraft/textures"));
		int format = PackBridge.packFormat();
		String desc = description.replace("\\", "\\\\").replace("\"", "\\\"");
		String meta = "{\n  \"pack\": {\n    \"pack_format\": " + format + ",\n    \"min_format\": " + format + ",\n    \"max_format\": " + format
				+ ",\n    \"description\": \"" + desc + "\"\n  }\n}\n";
		Files.writeString(dir.resolve("pack.mcmeta"), meta, StandardCharsets.UTF_8);
		TexDoc icon = TexDoc.blank(64, 64);
		int accent = VeloStyle.accent() | 0xFF000000;
		int[] px = icon.frames.get(0);
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				float t = (x + y) / 126f;
				int base = PixelOps.lerp(accent, 0xFF1A1A22, t * 0.85f);
				boolean border = x < 3 || y < 3 || x > 60 || y > 60;
				px[y * 64 + x] = border ? PixelOps.lerp(base, 0xFF000000, 0.35f) : base;
			}
		}
		icon.save(dir.resolve("pack.png"));
	}

	private void makeEditable(String id, Path zip) {
		String base = zip.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
		Path target = PackBridge.packDir().resolve(base);
		for (int i = 2; Files.exists(target); i++) {
			target = PackBridge.packDir().resolve(base + " (" + i + ")");
		}
		Path dir = target;
		setStatus("Unpacking " + zip.getFileName() + "...");
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				unzip(zip, dir);
				MinecraftClient.getInstance().execute(() -> {
					rescanNow();
					String copyId = "file/" + dir.getFileName();
					Object original = find(id);
					Object copy = find(copyId);
					if (original != null && copy != null && PackBridge.isSelected(original)) {
						PackBridge.unselect(original);
						Object freshCopy = find(copyId);
						if (freshCopy != null) {
							PackBridge.select(freshCopy);
						}
						changed = true;
					}
					focusedId = copyId;
					setStatus("Editable copy created: " + dir.getFileName());
				});
			} catch (IOException e) {
				MinecraftClient.getInstance().execute(() -> setStatus("Couldn't unpack: " + e.getMessage()));
			}
		});
	}

	private static void unzip(Path zip, Path dir) throws IOException {
		Path root = dir.toAbsolutePath().normalize();
		try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
			ZipEntry zipEntry;
			while ((zipEntry = zin.getNextEntry()) != null) {
				Path out = root.resolve(zipEntry.getName()).normalize();
				if (!out.startsWith(root)) {
					continue;
				}
				if (zipEntry.isDirectory()) {
					Files.createDirectories(out);
				} else {
					Files.createDirectories(out.getParent());
					Files.copy(zin, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		// A zip that wraps everything in one folder: lift its contents up.
		if (!Files.exists(root.resolve("pack.mcmeta"))) {
			try (Stream<Path> children = Files.list(root)) {
				List<Path> list = children.toList();
				if (list.size() == 1 && Files.isDirectory(list.get(0)) && Files.exists(list.get(0).resolve("pack.mcmeta"))) {
					Path inner = list.get(0);
					try (Stream<Path> innerChildren = Files.list(inner)) {
						for (Path child : innerChildren.toList()) {
							Files.move(child, root.resolve(child.getFileName()));
						}
					}
					Files.delete(inner);
				}
			}
		}
	}

	private void openVanilla() {
		openVanillaOnce = true;
		Screen parent = returnScreen();
		finished = true;
		PackBridge.commit(model);
		PackBridge.releaseIcons(model);
		closeWatcher();
		PackBridge.openVanillaScreen(parent);
	}

	private void rescanNow() {
		PackBridge.rescan(model);
		rebuild = true;
	}

	/** Packs dropped onto the window: copied into the packs folder (after asking). */
	private void filesDropped(List<Path> paths) {
		List<Path> packs = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		for (Path p : paths) {
			boolean zip = Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
			boolean folder = Files.isDirectory(p) && Files.exists(p.resolve("pack.mcmeta"));
			if (zip || folder) {
				packs.add(p);
			} else {
				rejected.add(p.getFileName().toString());
			}
		}
		if (packs.isEmpty()) {
			setStatus("Not a resource pack: " + String.join(", ", rejected));
			return;
		}
		String names = String.join(", ", packs.stream().map(p -> p.getFileName().toString()).toList());
		this.client.setScreen(new VeloConfirmScreen(this, "Add packs", "Copy into your resource packs folder: " + names
				+ (rejected.isEmpty() ? "" : " (skipping " + String.join(", ", rejected) + ")"), "Add", () -> {
					int copied = 0;
					for (Path p : packs) {
						try {
							copyRecursive(p, PackBridge.packDir().resolve(p.getFileName().toString()));
							copied++;
						} catch (IOException e) {
							setStatus("Couldn't copy " + p.getFileName() + ": " + e.getMessage());
						}
					}
					rescanNow();
					if (copied > 0) {
						setStatus("Added " + copied + (copied == 1 ? " pack." : " packs."));
					}
				}));
	}

	private static void copyRecursive(Path source, Path target) throws IOException {
		if (Files.isDirectory(source)) {
			try (Stream<Path> walk = Files.walk(source)) {
				for (Path p : walk.toList()) {
					Path out = target.resolve(source.relativize(p).toString());
					if (Files.isDirectory(p)) {
						Files.createDirectories(out);
					} else {
						Files.createDirectories(out.getParent());
						Files.copy(p, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		} else {
			Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	//? if <26.1 {
	@Override
	public void onFilesDropped(List<Path> paths) {
		filesDropped(paths);
	}
	//?} else {
	/*@Override
	public void onFilesDrop(List<Path> paths) {
		filesDropped(paths);
	}
	*///?}

	@Override
	public void tick() {
		super.tick();
		if (watcher != null && ++watchPoll >= 10) {
			watchPoll = 0;
			var key = watcher.poll();
			if (key != null) {
				key.pollEvents();
				key.reset();
				rescanIn = 20;
			}
		}
		if (rescanIn > 0 && --rescanIn == 0) {
			rescanNow();
		}
		if (openEditorFor != null && net.veloclient.velo.client.util.ClientCompat.currentScreen() == this) {
			String id = openEditorFor;
			openEditorFor = null;
			focusedId = id;
			openEditor(id);
		}
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
		for (VeloUi.Hit hit : List.copyOf(rowHits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				if (pressEntry != null) {
					focusedId = PackBridge.id(pressEntry);
					if (doubled) {
						toggle(pressEntry);
						pressEntry = null;
						return true;
					}
					pressX = click.x();
					pressY = click.y();
					dragX = pressX;
					dragY = pressY;
				}
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (pressEntry != null) {
			dragX = click.x();
			dragY = click.y();
			if (!dragging && Math.hypot(dragX - pressX, dragY - pressY) > 4) {
				dragging = true;
			}
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (pressEntry != null) {
			if (dragging) {
				drop();
			}
			pressEntry = null;
			dragging = false;
			return true;
		}
		return super.mouseReleased(click);
	}

	private void drop() {
		String id = PackBridge.id(pressEntry);
		int[] sel = listArea[1];
		int[] avail = listArea[0];
		if (VeloUi.inside(dragX, dragY, sel[0], sel[1], sel[2], sel[3])) {
			List<Object> shown = selected.stream().filter(this::matches).toList();
			int target = dropIndex(shown, sel[1]);
			if (pressList == 0) {
				select(pressEntry);
				refreshLists();
			}
			// Move it to where it was dropped, one step at a time (pinned packs stop it like vanilla).
			for (int guard = 0; guard < 200; guard++) {
				refreshLists();
				List<Object> now = selected.stream().filter(this::matches).toList();
				int at = indexOf(now, id);
				if (at < 0) {
					break;
				}
				int want = pressList == 1 && target > at ? target - 1 : target;
				Object entry = now.get(at);
				if (at > want && PackBridge.canMoveUp(entry)) {
					move(entry, true);
				} else if (at < want && PackBridge.canMoveDown(entry)) {
					move(entry, false);
				} else {
					break;
				}
			}
		} else if (pressList == 1 && VeloUi.inside(dragX, dragY, avail[0], avail[1], avail[2], avail[3])) {
			unselect(pressEntry);
		}
	}

	private static int indexOf(List<Object> list, String id) {
		for (int i = 0; i < list.size(); i++) {
			if (PackBridge.id(list.get(i)).equals(id)) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		for (int list = 0; list < 2; list++) {
			int[] a = listArea[list];
			if (VeloUi.inside(mouseX, mouseY, a[0], a[1], a[2], a[3])) {
				scrollTarget[list] -= (float) verticalAmount * 30;
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if ((input.key() == net.veloclient.velo.client.util.Keys.ENTER) && focusedId != null && (search == null || !search.isFocused())) {
			Object entry = find(focusedId);
			if (entry != null) {
				toggle(entry);
				return true;
			}
		}
		return super.keyPressed(input);
	}

	// ---- Lifecycle ----

	@Override
	protected void requestClose() {
		if (!finished) {
			finished = true;
			// Like vanilla's Done: saves the selection and reloads if it changed.
			PackBridge.commit(model);
		}
		super.requestClose();
	}

	@Override
	public void removed() {
		if (finished) {
			PackBridge.releaseIcons(model);
			closeWatcher();
		}
		super.removed();
	}

	private void closeWatcher() {
		if (watcher != null) {
			try {
				watcher.close();
			} catch (IOException ignored) {
				// Already closed.
			}
			watcher = null;
		}
	}
}
