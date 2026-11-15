package net.veloclient.velo.client.nowplaying;

import java.lang.invoke.MethodHandle;

/**
 * Windows media keys (play/pause, next, previous) pressed through user32's keybd_event - exactly what a keyboard's media
 * keys do, so every player that reacts to them (Spotify, browsers, Apple Music, VLC...) follows. The fallback when the
 * PowerShell media-session helper can't run, so the Media Player buttons always do something. The foreign function API
 * is final from Java 22 (26.x builds); 1.21.x runs on Java 21 and presses the key through WScript.Shell instead.
 */
final class MediaKeys {

	static final int NEXT = 0xB0;
	static final int PREVIOUS = 0xB1;
	static final int PLAY_PAUSE = 0xB3;
	private static final int KEYEVENTF_EXTENDEDKEY = 0x1;
	private static final int KEYEVENTF_KEYUP = 0x2;
	private static MethodHandle keybdEvent;
	private static boolean looked;

	private MediaKeys() {
	}

	static synchronized void press(int vk) {
		//? if >=26.1 {
		/*if (!looked) {
			looked = true;
			try {
				java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker();
				java.lang.foreign.SymbolLookup user32 = java.lang.foreign.SymbolLookup.libraryLookup("user32", java.lang.foreign.Arena.global());
				keybdEvent = linker.downcallHandle(user32.find("keybd_event").orElseThrow(),
						java.lang.foreign.FunctionDescriptor.ofVoid(java.lang.foreign.ValueLayout.JAVA_BYTE, java.lang.foreign.ValueLayout.JAVA_BYTE,
								java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_LONG));
			} catch (Throwable t) {
				keybdEvent = null;
			}
		}
		if (keybdEvent == null) {
			return;
		}
		try {
			keybdEvent.invokeExact((byte) vk, (byte) 0, KEYEVENTF_EXTENDEDKEY, 0L);
			keybdEvent.invokeExact((byte) vk, (byte) 0, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP, 0L);
		} catch (Throwable ignored) {
			// nothing to fall back to
		}
		*///?} else {
		try {
			new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
					"(New-Object -ComObject WScript.Shell).SendKeys([char]" + vk + ")")
					.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception ignored) {
			// no PowerShell
		}
		//?}
	}
}
