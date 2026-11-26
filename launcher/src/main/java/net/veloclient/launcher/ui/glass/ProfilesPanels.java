package net.veloclient.launcher.ui.glass;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import net.veloclient.launcher.data.ClientImport;
import net.veloclient.launcher.modrinth.ModrinthClient;
import net.veloclient.launcher.ui.Icons;
import net.veloclient.launcher.ui.RemoteIconLoader;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** The lower half of the glass Profiles page (2026-11-25): start from a modpack, bring your setup from other clients. */
public final class ProfilesPanels {

	private ProfilesPanels() {
	}

	private static VBox panel(String title, String note) {
		Label heading = new Label(title);
		heading.getStyleClass().add("panel-title");
		HBox top = new HBox(heading);
		top.setAlignment(Pos.CENTER_LEFT);
		if (note != null) {
			javafx.scene.layout.Region push = new javafx.scene.layout.Region();
			HBox.setHgrow(push, Priority.ALWAYS);
			Label n = new Label(note);
			n.getStyleClass().add("home-card-note");
			top.getChildren().addAll(push, n);
		}
		VBox box = new VBox(12, top);
		box.setPadding(new Insets(18));
		box.getStyleClass().add("glass-panel");
		Glass.install(box, 24);
		return box;
	}

	/** Popular Fabric modpacks from Modrinth (search on Enter); the + makes a new profile from one. */
	public static VBox modpacks(Consumer<ModrinthClient.SearchHit> install) {
		VBox box = panel("Start from a modpack", "MODRINTH");
		TextField search = new TextField();
		search.setPromptText("Search modpacks");
		search.getStyleClass().add("topbar-search-field");
		box.getChildren().add(search);
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(10);
		for (int c = 0; c < 3; c++) {
			javafx.scene.layout.ColumnConstraints col = new javafx.scene.layout.ColumnConstraints();
			col.setPercentWidth(100 / 3.0);
			grid.getColumnConstraints().add(col);
		}
		Label status = new Label("");
		status.getStyleClass().add("home-card-empty");
		box.getChildren().addAll(grid, status);
		Label foot = new Label("Each modpack becomes its own profile with Velo on top.");
		foot.getStyleClass().add("home-card-empty");
		box.getChildren().add(foot);
		Consumer<String> load = query -> {
			// the grid's shape while Modrinth answers (instead of a "Loading..." line)
			grid.getChildren().clear();
			Node skeleton = net.veloclient.launcher.ui.Skeleton.list(3);
			GridPane.setColumnSpan(skeleton, 3);
			grid.add(skeleton, 0, 0);
			status.setVisible(false);
			status.setManaged(false);
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.search(query, "modpack", null, query.isBlank() ? "downloads" : "relevance", 0, 9).hits();
				} catch (Exception e) {
					return null;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(hits -> Platform.runLater(() -> {
				grid.getChildren().clear();
				if (hits == null) {
					status.setText("Modrinth can't be reached right now.");
					return;
				}
				status.setVisible(hits.isEmpty());
				status.setManaged(hits.isEmpty());
				status.setText("Nothing found.");
				int i = 0;
				for (ModrinthClient.SearchHit hit : hits) {
					grid.add(packRow(hit, install), i % 3, i / 3);
					i++;
				}
			}));
		};
		search.setOnAction(e -> load.accept(search.getText().strip()));
		load.accept("");
		return box;
	}

	private static Node packRow(ModrinthClient.SearchHit hit, Consumer<ModrinthClient.SearchHit> install) {
		ImageView icon = new ImageView();
		icon.setFitWidth(38);
		icon.setFitHeight(38);
		StackPane iconBox = new StackPane(icon);
		iconBox.getStyleClass().add("pack-icon");
		iconBox.setMinSize(38, 38);
		iconBox.setMaxSize(38, 38);
		Rectangle clip = new Rectangle(38, 38);
		clip.setArcWidth(20);
		clip.setArcHeight(20);
		iconBox.setClip(clip);
		if (hit.iconUrl() != null && !hit.iconUrl().isBlank()) {
			RemoteIconLoader.load(icon, hit.iconUrl(), () -> { });
		}
		Label name = new Label(hit.title());
		name.getStyleClass().add("run-name");
		name.setMinWidth(0);
		Label sub = new Label(downloads(hit.downloads()) + " downloads");
		sub.getStyleClass().add("run-sub");
		VBox text = new VBox(2, name, sub);
		text.setMinWidth(0);
		HBox.setHgrow(text, Priority.ALWAYS);
		Button add = new Button();
		add.setGraphic(Icons.icon("plus", 14, Color.WHITE, 2.4));
		add.getStyleClass().setAll("button", "run-icon", "run-icon-plain");
		add.setTooltip(new javafx.scene.control.Tooltip("Install as a new profile"));
		add.setOnAction(e -> {
			add.setDisable(true);
			add.setGraphic(Icons.icon("check", 14, Color.web("#8de8ac"), 2.4));
			install.accept(hit);
		});
		HBox row = new HBox(11, iconBox, text, add);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("pack-row");
		row.setMinWidth(0);
		return row;
	}

	private static String downloads(long n) {
		return n >= 1_000_000 ? String.format(java.util.Locale.ROOT, "%.1fM", n / 1e6) : n >= 1000 ? (n / 1000) + "k" : String.valueOf(n);
	}

	/** Other launchers found on this PC (Lunar, Modrinth App, CurseForge, the Minecraft Launcher...). */
	public static VBox imports(Runnable openImport) {
		VBox box = panel("Bring your setup", "FOUND ON THIS PC");
		VBox rows = new VBox(8);
		rows.getChildren().add(net.veloclient.launcher.ui.Skeleton.list(2));
		box.getChildren().add(rows);
		Label foot = new Label("Mods, packs, servers, keybinds and worlds come along.");
		foot.getStyleClass().add("home-card-empty");
		foot.setWrapText(true);
		box.getChildren().add(foot);
		CompletableFuture.supplyAsync(() -> {
			try {
				return ClientImport.detect();
			} catch (RuntimeException e) {
				return List.<ClientImport.Source>of();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(found -> Platform.runLater(() -> {
			rows.getChildren().clear();
			java.util.Map<String, Integer> perClient = new java.util.LinkedHashMap<>();
			for (ClientImport.Source source : found) {
				perClient.merge(source.client(), 1, Integer::sum);
			}
			for (var entry : perClient.entrySet()) {
				// the launcher's own icon (the import view's set), its letter on a tile as the fallback
				Node logo = net.veloclient.launcher.ui.ClientImportView.brandLogo(entry.getKey(), 30);
				StackPane letter = new StackPane(logo);
				letter.getStyleClass().add("import-logo");
				letter.setMinSize(42, 42);
				letter.setMaxSize(42, 42);
				Label name = new Label(entry.getKey());
				name.getStyleClass().add("run-name");
				Label sub = new Label(entry.getValue() == 1 ? "1 setup found" : entry.getValue() + " setups found");
				sub.getStyleClass().add("run-sub");
				VBox text = new VBox(2, name, sub);
				HBox.setHgrow(text, Priority.ALWAYS);
				Button go = new Button("Import");
				go.getStyleClass().add("white-pill");
				go.setOnAction(e -> openImport.run());
				HBox row = new HBox(12, letter, text, go);
				row.setAlignment(Pos.CENTER_LEFT);
				row.getStyleClass().add("import-row");
				rows.getChildren().add(row);
			}
			if (perClient.isEmpty()) {
				Label none = new Label("No other launcher found. You can still import a profile .zip or a profile code.");
				none.getStyleClass().add("home-card-empty");
				none.setWrapText(true);
				Button open = new Button("Import anyway");
				open.getStyleClass().add("white-pill");
				open.setOnAction(e -> openImport.run());
				rows.getChildren().addAll(none, open);
			}
		}));
		return box;
	}
}
