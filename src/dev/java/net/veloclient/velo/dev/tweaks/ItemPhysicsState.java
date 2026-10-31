package net.veloclient.velo.dev.tweaks;

/** Item Physics: what a dropped item's render state carries on top of vanilla's (added by ItemPhysicsStateMixin). */
public interface ItemPhysicsState {

	boolean velo$grounded();

	void velo$setGrounded(boolean grounded);
}
