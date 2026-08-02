package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.integrated.IntegratedServer;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.Locale;

/**
 * Server performance readout (design spec section 6.3). On singleplayer/LAN
 * (an {@link IntegratedServer} is present) this shows the real tick time,
 * exactly like vanilla's own F3 server line. On a remote dedicated server
 * MSPT/TPS isn't sent to the client at all unless a server-side plugin
 * exposes it (e.g. via a scoreboard placeholder or plugin channel) - honest
 * about that limit, this module falls back to round-trip packet rate and
 * connection latency, both real client-observable numbers, rather than
 * guessing at a TPS figure the client was never told.
 */
public final class TpsTickGraphModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.5f, 0.02f);

	public TpsTickGraphModule() {
		super("tps-tick-graph", "TPS & Tick Graph",
				"Shows server tick time (singleplayer/LAN) or connection packet rate as a proxy on remote servers.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (handler == null) {
			return;
		}
		IntegratedServer integrated = client.getServer();
		ClientConnection connection = handler.getConnection();
		String line;
		if (integrated != null) {
			line = String.format(Locale.ROOT, "Tick: %.1f ms | %.0f tx %.0f rx",
					integrated.getAverageTickTime(), connection.getAveragePacketsSent(), connection.getAveragePacketsReceived());
		} else {
			line = String.format(Locale.ROOT, "%.0f tx %.0f rx pkt/s",
					connection.getAveragePacketsSent(), connection.getAveragePacketsReceived());
		}
		int width = client.textRenderer.getWidth(line);
		context.drawTextWithShadow(client.textRenderer, line, x - width / 2, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return 220;
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
