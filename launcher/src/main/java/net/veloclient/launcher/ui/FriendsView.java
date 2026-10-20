package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.social.LauncherSocial;
import net.veloclient.launcher.social.SkinHeads;
import net.veloclient.launcher.theme.LauncherTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The launcher's Friends tab - same friends, requests, blocks and messages as the in-game Friends
 * menu (both talk to the Velo server): add friends by username or UUID, see who's online and where
 * they're playing, chat, join a friend's server in one click, and hide your own status.
 */
public final class FriendsView {

	public interface Host {
		LauncherTheme theme();

		MinecraftSession session();

		void signIn();

		/** Launches the most recently used profile straight into {@code address}. */
		void joinServer(String address, Button trigger, ProgressBar progress, Label status);

		/** Called when unread counts change, so the sidebar badge can update. */
		void badgeChanged();
	}

	private enum Mode { CHAT, REQUESTS, BLOCKED }

	private final Host host;
	private final StackPane root = new StackPane();
	private Mode mode = Mode.CHAT;
	private String selected;
	private String draft = "";
	private String addDraft = "";
	private boolean addFocused;
	private String feedback = "";
	private boolean feedbackError;
	private final java.util.Set<String> loaded = new java.util.HashSet<>();
	private final Consumer<LauncherSocial.Event> listener = this::onEvent;

	private FriendsView(Host host) {
		this.host = host;
	}

	public static Node build(Host host) {
		FriendsView view = new FriendsView(host);
		LauncherSocial.addListener(view.listener);
		// Stop listening once the tab is navigated away from (the node leaves the scene).
		view.root.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene == null) {
				LauncherSocial.removeListener(view.listener);
			}
		});
		view.rebuild();
		return view.root;
	}

	private void onEvent(LauncherSocial.Event event) {
		if (event.type().equals("message") && event.message() != null && selected != null
				&& (selected.equals(event.message().from) || selected.equals(event.message().to))) {
			LauncherSocial.markRead(selected);
		}
		host.badgeChanged();
		rebuild();
	}

	private void rebuild() {
		if (root.getScene() != null && root.getScene().getFocusOwner() instanceof TextField focused) {
			addFocused = "add-friend".equals(focused.getId());
		}
		LauncherTheme theme = host.theme();
		if (host.session() == null && !LauncherSocial.isDemo()) {
			root.getChildren().setAll(centered("Sign in to use Friends",
					"Friends, messages and \"join friend\" use your Minecraft account.", "Sign In", host::signIn));
			return;
		}
		LauncherSocial.State state = LauncherSocial.snapshot();
		if (state == null) {
			root.getChildren().setAll(centered("Connecting to Velo Network...", LauncherSocial.status(), null, null));
			return;
		}
		Label heading = new Label("Friends");
		heading.getStyleClass().add("t-title");
		long onlineCount = state.friends.stream().filter(f -> f.online).count();
		Label sub = new Label(onlineCount + " online  ·  " + state.friends.size() + (state.friends.size() == 1 ? " friend" : " friends")
				+ (state.incoming.isEmpty() ? "" : "  ·  " + state.incoming.size() + (state.incoming.size() == 1 ? " request" : " requests")));
		sub.getStyleClass().add("t-muted");
		HBox titleRow = new HBox(14, heading, sub);
		titleRow.setAlignment(Pos.BASELINE_LEFT);

		HBox body = new HBox(16, buildLeft(state), buildRight(state));
		VBox.setVgrow(body, Priority.ALWAYS);
		VBox wrapper = new VBox(16, titleRow, body);
		root.getChildren().setAll(wrapper);
	}

	// ---- Left: me, add, list ----

	private Node buildLeft(LauncherSocial.State state) {
		LauncherTheme theme = host.theme();
		VBox left = new VBox(12);
		left.setPrefWidth(310);
		left.setMinWidth(290);
		left.getStyleClass().add("card");
		left.setPadding(new Insets(16));

		// Me.
		boolean invisible = state.me != null && state.me.invisible;
		HBox me = new HBox(10);
		me.setAlignment(Pos.CENTER_LEFT);
		Node myHead = head(state.me != null ? state.me.uuid : null, state.me != null ? state.me.username : "?", 34);
		Label myName = new Label(state.me != null ? state.me.username : "You");
		myName.getStyleClass().add("t-strong");
		Label myStatus = new Label(invisible ? "Appearing offline" : "Online");
		myStatus.getStyleClass().add("t-muted");
		myStatus.setGraphic(dot(invisible ? Color.web("#7a7a80") : Color.web("#43d17a")));
		VBox meText = new VBox(2, myName, myStatus);
		HBox.setHgrow(meText, Priority.ALWAYS);
		Button visibility = new Button(invisible ? "Go online" : "Hide me", Icons.icon(invisible ? "eye" : "lock", 13, Color.web("#E4E4E8")));
		visibility.getStyleClass().addAll("ghost-button", "button-sm");
		visibility.setMinWidth(Region.USE_PREF_SIZE);
		visibility.setTooltip(new Tooltip(invisible ? "Let friends see you're online and where you play"
				: "Appear offline to all friends - stays that way until you switch back, also in game"));
		visibility.setOnAction(e -> act(LauncherSocial.setInvisible(!invisible),
				invisible ? "Friends can see you again" : "You now appear offline"));
		me.getChildren().addAll(myHead, meText, visibility);

		// Add friend.
		TextField add = new TextField(addDraft);
		add.setId("add-friend");
		add.setPromptText("Add by username");
		add.textProperty().addListener((obs, o, n) -> addDraft = n);
		if (addFocused) {
			Platform.runLater(() -> {
				add.requestFocus();
				add.positionCaret(add.getText().length());
			});
		}
		HBox.setHgrow(add, Priority.ALWAYS);
		Button addButton = new Button("Add", Icons.icon("user-plus", 14, Color.WHITE));
		addButton.getStyleClass().add("primary-button");
		addButton.setMinWidth(Region.USE_PREF_SIZE);
		Runnable submit = () -> {
			String target = add.getText().strip();
			if (target.isEmpty()) {
				return;
			}
			LauncherSocial.sendRequest(target).thenAccept(error -> Platform.runLater(() -> {
				if (error == null) {
					addDraft = "";
				}
				feedback = error == null ? "Friend request sent to " + target : error;
				feedbackError = error != null;
				rebuild();
			}));
		};
		addButton.setOnAction(e -> submit.run());
		add.setOnAction(e -> submit.run());
		HBox addRow = new HBox(8, add, addButton);
		Region meLine = new Region();
		meLine.getStyleClass().add("hairline-h");
		left.getChildren().addAll(me, meLine, addRow);

		if (!feedback.isEmpty()) {
			Label info = new Label(feedback);
			info.setWrapText(true);
			info.setTextFill(feedbackError ? Color.web("#ff7070") : Color.web("#6fe39a"));
			info.setFont(Font.font(12));
			left.getChildren().add(info);
		}

		VBox list = new VBox(2);
		if (state.friends.isEmpty()) {
			Label empty = new Label("No friends yet. Add one above - they get the request in the launcher or in game.");
			empty.setWrapText(true);
			empty.getStyleClass().add("t-muted");
			list.getChildren().add(empty);
		}
		// Online first, then offline - each under its own small header.
		List<LauncherSocial.Friend> onlineFriends = state.friends.stream().filter(f -> f.online).toList();
		List<LauncherSocial.Friend> offlineFriends = state.friends.stream().filter(f -> !f.online).toList();
		for (Object[] group : new Object[][] {{"ONLINE", onlineFriends}, {"OFFLINE", offlineFriends}}) {
			@SuppressWarnings("unchecked")
			List<LauncherSocial.Friend> friends = (List<LauncherSocial.Friend>) group[1];
			if (friends.isEmpty()) {
				continue;
			}
			Label groupHeader = new Label(group[0] + "  " + friends.size());
			groupHeader.getStyleClass().add("field-label");
			groupHeader.setPadding(new Insets(list.getChildren().isEmpty() ? 4 : 14, 0, 6, 4));
			list.getChildren().add(groupHeader);
			for (LauncherSocial.Friend friend : friends) {
				list.getChildren().add(friendRow(friend));
			}
		}
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		left.getChildren().add(scroll);
		return left;
	}

	private Node friendRow(LauncherSocial.Friend friend) {
		LauncherTheme theme = host.theme();
		HBox row = new HBox(10);
		row.getStyleClass().add("friend-row");
		if (mode == Mode.CHAT && friend.uuid.equals(selected)) {
			row.getStyleClass().add("friend-row-selected");
		}
		row.setAlignment(Pos.CENTER_LEFT);
		StackPane headWithDot = new StackPane(head(friend.uuid, friend.username, 34));
		Circle dot = new Circle(4.5, statusColor(friend));
		dot.setStyle("-fx-stroke: -velo-surface;");
		dot.setStrokeWidth(2);
		StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
		headWithDot.getChildren().add(dot);
		Label name = new Label(friend.username);
		name.getStyleClass().add("t-strong");
		name.setOpacity(friend.online ? 1 : 0.7);
		Label status = new Label(describe(friend));
		status.getStyleClass().add(friend.online && friend.activity != null ? "t-green" : "t-muted");
		status.setStyle("-fx-font-size: 12px;");
		status.setWrapText(true);
		name.setWrapText(true);
		VBox textBox = new VBox(1, name, status);
		HBox.setHgrow(textBox, Priority.ALWAYS);
		row.getChildren().addAll(headWithDot, textBox);
		if (!inMyParty(friend.uuid)) {
			// Icon + short word, never squeezed: a cut-off "+..." told nobody anything.
			Button invite = new Button("Party", Icons.icon("user-plus", 13, Color.web("#E4E4E8")));
			invite.getStyleClass().addAll("ghost-button", "button-sm");
			invite.setMinWidth(Region.USE_PREF_SIZE);
			invite.setGraphicTextGap(6);
			invite.setTooltip(new javafx.scene.control.Tooltip("Invite " + friend.username + " to your party"));
			invite.setVisible(false);
			invite.managedProperty().bind(invite.visibleProperty());
			row.hoverProperty().addListener((o, a, hover) -> invite.setVisible(hover));
			invite.setOnAction(e -> {
				e.consume();
				act(LauncherSocial.partyInvite(friend.uuid), "Invited " + friend.username + " to your party");
			});
			row.getChildren().add(invite);
		}
		if (friend.unread > 0) {
			Label badge = new Label(friend.unread > 99 ? "99+" : String.valueOf(friend.unread));
			badge.getStyleClass().add("unread-badge");
			row.getChildren().add(badge);
		}
		row.setOnMouseClicked(e -> {
			mode = Mode.CHAT;
			select(friend.uuid);
		});
		return row;
	}

	private static boolean inMyParty(String uuid) {
		var party = LauncherSocial.party();
		if (party == null) {
			return false;
		}
		for (var member : party.getAsJsonArray("members")) {
			if (uuid.equals(member.getAsJsonObject().get("uuid").getAsString())) {
				return true;
			}
		}
		return false;
	}

	private void select(String uuid) {
		selected = uuid;
		draft = "";
		if (loaded.add(uuid)) {
			LauncherSocial.loadConversation(uuid).thenAccept(error -> Platform.runLater(this::rebuild));
		}
		LauncherSocial.markRead(uuid).thenAccept(error -> Platform.runLater(host::badgeChanged));
		rebuild();
	}

	// ---- Right: chat / requests / blocked ----

	private Node buildRight(LauncherSocial.State state) {
		VBox right = new VBox(14);
		right.getStyleClass().add("card");
		right.setPadding(new Insets(10, 20, 18, 20));
		HBox.setHgrow(right, Priority.ALWAYS);
		// Take exactly the remaining width - otherwise a long wrapped hint (e.g. on the Blocked tab)
		// reports its full one-line width as preferred size and squeezes the friends list.
		right.setMinWidth(0);
		right.setPrefWidth(0);

		HBox segments = new HBox(22,
				segment("Chat", Mode.CHAT),
				segment(state.incoming.isEmpty() ? "Requests" : "Requests (" + state.incoming.size() + ")", Mode.REQUESTS),
				segment("Blocked", Mode.BLOCKED));
		segments.getStyleClass().add("utab-row");
		segments.setAlignment(Pos.BOTTOM_LEFT);
		right.getChildren().add(segments);

		Node content = switch (mode) {
			case CHAT -> buildChat(state);
			case REQUESTS -> buildRequests(state);
			case BLOCKED -> buildBlocked(state);
		};
		VBox.setVgrow(content, Priority.ALWAYS);
		right.getChildren().add(content);
		return right;
	}

	private Button segment(String label, Mode target) {
		Button button = new Button(label);
		button.getStyleClass().setAll("button", "utab", "no-motion");
		button.setMinWidth(Region.USE_PREF_SIZE);
		if (mode == target) {
			button.getStyleClass().add("utab-active");
		}
		button.setOnAction(e -> {
			mode = target;
			rebuild();
		});
		return button;
	}

	private Node buildChat(LauncherSocial.State state) {
		LauncherTheme theme = host.theme();
		LauncherSocial.Friend friend = LauncherSocial.friend(selected);
		if (friend == null) {
			return centered(state.friends.isEmpty() ? "No one to chat with yet" : "Pick a friend to chat",
					state.friends.isEmpty() ? "Add friends on the left by their Minecraft username." : "Messages also pop up in game.", null, null);
		}
		VBox chat = new VBox(10);

		// Header.
		HBox header = new HBox(10);
		header.setAlignment(Pos.CENTER_LEFT);
		Label name = new Label(friend.username);
		name.setFont(Font.font("Inter", FontWeight.BOLD, 16));
		name.setTextFill(text(theme));
		Label where = new Label(describe(friend));
		where.getStyleClass().add("friend-status");
		where.setTextFill(text(theme));
		where.setGraphic(dot(statusColor(friend)));
		VBox who = new VBox(2, name, where);
		HBox.setHgrow(who, Priority.ALWAYS);
		header.getChildren().addAll(head(friend.uuid, friend.username, 40), who);

		ProgressBar progress = new ProgressBar(0);
		progress.setVisible(false);
		progress.setManaged(false);
		progress.setMaxWidth(Double.MAX_VALUE);
		Label launchStatus = new Label();
		launchStatus.getStyleClass().add("version-tag");
		launchStatus.setTextFill(text(theme));
		launchStatus.setVisible(false);
		launchStatus.setManaged(false);

		String joinable = friend.online && friend.activity != null && "server".equals(friend.activity.kind)
				&& friend.activity.detail != null && !friend.activity.detail.isBlank() ? friend.activity.detail : null;
		if (joinable != null) {
			Button join = new Button("Join " + friend.username);
			join.getStyleClass().addAll("button-positive", "button-compact");
			join.setTooltip(new Tooltip("Launch your last profile straight into " + joinable));
			join.setOnAction(e -> host.joinServer(joinable, join, progress, launchStatus));
			header.getChildren().add(join);
		}
		Button unfriend = confirmButton("Unfriend", () -> act(LauncherSocial.unfriend(friend.uuid), "Removed " + friend.username));
		Button block = confirmButton("Block", () -> act(LauncherSocial.block(friend.uuid), "Blocked " + friend.username));
		Button reportButton = new Button("Report");
		reportButton.getStyleClass().addAll("danger-ghost-button", "button-compact");
		reportButton.setOnAction(e -> ReportDialog.show(reportButton.getScene().getWindow(), friend.uuid, friend.username, "dm"));
		header.getChildren().addAll(unfriend, block, reportButton);
		chat.getChildren().addAll(header, progress, launchStatus);

		// Messages.
		VBox messages = new VBox(6);
		messages.setPadding(new Insets(6, 4, 6, 4));
		List<LauncherSocial.Message> conversation = LauncherSocial.conversation(friend.uuid);
		String me = state.me != null ? state.me.uuid : "";
		if (conversation.isEmpty()) {
			Label hi = new Label("Say hi to " + friend.username + "!");
			hi.getStyleClass().add("section-subtitle");
			hi.setTextFill(text(theme));
			messages.getChildren().add(hi);
		}
		DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());
		for (LauncherSocial.Message message : conversation) {
			boolean mine = message.from.equals(me);
			Node bubble;
			if (message.isWaypoint()) {
				bubble = waypointCard(message);
			} else {
				Label textLabel = new Label(message.text);
				textLabel.setWrapText(true);
				textLabel.setMaxWidth(420);
				textLabel.getStyleClass().add(mine ? "chat-bubble-mine" : "chat-bubble-theirs");
				bubble = textLabel;
			}
			Label stamp = new Label(time.format(Instant.ofEpochMilli(message.time)));
			stamp.getStyleClass().add("chat-time");
			stamp.setTextFill(text(theme));
			VBox item = new VBox(2, bubble, stamp);
			item.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
			HBox line = new HBox(item);
			line.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
			messages.getChildren().add(line);
		}
		ScrollPane scroll = new ScrollPane(messages);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		Platform.runLater(() -> scroll.setVvalue(1.0));

		// Input.
		TextField input = new TextField(draft);
		input.setPromptText("Message " + friend.username + "...");
		input.textProperty().addListener((obs, o, n) -> draft = n);
		HBox.setHgrow(input, Priority.ALWAYS);
		Button send = new Button("Send");
		send.getStyleClass().addAll("title-menu-button-primary", "button-compact");
		Runnable doSend = () -> {
			String textValue = input.getText().strip();
			if (textValue.isEmpty()) {
				return;
			}
			draft = "";
			input.clear();
			LauncherSocial.sendMessage(friend.uuid, textValue).thenAccept(error -> Platform.runLater(() -> {
				if (error != null) {
					draft = textValue;
					feedback = error;
					feedbackError = true;
					rebuild();
				}
			}));
		};
		send.setOnAction(e -> doSend.run());
		input.setOnAction(e -> doSend.run());
		HBox inputRow = new HBox(6, input, send);
		if (!addFocused) {
			Platform.runLater(() -> {
				input.requestFocus();
				input.positionCaret(input.getText().length());
			});
		}

		chat.getChildren().addAll(scroll, inputRow);
		return chat;
	}

	private Node waypointCard(LauncherSocial.Message message) {
		LauncherTheme theme = host.theme();
		var waypoint = message.waypoint;
		Label title = new Label(String.valueOf(waypoint.get("name")));
		title.setFont(Font.font("Inter", FontWeight.BOLD, 13));
		Object color = waypoint.get("color");
		title.setTextFill(color instanceof Number n ? Color.rgb((n.intValue() >> 16) & 0xFF, (n.intValue() >> 8) & 0xFF, n.intValue() & 0xFF) : text(theme));
		Label coords = new Label(String.format(Locale.ROOT, "%d, %d, %d  -  %s", num(waypoint.get("x")), num(waypoint.get("y")),
				num(waypoint.get("z")), waypoint.getOrDefault("worldName", waypoint.getOrDefault("dimension", ""))));
		coords.getStyleClass().add("friend-status");
		coords.setTextFill(text(theme));
		Label hint = new Label("Shared waypoint - add it from the Friends menu in game");
		hint.getStyleClass().add("chat-time");
		hint.setTextFill(text(theme));
		VBox card = new VBox(2, title, coords, hint);
		card.getStyleClass().add("waypoint-card");
		return card;
	}

	private static int num(Object value) {
		return value instanceof Number n ? (int) Math.floor(n.doubleValue()) : 0;
	}

	private Node buildRequests(LauncherSocial.State state) {
		VBox box = new VBox(8);
		box.getChildren().add(sectionLabel("INCOMING"));
		if (state.incoming.isEmpty()) {
			box.getChildren().add(muted("No pending requests."));
		}
		for (LauncherSocial.Request request : state.incoming) {
			Button accept = new Button("Accept");
			accept.getStyleClass().addAll("button-positive", "button-compact");
			accept.setOnAction(e -> act(LauncherSocial.respond(request.uuid, true), "You're now friends with " + request.username));
			Button deny = new Button("Deny");
			deny.getStyleClass().addAll("button-danger", "button-compact");
			deny.setOnAction(e -> act(LauncherSocial.respond(request.uuid, false), "Declined " + request.username));
			box.getChildren().add(personRow(request.uuid, request.username, "Sent " + ago(request.time), accept, deny));
		}
		box.getChildren().add(sectionLabel("SENT"));
		if (state.outgoing.isEmpty()) {
			box.getChildren().add(muted("Nothing waiting on others."));
		}
		for (LauncherSocial.Request request : state.outgoing) {
			Button cancel = new Button("Cancel");
			cancel.getStyleClass().add("button-compact");
			cancel.setOnAction(e -> act(LauncherSocial.cancelRequest(request.uuid), "Cancelled request to " + request.username));
			box.getChildren().add(personRow(request.uuid, request.username, "Waiting - sent " + ago(request.time), cancel));
		}
		ScrollPane scroll = new ScrollPane(box);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		return scroll;
	}

	private Node buildBlocked(LauncherSocial.State state) {
		VBox box = new VBox(8);
		box.getChildren().add(muted("Blocked players can't message you or send friend requests until you unblock them. They're never told."));
		if (state.blocked.isEmpty()) {
			box.getChildren().add(muted("You haven't blocked anyone."));
		}
		for (LauncherSocial.PlayerRef ref : state.blocked) {
			Button unblock = new Button("Unblock");
			unblock.getStyleClass().add("button-compact");
			unblock.setOnAction(e -> act(LauncherSocial.unblock(ref.uuid), "Unblocked " + ref.username));
			box.getChildren().add(personRow(ref.uuid, ref.username, "Blocked", unblock));
		}
		ScrollPane scroll = new ScrollPane(box);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		return scroll;
	}

	private Node personRow(String uuid, String name, String subtitle, Button... actions) {
		LauncherTheme theme = host.theme();
		HBox row = new HBox(10);
		row.getStyleClass().add("card-2");
		row.setPadding(new Insets(10, 14, 10, 12));
		row.setAlignment(Pos.CENTER_LEFT);
		Label nameLabel = new Label(name);
		nameLabel.getStyleClass().add("t-strong");
		Label sub = new Label(subtitle);
		sub.getStyleClass().add("t-muted");
		sub.setWrapText(true);
		for (Button action : actions) {
			action.setMinWidth(Region.USE_PREF_SIZE);
		}
		VBox textBox = new VBox(2, nameLabel, sub);
		HBox.setHgrow(textBox, Priority.ALWAYS);
		row.getChildren().addAll(head(uuid, name, 30), textBox);
		row.getChildren().addAll(actions);
		return row;
	}

	/** Destructive buttons need a second click ("Sure?") within 3 seconds. */
	private Button confirmButton(String label, Runnable action) {
		Button button = new Button(label);
		button.getStyleClass().add("button-compact");
		boolean[] armed = {false};
		button.setOnAction(e -> {
			if (armed[0]) {
				action.run();
				return;
			}
			armed[0] = true;
			button.setText("Sure?");
			button.getStyleClass().add("button-danger");
			javafx.animation.PauseTransition reset = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
			reset.setOnFinished(ev -> {
				armed[0] = false;
				button.setText(label);
				button.getStyleClass().remove("button-danger");
			});
			reset.play();
		});
		return button;
	}

	private void act(java.util.concurrent.CompletableFuture<String> future, String success) {
		future.thenAccept(error -> Platform.runLater(() -> {
			feedback = error == null ? success : error;
			feedbackError = error != null;
			rebuild();
		}));
	}

	// ---- Small builders ----

	private Node head(String uuid, String name, double size) {
		StackPane holder = new StackPane();
		holder.setMinSize(size, size);
		holder.setPrefSize(size, size);
		holder.setMaxSize(size, size);
		Label initial = new Label(name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT));
		initial.setTextFill(Color.WHITE);
		initial.setFont(Font.font("Inter", FontWeight.BOLD, size * 0.45));
		holder.setStyle("-fx-background-color: rgba(255,255,255,0.12); -fx-background-radius: " + (size / 5) + ";");
		holder.getChildren().add(initial);
		if (uuid != null) {
			SkinHeads.skin(uuid).thenAccept(bytes -> {
				if (bytes != null) {
					Platform.runLater(() -> {
						StackPane rendered = PlayerHeadView.build(bytes, size);
						if (rendered != null) {
							holder.setStyle("");
							holder.getChildren().setAll(rendered);
						}
					});
				}
			});
		}
		return holder;
	}

	private static Node dot(Color color) {
		return new Circle(4, color);
	}

	private static Color statusColor(LauncherSocial.Friend friend) {
		if (!friend.online || friend.activity == null) {
			return Color.web("#7a7a80");
		}
		String kind = friend.activity.kind == null ? "" : friend.activity.kind;
		return kind.equals("server") || kind.equals("singleplayer") || kind.equals("realm") ? Color.web("#43d17a") : Color.web("#f2c14e");
	}

	private static String describe(LauncherSocial.Friend friend) {
		if (!friend.online || friend.activity == null) {
			return friend.lastSeen > 0 ? "Offline - last seen " + ago(friend.lastSeen) : "Offline";
		}
		String detail = friend.activity.detail;
		return switch (friend.activity.kind == null ? "" : friend.activity.kind) {
			case "server" -> detail != null ? "Playing on " + detail : "Playing multiplayer";
			case "singleplayer" -> detail != null ? "Singleplayer - " + detail : "Playing singleplayer";
			case "realm" -> detail != null ? "On Realm " + detail : "Playing on a Realm";
			case "launcher" -> "In the launcher";
			default -> "In the menus";
		};
	}

	private static String ago(long epochMillis) {
		long seconds = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
		if (seconds < 60) {
			return "just now";
		}
		if (seconds < 3600) {
			return seconds / 60 + "m ago";
		}
		if (seconds < 86400) {
			return seconds / 3600 + "h ago";
		}
		return seconds / 86400 + "d ago";
	}

	private Label sectionLabel(String textValue) {
		Label label = new Label(textValue);
		label.getStyleClass().add("sidebar-section-title");
		label.setTextFill(text(host.theme()));
		return label;
	}

	private Label muted(String textValue) {
		Label label = new Label(textValue);
		label.setWrapText(true);
		label.getStyleClass().add("t-muted");
		return label;
	}

	private Node centered(String title, String subtitle, String buttonLabel, Runnable action) {
		VBox box = new VBox(10);
		box.setAlignment(Pos.CENTER);
		StackPane badge = new StackPane(Icons.icon("message", 22, Color.web("#C9C9D1")));
		badge.setMinSize(52, 52);
		badge.setMaxSize(52, 52);
		badge.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 16;");
		Label titleLabel = new Label(title);
		titleLabel.getStyleClass().add("t-heading");
		Label sub = muted(subtitle == null ? "" : subtitle);
		sub.setMaxWidth(420);
		sub.setAlignment(Pos.CENTER);
		sub.setStyle("-fx-text-alignment: center;");
		box.getChildren().addAll(badge, titleLabel, sub);
		if (buttonLabel != null) {
			Button button = new Button(buttonLabel);
			button.getStyleClass().add("primary-button");
			button.setOnAction(e -> action.run());
			box.getChildren().add(button);
		}
		Region spacer = new Region();
		VBox.setVgrow(spacer, Priority.ALWAYS);
		return box;
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
