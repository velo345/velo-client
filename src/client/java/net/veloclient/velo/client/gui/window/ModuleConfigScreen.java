package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.ModuleIcons;
import net.veloclient.velo.client.gui.widget.VeloAnim;
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
	/** The choice whose option list is open (under its row), or null. */
	private ConfigField.ChoiceField dropdown;
	private VeloValueRow dropdownRow;
	private static final int OPTION_H = 16;

	public interface Reopenable {
		void reopen();
	}

	// ---- Dashboard mode (modules implementing ModuleDashboard): live stats, side menu of sections, cards ----
	private static final int STATS_H = 56;
	/** The graphed stat's last ~12 s, sampled 5x a second. */
	private final float[] history = new float[60];
	private int historyCount;
	private long historyAtNanos;
	private String tip;
	private int navW = 124;
	private int navItem = 24;
	private static final int CARD_H = 72;
	/** The section each dashboard showed last, so reopening lands where you were. */
	private static final java.util.Map<String, String> LAST_SECTION = new java.util.HashMap<>();
	private final ModuleDashboard dash;
	/** Left edge of the setting rows (the whole content normally, right of the side menu in a dashboard). */
	private int listX;
	private int listTop;
	private String section;
	private List<String> sections = List.of();
	private int[][] navRects = new int[0][];
	private ConfigField.ChoiceField cardField;
	private int[][] cardRects = new int[0][];
	private List<String> hintLines = List.of();
	private List<ModuleDashboard.Stat> stats = List.of();
	private long statsAtNanos;
	private final java.util.Map<Integer, Float> navHover = new java.util.HashMap<>();
	private float navSelY = -1;

	public ModuleConfigScreen(Module module, Reopenable parent) {
		super(Text.literal(module.displayName()), module instanceof ModuleDashboard ? 660 : 380,
				module instanceof ModuleDashboard ? 420 : WINDOW_HEIGHT);
		this.module = module;
		this.parent = parent;
		this.dash = module instanceof ModuleDashboard d && module instanceof Configurable ? d : null;
		if (dash != null) {
			dash.dashboardOpened();
		}
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
		if (dash != null) {
			layoutDashboard(previousScrollOffset);
			return;
		}
		listX = contentX();

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

		if (module instanceof ModuleShortcut shortcut) {
			int w = Math.min(contentWidth() - 96, this.textRenderer.getWidth(shortcut.shortcutLabel()) + 40);
			addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, w, 20, Text.literal(shortcut.shortcutLabel() + "  >"),
					b -> shortcut.openShortcut(this)).primary());
		}
		addDrawableChild(new VeloButton(contentX() + contentWidth() - 84, contentBottom() - 20, 84, 20, Text.literal("Done"),
				b -> {
					requestClose();
					parent.reopen();
				}).primary());
	}

	private void layoutDashboard(double previousScrollOffset) {
		this.clearChildren();
		java.util.LinkedHashMap<String, List<ConfigField>> bySection = new java.util.LinkedHashMap<>();
		String current = "General";
		for (ConfigField field : ((Configurable) module).configFields()) {
			if (field instanceof ConfigField.HeaderField header) {
				current = header.label();
				bySection.computeIfAbsent(current, k -> new ArrayList<>());
				continue;
			}
			bySection.computeIfAbsent(current, k -> new ArrayList<>()).add(field);
		}
		for (String name : List.copyOf(bySection.keySet())) {
			List<ConfigField> override = dash.sectionOverride(name);
			if (override != null) {
				bySection.put(name, override);
			}
		}
		sections = List.copyOf(bySection.keySet());
		if (section == null || !sections.contains(section)) {
			String last = LAST_SECTION.get(module.id());
			section = last != null && sections.contains(last) ? last : sections.isEmpty() ? null : sections.get(0);
		}

		// Master switch, right end of the stats strip.
		addDrawableChild(new VeloToggle(contentX() + contentWidth() - SWITCH_WIDTH - 12, contentY() + (STATS_H - 18) / 2, SWITCH_WIDTH,
				Text.empty(), module::isEnabled, module::setEnabled));

		int top = contentY() + STATS_H + 12;
		int bottom = contentBottom() - 30;
		// Small screens (big GUI scale): a narrower menu with shorter items, no hint line.
		navW = Math.max(84, Math.min(124, contentWidth() / 4));
		navItem = Math.max(14, Math.min(34, (bottom - top) / Math.max(1, sections.size())));
		navRects = new int[sections.size()][];
		for (int i = 0; i < sections.size(); i++) {
			navRects[i] = new int[] {contentX(), top + i * navItem, navW, navItem - 2};
		}

		listX = contentX() + navW + 14;
		int listWidth = contentWidth() - navW - 14;
		listTop = top;
		List<ConfigField> fields = section == null ? List.of() : bySection.get(section);
		int y = top;
		String hint = section == null ? null : dash.sectionHint(section);
		hintLines = hint == null || bottom - top < 170 ? List.of() : wrap(hint, listWidth - 8);
		if (!hintLines.isEmpty()) {
			y += hintLines.size() * DESC_LINE_HEIGHT + 10;
		}
		cardField = null;
		String cardLabel = dash.cardChoice();
		for (ConfigField field : fields) {
			if (cardLabel != null && field instanceof ConfigField.ChoiceField choice && choice.label().equals(cardLabel)) {
				cardField = choice;
			}
		}
		if (cardField != null) {
			List<String> options = cardField.options();
			int gap = 6;
			int w = (listWidth - 4 - gap * (options.size() - 1)) / Math.max(1, options.size());
			cardRects = new int[options.size()][];
			for (int i = 0; i < options.size(); i++) {
				cardRects[i] = new int[] {listX + 2 + i * (w + gap), y, w, bottom - y < CARD_H + 30 ? 22 : CARD_H};
			}
			y += cardRects.length == 0 ? 0 : cardRects[0][3] + 10;
		} else {
			cardRects = new int[0][];
		}

		int listBottom = bottom;
		scrollRegion = new VeloScrollRegion(listX, y, listWidth, Math.max(ROW_HEIGHT, listBottom - y));
		scrollRegion.setScrollOffset(previousScrollOffset);
		int rowY = 0;
		for (ConfigField field : fields) {
			if (field == cardField) {
				continue;
			}
			ClickableWidget widget = buildFieldWidget(field, rowY);
			addSelectableChild(widget);
			scrollRegion.addRow(widget);
			rowY += ROW_HEIGHT;
		}
		scrollRegion.layout(ROW_HEIGHT, 0);

		addDrawableChild(new VeloButton(contentX() + contentWidth() - 84, contentBottom() - 20, 84, 20, Text.literal("Done"),
				b -> {
					requestClose();
					parent.reopen();
				}).primary());
	}

	private void selectSection(String name) {
		if (name.equals(section)) {
			return;
		}
		section = name;
		LAST_SECTION.put(module.id(), name);
		dropdown = null;
		dropdownRow = null;
		scrollRegion = null; // a new section starts at its top
		layoutContent();
		restartPageIn();
		net.veloclient.velo.client.sound.VeloUiSounds.tab();
	}

	/** Dev-only (screenshot tour): show a dashboard section. */
	public void selectSectionForTour(String name) {
		selectSection(name);
	}

	@Override
	protected int[] pageArea() {
		if (dash == null) {
			return super.pageArea();
		}
		return new int[] {listX, listTop, contentX() + contentWidth() - listX, contentBottom() - 30 - listTop};
	}

	private void renderDashboard(DrawContext context, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int accent = theme.accentStart() | 0xFF000000;
		int x = contentX();
		int y = contentY();
		int w = contentWidth();

		// Stats strip
		long now = System.nanoTime();
		if (now - statsAtNanos > 250_000_000L || stats.isEmpty()) {
			statsAtNanos = now;
			try {
				stats = dash.dashboardStats();
				tip = dash.dashboardTip();
			} catch (RuntimeException e) {
				stats = List.of();
			}
		}
		if (now - historyAtNanos > 200_000_000L) {
			historyAtNanos = now;
			for (ModuleDashboard.Stat stat : stats) {
				if (stat.graph()) {
					try {
						float v = Float.parseFloat(stat.value());
						System.arraycopy(history, 1, history, 0, history.length - 1);
						history[history.length - 1] = v;
						historyCount = Math.min(history.length, historyCount + 1);
					} catch (NumberFormatException ignored) {
					}
					break;
				}
			}
		}
		VeloDraw.fillRounded(context, x, y, w, STATS_H, VeloStyle.RADIUS_CARD, VeloStyle.card());
		VeloDraw.strokeRounded(context, x, y, w, STATS_H, VeloStyle.RADIUS_CARD, VeloStyle.border());
		int statsRight = x + w - SWITCH_WIDTH - 28;
		int n = Math.max(1, stats.size());
		int tileW = (statsRight - x - 12) / n;
		for (int i = 0; i < stats.size(); i++) {
			ModuleDashboard.Stat stat = stats.get(i);
			int tx = x + 14 + i * tileW;
			int inner = tileW - 22;
			if (i > 0) {
				context.fill(tx - 9, y + 10, tx - 8, y + STATS_H - 10, VeloStyle.border());
			}
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer,
					net.veloclient.velo.client.gui.title.MenuKit.small(fit(stat.label().toUpperCase(java.util.Locale.ROOT), inner)), tx, y + 9, VeloStyle.textFaint());
			Text value = net.veloclient.velo.client.gui.title.MenuKit.displayLg(stat.value());
			value = net.veloclient.velo.client.gui.title.MenuKit.fit(this.textRenderer, value, inner);
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, value, tx, y + 20, stat.color());
			if (stat.note() != null && !stat.note().isEmpty()) {
				int vx = tx + this.textRenderer.getWidth(value) + 5;
				int room = tx + inner - vx;
				if (room > 14) {
					context.drawTextWithShadow(this.textRenderer, fit(stat.note(), room), vx, y + 25, VeloStyle.textMuted());
				}
			}
			int barY = y + STATS_H - 13;
			if (stat.graph() && historyCount > 1) {
				// a small area graph of the last seconds, newest on the right
				float max = 1;
				for (int k = history.length - historyCount; k < history.length; k++) {
					max = Math.max(max, history[k]);
				}
				int bars = Math.min(historyCount, inner / 2);
				for (int k = 0; k < bars; k++) {
					float v = history[history.length - bars + k] / max;
					int bh = Math.max(1, Math.round(v * 7));
					int bx = tx + inner - (bars - k) * 2;
					context.fill(bx, barY + 7 - bh, bx + 1, barY + 7, VeloUi.withAlpha(stat.color(), k == bars - 1 ? 0xFF : 0x90));
				}
			} else if (stat.meter() >= 0) {
				VeloDraw.fillRounded(context, tx, barY + 3, inner, 3, 1, 0x1AFFFFFF);
				int fw = Math.round(inner * Math.min(1f, stat.meter()));
				if (fw > 0) {
					VeloDraw.fillRounded(context, tx, barY + 3, fw, 3, 1, stat.color());
				}
			}
		}
		context.fill(statsRight + 6, y + 10, statsRight + 7, y + STATS_H - 10, VeloStyle.border());

		// Side menu
		int selected = section == null ? -1 : sections.indexOf(section);
		if (selected >= 0 && selected < navRects.length) {
			float target = navRects[selected][1];
			navSelY = navSelY < 0 ? target : navSelY + (target - navSelY) * 0.3f;
			int sy = Math.round(navSelY);
			VeloDraw.fillRounded(context, x, sy, navW, navItem - 2, 6, VeloUi.withAlpha(theme.accentStart(), 0x2A));
			VeloDraw.fillRounded(context, x, sy + 6, 2, Math.max(2, navItem - 14), 1, accent);
		}
		for (int i = 0; i < navRects.length; i++) {
			int[] r = navRects[i];
			boolean hovered = inside(r, mouseX, mouseY) && dropdown == null;
			float h = VeloAnim.step(navHover.getOrDefault(i, 0f), hovered ? 1f : 0f, 0.016f * 8);
			navHover.put(i, h);
			if (i != selected && h > 0.01f) {
				VeloDraw.fillRounded(context, r[0], r[1], r[2], r[3], 6, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x12 * h)));
			}
			int color = i == selected ? VeloStyle.text() : VeloAnim.lerpArgb(VeloStyle.textMuted(), VeloStyle.text(), h);
			String summary = r[3] >= 28 ? dash.sectionSummary(sections.get(i)) : null;
			if (summary != null) {
				context.drawTextWithShadow(this.textRenderer, fit(sections.get(i), navW - 20), r[0] + 10, r[1] + r[3] / 2 - 9, color);
				net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer,
						net.veloclient.velo.client.gui.title.MenuKit.small(fit(summary, navW - 20)), r[0] + 10, r[1] + r[3] / 2 + 3,
						i == selected ? VeloUi.withAlpha(theme.accentStart(), 0xFF) : VeloStyle.textFaint());
			} else {
				context.drawTextWithShadow(this.textRenderer, fit(sections.get(i), navW - 20), r[0] + 10, r[1] + (r[3] - 8) / 2, color);
			}
		}
		context.fill(x + navW + 6, listTop, x + navW + 7, contentBottom() - 30, VeloStyle.border());

		// Section hint
		int hy = listTop;
		for (String line : hintLines) {
			context.drawTextWithShadow(this.textRenderer, line, listX + 4, hy, VeloStyle.textMuted());
			hy += DESC_LINE_HEIGHT;
		}

		// Cards
		if (cardField != null) {
			String current = cardField.get().get();
			List<String> options = cardField.options();
			for (int i = 0; i < cardRects.length && i < options.size(); i++) {
				int[] r = cardRects[i];
				String option = options.get(i);
				boolean active = option.equals(current);
				boolean hovered = inside(r, mouseX, mouseY) && dropdown == null;
				VeloDraw.fillRounded(context, r[0], r[1], r[2], r[3], 8,
						active ? VeloUi.withAlpha(theme.accentStart(), 0x26) : hovered ? VeloStyle.cardHover() : VeloStyle.card());
				VeloDraw.strokeRounded(context, r[0], r[1], r[2], r[3], 8, active ? accent : VeloStyle.border());
				if (active) {
					VeloDraw.fillCircle(context, r[0] + r[2] - 9f, r[1] + 9f, 2.5f, accent);
				}
				context.drawTextWithShadow(this.textRenderer, fit(option, r[2] - 22), r[0] + 8, r[1] + (r[3] < CARD_H ? 7 : 8), VeloStyle.text());
				if (r[3] < CARD_H) {
					continue; // compact cards (small screen): just the names
				}
				context.drawTextWithShadow(this.textRenderer, fit(dash.cardBlurb(option), r[2] - 14), r[0] + 8, r[1] + 21, VeloStyle.textMuted());
				String status = active ? dash.cardStatus(option) : null;
				int[] meters = dash.cardMeters(option);
				if (status != null) {
					context.drawTextWithShadow(this.textRenderer, fit(status, r[2] - 14), r[0] + 8, r[1] + 33, accent);
				}
				if (meters != null && r[3] >= 60) {
					cardMeter(context, r[0] + 8, r[1] + r[3] - 22, r[2] - 16, "FPS", meters[0], 0xFF4ADE80);
				}
				if (meters != null && r[3] >= 60) {
					cardMeter(context, r[0] + 8, r[1] + r[3] - 11, r[2] - 16, "LOOKS", meters[1], 0xFF60A5FA);
				}
			}
		}

		// Bottom left (left of Done): a tip for the situation, else what the module does
		if (tip != null) {
			int tx = x;
			VeloDraw.fillRounded(context, tx, contentBottom() - 17, 26, 14, 5, VeloUi.withAlpha(0xFFF5B942, 0x2A));
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.small("TIP"),
					tx + 6, contentBottom() - 13, 0xFFF5B942);
			context.drawTextWithShadow(this.textRenderer, fit(tip, w - 84 - 16 - 32), tx + 32, contentBottom() - 14, VeloStyle.textMuted());
		} else {
			context.drawTextWithShadow(this.textRenderer, fit(module.description(), w - 84 - 16), x, contentBottom() - 14, VeloStyle.textFaint());
		}
	}

	/** A card's tiny labelled 5-step meter. */
	private void cardMeter(DrawContext context, int x, int y, int w, String label, int value, int color) {
		net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.small(label),
				x, y - 2, VeloStyle.textFaint());
		int mx = x + 30;
		int segW = Math.max(3, (w - 30 - 4 * 2) / 5);
		for (int k = 0; k < 5; k++) {
			VeloDraw.fillRounded(context, mx + k * (segW + 2), y, segW, 3, 1, k < value ? color : 0x1FFFFFFF);
		}
	}

	private static boolean inside(int[] r, double mx, double my) {
		return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
	}

	/** {@code text} cut to {@code width} with "..." when it doesn't fit. */
	private String fit(String text, int width) {
		if (text == null) {
			return "";
		}
		if (this.textRenderer.getWidth(text) <= width) {
			return text;
		}
		int dots = this.textRenderer.getWidth("...");
		int end = text.length();
		while (end > 0 && this.textRenderer.getWidth(text.substring(0, end)) + dots > width) {
			end--;
		}
		return text.substring(0, end) + "...";
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
		int x = listX + 4;
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
						if (options.size() > 2) {
							// A real list to pick from (the chip already shows a dropdown chevron).
							dropdown = choice;
							dropdownRow = b;
							return;
						}
						int index = options.indexOf(choice.get().get());
						choice.set().accept(options.get((index + 1) % options.size()));
						// Some modules show different fields per choice; scroll position survives the rebuild.
						layoutContent();
					});
		}
		if (field instanceof ConfigField.TextField text) {
			TextFieldWidget widget = new TextFieldWidget(this.textRenderer, x, y + 4, width, 18, Text.literal(text.label()));
			widget.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder(text.placeholder()));
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
		if (field instanceof ConfigField.HeaderField header) {
			return new net.veloclient.velo.client.gui.widget.VeloSectionHeader(x, y + 10, width, 14, header.label().toUpperCase(java.util.Locale.ROOT));
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
		if (dropdown != null && input.key() == net.veloclient.velo.client.util.Keys.ESCAPE) {
			dropdown = null;
			dropdownRow = null;
			return true;
		}
		if (awaitingRebind != null) {
			ConfigField.KeybindField field = awaitingRebind;
			awaitingRebind = null;
			// Escape unbinds the key entirely rather than just cancelling
			// back to whatever was bound before - it's also not captured as
			// the new key itself, and doesn't fall through to close this
			// whole settings window.
			field.onKeyCodeChosen().accept(input.key() == net.veloclient.velo.client.util.Keys.ESCAPE
					? net.veloclient.velo.client.util.Keys.UNKNOWN : input.key());
			layoutContent();
			return true;
		}
		if (awaitingChordRebind != null) {
			if (input.key() == net.veloclient.velo.client.util.Keys.ESCAPE) {
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

	/** The open option list's box {x, y, w, h}: under its row, or above it when there's no room below. */
	private int[] dropdownBox() {
		int w = Math.max(120, Math.min(200, dropdownRow.getWidth() / 2 + 20));
		int h = dropdown.options().size() * OPTION_H + 6;
		int x = dropdownRow.getX() + dropdownRow.getWidth() - w;
		int y = dropdownRow.getY() + dropdownRow.getHeight();
		if (y + h > contentBottom() && dropdownRow.getY() - h >= contentY()) {
			y = dropdownRow.getY() - h;
		}
		return new int[] {x, y, w, h};
	}

	/** An option as the list shows it: HUD Style's fonts each in their own font. */
	private Text optionLabel(String option) {
		if (module.id().equals("hud-style") && dropdown.label().equals("Font")) {
			return net.veloclient.velo.client.hud.HudLook.preview(option, option);
		}
		return Text.literal(option);
	}

	private void renderDropdown(DrawContext context, int mouseX, int mouseY) {
		int[] box = dropdownBox();
		Theme theme = ThemeManager.active();
		VeloDraw.shadow(context, box[0], box[1], box[2], box[3], 7, 10, 3, 0x66000000);
		VeloDraw.fillRounded(context, box[0], box[1], box[2], box[3], 7, 0xF4141418);
		VeloDraw.strokeRounded(context, box[0], box[1], box[2], box[3], 7, VeloStyle.border());
		String current = dropdown.get().get();
		int y = box[1] + 3;
		for (String option : dropdown.options()) {
			boolean hovered = mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= y && mouseY < y + OPTION_H;
			boolean selected = option.equals(current);
			if (hovered || selected) {
				VeloDraw.fillRounded(context, box[0] + 3, y, box[2] - 6, OPTION_H, 5,
						selected ? VeloUi.withAlpha(theme.accentStart(), 0x30) : 0x14FFFFFF);
			}
			if (selected) {
				VeloDraw.fillCircle(context, box[0] + 10f, y + OPTION_H / 2f, 2.2f, theme.accentStart() | 0xFF000000);
			}
			context.drawTextWithShadow(this.textRenderer, optionLabel(option), box[0] + 18, y + (OPTION_H - 8) / 2,
					selected || hovered ? VeloStyle.text() : VeloStyle.textMuted());
			y += OPTION_H;
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (dropdown != null) {
			int[] box = dropdownBox();
			double mx = click.x();
			double my = click.y();
			if (mx >= box[0] && mx < box[0] + box[2] && my >= box[1] + 3 && my < box[1] + box[3] - 3) {
				int index = (int) ((my - box[1] - 3) / OPTION_H);
				if (index >= 0 && index < dropdown.options().size()) {
					dropdown.set().accept(dropdown.options().get(index));
				}
			}
			// Any click closes the list (and isn't passed on to what's under it).
			dropdown = null;
			dropdownRow = null;
			layoutContent();
			return true;
		}
		if (dash != null && click.button() == 0) {
			for (int i = 0; i < navRects.length; i++) {
				if (inside(navRects[i], click.x(), click.y())) {
					selectSection(sections.get(i));
					return true;
				}
			}
			if (cardField != null) {
				List<String> options = cardField.options();
				for (int i = 0; i < cardRects.length && i < options.size(); i++) {
					if (inside(cardRects[i], click.x(), click.y())) {
						if (!options.get(i).equals(cardField.get().get())) {
							cardField.set().accept(options.get(i));
							net.veloclient.velo.client.sound.VeloUiSounds.tab();
							layoutContent();
						}
						return true;
					}
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		dropdown = null;
		dropdownRow = null;
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layout(ROW_HEIGHT, 0);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (dash != null) {
			renderDashboard(context, mouseX, mouseY);
			return;
		}
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
		boolean overDropdown = false;
		if (dropdown != null) {
			int[] box = dropdownBox();
			overDropdown = mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= box[1] && mouseY < box[1] + box[3];
		}
		if (scrollRegion != null) {
			// Rows under the open list don't light up.
			scrollRegion.renderRows(context, overDropdown ? -10000 : mouseX, overDropdown ? -10000 : mouseY, delta);
			scrollRegion.renderScrollbar(context, ROW_HEIGHT, 0);
		}
		if (dropdown != null) {
			renderDropdown(context, mouseX, mouseY);
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
		if (dash != null) {
			dash.dashboardClosed();
		}
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		// Many settings (Max FPS, view distance...) are vanilla options: write options.txt too, or a restart before
		// vanilla saved it on its own brought the old values back.
		//? if <26.1 {
		net.minecraft.client.MinecraftClient.getInstance().options.write();
		//?} else {
		/*net.minecraft.client.Minecraft.getInstance().options.save();
		*///?}
		super.requestClose();
	}
}
