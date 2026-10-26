package net.veloclient.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SocialStatsServiceTest {

	@TempDir
	Path dir;

	@Test
	void recognisesInstagramProfilesAndReels() {
		assertEquals("instagram", SocialStatsService.platformOf("https://www.instagram.com/velo.client/"));
		assertTrue(SocialStatsService.isAccountUrl("https://www.instagram.com/velo.client/"));
		assertTrue(SocialStatsService.isAccountUrl("https://instagram.com/velo.client/reels/"));
		assertTrue(SocialStatsService.isAccountUrl("https://www.instagram.com/velo.client?igsh=abc"));
		assertFalse(SocialStatsService.isAccountUrl("https://www.instagram.com/reel/DAbc123_-x/"));
		assertFalse(SocialStatsService.isAccountUrl("https://www.instagram.com/p/DAbc123/?igsh=x"));
		assertEquals("DAbc123_-x", SocialStatsService.instagramShortcode("https://www.instagram.com/reel/DAbc123_-x/?igsh=abc"));
		assertEquals("DAbc123", SocialStatsService.instagramShortcode("https://www.instagram.com/velo.client/reel/DAbc123/"));
		assertEquals("DAbc123", SocialStatsService.instagramShortcode("https://www.instagram.com/p/DAbc123/"));
		assertNull(SocialStatsService.instagramShortcode("https://www.instagram.com/velo.client/"));
		// TikTok and YouTube unchanged
		assertTrue(SocialStatsService.isAccountUrl("https://www.tiktok.com/@velo.client"));
		assertFalse(SocialStatsService.isAccountUrl("https://www.youtube.com/shorts/abcdefghijk"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void instagramLinksAreKeyedByPostCodeAndProfilesNormalised() throws Exception {
		SocialStatsService socials = new SocialStatsService(dir, "/bin/false", null);
		socials.add("https://www.instagram.com/velo.client/reels/?igsh=abc");
		Map<String, Object> out = socials.add("https://www.instagram.com/reel/DAbc123/?igsh=x");
		var accounts = (List<SocialStatsService.Account>) out.get("accounts");
		assertEquals("https://www.instagram.com/velo.client/", accounts.get(0).url);
		assertEquals("@velo.client", accounts.get(0).name);
		var videos = (List<Map<String, Object>>) out.get("videos");
		assertEquals("instagram:DAbc123", videos.get(0).get("id"));
		assertEquals(false, ((Map<String, Object>) out.get("instagram")).get("connected"));
		assertThrows(ApiError.class, () -> socials.add("https://www.instagram.com/velo.client/"), "already tracked");
	}
}
