package net.veloclient.velo.client.pvp;

import net.veloclient.velo.client.modules.pvp.OldAnimationsModule;

/**
 * The first-person pose changes for Old Animations, applied by {@code OldAnimationsHandMixin} right
 * after vanilla's own arm transforms (so they're local to the held item, like 1.8 did them).
 */
public final class OldAnimTransforms {

	/** The item and hand currently being drawn in first person (render thread only). */
	public static Object currentStack;
	public static boolean currentIsMainHand;

	private OldAnimTransforms() {
	}

	/** After the equip transform: old rod/bow pose, then Item Position. {@code pose} is a MatrixStack/PoseStack. */
	public static void afterEquip(Object pose, boolean rightArm) {
		float side = rightArm ? 1f : -1f;
		float[] old = OldAnimationsModule.oldItemPose(currentStack);
		if (old != null) {
			translate(pose, old[0] * side, old[1], old[2]);
			rotateY(pose, old[3] * side);
		}
		float[] custom = net.veloclient.velo.client.modules.pvp.ItemPositionModule.transform(currentIsMainHand, currentStack);
		if (custom != null) {
			translate(pose, custom[0] * side, custom[1], custom[2]);
			if (custom[4] != 0f) {
				rotateX(pose, custom[4]);
			}
			if (custom[5] != 0f) {
				rotateY(pose, custom[5] * side);
			}
			if (custom[6] != 0f) {
				rotateZ(pose, custom[6] * side);
			}
			if (custom[3] != 1f) {
				scale(pose, custom[3]);
			}
		}
	}

	/** After the swing transform: the 1.7 sword-block pose (swinging on top of it is the block-hit look). */
	public static void afterSwing(Object pose, boolean rightArm) {
		if (!currentIsMainHand || !OldAnimationsModule.swordBlocking()) {
			return;
		}
		float side = rightArm ? 1f : -1f;
		translate(pose, side * -0.14142136f, 0.08f, 0.14142136f);
		rotateX(pose, -102.25f);
		rotateY(pose, side * 13.365f);
		rotateZ(pose, side * 78.05f);
	}

	private static void translate(Object pose, float x, float y, float z) {
		//? if <26.1 {
		((net.minecraft.client.util.math.MatrixStack) pose).translate(x, y, z);
		//?} else {
		/*((com.mojang.blaze3d.vertex.PoseStack) pose).translate(x, y, z);
		*///?}
	}

	private static void scale(Object pose, float s) {
		//? if <26.1 {
		((net.minecraft.client.util.math.MatrixStack) pose).scale(s, s, s);
		//?} else {
		/*((com.mojang.blaze3d.vertex.PoseStack) pose).scale(s, s, s);
		*///?}
	}

	private static void rotateX(Object pose, float degrees) {
		//? if <26.1 {
		((net.minecraft.client.util.math.MatrixStack) pose).multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(degrees));
		//?} else if <26.3 {
		/*((com.mojang.blaze3d.vertex.PoseStack) pose).mulPose(com.mojang.math.Axis.XP.rotationDegrees(degrees));
		*///?} else {
		/*((com.mojang.blaze3d.vertex.PoseStack) pose).rotate(com.mojang.math.Axis.XP.rotationDegrees(degrees));
		*///?}
	}

	private static void rotateY(Object pose, float degrees) {
		//? if <26.1 {
		((net.minecraft.client.util.math.MatrixStack) pose).multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(degrees));
		//?} else if <26.3 {
		/*((com.mojang.blaze3d.vertex.PoseStack) pose).mulPose(com.mojang.math.Axis.YP.rotationDegrees(degrees));
		*///?} else {
		/*((com.mojang.blaze3d.vertex.PoseStack) pose).rotate(com.mojang.math.Axis.YP.rotationDegrees(degrees));
		*///?}
	}

	private static void rotateZ(Object pose, float degrees) {
		//? if <26.1 {
		((net.minecraft.client.util.math.MatrixStack) pose).multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(degrees));
		//?} else if <26.3 {
		/*((com.mojang.blaze3d.vertex.PoseStack) pose).mulPose(com.mojang.math.Axis.ZP.rotationDegrees(degrees));
		*///?} else {
		/*((com.mojang.blaze3d.vertex.PoseStack) pose).rotate(com.mojang.math.Axis.ZP.rotationDegrees(degrees));
		*///?}
	}
}
