package net.veloclient.velo.dev.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Discord's local RPC, no library: frames of {opcode (int32 LE), length (int32 LE), JSON}. Unix socket
 * {@code discord-ipc-N} on Linux / macOS (also the Flatpak and Snap Discord), named pipe {@code \\.\pipe\discord-ipc-N}
 * on Windows. Opcode 0 = handshake, 1 = frame (commands such as SET_ACTIVITY).
 */
final class DiscordIpc implements Closeable {

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	private final SocketChannel unix;
	private final RandomAccessFile pipe;

	private DiscordIpc(SocketChannel unix, RandomAccessFile pipe) {
		this.unix = unix;
		this.pipe = pipe;
	}

	/** Connects and shakes hands with {@code clientId}; throws when Discord isn't running. */
	static DiscordIpc open(String clientId) throws IOException {
		IOException last = new IOException("Discord isn't running");
		for (int i = 0; i < 10; i++) {
			DiscordIpc ipc = null;
			try {
				if (WINDOWS) {
					ipc = new DiscordIpc(null, new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw"));
				} else {
					for (Path dir : socketDirs()) {
						Path socket = dir.resolve("discord-ipc-" + i);
						if (Files.exists(socket)) {
							SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
							channel.connect(UnixDomainSocketAddress.of(socket));
							ipc = new DiscordIpc(channel, null);
							break;
						}
					}
				}
				if (ipc == null) {
					continue;
				}
				JsonObject hello = new JsonObject();
				hello.addProperty("v", 1);
				hello.addProperty("client_id", clientId);
				ipc.write(0, hello);
				JsonObject ready = ipc.read();
				if (ready != null && "READY".equals(ready.has("evt") && !ready.get("evt").isJsonNull() ? ready.get("evt").getAsString() : "")) {
					return ipc;
				}
				ipc.close();
			} catch (IOException e) {
				last = e;
				if (ipc != null) {
					ipc.close();
				}
			}
		}
		throw last;
	}

	private static List<Path> socketDirs() {
		List<Path> dirs = new ArrayList<>();
		for (String env : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
			String v = System.getenv(env);
			if (v != null && !v.isBlank()) {
				Path base = Path.of(v);
				dirs.add(base);
				dirs.add(base.resolve("app/com.discordapp.Discord")); // Flatpak
				dirs.add(base.resolve("snap.discord")); // Snap
				dirs.add(base.resolve(".flatpak/dev.vencord.Vesktop/xdg-run")); // Vesktop
			}
		}
		dirs.add(Path.of("/tmp"));
		return dirs;
	}

	/** SET_ACTIVITY; {@code activity} null clears the status. */
	void setActivity(JsonObject activity) throws IOException {
		JsonObject args = new JsonObject();
		args.addProperty("pid", ProcessHandle.current().pid());
		if (activity != null) {
			args.add("activity", activity);
		}
		JsonObject frame = new JsonObject();
		frame.addProperty("cmd", "SET_ACTIVITY");
		frame.add("args", args);
		frame.addProperty("nonce", UUID.randomUUID().toString());
		write(1, frame);
		read();
	}

	private void write(int opcode, JsonObject json) throws IOException {
		byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(opcode).putInt(body.length).put(body).flip();
		if (unix != null) {
			while (buf.hasRemaining()) {
				unix.write(buf);
			}
		} else {
			pipe.write(buf.array());
		}
	}

	private JsonObject read() throws IOException {
		ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		readFully(header.array());
		header.getInt();
		int length = header.getInt();
		if (length < 0 || length > 1 << 20) {
			throw new IOException("bad Discord frame");
		}
		byte[] body = new byte[length];
		readFully(body);
		try {
			return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void readFully(byte[] into) throws IOException {
		int off = 0;
		while (off < into.length) {
			int n;
			if (unix != null) {
				n = unix.read(ByteBuffer.wrap(into, off, into.length - off));
			} else {
				n = pipe.read(into, off, into.length - off);
			}
			if (n < 0) {
				throw new IOException("Discord closed the connection");
			}
			off += n;
		}
	}

	@Override
	public final void close() { // not "public void close() {": a Stonecutter rule renames that shape for Screens
		try {
			if (unix != null) {
				unix.close();
			}
			if (pipe != null) {
				pipe.close();
			}
		} catch (IOException ignored) {
			// Closing anyway.
		}
	}
}
