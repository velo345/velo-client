package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * World Notes that players share: a note sits at a spot in a Minecraft world (server + backend world + position) and
 * holds a title, text with @mentions, a checklist everyone it's shared with can tick, a small whiteboard (strokes) and
 * optionally one picture. Private notes never reach the server - only shared ones live here.
 *
 * <p>Who sees a note: its owner, the people it's shared with (picked friends / the party at the time) and - when shared
 * with "friends" - every friend of the owner. Editing text / board is the owner's unless they let everyone edit;
 * ticking the checklist is for everyone who sees it. Mentions only reach people who can see the note, at most once per
 * note every few minutes. Everything is size-capped and rate-limited, players can report notes, staff can list, read
 * and remove any note and block players from notes (see {@link ModerationService#NOTE}).
 *
 * <p>Persists in {@code notes.json}, pictures in {@code notes-images/<id>.png}.
 */
final class NotesService {

	static final int MAX_TITLE = 60;
	static final int MAX_TEXT = 2000;
	static final int MAX_CHECKS = 60;
	static final int MAX_CHECK_TEXT = 140;
	static final int MAX_STROKES = 400;
	static final int MAX_POINTS = 8000;
	static final int MAX_MENTIONS = 8;
	static final int MAX_PEOPLE = 24;
	static final int MAX_NOTES_PER_OWNER = 300;
	static final int MAX_NOTES_PER_WORLD = 150;
	static final int MAX_IMAGE_BYTES = 256 * 1024;
	static final int MAX_IMAGE_SIDE = 512;
	static final int BOARD_W = 256;
	static final int BOARD_H = 160;
	static final Set<String> STYLES = Set.of("paper", "sticky", "glass", "sign", "neon");

	private static final RateLimiter.Window[] CREATE_LIMIT = {new RateLimiter.Window(15, 600_000), new RateLimiter.Window(60, 86_400_000)};
	private static final RateLimiter.Window[] SAVE_LIMIT = {new RateLimiter.Window(30, 60_000), new RateLimiter.Window(400, 3_600_000)};
	private static final RateLimiter.Window[] CHECK_LIMIT = {new RateLimiter.Window(60, 60_000)};
	private static final RateLimiter.Window[] BOARD_LIMIT = {new RateLimiter.Window(40, 10_000), new RateLimiter.Window(1500, 3_600_000)};
	private static final RateLimiter.Window[] IMAGE_LIMIT = {new RateLimiter.Window(5, 600_000), new RateLimiter.Window(30, 86_400_000)};
	private static final RateLimiter.Window[] MENTION_LIMIT = {new RateLimiter.Window(30, 3_600_000)};
	private static final long MENTION_REPEAT_MILLIS = 10 * 60_000;
	private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_]{2,16})");

	static final class Check {
		String id;
		String text;
		boolean done;
		String doneBy;
		long doneAt;
	}

	static final class Stroke {
		int c;
		int w;
		int[] p;
	}

	static final class Note {
		String id;
		String owner;
		String ownerName;
		long created;
		long updated;
		String editorName;
		int version;
		String server;
		String world;
		String dim;
		double x, y, z;
		String title;
		String text;
		int color = 0xFFF5D76E;
		String icon;
		String style = "paper";
		double size = 1.0;
		/** Turns to face whoever looks at it, or stays fixed at {@link #yaw} (degrees) like a sign on a wall. */
		boolean face = true;
		double yaw;
		List<Check> checks = new ArrayList<>();
		List<Stroke> board = new ArrayList<>();
		/** Version of the picture (0 = none); the bytes are in notes-images. */
		int image;
		/** "friends" (every friend of the owner) or "people" (only {@link #people}). */
		String share = "people";
		List<String> people = new ArrayList<>();
		Map<String, String> names = new LinkedHashMap<>();
		/** "owner" or "everyone" (everyone who sees it may edit text and board). */
		String edit = "owner";
		List<String> mentions = new ArrayList<>();
		/** Mentioned people who haven't opened it since. */
		List<String> unread = new ArrayList<>();
	}

	private static final class Data {
		Map<String, Note> notes = new LinkedHashMap<>();
	}

	private static final Gson GSON = new GsonBuilder().create();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String ID_CHARS = "abcdefghijkmnopqrstuvwxyz23456789";

	private final Path file;
	private final Path images;
	private final SocialService social;
	private final PartyService parties;
	private final ModerationService moderation;
	private Data data = new Data();
	private final Map<String, Long> lastMention = new HashMap<>();
	private volatile boolean dirty;

	NotesService(Path dataDir, SocialService social, PartyService parties, ModerationService moderation) {
		this.file = dataDir.resolve("notes.json");
		this.images = dataDir.resolve("notes-images");
		this.social = social;
		this.parties = parties;
		this.moderation = moderation;
		try {
			if (Files.exists(file)) {
				Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
				if (loaded != null && loaded.notes != null) {
					data = loaded;
				}
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[notes] Couldn't read notes.json: " + e.getMessage());
		}
		Thread saver = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					return;
				}
				if (dirty) {
					save();
				}
			}
		}, "notes-save");
		saver.setDaemon(true);
		saver.start();
	}

	private synchronized void save() {
		dirty = false;
		try {
			Files.createDirectories(file.getParent());
			Path tmp = Files.createTempFile(file.getParent(), "notes-", ".json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[notes] Couldn't save notes.json: " + e.getMessage());
		}
	}

	// ---- Access ----

	private boolean sees(Note note, String uuid) {
		return note.owner.equals(uuid) || note.people.contains(uuid) || "friends".equals(note.share) && social.friendsWith(note.owner, uuid);
	}

	private boolean edits(Note note, String uuid) {
		return note.owner.equals(uuid) || "everyone".equals(note.edit) && sees(note, uuid);
	}

	/** Everyone who sees the note right now. */
	private Set<String> audience(Note note) {
		Set<String> out = new LinkedHashSet<>();
		out.add(note.owner);
		out.addAll(note.people);
		if ("friends".equals(note.share)) {
			out.addAll(social.friendList(note.owner));
		}
		return out;
	}

	private Note require(String id, String me) throws ApiError {
		Note note = id == null ? null : data.notes.get(id);
		if (note == null || !sees(note, me)) {
			throw new ApiError(404, "That note doesn't exist any more");
		}
		return note;
	}

	private void limit(String me, String action, RateLimiter.Window[] windows) throws ApiError {
		try {
			social.rateLimit(me, action, windows);
		} catch (SocialService.SocialException e) {
			throw new ApiError(429, "Slow down a little - too many note changes");
		}
	}

	// ---- Reading ----

	/** What a player gets: the note without other people's unread state. */
	record NoteView(Note note, boolean mine, boolean canEdit, boolean unread) {
	}

	record InboxItem(String id, String title, String ownerName, String server, String dim, double x, double y, double z, long updated) {
	}

	synchronized List<NoteView> list(String me, String server) {
		List<NoteView> out = new ArrayList<>();
		for (Note note : data.notes.values()) {
			if ((server == null || server.equals(note.server)) && sees(note, me)) {
				out.add(new NoteView(strip(note), note.owner.equals(me), edits(note, me), note.unread.contains(me)));
			}
		}
		return out;
	}

	synchronized List<InboxItem> inbox(String me) {
		List<InboxItem> out = new ArrayList<>();
		for (Note note : data.notes.values()) {
			if (note.unread.contains(me) && sees(note, me)) {
				out.add(new InboxItem(note.id, note.title, note.ownerName, note.server, note.dim, note.x, note.y, note.z, note.updated));
			}
		}
		return out;
	}

	private static Note strip(Note note) {
		Note copy = GSON.fromJson(GSON.toJson(note), Note.class);
		copy.unread = List.of();
		return copy;
	}

	synchronized void seen(String me, String id) throws ApiError {
		Note note = require(id, me);
		if (note.unread.remove(me)) {
			dirty = true;
		}
	}

	// ---- Writing ----

	/** What a client sends to create / change a note (only what that player may change is taken over). */
	static final class NoteInput {
		String id;
		String server;
		String world;
		String dim;
		Double x, y, z;
		String title;
		String text;
		Integer color;
		String icon;
		String style;
		Double size;
		Boolean face;
		Double yaw;
		List<Check> checks;
		String share;
		List<String> people;
		String edit;
	}

	synchronized NoteView save(String me, NoteInput in) throws ApiError {
		moderation.check(ModerationService.NOTE, me);
		if (in == null) {
			throw new ApiError(400, "Missing note");
		}
		Note note;
		boolean created = false;
		if (in.id == null || in.id.isBlank()) {
			limit(me, "notes-create", CREATE_LIMIT);
			if (in.server == null || in.world == null || in.dim == null || in.x == null || in.y == null || in.z == null) {
				throw new ApiError(400, "A note needs a place");
			}
			long mine = data.notes.values().stream().filter(n -> n.owner.equals(me)).count();
			if (mine >= MAX_NOTES_PER_OWNER) {
				throw new ApiError(409, "You have " + MAX_NOTES_PER_OWNER + " shared notes - delete some first");
			}
			long here = data.notes.values().stream().filter(n -> n.owner.equals(me) && n.server.equals(in.server) && n.world.equals(in.world)).count();
			if (here >= MAX_NOTES_PER_WORLD) {
				throw new ApiError(409, "You have " + MAX_NOTES_PER_WORLD + " shared notes in this world - delete some first");
			}
			note = new Note();
			note.id = newId();
			note.owner = me;
			note.ownerName = social.usernameOf(me);
			note.created = System.currentTimeMillis();
			note.server = clip(in.server, 160);
			note.world = clip(in.world, 40);
			created = true;
		} else {
			note = require(in.id, me);
			if (!edits(note, me)) {
				throw new ApiError(403, "Only " + note.ownerName + " can change this note");
			}
		}
		limit(me, "notes-save", SAVE_LIMIT);
		boolean owner = note.owner.equals(me);
		if (in.dim != null && (created || owner)) {
			note.dim = clip(in.dim, 60);
			note.x = coord(in.x, note.x);
			note.y = coord(in.y, note.y);
			note.z = coord(in.z, note.z);
		}
		if (in.title != null) {
			note.title = CloudBackupService.clean(in.title, MAX_TITLE);
		}
		if (in.text != null) {
			note.text = cleanText(in.text, MAX_TEXT);
		}
		if (in.color != null) {
			note.color = 0xFF000000 | in.color;
		}
		if (in.icon != null) {
			note.icon = in.icon.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? clip(in.icon, 80) : null;
		}
		if (in.style != null && STYLES.contains(in.style)) {
			note.style = in.style;
		}
		if (in.size != null && Double.isFinite(in.size)) {
			note.size = Math.max(0.5, Math.min(2.0, in.size));
		}
		if (in.face != null) {
			note.face = in.face;
		}
		if (in.yaw != null && Double.isFinite(in.yaw)) {
			note.yaw = ((in.yaw % 360) + 360) % 360;
		}
		if (in.checks != null) {
			note.checks = mergeChecks(note.checks, in.checks);
		}
		if (owner) {
			if (in.share != null) {
				note.share = "friends".equals(in.share) ? "friends" : "people";
			}
			if (in.edit != null) {
				note.edit = "everyone".equals(in.edit) ? "everyone" : "owner";
			}
			if (in.people != null) {
				setPeople(note, me, in.people);
			}
		}
		if (note.title == null && note.text == null && note.checks.isEmpty()) {
			note.title = "Note";
		}
		touch(note, me);
		data.notes.put(note.id, note);
		mentions(note, me);
		broadcast(note, false);
		return new NoteView(strip(note), owner, true, false);
	}

	/** Shares with picked people: friends of the owner and current party mates only (no strangers). */
	private void setPeople(Note note, String me, List<String> requested) throws ApiError {
		Set<String> allowed = new LinkedHashSet<>(social.friendList(me));
		allowed.addAll(parties.partyMates(me));
		List<String> people = new ArrayList<>();
		for (String uuid : requested) {
			if (uuid == null || uuid.equals(me) || people.contains(uuid)) {
				continue;
			}
			// Keep people who already had it (even if no longer a friend - the owner can remove them).
			if (!allowed.contains(uuid) && !note.people.contains(uuid)) {
				continue;
			}
			people.add(uuid);
			if (people.size() >= MAX_PEOPLE) {
				break;
			}
		}
		Set<String> removed = new LinkedHashSet<>(note.people);
		removed.removeAll(people);
		note.people = people;
		note.names = new LinkedHashMap<>();
		for (String uuid : people) {
			note.names.put(uuid, social.usernameOf(uuid));
		}
		note.unread.removeIf(u -> !sees(note, u));
		for (String uuid : removed) {
			social.pushEvent(uuid, "notes", Map.of("server", note.server, "id", note.id, "removed", true));
		}
	}

	/** Takes over text / order from the client, but keeps who ticked what unless the box changed. */
	private static List<Check> mergeChecks(List<Check> old, List<Check> incoming) {
		Map<String, Check> byId = new HashMap<>();
		for (Check check : old) {
			byId.put(check.id, check);
		}
		List<Check> out = new ArrayList<>();
		for (Check in : incoming) {
			if (in == null || out.size() >= MAX_CHECKS) {
				continue;
			}
			String text = CloudBackupService.clean(in.text, MAX_CHECK_TEXT);
			if (text == null) {
				continue;
			}
			Check check = new Check();
			check.id = in.id != null && in.id.matches("[a-z0-9]{1,12}") ? in.id : newId().substring(0, 6);
			check.text = text;
			Check before = byId.get(check.id);
			if (before != null) {
				check.done = before.done;
				check.doneBy = before.doneBy;
				check.doneAt = before.doneAt;
			}
			out.add(check);
		}
		return out;
	}

	synchronized NoteView check(String me, String id, String checkId, boolean done) throws ApiError {
		moderation.check(ModerationService.NOTE, me);
		Note note = require(id, me);
		limit(me, "notes-check", CHECK_LIMIT);
		for (Check check : note.checks) {
			if (check.id.equals(checkId)) {
				if (check.done != done) {
					check.done = done;
					check.doneBy = done ? social.usernameOf(me) : null;
					check.doneAt = done ? System.currentTimeMillis() : 0;
					touch(note, me);
					broadcast(note, false);
				}
				return new NoteView(strip(note), note.owner.equals(me), edits(note, me), false);
			}
		}
		throw new ApiError(404, "That checklist item is gone");
	}

	synchronized NoteView board(String me, String id, List<Stroke> strokes, boolean clear) throws ApiError {
		moderation.check(ModerationService.NOTE, me);
		Note note = require(id, me);
		if (!edits(note, me)) {
			throw new ApiError(403, "Only " + note.ownerName + " can draw on this note");
		}
		limit(me, "notes-board", BOARD_LIMIT);
		if (clear) {
			note.board = new ArrayList<>();
		}
		int points = note.board.stream().mapToInt(s -> s.p.length / 2).sum();
		if (strokes != null) {
			for (Stroke in : strokes) {
				if (in == null || in.p == null || in.p.length < 2 || note.board.size() >= MAX_STROKES) {
					continue;
				}
				int n = Math.min(in.p.length / 2, 400);
				if (points + n > MAX_POINTS) {
					throw new ApiError(409, "The board is full - clear it first");
				}
				Stroke stroke = new Stroke();
				stroke.c = in.c;
				stroke.w = Math.max(1, Math.min(16, in.w));
				stroke.p = new int[n * 2];
				for (int i = 0; i < n; i++) {
					stroke.p[i * 2] = Math.max(0, Math.min(BOARD_W, in.p[i * 2]));
					stroke.p[i * 2 + 1] = Math.max(0, Math.min(BOARD_H, in.p[i * 2 + 1]));
				}
				note.board.add(stroke);
				points += n;
			}
		}
		touch(note, me);
		broadcast(note, false);
		return new NoteView(strip(note), note.owner.equals(me), true, false);
	}

	/** Sets ({@code png} base64) or removes ({@code null}) the note's picture. */
	synchronized NoteView image(String me, String id, String png) throws ApiError {
		moderation.check(ModerationService.NOTE, me);
		Note note = require(id, me);
		if (!edits(note, me)) {
			throw new ApiError(403, "Only " + note.ownerName + " can change the picture");
		}
		limit(me, "notes-image", IMAGE_LIMIT);
		Path path = images.resolve(note.id + ".png");
		try {
			if (png == null) {
				Files.deleteIfExists(path);
				note.image = 0;
			} else {
				byte[] bytes = Base64.getDecoder().decode(png);
				checkPng(bytes);
				Files.createDirectories(images);
				Files.write(path, bytes);
				note.image++;
			}
		} catch (IllegalArgumentException e) {
			throw new ApiError(400, "That isn't a picture");
		} catch (IOException e) {
			throw new ApiError(500, "Couldn't store the picture");
		}
		touch(note, me);
		broadcast(note, false);
		return new NoteView(strip(note), note.owner.equals(me), true, false);
	}

	/** PNG only, small, sane dimensions (read from the IHDR chunk). */
	private static void checkPng(byte[] bytes) throws ApiError {
		if (bytes.length > MAX_IMAGE_BYTES) {
			throw new ApiError(413, "Pictures can be up to " + MAX_IMAGE_BYTES / 1024 + " KB");
		}
		byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
		if (bytes.length < 33) {
			throw new ApiError(400, "That isn't a PNG picture");
		}
		for (int i = 0; i < magic.length; i++) {
			if (bytes[i] != magic[i]) {
				throw new ApiError(400, "That isn't a PNG picture");
			}
		}
		int w = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16) | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
		int h = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16) | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
		if (w <= 0 || h <= 0 || w > MAX_IMAGE_SIDE || h > MAX_IMAGE_SIDE) {
			throw new ApiError(400, "Pictures can be up to " + MAX_IMAGE_SIDE + "x" + MAX_IMAGE_SIDE);
		}
	}

	/** The picture as base64 for someone who sees the note (or staff with {@code me} null). */
	synchronized String imageData(String me, String id) throws ApiError {
		Note note = me == null ? data.notes.get(id) : require(id, me);
		if (note == null || note.image == 0) {
			throw new ApiError(404, "No picture");
		}
		try {
			return Base64.getEncoder().encodeToString(Files.readAllBytes(images.resolve(note.id + ".png")));
		} catch (IOException e) {
			throw new ApiError(404, "No picture");
		}
	}

	synchronized void delete(String me, String id) throws ApiError {
		Note note = require(id, me);
		if (!note.owner.equals(me)) {
			throw new ApiError(403, "Only " + note.ownerName + " can delete this note - you can leave it instead");
		}
		remove(note);
	}

	/** Stops seeing a note someone shared with you. */
	synchronized void leave(String me, String id) throws ApiError {
		Note note = require(id, me);
		if (note.owner.equals(me)) {
			throw new ApiError(400, "It's your own note - delete it instead");
		}
		if (!note.people.remove(me)) {
			throw new ApiError(400, "It's shared with all of " + note.ownerName + "'s friends - ask them to change that");
		}
		note.names.remove(me);
		note.unread.remove(me);
		dirty = true;
		social.pushEvent(me, "notes", Map.of("server", note.server, "id", note.id, "removed", true));
	}

	private void remove(Note note) {
		Set<String> audience = audience(note);
		data.notes.remove(note.id);
		try {
			Files.deleteIfExists(images.resolve(note.id + ".png"));
		} catch (IOException ignored) {
			// leftover file: harmless
		}
		dirty = true;
		for (String uuid : audience) {
			social.pushEvent(uuid, "notes", Map.of("server", note.server, "id", note.id, "removed", true));
		}
	}

	// ---- Mentions & events ----

	/** Finds @names in the text and checklist that belong to people who see the note, and tells the new ones. */
	private void mentions(Note note, String me) {
		Map<String, String> byName = new HashMap<>();
		for (String uuid : audience(note)) {
			byName.put(social.usernameOf(uuid).toLowerCase(Locale.ROOT), uuid);
		}
		Set<String> found = new LinkedHashSet<>();
		StringBuilder all = new StringBuilder(note.text == null ? "" : note.text);
		for (Check check : note.checks) {
			all.append('\n').append(check.text);
		}
		Matcher matcher = MENTION.matcher(all);
		while (matcher.find() && found.size() < MAX_MENTIONS) {
			String uuid = byName.get(matcher.group(1).toLowerCase(Locale.ROOT));
			if (uuid != null && !uuid.equals(me)) {
				found.add(uuid);
			}
		}
		note.mentions = new ArrayList<>(found);
		long now = System.currentTimeMillis();
		for (String uuid : found) {
			String key = note.id + "|" + uuid;
			Long last = lastMention.get(key);
			if (last != null && now - last < MENTION_REPEAT_MILLIS) {
				continue;
			}
			try {
				social.rateLimit(me, "notes-mention", MENTION_LIMIT);
			} catch (SocialService.SocialException e) {
				break;
			}
			lastMention.put(key, now);
			if (!note.unread.contains(uuid)) {
				note.unread.add(uuid);
			}
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("id", note.id);
			event.put("title", note.title == null ? "a note" : note.title);
			event.put("from", social.usernameOf(me));
			event.put("server", note.server);
			event.put("dim", note.dim);
			event.put("x", note.x);
			event.put("y", note.y);
			event.put("z", note.z);
			social.pushEvent(uuid, "note_mention", event);
		}
		lastMention.entrySet().removeIf(e -> now - e.getValue() > MENTION_REPEAT_MILLIS);
	}

	private void broadcast(Note note, boolean removed) {
		for (String uuid : audience(note)) {
			social.pushEvent(uuid, "notes", Map.of("server", note.server, "id", note.id, "removed", removed));
		}
	}

	private void touch(Note note, String me) {
		note.updated = System.currentTimeMillis();
		note.editorName = social.usernameOf(me);
		note.version++;
		dirty = true;
	}

	// ---- Staff ----

	record StaffRow(String id, String owner, String ownerName, String title, String preview, String server, String dim, double x, double y,
			double z, long created, long updated, int sharedWith, String share, boolean image, int checks, int strokes, int reports) {
	}

	synchronized List<StaffRow> staffList(String query, boolean reportedOnly, int limit) {
		String q = query == null ? "" : query.toLowerCase(Locale.ROOT).strip();
		List<StaffRow> out = new ArrayList<>();
		List<Note> all = new ArrayList<>(data.notes.values());
		all.sort((a, b) -> Long.compare(b.updated, a.updated));
		for (Note note : all) {
			int reports = moderation.openAgainst(ModerationService.NOTE, note.id);
			if (reportedOnly && reports == 0) {
				continue;
			}
			if (!q.isEmpty() && !(note.id.equals(q) || contains(note.ownerName, q) || contains(note.title, q) || contains(note.text, q)
					|| contains(note.server, q))) {
				continue;
			}
			String preview = note.text == null ? "" : note.text.length() > 140 ? note.text.substring(0, 140) + "..." : note.text;
			out.add(new StaffRow(note.id, note.owner, note.ownerName, note.title, preview, note.server, note.dim, note.x, note.y, note.z,
					note.created, note.updated, note.people.size(), note.share, note.image > 0, note.checks.size(), note.board.size(), reports));
			if (out.size() >= limit) {
				break;
			}
		}
		return out;
	}

	synchronized Note staffView(String id) throws ApiError {
		Note note = data.notes.get(id);
		if (note == null) {
			throw new ApiError(404, "That note doesn't exist any more");
		}
		return strip(note);
	}

	synchronized Note removeByStaff(String id) throws ApiError {
		Note note = data.notes.get(id);
		if (note == null) {
			throw new ApiError(404, "That note doesn't exist any more");
		}
		remove(note);
		return note;
	}

	synchronized Note get(String id) {
		return data.notes.get(id);
	}

	/** For the staff overview: how many notes exist. */
	synchronized int count() {
		return data.notes.size();
	}

	// ---- Helpers ----

	private static boolean contains(String text, String q) {
		return text != null && text.toLowerCase(Locale.ROOT).contains(q);
	}

	private static double coord(Double value, double fallback) {
		if (value == null || !Double.isFinite(value)) {
			return fallback;
		}
		return Math.max(-3.0e7, Math.min(3.0e7, value));
	}

	/** Like {@link CloudBackupService#clean} but keeps line breaks. */
	private static String cleanText(String text, int max) {
		if (text == null) {
			return null;
		}
		String plain = text.replace("\r", "").replaceAll("[\\p{Cntrl}&&[^\n]]|§", "").replaceAll("\n{3,}", "\n\n").strip();
		if (plain.isEmpty()) {
			return null;
		}
		return plain.length() > max ? plain.substring(0, max) : plain;
	}

	private static String clip(String text, int max) {
		if (text == null) {
			return null;
		}
		return text.length() > max ? text.substring(0, max) : text;
	}

	private static String newId() {
		StringBuilder id = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			id.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
		}
		return id.toString();
	}
}
