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
				keyCode -> {
					binding.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(keyCode));
					KeyBinding.updateKeysByCode();
				});
	}
}
