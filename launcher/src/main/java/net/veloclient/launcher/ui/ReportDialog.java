package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import net.veloclient.launcher.social.StoreApi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Report a friend's messages to the Velo Client team: what it is, a note, and - only if ticked - the recent messages. */
public final class ReportDialog {

	private static final String[][] CATEGORIES = {
			{"spam", "Spam"}, {"scam", "Scam"}, {"insult", "Insulting"}, {"harassment", "Harassment"}, {"inappropriate", "Inappropriate"}, {"other", "Other"}};

	private ReportDialog() {
	}

	public static void show(Window owner, String uuid, String name, String context) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Report " + name);
		dialog.setHeaderText("Report " + name);
		DialogStyling.apply(dialog);
		String[] category = {"spam"};
		FlowPane chips = new FlowPane(6, 6);
		Runnable[] paint = new Runnable[1];
		for (String[] c : CATEGORIES) {
			Button chip = new Button(c[1]);
			chip.setUserData(c[0]);
			chip.setOnAction(e -> {
				category[0] = c[0];
				paint[0].run();
			});
			chips.getChildren().add(chip);
		}
		paint[0] = () -> chips.getChildren().forEach(n -> n.setStyle("-fx-background-radius: 14; -fx-padding: 5 12 5 12; -fx-cursor: hand; -fx-text-fill: white;"
				+ " -fx-background-color: " + (category[0].equals(((Button) n).getUserData()) ? "#d93a3a" : "rgba(255,255,255,0.08)") + ";"));
		paint[0].run();
		TextArea note = new TextArea();
		note.setPromptText("What happened? (optional)");
		note.setPrefRowCount(3);
		note.setWrapText(true);
		CheckBox attach = new CheckBox("Attach the recent messages of this chat for staff to read");
		attach.setSelected(true);
		Label disclaimer = new Label("Chats aren't watched by anyone - chat at your own risk. Reports are read by people, so not every message"
				+ " can be checked or acted on. You can also block " + name + ".");
		disclaimer.getStyleClass().add("settings-row-hint");
		disclaimer.setWrapText(true);
		disclaimer.setMaxWidth(420);
		Label status = new Label();
		status.setWrapText(true);
		VBox body = new VBox(10, new Label("What is it?"), chips, note, attach, disclaimer, status);
		body.setPadding(new Insets(4, 0, 0, 0));
		body.setPrefWidth(440);
		dialog.getDialogPane().setContent(body);
		ButtonType sendType = new ButtonType("Send report", ButtonBar.ButtonData.OK_DONE);
		dialog.getDialogPane().getButtonTypes().addAll(sendType, ButtonType.CANCEL);
		Button send = (Button) dialog.getDialogPane().lookupButton(sendType);
		send.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
			event.consume();
			send.setDisable(true);
			status.setText("Sending...");
			CompletableFuture.supplyAsync(() -> {
				try {
					StoreApi.reportPlayer(uuid, category[0], note.getText(), context, attach.isSelected());
					return null;
				} catch (StoreApi.StoreError e) {
					return e.getMessage();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(error -> Platform.runLater(() -> {
				if (error == null) {
					status.setText("Thanks - the Velo Client team will look at it.");
					send.setText("Sent");
				} else {
					status.setText(error);
					send.setDisable(false);
				}
			}));
		});
		dialog.show();
	}
}
