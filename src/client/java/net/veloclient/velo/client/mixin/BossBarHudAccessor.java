package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The boss bars the server is showing right now (vanilla keeps them in a non-public map). */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.BossBarHud.class)
//?} else {
/*@Mixin(net.minecraft.client.gui.components.BossHealthOverlay.class)
*///?}
public interface BossBarHudAccessor {
	//? if <26.1 {
	@Accessor("bossBars")
	//?} else {
	/*@Accessor("events")
	*///?}
	java.util.Map<java.util.UUID, ?> velo$bars();
}
