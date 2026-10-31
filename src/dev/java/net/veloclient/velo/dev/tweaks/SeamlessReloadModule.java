package net.veloclient.velo.dev.tweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Seamless Resource Reload (dev build): no red Mojang screen when resources reload (resource packs, F3+T, Velo
 * Shaders). The game keeps drawing the world and HUD behind that screen anyway - it only covers them - so the overlay
 * is replaced by a small progress bar. Packs can't be loaded texture by texture (atlases are stitched in one go), so
 * the reload takes as long as before and the last moment, when everything is swapped in, is still a short hitch. The
 * very first load at startup keeps the normal screen.
 */
public final class SeamlessReloadModule extends AbstractModule implements Configurable {

	private static final List<String> POSITIONS = List.of("Top", "Bottom", "Hidden");

	private static SeamlessReloadModule instance;

	private String position = "Top";

	public SeamlessReloadModule() {
		super("seamless-reload", "Seamless Resource Reload", "Reloading resource packs no longer shows the red Mojang screen - you keep playing while it loads.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
	}

	public static boolean active() {
		SeamlessReloadModule m = instance;
		return m != null && m.isEnabled();
	}

	/** The small "Reloading resources" pill drawn instead of the overlay. */
	public static void drawProgress(DrawContext context, float progress) {
		SeamlessReloadModule m = instance;
		if (m == null || m.position.equals("Hidden")) {
			return;
		}
		var renderer = MinecraftClient.getInstance().textRenderer;
		String label = "Reloading resources " + Math.round(progress * 100) + "%";
		int w = Math.max(120, renderer.getWidth(label) + 16);
		int x = (context.getScaledWindowWidth() - w) / 2;
		int y = m.position.equals("Top") ? 6 : context.getScaledWindowHeight() - 30;
		VeloDraw.fillRounded(context, x, y, w, 22, 6, 0xC0101016);
		context.drawTextWithShadow(renderer, label, x + (w - renderer.getWidth(label)) / 2, y + 4, 0xFFE8E8F0);
		VeloDraw.fillRounded(context, x + 8, y + 15, w - 16, 2, 1, 0x40FFFFFF);
		int filled = Math.round((w - 16) * Math.max(0f, Math.min(1f, progress)));
		if (filled > 0) {
			VeloDraw.fillRounded(context, x + 8, y + 15, filled, 2, 1, 0xFFFFFFFF);
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.ChoiceField("Progress Bar", POSITIONS, () -> position, v -> position = v));
	}
}
