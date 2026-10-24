package net.veloclient.velo.client.modules.pvp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.pvp.CombatTracker;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/** Hits you've landed in a row without getting hit back (resets after 3 s without a hit). */
public final class ComboCounterModule extends AbstractModule implements HudModule, Configurable {

	private final HudPosition position = new HudPosition(0.02f, 0.40f);
	private boolean hideWhenZero = true;
	private boolean showBackground = false;
	private boolean colorByCombo = true;

	public ComboCounterModule() {
		super("combo-counter", "Combo Counter", "Counts the hits you land in a row without getting hit back.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		int combo = CombatTracker.combo();
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		if (combo == 0 && hideWhenZero && !editing) {
			return;
		}
		String label = (editing && combo == 0 ? 3 : combo) + " Combo";
		int color = !colorByCombo ? 0xFFFFFFFF : combo >= 5 ? 0xFFFF5CCB : combo >= 3 ? 0xFFFFC53D : 0xFFFFFFFF;
		if (net.veloclient.velo.client.hud.HudLook.on()) {
			if (showBackground && !net.veloclient.velo.client.hud.HudLook.hasBackground()) {
				// The module's own background still shows when HUD Style has none.
				VeloDraw.fillRounded(context, x - 4, y - 3, width() + 8, height() + 6, 6, 0x90101014);
			}
			net.veloclient.velo.client.hud.HudLook.stat(context, x, y, String.valueOf(editing && combo == 0 ? 3 : combo), "combo", color);
			return;
		}
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, width(), height(), 6, 0x90101014);
		}
		int textWidth = net.veloclient.velo.client.hud.HudLook.measure(label);
		net.veloclient.velo.client.hud.HudLook.draw(context, label, x + (width() - textWidth) / 2, y + 6, color);
	}

	@Override
	public int width() {
		if (net.veloclient.velo.client.hud.HudLook.on()) {
			return net.veloclient.velo.client.hud.HudLook.statWidth("00", "combo");
		}
		return 64;
	}

	@Override
	public int height() {
		if (net.veloclient.velo.client.hud.HudLook.on()) {
			return net.veloclient.velo.client.hud.HudLook.statHeight();
		}
		return 20;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Hide At Zero", () -> hideWhenZero, v -> hideWhenZero = v),
				new ConfigField.ToggleField("Color By Combo", () -> colorByCombo, v -> colorByCombo = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
