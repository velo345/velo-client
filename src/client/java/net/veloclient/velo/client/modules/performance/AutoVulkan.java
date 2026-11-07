package net.veloclient.velo.client.modules.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Moves players with a dedicated graphics card from OpenGL to Vulkan once (26.2+, ~30% more FPS on our bench).
 *
 * Minecraft's "Default" backend tries OpenGL first, so almost nobody runs Vulkan unless they find the option. This only
 * touches profiles still on "Default" (an explicit OpenGL or Vulkan choice is never changed), never with Iris or another
 * OpenGL-only mod installed, never on macOS, and only for GPUs whose Vulkan drivers are solid (GeForce, Radeon RX/Pro,
 * Arc A/B discrete). The switch is written when the game is up and takes effect next start.
 *
 * A marker in config/velo-client/auto-vulkan.txt makes it one-shot and safe:
 * - "pending": switched, the next start tries Vulkan; at init it becomes "trying".
 * - "trying" still there at the next init: that Vulkan start never came up (crash/hang) - back to Default, "failed".
 * - started on something other than Vulkan (the game fell back): back to Default, "failed".
 * - started on Vulkan: "ok". "failed"/"ok"/"declined" are final.
 */
public final class AutoVulkan {

	private static final String KEY = "preferredGraphicsBackend:";
	/** Mods that need OpenGL on 26.2+ - with any of these the profile stays where it is. */
	private static final List<String> GL_ONLY_MODS = List.of("iris", "oculus", "optifabric", "canvas", "immersive_portals");
	private static final Pattern NVIDIA = Pattern.compile("geforce|rtx|gtx|quadro|tesla|nvidia (a|l|t)\\d");
	// "Radeon RX Vega 8/11" are integrated: only the real Vega cards count.
	private static final Pattern AMD = Pattern.compile("radeon(\\(tm\\))? (rx(?! ?vega)|pro|vii)|radeon r9|vega (56|64|frontier)");
	private static final Pattern INTEL_ARC = Pattern.compile("arc(\\(tm\\))? (pro )?[ab]\\d{2,3}");

	private AutoVulkan() {
	}

	private static Path marker() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("velo-client").resolve("auto-vulkan.txt");
	}

	private static String state() {
		try {
			return Files.readString(marker(), StandardCharsets.UTF_8).strip();
		} catch (IOException e) {
			return "";
		}
	}

	private static void setState(String value) {
		try {
			Files.createDirectories(marker().getParent());
			Files.writeString(marker(), value, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Worst case it asks again next start.
		}
	}

	/** True when this GPU name is a dedicated card with a good Vulkan driver. */
	static boolean goodVulkanGpu(String name) {
		if (name == null) {
			return false;
		}
		String n = name.toLowerCase(Locale.ROOT);
		if (n.contains("llvmpipe") || n.contains("software") || n.contains("microsoft basic") || n.contains("swiftshader")) {
			return false;
		}
		return NVIDIA.matcher(n).find() || AMD.matcher(n).find() || INTEL_ARC.matcher(n).find();
	}

	private static boolean glOnlyModPresent() {
		var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
		return GL_ONLY_MODS.stream().anyMatch(loader::isModLoaded);
	}

	/** Called from client init, before Minecraft reads options.txt. */
	public static void beforeOptionsLoad() {
		//? if >=26.2 {
		/*String s = state();
		// Iris (or another OpenGL-only mod) installed after we switched: undo our switch before the game reads the file.
		if ((s.equals("pending") || s.equals("trying") || s.equals("ok")) && glOnlyModPresent()) {
			writeOptionsBackend("default");
			setState("declined");
			return;
		}
		if (s.equals("pending")) {
			setState("trying");
		} else if (s.equals("trying")) {
			// The Vulkan start we switched to never reached the menu: undo it before the game reads the file.
			writeOptionsBackend("default");
			setState("failed");
		}
		*///?}
	}

	/** Called once the game is up (render device exists). */
	public static void onStarted() {
		//? if >=26.2 {
		/*try {
			var device = com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
			if (device == null) {
				return;
			}
			var info = device.getDeviceInfo();
			boolean onVulkan = "vulkan".equalsIgnoreCase(info.backendName());
			var options = net.minecraft.client.Minecraft.getInstance().options;
			String s = state();
			if (s.equals("trying")) {
				if (onVulkan) {
					setState("ok");
				} else {
					options.preferredGraphicsBackend().set(net.minecraft.client.PreferredGraphicsApi.DEFAULT);
					options.save();
					setState("failed");
				}
				return;
			}
			if (!s.isEmpty()) {
				return;
			}
			if (onVulkan || options.preferredGraphicsBackend().get() != net.minecraft.client.PreferredGraphicsApi.DEFAULT) {
				setState("declined");
				return;
			}
			if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") || glOnlyModPresent()
					|| Boolean.getBoolean("velo.noAutoVulkan") || !goodVulkanGpu(info.name())) {
				return;
			}
			options.preferredGraphicsBackend().set(net.minecraft.client.PreferredGraphicsApi.VULKAN);
			options.save();
			setState("pending");
			org.slf4j.LoggerFactory.getLogger("velo-client").info("Auto Vulkan: {} - Vulkan from the next start", info.name());
			net.veloclient.velo.client.social.NotificationOverlay.show(new net.veloclient.velo.client.social.NotificationOverlay.Toast(null, null,
					"Vulkan turned on", "Your graphics card runs Vulkan well - Velo starts with it next time for more FPS. "
							+ "You can switch back in Video settings.", 9000, null, null, null));
		} catch (Throwable t) {
			org.slf4j.LoggerFactory.getLogger("velo-client").warn("Auto Vulkan check failed", t);
		}
		*///?}
	}

	/** Rewrites the backend line in options.txt directly (the game hasn't loaded it yet). */
	private static void writeOptionsBackend(String value) {
		Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("options.txt");
		try {
			if (!Files.exists(file)) {
				return;
			}
			List<String> lines = new java.util.ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).startsWith(KEY)) {
					lines.set(i, KEY + "\"" + value + "\"");
				}
			}
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Left as is - the game's own fallback still applies.
		}
	}
}
