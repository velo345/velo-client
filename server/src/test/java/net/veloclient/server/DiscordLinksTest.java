package net.veloclient.server;

import net.veloclient.server.store.CosmeticsService;
import net.veloclient.server.store.StoreConfig;
import net.veloclient.server.store.StoreDatabase;
import net.veloclient.server.store.StoreService;
import net.veloclient.server.store.TebexCheckout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiscordLinksTest {

	private static final String ME = "0123456789abcdef0123456789abcdef";
	private static final String OTHER = "fedcba9876543210fedcba9876543210";

	@TempDir
	Path dir;
	private StoreService store;
	private StoreDatabase db;
	private DiscordLinks links;

	@BeforeEach
	void setUp() throws Exception {
		CosmeticsService cosmetics = new CosmeticsService(dir);
		cosmetics.seedFromClasspath("/cosmetics-seed/", Map.of());
		db = new StoreDatabase(dir.resolve("store.db"));
		store = new StoreService(StoreConfig.load(dir), db, new TebexCheckout(null, null, null), Set.of(), null, null, q -> new String[] {q, "P"});
		store.setCosmetics(cosmetics);
		links = new DiscordLinks(dir, store);
	}

	@Test
	void linkingGivesTheNeonCapeAndItIsNeverForSale() throws Exception {
		assertFalse(store.cosmetics().buyable("cape_velo_neon"), "Velo Neon is earned, not bought");
		String code = links.createCode(ME, "Me");
		assertTrue(code.startsWith("VELO-"));
		var result = links.redeem(code, "111", "someone");
		assertEquals(ME, result.uuid());
		assertTrue(db.owns(ME, "cape_velo_neon"));
		assertNotNull(links.byUuid(ME));
		// A code works once.
		assertThrows(StoreService.StoreException.class, () -> links.redeem(code, "111", "someone"));
	}

	@Test
	void oneDiscordPerPlayerAndTheOtherWayRound() throws Exception {
		links.redeem(links.createCode(ME, "Me"), "111", "a");
		assertThrows(StoreService.StoreException.class, () -> links.redeem(links.createCode(ME, "Me"), "222", "b"));
		assertThrows(StoreService.StoreException.class, () -> links.redeem(links.createCode(OTHER, "Other"), "111", "a"));
	}

	@Test
	void unlinkRemovesTheCapeAndSurvivesRestart() throws Exception {
		links.redeem(links.createCode(ME, "Me"), "111", "a");
		assertEquals(ME, new DiscordLinks(dir, store).byUuid(ME).uuid());
		assertTrue(links.unlink("111", true));
		assertFalse(db.owns(ME, "cape_velo_neon"));
		assertFalse(links.unlink("111", true));
	}

	@Test
	void badCodesAreRefused() {
		assertThrows(StoreService.StoreException.class, () -> links.redeem("VELO-NOPE00", "1", "x"));
		assertThrows(StoreService.StoreException.class, () -> links.redeem(null, "1", "x"));
	}
}
