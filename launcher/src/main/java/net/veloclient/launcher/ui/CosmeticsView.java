package net.veloclient.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.launcher.theme.LauncherTheme;

import java.util.List;

/**
 * Cosmetics: a big live 3D showcase of your own skin wearing the selected cape (left) and your cape
 * collection as 3D-rendered cards (right). Selecting a card previews it; Equip makes it the cape
 * other Velo players see on you.
 */
public final class CosmeticsView {

	public interface Host {
		LauncherTheme activeTheme();

		void rebuild();

		default MinecraftSession session() {
			return null;
		}
	}

	/** The card currently previewed - survives rebuilds (equip/delete/import rebuild the page). */
	private static String selectedId;

	private CosmeticsView() {
	}

	public static Node build(Stage owner, Host host) {
		// Selecting/equipping refreshes this page in place (no full page transition per click).
		StackPane page = new StackPane();
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> page.getChildren().setAll(buildContent(owner, host, refresh[0], false));
		page.getChildren().setAll(buildContent(owner, host, refresh[0], true));
		return page;
	}

	private static Node buildContent(Stage owner, Host host, Runnable refresh, boolean firstShow) {
		List<CapeEntry> capes = CapeLibrary.listAll();
		String equippedId = CapeLibrary.equippedCapeId().orElse(null);
		if (selectedId == null || capes.stream().noneMatch(c -> c.id().equals(selectedId))) {
			selectedId = equippedId != null ? equippedId : capes.isEmpty() ? null : capes.get(0).id();
		}
		CapeEntry selected = capes.stream().filter(c -> c.id().equals(selectedId)).findFirst().orElse(null);

		// Header.
		Label heading = new Label("Cosmetics");
		heading.getStyleClass().add("page-title");
		Label sub = new Label("Your capes. The equipped one shows in-game for you and every other Velo player.");
		sub.getStyleClass().add("page-subtitle");
		VBox titles = new VBox(2, heading, sub);
		Button importButton = new Button("+  Import cape");
		importButton.getStyleClass().add("primary-button");
		importButton.setOnAction(e -> CapeImportDialog.show(owner).ifPresent(result -> {
			try {
				CapeLibrary.importPng(result.name(), result.pngFile(), result.preset());
				refresh.run();
			} catch (Exception ex) {
				error(owner, "Import failed", ex.getMessage());
			}
		}));
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(12, titles, spacer, importButton);
		header.setAlignment(Pos.CENTER_LEFT);

		// Left: showcase.
		Node showcase = buildShowcase(owner, host, refresh, selected, selected != null && selected.id().equals(equippedId));

		// Right: collection.
		FlowPane grid = new FlowPane(14, 14);
		grid.getStyleClass().add("cape-grid");
		grid.getChildren().add(CosmeticUi.capeCard(null, "No cape", CosmeticUi.badge(equippedId == null ? "Equipped" : "Off",
				equippedId == null ? "chip-owned" : null), selected == null && equippedId == null, () -> {
					CapeLibrary.unequip();
					selectedId = null;
					refresh.run();
				}));
		for (CapeEntry cape : capes) {
			List<GifFrames.Frame> frames = CosmeticUi.frames(cape);
			boolean equipped = cape.id().equals(equippedId);
			Node footer = equipped ? CosmeticUi.badge("Equipped", "chip-owned")
					: CosmeticUi.badge(cape.animated() ? "Animated" : "Static", null);
			grid.getChildren().add(CosmeticUi.capeCard("lib:" + cape.id(), frames,
					cape.name(), footer, cape.id().equals(selectedId), () -> {
						selectedId = cape.id();
						refresh.run();
					}));
		}
		if (capes.isEmpty()) {
			Label empty = new Label("No capes yet - import a cape texture (" + CapeLibrary.TEXTURE_WIDTH + "x"
					+ CapeLibrary.TEXTURE_HEIGHT + " up to " + CapeLibrary.MAX_TEXTURE_WIDTH + "x" + CapeLibrary.MAX_TEXTURE_HEIGHT
					+ ") or get one in the Store.");
			empty.getStyleClass().add("empty-state");
			empty.setWrapText(true);
			grid.getChildren().add(empty);
		}
		Label collectionTitle = new Label("Collection  ·  " + capes.size());
		collectionTitle.getStyleClass().add("section-label");
		VBox collection = new VBox(12, collectionTitle, grid);
		ScrollPane scroll = new ScrollPane(collection);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		HBox.setHgrow(scroll, Priority.ALWAYS);
		if (firstShow) {
			UiMotion.stagger(grid, 24);
		}

		HBox body = new HBox(22, showcase, scroll);
		VBox.setVgrow(body, Priority.ALWAYS);
		VBox root = new VBox(18, header, body);
		VBox.setVgrow(root, Priority.ALWAYS);
		return root;
	}

	private static Node buildShowcase(Stage owner, Host host, Runnable refresh, CapeEntry selected, boolean equipped) {
		StackPane stage = CosmeticUi.stage(300, 380);
		stage.getStyleClass().add("showcase-stage");
		List<GifFrames.Frame> frames = selected == null ? null : CosmeticUi.frames(selected);
		Label loading = new Label(host.session() == null ? "Sign in to see capes on your own skin" : "Loading your skin...");
		loading.getStyleClass().add("page-subtitle");
		loading.setWrapText(true);
		stage.getChildren().add(loading);
		CosmeticUi.skin(host.session(), skin -> {
			Node viewer = skin == null ? null : PlayerSkin3DView.createShowcase(skin.pngBytes(), skin.slim(),
					frames == null || frames.isEmpty() ? null : frames);
			if (viewer != null) {
				stage.getChildren().setAll(viewer);
			} else if (frames != null && !frames.isEmpty()) {
				ImageView big = new ImageView(CosmeticUi.thumbnail("lib:" + selected.id(), frames, 260, 300));
				stage.getChildren().setAll(big);
			}
		});
		Label hint = new Label("Drag to rotate");
		hint.getStyleClass().add("stage-hint");
		StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
		StackPane stageWithHint = new StackPane(stage, hint);
		hint.setTranslateY(-10);

		Label name = new Label(selected == null ? "No cape" : selected.name());
		name.getStyleClass().add("showcase-title");
		HBox badges = new HBox(6);
		if (selected != null) {
			badges.getChildren().add(CosmeticUi.badge(selected.animated() ? "Animated" : "Static", null));
			if (equipped) {
				badges.getChildren().add(CosmeticUi.badge("Equipped", "chip-owned"));
			}
		}

		HBox actions = new HBox(8);
		if (selected != null) {
			Button equip = new Button(equipped ? "Unequip" : "Equip");
			if (!equipped) {
				equip.getStyleClass().add("primary-button");
			}
			equip.setMaxWidth(Double.MAX_VALUE);
			HBox.setHgrow(equip, Priority.ALWAYS);
			equip.setOnAction(e -> {
				if (equipped) {
					CapeLibrary.unequip();
				} else {
					CapeLibrary.equip(selected.id());
				}
				refresh.run();
			});
			Button delete = new Button("Delete");
			delete.getStyleClass().add("danger-ghost-button");
			delete.setOnAction(e -> {
				try {
					CapeLibrary.delete(selected);
					selectedId = null;
					refresh.run();
				} catch (Exception ex) {
					error(owner, "Delete failed", ex.getMessage());
				}
			});
			actions.getChildren().addAll(equip, delete);
		}

		VBox panel = new VBox(12, stageWithHint, name, badges, actions);
		panel.getStyleClass().add("showcase-panel");
		panel.setMinWidth(324);
		panel.setMaxWidth(324);
		return panel;
	}

	private static void error(Stage owner, String title, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.initOwner(owner);
		alert.setTitle(title);
		alert.setHeaderText(title);
		alert.setContentText(message);
		DialogStyling.apply(alert);
		alert.showAndWait();
	}
}
