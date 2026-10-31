package net.veloclient.velo.dev.pets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pets (dev build only, see {@link net.veloclient.velo.dev.VeloDev}): a companion that follows you diagonally behind, does its trick when
 * you stand still, sits on your shoulder and sleeps while you're AFK, celebrates your kills and reacts when you're hit -
 * except in PvP fights by default, where that reaction would only get in the way. Only you see it for now.
 */
public final class PetsModule extends AbstractModule implements Configurable, PetsSettings {

	/** The pets bundled with this build, id -> name. */
	static final Map<String, String> PETS = new LinkedHashMap<>();

	static {
		String[][] all = {{"ember", "Ember Drake"}, {"frost", "Frost Drake"}, {"shadow", "Shadow Drake"}, {"void", "Void Drake"}, {"bone", "Bone Drake"},
				{"ghost", "Boo the Ghost"}, {"owl", "Aurora Owl"}, {"panda", "Momo the Red Panda"}, {"jelly", "Nebula Jelly"}, {"moon", "Luna the Pyjama Moon"},
				{"shark", "Finn the Sky Shark"}, {"reindeer", "Jingle the Reindeer"}, {"drone", "Velo Drone"},
				{"kitsune", "Yuki the Kitsune"}, {"penguin", "Pebble the Penguin"}, {"hedgehog", "Piko the Hedgehog"}, {"toucan", "Toco the Toucan"},
				{"monkey", "Kiki the Capuchin"}, {"tiger", "Raja the Tiger Cub"}, {"mini", "Mini-Me"}};
		for (String[] p : all) {
			PETS.put(p[0], p[1]);
		}
	}

	private static final List<String> HIT_OPTIONS = List.of("Always", "Not In PvP Fights", "Never");
	/** A hit counts as PvP if you hit a player this recently, or another player is this close. */
	private static final int PVP_MEMORY_TICKS = 200;
	private static final double PVP_RADIUS = 6.0;

	private static PetsModule instance;

	/** The pet you picked ("" = none) - kept in config/pets.json, shared with the dev launcher's Locker. */
	private static String pet = readPet();
	private static long petStamp;
	private double size = 1.0;
	private String hitReaction = "Not In PvP Fights";
	private boolean celebrateKills = true;
	private boolean tricks = true;
	private double sleepAfter = 45;
	private boolean hideFirstPerson = true;
	private boolean ride = false;
	/** Riding: how many times its size the pet is drawn, settled each frame so it stands about a horse's height. */
	private float rideScale = 2.5f;
	/** Riding: how far above your feet the saddle is (blocks). */
	private static volatile float seat;

	private PetBrain brain;
	private long lastNanos;
	private int tick;
	private int lastHurt;
	private int lastPlayerHitTick = -100000;
	/** Entities you attacked: id -> the tick of the attack. */
	private final Map<Integer, Integer> attacked = new HashMap<>();

	private static java.nio.file.Path petFile() {
		return net.veloclient.velo.config.VeloPaths.config().resolve("pets.json");
	}

	private static String readPet() {
		try {
			java.nio.file.Path file = petFile();
			if (java.nio.file.Files.exists(file)) {
				petStamp = java.nio.file.Files.getLastModifiedTime(file).toMillis();
				var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
				return json.has("pet") && !json.get("pet").isJsonNull() ? json.get("pet").getAsString() : "";
			}
		} catch (Exception ignored) {
			// none
		}
		return "";
	}

	/** The pet you have ("" for none). */
	static String pet() {
		return pet;
	}

	/** Picks a pet ("" for none): saved for the launcher, and into the look you have on. */
	static void setPet(String id) {
		pet = id == null ? "" : id;
		try {
			com.google.gson.JsonObject json = new com.google.gson.JsonObject();
			json.addProperty("pet", pet.isEmpty() ? null : pet);
			java.nio.file.Files.createDirectories(petFile().getParent());
			java.nio.file.Files.writeString(petFile(), json.toString());
			petStamp = java.nio.file.Files.getLastModifiedTime(petFile()).toMillis();
		} catch (Exception ignored) {
			// only this session has it
		}
		net.veloclient.velo.client.cosmetics.Loadouts.changed();
	}

	public PetsModule() {
		super("pets", "Pets (Dev)",
				"Local test build of Velo Pets: a companion that follows you, does tricks, rides your shoulder and celebrates your kills.",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, true);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
		// looks keep the pet like every other slot
		net.veloclient.velo.client.cosmetics.Loadouts.registerExtra("pet", PetsModule::pet, PetsModule::setPet);
		PvpCompat.onLocalAttack(id -> {
			attacked.put(id, tick);
			if (PvpCompat.isPlayer(PvpCompat.entityById(id))) {
				lastPlayerHitTick = tick;
			}
		});
	}

	@Override
	public boolean tricks() {
		return tricks;
	}

	@Override
	public float sleepAfter() {
		return (float) sleepAfter;
	}

	/** Kill and hit reactions, read from plain client state each tick. */
	private void onTick() {
		tick++;
		if (tick % 20 == 0) {   // picked in the dev launcher's Locker meanwhile?
			try {
				long stamp = java.nio.file.Files.exists(petFile()) ? java.nio.file.Files.getLastModifiedTime(petFile()).toMillis() : 0;
				if (stamp != petStamp) {
					pet = readPet();
				}
			} catch (Exception ignored) {
				// keep the current one
			}
		}
		LivingEntity self = PvpCompat.localPlayer();
		if (!isEnabled() || self == null || brain == null) {
			attacked.clear();
			lastHurt = 0;
			return;
		}
		// a kill: something you attacked in the last five seconds dies while still loaded
		attacked.entrySet().removeIf(e -> {
			if (tick - e.getValue() > 100) {
				return true;
			}
			Entity entity = PvpCompat.entityById(e.getKey());
			if (entity instanceof LivingEntity living && (living.getHealth() <= 0 || !living.isAlive())) {
				if (celebrateKills && !brain.busyWith("kill")) {
					brain.play("kill");
				}
				return true;
			}
			return false;
		});
		// a hit: the hurt animation starting
		int hurt = PvpCompat.hurtTime(self);
		if (hurt > lastHurt && lastHurt == 0 && !brain.busyWith("kill") && reactsToHit()) {
			brain.play("hit");
		}
		lastHurt = hurt;
	}

	private boolean reactsToHit() {
		if ("Never".equals(hitReaction)) {
			return false;
		}
		if ("Always".equals(hitReaction)) {
			return true;
		}
		return !inPvpFight();
	}

	private boolean inPvpFight() {
		if (tick - lastPlayerHitTick < PVP_MEMORY_TICKS) {
			return true;
		}
		LivingEntity self = PvpCompat.localPlayer();
		for (LivingEntity other : PvpCompat.otherPlayers()) {
			double dx = PvpCompat.x(other) - PvpCompat.x(self);
			double dy = PvpCompat.y(other) - PvpCompat.y(self);
			double dz = PvpCompat.z(other) - PvpCompat.z(self);
			if (dx * dx + dy * dy + dz * dz < PVP_RADIUS * PVP_RADIUS) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What the renderer draws this frame: the pet's brain, the player's position, its offset from the camera and the
	 * player's skin texture (an Identifier, for Mini-Me).
	 */
	record Frame(PetBrain brain, double x, double y, double z, float ox, float oy, float oz, Object skin) {
	}

	/** Advances your pet to this frame; null when there's nothing to draw. Render thread. */
	static Frame frame() {
		PetsModule m = instance;
		if (m == null || !m.isEnabled()) {
			return null;
		}
		LivingEntity self = PvpCompat.localPlayer();
		if (self == null || pet.isEmpty()) {
			m.brain = null;
			return null;
		}
		// Mini-Me comes in both arm widths, matching your skin
		String id = "mini".equals(pet) && !slimSkin(self) ? "mini_wide" : pet;
		if (m.brain == null || !m.brain.model.id.equals(id)) {
			PetModel model = PetModel.load(id);
			if (model == null) {
				return null;
			}
			m.brain = new PetBrain(model);
		}
		long now = System.nanoTime();
		float dt = m.lastNanos == 0 ? 0.016f : (now - m.lastNanos) / 1e9f;
		m.lastNanos = now;
		double[] pos = PvpCompat.renderPos(self);
		double ground = PvpCompat.groundBelow(pos[0], pos[1] + 0.5, pos[2], 6);
		boolean riding = m.ride;
		if (riding) {
			// size it to stand ~1.2 blocks tall (measured from what was drawn last frame), the saddle at ~3/4 of that
			float height = Math.max(0.1f, PetRenderer.lastTop - Math.min(0f, PetRenderer.lastBottom));
			m.rideScale = Math.max(0.8f, Math.min(6f, m.rideScale * (float) Math.pow(1.2f / height, 0.25)));
			seat = Math.max(0.3f, PetRenderer.lastBottom + (PetRenderer.lastTop - PetRenderer.lastBottom) * 0.72f);
		}
		m.brain.update(dt, pos[0], pos[1], pos[2], bodyYaw(self), self.getYaw(), (float) m.size, ground, m, riding ? m.rideScale : 0f);
		if (m.hideFirstPerson && firstPerson()) {
			// you'd only see it clip through the camera when you turn - F5 shows it (and keeps the riding size settled)
			return null;
		}
		double[] cam = PvpCompat.cameraPos();
		return new Frame(m.brain, pos[0], pos[1], pos[2], (float) (pos[0] - cam[0]), (float) (pos[1] - cam[1]), (float) (pos[2] - cam[2]),
				id.startsWith("mini") ? skinTexture(self) : null);
	}

	//? if <26.1 {
	private static Object skinTexture(LivingEntity self) {
		return self instanceof net.minecraft.client.network.AbstractClientPlayerEntity p ? p.getSkin().body().texturePath() : null;
	}

	private static boolean slimSkin(LivingEntity self) {
		return self instanceof net.minecraft.client.network.AbstractClientPlayerEntity p && p.getSkin().model() == net.minecraft.entity.player.PlayerSkinType.SLIM;
	}
	//?} else {
	/*private static Object skinTexture(LivingEntity self) {
		return self instanceof net.minecraft.client.player.AbstractClientPlayer p ? p.getSkin().body().texturePath() : null;
	}

	private static boolean slimSkin(LivingEntity self) {
		return self instanceof net.minecraft.client.player.AbstractClientPlayer p && p.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
	}
	*///?}

	/** True while you ride your pet (the player model sits on it, the camera stays where it is). */
	public static boolean riding() {
		PetsModule m = instance;
		return m != null && m.isEnabled() && m.ride && m.brain != null && !pet.isEmpty();
	}

	/** How far the player model is lifted while riding (blocks). */
	public static float seatHeight() {
		return seat;
	}

	private static boolean firstPerson() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().options.getPerspective().isFirstPerson();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().options.getCameraType().isFirstPerson();
		*///?}
	}

	private static float bodyYaw(LivingEntity entity) {
		//? if <26.1 {
		return entity.bodyYaw;
		//?} else {
		/*return entity.yBodyRot;
		*///?}
	}

	@Override
	public List<ConfigField> configFields() {
		// which pet: the Locker's Pets tab (in game and in the dev launcher)
		return List.of(
				new ConfigField.ToggleField("Ride Your Pet", () -> ride, v -> ride = v),
				new ConfigField.ToggleField("Hide It In First Person (F5 shows it)", () -> hideFirstPerson, v -> hideFirstPerson = v),
				new ConfigField.SliderField("Size", 0.5, 1.5, () -> size, v -> size = Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%"),
				new ConfigField.ChoiceField("React When You're Hit", HIT_OPTIONS, () -> hitReaction, v -> hitReaction = v),
				new ConfigField.ToggleField("Celebrate Your Kills", () -> celebrateKills, v -> celebrateKills = v),
				new ConfigField.ToggleField("Tricks, Shoulder Ride & Sleep When Idle", () -> tricks, v -> tricks = v),
				new ConfigField.SliderField("Fall Asleep After (seconds AFK)", 10, 180, () -> sleepAfter, v -> sleepAfter = Math.round(v), v -> Math.round(v) + "s"));
	}
}
