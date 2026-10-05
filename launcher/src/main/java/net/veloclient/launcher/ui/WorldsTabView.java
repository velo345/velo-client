package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.theme.LauncherTheme;
import net.veloclient.launcher.world.WorldImporter;
import net.veloclient.launcher.world.WorldInfo;
import net.veloclient.launcher.world.WorldScanner;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The "Worlds" profile tab: every singleplayer world of the profile, and one big "Import world"
 * button (a world folder or a downloaded .zip - or just drop either onto the tab) so nobody has to
 * hunt for the right saves folder again.
 */
public final class WorldsTabView {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

	private WorldsTabView() {
	}

	public static Node build(Stage owner, Instance instance, LauncherTheme theme) {
		Path saves = InstancePaths.savesDir(instance.id());
		VBox root = new VBox(14);
		root.setPadding(new Insets(14, 4, 4, 4));
		Label status = new Label();
		status.getStyleClass().add("settings-row-hint");
		FlowPane grid = new FlowPane(14, 14);
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> fill(grid, owner, saves, theme, status, refresh[0]);

		Button importButton = new Button("+  Import world");
		importButton.getStyleClass().add("primary-button");
		ContextMenu menu = new ContextMenu();
		MenuItem fromFolder = new MenuItem("From a world folder...");
		fromFolder.setOnAction(e -> {
			DirectoryChooser chooser = new DirectoryChooser();
			chooser.setTitle("Choose the world folder (the one with level.dat inside)");
			File picked = chooser.showDialog(owner);
			if (picked != null) {
				runImport(picked.toPath(), saves, status, refresh[0]);
			}
		});
		MenuItem fromZip = new MenuItem("From a .zip file...");
		fromZip.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose a world .zip");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("World zip", "*.zip"));
			File picked = chooser.showOpenDialog(owner);
			if (picked != null) {
				runImport(picked.toPath(), saves, status, refresh[0]);
			}
		});
		menu.getItems().addAll(fromFolder, fromZip);
		importButton.setOnAction(e -> menu.show(importButton, javafx.geometry.Side.BOTTOM, 0, 4));

		Button openFolder = new Button("Open saves folder");
		openFolder.getStyleClass().add("ghost-button");
		openFolder.setOnAction(e -> {
			try {
				java.nio.file.Files.createDirectories(saves);
				InstanceDetailView.openInFileManager(saves);
			} catch (Exception ex) {
				status.setText("Couldn't open the folder: " + ex.getMessage());
			}
		});

		Label hint = InstanceDetailView.messageLabel("Import a world folder or .zip - or drop it right here.", theme);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(10, hint, spacer, openFolder, importButton);
		header.setAlignment(Pos.CENTER_LEFT);

		ScrollPane scroll = new ScrollPane(grid);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		root.getChildren().addAll(header, status, scroll);

		// Drag & drop a world folder or zip anywhere on the tab.
		root.setOnDragOver(e -> {
			if (e.getDragboard().hasFiles()) {
				e.acceptTransferModes(TransferMode.COPY);
			}
			e.consume();
		});
		root.setOnDragDropped(e -> {
			List<File> files = e.getDragboard().getFiles();
			if (files != null) {
				for (File file : files) {
					runImport(file.toPath(), saves, status, refresh[0]);
				}
			}
			e.setDropCompleted(files != null && !files.isEmpty());
			e.consume();
		});

		refresh[0].run();
		return root;
	}

	private static void runImport(Path source, Path saves, Label status, Runnable refresh) {
		status.setText("Importing " + source.getFileName() + "...");
		CompletableFuture.supplyAsync(() -> {
			try {
				return "Imported \"" + WorldImporter.importWorld(source, saves) + "\"";
			} catch (Exception e) {
				return "Import failed: " + e.getMessage();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(message -> Platform.runLater(() -> {
			status.setText(message);
			refresh.run();
		}));
	}

	private static void fill(FlowPane grid, Stage owner, Path saves, LauncherTheme theme, Label status, Runnable refresh) {
		grid.getChildren().clear();
		List<WorldInfo> worlds = WorldScanner.list(saves);
		if (worlds.isEmpty()) {
			grid.getChildren().add(InstanceDetailView.messageLabel(
					"No worlds yet. Import one, or create one by playing this profile in singleplayer.", theme));
			return;
		}
		for (WorldInfo world : worlds) {
			grid.getChildren().add(card(owner, saves, world, theme, status, refresh));
		}
	}

	private static Node card(Stage owner, Path saves, WorldInfo world, LauncherTheme theme, Label status, Runnable refresh) {
		VBox card = new VBox(8);
		card.getStyleClass().add("search-card");
		card.setPrefWidth(190);
		card.setAlignment(Pos.TOP_CENTER);

		ImageView icon = new ImageView(world.iconFile() != null
				? new Image(world.iconFile().toUri().toString(), 64, 64, true, true)
				: new Image(WorldsTabView.class.getResourceAsStream("/net/veloclient/launcher/images/logo.png"), 64, 64, true, true));
		icon.setFitWidth(64);
		icon.setFitHeight(64);
		StackPane iconHolder = new StackPane(icon);
		iconHolder.setMinSize(64, 64);
		iconHolder.getStyleClass().add("instance-icon-custom");

		Label name = new Label(world.levelName());
		name.setFont(javafx.scene.text.Font.font("Inter", javafx.scene.text.FontWeight.BOLD, 13));
		name.setTextFill(InstanceDetailView.text(theme));
		name.setWrapText(true);
		name.setMaxWidth(170);
		Label mode = new Label(world.gamemode().displayName() + (world.hardcore() ? " (Hardcore)" : ""));
		mode.getStyleClass().add("version-tag");
		Label played = new Label(world.lastPlayedEpochMillis() > 0
				? "Played " + DATE.format(Instant.ofEpochMilli(world.lastPlayedEpochMillis())) : "Never played");
		played.getStyleClass().add("settings-row-hint");

		Button open = new Button("Open");
		open.getStyleClass().add("ghost-button");
		open.setOnAction(e -> {
			try {
				InstanceDetailView.openInFileManager(saves.resolve(world.folderName()));
			} catch (Exception ex) {
				status.setText("Couldn't open the folder: " + ex.getMessage());
			}
		});
		Button delete = new Button("Delete");
		delete.getStyleClass().add("ghost-button");
		delete.setOnAction(e -> {
			Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
			alert.initOwner(owner);
			alert.setTitle("Delete world?");
			alert.setHeaderText("Delete \"" + world.levelName() + "\"?");
			alert.setContentText("The world is deleted from this profile for good. This can't be undone.");
			DialogStyling.apply(alert);
			Optional<ButtonType> result = alert.showAndWait();
			if (result.isPresent() && result.get() == ButtonType.OK) {
				try {
					WorldImporter.deleteTree(saves.resolve(world.folderName()));
					status.setText("Deleted \"" + world.levelName() + "\"");
				} catch (Exception ex) {
					status.setText("Couldn't delete it (is the game still running?): " + ex.getMessage());
				}
				refresh.run();
			}
		});
		HBox actions = new HBox(8, open, delete);
		actions.setAlignment(Pos.CENTER);
		card.getChildren().addAll(iconHolder, name, mode, played, actions);
		return card;
	}
}
