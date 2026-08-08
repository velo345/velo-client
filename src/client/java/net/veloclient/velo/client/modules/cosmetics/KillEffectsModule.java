package net.veloclient.velo.client.modules.cosmetics;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
//? if <26.1 {
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.ActionResult;
//?} else {
/*import net.minecraft.core.particles.DustParticleOptions;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plays a purely client-side, purely visual effect (particles + optional
 * sound) at a player's position when they die, for however nearby a death
 * counts as {@link #trigger}.
 *
 * <p>Deliberately NOT detected from vanilla's own death chat message - that
 * was this module's first approach and it was unreliable in exactly the way
 * it sounds: message wording is locale/plugin-dependent, and a real game log
 * showed the message can even arrive before the victim's own health-sync
 * packet is applied, racing the very check meant to confirm it. Instead,
 * every tracked player's health is watched directly each tick (see {@link
 * #onTick}) - a &gt;0 -&gt; &lt;=0 transition (or the entity going
 * not-{@code isAlive()}) on the client's own authoritative entity state IS
 * the death, no parsing, no locale, no race. "Killed by a player" is
 * likewise derived from directly-observable client state instead of text:
 * {@link AttackEntityCallback} records every entity the local player
 * actually attacked and when, and a death within {@link
 * #ATTACK_ATTRIBUTION_WINDOW_TICKS} of that counts as "by me"; a death with
 * another tracked player within {@link #NEARBY_PLAYER_RADIUS} counts as "by
 * a player" more broadly. Both are heuristics too (this client still can't
 * see a ranged/projectile kill, or another player's own attacks), but
 * neither depends on the exact wording of a message a server might not even
 * send the way vanilla does.
 */
public final class KillEffectsModule extends AbstractModule implements Configurable {

	private static final List<String> TRIGGER_OPTIONS =
			List.of("Any Nearby Death", "Player Killed By Player", "Only My Kills");
	private static final List<String> EFFECT_OPTIONS = List.of(
			"Lightning Strike", "Color Burst", "Soul Implosion", "Totem Shatter",
			"Firework Salute", "Void Pull", "Electric Surge", "Confetti Rain");
	private static final int ATTACK_ATTRIBUTION_WINDOW_TICKS = 100;
	private static final double NEARBY_PLAYER_RADIUS = 6.0;

	private final Map<UUID, Float> lastHealth = new HashMap<>();
	private final Map<UUID, Integer> lastAttackedByMeTick = new HashMap<>();
	private int tickCounter;

	private String trigger = "Only My Kills";
	private String effect = "Lightning Strike";
	private int effectColor = 0xFFFF3030;
	private boolean soundEnabled = true;

	public KillEffectsModule() {
		super("kill-effects", "Kill Effects",
				"Plays a purely client-side visual effect (with optional sound) when a nearby player dies - "
						+ "pick when it triggers and which animation plays.",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		AttackEntityCallback.EVENT.register(this::onAttackEntity);
	}

	//? if <26.1 {
	private ActionResult onAttackEntity(PlayerEntity player, net.minecraft.world.World world,
			net.minecraft.util.Hand hand, net.minecraft.entity.Entity entity, net.minecraft.util.hit.EntityHitResult hitResult) {
		if (entity instanceof PlayerEntity target) {
			lastAttackedByMeTick.put(target.getUuid(), tickCounter);
		}
		return ActionResult.PASS;
	}
	//?} else {
	/*private InteractionResult onAttackEntity(PlayerEntity player, net.minecraft.world.level.Level world,
			net.minecraft.world.InteractionHand hand, net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.EntityHitResult hitResult) {
		if (entity instanceof PlayerEntity target) {
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
		java.util.Set<UUID> currentIds = new java.util.HashSet<>();
		for (PlayerEntity player : players) {
			UUID id = player.getUuid();
			currentIds.add(id);
			float health = player.getHealth();
			Float previous = lastHealth.put(id, health);
			if (previous != null && previous > 0f && (health <= 0f || !player.isAlive())) {
				onPlayerDied(client, player, players);
			}
		}
		lastHealth.keySet().retainAll(currentIds);
		lastAttackedByMeTick.keySet().retainAll(currentIds);
	}

	private void onPlayerDied(MinecraftClient client, PlayerEntity victim, List<PlayerEntity> allPlayers) {
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

	private static boolean isAnotherPlayerNearby(PlayerEntity victim, List<PlayerEntity> allPlayers) {
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

	private static float entityHeight(PlayerEntity entity) {
		return entity.getHeight();
	}
	//?} else {
	/*private static List<PlayerEntity> trackedPlayers(MinecraftClient client) {
		return List.copyOf(client.world.players());
	}

	private static float entityHeight(PlayerEntity entity) {
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
		for (int i = 0; i < 40; i++) {
			double offsetY = i * 0.4;
			spawnParticle(client, ParticleTypes.ELECTRIC_SPARK, x, y + offsetY, z, 0, 0, 0);
		}
		ring(client, ParticleTypes.CRIT, x, y, z, 1.2, 24);
		playSound(client, x, y, z, soundFor("thunder"), 1.0f, 1.0f);
		if (soundEnabled) {
			playSound(client, x, y, z, soundFor("lightning_impact"), 1.0f, 1.0f);
		}
	}

	private void colorBurst(MinecraftClient client, double x, double y, double z) {
		int color = effectColor & 0xFFFFFF;
		for (int i = 0; i < 60; i++) {
			double angle = RANDOM.nextDouble() * Math.PI * 2;
			double speed = RANDOM.nextDouble() * 0.4;
			spawnColoredDust(client, x, y + 1, z, Math.cos(angle) * speed, RANDOM.nextDouble() * 0.5, Math.sin(angle) * speed, color, 1.5f);
		}
		playSound(client, x, y, z, soundFor("firework_blast"), 1.0f, 1.2f);
	}

	private void soulImplosion(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 50; i++) {
			double angle = RANDOM.nextDouble() * Math.PI * 2;
			double radius = 1.5 + RANDOM.nextDouble();
			double px = x + Math.cos(angle) * radius;
			double pz = z + Math.sin(angle) * radius;
			spawnParticle(client, ParticleTypes.SOUL, px, y, pz, (x - px) * 0.05, 0.05, (z - pz) * 0.05);
		}
		spawnParticle(client, ParticleTypes.EXPLOSION, x, y, z, 0, 0, 0);
		playSound(client, x, y, z, soundFor("warden_roar"), 0.6f, 1.6f);
	}

	private void totemShatter(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 45; i++) {
			double angle = RANDOM.nextDouble() * Math.PI * 2;
			double speed = 0.2 + RANDOM.nextDouble() * 0.3;
			spawnParticle(client, ParticleTypes.TOTEM_OF_UNDYING, x, y + 0.5, z,
					Math.cos(angle) * speed, RANDOM.nextDouble() * 0.4, Math.sin(angle) * speed);
		}
		playSound(client, x, y, z, soundFor("totem_use"), 1.0f, 0.8f);
	}

	private void fireworkSalute(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 3; i++) {
			double offsetY = i * 0.6;
			spawnParticle(client, ParticleTypes.FIREWORK, x, y + offsetY, z, 0, 0.1, 0);
		}
		ring(client, ParticleTypes.FIREWORK, x, y + 1, z, 1.0, 16);
		playSound(client, x, y, z, soundFor("firework_large_blast"), 1.0f, 1.0f);
	}

	private void voidPull(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 40; i++) {
			double angle = RANDOM.nextDouble() * Math.PI * 2;
			double radius = 2.0;
			double px = x + Math.cos(angle) * radius;
			double pz = z + Math.sin(angle) * radius;
			spawnParticle(client, ParticleTypes.PORTAL, px, y + RANDOM.nextDouble(), pz,
					(x - px) * 0.08, 0.02, (z - pz) * 0.08);
		}
		playSound(client, x, y, z, soundFor("enderman_teleport"), 1.0f, 0.7f);
	}

	private void electricSurge(MinecraftClient client, double x, double y, double z) {
		for (int i = 0; i < 3; i++) {
			ring(client, ParticleTypes.ELECTRIC_SPARK, x, y + i * 0.5, z, 0.6 + i * 0.4, 14);
		}
		playSound(client, x, y, z, soundFor("amethyst_chime"), 1.0f, 1.4f);
	}

	private void confettiRain(MinecraftClient client, double x, double y, double z) {
		int[] colors = {0xFFFF3030, 0xFF30FF30, 0xFF3030FF, 0xFFFFFF30, 0xFFFF30FF, 0xFF30FFFF};
		for (int i = 0; i < 50; i++) {
			double px = x + (RANDOM.nextDouble() - 0.5) * 2;
			double pz = z + (RANDOM.nextDouble() - 0.5) * 2;
			int color = colors[RANDOM.nextInt(colors.length)];
			spawnColoredDust(client, px, y + 1.5 + RANDOM.nextDouble(), pz, 0, -0.05, 0, color & 0xFFFFFF, 1.2f);
		}
		playSound(client, x, y, z, soundFor("player_levelup"), 0.7f, 1.3f);
	}

	private void ring(MinecraftClient client, Object particle, double x, double y, double z, double radius, int count) {
		for (int i = 0; i < count; i++) {
			double angle = (Math.PI * 2 * i) / count;
			spawnParticle(client, particle, x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius, 0, 0.02, 0);
		}
	}

	private static final java.util.Random RANDOM = new java.util.Random();

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
			case "lightning_impact" -> SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT;
			case "firework_blast" -> SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST;
			case "firework_large_blast" -> SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST;
			case "warden_roar" -> SoundEvents.ENTITY_WARDEN_ROAR;
			case "totem_use" -> SoundEvents.ITEM_TOTEM_USE;
			case "enderman_teleport" -> SoundEvents.ENTITY_ENDERMAN_TELEPORT;
			case "amethyst_chime" -> SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
			case "player_levelup" -> SoundEvents.ENTITY_PLAYER_LEVELUP;
			default -> throw new IllegalArgumentException(key);
		};
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
			case "lightning_impact" -> SoundEvents.LIGHTNING_BOLT_IMPACT;
			case "firework_blast" -> SoundEvents.FIREWORK_ROCKET_BLAST;
			case "firework_large_blast" -> SoundEvents.FIREWORK_ROCKET_LARGE_BLAST;
			case "warden_roar" -> SoundEvents.WARDEN_ROAR;
			case "totem_use" -> SoundEvents.TOTEM_USE;
			case "enderman_teleport" -> SoundEvents.ENDERMAN_TELEPORT;
			case "amethyst_chime" -> SoundEvents.AMETHYST_BLOCK_CHIME;
			case "player_levelup" -> SoundEvents.PLAYER_LEVELUP;
			default -> throw new IllegalArgumentException(key);
		};
	}
	*///?}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Trigger", TRIGGER_OPTIONS, () -> trigger, v -> trigger = v),
				new ConfigField.ChoiceField("Effect", EFFECT_OPTIONS, () -> effect, v -> effect = v),
				new ConfigField.ColorField("Effect Color (Color Burst/Confetti)", () -> effectColor, v -> effectColor = v, false),
				new ConfigField.ToggleField("Play Sound", () -> soundEnabled, v -> soundEnabled = v));
	}
}
