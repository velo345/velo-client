package net.veloclient.velo.preview.guide;

import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.List;
import java.util.Optional;

/**
 * The guide changes the same settings the R-Shift menu does - through each module's own fields, found by their label -
 * so nothing here duplicates a module's logic, and a choice made in the guide is exactly what the menu shows afterwards.
 * Everything is saved to the active profile once, when the guide closes.
 */
final class GuideSettings {

	private static boolean dirty;

	private GuideSettings() {
	}

	static Optional<Module> module(String id) {
		return ModuleRegistry.get(id);
	}

	static boolean enabled(String id) {
		return module(id).map(Module::isEnabled).orElse(false);
	}

	static void setEnabled(String id, boolean on) {
		module(id).ifPresent(m -> {
			if (m.isEnabled() != on) {
				m.setEnabled(on);
				dirty = true;
			}
		});
	}

	private static Optional<ConfigField> field(String id, String label) {
		Optional<Module> m = module(id);
		if (m.isEmpty() || !(m.get() instanceof Configurable configurable)) {
			return Optional.empty();
		}
		for (ConfigField f : configurable.configFields()) {
			String l = switch (f) {
				case ConfigField.SliderField s -> s.label();
				case ConfigField.ToggleField s -> s.label();
				case ConfigField.ChoiceField s -> s.label();
				case ConfigField.ColorField s -> s.label();
				default -> null;
			};
			if (label.equals(l)) {
				return Optional.of(f);
			}
		}
		return Optional.empty();
	}

	static double slider(String id, String label, double fallback) {
		return field(id, label).filter(f -> f instanceof ConfigField.SliderField)
				.map(f -> ((ConfigField.SliderField) f).get().getAsDouble()).orElse(fallback);
	}

	static void setSlider(String id, String label, double value) {
		field(id, label).ifPresent(f -> {
			if (f instanceof ConfigField.SliderField s) {
				s.set().accept(Math.max(s.min(), Math.min(s.max(), value)));
				dirty = true;
			}
		});
	}

	static boolean toggle(String id, String label) {
		return field(id, label).filter(f -> f instanceof ConfigField.ToggleField)
				.map(f -> ((ConfigField.ToggleField) f).get().getAsBoolean()).orElse(false);
	}

	static void setToggle(String id, String label, boolean value) {
		field(id, label).ifPresent(f -> {
			if (f instanceof ConfigField.ToggleField t) {
				t.set().accept(value);
				dirty = true;
			}
		});
	}

	static String choice(String id, String label, String fallback) {
		return field(id, label).filter(f -> f instanceof ConfigField.ChoiceField)
				.map(f -> ((ConfigField.ChoiceField) f).get().get()).orElse(fallback);
	}

	static List<String> choices(String id, String label) {
		return field(id, label).filter(f -> f instanceof ConfigField.ChoiceField)
				.map(f -> ((ConfigField.ChoiceField) f).options()).orElse(List.of());
	}

	static void setChoice(String id, String label, String value) {
		field(id, label).ifPresent(f -> {
			if (f instanceof ConfigField.ChoiceField c) {
				c.set().accept(value);
				dirty = true;
			}
		});
	}

	static int color(String id, String label, int fallback) {
		return field(id, label).filter(f -> f instanceof ConfigField.ColorField)
				.map(f -> ((ConfigField.ColorField) f).get().getAsInt()).orElse(fallback);
	}

	static void setColor(String id, String label, int value) {
		field(id, label).ifPresent(f -> {
			if (f instanceof ConfigField.ColorField c) {
				c.set().accept(value);
				dirty = true;
			}
		});
	}

	static void markDirty() {
		dirty = true;
	}

	/** Saves what the guide changed into the active profile (once, when it closes). */
	static void saveIfChanged() {
		if (dirty) {
			dirty = false;
			net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		}
	}
}
