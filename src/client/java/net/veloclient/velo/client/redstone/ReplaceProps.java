package net.veloclient.velo.client.redstone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which properties a replaced block takes over from the one it replaces. Same-named ones carry over
 * as they are; the way a block points is converted between the different ways blocks store it -
 * {@code facing} (dispensers, observers, pistons...), {@code orientation} (crafters: "north_up"),
 * {@code axis} (logs, chains) and {@code rotation} (signs, heads: 0-15) - so a crafter becomes a
 * dispenser looking the same way. Stair {@code half} and slab {@code type} (top/bottom) map onto each
 * other too. Plain strings only, so it's easy to test.
 */
public final class ReplaceProps {

	/** How to turn the direction on the way: as is, 90° right, 180°, 90° left, or the opposite way (up/down too). */
	public enum Turn {
		NONE("As is"), RIGHT("90° right"), HALF("180°"), LEFT("90° left"), OPPOSITE("Opposite");

		public final String label;

		Turn(String label) {
			this.label = label;
		}

		public Turn next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	public record Options(boolean keepProperties, boolean keepDirection, Turn turn) {
	}

	private static final Set<String> DIRECTION_PROPS = Set.of("facing", "orientation", "axis", "rotation");
	private static final List<String> HORIZONTAL = List.of("south", "west", "north", "east");

	private ReplaceProps() {
	}

	/** "id[a=b,c=d]" -> {a=b, c=d}. */
	public static Map<String, String> parse(String stateText) {
		Map<String, String> out = new LinkedHashMap<>();
		int bracket = stateText.indexOf('[');
		if (bracket < 0 || !stateText.endsWith("]")) {
			return out;
		}
		for (String pair : stateText.substring(bracket + 1, stateText.length() - 1).split(",")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				out.put(pair.substring(0, eq), pair.substring(eq + 1));
			}
		}
		return out;
	}

	/**
	 * The properties to set on the new block: {@code source} is the old block's, {@code targetProps} the
	 * property names the new block has, {@code explicit} what the "with" text sets itself (never overridden).
	 */
	public static Map<String, String> carry(Map<String, String> source, Set<String> targetProps, Set<String> explicit, Options options) {
		Map<String, String> out = new LinkedHashMap<>();
		if (options.keepProperties()) {
			for (Map.Entry<String, String> e : source.entrySet()) {
				if (targetProps.contains(e.getKey()) && !explicit.contains(e.getKey()) && !DIRECTION_PROPS.contains(e.getKey())) {
					out.put(e.getKey(), e.getValue());
				}
			}
			// Stairs/trapdoors "half" <-> slabs "type" (top / bottom).
			if (source.containsKey("half") && targetProps.contains("type") && !targetProps.contains("half") && !explicit.contains("type")
					&& Set.of("top", "bottom").contains(source.get("half"))) {
				out.put("type", source.get("half"));
			}
			if (source.containsKey("type") && targetProps.contains("half") && !targetProps.contains("type") && !explicit.contains("half")
					&& Set.of("top", "bottom").contains(source.get("type"))) {
				out.put("half", source.get("type"));
			}
		}
		if (!options.keepDirection()) {
			return out;
		}
		String[] direction = direction(source);
		if (direction == null) {
			return out;
		}
		String dir = turn(direction[0], options.turn());
		String top = direction[1];
		if (options.turn() == Turn.OPPOSITE && top != null) {
			top = opposite(top);
		} else if (top != null && HORIZONTAL.contains(top)) {
			top = turn(top, options.turn());
		}
		if (targetProps.contains("facing") && !explicit.contains("facing")) {
			out.put("facing", dir);
		}
		if (targetProps.contains("orientation") && !explicit.contains("orientation")) {
			boolean vertical = dir.equals("up") || dir.equals("down");
			String second = vertical ? (top != null && HORIZONTAL.contains(top) ? top : "north") : "up";
			out.put("orientation", dir + "_" + second);
		}
		if (targetProps.contains("axis") && !explicit.contains("axis")) {
			out.put("axis", switch (dir) {
				case "east", "west" -> "x";
				case "up", "down" -> "y";
				default -> "z";
			});
		}
		if (targetProps.contains("rotation") && !explicit.contains("rotation") && HORIZONTAL.contains(dir)) {
			out.put("rotation", String.valueOf(HORIZONTAL.indexOf(dir) * 4));
		}
		return out;
	}

	/** {direction, top-or-null} the old block points in, or null if it has none. */
	static String[] direction(Map<String, String> p) {
		if (p.containsKey("facing")) {
			return new String[] {p.get("facing"), null};
		}
		if (p.containsKey("orientation")) {
			String[] parts = p.get("orientation").split("_");
			return parts.length == 2 ? parts : null;
		}
		if (p.containsKey("axis")) {
			return new String[] {switch (p.get("axis")) {
				case "x" -> "east";
				case "y" -> "up";
				default -> "south";
			}, null};
		}
		if (p.containsKey("rotation")) {
			try {
				int r = Math.floorMod(Math.round(Integer.parseInt(p.get("rotation")) / 4f), 4);
				return new String[] {HORIZONTAL.get(r), null};
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	static String turn(String dir, Turn turn) {
		if (turn == Turn.OPPOSITE) {
			return opposite(dir);
		}
		int i = HORIZONTAL.indexOf(dir);
		if (i < 0 || turn == Turn.NONE) {
			return dir;
		}
		int steps = switch (turn) {
			case RIGHT -> 1;
			case HALF -> 2;
			case LEFT -> 3;
			default -> 0;
		};
		return HORIZONTAL.get((i + steps) % 4);
	}

	static String opposite(String dir) {
		return switch (dir) {
			case "up" -> "down";
			case "down" -> "up";
			case "north" -> "south";
			case "south" -> "north";
			case "east" -> "west";
			case "west" -> "east";
			default -> dir;
		};
	}
}
