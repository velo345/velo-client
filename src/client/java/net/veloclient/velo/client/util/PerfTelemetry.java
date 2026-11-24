package net.veloclient.velo.client.util;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Anonymous performance numbers after an update (owner 2026-11-24: "see if an update broke or improved something"):
 * in the first three game sessions after Velo changed version, one small report per session - start-up time, FPS while
 * playing (average and the slowest 5 % of seconds over the first five minutes in a world), heap after the menu trim,
 * and what shapes those numbers (Minecraft version, render backend, GPU model, CPU cores, RAM, mod count, render
 * distance). No name, no UUID, no server, no chat - only a random id per install so repeats can be counted once.
 * {@code -Dvelo.noTelemetry} turns it off.
 */
public final class PerfTelemetry {

	private static final int SESSIONS_AFTER_UPDATE = 3;
	private static final int FPS_SECONDS = 300;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final List<Integer> fps = new ArrayList<>();
	private static long lastSample;
	private static boolean active;
	private static boolean sent;

	private PerfTelemetry() {
	}

	public static void register() {
		if (Boolean.getBoolean("velo.noTelemetry") || FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		active = claimSession();
		if (!active) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (sent) {
				return;
			}
			//? if <26.1 {
			boolean playing = client.world != null && client.player != null && ClientCompat.currentScreen() == null && client.isWindowFocused();
			int current = client.getCurrentFps();
			//?} else {
			/*boolean playing = client.level != null && client.player != null && ClientCompat.currentScreen() == null && client.isWindowActive();
			int current = client.getFps();
			*///?}
			long now = System.nanoTime();
			if (!playing || now - lastSample < 1_000_000_000L) {
				return;
			}
			lastSample = now;
			fps.add(current);
			if (fps.size() >= FPS_SECONDS) {
				send();
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (!sent && fps.size() >= 60) {
				send();
			}
		});
	}

	/** Counts this session against the version's three (reset when the Velo version changed). */
	private static boolean claimSession() {
		try {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("velo-client").resolve("perf-telemetry.json");
			JsonObject state = Files.exists(file) ? com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
			String version = veloVersion();
			if (!state.has("install")) {
				state.addProperty("install", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
			}
			int sessions = version.equals(state.has("version") ? state.get("version").getAsString() : "") && state.has("sessions")
					? state.get("sessions").getAsInt() : 0;
			if (sessions >= SESSIONS_AFTER_UPDATE) {
				return false;
			}
			state.addProperty("version", version);
			state.addProperty("sessions", sessions + 1);
			Files.createDirectories(file.getParent());
			Files.writeString(file, state.toString(), StandardCharsets.UTF_8);
			install = state.get("install").getAsString();
			session = sessions + 1;
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static String install = "";
	private static int session;

	private static String veloVersion() {
		return FabricLoader.getInstance().getModContainer("velo-client").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	private static void send() {
		sent = true;
		List<Integer> samples = new ArrayList<>(fps);
		if (samples.isEmpty()) {
			return;
		}
		JsonObject r = new JsonObject();
		String v = veloVersion();
		r.addProperty("velo", v.contains("+") ? v.substring(0, v.indexOf('+')) : v);
		r.addProperty("mc", FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		r.addProperty("install", install);
		r.addProperty("session", session);
		r.addProperty("os", System.getProperty("os.name", "?").replaceAll("[^A-Za-z0-9 .]", "") + " " + System.getProperty("os.arch", ""));
		r.addProperty("cores", Runtime.getRuntime().availableProcessors());
		try {
			var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			r.addProperty("ramGb", Math.round(os.getTotalMemorySize() / 1073741824.0));
		} catch (Throwable ignored) {
			// unknown
		}
		r.addProperty("heapMaxMb", Runtime.getRuntime().maxMemory() >> 20);
		String gpu = GpuInfo.describe();
		if (gpu != null) {
			r.addProperty("backend", gpu.toLowerCase(Locale.ROOT).contains("vulkan") ? "vulkan" : "opengl");
			r.addProperty("gpu", gpu.replaceAll("\\s*\\(.*$", "").trim());
		}
		r.addProperty("mods", FabricLoader.getInstance().getAllMods().size());
		r.addProperty("launchMs", net.veloclient.velo.client.devtools.StartupTimer.readyMs);
		r.addProperty("heapMb", MemoryTrim.trimmedUsedMb);
		List<Integer> sorted = new ArrayList<>(samples);
		java.util.Collections.sort(sorted);
		r.addProperty("fpsAvg", (int) Math.round(samples.stream().mapToInt(Integer::intValue).average().orElse(0)));
		r.addProperty("fpsLow", sorted.get(Math.max(0, (int) Math.floor(sorted.size() * 0.05) - 1)));
		r.addProperty("seconds", samples.size());
		try {
			//? if <26.1 {
			r.addProperty("renderDistance", net.minecraft.client.MinecraftClient.getInstance().options.getViewDistance().getValue());
			//?} else {
			/*r.addProperty("renderDistance", net.minecraft.client.Minecraft.getInstance().options.renderDistance().get());
			*///?}
		} catch (Throwable ignored) {
			// unknown
		}
		Thread.ofVirtual().name("velo-perf-telemetry").start(() -> {
			try {
				String base = net.veloclient.velo.client.network.VeloServerClient.serverBaseUrl();
				if (base == null) {
					return;
				}
				HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/telemetry/perf")).timeout(Duration.ofSeconds(15))
						.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(r.toString())).build(),
						HttpResponse.BodyHandlers.discarding());
				net.veloclient.velo.VeloClient.LOGGER.info("[Velo] performance report sent (session {} after the update)", session);
			} catch (Exception ignored) {
				// offline: nothing to report
			}
		});
	}
}
