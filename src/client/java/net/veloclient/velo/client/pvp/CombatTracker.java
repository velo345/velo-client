package net.veloclient.velo.client.pvp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Confirmed hits by the local player, for Combo Counter, Reach Display, Target HUD and Hit
 * Particles. An attack only counts as a hit once the target's hurt animation actually restarts
 * within a few ticks (the server accepted the damage) - swings at a player who's still
 * invulnerable, blocking with a shield or out of reach don't count.
 */
public final class CombatTracker {

	/** Called with the target entity id for every confirmed hit by you; {@code ownHit} false for others' hits (any nearby hurt). */
	public interface HitListener {
		void onHit(net.minecraft.entity.LivingEntity target, boolean ownHit, boolean crit);
	}

	private static final int CONFIRM_TICKS = 8;
	private static final List<HitListener> LISTENERS = new ArrayList<>();
	private static final Map<Integer, Integer> LAST_HURT = new HashMap<>();

	private static int pendingTarget = Integer.MIN_VALUE;
	private static double pendingReach;
	private static boolean pendingCrit;
	private static long pendingTick;
	private static long tick;

	private static int combo;
	private static long lastHitTick = -1000;
	private static double lastReach = -1;
	private static int lastTargetId = Integer.MIN_VALUE;
	private static long lastTargetTick = -1000;
	private static int selfLastHurt;
	private static boolean lastHitWasCrit;

	private CombatTracker() {
	}

	public static void register() {
		PvpCompat.onLocalAttack(CombatTracker::onAttack);
	}

	public static synchronized void addListener(HitListener listener) {
		LISTENERS.add(listener);
	}

	private static synchronized void onAttack(int entityId) {
		var entity = PvpCompat.entityById(entityId);
		if (entity == null) {
			return;
		}
		pendingTarget = entityId;
		pendingTick = tick;
		pendingReach = PvpCompat.reachTo(entity);
		pendingCrit = PvpCompat.isCritAttack();
		lastTargetId = entityId;
		lastTargetTick = tick;
	}

	public static synchronized void tick() {
		tick++;
		var self = PvpCompat.localPlayer();
		if (self == null) {
			combo = 0;
			LAST_HURT.clear();
			return;
		}
		// Getting hit yourself ends your combo.
		int selfHurt = PvpCompat.hurtTime(self);
		if (selfHurt > selfLastHurt && selfHurt >= PvpCompat.maxHurtTime(self) - 1) {
			combo = 0;
		}
		selfLastHurt = selfHurt;
		if (tick - lastHitTick > 60) {
			combo = 0;
		}
		Map<Integer, Integer> seen = new HashMap<>();
		for (var living : PvpCompat.livingNear(32)) {
			int id = living.getId();
			int hurt = PvpCompat.hurtTime(living);
			int previous = LAST_HURT.getOrDefault(id, 0);
			seen.put(id, hurt);
			boolean freshlyHurt = hurt > previous && hurt >= PvpCompat.maxHurtTime(living) - 1;
			if (!freshlyHurt) {
				continue;
			}
			boolean ownHit = id == pendingTarget && tick - pendingTick <= CONFIRM_TICKS;
			if (ownHit) {
				combo++;
				lastHitTick = tick;
				lastReach = pendingReach;
				pendingTarget = Integer.MIN_VALUE;
				lastHitWasCrit = pendingCrit;
			}
			for (HitListener listener : LISTENERS) {
				listener.onHit(living, ownHit, ownHit && pendingCrit);
			}
		}
		LAST_HURT.clear();
		LAST_HURT.putAll(seen);
	}

	public static synchronized int combo() {
		return combo;
	}

	public static synchronized long ticksSinceHit() {
		return tick - lastHitTick;
	}

	/** Reach of your last confirmed hit in blocks, or -1. */
	public static synchronized double lastReach() {
		return lastReach;
	}

	/** Who you last attacked, if within {@code maxTicks}; else Integer.MIN_VALUE. */
	public static synchronized int recentTarget(int maxTicks) {
		return tick - lastTargetTick <= maxTicks ? lastTargetId : Integer.MIN_VALUE;
	}
}
