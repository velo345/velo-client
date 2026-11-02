package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;
import net.veloclient.launcher.doctor.Diagnosis;
import net.veloclient.launcher.doctor.Diagnosis.Fix;
import net.veloclient.launcher.doctor.Diagnosis.Issue;
import net.veloclient.launcher.doctor.Diagnosis.Severity;
import net.veloclient.launcher.doctor.Doctor;
import net.veloclient.launcher.instance.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Velo Doctor's face: a status ring (green when all is well, red with the number of problems), then one card per
 * problem - what's wrong in one line, why it matters, the ways to fix it (recommended first) and a Fix button. "Fix
 * all" applies one consistent plan for everything. Fixed cards fold away with a tick; fixes that were tried and didn't
 * help show crossed out. When it runs out of ideas it says so and offers the bug report.
 */
public final class DoctorPanel {

	public enum Mode {
		/** The profile's Doctor section - re-checks by itself whenever mods or packs change. */
		PROFILE,
		/** Right after a crash, inside the crash dialog. */
		CRASH,
		/** Before starting, when something would crash the game. */
		PRELAUNCH
	}

	public interface Host {
		/** Fixes went in; {@code all} = it was "Fix all". The crash / pre-launch views start the game again. */
		void fixed(boolean all);

		/** "Send a bug report" - the crash dialog sends its own; elsewhere the bug report dialog opens. */
		void report(Diagnosis diagnosis);

		/** Every new result (first check, after fixes, live re-checks) - e.g. for a count badge. */
		default void shown(Diagnosis diagnosis) {
		}
	}

	private final Instance instance;
	private final Mode mode;
	private final Host host;
	private final Supplier<Diagnosis> diagnose;
	private final VBox root = new VBox(14);
	private final VBox cards = new VBox(10);
	private final StackPane ring = new StackPane();
	private final Label headline = new Label();
	private final Label subline = new Label();
	private final Button fixAll = new Button();
	private final HBox headerButtons = new HBox(8);
	private Diagnosis current;
	private boolean busy;

	private DoctorPanel(Instance instance, Mode mode, Supplier<Diagnosis> diagnose, Host host) {
		this.instance = instance;
		this.mode = mode;
		this.diagnose = diagnose;
		this.host = host;
	}

	/** Builds the panel and runs the first check in the background. */
	public static DoctorPanel create(Instance instance, Mode mode, Supplier<Diagnosis> diagnose, Host host) {
		DoctorPanel panel = new DoctorPanel(instance, mode, diagnose, host);
		panel.layout();
		panel.refresh();
		return panel;
	}

	/** Builds the panel around a diagnosis that's already there (crash dialog, pre-launch). */
	public static DoctorPanel create(Instance instance, Mode mode, Diagnosis ready, Supplier<Diagnosis> diagnose, Host host) {
		DoctorPanel panel = new DoctorPanel(instance, mode, diagnose, host);
		panel.layout();
		panel.show(ready);
		return panel;
	}

	public Node node() {
		return root;
	}

	/**
	 * Takes "Fix all" out of the header so the host can put it with its own buttons (the crash dialog: bottom row,
	 * next to Send report - where people look last). It keeps working and shows / hides itself as before.
	 */
	public Button takeFixAllButton() {
		headerButtons.getChildren().remove(fixAll);
		return fixAll;
	}

	public Diagnosis diagnosis() {
		return current;
	}

	private void layout() {
		root.getStyleClass().add("doctor");
		headline.getStyleClass().add("doctor-headline");
		subline.getStyleClass().add("doctor-subline");
		subline.setWrapText(true);
		VBox titles = new VBox(3, headline, subline);
		HBox.setHgrow(titles, Priority.ALWAYS);
		fixAll.getStyleClass().addAll("primary-button", "doctor-fix-all");
		fixAll.setGraphic(Icons.icon("zap", 14, Color.WHITE, 2.2));
		fixAll.setOnAction(e -> fixAll());
		headerButtons.setAlignment(Pos.CENTER_RIGHT);
		if (mode == Mode.PROFILE) {
			Button recheck = new Button("Check again");
			recheck.setGraphic(Icons.icon("refresh", 13, Color.web("#E4E4E8"), 2.2));
			recheck.setOnAction(e -> refresh());
			headerButtons.getChildren().add(recheck);
		}
		headerButtons.getChildren().add(fixAll);
		ring.setMinSize(56, 56);
		ring.setMaxSize(56, 56);
		HBox header = new HBox(16, ring, titles, headerButtons);
		header.setAlignment(Pos.CENTER_LEFT);
		header.getStyleClass().add("doctor-header");
		root.getChildren().addAll(header, cards);
		showChecking();
	}

	/** Runs the check off the UI thread and shows the result. */
	public void refresh() {
		if (busy) {
			return;
		}
		CompletableFuture.supplyAsync(diagnose, Executors.newVirtualThreadPerTaskExecutor())
				.whenComplete((d, error) -> Platform.runLater(() -> {
					if (d != null) {
						show(d);
					} else {
						headline.setText("Couldn't check this profile");
						subline.setText(error == null ? "" : String.valueOf(error.getMessage()));
					}
				}));
	}

	private void showChecking() {
		headline.setText("Checking...");
		subline.setText("Mods, resource packs, shaders and settings");
		Arc spinner = new Arc(0, 0, 22, 22, 90, 100);
		spinner.setType(ArcType.OPEN);
		spinner.setFill(null);
		spinner.setStroke(Color.web("#7dbbff"));
		spinner.setStrokeWidth(4);
		spinner.setStrokeLineCap(StrokeLineCap.ROUND);
		Circle track = track();
		ring.getChildren().setAll(track, spinner);
		RotateTransition spin = new RotateTransition(Duration.millis(900), spinner);
		spin.setByAngle(360);
		spin.setCycleCount(RotateTransition.INDEFINITE);
		spin.setInterpolator(Interpolator.LINEAR);
		spin.play();
		fixAll.setVisible(false);
		fixAll.setManaged(false);
	}

	private static Circle track() {
		Circle track = new Circle(22);
		track.setFill(null);
		track.setStroke(Color.web("#ffffff", 0.08));
		track.setStrokeWidth(4);
		return track;
	}

	private void show(Diagnosis d) {
		Diagnosis before = current;
		current = d;
		host.shown(d);
		List<Issue> serious = d.issues().stream().filter(i -> i.severity() != Severity.TIP).toList();
		long fixable = serious.stream().filter(Issue::fixable).count();
		boolean healthy = serious.isEmpty() && !d.needsReport();
		try {
			paintRing(healthy, serious.size());
		} catch (RuntimeException e) {
			// Decoration only: the problems and their fixes must show whatever happens here.
			net.veloclient.launcher.LauncherLog.warn("Doctor ring failed", e);
		}
		if (mode == Mode.CRASH) {
			headline.setText(serious.isEmpty() ? "We couldn't find the cause" : serious.size() == 1 ? "Found what crashed it" : "Found " + serious.size() + " problems");
			subline.setText(d.crashSummary() != null ? d.crashSummary() : "");
		} else if (healthy) {
			headline.setText("No problems found");
			long tips = d.issues().size();
			subline.setText(tips == 0 ? "Everything here fits together." : tips + (tips == 1 ? " tip" : " tips") + " below - nothing that breaks the game.");
		} else {
			headline.setText(serious.size() == 1 ? "1 problem found" : serious.size() + " problems found");
			subline.setText(mode == Mode.PRELAUNCH ? "These would stop the game from starting or crash it." : "Fix them here - the game would crash with them.");
		}
		fixAll.setText(mode == Mode.PROFILE ? "Fix all" : fixable == 1 ? "Fix & play" : "Fix all & play");
		fixAll.setVisible(fixable > 0);
		fixAll.setManaged(fixable > 0);
		fixAll.setDisable(false);
		List<Node> nodes = new ArrayList<>();
		if (d.needsReport()) {
			nodes.add(reportBanner(d));
		}
		for (Issue issue : d.issues()) {
			nodes.add(card(issue));
		}
		cards.getChildren().setAll(nodes);
		// Only animate cards in when the list actually changed - live re-checks shouldn't make it flicker.
		if (before == null || !sameIssues(before, d)) {
			for (int i = 0; i < nodes.size() && i < 10; i++) {
				Node n = nodes.get(i);
				n.setOpacity(0);
				FadeTransition fade = new FadeTransition(Duration.millis(220), n);
				fade.setToValue(1);
				TranslateTransition slide = new TranslateTransition(Duration.millis(320), n);
				slide.setFromY(10);
				slide.setToY(0);
				slide.setInterpolator(Interpolator.SPLINE(0.16, 1, 0.3, 1));
				ParallelTransition in = new ParallelTransition(fade, slide);
				in.setDelay(Duration.millis(40L * i));
				in.play();
			}
		}
	}

	private static boolean sameIssues(Diagnosis a, Diagnosis b) {
		if (a.issues().size() != b.issues().size() || a.needsReport() != b.needsReport()) {
			return false;
		}
		for (int i = 0; i < a.issues().size(); i++) {
			if (!a.issues().get(i).id().equals(b.issues().get(i).id()) || a.issues().get(i).fixes().size() != b.issues().get(i).fixes().size()) {
				return false;
			}
		}
		return true;
	}

	private void paintRing(boolean healthy, int problems) {
		Color color = healthy ? Color.web("#7dd3a0") : Color.web("#ff7a7a");
		Arc arc = new Arc(0, 0, 22, 22, 90, 0);
		arc.setType(ArcType.OPEN);
		arc.setFill(null);
		arc.setStroke(color);
		arc.setStrokeWidth(4);
		arc.setStrokeLineCap(StrokeLineCap.ROUND);
		Node center = healthy ? Icons.icon("check", 22, color, 3)
				: label(String.valueOf(Math.max(problems, 1)), "doctor-ring-count");
		StackPane glow = new StackPane();
		glow.getStyleClass().add(healthy ? "doctor-ring-ok" : "doctor-ring-bad");
		glow.setMaxSize(48, 48);
		ring.getChildren().setAll(glow, track(), arc, center);
		// The ring draws itself around, the center pops in.
		new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(arc.lengthProperty(), 0)),
				new KeyFrame(Duration.millis(650), new KeyValue(arc.lengthProperty(), -360, Interpolator.SPLINE(0.16, 1, 0.3, 1)))).play();
		center.setScaleX(0.4);
		center.setScaleY(0.4);
		ScaleTransition pop = new ScaleTransition(Duration.millis(380), center);
		pop.setToX(1);
		pop.setToY(1);
		pop.setInterpolator(UiMotion.BACK_OUT);
		pop.setDelay(Duration.millis(180));
		pop.play();
	}

	private Node reportBanner(Diagnosis d) {
		Label title = label("We can't fix this one automatically", "doctor-card-title");
		Label text = label(d.issues().stream().anyMatch(i -> i.id().equals("culprit-velo"))
				? "It's a bug in Velo Client. Send the report - it has everything we need to fix it."
				: "Send us the report and we'll look at it. If you fix it yourself in the meantime, the Doctor remembers what worked.", "doctor-card-detail");
		text.setWrapText(true);
		Button send = new Button("Send bug report");
		send.getStyleClass().add("primary-button");
		send.setGraphic(Icons.icon("bug", 14, Color.WHITE, 2.2));
		send.setOnAction(e -> host.report(d));
		VBox body = new VBox(4, title, text);
		HBox.setHgrow(body, Priority.ALWAYS);
		HBox banner = new HBox(14, Icons.icon("bug", 22, Color.web("#ffb36b"), 2.2), body, send);
		banner.setAlignment(Pos.CENTER_LEFT);
		banner.getStyleClass().addAll("doctor-card", "doctor-report");
		return banner;
	}

	private Node card(Issue issue) {
		String sev = switch (issue.severity()) {
			case CRASH -> "doctor-crash";
			case PROBLEM -> "doctor-problem";
			case TIP -> "doctor-tip";
		};
		Node icon = Icons.icon(issue.severity() == Severity.TIP ? "info" : "warning", 18,
				Color.web(issue.severity() == Severity.CRASH ? "#ff7a7a" : issue.severity() == Severity.PROBLEM ? "#f7c04a" : "#7dbbff"), 2.2);
		Label title = label(issue.title(), "doctor-card-title");
		title.setWrapText(true);
		Label detail = label(issue.detail(), "doctor-card-detail");
		detail.setWrapText(true);
		VBox body = new VBox(5, title, detail);
		ToggleGroup options = new ToggleGroup();
		if (!issue.fixes().isEmpty()) {
			FlowPane pills = new FlowPane(6, 6);
			for (int i = 0; i < issue.fixes().size(); i++) {
				Fix fix = issue.fixes().get(i);
				ToggleButton pill = new ToggleButton(fix.label());
				pill.setUserData(fix);
				pill.setToggleGroup(options);
				pill.getStyleClass().setAll("button", "doctor-option");
				if (fix.detail() != null) {
					pill.setTooltip(new javafx.scene.control.Tooltip(fix.detail()));
				}
				if (i == 0) {
					pill.setSelected(true);
					Label rec = label("Recommended", "doctor-recommended");
					pill.setGraphic(rec);
					pill.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
				}
				pills.getChildren().add(pill);
			}
			// One option always stays picked.
			options.selectedToggleProperty().addListener((o, was, now) -> {
				if (now == null && was != null) {
					was.setSelected(true);
				}
			});
			body.getChildren().add(pills);
		}
		for (Fix tried : issue.tried()) {
			Label t = label("Tried \"" + tried.label() + "\" - didn't help", "doctor-tried");
			body.getChildren().add(t);
		}
		HBox.setHgrow(body, Priority.ALWAYS);
		body.setMinWidth(0);
		HBox card = new HBox(12, icon, body);
		card.setAlignment(Pos.TOP_LEFT);
		card.getStyleClass().addAll("doctor-card", sev);
		if (!issue.fixes().isEmpty()) {
			Button fixButton = new Button("Fix");
			fixButton.getStyleClass().add("doctor-fix");
			fixButton.setOnAction(e -> {
				Fix fix = (Fix) options.getSelectedToggle().getUserData();
				fixOne(card, issue, fix);
			});
			if (mode == Mode.PROFILE) {
				card.getChildren().add(fixButton);
			} else {
				// Narrow dialogs: the button sits with the options instead of in a column of its own that got cut off.
				Node pills = body.getChildren().get(2);
				if (pills instanceof FlowPane flow) {
					flow.getChildren().add(fixButton);
				}
			}
		}
		return card;
	}

	private void fixOne(HBox card, Issue issue, Fix fix) {
		run(List.of(new Doctor.Chosen(issue, fix)), List.of(card), false);
	}

	private void fixAll() {
		if (current == null) {
			return;
		}
		List<Doctor.Chosen> plan = Doctor.planAll(instance, current);
		if (plan.isEmpty()) {
			return;
		}
		List<HBox> touched = new ArrayList<>();
		for (Node n : cards.getChildren()) {
			if (n instanceof HBox card && !card.getStyleClass().contains("doctor-tip") && !card.getStyleClass().contains("doctor-report")) {
				touched.add(card);
			}
		}
		run(plan, touched, true);
	}

	private void run(List<Doctor.Chosen> chosen, List<HBox> working, boolean all) {
		if (busy) {
			return;
		}
		busy = true;
		Diagnosis diagnosis = current;
		fixAll.setDisable(true);
		ProgressBar bar = new ProgressBar(0);
		bar.setMaxWidth(Double.MAX_VALUE);
		bar.getStyleClass().add("doctor-progress");
		Label step = label("Fixing...", "doctor-subline");
		VBox progress = new VBox(6, step, bar);
		root.getChildren().add(1, progress);
		for (HBox card : working) {
			card.getStyleClass().add("doctor-working");
			card.setDisable(true);
		}
		CompletableFuture.supplyAsync(() -> Doctor.apply(instance, diagnosis, chosen, (text, fraction) -> Platform.runLater(() -> {
			step.setText(text);
			bar.setProgress(fraction);
		})), Executors.newVirtualThreadPerTaskExecutor()).whenComplete((outcome, error) -> Platform.runLater(() -> {
			busy = false;
			root.getChildren().remove(progress);
			if (outcome == null) {
				for (HBox card : working) {
					card.getStyleClass().remove("doctor-working");
					card.setDisable(false);
				}
				fixAll.setDisable(false);
				Toasts.show(root, "warning", "#ff9a9a", "Fixing failed: " + (error == null ? "unknown error" : error.getMessage()));
				return;
			}
			if (!outcome.failures().isEmpty()) {
				Toasts.show(root, "warning", "#f7c04a", String.join(" · ", outcome.failures()));
			}
			// Fixed cards fold away with a tick, then the fresh check takes over.
			List<javafx.animation.Animation> folds = new ArrayList<>();
			for (HBox card : working) {
				folds.add(fold(card));
			}
			ParallelTransition all2 = new ParallelTransition(folds.toArray(new javafx.animation.Animation[0]));
			all2.setOnFinished(e -> {
				if (mode != Mode.CRASH) {
					// A crash's own findings can only be re-checked by playing - that view starts the game instead.
					show(outcome.after());
				}
				if (!outcome.applied().isEmpty()) {
					host.fixed(all);
				}
			});
			all2.play();
		}));
	}

	private static javafx.animation.Animation fold(HBox card) {
		StackPane tick = new StackPane(Icons.icon("check", 18, Color.web("#7dd3a0"), 3));
		tick.getStyleClass().add("doctor-tick");
		card.getChildren().add(tick);
		tick.setScaleX(0);
		tick.setScaleY(0);
		ScaleTransition pop = new ScaleTransition(Duration.millis(260), tick);
		pop.setToX(1);
		pop.setToY(1);
		pop.setInterpolator(UiMotion.BACK_OUT);
		double height = card.getHeight();
		card.setMinHeight(Region.USE_PREF_SIZE);
		Timeline collapse = new Timeline(
				new KeyFrame(Duration.ZERO, new KeyValue(card.prefHeightProperty(), height), new KeyValue(card.opacityProperty(), 1)),
				new KeyFrame(Duration.millis(280), new KeyValue(card.prefHeightProperty(), height), new KeyValue(card.opacityProperty(), 1)),
				new KeyFrame(Duration.millis(560), new KeyValue(card.prefHeightProperty(), 0, Interpolator.EASE_IN),
						new KeyValue(card.opacityProperty(), 0, Interpolator.EASE_IN)));
		return new ParallelTransition(pop, collapse);
	}

	private static Label label(String text, String style) {
		Label label = new Label(text);
		label.getStyleClass().add(style);
		return label;
	}

	// ---- The profile section: live while it's on screen ----

	/** The Doctor section of a profile page: checks on open and again whenever mods, packs, shaders or settings change. */
	public static Node section(Instance instance, Host host) {
		DoctorPanel panel = create(instance, Mode.PROFILE, () -> Doctor.check(instance), host);
		ScrollPane scroll = new ScrollPane(panel.node());
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		panel.root.setPadding(new Insets(14, 6, 18, 4));
		long[] stamp = {folderStamp(instance)};
		Timeline watch = new Timeline(new KeyFrame(Duration.millis(1200), e -> {
			long now = folderStamp(instance);
			if (now != stamp[0]) {
				stamp[0] = now;
				panel.refresh();
			}
		}));
		watch.setCycleCount(Timeline.INDEFINITE);
		scroll.sceneProperty().addListener((o, was, scene) -> {
			if (scene == null) {
				watch.stop();
			} else {
				watch.play();
			}
		});
		return scroll;
	}

	/** Cheap "did anything change?" - names, sizes and times of the files the Doctor reads. */
	static long folderStamp(Instance instance) {
		long h = 17;
		for (java.nio.file.Path dir : new java.nio.file.Path[] {net.veloclient.launcher.instance.InstancePaths.modsDir(instance.id()),
				net.veloclient.launcher.instance.InstancePaths.resourcePacksDir(instance.id()),
				net.veloclient.launcher.instance.InstancePaths.shaderPacksDir(instance.id())}) {
			try (var files = java.nio.file.Files.list(dir)) {
				for (java.nio.file.Path p : files.toList()) {
					h = h * 31 + p.getFileName().hashCode();
					h = h * 31 + java.nio.file.Files.getLastModifiedTime(p).toMillis();
				}
			} catch (Exception ignored) {
				// Missing folder.
			}
		}
		java.nio.file.Path options = net.veloclient.launcher.instance.InstancePaths.gameDir(instance.id()).resolve("options.txt");
		try {
			h = h * 31 + java.nio.file.Files.getLastModifiedTime(options).toMillis();
		} catch (Exception ignored) {
			// No options yet.
		}
		return h;
	}
}
