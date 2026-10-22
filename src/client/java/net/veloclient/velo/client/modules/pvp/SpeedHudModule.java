package net.veloclient.velo.client.modules.pvp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/** Your movement speed in blocks per second (horizontal, or including vertical). */
public final class SpeedHudModule extends AbstractModule implements HudModule, Configurable {

	private final HudPosition position = new HudPosition(0.02f, 0.50f);
	private final double[] samples = new double[10];
	private int index;
	private double lastX = Double.NaN;
	private double lastY;
	private double lastZ;
	private boolean includeVertical = false;
	private boolean showBackground = false;

	public SpeedHudModule() {
		super("speed-hud", "Speed", "Shows how fast you're moving in blocks per second.", ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> sample());
	}

	private void sample() {
		var p = PvpCompat.localPlayer();
		if (!isEnabled() || p == null) {
			lastX = Double.NaN;
			return;
		}
		if (!Double.isNaN(lastX)) {
			double dx = p.getX() - lastX;
			double dy = includeVertical ? p.getY() - lastY : 0;
			double dz = p.getZ() - lastZ;
			samples[index++ % samples.length] = Math.sqrt(dx * dx + dy * dy + dz * dz) * 20;
		}
		lastX = p.getX();
		lastY = p.getY();
		lastZ = p.getZ();
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		double sum = 0;
		for (double s : samples) {
			sum += s;
		}
		String label = String.format(Locale.ROOT, "%.2f b/s", sum / samples.length);
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, width(), height(), 6, 0x90101014);
		}
		net.veloclient.velo.client.hud.HudLook.draw(context, label, x + (width() - net.veloclient.velo.client.hud.HudLook.measure(label)) / 2, y + 6, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return 64;
	}

	@Override
	public int height() {
		return 20;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Include Up / Down", () -> includeVertical, v -> includeVertical = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
