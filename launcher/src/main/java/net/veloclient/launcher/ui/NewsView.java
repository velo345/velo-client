package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.veloclient.launcher.social.NewsApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The News page: a rotating banner of the newest posts, every post as a card, and the community
 * polls. Owners also get "New post", "New poll" and "Bug reports" here.
 */
public final class NewsView {

	public interface Host {
		Stage owner();

		/** Shows a sub-page (post, editor, reports) in the content area. */
		void show(Node page);

		/** Back to the News list (reloads it). */
		void openNews();

		boolean signedIn();
	}

	private NewsView() {
	}

	/** Remembers what the player has seen, for the sidebar's unread dot. */
	public static void markSeen(JsonObject feed) {
		long newest = newestPublished(feed);
		var settings = net.veloclient.launcher.data.LauncherSettings.get();
		if (newest > settings.newsSeenAt) {
			settings.newsSeenAt = newest;
			net.veloclient.launcher.data.LauncherSettings.save(settings);
		}
	}

	/** Posts published after the player last opened News. */
	public static int unread(JsonObject feed) {
		long seen = net.veloclient.launcher.data.LauncherSettings.get().newsSeenAt;
		int count = 0;
		for (JsonObject post : posts(feed)) {
			if (NewsUi.bool(post, "published") && NewsUi.num(post, "publishedAt") > seen) {
				count++;
			}
		}
		return count;
	}

	private static long newestPublished(JsonObject feed) {
		long newest = 0;
		for (JsonObject post : posts(feed)) {
			if (NewsUi.bool(post, "published")) {
				newest = Math.max(newest, NewsUi.num(post, "publishedAt"));
			}
		}
		return newest;
	}

	static List<JsonObject> posts(JsonObject feed) {
		List<JsonObject> out = new ArrayList<>();
		if (feed != null && feed.has("posts")) {
			for (JsonElement e : feed.getAsJsonArray("posts")) {
				out.add(e.getAsJsonObject());
			}
		}
		return out;
	}

	/** The banner's slides: pinned first, then newest, published only, at most 5. */
	public static List<JsonObject> bannerPosts(JsonObject feed) {
		return posts(feed).stream().filter(p -> NewsUi.bool(p, "published")).limit(5).toList();
	}

	/** Home's compact banner: newest posts rotating, with a "News" label; clicking opens the post. */
	public static Node homeBanner(List<JsonObject> posts, java.util.function.Consumer<JsonObject> onOpen) {
		NewsCarousel carousel = new NewsCarousel(posts, true, onOpen);
		carousel.setPrefSize(300, 158);
		Label label = new Label("NEWS");
		label.getStyleClass().add("news-home-label");
		StackPane.setAlignment(label, Pos.TOP_LEFT);
		StackPane.setMargin(label, new Insets(12, 0, 0, 14));
		label.setMouseTransparent(true);
		StackPane box = new StackPane(carousel, label);
		box.getStyleClass().add("news-home-banner");
		return box;
	}

	/** A post page opened from outside the News list (e.g. the Home banner). */
	public static Node postPage(Host host, JsonObject post, JsonObject feed) {
		return post(host, post, feed != null && NewsUi.bool(feed, "owner"));
	}

	/** Dev-only (launcher tour): the editor opened on a post. */
	public static Node editorForTour(Host host, JsonObject post) {
		return NewsEditorView.build(host, post);
	}

	public static Node build(Host host) {
		StackPane page = new StackPane();
		Label loading = new Label("Loading news...");
		loading.getStyleClass().add("page-subtitle");
		page.getChildren().add(loading);
		CompletableFuture.supplyAsync(() -> {
			try {
				return (Object) NewsApi.feed();
			} catch (NewsApi.NewsError e) {
				return e.getMessage();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result instanceof JsonObject feed) {
				markSeen(feed);
				page.getChildren().setAll(list(host, feed));
			} else {
				Label error = new Label(result + "");
				error.getStyleClass().add("empty-state");
				page.getChildren().setAll(error);
			}
		}));
		return page;
	}

	private static Node list(Host host, JsonObject feed) {
		boolean owner = NewsUi.bool(feed, "owner");
		Label title = new Label("News");
		title.getStyleClass().add("page-title");
		Label sub = new Label("What's new in Velo Client - and your say in what comes next.");
		sub.getStyleClass().add("page-subtitle");
		VBox titles = new VBox(2, title, sub);
		titles.setMinWidth(0);
		HBox.setHgrow(titles, Priority.ALWAYS);
		HBox header = new HBox(8, titles);
		header.setAlignment(Pos.CENTER_LEFT);
		if (owner) {
			Button reports = new Button("Bug reports");
			reports.getStyleClass().add("glass-button");
			reports.setOnAction(e -> host.show(BugReportsView.build(host)));
			Button newPoll = new Button("+  Poll");
			newPoll.getStyleClass().add("glass-button");
			newPoll.setOnAction(e -> PollEditor.open(host, null));
			Button newPost = new Button("+  New post");
			newPost.getStyleClass().add("primary-button");
			newPost.setOnAction(e -> host.show(NewsEditorView.build(host, null)));
			for (Button b : List.of(reports, newPoll, newPost)) {
				b.setMinWidth(Region.USE_PREF_SIZE);
			}
			header.getChildren().addAll(reports, newPoll, newPost);
		}

		VBox root = new VBox(22, header);
		root.setPadding(new Insets(0, 4, 24, 0));
		List<JsonObject> banner = bannerPosts(feed);
		if (!banner.isEmpty()) {
			NewsCarousel carousel = new NewsCarousel(banner, false, p -> host.show(post(host, p, owner)));
			carousel.setPrefHeight(300);
			carousel.setMinHeight(240);
			root.getChildren().add(carousel);
		}

		List<JsonObject> posts = posts(feed);
		JsonArray polls = feed.has("polls") ? feed.getAsJsonArray("polls") : new JsonArray();
		if (posts.isEmpty() && polls.isEmpty()) {
			Label empty = new Label(owner ? "No posts yet - write the first one with \"New post\"." : "No news yet - check back soon.");
			empty.getStyleClass().add("empty-state");
			root.getChildren().add(empty);
		}

		if (!polls.isEmpty()) {
			Label pollsLabel = sectionLabel("Polls");
			FlowPane pollGrid = new FlowPane(14, 14);
			for (JsonElement e : polls) {
				pollGrid.getChildren().add(pollCard(host, e.getAsJsonObject(), owner));
			}
			root.getChildren().addAll(pollsLabel, pollGrid);
		}

		if (!posts.isEmpty()) {
			FlowPane grid = new FlowPane(14, 14);
			for (JsonObject p : posts) {
				grid.getChildren().add(postCard(host, p, owner));
			}
			UiMotion.stagger(grid, 9);
			root.getChildren().addAll(sectionLabel("All posts"), grid);
		}

		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		return scroll;
	}

	private static Label sectionLabel(String text) {
		Label label = new Label(text.toUpperCase(java.util.Locale.ROOT));
		label.getStyleClass().add("news-section-label");
		return label;
	}

	private static Node postCard(Host host, JsonObject post, boolean owner) {
		String accent = NewsUi.str(post, "accent");
		StackPane cover = NewsUi.cover(NewsUi.str(post, "cover"), accent, NewsUi.str(post, "tag"), 14);
		cover.setPrefSize(268, 140);
		cover.setMaxSize(268, 140);
		if (!NewsUi.bool(post, "published")) {
			Label draft = new Label("DRAFT");
			draft.getStyleClass().add("news-draft-badge");
			StackPane.setAlignment(draft, Pos.TOP_LEFT);
			StackPane.setMargin(draft, new Insets(10));
			cover.getChildren().add(draft);
		} else if (NewsUi.bool(post, "pinned")) {
			Label pinned = new Label("PINNED");
			pinned.getStyleClass().add("news-draft-badge");
			StackPane.setAlignment(pinned, Pos.TOP_LEFT);
			StackPane.setMargin(pinned, new Insets(10));
			cover.getChildren().add(pinned);
		}
		Label tag = NewsUi.tag(NewsUi.str(post, "tag"), accent);
		Label when = new Label(NewsUi.date(NewsUi.bool(post, "published") ? NewsUi.num(post, "publishedAt") : 0));
		when.getStyleClass().add("news-card-date");
		HBox meta = new HBox(8, tag, when);
		meta.setAlignment(Pos.CENTER_LEFT);
		Label title = new Label(NewsUi.str(post, "title"));
		title.getStyleClass().add("news-card-title");
		title.setWrapText(true);
		Label summary = new Label(NewsUi.str(post, "summary"));
		summary.getStyleClass().add("news-card-summary");
		summary.setWrapText(true);
		summary.setMaxHeight(52);
		VBox card = new VBox(10, cover, meta, title, summary);
		card.getStyleClass().addAll("news-card", "motion-card");
		card.setPrefWidth(290);
		card.setMaxWidth(290);
		card.setOnMouseClicked(e -> host.show(post(host, post, owner)));
		return card;
	}

	/** A post on its own page: cover, tag/date, title, summary and the body blocks. */
	static Node post(Host host, JsonObject post, boolean owner) {
		Button back = new Button("←  News");
		back.getStyleClass().add("ghost-button");
		back.setOnAction(e -> host.openNews());
		HBox top = new HBox(8, back);
		top.setAlignment(Pos.CENTER_LEFT);
		if (owner) {
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			Button edit = new Button("Edit");
			edit.getStyleClass().add("glass-button");
			edit.setOnAction(e -> host.show(NewsEditorView.build(host, post)));
			Button delete = new Button("Delete");
			delete.getStyleClass().add("danger-ghost-button");
			delete.setOnAction(e -> {
				if (Confirm.ask(host.owner(), "Delete \"" + NewsUi.str(post, "title") + "\"?", "Players won't see it anymore. This can't be undone.")) {
					run(() -> {
						NewsApi.deletePost(NewsUi.str(post, "id"));
						return null;
					}, r -> host.openNews(), err -> { });
				}
			});
			top.getChildren().addAll(spacer, edit, delete);
		}
		ScrollPane scroll = new ScrollPane(article(post));
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		return new VBox(14, top, scroll);
	}

	/** The readable part of a post (also the editor's live preview). */
	static Node article(JsonObject post) {
		String accent = NewsUi.str(post, "accent");
		StackPane cover = NewsUi.cover(NewsUi.str(post, "cover"), accent, NewsUi.str(post, "tag"), 20);
		cover.setPrefHeight(260);
		cover.setMinHeight(200);
		Label tag = NewsUi.tag(NewsUi.str(post, "tag"), accent);
		HBox meta = new HBox(8, tag);
		meta.setAlignment(Pos.CENTER_LEFT);
		String version = NewsUi.str(post, "version");
		if (version != null && !version.isBlank()) {
			Label v = new Label("v" + version.replaceFirst("^v", ""));
			v.getStyleClass().add("news-version");
			meta.getChildren().add(v);
		}
		String author = NewsUi.str(post, "author");
		Label when = new Label(NewsUi.date(NewsUi.bool(post, "published") ? NewsUi.num(post, "publishedAt") : 0)
				+ (author != null && !author.isBlank() ? "  ·  by " + author : ""));
		when.getStyleClass().add("news-card-date");
		meta.getChildren().add(when);
		Label title = new Label(NewsUi.str(post, "title") == null || NewsUi.str(post, "title").isBlank() ? "Untitled post" : NewsUi.str(post, "title"));
		title.getStyleClass().add("news-article-title");
		title.setWrapText(true);
		VBox text = new VBox(10, meta, title);
		String summary = NewsUi.str(post, "summary");
		if (summary != null && !summary.isBlank()) {
			Label sub = new Label(summary);
			sub.getStyleClass().add("news-article-summary");
			sub.setWrapText(true);
			text.getChildren().add(sub);
		}
		VBox body = NewsUi.blocks(post.has("blocks") && post.get("blocks").isJsonArray() ? post.getAsJsonArray("blocks") : null, accent);
		VBox column = new VBox(22, text, body);
		column.setMaxWidth(760);
		column.setPadding(new Insets(4, 8, 30, 8));
		VBox article = new VBox(22, cover, column);
		article.setAlignment(Pos.TOP_CENTER);
		return article;
	}

	// ---- Polls ----

	/** A poll: answer buttons until you vote, then animated result bars (with your choice ticked). */
	static Node pollCard(Host host, JsonObject poll, boolean owner) {
		VBox card = new VBox(12);
		card.getStyleClass().add("poll-card");
		card.setPrefWidth(420);
		card.setMaxWidth(420);
		fillPoll(host, card, poll, owner, false);
		return card;
	}

	private static void fillPoll(Host host, VBox card, JsonObject poll, boolean owner, boolean animate) {
		card.getChildren().clear();
		boolean closed = NewsUi.bool(poll, "closed");
		boolean multi = NewsUi.bool(poll, "multi");
		boolean visible = NewsUi.bool(poll, "resultsVisible");
		List<Integer> mine = new ArrayList<>();
		if (poll.has("myVote") && poll.get("myVote").isJsonArray()) {
			poll.getAsJsonArray("myVote").forEach(e -> mine.add(e.getAsInt()));
		}
		boolean voted = !mine.isEmpty();

		Label badge = new Label(closed ? "ENDED" : "POLL");
		badge.getStyleClass().addAll("news-tag", closed ? "poll-badge-closed" : "poll-badge-open");
		long closesAt = NewsUi.num(poll, "closesAt");
		String timing = closed ? "Final results" : closesAt > 0 ? "Ends " + endsIn(closesAt) : multi ? "Pick one or more" : "Pick one";
		Label meta = new Label(timing + (visible ? "  ·  " + NewsUi.num(poll, "totalVotes") + " votes" : ""));
		meta.getStyleClass().add("news-card-date");
		HBox top = new HBox(8, badge, meta);
		top.setAlignment(Pos.CENTER_LEFT);
		if (owner) {
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			Button edit = new Button("Edit");
			edit.getStyleClass().add("mini-button");
			edit.setOnAction(e -> PollEditor.open(host, poll));
			top.getChildren().addAll(spacer, edit);
		}
		Label question = new Label(NewsUi.str(poll, "question"));
		question.getStyleClass().add("poll-question");
		question.setWrapText(true);
		card.getChildren().addAll(top, question);
		String description = NewsUi.str(poll, "description");
		if (description != null && !description.isBlank()) {
			card.getChildren().add(NewsUi.richText(description, "news-card-summary-flow"));
		}
		String image = NewsUi.str(poll, "image");
		if (image != null) {
			StackPane cover = NewsUi.cover(image, null, "poll", 12);
			cover.setPrefHeight(150);
			card.getChildren().add(cover);
		}

		Label status = new Label();
		status.getStyleClass().add("news-card-date");
		JsonArray options = poll.getAsJsonArray("options");
		long total = Math.max(1, NewsUi.num(poll, "totalVotes"));
		List<Integer> picked = new ArrayList<>(mine);
		VBox rows = new VBox(8);
		boolean showResults = visible && (voted || closed || owner);
		Button submit = new Button(voted ? "Update vote" : "Vote");
		submit.getStyleClass().add("primary-button");
		for (int i = 0; i < options.size(); i++) {
			JsonObject option = options.get(i).getAsJsonObject();
			int index = i;
			String text = NewsUi.str(option, "text");
			if (showResults && !(multi && !closed && !voted && !owner)) {
				long votes = NewsUi.num(option, "votes");
				double share = votes / (double) total;
				Region fill = new Region();
				fill.getStyleClass().add(mine.contains(i) ? "poll-bar-fill-mine" : "poll-bar-fill");
				fill.setMaxWidth(Region.USE_PREF_SIZE);
				Label label = new Label((mine.contains(i) ? "✓  " : "") + text);
				label.getStyleClass().add("poll-option-text");
				Label percent = new Label(Math.round(share * 100) + "%");
				percent.getStyleClass().add("poll-option-percent");
				Region spacer = new Region();
				HBox.setHgrow(spacer, Priority.ALWAYS);
				HBox labels = new HBox(label, spacer, percent);
				labels.setAlignment(Pos.CENTER_LEFT);
				labels.setPadding(new Insets(0, 12, 0, 12));
				StackPane bar = new StackPane(fill, labels);
				bar.getStyleClass().add("poll-bar");
				StackPane.setAlignment(fill, Pos.CENTER_LEFT);
				bar.widthProperty().addListener((o, a, w) -> {
					if (!animate) {
						fill.setPrefWidth(w.doubleValue() * share);
					}
				});
				if (animate) {
					fill.setPrefWidth(0);
					Timeline grow = new Timeline(new KeyFrame(Duration.millis(650), new KeyValue(fill.prefWidthProperty(), 400 * share, UiMotion.EASE_OUT)));
					bar.widthProperty().addListener((o, a, w) -> grow.getKeyFrames().setAll(
							new KeyFrame(Duration.millis(650), new KeyValue(fill.prefWidthProperty(), w.doubleValue() * share, UiMotion.EASE_OUT))));
					Platform.runLater(grow::play);
				}
				if (!closed && !owner && voted) {
					bar.setOnMouseClicked(e -> {
						// Clicking a result row starts changing your vote.
						JsonObject copy = poll.deepCopy();
						copy.addProperty("resultsVisible", false);
						copy.add("myVote", new JsonArray());
						fillPoll(host, card, copy, false, false);
					});
					bar.setCursor(javafx.scene.Cursor.HAND);
				}
				rows.getChildren().add(bar);
			} else {
				Button choice = new Button(text);
				choice.getStyleClass().add(picked.contains(i) ? "poll-option-picked" : "poll-option");
				choice.setMaxWidth(Double.MAX_VALUE);
				choice.setAlignment(Pos.CENTER_LEFT);
				choice.setDisable(closed);
				choice.setOnAction(e -> {
					if (multi) {
						if (picked.contains(index)) {
							picked.remove(Integer.valueOf(index));
						} else {
							picked.add(index);
						}
						choice.getStyleClass().setAll("button", picked.contains(index) ? "poll-option-picked" : "poll-option");
					} else {
						picked.clear();
						picked.add(index);
						vote(host, card, poll, picked, status);
					}
				});
				rows.getChildren().add(choice);
			}
		}
		card.getChildren().add(rows);
		if (multi && !closed && !owner && !(showResults && voted)) {
			submit.setOnAction(e -> {
				if (picked.isEmpty()) {
					status.setText("Pick at least one answer.");
					return;
				}
				vote(host, card, poll, picked, status);
			});
			card.getChildren().add(submit);
		}
		if (voted && !closed && showResults && !owner) {
			status.setText("Thanks for voting! Click an answer to change your vote.");
		}
		card.getChildren().add(status);
	}

	private static void vote(Host host, VBox card, JsonObject poll, List<Integer> picked, Label status) {
		if (!host.signedIn()) {
			status.setText("Sign in to vote.");
			return;
		}
		status.setText("Sending your vote...");
		List<Integer> choice = List.copyOf(picked);
		run(() -> NewsApi.vote(NewsUi.str(poll, "id"), choice), updated -> fillPoll(host, card, updated, false, true),
				status::setText);
	}

	private static String endsIn(long millis) {
		long left = millis - System.currentTimeMillis();
		if (left <= 0) {
			return "soon";
		}
		long hours = left / 3_600_000L;
		if (hours < 1) {
			return "in " + Math.max(1, left / 60_000L) + " min";
		}
		if (hours < 48) {
			return "in " + hours + " h";
		}
		return "in " + hours / 24 + " days";
	}

	// ---- Async helper ----

	@FunctionalInterface
	interface Call<T> {
		T run() throws Exception;
	}

	static <T> void run(Call<T> call, java.util.function.Consumer<T> onOk, java.util.function.Consumer<String> onError) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return (Object) call.run();
			} catch (Exception e) {
				return e;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result instanceof Exception e) {
				onError.accept(e.getMessage() != null ? e.getMessage() : "Something went wrong");
			} else {
				@SuppressWarnings("unchecked")
				T value = (T) result;
				onOk.accept(value);
			}
		}));
	}
}
