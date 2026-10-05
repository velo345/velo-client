package net.veloclient.velo.client.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Collects what's needed to debug a problem (versions, system, GPU, where you were, mods, enabled
 * Velo modules, the end of the game log, and a crash report if there is one) and sends it with
 * the player's own description to the Velo server's report inbox.
 */
public final class BugReporter {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final int LOG_LINES = 2500;

	private BugReporter() {
	}

	/** What will be sent, as readable "key: value" pairs (shown in the report screen). Call on the render thread. */
	public static Map<String, String> system() {
		Map<String, String> map = new LinkedHashMap<>();
		map.put("Velo Client", version("velo-client"));
		map.put("Minecraft", version("minecraft"));
		map.put("Fabric Loader", version("fabricloader"));
		map.put("Fabric API", version("fabric-api"));
		map.put("Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor", "?") + ")");
		map.put("OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
		map.put("CPU cores", String.valueOf(Runtime.getRuntime().availableProcessors()));
		Runtime rt = Runtime.getRuntime();
		map.put("Memory", (rt.totalMemory() - rt.freeMemory()) / 1048576 + " / " + rt.maxMemory() / 1048576 + " MB used");
		String gpu = gpu();
		if (gpu != null) {
			map.put("GPU", gpu);
		}
		MinecraftClient client = MinecraftClient.getInstance();
		map.put("FPS", String.valueOf(client.getCurrentFps()));
		map.put("Where", ClientCompat.isSingleplayer() ? "Singleplayer" : ClientCompat.currentServerAddress() != null
				? "Server " + ClientCompat.currentServerAddress() : "Menus");
		if (client.player != null) {
			map.put("Dimension", ClientCompat.dimensionId());
		}
		map.put("Window", client.getWindow().getWidth() + "x" + client.getWindow().getHeight() + ", GUI scale " + ClientCompat.guiScale());
		return map;
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
	}

	private static String gpu() {
		return net.veloclient.velo.client.util.GpuInfo.describe();
	}

	public static List<String> mods() {
		List<String> mods = new ArrayList<>();
		FabricLoader.getInstance().getAllMods().forEach(m -> {
			String id = m.getMetadata().getId();
			if (!id.startsWith("fabric-") || id.equals("fabric-api")) {
				mods.add(id + " " + m.getMetadata().getVersion().getFriendlyString());
			}
		});
		mods.sort(String::compareTo);
		return mods;
	}

	public static List<String> enabledModules() {
		List<String> list = new ArrayList<>();
		for (Module module : ModuleRegistry.all()) {
			if (module.isEnabled()) {
				list.add(module.id());
			}
		}
		list.sort(String::compareTo);
		return list;
	}

	public static Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	static String logTail() {
		Path log = gameDir().resolve("logs").resolve("latest.log");
		try {
			byte[] bytes = Files.readAllBytes(log);
			List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
			return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()));
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Sends a report. {@code system} must have been collected on the render thread; this does the
	 * file reading and upload in the background and calls back on the render thread.
	 */
	public static void send(String kind, String title, String message, Map<String, String> system, boolean includeLogs, Path crashFile,
			Consumer<String> onSent, Consumer<String> onError) {
		List<String> mods = mods();
		List<String> modules = enabledModules();
		//? if <26.1 {
		String username = MinecraftClient.getInstance().getSession().getUsername();
		//?} else {
		/*String username = net.minecraft.client.Minecraft.getInstance().getUser().getName();
		*///?}
		Thread.ofVirtual().start(() -> {
			try {
				JsonObject report = new JsonObject();
				report.addProperty("kind", kind);
				report.addProperty("source", "game");
				report.addProperty("title", title);
				report.addProperty("message", message);
				report.addProperty("username", username);
				JsonObject sys = new JsonObject();
				system.forEach(sys::addProperty);
				report.add("system", sys);
				report.add("mods", new Gson().toJsonTree(mods).getAsJsonArray());
				report.add("modules", new Gson().toJsonTree(modules).getAsJsonArray());
				if (includeLogs) {
					report.addProperty("log", logTail());
					if (crashFile != null) {
						report.addProperty("crash", Files.readString(crashFile, StandardCharsets.UTF_8));
					}
				}
				String base = VeloServerClient.serverBaseUrl();
				if (base == null) {
					throw new IllegalStateException("The Velo server is turned off (Velo Network module settings)");
				}
				HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/v1/reports"))
						.header("Content-Type", "application/json").timeout(Duration.ofSeconds(60))
						.POST(HttpRequest.BodyPublishers.ofString(report.toString()));
				String token = VeloServerClient.sessionToken();
				if (token != null) {
					request.header("Authorization", "Bearer " + token);
				}
				HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
				JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
				if (response.statusCode() / 100 != 2) {
					throw new IllegalStateException(body.has("error") ? body.get("error").getAsString() : "HTTP " + response.statusCode());
				}
				String id = body.get("id").getAsString();
				if (crashFile != null) {
					CrashCheck.markHandled(crashFile);
				}
				MinecraftClient.getInstance().execute(() -> onSent.accept(id));
			} catch (Exception e) {
				String reason = e instanceof java.io.IOException ? "Couldn't reach the Velo server" : e.getMessage();
				MinecraftClient.getInstance().execute(() -> onError.accept(reason == null ? "Something went wrong" : reason));
			}
		});
	}
}
