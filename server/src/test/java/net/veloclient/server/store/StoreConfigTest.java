package net.veloclient.server.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StoreConfigTest {

	@Test
	void addsNewItemsToAnOldStoreFileWithoutChangingPrices(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("store.json"), "{\"currency\":\"EUR\",\"coinPacks\":[],\"items\":[{\"id\":\"cape_red_code\",\"name\":\"Codebreaker\",\"price\":1500}]}");
		StoreConfig config = StoreConfig.load(dir);
		assertEquals(1500, config.item("cape_red_code").price());
		assertNotNull(config.item("wings_phoenix"));
		// Written back, so the owner can edit the new prices too.
		assertNotNull(StoreConfig.load(dir).item("wings_shadowfang"));
	}
}
