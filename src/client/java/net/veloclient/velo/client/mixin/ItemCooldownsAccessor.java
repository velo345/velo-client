package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw cooldown entries (start/end tick per cooldown group) for the Cooldowns HUD's exact timers. */
//? if <26.1 {
@Mixin(net.minecraft.entity.player.ItemCooldownManager.class)
public interface ItemCooldownsAccessor {

	@Accessor("entries")
	java.util.Map<net.minecraft.util.Identifier, ?> velo$entries();

	@Accessor("tick")
	int velo$tick();
}
//?} else {
/*@Mixin(net.minecraft.world.item.ItemCooldowns.class)
public interface ItemCooldownsAccessor {

	@Accessor("cooldowns")
	java.util.Map<net.minecraft.resources.Identifier, ?> velo$entries();

	@Accessor("tickCount")
	int velo$tick();
}
*///?}
