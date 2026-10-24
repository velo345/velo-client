package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.module.Module;

/**
 * A module in the Velo menu (0.9.8): a big icon on a tile that lights up (with a glow) while the module is on, its
 * switch top right, the name and a short "good for" tag. Click the switch to turn it on or off, the card to show
 * its settings in the side panel, double click to open all of them. Addons take their group's colour.
 */
public final class VeloModuleCard extends ClickableWidget {

	public static final int HEIGHT = 72;
	private static final int SWITCH_W = 18;
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
	private float select;
	private long lastNanos;
	private long lastClick;

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
		// A short skeleton first, then the cards cascade in: the page answers at once, the content follows.
		this.bornAt = System.nanoTime() + 70_000_000L + Math.min(order, 24) * 14_000_000L;
	}

	private boolean overSwitch(double mouseX, double mouseY) {
		return mouseX >= getX() + getWidth() - SWITCH_W - 14 && mouseY <= getY() + 22;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (overSwitch(click.x(), click.y())) {
			boolean value = !module.isEnabled();
			module.setEnabled(value);
			net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
			net.veloclient.velo.client.gui.title.QuickToggles.touch(module.id());
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, value ? 1.15f : 0.9f));
			return;
		}
		long now = System.currentTimeMillis();
		boolean twice = doubled || now - lastClick < 320;
		lastClick = now;
		client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		if (twice) {
			onOpen.run();
		} else {
			onSelect.run();
		}
	}

	/** 0..1 since this card was created (after its place in the cascade). */
	private float entrance() {
		float t = Math.max(0f, Math.min(1f, (System.nanoTime() - bornAt) / 260_000_000f));
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
		on = VeloAnim.step(on, enabled ? 1f : 0f, dt * 1.3f);
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, dt);
		select = VeloAnim.step(select, selected.getAsBoolean() ? 1f : 0f, dt);
		float in = entrance();
		if (in < 1f) {
			skeleton(context, Math.round(255 * (1f - in)));
		}
		if (in <= 0.001f) {
			return;
		}
		var renderer = MinecraftClient.getInstance().textRenderer;
		int x = getX();
		int y = getY() + Math.round((1f - in) * 10f - hover * 1.5f);
		int w = getWidth();
		int h = getHeight();
		int fade = Math.round(255 * in);

		// Card: glass that warms up in the module's colour when it's on, an accent ring when it's the selected one.
		if (hover > 0.01f || select > 0.01f) {
			VeloDraw.shadow(context, x, y, w, h, 9, 12, 4, VeloUi.withAlpha(0xFF000000, Math.round(0x70 * Math.max(hover, select) * in)));
		}
		int base = VeloAnim.lerpArgb(0x0AFFFFFF, 0x12FFFFFF, hover);
		int warm = VeloUi.withAlpha(color, Math.round((0x16 + 0x10 * select) * on));
		VeloDraw.fillRounded(context, x, y, w, h, 9, VeloUi.withAlpha(base, Math.round(((base >>> 24) & 0xFF) * in)));
		VeloDraw.fillRoundedGradient(context, x, y, w, h, 9, VeloUi.withAlpha(warm, Math.round(((warm >>> 24) & 0xFF) * in)), 0x00000000);
		int edge = VeloAnim.lerpArgb(VeloAnim.lerpArgb(0x14FFFFFF, VeloUi.withAlpha(color, 0x60), on), color, select);
		VeloDraw.strokeRounded(context, x, y, w, h, 9, VeloUi.withAlpha(edge, Math.round(((edge >>> 24) & 0xFF) * in)));
		if (select > 0.01f) {
			VeloDraw.strokeRounded(context, x - 2, y - 2, w + 4, h + 4, 11, VeloUi.withAlpha(color, Math.round(0x30 * select * in)));
		}

		// The icon tile: lit up with a glow while on.
		int tile = 26;
		int tx = x + 9;
		int ty = y + 9;
		if (on > 0.01f) {
			VeloDraw.shadow(context, tx, ty, tile, tile, 8, 10, 2, VeloUi.withAlpha(color, Math.round(0x70 * on * in)));
		}
		int dark = VeloUi.withAlpha(0xFF24242B, fade);
		int lit = VeloUi.withAlpha(VeloAnim.lerpArgb(color, 0xFFFFFFFF, 0.08f), fade);
		int litLow = VeloUi.withAlpha(VeloAnim.lerpArgb(color, 0xFF000000, 0.35f), fade);
		VeloDraw.fillRoundedGradient(context, tx, ty, tile, tile, 8, VeloAnim.lerpArgb(dark, lit, on), VeloAnim.lerpArgb(VeloUi.withAlpha(0xFF19191F, fade), litLow, on));
		VeloDraw.strokeRounded(context, tx, ty, tile, tile, 8, VeloUi.withAlpha(0xFFFFFFFF, Math.round((0x12 + 0x18 * on) * in)));
		int iconSize = 16;
		int tint = VeloAnim.lerpArgb(0xFFC9C9D1, 0xFFFFFFFF, on);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, tx + (tile - iconSize) / 2, ty + (tile - iconSize) / 2, 0f, 0f, iconSize, iconSize,
				iconSize, iconSize, iconSize, iconSize, VeloUi.withAlpha(tint, fade));

		// Switch, top right.
		float sx = x + w - SWITCH_W - 9;
		float sy = y + 11;
		boolean overSwitch = isHovered() && overSwitch(mouseX, mouseY);
		VeloDraw.fillRounded(context, sx, sy, SWITCH_W, SWITCH_H, SWITCH_H / 2f,
				VeloUi.withAlpha(VeloAnim.lerpArgb(overSwitch ? 0xFF4A4A54 : 0xFF3A3A42, color, on), fade));
		if (on > 0.01f) {
			VeloDraw.shadow(context, Math.round(sx), Math.round(sy), SWITCH_W, SWITCH_H, 5, 6, 1, VeloUi.withAlpha(color, Math.round(0x60 * on * in)));
		}
		float knob = SWITCH_H - 4;
		VeloDraw.fillCircle(context, sx + 2 + knob / 2 + (SWITCH_W - 4 - knob) * on, sy + SWITCH_H / 2f, knob / 2,
				VeloUi.withAlpha(VeloAnim.lerpArgb(0xFFC9C9D1, 0xFFFFFFFF, on), fade));

		// Name and tag.
		Text name = MenuKit.fit(renderer, MenuKit.uiBold(module.displayName()), w - 18);
		MenuKit.text(context, renderer, name, x + 9, y + 42, VeloUi.withAlpha(VeloAnim.lerpArgb(0xFFD4D4DA, 0xFFFFFFFF, Math.max(on, hover)), fade));
		// The tag in the card's own colour (accent, or the addon group's): tinted, never a white label.
		Text tagText = MenuKit.small(tag.toUpperCase(java.util.Locale.ROOT));
		int tagW = Math.min(w - 18, renderer.getWidth(tagText) + 10);
		VeloDraw.fillRounded(context, x + 9, y + 55, tagW, 11, 4, VeloUi.withAlpha(color, Math.round((0x1A + 0x10 * on) * in)));
		MenuKit.text(context, renderer, MenuKit.fit(renderer, tagText, w - 26), x + 14, y + 57,
				VeloUi.withAlpha(VeloAnim.lerpArgb(color, 0xFFFFFFFF, 0.45f), fade));
		if (module.beta()) {
			Text beta = MenuKit.small("BETA");
			int betaW = renderer.getWidth(beta) + 10;
			if (tagW + 4 + betaW <= w - 18) {
				VeloDraw.fillRounded(context, x + 9 + tagW + 4, y + 55, betaW, 11, 4, VeloUi.withAlpha(0xFFE0A030, Math.round(0xD0 * in)));
				MenuKit.text(context, renderer, beta, x + 14 + tagW + 4, y + 57, VeloUi.withAlpha(0xFF1A1206, fade));
			}
		}
	}

	/** The loading placeholder: the card's shapes in faint grey with a soft light sweeping across. */
	private void skeleton(DrawContext context, int alpha) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		VeloDraw.fillRounded(context, x, y, w, h, 9, VeloUi.withAlpha(0x0AFFFFFF, Math.round(0x0A * alpha / 255f) + 0));
		VeloDraw.strokeRounded(context, x, y, w, h, 9, VeloUi.withAlpha(0x12FFFFFF, Math.round(0x12 * alpha / 255f)));
		int shape = VeloUi.withAlpha(0x14FFFFFF, Math.round(0x14 * alpha / 255f));
		VeloDraw.fillRounded(context, x + 9, y + 9, 26, 26, 8, shape);
		VeloDraw.fillRounded(context, x + w - 27, y + 11, 18, 10, 5, shape);
		VeloDraw.fillRounded(context, x + 9, y + 42, Math.min(w - 30, 80), 8, 3, shape);
		VeloDraw.fillRounded(context, x + 9, y + 55, 36, 9, 3, shape);
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
