package net.veloclient.velo.dev.notes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.RemoteTextureLoader;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.config.VeloPaths;
import net.veloclient.velo.dev.storage.StorageStore;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Every World Note the client knows: your private ones (in {@code config/world-notes.json}, never uploaded) and the
 * shared ones you can see on the server you're on (fetched from the Velo server, refreshed when someone changes one).
 * All changes to shared notes go through here; callbacks run on the game thread.
 */
public final class NoteStore {

	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** Someone mentioned you in a note (any server). */
	public record Mention(String id, String title, String ownerName, String server, String dim, double x, double y, double z) {
	}

	private static final class Local {
		List<Note> notes = new ArrayList<>();
		Set<String> hidden = new LinkedHashSet<>();
	}

	private static Local local;
	private static final Map<String, Note> SHARED = new LinkedHashMap<>();
	/** Dev-only (screenshot tour): notes "shared by someone" without a server. */
	private static final List<Note> TOUR = new ArrayList<>();
	private static final List<Mention> INBOX = new ArrayList<>();
	private static String sharedFor;
	private static long lastFetch;
	private static boolean fetching;
	private static boolean refreshWanted;
	private static boolean blocked;
	private static int version;
	private static final Map<String, Identifier> TEXTURES = new HashMap<>();
	private static final Set<String> TEXTURE_PENDING = new LinkedHashSet<>();

	private NoteStore() {
	}

	public static int version() {
		return version;
	}

	public static boolean blocked() {
		return blocked;
	}

	private static Local local() {
		if (local == null) {
			Path file = file();
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					local = GSON.fromJson(reader, Local.class);
				} catch (Exception ignored) {
					local = null;
				}
			}
			if (local == null) {
				local = new Local();
			}
			if (local.notes == null) {
				local.notes = new ArrayList<>();
			}
			if (local.hidden == null) {
				local.hidden = new LinkedHashSet<>();
			}
		}
		return local;
	}

	private static Path file() {
		return VeloPaths.config().resolve("world-notes.json");
	}

	private static void saveLocal() {
		version++;
		try {
			Files.createDirectories(file().getParent());
			Path temp = file().resolveSibling("world-notes.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(local(), writer);
			}
			Files.move(temp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception ignored) {
			// next save tries again
		}
	}

	// ---- Where you are ----

	public static String serverKey() {
		return StorageStore.currentServerKey();
	}

	public static String worldKey() {
		return StorageStore.currentWorldKey();
	}

	/** Shared notes need a server everyone can find again (not a singleplayer save name). */
	public static boolean canShareHere() {
		String key = serverKey();
		return key != null && (key.startsWith("mp:") || key.startsWith("realm:")) && SocialClient.myUuidPublic() != null;
	}

	// ---- Queries ----

	/** Every note on the server you're on (any world / dimension), private first. */
	public static List<Note> onServer() {
		String server = serverKey();
		List<Note> out = new ArrayList<>();
		if (server == null) {
			return out;
		}
		for (Note note : local().notes) {
			if (server.equals(note.server)) {
				out.add(note);
			}
		}
		if (server.equals(sharedFor)) {
			out.addAll(SHARED.values());
		}
		for (Note note : TOUR) {
			if (server.equals(note.server)) {
				out.add(note);
			}
		}
		return out;
	}

	/** Notes in the world and dimension you're standing in (what gets drawn). */
	public static List<Note> here() {
		String world = worldKey();
		String dim = StorageStore.here();
		List<Note> out = new ArrayList<>();
		for (Note note : onServer()) {
			if (world != null && world.equals(note.world) && dim != null && dim.equals(note.dim) && !hidden(note)) {
				out.add(note);
			}
		}
		return out;
	}

	/** Private notes everywhere (for the All list). */
	public static List<Note> allLocal() {
		return List.copyOf(local().notes);
	}

	/** Dev-only (screenshot tour). */
	public static void tourAdd(Note note) {
		TOUR.add(note);
		version++;
	}

	public static List<Mention> inbox() {
		return List.copyOf(INBOX);
	}

	public static Note byId(String id) {
		for (Note note : local().notes) {
			if (note.id.equals(id)) {
				return note;
			}
		}
		return SHARED.get(id);
	}

	public static boolean hidden(Note note) {
		return !note.mine && local().hidden.contains(note.id);
	}

	public static void setHidden(Note note, boolean hide) {
		if (hide) {
			local().hidden.add(note.id);
		} else {
			local().hidden.remove(note.id);
		}
		saveLocal();
	}

	// ---- New notes ----

	/** A new private note a little in front of you, facing you. */
	public static Note draft() {
		Note note = new Note();
		note.id = "local-" + UUID.randomUUID().toString().substring(0, 8);
		note.local = true;
		note.mine = true;
		note.ownerName = NotesMc.myName();
		note.server = serverKey();
		note.world = worldKey();
		note.dim = StorageStore.here();
		note.created = System.currentTimeMillis();
		note.style = NotesModule.defaultStyle();
		note.color = NotesModule.defaultColor();
		double[] eye = NotesMc.eye();
		if (eye != null) {
			double dx = eye[3];
			double dz = eye[5];
			double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
			note.x = eye[0] + dx / len * 2.2;
			note.y = eye[1] - 0.15;
			note.z = eye[2] + dz / len * 2.2;
		}
		return note;
	}

	// ---- Saving ----

	/** Saves a note: private ones locally, shared ones on the server (sharing / un-sharing moves it). */
	public static void save(Note draft, Consumer<Note> done, Consumer<String> error) {
		if (draft.local) {
			Note existing = byId(draft.id);
			if (existing != null && !existing.local) {
				// Shared -> private: keep a private copy, take it off the server.
				Note copy = draft.copy();
				copy.id = "local-" + UUID.randomUUID().toString().substring(0, 8);
				copy.people = new ArrayList<>();
				copy.names = new LinkedHashMap<>();
				if (copy.image > 0) {
					copy.png = null;
					copy.image = 0;
				}
				post("/v1/notes/delete", idBody(existing.id), json -> {
					SHARED.remove(existing.id);
					storeLocal(copy);
					done.accept(copy);
				}, error);
				return;
			}
			draft.updated = System.currentTimeMillis();
			storeLocal(draft);
			done.accept(draft);
			return;
		}
		if (!canShareHere()) {
			error.accept("Shared notes need a multiplayer server and your Velo account");
			return;
		}
		boolean creating = draft.id == null || draft.id.startsWith("local-");
		JsonObject input = input(draft, creating);
		JsonObject body = new JsonObject();
		body.add("note", input);
		post("/v1/notes/save", body, json -> {
			Note saved = fromView(json);
			if (creating) {
				// Private -> shared: bring the drawing and picture along, then drop the private copy.
				String oldId = draft.id;
				local().notes.removeIf(n -> n.id.equals(oldId));
				saveLocal();
				if (!draft.board.isEmpty()) {
					board(saved, draft.board, false, n -> { }, error);
				}
				if (draft.png != null) {
					try {
						image(saved, Base64.getDecoder().decode(draft.png), n -> { }, error);
					} catch (IllegalArgumentException ignored) {
						// broken local picture: leave it
					}
				}
			}
			putShared(saved);
			done.accept(saved);
		}, error);
	}

	private static void storeLocal(Note note) {
		note.local = true;
		note.mine = true;
		note.canEdit = true;
		local().notes.removeIf(n -> n.id.equals(note.id));
		local().notes.add(note);
		saveLocal();
	}

	private static JsonObject input(Note note, boolean creating) {
		JsonObject o = new JsonObject();
		if (!creating) {
			o.addProperty("id", note.id);
		}
		o.addProperty("server", note.server);
		o.addProperty("world", note.world);
		o.addProperty("dim", note.dim);
		o.addProperty("x", note.x);
		o.addProperty("y", note.y);
		o.addProperty("z", note.z);
		o.addProperty("title", note.title == null ? "" : note.title);
		o.addProperty("text", note.text == null ? "" : note.text);
		o.addProperty("color", note.color & 0xFFFFFF);
		if (note.icon != null) {
			o.addProperty("icon", note.icon);
		} else {
			o.addProperty("icon", "");
		}
		o.addProperty("style", note.style);
		o.addProperty("size", note.size);
		o.addProperty("face", note.face);
		o.addProperty("yaw", note.yaw);
		o.add("checks", GSON.toJsonTree(note.checks));
		if (note.mine) {
			o.addProperty("share", note.share);
			o.add("people", GSON.toJsonTree(note.people));
			o.addProperty("edit", note.edit);
		}
		return o;
	}

	public static void check(Note note, Note.Check check, boolean done, Consumer<String> error) {
		check.done = done;
		check.doneBy = done ? NotesMc.myName() : null;
		check.doneAt = done ? System.currentTimeMillis() : 0;
		if (note.local) {
			saveLocal();
			return;
		}
		JsonObject body = idBody(note.id);
		body.addProperty("check", check.id);
		body.addProperty("done", done);
		post("/v1/notes/check", body, json -> putShared(fromView(json)), error);
	}

	/** Adds strokes to the board ({@code clear} empties it first). */
	public static void board(Note note, List<Note.Stroke> strokes, boolean clear, Consumer<Note> done, Consumer<String> error) {
		if (note.local) {
			if (clear) {
				note.board.clear();
			}
			note.board.addAll(strokes);
			saveLocal();
			done.accept(note);
			return;
		}
		JsonObject body = idBody(note.id);
		body.add("strokes", GSON.toJsonTree(strokes));
		body.addProperty("clear", clear);
		post("/v1/notes/board", body, json -> {
			Note saved = fromView(json);
			putShared(saved);
			done.accept(saved);
		}, error);
	}

	/** Sets ({@code png}) or removes ({@code null}) the note's picture. */
	public static void image(Note note, byte[] png, Consumer<Note> done, Consumer<String> error) {
		if (note.local) {
			note.png = png == null ? null : Base64.getEncoder().encodeToString(png);
			note.image = png == null ? 0 : note.image + 1;
			saveLocal();
			done.accept(note);
			return;
		}
		JsonObject body = idBody(note.id);
		if (png != null) {
			body.addProperty("png", Base64.getEncoder().encodeToString(png));
		}
		post("/v1/notes/image", body, json -> {
			Note saved = fromView(json);
			putShared(saved);
			done.accept(saved);
		}, error);
	}

	public static void delete(Note note, Runnable done, Consumer<String> error) {
		if (note.local) {
			local().notes.removeIf(n -> n.id.equals(note.id));
			saveLocal();
			done.run();
			return;
		}
		post(note.mine ? "/v1/notes/delete" : "/v1/notes/leave", idBody(note.id), json -> {
			SHARED.remove(note.id);
			version++;
			done.run();
		}, error);
	}

	public static void report(Note note, String category, String reason, Runnable done, Consumer<String> error) {
		JsonObject body = idBody(note.id);
		body.addProperty("category", category);
		body.addProperty("reason", reason);
		post("/v1/notes/report", body, json -> done.run(), error);
	}

	/** You opened it: the mention badge goes away. */
	public static void seen(Note note) {
		if (note.local || !note.unread) {
			return;
		}
		note.unread = false;
		INBOX.removeIf(m -> m.id().equals(note.id));
		version++;
		post("/v1/notes/seen", idBody(note.id), json -> { }, e -> { });
	}

	// ---- Pictures ----

	/** The note's picture as a texture, or null while loading / when there's none (or pictures from others are off). */
	public static Identifier texture(Note note) {
		if (note.image == 0 || !note.mine && !NotesModule.othersPictures()) {
			return null;
		}
		String key = note.id + ":" + note.image;
		Identifier texture = TEXTURES.get(key);
		if (texture != null || TEXTURE_PENDING.contains(key)) {
			return texture;
		}
		TEXTURE_PENDING.add(key);
		if (note.local) {
			if (note.png != null) {
				try {
					byte[] bytes = Base64.getDecoder().decode(note.png);
					NoteImages.remember(key, bytes);
					RemoteTextureLoader.fromBytes("note-" + key, bytes, id -> TEXTURES.put(key, id));
				} catch (IllegalArgumentException ignored) {
					// broken picture
				}
			}
			return null;
		}
		SocialClient.apiGet("/v1/notes/image-data?id=" + note.id).whenComplete((json, error) -> {
			if (json != null && json.has("png")) {
				try {
					byte[] bytes = Base64.getDecoder().decode(json.get("png").getAsString());
					NoteImages.remember(key, bytes);
					RemoteTextureLoader.fromBytes("note-" + key, bytes, id -> TEXTURES.put(key, id));
				} catch (IllegalArgumentException ignored) {
					// broken picture
				}
			}
		});
		return null;
	}

	// ---- Syncing ----

	public static void requestRefresh() {
		refreshWanted = true;
	}

	/** Called every tick: keeps the shared notes of the server you're on fresh. */
	static void tick() {
		String server = serverKey();
		if (server == null || !canShareHere() || !NotesModule.sharedOn()) {
			if (sharedFor != null) {
				SHARED.clear();
				sharedFor = null;
				version++;
			}
			return;
		}
		long now = System.currentTimeMillis();
		boolean due = !server.equals(sharedFor) || refreshWanted && now - lastFetch > 700 || now - lastFetch > 90_000;
		if (!due || fetching) {
			return;
		}
		fetching = true;
		refreshWanted = false;
		lastFetch = now;
		SocialClient.apiGet("/v1/notes/list?server=" + java.net.URLEncoder.encode(server, StandardCharsets.UTF_8)).whenComplete((json, error) ->
				MinecraftClient.getInstance().execute(() -> {
					fetching = false;
					if (json == null || !server.equals(serverKey())) {
						return;
					}
					SHARED.clear();
					for (JsonElement element : json.getAsJsonArray("notes")) {
						Note note = fromView(element.getAsJsonObject());
						SHARED.put(note.id, note);
					}
					INBOX.clear();
					if (json.has("inbox")) {
						for (JsonElement element : json.getAsJsonArray("inbox")) {
							INBOX.add(GSON.fromJson(element, Mention.class));
						}
					}
					blocked = json.has("blocked") && json.get("blocked").getAsBoolean();
					sharedFor = server;
					version++;
				}));
	}

	/** Server push: a note changed on some server, or someone mentioned you. */
	static void onEvent(String type, JsonObject data) {
		MinecraftClient.getInstance().execute(() -> {
			if ("notes".equals(type)) {
				String server = data.has("server") ? data.get("server").getAsString() : null;
				if (server != null && server.equals(sharedFor)) {
					requestRefresh();
				}
				return;
			}
			if ("note_mention".equals(type) && NotesModule.mentionPings()) {
				Mention mention = new Mention(str(data, "id"), str(data, "title"), str(data, "from"), str(data, "server"), str(data, "dim"),
						num(data, "x"), num(data, "y"), num(data, "z"));
				INBOX.removeIf(m -> m.id().equals(mention.id()));
				INBOX.add(0, mention);
				version++;
				requestRefresh();
				String where = mention.server() != null && mention.server().equals(serverKey()) ? "here - press to see it"
						: mention.server() == null ? "" : "on " + mention.server().replaceFirst("^mp:", "");
				net.veloclient.velo.client.social.NotificationOverlay.show(new net.veloclient.velo.client.social.NotificationOverlay.Toast(null, null,
						mention.ownerName() + " mentioned you", "\"" + mention.title() + "\" " + where, 7000,
						() -> NotesScreens.openMention(mention), null, null));
			}
		});
	}

	private static void putShared(Note note) {
		SHARED.put(note.id, note);
		version++;
	}

	private static Note fromView(JsonObject view) {
		Note note = GSON.fromJson(view.get("note"), Note.class);
		note.local = false;
		note.mine = view.has("mine") && view.get("mine").getAsBoolean();
		note.canEdit = view.has("canEdit") && view.get("canEdit").getAsBoolean();
		note.unread = view.has("unread") && view.get("unread").getAsBoolean();
		if (note.checks == null) {
			note.checks = new ArrayList<>();
		}
		if (note.board == null) {
			note.board = new ArrayList<>();
		}
		if (note.people == null) {
			note.people = new ArrayList<>();
		}
		if (note.names == null) {
			note.names = new LinkedHashMap<>();
		}
		if (note.style == null || !Note.STYLES.contains(note.style)) {
			note.style = "paper";
		}
		return note;
	}

	private static JsonObject idBody(String id) {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		return body;
	}

	private static void post(String path, JsonObject body, Consumer<JsonObject> ok, Consumer<String> error) {
		SocialClient.apiPost(path, body).whenComplete((json, throwable) -> MinecraftClient.getInstance().execute(() -> {
			if (throwable != null) {
				Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
				error.accept(cause.getMessage() == null ? "Couldn't reach the Velo server" : cause.getMessage());
			} else {
				ok.accept(json);
			}
		}));
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static double num(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
	}
}
