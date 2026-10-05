package net.veloclient.velo.client.hud;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Places every enabled HUD element the player hasn't positioned themselves: each one stacks into
 * its corner (see {@link HudPosition.Corner}) in module order, 2px apart, so the default HUD is
 * tidy edges only - never the middle of the screen, never overlapping, and stacks close up when an
 * element is turned off or shrinks. Runs once per frame before the HUD (and the HUD editor) draws.
 */
public final class HudAutoLayout {

	private static final int MARGIN = 2;
	private static final int GAP = 2;

	private HudAutoLayout() {
	}

	public static void update(int screenW, int screenH) {
		Map<HudPosition.Corner, List<HudModule>> groups = new EnumMap<>(HudPosition.Corner.class);
		for (Module module : ModuleRegistry.all()) {
			if (module instanceof HudModule hud && hud.isEnabled() && !hud.position().placed()) {
				groups.computeIfAbsent(hud.position().corner(), c -> new ArrayList<>()).add(hud);
			}
		}

		// Top-left: down from the corner.
		int topLeftBottom = stackDown(groups.get(HudPosition.Corner.TOP_LEFT), MARGIN, screenW, screenH, false);
		// Left side: below the top-left stack, starting no higher than a third of the screen.
		stackDown(groups.get(HudPosition.Corner.LEFT_SIDE), Math.max(screenH * 3 / 10, topLeftBottom + 8), screenW, screenH, false);
		// Top-right: down from the corner, below vanilla's effect icons when those are showing.
		stackDown(groups.get(HudPosition.Corner.TOP_RIGHT), MARGIN + vanillaEffectRows() * 26, screenW, screenH, true);
		// Right side (scoreboard): centred vertically, like vanilla's own sidebar.
		List<HudModule> rightSide = groups.get(HudPosition.Corner.RIGHT_SIDE);
		if (rightSide != null) {
			int total = 0;
			for (HudModule hud : rightSide) {
				total += height(hud) + GAP;
			}
			stackDown(rightSide, Math.max(MARGIN, (screenH - total) / 2), screenW, screenH, true);
		}
		// Top centre (boss bars): centred, down from the top edge like vanilla's own boss bar.
		List<HudModule> topCenter = groups.get(HudPosition.Corner.TOP_CENTER);
		if (topCenter != null) {
			int y = MARGIN + 1;
			for (HudModule hud : topCenter) {
				int h = height(hud);
				if (h <= 0) {
					continue;
				}
				hud.position().setAuto((screenW - width(hud)) / 2, y, screenW, screenH);
				y += h + GAP;
			}
		}
		// Bottom-right: up from the corner (bottom-left belongs to chat).
		List<HudModule> bottomRight = groups.get(HudPosition.Corner.BOTTOM_RIGHT);
		if (bottomRight != null) {
			int y = screenH - MARGIN;
			for (HudModule hud : bottomRight) {
				int h = height(hud);
				if (h <= 0) {
					continue;
				}
				y -= h;
				hud.position().setAuto(screenW - width(hud) - MARGIN, Math.max(0, y), screenW, screenH);
				y -= GAP;
			}
		}
	}

	/** Lays modules out downward from {@code y}; returns the y below the last one. */
	private static int stackDown(List<HudModule> modules, int y, int screenW, int screenH, boolean rightAligned) {
		if (modules == null) {
			return y;
		}
		for (HudModule hud : modules) {
			int h = height(hud);
			if (h <= 0) {
				continue; // nothing to show right now (e.g. no scoreboard) - takes no space
			}
			int w = width(hud);
			int x = rightAligned ? screenW - w - MARGIN : MARGIN;
			hud.position().setAuto(x, Math.min(y, Math.max(0, screenH - h)), screenW, screenH);
			y += h + GAP;
		}
		return y;
	}

	private static int width(HudModule hud) {
		return Math.round(hud.width() * hud.position().scale());
	}

	private static int height(HudModule hud) {
		return Math.round(hud.height() * hud.position().scale());
	}

	/** How many rows of vanilla status-effect icons are on screen (0-2) - they live in the top-right corner. */
	private static int vanillaEffectRows() {
		if (ModuleRegistry.get("potion-timers").map(Module::isEnabled).orElse(false)) {
			return 0; // Potion Timers replaces vanilla's icons
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return 0;
		}
		boolean beneficial = false;
		boolean harmful = false;
		//? if <26.1 {
		for (var effect : client.player.getStatusEffects()) {
			if (!effect.shouldShowIcon()) {
				continue;
			}
			if (effect.getEffectType().value().isBeneficial()) {
				beneficial = true;
			} else {
				harmful = true;
			}
		}
		//?} else {
		/*for (var effect : client.player.getActiveEffects()) {
			if (!effect.showIcon()) {
				continue;
			}
			if (effect.getEffect().value().isBeneficial()) {
				beneficial = true;
			} else {
				harmful = true;
			}
		}
		*///?}
		return (beneficial ? 1 : 0) + (harmful ? 1 : 0);
	}
}
