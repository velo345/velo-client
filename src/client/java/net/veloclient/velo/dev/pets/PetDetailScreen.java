package net.veloclient.velo.dev.pets;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.util.List;

/** One pet in the Store: its turntable on a dark stage, the price and Buy (Velo Coins) - or take it out / put it away. */
final class PetDetailScreen extends VeloWindow {

	private static final int PREVIEW_WIDTH = 250;

	private final CosmeticsCatalog.Item item;
	private final String petId;
	private String status = "";
	private boolean busy;

	PetDetailScreen(Screen parent, CosmeticsCatalog.Item item) {
		super(Text.literal(item.name()), 620, 380);
		this.item = item;
		this.petId = PetStore.petId(item);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int ix = contentX() + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		boolean owned = StoreClient.owns(item.id());
		boolean out = petId.equals(PetsModule.pet());
		VeloButton action = new VeloButton(ix, contentY() + 70, iw, 20, Text.literal(busy ? "..." : out ? "Put it away"
				: owned ? "Take it out" : StoreClient.buyLabel(PetStore.price(item))), b -> action(owned, out));
		if (!out) {
			action.primary();
		}
		addDrawableChild(action);
	}

	private void action(boolean owned, boolean out) {
		if (busy) {
			return;
		}
		if (out) {
			PetsModule.setPet("");
			status = "Put away";
			layoutContent();
			return;
		}
		if (owned) {
			takeOut();
			return;
		}
		if (!StoreClient.connected()) {
			status = "Connect to Velo Network first (in a world with a Microsoft account)";
			return;
		}
		busy = true;
		status = "Buying...";
		layoutContent();
		StoreClient.buyOrClaim(item.id(), () -> {
			busy = false;
			takeOut();
			status = "It's yours - " + item.name() + " follows you now!";
		}, error -> {
			busy = false;
			status = error;
			layoutContent();
		});
	}

	private void takeOut() {
		PetsModule.setPet(petId);
		PetsModule.enableIfOff();
		status = item.name() + " follows you now";
		layoutContent();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int px = contentX();
		int py = contentY();
		int ph = contentBottom() - contentY();
		VeloDraw.fillRounded(context, px, py, PREVIEW_WIDTH, ph, 8, 0xFF0E0E14);
		int size = Math.min(PREVIEW_WIDTH - 8, ph - 8);
		context.enableScissor(px + 1, py + 1, px + PREVIEW_WIDTH - 1, py + ph - 1);
		if (!PetPreviews.draw(context, petId, px + (PREVIEW_WIDTH - size) / 2, py + (ph - size) / 2, size)) {
			String loading = "Loading the preview...";
			context.drawTextWithShadow(this.textRenderer, loading, px + (PREVIEW_WIDTH - this.textRenderer.getWidth(loading)) / 2, py + ph / 2 - 4, VeloStyle.textMuted());
		}
		context.disableScissor();

		int ix = contentX() + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		int y = contentY();
		int accent = PetStore.accent(item);
		String tag = "PET";
		VeloDraw.fillRounded(context, ix, y, this.textRenderer.getWidth(tag) + 12, 12, 4, VeloUi.withAlpha(accent, 0xD0));
		context.drawTextWithShadow(this.textRenderer, tag, ix + 5, y + 2, 0xFF101014);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(item.name(), iw), ix, y + 18, VeloStyle.text());
		List<String> lines = VeloUi.wrap(item.description() == null ? "" : item.description(), iw);
		for (int i = 0; i < Math.min(3, lines.size()); i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), ix, y + 32 + i * 10, VeloStyle.textMuted());
		}
		List<String> how = VeloUi.wrap("Follows you everywhere, does tricks when you stand still, sits on your shoulder and celebrates your kills."
				+ " Every Velo player around you sees it. Size and tricks: the Pets module.", iw);
		for (int i = 0; i < Math.min(4, how.size()); i++) {
			context.drawTextWithShadow(this.textRenderer, how.get(i), ix, y + 100 + i * 10, VeloStyle.textMuted());
		}
		if (!status.isEmpty()) {
			for (String line : VeloUi.wrap(status, iw)) {
				context.drawTextWithShadow(this.textRenderer, line, ix, contentBottom() - 14, VeloStyle.text());
				break;
			}
		}
	}
}
