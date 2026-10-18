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

/**
 * Faster (or slower) creative flight: sets your flying speed while you fly in singleplayer, in
 * creative or in spectator mode - never in survival on a server. Vanilla flies at 1x; turning the
 * module off puts it back.
 */
public final class FlySpeedModule extends AbstractModule implements Configurable {

	public static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.fly_speed", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	private static final float VANILLA = 0.05f;
	private double speed = 3;
	private boolean applied;

	public FlySpeedModule() {
		super("fly-speed", "Fly Speed", "Fly faster in creative, spectator and singleplayer (up to 10x).",
				ModuleCategory.QOL, SafetyTag.CHECK_SERVER_RULES, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE_KEY.wasPressed()) {
				setEnabled(!isEnabled());
				NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Fly Speed " + (isEnabled() ? "on" : "off"),
						isEnabled() ? String.format(java.util.Locale.ROOT, "%.1fx flying speed", speed) : "Normal flying speed", 2000, null, null, null));
			}
			tick();
		});
	}

	/** {@code /flyspeed <amount>}: sets the speed (1 = vanilla) and turns the module on; null/off turns it off. */
	public void setSpeedFromCommand(Double multiplier) {
		if (multiplier == null) {
			setEnabled(false);
			return;
		}
		speed = Math.max(0.1, Math.min(10, Math.round(multiplier * 10) / 10.0));
		setEnabled(true);
	}

	public double speed() {
		return speed;
	}

	private void tick() {
		//? if <26.1 {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		if (player == null) {
			applied = false;
			return;
		}
		boolean allowed = ClientCompat.isSingleplayer() || player.isCreative() || player.isSpectator();
		if (isEnabled() && allowed) {
			player.getAbilities().setFlySpeed((float) (VANILLA * speed));
			applied = true;
		} else if (applied) {
			player.getAbilities().setFlySpeed(VANILLA);
			applied = false;
		}
		//?} else {
		/*var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null) {
			applied = false;
			return;
		}
		boolean allowed = ClientCompat.isSingleplayer() || player.isCreative() || player.isSpectator();
		if (isEnabled() && allowed) {
			player.getAbilities().setFlyingSpeed((float) (VANILLA * speed));
			applied = true;
		} else if (applied) {
			player.getAbilities().setFlyingSpeed(VANILLA);
			applied = false;
		}
		*///?}
	}

	@Override
	public void onDisable() {
		tick();
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Flying Speed", 0.5, 10, () -> speed, v -> speed = Math.round(v * 10) / 10.0,
						v -> String.format(java.util.Locale.ROOT, "%.1fx", v)),
				KeybindConfig.field("Toggle Key", TOGGLE_KEY));
	}
}
