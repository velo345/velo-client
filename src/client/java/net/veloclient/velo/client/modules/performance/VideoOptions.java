package net.veloclient.velo.client.modules.performance;

/**
 * Version-bridged access to the newer vanilla video options {@link PerformanceBoostModule} tunes
 * that have no Stonecutter replace rule (Yarn {@code getAo()} vs Mojmap {@code
 * ambientOcclusion()} etc. - verified via javap on each version's real Options class). Kept in
 * one place so the module itself reads the same on every version.
 */
final class VideoOptions {

	private VideoOptions() {
	}

	//? if <26.1 {
	private static net.minecraft.client.option.GameOptions o() {
		return net.minecraft.client.MinecraftClient.getInstance().options;
	}

	static boolean smoothLighting() { return o().getAo().getValue(); }
	static void setSmoothLighting(boolean v) { o().getAo().setValue(v); }
	static boolean fancyLeaves() { return o().getCutoutLeaves().getValue(); }
	static void setFancyLeaves(boolean v) { o().getCutoutLeaves().setValue(v); }
	static boolean improvedTransparency() { return o().getImprovedTransparency().getValue(); }
	static void setImprovedTransparency(boolean v) { o().getImprovedTransparency().setValue(v); }
	static double chunkFade() { return o().getChunkFade().getValue(); }
	static void setChunkFade(double v) { o().getChunkFade().setValue(v); }
	static void setMenuBlur(int v) { o().getMenuBackgroundBlurriness().setValue(v); }
	static void setWeatherRadius(int v) { o().getWeatherRadius().setValue(v); }
	static void setCloudRange(int v) { o().getCloudRenderDistance().setValue(v); }
	static void setVignette(boolean v) { o().getVignette().setValue(v); }
	static void setTextureFilteringOff() { o().getTextureFiltering().setValue(net.minecraft.client.option.TextureFilteringMode.NONE); }
	static int currentFps() { return net.minecraft.client.MinecraftClient.getInstance().getCurrentFps(); }
	//?} else {
	/*private static net.minecraft.client.Options o() {
		return net.minecraft.client.Minecraft.getInstance().options;
	}

	static boolean smoothLighting() { return o().ambientOcclusion().get(); }
	static void setSmoothLighting(boolean v) { o().ambientOcclusion().set(v); }
	static boolean fancyLeaves() { return o().cutoutLeaves().get(); }
	static void setFancyLeaves(boolean v) { o().cutoutLeaves().set(v); }
	static boolean improvedTransparency() { return o().improvedTransparency().get(); }
	static void setImprovedTransparency(boolean v) { o().improvedTransparency().set(v); }
	static double chunkFade() { return o().chunkSectionFadeInTime().get(); }
	static void setChunkFade(double v) { o().chunkSectionFadeInTime().set(v); }
	static void setMenuBlur(int v) { o().menuBackgroundBlurriness().set(v); }
	static void setWeatherRadius(int v) { o().weatherRadius().set(v); }
	static void setCloudRange(int v) { o().cloudRange().set(v); }
	static void setVignette(boolean v) { o().vignette().set(v); }
	static void setTextureFilteringOff() { o().textureFiltering().set(net.minecraft.client.TextureFilteringMethod.NONE); }
	static int currentFps() { return net.minecraft.client.Minecraft.getInstance().getFps(); }
	*///?}

	/** Only 26.2 has a selectable graphics API (OpenGL / Vulkan); elsewhere this reports "Default" and ignores changes. */
	static String graphicsBackend() {
		//? if <26.2 {
		return "Default";
		//?} else {
		/*return switch (o().preferredGraphicsBackend().get()) {
			case OPENGL -> "OpenGL";
			case VULKAN -> "Vulkan";
			default -> "Default";
		};
		*///?}
	}

	static void setGraphicsBackend(String label) {
		//? if >=26.2 {
		/*o().preferredGraphicsBackend().set(switch (label) {
			case "OpenGL" -> net.minecraft.client.PreferredGraphicsApi.OPENGL;
			case "Vulkan" -> net.minecraft.client.PreferredGraphicsApi.VULKAN;
			default -> net.minecraft.client.PreferredGraphicsApi.DEFAULT;
		});
		o().save();
		*///?}
	}

	static boolean hasGraphicsBackendChoice() {
		//? if <26.2 {
		return false;
		//?} else {
		/*return true;
		*///?}
	}
}
