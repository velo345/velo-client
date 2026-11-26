package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Tier Tagger (2026-11-24): players' PvP tiers from the big tier lists next to their name - above their head and in the
 * tab list - as a game-mode icon and HT1..LT5 in the tier's colour. Sources: PvPTiers (what used to be MCTiers) and
 * SubTiers; the data comes through Velo's server (see {@link net.veloclient.velo.client.tiers.TierData}).
 */
public final class TierTaggerModule extends AbstractModule implements Configurable {

	/** Game modes: shown label and the key on PvPTiers / SubTiers. */
	public static final String[][] PVPTIERS_MODES = {{"Sword", "sword"}, {"Axe", "axe"}, {"Mace", "mace"}, {"Crystal", "crystal"},
			{"Pot", "pot"}, {"Neth Pot", "neth_pot"}, {"SMP", "smp"}, {"UHC", "uhc"}};
	public static final String[][] SUBTIERS_MODES = {{"Minecart", "minecart"}, {"Diamond SMP", "dia_smp"}, {"OG Vanilla", "og_vanilla"},
			{"Bed", "bed"}, {"Bow", "bow"}, {"Speed", "speed"}, {"Debuff", "debuff"}, {"Creeper", "creeper"}, {"Trident", "trident"},
			{"Manhunt", "manhunt"}, {"Elytra", "elytra"}, {"Diamond Crystal", "dia_crystal"}};
	public static final String BEST = "Best tier";

	private static TierTaggerModule instance;

	private String source = "PvPTiers";
	private String pvpMode = BEST;
	private String subMode = BEST;
	private boolean nameTags = true;
	private boolean tabList = true;
	private boolean icon = true;
	private boolean retired = true;
	private boolean peak = false;
	private String side = "After the name";
	private boolean showOwn = true;

	/** Hold to see every tier of the player you're looking at (2026-11-25 request). */
	public static final net.minecraft.client.option.KeyBinding ALL_TIERS_KEY = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
			.registerKeyBinding(new net.minecraft.client.option.KeyBinding("key.velo-client.all_tiers", net.minecraft.client.util.InputUtil.Type.KEYSYM,
					net.veloclient.velo.client.util.Keys.G, net.veloclient.velo.client.keybind.VeloKeybinds.CATEGORY));

	public TierTaggerModule() {
		super("tier-tagger", "Tier Tagger", "Shows players' PvP tiers (PvPTiers, SubTiers) next to their name - above their head and in the tab list.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (isEnabled()) {
				net.veloclient.velo.client.tiers.TierData.tick();
			}
		});
	}

	public static TierTaggerModule active() {
		TierTaggerModule m = instance;
		return m != null && m.isEnabled() ? m : null;
	}

	public boolean subTiers() {
		return source.equals("SubTiers");
	}

	/** The mode key to show, or null for the best tier. */
	public String modeKey() {
		String label = subTiers() ? subMode : pvpMode;
		for (String[] m : subTiers() ? SUBTIERS_MODES : PVPTIERS_MODES) {
			if (m[0].equals(label)) {
				return m[1];
			}
		}
		return null;
	}

	public boolean nameTags() {
		return nameTags;
	}

	public boolean tabList() {
		return tabList;
	}

	public boolean icon() {
		return icon;
	}

	public boolean retired() {
		return retired;
	}

	public boolean peak() {
		return peak;
	}

	public boolean showOwn() {
		return showOwn;
	}

	public boolean before() {
		return side.equals("Before the name");
	}

	/** Bumped whenever a setting changes, so decorated names are rebuilt. */
	public static int settingsVersion;

	private static List<String> labels(String[][] modes) {
		List<String> out = new ArrayList<>();
		out.add(BEST);
		for (String[] m : modes) {
			out.add(m[0]);
		}
		return out;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Tier list", List.of("PvPTiers", "SubTiers"), () -> source, v -> {
					source = v;
					settingsVersion++;
				}),
				new ConfigField.ChoiceField("PvPTiers mode", labels(PVPTIERS_MODES), () -> pvpMode, v -> {
					pvpMode = v;
					settingsVersion++;
				}),
				new ConfigField.ChoiceField("SubTiers mode", labels(SUBTIERS_MODES), () -> subMode, v -> {
					subMode = v;
					settingsVersion++;
				}),
				new ConfigField.HeaderField("Where"),
				new ConfigField.ToggleField("Above heads", () -> nameTags, v -> {
					nameTags = v;
					settingsVersion++;
				}),
				new ConfigField.ToggleField("Tab list", () -> tabList, v -> {
					tabList = v;
					settingsVersion++;
				}),
				new ConfigField.ToggleField("Show my own tier", () -> showOwn, v -> {
					showOwn = v;
					settingsVersion++;
				}),
				new ConfigField.ChoiceField("Position", List.of("After the name", "Before the name"), () -> side, v -> {
					side = v;
					settingsVersion++;
				}),
				new ConfigField.HeaderField("Look"),
				new ConfigField.ToggleField("Game mode icon", () -> icon, v -> {
					icon = v;
					settingsVersion++;
				}),
				new ConfigField.ToggleField("Show retired tiers", () -> retired, v -> {
					retired = v;
					settingsVersion++;
				}),
				new ConfigField.ToggleField("Show peak when higher", () -> peak, v -> {
					peak = v;
					settingsVersion++;
				}),
				new ConfigField.HeaderField("All tiers"),
				net.veloclient.velo.client.keybind.KeybindConfig.field("Hold to see all tiers", ALL_TIERS_KEY));
	}

	@Override
	public void onEnable() {
		settingsVersion++;
	}

	@Override
	public void onDisable() {
		settingsVersion++;
	}
}
