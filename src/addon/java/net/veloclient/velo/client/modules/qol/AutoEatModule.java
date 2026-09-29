package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Random;

/**
 * Eats automatically once your hunger drops to the configured level, so a long automated task
 * (or just not paying attention) never leaves you starving. Picks the first real food item it
 * finds in the hotbar, switches to it, and starts eating exactly the way a real right-click-hold
 * would - the actual chew/finish timing is entirely vanilla's own item-use ticking, nothing here
 * estimates or shortcuts it. Does nothing while already eating/using an item, so it never
 * interrupts a bow draw, shield block, or an already-started meal.
 *
 * <p>Waits out a short randomized reaction delay after first crossing the hunger threshold
 * before actually switching slots and eating, rather than reacting on the exact tick hunger
 * drops - a real player never notices and reaches for food that instantly. The delay is
 * abandoned (and re-rolled fresh next time) if hunger recovers or an item use starts before it
 * runs out, so it never fires late for a threshold that's no longer even true.
 *
 * <p>Remembers whatever slot was selected before switching to food and hands it back the moment
 * the eat finishes - without this, an automation module that also needs a specific hotbar item
 * selected (a bridging module placing blocks, say) would find its slot silently stolen and left
 * on food indefinitely. Combined with never eating while any item use is already active, this is
 * what actually lets {@code AutoEatModule} run alongside modules like {@code
 * EnderChestFarmerModule}/{@code AutoTunnelMinerModule} without fighting them for the hotbar.
 */
public final class AutoEatModule extends AbstractModule implements Configurable {

	private static final Random RANDOM = new Random();

	private int hungerThreshold = 17;
	private int minReactionTicks = 10;
	private int maxReactionTicks = 30;

	private int reactionTicksRemaining = -1;
	private int preEatSlot = -1;

	public AutoEatModule() {
		super("auto-eat", "Auto Eat",
				"Automatically eats a food item from your hotbar once your hunger drops to the configured "
						+ "level, after a short randomized reaction delay. Never interrupts an already-active "
						+ "item use (drawing a bow, blocking, or already eating).",
				ModuleCategory.QOL, SafetyTag.CHECK_SERVER_RULES, false);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || client.player == null || client.world == null) {
			return;
		}
		PlayerEntity player = client.player;
		if (preEatSlot >= 0 && !isUsingItem(player)) {
			// The eat this module itself started has finished - hand the previously selected slot
			// back rather than leaving it parked on food, so whatever else needs a specific item
			// selected (another module, or you manually) finds it exactly where it was.
			setSelectedSlot(player.getInventory(), preEatSlot);
			preEatSlot = -1;
		}
		if (isUsingItem(player) || foodLevel(player) > hungerThreshold) {
			reactionTicksRemaining = -1;
			return;
		}
		if (reactionTicksRemaining < 0) {
			int min = Math.min(minReactionTicks, maxReactionTicks);
			int max = Math.max(minReactionTicks, maxReactionTicks);
			reactionTicksRemaining = min + RANDOM.nextInt(max - min + 1);
		}
		if (reactionTicksRemaining-- > 0) {
			return;
		}
		reactionTicksRemaining = -1;
		int slot = findFoodHotbarSlot(player);
		if (slot < 0) {
			return;
		}
		var inventory = player.getInventory();
		preEatSlot = selectedSlot(inventory);
		setSelectedSlot(inventory, slot);
		interactItem(client);
	}

	/** {@code Inventory#size()/getStack(int)} (Yarn) -> {@code Container#getContainerSize()/getItem(int)} (Mojmap) - same indexed access, diverges by name only. */
	private static int findFoodHotbarSlot(PlayerEntity player) {
		var inventory = player.getInventory();
		//? if <26.1 {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inventory.getStack(i);
			//?} else {
			/*for (int i = 0; i < 9; i++) {
			ItemStack stack = inventory.getItem(i);
			*///?}
			if (!stack.isEmpty() && isFood(stack)) {
				return i;
			}
		}
		return -1;
	}

	/** {@code DataComponentTypes#FOOD} (Yarn) -> {@code DataComponents#FOOD} (Mojmap) - same "is this a real food item" check, diverges by package/holder-class name only. */
	private static boolean isFood(ItemStack stack) {
		//? if <26.1 {
		return stack.get(net.minecraft.component.DataComponentTypes.FOOD) != null;
		//?} else {
		/*return stack.get(net.minecraft.core.component.DataComponents.FOOD) != null;
		*///?}
	}

	/** {@code LivingEntity#isUsingItem()} (Yarn) -> {@code LivingEntity#isUsingItem()} (Mojmap) - identical name, kept as its own helper only for symmetry with the other version-conditioned helpers here. */
	private static boolean isUsingItem(PlayerEntity player) {
		return player.isUsingItem();
	}

	/** {@code PlayerInventory#getSelectedSlot()/setSelectedSlot(int)} (Yarn) -> {@code Inventory#getSelectedSlot()/setSelectedSlot(int)} (Mojmap) - identical names, only the enclosing inventory type's own name differs (handled generically via {@code var} at the call site). */
	private static int selectedSlot(Object inventory) {
		//? if <26.1 {
		return ((net.minecraft.entity.player.PlayerInventory) inventory).getSelectedSlot();
		//?} else {
		/*return ((net.minecraft.world.entity.player.Inventory) inventory).getSelectedSlot();
		*///?}
	}

	private static void setSelectedSlot(Object inventory, int slot) {
		//? if <26.1 {
		((net.minecraft.entity.player.PlayerInventory) inventory).setSelectedSlot(slot);
		//?} else {
		/*((net.minecraft.world.entity.player.Inventory) inventory).setSelectedSlot(slot);
		*///?}
	}

	/** {@code PlayerEntity#getHungerManager()#getFoodLevel()} (Yarn) -> {@code Player#getFoodData()#getFoodLevel()} (Mojmap) - same value, diverges by name only. */
	private static int foodLevel(PlayerEntity player) {
		//? if <26.1 {
		return player.getHungerManager().getFoodLevel();
		//?} else {
		/*return player.getFoodData().getFoodLevel();
		*///?}
	}

	/** {@code ClientPlayerInteractionManager#interactItem} (Yarn) -> {@code MultiPlayerGameMode#useItem} (Mojmap) - the exact same "start using the item in this hand" call a real right-click-hold makes, diverges by name only. */
	private static void interactItem(MinecraftClient client) {
		//? if <26.1 {
		client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
		//?} else {
		/*client.gameMode.useItem(client.player, net.minecraft.world.InteractionHand.MAIN_HAND);
		*///?}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Eat Below Hunger Level", 1, 19,
						() -> hungerThreshold, v -> hungerThreshold = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Min Reaction Delay (ticks)", 0, 60,
						() -> minReactionTicks, v -> minReactionTicks = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Max Reaction Delay (ticks)", 0, 60,
						() -> maxReactionTicks, v -> maxReactionTicks = (int) v, v -> String.valueOf((int) v)));
	}
}
