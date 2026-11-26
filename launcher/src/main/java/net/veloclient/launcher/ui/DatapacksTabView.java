package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.veloclient.launcher.data.DatapackAsset;
import net.veloclient.launcher.data.DatapackRegistry;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.launch.Downloader;
import net.veloclient.launcher.modrinth.ModrinthClient;
import net.veloclient.launcher.theme.LauncherTheme;
import net.veloclient.launcher.world.WorldInfo;
import net.veloclient.launcher.world.WorldScanner;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The "Datapacks" profile tab: pick one of the profile's singleplayer worlds
 * (icon/name/gamemode read straight from its {@code level.dat} - see {@link
 * WorldScanner}), then manage that world's {@code datapacks/} folder the
 * same way the Mods tab manages a profile's mods - installed list with
 * Modrinth metadata where known, full-page browse/search, click-to-detail,
 * dependency checklist on install.
 */
public final class DatapacksTabView {

	private DatapacksTabView() {
	}

	public static Node build(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme) {
		StackPane container = new StackPane();
		container.setPadding(new Insets(14, 4, 4, 4));

		WorldInfo[] selectedWorld = {null};
		Runnable[] showWorldPicker = new Runnable[1];
		Runnable[] showManage = new Runnable[1];
		Runnable[] showBrowse = new Runnable[1];
		Runnable[] activeBack = new Runnable[1];
		Consumer<String>[] openDetail = new Consumer[1];

		// 2026-11-25 design: installed datapacks on the left, Modrinth on the right (like mods and packs)
		AddPanel add = new AddPanel(owner, instance, theme, () -> showManage[0].run());
		showWorldPicker[0] = () -> {
			add.setWorld(null);
			container.getChildren().setAll(buildWorldPicker(instance, theme, world -> {
				selectedWorld[0] = world;
				showManage[0].run();
			}));
		};
		showManage[0] = () -> {
			activeBack[0] = showManage[0];
			add.setWorld(selectedWorld[0]);
			ScrollKeeper.setAll(container, buildManageView(owner, overlayHost, instance, theme, selectedWorld[0], showWorldPicker[0], showManage[0], showBrowse[0], openDetail[0]));
		};
		showBrowse[0] = () -> {
			activeBack[0] = showBrowse[0];
			container.getChildren().setAll(buildBrowseView(owner, overlayHost, instance, theme, selectedWorld[0], showManage[0], openDetail[0]));
		};
		openDetail[0] = projectId -> showDatapackDetail(owner, overlayHost, container, instance, theme, selectedWorld[0], projectId, () -> activeBack[0].run());

		showWorldPicker[0].run();
		VBox main = new VBox(container);
		VBox.setVgrow(container, Priority.ALWAYS);
		main.getStyleClass().add("profile-section-panel");
		main.setPadding(new Insets(16));
		net.veloclient.launcher.ui.glass.Glass.install(main, 24);
		HBox.setHgrow(main, Priority.ALWAYS);
		main.setMinWidth(0);
		HBox both = new HBox(14, main, add.panel);
		both.getProperties().put("velo.ownGlass", true);
		return both;
	}

	/** The right panel: search Modrinth datapacks for this version and install them into the world picked on the left. */
	private static final class AddPanel {
		final VBox panel;
		private final VBox results = new VBox(2);
		private final TextField search = new TextField();
		private final Label hint = new Label();
		private final Stage owner;
		private final Instance instance;
		private final LauncherTheme theme;
		private final Runnable installed;
		private WorldInfo world;
		private long generation;

		AddPanel(Stage owner, Instance instance, LauncherTheme theme, Runnable installed) {
			this.owner = owner;
			this.instance = instance;
			this.theme = theme;
			this.installed = installed;
			Label title = new Label("Add datapacks");
			title.getStyleClass().add("panel-title");
			Label source = new Label("MODRINTH · " + instance.mcVersion());
			source.getStyleClass().add("home-card-note");
			javafx.scene.layout.Region push = new javafx.scene.layout.Region();
			HBox.setHgrow(push, Priority.ALWAYS);
			HBox head = new HBox(title, push, source);
			head.setAlignment(Pos.BASELINE_LEFT);
			search.setPromptText("Search datapacks for " + instance.mcVersion());
			search.getStyleClass().add("topbar-search-field");
			hint.getStyleClass().add("t-muted");
			hint.setWrapText(true);
			ScrollPane scroll = new ScrollPane(results);
			scroll.setFitToWidth(true);
			scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
			scroll.getStyleClass().add("scroll-pane");
			VBox.setVgrow(scroll, Priority.ALWAYS);
			javafx.animation.PauseTransition debounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(380));
			debounce.setOnFinished(e -> load());
			search.textProperty().addListener((o, a, t) -> debounce.playFromStart());
			panel = new VBox(12, head, search, hint, scroll);
			panel.getStyleClass().addAll("profile-section-panel", "add-panel");
			panel.setPadding(new Insets(18, 16, 16, 16));
			panel.setMinWidth(340);
			panel.setPrefWidth(364);
			panel.setMaxWidth(364);
			net.veloclient.launcher.ui.glass.Glass.install(panel, 24);
			load();
		}

		void setWorld(WorldInfo world) {
			this.world = world;
			hint.setText(world == null ? "Pick a world on the left - datapacks are installed per world." : "Installs into " + world.levelName());
			load();
		}

		private void load() {
			long mine = ++generation;
			results.getChildren().setAll(Skeleton.list(4));
			String text = search.getText() == null ? "" : search.getText().strip();
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.search(text, "datapack", instance.mcVersion(), text.isBlank() ? "downloads" : "relevance", 0, 20).hits();
				} catch (IOException e) {
					return null;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(hits -> Platform.runLater(() -> {
				if (mine != generation) {
					return;
				}
				results.getChildren().clear();
				if (hits == null) {
					results.getChildren().add(InstanceDetailView.messageLabel("Modrinth can't be reached right now.", theme));
					return;
				}
				for (ModrinthClient.SearchHit hit : hits) {
					results.getChildren().add(row(hit));
				}
			}));
		}

		private Node row(ModrinthClient.SearchHit hit) {
			Node icon = InstanceDetailView.iconView(hit.iconUrl(), 38);
			Label name = new Label(hit.title());
			name.getStyleClass().add("t-strong");
			name.setMinWidth(0);
			Label by = new Label((hit.author() == null || hit.author().isBlank() ? "" : "by " + hit.author() + "  ·  ")
					+ InstanceDetailView.formatCount(hit.downloads()));
			by.getStyleClass().add("mod-author");
			by.setMinWidth(0);
			VBox text = new VBox(3, name, by);
			text.setMinWidth(0);
			HBox.setHgrow(text, Priority.ALWAYS);
			ProgressBar progress = new ProgressBar(0);
			progress.setPrefWidth(70);
			progress.setVisible(false);
			progress.setManaged(false);
			Button install = new Button("Add");
			install.getStyleClass().addAll("primary-button", "button-sm");
			install.setDisable(world == null);
			Path datapacksDir = world == null ? null : WorldScanner.datapacksDir(InstancePaths.savesDir(instance.id()).resolve(world.folderName()));
			if (datapacksDir != null && DatapackRegistry.loadAll(datapacksDir).stream().anyMatch(a -> a.projectId().equals(hit.projectId()))) {
				install.setText("Added");
				install.setDisable(true);
			}
			install.setOnAction(e -> {
				install.setDisable(true);
				CompletableFuture.supplyAsync(() -> {
					try {
						return ModrinthClient.versions(hit.projectId(), instance.mcVersion(), "datapack");
					} catch (IOException ex) {
						return null;
					}
				}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(versions -> Platform.runLater(() -> {
					if (versions == null || versions.isEmpty()) {
						install.setDisable(false);
						InstanceDetailView.error(owner, "No compatible version", hit.title() + " has no version for Minecraft " + instance.mcVersion() + ".");
						return;
					}
					progress.setVisible(true);
					progress.setManaged(true);
					pickAndInstall(owner, theme, instance, datapacksDir, hit.title(), versions, progress, () -> {
						progress.setVisible(false);
						progress.setManaged(false);
						install.setText("Added");
						installed.run();
					});
				}));
			});
			HBox row = new HBox(12, icon, text, progress, install);
			row.setAlignment(Pos.CENTER_LEFT);
			row.getStyleClass().add("friend-row");
			Tooltip.install(row, new Tooltip(hit.description()));
			return row;
		}
	}

	// ---- World picker ----

	private static Node buildWorldPicker(Instance instance, LauncherTheme theme, Consumer<WorldInfo> onSelect) {
		VBox root = new VBox(14);
		root.getChildren().add(InstanceDetailView.messageLabel("Pick a world to manage its datapacks.", theme));

		List<WorldInfo> worlds = WorldScanner.list(InstancePaths.savesDir(instance.id()));
		FlowPane grid = new FlowPane(14, 14);
		if (worlds.isEmpty()) {
			grid.getChildren().add(InstanceDetailView.messageLabel("No worlds yet - play this profile singleplayer at least once first.", theme));
		}
		for (WorldInfo world : worlds) {
			grid.getChildren().add(buildWorldCard(world, theme, () -> onSelect.accept(world)));
		}

		ScrollPane scroll = new ScrollPane(grid);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		root.getChildren().add(scroll);
		return root;
	}

	private static Node buildWorldCard(WorldInfo world, LauncherTheme theme, Runnable onClick) {
		VBox card = new VBox(8);
		card.getStyleClass().add("search-card");
		card.setPrefWidth(170);
		card.setAlignment(Pos.TOP_CENTER);
		card.setCursor(javafx.scene.Cursor.HAND);
		card.setOnMouseClicked(e -> onClick.run());

		StackPane iconHolder = new StackPane();
		iconHolder.setPrefSize(64, 64);
		iconHolder.setMinSize(64, 64);
		iconHolder.getStyleClass().add("instance-icon-custom");
		ImageView iconView = new ImageView();
		iconView.setFitWidth(64);
		iconView.setFitHeight(64);
		iconView.setPreserveRatio(true);
		Image image = world.iconFile() != null
				? new Image(world.iconFile().toUri().toString(), 64, 64, true, true)
				: UiImages.resource("/net/veloclient/launcher/images/logo.png", 64, 64);
		iconView.setImage(image);
		iconHolder.getChildren().add(iconView);
		card.getChildren().add(iconHolder);

		Label name = new Label(world.levelName());
		name.setFont(javafx.scene.text.Font.font("Geist", javafx.scene.text.FontWeight.BOLD, 13));
		name.setTextFill(InstanceDetailView.text(theme));
		name.setWrapText(true);
		name.setMaxWidth(150);

		String modeLabel = world.gamemode().displayName() + (world.hardcore() ? " (Hardcore)" : "");
		Label mode = new Label(modeLabel);
		mode.getStyleClass().add("version-tag");
		mode.setTextFill(InstanceDetailView.text(theme));

		card.getChildren().addAll(name, mode);
		return card;
	}

	// ---- Manage (installed list for the selected world) ----

	private static Node buildManageView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, WorldInfo world,
			Runnable backToWorlds, Runnable refresh, Runnable openBrowse, Consumer<String> openDetail) {
		VBox root = new VBox(14);

		HBox header = new HBox(10);
		header.setAlignment(Pos.CENTER_LEFT);
		Button back = BackButton.create("other worlds", null);
		back.setOnAction(e -> backToWorlds.run());
		Label heading = new Label(world.levelName());
		heading.setFont(javafx.scene.text.Font.font("Geist", javafx.scene.text.FontWeight.BOLD, 15));
		heading.setTextFill(InstanceDetailView.text(theme));
		header.getChildren().addAll(back, heading);
		root.getChildren().add(header);

		Path datapacksDir = WorldScanner.datapacksDir(InstancePaths.savesDir(instance.id()).resolve(world.folderName()));

		HBox actions = new HBox(8);
		Button addButton = new Button("Add from file...");
		addButton.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose datapack .zip files to add");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Datapack zip files", "*.zip"));
			List<File> files = chooser.showOpenMultipleDialog(owner);
			if (files == null) {
				return;
			}
			for (File file : files) {
				try {
					Files.createDirectories(datapacksDir);
					Files.copy(file.toPath(), datapacksDir.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ex) {
					InstanceDetailView.error(owner, "Failed to add " + file.getName(), ex.getMessage());
				}
			}
			refresh.run();
		});
		Button openFolderButton = new Button("Open folder");
		openFolderButton.setOnAction(e -> {
			try {
				Files.createDirectories(datapacksDir);
				InstanceDetailView.openInFileManager(datapacksDir);
			} catch (Exception ex) {
				InstanceDetailView.error(owner, "Couldn't open folder", ex.getMessage());
			}
		});
		addButton.getStyleClass().add("glass-button");
		openFolderButton.getStyleClass().add("glass-button");
		actions.getChildren().addAll(addButton, openFolderButton);
		root.getChildren().add(actions);

		VBox list = new VBox(8);
		refreshInstalledList(list, theme, datapacksDir, refresh, openDetail);
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		root.getChildren().add(scroll);

		return root;
	}

	private static void refreshInstalledList(VBox list, LauncherTheme theme, Path datapacksDir, Runnable refresh, Consumer<String> openDetail) {
		list.getChildren().clear();
		try {
			Files.createDirectories(datapacksDir);
		} catch (IOException ignored) {
			// Best-effort.
		}
		List<Path> files;
		try (Stream<Path> stream = Files.list(datapacksDir)) {
			files = stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".zip"))
					.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
		} catch (IOException e) {
			list.getChildren().add(InstanceDetailView.messageLabel("Couldn't read folder: " + e.getMessage(), theme));
			return;
		}
		if (files.isEmpty()) {
			list.getChildren().add(InstanceDetailView.messageLabel("No datapacks in this world yet - add one above.", theme));
			return;
		}
		var known = DatapackRegistry.asMap(DatapackRegistry.loadAll(datapacksDir));
		for (Path file : files) {
			String fileName = file.getFileName().toString();
			DatapackAsset asset = known.get(fileName);
			list.getChildren().add(buildFileRow(theme, datapacksDir, file, asset, refresh, openDetail));
		}
	}

	private static Node buildFileRow(LauncherTheme theme, Path datapacksDir, Path file, DatapackAsset asset, Runnable refresh, Consumer<String> openDetail) {
		HBox row = new HBox(12);
		row.getStyleClass().add("mod-row");
		row.setAlignment(Pos.CENTER_LEFT);

		if (asset != null) {
			Node icon = InstanceDetailView.iconView(asset.iconUrl(), 40);
			icon.setCursor(javafx.scene.Cursor.HAND);
			icon.setOnMouseClicked(e -> openDetail.accept(asset.projectId()));
			row.getChildren().add(icon);
		} else {
			row.getChildren().add(InstanceDetailView.iconView(null, 40));
		}

		VBox info = new VBox(2);
		Label title = new Label(asset != null ? asset.title() : file.getFileName().toString());
		title.setFont(javafx.scene.text.Font.font("Geist", javafx.scene.text.FontWeight.BOLD, 13));
		title.setTextFill(InstanceDetailView.text(theme));
		Label meta = new Label(asset != null ? "v" + asset.versionNumber() : "Not from Modrinth");
		meta.getStyleClass().add("version-tag");
		meta.setTextFill(InstanceDetailView.text(theme));
		info.getChildren().addAll(title, meta);
		HBox.setHgrow(info, Priority.ALWAYS);
		row.getChildren().add(info);

		Button remove = new Button("Remove");
		remove.setOnAction(e -> {
			try {
				Files.deleteIfExists(file);
				DatapackRegistry.forget(datapacksDir, file.getFileName().toString());
			} catch (IOException ignored) {
				// Best-effort.
			}
			refresh.run();
		});
		row.getChildren().add(remove);
		return row;
	}

	// ---- Browse ----

	private static Node buildBrowseView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, WorldInfo world,
			Runnable onBack, Consumer<String> openDetail) {
		VBox root = new VBox(14);
		VBox.setVgrow(root, Priority.ALWAYS);
		Path datapacksDir = WorldScanner.datapacksDir(InstancePaths.savesDir(instance.id()).resolve(world.folderName()));

		HBox topRow = new HBox(10);
		topRow.setAlignment(Pos.CENTER_LEFT);
		Button back = BackButton.create("installed", null);
		back.setOnAction(e -> onBack.run());
		Label heading = new Label("Browse datapacks for Minecraft " + instance.mcVersion());
		heading.setFont(javafx.scene.text.Font.font("Geist", javafx.scene.text.FontWeight.BOLD, 15));
		heading.setTextFill(InstanceDetailView.text(theme));
		topRow.getChildren().addAll(back, heading);
		root.getChildren().add(topRow);

		HBox searchRow = new HBox(8);
		TextField query = new TextField();
		query.setPromptText("Search datapacks...");
		HBox.setHgrow(query, Priority.ALWAYS);
		Button searchButton = new Button("Search");
		searchRow.getChildren().addAll(query, searchButton);
		root.getChildren().add(searchRow);

		FlowPane results = new FlowPane(14, 14);
		ScrollPane resultsScroll = new ScrollPane(results);
		resultsScroll.setFitToWidth(true);
		resultsScroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(resultsScroll, Priority.ALWAYS);
		root.getChildren().add(resultsScroll);

		int[] generation = {0};   // older searches' answers are dropped
		Runnable runSearch = () -> {
			int requestGeneration = ++generation[0];
			String text = query.getText();
			results.getChildren().setAll(InstanceDetailView.loadingLabel(theme));
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.search(text, "datapack", instance.mcVersion(), "relevance", 0, 40);
				} catch (IOException e) {
					return e;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				if (requestGeneration != generation[0]) {
					return;
				}
				if (result instanceof Exception ex) {
					results.getChildren().setAll(InstanceDetailView.messageLabel("Search failed: " + ex.getMessage(), theme));
					return;
				}
				@SuppressWarnings("unchecked")
				ModrinthClient.SearchResult search = (ModrinthClient.SearchResult) result;
				if (search.hits().isEmpty()) {
					results.getChildren().setAll(InstanceDetailView.messageLabel("No results.", theme));
					return;
				}
				results.getChildren().clear();
				for (ModrinthClient.SearchHit hit : search.hits()) {
					results.getChildren().add(buildDatapackCard(owner, instance, theme, datapacksDir, hit, openDetail));
				}
			}));
		};
		searchButton.setOnAction(e -> runSearch.run());
		query.setOnAction(e -> runSearch.run());
		runSearch.run();

		return root;
	}

	private static Node buildDatapackCard(Stage owner, Instance instance, LauncherTheme theme, Path datapacksDir,
			ModrinthClient.SearchHit hit, Consumer<String> openDetail) {
		VBox card = new VBox(8);
		card.getStyleClass().add("search-card");
		card.setPrefWidth(190);
		card.setMinHeight(230);
		card.setMaxHeight(230);
		card.setAlignment(Pos.TOP_CENTER);

		Node icon = InstanceDetailView.iconView(hit.iconUrl(), 64);
		icon.setCursor(javafx.scene.Cursor.HAND);
		icon.setOnMouseClicked(e -> openDetail.accept(hit.projectId()));
		card.getChildren().add(icon);

		Label title = new Label(hit.title());
		title.setFont(javafx.scene.text.Font.font("Geist", javafx.scene.text.FontWeight.BOLD, 13));
		title.setTextFill(InstanceDetailView.text(theme));
		title.setWrapText(true);
		title.setAlignment(Pos.CENTER);
		title.setMaxWidth(170);

		Label downloads = new Label(InstanceDetailView.formatCount(hit.downloads()) + " downloads"
				+ (hit.author() == null || hit.author().isBlank() ? "" : "  ·  by " + hit.author()));
		downloads.getStyleClass().add("version-tag");
		downloads.setTextFill(InstanceDetailView.text(theme));

		VBox spacer = new VBox();
		VBox.setVgrow(spacer, Priority.ALWAYS);

		ProgressBar progress = new ProgressBar(0);
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.setVisible(false);
		progress.setManaged(false);

		boolean alreadyInstalled = DatapackRegistry.loadAll(datapacksDir).stream().anyMatch(a -> a.projectId().equals(hit.projectId()));
		Button install = new Button(alreadyInstalled ? "Installed" : "Install...");
		install.getStyleClass().add("button-compact");
		install.setMaxWidth(Double.MAX_VALUE);
		install.setDisable(alreadyInstalled);
		install.setOnAction(e -> {
			install.setDisable(true);
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.versions(hit.projectId(), instance.mcVersion(), "datapack");
				} catch (IOException ex) {
					return ex;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				if (result instanceof Exception ex) {
					install.setDisable(false);
					InstanceDetailView.error(owner, "Couldn't load versions", ex.getMessage());
					return;
				}
				@SuppressWarnings("unchecked")
				List<ModrinthClient.ProjectVersion> versions = (List<ModrinthClient.ProjectVersion>) result;
				if (versions.isEmpty()) {
					install.setDisable(false);
					InstanceDetailView.error(owner, "No compatible version", hit.title() + " has no version published for Minecraft " + instance.mcVersion() + ".");
					return;
				}
				pickAndInstall(owner, theme, instance, datapacksDir, hit.title(), versions, progress, () -> {
					install.setText("Installed");
				});
			}));
		});

		card.getChildren().addAll(title, downloads, spacer, progress, install);
		return card;
	}

	// ---- Detail view ----

	private static void showDatapackDetail(Stage owner, StackPane overlayHost, StackPane container, Instance instance, LauncherTheme theme,
			WorldInfo world, String projectId, Runnable back) {
		container.getChildren().setAll(Skeleton.detail());
		Path datapacksDir = WorldScanner.datapacksDir(InstancePaths.savesDir(instance.id()).resolve(world.folderName()));
		CompletableFuture.supplyAsync(() -> {
			Optional<ModrinthClient.ProjectDetail> detail = ModrinthClient.getProjectDetail(projectId);
			if (detail.isEmpty()) {
				return Optional.<Object[]>empty();
			}
			List<ModrinthClient.ProjectVersion> versions;
			try {
				versions = ModrinthClient.versions(projectId, instance.mcVersion(), "datapack");
			} catch (IOException e) {
				versions = List.of();
			}
			return Optional.of(new Object[] {detail.get(), versions});
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result.isEmpty()) {
				container.getChildren().setAll(InstanceDetailView.messageLabel("Couldn't load datapack details.", theme));
				return;
			}
			Object[] pair = result.get();
			ModrinthClient.ProjectDetail detail = (ModrinthClient.ProjectDetail) pair[0];
			@SuppressWarnings("unchecked")
			List<ModrinthClient.ProjectVersion> versions = (List<ModrinthClient.ProjectVersion>) pair[1];
			Set<String> installedVersionIds = DatapackRegistry.loadAll(datapacksDir).stream()
					.map(DatapackAsset::versionId).collect(java.util.stream.Collectors.toSet());
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
				installDatapackVersion(instance, datapacksDir, version, progress,
						() -> showDatapackDetail(owner, overlayHost, container, instance, theme, world, projectId, back));
			}));
		}));
	}

	// ---- Install pipeline (with dependency resolution into the SAME world's datapacks folder, not modsDir) ----

	/**
	 * Installs the newest compatible version directly rather than opening a
	 * version-picker overlay - a datapack rarely has more than one or two
	 * versions per Minecraft version, and the click-to-detail page (see
	 * {@link #showDatapackDetail}) already lists every version individually
	 * with its own Install button for the rare case an older one is wanted.
	 * A datapack's declared Modrinth dependencies are other datapacks (never
	 * mods), so they're resolved and downloaded into this same {@code
	 * datapacksDir} - see {@link #installDatapackVersion}.
	 */
	private static void pickAndInstall(Stage owner, LauncherTheme theme, Instance instance, Path datapacksDir, String title,
			List<ModrinthClient.ProjectVersion> versions, ProgressBar progress, Runnable onDone) {
		ModrinthClient.ProjectVersion newest = versions.stream()
				.max(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished)).orElse(versions.get(0));
		installDatapackVersion(instance, datapacksDir, newest, progress, onDone);
	}

	private static void installDatapackVersion(Instance instance, Path datapacksDir, ModrinthClient.ProjectVersion version, ProgressBar progress, Runnable onDone) {
		progress.setVisible(true);
		progress.setManaged(true);
		CompletableFuture.runAsync(() -> {
			downloadDatapackFile(datapacksDir, version);
			for (ModrinthClient.Dependency dep : version.requiredDependencies()) {
				try {
					ModrinthClient.ProjectVersion depVersion = dep.versionId() != null
							? ModrinthClient.getVersion(dep.versionId()).orElse(null)
							: ModrinthClient.versions(dep.projectId(), instance.mcVersion(), "datapack").stream()
									.max(java.util.Comparator.comparing(ModrinthClient.ProjectVersion::datePublished)).orElse(null);
					if (depVersion != null) {
						downloadDatapackFile(datapacksDir, depVersion);
					}
				} catch (IOException ignored) {
					// Best-effort - the main datapack still installs even if a dependency lookup fails.
				}
			}
			Platform.runLater(() -> {
				progress.setVisible(false);
				progress.setManaged(false);
				onDone.run();
			});
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	private static void downloadDatapackFile(Path datapacksDir, ModrinthClient.ProjectVersion version) {
		Optional<ModrinthClient.VersionFile> fileOpt = version.primaryFile();
		if (fileOpt.isEmpty()) {
			return;
		}
		ModrinthClient.VersionFile file = fileOpt.get();
		try {
			Files.createDirectories(datapacksDir);
			Path dest = datapacksDir.resolve(file.filename());
			Downloader.ensure(URI.create(file.url()), dest, file.sha1(), file.size(), n -> { });
			ModrinthClient.getProject(version.projectId()).ifPresent(project ->
					DatapackRegistry.record(datapacksDir, new DatapackAsset(project.id(), version.id(), file.filename(),
							project.title(), project.description(), project.iconUrl(), version.versionNumber())));
		} catch (IOException ignored) {
			// Best-effort - the file just won't show up if this fails.
		}
	}
}
