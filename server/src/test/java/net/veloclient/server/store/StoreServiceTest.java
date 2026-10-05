package net.veloclient.server.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StoreServiceTest {

	private static final String ME = "0123456789abcdef0123456789abcdef";
	private static final String OTHER = "fedcba9876543210fedcba9876543210";
	private static final String OWNER = "11111111111111111111111111111111";

	@TempDir
	Path dir;
	private StoreDatabase db;
	private StoreService store;
	private TebexCheckout tebex;

	@BeforeEach
	void setUp() throws Exception {
		db = new StoreDatabase(dir.resolve("store.db"));
		tebex = new TebexCheckout("project", "key", "whsecret");
		store = new StoreService(StoreConfig.load(dir), db, tebex, Set.of(OWNER), "https://example.test",
				new StoreService.AdConfig("123", "velo_rewarded", "ayetkey"), query -> query.length() == 32 ? new String[] {query, "Player"} : new String[] {OTHER, query});
	}

	@Test
	void webhookSignatureMatchesTebexScheme() throws Exception {
		byte[] body = "{\"id\":\"x\",\"type\":\"validation.webhook\"}".getBytes(StandardCharsets.UTF_8);
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("whsecret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String signature = HexFormat.of().formatHex(mac.doFinal(hash.getBytes(StandardCharsets.UTF_8)));
		assertTrue(store.verifyTebexSignature(body, signature));
		assertFalse(store.verifyTebexSignature(body, signature.replace('a', 'b')));
		assertFalse(store.verifyTebexSignature("{}".getBytes(), signature));
	}

	@Test
	void validationWebhookEchoesId() throws Exception {
		Object reply = store.tebexWebhook(json("{\"id\":\"abc\",\"type\":\"validation.webhook\",\"subject\":{}}"));
		assertEquals(Map.of("id", "abc"), reply);
	}

	@Test
	void paidOrderIsCreditedExactlyOnceAndRefundTakesItBack() throws Exception {
		StoreConfig.CoinPack pack = new StoreConfig().pack("coins_1200");
		db.createOrder("vo_1", ME, pack, "EUR");
		JsonObject paid = paymentEvent("evt1", "payment.completed", "tbx-1", "vo_1", ME, 9.99, "EUR");
		store.tebexWebhook(paid);
		assertEquals(1340, db.balance(ME));
		store.tebexWebhook(paid); // Tebex retry, same event id
		store.tebexWebhook(paymentEvent("evt2", "payment.completed", "tbx-1", "vo_1", ME, 9.99, "EUR")); // new id, same order
		assertEquals(1340, db.balance(ME));
		assertEquals("paid", db.order("vo_1").status());

		store.tebexWebhook(paymentEvent("evt3", "payment.refunded", "tbx-1", "vo_1", ME, 9.99, "EUR"));
		assertEquals(0, db.balance(ME));
		assertEquals("refunded", db.order("vo_1").status());
	}

	@Test
	void underpaidOrWrongPlayerGoesToReview() throws Exception {
		StoreConfig.CoinPack pack = new StoreConfig().pack("coins_6500");
		db.createOrder("vo_2", ME, pack, "EUR");
		store.tebexWebhook(paymentEvent("e1", "payment.completed", "tbx-2", "vo_2", ME, 1.00, "EUR"));
		assertEquals(0, db.balance(ME));
		assertEquals("review", db.order("vo_2").status());

		db.createOrder("vo_3", ME, pack, "EUR");
		store.tebexWebhook(paymentEvent("e2", "payment.completed", "tbx-3", "vo_3", OTHER, 39.99, "EUR"));
		assertEquals(0, db.balance(ME));
		assertEquals(0, db.balance(OTHER));
	}

	@Test
	void buyingSpendsCoinsOnceAndNeverGoesNegative() throws Exception {
		db.adjust(ME, 1000, "test", null, null);
		var error = assertThrows(StoreService.StoreException.class, () -> store.buy(ME, "Me", "cape_red_lightning"));
		assertEquals(402, error.status);
		assertEquals(1000, db.balance(ME));
		store.buy(ME, "Me", "cape_black_pattern");
		assertEquals(100, db.balance(ME));
		assertTrue(db.owns(ME, "cape_black_pattern"));
		assertEquals(409, assertThrows(StoreService.StoreException.class, () -> store.buy(ME, "Me", "cape_black_pattern")).status);
		assertEquals(100, db.balance(ME));
	}

	@Test
	void onlyOwnersCanGrantAndEverythingIsInTheLedger() throws Exception {
		assertEquals(403, assertThrows(StoreService.StoreException.class, () -> store.adminCoins(ME, "Me", OTHER, 500, "hi")).status);
		var view = store.adminCoins(OWNER, "Boss", OTHER, 500, "giveaway");
		assertEquals(500, view.balance());
		assertEquals("admin_grant", view.ledger().get(0).reason());
		store.adminItem(OWNER, "Boss", OTHER, "cape_symbols", true);
		assertTrue(db.owns(OTHER, "cape_symbols"));
		assertTrue(store.mayDisplayCape(OTHER, "cape_symbols"));
		assertFalse(store.mayDisplayCape(ME, "cape_symbols"));
		assertTrue(store.mayDisplayCape(ME, "custom:abc"));
	}

	@Test
	void ayetQueryStringMatchesPhpHttpBuildQuery() {
		// PHP: ksort($p); http_build_query($p) -> spaces become '+', '*' and '~' are percent-encoded.
		assertEquals("a=1&tid=x+y%2Az%7E&uid=abc", StoreService.phpQueryString(Map.of("uid", "abc", "tid", "x y*z~", "a", "1")));
	}

	@Test
	void ayetCallbackNeedsValidHmacIsDedupedAndCapped() throws Exception {
		assertNull(store.ayetCallback(ayet("t1"), "deadbeef"));
		assertNull(store.ayetCallback(ayet("t1"), null));
		assertEquals("credited", store.ayetCallback(ayet("t1"), sign(ayet("t1"))));
		assertEquals("duplicate", store.ayetCallback(ayet("t1"), sign(ayet("t1")))); // ayeT retry
		assertEquals("credited", store.ayetCallback(ayet("t2"), sign(ayet("t2")).toUpperCase()));
		assertEquals("credited", store.ayetCallback(ayet("t3"), sign(ayet("t3"))));
		assertEquals("capped", store.ayetCallback(ayet("t4"), sign(ayet("t4"))));
		assertEquals(18, db.balance(ME));
		Map<String, String> tampered = new HashMap<>(ayet("t5"));
		String signature = sign(tampered);
		tampered.put("uid", OTHER);
		assertNull(store.ayetCallback(tampered, signature));
	}

	@Test
	void questsNeedServerSeenPlayTime() throws Exception {
		var quest = store.rewards(ME).quests().stream().filter(q -> !q.id().startsWith("play")).findFirst().orElseThrow();
		StoreConfig.Quest def = new StoreConfig().quest(quest.id());
		db.addStat(ME, StoreService.today(), def.stat(), def.target());
		var view = store.rewards(ME).quests().stream().filter(q -> q.id().equals(quest.id())).findFirst().orElseThrow();
		assertFalse(view.claimable(), "counter alone must not pay without enough in-game time");
		assertThrows(StoreService.StoreException.class, () -> store.claimQuest(ME, quest.id()));
		assertEquals(0, db.balance(ME));
	}

	@Test
	void seventhDayInARowPaysTheWeeklyBonus() throws Exception {
		java.time.LocalDate today = java.time.LocalDate.parse(StoreService.today());
		StoreConfig.Rewards r = new StoreConfig().rewards;
		for (int back = 6; back >= 1; back--) {
			db.claimLogin(ME, today.minusDays(back).toString(), 0);
		}
		var view = store.rewards(ME);
		assertEquals(6, view.streak());
		assertTrue(view.weeklyBonusToday());
		assertEquals(r.dailyLoginCoins + Math.min(r.streakBonusMax, 6 * r.streakBonusPerDay) + r.weeklyStreakBonus, view.loginCoins());
		db.claimLogin(OTHER, today.minusDays(1).toString(), 0);
		assertFalse(store.rewards(OTHER).weeklyBonusToday());
	}

	@Test
	void reportedProgressIsRateCapped() throws Exception {
		store.reportProgress(ME, Map.of("mob_kills", 100_000L));
		long kills = db.stats(ME, StoreService.today()).getOrDefault("mob_kills", 0L);
		assertTrue(kills <= 40, "got " + kills);
	}

	private static Map<String, String> ayet(String tid) {
		return Map.of("uid", ME, "tid", tid, "amount", "6");
	}

	/** Independent re-implementation of ayeT's hash (sorted keys, form-encoded, HMAC-SHA256 hex). */
	private static String sign(Map<String, String> params) throws Exception {
		StringBuilder raw = new StringBuilder();
		for (String key : new java.util.TreeMap<>(params).keySet()) {
			raw.append(raw.length() == 0 ? "" : "&").append(key).append('=')
					.append(java.net.URLEncoder.encode(params.get(key), StandardCharsets.UTF_8));
		}
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("ayetkey".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(raw.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private static JsonObject paymentEvent(String id, String type, String txn, String orderId, String uuid, double paid, String currency) {
		return json("""
				{"id":"%s","type":"%s","subject":{"transaction_id":"%s","status":{"id":1},
				"price_paid":{"amount":%s,"currency":"%s"},
				"products":[{"id":1,"quantity":1,"custom":{"velo_order":"%s","velo_uuid":"%s"}}]}}"""
				.formatted(id, type, txn, paid, currency, orderId, uuid));
	}

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}
}
