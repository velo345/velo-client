package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.ParticlesMode;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Caps particle density to reduce lag from particle-heavy fights/farms, by
 * driving vanilla's own particle-density option rather than reimplementing
 * particle spawning (design spec section 6.1).
 */
public final class ParticleLimiterModule extends AbstractModule {

	private ParticlesMode previousMode;

	public ParticleLimiterModule() {
		super("particle-limiter", "Particle Limiter",
				"Caps particle density to MINIMAL to reduce lag from particle-heavy areas.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		MinecraftClient client = MinecraftClient.getInstance();
		previousMode = client.options.getParticles().getValue();
		client.options.getParticles().setValue(ParticlesMode.MINIMAL);
	}

	@Override
	public void onDisable() {
		if (previousMode != null) {
			MinecraftClient.getInstance().options.getParticles().setValue(previousMode);
			previousMode = null;
		}
	}
}
