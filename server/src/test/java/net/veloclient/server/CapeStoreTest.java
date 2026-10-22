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
		String hash = store.store("a".repeat(32), png(2048, 1024), false);
		assertEquals(hash, store.hashFor("a".repeat(32), false));
		CapeStore.StoredFile file = store.read(hash, u -> false);
		assertNotNull(file);
		assertEquals(hash, CapeStore.sha256(file.bytes()));
		// Survives a restart.
		assertEquals(hash, new CapeStore(dir).hashFor("a".repeat(32), false));
	}

	@Test
	void rejectsBadDimensionsAndFormats() throws Exception {
		CapeStore store = new CapeStore(dir);
		assertThrows(CapeStore.RejectedUpload.class, () -> store.store("b".repeat(32), png(4096, 2048), false));
		assertThrows(CapeStore.RejectedUpload.class, () -> store.store("c".repeat(32), png(100, 50), false));
		assertThrows(CapeStore.RejectedUpload.class, () -> store.store("d".repeat(32), "not an image".getBytes(), false));
	}

	private static byte[] gif(int w, int h, int frames) throws IOException {
		javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var stream = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(stream);
			writer.prepareWriteSequence(null);
			for (int i = 0; i < frames; i++) {
				writer.writeToSequence(new javax.imageio.IIOImage(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), null, null), null);
			}
			writer.endWriteSequence();
		}
		return out.toByteArray();
	}

	@Test
	void animatedCapesAreForVeloPlusOnly() throws Exception {
		CapeStore store = new CapeStore(dir);
		String member = "f".repeat(32);
		byte[] animated = gif(128, 64, 4);
		CapeStore.RejectedUpload notMember = assertThrows(CapeStore.RejectedUpload.class, () -> store.store("9".repeat(32), animated, false));
		assertEquals(403, notMember.status);
		String hash = store.store(member, animated, true);
		assertEquals(hash, store.hashFor(member, true));
		assertNull(store.hashFor(member, false), "hidden once the membership ends");
		assertNotNull(store.read(hash, u -> true));
		assertEquals("image/gif", store.read(hash, u -> true).contentType());
		assertNull(store.read(hash, u -> false));
		assertThrows(CapeStore.RejectedUpload.class, () -> new CapeStore(dir.resolve("x")).store(member, gif(1024, 512, 2), true), "too wide");
	}

	@Test
	void rateLimitsAndRemoves() throws Exception {
		CapeStore store = new CapeStore(dir);
		String uuid = "e".repeat(32);
		String hash = store.store(uuid, png(64, 32), false);
		CapeStore.RejectedUpload tooFast = assertThrows(CapeStore.RejectedUpload.class, () -> store.store(uuid, png(128, 64), false));
		assertEquals(429, tooFast.status);
		assertTrue(store.remove(uuid));
		assertNull(store.read(hash, u -> false));
	}
}
