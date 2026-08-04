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

		public void stop() {
			if (stopRequested.compareAndSet(false, true)) {
				process.destroy();
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
		Thread.ofVirtual().start(() -> {
			try {
				process.waitFor();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
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
