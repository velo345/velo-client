package net.veloclient.velo.dev.mixin;

import net.veloclient.velo.dev.tweaks.ItemPhysicsState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Item Physics: whether the item was on the ground when its render state was filled in. */
//? if <1.21.2 {
/*@Mixin(net.minecraft.client.MinecraftClient.class) // 1.21.1: no render states - nothing reads it there
*///?} else if <26.1 {
@Mixin(net.minecraft.client.render.entity.state.ItemEntityRenderState.class)
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.state.ItemEntityRenderState.class)
*///?}
public abstract class ItemPhysicsStateMixin implements ItemPhysicsState {

	@Unique
	private boolean velo$grounded;

	@Override
	public boolean velo$grounded() {
		return velo$grounded;
	}

	@Override
	public void velo$setGrounded(boolean grounded) {
		velo$grounded = grounded;
	}
}
