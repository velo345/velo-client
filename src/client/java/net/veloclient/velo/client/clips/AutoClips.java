package net.veloclient.velo.client.clips;

import net.veloclient.velo.client.pvp.FightTracker;
import net.veloclient.velo.client.social.NotificationOverlay;

import java.util.Locale;

/**
 * Clips Pro's automatic clips: a won fight (a kill) and a win screen (the server's "VICTORY" / "You won" / "#1" title)
 * save the last few seconds on their own - a moment after it happened, so the clip shows the finish too. Kills close
 * together become one clip.
 */
public final class AutoClips {

	private static final String[] WIN_WORDS = {"victory", "you won", "you win", "winner", "#1", "1st place", "champion", "game won"};
	private static final long DELAY_MILLIS = 2500;
	private static final long COOLDOWN_MILLIS = 12_000;

	public static volatile boolean kills = true;
	public static volatile boolean wins = true;
	public static volatile int length = 30;

	private static String pendingTrigger;
	private static FightTracker.Recap pendingRecap;
	private static long pendingAt;
	private static long lastSaved;

	private AutoClips() {
	}

	public static void register() {
		FightTracker.addListener(recap -> {
			if (kills && ProClipRecorder.active() && recap.outcome() == FightTracker.Outcome.WON) {
				queue("kill", recap);
			}
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	/** A title appeared on screen (from the server). */
	public static void onTitle(String title) {
		if (!wins || !ProClipRecorder.active() || title == null) {
			return;
		}
		String plain = title.replaceAll("§.", "").toLowerCase(Locale.ROOT).trim();
		for (String word : WIN_WORDS) {
			if (plain.contains(word)) {
				queue("win", null);
				return;
			}
		}
	}

	private static synchronized void queue(String trigger, FightTracker.Recap recap) {
		// A win outranks the kill that won it; a later kill just moves the save back.
		if (pendingTrigger == null || "win".equals(trigger)) {
			pendingTrigger = trigger;
			pendingRecap = recap;
		}
		pendingAt = System.currentTimeMillis() + DELAY_MILLIS;
	}

	private static synchronized void tick() {
		if (pendingTrigger == null || System.currentTimeMillis() < pendingAt) {
			return;
		}
		String trigger = pendingTrigger;
		FightTracker.Recap recap = pendingRecap;
		pendingTrigger = null;
		pendingRecap = null;
		if (System.currentTimeMillis() - lastSaved < COOLDOWN_MILLIS) {
			return;
		}
		lastSaved = System.currentTimeMillis();
		ClipRecorder.save(trigger, recap, length, file -> {
			if (file != null) {
				NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "win".equals(trigger) ? "Win clipped" : "Kill clipped",
						length + " seconds - R-Shift > Clips to share it", 3500, () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(
								new net.veloclient.velo.client.gui.media.MediaScreen(null, file)), null, null));
			}
		});
	}
}
