package net.veloclient.velo.client.gui.store;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.veloclient.velo.client.gui.VeloConfirmScreen;
import net.veloclient.velo.client.network.StoreClient;

/** "Buy X for N Velo Coins?" before every store purchase - no more buying something with one stray click. */
public final class PurchaseConfirm {

	private PurchaseConfirm() {
	}

	public static void ask(Screen parent, String name, int price, Runnable buy) {
		boolean gift = StoreClient.welcomeGift();
		String title = gift ? "Claim \"" + name + "\" for free?" : "Buy \"" + name + "\"?";
		String message = gift
				? "This uses your one free welcome gift - you can only pick one item this way."
				: String.format(java.util.Locale.ROOT, "This costs %,d Velo Coins. You have %,d - you'll have %,d left.",
						price, StoreClient.balance(), Math.max(0, StoreClient.balance() - price));
		MinecraftClient.getInstance().setScreen(new VeloConfirmScreen(parent, title, message, gift ? "Claim" : "Buy", buy));
	}
}
