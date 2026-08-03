package net.veloclient.velo.client.modules.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Local-only session stats: deaths and time played this session, detected by
 * watching for the vanilla death screen opening - never reported anywhere,
 * purely a personal log (design spec section 6.2).
 */
public final class SessionStatsModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.02f, 0.31f);
	private int deaths;
	private long sessionStartMillis;
	private boolean wasDeathScreenOpen;

	public SessionStatsModule() {
		super("session-stats", "Session Stats", "Tracks deaths and time played this session, locally only.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	@Override
	public void onEnable() {
		deaths = 0;
		sessionStartMillis = System.currentTimeMillis();
		wasDeathScreenOpen = false;
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			return;
		}
		//? if <26.1 {
		boolean deathScreenOpen = client.currentScreen instanceof DeathScreen;
		//?} else if <26.2 {
		/*boolean deathScreenOpen = client.screen instanceof DeathScreen;
		*///?} else {
		/*boolean deathScreenOpen = client.gui.screen() instanceof DeathScreen;
		*///?}
		if (deathScreenOpen && !wasDeathScreenOpen) {
			deaths++;
		}
		wasDeathScreenOpen = deathScreenOpen;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		long elapsedSeconds = (System.currentTimeMillis() - sessionStartMillis) / 1000;
		String time = String.format("%02d:%02d:%02d", elapsedSeconds / 3600, (elapsedSeconds / 60) % 60, elapsedSeconds % 60);
		int lineHeight = client.textRenderer.fontHeight + 1;
		context.drawTextWithShadow(client.textRenderer, "Session: " + time, x, y, 0xFFFFFFFF);
		context.drawTextWithShadow(client.textRenderer, "Deaths: " + deaths, x, y + lineHeight, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return 150;
	}

	@Override
	public int height() {
		return 2 * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}
}
