package net.veloclient.launcher.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;

/**
 * An image (an animated GIF plays) that always covers its whole area: scaled up until both sides are filled, the
 * rest cropped evenly from the middle - like CSS {@code background-size: cover}. Used for the Velo+ Home background.
 */
public final class CoverImageView extends Region {

	private final ImageView view = new ImageView();

	public CoverImageView(Image image) {
		view.setImage(image);
		view.setPreserveRatio(true);
		view.setSmooth(true);
		getChildren().add(view);
		setMinSize(0, 0);
		image.progressProperty().addListener((o, a, b) -> requestLayout());
		widthProperty().addListener((o, a, b) -> requestLayout());
		heightProperty().addListener((o, a, b) -> requestLayout());
	}

	@Override
	protected void layoutChildren() {
		Image image = view.getImage();
		double w = getWidth();
		double h = getHeight();
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0 || w <= 0 || h <= 0) {
			return;
		}
		double scale = Math.max(w / image.getWidth(), h / image.getHeight());
		double iw = image.getWidth() * scale;
		double ih = image.getHeight() * scale;
		view.setFitWidth(iw);
		view.setFitHeight(ih);
		view.relocate((w - iw) / 2, (h - ih) / 2);
	}

	@Override
	protected double computePrefWidth(double height) {
		return 0;
	}

	@Override
	protected double computePrefHeight(double width) {
		return 0;
	}
}
