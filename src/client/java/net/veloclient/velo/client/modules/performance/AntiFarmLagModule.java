package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Makes big redstone farms cheaper for your PC to watch, without changing what they do. The server
 * (or your singleplayer world) keeps ticking every farm in simulation distance exactly as before -
 * this only lightens what the client redraws:
 * <ul>
 * <li><b>Rebuild coalescing</b> - every block change makes the client re-mesh that 16x16x16
 * section; a blinking observer or a piston line does that 10-20 times a second per section. Sections
 * that keep changing are re-meshed at most every few ticks instead (latest state always shown, it
 * just updates at e.g. 5 fps instead of 20). Your own block placing/breaking is never delayed.</li>
 * <li><b>Piston animations</b> - moving pistons/blocks further away than the set distance skip the
 * 2-tick slide (they still appear in their new place).</li>
 * <li><b>Item stacks</b> - dropped item stacks draw one model instead of up to five.</li>
 * <li><b>Particles</b> - a per-tick budget for particles, and for block-break effects (64
 * particles each: piston/TNT/flying-machine farms break hundreds of blocks a second). Blocks you
 * break yourself always get their particles.</li>
 * <li><b>Sounds</b> - the same block sound (piston, dispenser, explosion...) plays at most a few
 * times per tick instead of hundreds of overlapping copies.</li>
 * </ul>
 * <b>Redstone sound volume</b> scales the sounds redstone machines make (pistons, dispensers,
 * droppers, repeaters/comparators, TNT, crafters, flying-machine slime/honey...) - 0% mutes them.
 * In <b>Auto</b> mode all of this only kicks in while redstone is actually running near you:
 * several sections around you that keep changing several times a second, for a few seconds in a
 * row. Just standing near redstone parts (a lobby full of hoppers) or loading chunks doesn't count.
 */
public final class AntiFarmLagModule extends AbstractModule implements Configurable {

	/** Re-marks a section dirty through the renderer's own method (set by the section mixin). */
	public interface SectionFlusher {
		void dirty(int x, int y, int z);
	}

	private static final List<String> MODES = List.of("Auto", "Always On");
	private static final List<String> SENSITIVITY = List.of("Low", "Medium", "High");
	private static AntiFarmLagModule instance;
	private static SectionFlusher flusher;
	/** True while the flusher itself is re-marking sections (so the mixin lets those through). */
	public static boolean flushing;

	private String mode = "Auto";
	private String sensitivity = "Medium";
	private double rebuildInterval = 5;
	private double pistonDistance = 24;
	private boolean simplifyItems = true;
	private double particleBudget = 300;
	private double breakEffects = 6;
	private double soundRepeats = 3;
	private boolean notify = true;
	private double redstoneVolume = 100;
	private boolean redstoneVolumeOnlyFarmMode = false;

	private boolean active;
	private long tick;
	/** Sections near you that kept changing this second (redstone clocks, farms) - see {@link #sectionDecision}. */
	private final java.util.Set<Long> hotThisSecond = new java.util.HashSet<>();
	private int lastHot;
	private long worldSince;
	private int hotSeconds;
	private int coldSeconds;
	private int particlesThisTick;
	private int breaksThisTick;
	private final Map<String, Integer> soundsThisTick = new HashMap<>();
	private final Map<Long, long[]> sections = new HashMap<>();
	private Object lastWorld;

	public AntiFarmLagModule() {
		super("anti-farm-lag", "Anti Farm Lag", "Lightens rendering near big redstone farms (observers, pistons, hoppers, TNT) without stopping them.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
	}

	private static AntiFarmLagModule on() {
		AntiFarmLagModule m = instance;
		return m != null && m.isEnabled() ? m : null;
	}

	/** Whether the lighter rendering is applied right now. */
	public static boolean active() {
		AntiFarmLagModule m = on();
		return m != null && m.active;
	}

	public static void setFlusher(SectionFlusher sectionFlusher) {
		flusher = sectionFlusher;
	}

	@Override
	public void onDisable() {
		setActive(false);
	}

	// ---------------------------------------------------------------- tick

	public static void tickAll() {
		AntiFarmLagModule m = instance;
		if (m != null && m.isEnabled()) {
			m.tick();
		}
	}

	private void tick() {
		tick++;
		particlesThisTick = 0;
		breaksThisTick = 0;
		soundsThisTick.clear();
		Object world = AntiFarmLagCompat.world();
		if (world != lastWorld) {
			lastWorld = world;
			sections.clear();
			hotThisSecond.clear();
			worldSince = tick;
			setActive(false);
		}
		if (world == null) {
			return;
		}
		flushDue(false);
		if (tick % 20 == 0) {
			lastHot = hotThisSecond.size();
			hotThisSecond.clear();
			boolean hot;
			if (mode.equals("Always On")) {
				hot = true;
			} else {
				int needed = switch (sensitivity) {
					case "Low" -> 10;
					case "High" -> 3;
					default -> 5;
				};
				// Ignore the join/teleport burst: chunks and their light arriving dirty sections several times.
				hot = tick - worldSince > 200 && lastHot >= needed;
			}
			hotSeconds = hot ? hotSeconds + 1 : 0;
			coldSeconds = hot ? 0 : coldSeconds + 1;
			if (!active && (mode.equals("Always On") || hotSeconds >= 3)) {
				setActive(true);
			} else if (active && !mode.equals("Always On") && coldSeconds >= 10) {
				setActive(false);
			}
		}
		if (tick % 200 == 0) {
			sections.values().removeIf(state -> tick - state[0] > 600 && state[2] == 0);
		}
	}

	private void setActive(boolean value) {
		if (active == value) {
			return;
		}
		active = value;
		if (!value) {
			flushDue(true);
			sections.clear();
		}
		if (notify && mode.equals("Auto") && isEnabled()) {
			NotificationOverlay.show(new NotificationOverlay.Toast(null, null, value ? "Farm mode on" : "Farm mode off",
					value ? "Lots of redstone nearby - rendering it lighter. Farms keep running." : "Back to normal rendering.",
					3500, null, null, null));
		}
	}

	// ---------------------------------------------------------------- section rebuilds

	/** {@link #sectionDecision} results. */
	public static final int ALLOW = 0;
	/** Rebuild it, but off the render thread (it changes too often to block a frame for). */
	public static final int ALLOW_ASYNC = 1;
	/** Skip for now; it's re-marked when its interval is up. */
	public static final int DEFER = 2;

	/**
	 * Called for every section the renderer (vanilla or Sodium) is about to re-mesh. In farm mode,
	 * sections that keep changing are re-meshed at most every few ticks, and never synchronously:
	 * Sodium and vanilla's "nearby" path rebuild sections next to the camera on the render thread,
	 * which is what made an observer clock right next to you drop FPS so hard. Sections that only
	 * change once in a while (your own building) are untouched.
	 * Section state: {lastAllowedTick, changeCount, pending(0/1), x, y, z, windowStartTick}.
	 */
	public static int sectionDecision(int x, int y, int z, boolean important) {
		AntiFarmLagModule m = instance;
		if (m == null || !m.isEnabled() || flushing) {
			return ALLOW;
		}
		long key = (((long) x) & 0x3FFFFF) << 42 | (((long) y) & 0xFFFFF) << 22 | (((long) z) & 0x3FFFFF);
		long[] state = m.sections.get(key);
		if (state == null) {
			// {lastAllowedTick, changesInWindow, pending, x, y, z, windowStart, firstSeen}
			m.sections.put(key, new long[] {m.tick, 1, 0, x, y, z, m.tick, m.tick});
			return ALLOW;
		}
		if (m.tick - state[6] > 20) {
			state[6] = m.tick;
			state[1] = 0;
		}
		state[1]++;
		// "Busy" = changed 3+ times within a second AND not just loaded (a freshly loaded section gets
		// marked several times by its chunk, light and neighbours - that's not a farm).
		boolean busy = state[1] >= 3 && m.tick - state[7] > 40;
		if (busy) {
			int[] camera = AntiFarmLagCompat.cameraSection();
			if (camera != null && Math.abs(x - camera[0]) <= 4 && Math.abs(z - camera[2]) <= 4) {
				m.hotThisSecond.add(key);
			}
		}
		if (!m.active || !busy) {
			state[0] = m.tick;
			return ALLOW;
		}
		if (m.tick - state[0] >= (long) m.rebuildInterval) {
			state[0] = m.tick;
			state[2] = 0;
			return important ? ALLOW_ASYNC : ALLOW;
		}
		state[2] = 1;
		return DEFER;
	}

	private void flushDue(boolean all) {
		if (flusher == null || sections.isEmpty()) {
			return;
		}
		flushing = true;
		try {
			Iterator<long[]> it = sections.values().iterator();
			while (it.hasNext()) {
				long[] state = it.next();
				if (state[2] == 1 && (all || tick - state[0] >= (long) rebuildInterval)) {
					state[0] = tick;
					state[2] = 0;
					flusher.dirty((int) state[3], (int) state[4], (int) state[5]);
				}
			}
		} finally {
			flushing = false;
		}
	}

	// ---------------------------------------------------------------- the rest

	/** Moving-piston render distance in blocks, given vanilla's. */
	public static int pistonDistance(int vanilla) {
		AntiFarmLagModule m = on();
		return m != null && m.active ? Math.min(vanilla, (int) m.pistonDistance) : vanilla;
	}

	public static boolean simplifyItems() {
		AntiFarmLagModule m = on();
		return m != null && m.active && m.simplifyItems;
	}

	/** True when this particle is over the per-tick budget. */
	public static boolean dropParticle() {
		AntiFarmLagModule m = on();
		return m != null && m.active && ++m.particlesThisTick > m.particleBudget;
	}

	/** True when this block-break effect should be skipped (over budget and not next to you). */
	public static boolean dropBreakEffect(double x, double y, double z) {
		AntiFarmLagModule m = on();
		if (m == null || !m.active) {
			return false;
		}
		double[] player = AntiFarmLagCompat.playerPos();
		if (player != null) {
			double dx = x - player[0];
			double dy = y - player[1];
			double dz = z - player[2];
			if (dx * dx + dy * dy + dz * dz < 36) {
				return false;
			}
		}
		return ++m.breaksThisTick > m.breakEffects;
	}

	/** True when the same block/neutral sound already played enough times this tick. */
	public static boolean dropSound(String id, String category) {
		AntiFarmLagModule m = on();
		if (m == null || !m.active || !(category.equalsIgnoreCase("blocks") || category.equalsIgnoreCase("neutral"))) {
			return false;
		}
		return m.soundsThisTick.merge(id, 1, Integer::sum) > m.soundRepeats;
	}

	/** Sound id paths (prefixes) counted as redstone machine sounds. */
	private static final List<String> REDSTONE_SOUNDS = List.of("block.piston.", "block.dispenser.", "block.comparator.",
			"block.lever.", "block.tripwire.", "block.crafter.", "block.redstone_torch.", "block.copper_bulb.", "entity.tnt.primed",
			"entity.generic.explode", "block.slime_block.", "block.honey_block.", "block.wooden_button.", "block.stone_button.",
			"block.wooden_pressure_plate.", "block.stone_pressure_plate.", "block.note_block.");

	/** Volume multiplier (0-1) for a sound, given its id ("minecraft:block.piston.extend"). */
	public static float redstoneVolume(String id) {
		AntiFarmLagModule m = on();
		if (m == null || m.redstoneVolume >= 100 || (m.redstoneVolumeOnlyFarmMode && !m.active)) {
			return 1f;
		}
		String path = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
		for (String prefix : REDSTONE_SOUNDS) {
			if (path.startsWith(prefix)) {
				return (float) (m.redstoneVolume / 100.0);
			}
		}
		return 1f;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Mode", MODES, () -> mode, v -> mode = v),
				new ConfigField.ChoiceField("Auto Sensitivity", SENSITIVITY, () -> sensitivity, v -> sensitivity = v),
				new ConfigField.SliderField("Rebuild Busy Sections Every (ticks)", 2, 10, () -> rebuildInterval, v -> rebuildInterval = Math.round(v),
						v -> (int) Math.round(v) + " (" + (20 / Math.max(1, (int) Math.round(v))) + "/s)"),
				new ConfigField.SliderField("Piston Animation Distance", 8, 64, () -> pistonDistance, v -> pistonDistance = Math.round(v),
						v -> (int) Math.round(v) + " blocks"),
				new ConfigField.ToggleField("Simplify Dropped Item Stacks", () -> simplifyItems, v -> simplifyItems = v),
				new ConfigField.SliderField("Particles Per Tick", 50, 2000, () -> particleBudget, v -> particleBudget = Math.round(v / 50) * 50,
						v -> String.valueOf((int) (Math.round(v / 50) * 50))),
				new ConfigField.SliderField("Block Break Effects Per Tick", 1, 40, () -> breakEffects, v -> breakEffects = Math.round(v),
						v -> String.valueOf((int) Math.round(v))),
				new ConfigField.SliderField("Same Sound Per Tick", 1, 10, () -> soundRepeats, v -> soundRepeats = Math.round(v),
						v -> String.valueOf((int) Math.round(v))),
				new ConfigField.SliderField("Redstone Sound Volume", 0, 100, () -> redstoneVolume, v -> redstoneVolume = Math.round(v / 5) * 5,
						v -> (int) (Math.round(v / 5) * 5) + "%"),
				new ConfigField.ToggleField("Redstone Volume Only In Farm Mode", () -> redstoneVolumeOnlyFarmMode, v -> redstoneVolumeOnlyFarmMode = v),
				new ConfigField.ToggleField("Notify When Farm Mode Turns On", () -> notify, v -> notify = v));
	}
}
