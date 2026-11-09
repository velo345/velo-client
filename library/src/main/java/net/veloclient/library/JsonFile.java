package net.veloclient.library;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small persistent documents (keys, presets, history) as JSON files, written atomically and only when changed. */
final class JsonFile<T> {

	private final Path file;
	private final Type type;
	private T value;
	private volatile boolean dirty;

	JsonFile(Path file, Type type, java.util.function.Supplier<T> empty) {
		this.file = file;
		this.type = type;
		T loaded = null;
		try {
			if (Files.exists(file)) {
				loaded = Http.GSON.fromJson(Files.readString(file), type);
			}
		} catch (Exception e) {
			System.out.println("[library] couldn't read " + file + ": " + e.getMessage());
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis()));
			} catch (IOException ignored) {
				// keep going with an empty document
			}
		}
		value = loaded != null ? loaded : empty.get();
	}

	synchronized T get() {
		return value;
	}

	void changed() {
		dirty = true;
	}

	synchronized void saveIfDirty() {
		if (!dirty) {
			return;
		}
		dirty = false;
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Http.GSON.toJson(value, type));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			dirty = true;
			System.out.println("[library] couldn't save " + file + ": " + e.getMessage());
		}
	}
}
