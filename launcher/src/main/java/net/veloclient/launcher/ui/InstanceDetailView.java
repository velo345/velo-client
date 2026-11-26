package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
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

	/** What the launcher adds to the page header: the profile's icon, a meta line, its menu actions. */
	public record Header(Node icon, String meta, Runnable edit, List<javafx.scene.control.MenuItem> menu, Runnable javaSettings) {
	}

	/** Play in the profile header (glass design): set by the launcher, starts this profile. */
	public static java.util.function.Consumer<Instance> play;

	public static Node build(Stage owner, Instance instance, LauncherTheme theme, Runnable onBack) {
		return build(owner, instance, theme, onBack, null);
	}

	/** Which section was open last, per profile (kept while the launcher runs). */
	private static final java.util.Map<String, String> LAST_SECTION = new java.util.HashMap<>();

	/**
	 * The profile page: a banner with the profile's icon, name and versions, then a section list on the left
	 * (Mods, Resource packs, Shaders, Worlds, Datapacks, Schematics, Modpacks, then Java & memory and logs) and the
	 * open section on the right. Sections are built the first time they're opened.
	 */
	public static Node build(Stage owner, Instance instance, LauncherTheme theme, Runnable onBack, Header header) {
		StackPane overlayHost = new StackPane();
		overlayHost.setPickOnBounds(false);

		// ---- Banner ----
		Button back = new Button();
		back.setGraphic(Icons.icon("arrow-left", 16, javafx.scene.paint.Color.web("#E4E4E8"), 2.2));
		back.getStyleClass().add("icon-button");
		back.setStyle("-fx-background-radius: 999; -fx-border-radius: 999;");
		back.setTooltip(new Tooltip("Back"));
		back.setOnAction(e -> onBack.run());
		Node icon = header != null && header.icon() != null ? header.icon() : iconView(null, 64);
		StackPane iconBox = new StackPane(icon);
		iconBox.setStyle("-fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 16; -fx-background-radius: 16;");
		Label title = new Label(instance.name());
		title.getStyleClass().add("t-title");
		// 2026-11-25 design chip: "MC" small, the version, a chevron - opens the version switcher popover
		Label mcKind = new Label("MC");
		mcKind.getStyleClass().add("chip-kind");
		Label mcValue = new Label(instance.mcVersion());
		mcValue.getStyleClass().add("chip-value");
		HBox mcContent = new HBox(6, mcKind, mcValue, Icons.icon("chevron-down", 12, javafx.scene.paint.Color.WHITE, 2.4));
		mcContent.setAlignment(Pos.CENTER_LEFT);
		Label mc = new Label();
		mc.setGraphic(mcContent);
		mc.getStyleClass().add("header-chip");
		Tooltip.install(mc, new Tooltip("Switch Minecraft version"));
		mc.setOnMouseClicked(e -> VersionMigration.popover(mc, overlayHost, instance, onBack));
		Label loader = LoaderPicker.chip(instance, null);
		HBox chips = new HBox(8, mc, loader);
		chips.setAlignment(Pos.CENTER_LEFT);
		Node graphicsApi = GraphicsApiToggle.build(instance);
		VBox graphicsBox = null;
		if (graphicsApi != null) {
			Label graphicsLabel = new Label("GRAPHICS · THIS PROFILE");
			graphicsLabel.getStyleClass().add("home-card-note");
			graphicsBox = new VBox(4, graphicsLabel, graphicsApi);
			graphicsBox.setAlignment(Pos.CENTER_RIGHT);
		}
		VBox titles = new VBox(8, title, chips);
		Region headerSpacer = new Region();
		HBox.setHgrow(headerSpacer, Priority.ALWAYS);
		HBox banner = new HBox(16, back, iconBox, titles, headerSpacer);
		if (graphicsBox != null) {
			banner.getChildren().add(graphicsBox);
		}
		banner.setAlignment(Pos.CENTER_LEFT);
		// 2026-11-25 design: no separate Edit/Profile buttons - everything is in the ⋯ menu (rename & icon first)
		if (header != null && header.menu() != null && !header.menu().isEmpty()) {
			Button more = new Button();
			more.setGraphic(Icons.icon("more", 18, javafx.scene.paint.Color.web("#E4E4E8"), 3));
			more.getStyleClass().add("icon-button");
			javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
			menu.getItems().addAll(header.menu());
			more.setOnAction(e -> menu.show(more, javafx.geometry.Side.BOTTOM, 0, 6));
			more.getStyleClass().add("glass-button");
			more.setTooltip(new Tooltip("Profile options"));
			banner.getChildren().add(more);
			banner.getProperties().put("velo.menu", menu);
		}
		if (play != null) {
			Button playButton = new Button("Play");
			playButton.setGraphic(Icons.icon("play", 15, javafx.scene.paint.Color.WHITE));
			playButton.getStyleClass().add("header-play");
			playButton.setMinWidth(Region.USE_PREF_SIZE);
			playButton.setOnAction(e -> play.accept(instance));
			banner.getChildren().add(playButton);
		}
		banner.setPadding(new Insets(20, 22, 20, 18));
		// 2026-11: the banner sits on its version's art, graded dark (like the redesign).
		javafx.scene.image.ImageView art = new javafx.scene.image.ImageView();
		art.setPreserveRatio(false);
		art.setOpacity(0);
		// Unmanaged: the picture follows the banner's size but never feeds back into it. Managed, its fit size became the
		// StackPane's preferred size, which grew the banner, which grew the picture... until the banner filled the page.
		art.setManaged(false);
		art.fitWidthProperty().bind(banner.widthProperty());
		art.fitHeightProperty().bind(banner.heightProperty());
		art.setMouseTransparent(true);
		VersionArt.load(instance.mcVersion(), image -> {
			art.setImage(image);
			// crop to the banner's shape so the picture isn't squashed
			art.setViewport(null);
			double ratio = Math.max(1, banner.getWidth()) / Math.max(1, banner.getHeight());
			double h = image.getWidth() / ratio;
			if (h < image.getHeight()) {
				art.setViewport(new javafx.geometry.Rectangle2D(0, (image.getHeight() - h) / 2, image.getWidth(), h));
			}
		});
		Region artShade = new Region();
		artShade.getStyleClass().add("profile-banner-shade");
		artShade.setMouseTransparent(true);
		StackPane bannerBox = new StackPane(art, artShade, banner);
		bannerBox.getStyleClass().add("profile-banner");
		javafx.scene.shape.Rectangle bannerClip = new javafx.scene.shape.Rectangle();
		bannerClip.widthProperty().bind(bannerBox.widthProperty());
		bannerClip.heightProperty().bind(bannerBox.heightProperty());
		bannerClip.setArcWidth(28);
		bannerClip.setArcHeight(28);
		bannerBox.setClip(bannerClip);

		// ---- Sections ----
		StackPane sectionHost = new StackPane();
		HBox.setHgrow(sectionHost, Priority.ALWAYS);
		java.util.Map<String, Node> built = new java.util.HashMap<>();
		java.util.LinkedHashMap<String, java.util.function.Supplier<Node>> sections = new java.util.LinkedHashMap<>();
		sections.put("Mods", () -> buildPackTab(owner, overlayHost, instance, theme, InstalledAssetStore.Kind.MOD,
				InstancePaths.modsDir(instance.id()), List.of("*.jar")));
		sections.put("Resource packs", () -> buildPackTab(owner, overlayHost, instance, theme, InstalledAssetStore.Kind.RESOURCE_PACK,
				InstancePaths.resourcePacksDir(instance.id()), List.of("*.zip")));
		sections.put("Shaders", () -> buildPackTab(owner, overlayHost, instance, theme, InstalledAssetStore.Kind.SHADER_PACK,
				InstancePaths.shaderPacksDir(instance.id()), List.of("*.zip")));
		sections.put("Worlds", () -> WorldsTabView.build(owner, instance, theme));
		sections.put("Datapacks", () -> DatapacksTabView.build(owner, overlayHost, instance, theme));
		sections.put("Schematics", () -> SchematicsTabView.build(owner, overlayHost, instance, theme));
		sections.put("Modpacks", () -> ModpacksTabView.build(owner, overlayHost, instance, theme));
		@SuppressWarnings("unchecked")
		java.util.function.Consumer<net.veloclient.launcher.doctor.Diagnosis>[] doctorBadge = new java.util.function.Consumer[1];
		{
			sections.put("Doctor", () -> DoctorPanel.section(instance, new DoctorPanel.Host() {
				@Override
				public void shown(net.veloclient.launcher.doctor.Diagnosis diagnosis) {
					if (doctorBadge[0] != null) {
						doctorBadge[0].accept(diagnosis);
					}
				}

				@Override
				public void fixed(boolean all) {
					// The other sections were built before the fixes - they're rebuilt when opened next.
					built.keySet().removeIf(k -> !k.equals("Doctor"));
				}

				@Override
				public void report(net.veloclient.launcher.doctor.Diagnosis diagnosis) {
					BugReportDialog.show(owner, "Doctor couldn't fix: " + (diagnosis.issues().isEmpty() ? instance.name() : diagnosis.issues().get(0).title()),
							null, net.veloclient.launcher.doctor.Doctor.reportData(instance, diagnosis));
				}
			}));
		}
		java.util.Map<String, Long> counts = new java.util.HashMap<>();
		counts.put("Mods", userFileCount(InstancePaths.modsDir(instance.id())));
		counts.put("Resource packs", userFileCount(InstancePaths.resourcePacksDir(instance.id())));
		counts.put("Shaders", userFileCount(InstancePaths.shaderPacksDir(instance.id())));
		counts.put("Worlds", folderCount(InstancePaths.savesDir(instance.id())));

		// 2026-11-25 glass: the sections as a rail on the left (icon, name, count), the profile check under them.
		VBox nav = new VBox(2);
		java.util.Map<String, Button> navButtons = new java.util.LinkedHashMap<>();
		java.util.function.Consumer<String> open = new java.util.function.Consumer<>() {
			@Override
			public void accept(String name) {
				LAST_SECTION.put(instance.id(), name);
				navButtons.forEach((n, b) -> b.getStyleClass().remove("rail-active"));
				navButtons.get(name).getStyleClass().add("rail-active");
				Node node = built.get(name);
				if (node == null) {
					sectionHost.getChildren().setAll(Skeleton.list(4));
					Platform.runLater(() -> {
						Node made = glassed(sections.get(name).get());
						built.put(name, made);
						if (name.equals(LAST_SECTION.get(instance.id()))) {
							sectionHost.getChildren().setAll(made);
							UiMotion.enter(made);
						}
					});
				} else {
					sectionHost.getChildren().setAll(node);
					UiMotion.enter(node);
				}
			}
		};
		for (String name : sections.keySet()) {
			Button button = navButton(name, counts.get(name));
			button.setOnAction(e -> open.accept(name));
			navButtons.put(name, button);
			nav.getChildren().add(button);
		}
		if (navButtons.containsKey("Doctor")) {
			// The badge fills in once the (cached, quick) check is done: a red count, or a green tick.
			Button doctor = navButtons.get("Doctor");
			doctorBadge[0] = d -> {
				long serious = d.issues().stream().filter(i -> i.severity() != net.veloclient.launcher.doctor.Diagnosis.Severity.TIP).count();
				if (doctor.getProperties().get("count") instanceof Label badge) {
					badge.setText(serious == 0 ? "✓" : String.valueOf(serious));
					badge.getStyleClass().removeAll("rail-count-ok", "rail-count-bad");
					badge.getStyleClass().add(serious == 0 ? "rail-count-ok" : "rail-count-bad");
					badge.setVisible(true);
				}
			};
			CompletableFuture.supplyAsync(() -> net.veloclient.launcher.doctor.Doctor.check(instance), Executors.newVirtualThreadPerTaskExecutor())
					.thenAccept(d -> Platform.runLater(() -> doctorBadge[0].accept(d)));
		}
		javafx.scene.control.MenuButton profileMenu = new javafx.scene.control.MenuButton("Profile");
		profileMenu.setGraphic(Icons.icon("sliders", 15, javafx.scene.paint.Color.web("#E4E4E8")));
		profileMenu.getStyleClass().add("profile-menu");
		profileMenu.setMinWidth(Region.USE_PREF_SIZE);
		{
			javafx.scene.control.MenuItem migrate = new javafx.scene.control.MenuItem("Change version");
			migrate.setOnAction(e -> VersionMigration.show(overlayHost, instance, onBack));
			profileMenu.getItems().add(migrate);
		}
		if (header != null && header.javaSettings() != null) {
			javafx.scene.control.MenuItem java = new javafx.scene.control.MenuItem("Java & memory");
			java.setOnAction(e -> header.javaSettings().run());
			profileMenu.getItems().add(java);
		}
		javafx.scene.control.MenuItem logs = new javafx.scene.control.MenuItem("Logs & crashes");
		logs.setOnAction(e -> {
			try {
				Files.createDirectories(InstancePaths.logsDir(instance.id()));
				openInFileManager(InstancePaths.logsDir(instance.id()));
			} catch (Exception ex) {
				error(owner, "Couldn't open logs", ex.getMessage());
			}
		});
		javafx.scene.control.MenuItem folder = new javafx.scene.control.MenuItem("Open profile folder");
		folder.setOnAction(e -> {
			try {
				Files.createDirectories(InstancePaths.gameDir(instance.id()));
				openInFileManager(InstancePaths.gameDir(instance.id()));
			} catch (Exception ex) {
				error(owner, "Couldn't open folder", ex.getMessage());
			}
		});
		profileMenu.getItems().addAll(logs, folder);
		// the profile's own actions (version, Java, logs, folder) join the ⋯ menu in the banner
		if (banner.getProperties().get("velo.menu") instanceof javafx.scene.control.ContextMenu menu) {
			menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
			menu.getItems().addAll(List.copyOf(profileMenu.getItems()));
		} else {
			profileMenu.getStyleClass().add("glass-button");
			banner.getChildren().add(banner.getChildren().indexOf(headerSpacer) + 1, profileMenu);
		}

		VBox aside = profileAside(instance, overlayHost, onBack, open);
		Node check = aside.getChildren().getFirst();
		Region railPush = new Region();
		VBox.setVgrow(railPush, Priority.ALWAYS);
		VBox rail = new VBox(2, nav, railPush, check);
		rail.getStyleClass().add("profile-rail");
		rail.setPadding(new Insets(10, 8, 10, 8));
		rail.setMinWidth(208);
		rail.setPrefWidth(208);
		rail.setMaxWidth(208);
		net.veloclient.launcher.ui.glass.Glass.install(rail, 24);
		VBox sectionPanel = new VBox(sectionHost);
		VBox.setVgrow(sectionHost, Priority.ALWAYS);
		HBox.setHgrow(sectionPanel, Priority.ALWAYS);
		HBox body = new HBox(14, rail, sectionPanel);
		VBox.setVgrow(body, Priority.ALWAYS);
		VBox root = new VBox(14, bannerBox, body);
		open.accept(LAST_SECTION.getOrDefault(instance.id(), "Mods"));
		return new StackPane(root, overlayHost);
	}

	private static VBox profileAside(Instance instance, StackPane overlayHost, Runnable onBack, java.util.function.Consumer<String> open) {
		Label checkTitle = new Label("PROFILE CHECK");
		checkTitle.getStyleClass().add("aside-title");
		Region square = new Region();
		square.getStyleClass().addAll("status-square", "status-square-off");
		Label status = new Label("Checking...");
		status.getStyleClass().add("aside-status");
		status.setGraphic(square);
		status.setGraphicTextGap(9);
		Label detail = new Label("Mods, Java, memory and graphics are checked against " + instance.mcVersion() + ".");
		detail.getStyleClass().add("aside-text");
		detail.setWrapText(true);
		Button doctor = new Button("Open the Doctor");
		doctor.getStyleClass().addAll("glass-button", "button-sm");
		doctor.setMaxWidth(Double.MAX_VALUE);
		doctor.setOnAction(e -> open.accept("Doctor"));
		VBox check = new VBox(10, checkTitle, status, detail, doctor);
		check.getStyleClass().add("aside-card");
		CompletableFuture.supplyAsync(() -> net.veloclient.launcher.doctor.Doctor.check(instance), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(d -> Platform.runLater(() -> {
					long serious = d.issues().stream().filter(i -> i.severity() != net.veloclient.launcher.doctor.Diagnosis.Severity.TIP).count();
					square.getStyleClass().remove("status-square-off");
					if (serious > 0) {
						square.getStyleClass().add("status-square-request");
					}
					status.setText(serious == 0 ? "Ready to play" : serious == 1 ? "1 thing to fix" : serious + " things to fix");
					detail.setText(serious == 0 ? "Everything in this profile fits Minecraft " + instance.mcVersion() + "."
							: "The Doctor found what's wrong and can fix it in one click.");
				}));

		Label switchTitle = new Label("SWITCH VERSION");
		switchTitle.getStyleClass().add("aside-title");
		Label switchText = new Label("Velo checks every mod first and shows what changes before anything moves.");
		switchText.getStyleClass().add("aside-text");
		switchText.setWrapText(true);
		javafx.scene.layout.FlowPane chips = new javafx.scene.layout.FlowPane(6, 6);
		int shown = 0;
		List<net.veloclient.launcher.launch.GameVersion> newestFirst = new java.util.ArrayList<>(List.of(net.veloclient.launcher.launch.GameVersion.values()));
		java.util.Collections.reverse(newestFirst);
		for (net.veloclient.launcher.launch.GameVersion version : newestFirst) {
			shown++;
			Button chip = new Button(version.id());
			chip.getStyleClass().add(version.id().equals(instance.mcVersion()) ? "aside-chip-on" : "aside-chip");
			chip.setMinWidth(Region.USE_PREF_SIZE);
			chip.setOnAction(e -> {
				if (!version.id().equals(instance.mcVersion())) {
					VersionMigration.show(overlayHost, instance, onBack);
				}
			});
			chips.getChildren().add(chip);
		}
		// every version right there (2026-11) - no extra "All versions" step
		VBox versions = new VBox(10, switchTitle, switchText, chips);
		versions.getStyleClass().add("aside-card");

		VBox aside = new VBox(12, check, versions);
		aside.setMinWidth(260);
		aside.setPrefWidth(280);
		aside.setMaxWidth(300);
		return aside;
	}

	/** A section that doesn't bring its own glass panels sits in one (pack sections have two: list + Add). */
	private static Node glassed(Node section) {
		if (section.getProperties().containsKey("velo.ownGlass")) {
			return section;
		}
		VBox panel = new VBox(section);
		VBox.setVgrow(section, Priority.ALWAYS);
		panel.getStyleClass().add("profile-section-panel");
		panel.setPadding(new Insets(16));
		net.veloclient.launcher.ui.glass.Glass.install(panel, 24);
		return panel;
	}

	private static final java.util.Map<String, String> RAIL_ICONS = java.util.Map.of("Mods", "box", "Resource packs", "image",
			"Shaders", "sun", "Worlds", "globe", "Datapacks", "file-code", "Schematics", "cube", "Modpacks", "package", "Doctor", "pulse");

	/** One row of the profile rail: icon, name, count on the right (the Doctor's fills in once its check is done). */
	private static Button navButton(String name, Long count) {
		Label text = new Label(name);
		text.getStyleClass().add("rail-text");
		Region push = new Region();
		HBox.setHgrow(push, Priority.ALWAYS);
		Label badge = new Label(count == null ? "" : String.valueOf(count));
		badge.getStyleClass().add("rail-count");
		badge.setVisible(count != null);
		HBox content = new HBox(11, Icons.icon(RAIL_ICONS.getOrDefault(name, "box"), 17, javafx.scene.paint.Color.web("#F4EFEE")), text, push, badge);
		content.setAlignment(Pos.CENTER_LEFT);
		Button button = new Button();
		button.setGraphic(content);
		button.getStyleClass().setAll("button", "rail-item", "no-motion");
		button.setMaxWidth(Double.MAX_VALUE);
		content.prefWidthProperty().bind(button.widthProperty().subtract(22));
		button.getProperties().put("count", badge);
		return button;
	}

	private static long folderCount(Path folder) {
		try (Stream<Path> stream = Files.list(folder)) {
			return stream.filter(Files::isDirectory).count();
		} catch (IOException e) {
			return 0;
		}
	}

	/** A tab whose content is built the first time it's selected: a loading skeleton shows for that one frame. */
	private static Tab lazyTab(String name, java.util.function.Supplier<Node> content) {
		Tab tab = new Tab(name, Skeleton.list(4));
		boolean[] built = {false};
		tab.selectedProperty().addListener((o, was, selected) -> {
			if (selected && !built[0]) {
				built[0] = true;
				// Next pulse: the tab switch (and skeleton) paints first, then the real content replaces it.
				javafx.application.Platform.runLater(() -> {
					Node node = content.get();
					tab.setContent(node);
					UiMotion.enter(node);
				});
			}
		});
		return tab;
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

		// The installed view is built once and kept: actions (toggle, remove, update) only refresh its
		// rows, so the list keeps its scroll position instead of jumping back to the top.
		InstalledView[] installedView = new InstalledView[1];
		showInstalled[0] = () -> {
			activeBack[0] = showInstalled[0];
			if (installedView[0] == null) {
				installedView[0] = buildInstalledView(owner, overlayHost, instance, theme, kind, folder, fileFilters, identifyAttempted,
						showSearch[0], openDetail[0]);
			} else {
				installedView[0].refresh().run();
			}
			container.getChildren().setAll(installedView[0].node());
			UiMotion.enter(installedView[0].node());
		};
		showSearch[0] = () -> {
			activeBack[0] = showSearch[0];
			Node search = buildSearchView(owner, overlayHost, instance, theme, kind, folder, showInstalled[0], openDetail[0]);
			container.getChildren().setAll(search);
			UiMotion.enter(search);
		};
		openDetail[0] = projectId -> showProjectDetail(owner, container, instance, theme, kind, folder, projectId, () -> activeBack[0].run());

		// Nothing installed yet (Velo's own jars don't count): open straight on Modrinth so the
		// obvious next step - finding something to add - is already on screen.
		// 2026-11-25 design: the installed list in one glass panel, "Add mods" (Modrinth search, categories, drop zone)
		// in a second one on the right - the old full-page search is gone.
		showInstalled[0].run();
		VBox main = new VBox(container);
		VBox.setVgrow(container, Priority.ALWAYS);
		main.getStyleClass().add("profile-section-panel");
		main.setPadding(new Insets(16));
		net.veloclient.launcher.ui.glass.Glass.install(main, 24);
		HBox.setHgrow(main, Priority.ALWAYS);
		main.setMinWidth(0);
		Node add = addPanel(owner, overlayHost, instance, theme, kind, folder, openDetail[0], () -> {
			if (installedView[0] != null) {
				installedView[0].refresh().run();
			}
		});
		HBox both = new HBox(14, main, add);
		both.getProperties().put("velo.ownGlass", true);
		return both;
	}

	/** The right panel of a pack section (design): add from Modrinth with a search, category pills, Install, and a drop zone. */
	private static Node addPanel(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, java.util.function.Consumer<String> openDetail, Runnable installed) {
		String noun = switch (kind) {
			case MOD -> "mods";
			case RESOURCE_PACK -> "resource packs";
			case SHADER_PACK -> "shaders";
			default -> "files";
		};
		Label title = new Label("Add " + noun);
		title.getStyleClass().add("panel-title");
		Label source = new Label("MODRINTH · " + instance.mcVersion());
		source.getStyleClass().add("home-card-note");
		Region push = new Region();
		HBox.setHgrow(push, Priority.ALWAYS);
		HBox head = new HBox(title, push, source);
		head.setAlignment(Pos.BASELINE_LEFT);
		TextField search = new TextField();
		search.setPromptText("Search " + noun + " for " + instance.mcVersion());
		search.getStyleClass().add("topbar-search-field");
		String[] categories = switch (kind) {
			case MOD -> new String[] {"Popular", "Performance", "Utility", "Map", "Building"};
			case RESOURCE_PACK -> new String[] {"Popular", "Vanilla-like", "PvP", "32x", "Dark"};
			case SHADER_PACK -> new String[] {"Popular", "Light", "Realistic", "Fantasy"};
			default -> new String[] {"Popular"};
		};
		javafx.scene.layout.FlowPane pills = new javafx.scene.layout.FlowPane(6, 6);
		VBox results = new VBox(2);
		ScrollPane scroll = new ScrollPane(results);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		long[] generation = {0};
		java.util.function.Consumer<String> load = query -> {
			long mine = ++generation[0];
			results.getChildren().setAll(Skeleton.list(4));
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.search(query, kind.modrinthProjectType(), instance.mcVersion(), query.isBlank() ? "downloads" : "relevance", 0, 20).hits();
				} catch (IOException e) {
					return null;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(hits -> Platform.runLater(() -> {
				if (mine != generation[0]) {
					return;
				}
				results.getChildren().clear();
				if (hits == null) {
					results.getChildren().add(messageLabel("Modrinth can't be reached right now.", theme));
					return;
				}
				for (ModrinthClient.SearchHit hit : hits) {
					Node row = buildSearchResultRow(owner, overlayHost, instance, theme, kind, folder, hit, openDetail);
					// an install that finished refreshes the installed list on the left
					row.lookupAll(".button").forEach(n -> {
						if (n instanceof Button b) {
							b.textProperty().addListener((o, a, t) -> {
								if (t != null && (t.startsWith("Added") || t.startsWith("Installed"))) {
									installed.run();
								}
							});
						}
					});
					results.getChildren().add(row);
				}
				if (hits.isEmpty()) {
					results.getChildren().add(messageLabel("Nothing found.", theme));
				}
			}));
		};
		Button[] active = new Button[1];
		for (String category : categories) {
			Button pill = new Button(category);
			pill.getStyleClass().setAll("button", "add-pill");
			if (active[0] == null) {
				active[0] = pill;
				pill.getStyleClass().add("add-pill-on");
			}
			pill.setOnAction(e -> {
				active[0].getStyleClass().remove("add-pill-on");
				pill.getStyleClass().add("add-pill-on");
				active[0] = pill;
				search.clear();
				load.accept(category.equals("Popular") ? "" : category.toLowerCase(java.util.Locale.ROOT));
			});
			pills.getChildren().add(pill);
		}
		javafx.animation.PauseTransition debounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(380));
		debounce.setOnFinished(e -> load.accept(search.getText().strip()));
		search.textProperty().addListener((o, a, t) -> debounce.playFromStart());
		// drop zone: files dragged in land in this section's folder
		Label dropText = new Label("Drop " + (kind == InstalledAssetStore.Kind.MOD ? ".jar files" : ".zip files") + " here");
		dropText.getStyleClass().add("t-muted");
		HBox drop = new HBox(10, Icons.icon("upload", 18, javafx.scene.paint.Color.web("#C9C2C3")), dropText);
		drop.setAlignment(Pos.CENTER);
		drop.getStyleClass().add("add-drop");
		drop.setMinHeight(58);
		drop.setOnDragOver(e -> {
			if (e.getDragboard().hasFiles()) {
				e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
				drop.getStyleClass().add("add-drop-hover");
			}
			e.consume();
		});
		drop.setOnDragExited(e -> drop.getStyleClass().remove("add-drop-hover"));
		drop.setOnDragDropped(e -> {
			boolean done = false;
			for (File file : e.getDragboard().getFiles()) {
				try {
					Files.createDirectories(folder);
					Files.copy(file.toPath(), folder.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
					done = true;
				} catch (IOException ex) {
					error(owner, "Failed to add " + file.getName(), ex.getMessage());
				}
			}
			e.setDropCompleted(done);
			e.consume();
			installed.run();
		});
		drop.setOnMouseClicked(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose files to add");
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
			installed.run();
		});
		VBox panel = new VBox(12, head, search, pills, scroll, drop);
		panel.getStyleClass().addAll("profile-section-panel", "add-panel");
		panel.setPadding(new Insets(18, 16, 16, 16));
		panel.setMinWidth(340);
		panel.setPrefWidth(364);
		panel.setMaxWidth(364);
		net.veloclient.launcher.ui.glass.Glass.install(panel, 24);
		load.accept("");
		return panel;
	}

	/** Files in the folder a user added themselves (Velo Client and Fabric API are managed by the launcher). */
	private static long userFileCount(Path folder) {
		try (Stream<Path> stream = Files.list(folder)) {
			return stream.filter(Files::isRegularFile).map(p -> enabledName(p.getFileName().toString()))
					.filter(name -> !name.startsWith("velo-client-") && !name.startsWith("fabric-api-") && !name.startsWith("velo-addons-")).count();
		} catch (IOException e) {
			return 0;
		}
	}

	private record InstalledView(Node node, Runnable refresh) {
	}

	/** Async-loads a project's full detail (description, gallery, every compatible version) and swaps it into {@code container} - see {@link ProjectDetailView}. */
	private static void showProjectDetail(Stage owner, StackPane container, Instance instance, LauncherTheme theme,
			InstalledAssetStore.Kind kind, Path folder, String projectId, Runnable back) {
		container.getChildren().setAll(Skeleton.detail());
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

	private static InstalledView buildInstalledView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, List<String> fileFilters, Set<String> identifyAttempted, Runnable openSearch,
			java.util.function.Consumer<String> openDetail) {
		VBox root = new VBox(12);

		VBox installedList = new VBox(0);
		installedList.getStyleClass().add("mod-table");
		ScrollPane scroll = new ScrollPane(installedList);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		String noun = switch (kind) {
			case MOD -> "mods";
			case RESOURCE_PACK -> "resource packs";
			case SHADER_PACK -> "shader packs";
			default -> "files";
		};
		Label count = new Label();
		count.getStyleClass().add("pack-count");
		TextField filter = new TextField();
		filter.setPromptText("Filter installed " + noun + "...");
		filter.setPrefWidth(220);

		UpdateTracker updates = new UpdateTracker();
		// Multi-select and the per-row version dropdown.
		PackSelection selection = new PackSelection(owner, instance, kind, folder, noun);
		Button updateAll = new Button();
		updateAll.getStyleClass().add("update-all-button");
		updateAll.setVisible(false);
		updateAll.setManaged(false);
		ProgressBar bulkProgress = new ProgressBar(0);
		bulkProgress.setPrefWidth(120);
		bulkProgress.setVisible(false);
		bulkProgress.setManaged(false);

		Runnable[] listRefresh = new Runnable[1];
		listRefresh[0] = () -> {
			// Keep the exact pixel offset: rows are rebuilt, the viewport shouldn't move.
			double contentHeight = installedList.getHeight();
			double viewport = scroll.getViewportBounds().getHeight();
			double offsetPx = scroll.getVvalue() * Math.max(0, contentHeight - viewport);
			updates.clear();
			refreshInstalledList(owner, overlayHost, installedList, instance, theme, kind, folder, identifyAttempted, listRefresh[0],
					openDetail, filter.getText(), updates, count, noun, selection);
			if (offsetPx > 0) {
				restoreScroll(scroll, installedList, offsetPx);
			}
		};
		updates.onChange = () -> {
			int n = updates.pending.size();
			updateAll.setText(n == 1 ? "Update 1 " + noun.substring(0, noun.length() - 1) : "Update all (" + n + ")");
			updateAll.setVisible(n > 0);
			updateAll.setManaged(n > 0);
		};
		updateAll.setOnAction(e -> {
			List<UpdateTracker.Pending> todo = List.copyOf(updates.pending.values());
			if (todo.isEmpty()) {
				return;
			}
			updateAll.setDisable(true);
			bulkProgress.setVisible(true);
			bulkProgress.setManaged(true);
			CompletableFuture.runAsync(() -> {
				for (int i = 0; i < todo.size(); i++) {
					UpdateTracker.Pending item = todo.get(i);
					int index = i;
					Platform.runLater(() -> updateAll.setText("Updating " + (index + 1) + " of " + todo.size() + "..."));
					downloadAndRecord(instance, kind, folder, item.newest(), item.asset().filename(),
							fraction -> Platform.runLater(() -> bulkProgress.setProgress((index + fraction) / todo.size())));
					LATEST_CACHE.put(cacheKey(instance, kind, item.asset().projectId()), new CachedLatest(item.newest(), System.currentTimeMillis()));
				}
				Platform.runLater(() -> {
					bulkProgress.setVisible(false);
					bulkProgress.setManaged(false);
					updateAll.setDisable(false);
					listRefresh[0].run();
				});
			}, Executors.newVirtualThreadPerTaskExecutor());
		});

		Button addButton = new Button(kind == InstalledAssetStore.Kind.MOD ? "+  Add mods" : "+  Add " + noun);
		addButton.getStyleClass().add("primary-button");
		addButton.setTooltip(new Tooltip("Browse and install from Modrinth"));
		addButton.setOnAction(e -> openSearch.run());

		Button importButton = new Button("Import file");
		importButton.setTooltip(new Tooltip("Add a " + (kind == InstalledAssetStore.Kind.MOD ? ".jar" : ".zip") + " you downloaded yourself"));
		importButton.setOnAction(e -> {
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
			listRefresh[0].run();
		});
		Button openFolderButton = new Button("Open folder");
		openFolderButton.getStyleClass().add("ghost-button");
		openFolderButton.setOnAction(e -> {
			try {
				Files.createDirectories(folder);
				openInFileManager(folder);
			} catch (Exception ex) {
				error(owner, "Couldn't open folder", ex.getMessage());
			}
		});
		filter.textProperty().addListener((obs, was, now) -> listRefresh[0].run());

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		// Next to the sidebar the page is narrower (2026-11): buttons keep their full label, the search field gives way.
		for (javafx.scene.control.Control control : new javafx.scene.control.Control[] {count, updateAll, openFolderButton, importButton, addButton}) {
			control.setMinWidth(Region.USE_PREF_SIZE);
		}
		filter.setMinWidth(130);
		HBox.setHgrow(filter, Priority.ALWAYS);
		filter.setMaxWidth(320);
		spacer.setMinWidth(0);
		openFolderButton.setText(null);
		openFolderButton.setGraphic(Icons.icon("folder", 16, javafx.scene.paint.Color.web("#E4E4E8")));
		openFolderButton.getStyleClass().setAll("button", "glass-button", "icon-only");
		openFolderButton.setTooltip(new Tooltip("Open folder"));
		count.getStyleClass().add("panel-title");
		HBox toolbar = new HBox(8, count, spacer, filter, openFolderButton);
		if (selection != null) {
			selection.modeButton.setMinWidth(Region.USE_PREF_SIZE);
			toolbar.getChildren().add(4, selection.modeButton);
		}
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.getStyleClass().add("pack-toolbar");

		root.getChildren().add(toolbar);
		// updates as one amber bar under the toolbar (design): what's new, and Update all
		Label updatesText = new Label();
		updatesText.getStyleClass().add("update-bar-text");
		HBox.setHgrow(updatesText, Priority.ALWAYS);
		updatesText.setMaxWidth(Double.MAX_VALUE);
		updateAll.getStyleClass().setAll("button", "update-bar-button");
		HBox updateBar = new HBox(12, Icons.icon("upload", 16, javafx.scene.paint.Color.web("#ffb347"), 2.2), updatesText, bulkProgress, updateAll);
		updateBar.setAlignment(Pos.CENTER_LEFT);
		updateBar.getStyleClass().add("update-bar");
		updateBar.visibleProperty().bind(updateAll.visibleProperty());
		updateBar.managedProperty().bind(updateAll.visibleProperty());
		Runnable previous = updates.onChange;
		updates.onChange = () -> {
			previous.run();
			int n = updates.pending.size();
			String names = updates.pending.values().stream().limit(3).map(p -> p.asset().title() != null ? p.asset().title() : p.asset().filename())
					.collect(java.util.stream.Collectors.joining(", "));
			updatesText.setText((n == 1 ? "1 update" : n + " updates") + (names.isEmpty() ? "" : "  ·  " + names));
			updateAll.setText("Update all");
		};
		root.getChildren().add(updateBar);
		if (kind == InstalledAssetStore.Kind.MOD) {
			root.getChildren().add(PlusAddonsCard.build(instance, () -> listRefresh[0].run()));
		}
		// Searching for something you don't have: no empty installed list taking the space above the Modrinth rows.
		installedList.getChildren().addListener((javafx.collections.ListChangeListener<Node>) change -> {
			boolean empty = installedList.getChildren().isEmpty();
			scroll.setVisible(!empty);
			scroll.setManaged(!empty);
		});
		root.getChildren().add(scroll);
		filter.setPromptText("Filter");
		// Dev (screenshot tour): -Dvelo.tourModSearch=map starts the Mods tab with that search typed in.
		String tourSearch = System.getProperty("velo.tourModSearch");
		if (tourSearch != null && System.getProperty("velo.launcherTour") != null && kind == InstalledAssetStore.Kind.MOD) {
			Platform.runLater(() -> filter.setText(tourSearch));
		}
		if (selection == null) {
			listRefresh[0].run();
			return new InstalledView(root, listRefresh[0]);
		}
		selection.setRefresh(listRefresh[0]);
		listRefresh[0].run();
		StackPane layered = new StackPane(root, selection.bar());
		StackPane.setAlignment(selection.bar(), Pos.BOTTOM_CENTER);
		StackPane.setMargin(selection.bar(), new Insets(0, 0, 14, 0));
		selection.installKeys(layered);
		return new InstalledView(layered, listRefresh[0]);
	}

	/** The small "BUILT IN" chip with the Velo logo; hovering says what Velo has instead. Null when nothing is built in. */
	static Label builtInBadge(String feature) {
		if (feature == null) {
			return null;
		}
		javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(UiImages.resource("/net/veloclient/launcher/images/logo.png", 12, 12));
		Label badge = new Label("BUILT IN", logo);
		badge.setGraphicTextGap(5);
		badge.getStyleClass().add("builtin-badge");
		Tooltip tip = new Tooltip("Velo already has " + feature + " - turn it on in game with Right Shift, no extra mod needed.");
		tip.setShowDelay(javafx.util.Duration.millis(150));
		tip.setWrapText(true);
		tip.setMaxWidth(260);
		Tooltip.install(badge, tip);
		return badge;
	}

	/**
	 * Under the installed list while you type in its filter: the best Modrinth matches you don't have yet, ready to add
	 * - so one search box finds both (2026-11).
	 */
	private static Node modrinthMatches(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, TextField filter, java.util.function.Consumer<String> openDetail) {
		Label heading = new Label("ADD FROM MODRINTH");
		heading.getStyleClass().add("friend-group-title");
		Label hint = new Label("");
		hint.getStyleClass().add("friend-group-count");
		HBox header = new HBox(10, heading, hint);
		header.setAlignment(Pos.CENTER_LEFT);
		header.getStyleClass().add("friend-group-header");
		VBox cards = new VBox();
		VBox box = new VBox(header, cards);
		box.getStyleClass().add("friend-group");
		box.setVisible(false);
		box.setManaged(false);
		javafx.animation.PauseTransition debounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(380));
		long[] generation = {0};
		debounce.setOnFinished(e -> {
			String text = filter.getText() == null ? "" : filter.getText().strip();
			long mine = ++generation[0];
			if (text.length() < 3) {
				box.setVisible(false);
				box.setManaged(false);
				return;
			}
			hint.setText("searching for \"" + text + "\"...");
			box.setVisible(true);
			box.setManaged(true);
			java.util.Set<String> have = InstalledAssetStore.loadAll(instance.id(), kind).stream()
					.map(InstalledAsset::projectId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
			java.util.concurrent.CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.search(text, kind.modrinthProjectType(), instance.mcVersion(), "relevance", 0, 10);
				} catch (Exception ex) {
					return null;
				}
			}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				if (mine != generation[0]) {
					return;
				}
				cards.getChildren().clear();
				if (result == null) {
					hint.setText("Modrinth didn't answer - try again in a moment");
					return;
				}
				for (ModrinthClient.SearchHit hit : result.hits()) {
					if (!have.contains(hit.projectId()) && cards.getChildren().size() < 4) {
						Node row = buildSearchResultRow(owner, overlayHost, instance, theme, kind, folder, hit, openDetail);
						if (!cards.getChildren().isEmpty()) {
							cards.getChildren().getLast().getStyleClass().add("friend-row-split");
						}
						cards.getChildren().add(row);
					}
				}
				hint.setText(cards.getChildren().isEmpty() ? "nothing new for \"" + text + "\" on " + instance.mcVersion()
						: "for Minecraft " + instance.mcVersion());
			}));
		});
		filter.textProperty().addListener((o, a, b) -> debounce.playFromStart());
		return box;
	}

	/** Re-applies a pixel scroll offset once the rebuilt rows have been laid out. */
	private static void restoreScroll(ScrollPane scroll, VBox content, double offsetPx) {
		Runnable apply = () -> {
			double range = content.getHeight() - scroll.getViewportBounds().getHeight();
			if (range > 0) {
				scroll.setVvalue(Math.min(1, offsetPx / range));
			}
		};
		apply.run();
		// Rows arrive over a few frames (addRowsInBatches): follow the growing list until the offset is reachable.
		javafx.beans.value.ChangeListener<Number> follow = new javafx.beans.value.ChangeListener<>() {
			@Override
			public void changed(javafx.beans.value.ObservableValue<? extends Number> obs, Number was, Number now) {
				apply.run();
				if (now.doubleValue() - scroll.getViewportBounds().getHeight() >= offsetPx) {
					content.heightProperty().removeListener(this);
				}
			}
		};
		content.heightProperty().addListener(follow);
		javafx.animation.PauseTransition giveUp = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
		giveUp.setOnFinished(e -> content.heightProperty().removeListener(follow));
		giveUp.play();
		Platform.runLater(apply);
	}

	// ---- Update lookups: cached per profile+project so refreshing rows doesn't re-query Modrinth ----

	private record CachedLatest(ModrinthClient.ProjectVersion newest, long fetchedAt) {
	}

	private static final java.util.Map<String, CachedLatest> LATEST_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long LATEST_TTL_MS = 10 * 60_000L;

	private static String cacheKey(Instance instance, InstalledAssetStore.Kind kind, String projectId) {
		return instance.id() + "|" + instance.mcVersion() + "|" + kind + "|" + projectId;
	}

	/** Which rows currently have a newer version, so "Update all" can act on them in one go. */
	private static final class UpdateTracker {
		record Pending(InstalledAsset asset, ModrinthClient.ProjectVersion newest) {
		}

		final java.util.Map<String, Pending> pending = new java.util.LinkedHashMap<>();
		Runnable onChange = () -> { };

		void clear() {
			pending.clear();
			onChange.run();
		}

		void add(InstalledAsset asset, ModrinthClient.ProjectVersion newest) {
			pending.put(asset.filename(), new Pending(asset, newest));
			onChange.run();
		}

		void remove(InstalledAsset asset) {
			if (pending.remove(asset.filename()) != null) {
				onChange.run();
			}
		}
	}

	private static void refreshInstalledList(Stage owner, StackPane overlayHost, VBox list, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, Set<String> identifyAttempted, Runnable refresh, java.util.function.Consumer<String> openDetail,
			String filterText, UpdateTracker updates, Label countLabel, String noun, PackSelection selection) {
		list.getChildren().clear();
		if (selection != null) {
			selection.beginRows();
		}
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
		countLabel.setText(files.size() + " " + (files.size() == 1 ? noun.substring(0, noun.length() - 1) : noun));
		if (files.isEmpty()) {
			Label empty = new Label("Nothing installed yet. Click \"Add\" to browse Modrinth, or \"Import file\" for a file you downloaded.");
			empty.getStyleClass().add("empty-state");
			empty.setWrapText(true);
			list.getChildren().add(empty);
			return;
		}
		var known = InstalledAssetStore.asMap(InstalledAssetStore.loadAll(instance.id(), kind));
		String needle = filterText == null ? "" : filterText.strip().toLowerCase(Locale.ROOT);
		List<Runnable> rows = new java.util.ArrayList<>();
		List<Path> unknownFiles = new java.util.ArrayList<>();
		for (Path file : files) {
			String fileName = file.getFileName().toString();
			boolean disabled = isDisabled(fileName);
			InstalledAsset asset = known.get(enabledName(fileName));
			if (asset == null) {
				unknownFiles.add(file);
			}
			if (!needle.isEmpty()) {
				String haystack = (asset != null ? asset.title() + " " : "") + fileName;
				if (!haystack.toLowerCase(Locale.ROOT).contains(needle)) {
					continue;
				}
			}
			if (asset != null) {
				rows.add(() -> {
					Node row = buildKnownRow(owner, overlayHost, instance, theme, kind, folder, file, disabled, asset, refresh, openDetail, updates);
					if (selection != null) {
						selection.decorate((HBox) row, file, true);
					}
					list.getChildren().add(row);
				});
			} else {
				rows.add(() -> {
					Node row = buildUnknownRow(instance, theme, kind, folder, file, disabled, identifyAttempted, refresh);
					if (selection != null) {
						selection.decorate((HBox) row, file, !isManagedFile(kind, enabledName(fileName)));
					}
					list.getChildren().add(row);
				});
			}
		}
		if (selection != null) {
			rows.add(selection::endRows);
		}
		addRowsInBatches(list, rows);
		autoIdentify(instance, kind, unknownFiles, identifyAttempted, refresh);
	}

	/**
	 * Adds the first screenful of rows now and the rest over the next frames: a profile with 100+ mods used to build (and style)
	 * every row in one go after each toggle/remove/update, freezing the launcher for a moment. A newer refresh cancels an older one.
	 */
	private static void addRowsInBatches(VBox list, List<Runnable> rows) {
		int generation = (int) list.getProperties().merge("velo.rowsGeneration", 1, (a, b) -> (Integer) a + 1);
		int first = Math.min(rows.size(), 24);
		for (int i = 0; i < first; i++) {
			rows.get(i).run();
		}
		if (first == rows.size()) {
			return;
		}
		int[] next = {first};
		new javafx.animation.AnimationTimer() {
			@Override
			public void handle(long now) {
				if (!Integer.valueOf(generation).equals(list.getProperties().get("velo.rowsGeneration"))) {
					stop();
					return;
				}
				long start = System.nanoTime();
				// ~8ms of row building per frame keeps scrolling and animations smooth.
				while (next[0] < rows.size() && System.nanoTime() - start < 8_000_000L) {
					rows.get(next[0]++).run();
				}
				if (next[0] >= rows.size()) {
					stop();
				}
			}
		}.start();
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

	/** Velo Client, Fabric API and the Addons jar are kept up to date by the launcher - never picked, toggled or deleted by hand. */
	private static boolean isManagedFile(InstalledAssetStore.Kind kind, String fileName) {
		return kind == InstalledAssetStore.Kind.MOD
				&& (fileName.startsWith("velo-client-") || fileName.startsWith("fabric-api-") || fileName.startsWith("velo-addons-"));
	}

	/** A quiet trash button that shows on row hover. */
	private static Button removeButton(Runnable action) {
		Button remove = new Button();
		remove.setGraphic(Icons.icon("trash", 16, javafx.scene.paint.Color.web("#A9A9B3")));
		remove.getStyleClass().addAll("icon-button", "icon-button-flat", "row-remove");
		remove.setTooltip(new Tooltip("Remove"));
		remove.setOnAction(e -> action.run());
		return remove;
	}

	private static CheckBox buildEnabledToggle(Instance instance, Path file, boolean disabled, Runnable refresh) {
		CheckBox toggle = new CheckBox();
		toggle.setSelected(!disabled);
		toggle.setTooltip(new Tooltip(disabled ? "Disabled - click to enable" : "Enabled - click to disable without removing it"));
		toggle.setOnAction(e -> setFileEnabled(instance, file, toggle.isSelected(), refresh));
		return toggle;
	}

	/** Renames {@code file} to add/strip the {@value #DISABLED_SUFFIX} suffix so it does/doesn't get loaded, then refreshes the list. */
	private static void setFileEnabled(Instance instance, Path file, boolean enable, Runnable refresh) {
		String fileName = file.getFileName().toString();
		String targetName = enable ? enabledName(fileName) : (isDisabled(fileName) ? fileName : fileName + DISABLED_SUFFIX);
		if (!targetName.equals(fileName)) {
			try {
				clearReadOnly(file);
				Files.move(file, file.resolveSibling(targetName), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				explainFileProblem(instance, file, enable ? "turn on" : "turn off", e);
			}
		}
		refresh.run();
	}

	/**
	 * Deletes a mod/pack file, saying why when it can't. It used to fail silently ("I can't delete any mod", bug report
	 * 2026-11-07): on Windows a file can't be deleted while the game has it open, and files copied over from other launchers
	 * are sometimes read-only. Read-only is cleared first; anything else is explained and logged.
	 */
	static boolean deleteFile(Instance instance, Path file) {
		try {
			clearReadOnly(file);
			Files.deleteIfExists(file);
			return true;
		} catch (IOException e) {
			explainFileProblem(instance, file, "remove", e);
			return false;
		}
	}

	private static void clearReadOnly(Path file) {
		try {
			var dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class);
			if (dos != null && dos.readAttributes().isReadOnly()) {
				dos.setReadOnly(false);
			}
		} catch (IOException | UnsupportedOperationException ignored) {
			// not Windows / no attribute - the delete itself says what's wrong
		}
	}

	private static void explainFileProblem(Instance instance, Path file, String what, IOException e) {
		net.veloclient.launcher.LauncherLog.warn("Couldn't " + what + " " + file, e);
		boolean running = instance != null && net.veloclient.launcher.instance.RunningInstanceManager.countRunning(instance.id()) > 0;
		String why = running ? "The game is still running with this profile and has the file open - close Minecraft and try again."
				: e instanceof java.nio.file.AccessDeniedException || String.valueOf(e.getMessage()).contains("used by another process")
						? "Another program has the file open (often a running game or an antivirus scan) - close Minecraft and try again."
						: "Windows refused it: " + e.getMessage();
		Platform.runLater(() -> {
			javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
			alert.setHeaderText("Couldn't " + what + " " + file.getFileName());
			alert.setContentText(why);
			DialogStyling.apply(alert);
			alert.show();
		});
	}

	private static Node buildKnownRow(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind, Path folder,
			Path file, boolean disabled, InstalledAsset asset, Runnable refresh, java.util.function.Consumer<String> openDetail, UpdateTracker updates) {
		HBox row = new HBox(12);
		row.getStyleClass().add("mod-row");
		row.setAlignment(Pos.CENTER_LEFT);
		row.setOpacity(disabled ? 0.5 : 1.0);

		Node icon = iconView(asset.iconUrl(), 40);
		icon.setCursor(javafx.scene.Cursor.HAND);
		icon.setOnMouseClicked(e -> openDetail.accept(asset.projectId()));
		row.getChildren().add(icon);

		VBox info = new VBox(2);
		Label title = new Label(asset.title() + (disabled ? "  (disabled)" : ""));
		title.setFont(Font.font("Geist", FontWeight.BOLD, 13));
		title.setTextFill(text(theme));
		Node meta;
		{
			HBox chipRow = new HBox(VersionDropdown.chip(instance, kind, asset, version -> {
				// Picking a version for a disabled one means you want to use it: it comes back enabled.
				if (disabled) {
					try {
						Files.move(file, file.resolveSibling(enabledName(file.getFileName().toString())), StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException ignored) {
						// Installed next to it then; the Doctor flags the duplicate.
					}
				}
				installPicked(owner, instance, kind, folder, version, asset.filename(), refresh);
			}));
			chipRow.setAlignment(Pos.CENTER_LEFT);
			chipRow.setSpacing(8);
			chipRow.getChildren().add(authorLabel(file, asset.projectId()));
			meta = chipRow;
		}
		info.getChildren().addAll(title, meta);
		Label builtin = builtinHint(null, asset.title());
		if (builtin != null) {
			builtin.setMinWidth(Region.USE_PREF_SIZE);
			builtin.setMaxWidth(Region.USE_PREF_SIZE);
			info.getChildren().add(builtin);
		}
		if (kind == InstalledAssetStore.Kind.MOD) {
			for (String rule : net.veloclient.launcher.data.ServerProfiles.badgesFor(file, asset)) {
				Label badge = new Label(rule);
				badge.getStyleClass().add("server-rule-badge");
				info.getChildren().add(badge);
			}
		}
		HBox.setHgrow(info, Priority.ALWAYS);
		row.getChildren().add(info);

		ProgressBar progress = new ProgressBar(0);
		progress.setPrefWidth(90);
		progress.setVisible(false);
		progress.setManaged(false);

		Button updateBadge = new Button();
		updateBadge.getStyleClass().add("row-update-button");
		updateBadge.setVisible(false);
		updateBadge.setManaged(false);
		if (!disabled) {
			checkForUpdate(owner, overlayHost, instance, theme, kind, folder, asset, updateBadge, progress, refresh, updates);
		}

		Button remove = removeButton(() -> {
			if (deleteFile(instance, file)) {
				InstalledAssetStore.forget(instance.id(), kind, enabledName(file.getFileName().toString()));
			}
			refresh.run();
		});
		row.getChildren().addAll(progress, updateBadge, remove, buildEnabledToggle(instance, file, disabled, refresh));
		return row;
	}

	private static Node buildUnknownRow(Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind, Path folder,
			Path file, boolean disabled, Set<String> identifyAttempted, Runnable refresh) {
		String fileName = enabledName(file.getFileName().toString());
		boolean isVeloClient = fileName.startsWith("velo-client-");
		boolean isFabricApi = fileName.startsWith("fabric-api-");
		boolean isAddons = fileName.startsWith("velo-addons-");
		boolean protectedFile = kind == InstalledAssetStore.Kind.MOD && (isVeloClient || isFabricApi || isAddons);

		HBox row = new HBox(12);
		row.getStyleClass().add("mod-row");
		row.setAlignment(Pos.CENTER_LEFT);
		row.setOpacity(disabled ? 0.5 : 1.0);

		row.getChildren().add(iconView(null, 40));

		VBox info = new VBox(2);
		Label name = new Label(fileName + (isVeloClient ? "  (Velo Client)" : isFabricApi ? "  (Fabric API - required)" : isAddons ? "  (Addons - managed in the card above)" : "")
				+ (disabled ? "  (disabled)" : ""));
		name.setTextFill(text(theme));
		Label size = new Label(protectedFile ? sizeOf(file) : sizeOf(file) + "  ·  not on Modrinth");
		size.getStyleClass().add("version-tag");
		size.setTextFill(text(theme));
		HBox sizeRow = new HBox(8, size);
		sizeRow.setAlignment(Pos.CENTER_LEFT);
		if (kind == InstalledAssetStore.Kind.MOD) {
			sizeRow.getChildren().add(authorLabel(file, null));
		}
		info.getChildren().addAll(name, sizeRow);
		HBox.setHgrow(info, Priority.ALWAYS);
		row.getChildren().add(info);

		if (!protectedFile) {
			row.getChildren().addAll(removeButton(() -> {
				deleteFile(instance, file);
				refresh.run();
			}), buildEnabledToggle(instance, file, disabled, refresh));
		} else {
			Label managed = new Label("Managed");
			managed.getStyleClass().addAll("badge", "badge-grey");
			row.getChildren().add(managed);
		}
		return row;
	}

	/**
	 * Looks every unrecognized file up on Modrinth by sha1 automatically (no button), in two requests for all of them: one
	 * {@code POST /version_files} and one {@code GET /projects}. It used to be two requests per file, all at once - after an
	 * import from Lunar (30-100 mods) Modrinth throttled the burst, the failures were silent and only tried once, so almost
	 * every mod stayed "not on Modrinth" although Modrinth knew it by checksum. A failed lookup may be tried again on the next
	 * refresh; files Modrinth doesn't know stay plain rows.
	 */
	private static void autoIdentify(Instance instance, InstalledAssetStore.Kind kind, List<Path> files, Set<String> identifyAttempted, Runnable refresh) {
		List<Path> todo = new java.util.ArrayList<>();
		for (Path file : files) {
			String fileName = file.getFileName().toString();
			String enabledFileName = enabledName(fileName);
			if (enabledFileName.startsWith("velo-client-") || enabledFileName.startsWith("fabric-api-") || enabledFileName.startsWith("velo-addons-")
					|| !identifyAttempted.add(fileName)) {
				continue;
			}
			todo.add(file);
		}
		if (todo.isEmpty()) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			java.util.Map<String, Path> byHash = new java.util.LinkedHashMap<>();
			for (Path file : todo) {
				try {
					byHash.put(Downloader.sha1Of(file), file);
				} catch (IOException ignored) {
					// unreadable: stays an unknown row
				}
			}
			boolean recorded = false;
			try {
				java.util.Map<String, ModrinthClient.ProjectVersion> versions = ModrinthClient.versionsByFileSha1(byHash.keySet());
				java.util.Set<String> projectIds = new java.util.LinkedHashSet<>();
				versions.values().forEach(v -> projectIds.add(v.projectId()));
				java.util.Map<String, ModrinthClient.Project> projects = new java.util.HashMap<>();
				for (ModrinthClient.Project project : ModrinthClient.projects(projectIds)) {
					projects.put(project.id(), project);
				}
				for (var entry : versions.entrySet()) {
					ModrinthClient.ProjectVersion v = entry.getValue();
					ModrinthClient.Project project = projects.get(v.projectId());
					Path file = byHash.get(entry.getKey());
					if (project == null || file == null) {
						continue;
					}
					InstalledAssetStore.record(instance.id(), kind, new InstalledAsset(project.id(), v.id(), enabledName(file.getFileName().toString()),
							project.title(), project.description(), project.iconUrl(), v.versionNumber(), kind.modrinthProjectType()));
					recorded = true;
				}
			} catch (IOException | RuntimeException e) {
				// Offline or throttled: let the next refresh try these again.
				todo.forEach(file -> identifyAttempted.remove(file.getFileName().toString()));
				return;
			}
			if (recorded) {
				Platform.runLater(refresh);
			}
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	private static void checkForUpdate(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, InstalledAsset asset, Button updateBadge, ProgressBar progress, Runnable refresh, UpdateTracker updates) {
		String key = cacheKey(instance, kind, asset.projectId());
		CachedLatest cached = LATEST_CACHE.get(key);
		java.util.function.Consumer<ModrinthClient.ProjectVersion> apply = newest -> {
			if (newest == null || newest.id().equals(asset.versionId())) {
				return;
			}
			updates.add(asset, newest);
			updateBadge.setText("Update to " + newest.versionNumber());
			updateBadge.setVisible(true);
			updateBadge.setManaged(true);
			updateBadge.setOnAction(e -> {
				updateBadge.setVisible(false);
				updateBadge.setManaged(false);
				updates.remove(asset);
				showVersionPicker(overlayHost, theme, instance, asset.title(), List.of(newest), asset.versionId(), kind.modrinthProjectType(), plan ->
						installWithDependencies(owner, instance, kind, folder, plan, asset.filename(), progress, refresh));
			});
		};
		if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < LATEST_TTL_MS) {
			apply.accept(cached.newest());
			return;
		}
		CompletableFuture.supplyAsync(() -> {
			try {
				return ModrinthClient.versions(asset.projectId(), instance.mcVersion(), kind.modrinthProjectType());
			} catch (IOException e) {
				return List.<ModrinthClient.ProjectVersion>of();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(versions -> {
			ModrinthClient.ProjectVersion newest = versions.stream()
					.max(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished)).orElse(null);
			LATEST_CACHE.put(key, new CachedLatest(newest, System.currentTimeMillis()));
			Platform.runLater(() -> apply.accept(newest));
		});
	}

	// ---- Full-page Modrinth search view ----

	private static Node buildSearchView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind, Path folder, Runnable onBack,
			java.util.function.Consumer<String> openDetail) {
		VBox root = new VBox(14);
		VBox.setVgrow(root, Priority.ALWAYS);

		HBox topRow = new HBox(10);
		topRow.setAlignment(Pos.CENTER_LEFT);
		Button back = BackButton.create("installed (" + userFileCount(folder) + ")", null);
		back.setOnAction(e -> onBack.run());
		Label heading = new Label("Browse Modrinth " + kind.modrinthProjectType() + "s for Minecraft " + instance.mcVersion());
		heading.setFont(Font.font("Geist", FontWeight.BOLD, 15));
		heading.setTextFill(text(theme));
		topRow.getChildren().addAll(back, heading);
		root.getChildren().add(topRow);

		HBox searchRow = new HBox(8);
		TextField query = new TextField();
		query.setPromptText("Search Modrinth (e.g. Sodium, minimap, shaders)...");
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
		// Every new search gets a number; answers of older ones are dropped. Without it the first "relevance" load (or a
		// search typed a moment earlier) could answer last and replace or mix into the results ("search bugs out").
		int[] generation = {0};

		Runnable[] loadPage = new Runnable[1];
		loadPage[0] = () -> {
			boolean firstPage = offset[0] == 0;
			int requestGeneration = generation[0];
			int requestOffset = offset[0];
			String text = query.getText();
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
					return ModrinthClient.search(text, kind.modrinthProjectType(), instance.mcVersion(), sortIndex, requestOffset, pageSize);
				} catch (IOException e) {
					return e;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				if (requestGeneration != generation[0] || requestOffset != offset[0]) {
					return;   // a newer search (or page) was asked for meanwhile
				}
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
			generation[0]++;
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

	/** "Built into Velo: World Map" for mods whose feature Velo already has. */
	/** The small "BUILT IN" chip next to a mod Velo already covers (2026-11: a quiet badge, the details on hover). */
	private static Label builtinHint(String slug, String title) {
		return builtInBadge(net.veloclient.launcher.data.VeloBuiltins.featureFor(slug, title));
	}

	/** Install button behaviour for a Modrinth hit: load versions, pick one (with dependencies), install, then flip the label. */
	private static void wireInstall(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, ModrinthClient.SearchHit hit, Button install, ProgressBar progress, String doneText) {
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
							install.setText(doneText);
							install.setDisable(true);
						}));
			}));
		});
	}

	/** A Modrinth hit as a list row (2026-11), matching the installed rows: icon, name + BUILT IN, one-line description, Add. */
	private static Node buildSearchResultRow(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, InstalledAssetStore.Kind kind,
			Path folder, ModrinthClient.SearchHit hit, java.util.function.Consumer<String> openDetail) {
		Node icon = iconView(hit.iconUrl(), 38);
		icon.setCursor(javafx.scene.Cursor.HAND);
		icon.setOnMouseClicked(e -> openDetail.accept(hit.projectId()));
		Label title = new Label(hit.title());
		title.getStyleClass().add("t-strong");
		title.setOnMouseClicked(e -> openDetail.accept(hit.projectId()));
		title.setCursor(javafx.scene.Cursor.HAND);
		title.setMinWidth(0);
		title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
		HBox titleRow = new HBox(8, title);
		titleRow.setAlignment(Pos.CENTER_LEFT);
		titleRow.setMinWidth(0);
		Label builtin = builtinHint(hit.slug(), hit.title());
		if (builtin != null) {
			builtin.setMinWidth(Region.USE_PREF_SIZE);
			titleRow.getChildren().add(builtin);
		}
		Label by = new Label(hit.author() == null || hit.author().isBlank() ? "" : "by " + hit.author());
		by.getStyleClass().add("mod-author");
		by.setMinWidth(0);
		by.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
		Label downloads = new Label(formatCount(hit.downloads()));
		downloads.getStyleClass().add("mod-downloads");
		downloads.setMinWidth(Region.USE_PREF_SIZE);
		Label dot = new Label("·");
		dot.getStyleClass().add("mod-downloads");
		HBox metaRow = new HBox(6, by, dot, downloads);
		metaRow.setAlignment(Pos.CENTER_LEFT);
		metaRow.setMinWidth(0);
		Tooltip.install(text0(titleRow), new Tooltip(hit.description()));
		VBox text = new VBox(3, titleRow, metaRow);
		text.setMinWidth(0);
		HBox.setHgrow(text, Priority.ALWAYS);
		ProgressBar progress = new ProgressBar(0);
		progress.setPrefWidth(80);
		progress.setVisible(false);
		progress.setManaged(false);
		Button add = new Button("Add");
		add.getStyleClass().addAll("primary-button", "button-sm");
		add.setMinWidth(76);
		wireInstall(owner, overlayHost, instance, theme, kind, folder, hit, add, progress, "Added");
		HBox row = new HBox(12, icon, text, progress, add);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("friend-row");
		return row;
	}

	private static Node text0(Node node) {
		return node;
	}

	private static final java.util.Map<String, String> AUTHORS = new java.util.concurrent.ConcurrentHashMap<>();

	/** "by CaffeineMC" from the mod jar's fabric.mod.json (read once in the background, cached per file and date). */
	private static Label authorLabel(Path file, String projectId) {
		Label label = new Label();
		label.getStyleClass().add("mod-author");
		label.setMinWidth(0);
		label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
		String key;
		try {
			key = file + "@" + Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			return label;
		}
		String cached = AUTHORS.get(key);
		if (cached != null) {
			label.setText(cached.isEmpty() ? "" : "by " + cached);
			return label;
		}
		Thread.ofVirtual().start(() -> {
			String authors = "";
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
				java.util.zip.ZipEntry entry = zip.getEntry("fabric.mod.json");
				if (entry != null) {
					try (var in = zip.getInputStream(entry)) {
						var json = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
						if (json.has("authors") && json.get("authors").isJsonArray()) {
							java.util.List<String> names = new java.util.ArrayList<>();
							for (var element : json.getAsJsonArray("authors")) {
								if (element.isJsonPrimitive()) {
									names.add(element.getAsString());
								} else if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
									names.add(element.getAsJsonObject().get("name").getAsString());
								}
							}
							authors = names.size() <= 2 ? String.join(", ", names) : names.get(0) + ", " + names.get(1) + " +" + (names.size() - 2);
						}
					}
				}
			} catch (Exception ignored) {
				// not a readable jar: no author line
			}
			if (authors.isEmpty() && projectId != null) {
				// packs, shaders and mods without authors in their file: the Modrinth project's owner
				authors = ModrinthClient.owner(projectId);
			}
			AUTHORS.put(key, authors);
			String shown = authors;
			Platform.runLater(() -> label.setText(shown.isEmpty() ? "" : "by " + shown));
		});
		return label;
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
		title.setFont(Font.font("Geist", FontWeight.BOLD, 13));
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
			wireInstall(owner, overlayHost, instance, theme, kind, folder, hit, install, progress, "Installed");
		}

		card.getChildren().addAll(title, description, downloads);
		Label builtin = builtinHint(hit.slug(), hit.title());
		if (builtin != null) {
			// on the icon's lower edge: a line of its own made the fixed-size card overflow
			builtin.setMinWidth(Region.USE_PREF_SIZE);
			builtin.setTranslateY(9);
			StackPane iconWithBadge = new StackPane(icon, builtin);
			StackPane.setAlignment(builtin, Pos.BOTTOM_CENTER);
			iconWithBadge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
			VBox.setMargin(iconWithBadge, new Insets(0, 0, 8, 0));
			card.getChildren().set(card.getChildren().indexOf(icon), iconWithBadge);
		}
		card.getChildren().addAll(spacer, progress, install);
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
		showVersionPicker(overlayHost, theme, instance, projectTitle, versions, null, null, onConfirm);
	}

	/** {@code installedVersionId} (nullable): an update - "What's new" then lists every version since that one. */
	static void showVersionPicker(StackPane overlayHost, LauncherTheme theme, Instance instance, String projectTitle,
			List<ModrinthClient.ProjectVersion> versions, String installedVersionId, String projectType,
			java.util.function.Consumer<InstallPlan> onConfirm) {
		VBox card = new VBox(12);
		card.getStyleClass().addAll("glass-panel", "modal-card");
		card.setMaxWidth(420);
		card.setPrefWidth(420);
		card.setMaxHeight(560);

		Label heading = new Label("Install " + projectTitle);
		heading.setFont(Font.font("Geist", FontWeight.BOLD, 16));
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
		// Dev build first: the author's notes for what this version (or update) changes.
		VBox changelog = new VBox();
		Runnable showChangelog = () -> {
			ModrinthClient.ProjectVersion picked = combo.getValue();
			if (picked != null) {
				changelog.getChildren().setAll(ChangelogView.between(picked.projectId(), instance.mcVersion(),
						projectType != null ? projectType : "mod", installedVersionId, picked));
			}
		};
		combo.setOnAction(e -> {
			dependencyChecklist.refresh(instance, combo.getValue());
			showChangelog.run();
		});
		dependencyChecklist.refresh(instance, combo.getValue());
		showChangelog.run();

		VBox scrollableContent = new VBox(10, combo, dependencyChecklist.node, changelog);
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

	/**
	 * A version picked in a row's dropdown: switches to it (up or down) and adds the required dependencies it needs
	 * that aren't installed yet - no second dialog.
	 */
	private static void installPicked(Stage owner, Instance instance, InstalledAssetStore.Kind kind, Path folder, ModrinthClient.ProjectVersion version,
			String oldFilename, Runnable refresh) {
		Toasts.show(owner.getScene().getRoot(), "download", "#7dbbff", "Switching to " + version.versionNumber() + "...");
		CompletableFuture.runAsync(() -> {
			List<ModrinthClient.ProjectVersion> deps = kind == InstalledAssetStore.Kind.MOD
					? DependencyChecklist.resolveRows(instance, version.requiredDependencies()).stream()
							.filter(r -> !r.alreadyInstalled() && r.resolvedVersion() != null).map(DependencyChecklist.Row::resolvedVersion).toList()
					: List.of();
			downloadAndRecord(instance, kind, folder, version, oldFilename, fraction -> { });
			Path modsDir = InstancePaths.modsDir(instance.id());
			for (ModrinthClient.ProjectVersion dep : deps) {
				downloadAndRecord(instance, InstalledAssetStore.Kind.MOD, modsDir, dep, null, fraction -> { });
			}
			Platform.runLater(() -> {
				Toasts.show(owner.getScene().getRoot(), "check", "#7dd3a0", "Now on " + version.versionNumber()
						+ (deps.isEmpty() ? "" : " (+" + deps.size() + (deps.size() == 1 ? " dependency)" : " dependencies)")));
				refresh.run();
			});
		}, Executors.newVirtualThreadPerTaskExecutor());
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

	static void downloadAndRecord(Instance instance, InstalledAssetStore.Kind kind, Path folder, ModrinthClient.ProjectVersion version,
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
				// Kept (not deleted) so a bad update can be undone by moving it back.
				Path oldFile = folder.resolve(oldFilenameToRemove);
				if (Files.exists(oldFile)) {
					Path backups = InstancePaths.dir(instance.id()).resolve("backups").resolve(folder.getFileName().toString());
					Files.createDirectories(backups);
					Files.move(oldFile, backups.resolve(oldFilenameToRemove), StandardCopyOption.REPLACE_EXISTING);
				}
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
						header.setFont(Font.font("Geist", FontWeight.BOLD, 12));
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

	private static final java.util.Map<Double, Image> FALLBACKS = new java.util.concurrent.ConcurrentHashMap<>();

	static Node iconView(String url, double size) {
		StackPane holder = new StackPane();
		holder.setPrefSize(size, size);
		holder.setMinSize(size, size);
		holder.getStyleClass().add("instance-icon-custom");
		ImageView view = new ImageView();
		view.setFitWidth(size);
		view.setFitHeight(size);
		view.setPreserveRatio(true);
		// Decoded once per size: every row used to decode and downscale the same PNG again on the UI thread.
		Image fallback = FALLBACKS.computeIfAbsent(size, s -> new Image(InstanceDetailView.class.getResourceAsStream(FALLBACK_ICON), s, s, true, true));
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

	/** What search results show while they load: skeleton cards in the shape of the result cards. */
	static Node loadingLabel(LauncherTheme theme) {
		return Skeleton.grid(8, 190, 230);
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
