package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.hud.StatusBarIcons;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.ModuleRegistry;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Random;

/**
 * Recolors the health bar - heart, absorption and empty-heart colors - or swaps the heart for your
 * own PNG (fitted to the 9px slot). Takes over vanilla's heart drawing (see StatusBarsMixin) with the
 * exact same layout, rows, regeneration wave, low-health shake and damage blink, so only the look
 * changes. Poisoned, withered and frozen hearts keep vanilla's look by default, since that's how you
 * notice those effects.
 */
public final class HeartsModule extends AbstractModule implements Configurable {

	private static final String KIND = "heart";
	private static final Identifier FILL = Identifier.of("velo-client", "textures/gui/hud/heart_fill.png");
	private static final Identifier OUTLINE = Identifier.of("velo-client", "textures/gui/hud/heart_outline.png");
	private static final Identifier DETAIL = Identifier.of("velo-client", "textures/gui/hud/heart_detail.png");
	private static final List<String> STYLES = List.of("Recolored", "Custom Drawing");

	private String style = "Recolored";
	private int heartColor = 0xFFE3262B;
	private int absorptionColor = 0xFFF5C33B;
	private int emptyColor = 0xFF2B2323;
	private int outlineColor = 0xFF000000;
	private boolean tintCustomIcon = false;
	private boolean vanillaForEffects = true;

	public HeartsModule() {
		super("hearts", "Hearts", "Recolor your hearts or use your own heart icon.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	private static HeartsModule active() {
		var module = ModuleRegistry.get("hearts").orElse(null);
		return module instanceof HeartsModule hearts && hearts.isEnabled() ? hearts : null;
	}

	/**
	 * Called at the start of vanilla's heart drawing with its own arguments; returns true when the
	 * hearts were drawn here (vanilla's are then skipped).
	 */
	public static boolean render(DrawContext context, PlayerEntity player, int xLeft, int yLineBase, int rowHeight,
			int regenIndex, float maxHealth, int lastHealth, int health, int absorption, boolean blinking, int ticks) {
		HeartsModule module = active();
		if (module == null) {
			return false;
		}
		if (module.vanillaForEffects && hasSpecialHearts(player)) {
			return false;
		}
		boolean custom = module.style.equals("Custom Drawing") && StatusBarIcons.hasDrawing(KIND);
		// Same seed vanilla uses for the frame, so the low-health shake matches.
		Random random = new Random(ticks * 312871L);
		int containers = (int) Math.ceil(maxHealth / 2.0);
		int absorptionContainers = (int) Math.ceil(absorption / 2.0);
		int maxHalves = containers * 2;
		for (int index = containers + absorptionContainers - 1; index >= 0; index--) {
			int x = xLeft + (index % 10) * 8;
			int y = yLineBase - (index / 10) * rowHeight;
			if (health + absorption <= 4) {
				y += random.nextInt(2);
			}
			if (index < containers && index == regenIndex) {
				y -= 2;
			}
			module.drawContainer(context, custom, x, y, blinking);
			int halves = index * 2;
			if (index >= containers) {
				int absorptionHalves = halves - maxHalves;
				if (absorptionHalves < absorption) {
					module.drawHeart(context, custom, x, y, absorptionHalves + 1 == absorption, module.absorptionColor, true);
				}
			}
			if (blinking && halves < lastHealth) {
				module.drawHeart(context, custom, x, y, halves + 1 == lastHealth, 0xFFFFFFFF, false);
			}
			if (halves < health) {
				module.drawHeart(context, custom, x, y, halves + 1 == health, module.heartColor, false);
			}
		}
		return true;
	}

	private void drawContainer(DrawContext context, boolean custom, int x, int y, boolean blinking) {
		if (custom) {
			StatusBarIcons.draw(context, KIND, StatusBarIcons.State.EMPTY, x, y, 0xFFFFFFFF);
			return;
		}
		layer(context, FILL, x, y, false, emptyColor);
		layer(context, OUTLINE, x, y, false, blinking ? 0xFFFFFFFF : outlineColor);
	}

	private void drawHeart(DrawContext context, boolean custom, int x, int y, boolean half, int color, boolean absorbing) {
		if (custom) {
			StatusBarIcons.draw(context, KIND, half ? StatusBarIcons.State.HALF : StatusBarIcons.State.FULL, x, y,
					tintCustomIcon || absorbing ? color : 0xFFFFFFFF);
			return;
		}
		layer(context, FILL, x, y, half, color);
		layer(context, DETAIL, x, y, half, 0xFFFFFFFF);
	}

	/** One 9x9 layer; a half heart is the left 5 columns, like vanilla's. */
	private static void layer(DrawContext context, Identifier texture, int x, int y, boolean half, int color) {
		int w = half ? 5 : 9;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, 9, w, 9, 9, 9, color);
	}

	private static boolean hasSpecialHearts(PlayerEntity player) {
		//? if <26.1 {
		return player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.POISON)
				|| player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.WITHER) || player.isFrozen();
		//?} else {
		/*return player.hasEffect(net.minecraft.world.effect.MobEffects.POISON)
				|| player.hasEffect(net.minecraft.world.effect.MobEffects.WITHER) || player.isFullyFrozen();
		*///?}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ColorField("Heart Color", () -> heartColor, v -> heartColor = v, false),
				new ConfigField.ColorField("Absorption Color", () -> absorptionColor, v -> absorptionColor = v, false),
				new ConfigField.ColorField("Empty Heart Color", () -> emptyColor, v -> emptyColor = v, true),
				new ConfigField.ColorField("Outline Color", () -> outlineColor, v -> outlineColor = v, true),
				new ConfigField.ToggleField("Keep Vanilla Look When Poisoned/Withered/Frozen", () -> vanillaForEffects, v -> vanillaForEffects = v),
				new ConfigField.ActionButtonField("Draw Custom Heart (or import a PNG)...", () -> {
					style = "Custom Drawing";
					net.veloclient.velo.client.gui.StatusIconEditor.openFromSettings(KIND, "Heart", heartColor);
				}),
				new ConfigField.ToggleField("Tint Custom Drawing With Heart Color", () -> tintCustomIcon, v -> tintCustomIcon = v),
				new ConfigField.ActionButtonField("Delete Custom Drawing", () -> {
					try {
						StatusBarIcons.delete(KIND);
					} catch (Exception ignored) {
						// nothing to delete
					}
					style = "Recolored";
				}));
	}
}
