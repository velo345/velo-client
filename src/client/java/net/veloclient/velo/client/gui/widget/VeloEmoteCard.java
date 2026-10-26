package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.emotes.EmoteCatalog;

/**
 * An emote in the Store: a little figure doing it, live, with its particles - then the name and the price, or
 * "Owned" / "On wheel". Click opens its page with the 3D preview.
 */
public final class VeloEmoteCard extends ClickableWidget {

	private final EmoteCatalog.EmoteType type;
	private final Runnable onOpen;
	private float hover;

	public VeloEmoteCard(int width, int height, EmoteCatalog.EmoteType type, Runnable onOpen) {
		super(0, 0, width, height, Text.literal(type.name()));
		this.type = type;
		this.onOpen = onOpen;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		onOpen.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, 0.016f * 6);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		int accent = type.accent();
		int lift = StoreCard.frame(context, x, y, w, h, hover, accent);
		int area = h - StoreCard.INFO_H;
		float scale = Math.min((area - 10) / 40f, w / 30f);
		context.enableScissor(x + 1, y - lift + 1, x + w - 1, y - lift + area - 1);
		EmoteFigure.draw(context, this, type.id(), x + w / 2f, y - lift + area - 5, scale, accent);
		context.disableScissor();

		boolean owned = EmoteCatalog.owns(type.id());
		String state = !owned ? null : EmoteCatalog.slotOf(type.id()) >= 0 ? "On wheel" : "Owned";
		StoreCard.info(context, textRenderer, x, y, w, h, lift, "Emote", accent, type.name(), state,
				net.veloclient.velo.client.network.StoreClient.price(type.itemId(), type.price()));
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
