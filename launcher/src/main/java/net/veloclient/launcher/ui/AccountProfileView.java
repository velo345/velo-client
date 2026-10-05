package net.veloclient.launcher.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.geometry.Pos;
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
	}

	private AccountProfileView() {
	}

	public static Node build(Host host) {
		MinecraftSession session = host.session();

		Button back = new Button("‹  Back");
		back.getStyleClass().add("ghost-button");
		back.setOnAction(e -> host.goBack());

		// ---- Hero: 3D model + identity ----
		StackPane stage = CosmeticUi.stage(240, 320);
		stage.getStyleClass().add("showcase-stage");
		Label loading = new Label("Loading skin...");
		loading.getStyleClass().add("page-subtitle");
		stage.getChildren().add(loading);
		CapeEntry equipped = CapeLibrary.equippedCapeId()
				.flatMap(id -> CapeLibrary.listAll().stream().filter(c -> c.id().equals(id)).findFirst()).orElse(null);
		CosmeticUi.skin(session, skin -> {
			var frames = equipped == null ? null : CosmeticUi.frames(equipped);
			Node viewer = skin == null ? null : PlayerSkin3DView.createViewer(skin.pngBytes(), skin.slim(),
					frames == null || frames.isEmpty() ? null : frames, true, 25, true);
			if (viewer != null) {
				stage.getChildren().setAll(viewer);
			} else {
				loading.setText("Couldn't load your skin.");
			}
		});

		Label name = new Label(session.username());
		name.getStyleClass().add("profile-name");
		Label accountChip = CosmeticUi.badge("Microsoft account", null);
		Label javaChip = CosmeticUi.badge("Java Edition", null);
		HBox chips = new HBox(6, accountChip, javaChip);

		Label uuid = new Label(session.uuid());
		uuid.getStyleClass().add("profile-uuid");
		Button copyUuid = new Button("Copy");
		copyUuid.getStyleClass().add("ghost-button");
		copyUuid.setOnAction(e -> {
			ClipboardContent content = new ClipboardContent();
			content.putString(session.uuid());
			Clipboard.getSystemClipboard().setContent(content);
			copyUuid.setText("Copied");
		});
		HBox uuidRow = new HBox(6, uuid, copyUuid);
		uuidRow.setAlignment(Pos.CENTER_LEFT);

		String expiry = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(session.accessTokenExpiresAtEpochMillis()));
		Label sessionLine = new Label(session.isAccessTokenExpired()
				? "Session refreshes automatically on next launch"
				: "Signed in  ·  session valid until " + expiry);
		sessionLine.getStyleClass().add("page-subtitle");

		Label capeLine = new Label("Cape: " + (equipped == null ? "none" : equipped.name()));
		capeLine.getStyleClass().add("page-subtitle");

		Button signOut = new Button("Sign out");
		signOut.getStyleClass().add("danger-ghost-button");
		signOut.setOnAction(e -> {
			Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
			confirm.initOwner(host.owner());
			confirm.setTitle("Sign out");
			confirm.setHeaderText("Sign out of " + session.username() + "?");
			DialogStyling.apply(confirm);
			confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> host.signOut());
		});

		VBox identity = new VBox(10, name, chips, uuidRow, sessionLine, capeLine, signOut);
		identity.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(identity, Priority.ALWAYS);

		// Most played (launcher-tracked) next to the identity.
		VBox mostPlayed = buildMostPlayed();
		HBox hero = new HBox(24, stage, identity, mostPlayed);
		hero.setAlignment(Pos.CENTER_LEFT);
		hero.getStyleClass().add("profile-hero");

		// ---- Stats ----
		PlayTimeStore.Data play = PlayTimeStore.get();
		List<Instance> instances = InstanceStore.loadAll();
		long mods = instances.stream().mapToLong(i -> countFiles(InstancePaths.modsDir(i.id()), ".jar")).sum();
		FlowPane velo = new FlowPane(12, 12,
				statTile("Time played", duration(PlayTimeStore.totalMillis()), "Time in game launched through Velo"),
				statTile("Sessions", String.valueOf(PlayTimeStore.totalSessions()), null),
				statTile("Longest session", duration(play.longestSessionMillis), null),
				statTile("Profiles", String.valueOf(instances.size()), null),
				statTile("Mods installed", String.valueOf(mods), "Across all profiles, including Velo's own"),
				statTile("Capes", String.valueOf(CapeLibrary.listAll().size()), null),
				statTile("Velo Coins", String.valueOf(CurrencyStore.balance()), null));
		Label veloTitle = new Label("Velo");
		veloTitle.getStyleClass().add("section-label");

		Label worldTitle = new Label("Singleplayer  ·  from your worlds' Minecraft statistics");
		worldTitle.getStyleClass().add("section-label");
		FlowPane world = new FlowPane(12, 12);
		Label scanning = new Label("Reading world statistics...");
		scanning.getStyleClass().add("page-subtitle");
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
		UiMotion.stagger(velo, 10);

		Label skinsTitle = new Label("Skins");
		skinsTitle.getStyleClass().add("section-label");
		VBox root = new VBox(18, back, hero, skinsTitle, SkinsSection.build(host), veloTitle, velo, worldTitle, world);
		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		return scroll;
	}

	private static VBox buildMostPlayed() {
		Label title = new Label("Most played");
		title.getStyleClass().add("section-label");
		VBox box = new VBox(10, title);
		box.getStyleClass().add("profile-side-card");
		box.setMinWidth(230);
		box.setMaxWidth(260);
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
			bar.getStyleClass().add("stat-bar");
			bar.setPrefWidth(Math.max(8, 220.0 * millis / top));
			bar.setMaxWidth(Region.USE_PREF_SIZE);
			box.getChildren().add(new VBox(4, line, bar));
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
