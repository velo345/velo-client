package net.veloclient.launcher.sandbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import net.veloclient.launcher.sandbox.Actors.Mob;
import net.veloclient.launcher.sandbox.Actors.Proj;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Velo World: the side-scroller under the Home card. Your own skin, biomes, ores in layers, crafting up to Aetherite,
 * three dungeons with puzzles, traps and bosses, the map trader, and party co-op hosted by the party leader
 * (with friendly fire the host can switch on).
 */
final class WorldGame implements MiniGame {

	private static final double GRAVITY = 40;
	private static final double HALF_W = 0.3;
	private static final double TALL = 1.85;
	private static final double REACH = 4.6;
	static final int SLOTS = 36;

	private final Arcade arcade;
	private final SplittableRandom random = new SplittableRandom();
	World world;
	final Dungeon[] dungeons = new Dungeon[3];
	Level level;
	private final Map<String, Sim> sims = new HashMap<>();
	/** Flowing water and lava, per level (see {@link Liquids}). */
	private final Map<String, Liquids> liquids = new HashMap<>();

	private Liquids liquids(Level l) {
		return liquids.computeIfAbsent(l.id(), k -> new Liquids());
	}

	// ---- the player ----
	double px;
	double py;
	private double vx;
	private double vy;
	private boolean onGround;
	private int facing = 1;
	private double walkPhase;
	private double walkAmount;
	private double coyote;
	private double jumpBuffer;
	private boolean jumpHeld;
	private boolean leftKey;
	private boolean rightKey;
	private boolean downKey;
	private int airJumps;
	private double stepTimer;
	int health = 10;
	int maxHealth = 10;
	int armor;
	private double invulnerable;
	private double sinceHurt = 10;
	private double regen;
	private double deadTimer;
	private double swing = 1;
	private double turn = 1;
	private double front = 1;
	private double frontTarget;
	private double speedBuff;
	private double strengthBuff;
	private double hazardTimer;
	private double returnX;
	private double returnY;

	// ---- camera / view ----
	private double camX;
	private double camY;
	private boolean camReady;
	private double scale = 2;
	private double block = 32;
	private double viewW = 800;
	private double viewH = 400;

	// ---- input ----
	private double mouseX;
	private double mouseY;
	private boolean mouseLeft;
	private boolean mouseRight;
	private int mineX = Integer.MIN_VALUE;
	private int mineY;
	private double mineProgress;
	private double useCooldown;
	private double pushCooldown;
	private double crateClock;
	private double attackCooldown;

	// ---- inventory ----
	final int[] ids = new int[SLOTS];
	final int[] counts = new int[SLOTS];
	int selected;
	private double selectedShown;

	// ---- effects / HUD ----
	private record Floater(String text, int icon, double[] state) {
	}

	private final List<Floater> floaters = new ArrayList<>();
	private String announcement;
	private double announceTimer;
	private double t;
	private double saveTimer;
	boolean hintSeen;
	private double hintTimer;
	private javafx.scene.image.WritableImage shadeImage;
	private int[] shadePixels;
	Runnable onInventoryChange = () -> {
	};

	// ---- party co-op ----
	private Coop coop;
	private boolean coopConnecting;
	private double coopRetry;
	private double netTimer;
	private boolean joined;
	private World ownWorld;
	private Dungeon[] ownDungeons;
	private String coopStatus = "";
	private final Map<String, Remote> remotes = new HashMap<>();
	/** Set by the host: party members can hurt each other. */
	boolean friendlyFire;

	/** An item lying in the world (dropped with Q, out of the inventory, or by a monster). */
	static final class Pickup {
		long pid;
		String level;
		int id;
		int count;
		double x;
		double y;
		double vx;
		double vy;
		double age;
		double wait;
	}

	private final List<Pickup> pickups = new ArrayList<>();

	/** Atmosphere: fireflies, leaves, ash, snow, cave dust, embers, dungeon motes. */
	private static final class Mote {
		int kind;
		double x;
		double y;
		double vx;
		double vy;
		double life;
		double max;
		double phase;
	}

	private static final int FIREFLY = 0, LEAF = 1, DUST = 2, EMBER = 3, SNOW = 4, CAVE_DUST = 5, RED_MOTE = 6, SPARK = 7, VOID_MOTE = 8;
	private final List<Mote> motes = new ArrayList<>();
	private double moteClock;
	private Color skyTint;
	private final Map<Integer, Image> fogImages = new HashMap<>();
	private final Map<Integer, LinearGradient> aoCache = new HashMap<>();

	/** Another party member in the same game. */
	static final class Remote {
		String name = "";
		String level = "world";
		double x;
		double y;
		double nx;
		double ny;
		int facing = 1;
		double walk;
		double swing = 1;
		int held;
		int armor;
		boolean dead;
		double front;
		double hurt;
		SkinRig rig;
		boolean skinAsked;
	}

	WorldGame(Arcade arcade) {
		this.arcade = arcade;
		newWorld(System.nanoTime());
	}

	void newWorld(long seed) {
		world = new World(seed);
		java.util.Arrays.fill(dungeons, null);
		sims.clear();
		liquids.clear();
		level = world;
		java.util.Arrays.fill(ids, 0);
		java.util.Arrays.fill(counts, 0);
		armor = 0;
		maxHealth = 10;
		health = 10;
		returnX = 0;
		returnY = 0;
		respawn();
		camReady = false;
		onInventoryChange.run();
	}

	Dungeon dungeon(int i) {
		if (dungeons[i] == null) {
			dungeons[i] = new Dungeon(world.seed, i);
		}
		return dungeons[i];
	}

	Level levelById(String id) {
		if (id != null && id.startsWith("dungeon")) {
			return dungeon(Integer.parseInt(id.substring(7)));
		}
		return world;
	}

	private Sim sim(Level l) {
		return sims.computeIfAbsent(l.id(), k -> {
			Sim sim = new Sim(l, world, simHost);
			if (authoritative()) {
				sim.populate();
			}
			return sim;
		});
	}

	private boolean authoritative() {
		return coop == null || coop.isHost() || !joined;
	}

	private void respawn() {
		if (level instanceof Dungeon d) {
			px = d.startX + 0.5;
			py = d.startY - 1e-3;
		} else {
			px = 0.5;
			py = world.surface(0);
			while (py > 1 && (blocked(px, py) || blocked(px, py - 1))) {
				py -= 1;
			}
		}
		vx = 0;
		vy = 0;
		health = maxHealth;
	}

	@Override
	public String id() {
		return "world";
	}

	@Override
	public String music() {
		Sim sim = sims.get(level.id());
		if (level instanceof Dungeon d) {
			if (sim != null && sim.boss != null && d.inArena(px, py)) {
				return "boss" + d.index;
			}
			return d.theme == Dungeon.Theme.CRYPT ? "crypt" : d.theme == Dungeon.Theme.FOUNDRY ? "foundry" : "spire";
		}
		if (underground()) {
			return "cave";
		}
		// the biome you're mostly in (with a little hysteresis so walking along a border doesn't flip the music)
		double[] wgt = world.biomeWeights(px);
		String now = wgt[1] > 0.6 ? "ash" : wgt[2] > 0.6 ? "frost" : wgt[0] > 0.6 ? "forest" : biomeTrack;
		biomeTrack = now;
		return now;
	}

	private String biomeTrack = "forest";

	@Override
	public boolean keepsRunning() {
		return coop != null && coop.alive() && (coop.isHost() ? coop.peerCount() > 0 : joined);
	}

	/** In a party game (hosting with friends in, or joined to one). */
	boolean inParty() {
		return coop != null && coop.alive() && (!coop.isHost() || coop.peerCount() > 0);
	}

		boolean canSetFriendlyFire() {
		return coop == null || coop.isHost();
	}

	void setFriendlyFire(boolean on) {
		if (!canSetFriendlyFire()) {
			return;
		}
		friendlyFire = on;
		say("Friendly fire " + (on ? "on" : "off"));
		if (coop != null) {
			JsonObject m = new JsonObject();
			m.addProperty("t", "rules");
			m.addProperty("ff", on);
			coop.send(m);
		}
		arcade.saveSoon();
	}

	// ------------------------------------------------------------------ physics helpers

	private boolean solidAt(int x, int y) {
		return Items.solid(level.get(x, y));
	}

	private boolean blocked(double x, double feet) {
		return boxHits(x - HALF_W, feet - TALL, x + HALF_W, feet);
	}

	private boolean boxHits(double x0, double y0, double x1, double y1) {
		for (int x = (int) Math.floor(x0); x <= (int) Math.floor(x1 - 1e-6); x++) {
			for (int y = (int) Math.floor(y0); y <= (int) Math.floor(y1 - 1e-6); y++) {
				if (solidAt(x, y)) {
					return true;
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ update

	@Override
	public void update(double dt) {
		t += dt;
		hintTimer += dt;
		announceTimer -= dt;
		coopTick(dt);
		updatePlayer(dt);
		if (coop == null || !coop.alive() || coop.isHost()) {
			// liquids flow on the host (or alone); guests get the blocks as they change
			liquids(level).update(level, dt, (int) Math.floor(px) - 40, (int) Math.floor(px) + 40, (int) Math.floor(py) - 25,
					(int) Math.floor(py) + 25, this::setTile);
		}
		updateSims(dt);
		hazards(dt);
		updatePickups(dt);
		updateAtmosphere(dt);
		selectedShown -= dt;
		for (Iterator<Floater> it = floaters.iterator(); it.hasNext(); ) {
			Floater f = it.next();
			f.state()[2] += dt;
			if (f.state()[2] > 1.4) {
				it.remove();
			}
		}
		speech.values().removeIf(sp -> (sp[1] -= dt) <= 0);
		for (Remote r : remotes.values()) {
			double k = 1 - Math.exp(-dt * 12);
			r.x += (r.nx - r.x) * k;
			r.y += (r.ny - r.y) * k;
			r.swing = Math.min(1, r.swing + dt / 0.26);
			r.hurt -= dt;
		}
		double tx = arcade.playing() ? px + facing * 1.2 + vx * 0.15 : px - (viewW * 0.235) / Math.max(1, block);
		double ty = py - 1.6;
		if (!camReady) {
			camX = tx;
			camY = ty;
			camReady = true;
		}
		camX += (tx - camX) * (1 - Math.exp(-dt * 5));
		camY += (ty - camY) * (1 - Math.exp(-dt * 4));
		clampCamera();
		world.trim(level == world ? camX : returnX);
		saveTimer += dt;
		if (saveTimer > 45) {
			saveTimer = 0;
			arcade.saveSoon();
		}
	}

	private void clampCamera() {
		if (level instanceof Dungeon d) {
			double halfW = viewW / Math.max(1, block) / 2;
			double halfH = viewH / Math.max(1, block) / 2;
			camX = d.width > halfW * 2 ? Math.max(halfW, Math.min(d.width - halfW, camX)) : d.width / 2.0;
			camY = d.heightTiles > halfH * 2 ? Math.max(halfH, Math.min(d.heightTiles - halfH, camY)) : d.heightTiles / 2.0;
		}
	}

	private void updatePlayer(double dt) {
		speedBuff -= dt;
		strengthBuff -= dt;
		useCooldown -= dt;
		pushCooldown -= dt;
		crateClock -= dt;
		if (crateClock <= 0 && level instanceof Dungeon) {
			crateClock = 0.12;
			fallCrates();
		}
		attackCooldown -= dt;
		invulnerable -= dt;
		sinceHurt += dt;
		regen += dt;
		if (deadTimer > 0) {
			deadTimer -= dt;
			if (deadTimer <= 0) {
				respawn();
				camReady = false;
			}
			return;
		}
		if (sinceHurt > 4 && regen > 3 && health < maxHealth) {
			regen = 0;
			health++;
		}
		int dir = (rightKey ? 1 : 0) - (leftKey ? 1 : 0);
		if (dir != 0) {
			facingTo(dir);
			frontTarget = 0;
		}
		front += (frontTarget - front) * Math.min(1, dt * 14);
		turn = Math.min(1, turn + dt * 7);
		double target = dir * 5.4 * (speedBuff > 0 ? 1.4 : 1);
		boolean ice = onGround && level.get((int) Math.floor(px), (int) Math.floor(py + 0.05)) == Items.ICE;
		double accel = onGround ? (ice ? 12 : 46) : 24;
		if (vx < target) {
			vx = Math.min(target, vx + accel * dt);
		} else if (vx > target) {
			vx = Math.max(target, vx - accel * dt);
		}
		coyote = onGround ? 0.09 : coyote - dt;
		if (onGround) {
			airJumps = count(Items.FEATHER) > 0 ? 1 : 0;
		}
		jumpBuffer -= dt;
		if (jumpBuffer > 0 && (coyote > 0 || airJumps > 0)) {
			if (coyote <= 0) {
				airJumps--;
				arcade.burst(screenX(px), screenY(py), Color.rgb(240, 240, 255, 0.7), 8, 0);
			}
			vy = -12.9;
			coyote = 0;
			jumpBuffer = 0;
			onGround = false;
			arcade.audio.play("jump", 0.8f, 0.95 + random.nextDouble() * 0.1);
		}
		int bodyAt = level.get((int) Math.floor(px), (int) Math.floor(py - 0.5));
		if (bodyAt == Items.WATER || bodyAt == Items.LAVA) {
			// in liquid: slow sinking, hold jump to swim up, everything drags
			vy = Math.min(bodyAt == Items.LAVA ? 2.5 : 4, vy + GRAVITY * 0.25 * dt);
			if (jumpHeld) {
				vy = Math.max(-6, vy - 30 * dt);
			}
			vx *= Math.pow(bodyAt == Items.LAVA ? 0.2 : 0.5, dt);
		} else {
		if (!jumpHeld && vy < -4) {
			vy += GRAVITY * 1.4 * dt;
		}
		vy = Math.min(28, vy + GRAVITY * dt);
		}
		moveX(vx * dt);
		boolean wasGround = onGround;
		double fall = vy;
		moveY(vy * dt);
		if (onGround && !wasGround && fall > 9) {
			arcade.audio.play("land", 0.7f, 1);
			arcade.burst(screenX(px), screenY(py), Color.rgb(200, 170, 150, 0.6), 5, 300);
			if (fall > 25) {
				hurt((int) ((fall - 23) / 1.5), px);
			}
		}
		if (onGround && Math.abs(vx) > 0.5) {
			walkPhase += Math.abs(vx) * dt * 2.3;
			walkAmount = Math.min(1, walkAmount + dt * 6);
			stepTimer -= dt;
			if (stepTimer <= 0) {
				stepTimer = 0.3;
				arcade.audio.play("step", 0.6f, 0.9 + random.nextDouble() * 0.2);
			}
		} else {
			walkAmount = Math.max(0, walkAmount - dt * 6);
		}
		if (py > level.height() + 4) {
			hurt(4, px);
			respawn();
		}
		swing = Math.min(1, swing + dt / 0.26);
		handleMouse(dt);
	}

	private void moveX(double d) {
		double nx = px + d;
		if (!blocked(nx, py)) {
			px = nx;
			return;
		}
		int dir = d > 0 ? 1 : -1;
		int cx = (int) Math.floor(nx + dir * HALF_W);
		int cy = (int) Math.floor(py - 0.5);
		if (level.get(cx, cy) == Items.CRATE && level.get(cx + dir, cy) == Items.AIR && pushCooldown <= 0) {
			// push the crate one block along
			pushCooldown = 0.18;
			setTile(level, cx, cy, Items.AIR);
			setTile(level, cx + dir, cy, Items.CRATE);
			arcade.audio.play("drop", 0.5f, 1.4);
			vx *= 0.3;
			return;
		}
		if (onGround && !blocked(nx, py - 1.0) && !blocked(px, py - 1.0)) {
			px = nx;
			py -= 1.0;
			return;
		}
		px = d > 0 ? Math.floor(nx + HALF_W) - HALF_W - 1e-4 : Math.floor(nx - HALF_W) + 1 + HALF_W + 1e-4;
		if (blocked(px, py)) {
			px -= d;
		}
		vx = 0;
	}

	private void moveY(double d) {
		double ny = py + d;
		if (d > 0 && !downKey) {
			int row = (int) Math.floor(ny);
			if (py <= row + 1e-3) {
				for (int x = (int) Math.floor(px - HALF_W); x <= (int) Math.floor(px + HALF_W - 1e-6); x++) {
					if (level.get(x, row) == Items.PLATFORM) {
						py = row - 1e-4;
						vy = 0;
						onGround = true;
						return;
					}
				}
			}
		}
		if (!blocked(px, ny)) {
			py = ny;
			onGround = d >= 0 && (blocked(px, py + 0.02) || onPlatform());
			return;
		}
		if (d > 0) {
			py = Math.floor(ny) - 1e-4;
			if (blocked(px, py)) {
				py = Math.ceil(py - TALL) + TALL - 1e-4;
			}
			onGround = true;
		} else {
			py = Math.floor(ny - TALL) + 1 + TALL + 1e-4;
		}
		vy = 0;
	}

	private boolean onPlatform() {
		if (downKey) {
			return false;
		}
		int row = (int) Math.floor(py + 0.02);
		if (Math.abs(py + 1e-4 - row) > 0.02) {
			return false;
		}
		for (int x = (int) Math.floor(px - HALF_W); x <= (int) Math.floor(px + HALF_W - 1e-6); x++) {
			if (level.get(x, row) == Items.PLATFORM) {
				return true;
			}
		}
		return false;
	}

	/** Crates near you drop one block whenever there's nothing under them. */
	private void fallCrates() {
		int x0 = (int) Math.floor(px) - 20;
		int y0 = (int) Math.floor(py) - 14;
		for (int y = y0 + 28; y >= y0; y--) {
			for (int x = x0; x <= x0 + 40; x++) {
				if (level.get(x, y) == Items.CRATE && level.get(x, y + 1) == Items.AIR) {
					setTile(level, x, y, Items.AIR);
					setTile(level, x, y + 1, Items.CRATE);
					if (Items.solid(level.get(x, y + 2))) {
						arcade.audio.play("land", 0.6f, 0.8);
						arcade.shake(1.5);
					}
				}
			}
		}
	}

	private void facingTo(int dir) {
		if (dir != facing) {
			facing = dir;
			turn = 0.2;
		}
	}

	// ------------------------------------------------------------------ sims

	private final Sim.Host simHost = new Sim.Host() {
		@Override
		public boolean traderUnlocked() {
			// the map trader only turns up once you've got an iron pickaxe (or something better)
			for (int id = Items.IRON_PICK; id <= Items.EMBER_PICK; id++) {
				if (count(id) > 0) {
					return true;
				}
			}
			return false;
		}

		@Override
		public void sound(String name, double x, double y) {
			double d = Math.hypot(x - px, y - py);
			if (d < 28) {
				arcade.audio.play(name, (float) Math.max(0.15, 1 - d / 28), 0.95 + random.nextDouble() * 0.1);
			}
		}

		@Override
		public void burst(double x, double y, int argb, int n) {
			arcade.burst(screenX(x), screenY(y), argbColor(argb), n, 300);
		}

		@Override
		public void shake(double amount, double x, double y) {
			if (Math.abs(x - px) < 30) {
				arcade.shake(amount);
			}
		}

		@Override
		public void tile(Level l, int x, int y, int id) {
			setTile(l, x, y, id);
		}

		@Override
		public void fired(Sim sim, Proj p) {
			if (coop != null && coop.isHost()) {
				JsonObject m = new JsonObject();
				m.addProperty("t", "proj");
				m.addProperty("l", sim.level.id());
				JsonArray a = new JsonArray();
				for (double v : new double[] {p.kind, p.x, p.y, p.vx, p.vy, p.damage, p.hostile ? 1 : 0, p.homing ? 1 : 0, p.gravity, p.life}) {
					a.add(round(v));
				}
				m.add("p", a);
				coop.send(m);
			}
		}

		@Override
		public void killed(Sim sim, Mob mob, String killer) {
			for (int[] loot : drops(mob.kind)) {
				spawnPickup(sim.level.id(), mob.x, mob.y - mob.tall() / 2, (random.nextDouble() - 0.5) * 6, -5 - random.nextDouble() * 3,
						loot[0], loot[1], 0, true);
			}
		}

		@Override
		public void bossDown(Sim sim, Mob boss, String killer) {
			if (sim.dungeon == null) {
				return;
			}
			Dungeon d = sim.dungeon;
			setTile(d, (int) Math.floor(d.bossX), (int) Math.floor(d.bossY) - 1, Items.GATE);
			if (coop != null && coop.isHost()) {
				JsonObject m = new JsonObject();
				m.addProperty("t", "boss_dead");
				m.addProperty("d", d.index);
				coop.send(m);
			}
			bossDefeatedHere(d.index, boss.x, boss.y);
		}

		@Override
		public void text(double x, double y, String text) {
			// no words: the fights are read from the sparks, the steam and the sounds
		}

		@Override
		public void announce(String text) {
			say(text);
			if (coop != null && coop.isHost()) {
				JsonObject m = new JsonObject();
				m.addProperty("t", "say");
				m.addProperty("text", text);
				coop.send(m);
			}
		}
	};

	private void updateSims(double dt) {
		if (authoritative()) {
			Map<String, List<Sim.Target>> targets = new HashMap<>();
			if (deadTimer <= 0) {
				targets.computeIfAbsent(level.id(), k -> new ArrayList<>()).add(new Sim.Target("local", px, py));
			}
			for (Map.Entry<String, Remote> e : remotes.entrySet()) {
				Remote r = e.getValue();
				if (!r.dead) {
					targets.computeIfAbsent(r.level, k -> new ArrayList<>()).add(new Sim.Target(e.getKey(), r.x, r.y));
				}
			}
			for (Map.Entry<String, List<Sim.Target>> e : targets.entrySet()) {
				sim(levelById(e.getKey())).update(dt, e.getValue(), true);
			}
			Sim mine = sims.get(level.id());
			if (mine != null && !targets.containsKey(level.id())) {
				mine.update(dt, List.of(), true);
			}
		} else {
			sim(level).update(dt, List.of(), false);
		}
	}

	private void hazards(double dt) {
		if (deadTimer > 0) {
			return;
		}
		Sim sim = sim(level);
		for (Mob m : sim.mobs) {
			if (m.friendly() || (m.kind == Actors.STALKER && m.tele > 0.2)) {
				continue;
			}
			if (Math.abs(m.x - px) < m.halfW() + HALF_W && m.y > py - TALL && m.y - m.tall() < py) {
				hurt(m.damage(), m.x);
			}
		}
		for (Iterator<Proj> it = sim.projectiles.iterator(); it.hasNext(); ) {
			Proj p = it.next();
			if (!p.hostile) {
				continue;
			}
			if (p.homing) {
				double dx = px - p.x;
				double dy = (py - 1) - p.y;
				double len = Math.max(0.1, Math.hypot(dx, dy));
				double speed = Math.max(3, Math.hypot(p.vx, p.vy));
				p.vx += (dx / len * speed - p.vx) * Math.min(1, dt * 1.6);
				p.vy += (dy / len * speed - p.vy) * Math.min(1, dt * 1.6);
			}
			if (p.kind == Actors.PILLAR && p.age < 0.12) {
				continue;
			}
			double r = p.radius();
			double top = p.y - Math.max(r, p.reach());
			if (px + HALF_W > p.x - r && px - HALF_W < p.x + r && py > top && py - TALL < p.y + r) {
				hurt(p.damage, p.x);
				if (p.kind != Actors.PILLAR && p.kind != Actors.WAVE) {
					it.remove();
				}
			}
		}
		hazardTimer -= dt;
		for (int x = (int) Math.floor(px - HALF_W); x <= (int) Math.floor(px + HALF_W); x++) {
			for (int y = (int) Math.floor(py - TALL); y <= (int) Math.floor(py - 0.01); y++) {
				int id = level.get(x, y);
				if (id == Items.SPIKES) {
					hurt(2, px - facing);
				} else if (id == Items.LAVA && hazardTimer <= 0) {
					hazardTimer = 0.45;
					hurt(3, px - facing);
					arcade.burst(screenX(px), screenY(py), Color.web("#FF8A2E"), 6, -100);
				} else if (id == Items.KEY_FRAG) {
					setTile(level, x, y, Items.AIR);
					arcade.audio.play("rune");
					arcade.burst(screenX(x + 0.5), screenY(y + 0.5), Color.web("#C9A8FF"), 18, 0);

				} else if (id == Items.THORN) {
					hurt(1, px - facing);
				}
			}
		}
	}

	// ------------------------------------------------------------------ mouse: mine, fight, use, place

	private double worldX(double sx) {
		return camX + (sx - viewW / 2) / block;
	}

	private double worldY(double sy) {
		return camY + (sy - viewH / 2) / block;
	}

	private double screenX(double wx) {
		return Math.round((wx - camX) * block + viewW / 2);
	}

	private double screenY(double wy) {
		return Math.round((wy - camY) * block + viewH / 2);
	}

	private void handleMouse(double dt) {
		double mx = worldX(mouseX);
		double my = worldY(mouseY);
		int tx = (int) Math.floor(mx);
		int ty = (int) Math.floor(my);
		double reach = Math.hypot(mx - px, my - (py - 1.2));
		if ((mouseLeft || mouseRight) && Math.abs(mx - px) > 0.2) {
			facingTo(mx > px ? 1 : -1);
		}
		int held = ids[selected];
		if (mouseLeft) {
			frontTarget = 0;
			if (Items.bow(held) > 0) {
				shoot(mx, my);
				return;
			}
			if (swing >= 1) {
				swing = 0;
				arcade.audio.play("swing", 0.5f, 0.9 + random.nextDouble() * 0.2);
			}
			Mob target = mobAt(mx, my);
			if (target != null && reach < REACH + target.halfW() && attackCooldown <= 0) {
				attack(target);
				return;
			}
			if (friendlyFire && coop != null && attackCooldown <= 0 && reach < REACH) {
				String victim = remoteAt(mx, my);
				if (victim != null) {
					pvpHit(victim);
					return;
				}
			}
			if (reach > REACH) {
				mineProgress = 0;
				return;
			}
			int id = level.get(tx, ty);
			if (id == Items.RUNE || id == Items.RUNE_LIT) {
				if (attackCooldown <= 0) {
					attackCooldown = 0.4;
					strikeRune(tx, ty);
				}
				return;
			}
			if (id >= Items.GLYPH && id <= Items.GLYPH_LAST) {
				if (attackCooldown <= 0) {
					attackCooldown = 0.35;
					swing = 0;
					setTile(level, tx, ty, Items.GLYPH + (id - Items.GLYPH + 1) % 4);
					arcade.audio.play("rune", 0.7f, 0.8 + (id - Items.GLYPH) * 0.1);
				}
				return;
			}
			double hardness = Items.hardness(id);
			if (id == Items.AIR || hardness < 0 || Items.needsTier(id) > Items.pickTier(held)) {
				mineProgress = 0;
				if (id != Items.AIR && hardness >= 0 && swing == 0) {
					floater("Needs a better pickaxe", 0);
				}
				return;
			}
			if (tx != mineX || ty != mineY) {
				mineX = tx;
				mineY = ty;
				mineProgress = 0;
			}
			double speed = Items.needsTier(id) > 0 ? 0.5 + Items.pickTier(held) * 0.7 : 1 + Items.pickTier(held) * 0.25;
			double before = mineProgress;
			mineProgress += dt * speed / hardness;
			if ((int) (before * 4) != (int) (mineProgress * 4)) {
				arcade.audio.play("dig", 0.7f, 0.8 + random.nextDouble() * 0.4);
			}
			if (mineProgress >= 1) {
				breakBlock(tx, ty, id);
			}
		} else {
			mineProgress = 0;
		}
		if (mouseRight && useCooldown <= 0) {
			useCooldown = 0.22;
			rightClick(tx, ty, mx, my, reach);
		}
	}

	private String remoteAt(double x, double y) {
		for (Map.Entry<String, Remote> e : remotes.entrySet()) {
			Remote r = e.getValue();
			if (r.level.equals(level.id()) && !r.dead && Math.abs(r.x - x) < 0.7 && y > r.y - TALL - 0.3 && y < r.y + 0.3) {
				return e.getKey();
			}
		}
		return null;
	}

	private void pvpHit(String victim) {
		attackCooldown = 0.35;
		swing = 0;
		Remote r = remotes.get(victim);
		int damage = (int) Math.round(Items.damage(ids[selected]) * (strengthBuff > 0 ? 1.5 : 1));
		r.hurt = 0.2;
		arcade.audio.play("hit");
		arcade.shake(2.5);
		floater("-" + damage, 0, screenX(r.x), screenY(r.y - TALL));
		JsonObject m = new JsonObject();
		m.addProperty("t", "pvp");
		m.addProperty("to", victim);
		m.addProperty("dmg", damage);
		m.addProperty("x", round(px));
		if (coop.isHost()) {
			coop.sendTo(victim, m);
		} else {
			coop.send(m);
		}
	}

	private void rightClick(int tx, int ty, double mx, double my, double reach) {
		Sim sim = sim(level);
		for (Mob m : sim.mobs) {
			if (m.friendly() && Math.abs(m.x - mx) < 1 && my > m.y - m.tall() - 0.3 && my < m.y + 0.3 && Math.abs(m.x - px) < 4) {
				mouseRight = false;
				if (m.kind == Actors.TRADER) {
					shop = trades();
					shopTitle = "MAP TRADER";
					shopQuote = "\"Maps to the old places, friend. They don't come cheap.\"";
				} else if (Math.floorMod(m.id, 3) == 0) {
					shop = villageTrades();
					shopTitle = "VILLAGE MERCHANT";
					shopQuote = "\"" + LINES[(int) Math.floorMod(m.id * 7L, LINES.length)] + "\"";
				} else {
					speak(m);
					return;
				}
				arcade.audio.play("trade");
				arcade.openTrade();
				return;
			}
		}
		int id = reach < REACH ? level.get(tx, ty) : Items.AIR;
		switch (id) {
			case Items.BENCH, Items.ANVIL -> {
				// right-click a workbench / anvil: open crafting right there
				mouseRight = false;
				arcade.audio.play("open");
				arcade.openCrafting();
				return;
			}
			case Items.GATE -> {
				mouseRight = false;
				useGate(tx, ty);
				return;
			}
			case Items.CHEST -> {
				openChest(tx, ty);
				return;
			}
			case Items.BRAZIER -> {
				if (ids[selected] == Items.TORCH && counts[selected] > 0) {
					take(selected, 1);
					setTile(level, tx, ty, Items.BRAZIER_LIT);
					arcade.audio.play("fire");
					arcade.burst(screenX(tx + 0.5), screenY(ty + 0.3), Color.web("#FF8A2E"), 14, -80);
				}
				return;
			}
			case Items.LEVER -> {
				setTile(level, tx, ty, Items.LEVER_ON);
				arcade.audio.play("lever");
				arcade.shake(2);
				return;
			}
			default -> {
			}
		}
		int held = ids[selected];
		if (counts[selected] <= 0) {
			return;
		}
		switch (held) {
			case Items.HEAL_POTION -> {
				if (health < maxHealth) {
					health = Math.min(maxHealth, health + 8);
					take(selected, 1);
					arcade.audio.play("potion");
					arcade.burst(screenX(px), screenY(py - 1), Color.web("#FF6B70"), 14, -60);
				}
				return;
			}
			case Items.SWIFT_ELIXIR -> {
				speedBuff = 60;
				take(selected, 1);
				arcade.audio.play("potion");
				return;
			}
			case Items.STRENGTH_TONIC -> {
				strengthBuff = 60;
				take(selected, 1);
				arcade.audio.play("potion");
				return;
			}
			case Items.HEART_CRYSTAL -> {
				if (maxHealth < 30) {
					maxHealth += 2;
					health = Math.min(maxHealth, health + 2);
					take(selected, 1);
					arcade.audio.play("level");
					floater("Max health " + maxHealth, 0);
				}
				return;
			}
			default -> {
			}
		}
		if (Items.defense(held) > 0) {
			int old = armor;
			armor = held;
			take(selected, 1);
			if (old != 0) {
				give(old, 1);
			}
			arcade.audio.play("equip");
			floater("Wearing " + Items.name(held), 0);
			return;
		}
		if (Items.isBlock(held) && reach < REACH && (level.get(tx, ty) == Items.AIR || Liquids.liquid(level.get(tx, ty))) && canPlace(tx, ty, held)) {
			setTile(level, tx, ty, held);
			take(selected, 1);
			swing = 0;
			frontTarget = 0;
			useCooldown = 0.12;
			arcade.audio.play("place", 0.8f, 0.9 + random.nextDouble() * 0.2);
		}
	}

	private boolean canPlace(int x, int y, int id) {
		if (level instanceof Dungeon) {
			return id == Items.TORCH || id == Items.PLATFORM;
		}
		if (Items.solid(id) && px + HALF_W > x && px - HALF_W < x + 1 && py > y && py - TALL < y + 1) {
			return false;
		}
		boolean wall = level.wallAt(x, y) != 0;
		return wall || level.get(x - 1, y) != Items.AIR || level.get(x + 1, y) != Items.AIR || level.get(x, y + 1) != Items.AIR
				|| level.get(x, y - 1) != Items.AIR;
	}

	private void shoot(double mx, double my) {
		if (attackCooldown > 0) {
			return;
		}
		int bow = ids[selected];
		if (count(Items.ARROW) <= 0) {
			if (swing >= 1) {
				floater("No arrows", 0);
				swing = 0;
			}
			return;
		}
		attackCooldown = bow == Items.VOID_BOW ? 0.3 : 0.45;
		consume(Items.ARROW, 1);
		double sx = px;
		double sy = py - 1.35;
		double dx = mx - sx;
		double dy = my - sy;
		double len = Math.max(0.1, Math.hypot(dx, dy));
		double speed = 22;
		int damage = (int) Math.round(Items.bow(bow) * (strengthBuff > 0 ? 1.5 : 1));
		swing = 0;
		arcade.audio.play("bow");
		if (authoritative()) {
			sim(level).fire(new Proj(Actors.PLAYER_ARROW, sx, sy, dx / len * speed, dy / len * speed, damage, false));
		} else {
			JsonObject m = new JsonObject();
			m.addProperty("t", "shoot");
			m.addProperty("l", level.id());
			JsonArray a = new JsonArray();
			for (double v : new double[] {sx, sy, dx / len * speed, dy / len * speed, damage}) {
				a.add(round(v));
			}
			m.add("p", a);
			coop.send(m);
		}
	}

	private Mob mobAt(double x, double y) {
		for (Mob m : sim(level).mobs) {
			if (!m.friendly() && Math.abs(m.x - x) < m.halfW() + 0.35 && y > m.y - m.tall() - 0.35 && y < m.y + 0.35) {
				return m;
			}
		}
		return null;
	}

	private void attack(Mob m) {
		attackCooldown = ids[selected] == Items.MAGMA_MAUL ? 0.5 : 0.3;
		swing = 0;
		int damage = (int) Math.round(Items.damage(ids[selected]) * (strengthBuff > 0 ? 1.5 : 1));
		double dir = Math.signum(m.x - px);
		arcade.shake(2.5);
		floater("-" + damage, 0, screenX(m.x), screenY(m.y - m.tall()));
		if (authoritative()) {
			sim(level).hit(m, damage, dir, "local");
		} else {
			m.hurt = 0.18;
			arcade.audio.play("hit");
			JsonObject msg = new JsonObject();
			msg.addProperty("t", "hit");
			msg.addProperty("l", level.id());
			msg.addProperty("id", m.id);
			msg.addProperty("dmg", damage);
			msg.addProperty("dir", dir);
			coop.send(msg);
		}
	}

	private void strikeRune(int x, int y) {
		if (!(level instanceof Dungeon d) || level.get(x, y) == Items.RUNE_LIT) {
			return;
		}
		int lit = 0;
		int index = -1;
		for (int i = 0; i < d.puzzle.size(); i++) {
			int[] p = d.puzzle.get(i);
			if (level.get(p[0], p[1]) == Items.RUNE_LIT) {
				lit++;
			}
			if (p[0] == x && p[1] == y) {
				index = i;
			}
		}
		swing = 0;
		if (index >= 0 && d.runeSymbol[index] == lit) {
			setTile(level, x, y, Items.RUNE_LIT);
			arcade.audio.play("rune", 1, 1 + lit * 0.12);
			arcade.burst(screenX(x + 0.5), screenY(y + 0.5), Color.web("#FF8A2E"), 12, 0);
		} else {
			for (int[] p : d.puzzle) {
				setTile(level, p[0], p[1], Items.RUNE);
			}
			arcade.audio.play("rune_wrong");
			arcade.shake(5);
		}
	}

	private void breakBlock(int x, int y, int id) {
		boolean hadCrown = id == Items.TRUNK && level == world && world.isTree(x) && world.get(x, world.treeTop(x)) == Items.TRUNK;
		setTile(level, x, y, Items.AIR);
		mineProgress = 0;
		arcade.audio.play("break", 0.8f, 0.9 + random.nextDouble() * 0.2);
		Color color = argbColor(Items.tone(id));
		for (int i = 0; i < 10; i++) {
			arcade.particle(screenX(x + random.nextDouble()), screenY(y + random.nextDouble()), (random.nextDouble() - 0.5) * 160,
					-random.nextDouble() * 160, color, 3 + random.nextInt(3), 600, 0.7);
		}
		int drop = Items.drop(id, random);
		if (drop > 0) {
			give(drop, 1);
		}
		if (id == Items.THORN) {
			// a thornstalk (cactus) loses everything above the broken block
			int above = 0;
			for (int up = y - 1; up > y - 12 && level.get(x, up) == Items.THORN; up--) {
				setTile(level, x, up, Items.AIR);
				above++;
				for (int i = 0; i < 4; i++) {
					arcade.particle(screenX(x + random.nextDouble()), screenY(up + random.nextDouble()), (random.nextDouble() - 0.5) * 120,
							-random.nextDouble() * 120, color, 3, 500, 0.6);
				}
			}
			for (int i = 0; i < above; i++) {
				int more = Items.drop(id, random);
				if (more > 0) {
					give(more, 1);
				}
			}
		}
		if (id == Items.TRUNK && level == world) {
			int logs = 0;
			for (int up = y - 1; up > y - 10 && world.get(x, up) == Items.TRUNK; up--) {
				setTile(world, x, up, Items.AIR);
				logs++;
			}
			if (logs > 0) {
				give(Items.LOG, logs);
			}
		}
		if (hadCrown && world.get(x, world.treeTop(x)) != Items.TRUNK) {
			int top = world.treeTop(x);
			int biome = world.biome(x);
			Color leaf = biome == World.FROST ? Color.web("#86AFC4") : biome == World.ASHLANDS ? Color.web("#8E7444") : Color.web("#AE2E36");
			for (int i = 0; i < 40; i++) {
				arcade.particle(screenX(x + 0.5 + (random.nextDouble() - 0.5) * 5), screenY(top - 1 - random.nextDouble() * 3.5),
						(random.nextDouble() - 0.5) * 120, -random.nextDouble() * 60, leaf, 3 + random.nextInt(3), 260, 0.9 + random.nextDouble() * 0.6);
			}
			if (random.nextDouble() < 0.6) {
				give(Items.STICK, 1 + random.nextInt(2));
			}
		}
	}

	// ------------------------------------------------------------------ atmosphere

	private boolean underground() {
		return level == world && camY > world.surface((int) Math.floor(camX)) + 7;
	}

	private void updateAtmosphere(double dt) {
		// the sky drifts towards the colour of the biome you're in
		if (level == world) {
			double[] wgt = world.biomeWeights(camX);
			Color base = arcade.backdrop.accent();
			Color ash = Color.web("#C8641E");
			Color frost = Color.web("#3E7AA6");
			Color target = new Color(base.getRed() * wgt[0] + ash.getRed() * wgt[1] + frost.getRed() * wgt[2],
					base.getGreen() * wgt[0] + ash.getGreen() * wgt[1] + frost.getGreen() * wgt[2],
					base.getBlue() * wgt[0] + ash.getBlue() * wgt[1] + frost.getBlue() * wgt[2], 1);
			skyTint = skyTint == null ? target : skyTint.interpolate(target, Math.min(1, dt * 1.5));
			arcade.backdrop.tint(skyTint);
			arcade.backdrop.trees = wgt[0] + wgt[2] * 0.8 + wgt[1] * 0.15;
		}
		double halfW = viewW / Math.max(1, block) / 2 + 2;
		double halfH = viewH / Math.max(1, block) / 2 + 2;
		int[] kinds;
		double rate;
		if (level instanceof Dungeon d) {
			kinds = switch (d.theme) {
				case CRYPT -> new int[] {RED_MOTE, CAVE_DUST, CAVE_DUST};
				case FOUNDRY -> new int[] {SPARK, EMBER, CAVE_DUST};
				case SPIRE -> new int[] {VOID_MOTE, VOID_MOTE, CAVE_DUST};
			};
			rate = 14;
		} else if (underground()) {
			int depth = (int) (camY - world.surface((int) Math.floor(camX)));
			kinds = depth > 50 ? new int[] {CAVE_DUST, EMBER} : new int[] {CAVE_DUST};
			rate = 9;
		} else {
			int biome = world.biome((int) Math.floor(camX));
			kinds = switch (biome) {
				case World.ASHLANDS -> new int[] {DUST, DUST, DUST, EMBER};
				case World.FROST -> new int[] {SNOW};
				default -> new int[] {FIREFLY, LEAF, LEAF};
			};
			rate = biome == World.FROST ? 34 : 14;
		}
		moteClock += dt * rate;
		while (moteClock > 1 && motes.size() < 160) {
			moteClock -= 1;
			Mote m = new Mote();
			m.kind = kinds[random.nextInt(kinds.length)];
			m.x = camX + (random.nextDouble() * 2 - 1) * halfW;
			boolean falls = m.kind == LEAF || m.kind == SNOW;
			boolean rises = m.kind == EMBER || m.kind == SPARK || m.kind == VOID_MOTE || m.kind == RED_MOTE;
			m.y = falls ? camY - halfH + random.nextDouble() * 2 : rises ? camY + halfH - random.nextDouble() * 3 : camY + (random.nextDouble() * 2 - 1) * halfH;
			m.phase = random.nextDouble() * 10;
			m.max = m.life = switch (m.kind) {
				case SPARK -> 1.2 + random.nextDouble();
				case FIREFLY -> 5 + random.nextDouble() * 4;
				default -> 6 + random.nextDouble() * 6;
			};
			switch (m.kind) {
				case LEAF -> {
					m.vy = 1.0 + random.nextDouble() * 0.8;
					m.vx = -0.6 - random.nextDouble();
				}
				case SNOW -> {
					m.vy = 1.6 + random.nextDouble() * 1.2;
					m.vx = -0.8 - random.nextDouble() * 0.6;
				}
				case DUST -> {
					m.vx = -2.5 - random.nextDouble() * 2.5;
					m.vy = (random.nextDouble() - 0.5) * 0.4;
				}
				case EMBER, RED_MOTE, VOID_MOTE -> {
					m.vy = -0.5 - random.nextDouble() * 0.9;
					m.vx = (random.nextDouble() - 0.5) * 0.6;
				}
				case SPARK -> {
					m.vy = -3 - random.nextDouble() * 3;
					m.vx = (random.nextDouble() - 0.5) * 2;
				}
				default -> {
					m.vx = (random.nextDouble() - 0.5) * 0.3;
					m.vy = (random.nextDouble() - 0.5) * 0.3;
				}
			}
			motes.add(m);
		}
		for (Iterator<Mote> it = motes.iterator(); it.hasNext(); ) {
			Mote m = it.next();
			m.life -= dt;
			m.phase += dt;
			double sway = Math.sin(m.phase * 2.1) * (m.kind == LEAF ? 1.4 : m.kind == SNOW ? 0.5 : m.kind == FIREFLY ? 0.8 : 0.2);
			m.x += (m.vx + sway) * dt;
			m.y += (m.vy + (m.kind == FIREFLY ? Math.cos(m.phase * 1.7) * 0.6 : 0)) * dt;
			if (m.life <= 0 || Math.abs(m.x - camX) > halfW + 6 || Math.abs(m.y - camY) > halfH + 6
					|| (m.kind != FIREFLY && Items.solid(level.get((int) Math.floor(m.x), (int) Math.floor(m.y))) && m.kind != SPARK)) {
				it.remove();
			}
		}
	}

	/** Motes that don't glow (drawn under the darkness). */
	private void drawMotes(GraphicsContext g, boolean glowing) {
		if (glowing) {
			g.setGlobalBlendMode(BlendMode.SRC_OVER); // SCREEN on a JavaFX canvas wrote see-through pixels (the launcher showed through)
		}
		for (Mote m : motes) {
			boolean glow = m.kind == FIREFLY || m.kind == EMBER || m.kind == RED_MOTE || m.kind == SPARK || m.kind == VOID_MOTE;
			if (glow != glowing) {
				continue;
			}
			double a = Math.min(1, Math.min(m.life, m.max - m.life) * 1.5);
			double x = screenX(m.x);
			double y = screenY(m.y);
			double px1 = scale;
			switch (m.kind) {
				case LEAF -> {
					g.setFill(Color.rgb(174, 46, 54, 0.85 * a));
					boolean flip = Math.sin(m.phase * 3) > 0;
					g.fillRect(x, y, px1 * (flip ? 2 : 1), px1 * (flip ? 1 : 2));
					g.setFill(Color.rgb(120, 26, 32, 0.85 * a));
					g.fillRect(x + px1, y + px1, px1, px1);
				}
				case SNOW -> {
					g.setFill(Color.rgb(240, 246, 255, 0.85 * a));
					g.fillRect(x, y, px1 * (m.phase % 3 < 1 ? 2 : 1), px1);
				}
				case DUST -> {
					g.setFill(Color.rgb(214, 180, 120, 0.4 * a));
					g.fillRect(x, y, px1, px1);
				}
				case CAVE_DUST -> {
					g.setFill(Color.rgb(220, 210, 230, 0.16 * a));
					g.fillRect(x, y, px1, px1);
				}
				default -> {
					Color c = switch (m.kind) {
						case FIREFLY -> Color.rgb(220, 255, 140);
						case EMBER, SPARK -> Color.rgb(255, 150, 60);
						case RED_MOTE -> Color.rgb(255, 70, 80);
						default -> Color.rgb(180, 130, 255);
					};
					double pulse = m.kind == FIREFLY ? 0.5 + 0.5 * Math.sin(m.phase * 4) : 1;
					double r = block * (m.kind == SPARK ? 0.25 : 0.45);
					g.setFill(new RadialGradient(0, 0, x, y, r, false, CycleMethod.NO_CYCLE,
							new Stop(0, c.deriveColor(0, 1, 1, 0.45 * a * pulse)), new Stop(1, Color.TRANSPARENT)));
					g.fillRect(x - r, y - r, r * 2, r * 2);
					g.setFill(c.deriveColor(0, 0.6, 1.4, a * pulse));
					g.fillRect(Math.round(x), Math.round(y), px1, px1);
				}
			}
		}
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
	}

	/** A soft, horizontally tileable fog band (white noise in alpha), tinted per colour and cached. */
	private Image fog(Color c) {
		int key = (int) (c.getRed() * 255) << 16 | (int) (c.getGreen() * 255) << 8 | (int) (c.getBlue() * 255);
		return fogImages.computeIfAbsent(key, k -> {
			int w = 256;
			int h = 64;
			int[] px = new int[w * h];
			int rgb = k & 0xFFFFFF;
			for (int y = 0; y < h; y++) {
				double vertical = Math.sin(Math.PI * y / (h - 1));
				for (int x = 0; x < w; x++) {
					double a = 0.5 + 0.22 * Math.sin(x * Math.PI * 2 / w * 2 + y * 0.13) + 0.18 * Math.sin(x * Math.PI * 2 / w * 5 + y * 0.31)
							+ 0.1 * Math.sin(x * Math.PI * 2 / w * 11 - y * 0.2);
					int alpha = (int) Math.max(0, Math.min(255, a * vertical * vertical * 255));
					px[y * w + x] = alpha << 24 | rgb;
				}
			}
			javafx.scene.image.WritableImage image = new javafx.scene.image.WritableImage(w, h);
			image.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), px, 0, w);
			return image;
		});
	}

	private void fogBand(GraphicsContext g, Color c, double y, double height, double scroll, double alpha) {
		Image img = fog(c);
		double tileW = height * 4;
		double offset = -Math.floorMod((long) scroll, (long) Math.max(1, tileW));
		g.setGlobalAlpha(alpha);
		g.setImageSmoothing(true);
		for (double x = offset; x < viewW; x += tileW) {
			g.drawImage(img, x, y, tileW + 1, height);
		}
		g.setImageSmoothing(false);
		g.setGlobalAlpha(1);
	}

	private void drawFog(GraphicsContext g, double time, boolean afterDark) {
		if (level instanceof Dungeon d) {
			if (afterDark) {
				Color c = argbColor(d.theme.glow).deriveColor(0, 1, 1.8, 1);
				fogBand(g, c, viewH * 0.62, viewH * 0.42, camX * block * 0.6 + time * 10, 0.08);
				fogBand(g, c, viewH * 0.78, viewH * 0.3, camX * block * 0.9 + time * 18, 0.07);
			}
			return;
		}
		if (underground()) {
			if (afterDark) {
				fogBand(g, Color.web("#8A7AA0"), viewH * 0.7, viewH * 0.36, camX * block * 0.7 + time * 6, 0.07);
			}
			return;
		}
		if (!afterDark) {
			double ground = screenY(world.surface((int) Math.floor(camX)));
			Color c = Backdrop.mix(arcade.backdrop.background(), skyTint == null ? arcade.backdrop.accent() : skyTint, 0.55)
					.deriveColor(0, 0.8, 1.6, 1);
			fogBand(g, c, ground - block * 3.2, block * 3.2, camX * block * 0.35 + time * 7, 0.28);
			fogBand(g, c, ground - block * 1.6, block * 2.4, camX * block * 0.75 + time * 13, 0.2);
		}
	}

	/** Faint moonlight shafts slanting down from the moon. */
	private void drawRays(GraphicsContext g, double time) {
		if (level != world || underground()) {
			return;
		}
		g.setGlobalBlendMode(BlendMode.SRC_OVER); // SCREEN on a JavaFX canvas wrote see-through pixels (the launcher showed through)
		double mx = viewW * 0.78;
		for (int i = 0; i < 3; i++) {
			double a = 0.035 + 0.015 * Math.sin(time * 0.4 + i * 2);
			double spread = 60 + i * 70;
			g.setFill(new LinearGradient(0, 0, 0, viewH, false, CycleMethod.NO_CYCLE, new Stop(0, Color.rgb(255, 225, 215, a)),
					new Stop(1, Color.TRANSPARENT)));
			g.fillPolygon(new double[] {mx - 10 - i * 30, mx + 10 - i * 30, mx - spread * 2.2 - i * 90, mx - spread * 3 - i * 90},
					new double[] {viewH * 0.17, viewH * 0.17, viewH, viewH}, 4);
		}
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
	}

	/** Edge light / shadow on blocks next to open space, and soft shadows on the walls under overhangs. */
	private void edgeShade(GraphicsContext g, int x, int y, double sx, double sy, int id) {
		double p = scale;
		if (Items.solid(id)) {
			if (!Items.solid(level.get(x, y - 1)) && id != Items.GRASS && id != Items.SNOW) {
				g.setFill(Color.rgb(255, 240, 230, 0.12));
				g.fillRect(sx, sy, block, p);
			}
			if (!Items.solid(level.get(x, y + 1))) {
				g.setFill(Color.rgb(0, 0, 0, 0.3));
				g.fillRect(sx, sy + block - p, block, p);
			}
			if (!Items.solid(level.get(x - 1, y))) {
				g.setFill(Color.rgb(255, 240, 230, 0.06));
				g.fillRect(sx, sy, p, block);
			}
			if (!Items.solid(level.get(x + 1, y))) {
				g.setFill(Color.rgb(0, 0, 0, 0.22));
				g.fillRect(sx + block - p, sy, p, block);
			}
			return;
		}
		if (level.wallAt(x, y) == 0) {
			return;
		}
		if (Items.solid(level.get(x, y - 1))) {
			g.setFill(ao(0));
			g.fillRect(sx, sy, block, block * 0.55);
		}
		if (Items.solid(level.get(x - 1, y))) {
			g.setFill(ao(1));
			g.fillRect(sx, sy, block * 0.4, block);
		}
		if (Items.solid(level.get(x + 1, y))) {
			g.setFill(ao(2));
			g.fillRect(sx + block * 0.6, sy, block * 0.4, block);
		}
	}

	private LinearGradient ao(int side) {
		return aoCache.computeIfAbsent(side, k -> switch (k) {
			case 0 -> new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, new Stop(0, Color.rgb(0, 0, 0, 0.55)), new Stop(1, Color.TRANSPARENT));
			case 1 -> new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, new Stop(0, Color.rgb(0, 0, 0, 0.4)), new Stop(1, Color.TRANSPARENT));
			default -> new LinearGradient(1, 0, 0, 0, true, CycleMethod.NO_CYCLE, new Stop(0, Color.rgb(0, 0, 0, 0.4)), new Stop(1, Color.TRANSPARENT));
		});
	}

	// ------------------------------------------------------------------ dropped items

	void dropSelected(boolean wholeStack) {
		if (counts[selected] <= 0 || deadTimer > 0) {
			return;
		}
		int id = ids[selected];
		int n = wholeStack ? counts[selected] : 1;
		take(selected, n);
		throwItem(id, n);
	}

	void dropStack(int id, int n) {
		if (n > 0) {
			throwItem(id, n);
		}
	}

	private void throwItem(int id, int n) {
		spawnPickup(level.id(), px + facing * 0.4, py - 1.3, facing * 6 + vx * 0.5, -4, id, n, 1.2, true);
		arcade.audio.play("swing", 0.6f, 1.3);
		swing = 0;
	}

	private void spawnPickup(String levelId, double x, double y, double vxx, double vyy, int id, int n, double wait, boolean broadcast) {
		Pickup p = new Pickup();
		p.pid = random.nextLong();
		p.level = levelId;
		p.id = id;
		p.count = n;
		p.x = x;
		p.y = y;
		p.vx = vxx;
		p.vy = vyy;
		p.wait = wait;
		pickups.add(p);
		if (broadcast && coop != null && (coop.isHost() || joined)) {
			coop.send(pickupMessage(p));
		}
	}

	private static JsonObject pickupMessage(Pickup p) {
		JsonObject m = new JsonObject();
		m.addProperty("t", "drop");
		m.addProperty("pid", p.pid);
		m.addProperty("l", p.level);
		m.addProperty("id", p.id);
		m.addProperty("n", p.count);
		m.addProperty("x", round(p.x));
		m.addProperty("y", round(p.y));
		m.addProperty("vx", round(p.vx));
		m.addProperty("vy", round(p.vy));
		return m;
	}

	private void updatePickups(double dt) {
		for (Iterator<Pickup> it = pickups.iterator(); it.hasNext(); ) {
			Pickup p = it.next();
			p.age += dt;
			p.wait -= dt;
			if (p.age > 300) {
				it.remove();
				continue;
			}
			Level l = levelById(p.level);
			boolean here = l == level;
			double dx = px - p.x;
			double dy = (py - 0.8) - p.y;
			double d = Math.hypot(dx, dy);
			if (here && deadTimer <= 0 && p.wait <= 0 && d < 3.2) {
				// drift into your pocket
				p.vx += dx / Math.max(0.2, d) * 40 * dt;
				p.vy += dy / Math.max(0.2, d) * 40 * dt;
			} else {
				p.vy = Math.min(16, p.vy + 30 * dt);
				p.vx *= Math.pow(0.05, dt);
			}
			double nx = p.x + p.vx * dt;
			double ny = p.y + p.vy * dt;
			if (Items.solid(l.get((int) Math.floor(nx), (int) Math.floor(p.y)))) {
				p.vx = 0;
			} else {
				p.x = nx;
			}
			if (Items.solid(l.get((int) Math.floor(p.x), (int) Math.floor(ny + 0.25)))) {
				p.y = Math.floor(ny + 0.25) - 0.25;
				p.vy = 0;
				p.vx *= 0.8;
			} else {
				p.y = ny;
			}
			if (here && deadTimer <= 0 && p.wait <= 0 && d < 0.9 && room(p.id, p.count)) {
				it.remove();
				give(p.id, p.count);
				if (coop != null && (coop.isHost() || joined)) {
					JsonObject m = new JsonObject();
					m.addProperty("t", "took");
					m.addProperty("pid", p.pid);
					coop.send(m);
				}
			}
		}
	}

	private boolean room(int id, int n) {
		int space = 0;
		for (int i = 0; i < SLOTS; i++) {
			if (counts[i] == 0) {
				space += Items.maxStack(id);
			} else if (ids[i] == id) {
				space += Items.maxStack(id) - counts[i];
			}
		}
		return space >= n;
	}

	private void drawPickups(GraphicsContext g, double time) {
		double size = block * 0.62;
		for (Pickup p : pickups) {
			if (!p.level.equals(level.id())) {
				continue;
			}
			double bob = Math.sin(time * 3 + p.pid % 7) * 3;
			double cx = screenX(p.x);
			double cy = screenY(p.y) + bob - size / 2;
			Sprites.glow(g, cx, cy, size, Color.rgb(255, 240, 220, 0.18));
			g.drawImage(Items.icon(p.id), cx - size / 2, cy - size / 2, size, size);
			if (p.count > 1) {
				arcade.label(g, String.valueOf(p.count), cx + size / 2, cy + size / 2, 9, Color.web("#F4EFEE"), true, false, true);
			}
		}
	}

	// ------------------------------------------------------------------ chests, gates, loot

	private void openChest(int x, int y) {
		if (!(level instanceof Dungeon d)) {
			setTile(level, x, y, Items.CHEST_OPEN);
			arcade.audio.play("chest");
			arcade.burst(screenX(x + 0.5), screenY(y + 0.3), Color.web("#FFE08A"), 20, 200);
			SplittableRandom r = new SplittableRandom(world.seed * 13 + x * 131L + y * 17L);
			World.Structure st = world.structureAt(x);
			boolean outpost = st != null && st.type() == World.OUTPOST;
			give(Items.IRON, 2 + r.nextInt(outpost ? 6 : 3));
			give(Items.ARROW, 6 + r.nextInt(12));
			if (r.nextDouble() < (outpost ? 0.6 : 0.3)) {
				give(Items.HEAL_POTION, 1);
			}
			if (outpost && r.nextDouble() < 0.45) {
				give(Items.RUBY, 1 + r.nextInt(2));
			}
			if (outpost && r.nextDouble() < 0.15) {
				give(Items.HEART_CRYSTAL, 1);
			}
			if (!outpost && r.nextDouble() < 0.5) {
				give(Items.TORCH, 6);
			}
			return;
		}
		setTile(level, x, y, Items.CHEST_OPEN);
		arcade.audio.play("chest");
		arcade.burst(screenX(x + 0.5), screenY(y + 0.3), Color.web("#FFE08A"), 20, 200);
		SplittableRandom r = new SplittableRandom(world.seed * 7 + x * 131L + y * 17L + d.index);
		List<int[]> loot = new ArrayList<>();
		switch (d.theme) {
			case CRYPT -> {
				loot.add(new int[] {Items.HEAL_POTION, 1 + r.nextInt(2)});
				loot.add(new int[] {Items.ARROW, 10 + r.nextInt(12)});
				loot.add(new int[] {Items.IRON, 3 + r.nextInt(4)});
				if (r.nextDouble() < 0.35) {
					loot.add(new int[] {Items.CRYPT_BLADE, 1});
				}
				if (r.nextDouble() < 0.25) {
					loot.add(new int[] {Items.HEART_CRYSTAL, 1});
				}
			}
			case FOUNDRY -> {
				loot.add(new int[] {Items.STRENGTH_TONIC, 1});
				loot.add(new int[] {Items.RUBY, 2 + r.nextInt(3)});
				loot.add(new int[] {Items.HEAL_POTION, 1 + r.nextInt(2)});
				if (r.nextDouble() < 0.3) {
					loot.add(new int[] {Items.HEART_CRYSTAL, 1});
				}
			}
			case SPIRE -> {
				loot.add(new int[] {Items.SWIFT_ELIXIR, 1 + r.nextInt(2)});
				loot.add(new int[] {Items.HEAL_POTION, 2});
				loot.add(new int[] {Items.BLOODSTONE, 2 + r.nextInt(3)});
				if (r.nextDouble() < 0.2) {
					loot.add(new int[] {Items.FEATHER, 1});
				}
				if (r.nextDouble() < 0.35) {
					loot.add(new int[] {Items.HEART_CRYSTAL, 1});
				}
			}
		}
		for (int[] l : loot) {
			give(l[0], l[1]);
		}
	}

	private void useGate(int x, int y) {
		if (level == world) {
			for (int i = 0; i < 3; i++) {
				if (Math.abs(world.gateX(i) - x) <= 1 && Math.abs(world.gateY(i) - y) <= 1) {
					Dungeon d = dungeon(i);
					returnX = px;
					returnY = py;
					arcade.audio.play("gate");
					arcade.travel(d.theme.title.toUpperCase(), () -> {
						level = d;
						respawn();
						camReady = false;
						say(d.theme.title);
					});
					return;
				}
			}
			return;
		}
		Dungeon d = (Dungeon) level;
		arcade.audio.play("gate");
		arcade.travel("VELO WORLD", () -> {
			level = world;
			px = returnX;
			py = returnY;
			if ((returnX == 0 && returnY == 0) || blocked(px, py)) {
				px = world.gateX(d.index) + 2.5;
				py = world.gateY(d.index) + 1 - 1e-3;
			}
			camReady = false;
		});
	}

	static List<int[]> drops(int kind) {
		SplittableRandom r = new SplittableRandom();
		List<int[]> out = new ArrayList<>();
		switch (kind) {
			case Actors.SLIME, Actors.CRYPT_SLIME -> out.add(new int[] {Items.GEL, 1 + r.nextInt(2)});
			case Actors.MAGMA_SLIME -> {
				out.add(new int[] {Items.GEL, 2});
				if (r.nextDouble() < 0.3) {
					out.add(new int[] {Items.COAL, 2});
				}
			}
			case Actors.BAT -> {
				if (r.nextDouble() < 0.5) {
					out.add(new int[] {Items.SINEW, 1});
				}
			}
			case Actors.SCUTTLER -> {
				out.add(new int[] {Items.SINEW, 1});
				if (r.nextDouble() < 0.1) {
					out.add(new int[] {Items.RUBY, 1});
				}
			}
			case Actors.FROST_WISP -> out.add(new int[] {Items.ARROW, 3});
			case Actors.REVENANT -> out.add(new int[] {Items.IRON, 1 + r.nextInt(2)});
			case Actors.SKELETON -> out.add(new int[] {Items.ARROW, 2 + r.nextInt(3)});
			case Actors.IMP -> {
				out.add(new int[] {Items.COAL, 2});
				if (r.nextDouble() < 0.15) {
					out.add(new int[] {Items.STRENGTH_TONIC, 1});
				}
			}
			case Actors.STALKER -> {
				if (r.nextDouble() < 0.3) {
					out.add(new int[] {Items.AETHERITE, 1});
				}
				if (r.nextDouble() < 0.2) {
					out.add(new int[] {Items.HEAL_POTION, 1});
				}
			}
			case Actors.BANDIT -> {
				out.add(new int[] {Items.IRON, 1 + r.nextInt(2)});
				if (r.nextDouble() < 0.25) {
					out.add(new int[] {Items.HEAL_POTION, 1});
				}
			}
			case Actors.BANDIT_ARCHER -> out.add(new int[] {Items.ARROW, 4 + r.nextInt(5)});
			case Actors.VOID_WISP -> {
				if (r.nextDouble() < 0.3) {
					out.add(new int[] {Items.HEAL_POTION, 1});
				}
			}
			default -> {
			}
		}
		return out;
	}

	private static List<int[]> bossLoot(int index) {
		return switch (index) {
			case 0 -> List.of(new int[] {Items.WARDEN_SIGIL, 2}, new int[] {Items.BONE_BOW, 1}, new int[] {Items.HEART_CRYSTAL, 2},
					new int[] {Items.HEAL_POTION, 3});
			case 1 -> List.of(new int[] {Items.GOLEM_CORE, 2}, new int[] {Items.MAGMA_MAUL, 1}, new int[] {Items.HEART_CRYSTAL, 2},
					new int[] {Items.STRENGTH_TONIC, 2});
			default -> List.of(new int[] {Items.HERALD_EYE, 2}, new int[] {Items.VOID_EDGE, 1}, new int[] {Items.HEART_CRYSTAL, 3},
					new int[] {Items.FEATHER, 1});
		};
	}

	private void giveTo(String killer, Sim sim, double x, List<int[]> loot) {
		if (loot.isEmpty()) {
			return;
		}
		String who = killer;
		if ("arrow".equals(killer)) {
			who = "local";
			double best = sim.level == level ? Math.abs(px - x) : Double.MAX_VALUE;
			for (Map.Entry<String, Remote> e : remotes.entrySet()) {
				if (e.getValue().level.equals(sim.level.id()) && Math.abs(e.getValue().x - x) < best) {
					best = Math.abs(e.getValue().x - x);
					who = e.getKey();
				}
			}
		}
		if ("local".equals(who) || coop == null) {
			for (int[] l : loot) {
				give(l[0], l[1]);
			}
		} else {
			JsonObject m = new JsonObject();
			m.addProperty("t", "loot");
			JsonArray a = new JsonArray();
			for (int[] l : loot) {
				a.add(l[0]);
				a.add(l[1]);
			}
			m.add("items", a);
			coop.sendTo(who, m);
		}
	}

	private void bossDefeatedHere(int index, double bx, double by) {
		Dungeon d = dungeon(index);
		boolean first = !d.bossDefeated || level == d;
		d.bossDefeated = true;
		world.setCleared(index, true);
		if (level == d && first) {
			arcade.audio.play("victory");
			arcade.shake(18);
			for (int i = 0; i < 4; i++) {
				arcade.burst(screenX(bx + (random.nextDouble() - 0.5) * 3), screenY(by - random.nextDouble() * 3), Color.web("#FFE08A"), 24, 200);
			}
			for (int[] l : bossLoot(index)) {
				give(l[0], l[1]);
			}
			say(d.theme.boss + " defeated!");
			String unlock = index == 0 ? "Bloodstone" : index == 1 ? "Emberite" : "Aetherite";
			floater(unlock + " ore now grows deep underground", 0);
		}
		arcade.saveSoon();
	}

	// ------------------------------------------------------------------ the player getting hurt

	private void hurt(int damage, double fromX) {
		if (invulnerable > 0 || deadTimer > 0 || damage <= 0) {
			return;
		}
		int def = Items.defense(armor);
		int taken = Math.max(1, (int) Math.round(damage * (1 - def * 0.06)));
		health -= taken;
		invulnerable = 0.8;
		sinceHurt = 0;
		vx = Math.signum(px - fromX) * 8;
		vy = -6;
		onGround = false;
		arcade.audio.play("hurt");
		arcade.shake(6);
		arcade.flash(Color.rgb(215, 38, 45, 0.35));
		if (health <= 0) {
			health = 0;
			deadTimer = 2.4;
			arcade.audio.play("death");
			arcade.burst(screenX(px), screenY(py - 1), Color.web("#D7262D"), 24, 300);
		}
	}

	// ------------------------------------------------------------------ inventory

	void give(int id, int n) {
		int left = n;
		for (int i = 0; i < SLOTS && left > 0; i++) {
			if (ids[i] == id && counts[i] < Items.maxStack(id)) {
				int add = Math.min(left, Items.maxStack(id) - counts[i]);
				counts[i] += add;
				left -= add;
			}
		}
		for (int i = 0; i < SLOTS && left > 0; i++) {
			if (counts[i] == 0) {
				ids[i] = id;
				int add = Math.min(left, Items.maxStack(id));
				counts[i] = add;
				left -= add;
			}
		}
		if (left < n) {
			floater("+" + (n - left) + " " + Items.name(id), id);
			arcade.audio.play("pickup", 0.7f, 1);
		}
		if (left > 0) {
			floater("Inventory full", 0);
		}
		onInventoryChange.run();
	}

	void take(int slot, int n) {
		counts[slot] -= n;
		if (counts[slot] <= 0) {
			counts[slot] = 0;
			ids[slot] = 0;
		}
		onInventoryChange.run();
	}

	int count(int id) {
		int total = 0;
		for (int i = 0; i < SLOTS; i++) {
			if (ids[i] == id) {
				total += counts[i];
			}
		}
		return total;
	}

	void consume(int id, int n) {
		int left = n;
		for (int i = SLOTS - 1; i >= 0 && left > 0; i--) {
			if (ids[i] == id) {
				int used = Math.min(left, counts[i]);
				counts[i] -= used;
				left -= used;
				if (counts[i] == 0) {
					ids[i] = 0;
				}
			}
		}
		onInventoryChange.run();
	}

	boolean near(int block) {
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = -4; dy <= 3; dy++) {
				if (level.get((int) Math.floor(px) + dx, (int) Math.floor(py) + dy - 1) == block) {
					return true;
				}
			}
		}
		return false;
	}

	boolean stationReady(Items.Station station) {
		return switch (station) {
			case HAND -> true;
			case BENCH -> near(Items.BENCH) || near(Items.ANVIL);
			case ANVIL -> near(Items.ANVIL);
		};
	}

	boolean canCraft(Items.Recipe recipe) {
		if (!stationReady(recipe.station())) {
			return false;
		}
		for (Items.Stack need : recipe.needs()) {
			if (count(need.id()) < need.count()) {
				return false;
			}
		}
		return true;
	}

	boolean craft(Items.Recipe recipe) {
		if (!canCraft(recipe)) {
			return false;
		}
		for (Items.Stack need : recipe.needs()) {
			consume(need.id(), need.count());
		}
		give(recipe.result().id(), recipe.result().count());
		arcade.audio.play("craft");
		return true;
	}

	List<Trade> shop = List.of();
	String shopTitle = "";
	String shopQuote = "";

	private static final String[] LINES = {
			"They say a hooded trader wanders the caves, selling maps.",
			"Bandits camp out in the Ashlands. Keep your blade close.",
			"The deeper you dig, the stranger the ore gets.",
			"Something old sleeps beneath the crypt. Don't wake it.",
			"Grandpa swears the moon used to set. Nobody believes him.",
			"Touch a thornstalk once. You won't do it twice.",
			"Forge iron at an anvil - a workbench won't do.",
			"Bring me gel and I'll brew you something that heals.",
			"The frost wisps aren't mean. Just... cold.",
			"Heard the Furnace Golem's heart still burns after it falls.",
			"Lock your door at night. Well. If we had nights.",
			"Hearts of crystal - eat one, live a little longer. True story."};
	private final Map<Integer, double[]> speech = new HashMap<>();

	private void speak(Mob m) {
		int line = (int) Math.floorMod(m.id * 31L + (long) (t / 6), LINES.length);
		speech.put(m.id, new double[] {line, 4.5});
		arcade.audio.play("talk", 0.8f, 0.9 + random.nextDouble() * 0.3);
		m.facing = px > m.x ? 1 : -1;
	}

	List<Trade> villageTrades() {
		return List.of(
				new Trade(Items.TORCH, 10, new int[] {Items.COAL, 2}, null),
				new Trade(Items.ARROW, 20, new int[] {Items.IRON, 2}, null),
				new Trade(Items.HEAL_POTION, 1, new int[] {Items.GEL, 4}, null),
				new Trade(Items.IRON, 3, new int[] {Items.LOG, 12}, null),
				new Trade(Items.RUBY, 1, new int[] {Items.IRON, 6}, null),
				new Trade(Items.HEART_CRYSTAL, 1, new int[] {Items.RUBY, 8}, null));
	}

	/** What the map trader sells. {@code cost}: id, count pairs; {@code locked}: why it isn't for sale yet. */
	record Trade(int result, int count, int[] cost, String locked) {
	}

	List<Trade> trades() {
		return List.of(
				new Trade(Items.MAP_CRYPT, 1, new int[] {Items.IRON, 5, Items.COAL, 4}, null),
				new Trade(Items.MAP_FOUNDRY, 1, new int[] {Items.RUBY, 6}, world.cleared[0] ? null : "After the Crypt Warden"),
				new Trade(Items.MAP_SPIRE, 1, new int[] {Items.BLOODSTONE, 6}, world.cleared[1] ? null : "After the Furnace Golem"),
				new Trade(Items.HEAL_POTION, 2, new int[] {Items.IRON, 2}, null),
				new Trade(Items.SWIFT_ELIXIR, 1, new int[] {Items.RUBY, 2}, null),
				new Trade(Items.ARROW, 20, new int[] {Items.COAL, 3}, null));
	}

	boolean canBuy(Trade trade) {
		if (trade.locked() != null) {
			return false;
		}
		for (int i = 0; i + 1 < trade.cost().length; i += 2) {
			if (count(trade.cost()[i]) < trade.cost()[i + 1]) {
				return false;
			}
		}
		return true;
	}

	boolean buy(Trade trade) {
		if (!canBuy(trade)) {
			return false;
		}
		for (int i = 0; i + 1 < trade.cost().length; i += 2) {
			consume(trade.cost()[i], trade.cost()[i + 1]);
		}
		give(trade.result(), trade.count());
		arcade.audio.play("trade");
		return true;
	}

	void select(int slot) {
		selected = Math.floorMod(slot, 9);
		selectedShown = 2.2;
		arcade.audio.play("click", 0.5f, 1.2);
	}

	private void floater(String text, int icon) {
		floater(text, icon, screenX(px), screenY(py - TALL - 0.3));
	}

	private void floater(String text, int icon, double x, double y) {
		for (Floater f : floaters) {
			if (f.text().equals(text) && f.state()[2] < 0.5) {
				return;
			}
		}
		floaters.add(new Floater(text, icon, new double[] {x, y - floaters.size() % 3 * 14, 0}));
	}

	void say(String text) {
		announcement = text;
		announceTimer = 3.2;
	}

	// ------------------------------------------------------------------ tiles (synced)

	private void setTile(Level l, int x, int y, int id) {
		l.set(x, y, id);
		liquids(l).touch(l, x, y);
		if (coop != null && coop.alive() && (coop.isHost() || joined)) {
			JsonObject m = new JsonObject();
			m.addProperty("t", "set");
			m.addProperty("l", l.id());
			m.addProperty("x", x);
			m.addProperty("y", y);
			m.addProperty("id", id);
			coop.send(m);
		}
	}

	// ------------------------------------------------------------------ input

	@Override
	public void key(KeyCode code, boolean down) {
		switch (code) {
			case A, LEFT -> leftKey = down;
			case D, RIGHT -> rightKey = down;
			case S, DOWN -> downKey = down;
			case W, UP, SPACE -> {
				if (down && !jumpHeld) {
					jumpBuffer = 0.13;
				}
				jumpHeld = down;
			}
			default -> {
				if (down && code.isDigitKey()) {
					String name = code.getName();
					int n = name.charAt(name.length() - 1) - '1';
					if (n >= 0 && n < 9) {
						select(n);
					}
				}
			}
		}
		if (down) {
			frontTarget = 0;
		}
	}

	@Override
	public void mouse(MouseEvent event) {
		mouseX = event.getX();
		mouseY = event.getY();
		if (event.getEventType() == MouseEvent.MOUSE_PRESSED) {
			if (event.getButton() == MouseButton.PRIMARY) {
				mouseLeft = true;
			} else if (event.getButton() == MouseButton.SECONDARY) {
				mouseRight = true;
				useCooldown = 0;
			}
		} else if (event.getEventType() == MouseEvent.MOUSE_RELEASED) {
			if (event.getButton() == MouseButton.PRIMARY) {
				mouseLeft = false;
			} else if (event.getButton() == MouseButton.SECONDARY) {
				mouseRight = false;
			}
		}
	}

	void releaseAll() {
		mouseLeft = false;
		mouseRight = false;
		leftKey = false;
		rightKey = false;
		downKey = false;
		jumpHeld = false;
	}

	@Override
	public void scroll(ScrollEvent event) {
		if (event.getDeltaY() != 0) {
			select(selected + (event.getDeltaY() < 0 ? 1 : -1));
		}
	}

	@Override
	public boolean restful() {
		if (keepsRunning()) {
			return false;
		}
		Sim sim = sims.get(level.id());
		boolean danger = sim != null && sim.mobs.stream().anyMatch(m -> !m.friendly() && Math.abs(m.x - px) < 12);
		return onGround && Math.abs(vx) < 0.1 && !danger && deadTimer <= 0 && !mouseLeft && !mouseRight;
	}

	@Override
	public void rest() {
		frontTarget = 1;
		releaseAll();
	}

	// ------------------------------------------------------------------ party co-op

	private void coopTick(double dt) {
		var party = net.veloclient.launcher.social.LauncherSocial.party();
		String token = net.veloclient.launcher.social.LauncherSocial.sessionToken();
		boolean inParty = party != null && party.has("members") && party.getAsJsonArray("members").size() > 1 && token != null && !Arcade.TOUR;
		if (!inParty) {
			if (coop != null) {
				leaveCoop("The party ended");
			}
			return;
		}
		boolean leader = net.veloclient.launcher.social.LauncherSocial.isPartyLeader();
		if (coop != null && (!coop.alive() || coop.isHost() != leader)) {
			leaveCoop(coop.isHost() ? "Hosting stopped" : "Lost the connection to the host");
		}
		coopRetry -= dt;
		if (coop == null && !coopConnecting && coopRetry <= 0) {
			coopConnecting = true;
			coopRetry = leader ? 10 : 5;
			Thread.ofVirtual().name("velo-arcade-connect").start(() -> {
				try {
					Coop link = leader ? Coop.host(token) : Coop.join(token);
					javafx.application.Platform.runLater(() -> {
						coopConnecting = false;
						coop = link;
						if (link.isHost()) {
							coopStatus = "Hosting for your party";
						} else {
							JsonObject hello = new JsonObject();
							hello.addProperty("t", "hello");
							link.send(hello);
							coopStatus = "Joining the leader's world...";
						}
					});
				} catch (Exception e) {
					javafx.application.Platform.runLater(() -> {
						coopConnecting = false;
						if (!leader) {
							coopStatus = "Waiting for the party leader to play";
						}
					});
				}
			});
		}
		if (coop == null) {
			return;
		}
		for (JsonObject m : coop.drain()) {
			try {
				onMessage(m);
			} catch (Exception ignored) {
				// a bad message: skip it
			}
		}
		netTimer -= dt;
		if (netTimer <= 0) {
			netTimer = 1 / 12.0;
			sendState();
		}
	}

	/** The arcade stops (launch, page change): drop the party link. */
	void stopCoop() {
		if (coop != null) {
			leaveCoop("Stopped playing");
		}
	}

	private void leaveCoop(String why) {
		if (coop != null) {
			coop.close();
		}
		coop = null;
		remotes.clear();
		coopStatus = "";
		if (joined) {
			joined = false;
			if (ownWorld != null) {
				world = ownWorld;
				System.arraycopy(ownDungeons, 0, dungeons, 0, 3);
			}
			ownWorld = null;
			ownDungeons = null;
			sims.clear();
			pickups.clear();
			level = world;
			respawn();
			camReady = false;
			say(why + " - back in your own world");
		}
	}

	private void onMessage(JsonObject m) {
		String type = m.get("t").getAsString();
		String from = m.has("_from") ? m.get("_from").getAsString() : "";
		switch (type) {
			case "_join" -> {
				Remote r = remotes.computeIfAbsent(from, k -> new Remote());
				r.name = m.get("name").getAsString();
				say(r.name + " joined your world");
				arcade.audio.play("level");
				coop.sendTo(from, welcome());
			}
			case "_leave" -> {
				Remote r = remotes.remove(from);
				if (r != null) {
					say(r.name + " left");
				}
			}
			case "hello" -> {
				if (coop.isHost()) {
					coop.sendTo(from, welcome());
				}
			}
			case "welcome" -> receiveWelcome(m);
			case "rules" -> {
				friendlyFire = m.get("ff").getAsBoolean();
				say("Friendly fire " + (friendlyFire ? "on" : "off"));
			}
			case "set" -> {
				Level l = levelById(m.get("l").getAsString());
				l.set(m.get("x").getAsInt(), m.get("y").getAsInt(), m.get("id").getAsInt());
				if (coop.isHost()) {
					coop.sendExcept(from, m);
				}
			}
			case "me" -> {
				Remote r = remotes.computeIfAbsent(from, k -> new Remote());
				m.addProperty("uuid", from);
				readRemote(r, m);
			}
			case "players" -> {
				java.util.Set<String> seen = new java.util.HashSet<>();
				String mine = String.valueOf(net.veloclient.launcher.social.LauncherSocial.myUuid());
				for (var el : m.getAsJsonArray("p")) {
					JsonObject p = el.getAsJsonObject();
					String uuid = p.get("uuid").getAsString();
					if (uuid.equals(mine)) {
						continue;
					}
					seen.add(uuid);
					Remote r = remotes.computeIfAbsent(uuid, k -> new Remote());
					if (p.has("name")) {
						r.name = p.get("name").getAsString();
					}
					readRemote(r, p);
				}
				remotes.keySet().retainAll(seen);
			}
			case "sim" -> {
				if (!m.get("l").getAsString().equals(level.id())) {
					return;
				}
				Sim sim = sim(level);
				java.util.Set<Integer> alive = new java.util.HashSet<>();
				for (var el : m.getAsJsonArray("m")) {
					JsonArray a = el.getAsJsonArray();
					int id = a.get(0).getAsInt();
					alive.add(id);
					Mob mob = sim.find(id);
					if (mob == null) {
						mob = new Mob(id, a.get(1).getAsInt(), a.get(2).getAsDouble(), a.get(3).getAsDouble());
						sim.mobs.add(mob);
					}
					mob.nx = a.get(2).getAsDouble();
					mob.ny = a.get(3).getAsDouble();
					mob.hp = a.get(4).getAsInt();
					mob.maxHp = a.get(5).getAsInt();
					mob.facing = a.get(6).getAsInt();
					mob.phase = a.get(7).getAsInt();
					mob.move = a.get(8).getAsInt();
					mob.moveT = a.get(9).getAsDouble();
					mob.tele = a.get(10).getAsDouble();
					mob.onGround = a.get(11).getAsInt() == 1;
					if (a.get(12).getAsInt() == 1) {
						mob.hurt = 0.18;
					}
					if (a.size() > 13) {
						mob.vent = a.get(13).getAsDouble();
					}
					if (mob.boss()) {
						sim.boss = mob;
					}
				}
				sim.mobs.removeIf(x -> !alive.contains(x.id));
				if (sim.boss != null && !alive.contains(sim.boss.id)) {
					sim.boss = null;
				}
			}
			case "proj" -> {
				if (!m.get("l").getAsString().equals(level.id())) {
					return;
				}
				JsonArray a = m.getAsJsonArray("p");
				Proj p = new Proj(a.get(0).getAsInt(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble(),
						a.get(4).getAsDouble(), a.get(5).getAsInt(), a.get(6).getAsInt() == 1);
				p.homing = a.get(7).getAsInt() == 1;
				p.gravity = a.get(8).getAsDouble();
				p.life = a.get(9).getAsDouble();
				sim(level).projectiles.add(p);
			}
			case "hit" -> {
				Sim sim = sims.get(m.get("l").getAsString());
				Mob mob = sim == null ? null : sim.find(m.get("id").getAsInt());
				if (mob != null) {
					sim.hit(mob, m.get("dmg").getAsInt(), m.get("dir").getAsDouble(), from);
				}
			}
			case "shoot" -> {
				JsonArray a = m.getAsJsonArray("p");
				Sim sim = sim(levelById(m.get("l").getAsString()));
				sim.fire(new Proj(Actors.PLAYER_ARROW, a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
						a.get(3).getAsDouble(), a.get(4).getAsInt(), false));
			}
			case "pvp" -> {
				String to = m.get("to").getAsString();
				String mine = String.valueOf(net.veloclient.launcher.social.LauncherSocial.myUuid());
				if (!friendlyFire) {
					return;
				}
				if (!coop.isHost() || to.equals(mine)) {
					hurt(m.get("dmg").getAsInt(), m.get("x").getAsDouble());
				} else {
					coop.sendTo(to, m);
				}
			}
			case "loot" -> {
				JsonArray a = m.getAsJsonArray("items");
				for (int i = 0; i + 1 < a.size(); i += 2) {
					give(a.get(i).getAsInt(), a.get(i + 1).getAsInt());
				}
			}
			case "boss_dead" -> {
				int index = m.get("d").getAsInt();
				Dungeon d = dungeon(index);
				bossDefeatedHere(index, d.bossX, d.bossY - 2);
			}
			case "say" -> say(m.get("text").getAsString());
			case "drop" -> {
				Pickup p = new Pickup();
				p.pid = m.get("pid").getAsLong();
				p.level = m.get("l").getAsString();
				p.id = m.get("id").getAsInt();
				p.count = m.get("n").getAsInt();
				p.x = m.get("x").getAsDouble();
				p.y = m.get("y").getAsDouble();
				p.vx = m.get("vx").getAsDouble();
				p.vy = m.get("vy").getAsDouble();
				p.wait = 0.4;
				if (Items.known(p.id)) {
					pickups.add(p);
				}
				if (coop.isHost()) {
					coop.sendExcept(from, m);
				}
			}
			case "took" -> {
				long pid = m.get("pid").getAsLong();
				pickups.removeIf(p -> p.pid == pid);
				if (coop.isHost()) {
					coop.sendExcept(from, m);
				}
			}
			default -> {
			}
		}
	}

	private void readRemote(Remote r, JsonObject p) {
		r.level = p.get("l").getAsString();
		r.nx = p.get("x").getAsDouble();
		r.ny = p.get("y").getAsDouble();
		if (Math.abs(r.nx - r.x) > 6 || Math.abs(r.ny - r.y) > 6) {
			r.x = r.nx;
			r.y = r.ny;
		}
		r.facing = p.get("f").getAsInt();
		r.walk = p.get("w").getAsDouble();
		double swing = p.get("s").getAsDouble();
		if (swing < r.swing) {
			r.swing = swing;
		}
		r.held = p.get("h").getAsInt();
		r.armor = p.get("a").getAsInt();
		r.dead = p.get("d").getAsInt() == 1;
		r.front = p.get("fr").getAsDouble();
		if (!r.skinAsked && p.has("uuid")) {
			r.skinAsked = true;
			String uuid = p.get("uuid").getAsString();
			Thread.ofVirtual().start(() -> {
				byte[][] skin = RemoteSkins.fetch(uuid);
				if (skin != null) {
					javafx.application.Platform.runLater(() -> r.rig = SkinRig.from(skin[0], skin[1][0] == 1));
				}
			});
		}
	}

	private JsonObject me() {
		JsonObject p = new JsonObject();
		p.addProperty("uuid", String.valueOf(net.veloclient.launcher.social.LauncherSocial.myUuid()));
		p.addProperty("name", RemoteSkins.myName());
		p.addProperty("l", level.id());
		p.addProperty("x", round(px));
		p.addProperty("y", round(py));
		p.addProperty("f", facing);
		p.addProperty("w", round(walkPhase * walkAmount));
		p.addProperty("s", round(swing));
		p.addProperty("h", ids[selected]);
		p.addProperty("a", armor);
		p.addProperty("d", deadTimer > 0 ? 1 : 0);
		p.addProperty("fr", round(front));
		return p;
	}

	private void sendState() {
		if (!coop.isHost()) {
			if (joined) {
				JsonObject m = me();
				m.addProperty("t", "me");
				coop.send(m);
			}
			return;
		}
		if (coop.peerCount() == 0) {
			return;
		}
		JsonObject players = new JsonObject();
		players.addProperty("t", "players");
		JsonArray list = new JsonArray();
		list.add(me());
		for (Map.Entry<String, Remote> e : remotes.entrySet()) {
			Remote r = e.getValue();
			JsonObject p = new JsonObject();
			p.addProperty("uuid", e.getKey());
			p.addProperty("name", r.name);
			p.addProperty("l", r.level);
			p.addProperty("x", round(r.nx));
			p.addProperty("y", round(r.ny));
			p.addProperty("f", r.facing);
			p.addProperty("w", round(r.walk));
			p.addProperty("s", round(r.swing));
			p.addProperty("h", r.held);
			p.addProperty("a", r.armor);
			p.addProperty("d", r.dead ? 1 : 0);
			p.addProperty("fr", round(r.front));
			list.add(p);
		}
		players.add("p", list);
		coop.send(players);
		java.util.Set<String> levels = new java.util.HashSet<>();
		for (Remote r : remotes.values()) {
			levels.add(r.level);
		}
		for (String l : levels) {
			Sim sim = sims.get(l);
			if (sim == null) {
				continue;
			}
			JsonObject snap = new JsonObject();
			snap.addProperty("t", "sim");
			snap.addProperty("l", l);
			JsonArray mobs = new JsonArray();
			for (Mob mob : sim.mobs) {
				JsonArray a = new JsonArray();
				a.add(mob.id);
				a.add(mob.kind);
				a.add(round(mob.x));
				a.add(round(mob.y));
				a.add(mob.hp);
				a.add(mob.maxHp);
				a.add(mob.facing);
				a.add(mob.phase);
				a.add(mob.move);
				a.add(round(mob.moveT));
				a.add(round(mob.tele));
				a.add(mob.onGround ? 1 : 0);
				a.add(mob.hurt > 0 ? 1 : 0);
				a.add(round(mob.vent));
				mobs.add(a);
			}
			snap.add("m", mobs);
			coop.send(snap);
		}
	}

	private JsonObject welcome() {
		JsonObject m = new JsonObject();
		m.addProperty("t", "welcome");
		m.addProperty("seed", world.seed);
		m.addProperty("ff", friendlyFire);
		JsonArray cleared = new JsonArray();
		for (boolean c : world.cleared) {
			cleared.add(c ? 1 : 0);
		}
		m.add("cleared", cleared);
		m.add("world", edits(world));
		JsonArray ds = new JsonArray();
		for (int i = 0; i < 3; i++) {
			JsonObject d = new JsonObject();
			if (dungeons[i] != null) {
				d.add("edits", edits(dungeons[i]));
				d.addProperty("boss", dungeons[i].bossDefeated);
			}
			ds.add(d);
		}
		m.add("dungeons", ds);
		m.addProperty("x", round(px));
		m.addProperty("y", round(py));
		m.addProperty("l", level.id());
		return m;
	}

	private void receiveWelcome(JsonObject m) {
		if (!joined) {
			ownWorld = world;
			ownDungeons = dungeons.clone();
		}
		joined = true;
		friendlyFire = m.has("ff") && m.get("ff").getAsBoolean();
		World w = new World(m.get("seed").getAsLong());
		JsonArray cleared = m.getAsJsonArray("cleared");
		for (int i = 0; i < 3; i++) {
			w.cleared[i] = cleared.get(i).getAsInt() == 1;
		}
		readEdits(w, m.getAsJsonArray("world"));
		world = w;
		java.util.Arrays.fill(dungeons, null);
		JsonArray ds = m.getAsJsonArray("dungeons");
		for (int i = 0; i < 3; i++) {
			JsonObject d = ds.get(i).getAsJsonObject();
			if (d.has("edits")) {
				Dungeon dg = dungeon(i);
				readEdits(dg, d.getAsJsonArray("edits"));
				dg.applyEdits();
				dg.bossDefeated = d.get("boss").getAsBoolean();
			}
		}
		sims.clear();
		pickups.clear();
		level = levelById(m.get("l").getAsString());
		px = m.get("x").getAsDouble() + 1;
		py = m.get("y").getAsDouble();
		if (blocked(px, py)) {
			px -= 1;
		}
		camReady = false;
		coopStatus = "In the party leader's world";
		say("Joined the party's world");
		arcade.audio.play("level");
	}

	private static JsonArray edits(Level l) {
		JsonArray a = new JsonArray();
		for (Map.Entry<Long, Integer> e : l.edits.entrySet()) {
			a.add(Level.keyX(e.getKey()));
			a.add(Level.keyY(e.getKey()));
			a.add(e.getValue());
		}
		return a;
	}

	private static void readEdits(Level l, JsonArray a) {
		if (a == null) {
			return;
		}
		for (int i = 0; i + 2 < a.size(); i += 3) {
			l.edits.put(Level.key(a.get(i).getAsInt(), a.get(i + 1).getAsInt()), a.get(i + 2).getAsInt());
		}
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	static Color argbColor(int argb) {
		return Color.rgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, ((argb >>> 24) & 0xFF) / 255.0);
	}

	String coopStatus() {
		return coopStatus;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(GraphicsContext g, double w, double h, double time) {
		viewW = w;
		viewH = h;
		scale = h > 1000 ? 3 : 2;
		block = 16 * scale;
		if (!camReady) {
			camX = px + 1.2;
			camY = py - 1.6;
			camReady = true;
			clampCamera();
		}
		if (level instanceof Dungeon d) {
			dungeonBackdrop(g, w, h, d, time);
		} else {
			arcade.backdrop.draw(g, w, h, camX * block, (camY - 40) * block, time);
		}
		int x0 = (int) Math.floor(worldX(0)) - 1;
		int x1 = (int) Math.floor(worldX(w)) + 1;
		int y0 = Math.max(0, (int) Math.floor(worldY(0)) - 1);
		int y1 = Math.min(level.height() - 1, (int) Math.floor(worldY(h)) + 1);
		Sim sim = sims.get(level.id());

		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				double sx = screenX(x);
				double sy = screenY(y);
				int id = level.get(x, y);
				int wall = level.wallAt(x, y);
				if (wall != 0 && !Items.solid(id)) {
					g.drawImage(Items.wall(wall), sx, sy, block, block);
					edgeShade(g, x, y, sx, sy, id);
				}
				if (id == Items.AIR) {
					int decor = level.decorAt(x, y);
					if (decor >= 0) {
						g.drawImage(Items.decor(decor), sx, sy, block, block);
					}
					continue;
				}
				if (id == Items.GATE) {
					continue;
				}
				double shake = sim != null && id == Items.CRUMBLE && sim.crumbling(x, y) ? (random.nextDouble() - 0.5) * 3 : 0;
				double bob = id == Items.KEY_FRAG ? Math.sin(time * 3 + x) * 3 : 0;
				if (Liquids.liquid(id)) {
					// a liquid shows as full as it is (full under more of itself), water a little see-through
					Liquids lq = liquids(level);
					float amount = level.get(x, y - 1) == id ? 1f : lq.amount(level, x, y);
					double hgt = Math.max(2, amount * block);
					if (id == Items.WATER) {
						g.setGlobalAlpha(0.78);
					}
					javafx.scene.image.Image img = Items.tile(id, x, y);
					double srcTop = img.getHeight() * (1 - amount);
					g.drawImage(img, 0, srcTop, img.getWidth(), img.getHeight() - srcTop, sx, sy + block - hgt, block, hgt);
					g.setGlobalAlpha(1);
					continue;
				}
				g.drawImage(Items.tile(id, x, y), sx + shake, sy + bob, block, block);
				if (Items.solid(id)) {
					edgeShade(g, x, y, sx, sy, id);
				}
				if (id == Items.RUNE || id == Items.RUNE_LIT) {
					drawRuneSymbol(g, x, y, sx, sy, id == Items.RUNE_LIT);
				} else if (id >= Items.GLYPH && id <= Items.GLYPH_LAST) {
					glyph(g, id - Items.GLYPH, sx + block / 2, sy + block * 0.6, block * 0.4, Color.web("#E8DEC8"));
				} else if (id == Items.BRAZIER_LIT) {
					for (int k = 0; k < 5; k++) {
						double fl = Math.abs(Math.sin(time * 9 + k * 1.7 + x));
						g.setFill(k % 2 == 0 ? Color.web("#FF8A2E") : Color.web("#FFE08A"));
						g.fillRect(sx + (4 + k * 2) * scale, sy + (6 - 2 - fl * 3) * scale, scale * 2, (2 + fl * 3) * scale);
					}
				} else if (id == Items.PLATE && level.get(x, y - 1) == Items.CRATE) {
					g.setFill(Color.rgb(0, 0, 0, 0.35));
					g.fillRect(sx + 2 * scale, sy, block - 4 * scale, 2 * scale);
				}
			}
		}
		if (level == world) {
			drawCanopies(g, x0, x1, time);
		}
		drawGates(g, x0, x1, y0, y1, time);
		if (level instanceof Dungeon d) {
			if (d.riddle == Dungeon.Riddle.RUNES) {
				drawPlaque(g, d);
			} else if (d.riddle == Dungeon.Riddle.GLYPHS) {
				for (int i = 0; i < 4; i++) {
					glyph(g, d.glyphTarget[i], screenX(d.targetX + i * 1.2), screenY(d.targetY + 0.5), block * 0.4, Color.rgb(201, 185, 182, 0.28));
				}
			}
		}
		if (mineProgress > 0 && mineX != Integer.MIN_VALUE) {
			g.drawImage(Items.crack((int) (mineProgress * 4)), screenX(mineX), screenY(mineY), block, block);
		}
		if (arcade.playing()) {
			double mx = worldX(mouseX);
			double my = worldY(mouseY);
			if (Math.hypot(mx - px, my - (py - 1.2)) < REACH) {
				g.setStroke(Color.rgb(255, 240, 235, 0.35));
				g.setLineWidth(1.5);
				g.strokeRect(screenX(Math.floor(mx)) + 0.75, screenY(Math.floor(my)) + 0.75, block - 1.5, block - 1.5);
			}
		}
		if (sim != null) {
			for (Mob m : sim.mobs) {
				if (m.kind == Actors.GOLEM && m.move == 4 && m.moveT < 0.9) {
					int n = m.phase > 1 ? 5 : 3;
					for (int i = 0; i < n; i++) {
						double ex = m.nx + (i - (n - 1) / 2.0) * 2.6;
						double pulse = 0.4 + 0.6 * Math.abs(Math.sin(time * 14));
						Sprites.glow(g, screenX(ex), screenY(m.y), block * 1.2, Color.rgb(255, 120, 40, 0.5 * pulse));
					}
				}
				if (m.kind == Actors.HERALD && sim.crystalsAlive() > 0) {
					double cx = screenX(m.x);
					double cy = screenY(m.y - m.tall() / 2);
					for (Mob c : sim.mobs) {
						if (c.kind == Actors.VOID_CRYSTAL) {
							g.setStroke(Color.rgb(180, 130, 255, 0.35 + 0.2 * Math.sin(time * 8 + c.id)));
							g.setLineWidth(scale * 1.5);
							g.strokeLine(screenX(c.x), screenY(c.y - 0.7), cx, cy);
						}
					}
					g.setStroke(Color.rgb(200, 160, 255, 0.5));
					g.setLineWidth(scale);
					double r = block * 2.2 + Math.sin(time * 3) * 3;
					g.strokeOval(cx - r, cy - r * 1.2, r * 2, r * 2.4);
					Sprites.glow(g, cx, cy, r * 1.2, Color.rgb(140, 90, 255, 0.12));
				}
				Sprites.mob(g, m, screenX(m.x), screenY(m.y), scale, time);
				if (m.kind == Actors.WARDEN && m.move == 12) {
					for (int k = 0; k < 3; k++) {
						double a = time * 5 + k * 2.1;
						g.setFill(Color.web("#FFE08A"));
						g.fillRect(screenX(m.x + Math.cos(a) * 0.9), screenY(m.y - m.tall() - 0.3 + Math.sin(a) * 0.2), scale * 2, scale * 2);
					}
				}
				if (!m.boss() && !m.friendly() && m.hp < m.maxHp) {
					double bw = m.halfW() * 2 * block;
					g.setFill(Color.rgb(0, 0, 0, 0.5));
					g.fillRect(screenX(m.x) - bw / 2, screenY(m.y - m.tall()) - 8, bw, 3);
					g.setFill(Color.web("#E0333A"));
					g.fillRect(screenX(m.x) - bw / 2, screenY(m.y - m.tall()) - 8, bw * Math.max(0, m.hp) / m.maxHp, 3);
				}
			}
		}
		if (sim != null) {
			for (Mob m : sim.mobs) {
				double[] sp = speech.get(m.id);
				if (sp != null) {
					drawBubble(g, LINES[(int) sp[0]], screenX(m.x), screenY(m.y - m.tall() - 0.6), Math.min(1, sp[1] * 2));
				}
			}
		}
		for (Map.Entry<String, Remote> e : remotes.entrySet()) {
			Remote r = e.getValue();
			if (r.level.equals(level.id()) && !r.dead) {
				if (r.hurt > 0) {
					g.setGlobalAlpha(0.5);
				}
				drawPlayerAt(g, r.rig, screenX(r.x), screenY(r.y), r.facing, Math.sin(r.walk * Math.PI) * 38 * Math.min(1, Math.abs(r.nx - r.x) * 8 + 0.2), r.swing,
						r.held, r.armor, r.front, 1, false);
				g.setGlobalAlpha(1);
				arcade.label(g, r.name, screenX(r.x), screenY(r.y - TALL - 0.5), 11, Color.web(friendlyFire ? "#FF8A8F" : "#F4EFEE"), true, true);
			}
		}
		drawPickups(g, time);
		if (deadTimer <= 0) {
			drawPlayer(g);
		}
		if (sim != null) {
			for (Proj p : sim.projectiles) {
				Sprites.projectile(g, p, screenX(p.x), screenY(p.y), block, time);
			}
		}
		drawMotes(g, false);
		drawFog(g, time, false);
		drawDarkness(g, x0, x1, y0, y1, time);
		drawFog(g, time, true);
		drawMotes(g, true);
		drawRays(g, time);
		if (!arcade.playing()) {
			return;
		}
		if (deadTimer > 0) {
			g.setFill(Color.rgb(20, 4, 6, 0.55));
			g.fillRect(0, 0, w, h);
			arcade.label(g, "YOU FELL", w / 2, h / 2 - 8, 26, Color.web("#F4EFEE"), true, true);
			arcade.label(g, "back on your feet in a moment", w / 2, h / 2 + 22, 12, Color.web("#C9B9B6"), true, true);
		}
		drawHud(g, w, h, sim);
		for (Floater f : floaters) {
			double age = f.state()[2];
			double alpha = Math.max(0, Math.min(1, (1.4 - age) * 3));
			double fx = f.state()[0];
			double fy = f.state()[1] - age * 26;
			if (f.icon() != 0) {
				g.setGlobalAlpha(alpha);
				g.drawImage(Items.icon(f.icon()), fx - 34, fy - 13, 16, 16);
				g.setGlobalAlpha(1);
			}
			arcade.label(g, f.text(), fx + (f.icon() != 0 ? 8 : 0), fy, 12, Color.web("#F4EFEE", alpha), true, true);
		}
	}

	private void drawBubble(GraphicsContext g, String text, double x, double y, double alpha) {
		g.setFont(javafx.scene.text.Font.font("Monocraft", 11));
		javafx.scene.text.Text probe = new javafx.scene.text.Text(text);
		probe.setFont(g.getFont());
		double w = Math.min(320, probe.getLayoutBounds().getWidth()) + 18;
		double h = 24;
		double tip = x;
		x = Math.max(w / 2 + 6, Math.min(viewW - w / 2 - 6, x)); // keep the bubble on screen, the tail on the speaker
		g.setGlobalAlpha(alpha);
		g.setFill(Color.rgb(16, 11, 12, 0.92));
		g.fillRoundRect(x - w / 2, y - h, w, h, 8, 8);
		g.setStroke(Color.rgb(255, 236, 236, 0.25));
		g.setLineWidth(1);
		g.strokeRoundRect(x - w / 2 + 0.5, y - h + 0.5, w - 1, h - 1, 8, 8);
		g.setFill(Color.rgb(16, 11, 12, 0.92));
		g.fillPolygon(new double[] {tip - 5, tip + 5, tip}, new double[] {y, y, y + 6}, 3);
		g.setGlobalAlpha(1);
		arcade.label(g, text, x, y - 8, 11, Color.web("#F4EFEE", alpha), true, true);
	}

	private void dungeonBackdrop(GraphicsContext g, double w, double h, Dungeon d, double time) {
		Color dark = argbColor(d.theme.dark);
		Color glow = argbColor(d.theme.glow);
		g.setFill(new LinearGradient(0, 0, 0, h, false, CycleMethod.NO_CYCLE, new Stop(0, Color.BLACK), new Stop(0.6, dark),
				new Stop(1, Backdrop.mix(dark, glow, 0.35))));
		g.fillRect(0, 0, w, h);
		for (int i = 0; i < 9; i++) {
			double px0 = Math.floorMod((long) (i * 211 - camX * block * 0.25), (long) (w + 200)) - 100;
			double width = 26 + (i % 3) * 14;
			g.setFill(Backdrop.mix(dark, glow, 0.12 + (i % 2) * 0.06));
			g.fillRect(px0, 0, width, h);
			g.setFill(Backdrop.mix(dark, glow, 0.2));
			g.fillRect(px0, h * 0.2 + i * 7, width, 6);
		}
		for (int i = 0; i < 14; i++) {
			double ox = Math.floorMod((long) (i * 97 - camX * block * 0.1 + time * 6), (long) Math.max(1, w));
			double oy = (i * 53 + time * (8 + i)) % h;
			Sprites.glow(g, ox, h - oy, 10, glow.deriveColor(0, 1, 1.6, 0.35));
		}
	}

	private void drawCanopies(GraphicsContext g, int x0, int x1, double time) {
		for (int x = x0 - 3; x <= x1 + 3; x++) {
			if (!world.isTree(x)) {
				continue;
			}
			int top = world.treeTop(x);
			if (world.get(x, top) != Items.TRUNK) {
				continue;
			}
			double cw = Items.CANOPY_W * scale;
			double ch = Items.CANOPY_H * scale;
			double sway = Math.round(Math.sin(time * 0.9 + x) * 0.6) * scale;
			g.drawImage(Items.canopy(world.biome(x), Math.floorMod(x, 3)), screenX(x + 0.5) - cw / 2 + sway, screenY(top + 0.9) - ch, cw, ch);
		}
	}

	private void drawGates(GraphicsContext g, int x0, int x1, int y0, int y1, double time) {
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				if (level.get(x, y) != Items.GATE) {
					continue;
				}
				double cx = screenX(x + 0.5);
				double cy = screenY(y - 0.5);
				Color tint = level instanceof Dungeon d ? argbColor(d.theme.glow).deriveColor(0, 1, 2.2, 1) : gateTint(x);
				Sprites.glow(g, cx, cy, block * 2.6, tint.deriveColor(0, 1, 1, 0.45));
				g.save();
				g.translate(cx, cy);
				for (int ring = 0; ring < 3; ring++) {
					g.rotate(time * (40 + ring * 25) * (ring % 2 == 0 ? 1 : -1));
					g.setStroke(tint.deriveColor(0, 1, 1.2, 0.8 - ring * 0.2));
					g.setLineWidth(scale * 2);
					double r = block * (1.1 - ring * 0.28);
					g.strokeArc(-r, -r * 1.3, r * 2, r * 2.6, ring * 40, 250, javafx.scene.shape.ArcType.OPEN);
				}
				g.restore();
				g.setFill(Color.rgb(10, 4, 14, 0.85));
				g.fillOval(cx - block * 0.55, cy - block * 0.8, block * 1.1, block * 1.6);
				if (Math.hypot(x + 0.5 - px, y - py) < 3 && arcade.playing()) {
					arcade.label(g, level == world ? "Right click: enter" : "Right click: leave", cx, cy - block * 1.7, 11, Color.web("#F4EFEE"), true, true);
				}
			}
		}
	}

	private Color gateTint(int x) {
		for (int i = 0; i < 3; i++) {
			if (Math.abs(world.gateX(i) - x) <= 1) {
				return argbColor(Dungeon.Theme.values()[i].glow).deriveColor(0, 1, 2.6, 1);
			}
		}
		return Color.web("#D7262D");
	}

	private void drawRuneSymbol(GraphicsContext g, int x, int y, double sx, double sy, boolean lit) {
		if (!(level instanceof Dungeon d)) {
			return;
		}
		int index = -1;
		for (int i = 0; i < d.puzzle.size(); i++) {
			if (d.puzzle.get(i)[0] == x && d.puzzle.get(i)[1] == y) {
				index = i;
			}
		}
		if (index < 0) {
			return;
		}
		if (lit) {
			Sprites.glow(g, sx + block / 2, sy + block * 0.6, block * 1.2, Color.rgb(255, 150, 60, 0.5));
		}
		glyph(g, d.runeSymbol[index], sx + block / 2, sy + block * 0.62, block * 0.42, lit ? Color.web("#FFC27A") : Color.web("#9C8C89"));
	}

	/** The four rune glyphs: circle, triangle, square, cross. */
	private void glyph(GraphicsContext g, int symbol, double cx, double cy, double size, Color c) {
		g.setStroke(c);
		g.setLineWidth(Math.max(2, scale * 1.5));
		double r = size / 2;
		switch (symbol) {
			case 0 -> g.strokeOval(cx - r, cy - r, size, size);
			case 1 -> g.strokePolygon(new double[] {cx, cx + r, cx - r}, new double[] {cy - r, cy + r, cy + r}, 3);
			case 2 -> g.strokeRect(cx - r, cy - r, size, size);
			default -> {
				g.strokeLine(cx - r, cy - r, cx + r, cy + r);
				g.strokeLine(cx + r, cy - r, cx - r, cy + r);
			}
		}
	}

	private void drawPlaque(GraphicsContext g, Dungeon d) {
		double sx = screenX(d.plaqueX);
		double sy = screenY(d.plaqueY);
		double w = block * 5;
		double h = block * 1.6;
		// just four symbols scratched into the wall by the entrance - figuring out what they mean is the riddle
		for (int i = 0; i < 4; i++) {
			glyph(g, i, sx + block * (0.7 + i * 1.2), sy + h / 2, block * 0.4, Color.rgb(201, 185, 182, 0.28));
		}
	}

	private void drawDarkness(GraphicsContext g, int x0, int x1, int y0, int y1, double time) {
		int lw = x1 - x0 + 1;
		int lh = y1 - y0 + 1;
		if (lw <= 0 || lh <= 0) {
			return;
		}
		double[][] light = light(x0, y0, x1, y1);
		int darkTint = level instanceof Dungeon d ? (Items.shade(d.theme.dark, 0.55) & 0xFFFFFF) : 0x07060F;
		if (shadeImage == null || shadeImage.getWidth() != lw || shadeImage.getHeight() != lh) {
			shadeImage = new javafx.scene.image.WritableImage(lw, lh);
			shadePixels = new int[lw * lh];
		}
		for (int i = 0; i < lw; i++) {
			for (int j = 0; j < lh; j++) {
				int a = (int) (Math.min(0.95, Math.max(0, (1 - light[i][j]) * 0.97)) * 255);
				shadePixels[j * lw + i] = (a << 24) | darkTint;
			}
		}
		shadeImage.getPixelWriter().setPixels(0, 0, lw, lh, javafx.scene.image.PixelFormat.getIntArgbInstance(), shadePixels, 0, lw);
		g.setImageSmoothing(true);
		g.drawImage(shadeImage, screenX(x0), screenY(y0), lw * block, lh * block);
		g.setImageSmoothing(false);
		g.setGlobalBlendMode(BlendMode.SRC_OVER); // SCREEN on a JavaFX canvas wrote see-through pixels (the launcher showed through)
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				int id = level.get(x, y);
				Color c = null;
				double r = block * 1.3;
				double cy = screenY(y + 0.5);
				if (id == Items.TORCH) {
					c = Color.rgb(255, 160, 80, 0.32);
					r = block * (2.2 + Math.sin(time * 9 + x) * 0.12);
					cy = screenY(y + 0.3);
				} else if (Items.glow(id) > 0) {
					c = argbColor(Items.tone(id)).deriveColor(0, 1, 1, 0.26);
				} else if (id == Items.AIR) {
					int decor = level.decorAt(x, y);
					if (decor == 7) {
						c = Color.rgb(110, 240, 208, 0.22);
					} else if (decor == 8) {
						c = Color.rgb(180, 140, 255, 0.24);
					} else if (decor == 12) {
						c = Color.rgb(255, 130, 50, 0.2);
					}
					cy = screenY(y + 0.7);
				}
				if (c == null) {
					continue;
				}
				double cx = screenX(x + 0.5);
				g.setFill(new RadialGradient(0, 0, cx, cy, r, false, CycleMethod.NO_CYCLE, new Stop(0, c), new Stop(1, Color.TRANSPARENT)));
				g.fillRect(cx - r, cy - r, r * 2, r * 2);
			}
		}
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
	}

	private double[][] light(int x0, int y0, int x1, int y1) {
		int m = 7;
		int w = x1 - x0 + 1 + m * 2;
		int h = y1 - y0 + 1 + m * 2;
		double[][] l = new double[w][h];
		boolean outdoors = level.outdoors();
		double ambient = level.ambient();
		for (int i = 0; i < w; i++) {
			int x = x0 - m + i;
			int first = -1;
			if (outdoors) {
				for (int y = 0; y < level.height(); y++) {
					if (!Items.clear(level.get(x, y))) {
						first = y;
						break;
					}
				}
			}
			for (int j = 0; j < h; j++) {
				int y = y0 - m + j;
				double value = ambient;
				if (outdoors && (y < 0 || first < 0 || y < first)) {
					value = 1;
				} else if (outdoors) {
					value = Math.max(ambient, 1 - (y - first + 1) * 0.2);
				}
				int id = level.get(x, y);
				double glow = Items.glow(id);
				if (id == Items.AIR) {
					int decor = level.decorAt(x, y);
					if (decor == 7 || decor == 8 || decor == 12) {
						glow = 0.55;
					}
				}
				l[i][j] = Math.max(value, glow);
			}
		}
		addLight(l, px, py - 1, x0 - m, y0 - m, 0.82);
		for (Remote r : remotes.values()) {
			if (r.level.equals(level.id())) {
				addLight(l, r.x, r.y - 1, x0 - m, y0 - m, 0.7);
			}
		}
		Sim sim = sims.get(level.id());
		if (sim != null) {
			for (Proj p : sim.projectiles) {
				if (p.kind == Actors.FIREBALL || p.kind == Actors.ORB || p.kind == Actors.PILLAR) {
					addLight(l, p.x, p.y, x0 - m, y0 - m, 0.7);
				}
			}
			for (Mob mob : sim.mobs) {
				if (mob.boss() || mob.kind == Actors.TRADER || mob.kind == Actors.IMP || mob.kind == Actors.MAGMA_SLIME || mob.kind == Actors.VOID_WISP) {
					addLight(l, mob.x, mob.y - mob.tall() / 2, x0 - m, y0 - m, mob.boss() ? 0.75 : 0.55);
				}
			}
		}
		for (int pass = 0; pass < 6; pass++) {
			for (int i = 0; i < w; i++) {
				for (int j = 0; j < h; j++) {
					double best = l[i][j];
					double drop = Items.solid(level.get(x0 - m + i, y0 - m + j)) ? 0.22 : 0.11;
					if (i > 0) {
						best = Math.max(best, l[i - 1][j] - drop);
					}
					if (i < w - 1) {
						best = Math.max(best, l[i + 1][j] - drop);
					}
					if (j > 0) {
						best = Math.max(best, l[i][j - 1] - drop);
					}
					if (j < h - 1) {
						best = Math.max(best, l[i][j + 1] - drop);
					}
					l[i][j] = best;
				}
			}
		}
		double[][] out = new double[x1 - x0 + 1][y1 - y0 + 1];
		for (int i = 0; i < out.length; i++) {
			for (int j = 0; j < out[0].length; j++) {
				out[i][j] = Math.max(0.05, l[i + m][j + m]);
			}
		}
		return out;
	}

	private static void addLight(double[][] l, double x, double y, int ox, int oy, double value) {
		int i = (int) Math.floor(x) - ox;
		int j = (int) Math.floor(y) - oy;
		if (i >= 0 && i < l.length && j >= 0 && j < l[0].length) {
			l[i][j] = Math.max(l[i][j], value);
		}
	}

	private void drawPlayer(GraphicsContext g) {
		if (invulnerable > 0 && ((int) (invulnerable * 14)) % 2 == 0) {
			g.setGlobalAlpha(0.45);
		}
		double legSwing = Math.sin(walkPhase * Math.PI) * 38 * walkAmount;
		if (!onGround) {
			legSwing = vy < 0 ? 22 : 12;
		}
		drawPlayerAt(g, arcade.rig, screenX(px), screenY(py), facing, legSwing, swing, ids[selected], armor, front, turn, true);
		g.setGlobalAlpha(1);
	}

	private void drawPlayerAt(GraphicsContext g, SkinRig rig, double x, double y, int face, double legSwing, double swingT, int held,
			int armorId, double frontness, double turnAmount, boolean local) {
		g.setFill(Color.rgb(0, 0, 0, 0.28));
		g.fillOval(x - 6 * scale, y - 1.5 * scale, 12 * scale, 3 * scale);
		if (rig == null) {
			rig = defaultRig();
		}
		int[] armorColors = Items.armorColors(armorId);
		if (frontness > 0.5) {
			double squash = Math.min(1, (frontness - 0.5) * 2);
			rig.drawFront(g, x, y, scale, 0.3 + squash * 0.7);
			if (armorColors != null) {
				g.setFill(argbColor(armorColors[1]).deriveColor(0, 1, 1, 0.85));
				g.fillRect(x - 4 * scale, y - 24 * scale, 8 * scale, 12 * scale);
				g.setFill(argbColor(armorColors[0]).deriveColor(0, 1, 1, 0.85));
				g.fillRect(x - 4 * scale, y - 33 * scale, 8 * scale, 3 * scale);
			}
			return;
		}
		double squash = Math.min(1, (0.5 - frontness) * 2) * turnAmount;
		double armSwing = -legSwing * 0.8;
		double idle = Math.sin(t * 2.2) * 2.5;
		double frontArm = 0;
		boolean bow = Items.bow(held) > 0;
		if (swingT < 1) {
			double e = 1 - Math.pow(1 - swingT, 3);
			frontArm = bow ? -90 : -150 + e * 120;
		} else if (held != 0) {
			frontArm = bow ? -80 : -12 + armSwing * 0.3;
		}
		double tilt = 0;
		if (local) {
			double mx = worldX(mouseX);
			double my = worldY(mouseY);
			tilt = clamp(Math.toDegrees(Math.atan2(my - (py - 1.6), Math.abs(mx - px) + 0.5)) * 0.35, -16, 14);
		}
		Image heldImage = held == 0 ? null : Items.icon(held);
		rig.drawSide(g, x, y, scale, face, legSwing, armSwing + idle, frontArm, tilt, squash, heldImage, bow ? 90 : held >= 96 ? 45 : 20);
		if (armorColors != null) {
			g.save();
			g.translate(x, y);
			g.scale(face * Math.max(0.05, squash), 1);
			g.setFill(argbColor(armorColors[1]).deriveColor(0, 1, 1, 0.9));
			g.fillRect(-2.5 * scale, -24 * scale, 5 * scale, 11 * scale);
			g.setFill(argbColor(armorColors[0]).deriveColor(0, 1, 1, 0.9));
			g.fillRect(-4.5 * scale, -33 * scale, 9 * scale, 3 * scale);
			g.fillRect(-4.5 * scale, -33 * scale, 2 * scale, 6 * scale);
			g.setFill(argbColor(armorColors[2]).deriveColor(0, 1, 1, 0.9));
			g.fillRect(-2.5 * scale, -15 * scale, 5 * scale, 2 * scale);
			g.restore();
		}
	}

	private static SkinRig fallbackRig;

	private static SkinRig defaultRig() {
		if (fallbackRig == null) {
			fallbackRig = SkinRig.from(null, false);
		}
		return fallbackRig;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private void drawHud(GraphicsContext g, double w, double h, Sim sim) {
		for (int i = 0; i < maxHealth; i++) {
			double x = 18 + (i % 15) * 14;
			double y = 18 + (i / 15) * 14;
			g.setFill(Color.rgb(0, 0, 0, 0.45));
			g.fillRoundRect(x - 1, y - 1, 12, 12, 4, 4);
			g.setFill(i < health ? Color.web("#E0333A") : Color.rgb(255, 255, 255, 0.10));
			g.fillRoundRect(x, y, 10, 10, 3, 3);
			if (i < health) {
				g.setFill(Color.rgb(255, 150, 150, 0.6));
				g.fillRect(x + 2, y + 2, 3, 2);
			}
		}
		double row = 18 + ((maxHealth - 1) / 15 + 1) * 14 + 4;
		double bx0 = 18;
		if (armor != 0) {
			g.drawImage(Items.icon(armor), bx0, row, 20, 20);
			arcade.label(g, String.valueOf(Items.defense(armor)), bx0 + 22, row + 15, 11, Color.web("#C9C0B8"), false);
			bx0 += 44;
		}
		if (speedBuff > 0) {
			g.drawImage(Items.icon(Items.SWIFT_ELIXIR), bx0, row, 20, 20);
			arcade.label(g, (int) speedBuff + "s", bx0 + 22, row + 15, 10, Color.web("#9CE4F0"), false);
			bx0 += 48;
		}
		if (strengthBuff > 0) {
			g.drawImage(Items.icon(Items.STRENGTH_TONIC), bx0, row, 20, 20);
			arcade.label(g, (int) strengthBuff + "s", bx0 + 22, row + 15, 10, Color.web("#FFC27A"), false);
		}
		double ty = 24;
		if (sim != null && sim.boss != null && level instanceof Dungeon) {
			Mob b = sim.boss;
			double bw = Math.min(w - 260, 460);
			double bxx = (w - bw) / 2;
			arcade.label(g, Actors.name(b.kind).toUpperCase() + (b.phase > 1 ? "  ·  ENRAGED" : ""), w / 2, ty, 13,
					Color.web(b.phase > 1 ? "#FF6B6F" : "#F4EFEE"), true, true);
			g.setFill(Color.rgb(0, 0, 0, 0.55));
			g.fillRoundRect(bxx - 2, ty + 8, bw + 4, 12, 6, 6);
			double frac = Math.max(0, b.hp) / (double) b.maxHp;
			g.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, new Stop(0, Color.web("#8A1218")), new Stop(1, Color.web("#FF3B44"))));
			g.fillRoundRect(bxx, ty + 10, bw * frac, 8, 4, 4);
			g.setFill(Color.rgb(255, 255, 255, 0.5));
			g.fillRect(bxx + bw / 2 - 1, ty + 9, 2, 10);
			ty += 34;
		}
		double ry = 66;
		if (level == world) {
			for (int i = 0; i < 3; i++) {
				if (count(Items.MAP_CRYPT + i) == 0) {
					continue;
				}
				double dx = world.gateX(i) - px;
				double dy = world.gateY(i) - py;
				String arrow = Math.abs(dx) < 3 ? "here" : (dx > 0 ? "→ " : "← ") + (int) Math.abs(dx);
				String text = Dungeon.Theme.values()[i].title + "  " + arrow + "  " + (dy > 0 ? "↓ " : "↑ ") + (int) Math.abs(dy)
						+ (dungeon(i).bossDefeated ? "  ✓" : "");
				arcade.label(g, text, w - 16, ry, 11, argbColor(Dungeon.Theme.values()[i].glow).deriveColor(0, 1, 3, 1), true, false, false);
				ry += 17;
			}
		}
		if (!coopStatus.isEmpty()) {
			int n = remotes.size() + 1;
			arcade.label(g, coopStatus + (n > 1 ? "  ·  " + n + " players" : "") + (friendlyFire ? "  ·  friendly fire" : ""), w - 16, ry, 11,
					Color.web("#9AF5CF"), true, false, false);
		}
		if (announceTimer > 0 && announcement != null) {
			double a = Math.max(0, Math.min(1, Math.min(announceTimer * 2, (3.2 - announceTimer) * 4)));
			arcade.label(g, announcement, w / 2, h * 0.3, 22, Color.web("#F4EFEE", a), true, true);
		}
		double slot = 40;
		double gap = 4;
		double total = slot * 9 + gap * 8;
		double hx = Math.round((w - total) / 2);
		double hy = h - slot - 14;
		g.setFill(Color.rgb(10, 7, 8, 0.72));
		g.fillRoundRect(hx - 6, hy - 6, total + 12, slot + 12, 14, 14);
		for (int i = 0; i < 9; i++) {
			double x = hx + i * (slot + gap);
			boolean sel = i == selected;
			g.setFill(sel ? Color.rgb(215, 38, 45, 0.28) : Color.rgb(255, 255, 255, 0.05));
			g.fillRoundRect(x, hy, slot, slot, 8, 8);
			if (sel) {
				g.setStroke(Color.web("#E0333A"));
				g.setLineWidth(2);
				g.strokeRoundRect(x + 1, hy + 1, slot - 2, slot - 2, 8, 8);
			}
			if (counts[i] > 0) {
				g.drawImage(Items.icon(ids[i]), x + 4, hy + 4, 32, 32);
				if (counts[i] > 1) {
					arcade.label(g, String.valueOf(counts[i]), x + slot - 4, hy + slot - 4, 11, Color.web("#F4EFEE"), true, false, true);
				}
			}
			arcade.label(g, String.valueOf(i + 1), x + 4, hy + 11, 9, Color.rgb(255, 240, 240, 0.35), false);
		}
		if (selectedShown > 0 && counts[selected] > 0) {
			double a = Math.min(1, selectedShown * 2);
			arcade.label(g, Items.name(ids[selected]), w / 2, hy - 30, 13, Color.web("#F4EFEE", a), true, true);
			String text = Items.text(ids[selected]);
			if (!text.isEmpty()) {
				arcade.label(g, text, w / 2, hy - 14, 10, Color.web("#C9B9B6", a), true, true);
			}
		}
		if (!hintSeen && hintTimer < 14) {
			double a = Math.min(1, Math.min(hintTimer * 2, (14 - hintTimer)));
			arcade.label(g, "A/D move · Space jump · S drop down · Left click mine & fight · Right click use / place · E inventory · Esc menu",
					w / 2, hy - 50, 11, Color.rgb(244, 239, 238, 0.85 * a), true, true);
		}
	}

	// ------------------------------------------------------------------ saving

	@Override
	public void save(JsonObject out) {
		if (joined && ownWorld != null) {
			World keep = world;
			Dungeon[] keepD = dungeons.clone();
			Level keepLevel = level;
			world = ownWorld;
			System.arraycopy(ownDungeons, 0, dungeons, 0, 3);
			level = world;
			saveInto(out, "world", 0.5, ownWorld.surface(0));
			world = keep;
			System.arraycopy(keepD, 0, dungeons, 0, 3);
			level = keepLevel;
			return;
		}
		saveInto(out, level.id(), px, py);
	}

	private void saveInto(JsonObject out, String levelId, double x, double y) {
		out.addProperty("v", 2);
		out.addProperty("seed", world.seed);
		out.addProperty("level", levelId);
		out.addProperty("px", x);
		out.addProperty("py", y);
		out.addProperty("rx", returnX);
		out.addProperty("ry", returnY);
		out.addProperty("health", Math.max(1, health));
		out.addProperty("maxHealth", maxHealth);
		out.addProperty("armor", armor);
		out.addProperty("selected", selected);
		out.addProperty("friendlyFire", friendlyFire);
		out.addProperty("hintSeen", hintSeen || hintTimer > 12);
		JsonArray inv = new JsonArray();
		for (int i = 0; i < SLOTS; i++) {
			inv.add(ids[i]);
			inv.add(counts[i]);
		}
		out.add("inventory", inv);
		out.add("edits", edits(world));
		JsonArray cleared = new JsonArray();
		for (boolean c : world.cleared) {
			cleared.add(c ? 1 : 0);
		}
		out.add("cleared", cleared);
		JsonArray ds = new JsonArray();
		for (int i = 0; i < 3; i++) {
			JsonObject d = new JsonObject();
			if (dungeons[i] != null) {
				d.add("edits", edits(dungeons[i]));
				d.addProperty("boss", dungeons[i].bossDefeated);
			}
			ds.add(d);
		}
		out.add("dungeons", ds);
	}

	@Override
	public void load(JsonObject in) {
		if (!in.has("seed") || !in.has("v") || in.get("v").getAsInt() < 2) {
			return; // the first (pre-dungeon) format: a fresh world
		}
		world = new World(in.get("seed").getAsLong());
		if (in.has("cleared")) {
			JsonArray c = in.getAsJsonArray("cleared");
			for (int i = 0; i < 3; i++) {
				world.cleared[i] = c.get(i).getAsInt() == 1;
			}
		}
		readEdits(world, in.getAsJsonArray("edits"));
		java.util.Arrays.fill(dungeons, null);
		if (in.has("dungeons")) {
			JsonArray ds = in.getAsJsonArray("dungeons");
			for (int i = 0; i < 3 && i < ds.size(); i++) {
				JsonObject d = ds.get(i).getAsJsonObject();
				if (d.has("edits")) {
					Dungeon dg = dungeon(i);
					readEdits(dg, d.getAsJsonArray("edits"));
					dg.applyEdits();
					dg.bossDefeated = d.has("boss") && d.get("boss").getAsBoolean();
				}
			}
		}
		sims.clear();
		JsonArray inv = in.getAsJsonArray("inventory");
		java.util.Arrays.fill(ids, 0);
		java.util.Arrays.fill(counts, 0);
		for (int i = 0; i < SLOTS && inv != null && i * 2 + 1 < inv.size(); i++) {
			int id = inv.get(i * 2).getAsInt();
			if (Items.known(id)) {
				ids[i] = id;
				counts[i] = inv.get(i * 2 + 1).getAsInt();
			}
		}
		level = levelById(in.has("level") ? in.get("level").getAsString() : "world");
		px = in.get("px").getAsDouble();
		py = in.get("py").getAsDouble();
		returnX = in.has("rx") ? in.get("rx").getAsDouble() : 0;
		returnY = in.has("ry") ? in.get("ry").getAsDouble() : 0;
		maxHealth = in.has("maxHealth") ? in.get("maxHealth").getAsInt() : 10;
		health = in.has("health") ? Math.min(maxHealth, in.get("health").getAsInt()) : maxHealth;
		armor = in.has("armor") ? in.get("armor").getAsInt() : 0;
		selected = in.has("selected") ? in.get("selected").getAsInt() : 0;
		friendlyFire = in.has("friendlyFire") && in.get("friendlyFire").getAsBoolean();
		hintSeen = in.has("hintSeen") && in.get("hintSeen").getAsBoolean();
		if (blocked(px, py)) {
			respawn();
		}
		camReady = false;
	}

	@Override
	public void dispose() {
		stopCoop();
		world.clearCache();
		sims.clear();
		floaters.clear();
		remotes.clear();
		pickups.clear();
	}

	// ------------------------------------------------------------------ screenshot tour

	void tourSetup(String scene) {
		java.util.Arrays.fill(ids, 0);
		java.util.Arrays.fill(counts, 0);
		give(Items.IRON_PICK, 1);
		give(Items.BLOOD_SWORD, 1);
		give(Items.BONE_BOW, 1);
		give(Items.TORCH, 24);
		give(Items.PLANKS, 37);
		give(Items.ARROW, 40);
		give(Items.HEAL_POTION, 3);
		give(Items.RUBY, 4);
		give(Items.MAP_CRYPT, 1);
		give(Items.MAP_FOUNDRY, 1);
		give(Items.ANVIL, 1);
		give(Items.HEART_CRYSTAL, 2);
		armor = Items.RUBY_ARMOR;
		maxHealth = 16;
		health = 13;
		floaters.clear();
		hintSeen = true;
		selected = 1;
		front = 0;
		frontTarget = 0;
		camReady = false;
		sims.clear();
		switch (scene) {
			case "cave" -> {
				level = world;
				outer:
				for (int x = 4; x < 400; x++) {
					int s = world.surface(x);
					for (int y = s + 16; y < World.HEIGHT - 4; y++) {
						if (world.get(x, y) == Items.AIR && world.get(x, y - 1) == Items.AIR && Items.solid(world.get(x, y + 1))) {
							px = x + 0.5;
							py = y + 1 - 1e-3;
							world.set(x + 3, y - 1, Items.TORCH);
							Sim sim = sim(world);
							sim.mobs.add(new Mob(900, Actors.TRADER, x - 2.5, y + 1 - 1e-3));
							sim.mobs.add(new Mob(901, Actors.BAT, x + 4, y - 1.5));
							break outer;
						}
					}
				}
			}
			case "ash", "frost" -> {
				level = world;
				int want = scene.equals("ash") ? World.ASHLANDS : World.FROST;
				for (int x = 40; x < 6000; x += 7) {
					if (world.biome(x) == want && world.biome(x + 30) == want && world.biome(x - 30) == want) {
						px = x + 0.5;
						py = world.surface(x);
						while (blocked(px, py)) {
							py -= 1;
						}
						Sim sim = sim(world);
						int kind = want == World.ASHLANDS ? Actors.SCUTTLER : Actors.FROST_WISP;
						sim.mobs.add(new Mob(902, kind, px + 6, want == World.ASHLANDS ? world.surface((int) px + 6) : py - 3));
						break;
					}
				}
			}
			case "village", "outpost" -> {
				level = world;
				int want = scene.equals("village") ? World.VILLAGE : World.OUTPOST;
				for (int r = 1; r < 60; r++) {
					World.Structure st = world.structureFor(r % 2 == 0 ? r / 2 : -(r / 2) - 1);
					if (st.type() == want) {
						px = want == World.VILLAGE ? st.houses().get(Math.min(1, st.houses().size() - 1))[0] - 1.5 : st.x0() - 3.5;
						py = world.surface((int) Math.floor(px));
						while (blocked(px, py)) {
							py -= 1;
						}
						Sim sim = sim(world);
						for (int k = 0; k < 90; k++) {
							sim.update(1 / 60.0, List.of(new Sim.Target("local", px, py)), true);
						}
						if (want == World.VILLAGE) {
							for (Mob m : sim.mobs) {
								if (m.kind == Actors.VILLAGER && Math.floorMod(m.id, 3) != 0) {
									speak(m);
									break;
								}
							}
						}
						break;
					}
				}
			}
			case "riddle0", "riddle1", "riddle2" -> {
				Dungeon d = dungeon(scene.charAt(6) - '0');
				level = d;
				int[] piece = d.puzzle.get(0);
				px = piece[0] - 2.5;
				py = piece[1] + 1 - 1e-3;
				for (int up = 0; up < 6 && blocked(px, py); up++) {
					py -= 1;
				}
				Sim sim = sim(d);
				sim.mobs.removeIf(m -> Math.abs(m.x - px) < 14);
				say(d.theme.title + " · " + d.riddle.name().toLowerCase());
			}
			case "dungeon", "foundry", "spire" -> {
				Dungeon d = dungeon(scene.equals("dungeon") ? 0 : scene.equals("foundry") ? 1 : 2);
				level = d;
				px = d.startX + 0.5;
				py = d.startY - 1e-3;
				sim(d);
			}
			case "boss", "boss1", "boss2" -> {
				int index = scene.equals("boss") ? 0 : scene.equals("boss1") ? 1 : 2;
				Dungeon d = dungeon(index);
				level = d;
				for (int[] door : d.doors) {
					d.set(door[0], door[1], door[2]);
				}
				px = d.bossX - 7;
				py = d.bossY;
				Sim sim = sim(d);
				sim.mobs.clear();
				sim.update(0.016, List.of(new Sim.Target("local", px, py)), true);
				if (index > 0 && sim.boss != null) {
					sim.boss.phase = 2; // the screenshots show the enraged look for these two
				}
			}
			default -> {
				level = world;
				for (int x = 6; x < 300; x++) {
					if (world.isTree(x)) {
						px = x - 5.5;
						py = world.surface((int) Math.floor(px));
						break;
					}
				}
				Sim sim = sim(world);
				sim.mobs.add(new Mob(903, Actors.SLIME, px + 8.5, world.surface((int) Math.floor(px + 8.5))));
			}
		}
	}

	/** Tour: let the fight run a little so a boss move is mid-way. */
	void tourAdvance(double seconds) {
		Sim sim = sims.get(level.id());
		if (sim == null) {
			return;
		}
		for (double s = 0; s < seconds; s += 1 / 60.0) {
			sim.update(1 / 60.0, List.of(new Sim.Target("local", px, py)), true);
		}
	}
}
