package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.aura.AuraManager;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.util.List;

/**
 * One aura: you wearing it in 3D (the real thing, as everyone sees it), the price and Buy (Velo Coins)
 * or Equip, and how you wear it - size, speed, density and colour (its own colours, nine others or
 * rainbow). Settings are per aura and saved right away, so you can tune one before buying it.
 */
public final class AuraDetailScreen extends VeloWindow {

	private static final int PREVIEW_WIDTH = 230;
	private static final int SWATCH = 14;

	private final AuraManager.AuraType type;
	private String status = "";
	private boolean busy;
	private int swatchX;
	private int swatchY;

	public AuraDetailScreen(Screen parent, AuraManager.AuraType type) {
		super(Text.literal(type.name()), 600, 400);
		this.type = type;
		returnTo(parent);
		AuraManager.setPreview(type.id());
		onClosed(AuraManager::clearPreview);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int x = contentX();
		addDrawableChild(new EntityPreviewWidget(x, contentY(), PREVIEW_WIDTH, contentBottom() - contentY()).spin(16f, 160f));

		int ix = x + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		boolean owned = AuraManager.owns(type.id());
		boolean equipped = type.id().equals(AuraManager.equippedId());
		int price = StoreClient.price(type.itemId(), type.price());
		VeloButton action = new VeloButton(ix, contentY() + 64, iw, 20, Text.literal(busy ? "..." : equipped ? "Take it off"
				: owned ? "Equip" : String.format(java.util.Locale.ROOT, "Buy for %,d Velo Coins", price)), b -> action(owned, equipped));
		if (!equipped) {
			action.primary();
		}
		addDrawableChild(action);

		int sy = contentY() + 108;
		List<Object[]> sliders = List.of(
				new Object[] {"Size", 0.5, 1.6, 0},
				new Object[] {"Speed", 0.25, 2.5, 1},
				new Object[] {"Density", 0.25, 2.0, 2});
		for (int i = 0; i < sliders.size(); i++) {
			Object[] row = sliders.get(i);
			int field = (int) row[3];
			addDrawableChild(new VeloSlider(ix, sy + i * 26, iw, 20, (String) row[0], (double) row[1], (double) row[2], () -> get(field),
					v -> set(field, (float) v), v -> Math.round(v * 100) / 100.0, v -> Math.round(v * 100) + "%"));
		}
		swatchX = ix;
		swatchY = sy + 3 * 26 + 16;
		addDrawableChild(new VeloButton(ix, swatchY + SWATCH + 12, (iw - 8) / 2, 18, Text.literal("Reset settings"), b -> {
			AuraManager.setSettings(type.id(), AuraEffects.Settings.DEFAULT);
			layoutContent();
		}));
	}

	private double get(int field) {
		AuraEffects.Settings s = AuraManager.settings(type.id());
		return switch (field) {
			case 0 -> s.size();
			case 1 -> s.speed();
			default -> s.density();
		};
	}

	private void set(int field, float v) {
		AuraEffects.Settings s = AuraManager.settings(type.id());
		AuraManager.setSettings(type.id(), new AuraEffects.Settings(field == 0 ? v : s.size(), field == 1 ? v : s.speed(),
				field == 2 ? v : s.density(), s.color()));
	}

	private void setColor(int color) {
		AuraEffects.Settings s = AuraManager.settings(type.id());
		AuraManager.setSettings(type.id(), new AuraEffects.Settings(s.size(), s.speed(), s.density(), color));
	}

	private void action(boolean owned, boolean equipped) {
		if (busy) {
			return;
		}
		if (equipped) {
			AuraManager.equip(null);
			status = "Aura off";
			layoutContent();
			return;
		}
		if (owned) {
			AuraManager.equip(type.id());
			status = "Equipped - other Velo Client players see it too";
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
			AuraManager.equip(type.id());
			status = "It's yours - equipped!";
			layoutContent();
		}, error -> {
			busy = false;
			status = error;
			layoutContent();
		});
	}

	/** Swatch {@code i}: 0 = the aura's own colours, then the palette, then rainbow. */
	private int swatchColor(int i) {
		if (i == 0) {
			return 0xFF000000 | AuraEffects.accent(type.kind(), AuraEffects.Settings.DEFAULT);
		}
		return i <= AuraEffects.PALETTE.length ? 0xFF000000 | AuraEffects.PALETTE[i - 1] : 0xFFFFFFFF;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		for (int i = 0; i <= AuraEffects.RAINBOW; i++) {
			int sx = swatchX + i * (SWATCH + 5);
			if (click.x() >= sx && click.x() < sx + SWATCH && click.y() >= swatchY && click.y() < swatchY + SWATCH) {
				setColor(i);
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int ix = contentX() + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		int y = contentY();
		int accent = type.accent();
		String tag = "AURA";
		VeloDraw.fillRounded(context, ix, y, this.textRenderer.getWidth(tag) + 12, 12, 4, VeloUi.withAlpha(accent, 0xD0));
		context.drawTextWithShadow(this.textRenderer, tag, ix + 5, y + 2, 0xFF101014);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(type.name(), iw), ix, y + 18, VeloStyle.text());
		List<String> lines = VeloUi.wrap(type.description(), iw);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), ix, y + 32 + i * 10, VeloStyle.textMuted());
		}
		context.drawTextWithShadow(this.textRenderer, "How you wear it (this aura only)", ix, y + 95, VeloStyle.textMuted());
		// Colours: the aura's own, nine more, rainbow.
		int current = AuraManager.settings(type.id()).color();
		context.drawTextWithShadow(this.textRenderer, "Colour", swatchX, swatchY - 11, VeloStyle.textMuted());
		for (int i = 0; i <= AuraEffects.RAINBOW; i++) {
			int sx = swatchX + i * (SWATCH + 5);
			if (i == AuraEffects.RAINBOW) {
				// A little rainbow: four bands.
				int[] bands = {0xFFFF4D4D, 0xFFFFD84D, 0xFF3FF0FF, 0xFFA65CFF};
				for (int b = 0; b < 4; b++) {
					context.fill(sx + b * SWATCH / 4, swatchY, sx + (b + 1) * SWATCH / 4, swatchY + SWATCH, bands[b]);
				}
			} else {
				VeloDraw.fillRounded(context, sx, swatchY, SWATCH, SWATCH, 4, swatchColor(i));
			}
			if (i == 0) {
				context.drawTextWithShadow(this.textRenderer, "A", sx + 4, swatchY + 3, 0xFF101014);
			}
			if (i == current) {
				VeloDraw.strokeRounded(context, sx - 2, swatchY - 2, SWATCH + 4, SWATCH + 4, 5, 0xFFFFFFFF);
			}
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, iw), ix, contentBottom() - 12, VeloStyle.text());
	}
}
