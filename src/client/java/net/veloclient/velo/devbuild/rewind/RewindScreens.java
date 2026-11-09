package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind's buttons: "Velo Rewind" on the title screen (the library), and Start / Pause / Resume / Stop Recording in the
 * pause menu. Plain buttons in the centre column - Velo's menus pick them up as their own styled rows (the recording ones
 * with record / pause / stop icons, see TitleScreenTheme).
 */
//? if <26.2 {
public final class RewindScreens {

	private RewindScreens() {
	}

	public static void register() {
	}
}
//?} else {
/*import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public final class RewindScreens {

	private RewindScreens() {
	}

	public static void register() {
		net.veloclient.velo.client.gui.title.PauseMenu.recordingStatus = () -> RewindRecorder.active() ? RewindRecorder.status() : null;
		RewindEditorScreen.registerHud();
		RewindApply.registerGizmo();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(client -> RewindEditorScreen.viewportSelect());
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof TitleScreen && RewindModule.recovered > 0) {
				int n = RewindModule.recovered;
				RewindModule.recovered = 0;
				net.minecraft.client.gui.components.toasts.SystemToast.add(client.gui.toastManager(),
						net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal("Velo Rewind"),
						Component.literal(n == 1 ? "Recovered a recording the last crash interrupted" : "Recovered " + n + " recordings a crash interrupted"));
			}
			if (!RewindModule.available()) {
				return;
			}
			int x = w / 2 - 100;
			if (screen instanceof TitleScreen) {
				Screens.getWidgets(screen).add(Button.builder(Component.literal("Velo Rewind - Open Replays"),
						b -> client.setScreen(new RewindLibraryScreen(screen))).bounds(x, h / 4 + 48 + 24 * 5, 200, 20).build());
			} else if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				int y = h / 4 + 8 + 24 * 6;
				if (!RewindRecorder.active()) {
					Screens.getWidgets(screen).add(Button.builder(Component.literal("Start Recording"), b -> {
						client.setScreen(null);
						RewindRecorder.toggle();
					}).bounds(x, y, 200, 20).build());
				} else {
					String label = RewindRecorder.paused() ? "Resume Recording" : "Pause Recording";
					Screens.getWidgets(screen).add(Button.builder(Component.literal(label), b -> {
						RewindRecorder.togglePause();
						client.setScreen(null);
					}).bounds(x, y, 200, 20).build());
					Screens.getWidgets(screen).add(Button.builder(Component.literal("Stop Recording"), b -> {
						client.setScreen(null);
						RewindRecorder.toggle();
					}).bounds(x, y + 24, 200, 20).build());
				}
			}
		});
	}
}
*///?}
