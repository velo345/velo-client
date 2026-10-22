package net.veloclient.server;

import com.google.gson.JsonParser;
import net.veloclient.server.store.CosmeticsService;
import net.veloclient.server.store.StoreConfig;
import net.veloclient.server.store.StoreDatabase;
import net.veloclient.server.store.StoreService;
import net.veloclient.server.store.TebexCheckout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlusTest {

	private static final String ME = "0123456789abcdef0123456789abcdef";
	private static final String OWNER = "11111111111111111111111111111111";

	@TempDir
	Path dir;
	private StoreService store;
	private StoreDatabase db;

	@BeforeEach
	void setUp() throws Exception {
		CosmeticsService cosmetics = new CosmeticsService(dir);
		cosmetics.seedFromClasspath("/cosmetics-seed/", Map.of());
		db = new StoreDatabase(dir.resolve("store.db"));
		store = new StoreService(StoreConfig.load(dir), db, new TebexCheckout(null, null, null), Set.of(OWNER), null, null, q -> new String[] {q, "Player"});
		store.setCosmetics(cosmetics);
	}

	@Test
	void grantMakesAMemberPaysMonthlyCoinsOnceAndEnds() throws Exception {
		assertFalse(store.isPlus(ME));
		long until = store.grantPlus(ME, "Me", 30, "owner", null, null);
		assertTrue(store.isPlus(ME));
		assertTrue(until > System.currentTimeMillis() + 29L * 86_400_000L);
		assertEquals(store.config().plus.monthlyCoins, db.balance(ME), "the first month's coins come with the grant");
		store.plusSweep();
		store.plusSweep();
		assertEquals(store.config().plus.monthlyCoins, db.balance(ME), "no second payout within the month");
		assertTrue(store.wallet(ME, "Me").plus());
		store.endPlus(ME);
		assertFalse(store.isPlus(ME));
	}

	@Test
	void ownerCanGiveTemporaryPlusByDays() throws Exception {
		store.adminPlus(OWNER, "Owner", ME, 7);
		long left = store.plusUntil(ME) - System.currentTimeMillis();
		assertTrue(left > 6L * 86_400_000L && left <= 7L * 86_400_000L);
		assertThrows(StoreService.StoreException.class, () -> store.adminPlus(ME, "Me", ME, 7), "only owners");
		store.adminPlus(OWNER, "Owner", ME, 0);
		assertFalse(store.isPlus(ME));
	}

	@Test
	void addonsAreOnlyForPlayersTheOwnerPicked() throws Exception {
		store.grantPlus(ME, "Me", 30, "owner", null, null);
		assertFalse(store.hasAddons(ME), "Velo+ alone doesn't give the Addons");
		assertThrows(StoreService.StoreException.class, () -> store.adminAddons(ME, "Me", ME, true), "only owners");
		assertTrue(store.adminAddons(OWNER, "Owner", ME, true).addons());
		assertTrue(store.hasAddons(ME));
		store.loadAddonAccess();
		assertTrue(store.hasAddons(ME), "kept across reloads");
		assertFalse(store.adminAddons(OWNER, "Owner", ME, false).addons());
		assertFalse(store.hasAddons(ME));
	}

	@Test
	void aPaymentCountsOnceAndARefundTakesItsDaysBack() throws Exception {
		store.grantPlus(ME, "Me", 30, "tebex", "txn-1", "rec-1");
		long afterFirst = store.plusUntil(ME);
		store.grantPlus(ME, "Me", 30, "tebex", "txn-1", "rec-1");
		assertEquals(afterFirst, store.plusUntil(ME), "the same payment twice adds nothing");
		// A renewal reported twice by two events: the second one is recorded but adds no days.
		store.grantPlus(ME, "Me", 30, "tebex", "txn-2", "rec-1");
		assertEquals(afterFirst, store.plusUntil(ME));
		assertEquals(ME, db.plusMemberOfPayment("rec-1"));
		assertEquals(ME, db.reversePlusPayment("txn-1"));
		store.loadPlus();
		assertFalse(store.isPlus(ME), "refunded: the month is gone");
	}

	@Test
	void tokenIsSignedAndVerifiable() throws Exception {
		KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		PlusTokens tokens = new PlusTokens(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
		assertTrue(tokens.enabled());
		String token = tokens.issue(ME, System.currentTimeMillis() + 30L * 86_400_000L, true, false);
		String[] parts = token.split("\\.");
		assertEquals("v1", parts[0]);
		Signature verify = Signature.getInstance("Ed25519");
		verify.initVerify(pair.getPublic());
		verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
		assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])));
		var payload = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(ME, payload.get("uuid").getAsString());
		assertTrue(payload.get("until").getAsLong() <= System.currentTimeMillis() + PlusTokens.MAX_VALID_MILLIS + 1000, "a token lasts a week at most");
		assertTrue(payload.get("plus").getAsBoolean());
		assertFalse(payload.has("addons"), "members don't get the Addons claim");
		var addonOnly = tokens.issue(ME, 0, false, true).split("\\.");
		var addonPayload = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(addonOnly[1]), StandardCharsets.UTF_8)).getAsJsonObject();
		assertTrue(addonPayload.get("addons").getAsBoolean());
		assertFalse(addonPayload.get("plus").getAsBoolean());
		assertTrue(addonPayload.get("until").getAsLong() > System.currentTimeMillis() + PlusTokens.MAX_VALID_MILLIS - 60_000, "Addons-only proofs last a week");
		assertFalse(new PlusTokens(null).enabled());
	}

	@Test
	void addonJarsAreStoredPerMinecraftVersion() throws Exception {
		AddonStore addons = new AddonStore(dir);
		byte[] jar = new byte[2000];
		jar[0] = 'P';
		jar[1] = 'K';
		var entry = addons.put("26.2", "0.9.0", jar);
		assertEquals(2000, entry.size());
		assertEquals(1, addons.list().size());
		assertArrayEquals(jar, addons.jar("26.2"));
		assertNull(addons.jar("1.21.11"));
		assertThrows(IllegalArgumentException.class, () -> addons.put("26.2", "0.9.0", new byte[2000]), "not a jar");
		assertThrows(IllegalArgumentException.class, () -> addons.put("../x", "1", jar));
	}
}
