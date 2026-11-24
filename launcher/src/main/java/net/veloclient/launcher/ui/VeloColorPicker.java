package net.veloclient.launcher.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;

import java.util.Locale;

/**
 * The launcher's colour picker: a {@link ColorPicker} (same value / onAction API, so it drops in everywhere) whose
 * popup is Velo's own dark panel - saturation/brightness field, hue bar, hex field, before/after preview and quick
 * colours - instead of JavaFX's white palette and its "Custom Colors" dialog (white text on white, owner 1.5.1).
 * The value is committed (onAction fires) when a drag ends, a quick colour is clicked or the hex is confirmed, so the
 * views that rebuild on every change don't rebuild on every mouse move.
 */
public class VeloColorPicker extends ColorPicker {

	private static final int SV_W = 216;
	private static final int SV_H = 140;
	private static final int HUE_H = 12;
	private static final String[] QUICK = {"#D7262D", "#FF7A3D", "#F7C548", "#22C17A", "#2EC4B6", "#4F7DFF", "#8B5CF6", "#EC4899",
			"#FFFFFF", "#BDBDBD", "#6B6B6B", "#000000"};

	private Popup popup;
	private double hue;
	private double sat;
	private double bri;
	private Color before;

	private Canvas svCanvas;
	private Canvas hueCanvas;
	private Region preview;
	private Region previewBefore;
	private TextField hex;
	private WritableImage svImage;
	private double svImageHue = -1;

	public VeloColorPicker() {
		this(Color.WHITE);
	}

	public VeloColorPicker(Color value) {
		super(value);
	}

	@Override
	public void show() {
		if (isDisabled() || getScene() == null) {
			return;
		}
		if (popup == null) {
			popup = build();
		}
		before = getValue() == null ? Color.WHITE : getValue();
		hue = before.getHue();
		sat = before.getSaturation();
		bri = before.getBrightness();
		previewBefore.setStyle(fill(before));
		refresh(false);
		Bounds b = localToScreen(getBoundsInLocal());
		if (b == null) {
			return;
		}
		popup.show(this, b.getMinX(), b.getMaxY() + 6);
		// keep it on screen
		var screen = javafx.stage.Screen.getScreensForRectangle(b.getMinX(), b.getMinY(), 1, 1);
		if (!screen.isEmpty()) {
			var area = screen.get(0).getVisualBounds();
			double w = popup.getWidth();
			double h = popup.getHeight();
			double x = Math.min(b.getMinX(), area.getMaxX() - w - 8);
			double y = b.getMaxY() + 6 + h > area.getMaxY() ? b.getMinY() - h - 6 : b.getMaxY() + 6;
			popup.setX(Math.max(area.getMinX() + 8, x));
			popup.setY(Math.max(area.getMinY() + 8, y));
		}
	}

	@Override
	public void hide() {
		if (popup != null) {
			popup.hide();
		}
	}

	private Popup build() {
		Popup p = new Popup();
		p.setAutoHide(true);
		p.setHideOnEscape(true);

		svCanvas = new Canvas(SV_W, SV_H);
		svCanvas.setCursor(Cursor.CROSSHAIR);
		svCanvas.setOnMousePressed(e -> pickSv(e.getX(), e.getY()));
		svCanvas.setOnMouseDragged(e -> pickSv(e.getX(), e.getY()));
		svCanvas.setOnMouseReleased(e -> commit());

		hueCanvas = new Canvas(SV_W, HUE_H);
		hueCanvas.setCursor(Cursor.HAND);
		hueCanvas.setOnMousePressed(e -> pickHue(e.getX()));
		hueCanvas.setOnMouseDragged(e -> pickHue(e.getX()));
		hueCanvas.setOnMouseReleased(e -> commit());

		previewBefore = new Region();
		previewBefore.setPrefSize(26, 26);
		preview = new Region();
		preview.setPrefSize(26, 26);
		previewBefore.setOnMouseClicked(e -> {
			setHsb(before);
			commit();
		});
		HBox previews = new HBox(0, previewBefore, preview);
		previews.setStyle("-fx-background-radius: 7; -fx-border-radius: 7; -fx-border-color: rgba(255,255,255,0.16);");

		hex = new TextField();
		hex.setPrefColumnCount(7);
		hex.setStyle("-fx-background-color: #0C0A0A; -fx-text-fill: #F4EFEE; -fx-background-radius: 7; -fx-border-radius: 7;"
				+ " -fx-border-color: rgba(255,255,255,0.14); -fx-font-family: 'JetBrains Mono', monospace; -fx-font-size: 12px; -fx-padding: 5 8;");
		hex.setOnAction(e -> applyHex());
		hex.focusedProperty().addListener((o, was, now) -> {
			if (!now) {
				applyHex();
			}
		});
		Label hash = new Label("HEX");
		hash.setStyle("-fx-text-fill: rgba(244,239,238,0.55); -fx-font-size: 11px; -fx-font-weight: bold;");
		HBox row = new HBox(8, previews, hash, hex);
		row.setAlignment(Pos.CENTER_LEFT);

		FlowPane quick = new FlowPane(6, 6);
		quick.setPrefWrapLength(SV_W);
		for (String c : QUICK) {
			Region swatch = new Region();
			swatch.setPrefSize(13, 13);
			swatch.setCursor(Cursor.HAND);
			swatch.setStyle(fill(Color.web(c)) + " -fx-background-radius: 99; -fx-border-radius: 99; -fx-border-color: rgba(255,255,255,0.18);");
			swatch.setOnMouseClicked(e -> {
				setHsb(Color.web(c));
				commit();
			});
			quick.getChildren().add(swatch);
		}

		VBox box = new VBox(10, svCanvas, hueCanvas, row, quick);
		box.setPadding(new Insets(12));
		box.setStyle("-fx-background-color: #141011; -fx-background-radius: 12; -fx-border-radius: 12;"
				+ " -fx-border-color: rgba(255,255,255,0.12); -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 24, 0.2, 0, 8);");
		p.getContent().add(box);
		drawHueBar();
		return p;
	}

	private void setHsb(Color c) {
		hue = c.getHue();
		sat = c.getSaturation();
		bri = c.getBrightness();
		refresh(true);
	}

	private void pickSv(double x, double y) {
		sat = clamp(x / SV_W);
		bri = 1 - clamp(y / SV_H);
		refresh(true);
	}

	private void pickHue(double x) {
		hue = clamp(x / SV_W) * 360;
		refresh(true);
	}

	private void applyHex() {
		String text = hex.getText().trim();
		if (!text.startsWith("#")) {
			text = "#" + text;
		}
		if (!text.matches("#[0-9a-fA-F]{6}")) {
			hex.setText(hexOf(current()));
			return;
		}
		Color c = Color.web(text);
		if (!c.equals(getValue())) {
			setHsb(c);
			commit();
		}
	}

	private Color current() {
		return Color.hsb(hue, sat, bri);
	}

	/** Redraws the panel; {@code live}: the value follows (listeners see it, onAction waits for {@link #commit}). */
	private void refresh(boolean live) {
		drawSv();
		GraphicsContext g = hueCanvas.getGraphicsContext2D();
		drawHueBar();
		double hx = hue / 360 * SV_W;
		g.setStroke(Color.WHITE);
		g.setLineWidth(2);
		g.strokeRoundRect(Math.max(1, Math.min(SV_W - 7, hx - 3)), 1, 6, HUE_H - 2, 4, 4);
		Color c = current();
		preview.setStyle(fill(c));
		if (!hex.isFocused()) {
			hex.setText(hexOf(c));
		}
		if (live) {
			setValue(c);
		}
	}

	private void commit() {
		setValue(current());
		fireEvent(new ActionEvent());
	}

	private void drawSv() {
		if (svImage == null) {
			svImage = new WritableImage(SV_W, SV_H);
		}
		if (svImageHue != hue) {
			svImageHue = hue;
			// one bulk write per hue (never per-pixel setColor - see launcher perf notes)
			int[] argb = new int[SV_W * SV_H];
			Color pure = Color.hsb(hue, 1, 1);
			for (int y = 0; y < SV_H; y++) {
				double v = 1 - y / (double) (SV_H - 1);
				for (int x = 0; x < SV_W; x++) {
					double s = x / (double) (SV_W - 1);
					double r = (1 - s + s * pure.getRed()) * v;
					double gr = (1 - s + s * pure.getGreen()) * v;
					double bl = (1 - s + s * pure.getBlue()) * v;
					argb[y * SV_W + x] = 0xFF000000 | (int) Math.round(r * 255) << 16 | (int) Math.round(gr * 255) << 8 | (int) Math.round(bl * 255);
				}
			}
			svImage.getPixelWriter().setPixels(0, 0, SV_W, SV_H, PixelFormat.getIntArgbInstance(), argb, 0, SV_W);
		}
		GraphicsContext g = svCanvas.getGraphicsContext2D();
		g.drawImage(svImage, 0, 0);
		double x = sat * SV_W;
		double y = (1 - bri) * SV_H;
		g.setStroke(bri > 0.6 && sat < 0.5 ? Color.BLACK : Color.WHITE);
		g.setLineWidth(2);
		g.strokeOval(x - 6, y - 6, 12, 12);
	}

	private void drawHueBar() {
		GraphicsContext g = hueCanvas.getGraphicsContext2D();
		g.clearRect(0, 0, SV_W, HUE_H);
		javafx.scene.paint.Stop[] stops = new javafx.scene.paint.Stop[7];
		for (int i = 0; i < 7; i++) {
			stops[i] = new javafx.scene.paint.Stop(i / 6.0, Color.hsb(i * 60, 1, 1));
		}
		g.setFill(new javafx.scene.paint.LinearGradient(0, 0, 1, 0, true, javafx.scene.paint.CycleMethod.NO_CYCLE, stops));
		g.fillRoundRect(0, 0, SV_W, HUE_H, HUE_H, HUE_H);
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}

	private static String fill(Color c) {
		return "-fx-background-color: " + hexOf(c) + ";";
	}

	static String hexOf(Color c) {
		return String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
	}
}
