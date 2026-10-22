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

/**
 * Blocks left until the build height limit (Bed Wars / SkyWars bridging and towering) and until the
 * void. The limit is the world's own top by default; minigame maps usually build lower, so set
 * yours (e.g. Hypixel Bed Wars maps) and the number turns amber, then red as you get close.
 */
public final class HeightLimitModule extends AbstractModule implements HudModule, Configurable {

	private final HudPosition position = new HudPosition(0.5f, 0.60f);
	private double customLimit = 0;
	private double customVoid = 0;
	private boolean showVoid = true;
	private boolean onlyWhenClose = false;
	private double closeBlocks = 10;

	public HeightLimitModule() {
		super("height-limit", "Height Limit", "Blocks left until the build height limit and the void - for Bed Wars and SkyWars.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		var player = PvpCompat.localPlayer();
		int[] world = PvpCompat.worldHeight();
		if (player == null || world == null) {
			return;
		}
		int feet = (int) Math.floor(player.getY());
		int limit = customLimit > 0 ? (int) customLimit : world[1];
		int voidY = customVoid != 0 ? (int) customVoid : world[0];
		int up = limit - feet;
		int down = feet - voidY;
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		if (onlyWhenClose && up > closeBlocks && !editing) {
			return;
		}
		net.veloclient.velo.client.hud.HudLook.panel(context, x, y, width(), height());
		int upColor = up <= 3 ? 0xFFFF5555 : up <= 8 ? 0xFFFFC53D : 0xFFFFFFFF;
		net.veloclient.velo.client.hud.HudLook.draw(context, "Limit", x + 6, y + 6, 0xFF9A9AA6);
		String upText = up <= 0 ? "MAX" : "↑ " + up;
		net.veloclient.velo.client.hud.HudLook.draw(context, upText, x + width() - 6 - net.veloclient.velo.client.hud.HudLook.measure(upText), y + 6, upColor);
		if (showVoid) {
			int downColor = down <= 5 ? 0xFFFF5555 : 0xFFB8B8C8;
			net.veloclient.velo.client.hud.HudLook.draw(context, "Void", x + 6, y + 18, 0xFF9A9AA6);
			String downText = "↓ " + down;
			net.veloclient.velo.client.hud.HudLook.draw(context, downText, x + width() - 6 - net.veloclient.velo.client.hud.HudLook.measure(downText), y + 18, downColor);
		}
	}

	@Override
	public int width() {
		return 70;
	}

	@Override
	public int height() {
		return showVoid ? 30 : 19;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Build Limit Y (0 = world top)", 0, 320, () -> customLimit, v -> customLimit = Math.round(v),
						v -> Math.round(v) == 0 ? "World top" : "Y " + Math.round(v)),
				new ConfigField.ToggleField("Show Distance To Void", () -> showVoid, v -> showVoid = v),
				new ConfigField.SliderField("Void Y (0 = world bottom)", -64, 64, () -> customVoid, v -> customVoid = Math.round(v),
						v -> Math.round(v) == 0 ? "World bottom" : "Y " + Math.round(v)),
				new ConfigField.ToggleField("Only When Close To The Limit", () -> onlyWhenClose, v -> onlyWhenClose = v),
				new ConfigField.SliderField("Close Means", 3, 40, () -> closeBlocks, v -> closeBlocks = Math.round(v),
						v -> Math.round(v) + " blocks"));
	}
}
