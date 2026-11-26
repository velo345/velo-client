package net.veloclient.launcher.ui.glass;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import net.veloclient.launcher.ui.RenderGate;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The window-filling pixel backdrop of the glass launcher (2026-11-25): one of the {@link PixelScenes} scaled to cover the
 * window with crisp pixels, its stars twinkling, a shooting star now and then and petals/snow drifting down - or, for
 * Velo+, the member's own picture or GIF. It also makes the frosted copy every {@link Glass} panel shows behind itself:
 * the same picture blurred and tinted, mapped with the same cover transform so a panel's backdrop lines up exactly.
 */
public final class Backdrop extends Pane {

	private static Backdrop current;

	/** The backdrop of the main window (glass panels read its frosted image and geometry). */
	public static Backdrop current() {
		return current;
	}

	private final ImageView base = pixelView();
	private final ImageView twinkleA = pixelView();
	private final ImageView twinkleB = pixelView();
	private final ImageView shooting = pixelView();
	private final ImageView driftA = pixelView();
	private final ImageView driftB = pixelView();
	private final ImageView custom = new ImageView();
	private final Region dim = new Region();
	private final ImageView fadeOut = pixelView();
	/** Pages other than Home: a calm grey instead of the scene (and grey frost behind the glass). */
	private final Region plain = new Region();
	private boolean plainMode;
	/** Px from the top where the scene still shows on a grey page (the profile header), 0 = grey all over. */
	private double plainTop;
	/** The thin light line along the scene tab's edge on the profile page. */
	private final javafx.scene.shape.SVGPath plainEdge = new javafx.scene.shape.SVGPath();
	private Image sceneFrost;
	private WritableImage plainFrost = plainFrostImage(0.45);

	private final ObjectProperty<Image> frosted = new SimpleObjectProperty<>();
	/** Bumped whenever the cover transform or the frosted picture changes - glass panels re-map then. */
	private final LongProperty geometry = new SimpleLongProperty();
	private final ObjectProperty<Color> tint = new SimpleObjectProperty<>(Color.rgb(24, 10, 16));

	private final javafx.beans.property.DoubleProperty shootT = new javafx.beans.property.SimpleDoubleProperty();
	private final javafx.beans.property.DoubleProperty driftT = new javafx.beans.property.SimpleDoubleProperty();
	private final Timeline twinkleATimeline;
	private final Timeline twinkleBTimeline;
	private final Timeline shootingTimeline;
	private final Timeline driftTimeline;

	private String sceneId = "";
	private Path customFile;
	private double glassAlpha = 0.45;
	private double customBlur = 0.3;
	private double customDim = 0.35;
	private int[] frostSource;
	private int frostW;
	private int frostH;
	private double scale = 1;
	private double offsetX;
	private double offsetY;
	private double sourceW = PixelScenes.W;
	private double sourceH = PixelScenes.H;

	/** The scene on screen only (each one is ~20 MB of pixels - keeping every scene ever shown added up fast). */
	private static final Map<String, PixelScenes.Scene> SCENES = new ConcurrentHashMap<>();
	/** Where each cropped animation layer sits in the 1280 x 800 picture: {x, y} per view. */
	private final Map<ImageView, int[]> layerOffsets = new java.util.HashMap<>();

	public Backdrop() {
		current = this;
		getStyleClass().add("backdrop");
		setMouseTransparent(true);
		setMinSize(0, 0);
		custom.setPreserveRatio(false);
		custom.setSmooth(true);
		custom.setVisible(false);
		dim.setMouseTransparent(true);
		dim.setVisible(false);
		plain.getStyleClass().add("backdrop-plain");
		plain.setOpacity(0);
		plain.setMouseTransparent(true);
		plainEdge.setFill(Color.TRANSPARENT);
		plainEdge.setStroke(Color.rgb(255, 255, 255, 0.13));
		plainEdge.setStrokeWidth(1);
		plainEdge.setManaged(false);
		plainEdge.setMouseTransparent(true);
		plainEdge.setVisible(false);
		plainEdge.opacityProperty().bind(plain.opacityProperty());
		getChildren().addAll(base, twinkleA, twinkleB, shooting, driftA, driftB, custom, dim, plain, plainEdge, fadeOut);
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(widthProperty());
		clip.heightProperty().bind(heightProperty());
		setClip(clip);

		// Stars blink in two groups (hard steps, like pixel art), a shooting star crosses every 9 s, drift falls slowly.
		twinkleATimeline = blink(twinkleA, 3.4, 0);
		twinkleBTimeline = blink(twinkleB, 4.6, 1.7);
		shootingTimeline = new Timeline(
				new KeyFrame(Duration.ZERO, new KeyValue(shooting.opacityProperty(), 0), new KeyValue(shootT, 0)),
				new KeyFrame(Duration.seconds(7.0), new KeyValue(shooting.opacityProperty(), 0), new KeyValue(shootT, 0)),
				new KeyFrame(Duration.seconds(7.2), new KeyValue(shooting.opacityProperty(), 1)),
				new KeyFrame(Duration.seconds(8.3), new KeyValue(shooting.opacityProperty(), 0, Interpolator.EASE_IN),
						new KeyValue(shootT, 1, Interpolator.LINEAR)),
				new KeyFrame(Duration.seconds(9)));
		shootingTimeline.setCycleCount(Animation.INDEFINITE);
		shootT.addListener((o, a, b) -> placeShooting());
		driftTimeline = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(driftT, 0)),
				new KeyFrame(Duration.seconds(48), new KeyValue(driftT, 1, Interpolator.LINEAR)));
		driftTimeline.setCycleCount(Animation.INDEFINITE);
		driftT.addListener((o, a, b) -> placeDrift());
		for (Timeline t : new Timeline[] {twinkleATimeline, twinkleBTimeline, shootingTimeline, driftTimeline}) {
			RenderGate.gate(t);
		}
		widthProperty().addListener((o, a, b) -> relayout());
		heightProperty().addListener((o, a, b) -> relayout());
	}

	private static ImageView pixelView() {
		ImageView view = new ImageView();
		view.setSmooth(false);
		view.setManaged(false);
		return view;
	}

	private static Timeline blink(ImageView view, double seconds, double delay) {
		Timeline t = new Timeline(
				new KeyFrame(Duration.ZERO, new KeyValue(view.opacityProperty(), 1, Interpolator.DISCRETE)),
				new KeyFrame(Duration.seconds(seconds / 2), new KeyValue(view.opacityProperty(), 0.2, Interpolator.DISCRETE)),
				new KeyFrame(Duration.seconds(seconds), new KeyValue(view.opacityProperty(), 1, Interpolator.DISCRETE)));
		t.setCycleCount(Animation.INDEFINITE);
		t.setDelay(Duration.seconds(delay));
		return t;
	}

	public ObjectProperty<Image> frostedProperty() {
		return frosted;
	}

	public LongProperty geometryProperty() {
		return geometry;
	}

	public ObjectProperty<Color> tintProperty() {
		return tint;
	}

	public String sceneId() {
		return sceneId;
	}

	/** Maps a point in the window to the frosted picture: {scale, offsetX, offsetY, frostW / sourceW}. */
	double[] mapping() {
		if (plainMode) {
			// the grey pages' frost is its own small gradient picture: map the window's height onto its 40 rows (the
			// scene's mapping put every panel below the top ~40 px outside it - the sidebar showed only its tint there,
			// darker and redder than the logo part above)
			// PLAIN_PAD extra rows above and below: panels scrolled past the window's edge (the Velo+ table) used to run out
			// of picture there and showed only their tint below a hard line
			double s = Math.max(1, getHeight()) / PLAIN_H;
			return new double[] {s, 0, -PLAIN_PAD * s, 1};
		}
		return new double[] {scale, offsetX, offsetY, sourceW == 0 ? 1 : frostW / sourceW};
	}

	private static final int PLAIN_W = 400;
	private static final int PLAIN_H = 40;
	private static final int PLAIN_PAD = 200;

	/** How dark the glass is (0 clear .. 1 solid) - re-tints the frosted picture. */
	public void setGlassAlpha(double alpha) {
		glassAlpha = Math.max(0.15, Math.min(0.85, alpha));
		plainFrost = plainFrostImage(glassAlpha);
		if (plainMode) {
			frosted.set(plainFrost);
			geometry.set(geometry.get() + 1);
		}
		rebuildFrost();
	}

	/**
	 * Shows {@code id}'s pixel scene, or {@code customFile} (Velo+) when given. Rendering runs off the UI thread; the old
	 * picture cross-fades out once the new one is ready.
	 */
	public void show(String id, Path customFile, double customBlur, double customDim, boolean animate) {
		this.customBlur = customBlur;
		this.customDim = customDim;
		boolean sameScene = customFile == null && this.customFile == null && id.equals(sceneId);
		this.customFile = customFile;
		setAnimated(animate);
		if (sameScene && base.getImage() != null) {
			return;
		}
		sceneId = id;
		if (customFile != null) {
			showCustom(customFile);
			return;
		}
		PixelScenes.Scene cached = SCENES.get(id);
		if (cached != null) {
			apply(cached);
			return;
		}
		Thread.ofVirtual().start(() -> {
			PixelScenes.Scene rendered;
			try {
				rendered = PixelScenes.render(id);
			} catch (RuntimeException e) {
				net.veloclient.launcher.LauncherLog.warn("Backdrop " + id + " failed to render", e);
				rendered = PixelScenes.render(PixelScenes.DEFAULT);
			}
			// not kept: the pictures made from it are all that's needed (re-rendering a scene takes ~50 ms)
			SCENES.clear();
			PixelScenes.Scene ready = rendered;
			Platform.runLater(() -> {
				if (id.equals(sceneId) && this.customFile == null) {
					apply(ready);
				}
			});
		});
	}

	public void setAnimated(boolean animate) {
		for (Timeline t : new Timeline[] {twinkleATimeline, twinkleBTimeline, shootingTimeline, driftTimeline}) {
			if (animate) {
				t.play();
			} else {
				t.stop();
			}
		}
		if (!animate) {
			twinkleA.setOpacity(1);
			twinkleB.setOpacity(1);
			shooting.setOpacity(0);
		}
	}

	private void crossFadeFromCurrent() {
		if (getWidth() <= 0 || (base.getImage() == null && !custom.isVisible())) {
			return;
		}
		javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
		fadeOut.setVisible(false);
		WritableImage shot = snapshot(params, null);
		fadeOut.setImage(shot);
		fadeOut.setFitWidth(getWidth());
		fadeOut.setFitHeight(getHeight());
		fadeOut.relocate(0, 0);
		fadeOut.setOpacity(1);
		fadeOut.setVisible(true);
		Timeline out = new Timeline(new KeyFrame(Duration.millis(520), new KeyValue(fadeOut.opacityProperty(), 0, Interpolator.EASE_BOTH)));
		out.setOnFinished(e -> {
			fadeOut.setImage(null);
			fadeOut.setVisible(false);
		});
		out.play();
	}

	private void apply(PixelScenes.Scene scene) {
		crossFadeFromCurrent();
		custom.setVisible(false);
		custom.setImage(null);
		custom.setEffect(null);
		dim.setVisible(false);
		sourceW = PixelScenes.W;
		sourceH = PixelScenes.H;
		base.setImage(image(scene.base()));
		// the animated layers are mostly empty: each is cut to the box that has pixels (a few MB less heap and VRAM)
		layer(twinkleA, scene.twinkleA());
		layer(twinkleB, scene.twinkleB());
		layer(shooting, scene.shooting());
		layer(driftA, scene.drift());
		driftB.setImage(driftA.getImage());
		layerOffsets.put(driftB, layerOffsets.get(driftA));
		// The frosted copy: half size is plenty for a blur.
		frostW = PixelScenes.W / 2;
		frostH = PixelScenes.H / 2;
		frostSource = halve(scene.base(), PixelScenes.W, PixelScenes.H);
		tint.set(averageTint(frostSource));
		rebuildFrost();
		relayout();
	}

	private void showCustom(Path file) {
		crossFadeFromCurrent();
		Image picture = new Image(file.toUri().toString(), true);
		Runnable ready = () -> {
			if (picture.isError() || picture.getWidth() <= 0) {
				// unreadable file: the default scene instead
				customFile = null;
				show(PixelScenes.DEFAULT, null, customBlur, customDim, true);
				return;
			}
			SCENES.clear();
			base.setImage(null);
			twinkleA.setImage(null);
			twinkleB.setImage(null);
			shooting.setImage(null);
			driftA.setImage(null);
			driftB.setImage(null);
			custom.setImage(picture);
			custom.setVisible(true);
			custom.setEffect(customBlur > 0.01 ? new javafx.scene.effect.GaussianBlur(customBlur * 40) : null);
			dim.setStyle("-fx-background-color: rgba(0,0,0," + String.format(java.util.Locale.ROOT, "%.3f", customDim * 0.8) + ");");
			dim.setVisible(customDim > 0.01);
			sourceW = picture.getWidth();
			sourceH = picture.getHeight();
			Image small = new Image(file.toUri().toString(), 640, 640 * sourceH / sourceW, false, true);
			frostW = (int) small.getWidth();
			frostH = (int) small.getHeight();
			frostSource = new int[frostW * frostH];
			PixelReader reader = small.getPixelReader();
			if (reader != null) {
				reader.getPixels(0, 0, frostW, frostH, PixelFormat.getIntArgbInstance(), frostSource, 0, frostW);
			}
			// the dim darkens the frosted copy too
			int k = (int) Math.round((1 - customDim * 0.8) * 255);
			for (int i = 0; i < frostSource.length; i++) {
				int c = frostSource[i];
				frostSource[i] = 0xFF000000 | ((((c >> 16) & 255) * k / 255) << 16) | ((((c >> 8) & 255) * k / 255) << 8) | ((c & 255) * k / 255);
			}
			tint.set(averageTint(frostSource));
			rebuildFrost();
			relayout();
		};
		if (picture.getProgress() >= 1) {
			ready.run();
		} else {
			picture.progressProperty().addListener((o, a, p) -> {
				if (p.doubleValue() >= 1) {
					ready.run();
				}
			});
		}
	}

	private void layer(ImageView view, int[] argb) {
		if (argb == null) {
			view.setImage(null);
			layerOffsets.put(view, new int[] {0, 0, PixelScenes.W, PixelScenes.H});
			return;
		}
		int minX = PixelScenes.W, minY = PixelScenes.H, maxX = -1, maxY = -1;
		for (int y = 0; y < PixelScenes.H; y++) {
			int row = y * PixelScenes.W;
			for (int x = 0; x < PixelScenes.W; x++) {
				if ((argb[row + x] >>> 24) != 0) {
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
			}
		}
		if (maxX < 0) {
			view.setImage(null);
			layerOffsets.put(view, new int[] {0, 0, 1, 1});
			return;
		}
		int w = maxX - minX + 1, h = maxY - minY + 1;
		WritableImage image = new WritableImage(w, h);
		image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, minY * PixelScenes.W + minX, PixelScenes.W);
		view.setImage(image);
		layerOffsets.put(view, new int[] {minX, minY, w, h});
	}

	private static WritableImage image(int[] argb) {
		WritableImage image = new WritableImage(PixelScenes.W, PixelScenes.H);
		image.getPixelWriter().setPixels(0, 0, PixelScenes.W, PixelScenes.H, PixelFormat.getIntArgbInstance(), argb, 0, PixelScenes.W);
		return image;
	}

	private static int[] halve(int[] src, int w, int h) {
		int hw = w / 2, hh = h / 2;
		int[] out = new int[hw * hh];
		for (int y = 0; y < hh; y++) {
			for (int x = 0; x < hw; x++) {
				int a = src[(y * 2) * w + x * 2], b = src[(y * 2) * w + x * 2 + 1], c = src[(y * 2 + 1) * w + x * 2], d = src[(y * 2 + 1) * w + x * 2 + 1];
				int r = (((a >> 16) & 255) + ((b >> 16) & 255) + ((c >> 16) & 255) + ((d >> 16) & 255)) / 4;
				int g = (((a >> 8) & 255) + ((b >> 8) & 255) + ((c >> 8) & 255) + ((d >> 8) & 255)) / 4;
				int bl = ((a & 255) + (b & 255) + (c & 255) + (d & 255)) / 4;
				out[y * hw + x] = 0xFF000000 | (r << 16) | (g << 8) | bl;
			}
		}
		return out;
	}

	/** The glass tint: the scene's average colour pulled most of the way to black (keeps blue scenes blue, red ones red). */
	private static Color averageTint(int[] pixels) {
		long r = 0, g = 0, b = 0;
		for (int c : pixels) {
			r += (c >> 16) & 255;
			g += (c >> 8) & 255;
			b += c & 255;
		}
		int n = Math.max(1, pixels.length);
		Color avg = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
		return Color.hsb(avg.getHue(), Math.min(0.75, avg.getSaturation() * 1.1), 0.1);
	}

	private void rebuildFrost() {
		int[] src = frostSource;
		if (src == null) {
			return;
		}
		int w = frostW, h = frostH;
		int[] blurred = src.clone();
		int[] tmp = new int[blurred.length];
		// three box passes = a near-Gaussian blur (radius in half-size pixels)
		int radius = 9;
		for (int pass = 0; pass < 3; pass++) {
			boxH(blurred, tmp, w, h, radius);
			boxV(tmp, blurred, w, h, radius);
		}
		Color t = tint.get();
		int tr = (int) Math.round(t.getRed() * 255), tg = (int) Math.round(t.getGreen() * 255), tb = (int) Math.round(t.getBlue() * 255);
		double a = glassAlpha;
		for (int i = 0; i < blurred.length; i++) {
			int c = blurred[i];
			// a touch of saturation like backdrop-filter: saturate(1.5), then the tint on top
			int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
			int lum = (r * 3 + g * 6 + b) / 10;
			r = clamp(lum + (r - lum) * 3 / 2);
			g = clamp(lum + (g - lum) * 3 / 2);
			b = clamp(lum + (b - lum) * 3 / 2);
			r = (int) (r * (1 - a) + tr * a);
			g = (int) (g * (1 - a) + tg * a);
			b = (int) (b * (1 - a) + tb * a);
			blurred[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
		}
		WritableImage image = new WritableImage(w, h);
		image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), blurred, 0, w);
		sceneFrost = image;
		frosted.set(plainMode ? plainFrost : image);
		geometry.set(geometry.get() + 1);
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : Math.min(255, v);
	}

	private static void boxH(int[] src, int[] dst, int w, int h, int r) {
		for (int y = 0; y < h; y++) {
			int row = y * w;
			int sr = 0, sg = 0, sb = 0;
			for (int i = -r; i <= r; i++) {
				int c = src[row + Math.max(0, Math.min(w - 1, i))];
				sr += (c >> 16) & 255;
				sg += (c >> 8) & 255;
				sb += c & 255;
			}
			int div = 2 * r + 1;
			for (int x = 0; x < w; x++) {
				dst[row + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
				int out = src[row + Math.max(0, x - r)];
				int in = src[row + Math.min(w - 1, x + r + 1)];
				sr += ((in >> 16) & 255) - ((out >> 16) & 255);
				sg += ((in >> 8) & 255) - ((out >> 8) & 255);
				sb += (in & 255) - (out & 255);
			}
		}
	}

	private static void boxV(int[] src, int[] dst, int w, int h, int r) {
		for (int x = 0; x < w; x++) {
			int sr = 0, sg = 0, sb = 0;
			for (int i = -r; i <= r; i++) {
				int c = src[Math.max(0, Math.min(h - 1, i)) * w + x];
				sr += (c >> 16) & 255;
				sg += (c >> 8) & 255;
				sb += c & 255;
			}
			int div = 2 * r + 1;
			for (int y = 0; y < h; y++) {
				dst[y * w + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
				int out = src[Math.max(0, y - r) * w + x];
				int in = src[Math.min(h - 1, y + r + 1) * w + x];
				sr += ((in >> 16) & 255) - ((out >> 16) & 255);
				sg += ((in >> 8) & 255) - ((out >> 8) & 255);
				sb += (in & 255) - (out & 255);
			}
		}
	}

	/** Cover: the picture always fills the window, cropped evenly, pixels kept square. */
	private void relayout() {
		double w = getWidth(), h = getHeight();
		if (w <= 0 || h <= 0) {
			return;
		}
		scale = Math.max(w / sourceW, h / sourceH);
		double dw = sourceW * scale, dh = sourceH * scale;
		offsetX = (w - dw) / 2;
		offsetY = (h - dh) / 2;
		base.setFitWidth(dw);
		base.setFitHeight(dh);
		base.relocate(offsetX, offsetY);
		for (ImageView view : new ImageView[] {twinkleA, twinkleB, shooting, driftA, driftB}) {
			int[] o = layerOffsets.getOrDefault(view, new int[] {0, 0, PixelScenes.W, PixelScenes.H});
			view.setFitWidth(o[2] * scale);
			view.setFitHeight(o[3] * scale);
			view.relocate(offsetX + o[0] * scale, offsetY + o[1] * scale);
		}
		custom.setFitWidth(dw);
		custom.setFitHeight(dh);
		custom.relocate(offsetX, offsetY);
		dim.resizeRelocate(0, 0, w, h);
		plain.resizeRelocate(0, 0, w, h);
		stylePlain();
		placeShooting();
		placeDrift();
		geometry.set(geometry.get() + 1);
	}

	private void placeShooting() {
		// the star travels 220 x 110 picture pixels while it fades
		double t = shootT.get();
		int[] o = layerOffsets.getOrDefault(shooting, new int[] {0, 0, 0, 0});
		shooting.relocate(offsetX + (o[0] + 220 * t) * scale, offsetY + (o[1] + 110 * t) * scale);
	}

	private void placeDrift() {
		double t = driftT.get();
		double dh = sourceH * scale;
		int[] o = layerOffsets.getOrDefault(driftA, new int[] {0, 0, 0, 0});
		driftA.relocate(offsetX + o[0] * scale, offsetY + o[1] * scale + dh * t);
		driftB.relocate(offsetX + o[0] * scale, offsetY + o[1] * scale + dh * t - dh);
	}

	/** A flat grey "frost" for the plain pages (a soft top-to-bottom shade). */
	private static WritableImage plainFrostImage(double alpha) {
		// wide enough for any window shape at PLAIN_H rows per window height (only the rows change colour)
		int w = PLAIN_W, h = PLAIN_H + 2 * PLAIN_PAD;
		WritableImage image = new WritableImage(w, h);
		int[] px = new int[w * h];
		// darker glass = closer to the page grey, clearer glass = lighter
		int top = PixelScenes.mix(0xFF35313A, 0xFF1A181D, alpha);
		int bottom = PixelScenes.mix(0xFF2A272E, 0xFF151418, alpha);
		for (int y = 0; y < h; y++) {
			// the window's own rows in the middle; above and below it the edge colours carry on
			double t = Math.max(0, Math.min(1, (y - PLAIN_PAD) / (double) (PLAIN_H - 1)));
			int c = PixelScenes.mix(top, bottom, t);
			for (int x = 0; x < w; x++) {
				px[y * w + x] = c;
			}
		}
		image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
		return image;
	}

	/** A grey page that lets the scene show behind its top {@code px} (the profile header), fading into the grey. */
	public void setPlainTop(double px) {
		double target = Math.max(0, px);
		if (plainTopAnim != null) {
			plainTopAnim.stop();
			plainTopAnim = null;
		}
		if (target == plainTop) {
			return;
		}
		if (!plainMode || plain.getOpacity() < 0.05 || getScene() == null) {
			// the grey isn't showing yet (coming from Home it fades in already cut) - no need to animate
			plainTop = target;
			stylePlain();
			return;
		}
		// on a grey page the header tab opens / closes smoothly instead of snapping (profile page <-> Profiles)
		javafx.beans.property.DoubleProperty depth = new javafx.beans.property.SimpleDoubleProperty(plainTop);
		depth.addListener((o, a, v) -> {
			plainTop = v.doubleValue();
			stylePlain();
		});
		plainTopAnim = new Timeline(new KeyFrame(Duration.millis(340), new KeyValue(depth, target, Interpolator.SPLINE(0.2, 0.8, 0.25, 1))));
		Timeline anim = plainTopAnim;
		anim.setOnFinished(e -> {
			if (plainTopAnim == anim) {
				plainTopAnim = null;
			}
		});
		net.veloclient.launcher.ui.UiMotion.afterFirstFrame(() -> {
			if (plainTopAnim == anim) {
				anim.play();
			}
		});
	}

	private Timeline plainTopAnim;

	/**
	 * The grey sheet with a flowing cut-out on top (owner's flow reference): the scene shows in a wide tab behind the
	 * header whose lower corners curve round into the grey, edged by a thin light line - no plain fade.
	 */
	private void stylePlain() {
		double w = getWidth(), h = getHeight();
		if (plainTop <= 0 || w <= 0 || h <= 0) {
			plain.setShape(null);
			plainEdge.setVisible(false);
			return;
		}
		if (plainTop < 1) {
			plain.setShape(null);
			plainEdge.setVisible(false);
			return;
		}
		double left = 104, right = w - 16, depth = plainTop, r = Math.min(34, depth);
		String sheet = String.format(java.util.Locale.ROOT,
				"M 0 0 H %1$.1f V %3$.1f A %4$.1f %4$.1f 0 0 0 %5$.1f %6$.1f H %7$.1f A %4$.1f %4$.1f 0 0 0 %2$.1f %3$.1f V 0 H %8$.1f V %9$.1f H 0 Z",
				left, right, depth - r, r, left + r, depth, right - r, w, h);
		javafx.scene.shape.SVGPath shape = new javafx.scene.shape.SVGPath();
		shape.setContent(sheet);
		plain.setScaleShape(false);
		plain.setCenterShape(false);
		plain.setShape(shape);
		plainEdge.setContent(String.format(java.util.Locale.ROOT,
				"M %1$.1f 0 V %3$.1f A %4$.1f %4$.1f 0 0 0 %5$.1f %6$.1f H %7$.1f A %4$.1f %4$.1f 0 0 0 %2$.1f %3$.1f V 0",
				left, right, depth - r, r, left + r, depth, right - r));
		plainEdge.setVisible(true);
	}

	/** Home shows the scene; every other page a calm grey (cross-fades, the glass follows). */
	/** True on the grey pages (everything but Home). */
	public boolean isPlain() {
		return plainMode;
	}

	/** When Home <-> grey last switched: the glass panels cross-fade their frost for the switch. */
	private long modeSwitchedAt;
	private Timeline plainFade;

	/** True while a Home <-> grey page switch is being applied (the glass then fades its frost instead of cutting). */
	public boolean switchingMode() {
		return System.nanoTime() - modeSwitchedAt < 150_000_000L;
	}

	public void setPlain(boolean value) {
		if (plainMode == value) {
			return;
		}
		plainMode = value;
		modeSwitchedAt = System.nanoTime();
		Timeline fade = new Timeline(new KeyFrame(Duration.millis(380), new KeyValue(plain.opacityProperty(), value ? 1 : 0, Interpolator.EASE_BOTH)));
		if (plainFade != null) {
			plainFade.stop();
		}
		plainFade = fade;
		// with the new page: after its (heavy) first frame, not inside it
		net.veloclient.launcher.ui.UiMotion.afterFirstFrame(() -> {
			if (plainFade == fade) {
				fade.play();
			}
		});
		if (sceneFrost != null || value) {
			frosted.set(value ? plainFrost : sceneFrost);
			geometry.set(geometry.get() + 1);
		}
	}

	@Override
	protected void layoutChildren() {
		// children are placed by relayout(); nothing is managed
	}
}
