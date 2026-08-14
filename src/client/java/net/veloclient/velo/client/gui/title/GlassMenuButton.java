package net.veloclient.velo.client.gui.title;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * The Store button on the title screen/escape menu: renders through the
 * exact same {@link TitleScreenTheme#drawGlassButton} call the repositioned
 * real vanilla buttons use, so it's pixel-identical to them (same corner
 * radius, same fill/border/hover treatment) instead of picking up {@code
 * VeloButton}'s own separate styling, which uses the active theme's
 * uncapped corner radius and looked visibly different sitting in the same
 * stack.
 */
public final class GlassMenuButton extends ClickableWidget {

	private final Consumer<GlassMenuButton> onPress;

	public GlassMenuButton(int x, int y, int width, int height, Text message, Consumer<GlassMenuButton> onPress) {
		super(x, y, width, height, message);
		this.onPress = onPress;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		if (this.active && this.visible) {
			MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			onPress.accept(this);
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		boolean hovered = this.active && isHovered();
		TitleScreenTheme.drawGlassButton(context, MinecraftClient.getInstance().textRenderer,
				new TitleScreenTheme.Layout(getX(), getY(), getWidth(), getHeight()), getMessage(), hovered, this.active);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
