package net.veloclient.velo.client.cosmetics;

import net.veloclient.velo.client.cosmetics.capeart.CapeArt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-game Locker's "upload your own cloak": any PNG, JPG or GIF. {@link CapeArt#detect} decides whether it already
 * is a cloak texture (then it's brought into the standard layout as it is) or an ordinary picture (then it's turned
 * into a cloak with the automatic settings - the launcher's upload dialog has all the knobs). Runs off the render
 * thread; the result is imported with {@link CapeManager#importCape} / {@link CapeManager#importAnimatedGif}.
 */
public final class CapeUpload {

	/** {@code png}: a cloak texture to import; or {@code gif}: an animated cloak. {@code message}: what happened, for the player. */
	public record Prepared(Path png, byte[] gif, String message) {
	}

	private CapeUpload() {
	}

	/** {@code animatedAllowed}: Velo+ - everyone else gets a GIF's first frame. */
	public static Prepared prepare(Path file, boolean animatedAllowed) throws IOException {
		List<CapeArt.Picture> frames = new ArrayList<>();
		List<Integer> delays = new ArrayList<>();
		boolean gif = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
		if (gif) {
			try (InputStream in = Files.newInputStream(file)) {
				GifDecoder.decodeCanvases(in, (canvas, delay) -> {
					// At most 1024 wide in memory (the detail games show animated cloaks at); a valid file goes up untouched anyway.
					frames.add(CapeArt.shrink(CapeArt.Picture.of(canvas), 1024));
					delays.add(delay);
				});
			}
		} else {
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
			if (image != null) {
				frames.add(CapeArt.Picture.of(image));
				delays.add(100);
			}
		}
		if (frames.isEmpty()) {
			throw new IOException("That file isn't a picture we can read (PNG, JPG or GIF)");
		}
		CapeArt.Picture first = frames.get(0);
		CapeArt.Detection detection = CapeArt.detect(first);
		boolean animated = frames.size() > 1 && animatedAllowed;
		String what = detection.isCape() ? "Cloak texture found - added as it is" : "Turned your picture into a cloak (elytra too) - fine-tune it in the launcher";
		if (frames.size() > 1 && !animatedAllowed) {
			what += ". Animated cloaks are Velo+, so it uses the first frame";
		}
		if (!animated) {
			Path png = Files.createTempFile("velo-cloak-", ".png");
			Files.write(png, CapeArt.stillPng(first, detection, CapeArt.Options.defaults(detection, first), detection.isCape()));
			return new Prepared(png, null, what);
		}
		byte[] original = Files.readAllBytes(file);
		return new Prepared(null, CapeArt.animatedGif(original, frames, delays, detection, CapeArt.Options.defaults(detection, first), detection.isCape()), what);
	}
}
