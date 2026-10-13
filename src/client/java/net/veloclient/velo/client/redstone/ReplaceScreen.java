package net.veloclient.velo.client.redstone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.Map;

/**
 * Replace in the selection: one block type for another ("*" = every non-air block), with an
 * optional explicit state ("minecraft:repeater[delay=3]"). Properties both blocks share (facing,
 * powered, half...) carry over unless "Keep properties" is off or the new text sets them.
 */
public final class ReplaceScreen extends VeloWindow {

	private TextFieldWidget from;
	private TextFieldWidget to;
	private boolean keepProperties = true;
	private String fromValue;
	private String toValue;

	public ReplaceScreen() {
		super(Text.literal("Replace Blocks"), 380, 210);
		int[] look = RmWorld.lookedBlock(64);
		Object level = RmWorld.clientLevel();
		fromValue = look != null && level != null ? RmWorld.id(RmWorld.state(level, look[0], look[1], look[2])) : "*";
		var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		String held = player == null ? "" : net.veloclient.velo.client.pvp.PvpCompat.itemId(net.veloclient.velo.client.pvp.PvpCompat.mainHand(player));
		toValue = held != null && RmWorld.parse(held, Map.of()) != null ? held : "minecraft:air";
	}

	@Override
	protected void layoutContent() {
		if (from != null) {
			fromValue = from.getText();
			toValue = to.getText();
		}
		this.clearChildren();
		int x = contentX();
		int w = contentWidth();
		from = new TextFieldWidget(this.textRenderer, x, contentY() + 14, w, 16, Text.literal("From"));
		from.setMaxLength(200);
		from.setText(fromValue);
		addDrawableChild(from);
		to = new TextFieldWidget(this.textRenderer, x, contentY() + 48, w, 16, Text.literal("To"));
		to.setMaxLength(300);
		to.setText(toValue);
		addDrawableChild(to);
		addDrawableChild(new VeloButton(x, contentY() + 74, w, 18, Text.literal("Keep properties (facing, powered...): " + (keepProperties ? "On" : "Off")),
				b -> {
					keepProperties = !keepProperties;
					layoutContent();
				}));
		int half = (w - 8) / 2;
		addDrawableChild(new VeloButton(x, contentBottom() - 20, half, 20, Text.literal("Replace"), b -> {
			RmOps.replace(from.getText().strip(), to.getText().strip(), keepProperties);
			requestClose();
		}));
		addDrawableChild(new VeloButton(x + half + 8, contentBottom() - 20, half, 20, Text.literal("Cancel"), b -> requestClose()));
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		context.drawTextWithShadow(this.textRenderer, "Replace (block id, or * for everything)", contentX(), contentY() + 3, VeloUi.muted());
		context.drawTextWithShadow(this.textRenderer, "With (block id, optionally [property=value,...])", contentX(), contentY() + 37, VeloUi.muted());
	}
}
