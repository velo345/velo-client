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
	private long lastNanos;
	private long pressedAt;

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
		pressedAt = System.nanoTime();
		onPress.accept(this);
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		lastNanos = now;
		if (selectProgress < 0) {
			selectProgress = selected ? 1f : 0f;
		}
		hoverProgress = VeloAnim.step(hoverProgress, isHovered() ? 1f : 0f, dt);
		selectProgress = VeloAnim.step(selectProgress, selected ? 1f : 0f, dt);

		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		VeloStyle.pushScale(context, x + w / 2f, y + h / 2f, VeloStyle.pressScale(pressedAt));

		float bgStrength = Math.max(hoverProgress * 0.55f, selectProgress);
		if (bgStrength > 0.01f) {
			int hoverBg = VeloUi.withAlpha(theme.text(), 0x12);
			int selectedBg = VeloUi.withAlpha(theme.accentStart(), 0x2E);
			int bg = VeloAnim.lerpArgb(hoverBg, selectedBg, selectProgress);
			VeloDraw.fillRounded(context, x, y, w, h, 7, VeloUi.withAlpha(bg, Math.round(((bg >>> 24) & 0xFF) * Math.min(1f, bgStrength))));
		}

		if (selectProgress > 0.01f) {
			float barHeight = (h - 10) * selectProgress;
			VeloDraw.fillRounded(context, (float) x + 1, y + (h - barHeight) / 2f, 3f, barHeight, 1.5f, theme.accentStart());
		}

		// Hovered rows nudge their icon/label right a touch - a small cue that the row is live.
		float nudge = hoverProgress * 1.5f;
		int iconX = x + 9;
		int iconY = y + (h - ICON_SIZE) / 2;
		int idle = VeloUi.withAlpha(theme.text(), 0xA8);
		int iconTint = VeloAnim.lerpArgb(VeloAnim.lerpArgb(idle, VeloStyle.text(), hoverProgress), theme.accentStart() | 0xFF000000, selectProgress);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(nudge, 0f);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, iconX, iconY, 0f, 0f,
				ICON_SIZE, ICON_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, iconTint);

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int textColor = VeloAnim.lerpArgb(VeloAnim.lerpArgb(idle, VeloStyle.text(), hoverProgress), 0xFFFFFFFF, selectProgress);
		context.drawTextWithShadow(textRenderer,
				VeloUi.trimStyled(getMessage(), x + w - (iconX + ICON_SIZE + 7) - 6,
						t -> selected ? TitleScreenTheme.tileFont(t.getString()) : TitleScreenTheme.bodyFont(t)),
				iconX + ICON_SIZE + 7, y + (h - 8) / 2, textColor);
		context.getMatrices().popMatrix();
		VeloStyle.popScale(context);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
