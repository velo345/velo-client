package net.veloclient.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.velo.client.clips.core.ClipTools;
import net.veloclient.velo.client.clips.core.VideoPlayer;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The launcher's clip player (Clips Pro .mp4 clips, with their sound): the same FFmpeg player the game uses
 * ({@link VideoPlayer}), drawn into a {@link WritableImage}. One control row - play/pause, the time, a timeline and the
 * volume. Trimming is the timeline's two end handles: drag them in and only that part plays; {@link #onTrimChanged}
 * tells the page to offer "Save trimmed" / "Share part". Space plays/pauses, the arrow keys jump 5 seconds, M mutes.
 */
public final class ClipPlayerView extends VBox {

	private static final String ACCENT = "#E0262E";

	private final StackPane stage = new StackPane();
	private final ImageView picture = new ImageView();
	private final Label message = new Label("Loading clip...");
	private final Button play = new Button();
	private final Label time = new Label("0:00 / 0:00");
	private final Pane timeline = new Pane();
	private final Region track = new Region();
	private final Region kept = new Region();
	private final Region played = new Region();
	private final Circle head = new Circle(5, Color.WHITE);
	private final Region startHandle = handle();
	private final Region endHandle = handle();
	private final Label tip = new Label();
	private final Button mute = new Button();
	private final Slider volume = new Slider(0, 1, 0.8);
	private volatile VideoPlayer player;
	private WritableImage image;
	private AnimationTimer timer;
	private double trimFrom;
	private double trimTo = -1;
	private Runnable onTrimChanged = () -> { };
	private boolean released;
	private Boolean shownPlaying;

	public ClipPlayerView(Path clip) {
		super(10);
		picture.setPreserveRatio(true);
		picture.setSmooth(true);
		message.setStyle("-fx-text-fill: #9aa0b0;");
		stage.getChildren().addAll(picture, message);
		stage.setStyle("-fx-background-color: #07070a; -fx-background-radius: 12;");
		stage.setMinSize(0, 0);
		picture.fitWidthProperty().bind(stage.widthProperty().subtract(8));
		picture.fitHeightProperty().bind(stage.heightProperty().subtract(8));
		stage.setOnMouseClicked(e -> toggle());
		stage.setCursor(Cursor.HAND);
		VBox.setVgrow(stage, Priority.ALWAYS);

		play.getStyleClass().add("primary-button");
		play.setGraphic(Icons.icon("pause", 14, Color.WHITE));
		play.setStyle("-fx-background-radius: 999; -fx-min-width: 34; -fx-min-height: 34; -fx-padding: 0;");
		play.setOnAction(e -> toggle());
		time.getStyleClass().add("t-mono");
		time.setMinWidth(Region.USE_PREF_SIZE);

		track.setStyle("-fx-background-color: #2a2a33; -fx-background-radius: 3;");
		kept.setStyle("-fx-background-color: #4a4a57; -fx-background-radius: 3;");
		played.setStyle("-fx-background-color: " + ACCENT + "; -fx-background-radius: 3;");
		tip.setStyle("-fx-background-color: #101014; -fx-text-fill: white; -fx-padding: 2 6; -fx-background-radius: 5; -fx-font-size: 11;");
		tip.setVisible(false);
		tip.setManaged(false);
		for (Region r : new Region[] {track, kept, played}) {
			r.setManaged(false);
			r.setMouseTransparent(true);
		}
		head.setManaged(false);
		head.setMouseTransparent(true);
		timeline.getChildren().addAll(track, kept, played, head, startHandle, endHandle, tip);
		timeline.setMinHeight(34);
		timeline.setPrefHeight(34);
		timeline.setCursor(Cursor.HAND);
		HBox.setHgrow(timeline, Priority.ALWAYS);
		timeline.widthProperty().addListener((o, a, b) -> layoutTimeline());
		timeline.setOnMousePressed(e -> {
			VideoPlayer p = player;
			if (p != null && e.getTarget() != startHandle && e.getTarget() != endHandle) {
				p.seek(at(e.getX()));
			}
		});
		timeline.setOnMouseDragged(e -> {
			VideoPlayer p = player;
			if (p != null && e.getTarget() != startHandle && e.getTarget() != endHandle) {
				p.seek(at(e.getX()));
			}
		});
		timeline.setOnMouseMoved(e -> showTip(e.getX(), clock(at(e.getX()))));
		timeline.setOnMouseExited(e -> tip.setVisible(false));
		dragHandle(startHandle, true);
		dragHandle(endHandle, false);

		mute.getStyleClass().add("ghost-button");
		mute.setGraphic(Icons.icon("volume", 16, Color.web("#E4E4E8")));
		mute.setTooltip(new Tooltip("Mute (M)"));
		mute.setOnAction(e -> setMuted(player != null && !player.muted()));
		volume.setPrefWidth(80);
		volume.valueProperty().addListener((o, a, b) -> {
			VideoPlayer p = player;
			if (p != null) {
				p.setMuted(false);
				p.setVolume(b.floatValue());
				updateMute();
			}
		});

		HBox controls = new HBox(12, play, time, timeline, mute, volume);
		controls.setAlignment(Pos.CENTER_LEFT);
		controls.getStyleClass().add("card");
		controls.setPadding(new Insets(6, 14, 6, 8));
		getChildren().addAll(stage, controls);

		setFocusTraversable(true);
		setOnKeyPressed(e -> {
			switch (e.getCode()) {
				case SPACE -> toggle();
				case LEFT -> seekBy(-5);
				case RIGHT -> seekBy(5);
				case M -> setMuted(player != null && !player.muted());
				default -> {
					return;
				}
			}
			e.consume();
		});
		// Leaving the page (another tab, the window) stops the sound with it.
		sceneProperty().addListener((o, was, now) -> {
			if (now == null && was != null) {
				release();
			}
		});
		open(clip.toString());
	}

	private static Region handle() {
		Region r = new Region();
		r.setStyle("-fx-background-color: #E4E4E8; -fx-background-radius: 3; -fx-border-color: #6a6a78; -fx-border-width: 0 0 0 0;");
		r.setPrefSize(8, 22);
		r.setMinSize(8, 22);
		r.setMaxSize(8, 22);
		r.setManaged(false);
		r.setCursor(Cursor.H_RESIZE);
		r.setOnMouseEntered(e -> r.setStyle("-fx-background-color: white; -fx-background-radius: 3;"));
		r.setOnMouseExited(e -> r.setStyle("-fx-background-color: #E4E4E8; -fx-background-radius: 3;"));
		return r;
	}

	/** Runs when a trim handle is dropped somewhere new. */
	public void onTrimChanged(Runnable listener) {
		onTrimChanged = listener;
	}

	public boolean trimmed() {
		VideoPlayer p = player;
		return p != null && trimTo > 0 && (trimFrom > 0.05 || trimTo < p.duration() - 0.05);
	}

	public double trimFrom() {
		return trimFrom;
	}

	public double trimTo() {
		VideoPlayer p = player;
		return trimTo > 0 ? trimTo : p == null ? 0 : p.duration();
	}

	public void resetTrim() {
		VideoPlayer p = player;
		if (p != null) {
			trimFrom = 0;
			trimTo = p.duration();
			p.setRange(0, p.duration());
			layoutTimeline();
			onTrimChanged.run();
		}
	}

	/** The FFmpeg the launcher uses (Velo's own download, else the PATH), or null. */
	public static Path ffmpeg() {
		return ClipTools.findFfmpeg(VeloPaths.root().resolve("tools").resolve("ffmpeg"));
	}

	/** Makes sure FFmpeg is there (downloads it once), off the UI thread; {@code done} gets null or the problem, on the UI thread. */
	public static void ensureFfmpeg(Consumer<String> done) {
		Thread.ofVirtual().name("velo-ffmpeg").start(() -> {
			String problem = null;
			if (ffmpeg() == null) {
				try {
					ClipTools.downloadFfmpeg(VeloPaths.root().resolve("tools").resolve("ffmpeg"));
					if (ffmpeg() == null) {
						problem = "The downloaded FFmpeg doesn't run on this PC";
					}
				} catch (Exception e) {
					LauncherLog.warn("Couldn't download FFmpeg", e);
					problem = "Couldn't download FFmpeg: " + e.getMessage();
				}
			}
			String result = problem;
			Platform.runLater(() -> done.accept(result));
		});
	}

	private void open(String source) {
		if (ffmpeg() == null) {
			message.setText("Getting FFmpeg for the player (once)...");
		}
		ensureFfmpeg(problem -> {
			if (problem != null) {
				message.setText(problem);
				return;
			}
			Thread.ofVirtual().name("velo-clip-open").start(() -> {
				try {
					VideoPlayer opened = VideoPlayer.open(ffmpeg(), source, 1600, VideoPlayer.Pixels.BGRA);
					Platform.runLater(() -> {
						if (released) {
							opened.release();
							return;
						}
						player = opened;
						trimTo = opened.duration();
						image = new WritableImage(opened.width(), opened.height());
						picture.setImage(image);
						opened.setVolume((float) volume.getValue());
						opened.play();
						startTimer();
					});
				} catch (Exception e) {
					Platform.runLater(() -> message.setText("Couldn't open this clip: " + e.getMessage()));
				}
			});
		});
	}

	private void startTimer() {
		timer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				VideoPlayer p = player;
				if (p == null) {
					return;
				}
				VideoPlayer.Frame frame = p.frame();
				if (frame != null) {
					message.setVisible(false);
					image.getPixelWriter().setPixels(0, 0, frame.width, frame.height, PixelFormat.getByteBgraPreInstance(), frame.data, 0, frame.width * 4);
					p.recycle(frame);
				}
				if (p.error() != null) {
					message.setVisible(true);
					message.setText(p.error());
				}
				time.setText(clock(p.position()) + " / " + clock(p.duration()));
				if (shownPlaying == null || shownPlaying != p.playing()) {
					shownPlaying = p.playing();
					play.setGraphic(Icons.icon(p.playing() ? "pause" : "play", 14, Color.WHITE));
				}
				layoutTimeline();
			}
		};
		timer.start();
	}

	private void layoutTimeline() {
		double w = timeline.getWidth();
		double h = timeline.getHeight();
		double cy = h / 2;
		VideoPlayer p = player;
		double duration = p == null ? 0 : p.duration();
		track.resizeRelocate(0, cy - 3, w, 6);
		if (duration <= 0) {
			kept.resizeRelocate(0, cy - 3, w, 6);
			played.resizeRelocate(0, cy - 3, 0, 6);
			startHandle.relocate(-4, cy - 11);
			endHandle.relocate(w - 4, cy - 11);
			return;
		}
		double from = w * trimFrom / duration;
		double to = w * trimTo() / duration;
		double at = w * Math.min(1, p.position() / duration);
		kept.resizeRelocate(from, cy - 3, Math.max(2, to - from), 6);
		played.resizeRelocate(from, cy - 3, Math.max(0, Math.min(at, to) - from), 6);
		head.setCenterX(at);
		head.setCenterY(cy);
		startHandle.relocate(from - 4, cy - 11);
		endHandle.relocate(to - 4, cy - 11);
	}

	private void dragHandle(Region handle, boolean start) {
		handle.setOnMousePressed(e -> {
			VideoPlayer p = player;
			if (p != null) {
				p.pause();
			}
			e.consume();
		});
		handle.setOnMouseDragged(e -> {
			VideoPlayer p = player;
			if (p == null) {
				return;
			}
			double x = handle.localToParent(e.getX(), e.getY()).getX();
			double t = at(x);
			if (start) {
				trimFrom = Math.max(0, Math.min(trimTo() - 0.5, t));
			} else {
				trimTo = Math.min(p.duration(), Math.max(trimFrom + 0.5, t));
			}
			showTip(x, (start ? "Start " : "End ") + clock(start ? trimFrom : trimTo()) + "  ·  " + clock(trimTo() - trimFrom));
			layoutTimeline();
			e.consume();
		});
		handle.setOnMouseReleased(e -> {
			VideoPlayer p = player;
			if (p != null) {
				p.setRange(trimFrom, trimTo());
				p.seek(start ? trimFrom : Math.max(trimFrom, trimTo() - 1));
			}
			tip.setVisible(false);
			onTrimChanged.run();
			e.consume();
		});
	}

	private void showTip(double x, String text) {
		tip.setText(text);
		tip.setVisible(true);
		tip.autosize();
		tip.relocate(Math.max(0, Math.min(timeline.getWidth() - tip.getWidth(), x - tip.getWidth() / 2)), -18);
	}

	private double at(double x) {
		VideoPlayer p = player;
		double w = Math.max(1, timeline.getWidth());
		return p == null ? 0 : Math.max(0, Math.min(1, x / w)) * p.duration();
	}

	private void toggle() {
		VideoPlayer p = player;
		if (p != null) {
			p.toggle();
		}
		requestFocus();
	}

	private void seekBy(double seconds) {
		VideoPlayer p = player;
		if (p != null) {
			p.seek(p.position() + seconds);
		}
	}

	private void setMuted(boolean muted) {
		VideoPlayer p = player;
		if (p != null) {
			p.setMuted(muted);
			updateMute();
		}
	}

	private void updateMute() {
		VideoPlayer p = player;
		boolean off = p == null || p.muted() || p.volume() <= 0.001f;
		mute.setGraphic(Icons.icon(off ? "volume-off" : "volume", 16, Color.web("#E4E4E8")));
	}

	private static String clock(double seconds) {
		int s = (int) Math.floor(Math.max(0, seconds));
		return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	/** Stops playback for good (leaving the page). */
	public void release() {
		released = true;
		if (timer != null) {
			timer.stop();
		}
		VideoPlayer p = player;
		player = null;
		if (p != null) {
			p.release();
		}
	}
}
