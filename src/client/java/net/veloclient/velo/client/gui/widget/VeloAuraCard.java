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
		int lift = StoreCard.frame(context, x, y, w, h, hover, accent);
		int area = h - StoreCard.INFO_H;
		float scale = Math.min(area / 2.6f, w / 2.6f);
		context.enableScissor(x + 1, y - lift + 1, x + w - 1, y - lift + area - 1);
		AuraGuiRenderer.draw(context, emitter, AuraManager.owns(type.id()) ? AuraManager.settings(type.id()) : AuraEffects.Settings.DEFAULT,
				x + w / 2f, y - lift + area - 6, scale);
		context.disableScissor();

		String state = type.id().equals(AuraManager.equippedId()) ? "Equipped" : AuraManager.owns(type.id()) ? "Owned" : null;
		StoreCard.info(context, textRenderer, x, y, w, h, lift, "Aura", accent, type.name(), state,
				net.veloclient.velo.client.network.StoreClient.price(type.itemId(), type.price()));
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
