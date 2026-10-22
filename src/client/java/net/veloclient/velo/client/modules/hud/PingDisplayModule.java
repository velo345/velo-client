package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Shows round-trip latency to the server, same value vanilla shows next to your name in the tab list. */
public final class PingDisplayModule extends AbstractModule implements HudModule {

	// Stacked just under Clock (also bottom-right by default) rather than
	// top-right, which Scoreboard (also on by default) owns and can grow
	// tall into.
	private final HudPosition position = new HudPosition(0.01f, 0.05f);

	public PingDisplayModule() {
		super("ping-display", "Ping Display", "Shows your connection latency to the server.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (client.player == null || handler == null) {
			return;
		}
		PlayerListEntry entry = handler.getPlayerListEntry(client.player.getUuid());
		int ping = entry != null ? entry.getLatency() : 0;
		if (HudLook.on()) {
			HudLook.stat(context, x, y, String.valueOf(ping), "ms", ping < 80 ? 0xFF6FE39A : ping < 160 ? 0xFFFFD56A : 0xFFFF7A7E);
			return;
		}
		net.veloclient.velo.client.hud.HudLook.draw(context, ping + " ms", x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return HudLook.on() ? HudLook.statWidth("000", "ms") : net.veloclient.velo.client.hud.HudLook.measure("0000 ms");
	}

	@Override
	public int height() {
		return HudLook.on() ? HudLook.statHeight() : MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
