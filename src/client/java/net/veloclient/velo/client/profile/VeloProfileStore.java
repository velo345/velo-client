package net.veloclient.velo.client.profile;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;
import net.veloclient.velo.profile.HudLayoutEntry;
import net.veloclient.velo.profile.ModuleStateStore;
import net.veloclient.velo.profile.VeloProfile;
import net.veloclient.velo.profile.VeloProfileManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side glue between {@link VeloProfileManager}'s plain-data
 * {@link VeloProfile} snapshots and this mod's actual live state (module
 * settings plus each {@link HudModule}'s screen position/scale) - kept out
 * of the common sourceset since {@code HudPosition}/{@code HudModule} are
 * client-only types.
 */
public final class VeloProfileStore {

	private static boolean deferredLoadArmed;

	private VeloProfileStore() {
	}

	public static VeloProfile captureCurrent(String name) {
		Map<String, HudLayoutEntry> hudLayout = new LinkedHashMap<>();
		for (Module module : ModuleRegistry.all()) {
			if (module instanceof HudModule hud) {
				var pos = hud.position();
				hudLayout.put(module.id(), new HudLayoutEntry(pos.xFraction(), pos.yFraction(), pos.scale()));
			}
		}
		return new VeloProfile(name, ModuleStateStore.captureAll(), hudLayout);
	}

	public static void applyToLive(VeloProfile profile) {
		ModuleStateStore.applyAll(profile.moduleStates());
		for (Module module : ModuleRegistry.all()) {
			if (module instanceof HudModule hud) {
				HudLayoutEntry entry = profile.hudLayout().get(module.id());
				if (entry != null) {
					hud.position().set(entry.xFraction(), entry.yFraction());
					hud.position().setScale(entry.scale());
				}
			}
		}
	}

	/** Saves the current live state as the active profile - call after any settings/HUD-layout change made through the UI. */
	public static void saveActive() {
		String name = VeloProfileManager.activeName();
		VeloProfileManager.save(captureCurrent(name));
	}

	/**
	 * Loading the active profile has to wait until the client is past its
	 * own startup sequence - applying a saved "enabled" state this early
	 * calls straight into a module's {@code onEnable()}, and several of
	 * those touch systems (the sound engine, the world renderer) that don't
	 * exist yet during mod init. A one-shot tick listener is the same
	 * pattern already used elsewhere in this codebase for exactly that
	 * class of problem.
	 */
	private static boolean deferredLoadDone;

	public static void loadActiveDeferred() {
		if (deferredLoadArmed) {
			return;
		}
		deferredLoadArmed = true;
		// Fabric API's Event<T> has no unregister - a "have we already
		// fired" guard inside the listener is the standard way to make a
		// registration effectively one-shot instead.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (deferredLoadDone) {
				return;
			}
			deferredLoadDone = true;
			String name = VeloProfileManager.activeName();
			VeloProfileManager.loadByName(name).ifPresentOrElse(
					VeloProfileStore::applyToLive,
					() -> VeloClient.LOGGER.info("No saved profile named '{}' yet - starting with module defaults.", name));
		});
	}
}
