package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Shows the player's own position. This is information the client already
 * knows about itself (identical to the vanilla F3 position line) - not a
 * scan of the world, so it's safe on every server (design spec section 6.2).
 */
public final class CoordinatesModule extends AbstractModule implements HudModule {

	private static final String SAMPLE = "XYZ: -0000 / -000 / -0000";
	private final HudPosition position = new HudPosition(0.01f, 0.09f);

	public CoordinatesModule() {
		super("coordinates", "Coordinates", "Displays your current X/Y/Z position.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		if (HudLook.on()) {
			// X Y Z in their own colours, each followed by its number.
			HudLook.panel(context, x, y, width(), height());
			int tx = x + HudLook.padX();
			int ty = y + HudLook.padY();
			double[] values = {client.player.getX(), client.player.getY(), client.player.getZ()};
			String[] axes = {"X", "Y", "Z"};
			int[] colors = {0xFFFF6B73, 0xFF6FE39A, 0xFF6FB6FF};
			for (int i = 0; i < 3; i++) {
				var axis = HudLook.value(axes[i]);
				HudLook.text(context, axis, tx, ty, colors[i]);
				tx += HudLook.width(axis) + 3;
				var number = HudLook.label(String.format(java.util.Locale.ROOT, "%.1f", values[i]));
				HudLook.text(context, number, tx, ty, 0xFFFFFFFF);
				tx += HudLook.width(number) + 8;
			}
			return;
		}
		String text = String.format("XYZ: %.1f / %.1f / %.1f",
				client.player.getX(), client.player.getY(), client.player.getZ());
		net.veloclient.velo.client.hud.HudLook.draw(context, text, x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		if (HudLook.on()) {
			// Sized to the numbers on screen (a fixed "-30000.0" per axis left a wide empty tail).
			var player = MinecraftClient.getInstance().player;
			double[] values = player == null ? new double[] {-1234.5, 64.0, 1234.5} : new double[] {player.getX(), player.getY(), player.getZ()};
			int w = 0;
			String[] axes = {"X", "Y", "Z"};
			for (int i = 0; i < 3; i++) {
				w += HudLook.width(HudLook.value(axes[i])) + 3 + HudLook.width(HudLook.label(String.format(java.util.Locale.ROOT, "%.1f", values[i]))) + 8;
			}
			return w - 8 + HudLook.padX() * 2;
		}
		return net.veloclient.velo.client.hud.HudLook.measure(SAMPLE);
	}

	@Override
	public int height() {
		return HudLook.on() ? HudLook.statHeight() : MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
