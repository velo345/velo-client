package net.veloclient.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModerationTest {

	@TempDir
	Path dir;

	private static byte[] mp4() {
		byte[] b = new byte[64];
		b[4] = 'f';
		b[5] = 't';
		b[6] = 'y';
		b[7] = 'p';
		return b;
	}

	@Test
	void reportsGroupPerTargetAndCloseTogether() throws Exception {
		ModerationService m = new ModerationService(dir);
		m.report(ModerationService.CLIP, "clip1", "Title", "owner", "Owner", "r1", "R1", "inappropriate", "bad");
		// The same reporter again only updates their report.
		m.report(ModerationService.CLIP, "clip1", "Title", "owner", "Owner", "r1", "R1", "spam", "spam now");
		m.report(ModerationService.CLIP, "clip1", "Title", "owner", "Owner", "r2", "R2", "nonsense", null);
		assertEquals(2, m.openAgainst(ModerationService.CLIP, "clip1"));
		assertEquals("other", m.reports("clip", "open").getFirst().category);
		assertThrows(ApiError.class, () -> m.report(ModerationService.CLIP, "clip2", "T", "owner", "Owner", "owner", "Owner", "spam", ""));
		assertEquals(2, m.close(ModerationService.CLIP, "clip1", "removed", "staff", "gone"));
		assertEquals(0, m.openCount(ModerationService.CLIP));
		// Survives a restart.
		assertEquals(2, new ModerationService(dir).reports("clip", "removed").size());
	}

	@Test
	void blocksStopSharingUntilLifted() throws Exception {
		ModerationService m = new ModerationService(dir);
		m.block(ModerationService.CLIP, "p", 7, "spam", "staff");
		assertThrows(ApiError.class, () -> m.check(ModerationService.CLIP, "p"));
		m.check(ModerationService.WORLD, "p");
		m.block(ModerationService.CLIP, "p", -1, null, "staff");
		m.check(ModerationService.CLIP, "p");
	}

	@Test
	void reportsAreRateLimited() throws Exception {
		ModerationService m = new ModerationService(dir);
		for (int i = 0; i < 10; i++) {
			m.report(ModerationService.WORLD, "w" + i, "W", "owner", "Owner", "r", "R", "spam", "");
		}
		assertThrows(ApiError.class, () -> m.report(ModerationService.WORLD, "w99", "W", "owner", "Owner", "r", "R", "spam", ""));
	}

	@Test
	void clipsExpireAfterRetentionAndUploadsAreCounted() throws Exception {
		ClipShareService clips = new ClipShareService(dir, 1L << 40, 0, 60);
		ClipShareService.Clip clip = clips.upload("u", "User", new ClipShareService.Meta("t", 5, 1280, 720, 60, null, null),
				new ByteArrayInputStream(mp4()), 64);
		assertEquals(clip.createdAt() + 60L * 86_400_000L, clips.expiresAt(clip));
		assertEquals(1, clips.stats().uploads);
		assertEquals(0, clips.sweep(uuid -> true));
		// One day of retention: a clip made two days ago goes.
		ClipShareService shortLived = new ClipShareService(dir.resolve("b"), 1L << 40, 0, 1);
		ClipShareService.Clip old = shortLived.upload("u", "User", new ClipShareService.Meta("t", 5, 1280, 720, 60, null, null),
				new ByteArrayInputStream(mp4()), 64);
		Path index = dir.resolve("b").resolve("clips.json");
		Files.writeString(index, Files.readString(index).replace(String.valueOf(old.createdAt()), String.valueOf(old.createdAt() - 2 * 86_400_000L)));
		ClipShareService reloaded = new ClipShareService(dir.resolve("b"), 1L << 40, 0, 1);
		assertEquals(1, reloaded.sweep(uuid -> true));
		assertNull(reloaded.get(old.id()));
		assertEquals(1, reloaded.stats().expired);
	}

	@Test
	void staffRemovalDeletesTheClip() throws Exception {
		ClipShareService clips = new ClipShareService(dir, 1L << 40, 0, 60);
		ClipShareService.Clip clip = clips.upload("u", "User", new ClipShareService.Meta("t", 5, 1280, 720, 60, null, null),
				new ByteArrayInputStream(mp4()), 64);
		clips.removeByStaff(clip.id());
		assertNull(clips.get(clip.id()));
		assertEquals(1, clips.stats().removedByStaff);
	}
}
