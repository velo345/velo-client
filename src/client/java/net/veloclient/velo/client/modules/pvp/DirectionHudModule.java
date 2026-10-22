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

/** A compass strip across the top of the screen: N / NE / E... sliding as you turn, with your exact heading. */
public final class DirectionHudModule extends AbstractModule implements HudModule, Configurable {

	private static final String[] MARKS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
	private static final int WIDTH = 160;

	private final HudPosition position = new HudPosition(0.5f, 0.01f);
	private boolean showDegrees = true;
	private boolean showBackground = false;
	private int accent = 0xFFFF5C5C;

	public DirectionHudModule() {
		super("direction-hud", "Direction", "A compass strip showing which way you're facing.", ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		var player = PvpCompat.localPlayer();
		if (player == null) {
			return;
		}
		// Minecraft yaw: 0 = south, 90 = west. Spread 180 degrees across the strip.
		float yaw = net.veloclient.velo.client.util.WorldInfo.rotation()[0];
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, WIDTH, height(), 6, 0x90101014);
		}
		float pixelsPerDegree = WIDTH / 180f;
		int center = x + WIDTH / 2;
		for (int i = 0; i < MARKS.length; i++) {
			float markYaw = i * 45f;
			float diff = wrap(markYaw - yaw);
			if (Math.abs(diff) > 85) {
				continue;
			}
			int mx = Math.round(center + diff * pixelsPerDegree);
			String mark = MARKS[i];
			int color = mark.equals("N") ? accent : mark.length() == 1 ? 0xFFFFFFFF : 0xFFB8B8C8;
			net.veloclient.velo.client.hud.HudLook.draw(context, mark, mx - net.veloclient.velo.client.hud.HudLook.measure(mark) / 2, y + 4, color);
		}
		for (int tick = 0; tick < 360; tick += 15) {
			float diff = wrap(tick - yaw);
			if (Math.abs(diff) > 88 || tick % 45 == 0) {
				continue;
			}
			int tx = Math.round(center + diff * pixelsPerDegree);
			context.fill(tx, y + 6, tx + 1, y + 10, 0x80FFFFFF);
		}
		context.fill(center, y + 13, center + 1, y + 16, accent);
		if (showDegrees) {
			int heading = Math.round((yaw + 180f) % 360f);
			String text = heading + "°";
			net.veloclient.velo.client.hud.HudLook.draw(context, text, center - net.veloclient.velo.client.hud.HudLook.measure(text) / 2, y + 17, 0xFFFFFFFF);
		}
	}

	private static float wrap(float degrees) {
		float d = degrees % 360f;
		if (d >= 180f) {
			d -= 360f;
		}
		if (d < -180f) {
			d += 360f;
		}
		return d;
	}

	@Override
	public int width() {
		return WIDTH;
	}

	@Override
	public int height() {
		return showDegrees ? 28 : 17;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show Degrees", () -> showDegrees, v -> showDegrees = v),
				new ConfigField.ColorField("North / Marker Color", () -> accent, v -> accent = v, false),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
