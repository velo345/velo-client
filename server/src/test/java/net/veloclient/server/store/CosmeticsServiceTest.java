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
	void emotesAreSoldWithTheirAnimation() throws Exception {
		var wave = cosmetics.item("emote_wave");
		assertNotNull(wave);
		assertEquals("emote", wave.type);
		assertTrue(wave.price > 0);
		assertTrue(cosmetics.buyable("emote_wave"));
		byte[] anim = cosmetics.file(wave.files.get("anim"));
		assertNotNull(anim);
		assertEquals("application/json", CosmeticsService.contentType(anim));
		// You only show an emote you own.
		assertFalse(store.mayDisplayCape(ME, "emote_wave"));
		db.adjust(ME, 5000, "test", null, "test");
		store.buy(ME, "Me", "emote_wave");
		assertTrue(store.mayDisplayCape(ME, "emote_wave"));
		// Any JSON isn't an emote.
		assertThrows(StoreService.StoreException.class, () -> cosmetics.storeFile("{\"x\":1}".getBytes()));
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

	@Test
	void ownerBundlesReplaceCollectionBundlesAndSell() throws Exception {
		var released = cosmetics.storeItems();
		assertTrue(released.size() >= 3);
		assertFalse(store.bundles().isEmpty() && cosmetics.storeCollections().stream().anyMatch(c -> c.items().size() >= 2));

		CosmeticsService.Bundle bundle = new CosmeticsService.Bundle();
		bundle.id = "starter";
		bundle.name = "Starter pack";
		bundle.items = java.util.List.of(released.get(0).id(), released.get(1).id(), released.get(2).id());
		bundle.price = 3000;
		bundle.active = true;
		cosmetics.upsertBundle(bundle);
		assertEquals(java.util.List.of("starter"), store.bundles().stream().map(StoreService.BundleView::id).toList());

		db.adjust(ME, 5000, "test", null, null);
		db.grantItem(ME, released.get(0).id(), "test");
		int full = released.get(0).price() + released.get(1).price() + released.get(2).price();
		int missing = full - released.get(0).price();
		store.buy(ME, "Me", "bundle:starter");
		assertTrue(db.owns(ME, released.get(1).id()) && db.owns(ME, released.get(2).id()));
		assertEquals(5000 - Math.max(10, (int) Math.round(3000.0 * missing / full / 10.0) * 10), db.balance(ME));
		assertEquals(409, assertThrows(StoreService.StoreException.class, () -> store.buy(ME, "Me", "bundle:starter")).status);

		bundle.active = false;
		cosmetics.upsertBundle(bundle);
		assertTrue(store.bundles().isEmpty());
		cosmetics.deleteBundle("starter");
		assertNull(cosmetics.storeBundles());
	}

	@Test
	void dailyDealIsChargedAtSalePrice() throws Exception {
		var catalog = store.catalog();
		assertEquals(Math.min(4, catalog.items().size()), catalog.daily().items().size());
		var deal = catalog.items().stream().filter(i -> i.fullPrice() != null).findFirst().orElseThrow();
		assertTrue(deal.price() < deal.fullPrice());
		db.adjust(ME, deal.price(), "test", null, null);
		store.buy(ME, "Me", deal.id());
		assertEquals(0, db.balance(ME));
	}
}
