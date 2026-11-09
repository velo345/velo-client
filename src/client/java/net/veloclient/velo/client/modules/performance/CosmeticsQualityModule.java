package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cosmetics Quality: how much detail cosmetics get, so a crowded lobby full of wings, auras and bodywear doesn't eat the
 * frame rate (owner, 2026-11-06; three levels since 2026-11-08 - "1100 FPS down to 100" with many wearers in full).
 * <ul>
 * <li><b>High</b>: your own cosmetics in full; other players in full only when they're among the nearest {@code budget}
 * wearers and within {@code fullDistance} - the rest lighter. Below 60 FPS the budget halves. Others' bodywear past
 * {@code wearDistance} isn't drawn.</li>
 * <li><b>Medium</b> (default, the former Lite): the light version on everyone, yourself included - wings without glow,
 * shine and particles, fewer aura particles, the simplified bodywear mesh; others' bodywear only up close (12 blocks);
 * other players' animated cloaks change frames at most 20 times a second.</li>
 * <li><b>Low</b>: Medium plus far fewer vertices (bodywear at ~500 instead of ~2,000 triangles), half the aura particles
 * again, others' bodywear within 6 blocks, others' wings and auras within 24, cloaks at half the texture resolution and
 * other players' animated cloaks at 10 frames a second.</li>
 * </ul>
 * The ranking is rebuilt from what was drawn in the last ~50 ms, so it costs nothing per cosmetic.
 */
public final class CosmeticsQualityModule extends AbstractModule implements Configurable {

	public static final List<String> MODES = List.of("High", "Medium", "Low");

	private static volatile String mode = "Medium";
	private static volatile int budget = 8;
	private static volatile double fullDistance = 24;
	private static volatile double wearDistance = 32;
	private static volatile boolean enabled = true;

	private record Seen(int id, double distSq) {
	}

	private static final List<Seen> SEEN = new ArrayList<>();
	private static Set<Integer> fullDetail = Set.of();
	private static long rankedAt;

	public CosmeticsQualityModule() {
		super("cosmetics-quality", "Cosmetics Quality",
				"High, Medium or Low detail for wings, auras, bodywear and cloaks - Medium keeps crowded lobbies smooth.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, true);
	}

	@Override
	public void onEnable() {
		enabled = true;
	}

	@Override
	public void onDisable() {
		enabled = false;
	}

	/** 0 = High, 1 = Medium, 2 = Low. */
	public static int level() {
		String m = effectiveMode();
		return "Low".equals(m) ? 2 : "High".equals(m) || "Full".equals(m) ? 0 : 1;
	}

	public static boolean low() {
		return level() == 2;
	}

	// Saved profiles name the old modes: Full = High; Lite and the old default Auto = Medium (the new default).
	private static void setMode(String value) {
		mode = switch (value == null ? "" : value) {
			case "High", "Full" -> "High";
			case "Low" -> "Low";
			default -> "Medium";
		};
		net.veloclient.velo.client.cosmetics.AnimatedCapeAsset.qualityChanged();
	}

	private static final String FORCED = System.getProperty("velo.cosmeticsQuality");   // bench override

	private static String effectiveMode() {
		if (FORCED != null) {
			return FORCED;
		}
		if (PerformanceBoostModule.lightCosmetics) {
			return "Medium"; // the old Performance Boost switch, from profiles saved before this module
		}
		return enabled ? mode : "Full";
	}

	/**
	 * Whether a wearer's wings and aura get the Lite version this frame. {@code entityId}: the wearer, {@code distSq}: its
	 * squared distance to you (0 for yourself).
	 */
	public static boolean lite(int entityId, boolean self, double distSq) {
		String m = effectiveMode();
		if ("Full".equals(m)) {
			return false;
		}
		if (!"High".equals(m)) {
			return true;
		}
		if (self) {
			return false;
		}
		note(entityId, distSq);
		return distSq > fullDistance * fullDistance || !fullDetail.contains(entityId);
	}

	/** Whether someone else's bodywear this far away is skipped. */
	public static boolean hideWear(boolean self, double distSq) {
		if (self) {
			return false;
		}
		String m = effectiveMode();
		if ("Full".equals(m)) {
			return false;
		}
		double limit = "Low".equals(m) ? Math.min(6, wearDistance) : "Medium".equals(m) ? Math.min(12, wearDistance) : wearDistance;
		return distSq > limit * limit;
	}

	/** Cloth cloak steps per second ({@code self}: your own cloak): High 240/120, Medium 144/72, Low 60/30. */
	public static float clothRate(boolean self) {
		int l = level();
		float own = l == 0 ? 240 : l == 1 ? 144 : 60;
		return self ? own : own / 2;
	}

	/** Cloth constraint passes per step: High 4, Medium 3, Low 2. */
	public static int clothIterations() {
		int l = level();
		return l == 0 ? 4 : l == 1 ? 3 : 2;
	}

	/** How long wings poses and aura particles are reused before they're rebuilt (nanoseconds). */
	public static long poseNanos(boolean self) {
		int l = level();
		if (l == 0 || l == 1 && self) {
			return 13_333_333L;   // 75 Hz
		}
		return l == 1 || self ? 25_000_000L : 40_000_000L;   // 40 Hz / 25 Hz
	}

	private static final int[] GRID_FULL = {1, 1}, GRID_HALF = {2, 2}, GRID_QUARTER = {4, 2};

	/**
	 * Cloth cloak mesh: every n-th column / row of the 9 x 15 cloth grid. High all; Medium others half (4x fewer
	 * vertices); Low yours half and others a quarter of the columns.
	 */
	public static int[] capeGrid(boolean self) {
		int l = level();
		if (l == 0 || l == 1 && self) {
			return GRID_FULL;
		}
		return l == 2 && !self ? GRID_QUARTER : GRID_HALF;
	}

	/** Low: someone else's wings and aura further than 24 blocks aren't drawn. */
	public static boolean hideFar(boolean self, double distSq) {
		return !self && low() && distSq > 24 * 24;
	}

	/** Texels per cape pixel cloak textures are decoded at: High keeps the RAM-based default, Low halves it. */
	public static int capeTexels(int ramDefault) {
		int l = level();
		return l == 2 ? Math.max(4, ramDefault / 2) : l == 1 ? Math.min(ramDefault, 8) : ramDefault;
	}

	/** Shortest time between two frame changes of someone else's animated cloak (each is a texture upload); 0 = no limit. */
	public static long otherCapeFrameNanos() {
		int l = level();
		return l == 2 ? 100_000_000L : l == 1 ? 50_000_000L : 0L;
	}

	private static synchronized void note(int id, double distSq) {
		SEEN.add(new Seen(id, distSq));
		long now = System.nanoTime();
		if (now - rankedAt < 50_000_000L) {
			return;
		}
		rankedAt = now;
		int allowed = budget;
		int fps = currentFps();
		if (fps > 0 && fps < 60) {
			allowed = Math.max(2, allowed / 2);
		}
		SEEN.sort((a, b) -> Double.compare(a.distSq(), b.distSq()));
		Set<Integer> next = new HashSet<>();
		for (Seen s : SEEN) {
			if (next.size() >= allowed) {
				break;
			}
			next.add(s.id());
		}
		fullDetail = next;
		SEEN.clear();
	}

	private static int currentFps() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().getCurrentFps();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().getFps();
		*///?}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Quality", MODES, () -> mode, CosmeticsQualityModule::setMode),
				new ConfigField.ToggleField("GPU Cosmetics (much faster in F5, 26.2+)", () -> net.veloclient.velo.client.cosmetics.render.GpuCosmetics.enabled,
						v -> net.veloclient.velo.client.cosmetics.render.GpuCosmetics.enabled = v),
				new ConfigField.HeaderField("High"),
				new ConfigField.SliderField("Full Detail Players", 1, 32, () -> budget, v -> budget = (int) Math.round(v),
						v -> String.valueOf((int) Math.round(v))),
				new ConfigField.SliderField("Full Detail Distance", 8, 64, () -> fullDistance, v -> fullDistance = Math.round(v),
						v -> Math.round(v) + " blocks"),
				new ConfigField.SliderField("Others' Bodywear Distance", 8, 64, () -> wearDistance, v -> wearDistance = Math.round(v),
						v -> Math.round(v) + " blocks"));
	}
}
