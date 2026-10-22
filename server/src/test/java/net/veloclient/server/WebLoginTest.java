package net.veloclient.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebLoginTest {

	@Test
	void codeWorksOnceAndGivesAWeekLongWebSession() {
		SessionRegistry registry = new SessionRegistry();
		String code = registry.createWebCode("uuid-1", "Velo2k");
		assertEquals(6, code.length());
		String[] login = registry.redeemWebCode(code);
		assertNotNull(login);
		assertEquals("uuid-1", login[1]);
		SessionRegistry.Session session = registry.session(login[0]);
		assertEquals(SessionRegistry.KIND_WEB, session.kind());
		assertTrue(session.expiresAtMillis() > System.currentTimeMillis() + 6L * 24 * 60 * 60 * 1000);
		assertNull(registry.redeemWebCode(code), "a code works once");
	}

	@Test
	void aNewCodeReplacesTheOldOneAndWebSessionsDontReplaceEachOther() {
		SessionRegistry registry = new SessionRegistry();
		String first = registry.createWebCode("uuid-1", "Velo2k");
		String second = registry.createWebCode("uuid-1", "Velo2k");
		assertNull(registry.redeemWebCode(first));
		String[] pc = registry.redeemWebCode(second);
		String[] phone = registry.redeemWebCode(registry.createWebCode("uuid-1", "Velo2k"));
		assertNotEquals(pc[0], phone[0]);
		assertNotNull(registry.session(pc[0]), "signing in on a second browser keeps the first one signed in");
		assertNotNull(registry.session(phone[0]));
	}

	@Test
	void wrongCodesFail() {
		SessionRegistry registry = new SessionRegistry();
		assertNull(registry.redeemWebCode("000000x"));
		assertNull(registry.redeemWebCode(null));
	}
}
