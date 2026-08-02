package net.veloclient.velo.client.modules.utility;

/**
 * One user-created "press this key, run this command" binding. {@code
 * keyCode} is a raw GLFW key code (not a vanilla {@code KeyBinding} - this
 * list is created/resized freely at runtime, which vanilla's own
 * once-at-startup keybinding registration doesn't support), or
 * {@code GLFW_KEY_UNKNOWN} while unbound.
 */
public record CommandKeybindEntry(String command, int keyCode) {
}
