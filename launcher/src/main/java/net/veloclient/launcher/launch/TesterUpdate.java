package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.social.TesterApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The Velo Client Tester launcher updates itself from the Velo server (tester builds are never public GitHub
 * releases): the newest build's app zip for this platform is downloaded with the tester's session and unpacked like
 * the installer-free update ({@link AppUpdate#installZip}) - the next start runs it. Only in a tester (dev) build.
 */
public final class TesterUpdate {

	private TesterUpdate() {
	}

	/** What a check found: the newest build (null = none), and whether it's ready to start (downloaded + unpacked). */
	public record Result(JsonObject newest, boolean newer, boolean ready, String problem) {
	}

	private static volatile String installed;

	/** The tester build waiting for a restart (unpacked this session), or null. */
	public static String readyVersion() {
		return installed;
	}

	/** Blocking: lists the builds and, when there's a newer one, downloads and unpacks it. */
	public static Result check(Consumer<String> status, Consumer<Double> progress) {
		if (!AppVersion.DEV) {
			return new Result(null, false, false, "Not a tester build");
		}
		JsonArray builds;
		try {
			builds = TesterApi.builds();
		} catch (TesterApi.TesterError e) {
			return new Result(null, false, false, e.status == 404 ? "not-tester" : e.getMessage());
		}
		if (builds.isEmpty()) {
			return new Result(null, false, false, null);
		}
		JsonObject newest = builds.get(0).getAsJsonObject();
		String version = newest.get("version").getAsString();
		if (UpdateService.compare(version, AppVersion.VERSION) <= 0) {
			return new Result(newest, false, false, null);
		}
		if (version.equals(installed) || Files.isRegularFile(AppUpdate.root().resolve(version).resolve("launcher.jar"))) {
			installed = version;
			return new Result(newest, true, true, null);
		}
		String suffix = "-app-" + AppUpdate.platform() + ".zip";
		String zipName = null;
		long size = 0;
		for (JsonElement f : newest.getAsJsonArray("files")) {
			String name = f.getAsJsonObject().get("name").getAsString();
			if (name.endsWith(suffix)) {
				zipName = name;
				size = f.getAsJsonObject().get("size").getAsLong();
			}
		}
		if (zipName == null) {
			return new Result(newest, true, false, "This build isn't out for " + AppUpdate.platform() + " yet");
		}
		try {
			Path zip = UpdateService.updatesDir().resolve(zipName);
			status.accept("Downloading tester build " + version + "...");
			TesterApi.download(version, zipName, size, zip, progress);
			AppUpdate.installZip(zip, version, status);
			installed = version;
			LauncherLog.info("Tester build " + version + " unpacked - starts with the next launch");
			return new Result(newest, true, true, null);
		} catch (Exception e) {
			LauncherLog.warn("Tester update failed", e);
			return new Result(newest, true, false, e.getMessage());
		}
	}

	/** Starts the launcher again (it then runs the unpacked build) and closes this one. */
	public static void restartNow() {
		UpdateService.relaunch();
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(600);
			} catch (InterruptedException ignored) {
				// close anyway
			}
			javafx.application.Platform.runLater(() -> {
				javafx.application.Platform.exit();
				System.exit(0);
			});
		}, "velo-tester-restart");
		t.setDaemon(true);
		t.start();
	}
}
