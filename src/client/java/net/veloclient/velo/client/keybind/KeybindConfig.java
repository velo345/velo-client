package net.veloclient.velo.client.keybind;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.module.ConfigField;

/** Builds the {@link ConfigField.KeybindField} boilerplate for a module's {@code KeyBinding}, so each module doesn't repeat it. */
public final class KeybindConfig {

	private KeybindConfig() {
	}

	public static ConfigField.KeybindField field(String label, KeyBinding binding) {
		return new ConfigField.KeybindField(label,
				() -> binding.isUnbound() ? "Unbound" : binding.getBoundKeyLocalizedText().getString(),
				() -> currentKeyCode(binding),
				keyCode -> {
					binding.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(keyCode));
					KeyBinding.updateKeysByCode();
				});
	}

	/**
	 * The current bound key has no direct public getter (the field itself is protected) -
	 * reconstructing it from its own translation key round-trips through the same lookup table
	 * {@code setBoundKey} itself resolves against, so it's exactly the current binding, not the
	 * key this was registered with. {@code InputUtil#fromTranslationKey} (Yarn) -> {@code
	 * InputConstants#getKey} (Mojmap), and the resulting key's {@code getCode()} (Yarn) ->
	 * {@code getValue()} (Mojmap) - both diverge by name only.
	 */
	private static int currentKeyCode(KeyBinding binding) {
		//? if <26.1 {
		return InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey()).getCode();
		//?} else {
		/*return InputUtil.getKey(binding.getBoundKeyTranslationKey()).getValue();
		*///?}
	}
}
