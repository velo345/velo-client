package net.veloclient.launcher.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.social.StoreApi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The owner's panel (only shown to owners - the server checks every call anyway):
 * <ul>
 * <li><b>Cosmetics</b> - every wing pair and cape with its status: <i>released</i> (in the store),
 * <i>testing</i> (only given out by you - whoever has it is seen with it by everyone) or <i>hidden</i>
 * (out of the store again, still worn by whoever owns it); price, "in the store until" for limited
 * drops, collection, and which collection the store features.</li>
 * <li><b>Players</b> - look players up, give or take coins and cosmetics; several names at once
 * (comma separated) for giveaways.</li>
 * <li><b>Collections</b> - name, tag and description of each drop, and new ones.</li>
 * </ul>
 * New wings come from {@code tools/cosmetics/import_wings.py} + {@code publish_wings.py}; they show up
 * here as testing.
 */
public final class OwnerView {

	private enum Tab { COSMETICS, PLAYERS, REPORTS, COLLECTIONS }

	private static Tab tab = Tab.COSMETICS;
	private static String filter = "all";

	@FunctionalInterface
	private interface Call {
		JsonObject run() throws StoreApi.StoreError;
	}

	private OwnerView() {
	}

	public static Node build(Runnable rebuild) {
		Label heading = new Label("Owner panel");
		heading.getStyleClass().add("page-title");
		Label sub = new Label("Release, test or hide cosmetics, set prices and limited-time windows, and hand out coins and items.");
		sub.getStyleClass().add("page-subtitle");
		HBox tabs = new HBox(6);
		for (Tab t : Tab.values()) {
			Button b = new Button(switch (t) {
				case COSMETICS -> "Cosmetics";
				case PLAYERS -> "Players";
				case REPORTS -> "Reports";
				case COLLECTIONS -> "Collections";
			});
			b.getStyleClass().add("segmented-button");
			if (t == tab) {
				b.getStyleClass().add("segmented-button-active");
			}
			b.setOnAction(e -> {
				tab = t;
				rebuild.run();
			});
			tabs.getChildren().add(b);
		}
		VBox body = new VBox(14);
		Label loading = new Label();
		loading.getStyleClass().add("page-subtitle");
		body.getChildren().add(Skeleton.list(5));
		run(StoreApi::adminCosmetics, data -> {
			body.getChildren().setAll(switch (tab) {
				case COSMETICS -> cosmetics(data, rebuild);
				case PLAYERS -> OwnerPlayers.build(data);
				case REPORTS -> OwnerReports.build();
				case COLLECTIONS -> collections(data, rebuild);
			});
		}, error -> {
			loading.setText(error);
			body.getChildren().setAll(loading);
		});

		VBox root = new VBox(16, new VBox(2, heading, sub), tabs, body);
		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox.setVgrow(wrapper, Priority.ALWAYS);
		return wrapper;
	}

	// ---- Cosmetics ----

	private static Node cosmetics(JsonObject data, Runnable rebuild) {
		List<String> collectionIds = new ArrayList<>();
		collectionIds.add("");
		data.getAsJsonArray("collections").forEach(c -> collectionIds.add(c.getAsJsonObject().get("id").getAsString()));

		HBox filters = new HBox(6);
		for (String f : List.of("all", "testing", "released", "hidden")) {
			long count = f.equals("all") ? data.getAsJsonArray("items").size()
					: items(data).stream().filter(i -> status(i).equals(f)).count();
			Button b = new Button(f.substring(0, 1).toUpperCase(Locale.ROOT) + f.substring(1) + "  " + count);
			b.getStyleClass().add("segmented-button");
			if (f.equals(filter)) {
				b.getStyleClass().add("segmented-button-active");
			}
			b.setOnAction(e -> {
				filter = f;
				rebuild.run();
			});
			filters.getChildren().add(b);
		}
		ComboBox<String> featured = new ComboBox<>();
		featured.getItems().addAll(collectionIds);
		featured.setValue(str(data, "featured", ""));
		featured.setPromptText("newest");
		Label featuredResult = hint("");
		Button saveFeatured = new Button("Feature");
		saveFeatured.getStyleClass().add("ghost-button");
		saveFeatured.setOnAction(e -> run(() -> {
			StoreApi.adminFeatured(featured.getValue());
			return new JsonObject();
		}, ok -> featuredResult.setText("Saved"), featuredResult::setText));
		HBox feature = new HBox(8, hint("Store features:"), featured, saveFeatured, featuredResult);
		feature.setAlignment(Pos.CENTER_LEFT);
		javafx.scene.layout.FlowPane top = new javafx.scene.layout.FlowPane(24, 8, filters, feature);
		top.setAlignment(Pos.CENTER_LEFT);

		VBox list = new VBox(10);
		List<JsonObject> items = items(data);
		items.sort(java.util.Comparator.comparingInt((JsonObject o) -> switch (status(o)) {
			case "testing" -> 0;
			case "released" -> 1;
			default -> 2;
		}).thenComparing(o -> str(o, "name", "")));
		for (JsonObject item : items) {
			if (filter.equals("all") || status(item).equals(filter)) {
				list.getChildren().add(itemRow(item, collectionIds));
			}
		}
		Label help = hint("Released = in the store (inside its window). Testing = only you hand it out - everyone sees it on whoever has it."
				+ " Hidden = out of the store; owners keep it. New wings: tools/cosmetics/import_wings.py, then publish_wings.py.");
		return new VBox(12, top, help, list);
	}

	private static Node itemRow(JsonObject item, List<String> collectionIds) {
		String id = item.get("id").getAsString();
		StackPane art = OwnerPreviews.thumb(item, 104, 76);

		VBox details = new VBox(8);
		Label name = new Label(str(item, "name", id));
		name.getStyleClass().add("settings-card-title");
		Label meta = hint(id + (item.has("collection") && !item.get("collection").isJsonNull() ? "  ·  " + item.get("collection").getAsString() : ""));
		VBox names = new VBox(2, name, meta);
		names.setMinWidth(200);
		names.setPrefWidth(220);

		ComboBox<String> status = new ComboBox<>();
		status.getItems().addAll("released", "testing", "hidden");
		status.setValue(status(item));
		status.setMinWidth(130);
		TextField price = new TextField(String.valueOf(item.get("price").getAsInt()));
		price.setMinWidth(90);
		price.setPrefWidth(90);
		price.setPromptText("Price");
		DatePicker until = new DatePicker();
		until.setPromptText("In store until");
		until.setMinWidth(150);
		until.setPrefWidth(150);
		if (item.has("availableUntil") && !item.get("availableUntil").isJsonNull()) {
			// Stored as the start of the day after (UTC): show the last day it's in the store.
			until.setValue(Instant.ofEpochMilli(item.get("availableUntil").getAsLong() - 1).atZone(ZoneId.of("UTC")).toLocalDate());
		}
		CheckBox noEnd = new CheckBox("No end");
		noEnd.setSelected(until.getValue() == null);
		until.valueProperty().addListener((obs, o, n) -> noEnd.setSelected(n == null));
		ComboBox<String> collection = new ComboBox<>();
		collection.getItems().addAll(collectionIds);
		collection.setValue(str(item, "collection", ""));
		collection.setMinWidth(150);
		collection.setPrefWidth(150);
		Label result = hint("");
		Button save = new Button("Save");
		save.getStyleClass().add("primary-button");
		save.setMinWidth(Region.USE_PREF_SIZE);
		save.setOnAction(e -> {
			JsonObject change = new JsonObject();
			change.addProperty("id", id);
			change.addProperty("status", status.getValue());
			try {
				change.addProperty("price", Integer.parseInt(price.getText().trim()));
			} catch (NumberFormatException ex) {
				result.setText("Price: a whole number");
				return;
			}
			change.addProperty("collection", collection.getValue() == null ? "" : collection.getValue());
			LocalDate day = until.getValue();
			boolean clear = noEnd.isSelected() || day == null;
			if (!clear) {
				change.addProperty("availableUntil", day.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli());
			}
			run(() -> StoreApi.adminUpdateCosmetic(change, clear), saved -> result.setText("Saved - " + saved.get("status").getAsString()),
					result::setText);
		});
		noEnd.setMinWidth(Region.USE_PREF_SIZE);
		javafx.scene.layout.FlowPane controls = new javafx.scene.layout.FlowPane(10, 8, labeled("Status", status), labeled("Price", price),
				labeled("Last day in store", new HBox(8, until, noEnd)), labeled("Collection", collection), new VBox(3, hint(" "), save), result);
		controls.setAlignment(Pos.BOTTOM_LEFT);
		HBox.setHgrow(details, Priority.ALWAYS);
		details.getChildren().addAll(names, controls);
		HBox row = new HBox(14, art, details);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("settings-card");
		return row;
	}

	// ---- Collections ----

	private static Node collections(JsonObject data, Runnable rebuild) {
		VBox list = new VBox(10);
		for (JsonElement element : data.getAsJsonArray("collections")) {
			list.getChildren().add(collectionCard(element.getAsJsonObject(), rebuild));
		}
		JsonObject blank = new JsonObject();
		list.getChildren().add(collectionCard(blank, rebuild));
		return list;
	}

	private static Node collectionCard(JsonObject c, Runnable rebuild) {
		boolean isNew = !c.has("id");
		TextField id = new TextField(str(c, "id", ""));
		id.setPromptText("id, e.g. winter");
		id.setEditable(isNew);
		id.setPrefWidth(140);
		TextField name = new TextField(str(c, "name", ""));
		name.setPromptText("Name, e.g. Winterfrost");
		TextField tag = new TextField(str(c, "tag", ""));
		tag.setPromptText("Tag, e.g. CHRISTMAS COLLECTION");
		tag.setPrefWidth(220);
		TextArea description = new TextArea(str(c, "description", ""));
		description.setPromptText("Description shown on the store banner");
		description.setPrefRowCount(2);
		description.setWrapText(true);
		Label result = hint("");
		Button save = new Button(isNew ? "Create collection" : "Save");
		save.getStyleClass().add("primary-button");
		save.setOnAction(e -> {
			JsonObject body = new JsonObject();
			body.addProperty("id", id.getText().trim().toLowerCase(Locale.ROOT));
			body.addProperty("name", name.getText().trim());
			body.addProperty("tag", tag.getText().trim());
			body.addProperty("description", description.getText().trim());
			run(() -> StoreApi.adminCollection(body), ok -> {
				if (isNew) {
					rebuild.run();
				} else {
					result.setText("Saved");
				}
			}, result::setText);
		});
		HBox.setHgrow(name, Priority.ALWAYS);
		VBox card = new VBox(8, title(isNew ? "New collection" : str(c, "name", "")), new HBox(8, id, name, tag), description,
				new HBox(8, save, result));
		card.getStyleClass().add("settings-card");
		return card;
	}

	// ---- Helpers ----

	private static List<JsonObject> items(JsonObject data) {
		List<JsonObject> out = new ArrayList<>();
		data.getAsJsonArray("items").forEach(e -> out.add(e.getAsJsonObject()));
		return out;
	}

	private static String status(JsonObject item) {
		return str(item, "status", "testing");
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	private static Label title(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("settings-card-title");
		return label;
	}

	private static Label hint(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("settings-row-hint");
		label.setWrapText(true);
		return label;
	}

	private static Node labeled(String text, Node control) {
		return new VBox(3, hint(text), control);
	}

	private static void run(Call call, java.util.function.Consumer<JsonObject> onOk, java.util.function.Consumer<String> onError) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return (Object) call.run();
			} catch (StoreApi.StoreError e) {
				return e;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result instanceof StoreApi.StoreError e) {
				onError.accept(e.getMessage());
			} else {
				onOk.accept((JsonObject) result);
			}
		}));
	}
}
