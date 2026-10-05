package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import net.veloclient.launcher.social.NewsApi;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared pieces of the News page, the Home banner and the post editor's live preview: cover
 * images (cropped to fill, rounded), tag chips, dates, and the block renderer that turns a post's
 * blocks into native JavaFX nodes.
 */
public final class NewsUi {

	private static final Map<String, Image> IMAGES = new ConcurrentHashMap<>();
	private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`|\\[(.+?)]\\((https?://[^)\\s]+)\\)");

	private NewsUi() {
	}

	// ---- Images ----

	/** Loads a news image (memory + disk cached) and hands it over on the FX thread; null if it can't be loaded. */
	static void image(String id, Consumer<Image> onReady) {
		if (id == null) {
			onReady.accept(null);
			return;
		}
		Image cached = IMAGES.get(id);
		if (cached != null) {
			onReady.accept(cached);
			return;
		}
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			byte[] bytes = NewsApi.image(id);
			Image image = bytes == null ? null : new Image(new ByteArrayInputStream(bytes));
			if (image != null && !image.isError()) {
				IMAGES.put(id, image);
			}
			Platform.runLater(() -> onReady.accept(image != null && !image.isError() ? image : null));
		});
	}

	/** Puts an already-known image into the cache (the editor uses this right after an upload). */
	static void remember(String id, Image image) {
		if (id != null && image != null) {
			IMAGES.put(id, image);
		}
	}

	/**
	 * A rounded box that shows {@code imageId} cropped to fill it ("cover"), or - without an image,
	 * or while it loads - a gradient in the post's accent color with its tag as a watermark.
	 */
	static StackPane cover(String imageId, String accent, String tag, double radius) {
		StackPane box = new StackPane();
		box.getStyleClass().add("news-cover");
		box.setMinSize(0, 0);
		Region fallback = new Region();
		fallback.setStyle(fallbackStyle(accent));
		Label watermark = new Label(tag == null ? "" : tag.toUpperCase(Locale.ROOT));
		watermark.getStyleClass().add("news-cover-watermark");
		ImageView logo = new ImageView(new Image(NewsUi.class.getResourceAsStream("/net/veloclient/launcher/images/logo.png"), 26, 26, true, true));
		logo.setOpacity(0.22);
		HBox mark = new HBox(8, logo, watermark);
		mark.setAlignment(Pos.CENTER_RIGHT);
		mark.setMouseTransparent(true);
		mark.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane.setAlignment(mark, Pos.TOP_RIGHT);
		StackPane.setMargin(mark, new javafx.geometry.Insets(14, 16, 0, 0));
		box.getChildren().add(fallback);
		if (tag != null) {
			box.getChildren().add(mark);
		}

		Rectangle clip = new Rectangle();
		clip.setArcWidth(radius * 2);
		clip.setArcHeight(radius * 2);
		clip.widthProperty().bind(box.widthProperty());
		clip.heightProperty().bind(box.heightProperty());
		box.setClip(clip);

		if (imageId != null) {
			ImageView view = new ImageView();
			view.setSmooth(true);
			view.setPreserveRatio(false);
			view.fitWidthProperty().bind(box.widthProperty());
			view.fitHeightProperty().bind(box.heightProperty());
			Runnable crop = () -> cropToFill(view, box.getWidth(), box.getHeight());
			box.widthProperty().addListener((o, a, b) -> crop.run());
			box.heightProperty().addListener((o, a, b) -> crop.run());
			view.setManaged(false);
			image(imageId, image -> {
				if (image == null) {
					return;
				}
				view.setImage(image);
				crop.run();
				view.setOpacity(0);
				box.getChildren().add(view);
				javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(260), view);
				fade.setToValue(1);
				fade.play();
			});
		}
		return box;
	}

	private static void cropToFill(ImageView view, double width, double height) {
		Image image = view.getImage();
		if (image == null || width <= 0 || height <= 0) {
			return;
		}
		double scale = Math.max(width / image.getWidth(), height / image.getHeight());
		double w = width / scale;
		double h = height / scale;
		view.setViewport(new javafx.geometry.Rectangle2D((image.getWidth() - w) / 2, (image.getHeight() - h) / 2, w, h));
		view.resizeRelocate(0, 0, width, height);
	}

	static String fallbackStyle(String accent) {
		String color = validColor(accent) ? accent : "-velo-accent-start";
		return "-fx-background-color: radial-gradient(center 80% 10%, radius 90%, derive(" + color + ", 10%) 0%, transparent 70%),"
				+ " linear-gradient(to bottom right, derive(" + color + ", -35%), derive(" + color + ", -82%));";
	}

	static boolean validColor(String color) {
		return color != null && color.matches("#[0-9a-fA-F]{6}");
	}

	// ---- Small pieces ----

	static Label tag(String tag, String accent) {
		Label chip = new Label(tag == null || tag.isBlank() ? "News" : tag);
		chip.getStyleClass().add("news-tag");
		if (validColor(accent)) {
			chip.setStyle("-fx-background-color: derive(" + accent + ", -55%); -fx-text-fill: derive(" + accent + ", 45%);");
		}
		return chip;
	}

	static String date(long millis) {
		if (millis <= 0) {
			return "Draft";
		}
		LocalDate day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
		long days = ChronoUnit.DAYS.between(day, LocalDate.now());
		if (days == 0) {
			return "Today";
		}
		if (days == 1) {
			return "Yesterday";
		}
		if (days < 7) {
			return days + " days ago";
		}
		return day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
	}

	static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	static long num(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
	}

	static boolean bool(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}

	// ---- Rich text ----

	/** Text with **bold**, *italic*, `code` and [links](https://...) as a wrapping TextFlow. */
	static TextFlow richText(String source, String styleClass) {
		TextFlow flow = new TextFlow();
		flow.getStyleClass().add(styleClass);
		String text = source == null ? "" : source;
		Matcher m = INLINE.matcher(text);
		int last = 0;
		while (m.find()) {
			if (m.start() > last) {
				flow.getChildren().add(styled(text.substring(last, m.start()), "news-run"));
			}
			if (m.group(1) != null) {
				flow.getChildren().add(styled(m.group(1), "news-run", "news-run-bold"));
			} else if (m.group(2) != null) {
				flow.getChildren().add(styled(m.group(2), "news-run", "news-run-italic"));
			} else if (m.group(3) != null) {
				flow.getChildren().add(styled(m.group(3), "news-run", "news-run-code"));
			} else {
				Text link = styled(m.group(4), "news-run", "news-run-link");
				String url = m.group(5);
				link.setOnMouseClicked(e -> openUrl(url));
				link.setCursor(javafx.scene.Cursor.HAND);
				flow.getChildren().add(link);
			}
			last = m.end();
		}
		if (last < text.length()) {
			flow.getChildren().add(styled(text.substring(last), "news-run"));
		}
		return flow;
	}

	private static Text styled(String text, String... classes) {
		Text node = new Text(text);
		node.getStyleClass().addAll(classes);
		return node;
	}

	static void openUrl(String url) {
		if (url == null || !url.matches("https?://.+")) {
			return;
		}
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
			} catch (Exception e) {
				try {
					new ProcessBuilder("xdg-open", url).start();
				} catch (Exception ignored) {
					// Nothing else to try.
				}
			}
		});
	}

	// ---- Post body ----

	/** A post's blocks as native nodes (shared by the post page and the editor's live preview). */
	static VBox blocks(JsonArray blocks, String accent) {
		VBox body = new VBox(14);
		body.getStyleClass().add("news-body");
		if (blocks == null) {
			return body;
		}
		for (JsonElement element : blocks) {
			Node node = block(element.getAsJsonObject(), accent);
			if (node != null) {
				body.getChildren().add(node);
			}
		}
		return body;
	}

	private static Node block(JsonObject b, String accent) {
		String type = str(b, "type");
		String text = str(b, "text");
		if (type == null) {
			return null;
		}
		switch (type) {
			case "heading" -> {
				Label heading = new Label(text == null ? "" : text);
				heading.getStyleClass().add("news-heading");
				heading.setWrapText(true);
				if (validColor(accent)) {
					Region bar = new Region();
					bar.getStyleClass().add("news-heading-bar");
					bar.setStyle("-fx-background-color: " + accent + ";");
					HBox row = new HBox(10, bar, heading);
					row.setAlignment(Pos.CENTER_LEFT);
					return row;
				}
				return heading;
			}
			case "text" -> {
				return richText(text, "news-text");
			}
			case "image" -> {
				String id = str(b, "image");
				if (id == null) {
					return null;
				}
				ImageView view = new ImageView();
				view.setPreserveRatio(true);
				view.setSmooth(true);
				StackPane frame = new StackPane(view);
				frame.getStyleClass().add("news-image");
				frame.setMinHeight(60);
				frame.setMaxWidth(Double.MAX_VALUE);
				view.fitWidthProperty().bind(frame.widthProperty().subtract(2));
				Rectangle clip = new Rectangle();
				clip.setArcWidth(20);
				clip.setArcHeight(20);
				clip.widthProperty().bind(view.fitWidthProperty());
				view.imageProperty().addListener((o, a, img) -> {
					if (img != null) {
						clip.heightProperty().bind(view.fitWidthProperty().multiply(img.getHeight() / img.getWidth()));
					}
				});
				view.setClip(clip);
				image(id, view::setImage);
				VBox box = new VBox(6, frame);
				String caption = str(b, "caption");
				if (caption != null && !caption.isBlank()) {
					Label label = new Label(caption);
					label.getStyleClass().add("news-caption");
					label.setWrapText(true);
					box.getChildren().add(label);
				}
				return box;
			}
			case "callout" -> {
				String style = str(b, "style") == null ? "info" : str(b, "style");
				Label icon = new Label(switch (style) {
					case "success" -> "✓";
					case "warning" -> "!";
					default -> "i";
				});
				icon.getStyleClass().addAll("news-callout-icon", "news-callout-icon-" + style);
				TextFlow flow = richText(text, "news-text");
				HBox.setHgrow(flow, javafx.scene.layout.Priority.ALWAYS);
				HBox callout = new HBox(12, icon, flow);
				callout.setAlignment(Pos.TOP_LEFT);
				callout.getStyleClass().addAll("news-callout", "news-callout-" + style);
				return callout;
			}
			case "list" -> {
				VBox list = new VBox(7);
				JsonArray items = b.has("items") && b.get("items").isJsonArray() ? b.getAsJsonArray("items") : new JsonArray();
				for (JsonElement item : items) {
					Region dot = new Region();
					dot.getStyleClass().add("news-list-dot");
					if (validColor(accent)) {
						dot.setStyle("-fx-background-color: " + accent + ";");
					}
					StackPane dotBox = new StackPane(dot);
					dotBox.setPadding(new Insets(6, 0, 0, 0));
					dotBox.setAlignment(Pos.TOP_CENTER);
					TextFlow flow = richText(item.getAsString(), "news-text");
					HBox.setHgrow(flow, javafx.scene.layout.Priority.ALWAYS);
					HBox row = new HBox(10, dotBox, flow);
					list.getChildren().add(row);
				}
				return list;
			}
			case "divider" -> {
				Region line = new Region();
				line.getStyleClass().add("news-divider");
				return line;
			}
			case "button" -> {
				Button button = new Button(text == null || text.isBlank() ? "Open link" : text);
				button.getStyleClass().add("primary-button");
				String url = str(b, "url");
				button.setOnAction(e -> openUrl(url));
				return button;
			}
			default -> {
				return null;
			}
		}
	}

	/** Plain list of strings from a JSON array (null-safe). */
	static List<String> strings(JsonObject o, String key) {
		List<String> out = new ArrayList<>();
		if (o != null && o.has(key) && o.get(key).isJsonArray()) {
			o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
		}
		return out;
	}
}
