package net.veloclient.velo.client.tiers;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.modules.pvp.TierTaggerModule;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier Tagger: adds a player's tier to their name - a game-mode icon (the real item, from font {@code velo-client:tiers})
 * and HT1..LT5 in the tier's colour, before or after the name. The finished name is kept per player and only rebuilt
 * when the name, the tier data or a setting changed (name tags and tab rows are built every frame).
 */
public final class TierTags {

	private static final Map<String, String> ICONS = Map.ofEntries(
			Map.entry("sword", ""), Map.entry("axe", ""), Map.entry("mace", ""), Map.entry("crystal", ""),
			Map.entry("pot", ""), Map.entry("neth_pot", ""), Map.entry("smp", ""), Map.entry("uhc", ""),
			Map.entry("minecart", ""), Map.entry("dia_smp", ""), Map.entry("og_vanilla", ""), Map.entry("bed", ""),
			Map.entry("bow", ""), Map.entry("speed", ""), Map.entry("debuff", ""), Map.entry("creeper", ""),
			Map.entry("trident", ""), Map.entry("manhunt", ""), Map.entry("elytra", ""), Map.entry("dia_crystal", ""));
	private static final String FALLBACK_ICON = "";

	/** HT / LT colour per tier: gold, silver, bronze, green, blue. */
	private static final int[][] COLORS = {{0xF2C14E, 0xD9AE4C}, {0xD5DBE3, 0xAEB6C1}, {0xEA9B5F, 0xC9845A}, {0x86D49A, 0x6DB383}, {0x8FB1E3, 0x7893BF}};
	private static final int RETIRED = 0x9A9A9A;
	private static final int SEPARATOR = 0x6E6E6E;

	private record Cached(Text base, int generation, int settings, Text result) {
	}

	private static final Map<UUID, Cached> NAME_TAGS = new ConcurrentHashMap<>();
	private static final Map<UUID, Cached> TAB = new ConcurrentHashMap<>();

	private TierTags() {
	}

	/** {@code base} with the player's tier added (or {@code base} itself when there's nothing to show). */
	public static Text decorate(Text base, UUID uuid, boolean tab) {
		TierTaggerModule m = TierTaggerModule.active();
		if (m == null || base == null || uuid == null || (tab ? !m.tabList() : !m.nameTags())) {
			return base;
		}
		if (!m.showOwn() && uuid.equals(ownUuid())) {
			return base;
		}
		Map<UUID, Cached> cache = tab ? TAB : NAME_TAGS;
		int gen = TierData.generation();
		int settings = TierTaggerModule.settingsVersion;
		Cached c = cache.get(uuid);
		if (c != null && c.base() == base && c.generation() == gen && c.settings() == settings) {
			TierData.get(uuid);   // keeps it fresh
			return c.result();
		}
		Text result = build(base, uuid, m);
		if (cache.size() > 2000) {
			cache.clear();
		}
		cache.put(uuid, new Cached(base, gen, settings, result));
		return result;
	}

	private static Text build(Text base, UUID uuid, TierTaggerModule m) {
		TierData.Player p = TierData.get(uuid);
		if (p == null) {
			return base;
		}
		Map<String, TierData.Tier> list = m.subTiers() ? p.subtiers() : p.pvptiers();
		TierData.Tier tier = pick(list, m.modeKey(), m.retired());
		if (tier == null) {
			return base;
		}
		MutableText tag = Text.empty();
		if (m.icon()) {
			tag.append(icon(tier.mode()));
			tag.append(Text.literal(" "));
		}
		tag.append(label(tier));
		if (m.peak() && (tier.peak() < tier.tier() || tier.peak() == tier.tier() && tier.peakHigh() && !tier.high())) {
			int pt = Math.max(1, Math.min(5, tier.peak()));
			tag.append(Text.literal(" (" + (tier.peakHigh() ? "HT" : "LT") + pt + ")").setStyle(Style.EMPTY.withColor(COLORS[pt - 1][tier.peakHigh() ? 0 : 1])));
		}
		MutableText out = Text.empty();
		Text separator = Text.literal(" | ").setStyle(Style.EMPTY.withColor(SEPARATOR));
		if (m.before()) {
			out.append(tag).append(separator).append(base);
		} else {
			out.append(base).append(separator).append(tag);
		}
		return out;
	}

	/** The game mode's icon (an item picture from font {@code velo-client:tiers}). */
	public static Text icon(String mode) {
		return Text.literal(ICONS.getOrDefault(mode, FALLBACK_ICON)).setStyle(iconStyle());
	}

	/** "HT1".."LT5" (retired: "RHT2") in the tier's colour, bold. */
	public static Text label(TierData.Tier tier) {
		int t = Math.max(1, Math.min(5, tier.tier()));
		int color = color(tier);
		String label = (tier.retired() ? "R" : "") + (tier.high() ? "HT" : "LT") + t;
		return Text.literal(label).setStyle(Style.EMPTY.withColor(color).withBold(true));
	}

	/** The tier's colour (RGB): gold, silver, bronze, green, blue - HT brighter than LT; grey when retired. */
	public static int color(TierData.Tier tier) {
		int t = Math.max(1, Math.min(5, tier.tier()));
		return tier.retired() ? RETIRED : COLORS[t - 1][tier.high() ? 0 : 1];
	}

	private static UUID ownUuid() {
		var self = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		return self == null ? null : net.veloclient.velo.client.pvp.PvpCompat.uuid(self);
	}

	/** The chosen mode, or the best tier of all (active ones before retired ones). */
	private static TierData.Tier pick(Map<String, TierData.Tier> list, String mode, boolean retired) {
		if (list == null || list.isEmpty()) {
			return null;
		}
		if (mode != null) {
			TierData.Tier t = list.get(mode);
			return t == null || (t.retired() && !retired) ? null : t;
		}
		TierData.Tier best = null;
		for (TierData.Tier t : list.values()) {
			if (t.retired() && !retired) {
				continue;
			}
			if (best == null || (best.retired() && !t.retired()) || (best.retired() == t.retired() && t.score() < best.score())) {
				best = t;
			}
		}
		return best;
	}

	private static Style iconStyle() {
		Identifier fontId = Identifier.of("velo-client", "tiers");
		//? if <26.1 {
		return Style.EMPTY.withFont(new net.minecraft.text.StyleSpriteSource.Font(fontId));
		//?} else {
		/*return Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(fontId));
		*///?}
	}
}
