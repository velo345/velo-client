package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.CurrencyStore;
import net.veloclient.launcher.data.StoreCatalog;
import net.veloclient.launcher.data.StoreItem;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.theme.LauncherTheme;

import java.io.ByteArrayInputStream;

/** The cosmetics store (design spec's Store): a grid of {@link StoreItem} cards plus a clickable Velo Coins balance pill top-right. Only "Capes" exists as a category today. */
public final class StoreView {

	public interface Host {
		Stage owner();

		LauncherTheme theme();

		MinecraftSession session();

		void openItem(StoreItem item);

		void rebuild();

		/** Opens the Velo Coins page (buy coins / free coins). */
		default void openCoins() {
		}
	}

	private StoreView() {
	}

	public static Node build(Host host) {
		Label heading = new Label("Store");
		heading.getStyleClass().add("page-title");
		Label sub = new Label("Capes for your Velo profile - preview them on your own skin before buying.");
		sub.getStyleClass().add("page-subtitle");
		VBox titles = new VBox(2, heading, sub);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox headerRow = new HBox(10, titles, spacer, buildBalancePill(host));
		headerRow.setAlignment(Pos.CENTER_LEFT);

		VBox root = new VBox(22, headerRow);
		var items = StoreCatalog.all();
		StoreItem featured = items.stream().filter(i -> !StoreOwnership.owns(i.id())).findFirst()
				.orElse(items.isEmpty() ? null : items.get(0));
		if (featured != null) {
			root.getChildren().add(buildFeatured(host, featured));
		}

		Label sectionTitle = new Label("Capes  ·  " + items.size());
		sectionTitle.getStyleClass().add("section-label");
		FlowPane grid = new FlowPane(14, 14);
		for (StoreItem item : items) {
			boolean owned = StoreOwnership.owns(item.id());
			grid.getChildren().add(CosmeticUi.capeCard("store:" + item.id(), CosmeticUi.frames(item),
					item.name(), CosmeticUi.priceChip(item.priceCoins(), owned), false, () -> host.openItem(item)));
		}
		UiMotion.stagger(grid, 24);
		root.getChildren().addAll(sectionTitle, grid);

		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox.setVgrow(wrapper, Priority.ALWAYS);
		return wrapper;
	}

	/** The wide banner on top: one cape you don't own yet, big, with a direct "View" action. */
	private static Node buildFeatured(Host host, StoreItem item) {
		boolean owned = StoreOwnership.owns(item.id());
		Label tag = new Label("FEATURED");
		tag.getStyleClass().add("featured-tag");
		Label name = new Label(item.name());
		name.getStyleClass().add("featured-title");
		Label description = new Label(item.description());
		description.getStyleClass().add("page-subtitle");
		description.setWrapText(true);
		description.setMaxWidth(380);
		Button view = new Button(owned ? "View" : "Preview & buy");
		view.getStyleClass().add("primary-button");
		view.setOnAction(e -> host.openItem(item));
		HBox priceRow = new HBox(12, CosmeticUi.priceChip(item.priceCoins(), owned), view);
		priceRow.setAlignment(Pos.CENTER_LEFT);
		VBox text = new VBox(10, tag, name, description, priceRow);
		text.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(text, Priority.ALWAYS);

		ImageView cape = new ImageView(CosmeticUi.thumbnail("store:" + item.id(), CosmeticUi.frames(item), 200, 200));
		StackPane art = new StackPane(cape);
		art.setMinWidth(220);

		HBox banner = new HBox(20, text, art);
		banner.setAlignment(Pos.CENTER_LEFT);
		banner.getStyleClass().addAll("featured-banner", "motion-card");
		banner.setOnMouseClicked(e -> host.openItem(item));
		CosmeticUi.animate(banner, cape, "store:" + item.id(), CosmeticUi.frames(item), 200, 200, false);
		return banner;
	}

	private static String balanceText() {
		int balance = CurrencyStore.balance();
		return balance < 0 ? "..." : String.format(java.util.Locale.ROOT, "%,d", balance);
	}

	private static Node buildBalancePill(Host host) {
		Label balance = new Label(balanceText());
		balance.getStyleClass().add("balance-amount");
		// The balance lives on the server: show the cached value, then refresh it.
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				net.veloclient.launcher.social.StoreApi.wallet();
				net.veloclient.launcher.data.StorePurchase.restoreMissing();
			} catch (net.veloclient.launcher.social.StoreApi.StoreError ignored) {
				// Shown as "..." until it works.
			}
			javafx.application.Platform.runLater(() -> balance.setText(balanceText()));
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		Button getCoins = new Button("Get coins");
		getCoins.getStyleClass().add("ghost-button");
		getCoins.setOnAction(e -> host.openCoins());
		HBox pill = new HBox(8, CosmeticUi.coin(20), balance, getCoins);
		pill.setAlignment(Pos.CENTER_LEFT);
		pill.getStyleClass().add("balance-pill");
		return pill;
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
