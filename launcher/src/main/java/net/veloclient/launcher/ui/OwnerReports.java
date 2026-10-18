package net.veloclient.launcher.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.social.StoreApi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Owner panel > Reports: chat reports from players, with the attached messages; dismiss or ban the reported player from services. */
final class OwnerReports {

	private OwnerReports() {
	}

	static Node build() {
		VBox list = new VBox(10);
		list.getChildren().add(Skeleton.list(5));
		load(list);
		return list;
	}

	private static void load(VBox list) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return (Object) StoreApi.staffReports("open");
			} catch (StoreApi.StoreError e) {
				return e.getMessage();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			list.getChildren().clear();
			if (result instanceof String error) {
				list.getChildren().add(hint(error));
				return;
			}
			var reports = ((JsonObject) result).getAsJsonArray("reports");
			if (reports.isEmpty()) {
				list.getChildren().add(hint("No open reports. Chat reports from players land here (and in Discord, if the reports webhook is set)."));
				return;
			}
			for (JsonElement element : reports) {
				list.getChildren().add(card(element.getAsJsonObject(), list));
			}
			UiMotion.stagger(list, 12);
		}));
	}

	private static Node card(JsonObject r, VBox list) {
		String id = r.get("id").getAsString();
		Label tag = new Label(r.get("category").getAsString().toUpperCase(Locale.ROOT));
		tag.setStyle("-fx-background-color: #d93a3a; -fx-background-radius: 8; -fx-padding: 2 8 2 8; -fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: 800;");
		Label who = new Label(r.get("reporterName").getAsString() + "  reported  " + r.get("targetName").getAsString());
		who.setStyle("-fx-font-weight: 700; -fx-text-fill: white;");
		String when = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(r.get("at").getAsLong()));
		Label meta = hint(("party".equals(r.get("context").getAsString()) ? "Party chat" : "Direct messages") + "  ·  " + when);
		HBox top = new HBox(10, tag, who);
		top.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(6, top, meta);
		String reason = r.has("reason") && !r.get("reason").isJsonNull() ? r.get("reason").getAsString() : "";
		if (!reason.isBlank()) {
			Label note = new Label("“" + reason + "”");
			note.setWrapText(true);
			note.setStyle("-fx-text-fill: white;");
			card.getChildren().add(note);
		}
		var messages = r.getAsJsonArray("messages");
		if (messages.isEmpty()) {
			card.getChildren().add(hint("No messages attached."));
		} else {
			VBox chat = new VBox(2);
			chat.setPadding(new Insets(8));
			chat.setStyle("-fx-background-color: rgba(0,0,0,0.25); -fx-background-radius: 8;");
			for (JsonElement m : messages) {
				JsonObject o = m.getAsJsonObject();
				Label line = new Label(o.get("fromName").getAsString() + ": " + o.get("text").getAsString());
				line.setWrapText(true);
				line.setStyle("-fx-text-fill: " + (o.get("fromName").getAsString().equals(r.get("targetName").getAsString()) ? "#ffb4b4" : "#c8c8d0")
						+ "; -fx-font-size: 12px;");
				chat.getChildren().add(line);
			}
			card.getChildren().add(chat);
		}
		Label result = hint("");
		Button dismiss = new Button("Dismiss");
		dismiss.getStyleClass().add("ghost-button");
		Button ban7 = new Button("Ban 7 days");
		ban7.getStyleClass().add("danger-ghost-button");
		Button banForever = new Button("Ban for good");
		banForever.getStyleClass().add("danger-ghost-button");
		dismiss.setOnAction(e -> resolve(id, false, 0, list, result));
		ban7.setOnAction(e -> resolve(id, true, 7, list, result));
		banForever.setOnAction(e -> resolve(id, true, 0, list, result));
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox actions = new HBox(8, result, grow, dismiss, ban7, banForever);
		actions.setAlignment(Pos.CENTER_LEFT);
		card.getChildren().add(actions);
		card.getChildren().add(hint("A ban stops their friends system and parties (in game: /velostaff " + r.get("targetName").getAsString() + " for more)."));
		card.getStyleClass().add("settings-card");
		return card;
	}

	private static void resolve(String id, boolean ban, int days, VBox list, Label result) {
		result.setText("Working...");
		CompletableFuture.supplyAsync(() -> {
			try {
				StoreApi.staffResolve(id, ban, days);
				return null;
			} catch (StoreApi.StoreError e) {
				return e.getMessage();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(error -> Platform.runLater(() -> {
			if (error != null) {
				result.setText(error);
			} else {
				load(list);
			}
		}));
	}

	private static Label hint(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("settings-row-hint");
		label.setWrapText(true);
		return label;
	}
}
