package net.veloclient.velo.client.redstone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

/** Name and save the selection as a .veloschem (Ctrl+S in Redstone Mode). */
public final class SchematicSaveScreen extends VeloWindow {

	private TextFieldWidget name;

	public SchematicSaveScreen() {
		super(Text.literal("Save Schematic"), 360, 150);
	}

	@Override
	protected void layoutContent() {
		String value = name != null ? name.getText() : "redstone-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmm"));
		this.clearChildren();
		name = new TextFieldWidget(this.textRenderer, contentX(), contentY() + 14, contentWidth(), 16, Text.literal("Name"));
		name.setMaxLength(64);
		name.setText(value);
		addDrawableChild(name);
		setInitialFocus(name);
		int half = (contentWidth() - 8) / 2;
		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, half, 20, Text.literal("Save"), b -> {
			SchematicIO.saveSelection(name.getText());
			requestClose();
		}));
		addDrawableChild(new VeloButton(contentX() + half + 8, contentBottom() - 20, half, 20, Text.literal("Cancel"), b -> requestClose()));
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		context.drawTextWithShadow(this.textRenderer, "Name (" + RmState.volume() + " blocks)", contentX(), contentY() + 3, VeloUi.muted());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Saved to schematics/velo - also listed under Schematics > Velo", contentWidth()),
				contentX(), contentY() + 38, VeloUi.muted());
	}
}
