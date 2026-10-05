package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
//?} else {
/*import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen vanilla's Advancements screen returns to - Velo's replacement goes back there too. */
@Mixin(AdvancementsScreen.class)
public interface AdvancementsScreenAccessor {
	//? if <26.1 {
	@Accessor("parent")
	//?} else {
	/*@Accessor("lastScreen")
	*///?}
	net.minecraft.client.gui.screen.Screen velo$parent();
}
