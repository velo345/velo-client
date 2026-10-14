package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.data.WingsCatalog;
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

	private enum Tab { COSMETICS, PLAYERS, COLLECTIONS }

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
		Label loading = new Label("Loading...");
		loading.getStyleClass().add("page-subtitle");
		body.getChildren().add(loading);
		run(StoreApi::adminCosmetics, data -> {
			body.getChildren().setAll(switch (tab) {
				case COSMETICS -> cosmetics(data, rebuild);
				case PLAYERS -> players(data);
				case COLLECTIONS -> collections(data, rebuild);
			});
		}, error -> loading.setText(error));

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
		boolean wings = id.startsWith("wings_");
		ImageView thumb = new ImageView();
		thumb.setFitWidth(96);
		thumb.setFitHeight(72);
		thumb.setPreserveRatio(true);
		String previewSha = item.has("files") && item.getAsJsonObject("files").has("preview")
				? item.getAsJsonObject("files").get("preview").getAsString() : null;
		if (wings && previewSha != null) {
			// Straight from the server's files (testing pairs aren't in your own catalog), loaded off the UI thread.
			CompletableFuture.supplyAsync(() -> new Image(WingsCatalog.openFile(previewSha)), Executors.newVirtualThreadPerTaskExecutor())
					.thenAccept(image -> Platform.runLater(() -> thumb.setImage(image)));
		}
		StackPane art = new StackPane(thumb);
		art.setMinSize(104, 76);
		String accent = str(item, "accent", "#ff4d4d");
		art.setStyle("-fx-background-radius: 10; -fx-background-color: radial-gradient(center 50% 60%, radius 70%, " + accent
				+ "66 0%, #16161d00 100%), #17171d;");
		if (id.startsWith("cape_")) {
			// The cape's first frame (just the outside of the cape), read off the UI thread.
			String gifSha = item.has("files") && item.getAsJsonObject("files").has("gif") ? item.getAsJsonObject("files").get("gif").getAsString() : null;
			thumb.setFitWidth(52);
			thumb.setFitHeight(84);
			if (gifSha != null) {
				CompletableFuture.supplyAsync(() -> firstCapeFrame(gifSha), Executors.newVirtualThreadPerTaskExecutor())
						.thenAccept(image -> Platform.runLater(() -> {
							if (image != null) {
								thumb.setImage(image);
							}
						}));
			}
		} else if (id.startsWith("aura_")) {
			// The aura itself, drawn once into a picture (not a live animation per row).
			net.veloclient.velo.client.cosmetics.aura.AuraEffects.Kind kind = net.veloclient.velo.client.cosmetics.aura.AuraEffects.kind(id.substring(5));
			if (kind != null) {
				Platform.runLater(() -> thumb.setImage(AuraFx.snapshot(kind, 104, 76)));
			}
		}

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

	/** Frame 0 of a cape GIF, cropped to the outside of the cape (10 x 16 cape pixels of the 64 x 32 layout). */
	private static Image firstCapeFrame(String sha) {
		try (java.io.InputStream in = WingsCatalog.openFile(sha); javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(in)) {
			var reader = javax.imageio.ImageIO.getImageReadersByFormatName("gif").next();
			reader.setInput(iis, true);
			java.awt.image.BufferedImage frame = reader.read(0);
			reader.dispose();
			int scale = Math.max(1, frame.getWidth() / 64);
			java.awt.image.BufferedImage face = frame.getSubimage(scale, scale, 10 * scale, 16 * scale);
			return javafx.embed.swing.SwingFXUtils.toFXImage(face, null);
		} catch (Exception e) {
			return null;
		}
	}

	// ---- Players ----

	private static Node players(JsonObject data) {
		TextField targets = new TextField();
		targets.setPromptText("Player names or UUIDs - several, comma separated, for giveaways");
		HBox.setHgrow(targets, Priority.ALWAYS);
		TextArea output = new TextArea();
		output.setEditable(false);
		output.setWrapText(true);
		output.setPrefRowCount(12);
		Button lookup = new Button("Look up");
		lookup.getStyleClass().add("ghost-button");
		lookup.setOnAction(e -> each(targets, StoreApi::adminLookup, output));

		TextField amount = new TextField();
		amount.setPromptText("Coins, e.g. 500 or -200");
		amount.setPrefWidth(170);
		TextField note = new TextField();
		note.setPromptText("Note (e.g. giveaway)");
		HBox.setHgrow(note, Priority.ALWAYS);
		Button coins = new Button("Apply coins");
		coins.getStyleClass().add("primary-button");
		coins.setOnAction(e -> {
			int value;
			try {
				value = Integer.parseInt(amount.getText().trim().replace("+", ""));
			} catch (NumberFormatException ex) {
				output.setText("Enter a whole number, e.g. 500 or -200");
				return;
			}
			each(targets, target -> StoreApi.adminCoins(target, value, note.getText().trim()), output);
		});

		ComboBox<String> item = new ComboBox<>();
		for (JsonObject i : items(data)) {
			item.getItems().add(i.get("id").getAsString());
		}
		item.setPromptText("Cosmetic");
		item.setPrefWidth(240);
		Button give = new Button("Give");
		give.getStyleClass().add("primary-button");
		give.setOnAction(e -> each(targets, target -> StoreApi.adminItem(target, item.getValue(), true), output));
		Button take = new Button("Take away");
		take.getStyleClass().add("danger-ghost-button");
		take.setOnAction(e -> each(targets, target -> StoreApi.adminItem(target, item.getValue(), false), output));

		VBox card = new VBox(10, title("Players"), new HBox(8, targets, lookup), new HBox(8, amount, note, coins),
				new HBox(8, item, give, take), output);
		card.getStyleClass().add("settings-card");
		return card;
	}

	@FunctionalInterface
	private interface PerTarget {
		JsonObject run(String target) throws StoreApi.StoreError;
	}

	/** Runs an action for every name in the field, one after another, and lists what happened. */
	private static void each(TextField field, PerTarget action, TextArea output) {
		List<String> targets = new ArrayList<>();
		for (String part : field.getText().split("[,\\s]+")) {
			if (!part.isBlank()) {
				targets.add(part.trim());
			}
		}
		if (targets.isEmpty()) {
			output.setText("Enter at least one player");
			return;
		}
		output.setText("Working...");
		CompletableFuture.runAsync(() -> {
			StringBuilder text = new StringBuilder();
			for (String target : targets) {
				try {
					text.append(describe(action.run(target))).append("\n\n");
				} catch (StoreApi.StoreError e) {
					text.append(target).append(": ").append(e.getMessage()).append("\n\n");
				}
			}
			// If you gave yourself something, the rest of the launcher sees it right away.
			try {
				StoreApi.wallet();
				net.veloclient.launcher.data.StorePurchase.restoreMissing();
				WingsCatalog.refresh();
			} catch (StoreApi.StoreError ignored) {
				// Shown on the next visit.
			}
			Platform.runLater(() -> output.setText(text.toString().strip()));
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	private static String describe(JsonObject view) {
		StringBuilder text = new StringBuilder(view.get("username").getAsString() + ": " + String.format(Locale.ROOT, "%,d", view.get("balance").getAsInt())
				+ " coins");
		JsonArray owned = view.getAsJsonArray("owned");
		text.append("\n  owns: ").append(owned.isEmpty() ? "nothing" : owned.toString().replaceAll("[\\[\\]\"]", "").replace(",", ", "));
		JsonArray ledger = view.getAsJsonArray("ledger");
		for (int i = 0; i < Math.min(5, ledger.size()); i++) {
			JsonObject entry = ledger.get(i).getAsJsonObject();
			text.append("\n  ").append(entry.get("delta").getAsInt() >= 0 ? "+" : "").append(entry.get("delta").getAsInt())
					.append("  ").append(entry.get("reason").getAsString());
			if (entry.has("ref") && !entry.get("ref").isJsonNull()) {
				text.append("  (").append(entry.get("ref").getAsString()).append(")");
			}
		}
		return text.toString();
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
