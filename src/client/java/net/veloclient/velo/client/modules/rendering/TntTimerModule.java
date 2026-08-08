package net.veloclient.velo.client.modules.rendering;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
//? if <26.1 {
import net.minecraft.entity.TntEntity;
//?} else {
/*import net.minecraft.world.entity.item.PrimedTnt;
*///?}
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.minecraft.world.debug.gizmo.TextGizmo;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * Floats a live countdown above every primed TNT entity in render range,
 * counting down from vanilla's own fuse ({@code getFuse()}, 80 ticks/4s by
 * default) - purely a readability aid for knowing exactly when a charge
 * will actually go off, using data the server already sends to render the
 * entity in the first place, nothing extra queried.
 *
 * <p>Positioned by hand from the entity's own real (continuous) coordinates
 * rather than via {@code GizmoDrawing.entityLabel}/{@code
 * Gizmos.billboardTextOverMob} - that call turned out to snap to the
 * entity's containing block cell ({@code getBlockX()}/{@code getBlockZ()},
 * confirmed via the real decompiled bytecode, not guessed), which for a
 * stationary debug label doesn't matter but for a falling/bouncing TNT
 * entity meant the countdown visibly snapped between block cells instead of
 * tracking it and wasn't centered over its actual position either.
 */
public final class TntTimerModule extends AbstractModule implements Configurable {

	private int textColor = 0xFFFF5555;
	private double textScale = 1.0;

	public TntTimerModule() {
		super("tnt-timer", "TNT Timer",
				"Shows a live countdown above primed TNT, with a customizable color and text size.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
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
		for (Entity entity : world.getEntities()) {
			//? if <26.1 {
			if (!(entity instanceof TntEntity tnt)) {
				continue;
			}
			int fuse = tnt.getFuse();
			//?} else {
			/*if (!(entity instanceof PrimedTnt tnt)) {
				continue;
			}
			int fuse = tnt.getFuse();
			*///?}
			String label = String.format(Locale.ROOT, "%.1fs", fuse / 20.0);
			if (net.veloclient.velo.client.modules.qol.SmallCapsModule.isHudSmallCapsEnabled()) {
				label = net.veloclient.velo.client.util.SmallCapsConverter.toSmallCaps(label);
			}
			//? if <26.1 {
			Vec3d entityPos = entity.getEntityPos();
			float entityHeight = entity.getHeight();
			//?} else {
			/*Vec3d entityPos = entity.position();
			float entityHeight = entity.getBbHeight();
			*///?}
			// Entity position is feet/bottom-center - a small gap above the
			// (roughly 1 block tall) TNT block itself, not several blocks up
			// like entityLabel's own tall-entity-tuned default offset was.
			Vec3d pos = entityPos.add(0, entityHeight + 0.35, 0);
			//? if <26.1 {
			TextGizmo.Style style = TextGizmo.Style.centered(textColor).scaled((float) textScale);
			//?} else {
			/*TextGizmo.Style style = TextGizmo.Style.forColorAndCentered(textColor).withScale((float) textScale);
			*///?}
			GizmoDrawing.text(label, pos, style);
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ColorField("Text Color", () -> textColor, v -> textColor = v, true),
				new ConfigField.SliderField("Text Size", 0.5, 3.0, () -> textScale,
						v -> textScale = v, v -> String.format(Locale.ROOT, "%.1f", v)));
	}
}
