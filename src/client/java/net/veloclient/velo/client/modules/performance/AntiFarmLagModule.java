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
 * <li><b>Moving blocks</b> - blocks pistons are moving use flat lighting (rebuilt every frame, smooth light was the
 * biggest cost in a piston farm).</li>
 * <li><b>Light</b> - light checks in sections that keep changing are collected and released at a bounded rate per tick,
 * so light near the machine can trail by up to about a second.</li>
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

	/** Runs a held-back light check on the client's light engine (set by TurboFarmLightMixin). */
	public interface LightFlusher {
		void check(long blockPos);
	}

	/** Light checks released per tick: bounds the light engine's work in any one frame. */
	private static final int LIGHT_CHECKS_PER_TICK = 128;
	/** True while moving blocks are built in farm mode (flat lighting, see TurboSodiumFlatMovingMixin). */
	public static volatile boolean flatMovingBlocks;
	private static LightFlusher lightFlusher;
	private static boolean lightFlushing;
	private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet deferredLight = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();

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
	private boolean itemFarms = true;
	private double itemDetailDistance = 12;
	private double maxDrawnItems = 1000;
	private boolean itemCountLabels = true;
	private double labelMinItems = 8;
	private String labelFont = "HUD Style";
	private String labelBackground = "Soft";
	private double labelSize = 1.0;
	private static final List<String> LABEL_FONTS = java.util.stream.Stream.concat(java.util.stream.Stream.of("HUD Style"),
			net.veloclient.velo.client.modules.hud.HudStyleModule.FONTS.stream()).toList();
	private static final List<String> LABEL_BACKGROUNDS = List.of("None", "Soft", "Glass", "Solid");
	/** Item farm mode: lots of dropped items / XP orbs around (counted once a second), with or without redstone. */
	private boolean itemFarm;
	private int itemCount;
	private int itemCalmTicks;
	private double particleBudget = 300;
	private double breakEffects = 6;
	private double soundRepeats = 3;
	private boolean notify = false;
	private double redstoneVolume = 100;
	private boolean redstoneVolumeOnlyFarmMode = false;

	private boolean active;
	private long tick;
	/** Sections near you that kept changing this second (redstone clocks, farms) - see {@link #sectionDecision}. */
	// fastutil, not HashMap<Long, ...>: the packed x/y/z keys collided so badly in Long.hashCode that buckets turned into
	// trees - TreeNode.find showed up in every chunk-load hitch. These mix the hash and don't box.
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet hotThisSecond = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private int lastHot;
	private long worldSince;
	private int hotSeconds;
	private int coldSeconds;
	private int particlesThisTick;
	private int breaksThisTick;
	private final Map<String, Integer> soundsThisTick = new HashMap<>();
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[]> sections = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private Object lastWorld;

	public AntiFarmLagModule() {
		super("anti-farm-lag", "Anti Farm Lag", "Lightens rendering near big redstone farms (observers, pistons, hoppers, TNT) without stopping them.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
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
		if (tick % 20 == 0) {
			updateItemFarm();
		}
		breaksThisTick = 0;
		soundsThisTick.clear();
		Object world = AntiFarmLagCompat.world();
		if (world != lastWorld) {
			lastWorld = world;
			sections.clear();
			hotThisSecond.clear();
			deferredLight.clear();
			lightFlusher = null;
			worldSince = tick;
			setActive(false);
		}
		if (world == null) {
			return;
		}
		flushDue(false);
		releaseLight(LIGHT_CHECKS_PER_TICK);
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

	/**
	 * Farm mode: holds a client light check back when its section keeps changing. Render thread only - the integrated
	 * server's engine calls in from its own threads and always goes through.
	 */
	public static boolean deferLightCheck(long blockPos, int sectionX, int sectionY, int sectionZ, LightFlusher flusher) {
		AntiFarmLagModule m = on();
		if (m == null || !m.active || lightFlushing || !"Render thread".equals(Thread.currentThread().getName())) {
			return false;
		}
		long key = (((long) sectionX) & 0x3FFFFF) << 42 | (((long) sectionY) & 0xFFFFF) << 22 | (((long) sectionZ) & 0x3FFFFF);
		long[] state = m.sections.get(key);
		if (state == null || state[1] < 3) {
			return false;
		}
		lightFlusher = flusher;
		m.deferredLight.add(blockPos);
		return true;
	}

	private void releaseLight(int count) {
		LightFlusher flusher = lightFlusher;
		if (flusher == null || deferredLight.isEmpty()) {
			return;
		}
		lightFlushing = true;
		try {
			for (int i = 0; i < count && !deferredLight.isEmpty(); i++) {
				flusher.check(deferredLight.removeFirstLong());
			}
		} finally {
			lightFlushing = false;
		}
	}

	private void setActive(boolean value) {
		if (active == value) {
			return;
		}
		active = value;
		if (!value) {
			releaseLight(Integer.MAX_VALUE);
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
		// Sections are only ever deferred (pending) while farm mode is active, and switching it off
		// flushes everything - so outside farm mode there's nothing to walk the whole map for.
		if (flusher == null || sections.isEmpty() || (!active && !all)) {
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

	// ---------------------------------------------------------------- item farms

	/**
	 * Item farm mode: a mob / iron / gold farm's output is thousands of dropped items on hoppers and in water streams - few
	 * block changes, so the redstone detection never noticed it, while every item ran full client physics each tick and was
	 * checked and drawn each frame. Counted once a second within 48 blocks; on from about 200 items or orbs (sensitivity:
	 * 400 / 200 / 100), off again once they stay under half that for 5 seconds. Always On keeps it on.
	 */
	private void updateItemFarm() {
		if (!itemFarms) {
			itemFarm = false;
			return;
		}
		itemCount = countItemsNearby(48);
		int threshold = switch (sensitivity) {
			case "Low" -> 400;
			case "High" -> 100;
			default -> 200;
		};
		if (mode.equals("Always On") || itemCount >= threshold) {
			itemFarm = true;
			itemCalmTicks = 0;
		} else if (itemFarm && itemCount < threshold / 2) {
			itemCalmTicks += 20;
			if (itemCalmTicks >= 100) {
				itemFarm = false;
			}
		}
	}

	private static int countItemsNearby(double radius) {
		//? if >=26.2 {
		/*var client = net.minecraft.client.Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return 0;
		}
		double r2 = radius * radius;
		int n = 0;
		for (net.minecraft.world.entity.Entity e : client.level.entitiesForRendering()) {
			if ((e instanceof net.minecraft.world.entity.item.ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb)
					&& e.distanceToSqr(client.player) < r2) {
				n++;
			}
		}
		return n;
		*///?} else {
		return 0;
		//?}
	}

	/** True while item farm mode is on (see {@link #updateItemFarm()}). */
	public static boolean itemFarm() {
		AntiFarmLagModule m = on();
		return m != null && m.itemFarm;
	}

	/** Items closer than this keep full detail even in item farm mode (squared, blocks). */
	public static double itemDetailDistanceSq() {
		AntiFarmLagModule m = instance;
		double d = m == null ? 12 : m.itemDetailDistance;
		return d * d;
	}

	/** One group of dropped items drawn as a single item with a count (see {@link #itemCluster}). */
	public static final class ItemCluster {
		public double x;
		public double y;
		public double z;
		public int count;
		public boolean visible;
		/** Squared distance to the camera, filled in when the labels are sorted. */
		double sortKey;
	}

	private static it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ItemCluster> clusters = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	/** Last frame's groups, for the count labels on the HUD. */
	private static it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ItemCluster> shownClusters = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private static final java.util.ArrayDeque<ItemCluster> POOL = new java.util.ArrayDeque<>();

	/** A new frame of entity extraction: last frame's groups go to the labels, a fresh set starts. */
	public static void beginItemFrame() {
		itemFrames++;
		drawnItems = 0;
		for (ItemCluster c : shownClusters.values()) {
			POOL.add(c);
		}
		shownClusters.clear();
		var swap = shownClusters;
		shownClusters = clusters;
		clusters = swap;
	}

	private static int drawnItems;

	/**
	 * The "Max Drawn Items" budget for this frame: true = this item may be drawn. Past the budget only items within the
	 * detail distance still are (the near ones matter, far ones become a count anyway).
	 */
	public static boolean takeItemBudget(double distSq) {
		AntiFarmLagModule m = instance;
		int max = m == null ? 1000 : (int) m.maxDrawnItems;
		if (drawnItems < max || distSq <= itemDetailDistanceSq()) {
			drawnItems++;
			return true;
		}
		return false;
	}

	/** Frames that reset the per-frame item groups (dev: the item bench logs it). */
	public static int itemFrames;

	public static int itemSlotsUsed() {
		return shownClusters.size();
	}

	/**
	 * Item farm mode groups dropped items: a water stream or a hopper line full of items becomes a few items with a count
	 * ("x37") instead of hundreds of models. The group is a block cell near you and grows with distance (1, 2 then 4 blocks a
	 * side past 12 and 20 blocks), so far streams collapse more. Returns the group this item falls in; {@code first} = it is
	 * the one that gets drawn (the caller does the full visibility check for it and stores the answer).
	 */
	public static ItemCluster itemCluster(double x, double y, double z, double distSq, int stackCount, boolean[] first) {
		int size = distSq < 12 * 12 ? 1 : distSq < 20 * 20 ? 2 : 4;
		long key = ((long) (Math.floorDiv((int) Math.floor(x), size) & 0x1FFFFF) << 43)
				^ ((long) (Math.floorDiv((int) Math.floor(y), size) & 0xFFF) << 31)
				^ ((long) (Math.floorDiv((int) Math.floor(z), size) & 0x1FFFFF) << 10) ^ size;
		ItemCluster c = clusters.get(key);
		if (c == null) {
			c = POOL.isEmpty() ? new ItemCluster() : POOL.poll();
			c.x = x;
			c.y = y;
			c.z = z;
			c.count = stackCount;
			c.visible = false;
			clusters.put(key, c);
			first[0] = true;
			return c;
		}
		c.count += stackCount;
		first[0] = false;
		return c;
	}

	/** The "x37" labels over grouped items, nearest first, at most 96 (HUD layer). */
	public static void renderItemLabels(net.minecraft.client.gui.DrawContext context, int screenWidth, int screenHeight) {
		AntiFarmLagModule m = instance;
		if (!itemFarm() || shownClusters.isEmpty() || m == null || !m.itemCountLabels) {
			return;
		}
		int minItems = (int) m.labelMinItems;
		float scale = (float) m.labelSize;
		float previousPoseScale = net.veloclient.velo.client.gui.VeloFonts.setHudPoseScale(scale);
		try {
			renderItemLabels(context, screenWidth, screenHeight, m, minItems, scale);
		} finally {
			net.veloclient.velo.client.gui.VeloFonts.setHudPoseScale(previousPoseScale);
		}
	}

	private static void renderItemLabels(net.minecraft.client.gui.DrawContext context, int screenWidth, int screenHeight, AntiFarmLagModule m,
			int minItems, float scale) {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		java.util.List<ItemCluster> labelled = new java.util.ArrayList<>();
		for (ItemCluster c : shownClusters.values()) {
			if (c.visible && c.count >= minItems) {
				labelled.add(c);
			}
		}
		if (labelled.isEmpty()) {
			return;
		}
		for (ItemCluster c : labelled) {
			float[] v = net.veloclient.velo.client.util.ClientCompat.cameraView(c.x, c.y, c.z);
			c.sortKey = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
		}
		labelled.sort(java.util.Comparator.comparingDouble(c -> c.sortKey));
		int drawn = 0;
		// nearest first; a label that would overlap one already drawn is left out (busy streams stay readable)
		java.util.List<int[]> taken = new java.util.ArrayList<>();
		// never over the hotbar (and the hearts / XP bar right above it)
		taken.add(new int[] {screenWidth / 2 - 100, screenHeight - 48, screenWidth / 2 + 100, screenHeight});
		for (ItemCluster c : labelled) {
			if (drawn >= 40) {
				break;
			}
			float[] screen = net.veloclient.velo.client.util.ClientCompat.project(
					net.veloclient.velo.client.util.ClientCompat.cameraView(c.x, c.y + 0.75, c.z), screenWidth, screenHeight);
			if (screen == null || screen[0] < 0 || screen[0] > screenWidth || screen[1] < 0 || screen[1] > screenHeight) {
				continue;
			}
			String text = "x" + (c.count >= 10_000 ? (c.count / 1000) + "k" : String.valueOf(c.count));
			net.minecraft.text.Text label = m.labelFont.equals("HUD Style") ? net.veloclient.velo.client.hud.HudLook.value(text)
					: net.veloclient.velo.client.hud.HudLook.preview(m.labelFont, text);
			int tw = net.veloclient.velo.client.hud.HudLook.width(label);
			// box in label space (unscaled), centred on the cluster; the scale is applied around that centre
			int pad = m.labelBackground.equals("None") ? 1 : 3;
			float bw = (tw + pad * 2) * scale;
			float bh = (9 + 4) * scale;
			int cx = Math.round(screen[0]);
			int cy = Math.round(screen[1]);
			int[] box = {Math.round(cx - bw / 2) - 1, Math.round(cy - bh / 2) - 1, Math.round(cx + bw / 2) + 1, Math.round(cy + bh / 2) + 1};
			boolean overlaps = false;
			for (int[] t : taken) {
				if (box[0] < t[2] && box[2] > t[0] && box[1] < t[3] && box[3] > t[1]) {
					overlaps = true;
					break;
				}
			}
			if (overlaps) {
				continue;
			}
			taken.add(box);
			boolean scaled = scale != 1f;
			if (scaled) {
				context.getMatrices().pushMatrix();
				context.getMatrices().translate((float) cx, (float) cy);
				context.getMatrices().scale(scale, scale);
				context.getMatrices().translate((float) -cx, (float) -cy);
			}
			try {
				int lx = cx - tw / 2;
				int ly = cy - 4;
				int x1 = lx - pad;
				int y1 = ly - 2;
				int w = tw + pad * 2;
				switch (m.labelBackground) {
					case "Soft" -> context.fill(x1, y1, x1 + w, y1 + 11, 0x99101014);
					case "Glass" -> {
						net.veloclient.velo.client.gui.widget.VeloDraw.fillRounded(context, x1, y1, w, 11, 4, 0xA00C0C10);
						net.veloclient.velo.client.gui.widget.VeloDraw.strokeRounded(context, x1, y1, w, 11, 4, 0x1CFFFFFF);
					}
					case "Solid" -> net.veloclient.velo.client.gui.widget.VeloDraw.fillRounded(context, x1, y1, w, 11, 3, 0xF0141418);
					default -> {
					}
				}
				context.drawTextWithShadow(client.textRenderer, label, lx, m.labelFont.equals("Minecraft") ? ly : ly + 1, 0xFFF2F2F5);
			} finally {
				if (scaled) {
					context.getMatrices().popMatrix();
				}
			}
			drawn++;
		}
	}

	public static boolean simplifyItems() {
		AntiFarmLagModule m = on();
		return m != null && (m.active || m.itemFarm) && m.simplifyItems;
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
				new ConfigField.ToggleField("Item Farms (lighter dropped items when there are lots)", () -> itemFarms, v -> itemFarms = v),
				new ConfigField.SliderField("Full Item Detail Within (blocks)", 4, 32, () -> itemDetailDistance, v -> itemDetailDistance = Math.round(v),
						v -> Math.round(v) + " blocks"),
				new ConfigField.SliderField("Max Drawn Items", 100, 5000, () -> maxDrawnItems, v -> maxDrawnItems = Math.round(v / 100) * 100,
						v -> Math.round(v / 100) * 100 + " items"),
				new ConfigField.ToggleField("Item Count Labels (x37 over grouped items)", () -> itemCountLabels, v -> itemCountLabels = v),
				new ConfigField.SliderField("Label Groups From (items)", 2, 64, () -> labelMinItems, v -> labelMinItems = Math.round(v),
						v -> Math.round(v) + " items"),
				new ConfigField.ChoiceField("Label Font", LABEL_FONTS, () -> labelFont, v -> labelFont = v),
				new ConfigField.ChoiceField("Label Background", LABEL_BACKGROUNDS, () -> labelBackground, v -> labelBackground = v),
				new ConfigField.SliderField("Label Size", 0.5, 2.0, () -> labelSize, v -> labelSize = Math.round(v * 20) / 20.0,
						v -> Math.round(v * 100) + "%"),
				new ConfigField.SliderField("Particles Per Tick", 50, 2000, () -> particleBudget, v -> particleBudget = Math.round(v / 50) * 50,
						v -> String.valueOf((int) (Math.round(v / 50) * 50))),
				new ConfigField.SliderField("Block Break Effects Per Tick", 1, 40, () -> breakEffects, v -> breakEffects = Math.round(v),
						v -> String.valueOf((int) Math.round(v))),
				new ConfigField.SliderField("Same Sound Per Tick", 1, 10, () -> soundRepeats, v -> soundRepeats = Math.round(v),
						v -> String.valueOf((int) Math.round(v))),
				new ConfigField.SliderField("Redstone Sound Volume", 0, 100, () -> redstoneVolume, v -> redstoneVolume = Math.round(v / 5) * 5,
						v -> (int) (Math.round(v / 5) * 5) + "%"),
				new ConfigField.ToggleField("Redstone Volume Only In Farm Mode", () -> redstoneVolumeOnlyFarmMode, v -> redstoneVolumeOnlyFarmMode = v),
				new ConfigField.ToggleField("Farm Mode Notifications", () -> notify, v -> notify = v));
	}
}
