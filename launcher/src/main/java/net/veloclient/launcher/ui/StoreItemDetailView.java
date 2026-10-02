package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.SkinFetcher;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.CurrencyStore;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.launcher.data.StoreCatalog;
import net.veloclient.launcher.data.StoreItem;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.data.StorePurchase;
import net.veloclient.launcher.theme.LauncherTheme;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** "Try before you buy" for one {@link StoreItem}: a live 3D preview of the signed-in account's skin wearing it, title/description/price, and a Buy/Equip button - the launcher's counterpart to the mod's in-game Store detail screen. */
public final class StoreItemDetailView {

	public interface Host {
		Stage owner();

		LauncherTheme theme();

		MinecraftSession session();

		void goBack();
	}

	private StoreItemDetailView() {
	}

	public static Node build(Host host, StoreItem item) {
		Button back = new Button("‹  Store");
		back.getStyleClass().add("ghost-button");
		back.setOnAction(e -> host.goBack());

		// Left: your skin wearing the cape, turning slowly with the cape facing you first.
		StackPane stage = CosmeticUi.stage(340, 420);
		stage.getStyleClass().add("showcase-stage");
		Label loading = new Label(host.session() == null ? "Sign in to preview this on your own skin" : "Loading preview...");
		loading.getStyleClass().add("page-subtitle");
		loading.setWrapText(true);
		stage.getChildren().add(loading);
		var frames = CosmeticUi.frames(item);
		CosmeticUi.skin(host.session(), skin -> {
			Node viewer = skin == null ? null
					: PlayerSkin3DView.createShowcase(skin.pngBytes(), skin.slim(), frames.isEmpty() ? null : frames);
			if (viewer != null) {
				stage.getChildren().setAll(viewer);
			} else if (!frames.isEmpty()) {
				stage.getChildren().setAll(new javafx.scene.image.ImageView(CosmeticUi.thumbnail("store:" + item.id(), frames, 300, 340)));
			}
		});
		Label hint = new Label("Drag to rotate  ·  scroll to zoom");
		hint.getStyleClass().add("stage-hint");
		StackPane preview = new StackPane(stage, hint);
		StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
		hint.setTranslateY(-10);

		// Right: details and the purchase action.
		boolean owned = StoreOwnership.owns(item.id());
		Label tag = new Label(frames.size() > 1 ? "CAPE  ·  ANIMATED" : "CAPE");
		tag.getStyleClass().add("featured-tag");
		Label name = new Label(item.name());
		name.getStyleClass().add("featured-title");
		Label description = new Label(item.description());
		description.getStyleClass().add("page-subtitle");
		description.setWrapText(true);
		description.setMaxWidth(380);

		Node priceChip = CosmeticUi.priceChip(item.priceCoins(), owned);
		Label balance = new Label("You have " + CurrencyStore.balance() + " coins");
		balance.getStyleClass().add("page-subtitle");
		HBox priceRow = new HBox(12, priceChip, balance);
		priceRow.setAlignment(Pos.CENTER_LEFT);

		Label status = new Label();
		status.getStyleClass().add("purchase-status");
		status.setWrapText(true);

		Button action = new Button(owned ? "Equip cape" : "Buy for " + item.priceCoins() + " coins");
		action.getStyleClass().add("primary-button");
		action.setMinHeight(46);
		action.setPrefWidth(260);
		action.setOnAction(e -> {
			if (StoreOwnership.owns(item.id())) {
				findLibraryIdFor(item).ifPresentOrElse(id -> {
					CapeLibrary.equip(id);
					status.setText("Equipped \"" + item.name() + "\" - it shows in-game now.");
				}, () -> status.setText("Couldn't find this cape in your library - try re-importing it from Cosmetics."));
				return;
			}
			StorePurchase.Result result = StorePurchase.buy(item);
			switch (result) {
				case SUCCESS -> {
					status.setText("Purchased! \"" + item.name() + "\" is in your Cosmetics now.");
					action.setText("Equip cape");
					priceRow.getChildren().set(0, CosmeticUi.priceChip(item.priceCoins(), true));
					balance.setText("You have " + CurrencyStore.balance() + " coins");
				}
				case INSUFFICIENT_COINS -> status.setText("Not enough coins - you have " + CurrencyStore.balance() + ".");
				case ALREADY_OWNED -> status.setText("You already own this cape.");
				case IMPORT_FAILED -> status.setText("Purchase failed - your coins were refunded.");
			}
		});

		VBox info = new VBox(12, tag, name, description, priceRow, action, status);
		info.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(info, Priority.ALWAYS);

		HBox body = new HBox(32, preview, info);
		body.setAlignment(Pos.CENTER_LEFT);
		VBox root = new VBox(16, back, body);
		return root;
	}

	/** {@link StorePurchase#buy} imports the cape under a fresh library id (not the catalog item's own id), so equipping the just-bought item has to look that library entry back up by name. */
	private static java.util.Optional<String> findLibraryIdFor(StoreItem item) {
		return CapeLibrary.listAll().stream()
				.filter(entry -> entry.animated() && entry.name().equals(item.name()))
				.reduce((first, second) -> second)
				.map(net.veloclient.launcher.data.CapeEntry::id);
	}

	private static void loadPreviewAsync(MinecraftSession session, StoreItem item, StackPane holder) {
		CompletableFuture<SkinFetcher.SkinData> skinFuture =
				CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(session), Executors.newVirtualThreadPerTaskExecutor());
		CompletableFuture<GifFrames.Result> capeFuture = CompletableFuture.supplyAsync(() -> {
			try (var in = StoreCatalog.openGif(item)) {
				return GifFrames.decode(in);
			} catch (Exception e) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor());

		skinFuture.thenAcceptBoth(capeFuture, (skin, cape) -> Platform.runLater(() -> {
			Node viewer = skin == null ? null
					: PlayerSkin3DView.createViewer(skin.pngBytes(), skin.slim(), cape == null ? null : cape.frames());
			if (viewer != null) {
				holder.getChildren().setAll(viewer);
			} else {
				Label failed = new Label("Couldn't load a preview.");
				failed.getStyleClass().add("section-subtitle");
				holder.getChildren().setAll(failed);
			}
		}));
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
