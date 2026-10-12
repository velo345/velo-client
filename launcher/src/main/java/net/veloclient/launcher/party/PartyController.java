package net.veloclient.launcher.party;

import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.LauncherSettings;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.social.LauncherSocial;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The party behaviour that isn't tied to the Party page: pops up invites, asks before switching
 * your mods to the leader's (and puts yours back when the party or the sharing ends - also after
 * a launcher restart), and follows the leader's "launch together".
 */
public final class PartyController {

	private final Stage stage;
	private final Supplier<Instance> currentProfile;
	private final BiConsumer<Instance, String> launch;
	private final Set<String> shownInvites = new HashSet<>();
	private final Set<Long> answeredSyncs = new HashSet<>();
	private boolean everLoaded;
	private Runnable onChange = () -> { };

	public PartyController(Stage stage, Supplier<Instance> currentProfile, BiConsumer<Instance, String> launch) {
		this.stage = stage;
		this.currentProfile = currentProfile;
		this.launch = launch;
		LauncherSocial.addPartyListener(this::onEvent);
		LauncherSocial.addListener(event -> {
			if (event.type().equals("state")) {
				check();
			}
		});
	}

	/** Called whenever the party changes (refreshes the Home strip). */
	public void setOnChange(Runnable onChange) {
		this.onChange = onChange;
	}

	private void onEvent(LauncherSocial.PartyEvent event) {
		switch (event.type()) {
			case "party_invite" -> showInvite(event.data());
			case "party_launch" -> followLaunch(event.data());
			default -> {
			}
		}
		check();
		onChange.run();
	}

	/** Reconciles the mod sync with the current party state. */
	private void check() {
		if (LauncherSocial.sessionToken() == null) {
			return;
		}
		everLoaded = true;
		JsonObject party = LauncherSocial.party();
		JsonObject sync = party != null && party.has("sync") && party.get("sync").isJsonObject() ? party.getAsJsonObject("sync") : null;
		boolean leader = LauncherSocial.isPartyLeader();
		// Synced profiles whose sync is over: party gone, sharing stopped, or we lead now.
		for (Instance synced : PartyModSync.syncedInstances()) {
			if (sync == null || leader) {
				restore(synced, party == null ? "You left the party" : "The leader stopped sharing their mods");
			}
		}
		for (JsonObject invite : LauncherSocial.partyInvites()) {
			showInvite(invite);
		}
		if (sync != null && !leader) {
			long setAt = sync.has("setAt") ? sync.get("setAt").getAsLong() : 0;
			Instance profile = currentProfile.get();
			if (profile != null && PartyModSync.appliedSyncTime(profile) != setAt && answeredSyncs.add(setAt)) {
				askToSync(profile, sync, leaderName(party));
			}
		}
	}

	private static String leaderName(JsonObject party) {
		String leader = party.get("leader").getAsString();
		for (var element : party.getAsJsonArray("members")) {
			JsonObject member = element.getAsJsonObject();
			if (leader.equals(member.get("uuid").getAsString())) {
				return member.get("name").getAsString();
			}
		}
		return "The leader";
	}

	private void showInvite(JsonObject invite) {
		String id = invite.get("party").getAsString() + "|" + invite.get("from").getAsString();
		if (!shownInvites.add(id)) {
			return;
		}
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.initOwner(stage);
		dialog.setTitle("Party invite");
		String from = invite.get("fromName").getAsString();
		javafx.scene.control.Label title = new javafx.scene.control.Label(from + " invited you to a party");
		title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
		javafx.scene.control.Label sub = new javafx.scene.control.Label(invite.get("leaderName").getAsString() + "'s party \u00B7 "
				+ invite.get("members").getAsInt() + " in it\nChat together, play with the leader's mods and launch at the same time.");
		sub.setWrapText(true);
		sub.setStyle("-fx-opacity: 0.75;");
		javafx.scene.layout.HBox content = new javafx.scene.layout.HBox(16,
				net.veloclient.launcher.ui.Heads.head(invite.get("from").getAsString(), from, 56), new VBox(6, title, sub));
		content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
		content.setPrefWidth(440);
		dialog.getDialogPane().setContent(content);
		ButtonType join = new ButtonType("Join party", ButtonBar.ButtonData.OK_DONE);
		ButtonType later = new ButtonType("Decline", ButtonBar.ButtonData.CANCEL_CLOSE);
		dialog.getDialogPane().getButtonTypes().addAll(join, later);
		net.veloclient.launcher.ui.DialogStyling.apply(dialog);
		dialog.showAndWait().ifPresent(choice -> LauncherSocial.partyRespond(invite.get("party").getAsString(), choice == join));
	}

	private void askToSync(Instance profile, JsonObject sync, String leaderName) {
		int mods = sync.has("mods") ? sync.getAsJsonArray("mods").size() : 0;
		String version = sync.has("mcVersion") ? sync.get("mcVersion").getAsString() : profile.mcVersion();
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.initOwner(stage);
		dialog.setTitle("Use the leader's mods?");
		dialog.setHeaderText(leaderName + " wants everyone on their mods");
		Label text = new Label("Switch \"" + profile.name() + "\" to " + leaderName + "'s " + mods + " mods (Minecraft " + version + ") for this party?"
				+ "\n\nYour own mods are kept safe and come back automatically when the party ends or the leader stops sharing.");
		text.setWrapText(true);
		text.setMaxWidth(440);
		ProgressBar progress = new ProgressBar();
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.setVisible(false);
		Label status = new Label();
		status.setWrapText(true);
		dialog.getDialogPane().setContent(new VBox(10, text, progress, status));
		ButtonType yes = new ButtonType("Switch my mods", ButtonBar.ButtonData.OK_DONE);
		ButtonType no = new ButtonType("Keep mine", ButtonBar.ButtonData.CANCEL_CLOSE);
		dialog.getDialogPane().getButtonTypes().addAll(yes, no);
		net.veloclient.launcher.ui.DialogStyling.apply(dialog);
		var yesButton = dialog.getDialogPane().lookupButton(yes);
		var noButton = dialog.getDialogPane().lookupButton(no);
		yesButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
			event.consume();
			yesButton.setDisable(true);
			noButton.setDisable(true);
			progress.setVisible(true);
			CompletableFuture.supplyAsync(() -> {
				try {
					return PartyModSync.apply(profile, sync, leaderName, s -> Platform.runLater(() -> status.setText(s)));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}).whenComplete((applied, error) -> Platform.runLater(() -> {
				progress.setVisible(false);
				if (error != null) {
					LauncherLog.warn("Party sync failed", error);
					status.setText("Couldn't switch: " + error.getMessage());
					noButton.setDisable(false);
					return;
				}
				status.setText(applied.missing().isEmpty() ? "Done - " + applied.installed() + " mods installed."
						: "Installed " + applied.installed() + " mods. Not on Modrinth (ask " + leaderName + " for these): "
								+ String.join(", ", applied.missing()));
				noButton.setDisable(false);
				((javafx.scene.control.Button) noButton).setText("Close");
				onChange.run();
			}));
		});
		dialog.show();
	}

	private void restore(Instance instance, String reason) {
		CompletableFuture.runAsync(() -> {
			try {
				PartyModSync.restore(instance, s -> { });
				LauncherLog.info("Party sync ended (" + reason + ") - restored mods of " + instance.name());
				Platform.runLater(onChange);
			} catch (Exception e) {
				LauncherLog.warn("Couldn't restore party-synced mods of " + instance.name(), e);
			}
		});
	}

	/** At startup: profiles left synced from a party that's gone by now get their mods back once the party state is known. */
	public void restoreStale() {
		if (!everLoaded) {
			return;
		}
		check();
	}

	private void followLaunch(JsonObject data) {
		if (!LauncherSettings.get().partyFollowLaunches) {
			return;
		}
		Instance profile = currentProfile.get();
		if (profile == null) {
			return;
		}
		String leader = data.has("leaderName") ? data.get("leaderName").getAsString() : "The leader";
		String server = data.has("server") ? data.get("server").getAsString() : "";
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.initOwner(stage);
		dialog.setTitle("Launch together");
		dialog.setHeaderText(leader + " is launching" + (server.isBlank() ? "" : " - joining " + server));
		Label countdown = new Label();
		dialog.getDialogPane().setContent(countdown);
		ButtonType go = new ButtonType("Launch now", ButtonBar.ButtonData.OK_DONE);
		ButtonType skip = new ButtonType("Not this time", ButtonBar.ButtonData.CANCEL_CLOSE);
		dialog.getDialogPane().getButtonTypes().addAll(go, skip);
		net.veloclient.launcher.ui.DialogStyling.apply(dialog);
		int[] seconds = {8};
		countdown.setText("Launching \"" + profile.name() + "\" in " + seconds[0] + "s...");
		Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
			seconds[0]--;
			countdown.setText("Launching \"" + profile.name() + "\" in " + seconds[0] + "s...");
			if (seconds[0] <= 0) {
				dialog.setResult(go);
				dialog.close();
			}
		}));
		timer.setCycleCount(8);
		timer.play();
		dialog.showAndWait().ifPresent(choice -> {
			timer.stop();
			if (choice == go) {
				Instance fresh = InstanceStore.loadAll().stream().filter(i -> i.id().equals(profile.id())).findFirst().orElse(profile);
				launch.accept(fresh, server.isBlank() ? null : server);
			}
		});
		timer.stop();
	}
}
