package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.AuraCatalog;
import net.veloclient.launcher.data.CurrencyStore;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.social.StoreApi;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One aura in the launcher's Store: your own skin wearing it in 3D (the same animation the game runs),
 * the price and Buy (Velo Coins) or Equip, and how you wear it - size, speed, density and colour,
 * changing live and saved for the game.
 */
public final class AuraDetailView {

	public interface Host {
		MinecraftSession session();

		void goBack();
	}

	private AuraDetailView() {
	}

	/** Your skin in 3D with {@code shown} around it (or the card version when signed out). */
	static Node stage(MinecraftSession session, AuraEffects.Kind kind, java.util.function.Supplier<AuraFx.Shown> shown, double w, double h) {
		StackPane stage = CosmeticUi.stage(w, h);
		stage.getStyleClass().add("showcase-stage");
		stage.getChildren().add(AuraFx.card(kind, () -> shown.get().settings(), w - 20, h - 20));
		CosmeticUi.skin(session, skin -> {
			Node viewer = skin == null ? null : PlayerSkin3DView.createShowcase(skin.pngBytes(), skin.slim(), null);
			if (viewer != null) {
				AuraFx.attach(viewer, shown);
				stage.getChildren().setAll(viewer);
			}
		});
		return stage;
	}

	public static Node build(Host host, AuraCatalog.AuraType type) {
		Button back = new Button("‹  Store");
		back.getStyleClass().add("ghost-button");
		back.setOnAction(e -> host.goBack());

		AuraEffects.Settings[] current = {AuraCatalog.settings(type.id())};
		Node stage = stage(host.session(), type.kind(), () -> new AuraFx.Shown(type.id(), current[0]), 360, 440);
		Label hint = new Label(host.session() == null ? "Sign in to see it on your own skin" : "Drag to rotate  ·  scroll to zoom");
		hint.getStyleClass().add("stage-hint");
		VBox preview = new VBox(10, stage, hint);
		preview.setAlignment(Pos.TOP_CENTER);

		boolean owned = AuraCatalog.owns(type.id());
		int price = StoreApi.price(type.itemId(), type.price());
		Label tag = new Label("AURA");
		tag.getStyleClass().add("featured-tag");
		Label name = new Label(type.name());
		name.getStyleClass().add("featured-title");
		Label description = new Label(type.description() + "  Every Velo Client player sees it.");
		description.getStyleClass().add("page-subtitle");
		description.setWrapText(true);
		description.setMaxWidth(420);
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
		Runnable label = () -> action.setText(type.id().equals(AuraCatalog.equipped()) ? "Take it off"
				: AuraCatalog.owns(type.id()) ? "Equip aura" : String.format(Locale.ROOT, "Buy for %,d coins", price));
		label.run();
		action.setOnAction(e -> {
			if (type.id().equals(AuraCatalog.equipped())) {
				AuraCatalog.equip(null);
				status.setText("Aura off.");
				label.run();
				return;
			}
			if (AuraCatalog.owns(type.id())) {
				AuraCatalog.equip(type.id());
				status.setText("Equipped - it shows the next time you start the game.");
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
					AuraCatalog.equip(type.id());
					status.setText("It's yours - equipped for your next game.");
					priceRow.getChildren().set(0, CosmeticUi.priceChip(price, true));
					balance.setText("You have " + CurrencyStore.balance() + " coins");
				} else {
					status.setText(error);
				}
				label.run();
			}));
		});

		VBox info = new VBox(12, tag, name, description, priceRow, action, status,
				settingsPanel(type.id(), type.kind(), s -> current[0] = s));
		info.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(info, Priority.ALWAYS);
		HBox body = new HBox(32, preview, info);
		body.setAlignment(Pos.CENTER_LEFT);
		return new VBox(16, back, body);
	}

	/**
	 * Size / speed / density sliders and the colour swatches for one aura: {@code live} gets every
	 * change (for the preview), the file is written when a slider is let go or a colour picked.
	 */
	static Node settingsPanel(String id, AuraEffects.Kind kind, Consumer<AuraEffects.Settings> live) {
		AuraEffects.Settings[] current = {AuraCatalog.settings(id)};
		VBox box = new VBox(6);
		Label title = new Label("How you wear it (this aura only)");
		title.getStyleClass().add("section-label");
		box.getChildren().add(title);
		Object[][] rows = {{"Size", 0.5, 1.6}, {"Speed", 0.25, 2.5}, {"Density", 0.25, 2.0}};
		for (int i = 0; i < rows.length; i++) {
			int field = i;
			double value = field == 0 ? current[0].size() : field == 1 ? current[0].speed() : current[0].density();
			Slider slider = new Slider((double) rows[i][1], (double) rows[i][2], value);
			slider.setMaxWidth(360);
			Label label = new Label();
			label.getStyleClass().add("page-subtitle");
			Runnable text = () -> label.setText(rows[field][0] + "  " + Math.round(slider.getValue() * 100) + "%");
			text.run();
			slider.valueProperty().addListener((obs, o, n) -> {
				text.run();
				AuraEffects.Settings s = current[0];
				float v = n.floatValue();
				current[0] = new AuraEffects.Settings(field == 0 ? v : s.size(), field == 1 ? v : s.speed(), field == 2 ? v : s.density(), s.color());
				live.accept(current[0]);
			});
			slider.valueChangingProperty().addListener((obs, was, now) -> {
				if (!now) {
					AuraCatalog.setSettings(id, current[0]);
				}
			});
			slider.setOnMouseReleased(e -> AuraCatalog.setSettings(id, current[0]));
			box.getChildren().addAll(label, slider);
		}
		Label colourLabel = new Label("Colour");
		colourLabel.getStyleClass().add("page-subtitle");
		HBox swatches = new HBox(6);
		Region[] cells = new Region[AuraEffects.RAINBOW + 1];
		Runnable mark = () -> {
			for (int i = 0; i < cells.length; i++) {
				cells[i].setStyle(cells[i].getStyle().replaceAll("-fx-border-color: [^;]*;", "")
						+ "-fx-border-color: " + (i == current[0].color() ? "white" : "transparent") + ";");
			}
		};
		for (int i = 0; i <= AuraEffects.RAINBOW; i++) {
			int index = i;
			Region cell = new Region();
			cell.setMinSize(22, 22);
			cell.setMaxSize(22, 22);
			String fill = i == AuraEffects.RAINBOW ? "linear-gradient(to right, #ff4d4d, #ffd84d, #3ff0ff, #a65cff)"
					: String.format("#%06x", i == 0 ? AuraEffects.accent(kind, AuraEffects.Settings.DEFAULT) : AuraEffects.PALETTE[i - 1]);
			cell.setStyle("-fx-background-color: " + fill + "; -fx-background-radius: 6; -fx-border-radius: 6; -fx-border-width: 2; -fx-cursor: hand;");
			javafx.scene.control.Tooltip.install(cell, new javafx.scene.control.Tooltip(
					i == 0 ? "Its own colours" : i == AuraEffects.RAINBOW ? "Rainbow" : AuraEffects.PALETTE_NAMES[i - 1]));
			cell.setOnMouseClicked(e -> {
				AuraEffects.Settings s = current[0];
				current[0] = new AuraEffects.Settings(s.size(), s.speed(), s.density(), index);
				live.accept(current[0]);
				AuraCatalog.setSettings(id, current[0]);
				mark.run();
			});
			cells[i] = cell;
			swatches.getChildren().add(cell);
		}
		mark.run();
		Label note = new Label("Saved for the game - it picks them up the next time it starts.");
		note.getStyleClass().add("stage-hint");
		note.setWrapText(true);
		box.getChildren().addAll(colourLabel, swatches, note);
		return box;
	}
}
