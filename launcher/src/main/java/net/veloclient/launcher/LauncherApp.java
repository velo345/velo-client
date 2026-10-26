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
import javafx.scene.shape.Rectangle;
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
import java.util.ArrayList;
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
	private LauncherTheme theme;
	private BorderPane root;
	/** The scene's root: the window ({@link #root}) with room for cards over it, like the first-start import prompt. */
	private StackPane layers;
	private StackPane content;
	private Stage stage;
	private ParticleBackground background;
	private MinecraftSession session;
	/** Which profile the home screen's carousel is showing - an index into {@link #orderedProfilesForHome()}, clamped back to range whenever the profile list changes (e.g. after a delete). */
	private int homeProfileIndex = 0;
	private net.veloclient.launcher.ui.VoxelSceneryView homeScenery;
	private StackPane homePlayerHolder;
	/** The launch target Play currently starts (see {@link #currentTarget()}). */
	private HomeLaunchTarget homeCurrentTarget;

	public static void main(String[] args) {
		LauncherLog.install();
		// Under Wayland the compositor already presents tear-free; JavaFX's own vsync on top only makes the
		// NVIDIA driver leak ~4x faster per frame under XWayland (see RenderGate).
		if ("wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")) && System.getProperty("prism.vsync") == null) {
			System.setProperty("prism.vsync", "false");
		}
		if (!SingleInstance.acquire()) {
			LauncherLog.info("Launcher already running - brought it to the front instead of starting a second one");
			System.exit(0);
		}
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		try {
			startLauncher(stage);
		} catch (Throwable error) {
			// Never just not open: say what broke and offer a report.
			error.printStackTrace();
			net.veloclient.launcher.ui.LauncherErrors.showReportDialog(null, error, true);
		}
	}

	private void startLauncher(Stage stage) {
		this.stage = stage;
		net.veloclient.launcher.ui.RenderGate.install(stage);
		net.veloclient.launcher.social.SkinHeads.onChange(uuid -> {
			if (session != null && session.uuid() != null && session.uuid().replace("-", "").equalsIgnoreCase(uuid)) {
				refreshAccountBadge();
			}
		});
		VeloPaths.ensureDirectories();
		theme = ThemeStore.load();
		// Registers the "Audiowide" family for Font.font()/CSS -fx-font-family
		// lookups for the rest of this process - loadFont's own return value
		// (a Font at one specific size) isn't otherwise used, the family just
		// needs loading once. Same font as the in-game title screen.
		Font.loadFont(getClass().getResourceAsStream("/net/veloclient/launcher/fonts/Audiowide-Regular.ttf"), 12);
		// Inter is the UI font everywhere (same as the in-game menus) - bundled so it looks the same
		// on every OS instead of falling back to whatever sans-serif the system has.
		for (String weight : new String[] {"Regular", "Medium", "SemiBold", "Bold", "ExtraBold"}) {
			Font.loadFont(getClass().getResourceAsStream("/net/veloclient/launcher/fonts/Inter-" + weight + ".ttf"), 12);
		}
		// Sora for display headings, JetBrains Mono for versions, prices and keys (0.9.7 redesign).
		for (String file : new String[] {"Sora-SemiBold", "Sora-Bold", "JetBrainsMono-Medium", "JetBrainsMono-Bold"}) {
			Font.loadFont(getClass().getResourceAsStream("/net/veloclient/launcher/fonts/" + file + ".ttf"), 12);
		}

		root = new BorderPane();
		root.getStyleClass().add("root");
		content = new StackPane();
		content.setPadding(new Insets(24, 32, 22, 32));

		root.setTop(buildTopBar());
		root.setCenter(content);
		root.setBottom(buildLaunchBar());
		showHome();

		layers = new StackPane(root);
		net.veloclient.launcher.ui.LauncherErrors.install(layers);
		Scene scene = new Scene(layers, 1100, 680);
		scene.getStylesheets().add(getClass().getResource("/net/veloclient/launcher/launcher.css").toExternalForm());
		applyTheme();
		net.veloclient.launcher.ui.UiMotion.install(scene);
		scene.getAccelerators().put(new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.K, javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
				() -> topBar.searchField().requestFocus());

		stage.getIcons().add(new Image(getClass().getResourceAsStream("/net/veloclient/launcher/images/logo.png")));
		stage.setTitle("Velo Client Launcher");
		// Our own window frame: the top bar is the title bar, with minimize / maximize / close on its right.
		windowChrome = net.veloclient.launcher.ui.WindowChrome.install(stage);
		windowChrome.dragWith(topBar);
		windowChrome.resizeWith(scene);
		Region chromeGap = new Region();
		chromeGap.getStyleClass().add("hairline-v");
		chromeGap.setMinHeight(22);
		chromeGap.setMaxHeight(22);
		HBox.setMargin(chromeGap, new Insets(0, 4, 0, 6));
		topBar.getChildren().addAll(chromeGap, windowChrome.controls());
		layers.getStyleClass().add("window-frame");
		windowChrome.maximizedProperty().addListener((o, a, max) -> layers.pseudoClassStateChanged(
				javafx.css.PseudoClass.getPseudoClass("maximized"), max));
		stage.setScene(scene);
		// The smallest size every page still lays out cleanly at (checked with the 13-minsize tour steps).
		stage.setMinWidth(MIN_WIDTH);
		stage.setMinHeight(MIN_HEIGHT);
		net.veloclient.launcher.ui.WindowPlacement.restore(stage, 1100, 680, windowChrome);
		boolean firstStart = ensureDefaultProfile();
		stage.show();
		if (firstStart) {
			offerClientImport();
		}
		installIdleMode();
		// World Hosting: "Relaunch with matching mods" from the game.
		net.veloclient.launcher.party.WorldRelaunch.start(instance -> launchWithProgress(instance, null, new Button(), new ProgressBar(), new Label()),
				(title, text) -> showPlaceholderAlert(title, text));
		net.veloclient.launcher.SingleInstance.onActivate(() -> Platform.runLater(() -> {
			stage.setIconified(false);
			stage.show();
			stage.toFront();
		}));

		attemptSilentSignIn();
		partyController = new net.veloclient.launcher.party.PartyController(stage, this::currentProfile,
				(instance, server) -> Platform.runLater(() -> {
					showHome();
					launchWithProgress(instance, server, launchBar.playButton(), launchBar.progress(), launchBar.statusLabel());
				}));
		partyController.setOnChange(this::refreshPartyStrip);
		net.veloclient.launcher.social.LauncherSocial.addListener(event -> {
			if ("web_code".equals(event.type())) {
				// velo-client.com asked for a sign-in code (fromName carries it).
				Platform.runLater(() -> net.veloclient.launcher.ui.WebCodeDialog.show(stage, event.fromName()));
				return;
			}
			refreshFriendsBadge();
		});
		String demoShot = System.getProperty("velo.launcherDemo");
		if (demoShot != null && !demoShot.isBlank()) {
			runFriendsDemo(java.nio.file.Path.of(demoShot));
		}
		String tourDir = System.getProperty("velo.launcherTour");
		if (tourDir != null && !tourDir.isBlank()) {
			runScreenshotTour(java.nio.file.Path.of(tourDir));
		}
		String autoLaunch = System.getProperty("velo.autoLaunch");
		if (autoLaunch != null && !autoLaunch.isBlank()) {
			autoLaunch(autoLaunch, 0);
		}
	}

	/**
	 * Dev-only ({@code -Dvelo.autoLaunch=<profile name or id>}): launches that profile as soon as the account
	 * has loaded, exactly like pressing Play - used to test the game with a real profile's mods (Essential...).
	 * {@code -Dvelo.gameJvmArgs} / {@code -Dvelo.gameArgs} ('|'-separated) are passed on to the game.
	 */
	private void autoLaunch(String target, int attempt) {
		javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(attempt == 0 ? 4 : 2));
		wait.setOnFinished(e -> {
			Instance instance = InstanceStore.loadAll().stream()
					.filter(i -> i.id().equals(target) || i.name().equalsIgnoreCase(target)).findFirst().orElse(null);
			if (instance == null) {
				LauncherLog.info("Auto-launch: no profile called " + target);
				return;
			}
			if (session == null) {
				if (attempt < 30) {
					autoLaunch(target, attempt + 1);
				} else {
					LauncherLog.info("Auto-launch: not signed in");
				}
				return;
			}
			LauncherLog.info("Auto-launch: " + instance.name());
			launchWithProgress(instance, null, launchBar.playButton(), launchBar.progress(), launchBar.statusLabel());
		});
		wait.play();
	}

	/**
	 * Dev-only ({@code -Dvelo.launcherTour=<folder>}): visits the main pages one after another and
	 * saves a snapshot of each, then exits. Navigation only - nothing is changed or launched.
	 */
	private void runScreenshotTour(java.nio.file.Path folder) {
		try {
			java.nio.file.Files.createDirectories(folder);
		} catch (java.io.IOException e) {
			LauncherLog.warn("Tour folder not writable", e);
			return;
		}
		List<Instance> instances = InstanceStore.loadAll();
		List<Object[]> steps = new java.util.ArrayList<>();
		steps.add(new Object[] {"01-home", (Runnable) this::showHome});
		steps.add(new Object[] {"01a-library", (Runnable) this::showLibrary});
		steps.add(new Object[] {"01b-news", (Runnable) this::showNews});
		steps.add(new Object[] {"01c-news-post", (Runnable) () -> showNewsPost(net.veloclient.launcher.ui.NewsView.bannerPosts(
				net.veloclient.launcher.social.NewsApi.cachedFeed()).get(0))});
		steps.add(new Object[] {"01d-news-editor", (Runnable) () -> setContent(net.veloclient.launcher.ui.NewsView.editorForTour(newsHost(),
				net.veloclient.launcher.ui.NewsView.bannerPosts(net.veloclient.launcher.social.NewsApi.cachedFeed()).get(0)))});
		if (!instances.isEmpty()) {
			steps.add(new Object[] {"02-profile", (Runnable) () -> showInstanceDetail(instances.get(0))});
		}
		steps.add(new Object[] {"03-settings", (Runnable) this::showSettings});
		steps.add(new Object[] {"04-cosmetics", (Runnable) this::showCosmetics});
		steps.add(new Object[] {"04b-cosmetics-wings", (Runnable) () -> {
			net.veloclient.launcher.ui.CosmeticsView.showWingsTab();
			showCosmetics();
		}});
		steps.add(new Object[] {"04d-cosmetics-skins", (Runnable) () -> {
			net.veloclient.launcher.ui.CosmeticsView.showSkinsTab();
			showCosmetics();
		}});
		steps.add(new Object[] {"05-store", (Runnable) () -> {
			net.veloclient.launcher.ui.StoreView.showFeaturedTab();
			showStore();
		}});
		if (!net.veloclient.launcher.data.StoreCatalog.all().isEmpty()) {
			steps.add(new Object[] {"05b-store-item", (Runnable) () -> showStoreItemDetail(net.veloclient.launcher.data.StoreCatalog.all().get(0))});
		}
		if (!net.veloclient.launcher.data.WingsCatalog.all().isEmpty()) {
			steps.add(new Object[] {"05e-wings", (Runnable) () -> showWingDetail(net.veloclient.launcher.data.WingsCatalog.all().stream()
					.filter(w -> w.id().equals("shadowfang")).findFirst().orElse(net.veloclient.launcher.data.WingsCatalog.all().get(0)))});
		}
		steps.add(new Object[] {"05h-owner", (Runnable) this::showOwner});
		steps.add(new Object[] {"05i-auras", (Runnable) () -> {
			net.veloclient.launcher.ui.StoreView.showAurasTab();
			showStore();
		}});
		steps.add(new Object[] {"05k-plus", (Runnable) () -> {
			net.veloclient.launcher.ui.StoreView.showPlusTab();
			showStore();
		}});
		steps.add(new Object[] {"05j-aura", (Runnable) () -> showAuraDetail(net.veloclient.launcher.data.AuraCatalog.byId("storm").orElseThrow())});
		steps.add(new Object[] {"04c-cosmetics-auras", (Runnable) () -> {
			net.veloclient.launcher.ui.CosmeticsView.showAurasTab();
			showCosmetics();
		}});
		steps.add(new Object[] {"05f-clips", (Runnable) this::showMedia});
		steps.add(new Object[] {"05g-party", (Runnable) this::showParty});
		steps.add(new Object[] {"05d-coins", (Runnable) this::showCoins});
		steps.add(new Object[] {"05c-friends", (Runnable) this::showFriends});
		steps.add(new Object[] {"06-servers", (Runnable) this::showServers});
		steps.add(new Object[] {"07-account", (Runnable) () -> {
			if (session != null) {
				showAccountProfile();
			}
		}});
		steps.add(new Object[] {"08-themes", (Runnable) this::showThemeEditor});
		steps.add(new Object[] {"08b-referrals", (Runnable) this::showReferrals});
		steps.add(new Object[] {"05l-bundles", (Runnable) () -> {
			net.veloclient.launcher.ui.StoreView.showBundlesForTour(false);
			showStore();
		}});
		steps.add(new Object[] {"05m-bundle-page", (Runnable) () -> {
			net.veloclient.launcher.ui.StoreView.showBundlesForTour(true);
			showStore();
		}});
		steps.add(new Object[] {"08c-owner", (Runnable) this::showOwner});
		steps.add(new Object[] {"08d-owner-bundles", (Runnable) () -> {
			net.veloclient.launcher.ui.OwnerView.showBundlesTab(false);
			showOwner();
		}});
		steps.add(new Object[] {"08e-owner-bundle-edit", (Runnable) () -> {
			net.veloclient.launcher.ui.OwnerView.showBundlesTab(true);
			showOwner();
		}});
		steps.add(new Object[] {"09-switch", (Runnable) this::showClientImport});
		steps.add(new Object[] {"09b-firstrun", (Runnable) () -> {
			showHome();
			java.util.List<net.veloclient.launcher.data.ClientImport.Source> found = net.veloclient.launcher.data.ClientImport.detect();
			if (found.isEmpty()) {
				found = java.util.List.of(new net.veloclient.launcher.data.ClientImport.Source("Lunar Client", "Lunar", null, null, "1.21.11", "fabric", null, null),
						new net.veloclient.launcher.data.ClientImport.Source("Minecraft Launcher", "Minecraft", null, null, "1.21.11", "fabric", null, null));
			}
			net.veloclient.launcher.ui.OnboardingView.show(layers, onboardingHost(), found);
		}});
		for (int step = 1; step <= 4; step++) {
			int index = step;
			steps.add(new Object[] {"09c-firstrun-step" + step, (Runnable) () -> {
				layers.getChildren().removeIf(n -> n.getStyleClass().contains("modal-scrim"));
				showHome();
				java.util.List<net.veloclient.launcher.data.ClientImport.Source> found = java.util.List.of(
						new net.veloclient.launcher.data.ClientImport.Source("Lunar Client", "Lunar", null, null, "1.21.11", "fabric", null, null),
						new net.veloclient.launcher.data.ClientImport.Source("Modrinth App", "Modrinth", null, null, "1.21.11", "fabric", null, null));
				net.veloclient.launcher.ui.OnboardingView.showAtStep(layers, onboardingHost(), found, index);
			}});
		}
		steps.add(new Object[] {"10-dialog-profile", (Runnable) () -> Platform.runLater(() -> InstanceEditDialog.show(stage, theme, "New profile", "",
				null, null))});
		steps.add(new Object[] {"10b-dialog-server", (Runnable) () -> Platform.runLater(() -> ServerEditDialog.show(stage, "Add Server", "", "", 25565,
				InstanceStore.loadAll(), null))});
		steps.add(new Object[] {"10c-dialog-confirm", (Runnable) () -> Platform.runLater(() -> {
			Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
			alert.initOwner(stage);
			alert.setHeaderText("Delete \"AsteriaSMP\"?");
			alert.setContentText("Its mods, worlds and settings are removed from this PC. This can't be undone.");
			net.veloclient.launcher.ui.DialogStyling.apply(alert);
			alert.showAndWait();
		})});
		for (String page : List.of("home", "library", "servers", "store", "locker", "settings", "friends")) {
			steps.add(new Object[] {"13-minsize-" + page, (Runnable) () -> {
				stage.setWidth(MIN_WIDTH);
				stage.setHeight(MIN_HEIGHT);
				navigate(page);
			}});
		}
		steps.add(new Object[] {"12-resizetest", (Runnable) () -> {
			// Dev: drags the right edge, then the top edge, with the OS mouse, logging the window bounds.
			javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
			javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
			stage.setX(vb.getMinX() + 200);
			stage.setY(vb.getMinY() + 150);
			stage.setWidth(1200);
			stage.setHeight(760);
			double[][] plans = {{stage.getX() + stage.getWidth() - 2, stage.getY() + stage.getHeight() / 2, 12, 0},
					{stage.getX() + stage.getWidth() / 2, stage.getY() + 2, 0, -8}};
			new Thread(() -> {
				try {
					for (double[] plan : plans) {
						Platform.runLater(() -> {
							robot.mouseMove(plan[0], plan[1]);
						});
						Thread.sleep(300);
						Platform.runLater(() -> robot.mousePress(javafx.scene.input.MouseButton.PRIMARY));
						Thread.sleep(150);
						for (int i = 1; i <= 8; i++) {
							int k = i;
							Platform.runLater(() -> robot.mouseMove(plan[0] + plan[2] * k, plan[1] + plan[3] * k));
							Thread.sleep(60);
							Platform.runLater(() -> System.out.printf("[resizetest] step %d mouse=%.0f,%.0f stage=%.0f,%.0f %.0fx%.0f%n", k,
									robot.getMouseX(), robot.getMouseY(), stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
						}
						Platform.runLater(() -> robot.mouseRelease(javafx.scene.input.MouseButton.PRIMARY));
						Thread.sleep(300);
					}
				} catch (InterruptedException ignored) {
				}
			}).start();
		}});
		steps.add(new Object[] {"11-navtiming", (Runnable) () -> {
			// Dev: how long each top tab takes to build and lay out (twice: cold, then warm).
			for (int round = 0; round < Integer.getInteger("velo.navRounds", 2); round++) {
				for (String p : List.of("home", "library", "servers", "store", "locker", "news", "clips", "settings")) {
					long t0 = System.nanoTime();
					navigate(p);
					long t1 = System.nanoTime();
					stage.getScene().getRoot().applyCss();
					stage.getScene().getRoot().layout();
					long t2 = System.nanoTime();
					System.out.printf("[navtiming] round %d %-9s build %5.1f ms  css+layout %5.1f ms%n", round, p, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
				}
			}
		}});
		// -Dvelo.launcherTourOnly=servers,themes -> only steps whose name contains one of these.
		String only = System.getProperty("velo.launcherTourOnly", "");
		if (!only.isBlank()) {
			List<String> wanted = List.of(only.split(","));
			steps.removeIf(step -> wanted.stream().noneMatch(w -> ((String) step[0]).contains(w.trim())));
		}
		String extra = System.getProperty("velo.launcherTourExtra", "");
		if (extra.contains("crash")) {
			steps.add(new Object[] {"09-crash", (Runnable) () -> net.veloclient.launcher.ui.CrashReportDialog.show(stage,
					new Instance("tour-demo", "AsteriaSMP", "26.2", null, 0), 1, System.currentTimeMillis(), null)});
		}
		if (extra.contains("vibe")) {
			// Promo shots: Home in every theme preset (in memory only, nothing saved), then with a picture behind it.
			for (LauncherTheme preset : net.veloclient.launcher.theme.LauncherThemePresets.all().values()) {
				steps.add(new Object[] {"20-vibe-home-" + preset.name().toLowerCase(java.util.Locale.ROOT).replace(' ', '-'), (Runnable) () -> {
					theme = preset;
					applyTheme();
					showHome();
				}});
			}
			String picture = System.getProperty("velo.tourHomeBg");
			if (picture != null) {
				steps.add(new Object[] {"21-vibe-home-bg", (Runnable) () -> {
					theme = net.veloclient.launcher.theme.LauncherThemePresets.VELO_DARK;
					applyTheme();
					tourHomeBackground = java.nio.file.Path.of(picture);
					showHome();
				}});
			}
			steps.add(new Object[] {"22-vibe-theme-editor", (Runnable) () -> {
				tourHomeBackground = null;
				showThemeEditor();
			}});
		}
		if (extra.contains("signin")) {
			steps.add(new Object[] {"07-signin", (Runnable) () -> net.veloclient.launcher.ui.SignInDialog.demo(stage, theme)});
		}
		if (System.getProperty("velo.launcherFps") != null) {
			startFpsLog();
		}
		if (System.getProperty("velo.fxWatchdog") != null) {
			net.veloclient.launcher.ui.FxStallWatchdog.start();
		}
		runTourStep(folder, steps, 0);
	}

	/** Dev-only (launcher tour): a Home background picture shown without Velo+ or saving anything. */
	private java.nio.file.Path tourHomeBackground;

	/** The tour page being shown (for the dev FPS log). */
	private static volatile String tourPage = "";

	/**
	 * Dev-only ({@code -Dvelo.launcherFps}): logs frames per second and the slowest frame every two
	 * seconds, with the tour page - to compare pages before and after performance work.
	 */
	private static void startFpsLog() {
		new javafx.animation.AnimationTimer() {
			private long windowStart;
			private long last;
			private int frames;
			private long worst;

			@Override
			public void handle(long now) {
				if (last != 0) {
					worst = Math.max(worst, now - last);
				}
				last = now;
				frames++;
				if (windowStart == 0) {
					windowStart = now;
				} else if (now - windowStart >= 2_000_000_000L) {
					LauncherLog.info(String.format(java.util.Locale.ROOT, "FPS %-20s %5.1f fps, worst frame %4.0f ms", tourPage,
							frames / ((now - windowStart) / 1e9), worst / 1e6));
					windowStart = now;
					frames = 0;
					worst = 0;
				}
			}
		}.start();
	}

	private void runTourStep(java.nio.file.Path folder, List<Object[]> steps, int index) {
		if (index >= steps.size()) {
			Platform.exit();
			return;
		}
		javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(Duration.millis(index == 0 ? 2500 : 300));
		wait.setOnFinished(e -> {
			tourPage = (String) steps.get(index)[0];
			long built = System.nanoTime();
			((Runnable) steps.get(index)[1]).run();
			if (System.getProperty("velo.fxWatchdog") != null) {
				LauncherLog.info(String.format(java.util.Locale.ROOT, "PAGE %-20s built in %4.0f ms", tourPage, (System.nanoTime() - built) / 1e6));
			}
			// -Dvelo.launcherTourDwell=8000: stay longer on each page (performance profiling).
			javafx.animation.PauseTransition shoot = new javafx.animation.PauseTransition(Duration.millis(
					Long.getLong("velo.launcherTourDwell", 2600)));
			shoot.setOnFinished(ev -> {
				try {
					javafx.stage.Window target = javafx.stage.Window.getWindows().stream()
							.filter(w -> w.isShowing() && w != stage).reduce((a, b) -> b).orElse(stage);
					// Optional -Dvelo.launcherTourScale=2 for sharper (e.g. website) shots.
					double scale = Double.parseDouble(System.getProperty("velo.launcherTourScale", "1"));
					javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
					params.setTransform(javafx.scene.transform.Transform.scale(scale, scale));
					var image = target.getScene().getRoot().snapshot(params, null);
					java.nio.file.Path out = folder.resolve(steps.get(index)[0] + ".png");
					javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", out.toFile());
					if (target != stage) {
						target.hide();
					}
				} catch (Exception ex) {
					LauncherLog.warn("Tour screenshot failed", ex);
				}
				runTourStep(folder, steps, index + 1);
			});
			shoot.play();
		});
		wait.play();
	}

	@Override
	public void stop() {
		net.veloclient.launcher.ui.WindowPlacement.save(stage, windowChrome);
		// Tell the Velo server right away that the launcher went away (friends see it immediately).
		net.veloclient.launcher.social.LauncherSocial.shutdown();
	}

	private static final double MIN_WIDTH = 1200;
	private static final double MIN_HEIGHT = 760;

	/** The launcher's own window frame (move, resize, maximize, the window buttons). */
	private net.veloclient.launcher.ui.WindowChrome windowChrome;

	private void applyTheme() {
		root.setStyle(themeStyle());
		if (layers != null) {
			layers.setStyle(themeStyle());
		}
	}

	private String themeStyle() {
		return net.veloclient.launcher.theme.ThemeTokens.style(theme);
	}

	private static String cssColor(int argb) {
		int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)", r, g, b, a / 255.0);
	}

	// ---- Shell: top bar + launch bar ----

	private net.veloclient.launcher.ui.TopBar topBar;
	private net.veloclient.launcher.ui.LaunchBar launchBar;
	/** The top-bar page the window is on ("home", "store", ...; "" for a sub page like a profile). */
	private String activePage = "home";

	private net.veloclient.launcher.ui.TopBar buildTopBar() {
		TextField search = net.veloclient.launcher.ui.HomeSearch.create(this::globalSearchEntries);
		topBar = new net.veloclient.launcher.ui.TopBar(new net.veloclient.launcher.ui.TopBar.Host() {
			@Override
			public void navigate(String page) {
				LauncherApp.this.navigate(page);
			}

			@Override
			public void openCoins() {
				showCoins();
			}

			@Override
			public void openFriends() {
				showFriends();
			}

			@Override
			public void openNotifications(Node anchor) {
				showNotifications(anchor);
			}

			@Override
			public void openAccountMenu(Node anchor) {
				showAccountMenu(anchor);
			}
		}, search);
		refreshNews(false);
		net.veloclient.launcher.social.StoreApi.onWalletChanged(() -> Platform.runLater(this::refreshCoins));
		refreshCoins();
		checkForUpdates(null);
		refreshAccountBadge();
		refreshFriendsBadge();
		return topBar;
	}

	private static final List<String> TAB_ORDER = List.of("home", "library", "servers", "store", "locker", "news", "clips", "settings");
	/** Set by a top-bar tab click: which side the next page slides in from. */
	private int navDirection;

	private void navigate(String page) {
		int from = TAB_ORDER.indexOf(activePage);
		int to = TAB_ORDER.indexOf(page);
		navDirection = from < 0 || to < 0 || from == to ? 0 : Integer.signum(to - from);
		switch (page) {
			case "home" -> showHome();
			case "library" -> showLibrary();
			case "servers" -> showServers();
			case "store" -> {
				net.veloclient.launcher.ui.StoreView.closeSubpage();
				showStore();
			}
			case "locker" -> showCosmetics();
			case "news" -> showNews();
			case "clips" -> showMedia();
			case "settings" -> showSettings();
			case "friends" -> showFriends();
			default -> showHome();
		}
	}

	private void setActivePage(String page) {
		activePage = page;
		if (topBar != null) {
			topBar.setActive(page);
		}
	}

	private void refreshCoins() {
		if (topBar != null) {
			boolean known = session != null && net.veloclient.launcher.social.StoreApi.connected() && net.veloclient.launcher.social.StoreApi.balance() >= 0;
			topBar.setCoins(known ? net.veloclient.launcher.social.StoreApi.balance() : -1);
		}
	}

	/** The top-bar search: profiles and servers (what Home's search offered), every page and common action, and store items. */
	private List<net.veloclient.launcher.ui.HomeSearch.Entry> globalSearchEntries() {
		List<net.veloclient.launcher.ui.HomeSearch.Entry> entries = new java.util.ArrayList<>(homeSearchEntries());
		Object[][] pages = {
				{"Home", "Page", "start", (Runnable) this::showHome, "home"},
				{"Library", "Page · all profiles", "profiles instances", (Runnable) this::showLibrary, "library"},
				{"Servers", "Page", "multiplayer", (Runnable) this::showServers, "server"},
				{"Store", "Page · wings, capes, auras", "shop buy cosmetics", (Runnable) this::showStore, "store"},
				{"Velo+", "Store · subscription", "plus membership cloud clips name style", (Runnable) this::showPlus, "crown"},
				{"Get coins", "Store · Velo Coins", "coins buy balance", (Runnable) this::showCoins, "store"},
				{"Locker", "Page · your cosmetics", "cosmetics capes wings auras equip", (Runnable) this::showCosmetics, "locker"},
				{"Skins", "Locker · change your skin", "skin change upload", (Runnable) this::showSkins, "skin"},
				{"News", "Page", "updates changelog", (Runnable) this::showNews, "news"},
				{"Clips", "Page · recordings & screenshots", "media video screenshots", (Runnable) this::showMedia, "clips"},
				{"Friends", "Page · friends & party", "chat party social", (Runnable) this::showFriends, "friends"},
				{"Invite friends", "Earn Velo Coins", "referral code invite", (Runnable) this::showReferrals, "gift"},
				{"Settings", "Page", "options preferences memory ram", (Runnable) this::showSettings, "settings"},
				{"Themes", "Settings · colors", "theme colors appearance custom", (Runnable) this::showThemeEditor, "palette"},
				{"Switch from another client", "Import keybinds, servers, mods", "lunar dawn feather modrinth curseforge import", (Runnable) this::showClientImport, "swap"},
				{"New profile", "Create a profile", "create add instance", (Runnable) this::createNewProfile, "plus"},
				{"My profile", "Account", "account player stats", (Runnable) () -> { if (session != null) { showAccountProfile(); } else { beginSignIn(); } }, "user"},
		};
		for (Object[] page : pages) {
			String icon = (String) page[4];
			entries.add(new net.veloclient.launcher.ui.HomeSearch.Entry((String) page[0], (String) page[1], false, (String) page[2],
					(Runnable) page[3], () -> searchIcon(icon)));
		}
		// Store items: everything that's for sale opens its own page.
		for (var wings : net.veloclient.launcher.data.WingsCatalog.all()) {
			if (wings.inStore()) {
				entries.add(new net.veloclient.launcher.ui.HomeSearch.Entry(wings.name(), "Store · Wings", false, "wings store item",
						() -> showWingDetail(wings), () -> searchIcon("wings")));
			}
		}
		for (var cape : net.veloclient.launcher.data.StoreCatalog.all()) {
			if (cape.inStore()) {
				entries.add(new net.veloclient.launcher.ui.HomeSearch.Entry(cape.name(), "Store · Cape", false, "cape store item",
						() -> showStoreItemDetail(cape), () -> searchIcon("cape")));
			}
		}
		for (var aura : net.veloclient.launcher.data.AuraCatalog.all()) {
			if (aura.inStore()) {
				entries.add(new net.veloclient.launcher.ui.HomeSearch.Entry(aura.name(), "Store · Aura", false, "aura store item",
						() -> showAuraDetail(aura), () -> searchIcon("aura")));
			}
		}
		return entries;
	}

	private Node searchIcon(String icon) {
		StackPane box = new StackPane(net.veloclient.launcher.ui.Icons.icon(icon, 17, Color.web("#C9C9D1")));
		box.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 8;");
		box.setPrefSize(32, 32);
		return box;
	}

	private net.veloclient.launcher.ui.LaunchBar buildLaunchBar() {
		launchBar = new net.veloclient.launcher.ui.LaunchBar(new net.veloclient.launcher.ui.LaunchBar.Host() {
			@Override
			public void openProfileSwitcher(Node anchor) {
				showProfileSwitcher(anchor);
			}

			@Override
			public void openProfileOptions() {
				Instance instance = currentProfile();
				if (instance != null) {
					showInstanceDetail(instance);
				} else {
					createNewProfile();
				}
			}

			@Override
			public void play(Button trigger, ProgressBar progress, Label status) {
				playCurrent(trigger, progress, status);
			}

			@Override
			public void relaunch() {
				relaunchCurrent();
			}

			@Override
			public void openPlayMenu(Node anchor) {
				showPlayMenu(anchor);
			}
		});
		RunningInstanceManager.running().addListener((javafx.collections.ListChangeListener<RunningInstanceManager.RunningInstance>) c ->
				Platform.runLater(this::refreshLaunchBar));
		refreshLaunchBar();
		return launchBar;
	}

	/** The launch target Play starts (index {@link #homeProfileIndex} into the ordered list), or null without profiles. */
	private HomeLaunchTarget currentTarget() {
		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
		if (targets.isEmpty()) {
			homeCurrentTarget = null;
			return null;
		}
		homeProfileIndex = Math.max(0, Math.min(homeProfileIndex, targets.size() - 1));
		homeCurrentTarget = targets.get(homeProfileIndex);
		return homeCurrentTarget;
	}

	/**
	 * Points the launch bar at {@code instance} (its plain entry) unless it already targets that profile,
	 * so PLAY/CONNECT on a profile's own page never launches some other profile.
	 */
	private void focusLaunchTarget(Instance instance) {
		HomeLaunchTarget current = currentTarget();
		if (current != null && current.instance().id().equals(instance.id())) {
			return;
		}
		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
		for (int i = 0; i < targets.size(); i++) {
			if (!targets.get(i).isServer() && targets.get(i).instance().id().equals(instance.id())) {
				homeProfileIndex = i;
				refreshLaunchBar();
				return;
			}
		}
	}

	private void selectTarget(int index) {
		homeProfileIndex = index;
		refreshLaunchBar();
		if ("home".equals(activePage)) {
			showHome();
		}
	}

	private void refreshLaunchBar() {
		if (launchBar == null) {
			return;
		}
		HomeLaunchTarget target = currentTarget();
		if (target == null) {
			ImageView logo = new ImageView(net.veloclient.launcher.ui.UiImages.resource("/net/veloclient/launcher/images/logo.png", 44, 44));
			launchBar.setTarget(new net.veloclient.launcher.ui.LaunchBar.Target(logo, "No profiles yet", "Create one to start playing", "CREATE PROFILE", false));
			launchBar.setRunning(null, null);
			launchBar.setStatus("");
			refreshLaunchBarSocial();
			return;
		}
		Instance instance = target.instance();
		String serverName = homeTargetServerName(target);
		String label = session == null ? "SIGN IN" : serverName != null ? "CONNECT" : "PLAY";
		launchBar.setTarget(new net.veloclient.launcher.ui.LaunchBar.Target(buildSidebarIcon(instance, target.serverAddress(), 44),
				serverName != null ? serverName : instance.name(), homeDetailsLine(instance, serverName), label, session != null));
		RunningInstanceManager.RunningInstance running = RunningInstanceManager.running().stream()
				.filter(r -> r.instance().id().equals(instance.id())).findFirst().orElse(null);
		launchBar.setRunning(running == null ? null : running.startedAtEpochMillis(), running == null ? null : running::stop);
		launchBar.setStatus(running != null ? "" : session == null ? "Signed out" : "Ready · Fabric " + instance.mcVersion());
		refreshLaunchBarSocial();
	}

	/** "Nova and Kirito are on AsteriaSMP" / "3 friends online" next to the profile switcher. */
	private void refreshLaunchBarSocial() {
		if (launchBar == null) {
			return;
		}
		var state = net.veloclient.launcher.social.LauncherSocial.snapshot();
		if (state == null || state.friends == null || session == null) {
			launchBar.setSocialLine("");
			return;
		}
		HomeLaunchTarget target = homeCurrentTarget;
		String host = target != null && target.isServer() ? parseHost(target.serverAddress()).toLowerCase(java.util.Locale.ROOT) : null;
		List<net.veloclient.launcher.social.LauncherSocial.Friend> online = state.friends.stream().filter(f -> f.online).toList();
		List<net.veloclient.launcher.social.LauncherSocial.Friend> there = host == null ? List.of() : online.stream()
				.filter(f -> f.activity != null && f.activity.detail != null && f.activity.detail.toLowerCase(java.util.Locale.ROOT).contains(host)).toList();
		List<net.veloclient.launcher.social.LauncherSocial.Friend> shown = there.isEmpty() ? online : there;
		if (shown.isEmpty()) {
			launchBar.setSocialLine("");
			return;
		}
		Node[] heads = shown.stream().limit(3).map(f -> net.veloclient.launcher.ui.Heads.head(f.uuid, f.username, 24)).toArray(Node[]::new);
		String text;
		if (!there.isEmpty()) {
			text = there.size() == 1 ? there.get(0).username + " is playing here" : there.size() + " friends are playing here";
		} else {
			text = online.size() == 1 ? online.get(0).username + " is online" : online.size() + " friends online";
		}
		launchBar.setSocialLine(text, heads);
	}

	/**
	 * Relaunch: asks the running game to close (a normal shutdown - worlds and settings are saved), waits until it
	 * has, then starts the same profile again, into the same server if it was on one.
	 */
	private void relaunchCurrent() {
		HomeLaunchTarget target = currentTarget();
		if (target == null || launchBar == null) {
			return;
		}
		RunningInstanceManager.RunningInstance running = RunningInstanceManager.running().stream()
				.filter(r -> r.instance().id().equals(target.instance().id())).findFirst().orElse(null);
		if (running == null) {
			return;
		}
		Instance instance = running.instance();
		String server = running.serverAddress();
		launchBar.setRelaunching(true);
		running.stop();
		running.process().onExit().orTimeout(60, java.util.concurrent.TimeUnit.SECONDS).whenComplete((process, error) -> Platform.runLater(() -> {
			if (error != null && running.process().isAlive()) {
				// Didn't close within a minute (stuck): leave it to the player rather than force-killing a game that may be saving.
				launchBar.setRelaunching(false);
				showPlaceholderAlert("Couldn't relaunch", "The game is taking long to close. Close it yourself, then press Play.");
				return;
			}
			// The running list drops it on the FX thread after exit; start once that happened.
			Platform.runLater(() -> launchWithProgress(instance, server, launchBar.playButton(), launchBar.progress(), launchBar.statusLabel()));
		}));
	}

	private void playCurrent(Button trigger, ProgressBar progress, Label status) {
		HomeLaunchTarget target = currentTarget();
		if (target == null) {
			createNewProfile();
			return;
		}
		if (session == null) {
			SignInDialog.show(stage, MICROSOFT_CLIENT_ID, newSession -> {
				onSignedIn(newSession);
				refreshLaunchBar();
			}, error -> showPlaceholderAlert("Sign-in failed", error));
			return;
		}
		launchWithProgress(target.instance(), target.serverAddress(), trigger, progress, status);
	}

	/** Above the profile switcher: every launch target (recent servers and profiles), plus Library and New profile. */
	private void showProfileSwitcher(Node anchor) {
		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
		VBox list = new VBox(2);
		javafx.stage.Popup[] popup = new javafx.stage.Popup[1];
		if (!targets.isEmpty()) {
			Label header = new Label("PLAY NEXT");
			header.getStyleClass().add("popover-section");
			list.getChildren().add(header);
		}
		for (int i = 0; i < Math.min(targets.size(), 9); i++) {
			HomeLaunchTarget target = targets.get(i);
			int index = i;
			String serverName = homeTargetServerName(target);
			Label name = new Label(serverName != null ? serverName : target.instance().name());
			name.getStyleClass().add("t-strong");
			Label meta = new Label(serverName != null ? "Server · via " + target.instance().name() : "Fabric " + target.instance().mcVersion());
			meta.getStyleClass().add("t-muted");
			VBox text = new VBox(2, name, meta);
			HBox.setHgrow(text, Priority.ALWAYS);
			HBox row = new HBox(12, buildSidebarIcon(target.instance(), target.serverAddress(), 34), text);
			if (index == homeProfileIndex) {
				row.getChildren().add(net.veloclient.launcher.ui.Icons.icon("check", 16, Color.web("#3DDC97"), 2.4));
				row.getStyleClass().add("popover-row-active");
			}
			row.setAlignment(Pos.CENTER_LEFT);
			row.getStyleClass().add("popover-row");
			row.setOnMouseClicked(e -> {
				popup[0].hide();
				selectTarget(index);
			});
			list.getChildren().add(row);
		}
		Button library = new Button("Library");
		library.setGraphic(net.veloclient.launcher.ui.Icons.icon("library", 15, Color.web("#C9C9D1")));
		library.getStyleClass().add("button-sm");
		library.setOnAction(e -> {
			popup[0].hide();
			showLibrary();
		});
		Button create = new Button("New profile");
		create.setGraphic(net.veloclient.launcher.ui.Icons.icon("plus", 15, Color.web("#C9C9D1")));
		create.getStyleClass().add("button-sm");
		create.setOnAction(e -> {
			popup[0].hide();
			createNewProfile();
		});
		HBox footer = new HBox(8, library, create);
		footer.setPadding(new Insets(8, 4, 2, 4));
		list.getChildren().add(footer);
		popup[0] = net.veloclient.launcher.ui.Popover.show(anchor, list, net.veloclient.launcher.ui.Popover.Side.ABOVE_LEFT, 340);
	}

	/** The Play button's chevron: launch the selected profile straight into one of the saved servers. */
	private void showPlayMenu(Node anchor) {
		HomeLaunchTarget target = currentTarget();
		if (target == null) {
			return;
		}
		Instance instance = target.instance();
		VBox list = new VBox(2);
		javafx.stage.Popup[] popup = new javafx.stage.Popup[1];
		Label header = new Label("PLAY " + instance.name().toUpperCase(java.util.Locale.ROOT) + " INTO");
		header.getStyleClass().add("popover-section");
		list.getChildren().add(header);
		List<SavedServer> servers = SavedServerStore.loadAll();
		list.getChildren().add(playMenuRow(searchIcon("home"), "Title screen", "No server, just the game", () -> {
			popup[0].hide();
			launchWithProgress(instance, null, launchBar.playButton(), launchBar.progress(), launchBar.statusLabel());
		}));
		for (SavedServer server : servers) {
			ImageView favicon = new ImageView();
			favicon.setFitWidth(32);
			favicon.setFitHeight(32);
			ServerFaviconCache.loadInto(favicon, server.host(), server.port(), fallbackServerIcon());
			list.getChildren().add(playMenuRow(favicon, server.name(), server.address(), () -> {
				popup[0].hide();
				launchWithProgress(instance, server.address(), launchBar.playButton(), launchBar.progress(), launchBar.statusLabel());
			}));
		}
		if (servers.isEmpty()) {
			Label hint = new Label("Add servers on the Servers page to join them from here.");
			hint.getStyleClass().add("t-muted");
			hint.setWrapText(true);
			hint.setPadding(new Insets(4, 10, 6, 10));
			list.getChildren().add(hint);
		}
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setMaxHeight(420);
		scroll.setPrefViewportHeight(Math.min(420, 52.0 * (servers.size() + 1) + 40));
		popup[0] = net.veloclient.launcher.ui.Popover.show(anchor, scroll, net.veloclient.launcher.ui.Popover.Side.ABOVE_RIGHT, 320);
	}

	private HBox playMenuRow(Node icon, String title, String subtitle, Runnable action) {
		Label name = new Label(title);
		name.getStyleClass().add("t-strong");
		Label meta = new Label(subtitle);
		meta.getStyleClass().add("t-muted");
		VBox text = new VBox(2, name, meta);
		HBox row = new HBox(12, icon, text);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("popover-row");
		row.setOnMouseClicked(e -> action.run());
		return row;
	}

	/** The bell: update available, unread news, friend requests and messages, running games. */
	private void showNotifications(Node anchor) {
		refreshNotificationDot();
		VBox list = new VBox(2);
		javafx.stage.Popup[] popup = new javafx.stage.Popup[1];
		Label header = new Label("NOTIFICATIONS");
		header.getStyleClass().add("popover-section");
		list.getChildren().add(header);
		if (availableUpdate != null) {
			list.getChildren().add(notificationRow("download", "#3DDC97", "Update ready",
					"Velo Client " + availableUpdate.version() + " - install now", () -> {
						popup[0].hide();
						net.veloclient.launcher.ui.UpdateDialog.show(stage, availableUpdate);
					}));
		}
		var state = net.veloclient.launcher.social.LauncherSocial.snapshot();
		if (state != null && state.incoming != null) {
			for (var request : state.incoming) {
				list.getChildren().add(notificationRow("user-plus", "#5AA9FF", request.username + " wants to be friends", "Open Friends to accept", () -> {
					popup[0].hide();
					showFriends();
				}));
			}
		}
		if (state != null && state.friends != null) {
			for (var friend : state.friends) {
				if (friend.unread > 0) {
					list.getChildren().add(notificationRow("message", "#BCA6FF", friend.username + " sent you " + (friend.unread == 1 ? "a message" : friend.unread + " messages"),
							"Open the chat", () -> {
								popup[0].hide();
								showFriends();
							}));
				}
			}
		}
		int unread = newsFeed == null ? 0 : net.veloclient.launcher.ui.NewsView.unread(newsFeed);
		if (unread > 0) {
			list.getChildren().add(notificationRow("news", "#FF7A7E", unread == 1 ? "1 new post" : unread + " new posts", "Read what's new", () -> {
				popup[0].hide();
				showNews();
			}));
		}
		for (RunningInstanceManager.RunningInstance ri : RunningInstanceManager.running()) {
			list.getChildren().add(notificationRow("play", "#3DDC97", ri.instance().name() + " is running", describeRunningTarget(ri) + " · view log", () -> {
				popup[0].hide();
				showRunningInstance(ri);
			}));
		}
		if (list.getChildren().size() == 1) {
			Label empty = new Label("You're all caught up.");
			empty.getStyleClass().add("t-muted");
			empty.setPadding(new Insets(8, 10, 12, 10));
			list.getChildren().add(empty);
		}
		popup[0] = net.veloclient.launcher.ui.Popover.show(anchor, list, net.veloclient.launcher.ui.Popover.Side.BELOW_RIGHT, 340);
	}

	private HBox notificationRow(String icon, String color, String title, String subtitle, Runnable action) {
		StackPane badge = new StackPane(net.veloclient.launcher.ui.Icons.icon(icon, 16, Color.web(color)));
		badge.setMinSize(32, 32);
		badge.setMaxSize(32, 32);
		badge.setStyle("-fx-background-color: " + color + "22; -fx-background-radius: 9;");
		return playMenuRow(badge, title, subtitle, action);
	}

	private void refreshNotificationDot() {
		if (topBar == null) {
			return;
		}
		var state = net.veloclient.launcher.social.LauncherSocial.snapshot();
		boolean social = state != null && ((state.incoming != null && !state.incoming.isEmpty())
				|| (state.friends != null && state.friends.stream().anyMatch(f -> f.unread > 0)));
		int unread = newsFeed == null ? 0 : net.veloclient.launcher.ui.NewsView.unread(newsFeed);
		topBar.setNotificationDot(availableUpdate != null || social || unread > 0);
	}

	/** The account pill: profile, invites, skins, theme, account switching and sign out. */
	private void showAccountMenu(Node anchor) {
		VBox list = new VBox(2);
		javafx.stage.Popup[] popup = new javafx.stage.Popup[1];
		if (session == null) {
			list.getChildren().add(notificationRow("user", "#FF7A7E", "Sign in with Microsoft", "Needed to play and sync cosmetics", () -> {
				popup[0].hide();
				beginSignIn();
			}));
		} else {
			Label name = new Label(session.username());
			name.getStyleClass().add("t-heading");
			Label tier = new Label(net.veloclient.launcher.social.StoreApi.isPlusCached() ? "Velo+ member" : "Velo Client player");
			tier.getStyleClass().add("t-muted");
			HBox headerRow = new HBox(12, net.veloclient.launcher.ui.Heads.head(session.uuid(), session.username(), 40), new VBox(2, name, tier));
			headerRow.setAlignment(Pos.CENTER_LEFT);
			headerRow.setPadding(new Insets(6, 8, 10, 8));
			list.getChildren().add(headerRow);
			list.getChildren().add(menuRow("user", "My profile", () -> {
				popup[0].hide();
				showAccountProfile();
			}));
			list.getChildren().add(menuRow("gift", "Invite friends · earn coins", () -> {
				popup[0].hide();
				showReferrals();
			}));
			list.getChildren().add(menuRow("skin", "Skins", () -> {
				popup[0].hide();
				showSkins();
			}));
		}
		list.getChildren().add(menuRow("palette", "Themes", () -> {
			popup[0].hide();
			showThemeEditor();
		}));
		List<MinecraftSession> accounts = AuthSession.loadAllAccounts();
		if (!accounts.isEmpty()) {
			Label header = new Label("ACCOUNTS");
			header.getStyleClass().add("popover-section");
			list.getChildren().add(header);
			for (MinecraftSession account : accounts) {
				boolean active = session != null && session.uuid().equals(account.uuid());
				Label accountName = new Label(account.username());
				accountName.getStyleClass().add("t-strong");
				HBox.setHgrow(accountName, Priority.ALWAYS);
				accountName.setMaxWidth(Double.MAX_VALUE);
				HBox row = new HBox(10, net.veloclient.launcher.ui.Heads.head(account.uuid(), account.username(), 24), accountName);
				if (active) {
					row.getChildren().add(net.veloclient.launcher.ui.Icons.icon("check", 15, Color.web("#3DDC97"), 2.4));
				}
				row.setAlignment(Pos.CENTER_LEFT);
				row.getStyleClass().add("popover-row");
				row.setOnMouseClicked(e -> {
					popup[0].hide();
					if (!active) {
						switchAccount(account);
					}
				});
				list.getChildren().add(row);
			}
		}
		list.getChildren().add(menuRow("plus", "Add account", () -> {
			popup[0].hide();
			beginSignIn();
		}));
		if (session != null) {
			HBox signOutRow = menuRow("logout", "Sign out", () -> {
				popup[0].hide();
				signOut();
			});
			((Label) signOutRow.getChildren().get(1)).setStyle("-fx-text-fill: -velo-danger;");
			list.getChildren().add(signOutRow);
		}
		popup[0] = net.veloclient.launcher.ui.Popover.show(anchor, list, net.veloclient.launcher.ui.Popover.Side.BELOW_RIGHT, 290);
	}

	private HBox menuRow(String icon, String text, Runnable action) {
		Label label = new Label(text);
		label.getStyleClass().add("t-strong");
		label.setStyle("-fx-font-family: 'Inter Medium';");
		HBox row = new HBox(12, net.veloclient.launcher.ui.Icons.icon(icon, 17, Color.web("#A9A9B3")), label);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("popover-row");
		row.setOnMouseClicked(e -> action.run());
		return row;
	}

	private void showRunningInstance(RunningInstanceManager.RunningInstance ri) {
		setActivePage("");
		setContent(RunningInstanceView.build(ri, theme, this::showHome, this::refreshLaunchBar));
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

	// ---- Idle mode: the launcher mostly sits in the background while you play ----

	private boolean idle;
	private javafx.animation.PauseTransition idleDelay;

	/**
	 * While the window is minimized - or unfocused with a game running - the launcher drops the
	 * current page (stopping every animation and 3D view on it), empties its image caches and
	 * hands the freed heap back to the OS. Coming back rebuilds the page you were on.
	 */
	private void installIdleMode() {
		idleDelay = new javafx.animation.PauseTransition(Duration.seconds(4));
		idleDelay.setOnFinished(e -> enterIdle());
		Runnable evaluate = () -> {
			boolean background = stage.isIconified() || !stage.isShowing()
					|| (!stage.isFocused() && !RunningInstanceManager.running().isEmpty());
			if (background && !idle) {
				idleDelay.playFromStart();
			} else if (!background) {
				idleDelay.stop();
				if (idle) {
					leaveIdle();
				}
			}
		};
		// Friends see the launcher as online only while it is in front (or was a moment ago).
		Runnable reportActive = () -> net.veloclient.launcher.social.LauncherSocial.setWindowActive(stage.isFocused() && !stage.isIconified());
		stage.iconifiedProperty().addListener((o, a, b) -> reportActive.run());
		stage.focusedProperty().addListener((o, a, b) -> reportActive.run());
		stage.iconifiedProperty().addListener((o, a, b) -> evaluate.run());
		stage.focusedProperty().addListener((o, a, b) -> {
			if (b) {
				checkAccountSkin(); // back from the game, where the skin may have changed
			}
			// Unfocused-with-a-game waits longer: alt-tabbing out for a second shouldn't rebuild pages.
			idleDelay.setDuration(Duration.seconds(stage.isIconified() ? 4 : 45));
			evaluate.run();
		});
		stage.showingProperty().addListener((o, a, b) -> evaluate.run());
	}

	private void enterIdle() {
		if (idle) {
			return;
		}
		idle = true;
		content.getChildren().clear();
		net.veloclient.launcher.ui.LauncherCaches.trimAll();
		// With SerialGC + MaxHeapFreeRatio (see launcher/build.gradle) a full collection also shrinks the heap.
		System.gc();
		LauncherLog.info("Launcher idle - page released, caches cleared");
	}

	private void leaveIdle() {
		idle = false;
		Runnable page = currentPage;
		(page != null ? page : (Runnable) this::showHome).run();
	}

	private void setContent(Node node) {
		Node previous = content.getChildren().size() == 1 ? content.getChildren().get(0) : null;
		if (previous == node) {
			return;
		}
		// The same page built again (equip, select, edit, a reload finishing): keep where it was scrolled to, no page animation.
		boolean rebuilt = net.veloclient.launcher.ui.ScrollKeeper.samePage(previous, node);
		List<?> scroll = rebuilt ? net.veloclient.launcher.ui.ScrollKeeper.captureAll(previous) : List.of();
		content.getChildren().setAll(node);
		if (rebuilt) {
			net.veloclient.launcher.ui.ScrollKeeper.restoreAll(node, scroll);
		} else {
			net.veloclient.launcher.ui.UiMotion.enterFrom(node, navDirection);
		}
		navDirection = 0;
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
	/** What the home search offers: every profile (and server shortcut in the carousel), and every saved server. */
	private List<net.veloclient.launcher.ui.HomeSearch.Entry> homeSearchEntries() {
		List<net.veloclient.launcher.ui.HomeSearch.Entry> entries = new java.util.ArrayList<>();
		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
		java.util.Set<String> profilesListed = new java.util.HashSet<>();
		for (int i = 0; i < targets.size(); i++) {
			HomeLaunchTarget target = targets.get(i);
			int index = i;
			Instance instance = target.instance();
			if (target.isServer()) {
				entries.add(new net.veloclient.launcher.ui.HomeSearch.Entry(target.serverAddress(), "via " + instance.name() + "  ·  " + instance.mcVersion(),
						true, instance.name() + " " + instance.mcVersion() + " " + target.serverAddress(), () -> jumpToHomeTarget(index),
						() -> buildSidebarIcon(instance, target.serverAddress(), 32)));
			} else if (profilesListed.add(instance.id())) {
				entries.add(new net.veloclient.launcher.ui.HomeSearch.Entry(instance.name(), "Fabric " + instance.mcVersion(), false,
						instance.mcVersion() + " fabric", () -> jumpToHomeTarget(index), () -> renderInstanceIcon(instance, 32)));
			}
		}
		java.util.Map<String, Instance> byId = new java.util.HashMap<>();
		InstanceStore.loadAll().forEach(i -> byId.put(i.id(), i));
		for (SavedServer server : SavedServerStore.loadAll()) {
			Instance profile = server.instanceId() == null ? null : byId.get(server.instanceId());
			String subtitle = server.address() + (profile != null ? "  ·  " + profile.name() + " " + profile.mcVersion() : "");
			entries.add(new net.veloclient.launcher.ui.HomeSearch.Entry(server.name(), subtitle, true,
					server.host() + " " + (profile != null ? profile.name() + " " + profile.mcVersion() : ""), () -> {
						// The carousel entry for this server if there is one, otherwise the Servers page.
						for (int i = 0; i < targets.size(); i++) {
							if (targets.get(i).isServer() && targets.get(i).serverAddress().equalsIgnoreCase(server.address())) {
								jumpToHomeTarget(i);
								return;
							}
						}
						showServers();
					}, () -> buildSidebarIcon(profile, server.address(), 32)));
		}
		return entries;
	}

	private void jumpToHomeTarget(int index) {
		homeProfileIndex = index;
		showHome();
	}

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
			// Only a plain launch covers the profile itself - one used to join a server still gets its own
			// plain entry (it used to vanish from the list after the first server launch).
			if (entry.serverAddress() == null || entry.serverAddress().isBlank()) {
				coveredInstanceIds.add(instance.id());
			}
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

	/** Home's news column - a bad post must never take Home (or the launcher's start) down with it. */
	private VBox safeWhatsNew() {
		try {
			return buildHomeWhatsNew();
		} catch (RuntimeException error) {
			LauncherLog.warn("Couldn't build What's new", error);
			net.veloclient.launcher.ui.LauncherErrors.offer(error);
			return new VBox();
		}
	}

	private void showHome() {
		currentPage = this::showHome;
		setActivePage("home");
		HomeLaunchTarget target = currentTarget();
		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();

		VBox page = new VBox(22);
		page.getStyleClass().add("page");
		page.setMinSize(0, 0);

		HBox top = new HBox(18);
		top.setMinHeight(0);
		VBox.setVgrow(top, Priority.ALWAYS);
		StackPane hero = buildHomeHero(target);
		HBox.setHgrow(hero, Priority.ALWAYS);
		VBox whatsNew = safeWhatsNew();
		top.getChildren().addAll(hero, whatsNew);
		top.widthProperty().addListener((o, a, w) -> {
			boolean wide = w.doubleValue() > 980;
			whatsNew.setVisible(wide);
			whatsNew.setManaged(wide);
		});

		VBox recent = buildHomeRecent(targets);
		// Under the launch options: partner servers and worlds to join (only shown when there are some).
		Node discover = net.veloclient.launcher.ui.DiscoverView.build(discoverJoiner(), true);
		page.getChildren().addAll(top, recent, discover);
		setContent(page);
		refreshLaunchBar();
	}

	/** Settings > Appearance: the Velo+ Home background (a picture or GIF instead of the island). */
	private Node homeBackgroundRow() {
		boolean plus = net.veloclient.launcher.social.StoreApi.isPlusCached();
		java.nio.file.Path current = net.veloclient.launcher.data.HomeBackground.file();
		VBox holder = new VBox();
		javafx.scene.layout.StackPane preview = new javafx.scene.layout.StackPane();
		preview.setPrefSize(112, 56);
		preview.setMinSize(112, 56);
		preview.setMaxSize(112, 56);
		Rectangle round = new Rectangle(112, 56);
		round.setArcWidth(14);
		round.setArcHeight(14);
		preview.setClip(round);
		preview.setStyle("-fx-background-color: -velo-surface-2;");
		if (current != null) {
			preview.getChildren().add(new net.veloclient.launcher.ui.CoverImageView(new javafx.scene.image.Image(current.toUri().toString(), 224, 112, true, true, true)));
		} else {
			preview.getChildren().add(net.veloclient.launcher.ui.Icons.icon("image", 20, Color.web("#6E6E78")));
		}
		Button pick = new Button(current == null ? "Choose image or GIF" : "Change");
		pick.setGraphic(net.veloclient.launcher.ui.Icons.icon(plus ? "image" : "lock", 15, Color.web("#E4E4E8")));
		pick.setDisable(!plus);
		pick.setOnAction(e -> {
			javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
			chooser.setTitle("Home background");
			chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Images and GIFs", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"));
			java.io.File file = chooser.showOpenDialog(stage);
			if (file == null) {
				return;
			}
			try {
				net.veloclient.launcher.data.HomeBackground.set(file.toPath());
				net.veloclient.launcher.ui.Toasts.show(pick, "Home background set");
				showSettings();
			} catch (Exception ex) {
				net.veloclient.launcher.ui.Toasts.show(pick, ex.getMessage());
			}
		});
		Button reset = new Button("Use the island");
		reset.getStyleClass().add("ghost-button");
		reset.setVisible(current != null);
		reset.setManaged(current != null);
		reset.setOnAction(e -> {
			try {
				net.veloclient.launcher.data.HomeBackground.clear();
			} catch (Exception ignored) {
				// Nothing to remove.
			}
			showSettings();
		});
		net.veloclient.launcher.ui.SettingsUi.row(holder, "Home background" + (plus ? "" : " · Velo+"),
				plus ? "Your own picture or animated GIF behind the Home card" : "Velo+ members can put their own picture or GIF on Home",
				"It's scaled to always cover the whole card (cropped evenly, never stretched). PNG, JPG or GIF up to "
						+ net.veloclient.launcher.data.HomeBackground.MAX_BYTES / 1024 / 1024 + " MB. Without Velo+ the island is shown.",
				preview, reset, pick);
		return holder.getChildren().isEmpty() ? new Region() : holder.getChildren().getFirst();
	}

	/** The big "continue playing" card: the selected profile over the 3D island, with your player standing on it. */
	private StackPane buildHomeHero(HomeLaunchTarget target) {
		StackPane hero = new StackPane();
		hero.getStyleClass().add("hero");
		hero.setMinSize(0, 300);
		Rectangle clip = new Rectangle();
		clip.setArcWidth(36);
		clip.setArcHeight(36);
		clip.widthProperty().bind(hero.widthProperty());
		clip.heightProperty().bind(hero.heightProperty());
		hero.setClip(clip);

		// Velo+: your own picture or GIF fills the whole card instead of the island (and the player standing on it).
		java.nio.file.Path customBackground = tourHomeBackground != null ? tourHomeBackground
				: net.veloclient.launcher.social.StoreApi.isPlusCached() ? net.veloclient.launcher.data.HomeBackground.file() : null;
		if (customBackground != null) {
			net.veloclient.launcher.ui.CoverImageView cover = new net.veloclient.launcher.ui.CoverImageView(
					new javafx.scene.image.Image(customBackground.toUri().toString(), true));
			cover.setMouseTransparent(true);
			cover.prefWidthProperty().bind(hero.widthProperty());
			cover.prefHeightProperty().bind(hero.heightProperty());
			hero.getChildren().add(cover);
		} else {
		if (homeScenery == null) {
			homeScenery = new net.veloclient.launcher.ui.VoxelSceneryView();
		}
		homeScenery.setTheme(accentColor(), Color.web(cssColor(theme.background())));
		homeScenery.setTranslateX(0);
		StackPane art = new StackPane(homeScenery);
		art.setMinSize(0, 0);
		art.setMouseTransparent(true);
		// The island sits right of center, so the text on the left stays readable.
		art.translateXProperty().bind(hero.widthProperty().multiply(0.18));
		hero.getChildren().add(art);

		javafx.scene.shape.Ellipse playerShadow = new javafx.scene.shape.Ellipse(58, 13);
		playerShadow.setMouseTransparent(true);
		playerShadow.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.rgb(0, 0, 0, 0.55)), new Stop(1, Color.rgb(0, 0, 0, 0))));
		playerShadow.translateXProperty().bind(hero.widthProperty().multiply(0.18));
		StackPane.setMargin(playerShadow, new Insets(178, 0, 0, 0));
		hero.getChildren().add(playerShadow);
		homePlayerHolder = new StackPane();
		homePlayerHolder.setPrefSize(480, 310);
		homePlayerHolder.setMaxSize(480, 310);
		homePlayerHolder.setMouseTransparent(true);
		homePlayerHolder.translateXProperty().bind(hero.widthProperty().multiply(0.18));
		StackPane.setMargin(homePlayerHolder, new Insets(0, 0, 120, 0));
		hero.getChildren().add(homePlayerHolder);
		loadHomePlayerModel();
		}

		Region fade = new Region();
		fade.setMouseTransparent(true);
		fade.setStyle("-fx-background-color: linear-gradient(to right, -velo-surface 22%, rgba(19,19,23,0.82) 42%, transparent 72%),"
				+ " linear-gradient(to top, rgba(11,11,14,0.85), transparent 45%);");
		hero.getChildren().add(fade);

		VBox text = new VBox(16);
		text.setAlignment(Pos.BOTTOM_LEFT);
		text.setPadding(new Insets(32, 36, 32, 36));
		text.setMaxWidth(560);
		text.setPickOnBounds(false);
		StackPane.setAlignment(text, Pos.BOTTOM_LEFT);
		if (target == null) {
			Label over = new Label("WELCOME TO VELO");
			over.getStyleClass().add("t-overline");
			Label title = new Label("Create your first profile");
			title.getStyleClass().add("t-display");
			title.setWrapText(true);
			Label sub = new Label("A profile is a Minecraft version with its own mods, packs and settings.");
			sub.getStyleClass().add("t-body");
			sub.setWrapText(true);
			Button create = new Button("New profile");
			create.setGraphic(net.veloclient.launcher.ui.Icons.icon("plus", 16, Color.WHITE));
			create.getStyleClass().addAll("primary-button", "button-lg");
			create.setOnAction(e -> createNewProfile());
			Button importButton = new Button("Switch from another client");
			importButton.setGraphic(net.veloclient.launcher.ui.Icons.icon("swap", 16, Color.web("#C9C9D1")));
			importButton.getStyleClass().add("button-lg");
			importButton.setOnAction(e -> showClientImport());
			text.getChildren().addAll(over, title, sub, new HBox(10, create, importButton));
		} else {
			Instance instance = target.instance();
			String serverName = homeTargetServerName(target);
			boolean running = RunningInstanceManager.running().stream().anyMatch(r -> r.instance().id().equals(instance.id()));
			Label over = new Label(running ? "RUNNING NOW" : QuickLaunchStore.loadAll().isEmpty() ? "READY TO PLAY" : "CONTINUE PLAYING");
			over.getStyleClass().add("t-overline");
			Node icon = buildSidebarIcon(instance, target.serverAddress(), 68);
			StackPane iconBox = new StackPane(icon);
			iconBox.setStyle("-fx-background-radius: 16; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 16;");
			Label name = new Label(serverName != null ? serverName : instance.name());
			name.getStyleClass().add("t-display");
			name.setStyle("-fx-font-size: 40px;");
			name.setMinWidth(0);
			Label meta = new Label(homeDetailsLine(instance, serverName) + playedLine(instance));
			meta.getStyleClass().add("t-body");
			VBox nameBox = new VBox(4, name, meta);
			nameBox.setMinWidth(0);
			HBox titleRow = new HBox(16, iconBox, nameBox);
			titleRow.setAlignment(Pos.CENTER_LEFT);

			HBox pills = new HBox(8);
			pills.setAlignment(Pos.CENTER_LEFT);
			if (target.isServer()) {
				Label online = new Label(target.serverAddress());
				online.getStyleClass().addAll("pill", "pill-grey");
				Region dot = new Region();
				dot.getStyleClass().add("status-dot");
				dot.setStyle("-fx-background-color: -velo-text-3;");
				online.setGraphic(dot);
				pills.getChildren().add(online);
				pingHomePill(target.serverAddress(), online, dot);
			}
			Label version = new Label("Minecraft " + instance.mcVersion());
			version.getStyleClass().addAll("pill", "pill-grey");
			pills.getChildren().add(version);
			Node party = homePartyPill();
			if (party != null) {
				pills.getChildren().add(party);
			}

			Button mods = new Button("Mods & packs");
			mods.setGraphic(net.veloclient.launcher.ui.Icons.icon("box", 16, Color.web("#E4E4E8")));
			mods.getStyleClass().add("glass-button");
			mods.setOnAction(e -> showInstanceDetail(instance));
			Button folder = new Button("Open folder");
			folder.setGraphic(net.veloclient.launcher.ui.Icons.icon("folder", 16, Color.web("#E4E4E8")));
			folder.getStyleClass().add("glass-button");
			folder.setOnAction(e -> openFolder(InstancePaths.gameDir(instance.id())));
			Button more = profileMenu(instance);
			HBox actions = new HBox(10, mods, folder, more);
			actions.setAlignment(Pos.CENTER_LEFT);
			text.getChildren().addAll(over, titleRow, pills, actions);
		}
		hero.getChildren().add(text);
		return hero;
	}

	private String playedLine(Instance instance) {
		Long millis = net.veloclient.launcher.data.PlayTimeStore.get().millisByProfile.get(instance.id());
		if (millis == null || millis < 60_000) {
			return "";
		}
		long hours = millis / 3_600_000;
		return "  ·  " + (hours > 0 ? hours + " h played" : (millis / 60_000) + " min played");
	}

	/** Pings the hero's server in the background and fills in "1,204 online" with a green dot. */
	private void pingHomePill(String address, Label pill, Region dot) {
		String host = parseHost(address);
		int port = parsePort(address);
		CompletableFuture.supplyAsync(() -> {
			try {
				return net.veloclient.launcher.net.ServerPinger.ping(host, port);
			} catch (Exception e) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result == null) {
				pill.setText(address + "  ·  offline");
				return;
			}
			dot.setStyle("");
			pill.getStyleClass().remove("pill-grey");
			pill.getStyleClass().add("pill-green");
			pill.setText(address + "  ·  " + java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(result.onlinePlayers()) + " online");
		}));
	}

	/** "Party · 2" pill on Home while you're in a party; opens the party. */
	private Node homePartyPill() {
		com.google.gson.JsonObject party = net.veloclient.launcher.social.LauncherSocial.party();
		if (party == null) {
			return null;
		}
		int count = party.getAsJsonArray("members").size();
		boolean lead = net.veloclient.launcher.social.LauncherSocial.isPartyLeader();
		Button pill = new Button("Party · " + count + (lead ? " · you lead" : ""));
		pill.getStyleClass().addAll("pill", "pill-blue");
		pill.setStyle("-fx-border-width: 0; -fx-cursor: hand;");
		pill.setOnAction(e -> showParty());
		return pill;
	}

	/** Right of the hero: the newest post as a big card, then two smaller ones. */
	private VBox buildHomeWhatsNew() {
		VBox column = new VBox(12);
		column.setMinWidth(340);
		column.setPrefWidth(380);
		column.setMaxWidth(400);
		Label title = new Label("What's new");
		title.getStyleClass().add("t-heading");
		Label all = new Label("All news");
		all.getStyleClass().add("t-link");
		all.setOnMouseClicked(e -> showNews());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(title, spacer, all);
		header.setAlignment(Pos.BASELINE_LEFT);
		column.getChildren().add(header);
		List<com.google.gson.JsonObject> posts = newsFeed == null ? List.of() : net.veloclient.launcher.ui.NewsView.bannerPosts(newsFeed);
		if (posts.isEmpty()) {
			Label none = new Label("News shows up here once you're online.");
			none.getStyleClass().add("t-muted");
			column.getChildren().add(none);
			return column;
		}
		Node lead = net.veloclient.launcher.ui.NewsView.homeLeadCard(posts.get(0), this::showNewsPost);
		VBox.setVgrow(lead, Priority.ALWAYS);
		column.getChildren().add(lead);
		VBox rows = new VBox();
		rows.getStyleClass().add("card");
		for (int i = 1; i < Math.min(3, posts.size()); i++) {
			rows.getChildren().add(net.veloclient.launcher.ui.NewsView.homeRow(posts.get(i), this::showNewsPost));
		}
		if (!rows.getChildren().isEmpty()) {
			column.getChildren().add(rows);
		}
		return column;
	}

	/** "Jump back in": the most recent launch targets as tiles (click picks it for Play, the round button plays it). */
	private VBox buildHomeRecent(List<HomeLaunchTarget> targets) {
		Label title = new Label("Jump back in");
		title.getStyleClass().add("t-heading");
		Label library = new Label("Library");
		library.getStyleClass().add("t-link");
		library.setOnMouseClicked(e -> showLibrary());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(title, spacer, library);
		header.setAlignment(Pos.BASELINE_LEFT);

		javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
		grid.setHgap(16);
		int columns = 5;
		for (int c = 0; c < columns; c++) {
			javafx.scene.layout.ColumnConstraints constraint = new javafx.scene.layout.ColumnConstraints();
			constraint.setPercentWidth(100.0 / columns);
			constraint.setHgrow(Priority.ALWAYS);
			grid.getColumnConstraints().add(constraint);
		}
		int shown = Math.min(columns - 1, targets.size());
		for (int i = 0; i < shown; i++) {
			grid.add(homeTargetTile(targets.get(i), i), i, 0);
		}
		VBox add = new VBox(8);
		add.getStyleClass().add("dashed-tile");
		add.setAlignment(Pos.CENTER);
		add.setPrefHeight(150);
		StackPane plus = new StackPane(net.veloclient.launcher.ui.Icons.icon("plus", 18, Color.web("#C9C9D1")));
		plus.setStyle("-fx-background-color: -velo-surface-2; -fx-background-radius: 99;");
		plus.setMinSize(38, 38);
		plus.setMaxSize(38, 38);
		Label addTitle = new Label("New profile");
		addTitle.getStyleClass().add("t-strong");
		Label addSub = new Label("or import from Lunar, Modrinth...");
		addSub.getStyleClass().add("t-muted");
		add.getChildren().addAll(plus, addTitle, addSub);
		add.setOnMouseClicked(e -> createNewProfile());
		grid.add(add, shown, 0);
		return new VBox(12, header, grid);
	}

	private StackPane homeTargetTile(HomeLaunchTarget target, int index) {
		Instance instance = target.instance();
		String serverName = homeTargetServerName(target);
		Node icon = buildSidebarIcon(instance, target.serverAddress(), 40);

		StackPane art = new StackPane();
		art.setPrefHeight(86);
		art.setMinHeight(86);
		Color tint = net.veloclient.launcher.ui.IconColorExtractor.fromNode(icon, accentColor());
		art.setStyle("-fx-background-color: linear-gradient(to bottom right, " + webColor(tint.deriveColor(0, 1, 0.55, 0.9)) + ", "
				+ webColor(tint.deriveColor(0, 1, 0.18, 0.9)) + "); -fx-background-radius: 14 14 0 0;");
		Node bigIcon = buildSidebarIcon(instance, target.serverAddress(), 54);
		bigIcon.setOpacity(0.9);
		art.getChildren().add(bigIcon);
		StackPane play = new StackPane(net.veloclient.launcher.ui.Icons.icon("play", 15, Color.WHITE));
		play.getStyleClass().add("tile-play");
		play.setOpacity(0);
		play.setScaleX(0.85);
		play.setScaleY(0.85);
		StackPane.setAlignment(play, Pos.BOTTOM_RIGHT);
		StackPane.setMargin(play, new Insets(0, 10, -21, 0));
		art.getChildren().add(play);
		play.setOnMouseClicked(e -> {
			e.consume();
			homeProfileIndex = index;
			refreshLaunchBar();
			playCurrent(launchBar.playButton(), launchBar.progress(), launchBar.statusLabel());
		});

		Label name = new Label(serverName != null ? serverName : instance.name());
		name.getStyleClass().add("t-strong");
		name.setStyle("-fx-font-size: 14px;");
		Label meta = new Label(serverName != null ? "via " + instance.name() : "Fabric " + instance.mcVersion());
		meta.getStyleClass().add("t-muted");
		VBox text = new VBox(2, name, meta);
		text.setPadding(new Insets(12, 14, 14, 14));
		VBox body = new VBox(art, text);
		StackPane tile = new StackPane(body);
		tile.getStyleClass().addAll("tile", "motion-card");
		if (index == homeProfileIndex) {
			tile.setStyle("-fx-border-color: -velo-accent-line;");
		}
		tile.setOnMouseEntered(e -> fadeTo(play, 1));
		tile.setOnMouseExited(e -> fadeTo(play, 0));
		tile.setOnMouseClicked(e -> {
			if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
				selectTarget(index);
			}
		});
		Tooltip.install(tile, new Tooltip("Pick " + name.getText() + " for Play"));
		ContextMenu menu = new ContextMenu();
		menu.getItems().addAll(profileMenuItems(instance));
		tile.setOnContextMenuRequested(e -> menu.show(tile, e.getScreenX(), e.getScreenY()));
		return tile;
	}

	private static void fadeTo(Node node, double opacity) {
		javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(Duration.millis(160), node);
		fade.setToValue(opacity);
		javafx.animation.ScaleTransition scale = new javafx.animation.ScaleTransition(Duration.millis(200), node);
		scale.setToX(opacity > 0 ? 1 : 0.85);
		scale.setToY(opacity > 0 ? 1 : 0.85);
		scale.setInterpolator(net.veloclient.launcher.ui.UiMotion.EASE_OUT);
		fade.play();
		scale.play();
	}

	private static String webColor(Color c) {
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.2f)", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255), c.getOpacity());
	}

	private void openFolder(java.nio.file.Path folder) {
		try {
			Files.createDirectories(folder);
			String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
			String[] command = os.contains("win") ? new String[] {"explorer", folder.toString()}
					: os.contains("mac") ? new String[] {"open", folder.toString()} : new String[] {"xdg-open", folder.toString()};
			new ProcessBuilder(command).start();
		} catch (java.io.IOException e) {
			LauncherLog.warn("Couldn't open " + folder, e);
		}
	}

	/** The signed-in account's 3D skin with cosmetics, loaded off the UI thread into {@link #homePlayerHolder}. */
	private void loadHomePlayerModel() {
		if (session == null) {
			return;
		}
		if (homePlayerHolder != null && homePlayerHolder.getChildren().isEmpty()) {
			homePlayerHolder.getChildren().setAll(net.veloclient.launcher.ui.CosmeticUi.spinner(30));
		}
		StackPane holder = homePlayerHolder;
		CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(session), Executors.newVirtualThreadPerTaskExecutor())
				.exceptionally(e -> null)
				.thenAccept(skin -> Platform.runLater(() -> {
					if (skin == null) {
						holder.getChildren().clear();
						return;
					}
					net.veloclient.launcher.ui.DressedPlayer.build(skin, false, 20, viewer -> {
						if (viewer == null) {
							holder.getChildren().clear();
							return;
						}
						viewer.setOpacity(0);
						holder.getChildren().setAll(viewer);
						javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(Duration.millis(260), viewer);
						fade.setToValue(1);
						fade.play();
					});
				}));
	}

	/** A rasterized image of {@code instance}'s icon - a custom icon loads directly, a built-in (vector-drawn) one is snapshotted. */
	private Image homeProfileIconImage(Instance instance) {
		if (instance.icon().kind() == InstanceIcon.Kind.CUSTOM) {
			var iconFile = InstancePaths.iconFile(instance.id());
			if (Files.exists(iconFile)) {
				return new Image(iconFile.toUri().toString(), 480, 480, true, false);
			}
		}
		Node rendered = renderInstanceIcon(instance, 480);
		rendered.applyCss();
		if (rendered instanceof javafx.scene.Parent parent) {
			parent.layout();
		}
		javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
		params.setFill(Color.TRANSPARENT);
		return rendered.snapshot(params, null);
	}

	/** "Fabric 26.2 · 12 mods" (+ "via <profile>" for a server shortcut). */
	private String homeDetailsLine(Instance instance, String serverName) {
		long mods = 0;
		try (var stream = java.nio.file.Files.list(net.veloclient.launcher.instance.InstancePaths.modsDir(instance.id()))) {
			mods = stream.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".jar") && !n.startsWith("velo-client-") && !n.startsWith("fabric-api-")).count();
		} catch (java.io.IOException ignored) {
			// No mods folder yet.
		}
		String line = "Fabric " + instance.mcVersion() + "  ·  " + (mods == 1 ? "1 mod" : mods + " mods");
		return serverName != null ? line + "  ·  via " + instance.name() : line;
	}

	/** Every less frequent profile action, for "⋯" buttons and right-click menus. */
	private List<MenuItem> profileMenuItems(Instance instance) {
		MenuItem edit = new MenuItem("Rename & change icon");
		edit.setOnAction(e -> editInstance(instance));
		MenuItem memory = new MenuItem("Memory & Java settings");
		memory.setOnAction(e -> InstanceSettingsDialog.show(stage, instance).ifPresent(updated -> {
			InstanceStore.save(updated);
			currentPage.run();
		}));
		MenuItem duplicate = new MenuItem("Duplicate profile");
		duplicate.setOnAction(e -> duplicateInstance(instance));
		MenuItem export = new MenuItem("Export as .zip");
		export.setOnAction(e -> exportInstance(instance));
		MenuItem delete = new MenuItem("Delete profile");
		delete.getStyleClass().add("menu-item-danger");
		delete.setOnAction(e -> confirmDeleteInstance(instance));
		return List.of(edit, memory, new SeparatorMenuItem(), duplicate, export, new SeparatorMenuItem(), delete);
	}

	private Button profileMenu(Instance instance) {
		Button more = new Button();
		more.setGraphic(net.veloclient.launcher.ui.Icons.icon("more", 18, Color.web("#E4E4E8"), 3));
		more.getStyleClass().addAll("glass-button", "icon-button");
		more.setTooltip(new Tooltip("More profile options"));
		ContextMenu menu = new ContextMenu();
		menu.getItems().addAll(profileMenuItems(instance));
		more.setOnAction(e -> menu.show(more, javafx.geometry.Side.BOTTOM, 0, 6));
		return more;
	}

	/** The account pill in the top bar: name and head (initial until the skin loads). */
	private void refreshAccountBadge() {
		if (topBar == null) {
			return;
		}
		MinecraftSession shown = session;
		if (shown == null) {
			StackPane icon = new StackPane(net.veloclient.launcher.ui.Icons.icon("user", 16, Color.WHITE));
			icon.setStyle("-fx-background-color: -velo-accent;");
			topBar.setAccount("Sign in", icon);
			return;
		}
		topBar.setAccount(shown.username(), net.veloclient.launcher.ui.Heads.head(shown.uuid(), shown.username(), 28));
		checkAccountSkin();
	}

	/**
	 * Asks Mojang for the account's current skin (it may have changed in-game, in the Locker or on
	 * minecraft.net); a different one replaces the cached head and redraws the badge via
	 * SkinHeads.onChange. At most every 20 s - it runs on every redraw, window focus and game exit.
	 */
	private void checkAccountSkin() {
		MinecraftSession shown = session;
		if (shown == null || shown.uuid() == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = skinCheckedAt.get(shown.uuid());
		if (last != null && now - last < 20_000) {
			return;
		}
		skinCheckedAt.put(shown.uuid(), now);
		CompletableFuture.runAsync(() -> SkinFetcher.fetch(shown), Executors.newVirtualThreadPerTaskExecutor());
	}

	private final java.util.Map<String, Long> skinCheckedAt = new java.util.concurrent.ConcurrentHashMap<>();

	/** The last page shown, so an account switch can redraw it with the new account. */
	private Runnable currentPage = this::showHome;

	/** Re-shows the open page after the account changed (home model, profile, cosmetics, store...). */
	private void refreshForAccountChange() {
		refreshAccountBadge();
		refreshLaunchBar();
		refreshCoins();
		Runnable page = currentPage;
		if (page != null) {
			page.run();
		}
	}

	private void showAccountProfile() {
		currentPage = this::showAccountProfile;
		setContent((Node) AccountProfileView.build(new AccountProfileView.Host() {
			@Override
			public Stage owner() {
				return stage;
			}

			@Override
			public void reload() {
				// The sidebar head and the Home model show the new skin too.
				refreshAccountBadge();
				showAccountProfile();
			}

			@Override
			public void withFreshSession(java.util.function.Consumer<MinecraftSession> action) {
				requireSignedIn(action);
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

			@Override
			public void openLocker() {
				showSkins();
			}

			@Override
			public void openReferrals() {
				showReferrals();
			}
		}));
		setActivePage("");
	}

	private void showServers() {
		currentPage = this::showServers;
		setActivePage("servers");
		Label title = new Label("Servers");
		title.getStyleClass().add("page-title");
		List<SavedServer> servers = SavedServerStore.loadAll();
		Label sub = new Label(servers.size() + (servers.size() == 1 ? " saved server" : " saved servers") + " with live status");
		sub.getStyleClass().add("page-subtitle");
		VBox heading = new VBox(4, title, sub);
		TextField search = new TextField();
		search.setPromptText("Search servers");
		search.setPrefWidth(220);
		Button refreshAll = new Button();
		refreshAll.setGraphic(net.veloclient.launcher.ui.Icons.icon("refresh", 16, Color.web("#E4E4E8")));
		refreshAll.getStyleClass().add("icon-button");
		refreshAll.setTooltip(new Tooltip("Refresh all"));
		Button addButton = new Button("Add server");
		addButton.setGraphic(net.veloclient.launcher.ui.Icons.icon("plus", 16, Color.WHITE, 2.2));
		addButton.getStyleClass().add("primary-button");
		addButton.setOnAction(e -> ServerEditDialog.show(stage, "Add Server", "", "", 25565, InstanceStore.loadAll(), null)
				.ifPresent(result -> {
					SavedServerStore.add(result.name(), result.host(), result.port(), result.instanceId());
					showServers();
				}));
		Region toolbarSpacer = new Region();
		HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
		HBox header = new HBox(10, heading, toolbarSpacer, search, refreshAll, addButton);
		header.setAlignment(Pos.BOTTOM_LEFT);

		VBox list = new VBox(10);
		List<Runnable> refreshers = new ArrayList<>();
		List<Node[]> rows = new ArrayList<>();
		if (servers.isEmpty()) {
			VBox empty = new VBox(10);
			empty.getStyleClass().add("dashed-tile");
			empty.setAlignment(Pos.CENTER);
			empty.setPadding(new Insets(48));
			Label emptyTitle = new Label("No servers yet");
			emptyTitle.getStyleClass().add("t-heading");
			Label hint = new Label("Add the servers you play on - you'll see who's online and can launch straight into them.");
			hint.getStyleClass().add("t-muted");
			empty.getChildren().addAll(emptyTitle, hint);
			empty.setOnMouseClicked(e -> addButton.fire());
			list.getChildren().add(empty);
		}
		// Discover first: partner servers and worlds hosted with Velo.
		list.getChildren().add(0, net.veloclient.launcher.ui.DiscoverView.build(discoverJoiner()));
		for (SavedServer server : servers) {
			Node row = buildServerRow(server, refreshers);
			rows.add(new Node[] {row});
			row.setUserData((server.name() + " " + server.address()).toLowerCase(java.util.Locale.ROOT));
			list.getChildren().add(row);
		}
		search.textProperty().addListener((obs, was, now) -> {
			String q = now.trim().toLowerCase(java.util.Locale.ROOT);
			for (Node[] r : rows) {
				boolean show = q.isEmpty() || String.valueOf(r[0].getUserData()).contains(q);
				r[0].setVisible(show);
				r[0].setManaged(show);
			}
		});
		refreshAll.setOnAction(e -> refreshers.forEach(Runnable::run));
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		HBox.setHgrow(scroll, Priority.ALWAYS);
		VBox aside = serversAside();
		HBox body = new HBox(24, scroll, aside);
		body.widthProperty().addListener((o, a, w) -> {
			boolean wide = w.doubleValue() > 1000;
			aside.setVisible(wide);
			aside.setManaged(wide);
		});
		VBox.setVgrow(body, Priority.ALWAYS);
		VBox page = new VBox(22, header, body);
		page.getStyleClass().add("page");
		setContent(page);
	}

	/** Right of the server list: which friends are playing where (with Join), and how Quick Play works. */
	private VBox serversAside() {
		VBox friends = new VBox(12);
		friends.getStyleClass().add("card");
		friends.setPadding(new Insets(18));
		Label title = new Label("Friends playing");
		title.getStyleClass().add("t-heading");
		friends.getChildren().add(title);
		var state = net.veloclient.launcher.social.LauncherSocial.snapshot();
		List<net.veloclient.launcher.social.LauncherSocial.Friend> online = state == null || state.friends == null ? List.of()
				: state.friends.stream().filter(f -> f.online).toList();
		for (var friend : online.stream().limit(6).toList()) {
			Label name = new Label(friend.username);
			name.getStyleClass().add("t-strong");
			String what = friend.activity != null && friend.activity.detail != null && !friend.activity.detail.isBlank() ? friend.activity.detail : "Online";
			Label activity = new Label(what);
			activity.setStyle("-fx-text-fill: -velo-green; -fx-font-size: 12px;");
			activity.setMaxWidth(170);
			VBox text = new VBox(2, name, activity);
			HBox.setHgrow(text, Priority.ALWAYS);
			HBox row = new HBox(12, net.veloclient.launcher.ui.Heads.head(friend.uuid, friend.username, 34), text);
			row.setAlignment(Pos.CENTER_LEFT);
			friends.getChildren().add(row);
		}
		if (online.isEmpty()) {
			Label none = new Label(session == null ? "Sign in to see your friends." : "No friends online right now.");
			none.getStyleClass().add("t-muted");
			friends.getChildren().add(none);
		}
		Button open = new Button("Open Friends");
		open.getStyleClass().addAll("glass-button", "button-sm");
		open.setOnAction(e -> showFriends());
		friends.getChildren().add(open);

		Label tipTitle = new Label("Quick Play");
		tipTitle.getStyleClass().add("t-strong");
		Label tip = new Label("Pick which profile each server launches with. The Play button's arrow joins any saved server directly.");
		tip.getStyleClass().add("t-muted");
		tip.setWrapText(true);
		VBox tipCard = new VBox(6, tipTitle, tip);
		tipCard.getStyleClass().add("card");
		tipCard.setPadding(new Insets(16));
		VBox aside = new VBox(16, friends, tipCard);
		aside.setMinWidth(300);
		aside.setPrefWidth(320);
		aside.setMaxWidth(340);
		return aside;
	}

	private Node buildServerRow(SavedServer server, List<Runnable> refreshers) {
		StackPane serverIconHolder = new StackPane();
		serverIconHolder.setPrefSize(52, 52);
		serverIconHolder.setMinSize(52, 52);
		serverIconHolder.getStyleClass().add("server-icon");
		ImageView serverIconView = new ImageView(fallbackServerIcon());
		serverIconView.setFitWidth(44);
		serverIconView.setFitHeight(44);
		serverIconView.setPreserveRatio(true);
		serverIconHolder.getChildren().add(serverIconView);

		Label name = new Label(server.name());
		name.getStyleClass().add("server-name");
		Label address = new Label(server.address());
		address.getStyleClass().add("server-address");
		HBox titleRow = new HBox(8, name, address);
		titleRow.setAlignment(Pos.BASELINE_LEFT);
		javafx.scene.text.TextFlow motd = new javafx.scene.text.TextFlow(pingingText());
		motd.setMaxHeight(36);
		VBox info = new VBox(4, titleRow, motd);
		HBox.setHgrow(info, Priority.ALWAYS);
		info.setMinWidth(160);

		// Status chips: online + players, ping, version.
		Label players = new Label("...");
		players.getStyleClass().addAll("server-chip");
		Label ping = new Label("");
		ping.getStyleClass().add("server-chip");
		Label version = new Label("");
		version.getStyleClass().add("server-chip");
		HBox chips = new HBox(6, players, ping, version);
		chips.setAlignment(Pos.CENTER_LEFT);
		info.getChildren().add(chips);

		List<Instance> instances = InstanceStore.loadAll();
		ComboBox<Instance> profilePicker = new ComboBox<>();
		profilePicker.getItems().add(null);
		profilePicker.getItems().addAll(instances);
		profilePicker.setPrefWidth(170);
		profilePicker.setConverter(new javafx.util.StringConverter<>() {
			@Override
			public String toString(Instance instance) {
				return instance == null ? "Choose a profile" : instance.name();
			}

			@Override
			public Instance fromString(String string) {
				return null;
			}
		});
		instances.stream().filter(i -> i.id().equals(server.instanceId())).findFirst()
				.ifPresentOrElse(profilePicker::setValue, () -> profilePicker.setValue(null));
		Label launchWith = new Label("Launches with");
		launchWith.getStyleClass().add("settings-row-hint");
		VBox profileBox = new VBox(3, launchWith, profilePicker);

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
		connect.getStyleClass().add("server-join");
		connect.setPrefWidth(110);
		Runnable refreshConnectButton = () -> {
			Instance selected = profilePicker.getValue();
			if (selected == null) {
				connect.setDisable(true);
				connect.setText("Connect");
				connect.setTooltip(new Tooltip("Choose which profile to launch with first."));
			} else if (session == null) {
				connect.setDisable(false);
				connect.setText("Sign in");
				connect.setTooltip(new Tooltip("Sign in with your Microsoft account first."));
			} else {
				connect.setDisable(false);
				connect.setText("Connect");
				connect.setTooltip(new Tooltip("Launch " + selected.name() + " straight into " + server.name()));
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

		Runnable refresh = () -> {
			motd.getChildren().setAll(pingingText());
			pingInto(server, motd, players, ping, version, serverIconView);
		};
		refreshers.add(refresh);

		Button more = new Button();
		more.setGraphic(net.veloclient.launcher.ui.Icons.icon("more", 18, Color.web("#C9C9D1"), 3));
		more.getStyleClass().addAll("icon-button", "icon-button-flat");
		ContextMenu menu = new ContextMenu();
		MenuItem editItem = new MenuItem("Edit server...");
		editItem.setOnAction(e -> ServerEditDialog.show(stage, "Edit Server", server.name(), server.host(), server.port(),
				InstanceStore.loadAll(), server.instanceId())
				.ifPresent(result -> {
					SavedServerStore.update(new SavedServer(server.id(), result.name(), result.host(), result.port(), result.instanceId()));
					showServers();
				}));
		MenuItem copyItem = new MenuItem("Copy address");
		copyItem.setOnAction(e -> {
			javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
			content.putString(server.address());
			javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
		});
		MenuItem refreshItem = new MenuItem("Refresh status");
		refreshItem.setOnAction(e -> refresh.run());
		MenuItem removeItem = new MenuItem("Remove");
		removeItem.setOnAction(e -> {
			javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
			alert.initOwner(stage);
			alert.setTitle("Remove server?");
			alert.setHeaderText("Remove \"" + server.name() + "\"?");
			alert.setContentText("It's only removed from this list - you can add it again any time.");
			net.veloclient.launcher.ui.DialogStyling.apply(alert);
			if (alert.showAndWait().filter(b -> b == javafx.scene.control.ButtonType.OK).isPresent()) {
				SavedServerStore.remove(server);
				showServers();
			}
		});
		menu.getItems().addAll(editItem, copyItem, refreshItem, new javafx.scene.control.SeparatorMenuItem(), removeItem);
		more.setOnAction(e -> menu.show(more, javafx.geometry.Side.BOTTOM, 0, 4));

		HBox actions = new HBox(10, profileBox, connect, more);
		actions.setAlignment(Pos.CENTER_RIGHT);
		HBox row = new HBox(14, serverIconHolder, info, actions);
		row.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(8, row, progressBar, launchStatus);
		card.getStyleClass().addAll("server-card", "motion-card");
		refresh.run();
		return card;
	}

	private void pingInto(SavedServer server, javafx.scene.text.TextFlow motd, Label players, Label ping, Label version, ImageView iconView) {
		players.setText("Checking...");
		players.getStyleClass().removeAll("server-online", "server-offline");
		ping.setVisible(false);
		ping.setManaged(false);
		version.setVisible(false);
		version.setManaged(false);
		CompletableFuture
				.supplyAsync(() -> {
					try {
						return ServerPinger.ping(server.host(), server.port());
					} catch (Exception e) {
						return e;
					}
				}, Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(result -> Platform.runLater(() -> {
					if (result instanceof ServerPinger.PingResult p) {
						boolean hasMotd = !net.veloclient.launcher.net.MotdText.plainText(p.motd()).isBlank();
						motd.getChildren().setAll(hasMotd ? motdTexts(p.motd()) : List.of());
						motd.setVisible(hasMotd);
						motd.setManaged(hasMotd);
						players.setText("●  " + String.format(java.util.Locale.ROOT, "%,d / %,d online", p.onlinePlayers(), p.maxPlayers()));
						players.getStyleClass().add("server-online");
						long ms = p.latencyMillis();
						ping.setText(ms + " ms");
						ping.getStyleClass().removeAll("server-ping-good", "server-ping-ok", "server-ping-bad");
						ping.getStyleClass().add(ms < 80 ? "server-ping-good" : ms < 160 ? "server-ping-ok" : "server-ping-bad");
						ping.setVisible(true);
						ping.setManaged(true);
						version.setText(p.versionName());
						version.setVisible(true);
						version.setManaged(true);
						applyServerFavicon(iconView, p.faviconPngBase64());
					} else {
						motd.getChildren().setAll(new javafx.scene.text.Text("Can't reach this server right now"));
						((javafx.scene.text.Text) motd.getChildren().get(0)).setFill(textColor());
						players.setText("●  Offline");
						players.getStyleClass().add("server-offline");
					}
				}));
	}

	/** The server list's per-row icon before a real favicon has (or ever) arrives - the app's own logo, same fallback style as installed-mod rows elsewhere in the launcher. */
	private Image fallbackServerIcon() {
		return net.veloclient.launcher.ui.UiImages.resource("/net/veloclient/launcher/images/logo.png", 36, 36);
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
			text.setFont(Font.font("Inter", weight, posture, 13));
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
						net.veloclient.launcher.social.LauncherSocial.setSession(this.session);
						refreshAccountBadge();
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
		net.veloclient.launcher.social.LauncherSocial.setSession(newSession);
		redeemPendingReferralSoon();
		// The sidebar's account badge is built once in buildSidebar() and
		// never touched by showHome() (which only rebuilds the title-screen
		// content) - always refreshing it here (not just in the "else"
		// branch that only ran when Home *wasn't* showing) fixes a real,
		// confirmed bug where signing in while already on Home left the
		// sidebar stuck on "Not signed in" even though session was set and
		// everything else (the account switcher dropdown, Home's own Play
		// button) correctly saw the new session.
		refreshForAccountChange();
	}

	/** Signs out of the current account only - switches straight into another saved account if one remains, otherwise drops back to "not signed in". */
	private void signOut() {
		if (session == null) {
			showHome();
			return;
		}
		Optional<MinecraftSession> next = AuthSession.remove(session.uuid());
		session = next.orElse(null);
		net.veloclient.launcher.social.LauncherSocial.setSession(session);
		showHome();
	}

	/** Switches to an already-saved account (from the account switcher dropdown) - no re-authentication needed unless its token has since expired. */
	private void switchAccount(MinecraftSession target) {
		AuthSession.switchTo(target.uuid());
		this.session = target;
		net.veloclient.launcher.social.LauncherSocial.setSession(target);
		refreshForAccountChange();
	}

	// ---- Friends ----

	private void showFriends() {
		currentPage = this::showFriends;
		Node friendsNode = (net.veloclient.launcher.ui.FriendsView.build(new net.veloclient.launcher.ui.FriendsView.Host() {
			@Override
			public LauncherTheme theme() {
				return theme;
			}

			@Override
			public MinecraftSession session() {
				return session;
			}

			@Override
			public void signIn() {
				beginSignIn();
			}

			@Override
			public void joinServer(String address, Button trigger, ProgressBar progress, Label status) {
				joinFriendServer(address, trigger, progress, status);
			}

			@Override
			public void badgeChanged() {
				refreshFriendsBadge();
			}
		}));
		// Friends and chat on the left, the party always next to them (it used to be a separate tab).
		Node party = net.veloclient.launcher.ui.PartyView.build(this::currentProfile);
		ScrollPane partyScroll = new ScrollPane(party);
		partyScroll.setFitToWidth(true);
		partyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		partyScroll.setMinWidth(320);
		partyScroll.setPrefWidth(360);
		partyScroll.setMaxWidth(380);
		Label partyTitle = new Label("Party");
		partyTitle.getStyleClass().add("t-title-sm");
		VBox partyColumn = new VBox(14, partyTitle, partyScroll);
		VBox.setVgrow(partyScroll, Priority.ALWAYS);
		partyColumn.setPadding(new Insets(0, 0, 0, 24));
		partyColumn.setStyle("-fx-border-color: transparent transparent transparent -velo-hairline; -fx-border-width: 0 0 0 1;");
		HBox.setHgrow(friendsNode, Priority.ALWAYS);
		HBox page = new HBox(24, friendsNode, partyColumn);
		page.getStyleClass().add("page");
		page.widthProperty().addListener((o, a, w) -> {
			boolean wide = w.doubleValue() > 980 || Boolean.TRUE.equals(friendsTabParty);
			partyColumn.setVisible(wide);
			partyColumn.setManaged(wide);
		});
		friendsTabParty = null;
		setContent(page);
		setActivePage("friends");
	}

	/** Dev-only: fills the Friends tab with sample data, saves a screenshot of the window and quits. */
	private void runFriendsDemo(java.nio.file.Path output) {
		var state = new net.veloclient.launcher.social.LauncherSocial.State();
		state.me = new net.veloclient.launcher.social.LauncherSocial.Me();
		state.me.uuid = session != null ? session.uuid() : "00000000000000000000000000000000";
		state.me.username = session != null ? session.username() : "You";
		String[][] rows = {
				{"069a79f444e94726a5befca90e38aaf5", "Notch", "server", "play.hypixel.net"},
				{"853c80ef3c3749fdaa49938b674adae6", "jeb_", "singleplayer", "Survival Island"},
				{"61699b2ed3274a019f1e0ea8c3f06bc6", "Dinnerbone", "launcher", null},
				{"e6b5c088068044df9e1b9bf11792291b", "Grumm", null, null}};
		java.util.List<net.veloclient.launcher.social.LauncherSocial.Friend> friends = new java.util.ArrayList<>();
		for (String[] row : rows) {
			var friend = new net.veloclient.launcher.social.LauncherSocial.Friend();
			friend.uuid = row[0];
			friend.username = row[1];
			friend.online = row[2] != null;
			if (row[2] != null) {
				friend.activity = new net.veloclient.launcher.social.LauncherSocial.Activity();
				friend.activity.kind = row[2];
				friend.activity.detail = row[3];
			}
			friend.lastSeen = System.currentTimeMillis() - 3 * 3600_000L;
			friend.unread = row[1].equals("jeb_") ? 2 : 0;
			friends.add(friend);
		}
		state.friends = friends;
		var request = new net.veloclient.launcher.social.LauncherSocial.Request();
		request.uuid = "7125ba8b1c864508b92bb5c042ccfe2b";
		request.username = "KrisJelbring";
		request.time = System.currentTimeMillis() - 300_000;
		state.incoming = java.util.List.of(request);
		long now = System.currentTimeMillis();
		java.util.List<net.veloclient.launcher.social.LauncherSocial.Message> chat = new java.util.ArrayList<>();
		String[][] lines = {{rows[0][0], "yo, you on tonight?"}, {state.me.uuid, "yeah, just finishing my base"},
				{rows[0][0], "nice - I'm in the bedwars queue on hypixel, join me when you're done"}};
		for (int i = 0; i < lines.length; i++) {
			var message = new net.veloclient.launcher.social.LauncherSocial.Message();
			message.id = i;
			message.from = lines[i][0];
			message.to = lines[i][0].equals(state.me.uuid) ? rows[0][0] : state.me.uuid;
			message.text = lines[i][1];
			message.time = now - (lines.length - i) * 120_000L;
			message.kind = "text";
			chat.add(message);
		}
		net.veloclient.launcher.social.LauncherSocial.loadDemo(state, java.util.Map.of(rows[0][0], chat));
		javafx.animation.PauseTransition open = new javafx.animation.PauseTransition(Duration.seconds(1));
		open.setOnFinished(e -> {
			if ("settings".equals(System.getProperty("velo.launcherDemoPage"))) {
				showSettings();
				javafx.animation.PauseTransition shoot = new javafx.animation.PauseTransition(Duration.seconds(2));
				shoot.setOnFinished(ev -> {
					try {
						var image = stage.getScene().snapshot(null);
						javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", output.toFile());
					} catch (Exception ex) {
						LauncherLog.warn("Settings demo screenshot failed", ex);
					}
					Platform.exit();
				});
				shoot.play();
				return;
			}
			showFriends();
			// Select the first friend by clicking their row, like a user would.
			// A ScrollPane's content only joins the scene graph after layout, so look the row up a beat later.
			javafx.animation.PauseTransition select = new javafx.animation.PauseTransition(Duration.millis(600));
			select.setOnFinished(ev -> root.lookupAll(".friend-row").stream().findFirst()
					.filter(row -> row.getOnMouseClicked() != null)
					.ifPresent(row -> row.getOnMouseClicked().handle(null)));
			select.play();
			javafx.animation.PauseTransition shoot = new javafx.animation.PauseTransition(Duration.seconds(4));
			shoot.setOnFinished(ev -> {
				try {
					var image = stage.getScene().snapshot(null);
					javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", output.toFile());
					LauncherLog.info("Friends demo screenshot saved to " + output);
				} catch (Exception ex) {
					LauncherLog.warn("Friends demo screenshot failed", ex);
				}
				Platform.exit();
			});
			shoot.play();
		});
		open.play();
	}

	/** "Join" on a friend: the most recently launched profile (else the newest one), straight into their server. */
	/**
	 * A world hosted with Velo: leave a note for the game ({@code config/pending-world-join.json}, it joins once signed
	 * in) and launch a profile of that Minecraft version.
	 */
	private net.veloclient.launcher.ui.DiscoverView.Joiner discoverJoiner() {
		return new net.veloclient.launcher.ui.DiscoverView.Joiner() {
			@Override
			public void server(String address, Button trigger) {
				joinFriendServer(address, trigger, new ProgressBar(), new Label());
			}

			@Override
			public void world(String worldId, String version, Button trigger) {
				joinHostedWorld(worldId, version, trigger);
			}
		};
	}

	private void joinHostedWorld(String worldId, String version, Button trigger) {
		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
		if (targets.isEmpty()) {
			showPlaceholderAlert("No profile yet", "Create a profile on the Home screen first.");
			return;
		}
		Instance instance = targets.stream().map(HomeLaunchTarget::instance).filter(i -> version.equals(i.mcVersion())).findFirst()
				.orElse(null);
		if (instance == null) {
			showPlaceholderAlert("Needs Minecraft " + version, "That world runs Minecraft " + version + " - make a " + version + " profile to join it.");
			return;
		}
		try {
			com.google.gson.JsonObject note = new com.google.gson.JsonObject();
			note.addProperty("world", worldId);
			note.addProperty("at", System.currentTimeMillis());
			java.nio.file.Files.createDirectories(net.veloclient.launcher.data.VeloPaths.config());
			java.nio.file.Files.writeString(net.veloclient.launcher.data.VeloPaths.config().resolve("pending-world-join.json"), note.toString());
		} catch (java.io.IOException e) {
			showPlaceholderAlert("Couldn't join", e.getMessage());
			return;
		}
		launchWithProgress(instance, null, trigger, new ProgressBar(), new Label());
	}

	private void joinFriendServer(String address, Button trigger, ProgressBar progress, Label status) {
		List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
		if (targets.isEmpty()) {
			showPlaceholderAlert("No profile yet", "Create a profile on the Home screen first - Join launches it straight into your friend's server.");
			return;
		}
		launchWithProgress(targets.get(0).instance(), address, trigger, progress, status);
	}

	// ---- News ----

	/** Last news feed (cached or fresh) - drives the Home banner and the sidebar's "new" badge. */
	private com.google.gson.JsonObject newsFeed;

	/** Loads the feed in the background (cached copy first), then updates the badge and, on Home, the banner. */
	private void refreshNews(boolean rebuildHome) {
		if (newsFeed == null) {
			newsFeed = net.veloclient.launcher.social.NewsApi.cachedFeed();
			updateNewsBadge();
		}
		CompletableFuture.runAsync(() -> {
			try {
				com.google.gson.JsonObject fresh = net.veloclient.launcher.social.NewsApi.feed();
				Platform.runLater(() -> {
					boolean changed = newsFeed == null || !newsFeed.equals(fresh);
					newsFeed = fresh;
					updateNewsBadge();
					if (changed && rebuildHome && "home".equals(activePage)) {
						showHome();
					}
				});
			} catch (net.veloclient.launcher.social.NewsApi.NewsError ignored) {
				// Offline - keep the cached copy.
			}
		});
	}

	private void updateNewsBadge() {
		if (topBar == null) {
			return;
		}
		topBar.setNewsCount(newsFeed == null ? 0 : net.veloclient.launcher.ui.NewsView.unread(newsFeed));
		refreshNotificationDot();
	}

	private void showNews() {
		currentPage = this::showNews;
		setContent(net.veloclient.launcher.ui.NewsView.build(newsHost()));
		setActivePage("news");
		// Opening News counts as seeing what's there now - the fresh feed confirms it once it arrives (host.seen()).
		if (newsFeed != null) {
			net.veloclient.launcher.ui.NewsView.markSeen(newsFeed);
		}
		updateNewsBadge();
	}

	private void showNewsPost(com.google.gson.JsonObject post) {
		currentPage = this::showNews;
		net.veloclient.launcher.ui.NewsView.markSeen(newsFeed, post);
		updateNewsBadge();
		setContent(net.veloclient.launcher.ui.NewsView.postPage(newsHost(), post, newsFeed));
		setActivePage("news");
	}

	private net.veloclient.launcher.ui.NewsView.Host newsHost() {
		return new net.veloclient.launcher.ui.NewsView.Host() {
			@Override
			public Stage owner() {
				return stage;
			}

			@Override
			public void show(javafx.scene.Node page) {
				setContent(page);
			}

			@Override
			public void openNews() {
				showNews();
			}

			@Override
			public boolean signedIn() {
				return net.veloclient.launcher.social.LauncherSocial.sessionToken() != null;
			}

			@Override
			public void seen(com.google.gson.JsonObject feed) {
				newsFeed = feed;
				updateNewsBadge();
			}
		};
	}

	/** Unread messages + pending requests on the top bar's Friends button, plus the launch bar's friends line. */
	private void refreshFriendsBadge() {
		if (topBar == null) {
			return;
		}
		topBar.setFriendsCount(net.veloclient.launcher.social.LauncherSocial.badgeCount());
		refreshNotificationDot();
		refreshLaunchBarSocial();
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
		currentPage = this::showCosmetics;
		setContent(CosmeticsView.build(stage, new CosmeticsView.Host() {
			@Override
			public LauncherTheme activeTheme() {
				return theme;
			}

			@Override
			public void rebuild() {
				showCosmetics();
			}

			@Override
			public MinecraftSession session() {
				return session;
			}

			@Override
			public void openStore() {
				showStore();
			}

			@Override
			public void withFreshSession(java.util.function.Consumer<MinecraftSession> action) {
				requireSignedIn(action);
			}
		}));
		setActivePage("locker");
		refreshWingsCatalog();
		refreshOwned();
	}

	/**
	 * Fetches what you own again (the owner may just have given you something) and shows the page
	 * again if it changed and you're still on it.
	 */
	private void refreshOwned() {
		Runnable page = currentPage;
		java.util.Set<String> before = net.veloclient.launcher.social.StoreApi.owned();
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				net.veloclient.launcher.social.StoreApi.wallet();
				net.veloclient.launcher.data.StorePurchase.restoreMissing();
			} catch (net.veloclient.launcher.social.StoreApi.StoreError ignored) {
				return;
			}
			if (!net.veloclient.launcher.social.StoreApi.owned().equals(before)) {
				// The catalog lists owned testing items too.
				net.veloclient.launcher.data.WingsCatalog.refresh();
				javafx.application.Platform.runLater(() -> {
					if (currentPage == page) {
						page.run();
					}
				});
			}
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
	}

	/**
	 * Fetches the server's wings catalog (new pairs, prices, what's for sale) in the background and
	 * shows the page again if it changed and you're still on it.
	 */
	private void refreshWingsCatalog() {
		Runnable page = currentPage;
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			if (net.veloclient.launcher.data.WingsCatalog.refresh()) {
				net.veloclient.launcher.ui.UiImages.forgetWings();
				javafx.application.Platform.runLater(() -> {
					if (currentPage == page) {
						page.run();
					}
				});
			}
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
	}

	// ---- Store ----

	private void showAuraDetail(net.veloclient.launcher.data.AuraCatalog.AuraType type) {
		setActivePage("store");
		currentPage = () -> showAuraDetail(type);
		setContent(net.veloclient.launcher.ui.AuraDetailView.build(new net.veloclient.launcher.ui.AuraDetailView.Host() {
			@Override
			public void openCoins() {
				showCoins();
			}

			@Override
			public MinecraftSession session() {
				return session;
			}

			@Override
			public void goBack() {
				showStore();
			}
		}, type));
		setActivePage("store");
	}

	private void showEmoteDetail(net.veloclient.launcher.data.EmoteCatalog.EmoteType type) {
		setActivePage("store");
		currentPage = () -> showEmoteDetail(type);
		setContent(net.veloclient.launcher.ui.EmoteDetailView.build(new net.veloclient.launcher.ui.EmoteDetailView.Host() {
			@Override
			public void openCoins() {
				showCoins();
			}

			@Override
			public MinecraftSession session() {
				return session;
			}

			@Override
			public void goBack() {
				showStore();
			}
		}, type));
		setActivePage("store");
	}

	private void showOwner() {
		currentPage = this::showOwner;
		setContent(net.veloclient.launcher.ui.OwnerView.build(this::showOwner));
		setActivePage("store");
	}

	private void showCoins() {
		currentPage = this::showCoins;
		setContent(net.veloclient.launcher.ui.CoinsView.build(new net.veloclient.launcher.ui.CoinsView.Links() {
			@Override
			public void openStore() {
				net.veloclient.launcher.ui.StoreView.showFeaturedTab();
				showStore();
			}

			@Override
			public void openReferrals() {
				showReferrals();
			}

			@Override
			public void openPlus() {
				showPlus();
			}
		}));
		setActivePage("store");
	}

	private void showStore() {
		currentPage = this::showStore;
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
			public void openCoins() {
				showCoins();
			}

			@Override
			public void openWings(net.veloclient.launcher.data.WingsCatalog.WingType type) {
				showWingDetail(type);
			}

			@Override
			public void openCosmetics() {
				showCosmetics();
			}

			@Override
			public void openOwner() {
				showOwner();
			}

			@Override
			public void openAura(net.veloclient.launcher.data.AuraCatalog.AuraType type) {
				showAuraDetail(type);
			}

			@Override
			public void openEmote(net.veloclient.launcher.data.EmoteCatalog.EmoteType type) {
				showEmoteDetail(type);
			}

			@Override
			public void rebuild() {
				showStore();
			}
		}));
		setActivePage("store");
		refreshWingsCatalog();
		// Server prices for the cards (shown on the next visit if they weren't loaded yet).
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				net.veloclient.launcher.social.StoreApi.catalog();
			} catch (net.veloclient.launcher.social.StoreApi.StoreError ignored) {
				// The bundled prices stay.
			}
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
	}

	private void showWingDetail(net.veloclient.launcher.data.WingsCatalog.WingType type) {
		setActivePage("store");
		setContent(net.veloclient.launcher.ui.WingDetailView.build(new net.veloclient.launcher.ui.WingDetailView.Host() {
			@Override
			public void openCoins() {
				showCoins();
			}

			@Override
			public MinecraftSession session() {
				return session;
			}

			@Override
			public void goBack() {
				showStore();
			}
		}, type));
	}

	private void showStoreItemDetail(net.veloclient.launcher.data.StoreItem item) {
		setActivePage("store");
		setContent(net.veloclient.launcher.ui.StoreItemDetailView.build(new net.veloclient.launcher.ui.StoreItemDetailView.Host() {
			@Override
			public void openCoins() {
				showCoins();
			}

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
		Image image = net.veloclient.launcher.ui.UiImages.resource("/net/veloclient/launcher/images/icons/action/" + iconName + ".png", 19, 19);
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
			Runnable back = "library".equals(activePage) ? this::showLibrary : this::showHome;
			String meta = playedLine(instance).replace("  ·  ", "");
			InstanceDetailView.Header header = new InstanceDetailView.Header(renderInstanceIcon(instance, 64), meta,
					() -> editInstance(instance), profileMenuItems(instance),
					() -> InstanceSettingsDialog.show(stage, instance).ifPresent(updated -> {
						InstanceStore.save(updated);
						refreshLaunchBar();
					}));
			currentPage = () -> showInstanceDetail(instance);
			focusLaunchTarget(instance);
			setContent(InstanceDetailView.build(stage, instance, theme, back, header));
			if (!"library".equals(activePage)) {
				setActivePage("library");
			}
		} catch (Exception e) {
			e.printStackTrace();
			showPlaceholderAlert("Couldn't open \"" + instance.name() + "\"",
					(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
							+ "\n\nThis profile's data may be from an older/incompatible launcher version.");
		}
	}

	/**
	 * First start with no profiles at all: creates a ready-to-play "Velo Client" profile on Minecraft 26.2 (with Velo Client and Fabric
	 * API installed), so a new player can press Play straight away. Only ever once - deleting every profile later doesn't bring it back.
	 */
	private boolean ensureDefaultProfile() {
		if (System.getProperty("velo.launcherTour") != null || System.getProperty("velo.launcherDemo") != null) {
			return false;
		}
		java.nio.file.Path marker = VeloPaths.config().resolve("default-profile-created");
		try {
			if (Files.exists(marker)) {
				return false;
			}
			if (InstanceStore.loadAll().isEmpty()) {
				Instance instance = InstanceStore.createNew("Velo Client", GameVersion.MC_26_2.id(), InstanceIcon.builtin(BuiltinIcons.DEFAULT));
				installModLoaderJars(instance);
				LauncherLog.info("Created the default profile on " + instance.mcVersion());
			}
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, java.time.Instant.now().toString());
			return true;
		} catch (Exception e) {
			LauncherLog.warn("Couldn't create the default profile", e);
			return false;
		}
	}

	private net.veloclient.launcher.ui.OnboardingView.Host onboardingHost() {
		return new net.veloclient.launcher.ui.OnboardingView.Host() {
			@Override
			public String accountName() {
				return session == null ? null : session.username();
			}

			@Override
			public void signIn(Runnable done) {
				SignInDialog.show(stage, MICROSOFT_CLIENT_ID, signedIn -> {
					onSignedIn(signedIn);
					done.run();
				}, error -> showPlaceholderAlert("Sign-in failed", error));
			}

			@Override
			public void openImport() {
				showClientImport();
			}
		};
	}

	/** A friend's code entered during setup before signing in: redeemed once the Velo server session is up. */
	private void redeemPendingReferralSoon() {
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			for (int attempt = 0; attempt < 15 && !net.veloclient.launcher.social.StoreApi.connected(); attempt++) {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					return;
				}
			}
			String error = net.veloclient.launcher.social.StoreApi.redeemPendingReferral();
			if (error != null) {
				Platform.runLater(() -> net.veloclient.launcher.ui.Toasts.show(layers, "Friend's code: " + error));
			}
		});
	}

	/** Very first start: the setup card (sign in, bring your stuff over from other launchers it finds, a friend's code). */
	private void offerClientImport() {
		java.util.concurrent.CompletableFuture.supplyAsync(net.veloclient.launcher.data.ClientImport::detect,
				java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()).thenAccept(sources -> Platform.runLater(() -> {
					net.veloclient.launcher.ui.OnboardingView.show(layers, onboardingHost(), sources);
				})).exceptionally(e -> {
					LauncherLog.warn("Couldn't look for other launchers", e);
					return null;
				});
	}

	/** "Switch from another client": the step-by-step import page. */
	private void showClientImport() {
		currentPage = this::showClientImport;
		setActivePage("library");
		setContent(net.veloclient.launcher.ui.ClientImportView.build(this::showHome, this::installModLoaderJars, this::showInstanceDetail));
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
				ImageView view = new ImageView(net.veloclient.launcher.ui.UiImages.file(iconFile, size, size));
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
		if (requiredUpdate != null && availableUpdate != null) {
			net.veloclient.launcher.ui.UpdateDialog.show(stage, availableUpdate, java.util.Objects.requireNonNullElse(requiredUpdate.message(), ""));
			return;
		}
		// Party leader with "launch together": everyone else's launcher follows.
		var partyNow = net.veloclient.launcher.social.LauncherSocial.party();
		if (partyNow != null && net.veloclient.launcher.social.LauncherSocial.isPartyLeader()
				&& net.veloclient.launcher.data.LauncherSettings.get().partyLaunchTogether && partyNow.getAsJsonArray("members").size() > 1) {
			net.veloclient.launcher.social.LauncherSocial.partyLaunch(quickPlayTarget, instance.mcVersion());
		}
		// Quick system check (memory, swap, disk, other games running) - only real problems interrupt.
		if (System.getProperty("velo.launcherTour") == null) {
			try {
				var warnings = net.veloclient.launcher.launch.SystemHealth.warnings(net.veloclient.launcher.launch.SystemHealth.check(instance));
				if (!warnings.isEmpty() && !net.veloclient.launcher.ui.SystemCheckDialog.confirmLaunch(stage, warnings)) {
					return;
				}
			} catch (RuntimeException e) {
				LauncherLog.warn("System check failed - launching anyway", e);
			}
		}
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
						refreshLaunchBar();
						if (net.veloclient.launcher.data.LauncherSettings.get().minimizeOnLaunch) {
							stage.setIconified(true);
						}
					});
					int exitCode = result.process().waitFor();
					Platform.runLater(this::checkAccountSkin);
					// A non-zero exit after a deliberate Stop is expected
					// (destroy()'d processes don't exit 0) - not a crash.
					if (exitCode != 0 && !running.wasStopped()) {
						Platform.runLater(() -> net.veloclient.launcher.ui.CrashReportDialog.show(stage, instance, exitCode, startedAt, result.logFile()));
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
						net.veloclient.launcher.social.LauncherSocial.setSession(session);
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

	private net.veloclient.launcher.party.PartyController partyController;

	/** The profile selected on Home (or the first one), for party sharing / syncing / launching. */
	private Instance currentProfile() {
		if (homeCurrentTarget != null) {
			return InstanceStore.loadAll().stream().filter(i -> i.id().equals(homeCurrentTarget.instance().id())).findFirst()
					.orElse(homeCurrentTarget.instance());
		}
		List<Instance> all = InstanceStore.loadAll();
		return all.isEmpty() ? null : all.get(0);
	}

	/** Friends page tab: null = pick automatically (Party while you're in one). */
	private Boolean friendsTabParty;

	private void showParty() {
		friendsTabParty = true;
		showFriends();
		setActivePage("friends");
	}

	/** Party changes show up in the launch bar's friends line and (on Home) the party pill. */
	private void refreshPartyStrip() {
		refreshLaunchBarSocial();
		if ("home".equals(activePage) && content.getScene() != null) {
			showHome();
		}
	}

	// ---- Library: every profile as a tile ----

	/** Library sort: 0 = recently played, 1 = name, 2 = newest. */
	private int librarySort = 0;
	/** Library filter: null = all, else a Minecraft version. */
	private String libraryFilter;

	private void showLibrary() {
		currentPage = this::showLibrary;
		setActivePage("library");
		List<Instance> all = new java.util.ArrayList<>(InstanceStore.loadAll());
		java.util.Map<String, Long> lastPlayed = new java.util.HashMap<>();
		for (QuickLaunchStore.Entry entry : QuickLaunchStore.loadAll()) {
			lastPlayed.merge(entry.instanceId(), entry.lastLaunchedEpochMillis(), Math::max);
		}
		switch (librarySort) {
			case 1 -> all.sort(Comparator.comparing(i -> i.name().toLowerCase(java.util.Locale.ROOT)));
			case 2 -> all.sort(Comparator.comparingLong(Instance::createdAtEpochMillis).reversed());
			default -> all.sort(Comparator.comparingLong((Instance i) -> lastPlayed.getOrDefault(i.id(), 0L))
					.thenComparingLong(Instance::createdAtEpochMillis).reversed());
		}
		long runningCount = RunningInstanceManager.running().size();

		Label title = new Label("Library");
		title.getStyleClass().add("page-title");
		Label sub = new Label(all.size() + (all.size() == 1 ? " profile" : " profiles") + (runningCount > 0 ? "  ·  " + runningCount + " running" : ""));
		sub.getStyleClass().add("page-subtitle");
		VBox heading = new VBox(4, title, sub);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		MenuButton importButton = new MenuButton("Import");
		importButton.setGraphic(net.veloclient.launcher.ui.Icons.icon("download", 16, Color.web("#E4E4E8")));
		MenuItem fromClient = new MenuItem("From another client (Lunar, Modrinth...)");
		fromClient.setOnAction(e -> showClientImport());
		MenuItem fromZip = new MenuItem("From a profile .zip");
		fromZip.setOnAction(e -> importInstance());
		importButton.getItems().addAll(fromClient, fromZip);
		Button create = new Button("New profile");
		create.setGraphic(net.veloclient.launcher.ui.Icons.icon("plus", 16, Color.WHITE, 2.2));
		create.getStyleClass().add("primary-button");
		create.setOnAction(e -> createNewProfile());
		HBox header = new HBox(10, heading, spacer, importButton, create);
		header.setAlignment(Pos.BOTTOM_LEFT);

		HBox filters = new HBox(8);
		filters.setAlignment(Pos.CENTER_LEFT);
		java.util.List<String> versions = all.stream().map(Instance::mcVersion).distinct().sorted(Comparator.reverseOrder()).toList();
		filters.getChildren().add(filterChip("All", libraryFilter == null, () -> {
			libraryFilter = null;
			showLibrary();
		}));
		for (String version : versions) {
			filters.getChildren().add(filterChip(version, version.equals(libraryFilter), () -> {
				libraryFilter = version;
				showLibrary();
			}));
		}
		Region filterSpacer = new Region();
		HBox.setHgrow(filterSpacer, Priority.ALWAYS);
		Label sortLabel = new Label("Sort");
		sortLabel.getStyleClass().add("t-muted");
		HBox sort = new HBox(2);
		sort.getStyleClass().add("segment-bar");
		String[] sorts = {"Recent", "Name", "Newest"};
		for (int i = 0; i < sorts.length; i++) {
			int index = i;
			Button b = new Button(sorts[i]);
			b.getStyleClass().setAll("button", "segmented-button");
			if (i == librarySort) {
				b.getStyleClass().add("segmented-button-active");
			}
			b.setOnAction(e -> {
				librarySort = index;
				showLibrary();
			});
			sort.getChildren().add(b);
		}
		filters.getChildren().addAll(filterSpacer, sortLabel, sort);

		javafx.scene.layout.FlowPane grid = new javafx.scene.layout.FlowPane(16, 16);
		for (Instance instance : all) {
			if (libraryFilter != null && !libraryFilter.equals(instance.mcVersion())) {
				continue;
			}
			grid.getChildren().add(libraryTile(instance, lastPlayed.get(instance.id())));
		}
		VBox add = new VBox(8);
		add.getStyleClass().add("dashed-tile");
		add.setAlignment(Pos.CENTER);
		add.setPrefSize(250, 178);
		StackPane plus = new StackPane(net.veloclient.launcher.ui.Icons.icon("plus", 18, Color.web("#C9C9D1")));
		plus.setStyle("-fx-background-color: -velo-surface-2; -fx-background-radius: 99;");
		plus.setMinSize(40, 40);
		plus.setMaxSize(40, 40);
		Label addLabel = new Label("New profile");
		addLabel.getStyleClass().add("t-strong");
		add.getChildren().addAll(plus, addLabel);
		add.setOnMouseClicked(e -> createNewProfile());
		grid.getChildren().add(add);
		// Tiles stretch to fill each row evenly instead of leaving a ragged gap on the right.
		grid.widthProperty().addListener((o, a, w) -> {
			double width = w.doubleValue();
			int columns = Math.max(1, (int) ((width + 16) / (240 + 16)));
			double tileWidth = Math.floor((width - 16 * (columns - 1)) / columns) - 0.5;
			for (Node tile : grid.getChildren()) {
				if (tile instanceof Region region) {
					region.setPrefWidth(tileWidth);
					region.setMinWidth(tileWidth);
					region.setMaxWidth(tileWidth);
				}
			}
		});
		ScrollPane scroll = new ScrollPane(grid);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox page = new VBox(20, header, filters, scroll);
		page.getStyleClass().add("page");
		setContent(page);
	}

	private Button filterChip(String text, boolean active, Runnable action) {
		Button chip = new Button(text);
		chip.getStyleClass().setAll("button", "fchip");
		if (active) {
			chip.getStyleClass().add("fchip-active");
		}
		chip.setOnAction(e -> action.run());
		return chip;
	}

	private Node libraryTile(Instance instance, Long lastPlayedMillis) {
		Node icon = renderInstanceIcon(instance, 44);
		Color tint = net.veloclient.launcher.ui.IconColorExtractor.fromNode(icon, accentColor());
		StackPane art = new StackPane();
		art.setMinHeight(116);
		art.setPrefHeight(116);
		art.setStyle("-fx-background-color: linear-gradient(to bottom right, " + webColor(tint.deriveColor(0, 1, 0.6, 0.95)) + ", "
				+ webColor(tint.deriveColor(0, 1, 0.16, 0.95)) + "); -fx-background-radius: 14 14 0 0;");
		Node watermark = renderInstanceIcon(instance, 120);
		watermark.setOpacity(0.18);
		watermark.setRotate(-12);
		StackPane.setAlignment(watermark, Pos.CENTER_RIGHT);
		StackPane.setMargin(watermark, new Insets(0, -18, 0, 0));
		art.getChildren().add(watermark);
		Rectangle artClip = new Rectangle();
		artClip.widthProperty().bind(art.widthProperty());
		artClip.heightProperty().bind(art.heightProperty());
		art.setClip(artClip);

		long running = RunningInstanceManager.countRunning(instance.id());
		if (running > 0) {
			Label badge = new Label("Running");
			badge.getStyleClass().addAll("pill", "pill-green");
			badge.setStyle("-fx-background-color: rgba(11,11,14,0.75);");
			Region dot = new Region();
			dot.getStyleClass().add("status-dot");
			badge.setGraphic(dot);
			StackPane.setAlignment(badge, Pos.TOP_LEFT);
			StackPane.setMargin(badge, new Insets(10));
			art.getChildren().add(badge);
		}
		StackPane play = new StackPane(net.veloclient.launcher.ui.Icons.icon("play", 15, Color.WHITE));
		play.getStyleClass().add("tile-play");
		play.setOpacity(0);
		StackPane.setAlignment(play, Pos.BOTTOM_RIGHT);
		StackPane.setMargin(play, new Insets(0, 12, 12, 0));
		art.getChildren().add(play);
		play.setOnMouseClicked(e -> {
			e.consume();
			List<HomeLaunchTarget> targets = orderedLaunchTargetsForHome();
			for (int i = 0; i < targets.size(); i++) {
				if (!targets.get(i).isServer() && targets.get(i).instance().id().equals(instance.id())) {
					homeProfileIndex = i;
					break;
				}
			}
			refreshLaunchBar();
			launchWithProgress(instance, null, launchBar.playButton(), launchBar.progress(), launchBar.statusLabel());
		});

		StackPane iconBox = new StackPane(icon);
		iconBox.setStyle("-fx-border-color: -velo-surface; -fx-border-width: 3; -fx-border-radius: 13; -fx-background-radius: 13;");
		iconBox.setTranslateY(-22);
		Label name = new Label(instance.name());
		name.getStyleClass().add("t-strong");
		name.setStyle("-fx-font-size: 15px;");
		Label meta = new Label(lastPlayedMillis == null ? "Never played" : "Played " + relativeTime(lastPlayedMillis));
		meta.getStyleClass().add("t-muted");
		VBox text = new VBox(2, name, meta);
		text.setMinWidth(0);
		HBox.setHgrow(text, Priority.ALWAYS);
		Label version = new Label(instance.mcVersion());
		version.getStyleClass().addAll("badge", "badge-grey", "badge-mono");
		version.setMinWidth(Region.USE_PREF_SIZE);
		HBox info = new HBox(12, iconBox, text, version);
		info.setAlignment(Pos.TOP_LEFT);
		info.setPadding(new Insets(10, 14, 0, 14));
		VBox.setMargin(info, new Insets(0, 0, -10, 0));
		VBox body = new VBox(art, info);
		StackPane tile = new StackPane(body);
		tile.getStyleClass().addAll("tile", "motion-card");
		Rectangle clip = new Rectangle();
		clip.setArcWidth(28);
		clip.setArcHeight(28);
		clip.widthProperty().bind(tile.widthProperty());
		clip.heightProperty().bind(tile.heightProperty());
		tile.setClip(clip);
		tile.setOnMouseEntered(e -> fadeTo(play, 1));
		tile.setOnMouseExited(e -> fadeTo(play, 0));
		tile.setOnMouseClicked(e -> {
			if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
				showInstanceDetail(instance);
			}
		});
		ContextMenu menu = new ContextMenu();
		menu.getItems().addAll(profileMenuItems(instance));
		tile.setOnContextMenuRequested(e -> menu.show(tile, e.getScreenX(), e.getScreenY()));
		return tile;
	}

	private static String relativeTime(long epochMillis) {
		long minutes = Math.max(0, (System.currentTimeMillis() - epochMillis) / 60_000);
		if (minutes < 2) {
			return "just now";
		}
		if (minutes < 60) {
			return minutes + " min ago";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours + "h ago";
		}
		long days = hours / 24;
		if (days == 1) {
			return "yesterday";
		}
		if (days < 7) {
			return days + " days ago";
		}
		return days < 30 ? (days / 7) + (days < 14 ? " week ago" : " weeks ago") : (days / 30) + (days < 60 ? " month ago" : " months ago");
	}

	private void showPlus() {
		net.veloclient.launcher.ui.StoreView.showPlusTab();
		showStore();
	}

	private void showSkins() {
		CosmeticsView.showSkinsTab();
		showCosmetics();
	}

	private void showReferrals() {
		if (session == null) {
			beginSignIn();
			return;
		}
		currentPage = this::showReferrals;
		setContent(net.veloclient.launcher.ui.ReferralView.build(this::showCoins));
		setActivePage("");
	}

	private void showMedia() {
		currentPage = this::showMedia;
		setActivePage("clips");
		setContent(net.veloclient.launcher.ui.MediaView.build(stage));
	}

	private net.veloclient.launcher.launch.UpdateService.Release availableUpdate;
	/** Set when the owner marked this version as too old (see UpdateService#policy): Play asks to update first. */
	private net.veloclient.launcher.launch.UpdateService.Policy requiredUpdate;

	/**
	 * Asks GitHub for the latest release in the background. Reveals the sidebar's "Update available"
	 * button when there's something new; {@code status} (nullable) is the Settings row to report into.
	 */
	private void checkForUpdates(Label status) {
		if (System.getProperty("velo.launcherTour") != null) {
			return;
		}
		if (status != null) {
			status.setText("Checking...");
		}
		Thread thread = new Thread(() -> {
			try {
				var release = net.veloclient.launcher.launch.UpdateService.check();
				var policy = net.veloclient.launcher.launch.UpdateService.policy();
				javafx.application.Platform.runLater(() -> {
					availableUpdate = release.anything() ? release : null;
					requiredUpdate = policy.required() && availableUpdate != null ? policy : null;
					refreshNotificationDot();
					if (status == null && requiredUpdate != null) {
						// A hotfix everyone needs: don't wait for the bell.
						net.veloclient.launcher.ui.UpdateDialog.show(stage, availableUpdate, java.util.Objects.requireNonNullElse(requiredUpdate.message(), ""));
					}
					if (status != null) {
						status.setText(availableUpdate == null ? "You're up to date (v" + AppVersion.VERSION + ")"
								: release.launcherNewer() ? "Version " + release.version() + " is available" : "A newer mod build is available");
						if (availableUpdate != null) {
							net.veloclient.launcher.ui.UpdateDialog.show(stage, availableUpdate);
						}
					}
				});
			} catch (Exception e) {
				LauncherLog.info("Update check failed: " + e);
				if (status != null) {
					javafx.application.Platform.runLater(() -> status.setText("Couldn't reach GitHub - try again later"));
				}
			}
		}, "velo-update-check");
		thread.setDaemon(true);
		thread.start();
	}

	private void showSettings() {
		currentPage = this::showSettings;
		Label heading = new Label("Settings");
		heading.getStyleClass().add("page-title");

		VBox content = new VBox(6);
		VBox updates = net.veloclient.launcher.ui.SettingsUi.card("Updates & system");
		Label updateStatus = new Label(availableUpdate != null ? "Version " + availableUpdate.version() + " is available"
				: "Installed: v" + AppVersion.VERSION);
		updateStatus.getStyleClass().add("settings-row-hint");
		Button checkButton = new Button(availableUpdate != null ? "Update" : "Check for updates");
		checkButton.getStyleClass().add("primary-button");
		checkButton.setOnAction(e -> {
			if (availableUpdate != null) {
				net.veloclient.launcher.ui.UpdateDialog.show(stage, availableUpdate);
			} else {
				checkForUpdates(updateStatus);
			}
		});
		net.veloclient.launcher.ui.SettingsUi.row(updates, "Velo Client", "Launcher and mod - updated right here, no website needed",
				"Downloads the latest GitHub release. New mod builds apply to every profile on its next launch;"
						+ " a new launcher version installs itself and restarts the launcher.", updateStatus, checkButton);
		javafx.scene.control.CheckBox minimize = new javafx.scene.control.CheckBox();
		minimize.setSelected(net.veloclient.launcher.data.LauncherSettings.get().minimizeOnLaunch);
		minimize.setOnAction(e -> {
			net.veloclient.launcher.data.LauncherSettings.Data data = net.veloclient.launcher.data.LauncherSettings.get();
			data.minimizeOnLaunch = minimize.isSelected();
			net.veloclient.launcher.data.LauncherSettings.save(data);
		});
		net.veloclient.launcher.ui.SettingsUi.row(updates, "Minimize when the game starts", "The launcher frees its memory while it's in the background",
				"While minimized (or in the background with a game running) the launcher drops the page you were on, stops all animations"
						+ " and empties its image caches, so it uses as little RAM and CPU as possible while you play.", minimize);
		Button systemCheck = new Button("Run check");
		systemCheck.setOnAction(e -> net.veloclient.launcher.ui.SystemCheckDialog.showAll(stage,
				net.veloclient.launcher.launch.SystemHealth.check(null)));
		net.veloclient.launcher.ui.SettingsUi.row(updates, "System check", "Memory, swap, disk space and other games - also runs before every launch",
				"Checks the usual causes of freezes and crashes on start. Before a launch only real problems interrupt you.", systemCheck);
		javafx.scene.control.CheckBox telemetry = new javafx.scene.control.CheckBox();
		telemetry.setSelected(net.veloclient.launcher.data.LauncherSettings.get().crashTelemetry);
		telemetry.setOnAction(e -> {
			net.veloclient.launcher.data.LauncherSettings.Data data = net.veloclient.launcher.data.LauncherSettings.get();
			data.crashTelemetry = telemetry.isSelected();
			net.veloclient.launcher.data.LauncherSettings.save(data);
		});
		net.veloclient.launcher.ui.SettingsUi.row(updates, "Automatic crash reports", "Off by default - helps us find the crashes hitting the most players",
				"When the game crashes, the crash report, game log, mod list and system info (OS, RAM, GPU, versions) are sent"
						+ " to the Velo team without you pressing Send. Reports of the same crash are grouped so we fix the biggest ones first."
						+ " No worlds, screenshots or chat beyond what's in the log.", telemetry);
		content.getChildren().add(updates);
		content.getChildren().addAll(net.veloclient.launcher.ui.PerformanceSettingsView.build(theme));

		VBox appearance = net.veloclient.launcher.ui.SettingsUi.card("Appearance");
		appearance.getChildren().add(themePicker());
		Button themeButton = new Button("Theme editor");
		themeButton.setGraphic(net.veloclient.launcher.ui.Icons.icon("palette", 15, Color.web("#E4E4E8")));
		themeButton.setOnAction(e -> showThemeEditor());
		net.veloclient.launcher.ui.SettingsUi.row(appearance, "Your own theme", "Accent, background, corners - with a live preview",
				"Make your own colors and share them as a code.", themeButton);
		appearance.getChildren().add(homeBackgroundRow());
		content.getChildren().add(1, appearance);

		VBox account = net.veloclient.launcher.ui.SettingsUi.card("Account");
		if (session != null) {
			Button signOutButton = new Button("Sign out");
			signOutButton.getStyleClass().add("danger-ghost-button");
			signOutButton.setOnAction(e -> signOut());
			net.veloclient.launcher.ui.SettingsUi.row(account, session.username(), "Microsoft account", "UUID " + session.uuid(), signOutButton);
			// velo-client.com: a short code to type on the website signs you in to the web Store.
			Label webCode = new Label("");
			webCode.setStyle("-fx-font-family: 'JetBrains Mono'; -fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -velo-text;");
			Button codeButton = new Button("Get a code");
			codeButton.setOnAction(e -> {
				codeButton.setDisable(true);
				java.util.concurrent.CompletableFuture.supplyAsync(() -> {
					try {
						return net.veloclient.launcher.social.StoreApi.webCode();
					} catch (net.veloclient.launcher.social.StoreApi.StoreError error) {
						com.google.gson.JsonObject failed = new com.google.gson.JsonObject();
						failed.addProperty("error", error.getMessage());
						return failed;
					}
				}).thenAccept(result -> Platform.runLater(() -> {
					codeButton.setDisable(false);
					if (result.has("error")) {
						webCode.setText("");
						net.veloclient.launcher.ui.Toasts.show(codeButton, result.get("error").getAsString());
						return;
					}
					String code = result.get("code").getAsString();
					webCode.setText(code.substring(0, 3) + " " + code.substring(3));
					codeButton.setText("New code");
					javafx.scene.input.ClipboardContent clip = new javafx.scene.input.ClipboardContent();
					clip.putString(code);
					javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
					net.veloclient.launcher.ui.Toasts.show(codeButton, "Copied - works for 5 minutes");
				}));
			});
			net.veloclient.launcher.ui.SettingsUi.row(account, "Website sign-in", "Buy in the Store on velo-client.com with this account",
					"Get a code, then type it on velo-client.com. It works once, for 5 minutes. The website never sees your Microsoft login.",
					webCode, codeButton);
		} else {
			Button signInButton = new Button("Sign in");
			signInButton.getStyleClass().add("primary-button");
			signInButton.setOnAction(e -> beginSignIn());
			net.veloclient.launcher.ui.SettingsUi.row(account, "Not signed in", "Sign in to play online", null, signInButton);
		}
		content.getChildren().add(account);

		VBox files = net.veloclient.launcher.ui.SettingsUi.card("Files");
		net.veloclient.launcher.ui.SettingsUi.row(files, "Launcher data", "Profiles, capes and settings",
				"Profiles: " + VeloPaths.profiles() + "\nCapes: " + VeloPaths.capes() + "\nManifest: " + VeloPaths.manifestFile(),
				openFolderButton(VeloPaths.root()));
		net.veloclient.launcher.ui.SettingsUi.row(files, "Launcher logs", "Attach these when reporting a launcher bug", null,
				openFolderButton(VeloPaths.logs()));
		content.getChildren().add(files);

		VBox about = net.veloclient.launcher.ui.SettingsUi.card("About");
		Label version = new Label("v" + AppVersion.VERSION);
		version.getStyleClass().add("settings-value");
		net.veloclient.launcher.ui.SettingsUi.row(about, "Velo Client Launcher", "Made by players, for players", null, version);
		content.getChildren().add(about);

		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		HBox.setHgrow(scroll, Priority.ALWAYS);
		content.setMaxWidth(860);
		content.setPadding(new Insets(4, 24, 24, 4));

		// Left: search, then one entry per section that scrolls there.
		TextField search = new TextField();
		search.setPromptText("Search settings");
		VBox nav = new VBox(2, heading, search);
		VBox.setMargin(search, new Insets(14, 0, 12, 0));
		nav.setMinWidth(230);
		nav.setPrefWidth(240);
		List<VBox> cards = content.getChildren().stream().filter(n -> n instanceof VBox v && v.getStyleClass().contains("settings-card"))
				.map(n -> (VBox) n).toList();
		for (VBox card : cards) {
			String name = card.getChildren().isEmpty() || !(card.getChildren().get(0) instanceof Label l) ? "Section" : l.getText();
			Button entry = new Button(name);
			entry.getStyleClass().setAll("button", "snav", "no-motion");
			entry.setMaxWidth(Double.MAX_VALUE);
			entry.setOnAction(e -> {
				double y = card.getBoundsInParent().getMinY();
				double max = content.getHeight() - scroll.getViewportBounds().getHeight();
				scroll.setVvalue(max <= 0 ? 0 : Math.min(1, y / max));
			});
			nav.getChildren().add(entry);
		}
		search.textProperty().addListener((o, a, query) -> {
			String q = query.strip().toLowerCase(java.util.Locale.ROOT);
			for (VBox card : cards) {
				boolean any = false;
				for (Node child : card.getChildren()) {
					if (child.getStyleClass().contains("settings-row")) {
						boolean match = q.isEmpty() || rowText(child).contains(q);
						child.setVisible(match);
						child.setManaged(match);
						any |= match;
					}
				}
				boolean titleMatch = !card.getChildren().isEmpty() && card.getChildren().get(0) instanceof Label l
						&& l.getText().toLowerCase(java.util.Locale.ROOT).contains(q);
				if (titleMatch) {
					card.getChildren().forEach(c -> {
						c.setVisible(true);
						c.setManaged(true);
					});
				}
				card.setVisible(any || titleMatch || q.isEmpty());
				card.setManaged(any || titleMatch || q.isEmpty());
			}
		});
		HBox page = new HBox(36, nav, scroll);
		page.getStyleClass().add("page");
		setContent(page);
		setActivePage("settings");
	}

	/** Every label text inside a settings row (lower case), for the settings search. */
	private static String rowText(Node node) {
		StringBuilder text = new StringBuilder();
		if (node instanceof Label label) {
			text.append(label.getText()).append(' ');
		}
		if (node instanceof javafx.scene.Parent parent) {
			for (Node child : parent.getChildrenUnmodifiable()) {
				text.append(rowText(child));
			}
		}
		return text.toString().toLowerCase(java.util.Locale.ROOT);
	}

	/** Theme presets and your own themes as small previews; clicking one switches the whole launcher. */
	private Node themePicker() {
		javafx.scene.layout.FlowPane tiles = new javafx.scene.layout.FlowPane(12, 12);
		java.util.Map<String, LauncherTheme> all = new java.util.LinkedHashMap<>(net.veloclient.launcher.theme.LauncherThemePresets.all());
		all.putAll(net.veloclient.launcher.theme.LauncherCustomThemeStore.asMap(net.veloclient.launcher.theme.LauncherCustomThemeStore.load()));
		for (LauncherTheme candidate : all.values()) {
			StackPane preview = new StackPane();
			preview.setPrefSize(150, 84);
			preview.setMinSize(150, 84);
			preview.setStyle("-fx-background-color: " + cssColor(candidate.background()) + "; -fx-background-radius: 9;");
			Region bar = new Region();
			bar.setMaxSize(130, 8);
			bar.setStyle("-fx-background-color: " + cssColor(candidate.surface()) + "; -fx-background-radius: 3;");
			StackPane.setAlignment(bar, Pos.TOP_CENTER);
			StackPane.setMargin(bar, new Insets(10, 0, 0, 0));
			Region panel = new Region();
			panel.setMaxSize(62, 40);
			panel.setStyle("-fx-background-color: " + cssColor(candidate.surface()) + "; -fx-background-radius: 6;");
			StackPane.setAlignment(panel, Pos.BOTTOM_LEFT);
			StackPane.setMargin(panel, new Insets(0, 0, 10, 10));
			Region accent = new Region();
			accent.setMaxSize(52, 16);
			accent.setStyle("-fx-background-color: " + cssColor(candidate.accentStart()) + "; -fx-background-radius: 5;");
			StackPane.setAlignment(accent, Pos.BOTTOM_RIGHT);
			StackPane.setMargin(accent, new Insets(0, 10, 10, 0));
			preview.getChildren().addAll(bar, panel, accent);
			Label name = new Label(candidate.name());
			name.getStyleClass().add("t-strong");
			name.setStyle("-fx-font-size: 12px;");
			HBox label = new HBox(6, name);
			label.setAlignment(Pos.CENTER_LEFT);
			if (candidate.name().equals(theme.name())) {
				label.getChildren().add(net.veloclient.launcher.ui.Icons.icon("check", 13, accentColor(), 2.8));
			}
			VBox tile = new VBox(8, preview, label);
			tile.getStyleClass().addAll("theme-tile", "motion-card");
			if (candidate.name().equals(theme.name())) {
				tile.getStyleClass().add("theme-tile-active");
			}
			tile.setOnMouseClicked(e -> {
				theme = candidate;
				ThemeStore.save(candidate);
				applyTheme();
				showSettings();
			});
			tiles.getChildren().add(tile);
		}
		VBox create = new VBox(8);
		create.getStyleClass().add("dashed-tile");
		create.setAlignment(Pos.CENTER);
		create.setPrefSize(162, 120);
		create.getChildren().addAll(net.veloclient.launcher.ui.Icons.icon("palette", 20, Color.web("#C9C9D1")), new Label("Create a theme"));
		create.setOnMouseClicked(e -> showThemeEditor());
		tiles.getChildren().add(create);
		tiles.setPadding(new Insets(10, 0, 4, 0));
		return tiles;
	}

	private Button openFolderButton(java.nio.file.Path folder) {
		Button open = new Button("Open");
		open.setTooltip(new Tooltip(folder.toString()));
		open.setOnAction(e -> {
			try {
				java.nio.file.Files.createDirectories(folder);
				InstanceDetailView.openInFileManager(folder);
			} catch (Exception ex) {
				showPlaceholderAlert("Couldn't open folder", ex.getMessage());
			}
		});
		return open;
	}

	private VBox settingsCard(String heading, String subtitle) {
		VBox card = new VBox(8);
		card.getStyleClass().add("glass-panel");
		Label headingLabel = new Label(heading);
		headingLabel.setFont(Font.font("Inter", FontWeight.BOLD, 15));
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
