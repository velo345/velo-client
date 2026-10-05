package net.veloclient.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NewsServiceTest {

	@TempDir
	Path dir;

	@Test
	void draftsOnlyForOwnersAndImagesRoundTrip() throws Exception {
		NewsService news = new NewsService(dir);
		BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(img, "png", png);
		NewsService.StoredImage stored = news.storeImage(png.toByteArray());
		assertTrue(stored.id().endsWith(".jpg"));
		assertNotNull(news.image(stored.id()));
		assertNull(news.image("../news.json"));

		NewsService.Post post = new NewsService.Post();
		post.title = "Velo 0.8.4";
		post.cover = stored.id();
		NewsService.Post saved = news.savePost(post, "owner");
		assertEquals(0, news.feed(null, false).posts().size());
		assertEquals(1, news.feed(null, true).posts().size());
		post.id = saved.id;
		post.published = true;
		news.savePost(post, "owner");
		assertEquals(1, new NewsService(dir).feed(null, false).posts().size());

		NewsService.Post bad = new NewsService.Post();
		bad.title = "x";
		bad.cover = "0123456789abcdef0123456789abcdef.png";
		assertThrows(NewsService.NewsException.class, () -> news.savePost(bad, "owner"));
	}

	@Test
	void votesAreHiddenUntilYouVote() throws Exception {
		NewsService news = new NewsService(dir);
		NewsService.Poll poll = new NewsService.Poll();
		poll.question = "Next feature?";
		poll.options = List.of("Minimap", "Replay", " ");
		String id = news.savePoll(poll, "owner").id();
		var before = news.feed("a", false).polls().get(0);
		assertFalse(before.resultsVisible());
		assertEquals(2, before.options().size());
		var after = news.vote("a", id, List.of(1));
		assertTrue(after.resultsVisible());
		assertEquals(1, after.options().get(1).votes());
		assertThrows(NewsService.NewsException.class, () -> news.vote("b", id, List.of(0, 1)));
		news.vote("a", id, List.of(0));
		assertEquals(1, news.feed("a", false).polls().get(0).options().get(0).votes());
		assertEquals(0, news.feed("a", false).polls().get(0).options().get(1).votes());
	}
}
