package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import org.lwjgl.glfw.GLFW;

//? if <26.1 {
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.math.Vec3d;
//?} else {
/*import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.phys.Vec3;
*///?}

/**
 * Fires one specific, named fabricated-movement scenario per keypress against whatever server the
 * client is currently connected to, purely so the server operator can confirm their own
 * AstraProtect flight checks (server-side gravity simulation, packet-burst guard, rubberband
 * strictness) actually catch each pattern — see {@code dev.astraprotect.flight} in the Asteria
 * plugin repo, which this harness exists to validate against. It does not attempt to actually gain
 * altitude undetected; each scenario is a deliberately obvious, named test case, not a usable cheat.
 *
 * <p>Only ever initialised when {@link AnticheatTestGate#isEnabled()} is true — see that class for
 * why. Every packet this sends is fabricated on top of whatever vanilla is already sending; nothing
 * here cancels or rewrites a real packet (no {@code ClientConnection} mixin, unlike
 * {@code FlyBoatVehicleMoveMixin}), so this can't accidentally desync ordinary movement when idle.
 */
public final class AnticheatTestController {

    private enum Scenario { NONE, SLOW_ASCENT, PACKET_BURST, RUBBERBAND_WEASEL, VEHICLE_SLOW_ASCENT }

    /** Blocks/tick added to the fabricated Y each tick while {@link Scenario#SLOW_ASCENT} runs -
     *  small enough that no naive "max speed per move" check would ever catch it, but a sustained,
     *  never-decaying climb no real jump/knockback arc can produce. */
    private static final double SLOW_ASCENT_RATE = 0.05;
    /** Extra fabricated packets sent back-to-back in one client tick for {@link Scenario#PACKET_BURST}. */
    private static final int BURST_PACKET_COUNT = 25;
    /** Deliberate offset applied to the very first position sent after a detected correction, for
     *  {@link Scenario#RUBBERBAND_WEASEL} - small enough to look like it could be float rounding,
     *  which is exactly the class of bypass attempt {@code FlightPacketListener#RUBBERBAND_EPSILON}
     *  exists to reject rather than silently re-baseline on. */
    private static final double WEASEL_OFFSET = 0.4;

    private static volatile Scenario active = Scenario.NONE;
    private static double fakeY;
    private static double lastSeenY = Double.NaN;
    private static boolean awaitingWeaselOpportunity;

    private AnticheatTestController() {
    }

    public static void initIfEnabled() {
        if (!AnticheatTestGate.isEnabled()) {
            return;
        }
        KeyBinding slowAscentKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.velo-client.anticheat-test-slow-ascent", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
        KeyBinding burstKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.velo-client.anticheat-test-packet-burst", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
        KeyBinding weaselKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.velo-client.anticheat-test-rubberband-weasel", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
        KeyBinding vehicleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.velo-client.anticheat-test-vehicle-ascent", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
        KeyBinding stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.velo-client.anticheat-test-stop", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

        VeloClient.LOGGER.warn("[AnticheatTest] Bind the 5 'AntiCheat Test' actions from vanilla's "
            + "Controls menu (search \"AntiCheat\") to run scenarios: Slow Ascent, Packet Burst, "
            + "Rubberband Weasel, Vehicle Slow Ascent, Stop.");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (slowAscentKey.wasPressed()) start(client, Scenario.SLOW_ASCENT);
            if (burstKey.wasPressed()) start(client, Scenario.PACKET_BURST);
            if (weaselKey.wasPressed()) start(client, Scenario.RUBBERBAND_WEASEL);
            if (vehicleKey.wasPressed()) start(client, Scenario.VEHICLE_SLOW_ASCENT);
            if (stopKey.wasPressed()) stop(client);
            tick(client);
        });
    }

    private static void start(MinecraftClient client, Scenario scenario) {
        if (client.player == null) {
            return;
        }
        active = scenario;
        fakeY = client.player.getY();
        lastSeenY = fakeY;
        awaitingWeaselOpportunity = scenario == Scenario.RUBBERBAND_WEASEL;
        VeloClient.LOGGER.warn("[AnticheatTest] scenario started: {}", scenario);
    }

    private static void stop(MinecraftClient client) {
        if (active == Scenario.NONE) {
            return;
        }
        VeloClient.LOGGER.warn("[AnticheatTest] scenario stopped: {}", active);
        active = Scenario.NONE;
    }

    private static void tick(MinecraftClient client) {
        if (active == Scenario.NONE || client.player == null) {
            return;
        }
        var networkHandler = client.getNetworkHandler();
        if (networkHandler == null) {
            return;
        }
        var player = client.player;

        switch (active) {
            case SLOW_ASCENT -> {
                fakeY += SLOW_ASCENT_RATE;
                sendPlayerPosition(networkHandler, player.getX(), fakeY, player.getZ(), false);
            }
            case PACKET_BURST -> {
                for (int i = 0; i < BURST_PACKET_COUNT; i++) {
                    boolean ascending = i % 2 == 0;
                    double y = player.getY() + (ascending ? 3.0 : 0.0);
                    sendPlayerPosition(networkHandler, player.getX(), y, player.getZ(), !ascending);
                }
            }
            case RUBBERBAND_WEASEL -> {
                // Wait for the server to actually correct us (a sudden Y jump we didn't cause -
                // the previous tick's SLOW_ASCENT-style fabricated packets aren't sent in this
                // scenario, so any jump here can only be a real server-side rubberband), then
                // immediately nudge the very next position away from exactly where we were put.
                double currentY = player.getY();
                if (!Double.isNaN(lastSeenY) && !awaitingWeaselOpportunity
                        && Math.abs(currentY - lastSeenY) > 1.0) {
                    sendPlayerPosition(networkHandler, player.getX() + WEASEL_OFFSET, currentY,
                        player.getZ(), true);
                    VeloClient.LOGGER.warn("[AnticheatTest] sent weasel offset after detected correction");
                    active = Scenario.NONE;
                } else if (awaitingWeaselOpportunity) {
                    // First tick after starting: nothing to react to yet, just prime the ascent
                    // so a correction is actually likely to happen soon.
                    fakeY += SLOW_ASCENT_RATE * 4;
                    sendPlayerPosition(networkHandler, player.getX(), fakeY, player.getZ(), false);
                    awaitingWeaselOpportunity = false;
                }
                lastSeenY = currentY;
            }
            case VEHICLE_SLOW_ASCENT -> {
                var vehicle = player.getVehicle();
                if (vehicle == null) {
                    VeloClient.LOGGER.warn("[AnticheatTest] VEHICLE_SLOW_ASCENT needs you to be riding something - stopping");
                    active = Scenario.NONE;
                    return;
                }
                fakeY += SLOW_ASCENT_RATE;
                float yaw;
                float pitch;
                //? if <26.1 {
                yaw = vehicle.getYaw();
                pitch = vehicle.getPitch();
                //?} else {
                /*yaw = vehicle.getYRot();
                pitch = vehicle.getXRot();
                *///?}
                sendVehiclePosition(networkHandler, vehicle.getX(), fakeY, vehicle.getZ(), yaw, pitch, false);
            }
            case NONE -> {
            }
        }
    }

    private static void sendPlayerPosition(Object networkHandler, double x, double y, double z, boolean onGround) {
        //? if <26.1 {
        var packet = new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, onGround, false);
        ((net.minecraft.client.network.ClientPlayNetworkHandler) networkHandler).sendPacket(packet);
        //?} else {
        /*var packet = new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, false);
        ((net.minecraft.client.multiplayer.ClientPacketListener) networkHandler).send(packet);
        *///?}
    }

    private static void sendVehiclePosition(Object networkHandler, double x, double y, double z,
                                             float yaw, float pitch, boolean onGround) {
        //? if <26.1 {
        var packet = new VehicleMoveC2SPacket(new Vec3d(x, y, z), yaw, pitch, onGround);
        ((net.minecraft.client.network.ClientPlayNetworkHandler) networkHandler).sendPacket(packet);
        //?} else {
        /*var packet = new ServerboundMoveVehiclePacket(new Vec3(x, y, z), yaw, pitch, onGround);
        ((net.minecraft.client.multiplayer.ClientPacketListener) networkHandler).send(packet);
        *///?}
    }
}
