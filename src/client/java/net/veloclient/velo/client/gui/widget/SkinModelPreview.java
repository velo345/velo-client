package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ClickableWidget;
//? if <26.1 {
import net.minecraft.client.gui.widget.PlayerSkinWidget;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
//?} else {
/*import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
*///?}

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A drag-to-rotate 3D player model for any skin texture - vanilla's own {@code PlayerSkinWidget}
 * (the one from the Realms/skin screens), so it works on the title screen too, no world needed.
 * Textures must be 64x64 (convert legacy 64x32 skins first).
 */
public final class SkinModelPreview {

	private SkinModelPreview() {
	}

	//? if <26.1 {
	private static java.util.function.Supplier<SkinTextures> current;

	private static SkinTextures currentSkin() {
		if (current == null) {
			MinecraftClient client = MinecraftClient.getInstance();
			current = client.getSkinProvider().supplySkinTextures(client.getGameProfile(), false);
		}
		return current.get();
	}

	/** Texture of the skin your account has right now (Mojang's copy; the default skin until it's loaded). */
	public static Identifier currentTexture() {
		return currentSkin().body().texturePath();
	}

	public static boolean currentSlim() {
		return currentSkin().model() == PlayerSkinType.SLIM;
	}
	//?} else {
	/*private static java.util.function.Supplier<PlayerSkin> current;

	private static PlayerSkin currentSkin() {
		if (current == null) {
			MinecraftClient client = MinecraftClient.getInstance();
			current = client.getSkinManager().createLookup(client.getGameProfile(), false);
		}
		return current.get();
	}

	public static Identifier currentTexture() {
		return currentSkin().body().texturePath();
	}

	public static boolean currentSlim() {
		return currentSkin().model() == PlayerModelType.SLIM;
	}
	*///?}

	/** Forget the cached current skin (after equipping a new one), so it's fetched again. */
	public static void refreshCurrent() {
		current = null;
	}

	/** {@code texture} returning null shows the skin your account has right now. */
	public static ClickableWidget create(int width, int height, Supplier<Identifier> texture, BooleanSupplier slim) {
		//? if <26.1 {
		return new PlayerSkinWidget(width, height, MinecraftClient.getInstance().getLoadedEntityModels(), () -> {
			Identifier id = texture.get();
			if (id == null) {
				return currentSkin();
			}
			return SkinTextures.create(new AssetInfo.TextureAssetInfo(id, id), null, null, slim.getAsBoolean() ? PlayerSkinType.SLIM : PlayerSkinType.WIDE);
		});
		//?} else {
		/*return new PlayerSkinWidget(width, height, MinecraftClient.getInstance().getEntityModels(), () -> {
			Identifier id = texture.get();
			if (id == null) {
				return currentSkin();
			}
			return PlayerSkin.insecure(new ClientAsset.ResourceTexture(id, id), null, null, slim.getAsBoolean() ? PlayerModelType.SLIM : PlayerModelType.WIDE);
		});
		*///?}
	}
}
