package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.widget.VeloToggle;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings window for any module (design spec section 5's "gear icon opens
 * inline expandable panel"): description, safety tag, an enable/disable
 * toggle, and - for modules that implement {@link Configurable} - the
 * adjustable fields as sliders/toggles/keybinds/text/choices, in a
 * scrollable list so a module with many settings (e.g. Performance Boost)
 * doesn't need an enormous or off-screen window.
 */
public final class ModuleConfigScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 26;
	private static final int DESC_LINE_HEIGHT = 11;
	private static final int WINDOW_HEIGHT = 420;

	private final Module module;
	private final Reopenable parent;
	private List<String> descriptionLines = List.of();
	private ConfigField.KeybindField awaitingRebind;
	private VeloScrollRegion scrollRegion;

	public interface Reopenable {
		void reopen();
	}

	public ModuleConfigScreen(Module module, Reopenable parent) {
		super(Text.literal(module.displayName() + " Settings"), 360, WINDOW_HEIGHT);
		this.module = module;
		this.parent = parent;
		// Every current Reopenable is also the Screen to fall back to -
		// without this, closing via Escape (not just the Done button) fell
		// through VeloWindow's default null returnScreen and dropped
		// straight back to gameplay instead of the module list.
		if (parent instanceof net.minecraft.client.gui.screen.Screen screen) {
			returnTo(screen);
		}
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		descriptionLines = wrap(module.description(), contentWidth());

		int y = contentY() + descriptionLines.size() * DESC_LINE_HEIGHT + 6;

		addDrawableChild(new VeloToggle(contentX(), y, contentWidth() - 60,
				Text.literal("Enabled"), module::isEnabled, module::setEnabled));
		y += ROW_HEIGHT + 4;

		int listBottom = contentBottom() - 26;
		scrollRegion = new VeloScrollRegion(contentX(), y, contentWidth(), Math.max(0, listBottom - y));

		if (module instanceof Configurable configurable) {
			int rowY = 0;
			for (ConfigField field : configurable.configFields()) {
				ClickableWidget widget = buildFieldWidget(field, rowY);
				addSelectableChild(widget);
				scrollRegion.addRow(widget);
				rowY += ROW_HEIGHT;
			}
			scrollRegion.layout(ROW_HEIGHT, 0);
		}

		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, contentWidth(), 20, Text.literal("Done"),
				b -> {
					requestClose();
					parent.reopen();
				}));
	}

	private ClickableWidget buildFieldWidget(ConfigField field, int y) {
		if (field instanceof ConfigField.SliderField slider) {
			return new VeloSlider(contentX(), y + 10, contentWidth(), 16, slider.label(),
					slider.min(), slider.max(), slider.get(), slider.set(), null,
					v -> slider.format().apply(v));
		}
		if (field instanceof ConfigField.ToggleField toggle) {
			return new VeloToggle(contentX(), y, contentWidth(), Text.literal(toggle.label()),
					toggle.get(), toggle.set());
		}
		if (field instanceof ConfigField.KeybindField keybind) {
			boolean listening = keybind == awaitingRebind;
			Text buttonText = Text.literal(keybind.label() + ": "
					+ (listening ? "> press a key (Esc to cancel) <" : keybind.displayText().get()));
			return new VeloButton(contentX(), y, contentWidth(), 20, buttonText,
					b -> {
						awaitingRebind = keybind;
						layoutContent();
					});
		}
		if (field instanceof ConfigField.ChoiceField choice) {
			String current = choice.get().get();
			return new VeloButton(contentX(), y, contentWidth(), 20,
					Text.literal(choice.label() + ": " + current + "  ▸"),
					b -> {
						List<String> options = choice.options();
						int index = options.indexOf(current);
						String next = options.get((index + 1) % options.size());
						choice.set().accept(next);
						layoutContent();
					});
		}
		if (field instanceof ConfigField.TextField text) {
			TextFieldWidget widget = new TextFieldWidget(this.textRenderer, contentX(), y, contentWidth(), 18, Text.literal(text.label()));
			widget.setPlaceholder(Text.literal(text.placeholder()));
			widget.setText(text.get().get());
			widget.setChangedListener(text.set());
			return widget;
		}
		throw new IllegalStateException("Unknown ConfigField type: " + field.getClass());
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (awaitingRebind != null) {
			ConfigField.KeybindField field = awaitingRebind;
			awaitingRebind = null;
			// Escape just cancels the rebind (matches vanilla's own Controls
			// screen) rather than also being captured as the new key or
			// falling through to close this whole settings window.
			if (input.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
				field.onKeyCodeChosen().accept(input.key());
			}
			layoutContent();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layout(ROW_HEIGHT, 0);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbar(context, ROW_HEIGHT, 0);
		}
		Theme theme = ThemeManager.active();
		int y = contentY();
		for (String line : descriptionLines) {
			context.drawTextWithShadow(this.textRenderer, line, contentX(), y, theme.text() & 0x00FFFFFF | 0xCC000000);
			y += DESC_LINE_HEIGHT;
		}
		String safety = "[" + module.safetyTag().displayName() + "]";
		int safetyWidth = this.textRenderer.getWidth(safety);
		context.drawTextWithShadow(this.textRenderer, safety, contentX() + contentWidth() - safetyWidth, contentY() - 12, theme.accentStart());
	}

	private static List<String> wrap(String text, int maxWidth) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (textRenderer.getWidth(candidate) > maxWidth && !current.isEmpty()) {
				lines.add(current.toString());
				current = new StringBuilder(word);
			} else {
				current = new StringBuilder(candidate);
			}
		}
		if (!current.isEmpty()) {
			lines.add(current.toString());
		}
		return lines;
	}

	@Override
	protected void requestClose() {
		// Covers both the Done button (calls this directly) and Escape
		// (Screen's default handling routes through VeloWindow.close(),
		// which itself calls this) - one override for both paths.
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		super.requestClose();
	}
}
