package net.veloclient.velo.client.redstone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.List;

/** What a schematic placement needs: every block with how many are already built and how many are missing. */
public final class MaterialListScreen extends VeloWindow {

	private final Placements.Placement placement;
	private final List<Object[]> rows;
	private double scroll;

	public MaterialListScreen(Placements.Placement placement) {
		super(Text.literal("Materials - " + placement.name), 380, 340);
		this.placement = placement;
		this.rows = Placements.materials(placement);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		int x = contentX();
		int w = contentWidth();
		int total = 0;
		int done = 0;
		for (Object[] r : rows) {
			total += (int) r[1];
			done += (int) r[2];
		}
		String header = (placement.layerMode == Placements.LayerMode.ALL ? "All layers" : "Shown layers") + "  ·  " + done + " / " + total
				+ " placed";
		context.drawTextWithShadow(this.textRenderer, header, x, contentY(), 0xFFB8B8C8);
		int barW = w;
		context.fill(x, contentY() + 12, x + barW, contentY() + 15, 0xFF2A2A33);
		context.fill(x, contentY() + 12, x + (int) ((long) barW * done / Math.max(1, total)), contentY() + 15, 0xFF6FE39A);
		int top = contentY() + 22;
		int bottom = contentBottom();
		int rowH = 20;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * rowH - (bottom - top))));
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) scroll;
		for (Object[] r : rows) {
			if (y + rowH >= top && y <= bottom) {
				int need = (int) r[1];
				int have = (int) r[2];
				int missing = need - have;
				VeloDraw.fillRounded(context, x, y, w, rowH - 2, 4, missing == 0 ? 0xFF1E2A22 : 0xFF22222A);
				context.drawItemWithoutEntity((ItemStack) RmWorld.blockItem(r[3]), x + 2, y + 1);
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(((String) r[0]).replace("minecraft:", "").replace('_', ' '), w - 150), x + 22, y + 5,
						0xFFFFFFFF);
				String count = missing == 0 ? "done (" + need + ")" : missing + " missing" + stacks(missing) + "  ·  " + need;
				context.drawTextWithShadow(this.textRenderer, count, x + w - 6 - this.textRenderer.getWidth(count), y + 5,
						missing == 0 ? 0xFF6FE39A : 0xFFFFD84A);
			}
			y += rowH;
		}
		context.disableScissor();
	}

	private static String stacks(int count) {
		return count >= 64 ? " (" + count / 64 + "st" + (count % 64 > 0 ? " +" + count % 64 : "") + ")" : "";
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= verticalAmount * 20;
		return true;
	}
}
