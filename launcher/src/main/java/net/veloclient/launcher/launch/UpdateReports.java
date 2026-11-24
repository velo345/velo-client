package net.veloclient.launcher.launch;

import com.google.gson.JsonObject;
import net.veloclient.launcher.AppVersion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Update problems report themselves: when the installer-free update fails (and the installer has to step in) or an
 * update didn't arrive after the restart, a launcher bug report with the end of update.log goes to the Velo server - so
 * the owner sees what really happens on Windows instead of "it just doesn't work". Quiet, in the background, at most
 * once per launcher run and reason; never blocks or fails the update itself.
 */
public final class UpdateReports {

	private static final java.util.Set<String> SENT = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private UpdateReports() {
	}

	public static void send(String what, String toVersion, Throwable error) {
		if (!SENT.add(what)) {
			return;
		}
		Thread t = new Thread(() -> {
			try {
				JsonObject report = new JsonObject();
				report.addProperty("kind", "bug");
				report.addProperty("source", "launcher");
				report.addProperty("area", "updater");
				report.addProperty("title", "Updater: " + what + " (" + AppVersion.VERSION + " -> " + toVersion + ")");
				report.addProperty("message", "Automatic report from the launcher's updater."
						+ (error == null ? "" : "\n\n" + AppUpdate.describe(error)));
				JsonObject sys = new JsonObject();
				sys.addProperty("Velo Launcher", AppVersion.VERSION + (AppVersion.DEV ? " (dev)" : ""));
				sys.addProperty("OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
				sys.addProperty("Java (launcher)", System.getProperty("java.version"));
				sys.addProperty("Install", String.valueOf(System.getProperty("jpackage.app-path")));
				sys.addProperty("Hopped from", String.valueOf(System.getProperty("velo.appHop")));
				report.add("system", sys);
				report.addProperty("log", net.veloclient.launcher.ui.BugReportDialog.redactSecrets(tail(400)));
				String id = net.veloclient.launcher.social.NewsApi.sendReport(report);
				UpdateService.log("[report] sent update report " + id + ": " + what);
			} catch (Throwable e) {
				UpdateService.log("[report] couldn't send the update report (" + e + ")");
			}
		}, "velo-update-report");
		t.setDaemon(true);
		t.start();
	}

	/** The last {@code lines} lines of update.log. */
	static String tail(int lines) {
		try {
			List<String> all = Files.readAllLines(UpdateService.logFile(), StandardCharsets.UTF_8);
			return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
		} catch (Exception e) {
			return "(update.log unreadable: " + e + ")";
		}
	}
}
