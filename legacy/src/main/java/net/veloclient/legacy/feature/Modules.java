package net.veloclient.legacy.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.veloclient.legacy.Json;
import net.veloclient.legacy.VeloLegacy;
import net.veloclient.legacy.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** All 1.8.9 modules: registry, ticking, the HUD pass and the config file (.velo-client/legacy-modules.json). */
public final class Modules {

	private static final List<LegacyModule> ALL = new ArrayList<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static long saveAt;
	private static boolean loaded;

	private Modules() {
	}

	public static List<LegacyModule> all() {
		return ALL;
	}

	public static void register() {
		ALL.add(new LegacyFeatures.ToggleSprint());
		ALL.add(new LegacyFeatures.Fullbright());
		ALL.add(new LegacyFeatures.Zoom());
		ALL.add(new PvpFeatures.Freelook());
		ALL.add(new PvpFeatures.HitColor());
		ALL.add(new PvpFeatures.OldAnimations());
		ALL.add(new LegacyFeatures.FpsDisplay());
		ALL.add(new LegacyFeatures.CpsDisplay());
		ALL.add(new LegacyFeatures.Keystrokes());
		ALL.add(new LegacyFeatures.Coordinates());
		ALL.add(new LegacyFeatures.PingDisplay());
		ALL.add(new LegacyFeatures.ArmorStatus());
		ALL.add(new LegacyFeatures.PotionStatus());
		ALL.add(new LegacyFeatures.Clock());
		ALL.add(new LegacyFeatures.ReachDisplay());
		ALL.add(new LegacyFeatures.DynamicFps());
		ALL.add(new LegacyFeatures.FpsBoost());
		load();
	}

	private static Path file() {
		return VeloPaths.root().resolve("legacy-modules.json");
	}

	private static void load() {
		loaded = true;
		JsonObject root = Json.read(file());
		if (root == null) {
			return;
		}
		for (LegacyModule m : ALL) {
			JsonElement e = root.get(m.id);
			if (e == null || !e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			if (o.has("hx")) {
				m.hx = o.get("hx").getAsFloat();
				m.hy = o.get("hy").getAsFloat();
			}
			for (Setting s : m.settings) {
				JsonElement v = o.get(s.name);
				if (v == null) {
					continue;
				}
				if (s instanceof Setting.Toggle t) {
					t.value = v.getAsBoolean();
				} else if (s instanceof Setting.Slider sl) {
					sl.set(v.getAsDouble());
				}
			}
			boolean on = o.has("enabled") && o.get("enabled").getAsBoolean();
			if (on != m.enabled) {
				m.enabled = on;
				if (on) {
					m.onToggle(true);
				}
			} else if (on) {
				m.onToggle(true);
			}
		}
	}

	public static void saveSoon() {
		saveAt = System.currentTimeMillis() + 1000;
	}

	public static void saveNow() {
		if (!loaded) {
			return;
		}
		JsonObject root = new JsonObject();
		for (LegacyModule m : ALL) {
			JsonObject o = new JsonObject();
			o.addProperty("enabled", m.enabled);
			o.addProperty("hx", m.hx);
			o.addProperty("hy", m.hy);
			for (Setting s : m.settings) {
				if (s instanceof Setting.Toggle t) {
					o.addProperty(s.name, t.value);
				} else if (s instanceof Setting.Slider sl) {
					o.addProperty(s.name, sl.value);
				}
			}
			root.add(m.id, o);
		}
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (Exception e) {
			VeloLegacy.LOGGER.warn("Velo: couldn't save the 1.8.9 module settings", e);
		}
	}

	public static void tick(Minecraft mc) {
		for (LegacyModule m : ALL) {
			if (m.enabled) {
				try {
					m.tick(mc);
				} catch (RuntimeException e) {
					VeloLegacy.LOGGER.warn("Velo module {} failed - turned off", m.id, e);
					m.setEnabled(false);
				}
			}
		}
		if (saveAt != 0 && System.currentTimeMillis() >= saveAt) {
			saveAt = 0;
			saveNow();
		}
	}

	/** The HUD pass (end of GameGui.render): every enabled HUD module at its place. */
	public static void renderHud(Minecraft mc) {
		if (mc.options.hideGui || mc.options.debugEnabled) {
			return;
		}
		boolean editing = mc.screen instanceof net.veloclient.legacy.menu.HudEditScreen;
		Window window = new Window(mc);
		for (LegacyModule m : ALL) {
			if (!m.enabled) {
				continue;
			}
			try {
				m.frame(mc);
				if (m.hud && !editing) {
					drawAt(mc, m, window.getWidth(), window.getHeight(), false);
				}
			} catch (RuntimeException e) {
				VeloLegacy.LOGGER.warn("Velo module {} failed while drawing - turned off", m.id, e);
				m.setEnabled(false);
			}
		}
	}

	/** Draws {@code m} at its saved place (kept on screen) and returns {x, y, w, h}. */
	public static int[] drawAt(Minecraft mc, LegacyModule m, int sw, int sh, boolean preview) {
		int x = Math.round(m.hx * sw);
		int y = Math.round(m.hy * sh);
		GlStateManager.enableBlend();
		GlStateManager.color4f(1f, 1f, 1f, 1f);
		int[] size = m.render(mc, x, y, preview);
		return new int[] {x, y, size[0], size[1]};
	}
}
