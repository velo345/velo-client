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
				{"shark", "Finn the Sky Shark"}, {"reindeer", "Jingle the Reindeer"},
				{"kitsune", "Yuki the Kitsune"}, {"penguin", "Pebble the Penguin"}, {"hedgehog", "Piko the Hedgehog"}, {"toucan", "Toco the Toucan"},
				{"monkey", "Kiki the Capuchin"}, {"tiger", "Raja the Tiger Cub"}, {"mini", "Mini-Me"}};
		for (String[] p : all) {
			// The reindeer is a Christmas pet: only around in December.
			if (!"reindeer".equals(p[0]) || java.time.LocalDate.now().getMonthValue() == 12) {
				PETS.put(p[0], p[1]);
			}
		}
	}

	private static final List<String> HIT_OPTIONS = List.of("Always", "Not In PvP Fights", "Never");
	/** A hit counts as PvP if you hit a player this recently, or another player is this close. */
	private static final int PVP_MEMORY_TICKS = 200;
	private static final double PVP_RADIUS = 6.0;

	private static PetsModule instance;

	/** The pet you picked ("" = none) - kept per account in config/accounts/<uuid>/pets.json, shared with the launcher's Locker. */
	private static String pet = readPet();
	private static long petStamp;
	private double size = 1.0;
	private String hitReaction = "Not In PvP Fights";
	private boolean celebrateKills = true;
	private boolean tricks = true;
	private double sleepAfter = 45;
	private boolean hideFirstPerson = true;
	/** Other Velo players' pets around you (from the online list). */
	private boolean showOthers = true;
	/** Other players' pets: uuid -> pet id (online list) and uuid -> its brain (only for players drawn this frame). */
	private static volatile Map<String, String> remotePets = Map.of();
	private static final Map<String, PetBrain> REMOTE_BRAINS = new HashMap<>();
	private static final Map<String, Long> REMOTE_LAST = new HashMap<>();
	/** Performance: other players' pets only this close, and at most this many at once (the nearest). */
	private static final double OTHERS_RANGE = 40;
	private static final int OTHERS_MAX = 8;
	private boolean ride = false;
	/** Riding: how many times its size the pet is drawn, settled each frame so it stands about a horse's height. */
	private float rideScale = 2.5f;
	/**
	 * Riding geometry, from the pet's own shape ({@link PetRenderer#rideShape}: measured in its local space, so it doesn't
	 * depend on the size or lift chosen here - no feedback loop): lift puts its lowest point on the ground, the offset
	 * centres it under you, and the seat is the top of its back.
	 */
	private static volatile float rideLift;
	private static volatile float rideOffX;
	private static volatile float rideOffZ;
	private float[] shape;
	/** Riding: how far above your feet the saddle is (blocks). */
	private static volatile float seat;
	/** Vanilla's riding pose puts the hips this far above the player's feet (Player vehicle attachment). */
	private static final float HIP = 0.6f;
	/** How tall a ridden pet stands (blocks). */
	private static final float RIDE_HEIGHT = 1.25f;

	private PetBrain brain;
	private long lastNanos;
	private int tick;
	private int lastHurt;
	private int lastPlayerHitTick = -100000;
	/** Entities you attacked: id -> the tick of the attack. */
	private final Map<Integer, Integer> attacked = new HashMap<>();

	private static java.nio.file.Path petFile() {
		return net.veloclient.velo.config.VeloPaths.accountConfig("pets.json");
	}

	private static String readPet() {
		try {
			java.nio.file.Path file = petFile();
			if (java.nio.file.Files.exists(file)) {
				petStamp = java.nio.file.Files.getLastModifiedTime(file).toMillis();
				var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
				String id = json.has("pet") && !json.get("pet").isJsonNull() ? json.get("pet").getAsString() : "";
				return PETS.containsKey(id) ? id : "";   // a pet that's no longer bundled (the old drone) just goes away
			}
		} catch (Exception ignored) {
			// none
		}
		return "";
	}

	/** Dev-only (screenshot tour): rides {@code id} (null: gets off and puts it away). Run with an isolated home. */
	public static void tourRide(String id, boolean ride) {
		PetsModule m = instance;
		if (m == null) {
			return;
		}
		setPet(id);
		m.setEnabled(id != null);
		m.ride = ride;
		m.hideFirstPerson = false;
		m.shape = null;
		m.brain = null;
	}

	/** Turns the Pets module on (after taking a pet out in the Store). */
	static void enableIfOff() {
		if (instance != null && !instance.isEnabled()) {
			instance.setEnabled(true);
		}
	}

	/** Whether you may have {@code id} out: bought in the Store (the owner has everything). */
	static boolean owns(String id) {
		return net.veloclient.velo.client.network.StoreClient.owns("pet_" + id);
	}

	/** Your own pet's brain this frame (null if none) - the renderer measures riding only on it. */
	static PetBrain ownBrain() {
		PetsModule m = instance;
		return m == null ? null : m.brain;
	}

	/** Everything to draw this frame: your pet, then other Velo players' pets near you. Render thread. */
	static List<Frame> frames() {
		List<Frame> out = new java.util.ArrayList<>();
		Frame own = frame();
		if (own != null) {
			out.add(own);
		}
		PetsModule m = instance;
		if (m != null && m.isEnabled() && m.showOthers && !remotePets.isEmpty()) {
			others(m, out);
		} else if (!REMOTE_BRAINS.isEmpty()) {
			REMOTE_BRAINS.clear();
		}
		return out;
	}

	private static void others(PetsModule m, List<Frame> out) {
		LivingEntity self = PvpCompat.localPlayer();
		if (self == null) {
			return;
		}
		double[] me = PvpCompat.renderPos(self);
		double[] cam = PvpCompat.cameraPos();
		List<Object[]> near = new java.util.ArrayList<>();
		for (LivingEntity other : PvpCompat.otherPlayers()) {
			String id = remotePets.get(uuidOf(other));
			if (id == null) {
				continue;
			}
			double[] pos = PvpCompat.renderPos(other);
			double d = Math.hypot(Math.hypot(pos[0] - me[0], pos[1] - me[1]), pos[2] - me[2]);
			if (d <= OTHERS_RANGE) {
				near.add(new Object[] {other, pos, d, id});
			}
		}
		near.sort(java.util.Comparator.comparingDouble(o -> (double) o[2]));
		long now = System.nanoTime();
		java.util.Set<String> drawn = new java.util.HashSet<>();
		for (int i = 0; i < near.size() && i < OTHERS_MAX; i++) {
			LivingEntity other = (LivingEntity) near.get(i)[0];
			double[] pos = (double[]) near.get(i)[1];
			String id = (String) near.get(i)[3];
			String model = "mini".equals(id) && !slimSkin(other) ? "mini_wide" : id;
			String key = uuidOf(other);
			drawn.add(key);
			PetBrain brain = REMOTE_BRAINS.get(key);
			if (brain == null || !brain.model.id.equals(model)) {
				PetModel loaded = PetModel.load(model);
				if (loaded == null) {
					continue;
				}
				brain = new PetBrain(loaded);
				REMOTE_BRAINS.put(key, brain);
			}
			Long last = REMOTE_LAST.get(key);
			float dt = last == null ? 0.016f : (now - last) / 1e9f;
			REMOTE_LAST.put(key, now);
			double ground = groundFor(brain, pos);
			brain.ride(0, 0, 0);
			brain.update(dt, pos[0], pos[1], pos[2], bodyYaw(other), other.getYaw(), (float) m.size, ground, m, 0f);
			out.add(new Frame(brain, pos[0], pos[1], pos[2], (float) (pos[0] - cam[0]), (float) (pos[1] - cam[1]), (float) (pos[2] - cam[2]),
					id.equals("mini") ? skinTexture(other) : null));
		}
		REMOTE_BRAINS.keySet().retainAll(drawn);
		REMOTE_LAST.keySet().retainAll(drawn);
	}

	/**
	 * The ground under the pet itself (it may stand where you don't), searched from a little above its owner and deep
	 * enough for creative flight. It also looks a little ahead where the pet walks, so it hops up a step before it
	 * reaches it instead of sliding into the block. NaN = nothing below (the brain keeps the ground it had).
	 */
	/** Ground per pet, reused while it hasn't moved much (the search below ran every frame: up to 2 x 48 block lookups). */
	private static final java.util.Map<PetBrain, double[]> GROUND = new java.util.WeakHashMap<>();

	private static double groundFor(PetBrain brain, double[] pos) {
		double[] at0 = brain.position();
		long now = System.nanoTime();
		double px = at0 == null ? pos[0] : at0[0], pz = at0 == null ? pos[2] : at0[2];
		double[] c = GROUND.get(brain);
		if (c != null && now - (long) c[0] < 50_000_000L && Math.abs(px - c[1]) < 0.15 && Math.abs(pz - c[2]) < 0.15 && Math.abs(pos[1] - c[3]) < 0.25) {
			return c[4];
		}
		double ground = searchGround(brain, pos);
		GROUND.put(brain, new double[] {now, px, pz, pos[1], ground});
		return ground;
	}

	private static double searchGround(PetBrain brain, double[] pos) {
		double[] at = brain.position();
		if (at == null) {
			return PvpCompat.groundBelow(pos[0], pos[1] + 0.5, pos[2], 6);
		}
		double from = pos[1] + 1.6;
		double here = PvpCompat.groundBelow(at[0], from, at[2], 48);
		double[] v = brain.groundVelocity();
		double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1]);
		if (speed < 0.3) {
			return here;
		}
		double reach = 0.35 + speed * 0.12;
		double ahead = PvpCompat.groundBelow(at[0] + v[0] / speed * reach, from, at[2] + v[1] / speed * reach, 48);
		// only steps up count ahead (a single block, like you walk) - ledges are taken when it's actually over them
		return !Double.isNaN(ahead) && (Double.isNaN(here) || ahead > here && ahead - here <= 1.1) ? ahead : here;
	}

	//? if <26.1 {
	private static String uuidOf(Entity entity) {
		return entity.getUuidAsString().replace("-", "");
	}
	//?} else {
	/*private static String uuidOf(Entity entity) {
		return entity.getStringUUID().replace("-", "");
	}
	*///?}

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
		super("pets", "Pets",
				"A companion that follows you, does tricks, rides your shoulder and celebrates your kills. Get pets in the Store.",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, true);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
		// looks keep the pet like every other slot
		net.veloclient.velo.client.cosmetics.Loadouts.registerExtra("pet", PetsModule::pet, PetsModule::setPet);
		// Your pet travels with the heartbeat (the server only passes it on if you own it), others' come with the online list.
		net.veloclient.velo.client.network.VeloServerClient.HEARTBEAT_EXTRAS.add(body -> {
			if (isEnabled() && !pet.isEmpty() && owns(pet)) {
				body.addProperty("pet", pet);
			}
		});
		net.veloclient.velo.client.network.VeloServerClient.ONLINE_LISTENERS.add(users -> {
			Map<String, String> next = new HashMap<>();
			for (var element : users) {
				var user = element.getAsJsonObject();
				if (user.has("pet") && !user.get("pet").isJsonNull() && user.has("uuid") && PETS.containsKey(user.get("pet").getAsString())) {
					next.put(user.get("uuid").getAsString().replace("-", "").toLowerCase(java.util.Locale.ROOT), user.get("pet").getAsString());
				}
			}
			remotePets = next;
		});
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
		if (self == null || pet.isEmpty() || !owns(pet)) {
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
		// Hidden in first person (not riding): nobody sees the pose, it only has to keep following - 60 Hz is plenty
		// (it used to run its whole animation every frame, ~2% of the render thread at high FPS).
		if (net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo && m.hideFirstPerson && !m.ride && firstPerson() && m.lastNanos != 0 && now - m.lastNanos < 16_000_000L) {
			return null;
		}
		float dt = m.lastNanos == 0 ? 0.016f : (now - m.lastNanos) / 1e9f;
		m.lastNanos = now;
		double[] pos = PvpCompat.renderPos(self);
		double ground = groundFor(m.brain, pos);
		boolean riding = m.ride;
		if (riding) {
			float[] measured = PetRenderer.rideShape;   // {minY, maxY, centreX, centreZ, backTop} at ride size 1
			if (measured != null) {
				if (m.shape == null) {
					m.shape = measured.clone();
				} else {
					// the shape breathes with the idle/walk loop - follow it slowly so nothing jitters
					float k = 1f - (float) Math.exp(-dt * 2.5f);
					for (int i = 0; i < 5; i++) {
						m.shape[i] += (measured[i] - m.shape[i]) * k;
					}
				}
			}
			if (m.shape != null) {
				float h = Math.max(0.05f, m.shape[1] - m.shape[0]);
				m.rideScale = Math.max(0.6f, Math.min(6f, RIDE_HEIGHT / h));
				rideLift = -m.shape[0] * m.rideScale;
				rideOffX = -m.shape[2] * m.rideScale;
				rideOffZ = -m.shape[3] * m.rideScale;
				seat = rideLift + m.shape[4] * m.rideScale;
			}
		} else {
			m.shape = null;
			PetRenderer.rideShape = null;
		}
		m.brain.ride(rideOffX, rideLift, rideOffZ);
		m.brain.update(dt, pos[0], pos[1], pos[2], bodyYaw(self), self.getYaw(), (float) m.size, ground, m, riding ? m.rideScale : 0f);
		double[] cam = PvpCompat.cameraPos();
		if (m.hideFirstPerson && firstPerson()) {
			// you'd only see it clip through the camera when you turn - F5 shows it. Riding still needs its shape once.
			if (riding && m.shape == null) {
				PetRenderer.build(m.brain, (float) (pos[0] - cam[0]), (float) (pos[1] - cam[1]), (float) (pos[2] - cam[2]));
			}
			return null;
		}
		return new Frame(m.brain, pos[0], pos[1], pos[2], (float) (pos[0] - cam[0]), (float) (pos[1] - cam[1]), (float) (pos[2] - cam[2]),
				id.startsWith("mini") ? skinTexture(self) : null);
	}

	//? if <26.1 {
	private static Object skinTexture(LivingEntity self) {
		//? if <1.21.2 {
		/*return self instanceof net.minecraft.client.network.AbstractClientPlayerEntity p ? p.getSkinTextures().texture() : null;
		*///?} else {
		return self instanceof net.minecraft.client.network.AbstractClientPlayerEntity p ? p.getSkin().body().texturePath() : null;
		//?}
	}

	private static boolean slimSkin(LivingEntity self) {
		//? if <1.21.2 {
		/*return self instanceof net.minecraft.client.network.AbstractClientPlayerEntity p && p.getSkinTextures().model() == net.minecraft.client.util.SkinTextures.Model.SLIM;
		*///?} else {
		return self instanceof net.minecraft.client.network.AbstractClientPlayerEntity p && p.getSkin().model() == net.minecraft.entity.player.PlayerSkinType.SLIM;
		//?}
	}
	//?} else {
	/*private static Object skinTexture(LivingEntity self) {
		return self instanceof net.minecraft.client.player.AbstractClientPlayer p ? p.getSkin().body().texturePath() : null;
	}

	private static boolean slimSkin(LivingEntity self) {
		return self instanceof net.minecraft.client.player.AbstractClientPlayer p && p.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
	}
	*///?}

	/** True while you ride your pet (the player model and the camera sit on it; the real player stays on the ground). */
	public static boolean riding() {
		PetsModule m = instance;
		return m != null && m.isEnabled() && m.ride && m.brain != null && m.shape != null && !pet.isEmpty();
	}

	/** How far the player model and the camera are lifted while riding: hips on the pet's back (blocks). */
	public static float seatHeight() {
		return Math.max(0f, seat - HIP);
	}

	/** Riding is switched on but the pet's shape isn't measured yet (the renderer measures it on the next frame). */
	static boolean wantsRide() {
		PetsModule m = instance;
		return m != null && m.isEnabled() && m.ride && m.brain != null && m.shape == null && !pet.isEmpty();
	}

	/** The ride offset the renderer measures against: {liftX, lift, liftZ, size}. */
	static float[] rideFrame() {
		PetsModule m = instance;
		return new float[] {rideOffX, rideLift, rideOffZ, m == null ? 1f : m.rideScale};
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
				new ConfigField.ToggleField("Ride Your Pet (Beta)", () -> ride, v -> ride = v),
				new ConfigField.ToggleField("Hide It In First Person (F5 shows it)", () -> hideFirstPerson, v -> hideFirstPerson = v),
				new ConfigField.ToggleField("Show Other Players' Pets", () -> showOthers, v -> showOthers = v),
				new ConfigField.SliderField("Size", 0.5, 1.5, () -> size, v -> size = Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%"),
				new ConfigField.ChoiceField("React When You're Hit", HIT_OPTIONS, () -> hitReaction, v -> hitReaction = v),
				new ConfigField.ToggleField("Celebrate Your Kills", () -> celebrateKills, v -> celebrateKills = v),
				new ConfigField.ToggleField("Tricks, Shoulder Ride & Sleep When Idle", () -> tricks, v -> tricks = v),
				new ConfigField.SliderField("Fall Asleep After (seconds AFK)", 10, 180, () -> sleepAfter, v -> sleepAfter = Math.round(v), v -> Math.round(v) + "s"));
	}
}
