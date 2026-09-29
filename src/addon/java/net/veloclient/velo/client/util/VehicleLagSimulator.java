package net.veloclient.velo.client.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Backing state for the Fly Boat module's lag-spike test (buffer real vehicle-move
 * packets for a window of ticks, feed the server a decoy position instead, then burst
 * the buffered real packets back-to-back like a network catch-up).
 *
 * The decoy dead-reckons: X/Z advance every tick at whatever constant velocity the boat
 * had the instant the window opened, instead of either leaking the true live X/Z (which
 * keeps the server's per-tick reference caught up to the present, making the eventual
 * burst of up-to-a-second-old positions look like a backward teleport) or freezing
 * outright (which reports zero horizontal movement for the whole window, so a burst
 * after any real horizontal flight dumps the *entire* window's travel distance in one
 * tick - trivially over vanilla's own ~10 block/tick budget on ServerPlayNetworkHandler
 * #onVehicleMove, which is exactly why moving horizontally got an instant kick after
 * the X/Z freeze). Dead reckoning keeps the server's tracked position advancing at a
 * believable, roughly-correct rate throughout the window, so the correction needed at
 * burst time stays small instead of dumping the whole window's displacement at once.
 *
 * Y still gets the same treatment as before: frozen while grounded (nothing to
 * simulate falling through), otherwise following vanilla's actual accelerating fall
 * curve (velocity -= gravity, then velocity *= drag, each tick).
 *
 * Deliberately packet-type-agnostic (buffers/returns raw {@link Object}s) so this class
 * doesn't need a Yarn/Mojmap split - the version-specific packet construction lives in
 * {@link net.veloclient.velo.client.mixin.FlyBoatVehicleMoveMixin}, which is the only
 * place that actually sends anything. This class never touches the network itself.
 */
public final class VehicleLagSimulator {

	public record DecoyPosition(double x, double y, double z) {
	}

	private static final double DRAG = 0.98;
	private static final Deque<Object> BUFFER = new ArrayDeque<>();

	private static volatile boolean active;
	private static volatile double fallRate = 0.04;
	private static volatile int burstInterval = 20;

	private static int tickCounter;
	private static double decoyX;
	private static double decoyY;
	private static double decoyZ;
	private static double velocityX;
	private static double velocityZ;
	private static double fallVelocity;
	private static boolean firstTickOfWindow = true;

	private static double prevRealX;
	private static double prevRealZ;
	private static boolean havePrevReal;

	private VehicleLagSimulator() {
	}

	public static void setActive(boolean value) {
		active = value;
		if (!value) {
			BUFFER.clear();
			tickCounter = 0;
			fallVelocity = 0;
			firstTickOfWindow = true;
			havePrevReal = false;
		}
	}

	public static boolean isActive() {
		return active;
	}

	public static void setFallRate(double value) {
		fallRate = value;
	}

	public static void setBurstInterval(int ticks) {
		burstInterval = Math.max(1, ticks);
	}

	/**
	 * Advances the decoy position for this tick; call once per intercepted real packet.
	 * X/Z dead-reckon from the velocity observed the instant the window opened. Y stays
	 * frozen while grounded, or follows the accelerating fall curve while airborne.
	 */
	public static DecoyPosition nextDecoyPosition(double realX, double realY, double realZ, boolean grounded) {
		if (firstTickOfWindow) {
			velocityX = havePrevReal ? realX - prevRealX : 0;
			velocityZ = havePrevReal ? realZ - prevRealZ : 0;
			decoyX = realX;
			decoyY = realY;
			decoyZ = realZ;
			fallVelocity = 0;
			firstTickOfWindow = false;
		} else {
			decoyX += velocityX;
			decoyZ += velocityZ;
		}

		if (grounded) {
			decoyY = realY;
			fallVelocity = 0;
		} else {
			fallVelocity = (fallVelocity - fallRate) * DRAG;
			decoyY += fallVelocity;
		}

		prevRealX = realX;
		prevRealZ = realZ;
		havePrevReal = true;

		return new DecoyPosition(decoyX, decoyY, decoyZ);
	}

	public static void queue(Object realPacket) {
		BUFFER.addLast(realPacket);
		tickCounter++;
	}

	public static boolean isBurstTick() {
		return tickCounter >= burstInterval;
	}

	/** Drains the buffer and resets the window; caller sends the returned packets in order. */
	public static List<Object> drainForBurst() {
		List<Object> drained = new ArrayList<>(BUFFER);
		BUFFER.clear();
		tickCounter = 0;
		fallVelocity = 0;
		firstTickOfWindow = true;
		return drained;
	}
}
