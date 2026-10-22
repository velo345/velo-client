package net.veloclient.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PlusFeaturesTest {

	private static final String ME = "0123456789abcdef0123456789abcdef";

	@TempDir
	Path dir;

	private static byte[] zip(String name, String content) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry(name));
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	@Test
	void backupsDeduplicatePruneAutoFirstAndKeepPinned() throws Exception {
		CloudBackupService cloud = new CloudBackupService(dir);
		CloudBackupService.Backup first = cloud.add(ME, zip("config/theme.json", "{}"), "Desktop", null, false);
		assertEquals(first.id(), cloud.add(ME, zip("config/theme.json", "{}"), "Desktop", null, true).id(), "same content twice is one restore point");
		cloud.pin(ME, first.id(), true, "Before the tournament");
		for (int i = 0; i < CloudBackupService.MAX_COUNT + 5; i++) {
			cloud.add(ME, zip("config/theme.json", "{\"v\":" + i + "}"), "Laptop", null, true);
		}
		var all = cloud.list(ME);
		assertEquals(CloudBackupService.MAX_COUNT, all.size());
		assertTrue(all.stream().anyMatch(b -> b.id().equals(first.id()) && b.pinned() && "Before the tournament".equals(b.label())), "pinned survives pruning");
		assertArrayEquals(zip("config/theme.json", "{}"), cloud.file(ME, first.id()));
		cloud.delete(ME, first.id());
		assertThrows(ApiError.class, () -> cloud.file(ME, first.id()));
	}

	@Test
	void backupsRejectEvilZips() throws Exception {
		CloudBackupService cloud = new CloudBackupService(dir);
		assertThrows(ApiError.class, () -> cloud.add(ME, zip("../../etc/passwd", "x"), null, null, false));
		assertThrows(ApiError.class, () -> cloud.add(ME, "not a zip".getBytes(), null, null, false));
		assertThrows(ApiError.class, () -> cloud.add("../x", zip("a", "b"), null, null, false));
		assertEquals(1, cloud.sweep(u -> false) + cloud.sweep(u -> false) + (cloud.list(ME).isEmpty() ? 1 : 0));
	}

	@Test
	void clipsUploadServeAndBelongToTheirOwner() throws Exception {
		ClipShareService clips = new ClipShareService(dir, Long.MAX_VALUE, 0);
		byte[] mp4 = new byte[4096];
		System.arraycopy("\0\0\0\u0018ftypisom".getBytes(StandardCharsets.ISO_8859_1), 0, mp4, 0, 12);
		var meta = new ClipShareService.Meta("<b>Ace</b>", 31.5, 1920, 1080, 60, "kill", null);
		ClipShareService.Clip clip = clips.upload(ME, "Me", meta, new ByteArrayInputStream(mp4), mp4.length);
		assertEquals(4096, clip.size());
		assertTrue(Files.exists(clips.video(clip.id())));
		assertThrows(ApiError.class, () -> clips.delete("f".repeat(32), clip.id(), false), "someone else's clip");
		assertThrows(ApiError.class, () -> clips.upload(ME, "Me", meta, new ByteArrayInputStream("no".getBytes()), 2), "not an mp4");
		String html = PlusFeatureRoutes.ClipPage.render(clip, "https://velo-client.com");
		assertTrue(html.contains("og:video\" content=\"https://velo-client.com/c/" + clip.id() + ".mp4"));
		assertFalse(html.contains("<b>Ace</b>"), "titles are escaped");
		clips.countView(clip.id());
		assertEquals(1, clips.get(clip.id()).views());
		assertEquals(1, new ClipShareService(dir, Long.MAX_VALUE, 0).of(ME).size() + clips.sweep(u -> true));
		clips.delete(ME, clip.id(), false);
		assertNull(clips.video(clip.id()));
	}

	@Test
	void clipStorageNeverOverflowsTheServerCap() throws Exception {
		ClipShareService clips = new ClipShareService(dir, 10_000, 0);
		byte[] mp4 = new byte[6000];
		System.arraycopy("\0\0\0\u0018ftypisom".getBytes(StandardCharsets.ISO_8859_1), 0, mp4, 0, 12);
		var meta = new ClipShareService.Meta("a", 10, 1280, 720, 60, "key", null);
		clips.upload(ME, "Me", meta, new ByteArrayInputStream(mp4), mp4.length);
		// The only clip is less than a day old, so it's protected: no room for a second one.
		ApiError full = assertThrows(ApiError.class, () -> clips.upload(ME, "Me", meta, new ByteArrayInputStream(mp4), mp4.length));
		assertEquals(507, full.status);
		assertTrue(clips.totalBytes() <= 10_000);
	}

	@Test
	void nameStylesAreStrictlyValidated() {
		assertEquals("1|wave|12|bg|ff4d4d,ffb84d|3|ctn", SessionRegistry.normalizeNameStyle("1|wave|12|bg|ff4d4d,ffb84d|3|ctn"));
		assertNull(SessionRegistry.normalizeNameStyle("1|wave|12|bg|ff4d4d|3|ctn§kHACK"));
		assertNull(SessionRegistry.normalizeNameStyle("1|wave|12|bg|<script>|3|ctn"));
		assertNull(SessionRegistry.normalizeNameStyle(null));
	}
}
