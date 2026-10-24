package net.veloclient.velo.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips the Vulkan-only mixins on game versions without a Vulkan renderer (before 26.2), so the
 * shared mixin list stays the same for every version without logging "target not found" there.
 * 26.3 moved the renderer to {@code com.mojang.renderpearl}; the Vulkan mixins have a 26.3 branch
 * each. {@code ONLY_26_2} lists the dev-only probes that don't (barrier probes, surface timing).
 *
 * <p>Also skips the vanilla-terrain mixins when Sodium is installed (it always is with the Velo
 * launcher): Sodium replaces vanilla's chunk renderer, so those hooks would never run - and
 * {@code ChunkSlicePrepMixin} collided with Sodium's own wrap of {@code prepareChunkRenders}, which
 * logged an InvalidInjectionException on every launch.
 */
public final class VeloMixinPlugin implements IMixinConfigPlugin {

	private static final Set<String> ONLY_26_2 = Set.of("VulkanBarrierProbeMixin", "VulkanPassBarrierMixin", "VulkanSurfaceProfileMixin");
	/** Patch vanilla's own terrain path, which Sodium replaces wholesale. */
	private static final Set<String> VANILLA_TERRAIN = Set.of("ChunkSlicePrepMixin", "SectionMeshSlotsMixin", "UberBufferSlotsMixin");

	private boolean hasVulkan;
	private boolean renderPearl;
	private boolean sodium;

	@Override
	public void onLoad(String mixinPackage) {
		ClassLoader loader = VeloMixinPlugin.class.getClassLoader();
		renderPearl = loader.getResource("com/mojang/renderpearl/backend/vulkan/VulkanDevice.class") != null;
		hasVulkan = renderPearl || loader.getResource("com/mojang/blaze3d/vulkan/VulkanDevice.class") != null;
		sodium = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (sodium && VANILLA_TERRAIN.contains(simpleName)) {
			return false;
		}
		if (renderPearl && ONLY_26_2.contains(simpleName)) {
			return false;
		}
		// Velo Shaders: the Vulkan versions (26.2 and 26.3).
		if ((simpleName.startsWith("VeloShaders") || simpleName.startsWith("VeloShadow") || simpleName.startsWith("VeloSodium")
				|| simpleName.startsWith("VeloFoliage") || simpleName.startsWith("VeloBlockRender") || simpleName.startsWith("VeloFluid"))
				&& !hasVulkan) {
			return false;
		}
		// The shadow pass lives in Sodium's renderer.
		if ((simpleName.startsWith("VeloShadow") || simpleName.startsWith("VeloSodium") || simpleName.startsWith("VeloFoliage")
				|| simpleName.startsWith("VeloBlockRender") || simpleName.startsWith("VeloFluid")) && !sodium) {
			return false;
		}
		return hasVulkan || !mixinClassName.contains(".Vulkan");
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
