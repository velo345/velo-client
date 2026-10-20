package net.veloclient.velo.client.gui.title;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * A clickable area for one of Velo's own entries on the title / pause menu (Store, Friends, a quick toggle...).
 * It draws nothing itself: {@link TitleMenu} / {@link PauseMenu} paint every row, Velo's and vanilla's alike, so
 * they look the same; this only takes the click (and keyboard focus and narration) at the right place.
 */
public final class HotspotButton extends ClickableWidget {

	private final Runnable onPress;
	/** A real button this one presses instead (the real one stays where another mod expects it). */
	private ClickableWidget forward;

	public HotspotButton(Text message, Runnable onPress) {
		super(-10000, -10000, 1, 1, message);
		this.onPress = onPress;
	}

	/** The real button this one presses, or null. */
	public ClickableWidget forward() {
		return forward;
	}

	/** Clicks go to {@code widget} (null: the Runnable). */
	public HotspotButton forwardTo(ClickableWidget widget) {
		this.forward = widget;
		return this;
	}

	/** Puts it at a rectangle for this frame (or hides it with {@code show == false}). */
	public void place(int x, int y, int width, int height, boolean show) {
		setX(show ? x : -10000);
		setY(show ? y : -10000);
		setWidth(Math.max(1, width));
		setHeight(Math.max(1, height));
		this.visible = show;
		this.active = show;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		if (this.active && this.visible) {
			MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			if (forward != null) {
				if (forward.active) {
					forward.onClick(click, doubled);
				}
			} else if (onPress != null) {
				onPress.run();
			}
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		// Painted by the menu.
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
