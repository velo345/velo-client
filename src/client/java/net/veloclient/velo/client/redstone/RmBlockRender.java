package net.veloclient.velo.client.redstone;

import net.veloclient.velo.client.pvp.PvpCompat;

import java.util.List;

/**
 * Draws locked schematic placements as the real blocks - with their textures, shapes and tints, like
 * Litematica - instead of coloured boxes. Only blocks the world is still missing are drawn (see
 * {@link RmRender#ghostBlocks()}). So they're never mistaken for placed blocks they're drawn as
 * holograms: a little smaller than a block, fully lit, washed with a soft white glow that slowly
 * shimmers. Wrong / extra blocks get their coloured overlay from the gizmo pass.
 *
 * <p>Every Minecraft call lives here per version: 1.21.11 submits whole block states, 26.x resolves
 * each state's model once ({@code BlockModelResolver}) and submits the cached model per position.
 */
final class RmBlockRender {

	/** Full block and sky light. */
	private static final int LIGHT = 0xF000F0;
	/** Holograms are drawn this much smaller than a block, centred. */
	private static final float SCALE = 0.86f;

	/** How white the hologram glow is right now (a slow shimmer between ~0.22 and ~0.38). */
	private static float glow() {
		return 0.3f + 0.08f * (float) Math.sin(System.currentTimeMillis() / 650.0);
	}

	private RmBlockRender() {
	}

	//? if <26.1 {
	static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (RedstoneMode.active()) {
				submit(context.matrices(), context.commandQueue());
			}
		});
	}

	private static void submit(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue) {
		List<Object[]> blocks = RmRender.ghostBlocks();
		if (blocks.isEmpty()) {
			return;
		}
		double[] cam = PvpCompat.cameraPos();
		int overlay = net.minecraft.client.render.OverlayTexture.getUv(glow(), false);
		float inset = (1 - SCALE) / 2;
		for (Object[] b : blocks) {
			matrices.push();
			matrices.translate((int) b[0] - cam[0] + inset, (int) b[1] - cam[1] + inset, (int) b[2] - cam[2] + inset);
			matrices.scale(SCALE, SCALE, SCALE);
			queue.submitBlock(matrices, (net.minecraft.block.BlockState) b[3], LIGHT, overlay, 0);
			matrices.pop();
		}
	}
	//?} else {
	/*private static final java.util.Map<Object, net.minecraft.client.renderer.block.BlockModelRenderState> MODELS = new java.util.IdentityHashMap<>();
	private static net.minecraft.client.renderer.block.BlockModelResolver resolver;
	private static Object resolverOwner;

	static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (RedstoneMode.active()) {
				submit(context.poseStack(), context.submitNodeCollector());
			}
		});
	}

	private static net.minecraft.client.renderer.block.BlockModelRenderState model(Object state) {
		var models = net.minecraft.client.Minecraft.getInstance().getModelManager();
		if (resolver == null || resolverOwner != models) {
			// A resource reload makes a new model manager - start over with it.
			resolver = new net.minecraft.client.renderer.block.BlockModelResolver(models);
			resolverOwner = models;
			MODELS.clear();
		}
		if (MODELS.size() > 4096) {
			MODELS.clear();
		}
		return MODELS.computeIfAbsent(state, s -> {
			var renderState = new net.minecraft.client.renderer.block.BlockModelRenderState();
			resolver.update(renderState, (net.minecraft.world.level.block.state.BlockState) s,
					net.minecraft.client.renderer.block.model.BlockDisplayContext.create());
			return renderState;
		});
	}

	private static void submit(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector) {
		List<Object[]> blocks = RmRender.ghostBlocks();
		if (blocks.isEmpty()) {
			return;
		}
		double[] cam = PvpCompat.cameraPos();
		int overlay = net.minecraft.client.renderer.texture.OverlayTexture.pack(glow(), false);
		float inset = (1 - SCALE) / 2;
		for (Object[] b : blocks) {
			var model = model(b[3]);
			if (model.isEmpty()) {
				continue;
			}
			poseStack.pushPose();
			poseStack.translate((int) b[0] - cam[0] + inset, (int) b[1] - cam[1] + inset, (int) b[2] - cam[2] + inset);
			poseStack.scale(SCALE, SCALE, SCALE);
			model.submit(poseStack, collector, LIGHT, overlay, 0);
			poseStack.popPose();
		}
	}
	*///?}
}
