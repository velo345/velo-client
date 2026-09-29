package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.Locale;

import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.util.VehicleLagSimulator;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

public final class FlyBoatModule extends AbstractModule implements Configurable {

	public static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.fly-boat-toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
	public static final KeyBinding DESCEND_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.fly-boat-descend", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private static final double MIN_SPEED = 4.0;
	private static final double MAX_SPEED = 120.0;
	private static final double MIN_FALL_RATE = 0.0;
	private static final double MAX_FALL_RATE = 0.2;
	private static final double MIN_BURST_INTERVAL = 5;
	private static final double MAX_BURST_INTERVAL = 200;

	private static volatile double flySpeed = 24.0;
	private static volatile double fallRate = 0.04;
	private static volatile double burstInterval = 20;
	private static volatile boolean flying;

	private Entity activeBoat;

	public FlyBoatModule() {
		super("fly-boat", "Fly Boat",
				"While riding a boat, cancels gravity and flies it through the air.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> onTick(client));
	}

	@Override
	public void onDisable() {
		releaseBoat();
	}

	private void onTick(MinecraftClient client) {
		if (TOGGLE_KEY.wasPressed()) {
			setEnabled(!isEnabled());
		}
		Entity boat = (isEnabled() && client.player != null && isBoat(client.player.getVehicle()))
				? client.player.getVehicle() : null;
		if (activeBoat != null && activeBoat != boat) {
			releaseBoat();
		}
		if (boat == null) {
			flying = false;
			VehicleLagSimulator.setActive(false);
			return;
		}
		activeBoat = boat;
		flying = true;
		VehicleLagSimulator.setActive(true);
		VehicleLagSimulator.setFallRate(fallRate);
		VehicleLagSimulator.setBurstInterval((int) Math.round(burstInterval));
		applyFlight(client, boat);
	}

	private void releaseBoat() {
		if (activeBoat != null) {
			activeBoat.setNoGravity(false);
			releaseServerBoat(MinecraftClient.getInstance(), activeBoat.getUuid());
			activeBoat = null;
		}
		flying = false;
		VehicleLagSimulator.setActive(false);
	}

	private static void applyFlight(MinecraftClient client, Entity boat) {
		boat.setNoGravity(true);
		var options = client.options;
		double forwardInput = (options.forwardKey.isPressed() ? 1 : 0) - (options.backKey.isPressed() ? 1 : 0);
		double verticalInput = (options.jumpKey.isPressed() ? 1 : 0) - (DESCEND_KEY.isPressed() ? 1 : 0);
		
		float boatYaw;
		//? if <26.1 {
		boatYaw = boat.getYaw();
		//?} else {
		/*boatYaw = boat.getYRot();
		*///?}
		
		double yawRad = Math.toRadians(boatYaw);
		double speedPerTick = flySpeed / 20.0;
		double vx = -Math.sin(yawRad) * forwardInput * speedPerTick;
		double vz = Math.cos(yawRad) * forwardInput * speedPerTick;
		double vy = verticalInput * speedPerTick;
		
		// Inline-Weiche statt Wrapper-Methode, um Fehlerquellen bei der Generierung auszuschließen!
		//? if <26.1 {
		boat.setVelocity(vx, vy, vz);
		//?} else {
		/*boat.setDeltaMovement(vx, vy, vz);
		*///?}

		syncServerBoat(client, boat.getUuid(), vx, vy, vz);
	}

	private static void syncServerBoat(MinecraftClient client, java.util.UUID boatId, double vx, double vy, double vz) {
		var server = client.getServer();
		if (server == null) return;
		
		//? if <26.1 {
		var dimensionKey = client.world.getRegistryKey();
		server.execute(() -> {
			var serverWorld = server.getWorld(dimensionKey);
			if (serverWorld == null) return;
			Entity serverBoat = serverWorld.getEntity(boatId);
			if (serverBoat != null) serverBoat.setVelocity(vx, vy, vz);
		});
		//?} else {
		/*var dimensionKey = client.level.dimension();
		server.execute(() -> {
			var serverWorld = server.getLevel(dimensionKey);
			if (serverWorld == null) return;
			Entity serverBoat = serverWorld.getEntity(boatId);
			if (serverBoat != null) serverBoat.setDeltaMovement(vx, vy, vz);
		});
		*///?}
	}

	private static void releaseServerBoat(MinecraftClient client, java.util.UUID boatId) {
		var server = client.getServer();
		if (server == null) return;
		
		//? if <26.1 {
		if (client.world == null) return;
		var dimensionKey = client.world.getRegistryKey();
		server.execute(() -> {
			var serverWorld = server.getWorld(dimensionKey);
			if (serverWorld != null) {
				Entity serverBoat = serverWorld.getEntity(boatId);
				if (serverBoat != null) serverBoat.setNoGravity(false);
			}
		});
		//?} else {
		/*if (client.level == null) return;
		var dimensionKey = client.level.dimension();
		server.execute(() -> {
			var serverWorld = server.getLevel(dimensionKey);
			if (serverWorld != null) {
				Entity serverBoat = serverWorld.getEntity(boatId);
				if (serverBoat != null) serverBoat.setNoGravity(false);
			}
		});
		*///?}
	}

	private static boolean isBoat(Entity entity) {
		//? if <26.1 {
		return entity instanceof net.minecraft.entity.vehicle.AbstractBoatEntity;
		//?} else {
		/*return entity instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat;
		*///?}
	}	
	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Toggle On/Off", TOGGLE_KEY),
				KeybindConfig.field("Descend", DESCEND_KEY),
				new ConfigField.SliderField("Flight Speed (blocks/s)", MIN_SPEED, MAX_SPEED,
						() -> flySpeed, v -> flySpeed = v, v -> String.format(Locale.ROOT, "%.1f", v)),
				new ConfigField.SliderField("Simulated Fall Gravity (blocks/tick²)", MIN_FALL_RATE, MAX_FALL_RATE,
						() -> fallRate, v -> fallRate = v, v -> String.format(Locale.ROOT, "%.3f", v)),
				new ConfigField.SliderField("Burst Interval (ticks)", MIN_BURST_INTERVAL, MAX_BURST_INTERVAL,
						() -> burstInterval, v -> burstInterval = v, v -> String.format(Locale.ROOT, "%.0f", v)));
	}

	public static boolean isFlying() {
		return flying;
	}

	public static void adjustSpeedOnScroll(double vertical) {
		double factor = vertical > 0 ? 1.1 : 1 / 1.1;
		flySpeed = Math.clamp(flySpeed * factor, MIN_SPEED, MAX_SPEED);
	}
}