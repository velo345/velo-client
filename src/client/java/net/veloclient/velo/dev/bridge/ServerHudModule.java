package net.veloclient.velo.dev.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * "Server HUD": what a Velo Bridge server places on the HUD - text (coins, rank), bars (boss health, quest progress),
 * timers (event countdown) and pictures - as one stack of cards the player moves in the HUD editor. Its settings also
 * hold the player's choices for the other server features: music, cutscenes and sharing the mod list.
 */
public final class ServerHudModule extends AbstractModule implements HudModule, Configurable {

	private static final int W = 168;
	private static ServerHudModule instance;
	private final HudPosition position = new HudPosition(0.985f, 0.36f);
	private boolean music = true;
	private boolean cutscenes = true;
	private boolean shareMods = true;
	private int lastHeight = 40;

	public ServerHudModule() {
		super("server-hud", "Server HUD", "Shows what Velo Bridge servers put on your HUD (quests, timers, boss bars). Also: server music, cutscenes, mod list sharing.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
	}

	static boolean music() {
		return instance == null || instance.music;
	}

	static boolean cutscenes() {
		return instance == null || instance.cutscenes;
	}

	/** Servers with Velo Bridge see your mod list (for their mod hints and logs) unless you switch it off. */
	static boolean shareMods() {
		return instance == null || instance.shareMods;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Server Music", () -> music, v -> {
					music = v;
					BridgeServerUi.musicSettingChanged();
				}),
				new ConfigField.ToggleField("Server Cutscenes", () -> cutscenes, v -> cutscenes = v),
				new ConfigField.ToggleField("Share My Mod List With Servers", () -> shareMods, v -> shareMods = v));
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private static boolean editing() {
		return net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		List<BridgeServerUi.HudItem> items = BridgeServerUi.hudItems();
		if (items.isEmpty() && editing()) {
			items = List.of(new BridgeServerUi.HudItem("demo", "bar", "Boss", "1200 / 2000", 0.6, 0, 0xFFFF5555, null, null, 0, 0, 0, 0),
					new BridgeServerUi.HudItem("demo2", "text", "Coins", "4,210", 0, 0, 0xFFF4B82E, "gold_nugget", null, 0, 0, 1, 0));
		}
		var tr = MinecraftClient.getInstance().textRenderer;
		int cy = y;
		long now = System.currentTimeMillis();
		for (BridgeServerUi.HudItem it : items) {
			int accent = it.color() | 0xFF000000;
			switch (it.type()) {
				case "image" -> {
					int w = Math.max(4, Math.min(W, it.w() <= 0 ? 64 : it.w()));
					int h = Math.max(4, Math.min(160, it.h() <= 0 ? 64 : it.h()));
					Identifier tex = BridgeServerUi.image(it.url());
					int ix = x + W - w;
					if (tex != null) {
						context.drawTexture(RenderPipelines.GUI_TEXTURED, tex, ix, cy, 0f, 0f, w, h, w, h);
					} else {
						VeloDraw.fillRounded(context, ix, cy, w, h, 6, 0x780F1014);
					}
					cy += h + 4;
				}
				case "bar" -> {
					int h = 30;
					card(context, x, cy, h, accent);
					int tx = x + 10;
					MenuKit.text(context, tr, MenuKit.small(it.title().toUpperCase(Locale.ROOT)), tx, cy + 6, 0xFFB4B7C0);
					var t = MenuKit.ui(it.text());
					MenuKit.text(context, tr, t, x + W - 8 - MenuKit.width(tr, t), cy + 6, 0xFFFFFFFF);
					int bx = tx, by = cy + 19, bw = W - 18;
					VeloDraw.fillRounded(context, bx, by, bw, 5, 2, 0x50FFFFFF);
					int fill = (int) Math.round(bw * Math.max(0, Math.min(1, it.value())));
					if (fill > 0) {
						VeloDraw.fillRounded(context, bx, by, Math.max(4, fill), 5, 2, accent);
					}
					cy += h + 4;
				}
				case "timer" -> {
					int h = 30;
					card(context, x, cy, h, accent);
					long left = Math.max(0, it.endsAt() - now) / 1000;
					String time = left >= 86400 ? (left / 86400) + "d " + (left % 86400 / 3600) + "h"
							: left >= 3600 ? String.format("%d:%02d:%02d", left / 3600, left / 60 % 60, left % 60) : String.format("%d:%02d", left / 60, left % 60);
					MenuKit.text(context, tr, MenuKit.small(it.title().toUpperCase(Locale.ROOT)), x + 10, cy + 6, 0xFFB4B7C0);
					MenuKit.text(context, tr, MenuKit.uiBold(left == 0 && it.endsAt() > 0 ? "Now" : time), x + 10, cy + 17, accent);
					cy += h + 4;
				}
				default -> {
					int h = it.title().isEmpty() ? 18 : 30;
					card(context, x, cy, h, accent);
					int tx = x + 10;
					if (it.icon() != null && !it.icon().isBlank()) {
						try {
							net.veloclient.velo.dev.storage.StorageMc.drawItem(context, net.veloclient.velo.dev.storage.StorageMc.stack(it.icon(), 1), tx - 1, cy + (h - 16) / 2, "");
							tx += 18;
						} catch (RuntimeException ignored) {
							// unknown item id
						}
					}
					if (!it.title().isEmpty()) {
						MenuKit.text(context, tr, MenuKit.small(it.title().toUpperCase(Locale.ROOT)), tx, cy + 6, 0xFFB4B7C0);
						MenuKit.text(context, tr, MenuKit.fit(tr, MenuKit.uiBold(it.text()), W - (tx - x) - 8), tx, cy + 17, 0xFFFFFFFF);
					} else {
						MenuKit.text(context, tr, MenuKit.fit(tr, MenuKit.uiBold(it.text()), W - (tx - x) - 8), tx, cy + 5, 0xFFFFFFFF);
					}
					cy += h + 4;
				}
			}
		}
		lastHeight = Math.max(18, cy - y - 4);
	}

	private static void card(DrawContext context, int x, int y, int h, int accent) {
		VeloDraw.fillRounded(context, x, y, W, h, 6, 0xB40F1014);
		VeloDraw.fillRounded(context, x + 3, y + 5, 2, h - 10, 1, accent);
	}

	@Override
	public int width() {
		return W;
	}

	@Override
	public int height() {
		return lastHeight;
	}
}
