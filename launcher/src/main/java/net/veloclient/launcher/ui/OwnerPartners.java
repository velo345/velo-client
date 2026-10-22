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
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.social.StoreApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Owner panel, Partner servers: the servers shown at the top of Discover (launcher and game), in this order. Add, edit,
 * reorder and remove, then Save - players see the new list the next time they open Discover.
 */
final class OwnerPartners {

	private record Row(TextField name, TextField address, TextField description, TextField versions, TextField accent, String id) {
	}

	private OwnerPartners() {
	}

	static Node build() {
		VBox rows = new VBox(10);
		List<Row> list = new ArrayList<>();
		Label status = new Label("Loading...");
		status.getStyleClass().add("settings-row-hint");
		Runnable[] redraw = new Runnable[1];
		redraw[0] = () -> {
			rows.getChildren().clear();
			for (int i = 0; i < list.size(); i++) {
				rows.getChildren().add(rowNode(list, i, redraw[0]));
			}
		};
		Button add = new Button("Add partner server");
		add.getStyleClass().add("ghost-button");
		add.setOnAction(e -> {
			list.add(newRow(null, "", "", "", "", ""));
			redraw[0].run();
		});
		Button save = new Button("Save");
		save.getStyleClass().add("primary-button");
		save.setOnAction(e -> {
			JsonArray out = new JsonArray();
			for (Row r : list) {
				JsonObject p = new JsonObject();
				if (r.id() != null) {
					p.addProperty("id", r.id());
				}
				p.addProperty("name", r.name().getText().trim());
				p.addProperty("address", r.address().getText().trim());
				p.addProperty("description", r.description().getText().trim());
				p.addProperty("versions", r.versions().getText().trim());
				if (!r.accent().getText().isBlank()) {
					p.addProperty("accent", r.accent().getText().trim());
				}
				out.add(p);
			}
			status.setText("Saving...");
			CompletableFuture.supplyAsync(() -> {
				try {
					StoreApi.saveAdminPartners(out);
					return "Saved - " + out.size() + " partner server" + (out.size() == 1 ? "" : "s") + " in Discover";
				} catch (StoreApi.StoreError error) {
					return error.getMessage();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(text -> Platform.runLater(() -> status.setText(text)));
		});
		CompletableFuture.supplyAsync(() -> {
			try {
				return StoreApi.adminPartners();
			} catch (StoreApi.StoreError error) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(json -> Platform.runLater(() -> {
			if (json == null) {
				status.setText("Couldn't load the partner list");
				return;
			}
			for (JsonElement e : json.getAsJsonArray("partners")) {
				JsonObject p = e.getAsJsonObject();
				list.add(newRow(text(p, "id"), text(p, "name"), text(p, "address"), text(p, "description"), text(p, "versions"), text(p, "accent")));
			}
			status.setText(list.isEmpty() ? "No partner servers yet - add your first one." : "");
			redraw[0].run();
		}));
		Label title = new Label("Partner servers");
		title.getStyleClass().add("settings-card-title");
		Label hint = new Label("Shown first in Discover, in the launcher (Servers page) and in game (title screen > Discover). Players join them directly.");
		hint.getStyleClass().add("settings-row-hint");
		hint.setWrapText(true);
		HBox actions = new HBox(8, add, save);
		VBox card = new VBox(12, title, hint, rows, actions, status);
		card.getStyleClass().add("settings-card");
		return card;
	}

	private static String text(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static Row newRow(String id, String name, String address, String description, String versions, String accent) {
		TextField n = field(name, "Name", 160);
		TextField a = field(address, "play.example.net", 180);
		TextField d = field(description, "One line about it", 260);
		TextField v = field(versions, "1.21.11 - 26.3", 110);
		TextField c = field(accent, "#ff2d3c", 80);
		return new Row(n, a, d, v, c, id == null || id.isEmpty() ? null : id);
	}

	private static TextField field(String value, String prompt, double width) {
		TextField f = new TextField(value);
		f.setPromptText(prompt);
		f.setPrefWidth(width);
		return f;
	}

	private static Node rowNode(List<Row> list, int index, Runnable redraw) {
		Row r = list.get(index);
		Button up = new Button("↑");
		up.getStyleClass().add("ghost-button");
		up.setDisable(index == 0);
		up.setOnAction(e -> {
			list.add(index - 1, list.remove(index));
			redraw.run();
		});
		Button down = new Button("↓");
		down.getStyleClass().add("ghost-button");
		down.setDisable(index == list.size() - 1);
		down.setOnAction(e -> {
			list.add(index + 1, list.remove(index));
			redraw.run();
		});
		Button remove = new Button("Remove");
		remove.getStyleClass().add("danger-ghost-button");
		remove.setOnAction(e -> {
			list.remove(index);
			redraw.run();
		});
		HBox.setHgrow(r.description(), Priority.ALWAYS);
		HBox row = new HBox(8, r.name(), r.address(), r.description(), r.versions(), r.accent(), up, down, remove);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(4, 0, 4, 0));
		return row;
	}
}
