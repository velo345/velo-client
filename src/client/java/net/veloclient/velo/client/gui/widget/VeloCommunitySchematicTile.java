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
import net.veloclient.velo.client.schematics.CommunitySchematicsClient;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * One shared schematic in the Community browser: its preview picture, title, author, upvotes and
 * downloads, and its format. Click opens the details (download, upvote).
 */
public final class VeloCommunitySchematicTile extends ClickableWidget {

	private final CommunitySchematicsClient.Item item;
	private final Runnable onClick;
	private volatile Identifier preview;

	public VeloCommunitySchematicTile(int width, CommunitySchematicsClient.Item item, Runnable onClick) {
		super(0, 0, width, width * 3 / 4 + 26, Text.literal(item.title()));
		this.item = item;
		this.onClick = onClick;
		RemoteTextureLoader.load(CommunitySchematicsClient.previewUrl(item.id()), id -> this.preview = id);
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		onClick.run();
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int imageHeight = getWidth() * 3 / 4;
		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), imageHeight, 5, 0xFF1A1A22);
		if (preview != null) {
			context.drawTexture(RenderPipelines.GUI_TEXTURED, preview, getX(), getY(), 0f, 0f, getWidth(), imageHeight, getWidth(), imageHeight,
					getWidth(), imageHeight);
		}
		String badge = "litematic".equals(item.format()) ? "Litematica" : "Velo";
		int bw = textRenderer.getWidth(badge) + 8;
		VeloDraw.fillRounded(context, getX() + 4, getY() + 4, bw, 12, 3, 0xC0101016);
		context.drawTextWithShadow(textRenderer, badge, getX() + 8, getY() + 6, 0xFFCFCFE0);
		String votes = "▲ " + item.votes();
		int vw = textRenderer.getWidth(votes) + 8;
		VeloDraw.fillRounded(context, getX() + getWidth() - vw - 4, getY() + 4, vw, 12, 3, item.voted() ? 0xE0D04040 : 0xC0101016);
		context.drawTextWithShadow(textRenderer, votes, getX() + getWidth() - vw, getY() + 6, 0xFFFFFFFF);
		if (isHovered()) {
			VeloDraw.strokeRounded(context, getX(), getY(), getWidth(), imageHeight, 5, theme.accentStart());
		}
		context.drawTextWithShadow(textRenderer, VeloUi.trim(item.title(), getWidth() - 4), getX() + 2, getY() + imageHeight + 4, theme.text());
		String meta = item.author() + "  ·  " + item.downloads() + " downloads";
		context.drawTextWithShadow(textRenderer, VeloUi.trim(meta, getWidth() - 4), getX() + 2, getY() + imageHeight + 15, 0xFFAAAAAA);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
