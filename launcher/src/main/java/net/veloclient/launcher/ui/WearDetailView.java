package net.veloclient.launcher.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.WearCatalog;
import net.veloclient.launcher.social.StoreApi;
import net.veloclient.velo.client.cosmetics.wear.WearTint;

/**
 * One bodywear item in the launcher's Store: you wearing it in 3D (tried on while the page is open), the price and Buy
 * (Velo Coins) or Wear, and its colour for recolourable items - saved for the game right away.
 */
public final class WearDetailView {

	public interface Host {
		MinecraftSession session();

		void goBack();

		default void openCoins() {
		}
	}

	private WearDetailView() {
	}

	public static Node build(Host host, WearCatalog.WearType type) {
		WearCatalog.setPreview(type.slot(), type.id());
		StackPane stage = CosmeticUi.stage(420, 480);
		stage.getStyleClass().add("showcase-stage");
		stage.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
		// Leaving the page takes the try-on off again.
		stage.sceneProperty().addListener((obs, was, now) -> {
			if (now == null) {
				WearCatalog.clearPreview();
			}
		});
		CosmeticUi.skin(host.session(), skin -> {
			double yaw = "back".equals(type.slot()) ? 160 : 25;
			Node viewer = skin == null ? null : PlayerSkin3DView.createViewer(skin.pngBytes(), skin.slim(), null, true, yaw, true);
			if (viewer != null) {
				stage.getChildren().setAll(viewer);
			}
		});
		int price = StoreApi.price(type.itemId(), type.price());
		ItemPage.Purchase purchase = new ItemPage.Purchase(type.itemId(), price,
				() -> WearCatalog.owns(type.id()),
				() -> type.id().equals(WearCatalog.equipped(type.slot())),
				() -> WearCatalog.equip(type.slot(), type.id()),
				() -> WearCatalog.equip(type.slot(), null),
				() -> {
					try {
						StoreApi.buy(type.itemId());
						return null;
					} catch (StoreApi.StoreError error) {
						return error.getMessage();
					}
				}, "bodywear", type.inStore());
		VBox extra = new VBox(8);
		if (type.tintable()) {
			extra.getChildren().add(colour(type));
		}
		if (WearCatalog.adjustable(type.slot())) {
			extra.getChildren().add(placement(type));
		}
		String slotName = WearCatalog.SLOT_NAMES.get(Math.max(0, WearCatalog.SLOTS.indexOf(type.slot())));
		return ItemPage.build(host::goBack, host::openCoins, stage, null, "Drag to turn it", type.accent(), "Bodywear · " + slotName,
				StoreTiles.Rarity.of(type.itemId(), price), type.name(), type.description(),
				java.util.List.of("One per slot - wear a hat, glasses, a chain, a watch, shoes and a backpack at once",
						type.tintable() ? "Recolour it to any colour" : "Modelled in full 3D", "Seen by every Velo Client player"), extra, purchase);
	}

	/** The colour: its own, a quick pick or any colour from the picker ({@code WearTint} codes, the same as the game). */
	static Node colour(WearCatalog.WearType type) {
		Label label = new Label("Colour");
		label.getStyleClass().add("t-heading");
		Label name = new Label();
		name.getStyleClass().add("stage-hint");
		javafx.scene.layout.FlowPane swatches = new javafx.scene.layout.FlowPane(6, 6);
		javafx.scene.control.ColorPicker picker = new javafx.scene.control.ColorPicker();
		picker.setPrefWidth(120);
		java.util.List<Region> cells = new java.util.ArrayList<>();
		Runnable[] mark = new Runnable[1];
		mark[0] = () -> {
			int rgb = WearTint.rgb(WearCatalog.color(type.id()));
			name.setText(rgb < 0 ? "Its own colours" : String.format("#%06X", rgb));
			for (Region cell : cells) {
				boolean on = java.util.Objects.equals(cell.getUserData(), rgb);
				cell.setStyle(cell.getStyle().replaceAll("-fx-border-color: [^;]*;", "-fx-border-color: " + (on ? "white" : "transparent") + ";"));
			}
		};
		java.util.function.IntConsumer set = code -> {
			WearCatalog.setColor(type.id(), code);
			mark[0].run();
		};
		Region own = cell("linear-gradient(to bottom, #8c8c94 50%, #d9d9de 50%)", "Its own colours");
		own.setUserData(-1);
		own.setOnMouseClicked(e -> set.accept(0));
		cells.add(own);
		for (int i = 0; i < WearTint.PRESETS.length; i++) {
			int rgb = WearTint.PRESETS[i];
			Region c = cell(String.format("#%06x", rgb), WearTint.PRESET_NAMES[i]);
			c.setUserData(rgb);
			c.setOnMouseClicked(e -> set.accept(WearTint.ofRgb(rgb)));
			cells.add(c);
		}
		swatches.getChildren().addAll(cells);
		int current = WearTint.rgb(WearCatalog.color(type.id()));
		if (current >= 0) {
			picker.setValue(javafx.scene.paint.Color.rgb((current >> 16) & 0xFF, (current >> 8) & 0xFF, current & 0xFF));
		}
		picker.setOnAction(e -> {
			javafx.scene.paint.Color c = picker.getValue();
			set.accept(WearTint.ofRgb((int) Math.round(c.getRed() * 255) << 16 | (int) Math.round(c.getGreen() * 255) << 8 | (int) Math.round(c.getBlue() * 255)));
		});
		mark[0].run();
		Label note = new Label("Any colour from the picker works too. Saved for the game right away - you can pick it before buying.");
		note.getStyleClass().add("stage-hint");
		note.setWrapText(true);
		javafx.scene.layout.HBox head = new javafx.scene.layout.HBox(8, label, name);
		head.setAlignment(javafx.geometry.Pos.BASELINE_LEFT);
		return new VBox(8, head, swatches, picker, note);
	}

	/**
	 * Placement: how high (glasses, masks, back items) and how far out from the face (glasses, masks), in quarter
	 * pixels - the model moves while you drag, saved when you let go (the game's setting too).
	 */
	static Node placement(WearCatalog.WearType type) {
		VBox box = new VBox(12);
		javafx.scene.control.Slider height = slider("Height", -WearCatalog.offset(type.id()), box);
		javafx.scene.control.Slider depth = WearCatalog.depthAdjustable(type.slot()) ? slider("Distance from face", WearCatalog.depth(type.id()), box) : null;
		Runnable live = () -> Wear3D.nudge(type.id(), (int) -Math.round(height.getValue()), depth == null ? 0 : (int) Math.round(depth.getValue()));
		Runnable save = () -> WearCatalog.setPlacement(type.id(), (int) -Math.round(height.getValue()), depth == null ? 0 : (int) Math.round(depth.getValue()));
		for (javafx.scene.control.Slider s : depth == null ? java.util.List.of(height) : java.util.List.of(height, depth)) {
			s.valueProperty().addListener((obs, was, now) -> live.run());
			s.valueChangingProperty().addListener((obs, was, now) -> {
				if (!now) {
					save.run();
				}
			});
			// a click on the track (no drag) changes the value without "changing"
			s.setOnMouseReleased(e -> save.run());
			s.setOnKeyReleased(e -> save.run());
		}
		return box;
	}

	private static javafx.scene.control.Slider slider(String title, int value, VBox into) {
		Label label = new Label(title);
		label.getStyleClass().add("t-heading");
		Label shown = new Label();
		shown.getStyleClass().add("stage-hint");
		javafx.scene.control.Slider slider = new javafx.scene.control.Slider(-WearCatalog.MAX_OFFSET, WearCatalog.MAX_OFFSET, value);
		slider.setMajorTickUnit(4);
		slider.setMinorTickCount(3);
		slider.setSnapToTicks(true);
		slider.setBlockIncrement(1);
		slider.setMaxWidth(260);
		Runnable show = () -> {
			double px = Math.round(slider.getValue()) / 4.0;
			shown.setText(px == 0 ? "As made" : (px > 0 ? "+" : "") + px + " px");
		};
		show.run();
		slider.valueProperty().addListener((obs, was, now) -> show.run());
		javafx.scene.layout.HBox head = new javafx.scene.layout.HBox(8, label, shown);
		head.setAlignment(javafx.geometry.Pos.BASELINE_LEFT);
		into.getChildren().add(new VBox(6, head, slider));
		return slider;
	}

	private static Region cell(String fill, String tip) {
		Region cell = new Region();
		cell.setMinSize(22, 22);
		cell.setMaxSize(22, 22);
		cell.setStyle("-fx-background-color: " + fill + "; -fx-background-radius: 6; -fx-border-radius: 6; -fx-border-width: 2; -fx-cursor: hand;"
				+ " -fx-border-color: transparent;");
		javafx.scene.control.Tooltip.install(cell, new javafx.scene.control.Tooltip(tip));
		return cell;
	}
}
