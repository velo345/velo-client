package net.veloclient.velo.client.waypoints;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Box;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.minecraft.client.render.DrawStyle;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.util.ClientCompat;

import java.util.List;
import java.util.Locale;

/**
 * Draws waypoints two ways at once:
 * <ul>
 * <li><b>In the world</b>: a beacon-style colored beam (bright core plus a soft glow) rising
 * through the waypoint's column, and an outline of the waypoint block - drawn through terrain so
 * it's findable from anywhere inside render distance.</li>
 * <li><b>On the HUD</b>: the icon and distance above the spot, projected with the live camera, so
 * it stays readable far beyond render distance. The name appears when you look at it or come
 * close; waypoints off-screen or behind you get a small arrow at the screen edge.</li>
 * </ul>
 * Settings live on {@link Settings} (edited through the Waypoints module).
 */
public final class WaypointRenderer {

	/** Rendering options - plain static fields, set by the Waypoints module's settings. */
	public static final class Settings {
		public static boolean enabled = true;
		public static boolean beams = true;
		public static float beamOpacity = 0.55f;
		public static float markerScale = 1.0f;
		public static boolean showDistance = true;
		public static boolean edgeArrows = true;
		public static int maxDistance = 0;

		private Settings() {
		}
	}

	private WaypointRenderer() {
	}

	/** World pass - called from the gizmo render event. */
	public static void renderWorld() {
		if (!Settings.enabled || !Settings.beams) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			return;
		}
		int bottom = client.world.getBottomY();
		int top = client.world.getTopYInclusive() + 1;
		for (Waypoint waypoint : WaypointManager.visibleHere()) {
			double distance = Math.sqrt(distanceSq(client, waypoint));
			if (Settings.maxDistance > 0 && distance > Settings.maxDistance) {
				continue;
			}
			// Fade the beam out as you walk into it, so it never fills the whole screen.
			float closeFade = (float) Math.max(0, Math.min(1, (distance - 3) / 10));
			if (closeFade <= 0.02f) {
				continue;
			}
			double cx = waypoint.blockX() + 0.5;
			double cz = waypoint.blockZ() + 0.5;
			int rgb = waypoint.color & 0x00FFFFFF;
			int coreAlpha = Math.round(255 * Settings.beamOpacity * closeFade);
			int glowAlpha = Math.round(70 * Settings.beamOpacity * closeFade);
			GizmoDrawing.box(new Box(cx - 0.12, bottom, cz - 0.12, cx + 0.12, top, cz + 0.12),
					DrawStyle.filled((coreAlpha << 24) | rgb)).ignoreOcclusion();
			GizmoDrawing.box(new Box(cx - 0.32, bottom, cz - 0.32, cx + 0.32, top, cz + 0.32),
					DrawStyle.filled((glowAlpha << 24) | rgb)).ignoreOcclusion();
			GizmoDrawing.box(new Box(waypoint.blockX(), waypoint.blockY(), waypoint.blockZ(),
					waypoint.blockX() + 1, waypoint.blockY() + 1, waypoint.blockZ() + 1),
					DrawStyle.stroked((Math.min(255, coreAlpha + 60) << 24) | rgb, 2f)).ignoreOcclusion();
		}
	}

	/** HUD pass - called from HudManager before the regular HUD modules. */
	public static void renderHud(DrawContext context, int screenWidth, int screenHeight) {
		if (!Settings.enabled) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			return;
		}
		List<Waypoint> waypoints = WaypointManager.sortedByDistance(WaypointManager.visibleHere());
		var textRenderer = client.textRenderer;
		// Nearest first: closer markers claim their label space, farther ones whose label would
		// collide drop the name (and if even that collides, the whole label - the icon stays).
		List<int[]> taken = new java.util.ArrayList<>();
		List<Object[]> placed = new java.util.ArrayList<>();
		for (Waypoint waypoint : waypoints) {
			double distance = Math.sqrt(distanceSq(client, waypoint));
			if (Settings.maxDistance > 0 && distance > Settings.maxDistance) {
				continue;
			}
			float[] view = ClientCompat.cameraView(waypoint.blockX() + 0.5, waypoint.blockY() + 1.4, waypoint.blockZ() + 0.5);
			float[] screen = ClientCompat.project(view, screenWidth, screenHeight);
			boolean onScreen = screen != null && screen[0] >= 0 && screen[0] <= screenWidth && screen[1] >= 0 && screen[1] <= screenHeight;
			if (!onScreen) {
				if (Settings.edgeArrows) {
					placed.add(new Object[] {waypoint, distance, view, null, false, false});
				}
				continue;
			}
			if (distance < 2.5) {
				continue;
			}
			float dx = screen[0] - screenWidth / 2f;
			float dy = screen[1] - screenHeight / 2f;
			boolean wantName = dx * dx + dy * dy < 45 * 45 || distance < 40
					|| waypoint.death && WaypointManager.LATEST_DEATH.equals(waypoint.name);
			String distanceLabel = formatDistance(distance);
			int nameWidth = wantName ? textRenderer.getWidth(VeloUi.trim(waypoint.name, 150)) + 6 : 0;
			int fullWidth = Math.round((nameWidth + textRenderer.getWidth(distanceLabel) + 12) * Settings.markerScale);
			int labelY = Math.round(screen[1] + 3 * Settings.markerScale);
			int labelHeight = Math.round(13 * Settings.markerScale);
			int[] full = {Math.round(screen[0]) - fullWidth / 2, labelY, fullWidth, labelHeight};
			boolean showName = wantName;
			boolean showLabel = true;
			if (overlapsAny(full, taken)) {
				int shortWidth = Math.round((textRenderer.getWidth(distanceLabel) + 12) * Settings.markerScale);
				int[] compact = {Math.round(screen[0]) - shortWidth / 2, labelY, shortWidth, labelHeight};
				showName = false;
				if (overlapsAny(compact, taken)) {
					showLabel = false;
				} else {
					taken.add(compact);
				}
			} else {
				taken.add(full);
			}
			placed.add(new Object[] {waypoint, distance, view, screen, showName, showLabel});
		}
		// Draw far to near so nearer markers end up on top.
		for (int i = placed.size() - 1; i >= 0; i--) {
			Object[] marker = placed.get(i);
			Waypoint waypoint = (Waypoint) marker[0];
			if (marker[3] == null) {
				drawEdgeArrow(context, waypoint, (float[]) marker[2], screenWidth, screenHeight);
			} else {
				float[] screen = (float[]) marker[3];
				drawMarker(context, waypoint, (double) marker[1], screen[0], screen[1], (boolean) marker[4], (boolean) marker[5]);
			}
		}
	}

	private static boolean overlapsAny(int[] rect, List<int[]> taken) {
		for (int[] other : taken) {
			if (rect[0] < other[0] + other[2] && rect[0] + rect[2] > other[0] && rect[1] < other[1] + other[3] && rect[1] + rect[3] > other[1]) {
				return true;
			}
		}
		return false;
	}

	private static void drawMarker(DrawContext context, Waypoint waypoint, double distance, float sx, float sy,
			boolean showName, boolean showLabel) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		float scale = Settings.markerScale;
		String distanceLabel = Settings.showDistance && showLabel ? formatDistance(distance) : null;
		String nameLabel = showName && showLabel ? waypoint.name : null;

		context.getMatrices().pushMatrix();
		context.getMatrices().translate(sx, sy);
		context.getMatrices().scale(scale, scale);

		int iconSize = 16;
		int iconY = -iconSize - 2;
		// Icon tile: rounded, tinted with the waypoint color.
		VeloDraw.fillRounded(context, -11, iconY - 3, 22, 22, 6, 0xB0101014);
		VeloDraw.strokeRounded(context, -11, iconY - 3, 22, 22, 6, waypoint.color | 0xFF000000);
		var stack = WaypointIcons.stack(waypoint.icon);
		if (stack != null) {
			context.drawItemWithoutEntity(stack, -8, iconY);
		} else {
			VeloDraw.fillCircle(context, 0, iconY + 8, 5, waypoint.color | 0xFF000000);
			VeloDraw.fillCircle(context, 0, iconY + 8, 2, 0xFFFFFFFF);
		}
		// Little pointer under the tile.
		context.fill(-1, -2, 1, 0, waypoint.color | 0xFF000000);

		if (nameLabel != null || distanceLabel != null) {
			int lineY = 3;
			String name = nameLabel == null ? null : VeloUi.trim(nameLabel, 150);
			int nameWidth = name == null ? 0 : textRenderer.getWidth(name);
			int distanceWidth = distanceLabel == null ? 0 : textRenderer.getWidth(distanceLabel);
			int gap = name != null && distanceLabel != null ? 6 : 0;
			int width = nameWidth + gap + distanceWidth + 12;
			int left = -width / 2;
			VeloDraw.fillRounded(context, left, lineY, width, 13, 5, 0xC0101014);
			VeloDraw.fillRounded(context, left + 1, lineY + 3, 2, 7, 1, waypoint.color | 0xFF000000);
			int textX = left + 6;
			if (name != null) {
				context.drawTextWithShadow(textRenderer, name, textX, lineY + 3, 0xFFFFFFFF);
				textX += nameWidth + gap;
			}
			if (distanceLabel != null) {
				context.drawTextWithShadow(textRenderer, distanceLabel, textX, lineY + 3,
						VeloAnim.lerpArgb(waypoint.color | 0xFF000000, 0xFFFFFFFF, 0.4f));
			}
		}
		context.getMatrices().popMatrix();
	}

	private static void drawEdgeArrow(DrawContext context, Waypoint waypoint, float[] view, int screenWidth, int screenHeight) {
		// Direction on screen: right = +x, up = +y in view space; behind the camera mirrors it.
		float dirX = view[0];
		float dirY = -view[1];
		if (view[2] > 0) {
			dirX = -dirX;
			dirY = -dirY;
			if (Math.abs(dirX) < 0.001f && Math.abs(dirY) < 0.001f) {
				dirY = 1;
			}
		}
		float length = (float) Math.sqrt(dirX * dirX + dirY * dirY);
		if (length < 0.0001f) {
			return;
		}
		dirX /= length;
		dirY /= length;
		float centerX = screenWidth / 2f;
		float centerY = screenHeight / 2f;
		float marginX = centerX - 18;
		float marginY = centerY - 18;
		float t = Math.min(Math.abs(dirX) > 0.0001f ? marginX / Math.abs(dirX) : Float.MAX_VALUE,
				Math.abs(dirY) > 0.0001f ? marginY / Math.abs(dirY) : Float.MAX_VALUE);
		int x = Math.round(centerX + dirX * t);
		int y = Math.round(centerY + dirY * t);
		int color = waypoint.color | 0xFF000000;
		VeloDraw.fillCircle(context, x, y, 6, 0xB0101014);
		var stack = WaypointIcons.stack(waypoint.icon);
		if (stack != null) {
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(x - 4.5f, y - 4.5f);
			context.getMatrices().scale(0.56f, 0.56f);
			context.drawItemWithoutEntity(stack, 0, 0);
			context.getMatrices().popMatrix();
		} else {
			VeloDraw.fillCircle(context, x, y, 3, color);
		}
		// Arrow tip pointing outwards.
		int tipX = Math.round(x + dirX * 10);
		int tipY = Math.round(y + dirY * 10);
		context.fill(tipX - 1, tipY - 1, tipX + 2, tipY + 2, color);
		int midX = Math.round(x + dirX * 8);
		int midY = Math.round(y + dirY * 8);
		context.fill(midX - 1, midY - 1, midX + 2, midY + 2, VeloUi.withAlpha(color, 0xAA));
	}

	static String formatDistance(double distance) {
		if (distance >= 10_000) {
			return String.format(Locale.ROOT, "%.1fkm", distance / 1000);
		}
		return Math.round(distance) + "m";
	}

	private static double distanceSq(MinecraftClient client, Waypoint waypoint) {
		double dx = waypoint.blockX() + 0.5 - client.player.getX();
		double dy = waypoint.blockY() - client.player.getY();
		double dz = waypoint.blockZ() + 0.5 - client.player.getZ();
		return dx * dx + dy * dy + dz * dz;
	}
}
