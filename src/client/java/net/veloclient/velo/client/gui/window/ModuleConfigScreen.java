package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.ModuleIcons;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.widget.VeloValueRow;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.widget.VeloToggle;
import net.veloclient.velo.client.keybind.ChordKeybinds;
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
	private static final int CHIP = 30;
	private static final int SWITCH_WIDTH = 28;
	private static final int WINDOW_HEIGHT = 420;

	private final Module module;
	private final Reopenable parent;
	private List<String> descriptionLines = List.of();
	private ConfigField.KeybindField awaitingRebind;
	private ConfigField.ChordKeybindField awaitingChordRebind;
	private final java.util.LinkedHashSet<Integer> chordHeldKeys = new java.util.LinkedHashSet<>();
	private final java.util.LinkedHashSet<Integer> chordMaxKeys = new java.util.LinkedHashSet<>();
	private VeloScrollRegion scrollRegion;

	public interface Reopenable {
		void reopen();
	}

	public ModuleConfigScreen(Module module, Reopenable parent) {
		super(Text.literal(module.displayName()), 380, WINDOW_HEIGHT);
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
		// A rebuild (e.g. from clicking a keybind field, or cycling a
		// choice) used to always create a brand new VeloScrollRegion at
		// offset 0, so any scrolled-down position silently reset to the top
		// on every single interaction - preserving it here means clicking
		// something no longer scrolls the list back up from under you.
		double previousScrollOffset = scrollRegion != null ? scrollRegion.scrollOffset() : 0;

		this.clearChildren();
		// Header card: icon chip, description, safety pill, and the master switch on the right.
		descriptionLines = wrap(module.description(), headerTextWidth());
		int headerHeight = headerHeight();
		addDrawableChild(new VeloToggle(contentX() + contentWidth() - SWITCH_WIDTH - 10, contentY() + 10, SWITCH_WIDTH,
				Text.empty(), module::isEnabled, module::setEnabled));

		int y = contentY() + headerHeight + 12;
		int listBottom = contentBottom() - 30;
		scrollRegion = new VeloScrollRegion(contentX(), y, contentWidth(), Math.max(ROW_HEIGHT, listBottom - y));
		scrollRegion.setScrollOffset(previousScrollOffset);

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

		addDrawableChild(new VeloButton(contentX() + contentWidth() - 84, contentBottom() - 20, 84, 20, Text.literal("Done"),
				b -> {
					requestClose();
					parent.reopen();
				}).primary());
	}

	private int rowWidth() {
		return scrollRegion != null ? scrollRegion.viewportWidth() - 4 : contentWidth() - 12;
	}

	private int headerTextWidth() {
		return contentWidth() - CHIP - 30 - SWITCH_WIDTH - 12;
	}

	private int headerHeight() {
		return Math.max(CHIP + 20, 10 + descriptionLines.size() * DESC_LINE_HEIGHT + 22);
	}

	private ClickableWidget buildFieldWidget(ConfigField field, int y) {
		int x = contentX() + 4;
		int width = rowWidth();
		if (field instanceof ConfigField.SliderField slider) {
			return new VeloSlider(x, y + 6, width, 18, slider.label(),
					slider.min(), slider.max(), slider.get(), slider.set(), null,
					v -> slider.format().apply(v));
		}
		if (field instanceof ConfigField.ToggleField toggle) {
			return new VeloToggle(x, y + 2, width, Text.literal(toggle.label()),
					toggle.get(), toggle.set());
		}
		if (field instanceof ConfigField.KeybindField keybind) {
			return new VeloValueRow(x, y + 2, width, 22, Text.literal(keybind.label()), VeloValueRow.Kind.KEY,
					() -> keybind.displayText().get(), null,
					b -> {
						awaitingRebind = keybind;
						layoutContent();
					}).listening(keybind == awaitingRebind);
		}
		if (field instanceof ConfigField.ChoiceField choice) {
			return new VeloValueRow(x, y + 2, width, 22, Text.literal(choice.label()), VeloValueRow.Kind.CHOICE,
					() -> choice.get().get(), null,
					b -> {
						List<String> options = choice.options();
						int index = options.indexOf(choice.get().get());
						choice.set().accept(options.get((index + 1) % options.size()));
						// Some modules show different fields per choice; scroll position survives the rebuild.
						layoutContent();
					});
		}
		if (field instanceof ConfigField.TextField text) {
			TextFieldWidget widget = new TextFieldWidget(this.textRenderer, x, y + 4, width, 18, Text.literal(text.label()));
			widget.setPlaceholder(Text.literal(text.placeholder()));
			widget.setText(text.get().get());
			widget.setChangedListener(text.set());
			return widget;
		}
		if (field instanceof ConfigField.ColorField color) {
			return new VeloValueRow(x, y + 2, width, 22, Text.literal(color.label()), VeloValueRow.Kind.COLOR,
					() -> String.format("#%06X", color.get().getAsInt() & 0xFFFFFF), () -> color.get().getAsInt(),
					b -> this.client.setScreen(new net.veloclient.velo.client.gui.VeloColorPickerScreen(
							this, color.label(), color.get().getAsInt(), color.includeAlpha(), color.set()::accept)));
		}
		if (field instanceof ConfigField.ActionButtonField action) {
			return new VeloButton(x, y + 3, width, 20, Text.literal(action.label()),
					b -> action.action().run());
		}
		if (field instanceof ConfigField.ChordKeybindField chord) {
			boolean listening = chord == awaitingChordRebind;
			return new VeloValueRow(x, y + 2, width, 22, Text.literal(chord.label()), VeloValueRow.Kind.KEY,
					() -> {
						if (!listening) {
							return ChordKeybinds.displayText(chord.get().get());
						}
						return chordMaxKeys.isEmpty() ? "Hold keys..." : ChordKeybinds.displayText(List.copyOf(chordMaxKeys)) + "...";
					}, null,
					b -> {
						awaitingChordRebind = chord;
						chordHeldKeys.clear();
						chordMaxKeys.clear();
						layoutContent();
					}).listening(listening && chordMaxKeys.isEmpty());
		}
		throw new IllegalStateException("Unknown ConfigField type: " + field.getClass());
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (awaitingRebind != null) {
			ConfigField.KeybindField field = awaitingRebind;
			awaitingRebind = null;
			// Escape unbinds the key entirely rather than just cancelling
			// back to whatever was bound before - it's also not captured as
			// the new key itself, and doesn't fall through to close this
			// whole settings window.
			field.onKeyCodeChosen().accept(input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
					? org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN : input.key());
			layoutContent();
			return true;
		}
		if (awaitingChordRebind != null) {
			if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
				ConfigField.ChordKeybindField field = awaitingChordRebind;
				awaitingChordRebind = null;
				chordHeldKeys.clear();
				chordMaxKeys.clear();
				field.set().accept(List.of());
				layoutContent();
				return true;
			}
			// Held keys accumulate into chordMaxKeys as they go down, so a
			// chord like Ctrl+Shift+Q is captured correctly even though each
			// key arrives as its own keyPressed event rather than all at
			// once - keyReleased below finalizes it once every key in the
			// combo has been let go again.
			chordHeldKeys.add(input.key());
			chordMaxKeys.add(input.key());
			layoutContent();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyInput input) {
		if (awaitingChordRebind != null) {
			chordHeldKeys.remove(input.key());
			if (chordHeldKeys.isEmpty() && !chordMaxKeys.isEmpty()) {
				ConfigField.ChordKeybindField field = awaitingChordRebind;
				awaitingChordRebind = null;
				field.set().accept(List.copyOf(chordMaxKeys));
				chordMaxKeys.clear();
				layoutContent();
			}
			return true;
		}
		return super.keyReleased(input);
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
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		int x = contentX();
		int y = contentY();
		int headerHeight = headerHeight();
		VeloDraw.fillRounded(context, x, y, contentWidth(), headerHeight, VeloStyle.RADIUS_CARD, VeloStyle.card());
		VeloDraw.strokeRounded(context, x, y, contentWidth(), headerHeight, VeloStyle.RADIUS_CARD, VeloStyle.border());

		int chipX = x + 10;
		int chipY = y + 10;
		boolean on = module.isEnabled();
		VeloDraw.fillRounded(context, chipX, chipY, CHIP, CHIP, 9,
				on ? VeloUi.withAlpha(theme.accentStart(), 0x30) : VeloUi.withAlpha(theme.text(), 0x0E));
		context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, ModuleIcons.textureFor(module.id()),
				chipX + 6, chipY + 6, 0f, 0f, CHIP - 12, CHIP - 12, 1024, 1024, 1024, 1024,
				on ? (theme.accentStart() | 0xFF000000) : VeloStyle.textMuted());

		int textX = chipX + CHIP + 10;
		int lineY = y + 10;
		for (String line : descriptionLines) {
			context.drawTextWithShadow(this.textRenderer, line, textX, lineY, VeloStyle.textMuted());
			lineY += DESC_LINE_HEIGHT;
		}
		drawSafetyPill(context, textX, lineY + 3);
	}

	private void drawSafetyPill(DrawContext context, int x, int y) {
		var tag = module.safetyTag();
		int color = switch (tag) {
			case ALWAYS_SAFE -> 0xFF3FB97A;
			case COSMETIC_ONLY -> 0xFF5B9BF0;
			case CHECK_SERVER_RULES -> 0xFFE7A33E;
		};
		String label = tag.displayName();
		int width = this.textRenderer.getWidth(label) + 16;
		VeloDraw.fillRounded(context, x, y, width, 13, 6, VeloUi.withAlpha(color, 0x2A));
		VeloDraw.fillCircle(context, x + 6f, y + 6.5f, 2f, color);
		context.drawTextWithShadow(this.textRenderer, label, x + 11, y + 3, color);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbar(context, ROW_HEIGHT, 0);
		}
		if (awaitingRebind != null || awaitingChordRebind != null) {
			String hint = awaitingRebind != null ? "Press a key - Esc unbinds" : "Hold the keys, release to save - Esc unbinds";
			context.drawTextWithShadow(this.textRenderer, hint, contentX(), contentBottom() - 14, VeloStyle.textMuted());
		}
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
