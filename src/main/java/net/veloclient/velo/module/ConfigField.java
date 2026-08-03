package net.veloclient.velo.module;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** One adjustable setting a {@link Configurable} module exposes to the per-module config screen. */
public sealed interface ConfigField {

	String label();

	record SliderField(String label, double min, double max, DoubleSupplier get, DoubleConsumer set,
			DoubleFunction<String> format) implements ConfigField {
	}

	record ToggleField(String label, BooleanSupplier get, Consumer<Boolean> set) implements ConfigField {
	}

	/**
	 * A rebindable keybind. Deliberately typed with only JDK types (not
	 * {@code KeyBinding}/{@code InputUtil}) so this common-sourceset file
	 * doesn't depend on client-only classes - the client-side module wires
	 * {@code onKeyCodeChosen} to call {@code KeyBinding#setBoundKey} and
	 * {@code KeyBinding.updateKeysByCode()} with the raw GLFW key code.
	 */
	record KeybindField(String label, Supplier<String> displayText, IntConsumer onKeyCodeChosen) implements ConfigField {
	}

	/** A free-text field, e.g. a comma-separated blocklist of substrings to filter by. */
	record TextField(String label, String placeholder, Supplier<String> get, Consumer<String> set) implements ConfigField {
	}

	/** A dropdown/cycle choice between a small fixed set of named presets. */
	record ChoiceField(String label, java.util.List<String> options, Supplier<String> get, Consumer<String> set) implements ConfigField {
	}

	/** A packed ARGB color (0xAARRGGBB), edited via a real HSV picker rather than raw component sliders. */
	record ColorField(String label, IntSupplier get, IntConsumer set, boolean includeAlpha) implements ConfigField {
	}
}
