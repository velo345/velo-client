package net.veloclient.velo.client.browser;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
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
 * Velo Browser: a real browser in a window over the game - YouTube, TikTok, Twitch, Instagram, Spotify and any other
 * site, with a start page of shortcuts. It keeps playing when the window closes (picture-in-picture or hidden, audio
 * only). Private by design: nothing is saved (see {@link CefRuntime}). Replaces the old Video Player.
 */
public final class BrowserModule extends AbstractModule implements Configurable, net.veloclient.velo.client.gui.window.ModuleShortcut {

	public static final KeyBinding OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.browser", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.U, VeloKeybinds.CATEGORY));
	public static final KeyBinding HIDE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.browser_hide", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	private static final List<String> AWAY = List.of("Small Window", "Keep Playing Hidden", "Close It");
	private static BrowserModule instance;
	private static long saveAt;

	public BrowserModule() {
		super("youtube", "Browser", "A real browser over the game: YouTube, TikTok, Twitch, Instagram, Spotify and any site. "
				+ "Keeps playing in a small window or hidden. Private - nothing is saved.", ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> CefRuntime.shutdown());
	}

	private void onTick(MinecraftClient client) {
		CefRuntime.pump();
		if (!isEnabled()) {
			while (OPEN_KEY.wasPressed()) {
				// swallowed while off
			}
			while (HIDE_KEY.wasPressed()) {
				// swallowed while off
			}
			return;
		}
		while (OPEN_KEY.wasPressed()) {
			if (ClientCompat.currentScreen() == null) {
				client.setScreen(new BrowserScreen());
			}
		}
		while (HIDE_KEY.wasPressed()) {
			if (ClientCompat.currentScreen() instanceof BrowserScreen) {
				BrowserWindow.hidden = true;
				client.setScreen(null);
			} else if (BrowserWindow.active()) {
				BrowserWindow.hidden = !BrowserWindow.hidden;
			}
		}
		if (saveAt != 0 && System.currentTimeMillis() >= saveAt) {
			saveAt = 0;
			net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		}
	}

	static void saveSoon() {
		saveAt = System.currentTimeMillis() + 1500;
	}

	static String keyName() {
		return OPEN_KEY.isUnbound() ? "The Browser key" : net.veloclient.velo.client.keybind.BindingAccess.keyText(OPEN_KEY);
	}

	static void toast(String text) {
		net.veloclient.velo.client.social.NotificationOverlay.show(new net.veloclient.velo.client.social.NotificationOverlay.Toast(null, null,
				"Velo Browser", text, 3500, null, null, null));
	}

	/** Picture-in-picture over the HUD while the browser window is closed. */
	public static void renderHud(DrawContext context, int screenWidth, int screenHeight) {
		BrowserModule m = instance;
		if (m == null || !m.isEnabled() || ClientCompat.currentScreen() instanceof BrowserScreen) {
			CefRuntime.pump();
			return;
		}
		BrowserWindow.renderPip(context, screenWidth, screenHeight);
	}

	@Override
	public void onDisable() {
		BrowserWindow.closePage();
	}

	@Override
	public String shortcutLabel() {
		return "Open Browser";
	}

	@Override
	public void openShortcut(net.minecraft.client.gui.screen.Screen parent) {
		MinecraftClient.getInstance().setScreen(new BrowserScreen());
	}

	@Override
	public String extraState() {
		return BrowserWindow.save();
	}

	@Override
	public void restoreExtraState(String state) {
		if (state != null && state.startsWith("{") && state.contains("\"shortcuts\"")) {
			BrowserWindow.load(state);
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Open Browser", OPEN_KEY),
				KeybindConfig.field("Hide / Show (keeps playing)", HIDE_KEY),
				new ConfigField.ChoiceField("When You Close the Window", AWAY, () -> switch (BrowserWindow.away) {
					case HIDDEN -> AWAY.get(1);
					case CLOSE -> AWAY.get(2);
					default -> AWAY.get(0);
				}, v -> BrowserWindow.away = v.equals(AWAY.get(1)) ? BrowserWindow.Away.HIDDEN : v.equals(AWAY.get(2)) ? BrowserWindow.Away.CLOSE
						: BrowserWindow.Away.PIP),
				// normal browsing keeps sign-ins and history on this PC only (incognito: the hat button in the bar keeps nothing)
				new ConfigField.ActionButtonField("Clear Browsing Data (sign-ins, history, cache)", () -> {
					CefRuntime.clearDataOnNextStart();
					toast("Browsing data is cleared the next time the game starts.");
				}),
				new ConfigField.SliderField("Small Window Opacity", 0.3, 1.0, () -> BrowserWindow.pipOpacity,
						v -> BrowserWindow.pipOpacity = (float) (Math.round(v * 100) / 100.0), v -> Math.round(v * 100) + "%"),
				new ConfigField.ChoiceField("Start Page Background", StartPage.BACKGROUNDS, () -> BrowserWindow.background, v -> {
					BrowserWindow.background = v;
					if (v.equals("Picture")) {
						// the system file picker blocks: off the game thread
						Thread t = new Thread(() -> {
							try {
								java.nio.file.Path file = net.veloclient.velo.client.gui.NativeFileDialog.pickFile("Start page picture", "Pictures", "png",
										"jpg", "jpeg", "gif", "webp");
								if (file != null) {
									BrowserWindow.picture = file;
									saveSoon();
								}
							} catch (Exception ignored) {
								// nothing picked
							}
						}, "velo-browser-picture");
						t.setDaemon(true);
						t.start();
					}
				}),
				new ConfigField.ActionButtonField("Open Browser", () -> MinecraftClient.getInstance().setScreen(new BrowserScreen())),
				new ConfigField.ActionButtonField("Close the Page (forget this session)", BrowserWindow::closePage));
	}
}
