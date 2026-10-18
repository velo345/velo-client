package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.CurrencyStore;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.data.WingsCatalog;
import net.veloclient.launcher.social.StoreApi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * One pair of wings in the launcher's Store: your own skin wearing them in 3D, flapping the way the
 * game animates them - pick Idle / Walk / Fly / Glide / Sneak / Swim to see each - with the price and
 * Buy (Velo Coins) or Equip. Size, flap speed and the rest are tuned per pair in the game's Store.
 */
public final class WingDetailView {

	public interface Host {
		MinecraftSession session();

		void goBack();
	}

	private WingDetailView() {
	}

	public static Node build(Host host, WingsCatalog.WingType type) {
		Button back = BackButton.create("Store", null);
		back.setOnAction(e -> host.goBack());

		StackPane stage = CosmeticUi.stage(360, 440);
		stage.getStyleClass().add("showcase-stage");
		ImageView still = new ImageView(new Image(WingsCatalog.openPreview(type.id())));
		still.setFitWidth(320);
		still.setPreserveRatio(true);
		stage.getChildren().add(still);
		Wings3D[] wings = new Wings3D[1];
		Wings3D.Motion[] motion = {Wings3D.Motion.FLY};
		CosmeticUi.skin(host.session(), skin -> {
			Node viewer = skin == null ? null : PlayerSkin3DView.createWingsShowcase(skin.pngBytes(), skin.slim(), w -> {
				wings[0] = w.motion(motion[0]);
				w.show(type.id());
			});
			if (viewer != null) {
				stage.getChildren().setAll(viewer);
			}
		});
		HBox motions = new HBox(6);
		motions.setAlignment(Pos.CENTER);
		java.util.List<Button> chips = new java.util.ArrayList<>();
		for (Wings3D.Motion m : Wings3D.Motion.values()) {
			Button chip = new Button(m.name().charAt(0) + m.name().substring(1).toLowerCase(java.util.Locale.ROOT));
			chip.getStyleClass().add(m == motion[0] ? "primary-button" : "ghost-button");
			chip.setOnAction(e -> {
				motion[0] = m;
				for (Button other : chips) {
					other.getStyleClass().removeAll("primary-button", "ghost-button");
					other.getStyleClass().add(other == chip ? "primary-button" : "ghost-button");
				}
				if (wings[0] != null) {
					wings[0].motion(m);
				}
			});
			chips.add(chip);
			motions.getChildren().add(chip);
		}
		Label hint = new Label(host.session() == null ? "Sign in to see them on your own skin" : "Drag to rotate  ·  scroll to zoom");
		hint.getStyleClass().add("stage-hint");
		VBox preview = new VBox(10, stage, motions, hint);
		preview.setAlignment(Pos.TOP_CENTER);

		boolean owned = StoreOwnership.owns(type.itemId());
		int price = StoreApi.price(type.itemId(), type.price());
		Label tag = new Label(net.veloclient.launcher.data.WingsCatalog.collection(type.collection())
				.map(c -> c.name().toUpperCase(java.util.Locale.ROOT) + "  ·  WINGS").orElse("WINGS"));
		tag.getStyleClass().add("featured-tag");
		Label name = new Label(type.name());
		name.getStyleClass().add("featured-title");
		Label description = new Label(type.description());
		description.getStyleClass().add("page-subtitle");
		description.setWrapText(true);
		description.setMaxWidth(400);
		Label style = new Label(switch (type.style()) {
			case "bat" -> "Leathery, snappy wingbeats";
			case "crystal" -> "Stiff, slow and heavy";
			default -> "Soft, flowing feathers";
		} + "  ·  animated with how you move  ·  seen by every Velo Client player");
		style.getStyleClass().add("page-subtitle");
		style.setWrapText(true);
		style.setMaxWidth(400);
		Label balance = new Label("You have " + CurrencyStore.balance() + " coins");
		balance.getStyleClass().add("page-subtitle");
		HBox priceRow = new HBox(12, CosmeticUi.priceChip(price, owned), balance);
		priceRow.setAlignment(Pos.CENTER_LEFT);
		Label status = new Label();
		status.getStyleClass().add("purchase-status");
		status.setWrapText(true);

		Button action = new Button();
		action.getStyleClass().add("primary-button");
		action.setMinHeight(46);
		action.setPrefWidth(280);
		Runnable label = () -> action.setText(type.id().equals(WingsCatalog.equipped()) ? "Take them off"
				: StoreOwnership.owns(type.itemId()) ? "Equip wings" : String.format(java.util.Locale.ROOT, "Buy for %,d coins", price));
		label.run();
		action.setOnAction(e -> {
			if (type.id().equals(WingsCatalog.equipped())) {
				WingsCatalog.equip(null);
				status.setText("Wings off.");
				label.run();
				return;
			}
			if (StoreOwnership.owns(type.itemId())) {
				WingsCatalog.equip(type.id());
				status.setText("Equipped - they show the next time you start the game.");
				label.run();
				return;
			}
			action.setDisable(true);
			status.setText("Buying...");
			CompletableFuture.supplyAsync(() -> {
				try {
					StoreApi.buy(type.itemId());
					return null;
				} catch (StoreApi.StoreError error) {
					return error.getMessage();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(error -> Platform.runLater(() -> {
				action.setDisable(false);
				if (error == null) {
					WingsCatalog.equip(type.id());
					status.setText("They're yours - equipped for your next game.");
					priceRow.getChildren().set(0, CosmeticUi.priceChip(price, true));
					balance.setText("You have " + CurrencyStore.balance() + " coins");
				} else {
					status.setText(error);
				}
				label.run();
			}));
		});
		Label tune = new Label("Size, height, spread and flap speed are set per pair in the in-game Store.");
		tune.getStyleClass().add("page-subtitle");
		tune.setWrapText(true);
		tune.setMaxWidth(400);

		VBox info = new VBox(12, tag, name, description, style, priceRow, action, status, tune);
		info.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(info, Priority.ALWAYS);
		HBox body = new HBox(32, preview, info);
		body.setAlignment(Pos.CENTER_LEFT);
		return new VBox(16, back, body);
	}
}
