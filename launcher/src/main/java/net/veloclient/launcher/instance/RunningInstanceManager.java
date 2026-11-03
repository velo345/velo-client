package net.veloclient.launcher.instance;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks every currently-running game process launched from this launcher,
 * across all instances - including several concurrent launches of the exact
 * same {@link Instance} (multi-instancing). The sidebar's "Running" section
 * and each instance's own detail view both read from {@link #running()}.
 */
public final class RunningInstanceManager {

	/** One live launch. {@link #stop()} is idempotent - safe to call from a "Stop" button even after the process already exited on its own. */
	public static final class RunningInstance {
		private final String runId;
		private final Instance instance;
		private final Process process;
		private final long startedAtEpochMillis;
		private final Path logFile;
		private final String serverAddress;
		private final AtomicBoolean stopRequested = new AtomicBoolean();

		private RunningInstance(Instance instance, Process process, Path logFile, String serverAddress) {
			this.runId = UUID.randomUUID().toString();
			this.instance = instance;
			this.process = process;
			this.logFile = logFile;
			this.serverAddress = serverAddress;
			this.startedAtEpochMillis = System.currentTimeMillis();
		}

		public String runId() {
			return runId;
		}

		public Instance instance() {
			return instance;
		}

		public Process process() {
			return process;
		}

		public long startedAtEpochMillis() {
			return startedAtEpochMillis;
		}

		public Path logFile() {
			return logFile;
		}

		/** Nullable - "host:port" if this run was launched straight into a server (Quick Play), null for a plain launch to the title screen/singleplayer. */
		public String serverAddress() {
			return serverAddress;
		}

		public boolean isAlive() {
			return process.isAlive();
		}

		/** True once {@link #stop()} has been called - a non-zero exit after this is an expected, deliberate shutdown, not a crash. */
		public boolean wasStopped() {
			return stopRequested.get();
		}

		/**
		 * Stop button: asks the game to close like its window's X (it saves the world and settings), and only if it's
		 * still running after 15 s ends the process. On Windows {@link Process#destroy()} is a hard kill - it used to
		 * be the first and only step, so Stop and Relaunch lost unsaved progress.
		 */
		public void stop() {
			if (stopRequested.compareAndSet(false, true)) {
				if (!requestClose()) {
					process.destroy();
					return;
				}
				process.onExit().orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).whenComplete((p, timeout) -> {
					if (timeout != null && process.isAlive()) {
						process.destroy();
					}
				});
			}
		}

		/**
		 * Asks the running game to quit on its own - the Velo mod checks {@code velo/close-request} in the profile every
		 * second and shuts down the normal way. False when the request couldn't be written.
		 */
		public boolean requestClose() {
			stopRequested.set(true);
			try {
				Path request = InstancePaths.gameDir(instance.id()).resolve("velo").resolve("close-request");
				java.nio.file.Files.createDirectories(request.getParent());
				java.nio.file.Files.writeString(request, String.valueOf(process.pid()));
				return true;
			} catch (Exception e) {
				return false;
			}
		}
	}

	private static final ObservableList<RunningInstance> RUNNING = FXCollections.observableArrayList();

	private RunningInstanceManager() {
	}

	/** Live view of every running launch - safe to bind UI directly to this (always mutated on the JavaFX thread). */
	public static ObservableList<RunningInstance> running() {
		return RUNNING;
	}

	/** @param serverAddress nullable - see {@link RunningInstance#serverAddress()}. */
	public static RunningInstance register(Instance instance, Process process, Path logFile, String serverAddress) {
		RunningInstance entry = new RunningInstance(instance, process, logFile, serverAddress);
		if (Platform.isFxApplicationThread()) {
			RUNNING.add(entry);
		} else {
			Platform.runLater(() -> RUNNING.add(entry));
		}
		MemoryGuard.watch(instance, process);
		Thread.ofVirtual().start(() -> {
			try {
				process.waitFor();
				net.veloclient.launcher.data.PlayTimeStore.record(instance.id(), System.currentTimeMillis() - entry.startedAtEpochMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			net.veloclient.launcher.social.LauncherSocial.gameEnded();
			Platform.runLater(() -> RUNNING.remove(entry));
		});
		return entry;
	}

	public static long countRunning(String instanceId) {
		return RUNNING.stream().filter(r -> r.instance().id().equals(instanceId)).count();
	}

	public static void stopAll() {
		for (RunningInstance instance : List.copyOf(RUNNING)) {
			instance.stop();
		}
	}
}
