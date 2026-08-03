package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
*///?}
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.WaveyCapesCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports the equipped Velo cape (and its bundled elytra texture, if it has
 * one - see {@link CapeManager#elytraTextureIdentifier}) as the local
 * player's own vanilla skin textures.
 *
 * <p>The cape field is only ever touched while Wavey Capes ({@link
 * WaveyCapesCompat}) is installed - it has no other way to discover a
 * cosmetic cape from a mod it doesn't know about, and its own fallback
 * renderer just reads this same accessor (confirmed by reading its real,
 * decompiled source). {@link net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer}
 * skips registering its own render layer whenever Wavey Capes is present, so
 * this is the only place a Velo cape gets attached to the local player in
 * that case - the rest of the time (Wavey Capes absent) Velo's own
 * ClothSimulator-driven render layer handles the cape directly instead.
 *
 * <p>The elytra field, unlike the cape one, is touched unconditionally
 * (Wavey Capes or not) - real elytra rendering is 100% vanilla's own system
 * either way (confirmed via the real {@code equipment/elytra.json} asset,
 * which already supports {@code "use_player_texture": true"} - the exact
 * same per-player-skin mechanism this mixin hooks for the cape), so there's
 * nothing Wavey-Capes-specific about it at all.
 */
//? if <26.1 {
@Mixin(AbstractClientPlayerEntity.class)
public abstract class LocalPlayerSkinCapeMixin {

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void velo$overrideCapeAndElytra(CallbackInfoReturnable<SkinTextures> cir) {
		if ((Object) this != MinecraftClient.getInstance().player) {
			return;
		}
		var equipped = CapeManager.equipped();
		if (equipped.isEmpty()) {
			return;
		}
		CapeDefinition definition = equipped.get();
		boolean overrideCape = WaveyCapesCompat.isLoaded();
		var elytraTexture = CapeManager.elytraTextureIdentifier(definition);
		if (!overrideCape && elytraTexture.isEmpty()) {
			return;
		}
		SkinTextures original = cir.getReturnValue();
		AssetInfo.TextureAsset capeAsset = original.cape();
		if (overrideCape) {
			// The single-arg TextureAssetInfo(Identifier) constructor does
			// NOT reuse that identifier as the texturePath - it derives a
			// DIFFERENT one ("textures/" + path + ".png", confirmed via
			// javap), meant for real resource-pack-backed assets. Our cape
			// is dynamically registered directly under `texture` via
			// TextureManager, so that derived path never resolves - real
			// bug, rendered as the black/purple missing-texture pattern.
			// The two-arg constructor with the SAME identifier for both id
			// and texturePath is what actually binds our registered
			// texture.
			Identifier capeTexture = CapeManager.textureIdentifier(definition);
			capeAsset = new AssetInfo.TextureAssetInfo(capeTexture, capeTexture);
		}
		AssetInfo.TextureAsset elytraAsset = original.elytra();
		if (elytraTexture.isPresent()) {
			Identifier id = elytraTexture.get();
			elytraAsset = new AssetInfo.TextureAssetInfo(id, id);
		}
		cir.setReturnValue(new SkinTextures(original.body(), capeAsset, elytraAsset, original.model(), original.secure()));
	}
}
//?} else {
/*@Mixin(AbstractClientPlayer.class)
public abstract class LocalPlayerSkinCapeMixin {

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void velo$overrideCapeAndElytra(CallbackInfoReturnable<PlayerSkin> cir) {
		if ((Object) this != Minecraft.getInstance().player) {
			return;
		}
		var equipped = CapeManager.equipped();
		if (equipped.isEmpty()) {
			return;
		}
		CapeDefinition definition = equipped.get();
		boolean overrideCape = WaveyCapesCompat.isLoaded();
		var elytraTexture = CapeManager.elytraTextureIdentifier(definition);
		if (!overrideCape && elytraTexture.isEmpty()) {
			return;
		}
		PlayerSkin original = cir.getReturnValue();
		ClientAsset.Texture capeAsset = original.cape();
		if (overrideCape) {
			// Same real bug/fix as the 1.21.11 branch above: the single-arg
			// ResourceTexture(Identifier) constructor derives a DIFFERENT
			// texturePath ("textures/" + path + ".png") instead of reusing
			// the identifier, which never resolves for our
			// dynamically-registered texture. Two-arg constructor, same
			// identifier twice.
			Identifier capeTexture = CapeManager.textureIdentifier(definition);
			capeAsset = new ClientAsset.ResourceTexture(capeTexture, capeTexture);
		}
		ClientAsset.Texture elytraAsset = original.elytra();
		if (elytraTexture.isPresent()) {
			Identifier id = elytraTexture.get();
			elytraAsset = new ClientAsset.ResourceTexture(id, id);
		}
		cir.setReturnValue(new PlayerSkin(original.body(), capeAsset, elytraAsset, original.model(), original.secure()));
	}
}
*///?}
