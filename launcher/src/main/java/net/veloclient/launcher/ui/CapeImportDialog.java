package net.veloclient.launcher.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.veloclient.launcher.data.CapePhysicsPresetData;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.velo.client.cosmetics.capeart.CapeArt;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Upload your own cloak": pick a PNG or GIF, and {@link CapeArt#detect} tells whether it's already a cloak texture
 * (used as it is, elytra included) or an ordinary picture - then it's turned into one right here, with a live 3D
 * preview and choices for how it sits (fill / fit / stretch, zoom, position), the background colour around it, the
 * quality, pixel-art scaling, the elytra and the inside. Detection can be overruled either way.
 */
public final class CapeImportDialog {

	/** The texture to import (64 x 32 layout, 2:1 or 1:1 with elytra) - or, animated, the finished GIF. */
	public record Prepared(BufferedImage texture, byte[] gif) {
	}

	/** {@code prepare} does the (possibly slow, for GIFs) conversion: run it off the FX thread. */
	public record Result(String name, CapePhysicsPresetData preset, java.util.concurrent.Callable<Prepared> prepare) {
	}

	private static final String[] QUALITY_NAMES = {"Classic 64 x 32", "HD 128 x 64", "HD 256 x 128", "HD 512 x 256", "Ultra 1024 x 512",
			"2K 2048 x 1024"};
	private static final int[] QUALITY_SCALES = {1, 2, 4, 8, 16, 32};
	private static final double PREVIEW_W = 230;
	private static final double PREVIEW_H = 250;

	private CapeImportDialog() {
	}

	public static Optional<Result> show(Stage owner) {
		boolean plus = net.veloclient.launcher.social.StoreApi.isPlusCached();
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Upload your own cloak - a cloak texture or any picture");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures (PNG, GIF, JPG)", "*.png", "*.gif", "*.jpg", "*.jpeg"));
		File file = chooser.showOpenDialog(owner);
		if (file == null) {
			return Optional.empty();
		}
		List<CapeArt.Picture> frames = new ArrayList<>();
		List<Integer> delays = new ArrayList<>();
		boolean gifFile = file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gif");
		try {
			if (gifFile) {
				try (InputStream in = Files.newInputStream(file.toPath())) {
					// At most 1024 wide in memory (2K frames would fill the launcher's heap); a valid file goes up untouched.
					for (GifFrames.Frame frame : GifFrames.decode(in, 1024).frames()) {
						frames.add(CapeArt.Picture.of(frame.image()));
						delays.add(frame.delayMillis());
					}
				}
			} else {
				BufferedImage image = javax.imageio.ImageIO.read(file);
				if (image != null) {
					frames.add(CapeArt.Picture.of(image));
					delays.add(100);
				}
			}
		} catch (IOException | RuntimeException e) {
			frames.clear();
		}
		if (frames.isEmpty()) {
			ErrorDialog.show(owner, "Can't read that picture", "\"" + file.getName() + "\" isn't a PNG, GIF or JPG we can open.", null, null);
			return Optional.empty();
		}
		// Animated cloaks are Velo+: everyone else gets the first frame.
		boolean animated = frames.size() > 1 && plus;
		CapeArt.Picture first = frames.get(0);
		CapeArt.Detection detection = CapeArt.detect(first);
		return new Flow(owner, file, frames, delays, animated, gifFile && frames.size() > 1 && !plus, first, detection).run();
	}

	private static final class Flow {
		final Stage owner;
		final File file;
		final List<CapeArt.Picture> frames;
		final List<Integer> delays;
		final boolean animated;
		final CapeArt.Picture first;
		final CapeArt.Detection detection;
		boolean convert;
		CapeArt.Options options;

		final Label badge = new Label();
		final Label title = new Label();
		final Label detail = new Label();
		final Hyperlink switchMode = new Hyperlink();
		final StackPane preview3d = new StackPane();
		final ImageView flat = new ImageView();
		final VBox converter = new VBox(10);
		final PauseTransition debounce = new PauseTransition(Duration.millis(120));
		final boolean firstFrameOnly;

		Flow(Stage owner, File file, List<CapeArt.Picture> frames, List<Integer> delays, boolean animated, boolean firstFrameOnly,
				CapeArt.Picture first, CapeArt.Detection detection) {
			this.owner = owner;
			this.file = file;
			this.frames = frames;
			this.delays = delays;
			this.animated = animated;
			this.firstFrameOnly = firstFrameOnly;
			this.first = first;
			this.detection = detection;
			this.convert = !detection.isCape();
			this.options = CapeArt.Options.defaults(detection, first);
		}

		Optional<Result> run() {
			Dialog<Result> dialog = new Dialog<>();
			dialog.initOwner(owner);
			dialog.setTitle("Upload your own cloak");
			dialog.setHeaderText(null);
			DialogStyling.apply(dialog);

			TextField name = new TextField(stripExtension(file.getName()));
			name.setPromptText("Cloak name");
			badge.getStyleClass().add("button-sm");
			title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
			detail.setWrapText(true);
			detail.setMaxWidth(560);
			detail.setStyle("-fx-text-fill: -velo-text-2, #A9A9B3;");
			switchMode.setOnAction(e -> {
				convert = !convert;
				refresh();
			});

			preview3d.setMinSize(PREVIEW_W, PREVIEW_H);
			preview3d.setPrefSize(PREVIEW_W, PREVIEW_H);
			preview3d.setStyle("-fx-background-color: radial-gradient(center 50% 60%, radius 70%, #5ac8ff30 0%, transparent 100%), -velo-surface-2;"
					+ " -fx-background-radius: 12;");
			flat.setSmooth(false);
			flat.setPreserveRatio(true);
			flat.setFitHeight(96);
			Label flatCaption = new Label("Your cloak's outside");
			flatCaption.setStyle("-fx-font-size: 11px; -fx-text-fill: #8A8A96;");
			VBox left = new VBox(10, preview3d, new HBox(10, flat, flatCaption));
			((HBox) left.getChildren().get(1)).setAlignment(Pos.CENTER_LEFT);

			buildConverter();
			Slider stiffness = slider(0, 1, 0.6);
			Slider gravity = slider(0, 3, 1.0);
			Slider damping = slider(0, 1, 0.9);
			Slider wind = slider(0, 2, 0.5);
			Slider segments = slider(2, 16, 6);
			segments.setSnapToTicks(true);
			segments.setMajorTickUnit(1);
			segments.setBlockIncrement(1);
			GridPane physicsGrid = grid();
			int row = 0;
			physicsGrid.addRow(row++, new Label("Stiffness"), stiffness);
			physicsGrid.addRow(row++, new Label("Gravity"), gravity);
			physicsGrid.addRow(row++, new Label("Damping"), damping);
			physicsGrid.addRow(row++, new Label("Wind"), wind);
			physicsGrid.addRow(row, new Label("Segments"), segments);
			TitledPane physics = new TitledPane("Cloth physics", physicsGrid);
			physics.setExpanded(false);

			VBox right = new VBox(12, labelled("Name", name), converter, physics);
			right.setPrefWidth(330);
			HBox body = new HBox(18, left, right);
			VBox content = new VBox(12, new HBox(10, badge, title), detail, switchMode, body);
			content.setPadding(new Insets(18));
			content.setPrefWidth(620);
			// Scrolls once it's taller than the screen allows (cloth physics opened, small window), so the buttons
			// below always stay reachable.
			javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
			scroll.setFitToWidth(true);
			scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
			scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
			double screenH = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
			// As tall as the content when the dialog opens (no empty space), never taller than the screen; opening
			// the physics section later doesn't grow the window, it scrolls.
			scroll.prefViewportHeightProperty().bind(javafx.beans.binding.Bindings.min(content.heightProperty(), screenH - 220));
			scroll.setMaxHeight(screenH - 200);
			dialog.getDialogPane().setContent(scroll);
			dialog.setResizable(true);

			ButtonType confirm = new ButtonType("Add to my cloaks", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
			dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);
			debounce.setOnFinished(e -> renderPreview());
			refresh();

			dialog.setResultConverter(button -> {
				if (button != confirm) {
					return null;
				}
				CapePhysicsPresetData preset = new CapePhysicsPresetData((float) stiffness.getValue(), (float) gravity.getValue(),
						(float) damping.getValue(), (float) wind.getValue(), (int) Math.round(segments.getValue()));
				boolean asIs = !convert;
				CapeArt.Options chosen = options;
				return new Result(name.getText().isBlank() ? "Cloak" : name.getText().trim(), preset, () -> prepare(asIs, chosen));
			});
			return dialog.showAndWait();
		}

		/** The texture or GIF to import, made from the frames (called off the FX thread). */
		Prepared prepare(boolean asIs, CapeArt.Options chosen) throws IOException {
			if (!animated) {
				byte[] png = CapeArt.stillPng(first, detection, chosen, asIs);
				return new Prepared(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)), null);
			}
			return new Prepared(null, CapeArt.animatedGif(Files.readAllBytes(file.toPath()), frames, delays, detection, chosen, asIs));
		}

		CapeArt.Picture texture(CapeArt.Picture picture, boolean asIs, CapeArt.Options o) {
			return asIs ? CapeArt.normalize(picture, detection) : CapeArt.convert(picture, o);
		}

		void refresh() {
			boolean cape = !convert;
			if (cape) {
				badge.setText("✓ Cloak texture");
				badge.setStyle("-fx-background-color: #3DDC9733; -fx-text-fill: #3DDC97; -fx-background-radius: 8; -fx-padding: 2 8;");
				title.setText(detection.isCape() ? detection.title() : "Used as a cloak texture");
				detail.setText((detection.isCape() ? detection.detail() : "Used exactly as it is, as a cloak texture.") + note());
				switchMode.setText(detection.isCape() ? "Not right? Treat it as a picture and convert it instead" : "Back to converting it");
			} else {
				badge.setText("Picture");
				badge.setStyle("-fx-background-color: #5ac8ff33; -fx-text-fill: #5ac8ff; -fx-background-radius: 8; -fx-padding: 2 8;");
				title.setText("We'll turn it into a cloak");
				detail.setText((detection.isCape() ? "Converting it like an ordinary picture." : detection.detail())
						+ " The elytra gets your art too." + note());
				switchMode.setText(detection.isCape() ? "Use it as a cloak texture after all" : "It already is a cloak texture? Use it as it is");
			}
			converter.setVisible(convert);
			converter.setManaged(convert);
			renderPreview();
		}

		String note() {
			if (animated) {
				return " Animated: " + frames.size() + " frames - every Velo player sees it move. Animated cloaks go up to 1024 x 512 (still ones up to 2K).";
			}
			if (firstFrameOnly) {
				return " Animated cloaks are part of Velo+, so the first frame is used.";
			}
			return "";
		}

		void schedulePreview() {
			debounce.playFromStart();
		}

		void renderPreview() {
			// The preview never needs more than 512 px (the 3D thumbnail is smaller): keeps sliders smooth at 2K.
			CapeArt.Picture texture = convert ? CapeArt.convert(first, options.withScale(Math.min(options.scale(), 8)))
					: CapeArt.normalize(first, detection, CapeArt.MAX_ANIMATED_WIDTH);
			BufferedImage image = texture.toImage();
			preview3d.getChildren().setAll(new ImageView(PlayerSkin3DView.capeThumbnail(image, PREVIEW_W, PREVIEW_H)));
			int unit = Math.max(1, texture.width() / CapeArt.LAYOUT_WIDTH);
			BufferedImage outside = image.getSubimage(unit, unit, 10 * unit, 16 * unit);
			flat.setImage(javafx.embed.swing.SwingFXUtils.toFXImage(outside, null));
		}

		void buildConverter() {
			ToggleGroup fitGroup = new ToggleGroup();
			HBox fits = new HBox(6);
			String[][] fitNames = {{"Fill", "Crop to fill the cloak"}, {"Fit", "Whole picture, background around it"}, {"Stretch", "Squeeze it in"}};
			for (int i = 0; i < 3; i++) {
				CapeArt.Fit fit = CapeArt.Fit.values()[i];
				ToggleButton b = new ToggleButton(fitNames[i][0]);
				b.setTooltip(new javafx.scene.control.Tooltip(fitNames[i][1]));
				b.getStyleClass().add("button-sm");
				b.setToggleGroup(fitGroup);
				b.setSelected(options.fit() == fit);
				b.setOnAction(e -> {
					b.setSelected(true);
					update(o -> new CapeArt.Options(fit, o.zoom(), o.panX(), o.panY(), o.background(), o.scale(), o.pixelArt(), o.elytra(), o.artInside()));
				});
				fits.getChildren().add(b);
			}
			Slider zoom = slider(1, 4, options.zoom());
			zoom.valueProperty().addListener((obs, a, v) -> update(o -> new CapeArt.Options(o.fit(), v.doubleValue(), o.panX(), o.panY(), o.background(),
					o.scale(), o.pixelArt(), o.elytra(), o.artInside())));
			Slider panX = slider(-1, 1, 0);
			panX.valueProperty().addListener((obs, a, v) -> update(o -> new CapeArt.Options(o.fit(), o.zoom(), v.doubleValue(), o.panY(), o.background(),
					o.scale(), o.pixelArt(), o.elytra(), o.artInside())));
			Slider panY = slider(-1, 1, 0);
			panY.valueProperty().addListener((obs, a, v) -> update(o -> new CapeArt.Options(o.fit(), o.zoom(), o.panX(), v.doubleValue(), o.background(),
					o.scale(), o.pixelArt(), o.elytra(), o.artInside())));

			int auto = CapeArt.autoBackground(first);
			CheckBox autoBg = new CheckBox("Auto");
			autoBg.setSelected(true);
			ColorPicker color = new ColorPicker(Color.rgb((auto >> 16) & 0xFF, (auto >> 8) & 0xFF, auto & 0xFF));
			color.getStyleClass().add("button-sm");
			color.setDisable(true);
			Runnable applyBg = () -> {
				Color c = color.getValue();
				int argb = autoBg.isSelected() ? 0
						: 0xFF000000 | (int) Math.round(c.getRed() * 255) << 16 | (int) Math.round(c.getGreen() * 255) << 8 | (int) Math.round(c.getBlue() * 255);
				update(o -> new CapeArt.Options(o.fit(), o.zoom(), o.panX(), o.panY(), argb, o.scale(), o.pixelArt(), o.elytra(), o.artInside()));
			};
			autoBg.setOnAction(e -> {
				color.setDisable(autoBg.isSelected());
				applyBg.run();
			});
			color.setOnAction(e -> applyBg.run());
			HBox bgRow = new HBox(8, autoBg, color);
			bgRow.setAlignment(Pos.CENTER_LEFT);

			ComboBox<String> quality = new ComboBox<>();
			quality.getItems().addAll(QUALITY_NAMES);
			int qIndex = 0;
			for (int i = 0; i < QUALITY_SCALES.length; i++) {
				if (QUALITY_SCALES[i] == options.scale()) {
					qIndex = i;
				}
			}
			quality.getSelectionModel().select(qIndex);
			quality.setOnAction(e -> {
				int s = QUALITY_SCALES[Math.max(0, quality.getSelectionModel().getSelectedIndex())];
				update(o -> o.withScale(s));
			});

			CheckBox pixel = new CheckBox("Pixel art (sharp, blocky edges)");
			pixel.setSelected(options.pixelArt());
			pixel.setOnAction(e -> update(o -> new CapeArt.Options(o.fit(), o.zoom(), o.panX(), o.panY(), o.background(), o.scale(), pixel.isSelected(),
					o.elytra(), o.artInside())));
			CheckBox elytra = new CheckBox("Paint my elytra too");
			elytra.setSelected(true);
			elytra.setOnAction(e -> update(o -> new CapeArt.Options(o.fit(), o.zoom(), o.panX(), o.panY(), o.background(), o.scale(), o.pixelArt(),
					elytra.isSelected(), o.artInside())));
			CheckBox inside = new CheckBox("Art on the inside as well");
			inside.setOnAction(e -> update(o -> new CapeArt.Options(o.fit(), o.zoom(), o.panX(), o.panY(), o.background(), o.scale(), o.pixelArt(),
					o.elytra(), inside.isSelected())));

			GridPane g = grid();
			int row = 0;
			g.addRow(row++, new Label("Placement"), fits);
			g.addRow(row++, new Label("Zoom"), zoom);
			g.addRow(row++, new Label("Left / right"), panX);
			g.addRow(row++, new Label("Up / down"), panY);
			g.addRow(row++, new Label("Background"), bgRow);
			g.addRow(row, new Label("Quality"), quality);
			converter.getChildren().setAll(g, pixel, elytra, inside);
		}

		void update(java.util.function.UnaryOperator<CapeArt.Options> change) {
			options = change.apply(options);
			schedulePreview();
		}
	}

	private static VBox labelled(String text, javafx.scene.Node node) {
		Label label = new Label(text);
		label.setStyle("-fx-font-size: 11px; -fx-text-fill: #8A8A96;");
		return new VBox(4, label, node);
	}

	private static GridPane grid() {
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		return grid;
	}

	private static Slider slider(double min, double max, double value) {
		Slider slider = new Slider(min, max, value);
		slider.setShowTickLabels(false);
		slider.setPrefWidth(200);
		return slider;
	}

	private static String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}
}
