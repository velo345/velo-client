package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * A non-interactive, word-wrapped line (or few lines) of text that can still
 * be dropped into a {@link VeloScrollRegion} as an ordinary row - useful for
 * inline notices/section headers that need to sit at a specific point in an
 * otherwise all-widget list, rather than being drawn separately and having
 * to fight the row layout's fixed row height to line up with it.
 */
public final class VeloLabel extends ClickableWidget {

	private final int color;

	public VeloLabel(int x, int y, int width, int height, String text, int color) {
		super(x, y, width, height, Text.literal(text));
		this.color = color;
		this.active = false;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		// Intentionally inert - a label, not a control.
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int lineY = getY();
		for (var line : textRenderer.wrapLines(getMessage(), getWidth())) {
			context.drawTextWithShadow(textRenderer, line, getX(), lineY, color);
			lineY += textRenderer.fontHeight + 1;
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
	}
}
