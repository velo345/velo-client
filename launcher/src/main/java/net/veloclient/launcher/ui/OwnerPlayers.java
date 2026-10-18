package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.data.WingsCatalog;
import net.veloclient.launcher.social.StoreApi;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Owner panel, Players tab: type a name, see everything about that player - coins, Velo+ (and for how
 * long), every cosmetic they own with a preview - and manage it in place: coin buttons, Velo+ days,
 * a remove button on each cosmetic. A bulk giveaway (several names at once) stays below, collapsed.
 */
final class OwnerPlayers {

	private final JsonObject catalog;
	private final Map<String, JsonObject> itemsById = new HashMap<>();
	private final VBox result = new VBox(14);
	private final TextField nameField = new TextField();
	private final Label status = new Label();
	private String shownTarget;

	private OwnerPlayers(JsonObject catalog) {
		this.catalog = catalog;
		catalog.getAsJsonArray("items").forEach(e -> itemsById.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject()));
	}

	static Node build(JsonObject catalog) {
		return new OwnerPlayers(catalog).root();
	}

	private Node root() {
		nameField.setPromptText("Player name or UUID");
		nameField.setPrefWidth(320);
		HBox.setHgrow(nameField, Priority.ALWAYS);
		Button lookup = new Button("Look up");
		lookup.getStyleClass().add("primary-button");
		lookup.setOnAction(e -> lookup(nameField.getText().trim()));
		nameField.setOnAction(e -> lookup.fire());
		status.getStyleClass().add("settings-row-hint");
		status.setWrapText(true);
		HBox search = new HBox(8, nameField, lookup);
		search.setAlignment(Pos.CENTER_LEFT);
		VBox searchCard = new VBox(8, title("Find a player"), search, status);
		searchCard.getStyleClass().add("settings-card");

		TitledPane bulk = new TitledPane("Bulk giveaway (several players at once)", bulkCard());
		bulk.setExpanded(false);
		return new VBox(14, searchCard, result, bulk);
	}

	// ---- Lookup + actions ----

	@FunctionalInterface
	private interface Action {
		JsonObject run() throws StoreApi.StoreError;
	}

	private void lookup(String target) {
		if (target.isBlank()) {
			status.setText("Enter a player name or UUID");
			return;
		}
		perform(() -> StoreApi.adminLookup(target), target, false);
	}

	/** Runs an API call off the UI thread and redraws the player card with the answer. */
	private void perform(Action action, String target, boolean affectsLauncher) {
		status.setText("Working...");
		CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject view = action.run();
				if (affectsLauncher) {
					// If you changed your own account, the rest of the launcher sees it right away.
					try {
						StoreApi.wallet();
						net.veloclient.launcher.data.StorePurchase.restoreMissing();
						WingsCatalog.refresh();
					} catch (StoreApi.StoreError ignored) {
						// Shown on the next visit.
					}
				}
				return (Object) view;
			} catch (StoreApi.StoreError e) {
				return e;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(out -> Platform.runLater(() -> {
			if (out instanceof StoreApi.StoreError e) {
				status.setText(e.getMessage());
				return;
			}
			status.setText("");
			JsonObject view = (JsonObject) out;
			shownTarget = view.get("uuid").getAsString();
			result.getChildren().setAll(playerCard(view));
		}));
	}

	// ---- The player card ----

	private Node playerCard(JsonObject view) {
		String uuid = view.get("uuid").getAsString();
		String name = view.get("username").getAsString();
		int balance = view.get("balance").getAsInt();
		long plusUntil = view.has("plusUntil") ? view.get("plusUntil").getAsLong() : 0;
		boolean plus = plusUntil > System.currentTimeMillis();
		JsonArray owned = view.getAsJsonArray("owned");

		Label nameLabel = new Label(name);
		nameLabel.getStyleClass().add("profile-name");
		Label uuidLabel = new Label(uuid);
		uuidLabel.getStyleClass().add("profile-uuid");
		VBox who = new VBox(3, nameLabel, uuidLabel);
		who.setAlignment(Pos.CENTER_LEFT);
		HBox header = new HBox(14, Heads.head(uuid, name, 64), who);
		header.setAlignment(Pos.CENTER_LEFT);

		FlowPane tiles = new FlowPane(12, 12,
				stat("Coins", String.format(Locale.ROOT, "%,d", balance), "balance"),
				stat("Velo+", plus ? remaining(plusUntil) : "Not subscribed", plus ? "until " + date(plusUntil) : "no membership"),
				stat("Cosmetics", String.valueOf(owned.size()), owned.isEmpty() ? "owns nothing" : "owned"));

		VBox card = new VBox(14, header, tiles, coinsCard(uuid), plusCard(uuid, plus), cosmeticsCard(uuid, owned), activityCard(view.getAsJsonArray("ledger")));
		card.getStyleClass().add("settings-card");
		return card;
	}

	private Node stat(String label, String value, String sub) {
		Label v = new Label(value);
		v.getStyleClass().add("settings-card-title");
		v.setStyle("-fx-font-size: 20px;");
		VBox box = new VBox(2, hint(label), v, hint(sub));
		box.setMinWidth(190);
		box.setPadding(new javafx.geometry.Insets(10, 14, 10, 14));
		box.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 10;");
		return box;
	}

	private Node coinsCard(String uuid) {
		FlowPane quick = new FlowPane(6, 6);
		for (int amount : new int[] {-1000, -500, -100, 100, 500, 1000, 5000}) {
			Button b = new Button((amount > 0 ? "+" : "") + String.format(Locale.ROOT, "%,d", amount));
			b.getStyleClass().add(amount > 0 ? "ghost-button" : "danger-ghost-button");
			b.setOnAction(e -> perform(() -> StoreApi.adminCoins(uuid, amount, "owner panel"), uuid, true));
			quick.getChildren().add(b);
		}
		TextField custom = new TextField();
		custom.setPromptText("Custom, e.g. 750 or -200");
		custom.setPrefWidth(180);
		TextField note = new TextField();
		note.setPromptText("Note (shown in their history)");
		HBox.setHgrow(note, Priority.ALWAYS);
		Button apply = new Button("Apply");
		apply.getStyleClass().add("primary-button");
		apply.setOnAction(e -> {
			int value;
			try {
				value = Integer.parseInt(custom.getText().trim().replace("+", ""));
			} catch (NumberFormatException ex) {
				status.setText("Coins: a whole number, e.g. 500 or -200");
				return;
			}
			perform(() -> StoreApi.adminCoins(uuid, value, note.getText().trim()), uuid, true);
		});
		HBox row = new HBox(8, custom, note, apply);
		row.setAlignment(Pos.CENTER_LEFT);
		return section("Coins", new VBox(8, quick, row));
	}

	private Node plusCard(String uuid, boolean plus) {
		FlowPane quick = new FlowPane(6, 6);
		for (int days : new int[] {1, 7, 14, 30, 90, 365}) {
			Button b = new Button("+" + days + (days == 1 ? " day" : " days"));
			b.getStyleClass().add("ghost-button");
			b.setOnAction(e -> perform(() -> StoreApi.adminPlus(uuid, days), uuid, true));
			quick.getChildren().add(b);
		}
		TextField custom = new TextField();
		custom.setPromptText("Days");
		custom.setPrefWidth(90);
		Button give = new Button("Add days");
		give.getStyleClass().add("primary-button");
		give.setOnAction(e -> {
			int days;
			try {
				days = Integer.parseInt(custom.getText().replaceAll("[^0-9]", ""));
			} catch (NumberFormatException ex) {
				status.setText("Velo+: enter the number of days");
				return;
			}
			if (days <= 0) {
				status.setText("Days must be more than 0 (use End Velo+ to remove it)");
				return;
			}
			perform(() -> StoreApi.adminPlus(uuid, days), uuid, true);
		});
		Button end = new Button("End Velo+");
		end.getStyleClass().add("danger-ghost-button");
		end.setDisable(!plus);
		end.setOnAction(e -> {
			if (confirm("End Velo+?", "Their Velo+ membership ends right away.")) {
				perform(() -> StoreApi.adminPlus(uuid, 0), uuid, true);
			}
		});
		HBox row = new HBox(8, custom, give, end);
		row.setAlignment(Pos.CENTER_LEFT);
		return section("Velo+", new VBox(8, quick, row,
				hint("Days add to what they have. Coins, badge and Addons switch on within a minute; the Discord role follows once linked.")));
	}

	private Node cosmeticsCard(String uuid, JsonArray owned) {
		List<String> ownedIds = new ArrayList<>();
		owned.forEach(e -> ownedIds.add(e.getAsString()));

		ComboBox<String> add = new ComboBox<>();
		for (String id : itemsById.keySet().stream().sorted().toList()) {
			if (!ownedIds.contains(id)) {
				add.getItems().add(id);
			}
		}
		add.setPromptText("Give a cosmetic...");
		add.setPrefWidth(260);
		Button give = new Button("Give");
		give.getStyleClass().add("primary-button");
		give.setOnAction(e -> {
			if (add.getValue() != null) {
				perform(() -> StoreApi.adminItem(uuid, add.getValue(), true), uuid, true);
			}
		});
		HBox addRow = new HBox(8, add, give);
		addRow.setAlignment(Pos.CENTER_LEFT);

		FlowPane grid = new FlowPane(10, 10);
		for (String id : ownedIds) {
			grid.getChildren().add(ownedTile(uuid, id));
		}
		Node body = ownedIds.isEmpty() ? hint("This player doesn't own any cosmetics.") : grid;
		return section("Cosmetics (" + ownedIds.size() + ")", new VBox(10, addRow, body));
	}

	private Node ownedTile(String uuid, String id) {
		JsonObject item = itemsById.get(id);
		Node thumb = item != null ? OwnerPreviews.thumb(item, 132, 100) : OwnerPreviews.placeholder(id, 132, 100);
		String shown = item != null && item.has("name") ? item.get("name").getAsString() : id;
		Label name = new Label(shown);
		name.getStyleClass().add("settings-card-title");
		name.setMaxWidth(132);
		Label meta = hint(id);
		meta.setMaxWidth(132);
		Button remove = new Button("Remove");
		remove.getStyleClass().add("danger-ghost-button");
		remove.setMaxWidth(Double.MAX_VALUE);
		remove.setOnAction(e -> {
			if (confirm("Remove " + shown + "?", "It is taken away from this player (they can buy or be given it again).")) {
				perform(() -> StoreApi.adminItem(uuid, id, false), uuid, true);
			}
		});
		VBox tile = new VBox(6, thumb, name, meta, remove);
		tile.setPrefWidth(150);
		tile.setPadding(new javafx.geometry.Insets(9));
		tile.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 12;");
		return tile;
	}

	private Node activityCard(JsonArray ledger) {
		if (ledger.isEmpty()) {
			return section("Recent activity", hint("Nothing yet."));
		}
		VBox lines = new VBox(3);
		for (int i = 0; i < Math.min(8, ledger.size()); i++) {
			JsonObject entry = ledger.get(i).getAsJsonObject();
			int delta = entry.get("delta").getAsInt();
			String text = (delta >= 0 ? "+" : "") + delta + "   " + entry.get("reason").getAsString()
					+ (entry.has("ref") && !entry.get("ref").isJsonNull() ? "   (" + entry.get("ref").getAsString() + ")" : "");
			lines.getChildren().add(hint(text));
		}
		return section("Recent activity", lines);
	}

	// ---- Bulk giveaway (the old several-names form) ----

	private interface PerTarget {
		JsonObject run(String target) throws StoreApi.StoreError;
	}

	private Node bulkCard() {
		TextField targets = new TextField();
		targets.setPromptText("Names or UUIDs, comma separated");
		TextArea output = new TextArea();
		output.setEditable(false);
		output.setWrapText(true);
		output.setPrefRowCount(6);
		TextField amount = new TextField();
		amount.setPromptText("Coins");
		amount.setPrefWidth(120);
		Button coins = new Button("Give coins");
		coins.getStyleClass().add("primary-button");
		coins.setOnAction(e -> {
			try {
				int value = Integer.parseInt(amount.getText().trim().replace("+", ""));
				each(targets, t -> StoreApi.adminCoins(t, value, "giveaway"), output);
			} catch (NumberFormatException ex) {
				output.setText("Coins: a whole number");
			}
		});
		ComboBox<String> item = new ComboBox<>();
		item.getItems().addAll(itemsById.keySet().stream().sorted().toList());
		item.setPromptText("Cosmetic");
		Button give = new Button("Give cosmetic");
		give.getStyleClass().add("primary-button");
		give.setOnAction(e -> {
			if (item.getValue() != null) {
				each(targets, t -> StoreApi.adminItem(t, item.getValue(), true), output);
			}
		});
		TextField days = new TextField();
		days.setPromptText("Velo+ days");
		days.setPrefWidth(110);
		Button plus = new Button("Give Velo+");
		plus.getStyleClass().add("primary-button");
		plus.setOnAction(e -> {
			try {
				int value = Integer.parseInt(days.getText().replaceAll("[^0-9]", ""));
				each(targets, t -> StoreApi.adminPlus(t, value), output);
			} catch (NumberFormatException ex) {
				output.setText("Velo+: number of days");
			}
		});
		FlowPane actions = new FlowPane(8, 8, amount, coins, item, give, days, plus);
		actions.setAlignment(Pos.CENTER_LEFT);
		return new VBox(8, targets, actions, output);
	}

	private void each(TextField field, PerTarget action, TextArea output) {
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
					JsonObject v = action.run(target);
					text.append(v.get("username").getAsString()).append(": ok, ")
							.append(String.format(Locale.ROOT, "%,d", v.get("balance").getAsInt())).append(" coins\n");
				} catch (StoreApi.StoreError e) {
					text.append(target).append(": ").append(e.getMessage()).append("\n");
				}
			}
			Platform.runLater(() -> output.setText(text.toString().strip()));
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	// ---- Helpers ----

	private boolean confirm(String title, String message) {
		javafx.stage.Window window = result.getScene() == null ? null : result.getScene().getWindow();
		return Confirm.ask(window instanceof javafx.stage.Stage s ? s : null, title, message);
	}

	private static String remaining(long until) {
		Duration left = Duration.between(Instant.now(), Instant.ofEpochMilli(until));
		long days = left.toDays();
		if (days >= 1) {
			return days + (days == 1 ? " day left" : " days left");
		}
		long hours = Math.max(1, left.toHours());
		return hours + (hours == 1 ? " hour left" : " hours left");
	}

	private static String date(long epochMillis) {
		return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).toString();
	}

	private static Node section(String heading, Node body) {
		VBox box = new VBox(8, title(heading), body);
		box.setPadding(new javafx.geometry.Insets(12, 14, 12, 14));
		box.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 12;");
		return box;
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
}
