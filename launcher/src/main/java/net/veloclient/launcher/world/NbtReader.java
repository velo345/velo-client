package net.veloclient.launcher.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal read-only NBT decoder - just enough of Minecraft's binary NBT
 * format to pull a few fields (world name, gamemode, hardcore flag) back out
 * of a save's {@code level.dat} for the launcher's Datapacks tab world
 * picker. Not a general-purpose NBT library: compound tags decode to a
 * plain {@code Map<String, Object>} (nested compounds/lists included), lists
 * decode to {@code java.util.List<Object>}, everything else to its boxed
 * Java primitive/String - enough to navigate and read leaf values without
 * needing a whole typed tag hierarchy for a feature this narrow.
 */
public final class NbtReader {

	private static final int TAG_END = 0;
	private static final int TAG_BYTE = 1;
	private static final int TAG_SHORT = 2;
	private static final int TAG_INT = 3;
	private static final int TAG_LONG = 4;
	private static final int TAG_FLOAT = 5;
	private static final int TAG_DOUBLE = 6;
	private static final int TAG_BYTE_ARRAY = 7;
	private static final int TAG_STRING = 8;
	private static final int TAG_LIST = 9;
	private static final int TAG_COMPOUND = 10;
	private static final int TAG_INT_ARRAY = 11;
	private static final int TAG_LONG_ARRAY = 12;

	private NbtReader() {
	}

	/** Reads a gzip-compressed NBT file (e.g. {@code level.dat}) and returns its root compound's contents. */
	public static Map<String, Object> readGzipCompound(InputStream rawIn) throws IOException {
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(rawIn))) {
			int rootType = in.readUnsignedByte();
			if (rootType != TAG_COMPOUND) {
				throw new IOException("Expected a root TAG_Compound, got tag type " + rootType);
			}
			skipString(in); // root compound's own (usually empty) name
			return readCompoundBody(in);
		}
	}

	private static Map<String, Object> readCompoundBody(DataInputStream in) throws IOException {
		Map<String, Object> map = new LinkedHashMap<>();
		while (true) {
			int type = in.readUnsignedByte();
			if (type == TAG_END) {
				return map;
			}
			String name = readString(in);
			map.put(name, readPayload(in, type));
		}
	}

	private static Object readPayload(DataInputStream in, int type) throws IOException {
		return switch (type) {
			case TAG_BYTE -> in.readByte();
			case TAG_SHORT -> in.readShort();
			case TAG_INT -> in.readInt();
			case TAG_LONG -> in.readLong();
			case TAG_FLOAT -> in.readFloat();
			case TAG_DOUBLE -> in.readDouble();
			case TAG_BYTE_ARRAY -> {
				int len = in.readInt();
				byte[] bytes = new byte[len];
				in.readFully(bytes);
				yield bytes;
			}
			case TAG_STRING -> readString(in);
			case TAG_LIST -> {
				int elementType = in.readUnsignedByte();
				int count = in.readInt();
				var list = new java.util.ArrayList<>(Math.max(0, count));
				for (int i = 0; i < count; i++) {
					list.add(elementType == TAG_END ? null : readPayload(in, elementType));
				}
				yield list;
			}
			case TAG_COMPOUND -> readCompoundBody(in);
			case TAG_INT_ARRAY -> {
				int len = in.readInt();
				int[] ints = new int[len];
				for (int i = 0; i < len; i++) {
					ints[i] = in.readInt();
				}
				yield ints;
			}
			case TAG_LONG_ARRAY -> {
				int len = in.readInt();
				long[] longs = new long[len];
				for (int i = 0; i < len; i++) {
					longs[i] = in.readLong();
				}
				yield longs;
			}
			default -> throw new IOException("Unsupported/unknown NBT tag type " + type);
		};
	}

	private static String readString(DataInputStream in) throws IOException {
		int len = in.readUnsignedShort();
		byte[] bytes = new byte[len];
		in.readFully(bytes);
		return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static void skipString(DataInputStream in) throws IOException {
		int len = in.readUnsignedShort();
		in.skipBytes(len);
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> compound(Map<String, Object> parent, String key) {
		Object value = parent.get(key);
		return value instanceof Map ? (Map<String, Object>) value : Map.of();
	}

	public static String string(Map<String, Object> compound, String key, String fallback) {
		Object value = compound.get(key);
		return value instanceof String s ? s : fallback;
	}

	public static int intValue(Map<String, Object> compound, String key, int fallback) {
		Object value = compound.get(key);
		return value instanceof Number n ? n.intValue() : fallback;
	}

	public static boolean byteAsBoolean(Map<String, Object> compound, String key, boolean fallback) {
		Object value = compound.get(key);
		return value instanceof Number n ? n.byteValue() != 0 : fallback;
	}
}
