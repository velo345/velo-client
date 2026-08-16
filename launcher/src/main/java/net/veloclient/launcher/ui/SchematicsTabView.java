package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.modrinth.ModrinthClient;
import net.veloclient.launcher.schematic.PhantomMarketClient;
import net.veloclient.launcher.schematic.SchematicMatch;
import net.veloclient.launcher.schematic.SchematicMatchCache;
import net.veloclient.launcher.theme.LauncherTheme;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The "Schematics" profile tab: local management of Litematica ({@code
 * .litematic}) and WorldEdit ({@code .schem}) schematic files for one
 * instance, plus a "Browse online" search view against PhantomMarket (see
 * {@link PhantomMarketClient}). Modeled on {@link InstanceDetailView}'s Mods/
 * Resource Packs/Shader Packs tabs (installed list + full-page browse, swapped
 * into one container) but schematics have no Modrinth project of their own,
 * so this is its own small pipeline rather than a fourth {@code
 * InstalledAssetStore.Kind}.
 */
public final class SchematicsTabView {

	private enum Format {
		LITEMATICA("Litematica", ".litematic", "bEpr0Arc", InstancePaths::schematicsLitematicaDir),
		WORLDEDIT("WorldEdit", ".schem", "1u6JkXh5", InstancePaths::schematicsWorldEditDir);

		final String label;
		final String extension;
		final String modrinthProjectId;
		final java.util.function.Function<String, Path> folder;

		Format(String label, String extension, String modrinthProjectId, java.util.function.Function<String, Path> folder) {
			this.label = label;
			this.extension = extension;
			this.modrinthProjectId = modrinthProjectId;
			this.folder = folder;
		}

		Path folder(String instanceId) {
			return folder.apply(instanceId);
		}
	}

	private SchematicsTabView() {
	}

	public static Node build(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme) {
		StackPane container = new StackPane();
		container.setPadding(new Insets(14, 4, 4, 4));

		Format defaultFormat = InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD).stream()
				.anyMatch(a -> a.projectId().equals(Format.WORLDEDIT.modrinthProjectId))
				&& InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD).stream()
				.noneMatch(a -> a.projectId().equals(Format.LITEMATICA.modrinthProjectId))
				? Format.WORLDEDIT : Format.LITEMATICA;

		Format[] selected = {defaultFormat};
		Set<String> identifyAttempted = new HashSet<>();
		Runnable[] showLocal = new Runnable[1];
		Runnable[] showBrowse = new Runnable[1];

		showLocal[0] = () -> container.getChildren().setAll(buildLocalView(owner, overlayHost, instance, theme, selected, identifyAttempted, showLocal[0], showBrowse[0]));
		showBrowse[0] = () -> container.getChildren().setAll(buildBrowseView(owner, instance, theme, selected, showLocal[0]));

		showLocal[0].run();
		return container;
	}

	// ---- Local list ----

	private static Node buildLocalView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme,
			Format[] selected, Set<String> identifyAttempted, Runnable refresh, Runnable openBrowse) {
		VBox root = new VBox(14);

		HBox formatRow = new HBox(6);
		formatRow.setAlignment(Pos.CENTER_LEFT);
		for (Format format : Format.values()) {
			Button toggle = new Button(format.label);
			toggle.getStyleClass().add("button-compact");
			if (format == selected[0]) {
				toggle.getStyleClass().add("title-menu-button-primary");
			}
			toggle.setOnAction(e -> {
				selected[0] = format;
				refresh.run();
			});
			formatRow.getChildren().add(toggle);
		}
		root.getChildren().add(formatRow);

		Format format = selected[0];
		boolean modInstalled = InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD).stream()
				.anyMatch(a -> a.projectId().equals(format.modrinthProjectId));
		if (!modInstalled) {
			root.getChildren().add(buildDependencyBanner(owner, overlayHost, instance, theme, format, refresh));
		}

		Path folder = format.folder(instance.id());

		VBox list = new VBox(8);
		Runnable listRefresh = () -> refreshList(list, theme, format, folder, identifyAttempted);

		HBox actions = new HBox(8);
		Button addButton = new Button("Add from file...");
		addButton.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose schematic files to add");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
					format.label + " files", "*" + format.extension));
			List<File> files = chooser.showOpenMultipleDialog(owner);
			if (files == null) {
				return;
			}
			for (File file : files) {
				try {
					Files.createDirectories(folder);
					Files.copy(file.toPath(), folder.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ex) {
					InstanceDetailView.error(owner, "Failed to add " + file.getName(), ex.getMessage());
				}
			}
			listRefresh.run();
		});
		Button openFolderButton = new Button("Open folder");
		openFolderButton.setOnAction(e -> {
			try {
				Files.createDirectories(folder);
				InstanceDetailView.openInFileManager(folder);
			} catch (Exception ex) {
				InstanceDetailView.error(owner, "Couldn't open folder", ex.getMessage());
			}
		});
		Button browseButton = new Button("Browse online");
		browseButton.getStyleClass().addAll("title-menu-button", "title-menu-button-primary", "button-compact");
		browseButton.setOnAction(e -> openBrowse.run());
		actions.getChildren().addAll(addButton, openFolderButton, browseButton);

		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		root.getChildren().addAll(actions, scroll);
		listRefresh.run();
		return root;
	}

	private static void refreshList(VBox list, LauncherTheme theme, Format format, Path folder, Set<String> identifyAttempted) {
		list.getChildren().clear();
		try {
			Files.createDirectories(folder);
		} catch (IOException ignored) {
			// Best-effort.
		}
		List<Path> files;
		try (Stream<Path> stream = Files.list(folder)) {
			files = stream.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(format.extension))
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		} catch (IOException e) {
			Label errorLabel = new Label("Couldn't read folder: " + e.getMessage());
			errorLabel.setTextFill(InstanceDetailView.text(theme));
			list.getChildren().add(errorLabel);
			return;
		}
		if (files.isEmpty()) {
			Label empty = new Label("No " + format.label + " schematics here yet - add a file, or browse online below.");
			empty.getStyleClass().add("section-subtitle");
			empty.setTextFill(InstanceDetailView.text(theme));
			list.getChildren().add(empty);
			return;
		}
		Map<String, SchematicMatch> matches = SchematicMatchCache.load(folder);
		Runnable refresh = () -> refreshList(list, theme, format, folder, identifyAttempted);
		for (Path file : files) {
			String fileName = file.getFileName().toString();
			SchematicMatch match = matches.get(fileName);
			list.getChildren().add(buildFileRow(theme, file, match, refresh));
			if (match == null && identifyAttempted.add(fileName)) {
				identifyAsync(folder, fileName, refresh);
			}
		}
	}

	/** Best-effort, once per filename per tab session (see {@code identifyAttempted}) - silently stays a plain filename row if PhantomMarket doesn't know it or the lookup fails. */
	private static void identifyAsync(Path folder, String fileName, Runnable refresh) {
		CompletableFuture.runAsync(() -> {
			try {
				SchematicMatch match = SchematicMatchCache.identify(fileName);
				Map<String, SchematicMatch> matches = SchematicMatchCache.load(folder);
				matches.put(fileName, match);
				SchematicMatchCache.save(folder, matches);
				Platform.runLater(refresh);
			} catch (IOException ignored) {
				// Network failure - leave uncached so it's retried on the next tab open, per SchematicMatchCache#identify's contract.
			}
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	private static Node buildFileRow(LauncherTheme theme, Path file, SchematicMatch match, Runnable refresh) {
		HBox row = new HBox(12);
		row.getStyleClass().add("mod-row");
		row.setAlignment(Pos.CENTER_LEFT);

		boolean identified = match != null && match.found();
		if (identified) {
			Node icon = InstanceDetailView.iconView(match.thumbnailUrl(), 40);
			icon.setCursor(Cursor.HAND);
			icon.setOnMouseClicked(e -> openInBrowser(match.postUrl()));
			row.getChildren().add(icon);
		} else {
			Label glyph = new Label("▦");
			glyph.setFont(Font.font("System", FontWeight.BOLD, 20));
			glyph.setTextFill(InstanceDetailView.text(theme));
			glyph.setMinWidth(32);
			glyph.setAlignment(Pos.CENTER);
			row.getChildren().add(glyph);
		}

		String fileName = file.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		String displayName = identified ? match.title() : (dot > 0 ? fileName.substring(0, dot) : fileName);

		VBox info = new VBox(2);
		Label title = new Label(displayName);
		title.setFont(Font.font("System", FontWeight.BOLD, 13));
		title.setTextFill(InstanceDetailView.text(theme));
		if (identified) {
			title.setCursor(Cursor.HAND);
			title.setOnMouseClicked(e -> openInBrowser(match.postUrl()));
		}
		Label meta = new Label(identified ? sizeOf(file) + "  ·  matched on PhantomMarket" : sizeOf(file));
		meta.getStyleClass().add("version-tag");
		meta.setTextFill(InstanceDetailView.text(theme));
		info.getChildren().addAll(title, meta);
		HBox.setHgrow(info, Priority.ALWAYS);
		row.getChildren().add(info);

		Button remove = new Button("Remove");
		remove.setOnAction(e -> {
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
				// Best-effort.
			}
			refresh.run();
		});
		row.getChildren().add(remove);
		return row;
	}

	private static String sizeOf(Path file) {
		try {
			long bytes = Files.size(file);
			return bytes >= 1024 * 1024
					? String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
					: String.format(Locale.ROOT, "%.0f KB", Math.max(1, bytes / 1024.0));
		} catch (IOException e) {
			return "";
		}
	}

	/** An inline "X isn't installed in this profile" notice with an opt-in "Add it" button - the same version-picker + dependency-checklist flow the Mods tab's own Modrinth install uses, just triggered from here instead of a search card. */
	private static Node buildDependencyBanner(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, Format format, Runnable refresh) {
		HBox banner = new HBox(10);
		banner.getStyleClass().add("mod-row");
		banner.setAlignment(Pos.CENTER_LEFT);

		Label text = new Label(format.label + " isn't installed in this profile - schematics will still be saved to the right folder for when you add it.");
		text.setWrapText(true);
		text.setTextFill(InstanceDetailView.text(theme));
		HBox.setHgrow(text, Priority.ALWAYS);
		banner.getChildren().add(text);

		Button add = new Button("Add " + format.label);
		add.getStyleClass().addAll("title-menu-button", "title-menu-button-primary", "button-compact");
		add.setOnAction(e -> {
			add.setDisable(true);
			add.setText("Loading versions...");
			Path modsDir = InstancePaths.modsDir(instance.id());
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.versions(format.modrinthProjectId, instance.mcVersion());
				} catch (IOException ex) {
					return List.<ModrinthClient.ProjectVersion>of();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(versions -> Platform.runLater(() -> {
				add.setDisable(false);
				add.setText("Add " + format.label);
				if (versions.isEmpty()) {
					InstanceDetailView.error(owner, "No compatible version", format.label + " has no version published for Minecraft " + instance.mcVersion() + " on Fabric.");
					return;
				}
				InstanceDetailView.showVersionPicker(overlayHost, theme, instance, format.label, versions, plan ->
						InstanceDetailView.installWithDependencies(owner, instance, InstalledAssetStore.Kind.MOD, modsDir, plan, null, new ProgressBar(0), refresh));
			}));
		});
		banner.getChildren().add(add);
		return banner;
	}

	// ---- Browse online (PhantomMarket) ----

	private static Node buildBrowseView(Stage owner, Instance instance, LauncherTheme theme, Format[] selected, Runnable onBack) {
		VBox root = new VBox(14);
		VBox.setVgrow(root, Priority.ALWAYS);

		HBox topRow = new HBox(10);
		topRow.setAlignment(Pos.CENTER_LEFT);
		Button back = new Button("< Back to installed");
		back.setOnAction(e -> onBack.run());
		Label heading = new Label("Browse schematics on market.phantom-node.com");
		heading.setFont(Font.font("System", FontWeight.BOLD, 15));
		heading.setTextFill(InstanceDetailView.text(theme));
		topRow.getChildren().addAll(back, heading);
		root.getChildren().add(topRow);

		Label notice = new Label("Previews load in-app; click a result to finish downloading on the real site (it's ad-supported - that's how it stays free), then use \"I downloaded it\" to file it into your " + selected[0].label + " folder.");
		notice.setWrapText(true);
		notice.getStyleClass().add("section-subtitle");
		notice.setTextFill(InstanceDetailView.text(theme));
		root.getChildren().add(notice);

		HBox searchRow = new HBox(8);
		TextField query = new TextField();
		query.setPromptText("Search schematics...");
		HBox.setHgrow(query, Priority.ALWAYS);
		Button searchButton = new Button("Search");
		Button importButton = new Button("I downloaded it - Import...");
		importButton.getStyleClass().add("button-compact");
		importButton.setOnAction(e -> importDownloadedFile(owner, instance, theme, selected[0]));
		searchRow.getChildren().addAll(query, searchButton, importButton);
		root.getChildren().add(searchRow);

		FlowPane results = new FlowPane(14, 14);
		VBox resultsColumn = new VBox(14, results);
		ScrollPane resultsScroll = new ScrollPane(resultsColumn);
		resultsScroll.setFitToWidth(true);
		resultsScroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(resultsScroll, Priority.ALWAYS);
		root.getChildren().add(resultsScroll);

		Runnable runSearch = () -> {
			results.getChildren().setAll(InstanceDetailView.loadingLabel(theme));
			CompletableFuture.supplyAsync(() -> {
				try {
					return PhantomMarketClient.search(query.getText());
				} catch (IOException ex) {
					return ex;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				if (result instanceof Exception ex) {
					results.getChildren().setAll(InstanceDetailView.messageLabel("Search failed: " + ex.getMessage(), theme));
					return;
				}
				@SuppressWarnings("unchecked")
				List<PhantomMarketClient.SearchHit> hits = (List<PhantomMarketClient.SearchHit>) result;
				if (hits.isEmpty()) {
					results.getChildren().setAll(InstanceDetailView.messageLabel("No results.", theme));
					return;
				}
				results.getChildren().clear();
				for (PhantomMarketClient.SearchHit hit : hits) {
					results.getChildren().add(buildSchematicCard(theme, hit));
				}
			}));
		};
		searchButton.setOnAction(e -> runSearch.run());
		query.setOnAction(e -> runSearch.run());
		runSearch.run();

		return root;
	}

	private static Node buildSchematicCard(LauncherTheme theme, PhantomMarketClient.SearchHit hit) {
		VBox card = new VBox(8);
		card.getStyleClass().add("search-card");
		card.setPrefWidth(190);
		card.setMinHeight(220);
		card.setMaxHeight(220);
		card.setAlignment(Pos.TOP_CENTER);

		StackPane thumbHolder = new StackPane();
		thumbHolder.setPrefSize(170, 96);
		thumbHolder.setMinSize(170, 96);
		thumbHolder.getStyleClass().add("instance-icon-custom");
		ImageView thumbView = new ImageView();
		thumbView.setFitWidth(170);
		thumbView.setFitHeight(96);
		thumbView.setPreserveRatio(true);
		String thumbUrl = hit.thumbnailUrl();
		if (thumbUrl != null) {
			RemoteIconLoader.load(thumbView, thumbUrl, () -> { });
		}
		thumbHolder.getChildren().add(thumbView);
		card.getChildren().add(thumbHolder);

		Label title = new Label(hit.title());
		title.setFont(Font.font("System", FontWeight.BOLD, 13));
		title.setTextFill(InstanceDetailView.text(theme));
		title.setWrapText(true);
		title.setAlignment(Pos.CENTER);
		title.setMaxWidth(170);

		Label author = new Label("by " + hit.authorUsername() + "  ·  " + InstanceDetailView.formatCount(hit.downloadCount()) + " downloads");
		author.getStyleClass().add("version-tag");
		author.setTextFill(InstanceDetailView.text(theme));
		author.setWrapText(true);
		author.setMaxWidth(170);

		VBox spacer = new VBox();
		VBox.setVgrow(spacer, Priority.ALWAYS);

		Button view = new Button("View & Download");
		view.getStyleClass().add("button-compact");
		view.setMaxWidth(Double.MAX_VALUE);
		String postUrl = PhantomMarketClient.postUrl(hit.slug());
		view.setOnAction(e -> openInBrowser(postUrl));

		card.getChildren().addAll(title, author, spacer, view);
		card.setCursor(Cursor.HAND);
		card.setOnMouseClicked(e -> {
			if (e.getTarget() != view) {
				openInBrowser(postUrl);
			}
		});
		return card;
	}

	private static void importDownloadedFile(Stage owner, Instance instance, LauncherTheme theme, Format format) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Choose the downloaded " + format.label + " schematic");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format.label + " files", "*" + format.extension));
		File downloads = new File(System.getProperty("user.home", "."), "Downloads");
		if (downloads.isDirectory()) {
			chooser.setInitialDirectory(downloads);
		}
		File file = chooser.showOpenDialog(owner);
		if (file == null) {
			return;
		}
		Path folder = format.folder(instance.id());
		try {
			Files.createDirectories(folder);
			Files.copy(file.toPath(), folder.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			InstanceDetailView.error(owner, "Import failed", ex.getMessage());
		}
	}

	private static void openInBrowser(String url) {
		try {
			java.awt.Desktop.getDesktop().browse(new URI(url));
			return;
		} catch (Exception ignored) {
			// Fall through to an OS-specific process, same fallback shape as InstanceDetailView#openInFileManager.
		}
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String[] command = os.contains("win") ? new String[] {"cmd", "/c", "start", "", url}
					: os.contains("mac") ? new String[] {"open", url}
					: new String[] {"xdg-open", url};
			new ProcessBuilder(command).start();
		} catch (IOException ignored) {
			// Best-effort - nothing more we can do here.
		}
	}
}
