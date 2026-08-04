package net.veloclient.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.AuthSession;
import net.veloclient.launcher.auth.MicrosoftAuth;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.SkinFetcher;
import net.veloclient.launcher.data.SavedServer;
import net.veloclient.launcher.data.SavedServerStore;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.instance.BuiltinIcons;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceIcon;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
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
import net.veloclient.launcher.ui.InstanceDetailView;
import net.veloclient.launcher.ui.InstanceEditDialog;
import net.veloclient.launcher.ui.InstanceSettingsDialog;
import net.veloclient.launcher.ui.ParticleBackground;
import net.veloclient.launcher.ui.PlayerHeadView;
import net.veloclient.launcher.ui.ServerEditDialog;
import net.veloclient.launcher.ui.SignInDialog;
import net.veloclient.launcher.ui.ThemeEditorView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
	private StackPane content;
	private Stage stage;
	private ParticleBackground background;
	private MinecraftSession session;
	private Label accountLabel;
	private Button accountButton;
	private Button navHome, navInstances, navCosmetics, navTheme, navSettings;

	public static void main(String[] args) {
		launch(args);
	}

	@Override
	public void start(Stage stage) {
		this.stage = stage;
		VeloPaths.ensureDirectories();
		theme = ThemeStore.load();

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
		stage.show();

		attemptSilentSignIn();
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
		sidebar.setPadding(new Insets(22, 12, 16, 12));
		sidebar.setPrefWidth(170);

		Label title = new Label("VELO CLIENT");
		title.getStyleClass().add("sidebar-title");
		title.setTextFill(accentColor());
		VBox.setMargin(title, new Insets(0, 0, 16, 6));

		navHome = navButton("Home", this::showHome);
		navInstances = navButton("Profiles", this::showInstances);
		navCosmetics = navButton("Cosmetics", this::showCosmetics);
		navTheme = navButton("Theme Editor", this::showThemeEditor);
		navSettings = navButton("Settings", this::showSettings);

		sidebar.getChildren().addAll(title, navHome, navInstances, navCosmetics, navTheme, navSettings);
		VBox spacer = new VBox();
		VBox.setVgrow(spacer, Priority.ALWAYS);
		sidebar.getChildren().add(spacer);

		Label version = new Label("v" + AppVersion.VERSION);
		version.getStyleClass().add("version-tag");
		version.setTextFill(textColor());
		sidebar.getChildren().add(version);
		return sidebar;
	}

	private Button navButton(String label, Runnable action) {
		Button button = new Button(label);
		button.getStyleClass().add("nav-button");
		button.setMaxWidth(Double.MAX_VALUE);
		button.setTextFill(textColor());
		button.setOnAction(e -> {
			action.run();
			markActiveNav(button);
		});
		return button;
	}

	private void markActiveNav(Button active) {
		for (Button b : List.of(navHome, navInstances, navCosmetics, navTheme, navSettings)) {
			b.getStyleClass().remove("nav-button-active");
		}
		active.getStyleClass().add("nav-button-active");
	}

	private void setContent(Node node) {
		content.getChildren().setAll(node);
	}

	// ---- Home / title screen ----

	private void showHome() {
		if (background == null) {
			background = new ParticleBackground(800, 600, 60);
		}
		background.setDotColor(accentColor().deriveColor(0, 1, 1, 0.35));
		background.widthProperty().unbind();
		background.heightProperty().unbind();

		StackPane titleScreen = new StackPane();
		titleScreen.getStyleClass().add("title-screen");
		background.widthProperty().bind(titleScreen.widthProperty());
		background.heightProperty().bind(titleScreen.heightProperty());
		titleScreen.getChildren().add(background);
		background.start();

		VBox center = new VBox(10);
		center.setAlignment(Pos.CENTER);

		Label title = new Label("VELO CLIENT");
		title.getStyleClass().add("velo-title");
		title.setTextFill(accentColor());
		title.setEffect(new javafx.scene.effect.DropShadow(24, accentColor().deriveColor(0, 1, 1, 0.6)));

		Label tagline = new Label("Made by Players for Players.");
		tagline.getStyleClass().add("velo-tagline");
		tagline.setTextFill(textColor());

		VBox menu = new VBox(12);
		menu.setAlignment(Pos.CENTER);
		menu.setMaxWidth(280);
		menu.setPadding(new Insets(28, 0, 0, 0));

		Button play = new Button("Play");
		play.getStyleClass().addAll("title-menu-button", "title-menu-button-primary");
		play.setMaxWidth(Double.MAX_VALUE);
		play.setOnAction(e -> { showInstances(); markActiveNav(navInstances); });

		Button servers = new Button("My Servers");
		servers.getStyleClass().add("title-menu-button");
		servers.setMaxWidth(Double.MAX_VALUE);
		servers.setOnAction(e -> showServers());

		Button quit = new Button("Quit");
		quit.getStyleClass().add("title-menu-button");
		quit.setMaxWidth(Double.MAX_VALUE);
		quit.setOnAction(e -> Platform.exit());

		menu.getChildren().addAll(play, servers, quit);
		center.getChildren().addAll(title, tagline, menu);

		titleScreen.getChildren().add(center);
		StackPane.setAlignment(center, Pos.CENTER);

		// Bottom-left account badge (Minecraft-style nametag corner).
		accountLabel = new Label();
		accountButton = new Button();
		accountButton.getStyleClass().add("account-badge");
		accountButton.setGraphic(buildAccountBadgeContent());
		accountButton.setOnAction(e -> {
			if (session == null) {
				beginSignIn();
			} else {
				showAccountProfile();
			}
		});
		StackPane.setAlignment(accountButton, Pos.BOTTOM_LEFT);
		StackPane.setMargin(accountButton, new Insets(0, 0, 18, 18));
		titleScreen.getChildren().add(accountButton);

		refreshAccountBadge();
		setContent(titleScreen);
		markActiveNav(navHome);
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
		box.getChildren().addAll(avatarHolder, accountLabel);
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
				LauncherApp.this.signOut();
				showHome();
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
		Label name = new Label(server.name());
		name.setFont(Font.font("System", FontWeight.BOLD, 15));
		name.setTextFill(accentColor());
		Label address = new Label(server.address());
		address.getStyleClass().add("version-tag");
		address.setTextFill(textColor());
		HBox spacer = new HBox();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		headerRow.getChildren().addAll(name, address, spacer);

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
			pingInto(server, motd, statusLine);
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
		pingInto(server, motd, statusLine);
		return card;
	}

	private void pingInto(SavedServer server, javafx.scene.text.TextFlow motd, Label statusLine) {
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
					} else {
						motd.getChildren().setAll(new javafx.scene.text.Text("Offline or unreachable"));
						((javafx.scene.text.Text) motd.getChildren().get(0)).setFill(textColor());
						statusLine.setText(((Exception) result).getMessage());
					}
				}));
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
				Platform.runLater(AuthSession::clear);
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
		if (content.getChildren().stream().anyMatch(n -> n.getStyleClass().contains("title-screen"))) {
			showHome();
		} else if (accountButton != null) {
			refreshAccountBadge();
		}
	}

	private void signOut() {
		session = null;
		AuthSession.clear();
		showHome();
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

	// ---- Profiles (mod loadouts you launch with Play) ----

	private void showInstances() {
		VBox box = sectionBox("Profiles");
		box.getChildren().add(sectionSubtitle("Each profile is its own mods folder + Minecraft version. Click its icon to manage mods/resource packs/shaders, the gear for RAM settings, or press Play to install and launch it."));

		FlowPane grid = new FlowPane(16, 16);
		for (Instance instance : InstanceStore.loadAll()) {
			grid.getChildren().add(buildInstanceCard(instance));
		}
		grid.getChildren().add(buildNewInstanceTile());

		ScrollPane scroll = new ScrollPane(grid);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		box.getChildren().add(wrapGlass(scroll));
		setContent(box);
	}

	private VBox buildInstanceCard(Instance instance) {
		VBox card = new VBox(10);
		card.getStyleClass().add("instance-card");
		card.setPrefWidth(200);
		card.setAlignment(Pos.TOP_CENTER);

		Node icon = renderInstanceIcon(instance, 72);
		icon.getStyleClass().add("instance-icon-button");
		icon.setOnMouseClicked(e -> showInstanceDetail(instance));

		Label name = new Label(instance.name());
		name.setFont(Font.font("System", FontWeight.BOLD, 15));
		name.setTextFill(textColor());
		name.setWrapText(true);

		Label version = new Label("Minecraft " + instance.mcVersion());
		version.getStyleClass().add("version-tag");
		version.setTextFill(textColor());

		HBox playRow = new HBox(6);
		boolean signedIn = session != null;
		Button playButton = new Button(signedIn ? "Play" : "Sign In to Play");
		playButton.getStyleClass().addAll("title-menu-button", "title-menu-button-primary");
		playButton.setMaxWidth(Double.MAX_VALUE);
		playButton.setTooltip(new Tooltip(signedIn ? "Install and launch " + instance.name()
				: "Sign in with your Microsoft account first - this won't launch anything until you do."));
		HBox.setHgrow(playButton, Priority.ALWAYS);
		Button settingsButton = new Button("⚙");
		settingsButton.setTooltip(new Tooltip("RAM & JVM settings"));
		settingsButton.setOnAction(e -> InstanceSettingsDialog.show(stage, instance).ifPresent(updated -> {
			InstanceStore.save(updated);
			showInstances();
		}));
		playRow.getChildren().addAll(playButton, settingsButton);

		ProgressBar progressBar = new ProgressBar(0);
		progressBar.setMaxWidth(Double.MAX_VALUE);
		progressBar.setVisible(false);
		progressBar.setManaged(false);
		Label statusLabel = new Label();
		statusLabel.getStyleClass().add("version-tag");
		statusLabel.setTextFill(textColor());
		statusLabel.setVisible(false);
		statusLabel.setManaged(false);
		statusLabel.setWrapText(true);

		playButton.setOnAction(e -> {
			if (session == null) {
				SignInDialog.show(stage, MICROSOFT_CLIENT_ID, newSession -> { onSignedIn(newSession); showInstances(); },
						error -> showPlaceholderAlert("Sign-in failed", error));
			} else {
				launchInstance(instance, playButton, progressBar, statusLabel);
			}
		});

		HBox actions = new HBox(6);
		actions.setAlignment(Pos.CENTER);
		Button editButton = new Button("Edit");
		editButton.setOnAction(e -> editInstance(instance));
		Button deleteButton = new Button("Delete");
		deleteButton.setOnAction(e -> confirmDeleteInstance(instance));
		actions.getChildren().addAll(editButton, deleteButton);

		card.getChildren().addAll(icon, name, version, playRow, progressBar, statusLabel, actions);
		return card;
	}

	private void showInstanceDetail(Instance instance) {
		setContent(InstanceDetailView.build(stage, instance, theme, this::showInstances));
	}

	private VBox buildNewInstanceTile() {
		VBox tile = new VBox(10);
		tile.getStyleClass().addAll("instance-card", "instance-card-new");
		tile.setPrefWidth(200);
		tile.setAlignment(Pos.CENTER);
		tile.setPrefHeight(190);

		Label plus = new Label("+");
		plus.setFont(Font.font("System", FontWeight.BOLD, 40));
		plus.setTextFill(accentColor());
		Label label = new Label("New Profile");
		label.setTextFill(textColor());

		tile.getChildren().addAll(plus, label);
		tile.setOnMouseClicked(e -> InstanceEditDialog.show(stage, theme, "New Profile", "", null, InstanceIcon.builtin(BuiltinIcons.DEFAULT))
				.ifPresent(result -> {
					Instance instance = InstanceStore.createNew(
							result.name().isBlank() ? "New Profile" : result.name(), result.mcVersion(), result.icon());
					applyIconChoice(instance, result);
					installModLoaderJars(instance);
					showInstances();
				}));
		return tile;
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
					showInstances();
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

	private void confirmDeleteInstance(Instance instance) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(stage);
		alert.setTitle("Delete Profile");
		alert.setHeaderText("Delete \"" + instance.name() + "\"?");
		alert.setContentText("This deletes its mods, saves, and config. This can't be undone.");
		DialogStyling.apply(alert);
		alert.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
			InstanceStore.delete(instance);
			showInstances();
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

	private void launchInstance(Instance instance, Button playButton, ProgressBar progressBar, Label statusLabel) {
		launchWithProgress(instance, null, playButton, progressBar, statusLabel);
	}

	/** @param quickPlayTarget nullable "host:port" - when present, launches straight into that server (My Servers' Connect). */
	private void launchWithProgress(Instance instance, String quickPlayTarget, Button triggerButton, ProgressBar progressBar, Label statusLabel) {
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
					Process process = GameLauncher.launch(instance, activeSession, listener, quickPlayTarget);
					Platform.runLater(() -> {
						statusLabel.setText("Running - " + instance.name());
						progressBar.setProgress(1.0);
					});
					int exitCode = process.waitFor();
					boolean crashedEarly = exitCode != 0 && (System.currentTimeMillis() - startedAt) < 15_000;
					Platform.runLater(() -> {
						triggerButton.setDisable(false);
						progressBar.setVisible(false);
						progressBar.setManaged(false);
						statusLabel.setText(exitCode == 0 ? "" : "Minecraft exited with code " + exitCode);
						statusLabel.setVisible(exitCode != 0);
						statusLabel.setManaged(exitCode != 0);
						if (exitCode != 0) {
							ErrorDialog.showLaunchFailure(stage, instance, exitCode, crashedEarly);
						}
					});
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
					Platform.runLater(() -> {
						AuthSession.clear();
						session = null;
						beginSignIn();
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
		VBox list = new VBox(6);
		list.getChildren().add(sectionSubtitle("Config root: " + VeloPaths.root()));
		list.getChildren().add(sectionSubtitle("Manifest: " + VeloPaths.manifestFile()));
		list.getChildren().add(sectionSubtitle("Profiles: " + VeloPaths.profiles()));
		list.getChildren().add(sectionSubtitle("Capes: " + VeloPaths.capes()));
		if (session != null) {
			list.getChildren().add(sectionSubtitle("Signed in as: " + session.username() + " (" + session.uuid() + ")"));
			Button signOutButton = new Button("Sign out");
			signOutButton.setOnAction(e -> signOut());
			list.getChildren().add(signOutButton);
		}
		box.getChildren().add(wrapGlass(list));
		setContent(box);
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
