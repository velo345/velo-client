package net.veloclient.velo.devbuild.rewind;

/**
 * Reads schematic files into block lists for the AI API's builds: vanilla structures (.nbt), Sponge schematics (.schem,
 * versions 2 and 3) and Litematica (.litematic, every region). Positions are relative to the schematic's corner; states
 * come as command block arguments ("minecraft:oak_stairs[facing=east,half=bottom]").
 */
//? if <26.2 {
final class Schematics {

	private Schematics() {
	}
}
//?} else {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Schematics {

	record Block(int x, int y, int z, String state) {
	}

	private Schematics() {
	}

	static List<Block> read(Path file) throws IOException {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		if (name.endsWith(".litematic")) {
			return litematic(root);
		}
		if (name.endsWith(".schem") || name.endsWith(".schematic")) {
			return sponge(root.getCompound("Schematic").orElse(root));
		}
		return structure(root);
	}

	// "minecraft:stairs" + {facing: east} -> minecraft:stairs[facing=east]
	private static String state(CompoundTag entry) {
		String block = entry.getStringOr("Name", "minecraft:air");
		CompoundTag props = entry.getCompoundOrEmpty("Properties");
		if (props.keySet().isEmpty()) {
			return block;
		}
		Map<String, String> sorted = new TreeMap<>();
		for (String k : props.keySet()) {
			sorted.put(k, props.getStringOr(k, ""));
		}
		StringBuilder sb = new StringBuilder(block).append('[');
		sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append(','));
		sb.setLength(sb.length() - 1);
		return sb.append(']').toString();
	}

	private static List<Block> structure(CompoundTag root) {
		ListTag palette = root.getListOrEmpty("palette");
		if (palette.size() == 0) {
			palette = root.getListOrEmpty("palettes").getCompoundOrEmpty(0).getListOrEmpty("palette");
		}
		List<String> states = new ArrayList<>();
		for (int i = 0; i < palette.size(); i++) {
			states.add(state(palette.getCompoundOrEmpty(i)));
		}
		List<Block> out = new ArrayList<>();
		ListTag blocks = root.getListOrEmpty("blocks");
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompoundOrEmpty(i);
			ListTag pos = b.getListOrEmpty("pos");
			int s = b.getIntOr("state", 0);
			if (s >= 0 && s < states.size()) {
				out.add(new Block(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0), states.get(s)));
			}
		}
		return out;
	}

	private static List<Block> sponge(CompoundTag root) {
		int w = root.getShortOr("Width", (short) 0) & 0xFFFF;
		int h = root.getShortOr("Height", (short) 0) & 0xFFFF;
		int l = root.getShortOr("Length", (short) 0) & 0xFFFF;
		CompoundTag blocks = root.getCompound("Blocks").orElse(null); // version 3
		CompoundTag paletteTag = blocks != null ? blocks.getCompoundOrEmpty("Palette") : root.getCompoundOrEmpty("Palette");
		byte[] data = (blocks != null ? blocks.getByteArray("Data") : root.getByteArray("BlockData")).orElse(new byte[0]);
		String[] palette = new String[paletteTag.keySet().size() + 1];
		for (String key : paletteTag.keySet()) {
			int id = paletteTag.getIntOr(key, -1);
			if (id >= 0 && id < palette.length) {
				palette[id] = key.contains(":") ? key : "minecraft:" + key;
			}
		}
		List<Block> out = new ArrayList<>();
		int index = 0;
		int i = 0;
		while (i < data.length && index < w * h * l) {
			int value = 0;
			int shift = 0;
			int b;
			do {
				b = data[i++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0 && i < data.length);
			int y = index / (w * l);
			int z = index % (w * l) / w;
			int x = index % w;
			if (value >= 0 && value < palette.length && palette[value] != null) {
				out.add(new Block(x, y, z, palette[value]));
			}
			index++;
		}
		return out;
	}

	private static List<Block> litematic(CompoundTag root) {
		List<Block> out = new ArrayList<>();
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		// every region, placed relative to the lowest corner of them all
		int baseX = Integer.MAX_VALUE;
		int baseY = Integer.MAX_VALUE;
		int baseZ = Integer.MAX_VALUE;
		for (String r : regions.keySet()) {
			int[] min = regionMin(regions.getCompoundOrEmpty(r));
			baseX = Math.min(baseX, min[0]);
			baseY = Math.min(baseY, min[1]);
			baseZ = Math.min(baseZ, min[2]);
		}
		for (String r : regions.keySet()) {
			CompoundTag region = regions.getCompoundOrEmpty(r);
			int[] min = regionMin(region);
			CompoundTag size = region.getCompoundOrEmpty("Size");
			int sx = Math.abs(size.getIntOr("x", 0));
			int sy = Math.abs(size.getIntOr("y", 0));
			int sz = Math.abs(size.getIntOr("z", 0));
			ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
			String[] palette = new String[paletteTag.size()];
			for (int i = 0; i < palette.length; i++) {
				palette[i] = state(paletteTag.getCompoundOrEmpty(i));
			}
			long[] bits = region.getLongArray("BlockStates").orElse(new long[0]);
			int perEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
			long mask = (1L << perEntry) - 1;
			long total = (long) sx * sy * sz;
			for (long index = 0; index < total; index++) {
				long start = index * perEntry;
				int word = (int) (start >> 6);
				int offset = (int) (start & 63);
				if (word >= bits.length) {
					break;
				}
				long value = bits[word] >>> offset;
				if (offset + perEntry > 64 && word + 1 < bits.length) {
					value |= bits[word + 1] << (64 - offset);
				}
				int id = (int) (value & mask);
				int x = (int) (index % sx);
				int z = (int) (index / sx % sz);
				int y = (int) (index / ((long) sx * sz));
				if (id < palette.length) {
					out.add(new Block(min[0] - baseX + x, min[1] - baseY + y, min[2] - baseZ + z, palette[id]));
				}
			}
		}
		return out;
	}

	// A region's lowest corner (its size can be negative: it then reaches back from its position).
	private static int[] regionMin(CompoundTag region) {
		CompoundTag pos = region.getCompoundOrEmpty("Position");
		CompoundTag size = region.getCompoundOrEmpty("Size");
		int[] out = new int[3];
		String[] axes = {"x", "y", "z"};
		for (int i = 0; i < 3; i++) {
			int p = pos.getIntOr(axes[i], 0);
			int s = size.getIntOr(axes[i], 0);
			out[i] = s < 0 ? p + s + 1 : p;
		}
		return out;
	}
}
*///?}
