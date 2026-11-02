package net.veloclient.launcher.ui;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.PetsSetting;
import net.veloclient.launcher.social.StoreApi;

/** One pet in the launcher's Store: its turntable, the price and Buy (Velo Coins) - or take it out / put it away. */
public final class PetDetailView {

	public interface Host {
		MinecraftSession session();

		void goBack();

		default void openCoins() {
		}
	}

	private PetDetailView() {
	}

	public static Node build(Host host, PetsSetting.StorePet pet) {
		StackPane stage = CosmeticUi.stage(420, 480);
		stage.getChildren().add(new PetSheetView(pet, 400, true));
		String id = pet.pet().id();
		int price = StoreApi.price(pet.itemId(), pet.price());
		ItemPage.Purchase purchase = new ItemPage.Purchase(pet.itemId(), price,
				() -> PetsSetting.owns(id),
				() -> id.equals(PetsSetting.equipped()),
				() -> PetsSetting.equip(id),
				() -> PetsSetting.equip(null),
				() -> {
					try {
						StoreApi.buy(pet.itemId());
						return null;
					} catch (StoreApi.StoreError error) {
						return error.getMessage();
					}
				}, "pet", pet.inStore());
		return ItemPage.build(host::goBack, host::openCoins, stage, null, "Follows you in every game", pet.pet().accent(), "Pets",
				StoreTiles.Rarity.of(pet.itemId(), price), pet.pet().name(), pet.description(),
				java.util.List.of("Walks and flies with you, does tricks when you stand still", "Rides on your shoulder, celebrates your kills",
						"Every Velo player around you sees it"), null, purchase);
	}
}
