package net.veloclient.velo.client.modules.performance;

/**
 * The barrier {@code VulkanPassBarrierMixin} records after a render pass that drew into the main
 * render target, in place of vanilla's ALL_COMMANDS -> ALL_COMMANDS one.
 *
 * <p>What can come after such a pass, and what it needs:
 * <ul>
 * <li>another pass drawing into the main target (most of the frame) - its fragment, depth and
 * colour-output stages must see this pass's attachment writes;</li>
 * <li>a pass or post effect sampling the main target - fragment shader reads;</li>
 * <li>a copy / blit to the screen, a clear, a readback - transfer stages (those also put their own
 * full barrier in front);</li>
 * <li>any later write to a texture this pass sampled (the lightmap, say) - a write-after-read,
 * covered because the source scope is every graphics stage of this pass.</li>
 * </ul>
 * The one thing left out is the vertex stages of the following work: no shader reads the main
 * render target in a vertex shader, and vertex/uniform buffers are only written by uploads, which
 * keep vanilla's full barrier. So the next pass can already fetch and transform its vertices while
 * this one is still shading - the GPU no longer drains completely between passes. Passes that draw
 * into anything else (lightmap, outlines, translucency targets) keep vanilla's barrier, since some
 * of those are read in vertex shaders.
 *
 * <p>Vanilla also ends every clear, upload and copy with the same full barrier. Each operation
 * already starts after the barrier that ended the one before it, so the one after it only has to
 * order this transfer's writes before everything that follows: source = the transfer stage alone
 * (instead of every command recorded so far, which drained the GPU behind a 2 KB uniform upload).
 *
 * <p>{@code -Dvelo.fullPassBarriers=true} restores vanilla's barrier everywhere.
 */
public final class PassBarriers {

	private static final boolean DISABLED = Boolean.getBoolean("velo.fullPassBarriers");

	// VK_PIPELINE_STAGE_2_* / VK_ACCESS_2_* (synchronization2 values, same as VK13).
	private static final long STAGE_FRAGMENT_SHADER = 0x80L;
	private static final long STAGE_EARLY_FRAGMENT_TESTS = 0x100L;
	private static final long STAGE_LATE_FRAGMENT_TESTS = 0x200L;
	private static final long STAGE_COLOR_ATTACHMENT_OUTPUT = 0x400L;
	private static final long STAGE_COMPUTE_SHADER = 0x800L;
	private static final long STAGE_ALL_TRANSFER = 0x1000L;
	private static final long STAGE_ALL_GRAPHICS = 0x8000L;
	private static final long STAGE_ALL_COMMANDS = 0x10000L;
	private static final long ACCESS_TRANSFER_WRITE = 0x1000L;
	private static final long ACCESS_COLOR_ATTACHMENT_WRITE = 0x100L;
	private static final long ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE = 0x400L;
	private static final long ACCESS_MEMORY_READ = 0x8000L;
	private static final long ACCESS_MEMORY_WRITE = 0x10000L;

	private PassBarriers() {
	}

	public static boolean enabled() {
		return !DISABLED;
	}

	//? if >=26.2 && <26.3 {
	/*// True when every attachment of the pass is the main render target's colour or depth texture.
	public static boolean onlyMainTarget(com.mojang.blaze3d.systems.RenderPassDescriptor descriptor) {
		var target = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
		var color = target.getColorTexture();
		var depth = target.getDepthTexture();
		boolean any = false;
		for (var attachment : descriptor.colorAttachments()) {
			if (attachment == null || attachment.textureView() == null) {
				continue;
			}
			if (attachment.textureView().texture() != color) {
				return false;
			}
			any = true;
		}
		var depthAttachment = descriptor.depthAttachment();
		if (depthAttachment != null && depthAttachment.textureView() != null) {
			if (depthAttachment.textureView().texture() != depth) {
				return false;
			}
			any = true;
		}
		return any;
	}

	// What a render pass draws into: attachment textures and mip levels, and its render area.
	private record Key(java.util.List<Object> color, Object depth, int depthMip, java.util.List<Integer> colorMips, int x, int y, int w, int h) {
	}

	public static Object key(com.mojang.blaze3d.systems.RenderPassDescriptor descriptor) {
		java.util.List<Object> color = new java.util.ArrayList<>(2);
		java.util.List<Integer> mips = new java.util.ArrayList<>(2);
		for (var attachment : descriptor.colorAttachments()) {
			boolean present = attachment != null && attachment.textureView() != null;
			color.add(present ? attachment.textureView().texture() : null);
			mips.add(present ? attachment.textureView().baseMipLevel() : -1);
		}
		var depth = descriptor.depthAttachment();
		boolean hasDepth = depth != null && depth.textureView() != null;
		var area = descriptor.renderArea;
		return new Key(color, hasDepth ? depth.textureView().texture() : null, hasDepth ? depth.textureView().baseMipLevel() : -1, mips,
				area == null ? -1 : area.x(), area == null ? -1 : area.y(), area == null ? -1 : area.width(), area == null ? -1 : area.height());
	}

	// The next pass can continue the open rendering instance: same attachments and area, and it doesn't clear any of them.
	public static boolean canContinue(com.mojang.blaze3d.systems.RenderPassDescriptor descriptor, Object openKey) {
		if (DISABLED || openKey == null || !openKey.equals(key(descriptor))) {
			return false;
		}
		for (var attachment : descriptor.colorAttachments()) {
			if (attachment != null && attachment.clearValue() != null && attachment.clearValue().isPresent()) {
				return false;
			}
		}
		var depth = descriptor.depthAttachment();
		return depth == null || depth.clearValue() == null || !depth.clearValue().isPresent();
	}

	public static void countMerged() {
		if (net.veloclient.velo.client.devtools.RenderProfiler.ENABLED) {
			net.veloclient.velo.client.devtools.RenderProfiler.barrierFrom("mergedPass");
		}
	}

	public static void afterMainTargetPass(org.lwjgl.vulkan.VkCommandBuffer commandBuffer, org.lwjgl.system.MemoryStack stack) {
		var barrier = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack).sType$Default()
				.srcStageMask(STAGE_ALL_GRAPHICS)
				.srcAccessMask(ACCESS_COLOR_ATTACHMENT_WRITE | ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE)
				.dstStageMask(STAGE_FRAGMENT_SHADER | STAGE_EARLY_FRAGMENT_TESTS | STAGE_LATE_FRAGMENT_TESTS
						| STAGE_COLOR_ATTACHMENT_OUTPUT | STAGE_COMPUTE_SHADER | STAGE_ALL_TRANSFER)
				.dstAccessMask(ACCESS_MEMORY_READ | ACCESS_MEMORY_WRITE);
		var dependency = org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
		org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
		if (net.veloclient.velo.client.devtools.RenderProfiler.ENABLED) {
			net.veloclient.velo.client.devtools.RenderProfiler.barrierFrom("preciseAfterMainPass");
		}
	}

	public static void afterTransfer(org.lwjgl.vulkan.VkCommandBuffer commandBuffer, org.lwjgl.system.MemoryStack stack) {
		var barrier = org.lwjgl.vulkan.VkMemoryBarrier2.calloc(1, stack).sType$Default()
				.srcStageMask(STAGE_ALL_TRANSFER)
				.srcAccessMask(ACCESS_TRANSFER_WRITE)
				.dstStageMask(STAGE_ALL_COMMANDS)
				.dstAccessMask(ACCESS_MEMORY_READ | ACCESS_MEMORY_WRITE);
		var dependency = org.lwjgl.vulkan.VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
		org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
		if (net.veloclient.velo.client.devtools.RenderProfiler.ENABLED) {
			net.veloclient.velo.client.devtools.RenderProfiler.barrierFrom("preciseAfterTransfer");
		}
	}
	*///?}
}
