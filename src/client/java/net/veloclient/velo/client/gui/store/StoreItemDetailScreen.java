package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
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
		addDrawableChild(new VeloButton(contentX(), doneY, contentWidth(), 20, Text.literal("Back"), b -> requestClose()));
	}

	private void onAction() {
		if (StoreOwnership.owns(item.id())) {
			CapeManager.equip(findLibraryIdFor(item.id()));
			status = Text.literal("Equipped \"" + item.name() + "\"");
			return;
		}
		StorePurchase.Result result = StorePurchase.buy(item);
		switch (result) {
			case SUCCESS -> {
				status = Text.literal("Purchased \"" + item.name() + "\" - added to your cape library!");
				layoutContent();
			}
			case INSUFFICIENT_COINS -> status = Text.literal("Not enough Velo Coins - you have " + CurrencyManager.balance() + ".");
			case ALREADY_OWNED -> {
				status = Text.literal("Already owned.");
				layoutContent();
			}
			case IMPORT_FAILED -> status = Text.literal("Purchase failed - refunded. Check the log for details.");
		}
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

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		Theme theme = ThemeManager.active();

		int previewWidth = contentWidth() * 2 / 5;
		int infoX = contentX() + previewWidth + 16;
		int infoWidth = contentX() + contentWidth() - infoX;
		int y = contentY();

		context.drawTextWithShadow(this.textRenderer, item.name(), infoX, y, theme.accentStart());
		y += 14;

		for (String line : wrap(item.description(), infoWidth)) {
			context.drawTextWithShadow(this.textRenderer, line, infoX, y, theme.text());
			y += 11;
		}
		y += 6;
		String priceLine = StoreOwnership.owns(item.id()) ? "You own this cape." : "Price: " + item.priceCoins() + " Velo Coins";
		context.drawTextWithShadow(this.textRenderer, priceLine, infoX, y, 0xFFF7D774);

		if (actionButton != null && !status.getString().isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, status, infoX, actionButton.getY() - 12, theme.text());
		}
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
