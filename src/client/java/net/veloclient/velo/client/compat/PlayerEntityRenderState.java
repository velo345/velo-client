package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.math.MathHelper;

/^*
 * 1.21.1 has no entity render states (1.21.2): the few fields Velo's cosmetics renderers read, filled from the player
 * at render time, so the same renderer code runs on both.
 ^/
public class PlayerEntityRenderState {
	public int id;
	public float bodyYaw;
	public double x;
	public double y;
	public double z;
	public boolean invisible;
	public int outlineColor;
	public double squaredDistanceToCamera;
	public SkinTextures skinTextures;

	public static PlayerEntityRenderState of(AbstractClientPlayerEntity player, float tickDelta) {
		PlayerEntityRenderState s = new PlayerEntityRenderState();
		s.id = player.getId();
		s.bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, player.prevBodyYaw, player.bodyYaw);
		s.x = MathHelper.lerp(tickDelta, player.prevX, player.getX());
		s.y = MathHelper.lerp(tickDelta, player.prevY, player.getY());
		s.z = MathHelper.lerp(tickDelta, player.prevZ, player.getZ());
		var self = MinecraftClient.getInstance().player;
		s.invisible = player.isInvisible() && (self == null || player.isInvisibleTo(self));
		s.outlineColor = 0;
		var camera = MinecraftClient.getInstance().gameRenderer.getCamera();
		s.squaredDistanceToCamera = camera == null ? 0 : player.squaredDistanceTo(camera.getPos());
		s.skinTextures = player.getSkinTextures();
		return s;
	}
}
*///?}
