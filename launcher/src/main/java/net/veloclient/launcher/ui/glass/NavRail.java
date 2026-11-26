package net.veloclient.launcher.ui.glass;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import net.veloclient.launcher.ui.Icons;
import net.veloclient.launcher.ui.UiImages;
import net.veloclient.launcher.ui.UiMotion;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The glass launcher's side nav (2026-11-25 design): a 72 px frosted column with the logo, the pages as icons, Settings at
 * the bottom and the account head under it. Hovering an icon shows its name in a small glass tooltip to the right; the
 * white pill behind the active page glides to the next one.
 */
public final class NavRail extends Pane {

	public interface Host {
		void navigate(String page);

		void openAccountMenu(Node anchor);
	}

	private static final double WIDTH = 72;
	private static final double ICON_DARK = 0x1A0A10;

	private final VBox bar = new VBox(6);
	private final Region selection = new Region();
	private final Label tip = new Label();
	private final Map<String, Button> buttons = new LinkedHashMap<>();
	private final Map<String, Region> dots = new LinkedHashMap<>();
	private final StackPane avatar = new StackPane();
	private final Button account = new Button();
	private String active = "";
	private Timeline selectionMove;
	private ParallelTransition tipIn;

	public NavRail(Host host, boolean tester) {
		getStyleClass().add("nav-rail");
		setMinWidth(WIDTH);
		setPrefWidth(WIDTH);
		setMaxWidth(WIDTH);
		setPickOnBounds(false);
		setViewOrder(-10);

		bar.getStyleClass().add("nav-bar");
		bar.setAlignment(Pos.TOP_CENTER);
		bar.setPadding(new Insets(18, 10, 14, 10));
		ImageView logo = new ImageView(UiImages.resource("/net/veloclient/launcher/images/logo.png", 40, 40));
		StackPane logoBox = new StackPane(logo);
		logoBox.setPadding(new Insets(0, 0, 14, 0));
		logoBox.setOnMouseClicked(e -> host.navigate("home"));
		logoBox.setStyle("-fx-cursor: hand;");
		bar.getChildren().add(logoBox);
		selection.getStyleClass().add("nav-selection");
		selection.setManaged(false);
		selection.setMouseTransparent(true);
		selection.setOpacity(0);
		bar.getChildren().add(selection);

		String[][] top = {{"home", "Home", "home"}, {"library", "Profiles", "layers"}, {"locker", "Locker", "hanger"}, {"store", "Store", "store"},
				{"friends", "Friends", "friends"}, {"servers", "Servers", "server"}, {"news", "News", "news"}, {"clips", "Clips", "clips"}};
		for (String[] page : top) {
			bar.getChildren().add(item(page[0], page[1], page[2], host));
		}
		if (tester) {
			bar.getChildren().add(item("testing", "Testing", "bug", host));
		}
		Region fill = new Region();
		VBox.setVgrow(fill, Priority.ALWAYS);
		bar.getChildren().add(fill);
		bar.getChildren().add(item("customize", "Customize", "palette", host));
		bar.getChildren().add(item("settings", "Settings", "settings", host));

		avatar.setMinSize(32, 32);
		avatar.setMaxSize(32, 32);
		Rectangle clip = new Rectangle(32, 32);
		clip.setArcWidth(16);
		clip.setArcHeight(16);
		avatar.setClip(clip);
		account.setGraphic(avatar);
		account.getStyleClass().setAll("button", "nav-account");
		account.setOnAction(e -> host.openAccountMenu(account));
		hoverTip(account, "Account");
		VBox.setMargin(account, new Insets(6, 0, 0, 0));
		bar.getChildren().add(account);

		tip.getStyleClass().add("nav-tip");
		tip.setManaged(false);
		tip.setMouseTransparent(true);
		tip.setVisible(false);
		getChildren().addAll(bar, tip);
		heightProperty().addListener((o, a, b) -> moveSelection(false));
		Glass.install(bar, 24);
	}

	@Override
	protected void layoutChildren() {
		bar.resizeRelocate(0, 0, WIDTH, getHeight());
		tip.autosize();
	}

	/** A page's button in the rail (the launcher tour lights it up), or null. */
	public Node itemNode(String id) {
		return buttons.get(id);
	}

	private Node item(String id, String name, String icon, Host host) {
		Button button = new Button();
		button.setGraphic(Icons.icon(icon, 22, Color.web("#F4EFEE")));
		button.getStyleClass().setAll("button", "nav-item", "no-motion");
		button.setOnAction(e -> {
			hideTip();
			host.navigate(id);
		});
		hoverTip(button, name);
		buttons.put(id, button);
		Region dot = new Region();
		dot.getStyleClass().add("nav-dot");
		dot.setMouseTransparent(true);
		dot.setVisible(false);
		dot.setMinSize(9, 9);
		dot.setMaxSize(9, 9);
		dots.put(id, dot);
		StackPane holder = new StackPane(button, dot);
		StackPane.setAlignment(dot, Pos.TOP_RIGHT);
		StackPane.setMargin(dot, new Insets(7, 7, 0, 0));
		holder.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		return holder;
	}

	private void hoverTip(Node node, String text) {
		node.setOnMouseEntered(e -> showTip(node, text));
		node.setOnMouseExited(e -> hideTip());
	}

	private void showTip(Node anchor, String text) {
		tip.setText(text);
		tip.applyCss();
		tip.autosize();
		Bounds b = sceneToLocal(anchor.localToScene(anchor.getLayoutBounds()));
		tip.relocate(WIDTH + 10, b.getMinY() + (b.getHeight() - tip.getHeight()) / 2);
		if (tipIn != null) {
			tipIn.stop();
		}
		tip.setVisible(true);
		tip.setOpacity(0);
		tip.setTranslateX(-6);
		FadeTransition fade = new FadeTransition(Duration.millis(120), tip);
		fade.setToValue(1);
		TranslateTransition slide = new TranslateTransition(Duration.millis(160), tip);
		slide.setToX(0);
		slide.setInterpolator(UiMotion.EASE_OUT);
		tipIn = new ParallelTransition(fade, slide);
		tipIn.setDelay(Duration.millis(60));
		tipIn.play();
	}

	private void hideTip() {
		if (tipIn != null) {
			tipIn.stop();
		}
		tip.setVisible(false);
	}

	/** Highlights {@code page}'s icon (the white pill glides there); other ids (sub pages) fade it out. */
	public void setActive(String page) {
		active = page == null ? "" : page;
		buttons.forEach((id, button) -> {
			boolean on = id.equals(active);
			button.getStyleClass().remove("nav-item-active");
			if (on) {
				button.getStyleClass().add("nav-item-active");
			}
			Node icon = button.getGraphic();
			javafx.scene.shape.SVGPath path = Icons.pathOf(icon);
			if (path != null) {
				path.setStroke(on ? Color.web("#1A0A10") : Color.web("#F4EFEE"));
			}
			button.setOpacity(on ? 1 : 0.72);
		});
		moveSelection(true);
		if (getScene() == null || bar.getHeight() == 0) {
			javafx.application.Platform.runLater(() -> moveSelection(false));
		}
	}

	private void moveSelection(boolean animate) {
		Button target = buttons.get(active);
		if (selectionMove != null) {
			selectionMove.stop();
		}
		if (target == null || target.getHeight() <= 0 || target.getParent() == null) {
			fade(0);
			return;
		}
		Bounds b = bar.sceneToLocal(target.localToScene(target.getLayoutBounds()));
		selection.resize(b.getWidth(), b.getHeight());
		selection.setLayoutX(b.getMinX());
		if (!animate || selection.getOpacity() < 0.01) {
			selection.setLayoutY(b.getMinY());
			fade(1);
			return;
		}
		double distance = Math.abs(b.getMinY() - selection.getLayoutY());
		selectionMove = new Timeline(
				new KeyFrame(Duration.millis(130), new KeyValue(selection.scaleYProperty(), 1 + Math.min(0.2, distance / 700), UiMotion.EASE_OUT)),
				new KeyFrame(Duration.millis(320), new KeyValue(selection.layoutYProperty(), b.getMinY(), UiMotion.BACK_OUT_SOFT),
						new KeyValue(selection.scaleYProperty(), 1, Interpolator.EASE_OUT)));
		selectionMove.play();
		fade(1);
	}

	private void fade(double to) {
		FadeTransition fade = new FadeTransition(Duration.millis(160), selection);
		fade.setToValue(to);
		fade.play();
	}

	/** A small accent dot on a page's icon (unread news, friend requests). */
	public void setDot(String page, boolean show) {
		Region dot = dots.get(page);
		if (dot != null) {
			dot.setVisible(show);
		}
	}

	public void setAccount(Node head) {
		avatar.getChildren().setAll(head);
	}
}
