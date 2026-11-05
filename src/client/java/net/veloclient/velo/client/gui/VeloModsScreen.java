package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.mods.ModConfigs;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Velo's own Mods page (replaces Mod Menu): every installed mod with its icon, version, authors and what it does; its
 * settings screen one click away (from the mod's Mod Menu integration, which Velo serves itself - {@link
 * net.veloclient.velo.client.mods.ModMenuApiShim}); and a switch to turn it off for the next start. Libraries are
 * folded away unless asked for.
 */
public final class VeloModsScreen extends VeloWindow implements net.veloclient.velo.client.gui.window.MenuPage {

	private static final int ROW = 38;
	private static final Map<String, Identifier> ICONS = new HashMap<>();
	private static final Map<String, Integer> ICON_SIZES = new HashMap<>();

	private enum Filter { ALL, SETTINGS, OFF, LIBRARIES }

	private static Filter filter = Filter.ALL;
	private TextFieldWidget searchField;
	private String search = "";
	private double scroll;
	private String feedback = "";
	private long feedbackUntil;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final List<ModConfigs.Mod> mods = ModConfigs.mods();

	public VeloModsScreen(Screen parent) {
		super(Text.literal("Mods"), 640, 420);
		returnTo(parent);
	}

	@Override
	public String railId() {
		return "mods";
	}

	@Override
	public int shellMaxContentWidth() {
		return 720;
	}

	@Override
	protected void layoutContent() {
		if (searchField != null) {
			search = searchField.getText();
		}
		this.clearChildren();
		searchField = new TextFieldWidget(this.textRenderer, contentX() + 20, contentY() + 3, 180, 14, Text.literal("Search"));
		searchField.setDrawsBackground(false);
		searchField.setPlaceholder(VeloUi.placeholder(VeloUi.trim("Search mods...", 120)));
		searchField.setText(search);
		searchField.setChangedListener(value -> scroll = 0);
		addDrawableChild(searchField);
	}

	private List<ModConfigs.Mod> shown() {
		String q = searchField == null ? search : searchField.getText();
		String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
		List<ModConfigs.Mod> out = new ArrayList<>();
		for (ModConfigs.Mod mod : mods) {
			boolean match = needle.isEmpty() || mod.name().toLowerCase(Locale.ROOT).contains(needle) || mod.id().contains(needle)
					|| mod.description().toLowerCase(Locale.ROOT).contains(needle);
			if (!match) {
				continue;
			}
			boolean keep = switch (filter) {
				case ALL -> !mod.library() || !needle.isEmpty();
				case SETTINGS -> ModConfigs.configFor(mod.id()) != null;
				case OFF -> !ModConfigs.enabledNextStart(mod);
				case LIBRARIES -> mod.library();
			};
			if (keep) {
				out.add(mod);
			}
		}
		return out;
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		Theme theme = ThemeManager.active();
		int x = contentX();
		int width = contentWidth();
		int y = contentY();

		// Search + folder.
		int folderW = Math.min(120, width / 4);
		int searchW = width - folderW - 6;
		net.veloclient.velo.client.gui.widget.VeloStyle.drawInputField(context, x, y, searchW, 20, searchField.isFocused(),
				VeloUi.inside(mouseX, mouseY, x, y, searchW, 20));
		searchField.setWidth(Math.max(20, searchW - 26));
		VeloDraw.searchGlyph(context, x + 10f, y + 10f, 9f, VeloUi.muted());
		hits.add(VeloUi.pill(context, x + searchW + 6, y, folderW, 20, "Open mods folder", 0, mouseX, mouseY,
				() -> FileManagerOpener.open(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods").toFile(), this::flash)));

		// Filters.
		int chipY = y + 28;
		int chipX = x;
		long settings = mods.stream().filter(m -> ModConfigs.configFor(m.id()) != null).count();
		long libs = mods.stream().filter(ModConfigs.Mod::library).count();
		String[] labels = {"All  " + (mods.size() - libs), "With settings  " + settings, "Off", "Libraries  " + libs};
		for (Filter f : Filter.values()) {
			String label = labels[f.ordinal()];
			int w = this.textRenderer.getWidth(label) + 20;
			boolean active = f == filter;
			boolean hovered = VeloUi.inside(mouseX, mouseY, chipX, chipY, w, 18);
			VeloDraw.fillRounded(context, chipX, chipY, w, 18, 9, active ? theme.accentStart() : hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x18)
					: VeloUi.withAlpha(0xFF000000, 0x40));
			context.drawTextWithShadow(this.textRenderer, label, chipX + 10, chipY + 5, active ? theme.onAccent() : theme.text());
			hits.add(new VeloUi.Hit(chipX, chipY, w, 18, () -> {
				filter = f;
				scroll = 0;
			}));
			chipX += w + 4;
		}

		// List.
		int listTop = chipY + 26;
		int listBottom = contentBottom() - 18;
		List<ModConfigs.Mod> list = shown();
		int contentHeight = list.size() * (ROW + 3);
		int listHeight = listBottom - listTop;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - listHeight)));
		if (list.isEmpty()) {
			String empty = filter == Filter.OFF ? "No mods switched off." : "No mods match \"" + search + "\".";
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(empty), x + width / 2, listTop + 30, VeloUi.muted());
		}
		context.enableScissor(x, listTop, x + width, listBottom);
		int cursor = listTop - (int) scroll;
		for (ModConfigs.Mod mod : list) {
			if (cursor + ROW >= listTop && cursor <= listBottom) {
				drawRow(context, mod, x, cursor, width - 6, mouseX, mouseY, listTop, listBottom);
			}
			cursor += ROW + 3;
		}
		context.disableScissor();
		if (contentHeight > listHeight) {
			int thumb = Math.max(14, listHeight * listHeight / contentHeight);
			int thumbY = listTop + (int) ((listHeight - thumb) * (scroll / Math.max(1, contentHeight - listHeight)));
			context.fill(x + width - 3, thumbY, x + width - 1, thumbY + thumb, VeloUi.withAlpha(theme.accentStart(), 0xAA));
		}

		// Footer.
		String footer = System.currentTimeMillis() < feedbackUntil ? feedback
				: ModConfigs.restartNeeded() ? "Changes apply when you restart the game."
				: list.size() + " mod" + (list.size() == 1 ? "" : "s") + "  -  everything Mod Menu did, built into Velo";
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(footer, width), x, contentBottom() - 10,
				ModConfigs.restartNeeded() ? theme.accentStart() : VeloUi.muted());
	}

	private void drawRow(DrawContext context, ModConfigs.Mod mod, int x, int y, int width, int mouseX, int mouseY, int clipTop, int clipBottom) {
		Theme theme = ThemeManager.active();
		boolean rowVisible = mouseY >= clipTop && mouseY < clipBottom;
		boolean hovered = rowVisible && VeloUi.inside(mouseX, mouseY, x, y, width, ROW);
		boolean on = ModConfigs.enabledNextStart(mod);
		VeloDraw.fillRounded(context, x, y, width, ROW, 7, hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x14) : VeloUi.withAlpha(0xFFFFFFFF, 0x08));

		// Icon.
		int iconX = x + 8;
		int iconY = y + (ROW - 26) / 2;
		VeloDraw.fillRounded(context, iconX, iconY, 26, 26, 6, VeloUi.withAlpha(0xFF000000, 0x50));
		Identifier icon = icon(mod);
		if (icon != null) {
			int size = ICON_SIZES.getOrDefault(mod.id(), 64);
			context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, iconX + 1, iconY + 1, 0f, 0f, 24, 24, size, size, size, size,
					on ? 0xFFFFFFFF : 0x80FFFFFF);
		} else {
			String letter = mod.name().isEmpty() ? "?" : mod.name().substring(0, 1).toUpperCase(Locale.ROOT);
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(letter), iconX + 13 - this.textRenderer.getWidth(letter) / 2, iconY + 9, theme.text());
		}

		// Actions on the right.
		var config = ModConfigs.configFor(mod.id());
		int right = x + width - 8;
		boolean toggleable = mod.jar() != null && !mod.core();
		if (toggleable) {
			int trackX = right - 26;
			int trackY = y + (ROW - 14) / 2;
			VeloDraw.fillRounded(context, trackX, trackY, 26, 14, 7, on ? theme.accentStart() : 0xFF3A3A40);
			VeloDraw.fillRounded(context, on ? trackX + 14 : trackX + 2, trackY + 2, 10, 10, 5, 0xFFFFFFFF);
			if (rowVisible) {
				hits.add(new VeloUi.Hit(trackX - 3, y, 32, ROW, () -> {
					String error = ModConfigs.setEnabled(mod, !on);
					flash(error != null ? error : (on ? mod.name() + " is off from the next start" : mod.name() + " is back on from the next start"));
				}));
			}
			right = trackX - 8;
		}
		if (config != null) {
			int w = 62;
			VeloUi.Hit open = VeloUi.pill(context, right - w, y + (ROW - 18) / 2, w, 18, "Settings", 1, mouseX, mouseY, () -> {
				Screen screen = config.apply(this);
				if (screen != null) {
					this.client.setScreen(screen);
				} else {
					flash(mod.name() + "'s settings didn't open");
				}
			});
			if (rowVisible) {
				hits.add(open);
			}
			right -= w + 6;
		}

		// Name, version, what it does.
		int textX = iconX + 34;
		int textW = Math.max(40, right - textX - 4);
		String title = mod.name() + "  " + mod.version();
		int nameColor = on ? theme.text() : VeloUi.muted();
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(mod.name()), textW), textX, y + 6, nameColor);
		int nameW = Math.min(textW, this.textRenderer.getWidth(MenuKit.uiBold(mod.name())));
		if (nameW + 40 < textW) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(mod.version()), textW - nameW - 8), textX + nameW + 6, y + 6,
					VeloUi.muted());
		}
		String line = mod.description().isBlank() ? (mod.authors().isBlank() ? mod.id() : "by " + mod.authors())
				: mod.description().replace('\n', ' ');
		if (!on) {
			line = "Off from the next start  -  " + line;
		}
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(line), textW), textX, y + 20, VeloUi.muted());
		if (hovered && !title.isEmpty() && !mod.authors().isBlank()) {
			// authors in the row's own space only while hovered (keeps the list calm)
			int aw = this.textRenderer.getWidth("by " + mod.authors());
			if (aw < textW - nameW - 60) {
				MenuKit.text(context, this.textRenderer, MenuKit.ui("by " + mod.authors()), textX + textW - aw, y + 6, VeloUi.muted());
			}
		}
	}

	/** The mod's own icon from its jar, as a texture (once per mod). */
	private static Identifier icon(ModConfigs.Mod mod) {
		if (mod.iconPath() == null) {
			return null;
		}
		if (ICONS.containsKey(mod.id())) {
			return ICONS.get(mod.id());
		}
		Identifier id = null;
		try {
			var path = mod.container().findPath(mod.iconPath()).orElse(null);
			if (path != null && Files.isRegularFile(path)) {
				try (InputStream in = Files.newInputStream(path)) {
					NativeImage image = NativeImage.read(in);
					ICON_SIZES.put(mod.id(), image.getWidth());
					String key = mod.id().replaceAll("[^a-z0-9_.-]", "_");
					id = Identifier.of("velo-client", "mod_icon/" + key);
					MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> key, image));
				}
			}
		} catch (Exception ignored) {
			// letter instead
		}
		ICONS.put(mod.id(), id);
		return id;
	}

	private void flash(String message) {
		feedback = message;
		feedbackUntil = System.currentTimeMillis() + 4000;
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
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= verticalAmount * 20;
		return true;
	}
}
