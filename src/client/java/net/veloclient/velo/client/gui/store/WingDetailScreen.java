package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;
import net.veloclient.velo.client.cosmetics.wings.WingsPreview;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.widget.VeloWingCard;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.util.List;

/**
 * One pair of wings: you wearing them in 3D (drag to turn, scroll to zoom), how they move - idle,
 * walking, flying, gliding, sneaking, swimming - the price and Buy (Velo Coins) or Equip, and how you
 * wear this pair: size, height, distance from your back, spread, flap speed and strength. Each pair
 * keeps its own settings; they're saved right away, so you can tune a pair before buying it.
 */
public final class WingDetailScreen extends VeloWindow {

	private static final int PREVIEW_WIDTH = 230;

	private final WingsManager.WingType type;
	private final int accent;
	private final int key = (int) (System.nanoTime() & 0xFFFF) - 70_000;
	private WingsPreview.Showcase showcase = WingsPreview.Showcase.IDLE;
	private String status = "";
	private boolean busy;

	public WingDetailScreen(Screen parent, WingsManager.WingType type) {
		super(Text.literal(type.name()), 600, 420);
		this.type = type;
		this.accent = VeloWingCard.accentFor(type.id());
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int x = contentX();
		addDrawableChild(new EntityPreviewWidget(x, contentY(), PREVIEW_WIDTH, contentBottom() - contentY() - 24)
				.wings(() -> new WingsPreview.Tag(new WingsManager.Worn(type.id(), WingsManager.settings(type.id())), showcase, key))
				.spin(16f, 160f).fallbackImage(() -> VeloWingCard.previewImage(type.id())));
		// How they move.
		int chipW = (PREVIEW_WIDTH - 10) / 6;
		WingsPreview.Showcase[] all = WingsPreview.Showcase.values();
		for (int i = 0; i < all.length; i++) {
			WingsPreview.Showcase s = all[i];
			VeloButton chip = new VeloButton(x + i * (chipW + 2), contentBottom() - 18, chipW, 16, Text.literal(s.label), b -> {
				showcase = s;
				layoutContent();
			});
			if (s == showcase) {
				chip.primary();
			}
			addDrawableChild(chip);
		}

		int ix = x + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		boolean owned = WingsManager.owns(type.id());
		boolean equipped = type.id().equals(WingsManager.equippedId());
		int price = StoreClient.price(type.itemId(), type.price());
		VeloButton action = new VeloButton(ix, contentY() + 74, iw, 20, Text.literal(busy ? "..." : equipped ? "Take them off"
				: owned ? "Equip" : String.format(java.util.Locale.ROOT, "Buy for %,d Velo Coins", price)), b -> action(owned, equipped));
		if (!equipped) {
			action.primary();
		}
		addDrawableChild(action);

		int sy = contentY() + 118;
		int half = (iw - 8) / 2;
		List<Object[]> sliders = List.of(
				new Object[] {"Size", 0.4, 2.0, 0, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"},
				new Object[] {"Height", -0.5, 0.5, 1, (java.util.function.DoubleFunction<String>) v -> String.format(java.util.Locale.ROOT, "%+d px", Math.round(v * 16))},
				new Object[] {"Distance", -0.2, 0.5, 2, (java.util.function.DoubleFunction<String>) v -> String.format(java.util.Locale.ROOT, "%+d px", Math.round(v * 16))},
				new Object[] {"Spread", -30.0, 60.0, 3, (java.util.function.DoubleFunction<String>) v -> Math.round(v) + "°"},
				new Object[] {"Flap speed", 0.25, 3.0, 4, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"},
				new Object[] {"Flap strength", 0.0, 2.0, 5, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"});
		for (int i = 0; i < sliders.size(); i++) {
			Object[] row = sliders.get(i);
			int field = (int) row[3];
			@SuppressWarnings("unchecked")
			java.util.function.DoubleFunction<String> format = (java.util.function.DoubleFunction<String>) row[4];
			addDrawableChild(new VeloSlider(ix + (i % 2) * (half + 8), sy + (i / 2) * 26, half, 20, (String) row[0], (double) row[1],
					(double) row[2], () -> get(field), v -> set(field, (float) v), v -> Math.round(v * 100) / 100.0, format));
		}
		addDrawableChild(new VeloButton(ix, sy + 82, half, 18, Text.literal("Reset settings"), b -> {
			WingsManager.setSettings(type.id(), WingsManager.Settings.DEFAULT);
			layoutContent();
		}));
	}

	private void action(boolean owned, boolean equipped) {
		if (busy) {
			return;
		}
		if (equipped) {
			WingsManager.equip(null);
			status = "Wings off";
			layoutContent();
			return;
		}
		if (owned) {
			WingsManager.equip(type.id());
			status = "Equipped - other Velo Client players see them too";
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
		StoreClient.buy(type.itemId(), () -> {
			busy = false;
			WingsManager.equip(type.id());
			status = "They're yours - equipped!";
			layoutContent();
		}, error -> {
			busy = false;
			status = error;
			layoutContent();
		});
	}

	private double get(int field) {
		WingsManager.Settings s = WingsManager.settings(type.id());
		return switch (field) {
			case 0 -> s.scale();
			case 1 -> -s.offsetY();
			case 2 -> s.offsetZ();
			case 3 -> s.spread();
			case 4 -> s.flapSpeed();
			default -> s.flapStrength();
		};
	}

	/** Height is shown "up = +" (the body's y axis points down). */
	private void set(int field, float v) {
		WingsManager.Settings s = WingsManager.settings(type.id());
		WingsManager.setSettings(type.id(), new WingsManager.Settings(field == 0 ? v : s.scale(), field == 1 ? -v : s.offsetY(),
				field == 2 ? v : s.offsetZ(), field == 3 ? v : s.spread(), field == 4 ? v : s.flapSpeed(), field == 5 ? v : s.flapStrength()));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int ix = contentX() + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		int y = contentY();
		String collection = WingsManager.collection(type.collection()).map(c -> c.name().toUpperCase(java.util.Locale.ROOT)).orElse("WINGS");
		VeloDraw.fillRounded(context, ix, y, this.textRenderer.getWidth(collection) + 12, 12, 4, VeloUi.withAlpha(accent, 0xD0));
		context.drawTextWithShadow(this.textRenderer, collection, ix + 5, y + 2, 0xFF101014);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(type.name(), iw), ix, y + 18, VeloStyle.text());
		String style = switch (type.style()) {
			case BAT -> "Leathery, snappy wingbeats";
			case CRYSTAL -> "Stiff, slow and heavy";
			default -> "Soft, flowing feathers";
		};
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(style + "  ·  animated with how you move", iw), ix, y + 30, VeloStyle.textFaint());
		List<String> lines = VeloUi.wrap(type.description(), iw);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), ix, y + 44 + i * 10, VeloStyle.textMuted());
		}
		context.drawTextWithShadow(this.textRenderer, "How you wear them (this pair only)", ix, y + 104, VeloStyle.textMuted());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, iw), ix, contentBottom() - 12, VeloStyle.text());
	}
}
