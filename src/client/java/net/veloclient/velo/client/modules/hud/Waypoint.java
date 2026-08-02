package net.veloclient.velo.client.modules.hud;

/**
 * A single user-placed waypoint. Manual only - never auto-populated from
 * world scanning, so this is a personal note system, not X-ray-adjacent
 * (design spec section 6.2).
 */
public record Waypoint(String name, String dimension, double x, double y, double z) {
}
