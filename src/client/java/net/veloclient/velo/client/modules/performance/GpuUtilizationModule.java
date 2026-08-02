package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Shows GPU utilization percentage, same figure as vanilla's F3 GPU line. */
public final class GpuUtilizationModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.98f, 0.12f);

	public GpuUtilizationModule() {
		super("gpu-utilization", "GPU Utilization", "Shows current GPU utilization percentage.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		double percentage = client.getGpuUtilizationPercentage();
		int color = percentage > 100 ? 0xFFFF5555 : 0xFFFFFFFF;
		context.drawTextWithShadow(client.textRenderer, "GPU: " + Math.round(percentage) + "%", x, y, color);
	}

	@Override
	public int width() {
		return MinecraftClient.getInstance().textRenderer.getWidth("GPU: 000%");
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
