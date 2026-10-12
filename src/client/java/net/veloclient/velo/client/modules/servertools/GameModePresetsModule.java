package net.veloclient.velo.client.modules.servertools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.profile.VeloProfileStore;
import net.veloclient.velo.client.serverpacks.GameSession;
import net.veloclient.velo.client.serverpacks.ServerPacks;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.config.VeloPaths;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import net.veloclient.velo.profile.VeloProfileManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Switches your Velo profile (HUD layout + module setup) when the game mode changes: a Bed Wars
 * layout with the Server Overlays and Cooldowns HUD, a clean one for Build Battle, your PvP setup
 * for duels... and back to the profile you had when you leave. The mode-to-profile table is kept
 * outside the profiles ({@code config/mode-presets.json}) so switching profiles never rewrites it.
 */
public final class GameModePresetsModule extends AbstractModule implements Configurable {

	public static final String NONE = "Don't switch";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static GameModePresetsModule instance;

	private Map<String, String> presets = new LinkedHashMap<>();
	private boolean restoreOnLeave = true;
	private String profileBeforeSwitch;
	private String switchedTo;

	public GameModePresetsModule() {
		super("mode-presets", "Game Mode Presets", "Loads a different Velo profile per game mode (Bed Wars, duels, SkyWars...) and switches back after.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		load();
		GameSession.onModeChange(session -> MinecraftClient.getInstance().execute(() -> onModeChange(session)));
	}

	public static GameModePresetsModule instance() {
		return instance;
	}

	private static Path file() {
		return VeloPaths.config().resolve("mode-presets.json");
	}

	private void load() {
		try {
			if (Files.exists(file())) {
				Map<String, Object> data = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
						new TypeToken<Map<String, Object>>() { }.getType());
				Object map = data.get("presets");
				if (map instanceof Map<?, ?> m) {
					m.forEach((k, v) -> presets.put(String.valueOf(k), String.valueOf(v)));
				}
				if (data.get("restoreOnLeave") instanceof Boolean b) {
					restoreOnLeave = b;
				}
			}
		} catch (Exception e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't read mode presets", e);
		}
	}

	private void save() {
		try {
			Files.createDirectories(file().getParent());
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("presets", presets);
			data.put("restoreOnLeave", restoreOnLeave);
			Files.writeString(file(), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (Exception e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't save mode presets", e);
		}
	}

	public String presetFor(ServerPacks.Mode mode) {
		return presets.getOrDefault(mode.name(), NONE);
	}

	public void setPreset(ServerPacks.Mode mode, String profile) {
		if (profile == null || profile.equals(NONE)) {
			presets.remove(mode.name());
		} else {
			presets.put(mode.name(), profile);
		}
		save();
	}

	private void onModeChange(GameSession session) {
		if (!isEnabled()) {
			return;
		}
		String target = session.online() ? presetFor(session.mode()) : NONE;
		if (!target.equals(NONE)) {
			if (target.equals(VeloProfileManager.activeName())) {
				return;
			}
			if (switchedTo == null) {
				profileBeforeSwitch = VeloProfileManager.activeName();
			}
			if (switchTo(target)) {
				switchedTo = target;
				NotificationOverlay.show(new NotificationOverlay.Toast(null, null, session.mode().label + " preset",
						"Switched to profile \"" + target + "\"", 3000, null, null, null));
			}
		} else if (switchedTo != null && restoreOnLeave && profileBeforeSwitch != null) {
			String back = profileBeforeSwitch;
			switchedTo = null;
			profileBeforeSwitch = null;
			if (!back.equals(VeloProfileManager.activeName())) {
				switchTo(back);
			}
		}
	}

	private boolean switchTo(String name) {
		var profile = VeloProfileManager.loadByName(name);
		if (profile.isEmpty()) {
			return false;
		}
		VeloProfileStore.saveActive();
		VeloProfileStore.applyToLive(profile.get());
		VeloProfileManager.setActiveName(name);
		// The target profile may have this module off - presets must keep working until you leave.
		if (!isEnabled()) {
			setEnabled(true);
		}
		return true;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ActionButtonField("Choose A Profile Per Game Mode", () -> MinecraftClient.getInstance().setScreen(
						new net.veloclient.velo.client.gui.ModePresetsScreen(net.veloclient.velo.client.util.ClientCompat.currentScreen()))),
				new ConfigField.ActionButtonField(restoreOnLeave ? "Switch Back When Leaving: On" : "Switch Back When Leaving: Off", () -> {
					restoreOnLeave = !restoreOnLeave;
					save();
				}));
	}
}
