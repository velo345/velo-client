package net.veloclient.velo.client.social;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.util.ClientCompat;

/**
 * "Follow me": a party leader can ask the party to follow them from server to server. While that is on, every
 * time the leader joins a server the other members get one small popup (Join / Dismiss) - no menu in the way
 * while they're fighting. Off by default and switched off again with each new party; the server also rate-limits
 * it and never repeats the same destination within a couple of minutes.
 */
public final class PartyFollow {

	private static volatile boolean requesting;
	private static String partyId;

	private PartyFollow() {
	}

	public static void register() {
		SocialClient.addPartyListener(PartyFollow::onEvent);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(PartyFollow::onJoin));
	}

	public static boolean requesting() {
		return requesting && SocialClient.isPartyLeader();
	}

	public static void setRequesting(boolean on) {
		requesting = on && SocialClient.isPartyLeader() && SocialClient.inParty();
	}

	/** Every client tick: a different party (or none) starts with it off. */
	public static void tick() {
		var party = SocialClient.party();
		String id = party != null && party.has("id") ? party.get("id").getAsString() : null;
		if (id == null ? partyId != null : !id.equals(partyId)) {
			partyId = id;
			requesting = false;
		}
		if (requesting && !SocialClient.isPartyLeader()) {
			requesting = false;
		}
	}

	/** The leader joined a world: if it is a server, tell the party. */
	private static void onJoin() {
		if (!requesting || !SocialClient.isPartyLeader() || !SocialClient.inParty()) {
			return;
		}
		String address = ClientCompat.currentServerAddress();
		if (address == null || address.isBlank() || ClientCompat.isSingleplayer()) {
			return;
		}
		SocialClient.partyFollow(address, ClientCompat.currentServerName());
	}

	private static void onEvent(String type, JsonObject data) {
		if (!type.equals("party_follow") || !data.has("server")) {
			return;
		}
		String server = data.get("server").getAsString();
		String label = data.has("serverName") && !data.get("serverName").isJsonNull() ? data.get("serverName").getAsString() : server;
		String leader = data.has("leaderName") ? data.get("leaderName").getAsString() : "Your party leader";
		String partyKey = "party-follow:" + (data.has("party") ? data.get("party").getAsString() : "");
		String current = ClientCompat.currentServerAddress();
		if (current != null && current.equalsIgnoreCase(server)) {
			return;
		}
		// One popup per party, replaced rather than stacked.
		NotificationOverlay.replaceKeyed(partyKey, new NotificationOverlay.Toast(data.has("leader") ? data.get("leader").getAsString() : null, leader,
				leader + " joined a server", label, 25000, null,
				() -> ClientCompat.joinServer(server, label), () -> { }).labels("Join", "Dismiss"));
	}
}
