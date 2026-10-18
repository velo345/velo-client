package net.veloclient.velo.client.modules.qol;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.waypoints.WaypointManager;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Party pings: press the ping key (unbound by default - V is taken by voice chat and others; set it in Controls) while looking at something and a marker pops up for everyone in
 * your party who's on the same server and dimension - "go here", "enemy", "loot" - with distance,
 * fading after a few seconds. Waypoints your party shares arrive as a popup you can save with one
 * click. Parties are made in the launcher (Party page).
 */
public final class PartyPingModule extends AbstractModule implements Configurable {

	public static final KeyBinding PING_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.party_ping", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	private record Ping(String from, double x, double y, double z, String dimension, String server, long at, boolean mine) {
	}

	private static final List<Ping> PINGS = new ArrayList<>();
	private static PartyPingModule instance;
	private double seconds = 8;
	private boolean sound = true;
	private int color = 0xFFFFC53D;
	private long lastPing;
	private String command = "party";

	public PartyPingModule() {
		super("party-ping", "Party", "Party chat (/party), pings (V), invites and shared waypoints - parties live in the Friends menu.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (PING_KEY.wasPressed()) {
				if (isEnabled()) {
					pingNow();
				}
			}
		});
		SocialClient.addPartyListener((type, data) -> MinecraftClient.getInstance().execute(() -> onPartyEvent(type, data)));
	}

	private void pingNow() {
		if (!SocialClient.inParty()) {
			NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Not in a party",
					"Start one in the launcher's Party page to ping for friends.", 3000, null, null, null));
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastPing < 700) {
			return;
		}
		lastPing = now;
		double[] target = lookTarget();
		if (target == null) {
			return;
		}
		String dimension = ClientCompat.dimensionId();
		String server = ClientCompat.currentServerAddress();
		synchronized (PINGS) {
			PINGS.add(new Ping("You", target[0], target[1], target[2], dimension, server, now, true));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("x", target[0]);
		payload.put("y", target[1]);
		payload.put("z", target[2]);
		payload.put("dimension", dimension);
		if (server != null) {
			payload.put("server", server);
		}
		SocialClient.partyPing(payload);
		if (sound) {
			net.veloclient.velo.client.pvp.PvpCompat.playUiSound("minecraft:block.note_block.pling", 1.6f, 0.6f);
		}
	}

	/** Where you're looking (block or entity hit within 160 blocks), or null. */
	private static double[] lookTarget() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		var player = client.player;
		if (player == null) {
			return null;
		}
		var hit = player.raycast(160, 1f, false);
		if (hit == null || hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
			return null;
		}
		var pos = hit.getPos();
		return new double[] {pos.x, pos.y, pos.z};
		//?} else {
		/*var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		var hit = player.pick(160, 1f, false);
		if (hit == null || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			return null;
		}
		var pos = hit.getLocation();
		return new double[] {pos.x, pos.y, pos.z};
		*///?}
	}

	private void onPartyEvent(String type, JsonObject data) {
		if (!isEnabled()) {
			return;
		}
		if (type.equals("party_ping") && data.has("x")) {
			synchronized (PINGS) {
				PINGS.add(new Ping(str(data, "fromName"), data.get("x").getAsDouble(), data.get("y").getAsDouble(), data.get("z").getAsDouble(),
						str(data, "dimension"), str(data, "server"), System.currentTimeMillis(), false));
			}
			if (sound) {
				net.veloclient.velo.client.pvp.PvpCompat.playUiSound("minecraft:block.note_block.bell", 1.4f, 0.7f);
			}
		} else if (type.equals("party_waypoint") && data.has("name")) {
			String from = str(data, "fromName");
			Map<String, Object> shared = new com.google.gson.Gson().fromJson(data, new com.google.gson.reflect.TypeToken<Map<String, Object>>() { }.getType());
			NotificationOverlay.show(new NotificationOverlay.Toast(str(data, "from"), from, from + " shared a waypoint", str(data, "name"), 12000,
					null, () -> {
						WaypointManager.importShared(shared, from);
						NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Waypoint saved", str(data, "name"), 2500, null, null, null));
					}, () -> { }).labels("Save", "Ignore"));
		} else if (type.equals("party_chat")) {
			net.veloclient.velo.client.social.PartyChat.show(data);
		} else if (type.equals("party_invite") && data.has("party")) {
			String from = str(data, "fromName");
			String partyId = str(data, "party");
			NotificationOverlay.replaceKeyed("party:" + partyId, new NotificationOverlay.Toast(str(data, "from"), from,
					from + " invited you to a party", str(data, "leaderName") + "'s party - chat, pings & shared waypoints", 30000,
					net.veloclient.velo.client.gui.FriendsScreen::openParty,
					() -> SocialClient.partyRespond(partyId, true),
					() -> SocialClient.partyRespond(partyId, false)).labels("Join", "Decline"));
		}
	}

	/** Called from the HUD pass: draws the live pings. */
	public static void renderHud(DrawContext context, int screenWidth, int screenHeight) {
		PartyPingModule module = instance;
		if (module == null || !module.isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		var self = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		if (self == null) {
			return;
		}
		long now = System.currentTimeMillis();
		long life = Math.round(module.seconds * 1000);
		String dimension = ClientCompat.dimensionId();
		String server = ClientCompat.currentServerAddress();
		List<Ping> pings;
		synchronized (PINGS) {
			PINGS.removeIf(p -> now - p.at() > life);
			pings = List.copyOf(PINGS);
		}
		for (Ping ping : pings) {
			if (!java.util.Objects.equals(ping.dimension(), dimension)
					|| ping.server() != null && server != null && !ping.server().equalsIgnoreCase(server)) {
				continue;
			}
			float[] view = ClientCompat.cameraView(ping.x(), ping.y() + 0.5, ping.z());
			float[] screen = ClientCompat.project(view, screenWidth, screenHeight);
			if (screen == null) {
				continue;
			}
			float age = (now - ping.at()) / (float) life;
			int alpha = (int) (255 * Math.min(1f, (1f - age) * 3f));
			int base = ping.mine() ? 0xFFFFFFFF : module.color;
			int col = (alpha << 24) | (base & 0xFFFFFF);
			int sx = Math.round(screen[0]);
			int sy = Math.round(screen[1]);
			float pulse = 1f + 0.25f * (float) Math.sin((now - ping.at()) / 120.0) * (1f - Math.min(1f, age * 4));
			int r = Math.round(6 * pulse);
			VeloDraw.fillCircle(context, sx, sy, r + 2, (alpha / 2 << 24));
			VeloDraw.fillCircle(context, sx, sy, r, col);
			double dx = ping.x() - net.veloclient.velo.client.pvp.PvpCompat.x(self);
			double dy = ping.y() - net.veloclient.velo.client.pvp.PvpCompat.y(self);
			double dz = ping.z() - net.veloclient.velo.client.pvp.PvpCompat.z(self);
			String label = ping.from() + "  " + Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m";
			int w = client.textRenderer.getWidth(label);
			VeloDraw.fillRounded(context, sx - w / 2 - 4, sy + r + 3, w + 8, 12, 3, (Math.min(alpha, 0xA0) << 24));
			context.drawTextWithShadow(client.textRenderer, label, sx - w / 2, sy + r + 5, (alpha << 24) | 0xFFFFFF);
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.TextField("Party Chat Command", "party", () -> command, v -> {
					command = v == null || v.isBlank() ? "party" : v.strip();
					net.veloclient.velo.client.social.PartyChat.setCommandName(command);
				}),
				KeybindConfig.field("Ping Key", PING_KEY),
				new ConfigField.SliderField("Ping Lasts (s)", 3, 30, () -> seconds, v -> seconds = Math.round(v),
						v -> String.format(Locale.ROOT, "%ds", Math.round(v))),
				new ConfigField.ColorField("Party Ping Color", () -> color, v -> color = v, false),
				new ConfigField.ToggleField("Ping Sound", () -> sound, v -> sound = v));
	}
}
