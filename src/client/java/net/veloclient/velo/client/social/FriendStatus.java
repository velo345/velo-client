package net.veloclient.velo.client.social;

import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.network.SocialClient;

/** Turns a friend's presence into the short status line and dot color shown everywhere friends appear. */
public final class FriendStatus {

	public static final int ONLINE_COLOR = 0xFF43D17A;
	public static final int IDLE_COLOR = 0xFFF2C14E;
	public static final int OFFLINE_COLOR = 0xFF7A7A80;

	private FriendStatus() {
	}

	public static String describe(SocialClient.Friend friend) {
		if (friend == null) {
			return "";
		}
		if (!friend.online || friend.activity == null) {
			return friend.lastSeen > 0 ? "Offline - last seen " + VeloUi.ago(friend.lastSeen) : "Offline";
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

	public static int dotColor(SocialClient.Friend friend) {
		if (friend == null || !friend.online || friend.activity == null) {
			return OFFLINE_COLOR;
		}
		String kind = friend.activity.kind == null ? "" : friend.activity.kind;
		return kind.equals("server") || kind.equals("singleplayer") || kind.equals("realm") ? ONLINE_COLOR : IDLE_COLOR;
	}
}
