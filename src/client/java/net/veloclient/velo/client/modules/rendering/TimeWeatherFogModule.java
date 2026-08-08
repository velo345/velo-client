package net.veloclient.velo.client.modules.rendering;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
//? if <26.1 {
import net.minecraft.client.render.fog.FogRenderer;
//?} else {
/*import net.minecraft.client.renderer.fog.FogRenderer;
*///?}
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side-only time, weather and fog override - never touches what's
 * actually sent to/tracked by the server (the "Normal" option always means
 * "leave whatever the server says alone"), purely how it's rendered
 * locally. Time and weather are re-applied every client tick directly
 * through vanilla's own public setters ({@code ClientWorld#setTime}/
 * {@code setRainGradient}/{@code setThunderGradient}, or their 26.1+
 * equivalents) - no mixin needed for either, since vanilla already exposes
 * exactly the entry points a server-time-update packet itself would call.
 * Fog color/distance instead go through {@code AtmosphericFogMixin} (the one
 * environment covering ordinary open-air fog - water/lava/powder-snow fog
 * are left alone, since those represent an actually different medium), and
 * "disable it fully" reuses vanilla's own {@code FogRenderer.toggleFog()}
 * debug toggle rather than reimplementing it.
 */
public final class TimeWeatherFogModule extends AbstractModule implements Configurable {

	// Day/Noon/Night/Midnight are deliberately vanilla's own exact "/time set
	// <preset>" values (confirmed against vanilla's real command source, not
	// guessed) - a previous set of hand-picked values was close but not
	// exact, which read as "the label doesn't match what's actually
	// in-game" even though the intent (a rough sunrise/day/noon/etc. cycle)
	// was right. Sunrise/Sunset aren't real vanilla presets (there's no
	// "/time set sunrise"), so those two keep sensible, common values.
	private static final Map<String, Long> FIXED_TIMES = Map.of(
			"Sunrise", 0L, "Day", 1000L, "Noon", 6000L, "Sunset", 12000L,
			"Night", 13000L, "Midnight", 18000L);
	private static final List<String> TIME_OPTIONS =
			List.of("Normal", "Sunrise", "Day", "Noon", "Sunset", "Night", "Midnight");

	private static final Map<String, float[]> WEATHER_GRADIENTS = Map.of(
			"Clear", new float[] {0f, 0f}, "Rain", new float[] {1f, 0f}, "Thunder", new float[] {1f, 1f});
	private static final List<String> WEATHER_OPTIONS = List.of("Normal", "Clear", "Rain", "Thunder");

	private static final List<String> FOG_MODE_OPTIONS = List.of("Normal", "Disabled", "Custom");

	private static volatile TimeWeatherFogModule instance;

	private String timeMode = "Normal";
	private String weatherMode = "Normal";
	private String fogMode = "Normal";
	private int fogColor = 0xFFC0D8FF;
	private double fogThickness = 1.0;

	private boolean vanillaFogCurrentlyDisabledByUs;
	private String lastAppliedTimeMode;

	public TimeWeatherFogModule() {
		super("time-weather-fog", "Time, Weather & Fog",
				"Locks the local time-of-day and weather to a fixed state, and lets you customize or fully "
						+ "disable fog - purely visual, never sent to the server.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || client.world == null) {
			applyFogToggle(false);
			lastAppliedTimeMode = null;
			return;
		}
		applyTime(client);
		applyWeather(client);
		applyFogToggle("Disabled".equals(fogMode));
	}

	@Override
	public void onDisable() {
		applyFogToggle(false);
		lastAppliedTimeMode = null;
	}

	private void applyFogToggle(boolean wantDisabled) {
		if (wantDisabled != vanillaFogCurrentlyDisabledByUs) {
			FogRenderer.toggleFog();
			vanillaFogCurrentlyDisabledByUs = wantDisabled;
		}
	}

	//? if <26.1 {
	private void applyTime(MinecraftClient client) {
		Long fixed = FIXED_TIMES.get(timeMode);
		if (fixed == null) {
			lastAppliedTimeMode = null;
			return;
		}
		// Applied once per actual mode change, not every tick - re-issuing
		// the exact same setTime() call every single tick (previously) was
		// visibly flickery, most likely fighting the sky/fog renderer's own
		// last-vs-current interpolation state. shouldTickTimeOfDay=false
		// already keeps it frozen on its own between changes.
		if (!timeMode.equals(lastAppliedTimeMode)) {
			client.world.setTime(client.world.getTime(), fixed, false);
			lastAppliedTimeMode = timeMode;
		}
	}

	private void applyWeather(MinecraftClient client) {
		float[] gradient = WEATHER_GRADIENTS.get(weatherMode);
		if (gradient == null) {
			return;
		}
		client.world.setRainGradient(gradient[0]);
		client.world.setThunderGradient(gradient[1]);
	}
	//?} else {
	/*private void applyTime(MinecraftClient client) {
		Long fixed = FIXED_TIMES.get(timeMode);
		if (fixed == null) {
			lastAppliedTimeMode = null;
			return;
		}
		if (!timeMode.equals(lastAppliedTimeMode)) {
			client.world.setTimeFromServer(fixed);
			lastAppliedTimeMode = timeMode;
		}
	}

	private void applyWeather(MinecraftClient client) {
		float[] gradient = WEATHER_GRADIENTS.get(weatherMode);
		if (gradient == null) {
			return;
		}
		client.world.setRainLevel(gradient[0]);
		client.world.setThunderLevel(gradient[1]);
	}
	*///?}

	/** Read from {@code AtmosphericFogMixin} - whether to override the atmospheric fog color this frame. */
	public static boolean isFogColorOverrideActive() {
		TimeWeatherFogModule module = instance;
		return module != null && module.isEnabled() && "Custom".equals(module.fogMode);
	}

	public static int fogColorOverrideValue() {
		TimeWeatherFogModule module = instance;
		return module != null ? module.fogColor : 0xFFFFFFFF;
	}

	/** Read from {@code AtmosphericFogMixin} - whether to rescale the atmospheric fog start/end distances this frame. */
	public static boolean isFogDistanceOverrideActive() {
		return isFogColorOverrideActive();
	}

	/** Higher = thicker/closer fog (distances are divided by this). */
	public static double fogThicknessValue() {
		TimeWeatherFogModule module = instance;
		return module != null ? Math.max(0.1, module.fogThickness) : 1.0;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Time", TIME_OPTIONS, () -> timeMode, v -> timeMode = v),
				new ConfigField.ChoiceField("Weather", WEATHER_OPTIONS, () -> weatherMode, v -> weatherMode = v),
				new ConfigField.ChoiceField("Fog Mode", FOG_MODE_OPTIONS, () -> fogMode, v -> fogMode = v),
				new ConfigField.ColorField("Fog Color (Custom mode)", () -> fogColor, v -> fogColor = v, true),
				new ConfigField.SliderField("Fog Thickness (Custom mode)", 0.1, 3.0, () -> fogThickness,
						v -> fogThickness = v, v -> String.format(Locale.ROOT, "%.1f", v)));
	}
}
