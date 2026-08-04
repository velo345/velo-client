package net.veloclient.velo.client.modules.utility;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
//? if <26.1 {
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
//?} else {
/*import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
*///?}
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Automatically reconnects to whatever server the client was just kicked from
 * or lost connection to, waiting progressively longer between attempts (3s,
 * 5s, 10s, 30s, then every 60s after that) - the same behaviour as the
 * well-known standalone "AutoReconnect" mod. Only ever fires off the
 * vanilla {@code DisconnectedScreen} (which vanilla itself only shows for an
 * unexpected connection loss/kick, never for a manual "Disconnect" from the
 * pause menu) and only when a real multiplayer server entry is known, so
 * this can't accidentally reconnect out of singleplayer. A "Cancel Reconnect"
 * button is added directly to that screen (via Fabric API's {@link Screens}
 * helper - no need to touch vanilla's screen classes) so the attempt can be
 * called off at any time; the countdown resets back to 3s once a connection
 * actually succeeds or the attempt is cancelled.
 */
public final class AutoReconnectModule extends AbstractModule {

	private static final int[] DELAY_SCHEDULE_SECONDS = {3, 5, 10, 30, 60};

	private boolean pending;
	private int attempt;
	private int remainingTicks;
	private Screen fallbackParent;
	private ClickableWidget activeButton;

	//? if <26.1 {
	private ServerInfo targetServer;
	//?} else {
	/*private ServerData targetServer;
	*///?}

	public AutoReconnectModule() {
		super("auto-reconnect", "Auto Reconnect",
				"Automatically reconnects after an unexpected disconnect, waiting progressively longer between "
						+ "attempts (3s, 5s, 10s, 30s, then every 60s). Adds a Cancel button to the disconnect screen.",
				ModuleCategory.UTILITY, SafetyTag.ALWAYS_SAFE, false);
		ScreenEvents.AFTER_INIT.register(this::onScreenInit);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
	}

	@Override
	public void onDisable() {
		pending = false;
	}

	private void reset() {
		pending = false;
		attempt = 0;
		targetServer = null;
		fallbackParent = null;
		activeButton = null;
	}

	/**
	 * Fires for every screen, not just the first disconnect of a chain - a
	 * failed reconnect attempt shows a brand new {@code DisconnectedScreen}
	 * too. Two things matter here:
	 * <ul>
	 * <li>{@code client.getCurrentServerEntry()}/{@code getCurrentServer()}
	 * is derived from the live network handler (verified via javap - it's
	 * {@code Nullables.map(getNetworkHandler(), ...)}, not a stored field),
	 * so it's only reliably populated on the *first* disconnect after a real
	 * connection; by the second attempt's own failure it can already be
	 * {@code null} again. {@link #targetServer} is captured once and reused
	 * for the whole retry chain instead of re-deriving it from that API on
	 * every attempt, which is what previously made this stop retrying after
	 * exactly one round-trip.</li>
	 * <li>{@link #fallbackParent} is likewise captured only once (the very
	 * first screen of the chain, whose own "back to server list" already
	 * points at the real server list) - previously every retry re-captured
	 * it as *that* attempt's own {@code DisconnectedScreen}, so
	 * {@code ConnectScreen}'s next failure created a screen whose parent was
	 * the previous failure's screen instead of the real server list,
	 * chaining one link deeper each attempt and needing one "back" press per
	 * failed attempt to unwind.</li>
	 * </ul>
	 */
	private void onScreenInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
		if (!isEnabled() || !(screen instanceof DisconnectedScreen)) {
			return;
		}
		if (targetServer == null && !captureServer(client, screen)) {
			return;
		}
		startCountdown(screen);
	}

	//? if <26.1 {
	private boolean captureServer(MinecraftClient client, Screen firstScreen) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server == null || server.address == null || server.address.isEmpty()) {
			return false;
		}
		this.targetServer = server;
		this.fallbackParent = firstScreen;
		return true;
	}
	//?} else {
	/*private boolean captureServer(MinecraftClient client, Screen firstScreen) {
		ServerData server = client.getCurrentServer();
		if (server == null || server.ip == null || server.ip.isEmpty()) {
			return false;
		}
		this.targetServer = server;
		this.fallbackParent = firstScreen;
		return true;
	}
	*///?}

	private void startCountdown(Screen currentScreen) {
		this.attempt++;
		this.remainingTicks = delayTicksForAttempt(attempt);
		this.pending = true;
		installButton(currentScreen);
	}

	private void installButton(Screen screen) {
		activeButton = createCancelButton(screen);
		//? if <26.1 {
		Screens.getButtons(screen).add(activeButton);
		//?} else {
		/*Screens.getWidgets(screen).add(activeButton);
		*///?}
		updateButtonLabel();
	}

	//? if <26.1 {
	private ButtonWidget createCancelButton(Screen screen) {
		return ButtonWidget.builder(Text.literal("Cancel Reconnect"), b -> onCancelPressed())
				.dimensions(screen.width / 2 - 110, screen.height - 24, 220, 20)
				.build();
	}
	//?} else {
	/*private Button createCancelButton(Screen screen) {
		return Button.builder(Text.literal("Cancel Reconnect"), b -> onCancelPressed())
				.bounds(screen.width / 2 - 110, screen.height - 24, 220, 20)
				.build();
	}
	*///?}

	private void onCancelPressed() {
		pending = false;
		attempt = 0;
		targetServer = null;
		fallbackParent = null;
		if (activeButton != null) {
			activeButton.setMessage(Text.literal("Reconnect Cancelled"));
		}
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || !pending) {
			return;
		}
		updateButtonLabel();
		remainingTicks--;
		if (remainingTicks <= 0) {
			pending = false;
			reconnectNow(client);
		}
	}

	private void updateButtonLabel() {
		if (activeButton == null) {
			return;
		}
		int secondsLeft = Math.max(0, (remainingTicks + 19) / 20);
		activeButton.setMessage(Text.literal("Reconnecting in " + secondsLeft + "s - Click to Cancel"));
	}

	//? if <26.1 {
	private void reconnectNow(MinecraftClient client) {
		if (targetServer == null) {
			return;
		}
		ServerAddress address = ServerAddress.parse(targetServer.address);
		ConnectScreen.connect(fallbackParent, client, address, targetServer, false, null);
	}
	//?} else {
	/*private void reconnectNow(MinecraftClient client) {
		if (targetServer == null) {
			return;
		}
		ServerAddress address = ServerAddress.parseString(targetServer.ip);
		ConnectScreen.startConnecting(fallbackParent, client, address, targetServer, false, null);
	}
	*///?}

	private static int delayTicksForAttempt(int attemptNumber) {
		int index = Math.min(attemptNumber - 1, DELAY_SCHEDULE_SECONDS.length - 1);
		return DELAY_SCHEDULE_SECONDS[index] * 20;
	}
}
