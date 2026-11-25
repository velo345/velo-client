package net.veloclient.velo.client.util;

import net.minecraft.client.gui.DrawContext;

/** The text (I-beam) mouse cursor over text inputs Velo draws itself (1.21.10+; 1.21.1 has no cursor API). */
public final class IBeamCursor {

	private IBeamCursor() {
	}

	public static void ibeam(DrawContext context) {
		//? if >=1.21.10 && <26.1 {
		context.setCursor(net.minecraft.client.gui.cursor.StandardCursors.IBEAM);
		//?} else if >=26.1 {
		/*context.requestCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.IBEAM);
		*///?}
	}
}
