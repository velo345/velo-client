package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
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

import java.util.function.Consumer;

/**
 * A sidebar nav row: icon + label, no individual border - rows read as one
 * continuous connected list (a single selected/hovered background tint and
 * a left accent bar) instead of a stack of separately-bordered buttons.
 */
public final class VeloNavButton extends ClickableWidget {

	private static final int ICON_SOURCE_SIZE = 1024;
	private static final int ICON_SIZE = 14;

	private final Identifier icon;
	private final Consumer<VeloNavButton> onPress;
	private boolean selected;
	private float hoverProgress;
	private float selectProgress = -1;
	private long lastNanos = -1;

	public VeloNavButton(int x, int y, int width, int height, Identifier icon, Text message, Consumer<VeloNavButton> onPress) {
		super(x, y, width, height, message);
		this.icon = icon;
		this.onPress = onPress;
	}

	public VeloNavButton selected(boolean selected) {
		this.selected = selected;
		return this;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		onPress.accept(this);
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		long now = System.nanoTime();
		float dt = lastNanos < 0 ? 0f : (now - lastNanos) / 1_000_000_000f;
		lastNanos = now;
		if (selectProgress < 0) {
			selectProgress = selected ? 1f : 0f;
		}
		hoverProgress = VeloAnim.step(hoverProgress, isHovered() ? 1f : 0f, dt);
		selectProgress = VeloAnim.step(selectProgress, selected ? 1f : 0f, dt);

		int selectedBg = VeloAnim.lerpArgb(theme.accentStart(), 0xFF000000, 0.55f);
		int hoverBg = VeloAnim.lerpArgb(theme.surfaceWithOpacity(), 0xFFFFFFFF, 0.06f);
		int bg = VeloAnim.lerpArgb(VeloAnim.lerpArgb(0x00FFFFFF, hoverBg, hoverProgress), selectedBg, selectProgress);
		if ((bg >>> 24) > 2) {
			VeloDraw.fillRounded(context, getX(), getY(), getWidth(), getHeight(), 5, bg);
		}

		if (selectProgress > 0.01f) {
			int barHeight = Math.round((getHeight() - 6) * selectProgress);
			int barY = getY() + (getHeight() - barHeight) / 2;
			VeloDraw.fillRounded(context, getX(), barY, 2, barHeight, 1, theme.accentStart());
		}

		int iconX = getX() + 8;
		int iconY = getY() + (getHeight() - ICON_SIZE) / 2;
		int iconTint = VeloAnim.lerpArgb(0xFF000000 | (theme.text() & 0xFFFFFF), theme.accentStart(), selectProgress);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, iconX, iconY, 0f, 0f,
				ICON_SIZE, ICON_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, iconTint);

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int textColor = VeloAnim.lerpArgb(theme.text(), 0xFFFFFFFF, selectProgress * 0.4f);
		context.drawTextWithShadow(textRenderer, TitleScreenTheme.bodyFont(getMessage()),
				iconX + ICON_SIZE + 6, getY() + (getHeight() - 8) / 2, textColor);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
