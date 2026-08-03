package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.ModuleRegistry;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Keeps a short scrollback of action-bar messages (boss bar cooldowns, plugin
 * status text, etc.) that vanilla only ever shows for a moment - purely
 * reorganizing information the server already sent (design spec section 6.2).
 * Fed by {@link net.veloclient.velo.client.mixin.ActionBarOverlayMixin}
 * rather than a Fabric API message event, since actual action-bar text (the
 * dedicated packet {@code /title @s actionbar} and most plugins use) never
 * passes through the event this used to listen to.
 */
public final class ActionBarLogModule extends AbstractModule implements HudModule, Configurable {

	private static final int DEFAULT_MAX_LINES = 5;
	private static final int WIDTH = 300;

	private final HudPosition position = new HudPosition(0.5f, 0.75f);
	private final Deque<Text> history = new ArrayDeque<>();
	private int maxLines = DEFAULT_MAX_LINES;

	public ActionBarLogModule() {
		super("actionbar-log", "Action Bar Log", "Keeps a short scrollback of action bar messages.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	public static void capture(Text message) {
		var module = ModuleRegistry.get("actionbar-log").orElse(null);
		if (!(module instanceof ActionBarLogModule log) || !log.isEnabled()) {
			return;
		}
		// Keeps the message's own formatting (color, bold, etc.) instead of
		// flattening it to a plain string - that formatting was silently
		// discarded before, which is why the log always looked unstyled
		// regardless of what the server actually sent.
		log.history.addLast(message.copy());
		while (log.history.size() > log.maxLines) {
			log.history.pollFirst();
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
		// Left-aligned starting at x, matching every other HudModule's
		// contract (x is the box's left edge, per HudPosition#resolveX) -
		// this used to center each line on x instead, which routinely drew
		// half the text outside the box the HUD editor shows/drags.
		for (Text line : history) {
			context.drawTextWithShadow(client.textRenderer, line, x, rowY, 0xFFFFFFFF);
			rowY += lineHeight;
		}
	}

	@Override
	public int width() {
		return WIDTH;
	}

	@Override
	public int height() {
		// maxLines (the configured capacity), not history.size() (however
		// many messages happen to be queued right now) - a HUD edit box that
		// resizes itself as messages arrive/expire would be a fidgety thing
		// to try to position.
		return maxLines * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.SliderField("Max Lines", 1, 10, () -> maxLines, v -> {
			maxLines = (int) v;
			while (history.size() > maxLines) {
				history.pollFirst();
			}
		}, v -> String.valueOf((int) v)));
	}
}
