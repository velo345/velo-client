package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
//? if <26.1 {
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.world.GameMode;
//?} else {
/*import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.GameType;
*///?}
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Renders entity hitbox outlines - informational only, for plugin/build hitbox debugging, not a
 * combat aid (it draws the exact box the server already uses, {@code Entity#getBoundingBox()},
 * nothing wider). Off by default and tagged Check Server Rules since some servers disallow any
 * hitbox rendering (design spec 6.3).
 *
 * <p>Its own renderer rather than a toggle over vanilla's F3 hitbox debug entry (which this
 * module used to just delegate to) - vanilla's own rendering is a fixed color with no way to
 * customize it, and the whole point of making this {@link Configurable} is letting players pick
 * a color/line width that's actually visible against their server's terrain.
 *
 * <p>Deliberately normal depth-tested rendering, not drawn through walls - this is informational
 * (the exact box the server already sends for entities you can already see), never an X-ray; a
 * box visible through terrain would show entities that aren't otherwise visible at all, which is
 * exactly the kind of advantage this client's own {@link SafetyTag} categories rule out. The
 * local player's own box is skipped too - nothing useful about drawing a box around yourself.
 */
public final class HitboxVisualizerModule extends AbstractModule implements Configurable {

	private int color = 0xFFFFFF00;
	private double lineWidth = 1.5;

	public HitboxVisualizerModule() {
		super("hitbox-visualizer", "Hitbox Visualizer",
				"Renders entity hitbox outlines for build/plugin testing, with a customizable color and line width.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, false);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::onRender);
	}

	private void onRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		DrawStyle style = DrawStyle.stroked(color, (float) lineWidth);
		for (Entity entity : world.getEntities()) {
			if (entity == client.player || !isVisible(client, entity)) {
				continue;
			}
			//? if <26.1 {
			if (entity instanceof EnderDragonEntity dragon) {
			//?} else {
			/*if (entity instanceof EnderDragon dragon) {
			*///?}
				// A dragon's overall getBoundingBox() doesn't match what the
				// server actually hit-tests against - it's hit-tested per
				// body part (head/neck/body/wings/tail), each with its own
				// box, so draw those instead of one big approximate box.
				//? if <26.1 {
				for (EnderDragonPart part : dragon.getBodyParts()) {
				//?} else {
				/*for (EnderDragonPart part : dragon.getSubEntities()) {
				*///?}
					GizmoDrawing.box(part.getBoundingBox(), style);
				}
				continue;
			}
			GizmoDrawing.box(entity.getBoundingBox(), style);
		}
	}

	/**
	 * Only draw boxes for entities the player can actually see: invisible
	 * entities (covers vanish-via-invisibility, not just players) and
	 * players in spectator mode are skipped rather than exposed through this
	 * debug tool.
	 */
	private static boolean isVisible(MinecraftClient client, Entity entity) {
		if (entity.isInvisible()) {
			return false;
		}
		if (entity instanceof PlayerEntity player) {
			var networkHandler = client.getNetworkHandler();
			PlayerListEntry entry = networkHandler == null ? null : networkHandler.getPlayerListEntry(player.getUuid());
			// No player-list entry (e.g. NPC-ish fake players some servers
			// spawn) means we can't tell their gamemode - keep showing
			// rather than guess.
			//? if <26.1 {
			if (entry != null && entry.getGameMode() == GameMode.SPECTATOR) {
				return false;
			}
			//?} else {
			/*if (entry != null && entry.getGameMode() == GameType.SPECTATOR) {
				return false;
			}
			*///?}
		}
		return true;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ColorField("Hitbox Color", () -> color, v -> color = v, true),
				new ConfigField.SliderField("Line Width", 0.5, 4.0, () -> lineWidth,
						v -> lineWidth = v, v -> String.format(java.util.Locale.ROOT, "%.1f", v)));
	}
}
