package net.veloclient.velo.client.modules.cosmetics;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
//? if <26.1 {
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.ActionResult;
//?} else {
/*import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionResult;
*///?}
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Plays a purely client-side, purely visual effect (particles + optional
 * sound) at an entity's position when it dies, for however nearby a death
 * counts as {@link #trigger}. Players are always watched; {@link
 * #includeAllEntities} additionally watches every other living entity too
 * (useful for testing the effect without needing a second real player, and
 * a fun option to just have on).
 *
 * <p>Deliberately NOT detected from vanilla's own death chat message - that
 * was this module's first approach and it was unreliable in exactly the way
 * it sounds: message wording is locale/plugin-dependent, and a real game log
 * showed the message can even arrive before the victim's own health-sync
 * packet is applied, racing the very check meant to confirm it. Instead,
 * every watched entity's health is tracked directly each tick (see {@link
 * #onTick}) - a &gt;0 -&gt; &lt;=0 transition (or the entity going
 * not-{@code isAlive()}) on the client's own authoritative entity state IS
 * the death, no parsing, no locale, no race. "Killed by a player" is
 * likewise derived from directly-observable client state instead of text:
 * {@link AttackEntityCallback} records every entity the local player
 * actually attacked and when, and a death within {@link
 * #ATTACK_ATTRIBUTION_WINDOW_TICKS} of that counts as "by me"; a death with
 * another tracked player within {@link #NEARBY_PLAYER_RADIUS} counts as "by
 * a player" more broadly.
 */
public final class KillEffectsModule extends AbstractModule implements Configurable {

	private static final List<String> TRIGGER_OPTIONS =
			List.of("Any Nearby Death", "Player Killed By Player", "Only My Kills");
	private static final List<String> EFFECT_OPTIONS = List.of(
			"Lightning Strike", "Color Burst", "Soul Implosion", "Totem Shatter",
			"Firework Salute", "Void Pull", "Electric Surge", "Confetti Rain");
	private static final List<String> SOUND_OPTIONS = List.of(
			"Automatic", "Thunder", "Anvil Land", "Totem", "Wither Spawn",
			"Ender Dragon Death", "Orb Pickup", "Firework Blast");
	private static final int ATTACK_ATTRIBUTION_WINDOW_TICKS = 100;
	private static final double NEARBY_PLAYER_RADIUS = 6.0;

	private final Map<UUID, Float> lastHealth = new HashMap<>();
	private final Map<UUID, Integer> lastAttackedByMeTick = new HashMap<>();
	private int tickCounter;

	private String trigger = "Only My Kills";
	private String effect = "Lightning Strike";
	private String soundOverride = "Automatic";
	private int effectColor = 0xFFFF3030;
	private boolean soundEnabled = true;
	private boolean includeAllEntities = false;

	public KillEffectsModule() {
		super("kill-effects", "Kill Effects",
				"Plays a purely client-side visual effect (with optional sound) when a nearby entity dies - "
						+ "pick when it triggers and which animation/sound plays.",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		AttackEntityCallback.EVENT.register(this::onAttackEntity);
	}

	//? if <26.1 {
	private ActionResult onAttackEntity(PlayerEntity player, net.minecraft.world.World world,
			net.minecraft.util.Hand hand, Entity entity, net.minecraft.util.hit.EntityHitResult hitResult) {
		if (entity instanceof LivingEntity target) {
			lastAttackedByMeTick.put(target.getUuid(), tickCounter);
		}
		return ActionResult.PASS;
	}
	//?} else {
	/*private InteractionResult onAttackEntity(PlayerEntity player, net.minecraft.world.level.Level world,
			net.minecraft.world.InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hitResult) {
		if (entity instanceof LivingEntity target) {
			lastAttackedByMeTick.put(target.getUuid(), tickCounter);
		}
		return InteractionResult.PASS;
	}
	*///?}

	private void onTick(MinecraftClient client) {
		tickCounter++;
		if (!isEnabled() || client.world == null || client.player == null) {
			lastHealth.clear();
			return;
		}
		List<PlayerEntity> players = trackedPlayers(client);
		List<LivingEntity> watched = includeAllEntities ? trackedLivingEntities(client) : List.copyOf(players);
		Set<UUID> currentIds = new HashSet<>();
		for (LivingEntity entity : watched) {
			UUID id = entity.getUuid();
			currentIds.add(id);
			float health = entity.getHealth();
			Float previous = lastHealth.put(id, health);
			if (previous != null && previous > 0f && (health <= 0f || !entity.isAlive())) {
				onEntityDied(client, entity, players);
			}
		}
		lastHealth.keySet().retainAll(currentIds);
		lastAttackedByMeTick.keySet().retainAll(currentIds);
	}

	private void onEntityDied(MinecraftClient client, LivingEntity victim, List<PlayerEntity> allPlayers) {
		Integer attackedTick = lastAttackedByMeTick.get(victim.getUuid());
		boolean killedByMe = attackedTick != null && (tickCounter - attackedTick) <= ATTACK_ATTRIBUTION_WINDOW_TICKS;
		boolean killedByPlayer = killedByMe || isAnotherPlayerNearby(victim, allPlayers);
		boolean shouldTrigger = switch (trigger) {
			case "Only My Kills" -> killedByMe;
			case "Player Killed By Player" -> killedByPlayer;
			default -> true;
		};
		if (shouldTrigger) {
			playEffect(client, victim.getX(), victim.getY() + entityHeight(victim) / 2, victim.getZ());
		}
	}

	private static boolean isAnotherPlayerNearby(LivingEntity victim, List<PlayerEntity> allPlayers) {
		for (PlayerEntity other : allPlayers) {
			if (other != victim && other.squaredDistanceTo(victim) <= NEARBY_PLAYER_RADIUS * NEARBY_PLAYER_RADIUS) {
				return true;
			}
		}
		return false;
	}

	//? if <26.1 {
	private static List<PlayerEntity> trackedPlayers(MinecraftClient client) {
		return List.copyOf(client.world.getPlayers());
	}

	private static List<LivingEntity> trackedLivingEntities(MinecraftClient client) {
		ClientWorld world = client.world;
		List<LivingEntity> result = new ArrayList<>();
		for (Entity entity : world.getEntities()) {
			if (entity instanceof LivingEntity living) {
				result.add(living);
			}
		}
		return result;
	}

	private static float entityHeight(LivingEntity entity) {
		return entity.getHeight();
	}
	//?} else {
	/*private static List<PlayerEntity> trackedPlayers(MinecraftClient client) {
		return List.copyOf(client.world.players());
	}

	private static List<LivingEntity> trackedLivingEntities(MinecraftClient client) {
		ClientWorld world = client.world;
		List<LivingEntity> result = new ArrayList<>();
		for (Entity entity : world.entitiesForRendering()) {
			if (entity instanceof LivingEntity living) {
				result.add(living);
			}
		}
		return result;
	}

	private static float entityHeight(LivingEntity entity) {
		return entity.getBbHeight();
	}
	*///?}

	private void playEffect(MinecraftClient client, double x, double y, double z) {
		switch (effect) {
			case "Lightning Strike" -> lightningStrike(client, x, y, z);
			case "Color Burst" -> colorBurst(client, x, y, z);
			case "Soul Implosion" -> soulImplosion(client, x, y, z);
			case "Totem Shatter" -> totemShatter(client, x, y, z);
			case "Firework Salute" -> fireworkSalute(client, x, y, z);
			case "Void Pull" -> voidPull(client, x, y, z);
			case "Electric Surge" -> electricSurge(client, x, y, z);
			case "Confetti Rain" -> confettiRain(client, x, y, z);
			default -> lightningStrike(client, x, y, z);
		}
	}

	private void lightningStrike(MinecraftClient client, double x, double y, double z) {
		// A real lightning bolt entity, added straight to the client's own
		// world (never sent to/by the server) - vanilla's actual lightning
		// visuals and thunder sound, not an imitation built from particles.
		// setCosmetic/setVisualOnly (vanilla's own flag for exactly this
		// "purely decorative" case, e.g. trial-chamber ominous events) skips
		// the fire-starting/entity-striking logic, though since this entity
		// only ever exists on this client and the server never authorized
		// it, that logic wouldn't do anything server-authoritative anyway.
		spawnRealLightning(client, x, y, z);
		for (int ring = 0; ring < 3; ring++) {
			ring(client, ParticleTypes.ELECTRIC_SPARK, x, y + ring * 0.7, z, 1.0 + ring * 0.6, 24);
		}
		for (int i = 0; i < 80; i++) {
			double offsetY = i * 0.25;
			spawnParticle(client, ParticleTypes.ELECTRIC_SPARK, x, y + offsetY, z,
					(RANDOM.nextDouble() - 0.5) * 0.15, 0, (RANDOM.nextDouble() - 0.5) * 0.15);
		}
		if (!"Automatic".equals(soundOverride)) {
			playSound(client, x, y, z, effectiveSound("thunder"), 1.0f, 1.0f);
		}
	}

	private void colorBurst(MinecraftClient client, double x, double y, double z) {
		int color = effectColor & 0xFFFFFF;
		for (int wave = 0; wave < 3; wave++) {
			for (int i = 0; i < 70; i++) {
				double angle = RANDOM.nextDouble() * Math.PI * 2;
				double speed = 0.25 + RANDOM.nextDouble() * 0.55;
				spawnColoredDust(client, x, y + 0.5 + wave * 0.4, z,
						Math.cos(angle) * speed, 0.15 + RANDOM.nextDouble() * 0.55, Math.sin(angle) * speed, color, 1.8f);
			}
		}
		playSound(client, x, y, z, effectiveSound("firework_blast"), 1.0f, 1.2f);
		if (soundEnabled && "Automatic".equals(soundOverride)) {
			playSound(client, x, y, z, effectiveSound("firework_large_blast"), 0.8f, 1.4f);
		}
	}

	private void soulImplosion(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 90; i++) {
			double angle = RANDOM.nextDouble() * Math.PI * 2;
			double radius = 1.5 + RANDOM.nextDouble() * 1.5;
			double px = x + Math.cos(angle) * radius;
			double pz = z + Math.sin(angle) * radius;
			spawnParticle(client, ParticleTypes.SOUL, px, y + RANDOM.nextDouble(), pz, (x - px) * 0.06, 0.08, (z - pz) * 0.06);
		}
		for (int i = 0; i < 3; i++) {
			spawnParticle(client, ParticleTypes.EXPLOSION, x, y + i * 0.4, z, 0, 0, 0);
		}
		playSound(client, x, y, z, effectiveSound("warden_roar"), 0.6f, 1.6f);
	}

	private void totemShatter(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 90; i++) {
			double angle = RANDOM.nextDouble() * Math.PI * 2;
			double speed = 0.25 + RANDOM.nextDouble() * 0.45;
			spawnParticle(client, ParticleTypes.TOTEM_OF_UNDYING, x, y + 0.5, z,
					Math.cos(angle) * speed, RANDOM.nextDouble() * 0.6, Math.sin(angle) * speed);
		}
		playSound(client, x, y, z, effectiveSound("totem_use"), 1.0f, 0.8f);
	}

	private void fireworkSalute(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 6; i++) {
			double offsetY = i * 0.5;
			spawnParticle(client, ParticleTypes.FIREWORK, x, y + offsetY, z, 0, 0.15, 0);
		}
		for (int ring = 0; ring < 2; ring++) {
			ring(client, ParticleTypes.FIREWORK, x, y + 0.5 + ring * 0.8, z, 1.0 + ring * 0.7, 20);
		}
		playSound(client, x, y, z, effectiveSound("firework_large_blast"), 1.0f, 1.0f);
	}

	private void voidPull(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 80; i++) {
			double angle = RANDOM.nextDouble() * Math.PI * 2;
			double radius = 2.2;
			double px = x + Math.cos(angle) * radius;
			double pz = z + Math.sin(angle) * radius;
			spawnParticle(client, ParticleTypes.PORTAL, px, y + RANDOM.nextDouble() * 2, pz,
					(x - px) * 0.09, 0.03, (z - pz) * 0.09);
		}
		spawnParticle(client, ParticleTypes.REVERSE_PORTAL, x, y + 0.5, z, 0, 0.1, 0);
		playSound(client, x, y, z, effectiveSound("enderman_teleport"), 1.0f, 0.7f);
	}

	private void electricSurge(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 5; i++) {
			ring(client, ParticleTypes.ELECTRIC_SPARK, x, y + i * 0.45, z, 0.5 + i * 0.5, 18);
		}
		for (int i = 0; i < 30; i++) {
			spawnParticle(client, ParticleTypes.ELECTRIC_SPARK, x, y + RANDOM.nextDouble() * 2, z,
					(RANDOM.nextDouble() - 0.5) * 0.3, 0, (RANDOM.nextDouble() - 0.5) * 0.3);
		}
		playSound(client, x, y, z, effectiveSound("amethyst_chime"), 1.0f, 1.4f);
	}

	private void confettiRain(MinecraftClient client, double x, double y, double z) {
		int[] colors = {0xFFFF3030, 0xFF30FF30, 0xFF3030FF, 0xFFFFFF30, 0xFFFF30FF, 0xFF30FFFF};
		for (int i = 0; i < 100; i++) {
			double px = x + (RANDOM.nextDouble() - 0.5) * 3;
			double pz = z + (RANDOM.nextDouble() - 0.5) * 3;
			int color = colors[RANDOM.nextInt(colors.length)];
			spawnColoredDust(client, px, y + 1.5 + RANDOM.nextDouble() * 1.5, pz, 0, -0.05, 0, color & 0xFFFFFF, 1.3f);
		}
		playSound(client, x, y, z, effectiveSound("player_levelup"), 0.7f, 1.3f);
	}

	private void ring(MinecraftClient client, Object particle, double x, double y, double z, double radius, int count) {
		for (int i = 0; i < count; i++) {
			double angle = (Math.PI * 2 * i) / count;
			spawnParticle(client, particle, x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius, 0, 0.02, 0);
		}
	}

	private static final Random RANDOM = new Random();

	/**
	 * Source of synthetic IDs for purely client-side entities we spawn
	 * ourselves (the lightning bolt below) - {@code ClientLevel.addEntity}
	 * calls {@code entity.getId()} on its very first line (to discard
	 * whatever previously held that ID), but a freshly-{@code new}'d entity
	 * never gets a real one assigned (that normally only happens via the
	 * server's spawn packet). On 26.2 specifically, {@code Entity.getId()}
	 * now throws {@code IllegalStateException("Tried to access entity ID
	 * before ID assignment")} if the id field is still its default 0 -
	 * confirmed via javap, a real crash reproduced by an actual kill, not a
	 * hypothetical one. 1.21.11/26.1 don't throw, but silently passing 0
	 * through is its own latent bug there (0 is reserved as
	 * {@code Entity.INVALID_ENTITY_ID}, but if it ever coincided with a real
	 * entity's id it would wrongly discard that entity) - so every version
	 * gets an explicit {@code setId(...)} before spawning, using descending
	 * negative numbers that can never collide with a real, server-assigned
	 * id (those only ever start at 1 up).
	 */
	private static final java.util.concurrent.atomic.AtomicInteger NEXT_LOCAL_ENTITY_ID =
			new java.util.concurrent.atomic.AtomicInteger(-1);

	/** Maps the "Kill Sound" choice field to {@link #soundFor}'s version-independent key, or null for "Automatic". */
	private String effectiveSoundKey() {
		return switch (soundOverride) {
			case "Thunder" -> "thunder";
			case "Anvil Land" -> "anvil_land";
			case "Totem" -> "totem_use";
			case "Wither Spawn" -> "wither_spawn";
			case "Ender Dragon Death" -> "dragon_death";
			case "Orb Pickup" -> "orb_pickup";
			case "Firework Blast" -> "firework_large_blast";
			default -> null;
		};
	}

	/**
	 * 26.2 moved the vanilla entity-type constants (LIGHTNING_BOLT etc.) off
	 * of {@code EntityType} itself and onto a separate {@code EntityTypes}
	 * holder class - confirmed via javap against the real 26.1 and 26.2
	 * common jars, {@code EntityType.class} on 26.2 only has 2 static fields
	 * left (CODEC/STREAM_CODEC), none of the actual type constants - so this
	 * needs its own 3-way split distinct from the rest of this block, which
	 * is identical between 26.1 and 26.2.
	 */
	//? if <26.1 {
	private void spawnRealLightning(MinecraftClient client, double x, double y, double z) {
		ClientWorld world = client.world;
		LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
		bolt.setId(NEXT_LOCAL_ENTITY_ID.getAndDecrement());
		bolt.setCosmetic(true);
		bolt.setPos(x, y, z);
		world.addEntity(bolt);
	}
	//?} else if <26.2 {
	/*private void spawnRealLightning(MinecraftClient client, double x, double y, double z) {
		ClientWorld world = client.world;
		LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, world);
		bolt.setId(NEXT_LOCAL_ENTITY_ID.getAndDecrement());
		bolt.setVisualOnly(true);
		bolt.setPos(x, y, z);
		world.addEntity(bolt);
	}
	*///?} else {
	/*private void spawnRealLightning(MinecraftClient client, double x, double y, double z) {
		ClientWorld world = client.world;
		LightningBolt bolt = new LightningBolt(net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT, world);
		bolt.setId(NEXT_LOCAL_ENTITY_ID.getAndDecrement());
		bolt.setVisualOnly(true);
		bolt.setPos(x, y, z);
		world.addEntity(bolt);
	}
	*///?}

	//? if <26.1 {
	private void spawnParticle(MinecraftClient client, Object particle, double x, double y, double z, double vx, double vy, double vz) {
		client.world.addParticleClient((net.minecraft.particle.ParticleEffect) particle, x, y, z, vx, vy, vz);
	}

	private void spawnColoredDust(MinecraftClient client, double x, double y, double z, double vx, double vy, double vz, int color, float scale) {
		client.world.addParticleClient(new DustParticleEffect(color, scale), x, y, z, vx, vy, vz);
	}

	private void playSound(MinecraftClient client, double x, double y, double z, net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
		if (soundEnabled) {
			client.world.playSoundClient(x, y, z, sound, SoundCategory.PLAYERS, volume, pitch, false);
		}
	}

	/**
	 * Vanilla's own sound constant names diverge between mappings by more
	 * than just their package here - mojmap drops the yarn "ENTITY_"/
	 * "BLOCK_"/"ITEM_" category prefix entirely (e.g. {@code
	 * ENTITY_LIGHTNING_BOLT_THUNDER} -> just {@code LIGHTNING_BOLT_THUNDER}),
	 * verified via javap against the real 26.1 and 26.2 jars - so each sound
	 * this module uses is looked up by its own version-independent key
	 * instead of every call site needing its own guard.
	 */
	private static net.minecraft.sound.SoundEvent soundFor(String key) {
		return switch (key) {
			case "thunder" -> SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER;
			case "firework_blast" -> SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST;
			case "firework_large_blast" -> SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST;
			case "warden_roar" -> SoundEvents.ENTITY_WARDEN_ROAR;
			case "totem_use" -> SoundEvents.ITEM_TOTEM_USE;
			case "enderman_teleport" -> SoundEvents.ENTITY_ENDERMAN_TELEPORT;
			case "amethyst_chime" -> SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
			case "player_levelup" -> SoundEvents.ENTITY_PLAYER_LEVELUP;
			case "anvil_land" -> SoundEvents.BLOCK_ANVIL_LAND;
			case "wither_spawn" -> SoundEvents.ENTITY_WITHER_SPAWN;
			case "dragon_death" -> SoundEvents.ENTITY_ENDER_DRAGON_DEATH;
			case "orb_pickup" -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
			default -> throw new IllegalArgumentException(key);
		};
	}

	/** The user's chosen "Kill Sound" override if set, otherwise the given effect-specific default key. */
	private net.minecraft.sound.SoundEvent effectiveSound(String defaultKey) {
		String overrideKey = effectiveSoundKey();
		return soundFor(overrideKey != null ? overrideKey : defaultKey);
	}
	//?} else {
	/*private void spawnParticle(MinecraftClient client, Object particle, double x, double y, double z, double vx, double vy, double vz) {
		client.world.addParticle((net.minecraft.core.particles.ParticleOptions) particle, x, y, z, vx, vy, vz);
	}

	private void spawnColoredDust(MinecraftClient client, double x, double y, double z, double vx, double vy, double vz, int color, float scale) {
		client.world.addParticle(new DustParticleOptions(color, scale), x, y, z, vx, vy, vz);
	}

	private void playSound(MinecraftClient client, double x, double y, double z, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
		if (soundEnabled) {
			client.world.playLocalSound(x, y, z, sound, SoundCategory.PLAYERS, volume, pitch, false);
		}
	}

	private static net.minecraft.sounds.SoundEvent soundFor(String key) {
		return switch (key) {
			case "thunder" -> SoundEvents.LIGHTNING_BOLT_THUNDER;
			case "firework_blast" -> SoundEvents.FIREWORK_ROCKET_BLAST;
			case "firework_large_blast" -> SoundEvents.FIREWORK_ROCKET_LARGE_BLAST;
			case "warden_roar" -> SoundEvents.WARDEN_ROAR;
			case "totem_use" -> SoundEvents.TOTEM_USE;
			case "enderman_teleport" -> SoundEvents.ENDERMAN_TELEPORT;
			case "amethyst_chime" -> SoundEvents.AMETHYST_BLOCK_CHIME;
			case "player_levelup" -> SoundEvents.PLAYER_LEVELUP;
			case "anvil_land" -> SoundEvents.ANVIL_LAND;
			case "wither_spawn" -> SoundEvents.WITHER_SPAWN;
			case "dragon_death" -> SoundEvents.ENDER_DRAGON_DEATH;
			case "orb_pickup" -> SoundEvents.EXPERIENCE_ORB_PICKUP;
			default -> throw new IllegalArgumentException(key);
		};
	}

	private net.minecraft.sounds.SoundEvent effectiveSound(String defaultKey) {
		String overrideKey = effectiveSoundKey();
		return soundFor(overrideKey != null ? overrideKey : defaultKey);
	}
	*///?}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Trigger", TRIGGER_OPTIONS, () -> trigger, v -> trigger = v),
				new ConfigField.ToggleField("Include All Entities (not just players)",
						() -> includeAllEntities, v -> includeAllEntities = v),
				new ConfigField.ChoiceField("Effect", EFFECT_OPTIONS, () -> effect, v -> effect = v),
				new ConfigField.ColorField("Effect Color (Color Burst/Confetti)", () -> effectColor, v -> effectColor = v, false),
				new ConfigField.ToggleField("Play Sound", () -> soundEnabled, v -> soundEnabled = v),
				new ConfigField.ChoiceField("Kill Sound", SOUND_OPTIONS, () -> soundOverride, v -> soundOverride = v));
	}
}
