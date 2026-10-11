package net.veloclient.velo.client.pvp;

/** Shared state between the two armor Hit Color mixins (render thread only). */
public final class HitColorEquipment {

	/** ARGB multiplier for the armor being drawn right now, or -1 for none. */
	public static int armorTint = -1;

	private HitColorEquipment() {
	}

	/** Hit Color category of a living render state ({@code HitColorModule.SELF/PLAYER/HOSTILE/PASSIVE}). */
	public static int category(Object renderState) {
		//? if <26.1 {
		var state = (net.minecraft.client.render.entity.state.LivingEntityRenderState) renderState;
		if (state instanceof net.minecraft.client.render.entity.state.PlayerEntityRenderState player) {
			return player.id == PvpCompat.localPlayerId() ? net.veloclient.velo.client.modules.pvp.HitColorModule.SELF
					: net.veloclient.velo.client.modules.pvp.HitColorModule.PLAYER;
		}
		return state.entityType != null && state.entityType.getSpawnGroup() == net.minecraft.entity.SpawnGroup.MONSTER
				? net.veloclient.velo.client.modules.pvp.HitColorModule.HOSTILE : net.veloclient.velo.client.modules.pvp.HitColorModule.PASSIVE;
		//?} else {
		/*var state = (net.minecraft.client.renderer.entity.state.LivingEntityRenderState) renderState;
		if (state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState player) {
			return player.id == PvpCompat.localPlayerId() ? net.veloclient.velo.client.modules.pvp.HitColorModule.SELF
					: net.veloclient.velo.client.modules.pvp.HitColorModule.PLAYER;
		}
		return state.entityType != null && state.entityType.getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
				? net.veloclient.velo.client.modules.pvp.HitColorModule.HOSTILE : net.veloclient.velo.client.modules.pvp.HitColorModule.PASSIVE;
		*///?}
	}
}
