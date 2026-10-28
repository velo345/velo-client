package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dev-only (screenshot tour): opens the pack screens on a small demo pack in the dev run's packs folder. */
public final class PackTour {

	private static VeloPacksScreen packs;
	private static PackBrowserScreen browser;
	private static TextureEditorScreen editor;
	private static SoundEditorScreen sound;
	private static CodeEditorScreen code;

	private PackTour() {
	}

	private static Path pack() throws Exception {
		Path dir = PackBridge.packDir().resolve("Velo Tour Pack");
		if (!Files.exists(dir)) {
			VeloPacksScreen.createPack(dir, "Made in the Velo pack editor");
		}
		for (String key : new String[] {"minecraft:textures/block/oak_log.png", "minecraft:textures/item/diamond_sword.png",
				"minecraft:textures/entity/creeper/creeper.png", "minecraft:textures/block/sea_lantern.png"}) {
			Path file = PackContents.file(dir, key);
			if (!Files.exists(file)) {
				byte[] data = PackBridge.readGame(key);
				byte[] meta = PackBridge.readGame(key + ".mcmeta");
				if (data != null) {
					Files.createDirectories(file.getParent());
					Files.write(file, data);
					if (meta != null) {
						Files.write(file.resolveSibling(file.getFileName() + ".mcmeta"), meta);
					}
				}
			}
		}
		return dir;
	}

	public static void openPacks(MinecraftClient client) {
		try {
			pack();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		packs = new VeloPacksScreen(null);
		client.setScreen(packs);
	}

	public static void openBrowser(MinecraftClient client, boolean game) {
		try {
			browser = new PackBrowserScreen(packs, pack(), "file/Velo Tour Pack", "Velo Tour Pack");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		client.setScreen(browser);
		if (game) {
			browser.showForTour(true, PackContents.Category.BLOCKS);
		}
	}

	public static void browserCategory(boolean game, PackContents.Category category) {
		browser.showForTour(game, category);
	}

	public static void openSound(MinecraftClient client, String eventId) {
		try {
			pack();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		sound = new SoundEditorScreen(browser, PackBridge.packDir().resolve("Velo Tour Pack"), eventId);
		client.setScreen(sound);
	}

	public static void openCode(MinecraftClient client, String key) {
		try {
			Path dir = pack();
			Path file = PackContents.file(dir, key);
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.write(file, PackBridge.readGame(key));
			}
			code = new CodeEditorScreen(browser, file, key);
			client.setScreen(code);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void openEditor(MinecraftClient client, String key, boolean demo, boolean colorize) {
		try {
			Path dir = pack();
			editor = new TextureEditorScreen(browser, dir, key, PackContents.file(dir, key));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		client.setScreen(editor);
		if (demo) {
			editor.demoForTour(colorize);
		}
	}

	public static PackBrowserScreen browser() {
		return browser;
	}

	public static TextureEditorScreen editor() {
		return editor;
	}

	public static SoundEditorScreen sound() {
		return sound;
	}

	public static CodeEditorScreen code() {
		return code;
	}

	/** Starts from an empty tour pack (earlier runs leave their edits in it). */
	public static void resetPack() {
		Path dir = PackBridge.packDir().resolve("Velo Tour Pack");
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		} catch (java.nio.file.NoSuchFileException ignored) {
			// nothing to reset
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** Removes a copied file from the pack again (e.g. a shader only opened for show). */
	public static void removeFile(String key) {
		try {
			Files.deleteIfExists(PackContents.file(PackBridge.packDir().resolve("Velo Tour Pack"), key));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** Puts the pack on and reloads resources, like Save & reload. */
	public static void applyAndReload() {
		browser.applyAndReload();
	}
}
