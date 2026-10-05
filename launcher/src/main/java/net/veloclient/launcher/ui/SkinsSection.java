package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import net.veloclient.launcher.data.SkinLibrary;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The "Skins" part of the profile page: every saved skin as a card (front view), equip with one
 * click, and add new ones from a player's username/UUID or a PNG. Same library as the in-game
 * Skins screen.
 */
final class SkinsSection {

	private SkinsSection() {
	}

	static Node build(AccountProfileView.Host host) {
		Label status = new Label("Add skins below, then click Equip - it changes your skin on your Minecraft account.");
		status.getStyleClass().add("settings-row-hint");

		TextField player = new TextField();
		player.setPromptText("Username or UUID");
		player.setPrefWidth(200);
		Button addPlayer = new Button("Add player's skin");
		addPlayer.getStyleClass().add("ghost-button");
		CheckBox slim = new CheckBox("Slim arms (for PNGs)");
		Button importPng = new Button("Import PNG...");
		importPng.getStyleClass().add("ghost-button");
		Region spacer = new Region();
		HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
		HBox addRow = new HBox(8, player, addPlayer, spacer, slim, importPng);
		addRow.setAlignment(Pos.CENTER_LEFT);

		FlowPane grid = new FlowPane(12, 12);
		StackPane previewStage = CosmeticUi.stage(200, 270);
		previewStage.getStyleClass().add("showcase-stage");
		Label previewName = new Label();
		previewName.getStyleClass().add("cape-card-title");
		Label previewHint = new Label("Drag to turn");
		previewHint.getStyleClass().add("settings-row-hint");
		VBox previewColumn = new VBox(8, previewStage, previewName, previewHint);
		previewColumn.setAlignment(Pos.TOP_CENTER);
		previewColumn.setMinWidth(200);
		// null = the skin the signed-in account wears right now.
		java.util.function.Consumer<SkinLibrary.Skin> show = skin -> {
			if (skin == null) {
				previewName.setText("Your current skin");
				Label loading = new Label(host.session() == null ? "Sign in to see your skin" : "Loading...");
				loading.getStyleClass().add("page-subtitle");
				previewStage.getChildren().setAll(loading);
				if (host.session() != null) {
					CosmeticUi.skin(host.session(), data -> {
						Node viewer = data == null ? null : PlayerSkin3DView.createViewer(data.pngBytes(), data.slim(), null, true, 25, false);
						if (viewer != null) {
							previewStage.getChildren().setAll(viewer);
							previewName.setText("Your current skin" + (data.slim() ? "  ·  Slim" : "  ·  Classic"));
						} else {
							loading.setText("Couldn't load your skin");
						}
					});
				}
				return;
			}
			Node viewer = viewer(skin);
			if (viewer == null) {
				Label none = new Label("Couldn't load this skin");
				none.getStyleClass().add("page-subtitle");
				previewStage.getChildren().setAll(none);
			} else {
				previewStage.getChildren().setAll(viewer);
			}
			previewName.setText(skin.name() + (skin.slim() ? "  ·  Slim" : "  ·  Classic"));
		};
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> fill(grid, host, status, refresh[0], show);

		Runnable doAddPlayer = () -> {
			String query = player.getText().trim();
			if (query.isEmpty()) {
				return;
			}
			status.setText("Looking up " + query + "...");
			run(() -> SkinLibrary.addFromPlayer(query), skin -> {
				status.setText("Added " + skin.name() + "'s skin.");
				player.clear();
				refresh[0].run();
			}, status);
		};
		addPlayer.setOnAction(e -> doAddPlayer.run());
		player.setOnAction(e -> doAddPlayer.run());
		importPng.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose a skin PNG (64x64)");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Skin PNG", "*.png"));
			File file = chooser.showOpenDialog(host.owner());
			if (file != null) {
				String name = file.getName().replaceFirst("\\.png$", "");
				boolean isSlim = slim.isSelected();
				run(() -> SkinLibrary.addPng(name, Files.readAllBytes(file.toPath()), isSlim), skin -> {
					status.setText("Added " + skin.name() + ".");
					refresh[0].run();
				}, status);
			}
		});

		refresh[0].run();
		HBox.setHgrow(grid, javafx.scene.layout.Priority.ALWAYS);
		grid.setPrefWrapLength(400);
		HBox body = new HBox(18, previewColumn, grid);
		VBox box = new VBox(12, addRow, body, status);
		box.getStyleClass().add("glass-panel");
		return box;
	}

	private static void fill(FlowPane grid, AccountProfileView.Host host, Label status, Runnable refresh,
			java.util.function.Consumer<SkinLibrary.Skin> show) {
		grid.getChildren().clear();
		var skins = SkinLibrary.all();
		show.accept(null);
		grid.getChildren().add(currentCard(host, status, refresh, show));
		String equipped = SkinLibrary.equippedId();
		for (SkinLibrary.Skin skin : skins) {
			boolean isEquipped = skin.id().equals(equipped);
			ImageView preview = new ImageView(front(skin));
			StackPane stage = new StackPane(preview);
			stage.setPrefSize(120, 150);
			stage.getStyleClass().add("skin-stage");
			Label name = new Label(skin.name());
			name.getStyleClass().add("cape-card-title");
			name.setMaxWidth(120);
			Label variant = CosmeticUi.badge(skin.slim() ? "Slim" : "Classic", null);
			Button equip = new Button(isEquipped ? "Equipped" : "Equip");
			equip.getStyleClass().add(isEquipped ? "ghost-button" : "primary-button");
			equip.setDisable(isEquipped);
			equip.setMaxWidth(Double.MAX_VALUE);
			equip.setOnAction(e -> {
				if (host.session() == null) {
					status.setText("Sign in first.");
					return;
				}
				equip.setDisable(true);
				equip.setText("Uploading...");
				status.setText("Uploading " + skin.name() + " to your Minecraft account...");
				host.withFreshSession(fresh -> run(() -> {
					SkinLibrary.equip(skin, fresh.minecraftAccessToken());
					return skin;
				}, done -> {
					status.setText("Skin changed to " + skin.name() + ". Servers show it the next time you join.");
					CosmeticUi.forgetSkin();
					host.reload();
				}, error -> {
					// Never leave the button stuck on "Uploading..." - e.g. Mojang's limit on quick skin changes.
					equip.setDisable(false);
					equip.setText("Equip");
					status.setText(error);
				}));
			});
			Button delete = new Button("✕");
			delete.getStyleClass().add("theme-row-delete");
			delete.setOnAction(e -> {
				try {
					SkinLibrary.delete(skin);
				} catch (Exception ex) {
					status.setText("Couldn't delete: " + ex.getMessage());
				}
				refresh.run();
			});
			HBox top = new HBox(4, variant, new Region(), delete);
			HBox.setHgrow(top.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
			top.setAlignment(Pos.CENTER_LEFT);
			VBox card = new VBox(8, top, stage, name, equip);
			card.setPrefWidth(140);
			card.getStyleClass().addAll("cape-card", "motion-card");
			if (isEquipped) {
				card.getStyleClass().add("cape-card-selected");
			}
			// Clicking a card (not its buttons) shows that skin in the 3D preview.
			card.setOnMouseClicked(e -> show.accept(skin));
			card.setCursor(javafx.scene.Cursor.HAND);
			grid.getChildren().add(card);
		}
	}

	/** First card: the skin the account wears now (click to preview it, "Save copy" keeps it in the library). */
	private static Node currentCard(AccountProfileView.Host host, Label status, Runnable refresh, java.util.function.Consumer<SkinLibrary.Skin> show) {
		StackPane stage = new StackPane();
		stage.setPrefSize(120, 150);
		stage.getStyleClass().add("skin-stage");
		if (host.session() != null) {
			CosmeticUi.skin(host.session(), data -> {
				if (data != null) {
					try {
						java.nio.file.Path tmp = java.nio.file.Files.createTempFile("velo-skin", ".png");
						java.nio.file.Files.write(tmp, data.pngBytes());
						Image front = front(new SkinLibrary.Skin("current", "current", data.slim(), 0), tmp);
						java.nio.file.Files.deleteIfExists(tmp);
						if (front != null) {
							stage.getChildren().setAll(new ImageView(front));
						}
					} catch (Exception ignored) {
						// Leave the card empty.
					}
				}
			});
		}
		Label name = new Label("Current skin");
		name.getStyleClass().add("cape-card-title");
		Label badge = CosmeticUi.badge("On your account", "chip-owned");
		Button save = new Button("Save copy");
		save.getStyleClass().add("ghost-button");
		save.setMaxWidth(Double.MAX_VALUE);
		save.setDisable(host.session() == null);
		save.setOnAction(e -> {
			e.consume();
			save.setDisable(true);
			String username = host.session().username();
			run(() -> SkinLibrary.addFromPlayer(username), skin -> {
				status.setText("Saved your current skin as \"" + skin.name() + "\".");
				refresh.run();
			}, status);
		});
		HBox top = new HBox(4, badge);
		VBox card = new VBox(8, top, stage, name, save);
		card.setPrefWidth(140);
		card.getStyleClass().addAll("cape-card", "motion-card");
		card.setOnMouseClicked(e -> show.accept(null));
		card.setCursor(javafx.scene.Cursor.HAND);
		return card;
	}

	@FunctionalInterface
	private interface Task<T> {
		T run() throws Exception;
	}

	private static <T> void run(Task<T> task, java.util.function.Consumer<T> onDone, Label status) {
		run(task, onDone, status::setText);
	}

	private static <T> void run(Task<T> task, java.util.function.Consumer<T> onDone, java.util.function.Consumer<String> onError) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return (Object) task.run();
			} catch (Exception e) {
				return e;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result instanceof Exception e) {
				onError.accept(e.getMessage() != null ? e.getMessage() : "Something went wrong");
			} else {
				@SuppressWarnings("unchecked")
				T value = (T) result;
				onDone.accept(value);
			}
		}));
	}

	/** Interactive 3D model; old 64x32 skins are converted to the 64x64 layout first. */
	private static Node viewer(SkinLibrary.Skin skin) {
		try {
			byte[] png = Files.readAllBytes(SkinLibrary.png(skin));
			BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(png));
			if (src != null && src.getHeight() == 32) {
				png = modernize(src);
			}
			return PlayerSkin3DView.createViewer(png, skin.slim(), null, true, 25, false);
		} catch (Exception e) {
			return null;
		}
	}

	/** Same conversion vanilla does for legacy skins: mirrored left limbs, see-through hat if fully opaque. */
	private static byte[] modernize(BufferedImage src) throws java.io.IOException {
		BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				out.setRGB(x, y, src.getRGB(x, y));
			}
		}
		int[][] moves = {{4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
				{8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
				{40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
		for (int[] m : moves) {
			for (int j = 0; j < m[5]; j++) {
				for (int i = 0; i < m[4]; i++) {
					out.setRGB(m[0] + m[2] + (m[4] - 1 - i), m[1] + m[3] + j, out.getRGB(m[0] + i, m[1] + j));
				}
			}
		}
		boolean hatHasAlpha = false;
		for (int y = 0; y < 16 && !hatHasAlpha; y++) {
			for (int x = 32; x < 64; x++) {
				if ((out.getRGB(x, y) >>> 24) < 128) {
					hatHasAlpha = true;
					break;
				}
			}
		}
		if (!hatHasAlpha) {
			for (int y = 0; y < 16; y++) {
				for (int x = 32; x < 64; x++) {
					out.setRGB(x, y, 0);
				}
			}
		}
		var bytes = new java.io.ByteArrayOutputStream();
		ImageIO.write(out, "png", bytes);
		return bytes.toByteArray();
	}

	/** Flat front view of a skin (head, body, arms, legs + overlay layers), scaled up crisply. */
	static Image front(SkinLibrary.Skin skin) {
		return front(skin, SkinLibrary.png(skin));
	}

	static Image front(SkinLibrary.Skin skin, java.nio.file.Path file) {
		try {
			BufferedImage src = ImageIO.read(file.toFile());
			boolean legacy = src.getHeight() == 32;
			int armW = skin.slim() ? 3 : 4;
			BufferedImage out = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
			// Base layer.
			copy(src, out, 8, 8, 8, 8, 4, 0);                     // head
			copy(src, out, 20, 20, 8, 12, 4, 8);                  // body
			copy(src, out, 44, 20, armW, 12, 4 - armW, 8);        // right arm (viewer's left)
			if (legacy) {
				copy(src, out, 44, 20, armW, 12, 12, 8);
				copy(src, out, 4, 20, 4, 12, 8, 20);
			} else {
				copy(src, out, 36, 52, armW, 12, 12, 8);           // left arm
				copy(src, out, 20, 52, 4, 12, 8, 20);              // left leg
			}
			copy(src, out, 4, 20, 4, 12, 4, 20);                  // right leg
			// Overlay layer (old 64x32 skins have none - their hat area is often solid black).
			if (!legacy) {
				overlay(src, out, 40, 8, 8, 8, 4, 0);
				overlay(src, out, 20, 36, 8, 12, 4, 8);
				overlay(src, out, 44, 36, armW, 12, 4 - armW, 8);
				overlay(src, out, 52, 52, armW, 12, 12, 8);
				overlay(src, out, 4, 36, 4, 12, 4, 20);
				overlay(src, out, 4, 52, 4, 12, 8, 20);
			}
			int scale = 4;
			WritableImage image = new WritableImage(16 * scale, 32 * scale);
			PixelWriter writer = image.getPixelWriter();
			for (int y = 0; y < 32 * scale; y++) {
				for (int x = 0; x < 16 * scale; x++) {
					writer.setArgb(x, y, out.getRGB(x / scale, y / scale));
				}
			}
			return image;
		} catch (Exception e) {
			return null;
		}
	}

	private static void copy(BufferedImage src, BufferedImage out, int sx, int sy, int w, int h, int dx, int dy) {
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out.setRGB(dx + x, dy + y, src.getRGB(sx + x, sy + y));
			}
		}
	}

	private static void overlay(BufferedImage src, BufferedImage out, int sx, int sy, int w, int h, int dx, int dy) {
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = src.getRGB(sx + x, sy + y);
				if ((argb >>> 24) > 0) {
					out.setRGB(dx + x, dy + y, argb);
				}
			}
		}
	}
}
