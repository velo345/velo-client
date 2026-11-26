package net.veloclient.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateAppliedTagsEvent;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateNameEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The feedback forums (suggestions, bug reports, tester feedback) in YouTrack (project VELO): every post becomes an
 * issue with its pictures/videos, every reply a comment, title / first-message edits and the forum tags (area, type,
 * Done / Declined / In Progress) follow. Back from YouTrack: a state change sets the forum's status tag and says so in
 * the thread, and the team's public comments are posted there as "Velo Team". The pinned guideline posts are skipped.
 * Which thread is which issue: bot-data/forums-youtrack.json.
 */
final class ForumSync extends ListenerAdapter {

	/** What a forum is: its YouTrack type (unless a forum tag says otherwise) and the tag it gets. */
	private record Forum(String name, String type, String tag) {
	}

	private static final Map<String, Forum> FORUMS = Map.of(
			"1558463800066441266", new Forum("Suggestions", "Suggestion", "discord-suggestion"),
			"1558468396159471636", new Forum("Bug reports", "Bug", "discord-bug"),
			"1574121277872931086", new Forum("Tester feedback", "Bug", "tester"));

	/** Comments the bot wrote from Discord start with this (so they aren't sent back). */
	private static final String FROM_DISCORD = "💬 ";
	private static final String FROM_TESTER = "🧪 ";
	/** YouTrack logins whose public comments are posted to Discord (YOUTRACK_DISCORD_POSTERS, comma-separated). */
	private static final Set<String> POSTERS = Set.of(System.getenv().getOrDefault("YOUTRACK_DISCORD_POSTERS", "admin").split("\\s*,\\s*"));

	private static final class Link {
		String issue;
		String forum;
		String state;
		/** Discord messages already in YouTrack */
		Set<String> messages = new LinkedHashSet<>();
		/** YouTrack comments already in Discord (or written by the bot) */
		Set<String> comments = new LinkedHashSet<>();
		/** Discord attachments already uploaded (a failed one is tried again on the next pass) */
		Set<String> files = new LinkedHashSet<>();
		boolean filesPending;
		boolean tagsPending;
		/** Discord reply -> its YouTrack comment (edits / deletes follow) */
		Map<String, String> replyComments = new LinkedHashMap<>();
		/** the owner's YouTrack comment -> its Discord message, and the text it was posted with */
		Map<String, String> postedMessages = new LinkedHashMap<>();
		Map<String, Integer> postedText = new LinkedHashMap<>();
		/** the title both sides last agreed on */
		String title;
		boolean deleted;
	}

	private static final class Store {
		Map<String, Link> threads = new LinkedHashMap<>();
		/** 2 = forum tags synced and files re-checked for the posts imported before that worked */
		int version;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final JDA jda;
	private final YouTrackClient yt;
	private final String guildId;
	private final Path file;
	private Store store = new Store();
	private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "velo-forum-sync");
		t.setDaemon(true);
		return t;
	});

	ForumSync(JDA jda, YouTrackClient yt, String guildId, Path dataDir) {
		this.jda = jda;
		this.yt = yt;
		this.guildId = guildId;
		this.file = dataDir.resolve("forums-youtrack.json");
		try {
			if (Files.exists(file)) {
				Store loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Store.class);
				if (loaded != null && loaded.threads != null) {
					store = loaded;
					for (Link l : store.threads.values()) {
						if (l.files == null) {
							l.files = new LinkedHashSet<>();
						}
						if (l.replyComments == null) {
							l.replyComments = new LinkedHashMap<>();
						}
						if (l.postedMessages == null) {
							l.postedMessages = new LinkedHashMap<>();
						}
						if (l.postedText == null) {
							l.postedText = new LinkedHashMap<>();
						}
						if (store.version < 2) {
							l.filesPending = true;   // imported before files / forum tags worked: once more
							l.tagsPending = true;
						}
					}
					store.version = 2;
				}
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[forums] couldn't read " + file + ": " + e.getMessage());
		}
	}

	/** Imports every existing post once, then every 2 minutes: missing posts and what changed in YouTrack. */
	private int rounds;
	private int pulls;
	private long lastPull;

	void start() {
		Thread.ofVirtual().name("velo-forum-poll").start(() -> {
			try {
				Thread.sleep(20_000);
			} catch (InterruptedException e) {
				return;
			}
			while (true) {
				// every minute what changed in YouTrack (one small request); every 15 minutes a full pass over the forums
				// (missed posts, files) - kept light on purpose, the box is shared (owner 2026-11-25)
				if (rounds++ % 15 == 0) {
					queue.submit(this::backfill);
				}
				queue.submit(this::pullFromYouTrack);
				try {
					Thread.sleep(60_000);
				} catch (InterruptedException e) {
					return;
				}
			}
		});
	}

	// ------------------------------------------------------------------ Discord -> YouTrack

	@Override
	public void onChannelCreate(ChannelCreateEvent event) {
		if (event.getChannel() instanceof ThreadChannel thread && forum(thread) != null) {
			// the first message lands a moment after the thread
			queue.submit(() -> {
				sleep(4000);
				importThread(thread);
			});
		}
	}

	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		if (!(event.getChannel() instanceof ThreadChannel thread) || forum(thread) == null || event.getAuthor().isBot()) {
			return;
		}
		Message message = event.getMessage();
		if (message.getId().equals(thread.getId())) {
			return; // the post itself (a forum post's first message has the thread's id)
		}
		queue.submit(() -> {
			Link link = store.threads.get(thread.getId());
			if (link == null) {
				importThread(thread);
				return;
			}
			forwardReply(link, message);
		});
	}

	@Override
	public void onMessageUpdate(MessageUpdateEvent event) {
		if (event.getChannel() instanceof ThreadChannel thread && forum(thread) != null && !event.getAuthor().isBot()
				&& !event.getMessage().getId().equals(thread.getId())) {
			Message m = event.getMessage();
			queue.submit(() -> {
				Link link = store.threads.get(thread.getId());
				String commentId = link == null ? null : link.replyComments.get(m.getId());
				if (commentId != null) {
					try {
						yt.updateComment(link.issue, commentId, replyText(m) + "\n\n_(edited on Discord)_");
					} catch (IOException e) {
						System.out.println("[forums] edit reply " + link.issue + ": " + e.getMessage());
					}
				}
			});
			return;
		}
		if (event.getChannel() instanceof ThreadChannel thread && forum(thread) != null && event.getMessage().getId().equals(thread.getId())) {
			queue.submit(() -> {
				Link link = store.threads.get(thread.getId());
				if (link != null) {
					try {
						yt.update(link.issue, null, description(thread, event.getMessage()));
					} catch (IOException e) {
						System.out.println("[forums] edit " + link.issue + ": " + e.getMessage());
					}
				}
			});
		}
	}

	@Override
	public void onMessageDelete(net.dv8tion.jda.api.events.message.MessageDeleteEvent event) {
		if (!(event.getChannel() instanceof ThreadChannel thread) || forum(thread) == null) {
			return;
		}
		String messageId = event.getMessageId();
		queue.submit(() -> {
			Link link = store.threads.get(thread.getId());
			if (link == null) {
				return;
			}
			String commentId = link.replyComments.remove(messageId);
			if (commentId != null) {
				try {
					yt.deleteComment(link.issue, commentId);
					save();
				} catch (IOException e) {
					System.out.println("[forums] delete reply " + link.issue + ": " + e.getMessage());
				}
			}
			// one of the bot's own posts (a team comment) deleted on Discord: it stays in YouTrack, just not linked any more
			link.postedMessages.values().remove(messageId);
		});
	}

	@Override
	public void onChannelDelete(net.dv8tion.jda.api.events.channel.ChannelDeleteEvent event) {
		if (!(event.getChannel() instanceof ThreadChannel thread)) {
			return;
		}
		queue.submit(() -> {
			Link link = store.threads.get(thread.getId());
			if (link == null || link.deleted) {
				return;
			}
			link.deleted = true;
			save();
			try {
				yt.tag(link.issue, "discord-deleted");
				yt.comment(link.issue, FROM_DISCORD + "The Discord thread was deleted - nothing syncs for this issue any more.");
			} catch (IOException e) {
				System.out.println("[forums] thread deleted " + link.issue + ": " + e.getMessage());
			}
		});
	}

	@Override
	public void onChannelUpdateName(ChannelUpdateNameEvent event) {
		if (event.getChannel() instanceof ThreadChannel thread && forum(thread) != null) {
			queue.submit(() -> {
				Link link = store.threads.get(thread.getId());
				if (link != null && !event.getNewValue().equals(link.title)) {
					link.title = event.getNewValue();
					save();
					try {
						yt.update(link.issue, event.getNewValue(), null);
					} catch (IOException e) {
						System.out.println("[forums] rename " + link.issue + ": " + e.getMessage());
					}
				}
			});
		}
	}

	@Override
	public void onChannelUpdateAppliedTags(ChannelUpdateAppliedTagsEvent event) {
		if (event.getChannel() instanceof ThreadChannel thread && forum(thread) != null) {
			queue.submit(() -> {
				Link link = store.threads.get(thread.getId());
				if (link != null) {
					syncTags(thread, link, false);
				}
			});
		}
	}

	/** The forum's tags -> Area / Type / State and forum-* tags on the issue. */
	private void syncTags(ThreadChannel thread, Link link, boolean withType) {
		List<JsonObject> fields = fieldsFromTags(thread.getAppliedTags(), forum(thread), withType);
		String state = stateFromTags(thread.getAppliedTags());
		if (state != null && !sameTag(state, link.state)) {
			fields.add(YouTrackClient.enumField("State", state));
			link.state = state;
		} else if (state == null && !withType && discordStatus(link.state) != null) {
			// its Done / Declined / In Progress tag was taken off on Discord: back to New
			fields.add(YouTrackClient.enumField("State", "New"));
			link.state = "New";
		}
		boolean area = fields.stream().anyMatch(f -> f.get("name").getAsString().equals("Subsystem"));
		if (!area && !withType) {
			JsonObject clear = new JsonObject();
			clear.addProperty("name", "Subsystem");
			clear.addProperty("$type", "SingleOwnedIssueCustomField");
			clear.add("value", com.google.gson.JsonNull.INSTANCE);
			fields.add(clear);
		}
		try {
			if (!fields.isEmpty()) {
				yt.setFields(link.issue, fields);
			}
			Set<String> want = new LinkedHashSet<>();
			for (ForumTag tag : thread.getAppliedTags()) {
				want.add(tagName(tag));
				yt.tag(link.issue, tagName(tag));
			}
			for (String have : yt.tagsOf(link.issue)) {
				if (have.startsWith("forum-") && !want.contains(have)) {
					yt.untag(link.issue, have);
				}
			}
			link.tagsPending = false;
			save();
		} catch (IOException e) {
			System.out.println("[forums] tags " + link.issue + ": " + e.getMessage());
		}
	}

	private Forum forum(ThreadChannel thread) {
		return thread.getParentChannel() instanceof ForumChannel f ? FORUMS.get(f.getId()) : null;
	}

	private static boolean guideline(ThreadChannel thread) {
		String name = thread.getName().toLowerCase(Locale.ROOT);
		return thread.isPinned() || name.contains("guideline") || name.contains("read me") || name.contains("readme");
	}

	/** A post: its issue, then every reply so far. */
	private void importThread(ThreadChannel thread) {
		Forum forum = forum(thread);
		if (forum == null || guideline(thread)) {
			return;
		}
		Link link = store.threads.get(thread.getId());
		try {
			if (link == null) {
				Message start;
				try {
					start = thread.retrieveStartMessage().complete();
				} catch (RuntimeException e) {
					start = null; // the first message was deleted
				}
				List<JsonObject> fields = fieldsFromTags(thread.getAppliedTags(), forum, true);
				String state = stateFromTags(thread.getAppliedTags());
				fields.add(YouTrackClient.enumField("State", state != null ? state : "New"));
				fields.add(YouTrackClient.enumField("Source", "Discord"));
				String author = start != null ? start.getAuthor().getName() : thread.getOwnerId();
				fields.add(YouTrackClient.textField("Player", author));
				fields.add(YouTrackClient.textField("Velo Report", "discord:" + thread.getId()));
				List<String> tagNames = new ArrayList<>(List.of("discord", forum.tag()));
				for (ForumTag tag : thread.getAppliedTags()) {
					tagNames.add(tagName(tag));
				}
				String issue = yt.create(thread.getName(), description(thread, start), fields, tagNames);
				link = new Link();
				link.issue = issue;
				link.forum = forum.name();
				link.state = state != null ? state : "New";
				link.title = thread.getName();
				store.threads.put(thread.getId(), link);
				save();
				if (start != null) {
					attachAll(link, start);
					link.messages.add(start.getId());
				}
				System.out.println("[forums] " + forum.name() + " \"" + thread.getName() + "\" -> " + issue);
			}
			// replies so far, oldest first
			List<Message> history = new ArrayList<>(thread.getIterableHistory().takeAsync(500).get());
			java.util.Collections.reverse(history);
			link.filesPending = false;
			for (Message m : history) {
				if (m.getAuthor().isBot()) {
					continue;
				}
				if (link.messages.contains(m.getId())) {
					attachAll(link, m);   // only what didn't make it before
				} else if (!m.getId().equals(thread.getId())) {
					forwardReply(link, m);
				}
			}
			save();
		} catch (Exception e) {
			System.out.println("[forums] import " + thread.getName() + ": " + e.getMessage());
		}
	}

	private static String replyText(Message m) {
		String text = m.getContentDisplay().strip();
		StringBuilder body = new StringBuilder(FROM_DISCORD).append("**").append(m.getAuthor().getName()).append("** on Discord:\n");
		body.append(text.isEmpty() ? "_(attachment)_" : text);
		for (Message.Attachment a : m.getAttachments()) {
			body.append("\n\n![").append(a.getFileName()).append("](").append(a.getFileName()).append(")");
		}
		return body.toString();
	}

	private void forwardReply(Link link, Message m) {
		if (link.messages.contains(m.getId())) {
			return;
		}
		try {
			String commentId = yt.comment(link.issue, replyText(m));
			link.comments.add(commentId);
			link.replyComments.put(m.getId(), commentId);
			attachAll(link, m);
			link.messages.add(m.getId());
			save();
		} catch (IOException e) {
			System.out.println("[forums] reply to " + link.issue + ": " + e.getMessage());
		}
	}

	private void attachAll(Link link, Message m) {
		String issue = link.issue;
		java.util.Set<String> existing = null;
		for (Message.Attachment a : m.getAttachments()) {
			if (link.files.contains(a.getId())) {
				continue;
			}
			try {
				if (existing == null) {
					existing = yt.attachmentNames(issue);
				}
				if (existing.contains(a.getFileName())) {
					link.files.add(a.getId());   // uploaded before files were tracked
					save();
					continue;
				}
				yt.attachUrl(issue, a.getUrl(), a.getFileName());
				String n = a.getFileName().toLowerCase(Locale.ROOT);
				if (a.isVideo() || n.matches(".*\\.(mp4|webm|mov|mkv)")) {
					yt.tag(issue, "has-video");
				} else if (a.isImage()) {
					yt.tag(issue, "has-screenshot");
				} else if (n.endsWith(".log") || n.endsWith(".txt")) {
					yt.tag(issue, "has-logs");
				}
				link.files.add(a.getId());
				save();
			} catch (IOException e) {
				link.filesPending = true;
				System.out.println("[forums] file " + a.getFileName() + " -> " + issue + ": " + e.getMessage());
			}
		}
	}

	private String description(ThreadChannel thread, Message start) {
		StringBuilder d = new StringBuilder();
		String text = start == null ? "" : start.getContentDisplay().strip();
		d.append(text.isEmpty() ? "_No text in the post._" : text);
		if (start != null) {
			for (Message.Attachment a : start.getAttachments()) {
				if (a.isImage()) {
					d.append("\n\n![").append(a.getFileName()).append("](").append(a.getFileName()).append(")");
				}
			}
		}
		d.append("\n\n---\n\n| | |\n|---|---|\n");
		d.append("| **Discord** | [").append(thread.getName().replace("|", "/")).append("](https://discord.com/channels/").append(guildId)
				.append("/").append(thread.getId()).append(") |\n");
		d.append("| **Forum** | ").append(forum(thread).name()).append(" |\n");
		if (start != null) {
			d.append("| **Author** | ").append(start.getAuthor().getName()).append(" |\n");
		}
		if (!thread.getAppliedTags().isEmpty()) {
			d.append("| **Forum tags** | ").append(String.join(", ", thread.getAppliedTags().stream().map(ForumTag::getName).toList())).append(" |\n");
		}
		d.append("\nReplies in the thread arrive here as comments; public comments here are posted back to the thread as \"Velo Team\".");
		return d.toString();
	}

	private static String tagName(ForumTag tag) {
		return "forum-" + tag.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
	}

	/** Area (Subsystem) and Type from the forum's tags. */
	private static List<JsonObject> fieldsFromTags(List<ForumTag> tags, Forum forum, boolean withType) {
		List<JsonObject> fields = new ArrayList<>();
		String type = forum.type();
		for (ForumTag tag : tags) {
			switch (tag.getName().toLowerCase(Locale.ROOT)) {
				case "launcher" -> fields.add(YouTrackClient.enumField("Subsystem", "Launcher"));
				case "module" -> fields.add(YouTrackClient.enumField("Subsystem", "HUD & Modules"));
				case "cosmetic" -> fields.add(YouTrackClient.enumField("Subsystem", "Cosmetics"));
				case "bug" -> type = "Bug";
				case "suggestion" -> type = "Suggestion";
				default -> {
				}
			}
		}
		if (withType || !type.equals(forum.type())) {
			fields.add(YouTrackClient.enumField("Type", type));
		}
		return fields;
	}

	/** Which forum status tag a YouTrack state shows as (null: none). */
	private static String discordStatus(String state) {
		return state == null ? null : switch (state) {
			case "Fixed", "Verified" -> "done";
			case "Won't Fix", "Duplicate", "Can't Reproduce" -> "declined";
			case "In Progress", "Testing" -> "progress";
			default -> null;
		};
	}

	private static boolean sameTag(String a, String b) {
		return a.equals(b) || discordStatus(a) != null && discordStatus(a).equals(discordStatus(b));
	}

	/** Done / Declined / In Progress on the forum -> YouTrack's State (null: no status tag). */
	private static String stateFromTags(List<ForumTag> tags) {
		for (ForumTag tag : tags) {
			switch (tag.getName().toLowerCase(Locale.ROOT)) {
				case "done" -> {
					return "Fixed";
				}
				case "declined" -> {
					return "Won't Fix";
				}
				case "in progress", "in development" -> {
					return "In Progress";
				}
				default -> {
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ YouTrack -> Discord

	private void pullFromYouTrack() {
		java.util.Set<String> changed;
		long now = System.currentTimeMillis();
		try {
			// only the issues touched since the last look (with a minute of overlap)
			// every 10th round everything (a comment edit doesn't always count as a change of its issue)
			changed = lastPull == 0 || pulls++ % 15 == 0 ? null : yt.changedSince("discord", lastPull - 90_000);
		} catch (IOException e) {
			System.out.println("[forums] changed: " + e.getMessage());
			return;
		}
		lastPull = now;
		for (var entry : List.copyOf(store.threads.entrySet())) {
			String threadId = entry.getKey();
			Link link = entry.getValue();
			if (changed != null && !changed.contains(link.issue)) {
				continue;
			}
			try {
				YouTrackClient.Status st = yt.status(link.issue);
				ThreadChannel thread = jda.getThreadChannelById(threadId);
				if (thread == null) {
					continue; // archived and not cached: picked up when it's active again
				}
				if (link.title != null && !st.summary().isBlank() && !st.summary().equals(link.title)) {
					// renamed in YouTrack: the thread follows (Discord titles: 100 characters)
					link.title = st.summary().length() > 100 ? st.summary().substring(0, 100) : st.summary();
					save();
					thread.getManager().setName(link.title).queue(null, e -> System.out.println("[forums] rename thread: " + e.getMessage()));
				} else if (link.title == null) {
					link.title = thread.getName();
				}
				// the owner's comments already on Discord: edited -> the message follows, deleted -> it goes too
				Set<String> alive = new LinkedHashSet<>();
				for (YouTrackClient.Comment c : st.comments()) {
					alive.add(c.id());
					String msg = link.postedMessages.get(c.id());
					if (msg != null && link.postedText.getOrDefault(c.id(), 0) != c.text().hashCode()) {
						link.postedText.put(c.id(), c.text().hashCode());
						save();
						thread.editMessageEmbedsById(msg, teamEmbed(c.text())).queue(null, e -> System.out.println("[forums] edit msg: " + e.getMessage()));
					}
				}
				for (var posted : List.copyOf(link.postedMessages.entrySet())) {
					if (!alive.contains(posted.getKey())) {
						link.postedMessages.remove(posted.getKey());
						link.postedText.remove(posted.getKey());
						save();
						thread.deleteMessageById(posted.getValue()).queue(null, e -> System.out.println("[forums] delete msg: " + e.getMessage()));
					}
				}
				if (!st.state().equals(link.state)) {
					String old = link.state;
					link.state = st.state();
					save();
					applyStatusTag(thread, st.state());
					if (old != null) {
						thread.sendMessage(statusLine(st.state())).queue(null, e -> System.out.println("[forums] status msg: " + e.getMessage()));
					}
				}
				for (YouTrackClient.Comment c : st.comments()) {
					if (link.comments.contains(c.id())) {
						continue;
					}
					link.comments.add(c.id());
					save();
					if (c.limited() || c.text().startsWith(FROM_DISCORD) || c.text().startsWith(FROM_TESTER) || c.text().startsWith("Again: ")) {
						continue; // team-only, or it came from Discord / the game in the first place
					}
					if (!POSTERS.contains(c.author())) {
						continue; // only the owner's comments speak for Velo on Discord (owner 2026-11-25)
					}
					postTeamComment(thread, link, c);
				}
			} catch (IOException | RuntimeException e) {
				System.out.println("[forums] pull " + link.issue + ": " + e.getMessage());
			}
		}
	}

	/** A team comment from YouTrack as the bot's message: an embed "Velo Team" with the text, plus its pictures. */
	private net.dv8tion.jda.api.EmbedBuilder teamEmbedBuilder(String raw) {
		String text = raw.length() > 3900 ? raw.substring(0, 3900) + "..." : raw;
		// YouTrack's inline picture syntax (![](file.png)) reads oddly on Discord - the pictures come as files below
		text = text.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)(\\{[^}]*\\})?", "").strip();
		return new net.dv8tion.jda.api.EmbedBuilder()
				.setAuthor("Velo Team", null, jda.getSelfUser().getEffectiveAvatarUrl())
				.setDescription(text.isEmpty() ? "_(picture)_" : text)
				.setColor(0xE5373E)
				.setFooter("Reply here - the team reads every message");
	}

	private net.dv8tion.jda.api.entities.MessageEmbed teamEmbed(String raw) {
		return teamEmbedBuilder(raw).build();
	}

	private void postTeamComment(ThreadChannel thread, Link link, YouTrackClient.Comment c) {
		net.dv8tion.jda.api.EmbedBuilder embed = teamEmbedBuilder(c.text());
		List<net.dv8tion.jda.api.utils.FileUpload> files = new ArrayList<>();
		for (String[] f : c.files()) {
			try {
				byte[] bytes = yt.download(f[1]);
				if (bytes.length < 24 * 1024 * 1024) {
					files.add(net.dv8tion.jda.api.utils.FileUpload.fromData(bytes, f[0]));
				}
			} catch (IOException e) {
				System.out.println("[forums] comment file " + f[0] + ": " + e.getMessage());
			}
		}
		for (var f : files) {
			if (f.getName().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp)")) {
				embed.setImage("attachment://" + f.getName());
				break;
			}
		}
		var action = thread.sendMessageEmbeds(embed.build());
		if (!files.isEmpty()) {
			action = action.addFiles(files);
		}
		action.queue(sent -> {
			link.postedMessages.put(c.id(), sent.getId());
			link.postedText.put(c.id(), c.text().hashCode());
			save();
		}, e -> System.out.println("[forums] comment msg: " + e.getMessage()));
	}

	private static String statusLine(String state) {
		return switch (state) {
			case "Confirmed" -> "👀 **Status: Confirmed** - the team has it on the list.";
			case "In Progress" -> "🛠️ **Status: In progress** - someone is working on this.";
			case "Testing" -> "🧪 **Status: Testing** - it's done and our testers are checking it before it ships.";
			case "Needs Info" -> "❓ **Status: Needs info** - the team needs a bit more from you, please reply here.";
			case "Fixed" -> "✅ **Status: Done** - this is fixed / added and comes with the next update.";
			case "Verified" -> "✅ **Status: Verified** - done and checked.";
			case "Won't Fix" -> "❌ **Status: Declined** - not something we'll change.";
			case "Duplicate" -> "🔁 **Status: Duplicate** - this was reported already.";
			case "Can't Reproduce" -> "🔍 **Status: Can't reproduce** - we couldn't make it happen. More details or a video help.";
			default -> "📋 **Status: " + state + "**";
		};
	}

	/** Sets the forum's own status tag (Done / Declined / In Progress) for a YouTrack state, keeping the other tags. */
	private static void applyStatusTag(ThreadChannel thread, String state) {
		if (!(thread.getParentChannel() instanceof ForumChannel forum)) {
			return;
		}
		String want = switch (state) {
			case "Fixed", "Verified" -> "done";
			case "Won't Fix", "Duplicate", "Can't Reproduce" -> "declined";
			case "In Progress", "Testing" -> "in progress|in development";
			default -> null;
		};
		Set<String> status = Set.of("done", "declined", "in progress", "in development");
		List<ForumTag> tags = new ArrayList<>();
		for (ForumTag t : thread.getAppliedTags()) {
			if (!status.contains(t.getName().toLowerCase(Locale.ROOT))) {
				tags.add(t);
			}
		}
		if (want != null) {
			for (ForumTag t : forum.getAvailableTags()) {
				if (t.getName().toLowerCase(Locale.ROOT).matches(want)) {
					tags.add(0, t);
					break;
				}
			}
		}
		if (tags.size() > 5) {
			tags = tags.subList(0, 5);
		}
		if (!new LinkedHashSet<>(tags).equals(new LinkedHashSet<>(thread.getAppliedTags()))) {
			thread.getManager().setAppliedTags(tags).queue(null, e -> System.out.println("[forums] tags " + thread.getName() + ": " + e.getMessage()));
		}
	}

	// ------------------------------------------------------------------ backfill

	private void backfill() {
		for (String forumId : FORUMS.keySet()) {
			ForumChannel forum = jda.getForumChannelById(forumId);
			if (forum == null) {
				continue;
			}
			List<ThreadChannel> threads = new ArrayList<>(forum.getThreadChannels());
			try {
				threads.addAll(forum.retrieveArchivedPublicThreadChannels().takeAsync(200).get());
			} catch (Exception e) {
				System.out.println("[forums] archived threads of " + forum.getName() + ": " + e.getMessage());
			}
			for (ThreadChannel thread : threads) {
				Link link = store.threads.get(thread.getId());
				if (guideline(thread)) {
					continue;
				}
				if (link == null || link.filesPending) {
					importThread(thread);
				}
				link = store.threads.get(thread.getId());
				if (link != null && link.tagsPending) {
					syncTags(thread, link, true);
				}
			}
		}
	}

	private synchronized void save() {
		try {
			Path tmp = file.resolveSibling("forums-youtrack.json.tmp");
			Files.writeString(tmp, GSON.toJson(store), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[forums] save failed: " + e.getMessage());
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
