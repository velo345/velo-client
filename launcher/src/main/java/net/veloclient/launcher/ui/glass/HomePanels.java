package net.veloclient.launcher.ui.glass;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import net.veloclient.launcher.instance.RunningInstanceManager;
import net.veloclient.launcher.instance.RunningInstanceManager.RunningInstance;
import net.veloclient.launcher.social.LauncherSocial;
import net.veloclient.launcher.ui.FriendsView;
import net.veloclient.launcher.ui.Heads;
import net.veloclient.launcher.ui.Icons;
import net.veloclient.launcher.ui.RenderGate;

import java.util.List;
import java.util.function.Consumer;

/** Home's right column cards in the glass launcher (2026-11-25): running games and friends online. */
public final class HomePanels {

	private HomePanels() {
	}

	/** A frosted card with a title row (title left, a small mono note right). */
	public static VBox card(String title, Label note) {
		Label heading = new Label(title);
		heading.getStyleClass().add("home-card-title");
		Region push = new Region();
		HBox.setHgrow(push, Priority.ALWAYS);
		HBox top = new HBox(heading, push);
		top.setAlignment(Pos.CENTER_LEFT);
		if (note != null) {
			note.getStyleClass().add("home-card-note");
			top.getChildren().add(note);
		}
		top.setPadding(new Insets(0, 2, 0, 2));
		VBox card = new VBox(10, top);
		card.getStyleClass().add("home-card");
		card.setPadding(new Insets(14));
		Glass.install(card, 22);
		return card;
	}

	public interface RunningHost {
		/** Brings the game's window to the front (or shows its log page when that isn't possible). */
		void show(RunningInstance running);

		void logs(RunningInstance running);

		void relaunch(RunningInstance running);
	}

	/**
	 * Running games: click a card to bring that game to the front; Logs, Relaunch and Stop as small icon buttons. A launch
	 * that is still preparing shows as "starting" with the Play button's progress.
	 */
	public static VBox running(RunningHost host, String startingName, ProgressBar startingProgress, javafx.beans.property.BooleanProperty preparing) {
		Label count = new Label();
		VBox card = card("Running", count);
		VBox rows = new VBox(8);
		card.getChildren().add(rows);
		Runnable rebuild = () -> {
			List<RunningInstance> list = List.copyOf(RunningInstanceManager.running());
			rows.getChildren().clear();
			boolean starting = startingName != null && startingProgress != null && preparing != null && preparing.get();
			int total = list.size() + (starting ? 1 : 0);
			count.setText(total == 0 ? "" : total == 1 ? "1 RUNNING" : total + " RUNNING");
			count.setStyle(total == 0 ? "" : "-fx-text-fill: #4cd07d;");
			for (RunningInstance ri : list) {
				rows.getChildren().add(runningRow(ri, host));
			}
			if (starting) {
				rows.getChildren().add(startingRow(startingName, startingProgress));
			}
			if (total == 0) {
				Label none = new Label("Nothing running. Press Play and your games show up here.");
				none.getStyleClass().add("home-card-empty");
				none.setWrapText(true);
				rows.getChildren().add(none);
			}
		};
		rebuild.run();
		ListChangeListener<RunningInstance> watch = c -> javafx.application.Platform.runLater(rebuild);
		RunningInstanceManager.running().addListener(new javafx.collections.WeakListChangeListener<>(watch));
		card.getProperties().put("velo.watch", watch);
		if (preparing != null) {
			javafx.beans.value.ChangeListener<Boolean> progressWatch = (o, a, b) -> rebuild.run();
			preparing.addListener(new javafx.beans.value.WeakChangeListener<>(progressWatch));
			card.getProperties().put("velo.progressWatch", progressWatch);
		}
		// play times tick once a second while Home is on screen
		Timeline tick = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
			for (Node row : rows.getChildren()) {
				if (row.getUserData() instanceof Runnable update) {
					update.run();
				}
			}
		}));
		tick.setCycleCount(Animation.INDEFINITE);
		RenderGate.gate(tick);
		card.sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				tick.stop();
			} else {
				tick.play();
			}
		});
		return card;
	}

	private static Node runningRow(RunningInstance ri, RunningHost host) {
		Region dot = new Region();
		dot.getStyleClass().add("run-dot");
		Label name = new Label(ri.instance().name());
		name.getStyleClass().add("run-name");
		Label sub = new Label();
		sub.getStyleClass().add("run-sub");
		VBox text = new VBox(1, name, sub);
		text.setMinWidth(0);
		HBox.setHgrow(text, Priority.ALWAYS);
		Button logs = iconButton("terminal", "Logs", false, () -> host.logs(ri));
		Button relaunch = iconButton("refresh", "Relaunch", false, () -> host.relaunch(ri));
		Button stop = iconButton("stop", "Stop", true, null);
		stop.setOnAction(e -> {
			e.consume();
			if (ri.wasStopped()) {
				ri.kill();
			} else {
				ri.stop();
				stop.setTooltip(new Tooltip("Still running? Click again to end it right away"));
			}
		});
		HBox buttons = new HBox(4, logs, relaunch, stop);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		HBox row = new HBox(9, dot, text, buttons);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("run-row");
		row.setOnMouseClicked(e -> host.show(ri));
		Tooltip.install(row, new Tooltip("Show the game"));
		String where = ri.serverAddress() != null ? ri.serverAddress() : ri.instance().mcVersion();
		Runnable update = () -> {
			long s = Math.max(0, (System.currentTimeMillis() - ri.startedAtEpochMillis()) / 1000);
			String time = s >= 3600 ? (s / 3600) + " h " + (s % 3600) / 60 + " min" : s >= 60 ? (s / 60) + " min" : s + " s";
			sub.setText(where + " · " + time);
		};
		update.run();
		row.setUserData(update);
		return row;
	}

	private static Node startingRow(String name, ProgressBar progress) {
		Region dot = new Region();
		dot.getStyleClass().addAll("run-dot", "run-dot-starting");
		Label title = new Label(name);
		title.getStyleClass().add("run-name");
		Label sub = new Label("Starting");
		sub.getStyleClass().add("run-sub");
		VBox text = new VBox(1, title, sub);
		HBox top = new HBox(9, dot, text);
		top.setAlignment(Pos.CENTER_LEFT);
		Region track = new Region();
		track.getStyleClass().add("run-track");
		Region fill = new Region();
		fill.getStyleClass().add("run-fill");
		StackPane bar = new StackPane(track, fill);
		StackPane.setAlignment(fill, Pos.CENTER_LEFT);
		bar.setMinHeight(4);
		bar.setMaxHeight(4);
		fill.maxWidthProperty().bind(bar.widthProperty().multiply(progress.progressProperty()));
		fill.prefWidthProperty().bind(fill.maxWidthProperty());
		VBox row = new VBox(8, top, bar);
		row.getStyleClass().addAll("run-row", "run-row-starting");
		return row;
	}

	private static Button iconButton(String icon, String tip, boolean danger, Runnable action) {
		Button b = new Button();
		b.setGraphic(Icons.icon(icon, icon.equals("stop") ? 11 : 15, danger ? Color.web("#ffb3b6") : Color.WHITE, 2.1));
		b.getStyleClass().setAll("button", "run-icon", danger ? "run-icon-danger" : "run-icon-plain");
		b.setTooltip(new Tooltip(tip));
		if (action != null) {
			b.setOnAction(e -> {
				e.consume();
				action.run();
			});
		}
		// a click on a button must not also "show the game"
		b.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.scene.input.MouseEvent::consume);
		return b;
	}

	/** Friends online: head, name, what they're playing, Join when they're on a server. */
	private static boolean inParty(String uuid) {
		var party = LauncherSocial.party();
		if (party == null) {
			return false;
		}
		for (String key : new String[] {"members", "invited"}) {
			if (party.has(key)) {
				for (var e : party.getAsJsonArray(key)) {
					if (uuid.equals(e.getAsJsonObject().get("uuid").getAsString())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static VBox friends(Consumer<String> join, Runnable openFriends) {
		Label count = new Label();
		VBox card = card("Friends", count);
		LauncherSocial.State state = LauncherSocial.snapshot();
		List<LauncherSocial.Friend> online = state == null || state.friends == null ? List.of()
				: state.friends.stream().filter(f -> f.online).limit(4).toList();
		long total = state == null || state.friends == null ? 0 : state.friends.stream().filter(f -> f.online).count();
		// (no "3 ONLINE" count in the header - the list shows who's on)
		count.setText("");
		for (LauncherSocial.Friend friend : online) {
			Node head = Heads.head(friend.uuid, friend.username, 28);
			Label name = new Label(friend.username);
			name.getStyleClass().add("run-name");
			Label what = new Label(FriendsView.describe(friend));
			what.getStyleClass().add("run-sub");
			VBox text = new VBox(1, name, what);
			text.setMinWidth(0);
			HBox.setHgrow(text, Priority.ALWAYS);
			HBox row = new HBox(10, head, text);
			row.setAlignment(Pos.CENTER_LEFT);
			row.getStyleClass().add("friend-mini");
			String address = friend.activity != null && "server".equals(friend.activity.kind) && friend.activity.detail != null
					&& !friend.activity.detail.isBlank() ? friend.activity.detail : null;
			if (address != null) {
				Button joinButton = new Button("Join");
				joinButton.getStyleClass().setAll("button", "mini-join");
				// full width always - the name and activity get shortened instead ("J..." / "...")
				joinButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
				joinButton.setOnAction(e -> join.accept(address));
				row.getChildren().add(joinButton);
			}
			if (!inParty(friend.uuid)) {
				// party invite right here (the server starts the party when there's none yet)
				Button invite = new Button();
				invite.setGraphic(Icons.icon("user-plus", 14, javafx.scene.paint.Color.WHITE, 2.2));
				invite.getStyleClass().setAll("button", "mini-invite");
				invite.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
				invite.setTooltip(new javafx.scene.control.Tooltip("Invite " + friend.username + " to your party"));
				invite.setOnAction(e -> {
					invite.setDisable(true);
					invite.setGraphic(Icons.icon("check", 14, javafx.scene.paint.Color.web("#8de8ac"), 2.4));
					LauncherSocial.partyInvite(friend.uuid).thenAccept(error -> javafx.application.Platform.runLater(() -> {
						if (error != null) {
							invite.setDisable(false);
							invite.setGraphic(Icons.icon("user-plus", 14, javafx.scene.paint.Color.WHITE, 2.2));
							invite.setTooltip(new javafx.scene.control.Tooltip(error));
						}
					}));
				});
				row.getChildren().add(invite);
			}
			card.getChildren().add(row);
		}
		if (online.isEmpty()) {
			Label none = new Label(state == null ? "Sign in to see your friends." : "No friends online right now.");
			none.getStyleClass().add("home-card-empty");
			card.getChildren().add(none);
		}
		card.setOnMouseClicked(e -> {
			if (e.getTarget() instanceof Node n && !(n instanceof Button) && !(n.getParent() instanceof Button)) {
				openFriends.run();
			}
		});
		card.setStyle("-fx-cursor: hand;");
		return card;
	}
}
