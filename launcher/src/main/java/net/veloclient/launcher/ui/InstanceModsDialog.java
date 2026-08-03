package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Shows every jar in a profile's mods folder; the bundled velo-client jar is protected, everything else can be added/removed. */
public final class InstanceModsDialog {

	private InstanceModsDialog() {
	}

	public static void show(Stage owner, String instanceName, Path modsDir) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle(instanceName + " - Mods");
		dialog.setHeaderText(instanceName + " - Mods");
		DialogStyling.apply(dialog);
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

		VBox root = new VBox(10);
		root.setPadding(new Insets(4));
		root.setPrefSize(440, 420);

		HBox actions = new HBox(8);
		Button addButton = new Button("Add mods...");
		Button openFolderButton = new Button("Open folder");
		actions.getChildren().addAll(addButton, openFolderButton);

		VBox list = new VBox(6);
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> refreshList(list, modsDir, refresh[0]);

		addButton.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose mod jar(s) to add");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Jar files", "*.jar"));
			List<File> files = chooser.showOpenMultipleDialog(owner);
			if (files == null) {
				return;
			}
			for (File file : files) {
				try {
					Files.copy(file.toPath(), modsDir.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ex) {
					showError(owner, "Failed to add " + file.getName(), ex.getMessage());
				}
			}
			refresh[0].run();
		});
		openFolderButton.setOnAction(e -> {
			try {
				Files.createDirectories(modsDir);
				openInFileManager(modsDir);
			} catch (Exception ex) {
				showError(owner, "Couldn't open folder", ex.getMessage());
			}
		});

		root.getChildren().addAll(actions, scroll);
		dialog.getDialogPane().setContent(root);
		refresh[0].run();

		dialog.showAndWait();
	}

	/**
	 * Shells out to the OS's own file manager rather than {@code java.awt.Desktop.open()} -
	 * AWT's Desktop pulls in its own native GTK toolkit, and initializing that
	 * alongside JavaFX's own GTK-based Glass toolkit in the same process
	 * reliably native-crashes the whole JVM on Linux (no Java exception to
	 * catch - the process just dies), which is what was happening here.
	 */
	private static void openInFileManager(Path folder) throws IOException {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[] command;
		if (os.contains("win")) {
			command = new String[] {"explorer.exe", folder.toString()};
		} else if (os.contains("mac")) {
			command = new String[] {"open", folder.toString()};
		} else {
			command = new String[] {"xdg-open", folder.toString()};
		}
		new ProcessBuilder(command).start();
	}

	private static void refreshList(VBox list, Path modsDir, Runnable refresh) {
		list.getChildren().clear();
		try {
			Files.createDirectories(modsDir);
		} catch (IOException ignored) {
			// Best-effort.
		}
		List<Path> jars;
		try (Stream<Path> files = Files.list(modsDir)) {
			jars = files.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			list.getChildren().add(new Label("Couldn't read mods folder: " + e.getMessage()));
			return;
		}
		if (jars.isEmpty()) {
			list.getChildren().add(new Label("No mods yet."));
			return;
		}
		for (Path jar : jars) {
			list.getChildren().add(buildRow(jar, refresh));
		}
	}

	private static HBox buildRow(Path jar, Runnable refresh) {
		String fileName = jar.getFileName().toString();
		boolean isVeloClient = fileName.startsWith("velo-client-");
		boolean isFabricApi = fileName.startsWith("fabric-api-");
		boolean protectedJar = isVeloClient || isFabricApi;

		HBox row = new HBox(10);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("mod-row");

		VBox info = new VBox(2);
		Label name = new Label(fileName + (isVeloClient ? "  (Velo Client)" : isFabricApi ? "  (Fabric API - required)" : ""));
		Label size = new Label(sizeOf(jar));
		size.getStyleClass().add("version-tag");
		info.getChildren().addAll(name, size);
		HBox.setHgrow(info, Priority.ALWAYS);

		row.getChildren().add(info);
		if (!protectedJar) {
			Button remove = new Button("Remove");
			remove.setOnAction(e -> {
				try {
					Files.deleteIfExists(jar);
				} catch (IOException ignored) {
					// Best-effort.
				}
				refresh.run();
			});
			row.getChildren().add(remove);
		}
		return row;
	}

	private static String sizeOf(Path file) {
		try {
			long bytes = Files.size(file);
			return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
		} catch (IOException e) {
			return "";
		}
	}

	private static void showError(Stage owner, String title, String message) {
		javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
		alert.initOwner(owner);
		alert.setTitle(title);
		alert.setHeaderText(title);
		alert.setContentText(message);
		DialogStyling.apply(alert);
		alert.showAndWait();
	}
}
