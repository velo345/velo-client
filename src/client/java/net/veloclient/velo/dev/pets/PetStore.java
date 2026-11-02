package net.veloclient.velo.dev.pets;

import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;
import net.veloclient.velo.client.network.StoreClient;

import java.util.ArrayList;
import java.util.List;

/** Pets as Store items ("pet_<id>" in the server's catalog): what's for sale, what it costs, and its preview sheet. */
final class PetStore {

	static final String PREFIX = "pet_";

	private PetStore() {
	}

	/** Pets the Store shows you: for sale, owned, or (for the owner) still in testing - in the module's order. */
	static List<CosmeticsCatalog.Item> shown() {
		List<CosmeticsCatalog.Item> out = new ArrayList<>();
		for (String id : PetsModule.PETS.keySet()) {
			CosmeticsCatalog.item(PREFIX + id).ifPresent(item -> {
				if (item.inStore() || StoreClient.owns(item.id()) || StoreClient.isOwner()) {
					out.add(item);
				}
			});
		}
		return out;
	}

	static java.util.Optional<CosmeticsCatalog.Item> item(String petId) {
		return CosmeticsCatalog.item(PREFIX + petId);
	}

	static int price(CosmeticsCatalog.Item item) {
		return StoreClient.price(item.id(), item.price());
	}

	static int accent(CosmeticsCatalog.Item item) {
		return item.accent() == null ? 0xFFFF3A2A : 0xFF000000 | item.accent();
	}

	static String petId(CosmeticsCatalog.Item item) {
		return item.id().substring(PREFIX.length());
	}
}
