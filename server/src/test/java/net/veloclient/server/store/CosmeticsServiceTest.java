package net.veloclient.server.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticsServiceTest {

	private static final String ME = "0123456789abcdef0123456789abcdef";
	private static final String OWNER = "11111111111111111111111111111111";

	@TempDir
	Path dir;
	private CosmeticsService cosmetics;
	private StoreService store;
	private StoreDatabase db;

	@BeforeEach
	void setUp() throws Exception {
		cosmetics = new CosmeticsService(dir);
		cosmetics.seedFromClasspath("/cosmetics-seed/", Map.of("wings_raven", 1234));
		db = new StoreDatabase(dir.resolve("store.db"));
		store = new StoreService(StoreConfig.load(dir), db, new TebexCheckout(null, null, null), Set.of(OWNER), null, null,
				query -> new String[] {query, "Player"});
		store.setCosmetics(cosmetics);
	}

	@Test
	void seedKeepsOldPricesAndStatuses() {
		assertEquals(1234, cosmetics.item("wings_raven").price);
		assertTrue(cosmetics.buyable("wings_raven"));
		assertEquals(CosmeticsService.STATUS_TESTING, cosmetics.item("wings_neon_cyan").status);
		assertFalse(cosmetics.buyable("wings_neon_cyan"));
		assertNotNull(cosmetics.item("wings_neon_cyan").files.get("mesh"));
	}

	@Test
	void testingItemsOnlyForOwnersAndPeopleWhoHaveThem() throws Exception {
		var publicView = cosmetics.catalog(Set.of(), false);
		assertTrue(publicView.items().stream().noneMatch(i -> i.id().equals("wings_neon_cyan")));
		assertTrue(publicView.collections().stream().noneMatch(c -> c.id().equals("neon")));
		assertTrue(cosmetics.catalog(Set.of(), true).items().stream().anyMatch(i -> i.id().equals("wings_neon_cyan")));
		var mine = cosmetics.catalog(Set.of("wings_neon_cyan"), false).items().stream().filter(i -> i.id().equals("wings_neon_cyan")).findFirst();
		assertTrue(mine.isPresent());
		assertFalse(mine.get().inStore());
		// Anyone can get what's needed to draw it on the owner.
		assertNotNull(cosmetics.renderInfo("wings_neon_cyan"));

		var buy = assertThrows(StoreService.StoreException.class, () -> store.buy(ME, "Me", "wings_neon_cyan"));
		assertEquals(404, buy.status);
		store.adminItem(OWNER, "Owner", ME, "wings_neon_cyan", true);
		assertTrue(store.mayDisplayCape(ME, "wings_neon_cyan"));
		assertFalse(store.mayDisplayCape(OWNER, "wings_neon_cyan"));
	}

	@Test
	void releaseAndTimeWindow() throws Exception {
		CosmeticsService.Item change = new CosmeticsService.Item();
		change.id = "wings_neon_red";
		change.status = CosmeticsService.STATUS_RELEASED;
		change.availableUntil = System.currentTimeMillis() - 1000;
		change.files = null;
		cosmetics.upsert(change, false);
		assertFalse(cosmetics.buyable("wings_neon_red"), "window already over");
		cosmetics.upsert(idOnly("wings_neon_red"), true);
		assertTrue(cosmetics.buyable("wings_neon_red"));
		assertTrue(store.catalog().items().stream().anyMatch(i -> i.id().equals("wings_neon_red")));
	}

	@Test
	void rejectsBadIdsAndMissingFiles() {
		assertThrows(StoreService.StoreException.class, () -> cosmetics.upsert(idOnly("Wings Bad"), false));
		assertThrows(StoreService.StoreException.class, () -> cosmetics.upsert(idOnly("wings_brand_new"), false));
		assertThrows(StoreService.StoreException.class, () -> cosmetics.storeFile("hello".getBytes()));
	}

	@Test
	void editsSurviveARestartAndReseed() throws Exception {
		CosmeticsService.Item change = idOnly("wings_cobweb");
		change.status = CosmeticsService.STATUS_HIDDEN;
		cosmetics.upsert(change, false);
		CosmeticsService again = new CosmeticsService(dir);
		again.seedFromClasspath("/cosmetics-seed/", Map.of());
		assertEquals(CosmeticsService.STATUS_HIDDEN, again.item("wings_cobweb").status);
		assertFalse(again.item("wings_cobweb").description.isEmpty(), "a status-only edit keeps the description");
		assertEquals(3, again.item("wings_cobweb").files.size());
	}

	private static CosmeticsService.Item idOnly(String id) {
		CosmeticsService.Item item = new CosmeticsService.Item();
		item.id = id;
		item.status = null;
		item.description = null;
		item.files = null;
		return item;
	}
}
