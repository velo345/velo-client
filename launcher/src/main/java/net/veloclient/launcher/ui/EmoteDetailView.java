package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.EmoteCatalog;
import net.veloclient.launcher.social.StoreApi;

/**
 * One emote in the launcher's Store: your own skin doing it in 3D on a loop, with its particles, the price and Buy
 * (Velo Coins). Once it's yours, "Equip" puts it on your emote wheel (the game's B key) and "Take it off" takes it
 * off again. Emotes are never free.
 */
public final class EmoteDetailView {

	public interface Host {
		MinecraftSession session();

		void goBack();

		default void openCoins() {
		}
	}

	private EmoteDetailView() {
	}

	/** Your skin doing the emote in 3D (the canvas figure until the skin and the animation are in, or signed out). */
	static Node stage(MinecraftSession session, EmoteCatalog.EmoteType type, double w, double h) {
		StackPane stage = CosmeticUi.stage(w, h);
		stage.getStyleClass().add("showcase-stage");
		stage.getChildren().add(EmoteFigureFx.create(type, w - 40, h - 40));
		EmoteCatalog.Animation[] animation = {EmoteCatalog.ready(type)};
		EmoteCatalog.load(type, a -> animation[0] = a);
		CosmeticUi.skin(session, skin -> {
			Node viewer = skin == null ? null : PlayerSkin3DView.createEmoteViewer(skin.pngBytes(), skin.slim(), () -> animation[0]);
			if (viewer != null) {
				Platform.runLater(() -> stage.getChildren().setAll(viewer));
			}
		});
		return stage;
	}

	public static Node build(Host host, EmoteCatalog.EmoteType type) {
		Node stage = stage(host.session(), type, 420, 480);
		if (stage instanceof javafx.scene.layout.Region region) {
			region.getStyleClass().removeAll("cape-stage", "showcase-stage");
			region.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
		}
		int price = StoreApi.price(type.itemId(), type.price());
		ItemPage.Purchase purchase = new ItemPage.Purchase(type.itemId(), price,
				() -> EmoteCatalog.owns(type.id()),
				() -> EmoteCatalog.slotOf(type.id()) >= 0,
				() -> EmoteCatalog.placeOnWheel(type.id()),
				() -> {
					int slot = EmoteCatalog.slotOf(type.id());
					if (slot >= 0) {
						EmoteCatalog.setSlot(slot, null);
					}
				},
				() -> {
					try {
						StoreApi.buy(type.itemId());
						EmoteCatalog.placeOnWheel(type.id());
						return null;
					} catch (StoreApi.StoreError error) {
						return error.getMessage();
					}
				}, "emote", type.inStore());
		return ItemPage.build(host::goBack, host::openCoins, stage, null,
				host.session() == null ? "Sign in to see it on your own skin" : "Drag to rotate  ·  scroll to zoom",
				type.accent(), "Emotes", StoreTiles.Rarity.of(type.itemId(), price), type.name(), type.description(),
				java.util.List.of("Hold B in game for your emote wheel", "Its own particles and sounds", "Seen by every Velo Client player"),
				null, purchase);
	}
}
