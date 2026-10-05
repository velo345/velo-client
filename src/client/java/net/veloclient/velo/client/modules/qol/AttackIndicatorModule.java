package net.veloclient.velo.client.modules.qol;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * The attack-cooldown ("charging sword/axe") indicator under a Custom Crosshair. Replacing the
 * crosshair replaces vanilla's whole crosshair element - which is also where vanilla draws this
 * indicator - so it used to vanish with a custom crosshair equipped. Drawn here instead, in a
 * style of your choice. Reads only the local player's own cooldown, exactly what vanilla shows.
 */
public final class AttackIndicatorModule extends AbstractModule implements Configurable {

	private static final List<String> STYLES = List.of("Bar", "Thin Line", "Vanilla");
	private static final Identifier VANILLA_FULL = Identifier.of("minecraft", "hud/crosshair_attack_indicator_full");
	private static final Identifier VANILLA_BACKGROUND = Identifier.of("minecraft", "hud/crosshair_attack_indicator_background");
	private static final Identifier VANILLA_PROGRESS = Identifier.of("minecraft", "hud/crosshair_attack_indicator_progress");

	private String style = "Thin Line";
	private int fillColor = 0xFFFFFFFF;
	private int backgroundColor = 0x80000000;
	private int readyColor = 0xFF6FE39A;
	private int width = 16;
	private int height = 3;
	private int offset = 10;
	private boolean showReady = true;
	private boolean followVanillaSetting = true;

	public AttackIndicatorModule() {
		super("attack-indicator", "Attack Indicator",
				"Shows the attack charge bar under your custom crosshair, styled and colored the way you like.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
	}

	/** Called right after the custom crosshair is drawn (see HudManager). */
	public static void renderUnderCrosshair(DrawContext context, int screenWidth, int screenHeight, int crosshairSize) {
		var module = net.veloclient.velo.module.ModuleRegistry.get("attack-indicator").orElse(null);
		if (module instanceof AttackIndicatorModule indicator && indicator.isEnabled()) {
			indicator.draw(context, screenWidth, screenHeight, crosshairSize);
		}
	}

	private void draw(DrawContext context, int screenWidth, int screenHeight, int crosshairSize) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		//? if <26.1 {
		boolean crosshairMode = client.options.getAttackIndicator().getValue() == net.minecraft.client.option.AttackIndicator.CROSSHAIR;
		float progress = client.player.getAttackCooldownProgress(0.0F);
		boolean slowWeapon = client.player.getAttackCooldownProgressPerTick() > 5.0F;
		//?} else {
		/*boolean crosshairMode = client.options.attackIndicator().get() == net.minecraft.client.AttackIndicatorStatus.CROSSHAIR;
		float progress = client.player.getAttackStrengthScale(0.0F);
		boolean slowWeapon = client.player.getCurrentItemAttackStrengthDelay() > 5.0F;
		*///?}
		if (followVanillaSetting && !crosshairMode) {
			return;
		}
		var target = client.targetedEntity;
		boolean ready = showReady && progress >= 1.0F && slowWeapon
				&& target instanceof net.minecraft.entity.LivingEntity && target.isAlive();
		if (!ready && progress >= 1.0F) {
			return;
		}

		int centerX = screenWidth / 2;
		int top = screenHeight / 2 + Math.max(crosshairSize, 8) / 2 + offset - 8;
		if (style.equals("Vanilla")) {
			int x = centerX - 8;
			if (ready) {
				context.drawGuiTexture(RenderPipelines.CROSSHAIR, VANILLA_FULL, x, top, 16, 16);
			} else {
				context.drawGuiTexture(RenderPipelines.CROSSHAIR, VANILLA_BACKGROUND, x, top, 16, 4);
				context.drawGuiTexture(RenderPipelines.CROSSHAIR, VANILLA_PROGRESS, 16, 4, 0, 0, x, top, (int) (progress * 17.0F), 4);
			}
			return;
		}

		int barHeight = style.equals("Thin Line") ? 1 : height;
		int x = centerX - width / 2;
		if (ready) {
			VeloDraw.fillRounded(context, x, top, width, barHeight, Math.min(1, barHeight / 2), readyColor);
			return;
		}
		VeloDraw.fillRounded(context, x, top, width, barHeight, Math.min(1, barHeight / 2), backgroundColor);
		int filled = Math.round(width * Math.max(0f, Math.min(1f, progress)));
		if (filled > 0) {
			VeloDraw.fillRounded(context, x, top, filled, barHeight, Math.min(1, barHeight / 2), fillColor);
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ColorField("Charge Color", () -> fillColor, v -> fillColor = v, true),
				new ConfigField.ColorField("Background Color", () -> backgroundColor, v -> backgroundColor = v, true),
				new ConfigField.ColorField("Ready Color (aiming at a mob)", () -> readyColor, v -> readyColor = v, true),
				new ConfigField.SliderField("Width", 6, 48, () -> width, v -> width = (int) v, v -> (int) v + "px"),
				new ConfigField.SliderField("Height", 1, 6, () -> height, v -> height = (int) v, v -> (int) v + "px"),
				new ConfigField.SliderField("Distance Below Crosshair", 0, 24, () -> offset, v -> offset = (int) v, v -> (int) v + "px"),
				new ConfigField.ToggleField("Show When Fully Charged On A Mob", () -> showReady, v -> showReady = v),
				new ConfigField.ToggleField("Only When Minecraft's Attack Indicator Is \"Crosshair\"", () -> followVanillaSetting,
						v -> followVanillaSetting = v));
	}
}
