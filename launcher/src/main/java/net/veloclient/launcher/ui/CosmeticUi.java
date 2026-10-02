package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.SkinFetcher;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.launcher.data.StoreCatalog;
import net.veloclient.launcher.data.StoreItem;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Shared building blocks for the Cosmetics and Store pages: decoded cape frames, cached 3D cape
 * thumbnails, the cape card, the coin icon/price chip, and the signed-in player's skin (fetched once
 * per account and reused, so switching between pages doesn't refetch it).
 */
final class CosmeticUi {

	private static final Map<String, List<GifFrames.Frame>> FRAMES = new ConcurrentHashMap<>();
	private static final Map<String, Image> THUMBNAILS = new ConcurrentHashMap<>();
	private static volatile String skinOwner;
	private static volatile SkinFetcher.SkinData skin;

	private CosmeticUi() {
	}

	// ---- Data ----

	static List<GifFrames.Frame> frames(CapeEntry cape) {
		return FRAMES.computeIfAbsent("lib:" + cape.id(), key -> {
			try {
				if (cape.animated()) {
					return GifFrames.decode(new ByteArrayInputStream(CapeLibrary.gifBytes(cape))).frames();
				}
				var image = ImageIO.read(new ByteArrayInputStream(CapeLibrary.textureBytes(cape)));
				return image == null ? List.of() : List.of(new GifFrames.Frame(image, 100));
			} catch (Exception e) {
				return List.of();
			}
		});
	}

	static List<GifFrames.Frame> frames(StoreItem item) {
		return FRAMES.computeIfAbsent("store:" + item.id(), key -> {
			try (var in = StoreCatalog.openGif(item)) {
				return GifFrames.decode(in).frames();
			} catch (Exception e) {
				return List.of();
			}
		});
	}

	/** A 3D-rendered cape image (cached per cape and size). FX thread. */
	static Image thumbnail(String key, List<GifFrames.Frame> frames, double width, double height) {
		if (frames.isEmpty()) {
			return null;
		}
		return THUMBNAILS.computeIfAbsent(key + "@" + (int) width + "x" + (int) height,
				k -> PlayerSkin3DView.capeThumbnail(frames.get(0).image(), width, height));
	}

	/** The account's skin, fetched once and cached; {@code onReady} runs on the FX thread (null if unavailable). */
	static void skin(MinecraftSession session, Consumer<SkinFetcher.SkinData> onReady) {
		if (session == null) {
			onReady.accept(null);
			return;
		}
		if (session.uuid().equals(skinOwner) && skin != null) {
			onReady.accept(skin);
			return;
		}
		CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(session), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(data -> Platform.runLater(() -> {
					if (data != null) {
						skinOwner = session.uuid();
						skin = data;
					}
					onReady.accept(data);
				}));
	}

	// ---- Visual pieces ----

	/** A small gold coin (the Velo Coins icon). */
	static Node coin(double size) {
		Circle disc = new Circle(size / 2);
		disc.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.web("#ffe38a")), new Stop(0.55, Color.web("#f4b82e")), new Stop(1, Color.web("#c9861a"))));
		disc.setStroke(Color.web("#a86a10"));
		disc.setStrokeWidth(Math.max(1, size / 14));
		Label mark = new Label("V");
		mark.setStyle("-fx-font-size: " + Math.round(size * 0.55) + "px; -fx-font-weight: 900; -fx-text-fill: #7a4a06;");
		StackPane coin = new StackPane(disc, mark);
		coin.setMinSize(size, size);
		coin.setMaxSize(size, size);
		return coin;
	}

	/** "<coin> 250" chip, or a green "Owned" chip. */
	static Node priceChip(int coins, boolean owned) {
		if (owned) {
			Label label = new Label("Owned");
			label.getStyleClass().addAll("chip", "chip-owned");
			return label;
		}
		Label amount = new Label(String.valueOf(coins));
		amount.getStyleClass().add("chip-amount");
		HBox chip = new HBox(6, coin(15), amount);
		chip.setAlignment(Pos.CENTER_LEFT);
		chip.getStyleClass().add("chip");
		return chip;
	}

	/** The lit "stage" a cape stands on: rounded panel with an accent glow from below. */
	static StackPane stage(double width, double height) {
		StackPane stage = new StackPane();
		stage.getStyleClass().add("cape-stage");
		stage.setPrefSize(width, height);
		stage.setMinSize(width, height);
		stage.setMaxSize(width, height);
		return stage;
	}

	/**
	 * A cape card: 3D cape on a stage, a title, and a footer node (price chip / badge). The card
	 * lifts on hover (UiMotion) and shows a ring when {@code selected}.
	 */
	static VBox capeCard(Image thumbnail, String title, Node footer, boolean selected, Runnable onClick) {
		StackPane stage = stage(150, 150);
		if (thumbnail != null) {
			ImageView view = new ImageView(thumbnail);
			view.setFitWidth(130);
			view.setFitHeight(140);
			view.setPreserveRatio(true);
			stage.getChildren().add(view);
		}
		Label name = new Label(title);
		name.getStyleClass().add("cape-card-title");
		name.setMaxWidth(150);
		VBox card = new VBox(8, stage, name);
		if (footer != null) {
			card.getChildren().add(footer);
		}
		card.getStyleClass().addAll("cape-card", "motion-card");
		if (selected) {
			card.getStyleClass().add("cape-card-selected");
		}
		card.setOnMouseClicked(e -> onClick.run());
		return card;
	}

	static Label badge(String text, String extraClass) {
		Label badge = new Label(text);
		badge.getStyleClass().add("chip");
		if (extraClass != null) {
			badge.getStyleClass().add(extraClass);
		}
		return badge;
	}
}
