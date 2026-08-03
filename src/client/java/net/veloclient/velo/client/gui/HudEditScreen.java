package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloButton;
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
public final class HudEditScreen extends Screen {

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
		this.addDrawableChild(new VeloButton(this.width / 2 - 50, this.height - 30, 100, 20, Text.literal("Done"), b -> this.close()));
	}

	private List<HudModule> enabledHudModules() {
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

	private int scaledWidth(HudModule hud) {
		return Math.round(hud.width() * hud.position().scale());
	}

	private int scaledHeight(HudModule hud) {
		return Math.round(hud.height() * hud.position().scale());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		int screenWidth = this.width;
		int screenHeight = this.height;

		for (HudModule hud : enabledHudModules()) {
			int w = scaledWidth(hud);
			int h = scaledHeight(hud);
			int x = hud.position().resolveX(screenWidth, w);
			int y = hud.position().resolveY(screenHeight, h);
			HudManager.renderScaled(context, hud, screenWidth, screenHeight, delta);

			boolean hovered = hud == dragging || hud == resizing || isInside(mouseX, mouseY, x, y, w, h);
			int borderColor = hovered ? theme.accentStart() : 0x77FFFFFF;
			VeloDraw.strokeRect(context, x - 2, y - 2, w + 4, h + 4, borderColor);
			context.drawTextWithShadow(this.textRenderer, hud.displayName() + " (" + Math.round(hud.position().scale() * 100) + "%)",
					x, y - 12, 0xFFFFFF00);

			if (hovered) {
				// Inside the box's own top-right corner, not above it -
				// sitting outside the bounding box meant the badge vanished
				// (hovered flipped false) the moment the mouse moved off the
				// element to actually reach it.
				int badgeX = x + w - BADGE_SIZE - 2;
				int badgeY = y + 2;
				boolean badgeHovered = isInside(mouseX, mouseY, badgeX, badgeY, BADGE_SIZE, BADGE_SIZE);
				VeloDraw.fillRounded(context, badgeX, badgeY, BADGE_SIZE, BADGE_SIZE, 2, badgeHovered ? 0xFFFF5555 : 0xAAFF5555);
				context.drawTextWithShadow(this.textRenderer, "x", badgeX + 3, badgeY + 2, 0xFFFFFFFF);

				int handleX = x + w - HANDLE_SIZE / 2;
				int handleY = y + h - HANDLE_SIZE / 2;
				boolean handleHovered = hud == resizing || isInside(mouseX, mouseY, handleX, handleY, HANDLE_SIZE, HANDLE_SIZE);
				VeloDraw.fillRounded(context, handleX, handleY, HANDLE_SIZE, HANDLE_SIZE, 2,
						handleHovered ? theme.accentStart() : 0xCCFFFFFF);
			}
		}
		super.render(context, mouseX, mouseY, delta);

		long now = System.nanoTime();
		float dt = lastNanos < 0 ? 0f : (now - lastNanos) / 1_000_000_000f;
		lastNanos = now;
		boolean mouseNearTop = mouseY < BANNER_HEIGHT + 6;
		bannerOpacity = VeloAnim.step(bannerOpacity, mouseNearTop ? 0f : 1f, dt * 2f);
		if (bannerOpacity > 0.02f) {
			int alpha = (int) (0xCC * bannerOpacity);
			context.fill(0, 0, this.width, BANNER_HEIGHT, (alpha << 24) | (theme.surfaceWithOpacity() & 0x00FFFFFF));
			int textAlpha = (int) (0xFF * bannerOpacity);
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("Drag to move - drag the corner handle to resize - click ✕ to disable"),
					this.width / 2, 7, (textAlpha << 24) | (theme.text() & 0x00FFFFFF));
		}
	}

	private static boolean isInside(int mouseX, int mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
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

			int badgeX = x + w - BADGE_SIZE - 2;
			int badgeY = y + 2;
			if (isInside(mouseX, mouseY, badgeX, badgeY, BADGE_SIZE, BADGE_SIZE)) {
				hud.setEnabled(false);
				return true;
			}
			int handleX = x + w - HANDLE_SIZE / 2;
			int handleY = y + h - HANDLE_SIZE / 2;
			if (isInside(mouseX, mouseY, handleX, handleY, HANDLE_SIZE, HANDLE_SIZE)) {
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
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		this.client.setScreen(this.parent);
	}

	/** Unlike {@link #close()} (Escape - returns to whatever screen opened this), the panel-toggle keybind should back all the way out to gameplay in one press. */
	public void closeToGame() {
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		this.client.setScreen(null);
	}
}
