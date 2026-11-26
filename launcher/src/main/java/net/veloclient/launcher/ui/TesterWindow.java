package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.social.TesterApi;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The Velo Client Tester's own window (owner 2026-11-24, replaces the Testing tab): opens with the Tester launcher,
 * behind it.
 * <ul>
 * <li><b>Test plan</b> - the build's test items as a checklist, one after the other: Works / Broken / Skip. Broken opens a
 * report (text, screenshots, short videos); launcher, game and update logs always go with it.</li>
 * <li><b>Report</b> - bugs and suggestions for anything not on the list, same flow.</li>
 * <li><b>Logs</b> - the launcher, game and update logs live, with copy and export.</li>
 * <li><b>Results</b> (owner) - every report from every tester with its logs and files, and each tester's progress.</li>
 * </ul>
 */
public final class TesterWindow {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
	private static final long LOG_TAIL = 1_500_000;

	private static Stage stage;
	private static String themeStyle = "";
	private static final StackPane content = new StackPane();
	private static final List<Button> navButtons = new ArrayList<>();
	private static boolean owner;
	private static Timeline logTimer;

	private TesterWindow() {
	}

	/** Opens (or shows) the window behind the launcher's own. FX thread. */
	public static void open(Stage launcher, String style) {
		themeStyle = style == null ? "" : style;
		if (stage != null) {
			stage.show();
			return;
		}
		stage = new Stage();
		stage.setTitle("Velo Client Tester - Testing");
		try {
			stage.getIcons().addAll(launcher.getIcons());
		} catch (RuntimeException ignored) {
			// no icon
		}
		BorderPane root = new BorderPane();
		root.setStyle(themeStyle);
		root.getStyleClass().add("tw-root");   // 2026-11-26: the launcher's calm grey + glass cards, no picture
		root.setLeft(nav());
		content.setPadding(new Insets(22, 26, 22, 26));
		root.setCenter(content);
		Scene scene = new Scene(root, 1120, 800);
		scene.getStylesheets().add(TesterWindow.class.getResource("/net/veloclient/launcher/launcher.css").toExternalForm());
		stage.setScene(scene);
		stage.setMinWidth(860);
		stage.setMinHeight(600);
		// behind the launcher: shown first, then the launcher comes back to the front
		if (launcher.getX() > 40) {
			stage.setX(Math.max(0, launcher.getX() - 60));
			stage.setY(Math.max(0, launcher.getY() + 40));
		}
		stage.setOnHidden(e -> stopLogs());
		stage.show();
		launcher.toFront();
		LauncherLog.info("[tester] Tester window opened");
		String tourPage = System.getProperty("velo.tourTesterPage");
		show(tourPage != null ? tourPage : "plan");
		String tourShot = System.getProperty("velo.tourTesterShot");
		if (tourShot != null) {
			// dev (screenshot tours): a picture of this window after it settled
			javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(javafx.util.Duration.millis(900));
			later.setOnFinished(e -> {
				try {
					javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
							java.nio.file.Path.of(tourShot).toFile());
				} catch (Exception ex) {
					LauncherLog.warn("[tester] tour shot failed", ex);
				}
			});
			later.play();
		}
		CompletableFuture.runAsync(() -> {
			try {
				JsonObject me = TesterApi.me();
				boolean isOwner = me.has("owner") && me.get("owner").getAsBoolean();
				Platform.runLater(() -> {
					owner = isOwner;
					if (isOwner) {
						navButtons.stream().filter(b -> "results".equals(b.getUserData())).forEach(b -> {
							b.setVisible(true);
							b.setManaged(true);
						});
					}
				});
			} catch (TesterApi.TesterError ignored) {
				// not connected yet - the pages say so
			}
		});
	}

	public static void toFront() {
		if (stage != null) {
			stage.show();
			stage.toFront();
		}
	}

	// ------------------------------------------------------------------ frame

	private static Node nav() {
		VBox nav = new VBox(4);
		nav.getStyleClass().add("tw-nav");
		nav.setPrefWidth(214);
		Label brand = new Label("Velo Tester");
		brand.getStyleClass().add("tw-brand");
		Label version = new Label("BUILD " + AppVersion.VERSION);
		version.getStyleClass().add("tw-build");
		VBox head = new VBox(3, brand, version);
		head.setPadding(new Insets(2, 10, 18, 10));
		nav.getChildren().add(head);
		nav.getChildren().addAll(navButton("plan", "Test plan", "check"), navButton("report", "Report a bug / idea", "bug"),
				navButton("mine", "My reports", "message"), navButton("known", "Known issues", "list"), navButton("logs", "Logs", "terminal"), navButton("results", "Results (owner)", "chart"));
		navButtons.stream().filter(b -> "results".equals(b.getUserData())).forEach(b -> {
			b.setVisible(false);
			b.setManaged(false);
		});
		return nav;
	}

	private static Button navButton(String key, String text, String icon) {
		Button b = new Button(text);
		b.setUserData(key);
		b.setMaxWidth(Double.MAX_VALUE);
		b.setAlignment(Pos.CENTER_LEFT);
		b.getStyleClass().setAll("button", "tw-nav-item");
		try {
			b.setGraphic(Icons.icon(icon, 15, javafx.scene.paint.Color.web("#C9C2C4")));
			b.setGraphicTextGap(11);
		} catch (RuntimeException ignored) {
			// unknown icon name
		}
		b.setOnAction(e -> show(key));
		navButtons.add(b);
		return b;
	}

	private static void styleNav(Button b, boolean active) {
		b.getStyleClass().remove("tw-nav-active");
		if (active) {
			b.getStyleClass().add("tw-nav-active");
		}
	}

	private static void show(String key) {
		LauncherLog.info("[tester] page " + key);
		navButtons.forEach(b -> styleNav(b, key.equals(b.getUserData())));
		stopLogs();
		Node page = switch (key) {
			case "report" -> reportPage(null, null, () -> show("mine"));
			case "mine" -> minePage();
			case "known" -> knownPage();
			case "logs" -> logsPage();
			case "results" -> resultsPage();
			default -> planPage();
		};
		content.getChildren().setAll(page);
	}

	private static Label title(String text) {
		Label l = new Label(text);
		l.getStyleClass().add("tw-title");
		return l;
	}

	private static Label muted(String text) {
		Label l = new Label(text);
		l.setWrapText(true);
		l.setStyle("-fx-text-fill: -velo-text-2; -fx-font-size: 13px;");
		return l;
	}

	/** Test items opened by a click (the rest stay one compact row). */
	private static final java.util.Set<String> EXPANDED = new java.util.HashSet<>();

	private static VBox card() {
		VBox c = new VBox(8);
		c.getStyleClass().add("tw-card");
		return c;
	}

	private static Button button(String text, boolean primary) {
		Button b = new Button(text);
		b.getStyleClass().add(primary ? "primary-button" : "ghost-button");
		return b;
	}

	private static ScrollPane scroll(Node node) {
		ScrollPane sp = new ScrollPane(node);
		sp.setFitToWidth(true);
		sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
		return sp;
	}

	// ------------------------------------------------------------------ test plan

	private static Node planPage() {
		VBox page = new VBox(14);
		page.getChildren().addAll(title("Test plan"), muted("Loading this build's test plan..."));
		CompletableFuture.runAsync(() -> {
			try {
				JsonObject me = TesterApi.me();
				JsonArray builds = TesterApi.builds();
				Platform.runLater(() -> renderPlan(page, me, builds));
			} catch (TesterApi.TesterError e) {
				boolean preview = java.nio.file.Files.isRegularFile(net.veloclient.launcher.data.VeloPaths.root().resolve("tester-plan-preview.json"));
				Platform.runLater(() -> {
					if (preview) {
						renderPlan(page, new JsonObject(), new JsonArray());   // the local preview works offline too
					} else {
						page.getChildren().setAll(title("Test plan"), muted("Couldn't load the test plan: " + e.getMessage()));
					}
				});
			}
		});
		return scroll(page);
	}

	private static void renderPlan(VBox page, JsonObject me, JsonArray builds) {
		page.getChildren().setAll(title("Test plan"));
		JsonObject build = null;
		for (JsonElement b : builds) {
			JsonObject o = b.getAsJsonObject();
			if (build == null || o.get("version").getAsString().equals(AppVersion.VERSION)) {
				build = o;
				if (o.get("version").getAsString().equals(AppVersion.VERSION)) {
					break;
				}
			}
		}
		// Preview of the next build's plan (owner's machine only): <data folder>/tester-plan-preview.json
		java.nio.file.Path preview = net.veloclient.launcher.data.VeloPaths.root().resolve("tester-plan-preview.json");
		if (java.nio.file.Files.isRegularFile(preview)) {
			try {
				JsonObject notes = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(preview)).getAsJsonObject();
				JsonObject fake = new JsonObject();
				fake.addProperty("version", notes.has("version") ? notes.get("version").getAsString() : "next");
				fake.add("notes", notes);
				build = fake;
				Label tag = new Label("PREVIEW - from " + preview.getFileName() + " on this PC only");
				tag.getStyleClass().add("tw-next");
				page.getChildren().add(tag);
			} catch (Exception e) {
				LauncherLog.warn("[tester] plan preview unreadable: " + e.getMessage(), null);
			}
		}
		if (build == null || !build.has("notes")) {
			page.getChildren().add(muted("No test plan for this build yet."));
			return;
		}
		String version = build.get("version").getAsString();
		JsonObject notes = build.getAsJsonObject("notes");
		JsonObject feedback = answers(me, version);
		if (notes.has("title")) {
			Label headline = new Label(notes.get("title").getAsString());
			headline.getStyleClass().add("tw-headline");
			page.getChildren().add(headline);
		}
		JsonArray items = notes.has("test") ? notes.getAsJsonArray("test") : new JsonArray();
		// progress: a bar and what was answered how
		VBox progressCard = card();
		progressCard.getStyleClass().add("tw-progress-card");
		Label progress = new Label();
		progress.getStyleClass().add("tw-progress-text");
		Label counts = new Label();
		counts.getStyleClass().add("tw-progress-counts");
		Region grow0 = new Region();
		HBox.setHgrow(grow0, Priority.ALWAYS);
		HBox progressHead = new HBox(10, progress, grow0, counts);
		progressHead.setAlignment(Pos.CENTER_LEFT);
		StackPane track = new StackPane();
		track.getStyleClass().add("tw-track");
		Region fill = new Region();
		fill.getStyleClass().add("tw-track-fill");
		StackPane.setAlignment(fill, Pos.CENTER_LEFT);
		track.getChildren().add(fill);
		progressCard.getChildren().addAll(progressHead, track);
		page.getChildren().add(progressCard);
		VBox list = new VBox(10);
		page.getChildren().add(list);
		List<String> states = new ArrayList<>();
		for (JsonElement e : items) {
			JsonObject item = e.getAsJsonObject();
			String id = item.get("id").getAsString();
			states.add(feedback.has(id) ? feedback.getAsJsonObject(id).get("state").getAsString() : null);
		}
		java.util.Set<Integer> reopened = new java.util.HashSet<>();
		Runnable[] redraw = new Runnable[1];
		redraw[0] = () -> {
			list.getChildren().clear();
			int done = 0;
			int works = 0;
			int broken = 0;
			int skipped = 0;
			int current = -1;
			for (int i = 0; i < items.size(); i++) {
				String st = states.get(i);
				if (st != null) {
					done++;
					works += st.equals("works") ? 1 : 0;
					broken += st.equals("broken") ? 1 : 0;
					skipped += st.equals("skip") ? 1 : 0;
				} else if (current < 0) {
					current = i;
				}
			}
			progress.setText(done + " of " + items.size() + " checked" + (current < 0 && !items.isEmpty() ? " - all done, thank you!" : ""));
			counts.setText(works + " work  ·  " + broken + " broken  ·  " + skipped + " skipped");
			double share = items.isEmpty() ? 0 : done / (double) items.size();
			fill.maxWidthProperty().bind(track.widthProperty().multiply(share));
			fill.minWidthProperty().bind(track.widthProperty().multiply(share));
			String lastGroup = null;
			for (int i = 0; i < items.size(); i++) {
				JsonObject item = items.get(i).getAsJsonObject();
				String group = item.has("group") ? item.get("group").getAsString() : null;
				if (group != null && !group.equals(lastGroup)) {
					int inGroup = 0;
					int doneInGroup = 0;
					for (int k = 0; k < items.size(); k++) {
						JsonObject other = items.get(k).getAsJsonObject();
						if (other.has("group") && other.get("group").getAsString().equals(group)) {
							inGroup++;
							doneInGroup += states.get(k) != null ? 1 : 0;
						}
					}
					Label g = new Label(group.toUpperCase(Locale.ROOT));
					g.getStyleClass().add("tw-group");
					Label gc = new Label(doneInGroup + " / " + inGroup);
					gc.getStyleClass().add("tw-group-count");
					HBox gh = new HBox(10, g, gc);
					gh.setAlignment(Pos.CENTER_LEFT);
					gh.setPadding(new Insets(list.getChildren().isEmpty() ? 2 : 14, 0, 0, 2));
					list.getChildren().add(gh);
					lastGroup = group;
				}
				list.getChildren().add(testCard(version, item, i, states, i == current, reopened, redraw[0]));
			}
		};
		redraw[0].run();
	}

	/**
	 * What this tester answered for {@code version}'s test plan, including answers from earlier builds of the same release
	 * (1.6.0.1 -> 1.6.0.2): a new build adds points to the list, the ones already answered stay answered (owner 2026-11-26).
	 */
	private static JsonObject answers(JsonObject me, String version) {
		JsonObject all = new JsonObject();
		if (!me.has("mine") || !me.get("mine").isJsonObject()) {
			return all;
		}
		int dot = version.lastIndexOf('.');
		String release = dot > 0 ? version.substring(0, dot + 1) : version;
		java.util.TreeMap<Integer, JsonObject> builds = new java.util.TreeMap<>();
		for (var entry : me.getAsJsonObject("mine").entrySet()) {
			String other = entry.getKey();
			if (!other.startsWith(release) || !other.substring(release.length()).matches("\\d{1,4}")) {
				continue;
			}
			int number = Integer.parseInt(other.substring(release.length()));
			JsonObject stat = entry.getValue().getAsJsonObject();
			if (other.compareTo(version) == 0 || dot > 0 && number < buildNumber(version, release)) {
				if (stat.has("feedback") && stat.get("feedback").isJsonObject()) {
					builds.put(number, stat.getAsJsonObject("feedback"));
				}
			}
		}
		for (JsonObject feedback : builds.values()) { // oldest first: a newer answer wins
			feedback.entrySet().forEach(e -> all.add(e.getKey(), e.getValue()));
		}
		return all;
	}

	private static int buildNumber(String version, String release) {
		String number = version.startsWith(release) ? version.substring(release.length()) : "";
		return number.matches("\\d{1,4}") ? Integer.parseInt(number) : 0;
	}

	private static Node testCard(String version, JsonObject item, int index, List<String> states, boolean current,
			java.util.Set<Integer> reopened, Runnable redraw) {
		VBox c = card();
		c.getStyleClass().add("tw-test");
		String id = item.get("id").getAsString();
		String titleText = item.has("title") ? item.get("title").getAsString() : id;
		String state = states.get(index);
		boolean open = state == null || reopened.contains(index);
		// less to read (owner 2026-11-26): only the next item (or one you clicked) shows its instructions and buttons
		boolean expanded = open && (current || reopened.contains(index) || EXPANDED.contains(id));
		if (open && !expanded) {
			c.getStyleClass().add("tw-test-closed");
			c.setOnMouseClicked(e -> {
				EXPANDED.add(id);
				redraw.run();
			});
		}
		if (current) {
			c.getStyleClass().add("tw-test-current");
		}
		if (!open) {
			c.getStyleClass().add("tw-test-done");
		}
		Label number = new Label(String.valueOf(index + 1));
		number.getStyleClass().add("tw-number");
		if (state != null) {
			number.getStyleClass().add("tw-number-" + state);
		}
		Label t = new Label(titleText);
		t.getStyleClass().add("tw-test-title");
		HBox head = new HBox(12, number, t);
		head.setAlignment(Pos.CENTER_LEFT);
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		head.getChildren().add(grow);
		if (state != null) {
			Label chip = new Label(state.equals("works") ? "WORKS" : state.equals("broken") ? "BROKEN" : "SKIPPED");
			chip.getStyleClass().addAll("tw-state", "tw-state-" + state);
			head.getChildren().add(chip);
			if (!open) {
				Button change = new Button("Change");
				change.getStyleClass().setAll("button", "tw-link");
				change.setOnAction(e -> {
					reopened.add(index);
					redraw.run();
				});
				head.getChildren().add(change);
			}
		} else if (current) {
			Label next = new Label("NEXT UP");
			next.getStyleClass().add("tw-next");
			head.getChildren().add(next);
		}
		c.getChildren().add(head);
		if (expanded) {
			VBox body = new VBox(10);
			body.setPadding(new Insets(0, 0, 0, 40));
			if (item.has("how")) {
				Label how = new Label(item.get("how").getAsString());
				how.setWrapText(true);
				how.getStyleClass().add("tw-how");
				body.getChildren().add(how);
			}
			Button works = new Button("Works");
			works.getStyleClass().setAll("button", "tw-answer", "tw-answer-works");
			Button broken = new Button("Broken");
			broken.getStyleClass().setAll("button", "tw-answer", "tw-answer-broken");
			Button skip = new Button("Skip");
			skip.getStyleClass().setAll("button", "tw-answer", "tw-answer-skip");
			works.setOnAction(e -> {
				reopened.remove(index);
				answer(version, id, titleText, "works", index, states, redraw);
			});
			skip.setOnAction(e -> {
				reopened.remove(index);
				answer(version, id, titleText, "skip", index, states, redraw);
			});
			broken.setOnAction(e -> {
				LauncherLog.info("[tester] test item " + id + ": broken - report opened");
				openReportDialog(id, titleText, () -> {
					reopened.remove(index);
					states.set(index, "broken");
					redraw.run();
				});
			});
			HBox buttons = new HBox(8, works, broken, skip);
			buttons.setAlignment(Pos.CENTER_LEFT);
			body.getChildren().add(buttons);
			c.getChildren().add(body);
		}
		return c;
	}

	private static void answer(String version, String id, String title, String state, int index, List<String> states, Runnable redraw) {
		LauncherLog.info("[tester] test item " + id + " (" + title + "): " + state);
		JsonObject e = TesterApi.eventOf("feedback", version);
		e.addProperty("item", id);
		e.addProperty("state", state);
		TesterApi.event(e);
		states.set(index, state);
		redraw.run();
	}

	// ------------------------------------------------------------------ report

	private static void openReportDialog(String item, String itemTitle, Runnable onSent) {
		Stage dialog = new Stage();
		dialog.initOwner(stage);
		dialog.initModality(Modality.WINDOW_MODAL);
		dialog.setTitle("Report: " + itemTitle);
		StackPane root = new StackPane();
		root.setStyle(themeStyle);
		root.setPadding(new Insets(20, 22, 20, 22));
		root.getChildren().add(reportPage(item, itemTitle, () -> {
			dialog.close();
			if (onSent != null) {
				onSent.run();
			}
		}));
		Scene scene = new Scene(root, 640, 600);
		scene.getStylesheets().add(TesterWindow.class.getResource("/net/veloclient/launcher/launcher.css").toExternalForm());
		dialog.setScene(scene);
		dialog.show();
	}

	/** The report form: a test item's "broken" ({@code item} set) or a free bug report / suggestion. */
	private static Node reportPage(String item, String itemTitle, Runnable onSent) {
		VBox page = new VBox(12);
		page.getChildren().add(title(item == null ? "Report a bug or an idea" : "What's broken?"));
		if (item != null) {
			page.getChildren().add(muted(itemTitle));
		}
		String[] type = {item == null ? "bug" : "broken"};
		if (item == null) {
			Button bug = button("Bug", true);
			Button idea = button("Suggestion", false);
			bug.setOnAction(e -> {
				type[0] = "bug";
				bug.getStyleClass().setAll("button", "primary-button");
				idea.getStyleClass().setAll("button", "ghost-button");
			});
			idea.setOnAction(e -> {
				type[0] = "suggestion";
				idea.getStyleClass().setAll("button", "primary-button");
				bug.getStyleClass().setAll("button", "ghost-button");
			});
			page.getChildren().add(new HBox(8, bug, idea));
		}
		javafx.scene.control.ComboBox<String> area = new javafx.scene.control.ComboBox<>(javafx.collections.FXCollections.observableArrayList(AREAS));
		area.setPromptText("Where? (optional)");
		area.setPrefWidth(230);
		TextField titleField = new TextField(item == null ? "" : itemTitle + " doesn't work");
		titleField.setPromptText("Short title");
		TextArea text = new TextArea();
		text.setPromptText("What happened? What did you expect? How can we make it happen again?");
		text.setWrapText(true);
		text.setPrefRowCount(8);
		VBox.setVgrow(text, Priority.ALWAYS);
		List<Path> files = new ArrayList<>();
		FlowPane chips = new FlowPane(6, 6);
		Runnable[] redrawChips = new Runnable[1];
		redrawChips[0] = () -> {
			chips.getChildren().clear();
			for (Path f : List.copyOf(files)) {
				Button chip = new Button(f.getFileName() + "   " + size(f) + "   ✕");
				chip.getStyleClass().setAll("button", "tw-file-chip");
				chip.setOnAction(e -> {
					files.remove(f);
					redrawChips[0].run();
				});
				chips.getChildren().add(chip);
			}
		};
		Button add = button("Browse files...", false);
		add.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Screenshots, clips or log files");
			chooser.getExtensionFilters().addAll(
					new FileChooser.ExtensionFilter("Pictures, videos, logs and text", ATTACHABLE.stream().map(x -> "*." + x).toList()),
					new FileChooser.ExtensionFilter("Pictures and videos", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.mp4", "*.webm", "*.mov", "*.mkv"),
					new FileChooser.ExtensionFilter("Logs and text", "*.txt", "*.log", "*.json", "*.csv", "*.md", "*.toml", "*.cfg", "*.properties", "*.zip"));
			var picked = chooser.showOpenMultipleDialog(page.getScene().getWindow());
			if (picked != null) {
				for (var f : picked) {
					if (files.size() < 10) {
						files.add(f.toPath());
					}
				}
				LauncherLog.info("[tester] report: " + picked.size() + " file(s) added");
				redrawChips[0].run();
			}
		});
		Button paste = button("Paste screenshot", false);
		paste.setOnAction(e -> {
			Path pasted = pasteImage();
			if (pasted != null) {
				files.add(pasted);
				redrawChips[0].run();
			}
		});
		Label status = new Label("Logs are attached automatically");
		status.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 12px;");
		status.setWrapText(true);
		Button send = button("Send report", true);
		send.setOnAction(e -> {
			if (text.getText().isBlank() && files.isEmpty()) {
				status.setText("Write a few words (or add a screenshot) first.");
				return;
			}
			send.setDisable(true);
			status.setText("Sending...");
			JsonObject report = new JsonObject();
			report.addProperty("version", AppVersion.VERSION);
			report.addProperty("type", type[0]);
			if (item != null) {
				report.addProperty("item", item);
			}
			report.addProperty("title", titleField.getText().strip());
			report.addProperty("text", text.getText().strip());
			report.addProperty("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") + " (" + System.getProperty("os.arch", "") + ")");
			if (area.getValue() != null) {
				report.addProperty("area", area.getValue());
			}
			String minecraft = lastMinecraft();
			if (minecraft != null) {
				report.addProperty("minecraft", minecraft);
			}
			List<Path> attachments = List.copyOf(files);
			LauncherLog.info("[tester] sending " + type[0] + " report" + (item != null ? " for " + item : "") + " with " + attachments.size() + " file(s)");
			CompletableFuture.runAsync(() -> {
				try {
					report.addProperty("launcherLog", BugReportDialog.redactSecrets(tail(launcherLog())));
					report.addProperty("gameLog", BugReportDialog.redactSecrets(tail(gameLog())));
					report.addProperty("updateLog", BugReportDialog.redactSecrets(tail(net.veloclient.launcher.launch.UpdateService.logFile())));
					String id = TesterApi.report(report);
					int n = 0;
					for (Path f : attachments) {
						int k = ++n;
						Platform.runLater(() -> status.setText("Uploading file " + k + " of " + attachments.size() + "..."));
						TesterApi.attach(id, f);
					}
					LauncherLog.info("[tester] report " + id + " sent");
					Platform.runLater(() -> {
						status.setText("Sent - thank you! (report " + id + ")");
						text.clear();
						files.clear();
						redrawChips[0].run();
						send.setDisable(false);
						if (onSent != null) {
							onSent.run();
						}
					});
				} catch (TesterApi.TesterError ex) {
					LauncherLog.warn("[tester] report failed: " + ex.getMessage(), null);
					Platform.runLater(() -> {
						status.setText("Couldn't send it: " + ex.getMessage());
						send.setDisable(false);
					});
				}
			});
		});
		Button clips = button("Add a Velo clip...", false);
		clips.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Your Velo clips");
			Path dir = clipsFolder();
			if (dir != null) {
				chooser.setInitialDirectory(dir.toFile());
			}
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Clips and screenshots", "*.mp4", "*.webm", "*.png", "*.jpg"));
			var picked = chooser.showOpenMultipleDialog(page.getScene().getWindow());
			if (picked != null) {
				for (var f : picked) {
					if (files.size() < 10) {
						files.add(f.toPath());
					}
				}
				redrawChips[0].run();
			}
		});
		// drop zone: drag screenshots, clips, logs or text files in (the whole page takes drops too)
		Label dropTitle = new Label("Drop screenshots, clips or logs");
		dropTitle.getStyleClass().add("tw-drop-title");
		Label dropSub = new Label("");
		dropSub.setManaged(false);
		dropSub.getStyleClass().add("tw-drop-sub");
		HBox fileButtons = new HBox(8, add, clips, paste);
		fileButtons.setAlignment(Pos.CENTER);
		VBox drop = new VBox(6, dropTitle, dropSub, fileButtons, chips);
		drop.setAlignment(Pos.CENTER);
		drop.getStyleClass().add("tw-drop");
		chips.setAlignment(Pos.CENTER);
		java.util.function.Consumer<List<java.io.File>> takeFiles = dropped -> {
			int skipped = 0;
			for (java.io.File f : dropped) {
				String name = f.getName().toLowerCase(Locale.ROOT);
				int dot = name.lastIndexOf('.');
				boolean ok = f.isFile() && dot > 0 && ATTACHABLE.contains(name.substring(dot + 1)) && f.length() <= 200L * 1024 * 1024;
				if (ok && files.size() < 10 && !files.contains(f.toPath())) {
					files.add(f.toPath());
				} else if (!ok) {
					skipped++;
				}
			}
			LauncherLog.info("[tester] report: " + dropped.size() + " file(s) dropped" + (skipped > 0 ? ", " + skipped + " not allowed" : ""));
			redrawChips[0].run();
			if (skipped > 0) {
				dropSub.setManaged(true);
				dropSub.setText(skipped + (skipped == 1 ? " file wasn't added" : " files weren't added") + " (type not allowed or over 200 MB)");
			}
		};
		page.setOnDragOver(e -> {
			if (e.getDragboard().hasFiles()) {
				e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
				if (!drop.getStyleClass().contains("tw-drop-hover")) {
					drop.getStyleClass().add("tw-drop-hover");
				}
			}
			e.consume();
		});
		page.setOnDragExited(e -> drop.getStyleClass().remove("tw-drop-hover"));
		page.setOnDragDropped(e -> {
			boolean ok = e.getDragboard().hasFiles();
			if (ok) {
				takeFiles.accept(e.getDragboard().getFiles());
			}
			drop.getStyleClass().remove("tw-drop-hover");
			e.setDropCompleted(ok);
			e.consume();
		});
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox bottom = new HBox(10, status, grow, send);
		HBox.setHgrow(status, Priority.SOMETIMES);
		bottom.setAlignment(Pos.CENTER_LEFT);
		page.getChildren().addAll(new HBox(8, titleField, area), text, drop, bottom);
		HBox.setHgrow(titleField, Priority.ALWAYS);
		return page;
	}

	/** What can be attached (the server takes the same list). */
	private static final java.util.Set<String> ATTACHABLE = java.util.Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "mp4", "webm", "mov",
			"mkv", "txt", "log", "json", "zip", "csv", "md", "toml", "cfg", "properties");

	/** The areas a report can be about (YouTrack's Subsystem values, tools/youtrack/setup.py). */
	private static final List<String> AREAS = List.of("Launcher", "Game", "Title Screen", "HUD & Modules", "Cosmetics", "Velo+",
			"Performance", "Clips & Rewind", "Friends & Party", "Store", "Velo World", "Installer & Updates");

	/** The newest profile's velo-clips folder (where the game saves clips), or null. */
	private static Path clipsFolder() {
		try (var dirs = java.nio.file.Files.list(net.veloclient.launcher.data.VeloPaths.root().resolve("instances"))) {
			return dirs.map(d -> d.resolve("velo-clips")).filter(java.nio.file.Files::isDirectory)
					.max(java.util.Comparator.comparingLong(d -> d.toFile().lastModified())).orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	/** The Minecraft version of the profile played last (for the report's Minecraft field), or null. */
	private static String lastMinecraft() {
		try {
			var last = net.veloclient.launcher.data.QuickLaunchStore.loadAll().stream()
					.max(java.util.Comparator.comparingLong(net.veloclient.launcher.data.QuickLaunchStore.Entry::lastLaunchedEpochMillis)).orElse(null);
			if (last == null) {
				return null;
			}
			return net.veloclient.launcher.instance.InstanceStore.loadAll().stream().filter(i -> i.id().equals(last.instanceId()))
					.map(net.veloclient.launcher.instance.Instance::mcVersion).findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ known issues (everything open in the tracker)

	private static Node knownPage() {
		VBox page = new VBox(12);
		page.getChildren().addAll(title("Known issues"), muted("Loading..."));
		CompletableFuture.runAsync(() -> {
			try {
				JsonArray issues = TesterApi.openIssues();
				Platform.runLater(() -> renderKnown(page, issues));
			} catch (TesterApi.TesterError e) {
				Platform.runLater(() -> page.getChildren().setAll(title("Known issues"), muted("Couldn't load: " + e.getMessage())));
			}
		});
		return scroll(page);
	}

	private static void renderKnown(VBox page, JsonArray issues) {
		page.getChildren().setAll(title("Known issues"),
				muted(issues.size() + " open"));
		TextField search = new TextField();
		search.setPromptText("Search known issues...");
		search.getStyleClass().add("tw-search");
		HBox filters = new HBox(6);
		filters.setAlignment(Pos.CENTER_LEFT);
		String[] filter = {"All"};
		VBox list = new VBox(8);
		Runnable[] draw = new Runnable[1];
		for (String f : List.of("All", "Bugs", "Crashes", "Suggestions", "In progress")) {
			Button b = new Button(f);
			b.getStyleClass().setAll("button", "tw-filter");
			if (f.equals("All")) {
				b.getStyleClass().add("tw-filter-on");
			}
			b.setOnAction(e -> {
				filter[0] = f;
				filters.getChildren().forEach(n -> n.getStyleClass().remove("tw-filter-on"));
				b.getStyleClass().add("tw-filter-on");
				draw[0].run();
			});
			filters.getChildren().add(b);
		}
		Label count = new Label();
		count.getStyleClass().add("tw-group-count");
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		filters.getChildren().addAll(grow, count);
		draw[0] = () -> {
			list.getChildren().clear();
			String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
			int shown = 0;
			for (JsonElement el : issues) {
				JsonObject i = el.getAsJsonObject();
				String type = str(i, "type");
				String state = str(i, "state");
				boolean match = switch (filter[0]) {
					case "Bugs" -> !type.equals("Crash") && !type.equals("Suggestion") && !type.equals("Feature Request");
					case "Crashes" -> type.equals("Crash");
					case "Suggestions" -> type.equals("Suggestion") || type.equals("Feature Request");
					case "In progress" -> state.equals("In Progress") || state.equals("Confirmed") || state.equals("Testing");
					default -> true;
				};
				if (!match || !q.isEmpty() && !(str(i, "summary") + " " + str(i, "id") + " " + str(i, "area")).toLowerCase(Locale.ROOT).contains(q)) {
					continue;
				}
				if (shown++ < 300) {
					list.getChildren().add(knownRow(i));
				}
			}
			count.setText(shown + " shown");
			if (shown == 0) {
				list.getChildren().add(muted("Nothing matches."));
			}
		};
		search.textProperty().addListener((o, a, b) -> draw[0].run());
		page.getChildren().addAll(search, filters, list);
		draw[0].run();
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static Node knownRow(JsonObject i) {
		String type = str(i, "type");
		String state = str(i, "state");
		Label id = new Label(str(i, "id"));
		id.getStyleClass().add("tw-known-id");
		Label summary = new Label(str(i, "summary"));
		summary.getStyleClass().add("tw-known-title");
		summary.setWrapText(true);
		Label typeChip = new Label(type.toUpperCase(Locale.ROOT));
		typeChip.getStyleClass().addAll("tw-type", "tw-type-" + type.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "-"));
		Label stateChip = new Label(state.toUpperCase(Locale.ROOT));
		stateChip.setStyle("-fx-text-fill: " + stateColour(state) + "; -fx-background-color: " + stateColour(state) + "22;");
		stateChip.getStyleClass().add("tw-state");
		HBox chips = new HBox(6, typeChip, stateChip);
		chips.setAlignment(Pos.CENTER_LEFT);
		if (!str(i, "area").isEmpty()) {
			Label area = new Label(str(i, "area"));
			area.getStyleClass().add("tw-area");
			chips.getChildren().add(area);
		}
		int occurrences = i.has("occurrences") ? i.get("occurrences").getAsInt() : 0;
		if (occurrences > 1) {
			Label occ = new Label(occurrences + "x");
			occ.getStyleClass().add("tw-area");
			chips.getChildren().add(occ);
		}
		VBox text = new VBox(5, new HBox(10, id, summary), chips);
		((HBox) text.getChildren().get(0)).setAlignment(Pos.BASELINE_LEFT);
		HBox.setHgrow(text, Priority.ALWAYS);
		Button meToo = new Button("I have this too");
		meToo.getStyleClass().setAll("button", "tw-answer", "tw-answer-skip");
		meToo.setMinWidth(Region.USE_PREF_SIZE);
		meToo.setOnAction(e -> {
			meToo.setDisable(true);
			meToo.setText("Sending...");
			CompletableFuture.runAsync(() -> {
				try {
					TesterApi.meToo(str(i, "id"), AppVersion.VERSION);
					LauncherLog.info("[tester] me too on " + str(i, "id"));
					Platform.runLater(() -> meToo.setText("Thanks - noted"));
				} catch (TesterApi.TesterError ex) {
					Platform.runLater(() -> {
						meToo.setText("Couldn't send");
						meToo.setDisable(false);
					});
				}
			});
		});
		HBox row = new HBox(14, text, meToo);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().addAll("tw-card", "tw-known");
		return row;
	}

	// ------------------------------------------------------------------ my reports (YouTrack state + the team's replies)

	private static Node minePage() {
		VBox page = new VBox(14);
		page.getChildren().addAll(title("My reports"), muted("Loading..."));
		loadMine(page);
		return scroll(page);
	}

	private static void loadMine(VBox page) {
		CompletableFuture.runAsync(() -> {
			try {
				JsonArray list = TesterApi.myReports(true);
				Platform.runLater(() -> renderMine(page, list));
			} catch (TesterApi.TesterError e) {
				Platform.runLater(() -> page.getChildren().setAll(title("My reports"), muted("Couldn't load: " + e.getMessage())));
			}
		});
	}

	private static void renderMine(VBox page, JsonArray list) {
		Button refresh = button("Refresh", false);
		refresh.setOnAction(e -> loadMine(page));
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox head = new HBox(10, title("My reports"), grow, refresh);
		head.setAlignment(Pos.CENTER_LEFT);
		page.getChildren().setAll(head);
		if (list.isEmpty()) {
			VBox empty = card();
			empty.getChildren().add(muted("Nothing reported yet"));
			page.getChildren().add(empty);
			return;
		}
		for (JsonElement el : list) {
			page.getChildren().add(mineCard(el.getAsJsonObject(), () -> loadMine(page)));
		}
	}

	private static String stateColour(String state) {
		return switch (state) {
			case "New", "Sending" -> "#9AA4B2";
			case "Confirmed" -> "#7FB2FF";
			case "In Progress" -> "#F2C14E";
			case "Testing" -> "#B38CFF";
			case "Needs Info" -> "#FF9F5A";
			case "Fixed", "Verified" -> "#4CD07D";
			default -> "#8A8A95";
		};
	}

	private static String stateHint(String state) {
		return switch (state) {
			case "Sending" -> "Still uploading to the team's tracker.";
			case "New" -> "Waiting for the team to look at it.";
			case "Confirmed" -> "The team can see it too - it's on the list.";
			case "In Progress" -> "Someone is working on it right now.";
			case "Needs Info" -> "The team needs more from you - see the comments and reply below.";
			case "Testing" -> "Done - please check it in the newest tester build and tell us if it works.";
			case "Fixed" -> "Fixed.";
			case "Verified" -> "Fixed and checked.";
			case "Can't Reproduce" -> "The team couldn't make it happen - more details or a video help.";
			case "Duplicate" -> "Someone reported the same thing already.";
			case "Won't Fix" -> "Not something we'll change.";
			default -> "";
		};
	}

	private static Node mineCard(JsonObject r, Runnable reload) {
		VBox c = card();
		String state = r.has("state") ? r.get("state").getAsString() : "New";
		String issue = r.has("issue") && !r.get("issue").isJsonNull() ? r.get("issue").getAsString() : null;
		Label stateChip = new Label(state.toUpperCase(Locale.ROOT));
		stateChip.setStyle("-fx-text-fill: " + stateColour(state) + "; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 2 8;"
				+ " -fx-background-color: " + stateColour(state) + "22; -fx-background-radius: 6;");
		Label id = new Label(issue != null ? issue : "");
		id.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px; -fx-font-family: 'Geist Mono';");
		Label when = new Label(TIME.format(Instant.ofEpochMilli(r.get("at").getAsLong())) + "  ·  build " + r.get("version").getAsString());
		when.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox top = new HBox(8, stateChip, id, grow, when);
		top.setAlignment(Pos.CENTER_LEFT);
		Label t = new Label(r.has("title") && !r.get("title").isJsonNull() && !r.get("title").getAsString().isBlank()
				? r.get("title").getAsString() : "(no title)");
		t.setWrapText(true);
		t.setStyle("-fx-text-fill: -velo-text; -fx-font-size: 14px; -fx-font-weight: bold;");
		Label hint = muted(stateHint(state));
		c.getChildren().addAll(top, t, hint);
		if (r.has("unread") && r.get("unread").getAsBoolean()) {
			Label news = new Label("NEW ANSWER");
			news.setStyle("-fx-text-fill: -velo-on-accent; -fx-background-color: -velo-accent; -fx-background-radius: 6; -fx-padding: 2 8; -fx-font-size: 10px; -fx-font-weight: bold;");
			c.getChildren().add(1, news);
		}
		// the conversation
		VBox thread = new VBox(6);
		if (r.has("comments")) {
			for (JsonElement el : r.getAsJsonArray("comments")) {
				JsonObject m = el.getAsJsonObject();
				boolean mine = m.has("tester") && m.get("tester").getAsBoolean();
				Label who = new Label((mine ? "You" : m.get("author").getAsString()) + "  ·  " + TIME.format(Instant.ofEpochMilli(m.get("at").getAsLong())));
				who.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 10.5px;");
				Label body = new Label(m.get("text").getAsString());
				body.setWrapText(true);
				body.setMaxWidth(560);
				body.setStyle("-fx-text-fill: " + (mine ? "-velo-on-accent" : "-velo-text") + "; -fx-font-size: 12.5px; -fx-padding: 8 11;"
						+ " -fx-background-radius: 10; -fx-background-color: " + (mine ? "-velo-accent" : "rgba(255,255,255,0.07)") + ";");
				VBox bubble = new VBox(3, who, body);
				bubble.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
				thread.getChildren().add(bubble);
			}
		}
		if (!thread.getChildren().isEmpty()) {
			c.getChildren().add(thread);
		}
		TextField reply = new TextField();
		reply.setPromptText(state.equals("Needs Info") ? "Answer the team..." : "Add something (more details, it happened again...)");
		HBox.setHgrow(reply, Priority.ALWAYS);
		Button send = button("Reply", true);
		Label status = new Label();
		status.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		Runnable doSend = () -> {
			String text = reply.getText().strip();
			if (text.isEmpty()) {
				return;
			}
			send.setDisable(true);
			status.setText("Sending...");
			CompletableFuture.runAsync(() -> {
				try {
					TesterApi.reply(r.get("id").getAsString(), text);
					LauncherLog.info("[tester] replied on report " + r.get("id").getAsString());
					Platform.runLater(reload);
				} catch (TesterApi.TesterError ex) {
					Platform.runLater(() -> {
						status.setText("Couldn't send: " + ex.getMessage());
						send.setDisable(false);
					});
				}
			});
		};
		send.setOnAction(e -> doSend.run());
		reply.setOnAction(e -> doSend.run());
		HBox replyRow = new HBox(8, reply, send);
		replyRow.setAlignment(Pos.CENTER_LEFT);
		int files = r.has("files") ? r.getAsJsonArray("files").size() : 0;
		Label attached = new Label(files + (files == 1 ? " file attached" : " files attached") + " (screenshots, videos, logs)");
		attached.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		c.getChildren().addAll(replyRow, status, attached);
		return c;
	}

	/** A picture from the clipboard as a PNG in the temp folder, or null. */
	private static Path pasteImage() {
		try {
			var image = Clipboard.getSystemClipboard().getImage();
			if (image == null) {
				return null;
			}
			Path file = Files.createTempFile("velo-screenshot-", ".png");
			javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
			LauncherLog.info("[tester] report: screenshot pasted (" + (int) image.getWidth() + "x" + (int) image.getHeight() + ")");
			return file;
		} catch (Exception | LinkageError e) {
			LauncherLog.warn("[tester] paste failed", e instanceof Exception ex ? ex : null);
			return null;
		}
	}

	private static String size(Path f) {
		try {
			long b = Files.size(f);
			return b > 1 << 20 ? String.format(Locale.ROOT, "%.1f MB", b / 1048576.0) : (b >> 10) + " KB";
		} catch (IOException e) {
			return "";
		}
	}

	// ------------------------------------------------------------------ logs

	private static Path launcherLog() {
		return net.veloclient.launcher.data.VeloPaths.logs().resolve("launcher.log");
	}

	/** The newest game log among all profiles. */
	private static Path gameLog() {
		Path best = null;
		long bestTime = 0;
		try {
			for (var instance : net.veloclient.launcher.instance.InstanceStore.loadAll()) {
				Path log = net.veloclient.launcher.instance.InstancePaths.logsDir(instance.id()).resolve("latest.log");
				if (Files.isRegularFile(log)) {
					long t = Files.getLastModifiedTime(log).toMillis();
					if (t > bestTime) {
						bestTime = t;
						best = log;
					}
				}
			}
		} catch (Exception ignored) {
			// none
		}
		return best;
	}

	/** The last {@link #LOG_TAIL} bytes of a log file ("" when there's none). */
	static String tail(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return "";
		}
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			long len = raf.length();
			long from = Math.max(0, len - LOG_TAIL);
			byte[] bytes = new byte[(int) (len - from)];
			raf.seek(from);
			raf.readFully(bytes);
			return (from > 0 ? "[... earlier lines cut ...]\n" : "") + new String(bytes, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "(couldn't read " + file + ": " + e.getMessage() + ")";
		}
	}

	private static Node logsPage() {
		// 2026-11-26 (owner: "Game logs EXTREM laggy"): the shared log viewer - only the visible lines are drawn, only new
		// lines are read (off the UI thread), levels coloured, search and filters. It used to put 1.5 MB into a text box every second.
		VBox page = new VBox(12);
		page.getChildren().add(title("Logs"));
		String[] which = {"launcher"};
		java.util.function.Supplier<Path> file = () -> switch (which[0]) {
			case "game" -> gameLog();
			case "update" -> net.veloclient.launcher.launch.UpdateService.logFile();
			default -> launcherLog();
		};
		Button launcher = new Button("Launcher");
		Button game = new Button("Game");
		Button update = new Button("Updater");
		List<Button> tabs = List.of(launcher, game, update);
		HBox seg = new HBox(4);
		seg.getStyleClass().add("run-seg");
		seg.setMaxWidth(Region.USE_PREF_SIZE);
		for (Button b : tabs) {
			b.getStyleClass().addAll("segmented-button", "run-seg-button");
			b.setOnAction(e -> {
				which[0] = b == game ? "game" : b == update ? "update" : "launcher";
				tabs.forEach(x -> x.getStyleClass().remove("segmented-button-active"));
				b.getStyleClass().add("segmented-button-active");
				LauncherLog.info("[tester] logs: " + which[0]);
			});
		}
		launcher.getStyleClass().add("segmented-button-active");
		seg.getChildren().addAll(tabs);
		Button export = button("Export...", false);
		export.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setInitialFileName("velo-" + which[0] + "-" + AppVersion.VERSION + ".log");
			var target = chooser.showSaveDialog(page.getScene().getWindow());
			if (target != null) {
				try {
					Files.writeString(target.toPath(), BugReportDialog.redactSecrets(tail(file.get())), StandardCharsets.UTF_8);
					LauncherLog.info("[tester] logs exported to " + target);
				} catch (IOException ex) {
					LauncherLog.warn("[tester] export failed", ex);
				}
			}
		});
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		export.getStyleClass().setAll("button", "log-action");
		HBox bar = new HBox(8, seg, grow, export);
		bar.setAlignment(Pos.CENTER_LEFT);
		Node viewer = RunningInstanceView.logViewer(file);
		VBox.setVgrow(viewer, Priority.ALWAYS);
		page.getChildren().addAll(bar, viewer);
		return page;
	}

	private static void stopLogs() {
		if (logTimer != null) {
			logTimer.stop();
			logTimer = null;
		}
	}

	// ------------------------------------------------------------------ results (owner)

	private static Node resultsPage() {
		VBox page = new VBox(14);
		page.getChildren().addAll(title("Results"), muted("Loading every tester's reports..."));
		CompletableFuture.runAsync(() -> {
			try {
				JsonArray reports = TesterApi.reports();
				JsonObject overview = TesterApi.overview();
				Platform.runLater(() -> renderResults(page, reports, overview));
			} catch (TesterApi.TesterError e) {
				Platform.runLater(() -> page.getChildren().setAll(title("Results"), muted("Couldn't load: " + e.getMessage())));
			}
		});
		return scroll(page);
	}

	private static void renderResults(VBox page, JsonArray reports, JsonObject overview) {
		page.getChildren().setAll(title("Results"));
		// per tester: what they did with each build
		VBox testers = card();
		Label th = new Label("Testers");
		th.setStyle("-fx-text-fill: -velo-text; -fx-font-size: 15px; -fx-font-weight: bold;");
		testers.getChildren().add(th);
		if (overview.has("testers")) {
			for (JsonElement t : overview.getAsJsonArray("testers")) {
				JsonObject o = t.getAsJsonObject();
				StringBuilder line = new StringBuilder(o.get("name").getAsString());
				if (o.has("builds") && o.get("builds").isJsonObject()) {
					for (var b : o.getAsJsonObject("builds").entrySet()) {
						JsonObject st = b.getValue().getAsJsonObject();
						int works = 0;
						int broken = 0;
						int skip = 0;
						if (st.has("feedback")) {
							for (var f : st.getAsJsonObject("feedback").entrySet()) {
								String s = f.getValue().getAsJsonObject().get("state").getAsString();
								if (s.equals("works")) {
									works++;
								} else if (s.equals("broken")) {
									broken++;
								} else {
									skip++;
								}
							}
						}
						line.append("   ·   ").append(b.getKey()).append(": ").append(st.has("launches") ? st.get("launches").getAsInt() : 0)
								.append(" starts, ").append(Math.round(st.has("minutes") ? st.get("minutes").getAsDouble() : 0)).append(" min, ")
								.append(works).append(" ✓ ").append(broken).append(" ✗ ").append(skip).append(" skipped");
					}
				}
				testers.getChildren().add(muted(line.toString()));
			}
		}
		page.getChildren().add(testers);
		if (reports.isEmpty()) {
			page.getChildren().add(muted("No reports yet."));
			return;
		}
		for (JsonElement r : reports) {
			page.getChildren().add(reportCard(r.getAsJsonObject()));
		}
	}

	private static Node reportCard(JsonObject r) {
		VBox c = card();
		String id = r.get("id").getAsString();
		String type = r.has("type") ? r.get("type").getAsString() : "bug";
		String status = r.has("status") ? r.get("status").getAsString() : "open";
		Label chip = new Label(type.toUpperCase(Locale.ROOT) + (r.has("item") && !r.get("item").isJsonNull() ? " · " + r.get("item").getAsString() : ""));
		String colour = type.equals("suggestion") ? "#7FB2FF" : "#FF6B73";
		chip.setStyle("-fx-text-fill: " + colour + "; -fx-font-size: 10px; -fx-font-weight: bold;");
		Label meta = new Label(r.get("name").getAsString() + "  ·  " + r.get("version").getAsString() + "  ·  "
				+ TIME.format(Instant.ofEpochMilli(r.get("at").getAsLong())) + "  ·  " + status);
		meta.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		Label t = new Label(r.has("title") ? r.get("title").getAsString() : "");
		t.setWrapText(true);
		t.setStyle("-fx-text-fill: -velo-text; -fx-font-size: 14px; -fx-font-weight: bold;");
		c.getChildren().addAll(chip, t, meta);
		if (r.has("text") && !r.get("text").getAsString().isBlank()) {
			c.getChildren().add(muted(r.get("text").getAsString()));
		}
		FlowPane files = new FlowPane(6, 6);
		if (r.has("files")) {
			for (JsonElement f : r.getAsJsonArray("files")) {
				String name = f.getAsString();
				Button b = button(name, false);
				b.setOnAction(e -> download(id, name));
				files.getChildren().add(b);
			}
		}
		c.getChildren().add(files);
		HBox actions = new HBox(8);
		for (String s : List.of("open", "fixed", "wontfix")) {
			Button b = button(s.equals("wontfix") ? "Won't fix" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1), s.equals(status));
			b.setOnAction(e -> CompletableFuture.runAsync(() -> {
				try {
					TesterApi.reportStatus(id, s);
					LauncherLog.info("[tester] report " + id + " -> " + s);
					Platform.runLater(() -> show("results"));
				} catch (TesterApi.TesterError ex) {
					LauncherLog.warn("[tester] status change failed: " + ex.getMessage(), null);
				}
			}));
			actions.getChildren().add(b);
		}
		if (r.has("youtrack") && !r.get("youtrack").isJsonNull()) {
			String issue = r.get("youtrack").getAsString();
			Button open = button("Open " + issue + " in YouTrack", false);
			open.setOnAction(e -> CoinsView.openUrl("https://youtrack.velo-client.com/issue/" + issue));
			actions.getChildren().add(open);
		}
		c.getChildren().add(actions);
		return c;
	}

	private static void download(String id, String name) {
		FileChooser chooser = new FileChooser();
		chooser.setInitialFileName(id + "-" + name);
		var target = chooser.showSaveDialog(stage);
		if (target == null) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				TesterApi.reportFile(id, name, target.toPath());
				LauncherLog.info("[tester] downloaded " + name + " of report " + id);
				java.awt.Desktop.getDesktop().open(target);
			} catch (Exception e) {
				LauncherLog.warn("[tester] download failed: " + e.getMessage(), null);
			}
		});
	}
}
