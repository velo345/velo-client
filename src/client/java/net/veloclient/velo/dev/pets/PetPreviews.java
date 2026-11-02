package net.veloclient.velo.dev.pets;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pet's Store preview: its idle turntable, pre-rendered as one sprite sheet (8 x 6 frames of 176 px, JPEG, from the
 * server). Decoded once in the background when first shown, then drawn as one frame of a texture - no 3D in the Store.
 */
final class PetPreviews {

	static final int FRAME = 176;
	static final int COLUMNS = 8;
	static final int ROWS = 6;
	static final int FRAMES = COLUMNS * ROWS;
	/** Frames per second of the turntable. */
	static final int FPS = 16;

	private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();

	private PetPreviews() {
	}

	/** Draws the pet's preview at (x, y), {@code size} square; false while its sheet isn't there yet. */
	static boolean draw(DrawContext context, String petId, int x, int y, int size) {
		Identifier texture = texture(petId);
		if (texture == null) {
			return false;
		}
		int frame = (int) (System.currentTimeMillis() * FPS / 1000 % FRAMES);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, (frame % COLUMNS) * FRAME, (frame / COLUMNS) * FRAME, size, size,
				FRAME, FRAME, FRAME * COLUMNS, FRAME * ROWS);
		return true;
	}

	private static Identifier texture(String petId) {
		Identifier ready = READY.get(petId);
		if (ready != null || LOADING.containsKey(petId)) {
			return ready;
		}
		var item = PetStore.item(petId).orElse(null);
		String sha = item == null || item.files() == null ? null : item.files().get("sheet");
		Path file = sha == null ? null : CosmeticsCatalog.file(sha);   // downloads in the background, null until it's there
		if (file == null) {
			return null;
		}
		LOADING.put(petId, true);
		Thread.ofVirtual().name("velo-pet-preview").start(() -> {
			try {
				BufferedImage sheet = ImageIO.read(file.toFile());
				if (sheet == null) {
					return;
				}
				NativeImage image = new NativeImage(sheet.getWidth(), sheet.getHeight(), false);
				for (int py = 0; py < sheet.getHeight(); py++) {
					for (int px = 0; px < sheet.getWidth(); px++) {
						image.setColorArgb(px, py, 0xFF000000 | sheet.getRGB(px, py));
					}
				}
				MinecraftClient.getInstance().execute(() -> {
					Identifier id = Identifier.of("velo-client", "pet_preview/" + petId);
					MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "pet preview " + petId, image));
					READY.put(petId, id);
				});
			} catch (Exception e) {
				LOADING.remove(petId);
			}
		});
		return null;
	}
}
