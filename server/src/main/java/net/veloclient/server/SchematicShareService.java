package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Community schematics: players share their own {@code .veloschem} / {@code .litematic} files with a
 * preview image, title, description and tags; everyone can browse, upvote and download them.
 *
 * <p>What's accepted, and why it's safe to hand to other players:
 * <ul>
 * <li>Only those two formats, checked by content, not by name: both are gzip; a .veloschem must
 * start with Velo's own header, a .litematic must be an NBT compound with regions. Decompression is
 * capped, so a gzip bomb can't eat the server. Nothing else (no jars, zips, scripts) gets through,
 * and the file is served back byte for byte under a name the server picks.</li>
 * <li>The preview must decode as an image; it's re-encoded as a fresh PNG (max 512 px wide), so
 * nothing but pixels is ever passed on.</li>
 * <li>Title, description and tags may not contain links, domains, invites or the usual scam bait;
 * control characters are refused. Players can delete their own uploads, owners any.</li>
 * <li>4 uploads an hour, 12 a day and 40 live uploads per player.</li>
 * </ul>
 *
 * <p>"Recommended" ranks by votes against age ({@code (votes + 1) / (hours + 2)^1.4}), so good
 * new uploads rise quickly and older ones need more votes to stay up; "New" and "Top" sort by time
 * and votes. Stored in the data directory: {@code schematics/index.json} plus the files and previews.
 */
final class SchematicShareService {

	static final int MAX_FILE_BYTES = 3 * 1024 * 1024;
	static final int MAX_PREVIEW_BYTES = 1024 * 1024;
	private static final long MAX_UNZIPPED = 96L * 1024 * 1024;
	private static final int PAGE_SIZE = 30;
	private static final int MAX_PER_PLAYER = 40;

	private static final Pattern LINK = Pattern.compile(
			"(?i)(https?:|ftp:|www\\.|discord(app)?\\s*\\.\\s*(gg|com)|\\.gg/|\\bt\\.me\\b|bit\\.ly|"
					+ "\\b[a-z0-9-]{2,}(\\.|\\s*\\(dot\\)\\s*|\\s*\\[dot\\]\\s*)(com|net|org|gg|io|xyz|ru|ly|me|co|tk|top|link|click|app|dev|shop"
					+ "|site|online|live|store|info|biz|cc|to|su|pw|zip|mov)\\b)");
	private static final Pattern SCAM = Pattern.compile(
			"(?i)(free\\s*nitro|nitro\\s*gift|gift\\s*card|free\\s*robux|free\\s*minecoins|steam\\s*gift|login\\s*to\\s*claim|"
					+ "verify\\s*your\\s*account|crypto|airdrop|token\\s*grabber|session\\s*id)");
	private static final Pattern TAG = Pattern.compile("[a-z0-9][a-z0-9 -]{0,19}");

	static final class SchematicException extends RuntimeException {
		final int status;

		SchematicException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	/** What's stored per upload. */
	static final class Entry {
		String id;
		String title;
		String description;
		List<String> tags;
		String format;
		long fileSize;
		String authorUuid;
		String author;
		long createdAt;
		long downloads;
		Set<String> voters = new HashSet<>();
	}

	/** One row of a listing, as the client sees it. */
	record Listing(String id, String title, String description, List<String> tags, String format, long fileSize, String author,
			long createdAt, int votes, long downloads, boolean voted, boolean mine) {
	}

	record Page(List<Listing> items, int total, int page, int pageSize) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path root;
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final RateLimiter limits = new RateLimiter();
	private final SecureRandom random = new SecureRandom();

	SchematicShareService(Path dataDir) throws IOException {
		this.root = dataDir.resolve("schematics");
		Files.createDirectories(root.resolve("files"));
		Files.createDirectories(root.resolve("previews"));
		Path index = root.resolve("index.json");
		if (Files.exists(index)) {
			List<Entry> loaded = GSON.fromJson(Files.readString(index, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() {
			}.getType());
			if (loaded != null) {
				for (Entry e : loaded) {
					if (e.voters == null) {
						e.voters = new HashSet<>();
					}
					entries.put(e.id, e);
				}
			}
		}
	}

	private void save() throws IOException {
		Path tmp = root.resolve("index.json.tmp");
		Files.writeString(tmp, GSON.toJson(new ArrayList<>(entries.values())), StandardCharsets.UTF_8);
		Files.move(tmp, root.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	// ---- upload ----

	synchronized Listing upload(String uuid, String username, String title, String description, List<String> tags, String fileName,
			byte[] file, byte[] preview) throws IOException {
		String cleanTitle = cleanText(title, 3, 60, "Title");
		String cleanDescription = description == null || description.isBlank() ? "" : cleanText(description, 0, 600, "Description");
		List<String> cleanTags = cleanTags(tags);
		String format = format(fileName, file);
		byte[] previewPng = cleanPreview(preview);
		long mine = entries.values().stream().filter(e -> e.authorUuid.equals(uuid)).count();
		if (mine >= MAX_PER_PLAYER) {
			throw new SchematicException(409, "You already share " + MAX_PER_PLAYER + " schematics - delete one first");
		}
		long wait = limits.tryAcquire(uuid, "upload", new RateLimiter.Window(4, 3_600_000), new RateLimiter.Window(12, 86_400_000));
		if (wait > 0) {
			throw new SchematicException(429, "Too many uploads - try again in " + RateLimiter.describeWait(wait));
		}
		Entry e = new Entry();
		e.id = newId();
		e.title = cleanTitle;
		e.description = cleanDescription;
		e.tags = cleanTags;
		e.format = format;
		e.fileSize = file.length;
		e.authorUuid = uuid;
		e.author = username;
		e.createdAt = System.currentTimeMillis();
		Files.write(root.resolve("files").resolve(e.id + ".bin"), file);
		Files.write(root.resolve("previews").resolve(e.id + ".png"), previewPng);
		entries.put(e.id, e);
		save();
		return listing(e, uuid);
	}

	private String newId() {
		String id;
		do {
			byte[] bytes = new byte[8];
			random.nextBytes(bytes);
			id = java.util.HexFormat.of().formatHex(bytes);
		} while (entries.containsKey(id));
		return id;
	}

	static String cleanText(String text, int min, int max, String what) {
		String t = text == null ? "" : text.strip().replaceAll("[ \\t]+", " ");
		if (t.length() < min) {
			throw new SchematicException(400, what + " is too short");
		}
		if (t.length() > max) {
			throw new SchematicException(400, what + " is too long (max " + max + " characters)");
		}
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (Character.isISOControl(c) && c != '\n' || Character.getType(c) == Character.FORMAT) {
				throw new SchematicException(400, what + " contains invisible characters");
			}
		}
		if (LINK.matcher(t).find()) {
			throw new SchematicException(400, "Links and web addresses aren't allowed in the " + what.toLowerCase(Locale.ROOT));
		}
		if (SCAM.matcher(t).find()) {
			throw new SchematicException(400, "The " + what.toLowerCase(Locale.ROOT) + " looks like spam - please describe the build itself");
		}
		return t;
	}

	static List<String> cleanTags(List<String> tags) {
		List<String> out = new ArrayList<>();
		if (tags == null) {
			return out;
		}
		for (String raw : tags) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String tag = raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
			if (!TAG.matcher(tag).matches() || LINK.matcher(tag).find()) {
				throw new SchematicException(400, "Tags are short words: letters, digits, spaces and dashes (\"" + raw.strip() + "\")");
			}
			if (!out.contains(tag)) {
				out.add(tag);
			}
		}
		if (out.size() > 6) {
			throw new SchematicException(400, "At most 6 tags");
		}
		return out;
	}

	/** "veloschem" or "litematic", checked by content; anything else is refused. */
	static String format(String fileName, byte[] file) throws IOException {
		String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT).strip();
		boolean velo = name.endsWith(".veloschem");
		boolean litematic = name.endsWith(".litematic");
		if (!velo && !litematic) {
			throw new SchematicException(400, "Only .veloschem and .litematic files can be shared");
		}
		if (file == null || file.length < 20 || file.length > MAX_FILE_BYTES) {
			throw new SchematicException(400, "The file must be under " + MAX_FILE_BYTES / 1024 / 1024 + " MB");
		}
		if ((file[0] & 0xFF) != 0x1F || (file[1] & 0xFF) != 0x8B) {
			throw new SchematicException(400, "That isn't a valid schematic file");
		}
		byte[] head = new byte[64 * 1024];
		int headLength = 0;
		long total = 0;
		boolean hasRegions = false;
		byte[] regions = "Regions".getBytes(StandardCharsets.US_ASCII);
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(file))) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			byte[] tail = new byte[0];
			while ((read = in.read(buffer)) > 0) {
				total += read;
				if (total > MAX_UNZIPPED) {
					throw new SchematicException(400, "That schematic is too big");
				}
				if (headLength < head.length) {
					int n = Math.min(read, head.length - headLength);
					System.arraycopy(buffer, 0, head, headLength, n);
					headLength += n;
				}
				if (!hasRegions) {
					byte[] window = new byte[tail.length + read];
					System.arraycopy(tail, 0, window, 0, tail.length);
					System.arraycopy(buffer, 0, window, tail.length, read);
					hasRegions = indexOf(window, regions) >= 0;
					tail = java.util.Arrays.copyOfRange(window, Math.max(0, window.length - regions.length), window.length);
				}
			}
		} catch (java.util.zip.ZipException | java.io.EOFException e) {
			throw new SchematicException(400, "That isn't a valid schematic file");
		}
		if (velo) {
			// DataOutputStream.writeUTF("VELOSCHEM"): two length bytes, then the text.
			if (headLength < 11 || head[0] != 0 || head[1] != 9
					|| !new String(head, 2, 9, StandardCharsets.US_ASCII).equals("VELOSCHEM")) {
				throw new SchematicException(400, "That isn't a valid .veloschem file");
			}
			return "veloschem";
		}
		if (headLength < 3 || head[0] != 10 || !hasRegions) {
			throw new SchematicException(400, "That isn't a valid .litematic file");
		}
		return "litematic";
	}

	private static int indexOf(byte[] data, byte[] needle) {
		outer:
		for (int i = 0; i <= data.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (data[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	/** Decodes the preview and writes it again as a plain PNG, at most 512 px wide. */
	static byte[] cleanPreview(byte[] bytes) throws IOException {
		if (bytes == null || bytes.length == 0 || bytes.length > MAX_PREVIEW_BYTES) {
			throw new SchematicException(400, "The preview image is missing or too big");
		}
		BufferedImage image;
		try {
			javax.imageio.stream.ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
			var readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				throw new SchematicException(400, "The preview isn't an image");
			}
			var reader = readers.next();
			reader.setInput(stream);
			if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048 || reader.getWidth(0) < 16 || reader.getHeight(0) < 16) {
				throw new SchematicException(400, "The preview must be between 16 and 2048 pixels");
			}
			image = reader.read(0);
		} catch (IOException | RuntimeException e) {
			if (e instanceof SchematicException se) {
				throw se;
			}
			throw new SchematicException(400, "The preview isn't a readable image");
		}
		int w = Math.min(512, image.getWidth());
		int h = Math.max(1, image.getHeight() * w / image.getWidth());
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		var g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, 0, 0, w, h, null);
		g.dispose();
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(out, "png", png);
		return png.toByteArray();
	}

	// ---- browse ----

	synchronized Page list(String sort, String query, String tag, int page, String viewerUuid) {
		String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
		String t = tag == null ? "" : tag.strip().toLowerCase(Locale.ROOT);
		long now = System.currentTimeMillis();
		List<Entry> matching = new ArrayList<>();
		for (Entry e : entries.values()) {
			if (!t.isEmpty() && !e.tags.contains(t)) {
				continue;
			}
			if (!q.isEmpty() && !(e.title.toLowerCase(Locale.ROOT).contains(q) || e.author.toLowerCase(Locale.ROOT).contains(q)
					|| e.tags.stream().anyMatch(x -> x.contains(q)) || e.description.toLowerCase(Locale.ROOT).contains(q))) {
				continue;
			}
			if ("mine".equals(sort) && !e.authorUuid.equals(viewerUuid)) {
				continue;
			}
			matching.add(e);
		}
		Comparator<Entry> order = switch (sort == null ? "hot" : sort) {
			case "new", "mine" -> Comparator.comparingLong((Entry e) -> e.createdAt).reversed();
			case "top" -> Comparator.comparingInt((Entry e) -> e.voters.size()).reversed().thenComparing(Comparator.comparingLong((Entry e) -> e.createdAt).reversed());
			default -> Comparator.comparingDouble((Entry e) -> hotScore(e, now)).reversed();
		};
		matching.sort(order);
		int p = Math.max(0, page);
		List<Listing> items = new ArrayList<>();
		for (int i = p * PAGE_SIZE; i < Math.min(matching.size(), (p + 1) * PAGE_SIZE); i++) {
			items.add(listing(matching.get(i), viewerUuid));
		}
		return new Page(items, matching.size(), p, PAGE_SIZE);
	}

	static double hotScore(Entry e, long now) {
		double hours = Math.max(0, (now - e.createdAt) / 3_600_000.0);
		return (e.voters.size() + 1) / Math.pow(hours + 2, 1.4);
	}

	private Listing listing(Entry e, String viewerUuid) {
		return new Listing(e.id, e.title, e.description, e.tags, e.format, e.fileSize, e.author, e.createdAt, e.voters.size(), e.downloads,
				viewerUuid != null && e.voters.contains(viewerUuid), viewerUuid != null && e.authorUuid.equals(viewerUuid));
	}

	synchronized byte[] preview(String id) throws IOException {
		if (!entries.containsKey(id)) {
			return null;
		}
		return Files.readAllBytes(root.resolve("previews").resolve(id + ".png"));
	}

	/** The file and the name to save it under ("<title>.<format>"); counts a download. */
	synchronized Object[] download(String id) throws IOException {
		Entry e = entries.get(id);
		if (e == null) {
			return null;
		}
		e.downloads++;
		save();
		String name = e.title.replaceAll("[^A-Za-z0-9 _.-]", "_").strip();
		return new Object[] {Files.readAllBytes(root.resolve("files").resolve(id + ".bin")), (name.isEmpty() ? "schematic" : name) + "." + e.format};
	}

	synchronized Listing vote(String id, String uuid, boolean up) throws IOException {
		Entry e = entries.get(id);
		if (e == null) {
			throw new SchematicException(404, "No such schematic");
		}
		if (e.authorUuid.equals(uuid)) {
			throw new SchematicException(400, "You can't vote for your own schematic");
		}
		long wait = limits.tryAcquire(uuid, "vote", new RateLimiter.Window(30, 60_000));
		if (wait > 0) {
			throw new SchematicException(429, "Slow down - try again in " + RateLimiter.describeWait(wait));
		}
		boolean changed = up ? e.voters.add(uuid) : e.voters.remove(uuid);
		if (changed) {
			save();
		}
		return listing(e, uuid);
	}

	synchronized boolean delete(String id, String uuid, boolean owner) throws IOException {
		Entry e = entries.get(id);
		if (e == null) {
			return false;
		}
		if (!owner && !e.authorUuid.equals(uuid)) {
			throw new SchematicException(403, "That's not your schematic");
		}
		entries.remove(id);
		Files.deleteIfExists(root.resolve("files").resolve(id + ".bin"));
		Files.deleteIfExists(root.resolve("previews").resolve(id + ".png"));
		save();
		return true;
	}

	void sweepLimits() {
		limits.sweep(86_400_000);
	}
}
