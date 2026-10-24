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
 * stores block states, block-entity data and every scheduled block / fluid tick with its delay and
 * priority, so a pasted contraption keeps running exactly where it was. {@code .litematic} files (Litematica's format, all
 * regions) can be loaded too.
 */
public final class SchematicIO {

	private static final String MAGIC = "VELOSCHEM";
	private static final int VERSION = 3;

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
			// v3: every scheduled tick with its delay, priority and kind (v1/v2 had a flag per block).
			out.writeInt(clip.ticks.size());
			for (int[] t : clip.ticks) {
				out.writeInt(t[0]);
				out.writeInt(t[1]);
				out.writeByte(t[2]);
				out.writeByte(t[3]);
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
			if (pending < 0 || pending > clip.states.length * 2L) {
				throw new IOException("Damaged file");
			}
			for (int n = 0; n < pending; n++) {
				if (version >= 3) {
					int index = in.readInt();
					int delay = in.readInt();
					int priority = in.readByte();
					int fluid = in.readByte();
					if (index >= 0 && index < clip.states.length) {
						clip.ticks.add(new int[] {index, Math.max(1, delay), priority, fluid});
					}
				} else {
					// Old files only knew "something was pending": a 1-tick wake-up at normal priority.
					int index = in.readInt();
					if (index >= 0 && index < clip.states.length) {
						clip.ticks.add(new int[] {index, 1, 3, 0});
					}
				}
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
		List<long[]> litematicTicks = new ArrayList<>();
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
			// Litematica keeps scheduled ticks too: {Time (delay), Priority (-3..3), SubTick, x, y, z}.
			for (String key : new String[] {"PendingBlockTicks", "PendingFluidTicks"}) {
				for (Object tick : RmWorld.list(RmWorld.child(regionTag, key))) {
					JsonObject t = RmWorld.json(tick).getAsJsonObject();
					if (!t.has("x") || !t.has("Time")) {
						continue;
					}
					int x = t.get("x").getAsInt() + ox;
					int y = t.get("y").getAsInt() + oy;
					int z = t.get("z").getAsInt() + oz;
					if (x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz) {
						int priority = t.has("Priority") ? t.get("Priority").getAsInt() + 3 : 3;
						long sub = t.has("SubTick") ? t.get("SubTick").getAsLong() : 0;
						litematicTicks.add(new long[] {clip.index(x, y, z), Math.max(1, t.get("Time").getAsInt()), priority,
								key.contains("Fluid") ? 1 : 0, sub});
					}
				}
			}
		}
		litematicTicks.sort((a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : a[2] != b[2] ? Long.compare(a[2], b[2]) : Long.compare(a[4], b[4]));
		for (long[] t : litematicTicks) {
			clip.ticks.add(new int[] {(int) t[0], (int) t[1], (int) t[2], (int) t[3]});
		}
		return clip;
	}

	/** Litematica's schematic format version this writes (current Litematica for 1.21+ reads 7). */
	private static final int LITEMATIC_VERSION = 7;

	/**
	 * Writes a clip as a .litematic with one region, so friends without Velo can load it in Litematica: blocks, block
	 * entities, entities and the scheduled block / fluid ticks.
	 */
	public static void saveLitematic(Path file, Clip clip, String name) throws IOException {
		Files.createDirectories(file.getParent());
		Map<Object, Integer> paletteIndex = new java.util.IdentityHashMap<>();
		List<Object> palette = new ArrayList<>();
		Object air = RmWorld.parse("minecraft:air", Map.of());
		paletteIndex.put(air, 0);
		palette.add(air);
		int[] indices = new int[clip.states.length];
		int blocks = 0;
		// Litematica's order: x fastest, then z, then y.
		int n = 0;
		for (int y = 0; y < clip.sy; y++) {
			for (int z = 0; z < clip.sz; z++) {
				for (int x = 0; x < clip.sx; x++) {
					Object state = clip.states[clip.index(x, y, z)];
					if (state == null) {
						state = air;
					}
					Integer index = paletteIndex.get(state);
					if (index == null) {
						index = palette.size();
						paletteIndex.put(state, index);
						palette.add(state);
					}
					if (!RmWorld.isAir(state)) {
						blocks++;
					}
					indices[n++] = index;
				}
			}
		}
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
		long[] words = new long[(int) ((indices.length * (long) bits + 63) / 64)];
		for (int i = 0; i < indices.length; i++) {
			long bit = (long) i * bits;
			int word = (int) (bit >>> 6);
			int offset = (int) (bit & 63);
			words[word] |= (long) indices[i] << offset;
			if (offset + bits > 64) {
				words[word + 1] |= (long) indices[i] >>> (64 - offset);
			}
		}
		List<Object> paletteTags = new ArrayList<>();
		for (Object state : palette) {
			Object entry = RmWorld.nbtCompound();
			RmWorld.nbtPut(entry, "Name", RmWorld.id(state));
			String text = RmWorld.toText(state);
			int open = text.indexOf('[');
			if (open > 0 && text.endsWith("]")) {
				Object props = RmWorld.nbtCompound();
				for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
					int eq = pair.indexOf('=');
					if (eq > 0) {
						RmWorld.nbtPut(props, pair.substring(0, eq), pair.substring(eq + 1));
					}
				}
				RmWorld.nbtPut(entry, "Properties", props);
			}
			paletteTags.add(entry);
		}
		List<Object> tileEntities = new ArrayList<>();
		for (int x = 0; x < clip.sx; x++) {
			for (int y = 0; y < clip.sy; y++) {
				for (int z = 0; z < clip.sz; z++) {
					Object be = clip.blockEntities[clip.index(x, y, z)];
					if (be != null) {
						Object tag = RmWorld.nbtCopy(be);
						RmWorld.nbtPut(tag, "x", x);
						RmWorld.nbtPut(tag, "y", y);
						RmWorld.nbtPut(tag, "z", z);
						tileEntities.add(tag);
					}
				}
			}
		}
		List<Object> entities = new ArrayList<>();
		for (Object[] e : clip.entities) {
			Object tag = RmWorld.nbtCopy(e[4]);
			RmWorld.nbtPut(tag, "Pos", RmWorld.nbtDoubles((double) e[0], (double) e[1], (double) e[2]));
			entities.add(tag);
		}
		List<Object> blockTicks = new ArrayList<>();
		List<Object> fluidTicks = new ArrayList<>();
		int order = 0;
		for (int[] t : clip.ticks) {
			Object state = clip.states[t[0]];
			if (state == null) {
				continue;
			}
			int x = t[0] / (clip.sy * clip.sz);
			int y = t[0] / clip.sz % clip.sy;
			int z = t[0] % clip.sz;
			Object tick = RmWorld.nbtCompound();
			if (t[3] == 1) {
				String fluid = RmRender.fluid(state) == null ? "water" : RmRender.fluid(state);
				RmWorld.nbtPut(tick, "Fluid", "minecraft:" + (RmRender.source(state) ? fluid : "flowing_" + fluid));
			} else {
				RmWorld.nbtPut(tick, "Block", RmWorld.id(state));
			}
			RmWorld.nbtPut(tick, "Priority", t[2] - 3);
			RmWorld.nbtPut(tick, "SubTick", (long) order++);
			RmWorld.nbtPut(tick, "Time", t[1]);
			RmWorld.nbtPut(tick, "x", x);
			RmWorld.nbtPut(tick, "y", y);
			RmWorld.nbtPut(tick, "z", z);
			(t[3] == 1 ? fluidTicks : blockTicks).add(tick);
		}
		Object region = RmWorld.nbtCompound();
		RmWorld.nbtPut(region, "Position", xyz(0, 0, 0));
		RmWorld.nbtPut(region, "Size", xyz(clip.sx, clip.sy, clip.sz));
		RmWorld.nbtPut(region, "BlockStatePalette", RmWorld.nbtList(paletteTags));
		RmWorld.nbtPut(region, "BlockStates", words);
		RmWorld.nbtPut(region, "TileEntities", RmWorld.nbtList(tileEntities));
		RmWorld.nbtPut(region, "Entities", RmWorld.nbtList(entities));
		RmWorld.nbtPut(region, "PendingBlockTicks", RmWorld.nbtList(blockTicks));
		RmWorld.nbtPut(region, "PendingFluidTicks", RmWorld.nbtList(fluidTicks));
		String regionName = name == null || name.isBlank() ? "Region" : name;
		Object regions = RmWorld.nbtCompound();
		RmWorld.nbtPut(regions, regionName, region);

		long now = System.currentTimeMillis();
		Object meta = RmWorld.nbtCompound();
		RmWorld.nbtPut(meta, "Name", regionName);
		String author = net.veloclient.velo.client.gui.title.MenuInfo.username();
		RmWorld.nbtPut(meta, "Author", author == null || author.isBlank() ? "Velo Client" : author);
		RmWorld.nbtPut(meta, "Description", "Made with Velo Client Redstone Mode");
		RmWorld.nbtPut(meta, "RegionCount", 1);
		RmWorld.nbtPut(meta, "TotalVolume", (int) Math.min(Integer.MAX_VALUE, clip.volume()));
		RmWorld.nbtPut(meta, "TotalBlocks", blocks);
		RmWorld.nbtPut(meta, "TimeCreated", now);
		RmWorld.nbtPut(meta, "TimeModified", now);
		RmWorld.nbtPut(meta, "EnclosingSize", xyz(clip.sx, clip.sy, clip.sz));

		Object root = RmWorld.nbtCompound();
		RmWorld.nbtPut(root, "MinecraftDataVersion", RmWorld.dataVersion());
		RmWorld.nbtPut(root, "Version", LITEMATIC_VERSION);
		RmWorld.nbtPut(root, "SubVersion", 1);
		RmWorld.nbtPut(root, "Metadata", meta);
		RmWorld.nbtPut(root, "Regions", regions);
		RmWorld.writeGzipNbt(file, root);
	}

	private static Object xyz(int x, int y, int z) {
		Object tag = RmWorld.nbtCompound();
		RmWorld.nbtPut(tag, "x", x);
		RmWorld.nbtPut(tag, "y", y);
		RmWorld.nbtPut(tag, "z", z);
		return tag;
	}

	/** Any .veloschem / .litematic file as a Clip. */
	public static Clip load(Path file) throws IOException {
		String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		return name.endsWith(".litematic") ? loadLitematic(file) : loadVelo(file);
	}

	/**
	 * Converts a .veloschem to .litematic or the other way round, next to where that format lives (litematics in
	 * schematics/, veloschems in schematics/velo). Background thread; reports in Redstone Mode's message line and to
	 * {@code done} (the new file, or null) on the client thread.
	 */
	public static void convert(Path file, java.util.function.Consumer<Path> done) {
		Thread thread = new Thread(() -> {
			Path out = null;
			String error = null;
			try {
				String fileName = file.getFileName().toString();
				boolean toLitematic = !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".litematic");
				String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
				Clip clip = load(file);
				out = unique((toLitematic ? litematicFolder() : veloFolder()).resolve(base + (toLitematic ? ".litematic" : ".veloschem")));
				if (toLitematic) {
					saveLitematic(out, clip, base);
				} else {
					save(out, clip, base);
				}
			} catch (Exception e) {
				error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				out = null;
			}
			Path result = out;
			String failure = error;
			net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
				if (result != null) {
					RmState.info("Converted to " + result.getFileName());
				} else {
					RmState.error("Couldn't convert " + file.getFileName() + ": " + failure);
				}
				if (done != null) {
					done.accept(result);
				}
			});
		}, "velo-schematic-convert");
		thread.setDaemon(true);
		thread.start();
	}

	/** {@code file}, or "name (2).ext", "name (3).ext"... if it already exists. */
	private static Path unique(Path file) {
		if (!Files.exists(file)) {
			return file;
		}
		String fileName = file.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		String base = fileName.substring(0, dot);
		String ext = fileName.substring(dot);
		for (int i = 2; ; i++) {
			Path candidate = file.resolveSibling(base + " (" + i + ")" + ext);
			if (!Files.exists(candidate)) {
				return candidate;
			}
		}
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
		saveSelection(name, false);
	}

	/**
	 * Saves the current selection as {@code name}.veloschem, or as {@code name}.litematic for friends on Litematica
	 * (captured and written on the server thread).
	 */
	public static void saveSelection(String name, boolean litematic) {
		int[] b = RmState.box();
		if (b == null) {
			RmState.error("Select an area first");
			return;
		}
		String clean = name.replaceAll("[^A-Za-z0-9 _.-]", "_").strip();
		String base = clean.isEmpty() ? "redstone" : clean;
		Path file = litematic ? litematicFolder().resolve(base + ".litematic") : veloFolder().resolve(base + ".veloschem");
		RmWorld.onServer(level -> {
			Clip clip = RmOps.capture(level, b[0], b[1], b[2], b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1, true);
			// Captured and written on the server thread - a schematic is small (palette + packed indices).
			try {
				if (litematic) {
					saveLitematic(file, clip, base);
				} else {
					save(file, clip, clean);
				}
				net.minecraft.client.MinecraftClient.getInstance().execute(() -> RmState.info("Saved " + file.getFileName()
						+ (litematic ? " - in schematics/, ready for Litematica" : " - find it under Schematics > Velo")));
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
