package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.gui.FriendsScreen;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.client.social.SocialSettings;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Friends &amp; messaging in game: the Friends key (default O) opens the friends menu - or, while a
 * popup is showing, whatever that popup is about - and this reports where you're playing to your
 * friends (unless you chose "appear offline", which the server enforces). Needs Velo Network.
 */
public final class FriendsModule extends AbstractModule implements Configurable {

	public static final KeyBinding OPEN_FRIENDS = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.open_friends", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, VeloKeybinds.CATEGORY));

	private int tickCounter;

	public FriendsModule() {
		super("friends", "Friends",
				"Friends list, messages and popups. Press O to open it; see where friends are playing.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(MinecraftClient client) {
		if (++tickCounter % 20 == 0) {
			reportPresence(client);
			NotificationOverlay.setHintKey(OPEN_FRIENDS.isUnbound() ? null : OPEN_FRIENDS.getBoundKeyLocalizedText().getString());
		}
		while (OPEN_FRIENDS.wasPressed()) {
			if (!isEnabled()) {
				continue;
			}
			if (ClientCompat.currentScreen() == null) {
				NotificationOverlay.Toast newest = NotificationOverlay.newest();
				if (newest != null) {
					NotificationOverlay.open(newest);
				} else {
					FriendsScreen.open();
				}
			}
		}
	}

	private static void reportPresence(MinecraftClient client) {
		if (client.world == null || client.player == null) {
			SocialClient.reportPresence("menu", null);
		} else if (ClientCompat.isSingleplayer()) {
			SocialClient.reportPresence("singleplayer", ClientCompat.singleplayerWorldName());
		} else if (ClientCompat.isRealm()) {
			SocialClient.reportPresence("realm", ClientCompat.currentServerName());
		} else {
			SocialClient.reportPresence("server", ClientCompat.currentServerAddress());
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ActionButtonField("Open Friends...", FriendsScreen::open),
				KeybindConfig.field("Open Friends Key", OPEN_FRIENDS),
				new ConfigField.ToggleField("Do Not Disturb (no popups)", SocialSettings::doNotDisturb, SocialSettings::setDoNotDisturb));
	}
}
