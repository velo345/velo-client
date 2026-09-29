package net.veloclient.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CapeStoreTest {

	@TempDir
	Path dir;

	private static byte[] png(int w, int h) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
		return out.toByteArray();
	}

	@Test
	void acceptsHdCapeAndServesIt() throws Exception {
		CapeStore store = new CapeStore(dir);
		String hash = store.store("a".repeat(32), png(2048, 1024));
		assertEquals(hash, store.hashFor("a".repeat(32)));
		CapeStore.StoredFile file = store.read(hash);
		assertNotNull(file);
		assertEquals(hash, CapeStore.sha256(file.bytes()));
		// Survives a restart.
		assertEquals(hash, new CapeStore(dir).hashFor("a".repeat(32)));
	}

	@Test
	void rejectsBadDimensionsAndFormats() throws Exception {
		CapeStore store = new CapeStore(dir);
		assertThrows(CapeStore.RejectedUpload.class, () -> store.store("b".repeat(32), png(4096, 2048)));
		assertThrows(CapeStore.RejectedUpload.class, () -> store.store("c".repeat(32), png(100, 50)));
		assertThrows(CapeStore.RejectedUpload.class, () -> store.store("d".repeat(32), "not an image".getBytes()));
	}

	@Test
	void rateLimitsAndRemoves() throws Exception {
		CapeStore store = new CapeStore(dir);
		String uuid = "e".repeat(32);
		String hash = store.store(uuid, png(64, 32));
		CapeStore.RejectedUpload tooFast = assertThrows(CapeStore.RejectedUpload.class, () -> store.store(uuid, png(128, 64)));
		assertEquals(429, tooFast.status);
		assertTrue(store.remove(uuid));
		assertNull(store.read(hash));
	}
}
