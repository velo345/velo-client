package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.NameTagLines;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shows how much damage a player just took (and how much they healed) right above their name -
 * "-3.5" in red for a couple of seconds after a hit, quick hits in a row add up. From the health
 * every client already receives for players it can see - or, where the server shows health next
 * to names (PvP servers), from the server's own number.
 */
public final class DamageNumbersModule extends AbstractModule implements Configurable {

	private static DamageNumbersModule instance;

	private record Change(float amount, long untilTick) {
	}

	private final Map<Integer, Float> lastHealth = new HashMap<>();
	private final Map<Integer, Change> shown = new HashMap<>();
	private long tick;
	private boolean showHeals = true;
	private boolean asHearts = false;
	private double seconds = 1.5;

	public DamageNumbersModule() {
		super("damage-numbers", "Damage Numbers", "Shows the damage players take (and heal) above their heads for a moment after each hit.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		NameTagLines.register(-10, (id, hasScore) -> line(id));
	}

	public static void tickAll() {
		DamageNumbersModule m = instance;
		if (m == null || !m.isEnabled()) {
			return;
		}
		m.tick++;
		Map<Integer, Float> now = new HashMap<>();
		for (var player : PvpCompat.otherPlayers()) {
			// The server's own displayed health when it shows one - more trustworthy on PvP servers.
			Float shown = net.veloclient.velo.client.pvp.ServerHealth.get(player.getId());
			float health = shown != null ? shown : player.getHealth() + player.getAbsorptionAmount();
			now.put(player.getId(), health);
			Float before = m.lastHealth.get(player.getId());
			if (before == null || Math.abs(health - before) < 0.05f || health <= 0f) {
				continue;
			}
			float delta = health - before;
			if (delta > 0 && !m.showHeals) {
				continue;
			}
			Change previous = m.shown.get(player.getId());
			// Hits close together add up into one number.
			float total = previous != null && previous.untilTick() > m.tick && Math.signum(previous.amount()) == Math.signum(delta)
					? previous.amount() + delta : delta;
			m.shown.put(player.getId(), new Change(total, m.tick + Math.round(m.seconds * 20)));
		}
		m.lastHealth.clear();
		m.lastHealth.putAll(now);
		m.shown.values().removeIf(c -> c.untilTick() < m.tick);
	}

	private static Object line(int entityId) {
		DamageNumbersModule m = instance;
		if (m == null || !m.isEnabled()) {
			return null;
		}
		Change change = m.shown.get(entityId);
		if (change == null) {
			return null;
		}
		float amount = m.asHearts ? change.amount() / 2f : change.amount();
		String text = String.format(Locale.ROOT, amount % 1f == 0f ? "%+.0f" : "%+.1f", amount);
		return PvpCompat.literal(text + (m.asHearts ? " ❤" : ""), amount < 0 ? 0xFF5555 : 0x6FE39A);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show Healing Too", () -> showHeals, v -> showHeals = v),
				new ConfigField.ToggleField("In Hearts (÷2)", () -> asHearts, v -> asHearts = v),
				new ConfigField.SliderField("Show For", 0.5, 5, () -> seconds, v -> seconds = Math.round(v * 2) / 2.0,
						v -> String.format(Locale.ROOT, "%.1fs", v)));
	}
}
