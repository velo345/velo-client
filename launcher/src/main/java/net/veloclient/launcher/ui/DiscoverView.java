package net.veloclient.launcher.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.social.StoreApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Discover, on the Servers page: partner servers the Velo team lists, friends' hosted worlds you may join and public
 * hosted worlds (World Hosting). Partner servers launch straight in; a hosted world launches a profile of its version
 * and the game joins it once it's signed in (see the game's {@code WorldHosting.pendingJoin}).
 */
public final class DiscoverView {

	/** What clicking does - implemented by the launcher (it owns profiles and launching). */
	public interface Joiner {
		void server(String address, Button trigger);

		void world(String worldId, String version, Button trigger);

		/** Opens a reward's own page (cloak / aura) to look at it. */
		default void preview(String itemId) {
		}
	}

	private DiscoverView() {
	}

	public static Node build(Joiner joiner) {
		return build(joiner, false);
	}

	/**
	 * {@code row}: one line of cards that scrolls sideways (the Home screen, under the launch options) and hides itself
	 * when there's nothing to discover; otherwise a wrapping grid (the Servers page).
	 */
	public static Node build(Joiner joiner, boolean row) {
		VBox box = new VBox(12);
		Label title = new Label("Discover");
		title.getStyleClass().add("t-title-sm");
		Label sub = new Label("Partner servers and worlds hosted with Velo - no IPs, no port forwarding");
		sub.getStyleClass().add("t-muted");
		box.getChildren().addAll(new VBox(2, title, sub));
		javafx.scene.layout.Pane cards = row ? new HBox(12) : new FlowPane(12, 12);
		if (row) {
			ScrollPane strip = new ScrollPane(cards);
			strip.setFitToHeight(true);
			strip.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
			strip.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
			strip.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
			// as tall as the cards (a fixed 196 px cut the partner card with its reward and put a scroll bar under it)
			strip.prefViewportHeightProperty().bind(cards.heightProperty());
			strip.minViewportHeightProperty().bind(cards.heightProperty());
			box.getChildren().add(strip);
		} else {
			box.getChildren().add(cards);
		}
		// Animated placeholders in the cards' shape while partners and worlds load (2026-11).
		cards.getChildren().add(Skeleton.grid(row ? 3 : 2, 340, 132));
		CompletableFuture.supplyAsync(() -> {
			List<Node> out = new ArrayList<>();
			JsonObject partners = quietly(StoreApi::partners);
			JsonObject friends = quietly(StoreApi::joinableWorlds);
			JsonObject publicWorlds = quietly(StoreApi::publicWorlds);
			return new JsonObject[] {partners, friends, publicWorlds};
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(data -> Platform.runLater(() -> {
			cards.getChildren().clear();
			if (data[0] != null && data[0].has("partners")) {
				for (JsonElement e : data[0].getAsJsonArray("partners")) {
					JsonObject p = e.getAsJsonObject();
					Button join = new Button("Join");
					join.getStyleClass().add("primary-button");
					join.setOnAction(ev -> joiner.server(p.get("address").getAsString(), join));
					cards.getChildren().add(partnerCard(p, join, joiner));
				}
			}
			java.util.Set<String> shown = new java.util.HashSet<>();
			if (data[1] != null && data[1].has("worlds")) {
				for (JsonElement e : data[1].getAsJsonArray("worlds")) {
					JsonObject w = e.getAsJsonObject();
					shown.add(w.get("id").getAsString());
					Button join = new Button("Join");
					join.getStyleClass().add("primary-button");
					join.setOnAction(ev -> joiner.world(w.get("id").getAsString(), w.get("version").getAsString(), join));
					cards.getChildren().add(card("FRIEND", "#3ddc97", w.get("name").getAsString(), "Hosted by " + w.get("hostName").getAsString(),
							w.get("version").getAsString() + "  ·  " + w.get("guests").getAsInt() + " playing", join));
				}
			}
			if (data[2] != null && data[2].has("worlds")) {
				for (JsonElement e : data[2].getAsJsonArray("worlds")) {
					JsonObject w = e.getAsJsonObject();
					if (shown.contains(w.get("id").getAsString())) {
						continue;
					}
					boolean ask = w.get("ask").getAsBoolean();
					boolean full = w.get("guests").getAsInt() >= w.get("maxPlayers").getAsInt();
					Button join = new Button(full ? "Full" : ask ? "Ask to join" : "Join");
					join.getStyleClass().add(ask ? "ghost-button" : "primary-button");
					join.setDisable(full);
					join.setOnAction(ev -> {
						if (ask) {
							join.setDisable(true);
							join.setText("Asking...");
							CompletableFuture.runAsync(() -> {
								String result;
								try {
									result = StoreApi.askWorld(w.get("id").getAsString()).get("allowed").getAsBoolean() ? "Let in - press Join" : "Asked the host";
								} catch (StoreApi.StoreError error) {
									result = error.getMessage();
								}
								String shownText = result;
								Platform.runLater(() -> join.setText(shownText));
							}, Executors.newVirtualThreadPerTaskExecutor());
						} else {
							joiner.world(w.get("id").getAsString(), w.get("version").getAsString(), join);
						}
					});
					VBox publicCard = (VBox) card("PUBLIC", "#5aa9ff", w.get("name").getAsString(), text(w, "description", "Hosted by " + w.get("hostName").getAsString()),
							w.get("version").getAsString() + "  ·  " + w.get("guests").getAsInt() + "/" + w.get("maxPlayers").getAsInt() + " playing", join);
					// Report: what's wrong with it, one click - Velo staff look at it.
					javafx.scene.control.MenuButton report = new javafx.scene.control.MenuButton();
					report.setGraphic(Icons.icon("flag", 14, javafx.scene.paint.Color.web("#A9A9B3")));
					report.getStyleClass().add("ghost-button");
					report.setTooltip(new javafx.scene.control.Tooltip("Report this world"));
					for (String[] reason : new String[][] {{"inappropriate", "Inappropriate"}, {"harassment", "Harassment or hate"},
							{"cheating", "Cheating"}, {"scam", "Scam"}, {"spam", "Spam"}, {"other", "Something else"}}) {
						javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(reason[1]);
						item.setOnAction(ev -> CompletableFuture.runAsync(() -> {
							String result;
							try {
								StoreApi.reportWorld(w.get("id").getAsString(), reason[0]);
								result = "Reported - staff will take a look";
							} catch (StoreApi.StoreError error) {
								result = error.getMessage();
							}
							String text = result;
							Platform.runLater(() -> {
								Toasts.show(report, text);
								report.setDisable(true);
							});
						}, Executors.newVirtualThreadPerTaskExecutor()));
						report.getItems().add(item);
					}
					HBox bottom = (HBox) publicCard.getChildren().getLast();
					bottom.getChildren().add(bottom.getChildren().size() - 1, report);
					cards.getChildren().add(publicCard);
				}
			}
			if (cards.getChildren().isEmpty() && row) {
				box.setVisible(false);
				box.setManaged(false);
				return;
			}
			if (cards.getChildren().isEmpty()) {
				Label none = new Label("Nothing to discover right now. With Velo+ you can host your own world for friends - or for everyone.");
				none.getStyleClass().add("t-muted");
				none.setWrapText(true);
				cards.getChildren().add(none);
			}
		}));
		return box;
	}

	private interface Call {
		JsonObject run() throws StoreApi.StoreError;
	}

	private static JsonObject quietly(Call call) {
		try {
			return call.run();
		} catch (Exception e) {
			return null;
		}
	}

	private static String text(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() && !o.get(key).getAsString().isBlank() ? o.get(key).getAsString() : fallback;
	}

	/**
	 * A partner server: its icon big on an accent-tinted card, live player count, the address (click to copy) and its
	 * partner code, if it has one, as a ticket-style badge.
	 */
	private static Node partnerCard(JsonObject p, Button join, Joiner joiner) {
		String accent = p.has("accent") && !p.get("accent").isJsonNull() ? p.get("accent").getAsString() : "#ff2d3c";
		String address = p.get("address").getAsString();
		boolean online = p.has("online") && p.get("online").getAsBoolean();

		javafx.scene.layout.StackPane iconBox = new javafx.scene.layout.StackPane();
		iconBox.setMinSize(48, 48);
		iconBox.setMaxSize(48, 48);
		iconBox.setStyle("-fx-background-color: #0f0c0c; -fx-background-radius: 10;");
		String base = net.veloclient.launcher.social.LauncherSocial.baseUrl();
		if (base != null && p.has("icon") && !p.get("icon").isJsonNull()) {
			javafx.scene.image.ImageView icon = new javafx.scene.image.ImageView(new javafx.scene.image.Image(
					base + "/v1/partners/icon/" + p.get("id").getAsString() + "?s=128&v=" + p.get("icon").getAsString(), 112, 112, true, true, true));
			icon.setFitWidth(48);
			icon.setFitHeight(48);
			icon.setSmooth(true);
			javafx.scene.shape.Rectangle round = new javafx.scene.shape.Rectangle(48, 48);
			round.setArcWidth(20);
			round.setArcHeight(20);
			icon.setClip(round);
			iconBox.getChildren().add(icon);
		} else {
			Label letter = new Label(p.get("name").getAsString().substring(0, 1).toUpperCase(java.util.Locale.ROOT));
			letter.setStyle("-fx-text-fill: " + accent + "; -fx-font-size: 24px; -fx-font-weight: 900;");
			iconBox.getChildren().add(letter);
		}

		Label tag = new Label("PARTNER");
		tag.getStyleClass().add("partner-tag");
		Label name = new Label(p.get("name").getAsString());
		name.getStyleClass().add("t-strong");
		name.setStyle("-fx-font-size: 15px;");
		Region dot = new Region();
		dot.getStyleClass().add("status-square");
		if (!online) {
			dot.getStyleClass().add("status-square-off");
		}
		Label players = new Label(online ? String.format(java.util.Locale.ROOT, "%,d playing", p.get("players").getAsInt()) : "Offline");
		players.setStyle("-fx-text-fill: " + (online ? "-velo-text" : "-velo-text-3") + "; -fx-font-size: 12px;");
		HBox live = new HBox(7, dot, players);
		live.setAlignment(Pos.CENTER_LEFT);
		String versions = text(p, "versions", "");
		if (!versions.isEmpty()) {
			Label v = new Label("·  " + versions);
			v.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11.5px;");
			live.getChildren().add(v);
		}
		HBox top = new HBox(12, iconBox, new VBox(4, new HBox(8, name, tag), live));
		((HBox) ((VBox) top.getChildren().get(1)).getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);
		top.setAlignment(Pos.CENTER_LEFT);

		Label description = new Label(text(p, "description", ""));
		description.getStyleClass().add("t-muted");
		description.setWrapText(false);
		description.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
		description.setMaxWidth(Double.MAX_VALUE);

		Button copy = new Button(address);
		copy.setGraphic(Icons.icon("copy", 12, javafx.scene.paint.Color.web("#A9A9B3")));
		copy.getStyleClass().add("ghost-button");
		copy.setStyle("-fx-font-size: 11.5px; -fx-padding: 4 9 4 9;");
		copy.setTooltip(new javafx.scene.control.Tooltip("Copy the address"));
		copy.setOnAction(ev -> {
			javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
			content.putString(address);
			javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
			Toasts.show(copy, "Address copied");
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox bottom = new HBox(8, copy, spacer, join);
		bottom.setAlignment(Pos.CENTER_LEFT);

		VBox card = new VBox(9, top, description);
		// 2026-11: perks as one quiet line (code + "free reward") instead of a ticket chip and a cosmetics box.
		List<String> perks = new ArrayList<>();
		if (p.has("codes") && p.get("codes").isJsonArray() && !p.getAsJsonArray("codes").isEmpty()) {
			JsonObject first = p.getAsJsonArray("codes").get(0).getAsJsonObject();
			int off = first.has("percent") ? first.get("percent").getAsInt() : 0;
			perks.add("Code " + first.get("code").getAsString() + (off > 0 ? " · " + off + "% off coins" : ""));
		}
		if (p.has("reward") && p.get("reward").isJsonObject()) {
			JsonObject reward = p.getAsJsonObject("reward");
			int minutes = reward.has("minutes") ? reward.get("minutes").getAsInt() : 0;
			perks.add(minutes > 0 ? "Free cosmetic after " + minutes + " min" : "Free cosmetic");
		}
		if (!perks.isEmpty()) {
			Label perkLine = new Label(String.join("   ·   ", perks));
			perkLine.getStyleClass().add("partner-perks");
			card.getChildren().add(perkLine);
		}
		// No stretching spacer: the card is only as tall as what it shows (2026-11: it stood too tall on Home).
		card.getChildren().add(bottom);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		// Compact and calm (2026-11): same footprint for every partner, so many of them sit side by side.
		card.getStyleClass().add("partner-card");
		card.setPadding(new Insets(14));
		card.setPrefWidth(360);
		card.setMinWidth(300);
		card.setMaxWidth(420);
		card.setOnMouseEntered(e -> card.setTranslateY(-2));
		card.setOnMouseExited(e -> card.setTranslateY(0));
		return card;
	}

	/**
	 * What playing there gives (server's PartnerPlayRewards), as one tidy line: the cloak's front, "Play 10 min - free" with
	 * the items' names, and a button per item that opens its page to look at it (the 3D preview on your skin). The server's
	 * full sentence is the tooltip.
	 */
	private static Node rewardRow(JsonObject reward, String accent, Joiner joiner) {
		javafx.scene.image.ImageView preview = new javafx.scene.image.ImageView();
		preview.setFitWidth(22);
		preview.setFitHeight(35);
		preview.setPreserveRatio(true);
		preview.setSmooth(true);
		javafx.scene.layout.StackPane thumb = new javafx.scene.layout.StackPane(preview);
		thumb.setMinSize(30, 40);
		thumb.setMaxSize(30, 40);
		thumb.setStyle("-fx-background-color: rgba(0,0,0,0.25); -fx-background-radius: 7;");
		int minutes = reward.has("minutes") ? reward.get("minutes").getAsInt() : 0;
		Label headline = new Label(minutes > 0 ? "Play " + minutes + " min here  ·  free" : "Free reward");
		headline.setStyle("-fx-text-fill: " + accent + "; -fx-font-size: 11.5px; -fx-font-weight: 800;");
		Label items = new Label("");
		items.setStyle("-fx-text-fill: -velo-text-2; -fx-font-size: 11px;");
		items.setWrapText(true);
		HBox buttons = new HBox(6);
		buttons.setAlignment(Pos.CENTER_LEFT);
		VBox words = new VBox(3, headline, items, buttons);
		words.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(words, Priority.ALWAYS);
		HBox row = new HBox(10, thumb, words);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setStyle("-fx-background-color: " + accent + "12; -fx-background-radius: 10; -fx-padding: 6 8 6 6;");
		String text = text(reward, "text", "");
		if (!text.isEmpty()) {
			javafx.scene.control.Tooltip.install(row, new javafx.scene.control.Tooltip(text));
		}
		List<String> ids = new ArrayList<>();
		if (reward.has("items") && reward.get("items").isJsonArray()) {
			for (var item : reward.getAsJsonArray("items")) {
				ids.add(item.getAsString());
			}
		}
		for (String id : ids) {
			Button look = new Button(id.startsWith("cape_") ? "Cloak" : id.startsWith("aura_") ? "Aura" : "Item");
			look.setGraphic(Icons.icon("eye", 11, javafx.scene.paint.Color.web("#C9CBD3")));
			look.getStyleClass().add("ghost-button");
			look.setStyle("-fx-font-size: 11px; -fx-padding: 2 8 2 7;");
			look.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
			look.setTooltip(new javafx.scene.control.Tooltip("Preview it"));
			look.setOnAction(e -> joiner.preview(id));
			buttons.getChildren().add(look);
		}
		String base = net.veloclient.launcher.social.LauncherSocial.baseUrl();
		if (base != null && !ids.isEmpty()) {
			Thread.ofVirtual().name("velo-partner-reward").start(() -> {
				java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
				List<String> names = new ArrayList<>();
				for (String id : ids) {
					try {
						String body = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v1/cosmetics/item?id=" + id)).build(),
								java.net.http.HttpResponse.BodyHandlers.ofString()).body();
						JsonObject item = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
						names.add(text(item, "name", id) + (id.startsWith("cape_") ? " Cloak" : id.startsWith("aura_") ? " Aura" : ""));
						if (id.startsWith("cape_") && item.has("files")) {
							JsonObject files = item.getAsJsonObject("files");
							String sha = files.has("card") ? files.get("card").getAsString() : files.get("gif").getAsString();
							byte[] gif = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v1/cosmetics/file/" + sha)).build(),
									java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body();
							java.awt.image.BufferedImage frame = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(gif));
							if (frame != null) {
								// the cloak's front: x 1..11, y 1..17 of the 64 x 32 layout
								int px = frame.getWidth() / 64;
								javafx.scene.image.Image image = javafx.embed.swing.SwingFXUtils.toFXImage(frame.getSubimage(px, px, 10 * px, 16 * px), null);
								javafx.application.Platform.runLater(() -> preview.setImage(image));
							}
						}
					} catch (Exception ignored) {
						// just no name / picture for this one
					}
				}
				String line = String.join(" + ", names.stream().map(n -> n.replace(" Cloak Cloak", " Cloak").replace(" Aura Aura", " Aura")).toList());
				javafx.application.Platform.runLater(() -> items.setText(line));
			});
		}
		return row;
	}

	private static Node card(String kind, String accent, String name, String line, String meta, Button action) {
		Label tag = new Label(kind);
		tag.setStyle("-fx-background-color: " + accent + "33; -fx-text-fill: " + accent + "; -fx-background-radius: 6; -fx-padding: 2 7 2 7;"
				+ " -fx-font-size: 10px; -fx-font-weight: 900;");
		Label title = new Label(name);
		title.getStyleClass().add("t-strong");
		title.setStyle("-fx-font-size: 15px;");
		Label description = new Label(line);
		description.getStyleClass().add("t-muted");
		description.setWrapText(true);
		description.setMaxHeight(36);
		Label info = new Label(meta);
		info.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		Region grow = new Region();
		VBox.setVgrow(grow, Priority.ALWAYS);
		HBox bottom = new HBox(info);
		bottom.setAlignment(Pos.CENTER_LEFT);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		bottom.getChildren().addAll(spacer, action);
		VBox card = new VBox(8, tag, title, description, grow, bottom);
		card.getStyleClass().add("partner-card");
		card.setPadding(new Insets(14));
		card.setPrefSize(300, 150);
		card.setMinWidth(280);
		return card;
	}
}
