package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.keybind.BindingAccess;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Velo's Key Binds screen, shown instead of vanilla's (Options > Controls > Key Binds): every
 * binding grouped by category with icons, one search box (finds actions *and* keys - type "G" to
 * see what G does), a filter for conflicts, and rebinding right in the row. Conflicting bindings
 * are marked red and name what they clash with. "Vanilla view" opens Minecraft's own screen once.
 */
public final class VeloKeybindsScreen extends VeloWindow {

	private static boolean openVanillaOnce;
	private static Screen lastOther;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget search;
	private String category = null;
	private boolean conflictsOnly;
	private KeyBinding listening;
	private float scroll;
	/** Where scrolling is heading; {@link #scroll} eases toward it each frame. Always within [0, maxScroll]. */
	private float scrollTarget;
	private float maxScroll;
	private long lastFrameNanos;
	private String status = "";

	public VeloKeybindsScreen(Screen parent) {
		super(Text.literal("Controls"), 720, 460);
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

	/** The screen open before the current replacement (pause menu, Controls...), for returning to it. */
	static Screen lastOther() {
		return lastOther;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		String previous = search != null ? search.getText() : "";
		search = new TextFieldWidget(this.textRenderer, contentX(), contentY(), 200, 16, Text.literal("Search"));
		search.setPlaceholder(Text.literal("Search actions or keys..."));
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
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		stepScroll();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		List<KeyBinding> all = BindingAccess.all();
		int conflictCount = (int) all.stream().filter(b -> !BindingAccess.conflicts(b).isEmpty()).count();

		// Top bar: search, conflicts filter, reset all, vanilla view.
		hits.add(VeloUi.pill(context, x + 208, y, 110, 16, "Conflicts (" + conflictCount + ")", conflictsOnly ? 3 : 0, mouseX, mouseY,
				() -> {
					conflictsOnly = !conflictsOnly;
					scroll = 0;
				scrollTarget = 0;
				}));
		hits.add(VeloUi.pill(context, x + width - 72, y, 72, 16, "Vanilla view", 0, mouseX, mouseY, this::openVanilla));
		hits.add(VeloUi.pill(context, x + width - 150, y, 72, 16, "Reset all", 0, mouseX, mouseY, () ->
				this.client.setScreen(new VeloConfirmScreen(this, "Reset every key bind?", "All keys go back to Minecraft's and Velo's defaults.",
						"Reset all", () -> {
							for (KeyBinding binding : BindingAccess.all()) {
								BindingAccess.reset(binding);
							}
							status = "All key binds reset";
						}))));

		// Category chips.
		Map<String, String> categories = new LinkedHashMap<>();
		for (KeyBinding binding : all) {
			categories.putIfAbsent(BindingAccess.categoryId(binding), BindingAccess.categoryLabel(binding));
		}
		int chipX = x;
		int chipY = y + 22;
		chipX = chip(context, chipX, chipY, null, "All", mouseX, mouseY);
		for (Map.Entry<String, String> entry : categories.entrySet()) {
			int w = this.textRenderer.getWidth(entry.getValue()) + 30;
			if (chipX + w > x + width) {
				chipX = x;
				chipY += 20;
			}
			chipX = chip(context, chipX, chipY, entry.getKey(), entry.getValue(), mouseX, mouseY);
		}

		// List.
		int top = chipY + 24;
		int bottom = contentBottom() - 14;
		String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
		context.enableScissor(x, top, x + width, bottom);
		int rowY = top - Math.round(scroll);
		int contentHeight = 0;
		for (Map.Entry<String, String> entry : categories.entrySet()) {
			if (category != null && !category.equals(entry.getKey())) {
				continue;
			}
			List<KeyBinding> rows = new ArrayList<>();
			for (KeyBinding binding : all) {
				if (!BindingAccess.categoryId(binding).equals(entry.getKey())) {
					continue;
				}
				if (conflictsOnly && BindingAccess.conflicts(binding).isEmpty()) {
					continue;
				}
				if (!q.isEmpty() && !BindingAccess.name(binding).toLowerCase(Locale.ROOT).contains(q)
						&& !BindingAccess.keyText(binding).toLowerCase(Locale.ROOT).equals(q)
						&& !BindingAccess.keyText(binding).toLowerCase(Locale.ROOT).contains(q)) {
					continue;
				}
				rows.add(binding);
			}
			if (rows.isEmpty()) {
				continue;
			}
			drawIcon(context, icon(entry.getKey()), x + 2, rowY + 2, 12, VeloStyle.accent());
			context.drawTextWithShadow(this.textRenderer, entry.getValue().toUpperCase(Locale.ROOT), x + 20, rowY + 4, VeloStyle.textMuted());
			rowY += 18;
			contentHeight += 18;
			for (KeyBinding binding : rows) {
				drawRow(context, binding, x, rowY, width, mouseX, mouseY, top, bottom);
				rowY += 24;
				contentHeight += 24;
			}
			rowY += 6;
			contentHeight += 6;
		}
		if (contentHeight == 0) {
			context.drawTextWithShadow(this.textRenderer, conflictsOnly ? "No conflicts - every key does one thing." : "Nothing matches \"" + q + "\"",
					x + 4, top + 6, VeloStyle.textMuted());
		}
		context.disableScissor();
		float max = Math.max(0, contentHeight - (bottom - top));
		maxScroll = max;

		String footer = listening != null
				? "Press a key or mouse button for \"" + BindingAccess.name(listening) + "\" - Esc cancels, Backspace unbinds"
				: status;
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(footer, width), x, contentBottom() - 9,
				listening != null ? VeloStyle.accent() : VeloStyle.textMuted());
	}

	private int chip(DrawContext context, int x, int y, String id, String label, int mouseX, int mouseY) {
		boolean active = id == null ? category == null : id.equals(category);
		int w = this.textRenderer.getWidth(label) + (id == null ? 16 : 30);
		hits.add(VeloUi.pill(context, x, y, w, 16, id == null ? label : "", active ? 1 : 0, mouseX, mouseY, () -> {
			category = id;
			scroll = 0;
				scrollTarget = 0;
		}));
		if (id != null) {
			drawIcon(context, icon(id), x + 6, y + 3, 10, active ? 0xFFFFFFFF : VeloStyle.textMuted());
			context.drawTextWithShadow(this.textRenderer, label, x + 20, y + 4, active ? 0xFFFFFFFF : VeloStyle.text());
		}
		return x + w + 4;
	}

	private void drawRow(DrawContext context, KeyBinding binding, int x, int y, int width, int mouseX, int mouseY, int top, int bottom) {
		List<KeyBinding> conflicts = BindingAccess.conflicts(binding);
		boolean conflict = !conflicts.isEmpty();
		boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, width, 22) && mouseY >= top && mouseY < bottom;
		VeloDraw.fillRounded(context, x, y, width, 22, 7, hovered ? VeloStyle.cardHover() : VeloStyle.card());
		if (conflict) {
			VeloDraw.strokeRounded(context, x, y, width, 22, 7, 0xC0E05555);
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(BindingAccess.name(binding), width / 2 - 10), x + 10, y + 7, VeloStyle.text());
		if (conflict) {
			String with = "Same key as " + BindingAccess.name(conflicts.get(0)) + (conflicts.size() > 1 ? " +" + (conflicts.size() - 1) : "");
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(with, width / 2 - 150), x + width / 2, y + 7, 0xFFFF7A7A);
		}
		boolean isListening = binding == listening;
		String key = isListening ? "> press a key <" : BindingAccess.isUnbound(binding) ? "Not bound" : BindingAccess.keyText(binding);
		int keyW = 110;
		int keyX = x + width - keyW - 34;
		boolean visible = y + 22 > top && y < bottom;
		VeloUi.Hit keyHit = VeloUi.pill(context, keyX, y + 3, keyW, 16, key, isListening ? 1 : conflict ? 3 : 0, mouseX, mouseY, () -> {
			listening = binding;
			status = "";
		});
		if (visible) {
			hits.add(keyHit);
		}
		if (!BindingAccess.isDefault(binding)) {
			VeloUi.Hit reset = VeloUi.pill(context, x + width - 28, y + 3, 22, 16, "R", 0, mouseX, mouseY, () -> {
				BindingAccess.reset(binding);
				status = "Reset \"" + BindingAccess.name(binding) + "\" to " + BindingAccess.keyText(binding);
			});
			if (visible) {
				hits.add(reset);
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
			if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
				status = "Cancelled";
			} else if (input.key() == GLFW.GLFW_KEY_BACKSPACE || input.key() == GLFW.GLFW_KEY_DELETE) {
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
