package net.veloclient.launcher.ui;

import java.util.function.Consumer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.CurrencyStore;
import net.veloclient.launcher.data.PlayTimeStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.theme.LauncherTheme;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Your profile (the account badge's destination): a turning 3D model of your skin wearing your
 * equipped cape, your identity, and stats - time played through Velo, your most played profiles,
 * what's set up locally, and totals from Minecraft's own statistics across every singleplayer
 * world in every profile.
 */
public final class AccountProfileView {

	public interface Host {
		javafx.stage.Stage owner();

		LauncherTheme theme();

		MinecraftSession session();

		void signOut();

		void goBack();

		/** Rebuilds the page (after equipping a skin). */
		default void reload() {
		}

		/** Runs {@code action} with a session whose Minecraft token is still valid (refreshing it first if needed). */
		default void withFreshSession(java.util.function.Consumer<MinecraftSession> action) {
			action.accept(session());
		}

		default void openLocker() {
		}

		default void openReferrals() {
		}
	}

	private AccountProfileView() {
	}

	public static Node build(Host host) {
		MinecraftSession session = host.session();

		// ---- Banner: who you are, with you in 3D on the right ----
		StackPane banner = new StackPane();
		banner.setMinHeight(230);
		banner.setStyle("-fx-background-color: radial-gradient(center 78% 40%, radius 60%, -velo-accent-glow 0%, transparent 100%),"
				+ " linear-gradient(to bottom, -velo-surface-2, -velo-background); -fx-background-radius: 20; -fx-border-color: -velo-hairline; -fx-border-radius: 20;");
		StackPane stage = new StackPane(CosmeticUi.spinner(30));
		stage.setPrefSize(260, 300);
		stage.setMaxSize(260, 300);
		StackPane.setAlignment(stage, Pos.CENTER_RIGHT);
		StackPane.setMargin(stage, new Insets(0, 36, 0, 0));
		CosmeticUi.skin(session, skin -> {
			if (skin == null) {
				stage.getChildren().clear();
				return;
			}
			DressedPlayer.build(skin, true, 22, viewer -> {
				if (viewer != null) {
					stage.getChildren().setAll(viewer);
				} else {
					stage.getChildren().clear();
				}
			});
		});
		banner.getChildren().add(stage);

		Node head = Heads.head(session.uuid(), session.username(), 112);
		StackPane headBox = new StackPane(head);
		headBox.setStyle("-fx-border-color: -velo-accent-text; -fx-border-width: 2; -fx-border-radius: 26; -fx-background-radius: 26; -fx-padding: 4;");
		headBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		Label name = new Label(session.username());
		name.getStyleClass().add("t-display");
		name.setStyle("-fx-font-size: 38px;");
		HBox badges = new HBox(8);
		badges.setAlignment(Pos.CENTER_LEFT);
		if (net.veloclient.launcher.social.StoreApi.isPlusCached()) {
			javafx.scene.image.ImageView plus = new javafx.scene.image.ImageView(new javafx.scene.image.Image(
					AccountProfileView.class.getResourceAsStream("/net/veloclient/launcher/images/velo-plus-badge.png"), 54, 26, true, true));
			badges.getChildren().add(plus);
		}
		Label java = new Label("Java Edition");
		java.getStyleClass().addAll("badge", "badge-grey");
		Label microsoft = new Label("Microsoft account");
		microsoft.getStyleClass().addAll("badge", "badge-grey");
		badges.getChildren().addAll(java, microsoft);
		long since = PlayTimeStore.get().firstRecordedEpochMillis;
		Label line = new Label("Online" + (since > 0 ? "  ·  Velo player since "
				+ DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(since)) : ""));
		line.getStyleClass().add("t-body");
		Region dot = new Region();
		dot.getStyleClass().add("status-dot");
		line.setGraphic(dot);
		Button locker = new Button("Open Locker");
		locker.setGraphic(Icons.icon("locker", 15, Color.web("#E4E4E8")));
		locker.setOnAction(e -> host.openLocker());
		Button invite = new Button("Invite friends");
		invite.setGraphic(Icons.icon("gift", 15, Color.web("#2A1E02")));
		invite.getStyleClass().add("buy-button");
		invite.setOnAction(e -> host.openReferrals());
		Button copyUuid = new Button("Copy UUID");
		copyUuid.getStyleClass().add("ghost-button");
		copyUuid.setOnAction(e -> {
			ClipboardContent content = new ClipboardContent();
			content.putString(session.uuid());
			Clipboard.getSystemClipboard().setContent(content);
			Toasts.show(copyUuid, "UUID copied");
		});
		HBox actions = new HBox(10, locker, invite, copyUuid);
		VBox identity = new VBox(10, name, badges, line, actions);
		identity.setAlignment(Pos.CENTER_LEFT);
		HBox idRow = new HBox(24, headBox, identity);
		idRow.setAlignment(Pos.CENTER_LEFT);
		idRow.setPadding(new Insets(28, 32, 28, 32));
		idRow.setPickOnBounds(false);
		StackPane.setAlignment(idRow, Pos.CENTER_LEFT);
		banner.getChildren().add(idRow);
		Rectangle clip = new Rectangle();
		clip.setArcWidth(40);
		clip.setArcHeight(40);
		clip.widthProperty().bind(banner.widthProperty());
		clip.heightProperty().bind(banner.heightProperty());
		banner.setClip(clip);

		// ---- Numbers ----
		PlayTimeStore.Data play = PlayTimeStore.get();
		List<Instance> instances = InstanceStore.loadAll();
		int coins = net.veloclient.launcher.social.StoreApi.balance();
		HBox kpis = new HBox(14,
				kpi("Time played", duration(PlayTimeStore.totalMillis()), false),
				kpi("Sessions", String.valueOf(PlayTimeStore.totalSessions()), false),
				kpi("Longest session", duration(play.longestSessionMillis), false),
				kpi("Velo Coins", coins >= 0 ? StoreTiles.format(coins) : "-", true));
		kpis.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

		// ---- Most played, what you wear, connections ----
		VBox mostPlayed = buildMostPlayed();
		HBox.setHgrow(mostPlayed, Priority.ALWAYS);
		VBox showcase = showcase(host);
		VBox connections = connections(host, session);
		HBox cards = new HBox(14, mostPlayed, showcase, connections);

		// ---- Singleplayer ----
		Label worldTitle = new Label("Singleplayer");
		worldTitle.getStyleClass().add("t-title-sm");
		Label worldSub = new Label("From your worlds' Minecraft statistics");
		worldSub.getStyleClass().add("t-muted");
		HBox worldHead = new HBox(12, worldTitle, worldSub);
		worldHead.setAlignment(Pos.BASELINE_LEFT);
		FlowPane world = StoreTiles.grid(150, 12);
		Label scanning = new Label("Reading world statistics...");
		scanning.getStyleClass().add("t-muted");
		world.getChildren().add(scanning);
		CompletableFuture.supplyAsync(() -> WorldTotals.scan(instances), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(totals -> Platform.runLater(() -> {
					world.getChildren().setAll(
							statTile("Play time", duration(totals.playTicks * 50), "In-game time across all your worlds"),
							statTile("Worlds", String.valueOf(totals.worlds), null),
							statTile("Mobs killed", compact(totals.mobKills), null),
							statTile("Deaths", compact(totals.deaths), null),
							statTile("Distance", String.format(Locale.ROOT, "%.1f km", totals.distanceCm / 100_000.0),
									"Walked, sprinted, swum, flown and ridden"),
							statTile("Blocks mined", compact(totals.blocksMined), null),
							statTile("Jumps", compact(totals.jumps), null));
					UiMotion.stagger(world, 10);
				}));

		VBox root = new VBox(20, banner, kpis, cards, worldHead, world);
		root.setPadding(new Insets(0, 4, 24, 0));
		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		return scroll;
	}

	private static Node kpi(String label, String value, boolean gold) {
		Label l = new Label(label);
		l.getStyleClass().add("t-muted");
		l.setStyle("-fx-font-size: 13px;" + (gold ? " -fx-text-fill: #c9a657;" : ""));
		Label v = new Label(value);
		v.getStyleClass().add("t-title");
		if (gold) {
			v.setStyle("-fx-text-fill: -velo-gold;");
		}
		VBox box = new VBox(10, l, v);
		box.setPadding(new Insets(18));
		box.getStyleClass().add("card");
		if (gold) {
			box.setStyle("-fx-background-color: rgba(247,192,74,0.06); -fx-border-color: rgba(247,192,74,0.22);");
		}
		box.setMaxWidth(Double.MAX_VALUE);
		return box;
	}

	/** What you wear right now: cape, wings and aura. */
	private static VBox showcase(Host host) {
		Label title = new Label("Wearing");
		title.getStyleClass().add("t-heading");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Label edit = new Label("Locker");
		edit.getStyleClass().add("t-link");
		edit.setOnMouseClicked(e -> host.openLocker());
		HBox head = new HBox(title, spacer, edit);
		HBox items = new HBox(10);
		CapeEntry cape = CapeLibrary.equippedCapeId().flatMap(id -> CapeLibrary.listAll().stream().filter(c -> c.id().equals(id)).findFirst()).orElse(null);
		var wings = net.veloclient.launcher.data.WingsCatalog.equipped() == null ? null
				: net.veloclient.launcher.data.WingsCatalog.byId(net.veloclient.launcher.data.WingsCatalog.equipped()).orElse(null);
		var aura = net.veloclient.launcher.data.AuraCatalog.equipped() == null ? null
				: net.veloclient.launcher.data.AuraCatalog.byId(net.veloclient.launcher.data.AuraCatalog.equipped()).orElse(null);
		StackPane capeArt = new StackPane();
		if (cape != null) {
			CosmeticUi.framesAsync(cape, frames -> {
				if (!frames.isEmpty()) {
					javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(CosmeticUi.thumbnail("lib:" + cape.id(), frames, 120, 120));
					view.setFitHeight(80);
					view.setPreserveRatio(true);
					capeArt.getChildren().setAll(view);
				}
			});
		}
		items.getChildren().add(worn(capeArt, cape == null ? "No cape" : cape.name()));
		javafx.scene.Node wingsArt = wings == null ? new Region() : new javafx.scene.image.ImageView(new javafx.scene.image.Image(
				net.veloclient.launcher.data.WingsCatalog.openPreview(wings.id()), 0, 74, true, true));
		items.getChildren().add(worn(wingsArt, wings == null ? "No wings" : wings.name()));
		javafx.scene.Node auraArt = aura == null ? new Region() : new javafx.scene.image.ImageView(
				AuraFx.snapshot(aura.kind(), net.veloclient.launcher.data.AuraCatalog.settings(aura.id()), 104, 80));
		items.getChildren().add(worn(auraArt, aura == null ? "No aura" : aura.name()));
		VBox box = new VBox(12, head, items);
		box.getStyleClass().add("card");
		box.setPadding(new Insets(18));
		box.setMinWidth(360);
		return box;
	}

	private static Node worn(javafx.scene.Node art, String name) {
		StackPane artBox = new StackPane(art);
		artBox.setMinSize(104, 90);
		artBox.setMaxSize(104, 90);
		artBox.setStyle("-fx-background-color: -velo-surface-2; -fx-background-radius: 10;");
		Label label = new Label(name);
		label.getStyleClass().add("t-strong");
		label.setStyle("-fx-font-size: 12px;");
		label.setMaxWidth(104);
		return new VBox(6, artBox, label);
	}

	/** Microsoft session, Discord link and sign out. */
	private static VBox connections(Host host, MinecraftSession session) {
		Label title = new Label("Connections");
		title.getStyleClass().add("t-heading");
		String expiry = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(session.accessTokenExpiresAtEpochMillis()));
		Node microsoft = connection("MS", "#2F6FD6", "Microsoft", session.isAccessTokenExpired() ? "Refreshes on next launch" : "Session valid until " + expiry, true);
		Label discordStatus = new Label("Checking...");
		discordStatus.getStyleClass().add("t-muted");
		HBox discord = (HBox) connection("D", "#5865F2", "Discord", "", false);
		((VBox) discord.getChildren().get(1)).getChildren().set(1, discordStatus);
		CompletableFuture.supplyAsync(() -> {
			try {
				return net.veloclient.launcher.social.StoreApi.discordStatus();
			} catch (net.veloclient.launcher.social.StoreApi.StoreError e) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result != null && result.has("linked") && result.get("linked").getAsBoolean()) {
				discordStatus.setText("Linked as " + result.get("discordName").getAsString());
				discordStatus.setStyle("-fx-text-fill: -velo-green;");
			} else {
				discordStatus.setText(result == null ? "Not available" : "Not linked - link it in the Store");
			}
		}));
		Region grow = new Region();
		VBox.setVgrow(grow, Priority.ALWAYS);
		Button signOut = new Button("Sign out");
		signOut.getStyleClass().addAll("danger-button", "button-sm");
		signOut.setMaxWidth(Double.MAX_VALUE);
		signOut.setOnAction(e -> {
			Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
			confirm.initOwner(host.owner());
			confirm.setTitle("Sign out");
			confirm.setHeaderText("Sign out of " + session.username() + "?");
			DialogStyling.apply(confirm);
			confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> host.signOut());
		});
		VBox box = new VBox(14, title, microsoft, discord, grow, signOut);
		box.getStyleClass().add("card");
		box.setPadding(new Insets(18));
		box.setMinWidth(260);
		box.setPrefWidth(280);
		return box;
	}

	private static Node connection(String letters, String color, String name, String status, boolean ok) {
		Label badge = new Label(letters);
		badge.setMinSize(32, 32);
		badge.setAlignment(Pos.CENTER);
		badge.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 8; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px;");
		Label title = new Label(name);
		title.getStyleClass().add("t-strong");
		Label sub = new Label(status);
		sub.setStyle(ok ? "-fx-text-fill: -velo-green; -fx-font-size: 12px;" : "-fx-text-fill: -velo-text-3; -fx-font-size: 12px;");
		HBox row = new HBox(10, badge, new VBox(2, title, sub));
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private static VBox buildMostPlayed() {
		Label title = new Label("Most played");
		title.getStyleClass().add("t-heading");
		VBox box = new VBox(12, title);
		box.getStyleClass().add("card");
		box.setPadding(new Insets(18));
		box.setMinWidth(240);
		Map<String, Long> byProfile = PlayTimeStore.get().millisByProfile;
		List<Instance> instances = InstanceStore.loadAll();
		List<Instance> ranked = instances.stream()
				.filter(i -> byProfile.getOrDefault(i.id(), 0L) > 0)
				.sorted(Comparator.comparingLong((Instance i) -> byProfile.getOrDefault(i.id(), 0L)).reversed())
				.limit(4).toList();
		if (ranked.isEmpty()) {
			Label empty = new Label("Play a profile and it shows up here.");
			empty.getStyleClass().add("page-subtitle");
			empty.setWrapText(true);
			box.getChildren().add(empty);
			return box;
		}
		long top = byProfile.get(ranked.get(0).id());
		for (Instance instance : ranked) {
			long millis = byProfile.get(instance.id());
			Label name = new Label(instance.name());
			name.getStyleClass().add("settings-row-label");
			Label time = new Label(duration(millis));
			time.getStyleClass().add("settings-row-hint");
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			HBox line = new HBox(name, spacer, time);
			Region bar = new Region();
			bar.setStyle("-fx-background-color: -velo-accent; -fx-background-radius: 3;");
			bar.setMinHeight(5);
			bar.setPrefHeight(5);
			bar.setPrefWidth(Math.max(8, 260.0 * millis / top));
			bar.setMaxWidth(Region.USE_PREF_SIZE);
			Region track = new Region();
			StackPane barTrack = new StackPane(bar);
			barTrack.setAlignment(Pos.CENTER_LEFT);
			barTrack.setStyle("-fx-background-color: -velo-surface-3; -fx-background-radius: 3;");
			barTrack.setMaxHeight(5);
			box.getChildren().add(new VBox(6, line, barTrack));
		}
		return box;
	}

	private static VBox statTile(String label, String value, String tooltip) {
		Label valueLabel = new Label(value);
		valueLabel.getStyleClass().add("stat-value");
		Label labelLabel = new Label(label);
		labelLabel.getStyleClass().add("stat-label");
		VBox tile = new VBox(2, valueLabel, labelLabel);
		tile.getStyleClass().addAll("stat-tile", "motion-card");
		tile.setPrefWidth(150);
		if (tooltip != null) {
			Tooltip.install(tile, new Tooltip(tooltip));
		}
		return tile;
	}

	private static long countFiles(Path dir, String suffix) {
		try (Stream<Path> stream = Files.list(dir)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
		} catch (Exception e) {
			return 0;
		}
	}

	static String duration(long millis) {
		long minutes = millis / 60_000;
		if (minutes < 60) {
			return minutes + "m";
		}
		long hours = minutes / 60;
		return hours < 100 ? hours + "h " + (minutes % 60) + "m" : hours + "h";
	}

	private static String compact(long n) {
		if (n >= 1_000_000) {
			return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
		}
		if (n >= 10_000) {
			return String.format(Locale.ROOT, "%.1fK", n / 1000.0);
		}
		return String.valueOf(n);
	}

	/** Sums Minecraft's per-world statistics files (saves/<world>/stats/*.json) over every profile. */
	private static final class WorldTotals {
		long playTicks;
		long deaths;
		long mobKills;
		long distanceCm;
		long blocksMined;
		long jumps;
		int worlds;

		static WorldTotals scan(List<Instance> instances) {
			WorldTotals totals = new WorldTotals();
			for (Instance instance : instances) {
				try (Stream<Path> saves = Files.list(InstancePaths.savesDir(instance.id()))) {
					for (Path world : saves.filter(Files::isDirectory).toList()) {
						totals.worlds++;
						try (Stream<Path> stats = Files.list(world.resolve("stats"))) {
							stats.filter(p -> p.toString().endsWith(".json")).forEach(totals::add);
						} catch (Exception ignored) {
							// World never played long enough to write stats.
						}
					}
				} catch (Exception ignored) {
					// Profile without saves.
				}
			}
			return totals;
		}

		private void add(Path file) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonObject stats = root.has("stats") ? root.getAsJsonObject("stats") : null;
				if (stats == null) {
					return;
				}
				JsonObject custom = stats.has("minecraft:custom") ? stats.getAsJsonObject("minecraft:custom") : new JsonObject();
				playTicks += get(custom, "minecraft:play_time") + get(custom, "minecraft:play_one_minute");
				deaths += get(custom, "minecraft:deaths");
				mobKills += get(custom, "minecraft:mob_kills");
				jumps += get(custom, "minecraft:jump");
				for (String key : custom.keySet()) {
					if (key.endsWith("_one_cm") && !key.contains("fall")) {
						distanceCm += custom.get(key).getAsLong();
					}
				}
				if (stats.has("minecraft:mined")) {
					for (var entry : stats.getAsJsonObject("minecraft:mined").entrySet()) {
						blocksMined += entry.getValue().getAsLong();
					}
				}
			} catch (Exception ignored) {
				// Corrupt/unknown stats file - skip.
			}
		}

		private static long get(JsonObject object, String key) {
			return object.has(key) ? object.get(key).getAsLong() : 0;
		}
	}
}
