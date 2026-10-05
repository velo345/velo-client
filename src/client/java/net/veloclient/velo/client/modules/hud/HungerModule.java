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
 * Shows your saturation the way hunger mods do: a colored outline around the food icons. Your food
 * bar only starts going down once saturation is used up, so the outline tells you how long you've
 * got. Each outlined icon is 2 saturation points (half an icon = 1). The food icons themselves can be
 * recolored or replaced with your own PNG. Takes over vanilla's food drawing (see StatusBarsMixin)
 * with the same layout and the same "starving" shake.
 */
public final class HungerModule extends AbstractModule implements Configurable {

	private static final String KIND = "food";
	private static final Identifier FILL = Identifier.of("velo-client", "textures/gui/hud/food_fill.png");
	private static final Identifier OUTLINE = Identifier.of("velo-client", "textures/gui/hud/food_outline.png");
	private static final Identifier DETAIL = Identifier.of("velo-client", "textures/gui/hud/food_detail.png");
	private static final List<String> STYLES = List.of("Vanilla", "Recolored", "Custom Drawing");

	private String style = "Vanilla";
	private boolean showSaturation = true;
	private int saturationColor = 0xFFFFC83A;
	private int foodColor = 0xFFC27A3E;
	private int hungerEffectColor = 0xFF7D9C3A;
	private int emptyColor = 0xFF2B2323;
	private int outlineColor = 0xFF000000;
	private boolean tintCustomIcon = false;

	public HungerModule() {
		super("hunger", "Hunger & Saturation",
				"Outlines your food icons by how much saturation you have left, and lets you recolor or replace the food icon.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	private static HungerModule active() {
		var module = ModuleRegistry.get("hunger").orElse(null);
		return module instanceof HungerModule hunger && hunger.isEnabled() ? hunger : null;
	}

	/** Called at the start of vanilla's food drawing; returns true when the food row was drawn here. */
	public static boolean render(DrawContext context, PlayerEntity player, int top, int right, int ticks) {
		HungerModule module = active();
		if (module == null) {
			return false;
		}
		//? if <26.1 {
		int food = player.getHungerManager().getFoodLevel();
		float saturation = player.getHungerManager().getSaturationLevel();
		boolean hungerEffect = player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.HUNGER);
		//?} else {
		/*int food = player.getFoodData().getFoodLevel();
		float saturation = player.getFoodData().getSaturationLevel();
		boolean hungerEffect = player.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER);
		*///?}
		boolean custom = module.style.equals("Custom Drawing") && StatusBarIcons.hasDrawing(KIND);
		boolean recolored = !custom && !module.style.equals("Vanilla");
		Random random = new Random(ticks * 312871L + 1);
		for (int i = 0; i < 10; i++) {
			int y = top;
			if (saturation <= 0 && ticks % (food * 3 + 1) == 0) {
				y += random.nextInt(3) - 1;
			}
			int x = right - i * 8 - 9;
			boolean full = i * 2 + 1 < food;
			boolean half = i * 2 + 1 == food;
			if (custom) {
				StatusBarIcons.draw(context, KIND, StatusBarIcons.State.EMPTY, x, y, 0xFFFFFFFF);
				if (full || half) {
					int tint = module.tintCustomIcon ? (hungerEffect ? module.hungerEffectColor : module.foodColor) : 0xFFFFFFFF;
					StatusBarIcons.draw(context, KIND, half ? StatusBarIcons.State.HALF : StatusBarIcons.State.FULL, x, y, tint);
				}
			} else if (recolored) {
				layer(context, FILL, x, y, false, module.emptyColor);
				if (full || half) {
					layer(context, FILL, x, y, half, hungerEffect ? module.hungerEffectColor : module.foodColor);
					layer(context, DETAIL, x, y, half, 0xFFFFFFFF);
				}
				layer(context, OUTLINE, x, y, false, module.outlineColor);
			} else {
				String prefix = "hud/food_";
				String suffix = hungerEffect ? "_hunger" : "";
				context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", prefix + "empty" + suffix), x, y, 9, 9);
				if (full) {
					context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", prefix + "full" + suffix), x, y, 9, 9);
				} else if (half) {
					context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", prefix + "half" + suffix), x, y, 9, 9);
				}
			}
			if (module.showSaturation) {
				float points = Math.min(2f, saturation - i * 2);
				if (points > 0) {
					module.drawSaturation(context, custom, recolored, x, y, points < 2f);
				}
			}
		}
		return true;
	}

	private void drawSaturation(DrawContext context, boolean custom, boolean recolored, int x, int y, boolean half) {
		if (custom) {
			Identifier outline = StatusBarIcons.drawingOutline(KIND);
			if (outline != null) {
				StatusBarIcons.drawSquare(context, outline, StatusBarIcons.drawingOutlineSize(KIND), x, y, half, saturationColor);
			}
			return;
		}
		if (recolored) {
			layer(context, OUTLINE, x, y, half, saturationColor);
			return;
		}
		Identifier outline = StatusBarIcons.vanillaOutline("hud/food_full");
		if (outline == null) {
			layer(context, OUTLINE, x, y, half, saturationColor);
			return;
		}
		StatusBarIcons.drawSquare(context, outline, StatusBarIcons.vanillaSize("hud/food_full"), x, y, half, saturationColor);
	}

	private static void layer(DrawContext context, Identifier texture, int x, int y, boolean half, int color) {
		int w = half ? 5 : 9;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, 9, w, 9, 9, 9, color);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show Saturation Outline", () -> showSaturation, v -> showSaturation = v),
				new ConfigField.ColorField("Saturation Color", () -> saturationColor, v -> saturationColor = v, true),
				new ConfigField.ChoiceField("Food Icon Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ColorField("Food Color (Recolored)", () -> foodColor, v -> foodColor = v, false),
				new ConfigField.ColorField("Food Color With Hunger Effect", () -> hungerEffectColor, v -> hungerEffectColor = v, false),
				new ConfigField.ColorField("Empty Food Color", () -> emptyColor, v -> emptyColor = v, true),
				new ConfigField.ColorField("Outline Color", () -> outlineColor, v -> outlineColor = v, true),
				new ConfigField.ActionButtonField("Draw Custom Food Icon (or import a PNG)...", () -> {
					style = "Custom Drawing";
					net.veloclient.velo.client.gui.StatusIconEditor.openFromSettings(KIND, "Food Icon", foodColor);
				}),
				new ConfigField.ToggleField("Tint Custom Drawing With Food Color", () -> tintCustomIcon, v -> tintCustomIcon = v),
				new ConfigField.ActionButtonField("Delete Custom Drawing", () -> {
					try {
						StatusBarIcons.delete(KIND);
					} catch (Exception ignored) {
						// nothing to delete
					}
					style = "Vanilla";
				}));
	}
}
