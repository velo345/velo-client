package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*import net.minecraft.client.model.Model;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;

/^*
 * 1.21.1 draws immediately (the render command queue came in 1.21.9): the same calls, written straight into the frame's
 * buffers, so Velo's renderers submit the same way on every version.
 ^/
public final class OrderedRenderCommandQueue {

	@FunctionalInterface
	public interface Custom {
		void render(MatrixStack.Entry entry, VertexConsumer consumer);
	}

	private final VertexConsumerProvider consumers;

	private OrderedRenderCommandQueue(VertexConsumerProvider consumers) {
		this.consumers = consumers;
	}

	public static OrderedRenderCommandQueue of(VertexConsumerProvider consumers) {
		return new OrderedRenderCommandQueue(consumers);
	}

	public void submitCustom(MatrixStack matrices, RenderLayer layer, Custom custom) {
		if (consumers != null) {
			custom.render(matrices.peek(), consumers.getBuffer(layer));
		}
	}

	public void submitText(MatrixStack matrices, float x, float y, net.minecraft.text.OrderedText text, boolean shadow,
			net.minecraft.client.font.TextRenderer.TextLayerType layer, int light, int color, int background, int outline) {
		if (consumers != null) {
			net.minecraft.client.MinecraftClient.getInstance().textRenderer.draw(text, x, y, color, shadow, matrices.peek().getPositionMatrix(), consumers,
					layer, background, light);
		}
	}

	public void submitBlock(MatrixStack matrices, net.minecraft.block.BlockState state, int light, int overlay, int outline) {
		if (consumers != null) {
			net.minecraft.client.MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(state, matrices, consumers, light, overlay);
		}
	}

	public void submitModel(Object model, Object state, MatrixStack matrices, RenderLayer layer, int light, int overlay, int outline, Object crumbling) {
		if (consumers != null && model instanceof Model m) {
			m.render(matrices, consumers.getBuffer(layer), light, overlay);
		}
	}
}
*///?}
