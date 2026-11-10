package net.veloclient.launcher.sandbox;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Every block and item of Velo World: ids, rules (hardness, tool tiers, damage, armor), crafting, and the 16x16 pixel
 * art - all drawn here in code in the launcher's dusk palette. Ids 0-63 are blocks (placeable), 64+ are items.
 */
final class Items {

	// ---- blocks ----
	static final int AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, LOG = 4, LEAVES = 5, PLANKS = 6, COAL_ORE = 7, IRON_ORE = 8,
			RUBY_ORE = 9, BENCH = 10, TORCH = 11, BEDROCK = 12, BRICK = 13, TRUNK = 14, KEY_FRAG = 15, BRICK_CRYPT = 16,
			BRICK_FOUNDRY = 17, BRICK_SPIRE = 18, PLATFORM = 19, SPIKES = 20, LAVA = 21, DOOR = 22, LEVER = 23, LEVER_ON = 24,
			RUNE = 25, RUNE_LIT = 26, CHEST = 27, CHEST_OPEN = 28, CRUMBLE = 29, GATE = 30, TRAP = 31, SAND = 32, SANDSTONE = 33,
			SNOW = 34, ICE = 35, BLOOD_ORE = 36, EMBER_ORE = 37, AETHER_ORE = 38, ANVIL = 39, SHALE = 40, THORN = 41, ROOF = 42, CLOTH = 43, BRAZIER = 44, BRAZIER_LIT = 45, CRATE = 46, PLATE = 47, GLYPH = 48;
	/** GLYPH .. GLYPH + 3: a tablet showing symbol 0-3 (hitting it turns it to the next). */
	static final int GLYPH_LAST = 51;
	/** Flowing water (see {@link Liquids}). */
	static final int WATER = 52;
	// ---- materials ----
	static final int STICK = 64, COAL = 65, IRON = 66, RUBY = 67, GEL = 68, BLOODSTONE = 69, EMBERITE = 70, AETHERITE = 71,
			SINEW = 72, ARROW = 73, WARDEN_SIGIL = 74, GOLEM_CORE = 75, HERALD_EYE = 76, MAP_CRYPT = 77, MAP_FOUNDRY = 78,
			MAP_SPIRE = 79, HEAL_POTION = 80, SWIFT_ELIXIR = 81, STRENGTH_TONIC = 82, HEART_CRYSTAL = 83, FEATHER = 84;
	// ---- gear ----
	static final int WOOD_PICK = 96, STONE_PICK = 97, IRON_PICK = 98, RUBY_PICK = 99, BLOOD_PICK = 100, EMBER_PICK = 101;
	static final int WOOD_SWORD = 104, STONE_SWORD = 105, IRON_SWORD = 106, RUBY_SWORD = 107, BLOOD_SWORD = 108, EMBER_SWORD = 109,
			AETHER_SWORD = 110, CRYPT_BLADE = 111, MAGMA_MAUL = 112, VOID_EDGE = 113;
	static final int WOOD_BOW = 116, BONE_BOW = 117, EMBER_BOW = 118, VOID_BOW = 119;
	static final int IRON_ARMOR = 120, RUBY_ARMOR = 121, BLOOD_ARMOR = 122, EMBER_ARMOR = 123, AETHER_ARMOR = 124;

	/** Everything known about one id. */
	static final class Def {
		String name = "?";
		int maxStack = 99;
		double hardness = -1;
		int needsTier;
		int pickTier;
		int damage = 1;
		int bow;
		int defense;
		int drop = -1;
		double glow;
		boolean solid;
		boolean clear;
		int tone = 0xFF57515C;
		String text = "";
	}

	private static final Def[] DEFS = new Def[256];

	private static Def def(int id, String name) {
		Def d = new Def();
		d.name = name;
		DEFS[id] = d;
		return d;
	}

	private static Def block(int id, String name, double hardness, int tier, int tone) {
		Def d = def(id, name);
		d.hardness = hardness;
		d.needsTier = tier;
		d.solid = true;
		d.tone = tone;
		return d;
	}

	static {
		def(AIR, "Air").clear = true;
		block(GRASS, "Grass", 0.45, 0, 0xFF4E5D33).drop = DIRT;
		block(DIRT, "Dirt", 0.45, 0, 0xFF543627);
		block(STONE, "Stone", 1.4, 1, 0xFF57515C);
		block(SHALE, "Shale", 2.0, 1, 0xFF34303A).drop = STONE;
		block(LOG, "Log", 1.1, 0, 0xFF7E5234);
		Def trunk = block(TRUNK, "Log", 1.1, 0, 0xFF7E5234);
		trunk.solid = false;
		trunk.clear = true;
		trunk.drop = LOG;
		Def leaves = block(LEAVES, "Leaves", 0.15, 0, 0xFF7A1F26);
		leaves.solid = false;
		leaves.clear = true;
		block(PLANKS, "Planks", 0.8, 0, 0xFF8A5A3A);
		block(COAL_ORE, "Coal Ore", 1.7, 1, 0xFF3A3540).drop = COAL;
		block(IRON_ORE, "Iron Ore", 2.1, 2, 0xFFC9A27E).drop = IRON;
		Def ruby = block(RUBY_ORE, "Ruby Ore", 2.6, 3, 0xFFD7262D);
		ruby.drop = RUBY;
		ruby.glow = 0.55;
		Def blood = block(BLOOD_ORE, "Bloodstone Ore", 3.0, 4, 0xFF8E1B3A);
		blood.drop = BLOODSTONE;
		blood.glow = 0.4;
		Def ember = block(EMBER_ORE, "Emberite Ore", 3.4, 5, 0xFFFF7A2E);
		ember.drop = EMBERITE;
		ember.glow = 0.7;
		Def aether = block(AETHER_ORE, "Aetherite Ore", 3.8, 6, 0xFFB48CFF);
		aether.drop = AETHERITE;
		aether.glow = 0.75;
		Def bench = block(BENCH, "Workbench", 0.8, 0, 0xFF7E5234);
		bench.solid = false;
		bench.clear = true;
		Def anvil = block(ANVIL, "Anvil", 1.5, 0, 0xFF5C5560);
		anvil.solid = false;
		anvil.clear = true;
		Def torch = block(TORCH, "Torch", 0.05, 0, 0xFFFFB347);
		torch.solid = false;
		torch.clear = true;
		torch.glow = 1;
		block(BEDROCK, "Bedrock", -1, 0, 0xFF1B1719).drop = 0;
		block(BRICK, "Stone Bricks", 1.4, 1, 0xFF5C5560);
		block(SAND, "Sand", 0.4, 0, 0xFFC9A46A);
		block(SANDSTONE, "Sandstone", 1.2, 1, 0xFFB08A55);
		block(SNOW, "Snow", 0.35, 0, 0xFFE8EEF2).drop = SNOW;
		block(ICE, "Ice", 0.9, 1, 0xFF9CC7E0);
		block(ROOF, "Roof Tiles", 1.0, 0, 0xFF8A2A22);
		Def cloth = block(CLOTH, "Cloth", 0.3, 0, 0xFF8E1A26);
		cloth.solid = false;
		cloth.clear = true;
		for (int id : new int[] {BRAZIER, BRAZIER_LIT, GLYPH, GLYPH + 1, GLYPH + 2, GLYPH + 3}) {
			Def d = block(id, id >= GLYPH ? "Tablet" : "Brazier", -1, 0, 0xFF5C5560);
			d.solid = false;
			d.clear = true;
			d.drop = 0;
		}
		DEFS[BRAZIER_LIT].glow = 1;
		DEFS[BRAZIER_LIT].tone = 0xFFFF8A2E;
		block(CRATE, "Crate", -1, 0, 0xFF8A5A3A).drop = 0;
		block(PLATE, "Pressure Plate", -1, 0, 0xFF6E6772).drop = 0;
		Def thorn = block(THORN, "Thornstalk", 0.6, 0, 0xFF5F7A3A);
		thorn.solid = false;
		thorn.clear = true;
		thorn.drop = STICK;
		// dungeon blocks (unbreakable)
		block(BRICK_CRYPT, "Crypt Brick", -1, 0, 0xFF6B6266).drop = 0;
		block(BRICK_FOUNDRY, "Foundry Plate", -1, 0, 0xFF5A3A30).drop = 0;
		block(BRICK_SPIRE, "Spire Stone", -1, 0, 0xFF2E2440).drop = 0;
		block(DOOR, "Sealed Door", -1, 0, 0xFF3A3540).drop = 0;
		block(TRAP, "Trap", -1, 0, 0xFF3A3540).drop = 0;
		block(CRUMBLE, "Crumbling Stone", -1, 0, 0xFF57515C).drop = 0;
		Def platform = block(PLATFORM, "Platform", 0.6, 0, 0xFF8A5A3A);
		platform.solid = false;
		platform.clear = true;
		for (int id : new int[] {SPIKES, LAVA, WATER, LEVER, LEVER_ON, RUNE, RUNE_LIT, CHEST, CHEST_OPEN, GATE, KEY_FRAG}) {
			Def d = block(id, "", -1, 0, 0xFF57515C);
			d.solid = false;
			d.clear = true;
			d.drop = 0;
		}
		DEFS[SPIKES].name = "Spikes";
		DEFS[LAVA].name = "Lava";
		DEFS[LAVA].glow = 0.9;
		DEFS[LAVA].tone = 0xFFFF6A2A;
		DEFS[WATER].name = "Water";
		DEFS[WATER].tone = 0xFF3A7BD5;
		DEFS[LEVER].name = "Lever";
		DEFS[LEVER_ON].name = "Lever";
		DEFS[RUNE].name = "Rune";
		DEFS[RUNE_LIT].name = "Rune";
		DEFS[RUNE_LIT].glow = 0.6;
		DEFS[CHEST].name = "Chest";
		DEFS[CHEST_OPEN].name = "Chest";
		DEFS[GATE].name = "Gate";
		DEFS[GATE].glow = 0.9;
		DEFS[KEY_FRAG].name = "Key Fragment";
		DEFS[KEY_FRAG].glow = 0.8;

		def(STICK, "Stick");
		def(COAL, "Coal");
		def(IRON, "Iron");
		def(RUBY, "Ruby");
		def(GEL, "Gel");
		def(BLOODSTONE, "Bloodstone").text = "Deep ore that appears once the Crypt Warden fell";
		def(EMBERITE, "Emberite").text = "Still warm. Appears once the Furnace Golem fell";
		def(AETHERITE, "Aetherite").text = "Hums quietly. Appears once the Void Herald fell";
		def(SINEW, "Sinew");
		def(ARROW, "Arrow");
		def(WARDEN_SIGIL, "Warden Sigil").text = "From the Crypt Warden - Bloodstone gear needs it";
		def(GOLEM_CORE, "Golem Core").text = "From the Furnace Golem - Emberite gear needs it";
		def(HERALD_EYE, "Herald Eye").text = "From the Void Herald - Aetherite gear needs it";
		for (int map : new int[] {MAP_CRYPT, MAP_FOUNDRY, MAP_SPIRE}) {
			def(map, "").maxStack = 1;
			DEFS[map].text = "Carry it: the way to the dungeon shows at the top";
		}
		DEFS[MAP_CRYPT].name = "Map: Crimson Crypt";
		DEFS[MAP_FOUNDRY].name = "Map: Sunken Foundry";
		DEFS[MAP_SPIRE].name = "Map: Void Spire";
		def(HEAL_POTION, "Healing Potion").maxStack = 9;
		DEFS[HEAL_POTION].text = "Right click: +8 health";
		def(SWIFT_ELIXIR, "Swift Elixir").maxStack = 9;
		DEFS[SWIFT_ELIXIR].text = "Right click: faster for 60 s";
		def(STRENGTH_TONIC, "Strength Tonic").maxStack = 9;
		DEFS[STRENGTH_TONIC].text = "Right click: hit harder for 60 s";
		def(HEART_CRYSTAL, "Heart Crystal").maxStack = 9;
		DEFS[HEART_CRYSTAL].text = "Right click: +2 max health (up to 30)";
		def(FEATHER, "Feather Charm").maxStack = 1;
		DEFS[FEATHER].text = "Carry it: jump again in the air";

		pick(WOOD_PICK, "Wooden Pickaxe", 1);
		pick(STONE_PICK, "Stone Pickaxe", 2);
		pick(IRON_PICK, "Iron Pickaxe", 3);
		pick(RUBY_PICK, "Ruby Pickaxe", 4);
		pick(BLOOD_PICK, "Bloodstone Pickaxe", 5);
		pick(EMBER_PICK, "Emberite Pickaxe", 6);
		sword(WOOD_SWORD, "Wooden Sword", 2);
		sword(STONE_SWORD, "Stone Sword", 3);
		sword(IRON_SWORD, "Iron Sword", 5);
		sword(RUBY_SWORD, "Ruby Sword", 7);
		sword(CRYPT_BLADE, "Crypt Blade", 9);
		sword(BLOOD_SWORD, "Bloodstone Sword", 11);
		sword(MAGMA_MAUL, "Magma Maul", 14);
		sword(EMBER_SWORD, "Emberite Sword", 15);
		sword(VOID_EDGE, "Void Edge", 18);
		sword(AETHER_SWORD, "Aetherite Sword", 21);
		bow(WOOD_BOW, "Wooden Bow", 3);
		bow(BONE_BOW, "Bone Bow", 7);
		bow(EMBER_BOW, "Emberite Bow", 11);
		bow(VOID_BOW, "Void Bow", 16);
		armor(IRON_ARMOR, "Iron Armor", 2);
		armor(RUBY_ARMOR, "Ruby Armor", 3);
		armor(BLOOD_ARMOR, "Bloodstone Armor", 5);
		armor(EMBER_ARMOR, "Emberite Armor", 7);
		armor(AETHER_ARMOR, "Aetherite Armor", 9);
	}

	private static void pick(int id, String name, int tier) {
		Def d = def(id, name);
		d.maxStack = 1;
		d.pickTier = tier;
		d.damage = 1 + tier;
	}

	private static void sword(int id, String name, int damage) {
		Def d = def(id, name);
		d.maxStack = 1;
		d.damage = damage;
	}

	private static void bow(int id, String name, int damage) {
		Def d = def(id, name);
		d.maxStack = 1;
		d.bow = damage;
		d.text = "Left click shoots an arrow · " + damage + " damage";
	}

	private static void armor(int id, String name, int defense) {
		Def d = def(id, name);
		d.maxStack = 1;
		d.defense = defense;
		d.text = "Right click to wear · " + defense + " defense";
	}

	private Items() {
	}

	private static Def d(int id) {
		Def d = id >= 0 && id < 256 ? DEFS[id] : null;
		return d != null ? d : DEFS[AIR];
	}

	static boolean known(int id) {
		return id > 0 && id < 256 && DEFS[id] != null;
	}

	static boolean isBlock(int id) {
		return id > 0 && id < 64;
	}

	static boolean solid(int id) {
		return d(id).solid;
	}

	static boolean clear(int id) {
		return d(id).clear;
	}

	static String name(int id) {
		return d(id).name;
	}

	static String text(int id) {
		return d(id).text;
	}

	static int maxStack(int id) {
		return d(id).maxStack;
	}

	static double hardness(int id) {
		return d(id).hardness;
	}

	static int needsTier(int id) {
		return d(id).needsTier;
	}

	static int pickTier(int id) {
		return d(id).pickTier;
	}

	static int damage(int id) {
		return d(id).damage;
	}

	static int bow(int id) {
		return d(id).bow;
	}

	static int defense(int id) {
		return d(id).defense;
	}

	static double glow(int id) {
		return d(id).glow;
	}

	static int tone(int id) {
		return d(id).tone;
	}

	static int drop(int id, SplittableRandom random) {
		if (id == LEAVES) {
			return random.nextDouble() < 0.12 ? STICK : 0;
		}
		int drop = d(id).drop;
		return drop < 0 ? id : drop;
	}

	// ------------------------------------------------------------------ crafting

	record Stack(int id, int count) {
	}

	/** Where a recipe is made: anywhere, next to a workbench, or next to an anvil. */
	enum Station {
		HAND, BENCH, ANVIL
	}

	record Recipe(Stack result, List<Stack> needs, Station station) {
	}

	private static Recipe r(int id, int count, Station station, int... needs) {
		java.util.List<Stack> list = new java.util.ArrayList<>();
		for (int i = 0; i + 1 < needs.length; i += 2) {
			list.add(new Stack(needs[i], needs[i + 1]));
		}
		return new Recipe(new Stack(id, count), List.copyOf(list), station);
	}

	static final List<Recipe> RECIPES = List.of(
			r(PLANKS, 4, Station.HAND, LOG, 1),
			r(STICK, 4, Station.HAND, PLANKS, 2),
			r(BENCH, 1, Station.HAND, PLANKS, 6),
			r(TORCH, 4, Station.HAND, COAL, 1, STICK, 1),
			r(TORCH, 2, Station.HAND, GEL, 1, STICK, 1),
			r(PLATFORM, 4, Station.HAND, PLANKS, 1),
			r(WOOD_PICK, 1, Station.BENCH, PLANKS, 3, STICK, 2),
			r(WOOD_SWORD, 1, Station.BENCH, PLANKS, 2, STICK, 1),
			r(STONE_PICK, 1, Station.BENCH, STONE, 3, STICK, 2),
			r(STONE_SWORD, 1, Station.BENCH, STONE, 2, STICK, 1),
			r(BRICK, 4, Station.BENCH, STONE, 4),
			r(WOOD_BOW, 1, Station.BENCH, STICK, 3, GEL, 2),
			r(ARROW, 10, Station.BENCH, STICK, 2, STONE, 1),
			r(HEAL_POTION, 1, Station.BENCH, GEL, 3, COAL, 1),
			r(ANVIL, 1, Station.BENCH, IRON, 5),
			r(IRON_PICK, 1, Station.ANVIL, IRON, 3, STICK, 2),
			r(IRON_SWORD, 1, Station.ANVIL, IRON, 2, STICK, 1),
			r(IRON_ARMOR, 1, Station.ANVIL, IRON, 8),
			r(RUBY_PICK, 1, Station.ANVIL, RUBY, 3, IRON, 2),
			r(RUBY_SWORD, 1, Station.ANVIL, RUBY, 3, IRON, 1),
			r(RUBY_ARMOR, 1, Station.ANVIL, RUBY, 6, IRON, 4),
			r(BLOOD_PICK, 1, Station.ANVIL, BLOODSTONE, 4, IRON, 2),
			r(BLOOD_SWORD, 1, Station.ANVIL, BLOODSTONE, 4, WARDEN_SIGIL, 1),
			r(BLOOD_ARMOR, 1, Station.ANVIL, BLOODSTONE, 8, WARDEN_SIGIL, 1),
			r(EMBER_PICK, 1, Station.ANVIL, EMBERITE, 4, BLOODSTONE, 2),
			r(EMBER_SWORD, 1, Station.ANVIL, EMBERITE, 4, GOLEM_CORE, 1),
			r(EMBER_ARMOR, 1, Station.ANVIL, EMBERITE, 8, GOLEM_CORE, 1),
			r(EMBER_BOW, 1, Station.ANVIL, EMBERITE, 3, SINEW, 2, GOLEM_CORE, 1),
			r(AETHER_SWORD, 1, Station.ANVIL, AETHERITE, 5, HERALD_EYE, 1),
			r(AETHER_ARMOR, 1, Station.ANVIL, AETHERITE, 10, HERALD_EYE, 1),
			r(VOID_BOW, 1, Station.ANVIL, AETHERITE, 4, SINEW, 2, HERALD_EYE, 1));

	// ------------------------------------------------------------------ pixel art

	private static final Map<Integer, Image> TEXTURES = new HashMap<>();
	private static final Map<Integer, Image> WALLS = new HashMap<>();
	private static final Map<Integer, Image> CANOPIES = new HashMap<>();
	private static final Map<Integer, Image> DECOR = new HashMap<>();
	private static Image[] cracks;

	static void clearCache() {
		TEXTURES.clear();
		WALLS.clear();
		CANOPIES.clear();
		DECOR.clear();
		cracks = null;
	}

	static Image icon(int id) {
		return TEXTURES.computeIfAbsent(id, k -> image(pixels(k, 0)));
	}

	/** Natural blocks come in three variants (picked by position) so the ground never looks tiled. */
	static Image tile(int id, int x, int y) {
		if (!varied(id)) {
			return icon(id);
		}
		int v = (int) (((x * 73856093L) ^ (y * 19349663L)) & 0x7fffffff) % 3;
		return v == 0 ? icon(id) : TEXTURES.computeIfAbsent(id + 1000 * v, k -> image(pixels(id, v)));
	}

	private static boolean varied(int id) {
		return switch (id) {
			case DIRT, GRASS, STONE, SHALE, SAND, SANDSTONE, SNOW, COAL_ORE, IRON_ORE, RUBY_ORE, BLOOD_ORE, EMBER_ORE, AETHER_ORE,
					BRICK_CRYPT, BRICK_FOUNDRY, BRICK_SPIRE, BRICK -> true;
			default -> false;
		};
	}

	/** The darker back wall behind dug-out ground / dungeon rooms, made from that block's texture. */
	static Image wall(int block) {
		return WALLS.computeIfAbsent(block, k -> {
			int[] px = pixels(k, 0);
			for (int i = 0; i < px.length; i++) {
				px[i] = (px[i] >>> 24) == 0 ? 0xFF0E0B0C : shade(px[i] | 0xFF000000, k >= BRICK_CRYPT && k <= BRICK_SPIRE ? 0.5 : 0.42);
			}
			return image(px);
		});
	}

	static Image crack(int stage) {
		if (cracks == null) {
			cracks = new Image[4];
			SplittableRandom random = new SplittableRandom(11);
			int[] px = new int[256];
			for (int s = 0; s < 4; s++) {
				for (int line = 0; line < 2 + s; line++) {
					int x = 8 + random.nextInt(-2, 3);
					int y = 8 + random.nextInt(-2, 3);
					for (int i = 0; i < 3 + s * 2; i++) {
						x = clamp(x + random.nextInt(-1, 2), 0, 15);
						y = clamp(y + random.nextInt(-1, 2), 0, 15);
						px[y * 16 + x] = 0xD0100808;
					}
				}
				cracks[s] = image(px.clone());
			}
		}
		return cracks[Math.max(0, Math.min(3, stage))];
	}

	/**
	 * Small decorations drawn on top of / under blocks (never blocks themselves): 0 grass tuft, 1 crimson flower,
	 * 2 dry bush, 3 bleached bones, 4 snow shrub, 5 ice crystal, 6 cave mushroom, 7 glow mushroom, 8 cave crystal,
	 * 9 stalactite (hangs), 10 hanging roots (hangs), 11 skull, 12 ember rock, 13 cobweb (corner).
	 */
	static Image decor(int kind) {
		return DECOR.computeIfAbsent(kind, k -> {
			int[] p = new int[256];
			SplittableRandom r = new SplittableRandom(77 + k);
			switch (k) {
				case 0 -> {
					for (int i = 0; i < 7; i++) {
						int x = 2 + r.nextInt(12);
						int h = 3 + r.nextInt(4);
						for (int y = 0; y < h; y++) {
							set(p, x + (y > 2 ? (i % 2 == 0 ? 1 : -1) : 0), 15 - y, y == h - 1 ? 0xFF8A9A55 : 0xFF5E6E3A);
						}
					}
				}
				case 1 -> {
					for (int y = 9; y < 16; y++) {
						set(p, 7, y, 0xFF4E5D33);
					}
					set(p, 6, 12, 0xFF5E6E3A);
					set(p, 5, 11, 0xFF5E6E3A);
					int[][] petals = {{7, 6}, {6, 7}, {8, 7}, {7, 8}, {6, 6}, {8, 6}, {6, 8}, {8, 8}};
					for (int[] pt : petals) {
						set(p, pt[0], pt[1], 0xFFD7262D);
					}
					set(p, 7, 7, 0xFFFFD27A);
					set(p, 6, 6, 0xFFFF6B70);
				}
				case 2 -> {
					for (int i = 0; i < 9; i++) {
						double a = Math.PI * (0.15 + i * 0.09);
						for (int s = 0; s < 6; s++) {
							set(p, 8 + (int) Math.round(Math.cos(a) * s * 1.2), 15 - (int) Math.round(Math.sin(a) * s), s > 3 ? 0xFFB09460 : 0xFF6E5630);
						}
					}
				}
				case 3 -> {
					line(p, 2, 14, 13, 13, 0xFFE8DEC8);
					line(p, 3, 15, 12, 14, 0xFFC8BEA8);
					set(p, 1, 13, 0xFFE8DEC8);
					set(p, 1, 15, 0xFFE8DEC8);
					set(p, 14, 12, 0xFFE8DEC8);
					set(p, 14, 14, 0xFFE8DEC8);
				}
				case 4 -> {
					for (int i = 0; i < 8; i++) {
						int x = 3 + r.nextInt(10);
						for (int y = 0; y < 3 + r.nextInt(4); y++) {
							set(p, x, 15 - y, y > 2 ? 0xFFFFFFFF : 0xFF4E6E78);
						}
					}
				}
				case 5 -> {
					for (int c = 0; c < 3; c++) {
						int x = 4 + c * 3;
						int h = 4 + r.nextInt(6);
						for (int y = 0; y < h; y++) {
							set(p, x, 15 - y, 0xFFB8E4FF);
							set(p, x + 1, 15 - y, y < h - 1 ? 0xFF6EA8D0 : 0);
						}
					}
				}
				case 6, 7 -> {
					boolean glow = k == 7;
					int cap = glow ? 0xFF6EF0D0 : 0xFFB3232B;
					for (int y = 11; y < 16; y++) {
						set(p, 7, y, 0xFFE8DEC8);
						set(p, 8, y, 0xFFC8BEA8);
					}
					for (int x = 4; x < 12; x++) {
						set(p, x, 10, cap);
						if (x > 4 && x < 11) {
							set(p, x, 9, cap);
						}
						if (x > 5 && x < 10) {
							set(p, x, 8, glow ? 0xFFB8FFF0 : 0xFFFF6B70);
						}
					}
					set(p, 6, 9, 0xFFFFFFFF);
				}
				case 8 -> {
					int[][] spikes = {{5, 7}, {8, 11}, {11, 6}};
					for (int[] s : spikes) {
						for (int y = 0; y < s[1]; y++) {
							int half = y < s[1] - 2 ? 1 : 0;
							for (int x = s[0] - half; x <= s[0] + half; x++) {
								set(p, x, 15 - y, x < s[0] ? 0xFFEADCFF : 0xFF8A5AE0);
							}
						}
					}
				}
				case 9 -> {
					for (int y = 0; y < 12; y++) {
						int half = Math.max(0, (11 - y) / 4);
						for (int x = 7 - half; x <= 8 + half; x++) {
							set(p, x, y, x < 8 ? 0xFF68616E : 0xFF48424C);
						}
					}
				}
				case 10 -> {
					for (int i = 0; i < 5; i++) {
						int x = 2 + i * 3;
						int len = 5 + r.nextInt(9);
						for (int y = 0; y < len; y++) {
							set(p, x + (int) Math.round(Math.sin(y * 0.6 + i) * 0.7), y, 0xFF4A3022);
						}
					}
				}
				case 11 -> {
					for (int y = 9; y < 15; y++) {
						for (int x = 4; x < 12; x++) {
							if (!(y == 14 && (x == 4 || x == 11))) {
								set(p, x, y, 0xFFE8DEC8);
							}
						}
					}
					set(p, 5, 11, 0xFF1A1010);
					set(p, 6, 11, 0xFF1A1010);
					set(p, 9, 11, 0xFF1A1010);
					set(p, 10, 11, 0xFF1A1010);
					set(p, 7, 13, 0xFF1A1010);
					set(p, 8, 15, 0xFFC8BEA8);
					set(p, 6, 15, 0xFFC8BEA8);
				}
				case 12 -> {
					for (int y = 11; y < 16; y++) {
						for (int x = 3; x < 13; x++) {
							if (Math.abs(x - 8) + (15 - y) < 6) {
								set(p, x, y, (x + y) % 3 == 0 ? 0xFFFF8A2E : 0xFF3A2A28);
							}
						}
					}
				}
				case 13 -> {
					line(p, 0, 0, 12, 12, 0x88E8E0E8);
					line(p, 0, 0, 15, 4, 0x88E8E0E8);
					line(p, 0, 0, 4, 15, 0x88E8E0E8);
					line(p, 0, 6, 6, 0, 0x88E8E0E8);
					line(p, 0, 11, 11, 0, 0x88E8E0E8);
				}
				default -> {
				}
			}
			outline(p);
			return image(p);
		});
	}

	static final int CANOPY_W = 88;
	static final int CANOPY_H = 76;

	/** Tree crowns per biome: 0 crimson forest, 1 ashland (sparse, dry), 2 frost (pale blue, snow caps). */
	static Image canopy(int biome, int variant) {
		return CANOPIES.computeIfAbsent(biome * 10 + variant, v -> {
			SplittableRandom r = new SplittableRandom(500 + v * 31L);
			int w = CANOPY_W;
			int h = CANOPY_H;
			int n = biome == 1 ? 4 + r.nextInt(2) : 7 + r.nextInt(3);
			double[][] blobs = new double[n][3];
			for (int i = 0; i < n; i++) {
				double angle = Math.PI * (1.05 + r.nextDouble() * 0.9) + (i == 0 ? Math.PI / 2 : 0);
				double dist = i == 0 ? 0 : 10 + r.nextDouble() * 14;
				blobs[i][0] = w / 2.0 + Math.cos(angle) * dist * 1.25;
				blobs[i][1] = h * 0.56 + Math.sin(angle) * dist * 0.9;
				blobs[i][2] = (i == 0 ? (biome == 1 ? 17 : 25) : (biome == 1 ? 9 : 13) + r.nextDouble() * 9);
			}
			int[] tones = switch (biome) {
				case 1 -> new int[] {0xFF2E2418, 0xFF4A3A22, 0xFF6E5630, 0xFF8E7444, 0xFFB09460};
				case 2 -> new int[] {0xFF1E3442, 0xFF2E5266, 0xFF4E7A92, 0xFF86AFC4, 0xFFE4F0F6};
				default -> new int[] {0xFF3A0E12, 0xFF5E171D, 0xFF86222A, 0xFFAE2E36, 0xFFD34A50};
			};
			int[][] bayer = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
			int[] px = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					double best = -1;
					double light = 0;
					for (double[] b : blobs) {
						double wobble = Math.sin(Math.atan2(y - b[1], x - b[0]) * 7 + b[2]) * 1.6;
						double dd = Math.hypot(x - b[0], (y - b[1]) * 1.08) / (b[2] + wobble);
						if (dd < 1) {
							double inside = 1 - dd;
							double lx = (b[0] - x) / b[2];
							double ly = (b[1] - y) / b[2];
							double l = 0.45 + lx * 0.28 + ly * 0.42 + inside * 0.2 - (y / (double) h) * 0.35;
							if (inside > best) {
								best = inside;
								light = l;
							}
						}
					}
					if (best < 0) {
						continue;
					}
					double level = Math.max(0, Math.min(3.999, light * 4.2));
					int base = (int) level;
					int tone = (level - base) * 16 > bayer[y & 3][x & 3] ? base + 1 : base;
					if (biome == 2 && light > 0.95) {
						tone = 4;
					}
					px[y * w + x] = tones[Math.min(4, tone)];
				}
			}
			for (int k = 0; k < 140; k++) {
				int i = r.nextInt(w * h);
				if (px[i] != 0) {
					px[i] = r.nextDouble() < 0.5 ? shade(px[i], 1.2) : tones[0];
				}
			}
			int[] copy = px.clone();
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					if (copy[y * w + x] != 0) {
						continue;
					}
					boolean edge = (x > 0 && copy[y * w + x - 1] != 0) || (x < w - 1 && copy[y * w + x + 1] != 0)
							|| (y > 0 && copy[(y - 1) * w + x] != 0) || (y < h - 1 && copy[(y + 1) * w + x] != 0);
					if (edge) {
						px[y * w + x] = shade(tones[0], 0.6);
					}
				}
			}
			WritableImage image = new WritableImage(w, h);
			image.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), px, 0, w);
			return image;
		});
	}

	/** Material palettes {light, mid, dark} for gear. */
	static int[] material(String name) {
		return switch (name) {
			case "wood" -> new int[] {0xFFB07A50, 0xFF8A5A3A, 0xFF5A3A28};
			case "stone" -> new int[] {0xFF9A949E, 0xFF6E6772, 0xFF48424C};
			case "iron" -> new int[] {0xFFF4ECE4, 0xFFC9C0B8, 0xFF8C8480};
			case "ruby" -> new int[] {0xFFFF7A80, 0xFFD7262D, 0xFF8A1218};
			case "blood" -> new int[] {0xFFD0506F, 0xFF8E1B3A, 0xFF4A0E20};
			case "ember" -> new int[] {0xFFFFC27A, 0xFFFF6A2A, 0xFFA8320E};
			case "aether" -> new int[] {0xFFEADCFF, 0xFFA57BFF, 0xFF5B3BA8};
			case "bone" -> new int[] {0xFFF4EBDA, 0xFFD8CCB4, 0xFF9A8E78};
			case "void" -> new int[] {0xFFC9A8FF, 0xFF6E3FD0, 0xFF2A1450};
			default -> new int[] {0xFFFFFFFF, 0xFFAAAAAA, 0xFF555555};
		};
	}

	private static int[] pixels(int id, int variant) {
		SplittableRandom r = new SplittableRandom(1000L + id * 7919L + variant * 104729L);
		int[] p = new int[256];
		switch (id) {
			case DIRT -> dirt(p, r);
			case GRASS -> topped(p, r, 0xFF6C7A43, 0xFF4E5D33);
			case SNOW -> {
				dirt(p, r);
				for (int x = 0; x < 16; x++) {
					int depth = 4 + (r.nextDouble() < 0.4 ? 1 : 0) + (x % 6 == 3 ? 1 : 0);
					for (int y = 0; y < depth; y++) {
						p[y * 16 + x] = vary(y == 0 ? 0xFFFFFFFF : 0xFFDDE8EE, r, 0.04);
					}
				}
			}
			case SAND -> {
				int off = r.nextInt(7);
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						double v = r.nextDouble();
						int c = v < 0.1 ? 0xFFA88450 : v < 0.16 ? 0xFFE2C08A : 0xFFC9A46A;
						if ((x + y * 3 + off) % 9 == 0) {
							c = 0xFFDDBB80; // wind ripples
						} else if ((x + y * 3 + off) % 9 == 1) {
							c = 0xFFB08C58;
						}
						p[y * 16 + x] = vary(c, r, 0.04);
					}
				}
				bevel(p, 1.06, 0.9);
			}
			case SANDSTONE -> {
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						p[y * 16 + x] = vary(y % 5 == 4 ? 0xFF8E6A3E : y % 5 == 0 ? 0xFFC9A46A : 0xFFB08A55, r, 0.05);
					}
				}
			}
			case ICE -> {
				for (int i = 0; i < 256; i++) {
					p[i] = vary(0xFF8AB8D4, r, 0.05);
				}
				for (int k = 0; k < 4; k++) {
					int x = r.nextInt(12);
					int y = r.nextInt(12);
					for (int s = 0; s < 4; s++) {
						set(p, x + s, y + s, 0xFFE8F6FF);
					}
				}
			}
			case STONE -> stone(p, r, 0xFF57515C, 0xFF68616E, 0xFF433E48);
			case SHALE -> stone(p, r, 0xFF34303A, 0xFF433E4A, 0xFF25222A);
			case BEDROCK -> {
				for (int i = 0; i < 256; i++) {
					p[i] = vary(r.nextDouble() < 0.5 ? 0xFF1B1719 : 0xFF2A2427, r, 0.2);
				}
			}
			case LOG, TRUNK -> bark(p, r, id == LOG);
			case LEAVES -> {
				for (int i = 0; i < 256; i++) {
					p[i] = vary(0xFF5A171C, r, 0.06);
				}
			}
			case PLANKS -> planks(p, r);
			case PLATFORM -> {
				for (int x = 0; x < 16; x++) {
					p[x] = vary(0xFFA87048, r, 0.05);
					p[16 + x] = vary(0xFF8A5A3A, r, 0.05);
					p[32 + x] = vary(0xFF8A5A3A, r, 0.05);
					p[48 + x] = 0xFF4A3022;
				}
				for (int y = 4; y < 8; y++) {
					p[y * 16 + 2] = 0xFF4A3022;
					p[y * 16 + 13] = 0xFF4A3022;
				}
			}
			case BRICK -> bricks(p, r, 0xFF6E6772, 0xFF5C5560, 0xFF2C272D);
			case BRICK_CRYPT -> bricks(p, r, 0xFF7A7074, 0xFF625A5E, 0xFF3A1418);
			case BRICK_FOUNDRY -> {
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						boolean seam = x % 8 == 7 || y % 8 == 7;
						boolean rivet = (x % 8 == 1 || x % 8 == 5) && (y % 8 == 1 || y % 8 == 5);
						p[y * 16 + x] = vary(seam ? 0xFF2A1A16 : rivet ? 0xFFB07050 : (x + y) % 7 == 0 ? 0xFF6E4636 : 0xFF5A3A30, r, 0.06);
					}
				}
			}
			case BRICK_SPIRE -> {
				for (int i = 0; i < 256; i++) {
					p[i] = vary(0xFF2E2440, r, 0.08);
				}
				for (int k = 0; k < 3; k++) {
					int x = r.nextInt(14);
					int y = r.nextInt(14);
					set(p, x, y, 0xFFB48CFF);
					set(p, x + 1, y, 0xFF6E4FB0);
					set(p, x, y + 1, 0xFF6E4FB0);
				}
				for (int x = 0; x < 16; x++) {
					p[x] = shade(p[x], 1.3);
				}
			}
			case CRUMBLE -> {
				bricks(p, r, 0xFF6E6772, 0xFF5C5560, 0xFF2C272D);
				for (int k = 0; k < 14; k++) {
					set(p, r.nextInt(16), r.nextInt(16), 0xFF1A1618);
				}
			}
			case DOOR -> {
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						boolean bar = x % 4 == 1 || x % 4 == 2;
						p[y * 16 + x] = bar ? vary(0xFF6E6772, r, 0.05) : 0xFF120D0E;
					}
				}
				for (int x = 0; x < 16; x++) {
					p[4 * 16 + x] = 0xFF8A1218;
					p[11 * 16 + x] = 0xFF8A1218;
				}
			}
			case TRAP -> {
				bricks(p, r, 0xFF5C5560, 0xFF4A444E, 0xFF22202A);
				for (int y = 6; y < 10; y++) {
					for (int x = 3; x < 13; x++) {
						p[y * 16 + x] = 0xFF0A0708;
					}
				}
				set(p, 7, 7, 0xFFFF3B44);
				set(p, 8, 7, 0xFFFF3B44);
			}
			case SPIKES -> {
				for (int s = 0; s < 4; s++) {
					int cx = s * 4 + 2;
					for (int y = 6; y < 16; y++) {
						int half = (y - 6) / 4;
						for (int x = cx - half; x <= cx + half; x++) {
							set(p, x, y, y < 9 ? 0xFFE4DCDC : 0xFF9A949E);
						}
					}
				}
				for (int x = 0; x < 16; x++) {
					p[15 * 16 + x] = 0xFF48424C;
				}
			}
			case WATER -> {
				for (int i = 0; i < 256; i++) {
					double v = r.nextDouble();
					p[i] = vary(v < 0.15 ? 0xFF5A9BE8 : v < 0.7 ? 0xFF3A7BD5 : 0xFF2E66B8, r, 0.04);
				}
				for (int x = 0; x < 16; x++) {
					p[x] = 0xFF9CC8F5;
					if (x % 5 == 2) {
						p[16 + x] = 0xFF7AB2EE;
					}
				}
			}
			case LAVA -> {
				for (int i = 0; i < 256; i++) {
					double v = r.nextDouble();
					p[i] = vary(v < 0.2 ? 0xFFFFC04A : v < 0.6 ? 0xFFFF7A2E : 0xFFD8401A, r, 0.05);
				}
				for (int x = 0; x < 16; x++) {
					p[x] = 0xFFFFE08A;
				}
			}
			case LEVER, LEVER_ON -> {
				for (int x = 4; x < 12; x++) {
					p[14 * 16 + x] = 0xFF5C5560;
					p[15 * 16 + x] = 0xFF3A3540;
				}
				boolean on = id == LEVER_ON;
				for (int i = 0; i < 7; i++) {
					set(p, on ? 8 + i / 2 : 8 - i / 2, 13 - i, 0xFF8A5A3A);
				}
				int kx = on ? 11 : 5;
				set(p, kx, 6, on ? 0xFF3DDC97 : 0xFFD7262D);
				set(p, kx + 1, 6, on ? 0xFF3DDC97 : 0xFFD7262D);
				set(p, kx, 5, on ? 0xFF9AF5CF : 0xFFFF7A80);
				outline(p);
			}
			case RUNE, RUNE_LIT -> {
				for (int y = 4; y < 16; y++) {
					for (int x = 2; x < 14; x++) {
						p[y * 16 + x] = vary(id == RUNE_LIT ? 0xFF5A3A30 : 0xFF48424C, r, 0.05);
					}
				}
				outline(p);
			}
			case CHEST, CHEST_OPEN -> {
				boolean open = id == CHEST_OPEN;
				for (int y = open ? 9 : 5; y < 16; y++) {
					for (int x = 1; x < 15; x++) {
						p[y * 16 + x] = vary(y == (open ? 9 : 5) ? 0xFFB07A50 : 0xFF8A5A3A, r, 0.05);
					}
				}
				for (int x = 1; x < 15; x++) {
					set(p, x, open ? 11 : 9, 0xFFE0B040);
				}
				set(p, 7, open ? 12 : 10, 0xFFFFE08A);
				set(p, 8, open ? 12 : 10, 0xFFFFE08A);
				if (open) {
					for (int x = 1; x < 15; x++) {
						set(p, x, 4, 0xFF6A4630);
						set(p, x, 5, 0xFF8A5A3A);
						set(p, x, 10, 0xFF1A1010);
					}
				}
				outline(p);
			}
			case GATE -> {
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						double dd = Math.hypot((x - 7.5) / 7.5, (y - 8.5) / 7.5);
						if (dd < 1) {
							p[y * 16 + x] = dd > 0.78 ? 0xFF2A2427 : vary(dd < 0.4 ? 0xFFFF8A8F : 0xFFB3232B, r, 0.15);
						}
					}
				}
			}
			case KEY_FRAG -> {
				int[][] key = {{7, 2}, {8, 2}, {6, 3}, {9, 3}, {6, 4}, {9, 4}, {7, 5}, {8, 5}, {7, 6}, {7, 7}, {7, 8}, {7, 9}, {8, 9}, {7, 10},
						{7, 11}, {8, 11}, {9, 11}};
				for (int[] k : key) {
					set(p, k[0], k[1], 0xFFC9A8FF);
					set(p, k[0] + 1, k[1], 0xFF8A5AE0);
				}
				outline(p);
			}
			case BENCH -> {
				for (int x = 1; x < 15; x++) {
					p[6 * 16 + x] = vary(0xFF9A6A45, r, 0.06);
					p[7 * 16 + x] = vary(0xFF7A5034, r, 0.06);
				}
				for (int y = 8; y < 16; y++) {
					p[y * 16 + 2] = 0xFF5A3A28;
					p[y * 16 + 3] = 0xFF4A3022;
					p[y * 16 + 12] = 0xFF5A3A28;
					p[y * 16 + 13] = 0xFF4A3022;
				}
				for (int x = 4; x < 8; x++) {
					p[5 * 16 + x] = 0xFFB9B4BE;
				}
				outline(p);
			}
			case ANVIL -> {
				for (int x = 1; x < 15; x++) {
					set(p, x, 6, 0xFF8C8490);
					set(p, x, 7, 0xFF6E6772);
					set(p, x, 8, 0xFF5C5560);
				}
				for (int y = 9; y < 13; y++) {
					for (int x = 5; x < 11; x++) {
						set(p, x, y, 0xFF48424C);
					}
				}
				for (int x = 3; x < 13; x++) {
					set(p, x, 13, 0xFF5C5560);
					set(p, x, 14, 0xFF3A3540);
				}
				outline(p);
			}
			case TORCH -> {
				for (int y = 7; y < 15; y++) {
					p[y * 16 + 7] = 0xFF6A4630;
					p[y * 16 + 8] = 0xFF4A3022;
				}
				set(p, 7, 5, 0xFFFFD27A);
				set(p, 8, 5, 0xFFFFB347);
				set(p, 7, 6, 0xFFFF8A3D);
				set(p, 8, 6, 0xFFFFD27A);
				set(p, 7, 4, 0xFFFF6B3D);
				set(p, 8, 4, 0xFFFFE9B0);
				set(p, 8, 3, 0xFFFF8A3D);
				outline(p);
			}
			case THORN -> {
				for (int y = 3; y < 16; y++) {
					set(p, 7, y, 0xFF5F7A3A);
					set(p, 8, y, 0xFF465E2A);
					if (y % 3 == 0) {
						set(p, y % 2 == 0 ? 6 : 9, y, 0xFFD8D0B0);
					}
				}
				for (int y = 6; y < 10; y++) {
					set(p, 4, y, 0xFF5F7A3A);
					set(p, 11, y + 2, 0xFF5F7A3A);
				}
				set(p, 5, 9, 0xFF5F7A3A);
				set(p, 10, 11, 0xFF5F7A3A);
				outline(p);
			}
			case ROOF -> {
				// overlapping clay tiles, each lit along its rounded top
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						int row = y / 4;
						int off = row % 2 == 0 ? 0 : 2;
						int lx = (x + off) % 4;
						int ly = y % 4;
						int c = ly == 0 ? 0xFFC0503E : ly == 3 ? 0xFF4A140F : lx == 3 ? 0xFF5E1A14 : 0xFF8A2A22;
						p[y * 16 + x] = vary(c, r, 0.05);
					}
				}
			}
			case CLOTH -> {
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						int c = (x / 4) % 2 == 0 ? 0xFF8E1A26 : 0xFF6A121C;
						if (y % 5 == 4) {
							c = 0xFFE0B040; // a sewn band
						}
						p[y * 16 + x] = vary(c, r, 0.05);
					}
				}
				for (int x = 0; x < 16; x += 3) {
					set(p, x, 2, 0xFFC42E3A);
				}
			}
			case BRAZIER, BRAZIER_LIT -> {
				// a stone bowl on a short stand (the flame is drawn live)
				for (int x = 2; x < 14; x++) {
					set(p, x, 7, 0xFF8C8490);
					set(p, x, 8, 0xFF6E6772);
				}
				for (int x = 3; x < 13; x++) {
					set(p, x, 9, 0xFF5C5560);
				}
				for (int x = 4; x < 12; x++) {
					set(p, x, 10, 0xFF48424C);
				}
				for (int y = 11; y < 15; y++) {
					set(p, 7, y, 0xFF5C5560);
					set(p, 8, y, 0xFF3A3540);
				}
				for (int x = 4; x < 12; x++) {
					set(p, x, 15, 0xFF48424C);
				}
				if (id == BRAZIER_LIT) {
					for (int x = 4; x < 12; x++) {
						set(p, x, 6, x % 2 == 0 ? 0xFFFF8A2E : 0xFFFFE08A);
					}
				} else {
					for (int x = 4; x < 12; x++) {
						set(p, x, 6, 0xFF2A2427);
					}
				}
				outline(p);
			}
			case CRATE -> {
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						boolean frame = x < 2 || x > 13 || y < 2 || y > 13;
						boolean brace = Math.abs(x - y) < 2 || Math.abs(x - (15 - y)) < 1;
						int c = frame ? 0xFF5A3A28 : brace ? 0xFF7E5234 : (y / 4) % 2 == 0 ? 0xFF9A6A45 : 0xFF8A5A3A;
						p[y * 16 + x] = vary(c, r, 0.05);
					}
				}
				for (int[] n : new int[][] {{1, 1}, {14, 1}, {1, 14}, {14, 14}}) {
					set(p, n[0], n[1], 0xFFC9C0B8);
				}
				bevel(p, 1.1, 0.85);
			}
			case PLATE -> {
				stone(p, r, 0xFF57515C, 0xFF68616E, 0xFF433E48);
				for (int x = 2; x < 14; x++) {
					set(p, x, 0, 0xFFB0A8BA);
					set(p, x, 1, 0xFF8C8490);
					set(p, x, 2, 0xFF5C5560);
				}
				set(p, 2, 1, 0xFF3A3540);
				set(p, 13, 1, 0xFF3A3540);
			}
			case GLYPH, GLYPH + 1, GLYPH + 2, GLYPH + 3 -> {
				for (int y = 2; y < 16; y++) {
					for (int x = 3; x < 13; x++) {
						p[y * 16 + x] = vary(y < 4 ? 0xFF6E6772 : 0xFF57515C, r, 0.05);
					}
				}
				outline(p);
			}
			case COAL_ORE -> ore(p, r, 0xFF17131A, 0xFF3A3540, false);
			case IRON_ORE -> ore(p, r, 0xFFC9A27E, 0xFFE6C9A8, false);
			case RUBY_ORE -> ore(p, r, 0xFFD7262D, 0xFFFF7A80, false);
			case BLOOD_ORE -> ore(p, r, 0xFF8E1B3A, 0xFFD0506F, true);
			case EMBER_ORE -> ore(p, r, 0xFFFF6A2A, 0xFFFFC27A, true);
			case AETHER_ORE -> ore(p, r, 0xFF8A5AE0, 0xFFEADCFF, true);
			case STICK -> {
				line(p, 3, 13, 12, 4, 0xFF6A4630);
				line(p, 4, 13, 12, 5, 0xFF4A3022);
				outline(p);
			}
			case COAL -> blob(p, r, 0xFF201B22, 0xFF4A4450, 5);
			case GEL -> blob(p, r, 0xCCB3232B, 0xEEFF6B6F, 4);
			case IRON -> ingot(p, material("iron"));
			case BLOODSTONE -> gem(p, material("blood"));
			case EMBERITE -> ingot(p, material("ember"));
			case AETHERITE -> gem(p, material("aether"));
			case RUBY -> gem(p, material("ruby"));
			case SINEW -> {
				for (int i = 0; i < 10; i++) {
					set(p, 3 + i, 8 + (int) Math.round(Math.sin(i * 0.9) * 2), 0xFFD8C8B0);
				}
				outline(p);
			}
			case ARROW -> {
				line(p, 2, 13, 12, 3, 0xFF8A5A3A);
				set(p, 12, 3, 0xFFC9C0B8);
				set(p, 13, 2, 0xFFC9C0B8);
				set(p, 12, 2, 0xFFC9C0B8);
				set(p, 13, 3, 0xFF8C8480);
				set(p, 2, 12, 0xFFF4EFEE);
				set(p, 3, 14, 0xFFF4EFEE);
				set(p, 1, 13, 0xFFF4EFEE);
				outline(p);
			}
			case WARDEN_SIGIL -> {
				gem(p, material("bone"));
				set(p, 8, 6, 0xFF8A1218);
				set(p, 8, 7, 0xFF8A1218);
			}
			case GOLEM_CORE -> {
				blob(p, r, 0xFF5A3A30, 0xFF8A5A40, 5);
				set(p, 8, 9, 0xFFFFC27A);
				set(p, 7, 9, 0xFFFF6A2A);
				set(p, 8, 8, 0xFFFF6A2A);
			}
			case HERALD_EYE -> {
				blob(p, r, 0xFF2A1450, 0xFF6E3FD0, 5);
				set(p, 8, 9, 0xFFEADCFF);
				set(p, 7, 9, 0xFFB48CFF);
				set(p, 9, 9, 0xFFB48CFF);
			}
			case MAP_CRYPT, MAP_FOUNDRY, MAP_SPIRE -> {
				for (int y = 3; y < 13; y++) {
					for (int x = 2; x < 14; x++) {
						p[y * 16 + x] = vary(0xFFE6D6B4, r, 0.04);
					}
				}
				line(p, 4, 10, 8, 6, 0xFF8A6A4A);
				line(p, 8, 6, 11, 8, 0xFF8A6A4A);
				int mark = id == MAP_CRYPT ? 0xFFD7262D : id == MAP_FOUNDRY ? 0xFFFF6A2A : 0xFF8A5AE0;
				set(p, 10, 7, mark);
				set(p, 12, 9, mark);
				set(p, 11, 8, mark);
				set(p, 12, 7, mark);
				set(p, 10, 9, mark);
				outline(p);
			}
			case HEAL_POTION -> potion(p, 0xFFD7262D);
			case SWIFT_ELIXIR -> potion(p, 0xFF4AC8E0);
			case STRENGTH_TONIC -> potion(p, 0xFFFF8A2E);
			case HEART_CRYSTAL -> {
				for (int y = 3; y < 14; y++) {
					for (int x = 2; x < 14; x++) {
						double hx = (x - 7.5) / 5.0;
						double hy = (y - 7.0) / 5.0;
						double f = Math.pow(hx * hx + hy * hy - 0.5, 3) - hx * hx * hy * hy * hy * -1;
						if (f < 0 || (Math.abs(hx) < 0.25 && hy > 0 && hy < 0.95 - Math.abs(hx))) {
							set(p, x, y, x < 7 && y < 7 ? 0xFFFF9AA0 : 0xFFD7262D);
						}
					}
				}
				outline(p);
			}
			case FEATHER -> {
				for (int i = 0; i < 10; i++) {
					set(p, 4 + i, 12 - i, 0xFFF4EFEE);
					set(p, 3 + i, 11 - i, 0xFFD8D0D0);
					set(p, 5 + i, 12 - i, 0xFFB8B0B0);
				}
				line(p, 2, 14, 5, 11, 0xFF8A8590);
				outline(p);
			}
			case WOOD_PICK -> pick(p, material("wood"));
			case STONE_PICK -> pick(p, material("stone"));
			case IRON_PICK -> pick(p, material("iron"));
			case RUBY_PICK -> pick(p, material("ruby"));
			case BLOOD_PICK -> pick(p, material("blood"));
			case EMBER_PICK -> pick(p, material("ember"));
			case WOOD_SWORD -> sword(p, material("wood"), false);
			case STONE_SWORD -> sword(p, material("stone"), false);
			case IRON_SWORD -> sword(p, material("iron"), false);
			case RUBY_SWORD -> sword(p, material("ruby"), false);
			case BLOOD_SWORD -> sword(p, material("blood"), true);
			case EMBER_SWORD -> sword(p, material("ember"), true);
			case AETHER_SWORD -> sword(p, material("aether"), true);
			case CRYPT_BLADE -> sword(p, material("bone"), true);
			case VOID_EDGE -> sword(p, material("void"), true);
			case MAGMA_MAUL -> {
				line(p, 2, 14, 9, 7, 0xFF5A3A28);
				line(p, 3, 14, 10, 7, 0xFF3A261C);
				int[] m = material("ember");
				for (int y = 1; y < 9; y++) {
					for (int x = 8; x < 15; x++) {
						if (Math.abs(x - 11.5) + Math.abs(y - 5) < 5.5) {
							set(p, x, y, y < 4 ? m[0] : (x + y) % 3 == 0 ? 0xFFFFE08A : m[1]);
						}
					}
				}
				outline(p);
			}
			case WOOD_BOW -> bow(p, material("wood"));
			case BONE_BOW -> bow(p, material("bone"));
			case EMBER_BOW -> bow(p, material("ember"));
			case VOID_BOW -> bow(p, material("void"));
			case IRON_ARMOR -> armor(p, material("iron"));
			case RUBY_ARMOR -> armor(p, material("ruby"));
			case BLOOD_ARMOR -> armor(p, material("blood"));
			case EMBER_ARMOR -> armor(p, material("ember"));
			case AETHER_ARMOR -> armor(p, material("aether"));
			default -> {
			}
		}
		return p;
	}

	/** Colours of worn armor (drawn over the player), null = none. */
	static int[] armorColors(int id) {
		return switch (id) {
			case IRON_ARMOR -> material("iron");
			case RUBY_ARMOR -> material("ruby");
			case BLOOD_ARMOR -> material("blood");
			case EMBER_ARMOR -> material("ember");
			case AETHER_ARMOR -> material("aether");
			default -> null;
		};
	}

	private static void sword(int[] p, int[] m, boolean fancy) {
		for (int t = 0; t <= 8; t++) {
			set(p, 6 + t, 9 - t, m[0]);
			set(p, 7 + t, 9 - t, m[1]);
			set(p, 6 + t, 10 - t, m[1]);
			if (fancy && t > 1 && t < 7) {
				set(p, 7 + t, 8 - t, t % 2 == 0 ? m[0] : m[2]);
			}
		}
		set(p, 15, 0, 0);
		int guard = fancy ? 0xFFE0B040 : m[2];
		for (int[] g : new int[][] {{3, 8}, {4, 9}, {5, 10}, {6, 11}, {7, 12}}) {
			set(p, g[0], g[1], guard);
		}
		if (fancy) {
			set(p, 2, 7, guard);
			set(p, 8, 13, guard);
		}
		set(p, 4, 11, 0xFF5A3A28);
		set(p, 3, 12, 0xFF6A4630);
		set(p, 2, 13, 0xFF5A3A28);
		set(p, 1, 14, fancy ? 0xFFFF3B44 : m[2]);
		outline(p);
	}

	private static void pick(int[] p, int[] m) {
		line(p, 2, 14, 10, 6, 0xFF6A4630);
		line(p, 3, 14, 11, 6, 0xFF4A3022);
		for (double s = -6; s <= 6; s += 0.25) {
			double v = (1 - (s / 6) * (s / 6)) * 2.2;
			double x = 10 + s * 0.707 + v * 0.707;
			double y = 5 + s * 0.707 - v * 0.707;
			int c = Math.abs(s) > 4.6 ? m[2] : s < 0 ? m[0] : m[1];
			set(p, (int) Math.round(x), (int) Math.round(y), c);
			set(p, (int) Math.round(x + 0.5), (int) Math.round(y + 0.5), m[1]);
		}
		outline(p);
	}

	private static void bow(int[] p, int[] m) {
		for (double a = -1.25; a <= 1.25; a += 0.05) {
			int x = (int) Math.round(7 + Math.cos(a) * 6 - 3);
			int y = (int) Math.round(8 + Math.sin(a) * 7);
			set(p, x + 2, y, m[1]);
			set(p, x + 3, y, Math.abs(a) > 1 ? m[2] : m[0]);
		}
		for (int y = 1; y < 16; y++) {
			set(p, 5, y, 0xFFE8E0D0);
		}
		outline(p);
	}

	private static void armor(int[] p, int[] m) {
		for (int y = 3; y < 15; y++) {
			for (int x = 2; x < 14; x++) {
				boolean sleeve = y < 8 && (x < 4 || x > 11);
				boolean body = x >= 4 && x <= 11;
				boolean neck = y < 5 && x >= 6 && x <= 9;
				if ((sleeve || body) && !neck) {
					set(p, x, y, x < 7 && y < 8 ? m[0] : (y == 9 ? m[2] : m[1]));
				}
			}
		}
		outline(p);
	}

	private static void potion(int[] p, int liquid) {
		for (int y = 6; y < 15; y++) {
			for (int x = 4; x < 12; x++) {
				double dd = Math.hypot((x - 7.5) / 4, (y - 10.5) / 4.2);
				if (dd < 1) {
					set(p, x, y, y < 8 ? 0xFFDDE8EE : x < 7 && y < 11 ? shade(liquid, 1.4) : liquid);
				}
			}
		}
		for (int y = 2; y < 6; y++) {
			set(p, 7, y, 0xFFDDE8EE);
			set(p, 8, y, 0xFFB8C8D0);
		}
		set(p, 7, 2, 0xFF8A5A3A);
		set(p, 8, 2, 0xFF8A5A3A);
		outline(p);
	}

	private static void gem(int[] p, int[] m) {
		int[][] gem = {{8, 3}, {7, 4}, {8, 4}, {9, 4}, {6, 5}, {7, 5}, {8, 5}, {9, 5}, {10, 5}, {5, 6}, {6, 6}, {7, 6}, {8, 6}, {9, 6},
				{10, 6}, {11, 6}, {5, 7}, {6, 7}, {7, 7}, {8, 7}, {9, 7}, {10, 7}, {11, 7}, {6, 8}, {7, 8}, {8, 8}, {9, 8}, {10, 8}, {7, 9},
				{8, 9}, {9, 9}, {8, 10}};
		for (int[] g : gem) {
			p[g[1] * 16 + g[0]] = g[1] < 6 ? m[0] : g[0] < 8 ? m[1] : m[2];
		}
		p[5 * 16 + 7] = 0xFFFFFFFF;
		outline(p);
	}

	private static void ingot(int[] p, int[] m) {
		for (int y = 6; y < 11; y++) {
			for (int x = 3 + (10 - y) / 2; x < 13 - (10 - y) / 2; x++) {
				p[y * 16 + x] = y == 6 ? m[0] : y < 9 ? m[1] : m[2];
			}
		}
		outline(p);
	}

	private static void bark(int[] p, SplittableRandom r, boolean cut) {
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int wave = (int) Math.round(Math.sin((y + x * 3) * 0.7) * 0.6);
				boolean groove = (x + wave) % 5 == 2;
				int color = groove ? 0xFF2E1D16 : 0xFF4E3324;
				if (x < 3) {
					color = groove ? 0xFF3A261C : 0xFF66432E;
				} else if (x > 12) {
					color = groove ? 0xFF22160F : 0xFF3A261B;
				}
				p[y * 16 + x] = vary(color, r, 0.05);
			}
		}
		if (cut) {
			for (int x = 0; x < 16; x++) {
				p[x] = 0xFF8A6446;
				p[15 * 16 + x] = 0xFF2E1D16;
			}
		}
	}

	private static void topped(int[] p, SplittableRandom r, int top, int under) {
		dirt(p, r);
		for (int x = 0; x < 16; x++) {
			int depth = 3 + (r.nextDouble() < 0.4 ? 1 : 0) + (x % 5 == 2 ? 1 : 0);
			for (int y = 0; y < depth; y++) {
				p[y * 16 + x] = vary(y == 0 ? top : under, r, 0.10);
			}
		}
	}

	private static void dirt(int[] p, SplittableRandom r) {
		for (int i = 0; i < 256; i++) {
			double v = r.nextDouble();
			p[i] = vary(v < 0.12 ? 0xFF3C261C : v < 0.2 ? 0xFF6E4A34 : 0xFF543627, r, 0.06);
		}
		// darker clumps
		for (int c = 0; c < 3; c++) {
			int cx = r.nextInt(16);
			int cy = r.nextInt(16);
			for (int d = 0; d < 6; d++) {
				set(p, (cx + r.nextInt(-1, 2)) & 15, (cy + r.nextInt(-1, 2)) & 15, 0xFF422A1F);
			}
		}
		// pebbles with a lit top
		for (int c = 0; c < 3; c++) {
			int cx = r.nextInt(14);
			int cy = r.nextInt(14);
			set(p, cx, cy, 0xFF8A8590);
			set(p, cx + 1, cy, 0xFF6E6772);
			set(p, cx, cy + 1, 0xFF5C5560);
			set(p, cx + 1, cy + 1, 0xFF3A3540);
		}
		// a thin root
		if (r.nextBoolean()) {
			int x = r.nextInt(16);
			for (int y = 0; y < 6 + r.nextInt(6); y++) {
				x = (x + r.nextInt(-1, 2)) & 15;
				set(p, x, y, 0xFF2E1D16);
			}
		}
		bevel(p, 1.08, 0.88);
	}

	/** Light from the top left: a lighter top row/left column, a darker bottom row/right column. */
	private static void bevel(int[] p, double light, double dark) {
		for (int i = 0; i < 16; i++) {
			if ((p[i] >>> 24) != 0) {
				p[i] = shade(p[i], light);
			}
			if ((p[i * 16] >>> 24) != 0) {
				p[i * 16] = shade(p[i * 16], (light + 1) / 2);
			}
			if ((p[15 * 16 + i] >>> 24) != 0) {
				p[15 * 16 + i] = shade(p[15 * 16 + i], dark);
			}
			if ((p[i * 16 + 15] >>> 24) != 0) {
				p[i * 16 + 15] = shade(p[i * 16 + 15], (dark + 1) / 2);
			}
		}
	}

	private static void stone(int[] p, SplittableRandom r, int base, int light, int dark) {
		for (int i = 0; i < 256; i++) {
			p[i] = vary(base, r, 0.05);
		}
		// rounded rocks, lit on their upper left, shadowed below right
		for (int b = 0; b < 4; b++) {
			int cx = r.nextInt(16);
			int cy = r.nextInt(16);
			double rx = 2.5 + r.nextDouble() * 2.5;
			double ry = 1.8 + r.nextDouble() * 1.8;
			for (int dy = -5; dy <= 5; dy++) {
				for (int dx = -6; dx <= 6; dx++) {
					double e = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry);
					if (e > 1) {
						continue;
					}
					int x = (cx + dx) & 15;
					int y = (cy + dy) & 15;
					int c = mixArgb(base, light, 0.45);
					if (e > 0.6 && dx + dy < 0) {
						c = light;
					} else if (e > 0.6 && dx + dy > 0) {
						c = dark;
					}
					p[y * 16 + x] = vary(c, r, 0.03);
				}
			}
		}
		// hairline cracks
		for (int k = 0; k < 2; k++) {
			int x = r.nextInt(16);
			int y = r.nextInt(16);
			for (int i = 0; i < 4 + r.nextInt(3); i++) {
				x = (x + r.nextInt(-1, 2)) & 15;
				y = (y + 1) & 15;
				p[y * 16 + x] = shade(dark, 0.75);
			}
		}
		bevel(p, 1.06, 0.9);
	}

	private static int mixArgb(int a, int b, double t) {
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	private static void bricks(int[] p, SplittableRandom r, int top, int face, int mortar) {
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int off = (y / 4) % 2 == 0 ? 0 : 4;
				boolean m = y % 4 == 3 || (x + off) % 8 == 7;
				boolean leftEdge = (x + off) % 8 == 0;
				boolean lowEdge = y % 4 == 2;
				int c = m ? mortar : y % 4 == 0 ? top : leftEdge ? mixArgb(face, top, 0.5) : lowEdge ? shade(face, 0.85) : face;
				p[y * 16 + x] = vary(c, r, 0.05);
			}
		}
		// chipped corners and moss/soot specks
		for (int k = 0; k < 4; k++) {
			set(p, r.nextInt(16), r.nextInt(16), shade(face, 0.7));
		}
	}

	private static void planks(int[] p, SplittableRandom r) {
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int board = y / 4;
				boolean seam = y % 4 == 3 || (x + board * 5) % 16 == 0;
				p[y * 16 + x] = vary(seam ? 0xFF4A3022 : board % 2 == 0 ? 0xFF8A5A3A : 0xFF7E5234, r, 0.05);
			}
		}
	}

	private static void ore(int[] p, SplittableRandom r, int dark, int light, boolean deep) {
		if (deep) {
			stone(p, r, 0xFF34303A, 0xFF433E4A, 0xFF25222A);
		} else {
			stone(p, r, 0xFF57515C, 0xFF68616E, 0xFF433E48);
		}
		for (int c = 0; c < (deep ? 5 : 4); c++) {
			int cx = 2 + r.nextInt(11);
			int cy = 2 + r.nextInt(11);
			// a little vein: dark rim, coloured body, one bright glint
			for (int dy = -1; dy <= 2; dy++) {
				for (int dx = -1; dx <= 2; dx++) {
					boolean body = dx >= 0 && dx <= 1 && dy >= 0 && dy <= 1;
					if (!body && Math.abs(dx - 0.5) + Math.abs(dy - 0.5) < 2.2) {
						set(p, cx + dx, cy + dy, shade(dark, 0.55));
					}
				}
			}
			set(p, cx, cy, light);
			set(p, cx + 1, cy, dark);
			set(p, cx, cy + 1, dark);
			set(p, cx + 1, cy + 1, shade(dark, 0.8));
			if (r.nextDouble() < 0.6) {
				set(p, cx, cy, 0xFFFFFFFF);
				set(p, cx + 1, cy, light);
			}
		}
	}

	private static void blob(int[] p, SplittableRandom r, int base, int light, int radius) {
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double dd = Math.hypot((x - 8) / 1.15, y - 9);
				if (dd < radius + r.nextDouble() * 0.8) {
					p[y * 16 + x] = (x < 8 && y < 8) ? light : vary(base, r, 0.08);
				}
			}
		}
		outline(p);
	}

	private static void line(int[] p, int x0, int y0, int x1, int y1, int color) {
		int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 0; i <= n; i++) {
			set(p, x0 + (x1 - x0) * i / Math.max(1, n), y0 + (y1 - y0) * i / Math.max(1, n), color);
		}
	}

	private static void set(int[] p, int x, int y, int color) {
		if (x >= 0 && x < 16 && y >= 0 && y < 16) {
			p[y * 16 + x] = color;
		}
	}

	private static void outline(int[] p) {
		int[] copy = p.clone();
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				if ((copy[y * 16 + x] >>> 24) != 0) {
					continue;
				}
				boolean edge = false;
				for (int[] dd : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					int nx = x + dd[0];
					int ny = y + dd[1];
					if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && (copy[ny * 16 + nx] >>> 24) > 0x80) {
						edge = true;
					}
				}
				if (edge) {
					p[y * 16 + x] = 0xFF120D0E;
				}
			}
		}
	}

	private static int vary(int argb, SplittableRandom r, double amount) {
		return shade(argb, 1 + (r.nextDouble() * 2 - 1) * amount);
	}

	static int shade(int argb, double f) {
		int a = argb >>> 24;
		int red = clamp((int) (((argb >> 16) & 0xFF) * f), 0, 255);
		int g = clamp((int) (((argb >> 8) & 0xFF) * f), 0, 255);
		int b = clamp((int) ((argb & 0xFF) * f), 0, 255);
		return (a << 24) | (red << 16) | (g << 8) | b;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static Image image(int[] px) {
		WritableImage image = new WritableImage(16, 16);
		PixelWriter writer = image.getPixelWriter();
		writer.setPixels(0, 0, 16, 16, javafx.scene.image.PixelFormat.getIntArgbInstance(), px, 0, 16);
		return image;
	}
}
