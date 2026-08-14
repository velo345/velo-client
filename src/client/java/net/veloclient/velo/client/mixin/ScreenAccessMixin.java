package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes {@code Screen}'s private auto-render list ({@code drawables} on
 * Yarn, {@code renderables} on Mojang mappings) so {@link TitleScreenMixin}/
 * {@link EscapeMenuMixin} can remove a repositioned vanilla button from it
 * (freeing them to draw their own glass visuls for it instead, same
 * "registered for clicks, drawn by hand" trick {@code ModMenuScreen}'s own
 * sidebar/grid already use) and add their own new Store button to it.
 *
 * <p>Targets {@code Screen} directly - the class that actually declares this
 * field - rather than {@code @Shadow}-ing it from a {@code TitleScreen}/
 * {@code PauseScreen}-targeted mixin. That was the original approach and it
 * does not reliably attach: a real launch crash showed Mixin's preprocessor
 * reporting {@code @Shadow field width was not located in the target class
 * TitleScreen} even though {@code Screen.width} genuinely exists there via
 * inheritance - shadowing a member declared *outside* the exact
 * {@code @Mixin} target class apparently isn't supported the way shadowing
 * the target's own declared members is, at least not in this Mixin/Loom
 * setup. An accessor mixin on the true declaring class doesn't have that
 * problem, which is the whole reason it's split into its own file instead of
 * living inside {@code TitleScreenMixin} itself.
 */
//? if <26.1 {
@Mixin(Screen.class)
public interface ScreenAccessMixin {

	@Accessor("drawables")
	List<Drawable> velo$drawables();
}
//?} else {
/*@Mixin(Screen.class)
public interface ScreenAccessMixin {

	@Accessor("renderables")
	List<Renderable> velo$drawables();
}
*///?}
