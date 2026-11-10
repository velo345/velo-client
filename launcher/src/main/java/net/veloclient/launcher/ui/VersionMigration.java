package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import net.veloclient.launcher.data.InstalledAsset;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.launch.GameVersion;
import net.veloclient.launcher.modrinth.ModrinthClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Moves a whole profile to another Minecraft version - up (26.2 -> 26.3, 1.21.11 -> 26.3) or down (26.2 -> 1.21.11).
 * Every mod, resource pack and shader is looked up on Modrinth for the target first, and the result is shown before
 * anything changes: what moves (old -> new version), what doesn't exist there yet, what can't be checked. Then it
 * either makes a copy (the old profile stays as it is) or changes this profile; settings, servers and keybinds come
 * along. Mods missing on the target are kept, disabled, so they come back once their authors update.
 */
final class VersionMigration {

	private VersionMigration() {
	}

	/** One installed file and what happens to it on the target. */
	private record Item(InstalledAssetStore.Kind kind, Path file, InstalledAsset asset, boolean disabled, ModrinthClient.ProjectVersion target,
			boolean checked) {
		String title() {
			return asset != null ? asset.title() : file.getFileName().toString().replace(".disabled", "");
		}

		boolean moves() {
			return target != null;
		}

		/** Resource and shader packs work across versions: without a newer download they simply come along as they are. */
		boolean pack() {
			return kind != InstalledAssetStore.Kind.MOD;
		}
	}

	private record Plan(String targetVersion, List<Item> items, List<ModrinthClient.ProjectVersion> extraDependencies) {
	}

	static void show(StackPane overlayHost, Instance instance, Runnable onDone) {
		VBox card = new VBox(14);
		card.getStyleClass().addAll("glass-panel", "modal-card", "migrate-card");
		card.setMaxWidth(620);
		card.setPrefWidth(620);
		card.setMaxHeight(640);
		// never taller than the window (minus a margin), so the buttons stay reachable
		card.maxHeightProperty().bind(javafx.beans.binding.Bindings.min(640, overlayHost.heightProperty().subtract(60)));
		StackPane backdrop = new StackPane(card);
		backdrop.getStyleClass().add("modal-backdrop");
		Runnable close = () -> {
			FadeTransition out = new FadeTransition(Duration.millis(160), backdrop);
			out.setToValue(0);
			out.setOnFinished(e -> overlayHost.getChildren().remove(backdrop));
			out.play();
		};
		backdrop.setOnMouseClicked(e -> {
			if (e.getTarget() == backdrop && !Boolean.TRUE.equals(card.getProperties().get("busy"))) {
				close.run();
			}
		});
		overlayHost.getChildren().add(backdrop);
		backdrop.setOpacity(0);
		FadeTransition in = new FadeTransition(Duration.millis(180), backdrop);
		in.setToValue(1);
		in.play();
		ScaleTransition pop = new ScaleTransition(Duration.millis(260), card);
		pop.setFromX(0.96);
		pop.setFromY(0.96);
		pop.setToX(1);
		pop.setToY(1);
		pop.setInterpolator(Interpolator.SPLINE(0.16, 1, 0.3, 1));
		pop.play();
		pickStep(card, instance, close, onDone);
	}

	// ---- Step 1: where to ----

	private static void pickStep(VBox card, Instance instance, Runnable close, Runnable onDone) {
		Label title = heading("Change Minecraft version");
		Label sub = muted("Move \"" + instance.name() + "\" with its mods, packs and settings. You'll see what works before anything changes.");
		FlowPane targets = new FlowPane(10, 10);
		int currentIndex = indexOf(instance.mcVersion());
		GameVersion[] all = GameVersion.values();
		for (int i = all.length - 1; i >= 0; i--) {
			GameVersion version = all[i];
			if (version.id().equals(instance.mcVersion())) {
				continue;
			}
			boolean up = i > currentIndex;
			VBox tile = new VBox(4);
			tile.getStyleClass().add("migrate-target");
			Label number = new Label(version.id());
			number.getStyleClass().add("migrate-target-version");
			Label kind = new Label(up ? "Upgrade" : "Downgrade");
			kind.getStyleClass().addAll("migrate-target-kind", up ? "migrate-up" : "migrate-down");
			// the version's picture on top of the tile
			tile.getChildren().add(VersionArt.tile(version.id(), false, VersionArt.TILE_W));
			tile.getChildren().addAll(kind);
			if (i == all.length - 1) {
				Label newest = new Label("Newest");
				newest.getStyleClass().addAll("version-tag-pill", "version-tag-newest");
				tile.getChildren().add(newest);
			}
			tile.setOnMouseClicked(e -> analyzeStep(card, instance, version.id(), close, onDone));
			targets.getChildren().add(tile);
		}
		Button cancel = new Button("Cancel");
		cancel.setOnAction(e -> close.run());
		// The version tiles scroll when they don't fit (they were cut off with no way down).
		ScrollPane scroll = new ScrollPane(targets);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("migrate-scroll");
		scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
		VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
		card.getChildren().setAll(title, sub, scroll, buttons(cancel));
		UiMotion.stagger(targets, 8);
	}

	// ---- Step 2: what works there ----

	private static void analyzeStep(VBox card, Instance instance, String target, Runnable close, Runnable onDone) {
		Label title = heading(instance.mcVersion() + "  →  " + target);
		Label status = muted("Checking every mod and pack on Modrinth...");
		ProgressBar progress = new ProgressBar(0);
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.getStyleClass().add("migrate-progress");
		card.getChildren().setAll(title, status, progress, Skeleton.list(5));
		card.getProperties().put("busy", true);
		CompletableFuture.supplyAsync(() -> analyze(instance, target, fraction -> Platform.runLater(() -> progress.setProgress(fraction))),
				Executors.newVirtualThreadPerTaskExecutor()).whenComplete((plan, error) -> Platform.runLater(() -> {
					card.getProperties().remove("busy");
					if (plan == null) {
						status.setText("Couldn't check the mods (" + (error == null ? "unknown error" : error.getMessage()) + ").");
						Button back = new Button("Back");
						back.setOnAction(e -> pickStep(card, instance, close, onDone));
						card.getChildren().setAll(title, status, buttons(back));
						return;
					}
					reviewStep(card, instance, plan, close, onDone);
				}));
	}

	private static Plan analyze(Instance instance, String target, java.util.function.DoubleConsumer progress) {
		List<Item> items = new ArrayList<>();
		Map<InstalledAssetStore.Kind, Path> folders = new LinkedHashMap<>();
		folders.put(InstalledAssetStore.Kind.MOD, InstancePaths.modsDir(instance.id()));
		folders.put(InstalledAssetStore.Kind.RESOURCE_PACK, InstancePaths.resourcePacksDir(instance.id()));
		folders.put(InstalledAssetStore.Kind.SHADER_PACK, InstancePaths.shaderPacksDir(instance.id()));
		List<Object[]> todo = new ArrayList<>();
		folders.forEach((kind, folder) -> {
			Map<String, InstalledAsset> known = InstalledAssetStore.asMap(InstalledAssetStore.loadAll(instance.id(), kind));
			try (Stream<Path> files = Files.list(folder)) {
				// Packs can be unpacked folders too (most self-made ones are) - those used to be left behind.
				for (Path file : files.filter(p -> Files.isRegularFile(p) || kind != InstalledAssetStore.Kind.MOD && Files.isDirectory(p)).sorted().toList()) {
					String name = file.getFileName().toString();
					String enabled = name.endsWith(".disabled") ? name.substring(0, name.length() - ".disabled".length()) : name;
					if (kind == InstalledAssetStore.Kind.MOD && (enabled.startsWith("velo-client-") || enabled.startsWith("fabric-api-")
							|| enabled.startsWith("velo-addons-"))) {
						continue; // the launcher puts the right ones in for the target
					}
					todo.add(new Object[] {kind, file, known.get(enabled), name.endsWith(".disabled")});
				}
			} catch (IOException ignored) {
				// Empty folder.
			}
		});
		AtomicInteger done = new AtomicInteger();
		// A few lookups at a time - Modrinth answers quickly, 100 mods shouldn't take 100 round trips in a row.
		List<CompletableFuture<Item>> futures = new ArrayList<>();
		java.util.concurrent.Semaphore limit = new java.util.concurrent.Semaphore(6);
		for (Object[] t : todo) {
			futures.add(CompletableFuture.supplyAsync(() -> {
				InstalledAssetStore.Kind kind = (InstalledAssetStore.Kind) t[0];
				Path file = (Path) t[1];
				InstalledAsset asset = (InstalledAsset) t[2];
				boolean disabled = (Boolean) t[3];
				try {
					limit.acquire();
					if (asset == null) {
						// Not on Modrinth (or never identified): packs work across versions as they are, mods can't be checked.
						return new Item(kind, file, null, disabled, null, false);
					}
					List<ModrinthClient.ProjectVersion> versions = VersionDropdown.versionsFor(asset.projectId(), target, kind);
					return new Item(kind, file, asset, disabled, versions.isEmpty() ? null : versions.get(0), true);
				} catch (Exception e) {
					return new Item(kind, file, asset, disabled, null, false);
				} finally {
					limit.release();
					progress.accept(done.incrementAndGet() / (double) Math.max(1, todo.size()));
				}
			}, Executors.newVirtualThreadPerTaskExecutor()));
		}
		for (CompletableFuture<Item> f : futures) {
			items.add(f.join());
		}
		// Required dependencies of the new versions that nothing in the profile provides yet.
		Set<String> planned = new HashSet<>();
		for (Item item : items) {
			if (item.moves()) {
				planned.add(item.target().projectId());
			}
		}
		List<ModrinthClient.ProjectVersion> extra = new ArrayList<>();
		for (Item item : items) {
			if (!item.moves() || item.kind() != InstalledAssetStore.Kind.MOD) {
				continue;
			}
			for (ModrinthClient.Dependency dep : item.target().requiredDependencies()) {
				if (dep.projectId() == null || !planned.add(dep.projectId()) || "P7dR8mSH".equals(dep.projectId())) {
					continue; // already coming along (P7dR8mSH = Fabric API, which the launcher manages)
				}
				try {
					List<ModrinthClient.ProjectVersion> versions = VersionDropdown.versionsFor(dep.projectId(), target, InstalledAssetStore.Kind.MOD);
					if (!versions.isEmpty()) {
						extra.add(versions.get(0));
					}
				} catch (Exception ignored) {
					// The Doctor reports a missing dependency on the first start.
				}
			}
		}
		return new Plan(target, items, extra);
	}

	// ---- Step 3: review and go ----

	private static void reviewStep(VBox card, Instance instance, Plan plan, Runnable close, Runnable onDone) {
		boolean downgrade = indexOf(plan.targetVersion()) < indexOf(instance.mcVersion());
		List<Item> moving = plan.items().stream().filter(Item::moves).toList();
		List<Item> missing = plan.items().stream().filter(i -> !i.moves() && i.checked() && !i.pack()).toList();
		List<Item> unchecked = plan.items().stream().filter(i -> !i.moves() && !i.checked() && !i.pack()).toList();
		List<Item> packsAsIs = plan.items().stream().filter(i -> !i.moves() && i.pack()).toList();
		long mods = plan.items().stream().filter(i -> i.kind() == InstalledAssetStore.Kind.MOD).count();
		long modsMoving = moving.stream().filter(i -> i.kind() == InstalledAssetStore.Kind.MOD).count();

		Label title = heading(instance.mcVersion() + "  →  " + plan.targetVersion());
		Label summary = muted(mods == 0 ? "No mods to move - packs and settings come along."
				: modsMoving + " of " + mods + " mods are available for " + plan.targetVersion()
						+ (plan.extraDependencies().isEmpty() ? "" : ", plus " + plan.extraDependencies().size() + " new dependencies they need") + ".");

		VBox groups = new VBox(14);
		if (!missing.isEmpty()) {
			groups.getChildren().add(group("Not available for " + plan.targetVersion() + " yet", "migrate-missing", "warning", "#f7c04a", missing,
					item -> item.kind() == InstalledAssetStore.Kind.MOD ? "kept, disabled" : "kept as is"));
		}
		if (!unchecked.isEmpty()) {
			groups.getChildren().add(group("Can't be checked (not on Modrinth)", "migrate-unknown", "info", "#A9A9B3", unchecked,
					item -> item.kind() == InstalledAssetStore.Kind.MOD ? "kept, disabled" : "kept as is"));
		}
		if (!packsAsIs.isEmpty()) {
			groups.getChildren().add(group("Coming along as they are", "migrate-ok", "check", "#7dd3a0", packsAsIs,
					item -> "pack works on " + plan.targetVersion()));
		}
		if (!moving.isEmpty()) {
			groups.getChildren().add(group("Moving", "migrate-ok", "check", "#7dd3a0", moving,
					item -> (item.asset() == null ? "" : "v" + item.asset().versionNumber() + "  →  ") + "v" + item.target().versionNumber()));
		}
		ScrollPane scroll = new ScrollPane(groups);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		ToggleGroup mode = new ToggleGroup();
		RadioButton copy = new RadioButton("Make a copy for " + plan.targetVersion() + " (recommended - this profile stays as it is)");
		RadioButton inPlace = new RadioButton("Change this profile");
		copy.setToggleGroup(mode);
		inPlace.setToggleGroup(mode);
		copy.setSelected(true);
		CheckBox worlds = new CheckBox(downgrade ? "Copy worlds too (opening a world in an older version can break it - the originals stay safe)"
				: "Copy worlds too");
		worlds.setWrapText(true);
		worlds.disableProperty().bind(inPlace.selectedProperty());
		Label worldNote = muted(downgrade ? "Changing this profile backs its worlds up first - an older version can break newer worlds." : "");
		worldNote.visibleProperty().bind(inPlace.selectedProperty());
		worldNote.managedProperty().bind(inPlace.selectedProperty());
		VBox options = new VBox(8, copy, worlds, inPlace, worldNote);
		options.getStyleClass().add("migrate-options");

		Button back = new Button("Back");
		back.setOnAction(e -> pickStep(card, instance, close, onDone));
		Button go = new Button((downgrade ? "Downgrade to " : "Move to ") + plan.targetVersion());
		go.getStyleClass().add("primary-button");
		go.setOnAction(e -> runStep(card, instance, plan, copy.isSelected(), worlds.isSelected(), close, onDone));
		card.getChildren().setAll(title, summary, scroll, options, buttons(back, go));
		UiMotion.stagger(groups, 6);
	}

	private static Node group(String heading, String style, String icon, String color, List<Item> items, java.util.function.Function<Item, String> note) {
		Label head = new Label(heading + "  ·  " + items.size());
		head.setGraphic(Icons.icon(icon, 14, Color.web(color), 2.2));
		head.getStyleClass().add("migrate-group-title");
		VBox rows = new VBox(2);
		for (Item item : items) {
			Label name = new Label(item.title());
			name.getStyleClass().add("migrate-row-name");
			Label kind = new Label(switch (item.kind()) {
				case MOD -> "Mod";
				case RESOURCE_PACK -> "Pack";
				case SHADER_PACK -> "Shader";
			});
			kind.getStyleClass().add("migrate-row-kind");
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			Label right = new Label(note.apply(item));
			right.getStyleClass().add("migrate-row-note");
			HBox row = new HBox(8, kind, name, spacer, right);
			row.setAlignment(Pos.CENTER_LEFT);
			row.getStyleClass().add("migrate-row");
			rows.getChildren().add(row);
		}
		VBox box = new VBox(6, head, rows);
		box.getStyleClass().addAll("migrate-group", style);
		return box;
	}

	// ---- Step 4: do it ----

	private static void runStep(VBox card, Instance instance, Plan plan, boolean copy, boolean copyWorlds, Runnable close, Runnable onDone) {
		Label title = heading((copy ? "Making your " : "Moving to ") + plan.targetVersion() + (copy ? " copy" : ""));
		Label status = muted("Starting...");
		ProgressBar progress = new ProgressBar(0);
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.getStyleClass().add("migrate-progress");
		card.getChildren().setAll(title, status, progress);
		card.getProperties().put("busy", true);
		CompletableFuture.supplyAsync(() -> {
			try {
				return execute(instance, plan, copy, copyWorlds, (text, fraction) -> Platform.runLater(() -> {
					status.setText(text);
					progress.setProgress(fraction);
				}));
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).whenComplete((result, error) -> Platform.runLater(() -> {
			card.getProperties().remove("busy");
			if (error != null) {
				status.setText("Something went wrong: " + error.getMessage() + (copy ? " - your original profile is untouched." : ""));
				Button ok = new Button("Close");
				ok.setOnAction(e -> close.run());
				card.getChildren().setAll(title, status, buttons(ok));
				return;
			}
			doneStep(card, result, plan, close, onDone);
		}));
	}

	private static Instance execute(Instance source, Plan plan, boolean copy, boolean copyWorlds,
			java.util.function.BiConsumer<String, Double> status) throws IOException {
		Instance target;
		if (copy) {
			status.accept("Creating the profile...", 0.02);
			target = InstanceStore.createNew(source.name() + " (" + plan.targetVersion() + ")", plan.targetVersion(), source.icon());
			target = target.withSettings(source.ramMinMb(), source.ramMaxMb(), source.extraJvmArgs());
			InstanceStore.save(target);
			Path from = InstancePaths.gameDir(source.id());
			Path to = InstancePaths.gameDir(target.id());
			status.accept("Copying settings, servers and keybinds...", 0.05);
			copyTree(from.resolve("config"), to.resolve("config"));
			for (String file : new String[] {"options.txt", "servers.dat", "optionsof.txt", "optionsshaders.txt"}) {
				copyFile(from.resolve(file), to.resolve(file));
			}
			if (copyWorlds) {
				status.accept("Copying worlds...", 0.08);
				copyTree(InstancePaths.savesDir(source.id()), InstancePaths.savesDir(target.id()));
			}
			Path icon = InstancePaths.iconFile(source.id());
			copyFile(icon, InstancePaths.iconFile(target.id()));
		} else {
			target = new Instance(source.id(), source.name(), plan.targetVersion(), source.icon(), source.createdAtEpochMillis(),
					source.ramMinMb(), source.ramMaxMb(), source.extraJvmArgs());
			if (indexOf(plan.targetVersion()) < indexOf(source.mcVersion())) {
				status.accept("Backing up worlds...", 0.05);
				copyTree(InstancePaths.savesDir(source.id()),
						InstancePaths.dir(source.id()).resolve("backups").resolve("saves-" + source.mcVersion() + "-" + System.currentTimeMillis()));
			}
		}
		List<Item> items = plan.items();
		int total = items.size() + plan.extraDependencies().size();
		int done = 0;
		for (Item item : items) {
			done++;
			Path targetFolder = folderOf(target, item.kind());
			Files.createDirectories(targetFolder);
			String fileName = item.file().getFileName().toString();
			String enabledName = fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - ".disabled".length()) : fileName;
			status.accept((item.moves() ? "Downloading " : "Keeping ") + item.title() + "...", 0.1 + 0.88 * done / Math.max(1, total));
			if (item.moves()) {
				String oldName = copy ? null : enabledName;
				if (!copy && item.disabled()) {
					// Back to its enabled name so the update replaces it cleanly - and stays disabled afterwards.
					Files.move(item.file(), item.file().resolveSibling(enabledName), StandardCopyOption.REPLACE_EXISTING);
				}
				InstanceDetailView.downloadAndRecord(target, item.kind(), targetFolder, item.target(), oldName, f -> { });
				if (item.disabled()) {
					item.target().primaryFile().ifPresent(f -> disable(targetFolder.resolve(f.filename())));
				}
			} else if (item.kind() == InstalledAssetStore.Kind.MOD) {
				// Made for the old version: kept, but off - it comes back on once there's an update.
				Path off = targetFolder.resolve(enabledName + ".disabled");
				if (copy) {
					Files.copy(item.file(), off, StandardCopyOption.REPLACE_EXISTING);
				} else if (!item.disabled()) {
					Files.move(item.file(), off, StandardCopyOption.REPLACE_EXISTING);
				}
				if (item.asset() != null) {
					InstalledAssetStore.record(target.id(), item.kind(), item.asset());
				}
			} else if (copy) {
				if (Files.isDirectory(item.file())) {
					copyTree(item.file(), targetFolder.resolve(fileName));
				} else {
					Files.copy(item.file(), targetFolder.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
				}
				if (item.asset() != null) {
					InstalledAssetStore.record(target.id(), item.kind(), item.asset());
				}
			}
		}
		for (ModrinthClient.ProjectVersion dep : plan.extraDependencies()) {
			done++;
			status.accept("Adding a dependency...", 0.1 + 0.88 * done / Math.max(1, total));
			InstanceDetailView.downloadAndRecord(target, InstalledAssetStore.Kind.MOD, InstancePaths.modsDir(target.id()), dep, null, f -> { });
		}
		if (!copy) {
			// The launcher's own jars for the old version go; the right ones are put in at the next start.
			try (Stream<Path> files = Files.list(InstancePaths.modsDir(target.id()))) {
				for (Path file : files.filter(p -> {
					String n = p.getFileName().toString();
					return n.startsWith("velo-client-") || n.startsWith("fabric-api-") || n.startsWith("velo-addons-");
				}).toList()) {
					Files.deleteIfExists(file);
				}
			}
			InstanceStore.save(target);
		}
		status.accept("Done", 1.0);
		return target;
	}

	private static void doneStep(VBox card, Instance result, Plan plan, Runnable close, Runnable onDone) {
		StackPane badge = new StackPane(Icons.icon("check", 34, Color.web("#7dd3a0"), 3));
		badge.getStyleClass().add("migrate-done-badge");
		badge.setScaleX(0);
		badge.setScaleY(0);
		ScaleTransition pop = new ScaleTransition(Duration.millis(420), badge);
		pop.setToX(1);
		pop.setToY(1);
		pop.setInterpolator(UiMotion.BACK_OUT);
		Label title = heading("\"" + result.name() + "\" is on " + plan.targetVersion());
		long off = plan.items().stream().filter(i -> !i.moves() && i.kind() == InstalledAssetStore.Kind.MOD).count();
		Label note = muted(off == 0 ? "Everything came along." : off + (off == 1 ? " mod is" : " mods are")
				+ " disabled until " + (off == 1 ? "it's" : "they're") + " updated - you'll find them in the Mods list.");
		VBox center = new VBox(12, badge, title, note);
		center.setAlignment(Pos.CENTER);
		center.setPadding(new Insets(16, 0, 8, 0));
		Button ok = new Button("Done");
		ok.getStyleClass().add("primary-button");
		ok.setOnAction(e -> {
			close.run();
			onDone.run();
		});
		card.getChildren().setAll(center, buttons(ok));
		TranslateTransition rise = new TranslateTransition(Duration.millis(360), center);
		rise.setFromY(12);
		rise.setToY(0);
		rise.setInterpolator(UiMotion.EASE_OUT);
		new ParallelTransition(pop, rise).play();
	}

	// ---- Helpers ----

	private static Path folderOf(Instance instance, InstalledAssetStore.Kind kind) {
		return switch (kind) {
			case MOD -> InstancePaths.modsDir(instance.id());
			case RESOURCE_PACK -> InstancePaths.resourcePacksDir(instance.id());
			case SHADER_PACK -> InstancePaths.shaderPacksDir(instance.id());
		};
	}

	private static void disable(Path file) {
		try {
			if (Files.exists(file)) {
				Files.move(file, file.resolveSibling(file.getFileName() + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException ignored) {
			// Stays enabled.
		}
	}

	private static int indexOf(String version) {
		GameVersion[] all = GameVersion.values();
		for (int i = 0; i < all.length; i++) {
			if (all[i].id().equals(version)) {
				return i;
			}
		}
		return -1;
	}

	private static void copyFile(Path from, Path to) throws IOException {
		if (Files.isRegularFile(from)) {
			Files.createDirectories(to.getParent());
			Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		if (!Files.isDirectory(from)) {
			return;
		}
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path dest = to.resolve(from.relativize(file).toString());
				Files.createDirectories(dest.getParent());
				Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static Label heading(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("migrate-title");
		label.setWrapText(true);
		return label;
	}

	private static Label muted(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("t-muted");
		label.setWrapText(true);
		return label;
	}

	private static HBox buttons(Button... buttons) {
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox row = new HBox(8, spacer);
		row.getChildren().addAll(buttons);
		row.setAlignment(Pos.CENTER_RIGHT);
		return row;
	}
}
