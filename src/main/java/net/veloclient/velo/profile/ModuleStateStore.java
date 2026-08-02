package net.veloclient.velo.profile;

import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic capture/restore of every module's enabled state and
 * {@link Configurable} field values, keyed by module id and field label so
 * it works for any module without per-module serialization code. Backs the
 * profile system (design spec section 5/8) - before this, none of it was
 * persisted at all, so every setting reset to its code default on relaunch.
 *
 * <p>{@link ConfigField.KeybindField} is intentionally not captured - it
 * only exposes a display-text supplier, not the raw key code, so there's
 * nothing safe to round-trip through JSON yet.
 */
public final class ModuleStateStore {

	public record ModuleSnapshot(boolean enabled, Map<String, Object> fields) {
	}

	private ModuleStateStore() {
	}

	public static Map<String, ModuleSnapshot> captureAll() {
		Map<String, ModuleSnapshot> result = new LinkedHashMap<>();
		for (Module module : ModuleRegistry.all()) {
			Map<String, Object> fields = new LinkedHashMap<>();
			if (module instanceof Configurable configurable) {
				for (ConfigField field : configurable.configFields()) {
					if (field instanceof ConfigField.SliderField slider) {
						fields.put(slider.label(), slider.get().getAsDouble());
					} else if (field instanceof ConfigField.ToggleField toggle) {
						fields.put(toggle.label(), toggle.get().getAsBoolean());
					} else if (field instanceof ConfigField.TextField text) {
						fields.put(text.label(), text.get().get());
					} else if (field instanceof ConfigField.ChoiceField choice) {
						fields.put(choice.label(), choice.get().get());
					}
				}
			}
			result.put(module.id(), new ModuleSnapshot(module.isEnabled(), fields));
		}
		return result;
	}

	public static void applyAll(Map<String, ModuleSnapshot> snapshot) {
		for (Module module : ModuleRegistry.all()) {
			ModuleSnapshot saved = snapshot.get(module.id());
			if (saved == null) {
				continue;
			}
			if (module instanceof Configurable configurable) {
				for (ConfigField field : configurable.configFields()) {
					Object value = saved.fields().get(field.label());
					if (value == null) {
						continue;
					}
					if (field instanceof ConfigField.SliderField slider && value instanceof Number number) {
						slider.set().accept(number.doubleValue());
					} else if (field instanceof ConfigField.ToggleField toggle && value instanceof Boolean bool) {
						toggle.set().accept(bool);
					} else if (field instanceof ConfigField.TextField text && value instanceof String str) {
						text.set().accept(str);
					} else if (field instanceof ConfigField.ChoiceField choice && value instanceof String str) {
						choice.set().accept(str);
					}
				}
			}
			// Applied after fields: modules like Performance Boost read
			// their own field state (e.g. which preset is selected) from
			// inside onEnable(), so the fields have to already be correct
			// before enabling can flip that switch.
			module.setEnabled(saved.enabled());
		}
	}
}
