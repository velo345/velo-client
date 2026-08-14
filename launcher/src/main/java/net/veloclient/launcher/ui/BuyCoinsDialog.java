package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.data.CurrencyStore;

import java.util.List;

/**
 * Velo Coins packages - mirrors the mod's own Buy Coins screen. No payment
 * backend yet, so per explicit product direction this is a stub: clicking a
 * package grants the coins immediately, no real charge.
 */
public final class BuyCoinsDialog {

	private record Package(int coins, int bonusCoins, String priceLabel) {
	}

	private static final List<Package> PACKAGES = List.of(
			new Package(500, 0, "$4.99"),
			new Package(1200, 140, "$9.99"),
			new Package(2600, 400, "$19.99"),
			new Package(6500, 1500, "$39.99"));

	private BuyCoinsDialog() {
	}

	public static void show(Stage owner) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Buy Velo Coins");
		dialog.setHeaderText("Current balance: " + CurrencyStore.balance() + " Velo Coins");
		DialogStyling.apply(dialog);

		VBox box = new VBox(10);
		box.setPadding(new Insets(16));
		Label notice = new Label("No real payment is charged yet - this just grants the coins.");
		notice.getStyleClass().add("section-subtitle");
		box.getChildren().add(notice);

		Label balanceLabel = new Label();
		for (Package pack : PACKAGES) {
			int total = pack.coins() + pack.bonusCoins();
			Button button = new Button(total + " Velo Coins" + (pack.bonusCoins() > 0 ? "  (+" + pack.bonusCoins() + " bonus)" : "")
					+ "  -  " + pack.priceLabel());
			button.getStyleClass().add("title-menu-button");
			button.setMaxWidth(Double.MAX_VALUE);
			button.setOnAction(e -> {
				CurrencyStore.grant(total);
				dialog.setHeaderText("Current balance: " + CurrencyStore.balance() + " Velo Coins");
				balanceLabel.setText("Added " + total + " Velo Coins!");
			});
			box.getChildren().add(button);
		}
		box.getChildren().add(balanceLabel);

		dialog.getDialogPane().setContent(box);
		dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
		dialog.showAndWait();
	}
}
