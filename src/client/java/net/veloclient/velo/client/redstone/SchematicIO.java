package net.veloclient.velo.client.redstone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Redstone Mode schematics. {@code .veloschem} (Velo's own, in {@code .minecraft/schematics/velo})
 * stores block states, block-entity data and which redstone parts had a tick pending, so a
 * pasted contraption starts up running. {@code .litematic} files (Litematica's format, all
 * regions) can be loaded too.
 */
public final class SchematicIO {

	private static final String MAGIC = "VELOSCHEM";
	private static final int VERSION = 2;

	private SchematicIO() {
	}

	public static Path veloFolder() {
		return FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("velo");
	}

	public static Path litematicFolder() {
		return FabricLoader.getInstance().getGameDir().resolve("schematics");
	}

	// ---- .veloschem ----

	public static void save(Path file, Clip clip, String name) throws IOException {
		Files.createDirectories(file.getParent());
		Map<String, Integer> palette = new HashMap<>();
		List<String> entries = new ArrayList<>();
		int[] indices = new int[clip.states.length];
		for (int i = 0; i < clip.states.length; i++) {
			String text = clip.states[i] == null ? "minecraft:air" : RmWorld.toText(clip.states[i]);
			Integer index = palette.get(text);
			if (index == null) {
				index = entries.size();
				palette.put(text, index);
				entries.add(text);
			}
			indices[i] = index;
		}
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
			out.writeUTF(MAGIC);
			out.writeInt(VERSION);
			writeString(out, name);
			out.writeLong(System.currentTimeMillis());
			out.writeInt(clip.sx);
			out.writeInt(clip.sy);
			out.writeInt(clip.sz);
			out.writeInt(entries.size());
			for (String entry : entries) {
				writeString(out, entry);
			}
			for (int index : indices) {
				out.writeInt(index);
			}
			List<Integer> withData = new ArrayList<>();
			for (int i = 0; i < clip.blockEntities.length; i++) {
				if (clip.blockEntities[i] != null) {
					withData.add(i);
				}
			}
			out.writeInt(withData.size());
			for (int i : withData) {
				out.writeInt(i);
				writeString(out, RmWorld.tagToText(clip.blockEntities[i]));
			}
			int pending = 0;
			for (boolean b : clip.pendingTicks) {
				pending += b ? 1 : 0;
			}
			out.writeInt(pending);
			for (int i = 0; i < clip.pendingTicks.length; i++) {
				if (clip.pendingTicks[i]) {
					out.writeInt(i);
				}
			}
			// v2: entities, placed after all blocks when pasting.
			out.writeInt(clip.entities.size());
			for (Object[] e : clip.entities) {
				out.writeDouble((double) e[0]);
				out.writeDouble((double) e[1]);
				out.writeDouble((double) e[2]);
				out.writeFloat((float) e[3]);
				writeString(out, RmWorld.tagToText(e[4]));
			}
		}
	}

	public static Clip loadVelo(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (!MAGIC.equals(in.readUTF())) {
				throw new IOException("Not a .veloschem file");
			}
			int version = in.readInt();
			if (version > VERSION) {
				throw new IOException("Made by a newer Velo Client");
			}
			readString(in);
			in.readLong();
			int sx = in.readInt();
			int sy = in.readInt();
			int sz = in.readInt();
			if ((long) sx * sy * sz > 400_000_000L || sx <= 0 || sy <= 0 || sz <= 0) {
				throw new IOException("Schematic too big");
			}
			Clip clip = new Clip(sx, sy, sz);
			int paletteSize = in.readInt();
			Object[] palette = new Object[paletteSize];
			Object air = RmWorld.parse("minecraft:air", Map.of());
			for (int i = 0; i < paletteSize; i++) {
				Object state = RmWorld.fromText(readString(in));
				palette[i] = state == null ? air : state;
			}
			for (int i = 0; i < clip.states.length; i++) {
				clip.states[i] = palette[in.readInt()];
			}
			int data = in.readInt();
			for (int n = 0; n < data; n++) {
				int i = in.readInt();
				clip.blockEntities[i] = RmWorld.tagFromText(readString(in));
			}
			int pending = in.readInt();
			for (int n = 0; n < pending; n++) {
				clip.pendingTicks[in.readInt()] = true;
			}
			if (version >= 2) {
				int entities = in.readInt();
				for (int n = 0; n < entities; n++) {
					double x = in.readDouble();
					double y = in.readDouble();
					double z = in.readDouble();
					float yaw = in.readFloat();
					Object tag = RmWorld.tagFromText(readString(in));
					if (tag != null) {
						clip.entities.add(new Object[] {x, y, z, yaw, tag});
					}
				}
			}
			return clip;
		}
	}

	private static void writeString(DataOutputStream out, String text) throws IOException {
		byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > 16 * 1024 * 1024) {
			throw new IOException("Damaged file");
		}
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	// ---- .litematic ----

	public static Clip loadLitematic(Path file) throws IOException {
		Object root = RmWorld.readGzipNbt(file);
		Object regionsTag = RmWorld.child(root, "Regions");
		JsonObject regions = RmWorld.json(regionsTag).getAsJsonObject();
		if (regions.size() == 0) {
			throw new IOException("No regions in this schematic");
		}
		// Bounding box over every region.
		int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (var entry : regions.entrySet()) {
			int[][] r = regionBox(entry.getValue().getAsJsonObject());
			for (int a = 0; a < 3; a++) {
				min[a] = Math.min(min[a], r[0][a]);
				max[a] = Math.max(max[a], r[0][a] + r[1][a] - 1);
			}
		}
		int sx = max[0] - min[0] + 1;
		int sy = max[1] - min[1] + 1;
		int sz = max[2] - min[2] + 1;
		if ((long) sx * sy * sz > 400_000_000L) {
			throw new IOException("Schematic too big (" + sx + "x" + sy + "x" + sz + ")");
		}
		Clip clip = new Clip(sx, sy, sz);
		Object air = RmWorld.parse("minecraft:air", Map.of());
		java.util.Arrays.fill(clip.states, air);
		for (var entry : regions.entrySet()) {
			JsonObject region = entry.getValue().getAsJsonObject();
			int[][] r = regionBox(region);
			int rx = r[1][0];
			int ry = r[1][1];
			int rz = r[1][2];
			JsonArray paletteJson = region.getAsJsonArray("BlockStatePalette");
			Object[] palette = new Object[paletteJson.size()];
			for (int i = 0; i < palette.length; i++) {
				JsonObject p = paletteJson.get(i).getAsJsonObject();
				Map<String, String> props = new HashMap<>();
				if (p.has("Properties")) {
					for (var prop : p.getAsJsonObject("Properties").entrySet()) {
						props.put(prop.getKey(), prop.getValue().getAsString());
					}
				}
				Object state = RmWorld.parse(p.get("Name").getAsString(), props);
				palette[i] = state == null ? air : state;
			}
			JsonArray packed = region.getAsJsonArray("BlockStates");
			long[] words = new long[packed.size()];
			for (int i = 0; i < words.length; i++) {
				words[i] = packed.get(i).getAsLong();
			}
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
			long mask = (1L << bits) - 1;
			int ox = r[0][0] - min[0];
			int oy = r[0][1] - min[1];
			int oz = r[0][2] - min[2];
			for (int y = 0; y < ry; y++) {
				for (int z = 0; z < rz; z++) {
					for (int x = 0; x < rx; x++) {
						long index = (long) y * rx * rz + (long) z * rx + x;
						long bit = index * bits;
						int word = (int) (bit >>> 6);
						int offset = (int) (bit & 63);
						if (word >= words.length) {
							continue;
						}
						long value = words[word] >>> offset;
						if (offset + bits > 64 && word + 1 < words.length) {
							value |= words[word + 1] << (64 - offset);
						}
						int id = (int) (value & mask);
						if (id < palette.length) {
							clip.states[clip.index(ox + x, oy + y, oz + z)] = palette[id];
						}
					}
				}
			}
			Object regionTag = RmWorld.child(regionsTag, entry.getKey());
			for (Object entity : RmWorld.list(RmWorld.child(regionTag, "Entities"))) {
				JsonObject data = RmWorld.json(entity).getAsJsonObject();
				if (!data.has("Pos")) {
					continue;
				}
				JsonArray pos = data.getAsJsonArray("Pos");
				float yaw = data.has("Rotation") ? data.getAsJsonArray("Rotation").get(0).getAsFloat() : 0f;
				clip.entities.add(new Object[] {pos.get(0).getAsDouble() + ox, pos.get(1).getAsDouble() + oy, pos.get(2).getAsDouble() + oz, yaw, entity});
			}
			for (Object te : RmWorld.list(RmWorld.child(regionTag, "TileEntities"))) {
				JsonObject pos = RmWorld.json(te).getAsJsonObject();
				if (!pos.has("x")) {
					continue;
				}
				int x = pos.get("x").getAsInt() + ox;
				int y = pos.get("y").getAsInt() + oy;
				int z = pos.get("z").getAsInt() + oz;
				if (x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz) {
					clip.blockEntities[clip.index(x, y, z)] = te;
				}
			}
		}
		return clip;
	}

	/** {{minX, minY, minZ}, {sizeX, sizeY, sizeZ}} of a region (Litematica sizes can be negative). */
	private static int[][] regionBox(JsonObject region) {
		JsonObject pos = region.getAsJsonObject("Position");
		JsonObject size = region.getAsJsonObject("Size");
		int[] p = {pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt()};
		int[] s = {size.get("x").getAsInt(), size.get("y").getAsInt(), size.get("z").getAsInt()};
		int[] mn = new int[3];
		int[] abs = new int[3];
		for (int a = 0; a < 3; a++) {
			mn[a] = s[a] < 0 ? p[a] + s[a] + 1 : p[a];
			abs[a] = Math.abs(s[a]);
		}
		return new int[][] {mn, abs};
	}

	/** Loads any supported file into the clipboard and opens a paste preview when Redstone Mode is on. */
	public static void loadToClipboard(Path file) {
		Thread thread = new Thread(() -> {
			try {
				String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
				Clip clip = name.endsWith(".litematic") ? loadLitematic(file) : loadVelo(file);
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
					Placements.add(file.getFileName().toString(), clip);
					if (!RedstoneMode.active()) {
						RedstoneMode.enter();
					}
					RmState.info("Loaded " + file.getFileName() + " (" + clip.sx + "x" + clip.sy + "x" + clip.sz
							+ ") - aim and left click to place it, Shift+scroll to rotate");
				});
			} catch (Exception e) {
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> RmState.error("Couldn't load " + file.getFileName() + ": "
						+ e.getMessage()));
			}
		}, "velo-schematic-load");
		thread.setDaemon(true);
		thread.start();
	}

	/** Saves the current selection as {@code name}.veloschem (captured and written on the server thread). */
	public static void saveSelection(String name) {
		int[] b = RmState.box();
		if (b == null) {
			RmState.error("Select an area first");
			return;
		}
		String clean = name.replaceAll("[^A-Za-z0-9 _.-]", "_").strip();
		Path file = veloFolder().resolve((clean.isEmpty() ? "redstone" : clean) + ".veloschem");
		RmWorld.onServer(level -> {
			Clip clip = RmOps.capture(level, b[0], b[1], b[2], b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1, true);
			// Captured and written on the server thread - a .veloschem is small (palette + ints).
			try {
				save(file, clip, clean);
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> RmState.info("Saved " + file.getFileName()
						+ " - find it under Schematics > Velo"));
			} catch (IOException e) {
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> RmState.error("Couldn't save: " + e.getMessage()));
			}
		});
	}

	/** Schematic files Redstone Mode can load: .veloschem first, then .litematic. */
	public static List<Path> loadable() {
		List<Path> files = new ArrayList<>();
		for (Path dir : new Path[] {veloFolder(), litematicFolder()}) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (var stream = Files.list(dir)) {
				stream.filter(p -> {
					String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
					return n.endsWith(".veloschem") || n.endsWith(".litematic");
				}).sorted().forEach(files::add);
			} catch (IOException ignored) {
				// Unreadable folder.
			}
		}
		return files;
	}
}
