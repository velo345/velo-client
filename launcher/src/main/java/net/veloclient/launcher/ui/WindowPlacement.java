package net.veloclient.launcher.ui;

import com.google.gson.Gson;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import net.veloclient.launcher.data.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the launcher window opens. JavaFX centers new windows on what it considers the primary
 * screen - on multi-monitor setups (notably KDE) that's often not the monitor you use, so the
 * launcher kept appearing off to the side. Now: the last position/size is remembered and
 * restored if that spot is still on a connected monitor; otherwise (first start, monitor
 * unplugged) it opens centered on the monitor the mouse is on.
 */
public final class WindowPlacement {

	private static final Gson GSON = new Gson();

	private record Saved(double x, double y, double width, double height, boolean maximized) {
	}

	private WindowPlacement() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("launcher-window.json");
	}

	/** Call before {@code stage.show()}. */
	public static void restore(Stage stage, double defaultWidth, double defaultHeight) {
		Saved saved = read();
		if (saved != null && mostlyVisible(saved)) {
			stage.setX(saved.x());
			stage.setY(saved.y());
			stage.setWidth(saved.width());
			stage.setHeight(saved.height());
			stage.setMaximized(saved.maximized());
		} else {
			Rectangle2D bounds = screenUnderMouse().getVisualBounds();
			double width = Math.min(defaultWidth, bounds.getWidth() - 40);
			double height = Math.min(defaultHeight, bounds.getHeight() - 40);
			stage.setWidth(width);
			stage.setHeight(height);
			stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2);
			stage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2);
		}
		// Some window managers place the window themselves on first map - put it back once it's up.
		double x = stage.getX();
		double y = stage.getY();
		stage.setOnShown(e -> Platform.runLater(() -> {
			if (!stage.isMaximized() && (Math.abs(stage.getX() - x) > 2 || Math.abs(stage.getY() - y) > 2)) {
				stage.setX(x);
				stage.setY(y);
			}
		}));
	}

	/** Call when the launcher closes. */
	public static void save(Stage stage) {
		try {
			Saved saved = new Saved(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), stage.isMaximized());
			Files.writeString(file(), GSON.toJson(saved), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Next start just centers again.
		}
	}

	private static Saved read() {
		try {
			return Files.exists(file()) ? GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Saved.class) : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean mostlyVisible(Saved saved) {
		if (saved.width() < 300 || saved.height() < 200) {
			return false;
		}
		Rectangle2D window = new Rectangle2D(saved.x(), saved.y(), saved.width(), saved.height());
		double visible = 0;
		for (Screen screen : Screen.getScreens()) {
			Rectangle2D b = screen.getVisualBounds();
			double w = Math.min(window.getMaxX(), b.getMaxX()) - Math.max(window.getMinX(), b.getMinX());
			double h = Math.min(window.getMaxY(), b.getMaxY()) - Math.max(window.getMinY(), b.getMinY());
			if (w > 0 && h > 0) {
				visible += w * h;
			}
		}
		return visible >= 0.6 * saved.width() * saved.height();
	}

	private static Screen screenUnderMouse() {
		try {
			javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
			double mx = robot.getMouseX();
			double my = robot.getMouseY();
			var screens = Screen.getScreensForRectangle(mx, my, 1, 1);
			if (!screens.isEmpty()) {
				return screens.get(0);
			}
		} catch (Exception ignored) {
			// Fall back to the primary screen.
		}
		return Screen.getPrimary();
	}
}
