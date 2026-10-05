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

/**
 * Recolors the armor points row above the hearts, or swaps its icon for your own drawing (full /
 * half / empty, made in the pixel editor). Takes over vanilla's armor row (see StatusBarsMixin)
 * with the same position and fill rules.
 */
public final class ArmorBarModule extends AbstractModule implements Configurable {

	private static final String KIND = "armor";
	private static final Identifier FILL = Identifier.of("velo-client", "textures/gui/hud/armor_fill.png");
	private static final Identifier OUTLINE = Identifier.of("velo-client", "textures/gui/hud/armor_outline.png");
	private static final Identifier DETAIL = Identifier.of("velo-client", "textures/gui/hud/armor_detail.png");
	private static final List<String> STYLES = List.of("Recolored", "Custom Drawing");

	private String style = "Recolored";
	private int armorColor = 0xFFB9C7D6;
	private int emptyColor = 0xFF2B2B30;
	private int outlineColor = 0xFF000000;
	private boolean tintCustom = false;

	public ArmorBarModule() {
		super("armor-bar", "Armor Bar", "Recolor the armor points above your hearts or draw your own armor icon.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	/** Called at the start of vanilla's armor row with its own arguments; true = drawn here. */
	public static boolean render(DrawContext context, PlayerEntity player, int yLineBase, int healthRows, int rowHeight, int xLeft) {
		var module = ModuleRegistry.get("armor-bar").orElse(null);
		if (!(module instanceof ArmorBarModule armor) || !module.isEnabled()) {
			return false;
		}
		//? if <26.1 {
		int points = player.getArmor();
		//?} else {
		/*int points = player.getArmorValue();
		*///?}
		if (points <= 0) {
			return true;
		}
		boolean custom = armor.style.equals("Custom Drawing") && StatusBarIcons.hasDrawing(KIND);
		int y = yLineBase - (healthRows - 1) * rowHeight - 10;
		for (int i = 0; i < 10; i++) {
			int x = xLeft + i * 8;
			boolean full = i * 2 + 1 < points;
			boolean half = i * 2 + 1 == points;
			if (custom) {
				int tint = armor.tintCustom ? armor.armorColor : 0xFFFFFFFF;
				StatusBarIcons.draw(context, KIND, full ? StatusBarIcons.State.FULL : half ? StatusBarIcons.State.HALF
						: StatusBarIcons.State.EMPTY, x, y, full || half ? tint : 0xFFFFFFFF);
				if (half && StatusBarIcons.texture(KIND, StatusBarIcons.State.HALF) == null) {
					// The derived half only covers the left side - show the empty icon behind it.
					StatusBarIcons.draw(context, KIND, StatusBarIcons.State.EMPTY, x, y, 0xFFFFFFFF);
					StatusBarIcons.draw(context, KIND, StatusBarIcons.State.HALF, x, y, tint);
				}
				continue;
			}
			layer(context, FILL, x, y, false, armor.emptyColor);
			if (full || half) {
				layer(context, FILL, x, y, half, armor.armorColor);
				layer(context, DETAIL, x, y, half, 0xFFFFFFFF);
			}
			layer(context, OUTLINE, x, y, false, armor.outlineColor);
		}
		return true;
	}

	private static void layer(DrawContext context, Identifier texture, int x, int y, boolean half, int color) {
		int w = half ? 5 : 9;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, 9, w, 9, 9, 9, color);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ColorField("Armor Color", () -> armorColor, v -> armorColor = v, false),
				new ConfigField.ColorField("Empty Color", () -> emptyColor, v -> emptyColor = v, true),
				new ConfigField.ColorField("Outline Color", () -> outlineColor, v -> outlineColor = v, true),
				new ConfigField.ActionButtonField("Draw Custom Armor Icon (or import a PNG)...", () -> {
					style = "Custom Drawing";
					net.veloclient.velo.client.gui.StatusIconEditor.openFromSettings(KIND, "Armor Icon", armorColor);
				}),
				new ConfigField.ToggleField("Tint Custom Drawing With Armor Color", () -> tintCustom, v -> tintCustom = v),
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
