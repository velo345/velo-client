package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.aura.AuraManager;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;

/**
 * A small picture tile for the in-game wardrobe: wings show their rendered picture, auras the aura itself live
 * (no 3D player per tile - one live preview per screen), "None" an empty slot. The name sits under it with a tick
 * when equipped or a Store tag when not owned; the selected tile is outlined in its colour.
 */
public final class VeloCosmeticTile extends ClickableWidget {

	public enum Kind { WINGS, AURA }

	private final Kind kind;
	private final String id;
	private final String label;
	private final boolean selected;
	private final boolean equipped;
	private final boolean owned;
	private final Runnable onClick;
	private final AuraEffects.Emitter emitter;
	private final int accent;
	private float hover;

	public VeloCosmeticTile(int x, int y, int width, int height, Kind kind, String id, String label, boolean selected, boolean equipped,
			boolean owned, Runnable onClick) {
		super(x, y, width, height, Text.literal(label));
		this.kind = kind;
		this.id = id;
		this.label = label;
		this.selected = selected;
		this.equipped = equipped;
		this.owned = owned;
		this.onClick = onClick;
		var auraType = kind == Kind.AURA && !id.isEmpty() ? AuraManager.type(id) : java.util.Optional.<AuraManager.AuraType>empty();
		this.emitter = auraType.map(t -> new AuraEffects.Emitter(t.kind())).orElse(null);
		this.accent = id.isEmpty() ? 0xFF8A8A9A : kind == Kind.WINGS ? VeloWingCard.accentFor(id) : auraType.map(AuraManager.AuraType::accent).orElse(0xFFFF4D4D);
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		onClick.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		hover = VeloAnim.step(hover, isHovered() ? 1f : 0f, 0.016f * 6);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		// The launcher's Locker tile: graphite card, picture on a slightly lighter well, name bottom-left; equipped =
		// green edge + tick, the one on the preview = white edge.
		int nameH = 16;
		VeloDraw.fillRounded(context, x, y, w, h, 7, VeloAnim.lerpArgb(0xFF16161C, 0xFF1C1C23, hover));
		VeloDraw.fillRounded(context, x + 3, y + 3, w - 6, h - nameH - 4, 5, VeloAnim.lerpArgb(0xFF1C1C23, 0xFF22222A, hover));
		int edge = equipped ? 0xFF3DDC97 : selected ? 0xD0FFFFFF : VeloAnim.lerpArgb(0x14FFFFFF, 0x40FFFFFF, hover);
		VeloDraw.strokeRounded(context, x, y, w, h, 7, edge);
		int area = h - nameH - 4;
		if (id.isEmpty()) {
			VeloDraw.cross(context, x + w / 2f, y + 3 + area / 2f, 6, 1.3f, 0xFF6A6A7A);
		} else if (kind == Kind.WINGS) {
			Identifier picture = VeloWingCard.previewImage(id);
			if (picture != null) {
				int pw = Math.min(w - 12, Math.round((area - 4) * 4 / 3f));
				int ph = pw * 3 / 4;
				context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, picture, x + (w - pw) / 2, y + 3 + (area - ph) / 2,
						0f, 0f, pw, ph, pw, ph, pw, ph);
			}
		} else if (emitter != null) {
			float scale = Math.min(area / 2.6f, w / 2.6f);
			context.enableScissor(x + 3, y + 3, x + w - 3, y + 3 + area);
			AuraGuiRenderer.draw(context, emitter, owned ? AuraManager.settings(id) : AuraEffects.Settings.DEFAULT, x + w / 2f, y + area - 2, scale);
			context.disableScissor();
		}
		int textY = y + h - nameH + 4;
		int right = x + w - 7;
		if (equipped) {
			// A small tick, drawn (no font glyph needed).
			int cx = right - 6;
			int cy = textY + 4;
			for (int i = 0; i < 3; i++) {
				context.fill(cx + i, cy + i - 1, cx + i + 1, cy + i + 1, 0xFF3DDC97);
			}
			for (int i = 0; i < 5; i++) {
				context.fill(cx + 2 + i, cy + 1 - i, cx + 3 + i, cy + 3 - i, 0xFF3DDC97);
			}
			right -= 12;
		} else if (!owned && !id.isEmpty()) {
			Text store = net.veloclient.velo.client.gui.title.MenuKit.small("STORE");
			int sw = textRenderer.getWidth(store);
			net.veloclient.velo.client.gui.title.MenuKit.text(context, textRenderer, store, right - sw, textY + 1, 0xFFFFD56A);
			right -= sw + 6;
		}
		Text name = net.veloclient.velo.client.gui.title.MenuKit.fit(textRenderer, net.veloclient.velo.client.gui.title.MenuKit.uiBold(label), right - (x + 7));
		net.veloclient.velo.client.gui.title.MenuKit.text(context, textRenderer, name, x + 7, textY, owned || id.isEmpty() ? 0xFFF4F4F6 : 0xFFA9A9B3);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
