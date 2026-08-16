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
 * plain glyph and filename with no click action. The bottom strip is always
 * a single full-width delete button.
 */
public final class VeloSchematicTile extends ClickableWidget {

	private static final int BOTTOM_STRIP_HEIGHT = 16;

	private final Path file;
	private final String displayName;
	private final String sizeLabel;
	private final SchematicMatch match;
	private final Consumer<Path> onDelete;
	private final Runnable onOpenMatch;
	private volatile Identifier thumbnail;

	public VeloSchematicTile(int x, int y, int width, int iconHeight, Path file, String displayName, String sizeLabel,
			SchematicMatch match, Consumer<Path> onDelete, Runnable onOpenMatch) {
		super(x, y, width, iconHeight + BOTTOM_STRIP_HEIGHT, Text.literal(match != null && match.found() ? match.title() : displayName));
		this.file = file;
		this.displayName = displayName;
		this.sizeLabel = sizeLabel;
		this.match = match;
		this.onDelete = onDelete;
		this.onOpenMatch = onOpenMatch;
		if (match != null && match.found() && match.thumbnailUrl() != null) {
			RemoteTextureLoader.load(match.thumbnailUrl(), id -> this.thumbnail = id);
		}
	}

	private int iconAreaHeight() {
		return getHeight() - BOTTOM_STRIP_HEIGHT;
	}

	private boolean matched() {
		return match != null && match.found();
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		int localY = (int) click.y() - getY();
		if (localY >= iconAreaHeight()) {
			onDelete.accept(file);
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

		int stripY = getY() + iconHeight;
		boolean deleteHovered = isHovered() && mouseY >= stripY;
		int deleteColor = deleteHovered ? 0xAAFF5555 : 0x33FF5555;
		VeloDraw.fillRounded(context, getX(), stripY, getWidth(), BOTTOM_STRIP_HEIGHT, 3, deleteColor);
		String deleteLabel = "Remove";
		int labelWidth = textRenderer.getWidth(deleteLabel);
		context.drawTextWithShadow(textRenderer, deleteLabel, getX() + (getWidth() - labelWidth) / 2,
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
