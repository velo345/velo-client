package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.aura.AuraManager;

/**
 * An aura in the Store: the aura itself, live, around a dim player shape (see {@link AuraGuiRenderer}),
 * with the name and the price - or "Owned" / "Equipped". Click opens its page with the 3D preview.
 */
public final class VeloAuraCard extends ClickableWidget {

	private final AuraManager.AuraType type;
	private final Runnable onOpen;
	private final AuraEffects.Emitter emitter;
	private float hover;

	public VeloAuraCard(int width, int height, AuraManager.AuraType type, Runnable onOpen) {
		super(0, 0, width, height, Text.literal(type.name()));
		this.type = type;
		this.onOpen = onOpen;
		this.emitter = new AuraEffects.Emitter(type.kind());
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
		if (hover > 0.01f) {
			VeloDraw.shadow(context, x, y, w, h, VeloStyle.RADIUS_CARD, 10, 4, VeloUi.withAlpha(accent, Math.round(0x50 * hover)));
		}
		VeloDraw.fillRounded(context, x, y, w, h, VeloStyle.RADIUS_CARD, 0xFF101016);
		VeloDraw.fillRoundedGradient(context, x, y, w, h - 30, VeloStyle.RADIUS_CARD, VeloUi.withAlpha(accent, 0x22), 0x00101016);
		VeloDraw.strokeRounded(context, x, y, w, h, VeloStyle.RADIUS_CARD, VeloUi.withAlpha(accent, 0x40 + Math.round(0x80 * hover)));
		int area = h - 34;
		float scale = Math.min(area / 2.6f, w / 2.6f);
		context.enableScissor(x + 1, y + 1, x + w - 1, y + area + 4);
		AuraGuiRenderer.draw(context, emitter, AuraManager.owns(type.id()) ? AuraManager.settings(type.id()) : AuraEffects.Settings.DEFAULT,
				x + w / 2f, y + area - 2, scale);
		context.disableScissor();

		String state = type.id().equals(AuraManager.equippedId()) ? "Equipped" : AuraManager.owns(type.id()) ? "Owned" : null;
		context.drawTextWithShadow(textRenderer, VeloUi.trim(type.name(), w - 12), x + 6, y + h - 25, 0xFFFFFFFF);
		if (state != null) {
			context.drawTextWithShadow(textRenderer, state, x + 6, y + h - 13, 0xFF8FE3A8);
		} else {
			int price = net.veloclient.velo.client.network.StoreClient.price(type.itemId(), type.price());
			VeloDraw.fillCircle(context, x + 10f, y + h - 9.5f, 3.6f, 0xFFC9861A);
			VeloDraw.fillCircle(context, x + 10f, y + h - 9.5f, 2.7f, 0xFFFFD56A);
			context.drawTextWithShadow(textRenderer, String.format(java.util.Locale.ROOT, "%,d", price), x + 17, y + h - 13, 0xFFFFD56A);
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
