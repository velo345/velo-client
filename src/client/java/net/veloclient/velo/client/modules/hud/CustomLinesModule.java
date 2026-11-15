package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Custom Te\u0078t (written escaped - Stonecutter renames the bare word on 26.x): up to eight lines of your own text on the HUD, with placeholders that fill in live - {fps}, {ping},
 * {x} {y} {z}, {facing}, {server}, {player}, {health}, {time}, {date}, {cps}. Empty lines are left out, so lines are
 * added and removed just by typing or clearing them. "&" colour codes work like on servers (&a green, &l bold...).
 */
public final class CustomLinesModule extends AbstractModule implements HudModule, Configurable {

	private static final int LINES = 8;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final List<String> ALIGN = List.of("Auto", "Left", "Center", "Right");

	private final HudPosition position = new HudPosition(0.01f, 0.40f);
	private final String[] lines = new String[LINES];
	private String align = "Auto";
	private boolean showBackground = false;
	private boolean shadow = true;

	private List<net.minecraft.text.Text> built = List.of();
	private long builtAt;
	private int builtWidth;

	public CustomLinesModule() {
		super("custom-text", "Custom Te\u0078t", "Your own text on the HUD with live placeholders like {fps}, {ping}, {server} and {x} {y} {z}.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		lines[0] = "&b{player} &7on &f{server}";
		lines[1] = "&7{fps} FPS &8| &7{ping} ms";
		for (int i = 2; i < LINES; i++) {
			lines[i] = "";
		}
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private void rebuild() {
		long now = System.currentTimeMillis();
		if (now - builtAt < 100) {
			return;
		}
		builtAt = now;
		List<net.minecraft.text.Text> out = new ArrayList<>();
		int w = 0;
		for (String line : lines) {
			if (line == null || line.isBlank()) {
				continue;
			}
			net.minecraft.text.Text text = net.minecraft.text.Text.literal(fill(line).replace('&', '§'));
			out.add(text);
			w = Math.max(w, HudLook.measure(text));
		}
		built = out;
		builtWidth = w;
	}

	private static String fill(String line) {
		if (line.indexOf('{') < 0) {
			return line;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		var player = client.player;
		StringBuilder sb = new StringBuilder(line.length() + 16);
		int i = 0;
		while (i < line.length()) {
			char c = line.charAt(i);
			int end = c == '{' ? line.indexOf('}', i) : -1;
			if (end < 0) {
				sb.append(c);
				i++;
				continue;
			}
			String key = line.substring(i + 1, end).toLowerCase(Locale.ROOT);
			String value = switch (key) {
				case "fps" -> String.valueOf(client.getCurrentFps());
				case "ping" -> {
					int ms = net.veloclient.velo.client.util.OwnPing.ms();
					if (ms < 0) {
						ms = net.veloclient.velo.client.gui.title.MenuInfo.ping();
					}
					yield ms < 0 ? "-" : String.valueOf(ms);
				}
				case "x" -> player == null ? "-" : String.valueOf((int) Math.floor(player.getX()));
				case "y" -> player == null ? "-" : String.valueOf((int) Math.floor(player.getY()));
				case "z" -> player == null ? "-" : String.valueOf((int) Math.floor(player.getZ()));
				case "facing" -> player == null ? "-" : facing(player.getYaw());
				case "server" -> {
					if (ClientCompat.isSingleplayer()) {
						yield "Singleplayer";
					}
					String address = ClientCompat.currentServerAddress();
					yield address == null ? "-" : address;
				}
				case "player" -> selfName(client);
				case "health" -> player == null ? "-" : String.valueOf(Math.round(player.getHealth()));
				case "time" -> LocalTime.now().format(TIME);
				case "date" -> LocalDate.now().format(DATE);
				case "cps" -> String.valueOf(CpsTracker.leftCps());
				default -> null;
			};
			if (value == null) {
				sb.append(line, i, end + 1);
			} else {
				sb.append(value);
			}
			i = end + 1;
		}
		return sb.toString();
	}

	private static String selfName(MinecraftClient client) {
		//? if <26.1 {
		return client.getSession().getUsername();
		//?} else {
		/*return client.getUser().getName();
		*///?}
	}

	private static String facing(float yaw) {
		float y = ((yaw % 360) + 360) % 360;
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		return names[Math.round(y / 45f) % 8];
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		CpsTracker.ensureRegistered();
		rebuild();
		if (built.isEmpty()) {
			return;
		}
		int w = width();
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, w, height(), 5, 0x90101014);
		}
		String a = align.equals("Auto") ? (position.xFraction() > 0.66f ? "Right" : position.xFraction() > 0.34f ? "Center" : "Left") : align;
		for (int i = 0; i < built.size(); i++) {
			net.minecraft.text.Text t = built.get(i);
			int tw = HudLook.measure(t);
			int tx = switch (a) {
				case "Right" -> x + w - 3 - tw;
				case "Center" -> x + (w - tw) / 2;
				default -> x + 3;
			};
			int ty = y + 3 + i * 10;
			if (shadow) {
				HudLook.draw(context, t, tx, ty, 0xFFFFFFFF);
			} else {
				net.veloclient.velo.client.gui.title.MenuKit.text(context, MinecraftClient.getInstance().textRenderer, t, tx, ty, 0xFFFFFFFF);
			}
		}
	}

	@Override
	public int width() {
		rebuild();
		return Math.max(30, builtWidth + 6);
	}

	@Override
	public int height() {
		rebuild();
		return Math.max(10, built.size() * 10) + 5;
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		for (int i = 0; i < LINES; i++) {
			int index = i;
			fields.add(new ConfigField.TextField("Line " + (i + 1), i == 0 ? "e.g. {fps} FPS | {ping} ms" : "(empty = hidden)",
					() -> lines[index], v -> {
						lines[index] = v == null ? "" : v;
						builtAt = 0;
					}));
		}
		fields.add(new ConfigField.ChoiceField("Align", ALIGN, () -> align, v -> align = v));
		fields.add(new ConfigField.ToggleField("Shadow", () -> shadow, v -> shadow = v));
		fields.add(new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
		return fields;
	}
}
