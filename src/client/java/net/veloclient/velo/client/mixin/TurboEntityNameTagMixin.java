package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Velo Turbo: a stable name tag object. Every frame vanilla builds each player's name tag anew (team prefix/suffix,
 * copy, hover style) - a new component object each time, so its laid-out text (FontPrepareMemo) and its cached visual
 * order were thrown away and rebuilt for every name tag, every frame (LuckySMP spawn, ~55 players: ~3% of the frame).
 * When the new name is equal in content, style and parts to last frame's, last frame's object is handed on instead - so
 * it draws exactly the same, and any real change (rank, team, prefix) is a different component and shows at once.
 * Off with -Dvelo.nameMemo=false or Turbo off. 26.2+ (VeloMixinPlugin).
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class TurboEntityNameTagMixin {

	@org.spongepowered.asm.mixin.Unique
	private static final boolean ON = !"false".equals(System.getProperty("velo.nameMemo"));

	// where the render state takes the name - after every getNameTag decoration (badges, name styles) has run
	@ModifyExpressionValue(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;"),
			require = 0)
	private net.minecraft.network.chat.Component velo$stableName(net.minecraft.network.chat.Component name,
			@Local(argsOnly = true) net.minecraft.world.entity.Entity entity) {
		if (!ON || name == null || !net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo
				|| !(entity instanceof net.veloclient.velo.client.util.StableName memo)) {
			return name;
		}
		Object last = memo.velo$lastName();
		if (last instanceof net.minecraft.network.chat.Component previous && previous != name && previous.equals(name)) {
			return previous;
		}
		memo.velo$lastName(name);
		return name;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboEntityNameTagMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
//?}
