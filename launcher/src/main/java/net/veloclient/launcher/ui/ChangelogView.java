package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.modrinth.ModrinthClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * "What's new" for a mod update: the author's notes for every version between the one you have and the one you're
 * about to install (newest first), so an update says what it changes before you take it. Markdown is shown as plain,
 * tidy text - headings, bullets, links reduced to their words.
 */
final class ChangelogView {

	private ChangelogView() {
	}

	/** Fills itself in the background; {@code installedVersionId} null = only {@code target}'s notes. */
	static Node between(String projectId, String mcVersion, String projectType, String installedVersionId, ModrinthClient.ProjectVersion target) {
		VBox box = new VBox(8);
		box.getStyleClass().add("changelog");
		Label heading = new Label("What's new");
		heading.getStyleClass().add("changelog-heading");
		box.getChildren().addAll(heading, Skeleton.list(2));
		CompletableFuture.supplyAsync(() -> {
			List<ModrinthClient.ProjectVersion> out = new ArrayList<>();
			try {
				List<ModrinthClient.ProjectVersion> all = new ArrayList<>(ModrinthClient.versions(projectId, mcVersion, projectType));
				all.sort(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished).reversed());
				String installedDate = all.stream().filter(v -> v.id().equals(installedVersionId)).map(ModrinthClient.ProjectVersion::datePublished)
						.findFirst().orElse(null);
				for (ModrinthClient.ProjectVersion v : all) {
					boolean notNewerThanTarget = v.datePublished().compareTo(target.datePublished()) <= 0;
					boolean newerThanInstalled = installedDate == null ? v.id().equals(target.id()) : v.datePublished().compareTo(installedDate) > 0;
					if (notNewerThanTarget && newerThanInstalled) {
						out.add(v);
					}
				}
			} catch (Exception e) {
				out.add(target);
			}
			if (out.isEmpty()) {
				out.add(target);
			}
			return out;
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(versions -> Platform.runLater(() -> {
			box.getChildren().setAll(heading);
			if (versions.size() > 1) {
				heading.setText("What's new - " + versions.size() + " versions since yours");
			}
			int shown = 0;
			for (ModrinthClient.ProjectVersion v : versions) {
				if (shown++ >= 8) {
					Label more = new Label("…and " + (versions.size() - 8) + " older versions");
					more.getStyleClass().add("t-muted");
					box.getChildren().add(more);
					break;
				}
				Label title = new Label(v.versionNumber() + "  ·  " + VersionDropdown.age(v.datePublished()));
				title.getStyleClass().add("changelog-version");
				box.getChildren().addAll(title, markdown(v.changelog()));
			}
		}));
		return box;
	}

	/** The first lines of a version's notes, for tooltips. */
	static String preview(String changelog, int maxChars) {
		String text = plain(changelog).strip();
		return text.isEmpty() ? "" : text.length() > maxChars ? text.substring(0, maxChars) + "…" : text;
	}

	static Node markdown(String changelog) {
		VBox lines = new VBox(3);
		String text = changelog == null ? "" : changelog.strip();
		if (text.isEmpty()) {
			Label none = new Label("No notes from the author for this version.");
			none.getStyleClass().add("t-muted");
			lines.getChildren().add(none);
			return lines;
		}
		int count = 0;
		for (String raw : text.split("\\R")) {
			String line = raw.strip();
			if (line.isEmpty() || line.matches("^[-*_]{3,}$")) {
				continue;
			}
			if (count++ > 40) {
				Label cut = new Label("…");
				cut.getStyleClass().add("t-muted");
				lines.getChildren().add(cut);
				break;
			}
			Label label;
			if (line.startsWith("#")) {
				label = new Label(inline(line.replaceFirst("^#+\\s*", "")));
				label.getStyleClass().add("changelog-subheading");
			} else if (line.matches("^([-*+]|\\d+\\.)\\s+.*")) {
				label = new Label("•  " + inline(line.replaceFirst("^([-*+]|\\d+\\.)\\s+", "")));
				label.getStyleClass().add("changelog-line");
			} else {
				label = new Label(inline(line));
				label.getStyleClass().add("changelog-line");
			}
			label.setWrapText(true);
			lines.getChildren().add(label);
		}
		return lines;
	}

	private static String plain(String changelog) {
		if (changelog == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (String line : changelog.split("\\R")) {
			String l = inline(line.strip().replaceFirst("^#+\\s*", "").replaceFirst("^([-*+]|\\d+\\.)\\s+", "• "));
			if (!l.isBlank()) {
				out.append(l).append('\n');
			}
		}
		return out.toString();
	}

	/** Links to their words, no **bold** / `code` / html marks. */
	private static String inline(String s) {
		return s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
				.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
				.replaceAll("<[^>]+>", "")
				.replace("**", "").replace("__", "").replace("`", "")
				.strip();
	}
}
