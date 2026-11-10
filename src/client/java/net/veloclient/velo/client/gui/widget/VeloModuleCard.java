package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.module.Module;

/**
 * A module in the Velo menu (2026-11 redesign, same look as the launcher): one slim row - a quiet icon tile whose icon
 * turns the accent colour while the module is on, the name, its category tag and a switch on the right. Click the
 * switch to turn it on or off, anywhere else to open its settings page (with the live preview). Addons take their
 * group's colour.
 */
public final class VeloModuleCard extends ClickableWidget {

	public static final int HEIGHT = 46;
	/** Layouts the R-Shift menu can switch between: the default grid, small tiles, one row per module. */
	public static final int GRID = 0, COMPACT = 1, LIST = 2;

	public static int heightFor(int style) {
		return style == COMPACT ? 24 : style == LIST ? 30 : HEIGHT;
	}

	private int style = GRID;

	/** Same card in another layout ({@link #GRID}, {@link #COMPACT} or {@link #LIST}). */
	public VeloModuleCard style(int style) {
		this.style = style;
		this.height = heightFor(style);
		return this;
	}
	private static final int SWITCH_W = 17;
	private static final int SWITCH_H = 10;

	private final Module module;
	private final Runnable onSelect;
	private final Runnable onOpen;
	private final java.util.function.BooleanSupplier selected;
	private final Identifier icon;
	private final int color;
	private final String tag;
	private final long bornAt;
	private float hover;
	private float on = -1;
	private long lastNanos;

	/**
	 * @param color the module's accent (the theme's, or its addon group's)
	 * @param order where it is in the grid - cards cascade in one after another
	 */
	public VeloModuleCard(int width, Module module, int color, int order, java.util.function.BooleanSupplier selected, Runnable onSelect, Runnable onOpen) {
		super(0, 0, width, HEIGHT, Text.literal(module.displayName()));
		this.module = module;
		this.color = color | 0xFF000000;
		this.selected = selected;
		this.onSelect = onSelect;
		this.onOpen = onOpen;
		this.icon = ModuleIcons.textureFor(module.id());
		this.tag = ModuleTags.tag(module);
		this.bornAt = System.nanoTime() + 40_000_000L + Math.min(order, 30) * 9_000_000L;
	}

	private boolean overSwitch(double mouseX, double mouseY) {
		return mouseX >= getX() + getWidth() - SWITCH_W - 16;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		if (overSwitch(click.x(), click.y())) {
			boolean value = !module.isEnabled();
			module.setEnabled(value);
			net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
			net.veloclient.velo.client.gui.title.QuickToggles.touch(module.id());
			net.veloclient.velo.client.sound.VeloUiSounds.toggle(value);
			return;
		}
		// One click opens the module (its settings + preview); the old side panel is gone.
		net.veloclient.velo.client.sound.VeloUiSounds.click(1.0f);
		onSelect.run();
		onOpen.run();
	}

	/** 0..1 since this card was created (after its place in the cascade). */
	private float entrance() {
		float t = Math.max(0f, Math.min(1f, (System.nanoTime() - bornAt) / 200_000_000f));
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		lastNanos = now;
		boolean enabled = module.isEnabled();
		if (on < 0) {
			on = enabled ? 1f : 0f;
		}
		on = VeloAnim.step(on, enabled ? 1f : 0f, dt * 1.4f);
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, dt * 1.3f);
		float in = entrance();
		if (in < 1f) {
			skeleton(context, Math.round(255 * (1f - in)));
		}
		if (in <= 0.001f) {
			return;
		}
		var renderer = MinecraftClient.getInstance().textRenderer;
		int x = getX();
		int y = getY() + Math.round((1f - in) * 6f);
		int w = getWidth();
		int h = getHeight();
		int fade = Math.round(255 * in);

		// Flat card: a hair lighter and an accent edge on hover - no glow, no warm wash.
		int base = VeloAnim.lerpArgb(0x0AFFFFFF, 0x13FFFFFF, hover);
		VeloDraw.fillRounded(context, x, y, w, h, 6, VeloUi.withAlpha(base, Math.round(((base >>> 24) & 0xFF) * in)));
		int edge = VeloAnim.lerpArgb(0x0DFFFFFF, VeloUi.withAlpha(color, 0x70), hover);
		VeloDraw.strokeRounded(context, x, y, w, h, 6, VeloUi.withAlpha(edge, Math.round(((edge >>> 24) & 0xFF) * in)));

		// Icon tile: always the same quiet square; the icon itself takes the accent while the module is on.
		int tile = style == COMPACT ? 16 : style == GRID ? 30 : 20;
		int tx = x + (style == GRID ? 9 : 7);
		int ty = y + (h - tile) / 2;
		VeloDraw.fillRounded(context, tx, ty, tile, tile, 5, VeloUi.withAlpha(0xFF1E1819, fade));
		int iconSize = style == COMPACT ? 10 : style == GRID ? 17 : 12;
		int tint = VeloAnim.lerpArgb(0xFF8E8384, VeloAnim.lerpArgb(color, 0xFFFFFFFF, 0.3f), on);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, tx + (tile - iconSize) / 2, ty + (tile - iconSize) / 2, 0f, 0f, iconSize, iconSize,
				iconSize, iconSize, iconSize, iconSize, VeloUi.withAlpha(tint, fade));

		// Name, and the category tag under it.
		int textX = tx + tile + (style == GRID ? 10 : 7);
		int textW = w - (textX - x) - SWITCH_W - 18;
		if (style == COMPACT) {
			// small tile: just the name, centred
			Text name = MenuKit.fit(renderer, MenuKit.uiBold(module.displayName()), textW);
			MenuKit.text(context, renderer, name, textX, y + (h - 8) / 2, VeloUi.withAlpha(0xFFEDE6E6, fade));
		} else {
		int nameW = style == LIST ? Math.min(textW, 150) : textW;
		Text name = MenuKit.fit(renderer, MenuKit.uiBold(module.displayName()), nameW);
		MenuKit.text(context, renderer, name, textX, y + (style == LIST ? 5 : style == GRID ? 12 : 7), VeloUi.withAlpha(0xFFEDE6E6, fade));
		if (style == LIST) {
			// list row: what it does, next to the name
			int descX = textX + 160;
			int descW = x + w - SWITCH_W - 18 - descX;
			if (descW > 40) {
				MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.ui(module.description()), descW), descX, y + (h - 8) / 2,
						VeloUi.withAlpha(0xFFB3A8A9, fade));
			}
		}
		StringBuilder small = new StringBuilder(tag.toUpperCase(java.util.Locale.ROOT));
		if (module.beta()) {
			small.append("  \u00B7  BETA");
		}
		if (module.isNew()) {
			small.append("  \u00B7  NEW");
		}
		MenuKit.text(context, renderer, MenuKit.fit(renderer, MenuKit.small(small.toString()), nameW), textX, y + (style == LIST ? 17 : style == GRID ? 26 : 19),
				VeloUi.withAlpha(module.isNew() ? VeloAnim.lerpArgb(color, 0xFFFFFFFF, 0.35f) : 0xFF8E8384, fade));
		}

		// Switch: a rounded square track with a square knob (launcher look).
		float sx = x + w - SWITCH_W - (style == GRID ? 11 : 8);
		float sy = y + (h - SWITCH_H) / 2f;
		boolean overSwitch = isHovered() && overSwitch(mouseX, mouseY);
		VeloDraw.fillRounded(context, sx, sy, SWITCH_W, SWITCH_H, 3f,
				VeloUi.withAlpha(VeloAnim.lerpArgb(overSwitch ? 0xFF3A3233 : 0xFF2A2324, color, on), fade));
		float knob = SWITCH_H - 4;
		VeloDraw.fillRounded(context, sx + 2 + (SWITCH_W - 4 - knob) * on, sy + 2, knob, knob, 1.5f,
				VeloUi.withAlpha(VeloAnim.lerpArgb(0xFF8E8384, 0xFFFFFFFF, on), fade));
	}

	/** The loading placeholder: the card's shapes in faint grey with a soft light sweeping across. */
	private void skeleton(DrawContext context, int alpha) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		VeloDraw.fillRounded(context, x, y, w, h, 6, VeloUi.withAlpha(0x0AFFFFFF, Math.round(0x0A * alpha / 255f) + 0));
		VeloDraw.strokeRounded(context, x, y, w, h, 6, VeloUi.withAlpha(0x12FFFFFF, Math.round(0x12 * alpha / 255f)));
		int shape = VeloUi.withAlpha(0x14FFFFFF, Math.round(0x14 * alpha / 255f));
		VeloDraw.fillRounded(context, x + 7, y + (h - 20) / 2, 20, 20, 5, shape);
		VeloDraw.fillRounded(context, x + w - 25, y + (h - 10) / 2, 17, 10, 3, shape);
		VeloDraw.fillRounded(context, x + 34, y + 8, Math.min(w - 70, 70), 7, 3, shape);
		VeloDraw.fillRounded(context, x + 34, y + 19, 30, 6, 3, shape);
		float sweep = (System.nanoTime() / 1_000_000L % 1100) / 1100f;
		int band = Math.max(20, w / 3);
		int bx = x - band + Math.round((w + band) * sweep);
		context.enableScissor(x, y, x + w, y + h);
		VeloDraw.fillRoundedGradient(context, bx, y, band, h, 0, VeloUi.withAlpha(0x10FFFFFF, Math.round(0x10 * alpha / 255f)), 0x00FFFFFF);
		context.disableScissor();
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
		builder.put(NarrationPart.HINT, module.isEnabled() ? "on" : "off");
	}
}
