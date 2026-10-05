package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Launcher news (posts / changelogs written by the owner in the launcher's editor) and community
 * polls. Everything lives in {@code news.json} in the data directory; images the owner uploads are
 * re-encoded and stored content-addressed in {@code news/images/}.
 *
 * <p>A post is a list of simple blocks (heading, text, image, callout, list, divider, button) so
 * the launcher can render it natively and the editor can show a live preview.
 */
final class NewsService {

	static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
	private static final int MAX_IMAGE_SIDE = 4096;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static final class NewsException extends RuntimeException {
		final int status;

		NewsException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	static final class Block {
		String type;
		String text;
		String image;
		String caption;
		List<String> items;
		String style;
		String url;
	}

	static final class Post {
		String id;
		String title;
		String summary;
		String tag;
		String accent;
		String cover;
		String version;
		List<Block> blocks = new ArrayList<>();
		boolean published;
		boolean pinned;
		long createdAt;
		long updatedAt;
		long publishedAt;
		String author;
	}

	static final class Poll {
		String id;
		String question;
		String description;
		String image;
		List<String> options = new ArrayList<>();
		boolean multi;
		long closesAt;
		boolean closed;
		/** "always" or "after_vote". */
		String showResults = "after_vote";
		long createdAt;
		String author;
		/** uuid -> chosen option indexes. Never sent to clients. */
		Map<String, List<Integer>> votes = new LinkedHashMap<>();
	}

	private static final class Data {
		List<Post> posts = new ArrayList<>();
		List<Poll> polls = new ArrayList<>();
	}

	private final Path file;
	private final Path imageDir;
	private Data data;

	NewsService(Path dataDir) throws IOException {
		this.file = dataDir.resolve("news.json");
		this.imageDir = dataDir.resolve("news").resolve("images");
		Files.createDirectories(imageDir);
		Data loaded = Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class) : null;
		this.data = loaded != null ? loaded : new Data();
		if (data.posts == null) {
			data.posts = new ArrayList<>();
		}
		if (data.polls == null) {
			data.polls = new ArrayList<>();
		}
	}

	private void save() {
		try {
			Path tmp = file.resolveSibling("news.json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new NewsException(500, "Couldn't save the news");
		}
	}

	// ---- Reading ----

	record OptionView(String text, int votes) {
	}

	record PollView(String id, String question, String description, String image, List<OptionView> options, boolean multi,
			long closesAt, boolean closed, boolean resultsVisible, int totalVotes, List<Integer> myVote, long createdAt) {
	}

	record Feed(List<Post> posts, List<PollView> polls, boolean owner) {
	}

	/** Published posts (owners also get drafts), newest first with pinned ones on top, and every poll. */
	synchronized Feed feed(String uuid, boolean owner) {
		List<Post> posts = data.posts.stream().filter(p -> owner || p.published)
				.sorted(Comparator.comparing((Post p) -> !p.pinned).thenComparing(p -> -(p.published ? p.publishedAt : p.updatedAt)))
				.toList();
		List<PollView> polls = data.polls.stream().sorted(Comparator.comparingLong((Poll p) -> -p.createdAt))
				.map(p -> view(p, uuid, owner)).toList();
		return new Feed(posts, polls, owner);
	}

	private PollView view(Poll poll, String uuid, boolean owner) {
		boolean closed = isClosed(poll);
		List<Integer> mine = uuid == null ? null : poll.votes.get(uuid);
		boolean visible = owner || closed || "always".equals(poll.showResults) || mine != null;
		int[] counts = new int[poll.options.size()];
		for (List<Integer> choice : poll.votes.values()) {
			for (int index : choice) {
				if (index >= 0 && index < counts.length) {
					counts[index]++;
				}
			}
		}
		List<OptionView> options = new ArrayList<>();
		for (int i = 0; i < poll.options.size(); i++) {
			options.add(new OptionView(poll.options.get(i), visible ? counts[i] : -1));
		}
		return new PollView(poll.id, poll.question, poll.description, poll.image, options, poll.multi, poll.closesAt, closed,
				visible, visible ? poll.votes.size() : -1, mine, poll.createdAt);
	}

	private static boolean isClosed(Poll poll) {
		return poll.closed || poll.closesAt > 0 && System.currentTimeMillis() > poll.closesAt;
	}

	// ---- Voting ----

	synchronized PollView vote(String uuid, String pollId, List<Integer> choice) {
		Poll poll = poll(pollId);
		if (isClosed(poll)) {
			throw new NewsException(409, "This poll has ended");
		}
		if (choice == null || choice.isEmpty()) {
			poll.votes.remove(uuid);
		} else {
			List<Integer> clean = choice.stream().distinct().filter(i -> i >= 0 && i < poll.options.size()).sorted().toList();
			if (clean.isEmpty()) {
				throw new NewsException(400, "Pick an option");
			}
			if (!poll.multi && clean.size() > 1) {
				throw new NewsException(400, "Only one answer allowed");
			}
			poll.votes.put(uuid, new ArrayList<>(clean));
		}
		save();
		return view(poll, uuid, false);
	}

	// ---- Owner: posts ----

	synchronized Post savePost(Post incoming, String author) {
		if (incoming.title == null || incoming.title.isBlank()) {
			throw new NewsException(400, "A post needs a title");
		}
		if (incoming.title.length() > 140 || incoming.summary != null && incoming.summary.length() > 400) {
			throw new NewsException(400, "Title or summary is too long");
		}
		long now = System.currentTimeMillis();
		Post existing = incoming.id == null ? null : data.posts.stream().filter(p -> p.id.equals(incoming.id)).findFirst().orElse(null);
		Post post = existing != null ? existing : new Post();
		if (existing == null) {
			post.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			post.createdAt = now;
			post.author = author;
			data.posts.add(post);
		}
		boolean wasPublished = post.published;
		post.title = incoming.title.trim();
		post.summary = incoming.summary == null ? "" : incoming.summary.trim();
		post.tag = incoming.tag == null || incoming.tag.isBlank() ? "News" : incoming.tag.trim();
		post.accent = incoming.accent;
		post.cover = checkImage(incoming.cover);
		post.version = incoming.version;
		post.blocks = incoming.blocks == null ? new ArrayList<>() : incoming.blocks;
		for (Block block : post.blocks) {
			block.image = checkImage(block.image);
		}
		post.pinned = incoming.pinned;
		post.published = incoming.published;
		post.updatedAt = now;
		if (post.published && !wasPublished) {
			post.publishedAt = now;
		}
		save();
		return post;
	}

	synchronized void deletePost(String id) {
		if (!data.posts.removeIf(p -> p.id.equals(id))) {
			throw new NewsException(404, "No such post");
		}
		save();
	}

	// ---- Owner: polls ----

	synchronized PollView savePoll(Poll incoming, String author) {
		if (incoming.question == null || incoming.question.isBlank()) {
			throw new NewsException(400, "A poll needs a question");
		}
		List<String> options = incoming.options == null ? List.of()
				: incoming.options.stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toList();
		if (options.size() < 2 || options.size() > 10) {
			throw new NewsException(400, "A poll needs 2 to 10 options");
		}
		Poll existing = incoming.id == null ? null : data.polls.stream().filter(p -> p.id.equals(incoming.id)).findFirst().orElse(null);
		Poll poll = existing != null ? existing : new Poll();
		if (existing == null) {
			poll.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			poll.createdAt = System.currentTimeMillis();
			poll.author = author;
			data.polls.add(poll);
		} else if (options.size() != poll.options.size()) {
			// Changing the number of answers would scramble existing votes.
			poll.votes.clear();
		}
		poll.question = incoming.question.trim();
		poll.description = incoming.description == null ? "" : incoming.description.trim();
		poll.image = checkImage(incoming.image);
		poll.options = new ArrayList<>(options);
		poll.multi = incoming.multi;
		poll.closesAt = Math.max(0, incoming.closesAt);
		poll.closed = incoming.closed;
		poll.showResults = "always".equals(incoming.showResults) ? "always" : "after_vote";
		save();
		return view(poll, null, true);
	}

	synchronized void deletePoll(String id) {
		if (!data.polls.removeIf(p -> p.id.equals(id))) {
			throw new NewsException(404, "No such poll");
		}
		save();
	}

	private Poll poll(String id) {
		return data.polls.stream().filter(p -> p.id.equals(id)).findFirst()
				.orElseThrow(() -> new NewsException(404, "No such poll"));
	}

	// ---- Images ----

	record StoredImage(String id, int width, int height) {
	}

	/** Decodes, size-checks and re-encodes an uploaded image (PNG if it has transparency, JPEG otherwise). */
	StoredImage storeImage(byte[] bytes) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) {
			throw new NewsException(400, "That isn't a PNG, JPEG, GIF or BMP image");
		}
		if (image.getWidth() > MAX_IMAGE_SIDE || image.getHeight() > MAX_IMAGE_SIDE) {
			throw new NewsException(400, "Images can be at most " + MAX_IMAGE_SIDE + " px wide/tall");
		}
		boolean alpha = image.getColorModel().hasAlpha();
		byte[] encoded;
		String type;
		if (alpha) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			encoded = out.toByteArray();
			type = "png";
		} else {
			BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
			rgb.getGraphics().drawImage(image, 0, 0, null);
			encoded = jpeg(rgb, 0.9f);
			type = "jpg";
		}
		String hash;
		try {
			hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)).substring(0, 32);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		String id = hash + "." + type;
		Path target = imageDir.resolve(id);
		if (!Files.exists(target)) {
			Files.write(target, encoded);
		}
		return new StoredImage(id, image.getWidth(), image.getHeight());
	}

	private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(stream);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.write(null, new IIOImage(image, null, null), param);
		} finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

	/** Bytes of a stored image, or null. Ids are validated so they can't escape the folder. */
	byte[] image(String id) throws IOException {
		if (id == null || !id.matches("[0-9a-f]{32}\\.(png|jpg)")) {
			return null;
		}
		Path path = imageDir.resolve(id);
		return Files.exists(path) ? Files.readAllBytes(path) : null;
	}

	private String checkImage(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		if (!id.matches("[0-9a-f]{32}\\.(png|jpg)") || !Files.exists(imageDir.resolve(id))) {
			throw new NewsException(400, "Unknown image - upload it first");
		}
		return id;
	}
}
