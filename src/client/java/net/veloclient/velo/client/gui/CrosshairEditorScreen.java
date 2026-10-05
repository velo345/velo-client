package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.veloclient.velo.client.crosshair.CrosshairDefinition;
import net.veloclient.velo.client.crosshair.CrosshairManager;
import net.veloclient.velo.client.gui.widget.VeloStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Opens one crosshair in the shared {@link PixelEditorScreen}: an "Idle" drawing and an optional
 * "Hit" drawing (shown while you aim at something you can hit). Older crosshairs that used the
 * former "color swap" hit effect are converted into a Hit drawing with the same look.
 */
public final class CrosshairEditorScreen {

	private CrosshairEditorScreen() {
	}

	public static PixelEditorScreen create(Screen parent, CrosshairDefinition definition) {
		String id = definition.id();
		return new PixelEditorScreen(parent, "Edit Crosshair", new PixelEditorScreen.Host() {
			@Override
			public List<PixelEditorScreen.StateSpec> states() {
				return List.of(
						new PixelEditorScreen.StateSpec("idle", "Idle", false, null),
						new PixelEditorScreen.StateSpec("hit", "On target", true, "Looks the same as Idle"));
			}

			@Override
			public List<Integer> sizes() {
				List<Integer> sizes = new ArrayList<>(List.of(definition.canvasSize()));
				for (int s : new int[] {8, 16, 32, 64}) {
					if (!sizes.contains(s)) {
						sizes.add(s);
					}
				}
				return sizes;
			}

			@Override
			public NativeImage load(String key) {
				if (key.equals("idle")) {
					return CrosshairManager.loadIdleImage(id);
				}
				return switch (definition.hitMode()) {
					case SEPARATE_IMAGE -> {
						try {
							yield CrosshairManager.loadHitImage(id);
						} catch (RuntimeException e) {
							yield null;
						}
					}
					case COLOR_SWAP -> swapped(CrosshairManager.loadIdleImage(id), definition.colorSwap());
					case NONE -> null;
				};
			}

			@Override
			public String name() {
				return definition.name();
			}

			@Override
			public String save(Map<String, NativeImage> images, String name) {
				NativeImage idle = images.get("idle");
				NativeImage hit = images.get("hit");
				CrosshairDefinition saved = new CrosshairDefinition(id, name, idle.getWidth(),
						hit != null ? CrosshairDefinition.HitMode.SEPARATE_IMAGE : CrosshairDefinition.HitMode.NONE, Map.of());
				try {
					CrosshairManager.save(saved, idle, hit);
					return null;
				} catch (RuntimeException e) {
					return "Couldn't save: " + e.getMessage();
				}
			}

			@Override
			public void drawContextPreview(DrawContext context, PixelEditorScreen editor, int x, int y, int width, int height) {
				// Both states over a sky/grass backdrop at in-game size (16 GUI px) and 2x.
				int half = width / 2;
				for (int i = 0; i < 2; i++) {
					int bx = x + 4 + i * half;
					int bw = half - 8;
					net.veloclient.velo.client.gui.widget.VeloDraw.fillGradient(context, bx, y + 4, bw, height - 8, 0xFF79A6FF, 0xFF5E8F3E);
					NativeImage image = editor.image(i == 0 ? "idle" : "hit");
					if (image == null) {
						image = editor.image("idle");
					}
					float cx = bx + bw / 2f;
					float cy = y + height / 2f;
					PixelEditorScreen.drawImage(context, image, cx - 16, cy - 16, 32, 0, 1f);
					context.drawTextWithShadow(net.minecraft.client.MinecraftClient.getInstance().textRenderer,
							i == 0 ? "Idle" : "On target", bx + 3, y + 6, VeloStyle.text());
				}
			}
		});
	}

	private static NativeImage swapped(NativeImage idle, Map<Integer, Integer> swap) {
		NativeImage out = PixelEditorScreen.copy(idle);
		for (int y = 0; y < out.getHeight(); y++) {
			for (int x = 0; x < out.getWidth(); x++) {
				int argb = out.getColorArgb(x, y);
				if ((argb >>> 24) == 0) {
					continue;
				}
				Integer mapped = swap.get(argb | 0xFF000000);
				if (mapped != null) {
					out.setColorArgb(x, y, (mapped & 0x00FFFFFF) | (argb & 0xFF000000));
				}
			}
		}
		idle.close();
		return out;
	}
}
