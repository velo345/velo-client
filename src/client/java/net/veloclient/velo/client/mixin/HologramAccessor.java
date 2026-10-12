package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The text of a text display entity (private in Mojang's names) - for reading server-drawn health holograms. */
//? if <26.1 {
@Mixin(net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity.class)
public interface HologramAccessor {

	@Invoker("getText")
	net.minecraft.text.Text velo$text();
}
//?} else {
/*@Mixin(net.minecraft.world.entity.Display.TextDisplay.class)
public interface HologramAccessor {

	@Invoker("getText")
	net.minecraft.network.chat.Component velo$text();
}
*///?}
