package net.veloclient.velo.client.modules.hud;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps a short scrollback of action-bar messages (boss bar cooldowns, plugin
 * status text, etc.) that vanilla only ever shows for a moment - purely
 * reorganizing information the server already sent (design spec section 6.2).
 */
public final class ActionBarLogModule extends AbstractModule implements HudModule {

	private static final int MAX_HISTORY = 5;
	private final HudPosition position = new HudPosition(0.5f, 0.75f);
	private final Deque<String> history = new ArrayDeque<>();

	public ActionBarLogModule() {
		super("actionbar-log", "Action Bar Log", "Keeps a short scrollback of action bar messages.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		ClientReceiveMessageEvents.GAME.register(this::onGameMessage);
	}

	private void onGameMessage(net.minecraft.text.Text message, boolean overlay) {
		if (!isEnabled() || !overlay) {
			return;
		}
		history.addLast(message.getString());
		while (history.size() > MAX_HISTORY) {
			history.pollFirst();
		}
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		int lineHeight = client.textRenderer.fontHeight + 1;
		int rowY = y;
		for (String line : history) {
			int width = client.textRenderer.getWidth(line);
			context.drawTextWithShadow(client.textRenderer, line, x - width / 2, rowY, 0xFFC8C8C8);
			rowY += lineHeight;
		}
	}

	@Override
	public int width() {
		return 300;
	}

	@Override
	public int height() {
		return MAX_HISTORY * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}
}
