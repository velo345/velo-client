package net.veloclient.velo;

import net.fabricmc.api.ModInitializer;
import net.veloclient.velo.config.VeloPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (non-client-only) entrypoint. Velo Client is a client-side-only mod
 * today, but module registration/config live outside the {@code client}
 * source set so a future companion server plugin (e.g. exposing TPS for the
 * Server Tools graphs) can depend on this jar without pulling in rendering code.
 */
public final class VeloClient implements ModInitializer {

	public static final String MOD_ID = "velo-client";
	public static final Logger LOGGER = LoggerFactory.getLogger("Velo Client");

	@Override
	public void onInitialize() {
		VeloPaths.ensureDirectories();
		LOGGER.info("Velo Client initializing (config root: {})", VeloPaths.root());
	}
}
