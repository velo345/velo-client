package net.veloclient.velo.dev.pets;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;
import net.veloclient.velo.client.gui.widget.StoreCard;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.network.StoreClient;

/** A pet in the Store: its turntable preview, the name and the price - or "Owned" / "With you". Click opens its page. */
final class VeloPetCard extends ClickableWidget {

	private final CosmeticsCatalog.Item item;
	private final Runnable onOpen;
	private float hover;

	VeloPetCard(int width, int height, CosmeticsCatalog.Item item, Runnable onOpen) {
		super(0, 0, width, height, Text.literal(item.name()));
		this.item = item;
		this.onOpen = onOpen;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		onOpen.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, 0.016f * 6);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		int accent = PetStore.accent(item);
		int lift = StoreCard.frame(context, x, y, w, h, hover, accent);
		int area = h - StoreCard.INFO_H;
		int size = Math.min(w - 4, area - 4);
		context.enableScissor(x + 1, y - lift + 1, x + w - 1, y - lift + area - 1);
		PetPreviews.draw(context, PetStore.petId(item), x + (w - size) / 2, y - lift + (area - size) / 2, size);
		context.disableScissor();
		String id = PetStore.petId(item);
		String state = id.equals(PetsModule.pet()) ? "With you" : StoreClient.owns(item.id()) ? "Owned" : null;
		StoreCard.info(context, MinecraftClient.getInstance().textRenderer, x, y, w, h, lift, "Pet", accent, item.name(), state, PetStore.price(item));
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
