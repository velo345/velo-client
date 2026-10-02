package net.veloclient.velo.client.social;

import net.veloclient.velo.config.ConfigManager;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which friend popups to show - edited from the Friends menu's Notifications tab (and per friend
 * via "Mute popups"), saved to {@code ~/.velo-client/config/social-notifications.json}.
 * "Do not disturb" silences every popup at once without losing the individual choices.
 */
public final class SocialSettings {

	private static final String CONFIG_ID = "social-notifications";

	private static final class Data {
		boolean doNotDisturb = false;
		boolean messages = true;
		boolean requests = true;
		boolean online = true;
		boolean waypoints = true;
		boolean sound = true;
		Set<String> mutedFriends = new LinkedHashSet<>();
	}

	private static Data data;

	private SocialSettings() {
	}

	private static Data data() {
		if (data == null) {
			data = ConfigManager.load(CONFIG_ID, Data.class, new Data());
			if (data.mutedFriends == null) {
				data.mutedFriends = new LinkedHashSet<>();
			}
		}
		return data;
	}

	private static void save() {
		ConfigManager.save(CONFIG_ID, data());
	}

	public static boolean doNotDisturb() {
		return data().doNotDisturb;
	}

	public static void setDoNotDisturb(boolean value) {
		data().doNotDisturb = value;
		save();
	}

	public static boolean messages() {
		return data().messages;
	}

	public static void setMessages(boolean value) {
		data().messages = value;
		save();
	}

	public static boolean requests() {
		return data().requests;
	}

	public static void setRequests(boolean value) {
		data().requests = value;
		save();
	}

	public static boolean online() {
		return data().online;
	}

	public static void setOnline(boolean value) {
		data().online = value;
		save();
	}

	public static boolean waypoints() {
		return data().waypoints;
	}

	public static void setWaypoints(boolean value) {
		data().waypoints = value;
		save();
	}

	public static boolean sound() {
		return data().sound;
	}

	public static void setSound(boolean value) {
		data().sound = value;
		save();
	}

	public static boolean isMuted(String uuid) {
		return uuid != null && data().mutedFriends.contains(uuid);
	}

	public static void setMuted(String uuid, boolean muted) {
		if (muted) {
			data().mutedFriends.add(uuid);
		} else {
			data().mutedFriends.remove(uuid);
		}
		save();
	}
}
