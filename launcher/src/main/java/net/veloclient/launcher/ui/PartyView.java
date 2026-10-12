package net.veloclient.launcher.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import net.veloclient.launcher.data.LauncherSettings;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.party.PartyModSync;
import net.veloclient.launcher.social.LauncherSocial;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Party page: who's in (heads, online status and what they're doing, the leader's crown),
 * invites both ways, party chat, and the leader's tools - make someone else leader, remove a
 * member, share your mod profile so everyone plays with the same mods, and launch together.
 */
public final class PartyView {

	/** What the page needs from the launcher. */
	public interface Host {
		/** The profile selected on Home (the one that's shared / synced / launched). */
		Instance currentProfile();
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

	private final Host host;
	private final VBox root = new VBox(14);
	private final Label feedback = new Label();
	private final Consumer<LauncherSocial.PartyEvent> listener = e -> rebuild();
	private VBox chatList;
	private ScrollPane chatScroll;

	private PartyView(Host host) {
		this.host = host;
		feedback.getStyleClass().add("settings-row-hint");
		feedback.setWrapText(true);
	}

	public static Node build(Host host) {
		PartyView view = new PartyView(host);
		LauncherSocial.addPartyListener(view.listener);
		view.root.sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				LauncherSocial.removePartyListener(view.listener);
			}
		});
		view.rebuild();
		return view.root;
	}

	private void rebuild() {
		JsonObject party = LauncherSocial.party();
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox top = new HBox(10, spacer);
		top.setAlignment(Pos.CENTER_LEFT);
		if (party != null) {
			Button leave = new Button("Leave party");
			leave.getStyleClass().add("danger-ghost-button");
			leave.setOnAction(e -> act(LauncherSocial.partyLeave(), "You left the party"));
			top.getChildren().add(leave);
		}

		VBox content = new VBox(14);
		for (JsonObject invite : LauncherSocial.partyInvites()) {
			content.getChildren().add(inviteCard(invite));
		}
		if (LauncherSocial.sessionToken() == null && !LauncherSocial.isDemo()) {
			content.getChildren().add(hint("Sign in and connect to the Velo server to use parties. " + LauncherSocial.status()));
		} else if (party == null) {
			content.getChildren().add(emptyState());
		} else {
			HBox columns = new HBox(16, membersColumn(party), chatColumn(party));
			HBox.setHgrow(columns.getChildren().get(1), Priority.ALWAYS);
			VBox.setVgrow(columns, Priority.ALWAYS);
			content.getChildren().add(columns);
			VBox.setVgrow(content, Priority.ALWAYS);
		}
		content.getChildren().add(feedback);
		VBox.setVgrow(content, Priority.ALWAYS);
		root.getChildren().setAll(top, content);
	}

	private Node emptyState() {
		VBox card = new VBox(10);
		card.getStyleClass().add("settings-card");
		Label heading = new Label("Play together");
		heading.getStyleClass().add("settings-card-title");
		Label text = new Label("Start a party and invite friends: chat from the launcher, play with the leader's mods and launch together."
				+ " In game, ping spots for your party (Party Ping module) and share waypoints from the world map.");
		text.setWrapText(true);
		text.getStyleClass().add("settings-row-hint");
		Button start = new Button("Start a party");
		start.getStyleClass().add("primary-button");
		start.setOnAction(e -> act(LauncherSocial.partyCreate(), "Party started - invite your friends"));
		card.getChildren().addAll(heading, text, inviteRow(), start);
		return card;
	}

	private Node inviteCard(JsonObject invite) {
		String from = str(invite, "fromName");
		Label title = new Label(from + " invited you to a party");
		title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
		Label sub = new Label(str(invite, "leaderName") + "'s party \u00B7 " + num(invite, "members") + (num(invite, "members") == 1 ? " player" : " players"));
		sub.getStyleClass().add("settings-row-hint");
		VBox text = new VBox(3, title, sub);
		Button join = new Button("Join party");
		join.getStyleClass().add("primary-button");
		join.setOnAction(e -> act(LauncherSocial.partyRespond(str(invite, "party"), true), "You joined the party"));
		Button decline = new Button("Decline");
		decline.getStyleClass().add("glass-button");
		decline.setOnAction(e -> act(LauncherSocial.partyRespond(str(invite, "party"), false), "Invite declined"));
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox row = new HBox(14, Heads.head(str(invite, "from"), from, 44), text, spacer, decline, join);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(14));
		row.setStyle("-fx-background-color: linear-gradient(to right, rgba(120,140,255,0.22), rgba(120,140,255,0.06));"
				+ " -fx-background-radius: 14; -fx-border-color: rgba(140,160,255,0.45); -fx-border-radius: 14;");
		return row;
	}

	/** Online friends first, as clickable heads - one click invites. */
	private Node inviteRow() {
		VBox box = new VBox(6);
		Label label = new Label("Invite friends");
		label.getStyleClass().add("settings-row-hint");
		javafx.scene.layout.FlowPane heads = new javafx.scene.layout.FlowPane(8, 8);
		LauncherSocial.State state = LauncherSocial.snapshot();
		JsonObject party = LauncherSocial.party();
		java.util.Set<String> taken = new java.util.HashSet<>();
		if (party != null) {
			for (String key : new String[] {"members", "invited"}) {
				if (party.has(key)) {
					for (JsonElement e : party.getAsJsonArray(key)) {
						taken.add(e.getAsJsonObject().get("uuid").getAsString());
					}
				}
			}
		}
		if (state != null && state.friends != null) {
			state.friends.stream().filter(f -> !taken.contains(f.uuid))
					.sorted(java.util.Comparator.comparing((LauncherSocial.Friend f) -> !f.online).thenComparing(f -> f.username.toLowerCase(Locale.ROOT)))
					.limit(24)
					.forEach(friend -> heads.getChildren().add(inviteChip(friend)));
		}
		if (heads.getChildren().isEmpty()) {
			label.setText(state == null || state.friends == null || state.friends.isEmpty()
					? "Add friends on the Friends tab to invite them" : "Everyone's already invited");
		}
		box.getChildren().addAll(label, heads);
		return box;
	}

	private Node inviteChip(LauncherSocial.Friend friend) {
		StackPane head = new StackPane(Heads.head(friend.uuid, friend.username, 34));
		Circle dot = new Circle(5, friend.online ? Color.web("#3ecf6e") : Color.web("#7a7a80"));
		dot.setStroke(Color.web("#14141a"));
		dot.setStrokeWidth(2);
		StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
		head.getChildren().add(dot);
		Label name = new Label(friend.username);
		name.setStyle("-fx-font-size: 10px;");
		name.setMaxWidth(64);
		VBox chip = new VBox(3, head, name);
		chip.setAlignment(Pos.CENTER);
		chip.setPadding(new Insets(6));
		chip.setStyle("-fx-background-radius: 10; -fx-cursor: hand;");
		chip.setOnMouseEntered(e -> chip.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 10; -fx-cursor: hand;"));
		chip.setOnMouseExited(e -> chip.setStyle("-fx-background-radius: 10; -fx-cursor: hand;"));
		javafx.scene.control.Tooltip.install(chip, new javafx.scene.control.Tooltip("Invite " + friend.username));
		chip.setOnMouseClicked(e -> act(LauncherSocial.partyInvite(friend.uuid), "Invited " + friend.username));
		chip.setOpacity(friend.online ? 1 : 0.6);
		return chip;
	}

	private Node membersColumn(JsonObject party) {
		boolean leader = LauncherSocial.isPartyLeader();
		String me = LauncherSocial.myUuid();
		VBox column = new VBox(8);
		column.setPrefWidth(340);
		column.setMinWidth(300);
		Label heading = new Label("Members");
		heading.getStyleClass().add("settings-card-title");
		column.getChildren().add(heading);
		for (JsonElement element : party.getAsJsonArray("members")) {
			JsonObject member = element.getAsJsonObject();
			column.getChildren().add(memberRow(member, leader, me, false));
		}
		if (party.has("invited")) {
			for (JsonElement element : party.getAsJsonArray("invited")) {
				column.getChildren().add(memberRow(element.getAsJsonObject(), leader, me, true));
			}
		}
		column.getChildren().add(inviteRow());
		column.getChildren().add(leaderTools(party, leader));
		ScrollPane scroll = new ScrollPane(column);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		scroll.setPrefWidth(360);
		scroll.setMinWidth(320);
		return scroll;
	}

	private Node memberRow(JsonObject member, boolean iAmLeader, String me, boolean invited) {
		String uuid = str(member, "uuid");
		String name = str(member, "name");
		boolean online = member.has("online") && member.get("online").getAsBoolean();
		boolean isLeader = member.has("leader") && member.get("leader").getAsBoolean();
		Label nameLabel = new Label(name + (uuid.equals(me) ? " (you)" : ""));
		nameLabel.getStyleClass().add("settings-row-label");
		String activity = invited ? "Invited" : !online ? "Offline" : activityText(str(member, "activity"), str(member, "detail"));
		Label sub = new Label(activity);
		sub.getStyleClass().add("settings-row-hint");
		VBox text = new VBox(2, nameLabel, sub);
		Node head = Heads.head(uuid, name, 34);
		StackPane headBox = new StackPane(head);
		if (!invited) {
			Circle dot = new Circle(5, online ? Color.web("#3ecf6e") : Color.web("#7a7a80"));
			dot.setStroke(Color.web("#14141a"));
			dot.setStrokeWidth(2);
			StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
			headBox.getChildren().add(dot);
		}
		HBox row = new HBox(10, headBox, text);
		row.setAlignment(Pos.CENTER_LEFT);
		if (isLeader) {
			Label crown = new Label("♛ Leader");
			crown.setStyle("-fx-text-fill: #ffc53d; -fx-font-weight: bold;");
			row.getChildren().add(crown);
		}
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		row.getChildren().add(spacer);
		if (iAmLeader && !uuid.equals(me)) {
			if (!invited) {
				Button promote = new Button("Make leader");
				promote.getStyleClass().add("glass-button");
				promote.setOnAction(e -> act(LauncherSocial.partyTransfer(uuid), name + " leads the party now"));
				row.getChildren().add(promote);
			}
			Button kick = new Button(invited ? "Cancel" : "Remove");
			kick.getStyleClass().add("danger-ghost-button");
			kick.setOnAction(e -> act(LauncherSocial.partyKick(uuid), invited ? "Invite cancelled" : name + " was removed"));
			row.getChildren().add(kick);
		}
		row.getStyleClass().add("settings-row");
		row.setOpacity(invited ? 0.65 : 1);
		return row;
	}

	private static String activityText(String kind, String detail) {
		if (kind == null) {
			return "Online";
		}
		return switch (kind) {
			case "launcher" -> "In the launcher";
			case "menu" -> "In the menus";
			case "server" -> detail != null ? "Playing on " + detail : "Playing online";
			case "singleplayer" -> "In singleplayer";
			default -> detail != null ? detail : "Online";
		};
	}

	private Node leaderTools(JsonObject party, boolean leader) {
		VBox card = new VBox(8);
		card.getStyleClass().add("settings-card");
		Label heading = new Label(leader ? "Leader tools" : "Party settings");
		heading.getStyleClass().add("settings-card-title");
		card.getChildren().add(heading);
		JsonObject sync = party.has("sync") && party.get("sync").isJsonObject() ? party.getAsJsonObject("sync") : null;
		LauncherSettings.Data settings = LauncherSettings.get();
		if (leader) {
			CheckBox share = new CheckBox("Everyone uses my mods");
			share.setSelected(sync != null);
			Label shareHint = new Label(sync != null ? "Sharing \"" + str(sync, "profile") + "\" (" + sync.getAsJsonArray("mods").size()
					+ " mods, " + str(sync, "mcVersion") + ")" : "Members get asked to switch their profile to your mods until the party ends.");
			shareHint.getStyleClass().add("settings-row-hint");
			shareHint.setWrapText(true);
			share.setOnAction(e -> {
				if (!share.isSelected()) {
					act(LauncherSocial.partySync(null), "Stopped sharing your mods");
					return;
				}
				Instance profile = host.currentProfile();
				if (profile == null) {
					share.setSelected(false);
					feedback.setText("Pick a profile on Home first");
					return;
				}
				share.setDisable(true);
				feedback.setText("Preparing your mod list...");
				CompletableFuture.supplyAsync(() -> {
					try {
						return PartyModSync.buildSync(profile, text -> Platform.runLater(() -> feedback.setText(text)));
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				}).whenComplete((payload, error) -> Platform.runLater(() -> {
					share.setDisable(false);
					if (error != null) {
						share.setSelected(false);
						feedback.setText("Couldn't read your mods: " + error.getMessage());
					} else {
						act(LauncherSocial.partySync(payload), "Shared \"" + profile.name() + "\" with the party");
					}
				}));
			});
			CheckBox together = new CheckBox("Launch together - when I press Play, everyone launches");
			together.setSelected(settings.partyLaunchTogether);
			together.setOnAction(e -> {
				settings.partyLaunchTogether = together.isSelected();
				LauncherSettings.save(settings);
			});
			card.getChildren().addAll(share, shareHint, together);
		} else {
			Label syncInfo = new Label(sync != null ? "The leader shares \"" + str(sync, "profile") + "\" - your launcher asks before switching your mods."
					: "The leader isn't sharing their mods.");
			syncInfo.getStyleClass().add("settings-row-hint");
			syncInfo.setWrapText(true);
			card.getChildren().add(syncInfo);
			Instance profile = host.currentProfile();
			if (profile != null && PartyModSync.isSynced(profile)) {
				Button restore = new Button("Restore my own mods");
				restore.getStyleClass().add("glass-button");
				restore.setOnAction(e -> {
					restore.setDisable(true);
					CompletableFuture.runAsync(() -> {
						try {
							PartyModSync.restore(profile, text -> Platform.runLater(() -> feedback.setText(text)));
							Platform.runLater(() -> {
								feedback.setText("Your mods are back");
								rebuild();
							});
						} catch (Exception ex) {
							Platform.runLater(() -> feedback.setText("Restore failed: " + ex.getMessage()));
						}
					});
				});
				card.getChildren().add(restore);
			}
			CheckBox follow = new CheckBox("Launch when the leader launches");
			follow.setSelected(settings.partyFollowLaunches);
			follow.setOnAction(e -> {
				settings.partyFollowLaunches = follow.isSelected();
				LauncherSettings.save(settings);
			});
			card.getChildren().add(follow);
		}
		return card;
	}

	private Node chatColumn(JsonObject party) {
		VBox column = new VBox(8);
		Label heading = new Label("Party chat");
		heading.getStyleClass().add("settings-card-title");
		chatList = new VBox(6);
		chatList.setPadding(new Insets(6));
		if (party.has("chat")) {
			for (JsonElement element : party.getAsJsonArray("chat")) {
				chatList.getChildren().add(chatLine(element.getAsJsonObject()));
			}
		}
		chatScroll = new ScrollPane(chatList);
		chatScroll.setFitToWidth(true);
		chatScroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(chatScroll, Priority.ALWAYS);
		chatScroll.vvalueProperty().set(1.0);
		Platform.runLater(() -> chatScroll.setVvalue(1.0));
		TextField input = new TextField();
		input.setPromptText("Message your party...");
		HBox.setHgrow(input, Priority.ALWAYS);
		Button send = new Button("Send");
		send.getStyleClass().add("primary-button");
		Runnable sendNow = () -> {
			String text = input.getText().strip();
			if (text.isEmpty()) {
				return;
			}
			input.clear();
			LauncherSocial.partyChat(text).thenAccept(error -> {
				if (error != null) {
					Platform.runLater(() -> feedback.setText(error));
				}
			});
		};
		send.setOnAction(e -> sendNow.run());
		input.setOnAction(e -> sendNow.run());
		HBox inputRow = new HBox(8, input, send);
		column.getChildren().addAll(heading, chatScroll, inputRow);
		column.getStyleClass().add("settings-card");
		return column;
	}

	private Node chatLine(JsonObject line) {
		String kind = str(line, "kind");
		String text = str(line, "text");
		String when = TIME.format(Instant.ofEpochMilli(line.get("time").getAsLong()));
		if ("system".equals(kind)) {
			Label system = new Label(when + "  " + text);
			system.setStyle("-fx-text-fill: #9aa0b0; -fx-font-style: italic;");
			system.setWrapText(true);
			return system;
		}
		String name = str(line, "name");
		Label who = new Label(name);
		who.setStyle("-fx-font-weight: bold;");
		Label body = new Label(text);
		body.setWrapText(true);
		if ("waypoint".equals(kind)) {
			body.setStyle("-fx-text-fill: #8fb8ff;");
		}
		Label time = new Label(when);
		time.getStyleClass().add("settings-row-hint");
		VBox textBox = new VBox(1, new HBox(6, who, time), body);
		HBox row = new HBox(8, Heads.head(str(line, "from"), name, 22), textBox);
		row.setAlignment(Pos.TOP_LEFT);
		return row;
	}

	private void act(CompletableFuture<String> future, String success) {
		feedback.setText("...");
		future.thenAccept(error -> Platform.runLater(() -> {
			feedback.setText(error == null ? success : error);
			rebuild();
		}));
	}

	private static Label hint(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("settings-row-hint");
		label.setWrapText(true);
		return label;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static int num(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsInt() : 0;
	}
}
