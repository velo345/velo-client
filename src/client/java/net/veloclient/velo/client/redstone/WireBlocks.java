package net.veloclient.velo.client.redstone;

import java.util.List;
import java.util.Map;

/** Blocks the wiring tools build with: what's offered, and which one "Auto" picks. */
public final class WireBlocks {

	public static final List<String> CHOICES = List.of("Auto", "Smooth Stone", "Stone", "Stone Bricks", "Polished Andesite",
			"White Concrete", "Gray Concrete", "Light Gray Concrete", "Black Concrete", "Red Concrete", "Quartz Block");

	private static final Map<String, String> IDS = Map.ofEntries(
			Map.entry("Smooth Stone", "minecraft:smooth_stone"),
			Map.entry("Stone", "minecraft:stone"),
			Map.entry("Stone Bricks", "minecraft:stone_bricks"),
			Map.entry("Polished Andesite", "minecraft:polished_andesite"),
			Map.entry("White Concrete", "minecraft:white_concrete"),
			Map.entry("Gray Concrete", "minecraft:gray_concrete"),
			Map.entry("Light Gray Concrete", "minecraft:light_gray_concrete"),
			Map.entry("Black Concrete", "minecraft:black_concrete"),
			Map.entry("Red Concrete", "minecraft:red_concrete"),
			Map.entry("Quartz Block", "minecraft:quartz_block"));

	private WireBlocks() {
	}

	/**
	 * The block to put under dust: the configured one, or for "Auto" the block the start sits on /
	 * is (when that's a full conducting block, so dust works on it - never glass or slabs), else
	 * smooth stone.
	 */
	public static String supportId(Object level, int[] start) {
		String chosen = IDS.get(RedstoneModeModule.wireBlock);
		if (chosen != null) {
			return chosen;
		}
		if (level != null && start != null) {
			for (int dy : new int[] {0, -1}) {
				int x = start[0];
				int y = start[1] + dy;
				int z = start[2];
				Object state = RmWorld.state(level, x, y, z);
				String id = RmWorld.id(state);
				if (RmWorld.conductor(level, x, y, z) && !id.contains("redstone") && !id.contains("observer") && !id.contains("piston")
						&& !id.contains("dispenser") && !id.contains("dropper") && !id.contains("note_block") && !id.contains("target")
						&& !id.contains("lamp")) {
					return id;
				}
			}
		}
		return "minecraft:smooth_stone";
	}

	public static String wallId() {
		return switch (RedstoneModeModule.waterWalls) {
			case "Stone Bricks" -> "minecraft:stone_bricks";
			case "Wire Block" -> IDS.getOrDefault(RedstoneModeModule.wireBlock, "minecraft:smooth_stone");
			default -> "minecraft:glass";
		};
	}
}
