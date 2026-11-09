package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind's editor while watching (Flashback-like): a menu bar on top; a glass timeline at the bottom - clip picker,
 * transport, speed, keyframe, export, a zoomable ruler and one row per track with draggable keyframes (right-click: value,
 * interpolation, label, delete); a side panel - World (time, rain, thunder, fog, speed), Camera (x, y, z, yaw, pitch, roll,
 * FOV), the entity or block you clicked in the world (hide it, swap a player's skin) and Output (format, resolution, FPS,
 * export). Every value can be set for the whole clip or keyframed. Escape hides the editor to fly; a small bar stays.
 */
//? if <26.2 {
public final class RewindEditorScreen {

	private RewindEditorScreen() {
	}

	static void worldTick() {
	}

	public static void registerHud() {
	}
}
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.util.Keys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RewindEditorScreen extends Screen {

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean in(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record Item(String label, String key, Runnable action) {
	}

	// ---- layout ----
	private static final int BAR_H = 18;
	private static final int MARGIN = 10;
	private static final int PANEL_W = 156;
	private static final int ROW_H = 14;
	private static final int ROW_GAP = 5;
	private static final int NAME_W = 70;
	private static final int TRACK_ROWS = 4;
	// The timeline's maximize toggle: taller (most of the screen), every track of every group shown; animated.
	private static boolean timelineMax;
	private static float timelineAnim;
	private static final String[] MENUS = {"File", "Edit", "View", "Playback", "Players", "Help"};
	private static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f};
	private static final String[] TABS = {"World", "Camera", "Selected", "Audio", "Export"};
	private static final String[] TAB_ICONS = {"globe", "eye", "pickaxe", "share"};

	// ---- state that survives closing the editor ----
	private static int tab;
	private static String selectedTrack = "g:Camera";
	private static int formatIndex = 3;
	private static int sizeIndex = 1;
	private static int exportFps = 60;
	private static Selection selection;

	// The entity picked in the world (outlined while the editor is open), or null.
	static Entity selectedEntity() {
		Selection s = selection;
		return s == null ? null : s.entity();
	}
	private static double viewStart;
	private static double viewEnd = -1;
	private static float shown;          // 0..1 open animation of the editor's panels

	// What was clicked in the world.
	private record Selection(Entity entity, net.minecraft.core.BlockPos block, String blockName) {
	}

	private final List<Hit> hits = new ArrayList<>();
	private final boolean showExport;
	private String tabTip;
	private int tabTipX;
	private int tabTipY;
	private int openMenu = -1;
	private int scrollRows;
	private boolean scrubbing;
	private int lastScrubSeek = -1;
	private long lastScrubSeekAt;
	private boolean showHelp;
	private boolean clipMenu;
	// dragging a keyframe
	private String dragTrack;
	private int dragFrom = -1;
	private int dragTo = -1;
	// right-click menu
	private List<Item> context;
	private int contextX;
	private int contextY;
	// a small "type a value" box
	private String inputTitle;
	private java.util.function.Consumer<String> inputDone;
	private net.minecraft.client.gui.components.EditBox input;
	private int sliderDrag = -1;          // index into panel rows while a value slider is dragged
	private final List<Object[]> sliders = new ArrayList<>(); // {x, y, w, property}
	private net.minecraft.client.gui.components.EditBox skinBox;

	public RewindEditorScreen() {
		this(false);
	}

	RewindEditorScreen(boolean showExport) {
		super(Component.literal("Velo Rewind"));
		this.showExport = showExport;
	}

	// ---- in-world: Escape swaps the pause menu for the editor; keys work while flying ----

	private static final boolean[] wasDown = new boolean[5];

	static void worldTick() {
		RewindPlayer p = RewindPlayer.current();
		Minecraft mc = Minecraft.getInstance();
		if (p == null || mc.level == null) {
			shown = 0;
			return;
		}
		Screen screen = mc.gui.screen();
		// a replay opens in the viewport (fly around and watch); Esc opens the editor
		if (screen instanceof PauseScreen && !RewindExport.busy()) {
			mc.setScreen(new RewindEditorScreen());
			return;
		}
		if (screen != null || RewindExport.busy()) {
			return;
		}
		int[] keys = {playKey(), Keys.LEFT, Keys.RIGHT, Keys.LEFT_BRACKET, Keys.RIGHT_BRACKET};
		for (int i = 0; i < keys.length; i++) {
			boolean down = Keys.isKeyDown(keys[i]);
			if (down && !wasDown[i]) {
				switch (i) {
					case 0 -> p.togglePause();
					case 1 -> p.seekClip(p.clipPosition() - 100);
					case 2 -> p.seekClip(p.clipPosition() + 100);
					case 3 -> p.setSpeed(step(p.speed(), -1));
					default -> p.setSpeed(step(p.speed(), 1));
				}
			}
			wasDown[i] = down;
		}
	}

	// The play / pause key as bound in Controls (Enter by default).
	static int playKey() {
		return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(RewindModule.PLAY_KEY).getValue();
	}

	static String playKeyName() {
		String n = RewindModule.PLAY_KEY.getTranslatedKeyMessage().getString();
		return n.length() > 8 ? n.substring(0, 8) : n;
	}

	// Viewport (no editor open): a left click picks what the crosshair points at - the outline shows it, the editor's
	// Selected tab has its options. Runs before the game sees the click (a spectator click would do nothing useful).
	static void viewportSelect() {
		Minecraft mc = Minecraft.getInstance();
		if (RewindPlayer.current() == null || mc.player == null || mc.gui.screen() != null || RewindExport.busy()) {
			return;
		}
		while (mc.options.keyAttack.consumeClick()) {
			var cam = mc.gameRenderer.mainCamera();
			Vec3 from = cam.position();
			Vec3 dir = new Vec3(cam.forwardVector());
			double reach = 96;
			var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(mc.player, from, from.add(dir.scale(reach)),
					new net.minecraft.world.phys.AABB(from, from.add(dir.scale(reach))).inflate(1), e -> e != mc.player && !e.isSpectator(), reach * reach);
			selection = hit == null ? null : new Selection(hit.getEntity(), null, null);
			tab = 2;
			if (hit != null) {
				mc.player.sendOverlayMessage(Component.literal("Selected " + hit.getEntity().getName().getString() + " - Esc for its options"));
			}
		}
	}

	private static float step(float speed, int dir) {
		int best = 2;
		for (int i = 0; i < SPEEDS.length; i++) {
			if (Math.abs(SPEEDS[i] - speed) < 0.01f) {
				best = i;
			}
		}
		return SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, best + dir))];
	}

	// ---- the small bar while flying (editor closed) ----

	private static float barShown;

	public static void registerHud() {
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "rewind_bar"), (g, delta) -> drawBar(g));
	}

	private static int barLogs;

	private static void drawBar(GuiGraphicsExtractor g) {
		RewindPlayer p = RewindPlayer.current();
		Minecraft mc = Minecraft.getInstance();
		if (Boolean.getBoolean("velo.rewindBarDebug") && barLogs++ % 60 == 0) {
			org.slf4j.LoggerFactory.getLogger("Velo Rewind").info("bar: player {} screen {} shown {}", p != null, mc.gui.screen(), barShown);
		}
		boolean want = p != null && mc.gui.screen() == null && !RewindExport.busy();
		barShown = VeloAnim.step(barShown, want ? 1f : 0f, 1 / 60f * 3f);
		if (p == null || barShown < 0.02f) {
			return;
		}
		Font font = mc.font;
		int w = 260;
		int h = 26;
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() - 8 - Math.round(h * barShown) - 30;
		int alpha = Math.round(255 * barShown);
		VeloDraw.fillRounded(g, x, y, w, h, 9, (Math.round(0xC4 * barShown) << 24) | 0x101015);
		VeloDraw.strokeRounded(g, x, y, w, h, 9, (Math.round(0x24 * barShown) << 24) | 0xFFFFFF);
		MenuKit.icon(g, p.paused() ? "play" : "pause", x + 10, y + 9, 8, (alpha << 24) | 0xF4F4F6);
		RewindEdits.Clip c = p.edits.clip();
		var t = MenuKit.mono(time(p.clipPosition()) + " / " + time(c.length));
		MenuKit.text(g, font, t, x + 24, y + 10, (alpha << 24) | 0xA9A9B3);
		int bx = x + 26 + MenuKit.width(font, t) + 10;
		int bw = x + w - 12 - bx;
		g.fill(bx, y + 12, bx + bw, y + 14, (Math.round(0x30 * barShown) << 24) | 0xFFFFFF);
		int done = c.length == 0 ? 0 : (int) ((long) bw * p.clipPosition() / c.length);
		g.fill(bx, y + 12, bx + done, y + 14, (alpha << 24) | (MenuKit.accent() & 0xFFFFFF));
		drawKeys(g, font, p, barShown);
	}

	// Top right while watching: which keys do what, the play / pause one first and biggest.
	private static void drawKeys(GuiGraphicsExtractor g, Font font, RewindPlayer p, float shown) {
		String[][] rows = {
				{playKeyName(), p.paused() ? "Play" : "Pause"},
				{"Esc", "Editor"},
				{"\u2190 \u2192", "5 s back / on"},
				{"[ ]", "Speed " + speedLabel(p.speed())}};
		int a = Math.round(255 * shown);
		int capW = 16;
		int labelW = 0;
		for (String[] r : rows) {
			capW = Math.max(capW, MenuKit.width(font, MenuKit.mono(r[0])) + 8);
			labelW = Math.max(labelW, MenuKit.width(font, MenuKit.ui(r[1])));
		}
		int w = 8 + capW + 8 + labelW + 10;
		int rowH = 15;
		int h = 10 + rowH * rows.length + 4;
		int x = g.guiWidth() - w - 10;
		int y = 10;
		VeloDraw.fillRounded(g, x, y, w, h, 8, (Math.round(0xB8 * shown) << 24) | 0x101015);
		VeloDraw.strokeRounded(g, x, y, w, h, 8, (Math.round(0x24 * shown) << 24) | 0xFFFFFF);
		for (int i = 0; i < rows.length; i++) {
			int ry = y + 7 + i * rowH;
			var key = MenuKit.mono(rows[i][0]);
			int kw = capW;
			boolean main = i == 0;
			int cap = main ? (a << 24) | (MenuKit.accent() & 0xFFFFFF) : (Math.round(0x30 * shown) << 24) | 0xFFFFFF;
			VeloDraw.fillRounded(g, x + 8, ry, kw, 12, 3, cap);
			MenuKit.text(g, font, key, x + 8 + (kw - MenuKit.width(font, key)) / 2, ry + 2, (a << 24) | 0xFFFFFF);
			var label = main ? MenuKit.uiBold(rows[i][1]) : MenuKit.ui(rows[i][1]);
			MenuKit.text(g, font, label, x + 8 + capW + 8, ry + 2, (Math.round((main ? 0xF4 : 0xA9) * shown) << 24) | (main ? 0xF4F4F6 : 0xA9A9B3));
		}
	}

	private static String speedLabel(float s) {
		return (Math.abs(s - Math.round(s)) < 0.01f ? String.valueOf(Math.round(s)) : String.valueOf(s)) + "x";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// the world stays visible behind the editor
	}

	@Override
	protected void init() {
		RewindPlayer p = RewindPlayer.current();
		int px = width - MARGIN - PANEL_W;
		skinBox = new net.minecraft.client.gui.components.EditBox(font, px + 12, 0, PANEL_W - 24, 14, Component.literal("Skin"));
		skinBox.setMaxLength(260);
		skinBox.setHint(Component.literal("Player name or PNG path"));
		skinBox.visible = false;
		addRenderableWidget(skinBox);
		playerSearch = new net.minecraft.client.gui.components.EditBox(font, 0, 0, 180, 14, Component.literal("Search"));
		playerSearch.setHint(Component.literal("Search players"));
		playerSearch.setMaxLength(32);
		playerSearch.visible = false;
		addRenderableWidget(playerSearch);
		if (input != null) {
			addRenderableWidget(input);
		}
	}

	@Override
	public void removed() {
		RewindApply.previewTick = -1;
		RewindPlayer p = RewindPlayer.current();
		if (p != null) {
			p.saveEdits();
		}
	}

	static String time(int ticks) {
		int s = Math.max(0, ticks) / 20;
		return String.format("%d:%02d", s / 60, s % 60);
	}

	private static String trim(double f) {
		return f == Math.rint(f) ? Long.toString(Math.round(f)) : String.format(java.util.Locale.ROOT, "%.2f", f);
	}

	// ---- drawing ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		RewindPlayer p = RewindPlayer.current();
		hits.clear();
		sliders.clear();
		shown = VeloAnim.step(shown, 1f, delta / 20f * 4f);
		if (p == null) {
			super.extractRenderState(g, mouseX, mouseY, delta);
			return;
		}
		drawFrameGuide(g);
		int slide = Math.round((1 - easeOut(shown)) * 140);
		g.pose().pushMatrix();
		g.pose().translate(0, slide);
		drawTimeline(g, font, p, mouseX, mouseY - slide);
		g.pose().popMatrix();
		g.pose().pushMatrix();
		g.pose().translate(slide * 1.6f, 0);
		drawPanel(g, font, p, mouseX - Math.round(slide * 1.6f), mouseY);
		g.pose().popMatrix();
		drawMenuBar(g, font, p, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, delta);
		if (tabTip != null) {
			var tip = MenuKit.ui(tabTip);
			int tw = MenuKit.width(font, tip) + 10;
			VeloDraw.fillRounded(g, tabTipX - tw / 2, tabTipY, tw, 12, 4, 0xE0101015);
			MenuKit.text(g, font, tip, tabTipX - tw / 2 + 5, tabTipY + 2, MenuKit.TEXT);
			tabTip = null;
		}
		if (clipMenu) {
			drawClipMenu(g, font, p, mouseX, mouseY);
		}
		if (context != null) {
			drawContext(g, font, mouseX, mouseY);
		}
		if (inputTitle != null) {
			drawInput(g, font);
		}
		if (showHelp) {
			drawHelp(g, font);
		}
		if (exportOpen || showExport || RewindExport.state != RewindExport.State.IDLE) {
			drawExport(g, font, mouseX, mouseY);
		}
	}

	// The export frame's width / height (16:9 when it follows the window).
	static double frameAspect() {
		RewindExport.Format f = RewindExport.FORMATS.get(formatIndex);
		if (f.aspect() > 0) {
			return f.aspect();
		}
		var w = Minecraft.getInstance().getWindow();
		return w.getWidth() / (double) Math.max(1, w.getHeight());
	}

	private static float easeOut(float t) {
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	// the export's frame: everything outside the chosen format is dimmed
	private void drawFrameGuide(GuiGraphicsExtractor g) {
		RewindExport.Format f = RewindExport.FORMATS.get(formatIndex);
		if (f.aspect() <= 0) {
			return;
		}
		int fw = width;
		int fh = height;
		if (width / (double) height > f.aspect()) {
			fw = (int) Math.round(height * f.aspect());
		} else {
			fh = (int) Math.round(width / f.aspect());
		}
		int x0 = (width - fw) / 2;
		int y0 = (height - fh) / 2;
		int dim = 0x99000000;
		g.fill(0, 0, x0, height, dim);
		g.fill(x0 + fw, 0, width, height, dim);
		g.fill(x0, 0, x0 + fw, y0, dim);
		g.fill(x0, y0 + fh, x0 + fw, height, dim);
		VeloDraw.strokeRect(g, x0, y0, fw, fh, 0x66FFFFFF);
	}

	private void drawMenuBar(GuiGraphicsExtractor g, Font font, RewindPlayer p, int mouseX, int mouseY) {
		playerSearch.visible = false; // the Players menu shows it again while it's open
		g.fill(0, 0, width, BAR_H, 0xC4101015);
		g.fill(0, BAR_H, width, BAR_H + 1, 0x24FFFFFF);
		MenuKit.logo(g, 6, 3, 12);
		int x = 24;
		for (int i = 0; i < MENUS.length; i++) {
			var label = MenuKit.ui(MENUS[i]);
			int w = MenuKit.width(font, label) + 12;
			boolean over = MenuKit.inside(mouseX, mouseY, x, 0, w, BAR_H);
			if (openMenu == i || over) {
				VeloDraw.fillRounded(g, x, 2, w, BAR_H - 4, 4, openMenu == i ? 0x33FFFFFF : 0x1AFFFFFF);
			}
			MenuKit.text(g, font, label, x + 6, (BAR_H - MenuKit.H_UI) / 2, MenuKit.TEXT);
			int index = i;
			int mx = x;
			hits.add(new Hit(x, 0, w, BAR_H, () -> openMenu = openMenu == index ? -1 : index));
			if (openMenu == i) {
				if (MENUS[i].equals("Players")) {
					drawPlayers(g, font, p, mx, BAR_H + 2, mouseX, mouseY);
				} else {
					drawDropdown(g, font, mx, BAR_H + 2, items(i, p), mouseX, mouseY, true);
				}
			}
			x += w + 2;
		}
		String right = p.name() + "  ·  " + p.edits.clip().name + "  ·  " + (p.paused() ? "Paused" : "Playing") + "  ·  " + trim(p.speed()) + "x";
		var t = MenuKit.mono(right);
		MenuKit.text(g, font, t, width - MenuKit.width(font, t) - 8, (BAR_H - MenuKit.H_MONO) / 2, MenuKit.TEXT_2);
	}

	private List<Item> items(int menu, RewindPlayer p) {
		Minecraft mc = Minecraft.getInstance();
		return switch (menu) {
			case 0 -> List.of(
					new Item("Export video...", null, () -> openExport()),
					new Item("Show recordings folder", null, () -> net.veloclient.velo.client.gui.FileManagerOpener.open(RewindLibraryScreen.folder().toFile(), s -> { })),
					new Item("Exit replay", null, RewindPlayer::exit));
			case 1 -> List.of(
					new Item(p.autosave.canUndo() ? "Undo" : "Undo (nothing yet)", "Ctrl+Z", p::undo),
					new Item(p.autosave.canRedo() ? "Redo" : "Redo (nothing)", "Ctrl+Y", p::redo),
					new Item("Show edit backups", null, () -> {
						java.nio.file.Path dir = RewindAutosave.backupDir(p.file());
						try {
							java.nio.file.Files.createDirectories(dir);
						} catch (java.io.IOException ignored) {
						}
						net.veloclient.velo.client.gui.FileManagerOpener.open(dir.toFile(), s -> { });
					}),
					new Item("Add keyframe", "K", () -> addKey(p, selectedTrack)),
					new Item("Delete this clip", null, () -> deleteClip(p)),
					new Item("New clip", null, p::addClip),
					new Item("Rename clip...", null, () -> ask("Clip name", p.edits.clip().name, v -> {
						p.edits.clip().name = v.isBlank() ? p.edits.clip().name : v.trim();
						p.saveEdits();
					})),
					new Item("Clear this clip's keyframes", null, () -> {
						p.edits.clip().tracks.values().forEach(t -> t.keys.clear());
						p.saveEdits();
					}));
			case 2 -> List.of(
					new Item(RewindApply.previewCamera ? "Free camera while paused" : "Camera path while paused", null,
							() -> RewindApply.previewCamera = !RewindApply.previewCamera),
					new Item("Frame: " + RewindExport.FORMATS.get(formatIndex).name(), null, () -> formatIndex = (formatIndex + 1) % RewindExport.FORMATS.size()),
					new Item("Hide editor (fly)", "Esc", () -> mc.setScreen(null)));
			case 3 -> List.of(
					new Item(p.paused() ? "Play" : "Pause", playKeyName(), p::togglePause),
					new Item("Back 5 s", "Left", () -> p.seekClip(p.clipPosition() - 100)),
					new Item("Forward 5 s", "Right", () -> p.seekClip(p.clipPosition() + 100)),
					new Item("To the clip's start", null, () -> p.seekClip(0)));
			default -> List.of(new Item("Controls", null, () -> showHelp = !showHelp),
					new Item(RewindApi.port() > 0 ? "AI access: on (port " + RewindApi.port() + ")" : "AI access: off - turn on",
							null, () -> RewindModule.setAiAccess(RewindApi.port() <= 0)),
					new Item("Copy prompt for an AI", null, RewindEditorScreen::copyAiPrompt),
					new Item("Open AI docs folder", null, () -> net.veloclient.velo.client.gui.FileManagerOpener.open(RewindLibraryScreen.folder().toFile(), s -> { })));
		};
	}

	private void drawDropdown(GuiGraphicsExtractor g, Font font, int x, int y, List<Item> items, int mouseX, int mouseY, boolean closeMenus) {
		int w = 170;
		int rowH = 16;
		int h = items.size() * rowH + 6;
		x = Math.min(x, width - w - 4);
		y = Math.min(y, height - h - 4);
		MenuKit.panel(g, x, y, w, h, 7);
		List<Hit> mine = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			Item it = items.get(i);
			int ry = y + 3 + i * rowH;
			boolean danger = it.label().startsWith("Delete") || it.label().startsWith("Remove");
			float hv = MenuKit.hover("rw-dd-" + it.label(), MenuKit.inside(mouseX, mouseY, x + 3, ry, w - 6, rowH));
			if (hv > 0.01f) {
				VeloDraw.fillRounded(g, x + 3, ry, w - 6, rowH, 4, (Math.round((danger ? 0x38 : 0x22) * hv) << 24) | (danger ? 0xFF5A5F : 0xFFFFFF));
			}
			MenuKit.text(g, font, MenuKit.ui(it.label()), x + 10, ry + (rowH - MenuKit.H_UI) / 2, danger ? MenuKit.RED_TEXT : MenuKit.TEXT);
			if (it.key() != null && !it.key().isEmpty()) {
				MenuKit.keyHint(g, font, it.key(), x + w - 8, ry + (rowH - MenuKit.H_MONO) / 2, MenuKit.TEXT_3);
			}
			Runnable action = it.action();
			mine.add(new Hit(x + 3, ry, w - 6, rowH, () -> {
				if (closeMenus) {
					openMenu = -1;
				}
				context = null;
				clipMenu = false;
				action.run();
			}));
		}
		hits.addAll(0, mine);
	}

	// ---- timeline ----

	private int lastLeft;
	private int lastSpan = 1;
	private int lastLength = 1;
	private int lastTracksTop;
	private int lastTracksBottom;
	private int lastRowsTop;
	private final List<String> rowTracks = new ArrayList<>();

	private int xOf(double t) {
		return lastLeft + (int) Math.round((t - viewStart) * lastSpan / Math.max(1, viewEnd - viewStart));
	}

	private int tickAt(double mx) {
		double t = viewStart + (mx - lastLeft) * (viewEnd - viewStart) / Math.max(1, lastSpan);
		return (int) Math.max(0, Math.min(lastLength, Math.round(t)));
	}

	// The tracks shown: the ones with values, plus the one picked in the panel.
	// Rows: a header per group ("g:Camera" - always there for the camera), its tracks under it while it's open.
	private static final java.util.Set<String> collapsed = new java.util.HashSet<>();

	private List<String> visibleTracks(RewindEdits.Clip c) {
		List<String> out = new ArrayList<>();
		for (String group : new String[] {"Camera", "World", "Audio", "Playback"}) {
			List<String> kids = new ArrayList<>();
			for (RewindEdits.Property prop : RewindEdits.PROPERTIES) {
				if (!prop.group().equals(group)) {
					continue;
				}
				RewindEdits.Track t = c.tracks.get(prop.id());
				if (timelineMax || t != null && t.active() || prop.id().equals(selectedTrack)) {
					kids.add(prop.id());
				}
			}
			if (group.equals("Audio")) {
				for (var e : c.tracks.entrySet()) {
					if (e.getKey().startsWith("voice:") && (e.getValue().active() || e.getKey().equals(selectedTrack))) {
						kids.add(e.getKey());
					}
				}
				if (timelineMax) {
					for (String uuid : RewindSound.voiceTracks()) {
						if (!kids.contains("voice:" + uuid)) {
							kids.add("voice:" + uuid);
						}
					}
				}
				if (selectedTrack != null && selectedTrack.startsWith("voice:") && !kids.contains(selectedTrack)) {
					kids.add(selectedTrack);
				}
			}
			if (kids.isEmpty() && !group.equals("Camera") && !("g:" + group).equals(selectedTrack)) {
				continue;
			}
			out.add("g:" + group);
			if (!collapsed.contains(group)) {
				out.addAll(kids);
			}
		}
		return out;
	}

	private static boolean isGroup(String row) {
		return row != null && row.startsWith("g:");
	}

	// The tracks of a group (or just the track itself).
	private static List<String> members(String row) {
		if (!isGroup(row)) {
			return List.of(row);
		}
		List<String> out = new ArrayList<>();
		for (RewindEdits.Property prop : RewindEdits.PROPERTIES) {
			if (prop.group().equals(row.substring(2)) && !(row.equals("g:Camera") && prop.id().equals("fov"))) {
				out.add(prop.id());
			}
		}
		RewindPlayer p = RewindPlayer.current();
		if (row.equals("g:Audio") && p != null) {
			for (String id : p.edits.clip().tracks.keySet()) {
				if (id.startsWith("voice:")) {
					out.add(id);
				}
			}
		}
		return out;
	}

	// The keyframe ticks of a row: a group shows every tick any of its tracks has a key at.
	private static java.util.TreeSet<Integer> rowKeys(RewindEdits.Clip c, String row) {
		java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
		for (String id : members(row)) {
			RewindEdits.Track t = c.tracks.get(id);
			if (t != null) {
				out.addAll(t.keys.keySet());
			}
		}
		return out;
	}

	private static int trackColor(String id) {
		RewindEdits.Property p = isGroup(id) ? null : RewindEdits.property(id);
		return switch (p == null ? (isGroup(id) ? id.substring(2) : "") : p.group()) {
			case "Camera" -> 0xFFF7C04A;
			case "Playback" -> 0xFF3DDC97;
			case "Audio" -> 0xFFC084FC;
			default -> 0xFF5AA9FF;
		};
	}

	// Square toggle: arrows pointing out (maximize) that turn to point in (back to normal) as the timeline grows.
	private int maximizeButton(GuiGraphicsExtractor g, int right, int y, int size, int mouseX, int mouseY) {
		int x = right - size;
		float hv = MenuKit.hover("rw-max", MenuKit.inside(mouseX, mouseY, x, y, size, size));
		VeloDraw.fillRounded(g, x, y, size, size, 8, VeloAnim.lerpArgb(timelineMax ? 0x24FFFFFF : 0x14FFFFFF, 0x2EFFFFFF, hv));
		float cx = x + size / 2f;
		float cy = y + size / 2f;
		float t = easeOut(timelineAnim);
		int c = VeloAnim.lerpArgb(MenuKit.TEXT_2, MenuKit.TEXT, hv);
		// two corner arrows; the arrow heads slide from the outer corners (maximize) to the inner ones (restore)
		for (int s = -1; s <= 1; s += 2) {
			float ox = cx + s * 5.5f;
			float oy = cy - s * 5.5f;
			float ix = cx + s * 1.5f;
			float iy = cy - s * 1.5f;
			VeloDraw.line(g, ix, iy, ox, oy, 1.2f, c);
			float hx = ix + (ox - ix) * (1 - t);
			float hy = iy + (oy - iy) * (1 - t);
			float dir = t < 0.5f ? 1 : -1; // which way the head opens
			float len = 3f;
			VeloDraw.line(g, hx, hy, hx - s * len * dir, hy, 1.2f, c);
			VeloDraw.line(g, hx, hy, hx, hy + s * len * dir, 1.2f, c);
		}
		hits.add(new Hit(x, y, size, size, () -> timelineMax = !timelineMax));
		if (hv > 0.5f) {
			tabTip = timelineMax ? "Smaller timeline" : "Maximize timeline";
			tabTipX = x + size / 2;
			tabTipY = y - 14;
		}
		return x;
	}

	private void drawTimeline(GuiGraphicsExtractor g, Font font, RewindPlayer p, int mouseX, int mouseY) {
		RewindEdits.Clip clip = p.edits.clip();
		int length = Math.max(1, clip.length);
		if (viewEnd < 0 || viewEnd > length || viewEnd <= viewStart) {
			viewStart = 0;
			viewEnd = length;
		}
		int pos = scrubbing ? (int) Math.round(RewindApply.previewTick) : p.clipPosition();
		int pad = 12;
		int controlsH = 24;
		int rulerH = 14;
		List<String> tracks = visibleTracks(clip);
		timelineAnim = VeloAnim.step(timelineAnim, timelineMax ? 1f : 0f, 1 / 60f * 4f);
		float grow = easeOut(timelineAnim);
		int fixedH = pad + controlsH + 10 + rulerH + 4 + pad;
		int maxRows = Math.max(TRACK_ROWS, (int) ((height * 0.66f - fixedH - BAR_H) / (ROW_H + ROW_GAP)));
		float rowsF = Math.min(Math.max(1, tracks.size()), TRACK_ROWS + (maxRows - TRACK_ROWS) * grow);
		int rows = Math.max(1, Math.min(tracks.size(), (int) Math.floor(rowsF + 0.001f)));
		scrollRows = Math.max(0, Math.min(scrollRows, tracks.size() - rows));
		int x0 = MARGIN;
		int panelW = width - 2 * MARGIN - PANEL_W - 8;
		int panelH = fixedH + Math.round(Math.max(1f, rowsF) * (ROW_H + ROW_GAP)) - ROW_GAP;
		int top = height - MARGIN - panelH;
		glass(g, x0, top, panelW, panelH);

		// controls: clip picker + clock (left), transport (centre), speed / keyframe / export (right)
		int cy = top + pad;
		var clipLabel = MenuKit.uiBold(clip.name);
		int cw = Math.min(110, MenuKit.width(font, clipLabel) + 26);
		float ch = MenuKit.hover("rw-clipbtn", MenuKit.inside(mouseX, mouseY, x0 + pad, cy, cw, controlsH));
		VeloDraw.fillRounded(g, x0 + pad, cy, cw, controlsH, 8, VeloAnim.lerpArgb(0x14FFFFFF, 0x2AFFFFFF, ch));
		MenuKit.text(g, font, MenuKit.fit(font, clipLabel, cw - 24), x0 + pad + 9, cy + (controlsH - MenuKit.H_UI) / 2, MenuKit.TEXT);
		MenuKit.icon(g, "menu", x0 + pad + cw - 15, cy + 8, 8, MenuKit.TEXT_2);
		hits.add(new Hit(x0 + pad, cy, cw, controlsH, () -> clipMenu = !clipMenu));
		clipMenuX = x0 + pad;
		clipMenuY = cy;
		var now = MenuKit.displaySm(time(pos));
		int clockX = x0 + pad + cw + 12;
		MenuKit.text(g, font, now, clockX, cy + (controlsH - MenuKit.H_DISPLAY_SM) / 2, MenuKit.TEXT);
		MenuKit.text(g, font, MenuKit.mono("/ " + time(length)), clockX + MenuKit.width(font, now) + 5,
				cy + (controlsH - MenuKit.H_DISPLAY_SM) / 2 + MenuKit.H_DISPLAY_SM - MenuKit.H_MONO, MenuKit.TEXT_3);
		int bsz = 24;
		int bx = x0 + panelW / 2 - (bsz * 3 + 16) / 2;
		circle(g, bx, cy, bsz, "reset", false, mouseX, mouseY, () -> p.seekClip(0));
		circle(g, bx + bsz + 8, cy, bsz, p.paused() ? "play" : "pause", true, mouseX, mouseY, p::togglePause);
		circle(g, bx + 2 * (bsz + 8), cy, bsz, "arrow_right", false, mouseX, mouseY, () -> p.seekClip(p.clipPosition() + 100));
		int rx = x0 + panelW - pad;
		rx = iconPill(g, font, rx, cy, controlsH, "share", "Export", false, mouseX, mouseY, this::openExport) - 6;
		rx = maximizeButton(g, rx, cy, controlsH, mouseX, mouseY) - 6;
		rx = iconPill(g, font, rx, cy, controlsH, "flag", "Keyframe", true, mouseX, mouseY, () -> addKey(p, selectedTrack)) - 10;
		int segW = 0;
		for (float sp : SPEEDS) {
			segW += MenuKit.width(font, MenuKit.ui(trim(sp) + "x")) + 10;
		}
		int sx = rx - segW - 4;
		if (sx > bx + 3 * (bsz + 8) + 6) {
			VeloDraw.fillRounded(g, sx, cy, segW + 4, controlsH, 8, 0x14FFFFFF);
			int ix = sx + 2;
			for (int i = 0; i < SPEEDS.length; i++) {
				float sp = SPEEDS[i];
				var label = MenuKit.ui(trim(sp) + "x");
				int w = MenuKit.width(font, label) + 10;
				boolean on = Math.abs(p.speed() - sp) < 0.01f;
				float hv = MenuKit.hover("rw-speed-" + i, MenuKit.inside(mouseX, mouseY, ix, cy + 2, w, controlsH - 4));
				if (on || hv > 0.01f) {
					VeloDraw.fillRounded(g, ix, cy + 2, w, controlsH - 4, 6, on ? 0xFF2C2C34 : (Math.round(0x18 * hv) << 24) | 0xFFFFFF);
				}
				MenuKit.text(g, font, label, ix + 5, cy + (controlsH - MenuKit.H_UI) / 2, on ? MenuKit.TEXT : MenuKit.TEXT_2);
				hits.add(new Hit(ix, cy, w, controlsH, () -> p.setSpeed(sp)));
				ix += w;
			}
		}

		// ruler
		int left = x0 + pad + NAME_W;
		int right = x0 + panelW - pad;
		int span = right - left;
		int rulerY = cy + controlsH + 10;
		lastLeft = left;
		lastSpan = span;
		lastLength = length;
		double visible = Math.max(1, viewEnd - viewStart);
		int[] steps = {5, 10, 20, 40, 100, 200, 400, 1200, 2400, 6000, 12000};
		int stepT = steps[steps.length - 1];
		for (int st : steps) {
			if (span * st / visible >= 70) {
				stepT = st;
				break;
			}
		}
		g.enableScissor(left - 2, rulerY - 2, right + 2, top + panelH);
		for (int t = (int) (Math.ceil(viewStart / stepT) * stepT); t <= viewEnd + 1; t += stepT) {
			int x = xOf(t);
			g.fill(x, rulerY + 9, x + 1, rulerY + rulerH, 0x55FFFFFF);
			var lt = MenuKit.mono(stepT < 20 ? String.format(java.util.Locale.ROOT, "%.2f", t / 20.0) : time(t));
			int lw = MenuKit.width(font, lt);
			MenuKit.text(g, font, lt, Math.min(right - lw, Math.max(left, x - lw / 2)), rulerY, MenuKit.TEXT_3);
			for (int k = 1; k < 4; k++) {
				int mx = xOf(t + stepT * k / 4.0);
				g.fill(mx, rulerY + 11, mx + 1, rulerY + rulerH, 0x26FFFFFF);
			}
		}
		g.disableScissor();
		MenuKit.text(g, font, MenuKit.mono(tracks.size() > rows ? "Tracks " + (scrollRows + 1) + "-" + (scrollRows + rows) + "/" + tracks.size() : "Tracks"),
				x0 + pad, rulerY + 1, MenuKit.TEXT_3);

		// track rows
		int rowY = rulerY + rulerH + 4;
		lastRowsTop = rowY;
		lastTracksTop = rulerY - 2;
		rowTracks.clear();
		for (int r = 0; r < rows; r++) {
			String id = tracks.get(scrollRows + r);
			rowTracks.add(id);
			boolean group = isGroup(id);
			RewindEdits.Track track = group ? null : clip.tracks.get(id);
			int y = rowY + r * (ROW_H + ROW_GAP);
			boolean sel = id.equals(selectedTrack);
			VeloDraw.fillRounded(g, left, y, span, ROW_H, 4, sel ? 0x16FFFFFF : group ? 0x10FFFFFF : 0x08FFFFFF);
			if (track != null && track.global != null && track.keys.isEmpty()) {
				VeloDraw.fillRounded(g, left, y + ROW_H / 2 - 1, span, 3, 1, (trackColor(id) & 0xFFFFFF) | 0x70000000);
			}
			float hv = MenuKit.hover("rw-track-" + id, MenuKit.inside(mouseX, mouseY, x0 + pad, y, NAME_W, ROW_H));
			int nameColor = sel ? MenuKit.TEXT : VeloAnim.lerpArgb(group ? MenuKit.TEXT_2 : MenuKit.TEXT_3, MenuKit.TEXT, hv);
			if (group) {
				String name = id.substring(2);
				boolean open = !collapsed.contains(name);
				// open / close triangle
				float tx = x0 + pad + 3f;
				float ty = y + ROW_H / 2f;
				if (open) {
					VeloDraw.fillQuad(g, tx - 3, ty - 1.5f, tx + 3, ty - 1.5f, tx, ty + 2.5f, tx - 3, ty - 1.5f, nameColor);
				} else {
					VeloDraw.fillQuad(g, tx - 1.5f, ty - 3, tx + 2.5f, ty, tx - 1.5f, ty + 3, tx - 1.5f, ty - 3, nameColor);
				}
				hits.add(new Hit(x0 + pad - 2, y, 12, ROW_H, () -> {
					if (!collapsed.remove(name)) {
						collapsed.add(name);
					}
				}));
				MenuKit.text(g, font, MenuKit.fit(font, MenuKit.uiBold(name), NAME_W - 12), x0 + pad + 10, y + (ROW_H - MenuKit.H_UI) / 2, nameColor);
				hits.add(new Hit(x0 + pad + 10, y, NAME_W - 10, ROW_H, () -> selectedTrack = id));
			} else {
				RewindEdits.Property prop = RewindEdits.property(id);
				VeloDraw.fillCircle(g, x0 + pad + 13f, y + ROW_H / 2f, 2.2f, sel ? trackColor(id) : (trackColor(id) & 0xFFFFFF) | 0x70000000);
				MenuKit.text(g, font, MenuKit.fit(font, MenuKit.ui(prop.label()), NAME_W - 20), x0 + pad + 20, y + (ROW_H - MenuKit.H_UI) / 2, nameColor);
				hits.add(new Hit(x0 + pad, y, NAME_W, ROW_H, () -> selectedTrack = id));
			}
		}
		g.enableScissor(left - 6, rulerY - 2, right + 6, top + panelH);
		for (int r = 0; r < rowTracks.size(); r++) {
			String id = rowTracks.get(r);
			boolean group = isGroup(id);
			java.util.TreeSet<Integer> ticks = rowKeys(clip, id);
			RewindEdits.Track track = group ? null : clip.tracks.get(id);
			int y = rowY + r * (ROW_H + ROW_GAP) + ROW_H / 2;
			Integer prevX = null;
			for (int key : ticks) {
				int k = id.equals(dragTrack) && key == dragFrom ? dragTo : key;
				int x = xOf(k);
				boolean hold = track != null && track.keys.lowerEntry(key) != null && "Hold".equals(track.keys.lowerEntry(key).getValue().interp);
				if (prevX != null && !hold) {
					g.fill(prevX + 5, y, x - 5, y + 1, (trackColor(id) & 0xFFFFFF) | 0x50000000);
				}
				prevX = x;
				int size = group ? 5 : 4;
				VeloDraw.fillQuad(g, x, y - size - 1, x + size + 1, y, x, y + size + 1, x - size - 1, y, 0xFF101015);
				VeloDraw.fillQuad(g, x, y - size, x + size, y, x, y + size, x - size, y, trackColor(id));
				if (track != null && !track.keys.get(key).label.isEmpty()) {
					MenuKit.text(g, font, MenuKit.mono(track.keys.get(key).label), x + 7, y - 3, MenuKit.TEXT_2);
				}
			}
		}
		int tracksBottom = rowY + Math.max(1, rows) * (ROW_H + ROW_GAP) - ROW_GAP;
		lastTracksBottom = tracksBottom;
		if (pos >= viewStart && pos <= viewEnd) {
			int hx = xOf(Math.min(length, pos));
			g.fill(hx, rulerY + 2, hx + 1, tracksBottom, 0xFFFF5A5F);
			VeloDraw.fillRounded(g, hx - 4, rulerY - 1, 9, 8, 3, 0xFFFF5A5F);
		}
		g.disableScissor();
		if (MenuKit.inside(mouseX, mouseY, left, rulerY - 2, span, tracksBottom - rulerY + 2)) {
			var tip = MenuKit.mono(time(tickAt(mouseX)));
			int tw = MenuKit.width(font, tip) + 10;
			VeloDraw.fillRounded(g, mouseX - tw / 2, top - 16, tw, 12, 4, 0xE0101015);
			MenuKit.text(g, font, tip, mouseX - tw / 2 + 5, top - 13, MenuKit.TEXT);
		}
	}

	// Deletes the selected clip; the last one becomes a fresh clip over its whole recording segment instead.
	private static void deleteClip(RewindPlayer p) {
		RewindEdits.Clip gone = p.edits.clips.remove(p.edits.selected);
		if (p.edits.clips.isEmpty()) {
			RewindEdits.Clip fresh = new RewindEdits.Clip();
			fresh.name = "Clip 1";
			fresh.segment = gone.segment;
			fresh.length = p.segmentLength(gone.segment);
			p.edits.clips.add(fresh);
		}
		p.selectClip(Math.max(0, Math.min(p.edits.clips.size() - 1, p.edits.selected - 1)));
	}

	private int clipMenuX;
	private int clipMenuY;

	private void drawClipMenu(GuiGraphicsExtractor g, Font font, RewindPlayer p, int mouseX, int mouseY) {
		List<Item> items = new ArrayList<>();
		for (int i = 0; i < p.edits.clips.size(); i++) {
			RewindEdits.Clip c = p.edits.clips.get(i);
			int index = i;
			items.add(new Item((i == p.edits.selected ? "● " : "   ") + c.name, time(c.length), () -> p.selectClip(index)));
		}
		items.add(new Item("+ New clip", null, p::addClip));
		items.add(new Item("Rename this clip...", null, () -> ask("Clip name", p.edits.clip().name, v -> {
			p.edits.clip().name = v.isBlank() ? p.edits.clip().name : v.trim();
			p.saveEdits();
		})));
		items.add(new Item(p.edits.clips.size() > 1 ? "Delete this clip" : "Delete this clip (starts over)", null, () -> deleteClip(p)));
		int h = items.size() * 16 + 6;
		drawDropdown(g, font, clipMenuX, clipMenuY - h - 4, items, mouseX, mouseY, false);
	}

	// ---- side panel ----

	private void drawPanel(GuiGraphicsExtractor g, Font font, RewindPlayer p, int mouseX, int mouseY) {
		int x = width - MARGIN - PANEL_W;
		int y = BAR_H + MARGIN;
		int content = switch (tab) {
			case 0 -> 30 + 12 + 5 * 26;
			case 1 -> 30 + 12 + 22 + 7 * 26 + 24;
			case 2 -> selection == null ? 30 + 14 : selection.entity() instanceof Player ? 30 + 190 : 30 + 90;
			default -> 30 + 12 + 4 * 16 + 12 + 3 * 16 + 12 + 22 + 26;
		};
		int h = height - y - MARGIN; // full height, slim
		glass(g, x, y, PANEL_W, h);
		// tabs
		int tx = x + 6;
		int tw = (PANEL_W - 12) / TABS.length;
		VeloDraw.fillRounded(g, tx, y + 6, PANEL_W - 12, 16, 6, 0x10FFFFFF);
		for (int i = 0; i < TABS.length; i++) {
			boolean on = tab == i;
			float hv = MenuKit.hover("rw-tab-" + i, MenuKit.inside(mouseX, mouseY, tx + i * tw, y + 6, tw, 16));
			if (on || hv > 0.01f) {
				VeloDraw.fillRounded(g, tx + i * tw + 1, y + 7, tw - 2, 14, 5, on ? 0xFF2C2C34 : (Math.round(0x14 * hv) << 24) | 0xFFFFFF);
			}
			tabIcon(g, i, tx + i * tw + tw / 2f, y + 14f, on ? MenuKit.TEXT : MenuKit.TEXT_2);
			if (hv > 0.5f) {
				tabTip = TABS[i];
				tabTipX = tx + i * tw + tw / 2;
				tabTipY = y + 24;
			}
			int index = i;
			hits.add(new Hit(tx + i * tw, y + 6, tw, 16, () -> tab = index));
		}
		int cy = y + 30;
		// the open tab's name as a small title
		MenuKit.text(g, font, MenuKit.uiBold(TABS[tab]), x + 10, cy, MenuKit.TEXT);
		cy += 14;
		skinBox.visible = false;
		switch (tab) {
			case 0 -> {
				cy = section(g, font, x, cy, "Clip value or keyframes");
				for (String id : new String[] {"time", "rain", "thunder", "fog", "speed"}) {
					cy = propertyRow(g, font, p, x, cy, id, mouseX, mouseY);
				}
				cy += 6;
				cy = section(g, font, x, cy, "Background (behind players / entities)");
				RewindEdits.Clip clip = p.edits.clip();
				String current = clip.background == null ? "World" : clip.background;
				for (String bg : RewindEdits.BACKGROUNDS) {
					cy = choice(g, font, x, cy, bg, bg.equals(current), mouseX, mouseY, () -> {
						clip.background = "World".equals(bg) ? null : bg;
						p.saveEdits();
					});
				}
			}
			case 1 -> {
				cy = section(g, font, x, cy, "Camera path");
				cy = wideButton(g, font, x, cy, "flag", "Keyframe this view", true, mouseX, mouseY, () -> keyCamera(p)) + 2;
				for (String id : new String[] {"cam_x", "cam_y", "cam_z", "cam_yaw", "cam_pitch", "cam_roll", "fov"}) {
					cy = propertyRow(g, font, p, x, cy, id, mouseX, mouseY);
				}
				cy += 4;
				wideButton(g, font, x, cy, RewindApply.previewCamera ? "eye" : "eye", RewindApply.previewCamera ? "Paused: path" : "Paused: free fly",
						false, mouseX, mouseY, () -> RewindApply.previewCamera = !RewindApply.previewCamera);
			}
			case 2 -> drawSelection(g, font, p, x, cy, mouseX, mouseY);
			case 3 -> drawAudio(g, font, p, x, cy, y + h, mouseX, mouseY);
			default -> drawOutput(g, font, x, cy, mouseX, mouseY);
		}
	}

	// Hand-drawn line icons (the nav set has no camera): globe, camera, pointer, export.
	private static void tabIcon(GuiGraphicsExtractor g, int which, float cx, float cy, int c) {
		float t = 1.1f;
		switch (which) {
			case 0 -> { // globe: outline, equator, a meridian ellipse
				VeloDraw.strokeArc(g, cx, cy, 4.8f, t, 0, 360, c);
				VeloDraw.line(g, cx - 4.8f, cy, cx + 4.8f, cy, 0.9f, c);
				float px = cx;
				float py = cy - 4.8f;
				for (int k = 1; k <= 24; k++) {
					double a = Math.PI * 2 * k / 24.0;
					float nx = cx + (float) Math.sin(a) * 2.1f;
					float ny = cy - (float) Math.cos(a) * 4.8f;
					VeloDraw.line(g, px, py, nx, ny, 0.9f, c);
					px = nx;
					py = ny;
				}
			}
			case 1 -> { // camera: body, lens, viewfinder bump
				VeloDraw.strokeRounded(g, cx - 5.5f, cy - 3.2f, 11f, 7.6f, 1.8f, t, c);
				VeloDraw.strokeArc(g, cx, cy + 0.6f, 2.1f, t, 0, 360, c);
				VeloDraw.fillRounded(g, cx - 2.6f, cy - 4.8f, 3.6f, 1.8f, 0.6f, c);
			}
			case 2 -> { // pointer arrow
				VeloDraw.fillQuad(g, cx - 3.5f, cy - 5f, cx + 3.8f, cy + 0.8f, cx + 0.3f, cy + 1.2f, cx - 3.5f, cy - 5f, c);
				VeloDraw.fillQuad(g, cx - 3.5f, cy - 5f, cx + 0.3f, cy + 1.2f, cx - 1.6f, cy + 4.2f, cx - 3.5f, cy - 5f, c);
				VeloDraw.line(g, cx + 0.2f, cy + 1.0f, cx + 2.4f, cy + 4.8f, 1.2f, c);
			}
			case 3 -> { // speaker: box + cone, two sound waves (arcs opening to the right)
				VeloDraw.fillRounded(g, cx - 5f, cy - 1.6f, 2.4f, 3.2f, 0.5f, c);
				VeloDraw.fillQuad(g, cx - 2.8f, cy - 1.6f, cx + 0.4f, cy - 4.2f, cx + 0.4f, cy + 4.2f, cx - 2.8f, cy + 1.6f, c);
				for (float r : new float[] {2.4f, 4.6f}) {
					float px = 0;
					float py = 0;
					for (int k = 0; k <= 8; k++) {
						double a = Math.toRadians(-48 + 96 * k / 8.0);
						float nx = cx + 1.4f + (float) Math.cos(a) * r;
						float ny = cy + (float) Math.sin(a) * r;
						if (k > 0) {
							VeloDraw.line(g, px, py, nx, ny, 1.1f, c);
						}
						px = nx;
						py = ny;
					}
				}
			}
			default -> { // export: tray + arrow up
				VeloDraw.line(g, cx, cy - 5f, cx, cy + 1.5f, t, c);
				VeloDraw.line(g, cx, cy - 5f, cx - 2.6f, cy - 2.4f, t, c);
				VeloDraw.line(g, cx, cy - 5f, cx + 2.6f, cy - 2.4f, t, c);
				VeloDraw.line(g, cx - 5f, cy + 1f, cx - 5f, cy + 4.6f, t, c);
				VeloDraw.line(g, cx + 5f, cy + 1f, cx + 5f, cy + 4.6f, t, c);
				VeloDraw.line(g, cx - 5f, cy + 4.6f, cx + 5f, cy + 4.6f, t, c);
			}
		}
	}

	// ---- Audio tab: every source's volume (a clip value or keyframes), each player's voice; scrolls ----

	private static int audioScroll;
	private int audioTop;
	private int audioBottom;
	private int audioContent;

	private void drawAudio(GuiGraphicsExtractor g, Font font, RewindPlayer p, int x, int top, int bottom, int mouseX, int mouseY) {
		audioTop = top;
		audioBottom = bottom - 6;
		int view = audioBottom - top;
		audioScroll = Math.max(0, Math.min(audioScroll, Math.max(0, audioContent - view)));
		int firstHit = hits.size();
		g.enableScissor(x, top, x + PANEL_W, audioBottom);
		int y = top - audioScroll;
		y = section(g, font, x, y, "Volumes - clip value or keyframes");
		for (RewindEdits.Property prop : RewindEdits.PROPERTIES) {
			if (prop.group().equals("Audio")) {
				y = propertyRow(g, font, p, x, y, prop.id(), mouseX, mouseY);
			}
		}
		java.util.List<String> voices = RewindSound.voiceTracks();
		y += 6;
		y = section(g, font, x, y, voices.isEmpty() ? "No voice chat in this recording" : "Voices");
		for (String uuid : voices) {
			y = propertyRow(g, font, p, x, y, "voice:" + uuid, mouseX, mouseY);
		}
		y += 6;
		String audio = RewindSound.status();
		if (audio != null) {
			MenuKit.text(g, font, MenuKit.fit(font, MenuKit.ui(audio), PANEL_W - 20), x + 10, y, MenuKit.TEXT_3);
			y += 12;
		}
		audioContent = y + audioScroll - top;
		g.disableScissor();
		// rows scrolled out of the panel can't be clicked
		for (int i = hits.size() - 1; i >= firstHit; i--) {
			Hit hit = hits.get(i);
			if (hit.y() + hit.h() <= top || hit.y() >= audioBottom) {
				hits.remove(i);
			}
		}
		if (audioContent > view) {
			int barH = Math.max(16, view * view / audioContent);
			int barY = top + (view - barH) * audioScroll / Math.max(1, audioContent - view);
			VeloDraw.fillRounded(g, x + PANEL_W - 5, barY, 2, barH, 1, 0x40FFFFFF);
		}
	}

	private int section(GuiGraphicsExtractor g, Font font, int x, int y, String text) {
		MenuKit.text(g, font, MenuKit.fit(font, MenuKit.ui(text), PANEL_W - 20), x + 10, y, MenuKit.TEXT_3);
		return y + 13;
	}

	// One property: name, its value now, a slider (drag), "Clip" (value for the whole clip) and "◆" (keyframe here).
	private int propertyRow(GuiGraphicsExtractor g, Font font, RewindPlayer p, int x, int y, String id, int mouseX, int mouseY) {
		RewindEdits.Property prop = RewindEdits.property(id);
		RewindEdits.Clip clip = p.edits.clip();
		RewindEdits.Track track = clip.tracks.get(id);
		double t = scrubbing ? RewindApply.previewTick : p.clipPosition();
		Double v = clip.value(id, t);
		double value = v != null ? v : currentValue(id, prop);
		boolean sel = id.equals(selectedTrack);
		int h = 22;
		int rx = x + 4;
		int rw = PANEL_W - 8;
		float hv = MenuKit.hover("rw-prop-" + id, MenuKit.inside(mouseX, mouseY, rx, y, rw, h));
		if (sel || hv > 0.01f) {
			VeloDraw.fillRounded(g, rx, y, rw, h, 5, sel ? 0x14FFFFFF : (Math.round(0x0A * hv) << 24) | 0xFFFFFF);
		}
		hits.add(new Hit(rx, y, rw - 70, 11, () -> selectedTrack = id));
		VeloDraw.fillCircle(g, x + 10f, y + 6f, 2f, track != null && track.active() ? trackColor(id) : 0x40FFFFFF);
		MenuKit.text(g, font, MenuKit.fit(font, MenuKit.ui(prop.label()), 62), x + 16, y + 2, MenuKit.TEXT);
		// value (click to type), then the two small toggles at the right
		boolean global = track != null && track.global != null;
		int bx = x + PANEL_W - 8;
		bx = tinyToggle(g, font, bx, y + 1, "◆", track != null && track.keys.containsKey(p.clipPosition()), mouseX, mouseY, () -> addKey(p, id, value)) - 3;
		bx = tinyToggle(g, font, bx, y + 1, "Clip", global, mouseX, mouseY, () -> {
			RewindEdits.Track tr = clip.track(id);
			tr.global = tr.global != null ? null : value;
			p.saveEdits();
		}) - 4;
		var vt = MenuKit.mono(format(id, value) + prop.unit());
		int vw = MenuKit.width(font, vt);
		MenuKit.text(g, font, vt, bx - vw, y + 3, v != null ? MenuKit.TEXT : MenuKit.TEXT_3);
		hits.add(new Hit(bx - vw - 2, y, vw + 4, 11, () -> ask(prop.label(), format(id, value), s -> setNumber(p, id, s))));
		// slider
		int sx = x + 10;
		int sw = PANEL_W - 20;
		int syy = y + 16;
		g.fill(sx, syy, sx + sw, syy + 2, 0x20FFFFFF);
		double f = (value - prop.min()) / (prop.max() - prop.min());
		if (id.equals("cam_x") || id.equals("cam_z") || id.equals("cam_y")) {
			f = 0.5; // world coordinates: the slider nudges around the current value
		}
		int kx = sx + (int) Math.round(Math.max(0, Math.min(1, f)) * sw);
		g.fill(sx, syy, kx, syy + 2, (trackColor(id) & 0xFFFFFF) | 0xA0000000);
		VeloDraw.fillCircle(g, kx + 0.5f, syy + 1f, 3f, 0xFFF4F4F6);
		sliders.add(new Object[] {sx, syy - 4, sw, id, value});
		return y + h + 4;
	}

	private int tinyToggle(GuiGraphicsExtractor g, Font font, int right, int y, String text, boolean on, int mouseX, int mouseY, Runnable action) {
		var t = MenuKit.mono(text);
		int w = text.equals("◆") ? 14 : Math.max(12, MenuKit.width(font, t) + 6);
		int x = right - w;
		float hv = MenuKit.hover("rw-tt-" + text + x + y, MenuKit.inside(mouseX, mouseY, x, y, w, 10));
		VeloDraw.fillRounded(g, x, y, w, 10, 3, on ? (MenuKit.accent() & 0xFFFFFF) | 0xD0000000 : VeloAnim.lerpArgb(0x12FFFFFF, 0x2AFFFFFF, hv));
		if (text.equals("◆")) {
			float cx = x + w / 2f;
			float cyy = y + 5f;
			VeloDraw.fillQuad(g, cx, cyy - 3.5f, cx + 3.5f, cyy, cx, cyy + 3.5f, cx - 3.5f, cyy, on ? MenuKit.onAccent() : MenuKit.TEXT_2);
		} else {
			MenuKit.text(g, font, t, x + (w - MenuKit.width(font, t)) / 2, y + 2, on ? MenuKit.onAccent() : MenuKit.TEXT_2);
		}
		hits.add(new Hit(x, y, w, 10, action));
		return x;
	}

	private static String format(String id, double v) {
		if (id.equals("time")) {
			int t = (int) Math.floorMod(Math.round(v), 24000);
			int hours = (t / 1000 + 6) % 24;
			int minutes = (int) (t % 1000 * 60 / 1000.0);
			return String.format("%02d:%02d", hours, minutes);
		}
		return String.format(java.util.Locale.ROOT, id.startsWith("cam_") ? "%.2f" : "%.2f", v).replaceAll("\\.?0+$", "");
	}

	private static double currentValue(String id, RewindEdits.Property prop) {
		Minecraft mc = Minecraft.getInstance();
		var cam = mc.gameRenderer.mainCamera();
		return switch (id) {
			case "cam_x" -> cam.position().x;
			case "cam_y" -> cam.position().y;
			case "cam_z" -> cam.position().z;
			case "cam_yaw" -> cam.yRot();
			case "cam_pitch" -> cam.xRot();
			case "fov" -> mc.options.fov().get();
			case "rain" -> mc.level == null ? 0 : mc.level.getRainLevel(1f);
			case "thunder" -> mc.level == null ? 0 : mc.level.getThunderLevel(1f);
			case "speed" -> RewindPlayer.current() == null ? 1 : RewindPlayer.current().speed();
			default -> prop.def();
		};
	}

	private void smallToggle(GuiGraphicsExtractor g, Font font, int x, int y, String text, boolean on, int mouseX, int mouseY, Runnable action) {
		var t = MenuKit.ui(text);
		int w = Math.max(20, MenuKit.width(font, t) + 8);
		float hv = MenuKit.hover("rw-st-" + text + x + y, MenuKit.inside(mouseX, mouseY, x, y, w, 12));
		VeloDraw.fillRounded(g, x, y, w, 12, 4, on ? (MenuKit.accent() & 0xFFFFFF) | 0xD0000000 : VeloAnim.lerpArgb(0x14FFFFFF, 0x2EFFFFFF, hv));
		MenuKit.text(g, font, t, x + (w - MenuKit.width(font, t)) / 2, y + 2, on ? MenuKit.onAccent() : MenuKit.TEXT_2);
		hits.add(new Hit(x, y, w, 12, action));
	}

	private int wideButton(GuiGraphicsExtractor g, Font font, int x, int y, String icon, String text, boolean primary, int mouseX, int mouseY, Runnable action) {
		int bx = x + 6;
		int bw = PANEL_W - 12;
		float hv = MenuKit.hover("rw-wb-" + text, MenuKit.inside(mouseX, mouseY, bx, y, bw, 18));
		VeloDraw.fillRounded(g, bx, y, bw, 18, 6, primary ? VeloAnim.lerpArgb((MenuKit.accent() & 0xFFFFFF) | 0xD0000000, MenuKit.accent(), hv)
				: VeloAnim.lerpArgb(0x14FFFFFF, 0x2AFFFFFF, hv));
		int c = primary ? MenuKit.onAccent() : MenuKit.TEXT;
		var t = MenuKit.fit(font, MenuKit.uiBold(text), bw - 30);
		int tw = 14 + MenuKit.width(font, t);
		MenuKit.icon(g, icon, bx + (bw - tw) / 2, y + 5, 8, c);
		MenuKit.text(g, font, t, bx + (bw - tw) / 2 + 14, y + (18 - MenuKit.H_UI) / 2, c);
		hits.add(new Hit(bx, y, bw, 18, action));
		return y + 22;
	}

	private void drawSelection(GuiGraphicsExtractor g, Font font, RewindPlayer p, int x, int y, int mouseX, int mouseY) {
		if (selection == null) {
			section(g, font, x, y, "Click an entity or block in the world");
			return;
		}
		RewindEdits.Clip clip = p.edits.clip();
		if (selection.entity() != null) {
			Entity e = selection.entity();
			String uuid = e.getUUID().toString();
			MenuKit.text(g, font, MenuKit.fit(font, MenuKit.uiBold(e.getName().getString()), PANEL_W - 20), x + 10, y, MenuKit.TEXT);
			y += 14;
			MenuKit.text(g, font, MenuKit.mono(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()), x + 12, y, MenuKit.TEXT_3);
			y += 10;
			MenuKit.text(g, font, MenuKit.mono(String.format(java.util.Locale.ROOT, "%.1f  %.1f  %.1f", e.getX(), e.getY(), e.getZ())), x + 12, y, MenuKit.TEXT_3);
			y += 16;
			boolean hidden = clip.hidden.contains(uuid);
			y = wideButton(g, font, x, y, "eye", hidden ? "Show in this clip" : "Hide in this clip", false, mouseX, mouseY, () -> {
				if (!clip.hidden.remove(uuid)) {
					clip.hidden.add(uuid);
				}
				p.saveEdits();
			});
			boolean following = e.getUUID().equals(RewindApply.followTarget);
			y = wideButton(g, font, x, y, "eye", following ? "Stop following" : "Camera follows it", following, mouseX, mouseY,
					() -> RewindApply.follow(following ? null : e.getUUID(), null));
			if (following) {
				int bw3 = (PANEL_W - 28) / 3;
				String[][] modes = {{"behind", "Behind"}, {"orbit", "Orbit"}, {"fixed", "Fixed"}};
				for (int i = 0; i < 3; i++) {
					String mode = modes[i][0];
					smallToggle2(g, font, x + 10 + i * (bw3 + 4), y, bw3, modes[i][1], mode.equals(RewindApply.followMode), mouseX, mouseY,
							() -> RewindApply.follow(e.getUUID(), mode));
				}
				y += 22;
			}
			y = wideButton(g, font, x, y, "teleport", "Camera to it", false, mouseX, mouseY, () -> {
				var mc = Minecraft.getInstance();
				Vec3 at = e.position().add(0, e.getBbHeight() + 1.5, 3);
				mc.player.snapTo(at.x, at.y, at.z, 180, 25);
			});
			if (e instanceof Player) {
				y = partToggles(g, font, p, clip, uuid, x, y + 2, mouseX, mouseY);
			}
			if (e instanceof Player && RewindSound.voiceTracks().contains(uuid)) {
				y += 2;
				y = propertyRow(g, font, p, x, y, "voice:" + uuid, mouseX, mouseY);
			}
			if (e instanceof Player) {
				y += 4;
				section(g, font, x, y, "Skin in this clip (name or PNG path)");
				y += 14;
				skinBox.visible = true;
				skinBox.setY(y);
				skinBox.setX(x + 12);
				VeloDraw.fillRounded(g, x + 10, y - 3, PANEL_W - 20, 20, 6, 0x10FFFFFF);
				y += 22;
				String current = clip.skins.get(uuid);
				int bw = (PANEL_W - 26) / 2;
				smallButton(g, font, x + 10, y, bw, "Apply", mouseX, mouseY, () -> {
					String v = skinBox.getValue().trim();
					if (!v.isEmpty()) {
						RewindApply.forgetSkin(v);
						clip.skins.put(uuid, v);
						p.saveEdits();
					}
				});
				smallButton(g, font, x + 16 + bw, y, bw, "Choose PNG...", mouseX, mouseY, () -> {
					String file = RewindCompat.openPng();
					if (file != null) {
						skinBox.setValue(file);
						RewindApply.forgetSkin(file);
						clip.skins.put(uuid, file);
						p.saveEdits();
					}
				});
				y += 22;
				if (current != null) {
					String status = RewindApply.skinStatus(current);
					MenuKit.text(g, font, MenuKit.fit(font, MenuKit.ui(status == null ? "Using: " + current : status), PANEL_W - 24), x + 12, y,
							status == null ? MenuKit.TEXT_2 : MenuKit.RED_TEXT);
					y += 12;
					smallButton(g, font, x + 10, y, PANEL_W - 20, "Own skin again", mouseX, mouseY, () -> {
						clip.skins.remove(uuid);
						p.saveEdits();
					});
				}
			}
		} else {
			MenuKit.text(g, font, MenuKit.fit(font, MenuKit.uiBold(selection.blockName()), PANEL_W - 20), x + 10, y, MenuKit.TEXT);
			y += 14;
			var b = selection.block();
			MenuKit.text(g, font, MenuKit.mono(b.getX() + "  " + b.getY() + "  " + b.getZ()), x + 12, y, MenuKit.TEXT_3);
			y += 16;
			wideButton(g, font, x, y, "teleport", "Camera to it", false, mouseX, mouseY, () -> {
				var mc = Minecraft.getInstance();
				mc.player.snapTo(b.getX() + 0.5, b.getY() + 3, b.getZ() + 4, 180, 30);
			});
		}
	}

	private static final String[][] PARTS = {{"cape", "Cloak"}, {"cosmetics", "Cosmetics"}, {"pets", "Pets"}, {"nametag", "Name tag"}, {"glow", "Glow"}};

	// What of this player shows in the clip: cloak, cosmetics (wings, bodywear, aura), pets, name tag, glow.
	private int partToggles(GuiGraphicsExtractor g, Font font, RewindPlayer p, RewindEdits.Clip clip, String uuid, int x, int y, int mouseX, int mouseY) {
		section(g, font, x, y, "Show in this clip");
		y += 13;
		int cx = x + 10;
		for (String[] part : PARTS) {
			var parts = clip.hideParts.computeIfAbsent(uuid, k -> new ArrayList<>());
			boolean shown = !parts.contains(part[0]);
			var t = MenuKit.ui(part[1]);
			int w = MenuKit.width(font, t) + 10;
			if (cx + w > x + PANEL_W - 8) {
				cx = x + 10;
				y += 15;
			}
			float hv = MenuKit.hover("rw-part-" + part[0], MenuKit.inside(mouseX, mouseY, cx, y, w, 12));
			VeloDraw.fillRounded(g, cx, y, w, 12, 4, shown ? (MenuKit.accent() & 0xFFFFFF) | 0xC0000000 : VeloAnim.lerpArgb(0x14FFFFFF, 0x2AFFFFFF, hv));
			MenuKit.text(g, font, t, cx + 5, y + 2, shown ? MenuKit.onAccent() : MenuKit.TEXT_3);
			hits.add(new Hit(cx, y, w, 12, () -> {
				if (!parts.remove(part[0])) {
					parts.add(part[0]);
				}
				if (parts.isEmpty()) {
					clip.hideParts.remove(uuid);
				}
				p.saveEdits();
			}));
			cx += w + 4;
		}
		return y + 18;
	}

	// ---- Players menu: everyone in the recording, search, hide / show one or all ----

	private net.minecraft.client.gui.components.EditBox playerSearch;
	private int playersScroll;
	private int playersX;
	private int playersBottom;

	private record Person(String uuid, String name, Entity entity, net.minecraft.world.entity.player.PlayerSkin skin) {
	}

	private static List<Person> people() {
		Minecraft mc = Minecraft.getInstance();
		Map<String, Person> out = new java.util.LinkedHashMap<>();
		String self = mc.player == null ? "" : mc.player.getUUID().toString();
		java.util.Map<String, Entity> inWorld = new java.util.HashMap<>();
		if (mc.level != null) {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof Player) {
					inWorld.put(e.getUUID().toString(), e);
				}
			}
		}
		if (mc.getConnection() != null) {
			for (var info : mc.getConnection().getOnlinePlayers()) {
				String id = info.getProfile().id().toString();
				if (!id.equals(self)) {
					out.put(id, new Person(id, info.getProfile().name(), inWorld.get(id), info.getSkin()));
				}
			}
		}
		for (var e : inWorld.entrySet()) {
			if (!e.getKey().equals(self) && !out.containsKey(e.getKey()) && e.getValue() instanceof net.minecraft.client.player.AbstractClientPlayer ap) {
				out.put(e.getKey(), new Person(e.getKey(), ap.getGameProfile().name(), ap, ap.getSkin()));
			}
		}
		List<Person> list = new ArrayList<>(out.values());
		list.sort((a, b) -> (a.entity() == null ? 1 : 0) - (b.entity() == null ? 1 : 0) != 0 ? (a.entity() == null ? 1 : -1)
				: a.name().compareToIgnoreCase(b.name()));
		return list;
	}

	private void drawPlayers(GuiGraphicsExtractor g, Font font, RewindPlayer p, int x, int y, int mouseX, int mouseY) {
		RewindEdits.Clip clip = p.edits.clip();
		String q = playerSearch.getValue().trim().toLowerCase(java.util.Locale.ROOT);
		List<Person> all = people();
		List<Person> list = new ArrayList<>();
		for (Person person : all) {
			if (q.isEmpty() || person.name().toLowerCase(java.util.Locale.ROOT).contains(q)) {
				list.add(person);
			}
		}
		int w = 210;
		int rowH = 18;
		int rows = Math.max(1, Math.min(list.size(), Math.max(4, (height - y - 80) / rowH)));
		playersScroll = Math.max(0, Math.min(playersScroll, Math.max(0, list.size() - rows)));
		int h = 8 + 16 + 6 + 16 + 6 + rows * rowH + 6;
		playersX = x;
		playersBottom = y + h;
		VeloDraw.shadow(g, x, y, w, h, 10, 12, 3, 0x55000000);
		VeloDraw.fillRounded(g, x, y, w, h, 8, 0xF0141418);
		VeloDraw.strokeRounded(g, x, y, w, h, 8, 0x26FFFFFF);
		int firstHit = hits.size();
		// search
		playerSearch.visible = true;
		playerSearch.setX(x + 8);
		playerSearch.setY(y + 9);
		playerSearch.setWidth(w - 16);
		VeloDraw.fillRounded(g, x + 6, y + 6, w - 12, 18, 6, 0x14FFFFFF);
		// all at once
		int by = y + 30;
		int bw = (w - 20) / 2;
		smallButton(g, font, x + 8, by, bw, "Hide all" + (q.isEmpty() ? "" : " found"), mouseX, mouseY, () -> {
			for (Person person : list) {
				if (!clip.hidden.contains(person.uuid())) {
					clip.hidden.add(person.uuid());
				}
			}
			p.saveEdits();
		});
		smallButton(g, font, x + 12 + bw, by, bw, "Show all" + (q.isEmpty() ? "" : " found"), mouseX, mouseY, () -> {
			for (Person person : list) {
				clip.hidden.remove(person.uuid());
			}
			p.saveEdits();
		});
		int ry = by + 22;
		if (list.isEmpty()) {
			MenuKit.text(g, font, MenuKit.ui(all.isEmpty() ? "No players in this recording" : "No one called that"), x + 10, ry + 4, MenuKit.TEXT_3);
		}
		for (int i = playersScroll; i < Math.min(list.size(), playersScroll + rows); i++) {
			Person person = list.get(i);
			boolean hidden = clip.hidden.contains(person.uuid());
			boolean sel = person.entity() != null && person.entity() == selectedEntity();
			float hv = MenuKit.hover("rw-pl-" + person.uuid(), MenuKit.inside(mouseX, mouseY, x + 4, ry, w - 8, rowH));
			if (sel || hv > 0.01f) {
				VeloDraw.fillRounded(g, x + 4, ry, w - 8, rowH, 5, sel ? 0x1EFFFFFF : (Math.round(0x12 * hv) << 24) | 0xFFFFFF);
			}
			if (person.skin() != null) {
				net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, person.skin(), x + 9, ry + 4, 10);
			}
			int nameColor = hidden ? MenuKit.TEXT_3 : person.entity() == null ? MenuKit.TEXT_2 : MenuKit.TEXT;
			MenuKit.text(g, font, MenuKit.fit(font, MenuKit.ui(person.name()), w - 80), x + 24, ry + (rowH - MenuKit.H_UI) / 2, nameColor);
			if (person.entity() == null) {
				var far = MenuKit.small("not nearby");
				MenuKit.text(g, font, far, x + w - 34 - MenuKit.width(font, far), ry + 5, MenuKit.TEXT_3);
			}
			// eye toggle
			int ex = x + w - 26;
			float eh = MenuKit.hover("rw-pl-eye-" + person.uuid(), MenuKit.inside(mouseX, mouseY, ex, ry + 1, 20, rowH - 2));
			VeloDraw.fillRounded(g, ex, ry + 2, 20, rowH - 4, 5, hidden ? 0x40FF4D57 : VeloAnim.lerpArgb(0x10FFFFFF, 0x26FFFFFF, eh));
			MenuKit.icon(g, "eye", ex + 6, ry + 5, 8, hidden ? 0xFFFF8A90 : MenuKit.TEXT);
			if (hidden) {
				VeloDraw.line(g, ex + 5, ry + 13, ex + 15, ry + 5, 1.2f, 0xFFFF8A90);
			}
			hits.add(new Hit(ex, ry + 1, 20, rowH - 2, () -> {
				if (!clip.hidden.remove(person.uuid())) {
					clip.hidden.add(person.uuid());
				}
				p.saveEdits();
			}));
			// the row: select them (and look at them)
			hits.add(new Hit(x + 4, ry, w - 34, rowH, () -> {
				if (person.entity() != null) {
					// select them and fly the camera right to them (a little behind and above, looking at them)
					selection = new Selection(person.entity(), null, null);
					tab = 2;
					Entity e = person.entity();
					Vec3 at = e.position().add(0, e.getBbHeight() + 1.2, 3.5);
					Minecraft.getInstance().player.snapTo(at.x, at.y, at.z, 180, 25);
					openMenu = -1;
				}
			}));
			ry += rowH;
		}
		if (list.size() > rows) {
			int track = rows * rowH;
			int barH = Math.max(12, track * rows / list.size());
			int barY = by + 22 + (track - barH) * playersScroll / Math.max(1, list.size() - rows);
			VeloDraw.fillRounded(g, x + w - 4, barY, 2, barH, 1, 0x40FFFFFF);
		}
		// on top of everything below it (like the other menus): its clicks first, then the panel itself swallows the rest
		List<Hit> mine = new ArrayList<>(hits.subList(firstHit, hits.size()));
		hits.subList(firstHit, hits.size()).clear();
		mine.add(new Hit(x, y, w, h, () -> { }));
		hits.addAll(0, mine);
	}

	private void smallButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, String text, int mouseX, int mouseY, Runnable action) {
		float hv = MenuKit.hover("rw-sb-" + text, MenuKit.inside(mouseX, mouseY, x, y, w, 16));
		VeloDraw.fillRounded(g, x, y, w, 16, 5, VeloAnim.lerpArgb(0x14FFFFFF, 0x2AFFFFFF, hv));
		var t = MenuKit.fit(font, MenuKit.ui(text), w - 8);
		MenuKit.text(g, font, t, x + (w - MenuKit.width(font, t)) / 2, y + 4, MenuKit.TEXT);
		hits.add(new Hit(x, y, w, 16, action));
	}

	private void drawOutput(GuiGraphicsExtractor g, Font font, int x, int y, int mouseX, int mouseY) {
		y = section(g, font, x, y, "Format (the frame shows on screen)");
		for (int i = 0; i < RewindExport.FORMATS.size(); i++) {
			RewindExport.Format f = RewindExport.FORMATS.get(i);
			int index = i;
			y = choice(g, font, x, y, f.name(), formatIndex == i, mouseX, mouseY, () -> {
				formatIndex = index;
				sizeIndex = Math.min(sizeIndex, RewindExport.FORMATS.get(index).sizes().length - 1);
			});
		}
		RewindExport.Format f = RewindExport.FORMATS.get(formatIndex);
		if (f.aspect() > 0) {
			y += 6;
			y = section(g, font, x, y, "Resolution");
			for (int i = 0; i < f.sizes().length; i++) {
				int index = i;
				y = choice(g, font, x, y, f.sizes()[i][0] + " x " + f.sizes()[i][1], sizeIndex == i, mouseX, mouseY, () -> sizeIndex = index);
			}
		}
		y += 6;
		y = section(g, font, x, y, "Frame rate");
		int bw = (PANEL_W - 26) / 2;
		smallToggle2(g, font, x + 10, y, bw, "30 FPS", exportFps == 30, mouseX, mouseY, () -> exportFps = 30);
		smallToggle2(g, font, x + 16 + bw, y, bw, "60 FPS", exportFps == 60, mouseX, mouseY, () -> exportFps = 60);
		y += 26;
		y = section(g, font, x, y, "Sound");
		for (String mode : RewindExport.AUDIO_MODES) {
			y = choice(g, font, x, y, mode, mode.equals(RewindExport.audioMode), mouseX, mouseY, () -> RewindExport.audioMode = mode);
		}
		y += 4;
		y = wideButton(g, font, x, y, "share", "Export this clip", true, mouseX, mouseY, this::openExport);
		drawAiCard(g, font, x, y + 10, mouseX, mouseY);
	}

	// AI access: whether it's on, where, and the prompt to hand an AI.
	private void drawAiCard(GuiGraphicsExtractor g, Font font, int x, int y, int mouseX, int mouseY) {
		if (y + 70 > height - MARGIN) {
			return;
		}
		int port = RewindApi.port();
		int cw = PANEL_W - 16;
		VeloDraw.fillRounded(g, x + 8, y, cw, 66, 7, 0x12FFFFFF);
		VeloDraw.fillCircle(g, x + 16f, y + 10f, 3f, port > 0 ? 0xFF4ADE80 : 0x50FFFFFF);
		MenuKit.text(g, font, MenuKit.uiBold("AI access"), x + 24, y + 6, MenuKit.TEXT);
		var state = MenuKit.mono(port > 0 ? "127.0.0.1:" + port : "off");
		MenuKit.text(g, font, state, x + 8 + cw - 8 - MenuKit.width(font, state), y + 6, MenuKit.TEXT_2);
		MenuKit.text(g, font, MenuKit.ui("Let an AI edit and export replays"), x + 14, y + 20, MenuKit.TEXT_2);
		int bw = (cw - 14) / 2;
		smallToggle2(g, font, x + 13, y + 38, bw, port > 0 ? "Turn off" : "Turn on", port > 0, mouseX, mouseY,
				() -> RewindModule.setAiAccess(port <= 0));
		smallToggle2(g, font, x + 17 + bw, y + 38, bw, copiedAt > 0 && System.currentTimeMillis() - copiedAt < 1500 ? "Copied" : "Copy prompt",
				false, mouseX, mouseY, RewindEditorScreen::copyAiPrompt);
	}

	private static long copiedAt;

	private static void copyAiPrompt() {
		if (RewindApi.port() <= 0) {
			RewindModule.setAiAccess(true);
		}
		Minecraft.getInstance().keyboardHandler.setClipboard(RewindApi.prompt());
		copiedAt = System.currentTimeMillis();
	}

	private int choice(GuiGraphicsExtractor g, Font font, int x, int y, String text, boolean on, int mouseX, int mouseY, Runnable action) {
		float hv = MenuKit.hover("rw-ch-" + text, MenuKit.inside(mouseX, mouseY, x + 8, y, PANEL_W - 16, 16));
		if (on || hv > 0.01f) {
			VeloDraw.fillRounded(g, x + 8, y, PANEL_W - 16, 16, 5, on ? 0x1EFFFFFF : (Math.round(0x10 * hv) << 24) | 0xFFFFFF);
		}
		VeloDraw.fillCircle(g, x + 17f, y + 8f, 3.5f, on ? MenuKit.accent() : 0x30FFFFFF);
		MenuKit.text(g, font, MenuKit.ui(text), x + 26, y + 4, on ? MenuKit.TEXT : MenuKit.TEXT_2);
		hits.add(new Hit(x + 8, y, PANEL_W - 16, 16, action));
		return y + 18;
	}

	private void smallToggle2(GuiGraphicsExtractor g, Font font, int x, int y, int w, String text, boolean on, int mouseX, int mouseY, Runnable action) {
		float hv = MenuKit.hover("rw-t2-" + text, MenuKit.inside(mouseX, mouseY, x, y, w, 18));
		VeloDraw.fillRounded(g, x, y, w, 18, 6, on ? (MenuKit.accent() & 0xFFFFFF) | 0xD0000000 : VeloAnim.lerpArgb(0x14FFFFFF, 0x2AFFFFFF, hv));
		var t = MenuKit.ui(text);
		MenuKit.text(g, font, t, x + (w - MenuKit.width(font, t)) / 2, y + 5, on ? MenuKit.onAccent() : MenuKit.TEXT);
		hits.add(new Hit(x, y, w, 18, action));
	}

	// ---- export window ----

	private boolean exportOpen;

	private void openExport() {
		exportOpen = true;
		RewindExport.reset();
	}

	private void drawExport(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		if (!exportOpen && !showExport && RewindExport.state == RewindExport.State.IDLE) {
			return;
		}
		if (showExport) {
			exportOpen = true;
		}
		RewindPlayer p = RewindPlayer.current();
		g.fill(0, 0, width, height, 0x88000000);
		int w = 300;
		int h = 168;
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		glass(g, x, y, w, h);
		RewindExport.State st = RewindExport.state;
		MenuKit.text(g, font, MenuKit.display(st == RewindExport.State.DONE ? "Video saved" : st == RewindExport.State.FAILED ? "Export failed" : "Export video"),
				x + 16, y + 14, MenuKit.TEXT);
		RewindExport.Format f = RewindExport.FORMATS.get(formatIndex);
		boolean running = st != RewindExport.State.IDLE && RewindExport.format != null;
		RewindExport.Format shownFormat = running ? RewindExport.format : f;
		int shownSize = running ? RewindExport.sizeIndex : sizeIndex;
		String size = shownFormat.aspect() > 0 ? shownFormat.sizes()[shownSize][0] + " x " + shownFormat.sizes()[shownSize][1] : "window size";
		MenuKit.text(g, font, MenuKit.mono((p == null ? "" : p.edits.clip().name + "  ·  ") + shownFormat.name() + "  ·  " + size + "  ·  "
				+ (running ? RewindExport.fpsShown : exportFps) + " FPS"),
				x + 16, y + 30, MenuKit.TEXT_3);
		int by = y + h - 34;
		if (st == RewindExport.State.IDLE) {
			String[] lines = {"The clip plays once from its start while it records.", "Keep the game window open until it's done.",
					"Saved to velo-rewind/exports as MP4."};
			for (int i = 0; i < lines.length; i++) {
				MenuKit.text(g, font, MenuKit.ui(lines[i]), x + 16, y + 52 + i * 12, MenuKit.TEXT_2);
			}
			exportButton(g, font, x + 16, by, (w - 40) / 2, "Cancel", false, mouseX, mouseY, () -> exportOpen = false);
			exportButton(g, font, x + 24 + (w - 40) / 2, by, (w - 40) / 2, "Start", true, mouseX, mouseY,
					() -> RewindExport.start(f, sizeIndex, exportFps));
		} else {
			// progress: a glowing bar that fills, a sweep moving across it while it works
			int bx = x + 16;
			int bw = w - 32;
			int barY = y + 70;
			double prog = RewindExport.progress;
			VeloDraw.fillRounded(g, bx, barY, bw, 10, 5, 0x1AFFFFFF);
			int fill = (int) Math.round(bw * Math.max(0.02, Math.min(1, prog)));
			int color = st == RewindExport.State.FAILED ? 0xFFFF5A5F : st == RewindExport.State.DONE ? MenuKit.GREEN : MenuKit.accent();
			VeloDraw.fillRounded(g, bx, barY, fill, 10, 5, color);
			if (st == RewindExport.State.RENDERING || st == RewindExport.State.FINISHING || st == RewindExport.State.PREPARING) {
				double phase = (System.currentTimeMillis() % 1400) / 1400.0;
				int sw = 40;
				int sx = bx + (int) ((fill + sw) * phase) - sw;
				g.enableScissor(bx, barY, bx + fill, barY + 10);
				VeloDraw.fillRounded(g, sx, barY, sw, 10, 5, 0x50FFFFFF);
				g.disableScissor();
			}
			var pct = MenuKit.displaySm(Math.round(prog * 100) + "%");
			MenuKit.text(g, font, pct, bx, barY + 18, MenuKit.TEXT);
			MenuKit.text(g, font, MenuKit.fit(font, MenuKit.ui(RewindExport.message), bw - 50), bx + 50, barY + 19, MenuKit.TEXT_2);
			if (st == RewindExport.State.DONE) {
				float pop = (float) Math.min(1, (System.currentTimeMillis() % 100000) / 300.0);
				VeloDraw.fillCircle(g, x + w - 30f, y + 22f, 9f * pop, MenuKit.GREEN);
				MenuKit.icon(g, "download", x + w - 34, y + 18, 8, 0xFF101015);
				exportButton(g, font, x + 16, by, (w - 40) / 2, "Open folder", false, mouseX, mouseY,
						() -> net.veloclient.velo.client.gui.FileManagerOpener.open(RewindExport.output.getParent().toFile(), s -> { }));
				exportButton(g, font, x + 24 + (w - 40) / 2, by, (w - 40) / 2, "Done", true, mouseX, mouseY, () -> {
					RewindExport.reset();
					exportOpen = false;
				});
			} else if (st == RewindExport.State.FAILED) {
				exportButton(g, font, x + 16, by, w - 32, "Close", false, mouseX, mouseY, () -> {
					RewindExport.state = RewindExport.State.IDLE;
					exportOpen = false;
				});
			} else if (st == RewindExport.State.RENDERING) {
				exportButton(g, font, x + 16, by, w - 32, "Stop here (keeps what's rendered)", false, mouseX, mouseY, RewindExport::cancel);
			}
		}
		// the window takes every click
		List<Hit> keep = new ArrayList<>(hits.subList(0, Math.min(hits.size(), exportHits)));
		hits.clear();
		hits.addAll(exportHitList);
		exportHitList.clear();
	}

	private int exportHits;
	private final List<Hit> exportHitList = new ArrayList<>();
	// the export window's buttons by label, for the self test (it presses them like a click would)
	private final Map<String, Runnable> exportButtons = new java.util.HashMap<>();

	void testOpenExport() {
		openExport();
	}

	boolean testPress(String label) {
		Runnable r = exportButtons.get(label);
		if (r != null) {
			r.run();
		}
		return r != null;
	}

	private void exportButton(GuiGraphicsExtractor g, Font font, int x, int y, int w, String text, boolean primary, int mouseX, int mouseY, Runnable action) {
		float hv = MenuKit.hover("rw-ex-" + text, MenuKit.inside(mouseX, mouseY, x, y, w, 22));
		VeloDraw.fillRounded(g, x, y, w, 22, 7, primary ? VeloAnim.lerpArgb((MenuKit.accent() & 0xFFFFFF) | 0xD0000000, MenuKit.accent(), hv)
				: VeloAnim.lerpArgb(0x14FFFFFF, 0x2AFFFFFF, hv));
		var t = MenuKit.uiBold(text);
		MenuKit.text(g, font, t, x + (w - MenuKit.width(font, t)) / 2, y + (22 - MenuKit.H_UI) / 2, primary ? MenuKit.onAccent() : MenuKit.TEXT);
		exportHitList.add(new Hit(x, y, w, 22, action));
		exportButtons.put(text, action);
	}

	// ---- shared bits ----

	private static void glass(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		VeloDraw.shadow(g, x, y, w, h, 12, 16, 4, 0x66000000);
		VeloDraw.fillRounded(g, x, y, w, h, 12, 0xC4101015);
		VeloDraw.strokeRounded(g, x, y, w, h, 12, 0x24FFFFFF);
		g.fill(x + 12, y + 1, x + w - 12, y + 2, 0x14FFFFFF);
	}

	private void circle(GuiGraphicsExtractor g, int x, int y, int size, String icon, boolean primary, int mouseX, int mouseY, Runnable action) {
		float hv = MenuKit.hover("rw-c-" + icon + x, MenuKit.inside(mouseX, mouseY, x, y, size, size));
		int fill = primary ? VeloAnim.lerpArgb(0xFFF4F4F6, 0xFFFFFFFF, hv) : VeloAnim.lerpArgb(0x18FFFFFF, 0x30FFFFFF, hv);
		VeloDraw.fillCircle(g, x + size / 2f, y + size / 2f, size / 2f, fill);
		MenuKit.icon(g, icon, x + (size - 9) / 2, y + (size - 9) / 2, 9, primary ? 0xFF101015 : MenuKit.TEXT);
		hits.add(new Hit(x, y, size, size, action));
	}

	private int iconPill(GuiGraphicsExtractor g, Font font, int right, int y, int h, String icon, String text, boolean primary, int mouseX, int mouseY,
			Runnable action) {
		var label = MenuKit.uiBold(text);
		int w = MenuKit.width(font, label) + 28;
		int x = right - w;
		float hv = MenuKit.hover("rw-ip-" + text, MenuKit.inside(mouseX, mouseY, x, y, w, h));
		VeloDraw.fillRounded(g, x, y, w, h, 8, primary ? VeloAnim.lerpArgb((MenuKit.accent() & 0xFFFFFF) | 0xD0000000, MenuKit.accent(), hv)
				: VeloAnim.lerpArgb(0x14FFFFFF, 0x2AFFFFFF, hv));
		int c = primary ? MenuKit.onAccent() : MenuKit.TEXT;
		MenuKit.icon(g, icon, x + 9, y + (h - 8) / 2, 8, c);
		MenuKit.text(g, font, label, x + 21, y + (h - MenuKit.H_UI) / 2, c);
		hits.add(new Hit(x, y, w, h, action));
		return x;
	}

	private void drawContext(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		drawDropdown(g, font, contextX, contextY, context, mouseX, mouseY, false);
	}

	private void ask(String title, String value, java.util.function.Consumer<String> done) {
		inputTitle = title;
		inputDone = done;
		int w = 220;
		input = new net.minecraft.client.gui.components.EditBox(font, (width - w) / 2 + 12, height / 2 - 4, w - 24, 14, Component.literal(title));
		input.setMaxLength(64);
		input.setValue(value);
		addRenderableWidget(input);
		setFocused(input);
		context = null;
		openMenu = -1;
		clipMenu = false;
	}

	private void closeInput(boolean apply) {
		if (input == null) {
			return;
		}
		String v = input.getValue();
		removeWidget(input);
		input = null;
		String t = inputTitle;
		inputTitle = null;
		if (apply && inputDone != null && t != null) {
			inputDone.accept(v);
		}
	}

	private void drawInput(GuiGraphicsExtractor g, Font font) {
		int w = 220;
		int h = 56;
		int x = (width - w) / 2;
		int y = height / 2 - 26;
		glass(g, x, y, w, h);
		MenuKit.text(g, font, MenuKit.uiBold(inputTitle), x + 12, y + 8, MenuKit.TEXT);
		VeloDraw.fillRounded(g, x + 10, y + 21, w - 20, 18, 6, 0x14FFFFFF);
		VeloDraw.strokeRounded(g, x + 10, y + 21, w - 20, 18, 6, MenuKit.accent());
		MenuKit.text(g, font, MenuKit.mono("Enter to apply  ·  Esc to cancel"), x + 12, y + 44, MenuKit.TEXT_3);
	}

	private void drawHelp(GuiGraphicsExtractor g, Font font) {
		String[] lines = {"Esc  hide / show the editor (fly with WASD, Space, Shift)", playKeyName() + " / Space  play / pause    K  keyframe", "Left  Right  jump 5 seconds    [  ]  speed",
				"Drag the ruler to preview, drag keyframes to move them", "Right-click a keyframe: value, interpolation, label, delete",
				"Wheel on the timeline: zoom    Shift+wheel: move    wheel on names: scroll", "Click an entity or block in the world to select it"};
		int w = 330;
		int h = 18 + lines.length * 12;
		int x = (width - w) / 2;
		int y = height / 2 - h / 2 - 40;
		glass(g, x, y, w, h);
		for (int i = 0; i < lines.length; i++) {
			MenuKit.text(g, font, MenuKit.ui(lines[i]), x + 12, y + 9 + i * 12, MenuKit.TEXT);
		}
	}

	// ---- editing ----

	private void addKey(RewindPlayer p, String id) {
		if (id.equals("g:Camera")) {
			keyCamera(p);
			selectedTrack = id;
			return;
		}
		if (isGroup(id)) {
			for (String m : members(id)) {
				RewindEdits.Track t = p.edits.clip().tracks.get(m);
				if (t != null && t.active()) {
					addKey(p, m);
				}
			}
			selectedTrack = id;
			return;
		}
		RewindEdits.Property prop = RewindEdits.property(id);
		Double v = p.edits.clip().value(id, p.clipPosition());
		addKey(p, id, v != null ? v : currentValue(id, prop));
	}

	private void addKey(RewindPlayer p, String id, double value) {
		RewindEdits.Track t = p.edits.clip().track(id);
		RewindEdits.Key k = t.keys.get(p.clipPosition());
		if (k == null) {
			k = new RewindEdits.Key();
			t.keys.put(p.clipPosition(), k);
		}
		k.value = value;
		selectedTrack = id;
		p.saveEdits();
	}

	// The camera as it is now: x, y, z, yaw, pitch (and roll / FOV where they already have a track) at the playhead.
	private void keyCamera(RewindPlayer p) {
		var cam = Minecraft.getInstance().gameRenderer.mainCamera();
		addKey(p, "cam_x", cam.position().x);
		addKey(p, "cam_y", cam.position().y);
		addKey(p, "cam_z", cam.position().z);
		addKey(p, "cam_yaw", cam.yRot());
		addKey(p, "cam_pitch", cam.xRot());
		RewindEdits.Clip c = p.edits.clip();
		for (String id : new String[] {"cam_roll", "fov"}) {
			RewindEdits.Track t = c.tracks.get(id);
			if (t != null && t.active()) {
				addKey(p, id);
			}
		}
		selectedTrack = "g:Camera";
	}

	private void setNumber(RewindPlayer p, String id, String text) {
		try {
			double v = Double.parseDouble(text.trim().replace(',', '.'));
			if (id.equals("time") && text.contains(":")) {
				return;
			}
			addKey(p, id, v);
		} catch (NumberFormatException e) {
			if (id.equals("time") && text.contains(":")) {
				String[] parts = text.trim().split(":");
				try {
					int hh = Integer.parseInt(parts[0]);
					int mm = Integer.parseInt(parts[1]);
					addKey(p, id, Math.floorMod((hh - 6) * 1000 + mm * 1000 / 60, 24000));
				} catch (RuntimeException ignored) {
				}
			}
		}
	}

	// ---- input ----

	private Selection pick(double mx, double my) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		var cam = mc.gameRenderer.mainCamera();
		java.util.function.Supplier<Float> fovOf = cam::getFov;
		double fov = Math.toRadians(fovOf.get());
		double ndcX = (2 * mx / width - 1);
		double ndcY = (1 - 2 * my / height);
		double tanY = Math.tan(fov / 2);
		double tanX = tanY * width / (double) height;
		var f = cam.forwardVector();
		var u = cam.upVector();
		var l = cam.leftVector();
		Vec3 dir = new Vec3(f.x() - l.x() * ndcX * tanX + u.x() * ndcY * tanY, f.y() - l.y() * ndcX * tanX + u.y() * ndcY * tanY,
				f.z() - l.z() * ndcX * tanX + u.z() * ndcY * tanY).normalize();
		Vec3 from = cam.position();
		Vec3 to = from.add(dir.scale(160));
		BlockHitResult block = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE,
				net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
		double reach = block.getType() == HitResult.Type.MISS ? 160 : block.getLocation().distanceTo(from);
		var entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(mc.player, from, from.add(dir.scale(reach)),
				new net.minecraft.world.phys.AABB(from, from.add(dir.scale(reach))).inflate(1), e -> e != mc.player && !e.isSpectator(), reach * reach);
		if (entityHit != null) {
			return new Selection(entityHit.getEntity(), null, null);
		}
		if (block.getType() == HitResult.Type.BLOCK) {
			var state = mc.level.getBlockState(block.getBlockPos());
			return new Selection(null, block.getBlockPos(), state.getBlock().getName().getString());
		}
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		RewindPlayer p = RewindPlayer.current();
		if (inputTitle != null) {
			if (input != null && input.isMouseOver(mx, my)) {
				return super.mouseClicked(event, doubleClick);
			}
			closeInput(false);
			return true;
		}
		if (skinBox != null && skinBox.visible && skinBox.isMouseOver(mx, my)) {
			return super.mouseClicked(event, doubleClick);
		}
		if (playerSearch != null && playerSearch.visible && playerSearch.isMouseOver(mx, my)) {
			return super.mouseClicked(event, doubleClick);
		}
		for (Hit h : List.copyOf(hits)) {
			if (h.in(mx, my)) {
				if (event.button() == 1 && p != null && keyAt(p, mx, my) != null) {
					break; // right-click on a keyframe: its menu (below)
				}
				h.action().run();
				return true;
			}
		}
		if (context != null || clipMenu || openMenu >= 0 || showHelp) {
			context = null;
			clipMenu = false;
			openMenu = -1;
			showHelp = false;
			return true;
		}
		if (p == null) {
			return super.mouseClicked(event, doubleClick);
		}
		// value sliders in the panel
		for (int i = 0; i < sliders.size(); i++) {
			Object[] s = sliders.get(i);
			if (mx >= (int) s[0] - 4 && mx <= (int) s[0] + (int) s[2] + 4 && my >= (int) s[1] && my <= (int) s[1] + 12) {
				sliderDrag = i;
				sliderBase = (double) s[4];
				sliderStartX = mx;
				dragSlider(p, mx);
				return true;
			}
		}
		// the timeline
		String[] key = keyAt(p, mx, my);
		if (key != null) {
			if (event.button() == 1) {
				openKeyMenu(p, key[0], Integer.parseInt(key[1]), mx, my);
			} else {
				dragTrack = key[0];
				dragFrom = Integer.parseInt(key[1]);
				dragTo = dragFrom;
				selectedTrack = key[0];
			}
			return true;
		}
		if (mx >= lastLeft && mx <= lastLeft + lastSpan && my >= lastTracksTop && my <= lastTracksBottom) {
			if (event.button() == 1) {
				String track = trackAt(my);
				int at = tickAt(mx);
				List<Item> items = new ArrayList<>();
				if (track != null) {
					items.add(new Item("Add keyframe here", null, () -> {
						p.seekClip(at);
						addKey(p, track);
					}));
					items.add(new Item(isGroup(track) ? "Remove these tracks" : "Remove this track", null, () -> {
						for (String id : members(track)) {
							p.edits.clip().tracks.remove(id);
						}
						p.saveEdits();
					}));
				}
				items.add(new Item("Jump here", null, () -> p.seekClip(at)));
				context = items;
				contextX = (int) mx;
				contextY = (int) my;
				return true;
			}
			scrubbing = true;
			RewindApply.previewTick = tickAt(mx);
			return true;
		}
		// the world: select what was clicked
		boolean onUi = my < BAR_H || mx > width - MARGIN - PANEL_W - 4 || my > lastTracksTop - 40;
		if (!onUi) {
			selection = pick(mx, my);
			if (selection != null) {
				tab = 2;
				if (skinBox != null && selection.entity() != null) {
					String current = p.edits.clip().skins.get(selection.entity().getUUID().toString());
					skinBox.setValue(current == null ? "" : current);
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private double sliderBase;
	private double sliderStartX;

	private void dragSlider(RewindPlayer p, double mx) {
		Object[] s = sliders.get(sliderDrag);
		String id = (String) s[3];
		RewindEdits.Property prop = RewindEdits.property(id);
		double v;
		if (id.equals("cam_x") || id.equals("cam_y") || id.equals("cam_z")) {
			v = sliderBase + (mx - sliderStartX) * 0.1; // world coordinates: a nudge, 0.1 block per pixel
		} else {
			double f = Math.max(0, Math.min(1, (mx - (int) s[0]) / (double) (int) s[2]));
			v = prop.min() + f * (prop.max() - prop.min());
			v = Math.round(v / prop.step()) * prop.step();
		}
		RewindEdits.Clip clip = p.edits.clip();
		RewindEdits.Track t = clip.track(id);
		if (!t.keys.isEmpty()) {
			addKey(p, id, v);
		} else {
			t.global = v;
		}
		selectedTrack = id;
	}

	private String trackAt(double my) {
		for (int r = 0; r < rowTracks.size(); r++) {
			int y = lastRowsTop + r * (ROW_H + ROW_GAP);
			if (my >= y - 2 && my < y + ROW_H + 2) {
				return rowTracks.get(r);
			}
		}
		return null;
	}

	private String[] keyAt(RewindPlayer p, double mx, double my) {
		String track = trackAt(my);
		if (track == null) {
			return null;
		}
		for (int k : rowKeys(p.edits.clip(), track)) {
			if (Math.abs(xOf(k) - mx) <= 5) {
				return new String[] {track, Integer.toString(k)};
			}
		}
		return null;
	}

	private void openKeyMenu(RewindPlayer p, String track, int tick, double mx, double my) {
		if (isGroup(track)) {
			List<Item> items = new ArrayList<>();
			for (String interp : RewindEdits.INTERPOLATIONS) {
				items.add(new Item("All: " + interp, null, () -> {
					for (String id : members(track)) {
						RewindEdits.Track t = p.edits.clip().tracks.get(id);
						if (t != null && t.keys.containsKey(tick)) {
							t.keys.get(tick).interp = interp;
						}
					}
					p.saveEdits();
				}));
			}
			items.add(new Item("Jump to it", null, () -> p.seekClip(tick)));
			items.add(new Item("Delete keyframes here", null, () -> {
				for (String id : members(track)) {
					RewindEdits.Track t = p.edits.clip().tracks.get(id);
					if (t != null) {
						t.keys.remove(tick);
					}
				}
				p.saveEdits();
			}));
			context = items;
			contextX = (int) mx;
			contextY = (int) my;
			return;
		}
		RewindEdits.Track t = p.edits.clip().tracks.get(track);
		RewindEdits.Key key = t.keys.get(tick);
		RewindEdits.Property prop = RewindEdits.property(track);
		List<Item> items = new ArrayList<>();
		items.add(new Item("Value: " + format(track, key.value) + prop.unit() + "  (edit)", null,
				() -> ask(prop.label() + " at " + time(tick), format(track, key.value), s -> {
					try {
						key.value = Double.parseDouble(s.trim().replace(',', '.'));
						p.saveEdits();
					} catch (NumberFormatException ignored) {
					}
				})));
		for (String interp : RewindEdits.INTERPOLATIONS) {
			items.add(new Item((interp.equals(key.interp) ? "● " : "   ") + interp, null, () -> {
				key.interp = interp;
				p.saveEdits();
			}));
		}
		items.add(new Item("Label...", null, () -> ask("Label", key.label, s -> {
			key.label = s.trim();
			p.saveEdits();
		})));
		items.add(new Item("Jump to it", null, () -> p.seekClip(tick)));
		items.add(new Item("Delete keyframe", null, () -> {
			t.keys.remove(tick);
			p.saveEdits();
		}));
		context = items;
		contextX = (int) mx;
		contextY = (int) my;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		RewindPlayer p = RewindPlayer.current();
		if (p == null) {
			return super.mouseDragged(event, dx, dy);
		}
		if (sliderDrag >= 0 && sliderDrag < sliders.size()) {
			dragSlider(p, event.x());
			return true;
		}
		if (dragTrack != null) {
			dragTo = tickAt(event.x());
			return true;
		}
		if (scrubbing) {
			int t = tickAt(event.x());
			RewindApply.previewTick = t;
			// keep the world in step while dragging, without seeking every frame
			long now = System.currentTimeMillis();
			if (Math.abs(t - lastScrubSeek) > 4 && now - lastScrubSeekAt > 90) {
				lastScrubSeek = t;
				lastScrubSeekAt = now;
				p.seekClip(t);
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		RewindPlayer p = RewindPlayer.current();
		if (sliderDrag >= 0) {
			sliderDrag = -1;
			if (p != null) {
				p.saveEdits();
			}
			return true;
		}
		if (dragTrack != null && p != null) {
			if (dragTo != dragFrom) {
				for (String id : members(dragTrack)) {
					RewindEdits.Track t = p.edits.clip().tracks.get(id);
					if (t != null && !t.keys.containsKey(dragTo)) {
						RewindEdits.Key k = t.keys.remove(dragFrom);
						if (k != null) {
							t.keys.put(dragTo, k);
						}
					}
				}
				p.saveEdits();
			}
			dragTrack = null;
			dragFrom = dragTo = -1;
			return true;
		}
		if (scrubbing) {
			scrubbing = false;
			if (p != null && RewindApply.previewTick >= 0) {
				p.seekClip((int) Math.round(RewindApply.previewTick));
			}
			RewindApply.previewTick = -1;
			lastScrubSeek = -1;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (openMenu >= 0 && MENUS[openMenu].equals("Players") && mx >= playersX && mx < playersX + 210 && my >= BAR_H && my < playersBottom) {
			playersScroll += dy > 0 ? -2 : 2;
			return true;
		}
		if (tab == 3 && mx >= width - MARGIN - PANEL_W && my >= audioTop && my <= audioBottom) {
			audioScroll += dy > 0 ? -24 : 24;
			return true;
		}
		if (my >= lastTracksTop && my <= lastTracksBottom) {
			if (mx < lastLeft) {
				scrollRows += dy > 0 ? -1 : 1; // scroll the track list
				return true;
			}
			double visible = viewEnd - viewStart;
			if (Keys.isKeyDown(Keys.LEFT_SHIFT)) {
				double move = -dy * visible * 0.15;
				move = Math.max(-viewStart, Math.min(lastLength - viewEnd, move));
				viewStart += move;
				viewEnd += move;
			} else {
				double anchor = viewStart + (mx - lastLeft) * visible / Math.max(1, lastSpan);
				double next = Math.max(20, Math.min(lastLength, visible * (dy > 0 ? 0.8 : 1.25)));
				double f = (anchor - viewStart) / visible;
				viewStart = Math.max(0, anchor - f * next);
				viewEnd = Math.min(lastLength, viewStart + next);
				viewStart = Math.max(0, viewEnd - next);
			}
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int k = event.key();
		if (inputTitle != null) {
			if (k == Keys.ENTER || k == Keys.KP_ENTER) {
				closeInput(true);
				return true;
			}
			if (k == Keys.ESCAPE) {
				closeInput(false);
				return true;
			}
			return super.keyPressed(event);
		}
		if (skinBox != null && skinBox.isFocused() && k != Keys.ESCAPE) {
			return super.keyPressed(event);
		}
		if (playerSearch != null && playerSearch.visible && playerSearch.isFocused() && k != Keys.ESCAPE) {
			return super.keyPressed(event);
		}
		if (exportOpen && k == Keys.ESCAPE && !RewindExport.busy()) {
			exportOpen = false;
			RewindExport.reset();
			return true;
		}
		if (context != null && k == Keys.ESCAPE) {
			context = null;
			return true;
		}
		RewindPlayer p = RewindPlayer.current();
		boolean ctrl = Keys.isKeyDown(Keys.LEFT_CONTROL) || Keys.isKeyDown(Keys.RIGHT_CONTROL);
		if (p != null && ctrl && (k == Keys.Z || k == Keys.Y)) {
			boolean shift = Keys.isKeyDown(Keys.LEFT_SHIFT) || Keys.isKeyDown(Keys.RIGHT_SHIFT);
			if (k == Keys.Y || shift) {
				p.redo();
			} else {
				p.undo();
			}
			return true;
		}
		if (p != null) {
			if (k == playKey() || k == Keys.SPACE) {
				p.togglePause();
				return true;
			}
			if (k == Keys.letter('K')) {
				addKey(p, selectedTrack);
				return true;
			}
			if (k == Keys.LEFT || k == Keys.RIGHT) {
				p.seekClip(p.clipPosition() + (k == Keys.LEFT ? -100 : 100));
				return true;
			}
		}
		return super.keyPressed(event);
	}
}
*///?}
