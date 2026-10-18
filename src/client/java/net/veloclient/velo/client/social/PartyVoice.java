package net.veloclient.velo.client.social;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.pvp.PvpCompat;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.UUID;

/**
 * Party voice chat through Simple Voice Chat, when you have it and turned this on (off by default): the party leader's game creates
 * a voice group with a random password and tells the party (the password shows in the Friends > Party tab), and everyone else's
 * game joins it as soon as they're in a world - so a party is in one voice chat the moment it's on a server together. Only
 * where Simple Voice Chat is installed and the server has groups enabled; nothing happens otherwise. If you leave the group
 * yourself it stays that way until you join the next world.
 *
 * <p>Simple Voice Chat has no API for this (its API is for server plugins), so the few internals it uses for its own group
 * screens are called by reflection; the first failure switches this off quietly instead of ever breaking anything.
 */
public final class PartyVoice {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

	private static boolean broken;
	private static long lastTry;
	private static int attempts;
	private static boolean wasInGroup;
	private static boolean optedOut;
	private static long createdAt;
	private static String partyId;

	private PartyVoice() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
	}

	/** Simple Voice Chat is installed (so the option makes sense). */
	public static boolean available() {
		return !broken && FabricLoader.getInstance().isModLoaded("voicechat");
	}

	private static void reset() {
		attempts = 0;
		wasInGroup = false;
		optedOut = false;
		lastTry = 0;
	}

	/** Every client tick (cheap: acts at most every two seconds). */
	public static void tick() {
		if (!SocialSettings.voiceGroup() || !available() || !SocialClient.inParty() || PvpCompat.localPlayer() == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastTry < 2000) {
			return;
		}
		lastTry = now;
		var party = SocialClient.party();
		String id = party.has("id") ? party.get("id").getAsString() : "";
		if (!id.equals(partyId)) {
			partyId = id;
			reset();
		}
		try {
			Object client = staticMethod("de.maxhenkel.voicechat.voice.client.ClientManager", "getClient");
			if (client == null) {
				return; // not connected to the server's voice chat (yet, or at all)
			}
			UUID inGroup = (UUID) call(staticMethod("de.maxhenkel.voicechat.voice.client.ClientManager", "getPlayerStateManager"), "getGroupID");
			JsonObject voice = party.has("voice") && party.get("voice").isJsonObject() ? party.getAsJsonObject("voice") : null;
			UUID target = voice == null ? null : groupId(voice.get("name").getAsString());
			boolean inTarget = target != null && target.equals(inGroup);
			if (wasInGroup && inGroup == null) {
				optedOut = true; // you left on purpose
			}
			wasInGroup = inGroup != null;
			if (optedOut || inTarget) {
				return;
			}
			if (target != null && inGroup == null) {
				if (attempts++ < 4) {
					send(newObject("de.maxhenkel.voicechat.net.JoinGroupPacket", new Class<?>[] {UUID.class, String.class},
							target, voice.get("password").getAsString()));
				}
				return;
			}
			if (SocialClient.isPartyLeader() && inGroup == null && (voice == null || target == null) && now - createdAt > 20_000 && attempts++ < 2) {
				String name = "Velo-" + id.substring(0, Math.min(6, id.length()));
				String password = randomPassword();
				Object type = Class.forName("de.maxhenkel.voicechat.api.Group$Type").getField("NORMAL").get(null);
				send(newObject("de.maxhenkel.voicechat.net.CreateGroupPacket", new Class<?>[] {String.class, String.class, Class.forName("de.maxhenkel.voicechat.api.Group$Type")},
						name, password, type));
				createdAt = now;
				SocialClient.partyVoice(name, password);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			broken = true;
			VeloClient.LOGGER.info("Party voice group disabled - Simple Voice Chat's internals weren't as expected: {}", e.toString());
		}
	}

	/** The voice group's name and password of your party, or null (for the Party tab). */
	public static String[] current() {
		var party = SocialClient.party();
		if (party == null || !party.has("voice") || !party.get("voice").isJsonObject()) {
			return null;
		}
		JsonObject voice = party.getAsJsonObject("voice");
		return new String[] {voice.get("name").getAsString(), voice.has("password") ? voice.get("password").getAsString() : ""};
	}

	private static UUID groupId(String name) throws ReflectiveOperationException {
		Object manager = staticMethod("de.maxhenkel.voicechat.voice.client.ClientManager", "getGroupManager");
		Collection<?> groups = (Collection<?>) call(manager, "getGroups");
		for (Object group : groups) {
			if (name.equals(call(group, "getName"))) {
				return (UUID) call(group, "getId");
			}
		}
		return null;
	}

	private static String randomPassword() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 8; i++) {
			sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}

	private static void send(Object packet) throws ReflectiveOperationException {
		Class<?> net = Class.forName("de.maxhenkel.voicechat.net.ClientServerNetManager");
		Class<?> packetType = Class.forName("de.maxhenkel.voicechat.net.Packet");
		net.getMethod("sendToServer", packetType).invoke(null, packet);
	}

	private static Object newObject(String className, Class<?>[] types, Object... args) throws ReflectiveOperationException {
		return Class.forName(className).getConstructor(types).newInstance(args);
	}

	private static Object staticMethod(String className, String method) throws ReflectiveOperationException {
		return Class.forName(className).getMethod(method).invoke(null);
	}

	/** {@code target.method()} on an instance (null when there is no instance). */
	private static Object call(Object target, String method) throws ReflectiveOperationException {
		if (target == null) {
			return null;
		}
		Method m = target.getClass().getMethod(method);
		m.setAccessible(true);
		return m.invoke(target);
	}
}
