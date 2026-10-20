package net.veloclient.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.data.WingsCatalog;
import net.veloclient.launcher.social.StoreApi;

import java.util.List;
import java.util.Locale;

/**
 * One pair of wings in the launcher's Store: your own skin wearing them in 3D, flapping the way the
 * game animates them - pick Idle / Walk / Fly / Glide / Sneak / Swim to see each - with the price and
 * Buy (Velo Coins), Equip, Gift and Wishlist. Size, flap speed and the rest are tuned per pair in the game.
 */
public final class WingDetailView {

	public interface Host {
		MinecraftSession session();

		void goBack();

		default void openCoins() {
		}
	}

	private WingDetailView() {
	}

	public static Node build(Host host, WingsCatalog.WingType type) {
		StackPane stage = new StackPane();
		stage.setMinSize(320, 380);
		ImageView still = new ImageView(UiImages.wingPreview(type.id()));
		still.setFitHeight(360);
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
		HBox motions = new HBox(2);
		motions.getStyleClass().add("segment-bar");
		motions.setAlignment(Pos.CENTER);
		motions.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
		List<Button> chips = new java.util.ArrayList<>();
		for (Wings3D.Motion m : Wings3D.Motion.values()) {
			Button chip = new Button(m.name().charAt(0) + m.name().substring(1).toLowerCase(Locale.ROOT));
			chip.getStyleClass().setAll("button", "segmented-button");
			if (m == motion[0]) {
				chip.getStyleClass().add("segmented-button-active");
			}
			chip.setOnAction(e -> {
				motion[0] = m;
				for (Button other : chips) {
					other.getStyleClass().remove("segmented-button-active");
				}
				chip.getStyleClass().add("segmented-button-active");
				if (wings[0] != null) {
					wings[0].motion(m);
				}
			});
			chips.add(chip);
			motions.getChildren().add(chip);
		}

		int price = StoreApi.price(type.itemId(), type.price());
		String crumb = "Wings" + WingsCatalog.collection(type.collection()).map(c -> "  ·  " + c.name()).orElse("");
		String feel = switch (type.style()) {
			case "bat" -> "Leathery, snappy wingbeats";
			case "crystal" -> "Stiff, slow and heavy wingbeats";
			default -> "Soft, flowing feathers";
		};
		List<String> bullets = List.of(feel + ", animated with how you move", "Seen by every Velo Client player",
				"Size, height, spread and flap speed adjustable in-game");
		ItemPage.Purchase purchase = new ItemPage.Purchase(type.itemId(), price,
				() -> StoreOwnership.owns(type.itemId()),
				() -> type.id().equals(WingsCatalog.equipped()),
				() -> WingsCatalog.equip(type.id()),
				() -> WingsCatalog.equip(null),
				() -> {
					try {
						StoreApi.buy(type.itemId());
						return null;
					} catch (StoreApi.StoreError error) {
						return error.getMessage();
					}
				}, "wings", type.inStore());
		return ItemPage.build(host::goBack, host::openCoins, stage, motions,
				host.session() == null ? "Sign in to see them on your own skin" : "Drag to rotate  ·  scroll to zoom",
				type.accent(), crumb, StoreTiles.Rarity.of(type.itemId(), price), type.name(), type.description(), bullets, null, purchase);
	}
}
