package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.List;

/** A small "Are you sure?" window in the Velo style - used before deleting things. */
public final class VeloConfirmScreen extends VeloWindow {

	private final String message;
	private final String confirmLabel;
	private final Runnable onConfirm;
	private List<String> lines = List.of();

	public VeloConfirmScreen(Screen parent, String title, String message, String confirmLabel, Runnable onConfirm) {
		super(Text.literal(title), 280, 140);
		this.message = message;
		this.confirmLabel = confirmLabel;
		this.onConfirm = onConfirm;
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		lines = VeloUi.wrap(message, contentWidth());
		int buttonWidth = (contentWidth() - 8) / 2;
		int y = contentBottom() - 20;
		addDrawableChild(new VeloButton(contentX(), y, buttonWidth, 20, Text.literal("Cancel"), b -> requestClose()));
		addDrawableChild(new VeloButton(contentX() + buttonWidth + 8, y, buttonWidth, 20, Text.literal(confirmLabel), b -> {
			onConfirm.run();
			requestClose();
		}).primary());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int y = contentY();
		for (String line : lines) {
			context.drawTextWithShadow(this.textRenderer, line, contentX(), y, VeloStyle.text());
			y += 11;
		}
	}
}
