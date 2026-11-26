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
	private final Label modsLine = new Label();
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
	private Button switcherButton;
	private Button optionsButton;
	private final Label playTitle = new Label();
	private final Label playSub = new Label();
	private boolean homeMode;
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
		switcherButton = switcher;

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
		dot.getStyleClass().add("status-square");
		statusText.getStyleClass().add("t-muted");
		statusText.setMinWidth(Region.USE_PREF_SIZE);
		statusBox = new HBox(8, dot, statusText);
		statusBox.setAlignment(Pos.CENTER_LEFT);
		// No dot without a status (while the game runs, the Running box has its own).
		statusBox.visibleProperty().bind(statusText.textProperty().isNotEmpty());
		statusBox.managedProperty().bind(statusBox.visibleProperty());

		Region runDot = new Region();
		runDot.getStyleClass().add("status-square");
		runningText.getStyleClass().add("t-strong");
		stopButton.getStyleClass().setAll("button", "button-sm", "danger-button");
		stopButton.setOnAction(e -> {
			if (killMode) {
				if (killAction != null) {
					killAction.run();
				}
				return;
			}
			if (stopAction != null) {
				stopAction.run();
				// Stopped but the game may hang on: the same button now ends it for good.
				showKill();
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

		// "Mods & packs": an icon tile, the title and a count line, then a chevron (2026-11).
		StackPane optionsIcon = new StackPane(Icons.icon("box", 17, Color.web("#F4EFEE")));
		optionsIcon.getStyleClass().add("launch-mods-icon");
		Label optionsTitle = new Label("Mods & packs");
		optionsTitle.getStyleClass().add("launch-mods-title");
		modsLine.getStyleClass().add("launch-mods-line");
		VBox optionsText = new VBox(4, optionsTitle, modsLine);
		optionsText.setAlignment(Pos.CENTER_LEFT);
		HBox optionsContent = new HBox(12, optionsIcon, optionsText, Icons.icon("chevron-right", 14, Color.web("#8A8486"), 2.2));
		optionsContent.setAlignment(Pos.CENTER_LEFT);
		Button options = new Button();
		options.setGraphic(optionsContent);
		options.getStyleClass().setAll("button", "launch-mods");
		options.setMinWidth(Region.USE_PREF_SIZE);
		options.setTooltip(new Tooltip("This profile's mods, resource packs, worlds and settings"));
		options.setOnAction(e -> host.openProfileOptions());
		optionsButton = options;

		play.getStyleClass().setAll("button", "play-button");
		play.setGraphic(Icons.icon("play", 16, Color.WHITE));
		play.setGraphicTextGap(12);
		// Never narrower than its label (CONNECT, RELAUNCH...), never below the 148 px the design asks for.
		play.minWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(() -> Math.max(148, play.prefWidth(-1)), play.textProperty(),
				play.fontProperty()));
		play.setOnAction(e -> host.play(play, progress, status));
		chevron.getStyleClass().setAll("button", "play-chevron");
		chevron.setGraphic(Icons.icon("chevron-up", 15, Color.WHITE, 2.4));
		chevron.setTooltip(new Tooltip("Play into a server directly"));
		chevron.setOnAction(e -> host.openPlayMenu(chevron));
		playSplit = new HBox(play, chevron);
		playSplit.setAlignment(Pos.CENTER);


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
		playArea.setMinWidth(192);

		// The divider and social line only take room when there's something to say.
		divider.visibleProperty().bind(social.textProperty().isNotEmpty());
		divider.managedProperty().bind(divider.visibleProperty());
		socialBox.visibleProperty().bind(social.textProperty().isNotEmpty());
		socialBox.managedProperty().bind(socialBox.visibleProperty());
		getChildren().addAll(switcher, divider, socialBox, spacer, statusBox, runningBox, options, playArea);
	}

	public void setTarget(Target target) {
		iconBox.getChildren().setAll(target.icon() == null ? new Region() : target.icon());
		name.setText(target.name());
		meta.setText(target.meta());
		if (homeMode) {
			String label = target.playLabel().toLowerCase(java.util.Locale.ROOT);
			playTitle.setText(label.isEmpty() ? "" : Character.toUpperCase(label.charAt(0)) + label.substring(1));
			play.setText(null);
		} else {
			play.setText(target.playLabel());
		}
		boolean menu = target.canPlayMenu() && !homeMode;
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
			// The bar stretches its children to its full height: keep the faces in the middle of it, not at the top.
			stack.setAlignment(Pos.CENTER_LEFT);
			stack.setMaxHeight(Region.USE_PREF_SIZE);
			for (Node head : heads) {
				if (head instanceof Region region) {
					region.setMaxHeight(Region.USE_PREF_SIZE);
				}
			}
			socialBox.getChildren().add(stack);
		}
		social.setText(text == null ? "" : text);
		socialBox.getChildren().add(social);
	}

	/** The count line under "Mods & packs" ("74 mods · 3 packs"). */
	public void setModsLine(String text) {
		modsLine.setText(text == null ? "" : text);
		modsLine.setVisible(text != null && !text.isEmpty());
		modsLine.setManaged(modsLine.isVisible());
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

	private Runnable killAction;
	private boolean killMode;

	private void showKill() {
		killMode = killAction != null;
		if (killMode) {
			stopButton.setText("Kill");
			stopButton.setTooltip(new Tooltip("Still running after Stop? End the game process right away (unsaved progress is lost)."));
		}
	}

	public void setRunning(Long since, Runnable stop) {
		setRunning(since, stop, null, false);
	}

	/** {@code stopped}: Stop was already pressed - the button is Kill then (ends the whole process tree). */
	public void setRunning(Long since, Runnable stop, Runnable kill, boolean stopped) {
		this.stopAction = stop;
		this.killAction = kill;
		killMode = false;
		stopButton.setText("Stop");
		stopButton.setTooltip(null);
		if (stopped) {
			showKill();
		}
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

	/** The line under "Play" on Home's play card ("into LuckySMP", "to the title screen"). */
	public void setPlaySub(String text) {
		playSub.setText(text == null ? "" : text);
		playSub.setVisible(text != null && !text.isEmpty());
		playSub.setManaged(playSub.isVisible());
	}

	/**
	 * Home's play card (2026-11-25 glass launcher): the bar's own parts moved into one glass card - the profile switcher
	 * and a clear "Mods & packs" button in one row, the big Play (it fills up as the launch progress), then {@code below}
	 * (the "launch into" chips). The bar itself is no longer shown; its Play, progress and status keep working as before.
	 */
	public javafx.scene.layout.VBox homeCard(Node below) {
		if (homeMode) {
			return rebuildHomeCard(below);
		}
		homeMode = true;
		getChildren().removeAll(switcherButton, optionsButton, playArea);
		switcherButton.getStyleClass().setAll("button", "home-switcher");
		optionsButton.getStyleClass().setAll("button", "home-mods");
		switcherButton.setMaxWidth(Double.MAX_VALUE);
		// both halves of the row as tall as the row (the switcher used to be shorter than Mods & packs)
		switcherButton.setMaxHeight(Double.MAX_VALUE);
		optionsButton.setMaxHeight(Double.MAX_VALUE);
		HBox.setHgrow(switcherButton, Priority.ALWAYS);
		HBox profileRow = new HBox(switcherButton, optionsButton);
		profileRow.getStyleClass().add("home-profile-row");
		profileRow.setFillHeight(true);
		// Play: just the icon and "Play", centred - where it goes is already picked right below ("launch into"), so the
		// small line under it said the same thing twice (owner, 2026-11-25)
		playTitle.getStyleClass().add("home-play-title");
		HBox playContent = new HBox(12, Icons.icon("play", 24, Color.WHITE), playTitle);
		playContent.setAlignment(Pos.CENTER);
		play.setGraphic(playContent);
		play.setText(null);
		play.minWidthProperty().unbind();
		play.setMinWidth(0);
		play.setMaxWidth(Double.MAX_VALUE);
		play.getStyleClass().setAll("button", "home-play");
		playSplit.getChildren().setAll(play);
		HBox.setHgrow(play, Priority.ALWAYS);
		playSplit.setMaxWidth(Double.MAX_VALUE);
		playArea.setMinWidth(0);
		playArea.setMaxWidth(Double.MAX_VALUE);
		progressView.setMinHeight(76);
		return flow(profileRow, below);
	}

	/** The flowing glass card: the profile row as its top tab, Play and {@code below} in the body. */
	private javafx.scene.layout.VBox flow(HBox profileRow, Node below) {
		// profile row and Play full width on top; "launch into" flows out narrower underneath
		net.veloclient.launcher.ui.glass.FlowCard card = new net.veloclient.launcher.ui.glass.FlowCard(below, profileRow, playArea);
		javafx.scene.layout.VBox holder = new javafx.scene.layout.VBox(card);
		holder.getStyleClass().add("home-play-card");
		return holder;
	}

	/** Puts the card's parts back when Home is rebuilt (the card is made fresh each time). */
	private javafx.scene.layout.VBox rebuildHomeCard(Node below) {
		if (switcherButton.getParent() instanceof javafx.scene.layout.Pane old) {
			old.getChildren().remove(switcherButton);
		}
		if (optionsButton.getParent() instanceof javafx.scene.layout.Pane old) {
			old.getChildren().remove(optionsButton);
		}
		if (playArea.getParent() instanceof javafx.scene.layout.Pane old) {
			old.getChildren().remove(playArea);
		}
		HBox profileRow = new HBox(switcherButton, optionsButton);
		profileRow.getStyleClass().add("home-profile-row");
		return flow(profileRow, below);
	}

	/** True while a launch prepares (the Play button shows its progress then). */
	public javafx.beans.property.BooleanProperty preparingProperty() {
		return progressView.visibleProperty();
	}
}
