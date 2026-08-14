package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
	}

	private StoreView() {
	}

	public static Node build(Host host) {
		VBox root = new VBox(20);

		HBox headerRow = new HBox(10);
		headerRow.setAlignment(Pos.CENTER_LEFT);
		Label heading = new Label("Store");
		heading.getStyleClass().add("section-heading");
		heading.setTextFill(accent(host.theme()));
		HBox spacer = new HBox();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Node balancePill = buildBalancePill(host);
		headerRow.getChildren().addAll(heading, spacer, balancePill);
		root.getChildren().add(headerRow);

		Label sectionTitle = new Label("Capes");
		sectionTitle.setFont(Font.font("System", FontWeight.BOLD, 16));
		sectionTitle.setTextFill(text(host.theme()));
		root.getChildren().add(sectionTitle);

		FlowPane grid = new FlowPane(14, 14);
		for (StoreItem item : StoreCatalog.all()) {
			grid.getChildren().add(buildItemCard(host, item));
		}
		root.getChildren().add(grid);

		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox.setVgrow(wrapper, Priority.ALWAYS);
		return wrapper;
	}

	private static Node buildBalancePill(Host host) {
		HBox pill = new HBox(8);
		pill.getStyleClass().add("glass-panel");
		pill.setAlignment(Pos.CENTER_LEFT);
		pill.setPadding(new Insets(6, 12, 6, 10));
		ImageView logo = new ImageView(new Image(StoreView.class.getResourceAsStream("/net/veloclient/launcher/images/logo.png"), 20, 20, true, true));
		Label balance = new Label(CurrencyStore.balance() + " Velo Coins");
		balance.setTextFill(text(host.theme()));
		balance.setFont(Font.font("System", FontWeight.BOLD, 13));
		pill.getChildren().addAll(logo, balance);
		pill.setOnMouseClicked(e -> {
			BuyCoinsDialog.show(host.owner());
			host.rebuild();
		});
		return pill;
	}

	private static Node buildItemCard(Host host, StoreItem item) {
		VBox card = new VBox(8);
		card.getStyleClass().add("instance-card");
		card.setPrefWidth(150);
		card.setAlignment(Pos.TOP_CENTER);

		StackPane preview = new StackPane();
		preview.setPrefSize(80, 110);
		preview.getStyleClass().add("instance-icon-custom");
		try {
			byte[] gifBytes = StoreCatalog.openGif(item).readAllBytes();
			Image gif = new Image(new ByteArrayInputStream(gifBytes));
			double scale = gif.getWidth() / 64.0;
			ImageView view = new ImageView(gif);
			view.setViewport(new javafx.geometry.Rectangle2D(scale, scale, 10 * scale, 16 * scale));
			view.setFitWidth(70);
			view.setFitHeight(110);
			view.setSmooth(false);
			view.setPreserveRatio(true);
			preview.getChildren().add(view);
		} catch (Exception e) {
			Label fallback = new Label("?");
			fallback.setTextFill(Color.WHITE);
			preview.getChildren().add(fallback);
		}

		boolean owned = StoreOwnership.owns(item.id());
		Label name = new Label(item.name() + (owned ? "  ✓" : ""));
		name.setTextFill(owned ? accent(host.theme()) : text(host.theme()));
		name.setFont(Font.font("System", FontWeight.BOLD, 13));
		name.setWrapText(true);

		Label price = new Label(owned ? "Owned" : item.priceCoins() + " Velo Coins");
		price.getStyleClass().add("version-tag");
		price.setTextFill(text(host.theme()));

		card.getChildren().addAll(preview, name, price);
		card.setOnMouseClicked(e -> host.openItem(item));
		return card;
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
