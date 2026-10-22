package net.veloclient.velo.client.gui.widget;

import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.skins.SkinTryOn;

/**
 * Your player as a bare render state, for 3D previews where there's no world yet (the title screen's Locker):
 * your skin - or the one the Locker is trying on - with your Velo cape. The wings and aura layers recognise it
 * by its id and draw what you wear, so the preview matches the in-game one.
 */
public final class MenuAvatar {

	/** The render state id of the menu avatar (never a real entity id). */
	public static final int ID = -1_337_042;

	private MenuAvatar() {
	}

	/** The avatar's height in blocks (a standing player). */
	public static final float HEIGHT = 1.8f;

	public static boolean is(int stateId) {
		return stateId == ID;
	}

	private static float ageTicks() {
		return (System.currentTimeMillis() % 3_600_000L) / 50f;
	}

	//? if <26.1 {
	public static net.minecraft.client.render.entity.state.PlayerEntityRenderState build() {
		var state = new net.minecraft.client.render.entity.state.PlayerEntityRenderState();
		state.entityType = net.minecraft.entity.EntityType.PLAYER;
		state.id = ID;
		state.skinTextures = skin();
		state.capeVisible = true;
		state.width = 0.6f;
		state.height = 1.8f;
		state.standingEyeHeight = 1.62f;
		state.age = ageTicks();
		state.light = 0xF000F0;
		return state;
	}

	private static net.minecraft.entity.player.SkinTextures skin() {
		net.minecraft.entity.player.SkinTextures base = SkinModelPreview.current();
		net.minecraft.util.Identifier tryOn = SkinTryOn.texture();
		net.minecraft.util.AssetInfo.TextureAsset body = tryOn == null ? base.body() : new net.minecraft.util.AssetInfo.TextureAssetInfo(tryOn, tryOn);
		net.minecraft.entity.player.PlayerSkinType model = tryOn == null ? base.model()
				: SkinTryOn.slim() ? net.minecraft.entity.player.PlayerSkinType.SLIM : net.minecraft.entity.player.PlayerSkinType.WIDE;
		net.minecraft.util.AssetInfo.TextureAsset cape = base.cape();
		var definition = CapeManager.renderCape();
		if (definition.isPresent()) {
			net.minecraft.util.Identifier texture = CapeManager.textureIdentifier(definition.get());
			cape = new net.minecraft.util.AssetInfo.TextureAssetInfo(texture, texture);
		}
		return new net.minecraft.entity.player.SkinTextures(body, cape, base.elytra(), model, base.secure());
	}
	//?} else {
	/*public static net.minecraft.client.renderer.entity.state.AvatarRenderState build() {
		var state = new net.minecraft.client.renderer.entity.state.AvatarRenderState();
		// (Avatar renderers are picked by the skin's model, no entity type needed; the bounding box fields' names
		// clash with a Stonecutter rule - the preview passes the height itself.)
		state.id = ID;
		state.skin = skin();
		state.showCape = true;
		state.eyeHeight = 1.62f;
		state.ageInTicks = ageTicks();
		state.lightCoords = 0xF000F0;
		return state;
	}

	private static net.minecraft.world.entity.player.PlayerSkin skin() {
		net.minecraft.world.entity.player.PlayerSkin base = SkinModelPreview.current();
		net.minecraft.resources.Identifier tryOn = SkinTryOn.texture();
		net.minecraft.core.ClientAsset.Texture body = tryOn == null ? base.body() : new net.minecraft.core.ClientAsset.ResourceTexture(tryOn, tryOn);
		net.minecraft.world.entity.player.PlayerModelType model = tryOn == null ? base.model()
				: SkinTryOn.slim() ? net.minecraft.world.entity.player.PlayerModelType.SLIM : net.minecraft.world.entity.player.PlayerModelType.WIDE;
		net.minecraft.core.ClientAsset.Texture cape = base.cape();
		var definition = CapeManager.renderCape();
		if (definition.isPresent()) {
			net.minecraft.resources.Identifier texture = CapeManager.textureIdentifier(definition.get());
			cape = new net.minecraft.core.ClientAsset.ResourceTexture(texture, texture);
		}
		return new net.minecraft.world.entity.player.PlayerSkin(body, cape, base.elytra(), model, base.secure());
	}
	*///?}
}
