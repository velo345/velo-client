package net.veloclient.velo.client.pvp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player estimates of what the client can't see directly about other players: how long their
 * effects have left, and their hunger and saturation. Ticked once per client tick, only while one
 * of the modules that shows this is on.
 *
 * <p><b>Effects:</b> which effects someone has is visible (their potion swirls). How long they last
 * isn't sent, so the duration comes from what we saw cause it: when they finish drinking a potion,
 * eating a golden apple, or get hit by a splash potion near them, we know the exact effects and
 * durations from that item. An effect that appears without a seen cause (before we were nearby,
 * beacons...) uses the usual potion duration and is marked as a guess.
 *
 * <p><b>Hunger:</b> the server never sends other players' food. It's simulated with vanilla's own
 * rules from what we can see: sprinting/swimming distance, jumps, natural regeneration, damage
 * taken all add exhaustion; finishing a food adds its nutrition and saturation. Seeing someone
 * sprint proves they have more than 6 food, so the estimate is corrected up then. Starts at full
 * (20 / 5) the first time a player is seen, after they respawn, and when the server evidently healed and
 * fed them (a sudden jump back to full health with no potion or apple seen - rounds resetting on PvP servers).
 */
public final class EnemyTracker {

	public static final class TrackedEffect {
		public final String id;
		public int amplifier;
		public long endTick;
		public long totalTicks;
		public boolean estimated;

		TrackedEffect(String id, int amplifier, long endTick, long totalTicks, boolean estimated) {
			this.id = id;
			this.amplifier = amplifier;
			this.endTick = endTick;
			this.totalTicks = totalTicks;
			this.estimated = estimated;
		}
	}

	public static final class Tracked {
		public final int id;
		public final Map<String, TrackedEffect> effects = new HashMap<>();
		public float hunger = 20f;
		public float saturation = 5f;
		float exhaustion;
		double lastX = Double.NaN;
		double lastY;
		double lastZ;
		boolean lastOnGround = true;
		float lastHealth = -1;
		float lastSwing;
		long lastSeenTick;
		// item use in progress
		Object usingStack;
		long usingSince;
		// effects we expect to appear (from a finished drink/eat or a splash), id -> spec, with expiry
		final Map<String, PvpCompat.EffectSpec> pending = new HashMap<>();
		long pendingUntil;

		Tracked(int id) {
			this.id = id;
		}
	}

	/** Usual (non-extended) potion durations in ticks - used when an effect appears without a seen cause. */
	private static final Map<String, Integer> TYPICAL_TICKS = Map.ofEntries(
			Map.entry("speed", 3600), Map.entry("strength", 3600), Map.entry("fire_resistance", 3600),
			Map.entry("regeneration", 900), Map.entry("invisibility", 3600), Map.entry("night_vision", 3600),
			Map.entry("jump_boost", 3600), Map.entry("water_breathing", 3600), Map.entry("slow_falling", 1800),
			Map.entry("slowness", 1800), Map.entry("weakness", 1800), Map.entry("poison", 900),
			Map.entry("absorption", 2400), Map.entry("resistance", 6000), Map.entry("turtle_master", 400),
			Map.entry("wither", 800), Map.entry("glowing", 600), Map.entry("levitation", 200),
			Map.entry("darkness", 260), Map.entry("blindness", 200), Map.entry("nausea", 300),
			Map.entry("hunger", 600), Map.entry("mining_fatigue", 6000), Map.entry("haste", 2400),
			Map.entry("luck", 6000), Map.entry("wind_charged", 3600), Map.entry("weaving", 3600),
			Map.entry("oozing", 3600), Map.entry("infested", 3600));

	private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
	private static Map<Integer, Object[]> lastPotions = new HashMap<>();
	private static long tick;
	private static int users;

	private EnemyTracker() {
	}

	/** Modules that read from the tracker register while enabled, so it costs nothing otherwise. */
	public static synchronized void acquire() {
		users++;
	}

	public static synchronized void release() {
		users = Math.max(0, users - 1);
		if (users == 0) {
			TRACKED.clear();
			lastPotions.clear();
		}
	}

	public static long now() {
		return tick;
	}

	public static synchronized Tracked get(int entityId) {
		return TRACKED.get(entityId);
	}

	public static synchronized void tick() {
		tick++;
		if (users == 0) {
			return;
		}
		Set<Integer> seen = new HashSet<>();
		for (var player : PvpCompat.otherPlayers()) {
			int id = player.getId();
			seen.add(id);
			Tracked t = TRACKED.computeIfAbsent(id, Tracked::new);
			t.lastSeenTick = tick;
			trackItemUse(t, player);
			trackEffects(t, player);
			trackHunger(t, player);
		}
		trackSplashes(seen);
		Iterator<Map.Entry<Integer, Tracked>> it = TRACKED.entrySet().iterator();
		while (it.hasNext()) {
			Tracked t = it.next().getValue();
			// Out of sight for a minute (or respawned with a new entity id): forget them.
			if (tick - t.lastSeenTick > 1200) {
				it.remove();
			}
		}
	}

	// ---------------------------------------------------------------- totems

	/** A player popped a Totem of Undying: all effects are replaced by the totem's own. */
	public static synchronized void onTotem(int entityId) {
		if (users == 0) {
			return;
		}
		Tracked t = TRACKED.computeIfAbsent(entityId, Tracked::new);
		t.effects.clear();
		t.effects.put("regeneration", new TrackedEffect("regeneration", 1, tick + 900, 900, false));
		t.effects.put("absorption", new TrackedEffect("absorption", 1, tick + 100, 100, false));
		t.effects.put("fire_resistance", new TrackedEffect("fire_resistance", 0, tick + 800, 800, false));
	}

	// ---------------------------------------------------------------- effects

	private static void trackEffects(Tracked t, net.minecraft.entity.LivingEntity player) {
		if (tick > t.pendingUntil) {
			t.pending.clear();
		}
		Set<String> visible = new HashSet<>();
		for (PvpCompat.EffectInfo info : PvpCompat.visibleEffects(player)) {
			visible.add(info.id());
			TrackedEffect known = t.effects.get(info.id());
			PvpCompat.EffectSpec cause = t.pending.remove(info.id());
			if (known == null) {
				if (cause != null) {
					t.effects.put(info.id(), new TrackedEffect(info.id(), cause.amplifier(), tick + cause.durationTicks(), cause.durationTicks(), false));
				} else {
					int typical = TYPICAL_TICKS.getOrDefault(info.id(), 1200);
					t.effects.put(info.id(), new TrackedEffect(info.id(), 0, tick + typical, typical, true));
				}
			} else if (cause != null) {
				// Refreshed by a new potion while it was still running.
				known.amplifier = cause.amplifier();
				known.endTick = tick + cause.durationTicks();
				known.totalTicks = cause.durationTicks();
				known.estimated = false;
			}
		}
		t.effects.keySet().removeIf(id -> !visible.contains(id));
	}

	private static void expect(Tracked t, List<PvpCompat.EffectSpec> specs) {
		for (PvpCompat.EffectSpec spec : specs) {
			t.pending.put(spec.id(), spec);
			// Already showing (refresh): apply right away.
			TrackedEffect known = t.effects.get(spec.id());
			if (known != null) {
				known.amplifier = spec.amplifier();
				known.endTick = tick + spec.durationTicks();
				known.totalTicks = spec.durationTicks();
				known.estimated = false;
				t.pending.remove(spec.id());
			}
		}
		t.pendingUntil = tick + 20;
	}

	private static void trackSplashes(Set<Integer> players) {
		Map<Integer, Object[]> now = PvpCompat.thrownPotions();
		for (var entry : lastPotions.entrySet()) {
			if (now.containsKey(entry.getKey())) {
				continue;
			}
			// The potion entity is gone: it hit something here. Splash radius is 4 blocks, and the
			// duration scales down with distance (vanilla: 1 - distance / 4).
			Object[] data = entry.getValue();
			double px = (double) data[0];
			double py = (double) data[1];
			double pz = (double) data[2];
			@SuppressWarnings("unchecked")
			List<PvpCompat.EffectSpec> specs = (List<PvpCompat.EffectSpec>) data[3];
			if (specs.isEmpty()) {
				continue;
			}
			for (var player : PvpCompat.otherPlayers()) {
				double dx = player.getX() - px;
				double dy = player.getY() - py;
				double dz = player.getZ() - pz;
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (distance > 4.5) {
					continue;
				}
				Tracked t = TRACKED.computeIfAbsent(player.getId(), Tracked::new);
				double scale = Math.max(0.0, 1.0 - distance / 4.0);
				List<PvpCompat.EffectSpec> scaled = new ArrayList<>();
				for (PvpCompat.EffectSpec spec : specs) {
					scaled.add(new PvpCompat.EffectSpec(spec.id(), spec.amplifier(), (int) Math.round(spec.durationTicks() * Math.max(0.25, scale))));
				}
				expect(t, scaled);
			}
		}
		lastPotions = now;
	}

	// ---------------------------------------------------------------- eating / drinking

	private static void trackItemUse(Tracked t, net.minecraft.entity.LivingEntity player) {
		var active = PvpCompat.activeItem(player);
		if (!active.isEmpty()) {
			if (t.usingStack == null || !PvpCompat.sameItem((net.minecraft.item.ItemStack) t.usingStack, active)) {
				t.usingStack = PvpCompat.copy(active);
				t.usingSince = tick;
			}
			return;
		}
		if (t.usingStack == null) {
			return;
		}
		var finished = (net.minecraft.item.ItemStack) t.usingStack;
		long used = tick - t.usingSince;
		t.usingStack = null;
		int needed = PvpCompat.consumeTicks(finished);
		if (needed <= 0 || used < needed - 2) {
			return;
		}
		// They finished it (stopped exactly when it completes).
		if (PvpCompat.isPotion(finished)) {
			expect(t, PvpCompat.potionEffects(finished));
		}
		List<PvpCompat.EffectSpec> extra = PvpCompat.consumeEffects(finished);
		if (!extra.isEmpty()) {
			expect(t, extra);
		}
		float[] food = PvpCompat.food(finished);
		if (food != null) {
			t.hunger = Math.min(20f, t.hunger + food[0]);
			t.saturation = Math.min(t.hunger, t.saturation + food[1]);
		}
	}

	// ---------------------------------------------------------------- hunger

	private static boolean nearOtherPlayer(net.minecraft.entity.LivingEntity player) {
		var self = PvpCompat.localPlayer();
		if (self != null && dist2(self, player) < 25) {
			return true;
		}
		for (var other : PvpCompat.otherPlayers()) {
			if (other != player && dist2(other, player) < 25) {
				return true;
			}
		}
		return false;
	}

	private static double dist2(net.minecraft.entity.LivingEntity a, net.minecraft.entity.LivingEntity b) {
		double dx = a.getX() - b.getX();
		double dy = a.getY() - b.getY();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dy * dy + dz * dz;
	}

	private static void trackHunger(Tracked t, net.minecraft.entity.LivingEntity player) {
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		boolean onGround = PvpCompat.onGround(player);
		float health = player.getHealth();
		if (Double.isNaN(t.lastX)) {
			t.lastX = x;
			t.lastY = y;
			t.lastZ = z;
			t.lastHealth = health;
			t.lastOnGround = onGround;
			return;
		}
		double dx = x - t.lastX;
		double dz = z - t.lastZ;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		// Teleports, respawns: don't count, reset to full after a respawn.
		if (horizontal > 10) {
			horizontal = 0;
		}
		if (t.lastHealth <= 0 && health > 0) {
			t.hunger = 20f;
			t.saturation = 5f;
			t.exhaustion = 0;
		}
		// Many servers heal and feed everyone between rounds (/heal + /feed, kit reset). A big instant health gain with
		// no potion or golden apple seen is that: the player is fed again too, so the estimate starts over at full.
		float gained = health - t.lastHealth;
		boolean potionSeen = tick <= t.pendingUntil + 10;
		if (t.lastHealth > 0 && (gained >= 12f || gained >= 8f && !potionSeen) && health >= player.getMaxHealth() - 1f) {
			t.hunger = 20f;
			t.saturation = 5f;
			t.exhaustion = 0;
			t.lastHealth = health;
		}
		boolean sprinting = player.isSprinting();
		if (PvpCompat.isSwimming(player)) {
			t.exhaustion += (float) (0.01 * horizontal);
		} else if (sprinting) {
			t.exhaustion += (float) (0.1 * horizontal);
		}
		if (t.lastOnGround && !onGround && y > t.lastY) {
			t.exhaustion += sprinting ? 0.2f : 0.05f;
		}
		// Attacking costs 0.1 exhaustion; count swings while someone else is close enough to hit.
		float swing = PvpCompat.swingProgress(player);
		if (swing > 0f && t.lastSwing == 0f && nearOtherPlayer(player)) {
			t.exhaustion += 0.1f;
		}
		t.lastSwing = swing;
		TrackedEffect hungerEffect = t.effects.get("hunger");
		if (hungerEffect != null) {
			t.exhaustion += 0.005f * (hungerEffect.amplifier + 1);
		}
		if (health < t.lastHealth) {
			t.exhaustion += 0.1f;
		} else if (health > t.lastHealth && t.lastHealth > 0 && !t.effects.containsKey("regeneration")) {
			// Natural regeneration costs exhaustion per half heart and needs 18+ food.
			t.exhaustion += 6f * (health - t.lastHealth);
			t.hunger = Math.max(t.hunger, 18f);
		}
		while (t.exhaustion >= 4f) {
			t.exhaustion -= 4f;
			if (t.saturation > 0) {
				t.saturation = Math.max(0f, t.saturation - 1f);
			} else {
				t.hunger = Math.max(0f, t.hunger - 1f);
			}
		}
		// Sprinting is impossible at 6 food or less - seeing it proves they have more.
		if (sprinting && t.hunger <= 6f) {
			t.hunger = 7f;
		}
		t.lastX = x;
		t.lastY = y;
		t.lastZ = z;
		t.lastHealth = health;
		t.lastOnGround = onGround;
	}
}
