package net.veloclient.velo.client.pvp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Counts how many Totems of Undying each player popped (entity event 35, the same packet that plays the totem
 * animation for everyone around). Kept per UUID, so a player walking out of render distance and back - a new
 * entity id - keeps their count. A count ends when that life ends: their death (entity event 3, health 0, or a
 * death line in chat naming them), you leaving the world/server, or a long quiet spell. A finished count stays on
 * screen a few seconds marked as dead, so you see what it took.
 */
public final class TotemPopTracker {

	public static final class Entry {
		public final UUID uuid;
		public String name;
		public int pops;
		public long lastPopMillis;
		/** When they died with pops on the clock (0 = alive). */
		public long diedMillis;
		public boolean self;
		int entityId;

		Entry(UUID uuid, String name) {
			this.uuid = uuid;
			this.name = name;
		}

		public boolean dead() {
			return diedMillis != 0;
		}
	}

	/** How long a finished (dead) count stays visible. */
	public static final long DEAD_SHOWN_MILLIS = 5000;
	private static final String[] DEATH_WORDS = {"slain", "killed", "died", "was shot", "eliminated", "fell", "blew up", "burned",
			"drowned", "was finished", "defeated", "doomed", "pummeled", "was struck", "exploded", "popped and died"};

	private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();
	private static Object lastWorld;
	private static volatile boolean enabled;
	private static boolean registered;
	private static long resetAfterMillis = 120_000;
	private static java.util.function.BiConsumer<Entry, Boolean> listener;

	private TotemPopTracker() {
	}

	/** Turned on by the Totem Pops module; does nothing while off. */
	public static synchronized void configure(boolean on, long resetAfter, java.util.function.BiConsumer<Entry, Boolean> onChange) {
		enabled = on;
		resetAfterMillis = resetAfter;
		listener = onChange;
		if (!on) {
			ENTRIES.clear();
		}
		if (on && !registered) {
			registered = true;
			PvpCompat.onChatText(TotemPopTracker::onChat);
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		}
	}

	public static synchronized void clear() {
		ENTRIES.clear();
	}

	/** Entity event from the server (EnemyTotemMixin): 35 = totem pop, 3 = death. */
	public static void onEntityEvent(int entityId, byte status) {
		if (!enabled || (status != 35 && status != 3)) {
			return;
		}
		Entity entity = PvpCompat.entityById(entityId);
		if (!(entity instanceof LivingEntity living) || !PvpCompat.isPlayer(living)) {
			return;
		}
		synchronized (TotemPopTracker.class) {
			if (status == 35) {
				pop(living);
			} else {
				died(ENTRIES.get(PvpCompat.uuid(living)));
			}
		}
	}

	private static void pop(LivingEntity player) {
		UUID uuid = PvpCompat.uuid(player);
		if (uuid == null) {
			return;
		}
		boolean self = player.getId() == PvpCompat.localPlayerId();
		Entry e = ENTRIES.get(uuid);
		if (e == null || e.dead()) {
			// a new life: a dead entry still on screen starts over
			e = new Entry(uuid, self ? "You" : PvpCompat.name(player));
			ENTRIES.remove(uuid);
			ENTRIES.put(uuid, e);
		}
		long now = System.currentTimeMillis();
		// Some servers resend the event within the same tick (proxies, replay plugins): one pop.
		if (now - e.lastPopMillis < 40) {
			return;
		}
		e.self = self;
		e.entityId = player.getId();
		if (!self) {
			e.name = PvpCompat.name(player);
		}
		e.pops++;
		e.lastPopMillis = now;
		notifyListener(e, false);
	}

	private static void died(Entry e) {
		if (e == null || e.dead()) {
			return;
		}
		e.diedMillis = System.currentTimeMillis();
		notifyListener(e, true);
	}

	private static void notifyListener(Entry e, boolean death) {
		var l = listener;
		if (l == null) {
			return;
		}
		try {
			l.accept(e, death);
		} catch (RuntimeException ex) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Totem pop listener failed", ex);
		}
	}

	/** A death line naming someone we count for: their count is over (servers that skip the death event). */
	private static synchronized void onChat(String line) {
		if (!enabled || ENTRIES.isEmpty()) {
			return;
		}
		String lower = line.toLowerCase(java.util.Locale.ROOT);
		boolean death = false;
		for (String w : DEATH_WORDS) {
			if (lower.contains(w)) {
				death = true;
				break;
			}
		}
		if (!death) {
			return;
		}
		for (Entry e : ENTRIES.values()) {
			if (!e.self && !e.dead() && containsName(line, e.name)) {
				died(e);
			}
		}
	}

	private static boolean containsName(String line, String name) {
		if (name == null || name.isEmpty()) {
			return false;
		}
		int at = line.indexOf(name);
		while (at >= 0) {
			boolean startOk = at == 0 || !isNameChar(line.charAt(at - 1));
			int end = at + name.length();
			boolean endOk = end >= line.length() || !isNameChar(line.charAt(end));
			if (startOk && endOk) {
				return true;
			}
			at = line.indexOf(name, at + 1);
		}
		return false;
	}

	private static boolean isNameChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	/** Every client tick while enabled: world changes, deaths the event missed, expiry. */
	public static synchronized void tick() {
		if (!enabled) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		Object world = client.world;
		if (world != lastWorld) {
			// another server, a new match world or a dimension change: counts start over
			lastWorld = world;
			ENTRIES.clear();
			return;
		}
		long now = System.currentTimeMillis();
		Iterator<Entry> it = ENTRIES.values().iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			if (e.dead()) {
				if (now - e.diedMillis > DEAD_SHOWN_MILLIS) {
					it.remove();
				}
				continue;
			}
			Entity entity = PvpCompat.entityById(e.entityId);
			if (entity instanceof LivingEntity living && PvpCompat.uuid(living) != null && PvpCompat.uuid(living).equals(e.uuid)) {
				if (!living.isAlive() || living.getHealth() <= 0) {
					died(e);
					continue;
				}
			}
			if (now - e.lastPopMillis > resetAfterMillis && !(FightTracker.inFight() && FightTracker.opponentId() == e.entityId)) {
				it.remove();
			}
		}
	}

	/** What the HUD shows: your current opponent first, then the latest pops; at most {@code max}. */
	public static synchronized List<Entry> visible(int max, boolean includeSelf) {
		List<Entry> list = new ArrayList<>();
		for (Entry e : ENTRIES.values()) {
			if (e.pops > 0 && (includeSelf || !e.self)) {
				list.add(e);
			}
		}
		int opponent = FightTracker.opponentId();
		list.sort(Comparator.<Entry>comparingInt(e -> e.entityId == opponent && !e.dead() ? 0 : 1)
				.thenComparingInt(e -> e.dead() ? 1 : 0)
				.thenComparing(Comparator.<Entry>comparingLong(e -> e.lastPopMillis).reversed()));
		return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
	}

	/** Whether this entry is the player you're fighting right now. */
	public static boolean isOpponent(Entry e) {
		return !e.self && FightTracker.inFight() && FightTracker.opponentId() == e.entityId;
	}
}
