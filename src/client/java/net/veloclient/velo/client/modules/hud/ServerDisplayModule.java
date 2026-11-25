package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.LastServer;
import net.veloclient.velo.client.gui.title.MenuInfo;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Server Display: the server's icon and address on the HUD (for recordings and screenshots). The icon is the one the
 * title screen's "Jump back in" card already keeps (server list entry, saved list or a status ping), so nothing extra is
 * loaded. In singleplayer it shows the world name, or nothing.
 */
public final class ServerDisplayModule extends AbstractModule implements HudModule, Configurable {

	private final HudPosition position = new HudPosition(0.01f, 0.01f);
	private boolean showIcon = true;
	private boolean showPing = false;
	private boolean showInSingleplayer = false;
	private boolean useName = false;
	private boolean showBackground = true;

	public ServerDisplayModule() {
		super("server-display", "Server Display", "Shows the server's logo and IP on the HUD - great for videos and screenshots.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private String label() {
		if (net.veloclient.velo.client.hud.HudSample.active() && ClientCompat.currentServerAddress() == null && !ClientCompat.isSingleplayer()) {
			return "play.velo-client.com";
		}
		if (ClientCompat.isSingleplayer()) {
			if (!showInSingleplayer) {
				return null;
			}
			String world = ClientCompat.singleplayerWorldName();
			return world == null ? "Singleplayer" : world;
		}
		String address = ClientCompat.currentServerAddress();
		if (address == null) {
			return null;
		}
		if (useName) {
			String name = ClientCompat.currentServerName();
			if (name != null && !name.isBlank()) {
				return name;
			}
		}
		return address.endsWith(":25565") ? address.substring(0, address.length() - 6) : address;
	}

	private Identifier icon() {
		String address = ClientCompat.currentServerAddress();
		LastServer.Entry last = LastServer.get();
		return address != null && last != null && address.equalsIgnoreCase(last.address()) ? LastServer.icon() : null;
	}

	private String pingText() {
		int ping = net.veloclient.velo.client.util.OwnPing.ms();
		if (ping < 0) {
			ping = MenuInfo.ping();
		}
		return ping < 0 ? null : ping + " ms";
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		String text = label();
		if (text == null) {
			return;
		}
		int h = height();
		if (showBackground) {
			HudLook.stylePanel(context, x, y, width(), h);   // the HUD Style's panel (glass by default)
		}
		int tx = x + 5;
		if (showIcon) {
			Identifier icon = ClientCompat.isSingleplayer() ? null : icon();
			if (icon != null) {
				MenuKit.texture(context, icon, x + 4, y + 3, h - 6, 64);
			} else {
				MenuKit.logo(context, x + 4, y + 3, h - 6);
			}
			tx = x + h + 2;
		}
		String ping = showPing && !ClientCompat.isSingleplayer() ? pingText() : null;
		if (ping == null) {
			HudLook.draw(context, text, tx, y + (h - 8) / 2, 0xFFFFFFFF);
		} else {
			HudLook.draw(context, text, tx, y + h / 2 - 9, 0xFFFFFFFF);
			HudLook.draw(context, ping, tx, y + h / 2 + 1, 0xFFB9B2C4);
		}
	}

	@Override
	public int width() {
		String text = label();
		if (text == null) {
			text = "play.velo-client.com";
		}
		int w = HudLook.measure(text);
		String ping = showPing ? pingText() : null;
		if (ping != null) {
			w = Math.max(w, HudLook.measure(ping));
		}
		return w + 10 + (showIcon ? height() - 3 : 0);
	}

	@Override
	public int height() {
		return 22;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show Logo", () -> showIcon, v -> showIcon = v),
				new ConfigField.ToggleField("Show Server Name Instead Of IP", () -> useName, v -> useName = v),
				new ConfigField.ToggleField("Show Ping", () -> showPing, v -> showPing = v),
				new ConfigField.ToggleField("Show In Singleplayer", () -> showInSingleplayer, v -> showInSingleplayer = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
