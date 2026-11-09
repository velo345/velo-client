package net.veloclient.velo.dev.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A server's mod hints (Velo Bridge "mod-rules"), checked against the player's own mods on their PC - nothing is
 * sent back. Only ever a heads-up, shown once the world is loaded: "From &lt;server&gt;: these mods aren't allowed
 * here". "Don't show again" hides it for that server for good.
 */
final class BridgeModRules {

	private static volatile String pendingTitle;
	private static volatile String pendingBody;
	private static volatile String pendingServer;

	private BridgeModRules() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> showWhenReady());
	}

	/** A forbidden mod that is installed (with the jar files to move away) or a recommended one that's missing. */
	record Hit(String label, String modrinth, List<java.nio.file.Path> files) {
	}

	private static volatile List<Hit> pendingBad = List.of();
	private static volatile List<Hit> pendingMissing = List.of();
	private static volatile String pendingMessage = "";

	static void check(String serverName, JsonObject rules) {
		String address = ClientCompat.currentServerAddress();
		String key = address == null ? serverName : address.toLowerCase(Locale.ROOT);
		if (hidden().contains(key)) {
			return;
		}
		List<String[]> mods = VeloBridgeClient.installedMods();
		List<Hit> bad = new ArrayList<>();
		for (JsonElement e : VeloBridgeClient.arr(rules, "forbid")) {
			JsonObject r = e.getAsJsonObject();
			if (installed(mods, r)) {
				bad.add(new Hit(VeloBridgeClient.str(r, "label", VeloBridgeClient.str(r, "value", "?")), null, files(r)));
			}
		}
		List<Hit> missing = new ArrayList<>();
		for (JsonElement e : VeloBridgeClient.arr(rules, "recommend")) {
			JsonObject r = e.getAsJsonObject();
			// only what the player doesn't have yet - nobody needs to be told about mods they already use
			if (!installed(mods, r)) {
				String match = VeloBridgeClient.str(r, "match", "name");
				String slug = match.equals("modrinth") || match.equals("id") ? VeloBridgeClient.str(r, "value", null) : null;
				missing.add(new Hit(VeloBridgeClient.str(r, "label", VeloBridgeClient.str(r, "value", "?")), slug, List.of()));
			}
		}
		if (bad.isEmpty() && missing.isEmpty()) {
			return;
		}
		pendingBad = bad;
		pendingMissing = missing;
		pendingMessage = VeloBridgeClient.str(rules, "message", "");
		pendingTitle = "From " + serverName + ": mods";
		pendingBody = "";
		pendingServer = key;
	}

	/** The jar files (in the mods folder) of every installed mod a rule matches. */
	private static List<java.nio.file.Path> files(JsonObject rule) {
		List<java.nio.file.Path> out = new ArrayList<>();
		java.nio.file.Path modsDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().normalize();
		for (net.fabricmc.loader.api.ModContainer mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
			var origin = mod.getOrigin();
			if (origin.getKind() != net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH || origin.getPaths().isEmpty()) {
				continue;
			}
			java.nio.file.Path jar = origin.getPaths().get(0).toAbsolutePath().normalize();
			String[] info = {mod.getMetadata().getId().toLowerCase(Locale.ROOT), mod.getMetadata().getName(), jar.getFileName().toString().toLowerCase(Locale.ROOT)};
			if (jar.startsWith(modsDir) && matches(info, rule) && !out.contains(jar)) {
				out.add(jar);
			}
		}
		return out;
	}

	private static boolean installed(List<String[]> mods, JsonObject rule) {
		for (String[] mod : mods) {
			if (matches(mod, rule)) {
				return true;
			}
		}
		return false;
	}

	/** mod = {id, name, jar file name}; Modrinth slugs and mod ids often differ only in - vs _ or spaces. */
	private static boolean matches(String[] mod, JsonObject rule) {
		String match = VeloBridgeClient.str(rule, "match", "name");
		String v = VeloBridgeClient.str(rule, "value", "").toLowerCase(Locale.ROOT);
		if (v.isEmpty()) {
			return false;
		}
		String flat = v.replaceAll("[^a-z0-9]", "");
		String name = mod[1] == null ? "" : mod[1].toLowerCase(Locale.ROOT);
		return switch (match) {
			case "id", "modrinth" -> mod[0].equals(v) || mod[0].equals(v.replace('-', '_')) || mod[0].replaceAll("[^a-z0-9]", "").equals(flat)
					|| name.replaceAll("[^a-z0-9]", "").equals(flat);
			default -> mod[0].contains(v) || name.contains(v) || mod[2].contains(v);
		};
	}

	private static void showWhenReady() {
		String title = pendingTitle;
		if (title == null || !BridgeMc.inWorld() || ClientCompat.currentScreen() != null) {
			return;
		}
		String server = pendingServer;
		pendingTitle = null;
		MinecraftClient.getInstance().setScreen(new BridgeModsScreen(title, pendingBad, pendingMissing, pendingMessage, () -> hide(server)));
	}

	private static Path file() {
		return VeloPaths.config().resolve("bridge-hidden-servers.json");
	}

	private static Set<String> hidden() {
		Set<String> out = new LinkedHashSet<>();
		try {
			if (Files.exists(file())) {
				JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonArray().forEach(e -> out.add(e.getAsString()));
			}
		} catch (Exception ignored) {
			// Unreadable: show it.
		}
		return out;
	}

	private static void hide(String server) {
		Set<String> all = hidden();
		all.add(server);
		JsonArray list = new JsonArray();
		all.forEach(list::add);
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), list.toString(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Shown again next time.
		}
	}
}
