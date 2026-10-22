package net.veloclient.velo.client.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.config.VeloPaths;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Velo+ Cloud Sync: your Velo setup on every PC, with restore points on the Velo server.
 *
 * <p><b>What's in a backup.</b> Module settings and HUD layouts (every saved profile), server auto-profiles, crosshairs,
 * waypoints, loadouts, theme, keybinds (Velo's own and the {@code key_} lines of {@code options.txt}) and the other
 * Velo settings in {@link #CONFIG_FILES}. Never account or sign-in files, proofs or caches.
 *
 * <p><b>Syncing.</b> {@code config/cloud-sync.json} remembers the hash of what was last synced. After sign-in the game
 * compares: only the cloud changed -> it's pulled; only this PC changed -> it's pushed; both -> this PC wins and the
 * other one stays a restore point. While playing it pushes every 10 minutes if anything changed, and once more when
 * the game closes.
 *
 * <p><b>Restoring.</b> Profiles, HUD layout and keybinds switch over at once; everything else is written at the next
 * start, before any of it is loaded ({@link #applyPendingAtStartup}), so nothing in memory can overwrite it again.
 */
public final class CloudSync {

	/** Config files that travel, by exact name. Anything not listed (accounts, proofs, caches, window state) never does. */
	private static final Set<String> CONFIG_FILES = Set.of("theme.json", "custom-themes.json", "active-profile.json", "crosshair-equip.json",
			"menu-key.json", "mode-presets.json", "quick-toggles.json", "command-keybinds.json", "waypoints.json", "waypoints-v2.json",
			"loadouts.json", "loadout-active.txt", "screens.json", "social-notifications.json", "redstone-mode.json", "storage-finder.json",
			"velo-target-blocks.json", "terminator-kits.json", "cosmetics-aura.json", "cosmetics-cape.json", "cosmetics-wings.json",
			"plus-badge.json", "name-style.json", "name-style-viewer.json", "currency.json");
	/** Whole folders under the Velo root that travel. */
	private static final String[] FOLDERS = {"saved-profiles", "profiles", "crosshairs"};
	private static final String KEYBINDS = "minecraft/keybinds.txt";
	private static final long MAX_FILE_BYTES = 1024 * 1024;
	private static final long AUTO_INTERVAL_MILLIS = 10 * 60 * 1000;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** {@code lastHash}: what was last synced (both sides had it); {@code lastSyncAt}: when. */
	public record State(boolean enabled, String lastHash, long lastSyncAt, String lastResult) {
	}

	/** A restore point, as the server lists it. */
	public record Backup(String id, long createdAt, long size, String sha256, String device, String label, boolean auto, boolean pinned, int files) {
	}

	public record Listing(boolean plus, List<Backup> backups, long usedBytes, long maxBytes, int maxCount) {
	}

	private static volatile State state;
	private static volatile boolean busy;
	private static long nextAuto;
	private static boolean startupChecked;

	private CloudSync() {
	}

	// ---- State ----

	private static Path pendingFile() {
		return VeloPaths.root().resolve("cloud").resolve("pending-restore.zip");
	}

	public static State state() {
		State s = state;
		if (s == null) {
			s = net.veloclient.velo.config.ConfigManager.load("cloud-sync", State.class, new State(true, null, 0, null));
			state = s;
		}
		return s;
	}

	private static void setState(State s) {
		state = s;
		net.veloclient.velo.config.ConfigManager.save("cloud-sync", s);
	}

	public static void setEnabled(boolean enabled) {
		State s = state();
		setState(new State(enabled, s.lastHash(), s.lastSyncAt(), s.lastResult()));
	}

	public static boolean busy() {
		return busy;
	}

	/** This PC's name, shown next to its restore points. */
	public static String deviceName() {
		String name = System.getenv("COMPUTERNAME");
		if (name == null || name.isBlank()) {
			name = System.getenv("HOSTNAME");
		}
		if (name == null || name.isBlank()) {
			try {
				name = java.net.InetAddress.getLocalHost().getHostName();
			} catch (Exception e) {
				name = System.getProperty("os.name", "PC");
			}
		}
		return name.length() > 40 ? name.substring(0, 40) : name;
	}

	// ---- Snapshot ----

	/** Everything that travels, as {path in the zip -> bytes}, sorted (so the same setup always makes the same zip). */
	static Map<String, byte[]> collect() throws IOException {
		Map<String, byte[]> files = new TreeMap<>();
		Path root = VeloPaths.root();
		for (String name : CONFIG_FILES) {
			Path file = VeloPaths.config().resolve(name);
			if (Files.isRegularFile(file) && Files.size(file) <= MAX_FILE_BYTES) {
				files.put("config/" + name, Files.readAllBytes(file));
			}
		}
		for (String folder : FOLDERS) {
			Path dir = root.resolve(folder);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> walk = Files.walk(dir, 4)) {
				for (Path file : walk.filter(Files::isRegularFile).toList()) {
					if (Files.size(file) <= MAX_FILE_BYTES) {
						files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
					}
				}
			}
		}
		String keys = keybindLines();
		if (!keys.isEmpty()) {
			files.put(KEYBINDS, keys.getBytes(StandardCharsets.UTF_8));
		}
		return files;
	}

	static byte[] zip(Map<String, byte[]> files) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> file : files.entrySet()) {
				ZipEntry entry = new ZipEntry(file.getKey());
				entry.setTime(0);
				zip.putNextEntry(entry);
				zip.write(file.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	static Map<String, byte[]> unzip(byte[] zip) throws IOException {
		Map<String, byte[]> files = new TreeMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (!entry.isDirectory() && allowed(entry.getName())) {
					files.put(entry.getName(), in.readNBytes((int) MAX_FILE_BYTES + 1));
				}
			}
		}
		return files;
	}

	/** Only paths a backup can contain - a tampered zip can't write anywhere else. */
	static boolean allowed(String name) {
		if (name.contains("..") || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
			return false;
		}
		if (name.equals(KEYBINDS)) {
			return true;
		}
		if (name.startsWith("config/")) {
			return CONFIG_FILES.contains(name.substring("config/".length()));
		}
		for (String folder : FOLDERS) {
			if (name.startsWith(folder + "/")) {
				return true;
			}
		}
		return false;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** The current setup as a zip (the live profile and keybinds saved to disk first). */
	private static byte[] snapshot() throws Exception {
		CompletableFuture<Void> flushed = new CompletableFuture<>();
		MinecraftClient client = MinecraftClient.getInstance();
		Runnable flush = () -> {
			try {
				net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
				writeOptions();
			} catch (Throwable t) {
				VeloClient.LOGGER.debug("Couldn't flush settings before a backup", t);
			}
			flushed.complete(null);
		};
		//? if <26.1 {
		boolean onThread = client.isOnThread();
		//?} else {
		/*boolean onThread = client.isSameThread();
		*///?}
		if (onThread) {
			flush.run();
		} else {
			client.execute(flush);
			flushed.get(5, java.util.concurrent.TimeUnit.SECONDS);
		}
		return zip(collect());
	}

	// ---- Keybinds (options.txt) ----

	private static Path optionsFile() {
		return FabricLoader.getInstance().getGameDir().resolve("options.txt");
	}

	private static String keybindLines() throws IOException {
		if (!Files.exists(optionsFile())) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (String line : Files.readAllLines(optionsFile(), StandardCharsets.UTF_8)) {
			if (line.startsWith("key_")) {
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	private static void writeOptions() {
		//? if <26.1 {
		MinecraftClient.getInstance().options.write();
		//?} else {
		/*MinecraftClient.getInstance().options.save();
		*///?}
	}

	/** Puts the saved keybinds on now (render thread) and saves options.txt. */
	private static void applyKeybinds(String lines) {
		Map<String, String> saved = new LinkedHashMap<>();
		for (String line : lines.split("\n")) {
			int colon = line.indexOf(':');
			if (line.startsWith("key_") && colon > 4) {
				saved.put(line.substring(4, colon), line.substring(colon + 1).trim());
			}
		}
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		for (net.minecraft.client.option.KeyBinding binding : client.options.allKeys) {
			String value = saved.get(binding.getId());
			if (value != null) {
				binding.setBoundKey(net.minecraft.client.util.InputUtil.fromTranslationKey(value));
			}
		}
		net.minecraft.client.option.KeyBinding.updateKeysByCode();
		client.options.write();
		//?} else {
		/*for (net.minecraft.client.KeyMapping binding : client.options.keyMappings) {
			String value = saved.get(binding.getName());
			if (value != null) {
				binding.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(value));
			}
		}
		net.minecraft.client.KeyMapping.resetMapping();
		client.options.save();
		*///?}
	}

	// ---- Applying a backup ----

	/** At mod start-up, before anything reads its settings: writes a restore that was waiting for this start. */
	public static void applyPendingAtStartup() {
		Path pending = pendingFile();
		if (!Files.exists(pending)) {
			return;
		}
		try {
			Map<String, byte[]> files = unzip(Files.readAllBytes(pending));
			writeFiles(files);
			Files.deleteIfExists(pending);
			VeloClient.LOGGER.info("Cloud Sync: restored {} files", files.size());
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Cloud Sync: couldn't finish the restore", e);
		}
	}

	private static void writeFiles(Map<String, byte[]> files) throws IOException {
		Path root = VeloPaths.root();
		for (Map.Entry<String, byte[]> file : files.entrySet()) {
			if (file.getKey().equals(KEYBINDS)) {
				continue;
			}
			Path target = root.resolve(file.getKey()).normalize();
			if (!target.startsWith(root)) {
				continue;
			}
			Files.createDirectories(target.getParent());
			Path temp = target.resolveSibling(target.getFileName() + ".cloud-tmp");
			Files.write(temp, file.getValue());
			Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/**
	 * Applies a backup: profiles (module settings + HUD) and keybinds now, the rest at the next start. Must run on the
	 * render thread.
	 */
	private static void apply(byte[] zip) throws IOException {
		Map<String, byte[]> files = unzip(zip);
		// Profiles first, straight to disk, then load the active one into the running game.
		Map<String, byte[]> now = new TreeMap<>();
		for (Map.Entry<String, byte[]> file : files.entrySet()) {
			if (file.getKey().startsWith("saved-profiles/") || file.getKey().equals("config/active-profile.json")) {
				now.put(file.getKey(), file.getValue());
			}
		}
		writeFiles(now);
		String name = net.veloclient.velo.profile.VeloProfileManager.activeName();
		net.veloclient.velo.profile.VeloProfileManager.loadByName(name).ifPresent(net.veloclient.velo.client.profile.VeloProfileStore::applyToLive);
		byte[] keys = files.get(KEYBINDS);
		if (keys != null) {
			applyKeybinds(new String(keys, StandardCharsets.UTF_8));
		}
		// Everything else waits for the next start.
		Files.createDirectories(pendingFile().getParent());
		Files.write(pendingFile(), zip);
	}

	// ---- Server ----

	private static HttpRequest.Builder request(String path) {
		String base = VeloServerClient.serverBaseUrl();
		String token = VeloServerClient.sessionToken();
		if (base == null || token == null) {
			throw new IllegalStateException("Not connected to the Velo Network yet");
		}
		return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token);
	}

	private static String send(HttpRequest request) throws Exception {
		HttpResponse<String> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			String message = "HTTP " + response.statusCode();
			try {
				message = JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString();
			} catch (Exception ignored) {
				// Not JSON.
			}
			throw new IOException(message);
		}
		return response.body();
	}

	public static Listing list() throws Exception {
		JsonObject json = JsonParser.parseString(send(request("/v1/cloud/backups").GET().build())).getAsJsonObject();
		List<Backup> backups = new ArrayList<>();
		for (JsonElement e : json.getAsJsonArray("backups")) {
			backups.add(GSON.fromJson(e, Backup.class));
		}
		return new Listing(json.get("plus").getAsBoolean(), backups, json.get("usedBytes").getAsLong(), json.get("maxBytes").getAsLong(),
				json.get("maxCount").getAsInt());
	}

	private static Backup upload(byte[] zip, String label, boolean auto) throws Exception {
		String query = "?device=" + URLEncoder.encode(deviceName(), StandardCharsets.UTF_8) + (label == null ? ""
				: "&label=" + URLEncoder.encode(label, StandardCharsets.UTF_8)) + (auto ? "&auto=1" : "");
		String body = send(request("/v1/cloud/backups/upload" + query).header("Content-Type", "application/zip")
				.POST(HttpRequest.BodyPublishers.ofByteArray(zip)).build());
		return GSON.fromJson(body, Backup.class);
	}

	private static byte[] download(String id) throws Exception {
		HttpResponse<byte[]> response = VeloServerClient.HTTP.send(request("/v1/cloud/backups/file?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8))
				.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		return response.body();
	}

	public static void delete(String id) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		send(request("/v1/cloud/backups/delete").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
	}

	public static void pin(String id, boolean pinned, String label) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		body.addProperty("pinned", pinned);
		if (label != null) {
			body.addProperty("label", label);
		}
		send(request("/v1/cloud/backups/pin").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
	}

	// ---- What the game does on its own ----

	private static boolean member() {
		return net.veloclient.velo.client.network.StoreClient.plus();
	}

	private static void done(String result, String hash) {
		State s = state();
		setState(new State(s.enabled(), hash == null ? s.lastHash() : hash, hash == null ? s.lastSyncAt() : System.currentTimeMillis(), result));
	}

	/** Called every client tick: the start-up check once signed in, then the 10-minute push. Never blocks the tick. */
	public static void tick() {
		if (busy || !state().enabled() || VeloServerClient.sessionToken() == null || !member()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!startupChecked) {
			startupChecked = true;
			nextAuto = now + AUTO_INTERVAL_MILLIS;
			run(CloudSync::syncOnStart);
		} else if (now >= nextAuto) {
			nextAuto = now + AUTO_INTERVAL_MILLIS;
			run(() -> pushIfChanged(true));
		}
	}

	/** The game is closing: one last push if anything changed (a few seconds at most). */
	public static void onStop() {
		if (!state().enabled() || VeloServerClient.sessionToken() == null || !member() || busy || Files.exists(pendingFile())) {
			return;
		}
		try {
			byte[] zip = zip(collect());
			String hash = sha256(zip);
			if (!hash.equals(state().lastHash())) {
				upload(zip, null, true);
				done("Backed up when the game closed", hash);
			}
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Cloud Sync: no backup on exit", e);
		}
	}

	private static void run(ThrowingRunnable task) {
		busy = true;
		Thread.ofVirtual().name("velo-cloud-sync").start(() -> {
			try {
				task.run();
			} catch (Exception e) {
				VeloClient.LOGGER.debug("Cloud Sync failed", e);
				done("Couldn't sync: " + e.getMessage(), null);
			} finally {
				busy = false;
			}
		});
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static void syncOnStart() throws Exception {
		Listing listing = list();
		byte[] local = snapshot();
		String localHash = sha256(local);
		String last = state().lastHash();
		Backup newest = listing.backups().isEmpty() ? null : listing.backups().get(0);
		if (newest == null) {
			upload(local, "First backup", false);
			done("First backup made", localHash);
			return;
		}
		if (newest.sha256().equals(localHash)) {
			done("Up to date", localHash);
			return;
		}
		boolean localChanged = !localHash.equals(last);
		boolean cloudChanged = !newest.sha256().equals(last);
		if (cloudChanged && !localChanged) {
			byte[] zip = download(newest.id());
			CompletableFuture<Exception> applied = new CompletableFuture<>();
			MinecraftClient.getInstance().execute(() -> {
				try {
					apply(zip);
					applied.complete(null);
				} catch (Exception e) {
					applied.complete(e);
				}
			});
			Exception failed = applied.get(10, java.util.concurrent.TimeUnit.SECONDS);
			if (failed != null) {
				throw failed;
			}
			done("Got your settings from " + (newest.device() == null ? "another PC" : newest.device()), newest.sha256());
			toast("Settings synced", "From " + (newest.device() == null ? "your other PC" : newest.device())
					+ ". Theme and waypoints finish on your next start.");
			return;
		}
		// Only this PC changed, or both did: this PC wins, the cloud's newest stays as a restore point.
		upload(local, cloudChanged ? "Kept this PC (both changed)" : null, !cloudChanged);
		done(cloudChanged ? "Both PCs changed - kept this one, the other is a restore point" : "Backed up", localHash);
	}

	private static void pushIfChanged(boolean auto) throws Exception {
		if (Files.exists(pendingFile())) {
			return; // a restore finishes at the next start - until then this PC's files are half old
		}
		byte[] zip = snapshot();
		String hash = sha256(zip);
		if (hash.equals(state().lastHash())) {
			return;
		}
		upload(zip, null, auto);
		done("Backed up", hash);
	}

	/** "Back up now" (with an optional name). {@code then} gets null or the error, on the render thread. */
	public static void backupNow(String label, Consumer<String> then) {
		run(() -> {
			String error = null;
			try {
				byte[] zip = snapshot();
				upload(zip, label, false);
				done("Backed up", sha256(zip));
			} catch (Exception e) {
				error = e.getMessage();
			}
			String result = error;
			MinecraftClient.getInstance().execute(() -> then.accept(result));
		});
	}

	/** Restores a restore point; first backs up what you have now ("Before restore"), so nothing is ever lost. */
	public static void restore(Backup backup, Consumer<String> then) {
		run(() -> {
			String error = null;
			try {
				try {
					upload(snapshot(), "Before restore", false);
				} catch (IOException e) {
					// Cloud full of pinned points or identical: restore anyway.
				}
				byte[] zip = download(backup.id());
				CompletableFuture<Exception> applied = new CompletableFuture<>();
				MinecraftClient.getInstance().execute(() -> {
					try {
						apply(zip);
						applied.complete(null);
					} catch (Exception e) {
						applied.complete(e);
					}
				});
				Exception failed = applied.get(10, java.util.concurrent.TimeUnit.SECONDS);
				if (failed != null) {
					throw failed;
				}
				done("Restored " + new java.text.SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(new java.util.Date(backup.createdAt())),
						backup.sha256());
			} catch (Exception e) {
				error = e.getMessage();
			}
			String result = error;
			MinecraftClient.getInstance().execute(() -> then.accept(result));
		});
	}

	private static void toast(String title, String text) {
		MinecraftClient.getInstance().execute(() -> net.veloclient.velo.client.social.NotificationOverlay.show(
				new net.veloclient.velo.client.social.NotificationOverlay.Toast(null, null, title, text, 6000, null, null, null)));
	}
}
