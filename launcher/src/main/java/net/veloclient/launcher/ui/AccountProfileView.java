package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.SkinFetcher;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.SavedServerStore;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.theme.LauncherTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The account badge's destination when signed in: everything about the
 * current session in one place (identity, session status, quick stats about
 * what's set up locally) plus Sign Out - replacing the old behavior where
 * clicking the badge signed you straight out with no confirmation or detail.
 */
public final class AccountProfileView {

	public interface Host {
		javafx.stage.Stage owner();

		LauncherTheme theme();

		MinecraftSession session();

		void signOut();

		void goBack();
	}

	private AccountProfileView() {
	}

	public static Node build(Host host) {
		LauncherTheme theme = host.theme();
		MinecraftSession session = host.session();
		Color text = text(theme);
		Color accent = accent(theme);

		VBox root = new VBox(18);

		Button back = new Button("< Back");
		back.setOnAction(e -> host.goBack());
		root.getChildren().add(back);

		HBox header = new HBox(24);
		header.setAlignment(Pos.TOP_LEFT);

		StackPane skinHolder = new StackPane();
		skinHolder.setPrefSize(220, 300);
		skinHolder.setMinSize(220, 300);
		skinHolder.setAlignment(Pos.CENTER);
		skinHolder.getStyleClass().add("glass-panel");
		Label loading = new Label("Loading skin...");
		loading.getStyleClass().add("section-subtitle");
		loading.setTextFill(text);
		skinHolder.getChildren().add(loading);
		load3DSkinAsync(session, skinHolder);

		VBox identity = new VBox(4);
		HBox nameRow = new HBox(10);
		nameRow.setAlignment(Pos.CENTER_LEFT);
		StackPane headHolder = new StackPane();
		headHolder.setPrefSize(40, 40);
		headHolder.setMinSize(40, 40);
		headHolder.getStyleClass().add("instance-icon-custom");
		Label placeholder = new Label(session.username().substring(0, 1).toUpperCase());
		placeholder.setFont(Font.font("System", FontWeight.BOLD, 18));
		placeholder.setTextFill(Color.WHITE);
		headHolder.getChildren().add(placeholder);
		loadHeadAsync(session, headHolder);
		Label name = new Label(session.username());
		name.setFont(Font.font("System", FontWeight.BOLD, 24));
		name.setTextFill(text);
		nameRow.getChildren().addAll(headHolder, name);

		Label uuid = new Label(session.uuid());
		uuid.getStyleClass().add("version-tag");
		uuid.setTextFill(text);
		Label accountType = new Label("Microsoft account · Minecraft: Java Edition");
		accountType.getStyleClass().add("section-subtitle");
		accountType.setTextFill(text);
		Label dragHint = new Label("Drag the model to rotate it, scroll to zoom.");
		dragHint.getStyleClass().add("version-tag");
		dragHint.setTextFill(text);
		identity.getChildren().addAll(nameRow, uuid, accountType, dragHint);

		header.getChildren().addAll(skinHolder, identity);
		HBox.setHgrow(identity, Priority.ALWAYS);
		root.getChildren().add(header);

		VBox sessionBox = new VBox(6);
		sessionBox.getStyleClass().add("glass-panel");
		Label sessionTitle = new Label("Session");
		sessionTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
		sessionTitle.setTextFill(accent);
		String expiry = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm").withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(session.accessTokenExpiresAtEpochMillis()));
		Label status = new Label(session.isAccessTokenExpired()
				? "Access token expired - will silently refresh next time it's needed."
				: "Access token valid until " + expiry + ".");
		status.setTextFill(text);
		status.setWrapText(true);
		sessionBox.getChildren().addAll(sessionTitle, status);
		root.getChildren().add(sessionBox);

		VBox statsBox = new VBox(6);
		statsBox.getStyleClass().add("glass-panel");
		Label statsTitle = new Label("On this computer");
		statsTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
		statsTitle.setTextFill(accent);
		int instanceCount = InstanceStore.loadAll().size();
		int serverCount = SavedServerStore.loadAll().size();
		String equippedCapeName = CapeLibrary.equippedCapeId()
				.flatMap(id -> CapeLibrary.listAll().stream().filter(c -> c.id().equals(id)).findFirst())
				.map(CapeEntry::name).orElse("None equipped");
		statsBox.getChildren().addAll(statsTitle,
				statLine("Mod profiles", instanceCount + (instanceCount == 1 ? " profile" : " profiles"), text),
				statLine("Saved servers", serverCount + (serverCount == 1 ? " server" : " servers"), text),
				statLine("Active theme", theme.name(), text),
				statLine("Equipped cape", equippedCapeName, text));
		root.getChildren().add(statsBox);

		VBox spacer = new VBox();
		VBox.setVgrow(spacer, Priority.ALWAYS);
		root.getChildren().add(spacer);

		Button signOut = new Button("Sign Out");
		signOut.getStyleClass().add("title-menu-button");
		signOut.setOnAction(e -> {
			Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
			confirm.initOwner(host.owner());
			confirm.setTitle("Sign Out");
			confirm.setHeaderText("Sign out of " + session.username() + "?");
			DialogStyling.apply(confirm);
			confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> host.signOut());
		});
		root.getChildren().add(signOut);

		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		return scroll;
	}

	private static void loadHeadAsync(MinecraftSession session, StackPane headHolder) {
		CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(session), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(skin -> Platform.runLater(() -> {
					StackPane head = skin == null ? null : PlayerHeadView.build(skin.pngBytes(), 40);
					if (head != null) {
						headHolder.getChildren().setAll(head.getChildren());
					}
				}));
	}

	private static void load3DSkinAsync(MinecraftSession session, StackPane skinHolder) {
		CompletableFuture.supplyAsync(() -> SkinFetcher.fetch(session), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(skin -> Platform.runLater(() -> {
					Node viewer = skin == null ? null : PlayerSkin3DView.createViewer(skin.pngBytes(), skin.slim());
					if (viewer != null) {
						skinHolder.getChildren().setAll(viewer);
					} else {
						Label failed = new Label("Couldn't load skin.");
						failed.getStyleClass().add("section-subtitle");
						skinHolder.getChildren().setAll(failed);
					}
				}));
	}

	private static HBox statLine(String label, String value, Color text) {
		Label l = new Label(label);
		l.setTextFill(text);
		l.setOpacity(0.75);
		l.setPrefWidth(140);
		Label v = new Label(value);
		v.setTextFill(text);
		v.setFont(Font.font("System", FontWeight.BOLD, 13));
		HBox row = new HBox(10, l, v);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
