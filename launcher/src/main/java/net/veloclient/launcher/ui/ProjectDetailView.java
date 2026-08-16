package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
import net.veloclient.launcher.modrinth.ModrinthClient;
import net.veloclient.launcher.theme.LauncherTheme;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Full "project page" style detail view for a Modrinth mod/resource pack/
 * shader - opened by clicking a search-result or installed-row icon in
 * {@link InstanceDetailView}. Modrinth's own project pages show a gallery,
 * long-form Markdown body, and every published version; this is the same
 * shape rendered with plain JavaFX controls (no Markdown renderer dependency
 * - {@link #plainText} strips the common Markdown tokens down to readable
 * text instead).
 */
public final class ProjectDetailView {

	public interface Host {
		Stage owner();

		LauncherTheme theme();

		void goBack();
	}

	private ProjectDetailView() {
	}

	public static Node build(Host host, ModrinthClient.ProjectDetail detail, List<ModrinthClient.ProjectVersion> versions,
			Set<String> installedVersionIds, Consumer<ModrinthClient.ProjectVersion> onInstallVersion) {
		LauncherTheme theme = host.theme();
		Color text = text(theme);
		Color accent = accent(theme);

		// The whole page lives inside this StackPane so the gallery lightbox
		// (see #openLightbox) can be added as a full-bleed overlay sibling on
		// top of it, the same backdrop-over-StackPane shape InstanceDetailView
		// already uses for its version-picker modal.
		StackPane page = new StackPane();

		VBox content = new VBox(16);
		content.setPadding(new Insets(4, 10, 14, 4));

		Button back = new Button("< Back");
		back.setOnAction(e -> host.goBack());
		content.getChildren().add(back);

		HBox header = new HBox(16);
		header.setAlignment(Pos.TOP_LEFT);
		header.getChildren().add(iconView(detail.iconUrl(), 96));

		VBox headerInfo = new VBox(6);
		Label title = new Label(detail.title());
		title.setFont(Font.font("System", FontWeight.BOLD, 22));
		title.setTextFill(accent);
		Label description = new Label(detail.description());
		description.setWrapText(true);
		description.setTextFill(text);
		description.getStyleClass().add("section-subtitle");

		HBox stats = new HBox(14);
		stats.getChildren().addAll(
				statLabel(formatCount(detail.downloads()) + " downloads", theme),
				statLabel(formatCount(detail.followers()) + " followers", theme));
		headerInfo.getChildren().addAll(title, description, stats);

		if (!detail.categoriesOrEmpty().isEmpty()) {
			FlowPane categories = new FlowPane(6, 6);
			for (String category : detail.categoriesOrEmpty()) {
				Label chip = new Label(category);
				chip.getStyleClass().add("version-tag");
				chip.setTextFill(text);
				chip.setPadding(new Insets(2, 6, 2, 6));
				chip.getStyleClass().add("category-chip");
				categories.getChildren().add(chip);
			}
			headerInfo.getChildren().add(categories);
		}
		HBox.setHgrow(headerInfo, Priority.ALWAYS);
		header.getChildren().add(headerInfo);
		content.getChildren().add(header);

		if (!detail.galleryOrEmpty().isEmpty()) {
			Label galleryHeading = new Label("Gallery");
			galleryHeading.setFont(Font.font("System", FontWeight.BOLD, 15));
			galleryHeading.setTextFill(text);
			content.getChildren().add(galleryHeading);

			FlowPane gallery = new FlowPane(10, 10);
			List<ModrinthClient.GalleryImage> galleryImages = detail.galleryOrEmpty();
			for (int i = 0; i < galleryImages.size(); i++) {
				int index = i;
				gallery.getChildren().add(galleryThumb(galleryImages.get(i), () -> openLightbox(page, galleryImages, index)));
			}
			content.getChildren().add(gallery);
		}

		Label descHeading = new Label("Description");
		descHeading.setFont(Font.font("System", FontWeight.BOLD, 15));
		descHeading.setTextFill(text);
		content.getChildren().add(descHeading);

		Label body = new Label(plainText(detail.body()));
		body.setWrapText(true);
		body.setTextFill(text);
		content.getChildren().add(body);

		Label versionsHeading = new Label("Versions");
		versionsHeading.setFont(Font.font("System", FontWeight.BOLD, 15));
		versionsHeading.setTextFill(text);
		content.getChildren().add(versionsHeading);

		if (versions.isEmpty()) {
			Label none = new Label("No compatible versions published.");
			none.getStyleClass().add("section-subtitle");
			none.setTextFill(text);
			content.getChildren().add(none);
		}
		for (ModrinthClient.ProjectVersion version : versions) {
			content.getChildren().add(versionRow(version, installedVersionIds.contains(version.id()), theme, onInstallVersion));
		}

		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		VBox root = new VBox(scroll);
		VBox.setVgrow(root, Priority.ALWAYS);
		page.getChildren().add(root);
		return page;
	}

	/**
	 * Full-size, navigable image viewer for the gallery - a full-bleed
	 * darkened backdrop over {@code page} (mirrors {@code
	 * InstanceDetailView#showVersionPicker}'s overlay shape) with the clicked
	 * image scaled up to fit the window, Prev/Next when there's more than
	 * one image, and its title/description as a caption. Dismissed by the
	 * close button or clicking the backdrop outside the image.
	 */
	private static void openLightbox(StackPane page, List<ModrinthClient.GalleryImage> images, int startIndex) {
		int[] index = {startIndex};

		StackPane backdrop = new StackPane();
		backdrop.getStyleClass().add("modal-backdrop");

		ImageView bigView = new ImageView();
		bigView.setPreserveRatio(true);
		bigView.fitWidthProperty().bind(page.widthProperty().subtract(180));
		bigView.fitHeightProperty().bind(page.heightProperty().subtract(160));

		Label caption = new Label();
		caption.setWrapText(true);
		caption.setTextFill(Color.WHITE);
		caption.setMaxWidth(640);
		caption.setAlignment(Pos.CENTER);
		caption.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

		Label counter = new Label();
		counter.setTextFill(Color.web("#cccccc"));

		Button prev = new Button("<");
		Button next = new Button(">");
		prev.getStyleClass().add("button-compact");
		next.getStyleClass().add("button-compact");
		boolean multi = images.size() > 1;
		prev.setVisible(multi);
		prev.setManaged(multi);
		next.setVisible(multi);
		next.setManaged(multi);

		Runnable[] show = new Runnable[1];
		show[0] = () -> {
			ModrinthClient.GalleryImage image = images.get(index[0]);
			bigView.setImage(null);
			RemoteIconLoader.load(bigView, image.url(), () -> { });
			String title = image.title() == null ? "" : image.title().strip();
			String description = image.description() == null ? "" : image.description().strip();
			caption.setText(title.isBlank() ? description : description.isBlank() ? title : title + "  -  " + description);
			counter.setText((index[0] + 1) + " / " + images.size());
		};
		prev.setOnAction(e -> {
			index[0] = (index[0] - 1 + images.size()) % images.size();
			show[0].run();
		});
		next.setOnAction(e -> {
			index[0] = (index[0] + 1) % images.size();
			show[0].run();
		});
		show[0].run();

		VBox imageColumn = new VBox(8, bigView, caption, counter);
		imageColumn.setAlignment(Pos.CENTER);
		HBox navRow = new HBox(20, prev, imageColumn, next);
		navRow.setAlignment(Pos.CENTER);

		Button close = new Button("✕");
		close.getStyleClass().add("button-compact");
		Runnable dismiss = () -> page.getChildren().remove(backdrop);
		close.setOnAction(e -> dismiss.run());

		StackPane lightboxContent = new StackPane(navRow, close);
		StackPane.setAlignment(close, Pos.TOP_RIGHT);
		StackPane.setMargin(close, new Insets(16));
		backdrop.getChildren().add(lightboxContent);

		backdrop.setOnMouseClicked(e -> {
			if (e.getTarget() == backdrop) {
				dismiss.run();
			}
		});

		page.getChildren().add(backdrop);
	}

	private static Node versionRow(ModrinthClient.ProjectVersion version, boolean installed, LauncherTheme theme,
			Consumer<ModrinthClient.ProjectVersion> onInstallVersion) {
		HBox row = new HBox(10);
		row.getStyleClass().add("mod-row");
		row.setAlignment(Pos.CENTER_LEFT);

		VBox info = new VBox(2);
		Label number = new Label(version.versionNumber() + (version.name() != null && !version.name().isBlank() ? "  -  " + version.name() : ""));
		number.setFont(Font.font("System", FontWeight.BOLD, 13));
		number.setTextFill(text(theme));
		String meta = String.join(", ", version.gameVersions()) + "  ·  " + String.join(", ", version.loaders());
		Label metaLabel = new Label(meta);
		metaLabel.getStyleClass().add("version-tag");
		metaLabel.setTextFill(text(theme));
		info.getChildren().addAll(number, metaLabel);
		HBox.setHgrow(info, Priority.ALWAYS);
		row.getChildren().add(info);

		Button install = new Button(installed ? "Installed" : "Install");
		install.getStyleClass().add("button-compact");
		install.setDisable(installed);
		install.setOnAction(e -> {
			install.setDisable(true);
			install.setText("Installing...");
			onInstallVersion.accept(version);
		});
		row.getChildren().add(install);
		return row;
	}

	private static Node galleryThumb(ModrinthClient.GalleryImage image, Runnable onClick) {
		ImageView view = new ImageView();
		view.setFitWidth(180);
		view.setFitHeight(110);
		view.setPreserveRatio(true);
		StackPane holder = new StackPane(view);
		holder.setPrefSize(180, 110);
		holder.getStyleClass().add("glass-panel");
		holder.setCursor(Cursor.HAND);
		holder.setOnMouseClicked(e -> onClick.run());
		RemoteIconLoader.load(view, image.url(), () -> { });
		return holder;
	}

	private static Label statLabel(String text, LauncherTheme theme) {
		Label label = new Label(text);
		label.getStyleClass().add("version-tag");
		label.setTextFill(text(theme));
		return label;
	}

	private static Node iconView(String url, double size) {
		StackPane holder = new StackPane();
		holder.setPrefSize(size, size);
		holder.setMinSize(size, size);
		holder.getStyleClass().add("instance-icon-custom");
		ImageView view = new ImageView();
		view.setFitWidth(size);
		view.setFitHeight(size);
		view.setPreserveRatio(true);
		Image fallback = new Image(InstanceDetailView.class.getResourceAsStream("/net/veloclient/launcher/images/logo.png"), size, size, true, true);
		view.setImage(fallback);
		RemoteIconLoader.load(view, url, () -> view.setImage(fallback));
		holder.getChildren().add(view);
		return holder;
	}

	// ---- Markdown -> plain text ----

	private static final Pattern IMAGES = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
	private static final Pattern LINKS = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
	private static final Pattern HEADINGS = Pattern.compile("(?m)^#{1,6}\\s*");
	private static final Pattern EMPHASIS = Pattern.compile("[*_`]{1,3}");
	private static final Pattern HTML_TAGS = Pattern.compile("</?[a-zA-Z][^>]*>");
	private static final Pattern BLANK_RUNS = Pattern.compile("\n{3,}");

	/** Strips the common Markdown tokens down to plain, readable text - this launcher has no Markdown renderer, so a description with formatting reads better stripped than shown verbatim with stray {@code #}/{@code *} characters. */
	static String plainText(String markdown) {
		if (markdown == null || markdown.isBlank()) {
			return "No description provided.";
		}
		String result = markdown;
		result = IMAGES.matcher(result).replaceAll("");
		result = LINKS.matcher(result).replaceAll("$1");
		result = HEADINGS.matcher(result).replaceAll("");
		result = EMPHASIS.matcher(result).replaceAll("");
		result = HTML_TAGS.matcher(result).replaceAll("");
		result = BLANK_RUNS.matcher(result).replaceAll("\n\n");
		return result.strip();
	}

	private static String formatCount(long count) {
		if (count >= 1_000_000) {
			return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
		}
		if (count >= 1_000) {
			return String.format(Locale.ROOT, "%.1fK", count / 1_000.0);
		}
		return String.valueOf(count);
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
