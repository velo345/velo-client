package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.SkinFetcher;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.launcher.data.StoreCatalog;
import net.veloclient.launcher.data.StoreItem;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Shared building blocks for the Cosmetics and Store pages: decoded cape frames, cached 3D cape
 * thumbnails, the cape card, the coin icon/price chip, and the signed-in player's skin (fetched once
 * per account and reused, so switching between pages doesn't refetch it).
 */
public final class CosmeticUi {

	/**
	 * Decoded cape frames for cards and thumbnails: cape-region crops at {@link #CARD_SCALE} px per cape pixel (about 4 MB
	 * for a 40-frame cape), so a few dozen fit.
	 */
	private static final Map<String, List<GifFrames.Frame>> FRAMES = LauncherCaches.lru(30);
	/** Full-resolution frames for the big 3D viewers - only the few on screen (about 15 MB each for a 1024 px cape). */
	private static final Map<String, List<GifFrames.Frame>> HD_FRAMES = LauncherCaches.lru(3);
	private static final int CARD_SCALE = 8;
	private static final int HD_SCALE = 32;
	/** Decoding happens off the UI thread, two at a time (a page of capes shouldn't saturate every core). */
	private static final java.util.concurrent.ExecutorService DECODER = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "velo-cape-decoder");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	});
	private static final Map<String, List<Consumer<List<GifFrames.Frame>>>> WAITING = new java.util.HashMap<>();
	private static final Map<String, Image> THUMBNAILS = LauncherCaches.lru(120);
	/** Hover animation frames, one array per cape and size - in THUMBNAILS, three 40-frame capes evicted each other's frames and every hover re-rendered them all. */
	private static final Map<String, Image[]> FRAME_THUMBNAILS = LauncherCaches.lru(10);
	private static volatile String skinOwner;
	private static volatile SkinFetcher.SkinData skin;

	private CosmeticUi() {
	}

	// ---- Data ----

	private static List<GifFrames.Frame> decode(CapeEntry cape, int scale) {
		try {
			if (cape.animated()) {
				return GifFrames.decodeCape(new ByteArrayInputStream(CapeLibrary.gifBytes(cape)), scale).frames();
			}
			var image = ImageIO.read(new ByteArrayInputStream(CapeLibrary.textureBytes(cape)));
			return image == null ? List.of() : List.of(new GifFrames.Frame(image, 100));
		} catch (Throwable e) {
			return List.of();
		}
	}

	private static List<GifFrames.Frame> decode(StoreItem item, int scale) {
		try (var in = StoreCatalog.openGif(item)) {
			return in == null ? List.of() : GifFrames.decodeCape(in, scale).frames();
		} catch (Throwable e) {
			return List.of();
		}
	}

	/** The frames if they're already decoded (instant), else null. */
	static List<GifFrames.Frame> cachedFrames(String key) {
		return FRAMES.get(key);
	}

	/** Blocking decode - only for code already running off the UI thread. */
	public static List<GifFrames.Frame> frames(CapeEntry cape) {
		return FRAMES.computeIfAbsent("lib:" + cape.id(), key -> decode(cape, CARD_SCALE));
	}

	/** Blocking decode - only for code already running off the UI thread. */
	static List<GifFrames.Frame> frames(StoreItem item) {
		return FRAMES.computeIfAbsent("store:" + item.id(), key -> decode(item, CARD_SCALE));
	}

	/** Blocking full-resolution decode for a big 3D viewer - only off the UI thread. */
	public static List<GifFrames.Frame> viewerFrames(CapeEntry cape) {
		return HD_FRAMES.computeIfAbsent("lib:" + cape.id(), key -> decode(cape, HD_SCALE));
	}

	/** Full-resolution frames for a big 3D viewer (see {@link #framesAsync}). */
	public static void viewerFramesAsync(CapeEntry cape, Consumer<List<GifFrames.Frame>> done) {
		load(HD_FRAMES, "lib:" + cape.id(), () -> decode(cape, HD_SCALE), done);
	}

	static void viewerFramesAsync(StoreItem item, Consumer<List<GifFrames.Frame>> done) {
		load(HD_FRAMES, "store:" + item.id(), () -> decode(item, HD_SCALE), done);
	}

	/**
	 * Decodes a library cape's frames off the UI thread; {@code done} runs on the FX thread (at once if
	 * they're cached already). Several waiting for the same cape share one decode.
	 */
	public static void framesAsync(CapeEntry cape, Consumer<List<GifFrames.Frame>> done) {
		load(FRAMES, "lib:" + cape.id(), () -> decode(cape, CARD_SCALE), done);
	}

	static void framesAsync(StoreItem item, Consumer<List<GifFrames.Frame>> done) {
		load(FRAMES, "store:" + item.id(), () -> decode(item, CARD_SCALE), done);
	}

	private static void load(Map<String, List<GifFrames.Frame>> cache, String key, java.util.function.Supplier<List<GifFrames.Frame>> decode,
			Consumer<List<GifFrames.Frame>> done) {
		List<GifFrames.Frame> cached = cache.get(key);
		if (cached != null) {
			done.accept(cached);
			return;
		}
		boolean first;
		String waitKey = (cache == HD_FRAMES ? "hd:" : "") + key;
		synchronized (WAITING) {
			List<Consumer<List<GifFrames.Frame>>> waiting = WAITING.get(waitKey);
			first = waiting == null;
			if (first) {
				waiting = new java.util.ArrayList<>();
				WAITING.put(waitKey, waiting);
			}
			waiting.add(done);
		}
		if (!first) {
			return;
		}
		DECODER.execute(() -> {
			List<GifFrames.Frame> frames = decode.get();
			// A failed decode isn't cached: it may work next time (the file might still be downloading).
			if (!frames.isEmpty()) {
				cache.put(key, frames);
			}
			List<Consumer<List<GifFrames.Frame>>> waiting;
			synchronized (WAITING) {
				waiting = WAITING.remove(waitKey);
			}
			Platform.runLater(() -> {
				if (waiting != null) {
					waiting.forEach(c -> c.accept(frames));
				}
			});
		});
	}

	/** A small spinning arc for "loading" - runs only while it's on screen. */
	public static Node spinner(double size) {
		javafx.scene.shape.Arc arc = new javafx.scene.shape.Arc(0, 0, size / 2, size / 2, 0, 270);
		arc.setType(javafx.scene.shape.ArcType.OPEN);
		arc.setFill(Color.TRANSPARENT);
		arc.setStroke(Color.web("#ffffff99"));
		arc.setStrokeWidth(Math.max(2, size / 9));
		arc.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
		javafx.animation.RotateTransition spin = new javafx.animation.RotateTransition(javafx.util.Duration.millis(900), arc);
		spin.setByAngle(360);
		spin.setInterpolator(javafx.animation.Interpolator.LINEAR);
		spin.setCycleCount(javafx.animation.Animation.INDEFINITE);
		StackPane box = new StackPane(arc);
		box.setMinSize(size + 4, size + 4);
		box.setMaxSize(size + 4, size + 4);
		box.sceneProperty().addListener((obs, oldScene, scene) -> {
			if (scene != null) {
				spin.play();
			} else {
				spin.stop();
			}
		});
		box.setMouseTransparent(true);
		return box;
	}

	/** A 3D-rendered cape image (cached per cape and size). FX thread. */
	static Image thumbnail(String key, List<GifFrames.Frame> frames, double width, double height) {
		if (frames.isEmpty()) {
			return null;
		}
		return THUMBNAILS.computeIfAbsent(key + "@" + (int) width + "x" + (int) height,
				k -> PlayerSkin3DView.capeThumbnail(frames.get(0).image(), width, height));
	}

	/** One frame of an animated cape as a 3D thumbnail, rendered on first use and cached. FX thread. */
	static Image frameThumbnail(String key, List<GifFrames.Frame> frames, int index, double width, double height) {
		if (index == 0) {
			return thumbnail(key, frames, width, height);
		}
		Image[] rendered = FRAME_THUMBNAILS.computeIfAbsent(key + "@" + (int) width + "x" + (int) height, k -> new Image[frames.size()]);
		if (index >= rendered.length) {
			return thumbnail(key, frames, width, height);
		}
		if (rendered[index] == null) {
			rendered[index] = PlayerSkin3DView.capeThumbnail(frames.get(index).image(), width, height);
		}
		return rendered[index];
	}

	/**
	 * Plays an animated cape's frames in {@code view} while the mouse is over {@code hoverTarget}
	 * (or all the time when {@code always}), back to the first frame afterwards. Static capes are
	 * left alone.
	 */
	static void animate(javafx.scene.Node hoverTarget, ImageView view, String key, List<GifFrames.Frame> frames,
			double width, double height, boolean always) {
		if (frames.size() < 2) {
			return;
		}
		int total = 0;
		for (GifFrames.Frame frame : frames) {
			total += Math.max(20, frame.delayMillis());
		}
		int loopMillis = total;
		long[] start = {0};
		int[] shown = {0};
		javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
			@Override
			public void handle(long now) {
				if (start[0] == 0) {
					start[0] = now;
				}
				long into = ((now - start[0]) / 1_000_000L) % loopMillis;
				int index = 0;
				for (int i = 0; i < frames.size(); i++) {
					into -= Math.max(20, frames.get(i).delayMillis());
					if (into < 0) {
						index = i;
						break;
					}
				}
				if (index != shown[0]) {
					shown[0] = index;
					view.setImage(frameThumbnail(key, frames, index, width, height));
				}
			}
		};
		if (always) {
			// Only while it's actually on screen, so leaving the page doesn't keep a timer running.
			view.sceneProperty().addListener((obs, oldScene, scene) -> {
				if (scene != null) {
					timer.start();
				} else {
					timer.stop();
				}
			});
			if (view.getScene() != null) {
				timer.start();
			}
			return;
		}
		hoverTarget.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> {
			start[0] = 0;
			timer.start();
		});
		hoverTarget.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> {
			timer.stop();
			shown[0] = 0;
			view.setImage(thumbnail(key, frames, width, height));
		});
	}

	/** The account's skin, fetched once and cached; {@code onReady} runs on the FX thread (null if unavailable). */
	/** Forgets the cached account skin (after equipping a new one). */
	static void forgetSkin() {
		skin = null;
		skinOwner = null;
	}

	static void skin(MinecraftSession session, Consumer<SkinFetcher.SkinData> onReady) {
		if (session == null) {
			onReady.accept(null);
			return;
		}
		if (session.uuid().equals(skinOwner) && skin != null) {
			onReady.accept(skin);
			return;
		}
		CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(session), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(data -> Platform.runLater(() -> {
					if (data != null) {
						skinOwner = session.uuid();
						skin = data;
					}
					onReady.accept(data);
				}));
	}

	// ---- Visual pieces ----

	/** A small gold coin (the Velo Coins icon). */
	static Node coin(double size) {
		Circle disc = new Circle(size / 2);
		disc.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.web("#ffe38a")), new Stop(0.55, Color.web("#f4b82e")), new Stop(1, Color.web("#c9861a"))));
		disc.setStroke(Color.web("#a86a10"));
		disc.setStrokeWidth(Math.max(1, size / 14));
		Label mark = new Label("V");
		mark.setStyle("-fx-font-size: " + Math.round(size * 0.55) + "px; -fx-font-weight: 900; -fx-text-fill: #7a4a06;");
		StackPane coin = new StackPane(disc, mark);
		coin.setMinSize(size, size);
		coin.setMaxSize(size, size);
		return coin;
	}

	/** "<coin> 250" chip, or a green "Owned" chip. */
	static Node priceChip(int coins, boolean owned) {
		if (owned) {
			Label label = new Label("Owned");
			label.getStyleClass().addAll("chip", "chip-owned");
			return label;
		}
		Label amount = new Label(String.valueOf(coins));
		amount.getStyleClass().add("chip-amount");
		HBox chip = new HBox(6, coin(15), amount);
		chip.setAlignment(Pos.CENTER_LEFT);
		chip.getStyleClass().add("chip");
		return chip;
	}

	/** The lit "stage" a cape stands on: rounded panel with an accent glow from below. */
	static StackPane stage(double width, double height) {
		StackPane stage = new StackPane();
		stage.getStyleClass().add("cape-stage");
		stage.setPrefSize(width, height);
		stage.setMinSize(width, height);
		stage.setMaxSize(width, height);
		return stage;
	}

	/**
	 * A cape card: 3D cape on a stage, a title, and a footer node (price chip / badge). The card
	 * lifts on hover (UiMotion) and shows a ring when {@code selected}.
	 */
	static VBox capeCard(Image thumbnail, String title, Node footer, boolean selected, Runnable onClick) {
		return capeCard(null, null, thumbnail, title, footer, selected, onClick);
	}

	/**
	 * A cape card that shows at once with a spinner and fills in when {@code loader} delivers the frames
	 * (decoded off the UI thread). The animated cape plays while hovered.
	 */
	static VBox capeCardLazy(String key, java.util.function.Consumer<Consumer<List<GifFrames.Frame>>> loader, String title, Node footer,
			boolean selected, Runnable onClick) {
		StackPane stage = stage(150, 150);
		stage.getChildren().add(spinner(26));
		Label name = new Label(title);
		name.getStyleClass().add("cape-card-title");
		name.setMaxWidth(150);
		VBox card = new VBox(8, stage, name);
		if (footer != null) {
			card.getChildren().add(footer);
		}
		card.getStyleClass().addAll("cape-card", "motion-card");
		if (selected) {
			card.getStyleClass().add("cape-card-selected");
		}
		card.setOnMouseClicked(e -> onClick.run());
		loader.accept(frames -> {
			if (frames.isEmpty()) {
				stage.getChildren().clear();
				return;
			}
			ImageView view = new ImageView(thumbnail(key, frames, 150, 150));
			view.setFitWidth(130);
			view.setFitHeight(140);
			view.setPreserveRatio(true);
			view.setOpacity(0);
			stage.getChildren().setAll(view);
			javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(220), view);
			fade.setToValue(1);
			fade.play();
			animate(card, view, key, frames, 150, 150, false);
		});
		return card;
	}

	/** A cape card whose animated cape plays while hovered. */
	static VBox capeCard(String key, List<GifFrames.Frame> frames, String title, Node footer, boolean selected, Runnable onClick) {
		return capeCard(key, frames, thumbnail(key, frames, 150, 150), title, footer, selected, onClick);
	}

	private static VBox capeCard(String key, List<GifFrames.Frame> frames, Image thumbnail, String title, Node footer, boolean selected,
			Runnable onClick) {
		StackPane stage = stage(150, 150);
		ImageView view = null;
		if (thumbnail != null) {
			view = new ImageView(thumbnail);
			view.setFitWidth(130);
			view.setFitHeight(140);
			view.setPreserveRatio(true);
			stage.getChildren().add(view);
		}
		Label name = new Label(title);
		name.getStyleClass().add("cape-card-title");
		name.setMaxWidth(150);
		VBox card = new VBox(8, stage, name);
		if (footer != null) {
			card.getChildren().add(footer);
		}
		card.getStyleClass().addAll("cape-card", "motion-card");
		if (selected) {
			card.getStyleClass().add("cape-card-selected");
		}
		card.setOnMouseClicked(e -> onClick.run());
		if (view != null && key != null && frames != null) {
			animate(card, view, key, frames, 150, 150, false);
		}
		return card;
	}

	static Label badge(String text, String extraClass) {
		Label badge = new Label(text);
		badge.getStyleClass().add("chip");
		if (extraClass != null) {
			badge.getStyleClass().add(extraClass);
		}
		return badge;
	}
}
