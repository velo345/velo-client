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

	private static final int TOGGLE_STRIP_HEIGHT = 20;
	private static final int TOGGLE_WIDTH = 26;
	private static final int TOGGLE_HEIGHT = 12;

	private final Module module;
	private final Runnable onOpenSettings;
	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private float hoverProgress;
	private float knobProgress = -1;
	private long lastNanos = -1;
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
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, newValue ? 1.1f : 0.9f));
		} else {
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			onOpenSettings.run();
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		long now = System.nanoTime();
		float dt = lastNanos < 0 ? 0f : (now - lastNanos) / 1_000_000_000f;
		lastNanos = now;

		// New tiles (category switch, search filter) pop in via scale+slide
		// instead of appearing instantly.
		float entrance = entranceProgress();
		context.getMatrices().pushMatrix();
		float centerX = getX() + getWidth() / 2f;
		float centerY = getY() + getHeight() / 2f;
		context.getMatrices().translate(centerX, centerY + (1f - entrance) * 6f);
		context.getMatrices().scale(0.85f + 0.15f * entrance, 0.85f + 0.15f * entrance);
		context.getMatrices().translate(-centerX, -centerY);

		int iconHeight = iconAreaHeight();
		boolean iconHovered = isHovered() && mouseY < getY() + iconHeight;
		hoverProgress = VeloAnim.step(hoverProgress, iconHovered ? 1f : 0f, dt);

		boolean enabled = getter.getAsBoolean();
		int base = enabled ? blend(theme.accentStart(), theme.surfaceWithOpacity(), 0.55f) : theme.surfaceWithOpacity();
		int hoverColor = VeloAnim.lerpArgb(base, 0xFFFFFFFF, 0.10f);
		int iconBg = VeloAnim.lerpArgb(base, hoverColor, hoverProgress);
		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), iconHeight, 5, iconBg);
		int accentEdge = enabled ? theme.accentStart() : 0x33FFFFFF;
		VeloDraw.strokeRect(context, getX(), getY(), getWidth(), iconHeight, accentEdge);

		var textRenderer = MinecraftClient.getInstance().textRenderer;
		float iconScale = Math.min(2.2f, (iconHeight - 18) / 16f);
		int iconCenterX = getX() + getWidth() / 2;
		int iconCenterY = getY() + (iconHeight - 12) / 2;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(iconCenterX, iconCenterY);
		context.getMatrices().scale(iconScale, iconScale);
		int iconTint = enabled ? (0xFF000000 | (theme.text() & 0xFFFFFF)) : 0xAAFFFFFF;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, iconTexture, -8, -8, 0f, 0f, 16, 16,
				ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, iconTint);
		context.getMatrices().popMatrix();
		if (!enabled) {
			VeloDraw.fillRounded(context, getX() + 3, getY() + 3, getWidth() - 6, iconHeight - 15, 4, 0x77000000);
		}

		String name = trimToWidth(textRenderer, module.displayName(), getWidth() - 6);
		int nameWidth = textRenderer.getWidth(name);
		context.drawTextWithShadow(textRenderer, name, getX() + (getWidth() - nameWidth) / 2,
				getY() + iconHeight - 12, theme.text());

		renderToggle(context, theme, enabled, dt);
		context.getMatrices().popMatrix();
	}

	private float entranceProgress() {
		float ageSeconds = (System.nanoTime() - spawnNanos) / 1_000_000_000f;
		float t = Math.min(1f, ageSeconds / 0.18f);
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	private void renderToggle(DrawContext context, Theme theme, boolean enabled, float dt) {
		if (knobProgress < 0) {
			knobProgress = enabled ? 1f : 0f;
		}
		knobProgress = VeloAnim.step(knobProgress, enabled ? 1f : 0f, dt);

		int trackX = getX() + (getWidth() - TOGGLE_WIDTH) / 2;
		int trackY = getY() + iconAreaHeight() + (TOGGLE_STRIP_HEIGHT - TOGGLE_HEIGHT) / 2;
		int off = 0xFF3A3230;
		int trackColor = VeloAnim.lerpArgb(off, theme.accentStart(), knobProgress);
		VeloDraw.fillRounded(context, trackX, trackY, TOGGLE_WIDTH, TOGGLE_HEIGHT, TOGGLE_HEIGHT / 2, trackColor);
		int knobSize = TOGGLE_HEIGHT - 4;
		int knobX = trackX + 2 + Math.round((TOGGLE_WIDTH - knobSize - 4) * knobProgress);
		VeloDraw.fillRounded(context, knobX, trackY + 2, knobSize, knobSize, knobSize / 2, 0xFFFFFFFF);
	}

	private static int blend(int a, int b, float t) {
		return VeloAnim.lerpArgb(a, b, 1 - t);
	}

	private static String trimToWidth(net.minecraft.client.font.TextRenderer renderer, String text, int maxWidth) {
		if (renderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String trimmed = text;
		while (trimmed.length() > 1 && renderer.getWidth(trimmed + "..") > maxWidth) {
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
