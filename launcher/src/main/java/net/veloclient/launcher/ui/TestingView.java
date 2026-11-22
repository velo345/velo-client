package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.launch.TesterUpdate;
import net.veloclient.launcher.social.TesterApi;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The Velo Client Tester launcher's "Testing" page: this build and newer ones (self-update from the Velo server), and
 * per build what changed and what to test - each test item gets Works / Broken / Skip and an optional comment, sent to
 * the owner. Items whose modules you actually used in game get a "used" tick. Owners also see every tester's
 * downloads, launches, minutes and test progress.
 *
 * <p>Notes format (tester/builds/&lt;version&gt;.json): {@code {"title", "summary", "changes": [..], "test": [{"id",
 * "title", "how", "modules": [..]}]}}.
 */
public final class TestingView {

	private TestingView() {
	}

	public static Node build() {
		VBox page = new VBox(18);
		page.getStyleClass().add("tester-page");
		page.setPadding(new Insets(26, 32, 32, 32));
		Label loading = new Label("Loading the tester builds...");
		loading.getStyleClass().add("t-muted");
		page.getChildren().add(loading);
		ScrollPane scroll = new ScrollPane(page);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		load(page);
		return scroll;
	}

	private static void load(VBox page) {
		CompletableFuture.runAsync(() -> {
			JsonObject me;
			JsonArray builds = new JsonArray();
			JsonObject overview = null;
			String problem = null;
			try {
				me = TesterApi.me();
				boolean allowed = me.get("tester").getAsBoolean() || me.get("owner").getAsBoolean();
				if (allowed) {
					builds = TesterApi.builds();
					if (me.get("owner").getAsBoolean()) {
						overview = TesterApi.overview();
					}
				}
			} catch (TesterApi.TesterError e) {
				me = null;
				problem = e.getMessage();
			}
			JsonObject meF = me;
			JsonArray buildsF = builds;
			JsonObject overviewF = overview;
			String problemF = problem;
			Platform.runLater(() -> render(page, meF, buildsF, overviewF, problemF));
		});
	}

	private static void render(VBox page, JsonObject me, JsonArray builds, JsonObject overview, String problem) {
		page.getChildren().clear();
		page.getChildren().add(hero(page, builds));
		if (me == null) {
			page.getChildren().add(notice("Not connected", problem == null ? "Sign in and connect to the Velo Network to see the tester builds." : problem));
			return;
		}
		boolean tester = me.get("tester").getAsBoolean();
		boolean owner = me.get("owner").getAsBoolean();
		if (!tester && !owner) {
			page.getChildren().add(notice("You're not a tester (yet)",
					"Tester builds are only for players with the Tester role on the Velo Discord. Got the role? Link your account with /link "
							+ "in Discord - it can take up to five minutes to show up here."));
			return;
		}
		JsonObject mine = me.has("mine") && me.get("mine").isJsonObject() ? me.getAsJsonObject("mine") : new JsonObject();
		if (owner && overview != null) {
			page.getChildren().add(testersCard(overview));
		}
		if (builds.isEmpty()) {
			page.getChildren().add(notice("No tester builds yet", "The first one shows up here as soon as it's uploaded."));
			return;
		}
		int shown = 0;
		for (JsonElement b : builds) {
			if (shown++ >= 6) {
				break;
			}
			JsonObject build = b.getAsJsonObject();
			String version = build.get("version").getAsString();
			JsonObject stat = mine.has(version) ? mine.getAsJsonObject(version) : new JsonObject();
			page.getChildren().add(buildCard(build, stat, shown == 1));
		}
		// opening the page counts as "read the notes" for the build you're on
		TesterApi.event(TesterApi.eventOf("seen", AppVersion.VERSION));
	}

	// ---- hero: which build you're on, and the update ----

	private static Node hero(VBox page, JsonArray builds) {
		Label badge = new Label("TESTER");
		badge.getStyleClass().add("tester-badge");
		Label title = new Label("Testing");
		title.getStyleClass().add("page-title");
		HBox titleRow = new HBox(12, title, badge);
		titleRow.setAlignment(Pos.CENTER_LEFT);
		Label sub = new Label("Everything in development lands here first. Try what's new, tell us what works and what breaks.");
		sub.getStyleClass().add("page-subtitle");
		sub.setWrapText(true);

		Label current = new Label(AppVersion.VERSION);
		current.getStyleClass().add("tester-build-number");
		Label currentLabel = new Label("YOU'RE ON");
		currentLabel.getStyleClass().add("t-overline");
		VBox currentBox = new VBox(2, currentLabel, current);

		Label status = new Label();
		status.getStyleClass().add("settings-row-hint");
		status.setWrapText(true);
		ProgressBar bar = new ProgressBar(0);
		bar.setMaxWidth(Double.MAX_VALUE);
		bar.setVisible(false);
		bar.setManaged(false);
		Button action = new Button("Check for updates");
		action.getStyleClass().add("primary-button");

		String newest = builds.isEmpty() ? null : builds.get(0).getAsJsonObject().get("version").getAsString();
		String ready = TesterUpdate.readyVersion();
		if (ready != null) {
			status.setText("Build " + ready + " is downloaded - restart to start it.");
			action.setText("Restart now");
		} else if (newest != null && net.veloclient.launcher.launch.UpdateService.compare(newest, AppVersion.VERSION) > 0) {
			status.setText("Build " + newest + " is out - get it now.");
			action.setText("Update to " + newest);
		} else if (newest != null) {
			status.setText("You have the newest tester build.");
		}
		action.setOnAction(e -> {
			if (TesterUpdate.readyVersion() != null) {
				TesterUpdate.restartNow();
				return;
			}
			action.setDisable(true);
			bar.setVisible(true);
			bar.setManaged(true);
			CompletableFuture.runAsync(() -> {
				TesterUpdate.Result r = TesterUpdate.check(text -> Platform.runLater(() -> status.setText(text)),
						p -> Platform.runLater(() -> bar.setProgress(p)));
				Platform.runLater(() -> {
					action.setDisable(false);
					bar.setVisible(false);
					bar.setManaged(false);
					if (r.ready()) {
						status.setText("Build " + TesterUpdate.readyVersion() + " is ready - restart to start it.");
						action.setText("Restart now");
					} else if (r.problem() != null && !"not-tester".equals(r.problem())) {
						status.setText("Update failed: " + r.problem());
					} else {
						status.setText("You have the newest tester build.");
						load(page);
					}
				});
			});
		});

		VBox right = new VBox(8, status, bar, action);
		right.setAlignment(Pos.CENTER_RIGHT);
		right.setMaxWidth(360);
		HBox.setHgrow(right, Priority.ALWAYS);
		Region gap = new Region();
		HBox.setHgrow(gap, Priority.ALWAYS);
		HBox card = new HBox(20, currentBox, gap, right);
		card.setAlignment(Pos.CENTER_LEFT);
		card.getStyleClass().addAll("settings-card", "tester-hero");
		return new VBox(10, titleRow, sub, card);
	}

	private static Node notice(String title, String text) {
		Label t = new Label(title);
		t.getStyleClass().add("settings-card-title");
		Label body = new Label(text);
		body.getStyleClass().add("settings-row-hint");
		body.setWrapText(true);
		VBox card = new VBox(8, t, body);
		card.getStyleClass().add("settings-card");
		return card;
	}

	// ---- one build: what changed, what to test ----

	private static Node buildCard(JsonObject build, JsonObject stat, boolean newest) {
		String version = build.get("version").getAsString();
		JsonObject notes = build.has("notes") && build.get("notes").isJsonObject() ? build.getAsJsonObject("notes") : new JsonObject();
		Label versionLabel = new Label(version);
		versionLabel.getStyleClass().add("tester-version");
		HBox head = new HBox(10, versionLabel);
		head.setAlignment(Pos.CENTER_LEFT);
		if (version.equals(AppVersion.VERSION)) {
			head.getChildren().add(pill("You're on this", "tester-pill-current"));
		} else if (newest && net.veloclient.launcher.launch.UpdateService.compare(version, AppVersion.VERSION) > 0) {
			head.getChildren().add(pill("New", "tester-pill-new"));
		}
		if (notes.has("createdAt")) {
			Label when = new Label(ago(notes.get("createdAt").getAsLong()));
			when.getStyleClass().add("t-muted");
			head.getChildren().add(when);
		}
		VBox card = new VBox(12, head);
		card.getStyleClass().addAll("settings-card", "tester-build");
		if (notes.has("title")) {
			Label t = new Label(notes.get("title").getAsString());
			t.getStyleClass().add("tester-build-title");
			t.setWrapText(true);
			card.getChildren().add(t);
		}
		if (notes.has("summary")) {
			Label s = new Label(notes.get("summary").getAsString());
			s.getStyleClass().add("settings-row-hint");
			s.setWrapText(true);
			card.getChildren().add(s);
		}
		if (notes.has("changes") && notes.get("changes").isJsonArray() && !notes.getAsJsonArray("changes").isEmpty()) {
			card.getChildren().add(section("WHAT CHANGED"));
			VBox list = new VBox(5);
			for (JsonElement c : notes.getAsJsonArray("changes")) {
				Label dot = new Label("•");
				dot.getStyleClass().add("tester-dot");
				Label text = new Label(c.getAsString());
				text.setWrapText(true);
				text.getStyleClass().add("tester-change");
				HBox row = new HBox(8, dot, text);
				HBox.setHgrow(text, Priority.ALWAYS);
				list.getChildren().add(row);
			}
			card.getChildren().add(list);
		}
		if (notes.has("test") && notes.get("test").isJsonArray() && !notes.getAsJsonArray("test").isEmpty()) {
			JsonArray tests = notes.getAsJsonArray("test");
			JsonObject feedback = stat.has("feedback") ? stat.getAsJsonObject("feedback") : new JsonObject();
			Set<String> used = new HashSet<>();
			if (stat.has("features")) {
				stat.getAsJsonArray("features").forEach(f -> used.add(f.getAsString()));
			}
			long done = tests.asList().stream().filter(t -> feedback.has(t.getAsJsonObject().get("id").getAsString())).count();
			card.getChildren().add(section("WHAT TO TEST  ·  " + done + " / " + tests.size() + " DONE"));
			VBox items = new VBox(8);
			for (JsonElement t : tests) {
				items.getChildren().add(testRow(version, t.getAsJsonObject(), feedback, used));
			}
			card.getChildren().add(items);
		}
		return card;
	}

	private static Node testRow(String version, JsonObject item, JsonObject feedback, Set<String> used) {
		String id = item.get("id").getAsString();
		Label title = new Label(item.has("title") ? item.get("title").getAsString() : id);
		title.getStyleClass().add("tester-item-title");
		title.setWrapText(true);
		HBox titleRow = new HBox(8, title);
		titleRow.setAlignment(Pos.CENTER_LEFT);
		if (item.has("modules") && item.get("modules").isJsonArray()) {
			boolean touched = item.getAsJsonArray("modules").asList().stream().anyMatch(m -> used.contains(m.getAsString()));
			if (touched) {
				titleRow.getChildren().add(pill("Used in game", "tester-pill-used"));
			}
		}
		Label state = new Label();
		Runnable showState = () -> {
			JsonObject f = feedback.has(id) ? feedback.getAsJsonObject(id) : null;
			String s = f == null ? null : f.get("state").getAsString();
			state.getStyleClass().removeAll("tester-pill-works", "tester-pill-broken", "tester-pill-skip");
			state.getStyleClass().add("tester-pill");
			if (s == null) {
				state.setText("Not tested");
				state.getStyleClass().add("tester-pill-skip");
			} else {
				state.setText("works".equals(s) ? "Works" : "broken".equals(s) ? "Broken" : "Skipped");
				state.getStyleClass().add("works".equals(s) ? "tester-pill-works" : "broken".equals(s) ? "tester-pill-broken" : "tester-pill-skip");
			}
		};
		showState.run();
		Region gap = new Region();
		HBox.setHgrow(gap, Priority.ALWAYS);
		titleRow.getChildren().addAll(gap, state);

		VBox box = new VBox(6, titleRow);
		if (item.has("how")) {
			Label how = new Label(item.get("how").getAsString());
			how.getStyleClass().add("settings-row-hint");
			how.setWrapText(true);
			box.getChildren().add(how);
		}
		TextField comment = new TextField();
		comment.setPromptText("What happened? (optional)");
		JsonObject existing = feedback.has(id) ? feedback.getAsJsonObject(id) : null;
		if (existing != null && existing.has("comment") && !existing.get("comment").isJsonNull()) {
			comment.setText(existing.get("comment").getAsString());
		}
		HBox.setHgrow(comment, Priority.ALWAYS);
		Button works = new Button("Works");
		works.getStyleClass().addAll("ghost-button", "tester-works");
		Button broken = new Button("Broken");
		broken.getStyleClass().addAll("ghost-button", "tester-broken");
		Button skip = new Button("Skip");
		skip.getStyleClass().add("ghost-button");
		for (Button b : new Button[] {works, broken, skip}) {
			String value = b == works ? "works" : b == broken ? "broken" : "skip";
			b.setOnAction(e -> {
				JsonObject ev = TesterApi.eventOf("feedback", version);
				ev.addProperty("item", id);
				ev.addProperty("state", value);
				if (!comment.getText().isBlank()) {
					ev.addProperty("comment", comment.getText().strip());
				}
				TesterApi.event(ev);
				JsonObject local = new JsonObject();
				local.addProperty("state", value);
				feedback.add(id, local);
				showState.run();
			});
		}
		HBox actions = new HBox(8, comment, works, broken, skip);
		actions.setAlignment(Pos.CENTER_LEFT);
		box.getChildren().add(actions);
		box.getStyleClass().add("tester-item");
		return box;
	}

	// ---- owner: who really tests ----

	private static Node testersCard(JsonObject overview) {
		Label title = new Label("Testers");
		title.getStyleClass().add("settings-card-title");
		Label hint = new Label("Per build: downloaded, launched, minutes played, test items answered, broken reports. Only you see this.");
		hint.getStyleClass().add("settings-row-hint");
		hint.setWrapText(true);
		VBox card = new VBox(10, title, hint);
		card.getStyleClass().addAll("settings-card", "tester-owner");
		JsonArray builds = overview.has("builds") ? overview.getAsJsonArray("builds") : new JsonArray();
		JsonArray testers = overview.has("testers") ? overview.getAsJsonArray("testers") : new JsonArray();
		if (testers.isEmpty()) {
			Label none = new Label("Nobody yet - give someone the Tester role on Discord (they need to /link their account).");
			none.getStyleClass().add("t-muted");
			none.setWrapText(true);
			card.getChildren().add(none);
			return card;
		}
		for (JsonElement t : testers) {
			JsonObject tester = t.getAsJsonObject();
			String name = tester.get("name").getAsString();
			JsonObject per = tester.has("builds") ? tester.getAsJsonObject("builds") : new JsonObject();
			Node head = Heads.head(tester.get("uuid").getAsString(), name, 28);
			Label nameLabel = new Label(name);
			nameLabel.getStyleClass().add("t-strong");
			long last = 0;
			for (var e : per.entrySet()) {
				JsonObject s = e.getValue().getAsJsonObject();
				last = Math.max(last, s.has("lastSeen") ? s.get("lastSeen").getAsLong() : 0);
			}
			Label lastLabel = new Label(last == 0 ? "never active" : "active " + ago(last));
			lastLabel.getStyleClass().add(last == 0 ? "tester-pill-broken" : "t-muted");
			Region gap = new Region();
			HBox.setHgrow(gap, Priority.ALWAYS);
			HBox row = new HBox(10, head, nameLabel, gap, lastLabel);
			row.setAlignment(Pos.CENTER_LEFT);
			FlowPane stats = new FlowPane(8, 6);
			int shown = 0;
			for (JsonElement b : builds) {
				if (shown++ >= 3) {
					break;
				}
				JsonObject build = b.getAsJsonObject();
				String version = build.get("version").getAsString();
				int items = 0;
				JsonObject notes = build.has("notes") && build.get("notes").isJsonObject() ? build.getAsJsonObject("notes") : new JsonObject();
				if (notes.has("test")) {
					items = notes.getAsJsonArray("test").size();
				}
				stats.getChildren().add(statChip(version, per.has(version) ? per.getAsJsonObject(version) : null, items));
			}
			VBox entry = new VBox(6, row, stats);
			entry.getStyleClass().add("tester-owner-row");
			card.getChildren().add(entry);
		}
		return card;
	}

	private static Node statChip(String version, JsonObject s, int items) {
		Label v = new Label(version);
		v.getStyleClass().add("t-mono");
		String text;
		String style;
		if (s == null || (!s.has("downloadedAt") || s.get("downloadedAt").getAsLong() == 0) && s.get("launches").getAsInt() == 0) {
			text = "not downloaded";
			style = "tester-chip-none";
		} else {
			int launches = s.has("launches") ? s.get("launches").getAsInt() : 0;
			long minutes = Math.round(s.has("minutes") ? s.get("minutes").getAsDouble() : 0);
			JsonObject fb = s.has("feedback") ? s.getAsJsonObject("feedback") : new JsonObject();
			long broken = fb.entrySet().stream().filter(e -> "broken".equals(e.getValue().getAsJsonObject().get("state").getAsString())).count();
			text = "↓  " + launches + " launches  ·  " + minutes + " min  ·  " + fb.size() + "/" + items + " tested" + (broken > 0 ? "  ·  " + broken + " broken" : "");
			style = minutes >= 10 && fb.size() > 0 ? "tester-chip-good" : "tester-chip-some";
		}
		Label t = new Label(text);
		HBox chip = new HBox(8, v, t);
		chip.setAlignment(Pos.CENTER_LEFT);
		chip.getStyleClass().addAll("tester-chip", style);
		return chip;
	}

	// ---- bits ----

	private static Label section(String text) {
		Label l = new Label(text);
		l.getStyleClass().add("t-overline");
		return l;
	}

	private static Label pill(String text, String style) {
		Label l = new Label(text);
		l.getStyleClass().addAll("tester-pill", style);
		return l;
	}

	static String ago(long millis) {
		Duration d = Duration.between(Instant.ofEpochMilli(millis), Instant.now());
		if (d.toMinutes() < 2) {
			return "just now";
		}
		if (d.toHours() < 1) {
			return d.toMinutes() + " min ago";
		}
		if (d.toDays() < 1) {
			return d.toHours() + " h ago";
		}
		return d.toDays() + (d.toDays() == 1 ? " day ago" : " days ago");
	}

	/**
	 * After a tester update: what's new in the build you're now on, once per build (a card over the launcher). The
	 * host opens the Testing page from its button.
	 */
	public static void maybeShowWhatsNew(StackPane layers, Runnable openTesting) {
		String version = AppVersion.VERSION;
		java.nio.file.Path marker = net.veloclient.launcher.data.VeloPaths.config().resolve("tester-seen-" + version);
		if (java.nio.file.Files.exists(marker)) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			JsonObject notes = null;
			try {
				for (JsonElement b : TesterApi.builds()) {
					JsonObject build = b.getAsJsonObject();
					if (build.get("version").getAsString().equals(version) && build.has("notes")) {
						notes = build.getAsJsonObject("notes");
					}
				}
			} catch (TesterApi.TesterError e) {
				return;
			}
			if (notes == null) {
				return;
			}
			JsonObject n = notes;
			Platform.runLater(() -> {
				try {
					java.nio.file.Files.createDirectories(marker.getParent());
					java.nio.file.Files.writeString(marker, "1");
				} catch (java.io.IOException ignored) {
					// shows again next time
				}
				showWhatsNew(layers, version, n, openTesting);
			});
		});
	}

	private static void showWhatsNew(StackPane layers, String version, JsonObject notes, Runnable openTesting) {
		StackPane dim = new StackPane();
		dim.getStyleClass().add("tester-dim");
		Label badge = new Label("TESTER BUILD " + version);
		badge.getStyleClass().add("tester-badge");
		Label title = new Label(notes.has("title") ? notes.get("title").getAsString() : "What's new");
		title.getStyleClass().add("tester-popup-title");
		title.setWrapText(true);
		VBox body = new VBox(6);
		if (notes.has("changes")) {
			int n = 0;
			for (JsonElement c : notes.getAsJsonArray("changes")) {
				if (n++ >= 8) {
					break;
				}
				Label l = new Label("•  " + c.getAsString());
				l.setWrapText(true);
				l.getStyleClass().add("tester-change");
				body.getChildren().add(l);
			}
		}
		int tests = notes.has("test") ? notes.getAsJsonArray("test").size() : 0;
		Label testLine = new Label(tests == 0 ? "" : tests + (tests == 1 ? " thing" : " things") + " to test in this build.");
		testLine.getStyleClass().add("t-strong");
		Button open = new Button(tests == 0 ? "Open Testing" : "Start testing");
		open.getStyleClass().add("primary-button");
		Button later = new Button("Later");
		later.getStyleClass().add("ghost-button");
		HBox buttons = new HBox(10, later, open);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		VBox card = new VBox(14, badge, title, body, testLine, buttons);
		card.getStyleClass().add("tester-popup");
		card.setMaxWidth(520);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		dim.getChildren().add(card);
		Runnable close = () -> layers.getChildren().remove(dim);
		later.setOnAction(e -> close.run());
		open.setOnAction(e -> {
			close.run();
			openTesting.run();
		});
		layers.getChildren().add(dim);
	}

	/** A newer tester build was downloaded in the background: restart now or later. */
	public static void showUpdateReady(StackPane layers, String version, JsonObject build) {
		if (version == null) {
			return;
		}
		JsonObject notes = build != null && build.has("notes") && build.get("notes").isJsonObject() ? build.getAsJsonObject("notes") : new JsonObject();
		StackPane dim = new StackPane();
		dim.getStyleClass().add("tester-dim");
		Label badge = new Label("NEW TESTER BUILD");
		badge.getStyleClass().add("tester-badge");
		Label title = new Label(version + (notes.has("title") ? "  ·  " + notes.get("title").getAsString() : ""));
		title.getStyleClass().add("tester-popup-title");
		title.setWrapText(true);
		Label text = new Label("It's downloaded. Restart to start it - the launcher closes and opens again by itself.");
		text.getStyleClass().add("settings-row-hint");
		text.setWrapText(true);
		Button restart = new Button("Restart now");
		restart.getStyleClass().add("primary-button");
		Button later = new Button("Later");
		later.getStyleClass().add("ghost-button");
		HBox buttons = new HBox(10, later, restart);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		VBox card = new VBox(14, badge, title, text, buttons);
		card.getStyleClass().add("tester-popup");
		card.setMaxWidth(480);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		dim.getChildren().add(card);
		later.setOnAction(e -> layers.getChildren().remove(dim));
		restart.setOnAction(e -> TesterUpdate.restartNow());
		layers.getChildren().add(dim);
	}
}
