package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.world.phys.Vec3;
import net.veloclient.velo.client.modules.rendering.VeloShaderLights;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import net.veloclient.velo.client.modules.rendering.VeloShadowMap;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Unique;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;
*///?}

/**
 * Velo Shaders: grows vanilla's Globals uniform block (bound to terrain, sky, post and most other
 * pipelines) by {@link VeloShadersModule#GLOBALS_EXTRA} bytes and fills them every frame - sun
 * direction, camera position, time, sky colour, rain and the packed settings. Shaders that declare
 * the shorter vanilla block are unaffected (std140 lets a buffer be larger than the block).
 */
//? if >=26.2 {
/*@Mixin(GlobalSettingsUniform.class)
public abstract class VeloShadersGlobalsMixin {

	@Unique
	private static final float[] VELO_INFO = new float[16];

	@ModifyExpressionValue(method = {"<init>", "update"}, at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;UBO_SIZE:I", opcode = Opcodes.GETSTATIC))
	private int velo$growBlock(int vanilla) {
		return ((vanilla + 15) & ~15) + VeloShadersModule.GLOBALS_EXTRA;
	}

	@WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;get()Ljava/nio/ByteBuffer;"))
	private ByteBuffer velo$appendShaderData(Std140Builder builder, Operation<ByteBuffer> original, @Local(argsOnly = true) Vec3 camera) {
		if (!VeloShadersModule.active()) {
			// Shaders off: no per-frame work at all (vanilla's shaders only read their own part of the block).
			VeloShadowMap.valid = false;
			return original.call(builder);
		}
		var level = net.minecraft.client.Minecraft.getInstance().level;
		float rain = level == null ? 0f : level.getRainLevel(1f);
		VeloShadersModule.writeGlobals(VeloShadersModule.FRAME_FLOATS, VeloShadersModule.FRAME_PACKS, camera.x, camera.y, camera.z, rain);
		float[] f = VeloShadersModule.FRAME_FLOATS;
		int[] p = VeloShadersModule.FRAME_PACKS;
		builder.putVec4(f[0], f[1], f[2], f[3]).putVec4(f[4], f[5], f[6], f[7]).putVec4(f[8], f[9], f[10], f[11])
				.putIVec4(p[0], p[1], p[2], p[3]).putIVec4(p[4], p[5], p[6], p[7]).putIVec4(p[8], p[9], p[10], p[11])
				.putIVec4(p[12], p[13], p[14], p[15]);
		// Camera matrices for the post lighting pass (rebuilding positions from depth). The camera
		// state is already extracted for this frame when Globals is written.
		var cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Matrix4f proj = cam == null || cam.projectionMatrix == null ? new Matrix4f() : cam.projectionMatrix;
		Matrix4f view = cam == null || cam.viewRotationMatrix == null ? new Matrix4f() : cam.viewRotationMatrix;
		builder.putMat4f(proj).putMat4f(new Matrix4f(proj).invert()).putMat4f(view).putMat4f(new Matrix4f(view).invert());
		boolean zZeroToOne = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		// The sun camera for this frame's shadow pass (sun by day, moon by night).
		float[] f2 = VeloShadersModule.FRAME_FLOATS;
		float sign = f2[1] > -0.08f ? 1f : -1f;
		VeloShadowMap.update(f2[0] * sign, f2[1] * sign, f2[2] * sign, camera.x, camera.y, camera.z, zZeroToOne,
				net.minecraft.client.Minecraft.getInstance().getFps());
		builder.putMat4f(VeloShadowMap.SHADER);
		float[] info = VELO_INFO;
		VeloShadersModule.writeLightInfo(info, camera.x, camera.y, camera.z, zZeroToOne);
		// VeloLightInfo3.z: what the camera is in (1 water, 2 lava, 3 powder snow) for the underwater look.
		var fog = cam == null ? null : cam.fogType;
		info[10] = !VeloShadersModule.active() || fog == null ? 0f : fog == net.minecraft.world.level.material.FogType.WATER ? 1f
				: fog == net.minecraft.world.level.material.FogType.LAVA ? 2f : fog == net.minecraft.world.level.material.FogType.POWDER_SNOW ? 3f : 0f;
		builder.putVec4(info[0], info[1], info[2], info[3]).putVec4(info[4], info[5], info[6], info[7]).putVec4(info[8], info[9], info[10], info[11])
				.putVec4(info[12], info[13], info[14], info[15]);
		float[] l = VeloShadersModule.FRAME_LIGHTS;
		int count = (int) info[0];
		for (int i = 0; i < VeloShaderLights.MAX; i++) {
			if (i < count) {
				int o = i * 8;
				builder.putVec4(l[o], l[o + 1], l[o + 2], l[o + 3]).putVec4(l[o + 4], l[o + 5], l[o + 6], l[o + 7]);
			} else {
				builder.putVec4(0f, 0f, 0f, 0f).putVec4(0f, 0f, 0f, 0f);
			}
		}
		float[] ents = VeloShadersModule.FRAME_ENTITIES;
		for (int i = 0; i < VeloShaderLights.MAX_ENTITIES; i++) {
			if (i < VeloShadersModule.frameEntityCount) {
				int o = i * 8;
				builder.putVec4(ents[o], ents[o + 1], ents[o + 2], ents[o + 3]).putVec4(ents[o + 4], 0f, 0f, 0f);
			} else {
				builder.putVec4(0f, 0f, 0f, 0f).putVec4(0f, 0f, 0f, 0f);
			}
		}
		builder.putMat4f(VeloShadowMap.NEAR_SHADOW);
		return original.call(builder);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadersGlobalsMixin {
	// Velo Shaders: 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
