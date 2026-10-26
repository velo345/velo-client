package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudManager;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Live drag-and-drop HUD layout editor (design spec sections 5 and 7):
 * renders behind-the-scenes over the still-visible game world (non-pausing
 * Screen), lets you drag any enabled HUD element to reposition it with
 * snap-to-grid, drag the handle in its bottom-right corner to resize it, and
 * click the small ✕ badge to disable one right there. The instruction banner
 * fades out whenever the mouse is near the top of the screen so it never
 * blocks dragging an element positioned up there.
 */
public final class HudEditScreen extends Screen implements net.veloclient.velo.client.gui.window.ModuleConfigScreen.Reopenable {

	private static final float GRID_SIZE = 0.01f;
	private static final int BADGE_SIZE = 12;
	private static final int HANDLE_SIZE = 8;
	private static final int BANNER_HEIGHT = 22;

	private final Screen parent;
	private HudModule dragging;
	private int dragOffsetX;
	private int dragOffsetY;
	private HudModule resizing;
	private float bannerOpacity = 1f;
	private long lastNanos = -1;

	public HudEditScreen(Screen parent) {
		super(Text.literal("Edit HUD Layout"));
		this.parent = parent;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	protected void init() {
		// The toolbar (HUD Style, Reset, Done) is drawn at the top - nothing sits over the hotbar.
	}

	@Override
	public void reopen() {
	}

	private void resetLayout() {
		// Every HUD element (enabled or not) back to its default spot and size - or just the F3 panels in F3 mode.
		if (redstone()) {
			net.veloclient.velo.client.redstone.RmHud.allPanels().forEach(panel -> panel.position().resetToDefault());
			return;
		}
		var f3 = f3Editing();
		if (f3 != null) {
			f3.allPanels().forEach(panel -> panel.position().resetToDefault());
			return;
		}
		for (Module module : ModuleRegistry.all()) {
			if (module instanceof HudModule hud) {
				hud.position().resetToDefault();
			}
		}
	}

	/** Better F3 on and the F3 screen open: only the F3 panels are editable (press F3 to switch). */
	private static net.veloclient.velo.client.modules.debug.BetterF3Module f3Editing() {
		if (ModuleRegistry.get("better-f3").orElse(null) instanceof net.veloclient.velo.client.modules.debug.BetterF3Module f3
				&& f3.isEnabled() && net.veloclient.velo.client.modules.debug.BetterF3Module.f3Open()) {
			return f3;
		}
		return null;
	}

	/** Redstone Mode on: only its overlay panels are shown and editable. */
	private static boolean redstone() {
		return net.veloclient.velo.client.redstone.RedstoneMode.active();
	}

	private static boolean betterF3On() {
		return ModuleRegistry.get("better-f3").map(Module::isEnabled).orElse(false);
	}

	/** F3 state when F3 went down here, or null - see {@link #keyReleased}. */
	private Boolean f3StateAtPress;

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == net.veloclient.velo.client.util.Keys.F3) {
			f3StateAtPress = net.veloclient.velo.client.modules.debug.BetterF3Module.f3Open();
			return true;
		}
		return super.keyPressed(input);
	}

	/**
	 * F3 toggles on release, and only if nothing else already toggled it: depending on the version
	 * vanilla also flips the debug screen for F3 while some screens are open, and doing it here on
	 * press as well made it flip twice (back to where it was).
	 */
	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyInput input) {
		if (input.key() == net.veloclient.velo.client.util.Keys.F3 && f3StateAtPress != null) {
			if (net.veloclient.velo.client.modules.debug.BetterF3Module.f3Open() == f3StateAtPress) {
				net.veloclient.velo.client.modules.debug.BetterF3Module.toggleF3();
			}
			f3StateAtPress = null;
			dragging = null;
			resizing = null;
			return true;
		}
		return super.keyReleased(input);
	}

	private List<HudModule> enabledHudModules() {
		if (redstone()) {
			List<HudModule> panels = new ArrayList<>();
			for (HudModule panel : net.veloclient.velo.client.redstone.RmHud.allPanels()) {
				if (panel.width() > 0 && panel.height() > 0) {
					panels.add(panel);
				}
			}
			return panels;
		}
		var f3 = f3Editing();
		if (f3 != null) {
			List<HudModule> panels = new ArrayList<>();
			for (HudModule panel : f3.panels()) {
				if (panel.width() > 0 && panel.height() > 0) {
					panels.add(panel);
				}
			}
			return panels;
		}
		List<HudModule> modules = new ArrayList<>();
		for (Module module : ModuleRegistry.all()) {
			// width()/height() of 0 means "nothing to show right now" (e.g.
			// the scoreboard module when the server hasn't set one) rather
			// than a real zero-size element, so it's skipped here instead of
			// showing an empty, meaningless draggable box.
			if (module instanceof HudModule hud && hud.isEnabled() && hud.width() > 0 && hud.height() > 0) {
				modules.add(hud);
			}
		}
		return modules;
	}

	/** Dev screenshot tour: holds {@code hud} as if dragged so its top-left sits at (x, y); null lets go. */
	public void dragForTour(HudModule hud, int x, int y) {
		dragging = hud;
		if (hud != null) {
			int w = scaledWidth(hud);
			int h = scaledHeight(hud);
			hud.position().set(this.width > w ? x / (float) (this.width - w) : 0f, this.height > h ? y / (float) (this.height - h) : 0f);
			hud.position().setPlaced(true);
		}
	}

	/** Dev screenshot tour: holds {@code hud}'s resize handle at {@code scale}; null lets go. */
	public void resizeForTour(HudModule hud, float scale) {
		resizing = hud;
		if (hud != null) {
			hud.position().setScale(scale);
		}
	}

	/** Dev screenshot tour: {x, y, w, h} of {@code hud} on screen. */
	public int[] areaForTour(HudModule hud) {
		int w = scaledWidth(hud);
		int h = scaledHeight(hud);
		return new int[] {hud.position().resolveX(this.width, w), hud.position().resolveY(this.height, h), w, h};
	}

	private int scaledWidth(HudModule hud) {
		return Math.round(hud.width() * hud.position().scale());
	}

	private int scaledHeight(HudModule hud) {
		return Math.round(hud.height() * hud.position().scale());
	}

	private final List<net.veloclient.velo.client.gui.widget.VeloUi.Hit> toolbarHits = new ArrayList<>();
	private final java.util.Map<HudModule, Float> hoverAnim = new java.util.HashMap<>();

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		int screenWidth = this.width;
		int screenHeight = this.height;
		net.veloclient.velo.client.hud.HudAutoLayout.update(screenWidth, screenHeight);
		long now = System.nanoTime();
		float dt = lastNanos < 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
		lastNanos = now;
		int accent = theme.accentStart() | 0xFF000000;

		// A light veil so the elements stand out from the world, and centre guides while something moves.
		context.fill(0, 0, screenWidth, screenHeight, 0x30000000);
		if (dragging != null) {
			context.fill(screenWidth / 2, 0, screenWidth / 2 + 1, screenHeight, VeloUi.withAlpha(accent, 0x50));
			context.fill(0, screenHeight / 2, screenWidth, screenHeight / 2 + 1, VeloUi.withAlpha(accent, 0x50));
		}

		HudModule labelled = null;
		int[] labelBox = null;
		for (HudModule hud : enabledHudModules()) {
			int w = scaledWidth(hud);
			int h = scaledHeight(hud);
			int x = hud.position().resolveX(screenWidth, w);
			int y = hud.position().resolveY(screenHeight, h);
			HudManager.renderScaled(context, hud, screenWidth, screenHeight, delta);

			boolean active = hud == dragging || hud == resizing;
			boolean hovered = active || isInside(mouseX, mouseY, x, y, w, h);
			float t = VeloAnim.step(hoverAnim.getOrDefault(hud, 0f), hovered ? 1f : 0f, dt * 1.6f);
			hoverAnim.put(hud, t);
			// A quiet outline at rest; the accent and a faint fill on the one you're on.
			if (t > 0.01f) {
				VeloDraw.fillRounded(context, x - 3, y - 3, w + 6, h + 6, 5, VeloUi.withAlpha(accent, Math.round(0x18 * t)));
			}
			VeloDraw.strokeRounded(context, x - 3, y - 3, w + 6, h + 6, 5, VeloAnim.lerpArgb(0x38FFFFFF, accent, t));
			if (hovered) {
				labelled = hud;
				labelBox = new int[] {x, y, w, h};
				if (f3Editing() == null && !redstone()) {
					// The hide button sits inside the box's top-right corner, so it doesn't vanish on the way there.
					int badgeX = x + w - BADGE_SIZE - 1;
					int badgeY = y + 1;
					boolean badgeHovered = isInside(mouseX, mouseY, badgeX, badgeY, BADGE_SIZE, BADGE_SIZE);
					VeloDraw.fillCircle(context, badgeX + BADGE_SIZE / 2f, badgeY + BADGE_SIZE / 2f, BADGE_SIZE / 2f, badgeHovered ? 0xFFFF4D5A : 0xD0202027);
					VeloDraw.cross(context, badgeX + BADGE_SIZE / 2f, badgeY + BADGE_SIZE / 2f, 4f, 1.2f, 0xFFFFFFFF);
				}
				int handleX = x + w - HANDLE_SIZE / 2 + 2;
				int handleY = y + h - HANDLE_SIZE / 2 + 2;
				boolean handleHovered = hud == resizing || isInside(mouseX, mouseY, handleX - 2, handleY - 2, HANDLE_SIZE + 4, HANDLE_SIZE + 4);
				VeloDraw.fillCircle(context, handleX + HANDLE_SIZE / 2f, handleY + HANDLE_SIZE / 2f, handleHovered ? 5f : 4f, accent);
				VeloDraw.fillCircle(context, handleX + HANDLE_SIZE / 2f, handleY + HANDLE_SIZE / 2f, handleHovered ? 2.5f : 2f, 0xFFFFFFFF);
			}
		}
		// Name and size of the one you're on, in a chip above it (below it at the very top).
		if (labelled != null) {
			Text name = MenuKit.uiBold(labelled.displayName());
			Text size = MenuKit.mono(Math.round(labelled.position().scale() * 100) + "%");
			int cw = this.textRenderer.getWidth(name) + this.textRenderer.getWidth(size) + 22;
			int cx = Math.max(2, Math.min(screenWidth - cw - 2, labelBox[0] - 3));
			int cy = labelBox[1] - 20 < 36 ? labelBox[1] + labelBox[3] + 6 : labelBox[1] - 20;
			VeloDraw.shadow(context, cx, cy, cw, 15, 5, 8, 2, 0x60000000);
			VeloDraw.fillRounded(context, cx, cy, cw, 15, 5, accent);
			MenuKit.text(context, this.textRenderer, name, cx + 7, cy + 4, 0xFFFFFFFF);
			MenuKit.text(context, this.textRenderer, size, cx + 13 + this.textRenderer.getWidth(name), cy + 5, 0xD0FFFFFF);
		}
		super.render(context, mouseX, mouseY, delta);
		drawToolbar(context, mouseX, mouseY, dt, accent);
	}

	private int toolbarY = -1;

	/** Whether a toolbar at this spot would sit over a (non-moving) HUD element. */
	private boolean toolbarCovers(int x, int y, int w, int h) {
		for (HudModule hud : enabledHudModules()) {
			if (hud == dragging || hud == resizing) {
				continue;
			}
			int hw = scaledWidth(hud);
			int hh = scaledHeight(hud);
			int hx = hud.position().resolveX(this.width, hw);
			int hy = hud.position().resolveY(this.height, hh);
			if (hx < x + w + 4 && hx + hw > x - 4 && hy < y + h + 4 && hy + hh > y - 4) {
				return true;
			}
		}
		return false;
	}

	/** Floating toolbar at the top: what you can do, then HUD Style / Reset / Done. Fades back while you drag. */
	private void drawToolbar(DrawContext context, int mouseX, int mouseY, float dt, int accent) {
		toolbarHits.clear();
		bannerOpacity = VeloAnim.step(bannerOpacity, dragging != null || resizing != null ? 0.25f : 1f, dt * 2f);
		int alpha = Math.round(255 * bannerOpacity);
		String hint = redstone() ? "Redstone Mode overlay - drag to move, corner to resize"
				: f3Editing() != null ? "F3 screen - drag to move, corner to resize - F3: back to the HUD"
				: betterF3On() ? "Drag to move  ·  corner to resize  ·  × to hide  ·  F3: edit the F3 screen"
				: "Drag to move  ·  corner to resize  ·  × to hide";
		Text hintText = MenuKit.ui(hint);
		String[] labels = redstone() || f3Editing() != null ? new String[] {"Reset", "Done"} : new String[] {"HUD Style", "Reset", "Done"};
		int buttonsW = 0;
		for (String label : labels) {
			buttonsW += this.textRenderer.getWidth(MenuKit.uiBold(label)) + 18 + 4;
		}
		int w = this.textRenderer.getWidth(hintText) + 24 + buttonsW + 6;
		int h = 24;
		int x = (this.width - w) / 2;
		// Top centre unless HUD elements live there (boss bar, coordinates...); then above the hotbar,
		// then the first free strip from the top down. Kept while it stays free, so it doesn't jump around.
		if (dragging == null && resizing == null && (toolbarY < 0 || toolbarCovers(x, toolbarY, w, h))) {
			// Redstone Mode keeps its own toolbar at the top centre: start lower there.
			int[] candidates = redstone() ? new int[] {this.height / 2 - h / 2 + 40, this.height - h - 64, 8} : new int[] {8, this.height - h - 64};
			toolbarY = -1;
			for (int c : candidates) {
				if (!toolbarCovers(x, c, w, h)) {
					toolbarY = c;
					break;
				}
			}
			for (int c = 8; toolbarY < 0 && c < this.height - h - 8; c += 6) {
				if (!toolbarCovers(x, c, w, h)) {
					toolbarY = c;
				}
			}
			if (toolbarY < 0) {
				toolbarY = 8;
			}
		}
		int y = Math.max(8, toolbarY);
		VeloDraw.shadow(context, x, y, w, h, 9, 12, 4, VeloUi.withAlpha(0xFF000000, Math.round(0x80 * bannerOpacity)));
		VeloDraw.fillRounded(context, x, y, w, h, 9, VeloUi.withAlpha(0xFF141419, Math.round(0xEC * bannerOpacity)));
		VeloDraw.strokeRounded(context, x, y, w, h, 9, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x1C * bannerOpacity)));
		MenuKit.text(context, this.textRenderer, hintText, x + 12, y + 8, VeloUi.withAlpha(0xFFB4B4BE, alpha));
		int bx = x + 12 + this.textRenderer.getWidth(hintText) + 12;
		for (String label : labels) {
			Text t = MenuKit.uiBold(label);
			int bw = this.textRenderer.getWidth(t) + 18;
			boolean primary = label.equals("Done");
			boolean hover = isInside(mouseX, mouseY, bx, y + 3, bw, h - 6);
			int fill = primary ? (hover ? VeloAnim.lerpArgb(accent, 0xFFFFFFFF, 0.15f) : accent) : hover ? 0x22FFFFFF : 0x10FFFFFF;
			VeloDraw.fillRounded(context, bx, y + 3, bw, h - 6, 6, VeloUi.withAlpha(fill, Math.round(((fill >>> 24) & 0xFF) * bannerOpacity)));
			MenuKit.text(context, this.textRenderer, t, bx + 9, y + 8, VeloUi.withAlpha(0xFFFFFFFF, alpha));
			Runnable action = switch (label) {
				case "Done" -> this::leave;
				case "Reset" -> this::resetLayout;
				default -> () -> ModuleRegistry.get("hud-style")
						.ifPresent(m -> this.client.setScreen(new net.veloclient.velo.client.gui.window.ModuleConfigScreen(m, this)));
			};
			toolbarHits.add(new VeloUi.Hit(bx, y + 3, bw, h - 6, action));
			bx += bw + 4;
		}
	}

	private static boolean isInside(int mouseX, int mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		for (var hit : List.copyOf(toolbarHits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		if (super.mouseClicked(click, doubled)) {
			return true;
		}
		int mouseX = (int) click.x();
		int mouseY = (int) click.y();
		for (HudModule hud : enabledHudModules()) {
			int w = scaledWidth(hud);
			int h = scaledHeight(hud);
			int x = hud.position().resolveX(this.width, w);
			int y = hud.position().resolveY(this.height, h);

			int badgeX = x + w - BADGE_SIZE - 1;
			int badgeY = y + 1;
			if (f3Editing() == null && !redstone() && isInside(mouseX, mouseY, badgeX, badgeY, BADGE_SIZE, BADGE_SIZE)) {
				hud.setEnabled(false);
				return true;
			}
			int handleX = x + w - HANDLE_SIZE / 2 + 2;
			int handleY = y + h - HANDLE_SIZE / 2 + 2;
			if (isInside(mouseX, mouseY, handleX - 2, handleY - 2, HANDLE_SIZE + 4, HANDLE_SIZE + 4)) {
				resizing = hud;
				return true;
			}
			if (isInside(mouseX, mouseY, x, y, w, h)) {
				dragging = hud;
				dragOffsetX = mouseX - x;
				dragOffsetY = mouseY - y;
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.gui.Click click, double offsetX, double offsetY) {
		if (resizing != null) {
			int w = scaledWidth(resizing);
			int h = scaledHeight(resizing);
			int x = resizing.position().resolveX(this.width, w);
			int y = resizing.position().resolveY(this.height, h);
			float fromWidth = resizing.width() > 0 ? ((float) click.x() - x) / resizing.width() : 1f;
			float fromHeight = resizing.height() > 0 ? ((float) click.y() - y) / resizing.height() : 1f;
			resizing.position().setScale(Math.max(fromWidth, fromHeight));
			return true;
		}
		if (dragging != null) {
			int newX = (int) click.x() - dragOffsetX;
			int newY = (int) click.y() - dragOffsetY;
			int w = scaledWidth(dragging);
			int h = scaledHeight(dragging);
			float xFraction = this.width > w ? newX / (float) (this.width - w) : 0f;
			float yFraction = this.height > h ? newY / (float) (this.height - h) : 0f;
			dragging.position().set(xFraction, yFraction);
			// Dragged by hand: from now on it stays exactly here instead of auto-stacking.
			dragging.position().setPlaced(true);
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.gui.Click click) {
		if (dragging != null) {
			dragging.position().snapToGrid(GRID_SIZE);
			dragging = null;
			return true;
		}
		if (resizing != null) {
			resizing = null;
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public void close() {
		leave();
	}

	/** Saves the layout and goes back to the screen that opened the editor. */
	private void leave() {
		if (redstone()) {
			net.veloclient.velo.client.redstone.RmHud.storePositions();
		}
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		this.client.setScreen(this.parent);
	}

	/** Unlike {@link #close()} (Escape - returns to whatever screen opened this), the panel-toggle keybind should back all the way out to gameplay in one press. */
	public void closeToGame() {
		if (redstone()) {
			net.veloclient.velo.client.redstone.RmHud.storePositions();
		}
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		this.client.setScreen(null);
	}
}
