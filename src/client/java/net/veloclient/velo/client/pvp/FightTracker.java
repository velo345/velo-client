package net.veloclient.velo.client.pvp;

import net.minecraft.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Follows one fight at a time against another player and, when it ends, produces a
 * {@link Recap}: hits dealt and taken, crits, best combo, reach, damage both ways, a hit timeline
 * and who won. A fight starts with your first confirmed hit on a player (or the first hit you take
 * with a player close by), and ends when one of you dies, the opponent vanishes, or nobody lands a
 * hit for a few seconds. Fights with fewer than three exchanges aren't worth a recap and are dropped.
 */
public final class FightTracker {

	public enum Outcome { WON, LOST, ENDED }

	/** One hit on the timeline: milliseconds since the fight began, and whether you dealt it. */
	public record Event(long atMillis, boolean dealt, boolean crit) {
	}

	public record Recap(String opponent, String opponentUuid, String server, long startedAt, long durationMillis, Outcome outcome,
			int hitsDealt, int hitsTaken, int crits, int attacks, int bestCombo, double averageReach, double maxReach,
			float damageDealt, float damageTaken, float healthLeft, float opponentHealthLeft, List<Event> events) {

		public int accuracyPercent() {
			return attacks <= 0 ? 0 : Math.min(100, Math.round(hitsDealt * 100f / attacks));
		}
	}

	private static final int END_IDLE_TICKS = 160;
	private static final int MAX_RECAPS = 20;
	private static final List<Recap> RECAPS = new ArrayList<>();
	private static final List<Consumer<Recap>> LISTENERS = new ArrayList<>();

	private static int opponentId = Integer.MIN_VALUE;
	private static String opponentName;
	private static String opponentUuid;
	private static long startedAt;
	private static long lastExchangeTick;
	private static long tick;
	private static int hitsDealt;
	private static int hitsTaken;
	private static int crits;
	private static int attacks;
	private static int bestCombo;
	private static double reachSum;
	private static int reachCount;
	private static double maxReach;
	private static float damageDealt;
	private static float damageTaken;
	private static float lastOpponentHealth = -1;
	private static float lastSelfHealth = -1;
	private static int lastSelfHurt;
	private static float lastOpponentSeenHealth = 20;
	private static final List<Event> EVENTS = new ArrayList<>();

	private FightTracker() {
	}

	public static void register() {
		CombatTracker.addListener(FightTracker::onHit);
		PvpCompat.onLocalAttack(id -> {
			synchronized (FightTracker.class) {
				if (id == opponentId) {
					attacks++;
				} else if (PvpCompat.entityById(id) instanceof LivingEntity living && PvpCompat.isPlayer(living)) {
					// First swing of a fight that hasn't started yet still counts towards accuracy.
					pendingAttacks++;
				}
			}
		});
	}

	private static int pendingAttacks;

	public static synchronized void addListener(Consumer<Recap> listener) {
		LISTENERS.add(listener);
	}

	/** Newest first. */
	public static synchronized List<Recap> recaps() {
		List<Recap> copy = new ArrayList<>(RECAPS);
		java.util.Collections.reverse(copy);
		return copy;
	}

	public static synchronized Recap latest() {
		return RECAPS.isEmpty() ? null : RECAPS.getLast();
	}

	public static synchronized boolean inFight() {
		return opponentId != Integer.MIN_VALUE;
	}

	private static synchronized void onHit(LivingEntity target, boolean ownHit, boolean crit) {
		if (!ownHit || !PvpCompat.isPlayer(target)) {
			return;
		}
		if (opponentId != target.getId()) {
			if (opponentId != Integer.MIN_VALUE) {
				finish(Outcome.ENDED);
			}
			begin(target);
			attacks += pendingAttacks;
		}
		pendingAttacks = 0;
		hitsDealt++;
		if (crit) {
			crits++;
		}
		bestCombo = Math.max(bestCombo, CombatTracker.combo());
		double reach = CombatTracker.lastReach();
		if (reach > 0) {
			reachSum += reach;
			reachCount++;
			maxReach = Math.max(maxReach, reach);
		}
		EVENTS.add(new Event(System.currentTimeMillis() - startedAt, true, crit));
		lastExchangeTick = tick;
	}

	private static void begin(LivingEntity opponent) {
		opponentId = opponent.getId();
		opponentName = PvpCompat.name(opponent);
		opponentUuid = String.valueOf(PvpCompat.uuid(opponent));
		startedAt = System.currentTimeMillis();
		lastExchangeTick = tick;
		hitsDealt = hitsTaken = crits = attacks = bestCombo = reachCount = 0;
		reachSum = maxReach = 0;
		damageDealt = damageTaken = 0;
		lastOpponentHealth = opponent.getHealth() + opponent.getAbsorptionAmount();
		lastOpponentSeenHealth = lastOpponentHealth;
		EVENTS.clear();
	}

	public static synchronized void tick() {
		tick++;
		LivingEntity self = PvpCompat.localPlayer();
		if (self == null) {
			if (opponentId != Integer.MIN_VALUE) {
				finish(Outcome.ENDED);
			}
			lastSelfHealth = -1;
			return;
		}
		float selfHealth = self.getHealth() + self.getAbsorptionAmount();
		int selfHurt = PvpCompat.hurtTime(self);
		boolean freshlyHurt = selfHurt > lastSelfHurt && selfHurt >= PvpCompat.maxHurtTime(self) - 1;
		lastSelfHurt = selfHurt;

		if (freshlyHurt && opponentId == Integer.MIN_VALUE) {
			// Hit first by someone: the closest player within melee/bow-ish range is the opponent.
			LivingEntity closest = null;
			double best = 8;
			for (LivingEntity other : PvpCompat.otherPlayers()) {
				double d = PvpCompat.distanceTo(other);
				if (d < best) {
					best = d;
					closest = other;
				}
			}
			if (closest != null) {
				begin(closest);
				attacks += pendingAttacks;
				pendingAttacks = 0;
			}
		}
		if (opponentId == Integer.MIN_VALUE) {
			lastSelfHealth = selfHealth;
			return;
		}
		if (freshlyHurt) {
			hitsTaken++;
			EVENTS.add(new Event(System.currentTimeMillis() - startedAt, false, false));
			lastExchangeTick = tick;
		}
		if (lastSelfHealth >= 0 && selfHealth < lastSelfHealth) {
			damageTaken += lastSelfHealth - selfHealth;
		}
		lastSelfHealth = selfHealth;

		if (selfHealth <= 0 || !self.isAlive()) {
			finish(Outcome.LOST);
			return;
		}
		var entity = PvpCompat.entityById(opponentId);
		if (!(entity instanceof LivingEntity opponent)) {
			// Gone: killed (and removed) if they were low, otherwise they ran / logged / got out of range.
			finish(lastOpponentSeenHealth <= 6 && tick - lastExchangeTick < 40 ? Outcome.WON : Outcome.ENDED);
			return;
		}
		float health = opponent.getHealth() + opponent.getAbsorptionAmount();
		if (lastOpponentHealth >= 0 && health < lastOpponentHealth) {
			damageDealt += lastOpponentHealth - health;
		}
		lastOpponentHealth = health;
		lastOpponentSeenHealth = health;
		if (health <= 0 || !opponent.isAlive()) {
			finish(Outcome.WON);
			return;
		}
		if (tick - lastExchangeTick > END_IDLE_TICKS) {
			finish(Outcome.ENDED);
		}
	}

	private static void finish(Outcome outcome) {
		int exchanges = hitsDealt + hitsTaken;
		LivingEntity self = PvpCompat.localPlayer();
		Recap recap = new Recap(opponentName, opponentUuid, net.veloclient.velo.client.util.ClientCompat.currentServerAddress(), startedAt,
				System.currentTimeMillis() - startedAt - (outcome == Outcome.ENDED ? (tick - lastExchangeTick) * 50 : 0), outcome,
				hitsDealt, hitsTaken, crits, Math.max(attacks, hitsDealt), bestCombo, reachCount == 0 ? 0 : reachSum / reachCount, maxReach,
				damageDealt, damageTaken, self == null ? 0 : self.getHealth(), Math.max(0, lastOpponentSeenHealth), List.copyOf(EVENTS));
		opponentId = Integer.MIN_VALUE;
		EVENTS.clear();
		if (exchanges < 3) {
			return;
		}
		RECAPS.add(recap);
		while (RECAPS.size() > MAX_RECAPS) {
			RECAPS.removeFirst();
		}
		for (Consumer<Recap> listener : List.copyOf(LISTENERS)) {
			try {
				listener.accept(recap);
			} catch (RuntimeException e) {
				net.veloclient.velo.VeloClient.LOGGER.warn("Fight recap listener failed", e);
			}
		}
	}
}
