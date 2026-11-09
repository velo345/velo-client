package net.veloclient.velo.devbuild.colors;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Color Codes: a key opens the colour code generator (pick a colour, copy it as hex / &#hex / §x / MiniMessage / gradient);
 * a second key takes the colour of the block you look at straight into it.
 */
public final class ColorCodesModule extends AbstractModule implements Configurable {

	public static final KeyBinding OPEN = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.color_codes", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.F4, VeloKeybinds.CATEGORY));
	public static final KeyBinding PICK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.color_pick_block", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	public ColorCodesModule() {
		super("color-codes", "Color Codes", "Pick a colour and copy it for chat, signs and plugins: hex, &#hex, MiniMessage, gradients. F4 opens it.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean on = isEnabled();
			while (OPEN.wasPressed()) {
				if (on && BlockColor.noScreen()) {
					client.setScreen(new ColorCodesScreen());
				}
			}
			testTick();
			while (PICK.wasPressed()) {
				int c = BlockColor.underCrosshair();
				if (on && BlockColor.noScreen() && c >= 0) {
					client.setScreen(ColorCodesScreen.pickingBlock(c));
				}
			}
		});
	}

	private static int test;

	// -Dvelo.colorTest: open Color Codes, then the inventory (Numbered Inventories), screenshot both, quit (26.2 only).
	private static void testTick() {
		//? if >=26.2 {
		/*if (!Boolean.getBoolean("velo.colorTest")) {
			return;
		}
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		test++;
		net.veloclient.velo.module.ModuleRegistry.get("numbered-inventories").ifPresent(m -> m.setEnabled(true));
		if (test == 60) {
			mc.setScreen(new ColorCodesScreen());
		} else if (test == 100) {
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, "colorcodes.png", mc.gameRenderer.mainRenderTarget(), 1, m -> { });
		} else if (test == 110) {
			mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
		} else if (test == 150) {
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, "numbered.png", mc.gameRenderer.mainRenderTarget(), 1, m -> { });
		} else if (test == 160) {
			mc.stop();
		}
		*///?}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.ActionButtonField("Open Color Codes", () ->
				net.minecraft.client.MinecraftClient.getInstance().setScreen(new ColorCodesScreen())));
	}
}
