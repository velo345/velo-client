package net.veloclient.server;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The oldest Velo Client version that may still be used ({@code <data>/update-policy.json}), set by the owner when a
 * hotfix has to reach everyone. Launchers (0.9.91+) below it open the update dialog on start and before Play.
 * {@code message}: optional line shown in that dialog (what the update fixes).
 */
final class UpdatePolicy {

	record Policy(String minimum, String message) {
	}

	private static final Gson GSON = new Gson();

	private final Path file;
	private volatile Policy policy = new Policy(null, null);

	UpdatePolicy(Path dataDir) throws IOException {
		this.file = dataDir.resolve("update-policy.json");
		if (Files.exists(file)) {
			Policy loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Policy.class);
			if (loaded != null) {
				policy = loaded;
			}
		}
	}

	Policy get() {
		return policy;
	}

	synchronized Policy set(String minimum, String message) throws ApiError, IOException {
		String min = minimum == null || minimum.isBlank() ? null : minimum.trim();
		if (min != null && !min.matches("[0-9]{1,4}(\\.[0-9]{1,4}){1,3}")) {
			throw new ApiError(400, "The minimum version must look like 0.9.91");
		}
		Policy next = new Policy(min, CloudBackupService.clean(message, 200));
		Path temp = Files.createTempFile(file.getParent(), "update-policy-", ".tmp");
		Files.writeString(temp, GSON.toJson(next), StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		policy = next;
		return next;
	}

	/** Whether {@code version} is older than {@code minimum} (dotted numbers; "0.9.91" is newer than "0.9.9"). */
	static boolean older(String version, String minimum) {
		if (minimum == null) {
			return false;
		}
		if (version == null) {
			return true;
		}
		String[] a = version.split("[.+-]");
		String[] b = minimum.split("[.+-]");
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? number(a[i]) : 0;
			int y = i < b.length ? number(b[i]) : 0;
			if (x != y) {
				return x < y;
			}
		}
		return false;
	}

	private static int number(String part) {
		try {
			return Integer.parseInt(part);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
