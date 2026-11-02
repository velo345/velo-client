package net.veloclient.velo.dev.storage;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Every Minecraft call Storage Memory needs, one {@code //? if} branch per mapping, so the rest of the feature stays
 * version-free (see the Stonecutter rule traps). Positions travel as plain ints, entities as {@link EntityInfo}.
 */
public final class StorageMc {

	/** A right-clicked villager / wandering trader. {@code npc}: Citizens-style server NPC (version 2 UUID). */
	public record EntityInfo(String uuid, double x, double y, double z, String profession, int level, String name, boolean named, boolean wandering, boolean npc) {
	}

	/** One trade as shown in the merchant screen. */
	public record Trade(ItemStack first, ItemStack second, ItemStack result, int uses, int maxUses) {
	}

	/** A slot of the open container screen, in screen coordinates. */
	public record ScreenSlot(int x, int y, ItemStack stack) {
	}

	private StorageMc() {
	}

	public static MinecraftClient client() {
		return MinecraftClient.getInstance();
	}

	public static boolean inWorld() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player != null && client.world != null;
	}

	public static double[] playerPos() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return null;
		}
		return new double[] {client.player.getX(), client.player.getY(), client.player.getZ(), client.player.getYaw()};
	}

	//? if <26.1 {
	// ---- Items ----

	public static boolean isEmpty(ItemStack stack) {
		return stack == null || stack.isEmpty();
	}

	public static String itemId(ItemStack stack) {
		return net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
	}

	public static int count(ItemStack stack) {
		return stack.getCount();
	}

	public static ItemStack stack(String id, int count) {
		net.minecraft.util.Identifier key = net.minecraft.util.Identifier.tryParse(id);
		if (key == null || !net.minecraft.registry.Registries.ITEM.containsId(key)) {
			return ItemStack.EMPTY;
		}
		return new ItemStack(net.minecraft.registry.Registries.ITEM.get(key), Math.max(1, Math.min(99, count)));
	}

	/** The item's own model when a server / resource pack swapped it (custom items), else null. */
	public static String itemModel(ItemStack stack) {
		var model = stack.get(net.minecraft.component.DataComponentTypes.ITEM_MODEL);
		if (model == null || model.toString().equals(itemId(stack))) {
			return null;
		}
		return model.toString();
	}

	/** A display stack with a remembered custom name / model (so custom server items look like themselves). */
	public static ItemStack decorate(ItemStack stack, String name, String model) {
		if (isEmpty(stack)) {
			return stack;
		}
		if (name != null) {
			stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal(name));
		}
		net.minecraft.util.Identifier key = model == null ? null : net.minecraft.util.Identifier.tryParse(model);
		if (key != null) {
			stack.set(net.minecraft.component.DataComponentTypes.ITEM_MODEL, key);
		}
		return stack;
	}

	/** Vanilla villagers show the level bar; plugin shops opening a merchant screen don't. */
	public static boolean merchantLeveled(Screen screen) {
		return ((net.minecraft.client.gui.screen.ingame.MerchantScreen) screen).getScreenHandler().isLeveled();
	}

	public static List<String> allItemIds() {
		List<String> ids = new ArrayList<>();
		for (net.minecraft.util.Identifier id : net.minecraft.registry.Registries.ITEM.getIds()) {
			ids.add(id.toString());
		}
		return ids;
	}

	public static String displayName(ItemStack stack) {
		return stack.getName().getString();
	}

	public static String customName(ItemStack stack) {
		var name = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
		return name == null ? null : name.getString();
	}

	public static List<String> lore(ItemStack stack) {
		var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
		List<String> lines = new ArrayList<>();
		if (lore != null) {
			lore.lines().forEach(line -> lines.add(line.getString()));
		}
		return lines;
	}

	/** {@code id=level} for every enchantment on the item, stored ones (books) included. */
	public static List<String> enchants(ItemStack stack) {
		List<String> list = new ArrayList<>();
		for (var type : List.of(net.minecraft.component.DataComponentTypes.ENCHANTMENTS, net.minecraft.component.DataComponentTypes.STORED_ENCHANTMENTS)) {
			var enchantments = stack.get(type);
			if (enchantments != null) {
				for (var entry : enchantments.getEnchantmentEntries()) {
					list.add(entry.getKey().getIdAsString() + "=" + entry.getIntValue());
				}
			}
		}
		return list;
	}

	public static boolean isShulker(ItemStack stack) {
		return stack.getItem() instanceof net.minecraft.item.BlockItem item && item.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock;
	}

	/** What's inside a shulker box (27 slots) or bundle; empty for anything else. */
	public static List<ItemStack> nested(ItemStack stack) {
		List<ItemStack> list = new ArrayList<>();
		var container = stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
		if (container != null) {
			net.minecraft.util.collection.DefaultedList<ItemStack> slots = net.minecraft.util.collection.DefaultedList.ofSize(27, ItemStack.EMPTY);
			container.copyTo(slots);
			list.addAll(slots);
		}
		var bundle = stack.get(net.minecraft.component.DataComponentTypes.BUNDLE_CONTENTS);
		if (bundle != null) {
			bundle.iterateCopy().forEach(list::add);
		}
		return list;
	}

	// ---- Enchantments (data-driven: the server's registry) ----

	/** Every enchantment the server knows: {id, display name, max level}. */
	public static List<String[]> enchantments() {
		MinecraftClient client = MinecraftClient.getInstance();
		List<String[]> list = new ArrayList<>();
		if (client.world == null) {
			return list;
		}
		var registry = client.world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
		registry.streamEntries().forEach(entry -> list.add(new String[] {entry.getIdAsString(),
				entry.value().description().getString(), String.valueOf(entry.value().getMaxLevel())}));
		return list;
	}

	/** "Mending", "Sharpness V" - or null when the enchantment is unknown here. */
	public static String enchantName(String id, int level) {
		MinecraftClient client = MinecraftClient.getInstance();
		net.minecraft.util.Identifier key = net.minecraft.util.Identifier.tryParse(id);
		if (client.world == null || key == null) {
			return null;
		}
		var registry = client.world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
		return registry.getEntry(key).map(entry -> net.minecraft.enchantment.Enchantment.getName(entry, level).getString()).orElse(null);
	}

	// ---- World ----

	/** The hashed seed of the world you're in (0 when unknown). */
	public static long seedHash() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return 0;
		}
		return ((net.veloclient.velo.dev.mixin.BiomeSeedAccessor) client.world.getBiomeAccess()).velo$seed();
	}

	private static net.minecraft.util.math.BlockPos pos(int x, int y, int z) {
		return new net.minecraft.util.math.BlockPos(x, y, z);
	}

	public static boolean loaded(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.world != null && client.world.isPosLoaded(pos(x, y, z));
	}

	/** Block id at a loaded position, or null. */
	public static String blockId(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!loaded(x, y, z)) {
			return null;
		}
		return net.minecraft.registry.Registries.BLOCK.getId(client.world.getBlockState(pos(x, y, z)).getBlock()).toString();
	}

	/** Slots of the item container at this block (ender chest = 27), or -1 when it isn't one. */
	public static int containerSize(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!loaded(x, y, z)) {
			return -1;
		}
		var blockEntity = client.world.getBlockEntity(pos(x, y, z));
		if (blockEntity instanceof net.minecraft.block.entity.EnderChestBlockEntity) {
			return 27;
		}
		if (blockEntity instanceof net.minecraft.inventory.Inventory inventory) {
			return inventory.size();
		}
		return -1;
	}

	public static boolean isEnderChest(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		return loaded(x, y, z) && client.world.getBlockEntity(pos(x, y, z)) instanceof net.minecraft.block.entity.EnderChestBlockEntity;
	}

	/** The other half of a double chest, or null. */
	public static int[] otherChestHalf(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!loaded(x, y, z)) {
			return null;
		}
		var state = client.world.getBlockState(pos(x, y, z));
		if (!(state.getBlock() instanceof net.minecraft.block.ChestBlock)
				|| state.get(net.minecraft.block.ChestBlock.CHEST_TYPE) == net.minecraft.block.enums.ChestType.SINGLE) {
			return null;
		}
		var other = pos(x, y, z).offset(net.minecraft.block.ChestBlock.getFacing(state));
		return new int[] {other.getX(), other.getY(), other.getZ()};
	}

	// ---- Screens ----

	public static boolean isContainerScreen(Screen screen) {
		return screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>
				&& !(screen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen)
				&& !(screen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen);
	}

	public static boolean isMerchantScreen(Screen screen) {
		return screen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen;
	}

	/** Vanilla's own title key ("container.enderchest") - null for a literal title (what plugin menus use). */
	public static String titleKey(Screen screen) {
		return screen.getTitle().getContent() instanceof net.minecraft.text.TranslatableTextContent translatable ? translatable.getKey() : null;
	}

	public static String title(Screen screen) {
		return screen.getTitle().getString();
	}

	/** The container's own slots (not your inventory below it). */
	public static List<ItemStack> containerSlots(Screen screen) {
		List<ItemStack> list = new ArrayList<>();
		for (var slot : ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler().slots) {
			if (!(slot.inventory instanceof net.minecraft.entity.player.PlayerInventory)) {
				list.add(slot.getStack());
			}
		}
		return list;
	}

	/** Every slot of the open screen with its position (for highlighting the searched item). */
	public static List<ScreenSlot> screenSlots(Screen screen) {
		List<ScreenSlot> list = new ArrayList<>();
		var accessor = (net.veloclient.velo.client.mixin.HandledScreenAccessor) screen;
		for (var slot : ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler().slots) {
			list.add(new ScreenSlot(accessor.velo$left() + slot.x, accessor.velo$top() + slot.y, slot.getStack()));
		}
		return list;
	}

	public static List<Trade> trades(Screen screen) {
		List<Trade> list = new ArrayList<>();
		for (var offer : ((net.minecraft.client.gui.screen.ingame.MerchantScreen) screen).getScreenHandler().getRecipes()) {
			list.add(new Trade(offer.getDisplayedFirstBuyItem(), offer.getDisplayedSecondBuyItem(), offer.getSellItem(), offer.getUses(), offer.getMaxUses()));
		}
		return list;
	}

	/** Your inventory right now (main, armor, offhand). */
	public static List<ItemStack> playerInventory() {
		MinecraftClient client = MinecraftClient.getInstance();
		List<ItemStack> list = new ArrayList<>();
		if (client.player != null) {
			var inventory = client.player.getInventory();
			for (int i = 0; i < inventory.size(); i++) {
				list.add(inventory.getStack(i));
			}
		}
		return list;
	}

	// ---- Interactions ----

	/** Calls back with the block you right-click and the villager / trader you right-click (client side). */
	public static void onInteract(Consumer<int[]> block, Consumer<EntityInfo> trader) {
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient() && player == MinecraftClient.getInstance().player) {
				var pos = hit.getBlockPos();
				block.accept(new int[] {pos.getX(), pos.getY(), pos.getZ()});
			}
			return net.minecraft.util.ActionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient() && player == MinecraftClient.getInstance().player) {
				EntityInfo info = null;
				if (entity instanceof net.minecraft.entity.passive.VillagerEntity villager) {
					var data = villager.getVillagerData();
					info = new EntityInfo(villager.getUuid().toString(), villager.getX(), villager.getY(), villager.getZ(),
							data.profession().getIdAsString(), data.level(), villager.getName().getString(), villager.hasCustomName(), false, villager.getUuid().version() == 2);
				} else if (entity instanceof net.minecraft.entity.passive.WanderingTraderEntity wanderer) {
					info = new EntityInfo(wanderer.getUuid().toString(), wanderer.getX(), wanderer.getY(), wanderer.getZ(),
							"minecraft:wandering_trader", 0, wanderer.getName().getString(), wanderer.hasCustomName(), true, wanderer.getUuid().version() == 2);
				}
				if (info != null) {
					trader.accept(info);
				}
			}
			return net.minecraft.util.ActionResult.PASS;
		});
	}

	// ---- Drawing ----

	public static void drawItem(DrawContext context, ItemStack stack, int x, int y, String countLabel) {
		context.drawItem(stack, x, y);
		context.drawStackOverlay(MinecraftClient.getInstance().textRenderer, stack, x, y, countLabel);
	}

	public static void drawTooltip(DrawContext context, ItemStack stack, int x, int y) {
		context.drawItemTooltip(MinecraftClient.getInstance().textRenderer, stack, x, y);
	}
	//?} else {
	/*// ---- Items ----

	public static boolean isEmpty(ItemStack stack) {
		return stack == null || stack.isEmpty();
	}

	public static String itemId(ItemStack stack) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	public static int count(ItemStack stack) {
		return stack.getCount();
	}

	public static ItemStack stack(String id, int count) {
		net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
		if (key == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
			return ItemStack.EMPTY;
		}
		return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(key), Math.max(1, Math.min(99, count)));
	}

	public static String itemModel(ItemStack stack) {
		var model = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
		if (model == null || model.toString().equals(itemId(stack))) {
			return null;
		}
		return model.toString();
	}

	public static ItemStack decorate(ItemStack stack, String name, String model) {
		if (isEmpty(stack)) {
			return stack;
		}
		if (name != null) {
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name));
		}
		net.minecraft.resources.Identifier key = model == null ? null : net.minecraft.resources.Identifier.tryParse(model);
		if (key != null) {
			stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, key);
		}
		return stack;
	}

	public static boolean merchantLeveled(Screen screen) {
		return ((net.minecraft.client.gui.screens.inventory.MerchantScreen) screen).getMenu().showProgressBar();
	}

	public static List<String> allItemIds() {
		List<String> ids = new ArrayList<>();
		for (net.minecraft.resources.Identifier id : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
			ids.add(id.toString());
		}
		return ids;
	}

	public static String displayName(ItemStack stack) {
		return stack.getHoverName().getString();
	}

	public static String customName(ItemStack stack) {
		var name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		return name == null ? null : name.getString();
	}

	public static List<String> lore(ItemStack stack) {
		var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		List<String> lines = new ArrayList<>();
		if (lore != null) {
			lore.lines().forEach(line -> lines.add(line.getString()));
		}
		return lines;
	}

	public static List<String> enchants(ItemStack stack) {
		List<String> list = new ArrayList<>();
		for (var type : List.of(net.minecraft.core.component.DataComponents.ENCHANTMENTS, net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS)) {
			var enchantments = stack.get(type);
			if (enchantments != null) {
				for (var entry : enchantments.entrySet()) {
					list.add(entry.getKey().getRegisteredName() + "=" + entry.getIntValue());
				}
			}
		}
		return list;
	}

	public static boolean isShulker(ItemStack stack) {
		return stack.getItem() instanceof net.minecraft.world.item.BlockItem item && item.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
	}

	public static List<ItemStack> nested(ItemStack stack) {
		List<ItemStack> list = new ArrayList<>();
		var container = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
		if (container != null) {
			net.minecraft.core.NonNullList<ItemStack> slots = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
			container.copyInto(slots);
			list.addAll(slots);
		}
		var bundle = stack.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			bundle.itemCopyStream().forEach(list::add);
		}
		return list;
	}

	// ---- Enchantments ----

	public static List<String[]> enchantments() {
		MinecraftClient client = MinecraftClient.getInstance();
		List<String[]> list = new ArrayList<>();
		if (client.level == null) {
			return list;
		}
		var registry = client.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
		registry.listElements().forEach(entry -> list.add(new String[] {entry.getRegisteredName(),
				entry.value().description().getString(), String.valueOf(entry.value().getMaxLevel())}));
		return list;
	}

	public static String enchantName(String id, int level) {
		MinecraftClient client = MinecraftClient.getInstance();
		net.minecraft.resources.Identifier key = net.minecraft.resources.Identifier.tryParse(id);
		if (client.level == null || key == null) {
			return null;
		}
		var registry = client.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
		return registry.get(key).map(entry -> net.minecraft.world.item.enchantment.Enchantment.getFullname(entry, level).getString()).orElse(null);
	}

	// ---- World ----

	public static long seedHash() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.level == null) {
			return 0;
		}
		return ((net.veloclient.velo.dev.mixin.BiomeSeedAccessor) client.level.getBiomeManager()).velo$seed();
	}

	private static net.minecraft.core.BlockPos pos(int x, int y, int z) {
		return new net.minecraft.core.BlockPos(x, y, z);
	}

	public static boolean loaded(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.level != null && client.level.isLoaded(pos(x, y, z));
	}

	public static String blockId(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!loaded(x, y, z)) {
			return null;
		}
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos(x, y, z)).getBlock()).toString();
	}

	public static int containerSize(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!loaded(x, y, z)) {
			return -1;
		}
		var blockEntity = client.level.getBlockEntity(pos(x, y, z));
		if (blockEntity instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity) {
			return 27;
		}
		if (blockEntity instanceof net.minecraft.world.Container container) {
			return container.getContainerSize();
		}
		return -1;
	}

	public static boolean isEnderChest(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		return loaded(x, y, z) && client.level.getBlockEntity(pos(x, y, z)) instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity;
	}

	public static int[] otherChestHalf(int x, int y, int z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!loaded(x, y, z)) {
			return null;
		}
		var state = client.level.getBlockState(pos(x, y, z));
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
				|| state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
			return null;
		}
		var other = pos(x, y, z).relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
		return new int[] {other.getX(), other.getY(), other.getZ()};
	}

	// ---- Screens ----

	public static boolean isContainerScreen(Screen screen) {
		return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
				&& !(screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)
				&& !(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen);
	}

	public static boolean isMerchantScreen(Screen screen) {
		return screen instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen;
	}

	public static String titleKey(Screen screen) {
		return screen.getTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable ? translatable.getKey() : null;
	}

	public static String title(Screen screen) {
		return screen.getTitle().getString();
	}

	public static List<ItemStack> containerSlots(Screen screen) {
		List<ItemStack> list = new ArrayList<>();
		for (var slot : ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen).getMenu().slots) {
			if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) {
				list.add(slot.getItem());
			}
		}
		return list;
	}

	public static List<ScreenSlot> screenSlots(Screen screen) {
		List<ScreenSlot> list = new ArrayList<>();
		var accessor = (net.veloclient.velo.client.mixin.HandledScreenAccessor) screen;
		for (var slot : ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen).getMenu().slots) {
			list.add(new ScreenSlot(accessor.velo$left() + slot.x, accessor.velo$top() + slot.y, slot.getItem()));
		}
		return list;
	}

	public static List<Trade> trades(Screen screen) {
		List<Trade> list = new ArrayList<>();
		for (var offer : ((net.minecraft.client.gui.screens.inventory.MerchantScreen) screen).getMenu().getOffers()) {
			list.add(new Trade(offer.getCostA(), offer.getCostB(), offer.getResult(), offer.getUses(), offer.getMaxUses()));
		}
		return list;
	}

	public static List<ItemStack> playerInventory() {
		MinecraftClient client = MinecraftClient.getInstance();
		List<ItemStack> list = new ArrayList<>();
		if (client.player != null) {
			var inventory = client.player.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				list.add(inventory.getItem(i));
			}
		}
		return list;
	}

	// ---- Interactions ----

	public static void onInteract(Consumer<int[]> block, Consumer<EntityInfo> trader) {
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && player == MinecraftClient.getInstance().player) {
				var pos = hit.getBlockPos();
				block.accept(new int[] {pos.getX(), pos.getY(), pos.getZ()});
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() && player == MinecraftClient.getInstance().player) {
				EntityInfo info = null;
				if (entity instanceof net.minecraft.world.entity.npc.villager.Villager villager) {
					var data = villager.getVillagerData();
					info = new EntityInfo(villager.getUUID().toString(), villager.getX(), villager.getY(), villager.getZ(),
							data.profession().getRegisteredName(), data.level(), villager.getName().getString(), villager.hasCustomName(), false, villager.getUUID().version() == 2);
				} else if (entity instanceof net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader wanderer) {
					info = new EntityInfo(wanderer.getUUID().toString(), wanderer.getX(), wanderer.getY(), wanderer.getZ(),
							"minecraft:wandering_trader", 0, wanderer.getName().getString(), wanderer.hasCustomName(), true, wanderer.getUUID().version() == 2);
				}
				if (info != null) {
					trader.accept(info);
				}
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
	}

	// ---- Drawing ----

	public static void drawItem(DrawContext context, ItemStack stack, int x, int y, String countLabel) {
		context.item(stack, x, y);
		context.itemDecorations(MinecraftClient.getInstance().textRenderer, stack, x, y, countLabel);
	}

	public static void drawTooltip(DrawContext context, ItemStack stack, int x, int y) {
		context.setTooltipForNextFrame(MinecraftClient.getInstance().textRenderer, stack, x, y);
	}
	*///?}
}
