package net.veloclient.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicShareServiceTest {

	private static final String ALICE = "a".repeat(32);
	private static final String BOB = "b".repeat(32);

	private static byte[] gzip(byte[] raw) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
			gz.write(raw);
		}
		return out.toByteArray();
	}

	private static byte[] veloschem() throws IOException {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(raw)) {
			out.writeUTF("VELOSCHEM");
			out.writeInt(3);
			out.write(new byte[64]);
		}
		return gzip(raw.toByteArray());
	}

	private static byte[] png() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB), "png", out);
		return out.toByteArray();
	}

	@Test
	void acceptsOnlyRealSchematics() throws IOException {
		assertEquals("veloschem", SchematicShareService.format("farm.veloschem", veloschem()));
		byte[] litematic = gzip(new byte[] {10, 0, 0, 10, 0, 7, 'R', 'e', 'g', 'i', 'o', 'n', 's', 0, 0, 0, 0, 0, 0, 0, 0, 0});
		assertEquals("litematic", SchematicShareService.format("farm.litematic", litematic));
		// Right name, wrong content; wrong name; a jar renamed to .litematic.
		assertThrows(SchematicShareService.SchematicException.class, () -> SchematicShareService.format("farm.litematic", veloschem()));
		assertThrows(SchematicShareService.SchematicException.class, () -> SchematicShareService.format("farm.zip", veloschem()));
		assertThrows(SchematicShareService.SchematicException.class,
				() -> SchematicShareService.format("evil.litematic", new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
	}

	@Test
	void refusesLinksAndScams() {
		for (String bad : List.of("get it at https://x.y", "join discord.gg/abc", "see www.farm.net", "my site farms.com", "farms (dot) xyz",
				"FREE NITRO here")) {
			assertThrows(SchematicShareService.SchematicException.class, () -> SchematicShareService.cleanText(bad, 0, 600, "Description"), bad);
		}
		// Ordinary sentences with dots stay fine.
		assertEquals("Fast kelp farm. Me and a friend built it in 1.21.4", SchematicShareService.cleanText(
				"Fast kelp farm. Me and a friend built it in 1.21.4", 0, 600, "Description"));
		assertEquals(List.of("kelp", "farm"), SchematicShareService.cleanTags(List.of("Kelp", "farm", "kelp")));
		assertThrows(SchematicShareService.SchematicException.class, () -> SchematicShareService.cleanTags(List.of("x.com")));
	}

	@Test
	void uploadVoteAndRank(@TempDir Path dir) throws IOException {
		SchematicShareService service = new SchematicShareService(dir);
		var mine = service.upload(ALICE, "Alice", "Kelp farm", "Fast and cheap", List.of("kelp"), "kelp.veloschem", veloschem(), png());
		assertThrows(SchematicShareService.SchematicException.class, () -> service.vote(mine.id(), ALICE, true));
		assertEquals(1, service.vote(mine.id(), BOB, true).votes());
		var page = service.list("hot", "kelp", null, 0, BOB);
		assertEquals(1, page.total());
		assertTrue(page.items().get(0).voted());
		// Survives a restart.
		assertEquals(1, new SchematicShareService(dir).list("top", null, null, 0, null).items().get(0).votes());
		assertThrows(SchematicShareService.SchematicException.class, () -> service.delete(mine.id(), BOB, false));
		assertTrue(service.delete(mine.id(), ALICE, false));
	}
}
