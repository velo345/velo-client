package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The window's top bar: brand, the main pages as underlined text tabs, then search, coins, friends,
 * notifications, settings and the account pill. Pages are switched by id ("home", "library", ...).
 */
public final class TopBar extends HBox {

	public interface Host {
		void navigate(String page);

		void openCoins();

		void openFriends();

		void openNotifications(Node anchor);

		void openAccountMenu(Node anchor);
	}

	private static final String[][] TABS = {
			{"home", "Home"}, {"library", "Library"}, {"servers", "Servers"}, {"store", "Store"},
			{"locker", "Locker"}, {"news", "News"}, {"clips", "Clips"}};

	private final Map<String, Button> tabs = new LinkedHashMap<>();
	private final Label newsCount = new Label();
	private final Label friendsCount = new Label();
	private final Region bellDot = new Region();
	private final Button coins = new Button();
	private final Label coinsText = new Label();
	private final Button account = new Button();
	private final StackPane avatar = new StackPane();
	private final Label accountName = new Label();
	private final TextField search;

	public TopBar(Host host, TextField searchField) {
		getStyleClass().add("topbar");
		setAlignment(Pos.CENTER_LEFT);
		setSpacing(6);

		ImageView logo = new ImageView(UiImages.resource("/net/veloclient/launcher/images/logo.png", 26, 26));
		Label brand = new Label("VELO");
		brand.getStyleClass().add("topbar-brand");
		HBox brandBox = new HBox(9, logo, brand);
		brandBox.setAlignment(Pos.CENTER_LEFT);
		brandBox.setPadding(new Insets(0, 18, 0, 0));
		brandBox.setMinWidth(Region.USE_PREF_SIZE);
		brand.setMinWidth(Region.USE_PREF_SIZE);
		brandBox.setOnMouseClicked(e -> host.navigate("home"));
		brandBox.setStyle("-fx-cursor: hand;");
		getChildren().add(brandBox);

		HBox nav = new HBox(2);
		nav.setAlignment(Pos.CENTER_LEFT);
		nav.getStyleClass().add("no-motion");
		for (String[] tab : TABS) {
			Button button = new Button(tab[1]);
			button.getStyleClass().setAll("button", "topnav-tab", "no-motion");
			button.setOnAction(e -> host.navigate(tab[0]));
			button.setMinWidth(Region.USE_PREF_SIZE);
			if (tab[0].equals("news")) {
				newsCount.getStyleClass().add("count-badge");
				newsCount.setVisible(false);
				newsCount.setManaged(false);
				button.setGraphic(newsCount);
				button.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
				button.setGraphicTextGap(6);
			}
			tabs.put(tab[0], button);
			nav.getChildren().add(button);
		}
		// One underline that slides to the active tab (instead of each tab drawing its own).
		indicator.getStyleClass().add("topnav-indicator");
		indicator.setManaged(false);
		indicator.setMouseTransparent(true);
		indicator.setOpacity(0);
		javafx.scene.layout.Pane navLayer = new javafx.scene.layout.Pane(nav, indicator);
		navLayer.getStyleClass().add("no-motion");
		nav.layoutBoundsProperty().addListener((o, a, b) -> {
			navLayer.setPrefSize(b.getWidth(), b.getHeight());
			moveIndicator(false);
		});
		navLayer.setMinWidth(Region.USE_PREF_SIZE);
		getChildren().add(navLayer);

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		getChildren().add(spacer);

		search = searchField;
		search.getStyleClass().add("topbar-search-field");
		search.setPromptText("Search everything");
		search.setPrefWidth(250);
		search.setMinWidth(120);
		Label kbd = new Label("Ctrl K");
		kbd.getStyleClass().add("kbd");
		kbd.setMouseTransparent(true);
		StackPane searchBox = new StackPane(search, kbd);
		StackPane.setAlignment(kbd, Pos.CENTER_RIGHT);
		StackPane.setMargin(kbd, new Insets(0, 10, 0, 0));
		Node searchIcon = Icons.icon("search", 15, Color.web("#8A8A95"));
		StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
		StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 11));
		searchBox.getChildren().add(searchIcon);
		search.focusedProperty().addListener((o, a, f) -> kbd.setVisible(!f && search.getText().isEmpty()));
		search.textProperty().addListener((o, a, t) -> kbd.setVisible(!search.isFocused() && t.isEmpty()));
		HBox.setMargin(searchBox, new Insets(0, 8, 0, 0));
		getChildren().add(searchBox);

		Label coinGlyph = new Label("V");
		coinGlyph.getStyleClass().add("coin");
		coinGlyph.setMinSize(20, 20);
		coinGlyph.setMaxSize(20, 20);
		coinGlyph.setStyle("-fx-font-size: 11px;");
		coinsText.getStyleClass().add("t-gold");
		coinsText.setStyle("-fx-font-weight: bold;");
		Label plus = new Label("+");
		plus.setStyle("-fx-text-fill: -velo-gold; -fx-font-size: 15px; -fx-padding: 0 0 1 4;");
		HBox coinContent = new HBox(7, coinGlyph, coinsText, plus);
		coinContent.setAlignment(Pos.CENTER_LEFT);
		coins.setGraphic(coinContent);
		coins.getStyleClass().setAll("button", "coin-pill");
		coins.setTooltip(new Tooltip("Velo Coins - get more"));
		coins.setOnAction(e -> host.openCoins());
		coins.setVisible(false);
		coins.setManaged(false);
		coins.setMinWidth(Region.USE_PREF_SIZE);
		getChildren().add(coins);

		Button friends = iconButton("friends", "Friends", () -> host.openFriends());
		friendsCount.getStyleClass().addAll("count-badge");
		friendsCount.setMouseTransparent(true);
		friendsCount.setVisible(false);
		StackPane friendsBox = new StackPane(friends, friendsCount);
		StackPane.setAlignment(friendsCount, Pos.TOP_RIGHT);
		StackPane.setMargin(friendsCount, new Insets(2, 0, 0, 0));
		friendsBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

		Button bell = iconButton("bell", "Notifications", null);
		bell.setOnAction(e -> host.openNotifications(bell));
		bellDot.setStyle("-fx-background-color: -velo-accent-text; -fx-background-radius: 99; -fx-border-color: -velo-background; -fx-border-width: 2; -fx-border-radius: 99;");
		bellDot.setMinSize(10, 10);
		bellDot.setMaxSize(10, 10);
		bellDot.setMouseTransparent(true);
		bellDot.setVisible(false);
		StackPane bellBox = new StackPane(bell, bellDot);
		StackPane.setAlignment(bellDot, Pos.TOP_RIGHT);
		StackPane.setMargin(bellDot, new Insets(8, 9, 0, 0));
		bellBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

		Button settings = iconButton("settings", "Settings", () -> host.navigate("settings"));
		tabs.put("settings", settings);
		HBox icons = new HBox(2, friendsBox, bellBox, settings);
		icons.setAlignment(Pos.CENTER);
		HBox.setMargin(icons, new Insets(0, 6, 0, 6));
		getChildren().add(icons);

		avatar.setMinSize(28, 28);
		avatar.setMaxSize(28, 28);
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(28, 28);
		clip.setArcWidth(28);
		clip.setArcHeight(28);
		avatar.setClip(clip);
		accountName.getStyleClass().add("t-strong");
		accountName.setMaxWidth(130);
		HBox accountContent = new HBox(9, avatar, accountName, Icons.icon("chevron-down", 13, Color.web("#8A8A95")));
		accountContent.setAlignment(Pos.CENTER_LEFT);
		account.setGraphic(accountContent);
		account.getStyleClass().setAll("button", "account-pill");
		account.setOnAction(e -> host.openAccountMenu(account));
		account.setMinWidth(Region.USE_PREF_SIZE);
		getChildren().add(account);

		// Narrow windows: the search box gives way first, then the brand word.
		widthProperty().addListener((o, a, w) -> {
			double width = w.doubleValue();
			searchBox.setVisible(width > 1260);
			searchBox.setManaged(width > 1260);
			brand.setVisible(width > 1020);
			brand.setManaged(width > 1020);
		});
	}

	private Button iconButton(String icon, String tooltip, Runnable action) {
		Button button = new Button();
		button.setGraphic(Icons.icon(icon, 19, Color.web("#C9C9D1")));
		button.getStyleClass().setAll("button", "icon-button", "icon-button-flat");
		button.setTooltip(new Tooltip(tooltip));
		if (action != null) {
			button.setOnAction(e -> action.run());
		}
		return button;
	}

	public TextField searchField() {
		return search;
	}

	private final Region indicator = new Region();
	private String activePage = "";
	private javafx.animation.Timeline indicatorMove;

	/** Underlines the tab for {@code page} (the line slides over); any other id (a sub page like a profile) hides it. */
	public void setActive(String page) {
		activePage = page == null ? "" : page;
		tabs.forEach((id, button) -> {
			button.getStyleClass().remove("topnav-tab-active");
			if (id.equals(activePage) && button.getStyleClass().contains("topnav-tab")) {
				button.getStyleClass().add("topnav-tab-active");
			}
		});
		moveIndicator(true);
		if (getScene() == null || getWidth() == 0) {
			javafx.application.Platform.runLater(() -> moveIndicator(false));
		}
	}

	private void moveIndicator(boolean animate) {
		Button target = tabs.get(activePage);
		if (indicatorMove != null) {
			indicatorMove.stop();
		}
		if (target == null || !target.getStyleClass().contains("topnav-tab") || target.getWidth() <= 0) {
			fadeIndicator(target == null || !target.getStyleClass().contains("topnav-tab") ? 0 : indicator.getOpacity());
			return;
		}
		double inset = 12;
		double x = target.getBoundsInParent().getMinX() + inset;
		double width = Math.max(8, target.getWidth() - inset * 2);
		double y = target.getBoundsInParent().getMaxY() - 2;
		indicator.setLayoutY(y);
		indicator.resize(indicator.getWidth(), 2);
		boolean hidden = indicator.getOpacity() < 0.01;
		if (!animate || hidden) {
			indicator.setTranslateX(x);
			indicator.resize(width, 2);
			fadeIndicator(1);
			return;
		}
		javafx.beans.property.DoubleProperty w = new javafx.beans.property.SimpleDoubleProperty(indicator.getWidth());
		w.addListener((o, a, v) -> indicator.resize(v.doubleValue(), 2));
		indicatorMove = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(280),
				new javafx.animation.KeyValue(indicator.translateXProperty(), x, UiMotion.EASE_OUT),
				new javafx.animation.KeyValue(w, width, UiMotion.EASE_OUT)));
		indicatorMove.play();
		fadeIndicator(1);
	}

	private void fadeIndicator(double to) {
		javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(160), indicator);
		fade.setToValue(to);
		fade.play();
	}

	public void setNewsCount(int unread) {
		newsCount.setText(unread > 9 ? "9+" : String.valueOf(unread));
		newsCount.setVisible(unread > 0);
		newsCount.setManaged(unread > 0);
	}

	public void setFriendsCount(int count) {
		friendsCount.setText(count > 9 ? "9+" : String.valueOf(count));
		friendsCount.setVisible(count > 0);
	}

	public void setNotificationDot(boolean show) {
		bellDot.setVisible(show);
	}

	/** {@code balance < 0} hides the coin pill (not signed in / wallet unknown). */
	public void setCoins(int balance) {
		boolean show = balance >= 0;
		coins.setVisible(show);
		coins.setManaged(show);
		if (show) {
			coinsText.setText(NumberFormat.getIntegerInstance(Locale.US).format(balance));
		}
	}

	public void setAccount(String name, Node head) {
		accountName.setText(name);
		avatar.getChildren().setAll(head);
	}
}
