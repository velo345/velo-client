package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The window shell (2026-11 redesign). Two parts that share one API:
 * <ul>
 * <li>this HBox - the slim strip above the page: search, friends online, notifications, bug report (the window controls
 * are appended by the launcher; it is also the window's drag area);</li>
 * <li>{@link #sidebar()} - the left rail: brand, the pages with icons, Settings under them, then Velo+ / coins and the
 * account switcher at the bottom.</li>
 * </ul>
 * Pages are switched by id ("home", "library", ...).
 */
public final class TopBar extends HBox {

	public interface Host {
		void navigate(String page);

		void openCoins();

		void openFriends();

		void openNotifications(Node anchor);

		void openAccountMenu(Node anchor);
	}

	private static final String[][] PAGES = {
			{"home", "Home", "home"}, {"library", "Library", "library"}, {"servers", "Servers", "server"},
			{"locker", "Locker", "locker"}, {"store", "Store", "store"}, {"friends", "Friends", "friends"},
			{"clips", "Clips", "clips"}, {"news", "News", "news"}};

	private final Map<String, Button> tabs = new LinkedHashMap<>();
	private final Label newsCount = new Label();
	private final Label friendsCount = new Label();
	private final Label friendsOnline = new Label();
	private final Region bellDot = new Region();
	private final Label coinsText = new Label();
	private final Label plusText = new Label();
	private final Button account = new Button();
	private final StackPane avatar = new StackPane();
	private final Label accountName = new Label();
	private final Label accountState = new Label();
	private final TextField search;
	private final VBox sidebar = new VBox(2);
	/** One highlight behind the active row that glides to the next one (instead of each row lighting up on its own). */
	private final Region selection = new Region();
	private javafx.animation.Timeline selectionMove;
	private String activePage = "";
	private boolean compact;
	/** The 72 px slot the window reserves; the sidebar inside it can grow over the page (hover). */
	private javafx.scene.layout.Pane rail;
	private final javafx.beans.property.DoubleProperty reveal = new javafx.beans.property.SimpleDoubleProperty(72);
	private final javafx.scene.shape.Rectangle railClip = new javafx.scene.shape.Rectangle();
	private final Region railShadow = new Region();
	private javafx.animation.Timeline railMove;
	private final javafx.animation.PauseTransition hoverDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(140));
	private static final double RAIL = 72;
	private static final double OPEN = 220;
	private Label brandLabel;
	private HBox strip;
	private Node accountText;
	private Node accountChevron;

	public TopBar(Host host, TextField searchField) {
		getStyleClass().add("topbar");
		setAlignment(Pos.CENTER_LEFT);
		setSpacing(8);
		buildSidebar(host);

		search = searchField;
		search.getStyleClass().add("topbar-search-field");
		search.setPromptText("Search profiles, servers, mods, news");
		search.setPrefWidth(360);
		search.setMinWidth(160);
		Label kbd = new Label("Ctrl K");
		kbd.getStyleClass().add("kbd");
		kbd.setMouseTransparent(true);
		StackPane searchBox = new StackPane(search, kbd);
		StackPane.setAlignment(kbd, Pos.CENTER_RIGHT);
		StackPane.setMargin(kbd, new Insets(0, 10, 0, 0));
		Node searchIcon = Icons.icon("search", 15, Color.web("#8A8486"));
		StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
		StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 11));
		searchBox.getChildren().add(searchIcon);
		search.focusedProperty().addListener((o, a, f) -> kbd.setVisible(!f && search.getText().isEmpty()));
		search.textProperty().addListener((o, a, t) -> kbd.setVisible(!search.isFocused() && t.isEmpty()));
		getChildren().add(searchBox);

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		getChildren().add(spacer);

		// "3 friends online" - a quiet text link, no avatars (2026-11 feedback).
		Region onlineDot = new Region();
		onlineDot.getStyleClass().add("status-square");
		friendsOnline.setGraphic(onlineDot);
		friendsOnline.setGraphicTextGap(8);
		friendsOnline.getStyleClass().add("topbar-friends");
		friendsOnline.setOnMouseClicked(e -> host.openFriends());
		friendsOnline.setVisible(false);
		friendsOnline.setManaged(false);
		getChildren().add(friendsOnline);

		Button bell = iconButton("bell", "Notifications", null);
		bell.setOnAction(e -> host.openNotifications(bell));
		bellDot.getStyleClass().add("bell-dot");
		bellDot.setMinSize(9, 9);
		bellDot.setMaxSize(9, 9);
		bellDot.setMouseTransparent(true);
		bellDot.setVisible(false);
		StackPane bellBox = new StackPane(bell, bellDot);
		StackPane.setAlignment(bellDot, Pos.TOP_RIGHT);
		StackPane.setMargin(bellDot, new Insets(8, 9, 0, 0));
		bellBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

		Button bug = iconButton("bug", "Report a problem (Ctrl+Shift+B)", null);
		bug.setOnAction(e -> BugReportDialog.show(bug.getScene().getWindow()));
		HBox icons = new HBox(2, bellBox, bug);
		icons.setAlignment(Pos.CENTER);
		getChildren().add(icons);

		widthProperty().addListener((o, a, w) -> {
			boolean roomy = w.doubleValue() > 760;
			searchBox.setVisible(roomy);
			searchBox.setManaged(roomy);
		});
	}

	private void buildSidebar(Host host) {
		sidebar.getStyleClass().add("sidebar-rail");
		sidebar.setMinWidth(220);
		sidebar.setPrefWidth(220);
		sidebar.setMaxWidth(220);

		ImageView logo = new ImageView(UiImages.resource("/net/veloclient/launcher/images/logo.png", 28, 28));
		Label brand = new Label("VELO");
		brand.getStyleClass().add("sidebar-brand");
		brandLabel = brand;
		HBox brandBox = new HBox(10, logo, brand);
		brandBox.setAlignment(Pos.CENTER_LEFT);
		brandBox.setPadding(new Insets(2, 10, 20, 10));
		brandBox.setOnMouseClicked(e -> host.navigate("home"));
		brandBox.setStyle("-fx-cursor: hand;");
		sidebar.getChildren().add(brandBox);
		selection.getStyleClass().add("sidebar-selection");
		selection.setManaged(false);
		selection.setMouseTransparent(true);
		selection.setOpacity(0);
		sidebar.getChildren().add(selection);
		sidebar.heightProperty().addListener((o, a, b) -> moveSelection(false));
		sidebar.widthProperty().addListener((o, a, b) -> moveSelection(false));

		for (String[] page : PAGES) {
			Button row = navRow(page[1], page[2], () -> host.navigate(page[0]));
			if (page[0].equals("news")) {
				attachCount(row, newsCount);
			} else if (page[0].equals("friends")) {
				attachCount(row, friendsCount);
			}
			tabs.put(page[0], row);
			sidebar.getChildren().add(row);
		}
		Region divider = new Region();
		divider.getStyleClass().add("sidebar-divider");
		VBox.setMargin(divider, new Insets(8, 10, 8, 10));
		sidebar.getChildren().add(divider);
		Button settings = navRow("Settings", "settings", () -> host.navigate("settings"));
		tabs.put("settings", settings);
		sidebar.getChildren().add(settings);

		Region fill = new Region();
		VBox.setVgrow(fill, Priority.ALWAYS);
		sidebar.getChildren().add(fill);

		// Velo+ and coins: their own strip, apart from who you are.
		Label plusLabel = new Label("VELO+");
		plusLabel.getStyleClass().addAll("t-overline", "sidebar-plus-label");
		plusText.getStyleClass().add("sidebar-strip-value");
		VBox plusCell = new VBox(5, plusLabel, plusText);
		plusCell.getStyleClass().add("sidebar-strip-cell");
		plusCell.setOnMouseClicked(e -> host.navigate("store"));
		Label coinsLabel = new Label("COINS");
		coinsLabel.getStyleClass().add("t-overline");
		coinsText.getStyleClass().addAll("sidebar-strip-value", "sidebar-coins");
		VBox coinsCell = new VBox(5, coinsLabel, coinsText);
		coinsCell.getStyleClass().add("sidebar-strip-cell");
		coinsCell.setOnMouseClicked(e -> host.openCoins());
		HBox.setHgrow(plusCell, Priority.ALWAYS);
		HBox.setHgrow(coinsCell, Priority.ALWAYS);
		plusCell.setMaxWidth(Double.MAX_VALUE);
		coinsCell.setMaxWidth(Double.MAX_VALUE);
		Region cellLine = new Region();
		cellLine.getStyleClass().add("sidebar-strip-line");
		strip = new HBox(plusCell, cellLine, coinsCell);
		strip.getStyleClass().add("sidebar-strip");
		VBox.setMargin(strip, new Insets(10, 0, 8, 0));
		sidebar.getChildren().add(strip);
		setCoins(-1);

		avatar.setMinSize(32, 32);
		avatar.setMaxSize(32, 32);
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(32, 32);
		clip.setArcWidth(10);
		clip.setArcHeight(10);
		avatar.setClip(clip);
		accountName.getStyleClass().add("t-strong");
		accountName.setMaxWidth(118);
		Region stateDot = new Region();
		stateDot.getStyleClass().add("status-square");
		stateDot.setScaleX(0.75);
		stateDot.setScaleY(0.75);
		accountState.setGraphic(stateDot);
		accountState.setGraphicTextGap(6);
		accountState.getStyleClass().add("t-muted");
		accountState.setStyle("-fx-font-size: 11px;");
		accountState.setText("Online");
		VBox who = new VBox(3, accountName, accountState);
		HBox.setHgrow(who, Priority.ALWAYS);
		Node chevron = Icons.icon("chevrons-updown", 15, Color.web("#8A8486"));
		accountText = who;
		accountChevron = chevron;
		HBox accountContent = new HBox(10, avatar, who, chevron);
		accountContent.setAlignment(Pos.CENTER_LEFT);
		account.setGraphic(accountContent);
		account.getStyleClass().setAll("button", "sidebar-account");
		account.setMaxWidth(Double.MAX_VALUE);
		account.setTooltip(new Tooltip("Switch account"));
		account.setOnAction(e -> host.openAccountMenu(account));
		sidebar.getChildren().add(account);
	}

	private Button navRow(String text, String icon, Runnable action) {
		Button row = new Button(text);
		row.setGraphic(Icons.icon(icon, 18, Color.web("#A9A2A3")));
		row.setGraphicTextGap(12);
		row.getStyleClass().setAll("button", "sidebar-nav", "no-motion");
		row.setMaxWidth(Double.MAX_VALUE);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setOnAction(e -> {
			// the unfolded sidebar stays open after a pick - it folds when the mouse leaves it (it used to snap shut)
			action.run();
		});
		return row;
	}

	/** A small count chip on the right end of a nav row (unread news, friend requests). */
	private static void attachCount(Button row, Label count) {
		count.getStyleClass().add("sidebar-count");
		count.setVisible(false);
		count.setManaged(false);
		Region push = new Region();
		HBox.setHgrow(push, Priority.ALWAYS);
		Node icon = row.getGraphic();
		Label text = new Label(row.getText());
		text.getStyleClass().add("sidebar-nav-text");
		HBox content = new HBox(12, icon, text, push, count);
		content.setAlignment(Pos.CENTER_LEFT);
		content.setMaxWidth(Double.MAX_VALUE);
		content.prefWidthProperty().bind(row.widthProperty().subtract(24));
		row.setText(null);
		row.setGraphic(content);
	}

	private Button iconButton(String icon, String tooltip, Runnable action) {
		Button button = new Button();
		button.setGraphic(Icons.icon(icon, 19, Color.web("#C9C2C3")));
		button.getStyleClass().setAll("button", "icon-button", "icon-button-flat");
		button.setTooltip(new Tooltip(tooltip));
		if (action != null) {
			button.setOnAction(e -> action.run());
		}
		return button;
	}

	/**
	 * Narrow windows (2026-11): the rail folds to 72 px - icons only, names as tooltips, Velo+/coins hidden, the account
	 * button just the head - so every page keeps its full width next to it.
	 */
	public void setCompact(boolean value) {
		if (compact == value) {
			return;
		}
		compact = value;
		double width = value ? RAIL : OPEN;
		sidebar.setMinWidth(width);
		sidebar.setPrefWidth(width);
		sidebar.setMaxWidth(width);
		brandLabel.setVisible(!value);
		brandLabel.setManaged(!value);
		strip.setVisible(!value);
		strip.setManaged(!value);
		accountText.setVisible(!value);
		accountText.setManaged(!value);
		accountChevron.setVisible(!value);
		accountChevron.setManaged(!value);
		tabs.forEach((id, row) -> {
			if (row.getGraphic() instanceof HBox content && row.getText() == null) {
				// rows with a count chip carry their name inside the graphic
				for (Node child : content.getChildren()) {
					// icons only: the name, the spacer and the count chip step aside so the icon sits centred like the others
					if (child != content.getChildren().getFirst()) {
						child.setVisible(!value && (!(child instanceof Label l) || !l.getStyleClass().contains("sidebar-count") || !l.getText().isEmpty()));
						child.setManaged(!value);
					}
				}
				content.setAlignment(value ? Pos.CENTER : Pos.CENTER_LEFT);
				row.setAlignment(value ? Pos.CENTER : Pos.CENTER_LEFT);
				content.prefWidthProperty().unbind();
				if (!value) {
					content.prefWidthProperty().bind(row.widthProperty().subtract(24));
					// the count chip is only shown again when it has something to say
					for (Node child : content.getChildren()) {
						if (child instanceof Label l && l.getStyleClass().contains("sidebar-count")) {
							boolean any = !l.getText().isEmpty() && !"0".equals(l.getText()) && l.getProperties().get("shown") == Boolean.TRUE;
							l.setVisible(any);
							l.setManaged(any);
						}
					}
				} else {
					content.setPrefWidth(Region.USE_COMPUTED_SIZE);
				}
			} else {
				row.setContentDisplay(value ? javafx.scene.control.ContentDisplay.GRAPHIC_ONLY : javafx.scene.control.ContentDisplay.LEFT);
				row.setAlignment(value ? Pos.CENTER : Pos.CENTER_LEFT);
			}
			String name = row.getText() != null ? row.getText() : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
			row.setTooltip(value ? new Tooltip(name) : null);
		});
		sidebar.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), value);
		javafx.application.Platform.runLater(() -> moveSelection(false));
	}

	/**
	 * The left rail as the window uses it (2026-11, like the redesign): a fixed 72 px slot with the icons; hovering it
	 * unfolds the full sidebar OVER the page (a soft shadow, the page doesn't move) and leaving it, or picking a page,
	 * folds it back. The full sidebar is laid out once at 220 px and revealed by an animated clip, so nothing re-wraps.
	 */
	public javafx.scene.layout.Pane rail() {
		if (rail != null) {
			return rail;
		}
		railShadow.getStyleClass().add("sidebar-shadow");
		railShadow.setManaged(false);
		railShadow.setMouseTransparent(true);
		railShadow.setOpacity(0);
		sidebar.setManaged(false);
		sidebar.setClip(railClip);
		rail = new javafx.scene.layout.Pane(railShadow, sidebar);
		rail.setMinWidth(RAIL);
		rail.setPrefWidth(RAIL);
		rail.setMaxWidth(RAIL);
		rail.setViewOrder(-10); // drawn above the page, so the open sidebar lies on top of it
		rail.setPickOnBounds(false);
		setCompact(true);
		Runnable place = () -> {
			double h = rail.getHeight();
			double w = compact ? RAIL : OPEN;
			sidebar.resizeRelocate(0, 0, w, h);
			railClip.setWidth(reveal.get());
			railClip.setHeight(h);
			railShadow.resizeRelocate(0, 0, reveal.get(), h);
		};
		rail.heightProperty().addListener((o, a, b) -> place.run());
		reveal.addListener((o, a, b) -> {
			railClip.setWidth(b.doubleValue());
			railShadow.resize(b.doubleValue(), rail.getHeight());
			railShadow.setOpacity(Math.max(0, Math.min(1, (b.doubleValue() - RAIL) / (OPEN - RAIL))));
		});
		hoverDelay.setOnFinished(e -> expand());
		if (System.getProperty("velo.tourSidebarOpen") != null && System.getProperty("velo.launcherTour") != null) {
			// Dev (screenshot tour): show the unfolded sidebar - a tour can't hover.
			javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
			later.setOnFinished(e -> expand());
			later.play();
		}
		sidebar.setOnMouseEntered(e -> hoverDelay.playFromStart());
		sidebar.setOnMouseExited(e -> {
			hoverDelay.stop();
			if (!account.isHover()) {
				collapse();
			}
		});
		return rail;
	}

	/** What fades with the fold: names, the Velo+/coins strip, the account name (icons stay put). */
	private Node[] fadeables() {
		return java.util.stream.Stream.of(brandLabel, strip, accountText, accountChevron).filter(java.util.Objects::nonNull).toArray(Node[]::new);
	}

	private void expand() {
		if (rail == null || !compact && reveal.get() >= OPEN - 1) {
			return;
		}
		setCompact(false);
		for (Node n : fadeables()) {
			n.setOpacity(0);
		}
		sidebar.resizeRelocate(0, 0, OPEN, rail.getHeight());
		animateReveal(OPEN, null);
	}

	/** Folds the sidebar back to its icons (mouse left, or a page was picked). */
	public void collapse() {
		hoverDelay.stop();
		if (rail == null || compact) {
			return;
		}
		animateReveal(RAIL, () -> {
			setCompact(true);
			sidebar.resizeRelocate(0, 0, RAIL, rail.getHeight());
			for (Node n : fadeables()) {
				n.setOpacity(1);
			}
		});
	}

	private void animateReveal(double to, Runnable done) {
		if (railMove != null) {
			railMove.stop();
		}
		java.util.List<javafx.animation.KeyValue> values = new java.util.ArrayList<>();
		values.add(new javafx.animation.KeyValue(reveal, to, UiMotion.EASE_OUT));
		// the texts fade with the fold instead of popping in or vanishing at the end
		for (Node n : fadeables()) {
			values.add(new javafx.animation.KeyValue(n.opacityProperty(), to > RAIL ? 1.0 : 0.0, UiMotion.EASE_OUT));
		}
		railMove = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(to > RAIL ? 260 : 200),
				values.toArray(new javafx.animation.KeyValue[0])));
		if (done != null) {
			railMove.setOnFinished(e -> done.run());
		}
		railMove.play();
	}

	/** The left rail; the launcher puts it beside everything else. */
	public VBox sidebar() {
		return sidebar;
	}

	public TextField searchField() {
		return search;
	}

	/** Highlights the row for {@code page} (the highlight glides there); any other id (a sub page) fades it out. */
	public void setActive(String page) {
		activePage = page == null ? "" : page;
		tabs.forEach((id, button) -> {
			button.getStyleClass().remove("sidebar-nav-active");
			if (id.equals(activePage)) {
				button.getStyleClass().add("sidebar-nav-active");
			}
		});
		moveSelection(true);
		if (sidebar.getScene() == null || sidebar.getHeight() == 0) {
			javafx.application.Platform.runLater(() -> moveSelection(false));
		}
	}

	private void moveSelection(boolean animate) {
		Button target = tabs.get(activePage);
		if (selectionMove != null) {
			selectionMove.stop();
		}
		if (target == null || target.getHeight() <= 0) {
			fadeSelection(target == null ? 0 : selection.getOpacity());
			return;
		}
		javafx.geometry.Bounds b = target.getBoundsInParent();
		selection.resize(b.getWidth(), b.getHeight());
		selection.setLayoutX(b.getMinX());
		if (!animate || selection.getOpacity() < 0.01) {
			selection.setLayoutY(b.getMinY());
			fadeSelection(1);
			return;
		}
		// A quick glide with a soft overshoot; a far jump stretches the bar a little on the way (feels like it travels).
		double distance = Math.abs(b.getMinY() - selection.getLayoutY());
		selectionMove = new javafx.animation.Timeline(
				new javafx.animation.KeyFrame(javafx.util.Duration.millis(140),
						new javafx.animation.KeyValue(selection.scaleYProperty(), 1 + Math.min(0.18, distance / 900), UiMotion.EASE_OUT)),
				new javafx.animation.KeyFrame(javafx.util.Duration.millis(320),
						new javafx.animation.KeyValue(selection.layoutYProperty(), b.getMinY(), UiMotion.BACK_OUT_SOFT),
						new javafx.animation.KeyValue(selection.scaleYProperty(), 1, UiMotion.EASE_OUT)));
		selectionMove.play();
		fadeSelection(1);
	}

	private void fadeSelection(double to) {
		javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(160), selection);
		fade.setToValue(to);
		fade.play();
	}

	public void setNewsCount(int unread) {
		newsCount.setText(unread > 9 ? "9+" : String.valueOf(unread));
		newsCount.getProperties().put("shown", unread > 0);
		newsCount.setVisible(unread > 0 && !compact);
		newsCount.setManaged(unread > 0 && !compact);
	}

	/** Pending friend requests (the badge on Friends). */
	public void setFriendsCount(int count) {
		friendsCount.setText(count > 9 ? "9+" : String.valueOf(count));
		friendsCount.getProperties().put("shown", count > 0);
		friendsCount.setVisible(count > 0 && !compact);
		friendsCount.setManaged(count > 0 && !compact);
	}

	/** Friends online right now ({@code < 1} hides the line). */
	public void setFriendsOnline(int count) {
		friendsOnline.setText(count == 1 ? "1 friend online" : count + " friends online");
		friendsOnline.setVisible(count > 0);
		friendsOnline.setManaged(count > 0);
	}

	public void setNotificationDot(boolean show) {
		bellDot.setVisible(show);
	}

	/** {@code balance < 0}: not signed in / wallet unknown - the strip shows dashes. */
	public void setCoins(int balance) {
		coinsText.setText(balance >= 0 ? NumberFormat.getIntegerInstance(Locale.US).format(balance) : "–");
	}

	/** The Velo+ cell: "Active" for members, "Get it" otherwise. */
	public void setPlus(boolean member) {
		plusText.setText(member ? "Active" : "Get it");
		plusText.getStyleClass().remove("sidebar-strip-muted");
		if (!member) {
			plusText.getStyleClass().add("sidebar-strip-muted");
		}
	}

	public void setAccount(String name, Node head) {
		accountName.setText(name);
		avatar.getChildren().setAll(head);
		boolean signedIn = !"Sign in".equals(name);
		accountState.setText(signedIn ? "Online" : "Not signed in");
		accountState.getGraphic().setVisible(signedIn);
	}
}
