package net.veloclient.velo.client.devtools;

/**
 * Dev-only GPU time per render pass ({@code -Dvelo.gpuPassProfile=true}, 26.2 Vulkan): a GPU timestamp is written at
 * every pass / transfer boundary the encoder records (see VulkanPassBarrierMixin), so the time between two marks is
 * what the GPU spent on the work recorded in between. Read back a few frames later from a ring of query pools; logs
 * the average per pass every 600 frames. Ticks are taken as nanoseconds (NVIDIA's timestamp period is 1).
 */
//? if >=26.2 && <26.3 {
/*public final class GpuPassTimer {

	public static final boolean ENABLED = Boolean.getBoolean("velo.gpuPassProfile");
	private static final int RING = 6;
	private static final int SLOTS = 256;
	private static final String CB_END = "(command buffer end)";
	private static long[] pools;
	private static org.lwjgl.vulkan.VkDevice vk;
	private static final String[][] LABELS = new String[RING][SLOTS];
	private static final int[] USED = new int[RING];
	private static int current;
	private static final java.util.Map<String, long[]> TOTAL = new java.util.HashMap<>();
	private static long frames;
	private static long gpuNanos;

	private GpuPassTimer() {
	}

	private static org.lwjgl.vulkan.VkDevice lastDevice;
	private static org.lwjgl.vulkan.VkCommandBuffer lastCommandBuffer;

	// A mark inside the pass that was begun last (GUI draws): same command buffer as that pass.
	public static void markInPass(String label) {
		if (ENABLED && lastCommandBuffer != null) {
			mark(lastDevice, lastCommandBuffer, label);
		}
	}

	public static void mark(org.lwjgl.vulkan.VkDevice device, org.lwjgl.vulkan.VkCommandBuffer commandBuffer, String label) {
		if (!ENABLED || commandBuffer == null) {
			return;
		}
		lastDevice = device;
		lastCommandBuffer = commandBuffer;
		if (pools == null) {
			create(device);
		}
		int slot = USED[current];
		if (slot >= SLOTS) {
			return;
		}
		org.lwjgl.vulkan.VK12.vkResetQueryPool(vk, pools[current], slot, 1);
		org.lwjgl.vulkan.KHRSynchronization2.vkCmdWriteTimestamp2KHR(commandBuffer, org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
				pools[current], slot);
		LABELS[current][slot] = label;
		USED[current] = slot + 1;
	}

	public static void endCommandBuffer(org.lwjgl.vulkan.VkDevice device, org.lwjgl.vulkan.VkCommandBuffer commandBuffer) {
		mark(device, commandBuffer, CB_END);
	}

	private static void create(org.lwjgl.vulkan.VkDevice device) {
		vk = device;
		pools = new long[RING];
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			var info = org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(stack).sType$Default()
					.queryType(org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS);
			java.nio.LongBuffer out = stack.callocLong(1);
			for (int i = 0; i < RING; i++) {
				org.lwjgl.vulkan.VK10.vkCreateQueryPool(device, info, null, out);
				pools[i] = out.get(0);
				org.lwjgl.vulkan.VK12.vkResetQueryPool(device, pools[i], 0, SLOTS);
			}
		}
	}

	// Frame boundary (render thread, outside the encoder): moves on to the oldest pool, reading it first.
	public static void endFrame() {
		if (!ENABLED || pools == null) {
			return;
		}
		current = (current + 1) % RING;
		collect(current);
		USED[current] = 0;
		if (++frames % 600 == 0) {
			log();
		}
	}

	private static void collect(int pool) {
		int n = USED[pool];
		if (n < 2) {
			return;
		}
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			java.nio.LongBuffer values = stack.callocLong(n * 2);
			int result = org.lwjgl.vulkan.VK10.vkGetQueryPoolResults(vk, pools[pool], 0, n, values, 16,
					org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT | org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
			if (result < 0) {
				return;
			}
			for (int i = 0; i + 1 < n; i++) {
				String label = LABELS[pool][i];
				if (CB_END.equals(label) || values.get(i * 2 + 1) == 0 || values.get(i * 2 + 3) == 0) {
					continue;
				}
				long nanos = values.get(i * 2 + 2) - values.get(i * 2);
				if (nanos < 0 || nanos > 50_000_000L) {
					continue;
				}
				long[] total = TOTAL.computeIfAbsent(normalize(label), k -> new long[2]);
				total[0] += nanos;
				total[1]++;
				gpuNanos += nanos;
			}
		}
	}

	private static String normalize(String label) {
		return label.replace("+merge", "").replace("+close", "");
	}

	private static void log() {
		StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "Velo GPU passes (avg us/frame, %.0f us total):", gpuNanos / 600e3));
		TOTAL.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(40)
				.forEach(e -> sb.append(String.format(java.util.Locale.ROOT, "%n  %7.1f us  %5.1f x  %s", e.getValue()[0] / 600e3,
						e.getValue()[1] / 600.0, e.getKey())));
		net.veloclient.velo.VeloClient.LOGGER.info(sb.toString());
		TOTAL.clear();
		gpuNanos = 0;
	}
}
*///?} else {
public final class GpuPassTimer {

	public static final boolean ENABLED = false;

	private GpuPassTimer() {
	}

	public static void endFrame() {
	}

	public static void markInPass(String label) {
	}
}
//?}
