package net.veloclient.velo.client.lyrics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;

import java.util.List;

/**
 * Lyrics beat effects: on the music's beat (BeatTracker listens to what the PC plays) the picture can kick - a chromatic
 * split, a flash, a shake, a glitch, a zoom - each one picked in the Studio, on every beat or only on the big moments
 * (hard hits and drops). The letters can also bounce a little on every beat. A screen pass after the world and hand,
 * before the HUD (same spot as Motion Blur); skipped entirely between hits, so it costs nothing then. Without a loopback
 * (macOS) every new lyric line counts as the hit.
 */
public final class LyricsPulse {

	//? if >=26.3 {
	/*private static final Identifier EFFECT = Identifier.of("velo-client", "lyrics_pulse_spirv");
	*///?} else {
	private static final Identifier EFFECT = Identifier.of("velo-client", "lyrics_pulse");
	//?}
	private static final Identifier PARAMS_ID = Identifier.of("velo-client", "textures/effect/lyrics_pulse_params.png");
	//? if <26.1 {
	//? if <1.21.2 {
	/*private static final Object POOL = null;
	*///?} else {
	private static final net.minecraft.client.util.memory.ObjectPool POOL = new net.minecraft.client.util.memory.ObjectPool(3);
	//?}
	//?} else {
	/*private static final com.mojang.blaze3d.resource.CrossFrameResourcePool POOL = new com.mojang.blaze3d.resource.CrossFrameResourcePool(3);
	*///?}

	private static PostEffectProcessor processor;
	private static NativeImageBackedTexture texture;
	private static volatile boolean broken;
	private static float envelope;
	private static long lastNow = Long.MIN_VALUE;
	private static long lastNanos;
	private static final boolean HOLD = Boolean.getBoolean("velo.lyricsPulseHold");

	private LyricsPulse() {
	}

	private static int seenBeats = -1;
	private static float bounce;
	private static float shakeX;
	private static float shakeY;
	private static int seed;
	private static final java.util.Random RANDOM = new java.util.Random();

	/** How much the letters grow right now (1 = not at all): a little bounce on every beat. Render thread. */
	public static float bounceScale() {
		return 1f + bounce * 0.07f;
	}

	/** Once per frame after the world and hand, before the HUD (PostEffectsFrameMixin / HudManager). Render thread. */
	public static void applyIfActive() {
		LyricsStyle st = LyricsModule.style;
		if (!st.beatSync || !LyricsModule.visibleNow()) {
			envelope = 0;
			bounce = 0;
			lastNow = Long.MIN_VALUE;
			return;
		}
		long nanos = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (nanos - lastNanos) / 1e9f);
		lastNanos = nanos;
		boolean everyBeat = "Every beat".equals(st.beatTrigger);
		BeatTracker.request();
		if (!BeatTracker.unavailable()) {
			int beats = BeatTracker.beats();
			if (seenBeats >= 0 && beats != seenBeats) {
				float strength = Math.max(0.6f, Math.min(1.6f, BeatTracker.strength()));
				bounce = Math.max(bounce, Math.min(1f, strength * 0.8f));
				if (everyBeat || BeatTracker.big()) {
					hit(BeatTracker.big() ? 1f : 0.55f);
				}
			}
			seenBeats = beats;
		} else {
			// no loopback here (macOS): fall back to the lyric lines - a hit on every new line
			boolean demo = LyricsModule.studioOpen;
			List<LyricsFetcher.Line> lines = LyricsSync.lines(demo);
			long now = LyricsSync.now(demo, st.offsetMs);
			if (lines != null && lineStarted(lines, lastNow, now)) {
				bounce = 1f;
				hit(0.8f);
			}
			lastNow = now;
		}
		envelope *= (float) Math.exp(-dt / 0.16);
		bounce *= (float) Math.exp(-dt / 0.12);
		if (HOLD) {
			envelope = 1f; // dev (-Dvelo.lyricsPulseHold): a pulse frozen at its peak, for screenshots
		}
		if (!st.beatBounce) {
			bounce = 0;
		}
		if (envelope < 0.01f || broken) {
			return; // nothing pulses: skip the pass
		}
		float e = envelope * (float) st.beatStrength;
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			// the zoom heads for the lyrics: a little above the middle for sky lyrics, lower for the 2D style
			float cy = st.world3d() ? (st.sky() ? 0.62f : 0.5f) : ("Top".equals(st.screenSpot) ? 0.85f : "Center".equals(st.screenSpot) ? 0.6f : 0.2f);
			float wobble = (float) Math.sin(nanos / 1e9 * 55);   // the shake rattles while it fades
			upload(client, st.fxZoom ? Math.min(1f, e * 0.5f) : 0f, st.fxSplit ? Math.min(1f, e * 0.6f) : 0f, st.fxFlash ? Math.min(1f, e * 0.4f) : 0f,
					st.fxGlitch ? Math.min(1f, e * 0.8f) : 0f, 0.5f, cy,
					st.fxShake ? 0.5f + shakeX * e * wobble * 0.5f : 0.5f, st.fxShake ? 0.5f + shakeY * e * wobble * 0.5f : 0.5f, seed);
			//? if >=1.21.2 {
			// Fetched every frame (vanilla caches it): a resource reload - e.g. a server resource pack - closes the old one.
			if (net.veloclient.velo.client.util.ClientCompat.reloadingResources()) {
				return; // never mid-reload (see ClientCompat.reloadingResources)
			}
			processor = load(client, EFFECT);
			//?}
			if (processor == null) {
				return;
			}
			//? if <26.1 {
			//? if >=1.21.2 {
			processor.render(client.getFramebuffer(), POOL);
			POOL.decrementLifespan();
			//?}
			//?} else if <26.2 {
			/*processor.process(client.getMainRenderTarget(), POOL);
			POOL.endFrame();
			*///?} else {
			/*processor.process(client.gameRenderer.mainRenderTarget(), POOL);
			POOL.endFrame();
			*///?}
		} catch (Throwable t) {
			broken = true;
			VeloClient.LOGGER.error("Lyrics beat effects failed to render - turning them off for this session", t);
		}
	}

	private static void hit(float power) {
		envelope = Math.max(envelope, power);
		double a = RANDOM.nextDouble() * Math.PI * 2;
		shakeX = (float) Math.cos(a);
		shakeY = (float) Math.sin(a);
		seed = RANDOM.nextInt(256);
	}

	/** A lyric line (not an instrumental gap) started between the two song times. A jump (seeking) counts as nothing. */
	private static boolean lineStarted(List<LyricsFetcher.Line> lines, long from, long to) {
		if (from == Long.MIN_VALUE || to <= from || to - from > 1500) {
			return false;
		}
		for (LyricsFetcher.Line line : lines) {
			if (line.startMs() > from && line.startMs() <= to && line.text() != null && !line.text().isBlank()) {
				return true;
			}
		}
		return false;
	}

	//? if >=1.21.2 {
	private static PostEffectProcessor load(MinecraftClient client, Identifier id) {
		return client.getShaderLoader().loadPostEffect(id, DefaultFramebufferSet.MAIN_ONLY);
	}
	//?}

	/** Pixel 0 = (zoom, split, flash, glitch), 1 = (centre x, centre y, shake x, shake y) all 0..1, 2 = (seed / 255). */
	private static void upload(MinecraftClient client, float zoom, float split, float flash, float glitch, float cx, float cy, float sx, float sy, int seed) {
		if (texture == null) {
			texture = new NativeImageBackedTexture(() -> "velo-lyrics-pulse", new NativeImage(4, 1, false));
			client.getTextureManager().registerTexture(PARAMS_ID, texture);
		}
		NativeImage image = texture.getImage();
		image.setColorArgb(0, 0, b(glitch) << 24 | b(zoom) << 16 | b(split) << 8 | b(flash));
		image.setColorArgb(1, 0, b(sy) << 24 | b(cx) << 16 | b(cy) << 8 | b(sx));
		image.setColorArgb(2, 0, 0xFF << 24 | (seed & 0xFF) << 16);
		texture.upload();
	}

	private static int b(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255)));
	}
}
