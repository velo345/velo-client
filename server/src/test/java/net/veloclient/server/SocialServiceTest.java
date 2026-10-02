package net.veloclient.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SocialServiceTest {

	private static final String ALICE = "a".repeat(32);
	private static final String BOB = "b".repeat(32);
	private static final String CAROL = "c".repeat(32);

	@TempDir
	Path dir;

	private SessionRegistry sessions;
	private SocialService social;

	@BeforeEach
	void setUp() throws Exception {
		sessions = new SessionRegistry();
		social = new SocialService(dir, sessions);
		social.onAuthenticated(ALICE, "Alice");
		social.onAuthenticated(BOB, "Bob");
		social.onAuthenticated(CAROL, "Carol");
	}

	private void befriend(String a, String b) throws Exception {
		social.sendRequest(a, b);
		social.respond(b, a, true);
	}

	@Test
	void requestByNameThenAcceptMakesFriends() throws Exception {
		SocialService.PlayerRef ref = social.sendRequest(ALICE, "bob");
		assertEquals(BOB, ref.uuid());
		assertEquals(1, social.state(BOB).incoming().size());
		assertEquals(1, social.state(ALICE).outgoing().size());

		social.respond(BOB, ALICE, true);
		assertEquals(List.of("Bob"), social.state(ALICE).friends().stream().map(SocialService.FriendView::username).toList());
		assertTrue(social.state(BOB).incoming().isEmpty());
	}

	@Test
	void mutualRequestAutoAccepts() throws Exception {
		social.sendRequest(ALICE, BOB);
		social.sendRequest(BOB, ALICE);
		assertEquals(1, social.state(ALICE).friends().size());
	}

	@Test
	void declineRemovesRequest() throws Exception {
		social.sendRequest(ALICE, BOB);
		social.respond(BOB, ALICE, false);
		assertTrue(social.state(ALICE).outgoing().isEmpty());
		assertTrue(social.state(ALICE).friends().isEmpty());
	}

	@Test
	void blockedPlayerCannotMessageAndRequestIsHidden() throws Exception {
		befriend(ALICE, BOB);
		social.block(BOB, ALICE);
		assertTrue(social.state(ALICE).friends().isEmpty(), "block removes the friendship");
		assertThrows(SocialService.SocialException.class, () -> social.sendMessage(ALICE, BOB, "hi"));

		// A new request from the blocked player is accepted on their side but never reaches Bob.
		social.sendRequest(ALICE, BOB);
		assertTrue(social.state(BOB).incoming().isEmpty());
		assertThrows(SocialService.SocialException.class, () -> social.sendRequest(BOB, ALICE), "must unblock first");

		social.unblock(BOB, ALICE);
		assertEquals(1, social.state(BOB).incoming().size());
	}

	@Test
	void messagesCountUnreadUntilRead() throws Exception {
		befriend(ALICE, BOB);
		social.sendMessage(ALICE, BOB, "hello");
		social.sendMessage(ALICE, BOB, "there");
		assertEquals(2, social.state(BOB).friends().get(0).unread());
		assertEquals(0, social.state(ALICE).friends().get(0).unread(), "your own messages are never unread");
		social.markRead(BOB, ALICE);
		assertEquals(0, social.state(BOB).friends().get(0).unread());
		assertEquals(List.of("hello", "there"), social.messages(BOB, ALICE, 0).stream().map(SocialService.MessageView::text).toList());
	}

	@Test
	void nonFriendsCannotMessage() {
		assertThrows(SocialService.SocialException.class, () -> social.sendMessage(ALICE, CAROL, "hi"));
	}

	@Test
	void invisiblePlayersAppearOffline() throws Exception {
		befriend(ALICE, BOB);
		String token = sessions.createSession(BOB, "Bob", SessionRegistry.KIND_GAME);
		social.setPresence(BOB, "server", "play.example.net");
		SocialService.FriendView bob = social.state(ALICE).friends().get(0);
		assertTrue(bob.online());
		assertEquals("play.example.net", bob.activity().detail());

		social.setInvisible(BOB, true);
		assertFalse(social.state(ALICE).friends().get(0).online());
		assertTrue(social.state(BOB).me().invisible());

		sessions.endSession(token);
		social.setInvisible(BOB, false);
		assertFalse(social.state(ALICE).friends().get(0).online(), "no live session means offline");
	}

	@Test
	void launcherAndGameSessionsCoexist() {
		String game = sessions.createSession(ALICE, "Alice", SessionRegistry.KIND_GAME);
		String launcher = sessions.createSession(ALICE, "Alice", SessionRegistry.KIND_LAUNCHER);
		assertNotNull(sessions.session(game));
		assertNotNull(sessions.session(launcher));
		assertEquals(1, sessions.onlineUsers().size(), "only the game session counts for badges");
	}

	@Test
	void pollReturnsEventsAfterSince() throws Exception {
		long since = social.state(BOB).seq();
		social.sendRequest(ALICE, BOB);
		SocialService.PollResult result = social.poll(BOB, since);
		assertFalse(result.reset());
		assertEquals("friend_request", result.events().get(0).type());
		assertTrue(social.poll(BOB, 0).reset(), "a fresh client is told to load the full state");
	}

	@Test
	void sharedWaypointArrivesAsMessage() throws Exception {
		befriend(ALICE, BOB);
		social.shareWaypoint(ALICE, List.of(BOB), Map.of("name", "Base", "x", 1.0, "y", 64.0, "z", -3.0, "dimension", "minecraft:overworld"));
		SocialService.MessageView message = social.messages(BOB, ALICE, 0).get(0);
		assertEquals("waypoint", message.kind());
		assertEquals("Base", message.waypoint().get("name"));
	}

	@Test
	void stateSurvivesRestart() throws Exception {
		befriend(ALICE, BOB);
		social.setInvisible(ALICE, true);
		social.saveIfDirty();
		SocialService reloaded = new SocialService(dir, new SessionRegistry());
		assertEquals(1, reloaded.state(ALICE).friends().size());
		assertTrue(reloaded.state(ALICE).me().invisible());
	}

	@Test
	void friendRequestsAreRateLimited() throws Exception {
		String[] targets = {"1", "2", "3", "4", "5", "6"};
		for (int i = 0; i < 5; i++) {
			String uuid = targets[i].repeat(32);
			social.onAuthenticated(uuid, "Player" + i);
			social.sendRequest(ALICE, uuid);
		}
		social.onAuthenticated("6".repeat(32), "Player6");
		SocialService.SocialException e = assertThrows(SocialService.SocialException.class, () -> social.sendRequest(ALICE, "6".repeat(32)));
		assertEquals(429, e.status);
	}

	@Test
	void declinedRequestHasCooldown() throws Exception {
		social.sendRequest(ALICE, BOB);
		social.respond(BOB, ALICE, false);
		SocialService.SocialException e = assertThrows(SocialService.SocialException.class, () -> social.sendRequest(ALICE, BOB));
		assertEquals(429, e.status);
	}

	@Test
	void messageBurstsAreRateLimited() throws Exception {
		befriend(ALICE, BOB);
		for (int i = 0; i < 8; i++) {
			social.sendMessage(ALICE, BOB, "spam " + i);
		}
		assertThrows(SocialService.SocialException.class, () -> social.sendMessage(ALICE, BOB, "one too many"));
	}
}
