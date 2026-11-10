package net.veloclient.launcher.sandbox;

/** The moving things of Velo World: monsters (and the map trader), bosses, and projectiles. */
final class Actors {

	private Actors() {
	}

	// monster kinds
	static final int SLIME = 0, BAT = 1, SCUTTLER = 2, FROST_WISP = 3, REVENANT = 4, SKELETON = 5, IMP = 6, STALKER = 7,
			VOID_WISP = 8, MAGMA_SLIME = 9, CRYPT_SLIME = 10, TRADER = 11, VILLAGER = 12, BANDIT = 13, BANDIT_ARCHER = 14, VOID_CRYSTAL = 15, WARDEN = 20, GOLEM = 21, HERALD = 22;

	static final int HOP = 0, FLY = 1, WALK = 2, STAND = 3, BOSS = 4, WANDER = 5, FLOAT = 6;

	/** {hp, damage, halfWidth*100, height*100, behaviour, speed*10} */
	static int[] stats(int kind) {
		return switch (kind) {
			case SLIME -> new int[] {6, 1, 45, 70, HOP, 30};
			case CRYPT_SLIME -> new int[] {12, 2, 45, 70, HOP, 34};
			case MAGMA_SLIME -> new int[] {18, 3, 50, 75, HOP, 36};
			case BAT -> new int[] {4, 1, 32, 45, FLY, 42};
			case FROST_WISP -> new int[] {7, 1, 30, 50, FLY, 38};
			case VOID_WISP -> new int[] {20, 3, 32, 55, FLY, 46};
			case IMP -> new int[] {14, 2, 34, 70, FLY, 40};
			case SCUTTLER -> new int[] {8, 2, 55, 50, WALK, 52};
			case REVENANT -> new int[] {16, 2, 32, 180, WALK, 30};
			case SKELETON -> new int[] {14, 2, 30, 180, WALK, 26};
			case STALKER -> new int[] {26, 3, 32, 190, WALK, 40};
			case TRADER -> new int[] {999, 0, 34, 185, STAND, 0};
			case VILLAGER -> new int[] {999, 0, 32, 180, WANDER, 14};
			case BANDIT -> new int[] {18, 3, 32, 180, WALK, 46};
			case BANDIT_ARCHER -> new int[] {14, 2, 30, 180, WALK, 28};
			case VOID_CRYSTAL -> new int[] {14, 0, 40, 140, FLOAT, 0};
			case WARDEN -> new int[] {170, 3, 90, 320, BOSS, 22};
			case GOLEM -> new int[] {240, 4, 120, 360, BOSS, 14};
			case HERALD -> new int[] {280, 4, 80, 230, BOSS, 50};
			default -> new int[] {5, 1, 40, 60, HOP, 30};
		};
	}

	static String name(int kind) {
		return switch (kind) {
			case WARDEN -> "Crypt Warden";
			case GOLEM -> "Furnace Golem";
			case HERALD -> "Void Herald";
			case TRADER -> "Map Trader";
			case VILLAGER -> "Villager";
			default -> "";
		};
	}

	static final class Mob {
		int id;
		final int kind;
		double x;
		double y;
		double vx;
		double vy;
		int hp;
		int maxHp;
		boolean onGround;
		double timer;
		double timer2;
		double hurt;
		double anim;
		int facing = 1;
		/** Bosses: phase (1 or 2), the current move and how long it has run. */
		int phase = 1;
		int move;
		double moveT;
		double tele;
		/** Network: where the host last said it is (guests glide towards it). */
		double nx;
		double ny;
		double life = Double.MAX_VALUE;
		/** Villagers and bandits stay around their home column. */
		double home = Double.NaN;
		/** Golem: seconds its core stays exposed after a slam. */
		double vent;
		/** Which structure (its left edge) spawned it. */
		double timer2Home;

		Mob(int id, int kind, double x, double y) {
			this.id = id;
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.nx = x;
			this.ny = y;
			int[] s = stats(kind);
			this.hp = s[0];
			this.maxHp = s[0];
		}

		double halfW() {
			return stats(kind)[2] / 100.0;
		}

		double tall() {
			return stats(kind)[3] / 100.0;
		}

		int damage() {
			return stats(kind)[1] + (phase > 1 ? 1 : 0);
		}

		int behaviour() {
			return stats(kind)[4];
		}

		double speed() {
			return stats(kind)[5] / 10.0 * (phase > 1 ? 1.35 : 1);
		}

		boolean boss() {
			return kind >= WARDEN;
		}

		boolean friendly() {
			return kind == TRADER || kind == VILLAGER;
		}
	}

	// projectile kinds
	static final int ARROW = 0, FIREBALL = 1, ORB = 2, SHARD = 3, WAVE = 4, PLAYER_ARROW = 5, PILLAR = 6;

	static final class Proj {
		int kind;
		double x;
		double y;
		double vx;
		double vy;
		double gravity;
		double life;
		int damage;
		boolean hostile;
		boolean homing;
		double age;

		Proj(int kind, double x, double y, double vx, double vy, int damage, boolean hostile) {
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.vx = vx;
			this.vy = vy;
			this.damage = damage;
			this.hostile = hostile;
			this.life = kind == PILLAR ? 0.9 : kind == WAVE ? 1.6 : kind == ORB ? 6 : 4;
			this.gravity = switch (kind) {
				case ARROW, PLAYER_ARROW -> 4;
				case FIREBALL -> 16;
				case SHARD -> 14;
				default -> 0;
			};
		}

		double radius() {
			return switch (kind) {
				case PILLAR -> 0.6;
				case WAVE -> 0.5;
				case ORB -> 0.32;
				case FIREBALL -> 0.3;
				default -> 0.18;
			};
		}

		/** Pillars and waves are tall: their hit box reaches up this far. */
		double reach() {
			return kind == PILLAR ? 3.2 : kind == WAVE ? 1.1 : 0;
		}
	}
}
