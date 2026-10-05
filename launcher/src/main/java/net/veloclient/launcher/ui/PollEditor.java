package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import net.veloclient.launcher.social.NewsApi;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Owner-only: create or edit a poll (question, optional description/image, 2-10 answers, timing). */
final class PollEditor {

	private static final String[] DURATIONS = {"Never ends", "1 day", "3 days", "1 week", "2 weeks", "1 month"};
	private static final long[] DURATION_MILLIS = {0, 86_400_000L, 3 * 86_400_000L, 7 * 86_400_000L, 14 * 86_400_000L, 30 * 86_400_000L};

	private PollEditor() {
	}

	static void open(NewsView.Host host, JsonObject existing) {
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.initOwner(host.owner());
		dialog.setTitle(existing == null ? "New poll" : "Edit poll");
		dialog.setHeaderText(existing == null ? "Ask the community" : "Edit poll");

		TextField question = new TextField(existing == null ? "" : NewsUi.str(existing, "question"));
		question.setPromptText("Question - e.g. \"What should we add next?\"");
		TextArea description = new TextArea(existing == null ? "" : NewsUi.str(existing, "description"));
		description.setPromptText("Details (optional)");
		description.setPrefRowCount(2);
		description.setWrapText(true);

		String[] image = {existing == null ? null : NewsUi.str(existing, "image")};
		StackPane imageBox = new StackPane();
		Label status = new Label();
		status.getStyleClass().add("settings-row-hint");
		Runnable refreshImage = () -> {
			if (image[0] == null) {
				imageBox.getChildren().clear();
				return;
			}
			StackPane cover = NewsUi.cover(image[0], null, "poll", 10);
			cover.setPrefSize(420, 110);
			imageBox.getChildren().setAll(cover);
		};
		refreshImage.run();
		Button pickImage = new Button("Add image...");
		pickImage.getStyleClass().add("glass-button");
		pickImage.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
			File file = chooser.showOpenDialog(host.owner());
			if (file == null) {
				return;
			}
			status.setText("Uploading...");
			NewsView.run(() -> {
				byte[] bytes = Files.readAllBytes(file.toPath());
				String id = NewsApi.uploadImage(bytes);
				NewsUi.remember(id, new Image(new ByteArrayInputStream(bytes)));
				return id;
			}, id -> {
				image[0] = id;
				refreshImage.run();
				status.setText("");
			}, status::setText);
		});
		Button removeImage = new Button("Remove image");
		removeImage.getStyleClass().add("ghost-button");
		removeImage.setOnAction(e -> {
			image[0] = null;
			refreshImage.run();
		});

		VBox optionsBox = new VBox(6);
		List<TextField> optionFields = new ArrayList<>();
		Runnable[] rebuild = new Runnable[1];
		rebuild[0] = () -> {
			optionsBox.getChildren().clear();
			for (int i = 0; i < optionFields.size(); i++) {
				TextField field = optionFields.get(i);
				field.setPromptText("Answer " + (i + 1));
				HBox.setHgrow(field, Priority.ALWAYS);
				Button remove = new Button("✕");
				remove.getStyleClass().add("mini-button");
				remove.setDisable(optionFields.size() <= 2);
				remove.setOnAction(e -> {
					optionFields.remove(field);
					rebuild[0].run();
				});
				HBox row = new HBox(6, field, remove);
				row.setAlignment(Pos.CENTER_LEFT);
				optionsBox.getChildren().add(row);
			}
		};
		if (existing != null) {
			for (var option : existing.getAsJsonArray("options")) {
				optionFields.add(new TextField(NewsUi.str(option.getAsJsonObject(), "text")));
			}
		}
		while (optionFields.size() < 2) {
			optionFields.add(new TextField());
		}
		rebuild[0].run();
		Button addOption = new Button("+ Answer");
		addOption.getStyleClass().add("mini-button");
		addOption.setOnAction(e -> {
			if (optionFields.size() < 10) {
				optionFields.add(new TextField());
				rebuild[0].run();
			}
		});

		CheckBox multi = new CheckBox("Allow picking more than one answer");
		multi.setSelected(existing != null && NewsUi.bool(existing, "multi"));
		CheckBox resultsFirst = new CheckBox("Show results before people vote");
		ComboBox<String> ends = new ComboBox<>();
		ends.getItems().addAll(DURATIONS);
		long currentEnd = existing == null ? 0 : NewsUi.num(existing, "closesAt");
		if (currentEnd > 0) {
			ends.getItems().add(0, "Keep current end date");
		}
		ends.getSelectionModel().select(existing == null ? 3 : 0);
		CheckBox closed = new CheckBox("Ended (no more votes)");
		closed.setSelected(existing != null && NewsUi.bool(existing, "closed"));
		if (existing != null) {
			Label note = new Label("Changing the number of answers resets the votes.");
			note.getStyleClass().add("settings-row-hint");
			optionsBox.getChildren().add(note);
		}

		VBox content = new VBox(10, question, description, new HBox(8, pickImage, removeImage), imageBox,
				new Label("Answers"), optionsBox, addOption, multi, resultsFirst, new HBox(8, new Label("Ends"), ends), closed, status);
		content.setPrefWidth(460);
		dialog.getDialogPane().setContent(content);
		ButtonType save = new ButtonType(existing == null ? "Create poll" : "Save", ButtonBar.ButtonData.OK_DONE);
		dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
		ButtonType delete = new ButtonType("Delete poll", ButtonBar.ButtonData.LEFT);
		if (existing != null) {
			dialog.getDialogPane().getButtonTypes().add(delete);
		}
		DialogStyling.apply(dialog);

		Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
		saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
			// Keep the dialog open until the server accepted it.
			e.consume();
			JsonObject poll = new JsonObject();
			if (existing != null) {
				poll.addProperty("id", NewsUi.str(existing, "id"));
			}
			poll.addProperty("question", question.getText());
			poll.addProperty("description", description.getText());
			if (image[0] != null) {
				poll.addProperty("image", image[0]);
			}
			JsonArray options = new JsonArray();
			optionFields.stream().map(TextField::getText).filter(t -> !t.isBlank()).forEach(options::add);
			poll.add("options", options);
			poll.addProperty("multi", multi.isSelected());
			poll.addProperty("showResults", resultsFirst.isSelected() ? "always" : "after_vote");
			int choice = ends.getSelectionModel().getSelectedIndex() - (currentEnd > 0 ? 1 : 0);
			poll.addProperty("closesAt", choice < 0 ? currentEnd : DURATION_MILLIS[choice] == 0 ? 0 : System.currentTimeMillis() + DURATION_MILLIS[choice]);
			poll.addProperty("closed", closed.isSelected());
			saveButton.setDisable(true);
			status.setText("Saving...");
			NewsView.run(() -> NewsApi.savePoll(poll), saved -> {
				dialog.close();
				host.openNews();
			}, error -> {
				saveButton.setDisable(false);
				status.setText(error);
			});
		});
		if (existing != null) {
			Button deleteButton = (Button) dialog.getDialogPane().lookupButton(delete);
			deleteButton.getStyleClass().add("danger-ghost-button");
			deleteButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
				e.consume();
				if (Confirm.ask(host.owner(), "Delete this poll?", "Its votes are deleted too.")) {
					NewsView.run(() -> {
						NewsApi.deletePoll(NewsUi.str(existing, "id"));
						return null;
					}, r -> {
						dialog.close();
						host.openNews();
					}, status::setText);
				}
			});
		}
		dialog.show();
	}
}
