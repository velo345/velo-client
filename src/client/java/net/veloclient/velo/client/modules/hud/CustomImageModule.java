package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.gui.NativeFileDialog;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.config.VeloPaths;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom Image: any picture of yours on the HUD - a logo, a stream overlay, a team banner. "Import Image..." opens the
 * system file dialog; the picture is copied into .velo-client/hud-images as a PNG (large ones scaled down to 1024 px)
 * so it survives the original being moved. Pick one of the imported pictures, size it, fade it.
 */
public final class CustomImageModule extends AbstractModule implements HudModule, Configurable {

	private static final String NONE = "(none - import one)";
	private static final int MAX_SIDE = 1024;

	private final HudPosition position = new HudPosition(0.02f, 0.55f);
	private String image = NONE;
	private double size = 80;
	private double opacity = 1.0;
	private boolean showBackground = false;

	private static final Map<String, Loaded> LOADED = new HashMap<>();
	private static volatile String status;

	private record Loaded(Identifier id, int width, int height) {
	}

	public CustomImageModule() {
		super("custom-image", "Custom Image", "Put any picture on your HUD - a logo, banner or overlay. Import PNG, JPG or GIF.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	private static Path folder() {
		return VeloPaths.root().resolve("hud-images");
	}

	private static List<String> images() {
		List<String> names = new ArrayList<>();
		try (var files = Files.list(folder())) {
			files.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png")).sorted().forEach(names::add);
		} catch (Exception ignored) {
			// no folder yet
		}
		return names;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	/** Loads (once) and returns the picture's texture, or null when there is none / it can't be read. */
	private static Loaded texture(String name) {
		if (name == null || name.equals(NONE)) {
			return null;
		}
		Loaded cached = LOADED.get(name);
		if (cached != null || LOADED.containsKey(name)) {
			return cached;
		}
		Loaded loaded = null;
		try (InputStream in = Files.newInputStream(folder().resolve(name))) {
			NativeImage picture = NativeImage.read(in);
			String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
			Identifier id = Identifier.of("velo-client", "hud_image/" + key);
			int w = picture.getWidth();
			int h = picture.getHeight();
			MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "velo-hud-image", picture));
			loaded = new Loaded(id, w, h);
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Custom Image: couldn't load {}", name, e);
		}
		LOADED.put(name, loaded);
		return loaded;
	}

	/** Copies the chosen file into hud-images as a PNG (scaled to at most 1024 px) and returns its new name. */
	private static String importFile(Path source) throws Exception {
		java.awt.image.BufferedImage read = javax.imageio.ImageIO.read(source.toFile());
		if (read == null) {
			throw new IllegalArgumentException("Not a picture Velo can read (use PNG, JPG, GIF or BMP)");
		}
		int w = read.getWidth();
		int h = read.getHeight();
		double scale = Math.min(1.0, MAX_SIDE / (double) Math.max(w, h));
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(read, 0, 0, nw, nh, null);
		g.dispose();
		String base = source.getFileName().toString().replaceAll("\\.[^.]*$", "").replaceAll("[^A-Za-z0-9 _-]", "").trim();
		if (base.isEmpty()) {
			base = "image";
		}
		Files.createDirectories(folder());
		String name = base + ".png";
		for (int i = 2; Files.exists(folder().resolve(name)); i++) {
			name = base + " " + i + ".png";
		}
		javax.imageio.ImageIO.write(out, "png", folder().resolve(name).toFile());
		return name;
	}

	private void pickAndImport() {
		Thread thread = new Thread(() -> {
			try {
				Path file = NativeFileDialog.pickFile("Choose a picture for your HUD", "Images", "png", "jpg", "jpeg", "gif", "bmp");
				if (file == null) {
					return;
				}
				String name = importFile(file);
				MinecraftClient.getInstance().execute(() -> {
					LOADED.remove(name);
					image = name;
					status = null;
				});
			} catch (Exception e) {
				status = e.getMessage() == null ? "Couldn't import that picture" : e.getMessage();
				VeloClient.LOGGER.warn("Custom Image: import failed", e);
			}
		}, "Velo HUD image import");
		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		Loaded t = texture(image);
		int w = width();
		int h = height();
		if (t == null) {
			if (net.veloclient.velo.client.hud.HudSample.active() || net.veloclient.velo.client.util.ClientCompat.currentScreen() != null) {
				VeloDraw.fillRounded(context, x, y, w, h, 6, 0x60101014);
				String label = status != null ? status : "Custom Image";
				HudLook.draw(context, label, x + Math.max(2, (w - HudLook.measure(label)) / 2), y + h / 2 - 4, 0xFFEDE6E6);
			}
			return;
		}
		if (showBackground) {
			VeloDraw.fillRounded(context, x - 3, y - 3, w + 6, h + 6, 6, 0x90101014);
		}
		int alpha = Math.max(0, Math.min(255, (int) Math.round(opacity * 255)));
		context.drawTexture(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, 0f, w, h, t.width(), t.height(), t.width(), t.height(),
				alpha << 24 | 0xFFFFFF);
	}

	@Override
	public int width() {
		return (int) Math.round(size);
	}

	@Override
	public int height() {
		Loaded t = texture(image);
		if (t == null) {
			return (int) Math.round(size * 0.5);
		}
		return Math.max(1, (int) Math.round(size * t.height() / (double) t.width()));
	}

	@Override
	public List<ConfigField> configFields() {
		List<String> options = new ArrayList<>(images());
		if (options.isEmpty() || !options.contains(image)) {
			options.add(0, image.equals(NONE) || options.isEmpty() ? NONE : image);
		}
		return List.of(
				new ConfigField.ActionButtonField("Import Image...", this::pickAndImport),
				new ConfigField.ChoiceField("Image", options, () -> image, v -> image = v),
				new ConfigField.SliderField("Width", 16, 480, () -> size, v -> size = Math.round(v), v -> Math.round(v) + " px"),
				new ConfigField.SliderField("Opacity", 0.1, 1.0, () -> opacity, v -> opacity = Math.round(v * 100) / 100.0, v -> Math.round(v * 100) + "%"),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v),
				new ConfigField.ActionButtonField("Open Image Folder", () -> {
					try {
						Files.createDirectories(folder());
					} catch (Exception ignored) {
						// shown as missing by the file manager
					}
					net.veloclient.velo.client.gui.FileManagerOpener.open(folder().toFile(), s -> status = s);
				}));
	}
}
