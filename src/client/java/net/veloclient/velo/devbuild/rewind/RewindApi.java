package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind's AI access: a small HTTP API on 127.0.0.1 so an AI agent (or any script) can make videos from replays
 * without clicking - open a recording, read and change the timeline (clips, keyframes, clip values, interpolation),
 * jump around, grab single frames or a contact sheet of many (far faster than exporting), undo, and export.
 *
 * <p>Off unless the module's "AI Access" setting is on or the game runs with {@code -Dvelo.rewindApi=<port>}. Every
 * request needs the token from {@code velo-rewind/api.json} ({@code Authorization: Bearer <token>}); the same folder
 * gets {@code AI.md}, the full description. {@code -Dvelo.rewindOpen=<file>} opens a recording right at the title screen
 * (for runs in a hidden window).
 */
//? if <26.2 {
public final class RewindApi {

	private RewindApi() {
	}

	public static void init() {
	}

	public static void setEnabled(boolean on) {
	}

	public static int port() {
		return -1;
	}

	public static boolean windowHidden() {
		return false;
	}
}
//?} else {
/*import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.veloclient.velo.client.clips.ClipRecorder;
import net.veloclient.velo.client.clips.FrameCapture;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class RewindApi {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Velo Rewind API");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
	private static final int DEFAULT_PORT = 25620;
	private static final String FORCED = System.getProperty("velo.aiApi", System.getProperty("velo.rewindApi"));
	// the launcher's token (Settings > AI Access), so it can hand the prompt out before the game runs
	private static final String GIVEN_TOKEN = System.getProperty("velo.aiToken");
	private static final String OPEN = System.getProperty("velo.rewindOpen");

	private static HttpServer server;
	private static String token;
	private static boolean opened;

	private RewindApi() {
	}

	private static volatile Path pendingOpen;

	public static void init() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Path open = pendingOpen;
			if (open != null && client.level == null && !RewindPlayer.active() && client.gui.screen() instanceof TitleScreen) {
				pendingOpen = null;
				RewindPlayer.open(open, client.gui.screen());
			}
		});
		if (FORCED != null) {
			setEnabled(true);
		}
		if (Boolean.getBoolean("velo.hiddenWindow")) {
			// AI runs (launcher: Settings > AI Access > Start the game hidden): no window on screen; it still renders
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (!windowHidden && client.gui.screen() instanceof TitleScreen) {
					windowHidden = true;
					client.options.pauseOnLostFocus = false;
					hideWindow(client.getWindow().handle());
					client.mouseHandler.releaseMouse();
				}
			});
		}
		if (OPEN != null) {
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (!opened && client.gui.screen() instanceof TitleScreen && client.level == null) {
					opened = true;
					RewindPlayer.open(resolve(OPEN), client.gui.screen());
				}
			});
		}
	}

	private static boolean windowHidden;

	// The game started hidden for an AI (it must not grab the mouse - HiddenWindowMouseMixin).
	public static boolean windowHidden() {
		return HIDDEN;
	}

	private static final boolean HIDDEN = Boolean.getBoolean("velo.hiddenWindow");

	// GLFW up to 26.2, SDL3 from 26.3 - whichever this game has.
	private static void hideWindow(long handle) {
		try {
			Class.forName("org.lwjgl.sdl.SDLVideo").getMethod("SDL_HideWindow", long.class).invoke(null, handle);
			LOG.info("Velo AI access: window hidden (SDL)");
			return;
		} catch (ReflectiveOperationException | LinkageError ignored) {
		}
		try {
			Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwHideWindow", long.class).invoke(null, handle);
			LOG.info("Velo AI access: window hidden (GLFW)");
		} catch (ReflectiveOperationException | LinkageError e) {
			LOG.warn("Velo AI access: couldn't hide the window", e);
		}
	}

	public static synchronized void setEnabled(boolean on) {
		if (on == (server != null)) {
			return;
		}
		if (!on) {
			server.stop(0);
			server = null;
			LOG.info("Velo Rewind AI access off");
			return;
		}
		try {
			int port = DEFAULT_PORT;
			if (FORCED != null && FORCED.matches("\\d+")) {
				port = Integer.parseInt(FORCED);
			}
			HttpServer s;
			try {
				s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
			} catch (IOException busy) {
				s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8); // another game has it
			}
			s.createContext("/", RewindApi::handle);
			s.setExecutor(Executors.newFixedThreadPool(2, r -> Thread.ofPlatform().daemon().name("velo-rewind-api").unstarted(r)));
			s.start();
			server = s;
			writeInfo(s.getAddress().getPort());
			LOG.info("Velo Rewind AI access on http://127.0.0.1:{} (token in velo-rewind/api.json)", s.getAddress().getPort());
		} catch (IOException e) {
			LOG.warn("Velo Rewind AI access couldn't start", e);
		}
	}

	// The port it listens on, or -1 when off.
	public static synchronized int port() {
		return server == null ? -1 : server.getAddress().getPort();
	}

	// What to paste into an AI chat (Claude Code, Codex, ...) so it knows how to use this.
	static String prompt() {
		Path dir = folder();
		return "You can control Velo Client (a Minecraft client) through its local AI API.\n"
				+ "1. Read the full guide first: " + dir.resolve("AI.md") + " (also GET / on the API). It lists every endpoint with examples.\n"
				+ "2. URL and token are in " + dir.resolve("api.json") + ". Send 'Authorization: Bearer <token>' with every request, JSON bodies.\n"
				+ "3. Start with GET /status and GET /player. Replays: GET /recordings, POST /open {\"file\", \"leave\": true}, poll /status until replay.ready.\n"
				+ "4. Always look before and after changes with GET /sheet?count=12&width=480. Edits save themselves; POST /undo reverts.\n"
				+ "5. Export with {\"audio\": \"voice\"} for exact frame-by-frame videos; poll GET /export until DONE.\n"
				+ "Rules: 20 ticks = 1 s; the recording player is target \"self\"; moving/chat/building only in singleplayer or as operator.";
	}

	private static Path folder() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("velo-rewind");
	}

	// api.json (address + token; the token stays the same across starts so scripts keep working) and AI.md
	private static void writeInfo(int port) throws IOException {
		Path dir = folder();
		Files.createDirectories(dir);
		Path info = dir.resolve("api.json");
		if (Files.exists(info)) {
			try {
				JsonObject old = JsonParser.parseString(Files.readString(info)).getAsJsonObject();
				token = old.get("token").getAsString();
			} catch (Exception ignored) {
			}
		}
		if (GIVEN_TOKEN != null && GIVEN_TOKEN.length() >= 32) {
			token = GIVEN_TOKEN;
		}
		if (token == null || token.length() < 32) {
			byte[] b = new byte[24];
			new SecureRandom().nextBytes(b);
			token = HexFormat.of().formatHex(b);
		}
		JsonObject o = new JsonObject();
		o.addProperty("url", "http://127.0.0.1:" + port);
		o.addProperty("token", token);
		o.addProperty("docs", dir.resolve("AI.md").toString());
		Files.writeString(info, GSON.toJson(o), StandardCharsets.UTF_8);
		try {
			info.toFile().setReadable(false, false);
			info.toFile().setReadable(true, true);
		} catch (Exception ignored) {
		}
		Files.writeString(dir.resolve("AI.md"), DOCS.replace("{PORT}", String.valueOf(port)), StandardCharsets.UTF_8);
	}

	// ---- requests ----

	private static void handle(HttpExchange ex) throws IOException {
		try {
			// browsers send an Origin: a web page must never drive the game
			if (ex.getRequestHeaders().getFirst("Origin") != null || !authorized(ex)) {
				send(ex, 401, error("missing or wrong token (see velo-rewind/api.json)"));
				return;
			}
			String method = ex.getRequestMethod();
			String path = ex.getRequestURI().getPath().replaceAll("/+$", "");
			Map<String, String> q = query(ex);
			JsonObject body = new JsonObject();
			if (method.equals("POST") || method.equals("PUT")) {
				String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				if (!raw.isBlank()) {
					JsonElement e = JsonParser.parseString(raw);
					body = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
					if (path.equals("/edits")) {
						body.add("__raw", e);
					}
				}
			}
			route(ex, method, path, q, body);
		} catch (ApiError e) {
			send(ex, e.status, error(e.getMessage()));
		} catch (Exception e) {
			LOG.warn("Velo Rewind API request failed", e);
			send(ex, 500, error(e.toString()));
		} finally {
			ex.close();
		}
	}

	private static void route(HttpExchange ex, String method, String path, Map<String, String> q, JsonObject b) throws Exception {
		switch (method + " " + (path.isEmpty() ? "/" : path)) {
			case "GET /" -> send(ex, 200, text(DOCS.replace("{PORT}", String.valueOf(server.getAddress().getPort()))), "text/markdown");
			case "GET /status" -> send(ex, 200, game(RewindApi::status));
			case "GET /properties" -> send(ex, 200, properties());
			case "GET /recordings" -> send(ex, 200, recordings());
			case "POST /open" -> {
				Path file = resolve(str(b, "file"));
				if (!Files.exists(file)) {
					throw new ApiError(404, "no such recording: " + file.getFileName());
				}
				boolean leave = b.has("leave") && b.get("leave").getAsBoolean();
				send(ex, 200, game(() -> {
					Minecraft mc = Minecraft.getInstance();
					if ((RewindPlayer.active() || mc.level != null) && !leave) {
						throw new ApiError(409, "the game is in a world or a replay - add \"leave\": true to leave it first");
					}
					if (RewindPlayer.active()) {
						RewindPlayer.exit();
					} else if (mc.level != null) {
						mc.level.disconnect(net.minecraft.network.chat.Component.literal("Opening a replay (AI API)"));
						mc.disconnectWithSavingScreen();
						mc.setScreen(new TitleScreen());
					}
					if (mc.level == null && !RewindPlayer.active()) {
						RewindPlayer.open(file, mc.gui.screen());
					} else {
						pendingOpen = file; // once the world is gone
					}
					return ok("opening - poll GET /status until replay.ready is true");
				}));
			}
			case "POST /exit" -> send(ex, 200, game(() -> {
				player();
				RewindPlayer.exit();
				return ok("left the replay");
			}));
			case "GET /edits" -> send(ex, 200, game(() -> GSON.toJsonTree(player().edits)));
			case "PUT /edits" -> send(ex, 200, game(() -> {
				RewindPlayer p = player();
				RewindEdits e = GSON.fromJson(b.get("__raw"), RewindEdits.class);
				validate(p, e);
				p.replaceEdits(e);
				return ok("edits replaced (one undo step)");
			}));
			case "POST /clip" -> send(ex, 200, game(() -> {
				RewindPlayer p = player();
				if (b.has("add") && b.get("add").getAsBoolean()) {
					p.addClip();
				}
				if (b.has("index")) {
					p.selectClip(b.get("index").getAsInt());
				}
				RewindEdits.Clip c = p.edits.clip();
				if (b.has("name")) {
					c.name = b.get("name").getAsString();
				}
				if (b.has("hideNames")) {
					c.hideNames = b.get("hideNames").getAsBoolean();
				}
				if (b.has("background")) {
					String bg = b.get("background").getAsString();
					if (!RewindEdits.BACKGROUNDS.contains(bg)) {
						throw new ApiError(400, "background must be one of " + RewindEdits.BACKGROUNDS);
					}
					c.background = bg;
				}
				if (b.has("start") || b.has("length")) {
					int segLen = p.segmentLength(c.segment);
					int start = b.has("start") ? b.get("start").getAsInt() : c.start;
					int length = b.has("length") ? b.get("length").getAsInt() : c.length;
					c.start = Math.max(0, Math.min(segLen - 1, start));
					c.length = Math.max(1, Math.min(segLen - c.start, length));
					p.seekClip(0);
				}
				p.saveEdits();
				return status();
			}));
			case "POST /key" -> send(ex, 200, game(() -> {
				RewindPlayer p = player();
				RewindEdits.Clip c = p.edits.clip();
				String track = track(b);
				int tick = clamp(c, integer(b, "tick", p.clipPosition()));
				if (!b.has("value")) {
					throw new ApiError(400, "value missing");
				}
				RewindEdits.Key k = c.track(track).keys.computeIfAbsent(tick, t -> new RewindEdits.Key());
				k.value = b.get("value").getAsDouble();
				if (b.has("interp")) {
					String interp = b.get("interp").getAsString();
					if (!RewindEdits.INTERPOLATIONS.contains(interp)) {
						throw new ApiError(400, "interp must be one of " + RewindEdits.INTERPOLATIONS);
					}
					k.interp = interp;
				}
				if (b.has("label")) {
					k.label = b.get("label").getAsString();
				}
				p.saveEdits();
				return ok("key " + track + " @" + tick + " = " + k.value);
			}));
			case "POST /keys" -> send(ex, 200, game(() -> {
				// many at once: {"keys": [{"track","tick","value","interp"?,"label"?}, ...], "replace": false}
				RewindPlayer p = player();
				RewindEdits.Clip c = p.edits.clip();
				if (b.has("replace") && b.get("replace").getAsBoolean()) {
					c.tracks.values().forEach(t -> t.keys.clear());
				}
				int n = 0;
				for (JsonElement e : b.getAsJsonArray("keys")) {
					JsonObject o = e.getAsJsonObject();
					String track = track(o);
					RewindEdits.Key k = c.track(track).keys.computeIfAbsent(clamp(c, o.get("tick").getAsInt()), t -> new RewindEdits.Key());
					k.value = o.get("value").getAsDouble();
					if (o.has("interp") && RewindEdits.INTERPOLATIONS.contains(o.get("interp").getAsString())) {
						k.interp = o.get("interp").getAsString();
					}
					if (o.has("label")) {
						k.label = o.get("label").getAsString();
					}
					n++;
				}
				p.saveEdits();
				return ok(n + " keys set");
			}));
			case "POST /key/delete" -> send(ex, 200, game(() -> {
				RewindPlayer p = player();
				RewindEdits.Clip c = p.edits.clip();
				String track = track(b);
				RewindEdits.Track t = c.tracks.get(track);
				boolean removed = t != null && t.keys.remove(integer(b, "tick", -1)) != null;
				p.saveEdits();
				return ok(removed ? "removed" : "there was no key there");
			}));
			case "POST /global" -> send(ex, 200, game(() -> {
				RewindPlayer p = player();
				String track = track(b);
				JsonElement v = b.get("value");
				p.edits.clip().track(track).global = v == null || v.isJsonNull() ? null : v.getAsDouble();
				p.saveEdits();
				return ok(track + " clip value " + (v == null || v.isJsonNull() ? "cleared" : v.getAsDouble()));
			}));
			case "POST /seek" -> send(ex, 200, game(() -> {
				player().seekClip(integer(b, "tick", 0));
				return status();
			}));
			case "POST /play", "POST /pause" -> send(ex, 200, game(() -> {
				RewindPlayer p = player();
				if (p.paused() == path.equals("/play")) {
					p.togglePause();
				}
				return status();
			}));
			case "POST /speed" -> send(ex, 200, game(() -> {
				player().setSpeed((float) Math.max(0.1, Math.min(8, b.get("speed").getAsDouble())));
				return status();
			}));
			case "POST /undo", "POST /redo" -> send(ex, 200, game(() -> {
				RewindPlayer p = player();
				boolean done = path.equals("/undo") ? p.undo() : p.redo();
				return ok(done ? path.substring(1) + " done" : "nothing to " + path.substring(1));
			}));
			case "GET /frame" -> {
				List<BufferedImage> img = frames(List.of(integer(q, "tick", -1)), q);
				byte[] bytes = encode(img.get(0), q.getOrDefault("format", "png"));
				send(ex, 200, bytes, q.getOrDefault("format", "png").startsWith("jp") ? "image/jpeg" : "image/png");
			}
			case "GET /sheet" -> {
				int from = integer(q, "from", 0);
				int to = integer(q, "to", -1);
				int count = Math.max(1, Math.min(64, integer(q, "count", 12)));
				int end = to >= 0 ? to : game(() -> RewindPlayer.active() ? player().edits.clip().length : 0);
				List<Integer> ticks = new ArrayList<>();
				for (int i = 0; i < count; i++) {
					ticks.add(count == 1 ? from : from + (int) Math.round((end - from) * i / (double) (count - 1)));
				}
				List<BufferedImage> imgs = frames(ticks, q);
				byte[] bytes = encode(sheet(imgs, ticks, integer(q, "columns", Math.min(4, count))), q.getOrDefault("format", "jpg"));
				send(ex, 200, bytes, q.getOrDefault("format", "jpg").startsWith("jp") ? "image/jpeg" : "image/png");
			}
			case "GET /export" -> send(ex, 200, exportState());
			case "POST /export" -> send(ex, 200, game(() -> {
				player();
				if (RewindExport.busy() || capturing) {
					throw new ApiError(409, "busy (exporting or grabbing frames)");
				}
				RewindExport.Format f = format(b.get("format"));
				if (b.has("audio")) {
					String a = b.get("audio").getAsString().toLowerCase(java.util.Locale.ROOT);
					RewindExport.audioMode = a.startsWith("every") || a.startsWith("game") ? RewindExport.AUDIO_MODES.get(0)
							: a.startsWith("voice") ? RewindExport.AUDIO_MODES.get(1) : RewindExport.AUDIO_MODES.get(2);
				}
				RewindExport.start(f, integer(b, "size", 0), Math.max(10, Math.min(120, integer(b, "fps", 30))));
				return ok("export started - poll GET /export");
			}));
			case "POST /export/cancel" -> send(ex, 200, game(() -> {
				RewindExport.cancel();
				return exportState();
			}));
			// ---- beyond replays: the game itself ----
			case "POST /record" -> send(ex, 200, game(() -> record(b)));
			case "GET /player" -> send(ex, 200, game(RewindApi::playerState));
			case "GET /worlds" -> send(ex, 200, worlds());
			case "POST /join" -> send(ex, 200, game(() -> join(b)));
			case "POST /leave" -> send(ex, 200, game(() -> {
				Minecraft mc = Minecraft.getInstance();
				if (RewindPlayer.active()) {
					RewindPlayer.exit();
				} else if (mc.level != null) {
					mc.level.disconnect(net.minecraft.network.chat.Component.literal("Left (AI API)"));
					mc.disconnectWithSavingScreen();
					mc.setScreen(new TitleScreen());
				}
				return ok("left");
			}));
			case "POST /chat" -> send(ex, 200, game(() -> {
				Minecraft mc = Minecraft.getInstance();
				String msg = str(b, "message");
				if (mc.getConnection() == null) {
					throw new ApiError(409, "not in a world");
				}
				requirePlay();
				if (msg.startsWith("/")) {
					mc.getConnection().sendCommand(msg.substring(1));
				} else {
					mc.getConnection().sendChat(msg);
				}
				return ok("sent");
			}));
			case "GET /chat" -> send(ex, 200, chatLog(integer(q, "lines", 30)));
			case "POST /input" -> send(ex, 200, game(() -> input(b)));
			case "POST /look" -> send(ex, 200, game(() -> {
				var pl = Minecraft.getInstance().player;
				if (pl == null) {
					throw new ApiError(409, "not in a world");
				}
				requirePlay();
				float yaw = b.has("yaw") ? b.get("yaw").getAsFloat() : pl.getYRot() + (b.has("dyaw") ? b.get("dyaw").getAsFloat() : 0);
				float pitch = b.has("pitch") ? b.get("pitch").getAsFloat() : pl.getXRot() + (b.has("dpitch") ? b.get("dpitch").getAsFloat() : 0);
				pl.setYRot(yaw);
				pl.setXRot(Math.max(-90, Math.min(90, pitch)));
				pl.setYHeadRot(yaw);
				return playerState();
			}));
			case "POST /fly" -> send(ex, 200, game(() -> {
				var pl = Minecraft.getInstance().player;
				if (pl == null) {
					throw new ApiError(409, "not in a world");
				}
				requirePlay();
				if (!pl.getAbilities().mayfly) {
					throw new ApiError(409, "flying isn't allowed here (creative or spectator only)");
				}
				pl.getAbilities().flying = !b.has("on") || b.get("on").getAsBoolean();
				pl.onUpdateAbilities();
				return playerState();
			}));
			case "GET /inventory" -> send(ex, 200, game(RewindApi::inventory));
			case "POST /inventory/click" -> send(ex, 200, game(() -> {
				// {"slot": menu slot index, "button": 0 left / 1 right (or hotbar 0-8 for swap), "action": pickup|quick_move|swap|throw|pickup_all|clone}
				Minecraft mc = Minecraft.getInstance();
				if (mc.player == null) {
					throw new ApiError(409, "not in a world");
				}
				requirePlay();
				var menu = mc.player.containerMenu;
				int slot = integer(b, "slot", -999);
				String action = b.has("action") ? b.get("action").getAsString().toUpperCase(java.util.Locale.ROOT) : "PICKUP";
				net.minecraft.world.inventory.ContainerInput input;
				try {
					input = net.minecraft.world.inventory.ContainerInput.valueOf(action);
				} catch (IllegalArgumentException e) {
					throw new ApiError(400, "action: pickup, quick_move, swap, throw, pickup_all, clone");
				}
				mc.gameMode.handleContainerInput(menu.containerId, slot, integer(b, "button", 0), input, mc.player);
				return inventory();
			}));
			case "POST /drop" -> send(ex, 200, game(() -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player == null) {
					throw new ApiError(409, "not in a world");
				}
				if (b.has("slot")) {
					mc.player.getInventory().setSelectedSlot(Math.max(0, Math.min(8, b.get("slot").getAsInt())));
				}
				requirePlay();
				boolean all = b.has("all") && b.get("all").getAsBoolean();
				if (mc.gui.screen() != null) {
					throw new ApiError(409, "close the open screen first (POST /screen/close)");
				}
				// the inventory menu's hotbar slots are 36-44; THROW with button 1 throws the whole stack
				mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, 36 + mc.player.getInventory().getSelectedSlot(), all ? 1 : 0,
						net.minecraft.world.inventory.ContainerInput.THROW, mc.player);
				mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				return inventory();
			}));
			case "GET /resourcepacks" -> send(ex, 200, game(() -> {
				var repo = Minecraft.getInstance().getResourcePackRepository();
				JsonObject o = new JsonObject();
				o.add("available", GSON.toJsonTree(new ArrayList<>(repo.getAvailableIds())));
				o.add("enabled", GSON.toJsonTree(new ArrayList<>(repo.getSelectedIds())));
				return o;
			}));
			case "POST /resourcepacks" -> send(ex, 200, game(() -> {
				// {"enabled": [ids, bottom first]} - "vanilla" stays first either way
				Minecraft mc = Minecraft.getInstance();
				var repo = mc.getResourcePackRepository();
				repo.reload();
				List<String> want = new ArrayList<>();
				for (JsonElement e : b.getAsJsonArray("enabled")) {
					String id = e.getAsString();
					if (!repo.getAvailableIds().contains(id)) {
						throw new ApiError(404, "no resource pack '" + id + "' (GET /resourcepacks)");
					}
					want.add(id);
				}
				if (!want.contains("vanilla")) {
					want.add(0, "vanilla");
				}
				repo.setSelected(want);
				mc.options.updateResourcePacks(repo);
				return ok("resource packs set - the game reloads them");
			}));
			case "GET /shaders" -> send(ex, 200, shaders(null, null));
			case "POST /shaders" -> send(ex, 200, shaders(b.has("pack") ? b.get("pack").getAsString() : null,
					b.has("enabled") ? b.get("enabled").getAsBoolean() : null));
			case "GET /mods" -> send(ex, 200, mods());
			case "POST /mods" -> {
				// {"file": "<jar name>", "enabled": bool}: renames .jar <-> .jar.disabled in the mods folder (after a restart)
				Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("mods");
				String file = str(b, "file").replaceAll("\\.disabled$", "");
				if (file.contains("/") || file.contains("\\") || !file.endsWith(".jar")) {
					throw new ApiError(400, "file: a jar name from GET /mods");
				}
				boolean on = !b.has("enabled") || b.get("enabled").getAsBoolean();
				Path jar = dir.resolve(file);
				Path off = dir.resolve(file + ".disabled");
				if (on && Files.exists(off)) {
					Files.move(off, jar);
				} else if (!on && Files.exists(jar)) {
					Files.move(jar, off);
				} else if (!Files.exists(jar) && !Files.exists(off)) {
					throw new ApiError(404, "no mod file '" + file + "'");
				}
				send(ex, 200, ok(file + (on ? " on" : " off") + " - restart the game for it to take effect"));
			}
			case "GET /follow" -> send(ex, 200, game(RewindApi::followState));
			case "POST /follow" -> send(ex, 200, game(() -> {
				// {"target": "<uuid | player name | nearest-item | nearest-player | null>", "mode"?: behind|orbit|fixed,
				//  "distance"?, "height"?, "speed"? (orbit, degrees a second)} - replays only: the camera keeps it in view
				player();
				JsonElement t = b.get("target");
				if (t == null || t.isJsonNull()) {
					RewindApply.follow(null, null);
					return followState();
				}
				net.minecraft.world.entity.Entity target = findEntity(t.getAsString());
				if (b.has("distance")) {
					RewindApply.followDistance = Math.max(1, Math.min(64, b.get("distance").getAsDouble()));
				}
				if (b.has("height")) {
					RewindApply.followHeight = Math.max(-16, Math.min(32, b.get("height").getAsDouble()));
				}
				if (b.has("speed")) {
					RewindApply.followSpeed = Math.max(-180, Math.min(180, b.get("speed").getAsDouble()));
				}
				String mode = b.has("mode") ? b.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT) : null;
				if (mode != null && !List.of("behind", "orbit", "fixed").contains(mode)) {
					throw new ApiError(400, "mode: behind, orbit or fixed");
				}
				RewindApply.follow(target.getUUID(), mode);
				return followState();
			}));
			case "GET /entities" -> send(ex, 200, game(() -> entities(q.get("type"), integer(q, "limit", 200))));
			case "GET /players" -> send(ex, 200, game(() -> {
				RewindPlayer p = player();
				List<Object[]> rows = p.players();
				JsonObject o = new JsonObject();
				o.addProperty("ready", rows != null);
				JsonArray a = new JsonArray();
				if (rows != null) {
					for (Object[] r : rows) {
						JsonObject po = new JsonObject();
						po.addProperty("uuid", r[0].toString());
						po.addProperty("name", (String) r[1]);
						po.addProperty("firstTick", (int) r[2]);
						po.addProperty("lastTick", (int) r[3]);
						po.addProperty("self", (boolean) r[4]);
						a.add(po);
					}
				}
				o.add("players", a);
				o.addProperty("note", "ticks are absolute in the recording (clip ticks = tick - clip start, see GET /status)");
				return o;
			}));
			case "GET /audio" -> send(ex, 200, audioState());
			case "POST /audio" -> {
				// {"systemDevice"?, "micDevice"? ("" = automatic), "recordVoice"?, "recordMic"?, "recordSystem"?, "exportSound"?: everything|voice|none}
				if (b.has("systemDevice")) {
					RewindAudio.systemDevice = b.get("systemDevice").getAsString();
				}
				if (b.has("micDevice")) {
					RewindAudio.micDevice = b.get("micDevice").getAsString();
				}
				if (b.has("recordVoice")) {
					RewindAudio.recordVoice = b.get("recordVoice").getAsBoolean();
				}
				if (b.has("recordMic")) {
					RewindAudio.recordMic = b.get("recordMic").getAsBoolean();
				}
				if (b.has("recordSystem")) {
					RewindAudio.recordSystem = b.get("recordSystem").getAsBoolean();
				}
				if (b.has("exportSound")) {
					String a = b.get("exportSound").getAsString().toLowerCase(java.util.Locale.ROOT);
					RewindExport.audioMode = a.startsWith("every") || a.startsWith("game") ? RewindExport.AUDIO_MODES.get(0)
							: a.startsWith("voice") ? RewindExport.AUDIO_MODES.get(1) : RewindExport.AUDIO_MODES.get(2);
				}
				game(() -> {
					RewindModule.audioChanged();
					return null;
				});
				send(ex, 200, audioState());
			}
			case "GET /block" -> send(ex, 200, game(() -> blockAt(integer(q, "x", 0), integer(q, "y", 0), integer(q, "z", 0))));
			case "POST /block/break" -> send(ex, 200, game(() -> breakBlock(b)));
			case "POST /block/place" -> send(ex, 200, game(() -> placeBlock(b)));
			case "POST /build" -> send(ex, 200, game(() -> build(b)));
			case "GET /build" -> send(ex, 200, buildState());
			case "POST /screenshot" -> send(ex, 200, game(() -> {
				// the game's own screenshot (into screenshots/), with the HUD unless "hud": false
				Minecraft mc = Minecraft.getInstance();
				if (mc.level == null) {
					throw new ApiError(409, "not in a world");
				}
				boolean hud = !b.has("hud") || b.get("hud").getAsBoolean();
				boolean was = mc.gui.hud.isHidden();
				RewindExport.setHud(mc, !hud);
				String name = "ai-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".png";
				// next frame, so the HUD change is in it
				mc.execute(() -> mc.execute(() -> {
					net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, m -> { });
					RewindExport.setHud(mc, was);
				}));
				JsonObject o = ok("saved");
				o.addProperty("file", mc.gameDirectory.toPath().resolve("screenshots").resolve(name).toString());
				return o;
			}));
			case "POST /video" -> send(ex, 200, game(() -> {
				// {"seconds": 10, "hud"?: false, "format"?, "size"?, "fps"?, "audio"?} - an MP4 of the live view (velo-rewind/clips)
				Minecraft mc = Minecraft.getInstance();
				if (mc.player == null) {
					throw new ApiError(409, "not in a world");
				}
				if (RewindExport.busy() || capturing) {
					throw new ApiError(409, "busy (exporting or grabbing frames)");
				}
				if (b.has("audio")) {
					String a = b.get("audio").getAsString().toLowerCase(java.util.Locale.ROOT);
					RewindExport.audioMode = a.startsWith("every") || a.startsWith("game") ? RewindExport.AUDIO_MODES.get(0) : RewindExport.AUDIO_MODES.get(2);
				}
				RewindExport.startLive(format(b.get("format") == null ? new com.google.gson.JsonPrimitive("Window") : b.get("format")), integer(b, "size", 0),
						Math.max(10, Math.min(60, integer(b, "fps", 30))), b.has("seconds") ? b.get("seconds").getAsDouble() : 10,
						b.has("hud") && b.get("hud").getAsBoolean());
				return ok("recording - poll GET /export until DONE; output is the MP4");
			}));
			case "GET /modules" -> send(ex, 200, game(RewindApi::modules));
			case "POST /module" -> send(ex, 200, game(() -> module(b)));
			case "GET /screen" -> send(ex, 200, game(() -> {
				Screen sc = Minecraft.getInstance().gui.screen();
				JsonObject o = new JsonObject();
				o.addProperty("screen", sc == null ? null : sc.getClass().getSimpleName());
				o.addProperty("title", sc == null ? null : sc.getTitle().getString());
				return o;
			}));
			case "POST /screen/close" -> send(ex, 200, game(() -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.level != null) {
					mc.setScreen(null);
				}
				return ok("closed");
			}));
			case "POST /quit" -> {
				send(ex, 200, ok("closing the game (edits and recordings are saved)"));
				Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
			}
			default -> throw new ApiError(404, "unknown: " + method + " " + path + " (GET / lists everything)");
		}
	}

	// ---- the game: recording, worlds, chat, input, modules ----

	private static JsonObject record(JsonObject b) {
		String action = str(b, "action").toLowerCase(java.util.Locale.ROOT);
		if (!RewindModule.available()) {
			throw new ApiError(409, "the Velo Rewind module is off - POST /module {\"id\": \"velo-rewind\", \"enabled\": true}");
		}
		switch (action) {
			case "start" -> {
				if (!RewindRecorder.active()) {
					RewindRecorder.toggle();
				}
			}
			case "pause" -> {
				if (RewindRecorder.active() && !RewindRecorder.paused()) {
					RewindRecorder.togglePause();
				}
			}
			case "resume" -> {
				if (RewindRecorder.paused()) {
					RewindRecorder.togglePause();
				}
			}
			case "stop" -> {
				if (RewindRecorder.active()) {
					RewindRecorder.apiName = b.has("name") ? b.get("name").getAsString() : null;
					RewindRecorder.toggle();
				}
			}
			default -> throw new ApiError(400, "action: start, pause, resume or stop");
		}
		JsonObject o = new JsonObject();
		o.addProperty("recording", RewindRecorder.active());
		o.addProperty("paused", RewindRecorder.paused());
		o.addProperty("status", RewindRecorder.status());
		Path last = RewindRecorder.lastFile();
		o.addProperty("lastSaved", last == null ? null : last.getFileName().toString());
		return o;
	}

	// Playing through the API (moving, chatting, inventory) - at your own risk, and only where it can't be abuse on someone
	// else's server: singleplayer / your LAN world, a server where you're an operator, or a Velo Rewind replay. Watching,
	// recording, screenshots and client settings work everywhere.
	static boolean playAllowed() {
		Minecraft mc = Minecraft.getInstance();
		if (RewindPlayer.active() || mc.hasSingleplayerServer()) {
			return true;
		}
		return mc.player != null && mc.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}

	private static void requirePlay() {
		if (!playAllowed()) {
			throw new ApiError(403, "playing through the AI API only works in singleplayer or on servers where you're an operator"
					+ " (bots on other people's servers break most rules) - watching, recording and screenshots work everywhere");
		}
	}

	private static JsonObject playerState() {
		Minecraft mc = Minecraft.getInstance();
		JsonObject o = new JsonObject();
		var pl = mc.player;
		o.addProperty("inWorld", pl != null && mc.level != null);
		o.addProperty("server", mc.getCurrentServer() != null ? mc.getCurrentServer().ip : mc.hasSingleplayerServer() ? "singleplayer" : null);
		o.addProperty("replay", RewindPlayer.active());
		o.addProperty("controlAllowed", playAllowed());
		if (pl != null && mc.level != null) {
			o.addProperty("name", pl.getGameProfile().name());
			o.addProperty("x", pl.getX());
			o.addProperty("y", pl.getY());
			o.addProperty("z", pl.getZ());
			o.addProperty("yaw", pl.getYRot());
			o.addProperty("pitch", pl.getXRot());
			o.addProperty("health", pl.getHealth());
			o.addProperty("food", pl.getFoodData().getFoodLevel());
			o.addProperty("onGround", pl.onGround());
			o.addProperty("flying", pl.getAbilities().flying);
			o.addProperty("gameMode", mc.gameMode == null ? null : mc.gameMode.getPlayerMode().getName());
			o.addProperty("dimension", mc.level.dimension().identifier().toString());
			o.addProperty("holding", pl.getMainHandItem().getHoverName().getString());
		}
		Screen sc = mc.gui.screen();
		o.addProperty("screen", sc == null ? null : sc.getClass().getSimpleName());
		return o;
	}

	private static JsonArray worlds() throws IOException {
		JsonArray a = new JsonArray();
		Path saves = Minecraft.getInstance().gameDirectory.toPath().resolve("saves");
		if (Files.isDirectory(saves)) {
			try (Stream<Path> s = Files.list(saves)) {
				for (Path w : s.filter(f -> Files.exists(f.resolve("level.dat"))).sorted().toList()) {
					a.add(w.getFileName().toString());
				}
			}
		}
		return a;
	}

	private static JsonObject join(JsonObject b) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null || RewindPlayer.active()) {
			throw new ApiError(409, "already in a world - POST /leave first");
		}
		mc.options.pauseOnLostFocus = false; // a hidden window never has focus
		if (b.has("server")) {
			String address = b.get("server").getAsString();
			if (!net.minecraft.client.multiplayer.resolver.ServerAddress.isValidAddress(address)) {
				throw new ApiError(400, "not a server address: " + address);
			}
			net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
					net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
					new net.minecraft.client.multiplayer.ServerData(address, address, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
			return ok("connecting to " + address + " - poll GET /player until inWorld");
		}
		String world = str(b, "world");
		if (!Files.exists(mc.gameDirectory.toPath().resolve("saves").resolve(world).resolve("level.dat"))) {
			throw new ApiError(404, "no world folder '" + world + "' (GET /worlds)");
		}
		mc.createWorldOpenFlows().openWorld(world, () -> mc.setScreen(new TitleScreen()));
		return ok("opening " + world + " - poll GET /player until inWorld");
	}

	// The last chat lines (what the chat HUD shows).
	private static JsonArray chatLog(int lines) throws Exception {
		return game(() -> {
			JsonArray a = new JsonArray();
			List<String> all = new ArrayList<>();
			try {
				var chat = Minecraft.getInstance().gui.hud.getChat();
				var f = chat.getClass().getDeclaredField("allMessages");
				f.setAccessible(true);
				for (Object m : (List<?>) f.get(chat)) {
					var content = m.getClass().getMethod("content").invoke(m);
					all.add(((net.minecraft.network.chat.Component) content).getString());
				}
			} catch (ReflectiveOperationException e) {
				throw new ApiError(500, "can't read the chat: " + e);
			}
			// newest first in the game's list
			for (int i = Math.min(all.size(), Math.max(1, lines)) - 1; i >= 0; i--) {
				a.add(all.get(i));
			}
			return a;
		});
	}

	// Held keys (until their time is up) - set again every tick, since the game lets go of keys when it loses focus.
	private static final Map<String, Long> HELD = new java.util.concurrent.ConcurrentHashMap<>();
	private static boolean inputTicking;

	private static net.minecraft.client.KeyMapping key(String name) {
		var o = Minecraft.getInstance().options;
		return switch (name) {
			case "forward" -> o.keyUp;
			case "back" -> o.keyDown;
			case "left" -> o.keyLeft;
			case "right" -> o.keyRight;
			case "jump" -> o.keyJump;
			case "sneak" -> o.keyShift;
			case "sprint" -> o.keySprint;
			case "attack" -> o.keyAttack;
			case "use" -> o.keyUse;
			case "drop" -> o.keyDrop;
			case "inventory" -> o.keyInventory;
			case "swap" -> o.keySwapOffhand;
			default -> throw new ApiError(400, "unknown key '" + name + "': forward back left right jump sneak sprint attack use drop inventory swap");
		};
	}

	private static JsonObject input(JsonObject b) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			throw new ApiError(409, "not in a world");
		}
		requirePlay();
		mc.options.pauseOnLostFocus = false;
		if (!inputTicking) {
			inputTicking = true;
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(client -> holdKeys());
		}
		long ms = Math.max(50, Math.min(30_000, b.has("ms") ? b.get("ms").getAsLong() : 250));
		if (b.has("hold")) {
			for (JsonElement e : b.getAsJsonArray("hold")) {
				key(e.getAsString());
				HELD.put(e.getAsString(), System.currentTimeMillis() + ms);
			}
		}
		if (b.has("tap")) {
			for (JsonElement e : b.getAsJsonArray("tap")) {
				var k = key(e.getAsString());
				net.minecraft.client.KeyMapping.click(net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(k));
				HELD.put(e.getAsString(), System.currentTimeMillis() + 60);
			}
		}
		if (b.has("release")) {
			for (JsonElement e : b.getAsJsonArray("release")) {
				HELD.put(e.getAsString(), 0L);
			}
		}
		if (b.has("slot")) {
			mc.player.getInventory().setSelectedSlot(Math.max(0, Math.min(8, b.get("slot").getAsInt())));
		}
		JsonObject o = playerState();
		o.add("held", GSON.toJsonTree(HELD.keySet()));
		return o;
	}

	private static void holdKeys() {
		if (HELD.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (var e : List.copyOf(HELD.entrySet())) {
			boolean down = e.getValue() > now;
			try {
				key(e.getKey()).setDown(down);
			} catch (ApiError ignored) {
			}
			if (!down) {
				HELD.remove(e.getKey());
			}
		}
	}

	// ---- entities and following them ----

	private static net.minecraft.world.entity.Entity findEntity(String key) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			throw new ApiError(409, "not in a world");
		}
		net.minecraft.world.entity.Entity best = null;
		double bestD = Double.MAX_VALUE;
		var cam = mc.gameRenderer.mainCamera().position();
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player) {
				continue;
			}
			RewindPlayer rp = RewindPlayer.current();
			boolean match = switch (key.toLowerCase(java.util.Locale.ROOT)) {
				case "self", "me", "recorder" -> rp != null && (e.getUUID().equals(rp.replaySelfUuid()) || e.getId() == rp.selfEntityId());
				case "nearest-item" -> e instanceof net.minecraft.world.entity.item.ItemEntity;
				case "nearest-player" -> e instanceof net.minecraft.world.entity.player.Player;
				case "nearest" -> true;
				default -> e.getUUID().toString().equalsIgnoreCase(key) || String.valueOf(e.getId()).equals(key)
						|| rp != null && rp.replaySelfUuid() != null && e.getUUID().equals(rp.replaySelfUuid()) && key.equalsIgnoreCase(rp.recordedSelfUuid())
						|| e instanceof net.minecraft.world.entity.player.Player pl && pl.getGameProfile().name().equalsIgnoreCase(key);
			};
			if (match) {
				double d = e.position().distanceToSqr(cam);
				if (d < bestD) {
					bestD = d;
					best = e;
				}
			}
		}
		if (best == null) {
			throw new ApiError(404, "nothing matching '" + key + "' nearby (GET /entities)");
		}
		return best;
	}

	private static JsonArray entities(String type, int limit) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			throw new ApiError(409, "not in a world");
		}
		var cam = mc.gameRenderer.mainCamera().position();
		List<net.minecraft.world.entity.Entity> list = new ArrayList<>();
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player) {
				continue;
			}
			if (type != null && !type.isBlank()) {
				String t = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
				if (!(t.equals(type) || t.equals("minecraft:" + type))) {
					continue;
				}
			}
			list.add(e);
		}
		list.sort(java.util.Comparator.comparingDouble(e -> e.position().distanceToSqr(cam)));
		JsonArray a = new JsonArray();
		RewindPlayer rp = RewindPlayer.current();
		for (net.minecraft.world.entity.Entity e : list.subList(0, Math.min(Math.max(1, limit), list.size()))) {
			JsonObject o = new JsonObject();
			o.addProperty("uuid", e.getUUID().toString());
			o.addProperty("id", e.getId());
			o.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
			o.addProperty("name", e.getName().getString());
			if (e instanceof net.minecraft.world.entity.item.ItemEntity item) {
				o.addProperty("item", item.getItem().getCount() + "x " + item.getItem().getHoverName().getString());
			}
			o.addProperty("x", e.getX());
			o.addProperty("y", e.getY());
			o.addProperty("z", e.getZ());
			o.addProperty("distance", Math.sqrt(e.position().distanceToSqr(cam)));
			if (rp != null && (e.getUUID().equals(rp.replaySelfUuid()) || e.getId() == rp.selfEntityId())) {
				o.addProperty("self", true);
			}
			a.add(o);
		}
		return a;
	}

	private static JsonObject audioState() {
		JsonObject o = new JsonObject();
		o.addProperty("systemDevice", RewindAudio.systemDevice);
		o.addProperty("micDevice", RewindAudio.micDevice);
		o.add("systemDevices", GSON.toJsonTree(RewindAudio.systemDevices()));
		o.add("micDevices", GSON.toJsonTree(RewindAudio.micDevices()));
		o.addProperty("systemAudioAvailable", RewindAudio.loopbackAvailable());
		o.addProperty("recordVoice", RewindAudio.recordVoice);
		o.addProperty("recordMic", RewindAudio.recordMic);
		o.addProperty("recordSystem", RewindAudio.recordSystem);
		o.addProperty("exportSound", RewindExport.audioMode);
		o.addProperty("voicechatInstalled", net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voicechat"));
		return o;
	}

	private static JsonObject followState() {
		JsonObject o = new JsonObject();
		net.minecraft.world.entity.Entity e = RewindApply.followed();
		o.addProperty("target", RewindApply.followTarget == null ? null : RewindApply.followTarget.toString());
		o.addProperty("inRange", e != null);
		o.addProperty("name", e == null ? null : e.getName().getString());
		o.addProperty("mode", RewindApply.followMode);
		o.addProperty("distance", RewindApply.followDistance);
		o.addProperty("height", RewindApply.followHeight);
		o.addProperty("speed", RewindApply.followSpeed);
		return o;
	}

	// ---- building (singleplayer / operator only, like all playing) ----

	private static JsonObject blockAt(int x, int y, int z) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			throw new ApiError(409, "not in a world");
		}
		var state = mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
		JsonObject o = new JsonObject();
		o.addProperty("block", state.toString().replaceFirst("^Block\\{([^}]*)\\}", "$1"));
		o.addProperty("air", state.isAir());
		return o;
	}

	// Breaks like the player would: instantly in creative; in survival it looks at the block and keeps digging.
	private static JsonObject breakBlock(JsonObject b) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			throw new ApiError(409, "not in a world");
		}
		requirePlay();
		var pos = new net.minecraft.core.BlockPos(integer(b, "x", 0), integer(b, "y", 0), integer(b, "z", 0));
		if (mc.level.getBlockState(pos).isAir()) {
			return ok("already air");
		}
		if (mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 6) {
			throw new ApiError(409, "too far away to reach (6 blocks) - walk there, or use POST /build with commands");
		}
		lookAt(mc, net.minecraft.world.phys.Vec3.atCenterOf(pos));
		mc.gameMode.startDestroyBlock(pos, net.minecraft.core.Direction.UP);
		if (!mc.player.getAbilities().instabuild) {
			digging = pos;
			digUntil = System.currentTimeMillis() + 15_000;
			if (!digTicking) {
				digTicking = true;
				net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> dig());
			}
			return ok("digging - GET /block shows when it's gone");
		}
		return ok("broken");
	}

	private static net.minecraft.core.BlockPos digging;
	private static long digUntil;
	private static boolean digTicking;

	private static void dig() {
		Minecraft mc = Minecraft.getInstance();
		if (digging == null || mc.player == null || mc.level == null) {
			digging = null;
			return;
		}
		if (mc.level.getBlockState(digging).isAir() || System.currentTimeMillis() > digUntil) {
			digging = null;
			return;
		}
		mc.gameMode.continueDestroyBlock(digging, net.minecraft.core.Direction.UP);
		mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
	}

	// Places the held block (or the hotbar slot / item given) at x y z against a neighbouring block, like a right click.
	private static JsonObject placeBlock(JsonObject b) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			throw new ApiError(409, "not in a world");
		}
		requirePlay();
		var pos = new net.minecraft.core.BlockPos(integer(b, "x", 0), integer(b, "y", 0), integer(b, "z", 0));
		if (b.has("slot")) {
			mc.player.getInventory().setSelectedSlot(Math.max(0, Math.min(8, b.get("slot").getAsInt())));
		}
		if (mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 6) {
			throw new ApiError(409, "too far away to reach (6 blocks) - walk there, or use POST /build with commands");
		}
		if (!mc.level.getBlockState(pos).canBeReplaced()) {
			throw new ApiError(409, "there's a block there already");
		}
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			var against = pos.relative(d);
			if (mc.level.getBlockState(against).isAir() || mc.level.getBlockState(against).canBeReplaced()) {
				continue;
			}
			var face = d.getOpposite();
			var hit = net.minecraft.world.phys.Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			lookAt(mc, hit);
			var result = mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
					new net.minecraft.world.phys.BlockHitResult(hit, face, against, false));
			mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
			JsonObject o = ok(result.consumesAction() ? "placed " + mc.player.getMainHandItem().getHoverName().getString() : "nothing placed: " + result);
			o.add("now", blockAt(pos.getX(), pos.getY(), pos.getZ()));
			return o;
		}
		throw new ApiError(409, "nothing next to it to place against");
	}

	private static void lookAt(Minecraft mc, net.minecraft.world.phys.Vec3 at) {
		var eye = mc.player.getEyePosition();
		double dx = at.x - eye.x;
		double dy = at.y - eye.y;
		double dz = at.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.player.setYHeadRot(yaw);
	}

	// Big builds go through commands (/setblock, /fill) - they need command rights (operator / cheats on), and are sent
	// a few per tick so the server isn't flooded.
	private static final java.util.concurrent.ConcurrentLinkedQueue<String> BUILD = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static volatile int buildTotal;
	private static boolean buildTicking;
	private static final int BUILD_PER_TICK = 40;

	private static JsonObject build(JsonObject b) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getConnection() == null) {
			throw new ApiError(409, "not in a world");
		}
		requirePlay();
		if (!mc.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
			throw new ApiError(403, "building with commands needs operator rights (singleplayer: cheats on, or /op)");
		}
		List<String> cmds = new ArrayList<>();
		int ox = integer(b, "x", mc.player.blockPosition().getX());
		int oy = integer(b, "y", mc.player.blockPosition().getY());
		int oz = integer(b, "z", mc.player.blockPosition().getZ());
		if (b.has("blocks")) {
			// [{"x","y","z","block": "minecraft:stone" or with state "minecraft:oak_stairs[facing=east]"}, ...] relative to x y z
			for (JsonElement e : b.getAsJsonArray("blocks")) {
				JsonObject o = e.getAsJsonObject();
				cmds.add("setblock " + (ox + o.get("x").getAsInt()) + " " + (oy + o.get("y").getAsInt()) + " " + (oz + o.get("z").getAsInt()) + " "
						+ blockArg(o.get("block").getAsString()));
			}
		}
		if (b.has("fill")) {
			// [{"from": [x,y,z], "to": [x,y,z], "block", "mode"?: replace|hollow|outline|keep|destroy}] relative to x y z
			for (JsonElement e : b.getAsJsonArray("fill")) {
				JsonObject o = e.getAsJsonObject();
				JsonArray f = o.getAsJsonArray("from");
				JsonArray t = o.getAsJsonArray("to");
				String mode = o.has("mode") ? " " + o.get("mode").getAsString().replaceAll("[^a-z]", "") : "";
				cmds.addAll(fillCommands(ox + f.get(0).getAsInt(), oy + f.get(1).getAsInt(), oz + f.get(2).getAsInt(),
						ox + t.get(0).getAsInt(), oy + t.get(1).getAsInt(), oz + t.get(2).getAsInt(), blockArg(o.get("block").getAsString()), mode));
			}
		}
		if (b.has("schematic")) {
			// a vanilla structure (.nbt), Sponge (.schem) or Litematica (.litematic) file - relative paths: the game's schematics folder
			Path file = Path.of(b.get("schematic").getAsString());
			if (!file.isAbsolute()) {
				file = mc.gameDirectory.toPath().resolve("schematics").resolve(file.toString());
			}
			if (!Files.exists(file)) {
				throw new ApiError(404, "no schematic " + file);
			}
			boolean air = b.has("includeAir") && b.get("includeAir").getAsBoolean();
			List<Schematics.Block> read;
			try {
				read = Schematics.read(file);
			} catch (IOException e) {
				throw new ApiError(400, "can't read " + file.getFileName() + ": " + e.getMessage());
			}
			for (var block : read) {
				if (!air && block.state().equals("minecraft:air")) {
					continue;
				}
				cmds.add("setblock " + (ox + block.x()) + " " + (oy + block.y()) + " " + (oz + block.z()) + " " + block.state());
			}
		}
		if (cmds.isEmpty()) {
			throw new ApiError(400, "give blocks, fill or schematic");
		}
		if (cmds.size() > 200_000) {
			throw new ApiError(400, cmds.size() + " blocks - 200000 at most per build");
		}
		BUILD.addAll(cmds);
		buildTotal += cmds.size();
		if (!buildTicking) {
			buildTicking = true;
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (client.getConnection() == null) {
					BUILD.clear();
					buildTotal = 0;
					return;
				}
				for (int i = 0; i < BUILD_PER_TICK; i++) {
					String c = BUILD.poll();
					if (c == null) {
						buildTotal = 0;
						break;
					}
					client.getConnection().sendCommand(c);
				}
			});
		}
		JsonObject o = ok(cmds.size() + " commands queued (" + BUILD_PER_TICK * 20 + " a second) - GET /build shows what's left");
		o.addProperty("queued", BUILD.size());
		return o;
	}

	private static JsonObject buildState() {
		JsonObject o = new JsonObject();
		o.addProperty("left", BUILD.size());
		o.addProperty("total", buildTotal);
		return o;
	}

	// /fill is limited to 32768 blocks: bigger boxes are cut into slabs.
	private static List<String> fillCommands(int x1, int y1, int z1, int x2, int y2, int z2, String block, String mode) {
		List<String> out = new ArrayList<>();
		int minX = Math.min(x1, x2);
		int maxX = Math.max(x1, x2);
		int minY = Math.min(y1, y2);
		int maxY = Math.max(y1, y2);
		int minZ = Math.min(z1, z2);
		int maxZ = Math.max(z1, z2);
		long layer = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
		int step = (int) Math.max(1, Math.min(maxY - minY + 1, 32768 / Math.max(1, layer)));
		if (layer > 32768 || !mode.isEmpty() && step < maxY - minY + 1) {
			// too wide for one layer, or a hollow / outline box that can't be split: plain setblocks would be too many
			if (layer > 32768) {
				throw new ApiError(400, "a fill layer is over 32768 blocks - split it");
			}
			throw new ApiError(400, "a " + mode.trim() + " fill over 32768 blocks can't be split - use a smaller box");
		}
		for (int y = minY; y <= maxY; y += step) {
			out.add("fill " + minX + " " + y + " " + minZ + " " + maxX + " " + Math.min(maxY, y + step - 1) + " " + maxZ + " " + block + mode);
		}
		return out;
	}

	private static String blockArg(String block) {
		if (!block.matches("[a-z0-9_.:\\-]+(\\[[a-z0-9_=,:.\\-]*\\])?(\\{.*\\})?")) {
			throw new ApiError(400, "not a block: " + block);
		}
		return block.contains(":") ? block : "minecraft:" + block;
	}

	private static JsonObject inventory() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			throw new ApiError(409, "not in a world");
		}
		var menu = mc.player.containerMenu;
		JsonObject o = new JsonObject();
		o.addProperty("menu", menu.getClass().getSimpleName());
		o.addProperty("open", mc.gui.screen() != null);
		o.addProperty("selectedHotbar", mc.player.getInventory().getSelectedSlot());
		o.addProperty("carried", menu.getCarried().isEmpty() ? null : stack(menu.getCarried()));
		JsonArray slots = new JsonArray();
		for (int i = 0; i < menu.slots.size(); i++) {
			var slot = menu.slots.get(i);
			if (slot.getItem().isEmpty()) {
				continue;
			}
			JsonObject so = new JsonObject();
			so.addProperty("slot", i);
			so.addProperty("item", stack(slot.getItem()));
			so.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
			so.addProperty("count", slot.getItem().getCount());
			so.addProperty("playerInventory", slot.container == mc.player.getInventory());
			so.addProperty("inventoryIndex", slot.getContainerSlot());
			slots.add(so);
		}
		o.add("slots", slots);
		return o;
	}

	private static String stack(net.minecraft.world.item.ItemStack s) {
		return s.getCount() + "x " + s.getHoverName().getString();
	}

	// Iris shader packs (when Iris is installed), by reflection; Velo Shaders are a module (POST /module).
	private static JsonObject shaders(String pack, Boolean enabled) throws Exception {
		JsonObject o = new JsonObject();
		o.addProperty("iris", net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris"));
		var velo = net.veloclient.velo.module.ModuleRegistry.all().stream().filter(m -> m.displayName().toLowerCase(java.util.Locale.ROOT).contains("shader")).toList();
		JsonArray vs = new JsonArray();
		velo.forEach(m -> vs.add(m.id() + (m.isEnabled() ? " (on)" : " (off)")));
		o.add("veloShaderModules", vs);
		if (!o.get("iris").getAsBoolean()) {
			if (pack != null || enabled != null) {
				throw new ApiError(409, "Iris isn't installed - Velo's own shaders are modules: POST /module");
			}
			return o;
		}
		return game(() -> {
			try {
				Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
				Object config = iris.getMethod("getIrisConfig").invoke(null);
				Path dir = (Path) iris.getMethod("getShaderpacksDirectory").invoke(null);
				if (pack != null) {
					if (!Files.exists(dir.resolve(pack))) {
						throw new ApiError(404, "no shader pack '" + pack + "' in " + dir);
					}
					config.getClass().getMethod("setShaderPackName", String.class).invoke(config, pack);
				}
				if (enabled != null) {
					config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
				}
				if (pack != null || enabled != null) {
					config.getClass().getMethod("save").invoke(config);
					iris.getMethod("reload").invoke(null);
				}
				o.addProperty("enabled", (boolean) config.getClass().getMethod("areShadersEnabled").invoke(config));
				Object name = ((java.util.Optional<?>) config.getClass().getMethod("getShaderPackName").invoke(config)).orElse(null);
				o.addProperty("pack", name == null ? null : name.toString());
				JsonArray packs = new JsonArray();
				if (Files.isDirectory(dir)) {
					try (Stream<Path> st = Files.list(dir)) {
						st.map(f -> f.getFileName().toString()).sorted().forEach(packs::add);
					}
				}
				o.add("packs", packs);
				return o;
			} catch (ApiError e) {
				throw e;
			} catch (Exception e) {
				throw new ApiError(500, "Iris: " + e);
			}
		});
	}

	private static JsonObject mods() throws IOException {
		JsonObject o = new JsonObject();
		JsonArray loaded = new JsonArray();
		for (var m : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
			var meta = m.getMetadata();
			if (m.getContainingMod().isPresent() || meta.getId().startsWith("fabric-") || meta.getId().equals("java")) {
				continue; // bundled libraries
			}
			loaded.add(meta.getId() + " " + meta.getVersion().getFriendlyString());
		}
		o.add("loaded", loaded);
		JsonArray files = new JsonArray();
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("mods");
		if (Files.isDirectory(dir)) {
			try (Stream<Path> st = Files.list(dir)) {
				for (Path f : st.sorted().toList()) {
					String n = f.getFileName().toString();
					if (n.endsWith(".jar") || n.endsWith(".jar.disabled")) {
						JsonObject fo = new JsonObject();
						fo.addProperty("file", n.replaceAll("\\.disabled$", ""));
						fo.addProperty("enabled", n.endsWith(".jar"));
						files.add(fo);
					}
				}
			}
		}
		o.add("files", files);
		return o;
	}

	private static JsonArray modules() {
		JsonArray a = new JsonArray();
		for (var m : net.veloclient.velo.module.ModuleRegistry.all()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", m.id());
			o.addProperty("name", m.displayName());
			o.addProperty("category", m.category().name());
			o.addProperty("enabled", m.isEnabled());
			if (m instanceof net.veloclient.velo.module.Configurable c) {
				JsonObject settings = new JsonObject();
				for (var f : c.configFields()) {
					switch (f) {
						case net.veloclient.velo.module.ConfigField.ToggleField t -> settings.addProperty(t.label(), t.get().getAsBoolean());
						case net.veloclient.velo.module.ConfigField.SliderField sl -> settings.addProperty(sl.label(), sl.get().getAsDouble());
						case net.veloclient.velo.module.ConfigField.ChoiceField ch -> settings.addProperty(ch.label(), ch.get().get());
						case net.veloclient.velo.module.ConfigField.TextField tx -> settings.addProperty(tx.label(), tx.get().get());
						case net.veloclient.velo.module.ConfigField.ColorField co -> settings.addProperty(co.label(), String.format("#%08X", co.get().getAsInt()));
						default -> {
						}
					}
				}
				o.add("settings", settings);
			}
			a.add(o);
		}
		return a;
	}

	// {"id", "enabled"?, "settings"?: {label: value}}; saved to the profile like the settings screen does.
	private static JsonObject module(JsonObject b) {
		String id = str(b, "id");
		var m = net.veloclient.velo.module.ModuleRegistry.get(id).orElseThrow(() -> new ApiError(404, "no module '" + id + "' (GET /modules)"));
		if (b.has("settings") && m instanceof net.veloclient.velo.module.Configurable c) {
			for (var e : b.getAsJsonObject("settings").entrySet()) {
				var f = c.configFields().stream().filter(x -> x.label().equalsIgnoreCase(e.getKey())).findFirst()
						.orElseThrow(() -> new ApiError(400, "module '" + id + "' has no setting '" + e.getKey() + "'"));
				JsonElement v = e.getValue();
				switch (f) {
					case net.veloclient.velo.module.ConfigField.ToggleField t -> t.set().accept(v.getAsBoolean());
					case net.veloclient.velo.module.ConfigField.SliderField sl -> sl.set().accept(Math.max(sl.min(), Math.min(sl.max(), v.getAsDouble())));
					case net.veloclient.velo.module.ConfigField.ChoiceField ch -> {
						String want = v.getAsString();
						String match = ch.options().stream().filter(x -> x.equalsIgnoreCase(want)).findFirst()
								.orElseThrow(() -> new ApiError(400, e.getKey() + " must be one of " + ch.options()));
						ch.set().accept(match);
					}
					case net.veloclient.velo.module.ConfigField.TextField tx -> tx.set().accept(v.getAsString());
					case net.veloclient.velo.module.ConfigField.ColorField co -> co.set().accept((int) Long.parseLong(v.getAsString().replace("#", ""), 16));
					default -> throw new ApiError(400, "'" + e.getKey() + "' can't be set through the API");
				}
			}
		}
		if (b.has("enabled")) {
			m.setEnabled(b.get("enabled").getAsBoolean());
		}
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		JsonObject o = ok(m.displayName() + (m.isEnabled() ? " on" : " off"));
		o.addProperty("enabled", m.isEnabled());
		return o;
	}

	// ---- answers ----

	private static JsonObject status() {
		Minecraft mc = Minecraft.getInstance();
		JsonObject o = new JsonObject();
		JsonObject rec = new JsonObject();
		rec.addProperty("active", RewindRecorder.active());
		rec.addProperty("paused", RewindRecorder.paused());
		o.add("recording", rec);
		RewindPlayer p = RewindPlayer.current();
		if (p == null) {
			o.add("replay", null);
		} else {
			JsonObject r = new JsonObject();
			r.addProperty("file", p.file().getFileName().toString());
			r.addProperty("ready", mc.level != null && mc.player != null);
			r.addProperty("ticksPerSecond", 20);
			r.addProperty("selfUuid", p.replaySelfUuid() == null ? null : p.replaySelfUuid().toString());
			r.addProperty("selfEntityId", p.selfEntityId());
			r.addProperty("selectedClip", p.edits.selected);
			JsonArray clips = new JsonArray();
			for (RewindEdits.Clip c : p.edits.clips) {
				JsonObject co = new JsonObject();
				co.addProperty("name", c.name);
				co.addProperty("segment", c.segment);
				co.addProperty("start", c.start);
				co.addProperty("length", c.length);
				co.addProperty("segmentLength", p.segmentLength(c.segment));
				co.addProperty("background", c.background == null ? "World" : c.background);
				clips.add(co);
			}
			r.add("clips", clips);
			r.addProperty("position", p.clipPosition());
			r.addProperty("paused", p.paused());
			r.addProperty("speed", p.speed());
			r.addProperty("canUndo", p.autosave.canUndo());
			r.add("voices", GSON.toJsonTree(RewindSound.voiceTracks()));
			r.addProperty("canRedo", p.autosave.canRedo());
			if (mc.player != null) {
				var cam = mc.gameRenderer.mainCamera();
				JsonObject c = new JsonObject();
				c.addProperty("x", cam.position().x);
				c.addProperty("y", cam.position().y);
				c.addProperty("z", cam.position().z);
				c.addProperty("yaw", cam.yRot());
				c.addProperty("pitch", cam.xRot());
				r.add("camera", c);
			}
			o.add("replay", r);
		}
		o.add("export", exportState());
		Screen s = mc.gui.screen();
		o.addProperty("screen", s == null ? null : s.getClass().getSimpleName());
		return o;
	}

	private static JsonObject exportState() {
		JsonObject e = new JsonObject();
		e.addProperty("state", RewindExport.state.name());
		e.addProperty("progress", RewindExport.progress);
		e.addProperty("message", RewindExport.message);
		e.addProperty("output", RewindExport.output == null ? null : RewindExport.output.toString());
		return e;
	}

	private static JsonObject properties() {
		JsonObject o = new JsonObject();
		o.add("tracks", GSON.toJsonTree(RewindEdits.PROPERTIES));
		o.add("interpolations", GSON.toJsonTree(RewindEdits.INTERPOLATIONS));
		o.add("backgrounds", GSON.toJsonTree(RewindEdits.BACKGROUNDS));
		JsonArray formats = new JsonArray();
		for (RewindExport.Format f : RewindExport.FORMATS) {
			JsonObject fo = new JsonObject();
			fo.addProperty("name", f.name());
			fo.add("sizes", GSON.toJsonTree(f.sizes()));
			formats.add(fo);
		}
		o.add("exportFormats", formats);
		return o;
	}

	private static JsonArray recordings() throws IOException {
		JsonArray a = new JsonArray();
		Path dir = folder();
		if (!Files.isDirectory(dir)) {
			return a;
		}
		try (Stream<Path> s = Files.list(dir)) {
			for (Path f : s.filter(x -> x.getFileName().toString().endsWith(".vrewind")).sorted().toList()) {
				JsonObject o = new JsonObject();
				o.addProperty("file", f.getFileName().toString());
				o.addProperty("bytes", Files.size(f));
				o.addProperty("modified", Files.getLastModifiedTime(f).toMillis());
				try (RewindReader r = new RewindReader(f)) {
					o.add("meta", JsonParser.parseString(r.meta()));
				} catch (Exception e) {
					o.addProperty("error", e.toString());
				}
				a.add(o);
			}
		}
		return a;
	}

	// ---- frames: seek, hide the HUD, wait for the world to settle, read the frame back ----

	private static volatile boolean capturing;
	private static final ArrayDeque<Object[]> JOBS = new ArrayDeque<>();
	private static Screen savedScreen;
	private static boolean savedHud;
	private static boolean savedPreview;
	private static boolean savedPauseOnFocus;
	private static int waitFrames;
	private static long seekedAt;
	private static Object[] job;

	// One image per tick (in clip ticks; -1 = where the playhead is), in the export format's shape.
	private static List<BufferedImage> frames(List<Integer> ticks, Map<String, String> q) throws Exception {
		int width = Math.max(64, Math.min(1920, integer(q, "width", 640)));
		boolean path = !"free".equals(q.get("camera"));
		RewindExport.Format format = q.containsKey("format_frame") ? format(new com.google.gson.JsonPrimitive(q.get("format_frame"))) : null;
		List<CompletableFuture<BufferedImage>> out = new ArrayList<>();
		game(() -> {
			if (RewindExport.busy() || capturing) {
				throw new ApiError(409, "busy (exporting or grabbing frames)");
			}
			if (Minecraft.getInstance().player == null) {
				throw new ApiError(409, "not in a world (or the replay isn't loaded yet)");
			}
			double aspect = format != null ? format.aspect() : RewindEditorScreen.frameAspect();
			for (int t : ticks) {
				CompletableFuture<BufferedImage> f = new CompletableFuture<>();
				out.add(f);
				JOBS.add(new Object[] {t, f, width, aspect});
			}
			begin(path);
			return null;
		});
		List<BufferedImage> imgs = new ArrayList<>();
		for (CompletableFuture<BufferedImage> f : out) {
			imgs.add(f.get(20, TimeUnit.SECONDS));
		}
		return imgs;
	}

	private static void begin(boolean cameraPath) {
		Minecraft mc = Minecraft.getInstance();
		capturing = true;
		savedScreen = mc.gui.screen();
		savedHud = mc.gui.hud.isHidden();
		savedPreview = RewindApply.previewCamera;
		savedPauseOnFocus = mc.options.pauseOnLostFocus;
		mc.options.pauseOnLostFocus = false;
		RewindApply.previewCamera = cameraPath;
		RewindPlayer p = RewindPlayer.current();
		if (p != null && !p.paused()) {
			p.togglePause();
		}
		mc.setScreen(null);
		RewindExport.setHud(mc, true);
		next();
		ClipRecorder.frameHook = RewindApi::onFrame;
	}

	private static void next() {
		job = JOBS.poll();
		if (job == null) {
			end();
			return;
		}
		RewindPlayer p = RewindPlayer.current();
		int t = (int) job[0];
		if (p != null && t >= 0 && t != p.clipPosition()) {
			p.seekClip(t);
			waitFrames = 4;
			seekedAt = System.nanoTime();
		} else {
			waitFrames = 2;
			seekedAt = System.nanoTime() - 200_000_000L;
		}
	}

	private static void onFrame() {
		Object[] j = job;
		if (j == null) {
			return;
		}
		if (Minecraft.getInstance().player == null) {
			fail(new ApiError(409, "left the world"));
			return;
		}
		// a few frames and three game ticks after a jump: entities have moved to where they were
		if (--waitFrames > 0 || System.nanoTime() - seekedAt < 160_000_000L) {
			return;
		}
		@SuppressWarnings("unchecked")
		CompletableFuture<BufferedImage> future = (CompletableFuture<BufferedImage>) j[1];
		int width = (int) j[2];
		double aspect = (double) j[3];
		boolean took = FrameCapture.grab((rgba, w, h) -> {
			try {
				future.complete(toImage(rgba, w, h, width, aspect));
			} catch (Throwable t) {
				future.completeExceptionally(t);
			} finally {
				FrameCapture.release(rgba);
			}
		}, 1080);
		if (took) {
			job = null;
			Minecraft.getInstance().execute(RewindApi::next);
		}
	}

	private static void fail(Exception e) {
		if (job != null) {
			((CompletableFuture<?>) job[1]).completeExceptionally(e);
		}
		for (Object[] j : JOBS) {
			((CompletableFuture<?>) j[1]).completeExceptionally(e);
		}
		JOBS.clear();
		job = null;
		end();
	}

	private static void end() {
		Minecraft mc = Minecraft.getInstance();
		if (ClipRecorder.frameHook == (Runnable) RewindApi::onFrame || capturing) {
			ClipRecorder.frameHook = null;
		}
		RewindExport.setHud(mc, savedHud);
		RewindApply.previewCamera = savedPreview;
		mc.options.pauseOnLostFocus = savedPauseOnFocus;
		if (savedScreen != null && RewindPlayer.active()) {
			mc.setScreen(savedScreen);
		}
		savedScreen = null;
		capturing = false;
	}

	// bottom-up RGBA -> the format's shape, centred, scaled to width
	private static BufferedImage toImage(ByteBuffer rgba, int w, int h, int width, double aspect) {
		int cw = w;
		int ch = h;
		if (aspect > 0) {
			if (w / (double) h > aspect) {
				cw = (int) Math.round(h * aspect);
			} else {
				ch = (int) Math.round(w / aspect);
			}
		}
		int ox = (w - cw) / 2;
		int oy = (h - ch) / 2;
		BufferedImage full = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
		int[] row = new int[cw];
		for (int y = 0; y < ch; y++) {
			int src = ((h - 1 - (y + oy)) * w + ox) * 4;
			for (int x = 0; x < cw; x++) {
				int i = src + x * 4;
				row[x] = (rgba.get(i) & 0xFF) << 16 | (rgba.get(i + 1) & 0xFF) << 8 | (rgba.get(i + 2) & 0xFF);
			}
			full.setRGB(0, y, cw, 1, row, 0, cw);
		}
		int height = Math.max(1, (int) Math.round(width * ch / (double) cw));
		if (width == cw) {
			return full;
		}
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(full, 0, 0, width, height, null);
		g.dispose();
		return out;
	}

	// a grid of frames, each labelled with its clip tick and time
	private static BufferedImage sheet(List<BufferedImage> imgs, List<Integer> ticks, int columns) {
		int cw = imgs.get(0).getWidth();
		int ch = imgs.get(0).getHeight();
		int cols = Math.max(1, Math.min(columns, imgs.size()));
		int rows = (imgs.size() + cols - 1) / cols;
		int gap = 4;
		BufferedImage out = new BufferedImage(cols * cw + (cols + 1) * gap, rows * ch + (rows + 1) * gap, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new java.awt.Color(16, 16, 21));
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, Math.max(11, cw / 28)));
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		for (int i = 0; i < imgs.size(); i++) {
			int x = gap + (i % cols) * (cw + gap);
			int y = gap + (i / cols) * (ch + gap);
			g.drawImage(imgs.get(i), x, y, null);
			int t = ticks.get(i);
			String label = "tick " + t + "  " + String.format("%d:%04.1f", t / 1200, (t % 1200) / 20.0);
			g.setColor(new java.awt.Color(0, 0, 0, 160));
			g.fillRect(x, y, g.getFontMetrics().stringWidth(label) + 10, g.getFontMetrics().getHeight() + 4);
			g.setColor(java.awt.Color.WHITE);
			g.drawString(label, x + 5, y + g.getFontMetrics().getAscent() + 2);
		}
		g.dispose();
		return out;
	}

	private static byte[] encode(BufferedImage img, String format) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, format.startsWith("jp") ? "jpg" : "png", out);
		return out.toByteArray();
	}

	// ---- helpers ----

	private static final class ApiError extends RuntimeException {
		final int status;

		ApiError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private static <T> T game(Supplier<T> task) throws Exception {
		try {
			return Minecraft.getInstance().submit(task).get(15, TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException e) {
			if (e.getCause() instanceof RuntimeException r) {
				throw r;
			}
			throw e;
		}
	}

	private static RewindPlayer player() {
		RewindPlayer p = RewindPlayer.current();
		if (p == null) {
			throw new ApiError(409, "no replay open - POST /open {\"file\": \"...\"}");
		}
		return p;
	}

	private static void validate(RewindPlayer p, RewindEdits e) {
		if (e == null || e.clips == null || e.clips.isEmpty()) {
			throw new ApiError(400, "edits need at least one clip");
		}
		for (RewindEdits.Clip c : e.clips) {
			if (c.segment < 0 || c.segment >= p.segmentCount()) {
				throw new ApiError(400, "clip '" + c.name + "': segment " + c.segment + " doesn't exist (0.." + (p.segmentCount() - 1) + ")");
			}
			int segLen = p.segmentLength(c.segment);
			c.start = Math.max(0, Math.min(segLen - 1, c.start));
			c.length = Math.max(1, Math.min(segLen - c.start, c.length));
			if (c.tracks == null) {
				c.tracks = new LinkedHashMap<>();
			}
			for (String id : c.tracks.keySet()) {
				if (RewindEdits.property(id) == null) {
					throw new ApiError(400, "unknown track '" + id + "' (GET /properties)");
				}
			}
			if (c.hidden == null) {
				c.hidden = new ArrayList<>();
			}
			if (c.skins == null) {
				c.skins = new LinkedHashMap<>();
			}
		}
	}

	private static String track(JsonObject b) {
		String t = str(b, "track");
		if (RewindEdits.property(t) == null) {
			throw new ApiError(400, "unknown track '" + t + "' (GET /properties)");
		}
		return t;
	}

	private static int clamp(RewindEdits.Clip c, int tick) {
		return Math.max(0, Math.min(c.length, tick));
	}

	private static RewindExport.Format format(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return RewindExport.FORMATS.get(0);
		}
		if (e.getAsJsonPrimitive().isNumber()) {
			return RewindExport.FORMATS.get(Math.max(0, Math.min(RewindExport.FORMATS.size() - 1, e.getAsInt())));
		}
		String s = e.getAsString().toLowerCase(java.util.Locale.ROOT);
		for (RewindExport.Format f : RewindExport.FORMATS) {
			if (f.name().toLowerCase(java.util.Locale.ROOT).startsWith(s) || f.name().toLowerCase(java.util.Locale.ROOT).contains(s)) {
				return f;
			}
		}
		throw new ApiError(400, "unknown format '" + e.getAsString() + "' (GET /properties)");
	}

	static Path resolve(String file) {
		Path p = Path.of(file);
		if (!p.isAbsolute()) {
			p = folder().resolve(file.endsWith(".vrewind") ? file : file + ".vrewind");
		}
		return p;
	}

	private static String str(JsonObject b, String key) {
		if (!b.has(key) || b.get(key).isJsonNull()) {
			throw new ApiError(400, key + " missing");
		}
		return b.get(key).getAsString();
	}

	private static int integer(JsonObject b, String key, int def) {
		return b.has(key) && !b.get(key).isJsonNull() ? b.get(key).getAsInt() : def;
	}

	private static int integer(Map<String, String> q, String key, int def) {
		try {
			return q.containsKey(key) ? Integer.parseInt(q.get(key)) : def;
		} catch (NumberFormatException e) {
			throw new ApiError(400, key + " must be a whole number");
		}
	}

	private static boolean authorized(HttpExchange ex) {
		String h = ex.getRequestHeaders().getFirst("Authorization");
		String given = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : query(ex).get("token");
		return token != null && given != null && java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
				given.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, String> query(HttpExchange ex) {
		Map<String, String> m = new LinkedHashMap<>();
		String q = ex.getRequestURI().getRawQuery();
		if (q != null) {
			for (String part : q.split("&")) {
				int i = part.indexOf('=');
				if (i > 0) {
					m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
				}
			}
		}
		return m;
	}

	private static JsonObject ok(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("message", message);
		return o;
	}

	private static JsonObject error(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("error", message);
		return o;
	}

	private static byte[] text(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static void send(HttpExchange ex, int status, JsonElement json) throws IOException {
		send(ex, status, text(GSON.toJson(json)), "application/json");
	}

	private static void send(HttpExchange ex, int status, byte[] body, String type) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type + (type.startsWith("image") ? "" : "; charset=utf-8"));
		ex.sendResponseHeaders(status, body.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}

	// ---- AI.md: what an agent reads first ----

	private static final String DOCS = """
			# Velo Client - AI access: the complete guide

			Read this once, top to bottom. Everything you need is here; nothing else has to be guessed.

			## 0. Connection
			- Base URL: http://127.0.0.1:{PORT} (only this PC can connect).
			- Every request needs the header `Authorization: Bearer <token>`. The token and URL are in
			  `velo-rewind/api.json` in the game folder (the Velo launcher's Settings > AI Access > Copy prompt shows them too).
			- Send and receive JSON (`Content-Type: application/json`). Images come back as PNG/JPEG bytes.
			- Errors always look like `{"ok": false, "error": "<what is wrong and what to do>"}` with an HTTP status:
			  400 = your request is wrong, 401 = token missing/wrong, 403 = not allowed here, 404 = not found,
			  409 = wrong state right now (no replay open, not in a world, busy) - the message says what to do first.
			- Requests run on the game thread one after another; one request at a time is best.

			## 1. Quick start (do this every session)
			1. `GET /status` - is a replay open (`replay` not null)? is it loaded (`replay.ready`)? export state?
			2. `GET /player` - in a world? which server? `controlAllowed` (may you move / chat / build here?).
			3. No replay open and you need one: `GET /recordings`, then `POST /open {"file": "<name>", "leave": true}`
			   and poll `GET /status` every second until `replay.ready` is true (usually 2-10 s).
			4. Look before you change anything: `GET /sheet?count=12&width=480` (one JPEG with 12 labelled frames).
			5. Change, look again, export. Edits save themselves - there is no "save" call.

			## 2. Rules that never change
			- Time is in game ticks: 20 ticks = 1 second. Inside a clip, ticks count from the clip's start (0 .. clip length).
			  `GET /players` ticks are absolute (whole recording); clip tick = absolute tick - clip start.
			- Positions are Minecraft world coordinates (x, y, z; y = up). Yaw: 0 = looking south (+Z), 90 = west (-X),
			  180 / -180 = north, -90 = east. Pitch: -90 = straight up, 0 = level, 90 = straight down.
			- The recording player (the account that recorded) has a DIFFERENT uuid inside the replay: use
			  `replay.selfUuid` / `replay.selfEntityId` from `GET /status`, or simply the target `"self"`.
			- A replay is made of clips (each its own timeline). Most endpoints work on the selected clip
			  (`replay.selectedClip`); switch with `POST /clip {"index": n}`.
			- Every track has a clip value (`global`, for the whole clip) and/or keyframes; keyframes win where they exist.
			- Undo with `POST /undo` if a change made it worse.
			- Playing (moving, chat/commands, inventory, building) only works in singleplayer, on servers where this account is
			  an operator, or in a replay - otherwise 403. Watching, recording, screenshots, frames and settings work everywhere.

			## 3. Replays: open, look, edit
			| Call | Body / query | Returns |
			|---|---|---|
			| `GET /recordings` | | `[{"file", "bytes", "meta": {"ticks", "server", "segments"...}}]` |
			| `POST /open` | `{"file": "name or full path", "leave"?: true}` | starts loading - poll `/status` |
			| `POST /exit` | | back to the title screen |
			| `GET /status` | | `replay`: file, ready, clips[{name, segment, start, length, background}], position, paused, speed, camera{x,y,z,yaw,pitch}, voices, selfUuid, selfEntityId |
			| `GET /players` | | every player in the whole recording: uuid, name, firstTick, lastTick, self (first call starts a scan - poll until `ready`) |
			| `GET /entities` | `?type=player&limit=200` | nearest entities: uuid, id, type, name, item, x/y/z, distance, self |
			| `GET /frame` | `?tick=120&width=1280&format=png&camera=path|free&format_frame=TikTok` | one image (1-2 s) |
			| `GET /sheet` | `?from=0&to=200&count=12&columns=4&width=480` | one JPEG grid of frames, each labelled with its tick |
			| `POST /seek` | `{"tick": 40}` | jumps inside the clip |
			| `POST /play` / `POST /pause` / `POST /speed` | `{"speed": 0.5}` | playback |
			| `GET /edits` / `PUT /edits` | whole edits JSON | everything at once (clips, tracks, hidden, skins, hideParts) |
			| `POST /clip` | `{"index"?, "add"?: true, "name"?, "start"?, "length"?, "background"?, "hideNames"?}` | select / add / trim a clip, set background, hide all names |
			| `POST /key` | `{"track", "tick", "value", "interp"?, "label"?}` | one keyframe |
			| `POST /keys` | `{"keys": [{"track","tick","value"}, ...], "replace"?: true}` | many keyframes (replace clears the clip's keys first) |
			| `POST /key/delete` | `{"track", "tick"}` | |
			| `POST /global` | `{"track", "value"}` (`null` clears) | a value for the whole clip |
			| `POST /follow` | `{"target": "self" or player name or uuid or entity id or "nearest-player" or "nearest-item", "mode"?: "behind"|"orbit"|"fixed", "distance"?: 4.5, "height"?: 1.2, "speed"?: 20}` | camera keeps the target in view and moves with it (`{"target": null}` stops) |
			| `POST /undo` / `POST /redo` | | |

			Tracks (ids for /key, /keys, /global):
			- Camera: `cam_x`, `cam_y`, `cam_z`, `cam_yaw`, `cam_pitch`, `cam_roll`, `fov`
			- World: `time` (0-24000; 6000 = noon, 13000 = night), `rain`, `thunder` (0-1), `fog` (multiplier)
			- Playback: `speed` (0.1-8)
			- Audio multipliers (1 = as recorded, up to 2): `volume`, `vol_players`, `vol_hostile`, `vol_neutral`, `vol_blocks`,
			  `vol_weather`, `vol_ambient`, `vol_records`, `vol_music`, `vol_voice`, `vol_mic`, `vol_system`, `voice:<uuid>`
			Interpolation (`interp`): `Smooth` (default, curved path), `Linear`, `Ease in`, `Ease out`, `Hold`.
			A camera keyframe needs all five: cam_x, cam_y, cam_z, cam_yaw, cam_pitch (same tick). Tip: fly or `/follow`,
			read `replay.camera` from `/status`, and key exactly those numbers.

			Hiding things (in `PUT /edits`, per clip):
			- `hideNames: true` - every name tag, the score line under names and name holograms (ranks, money). Use this for videos.
			- `hidden: ["<uuid>", ...]` - entities left out of the video (still shown, outlined red, while editing).
			- `hideParts: {"<uuid>": ["nametag", "cape", "cosmetics", "pets", "glow"]}` - parts of one player.
			- `skins: {"<uuid>": "<player name or PNG path>"}` - swap a skin.
			- `background`: `World`, `No terrain`, `Green screen`, `Blue screen`, `Black`.

			## 4. Export
			| Call | Body | Returns |
			|---|---|---|
			| `POST /export` | `{"format": "TikTok" or "YouTube" or "Square" or "Window", "size": 0|1|2, "fps": 30|60, "audio": "voice"|"everything"|"none"}` | starts; poll `GET /export` |
			| `GET /export` | | `{"state": IDLE|PREPARING|RENDERING|FINISHING|DONE|FAILED, "progress": 0-1, "message", "output": "<mp4 path>"}` |
			| `POST /export/cancel` | | stops (keeps what's rendered) |
			Sizes: TikTok 0 = 720x1280, 1 = 1080x1920; YouTube 0 = 1280x720, 1 = 1920x1080, 2 = 2560x1440; Square 0 = 720, 1 = 1080.
			- `"audio": "voice"` or `"none"` renders FRAME BY FRAME: exact timing, never a lost frame, sound in sync, however
			  long it takes. Use this for every video.
			- `"audio": "everything"` records what the speakers play and therefore runs in real time (can drop frames on a
			  slow PC). Only when you really need game sounds and a loopback device works (see `GET /audio`).
			- The video never shows HUD, chat, menus or outlines - only the world.
			- Poll `GET /export` every 2 s. DONE -> `output` is the MP4. FAILED -> `message` says why.

			## 5. Sound settings
			- `GET /audio`: recordVoice / recordMic / recordSystem, chosen devices, every device found, export sound mode.
			- `POST /audio {"systemDevice"?, "micDevice"? ("" = automatic), "recordVoice"?, "recordMic"?, "recordSystem"?, "exportSound"?}`.
			  Windows: system audio needs "Stereo Mix" or a virtual cable - choose it from `systemDevices`.

			## 6. The game itself
			| Call | Body | Notes |
			|---|---|---|
			| `GET /player` | | inWorld, server, controlAllowed, position, rotation, health, food, gameMode, dimension, holding, screen |
			| `GET /worlds` / `POST /join` | `{"world": "<folder>"}` or `{"server": "host:port"}` | poll `/player` until `inWorld` |
			| `POST /leave` | | leaves the world / replay |
			| `POST /record` | `{"action": "start"|"pause"|"resume"|"stop", "name"?}` | Velo Rewind recording (module must be on: `POST /module {"id": "velo-rewind", "enabled": true}`) |
			| `POST /chat` | `{"message": "hi" or "/command"}` | controlAllowed only |
			| `GET /chat` | `?lines=30` | last chat lines |
			| `POST /input` | `{"hold": ["forward","sprint"], "ms": 800, "tap": ["jump"], "release": [...], "slot": 0-8}` | keys: forward back left right jump sneak sprint attack use drop inventory swap |
			| `POST /look` | `{"yaw", "pitch"}` or `{"dyaw", "dpitch"}` | |
			| `POST /fly` | `{"on": true}` | creative / spectator |
			| `GET /inventory` / `POST /inventory/click` / `POST /drop` | `{"slot", "button", "action": pickup|quick_move|swap|throw|pickup_all|clone}` / `{"all"?, "slot"?}` | |
			| `GET /block` / `POST /block/break` / `POST /block/place` | `?x&y&z` / `{"x","y","z"}` / `{"x","y","z","slot"?}` | within 6 blocks |
			| `POST /build` | `{"x","y","z", "blocks"?: [{"x","y","z","block"}], "fill"?: [{"from":[..],"to":[..],"block","mode"?}], "schematic"?: "file.nbt|.schem|.litematic", "includeAir"?}` | needs command rights; `GET /build` = queue |
			| `POST /screenshot` | `{"hud"?: true}` | into screenshots/ |
			| `POST /video` | `{"seconds": 10, "format"?: "TikTok", "fps"?: 30, "hud"?: false, "audio"?: "none"}` | first-person MP4 of what happens now; poll `GET /export` |
			| `GET /modules` / `POST /module` | `{"id", "enabled"?, "settings"?: {"<label>": value}}` | Velo modules |
			| `GET /resourcepacks` / `POST /resourcepacks` | `{"enabled": ["vanilla", "file/<pack>.zip"]}` | |
			| `GET /shaders` / `POST /shaders` | `{"pack"?, "enabled"?}` | Iris |
			| `GET /mods` / `POST /mods` | `{"file": "<jar>", "enabled": bool}` | after a restart |
			| `GET /screen` / `POST /screen/close` | | |
			| `POST /quit` | | closes the game, saving everything |

			## 7. Recipes
			**A TikTok of a fight from a recording**
			1. `POST /open {"file": "fight", "leave": true}`; poll `/status` until ready.
			2. `GET /players` (poll until ready) - pick the fighter; note the clip start from `/status`.
			3. `PUT /edits` with the current edits plus `"hideNames": true` on the clip (or `POST /clip {"hideNames": true}`).
			4. Trim: `POST /clip {"start": <tick>, "length": <ticks>}`.
			5. Camera: `POST /follow {"target": "<name>", "mode": "behind", "distance": 4}` - or keyframes with `/keys`.
			6. Check: `GET /sheet?count=12&width=360&format_frame=TikTok`. Fix, check again.
			7. `POST /export {"format": "TikTok", "size": 1, "fps": 60, "audio": "voice"}`; poll `/export` until DONE.

			**A smooth camera move without follow**: `GET /status` -> `replay.camera` at the start; `POST /keys` with the five
			cam_* tracks at tick 0 and the end tick (and a few in between); `GET /sheet` to check; keyframes are Smooth by default.

			**Record something yourself**: `POST /module {"id": "velo-rewind", "enabled": true}`, `POST /join`, `POST /record
			{"action": "start"}`, ..., `POST /record {"action": "stop", "name": "my-take"}` - it's then in `GET /recordings`.

			## 8. Running without a window
			- The Velo launcher can start a profile hidden (Settings > AI Access > Start the game hidden) and from the command line:
			  `"Velo Client Launcher" --launch <profile id or name>` (a running launcher takes it over).
			- Or start Java with `-Dvelo.aiApi=<port> -Dvelo.hiddenWindow=true` and optionally `-Dvelo.rewindOpen=<file>`.
			- A hidden game never grabs the mouse. `POST /quit` closes it cleanly.

			## 9. When something goes wrong
			- 409 "no replay open" -> `POST /open`. 409 "not loaded yet" -> wait and poll `/status`.
			- 409 "busy (exporting or grabbing frames)" -> poll `GET /export` until it's not RENDERING.
			- 404 "nothing matching" for /follow -> `GET /entities?type=player` (only what's near the camera) or seek first.
			- 403 -> you're on someone else's server without operator rights: only watch / record there.
			- A frame looks wrong -> `GET /frame?camera=free` shows the free camera instead of the keyed path.
			- Export FAILED -> read `message`; try `"audio": "voice"`.
			""";

}
*///?}
