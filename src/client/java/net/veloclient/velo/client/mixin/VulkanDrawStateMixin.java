package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
*///?}

/**
 * Removes redundant per-draw work from 26.2's Vulkan render pass (Sodium doesn't run on Vulkan, so
 * nothing else touches this path).
 *
 * Vanilla re-pushes the pipeline's WHOLE descriptor set - every uniform buffer and sampler, each
 * found by a string HashMap lookup and written into freshly allocated native structs - whenever
 * any single uniform changes. Terrain changes exactly one ("ChunkSection") before every section
 * draw, so a frame pays that full rebuild ~1000+ times. Push descriptors can be updated
 * incrementally (they stay valid until the pipeline layout changes), so while the pipeline is
 * unchanged this pushes only the uniform buffers that actually changed. Pipeline or texture
 * changes still take vanilla's full push. Setting a uniform to the slice it already holds no
 * longer dirties anything, and vertex/index buffer binds identical to the current binding are
 * skipped (bindings survive pipeline switches in Vulkan).
 */
//? if >=26.2 {
/*@Mixin(VulkanRenderPass.class)
public abstract class VulkanDrawStateMixin {

	@Unique
	private static final int VELO_MAX_TRACKED = 8;
	@Unique
	private static final int VELO_VERTEX_SLOTS = 4;

	@Shadow
	@Final
	private VkCommandBuffer commandBuffer;
	@Shadow
	protected @Nullable VulkanRenderPipeline pipeline;
	@Shadow
	private boolean anyDescriptorDirty;
	@Shadow
	@Final
	protected HashMap<String, GpuBufferSlice> uniforms;

	@Unique
	private boolean velo$needsFullPush = true;
	@Unique
	private @Nullable VulkanRenderPipeline velo$pushedPipeline;
	@Unique
	private final String[] velo$changed = new String[VELO_MAX_TRACKED];
	@Unique
	private int velo$changedCount;
	@Unique
	private final long[] velo$vertexHandle = {-1L, -1L, -1L, -1L};
	@Unique
	private final long[] velo$vertexOffset = new long[VELO_VERTEX_SLOTS];
	// The last few uniform slices set, by name identity - a cheap "is this the value it already
	// has" check without hashing the name into the uniforms map on every draw.
	@Unique
	private final String[] velo$recentNames = new String[4];
	@Unique
	private final GpuBufferSlice[] velo$recentValues = new GpuBufferSlice[4];
	@Unique
	private int velo$recentNext;
	@Unique
	private long velo$indexHandle = -1L;
	@Unique
	private @Nullable IndexType velo$indexType;

	@Inject(method = "setPipeline", at = @At("HEAD"))
	private void velo$pipelineChanged(com.mojang.blaze3d.pipeline.RenderPipeline renderPipeline, CallbackInfo ci) {
		velo$needsFullPush = true;
	}

	@Inject(method = "bindTexture", at = @At("HEAD"))
	private void velo$textureChanged(String name, @Nullable GpuTextureView view, @Nullable GpuSampler sampler, CallbackInfo ci) {
		velo$needsFullPush = true;
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V", at = @At("HEAD"))
	private void velo$uniformBufferChanged(String name, GpuBuffer value, CallbackInfo ci) {
		velo$forgetRecent(name);
		velo$noteChanged(name);
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"), cancellable = true)
	private void velo$uniformSliceChanged(String name, GpuBufferSlice value, CallbackInfo ci) {
		if (!PerformanceBoostModule.fastDrawPath) {
			java.util.Arrays.fill(velo$recentNames, null); // vanilla writes the map behind the cache's back now
			velo$noteChanged(name);
			return;
		}
		for (int i = 0; i < velo$recentNames.length; i++) {
			if (velo$recentNames[i] == name) {
				GpuBufferSlice current = velo$recentValues[i];
				if (current.buffer() == value.buffer() && current.offset() == value.offset() && current.length() == value.length()) {
					ci.cancel();
					return;
				}
				velo$recentValues[i] = value;
				velo$noteChanged(name);
				return;
			}
		}
		// Another String instance with the same name may be cached - it's about to be overwritten.
		velo$forgetRecent(name);
		int slot = velo$recentNext;
		velo$recentNext = (slot + 1) & 3;
		velo$recentNames[slot] = name;
		velo$recentValues[slot] = value;
		velo$noteChanged(name);
	}

	// The map's value for this name; the recent cache always matches the map while the fast path is on.
	@Unique
	private GpuBufferSlice velo$currentUniform(String name) {
		for (int i = 0; i < velo$recentNames.length; i++) {
			if (velo$recentNames[i] == name) {
				return velo$recentValues[i];
			}
		}
		return uniforms.get(name);
	}

	@Unique
	private void velo$forgetRecent(String name) {
		for (int i = 0; i < velo$recentNames.length; i++) {
			if (velo$recentNames[i] != null && velo$recentNames[i].equals(name)) {
				velo$recentNames[i] = null;
			}
		}
	}

	@Unique
	private void velo$noteChanged(String name) {
		if (velo$needsFullPush) {
			return;
		}
		for (int i = 0; i < velo$changedCount; i++) {
			if (velo$changed[i].equals(name)) {
				return;
			}
		}
		if (velo$changedCount == VELO_MAX_TRACKED) {
			velo$needsFullPush = true;
			return;
		}
		velo$changed[velo$changedCount++] = name;
	}

	@Inject(method = "pushDescriptors", at = @At("HEAD"), cancellable = true)
	private void velo$pushChangedOnly(CallbackInfo ci) {
		if (!anyDescriptorDirty || pipeline == null) {
			return;
		}
		if (!PerformanceBoostModule.fastDrawPath || velo$needsFullPush || pipeline != velo$pushedPipeline
				|| velo$changedCount == 0 || VulkanRenderPass.VALIDATION || !velo$pushPartial()) {
			// Vanilla's full push runs next; it re-establishes every binding for this pipeline.
			velo$pushedPipeline = pipeline;
			velo$needsFullPush = !PerformanceBoostModule.fastDrawPath;
			velo$changedCount = 0;
			return;
		}
		anyDescriptorDirty = false;
		velo$changedCount = 0;
		ci.cancel();
	}

	// Pushes just the changed uniform buffers. False (nothing pushed) when any of them isn't a plain uniform buffer.
	@Unique
	private boolean velo$pushPartial() {
		List<VulkanBindGroupLayout.Entry> entries = pipeline.layout().entries();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(velo$changedCount, stack);
			int count = 0;
			for (int c = 0; c < velo$changedCount; c++) {
				String name = velo$changed[c];
				int binding = -1;
				for (int i = 0; i < entries.size(); i++) {
					if (entries.get(i).name().equals(name)) {
						binding = i;
						break;
					}
				}
				if (binding < 0) {
					continue; // not used by this pipeline
				}
				if (entries.get(binding).type() != VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER) {
					return false;
				}
				GpuBufferSlice slice = velo$currentUniform(name);
				if (slice == null) {
					return false; // let vanilla report the missing uniform
				}
				VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
				bufferInfo.buffer(((VulkanGpuBuffer) slice.buffer()).vkBuffer());
				bufferInfo.offset(slice.offset());
				bufferInfo.range(slice.length());
				VkWriteDescriptorSet write = writes.get(count++).sType$Default();
				write.dstBinding(binding);
				write.dstArrayElement(0);
				write.descriptorCount(1);
				write.descriptorType(6); // VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, as vanilla
				write.pBufferInfo(bufferInfo);
			}
			if (count > 0) {
				writes.limit(count);
				KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commandBuffer, 0, pipeline.pipelineLayout(), 0, writes);
			}
			return true;
		}
	}

	@Inject(method = "setVertexBuffer", at = @At("HEAD"), cancellable = true)
	private void velo$skipSameVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer, CallbackInfo ci) {
		if (slot < 0 || slot >= VELO_VERTEX_SLOTS) {
			return;
		}
		long handle = vertexBuffer != null ? ((VulkanGpuBuffer) vertexBuffer.buffer()).vkBuffer() : 0L;
		long offset = vertexBuffer != null ? vertexBuffer.offset() : 0L;
		if (PerformanceBoostModule.fastDrawPath && velo$vertexHandle[slot] == handle && velo$vertexOffset[slot] == offset) {
			ci.cancel();
			return;
		}
		velo$vertexHandle[slot] = handle;
		velo$vertexOffset[slot] = offset;
	}

	@Inject(method = "setIndexBuffer", at = @At("HEAD"), cancellable = true)
	private void velo$skipSameIndexBuffer(GpuBuffer indexBuffer, IndexType indexType, CallbackInfo ci) {
		long handle = ((VulkanGpuBuffer) indexBuffer).vkBuffer();
		if (PerformanceBoostModule.fastDrawPath && velo$indexHandle == handle && velo$indexType == indexType) {
			ci.cancel();
			return;
		}
		velo$indexHandle = handle;
		velo$indexType = indexType;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkSectionsToRender")
public abstract class VulkanDrawStateMixin {
}
//?}
