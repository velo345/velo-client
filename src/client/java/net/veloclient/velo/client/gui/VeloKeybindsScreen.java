package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.keybind.BindingAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Velo's Key Binds screen, shown instead of vanilla's (Options > Controls > Key Binds): every
 * binding grouped by category with icons, one search box (finds actions *and* keys - type "G" to
 * see what G does), a filter for conflicts, and rebinding right in the row. Conflicting bindings
 * are marked red and name what they clash with. The list is a tree of collapsible categories (searching
 * opens the matches); the keyboard map is the other view. "Vanilla view" opens Minecraft's own screen once.
 */
public final class VeloKeybindsScreen extends VeloWindow {

	private static boolean openVanillaOnce;
	private static Screen lastOther;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget search;
	private boolean conflictsOnly;
	private KeyBinding listening;
	private float scroll;
	/** Where scrolling is heading; {@link #scroll} eases toward it each frame. Always within [0, maxScroll]. */
	private float scrollTarget;
	private float maxScroll;
	private long lastFrameNanos;
	private String status = "";
	/** Keyboard map or the searchable list (default). */
	private static boolean mapView = false;
	/** Open groups in the list (remembered while the game runs). */
	private static final java.util.Set<String> EXPANDED = new java.util.HashSet<>();
	/** How far open each group is (0..1), eased toward its target for the expand animation. */
	private final Map<String, Float> openness = new java.util.HashMap<>();
	private long lastAnimNanos;
	private final KeyboardMapView map = new KeyboardMapView();

	public VeloKeybindsScreen(Screen parent) {
		super(Text.literal("Controls"), 900, 520);
		returnTo(parent);
	}

	/** Remembers the last other screen (vanilla's Controls screen) and swaps vanilla's Key Binds screen for this one. */
	public static void maybeReplace(MinecraftClient client, Screen screen) {
		//? if <26.1 {
		boolean vanillaKeys = screen instanceof net.minecraft.client.gui.screen.option.KeybindsScreen;
		//?} else {
		/*boolean vanillaKeys = screen instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
		*///?}
		if (!vanillaKeys) {
			//? if <26.1 {
			boolean statsScreen = screen instanceof net.minecraft.client.gui.screen.StatsScreen;
			//?} else {
			/*boolean statsScreen = screen instanceof net.minecraft.client.gui.screens.achievement.StatsScreen;
			*///?}
			if (!(screen instanceof VeloKeybindsScreen) && !(screen instanceof VeloStatsScreen) && !statsScreen
					&& !(screen instanceof VeloAdvancementsScreen) && !(screen instanceof net.veloclient.velo.client.mixin.AdvancementsScreenAccessor)) {
				lastOther = screen;
			}
			return;
		}
		if (openVanillaOnce) {
			openVanillaOnce = false;
			return;
		}
		if (!VeloScreens.controls()) {
			return;
		}
		Screen parent = lastOther;
		client.execute(() -> client.setScreen(new VeloKeybindsScreen(parent)));
	}

	/** Dev-only (screenshot tour): open the list on Movement and Gameplay. */
	public static void expandForTour() {
		mapView = false;
		EXPANDED.add("minecraft:movement");
		EXPANDED.add("minecraft:gameplay");
	}

	/** The screen open before the current replacement (pause menu, Controls...), for returning to it. */
	static Screen lastOther() {
		return lastOther;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		String previous = search != null ? search.getText() : "";
		int searchWidth = Math.max(110, Math.min(240, contentWidth() - 136 - 280));
		search = new TextFieldWidget(this.textRenderer, contentX() + 22, contentY() + 6, searchWidth - 28, 12, Text.literal("Search"));
		search.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder("Search actions or keys"));
		search.setDrawsBackground(false);
		search.setText(previous);
		search.setChangedListener(v -> {
			scroll = 0;
			scrollTarget = 0;
		});
		addDrawableChild(search);
	}

	private static Identifier icon(String categoryId) {
		String name = switch (categoryId) {
			case "minecraft:movement" -> "movement";
			case "minecraft:gameplay" -> "gameplay";
			case "minecraft:inventory" -> "inventory";
			case "minecraft:creative" -> "creative";
			case "minecraft:multiplayer" -> "multiplayer";
			case "minecraft:misc" -> "misc";
			case "minecraft:debug" -> "debug";
			case "minecraft:spectator" -> "spectator";
			default -> categoryId.startsWith("velo") ? "velo" : "other";
		};
		return Identifier.of("velo-client", "textures/icon/keys/" + name + ".png");
	}

	private static void drawIcon(DrawContext context, Identifier icon, int x, int y, int size, int color) {
		context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, icon, x, y, 0f, 0f, size, size, 56, 56, 56, 56, color);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		int searchW = search == null ? 200 : search.getWidth() + 28;
		boolean focused = search != null && search.isFocused();
		VeloStyle.drawInputField(context, contentX(), contentY(), searchW, 24, focused,
				VeloUi.inside(mouseX, mouseY, contentX(), contentY(), searchW, 24));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		stepScroll();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		List<KeyBinding> all = BindingAccess.all();
		int conflictCount = (int) all.stream().filter(b -> !BindingAccess.conflicts(b).isEmpty()).count();

		// Top bar: search, keyboard/list, conflicts, reset all, vanilla view.
		// Right to left: Vanilla view, Reset all, Conflicts; Keyboard / List right after the search box.
		int searchW = search == null ? 200 : search.getWidth() + 28;
		boolean focused = search != null && search.isFocused();
		// (The field's box is drawn under the text box, in renderContentLayer - drawn here it covered the text.)
		VeloDraw.searchGlyph(context, x + 11, y + 12, 10, MenuKit.TEXT_3);
		// List / Keyboard: a segmented switch.
		int segX = x + searchW + 8;
		int segW = 64;
		VeloDraw.fillRounded(context, segX, y, segW * 2, 24, 7, 0xFF131318);
		VeloDraw.strokeRounded(context, segX, y, segW * 2, 24, 7, 0x17FFFFFF);
		VeloDraw.fillRounded(context, segX + 2 + (mapView ? segW : 0), y + 2, segW - 4, 20, 5, VeloUi.withAlpha(MenuKit.accent(), 0x40));
		for (int i = 0; i < 2; i++) {
			Text label = MenuKit.ui(i == 0 ? "List" : "Keyboard");
			boolean on = (i == 1) == mapView;
			MenuKit.text(context, this.textRenderer, label, segX + i * segW + (segW - this.textRenderer.getWidth(label)) / 2, y + 8, on ? MenuKit.TEXT : MenuKit.TEXT_3);
			boolean map = i == 1;
			hits.add(new VeloUi.Hit(segX + i * segW, y, segW, 24, () -> mapView = map));
		}
		hits.add(VeloUi.pill(context, x + width - 76, y, 76, 24, "Vanilla view", 0, mouseX, mouseY, this::openVanilla));
		hits.add(VeloUi.pill(context, x + width - 76 - 6 - 70, y, 70, 24, "Reset all", 0, mouseX, mouseY, () ->
				this.client.setScreen(new VeloConfirmScreen(this, "Reset every key bind?", "All keys go back to Minecraft's and Velo's defaults.",
						"Reset all", () -> {
							for (KeyBinding binding : BindingAccess.all()) {
								BindingAccess.reset(binding);
							}
							status = "All key binds reset";
						}))));
		hits.add(VeloUi.pill(context, x + width - 76 - 6 - 70 - 6 - 96, y, 96, 24, conflictCount == 0 ? "No conflicts" : "Conflicts (" + conflictCount + ")",
				conflictsOnly ? 3 : 0, mouseX, mouseY, () -> {
					if (mapView) {
						// Jump to the next key two actions fight over.
						all.stream().filter(b -> !BindingAccess.conflicts(b).isEmpty()).findFirst()
								.ifPresent(b -> map.select(BindingAccess.keyId(b)));
						return;
					}
					conflictsOnly = !conflictsOnly;
					scroll = 0;
					scrollTarget = 0;
				}));

		if (mapView) {
			map.render(context, this.textRenderer, x, y + 32, width, contentBottom() - 14 - (y + 32), mouseX, mouseY, hits, new KeyboardMapView.Host() {
				@Override
				public void listen(KeyBinding binding) {
					listening = binding;
					status = "";
				}

				@Override
				public void status(String text) {
					status = text;
				}

				@Override
				public String query() {
					return search == null ? "" : search.getText();
				}
			});
			String footer = listening != null
					? "Press a key or mouse button for \"" + BindingAccess.name(listening) + "\" - Esc cancels, Backspace unbinds"
					: status;
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(footer), width), x, contentBottom() - 9,
					listening != null ? MenuKit.accent() : MenuKit.TEXT_3);
			return;
		}

		// The list: one collapsible group per category, like a file tree. Searching or the conflicts
		// filter opens every group that has a match.
		Map<String, String> categories = new LinkedHashMap<>();
		for (KeyBinding binding : all) {
			categories.putIfAbsent(BindingAccess.categoryId(binding), BindingAccess.categoryLabel(binding));
		}
		long now = System.nanoTime();
		float dt = lastAnimNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastAnimNanos) / 1_000_000_000f);
		lastAnimNanos = now;
		int top = y + 32;
		int bottom = contentBottom() - 14;
		String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
		boolean filtering = !q.isEmpty() || conflictsOnly;
		// Expand / collapse all, top right of the tree.
		boolean anyOpen = !EXPANDED.isEmpty();
		context.enableScissor(x, top, x + width, bottom);
		int rowY = top - Math.round(scroll);
		int contentHeight = 0;
		for (Map.Entry<String, String> entry : categories.entrySet()) {
			String id = entry.getKey();
			// Searching for a category ("movement", "velo") shows that whole category, opened.
			boolean categoryMatches = !q.isEmpty() && entry.getValue().toLowerCase(Locale.ROOT).contains(q);
			List<KeyBinding> rows = new ArrayList<>();
			int groupConflicts = 0;
			for (KeyBinding binding : all) {
				if (!BindingAccess.categoryId(binding).equals(id)) {
					continue;
				}
				boolean conflict = !BindingAccess.conflicts(binding).isEmpty();
				if (conflict) {
					groupConflicts++;
				}
				if (conflictsOnly && !conflict) {
					continue;
				}
				if (!q.isEmpty() && !categoryMatches && !BindingAccess.name(binding).toLowerCase(Locale.ROOT).contains(q)
						&& !BindingAccess.keyText(binding).toLowerCase(Locale.ROOT).contains(q)) {
					continue;
				}
				rows.add(binding);
			}
			if (rows.isEmpty()) {
				continue;
			}
			boolean open = filtering || EXPANDED.contains(id);
			float t = openness.getOrDefault(id, open ? 1f : 0f);
			t = net.veloclient.velo.client.gui.widget.VeloAnim.step(t, open ? 1f : 0f, dt * 2.4f);
			openness.put(id, t);
			float eased = 1f - (1f - t) * (1f - t) * (1f - t);

			// Group header.
			boolean headerHover = VeloUi.inside(mouseX, mouseY, x, rowY, width, 28) && mouseY >= top && mouseY < bottom;
			VeloDraw.fillRounded(context, x, rowY, width, 28, 8, headerHover ? 0xFF1A1A21 : 0xFF131318);
			VeloDraw.strokeRounded(context, x, rowY, width, 28, 8, headerHover ? 0x26FFFFFF : 0x14FFFFFF);
			rotatingChevron(context, x + 14, rowY + 14, 7f, eased, open ? MenuKit.TEXT : MenuKit.TEXT_3);
			VeloDraw.fillRounded(context, x + 26, rowY + 5, 18, 18, 5, VeloUi.withAlpha(MenuKit.accent(), 0x26));
			drawIcon(context, icon(id), x + 29, rowY + 8, 12, MenuKit.accent());
			Text label = MenuKit.uiBold(entry.getValue());
			MenuKit.text(context, this.textRenderer, label, x + 52, rowY + 10, MenuKit.TEXT);
			Text count = MenuKit.small(String.valueOf(rows.size()));
			int countX = x + 52 + this.textRenderer.getWidth(label) + 8;
			int cw = this.textRenderer.getWidth(count) + 10;
			VeloDraw.fillRounded(context, countX, rowY + 8, cw, 12, 6, 0x14FFFFFF);
			MenuKit.text(context, this.textRenderer, count, countX + 5, rowY + 10, MenuKit.TEXT_2);
			if (groupConflicts > 0) {
				Text warn = MenuKit.small(groupConflicts + (groupConflicts == 1 ? " CONFLICT" : " CONFLICTS"));
				int ww = this.textRenderer.getWidth(warn) + 10;
				VeloDraw.fillRounded(context, x + width - ww - 10, rowY + 8, ww, 12, 4, 0x33FF5A5A);
				MenuKit.text(context, this.textRenderer, warn, x + width - ww - 5, rowY + 10, 0xFFFF8A8A);
			}
			if (rowY + 28 > top && rowY < bottom && !filtering) {
				int hy = Math.max(rowY, top);
				hits.add(new VeloUi.Hit(x, hy, width, Math.min(rowY + 28, bottom) - hy, () -> {
					if (!EXPANDED.remove(id)) {
						EXPANDED.add(id);
					}
				}));
			}
			rowY += 32;
			contentHeight += 32;

			// Its bindings, sliding open under a tree guide line.
			int full = rows.size() * 24 + 4;
			int shown = Math.round(full * eased);
			if (shown > 0) {
				context.enableScissor(x, Math.max(top, rowY - 4), x + width, Math.min(bottom, rowY + shown));
				VeloDraw.fillRounded(context, x + 13, rowY - 4, 1, Math.max(1, rows.size() * 24 - 10), 0, 0x1FFFFFFF);
				int childY = rowY;
				for (KeyBinding binding : rows) {
					context.fill(x + 14, childY + 11, x + 22, childY + 12, 0x1FFFFFFF);
					drawRow(context, binding, x + 24, childY, width - 24, mouseX, mouseY, Math.max(top, rowY), Math.min(bottom, rowY + shown));
					childY += 24;
				}
				context.disableScissor();
				rowY += shown;
				contentHeight += shown;
			}
		}
		if (contentHeight == 0) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(conflictsOnly ? "No conflicts - every key does one thing." : "Nothing matches \"" + q + "\""),
					x + 4, top + 6, MenuKit.TEXT_3);
		}
		context.disableScissor();
		maxScroll = Math.max(0, contentHeight - (bottom - top));
		if (!filtering) {
			Text all2 = MenuKit.ui(anyOpen ? "Collapse all" : "Expand all");
			int aw = this.textRenderer.getWidth(all2);
			boolean hover = VeloUi.inside(mouseX, mouseY, x + width - aw, contentBottom() - 11, aw, 11);
			MenuKit.text(context, this.textRenderer, all2, x + width - aw, contentBottom() - 9, hover ? MenuKit.TEXT : MenuKit.TEXT_3);
			hits.add(new VeloUi.Hit(x + width - aw, contentBottom() - 11, aw, 11, () -> {
				if (anyOpen) {
					EXPANDED.clear();
				} else {
					EXPANDED.addAll(categories.keySet());
				}
			}));
		}

		String footer = listening != null
				? "Press a key or mouse button for \"" + BindingAccess.name(listening) + "\" - Esc cancels, Backspace unbinds"
				: status;
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(footer), width - 90), x, contentBottom() - 9,
				listening != null ? MenuKit.accent() : MenuKit.TEXT_3);
	}

	/** A chevron that turns from right (closed) to down (open) as {@code t} goes 0..1. */
	private static void rotatingChevron(DrawContext context, float cx, float cy, float size, float t, int color) {
		double a = Math.toRadians(90 * t);
		float h = size / 2f;
		float q = size / 4f;
		float[][] pts = {{-q, -h}, {q, 0}, {-q, h}};
		float[][] r = new float[3][2];
		for (int i = 0; i < 3; i++) {
			r[i][0] = cx + (float) (pts[i][0] * Math.cos(a) - pts[i][1] * Math.sin(a));
			r[i][1] = cy + (float) (pts[i][0] * Math.sin(a) + pts[i][1] * Math.cos(a));
		}
		VeloDraw.line(context, r[0][0], r[0][1], r[1][0], r[1][1], 1.4f, color);
		VeloDraw.line(context, r[1][0], r[1][1], r[2][0], r[2][1], 1.4f, color);
	}

	private void drawRow(DrawContext context, KeyBinding binding, int x, int y, int width, int mouseX, int mouseY, int top, int bottom) {
		List<KeyBinding> conflicts = BindingAccess.conflicts(binding);
		boolean conflict = !conflicts.isEmpty();
		boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, width, 22) && mouseY >= top && mouseY < bottom;
		if (hovered || conflict) {
			VeloDraw.fillRounded(context, x, y, width, 22, 6, conflict ? 0x14FF5A5A : 0x0DFFFFFF);
		}
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(BindingAccess.name(binding)), width / 2 - 10), x + 8, y + 7, MenuKit.TEXT);
		if (conflict) {
			String with = "Same key as " + BindingAccess.name(conflicts.get(0)) + (conflicts.size() > 1 ? " +" + (conflicts.size() - 1) : "");
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(with), width / 2 - 150), x + width / 2, y + 7, 0xFFFF8A8A);
		}
		boolean isListening = binding == listening;
		String key = isListening ? "Press a key..." : BindingAccess.isUnbound(binding) ? "Not bound" : BindingAccess.keyText(binding);
		int keyW = 110;
		int keyX = x + width - keyW - 30;
		boolean visible = y + 22 > top && y < bottom;
		VeloUi.Hit keyHit = VeloUi.pill(context, keyX, y + 2, keyW, 18, key, isListening ? 1 : conflict ? 3 : 0, mouseX, mouseY, () -> {
			listening = binding;
			status = "";
		});
		if (visible) {
			hits.add(keyHit);
		}
		if (!BindingAccess.isDefault(binding)) {
			// Back to the default key.
			boolean resetHover = VeloUi.inside(mouseX, mouseY, x + width - 24, y + 2, 18, 18);
			VeloDraw.fillRounded(context, x + width - 24, y + 2, 18, 18, 6, resetHover ? 0x26FFFFFF : 0x0DFFFFFF);
			MenuKit.icon(context, "reset", x + width - 20, y + 6, 10, resetHover ? MenuKit.TEXT : MenuKit.TEXT_3);
			if (visible) {
				hits.add(new VeloUi.Hit(x + width - 24, y + 2, 18, 18, () -> {
					BindingAccess.reset(binding);
					status = "Reset \"" + BindingAccess.name(binding) + "\" to " + BindingAccess.keyText(binding);
				}));
			}
		}
	}

	private void openVanilla() {
		openVanillaOnce = true;
		//? if <26.1 {
		MinecraftClient.getInstance().setScreen(new net.minecraft.client.gui.screen.option.KeybindsScreen(this, MinecraftClient.getInstance().options));
		//?} else {
		/*this.client.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(this, this.client.options));
		*///?}
	}

	// ---- Input ----

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (listening != null) {
			KeyBinding binding = listening;
			listening = null;
			if (input.key() == net.veloclient.velo.client.util.Keys.ESCAPE) {
				status = "Cancelled";
			} else if (input.key() == net.veloclient.velo.client.util.Keys.BACKSPACE || input.key() == net.veloclient.velo.client.util.Keys.DELETE) {
				BindingAccess.unbind(binding);
				status = "\"" + BindingAccess.name(binding) + "\" is now unbound";
			} else {
				BindingAccess.bindKeyboard(binding, input.key());
				status = "\"" + BindingAccess.name(binding) + "\" = " + BindingAccess.keyText(binding);
			}
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (listening != null) {
			KeyBinding binding = listening;
			listening = null;
			BindingAccess.bindMouse(binding, click.button());
			status = "\"" + BindingAccess.name(binding) + "\" = " + BindingAccess.keyText(binding);
			return true;
		}
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scrollTarget = Math.clamp(scrollTarget - (float) verticalAmount * 36, 0, maxScroll);
		return true;
	}

	/** Eases {@link #scroll} toward the target - smooth, and it can never run past either end. */
	private void stepScroll() {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000f);
		lastFrameNanos = now;
		scrollTarget = Math.clamp(scrollTarget, 0, maxScroll);
		scroll += (scrollTarget - scroll) * Math.min(1f, dt * 16f);
		if (Math.abs(scrollTarget - scroll) < 0.3f) {
			scroll = scrollTarget;
		}
	}
}
