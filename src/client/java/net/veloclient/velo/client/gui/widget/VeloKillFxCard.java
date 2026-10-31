package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.killfx.KillFxManager;

/**
 * A kill effect in the Store: the effect itself, looping on a dim figure (see {@link KillFxGuiRenderer}), with the name
 * and the price - or "Owned" / "Equipped". Click opens its page.
 */
public final class VeloKillFxCard extends ClickableWidget {

	private final KillFxManager.KillFxType type;
	private final Runnable onOpen;
	private final KillFxGuiRenderer.Loop loop;
	private float hover;

	public VeloKillFxCard(int width, int height, KillFxManager.KillFxType type, Runnable onOpen) {
		super(0, 0, width, height, Text.literal(type.name()));
		this.type = type;
		this.onOpen = onOpen;
		this.loop = new KillFxGuiRenderer.Loop(type.kind());
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
		float scale = Math.min(area / 4.6f, w / 5.2f);
		context.enableScissor(x + 1, y - lift + 1, x + w - 1, y - lift + area - 1);
		KillFxGuiRenderer.draw(context, loop, KillFxManager.owns(type.id()) ? KillFxManager.settings(type.id())
				: net.veloclient.velo.client.cosmetics.aura.AuraEffects.Settings.DEFAULT, x + w / 2f, y - lift + area - area * 0.22f, scale, 0.5);
		context.disableScissor();

		String state = type.id().equals(KillFxManager.equippedId()) ? "Equipped" : KillFxManager.owns(type.id()) ? "Owned" : null;
		StoreCard.info(context, textRenderer, x, y, w, h, lift, "Kill Effect", accent, type.name(), state,
				net.veloclient.velo.client.network.StoreClient.price(type.itemId(), type.price()));
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
