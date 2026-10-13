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

/** The "Redstone Mode" card at the bottom left of the Velo menu: a redstone-red button with icon, name and key hint. */
public final class RedstoneModeButton extends ClickableWidget {

	private static final Identifier ICON = Identifier.of("velo-client", "textures/icon/rm/redstone.png");

	private final Runnable action;
	private float hover;
	private long lastNanos;
	private long pressedAt;

	public RedstoneModeButton(int x, int y, int width, int height, Runnable action) {
		super(x, y, width, height, Text.literal("Redstone Mode"));
		this.action = action;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		pressedAt = System.nanoTime();
		action.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, VeloStyle.frameDelta(lastNanos, now));
		lastNanos = now;
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		VeloStyle.pushScale(context, x + w / 2f, y + h / 2f, VeloStyle.pressScale(pressedAt));
		int top = VeloAnim.lerpArgb(0xFFB3261E, 0xFFD8392F, hover);
		VeloDraw.fillRoundedGradient(context, x, y, w, h, 8, top, VeloAnim.lerpArgb(top, 0xFF3A0806, 0.45f));
		VeloDraw.strokeRounded(context, x, y, w, h, 8, VeloAnim.lerpArgb(0x60FF8A80, 0xC0FFB0A8, hover));
		int iconSize = h - 10;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, ICON, x + 6, y + 5, 0f, 0f, iconSize, iconSize, 64, 64, 64, 64, 0xFFFFFFFF);
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int tx = x + 6 + iconSize + 6;
		context.drawTextWithShadow(textRenderer, getMessage(), tx, y + h / 2 - 9, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal("Editor & tools · R"), tx, y + h / 2 + 2, 0xFFFFC9C4);
		VeloStyle.popScale(context);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
