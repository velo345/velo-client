package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/** A small muted, letter-spaced group label inside a list (e.g. "MODULES" / "TOOLS" in the sidebar). Not clickable. */
public final class VeloSectionHeader extends ClickableWidget {

	public VeloSectionHeader(int x, int y, int width, int height, String label) {
		super(x, y, width, height, Text.literal(label));
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		return false;
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		String label = getMessage().getString().toUpperCase(java.util.Locale.ROOT);
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int x = getX() + 9;
		int y = getY() + getHeight() - 11;
		// Hand-tracked letter spacing reads as a proper "overline" caption rather than shouting caps.
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x, y);
		context.getMatrices().scale(0.8f, 0.8f);
		int cursor = 0;
		for (int i = 0; i < label.length(); i++) {
			String ch = String.valueOf(label.charAt(i));
			context.drawTextWithShadow(textRenderer, ch, cursor, 0, VeloStyle.textFaint());
			cursor += textRenderer.getWidth(ch) + 1;
		}
		context.getMatrices().popMatrix();
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
