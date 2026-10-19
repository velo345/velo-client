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
		VeloDraw.fillRounded(context, x, y, w, h, 5, selected ? 0xFF1E1E28 : 0xFF15151C);
		VeloDraw.fillRoundedGradient(context, x, y, w, h - 14, 5, VeloUi.withAlpha(accent, 0x28 + Math.round(0x20 * hover)), 0x0015151C);
		VeloDraw.strokeRounded(context, x, y, w, h, 5, selected ? accent : VeloUi.withAlpha(accent, 0x30 + Math.round(0x70 * hover)));
		int area = h - 16;
		if (id.isEmpty()) {
			VeloDraw.cross(context, x + w / 2f, y + area / 2f + 2, 7, 1.5f, 0xFF6A6A7A);
		} else if (kind == Kind.WINGS) {
			Identifier picture = VeloWingCard.previewImage(id);
			if (picture != null) {
				int pw = Math.min(w - 6, Math.round(area * 4 / 3f));
				int ph = pw * 3 / 4;
				context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, picture, x + (w - pw) / 2, y + 2 + (area - ph) / 2,
						0f, 0f, pw, ph, pw, ph, pw, ph);
			}
		} else if (emitter != null) {
			float scale = Math.min(area / 2.6f, w / 2.6f);
			context.enableScissor(x + 1, y + 1, x + w - 1, y + area + 2);
			AuraGuiRenderer.draw(context, emitter, owned ? AuraManager.settings(id) : AuraEffects.Settings.DEFAULT, x + w / 2f, y + area - 1, scale);
			context.disableScissor();
		}
		String suffix = equipped ? " ✔" : !owned && !id.isEmpty() ? " ·Store" : "";
		String text = VeloUi.trim(label, w - 8 - textRenderer.getWidth(suffix)) + suffix;
		int tw = textRenderer.getWidth(text);
		context.drawTextWithShadow(textRenderer, text, x + (w - tw) / 2, y + h - 12, equipped ? 0xFF8FE3A8 : owned || id.isEmpty() ? 0xFFFFFFFF : 0xFFFFD56A);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
