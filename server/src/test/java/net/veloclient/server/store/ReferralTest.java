package net.veloclient.server.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReferralTest {

	private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	@TempDir
	Path dir;
	private StoreDatabase db;
	private StoreService store;

	@BeforeEach
	void setUp() throws Exception {
		db = new StoreDatabase(dir.resolve("store.db"));
		store = new StoreService(StoreConfig.load(dir), db, new TebexCheckout(null, null, null), Set.of(), null, null, query -> null);
	}

	/** Plays {@code seconds} of in-game time today (heartbeats a minute apart). */
	private void play(String uuid, long seconds) throws Exception {
		long t = System.currentTimeMillis() - seconds * 1000 - 60_000;
		for (long s = 0; s <= seconds; s += 60) {
			db.recordHeartbeat(uuid, StoreService.today(), t + s * 1000, 3 * 60_000L);
		}
	}

	private static String uuid(int i) {
		return String.format("%032x", i);
	}

	@Test
	void ladderGrowsAndStopsAtTheCap() {
		assertEquals(300, StoreService.referrerReward(0));
		assertEquals(300, StoreService.referrerReward(1));
		assertEquals(400, StoreService.referrerReward(2));
		assertEquals(400, StoreService.referrerReward(4));
		assertEquals(500, StoreService.referrerReward(5));
		assertEquals(500, StoreService.referrerReward(9));
		assertEquals(0, StoreService.referrerReward(10));
	}

	@Test
	void inviteIsPaidOnceAfterTwoHours() throws Exception {
		String code = store.referral(A, "Alice").code();
		assertEquals(6, code.length());
		StoreService.ReferralView view = store.redeemReferral(B, "Bob", code.toLowerCase());
		assertFalse(view.canRedeem());
		assertEquals("Alice", view.invitedBy().username());

		play(B, 3600);
		store.onGameHeartbeat(B);
		assertEquals(0, db.balance(A));
		assertEquals(0, db.balance(B));

		play(B, 3700);
		store.onGameHeartbeat(B);
		store.onGameHeartbeat(B);
		assertEquals(300, db.balance(A));
		assertEquals(300, db.balance(B));

		StoreService.ReferralView mine = store.referral(A, "Alice");
		assertEquals(1, mine.paidInvites());
		assertEquals(300, mine.earned());
		assertTrue(mine.invites().get(0).rewarded());
	}

	@Test
	void ownCodeSecondCodeAndCyclesAreRefused() throws Exception {
		String codeA = store.referral(A, "Alice").code();
		String codeB = store.referral(B, "Bob").code();
		assertEquals(400, assertThrows(StoreService.StoreException.class, () -> store.redeemReferral(A, "Alice", codeA)).status);
		store.redeemReferral(B, "Bob", codeA);
		assertEquals(400, assertThrows(StoreService.StoreException.class, () -> store.redeemReferral(A, "Alice", codeB)).status);
		String c = uuid(99);
		store.redeemReferral(c, "Carl", "alice"); // a name works too
		assertEquals(409, assertThrows(StoreService.StoreException.class, () -> store.redeemReferral(c, "Carl", codeB)).status);
		assertEquals(404, assertThrows(StoreService.StoreException.class, () -> store.redeemReferral(uuid(98), "Dan", "ZZZZZZ")).status);
	}

	@Test
	void codeClosesAfterTenPaidInvites() throws Exception {
		String code = store.referral(A, "Alice").code();
		for (int i = 1; i <= StoreService.MAX_PAID_INVITES; i++) {
			store.redeemReferral(uuid(i), "P" + i, code);
			play(uuid(i), 7300);
			store.onGameHeartbeat(uuid(i));
		}
		assertEquals(2 * 300 + 3 * 400 + 5 * 500, db.balance(A));
		assertEquals(403, assertThrows(StoreService.StoreException.class, () -> store.redeemReferral(uuid(50), "Late", code)).status);
	}

	@Test
	void giftChargesGiverAndGrantsRecipient() throws Exception {
		db.touchWallet(B, "Bob");
		String item = store.catalog().items().get(0).id();
		int price = store.catalog().items().get(0).price();
		db.adjust(A, price, "test", null, null);
		store.gift(A, "Alice", item, "bob");
		assertEquals(0, db.balance(A));
		assertTrue(db.owns(B, item));
		assertFalse(db.owns(A, item));
		db.adjust(A, price, "test", null, null);
		assertEquals(409, assertThrows(StoreService.StoreException.class, () -> store.gift(A, "Alice", item, "Bob")).status);
		assertEquals(404, assertThrows(StoreService.StoreException.class, () -> store.gift(A, "Alice", item, "Nobody")).status);
	}

	@Test
	void discountRoundsToTens() {
		assertEquals(1750, StoreService.discounted(2500, 30));
		assertEquals(2000, StoreService.discounted(2500, 20));
		assertEquals(10, StoreService.discounted(5, 50));
	}
}
