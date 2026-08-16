package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
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
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import net.veloclient.launcher.data.InstalledAsset;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.launch.Downloader;
import net.veloclient.launcher.modrinth.ModrinthClient;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The embedded workspace for one mod profile - replaces the old
 * {@code InstanceModsDialog} popup with tabs living directly in the main
 * window: Mods, Resource Packs, Shader Packs. Each pack tab shares the same
 * shape: an installed list (rich Modrinth-sourced metadata where known,
 * auto-identified by file hash otherwise) with manual add-from-file/open-
 * folder, and a full-page in-place Modrinth search+install view.
 */
public final class InstanceDetailView {

	private static final String FALLBACK_ICON = "/net/veloclient/launcher/images/logo.png";

	private InstanceDetailView() {
	}

	public static Node build(Stage owner, Instance instance, LauncherTheme theme, Runnable onBack) {
		VBox root = new VBox(14);

		HBox header = new HBox(12);
		header.setAlignment(Pos.CENTER_LEFT);
		Button back = new Button("< Back");
		back.setOnAction(e -> onBack.run());
		Label title = new Label(instance.name());
		title.setFont(Font.font("System", FontWeight.BOLD, 20));
		title.setTextFill(text(theme));
		Label version = new Label("Minecraft " + instance.mcVersion() + " · Fabric");
		version.getStyleClass().add("version-tag");
		version.setTextFill(text(theme));
		header.getChildren().addAll(back, title, version);
		root.getChildren().add(header);

		// A second layer on top of the tabs for the version/dependency picker
		// - it needs to grow and scroll with however many dependencies a mod
		// has, which a native Dialog's own auto-sizing handled poorly, and it
		// reads as part of this workspace rather than a separate OS window.
		StackPane overlayHost = new StackPane();
		overlayHost.setPickOnBounds(false);

		TabPane tabs = new TabPane();
		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tabs.getTabs().add(new Tab("Mods", buildPackTab(owner, overlayHost, instance, theme, InstalledAssetStore.Kind.MOD,
				InstancePaths.modsDir(instance.id()), List.of("*.jar"))));
		tabs.getTabs().add(new Tab("Modpacks", ModpacksTabView.build(owner, overlayHost, instance, theme)));
		tabs.getTabs().add(new Tab("Resource Packs", buildPackTab(owner, overlayHost, instance, theme, InstalledAssetStore.Kind.RESOURCE_PACK,
				InstancePaths.resourcePacksDir(instance.id()), List.of("*.zip"))));
		tabs.getTabs().add(new Tab("Shader Packs", buildPackTab(owner, overlayHost, instance, theme, InstalledAssetStore.Kind.SHADER_PACK,
				InstancePaths.shaderPacksDir(instance.id()), List.of("*.zip"))));
		tabs.getTabs().add(new Tab("Schematics", SchematicsTabView.build(owner, overlayHost, instance, theme)));
		tabs.getTabs().add(new Tab("Datapacks", DatapacksTabView.build(owner, overlayHost, instance, theme)));
		VBox.setVgrow(tabs, Priority.ALWAYS);
		root.getChildren().add(tabs);

		StackPane container = new StackPane(root, overlayHost);
		return container;
	}

	// ---- One pack tab (Mods / Resource Packs / Shader Packs) ----

	private static Node buildPackTab(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, List<String> fileFilters) {
		StackPane container = new StackPane();
		container.setPadding(new Insets(14, 4, 4, 4));
		Set<String> identifyAttempted = new HashSet<>();

		Runnable[] showInstalled = new Runnable[1];
		Runnable[] showSearch = new Runnable[1];
		// Whichever of the two views above was showing right before a detail
		// page was opened - ProjectDetailView's "< Back" returns here rather
		// than always landing on the installed list, so opening a detail page
		// from search results doesn't strand you back on the wrong tab.
		Runnable[] activeBack = new Runnable[1];
		java.util.function.Consumer<String> openDetail[] = new java.util.function.Consumer[1];

		showInstalled[0] = () -> {
			activeBack[0] = showInstalled[0];
			container.getChildren().setAll(
					buildInstalledView(owner, overlayHost, instance, theme, kind, folder, fileFilters, identifyAttempted, showInstalled[0], showSearch[0], openDetail[0]));
		};
		showSearch[0] = () -> {
			activeBack[0] = showSearch[0];
			container.getChildren().setAll(
					buildSearchView(owner, overlayHost, instance, theme, kind, folder, showInstalled[0], openDetail[0]));
		};
		openDetail[0] = projectId -> showProjectDetail(owner, container, instance, theme, kind, folder, projectId, () -> activeBack[0].run());

		showInstalled[0].run();
		return container;
	}

	/** Async-loads a project's full detail (description, gallery, every compatible version) and swaps it into {@code container} - see {@link ProjectDetailView}. */
	private static void showProjectDetail(Stage owner, StackPane container, Instance instance, LauncherTheme theme,
			InstalledAssetStore.Kind kind, Path folder, String projectId, Runnable back) {
		container.getChildren().setAll(loadingLabel(theme));
		CompletableFuture.supplyAsync(() -> {
			Optional<ModrinthClient.ProjectDetail> detail = ModrinthClient.getProjectDetail(projectId);
			if (detail.isEmpty()) {
				return Optional.<Object[]>empty();
			}
			List<ModrinthClient.ProjectVersion> versions;
			try {
				versions = ModrinthClient.versions(projectId, instance.mcVersion(), kind.modrinthProjectType());
			} catch (IOException e) {
				versions = List.of();
			}
			return Optional.of(new Object[] {detail.get(), versions});
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result.isEmpty()) {
				container.getChildren().setAll(messageLabel("Couldn't load project details.", theme));
				return;
			}
			Object[] pair = result.get();
			ModrinthClient.ProjectDetail detail = (ModrinthClient.ProjectDetail) pair[0];
			@SuppressWarnings("unchecked")
			List<ModrinthClient.ProjectVersion> versions = (List<ModrinthClient.ProjectVersion>) pair[1];
			Set<String> installedVersionIds = InstalledAssetStore.loadAll(instance.id(), kind).stream()
					.map(InstalledAsset::versionId).collect(java.util.stream.Collectors.toSet());
			ProjectDetailView.Host host = new ProjectDetailView.Host() {
				@Override
				public Stage owner() {
					return owner;
				}

				@Override
				public LauncherTheme theme() {
					return theme;
				}

				@Override
				public void goBack() {
					back.run();
				}
			};
			container.getChildren().setAll(ProjectDetailView.build(host, detail, versions, installedVersionIds, version -> {
				ProgressBar progress = new ProgressBar(0);
				installVersion(owner, instance, kind, folder, version, null, progress,
						() -> showProjectDetail(owner, container, instance, theme, kind, folder, projectId, back));
			}));
		}));
	}

	private static Node buildInstalledView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, List<String> fileFilters, Set<String> identifyAttempted, Runnable refresh, Runnable openSearch,
			java.util.function.Consumer<String> openDetail) {
		VBox root = new VBox(14);

		VBox installedList = new VBox(8);
		Runnable listRefresh = () -> refreshInstalledList(owner, overlayHost, installedList, instance, theme, kind, folder, identifyAttempted, refresh, openDetail);

		HBox actions = new HBox(8);
		Button addButton = new Button("Add from file...");
		addButton.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose files to add");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(kind == InstalledAssetStore.Kind.MOD ? "Jar files" : "Zip files", fileFilters));
			List<File> files = chooser.showOpenMultipleDialog(owner);
			if (files == null) {
				return;
			}
			for (File file : files) {
				try {
					Files.createDirectories(folder);
					Files.copy(file.toPath(), folder.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ex) {
					error(owner, "Failed to add " + file.getName(), ex.getMessage());
				}
			}
			refresh.run();
		});
		Button openFolderButton = new Button("Open folder");
		openFolderButton.setOnAction(e -> {
			try {
				Files.createDirectories(folder);
				openInFileManager(folder);
			} catch (Exception ex) {
				error(owner, "Couldn't open folder", ex.getMessage());
			}
		});
		Button modrinthButton = new Button("+ New Modrinth");
		modrinthButton.getStyleClass().addAll("title-menu-button", "title-menu-button-primary", "button-compact");
		modrinthButton.setOnAction(e -> openSearch.run());
		actions.getChildren().addAll(addButton, openFolderButton, modrinthButton);

		ScrollPane scroll = new ScrollPane(installedList);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		root.getChildren().addAll(actions, scroll);
		listRefresh.run();
		return root;
	}

	private static void refreshInstalledList(Stage owner, StackPane overlayHost, VBox list, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, Set<String> identifyAttempted, Runnable refresh, java.util.function.Consumer<String> openDetail) {
		list.getChildren().clear();
		try {
			Files.createDirectories(folder);
		} catch (IOException ignored) {
			// Best-effort.
		}
		List<Path> files;
		try (Stream<Path> stream = Files.list(folder)) {
			files = stream.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
		} catch (IOException e) {
			Label errorLabel = new Label("Couldn't read folder: " + e.getMessage());
			errorLabel.setTextFill(text(theme));
			list.getChildren().add(errorLabel);
			return;
		}
		if (files.isEmpty()) {
			Label empty = new Label("Nothing here yet - add files manually or install from Modrinth above.");
			empty.getStyleClass().add("section-subtitle");
			empty.setTextFill(text(theme));
			list.getChildren().add(empty);
			return;
		}
		var known = InstalledAssetStore.asMap(InstalledAssetStore.loadAll(instance.id(), kind));
		for (Path file : files) {
			String fileName = file.getFileName().toString();
			boolean disabled = isDisabled(fileName);
			InstalledAsset asset = known.get(enabledName(fileName));
			if (asset != null) {
				list.getChildren().add(buildKnownRow(owner, overlayHost, instance, theme, kind, folder, file, disabled, asset, refresh, openDetail));
			} else {
				list.getChildren().add(buildUnknownRow(instance, theme, kind, folder, file, disabled, identifyAttempted, refresh));
				autoIdentify(instance, kind, file, identifyAttempted, refresh);
			}
		}
	}

	// ---- Enable/disable (renames the extension so Fabric Loader/resource pack scanning never sees it, without deleting it) ----

	private static final String DISABLED_SUFFIX = ".disabled";

	private static boolean isDisabled(String fileName) {
		return fileName.endsWith(DISABLED_SUFFIX);
	}

	/** The filename this file would have (and is recorded under in {@link InstalledAssetStore}) while enabled. */
	private static String enabledName(String fileName) {
		return isDisabled(fileName) ? fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length()) : fileName;
	}

	private static CheckBox buildEnabledToggle(Path file, boolean disabled, Runnable refresh) {
		CheckBox toggle = new CheckBox();
		toggle.setSelected(!disabled);
		toggle.setTooltip(new Tooltip(disabled ? "Disabled - click to enable" : "Enabled - click to disable without removing it"));
		toggle.setOnAction(e -> setFileEnabled(file, toggle.isSelected(), refresh));
		return toggle;
	}

	/** Renames {@code file} to add/strip the {@value #DISABLED_SUFFIX} suffix so it does/doesn't get loaded, then refreshes the list. */
	private static void setFileEnabled(Path file, boolean enable, Runnable refresh) {
		String fileName = file.getFileName().toString();
		String targetName = enable ? enabledName(fileName) : (isDisabled(fileName) ? fileName : fileName + DISABLED_SUFFIX);
		if (!targetName.equals(fileName)) {
			try {
				Files.move(file, file.resolveSibling(targetName), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// Best-effort - the row just keeps its old state if this fails.
			}
		}
		refresh.run();
	}

	private static Node buildKnownRow(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind, Path folder,
			Path file, boolean disabled, InstalledAsset asset, Runnable refresh, java.util.function.Consumer<String> openDetail) {
		HBox row = new HBox(12);
		row.getStyleClass().add("mod-row");
		row.setAlignment(Pos.CENTER_LEFT);
		row.setOpacity(disabled ? 0.5 : 1.0);

		row.getChildren().add(buildEnabledToggle(file, disabled, refresh));
		Node icon = iconView(asset.iconUrl(), 48);
		icon.setCursor(javafx.scene.Cursor.HAND);
		icon.setOnMouseClicked(e -> openDetail.accept(asset.projectId()));
		row.getChildren().add(icon);

		VBox info = new VBox(2);
		Label title = new Label(asset.title() + (disabled ? "  (disabled)" : ""));
		title.setFont(Font.font("System", FontWeight.BOLD, 13));
		title.setTextFill(text(theme));
		Label meta = new Label("v" + asset.versionNumber());
		meta.getStyleClass().add("version-tag");
		meta.setTextFill(text(theme));
		info.getChildren().addAll(title, meta);
		HBox.setHgrow(info, Priority.ALWAYS);
		row.getChildren().add(info);

		ProgressBar progress = new ProgressBar(0);
		progress.setPrefWidth(90);
		progress.setVisible(false);
		progress.setManaged(false);

		Label updateBadge = new Label();
		updateBadge.getStyleClass().addAll("title-menu-button", "button-compact");
		updateBadge.setVisible(false);
		updateBadge.setManaged(false);
		checkForUpdate(owner, overlayHost, instance, theme, kind, folder, asset, updateBadge, progress, refresh);

		Button remove = new Button("Remove");
		remove.setOnAction(e -> {
			try {
				Files.deleteIfExists(file);
				InstalledAssetStore.forget(instance.id(), kind, enabledName(file.getFileName().toString()));
			} catch (IOException ignored) {
				// Best-effort.
			}
			refresh.run();
		});
		row.getChildren().addAll(progress, updateBadge, remove);
		return row;
	}

	private static Node buildUnknownRow(Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind, Path folder,
			Path file, boolean disabled, Set<String> identifyAttempted, Runnable refresh) {
		String fileName = enabledName(file.getFileName().toString());
		boolean isVeloClient = fileName.startsWith("velo-client-");
		boolean isFabricApi = fileName.startsWith("fabric-api-");
		boolean protectedFile = kind == InstalledAssetStore.Kind.MOD && (isVeloClient || isFabricApi);

		HBox row = new HBox(12);
		row.getStyleClass().add("mod-row");
		row.setAlignment(Pos.CENTER_LEFT);
		row.setOpacity(disabled ? 0.5 : 1.0);

		if (!protectedFile) {
			row.getChildren().add(buildEnabledToggle(file, disabled, refresh));
		}
		row.getChildren().add(iconView(null, 48));

		VBox info = new VBox(2);
		Label name = new Label(fileName + (isVeloClient ? "  (Velo Client)" : isFabricApi ? "  (Fabric API - required)" : "")
				+ (disabled ? "  (disabled)" : ""));
		name.setTextFill(text(theme));
		Label size = new Label(protectedFile ? sizeOf(file) : sizeOf(file) + "  ·  not on Modrinth");
		size.getStyleClass().add("version-tag");
		size.setTextFill(text(theme));
		info.getChildren().addAll(name, size);
		HBox.setHgrow(info, Priority.ALWAYS);
		row.getChildren().add(info);

		if (!protectedFile) {
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
		}
		return row;
	}

	/** Looks the file up on Modrinth by sha1 automatically (no button) the first time it's seen as unrecognized; silently stays a plain row if Modrinth doesn't know it. */
	private static void autoIdentify(Instance instance, InstalledAssetStore.Kind kind, Path file, Set<String> identifyAttempted, Runnable refresh) {
		String fileName = file.getFileName().toString();
		String enabledFileName = enabledName(fileName);
		if (enabledFileName.startsWith("velo-client-") || enabledFileName.startsWith("fabric-api-") || !identifyAttempted.add(fileName)) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				String sha1 = Downloader.sha1Of(file);
				ModrinthClient.versionByFileSha1(sha1).ifPresent(v -> ModrinthClient.getProject(v.projectId()).ifPresent(project ->
						InstalledAssetStore.record(instance.id(), kind, new InstalledAsset(project.id(), v.id(), enabledFileName,
								project.title(), project.description(), project.iconUrl(), v.versionNumber(), kind.modrinthProjectType()))));
			} catch (IOException ignored) {
				// Stays an unknown row.
			}
			Platform.runLater(refresh);
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	private static void checkForUpdate(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, InstalledAsset asset, Label updateBadge, ProgressBar progress, Runnable refresh) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return ModrinthClient.versions(asset.projectId(), instance.mcVersion(), kind.modrinthProjectType());
			} catch (IOException e) {
				return List.<ModrinthClient.ProjectVersion>of();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(versions -> {
			Optional<ModrinthClient.ProjectVersion> latest = versions.stream().max(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished));
			latest.ifPresent(newest -> {
				if (!newest.id().equals(asset.versionId())) {
					Platform.runLater(() -> {
						updateBadge.setText("Update to " + newest.versionNumber());
						updateBadge.setVisible(true);
						updateBadge.setManaged(true);
						updateBadge.setOnMouseClicked(e -> {
							updateBadge.setVisible(false);
							updateBadge.setManaged(false);
							showVersionPicker(overlayHost, theme, instance, asset.title(), List.of(newest), plan ->
									installWithDependencies(owner, instance, kind, folder, plan, asset.filename(), progress, refresh));
						});
					});
				}
			});
		});
	}

	// ---- Full-page Modrinth search view ----

	private static Node buildSearchView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind, Path folder, Runnable onBack,
			java.util.function.Consumer<String> openDetail) {
		VBox root = new VBox(14);
		VBox.setVgrow(root, Priority.ALWAYS);

		HBox topRow = new HBox(10);
		topRow.setAlignment(Pos.CENTER_LEFT);
		Button back = new Button("< Back to installed");
		back.setOnAction(e -> onBack.run());
		Label heading = new Label("Browse " + kind.modrinthProjectType() + "s for Minecraft " + instance.mcVersion());
		heading.setFont(Font.font("System", FontWeight.BOLD, 15));
		heading.setTextFill(text(theme));
		topRow.getChildren().addAll(back, heading);
		root.getChildren().add(topRow);

		HBox searchRow = new HBox(8);
		TextField query = new TextField();
		query.setPromptText("Search...");
		HBox.setHgrow(query, Priority.ALWAYS);
		ComboBox<String> sort = new ComboBox<>();
		sort.getItems().addAll("Relevance", "Downloads", "Follows", "Newest", "Recently updated");
		sort.setValue("Relevance");
		Button searchButton = new Button("Search");
		searchRow.getChildren().addAll(query, sort, searchButton);
		root.getChildren().add(searchRow);

		FlowPane results = new FlowPane(14, 14);
		Button loadMore = new Button("Load More");
		loadMore.getStyleClass().add("button-compact");
		loadMore.setMaxWidth(Double.MAX_VALUE);
		loadMore.setVisible(false);
		loadMore.setManaged(false);
		VBox resultsColumn = new VBox(14, results, loadMore);
		ScrollPane resultsScroll = new ScrollPane(resultsColumn);
		resultsScroll.setFitToWidth(true);
		resultsScroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(resultsScroll, Priority.ALWAYS);
		root.getChildren().add(resultsScroll);

		int[] offset = {0};
		int pageSize = 40;

		Runnable[] loadPage = new Runnable[1];
		loadPage[0] = () -> {
			boolean firstPage = offset[0] == 0;
			loadMore.setDisable(true);
			String sortIndex = switch (sort.getValue()) {
				case "Downloads" -> "downloads";
				case "Follows" -> "follows";
				case "Newest" -> "newest";
				case "Recently updated" -> "updated";
				default -> "relevance";
			};
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.search(query.getText(), kind.modrinthProjectType(), instance.mcVersion(), sortIndex, offset[0], pageSize);
				} catch (IOException e) {
					return e;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				loadMore.setDisable(false);
				if (result instanceof Exception ex) {
					if (firstPage) {
						results.getChildren().setAll(messageLabel("Search failed: " + ex.getMessage(), theme));
					}
					loadMore.setVisible(false);
					loadMore.setManaged(false);
					return;
				}
				@SuppressWarnings("unchecked")
				ModrinthClient.SearchResult search = (ModrinthClient.SearchResult) result;
				if (firstPage) {
					results.getChildren().clear();
					if (search.hits().isEmpty()) {
						results.getChildren().add(messageLabel("No results.", theme));
						loadMore.setVisible(false);
						loadMore.setManaged(false);
						return;
					}
				}
				for (ModrinthClient.SearchHit hit : search.hits()) {
					results.getChildren().add(buildSearchResultCard(owner, overlayHost, instance, theme, kind, folder, hit, openDetail));
				}
				offset[0] += search.hits().size();
				boolean hasMore = !search.hits().isEmpty() && offset[0] < search.totalHits();
				loadMore.setVisible(hasMore);
				loadMore.setManaged(hasMore);
			}));
		};

		Runnable runSearch = () -> {
			offset[0] = 0;
			results.getChildren().setAll(loadingLabel(theme));
			loadMore.setVisible(false);
			loadMore.setManaged(false);
			loadPage[0].run();
		};
		loadMore.setOnAction(e -> loadPage[0].run());
		searchButton.setOnAction(e -> runSearch.run());
		query.setOnAction(e -> runSearch.run());
		sort.setOnAction(e -> runSearch.run());

		// Load relevance results immediately - browsing shouldn't require typing something first.
		runSearch.run();
		return root;
	}

	private static Node buildSearchResultCard(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, ModrinthClient.SearchHit hit, java.util.function.Consumer<String> openDetail) {
		VBox card = new VBox(8);
		card.getStyleClass().add("search-card");
		card.setPrefWidth(190);
		card.setMinHeight(230);
		card.setMaxHeight(230);
		card.setAlignment(Pos.TOP_CENTER);

		Node icon = iconView(hit.iconUrl(), 64);
		icon.setCursor(javafx.scene.Cursor.HAND);
		icon.setOnMouseClicked(e -> openDetail.accept(hit.projectId()));
		card.getChildren().add(icon);

		Label title = new Label(hit.title());
		title.setFont(Font.font("System", FontWeight.BOLD, 13));
		title.setTextFill(text(theme));
		title.setWrapText(true);
		title.setAlignment(Pos.CENTER);
		title.setMaxWidth(170);

		Label description = new Label(truncate(hit.description(), 70));
		description.setWrapText(true);
		description.getStyleClass().add("section-subtitle");
		description.setTextFill(text(theme));
		description.setMaxWidth(170);
		description.setMinHeight(32);
		description.setMaxHeight(32);

		Label downloads = new Label(formatCount(hit.downloads()) + " downloads");
		downloads.getStyleClass().add("version-tag");
		downloads.setTextFill(text(theme));

		VBox spacer = new VBox();
		VBox.setVgrow(spacer, Priority.ALWAYS);

		ProgressBar progress = new ProgressBar(0);
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.setVisible(false);
		progress.setManaged(false);

		boolean alreadyInstalled = InstalledAssetStore.loadAll(instance.id(), kind).stream()
				.anyMatch(a -> a.projectId().equals(hit.projectId()));

		Button install = new Button(alreadyInstalled ? "Installed" : "Install...");
		install.getStyleClass().add("button-compact");
		install.setMaxWidth(Double.MAX_VALUE);
		install.setDisable(alreadyInstalled);
		if (!alreadyInstalled) {
			install.setOnAction(e -> {
				install.setDisable(true);
				CompletableFuture.supplyAsync(() -> {
					try {
						return ModrinthClient.versions(hit.projectId(), instance.mcVersion(), kind.modrinthProjectType());
					} catch (IOException ex) {
						return ex;
					}
				}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
					if (result instanceof Exception ex) {
						install.setDisable(false);
						error(owner, "Couldn't load versions", ex.getMessage());
						return;
					}
					@SuppressWarnings("unchecked")
					List<ModrinthClient.ProjectVersion> versions = (List<ModrinthClient.ProjectVersion>) result;
					if (versions.isEmpty()) {
						install.setDisable(false);
						error(owner, "No compatible version", hit.title() + " has no version published"
								+ ("mod".equals(kind.modrinthProjectType()) ? " for Minecraft " + instance.mcVersion() + " on Fabric." : " at all."));
						return;
					}
					// Stay on the search results after installing (so browsing
					// and installing several things in a row doesn't keep
					// kicking you back to the installed list) - just flip this
					// card's own button once it's done, instead of navigating.
					showVersionPicker(overlayHost, theme, instance, hit.title(), versions, plan ->
							installWithDependencies(owner, instance, kind, folder, plan, null, progress, () -> {
								install.setText("Installed");
								install.setDisable(true);
							}));
				}));
			});
		}

		card.getChildren().addAll(title, description, downloads, spacer, progress, install);
		return card;
	}

	record InstallPlan(ModrinthClient.ProjectVersion version, List<ModrinthClient.ProjectVersion> dependencies) {
	}

	/**
	 * The version-choice UI also lists the chosen version's required
	 * dependencies (resolved asynchronously as the selection changes) - each
	 * one already installed shows as such, everything else gets its own
	 * checkbox (checked by default, but each is genuinely optional to
	 * uncheck) rather than an earlier version's all-or-nothing prompt shown
	 * only after the main install had already finished.
	 *
	 * <p>Rendered as an in-window overlay (a backdrop + centered card added
	 * to {@code overlayHost}) rather than a native {@code Dialog} - a real
	 * OS-level dialog window couldn't grow past its own fixed size, so a mod
	 * with several dependencies just clipped/couldn't scroll. This card
	 * grows with its content up to a cap, then the dependency list itself
	 * scrolls.
	 */
	static void showVersionPicker(StackPane overlayHost, LauncherTheme theme, Instance instance, String projectTitle,
			List<ModrinthClient.ProjectVersion> versions, java.util.function.Consumer<InstallPlan> onConfirm) {
		VBox card = new VBox(12);
		card.getStyleClass().addAll("glass-panel", "modal-card");
		card.setMaxWidth(420);
		card.setPrefWidth(420);
		card.setMaxHeight(560);

		Label heading = new Label("Install " + projectTitle);
		heading.setFont(Font.font("System", FontWeight.BOLD, 16));
		heading.setTextFill(text(theme));
		heading.setWrapText(true);
		Label subheading = new Label("Choose which version to install");
		subheading.getStyleClass().add("section-subtitle");
		subheading.setTextFill(text(theme));

		ComboBox<ModrinthClient.ProjectVersion> combo = new ComboBox<>();
		combo.getItems().addAll(versions);
		combo.setValue(versions.get(0));
		combo.setConverter(new StringConverter<>() {
			@Override
			public String toString(ModrinthClient.ProjectVersion v) {
				return v == null ? "" : v.versionNumber();
			}

			@Override
			public ModrinthClient.ProjectVersion fromString(String string) {
				return null;
			}
		});

		DependencyChecklist dependencyChecklist = new DependencyChecklist();
		combo.setOnAction(e -> dependencyChecklist.refresh(instance, combo.getValue()));
		dependencyChecklist.refresh(instance, combo.getValue());

		VBox scrollableContent = new VBox(10, combo, dependencyChecklist.node);
		ScrollPane scroll = new ScrollPane(scrollableContent);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		Button cancel = new Button("Cancel");
		Button install = new Button("Install");
		install.getStyleClass().addAll("title-menu-button", "title-menu-button-primary", "button-compact");
		HBox spacer = new HBox();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox buttons = new HBox(8, spacer, cancel, install);

		card.getChildren().addAll(heading, subheading, scroll, buttons);

		StackPane backdrop = new StackPane(card);
		backdrop.getStyleClass().add("modal-backdrop");
		StackPane.setAlignment(card, Pos.CENTER);

		Runnable close = () -> overlayHost.getChildren().remove(backdrop);
		cancel.setOnAction(e -> close.run());
		backdrop.setOnMouseClicked(e -> {
			if (e.getTarget() == backdrop) {
				close.run();
			}
		});
		install.setOnAction(e -> {
			InstallPlan plan = new InstallPlan(combo.getValue(), dependencyChecklist.chosen());
			close.run();
			onConfirm.accept(plan);
		});

		overlayHost.getChildren().add(backdrop);
	}

	/** @param oldFilenameToRemove nullable - forwarded to the main version's install so an update replaces the old file (see {@link #installVersion}); dependency installs never have one since they're always fresh. */
	static void installWithDependencies(Stage owner, Instance instance, InstalledAssetStore.Kind kind, Path folder,
			InstallPlan plan, String oldFilenameToRemove, ProgressBar progress, Runnable refresh) {
		installVersion(owner, instance, kind, folder, plan.version(), oldFilenameToRemove, progress, refresh);
		if (!plan.dependencies().isEmpty()) {
			Path modsDir = InstancePaths.modsDir(instance.id());
			CompletableFuture.runAsync(() -> {
				for (ModrinthClient.ProjectVersion dep : plan.dependencies()) {
					downloadAndRecord(instance, InstalledAssetStore.Kind.MOD, modsDir, dep, null, fraction -> { });
				}
				Platform.runLater(refresh);
			}, Executors.newVirtualThreadPerTaskExecutor());
		}
	}

	/** @param oldFilenameToRemove nullable - when set (an update, not a fresh install), the old file is deleted once the new one is confirmed on disk, even if the filename changed between versions. */
	static void installVersion(Stage owner, Instance instance, InstalledAssetStore.Kind kind, Path folder, ModrinthClient.ProjectVersion version,
			String oldFilenameToRemove, ProgressBar progress, Runnable refresh) {
		if (version.primaryFile().isEmpty()) {
			return;
		}
		Platform.runLater(() -> {
			progress.setProgress(0);
			progress.setVisible(true);
			progress.setManaged(true);
		});
		CompletableFuture.runAsync(() -> {
			downloadAndRecord(instance, kind, folder, version, oldFilenameToRemove, fraction -> Platform.runLater(() -> progress.setProgress(fraction)));
			Platform.runLater(() -> {
				progress.setVisible(false);
				progress.setManaged(false);
				refresh.run();
			});
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	private static void downloadAndRecord(Instance instance, InstalledAssetStore.Kind kind, Path folder, ModrinthClient.ProjectVersion version,
			String oldFilenameToRemove, java.util.function.DoubleConsumer onProgress) {
		Optional<ModrinthClient.VersionFile> fileOpt = version.primaryFile();
		if (fileOpt.isEmpty()) {
			return;
		}
		ModrinthClient.VersionFile file = fileOpt.get();
		try {
			Files.createDirectories(folder);
			Path dest = folder.resolve(file.filename());
			long totalSize = Math.max(1, file.size());
			long[] done = {0};
			Downloader.ensure(URI.create(file.url()), dest, file.sha1(), file.size(), n -> {
				done[0] += n;
				onProgress.accept(Math.min(1.0, done[0] / (double) totalSize));
			});
			ModrinthClient.getProject(version.projectId()).ifPresent(project ->
					InstalledAssetStore.record(instance.id(), kind, new InstalledAsset(project.id(), version.id(), file.filename(),
							project.title(), project.description(), project.iconUrl(), version.versionNumber(), kind.modrinthProjectType())));
			if (oldFilenameToRemove != null && !oldFilenameToRemove.equals(file.filename())) {
				Files.deleteIfExists(folder.resolve(oldFilenameToRemove));
				InstalledAssetStore.forget(instance.id(), kind, oldFilenameToRemove);
			}
		} catch (IOException ignored) {
			// Best-effort - the file just won't show up if this fails.
		}
	}

	/**
	 * Builds the "Dependencies" section of the version-choice dialog: one row
	 * per required dependency of whichever version is currently selected,
	 * resolved asynchronously (each is a Modrinth lookup). Already-installed
	 * dependencies show as a plain confirmation line; everything else gets
	 * its own checkbox (checked by default, individually optional) rather
	 * than an all-or-nothing prompt.
	 */
	private static final class DependencyChecklist {
		final VBox node = new VBox(6);
		private final java.util.Map<String, CheckBox> checkboxByProjectId = new java.util.LinkedHashMap<>();
		private final java.util.Map<String, ModrinthClient.ProjectVersion> resolvedByProjectId = new java.util.LinkedHashMap<>();

		record Row(String projectId, String title, ModrinthClient.ProjectVersion resolvedVersion, boolean alreadyInstalled) {
		}

		void refresh(Instance instance, ModrinthClient.ProjectVersion forVersion) {
			checkboxByProjectId.clear();
			resolvedByProjectId.clear();
			List<ModrinthClient.Dependency> required = forVersion.requiredDependencies();
			if (required.isEmpty()) {
				node.getChildren().clear();
				return;
			}
			node.getChildren().setAll(new Label("Checking dependencies..."));
			CompletableFuture.supplyAsync(() -> resolveRows(instance, required), Executors.newVirtualThreadPerTaskExecutor())
					.thenAccept(rows -> Platform.runLater(() -> {
						node.getChildren().clear();
						if (rows.isEmpty()) {
							return;
						}
						Label header = new Label("Dependencies");
						header.setFont(Font.font("System", FontWeight.BOLD, 12));
						node.getChildren().add(header);
						for (Row row : rows) {
							if (row.alreadyInstalled()) {
								node.getChildren().add(new Label("✓ " + row.title() + " (already installed)"));
							} else if (row.resolvedVersion() != null) {
								CheckBox checkBox = new CheckBox(row.title());
								checkBox.setSelected(true);
								checkboxByProjectId.put(row.projectId(), checkBox);
								resolvedByProjectId.put(row.projectId(), row.resolvedVersion());
								node.getChildren().add(checkBox);
							} else {
								node.getChildren().add(new Label("⚠ " + row.title() + " (no compatible version found)"));
							}
						}
					}));
		}

		private static List<Row> resolveRows(Instance instance, List<ModrinthClient.Dependency> required) {
			Set<String> installedProjectIds = InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD).stream()
					.map(InstalledAsset::projectId).collect(java.util.stream.Collectors.toSet());
			List<Row> rows = new java.util.ArrayList<>();
			for (ModrinthClient.Dependency dep : required) {
				boolean already = installedProjectIds.contains(dep.projectId());
				String title = ModrinthClient.getProject(dep.projectId()).map(ModrinthClient.Project::title).orElse(dep.projectId());
				ModrinthClient.ProjectVersion resolved = already ? null
						: (dep.versionId() != null ? ModrinthClient.getVersion(dep.versionId()) : newestCompatible(dep.projectId(), instance.mcVersion()))
								.orElse(null);
				rows.add(new Row(dep.projectId(), title, resolved, already));
			}
			return rows;
		}

		List<ModrinthClient.ProjectVersion> chosen() {
			List<ModrinthClient.ProjectVersion> result = new java.util.ArrayList<>();
			checkboxByProjectId.forEach((projectId, checkBox) -> {
				if (checkBox.isSelected()) {
					result.add(resolvedByProjectId.get(projectId));
				}
			});
			return result;
		}
	}

	private static Optional<ModrinthClient.ProjectVersion> newestCompatible(String projectId, String mcVersion) {
		try {
			return ModrinthClient.versions(projectId, mcVersion).stream().max(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	// ---- Shared helpers ----

	static Node iconView(String url, double size) {
		StackPane holder = new StackPane();
		holder.setPrefSize(size, size);
		holder.setMinSize(size, size);
		holder.getStyleClass().add("instance-icon-custom");
		ImageView view = new ImageView();
		view.setFitWidth(size);
		view.setFitHeight(size);
		view.setPreserveRatio(true);
		Image fallback = new Image(InstanceDetailView.class.getResourceAsStream(FALLBACK_ICON), size, size, true, true);
		view.setImage(fallback);
		RemoteIconLoader.load(view, url, () -> view.setImage(fallback));
		holder.getChildren().add(view);
		return holder;
	}

	static String truncate(String text, int maxLen) {
		if (text == null) {
			return "";
		}
		String oneLine = text.replace("\n", " ").strip();
		return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen - 1).strip() + "…";
	}

	static String formatCount(long count) {
		if (count >= 1_000_000) {
			return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
		}
		if (count >= 1_000) {
			return String.format(Locale.ROOT, "%.1fK", count / 1_000.0);
		}
		return String.valueOf(count);
	}

	private static String sizeOf(Path file) {
		try {
			long bytes = Files.size(file);
			return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
		} catch (IOException e) {
			return "";
		}
	}

	public static void openInFileManager(Path folder) throws IOException {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[] command = os.contains("win") ? new String[] {"explorer.exe", folder.toString()}
				: os.contains("mac") ? new String[] {"open", folder.toString()}
				: new String[] {"xdg-open", folder.toString()};
		new ProcessBuilder(command).start();
	}

	static Label loadingLabel(LauncherTheme theme) {
		return messageLabel("Searching...", theme);
	}

	static Label messageLabel(String message, LauncherTheme theme) {
		Label label = new Label(message);
		label.setTextFill(text(theme));
		return label;
	}

	static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}

	static void error(Stage owner, String title, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.initOwner(owner);
		alert.setTitle(title);
		alert.setHeaderText(title);
		alert.setContentText(message);
		DialogStyling.apply(alert);
		alert.showAndWait();
	}
}
