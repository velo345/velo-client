package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.RemoteTextureLoader;
import net.veloclient.velo.client.schematics.SchematicMatch;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * One schematic file in {@link net.veloclient.velo.client.gui.SchematicsScreen}'s
 * grid (same shape as {@link VeloCapeTile}, minus the equip/edit halves this
 * has no use for - a schematic is just a file, not something with its own
 * settings). When {@code match} is non-null and {@link SchematicMatch#found()},
 * the icon area shows the matched PhantomMarket thumbnail and title (see
 * {@link net.veloclient.velo.client.schematics.SchematicMatchCache}) and
 * clicking it opens that listing in the system browser; otherwise it shows a
 * plain glyph and filename. With {@code onLoad} (schematics Redstone Mode can
 * read) the icon area loads the file straight into Redstone Mode instead, with a
 * hover label saying so. The bottom strip is a
 * delete button - split with a convert button (.veloschem <-> .litematic)
 * when {@code onConvert} is given.
 */
public final class VeloSchematicTile extends ClickableWidget {

	private static final int BOTTOM_STRIP_HEIGHT = 16;

	private final Path file;
	private final String displayName;
	private final String sizeLabel;
	private final SchematicMatch match;
	private final Consumer<Path> onDelete;
	private final Runnable onOpenMatch;
	private final Consumer<Path> onConvert;
	private Runnable onLoad;
	private volatile Identifier thumbnail;

	public VeloSchematicTile(int x, int y, int width, int iconHeight, Path file, String displayName, String sizeLabel,
			SchematicMatch match, Consumer<Path> onDelete, Runnable onOpenMatch) {
		this(x, y, width, iconHeight, file, displayName, sizeLabel, match, onDelete, onOpenMatch, null);
	}

	public VeloSchematicTile(int x, int y, int width, int iconHeight, Path file, String displayName, String sizeLabel,
			SchematicMatch match, Consumer<Path> onDelete, Runnable onOpenMatch, Consumer<Path> onConvert) {
		super(x, y, width, iconHeight + BOTTOM_STRIP_HEIGHT, Text.literal(match != null && match.found() ? match.title() : displayName));
		this.file = file;
		this.displayName = displayName;
		this.sizeLabel = sizeLabel;
		this.match = match;
		this.onDelete = onDelete;
		this.onOpenMatch = onOpenMatch;
		this.onConvert = onConvert;
		if (match != null && match.found() && match.thumbnailUrl() != null) {
			RemoteTextureLoader.load(match.thumbnailUrl(), id -> this.thumbnail = id);
		}
	}

	private int iconAreaHeight() {
		return getHeight() - BOTTOM_STRIP_HEIGHT;
	}

	/** Clicking the icon area loads the schematic into Redstone Mode (takes over from opening the online match). */
	public VeloSchematicTile onLoad(Runnable onLoad) {
		this.onLoad = onLoad;
		return this;
	}

	private boolean matched() {
		return match != null && match.found();
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		int localY = (int) click.y() - getY();
		if (localY >= iconAreaHeight()) {
			if (onConvert != null && click.x() < getX() + getWidth() / 2.0) {
				onConvert.accept(file);
			} else {
				onDelete.accept(file);
			}
		} else if (onLoad != null) {
			onLoad.run();
		} else if (matched()) {
			onOpenMatch.run();
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		int iconHeight = iconAreaHeight();

		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), iconHeight, 5, theme.surfaceWithOpacity());
		VeloDraw.strokeRounded(context, getX(), getY(), getWidth(), iconHeight, 5, matched() ? theme.accentStart() : 0x33FFFFFF);

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		if (thumbnail != null) {
			int thumbSize = getWidth() - 8;
			context.drawTexture(RenderPipelines.GUI_TEXTURED, thumbnail, getX() + 4, getY() + 4, 0f, 0f,
					thumbSize, thumbSize - 20, thumbSize, thumbSize - 20, thumbSize, thumbSize - 20);
		} else {
			String glyph = "▦";
			int glyphWidth = textRenderer.getWidth(glyph);
			context.drawTextWithShadow(textRenderer, glyph, getX() + (getWidth() - glyphWidth) / 2, getY() + 6, theme.accentStart());
		}

		String name = trimToWidth(matched() ? match.title() : displayName, getWidth() - 6);
		int nameWidth = textRenderer.getWidth(name);
		context.drawTextWithShadow(textRenderer, name, getX() + (getWidth() - nameWidth) / 2, getY() + iconHeight - 22, theme.text());

		int sizeWidth = textRenderer.getWidth(sizeLabel);
		context.drawTextWithShadow(textRenderer, sizeLabel, getX() + (getWidth() - sizeWidth) / 2, getY() + iconHeight - 11, 0xFFAAAAAA);

		if (onLoad != null && isHovered() && mouseY < getY() + iconHeight) {
			VeloDraw.fillRounded(context, getX() + 2, getY() + 2, getWidth() - 4, iconHeight - 4, 4, 0xC0101016);
			String[] lines = {"Load in", "Redstone Mode"};
			int ly = getY() + (iconHeight - 22) / 2;
			for (String line : lines) {
				String t = trimToWidth(line, getWidth() - 8);
				context.drawTextWithShadow(textRenderer, t, getX() + (getWidth() - textRenderer.getWidth(t)) / 2, ly, theme.accentStart());
				ly += 12;
			}
		}

		int stripY = getY() + iconHeight;
		int deleteX = getX();
		int deleteW = getWidth();
		if (onConvert != null) {
			int half = getWidth() / 2;
			boolean convertHovered = isHovered() && mouseY >= stripY && mouseX < getX() + half;
			VeloDraw.fillRounded(context, getX(), stripY, half - 1, BOTTOM_STRIP_HEIGHT, 3, convertHovered ? 0xAA3A7BFF : 0x333A7BFF);
			boolean toLitematic = !file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".litematic");
			String convertLabel = trimToWidth(toLitematic ? "> Litematic" : "> Velo", half - 4);
			context.drawTextWithShadow(textRenderer, convertLabel, getX() + (half - textRenderer.getWidth(convertLabel)) / 2,
					stripY + (BOTTOM_STRIP_HEIGHT - textRenderer.fontHeight) / 2, 0xFFFFFFFF);
			deleteX = getX() + half + 1;
			deleteW = getWidth() - half - 1;
		}
		boolean deleteHovered = isHovered() && mouseY >= stripY && mouseX >= deleteX;
		int deleteColor = deleteHovered ? 0xAAFF5555 : 0x33FF5555;
		VeloDraw.fillRounded(context, deleteX, stripY, deleteW, BOTTOM_STRIP_HEIGHT, 3, deleteColor);
		String deleteLabel = "Remove";
		int labelWidth = textRenderer.getWidth(deleteLabel);
		context.drawTextWithShadow(textRenderer, deleteLabel, deleteX + (deleteW - labelWidth) / 2,
				stripY + (BOTTOM_STRIP_HEIGHT - textRenderer.fontHeight) / 2, 0xFFFFFFFF);
	}

	private static String trimToWidth(String text, int maxWidth) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		if (renderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String trimmed = text;
		while (trimmed.length() > 1 && renderer.getWidth(trimmed + "..") > maxWidth) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed + "..";
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
