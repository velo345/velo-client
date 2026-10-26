package net.veloclient.velo.client.modules.rendering;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * No Break Particles: the burst of block bits when a block breaks and the crumbs while you mine it aren't spawned
 * (NoBreakParticlesMixin). Cleaner view while bridging, mining and in fights; sounds stay.
 */
public final class NoBreakParticlesModule extends AbstractModule implements Configurable {

	private static NoBreakParticlesModule instance;

	private boolean breakBurst = true;
	private boolean mining = true;

	public NoBreakParticlesModule() {
		super("no-break-particles", "No Break Particles",
				"Removes the particles when a block breaks and while you mine it - cleaner view, sounds stay.",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	/** A block just broke: skip its particle burst? */
	public static boolean hideBreak() {
		NoBreakParticlesModule m = instance;
		return m != null && m.isEnabled() && m.breakBurst;
	}

	/** A block is being mined: skip its crumbs? */
	public static boolean hideMining() {
		NoBreakParticlesModule m = instance;
		return m != null && m.isEnabled() && m.mining;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Block Break Particles", () -> breakBurst, v -> breakBurst = v),
				new ConfigField.ToggleField("Mining Particles", () -> mining, v -> mining = v));
	}
}
