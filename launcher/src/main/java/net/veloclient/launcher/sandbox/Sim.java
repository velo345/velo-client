package net.veloclient.launcher.sandbox;

import net.veloclient.launcher.sandbox.Actors.Mob;
import net.veloclient.launcher.sandbox.Actors.Proj;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Everything alive in one level: monsters, the boss, projectiles, traps and crumbling ledges. The host (or a solo
 * player) runs it for real; a party guest runs a mirror that only glides the monsters to where the host says they
 * are and flies the projectiles it was told about.
 */
final class Sim {

	interface Host {
		void sound(String name, double x, double y);

		void burst(double x, double y, int argb, int n);

		void shake(double amount, double x, double y);

		/** Sets a block and tells the party. */
		void tile(Level level, int x, int y, int id);

		/** A projectile was fired (the host tells the guests). */
		void fired(Sim sim, Proj p);

		void killed(Sim sim, Mob mob, String killer);

		void bossDown(Sim sim, Mob boss, String killer);

		void announce(String text);

		/** A little word over something (CLANK, EXPOSED...). */
		void text(double x, double y, String text);

		/** Far enough along for the map trader (an iron pickaxe or better). */
		default boolean traderUnlocked() {
			return true;
		}
	}

	record Target(String who, double x, double y) {
	}

	final Level level;
	final World world;
	final Dungeon dungeon;
	final List<Mob> mobs = new ArrayList<>();
	final List<Proj> projectiles = new ArrayList<>();
	private final Host host;
	private final SplittableRandom random = new SplittableRandom();
	private int nextId = 1;
	private double spawnTimer = 5;
	private double trapClock;
	private final List<double[]> crumbles = new ArrayList<>();
	Mob boss;
	private boolean bossIntroduced;

	Sim(Level level, World world, Host host) {
		this.level = level;
		this.world = world;
		this.dungeon = level instanceof Dungeon d ? d : null;
		this.host = host;
	}

	/** Dungeon monsters placed at generation (host / solo only). */
	void populate() {
		if (dungeon == null) {
			return;
		}
		int[][] kinds = {{Actors.CRYPT_SLIME, Actors.SKELETON}, {Actors.MAGMA_SLIME, Actors.IMP}, {Actors.VOID_WISP, Actors.STALKER}};
		for (int[] s : dungeon.spawns) {
			int kind = kinds[dungeon.index][s[2]];
			Mob mob = new Mob(nextId++, kind, s[0] + 0.5, s[1] - (stats(kind)[4] == Actors.FLY ? 2 : 0) - 1e-3);
			mobs.add(mob);
		}
	}

	private static int[] stats(int kind) {
		return Actors.stats(kind);
	}

	Mob find(int id) {
		for (Mob m : mobs) {
			if (m.id == id) {
				return m;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ update

	void update(double dt, List<Target> targets, boolean authoritative) {
		updateProjectiles(dt, authoritative);
		if (!authoritative) {
			for (Mob m : mobs) {
				double k = 1 - Math.exp(-dt * 12);
				m.vx = (m.nx - m.x) / Math.max(dt, 1e-3) * 0.2;
				m.x += (m.nx - m.x) * k;
				m.y += (m.ny - m.y) * k;
				m.anim += dt;
				m.hurt -= dt;
				m.tele -= dt;
			}
			return;
		}
		if (targets.isEmpty()) {
			return;
		}
		trapClock += dt;
		updateTraps(targets);
		updateCrumbles(dt, targets);
		updateDungeon(targets);
		spawn(dt, targets);
		peopleStructures(dt, targets);
		for (Iterator<Mob> it = mobs.iterator(); it.hasNext(); ) {
			Mob m = it.next();
			m.anim += dt;
			m.hurt -= dt;
			m.tele -= dt;
			m.life -= dt;
			Target t = nearest(targets, m.x, m.y);
			double dist = Math.abs(t.x() - m.x);
			boolean settled = !Double.isNaN(m.home);
			if ((dungeon == null && dist > (settled ? 95 : 56) && !m.boss()) || m.y > level.height() + 4 || m.life <= 0) {
				if (settled) {
					peopled.remove((int) m.timer2Home);
				}
				it.remove();
				continue;
			}
			m.facing = t.x() >= m.x ? 1 : -1;
			switch (m.behaviour()) {
				case Actors.HOP -> hop(m, t, dt);
				case Actors.FLY -> fly(m, t, dt);
				case Actors.WALK -> walk(m, t, dt);
				case Actors.BOSS -> boss(m, t, dt);
				case Actors.WANDER -> wander(m, t, dt);
				case Actors.FLOAT -> {
					m.vx = 0;
					m.y = m.ny + Math.sin(m.anim * 1.8 + m.id) * 0.3;
				}
				default -> standStill(m, dt);
			}
		}
	}

	private Target nearest(List<Target> targets, double x, double y) {
		Target best = targets.get(0);
		double bestD = Double.MAX_VALUE;
		for (Target t : targets) {
			double d = Math.hypot(t.x() - x, t.y() - y);
			if (d < bestD) {
				bestD = d;
				best = t;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ movement helpers

	boolean solid(int x, int y) {
		return Items.solid(level.get(x, y));
	}

	private boolean boxHits(double x0, double y0, double x1, double y1) {
		for (int x = (int) Math.floor(x0); x <= (int) Math.floor(x1 - 1e-6); x++) {
			for (int y = (int) Math.floor(y0); y <= (int) Math.floor(y1 - 1e-6); y++) {
				if (solid(x, y)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean blocked(Mob m, double x, double feet) {
		return boxHits(x - m.halfW(), feet - m.tall(), x + m.halfW(), feet);
	}

	/** Gravity + collisions (with one-way platforms) for walking/hopping monsters. */
	private void physics(Mob m, double dt) {
		m.vy = Math.min(26, m.vy + 34 * dt);
		double nx = m.x + m.vx * dt;
		if (!blocked(m, nx, m.y)) {
			m.x = nx;
		} else if (m.onGround && m.behaviour() == Actors.WALK && !blocked(m, nx, m.y - 1.05)) {
			m.y -= 1.05; // step up
			m.x = nx;
		} else {
			m.vx = 0;
			m.timer2 += 1;
		}
		double before = m.y;
		double ny = m.y + m.vy * dt;
		boolean landedOnPlatform = false;
		if (m.vy > 0) {
			int row = (int) Math.floor(ny);
			if (row > (int) Math.floor(before - 1e-6) || Math.floor(before) == before) {
				for (int x = (int) Math.floor(m.x - m.halfW()); x <= (int) Math.floor(m.x + m.halfW() - 1e-6); x++) {
					if (level.get(x, row) == Items.PLATFORM && before <= row + 1e-3) {
						landedOnPlatform = true;
					}
				}
			}
			if (landedOnPlatform) {
				m.y = row - 1e-4;
				m.vy = 0;
				m.onGround = true;
				return;
			}
		}
		if (!blocked(m, m.x, ny)) {
			m.y = ny;
			m.onGround = false;
		} else {
			if (m.vy > 0) {
				m.y = Math.floor(ny) - 1e-4;
				m.onGround = true;
			}
			m.vy = 0;
		}
	}

	private void hop(Mob m, Target t, double dt) {
		if (m.onGround) {
			m.vx *= Math.pow(0.0005, dt);
			m.timer -= dt;
			if (m.timer <= 0) {
				m.timer = 0.8 + random.nextDouble() * 1.1;
				boolean chase = Math.abs(t.x() - m.x) < 22;
				m.vy = -8.5 - random.nextDouble() * 3.5 - (m.kind == Actors.MAGMA_SLIME ? 1.5 : 0);
				m.vx = (chase ? m.facing : (random.nextBoolean() ? 1 : -1)) * (m.speed() * 0.8 + random.nextDouble() * 1.4);
				m.onGround = false;
				host.sound("hop", m.x, m.y);
			}
		}
		physics(m, dt);
	}

	private void walk(Mob m, Target t, double dt) {
		double dx = t.x() - m.x;
		double want = m.facing * m.speed();
		if (m.kind == Actors.SKELETON) {
			// keeps its distance and shoots
			double d = Math.abs(dx);
			want = d < 5 ? -m.facing * m.speed() : d > 9 ? m.facing * m.speed() : 0;
			m.timer -= dt;
			if (m.timer <= 0 && d < 16) {
				m.timer = 1.8 + random.nextDouble();
				shootAt(m, t, Actors.ARROW, 13, 2);
			}
		}
		if (m.kind == Actors.BANDIT_ARCHER) {
			double d = Math.abs(dx);
			want = d < 6 ? -m.facing * m.speed() : d > 11 ? m.facing * m.speed() : 0;
			m.timer -= dt;
			if (m.timer <= 0 && d < 17) {
				m.timer = 1.6 + random.nextDouble();
				shootAt(m, t, Actors.ARROW, 15, 2);
			}
		}
		if (!Double.isNaN(m.home) && Math.abs(dx) > 26) {
			// bandits guard their camp: don't chase far
			want = Math.signum(m.home - m.x) * m.speed() * (Math.abs(m.home - m.x) > 2 ? 1 : 0);
		}
		if (m.kind == Actors.STALKER) {
			m.timer -= dt;
			if (m.timer <= 0 && Math.abs(dx) < 18) {
				m.timer = 4 + random.nextDouble() * 2;
				double tx = t.x() - Math.signum(dx) * 2.5;
				if (!blocked(m, tx, t.y()) && blocked(m, tx, t.y() + 0.2)) {
					host.burst(m.x, m.y - m.tall() / 2, 0xFF6E3FD0, 12);
					m.x = tx;
					m.y = t.y();
					m.tele = 0.35;
					host.sound("teleport", m.x, m.y);
					host.burst(m.x, m.y - m.tall() / 2, 0xFFB48CFF, 12);
				}
			}
		}
		if (Math.abs(dx) > 30) {
			want = 0;
		}
		m.vx += (want - m.vx) * Math.min(1, dt * 8);
		if (m.onGround && m.timer2 > 0) {
			m.vy = -11;
			m.onGround = false;
			m.timer2 = 0;
		}
		physics(m, dt);
	}

	private void fly(Mob m, Target t, double dt) {
		double tx = t.x() + Math.sin(m.anim * 1.3 + m.id) * 2.5;
		double ty = t.y() - 1.8 + Math.sin(m.anim * 2.1 + m.id) * 1.2 - (m.kind == Actors.IMP ? 2.5 : 0);
		double max = m.speed();
		m.vx = clamp(m.vx + Math.signum(tx - m.x) * 9 * dt, -max, max);
		m.vy = clamp(m.vy + Math.signum(ty - m.y) * 9 * dt, -max * 0.8, max * 0.8);
		double nx = m.x + m.vx * dt;
		double ny = m.y + m.vy * dt;
		if (solid((int) Math.floor(nx), (int) Math.floor(m.y - 0.2))) {
			m.vx = -m.vx;
		} else {
			m.x = nx;
		}
		if (solid((int) Math.floor(m.x), (int) Math.floor(ny - 0.2)) || solid((int) Math.floor(m.x), (int) Math.floor(ny))) {
			m.vy = -m.vy;
		} else {
			m.y = ny;
		}
		m.timer -= dt;
		double d = Math.hypot(t.x() - m.x, t.y() - m.y);
		if (m.kind == Actors.IMP && m.timer <= 0 && d < 14) {
			m.timer = 2.2 + random.nextDouble();
			shootAt(m, t, Actors.FIREBALL, 9, 2);
		} else if (m.kind == Actors.VOID_WISP && m.timer <= 0 && d < 12) {
			m.timer = 3 + random.nextDouble();
			shootAt(m, t, Actors.ORB, 4.5, 2).homing = true;
		} else if (m.kind == Actors.BAT && random.nextDouble() < dt * 0.3 && d < 10) {
			host.sound("bat", m.x, m.y);
		}
	}

	/** Villagers: stroll around their village, stop and look at you when you come close. */
	private void wander(Mob m, Target t, double dt) {
		double d = Math.hypot(t.x() - m.x, t.y() - m.y);
		if (d < 3.5) {
			m.vx += (0 - m.vx) * Math.min(1, dt * 10);
		} else {
			m.timer -= dt;
			if (m.timer <= 0) {
				m.timer = 2 + random.nextDouble() * 4;
				double home = Double.isNaN(m.home) ? m.x : m.home;
				m.moveT = home + (random.nextDouble() - 0.5) * 18;
				m.move = random.nextDouble() < 0.35 ? 0 : 1; // sometimes just stand
			}
			double want = m.move == 1 && Math.abs(m.moveT - m.x) > 0.5 ? Math.signum(m.moveT - m.x) * m.speed() : 0;
			m.vx += (want - m.vx) * Math.min(1, dt * 6);
			if (Math.abs(m.vx) > 0.1) {
				m.facing = m.vx > 0 ? 1 : -1;
			}
		}
		if (m.onGround && m.timer2 > 0) {
			m.vy = -10;
			m.onGround = false;
			m.timer2 = 0;
		}
		physics(m, dt);
	}

	/** Villages and outposts already holding their people (by the structure's left edge). */
	private final java.util.Set<Integer> peopled = new java.util.HashSet<>();
	private double peopleClock;

	private void peopleStructures(double dt, List<Target> targets) {
		peopleClock -= dt;
		if (peopleClock > 0 || dungeon != null) {
			return;
		}
		peopleClock = 1;
		for (Target t : targets) {
			for (World.Structure st : world.structuresNear(t.x(), 40)) {
				if (!peopled.add(st.x0())) {
					continue;
				}
				java.util.Random r = new java.util.Random(st.seed());
				int ground = st.ground();
				if (st.type() == World.VILLAGE) {
					int n = Math.max(2, st.houses().size());
					for (int i = 0; i < n; i++) {
						int[] h = st.houses().get(i % Math.max(1, st.houses().size()));
						Mob v = new Mob(nextId++, Actors.VILLAGER, h[0] + h[1] / 2.0 + 0.5, ground - 1e-3);
						v.home = v.x;
						v.timer2Home = st.x0();
						v.facing = r.nextBoolean() ? 1 : -1;
						mobs.add(v);
					}
				} else {
					int n = 3 + r.nextInt(3);
					for (int i = 0; i < n; i++) {
						int kind = i % 3 == 2 ? Actors.BANDIT_ARCHER : Actors.BANDIT;
						double x = st.x0() + 4 + r.nextDouble() * (st.width() - 8);
						Mob b = new Mob(nextId++, kind, x, ground - 1e-3);
						b.home = st.centre();
						b.timer2Home = st.x0();
						mobs.add(b);
					}
					// one sentry up on the watchtower
					Mob sentry = new Mob(nextId++, Actors.BANDIT_ARCHER, st.centre(), ground - 8 - 1e-3);
					sentry.home = st.centre();
					sentry.timer2Home = st.x0();
					mobs.add(sentry);
				}
			}
		}
	}

	private void standStill(Mob m, double dt) {
		m.vx = 0;
		physics(m, dt);
	}

	private Proj shootAt(Mob m, Target t, int kind, double speed, int damage) {
		double sx = m.x;
		double sy = m.y - m.tall() * 0.65;
		double dx = t.x() - sx;
		double dy = (t.y() - 1) - sy;
		double len = Math.max(0.01, Math.hypot(dx, dy));
		double vx = dx / len * speed;
		double vy = dy / len * speed;
		if (kind == Actors.FIREBALL || kind == Actors.ARROW) {
			vy -= Math.abs(dx) * (kind == Actors.FIREBALL ? 0.55 : 0.12); // lob a little
		}
		Proj p = new Proj(kind, sx, sy, vx, vy, damage, true);
		fire(p);
		host.sound(kind == Actors.ARROW ? "bow" : kind == Actors.FIREBALL ? "fire" : "orb", sx, sy);
		return p;
	}

	void fire(Proj p) {
		projectiles.add(p);
		host.fired(this, p);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ------------------------------------------------------------------ projectiles

	private void updateProjectiles(double dt, boolean authoritative) {
		for (Iterator<Proj> it = projectiles.iterator(); it.hasNext(); ) {
			Proj p = it.next();
			p.age += dt;
			p.life -= dt;
			if (p.homing && !mobs.isEmpty()) {
				// homing orbs drift on; the steering happens against the player in WorldGame
			}
			p.vy += p.gravity * dt;
			p.x += p.vx * dt;
			p.y += p.vy * dt;
			boolean dead = p.life <= 0;
			if (p.kind == Actors.WAVE) {
				// waves hug the floor; they die at walls
				if (solid((int) Math.floor(p.x + Math.signum(p.vx) * 0.4), (int) Math.floor(p.y - 0.5))) {
					dead = true;
				}
			} else if (p.kind != Actors.PILLAR && p.kind != Actors.ORB && solid((int) Math.floor(p.x), (int) Math.floor(p.y))) {
				dead = true;
				if (p.kind == Actors.FIREBALL) {
					host.burst(p.x, p.y, 0xFFFF8A2E, 8);
				}
			} else if (p.kind == Actors.ORB && solid((int) Math.floor(p.x), (int) Math.floor(p.y)) && p.age > 0.3) {
				dead = true;
			}
			if (!dead && !p.hostile && authoritative) {
				for (Mob m : mobs) {
					if (!m.friendly() && Math.abs(m.x - p.x) < m.halfW() + 0.2 && p.y > m.y - m.tall() && p.y < m.y + 0.1) {
						hit(m, p.damage, Math.signum(p.vx), "arrow");
						dead = true;
						break;
					}
				}
			}
			if (dead) {
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------------ hits

	/** A hit on a monster (authoritative side). {@code killer}: "local", a party member's id, or "arrow". */
	void hit(Mob m, int damage, double dir, String killer) {
		if (m.friendly() || m.hp <= 0) {
			return;
		}
		if (m.boss() && m.move == -1) {
			return; // roaring between phases: can't be hurt
		}
		if (m.kind == Actors.WARDEN) {
			boolean front = m.facing == -(int) Math.signum(dir);
			if (m.move == STUN) {
				damage *= 2;
				host.text(m.x, m.y - m.tall(), "EXPOSED");
			} else if (front) {
				damage = Math.max(1, (int) Math.round(damage * 0.3));
				host.text(m.x, m.y - m.tall(), "CLANK");
				host.burst(m.x - dir * 0.8, m.y - m.tall() * 0.6, 0xFFFFE08A, 6);
				host.sound("clank", m.x, m.y);
			}
		} else if (m.kind == Actors.GOLEM) {
			if (m.vent > 0) {
				damage = (int) Math.round(damage * 1.5);
				host.text(m.x, m.y - m.tall(), "CORE HIT");
			} else {
				damage = Math.max(1, (int) Math.round(damage * 0.2));
				host.text(m.x, m.y - m.tall(), "TOO HOT");
				host.burst(m.x, m.y - m.tall() * 0.5, 0xFFFF8A2E, 5);
				host.sound("clank", m.x, m.y);
			}
		} else if (m.kind == Actors.HERALD && crystalsAlive() > 0) {
			host.text(m.x, m.y - m.tall(), "SHIELDED");
			host.burst(m.x, m.y - m.tall() * 0.5, 0xFFB48CFF, 6);
			host.sound("clank", m.x, m.y);
			return;
		}
		if (m.kind == Actors.VOID_CRYSTAL && m.hp - damage <= 0) {
			host.sound("shatter", m.x, m.y);
			host.shake(6, m.x, m.y);
			crystalTimer = boss != null && boss.phase > 1 ? 7 : 8;
		}
		m.hp -= damage;
		m.hurt = 0.18;
		if (!m.boss()) {
			m.vx = dir * 7;
			m.vy = -5;
			m.onGround = false;
		}
		host.sound("hit", m.x, m.y);
		if (m.hp <= 0) {
			mobs.remove(m);
			if (m.boss()) {
				boss = null;
				host.bossDown(this, m, killer);
			} else {
				host.sound("mobdie", m.x, m.y);
				host.burst(m.x, m.y - m.tall() / 2, color(m.kind), 16);
				host.killed(this, m, killer);
			}
		} else if (m.boss() && m.phase == 1 && m.hp < m.maxHp / 2) {
			// phase two: a roar, a shockwave of particles, faster and meaner
			m.phase = 2;
			m.move = -1;
			m.moveT = 0;
			host.sound("roar", m.x, m.y);
			host.shake(14, m.x, m.y);
			host.announce(Actors.name(m.kind) + " is enraged!");
		}
	}

	static int color(int kind) {
		return switch (kind) {
			case Actors.BAT -> 0xFF3A2A40;
			case Actors.SCUTTLER -> 0xFFC9A46A;
			case Actors.FROST_WISP -> 0xFFB8E4FF;
			case Actors.VOID_WISP, Actors.STALKER -> 0xFF8A5AE0;
			case Actors.IMP, Actors.MAGMA_SLIME -> 0xFFFF6A2A;
			case Actors.REVENANT -> 0xFF5E6E4A;
			case Actors.SKELETON, Actors.CRYPT_SLIME -> 0xFFE8DEC8;
			default -> 0xFFD7262D;
		};
	}


	// ------------------------------------------------------------------ spawning

	private void spawn(double dt, List<Target> targets) {
		spawnTimer -= dt;
		if (spawnTimer > 0 || dungeon != null) {
			return;
		}
		// calmer world (2026-11): fewer, slower spawns
		spawnTimer = 7 + random.nextDouble() * 6;
		long hostile = mobs.stream().filter(m -> !m.friendly()).count();
		if (hostile >= 1 + targets.size() || random.nextDouble() < 0.3) {
			return;
		}
		Target t = targets.get(random.nextInt(targets.size()));
		int surface = world.surface((int) Math.floor(t.x()));
		boolean underground = t.y() > surface + 6;
		int depth = (int) (t.y() - surface);
		for (int attempt = 0; attempt < 14; attempt++) {
			int side = random.nextBoolean() ? 1 : -1;
			int x = (int) Math.floor(t.x() + side * (15 + random.nextDouble() * 10));
			if (underground) {
				int y = (int) Math.floor(t.y() - 6 + random.nextDouble() * 10);
				if (level.get(x, y) != Items.AIR || level.get(x, y - 1) != Items.AIR || y <= world.surface(x) + 4) {
					continue;
				}
				boolean ground = solid(x, y + 1);
				if (ground && depth > 10 && random.nextDouble() < 0.07 && host.traderUnlocked() && mobs.stream().noneMatch(Mob::friendly)) {
					Mob trader = new Mob(nextId++, Actors.TRADER, x + 0.5, y + 1 - 1e-3);
					trader.life = 120;
					mobs.add(trader);
					host.announce("You hear someone humming in the dark...");
					return;
				}
				int kind = Actors.BAT;
				if (ground) {
					double r = random.nextDouble();
					if (world.cleared[2] && depth > 40 && r < 0.25) {
						kind = Actors.STALKER;
					} else if (world.cleared[1] && depth > 30 && r < 0.4) {
						kind = Actors.MAGMA_SLIME;
					} else if (world.cleared[0] && r < 0.5) {
						kind = Actors.SKELETON;
					} else if (r < 0.65) {
						kind = Actors.SLIME;
					}
				} else if (world.cleared[2] && random.nextDouble() < 0.25) {
					kind = Actors.VOID_WISP;
				}
				mobs.add(new Mob(nextId++, kind, x + 0.5, ground ? y + 1 - 1e-3 : y + 0.8));
				return;
			}
			int y = 1;
			while (y < World.HEIGHT - 1 && !solid(x, y)) {
				y++;
			}
			if (Math.abs(y - t.y()) > 14 || level.get(x, y - 1) != Items.AIR) {
				continue;
			}
			World.Structure st = world.structureAt(x);
			if (st != null && st.type() == World.VILLAGE) {
				continue;
			}
			int biome = world.biome(x);
			double r = random.nextDouble();
			int kind = biome == World.ASHLANDS ? Actors.SCUTTLER : biome == World.FROST ? Actors.FROST_WISP : Actors.SLIME;
			if (world.cleared[2] && r < 0.2) {
				kind = Actors.STALKER;
			} else if (world.cleared[1] && r < 0.4) {
				kind = Actors.IMP;
			} else if (world.cleared[0] && r < 0.6) {
				kind = Actors.REVENANT;
			}
			boolean flying = stats(kind)[4] == Actors.FLY;
			mobs.add(new Mob(nextId++, kind, x + 0.5, flying ? y - 3 : y - 1e-3));
			return;
		}
	}

	// ------------------------------------------------------------------ traps and ledges

	private void updateTraps(List<Target> targets) {
		if (dungeon == null) {
			return;
		}
		for (int i = 0; i < dungeon.traps.size(); i++) {
			int[] trap = dungeon.traps.get(i);
			double period = 2.4;
			double phase = (trapClock + i * 0.37) % period;
			if (phase > 0.02 && phase - 1.0 / 60 <= 0.02) {
				Target t = nearest(targets, trap[0], trap[1]);
				if (Math.hypot(t.x() - trap[0], t.y() - trap[1]) > 15) {
					continue;
				}
				double sx = trap[0] + 0.5 + trap[2] * 0.7;
				double sy = trap[1] + 0.5 + trap[3] * 0.7;
				Proj p = switch (dungeon.theme) {
					case CRYPT -> new Proj(Actors.ARROW, sx, sy, trap[2] * 12, 0, 2, true);
					case FOUNDRY -> new Proj(Actors.FIREBALL, sx, sy, (random.nextDouble() - 0.5) * 2, -13, 2, true);
					case SPIRE -> {
						double dx = t.x() - sx;
						double dy = t.y() - 1 - sy;
						double len = Math.max(0.1, Math.hypot(dx, dy));
						Proj orb = new Proj(Actors.ORB, sx, sy, dx / len * 4, dy / len * 4, 2, true);
						orb.homing = true;
						yield orb;
					}
				};
				if (p.kind == Actors.ARROW) {
					p.gravity = 0;
				}
				fire(p);
				host.sound(p.kind == Actors.ARROW ? "bow" : p.kind == Actors.FIREBALL ? "fire" : "orb", sx, sy);
			}
		}
	}

	private void updateCrumbles(double dt, List<Target> targets) {
		for (Target t : targets) {
			int fx = (int) Math.floor(t.x());
			int fy = (int) Math.floor(t.y() + 0.05);
			if (level.get(fx, fy) == Items.CRUMBLE && crumbles.stream().noneMatch(c -> c[0] == fx && c[1] == fy)) {
				crumbles.add(new double[] {fx, fy, 0.55, 0});
				host.sound("crumble", fx, fy);
			}
		}
		for (Iterator<double[]> it = crumbles.iterator(); it.hasNext(); ) {
			double[] c = it.next();
			c[2] -= dt;
			if (c[2] <= 0 && c[3] == 0) {
				host.tile(level, (int) c[0], (int) c[1], Items.AIR);
				host.burst(c[0] + 0.5, c[1] + 0.5, 0xFF57515C, 8);
				c[3] = 1;
				c[2] = 4;
			} else if (c[2] <= 0) {
				host.tile(level, (int) c[0], (int) c[1], Items.CRUMBLE);
				it.remove();
			}
		}
	}

	/** Shaking crumbling ledges (for the renderer). */
	boolean crumbling(int x, int y) {
		for (double[] c : crumbles) {
			if (c[0] == x && c[1] == y && c[3] == 0) {
				return true;
			}
		}
		return false;
	}

	private void updateDungeon(List<Target> targets) {
		if (dungeon == null) {
			return;
		}
		if (dungeon.riddle == Dungeon.Riddle.BRAZIERS && !dungeon.doorOpen()) {
			int lit = dungeon.progress();
			if (lit > 0 && lit < dungeon.puzzle.size()) {
				brazierClock += 1 / 60.0;
				if (brazierClock > 35) {
					brazierClock = 0;
					for (int[] b : dungeon.puzzle) {
						host.tile(level, b[0], b[1], Items.BRAZIER);
						host.burst(b[0] + 0.5, b[1] + 0.3, 0xFF57515C, 8);
					}
					host.sound("rune_wrong", dungeon.puzzle.get(0)[0], dungeon.puzzle.get(0)[1]);
				}
			} else {
				brazierClock = 0;
			}
		}
		if (!dungeon.doorOpen() && dungeon.solved()) {
			for (int[] d : dungeon.doors) {
				host.tile(level, d[0], d[1], d[2]);
				host.burst(d[0] + 0.5, d[1] + 0.5, 0xFF8A1218, 6);
			}
			host.sound("unseal", dungeon.doors.get(0)[0], dungeon.doors.get(0)[1]);
			host.shake(8, dungeon.doors.get(0)[0], dungeon.doors.get(0)[1]);

		}
		if (!dungeon.bossDefeated && boss == null) {
			for (Target t : targets) {
				if (dungeon.inArena(t.x(), t.y())) {
					int kind = Actors.WARDEN + dungeon.index;
					boss = new Mob(nextId++, kind, dungeon.bossX, kind == Actors.HERALD ? dungeon.bossY - 6 : dungeon.bossY);
					int extra = Math.max(0, targets.size() - 1);
					boss.maxHp = boss.hp = boss.hp + extra * boss.hp / 2;
					boss.move = 0;
					boss.timer = 2.2;
					boss.tele = 1.6;
					mobs.add(boss);
					if (!bossIntroduced) {
						bossIntroduced = true;
						host.announce(dungeon.theme.boss.toUpperCase());
					}
					host.sound("roar", boss.x, boss.y);
					host.shake(10, boss.x, boss.y);
					if (kind == Actors.HERALD) {
						spawnCrystals(boss);
					}
					break;
				}
			}
		}
	}

	// ------------------------------------------------------------------ bosses

	private static final int IDLE = 0, CHARGE = 1, LEAP = 2, VOLLEY = 3, ERUPT = 4, SLAM = 5, BARRAGE = 6, TELEPORT = 7, RING = 8,
			HOMING = 9, SUMMON = 10, DIVE = 11, STUN = 12;

	private void boss(Mob b, Target t, double dt) {
		b.moveT += dt;
		if (b.kind == Actors.GOLEM && b.vent > 0) {
			b.vent -= dt;
			if (random.nextDouble() < dt * 14) {
				host.burst(b.x + (random.nextDouble() - 0.5) * 2, b.y - b.tall() * 0.55, 0xFFE8E0E0, 2);
			}
		}
		if (b.kind == Actors.HERALD && crystalsAlive() == 0 && b.move != -1) {
			crystalTimer -= dt;
			if (crystalTimer <= 0) {
				spawnCrystals(b);
			}
		}
		if (b.phase > 1 && b.move != -1) {
			minionClock += dt;
			if (minionClock > (b.kind == Actors.HERALD ? 9 : 11)) {
				minionClock = 0;
				summonMinions(b);
			}
		}
		if (b.move == -1) {
			// enraged roar: stands, glows, then on with it
			b.vx = 0;
			if (b.kind != Actors.HERALD) {
				physics(b, dt);
			}
			if (b.moveT > 1.4) {
				b.move = IDLE;
				b.moveT = 0;
				b.timer = 0.6;
				if (b.kind == Actors.WARDEN) {
					for (int i = 0; i < 2; i++) {
						mobs.add(new Mob(nextId++, Actors.SKELETON, b.x + (i == 0 ? -4 : 4), b.y));
					}
				}
			}
			return;
		}
		switch (b.kind) {
			case Actors.WARDEN -> warden(b, t, dt);
			case Actors.GOLEM -> golem(b, t, dt);
			default -> herald(b, t, dt);
		}
	}

	private double minionClock;
	private double brazierClock;
	private double crystalTimer;

	int crystalsAlive() {
		int n = 0;
		for (Mob m : mobs) {
			if (m.kind == Actors.VOID_CRYSTAL) {
				n++;
			}
		}
		return n;
	}

	/** The Herald's shield: void crystals floating around the arena. */
	private void spawnCrystals(Mob herald) {
		if (dungeon == null) {
			return;
		}
		int n = herald.phase > 1 ? 4 : 3;
		for (int i = 0; i < n; i++) {
			double x = dungeon.arenaX0 + 3 + (dungeon.arenaX1 - dungeon.arenaX0 - 6) * (i + 0.5) / n;
			double y = dungeon.arenaY1 - 2 - (i % 2) * 5;
			Mob c = new Mob(nextId++, Actors.VOID_CRYSTAL, x, y);
			c.ny = y;
			mobs.add(c);
			host.burst(x, y - 0.7, 0xFFB48CFF, 14);
		}
		host.sound("summon", herald.x, herald.y);
	}

	/** Phase two: the boss calls its own kind of help (never more than three at once). */
	private void summonMinions(Mob b) {
		int kind = b.kind == Actors.WARDEN ? Actors.SKELETON : b.kind == Actors.GOLEM ? Actors.MAGMA_SLIME : Actors.VOID_WISP;
		long alive = mobs.stream().filter(m -> m.kind == kind).count();
		for (int i = 0; i < 2 && alive + i < 3; i++) {
			double side = i == 0 ? -1 : 1;
			Mob minion = new Mob(nextId++, kind, b.x + side * 3, kind == Actors.VOID_WISP ? b.y - 2 : b.y - 1e-3);
			minion.hurt = 0;
			mobs.add(minion);
			host.burst(minion.x, minion.y - 1, kind == Actors.SKELETON ? 0xFFE8DEC8 : kind == Actors.MAGMA_SLIME ? 0xFFFF8A2E : 0xFFB48CFF, 16);
		}
		host.sound("summon", b.x, b.y);
	}

	private void begin(Mob b, int move) {
		b.move = move;
		b.moveT = 0;
	}

	private void warden(Mob b, Target t, double dt) {
		double dx = t.x() - b.x;
		switch (b.move) {
			case IDLE -> {
				b.vx += (Math.signum(dx) * b.speed() - b.vx) * Math.min(1, dt * 5);
				b.timer -= dt;
				if (b.timer <= 0 && b.onGround) {
					double r = random.nextDouble();
					begin(b, Math.abs(dx) > 7 && r < 0.45 ? CHARGE : r < 0.75 ? LEAP : VOLLEY);
					b.tele = b.move == CHARGE ? 0.7 : 0.5;
					host.sound("telegraph", b.x, b.y);
				}
			}
			case CHARGE -> {
				if (b.moveT < 0.7) {
					b.vx = 0;
					b.facing = dx >= 0 ? 1 : -1;
				} else {
					b.vx = b.facing * (b.phase > 1 ? 17 : 14);
					if (b.timer2 > 0 || b.moveT > 1.5) {
						b.timer2 = 0;
						if (b.moveT < 1.5) {
							host.shake(12, b.x, b.y);
							host.sound("slam", b.x, b.y);
							begin(b, STUN);
							b.vx = 0;
							break;
						}
						endMove(b);
					}
				}
			}
			case LEAP -> {
				if (b.moveT < 0.5) {
					b.vx = 0;
				} else if (b.moveT < 0.55 && b.onGround) {
					b.vy = -16;
					b.vx = clamp(dx * 1.1, -10, 10);
					b.onGround = false;
				} else if (b.onGround && b.moveT > 0.7) {
					host.shake(14, b.x, b.y);
					host.sound("slam", b.x, b.y);
					host.burst(b.x, b.y, 0xFFE8DEC8, 20);
					for (int s = -1; s <= 1; s += 2) {
						Proj w = new Proj(Actors.WAVE, b.x + s * 1.2, b.y - 0.05, s * (b.phase > 1 ? 11 : 9), 0, 3, true);
						fire(w);
					}
					b.vx = 0;
					endMove(b);
				}
			}
			case VOLLEY -> {
				b.vx = 0;
				int volleys = b.phase > 1 ? 2 : 1;
				for (int v = 0; v < volleys; v++) {
					double at = 0.55 + v * 0.55;
					if (b.moveT >= at && b.moveT - dt < at) {
						int n = b.phase > 1 ? 9 : 5;
						for (int i = 0; i < n; i++) {
							double spread = (i - (n - 1) / 2.0) * 0.16;
							double base = Math.atan2(-0.9, Math.signum(dx));
							double angle = base + spread;
							double speed = 9 + Math.min(6, Math.abs(dx) * 0.4);
							fire(new Proj(Actors.SHARD, b.x, b.y - b.tall() * 0.8, Math.cos(angle) * speed, Math.sin(angle) * speed, 2, true));
						}
						host.sound("volley", b.x, b.y);
					}
				}
				if (b.moveT > 0.6 + volleys * 0.55) {
					endMove(b);
				}
			}
			case STUN -> {
				b.vx = 0;
				if (b.moveT > (b.phase > 1 ? 0.6 : 1.0)) {
					endMove(b);
				}
			}
			default -> endMove(b);
		}
		physics(b, dt);
	}

	private void golem(Mob b, Target t, double dt) {
		double dx = t.x() - b.x;
		switch (b.move) {
			case IDLE -> {
				b.vx += (Math.signum(dx) * b.speed() - b.vx) * Math.min(1, dt * 4);
				b.timer -= dt;
				if (b.timer <= 0 && b.onGround) {
					double r = random.nextDouble();
					begin(b, Math.abs(dx) < 4 ? SLAM : r < 0.45 ? BARRAGE : r < 0.85 ? ERUPT : SLAM);
					b.tele = 0.6;
					host.sound("telegraph", b.x, b.y);
				}
			}
			case BARRAGE -> {
				b.vx = 0;
				int n = b.phase > 1 ? 7 : 4;
				for (int i = 0; i < n; i++) {
					double at = 0.5 + i * 0.18;
					if (b.moveT >= at && b.moveT - dt < at) {
						double aim = t.x() + (random.nextDouble() - 0.5) * 6;
						double flight = 1.1;
						double vx = (aim - b.x) / flight;
						double vy = ((t.y() - 1) - (b.y - b.tall())) / flight - 0.5 * 16 * flight;
						fire(new Proj(Actors.FIREBALL, b.x, b.y - b.tall(), vx, vy, 3, true));
						host.sound("fire", b.x, b.y);
					}
				}
				if (b.moveT > 0.6 + n * 0.18) {
					endMove(b);
				}
			}
			case ERUPT -> {
				b.vx = 0;
				if (b.moveT < dt * 1.5) {
					// remember where the pillars come up (marked on the floor while it charges)
					b.nx = t.x();
				}
				if (b.moveT >= 0.9 && b.moveT - dt < 0.9) {
					int n = b.phase > 1 ? 5 : 3;
					for (int i = 0; i < n; i++) {
						double px = b.nx + (i - (n - 1) / 2.0) * 2.6;
						fire(new Proj(Actors.PILLAR, px, b.y, 0, 0, 3, true));
					}
					host.shake(10, b.x, b.y);
					host.sound("erupt", b.x, b.y);
				}
				if (b.moveT > 1.8) {
					endMove(b);
					vent(b);
				}
			}
			case SLAM -> {
				b.vx = 0;
				if (b.moveT >= 0.7 && b.moveT - dt < 0.7) {
					host.shake(16, b.x, b.y);
					host.sound("slam", b.x, b.y);
					for (int s = -1; s <= 1; s += 2) {
						fire(new Proj(Actors.WAVE, b.x + s * 1.5, b.y - 0.05, s * 10, 0, 4, true));
					}
					if (b.phase > 1) {
						for (int i = 0; i < 8; i++) {
							double a = Math.PI + i * Math.PI / 7;
							fire(new Proj(Actors.FIREBALL, b.x, b.y - b.tall() * 0.6, Math.cos(a) * 8, Math.sin(a) * 8, 2, true));
						}
					}
				}
				if (b.moveT > 1.3) {
					endMove(b);
					vent(b);
				}
			}
			default -> endMove(b);
		}
		physics(b, dt);
	}

	private void vent(Mob b) {
		b.vent = b.phase > 1 ? 2.0 : 2.8;
		b.timer = Math.max(b.timer, b.vent);
		host.sound("vent", b.x, b.y);
		host.text(b.x, b.y - b.tall() - 0.5, "CORE EXPOSED");
	}

	private void herald(Mob b, Target t, double dt) {
		double hx = t.x() + Math.sin(b.anim * 0.8) * 6;
		double hy = t.y() - 5 + Math.sin(b.anim * 1.6) * 1.2;
		switch (b.move) {
			case IDLE -> {
				b.vx += ((hx - b.x) * 1.5 - b.vx) * Math.min(1, dt * 2);
				b.vy += ((hy - b.y) * 1.5 - b.vy) * Math.min(1, dt * 2);
				b.timer -= dt;
				if (b.timer <= 0) {
					double r = random.nextDouble();
					long minions = mobs.stream().filter(m -> m.kind == Actors.VOID_WISP).count();
					begin(b, r < 0.25 ? TELEPORT : r < 0.5 ? RING : r < 0.72 ? HOMING : r < 0.85 && minions < 3 ? SUMMON : DIVE);
					b.tele = 0.6;
					host.sound("telegraph", b.x, b.y);
				}
			}
			case TELEPORT -> {
				b.vx *= 0.8;
				b.vy *= 0.8;
				if (b.moveT >= 0.45 && b.moveT - dt < 0.45 && dungeon != null) {
					host.burst(b.x, b.y - b.tall() / 2, 0xFF6E3FD0, 20);
					b.x = dungeon.arenaX0 + 3 + random.nextDouble() * (dungeon.arenaX1 - dungeon.arenaX0 - 6);
					b.y = dungeon.arenaY0 + 4 + random.nextDouble() * 5;
					host.sound("teleport", b.x, b.y);
					host.burst(b.x, b.y - b.tall() / 2, 0xFFB48CFF, 20);
				}
				if (b.moveT > 0.9) {
					begin(b, RING);
				}
			}
			case RING -> {
				b.vx *= 0.9;
				b.vy *= 0.9;
				int rings = b.phase > 1 ? 3 : 1;
				for (int k = 0; k < rings; k++) {
					double at = 0.6 + k * 0.45;
					if (b.moveT >= at && b.moveT - dt < at) {
						int n = b.phase > 1 ? 16 : 12;
						for (int i = 0; i < n; i++) {
							double a = i * Math.PI * 2 / n + k * 0.2;
							fire(new Proj(Actors.ORB, b.x, b.y - b.tall() / 2, Math.cos(a) * 5.5, Math.sin(a) * 5.5, 3, true));
						}
						host.sound("orb", b.x, b.y);
					}
				}
				if (b.moveT > 0.8 + rings * 0.45) {
					endMove(b);
				}
			}
			case HOMING -> {
				b.vx *= 0.9;
				b.vy *= 0.9;
				int n = b.phase > 1 ? 5 : 3;
				for (int i = 0; i < n; i++) {
					double at = 0.5 + i * 0.25;
					if (b.moveT >= at && b.moveT - dt < at) {
						Proj p = shootAt(b, t, Actors.ORB, 4, 3);
						p.homing = true;
						p.life = 7;
					}
				}
				if (b.moveT > 0.7 + n * 0.25) {
					endMove(b);
				}
			}
			case SUMMON -> {
				b.vx *= 0.9;
				b.vy *= 0.9;
				if (b.moveT >= 0.8 && b.moveT - dt < 0.8) {
					for (int i = 0; i < 2; i++) {
						mobs.add(new Mob(nextId++, Actors.VOID_WISP, b.x + (i == 0 ? -2 : 2), b.y - 1));
					}
					host.sound("summon", b.x, b.y);
				}
				if (b.moveT > 1.2) {
					endMove(b);
				}
			}
			case DIVE -> {
				if (b.moveT < 0.6) {
					b.vx *= 0.85;
					b.vy = -2;
				} else if (b.moveT < 0.62) {
					double dx = t.x() - b.x;
					double dy = (t.y() - 1) - b.y;
					double len = Math.max(0.1, Math.hypot(dx, dy));
					b.vx = dx / len * (b.phase > 1 ? 22 : 18);
					b.vy = dy / len * (b.phase > 1 ? 22 : 18);
					host.sound("dive", b.x, b.y);
				} else if (b.moveT > 1.1) {
					b.vx *= 0.3;
					b.vy *= 0.3;
					endMove(b);
				}
			}
			default -> endMove(b);
		}
		double nx = b.x + b.vx * dt;
		double ny = b.y + b.vy * dt;
		if (dungeon != null) {
			nx = clamp(nx, dungeon.arenaX0 + 1.2, dungeon.arenaX1 - 0.2);
			ny = clamp(ny, dungeon.arenaY0 + b.tall() + 0.2, dungeon.arenaY1 + 0.9);
		}
		b.x = nx;
		b.y = ny;
	}

	private void endMove(Mob b) {
		b.move = IDLE;
		b.moveT = 0;
		b.timer2 = 0;
		b.timer = (b.phase > 1 ? 0.9 : 1.6) + random.nextDouble() * 0.8;
	}
}
