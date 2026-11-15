package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.ModuleRegistry;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Module List: the modules you have turned on, as a small list on the HUD (longest name first by default). On the right
 * half of the screen it lines up on the right edge. Rebuilt four times a second, not every frame.
 */
public final class ModuleListModule extends AbstractModule implements HudModule, Configurable {

	private static final List<String> SORT = List.of("Length", "A-Z");
	private static final List<String> COLOR = List.of("Accent", "White", "Rainbow");

	private final HudPosition position = new HudPosition(0.99f, 0.30f);
	private String sort = "Length";
	private String color = "Accent";
	private boolean hideHud = true;
	private boolean hideCosmetics = true;
	private boolean showBackground = false;
	private boolean sideBar = true;

	private List<String> names = List.of();
	private long builtAt;
	private int builtWidth;

	public ModuleListModule() {
		super("module-list", "Module List", "Lists the modules you have turned on, right on the HUD.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private void rebuild() {
		long now = System.currentTimeMillis();
		if (now - builtAt < 250 && !names.isEmpty()) {
			return;
		}
		builtAt = now;
		List<String> list = new ArrayList<>();
		for (Module module : ModuleRegistry.all()) {
			if (!module.isEnabled() || module == this) {
				continue;
			}
			if (hideHud && module.category() == ModuleCategory.HUD || hideCosmetics && module.category() == ModuleCategory.COSMETICS) {
				continue;
			}
			list.add(module.displayName());
		}
		if (list.isEmpty() && net.veloclient.velo.client.hud.HudSample.active()) {
			list.addAll(List.of("Performance Boost", "Motion Blur", "Toggle Sprint", "Zoom"));
		}
		if (sort.equals("A-Z")) {
			list.sort(String.CASE_INSENSITIVE_ORDER);
		} else {
			list.sort(Comparator.comparingInt((String n) -> -HudLook.measure(n)).thenComparing(String.CASE_INSENSITIVE_ORDER));
		}
		int w = 0;
		for (String s : list) {
			w = Math.max(w, HudLook.measure(s));
		}
		names = list;
		builtWidth = w;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		rebuild();
		boolean right = position.xFraction() > 0.5f;
		int lh = lineHeight();
		int total = width();
		long now = System.currentTimeMillis();
		for (int i = 0; i < names.size(); i++) {
			String s = names.get(i);
			int tw = HudLook.measure(s);
			int rowW = tw + 8;
			int rx = right ? x + total - rowW : x;
			int ry = y + i * lh;
			if (showBackground) {
				context.fill(rx, ry, rx + rowW, ry + lh, 0x88101014);
			}
			int c = switch (color) {
				case "White" -> 0xFFFFFFFF;
				case "Rainbow" -> 0xFF000000 | java.awt.Color.HSBtoRGB(((now / 4000f) + i * 0.06f) % 1f, 0.55f, 1f);
				default -> HudLook.accent() | 0xFF000000;
			};
			if (sideBar) {
				int bx = right ? rx + rowW : rx - 2;
				context.fill(bx, ry, bx + 2, ry + lh, c);
			}
			HudLook.draw(context, s, rx + 4, ry + (lh - 8) / 2, c);
		}
	}

	private int lineHeight() {
		return 11;
	}

	@Override
	public int width() {
		rebuild();
		return Math.max(40, builtWidth + 10);
	}

	@Override
	public int height() {
		rebuild();
		return Math.max(lineHeight(), names.size() * lineHeight());
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Sort", SORT, () -> sort, v -> {
					sort = v;
					builtAt = 0;
				}),
				new ConfigField.ChoiceField("Color", COLOR, () -> color, v -> color = v),
				new ConfigField.ToggleField("Hide HUD Elements", () -> hideHud, v -> {
					hideHud = v;
					builtAt = 0;
				}),
				new ConfigField.ToggleField("Hide Cosmetics", () -> hideCosmetics, v -> {
					hideCosmetics = v;
					builtAt = 0;
				}),
				new ConfigField.ToggleField("Side Bar", () -> sideBar, v -> sideBar = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
