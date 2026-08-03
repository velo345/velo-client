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
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.WaveyCapesCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports the equipped Velo cape as the local player's own vanilla cape
 * texture, but only while Wavey Capes ({@link WaveyCapesCompat}) is
 * installed - it has no other way to discover a cosmetic cape from a mod it
 * doesn't know about, and its own fallback renderer just reads this same
 * accessor (confirmed by reading its real, decompiled source). {@link
 * net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer} skips
 * registering its own render layer whenever Wavey Capes is present, so this
 * is the only place a Velo cape gets attached to the local player in that
 * case - the rest of the time (Wavey Capes absent) this mixin is a no-op and
 * Velo's own ClothSimulator-driven render layer handles everything as usual.
 */
//? if <26.1 {
@Mixin(AbstractClientPlayerEntity.class)
public abstract class LocalPlayerSkinCapeMixin {

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void velo$overrideCapeForWaveyCapes(CallbackInfoReturnable<SkinTextures> cir) {
		if (!WaveyCapesCompat.isLoaded()) {
			return;
		}
		var equipped = CapeManager.equipped();
		if (equipped.isEmpty() || (Object) this != MinecraftClient.getInstance().player) {
			return;
		}
		Identifier texture = CapeManager.textureIdentifier(equipped.get());
		SkinTextures original = cir.getReturnValue();
		cir.setReturnValue(new SkinTextures(original.body(), new AssetInfo.TextureAssetInfo(texture),
				original.elytra(), original.model(), original.secure()));
	}
}
//?} else {
/*@Mixin(AbstractClientPlayer.class)
public abstract class LocalPlayerSkinCapeMixin {

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void velo$overrideCapeForWaveyCapes(CallbackInfoReturnable<PlayerSkin> cir) {
		if (!WaveyCapesCompat.isLoaded()) {
			return;
		}
		var equipped = CapeManager.equipped();
		if (equipped.isEmpty() || (Object) this != Minecraft.getInstance().player) {
			return;
		}
		Identifier texture = CapeManager.textureIdentifier(equipped.get());
		PlayerSkin original = cir.getReturnValue();
		cir.setReturnValue(new PlayerSkin(original.body(), new ClientAsset.ResourceTexture(texture),
				original.elytra(), original.model(), original.secure()));
	}
}
*///?}
