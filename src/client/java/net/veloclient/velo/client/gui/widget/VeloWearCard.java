package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.wear.WearManager;

/**
 * A bodywear item in the Store: its picture (rendered in Blender) floating on a glow in its own colour, lifting on
 * hover, with the name and the price - or "Owned" / "Wearing". Click opens its page with the live try-on.
 */
public final class VeloWearCard extends ClickableWidget {

	private static final String[] SLOT_NAMES = {"Hat", "Ears", "Glasses", "Mask", "Neck", "Watch", "Shoes", "Back"};

	private final WearManager.WearType type;
	private final Runnable onOpen;
	private final int accent;
	private final float phase;
	private float hover;

	public VeloWearCard(int width, int height, WearManager.WearType type, Runnable onOpen) {
		super(0, 0, width, height, Text.literal(type.name()));
		this.type = type;
		this.onOpen = onOpen;
		this.accent = type.accent() == null ? 0xFFFF4D4D : type.accent();
		this.phase = (type.id().hashCode() & 0xFF) / 40f;
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
		int lift = StoreCard.frame(context, x, y, w, h, hover, accent);
		int area = h - StoreCard.INFO_H - 4;
		float bob = (float) Math.sin(System.currentTimeMillis() / 900.0 + phase) * 2f;
		float grow = 1f + 0.06f * hover;
		int pw = Math.round(Math.min(w - 10, area * 4 / 3f) * grow);
		int ph = pw * 3 / 4;
		int px = x + (w - pw) / 2;
		int py = y - lift + 3 + (area - ph) / 2 + Math.round(bob);
		Identifier picture = WearManager.previewTexture(type.id());
		if (picture != null) {
			context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, picture, px, py, 0f, 0f, pw, ph, pw, ph, pw, ph);
		}
		String state = type.id().equals(WearManager.equipped(type.slot())) ? "Wearing" : WearManager.owns(type.id()) ? "Owned" : null;
		String kicker = "Bodywear · " + SLOT_NAMES[Math.max(0, WearManager.SLOTS.indexOf(type.slot()))];
		StoreCard.info(context, textRenderer, x, y, w, h, lift, kicker, accent, type.name(), state,
				net.veloclient.velo.client.network.StoreClient.price(type.itemId(), type.price()));
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
