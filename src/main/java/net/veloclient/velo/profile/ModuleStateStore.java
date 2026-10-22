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
 */
public final class ModuleStateStore {

	public record ModuleSnapshot(boolean enabled, Map<String, Object> fields) {
	}

	/** Snapshot entry (not a module) recording which key codes the keybind fields hold. */
	public static final String META_ID = "_velo";
	private static final String KEY_FORMAT = "Key format";
	/** Set once a profile has the 0.9.8 HUD look (elements without their own dark boxes). */
	private static final String HUD_LOOK = "HUD look";

	/**
	 * Key codes changed meaning in 26.3 (GLFW key codes -> SDL scancodes). The client sets the
	 * running version's format and a converter {@code (storedFormat, code) -> code for this version},
	 * so a profile saved on one version keeps the same physical keys on another.
	 */
	public static String currentKeyFormat = "glfw";
	public static java.util.function.BiFunction<String, Integer, Integer> keyConverter = (format, code) -> code;

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
					} else if (field instanceof ConfigField.ColorField color) {
						fields.put(color.label(), color.get().getAsInt());
					} else if (field instanceof ConfigField.ChordKeybindField chord) {
						fields.put(chord.label(), chord.get().get());
					} else if (field instanceof ConfigField.KeybindField keybind) {
						fields.put(keybind.label(), keybind.getKeyCode().getAsInt());
					}
				}
			}
			result.put(module.id(), new ModuleSnapshot(module.isEnabled(), fields));
		}
		result.put(META_ID, new ModuleSnapshot(false, Map.of(KEY_FORMAT, currentKeyFormat, HUD_LOOK, "0.9.8")));
		return result;
	}

	/**
	 * Settings from older versions: the CPS Counter module was merged into Mouse Buttons (style
	 * "Text"), so a profile that had CPS Counter on - and Mouse Buttons off - gets Mouse Buttons in
	 * text style instead of silently losing its CPS display.
	 */
	static Map<String, ModuleSnapshot> migrate(Map<String, ModuleSnapshot> snapshot) {
		snapshot = migrateHudLook(snapshot);
		ModuleSnapshot cps = snapshot.get("cps-counter");
		ModuleSnapshot mouse = snapshot.get("mouse-buttons");
		if (cps == null || !cps.enabled() || (mouse != null && mouse.enabled())) {
			return snapshot;
		}
		Map<String, ModuleSnapshot> result = new LinkedHashMap<>(snapshot);
		Map<String, Object> fields = new LinkedHashMap<>(mouse == null ? Map.of() : mouse.fields());
		fields.put("Style", "Text");
		result.put("mouse-buttons", new ModuleSnapshot(true, fields));
		result.remove("cps-counter");
		return result;
	}

	/**
	 * 0.9.8: HUD elements dropped their dark boxes by default (they hid the world). A profile saved before that still
	 * says "Show Background: on" for most of them - once, those switches are dropped so the new default applies.
	 */
	static Map<String, ModuleSnapshot> migrateHudLook(Map<String, ModuleSnapshot> snapshot) {
		ModuleSnapshot meta = snapshot.get(META_ID);
		if (meta != null && meta.fields().get(HUD_LOOK) != null) {
			return snapshot;
		}
		Map<String, ModuleSnapshot> result = new LinkedHashMap<>(snapshot);
		for (Map.Entry<String, ModuleSnapshot> entry : snapshot.entrySet()) {
			Map<String, Object> fields = entry.getValue().fields();
			if (fields.containsKey("Show Background") || fields.containsKey("Background") && fields.get("Background") instanceof Boolean) {
				Map<String, Object> copy = new LinkedHashMap<>(fields);
				copy.remove("Show Background");
				if (copy.get("Background") instanceof Boolean) {
					copy.remove("Background");
				}
				result.put(entry.getKey(), new ModuleSnapshot(entry.getValue().enabled(), copy));
			}
		}
		return result;
	}

	public static void applyAll(Map<String, ModuleSnapshot> snapshot) {
		snapshot = migrate(snapshot);
		ModuleSnapshot meta = snapshot.get(META_ID);
		// Saved before the format was recorded = GLFW codes (every version before 26.3).
		String storedFormat = meta != null && meta.fields().get(KEY_FORMAT) instanceof String f ? f : "glfw";
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
					} else if (field instanceof ConfigField.ColorField color && value instanceof Number number) {
						color.set().accept(number.intValue());
					} else if (field instanceof ConfigField.KeybindField keybind && value instanceof Number number) {
						keybind.onKeyCodeChosen().accept(keyConverter.apply(storedFormat, number.intValue()));
					} else if (field instanceof ConfigField.ChordKeybindField chord && value instanceof java.util.List<?> list) {
						// Round-tripped through JSON, so each element
						// deserializes back as a Double rather than the
						// Integer it was captured as - normalize here rather
						// than at every call site.
						java.util.List<Integer> keyCodes = new java.util.ArrayList<>();
						for (Object element : list) {
							if (element instanceof Number number) {
								keyCodes.add(keyConverter.apply(storedFormat, number.intValue()));
							}
						}
						chord.set().accept(keyCodes);
					}
				}
			}
			// Applied after fields: modules like Performance Boost read
			// their own field state (e.g. which preset is selected) from
			// inside onEnable(), so the fields have to already be correct
			// before enabling can flip that switch. Automation modules opt
			// out of this entirely via restoreEnabledStateOnLoad() - they
			// always come back up disabled regardless of how the profile
			// was saved.
			if (module.restoreEnabledStateOnLoad()) {
				module.setEnabled(saved.enabled());
			}
		}
	}
}
