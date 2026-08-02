package net.veloclient.velo.client.modules.hud;

import net.veloclient.velo.config.ConfigManager;

import java.util.ArrayList;
import java.util.List;

/** Persists the manual waypoint list to {@code ~/.velo-client/config/waypoints.json}. */
final class WaypointStore {

	private static final String MODULE_ID = "waypoints";

	private WaypointStore() {
	}

	record Data(List<Waypoint> waypoints) {
	}

	static List<Waypoint> load() {
		Data data = ConfigManager.load(MODULE_ID, Data.class, new Data(new ArrayList<>()));
		return new ArrayList<>(data.waypoints());
	}

	static void save(List<Waypoint> waypoints) {
		ConfigManager.save(MODULE_ID, new Data(waypoints));
	}
}
