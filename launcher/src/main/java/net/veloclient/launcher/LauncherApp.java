package net.veloclient.launcher;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.veloclient.launcher.auth.AuthSession;
import net.veloclient.launcher.auth.MicrosoftAuth;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.SkinFetcher;
import net.veloclient.launcher.data.QuickLaunchStore;
import net.veloclient.launcher.data.SavedServer;
import net.veloclient.launcher.data.SavedServerStore;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.instance.BuiltinIcons;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceIcon;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.instance.ProfileArchive;
import net.veloclient.launcher.instance.RunningInstanceManager;
import net.veloclient.launcher.launch.FabricApiInstaller;
import net.veloclient.launcher.launch.GameJars;
import net.veloclient.launcher.launch.GameLauncher;
import net.veloclient.launcher.launch.GameVersion;
import net.veloclient.launcher.launch.LaunchProgressListener;
import net.veloclient.launcher.net.ServerPinger;
import net.veloclient.launcher.theme.LauncherTheme;
import net.veloclient.launcher.theme.ThemeStore;
import net.veloclient.launcher.ui.AccountProfileView;
import net.veloclient.launcher.ui.CosmeticsView;
import net.veloclient.launcher.ui.DialogStyling;
import net.veloclient.launcher.ui.ErrorDialog;
import net.veloclient.launcher.ui.IconColorExtractor;
import net.veloclient.launcher.ui.InstanceDetailView;
import net.veloclient.launcher.ui.InstanceEditDialog;
import net.veloclient.launcher.ui.InstanceSettingsDialog;
import net.veloclient.launcher.ui.ParticleBackground;
import net.veloclient.launcher.ui.PlayerHeadView;
import net.veloclient.launcher.ui.PlayerSkin3DView;
import net.veloclient.launcher.ui.RunningInstanceView;
import net.veloclient.launcher.ui.ServerEditDialog;
import net.veloclient.launcher.ui.ServerFaviconCache;
import net.veloclient.launcher.ui.SignInDialog;
import net.veloclient.launcher.ui.ThemeEditorView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Standalone launcher shell (design spec section 4): a Minecraft-style
 * animated title screen for Home, plus Profiles/Mods/Cosmetics/Server
 * Profiles/Settings/Theme Editor sections behind a sidebar. Reads/writes the
 * exact same {@code ~/.velo-client/} files as the in-game mod (manifest.json,
 * profiles/*.json, config/theme.json), and signs in for real via the
 * Microsoft device-code flow ({@code net.veloclient.launcher.auth}) - see
 * README.md for how the client id is obtained.
 *
 * <p>"Profiles" here means named mod+version loadouts (an {@link Instance}:
 * icon, name, one of the 3 supported Minecraft versions, its own isolated
 * mods folder) - not the in-game mod's {@code ServerProfile} safe-mode
 * presets, which live under the separate "Server Profiles" section
 * ({@link #showServerProfiles()}) and are unrelated.
 *
 * <p>This app *is* the launcher: pressing Play on a profile downloads
 * whatever that Minecraft version + Fabric need (via
 * {@code net.veloclient.launcher.launch}) and starts the JVM directly - it
 * never shells out to or installs into the official Minecraft Launcher.
 */
public final class LauncherApp extends Application {

	/**
	 * Public-client Azure AD application id (design spec section 4's account
	 * sign-in). This value is not a secret - it identifies the app, not the
	 * user - so it's fine to compile it in. Registering your own is free; see
	 * README.md.
	 */
	private static final String MICROSOFT_CLIENT_ID = "6cc50134-1c8d-43dc-9265-e107d0540248";
	/** Opacity of the home screen's big background profile icon - low enough to read as a background element (the {@link #homeLogoGlow} wash behind it is what carries its actual color, not this), not a bold, flatly opaque sticker pasted over the scene. */
	private static final double HOME_LOGO_OPACITY = 0.5;

	private LauncherTheme theme;
	private BorderPane root;
	private StackPane content;
	private Stage stage;
	private ParticleBackground background;
	private MinecraftSession session;
	private Label accountLabel;
	private Button accountButton;
	private Button navHome, navServers, navCosmetics, navStore, navSettings;
	private VBox runningSection;
	private VBox quickLaunchSection;
	/** Which profile the home screen's carousel is showing - an index into {@link #orderedProfilesForHome()}, clamped back to range whenever the profile list changes (e.g. after a delete). */
	private int homeProfileIndex = 0;
	private ImageView homeBackgroundLogo;
	/** Soft radial color wash sitting directly behind {@link #homeBackgroundLogo}, tinted with the same extracted accent color as the border glow - many profile/server icons are mostly white/line-art on a transparent background, which reads as flat gray once alpha-blended over the dark particle backdrop with nothing else behind it. */
	private Circle homeLogoGlow;
	private StackPane homePlayerHolder;
	private StackPane homeBorderHost;
	private Label homeProfileName;
	private Label homeSubtitle;
	private StackPane homeActionsRowHolder;
	private Button homePlayButton;
	/** Manage-mods shortcut, beside {@link #homePlayButton} at the same height rather than shrunk down into {@link #homeActionsRowHolder}'s small icon row - "manage mods" is a common enough action to deserve a bigger, more accessible target than rename/duplicate/export/RAM/delete. */
	private Button homeGearButton;
	private ProgressBar homeLaunchProgress;
	private Label homeLaunchStatus;
	/**
	 * Row nodes for the sidebar's "Running" section, keyed by {@code runId}
	 * and reused across refreshes rather than rebuilt from scratch every
	 * time - {@code refreshRunningSidebar()} used to always clear and
	 * recreate every row on any change to the underlying list, but that list
	 * mutates from a background thread the instant any tracked process exits
	 * (see RunningInstanceManager.register), completely independent of
	 * whatever the user happens to be doing right then. A rebuild landing
	 * between a click's press and release replaced the very row being
	 * clicked with a brand-new Node, silently swallowing the click - a real,
	 * confirmed intermittent bug ("sometimes doesn't work"), not
	 * hypothetical, and not actually platform-specific despite how it
	 * presented - it's a timing race that can hit either OS. Keeping the
	 * same Node alive for any run that's still present (only genuinely
	 * added/removed rows get new Nodes) removes the window entirely for the
	 * common case of clicking on one instance while an unrelated one exits.
	 */
	private final java.util.Map<String, Node> runningRowsByRunId = new java.util.LinkedHashMap<>();

	public static void main(String[] args) {
		LauncherLog.install();
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		this.stage = stage;
		VeloPaths.ensureDirectories();
		theme = ThemeStore.load();
		// Registers the "Audiowide" family for Font.font()/CSS -fx-font-family
		// lookups for the rest of this process - loadFont's own return value
		// (a Font at one specific size) isn't otherwise used, the family just
		// needs loading once. Same font as the in-game title screen.
		Font.loadFont(getClass().getResourceAsStream("/net/veloclient/launcher/fonts/Audiowide-Regular.ttf"), 12);

		root = new BorderPane();
		root.getStyleClass().add("root");
		content = new StackPane();
		content.setPadding(new Insets(28));

		root.setLeft(buildSidebar());
		root.setCenter(content);
		showHome();

		Scene scene = new Scene(root, 1100, 680);
		scene.getStylesheets().add(getClass().getResource("/net/veloclient/launcher/launcher.css").toExternalForm());
		applyTheme();

		stage.getIcons().add(new Image(getClass().getResourceAsStream("/net/veloclient/launcher/images/logo.png")));
		stage.setTitle("Velo Client Launcher");
		stage.setScene(scene);
		stage.setMinWidth(920);
		stage.setMinHeight(600);
		fixWindowsMaximizeRestoreBug(stage);
		stage.show();

		attemptSilentSignIn();
	}

	/**
	 * JavaFX's own stage bounds sometimes fall out of sync with the real OS
	 * window after clicking "restore down" from maximized - the title bar/
	 * border shrink back but the scene content stays sized as if still
	 * maximized, since the {@code Stage}'s width/height properties never got
	 * the memo. Force them back explicitly once un-maximized, rather than
	 * trusting the native restore to have already done it.
	 *
	 * <p>The bounds to restore to are tracked <em>continuously</em> via
	 * listeners on x/y/width/height themselves (guarded by {@code
	 * !isMaximized()}), not captured once inside the {@code
	 * maximizedProperty} listener at the moment it flips to {@code true} - an
	 * earlier version did the latter and had a real, confirmed race: by the
	 * time that listener callback runs, {@code stage.getWidth()}/{@code
	 * getHeight()} can already reflect the *new* (maximized) size rather than
	 * the windowed size from just before, since JavaFX doesn't guarantee
	 * {@code maximizedProperty} fires strictly before the size properties
	 * update in the same pulse. That bug had two visible symptoms other than
	 * "restore doesn't shrink back": since the bad captured size gets reused
	 * as the restore target on every subsequent maximize/restore cycle, and
	 * each cycle's own bad capture could itself be based on an already-bad
	 * prior restore, repeated cycles could ratchet the "restored" size
	 * larger over time - reported as "the window keeps getting bigger and
	 * bigger", not just a one-off. Continuous tracking has no such window:
	 * whatever the size was at the instant maximizing began is always
	 * already known, nothing needs to be caught mid-transition.
	 */
	private void fixWindowsMaximizeRestoreBug(Stage stage) {
		double[] lastWindowedBounds = {stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()};
		javafx.beans.value.ChangeListener<Number> trackWhileWindowed = (obs, oldVal, newVal) -> {
			if (!stage.isMaximized()) {
				lastWindowedBounds[0] = stage.getX();
				lastWindowedBounds[1] = stage.getY();
				lastWindowedBounds[2] = stage.getWidth();
				lastWindowedBounds[3] = stage.getHeight();
			}
		};
		stage.xProperty().addListener(trackWhileWindowed);
		stage.yProperty().addListener(trackWhileWindowed);
		stage.widthProperty().addListener(trackWhileWindowed);
		stage.heightProperty().addListener(trackWhileWindowed);

		stage.maximizedProperty().addListener((obs, wasMaximized, isMaximized) -> {
			if (!isMaximized && lastWindowedBounds[2] > 0 && lastWindowedBounds[3] > 0) {
				Platform.runLater(() -> {
					stage.setX(lastWindowedBounds[0]);
					stage.setY(lastWindowedBounds[1]);
					stage.setWidth(lastWindowedBounds[2]);
					stage.setHeight(lastWindowedBounds[3]);
				});
			}
		});
	}

	private void applyTheme() {
		root.setStyle(String.format(java.util.Locale.ROOT,
				"-velo-background: %s; -velo-surface: %s; -velo-accent-start: %s; -velo-accent-end: %s; "
						+ "-velo-text: %s; -velo-hover: %s; -velo-hairline: %s; -fx-background-color: -velo-background;",
				cssColor(theme.background()), cssColor(theme.surface()), cssColor(theme.accentStart()),
				cssColor(theme.accentEnd()), cssColor(theme.text()), hoverColor(), hairlineColor()));
	}

	private static String cssColor(int argb) {
		int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)", r, g, b, a / 255.0);
	}

	private String hoverColor() {
		int s = theme.surface();
		int r = Math.min(255, ((s >> 16) & 0xFF) + 18);
		int g = Math.min(255, ((s >> 8) & 0xFF) + 18);
		int b = Math.min(255, (s & 0xFF) + 18);
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,0.9)", r, g, b);
	}

	private String hairlineColor() {
		return "rgba(255,255,255,0.08)";
	}

	// ---- Sidebar ----

	private VBox buildSidebar() {
		VBox sidebar = new VBox(4);
		sidebar.getStyleClass().add("sidebar");
		sidebar.setPadding(new Insets(22, 12, 12, 12));
		sidebar.setPrefWidth(190);

		Label title = new Label("VELO CLIENT");
		title.getStyleClass().add("sidebar-title");
		title.setTextFill(accentColor());
		VBox.setMargin(title, new Insets(0, 0, 16, 6));

		navHome = navIconButton("home", "Home", this::showHome);
		navServers = navIconButton("server", "Servers", this::showServers);
		navCosmetics = navIconButton("cosmetics", "Cosmetics", this::showCosmetics);
		navStore = navIconButton("store", "Store", this::showStore);
		navSettings = navIconButton("settings", "Settings", this::showSettings);

		sidebar.getChildren().addAll(title, navHome, navServers, navCosmetics, navStore, navSettings);

		// "Running" (live instances) and "Quick Launch" (recent one-click
		// shortcuts) - deliberately separated from the fixed nav above by
		// their own scrollable region, since either can grow past the
		// window's height once several profiles/servers are in play.
		runningSection = new VBox(4);
		quickLaunchSection = new VBox(4);
		VBox extras = new VBox(12, runningSection, quickLaunchSection);
		extras.setPadding(new Insets(14, 0, 0, 0));
		ScrollPane extrasScroll = new ScrollPane(extras);
		extrasScroll.setFitToWidth(true);
		extrasScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		extrasScroll.getStyleClass().add("sidebar-scroll");
		VBox.setVgrow(extrasScroll, Priority.ALWAYS);
		sidebar.getChildren().add(extrasScroll);

		// The Microsoft account switcher, pinned to the very bottom of the
		// sidebar (Discord/Spotify-style) - previously a floating badge in
		// the bottom-left corner of the Home screen itself, moved here so
		// Home has room for the new profile carousel instead.
		accountLabel = new Label();
		accountButton = new Button();
		accountButton.getStyleClass().add("account-badge");
		accountButton.setMaxWidth(Double.MAX_VALUE);
		accountButton.setGraphic(buildAccountBadgeContent());
		accountButton.setOnAction(e -> {
			if (session == null) {
				beginSignIn();
			} else {
				showAccountProfile();
			}
		});
		sidebar.getChildren().add(accountButton);
		refreshAccountBadge();

		// The app version tag sits below the account switcher (not above -
		// it's the least important thing at the bottom of the sidebar, not
		// a header for it).
		Label version = new Label("v" + AppVersion.VERSION);
		version.getStyleClass().add("version-tag");
		version.setTextFill(textColor());
		version.setMaxWidth(Double.MAX_VALUE);
		version.setAlignment(Pos.CENTER);
		VBox.setMargin(version, new Insets(6, 0, 0, 0));
		sidebar.getChildren().add(version);

		RunningInstanceManager.running().addListener((javafx.collections.ListChangeListener<RunningInstanceManager.RunningInstance>) c -> refreshRunningSidebar());
		refreshRunningSidebar();
		refreshQuickLaunchSidebar();
		return sidebar;
	}

	/** A sidebar nav row in the same "icon + label, left accent bar when active" shape as the in-game r-shift menu's own sidebar ({@code VeloNavButton}) - replaces the old plain text {@code navButton}. */
	private Button navIconButton(String iconName, String label, Runnable action) {
		ImageView icon = new ImageView(new Image(getClass().getResourceAsStream(
				"/net/veloclient/launcher/images/icons/nav/" + iconName + ".png"), 18, 18, true, true));
		Label text = new Label(label);
		text.setTextFill(textColor());
		HBox graphic = new HBox(10, icon, text);
		graphic.setAlignment(Pos.CENTER_LEFT);

		Button button = new Button();
		button.setGraphic(graphic);
		button.getStyleClass().add("nav-icon-button");
		button.setMaxWidth(Double.MAX_VALUE);
		button.setAlignment(Pos.CENTER_LEFT);
		button.setOnAction(e -> {
			action.run();
			markActiveNav(button);
		});
		return button;
	}

	// ---- Sidebar: Running instances ----

	private void refreshRunningSidebar() {
		List<RunningInstanceManager.RunningInstance> running = List.copyOf(RunningInstanceManager.running());
		java.util.Set<String> currentRunIds = running.stream()
				.map(RunningInstanceManager.RunningInstance::runId).collect(java.util.stream.Collectors.toSet());
		runningRowsByRunId.keySet().retainAll(currentRunIds);
		for (RunningInstanceManager.RunningInstance ri : running) {
			runningRowsByRunId.computeIfAbsent(ri.runId(), id -> buildRunningRow(ri));
		}
		runningSection.getChildren().clear();
		if (running.isEmpty()) {
			return;
		}
		Label header = new Label("RUNNING");
		header.getStyleClass().add("sidebar-section-title");
		header.setTextFill(textColor());
		runningSection.getChildren().add(header);
		for (RunningInstanceManager.RunningInstance ri : running) {
			runningSection.getChildren().add(runningRowsByRunId.get(ri.runId()));
		}
	}

	private Node buildRunningRow(RunningInstanceManager.RunningInstance ri) {
		HBox row = new HBox(8);
		row.getStyleClass().add("sidebar-mini-row");
		row.setAlignment(Pos.CENTER_LEFT);
		Node icon = buildSidebarIcon(ri.instance(), ri.serverAddress(), 22);

		Label name = new Label(ri.instance().name());
		name.setTextFill(textColor());
		name.setMaxWidth(96);

		String target = describeRunningTarget(ri);
		Label subtitle = new Label(target);
		subtitle.getStyleClass().add("sidebar-mini-subtitle");
		subtitle.setTextFill(textColor());
		subtitle.setMaxWidth(96);

		VBox textBox = new VBox(1, name, subtitle);
		HBox.setHgrow(textBox, Priority.ALWAYS);

		Button stop = new Button("✕");
		stop.getStyleClass().add("sidebar-mini-stop");
		stop.setTooltip(new Tooltip("Stop " + ri.instance().name()));
		stop.setOnAction(e -> {
			e.consume();
			ri.stop();
		});
		row.getChildren().addAll(icon, textBox, stop);
		// Hovering swaps the subtitle to a "click to view" hint rather than
		// showing both at once - there's only room for one line there, and
		// this row's own click target is the whole card anyway.
		row.setOnMouseEntered(e -> subtitle.setText("Click to view"));
		row.setOnMouseExited(e -> subtitle.setText(target));
		row.setOnMouseClicked(e -> showRunningInstance(ri));
		return row;
	}

	/** "Singleplayer" is the best-effort default when no Quick Play target is known - the launcher can't see what a player does once inside a plain launch (host their own world vs. connect manually elsewhere), only what it launched them into. */
	private String describeRunningTarget(RunningInstanceManager.RunningInstance ri) {
		String address = ri.serverAddress();
		if (address == null) {
			return "Singleplayer";
		}
		return SavedServerStore.loadAll().stream().filter(s -> s.address().equals(address)).findFirst()
				.map(SavedServer::name).orElse(address);
	}

	private void showRunningInstance(RunningInstanceManager.RunningInstance ri) {
		clearActiveNav();
		setContent(RunningInstanceView.build(ri, theme, this::showHome, this::refreshRunningSidebar));
	}

	private void clearActiveNav() {
		for (Button b : List.of(navHome, navServers, navCosmetics, navStore, navSettings)) {
			b.getStyleClass().remove("nav-icon-button-active");
		}
	}

	// ---- Sidebar: Quick Launch ----

	private static final int QUICK_LAUNCH_DISPLAY_LIMIT = 3;

	private void refreshQuickLaunchSidebar() {
		quickLaunchSection.getChildren().clear();
		List<Instance> instances = InstanceStore.loadAll();
		List<Node> rows = new java.util.ArrayList<>();
		// QuickLaunchStore itself already caps newly-recorded launches to 3,
		// but a launcher upgraded from an older version can still have more
		// than that sitting in its already-saved quick_launch.json - capped
		// again here defensively so the sidebar never shows more than 3
		// regardless of how many are on disk.
		for (QuickLaunchStore.Entry entry : QuickLaunchStore.loadAll()) {
			if (rows.size() >= QUICK_LAUNCH_DISPLAY_LIMIT) {
				break;
			}
			instances.stream().filter(i -> i.id().equals(entry.instanceId())).findFirst()
					.ifPresent(instance -> rows.add(buildQuickLaunchRow(instance, entry)));
		}
		if (rows.isEmpty()) {
			return;
		}
		Label header = new Label("QUICK LAUNCH");
		header.getStyleClass().add("sidebar-section-title");
		header.setTextFill(textColor());
		quickLaunchSection.getChildren().add(header);
		quickLaunchSection.getChildren().addAll(rows);
	}

	private Node buildQuickLaunchRow(Instance instance, QuickLaunchStore.Entry entry) {
		VBox container = new VBox(3);

		HBox row = new HBox(8);
		row.getStyleClass().add("sidebar-mini-row");
		row.setAlignment(Pos.CENTER_LEFT);
		String serverAddress = entry.serverAddress();
		Node icon = buildSidebarIcon(instance, serverAddress, 22);
		String label = serverAddress != null
				? SavedServerStore.loadAll().stream().filter(s -> s.address().equals(serverAddress)).findFirst()
						.map(SavedServer::name).orElse(serverAddress)
				: instance.name();
		Label text = new Label(label);
		text.setTextFill(textColor());
		text.setMaxWidth(96);
		HBox.setHgrow(text, Priority.ALWAYS);

		Label playIcon = new Label("▶");
		playIcon.getStyleClass().add("sidebar-mini-play");

		// A real, visible loading bar directly driven by the same
		// onPhase/onProgress callbacks a Profile/Server card's own Play/
		// Connect button uses - not a separate hand-rolled "is it busy"
		// flag - so its own visibility IS the launch state, with nothing
		// else to fall out of sync with it.
		ProgressBar progressBar = new ProgressBar(0);
		progressBar.getStyleClass().add("sidebar-mini-progress");
		progressBar.setMaxWidth(Double.MAX_VALUE);
		progressBar.setVisible(false);
		progressBar.setManaged(false);

		Button hiddenTrigger = new Button();
		Runnable startLaunch = () -> {
			if (progressBar.isVisible()) {
				// Already launching this one - ignore extra clicks instead
				// of stacking up duplicate launches.
				return;
			}
			launchWithProgress(instance, serverAddress, hiddenTrigger, progressBar, new Label());
		};
		// The whole card is clickable, not just the tiny play glyph - it's
		// styled and hover-highlighted as one clickable row (same as the
		// Running rows), so restricting the actual click target to just the
		// glyph meant most clicks on it did nothing.
		row.setOnMouseClicked(e -> {
			e.consume();
			startLaunch.run();
		});

		row.getChildren().addAll(icon, text, playIcon);
		Tooltip.install(row, new Tooltip(serverAddress != null
				? "Launch " + instance.name() + " straight into " + serverAddress
				: "Launch " + instance.name()));
		container.getChildren().addAll(row, progressBar);
		return container;
	}

	private void markActiveNav(Button active) {
		for (Button b : List.of(navHome, navServers, navCosmetics, navStore, navSettings)) {
			b.getStyleClass().remove("nav-icon-button-active");
		}
		active.getStyleClass().add("nav-icon-button-active");
	}

	private void setContent(Node node) {
		content.getChildren().setAll(node);
	}

	// ---- Home / title screen: one-click launch + the profile carousel ----

	/**
	 * One thing the Home carousel's Play button can one-click launch: either
	 * a profile on its own, or a profile launched straight into a specific
	 * server ({@code serverAddress} non-null) - the same distinction {@link
	 * QuickLaunchStore.Entry} already tracks. Collapsing every quick-launch
	 * entry down to just its {@link Instance} (the old {@code
	 * orderedProfilesForHome()} did exactly this) silently dropped which
	 * server it was a shortcut *into* - a real, confirmed bug where "launch
	 * straight back into the server you last played on" (one of the original
	 * points of this carousel) never actually showed up as its own carousel
	 * entry, only the bare profile did.
	 */
	private record HomeLaunchTarget(Instance instance, String serverAddress) {
		boolean isServer() {
			return serverAddress != null;
		}
	}

	/**
	 * Every recent quick-launch (profile-only or straight-into-a-server, up
	 * to {@link QuickLaunchStore}'s own history cap), most-recent first, then
	 * every other profile that's never been launched at all, ordered newest-
	 * created first - "the last thing you launched" (a plain profile launch
	 * or a server quick-play) is always index 0, exactly what the Play
	 * button should one-click launch again.
	 */
	private List<HomeLaunchTarget> orderedLaunchTargetsForHome() {
		List<Instance> all = InstanceStore.loadAll();
		java.util.Map<String, Instance> byId = new java.util.HashMap<>();
		for (Instance instance : all) {
			byId.put(instance.id(), instance);
		}
		List<HomeLaunchTarget> targets = new java.util.ArrayList<>();
		java.util.Set<String> coveredInstanceIds = new java.util.HashSet<>();
		for (QuickLaunchStore.Entry entry : QuickLaunchStore.loadAll()) {
			Instance instance = byId.get(entry.instanceId());
			if (instance == null) {
				continue; // profile since deleted
			}
			targets.add(new HomeLaunchTarget(instance, entry.serverAddress()));
			coveredInstanceIds.add(instance.id());
		}
		List<Instance> neverLaunched = new java.util.ArrayList<>();
		for (Instance instance : all) {
			if (!coveredInstanceIds.contains(instance.id())) {
				neverLaunched.add(instance);
			}
		}
		neverLaunched.sort(Comparator.comparingLong(Instance::createdAtEpochMillis).reversed());
		for (Instance instance : neverLaunched) {
			targets.add(new HomeLaunchTarget(instance, null));
		}
		return targets;
	}

	/** The server's saved name (falling back to its address) when {@code target} is a server quick-play shortcut, else null. */
	private String homeTargetServerName(HomeLaunchTarget target) {
		if (!target.isServer()) {
			return null;
		}
		return SavedServerStore.loadAll().stream().filter(s -> s.address().equals(target.serverAddress())).findFirst()
				.map(SavedServer::name).orElse(target.serverAddress());
	}

	private void showHome() {
		if (background == null) {
			background = new ParticleBackground(800, 600, 60);
		}
		background.setDotColor(accentColor().deriveColor(0, 1, 1, 0.35));
		background.widthProperty().unbind();
		background.heightProperty().unbind();

		StackPane titleScreen = new StackPane();
		titleScreen.getStyleClass().addAll("title-screen", "home-glow-border");
		// StackPane's default computeMinWidth/Height() equals its computed
		// preferred size, so a child that briefly *wants* to be huge (see the
		// fitWidth comment below) would otherwise force BorderPane to grow
		// content past the window's actual, fixed size - min size wins over
		// available area in Region.layoutInArea's boundedSize(). Floor both
		// to 0 so titleScreen (and its parent) can always shrink to fit.
		titleScreen.setMinSize(0, 0);
		content.setMinSize(0, 0);
		homeBorderHost = titleScreen;
		background.widthProperty().bind(titleScreen.widthProperty());
		background.heightProperty().bind(titleScreen.heightProperty());
		titleScreen.getChildren().add(background);
		background.start();

		// Soft color wash directly behind the big profile icon (added first
		// so it paints underneath it) - many profile/server icons are mostly
		// white line-art on a transparent background, which alpha-blends to
		// flat gray over the dark particle canvas with nothing behind it.
		// A Shape's fill is a plain Java Paint, not a CSS property, so this
		// doesn't need any of the CSS-pass handling homeIconImage() needs.
		homeLogoGlow = new Circle();
		homeLogoGlow.setMouseTransparent(true);
		homeLogoGlow.radiusProperty().bind(root.heightProperty().multiply(0.55));
		titleScreen.getChildren().add(homeLogoGlow);
		StackPane.setAlignment(homeLogoGlow, Pos.CENTER);
		applyLogoGlowColor(currentBorderColor);

		// Big profile icon behind everything else, sized off the window
		// itself (not a fixed pixel value) so it stays proportionally huge -
		// clearly bigger than the player render in front of it - at any
		// window size, and reacts to the window actually being resized
		// instead of needing showHome() to rerun. See updateHomeProfileDisplay().
		homeBackgroundLogo = new ImageView();
		homeBackgroundLogo.setOpacity(HOME_LOGO_OPACITY);
		homeBackgroundLogo.setPreserveRatio(true);
		// Bound to root's own size (the outermost BorderPane, fixed top-down
		// by the Scene/Stage) rather than titleScreen's - titleScreen is a
		// StackPane, and StackPane's default preferred-size computation is
		// the max of its children's preferred sizes, so binding a child's
		// size to titleScreen's *own* size created a real, confirmed runaway
		// feedback loop (bigger logo -> bigger titleScreen preferred size ->
		// bigger logo -> ...), visible as the whole window growing on its
		// own. root's size has no such path back down to this logo.
		//
		// Both fitWidth AND fitHeight are bound (not height alone): with
		// preserveRatio and only one dimension constrained, an icon image
		// whose aspect ratio isn't square (including a transient state
		// before an async-loaded image reports its real dimensions) can
		// make the *other*, unconstrained dimension balloon - and because
		// StackPane's default computeMinWidth/Height() equals its computed
		// preferred size, that oversized preference becomes an oversized
		// *minimum*, which wins over the actually-available area in
		// BorderPane's layout (Region.layoutInArea's boundedSize picks min
		// when min > available), forcing content to overflow past the
		// window - the same growth bug from a different trigger. Binding
		// both dimensions keeps the image letterboxed inside a fixed box
		// no matter its native aspect ratio, so this can't happen again.
		homeBackgroundLogo.fitHeightProperty().bind(root.heightProperty().multiply(0.98));
		homeBackgroundLogo.fitWidthProperty().bind(root.widthProperty().multiply(0.9));
		homeBackgroundLogo.setMouseTransparent(true);
		titleScreen.getChildren().add(homeBackgroundLogo);
		StackPane.setAlignment(homeBackgroundLogo, Pos.CENTER);

		// The signed-in account's 3D skin render, in front of the logo, no
		// box/background of its own (PlayerSkin3DView's SubScene is
		// transparent-filled) - fixed pose, not draggable, per design.
		homePlayerHolder = new StackPane();
		homePlayerHolder.setPrefSize(230, 340);
		homePlayerHolder.setMaxSize(230, 340);
		homePlayerHolder.setMouseTransparent(true);
		titleScreen.getChildren().add(homePlayerHolder);
		StackPane.setAlignment(homePlayerHolder, Pos.CENTER);
		StackPane.setMargin(homePlayerHolder, new Insets(0, 0, 70, 0));
		loadHomePlayerModel();

		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
		if (!targets.isEmpty()) {
			homeProfileIndex = Math.max(0, Math.min(homeProfileIndex, targets.size() - 1));
		}

		StackPane leftHit = chevronHitArea(true, () -> shiftHomeProfile(-1));
		StackPane rightHit = chevronHitArea(false, () -> shiftHomeProfile(1));
		boolean multipleTargets = targets.size() > 1;
		leftHit.setVisible(multipleTargets);
		leftHit.setManaged(multipleTargets);
		rightHit.setVisible(multipleTargets);
		rightHit.setManaged(multipleTargets);
		titleScreen.getChildren().addAll(leftHit, rightHit);
		StackPane.setAlignment(leftHit, Pos.CENTER_LEFT);
		StackPane.setAlignment(rightHit, Pos.CENTER_RIGHT);
		StackPane.setMargin(leftHit, new Insets(0, 0, 50, 10));
		StackPane.setMargin(rightHit, new Insets(0, 10, 50, 0));

		// Horizontal drag-to-swap: press-drag-release anywhere on the title
		// screen's own background (buttons/chevrons consume their own press
		// events first, so this never steals a click from them) slides the
		// carousel the same way clicking a chevron would - dragging left
		// (content trailing the cursor to the left) advances to the *next*
		// target, same direction as the right chevron.
		if (multipleTargets) {
			installHomeDragToSwap(titleScreen);
		}

		VBox topArea = new VBox(4);
		topArea.setAlignment(Pos.CENTER);
		topArea.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		Label title = new Label("VELO CLIENT");
		title.getStyleClass().add("velo-title");
		title.setTextFill(accentColor());
		title.setEffect(new DropShadow(24, accentColor().deriveColor(0, 1, 1, 0.6)));
		Label tagline = new Label("Made by Players for Players.");
		tagline.getStyleClass().add("velo-tagline");
		tagline.setTextFill(textColor());
		topArea.getChildren().addAll(title, tagline);
		titleScreen.getChildren().add(topArea);
		StackPane.setAlignment(topArea, Pos.TOP_CENTER);
		StackPane.setMargin(topArea, new Insets(18, 0, 0, 0));

		HBox topRight = new HBox(8);
		topRight.setAlignment(Pos.CENTER_RIGHT);
		topRight.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		Button newProfileButton = new Button("+ New Profile");
		newProfileButton.getStyleClass().addAll("title-menu-button", "title-menu-button-primary", "button-compact");
		newProfileButton.setOnAction(e -> createNewProfile());
		Button importProfileButton = new Button("Import");
		importProfileButton.getStyleClass().addAll("title-menu-button", "button-compact");
		importProfileButton.setOnAction(e -> importInstance());
		topRight.getChildren().addAll(newProfileButton, importProfileButton);
		titleScreen.getChildren().add(topRight);
		StackPane.setAlignment(topRight, Pos.TOP_RIGHT);
		StackPane.setMargin(topRight, new Insets(18, 22, 0, 0));

		VBox bottomArea = new VBox(8);
		bottomArea.setAlignment(Pos.CENTER);
		bottomArea.setMaxSize(440, Region.USE_PREF_SIZE);

		homeProfileName = new Label();
		homeProfileName.getStyleClass().add("home-profile-name");
		homeProfileName.setTextFill(textColor());
		homeSubtitle = new Label();
		homeSubtitle.getStyleClass().add("version-tag");
		homeSubtitle.setTextFill(textColor());

		homeActionsRowHolder = new StackPane();

		homePlayButton = new Button("Play");
		homePlayButton.getStyleClass().add("home-play-button");
		homePlayButton.setMinHeight(58);
		HBox.setHgrow(homePlayButton, Priority.ALWAYS);
		homePlayButton.setMaxWidth(Double.MAX_VALUE);

		// Beside Play rather than shrunk into the small icon row below - same
		// height as Play, a much bigger and more accessible target for what
		// (alongside launching) is the single most-used action on a profile.
		homeGearButton = new Button();
		homeGearButton.setGraphic(new ImageView(new Image(getClass().getResourceAsStream(
				"/net/veloclient/launcher/images/icons/action/manage.png"), 22, 22, true, true)));
		homeGearButton.getStyleClass().add("home-gear-button");
		homeGearButton.setMinSize(58, 58);
		homeGearButton.setMaxSize(58, 58);
		homeGearButton.setTooltip(new Tooltip("Manage mods, resource packs, shaders, schematics, datapacks..."));

		HBox playRow = new HBox(10, homePlayButton, homeGearButton);
		playRow.setAlignment(Pos.CENTER);
		playRow.setMaxWidth(320);

		homeLaunchProgress = new ProgressBar(0);
		homeLaunchProgress.setMaxWidth(320);
		homeLaunchProgress.setVisible(false);
		homeLaunchProgress.setManaged(false);
		homeLaunchStatus = new Label();
		homeLaunchStatus.getStyleClass().add("version-tag");
		homeLaunchStatus.setTextFill(textColor());
		homeLaunchStatus.setVisible(false);
		homeLaunchStatus.setManaged(false);
		homeLaunchStatus.setWrapText(true);

		bottomArea.getChildren().addAll(homeProfileName, homeSubtitle, homeActionsRowHolder, playRow, homeLaunchProgress, homeLaunchStatus);
		titleScreen.getChildren().add(bottomArea);
		StackPane.setAlignment(bottomArea, Pos.BOTTOM_CENTER);
		StackPane.setMargin(bottomArea, new Insets(0, 0, 22, 0));

		if (targets.isEmpty()) {
			homeProfileName.setText("No profiles yet");
			homeSubtitle.setText("");
			homeGearButton.setVisible(false);
			homeGearButton.setManaged(false);
			homePlayButton.setText("+ Create a Profile");
			homePlayButton.setOnAction(e -> createNewProfile());
		} else {
			updateHomeProfileDisplay(targets, false, 1);
		}

		setContent(titleScreen);
		markActiveNav(navHome);
	}

	/**
	 * Press-drag-release on {@code hitArea} (the title screen's own
	 * StackPane, behind every button/chevron - those consume their own press
	 * events first, so this never steals a click) slides the profile
	 * carousel the same way a chevron click does, once the drag passes a
	 * small threshold; a live translateX follow on the background logo while
	 * dragging gives immediate visual feedback instead of only reacting on
	 * release.
	 */
	private void installHomeDragToSwap(StackPane hitArea) {
		double[] startX = {0};
		boolean[] dragging = {false};
		double threshold = 70;

		hitArea.setOnMousePressed(e -> {
			startX[0] = e.getSceneX();
			dragging[0] = true;
		});
		hitArea.setOnMouseDragged(e -> {
			if (!dragging[0] || homeBackgroundLogo == null) {
				return;
			}
			homeBackgroundLogo.setTranslateX(e.getSceneX() - startX[0]);
		});
		hitArea.setOnMouseReleased(e -> {
			if (!dragging[0]) {
				return;
			}
			dragging[0] = false;
			double delta = e.getSceneX() - startX[0];
			if (Math.abs(delta) < threshold) {
				if (homeBackgroundLogo != null) {
					TranslateTransition snapBack = new TranslateTransition(Duration.millis(160), homeBackgroundLogo);
					snapBack.setToX(0);
					snapBack.play();
				}
				return;
			}
			if (homeBackgroundLogo != null) {
				homeBackgroundLogo.setTranslateX(0);
			}
			// Dragging left (negative delta) reads as "swipe to the next
			// item", same direction as the right chevron (+1).
			shiftHomeProfile(delta < 0 ? 1 : -1);
		});
	}

	/** A large, background-free chevron (see {@code account-switcher-chevron} for the same drawn-not-glyph reasoning) with a hover-scale animation, for switching the home carousel's selected profile. */
	private StackPane chevronHitArea(boolean pointingLeft, Runnable onClick) {
		Polyline chevron = pointingLeft
				? new Polyline(10, 0, 0, 14, 10, 28)
				: new Polyline(0, 0, 10, 14, 0, 28);
		chevron.getStyleClass().add("home-chevron");
		chevron.setStrokeWidth(4);
		chevron.setStrokeLineCap(StrokeLineCap.ROUND);
		chevron.setStrokeLineJoin(StrokeLineJoin.ROUND);

		StackPane hit = new StackPane(chevron);
		hit.setPrefSize(48, 64);
		hit.setMaxSize(48, 64);
		hit.setCursor(Cursor.HAND);
		hit.setPickOnBounds(true);
		hit.setOnMouseEntered(e -> animateScale(chevron, 1.25));
		hit.setOnMouseExited(e -> animateScale(chevron, 1.0));
		hit.setOnMouseClicked(e -> onClick.run());
		return hit;
	}

	private void animateScale(Node node, double target) {
		var scaleXTransition = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.millis(140),
				new javafx.animation.KeyValue(node.scaleXProperty(), target, Interpolator.EASE_BOTH),
				new javafx.animation.KeyValue(node.scaleYProperty(), target, Interpolator.EASE_BOTH)));
		scaleXTransition.play();
	}

	/** @param delta +1 (right chevron/swipe-left, "next") or -1 (left chevron/swipe-right, "previous") - also doubles as the slide direction so the carousel always visibly moves the way the gesture that triggered it pointed. */
	private void shiftHomeProfile(int delta) {
		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
		if (targets.size() < 2) {
			return;
		}
		homeProfileIndex = Math.floorMod(homeProfileIndex + delta, targets.size());
		updateHomeProfileDisplay(targets, true, delta);
	}

	/**
	 * Refreshes the name/subtitle/actions/Play-button/background-logo/
	 * border-glow for {@code targets.get(homeProfileIndex)}, animated
	 * (slide+fade the logo, fade the border color) when {@code animate} is
	 * true - i.e. every call except the screen's initial build.
	 *
	 * @param direction which way the slide should visibly travel - +1 slides
	 * the new content in from the right (matches the right chevron/a
	 * swipe-left gesture), -1 from the left; meaningless when {@code
	 * animate} is false.
	 */
	private void updateHomeProfileDisplay(List<HomeLaunchTarget> targets, boolean animate, int direction) {
		HomeLaunchTarget target = targets.get(homeProfileIndex);
		homeCurrentTarget = target;
		Instance instance = target.instance();
		String serverName = homeTargetServerName(target);

		homeProfileName.setText(serverName != null ? serverName : instance.name());
		homeSubtitle.setText(serverName != null ? "via " + instance.name() : "");
		homeActionsRowHolder.getChildren().setAll(buildHomeProfileActionsRow(instance));
		homeGearButton.setVisible(true);
		homeGearButton.setManaged(true);
		homeGearButton.setOnAction(e -> showInstanceDetail(instance));

		boolean signedIn = session != null;
		String launchLabel = "Fabric " + instance.mcVersion();
		homePlayButton.setText(signedIn ? (serverName != null ? "Connect · " + launchLabel : "Launch " + launchLabel) : "Sign In to Play");
		homePlayButton.setTooltip(new Tooltip(signedIn
				? (serverName != null ? "Install and connect straight into " + serverName : "Install and launch " + instance.name())
				: "Sign in with your Microsoft account first - this won't launch anything until you do."));
		homePlayButton.setOnAction(e -> {
			if (session == null) {
				SignInDialog.show(stage, MICROSOFT_CLIENT_ID, newSession -> { onSignedIn(newSession); showHome(); },
						error -> showPlaceholderAlert("Sign-in failed", error));
			} else {
				launchWithProgress(instance, target.serverAddress(), homePlayButton, homeLaunchProgress, homeLaunchStatus);
			}
		});

		Image logoImage = homeIconImage(target);
		Color glowColor = IconColorExtractor.fromImage(logoImage, accentColor());
		if (animate) {
			animateLogoSlide(logoImage, direction);
			animateBorderColor(glowColor);
		} else {
			homeBackgroundLogo.setImage(logoImage);
			applyBorderColor(glowColor);
		}
	}

	/**
	 * Slides the current background logo out one side while fading, swaps
	 * the image, then slides the new one back in from the other side while
	 * fading in - "smooth slide motion" between profiles.
	 *
	 * @param direction +1: old content exits left, new content enters from
	 * the right (the "next" direction) - -1 is the mirror image. Previously
	 * this read {@code homeBackgroundLogo.getTranslateX()} to guess a
	 * direction instead of being told one explicitly - since that property
	 * is back at (near) 0 by the time any new call starts (each slide
	 * finishes by animating it home to exactly 0), the guess was
	 * effectively constant, a real, confirmed bug where every swap slid the
	 * same way regardless of which chevron was clicked.
	 */
	private void animateLogoSlide(Image newImage, int direction) {
		double dir = direction < 0 ? -1 : 1;
		TranslateTransition out = new TranslateTransition(Duration.millis(160), homeBackgroundLogo);
		out.setToX(-60 * dir);
		FadeTransition fadeOut = new FadeTransition(Duration.millis(160), homeBackgroundLogo);
		fadeOut.setToValue(0.0);
		ParallelTransition outPhase = new ParallelTransition(out, fadeOut);
		outPhase.setOnFinished(e -> {
			homeBackgroundLogo.setImage(newImage);
			homeBackgroundLogo.setTranslateX(60 * dir);
			TranslateTransition in = new TranslateTransition(Duration.millis(200), homeBackgroundLogo);
			in.setToX(0);
			in.setInterpolator(Interpolator.EASE_OUT);
			FadeTransition fadeIn = new FadeTransition(Duration.millis(200), homeBackgroundLogo);
			fadeIn.setToValue(HOME_LOGO_OPACITY);
			new ParallelTransition(in, fadeIn).play();
		});
		outPhase.play();
	}

	private Color currentBorderColor = Color.TRANSPARENT;

	/** Sets both the inline border-color (CSS lookup colors can't take a Java {@link Color} value directly) and a matching {@link DropShadow} glow - done in Java rather than pure CSS so {@link #animateBorderColor} can interpolate it smoothly frame-by-frame. */
	/**
	 * Deliberately CSS border-color only, no {@link DropShadow}/glow effect
	 * on {@code homeBorderHost} itself - {@code Node.setEffect(...)} forces
	 * that WHOLE node (here, the entire home screen: an animated Canvas
	 * background, a 3D SubScene player render, and several bound StackPanes)
	 * to render into an offscreen buffer first. Confirmed by direct
	 * instrumentation that doing that made every other child of this same
	 * node stop appearing at all (not mispositioned - genuinely never
	 * painted), while every other symptom this method's own earlier
	 * (removed) history chased - Audiowide font metrics, StackPane
	 * TOP_CENTER/BOTTOM_CENTER alignment, translateX bindings - turned out to
	 * be red herrings once this was found and removed. A future "add a glow
	 * back" attempt should apply the effect to a small dedicated decorative
	 * node (e.g. a thin Rectangle traced just outside the border), never to
	 * the content-holding node itself.
	 */
	private void applyBorderColor(Color color) {
		currentBorderColor = color;
		homeBorderHost.setStyle("-fx-border-color: " + cssColor(argbOf(color)) + ";");
		applyLogoGlowColor(color);
	}

	private void animateBorderColor(Color target) {
		Color from = currentBorderColor;
		var timeline = new javafx.animation.Timeline();
		int steps = 20;
		for (int i = 0; i <= steps; i++) {
			double frac = i / (double) steps;
			Color frame = from.interpolate(target, frac);
			timeline.getKeyFrames().add(new javafx.animation.KeyFrame(Duration.millis(280 * frac), e -> {
				homeBorderHost.setStyle("-fx-border-color: " + cssColor(argbOf(frame)) + ";");
				applyLogoGlowColor(frame);
			}));
		}
		currentBorderColor = target;
		timeline.play();
	}

	/** Paints {@link #homeLogoGlow} as a soft radial wash of {@code color}, fading to fully transparent at the edge - a plain Shape fill, so (unlike {@link #homeIconImage}'s snapshot) no CSS pass is needed for it to show up. */
	private void applyLogoGlowColor(Color color) {
		if (homeLogoGlow == null) {
			return;
		}
		homeLogoGlow.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
				new Stop(0, color.deriveColor(0, 1, 1, 0.55)),
				new Stop(0.6, color.deriveColor(0, 1, 1, 0.28)),
				new Stop(1, color.deriveColor(0, 1, 1, 0))));
	}

	private static int argbOf(Color c) {
		return ((int) Math.round(c.getOpacity() * 255) << 24)
				| ((int) Math.round(c.getRed() * 255) << 16)
				| ((int) Math.round(c.getGreen() * 255) << 8)
				| (int) Math.round(c.getBlue() * 255);
	}

	/** The {@link HomeLaunchTarget} last passed to {@link #updateHomeProfileDisplay} - lets {@link #homeIconImage}'s async server-favicon fetch tell whether its result is still what's actually on screen (the user may well have already swiped to something else by the time a ping resolves) before applying it. */
	private HomeLaunchTarget homeCurrentTarget;

	/** The background logo/border-glow image for {@code target}: the target server's own favicon for a server quick-play shortcut (matching the sidebar's Quick Launch/Running rows and My Servers), the profile's own icon otherwise. */
	private Image homeIconImage(HomeLaunchTarget target) {
		Image profileImage = homeProfileIconImage(target.instance());
		if (!target.isServer()) {
			return profileImage;
		}
		String address = target.serverAddress();
		Optional<SavedServer> saved = SavedServerStore.loadAll().stream().filter(s -> s.address().equals(address)).findFirst();
		String host = saved.map(SavedServer::host).orElseGet(() -> parseHost(address));
		int port = saved.map(SavedServer::port).orElseGet(() -> parsePort(address));
		// ServerFaviconCache only offers an ImageView-targeting API (it's
		// meant for live sidebar rows) - probing into a throwaway,
		// never-shown ImageView and watching its imageProperty is a cheap
		// way to reuse the exact same cache/fetch/decode path for the home
		// screen's own background logo instead of duplicating it.
		ImageView probe = new ImageView();
		probe.imageProperty().addListener((obs, oldImg, newImg) -> {
			if (newImg == null || newImg == profileImage || homeBackgroundLogo == null) {
				return;
			}
			HomeLaunchTarget current = homeCurrentTarget;
			if (current == null || !current.isServer() || !address.equals(current.serverAddress())) {
				return; // the carousel moved on since this fetch started
			}
			homeBackgroundLogo.setImage(newImg);
			applyBorderColor(IconColorExtractor.fromImage(newImg, accentColor()));
		});
		ServerFaviconCache.loadInto(probe, host, port, profileImage);
		return probe.getImage();
	}

	/** A rasterized image of {@code instance}'s icon - a custom icon loads directly, a built-in (vector-drawn) one is snapshotted so both feed {@link IconColorExtractor} the same way. */
	private Image homeProfileIconImage(Instance instance) {
		if (instance.icon().kind() == InstanceIcon.Kind.CUSTOM) {
			var iconFile = InstancePaths.iconFile(instance.id());
			if (Files.exists(iconFile)) {
				return new Image(iconFile.toUri().toString(), 480, 480, true, false);
			}
		}
		Node rendered = renderInstanceIcon(instance, 480);
		// rendered is a freshly-built StackPane that was never attached to a
		// Scene, so its inline "-fx-background-color: linear-gradient(...)"
		// (see BuiltinIcons.render) has never actually been through a CSS
		// pass - without one, snapshot() rasterizes only the plain-drawn
		// glyph ImageView on top, not the colored gradient tile behind it,
		// which is why the built-in-icon background logo showed up as a
		// huge, pale, colorless outline instead of its real accent color.
		rendered.applyCss();
		if (rendered instanceof javafx.scene.Parent parent) {
			parent.layout();
		}
		javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
		params.setFill(Color.TRANSPARENT);
		return rendered.snapshot(params, null);
	}

	private void loadHomePlayerModel() {
		if (session == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(session), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(skin -> Platform.runLater(() -> {
					if (skin == null || homePlayerHolder == null) {
						return;
					}
					Node viewer = PlayerSkin3DView.createViewer(skin.pngBytes(), skin.slim(), null, false);
					if (viewer != null) {
						homePlayerHolder.getChildren().setAll(viewer);
					}
				}));
	}

	/** brush(rename)/copy(duplicate)/download(export)/delete, plus a RAM icon (per-profile memory settings) - the home screen's replacement for the old Profiles-grid card's action row. Manage mods (the gear) lives next to Play now, not in this row - see {@link #homeGearButton}. */
	private HBox buildHomeProfileActionsRow(Instance instance) {
		HBox actions = new HBox(8);
		actions.setAlignment(Pos.CENTER);
		Button renameButton = iconActionButton("brush", "Rename / change icon", false);
		renameButton.setOnAction(e -> editInstance(instance));
		Button duplicateButton = iconActionButton("duplicate", "Duplicate profile - copies its mods/config into a new one", false);
		duplicateButton.setOnAction(e -> duplicateInstance(instance));
		Button exportButton = iconActionButton("export", "Export profile - saves its mods/config as a .zip", false);
		exportButton.setOnAction(e -> exportInstance(instance));
		Button ramButton = iconActionButton("ram", "RAM & JVM settings", false);
		ramButton.setOnAction(e -> InstanceSettingsDialog.show(stage, instance).ifPresent(updated -> {
			InstanceStore.save(updated);
			showHome();
		}));
		Button deleteButton = iconActionButton("delete", "Delete profile", true);
		deleteButton.setOnAction(e -> confirmDeleteInstance(instance));
		actions.getChildren().addAll(renameButton, duplicateButton, exportButton, ramButton, deleteButton);
		return actions;
	}

	/** Dropdown listing every saved account (a player head + name each, active one checked) plus an "Add Account" entry at the bottom. */
	private void showAccountSwitcher(Node anchor) {
		ContextMenu menu = new ContextMenu();
		menu.getStyleClass().add("account-switcher-menu");

		List<MinecraftSession> accounts = AuthSession.loadAllAccounts();
		if (accounts.isEmpty()) {
			MenuItem none = new MenuItem("No saved accounts yet");
			none.setDisable(true);
			menu.getItems().add(none);
		}
		for (MinecraftSession account : accounts) {
			boolean active = session != null && session.uuid().equals(account.uuid());
			MenuItem item = new MenuItem(account.username());
			item.setGraphic(buildAccountMenuRowGraphic(account, active));
			item.setOnAction(e -> {
				if (!active) {
					switchAccount(account);
				}
			});
			menu.getItems().add(item);
		}
		menu.getItems().add(new SeparatorMenuItem());
		MenuItem addAccount = new MenuItem("+ Add Account");
		addAccount.setOnAction(e -> beginSignIn());
		menu.getItems().add(addAccount);

		menu.show(anchor, javafx.geometry.Side.TOP, 0, 0);
	}

	private Node buildAccountMenuRowGraphic(MinecraftSession account, boolean active) {
		StackPane headHolder = new StackPane();
		headHolder.setPrefSize(20, 20);
		headHolder.setMinSize(20, 20);
		headHolder.getStyleClass().add("instance-icon-custom");
		Label placeholder = new Label(account.username().substring(0, 1).toUpperCase());
		placeholder.setTextFill(Color.WHITE);
		placeholder.setFont(Font.font("System", FontWeight.BOLD, 10));
		headHolder.getChildren().add(placeholder);
		CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(account), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(skin -> Platform.runLater(() -> {
					StackPane head = skin == null ? null : PlayerHeadView.build(skin.pngBytes(), 20);
					if (head != null) {
						headHolder.getChildren().setAll(head.getChildren());
					}
				}));
		Label check = new Label(active ? "✓" : "");
		check.setMinWidth(14);
		HBox row = new HBox(8, headHolder, check);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private Node buildAccountBadgeContent() {
		HBox box = new HBox(10);
		box.setAlignment(Pos.CENTER_LEFT);
		StackPane avatarHolder = new StackPane();
		avatarHolder.setPrefSize(32, 32);
		avatarHolder.setMinSize(32, 32);
		avatarHolder.setMaxSize(32, 32);
		Label avatarFallback = new Label();
		avatarFallback.getStyleClass().add("avatar-circle");
		avatarFallback.setStyle("-fx-background-color: linear-gradient(to bottom right, " + cssColor(theme.accentStart()) + ", " + cssColor(theme.accentEnd()) + ");");
		avatarFallback.setTextFill(Color.WHITE);
		avatarHolder.getChildren().add(avatarFallback);
		accountLabel.setTextFill(textColor());

		Separator separator = new Separator(javafx.geometry.Orientation.VERTICAL);
		separator.getStyleClass().add("account-badge-separator");

		// A drawn chevron (not a font glyph - "▾" renders as a tiny,
		// inconsistently-shaped triangle depending on the system font) in a
		// deliberately oversized hit area, so it's actually easy to click
		// rather than a couple of pixels of glyph.
		javafx.scene.shape.Polyline chevron = new javafx.scene.shape.Polyline(0, 0, 4, 4, 8, 0);
		chevron.getStyleClass().add("account-switcher-chevron");
		chevron.setStrokeWidth(1.8);
		chevron.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
		chevron.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
		StackPane arrowHit = new StackPane(chevron);
		arrowHit.getStyleClass().add("account-switcher-arrow-glyph");
		arrowHit.setPrefSize(26, 26);
		arrowHit.setMinSize(26, 26);
		arrowHit.setMaxSize(26, 26);
		// Consuming here (not just on the eventual click) stops the event
		// before it bubbles up to the Button's own press/release handling,
		// which is what actually decides whether the Button fires its
		// action - consuming only the later synthesized CLICKED event would
		// be too late, since Button already reacts on MOUSE_RELEASED.
		arrowHit.setOnMousePressed(javafx.event.Event::consume);
		arrowHit.setOnMouseReleased(javafx.event.Event::consume);
		arrowHit.setOnMouseClicked(e -> {
			e.consume();
			showAccountSwitcher(accountButton);
		});

		box.getChildren().addAll(avatarHolder, accountLabel, separator, arrowHit);
		return box;
	}

	private void refreshAccountBadge() {
		HBox box = (HBox) accountButton.getGraphic();
		StackPane holder = (StackPane) box.getChildren().get(0);
		Label fallback = (Label) holder.getChildren().get(0);
		if (session != null) {
			fallback.setText(session.username().substring(0, 1).toUpperCase());
			accountLabel.setText(session.username());
			CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(session), Executors.newVirtualThreadPerTaskExecutor())
					.thenAccept(skin -> Platform.runLater(() -> {
						StackPane head = skin == null ? null : PlayerHeadView.build(skin.pngBytes(), 32);
						if (head != null && holder.getChildren().contains(fallback)) {
							holder.getChildren().setAll(head.getChildren());
						}
					}));
		} else {
			fallback.setText("?");
			accountLabel.setText("Not signed in - click to sign in");
		}
	}

	private void showAccountProfile() {
		setContent((Node) AccountProfileView.build(new AccountProfileView.Host() {
			@Override
			public Stage owner() {
				return stage;
			}

			@Override
			public LauncherTheme theme() {
				return theme;
			}

			@Override
			public MinecraftSession session() {
				return session;
			}

			@Override
			public void signOut() {
				// LauncherApp.this.signOut() already navigates home itself
				// (and, per its own doc, switches straight into another
				// saved account if one remains rather than always landing
				// on "not signed in").
				LauncherApp.this.signOut();
			}

			@Override
			public void goBack() {
				showHome();
			}
		}));
	}

	private void showServers() {
		VBox box = sectionBox("My Servers");
		box.getChildren().add(sectionSubtitle("Saved servers, pinged live for status. Assign a mod profile to each one to launch straight into it."));

		Button addButton = new Button("+ Add Server");
		addButton.getStyleClass().add("title-menu-button");
		addButton.setOnAction(e -> ServerEditDialog.show(stage, "Add Server", "", "", 25565, InstanceStore.loadAll(), null)
				.ifPresent(result -> {
					SavedServerStore.add(result.name(), result.host(), result.port(), result.instanceId());
					showServers();
				}));
		box.getChildren().add(addButton);

		VBox list = new VBox(10);
		List<SavedServer> servers = SavedServerStore.loadAll();
		if (servers.isEmpty()) {
			list.getChildren().add(sectionSubtitle("No servers saved yet - add one above."));
		}
		for (SavedServer server : servers) {
			list.getChildren().add(buildServerRow(server));
		}
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		box.getChildren().add(wrapGlass(scroll));
		setContent(box);
	}

	private Node buildServerRow(SavedServer server) {
		VBox card = new VBox(6);
		card.getStyleClass().add("glass-panel");

		HBox headerRow = new HBox(10);
		headerRow.setAlignment(Pos.CENTER_LEFT);
		StackPane serverIconHolder = new StackPane();
		serverIconHolder.setPrefSize(36, 36);
		serverIconHolder.setMinSize(36, 36);
		serverIconHolder.getStyleClass().add("instance-icon-custom");
		ImageView serverIconView = new ImageView(fallbackServerIcon());
		serverIconView.setFitWidth(36);
		serverIconView.setFitHeight(36);
		serverIconView.setPreserveRatio(true);
		serverIconHolder.getChildren().add(serverIconView);
		Label name = new Label(server.name());
		name.setFont(Font.font("System", FontWeight.BOLD, 15));
		name.setTextFill(accentColor());
		Label address = new Label(server.address());
		address.getStyleClass().add("version-tag");
		address.setTextFill(textColor());
		HBox spacer = new HBox();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		headerRow.getChildren().addAll(serverIconHolder, name, address, spacer);

		javafx.scene.text.TextFlow motd = new javafx.scene.text.TextFlow(pingingText());

		Label statusLine = new Label();
		statusLine.getStyleClass().add("section-subtitle");
		statusLine.setTextFill(textColor());
		statusLine.setWrapText(true);

		List<Instance> instances = InstanceStore.loadAll();
		ComboBox<Instance> profilePicker = new ComboBox<>();
		profilePicker.getItems().add(null);
		profilePicker.getItems().addAll(instances);
		profilePicker.setConverter(new javafx.util.StringConverter<>() {
			@Override
			public String toString(Instance instance) {
				return instance == null ? "No profile assigned" : instance.name();
			}

			@Override
			public Instance fromString(String string) {
				return null;
			}
		});
		instances.stream().filter(i -> i.id().equals(server.instanceId())).findFirst()
				.ifPresentOrElse(profilePicker::setValue, () -> profilePicker.setValue(null));

		ProgressBar progressBar = new ProgressBar(0);
		progressBar.setMaxWidth(Double.MAX_VALUE);
		progressBar.setVisible(false);
		progressBar.setManaged(false);
		Label launchStatus = new Label();
		launchStatus.getStyleClass().add("version-tag");
		launchStatus.setTextFill(textColor());
		launchStatus.setVisible(false);
		launchStatus.setManaged(false);
		launchStatus.setWrapText(true);

		Button connect = new Button("Connect");
		connect.getStyleClass().addAll("title-menu-button", "title-menu-button-primary");
		Runnable refreshConnectButton = () -> {
			Instance selected = profilePicker.getValue();
			if (selected == null) {
				connect.setDisable(true);
				connect.setText("Connect");
				connect.setTooltip(new Tooltip("Assign a mod profile above first."));
			} else if (session == null) {
				connect.setDisable(false);
				connect.setText("Sign In to Connect");
				connect.setTooltip(new Tooltip("Sign in with your Microsoft account first - this won't connect until you do."));
			} else {
				connect.setDisable(false);
				connect.setText("Connect");
				connect.setTooltip(new Tooltip("Launch straight into " + server.name()));
			}
		};
		refreshConnectButton.run();

		profilePicker.valueProperty().addListener((obs, oldVal, newVal) -> {
			SavedServerStore.update(new SavedServer(server.id(), server.name(), server.host(), server.port(),
					newVal != null ? newVal.id() : null));
			refreshConnectButton.run();
		});

		connect.setOnAction(e -> {
			Instance instance = profilePicker.getValue();
			if (instance == null) {
				return;
			}
			if (session == null) {
				SignInDialog.show(stage, MICROSOFT_CLIENT_ID, newSession -> { onSignedIn(newSession); showServers(); },
						error -> showPlaceholderAlert("Sign-in failed", error));
				return;
			}
			launchWithProgress(instance, server.address(), connect, progressBar, launchStatus);
		});

		Button refresh = new Button("Refresh");
		refresh.setOnAction(e -> {
			motd.getChildren().setAll(pingingText());
			statusLine.setText("");
			pingInto(server, motd, statusLine, serverIconView);
		});
		Button edit = new Button("Edit");
		edit.setOnAction(e -> ServerEditDialog.show(stage, "Edit Server", server.name(), server.host(), server.port(),
				InstanceStore.loadAll(), server.instanceId())
				.ifPresent(result -> {
					SavedServerStore.update(new SavedServer(server.id(), result.name(), result.host(), result.port(), result.instanceId()));
					showServers();
				}));
		Button remove = new Button("Remove");
		remove.setOnAction(e -> {
			SavedServerStore.remove(server);
			showServers();
		});

		HBox profileRow = new HBox(8, new Label("Launch with:"), profilePicker, connect);
		profileRow.setAlignment(Pos.CENTER_LEFT);
		((Label) profileRow.getChildren().get(0)).setTextFill(textColor());

		HBox actions = new HBox(8, refresh, edit, remove);

		card.getChildren().addAll(headerRow, motd, statusLine, profileRow, progressBar, launchStatus, actions);
		pingInto(server, motd, statusLine, serverIconView);
		return card;
	}

	private void pingInto(SavedServer server, javafx.scene.text.TextFlow motd, Label statusLine, ImageView iconView) {
		CompletableFuture
				.supplyAsync(() -> {
					try {
						return ServerPinger.ping(server.host(), server.port());
					} catch (Exception e) {
						return e;
					}
				}, Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(result -> Platform.runLater(() -> {
					if (result instanceof ServerPinger.PingResult ping) {
						boolean hasMotd = !net.veloclient.launcher.net.MotdText.plainText(ping.motd()).isBlank();
						motd.getChildren().setAll(hasMotd ? motdTexts(ping.motd()) : List.of());
						motd.setVisible(hasMotd);
						motd.setManaged(hasMotd);
						statusLine.setText(String.format("%s  ·  %d/%d players  ·  %dms",
								ping.versionName(), ping.onlinePlayers(), ping.maxPlayers(), ping.latencyMillis()));
						applyServerFavicon(iconView, ping.faviconPngBase64());
					} else {
						motd.getChildren().setAll(new javafx.scene.text.Text("Offline or unreachable"));
						((javafx.scene.text.Text) motd.getChildren().get(0)).setFill(textColor());
						statusLine.setText(((Exception) result).getMessage());
						// Leave the fallback icon in place - a server that's
						// merely offline right now still has a real favicon
						// worth showing next time it answers.
					}
				}));
	}

	/** The server list's per-row icon before a real favicon has (or ever) arrives - the app's own logo, same fallback style as installed-mod rows elsewhere in the launcher. */
	private Image fallbackServerIcon() {
		return new Image(getClass().getResourceAsStream("/net/veloclient/launcher/images/logo.png"), 36, 36, true, true);
	}

	/** Decodes a Server List Ping favicon (raw base64 PNG payload, no data-URI prefix) into {@code iconView}; leaves the current (fallback) image alone if it's missing or fails to decode. */
	private void applyServerFavicon(ImageView iconView, String faviconPngBase64) {
		if (faviconPngBase64 == null || faviconPngBase64.isBlank()) {
			return;
		}
		try {
			byte[] bytes = java.util.Base64.getDecoder().decode(faviconPngBase64);
			Image image = new Image(new java.io.ByteArrayInputStream(bytes), 36, 36, true, true);
			if (!image.isError()) {
				iconView.setImage(image);
			}
		} catch (IllegalArgumentException ignored) {
			// Malformed base64 - keep the fallback icon.
		}
	}

	private javafx.scene.text.Text pingingText() {
		javafx.scene.text.Text text = new javafx.scene.text.Text("Pinging...");
		text.setFill(textColor());
		return text;
	}

	/** Renders each MOTD segment as its own styled {@code Text} run - real color/bold/italic/underline/strikethrough, matching how it actually looks in vanilla's multiplayer screen instead of a flat wall of white text. */
	private List<javafx.scene.text.Text> motdTexts(List<net.veloclient.launcher.net.MotdText.Segment> segments) {
		List<javafx.scene.text.Text> texts = new java.util.ArrayList<>();
		for (net.veloclient.launcher.net.MotdText.Segment segment : segments) {
			javafx.scene.text.Text text = new javafx.scene.text.Text(segment.text());
			text.setFill(Color.rgb((segment.argbColor() >> 16) & 0xFF, (segment.argbColor() >> 8) & 0xFF, segment.argbColor() & 0xFF));
			javafx.scene.text.FontWeight weight = segment.bold() ? FontWeight.BOLD : FontWeight.NORMAL;
			javafx.scene.text.FontPosture posture = segment.italic() ? javafx.scene.text.FontPosture.ITALIC : javafx.scene.text.FontPosture.REGULAR;
			text.setFont(Font.font("System", weight, posture, 13));
			text.setUnderline(segment.underlined());
			text.setStrikethrough(segment.strikethrough());
			texts.add(text);
		}
		return texts;
	}

	// ---- Sign-in ----

	private void attemptSilentSignIn() {
		Optional<MinecraftSession> cached = AuthSession.loadCached();
		if (cached.isEmpty()) {
			return;
		}
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				MinecraftSession restored = cached.get();
				if (restored.isAccessTokenExpired()) {
					var auth = new net.veloclient.launcher.auth.MicrosoftAuth(MICROSOFT_CLIENT_ID);
					restored = auth.signInWithRefreshToken(restored.microsoftRefreshToken());
					AuthSession.save(restored);
				}
				MinecraftSession finalSession = restored;
				Platform.runLater(() -> onSignedIn(finalSession));
			} catch (Exception e) {
				// Only this account's refresh token is actually dead - drop
				// just that one rather than every saved account, and fall
				// back to whichever other one (if any) is now active.
				String deadUuid = cached.get().uuid();
				Platform.runLater(() -> {
					Optional<MinecraftSession> next = AuthSession.remove(deadUuid);
					if (next.isPresent()) {
						this.session = next.get();
						if (accountButton != null) {
							refreshAccountBadge();
						}
					}
				});
			}
		});
	}

	private void beginSignIn() {
		SignInDialog.show(stage, MICROSOFT_CLIENT_ID,
				this::onSignedIn,
				error -> showPlaceholderAlert("Sign-in failed", error));
	}

	private void onSignedIn(MinecraftSession newSession) {
		this.session = newSession;
		AuthSession.save(newSession);
		// The sidebar's account badge is built once in buildSidebar() and
		// never touched by showHome() (which only rebuilds the title-screen
		// content) - always refreshing it here (not just in the "else"
		// branch that only ran when Home *wasn't* showing) fixes a real,
		// confirmed bug where signing in while already on Home left the
		// sidebar stuck on "Not signed in" even though session was set and
		// everything else (the account switcher dropdown, Home's own Play
		// button) correctly saw the new session.
		if (accountButton != null) {
			refreshAccountBadge();
		}
		if (content.getChildren().stream().anyMatch(n -> n.getStyleClass().contains("title-screen"))) {
			showHome();
		}
	}

	/** Signs out of the current account only - switches straight into another saved account if one remains, otherwise drops back to "not signed in". */
	private void signOut() {
		if (session == null) {
			showHome();
			return;
		}
		Optional<MinecraftSession> next = AuthSession.remove(session.uuid());
		session = next.orElse(null);
		showHome();
	}

	/** Switches to an already-saved account (from the account switcher dropdown) - no re-authentication needed unless its token has since expired. */
	private void switchAccount(MinecraftSession target) {
		AuthSession.switchTo(target.uuid());
		this.session = target;
		if (accountButton != null) {
			refreshAccountBadge();
		}
		if (content.getChildren().stream().anyMatch(n -> n.getStyleClass().contains("title-screen"))) {
			showHome();
		}
	}

	private void showPlaceholderAlert(String title, String message) {
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.initOwner(stage);
		alert.setTitle(title);
		alert.setHeaderText(title);
		alert.setContentText(message);
		net.veloclient.launcher.ui.DialogStyling.apply(alert);
		alert.showAndWait();
	}

	// ---- Cosmetics (capes) ----

	private void showCosmetics() {
		setContent(CosmeticsView.build(stage, new CosmeticsView.Host() {
			@Override
			public LauncherTheme activeTheme() {
				return theme;
			}

			@Override
			public void rebuild() {
				showCosmetics();
			}
		}));
	}

	// ---- Store ----

	private void showStore() {
		setContent(net.veloclient.launcher.ui.StoreView.build(new net.veloclient.launcher.ui.StoreView.Host() {
			@Override
			public Stage owner() {
				return stage;
			}

			@Override
			public LauncherTheme theme() {
				return theme;
			}

			@Override
			public MinecraftSession session() {
				return session;
			}

			@Override
			public void openItem(net.veloclient.launcher.data.StoreItem item) {
				showStoreItemDetail(item);
			}

			@Override
			public void rebuild() {
				showStore();
			}
		}));
		markActiveNav(navStore);
	}

	private void showStoreItemDetail(net.veloclient.launcher.data.StoreItem item) {
		setContent(net.veloclient.launcher.ui.StoreItemDetailView.build(new net.veloclient.launcher.ui.StoreItemDetailView.Host() {
			@Override
			public Stage owner() {
				return stage;
			}

			@Override
			public LauncherTheme theme() {
				return theme;
			}

			@Override
			public MinecraftSession session() {
				return session;
			}

			@Override
			public void goBack() {
				showStore();
			}
		}, item));
	}

	// ---- Theme editor ----

	private void showThemeEditor() {
		setContent(ThemeEditorView.build(new ThemeEditorView.Host() {
			@Override
			public LauncherTheme activeTheme() {
				return theme;
			}

			@Override
			public void setActiveTheme(LauncherTheme newTheme) {
				theme = newTheme;
				ThemeStore.save(theme);
				applyTheme();
			}

			@Override
			public void rebuild() {
				showThemeEditor();
			}
		}));
	}

	// ---- Profiles (mod loadouts you launch with Play) - see showHome() for the carousel that replaced the old grid page ----

	/**
	 * A small square icon-only action button (Edit/Duplicate/Export/Delete
	 * on a profile card), with a tooltip explaining what it does. A real
	 * bundled icon image, not a Unicode glyph - text-glyph buttons here
	 * previously rendered visibly blank for some icons (delete's trash
	 * emoji in particular) since JavaFX's default font on some systems has
	 * no glyph for it at all, unlike a bundled image which always renders
	 * the same regardless of what fonts happen to be installed.
	 */
	private Button iconActionButton(String iconName, String tooltip, boolean danger) {
		Image image = new Image(getClass().getResourceAsStream(
				"/net/veloclient/launcher/images/icons/action/" + iconName + ".png"), 19, 19, true, true);
		Button button = new Button();
		button.setGraphic(new ImageView(image));
		button.getStyleClass().add("icon-action-button");
		if (danger) {
			button.getStyleClass().add("icon-action-button-danger");
		}
		button.setTooltip(new Tooltip(tooltip));
		return button;
	}

	private void showInstanceDetail(Instance instance) {
		// Previously let any exception from building this view propagate
		// straight into the FX event dispatcher, which logs it to stderr but
		// otherwise looks exactly like "clicking the icon does nothing" -
		// silently no-op from the user's side, with zero indication
		// anything went wrong at all. Surfacing it explicitly at least turns
		// that into an actionable error instead of a mystery.
		try {
			setContent(InstanceDetailView.build(stage, instance, theme, this::showHome));
		} catch (Exception e) {
			e.printStackTrace();
			showPlaceholderAlert("Couldn't open \"" + instance.name() + "\"",
					(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
							+ "\n\nThis profile's data may be from an older/incompatible launcher version.");
		}
	}

	/** Top-right "+ New Profile" button on the home screen - was previously a grid tile on the old Profiles page. */
	private void createNewProfile() {
		InstanceEditDialog.show(stage, theme, "New Profile", "", null, InstanceIcon.builtin(BuiltinIcons.DEFAULT))
				.ifPresent(result -> {
					Instance instance = InstanceStore.createNew(
							result.name().isBlank() ? "New Profile" : result.name(), result.mcVersion(), result.icon());
					applyIconChoice(instance, result);
					installModLoaderJars(instance);
					homeProfileIndex = 0;
					showHome();
				});
	}

	private void editInstance(Instance instance) {
		InstanceEditDialog.show(stage, theme, "Edit Profile", instance.name(), instance.mcVersion(), instance.icon())
				.ifPresent(result -> {
					Instance updated = new Instance(instance.id(),
							result.name().isBlank() ? instance.name() : result.name(),
							result.mcVersion(), result.icon(), instance.createdAtEpochMillis(),
							instance.ramMinMb(), instance.ramMaxMb(), instance.extraJvmArgs());
					InstanceStore.save(updated);
					applyIconChoice(updated, result);
					installModLoaderJars(updated);
					showHome();
				});
	}

	/**
	 * Installs velo-client's own jar (bundled, instant) plus the exact Fabric
	 * API build it needs (downloaded once per version, then cached - see
	 * {@link FabricApiInstaller}) into a profile's mods folder. Fabric API's
	 * download runs in the background so creating/editing a profile never
	 * blocks the UI on network access.
	 */
	private void installModLoaderJars(Instance instance) {
		GameVersion version = GameVersion.byId(instance.mcVersion());
		var modsDir = InstancePaths.modsDir(instance.id());
		GameJars.installInto(modsDir, version);
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				FabricApiInstaller.installInto(modsDir, version);
			} catch (Exception e) {
				Platform.runLater(() -> showPlaceholderAlert("Couldn't install Fabric API for " + instance.name(),
						e.getMessage() + "\n\nIt will be installed automatically the next time you press Play."));
			}
		});
	}

	private void applyIconChoice(Instance instance, InstanceEditDialog.Result result) {
		try {
			if (result.icon().kind() == InstanceIcon.Kind.CUSTOM && result.customIconSource() != null) {
				Files.copy(result.customIconSource(), InstancePaths.iconFile(instance.id()), StandardCopyOption.REPLACE_EXISTING);
			} else if (result.icon().kind() == InstanceIcon.Kind.BUILTIN) {
				Files.deleteIfExists(InstancePaths.iconFile(instance.id()));
			}
		} catch (IOException e) {
			showPlaceholderAlert("Couldn't save icon", e.getMessage());
		}
	}

	/** Prompts for a new name, then copies the profile's mods/config/packs (not its saves) into a fresh one - see {@link InstanceStore#duplicate}. */
	private void duplicateInstance(Instance instance) {
		TextInputDialog dialog = new TextInputDialog(instance.name() + " (Copy)");
		dialog.initOwner(stage);
		dialog.setTitle("Duplicate Profile");
		dialog.setHeaderText("Duplicate \"" + instance.name() + "\"");
		dialog.setContentText("New profile name:");
		DialogStyling.apply(dialog);
		dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty()).ifPresent(name -> {
			InstanceStore.duplicate(instance, name);
			showHome();
		});
	}

	/** Zips the profile's mods/config/resource packs/shader packs to a file the user picks - see {@link ProfileArchive#export}. */
	private void exportInstance(Instance instance) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Export \"" + instance.name() + "\"");
		chooser.setInitialFileName(safeFileName(instance.name()) + ".zip");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Velo profile archive (*.zip)", "*.zip"));
		java.io.File target = chooser.showSaveDialog(stage);
		if (target == null) {
			return;
		}
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				ProfileArchive.export(instance, target.toPath());
			} catch (IOException e) {
				Platform.runLater(() -> showPlaceholderAlert("Export failed", e.getMessage()));
			}
		});
	}

	private static String safeFileName(String name) {
		return name.replaceAll("[^a-zA-Z0-9._-]+", "-");
	}

	/**
	 * Imports a profile archive as a brand new profile - accepts either
	 * this launcher's own export (exact Minecraft version/RAM/JVM args
	 * restored) or a generic zip of jars from anywhere else (every {@code
	 * .jar} found becomes a mod, best-effort) - see {@link ProfileArchive#importFrom}.
	 * Fabric API and Velo Client's own jar are (re)installed afterward
	 * regardless, so an archive that never had them still ends up with a
	 * working profile.
	 */
	private void importInstance() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Import Profile");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Profile/mods archive (*.zip)", "*.zip"));
		java.io.File source = chooser.showOpenDialog(stage);
		if (source == null) {
			return;
		}
		String defaultName = source.getName().replaceFirst("(?i)\\.zip$", "");
		TextInputDialog dialog = new TextInputDialog(defaultName.isBlank() ? "Imported Profile" : defaultName);
		dialog.initOwner(stage);
		dialog.setTitle("Import Profile");
		dialog.setHeaderText("Name this imported profile");
		dialog.setContentText("Profile name:");
		DialogStyling.apply(dialog);
		dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty()).ifPresent(name ->
				Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
					try {
						GameVersion[] versions = GameVersion.values();
						String fallbackVersion = versions[versions.length - 1].id();
						Instance imported = ProfileArchive.importFrom(source.toPath(), name, fallbackVersion);
						Platform.runLater(() -> {
							try {
								installModLoaderJars(imported);
							} catch (IllegalArgumentException e) {
								// The archive named a Minecraft version this launcher doesn't
								// support - the profile itself still imported fine (mods/config
								// already extracted), just without velo-client/Fabric API
								// auto-installed; editing its version in "Edit" will retry that.
								showPlaceholderAlert("Imported with a warning",
										"\"" + imported.mcVersion() + "\" isn't a supported Minecraft version, so "
												+ "Velo Client/Fabric API weren't installed automatically. Edit the "
												+ "profile to set a supported version and they'll install then.");
							}
							showHome();
						});
					} catch (IOException e) {
						Platform.runLater(() -> showPlaceholderAlert("Import failed", e.getMessage()));
					}
				}));
	}

	private void confirmDeleteInstance(Instance instance) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(stage);
		alert.setTitle("Delete Profile");
		alert.setHeaderText("Delete \"" + instance.name() + "\"?");
		alert.setContentText("This deletes its mods, saves, and config. This can't be undone.");
		DialogStyling.apply(alert);
		alert.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
			InstanceStore.delete(instance);
			homeProfileIndex = 0;
			showHome();
		});
	}

	private Node renderInstanceIcon(Instance instance, double size) {
		if (instance.icon().kind() == InstanceIcon.Kind.CUSTOM) {
			var iconFile = InstancePaths.iconFile(instance.id());
			if (Files.exists(iconFile)) {
				ImageView view = new ImageView(new Image(iconFile.toUri().toString(), size, size, true, true));
				StackPane wrapper = new StackPane(view);
				wrapper.setPrefSize(size, size);
				wrapper.getStyleClass().add("instance-icon-custom");
				return wrapper;
			}
		}
		return BuiltinIcons.render(instance.icon().kind() == InstanceIcon.Kind.BUILTIN ? instance.icon().value() : BuiltinIcons.DEFAULT,
				size, accentColor(), accentColor().deriveColor(0, 1, 0.7, 1));
	}

	/** The sidebar's Running/Quick Launch icon: the target server's own favicon for a server launch (matching My Servers), the profile's own icon for a plain launch. */
	private Node buildSidebarIcon(Instance instance, String serverAddress, double size) {
		if (serverAddress == null) {
			return renderInstanceIcon(instance, size);
		}
		StackPane holder = new StackPane();
		holder.setPrefSize(size, size);
		holder.setMinSize(size, size);
		holder.getStyleClass().add("instance-icon-custom");
		ImageView view = new ImageView();
		view.setFitWidth(size);
		view.setFitHeight(size);
		view.setPreserveRatio(true);
		holder.getChildren().add(view);

		Optional<SavedServer> saved = SavedServerStore.loadAll().stream().filter(s -> s.address().equals(serverAddress)).findFirst();
		String host = saved.map(SavedServer::host).orElseGet(() -> parseHost(serverAddress));
		int port = saved.map(SavedServer::port).orElseGet(() -> parsePort(serverAddress));
		ServerFaviconCache.loadInto(view, host, port, fallbackServerIcon());
		return holder;
	}

	private static String parseHost(String address) {
		int colon = address.lastIndexOf(':');
		return colon > 0 ? address.substring(0, colon) : address;
	}

	private static int parsePort(String address) {
		int colon = address.lastIndexOf(':');
		if (colon <= 0) {
			return 25565;
		}
		try {
			return Integer.parseInt(address.substring(colon + 1));
		} catch (NumberFormatException e) {
			return 25565;
		}
	}

	private void launchInstance(Instance instance, Button playButton, ProgressBar progressBar, Label statusLabel) {
		launchWithProgress(instance, null, playButton, progressBar, statusLabel);
	}

	/** @param quickPlayTarget nullable "host:port" - when present, launches straight into that server (My Servers' Connect). */
	private void launchWithProgress(Instance instance, String quickPlayTarget, Button triggerButton, ProgressBar progressBar, Label statusLabel) {
		// Refreshes velo-client's own jar from whatever is bundled in THIS
		// running launcher build before every single launch (cheap local
		// file copy, no network - see GameJars) - otherwise a profile
		// created under an older launcher version would keep running that
		// older velo-client jar forever, since nothing else ever touches an
		// existing profile's mods folder again after it's created.
		GameJars.installInto(InstancePaths.modsDir(instance.id()), GameVersion.byId(instance.mcVersion()));
		requireSignedIn(activeSession -> {
			triggerButton.setDisable(true);
			progressBar.setProgress(0);
			progressBar.setVisible(true);
			progressBar.setManaged(true);
			statusLabel.setVisible(true);
			statusLabel.setManaged(true);
			statusLabel.setText("Starting...");

			LaunchProgressListener listener = new LaunchProgressListener() {
				@Override
				public void onPhase(String phase) {
					Platform.runLater(() -> {
						statusLabel.setText(phase);
						progressBar.setProgress(0);
					});
				}

				@Override
				public void onProgress(double fraction) {
					Platform.runLater(() -> progressBar.setProgress(fraction));
				}
			};

			long startedAt = System.currentTimeMillis();
			Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
				try {
					GameLauncher.LaunchResult result = GameLauncher.launch(instance, activeSession, listener, quickPlayTarget);
					RunningInstanceManager.RunningInstance running =
							RunningInstanceManager.register(instance, result.process(), result.logFile(), quickPlayTarget);
					QuickLaunchStore.recordLaunch(instance.id(), quickPlayTarget);
					Platform.runLater(() -> {
						// Re-enable right away (not "not until it exits") -
						// multi-instancing means this exact button may need
						// pressing again for another concurrent copy of the
						// same profile while this one is still running; the
						// sidebar's "Running" section is now the source of
						// truth for what's actually up.
						triggerButton.setDisable(false);
						progressBar.setVisible(false);
						progressBar.setManaged(false);
						statusLabel.setVisible(false);
						statusLabel.setManaged(false);
						refreshRunningSidebar();
						refreshQuickLaunchSidebar();
					});
					int exitCode = result.process().waitFor();
					boolean crashedEarly = exitCode != 0 && (System.currentTimeMillis() - startedAt) < 15_000;
					// A non-zero exit after a deliberate Stop is expected
					// (destroy()'d processes don't exit 0) - not a crash.
					if (exitCode != 0 && !running.wasStopped()) {
						Platform.runLater(() -> ErrorDialog.showLaunchFailure(stage, instance, exitCode, crashedEarly, result.logFile()));
					}
				} catch (Exception e) {
					Platform.runLater(() -> {
						triggerButton.setDisable(false);
						progressBar.setVisible(false);
						progressBar.setManaged(false);
						statusLabel.setVisible(false);
						statusLabel.setManaged(false);
						ErrorDialog.show(stage, "Couldn't launch " + instance.name(), e.getMessage(), stackTraceOf(e), null);
					});
				}
			});
		});
	}

	private static String stackTraceOf(Throwable t) {
		java.io.StringWriter sw = new java.io.StringWriter();
		t.printStackTrace(new java.io.PrintWriter(sw));
		return sw.toString();
	}

	/** Runs {@code action} with a signed-in, non-expired session - signing in (or refreshing) first if needed. */
	private void requireSignedIn(Consumer<MinecraftSession> action) {
		if (session != null && !session.isAccessTokenExpired()) {
			action.accept(session);
			return;
		}
		if (session != null) {
			Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
				try {
					var auth = new MicrosoftAuth(MICROSOFT_CLIENT_ID);
					MinecraftSession refreshed = auth.signInWithRefreshToken(session.microsoftRefreshToken());
					Platform.runLater(() -> {
						onSignedIn(refreshed);
						action.accept(refreshed);
					});
				} catch (Exception e) {
					// Only this account's refresh token is dead - drop just
					// that one and fall back to another saved account if one
					// exists, same as a manual sign-out.
					Platform.runLater(() -> {
						Optional<MinecraftSession> next = AuthSession.remove(session.uuid());
						session = next.orElse(null);
						if (session != null) {
							// Re-enter rather than using it directly - the
							// fallback account's own cached token may also
							// be expired and need its own refresh first.
							requireSignedIn(action);
						} else {
							beginSignIn();
						}
					});
				}
			});
			return;
		}
		SignInDialog.show(stage, MICROSOFT_CLIENT_ID, newSession -> {
			onSignedIn(newSession);
			action.accept(newSession);
		}, error -> showPlaceholderAlert("Sign-in failed", error));
	}

	// ---- Settings ----

	private void showSettings() {
		VBox box = sectionBox("Settings");

		VBox content = new VBox(16);

		VBox appearanceCard = settingsCard("Appearance",
				"Colors, gradients, and presets for the whole launcher.");
		Button themeButton = new Button("Open Theme Editor");
		themeButton.getStyleClass().addAll("title-menu-button", "button-compact");
		themeButton.setOnAction(e -> showThemeEditor());
		appearanceCard.getChildren().add(themeButton);
		content.getChildren().add(appearanceCard);

		VBox logsCard = settingsCard("Logs",
				"The launcher's own log (not the game's) - useful when reporting a launcher bug.");
		Button openLogsButton = new Button("Open Logs Folder");
		openLogsButton.getStyleClass().addAll("title-menu-button", "button-compact");
		openLogsButton.setOnAction(e -> {
			try {
				InstanceDetailView.openInFileManager(VeloPaths.logs());
			} catch (Exception ex) {
				showPlaceholderAlert("Couldn't open logs folder", ex.getMessage());
			}
		});
		logsCard.getChildren().add(openLogsButton);
		content.getChildren().add(logsCard);

		VBox dataCard = settingsCard("Data locations", null);
		dataCard.getChildren().addAll(
				sectionSubtitle("Config root: " + VeloPaths.root()),
				sectionSubtitle("Manifest: " + VeloPaths.manifestFile()),
				sectionSubtitle("Profiles: " + VeloPaths.profiles()),
				sectionSubtitle("Capes: " + VeloPaths.capes()));
		content.getChildren().add(dataCard);

		VBox accountCard = settingsCard("Account", null);
		if (session != null) {
			accountCard.getChildren().add(sectionSubtitle("Signed in as: " + session.username() + " (" + session.uuid() + ")"));
			Button signOutButton = new Button("Sign out");
			signOutButton.getStyleClass().add("button-compact");
			signOutButton.setOnAction(e -> signOut());
			accountCard.getChildren().add(signOutButton);
		} else {
			accountCard.getChildren().add(sectionSubtitle("Not signed in."));
		}
		content.getChildren().add(accountCard);

		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		box.getChildren().add(scroll);
		setContent(box);
	}

	private VBox settingsCard(String heading, String subtitle) {
		VBox card = new VBox(8);
		card.getStyleClass().add("glass-panel");
		Label headingLabel = new Label(heading);
		headingLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
		headingLabel.setTextFill(accentColor());
		card.getChildren().add(headingLabel);
		if (subtitle != null) {
			card.getChildren().add(sectionSubtitle(subtitle));
		}
		return card;
	}

	// ---- Shared styling helpers ----

	private VBox sectionBox(String title) {
		VBox box = new VBox(14);
		Label heading = new Label(title);
		heading.getStyleClass().add("section-heading");
		heading.setTextFill(accentColor());
		box.getChildren().add(heading);
		return box;
	}

	private VBox wrapGlass(Node content) {
		VBox wrapper = new VBox(content);
		wrapper.getStyleClass().add("glass-panel");
		VBox.setVgrow(wrapper, Priority.ALWAYS);
		return wrapper;
	}

	private Label sectionSubtitle(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("section-subtitle");
		label.setTextFill(textColor());
		label.setWrapText(true);
		return label;
	}

	private Color accentColor() {
		return Color.rgb((theme.accentStart() >> 16) & 0xFF, (theme.accentStart() >> 8) & 0xFF, theme.accentStart() & 0xFF);
	}

	private Color textColor() {
		return Color.rgb((theme.text() >> 16) & 0xFF, (theme.text() >> 8) & 0xFF, theme.text() & 0xFF);
	}
}
