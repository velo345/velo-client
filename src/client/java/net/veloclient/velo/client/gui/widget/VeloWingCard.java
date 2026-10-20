package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;

/**
 * A pair of wings in the Store: the pair's picture (rendered from the model) gently floating on a
 * glow in its own colour, lifting on hover, with the name and the price - or "Owned" / "Equipped".
 * Click opens the pair's page with the live 3D preview. Cards deliberately don't each render a 3D
 * player: every GUI entity preview is its own offscreen render target, and a page of them ran a
 * small GPU out of memory - one live preview per screen.
 */
public final class VeloWingCard extends ClickableWidget {

	private final WingsManager.WingType type;
	private final Runnable onOpen;
	private final int accent;
	private final float phase;
	private float hover;

	public VeloWingCard(int width, int height, WingsManager.WingType type, Runnable onOpen) {
		super(0, 0, width, height, Text.literal(type.name()));
		this.type = type;
		this.onOpen = onOpen;
		this.accent = accentFor(type.id());
		this.phase = (type.id().hashCode() & 0xFF) / 40f;
	}

	/** The pair's still picture (rendered by tools/cosmetics/render_wing_previews.py); null while a server-only one downloads. */
	public static Identifier previewImage(String id) {
		return WingsManager.previewTexture(id);
	}

	/** A colour that matches each pair, for glows and accents ({@code accent} in wings.json). */
	public static int accentFor(String id) {
		return WingsManager.type(id).map(WingsManager.WingType::accent).orElse(0xFFFF4D4D);
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
		// The picture, 4:3, floating slowly (each card a little out of step) and growing on hover.
		int area = h - StoreCard.INFO_H - 4;
		float bob = (float) Math.sin(System.currentTimeMillis() / 900.0 + phase) * 2f;
		float grow = 1f + 0.06f * hover;
		int pw = Math.round(Math.min(w - 10, area * 4 / 3f) * grow);
		int ph = pw * 3 / 4;
		int px = x + (w - pw) / 2;
		int py = y - lift + 3 + (area - ph) / 2 + Math.round(bob);
		Identifier picture = previewImage(type.id());
		if (picture != null) {
			context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, picture, px, py, 0f, 0f, pw, ph, pw, ph, pw, ph);
		}

		String state = WingsManager.equippedId() != null && WingsManager.equippedId().equals(type.id()) ? "Equipped"
				: WingsManager.owns(type.id()) ? "Owned" : null;
		String kicker = type.collection() != null && !type.collection().isBlank()
				? "Wings · " + WingsManager.collection(type.collection()).map(WingsManager.Collection::name).orElse(type.collection()) : "Wings";
		StoreCard.info(context, textRenderer, x, y, w, h, lift, kicker, accent, type.name(), state,
				net.veloclient.velo.client.network.StoreClient.price(type.itemId(), type.price()));
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
