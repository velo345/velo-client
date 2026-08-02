package net.veloclient.velo.client.modules.debug;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Hotkeys to reload resource packs (and shaders, if Iris is present) without
 * leaving the game - resource-pack devs iterate constantly (design spec
 * section 6.4). The Iris reload is invoked reflectively so this module has no
 * hard compile-time dependency on Iris.
 */
public final class ResourceReloadHotkeyModule extends AbstractModule implements Configurable {

	public static final KeyBinding RELOAD_RESOURCES = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.reload_resources", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
	public static final KeyBinding RELOAD_SHADERS = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.reload_shaders", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	public ResourceReloadHotkeyModule() {
		super("resource-reload-hotkeys", "Resource/Shader Reload Hotkeys",
				"Hotkeys to reload resource packs and (if Iris is installed) shaders without restarting.",
				ModuleCategory.DEBUG, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			return;
		}
		while (RELOAD_RESOURCES.wasPressed()) {
			client.reloadResources();
		}
		while (RELOAD_SHADERS.wasPressed()) {
			reloadIrisShaders();
		}
	}

	private void reloadIrisShaders() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return;
		}
		try {
			Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object instance = irisApi.getMethod("getInstance").invoke(null);
			irisApi.getMethod("reload").invoke(instance);
		} catch (ReflectiveOperationException ignored) {
			// Iris present but the reflective API shape changed; fail silently rather than crash.
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Reload Resources Key", RELOAD_RESOURCES),
				KeybindConfig.field("Reload Shaders Key", RELOAD_SHADERS));
	}
}
