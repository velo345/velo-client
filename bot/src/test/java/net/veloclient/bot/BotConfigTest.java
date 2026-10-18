package net.veloclient.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BotConfigTest {

	@TempDir
	Path dir;

	@Test
	void startsWithDefaultTicketTypesAndKeepsEdits() throws Exception {
		BotConfig config = BotConfig.load(dir);
		assertEquals(6, config.ticketTypes.size());
		assertEquals("staff", config.type("staff").id);
		assertEquals("partner", config.type("partner").id);
		assertTrue(config.type("bug").questions.size() >= 3);
		assertTrue(config.type("bug").questions.size() <= 5, "a Discord form holds five questions");

		config.type("purchase").staffRoleIds.add("123");
		config.type("purchase").questions.get(0).label = "Your name";
		config.ticketLogChannelId = "42";
		config.linkChannelId = "84";
		config.save();

		BotConfig again = BotConfig.load(dir);
		assertEquals("123", again.type("purchase").staffRoleIds.get(0));
		assertEquals("Your name", again.type("purchase").questions.get(0).label);
		assertEquals("42", again.ticketLogChannelId);
		assertEquals("84", again.linkChannelId);
	}

	@Test
	void reloadPicksUpHandEdits() throws Exception {
		BotConfig config = BotConfig.load(dir);
		config.linkChannelId = "84";
		config.save();
		Path file = dir.resolve("bot-config.json");
		Files.writeString(file, Files.readString(file).replace("\"label\": \"Support\"", "\"label\": \"Help desk\""));
		config.reload();
		assertEquals("Help desk", config.type("support").label);
		assertEquals("84", config.linkChannelId);
	}

	@Test
	void unknownTypeFallsBackToTheFirst() throws Exception {
		BotConfig config = BotConfig.load(dir);
		assertEquals(config.ticketTypes.get(0).id, config.type("nope").id);
	}

	@Test
	void newDefaultTypesReachOldConfigsOnceOnly() throws Exception {
		Files.writeString(dir.resolve("bot-config.json"), "{\"ticketTypes\": [{\"id\": \"support\", \"label\": \"Help\", \"questions\": []}]}");
		BotConfig config = BotConfig.load(dir);
		assertTrue(config.ticketTypes.stream().anyMatch(t -> t.id.equals("staff")));
		assertEquals("Help", config.type("support").label);
		config.ticketTypes.removeIf(t -> t.id.equals("partner"));
		config.save();
		assertTrue(BotConfig.load(dir).ticketTypes.stream().noneMatch(t -> t.id.equals("partner")));
	}

	@Test
	void giveawayDurations() {
		assertEquals(30 * 60_000L, Giveaways.parseDuration("30m"));
		assertEquals(36 * 3_600_000L, Giveaways.parseDuration("1d 12h"));
		assertEquals(7 * 86_400_000L, Giveaways.parseDuration("1w"));
		assertEquals(0, Giveaways.parseDuration("soon"));
	}
}
