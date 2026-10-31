package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.wear.WearManager;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.util.List;

/**
 * One bodywear item: you wearing it in 3D (tried on while this is open), the price and Buy (Velo Coins) or Wear, and
 * its colour for recolourable items - saved right away, so you can pick it before buying.
 */
public final class WearDetailScreen extends VeloWindow {

	private static final int PREVIEW_WIDTH = 230;
	private static final String[] SLOT_NAMES = {"HAT", "EARS", "GLASSES", "MASK", "NECK", "WATCH", "SHOES", "BACK"};

	private final WearManager.WearType type;
	private String status = "";
	private boolean busy;

	public WearDetailScreen(Screen parent, WearManager.WearType type) {
		super(Text.literal(type.name()), 600, 380);
		this.type = type;
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		WearManager.setPreview(type.slot(), type.id());
		int x = contentX();
		addDrawableChild(new EntityPreviewWidget(x, contentY(), PREVIEW_WIDTH, contentBottom() - contentY()).spin(16f, "back".equals(type.slot()) ? 200f : 20f)
				.fallbackImage(() -> WearManager.previewTexture(type.id())));
		int ix = x + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		boolean owned = WearManager.owns(type.id());
		boolean wearing = type.id().equals(WearManager.equipped(type.slot()));
		int price = StoreClient.price(type.itemId(), type.price());
		VeloButton action = new VeloButton(ix, contentY() + 74, iw, 20, Text.literal(busy ? "..." : wearing ? "Take it off"
				: owned ? "Wear it" : StoreClient.buyLabel(price)), b -> action(owned, wearing));
		if (!wearing) {
			action.primary();
		}
		addDrawableChild(action);
		if (type.tintable()) {
			addDrawableChild(new net.veloclient.velo.client.gui.widget.VeloColorSwatches(ix, contentY() + 124, iw, () -> WearManager.color(type.id()),
					code -> WearManager.setColor(type.id(), code)));
		}
		if (WearManager.adjustable(type.slot())) {
			int top = contentY() + 124 + (type.tintable() ? net.veloclient.velo.client.gui.widget.VeloColorSwatches.heightFor(iw) + 10 : 0);
			addDrawableChild(net.veloclient.velo.client.gui.CapeEquipScreen.heightSlider(ix, top, iw, type.id()));
			if (WearManager.depthAdjustable(type.slot())) {
				addDrawableChild(net.veloclient.velo.client.gui.CapeEquipScreen.depthSlider(ix, top + 24, iw, type.id()));
			}
		}
	}

	@Override
	public void removed() {
		WearManager.clearPreview();
		super.removed();
	}

	private void action(boolean owned, boolean wearing) {
		if (busy) {
			return;
		}
		if (wearing) {
			WearManager.equip(type.slot(), null);
			status = "Taken off";
			layoutContent();
			return;
		}
		if (owned) {
			WearManager.equip(type.slot(), type.id());
			status = "Wearing it - other Velo Client players see it too";
			layoutContent();
			return;
		}
		if (!StoreClient.connected()) {
			status = "Connect to Velo Network first (in a world with a Microsoft account)";
			return;
		}
		busy = true;
		status = "Buying...";
		layoutContent();
		StoreClient.buyOrClaim(type.itemId(), () -> {
			busy = false;
			WearManager.equip(type.slot(), type.id());
			status = "It's yours - you're wearing it!";
			layoutContent();
		}, error -> {
			busy = false;
			status = error;
			layoutContent();
		});
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int ix = contentX() + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		int y = contentY();
		int accent = type.accent() == null ? 0xFFFF4D4D : type.accent();
		int slotIndex = Math.max(0, WearManager.SLOTS.indexOf(type.slot()));
		String tag = "BODYWEAR  ·  " + SLOT_NAMES[slotIndex];
		VeloDraw.fillRounded(context, ix, y, this.textRenderer.getWidth(tag) + 12, 12, 4, VeloUi.withAlpha(accent, 0xD0));
		context.drawTextWithShadow(this.textRenderer, tag, ix + 5, y + 2, 0xFF101014);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(type.name(), iw), ix, y + 18, VeloStyle.text());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("One per slot - wear a hat, glasses, a chain, shoes and more at once", iw), ix, y + 30,
				VeloStyle.textFaint());
		List<String> lines = VeloUi.wrap(type.description(), iw);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), ix, y + 44 + i * 10, VeloStyle.textMuted());
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, iw), ix, contentBottom() - 12, VeloStyle.text());
	}
}
