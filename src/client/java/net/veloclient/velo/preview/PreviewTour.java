package net.veloclient.velo.preview;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Screenshot-tour scenes for the preview features (ScreenshotTour "preview:<name>"): steps of {delayTicks, Runnable}. */
public final class PreviewTour {

	private static net.veloclient.velo.preview.guide.GuideScreen guide;

	private PreviewTour() {
	}

	private static Object[] step(int delay, Runnable run) {
		return new Object[] {delay, run};
	}

	private static Screen current() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		return client.currentScreen;
		//?} else if <26.2 {
		/*return client.screen;
		*///?} else {
		/*return client.gui.screen();
		*///?}
	}

	public static List<Object[]> scene(String name, Consumer<String> shot) {
		MinecraftClient client = MinecraftClient.getInstance();
		return switch (name) {
			case "guide" -> {
				List<Object[]> steps = new ArrayList<>();
				steps.add(step(0, () -> {
					guide = new net.veloclient.velo.preview.guide.GuideScreen(null);
					client.setScreen(guide);
				}));
				steps.add(step(40, () -> shot.accept("guide-0-welcome")));
				String[] names = {"look", "view", "style", "hud", "overlays", "combat", "clips", "pvp", "done"};
				for (int i = 1; i <= 9; i++) {
					int page = i;
					steps.add(step(2, () -> guide.goForTour(page, null)));
					steps.add(step(4, () -> shot.accept("guide-" + page + "-" + names[page - 1] + "-mid")));
					steps.add(step(26, () -> shot.accept("guide-" + page + "-" + names[page - 1])));
				}
				for (String hud : new String[] {"hunger", "hotbar", "xp-bar"}) {
					steps.add(step(2, () -> guide.goForTour(4, hud)));
					steps.add(step(30, () -> shot.accept("guide-4-hud-" + hud)));
				}
				steps.add(step(2, () -> client.setScreen(null)));
				yield steps;
			}
			case "rshift" -> {
				List<Object[]> steps = new ArrayList<>();
				steps.add(step(0, () -> client.setScreen(net.veloclient.velo.client.gui.ModMenuScreen.forView(false))));
				steps.add(step(30, () -> shot.accept("shell-modules")));
				String[] pages = {"skins", "friends", "clips", "fight_recap", "waypoints", "queue", "schematics", "settings", "profiles", "globe", "party"};
				String[] from = {"modules"};
				for (String page : pages) {
					steps.add(step(2, () -> {
						net.veloclient.velo.client.gui.window.MenuShell.navigateForTour(current(), from[0], page);
						from[0] = page;
					}));
					steps.add(step(3, () -> shot.accept("shell-" + page + "-mid")));
					steps.add(step(25, () -> shot.accept("shell-" + page)));
				}
				steps.add(step(2, () -> client.setScreen(null)));
				yield steps;
			}
			default -> List.of();
		};
	}
}
