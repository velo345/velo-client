package net.veloclient.velo.client.modules.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks left/right click rate by watching the vanilla attack/use keybinds'
 * pressed state each tick - the same inputs vanilla already reacts to, just
 * counted. No input is synthesized or accelerated (design spec section 2).
 */
final class CpsTracker {

	private static final long WINDOW_MILLIS = 1000;
	private static final Deque<Long> LEFT_CLICKS = new ArrayDeque<>();
	private static final Deque<Long> RIGHT_CLICKS = new ArrayDeque<>();
	private static boolean leftWasDown;
	private static boolean rightWasDown;
	private static boolean registered;

	private CpsTracker() {
	}

	static void ensureRegistered() {
		if (registered) {
			return;
		}
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(CpsTracker::onTick);
	}

	private static void onTick(MinecraftClient client) {
		long now = System.currentTimeMillis();
		boolean leftDown = client.options.attackKey.isPressed();
		if (leftDown && !leftWasDown) {
			LEFT_CLICKS.addLast(now);
		}
		leftWasDown = leftDown;

		boolean rightDown = client.options.useKey.isPressed();
		if (rightDown && !rightWasDown) {
			RIGHT_CLICKS.addLast(now);
		}
		rightWasDown = rightDown;

		prune(LEFT_CLICKS, now);
		prune(RIGHT_CLICKS, now);
	}

	private static void prune(Deque<Long> clicks, long now) {
		while (!clicks.isEmpty() && now - clicks.peekFirst() > WINDOW_MILLIS) {
			clicks.pollFirst();
		}
	}

	static int leftCps() {
		return LEFT_CLICKS.size();
	}

	static int rightCps() {
		return RIGHT_CLICKS.size();
	}
}
