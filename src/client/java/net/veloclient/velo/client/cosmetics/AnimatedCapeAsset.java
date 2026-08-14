package net.veloclient.velo.client.cosmetics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers one stable {@link Identifier} per animated cape whose bound
 * texture's pixel content is swapped in place, frame by frame, rather than
 * registering (or looking up) a different identifier per frame.
 *
 * <p>That's what makes animated capes "just work" everywhere a cape texture
 * identifier is already read - {@link CapeManager#textureIdentifier}, {@link
 * net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer}, the cape
 * library tiles, and (via {@link net.veloclient.velo.client.mixin.LocalPlayerSkinCapeMixin})
 * Wavey Capes' own fallback renderer - none of them need to know or care that
 * the texture behind that identifier is animated at all; they just keep
 * sampling the same identifier every frame like they always did, and the GPU
 * content underneath happens to be changing.
 *
 * <p>Frame swaps never call {@link NativeImageBackedTexture#setImage}, which
 * closes whatever image was previously assigned to it - the decoded frames
 * are a persistent, reusable master list (read from repeatedly, forever), not
 * something that can be handed away and closed. Instead each tick copies the
 * next frame's pixels into the texture's own backing image via {@link
 * NativeImage#copyFrom}, leaving the master frames untouched.
 */
public final class AnimatedCapeAsset {

	private static final Map<String, AnimatedCapeAsset> REGISTRY = new LinkedHashMap<>();

	private final Identifier identifier;
	private final NativeImageBackedTexture texture;
	private final List<GifDecoder.Frame> frames;
	private final int width;
	private final int height;
	private int frameIndex = -1;
	private long msIntoCurrentFrame;
	private long lastAdvanceNanos = -1;

	private AnimatedCapeAsset(Identifier identifier, NativeImageBackedTexture texture, List<GifDecoder.Frame> frames, int width, int height) {
		this.identifier = identifier;
		this.texture = texture;
		this.frames = frames;
		this.width = width;
		this.height = height;
	}

	/** Decodes and registers {@code gifPath} (a resource path, e.g. from a store cape or an imported bundle's {@code frames.gif}) under a texture id derived from {@code capeId}, or returns the already-registered one. */
	public static synchronized AnimatedCapeAsset getOrRegister(String capeId, java.util.function.Supplier<InputStream> gifSource) {
		return REGISTRY.computeIfAbsent(capeId, id -> {
			try (InputStream in = gifSource.get()) {
				GifDecoder.Result decoded = GifDecoder.decode(in);
				if (decoded.frames().isEmpty()) {
					throw new IOException("GIF has no frames");
				}
				Identifier textureId = Identifier.of("velo-client", "cape_anim_" + id.replace('-', '_'));
				NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "animated cape " + id,
						decoded.width(), decoded.height(), false);
				MinecraftClient.getInstance().getTextureManager().registerTexture(textureId, texture);
				AnimatedCapeAsset animated = new AnimatedCapeAsset(textureId, texture, decoded.frames(), decoded.width(), decoded.height());
				animated.applyFrame(0);
				return animated;
			} catch (IOException e) {
				throw new RuntimeException("Failed to decode animated cape texture for " + id, e);
			}
		});
	}

	public Identifier identifier() {
		return identifier;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** Advances every registered animated cape's frame by real elapsed time. Cheap enough to call unconditionally every client tick - a handful of small textures at most. */
	public static synchronized void tickAll() {
		long now = System.nanoTime();
		for (AnimatedCapeAsset texture : REGISTRY.values()) {
			texture.advance(now);
		}
	}

	private void advance(long nowNanos) {
		if (lastAdvanceNanos < 0) {
			lastAdvanceNanos = nowNanos;
			return;
		}
		long elapsedMs = (nowNanos - lastAdvanceNanos) / 1_000_000L;
		lastAdvanceNanos = nowNanos;
		if (elapsedMs <= 0) {
			return;
		}
		msIntoCurrentFrame += elapsedMs;
		int currentDelay = frames.get(Math.max(frameIndex, 0)).delayMillis();
		int guard = 0;
		while (msIntoCurrentFrame >= currentDelay && guard++ < frames.size()) {
			msIntoCurrentFrame -= currentDelay;
			applyFrame((frameIndex + 1) % frames.size());
			currentDelay = frames.get(frameIndex).delayMillis();
		}
	}

	private void applyFrame(int index) {
		frameIndex = index;
		texture.getImage().copyFrom(frames.get(index).image());
		texture.upload();
	}
}
