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
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.module.Module;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A Lunar/Feather-style module tile: a big icon (click to open settings)
 * with the module's name and a small on/off switch fixed at the bottom -
 * enabling/disabling never requires opening settings first.
 */
public final class VeloModuleTile extends ClickableWidget {

	private static final int TOGGLE_STRIP_HEIGHT = 22;
	private static final int TOGGLE_WIDTH = 22;
	private static final int TOGGLE_HEIGHT = 12;

	private final Module module;
	private final Runnable onOpenSettings;
	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private float hoverProgress;
	private float knobProgress = -1;
	private long lastNanos;
	private long pressedAt;
	private float stripHover;
	private final Identifier iconTexture;
	private static final int ICON_SOURCE_SIZE = 1024;
	private final long spawnNanos = System.nanoTime();

	public VeloModuleTile(int x, int y, int size, Module module, Runnable onOpenSettings) {
		super(x, y, size, size + TOGGLE_STRIP_HEIGHT, Text.literal(module.displayName()));
		this.module = module;
		this.onOpenSettings = onOpenSettings;
		this.getter = module::isEnabled;
		this.setter = module::setEnabled;
		this.iconTexture = ModuleIcons.textureFor(module.id());
	}

	private int iconAreaHeight() {
		return getHeight() - TOGGLE_STRIP_HEIGHT;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		int localY = (int) click.y() - getY();
		MinecraftClient client = MinecraftClient.getInstance();
		if (localY >= iconAreaHeight()) {
			boolean newValue = !getter.getAsBoolean();
			setter.accept(newValue);
			net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
			pressedAt = System.nanoTime();
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, newValue ? 1.1f : 0.9f));
		} else {
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			pressedAt = System.nanoTime();
			onOpenSettings.run();
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		lastNanos = now;

		boolean enabled = getter.getAsBoolean();
		if (knobProgress < 0) {
			knobProgress = enabled ? 1f : 0f;
		}
		knobProgress = VeloAnim.step(knobProgress, enabled ? 1f : 0f, dt);
		int iconHeight = iconAreaHeight();
		boolean hovered = isHovered();
		boolean stripHovered = hovered && mouseY >= getY() + iconHeight;
		hoverProgress = VeloAnim.step(hoverProgress, hovered ? 1f : 0f, dt);
		stripHover = VeloAnim.step(stripHover, stripHovered ? 1f : 0f, dt);

		// New tiles (category switch, search filter) pop in via scale+slide instead of appearing
		// instantly; hovered tiles lift slightly; clicks give a short press pulse.
		float entrance = entranceProgress();
		float scale = (0.88f + 0.12f * entrance) * VeloStyle.pressScale(pressedAt);
		float centerX = getX() + getWidth() / 2f;
		float centerY = getY() + getHeight() / 2f;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(centerX, centerY + (1f - entrance) * 8f - hoverProgress * 1.5f);
		context.getMatrices().scale(scale, scale);
		context.getMatrices().translate(-centerX, -centerY);

		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		int radius = VeloStyle.RADIUS_CARD;
		float on = knobProgress;

		if (hoverProgress > 0.01f) {
			VeloDraw.shadow(context, x, y, w, h, radius, 8, 3, VeloUi.withAlpha(0xFF000000, Math.round(0x60 * hoverProgress)));
		}
		int card = VeloAnim.lerpArgb(VeloStyle.card(), VeloStyle.cardHover(), hoverProgress);
		int top = VeloAnim.lerpArgb(card, VeloStyle.accentSoft(0.22f), on);
		VeloDraw.fillRoundedGradient(context, x, y, w, h, radius, top, card);
		int border = VeloAnim.lerpArgb(VeloAnim.lerpArgb(VeloStyle.border(), VeloStyle.borderStrong(), hoverProgress),
				VeloUi.withAlpha(theme.accentStart(), 0xA0), on);
		VeloDraw.strokeRounded(context, x, y, w, h, radius, border);

		// Icon on a soft rounded chip.
		int chip = 34;
		int chipX = x + (w - chip) / 2;
		int chipY = y + 12;
		int chipColor = VeloAnim.lerpArgb(VeloUi.withAlpha(theme.text(), 0x0E), VeloUi.withAlpha(theme.accentStart(), 0x30), on);
		VeloDraw.fillRounded(context, chipX, chipY, chip, chip, 10, chipColor);
		int iconSize = 20;
		int iconTint = VeloAnim.lerpArgb(VeloUi.withAlpha(theme.text(), 0xB4),
				VeloAnim.lerpArgb(theme.accentStart() | 0xFF000000, 0xFFFFFFFF, 0.25f), on);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, iconTexture, chipX + (chip - iconSize) / 2, chipY + (chip - iconSize) / 2,
				0f, 0f, iconSize, iconSize, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, iconTint);

		var textRenderer = MinecraftClient.getInstance().textRenderer;
		String trimmedName = trimToWidth(textRenderer, module.displayName(), w - 10);
		Text name = TitleScreenTheme.tileFont(trimmedName);
		int nameWidth = textRenderer.getWidth(name);
		int nameColor = VeloAnim.lerpArgb(VeloStyle.textMuted(), VeloStyle.text(), Math.max(on, hoverProgress));
		context.drawTextWithShadow(textRenderer, name, x + (w - nameWidth) / 2, chipY + chip + 8, nameColor);

		renderToggleStrip(context, theme, enabled);
		context.getMatrices().popMatrix();
	}

	private float entranceProgress() {
		float ageSeconds = (System.nanoTime() - spawnNanos) / 1_000_000_000f;
		float t = Math.min(1f, ageSeconds / 0.22f);
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	/** Bottom strip: status word on the left, switch on the right; the whole strip toggles. */
	private void renderToggleStrip(DrawContext context, Theme theme, boolean enabled) {
		int x = getX();
		int w = getWidth();
		int stripY = getY() + iconAreaHeight();
		context.fill(x + 6, stripY, x + w - 6, stripY + 1, VeloStyle.border());
		if (stripHover > 0.01f) {
			VeloDraw.fillRounded(context, x + 3, stripY + 3, w - 6, TOGGLE_STRIP_HEIGHT - 6, 5,
					VeloUi.withAlpha(theme.text(), Math.round(0x10 * stripHover)));
		}

		var textRenderer = MinecraftClient.getInstance().textRenderer;
		Text status = TitleScreenTheme.bodyFont(Text.literal(enabled ? "On" : "Off"));
		int statusColor = VeloAnim.lerpArgb(VeloStyle.textFaint(), theme.accentStart() | 0xFF000000, knobProgress);
		context.drawTextWithShadow(textRenderer, status, x + 9, stripY + (TOGGLE_STRIP_HEIGHT - 8) / 2, statusColor);

		float trackX = x + w - 9 - TOGGLE_WIDTH;
		float trackY = stripY + (TOGGLE_STRIP_HEIGHT - TOGGLE_HEIGHT) / 2f;
		VeloToggle.drawSwitch(context, trackX, trackY, TOGGLE_WIDTH, TOGGLE_HEIGHT, knobProgress, stripHover);
	}

	private static int blend(int a, int b, float t) {
		return VeloAnim.lerpArgb(a, b, 1 - t);
	}

	/** Measures against the Anta tile-title font itself (not the default font) so the trim boundary matches what's actually drawn - a mismatch here is exactly what left names clipped before. */
	private static String trimToWidth(net.minecraft.client.font.TextRenderer renderer, String text, int maxWidth) {
		if (renderer.getWidth(TitleScreenTheme.tileFont(text)) <= maxWidth) {
			return text;
		}
		String trimmed = text;
		while (trimmed.length() > 1 && renderer.getWidth(TitleScreenTheme.tileFont(trimmed + "..")) > maxWidth) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed + "..";
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
		builder.put(NarrationPart.HINT, getter.getAsBoolean() ? "on" : "off");
	}
}
