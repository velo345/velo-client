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
		root.setLeft(nav());
		content.setPadding(new Insets(22, 26, 22, 26));
		root.setCenter(content);
		Scene scene = new Scene(root, 980, 680);
		scene.getStylesheets().add(TesterWindow.class.getResource("/net/veloclient/launcher/launcher.css").toExternalForm());
		stage.setScene(scene);
		stage.setMinWidth(760);
		stage.setMinHeight(520);
		// behind the launcher: shown first, then the launcher comes back to the front
		if (launcher.getX() > 40) {
			stage.setX(Math.max(0, launcher.getX() - 60));
			stage.setY(Math.max(0, launcher.getY() + 40));
		}
		stage.setOnHidden(e -> stopLogs());
		stage.show();
		launcher.toFront();
		LauncherLog.info("[tester] Tester window opened");
		show("plan");
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
		nav.setPadding(new Insets(20, 12, 20, 12));
		nav.setPrefWidth(200);
		nav.setStyle("-fx-background-color: -velo-surface; -fx-border-color: transparent -velo-hairline transparent transparent;");
		Label brand = new Label("Velo Tester");
		brand.setStyle("-fx-text-fill: -velo-text; -fx-font-size: 17px; -fx-font-weight: bold;");
		Label version = new Label("Build " + AppVersion.VERSION);
		version.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		VBox head = new VBox(2, brand, version);
		head.setPadding(new Insets(0, 8, 16, 8));
		nav.getChildren().add(head);
		nav.getChildren().addAll(navButton("plan", "Test plan", "check"), navButton("report", "Report a bug / idea", "bug"),
				navButton("logs", "Logs", "terminal"), navButton("results", "Results (owner)", "chart"));
		navButtons.stream().filter(b -> "results".equals(b.getUserData())).forEach(b -> {
			b.setVisible(false);
			b.setManaged(false);
		});
		Region spacer = new Region();
		VBox.setVgrow(spacer, Priority.ALWAYS);
		Label hint = new Label("Every report sends your launcher, game and update logs with it.");
		hint.setWrapText(true);
		hint.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		hint.setPadding(new Insets(0, 8, 0, 8));
		nav.getChildren().addAll(spacer, hint);
		return nav;
	}

	private static Button navButton(String key, String text, String icon) {
		Button b = new Button(text);
		b.setUserData(key);
		b.setMaxWidth(Double.MAX_VALUE);
		b.setAlignment(Pos.CENTER_LEFT);
		try {
			b.setGraphic(Icons.icon(icon, 15, javafx.scene.paint.Color.web("#B8B0B2")));
			b.setGraphicTextGap(10);
		} catch (RuntimeException ignored) {
			// unknown icon name
		}
		b.setOnAction(e -> show(key));
		navButtons.add(b);
		styleNav(b, false);
		return b;
	}

	private static void styleNav(Button b, boolean active) {
		b.setStyle("-fx-background-color: " + (active ? "-velo-accent-soft" : "transparent") + "; -fx-background-radius: 8; -fx-padding: 9 12;"
				+ " -fx-text-fill: " + (active ? "-velo-text" : "-velo-text-2") + "; -fx-font-size: 13px; -fx-cursor: hand;");
	}

	private static void show(String key) {
		LauncherLog.info("[tester] page " + key);
		navButtons.forEach(b -> styleNav(b, key.equals(b.getUserData())));
		stopLogs();
		Node page = switch (key) {
			case "report" -> reportPage(null, null, null);
			case "logs" -> logsPage();
			case "results" -> resultsPage();
			default -> planPage();
		};
		content.getChildren().setAll(page);
	}

	private static Label title(String text) {
		Label l = new Label(text);
		l.setStyle("-fx-text-fill: -velo-text; -fx-font-size: 22px; -fx-font-weight: bold;");
		return l;
	}

	private static Label muted(String text) {
		Label l = new Label(text);
		l.setWrapText(true);
		l.setStyle("-fx-text-fill: -velo-text-2; -fx-font-size: 13px;");
		return l;
	}

	private static VBox card() {
		VBox c = new VBox(8);
		c.setPadding(new Insets(14, 16, 14, 16));
		c.setStyle("-fx-background-color: -velo-surface; -fx-background-radius: 12; -fx-border-color: -velo-hairline; -fx-border-radius: 12;");
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
				Platform.runLater(() -> page.getChildren().setAll(title("Test plan"), muted("Couldn't load the test plan: " + e.getMessage())));
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
		if (build == null || !build.has("notes")) {
			page.getChildren().add(muted("No test plan for this build yet."));
			return;
		}
		String version = build.get("version").getAsString();
		JsonObject notes = build.getAsJsonObject("notes");
		JsonObject mine = me.has("mine") && me.get("mine").isJsonObject() && me.getAsJsonObject("mine").has(version)
				? me.getAsJsonObject("mine").getAsJsonObject(version) : new JsonObject();
		JsonObject feedback = mine.has("feedback") && mine.get("feedback").isJsonObject() ? mine.getAsJsonObject("feedback") : new JsonObject();
		if (notes.has("summary")) {
			page.getChildren().add(muted(notes.get("summary").getAsString()));
		}
		JsonArray items = notes.has("test") ? notes.getAsJsonArray("test") : new JsonArray();
		Label progress = new Label();
		progress.setStyle("-fx-text-fill: -velo-text-2; -fx-font-size: 12px;");
		page.getChildren().add(progress);
		VBox list = new VBox(10);
		page.getChildren().add(list);
		List<String> states = new ArrayList<>();
		for (JsonElement e : items) {
			JsonObject item = e.getAsJsonObject();
			String id = item.get("id").getAsString();
			states.add(feedback.has(id) ? feedback.getAsJsonObject(id).get("state").getAsString() : null);
		}
		Runnable[] redraw = new Runnable[1];
		redraw[0] = () -> {
			list.getChildren().clear();
			int done = 0;
			int current = -1;
			for (int i = 0; i < items.size(); i++) {
				if (states.get(i) != null) {
					done++;
				} else if (current < 0) {
					current = i;
				}
			}
			progress.setText(done + " of " + items.size() + " checked" + (current < 0 && !items.isEmpty() ? " - all done, thank you!" : ""));
			for (int i = 0; i < items.size(); i++) {
				list.getChildren().add(testCard(version, items.get(i).getAsJsonObject(), i, states, i == current, redraw[0]));
			}
		};
		redraw[0].run();
	}

	private static Node testCard(String version, JsonObject item, int index, List<String> states, boolean current, Runnable redraw) {
		VBox c = card();
		String id = item.get("id").getAsString();
		String titleText = item.has("title") ? item.get("title").getAsString() : id;
		String state = states.get(index);
		if (current) {
			c.setStyle(c.getStyle() + " -fx-border-color: -velo-accent; -fx-border-width: 1.5;");
		}
		Label t = new Label((index + 1) + ".  " + titleText);
		t.setStyle("-fx-text-fill: -velo-text; -fx-font-size: 15px; -fx-font-weight: bold;");
		HBox head = new HBox(10, t);
		head.setAlignment(Pos.CENTER_LEFT);
		if (state != null) {
			Region grow = new Region();
			HBox.setHgrow(grow, Priority.ALWAYS);
			Label chip = new Label(state.equals("works") ? "WORKS" : state.equals("broken") ? "BROKEN" : "SKIPPED");
			String colour = state.equals("works") ? "#3DDC97" : state.equals("broken") ? "#FF6B73" : "#9A9396";
			chip.setStyle("-fx-text-fill: " + colour + "; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 6;"
					+ " -fx-border-color: " + colour + "; -fx-border-radius: 6;");
			head.getChildren().addAll(grow, chip);
		}
		c.getChildren().add(head);
		if (item.has("how") && (current || state == null)) {
			c.getChildren().add(muted(item.get("how").getAsString()));
		}
		if (current || state != null) {
			Button works = button("It works", current);
			Button broken = button("Broken - report it", false);
			Button skip = button("Skip", false);
			works.setOnAction(e -> answer(version, id, titleText, "works", index, states, redraw));
			skip.setOnAction(e -> answer(version, id, titleText, "skip", index, states, redraw));
			broken.setOnAction(e -> {
				LauncherLog.info("[tester] test item " + id + ": broken - report opened");
				openReportDialog(id, titleText, () -> {
					states.set(index, "broken");
					redraw.run();
				});
			});
			HBox buttons = new HBox(8, works, broken, skip);
			c.getChildren().add(buttons);
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
		page.getChildren().add(muted(item == null
				? "Anything that isn't on the test plan: a bug, something that feels off, an idea. Screenshots and short videos help a lot."
				: "\"" + itemTitle + "\" - tell us what happened and what you expected. Screenshots and short videos help a lot."));
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
				Button chip = new Button(f.getFileName() + "  " + size(f) + "  ✕");
				chip.getStyleClass().add("ghost-button");
				chip.setOnAction(e -> {
					files.remove(f);
					redrawChips[0].run();
				});
				chips.getChildren().add(chip);
			}
		};
		Button add = button("Add screenshots / videos...", false);
		add.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Screenshots or short videos");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures and videos",
					"*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.mp4", "*.webm", "*.mov", "*.mkv"));
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
		Label status = new Label("Launcher, game and update logs are attached automatically.");
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
		HBox fileButtons = new HBox(8, add, paste);
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox bottom = new HBox(10, status, grow, send);
		HBox.setHgrow(status, Priority.SOMETIMES);
		bottom.setAlignment(Pos.CENTER_LEFT);
		page.getChildren().addAll(titleField, text, fileButtons, chips, bottom);
		return page;
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
		VBox page = new VBox(12);
		page.getChildren().add(title("Logs"));
		String[] which = {"launcher"};
		TextArea area = new TextArea();
		area.setEditable(false);
		area.setWrapText(false);
		area.setStyle("-fx-font-family: 'JetBrains Mono', 'DejaVu Sans Mono', monospace; -fx-font-size: 11.5px;"
				+ " -fx-control-inner-background: #0C0A0A; -fx-text-fill: #E6E0E0;");
		VBox.setVgrow(area, Priority.ALWAYS);
		Label where = new Label();
		where.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		long[] lastSize = {-1};
		Runnable refresh = () -> {
			Path file = switch (which[0]) {
				case "game" -> gameLog();
				case "update" -> net.veloclient.launcher.launch.UpdateService.logFile();
				default -> launcherLog();
			};
			where.setText(file == null ? "No log yet" : file.toString());
			long size;
			try {
				size = file == null || !Files.exists(file) ? 0 : Files.size(file);
			} catch (IOException e) {
				size = 0;
			}
			if (size == lastSize[0]) {
				return;
			}
			lastSize[0] = size;
			// new lines arrived: show them (the newest at the bottom)
			String text = tail(file);
			area.setText(text);
			area.positionCaret(text.length());
			area.setScrollTop(Double.MAX_VALUE);
		};
		Button launcher = button("Launcher", true);
		Button game = button("Game", false);
		Button update = button("Updater", false);
		List<Button> tabs = List.of(launcher, game, update);
		for (Button b : tabs) {
			b.setOnAction(e -> {
				which[0] = b == game ? "game" : b == update ? "update" : "launcher";
				tabs.forEach(x -> x.getStyleClass().setAll("button", x == b ? "primary-button" : "ghost-button"));
				lastSize[0] = -1;
				LauncherLog.info("[tester] logs: " + which[0]);
				refresh.run();
			});
		}
		Button copy = button("Copy", false);
		copy.setOnAction(e -> {
			ClipboardContent c = new ClipboardContent();
			c.putString(area.getText());
			Clipboard.getSystemClipboard().setContent(c);
			LauncherLog.info("[tester] logs copied (" + which[0] + ")");
		});
		Button export = button("Export...", false);
		export.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setInitialFileName("velo-" + which[0] + "-" + AppVersion.VERSION + ".log");
			var target = chooser.showSaveDialog(page.getScene().getWindow());
			if (target != null) {
				try {
					Files.writeString(target.toPath(), BugReportDialog.redactSecrets(area.getText()), StandardCharsets.UTF_8);
					LauncherLog.info("[tester] logs exported to " + target);
				} catch (IOException ex) {
					LauncherLog.warn("[tester] export failed", ex);
				}
			}
		});
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox bar = new HBox(8, launcher, game, update, grow, copy, export);
		bar.setAlignment(Pos.CENTER_LEFT);
		page.getChildren().addAll(bar, where, area);
		refresh.run();
		logTimer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> refresh.run()));
		logTimer.setCycleCount(Timeline.INDEFINITE);
		logTimer.play();
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
