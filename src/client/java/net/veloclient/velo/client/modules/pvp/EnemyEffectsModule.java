package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.EnemyTracker;
import net.veloclient.velo.client.pvp.NameTagLines;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Other players' potion effects above their head: the effect's own icon with roughly how long it
 * has left. Which effects someone has comes from their potion swirls; the time is exact when we saw
 * them drink/eat/get splashed, otherwise a guess from the usual potion length (shown with "~").
 * See {@link EnemyTracker}.
 */
public final class EnemyEffectsModule extends AbstractModule implements Configurable {

	private static EnemyEffectsModule instance;

	private boolean showTime = true;
	private boolean showLevel = true;
	private boolean onlyBeneficial = false;
	private boolean markGuesses = true;
	private double maxIcons = 6;

	public EnemyEffectsModule() {
		super("enemy-effects", "Enemy Effects", "Shows other players' potion effects and roughly how long they last above their heads.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		NameTagLines.register(20, EnemyEffectsModule::line);
	}

	@Override
	public void onEnable() {
		EnemyTracker.acquire();
	}

	@Override
	public void onDisable() {
		EnemyTracker.release();
	}

	private static Object line(int entityId, boolean hasScoreLine) {
		EnemyEffectsModule m = instance;
		if (m == null || !m.isEnabled()) {
			return null;
		}
		EnemyTracker.Tracked tracked = EnemyTracker.get(entityId);
		if (tracked == null || tracked.effects.isEmpty()) {
			return null;
		}
		List<EnemyTracker.TrackedEffect> effects = new ArrayList<>(tracked.effects.values());
		effects.sort((a, b) -> Long.compare(b.endTick, a.endTick));
		var text = PvpCompat.empty();
		int shown = 0;
		for (EnemyTracker.TrackedEffect effect : effects) {
			PvpCompat.EffectInfo info = PvpCompat.effectInfo(effect.id);
			if (m.onlyBeneficial && info != null && !info.beneficial()) {
				continue;
			}
			if (shown++ >= (int) m.maxIcons) {
				break;
			}
			if (shown > 1) {
				text.append(PvpCompat.literal("  ", 0xFFFFFF));
			}
			text.append(PvpCompat.sprite("mob_effect/" + effect.id));
			if (m.showLevel && effect.amplifier > 0) {
				text.append(PvpCompat.literal(" " + roman(effect.amplifier + 1), 0xFFE08A));
			}
			if (m.showTime) {
				long left = Math.max(0, effect.endTick - EnemyTracker.now());
				int color = info == null || info.beneficial() ? 0xD9F2FF : 0xFF9A9A;
				String time = (effect.estimated && m.markGuesses ? "~" : "") + formatTicks(left);
				text.append(PvpCompat.literal(" " + time, effect.estimated ? 0xB8B8B8 : color));
			}
		}
		return shown == 0 ? null : text;
	}

	static String formatTicks(long ticks) {
		long seconds = (ticks + 19) / 20;
		return seconds >= 60 ? (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60) : seconds + "s";
	}

	private static String roman(int n) {
		return switch (n) {
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> String.valueOf(n);
		};
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show Time Left", () -> showTime, v -> showTime = v),
				new ConfigField.ToggleField("Show Level (II, III...)", () -> showLevel, v -> showLevel = v),
				new ConfigField.ToggleField("Only Beneficial Effects", () -> onlyBeneficial, v -> onlyBeneficial = v),
				new ConfigField.ToggleField("Mark Guessed Times With ~", () -> markGuesses, v -> markGuesses = v),
				new ConfigField.SliderField("Max Icons", 1, 10, () -> maxIcons, v -> maxIcons = Math.round(v), v -> String.valueOf((int) Math.round(v))));
	}
}
