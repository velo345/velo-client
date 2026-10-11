package net.veloclient.velo.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips the Vulkan-only mixins on game versions without a Vulkan renderer (before 26.2), so the
 * shared mixin list stays the same for every version without logging "target not found" there.
 * 26.3 moved the renderer to {@code com.mojang.renderpearl}; two mixins hook 26.2-only internals
 * (bind group layouts, the intermediary shader module) that no longer exist there and are skipped.
 */
public final class VeloMixinPlugin implements IMixinConfigPlugin {

	private static final Set<String> ONLY_26_2 = Set.of("VulkanDrawStateMixin", "VulkanShaderCacheMixin", "VulkanRenderProfileMixin");

	private boolean hasVulkan;
	private boolean renderPearl;

	@Override
	public void onLoad(String mixinPackage) {
		ClassLoader loader = VeloMixinPlugin.class.getClassLoader();
		renderPearl = loader.getResource("com/mojang/renderpearl/backend/vulkan/VulkanDevice.class") != null;
		hasVulkan = renderPearl || loader.getResource("com/mojang/blaze3d/vulkan/VulkanDevice.class") != null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (renderPearl && ONLY_26_2.contains(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1))) {
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
