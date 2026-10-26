package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A small window asking for one line ("New pack" name...). {@code onConfirm} returns an error to show, or null to close. */
public final class PackPromptScreen extends VeloWindow {

	private final String message;
	private final String initial;
	private final String confirmLabel;
	private final Function<String, String> onConfirm;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget field;
	private String value;
	private String error = "";

	public PackPromptScreen(Screen parent, String title, String message, String initial, String confirmLabel, Function<String, String> onConfirm) {
		super(Text.literal(title), 300, 136);
		this.message = message;
		this.initial = initial;
		this.value = initial;
		this.confirmLabel = confirmLabel;
		this.onConfirm = onConfirm;
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		field = new TextFieldWidget(this.textRenderer, contentX() + 6, contentY() + 20, contentWidth() - 12, 14, Text.literal("Name"));
		field.setDrawsBackground(false);
		field.setMaxLength(64);
		field.setText(value != null ? value : initial);
		field.setChangedListener(v -> {
			value = v;
			error = "";
		});
		addDrawableChild(field);
		setInitialFocus(field);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		MenuKit.text(context, this.textRenderer, MenuKit.ui(message), x, y, MenuKit.TEXT_2);
		VeloStyle.drawInputField(context, x, y + 14, contentWidth(), 22, field != null && field.isFocused(), false);
		if (!error.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(error, contentWidth())), x, y + 42, MenuKit.RED_TEXT);
		}
		int bw = (contentWidth() - 8) / 2;
		int by = contentBottom() - 18;
		hits.add(VeloUi.pill(context, x, by, bw, 18, "Cancel", 0, mouseX, mouseY, this::requestClose));
		hits.add(VeloUi.pill(context, x + bw + 8, by, bw, 18, confirmLabel, 1, mouseX, mouseY, this::confirm));
	}

	private void confirm() {
		String result = onConfirm.apply(value == null ? "" : value.trim());
		if (result != null) {
			error = result;
		} else {
			requestClose();
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == net.veloclient.velo.client.util.Keys.ENTER || input.key() == net.veloclient.velo.client.util.Keys.KP_ENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(input);
	}
}
