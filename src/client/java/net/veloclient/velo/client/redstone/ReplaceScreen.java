package net.veloclient.velo.client.redstone;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.Map;

/**
 * Replace in the selection: one or more block types (comma separated, "*" = every non-air block)
 * with another, with an optional explicit state ("minecraft:repeater[delay=3]"). Both fields suggest
 * blocks by name or id as you type (with their icons), and the grid button next to each opens the
 * block picker. Settings (remembered): keep properties (powered, waterlogged, half...), keep
 * direction - converted between facing / crafter orientation / axis / sign rotation, see
 * {@link ReplaceProps} - turned if you like, and a chance to replace only part of the blocks.
 */
public final class ReplaceScreen extends VeloWindow {

	private TextFieldWidget from;
	private TextFieldWidget to;
	private BlockSuggest fromSuggest;
	private BlockSuggest toSuggest;
	private static boolean keepProperties = true;
	private static boolean keepDirection = true;
	private static ReplaceProps.Turn turn = ReplaceProps.Turn.NONE;
	private static int chance = 100;
	private String fromValue;
	private String toValue;

	public ReplaceScreen() {
		super(Text.literal("Replace Blocks"), 420, 292);
		int[] look = RmWorld.lookedBlock(64);
		Object level = RmWorld.clientLevel();
		fromValue = look != null && level != null ? RmWorld.id(RmWorld.state(level, look[0], look[1], look[2])) : "*";
		var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		String held = player == null ? "" : net.veloclient.velo.client.pvp.PvpCompat.itemId(net.veloclient.velo.client.pvp.PvpCompat.mainHand(player));
		toValue = held != null && RmWorld.parse(held, Map.of()) != null ? held : "minecraft:air";
	}

	@Override
	protected void layoutContent() {
		if (from != null) {
			fromValue = from.getText();
			toValue = to.getText();
		}
		this.clearChildren();
		int x = contentX();
		int w = contentWidth();
		int fieldW = w - 46;
		from = new TextFieldWidget(this.textRenderer, x, contentY() + 14, fieldW, 16, Text.literal("From"));
		from.setMaxLength(200);
		from.setText(fromValue);
		from.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder("Block name(s), e.g. stone or stone, dirt or *"));
		addDrawableChild(from);
		fromSuggest = new BlockSuggest(from, true);
		addDrawableChild(new VeloButton(x + w - 20, contentY() + 13, 20, 18, Text.literal("..."),
				b -> pick("Replace which block", id -> from.setText(id))));
		to = new TextFieldWidget(this.textRenderer, x, contentY() + 50, fieldW, 16, Text.literal("To"));
		to.setMaxLength(300);
		to.setText(toValue);
		to.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder("Type a block name, e.g. glass"));
		addDrawableChild(to);
		toSuggest = new BlockSuggest(to, false);
		addDrawableChild(new VeloButton(x + w - 20, contentY() + 49, 20, 18, Text.literal("..."),
				b -> pick("Replace with", id -> to.setText(id))));
		int half = (w - 8) / 2;
		addDrawableChild(new VeloButton(x, contentY() + 80, half, 18, Text.literal("Keep properties: " + (keepProperties ? "On" : "Off")),
				b -> {
					keepProperties = !keepProperties;
					layoutContent();
				}));
		addDrawableChild(new VeloButton(x + half + 8, contentY() + 80, half, 18, Text.literal("Keep direction: " + (keepDirection ? "On" : "Off")),
				b -> {
					keepDirection = !keepDirection;
					layoutContent();
				}));
		VeloButton turnButton = new VeloButton(x, contentY() + 104, half, 18, Text.literal("Turn: " + turn.label), b -> {
			turn = turn.next();
			layoutContent();
		});
		turnButton.active = keepDirection;
		addDrawableChild(turnButton);
		addDrawableChild(new net.veloclient.velo.client.gui.widget.VeloSlider(x + half + 8, contentY() + 104, half, 18, "Chance", 1, 100,
				() -> chance, v -> chance = (int) Math.round(v), v -> (double) Math.round(v), v -> Math.round(v) + "%"));
		addDrawableChild(new VeloButton(x, contentBottom() - 20, half, 20, Text.literal("Replace"), b -> apply()));
		addDrawableChild(new VeloButton(x + half + 8, contentBottom() - 20, half, 20, Text.literal("Cancel"), b -> requestClose()));
		setInitialFocus(to);
	}

	/** Opens the icon grid; the chosen block goes into the field when this window comes back. */
	private void pick(String title, java.util.function.Consumer<String> set) {
		this.client.setScreen(new BlockPickerScreen(title, false, set).backTo(this));
	}

	private void apply() {
		String toText = to.getText().strip();
		java.util.List<String> fromIds = new java.util.ArrayList<>();
		for (String part : from.getText().split(",")) {
			String one = part.strip();
			if (one.isEmpty()) {
				continue;
			}
			if (!one.equals("*") && RmWorld.fromText(one) == null) {
				RmState.error("Unknown block: " + one);
				return;
			}
			fromIds.add(one.contains(":") || one.equals("*") ? stripState(one) : "minecraft:" + stripState(one));
		}
		if (fromIds.isEmpty()) {
			RmState.error("Say which block to replace");
			return;
		}
		RmOps.replace(fromIds, toText, new ReplaceProps.Options(keepProperties, keepDirection, turn), chance);
		requestClose();
	}

	private static String stripState(String text) {
		int bracket = text.indexOf('[');
		return bracket >= 0 ? text.substring(0, bracket) : text;
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		context.drawTextWithShadow(this.textRenderer, "Replace (block names or ids, comma separated, * = everything)", contentX(), contentY() + 3, VeloUi.muted());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Direction carries over between facing, crafter orientation, log axis and sign rotation.",
				contentWidth()), contentX(), contentY() + 128, VeloUi.muted());
		context.drawTextWithShadow(this.textRenderer, "With (block, optionally [property=value,...])", contentX(), contentY() + 39, VeloUi.muted());
		fromSuggest.renderIcon(context, contentX() + contentWidth() - 42, contentY() + 13);
		toSuggest.renderIcon(context, contentX() + contentWidth() - 42, contentY() + 49);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		// Suggestions go over everything else in the window.
		fromSuggest.renderList(context, mouseX, mouseY);
		toSuggest.renderList(context, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (fromSuggest.click(click.x(), click.y()) || toSuggest.click(click.x(), click.y())) {
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (fromSuggest.key(input.key()) || toSuggest.key(input.key())) {
			return true;
		}
		return super.keyPressed(input);
	}
}
