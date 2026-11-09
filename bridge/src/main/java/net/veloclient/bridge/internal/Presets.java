package net.veloclient.bridge.internal;

import java.util.List;
import java.util.Map;

/** The ready-made mod lists (the same ones the Velo owner panel offers). Each entry: {match, value, label}. */
public final class Presets {

	private Presets() {
	}

	public static final Map<String, List<String[]>> ALL = Map.of(
			"cheat-clients", List.of(
					new String[] {"id", "wurst", "Wurst"}, new String[] {"id", "meteor-client", "Meteor Client"}, new String[] {"id", "aristois", "Aristois"},
					new String[] {"id", "liquidbounce", "LiquidBounce"}, new String[] {"name", "glazed", "Glazed"}, new String[] {"id", "thunderhack", "ThunderHack"},
					new String[] {"name", "rusherhack", "RusherHack"}, new String[] {"name", "future client", "Future"}, new String[] {"name", "impact", "Impact"},
					new String[] {"id", "boze", "Boze"}, new String[] {"name", "inertia", "Inertia"}, new String[] {"name", "bleachhack", "BleachHack"},
					new String[] {"name", "coffee client", "Coffee Client"}, new String[] {"id", "kami", "KAMI"}, new String[] {"name", "argon", "Argon"},
					new String[] {"name", "prestige", "Prestige"}),
			"often-banned", List.of(
					new String[] {"id", "baritone", "Baritone"}, new String[] {"name", "xray", "X-Ray"}, new String[] {"id", "freecam", "Freecam"},
					new String[] {"name", "autoclicker", "Auto clicker"}, new String[] {"name", "auto clicker", "Auto clicker"},
					new String[] {"name", "killaura", "Kill aura"}, new String[] {"name", "printer", "Litematica printer"},
					new String[] {"name", "seedcracker", "SeedCrackerX"}),
			"radar", List.of(
					new String[] {"id", "xaerominimap", "Xaero's Minimap (radar)"}, new String[] {"id", "journeymap", "JourneyMap"},
					new String[] {"id", "voxelmap", "VoxelMap"}));

	/** Recommendation lists (Modrinth slugs, so players can add them with one click in Velo). */
	public static final Map<String, List<String[]>> RECOMMEND = Map.of(
			"performance", List.of(
					new String[] {"modrinth", "sodium", "Sodium"}, new String[] {"modrinth", "lithium", "Lithium"},
					new String[] {"modrinth", "ferrite-core", "FerriteCore"}, new String[] {"modrinth", "entityculling", "Entity Culling"},
					new String[] {"modrinth", "immediatelyfast", "ImmediatelyFast"}),
			"voice-chat", List.<String[]>of(new String[] {"modrinth", "simple-voice-chat", "Simple Voice Chat"}),
			"emotes", List.<String[]>of(new String[] {"modrinth", "emotecraft", "Emotecraft"}),
			"shaders", List.of(new String[] {"modrinth", "iris", "Iris Shaders"}, new String[] {"modrinth", "sodium", "Sodium"}));
}
