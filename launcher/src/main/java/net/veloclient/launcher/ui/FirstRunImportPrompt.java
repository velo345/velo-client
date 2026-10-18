package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import net.veloclient.launcher.data.ClientImport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The very first start: if another launcher is on this PC, a card over the window offers to bring everything over
 * ("Bring my stuff over" opens {@link ClientImportView}) or to start fresh. Shows the logos of what was found.
 */
public final class FirstRunImportPrompt {

	/** Ease-out with a small overshoot ("back"): pops past 1 and settles. */
	private static final Interpolator BACK = new Interpolator() {
		@Override
		protected double curve(double t) {
			double c = 1.7, u = t - 1;
			return 1 + (c + 1) * u * u * u + c * u * u;
		}
	};

	private FirstRunImportPrompt() {
	}

	public static void show(StackPane layers, List<ClientImport.Source> sources, Runnable onImport) {
		Set<String> clients = new LinkedHashSet<>();
		for (ClientImport.Source source : sources) {
			clients.add(source.client());
		}
		if (clients.isEmpty()) {
			return;
		}

		HBox logos = new HBox(14);
		logos.setAlignment(Pos.CENTER);
		List<Node> logoNodes = new ArrayList<>();
		for (String client : clients.stream().limit(5).toList()) {
			Node logo = ClientImportView.brandLogo(client, 64);
			logoNodes.add(logo);
			logos.getChildren().add(logo);
		}

		Label title = new Label("Coming from another client?");
		title.setStyle("-fx-font-size: 24px; -fx-font-weight: 800; -fx-text-fill: white;");
		Label subtitle = new Label("We found " + names(new ArrayList<>(clients)) + " on this PC.\nBring your keybinds, servers and mods along - it takes a minute.");
		subtitle.setStyle("-fx-font-size: 13.5px; -fx-text-fill: rgba(255,255,255,0.68); -fx-text-alignment: center;");
		subtitle.setWrapText(true);
		subtitle.setAlignment(Pos.CENTER);

		Button bring = new Button("Bring my stuff over");
		bring.getStyleClass().add("primary-button");
		bring.setStyle("-fx-font-size: 14px; -fx-padding: 11 26 11 26;");
		bring.setDefaultButton(true);
		Button fresh = new Button("Start fresh");
		fresh.getStyleClass().add("ghost-button");
		fresh.setStyle("-fx-font-size: 14px; -fx-padding: 11 22 11 22;");
		HBox actions = new HBox(10, fresh, bring);
		actions.setAlignment(Pos.CENTER);
		Label later = new Label("You can do this any time with \"Switch from another client\" at the top.");
		later.setStyle("-fx-font-size: 11.5px; -fx-text-fill: rgba(255,255,255,0.4);");

		VBox card = new VBox(16, logos, title, subtitle, actions, later);
		card.setAlignment(Pos.CENTER);
		card.setPadding(new Insets(34, 40, 26, 40));
		card.setMaxWidth(600);
		card.setMaxHeight(Region.USE_PREF_SIZE);
		card.setStyle("-fx-background-color: linear-gradient(to bottom, rgba(40,40,46,0.98), rgba(24,24,28,0.98)); -fx-background-radius: 22;"
				+ " -fx-border-color: rgba(255,255,255,0.09); -fx-border-radius: 22; -fx-border-width: 1;"
				+ " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 40, 0.2, 0, 12);");
		VBox.setMargin(actions, new Insets(6, 0, 0, 0));

		StackPane scrim = new StackPane(card);
		scrim.setStyle("-fx-background-color: rgba(8,8,10,0.62);");
		scrim.setPadding(new Insets(24));
		layers.getChildren().add(scrim);

		Runnable close = () -> {
			FadeTransition out = new FadeTransition(Duration.millis(180), scrim);
			out.setToValue(0);
			ScaleTransition shrink = new ScaleTransition(Duration.millis(180), card);
			shrink.setToX(0.96);
			shrink.setToY(0.96);
			ParallelTransition both = new ParallelTransition(out, shrink);
			both.setOnFinished(e -> layers.getChildren().remove(scrim));
			both.play();
		};
		fresh.setOnAction(e -> close.run());
		bring.setOnAction(e -> {
			close.run();
			onImport.run();
		});
		scrim.setOnMouseClicked(e -> {
			if (e.getTarget() == scrim) {
				close.run();
			}
		});

		// In: the dimmer fades, the card rises and settles, then the logos pop in one by one.
		scrim.setOpacity(0);
		card.setScaleX(0.92);
		card.setScaleY(0.92);
		card.setTranslateY(18);
		FadeTransition fade = new FadeTransition(Duration.millis(260), scrim);
		fade.setToValue(1);
		ScaleTransition grow = new ScaleTransition(Duration.millis(420), card);
		grow.setToX(1);
		grow.setToY(1);
		grow.setInterpolator(BACK);
		TranslateTransition rise = new TranslateTransition(Duration.millis(420), card);
		rise.setToY(0);
		rise.setInterpolator(Interpolator.EASE_OUT);
		new ParallelTransition(fade, grow, rise).play();
		for (int i = 0; i < logoNodes.size(); i++) {
			Node logo = logoNodes.get(i);
			logo.setScaleX(0.3);
			logo.setScaleY(0.3);
			logo.setOpacity(0);
			ScaleTransition pop = new ScaleTransition(Duration.millis(360), logo);
			pop.setToX(1);
			pop.setToY(1);
			pop.setInterpolator(BACK);
			FadeTransition show = new FadeTransition(Duration.millis(200), logo);
			show.setToValue(1);
			ParallelTransition both = new ParallelTransition(pop, show);
			both.setDelay(Duration.millis(220 + i * 90L));
			both.play();
		}
	}

	private static String names(List<String> clients) {
		List<String> nice = clients.stream().map(c -> c.equals("Minecraft Launcher") ? "the Minecraft Launcher" : c).toList();
		if (nice.size() == 1) {
			return nice.get(0);
		}
		return String.join(", ", nice.subList(0, nice.size() - 1)) + " and " + nice.get(nice.size() - 1);
	}
}
