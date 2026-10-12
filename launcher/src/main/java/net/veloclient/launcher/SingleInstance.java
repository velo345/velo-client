package net.veloclient.launcher;

import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Only one launcher at a time: a second start (double-clicked shortcut, start menu again...)
 * used to open a whole second JVM with its own copy of everything. Now the first instance holds
 * a lock file and listens on a loopback port; a second start just asks it to come to the front
 * and exits.
 */
public final class SingleInstance {

	private static FileLock lock;
	private static ServerSocket server;
	private static volatile Runnable onActivate = () -> { };

	private SingleInstance() {
	}

	/** True if this is the only launcher; false if another one is running (it has been asked to show itself). */
	public static boolean acquire() {
		try {
			VeloPaths.ensureDirectories();
			Path lockFile = VeloPaths.root().resolve("launcher.lock");
			Path portFile = VeloPaths.root().resolve("launcher.port");
			FileChannel channel = new RandomAccessFile(lockFile.toFile(), "rw").getChannel();
			lock = channel.tryLock();
			if (lock == null) {
				try {
					int port = Integer.parseInt(Files.readString(portFile, StandardCharsets.UTF_8).strip());
					try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
						socket.getOutputStream().write("show\n".getBytes(StandardCharsets.UTF_8));
					}
				} catch (Exception ignored) {
					// Couldn't reach it - it'll still be running; just don't start a second one.
				}
				return false;
			}
			server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
			Files.writeString(portFile, String.valueOf(server.getLocalPort()), StandardCharsets.UTF_8);
			Thread listener = new Thread(SingleInstance::listen, "velo-single-instance");
			listener.setDaemon(true);
			listener.start();
			return true;
		} catch (IOException | RuntimeException e) {
			// Locking unsupported here - better to run than to refuse to start.
			return true;
		}
	}

	private static void listen() {
		while (server != null && !server.isClosed()) {
			try (Socket socket = server.accept()) {
				socket.getInputStream().read(new byte[16]);
				onActivate.run();
			} catch (IOException e) {
				return;
			}
		}
	}

	public static void onActivate(Runnable action) {
		onActivate = action;
	}
}
