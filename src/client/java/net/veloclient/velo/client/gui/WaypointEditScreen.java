package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.waypoints.Waypoint;
import net.veloclient.velo.client.waypoints.WaypointIcons;
import net.veloclient.velo.client.waypoints.WaypointManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Create or edit one waypoint: name, position (or "use my position"), dimension, color (presets or
 * a custom color) and icon. Opened by the New Waypoint key with the name already selected, so
 * "N, type a name, Enter" is all it takes to drop a named waypoint where you stand.
 */
public final class WaypointEditScreen extends VeloWindow {

	private static final String[] DIMENSIONS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};

	private final Waypoint editing;
	private final boolean creating;
	private final String suggestedName;
	private String name;
	private String xValue;
	private String yValue;
	private String zValue;
	private String dimension;
	private int color;
	private String icon;
	private TextFieldWidget nameField;
	private TextFieldWidget xField;
	private TextFieldWidget yField;
	private TextFieldWidget zField;
	private String error = "";
	private boolean focusName = true;
	private final List<VeloUi.Hit> hits = new ArrayList<>();

	private WaypointEditScreen(Screen parent, Waypoint editing, boolean creating) {
		super(Text.literal(creating ? "New Waypoint" : "Edit Waypoint"), 380, 318);
		returnTo(parent);
		this.editing = editing;
		this.creating = creating;
		// A new waypoint starts with an empty name showing the suggestion as a placeholder - Enter
		// keeps the suggestion, typing replaces it.
		this.suggestedName = editing.name;
		this.name = creating ? "" : editing.name;
		this.xValue = String.valueOf(editing.blockX());
		this.yValue = String.valueOf(editing.blockY());
		this.zValue = String.valueOf(editing.blockZ());
		this.dimension = editing.dimension;
		this.color = editing.color;
		this.icon = editing.icon;
	}

	/** A new waypoint at the player's position, not saved until "Create" - null-safe outside a world. */
	public static WaypointEditScreen createHere(Screen parent) {
		MinecraftClient client = MinecraftClient.getInstance();
		String world = WaypointManager.currentWorldKey();
		String dimension = ClientCompat.dimensionId();
		Waypoint draft = new Waypoint(WaypointManager.defaultName(), world == null ? WaypointManager.LEGACY_WORLD : world,
				dimension == null ? "minecraft:overworld" : dimension,
				client.player == null ? 0 : Math.floor(client.player.getX()) + 0.5,
				client.player == null ? 64 : Math.floor(client.player.getY()),
				client.player == null ? 0 : Math.floor(client.player.getZ()) + 0.5,
				WaypointManager.nextColor(), WaypointIcons.NONE);
		return new WaypointEditScreen(parent, draft, true);
	}

	/** A new waypoint at a chosen spot (e.g. right-clicked on the world map), not saved until "Create". */
	public static WaypointEditScreen createAt(Screen parent, String world, String dimension, double x, double y, double z) {
		Waypoint draft = new Waypoint(WaypointManager.defaultName(), world == null ? WaypointManager.LEGACY_WORLD : world,
				dimension == null ? "minecraft:overworld" : dimension, Math.floor(x) + 0.5, Math.floor(y), Math.floor(z) + 0.5,
				WaypointManager.nextColor(), WaypointIcons.NONE);
		return new WaypointEditScreen(parent, draft, true);
	}

	public static WaypointEditScreen edit(Screen parent, Waypoint waypoint) {
		return new WaypointEditScreen(parent, waypoint, false);
	}

	@Override
	protected void layoutContent() {
		captureFields();
		this.clearChildren();
		int x = contentX();
		int y = contentY();
		nameField = field(x + 6, y + 14, contentWidth() - 12, name, 40, "Name");
		int coordWidth = (contentWidth() - 120) / 3;
		xField = field(x + 6, y + 50, coordWidth - 10, xValue, 9, "X");
		yField = field(x + 6 + coordWidth, y + 50, coordWidth - 10, yValue, 6, "Y");
		zField = field(x + 6 + coordWidth * 2, y + 50, coordWidth - 10, zValue, 9, "Z");
		if (creating) {
			nameField.setPlaceholder(Text.literal(suggestedName));
		}
		if (focusName) {
			focusName = false;
			setInitialFocus(nameField);
		}
	}

	private TextFieldWidget field(int x, int y, int width, String value, int maxLength, String label) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 14, Text.literal(label));
		field.setDrawsBackground(false);
		field.setMaxLength(maxLength);
		field.setText(value);
		addDrawableChild(field);
		return field;
	}

	private void captureFields() {
		if (nameField != null) {
			name = nameField.getText();
			xValue = xField.getText();
			yValue = yField.getText();
			zValue = zField.getText();
		}
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		Theme theme = ThemeManager.active();
		int x = contentX();
		int width = contentWidth();
		// Everything above the button bar scrolls (small GUI sizes can't fit it all); the bar stays put.
		int viewTop = contentY();
		int viewBottom = contentBottom() - 26;
		int y = viewTop - (int) scroll;
		placeFields(x, y, width, viewTop, viewBottom);
		context.enableScissor(x - 2, viewTop, x + width + 2, viewBottom);

		context.drawTextWithShadow(this.textRenderer, "NAME", x, y, VeloUi.muted());
		box(context, x, y + 10, width, nameField != null && nameField.isFocused());

		context.drawTextWithShadow(this.textRenderer, "POSITION", x, y + 36, VeloUi.muted());
		int coordWidth = (width - 120) / 3;
		String[] axes = {"X", "Y", "Z"};
		TextFieldWidget[] fields = {xField, yField, zField};
		for (int i = 0; i < 3; i++) {
			box(context, x + i * coordWidth, y + 46, coordWidth - 4, fields[i] != null && fields[i].isFocused());
			context.drawTextWithShadow(this.textRenderer, axes[i], x + i * coordWidth + coordWidth - 14, y + 52, VeloUi.muted());
		}
		hits.add(VeloUi.pill(context, x + width - 112, y + 46, 112, 20, width < 300 ? "My pos" : "Use my position", 0, mouseX, mouseY, this::useMyPosition));

		// Dimension.
		context.drawTextWithShadow(this.textRenderer, "DIMENSION", x, y + 74, VeloUi.muted());
		int dimX = x;
		int maxDimWidth = (width - 8) / DIMENSIONS.length;
		for (String dim : DIMENSIONS) {
			String label = WaypointManager.dimensionLabel(dim);
			int dimWidth = Math.min(this.textRenderer.getWidth(label) + 18, maxDimWidth);
			boolean active = dim.equals(dimension);
			hits.add(VeloUi.pill(context, dimX, y + 84, dimWidth, 18, label, active ? 1 : 0, mouseX, mouseY, () -> dimension = dim));
			dimX += dimWidth + 4;
		}

		// Color.
		context.drawTextWithShadow(this.textRenderer, "COLOR", x, y + 110, VeloUi.muted());
		int swatchStep = Math.min(21, Math.max(12, (width - 70) / WaypointManager.PALETTE.length));
		int swatchSize = swatchStep - 5;
		int swatchX = x;
		for (int preset : WaypointManager.PALETTE) {
			boolean active = (preset | 0xFF000000) == (color | 0xFF000000);
			if (active) {
				VeloDraw.fillRounded(context, swatchX - 2, y + 118, swatchSize + 4, swatchSize + 4, 6, 0xFFFFFFFF);
			}
			VeloDraw.fillRounded(context, swatchX, y + 120, swatchSize, swatchSize, 5, preset);
			hits.add(new VeloUi.Hit(swatchX, y + 120, swatchSize, swatchSize, () -> color = preset));
			swatchX += swatchStep;
		}
		boolean custom = true;
		for (int preset : WaypointManager.PALETTE) {
			custom &= (preset | 0xFF000000) != (color | 0xFF000000);
		}
		hits.add(VeloUi.pill(context, swatchX + 2, y + 119, x + width - swatchX - 2, 18, custom ? "Custom" : "Custom...",
				custom ? 1 : 0, mouseX, mouseY, () -> {
					captureFields();
					this.client.setScreen(new VeloColorPickerScreen(this, "Waypoint Color", color, false, picked -> color = picked | 0xFF000000));
				}));

		// Icon grid.
		context.drawTextWithShadow(this.textRenderer, "ICON", x, y + 146, VeloUi.muted());
		List<String> icons = WaypointIcons.keys();
		int perRow = Math.max(1, width / 24);
		for (int i = 0; i < icons.size(); i++) {
			String key = icons.get(i);
			int tileX = x + (i % perRow) * 24;
			int tileY = y + 156 + (i / perRow) * 24;
			boolean active = key.equals(icon);
			boolean hovered = VeloUi.inside(mouseX, mouseY, tileX, tileY, 22, 22);
			VeloDraw.fillRounded(context, tileX, tileY, 22, 22, 5, active ? VeloUi.withAlpha(color, 0xB0)
					: hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x20) : VeloUi.withAlpha(0xFF000000, 0x50));
			var stack = WaypointIcons.stack(key);
			if (stack != null) {
				context.drawItemWithoutEntity(stack, tileX + 3, tileY + 3);
			} else {
				VeloDraw.fillCircle(context, tileX + 11, tileY + 11, 5, color | 0xFF000000);
			}
			hits.add(new VeloUi.Hit(tileX, tileY, 22, 22, () -> icon = key));
		}

		int contentEnd = y + 156 + ((icons.size() + perRow - 1) / perRow) * 24;
		context.disableScissor();
		clipHits(viewTop, viewBottom);
		int overflow = Math.max(0, contentEnd + (int) scroll - viewBottom);
		scroll = Math.max(0, Math.min(scroll, overflow));
		if (overflow > 0) {
			int trackHeight = viewBottom - viewTop;
			int thumb = Math.max(12, trackHeight * trackHeight / (trackHeight + overflow));
			int thumbY = viewTop + (int) ((trackHeight - thumb) * (scroll / overflow));
			context.fill(x + width + 4, thumbY, x + width + 6, thumbY + thumb, VeloUi.withAlpha(theme.accentStart(), 0xAA));
		}

		// Preview + buttons.
		int bottom = contentBottom();
		if (!error.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(error, width), x, bottom - 32, 0xFFFF7070);
		}
		hits.add(VeloUi.pill(context, x + width - 90, bottom - 20, 90, 20, creating ? "Create" : "Save", 1, mouseX, mouseY, this::save));
		hits.add(VeloUi.pill(context, x + width - 166, bottom - 20, 70, 20, "Cancel", 0, mouseX, mouseY, this::requestClose));
		if (!creating) {
			hits.add(VeloUi.pill(context, x, bottom - 20, 70, 20, "Delete", 3, mouseX, mouseY, () -> {
				WaypointManager.delete(editing);
				requestClose();
			}));
		}
		VeloDraw.fillRounded(context, x + (creating ? 0 : 78), bottom - 20, 4, 20, 2, color | 0xFF000000);
		int previewSpace = width - 166 - (creating ? 8 : 86) - 4;
		if (previewSpace > 20) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(currentName(), previewSpace), x + (creating ? 8 : 86), bottom - 14, theme.text());
		}
	}

	private double scroll;

	/** Moves the text fields with the scrolled content and hides any that isn't fully inside the visible area. */
	private void placeFields(int x, int y, int width, int viewTop, int viewBottom) {
		if (nameField == null) {
			return;
		}
		int coordWidth = (width - 120) / 3;
		place(nameField, x + 6, y + 14, width - 12, viewTop, viewBottom);
		place(xField, x + 6, y + 50, coordWidth - 10, viewTop, viewBottom);
		place(yField, x + 6 + coordWidth, y + 50, coordWidth - 10, viewTop, viewBottom);
		place(zField, x + 6 + coordWidth * 2, y + 50, coordWidth - 10, viewTop, viewBottom);
	}

	private static void place(TextFieldWidget field, int x, int y, int width, int viewTop, int viewBottom) {
		field.setX(x);
		field.setY(y);
		field.setWidth(Math.max(10, width));
		field.visible = y >= viewTop && y + 14 <= viewBottom;
	}

	private void clipHits(int top, int bottom) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			VeloUi.Hit hit = hits.get(i);
			int y1 = Math.max(hit.y(), top);
			int y2 = Math.min(hit.y() + hit.height(), bottom);
			if (y2 <= y1) {
				hits.remove(i);
			} else if (y1 != hit.y() || y2 != hit.y() + hit.height()) {
				hits.set(i, new VeloUi.Hit(hit.x(), y1, hit.width(), y2 - y1, hit.action()));
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= verticalAmount * 16;
		if (scroll < 0) {
			scroll = 0;
		}
		return true;
	}

	private String currentName() {
		String typed = nameField == null ? name : nameField.getText();
		return typed.isBlank() ? suggestedName : typed;
	}

	private void box(DrawContext context, int x, int y, int width, boolean focused) {
		Theme theme = ThemeManager.active();
		VeloDraw.fillRounded(context, x, y, width, 20, 5, VeloUi.withAlpha(0xFF000000, focused ? 0x70 : 0x50));
		if (focused) {
			VeloDraw.strokeRounded(context, x, y, width, 20, 5, VeloUi.withAlpha(theme.accentStart(), 0xA0));
		}
	}

	private void useMyPosition() {
		var player = MinecraftClient.getInstance().player;
		if (player == null) {
			error = "Join a world first";
			return;
		}
		xField.setText(String.valueOf((int) Math.floor(player.getX())));
		yField.setText(String.valueOf((int) Math.floor(player.getY())));
		zField.setText(String.valueOf((int) Math.floor(player.getZ())));
		String here = ClientCompat.dimensionId();
		if (here != null) {
			dimension = here;
		}
	}

	private void save() {
		captureFields();
		String cleanName = name.strip().isEmpty() ? suggestedName : name.strip();
		int bx;
		int by;
		int bz;
		try {
			bx = Integer.parseInt(xValue.strip());
			by = Integer.parseInt(yValue.strip());
			bz = Integer.parseInt(zValue.strip());
		} catch (NumberFormatException e) {
			error = "Coordinates must be whole numbers";
			return;
		}
		editing.name = cleanName;
		editing.x = bx + 0.5;
		editing.y = by;
		editing.z = bz + 0.5;
		editing.dimension = dimension;
		editing.color = color | 0xFF000000;
		editing.icon = icon;
		if (creating) {
			if (WaypointManager.currentWorldKey() != null) {
				editing.world = WaypointManager.currentWorldKey();
			}
			WaypointManager.add(editing);
		} else {
			WaypointManager.save();
		}
		requestClose();
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
		if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
			save();
			return true;
		}
		return super.keyPressed(input);
	}
}
