package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import net.veloclient.launcher.social.SkinHeads;

import java.util.Locale;

/** A player's face (from their skin), with their initial as a placeholder while it loads. */
public final class Heads {

	private Heads() {
	}

	public static Node head(String uuid, String name, double size) {
		StackPane holder = new StackPane();
		holder.setMinSize(size, size);
		holder.setPrefSize(size, size);
		holder.setMaxSize(size, size);
		Label initial = new Label(name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT));
		initial.setTextFill(Color.WHITE);
		initial.setFont(Font.font("Inter", FontWeight.BOLD, size * 0.45));
		holder.setStyle("-fx-background-color: rgba(255,255,255,0.12); -fx-background-radius: " + (size / 5) + ";");
		holder.getChildren().add(initial);
		if (uuid != null) {
			SkinHeads.skin(uuid).thenAccept(bytes -> {
				if (bytes != null) {
					Platform.runLater(() -> {
						StackPane rendered = PlayerHeadView.build(bytes, size);
						if (rendered != null) {
							holder.setStyle("");
							holder.getChildren().setAll(rendered);
						}
					});
				}
			});
		}
		return holder;
	}
}
