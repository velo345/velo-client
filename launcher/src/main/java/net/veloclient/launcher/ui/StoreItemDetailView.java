package net.veloclient.launcher.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.launcher.data.StoreItem;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.data.StorePurchase;
import net.veloclient.launcher.social.StoreApi;
import net.veloclient.launcher.theme.LauncherTheme;

import java.util.List;
import java.util.Optional;

/** "Try before you buy" for one cape: your skin wearing it in 3D, its price and Buy / Equip / Gift / Wishlist. */
public final class StoreItemDetailView {

	public interface Host {
		Stage owner();

		LauncherTheme theme();

		MinecraftSession session();

		void goBack();

		default void openCoins() {
		}
	}

	private StoreItemDetailView() {
	}

	public static Node build(Host host, StoreItem item) {
		StackPane stage = new StackPane();
		stage.setMinSize(320, 400);
		if (host.session() == null) {
			Label signIn = new Label("Sign in to preview this on your own skin");
			signIn.getStyleClass().add("t-muted");
			stage.getChildren().add(signIn);
		} else {
			stage.getChildren().add(CosmeticUi.spinner(34));
		}
		// The skin and the cape's frames load off the UI thread; the page is already up.
		List<GifFrames.Frame>[] loaded = new List[1];
		net.veloclient.launcher.auth.SkinFetcher.SkinData[] skinData = new net.veloclient.launcher.auth.SkinFetcher.SkinData[1];
		boolean[] arrived = new boolean[2];
		Runnable build = () -> {
			if (!arrived[0] || !arrived[1]) {
				return;
			}
			var frames = loaded[0] == null ? List.<GifFrames.Frame>of() : loaded[0];
			Node viewer = skinData[0] == null ? null
					: PlayerSkin3DView.createShowcase(skinData[0].pngBytes(), skinData[0].slim(), frames.isEmpty() ? null : frames);
			if (viewer != null) {
				stage.getChildren().setAll(viewer);
			} else if (!frames.isEmpty()) {
				stage.getChildren().setAll(new javafx.scene.image.ImageView(CosmeticUi.thumbnail("store:" + item.id(), frames, 300, 340)));
			} else {
				stage.getChildren().clear();
			}
		};
		CosmeticUi.framesAsync(item, f -> {
			loaded[0] = f;
			arrived[1] = true;
			build.run();
		});
		CosmeticUi.skin(host.session(), data -> {
			skinData[0] = data;
			arrived[0] = true;
			build.run();
		});

		int price = StoreApi.price(item.id(), item.priceCoins());
		ItemPage.Purchase purchase = new ItemPage.Purchase(item.id(), price,
				() -> StoreOwnership.owns(item.id()),
				() -> findLibraryIdFor(item).map(id -> id.equals(CapeLibrary.equippedCapeId().orElse(null))).orElse(false),
				() -> findLibraryIdFor(item).ifPresent(CapeLibrary::equip),
				CapeLibrary::unequip,
				() -> StorePurchase.buy(item), "cape", item.inStore());
		return ItemPage.build(host::goBack, host::openCoins, stage, null,
				host.session() == null ? null : "Drag to rotate  ·  scroll to zoom",
				"#5aa9ff", "Capes", StoreTiles.Rarity.of(item.id(), price), item.name(), item.description(),
				List.of("Animated, with real cloth physics in-game", "Seen by every Velo Client player", "Works with elytra and wings"), null, purchase);
	}

	/** {@link StorePurchase#buy} imports the cape under a fresh library id (not the catalog item's own id), so equipping the just-bought item has to look that library entry back up by name. */
	private static Optional<String> findLibraryIdFor(StoreItem item) {
		return CapeLibrary.listAll().stream()
				.filter(entry -> entry.animated() && entry.name().equals(item.name()))
				.reduce((first, second) -> second)
				.map(CapeEntry::id);
	}
}
