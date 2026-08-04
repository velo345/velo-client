package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Shows the real-world wall clock time (purely local, never sent anywhere). */
public final class ClockModule extends AbstractModule implements HudModule {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	// Bottom-right, not top-right - Scoreboard (also on by default) owns the
	// top-right corner and can grow tall, so anything else parked there
	// would sit right under/behind it on a fresh profile.
	private final HudPosition position = new HudPosition(0.98f, 0.90f);

	public ClockModule() {
		super("clock", "Clock", "Displays the current real-world time.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		String text = LocalTime.now().format(FORMAT);
		context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return MinecraftClient.getInstance().textRenderer.getWidth("00:00:00");
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
