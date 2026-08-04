package net.veloclient.velo.client.gui;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * The small "which client is this player using" badge shown next to the
 * local player's own name - tab list ({@link net.veloclient.velo.client.mixin.PlayerListHudMixin}),
 * chat ({@link net.veloclient.velo.client.mixin.ChatHudMixin}), and the
 * in-world nametag above their own head ({@link net.veloclient.velo.client.mixin.PlayerNameTagBadgeMixin})
 * all draw the exact same icon through here, same as Lunar/NoRisk-style
 * clients. Drawn from the mod's own 500x500 logo asset (already used for the
 * title-screen watermark, see {@link VeloBranding}) rather than a
 * separately-shipped tiny icon, since sampling a real high-resolution source
 * down to badge size looks meaningfully sharper than a source image that was
 * already only badge-sized to begin with.
 *
 * <p>Purely a local cosmetic, exactly like the cape/crosshair systems - never
 * sent to the server, and nobody else running Velo Client (or not) sees it on
 * this client's player, since there's no cosmetic-sync backend.
 *
 * <p>The chat variant ({@link #prefixWithBadge}) can't just draw an icon at
 * render time like the other two - the visible chat text is pre-formatted
 * Text the server sends back, not something rendered from separately-known
 * name/message parts this mod controls, so there's no isolated "draw the
 * name" call to hook the way {@code PlayerListHudMixin} does. Instead it
 * prepends a real glyph from a custom bitmap font (see {@code
 * assets/velo-client/font/badge.json}, built from this same logo) styled
 * onto a private-use-area character, which then just rides along through
 * Minecraft's normal text rendering - no separate render hook needed there.
 */
public final class VeloBadge {

	// Tab list rows are only ~9px tall (font height) - 11px visibly
	// overflowed the row. 6px sits cleanly inside it without looking
	// pixelated, since this is still sampled down from the real 500x500
	// source texture rather than a small pre-shrunk one. PlayerListHudMixin
	// centers it against the actual row height using this constant, so it
	// stays centered at any size.
	public static final int SIZE = 6;

	private static final Identifier LOGO_TEXTURE = Identifier.of("velo-client", "textures/icon/logo.png");
	private static final int LOGO_SOURCE_SIZE = 500;
	// A private-use-area codepoint (U+E050), matched exactly by the
	// "chars" entry in assets/velo-client/font/badge.json's bitmap
	// provider.
	private static final String GLYPH = "";

	private VeloBadge() {
	}

	/**
	 * True if {@code text} contains {@code ownName} as a genuine, whole
	 * username - not just as a substring of some other, longer name. A
	 * plain {@code String#contains} check wrongly matched "r4wwrrr" inside
	 * a completely different player's name "r4wwrrrr", badging both of them
	 * in the tab list - Minecraft usernames are restricted to
	 * {@code [a-zA-Z0-9_]}, so this rejects a match unless the characters
	 * immediately before/after it (if any) fall outside that set.
	 */
	public static boolean isOwnName(String text, String ownName) {
		int index = text.indexOf(ownName);
		if (index < 0) {
			return false;
		}
		boolean boundaryBefore = index == 0 || !isNameChar(text.charAt(index - 1));
		int after = index + ownName.length();
		boolean boundaryAfter = after >= text.length() || !isNameChar(text.charAt(after));
		return boundaryBefore && boundaryAfter;
	}

	private static boolean isNameChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	/** Draws the badge at {@code (x, y)} on-screen, {@link #SIZE} pixels square. */
	public static void draw(DrawContext context, int x, int y) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, x, y, 0f, 0f,
				SIZE, SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE);
	}

	/**
	 * Prepends the badge glyph (custom bitmap font, see class doc) to a chat
	 * line or nametag.
	 *
	 * <p>The glyph and {@code original} are added as two independent
	 * children of a neutral empty root, NOT as {@code glyph.append(original)}
	 * directly - Minecraft's real text style resolution has the badge's
	 * custom-font style cascade down into any component nested inside it
	 * (the same mechanism that makes {@code Text.literal("a").formatted(RED)
	 * .append("b")} render "b" in red too), and the badge font has no glyphs
	 * defined for anything but the one private-use character - so
	 * {@code original} inherited it and rendered as unreadable
	 * missing-glyph boxes for every real letter. As two siblings under a
	 * plain, unstyled root instead of one being the other's ancestor, only
	 * the glyph itself resolves the custom font - {@code original} falls
	 * through to the normal default one, untouched.
	 */
	public static Text prefixWithBadge(Text original) {
		Identifier fontId = Identifier.of("velo-client", "badge");
		//? if <26.1 {
		Style badgeStyle = Style.EMPTY.withFont(new net.minecraft.text.StyleSpriteSource.Font(fontId));
		//?} else {
		/*Style badgeStyle = Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(fontId));
		*///?}
		MutableText result = Text.empty();
		result.append(Text.literal(GLYPH).setStyle(badgeStyle));
		result.append(original);
		return result;
	}
}
