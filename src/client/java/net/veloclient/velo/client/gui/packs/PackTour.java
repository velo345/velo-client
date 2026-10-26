package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dev-only (screenshot tour): opens the pack screens on a small demo pack in the dev run's packs folder. */
public final class PackTour {

	private static VeloPacksScreen packs;
	private static PackBrowserScreen browser;
	private static TextureEditorScreen editor;

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
		client.setScreen(new SoundEditorScreen(browser, PackBridge.packDir().resolve("Velo Tour Pack"), eventId));
	}

	public static void openCode(MinecraftClient client, String key) {
		try {
			Path dir = pack();
			Path file = PackContents.file(dir, key);
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.write(file, PackBridge.readGame(key));
			}
			client.setScreen(new CodeEditorScreen(browser, file, key));
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
}
