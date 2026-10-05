package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.hud.StatusBarIcons;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Opens the {@link PixelEditorScreen} for a hearts / food / armor icon: three states (full, plus
 * optional half and empty), starting from Velo's built-in art in the module's color so you edit an
 * icon instead of facing a blank grid. Saved via {@link StatusBarIcons}.
 */
public final class StatusIconEditor {

	private StatusIconEditor() {
	}

	/**
	 * @param kind     "heart", "food" or "armor" (also the built-in art's file prefix)
	 * @param color    the module's current color for the starter art
	 */
	public static void open(Screen parent, String kind, String label, int color) {
		MinecraftClient.getInstance().setScreen(new PixelEditorScreen(parent, "Draw " + label, new PixelEditorScreen.Host() {
			@Override
			public List<PixelEditorScreen.StateSpec> states() {
				return List.of(
						new PixelEditorScreen.StateSpec("full", "Full", false, null),
						new PixelEditorScreen.StateSpec("half", "Half", true, "Uses the left half of Full"),
						new PixelEditorScreen.StateSpec("empty", "Empty", true, "Uses a dark copy of Full"));
			}

			@Override
			public List<Integer> sizes() {
				return List.of(9, 16, 18);
			}

			@Override
			public NativeImage load(String key) {
				Map<StatusBarIcons.State, NativeImage> saved = StatusBarIcons.loadForEditing(kind);
				NativeImage wanted = saved.remove(state(key));
				saved.values().forEach(NativeImage::close);
				return wanted;
			}

			@Override
			public NativeImage starter(String key, int size) {
				return builtIn(kind, state(key), color);
			}

			@Override
			public String save(Map<String, NativeImage> images, String name) {
				Map<StatusBarIcons.State, NativeImage> out = new EnumMap<>(StatusBarIcons.State.class);
				for (Map.Entry<String, NativeImage> entry : images.entrySet()) {
					if (entry.getValue() != null) {
						out.put(state(entry.getKey()), entry.getValue());
					}
				}
				try {
					StatusBarIcons.save(kind, out);
					return null;
				} catch (Exception e) {
					return "Couldn't save: " + e.getMessage();
				}
			}

			@Override
			public void drawContextPreview(DrawContext context, PixelEditorScreen editor, int x, int y, int width, int height) {
				// A row the way it looks in game: full, full, full, half, empty, empty (2x size).
				NativeImage full = editor.image("full");
				NativeImage half = editor.image("half");
				NativeImage empty = editor.image("empty");
				int slot = 18;
				int step = 16;
				int rowX = x + Math.max(6, (width - (step * 5 + slot)) / 2);
				int rowY = y + (height - slot) / 2;
				for (int i = 0; i < 6; i++) {
					int sx = rowX + i * step;
					if (i < 3) {
						PixelEditorScreen.drawImage(context, full, sx, rowY, slot, 0, 1f);
					} else if (i == 3) {
						if (empty != null) {
							PixelEditorScreen.drawImage(context, empty, sx, rowY, slot, 0, 1f);
						} else {
							PixelEditorScreen.drawImage(context, full, sx, rowY, slot, 0x59000000, 1f);
						}
						if (half != null) {
							PixelEditorScreen.drawImage(context, half, sx, rowY, slot, 0, 1f);
						} else {
							PixelEditorScreen.drawImage(context, full, sx, rowY, slot, 0, 5 / 9f);
						}
					} else if (empty != null) {
						PixelEditorScreen.drawImage(context, empty, sx, rowY, slot, 0, 1f);
					} else {
						PixelEditorScreen.drawImage(context, full, sx, rowY, slot, 0x59000000, 1f);
					}
				}
			}
		}));
	}

	/** Opens the editor on top of whatever screen is showing (the module's settings). */
	public static void openFromSettings(String kind, String label, int color) {
		//? if <26.1 {
		Screen parent = MinecraftClient.getInstance().currentScreen;
		//?} else if <26.2 {
		/*Screen parent = net.minecraft.client.Minecraft.getInstance().screen;
		*///?} else {
		/*Screen parent = net.minecraft.client.Minecraft.getInstance().gui.screen();
		*///?}
		open(parent, kind, label, color);
	}

	private static StatusBarIcons.State state(String key) {
		return switch (key) {
			case "half" -> StatusBarIcons.State.HALF;
			case "empty" -> StatusBarIcons.State.EMPTY;
			default -> StatusBarIcons.State.FULL;
		};
	}

	/** Velo's own 9x9 art for a state, colored like the module draws it. */
	static NativeImage builtIn(String kind, StatusBarIcons.State state, int color) {
		NativeImage fill = read(kind + "_fill");
		NativeImage detail = read(kind + "_detail");
		NativeImage outline = read(kind + "_outline");
		if (fill == null || outline == null) {
			return null;
		}
		NativeImage out = PixelEditorScreen.blank(9);
		int fillColor = state == StatusBarIcons.State.EMPTY ? 0xFF2B2323 : color;
		int columns = state == StatusBarIcons.State.HALF ? 5 : 9;
		for (int y = 0; y < 9; y++) {
			for (int x = 0; x < 9; x++) {
				int argb = 0;
				if ((fill.getColorArgb(x, y) >>> 24) != 0) {
					argb = state == StatusBarIcons.State.HALF && x >= columns ? 0 : fillColor | 0xFF000000;
					if (argb != 0 && state != StatusBarIcons.State.EMPTY && detail != null) {
						argb = blend(argb, detail.getColorArgb(x, y));
					}
				}
				if ((outline.getColorArgb(x, y) >>> 24) != 0 && (state != StatusBarIcons.State.HALF || x < columns)) {
					argb = 0xFF000000;
				}
				out.setColorArgb(x, y, argb);
			}
		}
		fill.close();
		outline.close();
		if (detail != null) {
			detail.close();
		}
		return out;
	}

	private static int blend(int base, int over) {
		int a = over >>> 24;
		if (a == 0) {
			return base;
		}
		int r = (((over >> 16) & 0xFF) * a + ((base >> 16) & 0xFF) * (255 - a)) / 255;
		int g = (((over >> 8) & 0xFF) * a + ((base >> 8) & 0xFF) * (255 - a)) / 255;
		int b = ((over & 0xFF) * a + (base & 0xFF) * (255 - a)) / 255;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static NativeImage read(String name) {
		var resource = MinecraftClient.getInstance().getResourceManager()
				.getResource(Identifier.of("velo-client", "textures/gui/hud/" + name + ".png"));
		if (resource.isEmpty()) {
			return null;
		}
		//? if <26.1 {
		try (InputStream in = resource.get().getInputStream()) {
		//?} else {
		/*try (InputStream in = resource.get().open()) {
		*///?}
			return NativeImage.read(in);
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Velo: couldn't read built-in icon {}", name, e);
			return null;
		}
	}
}
