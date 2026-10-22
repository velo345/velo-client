package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * A clickable block the screen paints itself (banners, bundle cards, "All auras ›" links) - a widget so it can be
 * a row of a {@link VeloScrollRegion}: positioned, clipped and only clickable while visible, like the tiles around it.
 */
public final class DrawnCard extends ClickableWidget {

	@FunctionalInterface
	public interface Painter {
		void paint(DrawContext context, int x, int y, int width, int height, float hover);
	}

	private final Painter painter;
	private final Runnable onClick;
	private float hover;

	public DrawnCard(int width, int height, String name, Painter painter, Runnable onClick) {
		super(0, 0, width, height, Text.literal(name));
		this.painter = painter;
		this.onClick = onClick;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		if (onClick != null) {
			onClick.run();
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		hover = VeloAnim.step(hover, isHovered() && onClick != null ? 1f : 0f, 0.016f * 6);
		painter.paint(context, getX(), getY(), getWidth(), getHeight(), hover);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
