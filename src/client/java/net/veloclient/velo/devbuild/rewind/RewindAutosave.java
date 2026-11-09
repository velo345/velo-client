package net.veloclient.velo.devbuild.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Saves a recording's edits after every action without the disk (or the game) suffering when actions come fast (plain
 * Java). An action only marks the edits changed; once things settle (0.4 s quiet, or 2 s into a burst such as dragging a
 * slider) they are written - at most once a second, off the game thread, atomically (temp file + move, so a crash
 * mid-write can't leave a broken file). Every write is also an undo step. Backups go to
 * {@code velo-rewind/backups/<recording>/}: at most one per 20 s, only when something changed, the newest 30 kept.
 */
final class RewindAutosave {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long QUIET_MS = 400;
	private static final long BURST_MS = 2000;
	private static final long MIN_WRITE_MS = 1000;
	private static final long BACKUP_EVERY_MS = 20_000;
	private static final int BACKUPS_KEPT = 30;
	private static final int UNDO_STEPS = 100;

	// one writer thread for every recording: the newest pending content wins, older ones are skipped
	private static final AtomicReference<Object[]> PENDING = new AtomicReference<>();
	private static final Object LOCK = new Object();
	private static Thread thread;
	private static volatile boolean writing;

	private final Path recording;
	private long firstChange;
	private long lastChange;
	private long lastWrite;
	private long lastBackup;
	private String lastBackupJson;
	private final List<String> history = new ArrayList<>();
	private int index = -1;

	RewindAutosave(Path recording, RewindEdits initial) {
		this.recording = recording;
		push(GSON.toJson(initial));
		lastBackupJson = history.get(0);
	}

	/** Something in the edits changed (cheap: call it after every action, every frame of a drag). */
	void changed() {
		long now = System.currentTimeMillis();
		if (firstChange == 0) {
			firstChange = now;
		}
		lastChange = now;
	}

	/** Every game tick: writes the edits once the changes settle. */
	void tick(RewindEdits edits) {
		if (firstChange == 0) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean settled = now - lastChange >= QUIET_MS || now - firstChange >= BURST_MS;
		if (settled && now - lastWrite >= MIN_WRITE_MS) {
			commit(edits, false);
		}
	}

	/** Writes now and waits for it (leaving the replay, closing the game). */
	void flush(RewindEdits edits) {
		if (firstChange != 0) {
			commit(edits, true);
		}
		drain();
	}

	private void commit(RewindEdits edits, boolean backupNow) {
		firstChange = 0;
		lastWrite = System.currentTimeMillis();
		String json = GSON.toJson(edits);
		if (json.equals(history.get(index))) {
			return; // nothing really changed (a click that set the same value)
		}
		push(json);
		boolean backup = !json.equals(lastBackupJson) && (backupNow || lastWrite - lastBackup >= BACKUP_EVERY_MS);
		if (backup) {
			lastBackup = lastWrite;
			lastBackupJson = json;
		}
		write(recording, json, backup);
	}

	private void push(String json) {
		while (history.size() > index + 1) {
			history.remove(history.size() - 1);
		}
		history.add(json);
		if (history.size() > UNDO_STEPS) {
			history.remove(0);
		}
		index = history.size() - 1;
	}

	boolean canUndo() {
		return index > 0;
	}

	boolean canRedo() {
		return index < history.size() - 1;
	}

	/** The edits one step back (already saved), or null. Pending changes are committed first so they can be redone. */
	RewindEdits undo(RewindEdits current) {
		if (firstChange != 0) {
			commit(current, false);
		}
		if (!canUndo()) {
			return null;
		}
		index--;
		return restore();
	}

	RewindEdits redo() {
		if (!canRedo()) {
			return null;
		}
		index++;
		return restore();
	}

	private RewindEdits restore() {
		String json = history.get(index);
		write(recording, json, false);
		lastWrite = System.currentTimeMillis();
		return GSON.fromJson(json, RewindEdits.class);
	}

	// ---- disk ----

	static Path backupDir(Path recording) {
		String name = recording.getFileName().toString().replaceFirst("\\.vrewind$", "");
		return recording.resolveSibling("backups").resolve(name);
	}

	/** Backups of a recording's edits, newest first. */
	static List<Path> backups(Path recording) {
		try (Stream<Path> s = Files.list(backupDir(recording))) {
			return s.filter(f -> f.getFileName().toString().endsWith(".json")).sorted(Comparator.reverseOrder()).toList();
		} catch (Exception e) {
			return List.of();
		}
	}

	private static void write(Path recording, String json, boolean backup) {
		PENDING.set(new Object[] {recording, json, backup});
		synchronized (LOCK) {
			if (thread == null || !thread.isAlive()) {
				thread = Thread.ofPlatform().daemon().name("velo-rewind-autosave").start(RewindAutosave::loop);
			}
			LOCK.notifyAll();
		}
	}

	private static void loop() {
		while (true) {
			writing = true;
			Object[] job = PENDING.getAndSet(null);
			if (job == null) {
				writing = false;
				synchronized (LOCK) {
					try {
						LOCK.notifyAll(); // wakes drain()
						LOCK.wait(5000);
					} catch (InterruptedException e) {
						return;
					}
				}
				continue;
			}
			Path recording = (Path) job[0];
			String json = (String) job[1];
			try {
				Path target = RewindEdits.fileFor(recording);
				Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
				Files.writeString(tmp, json, StandardCharsets.UTF_8);
				try {
					Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				}
				if ((boolean) job[2]) {
					Path dir = backupDir(recording);
					Files.createDirectories(dir);
					String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
					Files.writeString(dir.resolve("edits-" + stamp + ".json"), json, StandardCharsets.UTF_8);
					List<Path> all = backups(recording);
					for (int i = BACKUPS_KEPT; i < all.size(); i++) {
						Files.deleteIfExists(all.get(i));
					}
				}
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger("Velo Rewind").warn("Velo Rewind couldn't save the edits of {}", recording.getFileName(), e);
			} finally {
				writing = false;
			}
		}
	}

	// Waits (up to 5 s) until nothing is left to write.
	private static void drain() {
		long until = System.currentTimeMillis() + 5000;
		synchronized (LOCK) {
			while ((PENDING.get() != null || writing) && System.currentTimeMillis() < until) {
				LOCK.notifyAll();
				try {
					LOCK.wait(100);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}
}
