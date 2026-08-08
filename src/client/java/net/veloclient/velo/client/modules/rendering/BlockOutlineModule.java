package net.veloclient.velo.client.modules.rendering;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * Replaces vanilla's own hardcoded-color, hardcoded-width selected-block
 * outline with a customizable one. {@code BlockOutlineCancelMixin} cancels
 * vanilla's own render call while this is enabled (so the two don't overlap)
 * and this draws its own box around {@code client.crosshairTarget} instead,
 * via the same {@code GizmoDrawing} API vanilla's own debug renderers use.
 *
 * <p>A plain bounding-box outline rather than vanilla's exact per-shape
 * voxel outline (stairs, slabs, fences, ...) - simpler, and the whole box is
 * what actually matters for "which block am I looking at", not its precise
 * collision shape.
 */
public final class BlockOutlineModule extends AbstractModule implements Configurable {

	private static volatile BlockOutlineModule instance;

	private int outlineColor = 0xFF000000;
	private double lineWidth = 2.0;

	public BlockOutlineModule() {
		super("block-outline", "Block Outline",
				"Replaces the default selected-block outline with a customizable color and line width.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::onRender);
	}

	/** Read from {@code BlockOutlineCancelMixin} to decide whether to cancel vanilla's own outline render call. */
	public static boolean shouldReplaceVanillaOutline() {
		BlockOutlineModule module = instance;
		return module != null && module.isEnabled();
	}

	private void onRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		HitResult target = client.crosshairTarget;
		if (!(target instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos pos = blockHit.getBlockPos();
		Box box = expandSlightly(new Box(pos));
		GizmoDrawing.box(box, DrawStyle.stroked(outlineColor, (float) lineWidth)).ignoreOcclusion();
	}

	//? if <26.1 {
	private static Box expandSlightly(Box box) {
		return box.expand(0.002);
	}
	//?} else {
	/*private static Box expandSlightly(Box box) {
		return box.inflate(0.002);
	}
	*///?}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ColorField("Outline Color", () -> outlineColor, v -> outlineColor = v, true),
				new ConfigField.SliderField("Line Width", 0.5, 5.0, () -> lineWidth,
						v -> lineWidth = v, v -> String.format(Locale.ROOT, "%.1f", v)));
	}
}
