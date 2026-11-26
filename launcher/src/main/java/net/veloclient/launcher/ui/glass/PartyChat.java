package net.veloclient.launcher.ui.glass;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import net.veloclient.launcher.social.LauncherSocial;
import net.veloclient.launcher.ui.Heads;
import net.veloclient.launcher.ui.Icons;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Home's party chat (2026-11-25): a glass card at the bottom of the right column while you're in a party - the last
 * messages (yours as accent bubbles on the right, the others' with their head), and a field to write. New messages
 * slide in live; it shows and hides itself as you join or leave a party, without rebuilding Home.
 */
public final class PartyChat {

	private static final int KEEP = 40;

	private final VBox card = new VBox(8);
	private final VBox lines = new VBox(6);
	private final ScrollPane scroll = new ScrollPane(lines);
	private final Label title = new Label("Party chat");
	private int shownCount = -1;
	private String shownParty = "";

	private PartyChat() {
	}

	public static Node create() {
		return new PartyChat().build();
	}

	private Node build() {
		title.getStyleClass().add("panel-title");
		// how you get out of a party, right where the party is (owner 2026-11-25: "unclear how to leave")
		Button leave = new Button("Leave");
		leave.setGraphic(net.veloclient.launcher.ui.Icons.icon("logout", 12, javafx.scene.paint.Color.web("#FF8A8F"), 2.2));
		leave.getStyleClass().addAll("danger-ghost-button", "party-chat-leave");
		leave.setMinWidth(Region.USE_PREF_SIZE);
		leave.setOnAction(e -> {
			leave.setDisable(true);
			net.veloclient.launcher.social.LauncherSocial.partyLeave().thenAccept(error -> javafx.application.Platform.runLater(() -> {
				leave.setDisable(false);
				if (error != null) {
					net.veloclient.launcher.ui.Toasts.show(leave, "warning", "#FF6B6F", error);
				}
			}));
		});
		Region push = new Region();
		HBox.setHgrow(push, Priority.ALWAYS);
		HBox top = new HBox(8, title, push, leave);
		top.setAlignment(Pos.CENTER_LEFT);

		lines.setPadding(new Insets(2));
		lines.setFillWidth(true);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("edge-to-edge");
		// grows / shrinks with what's left in the column (news, running games and friends come first)
		scroll.setPrefHeight(150);
		scroll.setMinHeight(54);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		lines.heightProperty().addListener((o, a, b) -> scroll.setVvalue(1.0));

		TextField input = new TextField();
		input.setPromptText("Message your party...");
		input.getStyleClass().add("party-chat-input");
		HBox.setHgrow(input, Priority.ALWAYS);
		Button send = new Button();
		send.setGraphic(Icons.icon("send", 14, Color.WHITE, 2.2));
		send.getStyleClass().setAll("button", "party-chat-send");
		Runnable sendNow = () -> {
			String text = input.getText().strip();
			if (text.isEmpty()) {
				return;
			}
			input.clear();
			LauncherSocial.partyChat(text).thenAccept(error -> {
				if (error != null) {
					Platform.runLater(() -> input.setPromptText(error));
				}
			});
		};
		send.setOnAction(e -> sendNow.run());
		input.setOnAction(e -> sendNow.run());
		// same height, centred on one line (the button sat a few px low)
		input.setMinHeight(34);
		input.setPrefHeight(34);
		input.setMaxHeight(34);
		HBox inputRow = new HBox(6, input, send);
		inputRow.setAlignment(Pos.CENTER);
		inputRow.setMinHeight(34);
		inputRow.setFillHeight(false);

		card.getChildren().addAll(top, scroll, inputRow);
		card.setPadding(new Insets(16));
		card.getStyleClass().addAll("glass-panel", "party-chat");
		card.setMinHeight(150);
		card.setMaxHeight(270);
		VBox.setVgrow(card, Priority.ALWAYS);
		Glass.install(card, 24);

		Consumer<LauncherSocial.PartyEvent> listener = event -> refresh();
		card.sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				LauncherSocial.removePartyListener(listener);
			} else {
				LauncherSocial.addPartyListener(listener);
			}
		});
		refresh();
		return card;
	}

	private static boolean tour() {
		return System.getProperty("velo.tourParty") != null;
	}

	private List<JsonObject> messages() {
		List<JsonObject> out = new ArrayList<>();
		if (tour()) {
			// dev (screenshot tour): a short sample conversation
			String[][] sample = {{"Notch", "069a79f444e94726a5befca90e38aaf5", "ready when you are"}, {"jeb_", "853c80ef3c3749fdaa49938b674adae6", "bring the sheep"},
					{"", "me", "launching LuckySMP in 10s"}, {"Notch", "069a79f444e94726a5befca90e38aaf5", "gg let's go"}};
			long t = System.currentTimeMillis() - 60_000;
			for (String[] m : sample) {
				JsonObject o = new JsonObject();
				o.addProperty("kind", "chat");
				o.addProperty("name", m[0]);
				o.addProperty("from", m[1]);
				o.addProperty("text", m[2]);
				o.addProperty("time", t += 9000);
				out.add(o);
			}
			return out;
		}
		JsonObject party = LauncherSocial.party();
		if (party != null && party.has("chat")) {
			for (JsonElement e : party.getAsJsonArray("chat")) {
				out.add(e.getAsJsonObject());
			}
		}
		return out.size() > KEEP ? out.subList(out.size() - KEEP, out.size()) : out;
	}

	/** Shows / hides the card with the party and adds new lines (only the new ones, sliding in). */
	private void refresh() {
		JsonObject party = LauncherSocial.party();
		boolean inParty = tour() || party != null;
		card.setVisible(inParty);
		card.setManaged(inParty);
		if (!inParty) {
			lines.getChildren().clear();
			shownCount = -1;
			return;
		}
		String id = tour() ? "tour" : party.has("id") ? party.get("id").getAsString() : "";
		List<JsonObject> msgs = messages();
		boolean fresh = !id.equals(shownParty) || shownCount < 0 || msgs.size() < shownCount;
		if (fresh) {
			lines.getChildren().clear();
			shownParty = id;
			shownCount = 0;
		}
		for (int i = shownCount; i < msgs.size(); i++) {
			Node line = line(msgs.get(i));
			lines.getChildren().add(line);
			if (!fresh) {
				line.setOpacity(0);
				line.setTranslateY(10);
				FadeTransition fade = new FadeTransition(Duration.millis(220), line);
				fade.setToValue(1);
				TranslateTransition rise = new TranslateTransition(Duration.millis(260), line);
				rise.setToY(0);
				fade.play();
				rise.play();
			}
		}
		shownCount = msgs.size();
		if (lines.getChildren().isEmpty()) {
			Label empty = new Label("Say hi to your party.");
			empty.getStyleClass().add("home-card-empty");
			lines.getChildren().add(empty);
			shownCount = 0;
		} else if (lines.getChildren().getFirst() instanceof Label l && l.getStyleClass().contains("home-card-empty") && lines.getChildren().size() > 1) {
			lines.getChildren().removeFirst();
		}
		long members = party != null && party.has("members") ? party.getAsJsonArray("members").size() : 4;
		title.setText("Party chat · " + members);
	}

	private Node line(JsonObject m) {
		String kind = str(m, "kind");
		String text = str(m, "text");
		if ("system".equals(kind)) {
			Label system = new Label(text);
			system.getStyleClass().add("party-chat-system");
			system.setWrapText(true);
			return system;
		}
		String from = str(m, "from");
		boolean mine = "me".equals(from) || from.equals(LauncherSocial.myUuid());
		Label body = new Label(text);
		body.setWrapText(true);
		body.getStyleClass().add(mine ? "party-bubble-mine" : "party-bubble");
		body.setMaxWidth(200);
		if (mine) {
			HBox row = new HBox(body);
			row.setAlignment(Pos.CENTER_RIGHT);
			return row;
		}
		Label who = new Label(str(m, "name"));
		who.getStyleClass().add("party-chat-name");
		VBox textBox = new VBox(2, who, body);
		textBox.setMinWidth(0);
		HBox row = new HBox(8, Heads.head(from, str(m, "name"), 22), textBox);
		row.setAlignment(Pos.TOP_LEFT);
		return row;
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}
}
