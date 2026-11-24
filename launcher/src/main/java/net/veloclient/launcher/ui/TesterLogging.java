package net.veloclient.launcher.ui;

import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import net.veloclient.launcher.LauncherLog;

/**
 * Tester launcher only: every click (what was clicked, on which window), key shortcut and window that opens or closes
 * goes to launcher.log - so a tester's report shows exactly what they did before something broke. Typed text is never
 * logged, only which field got focus.
 */
public final class TesterLogging {

	private static boolean installed;

	private TesterLogging() {
	}

	public static void install() {
		if (installed) {
			return;
		}
		installed = true;
		LauncherLog.info("[tester] detailed action logging on");
		for (Window w : Window.getWindows()) {
			watch(w);
		}
		Window.getWindows().addListener((javafx.collections.ListChangeListener<Window>) change -> {
			while (change.next()) {
				for (Window w : change.getAddedSubList()) {
					watch(w);
					LauncherLog.info("[ui] window opened: " + describeWindow(w));
				}
				for (Window w : change.getRemoved()) {
					LauncherLog.info("[ui] window closed: " + describeWindow(w));
				}
			}
		});
	}

	private static void watch(Window w) {
		if (w.getProperties().containsKey("velo.testerLogging")) {
			return;
		}
		w.getProperties().put("velo.testerLogging", Boolean.TRUE);
		if (w.getScene() != null) {
			watch(w.getScene(), w);
		}
		w.sceneProperty().addListener((o, a, scene) -> {
			if (scene != null) {
				watch(scene, w);
			}
		});
	}

	private static void watch(Scene scene, Window w) {
		if (scene.getProperties().containsKey("velo.testerLogging")) {
			return;
		}
		scene.getProperties().put("velo.testerLogging", Boolean.TRUE);
		scene.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> LauncherLog.info("[click] " + describe(e.getTarget()) + " (" + e.getButton()
				+ (e.getClickCount() > 1 ? " x" + e.getClickCount() : "") + ", " + describeWindow(w) + ")"));
		scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
			// shortcuts and navigation keys only - never what someone types
			if (e.isShortcutDown() || e.isAltDown() || e.getCode().isFunctionKey() || e.getCode() == javafx.scene.input.KeyCode.ESCAPE
					|| e.getCode() == javafx.scene.input.KeyCode.ENTER && !(e.getTarget() instanceof TextInputControl)) {
				LauncherLog.info("[key] " + (e.isShortcutDown() ? "Ctrl+" : "") + (e.isAltDown() ? "Alt+" : "") + e.getCode() + " on " + describe(e.getTarget()));
			}
		});
		scene.focusOwnerProperty().addListener((o, a, n) -> {
			if (n instanceof TextInputControl) {
				LauncherLog.info("[focus] text field " + describe(n));
			}
		});
	}

	/** "Button 'Play'" / "Label 'Settings' in .nav-row" - the nearest thing with a text, id or style class. */
	static String describe(EventTarget target) {
		Node node = target instanceof Node n ? n : null;
		StringBuilder path = new StringBuilder();
		int depth = 0;
		while (node != null && depth++ < 8) {
			String text = node instanceof Labeled l && l.getText() != null && !l.getText().isBlank() ? l.getText().strip()
					: node instanceof TextInputControl t && t.getPromptText() != null ? "field: " + t.getPromptText() : null;
			if (text != null) {
				text = text.length() > 50 ? text.substring(0, 50) + "..." : text;
				path.insert(0, node.getClass().getSimpleName() + " '" + text.replace('\n', ' ') + "'");
				break;
			}
			if (node.getId() != null) {
				path.insert(0, node.getClass().getSimpleName() + "#" + node.getId());
				break;
			}
			node = node.getParent();
		}
		if (path.isEmpty()) {
			Node n = target instanceof Node t ? t : null;
			path.append(n == null ? String.valueOf(target) : n.getClass().getSimpleName() + (n.getStyleClass().isEmpty() ? "" : "." + n.getStyleClass().get(0)));
		}
		return path.toString();
	}

	private static String describeWindow(Window w) {
		if (w instanceof javafx.stage.Stage s && s.getTitle() != null && !s.getTitle().isBlank()) {
			return "window '" + s.getTitle() + "'";
		}
		return w.getClass().getSimpleName();
	}
}
