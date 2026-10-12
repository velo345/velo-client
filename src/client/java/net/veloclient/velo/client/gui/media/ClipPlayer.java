package net.veloclient.velo.client.gui.media;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.clips.MjpegAvi;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plays a saved clip inside a GUI: frames are decoded one ahead on a worker thread and swapped into
 * a single texture, so playback costs one texture upload per shown frame. Supports pause, seek and
 * looping. {@link #close()} frees the texture.
 */
public final class ClipPlayer {

	private record Decoded(int index, NativeImage image) {
	}

	private final Path file;
	private volatile MjpegAvi.Clip clip;
	private volatile String error;
	private final AtomicReference<Decoded> ready = new AtomicReference<>();
	private volatile boolean decoding;
	private NativeImageBackedTexture texture;
	private Identifier id;
	private int texWidth;
	private int texHeight;
	private int shown = -1;
	private boolean playing = true;
	private double position;
	private long lastNanos;
	private boolean closed;

	public ClipPlayer(Path file) {
		this.file = file;
		MediaTextures.decoder().execute(() -> {
			try {
				clip = MjpegAvi.read(file);
				if (clip.frames().isEmpty()) {
					error = "This clip has no frames";
				}
			} catch (Exception e) {
				error = "Couldn't open the clip: " + e.getMessage();
			}
		});
	}

	public Path file() {
		return file;
	}

	public String error() {
		return error;
	}

	public boolean loaded() {
		return clip != null && !clip.frames().isEmpty();
	}

	public boolean playing() {
		return playing;
	}

	public void togglePlay() {
		if (!playing && clip != null && position >= clip.frames().size() - 1) {
			position = 0;
		}
		playing = !playing;
	}

	/** 0..1 */
	public double progress() {
		return clip == null || clip.frames().size() < 2 ? 0 : position / (clip.frames().size() - 1);
	}

	public void seek(double fraction) {
		if (clip != null) {
			position = Math.max(0, Math.min(1, fraction)) * (clip.frames().size() - 1);
		}
	}

	/** Steps a frame (paused frame-by-frame review). */
	public void step(int frames) {
		if (clip != null) {
			playing = false;
			position = Math.max(0, Math.min(clip.frames().size() - 1, Math.round(position) + frames));
		}
	}

	public double seconds() {
		return clip == null ? 0 : clip.seconds();
	}

	public double currentSeconds() {
		return clip == null ? 0 : position / Math.max(1, clip.fps());
	}

	/** Advances playback and swaps in decoded frames; returns the texture to draw (null until the first frame). */
	public MediaTextures.Tex frame() {
		if (closed) {
			return null;
		}
		long now = System.nanoTime();
		MjpegAvi.Clip current = clip;
		if (current != null && !current.frames().isEmpty()) {
			if (playing && lastNanos != 0) {
				position += (now - lastNanos) / 1e9 * current.fps();
				if (position >= current.frames().size() - 1) {
					position = current.frames().size() - 1;
					playing = false;
				}
			}
			Decoded decoded = ready.getAndSet(null);
			if (decoded != null) {
				upload(decoded);
			}
			int wanted = (int) Math.round(position);
			if (wanted != shown && !decoding && ready.get() == null) {
				decoding = true;
				byte[] jpeg = current.frames().get(wanted);
				MediaTextures.decoder().execute(() -> {
					try {
						NativeImage image = MediaTextures.decodeJpeg(jpeg, 1920);
						Decoded previous = ready.getAndSet(new Decoded(wanted, image));
						if (previous != null) {
							previous.image().close();
						}
					} catch (Exception e) {
						error = "Frame " + wanted + " is damaged";
					} finally {
						decoding = false;
					}
				});
			}
		}
		lastNanos = now;
		return id == null ? null : new MediaTextures.Tex(id, texWidth, texHeight);
	}

	private void upload(Decoded decoded) {
		NativeImage image = decoded.image();
		if (texture == null || image.getWidth() != texWidth || image.getHeight() != texHeight) {
			if (id != null) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			}
			MediaTextures.Tex tex = MediaTextures.register(image, "velo clip");
			id = tex.id();
			texWidth = tex.width();
			texHeight = tex.height();
			texture = (NativeImageBackedTexture) MinecraftClient.getInstance().getTextureManager().getTexture(id);
		} else {
			texture.setImage(image);
			texture.upload();
		}
		shown = decoded.index();
	}

	public void release() {
		closed = true;
		Decoded pending = ready.getAndSet(null);
		if (pending != null) {
			pending.image().close();
		}
		if (id != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			id = null;
			texture = null;
		}
	}
}
