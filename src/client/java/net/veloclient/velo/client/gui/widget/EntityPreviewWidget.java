package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
//? if <26.1 {
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.EntityPose;
//?} else {
/*import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Pose;
*///?}
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Live 3D preview of the local player inside a GUI panel - the Store's
 * "try before you buy" view (design spec's cosmetics store). Renders the
 * real equipped gear, skin, and - whichever cape {@link
 * net.veloclient.velo.client.cosmetics.CapeManager}'s preview override
 * currently points at - through vanilla's own entity renderer (including
 * Wavey Capes' fallback and this mod's own {@code CapeFeatureRenderer}), so
 * everything looks exactly as it would in-world, animation included.
 *
 * <p>This does *not* go through vanilla's own {@code InventoryScreen}
 * preview helper ({@code drawEntity}/{@code
 * extractEntityInInventoryFollowsMouse}) - real bytecode-level inspection
 * (this codebase has no decompiled Minecraft source available, so this was
 * read directly from the compiled class, not guessed) showed that helper
 * computes its "look at cursor" body yaw as {@code atan((centerX - mouseX)
 * / 40) * 20 degrees} - and since {@code atan} asymptotically caps at ±90°
 * *no matter how large its input gets*, the resulting yaw can never exceed
 * roughly ±31°, confirmed by real in-game testing (topped out around 45°,
 * the same ballpark) - not far enough to ever show the back of the player,
 * where a cape actually is, regardless of how far you drag. So this
 * replicates that helper's own internals directly (same public APIs it
 * itself calls under the hood: {@code EntityRenderDispatcher.getRenderer}
 * + {@code EntityRenderer.getAndUpdateRenderState} to build the render
 * state, {@code DrawContext.addEntity} to actually draw it) but with a
 * plain, *uncapped* body yaw driven by drag distance instead of that
 * bounded arctangent - a full turn is just a longer drag, same feel as the
 * launcher's own free-spinning viewer.
 *
 * <p>Requires a real {@link PlayerEntity} to render at all, which only
 * exists once actually in a world - opened from the title screen (before
 * any world is joined), {@code client.player} is null and there is nothing
 * to preview yet, so this shows an explanatory message instead of a blank
 * panel.
 */
public final class EntityPreviewWidget extends ClickableWidget {

	private static final float DEGREES_PER_DRAG_PIXEL = 0.6f;
	private static final float MIN_ZOOM = 0.6f;
	private static final float MAX_ZOOM = 2.2f;

	/** 180 by default so the cape - the entire point of this preview - is what you see first, not vanilla's own front-facing default. */
	private float yawDegrees = 180f;
	private float zoom = 1f;
	private double lastDragX;
	private boolean dragging;

	public EntityPreviewWidget(int x, int y, int width, int height) {
		super(x, y, width, height, Text.literal("Preview"));
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), getHeight(), 6, theme.surfaceWithOpacity());

		PlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
			String line1 = "Join a world";
			String line2 = "to preview this";
			context.drawCenteredTextWithShadow(textRenderer, line1, getX() + getWidth() / 2, getY() + getHeight() / 2 - 10, theme.text());
			context.drawCenteredTextWithShadow(textRenderer, line2, getX() + getWidth() / 2, getY() + getHeight() / 2 + 2, theme.text());
			return;
		}
		int x1 = getX() + 4;
		int y1 = getY() + 4;
		int x2 = getX() + getWidth() - 4;
		int y2 = getY() + getHeight() - 4;
		int size = Math.round(Math.min(getWidth(), getHeight()) * 2 / 5f * zoom);

		drawRotatableEntity(context, x1, y1, x2, y2, size, yawDegrees, player);

		VeloDraw.strokeRounded(context, getX(), getY(), getWidth(), getHeight(), 6, 0x33FFFFFF);
	}

	//? if <26.1 {
	private static void drawRotatableEntity(DrawContext context, int x1, int y1, int x2, int y2, int size, float yawDegrees, net.minecraft.entity.LivingEntity entity) {
		Quaternionf worldRotation = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf tilt = new Quaternionf();
		worldRotation.mul(tilt);

		EntityRenderState state = extractRenderState(entity);
		if (state instanceof LivingEntityRenderState living) {
			living.bodyYaw = 180f + yawDegrees;
			living.relativeHeadYaw = 0f;
			living.pitch = living.pose == EntityPose.GLIDING ? 0f : living.pitch;
		}
		// entity.getHeight() rather than the render state's own dimension
		// field - a normal player is never custom-scaled, so there's
		// nothing to normalize here the way vanilla's own version of this
		// (which has to handle *any* entity, baby mobs included) does.
		Vector3f position = new Vector3f(0, entity.getHeight() / 2f, 0);
		context.addEntity(state, (float) size, position, worldRotation, tilt, x1, y1, x2, y2);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static EntityRenderState extractRenderState(net.minecraft.entity.LivingEntity entity) {
		EntityRenderManager dispatcher = MinecraftClient.getInstance().getEntityRenderDispatcher();
		EntityRenderer renderer = dispatcher.getRenderer(entity);
		EntityRenderState state = renderer.getAndUpdateRenderState(entity, 1.0f);
		state.light = 0xF000F0;
		state.shadowPieces.clear();
		state.outlineColor = 0;
		return state;
	}
	//?} else {
	/*private static void drawRotatableEntity(DrawContext context, int x1, int y1, int x2, int y2, int size, float yawDegrees, net.minecraft.world.entity.LivingEntity entity) {
		Quaternionf worldRotation = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf tilt = new Quaternionf();
		worldRotation.mul(tilt);

		EntityRenderState state = extractRenderState(entity);
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180f + yawDegrees;
			living.yRot = 0f;
			living.xRot = living.pose == Pose.FALL_FLYING ? 0f : living.xRot;
		}
		Vector3f position = new Vector3f(0, entity.getBbHeight() / 2f, 0);
		context.entity(state, (float) size, position, worldRotation, tilt, x1, y1, x2, y2);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static EntityRenderState extractRenderState(net.minecraft.world.entity.LivingEntity entity) {
		EntityRenderDispatcher dispatcher = MinecraftClient.getInstance().getEntityRenderDispatcher();
		EntityRenderer renderer = dispatcher.getRenderer(entity);
		EntityRenderState state = renderer.createRenderState(entity, 1.0f);
		state.lightCoords = 0xF000F0;
		state.shadowPieces.clear();
		state.outlineColor = 0;
		return state;
	}
	*///?}

	@Override
	public boolean mouseDragged(net.minecraft.client.gui.Click click, double offsetX, double offsetY) {
		if (dragging) {
			yawDegrees += (float) ((click.x() - lastDragX) * DEGREES_PER_DRAG_PIXEL);
			lastDragX = click.x();
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		dragging = true;
		lastDragX = click.x();
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.gui.Click click) {
		dragging = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + (float) verticalAmount * 0.08f));
		return true;
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE, getMessage());
	}
}
