package net.veloclient.velo.client.util;

/**
 * The graphics card and rendering backend, from Minecraft's own render device - never direct
 * OpenGL calls (26.2 can run on Vulkan, where a GL call has no context and aborts the game).
 */
public final class GpuInfo {

	private static String cached;

	private GpuInfo() {
	}

	/** e.g. "NVIDIA GeForce RTX 3070 (Vulkan, 580.95)", or null when unknown. */
	public static String describe() {
		if (cached != null) {
			return cached.isEmpty() ? null : cached;
		}
		try {
			var device = com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
			if (device == null) {
				return null;
			}
			//? if <26.2 {
			cached = device.getRenderer() + " (" + device.getBackendName() + " " + device.getVersion() + ")";
			//?} else {
			/*var info = device.getDeviceInfo();
			cached = info.name() + " (" + info.backendName() + ", " + info.driverInfo() + ")";
			*///?}
		} catch (Throwable t) {
			cached = "";
		}
		return cached == null || cached.isEmpty() ? null : cached;
	}
}
