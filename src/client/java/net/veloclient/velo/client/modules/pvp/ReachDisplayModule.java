package net.veloclient.velo.client.modules.pvp;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.modules.servertools.RadiusShapes;
import net.veloclient.velo.client.pvp.CombatTracker;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your attack reach as a circle on the ground around you: an opponent standing on it is exactly at
 * your maximum reach, inside it you can hit them. The radius is your real reach (the interaction
 * range attribute, 3 blocks in survival) plus half a player's width, since reach is measured to the
 * edge of their hitbox. The circle turns green while another player is inside it. Optionally a
 * second "spacing" circle (where you want to stand to out-reach them) and your last hit's reach
 * as text.
 */
public final class ReachDisplayModule extends AbstractModule implements HudModule, Configurable {

	private static final double HALF_PLAYER_WIDTH = 0.3;

	private final HudPosition position = new HudPosition(0.02f, 0.45f);
	private boolean onlyNearPlayers = true;
	private boolean showSpacing = false;
	private double spacingInset = 0.4;
	private boolean showText = false;
	private int idleColor = 0xD0FF3B3B;
	private int inReachColor = 0xFF6FE39A;
	private double lineWidth = 2.5;

	public ReachDisplayModule() {
		super("reach-display", "Reach Display", "A red circle on the ground showing exactly how far you can hit - green when someone is in reach.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> renderWorld());
	}

	private void renderWorld() {
		if (!isEnabled()) {
			return;
		}
		var self = PvpCompat.localPlayer();
		if (self == null) {
			return;
		}
		double[] p = PvpCompat.renderPos(self);
		double radius = PvpCompat.attackRange() + HALF_PLAYER_WIDTH;
		boolean anyNear = false;
		boolean inReach = false;
		for (var other : PvpCompat.otherPlayers()) {
			double dx = other.getX() - self.getX();
			double dz = other.getZ() - self.getZ();
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			if (horizontal < 10) {
				anyNear = true;
			}
			if (horizontal <= radius && Math.abs(other.getY() - self.getY()) < 2.5) {
				inReach = true;
			}
		}
		if (onlyNearPlayers && !anyNear) {
			return;
		}
		// Always on the actual floor: your feet while standing, the block below you mid-jump.
		double ground = PvpCompat.onGround(self) ? p[1] : PvpCompat.groundBelow(p[0], p[1], p[2], 12);
		if (Double.isNaN(ground)) {
			return;
		}
		double y = ground + 0.02;
		int color = inReach ? inReachColor : idleColor;
		drawCircle(p[0], y, p[2], radius, color, (float) lineWidth);
		if (showSpacing) {
			drawCircle(p[0], y, p[2], Math.max(0.5, radius - spacingInset), (idleColor & 0x00FFFFFF) | 0x60000000, (float) lineWidth * 0.6f);
		}
	}

	/**
	 * The ring as short segments; a segment with another player between it and the camera is left
	 * out, so the ring goes behind players instead of being painted over them.
	 */
	private static void drawCircle(double cx, double y, double cz, double radius, int color, float width) {
		double[] camera = PvpCompat.cameraPos();
		var players = PvpCompat.otherPlayers();
		int segments = 72;
		double px = cx + radius;
		double pz = cz;
		for (int i = 1; i <= segments; i++) {
			double a = Math.PI * 2 * i / segments;
			double nx = cx + radius * Math.cos(a);
			double nz = cz + radius * Math.sin(a);
			double mx = (px + nx) / 2;
			double mz = (pz + nz) / 2;
			if (!hiddenBehindPlayer(camera, mx, y, mz, players)) {
				RadiusShapes.line(px, y, pz, nx, y, nz, color, width);
			}
			px = nx;
			pz = nz;
		}
	}

	private static boolean hiddenBehindPlayer(double[] camera, double x, double y, double z, java.util.List<net.minecraft.entity.LivingEntity> players) {
		double dx = x - camera[0];
		double dy = y - camera[1];
		double dz = z - camera[2];
		for (var player : players) {
			var box = player.getBoundingBox();
			// Slab test: does the camera -> point segment pass through this player's hitbox?
			double tMin = 0;
			double tMax = 0.98;
			double[][] axes = {{camera[0], dx, box.minX - 0.05, box.maxX + 0.05}, {camera[1], dy, box.minY, box.maxY + 0.05},
					{camera[2], dz, box.minZ - 0.05, box.maxZ + 0.05}};
			boolean hit = true;
			for (double[] axis : axes) {
				if (Math.abs(axis[1]) < 1e-9) {
					if (axis[0] < axis[2] || axis[0] > axis[3]) {
						hit = false;
						break;
					}
					continue;
				}
				double t1 = (axis[2] - axis[0]) / axis[1];
				double t2 = (axis[3] - axis[0]) / axis[1];
				tMin = Math.max(tMin, Math.min(t1, t2));
				tMax = Math.min(tMax, Math.max(t1, t2));
				if (tMin > tMax) {
					hit = false;
					break;
				}
			}
			if (hit) {
				return true;
			}
		}
		return false;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		if (!showText) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		double reach = CombatTracker.lastReach();
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		if ((reach < 0 || CombatTracker.ticksSinceHit() > 100) && !editing) {
			return;
		}
		String label = String.format(Locale.ROOT, "Reach %.2f", reach < 0 ? 2.87 : reach);
		VeloDraw.fillRounded(context, x, y, width(), height(), 6, 0x90101014);
		int textWidth = client.textRenderer.getWidth(label);
		context.drawTextWithShadow(client.textRenderer, label, x + (width() - textWidth) / 2, y + 6, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return 72;
	}

	@Override
	public int height() {
		return showText ? 20 : 0;
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(new ConfigField.HeaderField("Reach Circle"));
		fields.add(new ConfigField.ToggleField("Only When Players Are Near", () -> onlyNearPlayers, v -> onlyNearPlayers = v));
		fields.add(new ConfigField.ColorField("Circle Color", () -> idleColor, v -> idleColor = v, true));
		fields.add(new ConfigField.ColorField("In Reach Color", () -> inReachColor, v -> inReachColor = v, true));
		fields.add(new ConfigField.SliderField("Line Width", 1, 6, () -> lineWidth, v -> lineWidth = Math.round(v * 2) / 2.0,
				v -> String.format(Locale.ROOT, "%.1f", v)));
		fields.add(new ConfigField.HeaderField("Spacing Circle"));
		fields.add(new ConfigField.ToggleField("Show Spacing Circle", () -> showSpacing, v -> showSpacing = v));
		fields.add(new ConfigField.SliderField("Spacing Inside Reach", 0.1, 1.5, () -> spacingInset, v -> spacingInset = Math.round(v * 10) / 10.0,
				v -> String.format(Locale.ROOT, "%.1f blocks", v)));
		fields.add(new ConfigField.HeaderField("Last Hit"));
		fields.add(new ConfigField.ToggleField("Show Last Hit Reach", () -> showText, v -> showText = v));
		return fields;
	}
}
