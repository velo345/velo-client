package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import net.veloclient.launcher.data.AppliedModpack;
import net.veloclient.launcher.data.AppliedModpackStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.modpack.ModpackInstaller;
import net.veloclient.launcher.modrinth.ModrinthClient;
import net.veloclient.launcher.theme.LauncherTheme;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The "Modpacks" profile tab: shows whichever modpack is currently applied
 * (if any - see {@link AppliedModpackStore}) plus a Modrinth browse/search
 * view to apply a different one. Deliberately not modeled on {@link
 * InstanceDetailView}'s Mods tab (an add/remove file list) - a modpack isn't
 * a single file you can list, it's an entire loadout applied over the
 * profile's game directory via {@link ModpackInstaller}, so "installed"
 * here means "currently applied", singular.
 */
public final class ModpacksTabView {

	private ModpacksTabView() {
	}

	public static Node build(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme) {
		StackPane container = new StackPane();
		container.setPadding(new Insets(14, 4, 4, 4));

		Runnable[] showStatus = new Runnable[1];
		Runnable[] showBrowse = new Runnable[1];
		showStatus[0] = () -> container.getChildren().setAll(buildStatusView(owner, overlayHost, instance, theme, showStatus[0], showBrowse[0]));
		showBrowse[0] = () -> container.getChildren().setAll(buildBrowseView(owner, overlayHost, instance, theme, showStatus[0]));

		showStatus[0].run();
		return container;
	}

	private static Node buildStatusView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, Runnable refresh, Runnable openBrowse) {
		VBox root = new VBox(14);

		Optional<AppliedModpack> applied = AppliedModpackStore.load(instance.id());
		if (applied.isPresent()) {
			AppliedModpack modpack = applied.get();
			HBox card = new HBox(14);
			card.getStyleClass().add("mod-row");
			card.setAlignment(Pos.CENTER_LEFT);
			card.getChildren().add(InstanceDetailView.iconView(modpack.iconUrl(), 56));

			VBox info = new VBox(4);
			Label title = new Label(modpack.title());
			title.setFont(Font.font("System", FontWeight.BOLD, 15));
			title.setTextFill(InstanceDetailView.text(theme));
			Label version = new Label("Currently applied - v" + modpack.versionNumber());
			version.getStyleClass().add("version-tag");
			version.setTextFill(InstanceDetailView.text(theme));
			info.getChildren().addAll(title, version);
			HBox.setHgrow(info, Priority.ALWAYS);
			card.getChildren().add(info);

			Button change = new Button("Change Modpack...");
			change.getStyleClass().addAll("title-menu-button", "title-menu-button-primary", "button-compact");
			change.setOnAction(e -> openBrowse.run());
			card.getChildren().add(change);

			root.getChildren().add(card);
			root.getChildren().add(sectionSubtitleLike(theme,
					"Installing a different modpack (or updating this one) overwrites this profile's mods and config with the new pack's own."));
		} else {
			root.getChildren().add(sectionSubtitleLike(theme,
					"No modpack applied to this profile yet. Browsing below installs one - it fully replaces this profile's current mods and config with the pack's own, so this is best done on a fresh profile."));
			Button browse = new Button("Browse Modpacks");
			browse.getStyleClass().addAll("title-menu-button", "title-menu-button-primary");
			browse.setOnAction(e -> openBrowse.run());
			root.getChildren().add(browse);
		}

		return root;
	}

	private static Label sectionSubtitleLike(LauncherTheme theme, String text) {
		Label label = new Label(text);
		label.getStyleClass().add("section-subtitle");
		label.setTextFill(InstanceDetailView.text(theme));
		label.setWrapText(true);
		return label;
	}

	// ---- Browse ----

	private static Node buildBrowseView(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme, Runnable onBack) {
		VBox root = new VBox(14);
		VBox.setVgrow(root, Priority.ALWAYS);

		HBox topRow = new HBox(10);
		topRow.setAlignment(Pos.CENTER_LEFT);
		Button back = new Button("< Back");
		back.setOnAction(e -> onBack.run());
		Label heading = new Label("Browse modpacks for Minecraft " + instance.mcVersion());
		heading.setFont(Font.font("System", FontWeight.BOLD, 15));
		heading.setTextFill(InstanceDetailView.text(theme));
		topRow.getChildren().addAll(back, heading);
		root.getChildren().add(topRow);

		HBox searchRow = new HBox(8);
		TextField query = new TextField();
		query.setPromptText("Search modpacks...");
		HBox.setHgrow(query, Priority.ALWAYS);
		ComboBox<String> sort = new ComboBox<>();
		sort.getItems().addAll("Relevance", "Downloads", "Follows", "Newest", "Recently updated");
		sort.setValue("Relevance");
		Button searchButton = new Button("Search");
		searchRow.getChildren().addAll(query, sort, searchButton);
		root.getChildren().add(searchRow);

		FlowPane results = new FlowPane(14, 14);
		Button loadMore = new Button("Load More");
		loadMore.getStyleClass().add("button-compact");
		loadMore.setMaxWidth(Double.MAX_VALUE);
		loadMore.setVisible(false);
		loadMore.setManaged(false);
		VBox resultsColumn = new VBox(14, results, loadMore);
		ScrollPane resultsScroll = new ScrollPane(resultsColumn);
		resultsScroll.setFitToWidth(true);
		resultsScroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(resultsScroll, Priority.ALWAYS);
		root.getChildren().add(resultsScroll);

		int[] offset = {0};
		int pageSize = 30;

		Runnable[] loadPage = new Runnable[1];
		loadPage[0] = () -> {
			boolean firstPage = offset[0] == 0;
			loadMore.setDisable(true);
			String sortIndex = switch (sort.getValue()) {
				case "Downloads" -> "downloads";
				case "Follows" -> "follows";
				case "Newest" -> "newest";
				case "Recently updated" -> "updated";
				default -> "relevance";
			};
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.search(query.getText(), "modpack", instance.mcVersion(), sortIndex, offset[0], pageSize);
				} catch (IOException e) {
					return e;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				loadMore.setDisable(false);
				if (result instanceof Exception ex) {
					if (firstPage) {
						results.getChildren().setAll(InstanceDetailView.messageLabel("Search failed: " + ex.getMessage(), theme));
					}
					loadMore.setVisible(false);
					loadMore.setManaged(false);
					return;
				}
				@SuppressWarnings("unchecked")
				ModrinthClient.SearchResult search = (ModrinthClient.SearchResult) result;
				if (firstPage) {
					results.getChildren().clear();
					if (search.hits().isEmpty()) {
						results.getChildren().add(InstanceDetailView.messageLabel("No results.", theme));
						loadMore.setVisible(false);
						loadMore.setManaged(false);
						return;
					}
				}
				for (ModrinthClient.SearchHit hit : search.hits()) {
					results.getChildren().add(buildModpackCard(owner, overlayHost, instance, theme, hit, onBack));
				}
				offset[0] += search.hits().size();
				boolean hasMore = !search.hits().isEmpty() && offset[0] < search.totalHits();
				loadMore.setVisible(hasMore);
				loadMore.setManaged(hasMore);
			}));
		};

		Runnable runSearch = () -> {
			offset[0] = 0;
			results.getChildren().setAll(InstanceDetailView.loadingLabel(theme));
			loadMore.setVisible(false);
			loadMore.setManaged(false);
			loadPage[0].run();
		};
		loadMore.setOnAction(e -> loadPage[0].run());
		searchButton.setOnAction(e -> runSearch.run());
		query.setOnAction(e -> runSearch.run());
		sort.setOnAction(e -> runSearch.run());
		runSearch.run();

		return root;
	}

	private static Node buildModpackCard(Stage owner, StackPane overlayHost, Instance instance, LauncherTheme theme,
			ModrinthClient.SearchHit hit, Runnable afterApply) {
		VBox card = new VBox(8);
		card.getStyleClass().add("search-card");
		card.setPrefWidth(190);
		card.setMinHeight(230);
		card.setMaxHeight(230);
		card.setAlignment(Pos.TOP_CENTER);

		card.getChildren().add(InstanceDetailView.iconView(hit.iconUrl(), 64));

		Label title = new Label(hit.title());
		title.setFont(Font.font("System", FontWeight.BOLD, 13));
		title.setTextFill(InstanceDetailView.text(theme));
		title.setWrapText(true);
		title.setAlignment(Pos.CENTER);
		title.setMaxWidth(170);

		Label downloads = new Label(InstanceDetailView.formatCount(hit.downloads()) + " downloads");
		downloads.getStyleClass().add("version-tag");
		downloads.setTextFill(InstanceDetailView.text(theme));

		VBox spacer = new VBox();
		VBox.setVgrow(spacer, Priority.ALWAYS);

		ProgressBar progress = new ProgressBar(0);
		progress.setMaxWidth(Double.MAX_VALUE);
		progress.setVisible(false);
		progress.setManaged(false);

		Button install = new Button("Apply...");
		install.getStyleClass().add("button-compact");
		install.setMaxWidth(Double.MAX_VALUE);
		install.setOnAction(e -> {
			install.setDisable(true);
			CompletableFuture.supplyAsync(() -> {
				try {
					return ModrinthClient.versions(hit.projectId(), instance.mcVersion(), "modpack");
				} catch (IOException ex) {
					return ex;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				if (result instanceof Exception ex) {
					install.setDisable(false);
					InstanceDetailView.error(owner, "Couldn't load versions", ex.getMessage());
					return;
				}
				@SuppressWarnings("unchecked")
				List<ModrinthClient.ProjectVersion> versions = (List<ModrinthClient.ProjectVersion>) result;
				if (versions.isEmpty()) {
					install.setDisable(false);
					InstanceDetailView.error(owner, "No compatible version", hit.title() + " has no version published for Minecraft " + instance.mcVersion() + " on Fabric.");
					return;
				}
				InstanceDetailView.showVersionPicker(overlayHost, theme, instance, hit.title(), versions, plan -> {
					if (!confirmOverwrite(owner, hit.title())) {
						install.setDisable(false);
						return;
					}
					applyModpack(owner, instance, hit, plan.version(), progress, () -> {
						install.setText("Applied");
						afterApply.run();
					}, () -> install.setDisable(false));
				});
			}));
		});

		card.getChildren().addAll(title, downloads, spacer, progress, install);
		return card;
	}

	private static boolean confirmOverwrite(Stage owner, String packName) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(owner);
		alert.setTitle("Apply " + packName + "?");
		alert.setHeaderText("This replaces this profile's mods and config");
		alert.setContentText("Applying \"" + packName + "\" overwrites this profile's current mods folder and any config files the pack ships with its own. This can't be undone automatically. Continue?");
		DialogStyling.apply(alert);
		Optional<ButtonType> result = alert.showAndWait();
		return result.isPresent() && result.get() == ButtonType.OK;
	}

	private static void applyModpack(Stage owner, Instance instance, ModrinthClient.SearchHit hit, ModrinthClient.ProjectVersion version,
			ProgressBar progress, Runnable onSuccess, Runnable onFailure) {
		progress.setProgress(0);
		progress.setVisible(true);
		progress.setManaged(true);
		CompletableFuture.runAsync(() -> {
			try {
				ModpackInstaller.apply(instance.id(), version, fraction -> Platform.runLater(() -> progress.setProgress(fraction)));
				AppliedModpackStore.save(instance.id(), new AppliedModpack(hit.projectId(), version.id(), hit.title(), version.versionNumber(), hit.iconUrl()));
				Platform.runLater(() -> {
					progress.setVisible(false);
					progress.setManaged(false);
					onSuccess.run();
				});
			} catch (IOException e) {
				Platform.runLater(() -> {
					progress.setVisible(false);
					progress.setManaged(false);
					InstanceDetailView.error(owner, "Couldn't apply modpack", e.getMessage());
					onFailure.run();
				});
			}
		}, Executors.newVirtualThreadPerTaskExecutor());
	}
}
