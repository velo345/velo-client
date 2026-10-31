package net.veloclient.velo.client.redstone;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pick a .veloschem / .litematic to load as a preview (Ctrl+L), with a search over the file names, and the other schematic
 * actions right below the list: save the selection, open the folder, browse community schematics.
 */
public final class SchematicPickScreen extends VeloWindow {

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final List<Path> files = SchematicIO.loadable();
	private double scroll;
	private TextFieldWidget search;
	private String query = "";

	private static final int SEARCH_H = 18;
	private static final int ACTIONS_H = 26;

	public SchematicPickScreen(Screen parent) {
		super(Text.literal("Load Schematic"), 420, 320);
		if (parent != null) {
			returnTo(parent);
		}
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		search = new TextFieldWidget(this.textRenderer, contentX(), contentY(), contentWidth(), SEARCH_H, Text.literal("Search"));
		search.setMaxLength(64);
		search.setPlaceholder(VeloUi.placeholder("Search schematics..."));
		search.setText(query);
		search.setChangedListener(text -> {
			query = text;
			scroll = 0;
		});
		addDrawableChild(search);
		setInitialFocus(search);
		int third = (contentWidth() - 8) / 3;
		int by = contentBottom() - 20;
		addDrawableChild(new VeloButton(contentX(), by, third, 20, Text.literal(RmState.hasSelection() ? "Save selection" : "Save selection..."),
				b -> {
					closeToGame();
					RmInput.saveSelection();
				}));
		addDrawableChild(new VeloButton(contentX() + third + 4, by, third, 20, Text.literal("Open folder"), b -> {
			try {
				java.nio.file.Files.createDirectories(SchematicIO.veloFolder());
			} catch (java.io.IOException ignored) {
				// Opening reports it.
			}
			net.veloclient.velo.client.gui.FileManagerOpener.open(SchematicIO.veloFolder().toFile(), RmState::info);
		}));
		addDrawableChild(new VeloButton(contentX() + (third + 4) * 2, by, contentWidth() - (third + 4) * 2, 20, Text.literal("Community..."),
				b -> net.minecraft.client.MinecraftClient.getInstance()
						.setScreen(new net.veloclient.velo.client.gui.CommunitySchematicsScreen(null))));
	}

	/** Files whose name has every word of the search in it (any order, ignoring case, _ and - count as spaces). */
	private List<Path> shown() {
		String q = query.toLowerCase(java.util.Locale.ROOT).replace('_', ' ').replace('-', ' ').strip();
		if (q.isEmpty()) {
			return files;
		}
		String[] words = q.split("\\s+");
		List<Path> out = new ArrayList<>();
		for (Path file : files) {
			String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).replace('_', ' ').replace('-', ' ');
			boolean all = true;
			for (String word : words) {
				if (!name.contains(word)) {
					all = false;
					break;
				}
			}
			if (all) {
				out.add(file);
			}
		}
		return out;
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		if (files.isEmpty()) {
			for (String line : VeloUi.wrap("No schematics yet. Select an area in Redstone Mode and press Save, or put .litematic"
					+ " files in .minecraft/schematics.",
					w)) {
				context.drawTextWithShadow(this.textRenderer, line, x, y, VeloUi.muted());
				y += 11;
			}
			return;
		}
		List<Path> files = shown();
		int top = y + SEARCH_H + 6;
		int bottom = contentBottom() - ACTIONS_H;
		if (files.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Nothing matches \"" + query + "\"", w), x, top + 4, VeloUi.muted());
			return;
		}
		context.enableScissor(x, top, x + w, bottom);
		int rowY = top - (int) scroll;
		for (Path file : files) {
			if (rowY + 20 >= top && rowY <= bottom) {
				boolean hover = VeloUi.inside(mouseX, mouseY, x, rowY, w, 20) && mouseY >= top && mouseY < bottom;
				VeloDraw.fillRounded(context, x, rowY, w, 20, 5, hover ? VeloUi.withAlpha(0xFFFFFFFF, 0x18) : VeloStyle.card());
				String name = file.getFileName().toString();
				boolean velo = name.endsWith(".veloschem");
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(name, w - 80), x + 6, rowY + 6, 0xFFFFFFFF);
				String tag = velo ? "Velo" : "Litematica";
				context.drawTextWithShadow(this.textRenderer, tag, x + w - 6 - this.textRenderer.getWidth(tag), rowY + 6, velo ? 0xFF8FB8FF : 0xFFFFC53D);
				int clipTop = Math.max(rowY, top);
				int clipBottom = Math.min(rowY + 20, bottom);
				if (clipBottom > clipTop) {
					hits.add(new VeloUi.Hit(x, clipTop, w, clipBottom - clipTop, () -> {
						SchematicIO.loadToClipboard(file);
						closeToGame();
					}));
				}
			}
			rowY += 22;
		}
		context.disableScissor();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, files.size() * 22 - (bottom - top))));
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
