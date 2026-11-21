package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Mini Player: a small live 3D you on the HUD with your skin, armour and Velo cosmetics, like the paper doll in
 * Bedrock Edition. It copies your pose (sneaking, swimming, gliding, swinging) because it is drawn from your own player.
 * "When Moving" shows it only while you move or turn and fades it out after a moment, the way Bedrock does.
 *
 * <p>Two 3D player previews in one frame run 26.2's Vulkan device out of memory, so on 26.2+ it only draws over screens
 * that show no 3D player of their own (pause menu, chat, chests...) and steps aside in the others.
 */
public final class MiniPlayerModule extends AbstractModule implements HudModule, Configurable {

	private static final List<String> SHOW = List.of("Always", "When Moving");

	private final HudPosition position = new HudPosition(0.015f, 0.14f);
	private String show = "Always";
	private double turn = 25;
	/** How much the head follows you looking up / down, 0..100 %. */
	private double headTilt = 100;
	private double size = 1.0;
	private boolean showBackground = false;

	private double lastX = Double.NaN;
	private double lastY;
	private double lastZ;
	private float lastYaw;
	private long lastActive;

	public MiniPlayerModule() {
		super("mini-player", "Mini Player", "A small live 3D you on the HUD with your skin, armour and cosmetics - like Bedrock's paper doll.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		int w = width();
		int h = height();
		var screen = net.veloclient.velo.client.util.ClientCompat.currentScreen();
		if (net.veloclient.velo.client.hud.HudSample.active()) {
			placeholder(context, x, y, w, h); // the HUD editor's sample
			return;
		}
		if (client.player == null) {
			return;
		}
		if (screen != null && !dollAllowedOver(screen)) {
			// a screen that draws its own 3D player (inventory, Velo windows): a second one runs 26.2's Vulkan out of
			// memory - the Mini Player just steps out of the way there (it used to show a stand-in sketch, which looked broken)
			return;
		}
		float alpha = 1f;
		if (show.equals("When Moving")) {
			long now = System.currentTimeMillis();
			double px = client.player.getX();
			double py = client.player.getY();
			double pz = client.player.getZ();
			float yaw = client.player.getYaw();
			if (Double.isNaN(lastX) || Math.abs(px - lastX) + Math.abs(py - lastY) + Math.abs(pz - lastZ) > 0.01 || Math.abs(yaw - lastYaw) > 0.5f) {
				lastActive = now;
			}
			lastX = px;
			lastY = py;
			lastZ = pz;
			lastYaw = yaw;
			long idle = now - lastActive;
			if (idle > 1600) {
				return;
			}
			alpha = idle < 1200 ? 1f : 1f - (idle - 1200) / 400f;
			if (alpha < 0.35f) {
				return;   // the entity itself can't fade; it disappears at the end of the background fade
			}
		}
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, w, h, 6, net.veloclient.velo.client.gui.widget.VeloUi.withAlpha(0x90101014, Math.round(0x90 * alpha)));
		}
		int box = (int) Math.round(30 * size);
		EntityPreviewWidget.drawDoll(context, x, y, x + w, y + h, box, (float) -turn, (float) (headTilt / 100.0));
	}

	/** Screens over which the real 3D Mini Player can draw: ones that never show a 3D player themselves (on 26.2+). */
	private static boolean dollAllowedOver(Object screen) {
		//? if <26.2 {
		return true;
		//?} else {
		/*return screen instanceof net.minecraft.client.gui.screens.PauseScreen || screen instanceof net.minecraft.client.gui.screens.ChatScreen
				|| screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
				&& !(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)
				&& !(screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen);
		*///?}
	}

	private void placeholder(DrawContext context, int x, int y, int w, int h) {
		VeloDraw.fillRounded(context, x, y, w, h, 6, 0x60101014);
		int cx = x + w / 2;
		int unit = Math.max(2, w / 12);
		context.fill(cx - unit * 2, y + h / 8, cx + unit * 2, y + h / 8 + unit * 4, 0xC0D8B48A);
		context.fill(cx - unit * 2, y + h / 8 + unit * 4, cx + unit * 2, y + h / 8 + unit * 10, 0xC04A7BD0);
		context.fill(cx - unit * 4, y + h / 8 + unit * 4, cx - unit * 2, y + h / 8 + unit * 10, 0xC0D8B48A);
		context.fill(cx + unit * 2, y + h / 8 + unit * 4, cx + unit * 4, y + h / 8 + unit * 10, 0xC0D8B48A);
		context.fill(cx - unit * 2, y + h / 8 + unit * 10, cx + unit * 2, y + h / 8 + unit * 16, 0xC0303A6A);
		HudLook.draw(context, "Mini Player", x + (w - HudLook.measure("Mini Player")) / 2, y + h - 11, 0xFFEDE6E6);
	}

	@Override
	public int width() {
		return (int) Math.round(56 * size);
	}

	@Override
	public int height() {
		return (int) Math.round(84 * size);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Show", SHOW, () -> show, v -> show = v),
				new ConfigField.SliderField("Turn", -180, 180, () -> turn, v -> turn = Math.round(v), v -> Math.round(v) + "°"),
				new ConfigField.SliderField("Head Tilt", 0, 100, () -> headTilt, v -> headTilt = Math.round(v), v -> Math.round(v) + "%"),
				new ConfigField.SliderField("Size", 0.6, 2.0, () -> size, v -> size = Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%"),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
