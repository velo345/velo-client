package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundInstanceListener;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.sound.SoundCategory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Tracks recently-started sounds and where they came from, for the Sound
 * Radar overlay. Vanilla has no API to enumerate "currently playing" sounds
 * with position, so this instead listens for sounds as they start (there's
 * no "stopped" callback either) and keeps each one as a fading blip for a
 * short window - the same approach a radar/ping display needs regardless,
 * since most game sounds are short one-shots anyway (footsteps, hits,
 * ambience blips) rather than long steady tones.
 *
 * <p>Each blip stores the sound's absolute world bearing, not an angle
 * already relative to the player - the radar recomputes the relative angle
 * against the player's *current* yaw every frame it draws, so blips rotate
 * around the circle like a compass as the player turns instead of staying
 * fixed at whatever angle happened to be current the instant the sound
 * started.
 */
final class SoundRadarTracker implements SoundInstanceListener {

	record Blip(double worldBearingDegrees, String soundId, String shortName, SoundCategory category, long startedAtMs) {
	}

	private static final long LIFETIME_MS = 3000;
	private static final Deque<Blip> BLIPS = new ArrayDeque<>();
	private static boolean registered;

	private SoundRadarTracker() {
	}

	static void ensureRegistered() {
		if (registered) {
			return;
		}
		registered = true;
		MinecraftClient.getInstance().getSoundManager().registerListener(new SoundRadarTracker());
	}

	@Override
	public void onSoundPlayed(SoundInstance sound, WeightedSoundSet soundSet, float f) {
		var player = MinecraftClient.getInstance().player;
		if (player == null || sound.isRelative()) {
			return;
		}
		double dx = sound.getX() - player.getX();
		double dz = sound.getZ() - player.getZ();
		if (dx * dx + dz * dz < 0.01) {
			return;
		}
		double worldBearing = Math.toDegrees(Math.atan2(-dx, dz));

		String id = sound.getId().toString();
		String path = sound.getId().getPath();
		int lastDot = path.lastIndexOf('.');
		String shortName = lastDot >= 0 ? path.substring(lastDot + 1) : path;

		synchronized (BLIPS) {
			BLIPS.addLast(new Blip(worldBearing, id, shortName, sound.getCategory(), System.currentTimeMillis()));
			while (BLIPS.size() > 48) {
				BLIPS.pollFirst();
			}
		}
	}

	static List<Blip> activeBlips() {
		long now = System.currentTimeMillis();
		synchronized (BLIPS) {
			BLIPS.removeIf(b -> now - b.startedAtMs() > LIFETIME_MS);
			return List.copyOf(BLIPS);
		}
	}
}
