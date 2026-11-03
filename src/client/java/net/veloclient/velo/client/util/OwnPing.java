package net.veloclient.velo.client.util;

/**
 * Your real ping to the server you're on, measured the way the F3 ping chart does it: every two seconds a vanilla
 * ping request (play phase), the server echoes it straight back. The tab list's numbers are whatever the server
 * sends per player - many networks hide them (everyone at 0-1 ms), and even honest ones update them only every few
 * seconds - so your own row and the Ping HUD use this instead.
 */
public final class OwnPing {

	private static final long INTERVAL_MS = 2000;
	private static long lastSent;
	private static double smoothed = -1;
	private static long lastResult;

	private OwnPing() {
	}

	public static void register() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	/** The measured round trip in ms, or -1 when there's none (singleplayer, no answer yet, or stale). */
	public static int ms() {
		if (smoothed < 0 || System.currentTimeMillis() - lastResult > 10_000) {
			return -1;
		}
		return (int) Math.round(smoothed);
	}

	private static void tick() {
		long now = System.currentTimeMillis();
		if (now - lastSent < INTERVAL_MS || ClientCompat.isSingleplayer()) {
			return;
		}
		lastSent = now;
		try {
			//? if <26.1 {
			var handler = net.minecraft.client.MinecraftClient.getInstance().getNetworkHandler();
			if (handler != null) {
				handler.sendPacket(new net.minecraft.network.packet.c2s.query.QueryPingC2SPacket(net.minecraft.util.Util.getMeasuringTimeMs()));
			}
			//?} else {
			/*var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
			if (connection != null) {
				connection.send(new net.minecraft.network.protocol.ping.ServerboundPingRequestPacket(net.minecraft.util.Util.getMillis()));
			}
			*///?}
		} catch (RuntimeException | LinkageError ignored) {
			// not connected right now
		}
	}

	/** The server's answer (from OwnPingMixin): {@code sentAt} is the timestamp we sent. */
	public static void answered(long sentAt) {
		//? if <26.1 {
		long now = net.minecraft.util.Util.getMeasuringTimeMs();
		//?} else {
		/*long now = net.minecraft.util.Util.getMillis();
		*///?}
		long rtt = now - sentAt;
		if (rtt < 0 || rtt > 30_000) {
			return;
		}
		// A stale value is from before (another server, a reconnect): start over instead of blending it in.
		boolean stale = System.currentTimeMillis() - lastResult > 10_000;
		smoothed = smoothed < 0 || stale ? rtt : smoothed * 0.6 + rtt * 0.4;
		lastResult = System.currentTimeMillis();
	}

	/** Forget the last server's ping (on disconnect / joining another). */
	public static void reset() {
		smoothed = -1;
	}
}
