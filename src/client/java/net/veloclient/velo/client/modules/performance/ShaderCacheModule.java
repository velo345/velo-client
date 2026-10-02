package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * Keeps compiled shaders and the GPU driver's pipeline cache on disk so the game doesn't rebuild
 * them on every start and every resource-pack reload (Vulkan renderer, 26.2+ - see {@link
 * ShaderCache}). On OpenGL the GPU driver already keeps its own shader cache; the launcher makes
 * sure that one is enabled and big enough.
 */
public final class ShaderCacheModule extends AbstractModule implements Configurable {

	public ShaderCacheModule() {
		super("shader-cache", "Shader Cache",
				"Saves compiled shaders and Vulkan pipelines to disk, so startup and resource-pack reloads skip recompiling them.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, true);
	}

	@Override
	public void onEnable() {
		ShaderCache.enabled = true;
	}

	@Override
	public void onDisable() {
		ShaderCache.enabled = false;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ActionButtonField("Show Cache Stats", () -> NotificationOverlay.show(new NotificationOverlay.Toast(null, null,
						String.format(Locale.ROOT, "Shader cache: %.1f MB on disk", ShaderCache.diskBytes() / (1024.0 * 1024.0)),
						ShaderCache.summary(), 9000, null, null, null))),
				new ConfigField.ActionButtonField("Clear Shader Cache (rebuilds next start)", () -> {
					ShaderCache.clear();
					NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Shader cache cleared",
							"Shaders are rebuilt and cached again on the next start", 5000, null, null, null));
				}));
	}
}
