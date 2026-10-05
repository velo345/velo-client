package net.veloclient.launcher.ui;

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
import net.veloclient.launcher.social.NewsApi;

import java.time.Instant;
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
		filter.getItems().addAll("Open", "All", "Crashes", "Bugs", "Fixed");
		filter.setValue("Open");
		Button refresh = new Button("Refresh");
		refresh.getStyleClass().add("glass-button");
		HBox top = new HBox(10, back, title, spacer, filter, refresh);
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
		render[0] = () -> {
			list.getChildren().clear();
			String f = filter.getValue();
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
		load[0].run();
		return new VBox(14, top, body);
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
				+ (version != null ? "  ·  Velo " + version : "") + (mc != null ? "  ·  MC " + mc : ""));
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
			content.putString(new GsonBuilder().setPrettyPrinting().create().toJson(r));
			Clipboard.getSystemClipboard().setContent(content);
			copy.setText("Copied");
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
		HBox actions = new HBox(8, kind, spacer, new Label("Status"), status, copy, delete);
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
