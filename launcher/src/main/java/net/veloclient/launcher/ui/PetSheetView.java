package net.veloclient.launcher.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Duration;
import net.veloclient.launcher.data.PetsSetting;
import net.veloclient.launcher.data.WingsCatalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A pet's Store preview: its still picture, and its idle turntable (one sprite sheet of 8 x 6 frames from the server)
 * when animated. Light on purpose: sheets are decoded scaled down and at most a few are kept (LRU); all playing views
 * share one 16 fps timer that only runs while one is on screen; tiles play only while hovered.
 */
final class PetSheetView extends ImageView {

	private static final int COLUMNS = 8;
	private static final int ROWS = 6;
	private static final int FRAMES = COLUMNS * ROWS;
	private static final int MAX_SHEETS = 4;
	private static final Map<String, Image> SHEETS = new LinkedHashMap<>(8, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
			return size() > MAX_SHEETS;
		}
	};
	private static final Set<PetSheetView> PLAYING = java.util.Collections.newSetFromMap(new WeakHashMap<>());
	private static Timeline timer;
	/**
	 * Downloads, one or two at a time so a slow connection isn't split twenty ways: still pictures jump the queue, the
	 * animations follow in the background - by the time you hover a tile its sheet is usually already on disk.
	 */
	private static final java.util.concurrent.PriorityBlockingQueue<Job> QUEUE = new java.util.concurrent.PriorityBlockingQueue<>();
	private static final java.util.concurrent.atomic.AtomicLong ORDER = new java.util.concurrent.atomic.AtomicLong();

	private record Job(int priority, long order, Runnable work) implements Comparable<Job> {
		@Override
		public int compareTo(Job o) {
			return priority != o.priority ? Integer.compare(priority, o.priority) : Long.compare(order, o.order);
		}
	}

	static {
		for (int i = 0; i < 2; i++) {
			Thread.ofVirtual().start(() -> {
				while (true) {
					try {
						QUEUE.take().work().run();
					} catch (InterruptedException e) {
						return;
					} catch (RuntimeException ignored) {
						// one failed download doesn't stop the rest
					}
				}
			});
		}
	}

	/** 0 = needed now (a hover, the item page), 1 = still pictures, 2 = sheets fetched ahead. */
	private static void queue(int priority, Runnable work) {
		QUEUE.add(new Job(priority, ORDER.incrementAndGet(), work));
	}

	private final PetsSetting.StorePet pet;
	private final double size;
	private Image still;
	private boolean playing;

	/** {@code alwaysPlay}: the item page (plays while shown); tiles play only while hovered. */
	PetSheetView(PetsSetting.StorePet pet, double size, boolean alwaysPlay) {
		this.pet = pet;
		this.size = size;
		setFitWidth(size);
		setFitHeight(size);
		setPreserveRatio(true);
		setSmooth(true);
		setMouseTransparent(true);
		loadStill();
		if (pet.sheetSha() != null) {
			queue(2, () -> WingsCatalog.file(pet.sheetSha()));   // on disk for the hover later
		}
		if (alwaysPlay) {
			sceneProperty().addListener((o, was, now) -> play(now != null));
		}
	}

	private void loadStill() {
		if (pet.previewSha() == null) {
			return;
		}
		queue(1, () -> {
			java.nio.file.Path file = WingsCatalog.file(pet.previewSha());
			if (file != null) {
				Image image = new Image(file.toUri().toString(), size * 2, size * 2, true, true);
				Platform.runLater(() -> {
					still = image;
					if (!playing) {
						setImage(image);
						setViewport(null);
					}
				});
			}
		});
	}

	/** Starts or stops the turntable (the still picture shows while it's stopped or the sheet is loading). */
	void play(boolean on) {
		if (on == playing) {
			return;
		}
		playing = on;
		if (!on) {
			PLAYING.remove(this);
			setImage(still);
			setViewport(null);
			stopTimerIfIdle();
			return;
		}
		Image sheet = SHEETS.get(sheetKey());
		if (sheet != null) {
			start(sheet);
			return;
		}
		if (pet.sheetSha() == null) {
			return;
		}
		queue(0, () -> {
			java.nio.file.Path file = WingsCatalog.file(pet.sheetSha());
			if (file == null) {
				return;
			}
			double frame = frameSize();
			Image image = new Image(file.toUri().toString(), frame * COLUMNS, frame * ROWS, true, true);
			Platform.runLater(() -> {
				SHEETS.put(sheetKey(), image);
				if (playing) {
					start(image);
				}
			});
		});
	}

	/** Tiles decode the sheet only as large as they draw it; big views (the item page) get every pixel there is. */
	private double frameSize() {
		return size >= 150 ? 176 : Math.min(176, Math.ceil(size * 1.5));
	}

	private String sheetKey() {
		return pet.pet().id() + "@" + (int) frameSize();
	}

		private void start(Image sheet) {
		setImage(sheet);
		PLAYING.add(this);
		step();
		if (timer == null) {
			timer = new Timeline(new KeyFrame(Duration.millis(1000.0 / 16), e -> {
				for (PetSheetView view : java.util.List.copyOf(PLAYING)) {
					if (view.getScene() == null) {
						PLAYING.remove(view);
					} else {
						view.step();
					}
				}
				stopTimerIfIdle();
			}));
			timer.setCycleCount(Timeline.INDEFINITE);
		}
		timer.play();
	}

	private void step() {
		Image sheet = getImage();
		if (sheet == null || sheet.getWidth() <= 0) {
			return;
		}
		int frame = (int) (System.currentTimeMillis() * 16 / 1000 % FRAMES);
		double w = sheet.getWidth() / COLUMNS;
		double h = sheet.getHeight() / ROWS;
		setViewport(new Rectangle2D((frame % COLUMNS) * w, (frame / COLUMNS) * h, w, h));
	}

	private static void stopTimerIfIdle() {
		if (timer != null && PLAYING.isEmpty()) {
			timer.stop();
		}
	}
}
