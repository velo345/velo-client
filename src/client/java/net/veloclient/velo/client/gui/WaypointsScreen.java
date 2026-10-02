package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.waypoints.Waypoint;
import net.veloclient.velo.client.waypoints.WaypointIcons;
import net.veloclient.velo.client.waypoints.WaypointManager;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The waypoints menu (default key P): pick a world (defaults to the one you're in), a tab per
 * dimension, search by name, and per waypoint: show/hide, edit (name, color, icon, position),
 * share with friends, delete. Also bulk-deletes this world's death waypoints.
 */
public final class WaypointsScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 30;
	private static final List<String> BASE_DIMENSIONS = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

	private String world;
	private String dimension;
	private TextFieldWidget searchField;
	private String search = "";
	private double scroll;
	private String confirmKey;
	private long confirmUntil;
	private String feedback = "";
	private long feedbackUntil;
	private final List<VeloUi.Hit> hits = new ArrayList<>();

	public WaypointsScreen(Screen parent) {
		super(Text.literal("Waypoints"), 600, 400);
		returnTo(parent);
		world = WaypointManager.currentWorldKey();
		if (world == null) {
			List<String> known = WaypointManager.knownWorlds();
			world = known.isEmpty() ? null : known.get(0);
		}
		String here = ClientCompat.dimensionId();
		dimension = here != null ? here : "minecraft:overworld";
	}

	@Override
	protected void layoutContent() {
		if (searchField != null) {
			search = searchField.getText();
		}
		this.clearChildren();
		searchField = new TextFieldWidget(this.textRenderer, contentX() + 20, contentY() + 31, 180, 14, Text.literal("Search"));
		searchField.setDrawsBackground(false);
		searchField.setPlaceholder(Text.literal(VeloUi.trim("Search waypoints...", 120)));
		searchField.setText(search);
		searchField.setChangedListener(value -> scroll = 0);
		addDrawableChild(searchField);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		Theme theme = ThemeManager.active();
		int x = contentX();
		int width = contentWidth();
		int y = contentY();

		// World picker.
		List<String> worlds = WaypointManager.knownWorlds();
		if (world != null && !worlds.contains(world)) {
			worlds = new ArrayList<>(worlds);
			worlds.add(world);
		}
		final List<String> worldList = worlds;
		int visibleWidth = Math.min(146, width / 3);
		int pickerWidth = width - visibleWidth - 58;
		hits.add(VeloUi.pill(context, x, y, 20, 20, "<", 0, mouseX, mouseY, () -> cycleWorld(worldList, -1)));
		VeloDraw.fillRounded(context, x + 24, y, pickerWidth, 20, 5, VeloUi.withAlpha(0xFF000000, 0x40));
		String current = WaypointManager.currentWorldKey();
		String worldLabel = world == null ? "No waypoints yet" : WaypointManager.worldName(world)
				+ (world.equals(current) ? "  - you are here" : "");
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(worldLabel, pickerWidth - 14), x + 32, y + 6, theme.text());
		hits.add(VeloUi.pill(context, x + 28 + pickerWidth, y, 20, 20, ">", 0, mouseX, mouseY, () -> cycleWorld(worldList, 1)));

		// Visibility master switch (the Waypoints module itself).
		boolean shown = ModuleRegistry.get("waypoints").map(m -> m.isEnabled()).orElse(true);
		hits.add(VeloUi.pill(context, x + width - visibleWidth, y, visibleWidth, 20, shown ? "Waypoints visible" : "Waypoints hidden", shown ? 1 : 0,
				mouseX, mouseY, () -> ModuleRegistry.get("waypoints").ifPresent(m -> m.setEnabled(!shown))));

		// Search + actions.
		int rowY = y + 28;
		int deaths = world == null ? 0 : (int) WaypointManager.inWorld(world).stream().filter(w -> w.death).count();
		int newWidth = Math.min(96, width / 4);
		int deathsWidth = deaths > 0 ? Math.min(130, width / 4) : 0;
		int searchWidth = width - newWidth - deathsWidth - (deaths > 0 ? 12 : 6);
		net.veloclient.velo.client.gui.widget.VeloStyle.drawInputField(context, x, rowY, searchWidth, 20, searchField.isFocused(),
				VeloUi.inside(mouseX, mouseY, x, rowY, searchWidth, 20));
		searchField.setWidth(Math.max(20, searchWidth - 26));
		VeloDraw.searchGlyph(context, x + 10f, rowY + 10f, 9f, VeloUi.muted());
		boolean inWorld = MinecraftClient.getInstance().player != null && current != null;
		VeloUi.Hit newHit = VeloUi.pill(context, x + searchWidth + 6, rowY, newWidth, 20, "+ New here", inWorld ? 1 : 0, mouseX, mouseY,
				() -> this.client.setScreen(WaypointEditScreen.createHere(this)));
		if (inWorld) {
			hits.add(newHit);
		}
		if (deaths > 0) {
			hits.add(confirmPill(context, x + width - deathsWidth, rowY + 2, deathsWidth, "Delete deaths (" + deaths + ")", "deaths", mouseX, mouseY, () -> {
				int removed = WaypointManager.deleteDeaths(world);
				flash("Deleted " + removed + " death waypoint" + (removed == 1 ? "" : "s"));
			}));
		}

		// Dimension tabs.
		int tabY = rowY + 28;
		List<String> dimensions = new ArrayList<>(BASE_DIMENSIONS);
		if (world != null) {
			for (Waypoint waypoint : WaypointManager.inWorld(world)) {
				if (!dimensions.contains(waypoint.dimension)) {
					dimensions.add(waypoint.dimension);
				}
			}
		}
		if (!dimensions.contains(dimension)) {
			dimensions.add(dimension);
		}
		int tabX = x;
		int maxTabWidth = (width - 4 * (dimensions.size() - 1)) / dimensions.size();
		for (String dim : dimensions) {
			int count = world == null ? 0 : WaypointManager.inWorldAndDimension(world, dim).size();
			String fullLabel = WaypointManager.dimensionLabel(dim) + (count > 0 ? "  " + count : "");
			int tabWidth = Math.min(this.textRenderer.getWidth(fullLabel) + 22, maxTabWidth);
			String label = VeloUi.trim(fullLabel, tabWidth - 20);
			boolean active = dim.equals(dimension);
			boolean hovered = VeloUi.inside(mouseX, mouseY, tabX, tabY, tabWidth, 20);
			int bg = active ? theme.accentStart() : hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x18) : VeloUi.withAlpha(0xFF000000, 0x40);
			VeloDraw.fillRounded(context, tabX, tabY, tabWidth, 20, 6, bg);
			VeloDraw.fillCircle(context, tabX + 8, tabY + 10, 2, dimensionColor(dim));
			context.drawTextWithShadow(this.textRenderer, label, tabX + 14, tabY + 6, active ? 0xFFFFFFFF : theme.text());
			hits.add(new VeloUi.Hit(tabX, tabY, tabWidth, 20, () -> {
				dimension = dim;
				scroll = 0;
			}));
			tabX += tabWidth + 4;
		}

		// Legacy import banner.
		int listTop = tabY + 28;
		if (WaypointManager.LEGACY_WORLD.equals(world) && current != null) {
			VeloDraw.fillRounded(context, x, listTop, width, 22, 5, VeloUi.withAlpha(theme.accentStart(), 0x40));
			int moveWidth = Math.min(160, width / 2);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("These came from the old waypoint system.", width - moveWidth - 16), x + 8, listTop + 7, theme.text());
			hits.add(VeloUi.pill(context, x + width - moveWidth - 6, listTop + 3, moveWidth, 16, "Move all to this world", 1, mouseX, mouseY, () -> {
				WaypointManager.moveWorld(WaypointManager.LEGACY_WORLD, current);
				world = current;
				flash("Moved to " + WaypointManager.worldName(current));
			}));
			listTop += 28;
		}

		// List.
		int listBottom = contentBottom() - 16;
		List<Waypoint> waypoints = filtered();
		int contentHeight = waypoints.size() * (ROW_HEIGHT + 3);
		int listHeight = listBottom - listTop;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - listHeight)));
		if (waypoints.isEmpty()) {
			String empty = world == null ? "Join a world to start placing waypoints."
					: !search.isBlank() ? "Nothing matches \"" + search + "\"."
					: "No waypoints in the " + WaypointManager.dimensionLabel(dimension) + " yet. Press \"+ New here\" or the New Waypoint key.";
			int lineY = listTop + 30;
			for (String line : VeloUi.wrap(empty, width - 60)) {
				context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), x + width / 2, lineY, VeloUi.muted());
				lineY += 11;
			}
		}
		context.enableScissor(x, listTop, x + width, listBottom);
		int cursor = listTop - (int) scroll;
		for (Waypoint waypoint : waypoints) {
			if (cursor + ROW_HEIGHT >= listTop && cursor <= listBottom) {
				drawRow(context, waypoint, x, cursor, width - 6, mouseX, mouseY, listTop, listBottom);
			}
			cursor += ROW_HEIGHT + 3;
		}
		context.disableScissor();
		if (contentHeight > listHeight) {
			int thumb = Math.max(14, listHeight * listHeight / contentHeight);
			int thumbY = listTop + (int) ((listHeight - thumb) * (scroll / Math.max(1, contentHeight - listHeight)));
			context.fill(x + width - 3, thumbY, x + width - 1, thumbY + thumb, VeloUi.withAlpha(theme.accentStart(), 0xAA));
		}

		// Footer.
		String footer = System.currentTimeMillis() < feedbackUntil ? feedback
				: waypoints.size() + " waypoint" + (waypoints.size() == 1 ? "" : "s") + " shown  -  click one to edit";
		boolean deathOn = net.veloclient.velo.client.modules.hud.WaypointsModule.deathWaypointsEnabled();
		String deathLabel = deathOn ? "Auto death waypoints: on" : "Auto death waypoints: off";
		int deathWidth = this.textRenderer.getWidth(deathLabel) + 16;
		hits.add(VeloUi.pill(context, x + width - deathWidth, contentBottom() - 12, deathWidth, 14, deathLabel, deathOn ? 1 : 0,
				mouseX, mouseY, () -> net.veloclient.velo.client.modules.hud.WaypointsModule.setDeathWaypoints(!deathOn)));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(footer, width - deathWidth - 8), x, contentBottom() - 8, VeloUi.muted());
	}

	private void drawRow(DrawContext context, Waypoint waypoint, int x, int y, int width, int mouseX, int mouseY, int clipTop, int clipBottom) {
		Theme theme = ThemeManager.active();
		boolean rowVisible = mouseY >= clipTop && mouseY < clipBottom;
		boolean hovered = rowVisible && VeloUi.inside(mouseX, mouseY, x, y, width, ROW_HEIGHT);
		int bg = hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x14) : VeloUi.withAlpha(0xFFFFFFFF, 0x08);
		VeloDraw.fillRounded(context, x, y, width, ROW_HEIGHT, 6, bg);
		VeloDraw.fillRounded(context, x, y + 4, 3, ROW_HEIGHT - 8, 1, waypoint.color | 0xFF000000);

		// Enabled switch.
		int trackX = x + 10;
		int trackY = y + 8;
		VeloDraw.fillRounded(context, trackX, trackY, 24, 14, 7, waypoint.enabled ? (waypoint.color | 0xFF000000) : 0xFF3A3A40);
		VeloDraw.fillRounded(context, waypoint.enabled ? trackX + 12 : trackX + 2, trackY + 2, 10, 10, 5, 0xFFFFFFFF);
		if (rowVisible) {
			hits.add(new VeloUi.Hit(trackX - 2, y, 30, ROW_HEIGHT, () -> {
				waypoint.enabled = !waypoint.enabled;
				WaypointManager.save();
			}));
		}

		// Icon.
		int iconX = x + 42;
		VeloDraw.fillRounded(context, iconX, y + 4, 22, 22, 5, VeloUi.withAlpha(0xFF000000, 0x50));
		var stack = WaypointIcons.stack(waypoint.icon);
		if (stack != null) {
			context.drawItemWithoutEntity(stack, iconX + 3, y + 7);
		} else {
			VeloDraw.fillCircle(context, iconX + 11, y + 15, 5, waypoint.color | 0xFF000000);
		}

		// Name + details.
		int textX = iconX + 30;
		boolean compact = width < 360;
		int actionsWidth = compact ? 0 : 176;
		int nameColor = waypoint.enabled ? VeloAnim.lerpArgb(waypoint.color | 0xFF000000, 0xFFFFFFFF, 0.25f) : VeloUi.muted();
		int textSpace = Math.max(30, width - (textX - x) - (compact ? 58 : actionsWidth));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(waypoint.name, textSpace), textX, y + 5, nameColor);
		StringBuilder detail = new StringBuilder(String.format(Locale.ROOT, "%d, %d, %d", waypoint.blockX(), waypoint.blockY(), waypoint.blockZ()));
		var player = MinecraftClient.getInstance().player;
		if (player != null && world != null && world.equals(WaypointManager.currentWorldKey()) && waypoint.dimension.equals(ClientCompat.dimensionId())) {
			double dx = waypoint.x - player.getX();
			double dy = waypoint.y - player.getY();
			double dz = waypoint.z - player.getZ();
			detail.append("  -  ").append(Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz))).append("m away");
		}
		if (waypoint.death) {
			detail.append("  -  died ").append(VeloUi.ago(waypoint.created));
		}
		if (!waypoint.enabled) {
			detail.append("  -  hidden");
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(detail.toString(), textSpace), textX, y + 17, VeloUi.muted());

		if (!rowVisible) {
			return;
		}
		if (compact) {
			// No room for three buttons: the row itself opens the editor (which has Delete), plus Share.
			hits.add(VeloUi.pill(context, x + width - 52, y + 7, 46, 16, "Share", 0, mouseX, mouseY,
					() -> this.client.setScreen(new WaypointShareScreen(this, waypoint))));
			hits.add(new VeloUi.Hit(textX, Math.max(y, clipTop), x + width - 58 - textX, ROW_HEIGHT, () -> this.client.setScreen(WaypointEditScreen.edit(this, waypoint))));
			return;
		}
		int buttonY = y + 7;
		int bx = x + width - 8;
		bx -= 52;
		hits.add(confirmPill(context, bx, buttonY, 52, "Delete", "del:" + waypoint.id, mouseX, mouseY, () -> {
			WaypointManager.delete(waypoint);
			flash("Deleted \"" + waypoint.name + "\"");
		}));
		bx -= 54;
		hits.add(VeloUi.pill(context, bx, buttonY, 50, 16, "Share", 0, mouseX, mouseY,
				() -> this.client.setScreen(new WaypointShareScreen(this, waypoint))));
		bx -= 46;
		hits.add(VeloUi.pill(context, bx, buttonY, 42, 16, "Edit", 0, mouseX, mouseY,
				() -> this.client.setScreen(WaypointEditScreen.edit(this, waypoint))));
		hits.add(new VeloUi.Hit(textX, y, bx - textX - 4, ROW_HEIGHT, () -> this.client.setScreen(WaypointEditScreen.edit(this, waypoint))));
	}

	private List<Waypoint> filtered() {
		if (world == null) {
			return List.of();
		}
		String query = searchField == null ? search : searchField.getText();
		String lower = query.toLowerCase(Locale.ROOT).strip();
		List<Waypoint> list = new ArrayList<>();
		for (Waypoint waypoint : WaypointManager.inWorldAndDimension(world, dimension)) {
			if (lower.isEmpty() || waypoint.name.toLowerCase(Locale.ROOT).contains(lower)) {
				list.add(waypoint);
			}
		}
		boolean here = world.equals(WaypointManager.currentWorldKey()) && dimension.equals(ClientCompat.dimensionId());
		if (here) {
			return WaypointManager.sortedByDistance(list);
		}
		list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		return list;
	}

	private void cycleWorld(List<String> worlds, int direction) {
		if (worlds.isEmpty()) {
			return;
		}
		int index = world == null ? 0 : worlds.indexOf(world);
		index = Math.floorMod(index + direction, worlds.size());
		world = worlds.get(index);
		scroll = 0;
	}

	private static int dimensionColor(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> 0xFF5DBB63;
			case "minecraft:the_nether" -> 0xFFE5484D;
			case "minecraft:the_end" -> 0xFFB58CE8;
			default -> 0xFF8B8D98;
		};
	}

	private VeloUi.Hit confirmPill(DrawContext context, int x, int y, int width, String label, String key, int mouseX, int mouseY, Runnable action) {
		boolean armed = key.equals(confirmKey) && System.currentTimeMillis() < confirmUntil;
		return VeloUi.pill(context, x, y, width, 16, armed ? "Sure?" : label, armed ? 3 : 0, mouseX, mouseY, () -> {
			if (armed) {
				confirmKey = null;
				action.run();
			} else {
				confirmKey = key;
				confirmUntil = System.currentTimeMillis() + 3000;
			}
		});
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
		scroll -= verticalAmount * 18;
		return true;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
	}
}
