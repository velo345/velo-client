package net.veloclient.velo.client.modules.debug;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * F3-style extended info panel for whatever block or entity is currently
 * targeted - registry id, block-entity NBT, entity id/UUID (design spec
 * section 6.4). All of this is data the client already has client-side to
 * render the target; nothing is queried beyond it.
 */
public final class LookingAtInspectorModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.3f, 0.4f);

	public LookingAtInspectorModule() {
		super("looking-at-inspector", "Block/Entity Inspector",
				"Shows extended info (registry id, NBT, UUID) for the block or entity you're looking at.",
				ModuleCategory.DEBUG, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		HitResult target = client.crosshairTarget;
		if (world == null || target == null) {
			return;
		}
		int lineHeight = client.textRenderer.fontHeight + 1;
		int rowY = y;

		if (target.getType() == HitResult.Type.BLOCK && target instanceof BlockHitResult blockHit) {
			BlockState state = world.getBlockState(blockHit.getBlockPos());
			String id = Registries.BLOCK.getId(state.getBlock()).toString();
			rowY = drawLine(context, x, rowY, lineHeight, "Block: " + id);
			rowY = drawLine(context, x, rowY, lineHeight, "Pos: " + blockHit.getBlockPos().toShortString());
			BlockEntity blockEntity = world.getBlockEntity(blockHit.getBlockPos());
			if (blockEntity != null) {
				String nbt = blockEntity.createNbt(world.getRegistryManager()).toString();
				if (nbt.length() > 120) {
					nbt = nbt.substring(0, 120) + "...";
				}
				drawLine(context, x, rowY, lineHeight, "NBT: " + nbt);
			}
		} else if (target.getType() == HitResult.Type.ENTITY && target instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			rowY = drawLine(context, x, rowY, lineHeight, "Entity: " + Registries.ENTITY_TYPE.getId(entity.getType()));
			drawLine(context, x, rowY, lineHeight, "UUID: " + entity.getUuid());
		}
	}

	private int drawLine(DrawContext context, int x, int y, int lineHeight, String text) {
		MinecraftClient client = MinecraftClient.getInstance();
		context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFFFFFFFF);
		return y + lineHeight;
	}

	@Override
	public int width() {
		return 300;
	}

	@Override
	public int height() {
		return 3 * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}
}
