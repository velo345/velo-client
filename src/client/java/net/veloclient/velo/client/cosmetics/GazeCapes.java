package net.veloclient.velo.client.cosmetics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Capes whose eye follows you: the Dragon Eye cape looks at the nearest player standing behind whoever wears it.
 *
 * <p>The cape's catalog entry carries a "gaze" file next to its GIF - a 7 x 3 atlas of the whole cape texture region
 * (see tools/cosmetics/cape3d/capes/dragon_eye_gaze.py), the iris turned to each direction. While someone is behind a
 * wearer, that wearer gets a texture of their own, painted by blending the four atlas cells nearest to where the target
 * is, so the eye tracks smoothly; with nobody behind it eases back to centre and hands over to the normal animation.
 * Purely visual and local: each client decides from the players it can see.
 */
public final class GazeCapes {

	private static final String ITEM_ID = "cape_dragon_eye";
	private static final int COLS = 7, ROWS = 3;
	private static final int MAX_WEARERS = 4;
	private static final double RANGE = 18.0;
	private static final long PAINT_EVERY_NANOS = 90_000_000L;

	private static NativeImage atlas;
	private static int cellW, cellH, fullW, fullH;
	private static final AtomicBoolean loading = new AtomicBoolean();
	private static final Map<Integer, Wearer> WEARERS = new HashMap<>();

	private static final class Wearer {
		final Identifier id;
		final AnimatedCapeAsset.SmoothTexture texture;
		float gx, gy;
		float paintedX = 9, paintedY = 9;
		long lastPaint, lastTarget, lastUsed;
		long lastFrame = System.nanoTime();

		Wearer(int entityId) {
			this.id = Identifier.of("velo-client", "cape_gaze_" + entityId);
			this.texture = new AnimatedCapeAsset.SmoothTexture(() -> "gaze cloak " + entityId, fullW, fullH);
			this.texture.getImage().fillRect(0, 0, fullW, fullH, 0);
			MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
		}
	}

	private GazeCapes() {
	}

	/** The texture to draw {@code definition} with on entity {@code entityId}: its own gaze texture, or {@code fallback}. */
	public static Identifier texture(CapeDefinition definition, int entityId, Identifier fallback) {
		String itemId = definition.sourceItemId() != null ? definition.sourceItemId() : definition.id();
		if (!ITEM_ID.equals(itemId) || !ensureAtlas()) {
			return fallback;
		}
		long now = System.nanoTime();
		float[] target = GazeTargets.find(entityId, RANGE);
		Wearer w = WEARERS.get(entityId);
		if (w == null) {
			if (target == null || WEARERS.size() >= MAX_WEARERS) {
				return fallback;
			}
			w = new Wearer(entityId);
			WEARERS.put(entityId, w);
		}
		w.lastUsed = now;
		float dt = Math.min(.1f, (now - w.lastFrame) / 1e9f);
		w.lastFrame = now;
		if (target != null) {
			w.lastTarget = now;
		}
		// Ease towards the target (or back to centre), quick but not snapping.
		float tx = target != null ? target[0] : 0f, ty = target != null ? target[1] : 0f;
		float k = 1f - (float) Math.exp(-dt * 9f);
		w.gx += (tx - w.gx) * k;
		w.gy += (ty - w.gy) * k;
		if (target == null && now - w.lastTarget > 1_500_000_000L && Math.abs(w.gx) + Math.abs(w.gy) < .05f) {
			return fallback;
		}
		if (now - w.lastPaint > PAINT_EVERY_NANOS && Math.abs(w.gx - w.paintedX) + Math.abs(w.gy - w.paintedY) > .02f) {
			paint(w);
			w.lastPaint = now;
		}
		return w.id;
	}

	/** Frees wearers nobody has drawn for a few seconds. Called every client tick. */
	public static void tick() {
		long now = System.nanoTime();
		for (Iterator<Map.Entry<Integer, Wearer>> it = WEARERS.entrySet().iterator(); it.hasNext(); ) {
			Wearer w = it.next().getValue();
			if (now - w.lastUsed > 5_000_000_000L) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(w.id);
				it.remove();
			}
		}
	}

	private static boolean ensureAtlas() {
		if (atlas != null) {
			return true;
		}
		if (loading.get()) {
			return false;
		}
		String sha = CosmeticsCatalog.item(ITEM_ID).map(i -> i.files().get("gaze")).orElse(null);
		Path file = sha == null ? null : CosmeticsCatalog.file(sha);
		if (file == null || !loading.compareAndSet(false, true)) {
			return false;
		}
		Thread.ofVirtual().name("velo-gaze-atlas").start(() -> {
			try (InputStream in = Files.newInputStream(file)) {
				// The atlas comes at the HD cloaks' 32 texels per cape pixel; kept at what the cloaks are drawn at.
				java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(in);
				int texels = source.getWidth() / COLS / 22;
				NativeImage image = GifDecoder.shrunkForCape(source, texels);
				MinecraftClient.getInstance().execute(() -> {
					cellW = image.getWidth() / COLS;
					cellH = image.getHeight() / ROWS;
					// The cells are the used 22 x 17 cape-pixel corner of a 64 x 32 layout.
					fullW = cellW * 64 / 22;
					fullH = cellH * 32 / 17;
					atlas = image;
				});
			} catch (Exception e) {
				net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't load the gaze atlas: {}", e.toString());
				loading.set(false);
			}
		});
		return false;
	}

	/** Paints the wearer's texture as the bilinear blend of the four atlas cells around its gaze. */
	private static void paint(Wearer w) {
		float fx = (Math.max(-1f, Math.min(1f, w.gx)) + 1f) / 2f * (COLS - 1);
		float fy = (1f - (Math.max(-1f, Math.min(1f, w.gy)) + 1f) / 2f) * (ROWS - 1);
		int c0 = Math.min(COLS - 2, (int) fx), r0 = Math.min(ROWS - 2, (int) fy);
		float ax = fx - c0, ay = fy - r0;
		float w00 = (1 - ax) * (1 - ay), w10 = ax * (1 - ay), w01 = (1 - ax) * ay, w11 = ax * ay;
		int x0 = c0 * cellW, y0 = r0 * cellH, x1 = x0 + cellW, y1 = y0 + cellH;
		NativeImage out = w.texture.getImage();
		for (int y = 0; y < cellH; y++) {
			for (int x = 0; x < cellW; x++) {
				int p00 = atlas.getColorArgb(x0 + x, y0 + y), p10 = atlas.getColorArgb(x1 + x, y0 + y);
				int p01 = atlas.getColorArgb(x0 + x, y1 + y), p11 = atlas.getColorArgb(x1 + x, y1 + y);
				out.setColorArgb(x, y, blend(p00, p10, p01, p11, w00, w10, w01, w11));
			}
		}
		w.texture.upload();
		w.paintedX = w.gx;
		w.paintedY = w.gy;
	}

	private static int blend(int a, int b, int c, int d, float wa, float wb, float wc, float wd) {
		int r = Math.round(((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb + ((c >> 16) & 255) * wc + ((d >> 16) & 255) * wd);
		int g = Math.round(((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb + ((c >> 8) & 255) * wc + ((d >> 8) & 255) * wd);
		int bl = Math.round((a & 255) * wa + (b & 255) * wb + (c & 255) * wc + (d & 255) * wd);
		return 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, bl);
	}
}
