package net.veloclient.velo.client.pvp;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ConsumableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.consume.ApplyEffectsConsumeEffect;
import net.minecraft.item.consume.ConsumeEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.object.AtlasTextObjectContents;
import net.minecraft.util.Identifier;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
*///?}

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every Minecraft call the PvP modules need, in one place with one branch per mapping (Yarn up to
 * 1.21.11, Mojang names from 26.1). The modules themselves only use these helpers and plain Java,
 * so they compile unchanged on every supported version.
 *
 * <p>Nothing here reads anything the server didn't send this client: other players' health and
 * effect particles are synced entity data, held items and their components are synced equipment.
 */
public final class PvpCompat {

	/** What a potion, golden apple etc. applies: effect id path ("strength"), amplifier, duration in ticks. */
	public record EffectSpec(String id, int amplifier, int durationTicks) {
	}

	/** An effect as seen on another player: id path, display name, beneficial or not. */
	public record EffectInfo(String id, String name, boolean beneficial, int color) {
	}

	private static Map<String, EffectInfo> particleTable;

	private PvpCompat() {
	}

	// ---------------------------------------------------------------- players

	public static int localPlayerId() {
		//? if <26.1 {
		var player = MinecraftClient.getInstance().player;
		//?} else {
		/*var player = Minecraft.getInstance().player;
		*///?}
		return player == null ? Integer.MIN_VALUE : player.getId();
	}

	public static LivingEntity localPlayer() {
		//? if <26.1 {
		return MinecraftClient.getInstance().player;
		//?} else {
		/*return Minecraft.getInstance().player;
		*///?}
	}

	/** Every player entity in the world except yourself. */
	public static List<LivingEntity> otherPlayers() {
		List<LivingEntity> result = new ArrayList<>();
		//? if <26.1 {
		var world = MinecraftClient.getInstance().world;
		if (world == null) {
			return result;
		}
		for (PlayerEntity p : world.getPlayers()) {
			if (p != MinecraftClient.getInstance().player) {
				result.add(p);
			}
		}
		//?} else {
		/*var level = Minecraft.getInstance().level;
		if (level == null) {
			return result;
		}
		for (Player p : level.players()) {
			if (p != Minecraft.getInstance().player) {
				result.add(p);
			}
		}
		*///?}
		return result;
	}

	public static Entity entityById(int id) {
		//? if <26.1 {
		var world = MinecraftClient.getInstance().world;
		return world == null ? null : world.getEntityById(id);
		//?} else {
		/*var level = Minecraft.getInstance().level;
		return level == null ? null : level.getEntity(id);
		*///?}
	}

	public static boolean isPlayer(Entity entity) {
		//? if <26.1 {
		return entity instanceof PlayerEntity;
		//?} else {
		/*return entity instanceof Player;
		*///?}
	}

	/**
	 * A real player rather than an NPC: servers' NPCs (Citizens, lobby shopkeepers, practice bots...) are player entities too, but
	 * they're not in the tab list's player data, or carry a version-2 UUID. Fight tracking and the Target HUD only count real players.
	 */
	public static boolean isRealPlayer(Entity entity) {
		if (!isPlayer(entity)) {
			return false;
		}
		java.util.UUID id = uuid(entity);
		if (id == null || id.version() == 2) {
			return false;
		}
		//? if <26.1 {
		var handler = MinecraftClient.getInstance().getNetworkHandler();
		return handler == null || handler.getPlayerListEntry(id) != null;
		//?} else {
		/*var connection = Minecraft.getInstance().getConnection();
		return connection == null || connection.getPlayerInfo(id) != null;
		*///?}
	}

	/** On your side: the same scoreboard team as you (minigame teams), or in your Velo party. */
	public static boolean isTeammate(Entity entity) {
		LivingEntity self = localPlayer();
		if (self == null || entity == null) {
			return false;
		}
		//? if <26.1 {
		if (self.isTeammate(entity)) {
			return true;
		}
		//?} else {
		/*if (self.isAlliedTo(entity)) {
			return true;
		}
		*///?}
		var party = net.veloclient.velo.client.network.SocialClient.party();
		if (party != null && party.has("members")) {
			String id = String.valueOf(uuid(entity)).replace("-", "");
			for (var member : party.getAsJsonArray("members")) {
				var o = member.getAsJsonObject();
				if (o.has("uuid") && id.equalsIgnoreCase(o.get("uuid").getAsString().replace("-", ""))) {
					return true;
				}
			}
		}
		return false;
	}

	/** Someone you'd actually fight: a real player who isn't on your team. */
	public static boolean isOpponent(Entity entity) {
		return isRealPlayer(entity) && !isTeammate(entity);
	}

	public static String name(Entity entity) {
		return entity.getName().getString();
	}

	public static java.util.UUID uuid(Entity entity) {
		//? if <26.1 {
		return entity.getUuid();
		//?} else {
		/*return entity.getUUID();
		*///?}
	}

	public static int hurtTime(LivingEntity entity) {
		return entity.hurtTime;
	}

	public static int maxHurtTime(LivingEntity entity) {
		//? if <26.1 {
		return entity.maxHurtTime;
		//?} else {
		/*return entity.hurtDuration;
		*///?}
	}

	/** Arm swing progress 0..1 (0 = not swinging). */
	public static float swingProgress(LivingEntity entity) {
		//? if <26.1 {
		return entity.getHandSwingProgress(1f);
		//?} else if <26.3 {
		/*return entity.getAttackAnim(1f);
		*///?} else {
		/*return entity.getSwingAnimation(1f);
		*///?}
	}

	public static boolean hasFireResistance(LivingEntity entity) {
		//? if <26.1 {
		return entity.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE);
		//?} else {
		/*return entity.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
		*///?}
	}

	public static boolean onGround(Entity entity) {
		//? if <26.1 {
		return entity.isOnGround();
		//?} else {
		/*return entity.onGround();
		*///?}
	}

	public static boolean isSwimming(Entity entity) {
		return entity.isSwimming();
	}

	public static double x(Entity e) {
		return e.getX();
	}

	public static double y(Entity e) {
		return e.getY();
	}

	public static double z(Entity e) {
		return e.getZ();
	}

	/**
	 * Text floating just above {@code player}'s head - text displays and named armor stands, which
	 * servers use to draw names, ranks and health when they hide the vanilla name tag.
	 */
	public static List<String> floatingTextAbove(LivingEntity player) {
		List<String> texts = new ArrayList<>();
		double x = player.getX();
		double top = player.getBoundingBox().maxY;
		double z = player.getZ();
		try {
			//? if <26.1 {
			var world = MinecraftClient.getInstance().world;
			if (world == null) {
				return texts;
			}
			var area = new net.minecraft.util.math.Box(x - 0.8, top - 0.4, z - 0.8, x + 0.8, top + 2.6, z + 0.8);
			for (Entity e : world.getOtherEntities(player, area, e -> !(e instanceof LivingEntity) || e.hasCustomName())) {
				if (e instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity) {
					texts.add(((net.veloclient.velo.client.mixin.HologramAccessor) e).velo$text().getString());
				} else if (e.hasCustomName() && e.getCustomName() != null) {
					texts.add(e.getCustomName().getString());
				}
			}
			//?} else {
			/*var level = Minecraft.getInstance().level;
			if (level == null) {
				return texts;
			}
			var area = new net.minecraft.world.phys.AABB(x - 0.8, top - 0.4, z - 0.8, x + 0.8, top + 2.6, z + 0.8);
			for (Entity e : level.getEntities(player, area, e -> !(e instanceof LivingEntity) || e.hasCustomName())) {
				if (e instanceof net.minecraft.world.entity.Display.TextDisplay) {
					texts.add(((net.veloclient.velo.client.mixin.HologramAccessor) e).velo$text().getString());
				} else if (e.hasCustomName() && e.getCustomName() != null) {
					texts.add(e.getCustomName().getString());
				}
			}
			*///?}
		} catch (RuntimeException | LinkageError ignored) {
			// Accessor not applied on this setup - nothing to read.
		}
		return texts;
	}

	/** Your attack reach in blocks (the entity interaction range attribute - 3 in vanilla survival). */
	public static double attackRange() {
		//? if <26.1 {
		var p = MinecraftClient.getInstance().player;
		return p == null ? 3.0 : p.getEntityInteractionRange();
		//?} else {
		/*var p = Minecraft.getInstance().player;
		return p == null ? 3.0 : p.entityInteractionRange();
		*///?}
	}

	/** The camera's world position {x, y, z} this frame. */
	public static double[] cameraPos() {
		//? if <26.1 {
		var pos = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
		//?} else if <26.2 {
		/*var pos = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		*///?} else {
		/*var pos = Minecraft.getInstance().gameRenderer.mainCamera().position();
		*///?}
		return new double[] {pos.x, pos.y, pos.z};
	}

	/** The camera's {yaw, pitch} in degrees this frame. */
	public static float[] cameraRotation() {
		//? if <26.1 {
		var camera = MinecraftClient.getInstance().gameRenderer.getCamera();
		return new float[] {camera.getYaw(), camera.getPitch()};
		//?} else if <26.2 {
		/*var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		return new float[] {camera.yRot(), camera.xRot()};
		*///?} else {
		/*var camera = Minecraft.getInstance().gameRenderer.mainCamera();
		return new float[] {camera.yRot(), camera.xRot()};
		*///?}
	}

	/**
	 * Top of the first solid block at or below (x, y, z), scanning at most {@code depth} blocks
	 * down - the floor under you even mid-jump. NaN if there's nothing solid that close.
	 */
	public static double groundBelow(double x, double y, double z, int depth) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		int top = (int) Math.floor(y + 0.01);
		//? if <26.1 {
		var world = MinecraftClient.getInstance().world;
		if (world == null) {
			return Double.NaN;
		}
		for (int by = top; by >= top - depth; by--) {
			var pos = new net.minecraft.util.math.BlockPos(bx, by, bz);
			var shape = world.getBlockState(pos).getCollisionShape(world, pos);
			if (!shape.isEmpty()) {
				double surface = by + shape.getMax(net.minecraft.util.math.Direction.Axis.Y);
				if (surface <= y + 0.01) {
					return surface;
				}
			}
		}
		//?} else {
		/*var level = Minecraft.getInstance().level;
		if (level == null) {
			return Double.NaN;
		}
		for (int by = top; by >= top - depth; by--) {
			var pos = new net.minecraft.core.BlockPos(bx, by, bz);
			var shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (!shape.isEmpty()) {
				double surface = by + shape.max(net.minecraft.core.Direction.Axis.Y);
				if (surface <= y + 0.01) {
					return surface;
				}
			}
		}
		*///?}
		return Double.NaN;
	}

	/** Render-interpolated position {x, y, z} of {@code entity} for this frame (smooth while moving). */
	public static double[] renderPos(Entity entity) {
		//? if <26.1 {
		float partial = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(true);
		var pos = entity.getLerpedPos(partial);
		//?} else {
		/*float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
		var pos = entity.getPosition(partial);
		*///?}
		return new double[] {pos.x, pos.y, pos.z};
	}

	/** Distance from the local player's eyes to the closest point of {@code target}'s hitbox. */
	public static double reachTo(Entity target) {
		LivingEntity self = localPlayer();
		if (self == null) {
			return 0;
		}
		double ex = self.getX();
		double ey = self.getEyeY();
		double ez = self.getZ();
		var bounds = target.getBoundingBox();
		double cx = Math.max(bounds.minX, Math.min(ex, bounds.maxX));
		double cy = Math.max(bounds.minY, Math.min(ey, bounds.maxY));
		double cz = Math.max(bounds.minZ, Math.min(ez, bounds.maxZ));
		double dx = cx - ex;
		double dy = cy - ey;
		double dz = cz - ez;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public static double distanceTo(Entity target) {
		LivingEntity self = localPlayer();
		if (self == null) {
			return 0;
		}
		double dx = target.getX() - self.getX();
		double dy = target.getY() - self.getY();
		double dz = target.getZ() - self.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ---------------------------------------------------------------- items

	public static ItemStack mainHand(LivingEntity entity) {
		//? if <26.1 {
		return entity.getMainHandStack();
		//?} else {
		/*return entity.getMainHandItem();
		*///?}
	}

	public static ItemStack offHand(LivingEntity entity) {
		//? if <26.1 {
		return entity.getOffHandStack();
		//?} else {
		/*return entity.getOffhandItem();
		*///?}
	}

	/** The stack being used right now (eaten, drunk, drawn, blocked with), or empty. */
	public static ItemStack activeItem(LivingEntity entity) {
		//? if <26.1 {
		return entity.isUsingItem() ? entity.getActiveItem() : ItemStack.EMPTY;
		//?} else {
		/*return entity.isUsingItem() ? entity.getUseItem() : ItemStack.EMPTY;
		*///?}
	}

	/** Armor pieces top to bottom (helmet first), empty stacks included. */
	public static List<ItemStack> armor(LivingEntity entity) {
		List<ItemStack> list = new ArrayList<>(4);
		//? if <26.1 {
		list.add(entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD));
		list.add(entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST));
		list.add(entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS));
		list.add(entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET));
		//?} else {
		/*list.add(entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
		list.add(entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
		list.add(entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS));
		list.add(entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET));
		*///?}
		return list;
	}

	public static boolean isSword(ItemStack stack) {
		//? if <26.1 {
		return stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS);
		//?} else {
		/*return stack.is(net.minecraft.tags.ItemTags.SWORDS);
		*///?}
	}

	public static boolean isShield(ItemStack stack) {
		//? if <26.1 {
		return stack.isOf(Items.SHIELD);
		//?} else {
		/*return stack.is(Items.SHIELD);
		*///?}
	}

	public static boolean isFishingRod(ItemStack stack) {
		//? if <26.1 {
		return stack.isOf(Items.FISHING_ROD);
		//?} else {
		/*return stack.is(Items.FISHING_ROD);
		*///?}
	}

	public static boolean isBow(ItemStack stack) {
		//? if <26.1 {
		return stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW);
		//?} else {
		/*return stack.is(Items.BOW) || stack.is(Items.CROSSBOW);
		*///?}
	}

	/** Splash potion with Instant Health ("pot" in PvP slang). */
	public static boolean isHealingSplash(ItemStack stack) {
		if (!isSplashPotion(stack)) {
			return false;
		}
		for (EffectSpec spec : potionEffects(stack)) {
			if (spec.id().equals("instant_health")) {
				return true;
			}
		}
		return false;
	}

	public static boolean isSplashPotion(ItemStack stack) {
		//? if <26.1 {
		return stack.isOf(Items.SPLASH_POTION);
		//?} else {
		/*return stack.is(Items.SPLASH_POTION);
		*///?}
	}

	public static boolean isPotion(ItemStack stack) {
		//? if <26.1 {
		return stack.isOf(Items.POTION) || stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
		//?} else {
		/*return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
		*///?}
	}

	/** Effects a potion stack applies (empty for anything else). */
	public static List<EffectSpec> potionEffects(ItemStack stack) {
		List<EffectSpec> list = new ArrayList<>();
		//? if <26.1 {
		PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
		if (contents != null) {
			for (StatusEffectInstance instance : contents.getEffects()) {
				list.add(spec(instance));
			}
		}
		//?} else {
		/*PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents != null) {
			for (MobEffectInstance instance : contents.getAllEffects()) {
				list.add(spec(instance));
			}
		}
		*///?}
		return list;
	}

	/** Effects eating/drinking {@code stack} applies (golden apples, suspicious stew...), certain ones only. */
	public static List<EffectSpec> consumeEffects(ItemStack stack) {
		List<EffectSpec> list = new ArrayList<>();
		//? if <26.1 {
		ConsumableComponent consumable = stack.get(DataComponentTypes.CONSUMABLE);
		if (consumable != null) {
			for (ConsumeEffect effect : consumable.onConsumeEffects()) {
				if (effect instanceof ApplyEffectsConsumeEffect apply && apply.probability() >= 1f) {
					for (StatusEffectInstance instance : apply.effects()) {
						list.add(spec(instance));
					}
				}
			}
		}
		//?} else {
		/*Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable != null) {
			for (ConsumeEffect effect : consumable.onConsumeEffects()) {
				if (effect instanceof ApplyStatusEffectsConsumeEffect apply && apply.probability() >= 1f) {
					for (MobEffectInstance instance : apply.effects()) {
						list.add(spec(instance));
					}
				}
			}
		}
		*///?}
		return list;
	}

	/** Ticks it takes to eat/drink {@code stack}, or -1 if it isn't consumable. */
	public static int consumeTicks(ItemStack stack) {
		//? if <26.1 {
		ConsumableComponent consumable = stack.get(DataComponentTypes.CONSUMABLE);
		//?} else {
		/*Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		*///?}
		return consumable == null ? -1 : Math.round(consumable.consumeSeconds() * 20f);
	}

	/** {nutrition, saturation} of a food stack, or null. */
	public static float[] food(ItemStack stack) {
		//? if <26.1 {
		FoodComponent food = stack.get(DataComponentTypes.FOOD);
		//?} else {
		/*FoodProperties food = stack.get(DataComponents.FOOD);
		*///?}
		return food == null ? null : new float[] {food.nutrition(), food.saturation()};
	}

	/** Registry id of the stack's item, e.g. "minecraft:arrow". */
	public static String itemId(ItemStack stack) {
		//? if <26.1 {
		return String.valueOf(Registries.ITEM.getId(stack.getItem()));
		//?} else {
		/*return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		*///?}
	}

	public static boolean sameItem(ItemStack a, ItemStack b) {
		return a.getItem() == b.getItem();
	}

	public static ItemStack copy(ItemStack stack) {
		return stack.copy();
	}

	public static ItemStack itemStack(String id) {
		//? if <26.1 {
		return new ItemStack(Registries.ITEM.get(Identifier.of(id)));
		//?} else {
		/*return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
		*///?}
	}

	/** Thrown splash potions in the world: entity id -> {x, y, z} and their effects. */
	public static Map<Integer, Object[]> thrownPotions() {
		Map<Integer, Object[]> result = new HashMap<>();
		//? if <26.1 {
		var world = MinecraftClient.getInstance().world;
		if (world == null) {
			return result;
		}
		for (Entity e : world.getEntities()) {
			if (e instanceof PotionEntity potion) {
				result.put(e.getId(), new Object[] {e.getX(), e.getY(), e.getZ(), potionEffects(potion.getStack())});
			}
		}
		//?} else {
		/*var level = Minecraft.getInstance().level;
		if (level == null) {
			return result;
		}
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof AbstractThrownPotion potion) {
				result.put(e.getId(), new Object[] {e.getX(), e.getY(), e.getZ(), potionEffects(potion.getItem())});
			}
		}
		*///?}
		return result;
	}

	// ---------------------------------------------------------------- cooldowns

	/** One running item cooldown: its group id, an icon, and exact ticks left / total. */
	public record CooldownView(String group, ItemStack icon, int remainingTicks, int totalTicks) {
	}

	/** Every item cooldown running on the local player right now (ender pearl, wind charge, shield...). */
	public static List<CooldownView> cooldowns() {
		try {
			return readCooldowns();
		} catch (RuntimeException | LinkageError e) {
			// The accessor mixin is optional; without it there's simply nothing to show.
			return List.of();
		}
	}

	private static List<CooldownView> readCooldowns() {
		List<CooldownView> list = new ArrayList<>();
		//? if <26.1 {
		var player = MinecraftClient.getInstance().player;
		if (player == null) {
			return list;
		}
		Object manager = player.getItemCooldownManager();
		//?} else {
		/*var player = Minecraft.getInstance().player;
		if (player == null) {
			return list;
		}
		Object manager = player.getCooldowns();
		*///?}
		var accessor = (net.veloclient.velo.client.mixin.ItemCooldownsAccessor) manager;
		int now = accessor.velo$tick();
		for (var entry : accessor.velo$entries().entrySet()) {
			int[] times = startEnd(entry.getValue());
			if (times == null || times[1] <= now) {
				continue;
			}
			String group = entry.getKey().toString();
			ItemStack icon = itemStack(group);
			if (icon.isEmpty()) {
				icon = findCooldownItem(manager, group);
			}
			list.add(new CooldownView(group, icon, times[1] - now, Math.max(1, times[1] - times[0])));
		}
		return list;
	}

	/** The cooldown entry is a private record (startTick, endTick) - read its two int components by type, which also works under 1.21.11's runtime names. */
	private static int[] startEnd(Object record) {
		try {
			var components = record.getClass().getRecordComponents();
			int[] values = new int[2];
			int found = 0;
			for (var component : components) {
				if (component.getType() == int.class && found < 2) {
					var accessor = component.getAccessor();
					accessor.setAccessible(true);
					values[found++] = (int) accessor.invoke(record);
				}
			}
			return found == 2 ? values : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** A cooldown group that isn't an item id (custom use_cooldown groups) - use whichever carried item belongs to it. */
	private static ItemStack findCooldownItem(Object manager, String group) {
		for (ItemStack stack : net.veloclient.velo.client.util.ClientCompat.inventoryStacks()) {
			if (stack.isEmpty()) {
				continue;
			}
			//? if <26.1 {
			Identifier id = ((net.minecraft.entity.player.ItemCooldownManager) manager).getGroup(stack);
			//?} else {
			/*Identifier id = ((net.minecraft.world.item.ItemCooldowns) manager).getCooldownGroup(stack);
			*///?}
			if (id != null && id.toString().equals(group)) {
				return stack;
			}
		}
		return itemStack("minecraft:clock");
	}

	// ---------------------------------------------------------------- effects

	//? if <26.1 {
	private static EffectSpec spec(StatusEffectInstance instance) {
		Identifier id = Registries.STATUS_EFFECT.getId(instance.getEffectType().value());
		return new EffectSpec(id == null ? "unknown" : id.getPath(), instance.getAmplifier(), instance.getDuration());
	}
	//?} else {
	/*private static EffectSpec spec(MobEffectInstance instance) {
		Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value());
		return new EffectSpec(id == null ? "unknown" : id.getPath(), instance.getAmplifier(), instance.getDuration());
	}
	*///?}

	/**
	 * The effects visible on {@code entity}: each effect's particle (synced entity data, the same
	 * swirls everyone sees) matched back to the effect that makes it. Beacon/ambient effects make
	 * the same particle, hidden-particle effects don't show at all - that's all a client can know.
	 */
	public static List<EffectInfo> visibleEffects(LivingEntity entity) {
		try {
			return readVisibleEffects(entity);
		} catch (RuntimeException | AssertionError | LinkageError e) {
			return List.of();
		}
	}

	private static List<EffectInfo> readVisibleEffects(LivingEntity entity) {
		List<EffectInfo> result = new ArrayList<>();
		Map<String, EffectInfo> table = particleTable();
		//? if <26.1 {
		List<ParticleEffect> particles = entity.getDataTracker().get(net.veloclient.velo.client.mixin.LivingEntityEffectsAccessor.velo$effectParticles());
		for (ParticleEffect particle : particles) {
			EffectInfo info = table.get(particleKey(particle));
			if (info != null && !result.contains(info)) {
				result.add(info);
			}
		}
		//?} else {
		/*List<ParticleOptions> particles = entity.getEntityData().get(net.veloclient.velo.client.mixin.LivingEntityEffectsAccessor.velo$effectParticles());
		for (ParticleOptions particle : particles) {
			EffectInfo info = table.get(particleKey(particle));
			if (info != null && !result.contains(info)) {
				result.add(info);
			}
		}
		*///?}
		return result;
	}

	public static synchronized EffectInfo effectInfo(String id) {
		for (EffectInfo info : particleTable().values()) {
			if (info.id().equals(id)) {
				return info;
			}
		}
		return null;
	}

	private static synchronized Map<String, EffectInfo> particleTable() {
		if (particleTable != null) {
			return particleTable;
		}
		Map<String, EffectInfo> table = new HashMap<>();
		//? if <26.1 {
		for (StatusEffect effect : Registries.STATUS_EFFECT) {
			Identifier id = Registries.STATUS_EFFECT.getId(effect);
			RegistryEntry<StatusEffect> entry = Registries.STATUS_EFFECT.getEntry(effect);
			try {
				ParticleEffect particle = effect.createParticle(new StatusEffectInstance(entry, 100));
				table.putIfAbsent(particleKey(particle), new EffectInfo(id.getPath(), effect.getName().getString(), effect.isBeneficial(), effect.getColor()));
			} catch (RuntimeException ignored) {
				// An effect whose particle can't be made without a real entity - skip it.
			}
		}
		//?} else {
		/*for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
			Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect);
			Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect);
			try {
				ParticleOptions particle = effect.createParticleOptions(new MobEffectInstance(holder, 100));
				table.putIfAbsent(particleKey(particle), new EffectInfo(id.getPath(), effect.getDisplayName().getString(), effect.isBeneficial(), effect.getColor()));
			} catch (RuntimeException ignored) {
				// An effect whose particle can't be made without a real entity - skip it.
			}
		}
		*///?}
		particleTable = table;
		return table;
	}

	//? if <26.1 {
	private static String particleKey(ParticleEffect particle) {
		String type = String.valueOf(Registries.PARTICLE_TYPE.getId(particle.getType()));
		if (particle instanceof TintedParticleEffect tinted) {
			return type + "#" + rgb(tinted.getRed(), tinted.getGreen(), tinted.getBlue());
		}
		return type;
	}
	//?} else {
	/*private static String particleKey(ParticleOptions particle) {
		String type = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()));
		if (particle instanceof ColorParticleOption tinted) {
			return type + "#" + rgb(tinted.getRed(), tinted.getGreen(), tinted.getBlue());
		}
		return type;
	}
	*///?}

	private static int rgb(float r, float g, float b) {
		return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
	}

	// ---------------------------------------------------------------- combat

	/** Calls {@code onAttack} with the entity id whenever the local player attacks something. */
	public static void onLocalAttack(java.util.function.IntConsumer onAttack) {
		//? if <26.1 {
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient() && player == MinecraftClient.getInstance().player) {
				onAttack.accept(entity.getId());
			}
			return net.minecraft.util.ActionResult.PASS;
		});
		//?} else {
		/*net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() && player == Minecraft.getInstance().player) {
				onAttack.accept(entity.getId());
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		*///?}
	}

	/**
	 * Whether the local player's attack right now is a critical hit, by vanilla's own rule: falling,
	 * not on the ground, a ladder or in water, not blind, not riding, attack charged above 90%, not
	 * sprinting. Checked when the attack is sent, before the charge resets.
	 */
	public static boolean isCritAttack() {
		//? if <26.1 {
		var p = MinecraftClient.getInstance().player;
		return p != null && p.fallDistance > 0 && !p.isOnGround() && !p.isClimbing() && !p.isTouchingWater()
				&& !p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS) && !p.hasVehicle()
				&& p.getAttackCooldownProgress(0.5f) > 0.9f && !p.isSprinting();
		//?} else {
		/*var p = Minecraft.getInstance().player;
		return p != null && p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater()
				&& !p.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) && !p.isPassenger()
				&& p.getAttackStrengthScale(0.5f) > 0.9f && !p.isSprinting();
		*///?}
	}

	/** Living entities (players and mobs) within {@code radius} blocks of the local player, yourself excluded. */
	public static List<LivingEntity> livingNear(double radius) {
		List<LivingEntity> list = new ArrayList<>();
		LivingEntity self = localPlayer();
		if (self == null) {
			return list;
		}
		double r2 = radius * radius;
		//? if <26.1 {
		var world = MinecraftClient.getInstance().world;
		if (world == null) {
			return list;
		}
		for (Entity e : world.getEntities()) {
		//?} else {
		/*var level = Minecraft.getInstance().level;
		if (level == null) {
			return list;
		}
		for (Entity e : level.entitiesForRendering()) {
		*///?}
			if (e instanceof LivingEntity living && e != self) {
				double dx = e.getX() - self.getX();
				double dy = e.getY() - self.getY();
				double dz = e.getZ() - self.getZ();
				if (dx * dx + dy * dy + dz * dz <= r2) {
					list.add(living);
				}
			}
		}
		return list;
	}

	/** The player the crosshair is on right now, or null. */
	public static LivingEntity crosshairPlayer() {
		//? if <26.1 {
		Entity target = MinecraftClient.getInstance().targetedEntity;
		//?} else {
		/*Entity target = Minecraft.getInstance().crosshairPickEntity;
		*///?}
		return isPlayer(target) ? (LivingEntity) target : null;
	}

	/**
	 * Hit particles on {@code target}: {@code style} is one of Crit, Magic, Blood, Hearts, Flame,
	 * Soul, Totem, Sparks, Notes, Cloud, Dust (Dust uses {@code rgb}). Client-only particles, like
	 * vanilla's own crit sparks.
	 */
	public static void spawnHitParticles(Entity target, String style, int count, int rgb) {
		java.util.Random random = new java.util.Random();
		//? if <26.1 {
		double width = target.getWidth();
		double height = target.getHeight();
		//?} else {
		/*double width = target.getBbWidth();
		double height = target.getBbHeight();
		*///?}
		//? if <26.1 {
		var world = MinecraftClient.getInstance().world;
		if (world == null) {
			return;
		}
		net.minecraft.particle.ParticleEffect effect = switch (style) {
			case "Magic" -> net.minecraft.particle.ParticleTypes.ENCHANTED_HIT;
			case "Blood" -> new net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK,
					net.minecraft.block.Blocks.REDSTONE_BLOCK.getDefaultState());
			case "Hearts" -> net.minecraft.particle.ParticleTypes.HEART;
			case "Flame" -> net.minecraft.particle.ParticleTypes.FLAME;
			case "Soul" -> net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME;
			case "Totem" -> net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING;
			case "Sparks" -> net.minecraft.particle.ParticleTypes.ELECTRIC_SPARK;
			case "Notes" -> net.minecraft.particle.ParticleTypes.NOTE;
			case "Cloud" -> net.minecraft.particle.ParticleTypes.CLOUD;
			case "Dust" -> new net.minecraft.particle.DustParticleEffect(rgb & 0xFFFFFF, 1.0f);
			default -> net.minecraft.particle.ParticleTypes.CRIT;
		};
		for (int i = 0; i < count; i++) {
			double px = target.getX() + (random.nextDouble() - 0.5) * width;
			double py = target.getY() + random.nextDouble() * height;
			double pz = target.getZ() + (random.nextDouble() - 0.5) * width;
			world.addParticleClient(effect, px, py, pz, (random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 0.3, (random.nextDouble() - 0.5) * 0.4);
		}
		//?} else {
		/*var level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		net.minecraft.core.particles.ParticleOptions effect = switch (style) {
			case "Magic" -> net.minecraft.core.particles.ParticleTypes.ENCHANTED_HIT;
			case "Blood" -> new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
					net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState());
			case "Hearts" -> net.minecraft.core.particles.ParticleTypes.HEART;
			case "Flame" -> net.minecraft.core.particles.ParticleTypes.FLAME;
			case "Soul" -> net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME;
			case "Totem" -> net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING;
			case "Sparks" -> net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK;
			case "Notes" -> net.minecraft.core.particles.ParticleTypes.NOTE;
			case "Cloud" -> net.minecraft.core.particles.ParticleTypes.CLOUD;
			case "Dust" -> new net.minecraft.core.particles.DustParticleOptions(rgb & 0xFFFFFF, 1.0f);
			default -> net.minecraft.core.particles.ParticleTypes.CRIT;
		};
		for (int i = 0; i < count; i++) {
			double px = target.getX() + (random.nextDouble() - 0.5) * width;
			double py = target.getY() + random.nextDouble() * height;
			double pz = target.getZ() + (random.nextDouble() - 0.5) * width;
			level.addParticle(effect, px, py, pz, (random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 0.3, (random.nextDouble() - 0.5) * 0.4);
		}
		*///?}
	}

	// ---------------------------------------------------------------- sounds, chat, world

	/** Plays a vanilla sound by id (e.g. "minecraft:entity.experience_orb.pickup") as a UI sound - only you hear it. */
	public static void playUiSound(String id, float pitch, float volume) {
		try {
			//? if <26.1 {
			var event = Registries.SOUND_EVENT.get(Identifier.of(id));
			if (event != null) {
				MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(event, pitch, volume));
			}
			//?} else {
			/*var event = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id));
			if (event != null) {
				Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(event, pitch, volume));
			}
			*///?}
		} catch (RuntimeException ignored) {
			// Unknown id - nothing to play.
		}
	}

	/** Sends a chat message as you (only where a module explicitly does that, like Auto GG). */
	public static void sendChat(String message) {
		//? if <26.1 {
		var handler = MinecraftClient.getInstance().getNetworkHandler();
		if (handler != null) {
			handler.sendChatMessage(message);
		}
		//?} else {
		/*var connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.sendChat(message);
		}
		*///?}
	}

	/** Calls {@code listener} with the plain text of every chat and system (game) message you receive. */
	public static void onChatText(java.util.function.Consumer<String> listener) {
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				listener.accept(message.getString());
			}
		});
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register(
				(message, signed, sender, params, timestamp) -> listener.accept(message.getString()));
	}

	/** {lowest, highest} buildable Y of the current world, or null. */
	public static int[] worldHeight() {
		//? if <26.1 {
		var world = MinecraftClient.getInstance().world;
		return world == null ? null : new int[] {world.getBottomY(), world.getTopYInclusive()};
		//?} else {
		/*var level = Minecraft.getInstance().level;
		return level == null ? null : new int[] {level.getMinY(), level.getMaxY()};
		*///?}
	}

	// ---------------------------------------------------------------- text

	/** A GUI-atlas sprite drawn inline as one text glyph, e.g. "hud/heart/full" or "mob_effect/speed". */
	//? if <26.1 {
	public static MutableText sprite(String path) {
		return Text.object(new AtlasTextObjectContents(Identifier.of("minecraft", "gui"), Identifier.of("minecraft", path)));
	}

	public static MutableText literal(String text, int rgb) {
		return Text.literal(text).setStyle(Style.EMPTY.withColor(rgb & 0xFFFFFF));
	}

	public static MutableText empty() {
		return Text.empty();
	}
	//?} else {
	/*public static MutableComponent sprite(String path) {
		return Component.object(new AtlasSprite(Identifier.fromNamespaceAndPath("minecraft", "gui"), Identifier.fromNamespaceAndPath("minecraft", path)));
	}

	public static MutableComponent literal(String content, int rgb) {
		return Component.literal(content).withColor(rgb & 0xFFFFFF);
	}

	public static MutableComponent empty() {
		return Component.empty();
	}
	*///?}
}
