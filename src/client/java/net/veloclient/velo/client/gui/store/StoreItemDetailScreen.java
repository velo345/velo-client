package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.economy.CurrencyManager;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.store.StoreAssets;
import net.veloclient.velo.client.store.StoreItem;
import net.veloclient.velo.client.store.StoreOwnership;
import net.veloclient.velo.client.store.StorePurchase;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * "Try before you buy" for one {@link StoreItem}: a live 3D preview of the
 * local player wearing it (via {@link CapeManager}'s preview override, set
 * for the lifetime of this screen and cleared the moment it closes - Esc,
 * Done, or navigating away all go through {@link #onClosed}) alongside its
 * title, description, price, and a Buy/Equip button.
 */
public final class StoreItemDetailScreen extends VeloWindow {

	private final StoreItem item;
	private Text status = Text.literal("");
	private VeloButton actionButton;

	public StoreItemDetailScreen(Screen parent, StoreItem item) {
		super(Text.literal(item.name()), 480, 360);
		this.item = item;
		returnTo(parent);
		try {
			CapeManager.setPreviewOverride(
					CapeManager.previewDefinitionFor(item.id(), item.name(), StoreAssets.openGif(item), CapePhysicsPreset.defaults()));
		} catch (java.io.IOException e) {
			net.veloclient.velo.VeloClient.LOGGER.error("Failed to build Store preview for {}", item.id(), e);
		}
		onClosed(CapeManager::clearPreviewOverride);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();

		int previewWidth = contentWidth() * 2 / 5;
		int previewX = contentX();

		int doneY = contentBottom() - 20;
		// A real gap (was 4px) between the action and Back rows - close
		// enough before that the new bordered button style read as the two
		// touching/overlapping rather than as two separate rows.
		int actionY = doneY - 32;
		previewBottom = actionY - 10;
		// The Back row spans the *full* content width (both columns), so the
		// preview box has to stop above it - it used to run the full content
		// height down to contentBottom(), which put its bottom edge directly
		// under (visually, "inside") the Back button.
		int previewHeight = actionY - 10 - contentY();
		addDrawableChild(new EntityPreviewWidget(previewX, contentY(), previewWidth, previewHeight));

		int infoX = previewX + previewWidth + 16;
		int infoWidth = contentX() + contentWidth() - infoX;

		boolean owned = StoreOwnership.owns(item.id());
		actionButton = new VeloButton(infoX, actionY, infoWidth, 20,
				Text.literal(owned ? "Equip" : "Buy for " + item.priceCoins() + " Velo Coins"), b -> onAction());
		if (!owned) {
			actionButton.primary();
		}
		addDrawableChild(actionButton);
		addDrawableChild(new VeloButton(contentX(), doneY, 80, 20, Text.literal("Back"), b -> requestClose()));
	}

	private boolean busy;

	private void onAction() {
		if (StoreOwnership.owns(item.id())) {
			String id = findLibraryIdFor(item.id());
			if (id == null) {
				net.veloclient.velo.client.store.StoreRestore.restoreMissing();
				id = findLibraryIdFor(item.id());
			}
			if (id != null) {
				CapeManager.equip(id);
				status = Text.literal("Equipped \"" + item.name() + "\"");
			}
			return;
		}
		if (busy) {
			return;
		}
		busy = true;
		status = Text.literal("Buying...");
		StorePurchase.buy(item, error -> {
			busy = false;
			if (error == null) {
				status = Text.literal("Purchased \"" + item.name() + "\" - it's in your cape library!");
				layoutContent();
			} else if (error.startsWith("Not enough")) {
				status = Text.literal(error + " - get more under Velo Coins.");
			} else {
				status = Text.literal(error);
			}
		});
	}

	/** {@link StorePurchase#buy} imports the cape under a fresh library id (not the catalog item's own id), so equipping the just-bought item has to look that library entry back up by name. */
	private String findLibraryIdFor(String itemId) {
		var item = net.veloclient.velo.client.store.StoreCatalog.byId(itemId).orElseThrow();
		return CapeManager.library().values().stream()
				.filter(def -> def.animated() && def.name().equals(item.name()))
				.reduce((first, second) -> second) // most-recently-imported match wins if bought more than once
				.map(net.veloclient.velo.client.cosmetics.CapeDefinition::id)
				.orElse(null);
	}

	private int previewBottom;

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int previewWidth = contentWidth() * 2 / 5;
		int infoX = contentX() + previewWidth + 16;
		int infoWidth = contentX() + contentWidth() - infoX;
		int y = contentY() + 4;

		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont("CAPE  ·  ANIMATED"),
				infoX, y, VeloStyle.accent());
		y += 14;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(infoX, y);
		context.getMatrices().scale(1.6f, 1.6f);
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont(VeloUi.trim(item.name(), (int) (infoWidth / 1.6f))), 0, 0, VeloStyle.text());
		context.getMatrices().popMatrix();
		y += 22;
		for (String line : wrap(item.description(), infoWidth)) {
			context.drawTextWithShadow(this.textRenderer, line, infoX, y, VeloStyle.textMuted());
			y += 11;
		}
		y += 8;
		// Price chip (or "Owned").
		boolean owned = StoreOwnership.owns(item.id());
		String price = owned ? "Owned" : String.valueOf(item.priceCoins());
		int chipW = this.textRenderer.getWidth(price) + (owned ? 16 : 28);
		VeloDraw.fillRounded(context, infoX, y, chipW, 16, 8, owned ? VeloUi.withAlpha(0xFF3FB97A, 0x40) : VeloAnim.lerpArgb(VeloStyle.card(), 0xFFF4B82E, 0.14f));
		if (owned) {
			context.drawTextWithShadow(this.textRenderer, price, infoX + 8, y + 4, 0xFF6FE0A4);
		} else {
			VeloDraw.fillCircle(context, infoX + 9f, y + 8f, 5f, 0xFFC9861A);
			VeloDraw.fillCircle(context, infoX + 9f, y + 8f, 3.8f, 0xFFFFD56A);
			context.drawTextWithShadow(this.textRenderer, price, infoX + 18, y + 4, 0xFFFFD56A);
			context.drawTextWithShadow(this.textRenderer, "You have " + CurrencyManager.balance(), infoX + chipW + 8, y + 4, VeloStyle.textFaint());
		}

		if (actionButton != null && !status.getString().isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, status, infoX, actionButton.getY() - 12, VeloStyle.textMuted());
		}
		context.drawTextWithShadow(this.textRenderer, "Drag to rotate", contentX() + previewWidth / 2 - this.textRenderer.getWidth("Drag to rotate") / 2,
				previewBottom - 14, VeloStyle.textFaint());
	}

	private List<String> wrap(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (this.textRenderer.getWidth(candidate) > maxWidth && !current.isEmpty()) {
				lines.add(current.toString());
				current = new StringBuilder(word);
			} else {
				current = new StringBuilder(candidate);
			}
		}
		if (!current.isEmpty()) {
			lines.add(current.toString());
		}
		return lines;
	}
}
