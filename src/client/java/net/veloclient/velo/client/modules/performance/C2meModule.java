package net.veloclient.velo.client.modules.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * C2ME (Experimental): asks the Velo launcher to add the C2ME mod, which generates, loads and saves chunks on every CPU
 * core - singleplayer and LAN worlds load and explore much faster on many-core CPUs. It does nothing on multiplayer
 * servers (the server makes the chunks there).
 *
 * <p>A game can't add a mod to itself while it runs, so this only writes {@code config/velo-client/experimental-mods.properties};
 * the launcher reads it before the next start and installs C2ME (or removes the copy it installed). Opt-in because it
 * was never measured next to Velo and is marked experimental upstream - see PerformanceModsInstaller.
 */
public final class C2meModule extends AbstractModule {

	public static final String KEY = "c2me";

	public C2meModule() {
		super("c2me", "C2ME (Experimental)",
				"Faster world generation and loading in singleplayer on every CPU core. Applies on your next start from the Velo launcher.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		write(true);
	}

	@Override
	public void onDisable() {
		write(false);
	}

	/** Whether C2ME is actually running in this session (the launcher installed it, or the player did). */
	public static boolean loaded() {
		return FabricLoader.getInstance().isModLoaded("c2me");
	}

	private static void write(boolean wanted) {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("velo-client").resolve("experimental-mods.properties");
		try {
			java.util.Properties props = new java.util.Properties();
			if (Files.exists(file)) {
				try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					props.load(in);
				}
			}
			if (String.valueOf(wanted).equals(props.getProperty(KEY))) {
				return;
			}
			props.setProperty(KEY, String.valueOf(wanted));
			Files.createDirectories(file.getParent());
			try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				props.store(out, "Written by Velo Client - read by the Velo launcher before the game starts");
			}
		} catch (IOException e) {
			VeloClient.LOGGER.warn("Couldn't save the C2ME choice", e);
		}
	}
}
