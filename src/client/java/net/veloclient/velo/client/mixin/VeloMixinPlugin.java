package net.veloclient.velo.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Skips the Vulkan-only mixins on game versions without a Vulkan renderer (before 26.2), so the
 * shared mixin list stays the same for every version without logging "target not found" there.
 */
public final class VeloMixinPlugin implements IMixinConfigPlugin {

	private boolean hasVulkan;

	@Override
	public void onLoad(String mixinPackage) {
		hasVulkan = VeloMixinPlugin.class.getClassLoader().getResource("com/mojang/blaze3d/vulkan/VulkanDevice.class") != null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
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
