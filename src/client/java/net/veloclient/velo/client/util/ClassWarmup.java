package net.veloclient.velo.client.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.veloclient.velo.VeloClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads the classes behind the Store, the Locker and the cosmetics screens on a background thread soon after the game
 * has started, so opening them the first time doesn't.
 *
 * <p>Opening the Store loaded ~200 classes on the render thread, each one read from the jar and run through Mixin
 * first - the ~400 ms freeze on the first open (JFR + hitch log, 2026-11-06). Loading isn't initialising
 * ({@code Class.forName(name, false, ...)}): no static initialiser runs here, so nothing touches the game from this
 * thread; the classes are just ready when the render thread asks for them.
 */
public final class ClassWarmup {

	/** Packages (under the mod's root) whose classes the first Store / Locker open needs. */
	private static final String[] PACKAGES = {
			"net/veloclient/velo/client/gui/store", "net/veloclient/velo/client/gui/widget", "net/veloclient/velo/client/gui/window",
			"net/veloclient/velo/client/cosmetics", "net/veloclient/velo/client/emotes", "net/veloclient/velo/dev/pets",
	};
	/** Single classes from busier packages. */
	private static final String[] CLASSES = {
			"net.veloclient.velo.client.gui.CapeEquipScreen", "net.veloclient.velo.client.gui.EmoteLockerScreen",
			"net.veloclient.velo.client.gui.KillFxLockerScreen",
	};
	private static final long DELAY_MS = 4000;

	private ClassWarmup() {
	}

	public static void register() {
		if (Boolean.getBoolean("velo.noClassWarmup")) {
			return;
		}
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> Thread.ofPlatform().daemon().name("velo-class-warmup")
				.priority(Thread.MIN_PRIORITY).start(ClassWarmup::run));
	}

	private static void run() {
		try {
			Thread.sleep(DELAY_MS); // let the title screen settle first
			ClassLoader loader = ClassWarmup.class.getClassLoader();
			List<String> names = new ArrayList<>(List.of(CLASSES));
			FabricLoader.getInstance().getModContainer("velo-client").ifPresent(mod -> {
				for (Path root : mod.getRootPaths()) {
					for (String pkg : PACKAGES) {
						Path dir = root.resolve(pkg);
						if (!Files.isDirectory(dir)) {
							continue;
						}
						try (Stream<Path> files = Files.walk(dir)) {
							files.map(p -> root.relativize(p).toString().replace('\\', '/'))
									.filter(n -> n.endsWith(".class") && !n.contains("module-info"))
									.forEach(n -> names.add(n.substring(0, n.length() - 6).replace('/', '.')));
						} catch (Exception e) {
							VeloClient.LOGGER.debug("Class warm-up couldn't list {}", dir, e);
						}
					}
				}
			});
			long started = System.nanoTime();
			int loaded = 0;
			for (String name : names) {
				try {
					Class.forName(name, false, loader);
					loaded++;
				} catch (Throwable e) {
					// a class for another Minecraft version or an optional mod - it will never be needed here
				}
			}
			VeloClient.LOGGER.info("Velo class warm-up: {} classes in {} ms", loaded, (System.nanoTime() - started) / 1_000_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
