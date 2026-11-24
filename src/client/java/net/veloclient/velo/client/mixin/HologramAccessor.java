package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The text of a text display entity (private in Mojang's names) - for reading server-drawn health holograms. */
//? if <26.1 {
@Mixin(net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity.class)
public interface HologramAccessor {

	@Invoker("getText")
	net.minecraft.text.Text velo$text();

	// The lines as last laid out for drawing (null until drawn once) - DisplayCulling sizes the hologram from it.
	@org.spongepowered.asm.mixin.gen.Accessor("textLines")
	net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity.TextLines velo$layout();
}
//?} else {
/*@Mixin(net.minecraft.world.entity.Display.TextDisplay.class)
public interface HologramAccessor {

	@Invoker("getText")
	net.minecraft.network.chat.Component velo$text();

	// The lines as last laid out for drawing (null until drawn once) - DisplayCulling sizes the hologram from it.
	@org.spongepowered.asm.mixin.gen.Accessor("clientDisplayCache")
	net.minecraft.world.entity.Display.TextDisplay.CachedInfo velo$layout();
}
*///?}
