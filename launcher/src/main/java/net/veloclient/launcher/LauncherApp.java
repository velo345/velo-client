package net.veloclient.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.AuthSession;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.ManifestReader;
import net.veloclient.launcher.data.ModuleInfo;
import net.veloclient.launcher.data.ProfileData;
import net.veloclient.launcher.data.ProfileStore;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.net.ServerPinger;
import net.veloclient.launcher.theme.LauncherTheme;
import net.veloclient.launcher.theme.LauncherThemePresets;
import net.veloclient.launcher.theme.ThemeStore;
import net.veloclient.launcher.ui.ParticleBackground;
import net.veloclient.launcher.ui.SignInDialog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Standalone launcher shell (design spec section 4): a Minecraft-style
 * animated title screen for Home, plus Mods/Cosmetics/Profiles/Settings/
 * Theme Editor sections behind a sidebar. Reads/writes the exact same
 * {@code ~/.velo-client/} files as the in-game mod (manifest.json,
 * profiles/*.json, config/theme.json), and signs in for real via the
 * Microsoft device-code flow ({@code net.veloclient.launcher.auth}) - see
 * README.md for how the client id is obtained.
 *
 * <p>Still not implemented: actually installing/launching the game (that
 * needs a full Fabric + asset/library downloader, a separate large piece of
 * work) - once signed in, Play shows your account and says so honestly
 * rather than pretending to launch anything.
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
	private Button navHome, navMods, navCosmetics, navProfiles, navTheme, navSettings;

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
		navMods = navButton("Mods", this::showMods);
		navCosmetics = navButton("Cosmetics", this::showCosmetics);
		navProfiles = navButton("Profiles", this::showProfiles);
		navTheme = navButton("Theme Editor", this::showThemeEditor);
		navSettings = navButton("Settings", this::showSettings);

		sidebar.getChildren().addAll(title, navHome, navMods, navCosmetics, navProfiles, navTheme, navSettings);
		VBox spacer = new VBox();
		VBox.setVgrow(spacer, Priority.ALWAYS);
		sidebar.getChildren().add(spacer);

		Label version = new Label("v0.1.0 · MC 1.21.11");
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
		for (Button b : List.of(navHome, navMods, navCosmetics, navProfiles, navTheme, navSettings)) {
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

		Button play = new Button(session != null ? "Play as " + session.username() : "Sign in to Play");
		play.getStyleClass().addAll("title-menu-button", "title-menu-button-primary");
		play.setMaxWidth(Double.MAX_VALUE);
		play.setOnAction(e -> {
			if (session == null) {
				beginSignIn();
			} else {
				showPlaceholderAlert("Play",
						"Signed in as " + session.username() + " (" + session.uuid() + ").\n\n"
						+ "Installing/launching the game (Fabric + asset/library download) isn't "
						+ "implemented yet - this button will do that once it lands.");
			}
		});

		Button mods = new Button("Mods");
		mods.getStyleClass().add("title-menu-button");
		mods.setMaxWidth(Double.MAX_VALUE);
		mods.setOnAction(e -> { showMods(); markActiveNav(navMods); });

		Button servers = new Button("My Servers");
		servers.getStyleClass().add("title-menu-button");
		servers.setMaxWidth(Double.MAX_VALUE);
		servers.setOnAction(e -> showServers());

		menu.getChildren().addAll(play, mods, servers);
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
				signOut();
			}
		});
		StackPane.setAlignment(accountButton, Pos.BOTTOM_LEFT);
		StackPane.setMargin(accountButton, new Insets(0, 0, 18, 18));
		titleScreen.getChildren().add(accountButton);

		Label versionTag = new Label("Velo Client 0.1.0  ·  Minecraft 1.21.11  ·  Fabric");
		versionTag.getStyleClass().add("version-tag");
		versionTag.setTextFill(textColor());
		StackPane.setAlignment(versionTag, Pos.BOTTOM_RIGHT);
		StackPane.setMargin(versionTag, new Insets(0, 18, 18, 0));
		titleScreen.getChildren().add(versionTag);

		refreshAccountBadge();
		setContent(titleScreen);
		markActiveNav(navHome);
	}

	private Node buildAccountBadgeContent() {
		HBox box = new HBox(10);
		box.setAlignment(Pos.CENTER_LEFT);
		Label avatar = new Label();
		avatar.getStyleClass().add("avatar-circle");
		avatar.setStyle("-fx-background-color: linear-gradient(to bottom right, " + cssColor(theme.accentStart()) + ", " + cssColor(theme.accentEnd()) + ");");
		avatar.setTextFill(Color.WHITE);
		accountLabel.setTextFill(textColor());
		box.getChildren().addAll(avatar, accountLabel);
		return box;
	}

	private void refreshAccountBadge() {
		HBox box = (HBox) accountButton.getGraphic();
		Label avatar = (Label) box.getChildren().get(0);
		if (session != null) {
			avatar.setText(session.username().substring(0, 1).toUpperCase());
			accountLabel.setText(session.username());
		} else {
			avatar.setText("?");
			accountLabel.setText("Not signed in - click to sign in");
		}
	}

	private void showServers() {
		VBox box = sectionBox("My Servers");
		box.getChildren().add(sectionSubtitle("Saved servers, pinged live for status - separate from your in-game server list."));

		Button addButton = new Button("+ Add Server");
		addButton.getStyleClass().add("title-menu-button");
		addButton.setOnAction(e -> net.veloclient.launcher.ui.ServerEditDialog.show(stage, "Add Server", "", "", 25565)
				.ifPresent(result -> {
					net.veloclient.launcher.data.SavedServerStore.add(result.name(), result.host(), result.port());
					showServers();
				}));
		box.getChildren().add(addButton);

		VBox list = new VBox(8);
		List<net.veloclient.launcher.data.SavedServer> servers = net.veloclient.launcher.data.SavedServerStore.loadAll();
		if (servers.isEmpty()) {
			list.getChildren().add(sectionSubtitle("No servers saved yet - add one above."));
		}
		for (net.veloclient.launcher.data.SavedServer server : servers) {
			list.getChildren().add(buildServerRow(server));
		}
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		box.getChildren().add(wrapGlass(scroll));
		setContent(box);
	}

	private Node buildServerRow(net.veloclient.launcher.data.SavedServer server) {
		VBox card = new VBox(4);
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

		Label status = new Label("Pinging...");
		status.getStyleClass().add("section-subtitle");
		status.setTextFill(textColor());

		HBox actions = new HBox(8);
		Button connect = new Button("Connect");
		connect.setOnAction(e -> showPlaceholderAlert("Connect",
				"Installing/launching the game isn't implemented yet, so Velo Client can't join "
				+ server.name() + " directly - once game launching lands, this button will connect straight to it."));
		Button refresh = new Button("Refresh");
		refresh.setOnAction(e -> {
			status.setText("Pinging...");
			pingInto(server, status);
		});
		Button edit = new Button("Edit");
		edit.setOnAction(e -> net.veloclient.launcher.ui.ServerEditDialog.show(stage, "Edit Server", server.name(), server.host(), server.port())
				.ifPresent(result -> {
					net.veloclient.launcher.data.SavedServerStore.update(new net.veloclient.launcher.data.SavedServer(
							server.id(), result.name(), result.host(), result.port()));
					showServers();
				}));
		Button remove = new Button("Remove");
		remove.setOnAction(e -> {
			net.veloclient.launcher.data.SavedServerStore.remove(server);
			showServers();
		});
		actions.getChildren().addAll(connect, refresh, edit, remove);

		card.getChildren().addAll(headerRow, status, actions);
		pingInto(server, status);
		return card;
	}

	private void pingInto(net.veloclient.launcher.data.SavedServer server, Label status) {
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
						status.setText(String.format("%s  ·  %d/%d players  ·  %dms  ·  %s",
								ping.versionName(), ping.onlinePlayers(), ping.maxPlayers(), ping.latencyMillis(), ping.motd()));
					} else {
						status.setText("Offline or unreachable (" + ((Exception) result).getMessage() + ")");
					}
				}));
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
		alert.setTitle(title);
		alert.setHeaderText(title);
		alert.setContentText(message);
		net.veloclient.launcher.ui.DialogStyling.apply(alert);
		alert.showAndWait();
	}

	// ---- Mods ----

	private void showMods() {
		VBox box = sectionBox("Mods");
		List<ModuleInfo> modules = ManifestReader.read();
		if (modules.isEmpty()) {
			box.getChildren().add(sectionSubtitle(
					"No manifest.json found yet - launch the game at least once so the mod can export its module list."));
			setContent(box);
			return;
		}
		box.getChildren().add(sectionSubtitle(modules.size() + " modules installed (read-only browser; toggle them in-game or via a profile)"));

		Map<String, List<ModuleInfo>> byCategory = modules.stream().collect(Collectors.groupingBy(ModuleInfo::category));
		ScrollPane scroll = new ScrollPane();
		VBox list = new VBox(10);
		for (var entry : byCategory.entrySet()) {
			Label categoryLabel = new Label(entry.getKey());
			categoryLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
			categoryLabel.setTextFill(accentColor());
			list.getChildren().add(categoryLabel);
			for (ModuleInfo module : entry.getValue()) {
				Label row = new Label(module.displayName() + "  [" + module.safetyTag() + "]");
				row.setTextFill(textColor());
				row.setTooltip(new Tooltip(module.description()));
				list.getChildren().add(row);
			}
		}
		scroll.setContent(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		box.getChildren().add(wrapGlass(scroll));
		setContent(box);
	}

	// ---- Cosmetics ----

	private void showCosmetics() {
		VBox box = sectionBox("Cosmetics");
		box.getChildren().add(sectionSubtitle("Cape library - shared with the in-game Capes menu; import, equip, or delete here."));

		Button importButton = new Button("Import Cape...");
		importButton.getStyleClass().add("title-menu-button");
		importButton.setOnAction(e -> {
			net.veloclient.launcher.ui.CapeImportDialog.show(stage).ifPresent(result -> {
				try {
					net.veloclient.launcher.data.CapeLibrary.importPng(result.name(), result.pngFile(), result.preset());
					showCosmetics();
				} catch (Exception ex) {
					showPlaceholderAlert("Import failed", ex.getMessage());
				}
			});
		});
		box.getChildren().add(importButton);

		VBox list = new VBox(8);
		List<net.veloclient.launcher.data.CapeEntry> capes = net.veloclient.launcher.data.CapeLibrary.listAll();
		String equippedId = net.veloclient.launcher.data.CapeLibrary.equippedCapeId().orElse(null);
		if (capes.isEmpty()) {
			list.getChildren().add(sectionSubtitle("No capes imported yet. Textures must be exactly "
					+ net.veloclient.launcher.data.CapeLibrary.TEXTURE_WIDTH + "x"
					+ net.veloclient.launcher.data.CapeLibrary.TEXTURE_HEIGHT + " PNGs."));
		}
		for (net.veloclient.launcher.data.CapeEntry cape : capes) {
			boolean equipped = cape.id().equals(equippedId);
			HBox row = new HBox(10);
			row.setAlignment(Pos.CENTER_LEFT);
			Label label = new Label(cape.name() + (equipped ? "  (equipped)" : ""));
			label.setTextFill(equipped ? accentColor() : textColor());
			Button equipButton = new Button(equipped ? "Unequip" : "Equip");
			equipButton.setOnAction(e -> {
				if (equipped) {
					net.veloclient.launcher.data.CapeLibrary.unequip();
				} else {
					net.veloclient.launcher.data.CapeLibrary.equip(cape.id());
				}
				showCosmetics();
			});
			Button deleteButton = new Button("Delete");
			deleteButton.setOnAction(e -> {
				try {
					net.veloclient.launcher.data.CapeLibrary.delete(cape);
					showCosmetics();
				} catch (Exception ex) {
					showPlaceholderAlert("Delete failed", ex.getMessage());
				}
			});
			row.getChildren().addAll(label, equipButton, deleteButton);
			list.getChildren().add(row);
		}
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		box.getChildren().add(wrapGlass(scroll));
		setContent(box);
	}

	// ---- Profiles ----

	private void showProfiles() {
		VBox box = sectionBox("Profiles");
		List<ProfileData> profiles = ProfileStore.loadAll();
		VBox list = new VBox(8);
		if (profiles.isEmpty()) {
			list.getChildren().add(sectionSubtitle("No saved profiles yet - save one from the in-game panel."));
		}
		for (ProfileData profile : profiles) {
			HBox row = new HBox(10);
			row.setAlignment(Pos.CENTER_LEFT);
			Label label = new Label(profile.name() + "  (" + String.join(", ", profile.addressPatterns()) + ")"
					+ (profile.safeMode() ? "  [Safe Mode]" : ""));
			label.setTextFill(textColor());
			Button delete = new Button("Delete");
			delete.setOnAction(e -> {
				ProfileStore.delete(profile);
				showProfiles();
			});
			row.getChildren().addAll(label, delete);
			list.getChildren().add(row);
		}
		box.getChildren().add(wrapGlass(list));
		setContent(box);
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

	// ---- Theme editor ----

	private void showThemeEditor() {
		VBox box = sectionBox("Theme Editor");
		FlowPane presets = new FlowPane(8, 8);
		for (LauncherTheme preset : LauncherThemePresets.all().values()) {
			Button button = new Button(preset.name() + (preset.name().equals(theme.name()) ? " (active)" : ""));
			button.setOnAction(e -> {
				theme = preset;
				ThemeStore.save(theme);
				applyTheme();
				showThemeEditor();
			});
			presets.getChildren().add(button);
		}
		box.getChildren().add(presets);

		VBox sliders = new VBox(10);
		sliders.getChildren().add(sectionSubtitle("Corner Radius: " + theme.cornerRadius()));
		sliders.getChildren().add(slider(0, 16, theme.cornerRadius(), v -> withTheme(t -> new LauncherTheme(
				t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(), t.text(), (int) v,
				t.blurIntensity(), t.animationSpeed(), t.panelOpacity()))));

		sliders.getChildren().add(sectionSubtitle("Blur Intensity: " + theme.blurIntensity()));
		sliders.getChildren().add(slider(0, 1, theme.blurIntensity(), v -> withTheme(t -> new LauncherTheme(
				t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(), t.text(), t.cornerRadius(),
				(float) v, t.animationSpeed(), t.panelOpacity()))));

		sliders.getChildren().add(sectionSubtitle("Panel Opacity: " + theme.panelOpacity()));
		sliders.getChildren().add(slider(0, 1, theme.panelOpacity(), v -> withTheme(t -> new LauncherTheme(
				t.name(), t.background(), t.surface(), t.accentStart(), t.accentEnd(), t.text(), t.cornerRadius(),
				t.blurIntensity(), t.animationSpeed(), (float) v))));
		box.getChildren().add(wrapGlass(sliders));

		setContent(box);
	}

	private Slider slider(double min, double max, double value, java.util.function.DoubleConsumer onChange) {
		Slider slider = new Slider(min, max, value);
		slider.valueProperty().addListener((obs, oldVal, newVal) -> onChange.accept(newVal.doubleValue()));
		return slider;
	}

	private void withTheme(java.util.function.Function<LauncherTheme, LauncherTheme> mutator) {
		theme = mutator.apply(theme);
		ThemeStore.save(theme);
		applyTheme();
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
