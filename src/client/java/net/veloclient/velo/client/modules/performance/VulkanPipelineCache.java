package net.veloclient.velo.client.modules.performance;

//? if >=26.2 {
/*import net.minecraft.client.Minecraft;
import net.veloclient.velo.VeloClient;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
*///?}

/**
 * A persistent {@code VkPipelineCache} for vanilla's Vulkan renderer (26.2+), which otherwise
 * creates every pipeline with no cache, so the driver recompiles all of them on every launch and
 * resource reload. Loaded from {@link ShaderCache#pipelineCacheFile()} when the first pipeline is
 * created, saved a few seconds after the last new pipeline and when the device closes.
 *
 * <p>The file is wrapped in a small header of our own (magic, length, CRC32) so a truncated or
 * corrupted file is discarded instead of handed to the driver; the driver additionally checks its
 * own header and ignores data from a different GPU or driver version.
 */
public final class VulkanPipelineCache {

	//? if >=26.2 {
	/*private static final int MAGIC = 0x56504331; // "VPC1"
	private static final long SAVE_DELAY_SECONDS = 4;
	private static final ScheduledExecutorService SAVER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "velo-pipeline-cache");
		thread.setDaemon(true);
		return thread;
	});

	private static VkDevice device;
	private static long cache;
	private static boolean dirty;
	private static ScheduledFuture<?> pendingSave;

	private VulkanPipelineCache() {
	}

	// Replaces vanilla's {@code vkCreateGraphicsPipelines(device, 0L, ...)} - same call, plus our cache.
	public static synchronized int createGraphicsPipelines(VkDevice vkDevice, long ignoredCache, VkGraphicsPipelineCreateInfo.Buffer info,
			VkAllocationCallbacks allocator, LongBuffer pipelines) {
		long handle = ShaderCache.enabled ? handleFor(vkDevice) : 0L;
		long started = System.nanoTime();
		int result = VK12.vkCreateGraphicsPipelines(vkDevice, handle, info, allocator, pipelines);
		ShaderCache.recordPipeline(System.nanoTime() - started);
		if (handle != 0L) {
			dirty = true;
			scheduleSave();
		}
		return result;
	}

	private static long handleFor(VkDevice vkDevice) {
		if (device == vkDevice && cache != 0L) {
			return cache;
		}
		device = vkDevice;
		cache = createCache(vkDevice, readInitialData());
		if (cache == 0L) {
			// The driver didn't like our data (can't happen with a valid header, but be safe): start empty.
			cache = createCache(vkDevice, null);
		}
		return cache;
	}

	private static long createCache(VkDevice vkDevice, byte[] initialData) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineCacheCreateInfo createInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
			ByteBuffer data = null;
			if (initialData != null && initialData.length > 0) {
				data = MemoryUtil.memAlloc(initialData.length);
				data.put(initialData).flip();
				createInfo.pInitialData(data);
			}
			try {
				LongBuffer out = stack.mallocLong(1);
				int result = VK10.vkCreatePipelineCache(vkDevice, createInfo, null, out);
				if (result != VK10.VK_SUCCESS) {
					VeloClient.LOGGER.warn("Velo shader cache: vkCreatePipelineCache failed ({}), continuing without it", result);
					return 0L;
				}
				if (initialData != null) {
					VeloClient.LOGGER.info("Velo shader cache: loaded {} KB of Vulkan pipeline cache", initialData.length / 1024);
				}
				return out.get(0);
			} finally {
				if (data != null) {
					MemoryUtil.memFree(data);
				}
			}
		}
	}

	private static byte[] readInitialData() {
		try {
			var file = ShaderCache.pipelineCacheFile();
			if (!Files.exists(file)) {
				return null;
			}
			ByteBuffer raw = ByteBuffer.wrap(Files.readAllBytes(file));
			if (raw.remaining() < 16 || raw.getInt() != MAGIC) {
				return null;
			}
			int length = raw.getInt();
			long crc = raw.getLong();
			if (length < 0 || length != raw.remaining()) {
				return null;
			}
			byte[] payload = new byte[length];
			raw.get(payload);
			CRC32 check = new CRC32();
			check.update(payload);
			return check.getValue() == crc ? payload : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static void scheduleSave() {
		if (pendingSave != null) {
			pendingSave.cancel(false);
		}
		// Saved on the render thread (where pipelines are created), a moment after the last one.
		pendingSave = SAVER.schedule(() -> Minecraft.getInstance().execute(VulkanPipelineCache::saveNow), SAVE_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	public static synchronized void saveNow() {
		if (!dirty || device == null || cache == 0L) {
			return;
		}
		dirty = false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer size = stack.mallocPointer(1);
			if (VK10.vkGetPipelineCacheData(device, cache, size, null) != VK10.VK_SUCCESS || size.get(0) <= 0) {
				return;
			}
			int length = (int) Math.min(Integer.MAX_VALUE - 64, size.get(0));
			ByteBuffer data = MemoryUtil.memAlloc(length);
			try {
				size.put(0, length);
				if (VK10.vkGetPipelineCacheData(device, cache, size, data) != VK10.VK_SUCCESS) {
					return;
				}
				byte[] payload = new byte[(int) size.get(0)];
				data.get(0, payload);
				CRC32 crc = new CRC32();
				crc.update(payload);
				ByteBuffer out = ByteBuffer.allocate(16 + payload.length);
				out.putInt(MAGIC).putInt(payload.length).putLong(crc.getValue()).put(payload);
				ShaderCache.writeAtomically(ShaderCache.pipelineCacheFile(), out.array());
				VeloClient.LOGGER.info("Velo shader cache: saved {} KB of Vulkan pipeline cache. {}", payload.length / 1024, ShaderCache.summary());
			} finally {
				MemoryUtil.memFree(data);
			}
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Velo shader cache: saving the pipeline cache failed", e);
		}
	}

	// The device is about to be destroyed: save, then free our cache object while the device still exists.
	public static synchronized void onDeviceClosing(VkDevice closing) {
		if (closing != device || cache == 0L) {
			return;
		}
		if (pendingSave != null) {
			pendingSave.cancel(false);
		}
		dirty = true;
		saveNow();
		VK10.vkDestroyPipelineCache(device, cache, null);
		cache = 0L;
		device = null;
	}
	*///?}
}
