package net.veloclient.launcher.sandbox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.ui.Icons;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The Home arcade (2026-11 gimmick): press WASD or an arrow key on Home and the card's text slides away - the Ridgeline
 * turns into a little side-scroller with your skin (plus Snake and Blockfall in the Esc menu). Stand still for a few
 * seconds and the Home GUI fades back while your player turns to face you. Everything saves to arcade.json; the loop,
 * the audio line and the world's memory are only alive while you play.
 */
public final class Arcade extends StackPane {

	/** What the launcher does for the arcade. */
	public interface Host {
		void playingChanged(boolean playing);

		void fullscreenChanged(boolean fullscreen);

		void openSettings();
	}

	private static final double IDLE_SECONDS = 6;

	final ChipAudio audio = new ChipAudio();
	final Backdrop backdrop;
	SkinRig rig;
	private final Host host;
	private final Canvas canvas = new Canvas();
	private final Pane canvasHolder = new Pane(canvas);
	private final WorldGame world;
	private SnakeGame snake;
	private BlockfallGame blocks;
	private MiniGame game;
	private final AnimationTimer timer;
	private boolean timerRunning;
	private long lastNanos;
	private double time;
	private boolean shown;
	private boolean playing;
	private boolean fullscreen;
	private boolean loaded;
	private boolean disposed;
	private double idleFor;
	private double settle;
	private double saveDelay = -1;

	// effects
	private static final class Particle {
		double x;
		double y;
		double vx;
		double vy;
		double size;
		double life;
		double gravity;
		Color color;
	}

	private final List<Particle> particles = new ArrayList<>();
	private double shake;
	private Color flashColor;
	private double flash;
	private final SplittableRandom random = new SplittableRandom();

	// pixel transition
	private double transition = -1;
	private boolean fromNothing;
	private Runnable transitionMid;
	private String transitionTitle;
	private long transitionSeed;

	// overlays
	private final HBox hudButtons;
	private final Button fullscreenButton;
	private StackPane menuLayer;
	private VBox menuCard;
	private StackPane inventoryLayer;
	private javafx.scene.Group menuFit;
	private javafx.scene.Group inventoryFit;
	private Node inventoryCard;
	private GridPane slotGrid;
	private VBox recipeList;
	private Label benchNote;
	private final ImageView cursorView = new ImageView();
	private int cursorId;
	private int cursorCount;

	public Arcade(Color accent, Color background, Host host) {
		this.host = host;
		this.backdrop = new Backdrop(accent, background);
		getStyleClass().add("arcade");
		setMinSize(0, 0);
		canvasHolder.setMinSize(0, 0);
		canvas.getGraphicsContext2D().setImageSmoothing(false);
		world = new WorldGame(this);
		snake = new SnakeGame(this);
		blocks = new BlockfallGame(this);
		game = world;
		world.onInventoryChange = this::refreshInventory;

		fullscreenButton = hudButton("expand", "Fullscreen (F)", this::toggleFullscreen);
		Button menuButton = hudButton("pause", "Menu (Esc)", this::toggleMenu);
		hudButtons = new HBox(6, fullscreenButton, menuButton);
		hudButtons.setPickOnBounds(false);
		hudButtons.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane.setAlignment(hudButtons, Pos.TOP_RIGHT);
		StackPane.setMargin(hudButtons, new Insets(12));
		cursorView.setManaged(false);
		cursorView.setMouseTransparent(true);
		cursorView.setSmooth(false);
		cursorView.setFitWidth(32);
		cursorView.setFitHeight(32);
		getChildren().addAll(canvasHolder, hudButtons, cursorView);
		hudButtons.setOpacity(0);
		hudButtons.setMouseTransparent(true);

		canvas.addEventHandler(MouseEvent.ANY, this::onMouse);
		canvas.addEventHandler(ScrollEvent.SCROLL, e -> {
			if (playing && overlayOpen() == null) {
				input();
				game.scroll(e);
				e.consume();
			}
		});
		addEventFilter(MouseEvent.MOUSE_MOVED, e -> moveCursor(e.getX(), e.getY()));
		addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> moveCursor(e.getX(), e.getY()));
		setMouseTransparent(true);
		timer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				frame(now);
			}
		};
	}

	@Override
	protected void layoutChildren() {
		super.layoutChildren();
		// menus shrink to fit the Home card (they're full size in fullscreen)
		for (javafx.scene.Group fit : new javafx.scene.Group[] {menuFit, inventoryFit, tradeFit}) {
			if (fit != null && !fit.getChildren().isEmpty()) {
				Node card = fit.getChildren().getFirst();
				double cw = card.prefWidth(-1);
				double ch = card.prefHeight(cw);
				double scale = Math.min(1, Math.min((getWidth() - 32) / Math.max(1, cw), (getHeight() - 36) / Math.max(1, ch)));
				fit.setScaleX(scale);
				fit.setScaleY(scale);
			}
		}
		double w = Math.max(1, Math.floor(getWidth()));
		double h = Math.max(1, Math.floor(getHeight()));
		if (canvas.getWidth() != w || canvas.getHeight() != h) {
			canvas.setWidth(w);
			canvas.setHeight(h);
			if (shown && !timerRunning) {
				draw();
			}
		}
	}

	// ------------------------------------------------------------------ public API

	public void setSkin(byte[] png, boolean slim) {
		rig = SkinRig.from(png, slim);
		if (shown && !timerRunning) {
			draw();
		}
	}

	public boolean shown() {
		return shown;
	}

	public boolean playing() {
		return playing;
	}

	public boolean fullscreen() {
		return fullscreen;
	}

	/** WASD / arrow keys / space: what wakes the arcade on Home. */
	public static boolean isStartKey(KeyCode code) {
		return switch (code) {
			case W, A, S, D, UP, DOWN, LEFT, RIGHT -> true;
			default -> false;
		};
	}

	/** Starts playing (from the Home GUI): the card's text goes, the world builds itself in pixels. */
	public void start() {
		if (disposed) {
			return;
		}
		if (!loaded) {
			load();
		}
		if (rig == null) {
			rig = SkinRig.from(null, false);
		}
		boolean first = !shown;
		shown = true;
		playing = true;
		idleFor = 0;
		setMouseTransparent(false);
		hudButtons.setMouseTransparent(false);
		fade(hudButtons, 1, 260);
		audio.start();
		audio.music(game.music());
		if (first) {
			fromNothing = true;
			startTransition(null, null);
			audio.play("whoosh");
		} else {
			audio.play("open", 0.6f, 1);
		}
		setFocusTraversable(true);
		requestFocus();
		host.playingChanged(true);
		run();
	}

	/** Back to the Home GUI (still showing the scene, the player facing you). */
	public void rest() {
		if (!playing) {
			return;
		}
		playing = false;
		closeOverlays(false);
		world.releaseAll();
		game.rest();
		settle = 1.8;
		fade(hudButtons, 0, 200);
		hudButtons.setMouseTransparent(true);
		setMouseTransparent(true);
		audio.stop();
		if (fullscreen) {
			fullscreen = false;
			host.fullscreenChanged(false);
		}
		host.playingChanged(false);
		save();
		// pixels sweep the game away and Home is the plain Ridgeline again
		toNothing = true;
		fullscreenButton.setGraphic(Icons.icon("expand", 15, Color.web("#F4EFEE"), 2.2));
		startTransition(null, null);
		audio.play("whoosh");
	}

	private boolean toNothing;

	private void finishExit() {
		transition = -1;
		frozenTransition = -1;
		toNothing = false;
		shown = false;
		particles.clear();
		canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
	}

	/** Leaving Home, the window minimized: stop everything right away. */
	public void suspend() {
		if (playing) {
			rest();
		}
		stopLoop();
		audio.stop();
		if (toNothing || !playing) {
			finishExit();
		}
	}

	/** A game is launching: save, stop and let go of everything (textures, chunks, audio). */
	public void dispose() {
		if (disposed) {
			return;
		}
		if (loaded) {
			save();
		}
		disposed = true;
		playing = false;
		stopLoop();
		audio.stop();
		world.dispose();
		snake.dispose();
		blocks.dispose();
		Items.clearCache();
		MobArt.clear();
		BossArt.clear();
		particles.clear();
		rig = null;
		canvas.setWidth(0);
		canvas.setHeight(0);
	}

	/** Key events from the launcher's scene; true when the arcade used it. */
	public boolean handleKey(KeyEvent event) {
		if (!playing || disposed) {
			return false;
		}
		boolean down = event.getEventType() == KeyEvent.KEY_PRESSED;
		if (event.getEventType() == KeyEvent.KEY_TYPED) {
			return true;
		}
		KeyCode code = event.getCode();
		input();
		if (down && code == KeyCode.ESCAPE) {
			if (tradeLayer != null && tradeLayer.isVisible()) {
				closeTrade();
			} else if (inventoryLayer != null && inventoryLayer.isVisible()) {
				toggleInventory();
			} else {
				toggleMenu();
			}
			return true;
		}
		if (menuLayer != null && menuLayer.isVisible()) {
			return true;
		}
		if (down && (code == KeyCode.F || code == KeyCode.F11)) {
			toggleFullscreen();
			return true;
		}
		if (game == world && down && (code == KeyCode.E || code == KeyCode.I || code == KeyCode.TAB)) {
			toggleInventory();
			return true;
		}
		if (transition >= 0) {
			return true;
		}
		if (game == world && down && code == KeyCode.Q) {
			world.dropSelected(event.isShortcutDown());
			return true;
		}
		game.key(code, down);
		return true;
	}

	// ------------------------------------------------------------------ screenshot tour

	private boolean frozen;
	private double frozenTransition = -1;

	/** -Dvelo.launcherTourExtra=arcade: puts the arcade into a state worth a screenshot (no input needed). */
	public void tour(String what) {
		switch (what) {
			case "transition" -> {
				start();
				frozenTransition = 0.62;
			}
			case "world" -> {
				start();
				transition = -1;
				fromNothing = false;
				world.tourSetup("world");
				world.key(KeyCode.D, true);
			}
			case "cave", "ash", "frost", "dungeon", "foundry", "spire", "village", "outpost", "riddle0", "riddle1", "riddle2" -> {
				start();
				transition = -1;
				fromNothing = false;
				game = world;
				world.tourSetup(what);
			}
			case "boss", "boss1", "boss2" -> {
				start();
				transition = -1;
				fromNothing = false;
				game = world;
				world.tourSetup(what);
				world.tourAdvance(what.equals("boss") ? 2.9 : what.equals("boss1") ? 3.4 : 3.0);
			}
			case "trade" -> {
				start();
				transition = -1;
				fromNothing = false;
				game = world;
				world.tourSetup("cave");
				world.give(Items.IRON, 9);
				world.give(Items.COAL, 6);
				world.shop = world.trades();
				world.shopTitle = "MAP TRADER";
				world.shopQuote = "\"Maps to the old places, friend. They don't come cheap.\"";
				openTrade();
			}
			case "inventory" -> {
				start();
				transition = -1;
				world.tourSetup("world");
				toggleInventory();
			}
			case "menu" -> {
				start();
				transition = -1;
				toggleMenu();
			}
			case "snake" -> {
				start();
				transition = -1;
				game = snake;
				audio.music(snake.music());
				frozen = true;
				snake.tourSetup();
			}
			case "blocks" -> {
				start();
				transition = -1;
				game = blocks;
				frozen = true;
				blocks.tourSetup();
			}
			case "switch" -> {
				start();
				transition = -1;
				game = world;
				frozen = false;
				switchTo(snake);
				frozenTransition = 0.97;
			}
			case "fullscreen" -> {
				start();
				transition = -1;
				game = world;
				world.tourSetup("world");
				toggleFullscreen();
			}
			case "rest" -> {
				world.key(KeyCode.D, false);
				rest();
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ loop

	private void run() {
		if (!timerRunning && !disposed) {
			timerRunning = true;
			lastNanos = 0;
			timer.start();
		}
	}

	private void stopLoop() {
		if (timerRunning) {
			timer.stop();
			timerRunning = false;
		}
	}

	private void frame(long now) {
		double dt = lastNanos == 0 ? 1 / 60.0 : Math.min(0.05, (now - lastNanos) / 1e9);
		lastNanos = now;
		time += dt;
		if (frozenTransition >= 0 && transition >= 0) {
			transition = Math.min(transition + dt / 0.5, frozenTransition);
		} else if (transition >= 0) {
			double before = transition;
			transition += dt / 0.5;
			if (before < 1 && transition >= 1) {
				if (transitionMid != null) {
					transitionMid.run();
					transitionMid = null;
				}
				fromNothing = false;
			}
			if (transition >= 2) {
				transition = -1;
				if (toNothing) {
					finishExit();
				}
			}
		}
		boolean menu = menuLayer != null && menuLayer.isVisible();
		if (playing && transition < 0 && !frozen && (!menu || game.keepsRunning())) {
			game.update(dt);
			audio.music(game.music());
			idleFor += dt;
			if (idleFor > IDLE_SECONDS && game.restful() && overlayOpen() == null) {
				rest();
			}
		} else if (!playing) {
			game.update(dt);
			settle -= dt;
		}
		for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
			Particle p = it.next();
			p.x += p.vx * dt;
			p.y += p.vy * dt;
			p.vy += p.gravity * dt;
			p.life -= dt;
			if (p.life <= 0) {
				it.remove();
			}
		}
		shake = Math.max(0, shake - dt * 30);
		flash = Math.max(0, flash - dt * 2.5);
		if (saveDelay > 0) {
			saveDelay -= dt;
			if (saveDelay <= 0) {
				save();
			}
		}
		draw();
		if (!playing && transition < 0) {
			stopLoop();
		}
	}

	private void draw() {
		GraphicsContext g = canvas.getGraphicsContext2D();
		double w = canvas.getWidth();
		double h = canvas.getHeight();
		if (w < 2 || h < 2 || disposed) {
			return;
		}
		g.setImageSmoothing(false);
		g.save();
		if (shake > 0) {
			g.translate((random.nextDouble() - 0.5) * shake, (random.nextDouble() - 0.5) * shake);
		}
		if (!shown || (transition >= 0 && transition < 1 && fromNothing) || (transition >= 1 && toNothing)) {
			g.clearRect(-20, -20, w + 40, h + 40);
			if (!shown) {
				g.restore();
				return;
			}
		} else {
			game.render(g, w, h, time);
		}
		for (Particle p : particles) {
			g.setGlobalAlpha(Math.min(1, p.life / 0.3));
			g.setFill(p.color);
			g.fillRect(Math.round(p.x), Math.round(p.y), p.size, p.size);
		}
		g.setGlobalAlpha(1);
		g.restore();
		if (flash > 0 && flashColor != null) {
			g.setFill(flashColor.deriveColor(0, 1, 1, Math.min(1, flash)));
			g.fillRect(0, 0, w, h);
		}
		// soft vignette so the scene sits in the card
		g.setFill(new javafx.scene.paint.RadialGradient(0, 0, w / 2, h / 2, Math.max(w, h) * 0.75, false,
				javafx.scene.paint.CycleMethod.NO_CYCLE, new javafx.scene.paint.Stop(0.6, Color.TRANSPARENT),
				new javafx.scene.paint.Stop(1, Color.rgb(0, 0, 0, 0.35))));
		g.fillRect(0, 0, w, h);
		if (transition >= 0) {
			drawTransition(g, w, h);
		}
	}

	// ------------------------------------------------------------------ pixel transition

	private void startTransition(String title, Runnable mid) {
		transition = 0;
		transitionMid = mid;
		transitionTitle = title;
		transitionSeed = random.nextLong();
		run();
	}

	/**
	 * A wave of pixels: squares grow in from one corner (in the launcher's reds and darks, a bright edge leading),
	 * cover the card, the next game's name flashes up in pixels, then the squares shrink away from the other corner.
	 */
	private void drawTransition(GraphicsContext g, double w, double h) {
		double cs = Math.max(16, Math.round(Math.min(w, h) / 22));
		int nx = (int) Math.ceil(w / cs) + 1;
		int ny = (int) Math.ceil(h / cs) + 1;
		boolean cover = transition < 1;
		double p = cover ? transition : transition - 1;
		Color dark = Backdrop.mix(backdrop.background(), Color.BLACK, 0.35);
		Color mid = Backdrop.mix(backdrop.background(), backdrop.accent(), 0.25);
		Color hot = backdrop.accent();
		Color spark = Color.web("#FF8A8F");
		for (int i = 0; i < nx; i++) {
			for (int j = 0; j < ny; j++) {
				double diag = cover ? (i + j) / (double) (nx + ny) : 1 - (i + j) / (double) (nx + ny);
				double jitter = World.hash(transitionSeed, i * 7919L + j);
				double delay = diag * 0.55 + jitter * 0.18;
				double local = Math.max(0, Math.min(1, (p - delay) / 0.27));
				double size = cover ? local : 1 - local;
				if (size <= 0.001) {
					continue;
				}
				double s = Math.round(cs * size);
				double x = i * cs + (cs - s) / 2;
				double y = j * cs + (cs - s) / 2;
				double edge = cover ? local : 1 - local;
				Color c;
				if (edge < 0.55 && edge > 0.05) {
					c = jitter < 0.25 ? spark : hot;
				} else {
					c = jitter < 0.6 ? dark : jitter < 0.9 ? mid : Backdrop.mix(dark, hot, 0.35);
				}
				g.setFill(c);
				g.fillRect(Math.floor(x), Math.floor(y), Math.ceil(s), Math.ceil(s));
			}
		}
		// the name of what's coming, in big pixel letters while the screen is covered
		String title = transitionTitle != null ? transitionTitle : gameTitle(game);
		double near = toNothing ? 0 : 1 - Math.min(1, Math.abs(transition - 1) / 0.35);
		if (near > 0) {
			double size = Math.max(26, Math.min(64, h / 7));
			double y = h / 2 + size * 0.35 + (1 - near) * 10;
			g.setGlobalAlpha(near);
			label(g, title, w / 2 + 3, y + 3, size, Color.rgb(0, 0, 0, 0.6), true, true);
			label(g, title, w / 2, y, size, Color.web("#F4EFEE"), true, true);
			g.setGlobalAlpha(1);
		}
	}

	private static String gameTitle(MiniGame game) {
		return switch (game.id()) {
			case "snake" -> "SNAKE";
			case "blocks" -> "BLOCKFALL";
			default -> "VELO WORLD";
		};
	}

	/** Velo World going through a gate: the pixel wave with the place's name, then {@code mid} moves the player. */
	void travel(String title, Runnable mid) {
		world.releaseAll();
		audio.play("whoosh");
		startTransition(title, () -> {
			mid.run();
			audio.music(game.music());
		});
	}

	private StackPane tradeLayer;

	/** The map trader's goods. */
	void openTrade() {
		world.releaseAll();
		if (tradeLayer == null) {
			Region scrim = new Region();
			scrim.getStyleClass().add("arcade-scrim");
			scrim.setOnMouseClicked(e -> closeTrade());
			VBox card = new VBox(12);
			card.getStyleClass().add("arcade-menu");
			card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
			tradeFit = new javafx.scene.Group(card);
			tradeLayer = new StackPane(scrim, tradeFit);
			getChildren().add(getChildren().size() - 1, tradeLayer);
		}
		fillTrade();
		tradeLayer.setVisible(true);
		pop(tradeFit.getChildren().getFirst());
	}

	private javafx.scene.Group tradeFit;

	private void closeTrade() {
		if (tradeLayer != null && tradeLayer.isVisible()) {
			tradeLayer.setVisible(false);
			audio.play("close");
			requestFocus();
		}
	}

	private void fillTrade() {
		VBox card = (VBox) tradeFit.getChildren().getFirst();
		Label title = new Label(world.shopTitle);
		title.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 18px; -fx-font-weight: bold;");
		title.getStyleClass().add("arcade-title");
		Label sub = new Label(world.shopQuote);
		sub.setWrapText(true);
		sub.setMaxWidth(400);
		sub.getStyleClass().add("arcade-sub");
		VBox rows = new VBox(6);
		for (WorldGame.Trade trade : world.shop) {
			ImageView icon = new ImageView(Items.icon(trade.result()));
			icon.setSmooth(false);
			icon.setFitWidth(32);
			icon.setFitHeight(32);
			Label name = new Label(Items.name(trade.result()) + (trade.count() > 1 ? "  x" + trade.count() : ""));
			name.getStyleClass().add("arcade-recipe-name");
			HBox cost = new HBox(6);
			cost.setAlignment(Pos.CENTER_LEFT);
			if (trade.locked() != null) {
				Label lock = new Label(trade.locked());
				lock.getStyleClass().add("arcade-need-missing");
				cost.getChildren().add(lock);
			} else {
				for (int i = 0; i + 1 < trade.cost().length; i += 2) {
					ImageView small = new ImageView(Items.icon(trade.cost()[i]));
					small.setSmooth(false);
					small.setFitWidth(16);
					small.setFitHeight(16);
					Label n = new Label(String.valueOf(trade.cost()[i + 1]));
					n.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 11px;");
					n.getStyleClass().add(world.count(trade.cost()[i]) >= trade.cost()[i + 1] ? "arcade-need-ok" : "arcade-need-missing");
					cost.getChildren().addAll(small, n);
				}
			}
			VBox text = new VBox(3, name, cost);
			HBox.setHgrow(text, Priority.ALWAYS);
			Button buy = menuButton("Buy", world.canBuy(trade), null);
			buy.setDisable(!world.canBuy(trade));
			buy.setOnAction(e -> {
				if (world.buy(trade)) {
					fillTrade();
				}
			});
			HBox row = new HBox(10, icon, text, buy);
			row.setAlignment(Pos.CENTER_LEFT);
			row.getStyleClass().add("arcade-recipe");
			row.setPrefWidth(400);
			rows.getChildren().add(row);
		}
		Button close = menuButton("Leave", false, this::closeTrade);
		close.setMaxWidth(Double.MAX_VALUE);
		card.getChildren().setAll(new VBox(4, title, sub), rows, close);
	}

	private void switchTo(MiniGame next) {
		if (next == game || transition >= 0) {
			closeOverlays(true);
			return;
		}
		closeOverlays(true);
		world.releaseAll();
		audio.play("whoosh");
		audio.music(next.music());
		startTransition(gameTitle(next), () -> {
			game = next;
			hudButtons.setVisible(true);
			saveSoon();
		});
	}

	// ------------------------------------------------------------------ effects (used by the games)

	void particle(double x, double y, double vx, double vy, Color color, double size, double gravity, double life) {
		if (particles.size() > 400) {
			return;
		}
		Particle p = new Particle();
		p.x = x;
		p.y = y;
		p.vx = vx;
		p.vy = vy;
		p.size = size;
		p.life = life;
		p.gravity = gravity;
		p.color = color;
		particles.add(p);
	}

	void burst(double x, double y, Color color, int n, double gravity) {
		for (int i = 0; i < n; i++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double speed = 40 + random.nextDouble() * 160;
			particle(x, y, Math.cos(angle) * speed, Math.sin(angle) * speed - (gravity > 0 ? 80 : 0), color,
					2 + random.nextInt(4), gravity, 0.4 + random.nextDouble() * 0.5);
		}
	}

	void shake(double amount) {
		shake = Math.max(shake, amount);
	}

	void flash(Color color) {
		flashColor = color;
		flash = 1;
	}

	void saveSoon() {
		saveDelay = 0.5;
	}

	private void input() {
		idleFor = 0;
	}

	// text in Monocraft (the launcher bundles it) with a hard 1px shadow
	void label(GraphicsContext g, String text, double x, double y, double size, Color color, boolean right) {
		text(g, text, x, y, size, color, right ? TextAlignment.RIGHT : TextAlignment.LEFT, false);
	}

	void label(GraphicsContext g, String text, double x, double y, double size, Color color, boolean center, boolean unused) {
		text(g, text, x, y, size, color, center ? TextAlignment.CENTER : TextAlignment.LEFT, false);
	}

	void label(GraphicsContext g, String text, double x, double y, double size, Color color, boolean right, boolean center, boolean bold) {
		text(g, text, x, y, size, color, right ? TextAlignment.RIGHT : center ? TextAlignment.CENTER : TextAlignment.LEFT, bold);
	}

	private void text(GraphicsContext g, String text, double x, double y, double size, Color color, TextAlignment align, boolean bold) {
		g.setFont(Font.font("Monocraft", bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
		g.setTextAlign(align);
		g.setFill(Color.rgb(0, 0, 0, 0.55 * color.getOpacity()));
		g.fillText(text, x + 1, y + 1);
		g.setFill(color);
		g.fillText(text, x, y);
		g.setTextAlign(TextAlignment.LEFT);
	}

	/** The centred title card for start / game over screens. */
	void banner(GraphicsContext g, double w, double h, String title, String line, String hint) {
		double bw = Math.min(w - 40, 420);
		double bh = 128;
		double x = (w - bw) / 2;
		double y = (h - bh) / 2;
		g.setFill(Color.rgb(10, 7, 8, 0.86));
		g.fillRoundRect(x, y, bw, bh, 16, 16);
		g.setStroke(Backdrop.mix(backdrop.background(), backdrop.accent(), 0.5));
		g.setLineWidth(2);
		g.strokeRoundRect(x, y, bw, bh, 16, 16);
		label(g, title, w / 2, y + 44, 28, Color.web("#F4EFEE"), true, true);
		label(g, line, w / 2, y + 74, 13, Color.web("#C9B9B6"), true, true);
		double blink = 0.55 + 0.45 * Math.sin(time * 4);
		label(g, hint, w / 2, y + 102, 12, Color.web("#FF6B6F", blink), true, true);
	}

	// ------------------------------------------------------------------ mouse

	private void onMouse(MouseEvent e) {
		if (!playing || overlayOpen() != null || transition >= 0) {
			return;
		}
		if (e.getEventType() == MouseEvent.MOUSE_PRESSED || e.getEventType() == MouseEvent.MOUSE_DRAGGED) {
			input();
		}
		if (e.getEventType() == MouseEvent.MOUSE_EXITED) {
			world.releaseAll();
			return;
		}
		game.mouse(e);
		if (e.getEventType() == MouseEvent.MOUSE_PRESSED) {
			requestFocus();
			e.consume();
		}
	}

	// ------------------------------------------------------------------ HUD, menu, inventory

	private Button hudButton(String icon, String tip, Runnable action) {
		Button button = new Button();
		button.setGraphic(Icons.icon(icon, 15, Color.web("#F4EFEE"), 2.2));
		button.getStyleClass().addAll("icon-button", "arcade-hud-button");
		button.setFocusTraversable(false);
		button.setTooltip(new javafx.scene.control.Tooltip(tip));
		button.setOnAction(e -> {
			input();
			action.run();
		});
		return button;
	}

	private void toggleFullscreen() {
		fullscreen = !fullscreen;
		audio.play(fullscreen ? "open" : "close");
		fullscreenButton.setGraphic(Icons.icon(fullscreen ? "close" : "expand", 15, Color.web("#F4EFEE"), 2.2));
		host.fullscreenChanged(fullscreen);
		requestFocus();
		if (menuLayer != null && menuLayer.isVisible()) {
			buildMenuContent();
		}
	}

	private Node overlayOpen() {
		if (menuLayer != null && menuLayer.isVisible()) {
			return menuLayer;
		}
		if (inventoryLayer != null && inventoryLayer.isVisible()) {
			return inventoryLayer;
		}
		if (tradeLayer != null && tradeLayer.isVisible()) {
			return tradeLayer;
		}
		return null;
	}

	private void closeOverlays(boolean sound) {
		if (menuLayer != null && menuLayer.isVisible()) {
			menuLayer.setVisible(false);
			if (sound) {
				audio.play("close");
			}
		}
		if (inventoryLayer != null && inventoryLayer.isVisible()) {
			returnCursor();
			inventoryLayer.setVisible(false);
		}
	}

	private void toggleMenu() {
		if (menuLayer == null) {
			menuCard = new VBox(14);
			menuCard.getStyleClass().add("arcade-menu");
			menuCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
			Region scrim = new Region();
			scrim.getStyleClass().add("arcade-scrim");
			scrim.setOnMouseClicked(e -> toggleMenu());
			menuFit = new javafx.scene.Group(menuCard);
			menuLayer = new StackPane(scrim, menuFit);
			menuLayer.setVisible(false);
			getChildren().add(getChildren().size() - 1, menuLayer);
		}
		if (menuLayer.isVisible()) {
			menuLayer.setVisible(false);
			audio.play("close");
			requestFocus();
			return;
		}
		if (inventoryLayer != null && inventoryLayer.isVisible()) {
			returnCursor();
			inventoryLayer.setVisible(false);
		}
		world.releaseAll();
		buildMenuContent();
		menuLayer.setVisible(true);
		pop(menuCard);
		audio.play("open");
	}

	private void buildMenuContent() {
		Label title = new Label("PAUSED");
		title.getStyleClass().add("arcade-title");
		title.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 24px; -fx-font-weight: bold;");
		Label sub = new Label("Something to do while the game loads");
		sub.getStyleClass().add("arcade-sub");

		HBox tiles = new HBox(8, gameTile(world, "World", "Mine, build, craft"), gameTile(snake, "Snake", "Your skin, but long"),
				gameTile(blocks, "Blockfall", "Clear the rows"));
		tiles.setAlignment(Pos.CENTER);

		Button resume = menuButton("Resume", true, this::toggleMenu);
		Button full = menuButton(fullscreen ? "Leave fullscreen" : "Fullscreen", false, () -> {
			toggleMenu();
			toggleFullscreen();
		});
		// Separate volume sliders for the music and the sounds (were on/off buttons).
		Node music = volumeRow("Music", audio.musicVolume, v -> {
			audio.musicVolume = v;
			audio.musicOn = v > 0.001f;
		});
		Node sounds = volumeRow("Sounds", audio.sfxVolume, v -> {
			audio.sfxVolume = v;
			audio.sfxOn = v > 0.001f;
		});
		String resetText = game == world ? "Reset world" : "New round";
		Button reset = menuButton(resetText, false, null);
		reset.setOnAction(e -> {
			if (!"confirm".equals(reset.getUserData()) && game == world) {
				reset.setUserData("confirm");
				reset.setText("Sure? Click again");
				reset.getStyleClass().add("arcade-danger");
				audio.play("click");
				return;
			}
			if (game == world) {
				world.newWorld(System.nanoTime());
			} else {
				replace(game);
			}
			toggleMenu();
			startTransition(gameTitle(game), null);
			audio.play("whoosh");
			saveSoon();
		});
		Button settings = menuButton("Launcher settings", false, () -> {
			rest();
			host.openSettings();
		});
		Button back = menuButton("Back to the launcher", false, () -> {
			menuLayer.setVisible(false);
			rest();
		});
		GridPane buttons = new GridPane();
		buttons.setHgap(8);
		buttons.setVgap(8);
		for (int c = 0; c < 3; c++) {
			javafx.scene.layout.ColumnConstraints third = new javafx.scene.layout.ColumnConstraints();
			third.setPercentWidth(100 / 3.0);
			buttons.getColumnConstraints().add(third);
		}
		for (Button b : new Button[] {resume, full, reset, settings, back}) {
			b.setMaxWidth(Double.MAX_VALUE);
		}
		buttons.add(resume, 0, 0, 2, 1);
		buttons.add(full, 2, 0);
		buttons.add(reset, 0, 1);
		buttons.add(settings, 1, 1);
		buttons.add(back, 2, 1);
		HBox volumes = new HBox(14, music, sounds);
		HBox.setHgrow(music, Priority.ALWAYS);
		HBox.setHgrow(sounds, Priority.ALWAYS);
		buttons.add(volumes, 0, 2, 3, 1);
		// Friendly fire only means something in a party game of Velo World.
		if (game == world && world.inParty()) {
			Button ff = menuButton("Friendly fire: " + (world.friendlyFire ? "On" : "Off"), false, () -> {
				world.setFriendlyFire(!world.friendlyFire);
				buildMenuContent();
			});
			ff.setMaxWidth(Double.MAX_VALUE);
			ff.setDisable(!world.canSetFriendlyFire());
			if (!world.canSetFriendlyFire()) {
				ff.setText("Friendly fire: " + (world.friendlyFire ? "On" : "Off") + " (the host decides)");
			}
			buttons.add(ff, 0, 3, 3, 1);
		}
		VBox head = new VBox(4, title, sub);
		menuCard.getChildren().setAll(head, tiles, buttons);
	}

	/** "Music  [-----o----]  35%": a slider row for one volume. */
	private Node volumeRow(String label, float value, java.util.function.Consumer<Float> set) {
		Label name = new Label(label);
		name.getStyleClass().add("arcade-sub");
		name.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 12px;");
		name.setMinWidth(Region.USE_PREF_SIZE);
		javafx.scene.control.Slider slider = new javafx.scene.control.Slider(0, 1, value);
		slider.getStyleClass().add("arcade-slider");
		HBox.setHgrow(slider, Priority.ALWAYS);
		Label pct = new Label(Math.round(value * 100) + "%");
		pct.getStyleClass().add("arcade-sub");
		pct.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 12px;");
		pct.setMinWidth(38);
		slider.valueProperty().addListener((o, a, b) -> {
			set.accept(b.floatValue());
			pct.setText(Math.round(b.doubleValue() * 100) + "%");
			saveSoon();
		});
		slider.setOnMouseReleased(e -> audio.play("click"));
		HBox row = new HBox(10, name, slider, pct);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("arcade-volume");
		row.setMaxWidth(Double.MAX_VALUE);
		return row;
	}

	/** A fresh round of Snake / Blockfall (keeps the best score). */
	private void replace(MiniGame which) {
		JsonObject keep = new JsonObject();
		which.save(keep);
		if (which == snake) {
			snake = new SnakeGame(this);
			snake.load(keep);
			game = snake;
		} else {
			blocks = new BlockfallGame(this);
			blocks.load(keep);
			game = blocks;
		}
	}

	private Node gameTile(MiniGame which, String name, String text) {
		Canvas art = new Canvas(132, 60);
		GraphicsContext g = art.getGraphicsContext2D();
		g.setImageSmoothing(false);
		paintTileArt(g, which);
		Label n = new Label(name);
		n.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 13px; -fx-font-weight: bold;");
		n.getStyleClass().add("arcade-tile-name");
		Label d = new Label(text);
		d.getStyleClass().add("arcade-tile-text");
		VBox box = new VBox(6, art, n, d);
		box.getStyleClass().add("arcade-game-tile");
		if (which == game) {
			box.getStyleClass().add("arcade-game-tile-on");
		}
		box.setOnMouseClicked(e -> {
			audio.play("click");
			switchTo(which);
		});
		box.setOnMouseEntered(e -> audio.play("turn"));
		return box;
	}

	private void paintTileArt(GraphicsContext g, MiniGame which) {
		Color bg = Backdrop.mix(backdrop.background(), backdrop.accent(), 0.16);
		g.setFill(bg);
		g.save();
		g.translate(0, -10);
		g.fillRect(0, 0, 132, 72);
		g.setFill(Backdrop.mix(backdrop.background(), backdrop.accent(), 0.3));
		g.fillRect(0, 44, 132, 28);
		if (which == world) {
			for (int i = 0; i < 9; i++) {
				g.drawImage(Items.icon(Items.GRASS), i * 16 - 4, 56, 16, 16);
			}
			g.drawImage(Items.icon(Items.TRUNK), 96, 40, 16, 16);
			g.drawImage(Items.icon(Items.TRUNK), 96, 24, 16, 16);
			for (int dx = -1; dx <= 1; dx++) {
				g.drawImage(Items.icon(Items.LEAVES), 96 + dx * 16, 10, 16, 16);
			}
			if (rig != null) {
				rig.drawSide(g, 40, 56, 1.5, 1, 18, -14, -60, 0, 1, Items.icon(Items.STONE_PICK), 45);
			}
		} else if (which == snake) {
			if (rig != null) {
				for (int i = 5; i >= 1; i--) {
					g.drawImage(i == 5 ? rig.pants : rig.shirt, 22 + i * -3 + (5 - i) * 16, 40, 14, 14);
				}
				g.save();
				g.translate(104, 47);
				g.rotate(90);
				g.drawImage(rig.face, -9, -9, 18, 18);
				g.restore();
			}
			g.drawImage(Items.icon(Items.RUBY), 106, 18, 16, 16);
		} else {
			int[][] cells = {{1, 3, 0}, {2, 3, 0}, {3, 3, 2}, {4, 3, 2}, {5, 3, 2}, {6, 3, 4}, {2, 2, 1}, {5, 2, 3}, {6, 2, 4}, {6, 1, 4},
					{3, 0, 5}};
			int[] kinds = {Items.RUBY_ORE, Items.STONE, Items.PLANKS, Items.GRASS, Items.BRICK, Items.IRON_ORE};
			for (int[] c : cells) {
				g.drawImage(Items.icon(kinds[c[2]]), 10 + c[0] * 14, 12 + c[1] * 14, 14, 14);
			}
		}
		g.restore();
	}

	private Button menuButton(String text, boolean primary, Runnable action) {
		Button button = new Button(text);
		button.getStyleClass().add(primary ? "primary-button" : "arcade-button");
		button.setFocusTraversable(false);
		if (action != null) {
			button.setOnAction(e -> {
				audio.play("click");
				action.run();
			});
		}
		return button;
	}

	private void pop(Node card) {
		card.setOpacity(0);
		card.setScaleX(0.94);
		card.setScaleY(0.94);
		FadeTransition fade = new FadeTransition(Duration.millis(180), card);
		fade.setToValue(1);
		ScaleTransition grow = new ScaleTransition(Duration.millis(260), card);
		grow.setToX(1);
		grow.setToY(1);
		grow.setInterpolator(net.veloclient.launcher.ui.UiMotion.BACK_OUT_SOFT);
		new ParallelTransition(fade, grow).play();
	}

	private static void fade(Node node, double to, double millis) {
		FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
		fade.setToValue(to);
		fade.play();
	}

	/** Opens the inventory (with its crafting list) - right-clicking a workbench or anvil in Velo World. */
	void openCrafting() {
		if (inventoryLayer == null || !inventoryLayer.isVisible()) {
			toggleInventory();
		}
	}

		private void toggleInventory() {
		if (inventoryLayer == null) {
			buildInventory();
		}
		if (inventoryLayer.isVisible()) {
			returnCursor();
			inventoryLayer.setVisible(false);
			audio.play("close");
			requestFocus();
			return;
		}
		world.releaseAll();
		refreshInventory();
		inventoryLayer.setVisible(true);
		pop(inventoryCard);
		audio.play("open");
	}

	private void buildInventory() {
		slotGrid = new GridPane();
		slotGrid.setHgap(5);
		slotGrid.setVgap(5);
		Label title = new Label("INVENTORY");
		title.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 15px; -fx-font-weight: bold;");
		title.getStyleClass().add("arcade-title");
		Label hint = new Label("Click to pick up and place · right click splits a stack · click outside to drop · Q drops in game");
		hint.getStyleClass().add("arcade-sub");
		hint.setWrapText(true);
		VBox left = new VBox(10, title, slotGrid, hint);
		left.setMaxWidth(9 * 49);

		Label craftTitle = new Label("CRAFTING");
		craftTitle.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 15px; -fx-font-weight: bold;");
		craftTitle.getStyleClass().add("arcade-title");
		benchNote = new Label();
		benchNote.getStyleClass().add("arcade-sub");
		benchNote.setWrapText(true);
		benchNote.setMaxWidth(260);
		recipeList = new VBox(6);
		ScrollPane scroll = new ScrollPane(recipeList);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setPrefViewportHeight(250);
		scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
		VBox right = new VBox(10, craftTitle, benchNote, scroll);
		right.setPrefWidth(270);
		right.setMinWidth(230);
		HBox card = new HBox(22, left, right);
		card.getStyleClass().add("arcade-menu");
		card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		Region scrim = new Region();
		scrim.getStyleClass().add("arcade-scrim");
		scrim.setOnMouseClicked(e -> {
			if (cursorCount > 0) {
				world.dropStack(cursorId, cursorCount);
				cursorId = 0;
				cursorCount = 0;
				cursorView.setImage(null);
				refreshInventory();
			} else {
				toggleInventory();
			}
		});
		inventoryFit = new javafx.scene.Group(card);
		inventoryCard = card;
		inventoryLayer = new StackPane(scrim, inventoryFit);
		inventoryLayer.setVisible(false);
		getChildren().add(getChildren().size() - 1, inventoryLayer);
	}

	private void refreshInventory() {
		if (inventoryLayer == null) {
			return;
		}
		slotGrid.getChildren().clear();
		for (int i = 0; i < WorldGame.SLOTS; i++) {
			int slot = i;
			StackPane cell = new StackPane();
			cell.getStyleClass().add("arcade-slot");
			if (i < 9) {
				cell.getStyleClass().add("arcade-slot-hotbar");
			}
			if (i == world.selected) {
				cell.getStyleClass().add("arcade-slot-selected");
			}
			cell.setMinSize(44, 44);
			cell.setPrefSize(44, 44);
			if (world.counts[i] > 0) {
				ImageView icon = new ImageView(Items.icon(world.ids[i]));
				icon.setSmooth(false);
				icon.setFitWidth(32);
				icon.setFitHeight(32);
				cell.getChildren().add(icon);
				if (world.counts[i] > 1) {
					Label count = new Label(String.valueOf(world.counts[i]));
					count.getStyleClass().add("arcade-count");
					count.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 11px;");
					StackPane.setAlignment(count, Pos.BOTTOM_RIGHT);
					StackPane.setMargin(count, new Insets(0, 3, 1, 0));
					cell.getChildren().add(count);
				}
				javafx.scene.control.Tooltip.install(cell, new javafx.scene.control.Tooltip(Items.name(world.ids[i])));
			}
			cell.setOnMousePressed(e -> {
				clickSlot(slot, e.getButton() == MouseButton.SECONDARY);
				e.consume();
			});
			slotGrid.add(cell, i % 9, i / 9);
		}
		boolean bench = world.stationReady(Items.Station.BENCH);
		boolean anvil = world.stationReady(Items.Station.ANVIL);
		benchNote.setText(anvil ? "Anvil nearby - everything can be forged" : bench ? "Workbench nearby · metal gear needs an anvil (5 iron)"
				: "Tools need a workbench nearby (6 planks)");
		recipeList.getChildren().clear();
		for (Items.Recipe recipe : Items.RECIPES) {
			boolean can = world.canCraft(recipe);
			ImageView icon = new ImageView(Items.icon(recipe.result().id()));
			icon.setSmooth(false);
			icon.setFitWidth(32);
			icon.setFitHeight(32);
			Label name = new Label(Items.name(recipe.result().id()) + (recipe.result().count() > 1 ? "  x" + recipe.result().count() : ""));
			name.getStyleClass().add("arcade-recipe-name");
			HBox needs = new HBox(6);
			needs.setAlignment(Pos.CENTER_LEFT);
			for (Items.Stack need : recipe.needs()) {
				ImageView small = new ImageView(Items.icon(need.id()));
				small.setSmooth(false);
				small.setFitWidth(16);
				small.setFitHeight(16);
				Label n = new Label(need.count() + "");
				n.getStyleClass().add(world.count(need.id()) >= need.count() ? "arcade-need-ok" : "arcade-need-missing");
				n.setStyle("-fx-font-family: 'Monocraft'; -fx-font-size: 11px;");
				needs.getChildren().addAll(small, n);
			}
			if (recipe.station() != Items.Station.HAND) {
				boolean ready = world.stationReady(recipe.station());
				ImageView benchIcon = new ImageView(Items.icon(recipe.station() == Items.Station.ANVIL ? Items.ANVIL : Items.BENCH));
				benchIcon.setSmooth(false);
				benchIcon.setFitWidth(16);
				benchIcon.setFitHeight(16);
				benchIcon.setOpacity(ready ? 0.9 : 0.35);
				needs.getChildren().add(benchIcon);
			}
			VBox text = new VBox(3, name, needs);
			HBox row = new HBox(10, icon, text);
			row.setAlignment(Pos.CENTER_LEFT);
			row.getStyleClass().add("arcade-recipe");
			if (!can) {
				row.getStyleClass().add("arcade-recipe-off");
			}
			row.setOnMouseClicked(e -> {
				if (world.craft(recipe)) {
					refreshInventory();
				} else {
					audio.play("turn");
				}
			});
			recipeList.getChildren().add(row);
		}
	}

	private void clickSlot(int slot, boolean right) {
		int id = world.ids[slot];
		int count = world.counts[slot];
		if (cursorCount == 0) {
			if (count == 0) {
				return;
			}
			int take = right ? (count + 1) / 2 : count;
			cursorId = id;
			cursorCount = take;
			world.counts[slot] -= take;
			if (world.counts[slot] == 0) {
				world.ids[slot] = 0;
			}
		} else if (count == 0 || id == cursorId) {
			int room = Items.maxStack(cursorId) - count;
			int put = Math.min(room, right ? 1 : cursorCount);
			if (put <= 0) {
				return;
			}
			world.ids[slot] = cursorId;
			world.counts[slot] = count + put;
			cursorCount -= put;
		} else if (!right) {
			world.ids[slot] = cursorId;
			world.counts[slot] = cursorCount;
			cursorId = id;
			cursorCount = count;
		}
		if (cursorCount == 0) {
			cursorId = 0;
		}
		cursorView.setImage(cursorCount > 0 ? Items.icon(cursorId) : null);
		audio.play("click", 0.5f, 1.3);
		refreshInventory();
	}

	private void returnCursor() {
		if (cursorCount > 0) {
			world.give(cursorId, cursorCount);
		}
		cursorId = 0;
		cursorCount = 0;
		cursorView.setImage(null);
	}

	private void moveCursor(double x, double y) {
		cursorView.relocate(x - 16, y - 16);
		cursorView.toFront();
	}

	// ------------------------------------------------------------------ saving

	private static Path file() {
		return VeloPaths.root().resolve("arcade.json");
	}

	/** Screenshot tours never touch the player's save. */
	static final boolean TOUR = System.getProperty("velo.launcherTour") != null;

	private void load() {
		loaded = true;
		if (TOUR) {
			return;
		}
		try {
			Path file = file();
			if (!Files.exists(file)) {
				return;
			}
			JsonObject in = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			audio.musicVolume = in.has("musicVolume") ? in.get("musicVolume").getAsFloat()
					: in.has("music") && !in.get("music").getAsBoolean() ? 0f : 0.35f;
			audio.sfxVolume = in.has("soundVolume") ? in.get("soundVolume").getAsFloat()
					: in.has("sounds") && !in.get("sounds").getAsBoolean() ? 0f : 0.5f;
			audio.musicOn = audio.musicVolume > 0.001f;
			audio.sfxOn = audio.sfxVolume > 0.001f;
			if (in.has("world")) {
				world.load(in.getAsJsonObject("world"));
			}
			snake.load(in);
			blocks.load(in);
			String last = in.has("game") ? in.get("game").getAsString() : "world";
			game = "snake".equals(last) ? snake : "blocks".equals(last) ? blocks : world;
		} catch (Exception e) {
			net.veloclient.launcher.LauncherLog.info("Arcade save unreadable, starting fresh: " + e);
		}
	}

	public void save() {
		if (!loaded || disposed || TOUR) {
			return;
		}
		saveDelay = -1;
		try {
			JsonObject out = new JsonObject();
			out.addProperty("game", game.id());
			out.addProperty("music", audio.musicOn);
			out.addProperty("sounds", audio.sfxOn);
			out.addProperty("musicVolume", audio.musicVolume);
			out.addProperty("soundVolume", audio.sfxVolume);
			JsonObject w = new JsonObject();
			world.save(w);
			out.add("world", w);
			snake.save(out);
			blocks.save(out);
			Path file = file();
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("arcade.json.tmp");
			Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			net.veloclient.launcher.LauncherLog.info("Arcade save failed: " + e);
		}
	}
}
