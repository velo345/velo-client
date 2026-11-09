package net.veloclient.velo.dev.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.emotes.EmoteSounds;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server HUD elements, server menus and server music (Velo Bridge 1.2). HUD elements are drawn by
 * {@link ServerHudModule}; menus open as {@link BridgeMenuScreen}; music fades in and out over the Music slider and
 * pauses vanilla's music while it plays.
 */
final class BridgeServerUi {

	record HudItem(String id, String type, String title, String text, double value, long endsAt, int color, String icon, String url, int w, int h,
			int order, long since) {
	}

	static final Map<String, HudItem> HUD = new ConcurrentHashMap<>();
	private static final Map<String, Identifier> IMAGES = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> IMAGE_PENDING = new ConcurrentHashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private static EmoteSounds.Handle music;
	private static String musicKey;

	private BridgeServerUi() {
	}

	static void handle(String type, JsonObject m) {
		switch (type) {
			case "hud_set" -> {
				String id = str(m, "id", "");
				if (id.isEmpty() || HUD.size() > 40 && !HUD.containsKey(id)) {
					return;
				}
				HudItem old = HUD.get(id);
				HUD.put(id, new HudItem(id, str(m, "type", "text"), str(m, "title", ""), str(m, "text", ""), num(m, "value"), (long) num(m, "endsAt"),
						VeloBridgeClient.color(str(m, "color", "#5aa9ff")), str(m, "icon", null), str(m, "url", null), (int) num(m, "w"), (int) num(m, "h"),
						(int) num(m, "order"), old == null ? System.currentTimeMillis() : old.since()));
			}
			case "hud_remove" -> HUD.remove(str(m, "id", ""));
			case "hud_clear" -> HUD.clear();
			case "menu" -> MinecraftClient.getInstance().execute(() -> BridgeMenuScreen.open(m));
			case "menu_close" -> MinecraftClient.getInstance().execute(() -> BridgeMenuScreen.closeFromServer(str(m, "id", "")));
			case "cutscene" -> BridgeCutscene.start(m);
			case "cutscene_stop" -> BridgeCutscene.stop(false);
			case "music" -> MinecraftClient.getInstance().execute(() -> music(str(m, "sound", ""), (float) num(m, "volume"), !m.has("loop") || m.get("loop").getAsBoolean()));
			case "music_stop" -> MinecraftClient.getInstance().execute(() -> music(null, 0, false));
			default -> {
			}
		}
	}

	/** Sorted for drawing. */
	static List<HudItem> hudItems() {
		List<HudItem> list = new ArrayList<>(HUD.values());
		list.sort(Comparator.comparingInt(HudItem::order).thenComparingLong(HudItem::since));
		return list;
	}

	/** The picture of an image element, downloaded once (https, PNG/JPEG up to 512 KB); null until it's there. */
	static Identifier image(String url) {
		if (url == null || !url.startsWith("https://")) {
			return null;
		}
		Identifier id = IMAGES.get(url);
		if (id != null || IMAGE_PENDING.putIfAbsent(url, true) != null) {
			return id;
		}
		Thread.startVirtualThread(() -> {
			try {
				HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).header("User-Agent", "VeloClient-Bridge").build(),
						HttpResponse.BodyHandlers.ofByteArray());
				if (r.statusCode() == 200 && r.body().length < 512 * 1024) {
					net.veloclient.velo.client.gui.RemoteTextureLoader.fromBytes("bridge-hud:" + url, r.body(), false, tex -> IMAGES.put(url, tex));
				}
			} catch (Exception ignored) {
				// no picture then
			}
		});
		return null;
	}

	private static void music(String sound, float volume, boolean loop) {
		String key = sound == null || sound.isBlank() ? null : sound + "|" + volume + "|" + loop;
		if (java.util.Objects.equals(key, musicKey) && music != null && !music.done()) {
			return;
		}
		if (music != null) {
			music.fadeOut();
			music = null;
		}
		musicKey = key;
		if (key == null || !ServerHudModule.music()) {
			return;
		}
		float v = Math.max(0.05f, Math.min(1f, volume <= 0 ? 0.8f : volume));
		music = loop ? EmoteSounds.menuMusic(sound, v) : EmoteSounds.music(null, sound, v, false);
	}

	static void tick(MinecraftClient client) {
		if (music != null) {
			if (music.done() && musicKey != null && musicKey.endsWith("|false")) {
				music = null;
				return;
			}
			// vanilla's music would play on top of the server's
			//? if <26.1 {
			client.getMusicTracker().stop();
			//?} else {
			/*client.getMusicManager().stopPlaying();
			*///?}
		}
	}

	/** The player switched server music off in the Server HUD settings. */
	static void musicSettingChanged() {
		if (!ServerHudModule.music() && music != null) {
			music.fadeOut();
			music = null;
		}
	}

	static void reset() {
		HUD.clear();
		if (music != null) {
			music.fadeOut();
			music = null;
		}
		musicKey = null;
		BridgeCutscene.stop(false);
		MinecraftClient.getInstance().execute(() -> BridgeMenuScreen.closeFromServer(null));
	}

	private static String str(JsonObject m, String k, String fallback) {
		return m.has(k) && m.get(k).isJsonPrimitive() ? m.get(k).getAsString() : fallback;
	}

	private static double num(JsonObject m, String k) {
		try {
			return m.has(k) && m.get(k).isJsonPrimitive() ? m.get(k).getAsDouble() : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
