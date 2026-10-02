package net.veloclient.velo.client.waypoints;

/**
 * One waypoint. Always belongs to exactly one world (a server address, a singleplayer save or a
 * realm - see {@link WaypointManager#currentWorldKey()}) and one dimension inside it, so a base on
 * one server never shows up on another, and the Nether's coordinates never leak into the Overworld.
 * Mutable on purpose: the editor changes fields in place and then calls {@link WaypointManager#save()}.
 */
public final class Waypoint {

	public String id;
	public String name;
	public String world;
	public String dimension;
	public double x;
	public double y;
	public double z;
	public int color;
	/** A key from {@link WaypointIcons}. */
	public String icon;
	public boolean enabled = true;
	/** Created automatically on death (skull icon, "Latest Death") - bulk-deletable separately. */
	public boolean death;
	public long created;

	public Waypoint() {
	}

	public Waypoint(String name, String world, String dimension, double x, double y, double z, int color, String icon) {
		this.id = java.util.UUID.randomUUID().toString();
		this.name = name;
		this.world = world;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.color = color;
		this.icon = icon;
		this.created = System.currentTimeMillis();
	}

	public int blockX() {
		return (int) Math.floor(x);
	}

	public int blockY() {
		return (int) Math.floor(y);
	}

	public int blockZ() {
		return (int) Math.floor(z);
	}
}
