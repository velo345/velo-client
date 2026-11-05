package net.veloclient.launcher.ui;

import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.RunningInstanceManager;
import net.veloclient.launcher.launch.GraphicsApi;

/**
 * Vulkan | OpenGL in the profile banner (26.2+): a two-way pill whose highlight glides to the chosen side. Written
 * straight into the profile's options.txt, so the in-game video setting always agrees.
 */
final class GraphicsApiToggle {

	private static final double SEGMENT = 74;

	private GraphicsApiToggle() {
	}

	/** Null for versions without the choice. */
	static Node build(Instance instance) {
		if (!GraphicsApi.supported(instance.mcVersion())) {
			return null;
		}
		boolean[] opengl = {GraphicsApi.OPENGL.equals(GraphicsApi.effective(instance.id()))};
		Region knob = new Region();
		knob.getStyleClass().add("gapi-knob");
		knob.setMinSize(SEGMENT, 24);
		knob.setMaxSize(SEGMENT, 24);
		knob.setTranslateX(opengl[0] ? SEGMENT : 0);
		Label vulkan = segment("Vulkan");
		Label gl = segment("OpenGL");
		HBox labels = new HBox(vulkan, gl);
		StackPane pill = new StackPane(knob, labels);
		StackPane.setAlignment(knob, Pos.CENTER_LEFT);
		pill.getStyleClass().add("gapi-pill");
		pill.setMaxWidth(Region.USE_PREF_SIZE);
		Tooltip.install(pill, new Tooltip("Graphics API for this profile. Vulkan is faster on most PCs;"
				+ " OpenGL is needed for Iris shaders and some older mods. Applies on the next start."));
		Runnable paint = () -> {
			vulkan.getStyleClass().remove("gapi-on");
			gl.getStyleClass().remove("gapi-on");
			(opengl[0] ? gl : vulkan).getStyleClass().add("gapi-on");
		};
		paint.run();
		java.util.function.Consumer<Boolean> pick = toGl -> {
			if (toGl == opengl[0]) {
				return;
			}
			boolean running = RunningInstanceManager.running().stream().anyMatch(r -> r.instance().id().equals(instance.id()));
			try {
				GraphicsApi.write(instance.id(), toGl ? GraphicsApi.OPENGL : GraphicsApi.VULKAN);
			} catch (Exception e) {
				Toasts.show(pill, "warning", "#ff9a9a", "Couldn't save: " + e.getMessage());
				return;
			}
			opengl[0] = toGl;
			TranslateTransition glide = new TranslateTransition(Duration.millis(280), knob);
			glide.setToX(toGl ? SEGMENT : 0);
			glide.setInterpolator(UiMotion.BACK_OUT_SOFT);
			glide.play();
			paint.run();
			if (running) {
				Toasts.show(pill, "info", "#7dbbff", "Switches to " + (toGl ? "OpenGL" : "Vulkan") + " the next time you start this profile");
			}
		};
		vulkan.setOnMouseClicked(e -> pick.accept(false));
		gl.setOnMouseClicked(e -> pick.accept(true));
		return pill;
	}

	private static Label segment(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("gapi-segment");
		label.setMinWidth(SEGMENT);
		label.setPrefWidth(SEGMENT);
		label.setAlignment(Pos.CENTER);
		return label;
	}
}
