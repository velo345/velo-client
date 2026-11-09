package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind: puts the selected clip's tracks into the game while watching - the camera path (every frame, between ticks),
 * roll and FOV (RewindCameraMixin asks), playback speed, time of day, rain, thunder, fog, hidden entities and swapped skins.
 * The value comes from the playhead, or from where the timeline is being dragged (live preview).
 */
//? if <26.2 {
public final class RewindApply {

	private RewindApply() {
	}

	public static double[] camera(float partial) {
		return null;
	}

	public static float rollRadians() {
		return 0f;
	}

	public static Float fov() {
		return null;
	}

	static void tick() {
	}

	static void registerGizmo() {
	}

	public static int backgroundColor() {
		return -1;
	}

	public static boolean hideTerrain() {
		return false;
	}

	public static boolean renderEntity(Object e) {
		return true;
	}

	public static Boolean glowing(Object e) {
		return null;
	}

	public static int outlineColor(Object e) {
		return -1;
	}

	public static boolean showName(Object e) {
		return true;
	}
}
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RewindApply {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Velo Rewind");

	// Set by the editor while the playhead is dragged (clip ticks), else -1.
	static volatile double previewTick = -1;
	// Camera keyframes drive the view while playing / dragging; paused you fly freely (to set up the next keyframe) unless this is on.
	static volatile boolean previewCamera;

	private RewindApply() {
	}

	private static double tickNow(RewindPlayer p, float partial) {
		if (previewTick >= 0) {
			return previewTick;
		}
		return p.clipPosition() + (p.paused() ? 0 : partial);
	}

	private static boolean cameraDriven(RewindPlayer p) {
		RewindEdits.Clip c = p.edits.clip();
		boolean hasPath = has(c, "cam_x") || has(c, "cam_y") || has(c, "cam_z") || has(c, "cam_yaw") || has(c, "cam_pitch");
		return hasPath && (!p.paused() || previewTick >= 0 || previewCamera);
	}

	private static boolean has(RewindEdits.Clip c, String id) {
		RewindEdits.Track t = c.tracks.get(id);
		return t != null && t.active();
	}

	// ---- asked by RewindCameraMixin every frame ----

	public static double[] camera(float partial) {
		RewindPlayer p = RewindPlayer.current();
		Minecraft mc = Minecraft.getInstance();
		if (p != null && mc.player != null && followTarget != null) {
			double[] f = followPose(mc, partial);
			if (f != null) {
				syncViewer(mc, f);
				return f;
			}
		}
		if (p == null || mc.player == null || !cameraDriven(p)) {
			return null;
		}
		RewindEdits.Clip c = p.edits.clip();
		double t = tickNow(p, partial);
		var eye = mc.player.getEyePosition(partial);
		double[] out = {
				or(c.value("cam_x", t), eye.x), or(c.value("cam_y", t), eye.y), or(c.value("cam_z", t), eye.z),
				or(c.value("cam_yaw", t), mc.player.getViewYRot(partial)), or(c.value("cam_pitch", t), mc.player.getViewXRot(partial))};
		syncViewer(mc, out);
		return out;
	}

	// The (invisible) viewer looks where the camera looks: culling and everything else that goes by the player's view
	// (entity culling, visibility caches) then matches what's on screen instead of what's behind it.
	private static void syncViewer(Minecraft mc, double[] out) {
		mc.player.setYRot((float) out[3]);
		mc.player.setXRot((float) out[4]);
		mc.player.yRotO = (float) out[3];
		mc.player.xRotO = (float) out[4];
		mc.player.setYHeadRot((float) out[3]);
	}

	// ---- follow: the camera keeps an entity (player, item, mob) in view and moves with it ----

	public static volatile java.util.UUID followTarget;
	// "behind" (over its shoulder, turning with it), "orbit" (circling it), "fixed" (same offset as when it started)
	public static volatile String followMode = "behind";
	public static volatile double followDistance = 4.5;
	public static volatile double followHeight = 1.2;
	public static volatile double followSpeed = 20; // orbit: degrees per second
	private static double[] followCam;
	private static net.minecraft.world.phys.Vec3 followOffset;
	private static long followLast;
	private static double followAngle;

	public static void follow(java.util.UUID target, String mode) {
		followTarget = target;
		if (mode != null) {
			followMode = mode;
		}
		followCam = null;
		followOffset = null;
		followLast = 0;
	}

	static Entity followed() {
		Minecraft mc = Minecraft.getInstance();
		java.util.UUID id = followTarget;
		if (id == null || mc.level == null) {
			return null;
		}
		for (Entity e : mc.level.entitiesForRendering()) {
			if (id.equals(e.getUUID())) {
				return e;
			}
		}
		return null;
	}

	private static double[] followPose(Minecraft mc, float partial) {
		Entity e = followed();
		if (e == null) {
			return null; // out of the recording's range for now: the camera stays as it was
		}
		var at = e.getPosition(partial).add(0, Math.min(1.6, e.getBbHeight() * 0.7), 0);
		long now = System.nanoTime();
		double dt = followLast == 0 ? 0 : Math.min(0.1, (now - followLast) / 1e9);
		followLast = now;
		net.minecraft.world.phys.Vec3 want;
		switch (followMode) {
			case "orbit" -> {
				followAngle += Math.toRadians(followSpeed) * dt;
				want = at.add(Math.sin(followAngle) * followDistance, followHeight, Math.cos(followAngle) * followDistance);
			}
			case "fixed" -> {
				if (followOffset == null) {
					var cam = mc.gameRenderer.mainCamera().position();
					followOffset = cam.subtract(at);
				}
				want = at.add(followOffset);
			}
			default -> {
				// over the shoulder: behind where it looks (an item has no look - behind where it's going, else as before)
				double yaw;
				if (e instanceof net.minecraft.world.entity.LivingEntity living) {
					yaw = Math.toRadians(living.getViewYRot(partial));
				} else {
					var v = e.getDeltaMovement();
					yaw = v.horizontalDistanceSqr() > 1e-4 ? Math.atan2(-v.x, v.z) : Math.toRadians(followCam != null ? followCam[3] : 0);
				}
				want = at.add(Math.sin(yaw) * followDistance, followHeight, -Math.cos(yaw) * followDistance);
			}
		}
		double[] cam = followCam;
		if (cam == null) {
			cam = new double[] {want.x, want.y, want.z, 0, 0};
		} else {
			double k = 1 - Math.exp(-dt * 6); // smooth, not glued
			cam[0] += (want.x - cam[0]) * k;
			cam[1] += (want.y - cam[1]) * k;
			cam[2] += (want.z - cam[2]) * k;
		}
		double dx = at.x - cam[0];
		double dy = at.y - cam[1];
		double dz = at.z - cam[2];
		cam[3] = Math.toDegrees(Math.atan2(-dx, dz));
		cam[4] = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		followCam = cam;
		return cam.clone();
	}

	public static float rollRadians() {
		RewindPlayer p = RewindPlayer.current();
		if (p == null) {
			return 0f;
		}
		Double roll = p.edits.clip().value("cam_roll", tickNow(p, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)));
		return roll == null ? 0f : (float) Math.toRadians(-roll);
	}

	public static Float fov() {
		RewindPlayer p = RewindPlayer.current();
		if (p == null) {
			return null;
		}
		Double fov = p.edits.clip().value("fov", tickNow(p, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)));
		return fov == null ? null : fov.floatValue();
	}

	private static double or(Double v, double fallback) {
		return v == null ? fallback : v;
	}

	// ---- the camera, drawn in the world (Blender-like) while you fly freely ----

	static void registerGizmo() {
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.BEFORE_GIZMOS.register(context -> drawCamera());
	}

	private static void drawCamera() {
		RewindPlayer p = RewindPlayer.current();
		Minecraft mc = Minecraft.getInstance();
		if (p == null || mc.player == null || cameraDriven(p) || RewindExport.busy()) {
			return;
		}
		RewindEdits.Clip c = p.edits.clip();
		if (!(has(c, "cam_x") || has(c, "cam_y") || has(c, "cam_z"))) {
			return;
		}
		double t = previewTick >= 0 ? previewTick : p.clipPosition();
		// the path: sampled along the clip, keyframes marked
		net.minecraft.world.phys.Vec3 prev = null;
		for (int k = 0; k <= c.length; k += 2) {
			net.minecraft.world.phys.Vec3 at = pos(c, k);
			if (at == null) {
				break;
			}
			if (prev != null) {
				net.minecraft.gizmos.Gizmos.line(prev, at, 0xB0F7C04A, 1.5f);
			}
			prev = at;
		}
		java.util.Set<Integer> keys = new java.util.TreeSet<>();
		for (String id : new String[] {"cam_x", "cam_y", "cam_z"}) {
			RewindEdits.Track tr = c.tracks.get(id);
			if (tr != null) {
				keys.addAll(tr.keys.keySet());
			}
		}
		for (int k : keys) {
			net.minecraft.world.phys.Vec3 at = pos(c, k);
			if (at != null) {
				double r = 0.12;
				net.minecraft.gizmos.Gizmos.line(at.add(-r, 0, 0), at.add(r, 0, 0), 0xFFF7C04A, 3f);
				net.minecraft.gizmos.Gizmos.line(at.add(0, -r, 0), at.add(0, r, 0), 0xFFF7C04A, 3f);
				net.minecraft.gizmos.Gizmos.line(at.add(0, 0, -r), at.add(0, 0, r), 0xFFF7C04A, 3f);
			}
		}
		// the camera at the playhead: a pyramid towards what it sees, the frame in the export's shape, a triangle on top
		net.minecraft.world.phys.Vec3 eye = pos(c, t);
		if (eye == null) {
			return;
		}
		double yaw = Math.toRadians(or(c.value("cam_yaw", t), 0));
		double pitch = Math.toRadians(or(c.value("cam_pitch", t), 0));
		double roll = Math.toRadians(or(c.value("cam_roll", t), 0));
		net.minecraft.world.phys.Vec3 fwd = new net.minecraft.world.phys.Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		net.minecraft.world.phys.Vec3 right = new net.minecraft.world.phys.Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		net.minecraft.world.phys.Vec3 up = right.cross(fwd).normalize();
		// roll turns right/up around the view direction
		net.minecraft.world.phys.Vec3 r2 = right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll)));
		net.minecraft.world.phys.Vec3 u2 = up.scale(Math.cos(roll)).subtract(right.scale(Math.sin(roll)));
		double aspect = RewindEditorScreen.frameAspect();
		double fovV = Math.toRadians(or(c.value("fov", t), mc.options.fov().get()));
		double d = 1.4;
		double hh = Math.tan(fovV / 2) * d * 0.6;
		double hw = hh * aspect;
		net.minecraft.world.phys.Vec3 centre = eye.add(fwd.scale(d));
		net.minecraft.world.phys.Vec3[] corner = {
				centre.add(r2.scale(-hw)).add(u2.scale(hh)), centre.add(r2.scale(hw)).add(u2.scale(hh)),
				centre.add(r2.scale(hw)).add(u2.scale(-hh)), centre.add(r2.scale(-hw)).add(u2.scale(-hh))};
		int col = 0xFFFFFFFF;
		for (int i = 0; i < 4; i++) {
			net.minecraft.gizmos.Gizmos.line(eye, corner[i], col, 2f);
			net.minecraft.gizmos.Gizmos.line(corner[i], corner[(i + 1) % 4], col, 2.5f);
		}
		net.minecraft.world.phys.Vec3 topMid = centre.add(u2.scale(hh));
		net.minecraft.world.phys.Vec3 tip = topMid.add(u2.scale(hh * 0.55));
		net.minecraft.gizmos.Gizmos.line(topMid.add(r2.scale(-hw * 0.35)), tip, 0xFFFF5A5F, 2.5f);
		net.minecraft.gizmos.Gizmos.line(tip, topMid.add(r2.scale(hw * 0.35)), 0xFFFF5A5F, 2.5f);
		net.minecraft.gizmos.Gizmos.line(topMid.add(r2.scale(hw * 0.35)), topMid.add(r2.scale(-hw * 0.35)), 0xFFFF5A5F, 2.5f);
		// a ray to where it looks
		net.minecraft.gizmos.Gizmos.line(centre, eye.add(fwd.scale(6)), 0x60FFFFFF, 1f);
	}

	private static net.minecraft.world.phys.Vec3 pos(RewindEdits.Clip c, double t) {
		Double x = c.value("cam_x", t);
		Double y = c.value("cam_y", t);
		Double z = c.value("cam_z", t);
		if (x == null || y == null || z == null) {
			return null;
		}
		return new net.minecraft.world.phys.Vec3(x, y, z);
	}

	// ---- backgrounds (RewindBackgroundMixin / RewindChunkMixin / RewindSodiumChunkMixin, every frame) ----

	private static String background() {
		RewindPlayer p = RewindPlayer.current();
		if (p == null) {
			return "World";
		}
		String b = p.edits.clip().background;
		return b == null ? "World" : b;
	}

	public static boolean hideTerrain() {
		return !"World".equals(background());
	}

	// The flat colour behind everything (RGB), or -1 for the world's own sky.
	public static int backgroundColor() {
		return switch (background()) {
			case "Green screen" -> 0x00B140;
			case "Blue screen" -> 0x0047BB;
			case "Black" -> 0x000000;
			default -> -1;
		};
	}

	// ---- how entities look (RewindRenderMixin, RewindGlowMixin, RewindTeamColorMixin, RewindNameMixin) ----

	static {
		net.veloclient.velo.client.cosmetics.CosmeticHide.test = (uuid, part) -> {
			RewindPlayer p = RewindPlayer.current();
			if (p == null) {
				return false;
			}
			var all = p.edits.clip().hideParts;
			var parts = all == null ? null : all.get(uuid);
			return parts != null && parts.contains(part);
		};
	}

	// Editing (editor open or flying around the viewport) - not the final render: no outlines there, hidden ones gone.
	private static boolean editorShown() {
		return !RewindExport.busy();
	}

	private static boolean hiddenInClip(Entity e) {
		RewindPlayer p = RewindPlayer.current();
		return p != null && p.edits.clip().hidden.contains(e.getUUID().toString());
	}

	// Hidden entities: drawn (outlined red) while editing, left out of the exported video.
	public static boolean renderEntity(Entity e) {
		return !(RewindPlayer.active() && hiddenInClip(e) && !editorShown());
	}

	// null = as the game decides.
	public static Boolean glowing(Entity e) {
		if (!RewindPlayer.active()) {
			return null;
		}
		if (editorShown() && (e == RewindEditorScreen.selectedEntity() || hiddenInClip(e))) {
			return true;
		}
		if (net.veloclient.velo.client.cosmetics.CosmeticHide.hidden(e.getUUID(), "glow")) {
			return false;
		}
		return null;
	}

	// Outline colour: white for the selection, red for hidden ones; -1 = the entity's own.
	public static int outlineColor(Entity e) {
		if (!RewindPlayer.active() || !editorShown()) {
			return -1;
		}
		if (hiddenInClip(e)) {
			return 0xFF4D57;
		}
		return e == RewindEditorScreen.selectedEntity() ? 0xFFFFFF : -1;
	}

	public static boolean showName(Entity e) {
		return !(RewindPlayer.active() && net.veloclient.velo.client.cosmetics.CosmeticHide.hidden(e.getUUID(), "nametag"));
	}

	// ---- every game tick ----

	private static float lastSpeed = -1;

	static void tick() {
		RewindPlayer p = RewindPlayer.current();
		Minecraft mc = Minecraft.getInstance();
		if (p == null || mc.level == null || mc.player == null) {
			net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule.externalFogThickness = null;
			lastSpeed = -1;
			return;
		}
		RewindEdits.Clip c = p.edits.clip();
		double t = tickNow(p, 0);
		Double speed = c.value("speed", t);
		if (speed != null && Math.abs(speed - lastSpeed) > 0.01 && previewTick < 0) {
			lastSpeed = speed.floatValue();
			p.setSpeed(lastSpeed);
		}
		Double time = c.value("time", t);
		if (time != null) {
			setTime(mc, Math.round(time));
		}
		Double rain = c.value("rain", t);
		if (backgroundColor() >= 0) {
			rain = 0.0; // a flat background: no rain splashing on ground that isn't drawn
		}
		if (rain != null) {
			mc.level.setRainLevel(rain.floatValue());
		}
		Double thunder = c.value("thunder", t);
		if (thunder != null) {
			mc.level.setThunderLevel(thunder.floatValue());
		}
		net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule.externalFogThickness = c.value("fog", t);
		// the camera path also carries the (invisible) viewer along, so chunks load where the camera is
		double[] cam = camera(0);
		if (cam != null) {
			mc.player.setPos(cam[0], cam[1] - mc.player.getEyeHeight(), cam[2]);
		}
		// hidden entities stay in the world (RewindLookMixins skip drawing them outside the editor, outline them inside)
		applySkins(mc, c);
	}

	private static Object overworldClock;

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setTime(Minecraft mc, long ticks) {
		try {
			if (overworldClock == null) {
				overworldClock = mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
						.getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
			}
			long day = RewindCompat.dayTicks(mc.level, (net.minecraft.core.Holder) overworldClock);
			long target = Math.floorDiv(day, 24000L) * 24000L + Math.floorMod(ticks, 24000L);
			((net.minecraft.client.ClientClockManager) mc.level.clockManager()).handleUpdates(mc.level.getGameTime(),
					(Map) Map.of(overworldClock, new net.minecraft.world.clock.ClockNetworkState(target, 0f, 0f)));
		} catch (RuntimeException e) {
			overworldClock = null;
		}
	}

	// ---- skins: a player name or a PNG per entity ----

	private static final Map<String, Object> SKINS = new ConcurrentHashMap<>();   // source -> PlayerSkin (or a String error / "loading")
	private static java.lang.reflect.Field skinLookup;

	@SuppressWarnings("unchecked")
	private static void applySkins(Minecraft mc, RewindEdits.Clip c) {
		if (c.skins.isEmpty() || mc.getConnection() == null) {
			return;
		}
		for (var e : c.skins.entrySet()) {
			Object skin = SKINS.computeIfAbsent(e.getValue(), RewindApply::load);
			if (!(skin instanceof net.minecraft.world.entity.player.PlayerSkin playerSkin)) {
				continue;
			}
			var info = mc.getConnection().getPlayerInfo(UUID.fromString(e.getKey()));
			if (info == null) {
				continue;
			}
			try {
				if (skinLookup == null) {
					skinLookup = info.getClass().getDeclaredField("skinLookup");
					skinLookup.setAccessible(true);
				}
				var current = (java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin>) skinLookup.get(info);
				if (current == null || current.get() != playerSkin) {
					skinLookup.set(info, (java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin>) () -> playerSkin);
				}
			} catch (ReflectiveOperationException ex) {
				LOG.warn("Velo Rewind: can't swap a skin", ex);
				return;
			}
		}
	}

	// Status of a skin source for the panel: null = ready, else a short text.
	static String skinStatus(String source) {
		Object s = SKINS.get(source);
		return s instanceof String text ? text : null;
	}

	static void forgetSkin(String source) {
		SKINS.remove(source);
	}

	private static Object load(String source) {
		Thread.ofVirtual().start(() -> {
			try {
				byte[] png;
				boolean slim = false;
				if (source.toLowerCase().endsWith(".png")) {
					png = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(source));
				} else {
					var http = java.net.http.HttpClient.newHttpClient();
					var r1 = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/" + source)).build(),
							java.net.http.HttpResponse.BodyHandlers.ofString());
					if (r1.statusCode() != 200) {
						SKINS.put(source, "No player called " + source);
						return;
					}
					String id = com.google.gson.JsonParser.parseString(r1.body()).getAsJsonObject().get("id").getAsString();
					var r2 = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id)).build(),
							java.net.http.HttpResponse.BodyHandlers.ofString());
					String textures = null;
					for (var prop : com.google.gson.JsonParser.parseString(r2.body()).getAsJsonObject().getAsJsonArray("properties")) {
						if (prop.getAsJsonObject().get("name").getAsString().equals("textures")) {
							textures = new String(java.util.Base64.getDecoder().decode(prop.getAsJsonObject().get("value").getAsString()), java.nio.charset.StandardCharsets.UTF_8);
						}
					}
					var skinJson = com.google.gson.JsonParser.parseString(textures).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
					slim = skinJson.has("metadata") && "slim".equals(skinJson.getAsJsonObject("metadata").get("model").getAsString());
					png = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(skinJson.get("url").getAsString())).build(),
							java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body();
				}
				boolean slimArms = slim;
				byte[] bytes = png;
				Minecraft.getInstance().execute(() -> {
					try {
						var image = com.mojang.blaze3d.platform.NativeImage.read(bytes);
						var id = net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "rewind/skin_" + Integer.toHexString(source.hashCode()));
						Minecraft.getInstance().getTextureManager().register(id, new net.minecraft.client.renderer.texture.DynamicTexture(() -> "rewind skin", image));
						var texture = new net.minecraft.core.ClientAsset.ResourceTexture(id, id);
						SKINS.put(source, net.minecraft.world.entity.player.PlayerSkin.insecure(texture, null, null,
								slimArms ? net.minecraft.world.entity.player.PlayerModelType.SLIM : net.minecraft.world.entity.player.PlayerModelType.WIDE));
					} catch (Exception ex) {
						SKINS.put(source, "Not a skin picture");
					}
				});
			} catch (Exception ex) {
				SKINS.put(source, "Couldn't load the skin");
			}
		});
		return "loading";
	}
}
*///?}
