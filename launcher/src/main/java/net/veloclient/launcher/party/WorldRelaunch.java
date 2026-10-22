package net.veloclient.launcher.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceIcon;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.modrinth.ModrinthClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * World Hosting, "Relaunch with matching mods": the game leaves {@code config/pending-world-join.json} (the world, its
 * Minecraft version, the host's mods and its own pid) and closes. Once that game has exited, this makes (or reuses) a
 * profile "World: <host>", installs the host's mods into it from Modrinth (by each jar's SHA-1, through
 * {@link PartyModSync}), turns the note into a plain join and launches the profile - which joins the world.
 *
 * <p>That profile is temporary: your own profiles and mods are never touched, and once the game you played the world
 * in has closed, the "World: ..." profile is deleted again (the downloaded mods stay cached, so next time is quick).
 */
public final class WorldRelaunch {

	private static boolean started;

	private WorldRelaunch() {
	}

	/**
	 * @param launch    starts an instance (on the FX thread)
	 * @param message   shows a short message to the player (title, text)
	 */
	public static synchronized void start(Consumer<Instance> launch, BiConsumer<String, String> message) {
		if (started) {
			return;
		}
		started = true;
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "velo-world-relaunch");
			t.setDaemon(true);
			return t;
		}).scheduleWithFixedDelay(() -> check(launch, message), 3, 3, TimeUnit.SECONDS);
	}

	private static Path note() {
		return VeloPaths.config().resolve("pending-world-join.json");
	}

	/** Temporary world profiles waiting for their game to be done: instance id -> when it was launched. */
	private static final java.util.Map<String, Long> TEMPORARY = new java.util.concurrent.ConcurrentHashMap<>();

	private static void check(Consumer<Instance> launch, BiConsumer<String, String> message) {
		cleanUp();
		try {
			if (!Files.exists(note())) {
				return;
			}
			JsonObject json = JsonParser.parseString(Files.readString(note(), StandardCharsets.UTF_8)).getAsJsonObject();
			if (!json.has("relaunch") || !json.get("relaunch").getAsBoolean()) {
				return;
			}
			if (System.currentTimeMillis() - json.get("at").getAsLong() > 10 * 60_000) {
				Files.deleteIfExists(note());
				return;
			}
			long pid = json.get("pid").getAsLong();
			if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
				return; // the game is still closing
			}
			String version = json.get("version").getAsString();
			String host = json.get("hostName").getAsString();
			Instance instance = profileFor(host, version);
			JsonArray mods = new JsonArray();
			List<String> notFound = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("mods")) {
				JsonObject mod = e.getAsJsonObject();
				String sha1 = mod.has("sha1") ? mod.get("sha1").getAsString() : null;
				String name = mod.has("name") ? mod.get("name").getAsString() : mod.get("id").getAsString();
				JsonObject entry = new JsonObject();
				entry.addProperty("name", name);
				entry.addProperty("file", mod.has("file") ? mod.get("file").getAsString() : mod.get("id").getAsString() + ".jar");
				if (sha1 != null) {
					entry.addProperty("sha1", sha1);
					ModrinthClient.versionByFileSha1(sha1).ifPresent(v -> {
						for (ModrinthClient.VersionFile file : v.files()) {
							if (sha1.equalsIgnoreCase(file.sha1())) {
								entry.addProperty("url", file.url());
							}
						}
					});
				}
				if (!entry.has("url")) {
					notFound.add(name);
					continue;
				}
				mods.add(entry);
			}
			JsonObject sync = new JsonObject();
			sync.addProperty("profile", host + "'s world");
			sync.addProperty("mcVersion", version);
			sync.add("mods", mods);
			PartyModSync.apply(instance, sync, host, status -> LauncherLog.info("World relaunch: " + status));
			// Now a plain join for the game that's about to start.
			JsonObject join = new JsonObject();
			join.addProperty("world", json.get("world").getAsString());
			join.addProperty("at", System.currentTimeMillis());
			Files.writeString(note(), join.toString(), StandardCharsets.UTF_8);
			Instance ready = InstanceStore.loadAll().stream().filter(i -> i.id().equals(instance.id())).findFirst().orElse(instance);
			TEMPORARY.put(ready.id(), System.currentTimeMillis());
			Platform.runLater(() -> {
				if (!notFound.isEmpty()) {
					message.accept("Some mods aren't on Modrinth", String.join(", ", notFound) + " - ask " + host + " for them.");
				}
				launch.accept(ready);
			});
		} catch (Exception e) {
			LauncherLog.warn("World relaunch failed", e);
			try {
				Files.deleteIfExists(note());
			} catch (Exception ignored) {
				// Next time.
			}
			Platform.runLater(() -> message.accept("Couldn't relaunch", e.getMessage()));
		}
	}

	/**
	 * Deletes a temporary world profile once no game runs from its folder any more (checked from a minute after launch,
	 * so the game has had time to start).
	 */
	private static void cleanUp() {
		// Also the ones from before a launcher restart (marked by a file in the profile folder).
		for (Instance instance : InstanceStore.loadAll()) {
			if (Files.exists(net.veloclient.launcher.instance.InstancePaths.dir(instance.id()).resolve(MARKER))) {
				TEMPORARY.putIfAbsent(instance.id(), 0L);
			}
		}
		for (var entry : java.util.List.copyOf(TEMPORARY.entrySet())) {
			if (System.currentTimeMillis() - entry.getValue() < 60_000) {
				continue;
			}
			String dir = net.veloclient.launcher.instance.InstancePaths.gameDir(entry.getKey()).toAbsolutePath().toString();
			boolean running = ProcessHandle.allProcesses().anyMatch(p -> p.info().commandLine().map(c -> c.contains(dir)).orElse(false));
			if (!running) {
				TEMPORARY.remove(entry.getKey());
				InstanceStore.loadAll().stream().filter(i -> i.id().equals(entry.getKey())).findFirst().ifPresent(instance -> {
					InstanceStore.delete(instance);
					LauncherLog.info("Removed the temporary world profile " + instance.name());
				});
			}
		}
	}

	/** The profile for playing in {@code host}'s world (temporary - see {@link #cleanUp}). */
	private static Instance profileFor(String host, String version) {
		String name = "World: " + host;
		for (Instance instance : InstanceStore.loadAll()) {
			if (instance.name().equals(name)) {
				if (!instance.mcVersion().equals(version)) {
					Instance changed = new Instance(instance.id(), instance.name(), version, instance.icon(), instance.createdAtEpochMillis(),
							instance.ramMinMb(), instance.ramMaxMb(), instance.extraJvmArgs());
					InstanceStore.save(changed);
					return changed;
				}
				return instance;
			}
		}
		Instance created = InstanceStore.createNew(name, version, InstanceIcon.builtin(net.veloclient.launcher.instance.BuiltinIcons.DEFAULT));
		try {
			Files.writeString(net.veloclient.launcher.instance.InstancePaths.dir(created.id()).resolve(MARKER), "Temporary profile for a hosted world");
		} catch (java.io.IOException ignored) {
			// Then it's only cleaned up this session.
		}
		return created;
	}

	private static final String MARKER = "velo-world-temporary";
}
