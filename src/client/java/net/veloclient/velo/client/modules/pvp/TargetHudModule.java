package net.veloclient.velo.client.modules.pvp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.veloclient.velo.client.gui.PlayerHeads;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.pvp.CombatTracker;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * A card for the player you're fighting: head, name, a smooth health bar with the exact number,
 * absorption, their armor and held item, distance, and whether you're ahead on health. Shows the
 * player you last hit (for a few seconds) - optionally also the one under your crosshair. NPCs and teammates never show.
 */
public final class TargetHudModule extends AbstractModule implements HudModule, Configurable {

	private static final int WIDTH = 150;
	private static final int HEIGHT = 46;

	private final HudPosition position = new HudPosition(0.58f, 0.55f);
	private boolean showArmor = true;
	private boolean showDistance = true;
	private boolean showWinning = true;
	private double holdSeconds = 4;
	private boolean showLookedAt = false;
	private float shownHealth = -1;
	private int shownId = Integer.MIN_VALUE;

	public TargetHudModule() {
		super("target-hud", "Target HUD", "Shows the player you're fighting: health, armor, distance and who's winning.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private LivingEntity target() {
		int recent = CombatTracker.recentTarget((int) Math.round(holdSeconds * 20));
		if (recent != Integer.MIN_VALUE && PvpCompat.entityById(recent) instanceof LivingEntity living
				&& PvpCompat.isOpponent(living) && living.isAlive()) {
			return living;
		}
		// Only while you're fighting by default - looking at an NPC or a teammate in the lobby isn't a fight.
		if (!showLookedAt) {
			return null;
		}
		LivingEntity looked = PvpCompat.crosshairPlayer();
		return looked != null && PvpCompat.isOpponent(looked) ? looked : null;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		LivingEntity self = PvpCompat.localPlayer();
		if (self == null) {
			return;
		}
		LivingEntity target = target();
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		if (target == null && !editing) {
			shownId = Integer.MIN_VALUE;
			return;
		}
		String name = target == null ? "Target" : PvpCompat.name(target);
		float health = target == null ? 14f : target.getHealth();
		float max = target == null ? 20f : Math.max(1f, target.getMaxHealth());
		float absorption = target == null ? 4f : target.getAbsorptionAmount();
		int id = target == null ? -1 : target.getId();
		if (id != shownId) {
			shownId = id;
			shownHealth = health;
		}
		// Ease the bar towards the real value so hits read as a quick drop, not a jump.
		shownHealth += (health - shownHealth) * Math.min(1f, 0.25f + tickDelta * 0.1f);

		VeloDraw.shadow(context, x, y, WIDTH, HEIGHT, 8, 6, 2, 0x60000000);
		net.veloclient.velo.client.hud.HudLook.panel(context, x, y, WIDTH, HEIGHT);
		if (target != null) {
			PlayerHeads.draw(context, PvpCompat.uuid(target).toString(), name, x + 6, y + 6, 34);
		} else {
			VeloDraw.fillRounded(context, x + 6, y + 6, 34, 34, 6, 0x40000000);
		}
		int textX = x + 46;
		net.veloclient.velo.client.hud.HudLook.draw(context, name, textX, y + 6, 0xFFFFFFFF);
		if (showDistance) {
			String distance = String.format(Locale.ROOT, "%.1fm", target == null ? 3.1 : PvpCompat.distanceTo(target));
			int w = net.veloclient.velo.client.hud.HudLook.measure(distance);
			net.veloclient.velo.client.hud.HudLook.draw(context, distance, x + WIDTH - 6 - w, y + 6, 0xFFB8B8C8);
		}
		// Health bar
		int barX = textX;
		int barY = y + 19;
		int barW = WIDTH - 52;
		float ratio = Math.max(0f, Math.min(1f, shownHealth / max));
		int barColor = ratio > 0.6f ? 0xFF6FE39A : ratio > 0.3f ? 0xFFFFC53D : 0xFFFF5555;
		VeloDraw.fillRounded(context, barX, barY, barW, 6, 3, 0x55000000);
		if (ratio > 0f) {
			VeloDraw.fillRounded(context, barX, barY, Math.max(6, Math.round(barW * ratio)), 6, 3, barColor);
		}
		if (absorption > 0f) {
			float absorbRatio = Math.min(1f, absorption / max);
			VeloDraw.fillRounded(context, barX, barY + 7, Math.max(4, Math.round(barW * absorbRatio)), 2, 1, 0xFFFFD84A);
		}
		String hp = String.format(Locale.ROOT, "%.1f", health) + (absorption > 0f ? String.format(Locale.ROOT, " +%.0f", absorption) : "");
		net.veloclient.velo.client.hud.HudLook.draw(context, hp, textX, y + 30, 0xFFFFFFFF);
		if (showWinning) {
			float mine = self.getHealth() + self.getAbsorptionAmount();
			float theirs = health + absorption;
			String verdict = mine > theirs + 0.5f ? "Winning" : mine + 0.5f < theirs ? "Losing" : "Even";
			int color = verdict.equals("Winning") ? 0xFF6FE39A : verdict.equals("Losing") ? 0xFFFF5555 : 0xFFFFC53D;
			int w = net.veloclient.velo.client.hud.HudLook.measure(verdict);
			net.veloclient.velo.client.hud.HudLook.draw(context, verdict, x + WIDTH - 6 - w, y + 30, color);
		}
		if (showArmor && target != null) {
			int ix = x + WIDTH + 2;
			int iy = y;
			for (ItemStack piece : PvpCompat.armor(target)) {
				if (!piece.isEmpty()) {
					context.drawItemWithoutEntity(piece, ix, iy);
				}
				iy += 12;
			}
			ItemStack held = PvpCompat.mainHand(target);
			if (!held.isEmpty()) {
				context.drawItemWithoutEntity(held, ix + 16, y + 15);
			}
		}
	}

	@Override
	public int width() {
		return WIDTH + (showArmor ? 36 : 0);
	}

	@Override
	public int height() {
		return HEIGHT;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show Armor & Held Item", () -> showArmor, v -> showArmor = v),
				new ConfigField.ToggleField("Show Distance", () -> showDistance, v -> showDistance = v),
				new ConfigField.ToggleField("Show Winning / Losing", () -> showWinning, v -> showWinning = v),
				new ConfigField.ToggleField("Also Show The Player You Look At", () -> showLookedAt, v -> showLookedAt = v),
				new ConfigField.SliderField("Keep Showing After Hit (s)", 1, 15, () -> holdSeconds, v -> holdSeconds = Math.round(v),
						v -> (int) Math.round(v) + "s"));
	}
}
