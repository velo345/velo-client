package net.veloclient.launcher.ui.glass;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import net.veloclient.launcher.ui.Icons;
import net.veloclient.launcher.ui.UiImages;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Settings > Appearance: the launcher background (2026-11-25) - every pixel scene as a small live thumbnail, Custom
 * (Velo+, your own picture or GIF) and how dark the glass is. Changes apply at once.
 */
public final class BackdropPicker {

	public interface Host {
		String current();

		boolean plus();

		void pick(String id);

		/** Custom: choose a picture (Velo+), or explain Velo+ to non-members. */
		void custom();

		double glassDarkness();

		void setGlassDarkness(double value);

		double customBlur();

		double customDim();

		void setCustom(double blur, double dim);
	}

	private static final Map<String, Image> THUMBS = new ConcurrentHashMap<>();
	private static final int TW = 160;
	private static final int TH = 100;

	private BackdropPicker() {
	}

	public static Node build(Host host) {
		FlowPane tiles = new FlowPane(10, 10);
		for (PixelScenes.Info info : PixelScenes.ALL) {
			tiles.getChildren().add(tile(info.id(), info.name(), host.current().equals(info.id()), host, sceneThumb(info.id())));
		}
		Node customArt;
		StackPane art = new StackPane(Icons.icon("image", 20, Color.web("#E4E4E8")));
		art.getStyleClass().add("bg-tile-custom");
		ImageView badge = new ImageView(UiImages.resource("/net/veloclient/launcher/images/badges/plus.png", 42, 28));
		badge.setFitWidth(21);
		badge.setFitHeight(14);
		StackPane.setAlignment(badge, Pos.TOP_RIGHT);
		StackPane.setMargin(badge, new Insets(6));
		art.getChildren().add(badge);
		customArt = art;
		tiles.getChildren().add(tile("custom", "Custom", "custom".equals(host.current()), host, customArt));

		VBox box = new VBox(12, tiles);
		box.getChildren().add(sliderRow("Glass", "Clear", "Dark", host.glassDarkness(), host::setGlassDarkness));
		if ("custom".equals(host.current()) && host.plus()) {
			double[] values = {host.customBlur(), host.customDim()};
			box.getChildren().add(sliderRow("Blur", "Sharp", "Soft", values[0], v -> {
				values[0] = v;
				host.setCustom(values[0], values[1]);
			}));
			box.getChildren().add(sliderRow("Dim", "Bright", "Dark", values[1], v -> {
				values[1] = v;
				host.setCustom(values[0], values[1]);
			}));
		}
		return box;
	}

	private static Node tile(String id, String name, boolean on, Host host, Node art) {
		StackPane frame = new StackPane(art);
		frame.setMinSize(TW, TH);
		frame.setMaxSize(TW, TH);
		Rectangle clip = new Rectangle(TW, TH);
		clip.setArcWidth(18);
		clip.setArcHeight(18);
		frame.setClip(clip);
		Label label = new Label(name);
		label.getStyleClass().add("bg-tile-name");
		VBox tile = new VBox(6, frame, label);
		tile.getStyleClass().addAll("bg-tile", on ? "bg-tile-on" : "bg-tile-off");
		tile.setPadding(new Insets(5));
		tile.setOnMouseClicked(e -> {
			if (id.equals("custom")) {
				host.custom();
			} else {
				host.pick(id);
			}
		});
		return tile;
	}

	/** A small picture of the scene, rendered once in the background. */
	private static Node sceneThumb(String id) {
		ImageView view = new ImageView();
		view.setFitWidth(TW);
		view.setFitHeight(TH);
		view.setSmooth(true);
		Image cached = THUMBS.get(id);
		if (cached != null) {
			view.setImage(cached);
			return view;
		}
		Thread.ofVirtual().start(() -> {
			PixelScenes.Scene scene = PixelScenes.render(id);
			int[] src = scene.base();
			int[] out = new int[TW * TH];
			// point sampling keeps the pixel-art look
			for (int y = 0; y < TH; y++) {
				for (int x = 0; x < TW; x++) {
					out[y * TW + x] = src[(y * PixelScenes.H / TH) * PixelScenes.W + x * PixelScenes.W / TW];
				}
			}
			Platform.runLater(() -> {
				WritableImage image = new WritableImage(TW, TH);
				image.getPixelWriter().setPixels(0, 0, TW, TH, PixelFormat.getIntArgbInstance(), out, 0, TW);
				THUMBS.put(id, image);
				view.setImage(image);
			});
		});
		return view;
	}

	private static Node sliderRow(String name, String low, String high, double value, Consumer<Double> onChange) {
		Label title = new Label(name);
		title.getStyleClass().add("t-strong");
		title.setMinWidth(54);
		Slider slider = new Slider(0, 1, value);
		HBox.setHgrow(slider, Priority.ALWAYS);
		slider.valueChangingProperty().addListener((o, was, changing) -> {
			if (!changing) {
				onChange.accept(slider.getValue());
			}
		});
		slider.setOnMouseReleased(e -> onChange.accept(slider.getValue()));
		Label lo = new Label(low);
		lo.getStyleClass().add("t-muted");
		Label hi = new Label(high);
		hi.getStyleClass().add("t-muted");
		HBox row = new HBox(10, title, lo, slider, hi);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setMaxWidth(560);
		return row;
	}
}
