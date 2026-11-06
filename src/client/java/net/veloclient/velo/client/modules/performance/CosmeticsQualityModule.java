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
 * frame rate (owner, 2026-11-06).
 * <ul>
 * <li><b>Auto</b> (default): your own cosmetics in full; other players in full only when they're among the nearest
 * {@code budget} wearers and within {@code fullDistance} - the rest get the Lite version (wings without glow, shine and
 * particles, fewer aura particles). Below 60 FPS the budget halves. Others' bodywear past {@code wearDistance} isn't drawn.</li>
 * <li><b>Full</b>: everything in full detail at any distance.</li>
 * <li><b>Lite</b>: the Lite version on everyone, yourself included; others' bodywear only up close.</li>
 * </ul>
 * The ranking is rebuilt from what was drawn in the last ~50 ms, so it costs nothing per cosmetic.
 */
public final class CosmeticsQualityModule extends AbstractModule implements Configurable {

	public static final List<String> MODES = List.of("Auto", "Full", "Lite");

	private static volatile String mode = "Auto";
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
				"Full detail where it counts: Auto keeps your own and the nearest players' cosmetics in full and lightens the rest.",
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

	private static String effectiveMode() {
		if (PerformanceBoostModule.lightCosmetics) {
			return "Lite"; // the old Performance Boost switch, from profiles saved before this module
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
		if ("Lite".equals(m)) {
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
		double limit = "Lite".equals(m) ? Math.min(12, wearDistance) : wearDistance;
		return distSq > limit * limit;
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
				new ConfigField.ChoiceField("Quality", MODES, () -> mode, v -> mode = v),
				new ConfigField.ToggleField("GPU Cosmetics (much faster in F5, 26.2+)", () -> net.veloclient.velo.client.cosmetics.render.GpuCosmetics.enabled,
						v -> net.veloclient.velo.client.cosmetics.render.GpuCosmetics.enabled = v),
				new ConfigField.HeaderField("Auto"),
				new ConfigField.SliderField("Full Detail Players", 1, 32, () -> budget, v -> budget = (int) Math.round(v),
						v -> String.valueOf((int) Math.round(v))),
				new ConfigField.SliderField("Full Detail Distance", 8, 64, () -> fullDistance, v -> fullDistance = Math.round(v),
						v -> Math.round(v) + " blocks"),
				new ConfigField.SliderField("Others' Bodywear Distance", 8, 64, () -> wearDistance, v -> wearDistance = Math.round(v),
						v -> Math.round(v) + " blocks"));
	}
}
