package net.veloclient.velo.client.worldmap;

import com.google.gson.JsonObject;
import net.veloclient.velo.client.network.SocialClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live position sharing with your Velo party: you switch it on in the world map, then your party sees you
 * as an arrow on their map. Off by default - and off again every time you join a different party (or leave
 * one). Only members who also run Velo Client see it; nothing is sent while it is off.
 */
public final class PartyLocator {

	/** One other member on the map. */
	public record Member(String uuid, String name, double x, double z, double yaw, String dimension, String world) {
	}

	private static final long SEND_EVERY_MILLIS = 1000;
	private static final long POLL_EVERY_MILLIS = 1000;

	private static volatile boolean sharing;
	private static volatile List<Member> members = List.of();
	private static String partyId;
	private static long lastSent;
	private static long lastPolled;
	private static volatile boolean polling;

	private PartyLocator() {
	}

	public static boolean sharing() {
		return sharing;
	}

	public static void setSharing(boolean on) {
		if (on && !SocialClient.inParty()) {
			return;
		}
		sharing = on;
		lastSent = 0;
		if (!on && SocialClient.party() != null) {
			SocialClient.partyPosition(null);
		}
	}

	/** Whether sharing makes sense right now: in a party with someone else in it. */
	public static boolean available() {
		return SocialClient.inParty();
	}

	public static List<Member> members() {
		return SocialClient.inParty() ? members : List.of();
	}

	/** Every client tick. */
	public static void tick() {
		var party = SocialClient.party();
		String id = party != null && party.has("id") ? party.get("id").getAsString() : null;
		if (id == null ? partyId != null : !id.equals(partyId)) {
			// A different party (or none): sharing starts off again.
			partyId = id;
			sharing = false;
			members = List.of();
		}
		if (!sharing) {
			return;
		}
		if (!SocialClient.inParty()) {
			sharing = false;
			return;
		}
		long now = System.currentTimeMillis();
		double[] player = MapCompat.player();
		String world = WorldMap.currentWorld();
		String dimension = WorldMap.currentDimension();
		if (player == null || world == null || dimension == null || now - lastSent < SEND_EVERY_MILLIS) {
			return;
		}
		lastSent = now;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("x", player[0]);
		payload.put("y", player[1]);
		payload.put("z", player[2]);
		payload.put("yaw", player[3]);
		payload.put("dimension", dimension);
		payload.put("world", world);
		SocialClient.partyPosition(payload);
	}

	/** Called while a map is on screen: refreshes the other members' positions at most once a second. */
	public static void pollIfDue() {
		long now = System.currentTimeMillis();
		if (polling || !SocialClient.inParty() || now - lastPolled < POLL_EVERY_MILLIS) {
			return;
		}
		lastPolled = now;
		polling = true;
		SocialClient.partyPositions().thenAccept(list -> {
			List<Member> out = new java.util.ArrayList<>();
			for (JsonObject o : list) {
				out.add(new Member(o.get("uuid").getAsString(), o.get("name").getAsString(), o.get("x").getAsDouble(), o.get("z").getAsDouble(),
						o.get("yaw").getAsDouble(), o.get("dimension").getAsString(), o.get("world").getAsString()));
			}
			members = List.copyOf(out);
			polling = false;
		});
	}
}
