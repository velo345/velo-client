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
		LauncherTheme theme = host.theme();
		Color text = text(theme);
		Color accent = accent(theme);

		VBox root = new VBox(18);

		Button back = new Button("< Back");
		back.setOnAction(e -> host.goBack());
		root.getChildren().add(back);

		HBox body = new HBox(24);
		body.setAlignment(Pos.TOP_LEFT);

		StackPane previewHolder = new StackPane();
		previewHolder.setPrefSize(240, 320);
		previewHolder.setMinSize(240, 320);
		previewHolder.setAlignment(Pos.CENTER);
		previewHolder.getStyleClass().add("glass-panel");
		Label loading = new Label(host.session() == null ? "Sign in to preview this on your own skin." : "Loading preview...");
		loading.getStyleClass().add("section-subtitle");
		loading.setTextFill(text);
		loading.setWrapText(true);
		previewHolder.getChildren().add(loading);
		if (host.session() != null) {
			loadPreviewAsync(host.session(), item, previewHolder);
		}

		VBox info = new VBox(10);
		Label name = new Label(item.name());
		name.setFont(Font.font("System", FontWeight.BOLD, 24));
		name.setTextFill(accent);
		Label description = new Label(item.description());
		description.setTextFill(text);
		description.setWrapText(true);
		description.setMaxWidth(320);

		boolean owned = StoreOwnership.owns(item.id());
		Label priceLabel = new Label(owned ? "You own this cape." : "Price: " + item.priceCoins() + " Velo Coins");
		priceLabel.setTextFill(Color.web("#f7d774"));
		priceLabel.setFont(Font.font("System", FontWeight.BOLD, 13));

		Label status = new Label();
		status.setTextFill(text);
		status.setWrapText(true);

		Button action = new Button(owned ? "Equip" : "Buy for " + item.priceCoins() + " Velo Coins");
		action.getStyleClass().add("title-menu-button");
		if (!owned) {
			action.getStyleClass().add("title-menu-button-primary");
		}
		action.setOnAction(e -> {
			if (StoreOwnership.owns(item.id())) {
				findLibraryIdFor(item).ifPresentOrElse(CapeLibrary::equip,
						() -> status.setText("Couldn't find this cape in your library - try re-importing from the mod's Cape Library screen."));
				status.setText("Equipped \"" + item.name() + "\"");
				return;
			}
			StorePurchase.Result result = StorePurchase.buy(item);
			switch (result) {
				case SUCCESS -> {
					status.setText("Purchased \"" + item.name() + "\" - added to your cape library!");
					action.setText("Equip");
					action.getStyleClass().remove("title-menu-button-primary");
					priceLabel.setText("You own this cape.");
				}
				case INSUFFICIENT_COINS -> status.setText("Not enough Velo Coins - you have " + CurrencyStore.balance() + ".");
				case ALREADY_OWNED -> status.setText("Already owned.");
				case IMPORT_FAILED -> status.setText("Purchase failed - refunded.");
			}
		});

		info.getChildren().addAll(name, description, priceLabel, action, status);
		HBox.setHgrow(info, Priority.ALWAYS);
		body.getChildren().addAll(previewHolder, info);
		root.getChildren().add(body);

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
