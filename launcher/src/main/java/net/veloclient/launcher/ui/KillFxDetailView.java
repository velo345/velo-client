package net.veloclient.launcher.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.KillFxCatalog;
import net.veloclient.launcher.social.StoreApi;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.killfx.KillFxEffects;

/**
 * One kill effect in the launcher's Store: it plays over and over (the same animation the game runs, with its sound
 * on Replay), the price and Buy (Velo Coins) or Equip, and its colour - saved for the game.
 */
public final class KillFxDetailView {

	public interface Host {
		MinecraftSession session();

		void goBack();

		default void openCoins() {
		}
	}

	private KillFxDetailView() {
	}

	public static Node build(Host host, KillFxCatalog.KillFxType type) {
		AuraEffects.Settings[] current = {KillFxCatalog.settings(type.id())};
		boolean[] sound = {false};
		StackPane stage = CosmeticUi.stage(420, 480);
		stage.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
		Node card = AuraFx.killFxCard(type.kind(), () -> current[0], 420, 480, () -> {
			if (sound[0]) {
				sound[0] = false;
				KillFxSound.play(type.id());
			}
		});
		stage.getChildren().add(card);
		int price = StoreApi.price(type.itemId(), type.price());
		ItemPage.Purchase purchase = new ItemPage.Purchase(type.itemId(), price,
				() -> KillFxCatalog.owns(type.id()),
				() -> type.id().equals(KillFxCatalog.equipped()),
				() -> KillFxCatalog.equip(type.id()),
				() -> KillFxCatalog.equip(null),
				() -> {
					try {
						StoreApi.buy(type.itemId());
						return null;
					} catch (StoreApi.StoreError error) {
						return error.getMessage();
					}
				}, "kill effect", type.inStore());
		VBox extra = new VBox(8);
		Button replay = new Button("Play with sound");
		replay.getStyleClass().add("secondary-button");
		replay.setOnAction(e -> {
			// From the top, the sound starting with it.
			KillFxSound.stop();
			sound[0] = true;
			AuraFx.restartKillFx(card);
		});
		extra.getChildren().addAll(replay, volume(), colours(type, s -> current[0] = s));
		return ItemPage.build(host::goBack, host::openCoins, stage, null, "Plays where your kills fall in game", type.accent(), "Kill Effects",
				StoreTiles.Rarity.of(type.itemId(), price), type.name(), type.description(),
				java.util.List.of("Custom particles and its own sound", "Your colour", "Plays on your kills (Kill Effects module in game)"), extra, purchase);
	}

	/** The kill effects' volume, in the game and here. */
	static Node volume() {
		Label label = new Label();
		label.getStyleClass().add("page-subtitle");
		javafx.scene.control.Slider slider = new javafx.scene.control.Slider(0, 1.5, KillFxCatalog.volume());
		slider.setMaxWidth(Double.MAX_VALUE);
		Runnable text = () -> label.setText("Volume  " + Math.round(slider.getValue() * 100) + "%");
		text.run();
		slider.valueProperty().addListener((obs, o, n) -> text.run());
		slider.valueChangingProperty().addListener((obs, was, now) -> {
			if (!now) {
				KillFxCatalog.setVolume((float) slider.getValue());
			}
		});
		slider.setOnMouseReleased(e -> KillFxCatalog.setVolume((float) slider.getValue()));
		return new VBox(4, label, slider);
	}

	static Node colours(KillFxCatalog.KillFxType type, java.util.function.Consumer<AuraEffects.Settings> live) {
		VBox box = new VBox(6);
		Label title = new Label("Colour");
		title.getStyleClass().add("t-heading");
		HBox swatches = new HBox(6);
		Region[] cells = new Region[AuraEffects.RAINBOW + 1];
		int[] current = {KillFxCatalog.color(type.id())};
		Runnable mark = () -> {
			for (int i = 0; i < cells.length; i++) {
				cells[i].setStyle(cells[i].getStyle().replaceAll("-fx-border-color: [^;]*;", "")
						+ "-fx-border-color: " + (i == current[0] ? "white" : "transparent") + ";");
			}
		};
		for (int i = 0; i <= AuraEffects.RAINBOW; i++) {
			int index = i;
			Region cell = new Region();
			cell.setMinSize(22, 22);
			cell.setMaxSize(22, 22);
			String fill = i == AuraEffects.RAINBOW ? "linear-gradient(to right, #ff4d4d, #ffd84d, #3ff0ff, #a65cff)"
					: String.format("#%06x", i == 0 ? KillFxEffects.accent(type.kind(), AuraEffects.Settings.DEFAULT) : AuraEffects.PALETTE[i - 1]);
			cell.setStyle("-fx-background-color: " + fill + "; -fx-background-radius: 6; -fx-border-radius: 6; -fx-border-width: 2; -fx-cursor: hand;");
			javafx.scene.control.Tooltip.install(cell, new javafx.scene.control.Tooltip(
					i == 0 ? "Its own colours" : i == AuraEffects.RAINBOW ? "Rainbow" : AuraEffects.PALETTE_NAMES[i - 1]));
			cell.setOnMouseClicked(e -> {
				current[0] = index;
				KillFxCatalog.setColor(type.id(), index);
				live.accept(KillFxCatalog.settings(type.id()));
				mark.run();
			});
			cells[i] = cell;
			swatches.getChildren().add(cell);
		}
		mark.run();
		Label note = new Label("Saved for the game - it picks it up the next time it starts.");
		note.getStyleClass().add("stage-hint");
		note.setWrapText(true);
		box.getChildren().addAll(title, swatches, note);
		return box;
	}
}
