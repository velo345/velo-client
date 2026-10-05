package net.veloclient.velo.client.keybind;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Version-neutral access to Minecraft's key bindings for the Velo Controls screen: names,
 * categories, the bound key, conflicts, rebinding and resetting (saved to options.txt right away,
 * exactly like vanilla's screen does).
 */
public final class BindingAccess {

	private BindingAccess() {
	}

	public static List<KeyBinding> all() {
		//? if <26.1 {
		return List.of(MinecraftClient.getInstance().options.allKeys);
		//?} else {
		/*return List.of(net.minecraft.client.Minecraft.getInstance().options.keyMappings);
		*///?}
	}

	public static String name(KeyBinding binding) {
		//? if <26.1 {
		return net.minecraft.text.Text.translatable(binding.getId()).getString();
		//?} else {
		/*return net.minecraft.network.chat.Component.translatable(binding.getName()).getString();
		*///?}
	}

	/** Category id like "minecraft:movement" or "velo-client:..." (for grouping and icons). */
	public static String categoryId(KeyBinding binding) {
		return binding.getCategory().id().toString();
	}

	public static String categoryLabel(KeyBinding binding) {
		//? if <26.1 {
		return binding.getCategory().getLabel().getString();
		//?} else {
		/*return binding.getCategory().label().getString();
		*///?}
	}

	public static String keyText(KeyBinding binding) {
		//? if <26.1 {
		return binding.getBoundKeyLocalizedText().getString();
		//?} else {
		/*return binding.getTranslatedKeyMessage().getString();
		*///?}
	}

	/** Stable name of the bound key (e.g. key.keyboard.g) - equal names = same key. */
	public static String keyId(KeyBinding binding) {
		//? if <26.1 {
		return binding.getBoundKeyTranslationKey();
		//?} else {
		/*return binding.saveString();
		*///?}
	}

	public static boolean isUnbound(KeyBinding binding) {
		return binding.isUnbound();
	}

	public static boolean isDefault(KeyBinding binding) {
		return binding.isDefault();
	}

	/**
	 * Other bindings on the same key (empty when unbound). Like vanilla, two bindings that are both
	 * still on their default key don't count - Minecraft ships some keys shared on purpose.
	 */
	public static List<KeyBinding> conflicts(KeyBinding binding) {
		List<KeyBinding> out = new ArrayList<>();
		if (binding.isUnbound()) {
			return out;
		}
		String key = keyId(binding);
		for (KeyBinding other : all()) {
			if (other != binding && !other.isUnbound() && keyId(other).equals(key) && (!other.isDefault() || !binding.isDefault())) {
				out.add(other);
			}
		}
		return out;
	}

	public static void bindKeyboard(KeyBinding binding, int keyCode) {
		set(binding, net.minecraft.client.util.InputUtil.Type.KEYSYM.createFromCode(keyCode));
	}

	public static void bindMouse(KeyBinding binding, int button) {
		set(binding, net.minecraft.client.util.InputUtil.Type.MOUSE.createFromCode(button));
	}

	public static void unbind(KeyBinding binding) {
		//? if <26.1 {
		set(binding, net.minecraft.client.util.InputUtil.UNKNOWN_KEY);
		//?} else {
		/*set(binding, com.mojang.blaze3d.platform.InputConstants.UNKNOWN);
		*///?}
	}

	public static void reset(KeyBinding binding) {
		set(binding, binding.getDefaultKey());
	}

	private static void set(KeyBinding binding, net.minecraft.client.util.InputUtil.Key key) {
		//? if <26.1 {
		binding.setBoundKey(key);
		KeyBinding.updateKeysByCode();
		MinecraftClient.getInstance().options.write();
		//?} else {
		/*binding.setKey(key);
		KeyBinding.resetMapping();
		net.minecraft.client.Minecraft.getInstance().options.save();
		*///?}
	}
}
