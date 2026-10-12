package net.veloclient.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PartyServiceTest {

	private static final String ALICE = "a".repeat(32);
	private static final String BOB = "b".repeat(32);
	private static final String CAROL = "c".repeat(32);

	@TempDir
	Path dir;

	private SocialService social;
	private PartyService parties;

	@BeforeEach
	void setUp() throws Exception {
		social = new SocialService(dir, new SessionRegistry());
		social.onAuthenticated(ALICE, "Alice");
		social.onAuthenticated(BOB, "Bob");
		social.onAuthenticated(CAROL, "Carol");
		social.sendRequest(ALICE, BOB);
		social.respond(BOB, ALICE, true);
		parties = new PartyService(social);
	}

	@Test
	void inviteJoinTransferLeave() throws Exception {
		PartyService.PartyView party = parties.invite(ALICE, BOB);
		assertEquals(ALICE, party.leader());
		assertEquals(1, parties.state(BOB).invites().size());

		parties.respond(BOB, party.id(), true);
		PartyService.PartyView joined = parties.state(ALICE).party();
		assertEquals(2, joined.members().size());
		assertTrue(parties.state(BOB).invites().isEmpty());

		assertThrows(SocialService.SocialException.class, () -> parties.transfer(BOB, ALICE), "only the leader transfers");
		parties.transfer(ALICE, BOB);
		assertEquals(BOB, parties.state(ALICE).party().leader());

		parties.leave(BOB);
		assertEquals(ALICE, parties.state(ALICE).party().leader(), "remaining member leads");
		assertNull(parties.state(BOB).party());
	}

	@Test
	void onlyFriendsCanBeInvited() {
		assertThrows(SocialService.SocialException.class, () -> parties.invite(ALICE, CAROL));
	}

	@Test
	void syncAndChatReachTheParty() throws Exception {
		PartyService.PartyView party = parties.invite(ALICE, BOB);
		parties.respond(BOB, party.id(), true);
		parties.setSync(ALICE, new PartyService.ModSync("PvP", "26.2", List.of(Map.of("file", "sodium.jar", "sha1", "abc")), 0));
		PartyService.ModSync sync = parties.state(BOB).party().sync();
		assertEquals("PvP", sync.profile());
		assertEquals(1, sync.mods().size());
		assertThrows(SocialService.SocialException.class, () -> parties.setSync(BOB, null), "members can't change the sync");

		parties.chat(BOB, "gg");
		assertTrue(parties.state(ALICE).party().chat().stream().anyMatch(line -> "gg".equals(line.text())));
	}
}
