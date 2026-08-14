package net.veloclient.velo.client.modules.utility;

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
import net.minecraft.client.session.Session;
//?} else {
/*import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.User;
*///?}
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.auth.VeloAccountAuth;
import net.veloclient.velo.client.mixin.SessionAccessorMixin;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.Locale;
import java.util.UUID;

/**
 * Adds a "Fix Session & Reconnect" button directly to the vanilla disconnect
 * screen whenever the kick reason is specifically an expired/invalid session
 * (vanilla's own "Invalid session (Try restarting your game and the
 * launcher)" message - see {@code disconnect.loginFailedInfo.invalidSession}
 * in {@code ClientLoginNetworkHandler#joinServerSession}). Refreshes the
 * saved Velo account's Microsoft/Xbox/Minecraft token chain in the
 * background via {@link VeloAccountAuth} (the same refresh-token flow the
 * launcher itself uses), swaps it into the running client via {@link
 * SessionAccessorMixin}, and reconnects - all without actually restarting
 * either the game or the launcher, unlike vanilla's own advice.
 *
 * <p>Only ever installs itself on a real {@code DisconnectedScreen} for that
 * one specific reason, mirroring {@link AutoReconnectModule}'s own
 * "capture the target server once, from the first screen of the chain"
 * approach so a failed fix attempt (which produces its own fresh
 * {@code DisconnectedScreen}) doesn't lose track of where to reconnect to.
 */
public final class SessionAutoFixerModule extends AbstractModule {

	private Screen fallbackParent;
	private ClickableWidget activeButton;
	private boolean fixing;

	//? if <26.1 {
	private ServerInfo targetServer;
	//?} else {
	/*private ServerData targetServer;
	*///?}

	public SessionAutoFixerModule() {
		super("session-auto-fixer", "Session Auto-Fixer",
				"Adds a one-click fix to the disconnect screen when kicked for an invalid/expired session, "
						+ "refreshing your saved Velo account and reconnecting without restarting the launcher.",
				ModuleCategory.UTILITY, SafetyTag.ALWAYS_SAFE, true);
		ScreenEvents.AFTER_INIT.register(this::onScreenInit);
	}

	private void onScreenInit(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
		if (!isEnabled() || !(screen instanceof DisconnectedScreen disconnectedScreen)) {
			return;
		}
		if (!isInvalidSessionReason(disconnectedScreen) || !captureServer(client, screen)) {
			return;
		}
		fixing = false;
		installButton(screen);
	}

	/**
	 * The kick reason ("Invalid session (Try restarting your game and the
	 * launcher)") is NOT what {@code getNarratedTitle()}/{@code
	 * getNarrationMessage()} return - both just echo the screen's generic
	 * heading ("Disconnected"), confirmed by decompiling {@code Screen}
	 * itself (its narrated-title method is a one-line call straight through
	 * to {@code getTitle()}, i.e. {@code this.title}, nothing else). The
	 * real reason text lives on a separate field {@code DisconnectedScreen}
	 * itself declares - a raw {@code Text}/{@code Component} on some builds,
	 * or a record wrapping one behind a {@code reason()} accessor on others
	 * (confirmed against the real Yarn 1.21.11 mappings: field {@code info},
	 * type {@code DisconnectionInfo}, with a real generated {@code reason()}
	 * accessor) - and that exact field name isn't available for the
	 * Mojang-mapped 26.1/26.2 builds this project also targets. Rather than
	 * guess it and risk a hard Mixin accessor failure on launch for those
	 * versions, this reflectively scans every field {@code DisconnectedScreen}
	 * itself declares (title/button-label text included - harmless, they
	 * just never contain "invalid session") for anything - directly, or one
	 * level behind a {@code reason()}-shaped wrapper - whose {@code
	 * getString()} contains it. Older code here checked the wrong text
	 * entirely, so the button never appeared for a real invalid-session kick
	 * no matter what.
	 */
	private static boolean isInvalidSessionReason(DisconnectedScreen screen) {
		for (var field : screen.getClass().getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			try {
				field.setAccessible(true);
				if (velo$mentionsInvalidSession(field.get(screen))) {
					return true;
				}
			} catch (ReflectiveOperationException ignored) {
				// Keep scanning - a field we can't read is just not a candidate.
			}
		}
		return false;
	}

	private static boolean velo$mentionsInvalidSession(Object value) {
		if (value == null) {
			return false;
		}
		String direct = velo$tryGetString(value);
		if (direct != null && direct.toLowerCase(Locale.ROOT).contains("invalid session")) {
			return true;
		}
		try {
			Object reason = value.getClass().getMethod("reason").invoke(value);
			String reasonText = velo$tryGetString(reason);
			return reasonText != null && reasonText.toLowerCase(Locale.ROOT).contains("invalid session");
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static String velo$tryGetString(Object value) {
		if (value == null) {
			return null;
		}
		try {
			Object result = value.getClass().getMethod("getString").invoke(value);
			return result instanceof String s ? s : null;
		} catch (ReflectiveOperationException e) {
			return null;
		}
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

	private void installButton(Screen screen) {
		activeButton = createButton(screen);
		//? if <26.1 {
		Screens.getButtons(screen).add(activeButton);
		//?} else {
		/*Screens.getWidgets(screen).add(activeButton);
		*///?}
	}

	//? if <26.1 {
	private ButtonWidget createButton(Screen screen) {
		return ButtonWidget.builder(Text.literal("Fix Session & Reconnect"), b -> onFixPressed())
				.dimensions(screen.width / 2 - 110, screen.height - 48, 220, 20)
				.build();
	}
	//?} else {
	/*private Button createButton(Screen screen) {
		return Button.builder(Text.literal("Fix Session & Reconnect"), b -> onFixPressed())
				.bounds(screen.width / 2 - 110, screen.height - 48, 220, 20)
				.build();
	}
	*///?}

	private void onFixPressed() {
		if (fixing) {
			return;
		}
		fixing = true;
		setButtonMessage("Fixing session...");
		Thread thread = new Thread(this::refreshAndReconnect, "velo-session-fix");
		thread.setDaemon(true);
		thread.start();
	}

	private void refreshAndReconnect() {
		MinecraftClient client = MinecraftClient.getInstance();
		try {
			VeloAccountAuth.RefreshedSession refreshed = VeloAccountAuth.refreshActiveAccount();
			client.execute(() -> applyAndReconnect(client, refreshed));
		} catch (VeloAccountAuth.AuthRefreshException e) {
			client.execute(() -> {
				fixing = false;
				setButtonMessage(e.getMessage());
			});
		} catch (Exception e) {
			client.execute(() -> {
				fixing = false;
				setButtonMessage("Session fix failed - restart the launcher.");
			});
		}
	}

	//? if <26.1 {
	private void applyAndReconnect(MinecraftClient client, VeloAccountAuth.RefreshedSession refreshed) {
		Session current = client.getSession();
		Session newSession = new Session(refreshed.username(), UUID.fromString(refreshed.uuid()), refreshed.accessToken(),
				current.getXuid(), current.getClientId());
		((SessionAccessorMixin) client).velo$setSession(newSession);
		reconnect(client);
	}

	private void reconnect(MinecraftClient client) {
		if (targetServer == null) {
			return;
		}
		ServerAddress address = ServerAddress.parse(targetServer.address);
		ConnectScreen.connect(fallbackParent, client, address, targetServer, false, null);
	}
	//?} else {
	/*private void applyAndReconnect(MinecraftClient client, VeloAccountAuth.RefreshedSession refreshed) {
		User current = client.getUser();
		User newUser = new User(refreshed.username(), UUID.fromString(refreshed.uuid()), refreshed.accessToken(),
				current.getXuid(), current.getClientId());
		((SessionAccessorMixin) client).velo$setSession(newUser);
		reconnect(client);
	}

	private void reconnect(MinecraftClient client) {
		if (targetServer == null) {
			return;
		}
		ServerAddress address = ServerAddress.parseString(targetServer.ip);
		ConnectScreen.startConnecting(fallbackParent, client, address, targetServer, false, null);
	}
	*///?}

	private void setButtonMessage(String message) {
		if (activeButton != null) {
			activeButton.setMessage(Text.literal(message));
		}
	}
}
