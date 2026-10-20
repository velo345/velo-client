package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import net.veloclient.launcher.data.ClientImport;
import net.veloclient.launcher.social.StoreApi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The very first start, as a card over the window: welcome, sign in, bring your stuff from another client (only
 * when one was found), a friend's invite code, done. Every step can be skipped; a code entered before signing in
 * is kept and redeemed once the Velo server knows who you are.
 */
public final class OnboardingView {

	public interface Host {
		/** The signed-in player's name, or null. */
		String accountName();

		/** Starts Microsoft sign-in; {@code done} runs (on the FX thread) once it worked. */
		void signIn(Runnable done);

		/** Opens the "Switch from another client" page. */
		void openImport();
	}

	private enum Step { WELCOME, SIGN_IN, IMPORT, CODE, DONE }

	private final StackPane layers;
	private final Host host;
	private final List<ClientImport.Source> sources;
	private final List<Step> steps = new ArrayList<>();
	private final VBox rail = new VBox(4);
	private final StackPane stage = new StackPane();
	private StackPane scrim;
	private int index;
	private boolean importChosen;
	private String codeNote;

	private OnboardingView(StackPane layers, Host host, List<ClientImport.Source> sources) {
		this.layers = layers;
		this.host = host;
		this.sources = sources;
		steps.add(Step.WELCOME);
		steps.add(Step.SIGN_IN);
		if (!sources.isEmpty()) {
			steps.add(Step.IMPORT);
		}
		steps.add(Step.CODE);
		steps.add(Step.DONE);
	}

	public static void show(StackPane layers, Host host, List<ClientImport.Source> sources) {
		new OnboardingView(layers, host, sources).open();
	}

	/** Dev (screenshot tour): opens on step {@code step} (0-based). */
	public static void showAtStep(StackPane layers, Host host, List<ClientImport.Source> sources, int step) {
		OnboardingView view = new OnboardingView(layers, host, sources);
		view.index = Math.max(0, Math.min(view.steps.size() - 1, step));
		view.open();
	}

	private void open() {
		Label brand = new Label("Velo Client");
		brand.getStyleClass().add("t-heading");
		ImageView logo = new ImageView(new Image(OnboardingView.class.getResourceAsStream("/net/veloclient/launcher/images/logo.png")));
		logo.setFitWidth(26);
		logo.setPreserveRatio(true);
		HBox brandRow = new HBox(10, logo, brand);
		brandRow.setAlignment(Pos.CENTER_LEFT);
		VBox side = new VBox(26, brandRow, rail);
		side.setPadding(new Insets(28, 22, 28, 26));
		side.setMinWidth(220);
		side.setPrefWidth(220);
		side.setStyle("-fx-background-color: rgba(255,255,255,0.025); -fx-background-radius: 20 0 0 20;");

		stage.setPadding(new Insets(36, 40, 30, 40));
		stage.setAlignment(Pos.TOP_LEFT);
		HBox.setHgrow(stage, Priority.ALWAYS);
		HBox card = new HBox(side, stage);
		card.getStyleClass().add("card");
		card.setStyle("-fx-background-radius: 20; -fx-border-radius: 20; -fx-border-color: -velo-hairline-2;"
				+ " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 40, 0.2, 0, 12);");
		card.setMaxSize(860, 520);
		card.setPrefSize(860, 520);

		scrim = new StackPane(card);
		scrim.getStyleClass().add("modal-scrim");
		scrim.setPadding(new Insets(24));
		layers.getChildren().add(scrim);
		render(false);

		scrim.setOpacity(0);
		card.setScaleX(0.95);
		card.setScaleY(0.95);
		FadeTransition fade = new FadeTransition(Duration.millis(240), scrim);
		fade.setToValue(1);
		ScaleTransition grow = new ScaleTransition(Duration.millis(320), card);
		grow.setToX(1);
		grow.setToY(1);
		grow.setInterpolator(UiMotion.EASE_OUT);
		new ParallelTransition(fade, grow).play();
	}

	private void close() {
		FadeTransition out = new FadeTransition(Duration.millis(200), scrim);
		out.setToValue(0);
		out.setOnFinished(e -> layers.getChildren().remove(scrim));
		out.play();
		if (importChosen) {
			host.openImport();
		}
	}

	private void go(int next) {
		index = Math.max(0, Math.min(steps.size() - 1, next));
		render(true);
	}

	private void render(boolean animate) {
		rail.getChildren().clear();
		for (int i = 0; i < steps.size(); i++) {
			boolean done = i < index;
			boolean current = i == index;
			StackPane dot = new StackPane();
			dot.setMinSize(22, 22);
			dot.setMaxSize(22, 22);
			if (done) {
				dot.getChildren().add(Icons.icon("check", 12, Color.WHITE, 2.4));
				dot.setStyle("-fx-background-color: -velo-accent; -fx-background-radius: 11;");
			} else {
				Label number = new Label(String.valueOf(i + 1));
				number.setStyle("-fx-font-size: 11px; -fx-font-family: 'Inter'; -fx-font-weight: bold; -fx-text-fill: "
						+ (current ? "-velo-text" : "-velo-text-3") + ";");
				dot.getChildren().add(number);
				dot.setStyle("-fx-background-radius: 11; -fx-border-radius: 11; -fx-border-color: "
						+ (current ? "-velo-accent" : "-velo-hairline-2") + "; -fx-border-width: 1.5;");
			}
			Label name = new Label(switch (steps.get(i)) {
				case WELCOME -> "Welcome";
				case SIGN_IN -> "Sign in";
				case IMPORT -> "Bring your stuff";
				case CODE -> "Friend's code";
				case DONE -> "Ready";
			});
			name.getStyleClass().add(current ? "t-strong" : "t-muted");
			HBox row = new HBox(12, dot, name);
			row.setAlignment(Pos.CENTER_LEFT);
			row.setPadding(new Insets(7, 0, 7, 0));
			rail.getChildren().add(row);
		}

		Node content = switch (steps.get(index)) {
			case WELCOME -> welcome();
			case SIGN_IN -> signIn();
			case IMPORT -> importStep();
			case CODE -> codeStep();
			case DONE -> done();
		};
		stage.getChildren().setAll(content);
		if (animate) {
			content.setOpacity(0);
			content.setTranslateX(18);
			FadeTransition fade = new FadeTransition(Duration.millis(220), content);
			fade.setToValue(1);
			TranslateTransition slide = new TranslateTransition(Duration.millis(280), content);
			slide.setToX(0);
			slide.setInterpolator(UiMotion.EASE_OUT);
			new ParallelTransition(fade, slide).play();
		}
	}

	// ---- Steps ----

	private Node welcome() {
		VBox features = new VBox(14,
				feature("zap", "Fast out of the box", "Performance mods and a tuned profile, ready to play."),
				feature("wings", "Cosmetics everyone sees", "Wings, capes and auras that show on every Velo player."),
				feature("friends", "Play together", "Friends, parties and one-click joining."));
		Button start = primary("Get started", () -> go(index + 1));
		return page("WELCOME", "Let's get you set up", "Takes about a minute. You can skip anything.", features, footer(null, start));
	}

	private Node signIn() {
		String name = host.accountName();
		Node body;
		Button next;
		if (name != null) {
			Label signed = new Label("Signed in as " + name);
			signed.getStyleClass().add("t-strong");
			HBox row = new HBox(12, Icons.icon("check", 18, Color.web("#3DDC97"), 2.4), signed);
			row.setAlignment(Pos.CENTER_LEFT);
			row.getStyleClass().add("card-2");
			row.setPadding(new Insets(14, 16, 14, 16));
			body = row;
			next = primary("Continue", () -> go(index + 1));
		} else {
			Button microsoft = new Button("Sign in with Microsoft", Icons.icon("user", 15, Color.WHITE));
			microsoft.getStyleClass().addAll("primary-button", "button-lg");
			microsoft.setOnAction(e -> host.signIn(() -> render(false)));
			Label why = new Label("You need a Minecraft account to play. Signing in also turns on friends, cosmetics and Velo Coins.");
			why.getStyleClass().add("t-body");
			why.setWrapText(true);
			body = new VBox(16, why, microsoft);
			next = ghost("Skip for now", () -> go(index + 1));
		}
		return page("ACCOUNT", "Sign in", "Your Microsoft account, the same one the Minecraft Launcher uses.", body, footer(back(), next));
	}

	private Node importStep() {
		Set<String> clients = new LinkedHashSet<>();
		sources.forEach(s -> clients.add(s.client()));
		HBox logos = new HBox(14);
		for (String client : clients.stream().limit(5).toList()) {
			Label caption = new Label(client);
			caption.getStyleClass().add("t-muted");
			VBox box = new VBox(8, ClientImportView.brandLogo(client, 56), caption);
			box.setAlignment(Pos.CENTER);
			logos.getChildren().add(box);
		}
		Label what = new Label("Keybinds, servers, resource packs and mods come along. Nothing in the other client changes.");
		what.getStyleClass().add("t-body");
		what.setWrapText(true);
		Button bring = primary("Bring my stuff over", () -> {
			importChosen = true;
			go(index + 1);
		});
		Button fresh = ghost("Start fresh", () -> {
			importChosen = false;
			go(index + 1);
		});
		HBox buttons = new HBox(8, fresh, bring);
		return page("SWITCH", "Coming from another client?", "We found " + names(new ArrayList<>(clients)) + " on this PC.",
				new VBox(22, logos, what), footer(back(), buttons));
	}

	private Node codeStep() {
		TextField field = new TextField();
		field.setPromptText("Code or friend's name");
		field.setPrefWidth(260);
		field.setStyle("-fx-font-family: 'JetBrains Mono Medium'; -fx-font-size: 15px;");
		Label result = new Label(codeNote == null ? "" : codeNote);
		result.getStyleClass().add("t-muted");
		result.setWrapText(true);
		Button use = primary("Use code", null);
		use.disableProperty().bind(field.textProperty().isEmpty());
		Runnable redeem = () -> {
			String code = field.getText().trim();
			if (code.isEmpty()) {
				return;
			}
			if (host.accountName() == null || !StoreApi.connected()) {
				StoreApi.savePendingReferral(code);
				codeNote = "Saved. It's applied as soon as you sign in.";
				go(index + 1);
				return;
			}
			use.disableProperty().unbind();
			use.setDisable(true);
			result.setText("Checking...");
			CompletableFuture.supplyAsync(() -> {
				try {
					StoreApi.redeemReferral(code);
					return (String) null;
				} catch (StoreApi.StoreError e) {
					if (e.status == 0 || e.status == 401) {
						StoreApi.savePendingReferral(code);
						return "";
					}
					return e.getMessage();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(error -> Platform.runLater(() -> {
				if (error == null || error.isEmpty()) {
					codeNote = error == null ? "Code saved. Your " + 300 + " coins arrive after 2 hours of play."
							: "Saved. It's applied once the Velo server is reachable.";
					go(index + 1);
				} else {
					use.disableProperty().bind(field.textProperty().isEmpty());
					result.setText(error);
				}
			}));
		};
		use.setOnAction(e -> redeem.run());
		field.setOnAction(e -> redeem.run());
		Label how = new Label("A friend invited you? Enter their code and you both get Velo Coins once you've played 2 hours.");
		how.getStyleClass().add("t-body");
		how.setWrapText(true);
		HBox form = new HBox(8, field, use);
		form.setAlignment(Pos.CENTER_LEFT);
		HBox reward = new HBox(8, StoreTiles.coin(18), new Label("300 for you"));
		((Label) reward.getChildren().get(1)).getStyleClass().add("t-gold");
		reward.setAlignment(Pos.CENTER_LEFT);
		return page("INVITED?", "Got a friend's code?", "Only now or in your first 7 days.", new VBox(16, how, reward, form, result),
				footer(back(), ghost("I don't have one", () -> go(index + 1))));
	}

	private Node done() {
		VBox list = new VBox(10);
		String name = host.accountName();
		list.getChildren().add(summary(name != null, name != null ? "Signed in as " + name : "Not signed in - do it any time top right"));
		if (steps.contains(Step.IMPORT)) {
			list.getChildren().add(summary(importChosen, importChosen ? "Import opens next" : "Starting fresh"));
		}
		if (codeNote != null) {
			list.getChildren().add(summary(true, codeNote));
		}
		list.getChildren().add(summary(true, "Your Velo Client profile is ready on the newest version"));
		Button play = primary(importChosen ? "Continue to import" : "Start playing", this::close);
		return page("READY", "You're all set", "Press Play at the bottom whenever you're ready.", list, footer(null, play));
	}

	// ---- Building blocks ----

	private Node page(String overline, String title, String subtitle, Node body, Node footer) {
		Label over = new Label(overline);
		over.getStyleClass().add("t-overline");
		Label heading = new Label(title);
		heading.getStyleClass().add("t-title");
		heading.setWrapText(true);
		Label sub = new Label(subtitle);
		sub.getStyleClass().add("t-muted");
		sub.setWrapText(true);
		Region grow = new Region();
		VBox.setVgrow(grow, Priority.ALWAYS);
		VBox page = new VBox(6, over, heading, sub, body, grow, footer);
		VBox.setMargin(body, new Insets(24, 0, 0, 0));
		return page;
	}

	private Node footer(Node left, Node right) {
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button skipAll = new Button("Skip setup");
		skipAll.getStyleClass().add("icon-button-flat");
		skipAll.setOnAction(e -> {
			importChosen = false;
			close();
		});
		HBox row = new HBox(10);
		row.setAlignment(Pos.CENTER_LEFT);
		if (left != null) {
			row.getChildren().add(left);
		}
		if (steps.get(index) != Step.DONE) {
			row.getChildren().add(skipAll);
		}
		row.getChildren().addAll(spacer, right);
		return row;
	}

	private Button back() {
		Button back = new Button(null, Icons.icon("arrow-left", 16, Color.web("#E4E4E8")));
		back.getStyleClass().add("icon-button");
		back.setOnAction(e -> go(index - 1));
		return back;
	}

	private static Button primary(String text, Runnable action) {
		Button button = new Button(text);
		button.getStyleClass().add("primary-button");
		button.setDefaultButton(true);
		if (action != null) {
			button.setOnAction(e -> action.run());
		}
		return button;
	}

	private static Button ghost(String text, Runnable action) {
		Button button = new Button(text);
		button.getStyleClass().add("ghost-button");
		button.setOnAction(e -> action.run());
		return button;
	}

	private static Node feature(String icon, String title, String text) {
		StackPane badge = new StackPane(Icons.icon(icon, 18, Color.WHITE));
		badge.setMinSize(40, 40);
		badge.setMaxSize(40, 40);
		badge.setStyle("-fx-background-color: -velo-accent-soft; -fx-background-radius: 11;");
		Label name = new Label(title);
		name.getStyleClass().add("t-strong");
		Label detail = new Label(text);
		detail.getStyleClass().add("t-muted");
		detail.setWrapText(true);
		HBox row = new HBox(14, badge, new VBox(2, name, detail));
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private static Node summary(boolean ok, String text) {
		Label label = new Label(text);
		label.getStyleClass().add("t-body");
		label.setWrapText(true);
		HBox row = new HBox(12, Icons.icon(ok ? "check" : "info", 16, Color.web(ok ? "#3DDC97" : "#A9A9B3"), 2.2), label);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private static String names(List<String> clients) {
		if (clients.size() == 1) {
			return clients.get(0);
		}
		return String.join(", ", clients.subList(0, clients.size() - 1)) + " and " + clients.get(clients.size() - 1);
	}
}
