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
	private static final String VANILLA = "Vanilla Recolored";
	private static final String VELO = "Velo Style";
	private static final List<String> STYLES = List.of(VANILLA, VELO, "Custom Drawing");

	private String style = VANILLA;
	/** Step aside when the server's resource pack restyles or hides this bar (MCC Island and others). */
	private boolean serverWins = true;
	/** Per frame, set by {@link #render}: the vanilla sprites are being recoloured (and whether the world is hardcore). */
	private boolean vanillaLook;
	private boolean hardcore;
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
			int regenIndex, float maxHealth, int currentHealth, int delayedHealth, int absorption, boolean blinking, int ticks) {
		HeartsModule module = active();
		if (module == null) {
			return false;
		}
		if (module.serverWins && net.veloclient.velo.client.hud.ServerHudPack.overridesHearts()) {
			return false; // the server's pack styles or hides this bar itself
		}
		boolean custom = module.style.equals("Custom Drawing") && StatusBarIcons.hasDrawing(KIND);
		// Your own drawing has its poison / wither / frozen hearts (made from it, or drawn) - only the other styles fall
		// back to vanilla's when that's switched on.
		if (!custom && module.vanillaForEffects && hasSpecialHearts(player)) {
			return false;
		}
		module.variant = custom ? effectVariant(player) : StatusBarIcons.Variant.NORMAL;
		module.vanillaLook = !custom && !module.style.equals(VELO);
		module.hardcore = module.vanillaLook && isHardcore();
		// Vanilla's parameter names are misleading: "lastHealth" is the player's CURRENT health (what the hearts fill to) and
		// "health" is the delayed value that only catches up after a second without damage (the white damage flash goes up to it).
		// Filling to the delayed value made hearts lag behind - or not move at all during a fight.
		int health = currentHealth;
		int lastHealth = delayedHealth;
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
		// AppleSkin's "health restored by the held food" preview, which would have drawn after vanilla's hearts.
		net.veloclient.velo.client.util.AppleSkinCompat.afterHealth(context, player, xLeft, yLineBase, rowHeight, regenIndex, maxHealth,
				currentHealth, delayedHealth, absorption, blinking);
		return true;
	}

	private void drawContainer(DrawContext context, boolean custom, int x, int y, boolean blinking) {
		if (vanillaLook) {
			// (no "_blinking" container: its white outline flashed next to recoloured hearts)
			vanillaSprite(context, "container" + (hardcore ? "_hardcore" : ""), x, y);
			return;
		}
		if (custom) {
			StatusBarIcons.drawVariant(context, KIND, variant == StatusBarIcons.Variant.WITHERED ? variant : StatusBarIcons.Variant.NORMAL,
					StatusBarIcons.State.EMPTY, x, y, 0xFFFFFFFF);
			return;
		}
		layer(context, FILL, x, y, false, emptyColor);
		layer(context, OUTLINE, x, y, false, blinking ? flashColor(heartColor) : outlineColor);
	}

	private void drawHeart(DrawContext context, boolean custom, int x, int y, boolean half, int color, boolean absorbing) {
		if (vanillaLook) {
			String shape = (half ? "half" : "full");
			if (color == 0xFFFFFFFF && !absorbing) {
				// The flash after taking damage: a light version of YOUR heart colour (vanilla's white-red blinking sprite
				// clashed with recoloured hearts - owner 2026-11-25)
				recoloredSprite(context, "hud/heart/" + (hardcore ? "hardcore_" : "") + shape, flashColor(heartColor), x, y);
			} else if (absorbing) {
				recoloredSprite(context, "hud/heart/absorbing_" + (hardcore ? "hardcore_" : "") + shape, color, x, y);
			} else {
				recoloredSprite(context, "hud/heart/" + (hardcore ? "hardcore_" : "") + shape, color, x, y);
			}
			return;
		}
		if (custom) {
			StatusBarIcons.State state = half ? StatusBarIcons.State.HALF : StatusBarIcons.State.FULL;
			if (color == 0xFFFFFFFF && !absorbing) {
				// the damage flash: the drawing, lightened toward white in your heart colour
				StatusBarIcons.draw(context, KIND, state, x, y, tintCustomIcon ? flashColor(heartColor) : 0xFFFFFFFF);
			} else if (absorbing) {
				StatusBarIcons.drawVariant(context, KIND, StatusBarIcons.Variant.ABSORBING, state, x, y, 0xFFFFFFFF);
			} else if (variant != StatusBarIcons.Variant.NORMAL) {
				StatusBarIcons.drawVariant(context, KIND, variant, state, x, y, 0xFFFFFFFF);
			} else {
				StatusBarIcons.draw(context, KIND, state, x, y, tintCustomIcon ? color : 0xFFFFFFFF);
			}
			return;
		}
		layer(context, FILL, x, y, half, color == 0xFFFFFFFF && !absorbing ? flashColor(heartColor) : color);
		layer(context, DETAIL, x, y, half, 0xFFFFFFFF);
	}

	/** The damage flash colour: the heart colour mixed most of the way to white (reads as "just lost", stays on-theme). */
	private static int flashColor(int color) {
		int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
		r = r + (255 - r) * 3 / 5;
		g = g + (255 - g) * 3 / 5;
		b = b + (255 - b) * 3 / 5;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static void vanillaSprite(DrawContext context, String name, int x, int y) {
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/heart/" + name), x, y, 9, 9);
	}

	private static void recoloredSprite(DrawContext context, String sprite, int color, int x, int y) {
		Identifier texture = StatusBarIcons.recoloredSprite(sprite, color);
		if (texture == null) {
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", sprite), x, y, 9, 9);
			return;
		}
		StatusBarIcons.drawSquare(context, texture, StatusBarIcons.recoloredSize(sprite, color), x, y, false, 0xFFFFFFFF);
	}

	private static boolean isHardcore() {
		//? if <26.1 {
		var world = net.minecraft.client.MinecraftClient.getInstance().world;
		return world != null && world.getLevelProperties().isHardcore();
		//?} else {
		/*var level = net.minecraft.client.Minecraft.getInstance().level;
		return level != null && level.getLevelData().isHardcore();
		*///?}
	}

	/** One 9x9 layer; a half heart is the left 5 columns, like vanilla's. */
	private static void layer(DrawContext context, Identifier texture, int x, int y, boolean half, int color) {
		int w = half ? 5 : 9;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, 9, w, 9, 9, 9, color);
	}

	/** This frame's heart variant for the custom drawing (vanilla's order: poison, then wither, then frozen). */
	private StatusBarIcons.Variant variant = StatusBarIcons.Variant.NORMAL;

	private static StatusBarIcons.Variant effectVariant(PlayerEntity player) {
		//? if <26.1 {
		if (player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.POISON)) {
			return StatusBarIcons.Variant.POISONED;
		}
		if (player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.WITHER)) {
			return StatusBarIcons.Variant.WITHERED;
		}
		return player.isFrozen() ? StatusBarIcons.Variant.FROZEN : StatusBarIcons.Variant.NORMAL;
		//?} else {
		/*if (player.hasEffect(net.minecraft.world.effect.MobEffects.POISON)) {
			return StatusBarIcons.Variant.POISONED;
		}
		if (player.hasEffect(net.minecraft.world.effect.MobEffects.WITHER)) {
			return StatusBarIcons.Variant.WITHERED;
		}
		return player.isFullyFrozen() ? StatusBarIcons.Variant.FROZEN : StatusBarIcons.Variant.NORMAL;
		*///?}
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

	private void openVariant(StatusBarIcons.Variant variant) {
		style = "Custom Drawing";
		//? if <26.1 {
		net.minecraft.client.gui.screen.Screen parent = net.minecraft.client.MinecraftClient.getInstance().currentScreen;
		//?} else if <26.2 {
		/*net.minecraft.client.gui.screens.Screen parent = net.minecraft.client.Minecraft.getInstance().screen;
		*///?} else {
		/*net.minecraft.client.gui.screens.Screen parent = net.minecraft.client.Minecraft.getInstance().gui.screen();
		*///?}
		net.veloclient.velo.client.gui.StatusIconEditor.openVariant(parent, KIND, variant, heartColor);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Use the Server's Hearts When Its Pack Has Its Own", () -> serverWins, v -> serverWins = v),
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = "Recolored".equals(v) ? VANILLA : v),
				new ConfigField.ColorField("Heart Color", () -> heartColor, v -> heartColor = v, false),
				new ConfigField.ColorField("Absorption Color", () -> absorptionColor, v -> absorptionColor = v, false),
				new ConfigField.ColorField("Empty Heart Color (Velo Style)", () -> emptyColor, v -> emptyColor = v, true),
				new ConfigField.ColorField("Outline Color (Velo Style)", () -> outlineColor, v -> outlineColor = v, true),
				new ConfigField.ToggleField("Keep Vanilla Look When Poisoned/Withered/Frozen (Velo & Recolored)", () -> vanillaForEffects, v -> vanillaForEffects = v),
				new ConfigField.ActionButtonField("Draw Custom Heart...", () -> {
					style = "Custom Drawing";
					net.veloclient.velo.client.gui.StatusIconEditor.openFromSettings(KIND, "Heart", heartColor);
				}),
				new ConfigField.ToggleField("Tint Custom Drawing With Heart Color", () -> tintCustomIcon, v -> tintCustomIcon = v),
				new ConfigField.HeaderField("Effect Hearts (made from your drawing, or draw your own)"),
				new ConfigField.ActionButtonField("Draw Absorption Heart...", () -> openVariant(StatusBarIcons.Variant.ABSORBING)),
				new ConfigField.ActionButtonField("Draw Poison Heart...", () -> openVariant(StatusBarIcons.Variant.POISONED)),
				new ConfigField.ActionButtonField("Draw Wither Heart...", () -> openVariant(StatusBarIcons.Variant.WITHERED)),
				new ConfigField.ActionButtonField("Draw Frozen Heart...", () -> openVariant(StatusBarIcons.Variant.FROZEN)),
				new ConfigField.ActionButtonField("Back To Automatic Effect Hearts", () -> {
					for (StatusBarIcons.Variant variant : StatusBarIcons.Variant.values()) {
						if (variant != StatusBarIcons.Variant.NORMAL) {
							try {
								StatusBarIcons.delete(variant.kind(KIND));
							} catch (Exception ignored) {
								// nothing to delete
							}
						}
					}
				}),
				new ConfigField.ActionButtonField("Delete Custom Drawing", () -> {
					try {
						StatusBarIcons.delete(KIND);
					} catch (Exception ignored) {
						// nothing to delete
					}
					style = VANILLA;
				}));
	}
}
