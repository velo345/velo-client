package net.veloclient.launcher.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The bar along the bottom of every page: which profile Play starts (click to switch), a line about friends,
 * and the big split Play button. While a launch prepares, the button itself fills up as a progress bar; while
 * the selected profile runs, a "Running" box with a timer and Stop sits next to it.
 */
public final class LaunchBar extends HBox {

	public interface Host {
		void openProfileSwitcher(Node anchor);

		void openProfileOptions();

		/** Play pressed - the host drives {@code progress} / {@code status} through the launch. */
		void play(Button trigger, ProgressBar progress, Label status);

		void openPlayMenu(Node anchor);

		/** Closes the running game properly and starts it again (after changing mods, for instance). */
		default void relaunch() {
		}
	}

	/** What the bar shows for the current target. */
	public record Target(Node icon, String name, String meta, String playLabel, boolean canPlayMenu) {
	}

	private final StackPane iconBox = new StackPane();
	private final Label name = new Label();
	private final Label meta = new Label();
	private final Label social = new Label();
	private final HBox socialBox = new HBox(10);
	private final Label statusText = new Label();
	private final Button play = new Button();
	private final Button chevron = new Button();
	private final HBox playSplit;
	private final StackPane playArea;
	private final StackPane progressView;
	private final Region progressFill = new Region();
	private final Label progressLabel = new Label();
	private final ProgressBar progress = new ProgressBar(0);
	private final Label status = new Label();
	private final HBox runningBox = new HBox(10);
	private final Label runningText = new Label();
	private final Button stopButton = new Button("Stop");
	private final Button relaunchButton = new Button("Relaunch");
	private final HBox statusBox;
	private Timeline runningTimer;
	private long runningSince;
	private Runnable stopAction;

	public LaunchBar(Host host) {
		getStyleClass().add("launchbar");
		setAlignment(Pos.CENTER_LEFT);
		setSpacing(16);

		iconBox.setMinSize(44, 44);
		iconBox.setMaxSize(44, 44);
		Rectangle clip = new Rectangle(44, 44);
		clip.setArcWidth(20);
		clip.setArcHeight(20);
		iconBox.setClip(clip);
		name.getStyleClass().add("profile-switcher-name");
		meta.getStyleClass().add("profile-switcher-meta");
		name.setMinWidth(Region.USE_PREF_SIZE);
		meta.setMinWidth(Region.USE_PREF_SIZE);
		VBox text = new VBox(3, name, meta);
		text.setAlignment(Pos.CENTER_LEFT);
		HBox switcherContent = new HBox(12, iconBox, text, Icons.icon("chevrons-updown", 16, Color.web("#8A8A95")));
		switcherContent.setAlignment(Pos.CENTER_LEFT);
		Button switcher = new Button();
		switcher.setGraphic(switcherContent);
		switcher.getStyleClass().setAll("button", "profile-switcher");
		switcher.setTooltip(new Tooltip("Switch profile"));
		switcher.setOnAction(e -> host.openProfileSwitcher(switcher));
		switcher.setMinWidth(Region.USE_PREF_SIZE);

		Region divider = new Region();
		divider.getStyleClass().add("hairline-v");
		divider.setMinHeight(30);
		divider.setMaxHeight(30);

		social.getStyleClass().add("t-body");
		social.setStyle("-fx-font-size: 13px;");
		socialBox.setAlignment(Pos.CENTER_LEFT);
		socialBox.getChildren().add(social);

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		Region dot = new Region();
		dot.getStyleClass().add("status-dot");
		statusText.getStyleClass().add("t-muted");
		statusBox = new HBox(8, dot, statusText);
		statusBox.setAlignment(Pos.CENTER_LEFT);
		// No dot without a status (while the game runs, the Running box has its own).
		statusBox.visibleProperty().bind(statusText.textProperty().isNotEmpty());
		statusBox.managedProperty().bind(statusBox.visibleProperty());

		Region runDot = new Region();
		runDot.getStyleClass().add("status-dot");
		runDot.setStyle("-fx-effect: dropshadow(gaussian, #3ddc97, 8, 0.3, 0, 0);");
		runningText.getStyleClass().add("t-strong");
		stopButton.getStyleClass().setAll("button", "button-sm", "danger-button");
		stopButton.setOnAction(e -> {
			if (stopAction != null) {
				stopAction.run();
			}
		});
		runningBox.getStyleClass().add("running-box");
		runningBox.setAlignment(Pos.CENTER_LEFT);
		relaunchButton.getStyleClass().setAll("button", "button-sm", "ghost-button");
		relaunchButton.setGraphic(Icons.icon("refresh", 13, Color.web("#E4E4E8")));
		relaunchButton.setMinWidth(Region.USE_PREF_SIZE);
		relaunchButton.setTooltip(new Tooltip("Close the game properly and start it again - after changing mods or settings"));
		relaunchButton.setOnAction(e -> host.relaunch());
		stopButton.setMinWidth(Region.USE_PREF_SIZE);
		runningText.setMinWidth(Region.USE_PREF_SIZE);
		runningBox.getChildren().addAll(runDot, runningText, relaunchButton, stopButton);
		runningBox.setVisible(false);
		runningBox.setManaged(false);

		Button options = new Button("Mods & settings");
		options.setGraphic(Icons.icon("box", 17, Color.web("#C9C9D1")));
		options.setGraphicTextGap(9);
		options.getStyleClass().setAll("button", "ghost-button");
		options.setMinHeight(50);
		options.setMinWidth(Region.USE_PREF_SIZE);
		options.setStyle("-fx-background-radius: 12; -fx-border-radius: 12; -fx-padding: 0 16 0 14;");
		options.setTooltip(new Tooltip("This profile's mods, resource packs, worlds and settings"));
		options.setOnAction(e -> host.openProfileOptions());

		play.getStyleClass().setAll("button", "play-button");
		play.setGraphic(Icons.icon("play", 16, Color.WHITE));
		play.setGraphicTextGap(12);
		play.setMinWidth(Region.USE_PREF_SIZE);
		play.setOnAction(e -> host.play(play, progress, status));
		chevron.getStyleClass().setAll("button", "play-chevron");
		chevron.setGraphic(Icons.icon("chevron-up", 15, Color.WHITE, 2.4));
		chevron.setTooltip(new Tooltip("Play into a server directly"));
		chevron.setOnAction(e -> host.openPlayMenu(chevron));
		playSplit = new HBox(play, chevron);
		playSplit.setAlignment(Pos.CENTER);
		playSplit.setStyle("-fx-effect: dropshadow(gaussian, -velo-accent-glow, 22, 0.05, 0, 6);");

		// Launch progress: the same footprint as the Play button, filling from left to right.
		Region track = new Region();
		track.getStyleClass().add("play-progress-track");
		progressFill.getStyleClass().add("play-progress-fill");
		progressFill.setMaxWidth(0);
		StackPane.setAlignment(progressFill, Pos.CENTER_LEFT);
		progressLabel.getStyleClass().add("play-progress-label");
		status.getStyleClass().add("play-progress-sub");
		status.setMaxWidth(230);
		VBox progressText = new VBox(1, progressLabel, status);
		progressText.setAlignment(Pos.CENTER);
		progressView = new StackPane(track, progressFill, progressText);
		progressView.setMinHeight(50);
		Rectangle progressClip = new Rectangle();
		progressClip.setArcWidth(24);
		progressClip.setArcHeight(24);
		progressClip.widthProperty().bind(progressView.widthProperty());
		progressClip.heightProperty().bind(progressView.heightProperty());
		progressView.setClip(progressClip);
		progressView.setVisible(false);
		progressFill.maxWidthProperty().bind(progressView.widthProperty().multiply(progress.progressProperty()));
		progressFill.prefWidthProperty().bind(progressFill.maxWidthProperty());
		progressLabel.setText("PREPARING");
		progress.visibleProperty().addListener((o, a, visible) -> {
			progressView.setVisible(visible);
			playSplit.setVisible(!visible);
		});
		playArea = new StackPane(playSplit, progressView);
		playArea.setMinWidth(260);

		getChildren().addAll(switcher, divider, socialBox, spacer, statusBox, runningBox, options, playArea);
	}

	public void setTarget(Target target) {
		iconBox.getChildren().setAll(target.icon() == null ? new Region() : target.icon());
		name.setText(target.name());
		meta.setText(target.meta());
		play.setText(target.playLabel());
		boolean menu = target.canPlayMenu();
		chevron.setVisible(menu);
		chevron.setManaged(menu);
		play.getStyleClass().remove("play-button-solo");
		if (!menu) {
			play.getStyleClass().add("play-button-solo");
		}
	}

	public void setSocialLine(String text, Node... heads) {
		socialBox.getChildren().setAll();
		if (heads.length > 0) {
			HBox stack = new HBox(-6);
			stack.getChildren().addAll(heads);
			socialBox.getChildren().add(stack);
		}
		social.setText(text == null ? "" : text);
		socialBox.getChildren().add(social);
	}

	public void setStatus(String text) {
		statusText.setText(text);
	}

	/** Shows the "Running" box for the selected profile ({@code since} = epoch millis), or hides it with {@code null}. */
	/** While a relaunch waits for the game to close. */
	public void setRelaunching(boolean busy) {
		relaunchButton.setDisable(busy);
		stopButton.setDisable(busy);
		relaunchButton.setText(busy ? "Closing..." : "Relaunch");
	}

	public void setRunning(Long since, Runnable stop) {
		this.stopAction = stop;
		setRelaunching(false);
		boolean running = since != null;
		runningBox.setVisible(running);
		runningBox.setManaged(running);
		if (runningTimer != null) {
			runningTimer.stop();
		}
		if (!running) {
			return;
		}
		runningSince = since;
		Runnable tick = () -> {
			long s = Math.max(0, (System.currentTimeMillis() - runningSince) / 1000);
			runningText.setText("Running  " + (s >= 3600 ? (s / 3600) + ":" : "") + String.format("%02d:%02d", (s % 3600) / 60, s % 60));
		};
		tick.run();
		runningTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick.run()));
		runningTimer.setCycleCount(Timeline.INDEFINITE);
		runningTimer.play();
	}

	public Button playButton() {
		return play;
	}

	public ProgressBar progress() {
		return progress;
	}

	public Label statusLabel() {
		return status;
	}

	public void setPlayInsets(Insets insets) {
		HBox.setMargin(playArea, insets);
	}
}
