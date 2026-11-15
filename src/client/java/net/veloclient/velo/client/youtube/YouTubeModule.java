package net.veloclient.velo.client.youtube;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * YouTube: a floating video window in the game. U (changeable) opens it: search or paste a link, drag the window by its
 * title bar, resize it from the corner, minimize it to a pill, maximize it or go fullscreen (F / Escape). Close the screen
 * and the video keeps playing as picture-in-picture over the HUD.
 */
public final class YouTubeModule extends AbstractModule implements Configurable, net.veloclient.velo.client.gui.window.ModuleShortcut {

	public static final KeyBinding OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.youtube", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.U, VeloKeybinds.CATEGORY));
	public static final KeyBinding NEXT_SHORT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.next_short", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.DOWN, VeloKeybinds.CATEGORY));
	public static final KeyBinding PREVIOUS_SHORT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.previous_short", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UP, VeloKeybinds.CATEGORY));

	private static final List<String> QUALITY = List.of("360p", "480p", "720p", "1080p");
	private static YouTubeModule instance;
	private boolean pip = true;
	private static long saveAt;

	public YouTubeModule() {
		super("youtube", "Video Player", "Watch YouTube, Shorts, Twitch, any video link or your own video files in a window over the game - movable, resizable, fullscreen, picture-in-picture.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			while (OPEN_KEY.wasPressed()) {
				// swallowed while off
			}
			while (NEXT_SHORT.wasPressed()) {
				// swallowed while off
			}
			while (PREVIOUS_SHORT.wasPressed()) {
				// swallowed while off
			}
			return;
		}
		// Shorts keys while playing in picture-in-picture (inside the window the arrow keys and wheel do it)
		while (NEXT_SHORT.wasPressed()) {
			YouTubeOverlay.flick(1);
		}
		while (PREVIOUS_SHORT.wasPressed()) {
			YouTubeOverlay.flick(-1);
		}
		while (OPEN_KEY.wasPressed()) {
			if (ClientCompat.currentScreen() == null) {
				client.setScreen(new YouTubeScreen());
			}
		}
		if (saveAt != 0 && System.currentTimeMillis() >= saveAt) {
			saveAt = 0;
			net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		}
	}

	/** The window moved or changed size: saved with the profile a moment later. */
	static void saveSoon() {
		saveAt = System.currentTimeMillis() + 1500;
	}

	/** Picture-in-picture over the HUD while the YouTube screen is closed. */
	public static void renderHud(DrawContext context, int screenWidth, int screenHeight) {
		YouTubeModule m = instance;
		if (m == null || !m.isEnabled() || !m.pip || !YouTubeOverlay.active() || ClientCompat.currentScreen() instanceof YouTubeScreen) {
			return;
		}
		YouTubeOverlay.render(context, screenWidth, screenHeight, -1, -1, false);
	}

	@Override
	public void onDisable() {
		YouTubeOverlay.stop();
	}

	@Override
	public String shortcutLabel() {
		return "Open YouTube";
	}

	@Override
	public void openShortcut(net.minecraft.client.gui.screen.Screen parent) {
		MinecraftClient.getInstance().setScreen(new YouTubeScreen());
	}

	@Override
	public String extraState() {
		return YouTubeOverlay.save();
	}

	@Override
	public void restoreExtraState(String state) {
		YouTubeOverlay.load(state);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Open Window (search, move, resize)", OPEN_KEY),
				KeybindConfig.field("Next Short", NEXT_SHORT),
				KeybindConfig.field("Previous Short", PREVIOUS_SHORT),
				new ConfigField.ChoiceField("Quality", QUALITY, () -> YouTubeOverlay.quality + "p",
						v -> YouTubeOverlay.quality = Integer.parseInt(v.replace("p", ""))),
				new ConfigField.ToggleField("Keep Playing When Closed (Picture-in-Picture)", () -> pip, v -> {
					pip = v;
					if (!v && ClientCompat.currentScreen() == null) {
						YouTubeOverlay.stop();
					}
				}),
				new ConfigField.SliderField("Picture-in-Picture Opacity", 0.3, 1.0, () -> YouTubeOverlay.pipOpacity,
						v -> YouTubeOverlay.pipOpacity = (float) (Math.round(v * 100) / 100.0), v -> Math.round(v * 100) + "%"),
				new ConfigField.ActionButtonField("Open YouTube", () -> MinecraftClient.getInstance().setScreen(new YouTubeScreen())));
	}
}
