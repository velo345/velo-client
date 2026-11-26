package net.veloclient.launcher.ui.glass;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import net.veloclient.launcher.data.EmoteCatalog;
import net.veloclient.launcher.ui.EmoteFigureFx;

import java.util.function.Consumer;

/**
 * The launcher's emote wheel (2026-11-25): B on Home opens the same 8 slots as the game's wheel, each a little live
 * preview of the emote; a click plays it on your Home player (and on your party's Homes). B, Esc or a click outside
 * closes it. Opens and closes with a soft scale - no hard cut.
 */
public final class EmoteWheel {

	private static StackPane open;

	private EmoteWheel() {
	}

	public static boolean isOpen() {
		return open != null;
	}

	/** The emote under the mouse while the wheel is held open (B down), and what plays it. */
	private static String hovered;
	private static Consumer<String> player;

	/**
	 * Hold-to-pick (owner 2026-11-26, like in game): B down opens the wheel, pointing picks a segment, letting go of B
	 * plays it (nothing hovered = just closes). Clicking a segment still works too.
	 */
	public static void release(StackPane layers) {
		if (open == null) {
			return;
		}
		String id = hovered;
		Consumer<String> play = player;
		close(layers);
		if (id != null && play != null) {
			play.accept(id);
		}
	}

	/** Opens the wheel over {@code layers} (or closes it when it's open). {@code play} gets the chosen emote's id. */
	public static void toggle(StackPane layers, Consumer<String> play) {
		if (open != null) {
			close(layers);
			return;
		}
		hovered = null;
		player = play;
		StackPane root = new StackPane();
		root.getStyleClass().add("emote-wheel-scrim");
		root.setOnMouseClicked(e -> {
			if (e.getTarget() == root) {
				close(layers);
			}
		});
		// One frosted disc cut into 8 segments (owner 2026-11-25: no loose circles, no text spilling out of slots):
		// each segment shows its emote moving, the hovered one lights up and its name sits in the middle.
		StackPane wheel = new StackPane();
		wheel.setMinSize(SIZE, SIZE);
		wheel.setMaxSize(SIZE, SIZE);
		wheel.setPickOnBounds(false);
		wheel.getStyleClass().add("emote-wheel-disc");
		Pane layer = new Pane();
		layer.setMinSize(SIZE, SIZE);
		layer.setMaxSize(SIZE, SIZE);
		layer.setPickOnBounds(false);
		Circle hub = new Circle(SIZE / 2.0, SIZE / 2.0, INNER - 6);
		hub.getStyleClass().add("emote-wheel-hub");
		hub.setMouseTransparent(true);
		layer.getChildren().add(hub);
		Label name = new Label("Emotes");
		name.getStyleClass().add("emote-wheel-center");
		name.setMaxWidth(INNER * 2 - 30);
		name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
		Label sub = new Label("Point at one");
		sub.getStyleClass().add("emote-wheel-sub");
		name.setAlignment(Pos.CENTER);
		sub.setAlignment(Pos.CENTER);
		sub.setMaxWidth(INNER * 2 - 30);
		sub.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
		VBox centre = new VBox(2, name, sub);
		centre.setFillWidth(false);
		centre.setAlignment(Pos.CENTER);
		centre.setMouseTransparent(true);
		int slots = EmoteCatalog.SLOTS;
		boolean any = false;
		Runnable[] enter = new Runnable[slots];
		Runnable[] leave = new Runnable[slots];
		int[] lit = {-1};
		for (int i = 0; i < slots; i++) {
			String id = EmoteCatalog.slot(i);
			var type = id == null ? null : EmoteCatalog.byId(id).orElse(null);
			any |= type != null;
			double mid = -90 + 360.0 * i / slots;
			javafx.scene.shape.Path seg = segment(mid - 180.0 / slots + GAP, mid + 180.0 / slots - GAP);
			seg.getStyleClass().add("emote-wheel-seg");
			layer.getChildren().add(seg);
			double rad = Math.toRadians(mid);
			double cx = SIZE / 2.0 + Math.cos(rad) * (INNER + OUTER) / 2.0;
			double cy = SIZE / 2.0 + Math.sin(rad) * (INNER + OUTER) / 2.0;
			Node figure;
			if (type == null) {
				Label plus = new Label("+");
				plus.getStyleClass().add("emote-wheel-empty");
				figure = plus;
			} else {
				figure = EmoteFigureFx.create(type, FIG, FIG);
			}
			StackPane holder = new StackPane(figure);
			holder.setMouseTransparent(true);
			holder.setPrefSize(FIG, FIG);
			holder.resizeRelocate(cx - FIG / 2.0, cy - FIG / 2.0, FIG, FIG);
			holder.setManaged(false);
			layer.getChildren().add(holder);
			String label = type == null ? "Empty slot" : type.name();
			String hint = type == null ? "Equip emotes in Locker" : "Let go of B to play";
			int slot = i;
			enter[i] = () -> {
				seg.getStyleClass().add("emote-wheel-seg-hover");
				name.setText(label);
				sub.setText(hint);
				hovered = type == null ? null : id;
				holder.setScaleX(1.12);
				holder.setScaleY(1.12);
			};
			leave[i] = () -> {
				seg.getStyleClass().remove("emote-wheel-seg-hover");
				name.setText("Emotes");
				sub.setText("Point at one");
				if (id != null && id.equals(hovered)) {
					hovered = null;
				}
				holder.setScaleX(1);
				holder.setScaleY(1);
			};
			seg.setOnMouseEntered(e -> {
				if (lit[0] != slot) {
					if (lit[0] >= 0) {
						leave[lit[0]].run();
					}
					lit[0] = slot;
					enter[slot].run();
				}
			});
			if (type != null) {
				seg.setOnMouseClicked(e -> {
					close(layers);
					play.accept(id);
				});
			}
		}
		if (!any) {
			sub.setText("Get some in the Store");
		}
		// The direction is enough (owner 2026-11-26): wherever the mouse is - on the wheel or far off it - the segment
		// in that direction from the centre lights up, and letting go of B plays it. Only right in the middle picks none.
		root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
			javafx.geometry.Point2D c = wheel.localToScene(SIZE / 2.0, SIZE / 2.0);
			double dx = e.getSceneX() - c.getX(), dy = e.getSceneY() - c.getY();
			int pick = -1;
			if (dx * dx + dy * dy > 24 * 24) {
				double deg = Math.toDegrees(Math.atan2(dy, dx)) + 90;   // 0 = up, clockwise, like the slots
				pick = Math.floorMod((int) Math.round(deg / (360.0 / slots)), slots);
			}
			if (pick != lit[0]) {
				if (lit[0] >= 0) {
					leave[lit[0]].run();
				}
				lit[0] = pick;
				if (pick >= 0) {
					enter[pick].run();
				}
			}
		});
		wheel.getChildren().addAll(layer, centre);
		// real glass: the blurred backdrop behind the whole disc
		Glass.install(wheel, SIZE / 2.0);
		root.getChildren().add(wheel);
		root.setFocusTraversable(true);
		root.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.ESCAPE) {
				close(layers);
				e.consume();
			}
		});
		layers.getChildren().add(root);
		open = root;
		Platform.runLater(root::requestFocus);
		root.setOpacity(0);
		wheel.setScaleX(0.86);
		wheel.setScaleY(0.86);
		FadeTransition fade = new FadeTransition(Duration.millis(180), root);
		fade.setToValue(1);
		ScaleTransition grow = new ScaleTransition(Duration.millis(260), wheel);
		grow.setToX(1);
		grow.setToY(1);
		grow.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.25, 1));
		new ParallelTransition(fade, grow).play();
	}

	private static final double SIZE = 420;
	private static final double OUTER = 206;
	private static final double INNER = 86;
	private static final double GAP = 0.9;
	private static final int FIG = 72;

	/** One ring segment between {@code a0} and {@code a1} degrees (0 = right, clockwise), centred in the disc. */
	private static javafx.scene.shape.Path segment(double a0, double a1) {
		double c = SIZE / 2.0;
		double r0 = Math.toRadians(a0), r1 = Math.toRadians(a1);
		javafx.scene.shape.Path path = new javafx.scene.shape.Path(
				new javafx.scene.shape.MoveTo(c + Math.cos(r0) * INNER, c + Math.sin(r0) * INNER),
				new javafx.scene.shape.LineTo(c + Math.cos(r0) * OUTER, c + Math.sin(r0) * OUTER),
				new javafx.scene.shape.ArcTo(OUTER, OUTER, 0, c + Math.cos(r1) * OUTER, c + Math.sin(r1) * OUTER, false, true),
				new javafx.scene.shape.LineTo(c + Math.cos(r1) * INNER, c + Math.sin(r1) * INNER),
				new javafx.scene.shape.ArcTo(INNER, INNER, 0, c + Math.cos(r0) * INNER, c + Math.sin(r0) * INNER, false, false),
				new javafx.scene.shape.ClosePath());
		path.setPickOnBounds(false);
		return path;
	}

	public static void close(StackPane layers) {
		StackPane root = open;
		open = null;
		hovered = null;
		if (root == null) {
			return;
		}
		root.setMouseTransparent(true);
		FadeTransition fade = new FadeTransition(Duration.millis(160), root);
		fade.setToValue(0);
		fade.setOnFinished(e -> layers.getChildren().remove(root));
		fade.play();
	}

	/** Plays emote {@code id} on a 3D player from PlayerSkin3DView.createIdleViewer (nothing for other nodes). */
	@SuppressWarnings("unchecked")
	public static void play(Node player, String id) {
		if (player == null || id == null || !(player.getProperties().get("velo.emote") instanceof Consumer<?> consumer)) {
			return;
		}
		var type = EmoteCatalog.byId(id).orElse(null);
		EmoteCatalog.load(type, animation -> Platform.runLater(() -> ((Consumer<EmoteCatalog.Animation>) consumer).accept(animation)));
	}
}
