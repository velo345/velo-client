package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.util.Keys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Autocomplete for a block-id text field: as you type, the best matching blocks show up below it
 * with their icon, their in-game name and their id - so "repeat", "rep", "Redstone Rep" or
 * "glass pane" all find the right block without knowing the exact id. Up / Down pick, Tab or Enter
 * (or a click) takes one. A "[property=value]" part you typed after the id is kept.
 */
final class BlockSuggest {

	private static final int ROWS = 7;
	private static final int ROW_HEIGHT = 18;
	private static List<Object[]> catalog;

	private final TextFieldWidget field;
	private final boolean allowStar;
	private List<Object[]> shown = List.of();
	private String lastQuery;
	private int index;
	private boolean dismissed;

	BlockSuggest(TextFieldWidget field, boolean allowStar) {
		this.field = field;
		this.allowStar = allowStar;
	}

	/** {id, lowercase name, state, icon} for every placeable block. */
	private static List<Object[]> catalog() {
		if (catalog == null) {
			List<Object[]> out = new ArrayList<>();
			for (String id : RmWorld.allBlockIds()) {
				Object state = RmWorld.parse(id, Map.of());
				if (state == null) {
					continue;
				}
				ItemStack icon = (ItemStack) RmWorld.blockItem(state);
				out.add(new Object[] {id, RmWorld.blockName(state).toLowerCase(Locale.ROOT), state, icon});
			}
			catalog = out;
		}
		return catalog;
	}

	/** How well {@code q} matches a block: lower is better, -1 = no match. */
	private static int score(String q, String id, String name) {
		String path = id.startsWith("minecraft:") ? id.substring(10) : id;
		String underscored = q.replace(' ', '_');
		if (path.equals(underscored) || name.equals(q)) {
			return 0;
		}
		if (path.startsWith(underscored) || name.startsWith(q)) {
			return 1 + path.length() / 8;
		}
		// Every typed word starts a word of the name or id ("red rep" -> "Redstone Repeater").
		String[] words = q.split("[ _]+");
		boolean allWords = words.length > 0;
		for (String word : words) {
			if (!word.isEmpty() && !(name.startsWith(word) || name.contains(" " + word) || path.startsWith(word) || path.contains("_" + word))) {
				allWords = false;
				break;
			}
		}
		if (allWords) {
			return 10 + path.length() / 8;
		}
		if (path.contains(underscored) || name.contains(q) || id.contains(q)) {
			return 20 + path.length() / 8;
		}
		return -1;
	}

	private String query() {
		String text = field.getText();
		int bracket = text.indexOf('[');
		return (bracket >= 0 ? text.substring(0, bracket) : text).strip().toLowerCase(Locale.ROOT);
	}

	private void refresh() {
		String q = query();
		if (q.equals(lastQuery)) {
			return;
		}
		lastQuery = q;
		index = 0;
		dismissed = false;
		if (q.isEmpty() || q.equals("*")) {
			shown = List.of();
			return;
		}
		List<Object[]> scored = new ArrayList<>();
		for (Object[] block : catalog()) {
			int score = score(q, (String) block[0], (String) block[1]);
			if (score >= 0) {
				scored.add(new Object[] {score, block});
			}
		}
		scored.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
		List<Object[]> out = new ArrayList<>();
		for (int i = 0; i < scored.size() && out.size() < ROWS; i++) {
			out.add((Object[]) scored.get(i)[1]);
		}
		// Already exactly one of the suggestions: nothing left to suggest.
		if (out.size() == 1 && ((String) out.get(0)[0]).equals(q.contains(":") ? q : "minecraft:" + q)) {
			out = List.of();
		}
		shown = out;
	}

	boolean visible() {
		refresh();
		return field.isFocused() && !dismissed && !shown.isEmpty();
	}

	private void accept(Object[] block) {
		String text = field.getText();
		int bracket = text.indexOf('[');
		String rest = bracket >= 0 ? text.substring(bracket) : "";
		String id = (String) block[0];
		field.setText(id + rest);
		lastQuery = query();
		shown = List.of();
	}

	/** The block the field currently names (for the icon next to it), or null. */
	Object currentState() {
		String text = field.getText().strip();
		if (text.isEmpty() || text.equals("*")) {
			return null;
		}
		return RmWorld.fromText(text);
	}

	/** Draws the field's icon box (to the right of the field, 18x18). */
	void renderIcon(DrawContext context, int x, int y) {
		VeloDraw.fillRounded(context, x, y, 18, 18, 4, 0xFF22222A);
		Object state = currentState();
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		if (state != null && !((ItemStack) RmWorld.blockItem(state)).isEmpty()) {
			context.drawItemWithoutEntity((ItemStack) RmWorld.blockItem(state), x + 1, y + 1);
		} else if (allowStar && field.getText().strip().equals("*")) {
			context.drawTextWithShadow(textRenderer, "*", x + 7, y + 6, 0xFFFFD84A);
		} else if (state == null && !field.getText().isBlank()) {
			context.drawTextWithShadow(textRenderer, "?", x + 6, y + 5, 0xFFFF7070);
		}
	}

	/** Draws the suggestion list below the field (call after the widgets, so it's on top). */
	void renderList(DrawContext context, int mouseX, int mouseY) {
		if (!visible()) {
			return;
		}
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int x = field.getX();
		int y = field.getY() + field.getHeight() + 2;
		int w = field.getWidth();
		int h = shown.size() * ROW_HEIGHT + 4;
		VeloDraw.fillRounded(context, x, y, w, h, 5, 0xF6141419);
		VeloDraw.strokeRounded(context, x, y, w, h, 5, 0x40FFFFFF);
		for (int i = 0; i < shown.size(); i++) {
			Object[] block = shown.get(i);
			int ry = y + 2 + i * ROW_HEIGHT;
			boolean hover = VeloUi.inside(mouseX, mouseY, x, ry, w, ROW_HEIGHT);
			if (hover) {
				index = i;
			}
			if (i == index) {
				VeloDraw.fillRounded(context, x + 2, ry, w - 4, ROW_HEIGHT, 4, 0xFF33333F);
			}
			ItemStack icon = (ItemStack) block[3];
			if (!icon.isEmpty()) {
				context.drawItemWithoutEntity(icon, x + 4, ry + 1);
			}
			String name = RmWorld.blockName(block[2]);
			String id = ((String) block[0]).replace("minecraft:", "");
			int nameWidth = Math.min(textRenderer.getWidth(name), w / 2);
			context.drawTextWithShadow(textRenderer, VeloUi.trim(name, w / 2), x + 24, ry + 5, 0xFFFFFFFF);
			context.drawTextWithShadow(textRenderer, VeloUi.trim(id, w - nameWidth - 40), x + 30 + nameWidth, ry + 5, 0xFF8A8A9E);
		}
	}

	/** A click inside the list takes that block; true when the click was used. */
	boolean click(double mx, double my) {
		if (!visible()) {
			return false;
		}
		int x = field.getX();
		int y = field.getY() + field.getHeight() + 2;
		for (int i = 0; i < shown.size(); i++) {
			if (VeloUi.inside(mx, my, x, y + 2 + i * ROW_HEIGHT, field.getWidth(), ROW_HEIGHT)) {
				accept(shown.get(i));
				return true;
			}
		}
		return false;
	}

	/** Up / Down / Tab / Enter / Escape while the list is open; true when the key was used. */
	boolean key(int key) {
		if (!visible()) {
			return false;
		}
		if (key == Keys.DOWN) {
			index = (index + 1) % shown.size();
			return true;
		}
		if (key == Keys.UP) {
			index = (index - 1 + shown.size()) % shown.size();
			return true;
		}
		if (key == Keys.TAB || key == Keys.ENTER || key == Keys.KP_ENTER) {
			accept(shown.get(Math.min(index, shown.size() - 1)));
			return true;
		}
		if (key == Keys.ESCAPE) {
			dismissed = true;
			return true;
		}
		return false;
	}
}
