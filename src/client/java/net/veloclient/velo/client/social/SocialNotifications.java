package net.veloclient.velo.client.social;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.gui.FriendsScreen;
import net.veloclient.velo.client.network.SocialClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns {@link SocialClient} events into {@link NotificationOverlay} popups, honoring {@link
 * SocialSettings} (per-type toggles, do-not-disturb, per-friend mute). Repeated messages from the
 * same friend replace that friend's popup instead of stacking ("3 new messages"), and a message
 * for the chat you're currently looking at doesn't pop up at all.
 */
public final class SocialNotifications {

	private static final long MESSAGE_LIFE = 7000;
	private static final long REQUEST_LIFE = 15000;
	private static final long ONLINE_LIFE = 4500;
	private static final Map<String, Integer> UNSEEN_BURST = new HashMap<>();

	private SocialNotifications() {
	}

	public static void register() {
		SocialClient.addListener(event -> MinecraftClient.getInstance().execute(() -> handle(event)));
	}

	private static void handle(SocialClient.Event event) {
		if (SocialSettings.doNotDisturb()
				|| !net.veloclient.velo.module.ModuleRegistry.get("friends").map(m -> m.isEnabled()).orElse(true)) {
			return;
		}
		switch (event.type()) {
			case "message" -> onMessage(event);
			case "friend_request" -> {
				SocialClient.Request request = event.request();
				if (request == null || !SocialSettings.requests()) {
					return;
				}
				NotificationOverlay.replaceKeyed("req:" + request.uuid, new NotificationOverlay.Toast(request.uuid, request.username,
						request.username, "wants to be your friend", REQUEST_LIFE,
						FriendsScreen::openRequests,
						() -> SocialClient.respond(request.uuid, true),
						() -> SocialClient.respond(request.uuid, false)));
				ping();
			}
			case "friend_added" -> {
				SocialClient.Friend friend = event.friend();
				if (friend == null || !SocialSettings.requests()) {
					return;
				}
				NotificationOverlay.replaceKeyed("req:" + friend.uuid, new NotificationOverlay.Toast(friend.uuid, friend.username,
						friend.username, "You're now friends", ONLINE_LIFE, () -> FriendsScreen.openChat(friend.uuid), null, null));
				ping();
			}
			case "friend_online" -> {
				SocialClient.Friend friend = event.friend();
				if (friend == null || !SocialSettings.online() || SocialSettings.isMuted(friend.uuid)) {
					return;
				}
				String address = friend.activity != null && "server".equals(friend.activity.kind) ? friend.activity.detail : null;
				boolean joinable = address != null && !address.isBlank()
						&& !address.equalsIgnoreCase(String.valueOf(net.veloclient.velo.client.util.ClientCompat.currentServerAddress()));
				NotificationOverlay.Toast toast = new NotificationOverlay.Toast(friend.uuid, friend.username,
						friend.username + " is online", FriendStatus.describe(friend), joinable ? REQUEST_LIFE / 2 : ONLINE_LIFE,
						() -> FriendsScreen.openChat(friend.uuid),
						joinable ? () -> net.veloclient.velo.client.util.ClientCompat.joinServer(address, friend.username + "'s server") : null,
						joinable ? () -> FriendsScreen.openChat(friend.uuid) : null);
				NotificationOverlay.replaceKeyed("on:" + friend.uuid, joinable ? toast.labels("Join", "Chat") : toast);
			}
			default -> {
			}
		}
	}

	private static void onMessage(SocialClient.Event event) {
		SocialClient.Message message = event.message();
		SocialClient.State state = SocialClient.snapshot();
		if (message == null || state == null || state.me == null || message.from.equals(state.me.uuid)) {
			return;
		}
		if (SocialSettings.isMuted(message.from) || FriendsScreen.isChatOpen(message.from)) {
			return;
		}
		if (message.isWaypoint() ? !SocialSettings.waypoints() : !SocialSettings.messages()) {
			return;
		}
		String key = "msg:" + message.from;
		int burst = UNSEEN_BURST.merge(key, 1, Integer::sum);
		String body = message.isWaypoint() ? "Shared a waypoint: " + message.waypoint.get("name") : message.text;
		String title = burst > 1 ? event.fromName() + "  (" + burst + " new)" : event.fromName();
		NotificationOverlay.replaceKeyed(key, new NotificationOverlay.Toast(message.from, event.fromName(), title, body, MESSAGE_LIFE,
				() -> FriendsScreen.openChat(message.from), null, null));
		ping();
	}

	/** Called when a chat is opened, so the next popup from that friend starts counting from one again. */
	public static void clearBurst(String uuid) {
		UNSEEN_BURST.remove("msg:" + uuid);
	}

	private static void ping() {
		if (!SocialSettings.sound()) {
			return;
		}
		//? if <26.1 {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(
				net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.7f));
		//?} else {
		/*MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
				net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 1.7f));
		*///?}
	}
}
