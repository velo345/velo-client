package net.veloclient.velo.devbuild.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Velo Rewind's edits of one recording (plain Java, saved next to it as {@code <name>.vrewind.json}): its clips - each a
 * stretch of one recorded segment with its own timeline - and per clip the tracks: a value for the whole clip and/or
 * keyframes (tick -> value, interpolation, label) for parts of it. Properties are the things a track can drive: the camera
 * (x, y, z, yaw, pitch, roll, FOV), playback speed, time of day, rain, thunder, fog.
 */
public final class RewindEdits {

	/** A property a track can drive. {@code min/max/step}: the editor's range; {@code unit} only for display. */
	public record Property(String id, String label, String group, double min, double max, double step, double def, String unit) {
	}

	public static final List<Property> PROPERTIES = List.of(
			new Property("cam_x", "Camera X", "Camera", -3.0e7, 3.0e7, 0.1, 0, ""),
			new Property("cam_y", "Camera Y", "Camera", -2048, 2048, 0.1, 64, ""),
			new Property("cam_z", "Camera Z", "Camera", -3.0e7, 3.0e7, 0.1, 0, ""),
			new Property("cam_yaw", "Yaw", "Camera", -360, 360, 0.5, 0, "°"),
			new Property("cam_pitch", "Pitch", "Camera", -90, 90, 0.5, 0, "°"),
			new Property("cam_roll", "Roll", "Camera", -180, 180, 0.5, 0, "°"),
			new Property("fov", "FOV", "Camera", 10, 150, 1, 70, "°"),
			new Property("speed", "Speed", "Playback", 0.1, 8, 0.05, 1, "x"),
			new Property("time", "Time of day", "World", 0, 24000, 50, 6000, ""),
			new Property("rain", "Rain", "World", 0, 1, 0.05, 0, ""),
			new Property("thunder", "Thunder", "World", 0, 1, 0.05, 0, ""),
			new Property("fog", "Fog", "World", 0, 8, 0.1, 1, "x"),
			// Audio: multipliers on the game's own volumes (1 = as recorded); voice:<uuid> tracks are one player's voice
			new Property("volume", "All audio", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_players", "Players", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_hostile", "Hostile mobs", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_neutral", "Friendly mobs", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_blocks", "Blocks", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_weather", "Weather", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_ambient", "Ambient", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_records", "Jukebox", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_music", "Music", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_voice", "Voice chat", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_mic", "My microphone", "Audio", 0, 2, 0.05, 1, "x"),
			new Property("vol_system", "System audio", "Audio", 0, 2, 0.05, 1, "x"));

	// Names for voice:<uuid> tracks (filled from the recording's player list while it plays).
	public static final Map<String, String> VOICE_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

	public static Property property(String id) {
		for (Property p : PROPERTIES) {
			if (p.id().equals(id)) {
				return p;
			}
		}
		if (id != null && id.startsWith("voice:") && id.length() > 6) {
			String uuid = id.substring(6);
			String name = VOICE_NAMES.get(uuid);
			return new Property(id, "Voice " + (name != null ? name : uuid.substring(0, Math.min(8, uuid.length()))), "Audio", 0, 2, 0.05, 1, "x");
		}
		return null;
	}

	// What's behind the players / entities: the world, or no terrain (sky stays), or a flat colour for keying.
	public static final List<String> BACKGROUNDS = List.of("World", "No terrain", "Green screen", "Blue screen", "Black");

	public static final List<String> INTERPOLATIONS = List.of("Smooth", "Linear", "Ease in", "Ease out", "Hold");

	public static final class Key {
		public double value;
		public String interp = "Smooth";
		public String label = "";

		public Key() {
		}

		Key(double value) {
			this.value = value;
		}
	}

	public static final class Track {
		/** The value for the whole clip (null: none) - keyframes, where there are any, win. */
		public Double global;
		public TreeMap<Integer, Key> keys = new TreeMap<>();

		public boolean active() {
			return global != null || !keys.isEmpty();
		}

		/** The value at {@code tick} (fractional for smooth camera moves), or null if this track sets nothing. */
		public Double at(double tick, boolean angle) {
			if (keys.isEmpty()) {
				return global;
			}
			var lo = keys.floorEntry((int) Math.floor(tick));
			var hi = keys.ceilingEntry((int) Math.ceil(tick));
			if (lo == null) {
				return hi.getValue().value;
			}
			if (hi == null || hi.getKey().equals(lo.getKey())) {
				return lo.getValue().value;
			}
			double t = (tick - lo.getKey()) / (double) (hi.getKey() - lo.getKey());
			String interp = lo.getValue().interp;
			t = switch (interp) {
				case "Linear" -> t;
				case "Ease in" -> t * t * t;
				case "Ease out" -> 1 - Math.pow(1 - t, 3);
				case "Hold" -> 0;
				default -> t * t * (3 - 2 * t);
			};
			double a = lo.getValue().value;
			double b = hi.getValue().value;
			if (angle) {
				double d = ((b - a) % 360 + 540) % 360 - 180; // the short way round
				return a + d * t;
			}
			if ("Smooth".equals(interp)) {
				// Catmull-Rom through the neighbours: a camera path that curves instead of bending at every key
				var before = keys.lowerEntry(lo.getKey());
				var after = keys.higherEntry(hi.getKey());
				double p0 = before == null ? a : before.getValue().value;
				double p3 = after == null ? b : after.getValue().value;
				double u = (tick - lo.getKey()) / (double) (hi.getKey() - lo.getKey());
				double u2 = u * u;
				double u3 = u2 * u;
				return 0.5 * ((2 * a) + (-p0 + b) * u + (2 * p0 - 5 * a + 4 * b - p3) * u2 + (-p0 + 3 * a - 3 * b + p3) * u3);
			}
			return a + (b - a) * t;
		}
	}

	public static final class Clip {
		public String name = "Clip";
		public int segment;
		public int start;
		public int length;
		public Map<String, Track> tracks = new LinkedHashMap<>();
		/** Entities hidden in this clip (UUIDs). */
		public List<String> hidden = new ArrayList<>();
		/** Player skins swapped in this clip: UUID -> player name or PNG path. */
		public Map<String, String> skins = new LinkedHashMap<>();
		/** Per player (UUID): parts of their look hidden in this clip - cape, cosmetics, pets, nametag, glow. */
		public Map<String, List<String>> hideParts = new LinkedHashMap<>();
		/** One of {@link #BACKGROUNDS} (null = World). */
		public String background;

		public Track track(String id) {
			return tracks.computeIfAbsent(id, k -> new Track());
		}

		public Double value(String id, double tick) {
			Track t = tracks.get(id);
			return t == null ? null : t.at(tick, id.equals("cam_yaw") || id.equals("cam_roll"));
		}
	}

	public List<Clip> clips = new ArrayList<>();
	public int selected;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Path fileFor(Path recording) {
		return recording.resolveSibling(recording.getFileName() + ".json");
	}

	/** The edits saved next to the recording, or one clip per recorded segment. */
	public static RewindEdits load(Path recording, int[] segmentTicks) {
		RewindEdits edits = null;
		try {
			Path f = fileFor(recording);
			if (Files.exists(f)) {
				edits = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), RewindEdits.class);
			}
		} catch (Exception ignored) {
		}
		// a broken file (shouldn't happen - saves are atomic): the newest backup that reads
		if (edits == null || edits.clips == null || edits.clips.isEmpty()) {
			for (Path b : RewindAutosave.backups(recording)) {
				try {
					edits = GSON.fromJson(Files.readString(b, StandardCharsets.UTF_8), RewindEdits.class);
					if (edits != null && edits.clips != null && !edits.clips.isEmpty()) {
						break;
					}
				} catch (Exception ignored) {
				}
				edits = null;
			}
		}
		if (edits == null || edits.clips == null || edits.clips.isEmpty()) {
			edits = new RewindEdits();
			for (int i = 0; i < segmentTicks.length; i++) {
				Clip c = new Clip();
				c.name = "Clip " + (i + 1);
				c.segment = i;
				c.length = segmentTicks[i];
				edits.clips.add(c);
			}
		}
		edits.selected = Math.max(0, Math.min(edits.clips.size() - 1, edits.selected));
		return edits;
	}

	public void save(Path recording) {
		try {
			Files.writeString(fileFor(recording), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}

	public Clip clip() {
		return clips.get(selected);
	}
}
