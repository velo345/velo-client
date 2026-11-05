package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

/^*
 * 1.21.1 has no HUD element registry (Fabric's came later): attached elements are drawn from HudRenderCallback, after the
 * vanilla HUD; replacing a vanilla element isn't possible there (VanillaHudGateMixin covers the ones Velo replaces).
 ^/
public final class HudElementRegistry {

	@FunctionalInterface
	public interface HudElement {
		void render(DrawContext context, RenderTickCounter tickCounter);
	}

	private HudElementRegistry() {
	}

	public static void attachElementAfter(Object anchor, Identifier id, HudElement element) {
		net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(element::render);
	}

	public static void attachElementBefore(Object anchor, Identifier id, HudElement element) {
		attachElementAfter(anchor, id, element);
	}

	public static void addLast(Identifier id, HudElement element) {
		attachElementAfter(null, id, element);
	}

	public static void addFirst(Identifier id, HudElement element) {
		attachElementAfter(null, id, element);
	}
}
*///?}
