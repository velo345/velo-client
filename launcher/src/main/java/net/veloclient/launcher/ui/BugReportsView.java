package net.veloclient.launcher.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import net.veloclient.launcher.social.NewsApi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Owner-only inbox of bug and crash reports players sent (from the game's pause menu, the
 * "crashed last time" prompt, or the launcher's crash dialog): a filterable list on the left,
 * everything about the selected report on the right (message, system, mods, crash, log).
 */
final class BugReportsView {

	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

	private BugReportsView() {
	}

	static Node build(NewsView.Host host) {
		Button back = new Button("←  News");
		back.getStyleClass().add("ghost-button");
		back.setOnAction(e -> host.openNews());
		Label title = new Label("Bug reports");
		title.getStyleClass().add("page-title");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		ComboBox<String> filter = new ComboBox<>();
		filter.getItems().addAll("Open", "Grouped by cause", "All", "Crashes", "Bugs", "Fixed");
		filter.setValue("Open");
		Button refresh = new Button("Refresh");
		refresh.getStyleClass().add("glass-button");
		Button exportOpen = new Button("Export open");
		exportOpen.getStyleClass().add("glass-button");
		HBox top = new HBox(10, back, title, spacer, filter, exportOpen, refresh);
		top.setAlignment(Pos.CENTER_LEFT);

		VBox list = new VBox(8);
		list.setPadding(new Insets(0, 6, 10, 0));
		ScrollPane listScroll = new ScrollPane(list);
		listScroll.setFitToWidth(true);
		listScroll.getStyleClass().add("scroll-pane");
		listScroll.setPrefWidth(340);
		listScroll.setMinWidth(300);
		StackPane detail = new StackPane(hint("Pick a report on the left."));
		HBox.setHgrow(detail, Priority.ALWAYS);
		HBox body = new HBox(16, listScroll, detail);
		VBox.setVgrow(body, Priority.ALWAYS);

		List<JsonObject> all = new ArrayList<>();
		Runnable[] render = new Runnable[1];
		Runnable[] load = new Runnable[1];
		String[] openGroup = {null};
		render[0] = () -> {
			list.getChildren().clear();
			String f = filter.getValue();
			if ("Grouped by cause".equals(f)) {
				renderGroups(host, list, detail, all, openGroup, render[0], load[0]);
				return;
			}
			int shown = 0;
			for (JsonObject r : all) {
				String status = NewsUi.str(r, "status");
				boolean open = "new".equals(status) || "seen".equals(status);
				boolean keep = switch (f) {
					case "Open" -> open;
					case "Crashes" -> "crash".equals(NewsUi.str(r, "kind"));
					case "Bugs" -> "bug".equals(NewsUi.str(r, "kind"));
					case "Fixed" -> "fixed".equals(status) || "wontfix".equals(status);
					default -> true;
				};
				if (keep) {
					list.getChildren().add(row(r, () -> openReport(host, detail, r, load[0])));
					shown++;
				}
			}
			if (shown == 0) {
				list.getChildren().add(hint(all.isEmpty() ? "No reports yet." : "Nothing here."));
			}
		};
		load[0] = () -> NewsView.run(NewsApi::reports, reports -> {
			all.clear();
			for (JsonElement e : reports) {
				all.add(e.getAsJsonObject());
			}
			render[0].run();
		}, error -> list.getChildren().setAll(hint(error)));
		filter.valueProperty().addListener((o, a, v) -> render[0].run());
		refresh.setOnAction(e -> load[0].run());
		exportOpen.setOnAction(e -> exportOpen(host, all, exportOpen));
		load[0].run();
		return new VBox(14, top, body);
	}

	/**
	 * Reports clustered by cause (the server's {@code group}: same root exception and top stack
	 * frames, or the same title), biggest open group first. Opening a group lists its reports and
	 * offers to mark the whole group fixed at once.
	 */
	private static void renderGroups(NewsView.Host host, VBox list, StackPane detail, List<JsonObject> all, String[] openGroup,
			Runnable rerender, Runnable reload) {
		java.util.Map<String, List<JsonObject>> groups = new java.util.LinkedHashMap<>();
		for (JsonObject r : all) {
			String group = NewsUi.str(r, "group");
			groups.computeIfAbsent(group == null ? NewsUi.str(r, "id") : group, k -> new ArrayList<>()).add(r);
		}
		if (openGroup[0] != null && groups.containsKey(openGroup[0])) {
			List<JsonObject> members = groups.get(openGroup[0]);
			Button back = new Button("←  All groups");
			back.getStyleClass().add("ghost-button");
			back.setOnAction(e -> {
				openGroup[0] = null;
				rerender.run();
			});
			Button fixAll = new Button("Mark group fixed");
			fixAll.getStyleClass().add("glass-button");
			fixAll.setOnAction(e -> {
				fixAll.setDisable(true);
				NewsView.run(() -> {
					for (JsonObject r : members) {
						String status = NewsUi.str(r, "status");
						if (!"fixed".equals(status) && !"wontfix".equals(status)) {
							NewsApi.reportStatus(NewsUi.str(r, "id"), "fixed");
						}
					}
					return null;
				}, done -> reload.run(), error -> fixAll.setDisable(false));
			});
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			HBox bar = new HBox(8, back, spacer, fixAll);
			bar.setAlignment(Pos.CENTER_LEFT);
			list.getChildren().add(bar);
			for (JsonObject r : members) {
				list.getChildren().add(row(r, () -> openReport(host, detail, r, reload)));
			}
			return;
		}
		openGroup[0] = null;
		List<java.util.Map.Entry<String, List<JsonObject>>> sorted = new ArrayList<>(groups.entrySet());
		sorted.sort(java.util.Comparator.comparingLong((java.util.Map.Entry<String, List<JsonObject>> e) -> -openCount(e.getValue()))
				.thenComparingLong(e -> -e.getValue().size()));
		for (var entry : sorted) {
			List<JsonObject> members = entry.getValue();
			JsonObject first = members.get(0);
			long open = openCount(members);
			long players = members.stream().map(r -> NewsUi.str(r, "username")).filter(java.util.Objects::nonNull).distinct().count();
			java.util.Set<String> versions = new java.util.TreeSet<>();
			for (JsonObject r : members) {
				String v = NewsUi.str(r, "version");
				if (v != null) {
					versions.add(v);
				}
			}
			boolean crash = "crash".equals(NewsUi.str(first, "kind"));
			Label kind = new Label(crash ? "CRASH" : "BUG");
			kind.getStyleClass().addAll("news-tag", crash ? "report-kind-crash" : "report-kind-bug");
			Label count = new Label(members.size() + (members.size() == 1 ? " report" : " reports"));
			count.getStyleClass().addAll("news-tag", open > 0 ? "report-status-new" : "report-status-fixed");
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			Label when = new Label(NewsUi.date(NewsUi.num(first, "createdAt")));
			when.getStyleClass().add("news-card-date");
			HBox top = new HBox(6, kind, count, spacer, when);
			top.setAlignment(Pos.CENTER_LEFT);
			String label = NewsUi.str(first, "groupLabel");
			Label title = new Label(label == null || label.isBlank() ? NewsUi.str(first, "title") : label);
			title.getStyleClass().add("news-card-title");
			title.setWrapText(true);
			Label meta = new Label(open + " open  ·  " + players + (players == 1 ? " player" : " players")
					+ (versions.isEmpty() ? "" : "  ·  Velo " + String.join(", ", versions)));
			meta.getStyleClass().add("news-card-date");
			VBox card = new VBox(6, top, title, meta);
			card.getStyleClass().add("report-row");
			card.setOnMouseClicked(e -> {
				openGroup[0] = entry.getKey();
				rerender.run();
			});
			list.getChildren().add(card);
		}
		if (groups.isEmpty()) {
			list.getChildren().add(hint("No reports yet."));
		}
	}

	private static long openCount(List<JsonObject> reports) {
		return reports.stream().map(r -> NewsUi.str(r, "status")).filter(s -> s == null || "new".equals(s) || "seen".equals(s)).count();
	}

	/**
	 * Saves every report that isn't marked fixed / won't fix (new and seen alike) as one JSON file
	 * with the full details (crash report, log, mods) - easy to hand to whoever fixes them.
	 */
	private static void exportOpen(NewsView.Host host, List<JsonObject> all, Button button) {
		List<String> ids = new ArrayList<>();
		for (JsonObject r : all) {
			String status = NewsUi.str(r, "status");
			if (status == null || "new".equals(status) || "seen".equals(status)) {
				ids.add(NewsUi.str(r, "id"));
			}
		}
		if (ids.isEmpty()) {
			button.setText("Nothing open");
			return;
		}
		Path target = chooseFile(host, "velo-open-reports-" + LocalDate.now() + ".json");
		if (target == null) {
			return;
		}
		button.setDisable(true);
		button.setText("Exporting...");
		NewsView.run(() -> {
			JsonArray full = new JsonArray();
			for (String id : ids) {
				full.add(NewsApi.report(id));
			}
			Files.writeString(target, PRETTY.toJson(full), StandardCharsets.UTF_8);
			return full.size();
		}, count -> {
			button.setDisable(false);
			button.setText("Exported " + count);
		}, error -> {
			button.setDisable(false);
			button.setText("Export failed");
			button.setTooltip(new javafx.scene.control.Tooltip(error));
		});
	}

	private static Path chooseFile(NewsView.Host host, String suggestedName) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Export reports");
		chooser.setInitialFileName(suggestedName);
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
		Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
		if (Files.isDirectory(downloads)) {
			chooser.setInitialDirectory(downloads.toFile());
		}
		File file = chooser.showSaveDialog(host.owner());
		return file == null ? null : file.toPath();
	}

	private static Label hint(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("empty-state");
		label.setWrapText(true);
		return label;
	}

	private static Node row(JsonObject r, Runnable onOpen) {
		boolean crash = "crash".equals(NewsUi.str(r, "kind"));
		Label kind = new Label(crash ? "CRASH" : "BUG");
		kind.getStyleClass().addAll("news-tag", crash ? "report-kind-crash" : "report-kind-bug");
		Label status = statusChip(NewsUi.str(r, "status"));
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Label when = new Label(NewsUi.date(NewsUi.num(r, "createdAt")));
		when.getStyleClass().add("news-card-date");
		HBox top = new HBox(6, kind, status, spacer, when);
		top.setAlignment(Pos.CENTER_LEFT);
		String headline = NewsUi.str(r, "title");
		if (headline == null || headline.isBlank()) {
			headline = NewsUi.str(r, "message");
		}
		Label title = new Label(headline == null || headline.isBlank() ? "(no message)" : headline);
		title.getStyleClass().add("news-card-title");
		title.setWrapText(true);
		title.setMaxHeight(40);
		String who = NewsUi.str(r, "username");
		String version = NewsUi.str(r, "version");
		String mc = NewsUi.str(r, "minecraft");
		Label meta = new Label((who == null ? "Anonymous" : who) + "  ·  " + NewsUi.str(r, "source")
				+ (version != null ? "  ·  Velo " + version : "") + (mc != null ? "  ·  MC " + mc : "")
				+ (r.has("auto") && r.get("auto").getAsBoolean() ? "  ·  auto" : ""));
		meta.getStyleClass().add("news-card-date");
		VBox card = new VBox(6, top, title, meta);
		card.getStyleClass().add("report-row");
		card.setOnMouseClicked(e -> onOpen.run());
		return card;
	}

	private static Label statusChip(String status) {
		Label chip = new Label(switch (status == null ? "new" : status) {
			case "seen" -> "Seen";
			case "fixed" -> "Fixed";
			case "wontfix" -> "Won't fix";
			default -> "New";
		});
		chip.getStyleClass().addAll("news-tag", "report-status-" + (status == null ? "new" : status));
		return chip;
	}

	private static void openReport(NewsView.Host host, StackPane detail, JsonObject summary, Runnable reload) {
		detail.getChildren().setAll(hint("Loading report..."));
		String id = NewsUi.str(summary, "id");
		NewsView.run(() -> NewsApi.report(id), report -> {
			detail.getChildren().setAll(details(host, report, reload));
			if ("new".equals(NewsUi.str(report, "status"))) {
				NewsView.run(() -> {
					NewsApi.reportStatus(id, "seen");
					return null;
				}, r -> { }, e -> { });
			}
		}, error -> detail.getChildren().setAll(hint(error)));
	}

	private static Node details(NewsView.Host host, JsonObject r, Runnable reload) {
		String id = NewsUi.str(r, "id");
		boolean crash = "crash".equals(NewsUi.str(r, "kind"));
		Label kind = new Label(crash ? "CRASH" : "BUG");
		kind.getStyleClass().addAll("news-tag", crash ? "report-kind-crash" : "report-kind-bug");
		String who = NewsUi.str(r, "username");
		Label title = new Label(NewsUi.str(r, "title") != null && !NewsUi.str(r, "title").isBlank() ? NewsUi.str(r, "title")
				: crash ? "Crash report" : "Bug report");
		title.getStyleClass().add("news-article-title");
		title.setWrapText(true);
		Label meta = new Label((who == null ? "Anonymous" : who) + "  ·  from the " + NewsUi.str(r, "source") + "  ·  "
				+ WHEN.format(Instant.ofEpochMilli(NewsUi.num(r, "createdAt")).atZone(ZoneId.systemDefault())) + "  ·  #" + id);
		meta.getStyleClass().add("news-card-date");

		ComboBox<String> status = new ComboBox<>();
		status.getItems().addAll("new", "seen", "fixed", "wontfix");
		status.setValue(NewsUi.str(r, "status") == null ? "new" : NewsUi.str(r, "status"));
		status.valueProperty().addListener((o, a, v) -> NewsView.run(() -> {
			NewsApi.reportStatus(id, v);
			return null;
		}, x -> reload.run(), e -> { }));
		Button copy = new Button("Copy all");
		copy.getStyleClass().add("glass-button");
		copy.setOnAction(e -> {
			ClipboardContent content = new ClipboardContent();
			content.putString(PRETTY.toJson(r));
			Clipboard.getSystemClipboard().setContent(content);
			copy.setText("Copied");
		});
		Button export = new Button("Export");
		export.getStyleClass().add("glass-button");
		export.setOnAction(e -> {
			Path target = chooseFile(host, "velo-report-" + id + ".json");
			if (target == null) {
				return;
			}
			try {
				Files.writeString(target, PRETTY.toJson(r), StandardCharsets.UTF_8);
				export.setText("Exported");
			} catch (IOException ex) {
				export.setText("Export failed");
			}
		});
		Button delete = new Button("Delete");
		delete.getStyleClass().add("danger-ghost-button");
		delete.setOnAction(e -> {
			if (Confirm.ask(host.owner(), "Delete this report?", "It can't be restored.")) {
				NewsView.run(() -> {
					NewsApi.deleteReport(id);
					return null;
				}, x -> reload.run(), err -> { });
			}
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox actions = new HBox(8, kind, spacer, new Label("Status"), status, copy, export, delete);
		actions.setAlignment(Pos.CENTER_LEFT);

		VBox content = new VBox(14, actions, title, meta);
		String message = NewsUi.str(r, "message");
		VBox messageBox = new VBox(6, sectionTitle("What they said"),
				NewsUi.richText(message == null || message.isBlank() ? "(No message - they only sent the technical details.)" : message, "news-text"));
		messageBox.getStyleClass().add("news-callout");
		content.getChildren().add(messageBox);

		if (r.has("system") && r.get("system").isJsonObject()) {
			GridPane grid = new GridPane();
			grid.setHgap(18);
			grid.setVgap(6);
			int row = 0;
			Map<String, String> sorted = new TreeMap<>();
			for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("system").entrySet()) {
				sorted.put(e.getKey(), e.getValue().isJsonNull() ? "" : e.getValue().getAsString());
			}
			for (Map.Entry<String, String> e : sorted.entrySet()) {
				Label key = new Label(e.getKey());
				key.getStyleClass().add("news-field-label");
				Label value = new Label(e.getValue());
				value.getStyleClass().add("report-value");
				value.setWrapText(true);
				grid.addRow(row++, key, value);
			}
			content.getChildren().add(new VBox(8, sectionTitle("System"), grid));
		}
		addList(content, r, "modules", "Enabled Velo modules");
		addList(content, r, "mods", "Mods");
		addText(content, NewsUi.str(r, "crash"), "Crash report", true);
		addText(content, NewsUi.str(r, "log"), "Log (end)", false);

		content.setPadding(new Insets(16, 18, 20, 18));
		content.getStyleClass().add("report-detail");
		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		return scroll;
	}

	private static Label sectionTitle(String text) {
		Label label = new Label(text.toUpperCase(Locale.ROOT));
		label.getStyleClass().add("news-section-label");
		return label;
	}

	private static void addList(VBox content, JsonObject r, String key, String title) {
		if (!r.has(key) || !r.get(key).isJsonArray()) {
			return;
		}
		JsonArray items = r.getAsJsonArray(key);
		List<String> lines = new ArrayList<>();
		items.forEach(e -> lines.add(e.getAsString()));
		TextArea area = mono(String.join("\n", lines), Math.min(12, Math.max(3, lines.size())));
		TitledPane pane = new TitledPane(title + " (" + lines.size() + ")", area);
		pane.setExpanded(false);
		content.getChildren().add(pane);
	}

	private static void addText(VBox content, String text, String title, boolean expanded) {
		if (text == null || text.isBlank()) {
			return;
		}
		TitledPane pane = new TitledPane(title, mono(text, 18));
		pane.setExpanded(expanded);
		content.getChildren().add(pane);
	}

	private static TextArea mono(String text, int rows) {
		TextArea area = new TextArea(text);
		area.setEditable(false);
		area.setPrefRowCount(rows);
		area.getStyleClass().add("mono-area");
		return area;
	}
}
