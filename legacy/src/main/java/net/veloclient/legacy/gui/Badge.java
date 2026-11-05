package net.veloclient.legacy.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.resource.Identifier;
import net.veloclient.legacy.cape.Capes;
import net.veloclient.legacy.net.VeloUsers;

import java.util.UUID;

/** The Velo logo in front of Velo users' names (the Velo+ one has the red plus); you always get yours. */
public final class Badge {

	private static final Identifier LOGO = new Identifier("velo-client", "textures/gui/badge.png");
	private static final Identifier PLUS = new Identifier("velo-client", "textures/gui/badge_plus.png");
	public static final int SIZE = 7;

	private Badge() {
	}

	/** 0 = no badge, 1 = Velo, 2 = Velo+. */
	public static int kind(UUID uuid, String name) {
		VeloUsers.Entry entry = VeloUsers.get(uuid, name);
		if (entry != null) {
			return entry.plus() ? 2 : 1;
		}
		String own = Capes.ownUuid();
		return own != null && uuid != null && own.equals(uuid.toString().replace("-", "")) ? 1 : 0;
	}

	public static int width(int kind) {
		return kind == 2 ? SIZE * 3 / 2 : SIZE;
	}

	/** Spaces that make room for the badge in front of a name. */
	public static String padding(int kind) {
		int need = width(kind) + 1;
		StringBuilder pad = new StringBuilder();
		int space = Math.max(1, Minecraft.getInstance().textRenderer.getWidth(" "));
		for (int w = 0; w < need; w += space) {
			pad.append(' ');
		}
		return pad.toString();
	}

	/** Draws the badge with its top left at x/y (GUI coordinates or a name tag's local space). */
	public static void draw(int kind, int x, int y) {
		GlStateManager.enableTexture();
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(770, 771, 1, 0);
		GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		Minecraft.getInstance().getTextureManager().bind(kind == 2 ? PLUS : LOGO);
		if (kind == 2) {
			GuiElement.drawTexture(x, y, 0.0F, 0.0F, 48, 32, width(2), SIZE, 48.0F, 32.0F);
		} else {
			GuiElement.drawTexture(x, y, 0.0F, 0.0F, 32, 32, SIZE, SIZE, 32.0F, 32.0F);
		}
	}
}
