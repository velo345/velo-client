package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.UUID;

/**
 * Phantom, for building and testing redstone in singleplayer:
 * <ul>
 *   <li><b>Ignore redstone</b> - you don't set anything off: pressure plates, tripwires and sculk
 *   sensors act as if you weren't there (the game's own "ignores block triggers" and "dampens
 *   vibrations" flags, the ones spectators use).</li>
 *   <li><b>No Clip</b> - while you fly in creative you pass through blocks; land and you collide
 *   normally again.</li>
 * </ul>
 * Both work on the singleplayer (integrated) server only - a multiplayer server decides itself
 * what you trigger and where you can move.
 */
public final class PhantomModule extends AbstractModule implements Configurable {

	public static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.phantom", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	private static volatile PhantomModule instance;
	/** The local player's id - the only entity Phantom applies to (also on the integrated server). */
	private static volatile UUID self;
	private boolean ignoreRedstone = true;
	private boolean noClip = true;

	public PhantomModule() {
		super("phantom", "Phantom", "Singleplayer: don't trigger pressure plates, tripwires or sculk, and fly through walls in creative.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE_KEY.wasPressed()) {
				setEnabled(!isEnabled());
				NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Phantom " + (isEnabled() ? "on" : "off"),
						isEnabled() ? (ClientCompat.isSingleplayer() ? "Redstone ignores you, fly through walls" : "Only works in singleplayer")
								: "Back to normal", 2000, null, null, null));
			}
			self = localPlayerId();
		});
	}

	private static UUID localPlayerId() {
		//? if <26.1 {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		return player == null ? null : player.getUuid();
		//?} else {
		/*var player = net.minecraft.client.Minecraft.getInstance().player;
		return player == null ? null : player.getUUID();
		*///?}
	}

	private static boolean active(UUID id) {
		PhantomModule module = instance;
		return module != null && module.isEnabled() && id != null && id.equals(self) && ClientCompat.isSingleplayer();
	}

	/** Pressure plates, tripwires and sculk sensors should ignore this entity (either side). */
	public static boolean ignoresRedstone(UUID id) {
		PhantomModule module = instance;
		return module != null && module.ignoreRedstone && active(id);
	}

	/** This player may fly through blocks right now (creative / spectator flight only). */
	public static boolean noClip(UUID id, boolean flying, boolean creative) {
		PhantomModule module = instance;
		return module != null && module.noClip && flying && creative && active(id);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Ignore Pressure Plates, Tripwires & Sculk", () -> ignoreRedstone, v -> ignoreRedstone = v),
				new ConfigField.ToggleField("No Clip While Flying", () -> noClip, v -> noClip = v),
				KeybindConfig.field("Toggle Key", TOGGLE_KEY));
	}
}
