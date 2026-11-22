package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * GPU Item Meshes: big item models (the detailed 3D items server resource packs hand out - held, dropped or on display)
 * are uploaded to the graphics card once and then only moved each frame, instead of being rebuilt vertex by vertex every
 * frame (see {@link GpuHeldMeshes}). On by default; off puts every item back on Minecraft's own path. 26.2.
 */
public final class GpuMeshesModule extends AbstractModule {

	/** Read by GpuHeldMeshes every item; true until the profile says otherwise. */
	public static volatile boolean active = true;

	public GpuMeshesModule() {
		super("gpu-item-meshes", "GPU Item Meshes",
				"Keeps big item models (custom 3D items from server packs - in hands, on the ground, on display) on the graphics card "
						+ "instead of rebuilding them every frame. Much higher FPS on busy server spawns; looks the same.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, true);
	}

	@Override
	public void onEnable() {
		active = true;
	}

	@Override
	public void onDisable() {
		active = false;
	}
}
