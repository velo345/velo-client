package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import net.veloclient.launcher.social.StoreApi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The owner's panel (only shown to owners - the server checks every call anyway). A side nav like Settings:
 * <ul>
 * <li><b>Cosmetics</b> - every wing pair and cape with its status: <i>released</i> (in the store),
 * <i>testing</i> (only given out by you - whoever has it is seen with it by everyone) or <i>hidden</i>
 * (out of the store again, still worn by whoever owns it); price, last day in the store for limited
 * drops, collection, and which collection the store features.</li>
 * <li><b>Bundles</b> - several items for one price, switched on or off, optionally until a day. While there
 * are none, the store offers each collection as a bundle at 20% off.</li>
 * <li><b>Collections</b> - name, tag and description of each drop, and new ones.</li>
 * <li><b>Players</b> - look players up, give or take coins and cosmetics; several names at once
 * (comma separated) for giveaways.</li>
 * <li><b>Reports</b> - player reports to resolve.</li>
 * </ul>
 * New wings come from {@code tools/cosmetics/import_wings.py} + {@code publish_wings.py}; they show up
 * here as testing.
 */
public final class OwnerView {

	private enum Tab { COSMETICS, BUNDLES, COLLECTIONS, PLAYERS, PARTNERS, REPORTS }

	private static Tab tab = Tab.COSMETICS;
	private static String filter = "all";
	private static String query = "";
	/** The bundle being edited (a copy), or null for none; an object without "id" is a new one. */
	private static JsonObject editing;

	@FunctionalInterface
	private interface Call {
		JsonObject run() throws StoreApi.StoreError;
	}

	private OwnerView() {
	}

	/** Opens on the Bundles tab next time (with the new-bundle editor open when {@code newBundle}). */
	public static void showBundlesTab(boolean newBundle) {
		tab = Tab.BUNDLES;
		editing = null;
		if (newBundle) {
			editing = new JsonObject();
			editing.addProperty("active", true);
			editing.add("items", new JsonArray());
		}
	}

	public static Node build(Runnable rebuild) {
		Label overline = new Label("OWNER");
		overline.getStyleClass().add("t-overline");
		Label heading = new Label("Owner panel");
		heading.getStyleClass().add("t-title");
		Label sub = new Label("Release cosmetics, build bundles, run drops and look after players.");
		sub.getStyleClass().add("t-muted");
		VBox head = new VBox(4, overline, heading, sub);

		HBox kpis = new HBox(12);
		VBox nav = new VBox(2);
		nav.setMinWidth(200);
		nav.setPrefWidth(210);
		VBox body = new VBox(14);
		HBox.setHgrow(body, Priority.ALWAYS);
		body.getChildren().add(Skeleton.list(5));
		fillNav(nav, null, rebuild);

		run(StoreApi::adminCosmetics, data -> {
			fillNav(nav, data, rebuild);
			List<JsonObject> items = items(data);
			kpis.getChildren().setAll(
					kpi("Released", items.stream().filter(i -> status(i).equals("released")).count(), "#3DDC97"),
					kpi("Testing", items.stream().filter(i -> status(i).equals("testing")).count(), "#5AA9FF"),
					kpi("Hidden", items.stream().filter(i -> status(i).equals("hidden")).count(), "#A9A9B3"),
					kpi("Bundles live", bundles(data).stream().filter(b -> b.has("active") && b.get("active").getAsBoolean()).count(), "#F7C04A"));
			body.getChildren().setAll(switch (tab) {
				case COSMETICS -> cosmetics(data, rebuild);
				case BUNDLES -> bundlesTab(data, rebuild);
				case COLLECTIONS -> collections(data, rebuild);
				case PLAYERS -> OwnerPlayers.build(data);
				case PARTNERS -> OwnerPartners.build();
				case REPORTS -> OwnerReports.build();
			});
		}, error -> {
			Label failed = new Label(error);
			failed.getStyleClass().add("t-muted");
			failed.setWrapText(true);
			body.getChildren().setAll(failed);
		});

		HBox columns = new HBox(28, nav, body);
		VBox root = new VBox(20, head, kpis, columns);
		root.setPadding(new Insets(4, 24, 24, 4));
		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		wrapper.getStyleClass().add("page");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox.setVgrow(wrapper, Priority.ALWAYS);
		return wrapper;
	}

	private static void fillNav(VBox nav, JsonObject data, Runnable rebuild) {
		nav.getChildren().clear();
		for (Tab t : Tab.values()) {
			String[] meta = switch (t) {
				case COSMETICS -> new String[] {"Cosmetics", "wings"};
				case BUNDLES -> new String[] {"Bundles", "box"};
				case COLLECTIONS -> new String[] {"Collections", "grid"};
				case PLAYERS -> new String[] {"Players", "user"};
				case PARTNERS -> new String[] {"Partner servers", "globe"};
				case REPORTS -> new String[] {"Reports", "shield"};
			};
			Label count = new Label(data == null ? "" : switch (t) {
				case COSMETICS -> String.valueOf(data.getAsJsonArray("items").size());
				case BUNDLES -> String.valueOf(bundles(data).size());
				case COLLECTIONS -> String.valueOf(data.getAsJsonArray("collections").size());
				default -> "";
			});
			count.getStyleClass().add("snav-count");
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			HBox graphic = new HBox(10, Icons.icon(meta[1], 16, Color.web(t == tab ? "#FFFFFF" : "#A9A9B3")), new Label(meta[0]), spacer, count);
			graphic.setAlignment(Pos.CENTER_LEFT);
			graphic.getChildren().get(1).getStyleClass().add(t == tab ? "t-strong" : "t-muted");
			graphic.setMaxWidth(Double.MAX_VALUE);
			Button entry = new Button(null, graphic);
			entry.getStyleClass().setAll("button", "snav", "no-motion");
			if (t == tab) {
				entry.getStyleClass().add("snav-active");
			}
			graphic.prefWidthProperty().bind(entry.widthProperty().subtract(26));
			entry.setMaxWidth(Double.MAX_VALUE);
			entry.setOnAction(e -> {
				tab = t;
				rebuild.run();
			});
			nav.getChildren().add(entry);
		}
	}

	private static Node kpi(String label, long value, String color) {
		Label number = new Label(String.valueOf(value));
		number.getStyleClass().add("t-title-sm");
		Region dot = new Region();
		dot.setMinSize(8, 8);
		dot.setMaxSize(8, 8);
		dot.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 4;");
		Label name = new Label(label);
		name.getStyleClass().add("t-muted");
		HBox top = new HBox(8, dot, name);
		top.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(6, top, number);
		card.getStyleClass().add("card");
		card.setPadding(new Insets(14, 18, 14, 18));
		card.setMinWidth(150);
		HBox.setHgrow(card, Priority.ALWAYS);
		card.setMaxWidth(Double.MAX_VALUE);
		return card;
	}

	// ---- Cosmetics ----

	private static Node cosmetics(JsonObject data, Runnable rebuild) {
		List<String> collectionIds = collectionIds(data);

		HBox filters = new HBox(6);
		filters.setAlignment(Pos.CENTER_LEFT);
		for (String f : List.of("all", "testing", "released", "hidden")) {
			long count = f.equals("all") ? data.getAsJsonArray("items").size()
					: items(data).stream().filter(i -> status(i).equals(f)).count();
			Button b = new Button(f.substring(0, 1).toUpperCase(Locale.ROOT) + f.substring(1) + "  " + count);
			b.getStyleClass().setAll("button", "fchip", "no-motion");
			if (f.equals(filter)) {
				b.getStyleClass().add("fchip-active");
			}
			b.setOnAction(e -> {
				filter = f;
				rebuild.run();
			});
			filters.getChildren().add(b);
		}
		TextField search = new TextField(query);
		search.setPromptText("Search name or id");
		search.setPrefWidth(220);

		ComboBox<String> featured = new ComboBox<>();
		featured.getItems().addAll(collectionIds);
		featured.setValue(str(data, "featured", ""));
		featured.setPromptText("Newest");
		Label featuredResult = hint("");
		Button saveFeatured = new Button("Feature");
		saveFeatured.getStyleClass().add("ghost-button");
		saveFeatured.setOnAction(e -> run(() -> {
			StoreApi.adminFeatured(featured.getValue());
			return new JsonObject();
		}, ok -> Toasts.show(saveFeatured, "Store now features " + (featured.getValue() == null || featured.getValue().isBlank()
				? "the newest collection" : featured.getValue())), featuredResult::setText));
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox feature = new HBox(8, fieldLabel("FEATURED"), featured, saveFeatured, featuredResult);
		feature.setAlignment(Pos.CENTER_LEFT);
		HBox toolbar = new HBox(12, search, filters, spacer, feature);
		toolbar.setAlignment(Pos.CENTER_LEFT);

		VBox list = new VBox(8);
		List<JsonObject> items = items(data);
		items.sort(java.util.Comparator.comparingInt((JsonObject o) -> switch (status(o)) {
			case "testing" -> 0;
			case "released" -> 1;
			default -> 2;
		}).thenComparing(o -> str(o, "name", "")));
		List<Node> rows = new ArrayList<>();
		List<String> keys = new ArrayList<>();
		for (JsonObject item : items) {
			if (filter.equals("all") || status(item).equals(filter)) {
				rows.add(itemRow(item, collectionIds));
				keys.add((str(item, "name", "") + " " + item.get("id").getAsString()).toLowerCase(Locale.ROOT));
			}
		}
		list.getChildren().setAll(rows);
		Runnable applySearch = () -> {
			String q = query.strip().toLowerCase(Locale.ROOT);
			for (int i = 0; i < rows.size(); i++) {
				boolean show = q.isEmpty() || keys.get(i).contains(q);
				rows.get(i).setVisible(show);
				rows.get(i).setManaged(show);
			}
		};
		search.textProperty().addListener((o, a, n) -> {
			query = n;
			applySearch.run();
		});
		applySearch.run();
		Label help = hint("Released = in the store (inside its window). Testing = only you hand it out, and everyone sees it on whoever has it."
				+ " Hidden = out of the store, but owners keep it.");
		return new VBox(14, toolbar, help, list);
	}

	private static Node itemRow(JsonObject item, List<String> collectionIds) {
		String id = item.get("id").getAsString();
		StackPane art = OwnerPreviews.thumb(item, 96, 72);

		Label name = new Label(str(item, "name", id));
		name.getStyleClass().add("t-strong");
		Label idLabel = new Label(id);
		idLabel.getStyleClass().add("t-mono");
		HBox titleRow = new HBox(8, name, statusPill(status(item)));
		titleRow.setAlignment(Pos.CENTER_LEFT);
		VBox names = new VBox(3, titleRow, idLabel);
		names.setMinWidth(210);
		names.setPrefWidth(230);

		ComboBox<String> status = new ComboBox<>();
		status.getItems().addAll("released", "testing", "hidden");
		status.setValue(status(item));
		status.setPrefWidth(120);
		TextField price = new TextField(String.valueOf(item.get("price").getAsInt()));
		price.setPrefWidth(84);
		price.setPromptText("Price");
		DatePicker until = new DatePicker();
		until.setPromptText("No end");
		until.setPrefWidth(140);
		if (item.has("availableUntil") && !item.get("availableUntil").isJsonNull()) {
			// Stored as the start of the day after (UTC): show the last day it's in the store.
			until.setValue(Instant.ofEpochMilli(item.get("availableUntil").getAsLong() - 1).atZone(ZoneId.of("UTC")).toLocalDate());
		}
		Button clearUntil = new Button(null, Icons.icon("close", 12, Color.web("#A9A9B3")));
		clearUntil.getStyleClass().add("icon-button-flat");
		clearUntil.setOnAction(e -> until.setValue(null));
		clearUntil.visibleProperty().bind(until.valueProperty().isNotNull());
		ComboBox<String> collection = new ComboBox<>();
		collection.getItems().addAll(collectionIds);
		collection.setValue(str(item, "collection", ""));
		collection.setPrefWidth(140);
		collection.setButtonCell(new javafx.scene.control.ListCell<>() {
			@Override
			protected void updateItem(String value, boolean empty) {
				super.updateItem(value, empty);
				setText(empty || value == null || value.isEmpty() ? "None" : value);
			}
		});
		Label result = hint("");
		Button save = new Button("Save");
		save.getStyleClass().addAll("primary-button", "button-sm");
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
			boolean clear = day == null;
			if (!clear) {
				change.addProperty("availableUntil", day.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli());
			}
			run(() -> StoreApi.adminUpdateCosmetic(change, clear), saved -> {
				result.setText("");
				titleRow.getChildren().set(1, statusPill(saved.get("status").getAsString()));
				Toasts.show(save, "Saved " + str(item, "name", id));
			}, result::setText);
		});
		HBox untilBox = new HBox(2, until, clearUntil);
		untilBox.setAlignment(Pos.CENTER_LEFT);
		FlowPane controls = new FlowPane(10, 8, labeled("STATUS", status), labeled("PRICE", price), labeled("LAST DAY", untilBox),
				labeled("COLLECTION", collection), new VBox(5, fieldLabel(" "), save), result);
		controls.setAlignment(Pos.BOTTOM_LEFT);
		HBox.setHgrow(controls, Priority.ALWAYS);
		HBox row = new HBox(16, art, names, controls);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("card");
		row.setPadding(new Insets(12, 16, 12, 12));
		return row;
	}

	private static Label statusPill(String status) {
		Label pill = new Label(status.substring(0, 1).toUpperCase(Locale.ROOT) + status.substring(1));
		pill.getStyleClass().addAll("badge", switch (status) {
			case "released" -> "badge-green";
			case "testing" -> "badge-blue";
			default -> "badge-grey";
		});
		return pill;
	}

	// ---- Bundles ----

	private static Node bundlesTab(JsonObject data, Runnable rebuild) {
		List<JsonObject> bundles = bundles(data);
		Button create = new Button("New bundle", Icons.icon("plus", 14, Color.WHITE));
		create.getStyleClass().add("primary-button");
		create.setOnAction(e -> {
			editing = new JsonObject();
			editing.addProperty("active", true);
			editing.add("items", new JsonArray());
			rebuild.run();
		});
		Label intro = hint(bundles.isEmpty()
				? "No bundles of your own yet, so the store offers every collection as a bundle at 20% off. Your first bundle replaces that."
				: "Only items that are released and in their window are sold. A player who owns part of a bundle pays the bundle's rate for the rest.");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox top = new HBox(12, intro, spacer, create);
		top.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(intro, Priority.SOMETIMES);

		VBox content = new VBox(14, top);
		if (editing != null) {
			content.getChildren().add(bundleEditor(data, editing, rebuild));
		}
		FlowPane grid = new FlowPane(12, 12);
		for (JsonObject bundle : bundles) {
			if (editing != null && str(editing, "id", "").equals(str(bundle, "id", null))) {
				continue;
			}
			grid.getChildren().add(bundleCard(data, bundle, rebuild));
		}
		if (!grid.getChildren().isEmpty()) {
			content.getChildren().add(grid);
		}
		return content;
	}

	private static Node bundleCard(JsonObject data, JsonObject bundle, Runnable rebuild) {
		HBox thumbs = new HBox(-18);
		int separate = 0;
		for (JsonElement e : bundle.getAsJsonArray("items")) {
			JsonObject item = item(data, e.getAsString());
			if (item != null) {
				separate += item.get("price").getAsInt();
				if (thumbs.getChildren().size() < 4) {
					thumbs.getChildren().add(OwnerPreviews.thumb(item, 64, 52));
				}
			}
		}
		int price = bundle.get("price").getAsInt();
		boolean active = bundle.has("active") && bundle.get("active").getAsBoolean();
		Long until = bundle.has("availableUntil") && !bundle.get("availableUntil").isJsonNull() ? bundle.get("availableUntil").getAsLong() : null;
		boolean expired = until != null && until <= System.currentTimeMillis();

		Label name = new Label(str(bundle, "name", bundle.get("id").getAsString()));
		name.getStyleClass().add("t-heading");
		Label state = new Label(expired ? "Ended" : active ? "Live" : "Off");
		state.getStyleClass().addAll("badge", expired ? "badge-grey" : active ? "badge-green" : "badge-grey");
		HBox titleRow = new HBox(8, name, state);
		titleRow.setAlignment(Pos.CENTER_LEFT);
		Label meta = hint(bundle.getAsJsonArray("items").size() + " items" + (until != null ? "  ·  until " + lastDay(until) : ""));
		HBox priceRow = new HBox(8, StoreTiles.price(price, separate > price ? separate : null, 15));
		priceRow.setAlignment(Pos.CENTER_LEFT);
		if (separate > price) {
			Label save = new Label("save " + Math.round(100.0 * (separate - price) / separate) + "%");
			save.getStyleClass().addAll("badge", "badge-green");
			priceRow.getChildren().add(save);
		}
		Button edit = new Button("Edit", Icons.icon("edit", 13, Color.WHITE));
		edit.getStyleClass().addAll("ghost-button", "button-sm");
		edit.setOnAction(e -> {
			editing = bundle.deepCopy();
			rebuild.run();
		});
		Button toggle = new Button(active ? "Turn off" : "Turn on");
		toggle.getStyleClass().addAll("ghost-button", "button-sm");
		toggle.setOnAction(e -> {
			JsonObject change = bundle.deepCopy();
			change.addProperty("active", !active);
			run(() -> StoreApi.adminBundle(change), ok -> rebuild.run(), msg -> Toasts.show(toggle, msg));
		});
		HBox actions = new HBox(8, edit, toggle);
		VBox card = new VBox(10, thumbs, titleRow, meta, priceRow, actions);
		card.getStyleClass().add("card");
		card.setPadding(new Insets(16));
		card.setPrefWidth(300);
		return card;
	}

	private static Node bundleEditor(JsonObject data, JsonObject bundle, Runnable rebuild) {
		boolean isNew = !bundle.has("id");
		Label title = new Label(isNew ? "New bundle" : "Edit " + str(bundle, "name", ""));
		title.getStyleClass().add("t-heading");

		TextField id = new TextField(str(bundle, "id", ""));
		id.setPromptText("e.g. starter_pack");
		id.setEditable(isNew);
		id.setPrefWidth(170);
		TextField name = new TextField(str(bundle, "name", ""));
		name.setPromptText("e.g. Starter Pack");
		HBox.setHgrow(name, Priority.ALWAYS);
		TextField price = new TextField(bundle.has("price") ? String.valueOf(bundle.get("price").getAsInt()) : "");
		price.setPromptText("Coins");
		price.setPrefWidth(110);
		TextArea description = new TextArea(str(bundle, "description", ""));
		description.setPromptText("One line for the store card");
		description.setPrefRowCount(2);
		description.setWrapText(true);
		CheckBox active = new CheckBox("Live in the store");
		active.setSelected(!bundle.has("active") || bundle.get("active").getAsBoolean());
		DatePicker until = new DatePicker();
		until.setPromptText("No end");
		until.setPrefWidth(150);
		if (bundle.has("availableUntil") && !bundle.get("availableUntil").isJsonNull()) {
			until.setValue(Instant.ofEpochMilli(bundle.get("availableUntil").getAsLong() - 1).atZone(ZoneId.of("UTC")).toLocalDate());
		}

		Set<String> chosen = new LinkedHashSet<>();
		if (bundle.has("items")) {
			bundle.getAsJsonArray("items").forEach(e -> chosen.add(e.getAsString()));
		}
		Label summary = new Label();
		summary.getStyleClass().add("t-muted");
		Button suggest = new Button("Use 20% off");
		suggest.getStyleClass().addAll("ghost-button", "button-sm");
		Runnable updateSummary = () -> {
			int separate = chosen.stream().map(i -> item(data, i)).filter(java.util.Objects::nonNull).mapToInt(i -> i.get("price").getAsInt()).sum();
			String text = chosen.size() + (chosen.size() == 1 ? " item" : " items") + "  ·  " + StoreTiles.format(separate) + " separately";
			try {
				int p = Integer.parseInt(price.getText().trim());
				if (separate > 0) {
					long off = Math.round(100.0 * (separate - p) / separate);
					text += "  ·  " + (off > 0 ? "save " + off + "%" : off < 0 ? "costs more than separately" : "no saving");
				}
			} catch (NumberFormatException ignored) {
				// No price yet.
			}
			summary.setText(text);
			suggest.setUserData(separate);
		};
		suggest.setOnAction(e -> {
			int separate = suggest.getUserData() instanceof Integer s ? s : 0;
			if (separate > 0) {
				price.setText(String.valueOf(Math.max(10, Math.round(separate * 0.8 / 10.0) * 10)));
			}
		});
		price.textProperty().addListener((o, a, n) -> updateSummary.run());

		FlowPane picker = new FlowPane(10, 10);
		List<JsonObject> all = items(data);
		all.sort(java.util.Comparator.comparing((JsonObject o) -> !status(o).equals("released")).thenComparing(o -> str(o, "name", "")));
		for (JsonObject item : all) {
			String itemId = item.get("id").getAsString();
			Label itemName = new Label(str(item, "name", itemId));
			itemName.getStyleClass().add("t-strong");
			itemName.setMaxWidth(120);
			Label itemMeta = new Label(StoreTiles.format(item.get("price").getAsInt()) + (status(item).equals("released") ? "" : "  ·  " + status(item)));
			itemMeta.getStyleClass().add("t-muted");
			StackPane check = new StackPane(Icons.icon("check", 12, Color.WHITE, 2.4));
			check.setMinSize(20, 20);
			check.setMaxSize(20, 20);
			check.setStyle("-fx-background-color: -velo-accent; -fx-background-radius: 10;");
			StackPane thumb = new StackPane(OwnerPreviews.thumb(item, 120, 74), check);
			StackPane.setAlignment(check, Pos.TOP_RIGHT);
			StackPane.setMargin(check, new Insets(6));
			VBox tile = new VBox(6, thumb, itemName, itemMeta);
			tile.getStyleClass().addAll("card-2", "card-hover");
			tile.setPadding(new Insets(8));
			tile.setPrefWidth(136);
			Runnable paint = () -> {
				boolean on = chosen.contains(itemId);
				check.setVisible(on);
				tile.getStyleClass().remove("card-selected");
				if (on) {
					tile.getStyleClass().add("card-selected");
				}
			};
			tile.setOnMouseClicked(e -> {
				if (!chosen.remove(itemId)) {
					chosen.add(itemId);
				}
				paint.run();
				updateSummary.run();
			});
			paint.run();
			picker.getChildren().add(tile);
		}
		updateSummary.run();

		Label result = hint("");
		Button save = new Button(isNew ? "Create bundle" : "Save bundle");
		save.getStyleClass().add("primary-button");
		save.setOnAction(e -> {
			JsonObject body = new JsonObject();
			body.addProperty("id", id.getText().trim().toLowerCase(Locale.ROOT));
			body.addProperty("name", name.getText().trim());
			body.addProperty("description", description.getText().trim());
			try {
				body.addProperty("price", Integer.parseInt(price.getText().trim()));
			} catch (NumberFormatException ex) {
				result.setText("Price: a whole number");
				return;
			}
			JsonArray items = new JsonArray();
			chosen.forEach(items::add);
			body.add("items", items);
			body.addProperty("active", active.isSelected());
			if (until.getValue() != null) {
				body.addProperty("availableUntil", until.getValue().plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli());
			}
			run(() -> StoreApi.adminBundle(body), ok -> {
				editing = null;
				rebuild.run();
			}, result::setText);
		});
		Button cancel = new Button("Cancel");
		cancel.getStyleClass().add("ghost-button");
		cancel.setOnAction(e -> {
			editing = null;
			rebuild.run();
		});
		HBox actions = new HBox(8, save, cancel);
		actions.setAlignment(Pos.CENTER_LEFT);
		if (!isNew) {
			Button delete = new Button("Delete");
			delete.getStyleClass().add("danger-button");
			delete.setOnAction(e -> run(() -> StoreApi.adminDeleteBundle(bundle.get("id").getAsString()), ok -> {
				editing = null;
				rebuild.run();
			}, result::setText));
			Region gap = new Region();
			HBox.setHgrow(gap, Priority.ALWAYS);
			actions.getChildren().addAll(gap, delete);
		}
		actions.getChildren().add(2, result);

		HBox fields = new HBox(10, labeled("ID", id), labeled("NAME", name), labeled("PRICE", price));
		HBox.setHgrow(fields.getChildren().get(1), Priority.ALWAYS);
		HBox options = new HBox(16, labeled("LAST DAY", until), new VBox(5, fieldLabel(" "), active));
		options.setAlignment(Pos.BOTTOM_LEFT);
		HBox summaryRow = new HBox(10, summary, suggest);
		summaryRow.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(14, title, fields, labeled("DESCRIPTION", description), options, fieldLabel("ITEMS"), picker, summaryRow, actions);
		card.getStyleClass().add("card");
		card.setStyle("-fx-border-color: -velo-accent-line;");
		card.setPadding(new Insets(18));
		return card;
	}

	private static String lastDay(long until) {
		return Instant.ofEpochMilli(until - 1).atZone(ZoneId.of("UTC")).toLocalDate().toString();
	}

	// ---- Collections ----

	private static Node collections(JsonObject data, Runnable rebuild) {
		VBox list = new VBox(10);
		for (JsonElement element : data.getAsJsonArray("collections")) {
			JsonObject c = element.getAsJsonObject();
			long count = items(data).stream().filter(i -> str(i, "collection", "").equals(str(c, "id", ""))).count();
			list.getChildren().add(collectionCard(c, count, rebuild));
		}
		list.getChildren().add(collectionCard(new JsonObject(), 0, rebuild));
		return list;
	}

	private static Node collectionCard(JsonObject c, long itemCount, Runnable rebuild) {
		boolean isNew = !c.has("id");
		TextField id = new TextField(str(c, "id", ""));
		id.setPromptText("e.g. winter");
		id.setEditable(isNew);
		id.setPrefWidth(140);
		TextField name = new TextField(str(c, "name", ""));
		name.setPromptText("e.g. Winterfrost");
		TextField tag = new TextField(str(c, "tag", ""));
		tag.setPromptText("e.g. CHRISTMAS COLLECTION");
		tag.setPrefWidth(220);
		TextArea description = new TextArea(str(c, "description", ""));
		description.setPromptText("Shown on the store banner");
		description.setPrefRowCount(2);
		description.setWrapText(true);
		Label result = hint("");
		Button save = new Button(isNew ? "Create collection" : "Save");
		save.getStyleClass().addAll("primary-button", "button-sm");
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
					Toasts.show(save, "Saved " + name.getText().trim());
				}
			}, result::setText);
		});
		Label heading = new Label(isNew ? "New collection" : str(c, "name", ""));
		heading.getStyleClass().add("t-heading");
		HBox titleRow = new HBox(10, heading);
		titleRow.setAlignment(Pos.CENTER_LEFT);
		if (!isNew) {
			Label count = new Label(itemCount + (itemCount == 1 ? " item" : " items"));
			count.getStyleClass().addAll("badge", "badge-grey");
			titleRow.getChildren().add(count);
		}
		HBox fields = new HBox(10, labeled("ID", id), labeled("NAME", name), labeled("TAG", tag));
		HBox.setHgrow(fields.getChildren().get(1), Priority.ALWAYS);
		HBox actions = new HBox(8, save, result);
		actions.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(12, titleRow, fields, labeled("DESCRIPTION", description), actions);
		card.getStyleClass().add("card");
		if (isNew) {
			card.setStyle("-fx-border-style: segments(6, 5); -fx-border-color: -velo-hairline-2;");
		}
		card.setPadding(new Insets(16, 18, 16, 18));
		return card;
	}

	// ---- Helpers ----

	private static List<String> collectionIds(JsonObject data) {
		List<String> ids = new ArrayList<>();
		ids.add("");
		data.getAsJsonArray("collections").forEach(c -> ids.add(c.getAsJsonObject().get("id").getAsString()));
		return ids;
	}

	private static List<JsonObject> items(JsonObject data) {
		List<JsonObject> out = new ArrayList<>();
		data.getAsJsonArray("items").forEach(e -> out.add(e.getAsJsonObject()));
		return out;
	}

	private static JsonObject item(JsonObject data, String id) {
		for (JsonElement e : data.getAsJsonArray("items")) {
			if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
				return e.getAsJsonObject();
			}
		}
		return null;
	}

	private static List<JsonObject> bundles(JsonObject data) {
		List<JsonObject> out = new ArrayList<>();
		if (data != null && data.has("bundles") && data.get("bundles").isJsonArray()) {
			data.getAsJsonArray("bundles").forEach(e -> out.add(e.getAsJsonObject()));
		}
		return out;
	}

	private static String status(JsonObject item) {
		return str(item, "status", "testing");
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	private static Label hint(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("t-muted");
		label.setWrapText(true);
		return label;
	}

	private static Label fieldLabel(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("field-label");
		return label;
	}

	private static Node labeled(String text, Node control) {
		return new VBox(5, fieldLabel(text), control);
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
