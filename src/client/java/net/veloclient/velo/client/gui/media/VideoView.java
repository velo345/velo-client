package net.veloclient.velo.client.gui.media;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.clips.FfmpegTool;
import net.veloclient.velo.client.clips.core.VideoPlayer;

/**
 * A {@link VideoPlayer} drawn in the game: one texture, refilled straight from the decoded RGBA bytes (a memory copy,
 * no per-pixel work) whenever the player has a new picture. Opening probes the clip on a worker thread, so screens show
 * "Loading" for a moment; FFmpeg is fetched first if this PC has none yet.
 */
public final class VideoView {

	private volatile VideoPlayer player;
	private volatile String error;
	private volatile String status = "Loading...";
	private NativeImage image;
	private NativeImageBackedTexture texture;
	private Identifier id;
	private boolean released;
	private final boolean autoplay;

	/** {@code source}: a file path or an https link. */
	public VideoView(String source, boolean autoplay) {
		this.autoplay = autoplay;
		FfmpegTool.ensure(problem -> {
			if (problem != null) {
				error = problem;
				return;
			}
			MediaTextures.decoder().execute(() -> {
				try {
					VideoPlayer opened = VideoPlayer.open(FfmpegTool.binary(), source, 1280, VideoPlayer.Pixels.RGBA);
					if (released) {
						opened.release();
						return;
					}
					player = opened;
					if (autoplay) {
						opened.play();
					}
				} catch (Exception e) {
					error = e.getMessage();
				}
			});
		});
		if (FfmpegTool.binary() == null) {
			status = "Getting FFmpeg for the player (once)...";
		}
	}

	/** Null until the clip is open. */
	public VideoPlayer player() {
		return player;
	}

	public String error() {
		VideoPlayer p = player;
		return error != null ? error : p == null ? null : p.error();
	}

	public String status() {
		return status;
	}

	/** This frame's picture (call once per frame), or null before the first one. */
	public MediaTextures.Tex texture() {
		VideoPlayer p = player;
		if (p == null || released) {
			return id == null ? null : new MediaTextures.Tex(id, p == null ? 1 : p.width(), p == null ? 1 : p.height());
		}
		VideoPlayer.Frame frame = p.frame();
		if (frame != null) {
			if (image == null) {
				image = new NativeImage(frame.width, frame.height, false);
				MediaTextures.Tex tex = MediaTextures.register(image, "velo video");
				id = tex.id();
				texture = (NativeImageBackedTexture) MinecraftClient.getInstance().getTextureManager().getTexture(id);
			}
			//? if <26.1 {
			long pointer = image.imageId();
			//?} else {
			/*long pointer = image.getPointer();
			*///?}
			org.lwjgl.system.MemoryUtil.memByteBuffer(pointer, frame.data.length).put(0, frame.data, 0, frame.data.length);
			texture.upload();
			p.recycle(frame);
		}
		return id == null ? null : new MediaTextures.Tex(id, p.width(), p.height());
	}

	public void release() {
		released = true;
		VideoPlayer p = player;
		if (p != null) {
			p.release();
		}
		if (id != null) {
			// Frees the texture and its image.
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			id = null;
			texture = null;
			image = null;
		}
	}
}
