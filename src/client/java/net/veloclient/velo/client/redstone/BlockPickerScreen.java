package net.veloclient.velo.client.redstone;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Pick a block by its icon (with search) - used by Redstone Mode's tool settings and Replace. */
public final class BlockPickerScreen extends VeloWindow {

	private static List<String> allIds;

	private final Consumer<String> onPick;
	private final boolean allowAuto;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget search;
	private String query = "";
	private double scroll;

	public BlockPickerScreen(String title, boolean allowAuto, Consumer<String> onPick) {
		super(Text.literal(title), 360, 300);
		this.onPick = onPick;
		this.allowAuto = allowAuto;
	}

	private static List<String> ids() {
		if (allIds == null) {
			List<String> ids = new ArrayList<>();
			for (String id : RmWorld.allBlockIds()) {
				Object state = RmWorld.parse(id, Map.of());
				if (state != null && !RmWorld.isAir(state) && !((ItemStack) RmWorld.blockItem(state)).isEmpty()) {
					ids.add(id);
				}
			}
			allIds = ids;
		}
		return allIds;
	}

	@Override
	protected void layoutContent() {
		if (search != null) {
			query = search.getText();
		}
		this.clearChildren();
		search = new TextFieldWidget(this.textRenderer, contentX(), contentY(), contentWidth(), 16, Text.literal("Search"));
		search.setPlaceholder(Text.literal("Search blocks..."));
		search.setText(query);
		search.setChangedListener(q -> scroll = 0);
		addDrawableChild(search);
		setInitialFocus(search);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).strip();
		List<String> shown = new ArrayList<>();
		if (allowAuto && (q.isEmpty() || "auto".contains(q))) {
			shown.add("auto");
		}
		for (String id : ids()) {
			if (q.isEmpty() || id.contains(q.replace(' ', '_'))) {
				shown.add(id);
			}
		}
		int x = contentX();
		int top = contentY() + 22;
		int bottom = contentBottom();
		int cell = 22;
		int columns = Math.max(1, contentWidth() / cell);
		int rows = (shown.size() + columns - 1) / columns;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows * cell - (bottom - top))));
		context.enableScissor(x, top, x + contentWidth(), bottom);
		String hovered = null;
		for (int i = 0; i < shown.size(); i++) {
			int cx = x + (i % columns) * cell;
			int cy = top + (i / columns) * cell - (int) scroll;
			if (cy + cell < top || cy > bottom) {
				continue;
			}
			String id = shown.get(i);
			boolean hover = VeloUi.inside(mouseX, mouseY, cx, cy, cell - 2, cell - 2) && mouseY >= top && mouseY < bottom;
			VeloDraw.fillRounded(context, cx, cy, cell - 2, cell - 2, 3, hover ? 0xFF3A3A48 : 0xFF22222A);
			if (id.equals("auto")) {
				context.drawTextWithShadow(this.textRenderer, "A", cx + 7, cy + 6, 0xFFFFD84A);
			} else {
				context.drawItemWithoutEntity((ItemStack) RmWorld.blockItem(RmWorld.parse(id, Map.of())), cx + 2, cy + 2);
			}
			if (hover) {
				hovered = id;
			}
			int clipTop = Math.max(cy, top);
			int clipBottom = Math.min(cy + cell - 2, bottom);
			if (clipBottom > clipTop) {
				hits.add(new VeloUi.Hit(cx, clipTop, cell - 2, clipBottom - clipTop, () -> {
					onPick.accept(id);
					closeToGame();
				}));
			}
		}
		context.disableScissor();
		if (hovered != null) {
			String label = hovered.equals("auto") ? "Auto (detected from what's already built)" : hovered.replace("minecraft:", "");
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(label, contentWidth()), x, bottom + 4, 0xFFFFFFFF);
		}
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
		scroll -= verticalAmount * 22;
		return true;
	}
}
