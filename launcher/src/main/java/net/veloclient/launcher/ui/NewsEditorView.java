package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import net.veloclient.launcher.social.NewsApi;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Owner-only post editor: basics (title, summary, tag, version, accent color, pinned), a cover
 * image, and the body as a list of blocks (heading, text, image, callout, list, divider, button)
 * you can add, reorder and remove - with a live preview of the finished post next to it. Images
 * are uploaded when picked (or dropped onto the editor). "Import post" loads a prepared post file
 * (JSON; images can be given as {@code file:relative/path.png} and are uploaded on import).
 */
final class NewsEditorView {

	private static final List<String> TAGS = List.of("Update", "News", "Event", "Community", "Sneak peek", "Fix");
	private static final String[][] BLOCK_TYPES = {
			{"heading", "Heading"}, {"text", "Text"}, {"image", "Image"}, {"callout", "Callout"},
			{"list", "List"}, {"divider", "Divider"}, {"button", "Button"}};

	private final NewsView.Host host;
	private JsonObject post;
	private final VBox blocksBox = new VBox(10);
	private final StackPane preview = new StackPane();
	private final PauseTransition previewDelay = new PauseTransition(Duration.millis(160));
	private final Label status = new Label();
	private StackPane coverBox;
	private VBox basicsBox;

	private NewsEditorView(NewsView.Host host, JsonObject existing) {
		this.host = host;
		this.post = existing == null ? fresh() : existing.deepCopy();
		previewDelay.setOnFinished(e -> renderPreview());
	}

	static Node build(NewsView.Host host, JsonObject existing) {
		return new NewsEditorView(host, existing).root(existing == null);
	}

	private static JsonObject fresh() {
		JsonObject post = new JsonObject();
		post.addProperty("title", "");
		post.addProperty("summary", "");
		post.addProperty("tag", "Update");
		post.add("blocks", new JsonArray());
		JsonObject first = new JsonObject();
		first.addProperty("type", "text");
		first.addProperty("text", "");
		post.getAsJsonArray("blocks").add(first);
		return post;
	}

	private Node root(boolean isNew) {
		Button back = new Button("←  News");
		back.getStyleClass().add("ghost-button");
		back.setOnAction(e -> {
			if (Confirm.ask(host.owner(), "Leave the editor?", "Unsaved changes to this post are lost.")) {
				host.openNews();
			}
		});
		Label heading = new Label(isNew ? "New post" : "Edit post");
		heading.getStyleClass().add("page-title");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button importButton = new Button("Import post...");
		importButton.getStyleClass().add("glass-button");
		importButton.setOnAction(e -> importPost());
		boolean published = NewsUi.bool(post, "published");
		Button draft = new Button(published ? "Unpublish" : "Save draft");
		draft.getStyleClass().add("glass-button");
		draft.setOnAction(e -> save(false));
		Button publish = new Button(published ? "Save changes" : "Publish");
		publish.getStyleClass().add("primary-button");
		publish.setOnAction(e -> save(true));
		HBox top = new HBox(10, back, heading, spacer, status, importButton, draft, publish);
		top.setAlignment(Pos.CENTER_LEFT);
		status.getStyleClass().add("news-card-date");

		basicsBox = new VBox();
		VBox form = new VBox(14, basicsBox, section("Cover image", coverEditor()), section("Content", contentEditor()));
		form.setPadding(new Insets(0, 10, 20, 0));
		rebuildBasics();
		ScrollPane formScroll = new ScrollPane(form);
		formScroll.setFitToWidth(true);
		formScroll.getStyleClass().add("scroll-pane");
		formScroll.setMinWidth(380);
		formScroll.setPrefWidth(470);

		Label previewLabel = new Label("LIVE PREVIEW");
		previewLabel.getStyleClass().add("news-section-label");
		ScrollPane previewScroll = new ScrollPane(preview);
		previewScroll.setFitToWidth(true);
		previewScroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(previewScroll, Priority.ALWAYS);
		VBox previewColumn = new VBox(8, previewLabel, previewScroll);
		previewColumn.getStyleClass().add("news-preview-frame");
		HBox.setHgrow(previewColumn, Priority.ALWAYS);

		HBox body = new HBox(16, formScroll, previewColumn);
		VBox.setVgrow(body, Priority.ALWAYS);
		renderPreview();
		return new VBox(14, top, body);
	}

	private static Node section(String title, Node content) {
		Label label = new Label(title);
		label.getStyleClass().add("section-label");
		VBox box = new VBox(10, label, content);
		box.getStyleClass().add("news-editor-section");
		return box;
	}

	// ---- Basics ----

	private void rebuildBasics() {
		TextField title = field("Title - e.g. \"Velo Client 0.8.4 is here\"", "title");
		TextArea summary = new TextArea(NewsUi.str(post, "summary"));
		summary.setPromptText("One or two sentences for the banner and the post card");
		summary.setWrapText(true);
		summary.setPrefRowCount(2);
		summary.textProperty().addListener((o, a, v) -> set("summary", v));

		ComboBox<String> tag = new ComboBox<>();
		tag.getItems().addAll(TAGS);
		tag.setEditable(true);
		tag.setValue(NewsUi.str(post, "tag") == null ? "Update" : NewsUi.str(post, "tag"));
		tag.valueProperty().addListener((o, a, v) -> set("tag", v));
		tag.setPrefWidth(150);
		TextField version = field("Version (optional)", "version");
		version.setPrefWidth(130);

		String accent = NewsUi.str(post, "accent");
		ColorPicker color = new ColorPicker(NewsUi.validColor(accent) ? Color.web(accent) : Color.web("#ff4444"));
		color.getStyleClass().add("button");
		CheckBox themed = new CheckBox("Theme color");
		themed.setSelected(!NewsUi.validColor(accent));
		color.setDisable(themed.isSelected());
		color.valueProperty().addListener((o, a, v) -> set("accent", hex(v)));
		themed.selectedProperty().addListener((o, a, v) -> {
			color.setDisable(v);
			set("accent", v ? null : hex(color.getValue()));
		});
		CheckBox pinned = new CheckBox("Pin to the top of the banner");
		pinned.setSelected(NewsUi.bool(post, "pinned"));
		pinned.selectedProperty().addListener((o, a, v) -> {
			post.addProperty("pinned", v);
			schedulePreview();
		});

		HBox tagRow = new HBox(10, labeled("Tag", tag), labeled("Version", version));
		HBox colorRow = new HBox(10, labeled("Accent", color), themed);
		colorRow.setAlignment(Pos.BOTTOM_LEFT);
		basicsBox.getChildren().setAll(section("Basics", new VBox(10, title, summary, tagRow, colorRow, pinned)));
	}

	private static Node labeled(String label, Node node) {
		Label l = new Label(label);
		l.getStyleClass().add("news-field-label");
		return new VBox(4, l, node);
	}

	private TextField field(String prompt, String key) {
		TextField field = new TextField(NewsUi.str(post, key) == null ? "" : NewsUi.str(post, key));
		field.setPromptText(prompt);
		field.textProperty().addListener((o, a, v) -> set(key, v));
		return field;
	}

	private void set(String key, String value) {
		if (value == null) {
			post.remove(key);
		} else {
			post.addProperty(key, value);
		}
		schedulePreview();
	}

	private static String hex(Color c) {
		return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
	}

	// ---- Cover ----

	private Node coverEditor() {
		coverBox = new StackPane();
		refreshCover();
		Button upload = new Button("Upload image...");
		upload.getStyleClass().add("glass-button");
		upload.setOnAction(e -> pickImage(id -> {
			set("cover", id);
			refreshCover();
		}));
		Button remove = new Button("Remove");
		remove.getStyleClass().add("ghost-button");
		remove.setOnAction(e -> {
			set("cover", null);
			refreshCover();
		});
		Label hint = new Label("Wide images look best (16:9 or wider). You can also drop an image here.");
		hint.getStyleClass().add("settings-row-hint");
		hint.setWrapText(true);
		acceptDrops(coverBox, id -> {
			set("cover", id);
			refreshCover();
		});
		return new VBox(8, coverBox, new HBox(8, upload, remove), hint);
	}

	private void refreshCover() {
		StackPane cover = NewsUi.cover(NewsUi.str(post, "cover"), NewsUi.str(post, "accent"), NewsUi.str(post, "tag"), 12);
		cover.setPrefHeight(150);
		coverBox.getChildren().setAll(cover);
	}

	// ---- Blocks ----

	private Node contentEditor() {
		rebuildBlocks();
		FlowPane add = new FlowPane(6, 6);
		for (String[] type : BLOCK_TYPES) {
			Button button = new Button("+ " + type[1]);
			button.getStyleClass().add("mini-button");
			button.setOnAction(e -> {
				JsonObject block = new JsonObject();
				block.addProperty("type", type[0]);
				if ("list".equals(type[0])) {
					block.add("items", new JsonArray());
				}
				if ("callout".equals(type[0])) {
					block.addProperty("style", "info");
				}
				blocks().add(block);
				rebuildBlocks();
				schedulePreview();
			});
			add.getChildren().add(button);
		}
		Label hint = new Label("Text supports **bold**, *italic*, `code` and [links](https://example.com).");
		hint.getStyleClass().add("settings-row-hint");
		hint.setWrapText(true);
		return new VBox(10, blocksBox, add, hint);
	}

	private JsonArray blocks() {
		if (!post.has("blocks") || !post.get("blocks").isJsonArray()) {
			post.add("blocks", new JsonArray());
		}
		return post.getAsJsonArray("blocks");
	}

	private void rebuildBlocks() {
		blocksBox.getChildren().clear();
		JsonArray blocks = blocks();
		for (int i = 0; i < blocks.size(); i++) {
			blocksBox.getChildren().add(blockEditor(blocks.get(i).getAsJsonObject(), i, blocks.size()));
		}
		if (blocks.isEmpty()) {
			Label empty = new Label("No content yet - add a block below.");
			empty.getStyleClass().add("settings-row-hint");
			blocksBox.getChildren().add(empty);
		}
	}

	private Node blockEditor(JsonObject block, int index, int count) {
		String type = NewsUi.str(block, "type");
		String name = "Block";
		for (String[] t : BLOCK_TYPES) {
			if (t[0].equals(type)) {
				name = t[1];
			}
		}
		Label label = new Label(name.toUpperCase(Locale.ROOT));
		label.getStyleClass().add("news-block-type");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button up = tool("↑", () -> move(index, -1));
		up.setDisable(index == 0);
		Button down = tool("↓", () -> move(index, 1));
		down.setDisable(index == count - 1);
		Button remove = tool("✕", () -> {
			blocks().remove(index);
			rebuildBlocks();
			schedulePreview();
		});
		HBox header = new HBox(6, label, spacer, up, down, remove);
		header.setAlignment(Pos.CENTER_LEFT);
		VBox box = new VBox(8, header);
		box.getStyleClass().add("news-block-editor");

		switch (type == null ? "" : type) {
			case "heading" -> box.getChildren().add(blockField(block, "text", "Heading text"));
			case "text" -> box.getChildren().add(blockArea(block, "text", "Write a paragraph...", 4));
			case "callout" -> {
				ComboBox<String> style = new ComboBox<>();
				style.getItems().addAll("info", "success", "warning");
				style.setValue(NewsUi.str(block, "style") == null ? "info" : NewsUi.str(block, "style"));
				style.valueProperty().addListener((o, a, v) -> {
					block.addProperty("style", v);
					schedulePreview();
				});
				box.getChildren().addAll(style, blockArea(block, "text", "Something to highlight", 2));
			}
			case "list" -> {
				TextArea area = new TextArea(String.join("\n", NewsUi.strings(block, "items")));
				area.setPromptText("One item per line");
				area.setPrefRowCount(4);
				area.setWrapText(true);
				area.textProperty().addListener((o, a, v) -> {
					JsonArray items = new JsonArray();
					for (String line : v.split("\n")) {
						String clean = line.replaceFirst("^\\s*[-*•]\\s*", "").trim();
						if (!clean.isEmpty()) {
							items.add(clean);
						}
					}
					block.add("items", items);
					schedulePreview();
				});
				box.getChildren().add(area);
			}
			case "image" -> {
				StackPane thumb = new StackPane();
				Runnable refresh = () -> {
					StackPane cover = NewsUi.cover(NewsUi.str(block, "image"), NewsUi.str(post, "accent"), "image", 10);
					cover.setPrefHeight(110);
					thumb.getChildren().setAll(cover);
				};
				refresh.run();
				Consumer<String> setImage = id -> {
					block.addProperty("image", id);
					refresh.run();
					schedulePreview();
				};
				acceptDrops(thumb, setImage);
				Button pick = new Button(NewsUi.str(block, "image") == null ? "Upload image..." : "Replace image...");
				pick.getStyleClass().add("glass-button");
				pick.setOnAction(e -> pickImage(setImage));
				box.getChildren().addAll(thumb, pick, blockField(block, "caption", "Caption (optional)"));
			}
			case "button" -> box.getChildren().addAll(blockField(block, "text", "Button text"), blockField(block, "url", "https://..."));
			default -> {
				// Divider: nothing to edit.
			}
		}
		return box;
	}

	private Button tool(String glyph, Runnable action) {
		Button button = new Button(glyph);
		button.getStyleClass().add("mini-button");
		button.setOnAction(e -> action.run());
		return button;
	}

	private void move(int index, int step) {
		JsonArray blocks = blocks();
		int target = index + step;
		if (target < 0 || target >= blocks.size()) {
			return;
		}
		JsonElement a = blocks.get(index);
		blocks.set(index, blocks.get(target));
		blocks.set(target, a);
		rebuildBlocks();
		schedulePreview();
	}

	private TextField blockField(JsonObject block, String key, String prompt) {
		TextField field = new TextField(NewsUi.str(block, key) == null ? "" : NewsUi.str(block, key));
		field.setPromptText(prompt);
		field.textProperty().addListener((o, a, v) -> {
			block.addProperty(key, v);
			schedulePreview();
		});
		return field;
	}

	private TextArea blockArea(JsonObject block, String key, String prompt, int rows) {
		TextArea area = new TextArea(NewsUi.str(block, key) == null ? "" : NewsUi.str(block, key));
		area.setPromptText(prompt);
		area.setWrapText(true);
		area.setPrefRowCount(rows);
		area.textProperty().addListener((o, a, v) -> {
			block.addProperty(key, v);
			schedulePreview();
		});
		return area;
	}

	// ---- Images ----

	private void pickImage(Consumer<String> onUploaded) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Choose an image");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
		File file = chooser.showOpenDialog(host.owner());
		if (file != null) {
			upload(file.toPath(), onUploaded);
		}
	}

	private void acceptDrops(Node target, Consumer<String> onUploaded) {
		target.setOnDragOver(e -> {
			if (e.getDragboard().hasFiles()) {
				e.acceptTransferModes(TransferMode.COPY);
			}
			e.consume();
		});
		target.setOnDragDropped(e -> {
			List<File> files = e.getDragboard().getFiles();
			if (files != null && !files.isEmpty()) {
				upload(files.get(0).toPath(), onUploaded);
			}
			e.setDropCompleted(true);
			e.consume();
		});
	}

	private void upload(Path file, Consumer<String> onUploaded) {
		status.setText("Uploading " + file.getFileName() + "...");
		NewsView.run(() -> {
			byte[] bytes = Files.readAllBytes(file);
			String id = NewsApi.uploadImage(bytes);
			NewsUi.remember(id, new Image(new ByteArrayInputStream(bytes)));
			return id;
		}, id -> {
			status.setText("Image uploaded.");
			onUploaded.accept(id);
		}, error -> status.setText("Upload failed: " + error));
	}

	/** Loads a prepared post (JSON). {@code file:...} image references are uploaded first. */
	private void importPost() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Import a post file");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Velo post", "*.json"));
		File file = chooser.showOpenDialog(host.owner());
		if (file == null) {
			return;
		}
		status.setText("Importing...");
		Path dir = file.toPath().getParent();
		String keepId = NewsUi.str(post, "id");
		NewsView.run(() -> {
			JsonObject imported = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
			imported.remove("id");
			imported.remove("published");
			resolveImage(imported, "cover", dir);
			if (imported.has("blocks")) {
				for (JsonElement b : imported.getAsJsonArray("blocks")) {
					resolveImage(b.getAsJsonObject(), "image", dir);
				}
			}
			return imported;
		}, imported -> {
			if (keepId != null) {
				imported.addProperty("id", keepId);
			}
			imported.addProperty("published", NewsUi.bool(post, "published"));
			post = imported;
			rebuildBasics();
			refreshCover();
			rebuildBlocks();
			renderPreview();
			status.setText("Imported - check the preview, then publish.");
		}, error -> status.setText("Import failed: " + error));
	}

	private static void resolveImage(JsonObject object, String key, Path dir) throws Exception {
		String value = NewsUi.str(object, key);
		if (value != null && value.startsWith("file:")) {
			Path path = dir.resolve(value.substring("file:".length())).normalize();
			byte[] bytes = Files.readAllBytes(path);
			String id = NewsApi.uploadImage(bytes);
			NewsUi.remember(id, new Image(new ByteArrayInputStream(bytes)));
			object.addProperty(key, id);
		}
	}

	// ---- Preview & saving ----

	private void schedulePreview() {
		previewDelay.playFromStart();
	}

	private void renderPreview() {
		preview.getChildren().setAll(NewsView.article(post));
	}

	private void save(boolean publish) {
		if (NewsUi.str(post, "title") == null || NewsUi.str(post, "title").isBlank()) {
			status.setText("Give the post a title first.");
			return;
		}
		JsonObject copy = post.deepCopy();
		copy.addProperty("published", publish);
		status.setText(publish ? "Publishing..." : "Saving...");
		NewsView.run(() -> NewsApi.savePost(copy), saved -> {
			post = saved;
			host.show(NewsView.post(host, saved, true));
		}, error -> status.setText(error));
	}
}
