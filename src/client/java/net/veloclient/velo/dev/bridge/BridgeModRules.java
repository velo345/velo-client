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

	static void check(String serverName, JsonObject rules) {
		String address = ClientCompat.currentServerAddress();
		String key = address == null ? serverName : address.toLowerCase(Locale.ROOT);
		if (hidden().contains(key)) {
			return;
		}
		List<String[]> mods = VeloBridgeClient.installedMods();
		Set<String> bad = new LinkedHashSet<>();
		for (JsonElement e : VeloBridgeClient.arr(rules, "forbid")) {
			JsonObject r = e.getAsJsonObject();
			if (installed(mods, r)) {
				bad.add(VeloBridgeClient.str(r, "label", VeloBridgeClient.str(r, "value", "?")));
			}
		}
		List<String> missing = new ArrayList<>();
		for (JsonElement e : VeloBridgeClient.arr(rules, "recommend")) {
			JsonObject r = e.getAsJsonObject();
			if (!installed(mods, r)) {
				missing.add(VeloBridgeClient.str(r, "label", VeloBridgeClient.str(r, "value", "?")));
			}
		}
		if (bad.isEmpty() && missing.isEmpty()) {
			return;
		}
		StringBuilder body = new StringBuilder();
		if (!bad.isEmpty()) {
			body.append("Not allowed here: ").append(String.join(", ", bad)).append(".");
			String message = VeloBridgeClient.str(rules, "message", "");
			if (!message.isBlank()) {
				body.append(' ').append(message);
			}
		}
		if (!missing.isEmpty()) {
			body.append(body.isEmpty() ? "" : "\n\n").append("Recommended here: ").append(String.join(", ", missing)).append(".");
		}
		body.append("\n\nThis comes from the server, not from Velo Client - nothing was changed or blocked.");
		pendingTitle = "From " + serverName + ": mods";
		pendingBody = body.toString();
		pendingServer = key;
	}

	private static boolean installed(List<String[]> mods, JsonObject rule) {
		String match = VeloBridgeClient.str(rule, "match", "name");
		String v = VeloBridgeClient.str(rule, "value", "").toLowerCase(Locale.ROOT);
		if (v.isEmpty()) {
			return false;
		}
		for (String[] mod : mods) {
			boolean hit = switch (match) {
				case "id", "modrinth" -> mod[0].equals(v) || mod[0].equals(v.replace('-', '_'));
				default -> mod[0].contains(v) || mod[1] != null && mod[1].toLowerCase(Locale.ROOT).contains(v) || mod[2].contains(v);
			};
			if (hit) {
				return true;
			}
		}
		return false;
	}

	private static void showWhenReady() {
		String title = pendingTitle;
		if (title == null || !BridgeMc.inWorld() || ClientCompat.currentScreen() != null) {
			return;
		}
		String body = pendingBody;
		String server = pendingServer;
		pendingTitle = null;
		int lines = body.split("\n").length + body.length() / 52;
		MinecraftClient.getInstance().setScreen(new net.veloclient.velo.client.gui.VeloConfirmScreen(null, title, body, "Got it", "Don't show again",
				() -> hide(server), 340, Math.min(300, 96 + lines * 11)));
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
