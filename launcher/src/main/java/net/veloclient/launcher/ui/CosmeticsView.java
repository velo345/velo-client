package net.veloclient.launcher.ui;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.theme.LauncherTheme;

import java.io.ByteArrayInputStream;

/** The redesigned Cosmetics screen: capes with live texture previews instead of a plain name list. */
public final class CosmeticsView {

	public interface Host {
		LauncherTheme activeTheme();

		void rebuild();
	}

	private CosmeticsView() {
	}

	public static Node build(Stage owner, Host host) {
		VBox root = new VBox(20);

		Label heading = new Label("Cosmetics");
		heading.getStyleClass().add("section-heading");
		heading.setTextFill(accent(host.activeTheme()));
		root.getChildren().add(heading);

		root.getChildren().add(buildCapesSection(owner, host));

		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox.setVgrow(wrapper, Priority.ALWAYS);
		return wrapper;
	}

	private static VBox buildCapesSection(Stage owner, Host host) {
		VBox section = new VBox(10);
		Label title = new Label("Capes");
		title.setFont(Font.font("System", FontWeight.BOLD, 16));
		title.setTextFill(text(host.activeTheme()));

		Button importButton = new Button("Import Cape...");
		importButton.getStyleClass().add("title-menu-button");
		importButton.setOnAction(e -> CapeImportDialog.show(owner).ifPresent(result -> {
			try {
				CapeLibrary.importPng(result.name(), result.pngFile(), result.preset());
				host.rebuild();
			} catch (Exception ex) {
				error(owner, "Import failed", ex.getMessage());
			}
		}));

		HBox header = new HBox(12, title, importButton);
		header.setAlignment(Pos.CENTER_LEFT);

		FlowPane grid = new FlowPane(14, 14);
		var capes = CapeLibrary.listAll();
		String equippedId = CapeLibrary.equippedCapeId().orElse(null);
		if (capes.isEmpty()) {
			Label empty = new Label("No capes imported yet. Textures must keep the 2:1 cape ratio, "
					+ CapeLibrary.TEXTURE_WIDTH + "x" + CapeLibrary.TEXTURE_HEIGHT + " up to "
					+ CapeLibrary.MAX_TEXTURE_WIDTH + "x" + CapeLibrary.MAX_TEXTURE_HEIGHT + ".");
			empty.getStyleClass().add("section-subtitle");
			empty.setTextFill(text(host.activeTheme()));
			grid.getChildren().add(empty);
		}
		for (CapeEntry cape : capes) {
			grid.getChildren().add(buildCapeCard(owner, host, cape, cape.id().equals(equippedId)));
		}

		section.getChildren().addAll(header, grid);
		return section;
	}

	private static VBox buildCapeCard(Stage owner, Host host, CapeEntry cape, boolean equipped) {
		VBox card = new VBox(8);
		card.getStyleClass().add("instance-card");
		card.setPrefWidth(140);
		card.setAlignment(Pos.TOP_CENTER);

		Node preview = buildCapePreview(cape);
		Label name = new Label(cape.name() + (equipped ? "  ✓" : ""));
		name.setTextFill(equipped ? accent(host.activeTheme()) : text(host.activeTheme()));
		name.setWrapText(true);
		name.setFont(Font.font("System", FontWeight.BOLD, 13));

		Button equipButton = new Button(equipped ? "Unequip" : "Equip");
		equipButton.setMaxWidth(Double.MAX_VALUE);
		equipButton.setOnAction(e -> {
			if (equipped) {
				CapeLibrary.unequip();
			} else {
				CapeLibrary.equip(cape.id());
			}
			host.rebuild();
		});
		Button deleteButton = new Button("Delete");
		deleteButton.setMaxWidth(Double.MAX_VALUE);
		deleteButton.setOnAction(e -> {
			try {
				CapeLibrary.delete(cape);
				host.rebuild();
			} catch (Exception ex) {
				error(owner, "Delete failed", ex.getMessage());
			}
		});

		card.getChildren().addAll(preview, name, equipButton, deleteButton);
		return card;
	}

	/** Crops the cape template's back-panel region (same 10x16 @ (1,1) UV the in-game VeloCapeTile draws), scaled to whatever resolution this particular texture actually is. */
	private static Node buildCapePreview(CapeEntry cape) {
		StackPane holder = new StackPane();
		holder.setPrefSize(70, 96);
		holder.getStyleClass().add("instance-icon-custom");
		try {
			byte[] bytes = CapeLibrary.textureBytes(cape);
			Image full = new Image(new ByteArrayInputStream(bytes));
			double scale = full.getWidth() / CapeLibrary.TEXTURE_WIDTH;
			ImageView view = new ImageView(full);
			view.setViewport(new Rectangle2D(scale, scale, 10 * scale, 16 * scale));
			view.setFitWidth(60);
			view.setFitHeight(96);
			view.setSmooth(false);
			view.setPreserveRatio(true);
			holder.getChildren().add(view);
		} catch (Exception e) {
			Label fallback = new Label("?");
			fallback.setTextFill(Color.WHITE);
			holder.getChildren().add(fallback);
		}
		return holder;
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
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
