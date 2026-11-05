package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*import net.minecraft.text.Style;
import net.minecraft.util.Identifier;

/^* 1.21.1 stand-in for 1.21.9's font reference: in 1.21.1 a Style's font is just an Identifier. ^/
public sealed interface StyleSpriteSource permits StyleSpriteSource.Font, StyleSpriteSource.Default {

	StyleSpriteSource DEFAULT = new Default();

	record Font(Identifier id) implements StyleSpriteSource {
	}

	final class Default implements StyleSpriteSource {
		private Default() {
		}
	}

	static StyleSpriteSource of(Identifier id) {
		return id == null || id.equals(Style.DEFAULT_FONT_ID) ? DEFAULT : new Font(id);
	}

	static Identifier id(StyleSpriteSource source) {
		return source instanceof Font font ? font.id() : Style.DEFAULT_FONT_ID;
	}
}
*///?}
