package net.veloclient.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldHostTest {

	private static final String HOST = "0123456789abcdef0123456789abcdef";
	private static final String STRANGER = "ffffffffffffffffffffffffffffffff";

	@TempDir
	Path dir;

	private static String line(InputStream in) throws Exception {
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = in.read()) != -1 && c != '\n') {
			sb.append((char) c);
		}
		return sb.toString();
	}

	@Test
	void relayPipesAGuestToTheHostsWorld() throws Exception {
		WorldHostService worlds = new WorldHostService(new SocialService(dir, new SessionRegistry()), 0, "localhost");
		worlds.start();
		WorldHostService.World world = worlds.open(HOST, "Host", "My World", "26.2", false);
		assertThrows(ApiError.class, () -> worlds.ticket(world, HOST, "Host"), "not reachable before the host connects");

		Socket control = new Socket("localhost", worlds.port());
		control.getOutputStream().write(("HOST " + world.hostKey + "\n").getBytes(StandardCharsets.UTF_8));
		InputStream controlIn = control.getInputStream();
		assertEquals("OK " + world.id, line(controlIn));
		for (int i = 0; i < 50 && !world.online(); i++) {
			Thread.sleep(10);
		}
		assertThrows(ApiError.class, () -> worlds.ticket(world, STRANGER, "Stranger"), "strangers aren't invited");

		String ticket = worlds.ticket(world, HOST, "Host");
		Socket guest = new Socket("localhost", worlds.port());
		OutputStream guestOut = guest.getOutputStream();
		guestOut.write(("JOIN " + ticket + "\n").getBytes(StandardCharsets.UTF_8));
		guestOut.write("hello".getBytes(StandardCharsets.UTF_8));
		guestOut.flush();

		String open = line(controlIn);
		assertTrue(open.startsWith("OPEN "), open);
		String stream = open.split(" ")[1];
		Socket data = new Socket("localhost", worlds.port());
		data.getOutputStream().write(("DATA " + world.hostKey + " " + stream + "\n").getBytes(StandardCharsets.UTF_8));
		data.getOutputStream().flush();
		byte[] got = data.getInputStream().readNBytes(5);
		assertEquals("hello", new String(got, StandardCharsets.UTF_8), "guest bytes reach the host");
		data.getOutputStream().write("world".getBytes(StandardCharsets.UTF_8));
		data.getOutputStream().flush();
		InputStream guestIn = guest.getInputStream();
		assertEquals("OK", line(guestIn));
		assertEquals("world", new String(guestIn.readNBytes(5), StandardCharsets.UTF_8), "host bytes reach the guest");
		assertThrows(ApiError.class, () -> worlds.ticket(world, STRANGER, "x"));
		guest.close();
		data.close();
		control.close();
	}
}
