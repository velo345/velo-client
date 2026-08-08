package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a setter for the client's own login session, which vanilla only
 * ever assigns once (a private final field with no mutator) - needed so
 * {@code SessionAutoFixerModule} can swap in a freshly refreshed access
 * token in place and reconnect, instead of what vanilla's own "Invalid
 * session" message tells players to do (restart the whole game and
 * launcher).
 */
//? if <26.1 {
@Mixin(MinecraftClient.class)
public interface SessionAccessorMixin {
	@Mutable
	@Accessor("session")
	void velo$setSession(Session session);
}
//?} else {
/*@Mixin(Minecraft.class)
public interface SessionAccessorMixin {
	@Mutable
	@Accessor("user")
	void velo$setSession(User user);
}
*///?}
