package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.hud.HudManager;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Redstone Mode's overlay: a tool bar at the top (tools, File / Edit / View / Settings menus,
 * freeze, record, hide, exit), the active tool's settings on the right, loaded schematics on the
 * left, a video-player timeline at the bottom once something was recorded, and a small status badge
 * in the bottom right. Tap Left Alt to get the mouse and click it; every panel can be moved and
 * scaled in Edit HUD Layout while Redstone Mode is on. While the mode is on this is the only HUD
 * Velo draws.
 */
public final class RmHud {

	static final int BG = 0xD0121218;
	static final int BG_HOVER = 0xFF2A2A36;
	static final int ACCENT = 0xFFFF4D4D;
	static final int TEXT = 0xFFFFFFFF;
	static final int MUTED = 0xFF9A9AAE;

	/** A clickable area in screen coordinates. */
	record Hit(float x, float y, float w, float h, Runnable action, String tooltip) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	record MenuItem(String label, String right, Runnable action, BooleanSupplier checked) {
		static MenuItem of(String label, Runnable action) {
			return new MenuItem(label, null, action, null);
		}
	}

	private static final List<Hit> HITS = new ArrayList<>();
	private static final List<Hit> FRAME = new ArrayList<>();
	static double mouseX = -1;
	static double mouseY = -1;
	static boolean cursor;
	private static List<MenuItem> menu;
	private static float menuX;
	private static float menuY;
	static boolean draggingSlider;
	private static float sliderX;
	private static float sliderW;

	private static final List<Panel> PANELS = new ArrayList<>();
	static final Panel TOOLBAR = register(new ToolbarPanel());
	static final Panel TOOL = register(new ToolPanel());
	static final Panel SCHEMATICS = register(new SchematicsPanel());
	static final Panel TIMELINE = register(new TimelinePanel());
	static final Panel BADGE = register(new BadgePanel());

	private RmHud() {
	}

	private static Panel register(Panel panel) {
		PANELS.add(panel);
		float[] saved = RmSettings.get().panels.get(panel.id());
		if (saved != null && saved.length >= 4) {
			panel.position().set(saved[0], saved[1]);
			panel.position().setScale(saved[2]);
			panel.position().setPlaced(saved[3] > 0.5f);
		}
		return panel;
	}

	/** The panels shown right now - also what Edit HUD Layout edits while Redstone Mode is on. */
	public static List<HudModule> panels() {
		List<HudModule> out = new ArrayList<>();
		for (Panel p : PANELS) {
			if (p.shown()) {
				out.add(p);
			}
		}
		return out;
	}

	public static List<HudModule> allPanels() {
		return new ArrayList<>(PANELS);
	}

	/** Writes panel positions into the settings (after Edit HUD Layout). */
	public static void storePositions() {
		for (Panel p : PANELS) {
			HudPosition pos = p.position();
			RmSettings.get().panels.put(p.id(), new float[] {pos.xFraction(), pos.yFraction(), pos.scale(), pos.placed() ? 1 : 0});
		}
		RmSettings.save();
	}

	static Identifier icon(String name) {
		return Identifier.of("velo-client", "textures/icon/rm/" + name + ".png");
	}

	static void drawIcon(DrawContext context, String name, int x, int y, int size, int color) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, icon(name), x, y, 0f, 0f, size, size, 64, 64, 64, 64, color);
	}

	// ---- HUD pass ----

	/** Called from the HUD while Redstone Mode is on (instead of every other Velo HUD element). */
	public static void render(DrawContext context, int screenWidth, int screenHeight, float tickDelta) {
		FRAME.clear();
		hotbarIcon(context, screenWidth, screenHeight);
		RmAnalysis.renderLabels(context, screenWidth, screenHeight);
		if (!RedstoneMode.overlayHidden()) {
			for (Panel p : PANELS) {
				if (p.shown()) {
					HudManager.renderScaled(context, p, screenWidth, screenHeight, tickDelta);
				}
			}
			message(context, screenWidth, screenHeight);
			if (menu != null) {
				drawMenu(context);
			}
			if (cursor) {
				tooltip(context, screenWidth, screenHeight);
			}
		}
		synchronized (HITS) {
			HITS.clear();
			HITS.addAll(FRAME);
		}
	}

	/**
	 * The Redstone tool slot ("slot 0") right of the hotbar, built from vanilla's own hotbar sprites
	 * so it matches (and picks up Hotbar Colors). Selected, it carries the selection frame - vanilla's
	 * frame on the normal slot is hidden meanwhile (see {@code SpriteTints}).
	 */
	private static void hotbarIcon(DrawContext context, int w, int h) {
		int x = w / 2 + 91 + 6;
		int y = h - 22;
		Identifier bar = Identifier.of("minecraft", "hud/hotbar");
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, bar, 182, 22, 0, 0, x, y, 21, 22);
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, bar, 182, 22, 181, 0, x + 21, y, 1, 22);
		boolean held = RedstoneMode.toolHeld();
		drawIcon(context, RmState.tool.icon, x + 3, y + 3, 16, held ? 0xFFFFFFFF : 0xB0FFFFFF);
		if (held) {
			// Not quite -1, so the hidden-selection tint doesn't apply to this one.
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/hotbar_selection"), x - 1, y - 1, 24, 23, 0xFEFFFFFF);
		}
	}

	private static void message(DrawContext context, int w, int h) {
		String text = RmState.message();
		String job = RmJobs.status();
		String route = Wiring.progressText();
		String line = route != null ? route : job != null ? job + "  (End stops)" : text;
		if (line == null) {
			return;
		}
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		String shown = VeloUi.trim(line, Math.min(420, w - 40));
		int tw = textRenderer.getWidth(shown);
		int x = (w - tw) / 2;
		int y = h - 62;
		boolean error = route == null && job == null && RmState.messageIsError();
		VeloDraw.fillRounded(context, x - 6, y - 3, tw + 12, 14, 4, 0xC0101016);
		context.drawTextWithShadow(textRenderer, shown, x, y, error ? 0xFFFF7070 : route != null || job != null ? 0xFFFFD84A : 0xFF8FE3A8);
	}

	private static void tooltip(DrawContext context, int w, int h) {
		String tip = null;
		synchronized (HITS) {
			for (Hit hit : HITS) {
				if (hit.tooltip() != null && hit.contains(mouseX, mouseY)) {
					tip = hit.tooltip();
				}
			}
		}
		if (tip == null) {
			return;
		}
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int tw = textRenderer.getWidth(tip);
		int x = (int) Math.min(mouseX + 10, w - tw - 8);
		int y = (int) Math.min(mouseY + 12, h - 14);
		VeloDraw.fillRounded(context, x - 4, y - 3, tw + 8, 14, 3, 0xF0101016);
		context.drawTextWithShadow(textRenderer, tip, x, y, TEXT);
	}

	// ---- menus ----

	static void openMenu(float x, float y, List<MenuItem> items) {
		menu = items;
		menuX = x;
		menuY = y;
	}

	static void closeMenu() {
		menu = null;
	}

	private static void drawMenu(DrawContext context) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int width = 150;
		for (MenuItem item : menu) {
			width = Math.max(width, textRenderer.getWidth(item.label()) + (item.right() != null ? textRenderer.getWidth(item.right()) + 30 : 30));
		}
		int x = Math.round(menuX);
		int y = Math.round(menuY);
		int h = menu.size() * 14 + 6;
		VeloDraw.fillRounded(context, x, y, width, h, 5, 0xF4141419);
		VeloDraw.strokeRounded(context, x, y, width, h, 5, 0x40FFFFFF);
		int ry = y + 3;
		for (MenuItem item : menu) {
			if (item.action() == null) {
				context.fill(x + 6, ry + 6, x + width - 6, ry + 7, 0x30FFFFFF);
				ry += 14;
				continue;
			}
			boolean hover = cursor && mouseX >= x && mouseX < x + width && mouseY >= ry && mouseY < ry + 14;
			if (hover) {
				VeloDraw.fillRounded(context, x + 3, ry, width - 6, 14, 3, 0x40FF4D4D);
			}
			boolean checked = item.checked() != null && item.checked().getAsBoolean();
			if (item.checked() != null) {
				VeloDraw.fillRounded(context, x + 7, ry + 3, 8, 8, 2, checked ? ACCENT : 0x50FFFFFF);
			}
			context.drawTextWithShadow(textRenderer, item.label(), x + (item.checked() != null ? 20 : 8), ry + 3, TEXT);
			if (item.right() != null) {
				context.drawTextWithShadow(textRenderer, item.right(), x + width - 8 - textRenderer.getWidth(item.right()), ry + 3, MUTED);
			}
			Runnable action = item.action();
			boolean keepOpen = item.checked() != null;
			FRAME.add(new Hit(x, ry, width, 14, () -> {
				action.run();
				if (!keepOpen) {
					closeMenu();
				}
			}, null));
			ry += 14;
		}
	}

	// ---- input from the cursor screen ----

	/** A click in cursor mode; true when the overlay used it. */
	static boolean click(double mx, double my) {
		List<Hit> hits;
		synchronized (HITS) {
			hits = new ArrayList<>(HITS);
		}
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.contains(mx, my)) {
				if (menu != null && !isMenuHit(hit)) {
					closeMenu();
				}
				hit.action().run();
				return true;
			}
		}
		if (menu != null) {
			closeMenu();
			return true;
		}
		return false;
	}

	private static boolean isMenuHit(Hit hit) {
		return hit.x() >= menuX - 1 && hit.y() >= menuY - 1;
	}

	static void drag(double mx) {
		if (draggingSlider && sliderW > 0) {
			Timeline.seekFraction((mx - sliderX) / sliderW);
		}
	}

	static void setSlider(float x, float w) {
		sliderX = x;
		sliderW = w;
	}

	// ---- panel base ----

	/** One overlay panel: a HUD element (so Edit HUD Layout can move / scale it) that records its clickable parts. */
	abstract static class Panel extends AbstractModule implements HudModule {

		private final HudPosition position;
		protected int width = 100;
		protected int height = 20;
		private int originX;
		private int originY;
		private float scale = 1;

		Panel(String id, String name, float x, float y) {
			super(id, name, "Redstone Mode overlay", ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
			position = new HudPosition(x, y);
		}

		abstract boolean shown();

		/** Lays out and draws at local x/y (unscaled); sets {@link #width} / {@link #height}. */
		abstract void draw(DrawContext context, int x, int y);

		@Override
		public boolean isEnabled() {
			return RedstoneMode.active() && shown();
		}

		@Override
		public HudPosition position() {
			return position;
		}

		@Override
		public int width() {
			return width;
		}

		@Override
		public int height() {
			return height;
		}

		@Override
		public void render(DrawContext context, int x, int y, float tickDelta) {
			originX = x;
			originY = y;
			scale = position.scale();
			draw(context, x, y);
		}

		/** Records a click area given in this panel's local (unscaled) coordinates. */
		void hit(int x, int y, int w, int h, Runnable action, String tooltip) {
			FRAME.add(new Hit(originX + (x - originX) * scale, originY + (y - originY) * scale, w * scale, h * scale, action, tooltip));
		}

		float screenX(int localX) {
			return originX + (localX - originX) * scale;
		}

		float screenY(int localY) {
			return originY + (localY - originY) * scale;
		}

		boolean hovered(int x, int y, int w, int h) {
			if (!cursor) {
				return false;
			}
			double lx = originX + (mouseX - originX) / scale;
			double ly = originY + (mouseY - originY) / scale;
			return lx >= x && lx < x + w && ly >= y && ly < y + h;
		}

		/** An icon button. */
		void iconButton(DrawContext context, int x, int y, int size, String icon, boolean active, String tooltip, Runnable action) {
			boolean hover = hovered(x, y, size, size);
			int bg = active ? 0xFFB83030 : hover ? BG_HOVER : 0x00000000;
			if (bg != 0) {
				VeloDraw.fillRounded(context, x, y, size, size, 4, bg);
			}
			int pad = Math.max(2, size / 5);
			drawIcon(context, icon, x + pad, y + pad, size - pad * 2, active || hover ? 0xFFFFFFFF : 0xFFC8C8D4);
			hit(x, y, size, size, action, tooltip);
		}

		/** A text button; returns its width. */
		int textButton(DrawContext context, int x, int y, int w, String label, boolean primary, Runnable action) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			int width = w > 0 ? w : textRenderer.getWidth(label) + 12;
			boolean hover = hovered(x, y, width, 14);
			int bg = primary ? (hover ? 0xFFD04040 : 0xFFB83030) : hover ? 0xFF33333F : 0xFF24242E;
			VeloDraw.fillRounded(context, x, y, width, 14, 4, bg);
			context.drawTextWithShadow(textRenderer, VeloUi.trim(label, width - 6), x + (width - Math.min(textRenderer.getWidth(label), width - 6)) / 2, y + 3, TEXT);
			hit(x, y, width, 14, action, null);
			return width;
		}

		/** "Label ........ value" row; clicking the value runs {@code action}. */
		void settingRow(DrawContext context, int x, int y, int w, String label, String value, Runnable action) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			context.drawTextWithShadow(textRenderer, label, x, y + 3, MUTED);
			String shown = VeloUi.trim(value, w / 2);
			int vw = textRenderer.getWidth(shown) + 10;
			int vx = x + w - vw;
			boolean hover = hovered(vx, y, vw, 14);
			VeloDraw.fillRounded(context, vx, y, vw, 14, 4, hover ? 0xFF3A3A48 : 0xFF26262F);
			context.drawTextWithShadow(textRenderer, shown, vx + 5, y + 3, TEXT);
			hit(vx, y, vw, 14, action, null);
		}
	}

	// ---- tool bar (top) ----

	private static final class ToolbarPanel extends Panel {
		ToolbarPanel() {
			super("rm-toolbar", "Redstone: Tools", 0.5f, 0.01f);
		}

		@Override
		boolean shown() {
			return true;
		}

		@Override
		void draw(DrawContext context, int x, int y) {
			int size = 20;
			RmState.Tool[] tools = RmState.Tool.values();
			int count = tools.length + 9;
			width = count * (size + 2) + 16;
			height = size + 6;
			VeloDraw.fillRounded(context, x, y, width, height, 7, BG);
			int bx = x + 4;
			int by = y + 3;
			for (RmState.Tool tool : tools) {
				iconButton(context, bx, by, size, tool.icon, RmState.tool == tool, tool.label, () -> RedstoneMode.selectTool(tool));
				bx += size + 2;
			}
			context.fill(bx + 2, by + 3, bx + 3, by + size - 3, 0x40FFFFFF);
			bx += 8;
			int menuX = bx;
			iconButton(context, bx, by, size, "file", false, "File", () -> openMenu(screenX(menuX), screenY(y + height + 2), RmMenus.file()));
			bx += size + 2;
			int editX = bx;
			iconButton(context, bx, by, size, "undo", false, "Edit", () -> openMenu(screenX(editX), screenY(y + height + 2), RmMenus.edit()));
			bx += size + 2;
			int viewX = bx;
			iconButton(context, bx, by, size, "view", false, "View", () -> openMenu(screenX(viewX), screenY(y + height + 2), RmMenus.view()));
			bx += size + 2;
			int setX = bx;
			iconButton(context, bx, by, size, "settings", false, "Settings", () -> openMenu(screenX(setX), screenY(y + height + 2),
					RmMenus.settings()));
			bx += size + 2;
			context.fill(bx + 2, by + 3, bx + 3, by + size - 3, 0x40FFFFFF);
			bx += 8;
			iconButton(context, bx, by, size, "freeze", TickControls.frozen(), "Freeze world (F7)", TickControls::toggleFreeze);
			bx += size + 2;
			boolean recording = Timeline.phase() == Timeline.Phase.RECORDING;
			iconButton(context, bx, by, size, "record", recording || Timeline.replayOpen(),
					recording ? "Stop recording (K)" : Timeline.replayOpen() ? "Leave replay (K)" : "Record selection (K)", Timeline::toggle);
			bx += size + 2;
			iconButton(context, bx, by, size, "recordings", false, "Recordings", () -> openMenu(screenX(x + width - 60), screenY(y + height + 2),
					RmMenus.recordings()));
			bx += size + 2;
			iconButton(context, bx, by, size, "hidden", false, "Hide overlay (F9)", RedstoneMode::toggleOverlay);
			bx += size + 2;
			iconButton(context, bx, by, size, "exit", false, "Leave Redstone Mode" + RmKeys.toggleHint(), RedstoneMode::exit);
		}
	}

	// ---- tool panel (right) ----

	private static final class ToolPanel extends Panel {
		ToolPanel() {
			super("rm-tool", "Redstone: Tool Settings", 0.99f, 0.32f);
		}

		@Override
		boolean shown() {
			return true;
		}

		@Override
		void draw(DrawContext context, int x, int y) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			width = 176;
			int inner = width - 16;
			List<Runnable> deferred = new ArrayList<>();
			int cy = y + 8;
			// Header
			RmState.Tool tool = RmState.tool;
			String title = RmState.pasting ? "Paste" : Placements.following() != null ? "Place Schematic" : tool.label;
			String iconName = RmState.pasting ? "paste" : Placements.following() != null ? "layers" : tool.icon;
			int top = y;
			int[] heightHolder = {0};
			// Background is drawn after measuring: draw content into a list of operations first.
			List<Runnable> draw = new ArrayList<>();
			draw.add(() -> drawIcon(context, iconName, x + 8, top + 7, 14, ACCENT));
			draw.add(() -> context.drawTextWithShadow(textRenderer, title, x + 26, top + 10, TEXT));
			cy += 18;
			int hintY = cy;
			List<String> hint = VeloUi.wrap(hintText(tool), inner);
			for (int i = 0; i < hint.size(); i++) {
				String line = hint.get(i);
				int ly = hintY + i * 10;
				draw.add(() -> context.drawTextWithShadow(textRenderer, line, x + 8, ly, MUTED));
			}
			cy += hint.size() * 10 + 6;
			if (!RedstoneMode.toolHeld()) {
				int ly = cy;
				draw.add(() -> context.drawTextWithShadow(textRenderer, VeloUi.trim("Press 0 or scroll past the hotbar for the tool slot"
						+ " to use it", inner), x + 8, ly, 0xFFFFD84A));
				cy += 14;
			}
			cy = RmToolSettings.layout(this, context, x + 8, cy, inner, draw);
			height = cy - y + 6;
			VeloDraw.fillRounded(context, x, y, width, height, 7, BG);
			for (Runnable r : draw) {
				r.run();
			}
			deferred.forEach(Runnable::run);
			heightHolder[0] = height;
		}

		private String hintText(RmState.Tool tool) {
			if (RmState.pasting) {
				return "Aim where it goes  ·  Shift+scroll rotates  ·  left click pastes  ·  right click cancels";
			}
			if (Placements.following() != null) {
				return "Aim where it goes  ·  Shift+scroll rotates  ·  left click locks it in place  ·  right click cancels";
			}
			return tool.hint;
		}
	}

	// ---- schematics (left) ----

	private static final class SchematicsPanel extends Panel {
		SchematicsPanel() {
			super("rm-schematics", "Redstone: Schematics", 0.01f, 0.32f);
		}

		@Override
		boolean shown() {
			return !Placements.all().isEmpty();
		}

		@Override
		void draw(DrawContext context, int x, int y) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			width = 190;
			List<Placements.Placement> list = Placements.all();
			Placements.Placement selected = Placements.selected();
			List<Runnable> draw = new ArrayList<>();
			int cy = y + 8;
			int top = y;
			draw.add(() -> drawIcon(context, "layers", x + 8, top + 7, 14, ACCENT));
			draw.add(() -> context.drawTextWithShadow(textRenderer, "Schematics", x + 26, top + 10, TEXT));
			cy += 18;
			for (Placements.Placement p : list) {
				int ry = cy;
				boolean isSelected = p == selected;
				draw.add(() -> {
					if (isSelected) {
						VeloDraw.fillRounded(context, x + 4, ry - 2, width - 8, 18, 4, 0x40FF4D4D);
					}
					context.drawTextWithShadow(textRenderer, VeloUi.trim(p.name, width - 76), x + 8, ry + 3, p.visible ? TEXT : MUTED);
					iconButton(context, x + width - 62, ry, 14, p.visible ? "view" : "hidden", false, p.visible ? "Hide" : "Show",
							() -> p.visible = !p.visible);
					iconButton(context, x + width - 44, ry, 14, p.locked ? "lock" : "unlock", false, p.locked ? "Locked in place" : "Lock here",
							() -> p.locked = true);
					iconButton(context, x + width - 26, ry, 14, "close", false, "Remove", () -> Placements.remove(p));
					hit(x + 4, ry - 2, width - 72, 18, () -> Placements.select(p), null);
				});
				cy += 20;
			}
			if (selected != null) {
				cy += 4;
				Clip v = selected.view();
				int infoY = cy;
				String info = v.sx + " x " + v.sy + " x " + v.sz + "  ·  " + v.solidCount() + " blocks"
						+ (v.entities.isEmpty() ? "" : " · " + v.entities.size() + " entities");
				draw.add(() -> context.drawTextWithShadow(textRenderer, VeloUi.trim(info, width - 16), x + 8, infoY, MUTED));
				cy += 14;
				int inner = width - 16;
				int rowY = cy;
				draw.add(() -> settingRow(context, x + 8, rowY, inner, "Layers", switch (selected.layerMode) {
					case ALL -> "All";
					case SINGLE -> "Single";
					case RANGE -> "Range";
				}, () -> selected.layerMode = Placements.LayerMode.values()[(selected.layerMode.ordinal() + 1) % 3]));
				cy += 18;
				if (selected.layerMode != Placements.LayerMode.ALL) {
					int axisY = cy;
					draw.add(() -> settingRow(context, x + 8, axisY, inner, "Axis", String.valueOf(selected.axis).toUpperCase(Locale.ROOT),
							() -> selected.axis = selected.axis == 'y' ? 'x' : selected.axis == 'x' ? 'z' : 'y'));
					cy += 18;
					int layerY = cy;
					draw.add(() -> {
						context.drawTextWithShadow(textRenderer, selected.layerMode == Placements.LayerMode.RANGE ? "From" : "Layer", x + 8, layerY + 3, MUTED);
						feetButton(context, x + width - 112, layerY, () -> selected.layer = selected.layerAtFeet());
						stepper(context, x + width - 74, layerY, selected.layer, v2 -> selected.layer = Math.max(0, Math.min(selected.layerCount() - 1, v2)));
					});
					cy += 18;
					if (selected.layerMode == Placements.LayerMode.RANGE) {
						int toY = cy;
						draw.add(() -> {
							context.drawTextWithShadow(textRenderer, "To", x + 8, toY + 3, MUTED);
							feetButton(context, x + width - 112, toY, () -> selected.layerTo = selected.layerAtFeet());
							stepper(context, x + width - 74, toY, selected.layerTo,
									v2 -> selected.layerTo = Math.max(0, Math.min(selected.layerCount() - 1, v2)));
						});
						cy += 18;
					}
				}
				int placeY = cy;
				draw.add(() -> {
					int bx = x + 8;
					bx += textButton(context, bx, placeY, 0, "Move Here", false, () -> {
						selected.moveToFeet();
						RmState.info("Lower corner moved to where you stand");
					}) + 4;
					textButton(context, bx, placeY, 0, selected.outlineOnly ? "Show Blocks" : "Outline Only", selected.outlineOnly,
							() -> selected.outlineOnly = !selected.outlineOnly);
				});
				cy += 18;
				int buttonsY = cy;
				draw.add(() -> {
					int bx = x + 8;
					bx += textButton(context, bx, buttonsY, 0, "Rotate", false, () -> selected.rotation = (selected.rotation + 1) % 4) + 4;
					bx += textButton(context, bx, buttonsY, 0, "Flip", false, () -> selected.mirror = !selected.mirror) + 4;
					textButton(context, bx, buttonsY, 0, "Materials", false, () -> MinecraftClient.getInstance().setScreen(new MaterialListScreen(selected)));
				});
				cy += 18;
				int pasteY = cy;
				draw.add(() -> textButton(context, x + 8, pasteY, width - 16, RmWorld.singleplayer() ? "Paste into world" : "Paste (singleplayer only)",
						true, () -> {
							if (RmWorld.singleplayer()) {
								RmOps.paste(selected.view(), selected.x, selected.y, selected.z);
							} else {
								RmState.error("Pasting needs singleplayer - build it by hand using the preview (middle click picks blocks)");
							}
						}));
				cy += 18;
			}
			height = cy - y + 6;
			VeloDraw.fillRounded(context, x, y, width, height, 7, BG);
			for (Runnable r : draw) {
				r.run();
			}
		}

		/** Sets a layer to the one you're standing in (along the placement's layer axis). */
		private void feetButton(DrawContext context, int x, int y, Runnable set) {
			hit(x, y, 34, 14, set, "Set to the layer at your feet");
			textButton(context, x, y, 34, "Feet", false, set);
		}

		private void stepper(DrawContext context, int x, int y, int value, java.util.function.IntConsumer set) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			textButton(context, x, y, 18, "-", false, () -> set.accept(value - 1));
			String text = String.valueOf(value);
			context.drawTextWithShadow(textRenderer, text, x + 37 - textRenderer.getWidth(text) / 2, y + 3, TEXT);
			textButton(context, x + 56, y, 18, "+", false, () -> set.accept(value + 1));
		}
	}

	// ---- timeline (bottom) ----

	private static final class TimelinePanel extends Panel {
		TimelinePanel() {
			super("rm-timeline", "Redstone: Timeline", 0.5f, 0.84f);
		}

		@Override
		boolean shown() {
			return Timeline.replayOpen() || Timeline.phase() == Timeline.Phase.RECORDING;
		}

		@Override
		void draw(DrawContext context, int x, int y) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			width = 380;
			height = 34;
			VeloDraw.fillRounded(context, x, y, width, height, 8, BG);
			if (Timeline.phase() == Timeline.Phase.RECORDING) {
				VeloDraw.fillCircle(context, x + 14, y + 17, 4, (System.currentTimeMillis() / 500) % 2 == 0 ? ACCENT : 0xFF802020);
				context.drawTextWithShadow(textRenderer, "Recording  " + Timeline.recordingFrames() + " ticks", x + 24, y + 13, TEXT);
				textButton(context, x + width - 70, y + 10, 62, "Stop (K)", true, Timeline::toggle);
				return;
			}
			int frames = Math.max(1, Timeline.frameCount());
			int frame = Timeline.shownFrame();
			int bx = x + 6;
			iconButton(context, bx, y + 7, 20, "back", false, "Previous tick (←)", () -> Timeline.scrub(-1));
			bx += 22;
			iconButton(context, bx, y + 7, 20, Timeline.playing() ? "pause" : "play", false, Timeline.playing() ? "Pause (↑)" : "Play (↑)",
					Timeline::togglePlay);
			bx += 22;
			iconButton(context, bx, y + 7, 20, "step", false, "Next tick (→)", () -> Timeline.scrub(1));
			bx += 26;
			// Slider with activity marks
			int sx = bx;
			int sw = width - (bx - x) - 118;
			int sy = y + 15;
			context.fill(sx, sy, sx + sw, sy + 4, 0xFF2A2A33);
			int step = Math.max(1, frames / Math.max(1, sw));
			for (int f = 1; f <= frames; f += step) {
				int changes = Timeline.changesAt(f);
				if (changes > 0) {
					int px = sx + (int) ((long) f * sw / frames);
					int hgt = Math.min(6, 1 + changes / 2);
					context.fill(px, sy - hgt, px + 1, sy, 0xFFC080FF);
				}
			}
			int cursorX = sx + (int) ((long) frame * sw / frames);
			context.fill(sx, sy, cursorX, sy + 4, ACCENT);
			VeloDraw.fillCircle(context, cursorX, sy + 2, 5, 0xFFFFFFFF);
			hit(sx, y + 6, sw, 22, () -> {
				draggingSlider = true;
				drag(mouseX);
			}, null);
			setSlider(screenX(sx), sw * position().scale());
			String time = frame + " / " + frames;
			context.drawTextWithShadow(textRenderer, time, sx + sw + 8, y + 9, TEXT);
			String speed = String.format(Locale.ROOT, "%.2fx", RmSettings.get().playbackSpeed);
			context.drawTextWithShadow(textRenderer, speed, sx + sw + 8, y + 20, MUTED);
			int rx = x + width - 64;
			textButton(context, rx, y + 3, 14, "-", false, () -> Timeline.changeSpeed(-1));
			textButton(context, rx + 16, y + 3, 14, "+", false, () -> Timeline.changeSpeed(1));
			iconButton(context, x + width - 28, y + 7, 20, "close", false, "Leave replay (K)", Timeline::toggle);
		}
	}

	// ---- badge (bottom right) ----

	private static final class BadgePanel extends Panel {
		BadgePanel() {
			super("rm-badge", "Redstone: Status", 0.99f, 0.98f);
		}

		@Override
		boolean shown() {
			return true;
		}

		@Override
		void draw(DrawContext context, int x, int y) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			List<String[]> chips = new ArrayList<>();
			if (TickControls.frozen()) {
				chips.add(new String[] {"FROZEN", "+" + TickControls.stepped()});
			}
			if (Timeline.phase() == Timeline.Phase.RECORDING) {
				chips.add(new String[] {"REC", null});
			}
			if (Timeline.replayOpen()) {
				chips.add(new String[] {"REPLAY", null});
			}
			String title = "REDSTONE MODE";
			int w = textRenderer.getWidth(title) + 26;
			for (String[] chip : chips) {
				w += textRenderer.getWidth(chip[0] + (chip[1] != null ? " " + chip[1] : "")) + 12;
			}
			String hint = cursor ? "Alt: back to game" : "Alt: menu";
			w += textRenderer.getWidth(hint) + 10;
			width = w;
			height = 16;
			VeloDraw.fillRounded(context, x, y, width, height, 5, BG);
			drawIcon(context, "redstone", x + 4, y + 2, 12, ACCENT);
			int tx = x + 20;
			context.drawTextWithShadow(textRenderer, title, tx, y + 4, TEXT);
			tx += textRenderer.getWidth(title) + 6;
			for (String[] chip : chips) {
				String text = chip[0] + (chip[1] != null ? " " + chip[1] : "");
				int cw = textRenderer.getWidth(text) + 8;
				VeloDraw.fillRounded(context, tx, y + 2, cw, 12, 3, chip[0].equals("REC") ? 0xFFB02020 : 0xFF2E5C9E);
				context.drawTextWithShadow(textRenderer, text, tx + 4, y + 4, TEXT);
				tx += cw + 4;
			}
			context.drawTextWithShadow(textRenderer, hint, tx + 4, y + 4, MUTED);
		}
	}
}
