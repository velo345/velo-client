package net.veloclient.velo.dev.storage;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.world.debug.gizmo.GizmoDrawing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Points you at what you picked in the Storage menu - like a waypoint, not like an x-ray: a soft light beam rises from
 * the target and is hidden by blocks like anything else (so it pokes out above roofs and shows from inside the room,
 * but never draws a chest through a wall), a thin outline once you actually look at it, and a "Chest - 23m to your
 * left" line under the crosshair. A route walks its stops one by one: opening the container you're led to moves on
 * to the next. In the container, slots holding what you came for get a frame.
 */
public final class StorageHighlight {

	/** One place to lead to. {@code w/h/d}: box size (2 wide for double chests, villager-sized for traders). */
	private record Target(String world, String dim, double x, double y, double z, double w, double h, double d, String label, String hint) {
	}

	private static final List<Target> TARGETS = new ArrayList<>();
	private static int step;
	private static long until;
	/** Items to frame in container screens (stays a little after the marker is gone). */
	private static Set<String> items = Set.of();
	private static long itemsUntil;
	private static String finished;
	private static long finishedUntil;

	private StorageHighlight() {
	}

	public static void register() {
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> renderWorld());
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.of("velo-client", "dev_storage_target"),
				(context, tickCounter) -> renderHud(context));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
				ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, tickDelta) -> renderSlots(s, context)));
	}

	private static Target blockTarget(String worldKey, String dim, int bx, int by, int bz, int[] other, String label, String hint) {
		double x = bx;
		double z = bz;
		double w = 1;
		double d = 1;
		if (other != null) {
			x = Math.min(bx, other[0]);
			z = Math.min(bz, other[2]);
			w = Math.abs(bx - other[0]) + 1;
			d = Math.abs(bz - other[2]) + 1;
		}
		return new Target(worldKey, dim, x, by, z, w, 1, d, label, hint);
	}

	private static void start(List<Target> targets, Set<String> frame) {
		TARGETS.clear();
		TARGETS.addAll(targets);
		step = 0;
		until = System.currentTimeMillis() + 10 * 60_000;
		items = frame;
		itemsUntil = until;
		finished = null;
	}

	public static void block(String worldKey, String dimension, int bx, int by, int bz, int[] other, String what, String itemId) {
		start(dimension == null ? List.of() : List.of(blockTarget(worldKey, dimension, bx, by, bz, other, what, null)),
				itemId == null ? Set.of() : Set.of(itemId));
		if (dimension == null) {
			// Only framing slots (ender chest / inventory).
			itemsUntil = System.currentTimeMillis() + 120_000;
		}
	}

	public static void entity(String worldKey, String dimension, double ex, double ey, double ez, String what, String itemId) {
		start(List.of(new Target(worldKey, dimension, ex - 0.4, ey, ez - 0.4, 0.8, 2, 0.8, what, null)), itemId == null ? Set.of() : Set.of(itemId));
	}

	/** Leads along a route's stops; {@code frame}: the item ids to frame in each container. */
	public static void route(String worldKey, List<StorageIndex.Stop> stops, Set<String> frame) {
		List<Target> targets = new ArrayList<>();
		for (StorageIndex.Stop stop : stops) {
			StringBuilder hint = new StringBuilder();
			stop.take().forEach((id, count) -> hint.append(hint.isEmpty() ? "take " : ", ").append(count).append(" ").append(StorageIndex.name(id)));
			int bx = (int) Math.floor(stop.x());
			int by = (int) Math.floor(stop.y());
			int bz = (int) Math.floor(stop.z());
			targets.add(blockTarget(worldKey, stop.dim(), bx, by, bz, stop.container() == null ? null : stop.container().other, stop.label(), hint.toString()));
		}
		start(targets, frame);
	}

	public static void clear() {
		TARGETS.clear();
		until = 0;
		itemsUntil = 0;
	}

	private static Target current() {
		if (System.currentTimeMillis() > until || step >= TARGETS.size()) {
			return null;
		}
		return TARGETS.get(step);
	}

	/** Opening the container we lead to: on to the next stop (the slot frames take over in here). */
	static void onContainerOpened(int[] pos) {
		Target target = current();
		if (pos == null || target == null || pos[1] != (int) target.y() || pos[0] < target.x() || pos[0] >= target.x() + target.w()
				|| pos[2] < target.z() || pos[2] >= target.z() + target.d()) {
			// Ender chests count wherever: any one opens your ender chest.
			if (target == null || !"minecraft:ender_chest".equals(blockAt(pos)) || !target.label().equals(StorageIndex.name("minecraft:ender_chest"))) {
				return;
			}
		}
		step++;
		if (step >= TARGETS.size()) {
			until = 0;
			if (TARGETS.size() > 1) {
				finished = "Route done - you've been to every stop";
				finishedUntil = System.currentTimeMillis() + 4000;
			}
		}
	}

	private static String blockAt(int[] pos) {
		return pos == null ? null : StorageMc.blockId(pos[0], pos[1], pos[2]);
	}

	private static boolean visible(Target target) {
		return target != null && target.world() != null && target.world().equals(StorageStore.currentWorldKey())
				&& target.dim() != null && target.dim().equals(StorageStore.here());
	}

	private static void renderWorld() {
		Target target = current();
		double[] me = StorageMc.playerPos();
		if (!visible(target) || me == null) {
			return;
		}
		double cx = target.x() + target.w() / 2;
		double cz = target.z() + target.d() / 2;
		double top = target.y() + target.h();
		double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 400.0);
		// The beam: depth-tested like any block, tall enough to clear a roof. Thin core + soft glow.
		double beamTop = top + 24;
		GizmoDrawing.box(new Box(cx - 0.06, top + 0.1, cz - 0.06, cx + 0.06, beamTop, cz + 0.06),
				DrawStyle.filled(((0x90 + (int) (0x40 * pulse)) << 24) | 0x8FE6FF));
		GizmoDrawing.box(new Box(cx - 0.18, top + 0.1, cz - 0.18, cx + 0.18, beamTop, cz + 0.18), DrawStyle.filled(0x228FE6FF));
		// The outline only means something up close, and only where you can actually see the block.
		double dx = cx - me[0];
		double dz = cz - me[2];
		if (dx * dx + dz * dz < 24 * 24) {
			double grow = 0.004;
			GizmoDrawing.box(new Box(target.x() - grow, target.y() - grow, target.z() - grow, target.x() + target.w() + grow, top + grow,
					target.z() + target.d() + grow), DrawStyle.stroked(((0xA0 + (int) (0x50 * pulse)) << 24) | 0x8FE6FF, 2f));
		}
	}

	private static void renderHud(DrawContext context) {
		if (net.veloclient.velo.client.util.ClientCompat.currentScreen() != null) {
			return;
		}
		int sw = context.getScaledWindowWidth();
		int sh = context.getScaledWindowHeight();
		if (finished != null && System.currentTimeMillis() < finishedUntil) {
			drawLine(context, finished, sw, sh / 2 + 18, 0xFF8FF0A8);
			return;
		}
		Target target = current();
		double[] me = StorageMc.playerPos();
		if (target == null || me == null) {
			return;
		}
		String prefix = TARGETS.size() > 1 ? "Stop " + (step + 1) + "/" + TARGETS.size() + ": " : "";
		if (!visible(target)) {
			String where = target.world() != null && target.world().equals(StorageStore.currentWorldKey())
					? "in the " + StorageStore.dimensionLabel(target.dim()) : "on another world";
			drawLine(context, prefix + target.label() + " is " + where, sw, sh / 2 + 18, 0xFFB8C4D6);
			return;
		}
		double cx = target.x() + target.w() / 2;
		double cz = target.z() + target.d() / 2;
		double dx = cx - me[0];
		double dy = target.y() + target.h() / 2 - me[1] - 1.6;
		double dz = cz - me[2];
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		String line;
		if (distance < 3) {
			line = prefix + target.label() + " - right here";
		} else {
			// Angle to the target relative to where you look (Minecraft yaw: 0 = +Z, 90 = -X).
			double angle = Math.toDegrees(Math.atan2(-dx, dz));
			double relative = ((angle - me[3]) % 360 + 540) % 360 - 180;
			String direction = Math.abs(relative) < 25 ? "ahead" : Math.abs(relative) > 150 ? "behind you"
					: relative < 0 ? "to your left" : "to your right";
			String vertical = dy > 3 ? ", above" : dy < -3 ? ", below" : "";
			line = prefix + target.label() + "  -  " + Math.round(distance) + "m " + direction + vertical;
		}
		drawLine(context, line, sw, sh / 2 + 18, 0xFFB8EEFF);
		if (target.hint() != null && !target.hint().isEmpty()) {
			drawLine(context, target.hint(), sw, sh / 2 + 31, 0xFFD6DCE6);
		}
	}

	private static void drawLine(DrawContext context, String line, int sw, int y, int color) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		int width = renderer.getWidth(line);
		int x = sw / 2 - width / 2;
		context.fill(x - 4, y - 3, x + width + 4, y + 11, 0x70000000);
		context.drawTextWithShadow(renderer, line, x, y, color);
	}

	private static void renderSlots(Screen screen, DrawContext context) {
		if (items.isEmpty() || System.currentTimeMillis() > itemsUntil || !StorageMc.isContainerScreen(screen)) {
			return;
		}
		int color = (System.currentTimeMillis() / 400) % 2 == 0 ? 0xFF8FE6FF : 0xFFD0F6FF;
		for (StorageMc.ScreenSlot slot : StorageMc.screenSlots(screen)) {
			if (holds(slot.stack(), 0)) {
				int sx0 = slot.x() - 1;
				int sy0 = slot.y() - 1;
				context.fill(sx0, sy0, sx0 + 18, sy0 + 1, color);
				context.fill(sx0, sy0 + 17, sx0 + 18, sy0 + 18, color);
				context.fill(sx0, sy0, sx0 + 1, sy0 + 18, color);
				context.fill(sx0 + 17, sy0, sx0 + 18, sy0 + 18, color);
			}
		}
	}

	private static boolean holds(ItemStack stack, int depth) {
		if (StorageMc.isEmpty(stack)) {
			return false;
		}
		if (items.contains(StorageMc.itemId(stack))) {
			return true;
		}
		if (depth < 3) {
			for (ItemStack nested : StorageMc.nested(stack)) {
				if (holds(nested, depth + 1)) {
					return true;
				}
			}
		}
		return false;
	}

	static String describe(String block) {
		return block == null ? "Container" : StorageIndex.name(block.toLowerCase(Locale.ROOT));
	}
}
