package net.veloclient.velo.client.redstone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

/**
 * Name and save the selection (Ctrl+S in Redstone Mode) as a .veloschem, or as a .litematic to share with friends who
 * use Litematica instead of Velo.
 */
public final class SchematicSaveScreen extends VeloWindow {

	private TextFieldWidget name;
	/** Remembered while the game runs, so sharing several builds doesn't mean switching every time. */
	private static boolean litematic;

	public SchematicSaveScreen() {
		super(Text.literal("Save Schematic"), 360, 172);
	}

	/** Dev-only (screenshot tour): picks the format as its button would. */
	public void litematicForTour(boolean on) {
		litematic = on;
		layoutContent();
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
		addDrawableChild(new VeloButton(contentX(), contentY() + 50, half, 18, Text.literal(".veloschem (Velo)"), b -> {
			litematic = false;
			layoutContent();
		}).selected(!litematic));
		addDrawableChild(new VeloButton(contentX() + half + 8, contentY() + 50, half, 18, Text.literal(".litematic (Litematica)"), b -> {
			litematic = true;
			layoutContent();
		}).selected(litematic));
		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, half, 20, Text.literal("Save"), b -> {
			SchematicIO.saveSelection(name.getText(), litematic);
			requestClose();
		}));
		addDrawableChild(new VeloButton(contentX() + half + 8, contentBottom() - 20, half, 20, Text.literal("Cancel"), b -> requestClose()));
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		context.drawTextWithShadow(this.textRenderer, "Name (" + RmState.volume() + " blocks)", contentX(), contentY() + 3, VeloUi.muted());
		String where = litematic ? "Saved to schematics/ - friends without Velo can load it in Litematica"
				: "Saved to schematics/velo - keeps running clocks exactly; listed under Schematics > Velo";
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(where, contentWidth()), contentX(), contentY() + 38, VeloUi.muted());
	}
}
