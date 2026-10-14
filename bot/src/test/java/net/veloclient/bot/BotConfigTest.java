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
		assertEquals(4, config.ticketTypes.size());
		assertTrue(config.type("bug").questions.size() >= 3);
		assertTrue(config.type("bug").questions.size() <= 5, "a Discord form holds five questions");

		config.type("purchase").staffRoleIds.add("123");
		config.type("purchase").questions.get(0).label = "Your name";
		config.ticketLogChannelId = "42";
		config.save();

		BotConfig again = BotConfig.load(dir);
		assertEquals("123", again.type("purchase").staffRoleIds.get(0));
		assertEquals("Your name", again.type("purchase").questions.get(0).label);
		assertEquals("42", again.ticketLogChannelId);
	}

	@Test
	void reloadPicksUpHandEdits() throws Exception {
		BotConfig config = BotConfig.load(dir);
		Path file = dir.resolve("bot-config.json");
		Files.writeString(file, Files.readString(file).replace("\"label\": \"Support\"", "\"label\": \"Help desk\""));
		config.reload();
		assertEquals("Help desk", config.type("support").label);
	}

	@Test
	void unknownTypeFallsBackToTheFirst() throws Exception {
		BotConfig config = BotConfig.load(dir);
		assertEquals(config.ticketTypes.get(0).id, config.type("nope").id);
	}
}
