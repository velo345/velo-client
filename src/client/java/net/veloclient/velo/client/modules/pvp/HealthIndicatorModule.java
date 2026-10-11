package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.NameTagLines;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * Shows other players' health above their name tag - hearts drawn with the game's own heart
 * sprites (golden ones for absorption), a number, or both. Health is synced entity data every
 * client already receives; some servers deliberately hide it, then it can't show more than that.
 * Servers that already show health under names (a scoreboard "below name" line, e.g. PvP servers)
 * hide this by default so there aren't two health bars.
 */
public final class HealthIndicatorModule extends AbstractModule implements Configurable {

	private static final List<String> STYLES = List.of("Hearts", "Number", "Hearts + Number");
	private static HealthIndicatorModule instance;

	private String style = "Hearts + Number";
	private boolean onlyWhenDamaged = false;
	private boolean hideWhenServerShows = true;
	private boolean showAbsorption = true;
	private boolean colorNumber = true;

	public HealthIndicatorModule() {
		super("health-indicator", "Health Indicator", "Shows other players' hearts above their heads when they're not at full health.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		NameTagLines.register(0, HealthIndicatorModule::line);
	}

	private static Object line(int entityId, boolean hasScoreLine) {
		HealthIndicatorModule m = instance;
		if (m == null || !m.isEnabled()) {
			return null;
		}
		// The server already shows this player's health (under the name / on the tag): use theirs, not ours.
		if (m.hideWhenServerShows && (hasScoreLine || net.veloclient.velo.client.pvp.ServerHealth.get(entityId) != null)) {
			return null;
		}
		if (!(PvpCompat.entityById(entityId) instanceof net.minecraft.entity.LivingEntity living)) {
			return null;
		}
		float health = Math.max(0f, living.getHealth());
		float max = Math.max(1f, living.getMaxHealth());
		float absorption = m.showAbsorption ? Math.max(0f, living.getAbsorptionAmount()) : 0f;
		if (m.onlyWhenDamaged && health >= max && absorption <= 0f) {
			return null;
		}
		var text = PvpCompat.empty();
		if (!m.style.equals("Number")) {
			// Ten heart slots for the whole health bar, whatever the max health is.
			float halves = health / max * 20f;
			int full = (int) Math.floor(halves / 2f);
			boolean half = halves - full * 2 >= 1f;
			for (int i = 0; i < 10; i++) {
				String sprite = i < full ? "hud/heart/full" : i == full && half ? "hud/heart/half" : "hud/heart/container";
				text.append(PvpCompat.sprite(sprite));
			}
			int absorbHalves = (int) Math.ceil(absorption);
			for (int i = 0; i < Math.min(10, (absorbHalves + 1) / 2); i++) {
				text.append(PvpCompat.sprite(absorbHalves - i * 2 == 1 ? "hud/heart/absorbing_half" : "hud/heart/absorbing_full"));
			}
		}
		if (!m.style.equals("Hearts")) {
			float ratio = health / max;
			int color = !m.colorNumber ? 0xFFFFFF : ratio > 0.6f ? 0x6FE39A : ratio > 0.3f ? 0xFFC53D : 0xFF5555;
			String number = String.format(Locale.ROOT, health % 1f == 0f ? "%.0f" : "%.1f", health);
			if (!m.style.equals("Number")) {
				text.append(PvpCompat.literal(" ", 0xFFFFFF));
			}
			text.append(PvpCompat.literal(number, color));
			if (absorption > 0f) {
				text.append(PvpCompat.literal(String.format(Locale.ROOT, " +%.0f", absorption), 0xFFD84A));
			}
			if (m.style.equals("Number")) {
				text.append(PvpCompat.literal(" ", 0xFFFFFF)).append(PvpCompat.sprite("hud/heart/full"));
			}
		}
		return text;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ToggleField("Only When Damaged", () -> onlyWhenDamaged, v -> onlyWhenDamaged = v),
				new ConfigField.ToggleField("Hide When The Server Shows Health", () -> hideWhenServerShows, v -> hideWhenServerShows = v),
				new ConfigField.ToggleField("Show Absorption", () -> showAbsorption, v -> showAbsorption = v),
				new ConfigField.ToggleField("Color Number By Health", () -> colorNumber, v -> colorNumber = v));
	}
}
