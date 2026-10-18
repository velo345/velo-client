package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import net.veloclient.launcher.data.ClientImport;
import net.veloclient.launcher.instance.BuiltinIcons;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceIcon;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.launch.GameVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * "Switch to Velo Client": a three-step page - pick the launcher you came from (the ones found on this PC light up), pick what
 * to bring, pick where it goes - then one button. Same work as {@link ClientImport}, just friendly for someone new.
 */
public final class ClientImportView {

	/**
	 * Each supported client: its logo ({@code clients/<logo>.png}, the client's own icon - only used to say which launcher
	 * you're importing from) with a colour and letter as the fallback.
	 */
	private record Brand(String client, String logo, String letter, String color) {
	}

	private static final List<Brand> BRANDS = List.of(
			new Brand("Minecraft Launcher", "minecraft", "M", "#5ba33b"),
			new Brand("Lunar Client", "lunar", "L", "#6c74ff"),
			new Brand("Feather Client", "feather", "F", "#2fb7f5"),
			new Brand("NoRisk Client", "norisk", "N", "#ef4a52"),
			new Brand("Modrinth App", "modrinth", "M", "#1bd96a"),
			new Brand("Prism / MultiMC", "prism", "P", "#b07cff"),
			new Brand("CurseForge", "curseforge", "C", "#f16436"),
			// Tiles for these only show up when found.
			new Brand("LabyMod", "labymod", "L", "#2f6dff"),
			new Brand("ATLauncher", "atlauncher", "A", "#4aa3df"),
			new Brand("GDLauncher", "gdlauncher", "G", "#e8a33d"),
			new Brand("FTB App", "ftb", "F", "#d63b3b"),
			new Brand("Technic", "technic", "T", "#e2462f"),
			new Brand("Other launchers", "other", "+", "#7a7f8c"));
	/** The always-shown tiles (the rest only when found). */
	private static final int CORE_BRANDS = 7;

	/** The client's logo at {@code size} px, or its letter in a coloured circle. */
	static Node brandLogo(String client, double size) {
		Brand brand = BRANDS.stream().filter(b -> b.client().equals(client)).findFirst().orElse(null);
		if (brand != null) {
			var url = ClientImportView.class.getResource("/net/veloclient/launcher/clients/" + brand.logo() + ".png");
			if (url != null) {
				javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(new javafx.scene.image.Image(url.toExternalForm(), size * 2, size * 2, true, true));
				view.setFitWidth(size);
				view.setFitHeight(size);
				view.setSmooth(true);
				return view;
			}
		}
		StackPane logo = new StackPane(new Circle(size / 2, Color.web(brand == null ? "#ff4d4d" : brand.color())));
		Label letter = new Label(brand == null ? client.substring(0, 1) : brand.letter());
		letter.setStyle("-fx-font-size: " + Math.round(size * 0.45) + "px; -fx-font-weight: 900; -fx-text-fill: white;");
		logo.getChildren().add(letter);
		return logo;
	}

	// Material icons (Apache 2.0), 24 x 24.
	private static final String ICON_KEYS = "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z";
	private static final String ICON_SERVERS = "M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z";
	private static final String ICON_MODS = "M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z";
	private static final String ICON_CONFIG = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z";
	private static final String ICON_PACKS = "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z";
	private static final String ICON_SHADERS = "M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z";
	private static final String ICON_WORLDS = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z";
	private static final String ICON_CHECK = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";

	private enum Part { SETTINGS, SERVERS, MODS, CONFIGS, PACKS, SHADERS, WORLDS }

	private ClientImportView() {
	}

	/**
	 * @param installLoaderJars puts Velo Client + Fabric API into a freshly created profile
	 * @param openProfile shows a profile when the import is done
	 */
	public static Node build(Runnable onBack, Consumer<Instance> installLoaderJars, Consumer<Instance> openProfile) {
		Label title = new Label("Switch to Velo Client");
		title.getStyleClass().add("page-title");
		Label subtitle = new Label("Bring your keybinds, servers and mods along in a few clicks.");
		subtitle.getStyleClass().add("page-subtitle");
		HBox header = new HBox(14, BackButton.create("Home", onBack), new VBox(2, title, subtitle));
		header.setAlignment(Pos.CENTER_LEFT);

		VBox steps = new VBox(16);
		Label scanning = new Label("Looking for your other launchers...");
		scanning.getStyleClass().add("page-subtitle");
		steps.getChildren().addAll(scanning, Skeleton.grid(7, 132, 128));

		VBox root = new VBox(20, header, steps);
		root.setPadding(new Insets(4, 4, 24, 4));
		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);

		CompletableFuture.supplyAsync(ClientImport::detect, Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(sources -> Platform.runLater(() -> new Flow(steps, sources, installLoaderJars, openProfile).start()));
		return wrapper;
	}

	/** The page's state as you click through it. */
	private static final class Flow {
		final VBox steps;
		final List<ClientImport.Source> sources;
		final Consumer<Instance> installLoaderJars;
		final Consumer<Instance> openProfile;
		ClientImport.Source chosen;
		final Map<Part, Boolean> picked = new LinkedHashMap<>();
		VBox whatCard;
		VBox whereCard;

		Flow(VBox steps, List<ClientImport.Source> sources, Consumer<Instance> installLoaderJars, Consumer<Instance> openProfile) {
			this.steps = steps;
			this.sources = sources;
			this.installLoaderJars = installLoaderJars;
			this.openProfile = openProfile;
		}

		void start() {
			FlowPane tiles = new FlowPane(12, 12);
			// The ones on this PC first.
			List<Brand> ordered = new ArrayList<>(BRANDS);
			ordered.sort(java.util.Comparator.comparing(b -> sources.stream().noneMatch(s -> s.client().equals(b.client()))));
			for (Brand brand : ordered) {
				List<ClientImport.Source> mine = sources.stream().filter(s -> s.client().equals(brand.client())).toList();
				if (mine.isEmpty() && BRANDS.indexOf(brand) >= CORE_BRANDS) {
					continue;
				}
				tiles.getChildren().add(clientTile(brand, mine));
			}
			VBox card = card("1", "Where are you coming from?", tiles);
			if (sources.isEmpty()) {
				Label none = hint("We didn't find another launcher with anything to bring over on this PC.");
				card.getChildren().add(none);
			}
			steps.getChildren().setAll(card);
			UiMotion.stagger(tiles, 10);
			if (System.getProperty("velo.launcherTour") != null && !sources.isEmpty()) {
				// Screenshot tour: show the whole page with the first setup picked.
				choose(sources.get(sources.size() - 1));
			}
		}

		Node clientTile(Brand brand, List<ClientImport.Source> mine) {
			boolean found = !mine.isEmpty();
			Node logo = brandLogo(brand.client(), 48);
			if (!found && logo instanceof javafx.scene.image.ImageView image) {
				image.setEffect(new javafx.scene.effect.ColorAdjust(0, -0.85, -0.1, 0));
			}
			Label name = new Label(brand.client());
			name.setStyle("-fx-font-weight: 700; -fx-text-fill: white; -fx-font-size: 12px;");
			name.setWrapText(true);
			name.setAlignment(Pos.CENTER);
			Label status = new Label(found ? "Found" : "Not on this PC");
			status.setStyle(found ? "-fx-background-color: rgba(70,210,120,0.18); -fx-text-fill: #6fe39a; -fx-background-radius: 8; -fx-padding: 1 8 1 8;"
					+ " -fx-font-size: 10px; -fx-font-weight: 800;" : "-fx-text-fill: rgba(255,255,255,0.4); -fx-font-size: 10px;");
			VBox content = new VBox(8, logo, name, status);
			content.setAlignment(Pos.CENTER);
			Button tile = new Button();
			tile.setGraphic(content);
			tile.setPrefSize(140, 128);
			tile.setStyle(tileStyle(false, found));
			tile.setDisable(!found);
			if (!found) {
				tile.setOpacity(0.45);
			}
			tile.setOnMouseEntered(e -> scale(tile, found ? 1.05 : 1));
			tile.setOnMouseExited(e -> scale(tile, 1));
			tile.setOnAction(e -> {
				tile.getParent().getChildrenUnmodifiable().forEach(n -> {
					if (n instanceof Button b && !b.isDisabled()) {
						b.setStyle(tileStyle(false, true));
					}
				});
				tile.setStyle(tileStyle(true, true));
				pickSource(mine);
			});
			return tile;
		}

		void pickSource(List<ClientImport.Source> mine) {
			if (mine.size() == 1) {
				choose(mine.get(0));
				return;
			}
			// Several setups of that launcher (versions, instances): pick one.
			FlowPane chips = new FlowPane(8, 8);
			for (ClientImport.Source source : mine) {
				String loader = source.loader() != null && !source.loader().equals("fabric") ? "  ·  " + capitalize(source.loader()) : "";
				Button chip = new Button(source.name() + (source.mcVersion() != null && !source.name().contains(source.mcVersion())
						? "  ·  " + source.mcVersion() : "") + loader + (source.modCount() > 0 ? "  ·  " + source.modCount() + " mods" : ""));
				chip.setStyle(chipStyle(false));
				chip.setOnAction(e -> {
					chips.getChildren().forEach(n -> n.setStyle(chipStyle(false)));
					chip.setStyle(chipStyle(true));
					choose(source);
				});
				chips.getChildren().add(chip);
			}
			VBox card = (VBox) steps.getChildren().get(0);
			card.getChildren().removeIf(n -> "chips".equals(n.getId()));
			chips.setId("chips");
			card.getChildren().add(chips);
			UiMotion.enter(chips);
			steps.getChildren().retainAll(card);
		}

		static String capitalize(String text) {
			return text.equals("neoforge") ? "NeoForge" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
		}

		void choose(ClientImport.Source source) {
			chosen = source;
			picked.clear();
			FlowPane options = new FlowPane(10, 10);
			addOption(options, Part.SETTINGS, ICON_KEYS, "Keybinds & settings", source.hasOptions() ? "" : null, true);
			addOption(options, Part.SERVERS, ICON_SERVERS, "Servers", source.hasServers() ? "" : null, true);
			addOption(options, Part.MODS, ICON_MODS, "Mods", source.modCount() > 0 ? String.valueOf(source.modCount()) : null, true);
			addOption(options, Part.CONFIGS, ICON_CONFIG, "Mod settings", source.hasConfig() ? "" : null, true);
			addOption(options, Part.PACKS, ICON_PACKS, "Resource packs", source.resourcePackCount() > 0 ? String.valueOf(source.resourcePackCount()) : null, true);
			addOption(options, Part.SHADERS, ICON_SHADERS, "Shaders", source.shaderPackCount() > 0 ? String.valueOf(source.shaderPackCount()) : null, true);
			addOption(options, Part.WORLDS, ICON_WORLDS, "Worlds", source.worldCount() > 0 ? String.valueOf(source.worldCount()) : null, false);
			whatCard = card("2", "What should come along?", options);
			if (picked.containsKey(Part.MODS)) {
				whatCard.getChildren().add(hint("Mods are downloaded again in the right version for your new profile."));
			}
			buildWhere();
			steps.getChildren().setAll(steps.getChildren().get(0), whatCard, whereCard);
			UiMotion.enter(whatCard);
			UiMotion.enter(whereCard);
			UiMotion.stagger(options, 8);
		}

		void addOption(FlowPane pane, Part part, String icon, String label, String count, boolean byDefault) {
			if (count == null) {
				return;
			}
			picked.put(part, byDefault);
			SVGPath glyph = new SVGPath();
			glyph.setContent(icon);
			glyph.setScaleX(1.1);
			glyph.setScaleY(1.1);
			StackPane bubble = new StackPane(glyph);
			bubble.setMinSize(40, 40);
			bubble.setMaxSize(40, 40);
			Label text = new Label(label);
			text.setStyle("-fx-font-weight: 700; -fx-text-fill: white;");
			text.setMinWidth(Region.USE_PREF_SIZE);
			Label number = new Label(count);
			number.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 11px;");
			VBox words = new VBox(1, text, number);
			words.setAlignment(Pos.CENTER_LEFT);
			if (count.isEmpty()) {
				words.getChildren().remove(number);
			}
			SVGPath tick = new SVGPath();
			tick.setContent(ICON_CHECK);
			tick.setScaleX(0.7);
			tick.setScaleY(0.7);
			Region grow = new Region();
			HBox.setHgrow(grow, Priority.ALWAYS);
			HBox content = new HBox(10, bubble, words, grow, tick);
			content.setAlignment(Pos.CENTER_LEFT);
			Button toggle = new Button();
			toggle.setGraphic(content);
			toggle.setPrefWidth(250);
			toggle.setMinHeight(58);
			Runnable paint = () -> {
				boolean on = picked.get(part);
				toggle.setStyle(tileStyle(on, true) + " -fx-alignment: center-left; -fx-padding: 8 12 8 10;");
				bubble.setStyle("-fx-background-radius: 12; -fx-background-color: " + (on ? "rgba(255,77,77,0.25)" : "rgba(255,255,255,0.06)") + ";");
				glyph.setFill(on ? Color.web("#ff8080") : Color.web("#ffffff", 0.6));
				tick.setFill(Color.web("#ff6b6b"));
				tick.setVisible(on);
			};
			paint.run();
			toggle.setOnAction(e -> {
				picked.put(part, !picked.get(part));
				paint.run();
				scale(toggle, 0.96);
				javafx.animation.PauseTransition back = new javafx.animation.PauseTransition(Duration.millis(90));
				back.setOnFinished(ev -> scale(toggle, 1));
				back.play();
			});
			pane.getChildren().add(toggle);
		}

		void buildWhere() {
			Button fresh = segment("New profile", true);
			Button existing = segment("A profile I have", false);
			HBox switcher = new HBox(0, fresh, existing);
			switcher.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 12; -fx-padding: 3;");
			switcher.setMaxWidth(Region.USE_PREF_SIZE);

			TextField name = new TextField("From " + chosen.client());
			name.setPrefWidth(240);
			ComboBox<String> version = new ComboBox<>();
			for (GameVersion v : GameVersion.values()) {
				version.getItems().add(v.id());
			}
			version.setValue(chosen.mcVersion() != null && version.getItems().contains(chosen.mcVersion()) ? chosen.mcVersion() : GameVersion.MC_26_2.id());
			HBox newRow = new HBox(10, labeled("Name", name), labeled("Minecraft", version));
			ComboBox<Instance> profiles = new ComboBox<>();
			profiles.getItems().addAll(InstanceStore.loadAll());
			profiles.setConverter(new javafx.util.StringConverter<>() {
				@Override
				public String toString(Instance i) {
					return i == null ? "" : i.name() + "  ·  " + i.mcVersion();
				}

				@Override
				public Instance fromString(String s) {
					return null;
				}
			});
			profiles.setPrefWidth(300);
			if (!profiles.getItems().isEmpty()) {
				profiles.setValue(profiles.getItems().get(0));
			}
			VBox existingRow = new VBox(labeled("Profile", profiles));
			StackPane target = new StackPane(newRow);
			target.setAlignment(Pos.TOP_LEFT);
			boolean[] useNew = {true};
			fresh.setOnAction(e -> {
				useNew[0] = true;
				fresh.setStyle(segmentStyle(true));
				existing.setStyle(segmentStyle(false));
				target.getChildren().setAll(newRow);
				UiMotion.enter(newRow);
			});
			existing.setOnAction(e -> {
				useNew[0] = false;
				fresh.setStyle(segmentStyle(false));
				existing.setStyle(segmentStyle(true));
				target.getChildren().setAll(existingRow);
				UiMotion.enter(existingRow);
			});
			existing.setDisable(profiles.getItems().isEmpty());

			Button go = new Button("Bring it over");
			go.getStyleClass().add("primary-button");
			go.setStyle("-fx-font-size: 15px; -fx-padding: 12 34 12 34;");
			go.setOnAction(e -> {
				Instance instance;
				if (useNew[0]) {
					String profileName = name.getText().isBlank() ? "From " + chosen.client() : name.getText().strip();
					instance = InstanceStore.createNew(profileName, version.getValue(), InstanceIcon.builtin(BuiltinIcons.DEFAULT));
					installLoaderJars.accept(instance);
				} else if (profiles.getValue() != null) {
					instance = profiles.getValue();
				} else {
					return;
				}
				run(instance);
			});
			whereCard = card("3", "Where should it go?", new VBox(14, switcher, target, go));
		}

		void run(Instance instance) {
			ClientImport.Options options = new ClientImport.Options(on(Part.SETTINGS), on(Part.SERVERS), on(Part.MODS), on(Part.CONFIGS),
					on(Part.PACKS), on(Part.SHADERS), on(Part.WORLDS));
			ProgressBar bar = new ProgressBar();
			bar.setPrefWidth(420);
			Label status = hint("Getting started...");
			VBox working = new VBox(12, bar, status);
			whereCard.getChildren().setAll(whereCard.getChildren().get(0), working);
			UiMotion.enter(working);
			steps.getChildren().get(0).setDisable(true);
			whatCard.setDisable(true);
			CompletableFuture.supplyAsync(() -> ClientImport.importInto(chosen, options, instance.id(), instance.mcVersion(),
					text -> Platform.runLater(() -> status.setText(text))), Executors.newVirtualThreadPerTaskExecutor())
					.thenAccept(summary -> Platform.runLater(() -> done(instance, summary)));
		}

		void done(Instance instance, String summary) {
			SVGPath tick = new SVGPath();
			tick.setContent(ICON_CHECK);
			tick.setFill(Color.WHITE);
			tick.setScaleX(1.6);
			tick.setScaleY(1.6);
			Circle disc = new Circle(30, Color.web("#3fbf6f"));
			StackPane badge = new StackPane(disc, tick);
			Label title = new Label("All set!");
			title.getStyleClass().add("page-title");
			Label text = hint(summary + " Into \"" + instance.name() + "\".");
			Button open = new Button("Open profile");
			open.getStyleClass().add("primary-button");
			open.setOnAction(e -> openProfile.accept(instance));
			VBox box = new VBox(12, badge, title, text, open);
			box.setAlignment(Pos.CENTER);
			box.setPadding(new Insets(30));
			box.getStyleClass().add("settings-card");
			steps.getChildren().setAll(box);
			badge.setScaleX(0.2);
			badge.setScaleY(0.2);
			ScaleTransition pop = new ScaleTransition(Duration.millis(420), badge);
			pop.setToX(1);
			pop.setToY(1);
			pop.setInterpolator(javafx.animation.Interpolator.SPLINE(0.3, 1.6, 0.5, 1));
			FadeTransition fade = new FadeTransition(Duration.millis(260), box);
			fade.setFromValue(0);
			fade.setToValue(1);
			new ParallelTransition(pop, fade).play();
		}

		boolean on(Part part) {
			return Boolean.TRUE.equals(picked.get(part));
		}
	}

	// ---- small pieces ----

	private static VBox card(String number, String heading, Node body) {
		Label step = new Label(number);
		step.setMinSize(28, 28);
		step.setAlignment(Pos.CENTER);
		step.setStyle("-fx-background-color: linear-gradient(to bottom right, #ff4d4d, #b71c1c); -fx-background-radius: 14; -fx-text-fill: white;"
				+ " -fx-font-weight: 900;");
		Label title = new Label(heading);
		title.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: white;");
		HBox top = new HBox(10, step, title);
		top.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(14, top, body);
		card.getStyleClass().add("settings-card");
		card.setPadding(new Insets(18));
		return card;
	}

	private static Label hint(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("settings-row-hint");
		label.setWrapText(true);
		return label;
	}

	private static VBox labeled(String text, Node control) {
		Label label = new Label(text);
		label.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 11px;");
		return new VBox(4, label, control);
	}

	private static String tileStyle(boolean selected, boolean enabled) {
		return "-fx-background-radius: 16; -fx-border-radius: 16; -fx-cursor: " + (enabled ? "hand" : "default") + "; -fx-background-color: "
				+ (selected ? "rgba(255,77,77,0.16)" : "rgba(255,255,255,0.05)") + "; -fx-border-color: "
				+ (selected ? "rgba(255,90,90,0.85)" : "rgba(255,255,255,0.08)") + "; -fx-border-width: " + (selected ? 2 : 1) + ";";
	}

	private static String chipStyle(boolean selected) {
		return "-fx-background-radius: 20; -fx-padding: 6 14 6 14; -fx-cursor: hand; -fx-text-fill: white; -fx-background-color: "
				+ (selected ? "rgba(255,77,77,0.35)" : "rgba(255,255,255,0.07)") + ";";
	}

	private static Button segment(String text, boolean selected) {
		Button button = new Button(text);
		button.setStyle(segmentStyle(selected));
		return button;
	}

	private static String segmentStyle(boolean selected) {
		return "-fx-background-radius: 10; -fx-padding: 7 16 7 16; -fx-cursor: hand; -fx-font-weight: 700; -fx-text-fill: "
				+ (selected ? "white" : "rgba(255,255,255,0.6)") + "; -fx-background-color: " + (selected ? "linear-gradient(to bottom right, #ff4d4d, #b71c1c)" : "transparent") + ";";
	}

	private static void scale(Node node, double to) {
		ScaleTransition t = new ScaleTransition(Duration.millis(140), node);
		t.setToX(to);
		t.setToY(to);
		t.play();
	}
}
