package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.economy.CurrencyManager;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.List;

/**
 * Velo Coins packages (design spec's store currency purchase flow). There's
 * no payment backend yet, so per explicit product direction this is a stub:
 * clicking a package grants the coins immediately, no real charge - a
 * placeholder for when real billing is wired up.
 */
public final class BuyCoinsScreen extends VeloWindow {

	private record Package(int coins, int bonusCoins, String priceLabel) {
	}

	private static final List<Package> PACKAGES = List.of(
			new Package(500, 0, "$4.99"),
			new Package(1200, 140, "$9.99"),
			new Package(2600, 400, "$19.99"),
			new Package(6500, 1500, "$39.99"));

	private Text status = Text.literal("");

	public BuyCoinsScreen(Screen parent) {
		super(Text.literal("Buy Velo Coins"), 360, 320);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int y = contentY() + 14;
		int rowHeight = 40;
		for (Package pack : PACKAGES) {
			int total = pack.coins() + pack.bonusCoins();
			String label = total + " Velo Coins" + (pack.bonusCoins() > 0 ? "  (+" + pack.bonusCoins() + " bonus)" : "") + "  -  " + pack.priceLabel();
			VeloButton button = new VeloButton(contentX(), y, contentWidth(), rowHeight - 8, Text.literal(label), b -> {
				CurrencyManager.grant(total);
				status = Text.literal("Added " + total + " Velo Coins! New balance: " + CurrencyManager.balance());
			});
			addDrawableChild(button);
			y += rowHeight;
		}
		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, contentWidth(), 20, Text.literal("Back"), b -> requestClose()));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		Theme theme = ThemeManager.active();
		context.drawTextWithShadow(this.textRenderer, "No real payment is charged yet - this just grants the coins.",
				contentX(), contentY(), theme.text());
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 34, theme.accentStart());
	}
}
