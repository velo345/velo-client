package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloQueueSessionRow;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.modules.queue.BackgroundQueueManager;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The actual control panel for the Background Queue Session module - opened
 * via the module's own settings screen ("Manage Background Sessions...",
 * same pattern as "Manage Crosshairs..." for {@link CrosshairSelectScreen}).
 * Everything here is button-driven rather than keybind-only, since none of
 * the module's keybinds are bound to anything by default.
 *
 * <p>One row per server you've sent to the background ("ghost" session) -
 * you can hold several at once, one per server. Each row:
 * <ul>
 * <li><b>Peek</b> - shows that session's live status (and its last few chat
 * lines) in the on-screen overlay, without leaving whatever you're doing.
 * Toggle it off (or Peek a different one) any time.</li>
 * <li><b>Switch</b> - leaves whatever's currently active and actually plays
 * on that session.</li>
 * <li><b>Terminate</b> - ends that background connection for good (this one
 * really does drop you from the queue, unlike Switch/Peek).</li>
 * </ul>
 */
public final class BackgroundQueueSessionsScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 52;
	private static final int ROW_GAP = 6;
	private static final String EXPLAINER = "Send your current server/world to the background below. "
			+ "Peek checks status, Switch/Resume plays it, Terminate/Forget drops it.";

	private VeloScrollRegion scrollRegion;
	private VeloButton demoteButton;
	private Text status = Text.literal("");
	private List<String> explainerLines = List.of();

	public BackgroundQueueSessionsScreen(Screen parent) {
		super(Text.literal("Background Queue Sessions"), 420, 420);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		double previousScrollOffset = scrollRegion != null ? scrollRegion.scrollOffset() : 0;
		this.clearChildren();

		explainerLines = wrap(EXPLAINER, contentWidth());
		int y = contentY() + explainerLines.size() * (this.textRenderer.fontHeight + 1) + 4;

		String currentLabel = currentServerLabel();
		demoteButton = new VeloButton(contentX(), y, contentWidth(), 20,
				Text.literal(currentLabel == null ? "Not connected to anything right now" : "Send \"" + currentLabel + "\" to Background"),
				b -> {
					String key = BackgroundQueueManager.demote();
					status = Text.literal(key == null
							? "Couldn't background this - not connected to a server or world right now."
							: "\"" + key + "\" is now running in the background.");
					layoutContent();
				});
		demoteButton.active = currentLabel != null;
		addDrawableChild(demoteButton);
		y += 24;

		int listTop = y;
		int listBottom = contentBottom() - 20;
		scrollRegion = new VeloScrollRegion(contentX(), listTop, contentWidth(), Math.max(0, listBottom - listTop));
		scrollRegion.setScrollOffset(previousScrollOffset);

		List<BackgroundQueueManager.SessionSummary> sessions = new ArrayList<>(BackgroundQueueManager.sessions());
		for (BackgroundQueueManager.SessionSummary summary : sessions) {
			VeloQueueSessionRow row = new VeloQueueSessionRow(contentX(), 0, contentWidth(), ROW_HEIGHT, summary.key(),
					() -> {
						if (summary.key().equals(BackgroundQueueManager.peekedKey())) {
							BackgroundQueueManager.clearPeeked();
						} else {
							BackgroundQueueManager.setPeeked(summary.key());
						}
					},
					() -> {
						BackgroundQueueManager.promote(summary.key());
						status = Text.literal("Switched to \"" + summary.key() + "\".");
						layoutContent();
					},
					key -> {
						BackgroundQueueManager.terminate(key);
						status = Text.literal("Ended background session \"" + key + "\".");
						layoutContent();
					});
			addSelectableChild(row);
			scrollRegion.addRow(row);
		}
		scrollRegion.layout(ROW_HEIGHT, ROW_GAP);

		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private static String currentServerLabel() {
		MinecraftClient client = MinecraftClient.getInstance();
		var handler = client.getNetworkHandler();
		if (handler == null) {
			return null;
		}
		//? if <26.1 {
		boolean inSingleplayer = client.isInSingleplayer();
		var server = client.getCurrentServerEntry();
		String name = server != null ? server.name : null;
		//?} else {
		/*boolean inSingleplayer = client.hasSingleplayerServer();
		var server = client.getCurrentServer();
		String name = server != null ? server.name : null;
		*///?}
		if (inSingleplayer) {
			return "this singleplayer world";
		}
		return name != null ? name : "current server";
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layout(ROW_HEIGHT, ROW_GAP);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		Theme theme = ThemeManager.active();

		int ey = contentY();
		for (String line : explainerLines) {
			context.drawTextWithShadow(this.textRenderer, line, contentX(), ey, (theme.text() & 0x00FFFFFF) | 0xCCFFFFFF);
			ey += this.textRenderer.fontHeight + 1;
		}

		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbar(context, ROW_HEIGHT, ROW_GAP);
			if (BackgroundQueueManager.sessions().isEmpty()) {
				context.drawTextWithShadow(this.textRenderer, "No background sessions yet - use the button above to create one.",
						contentX(), contentBottom() - 60, theme.text());
			}
		}
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 32, theme.text());
	}

	private static List<String> wrap(String text, int maxWidth) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (textRenderer.getWidth(candidate) > maxWidth && !current.isEmpty()) {
				lines.add(current.toString());
				current = new StringBuilder(word);
			} else {
				current = new StringBuilder(candidate);
			}
		}
		if (!current.isEmpty()) {
			lines.add(current.toString());
		}
		return lines;
	}
}
