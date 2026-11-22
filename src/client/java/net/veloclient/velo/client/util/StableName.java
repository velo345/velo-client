package net.veloclient.velo.client.util;

/** Velo Turbo: an entity's last name tag (TurboEntityNameMemoMixin adds it to Entity, TurboEntityNameTagMixin uses it). */
public interface StableName {

	Object velo$lastName();

	void velo$lastName(Object name);
}
