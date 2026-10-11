package net.veloclient.velo.client.modules.pvp;

import net.minecraft.util.Identifier;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * A red glow around the screen edges when your health drops low (pulsing faster the lower it
 * gets), with an optional heartbeat sound - hard to miss mid-fight, unlike the hearts bar.
 */
public final class LowHealthWarningModule extends AbstractModule implements Configurable {

	private static LowHealthWarningModule instance;

	private double threshold = 6;
	private double intensity = 0.55;
	private boolean heartbeat = true;
	private int color = 0xFFFF2020;
	private long lastBeat;

	public LowHealthWarningModule() {
		super("low-health-warning", "Low Health Warning", "Red glow around the screen (and a heartbeat) when your health is low.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.HOTBAR,
				Identifier.of("velo-client", "low_health_warning"),
				(context, tickCounter) -> render(context));
	}

	private static void render(net.minecraft.client.gui.DrawContext context) {
		LowHealthWarningModule m = instance;
		var player = PvpCompat.localPlayer();
		if (m == null || !m.isEnabled() || player == null || player.getHealth() <= 0) {
			return;
		}
		float health = player.getHealth();
		if (health > m.threshold) {
			return;
		}
		float danger = 1f - (float) (health / m.threshold);
		double speed = 2.5 + danger * 5;
		float pulse = 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 1000.0 * speed);
		int alpha = Math.round(255 * (float) m.intensity * (0.4f + 0.6f * danger) * pulse);
		int edge = (Math.min(255, alpha) << 24) | (m.color & 0xFFFFFF);
		int clear = m.color & 0xFFFFFF;
		int w = context.getScaledWindowWidth();
		int h = context.getScaledWindowHeight();
		int depth = Math.max(24, Math.min(w, h) / 6);
		net.veloclient.velo.client.gui.widget.VeloDraw.fillGradient(context, 0, 0, w, depth, edge, clear);
		net.veloclient.velo.client.gui.widget.VeloDraw.fillGradient(context, 0, h - depth, w, depth, clear, edge);
		for (int i = 0; i < depth; i += 2) {
			int a = Math.round((Math.min(255, alpha)) * (1f - i / (float) depth));
			context.fill(i, 0, i + 2, h, (a << 24) | clear);
			context.fill(w - i - 2, 0, w - i, h, (a << 24) | clear);
		}
		if (m.heartbeat) {
			long now = System.currentTimeMillis();
			long interval = Math.round(1100 - danger * 600);
			if (now - m.lastBeat > interval) {
				m.lastBeat = now;
				PvpCompat.playUiSound("minecraft:block.note_block.basedrum", 0.6f, 0.5f + danger * 0.4f);
			}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Warn At Or Below", 1, 16, () -> threshold, v -> threshold = Math.round(v),
						v -> String.format(java.util.Locale.ROOT, "%.1f hearts", Math.round(v) / 2.0)),
				new ConfigField.SliderField("Intensity", 0.1, 1.0, () -> intensity, v -> intensity = Math.round(v * 20) / 20.0,
						v -> Math.round(v * 100) + "%"),
				new ConfigField.ColorField("Color", () -> color, v -> color = v, false),
				new ConfigField.ToggleField("Heartbeat Sound", () -> heartbeat, v -> heartbeat = v));
	}
}
