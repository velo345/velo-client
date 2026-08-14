package net.veloclient.velo.client.gui.widget;

import net.minecraft.util.Identifier;

/**
 * Lucide-derived sidebar navigation icons (see NOTICE.md) - same white-on-
 * transparent, tint-at-draw-time convention as {@link ModuleIcons}, just
 * under {@code textures/icon/nav/} instead of {@code textures/icon/module/}.
 */
public final class VeloNavIcons {

	private VeloNavIcons() {
	}

	public static Identifier of(String key) {
		return Identifier.of("velo-client", "textures/icon/nav/" + key + ".png");
	}
}
