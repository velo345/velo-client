package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.Module;

import java.util.Set;

/**
 * Simulated previews (2026-11, owner: "always show the feature"): for modules whose effect only appears in certain
 * moments - a hit, a low-health second, a burning player, a block you look at - the preview plays a small scene that
 * shows it, using the module's own settings (its colour, size...) and, where the module draws through Velo code, the
 * module's real drawing (hearts, hunger, armor, HUD style). Some scenes stand on a figure (your player, drawn by
 * {@link net.veloclient.velo.client.gui.widget.EntityPreviewWidget}); their overlays are drawn on top of it.
 */
public final class ModuleScenes {

	/** Scenes drawn around your player. */
	private static final Set<String> FIGURE = Set.of("hit-color", "damage-numbers", "health-indicator", "enemy-effects", "enemy-hunger",
			"own-name-tag", "nick-hider", "hit-particles", "particle-size", "particle-limiter", "low-health-warning", "hold-up-items",
			"item-position", "kill-effects", "hitbox-visualizer");
	/** Scenes without a figure. */
	private static final Set<String> PLAIN = Set.of("hearts", "hunger", "armor-bar", "xp-bar", "hotbar", "hud-style", "small-caps",
			"block-outline", "tnt-timer", "anti-deepslate", "hide-foliage", "full-bright", "no-break-particles", "container-animations",
			"waypoints", "better-tab-list", "chat-heads", "block-break-progress", "glint-colorizer", "item-physics", "chunk-animator", "phantom",
			"polyblur", "input-sampler", "color-grading", "chunk-border-overlay", "world-border-visualizer", "light-level-overlay",
			"old-animations", "low-fire", "potion-timers", "scoreboard-hud");

	private ModuleScenes() {
	}

	public static boolean has(Module module) {
		return FIGURE.contains(module.id()) || PLAIN.contains(module.id());
	}

	public static boolean wantsFigure(Module module) {
		return FIGURE.contains(module.id()) && !module.id().equals("kill-effects");
	}

	public static boolean hurtPulse(Module module) {
		return module.id().equals("hit-color");
	}

	private static double time() {
		return (System.nanoTime() / 1_000_000L) / 1000.0;
	}

	/** The module's first colour setting (else {@code fallback}). */
	private static int color(Module module, int fallback) {
		if (module instanceof Configurable configurable) {
			for (ConfigField field : configurable.configFields()) {
				if (field instanceof ConfigField.ColorField c) {
					return c.get().getAsInt() | 0xFF000000;
				}
			}
		}
		return fallback;
	}

	/** The module's slider whose label contains {@code part} (else {@code fallback}). */
	private static double slider(Module module, String part, double fallback) {
		if (module instanceof Configurable configurable) {
			for (ConfigField field : configurable.configFields()) {
				if (field instanceof ConfigField.SliderField s && s.label().toLowerCase(java.util.Locale.ROOT).contains(part)) {
					return s.get().getAsDouble();
				}
			}
		}
		return fallback;
	}

	private static String choice(Module module, String part, String fallback) {
		if (module instanceof Configurable configurable) {
			for (ConfigField field : configurable.configFields()) {
				if (field instanceof ConfigField.ChoiceField c && c.label().toLowerCase(java.util.Locale.ROOT).contains(part)) {
					return c.get().get();
				}
			}
		}
		return fallback;
	}

	private static boolean toggle(Module module, String part, boolean fallback) {
		if (module instanceof Configurable configurable) {
			for (ConfigField field : configurable.configFields()) {
				if (field instanceof ConfigField.ToggleField t && t.label().toLowerCase(java.util.Locale.ROOT).contains(part)) {
					return t.get().getAsBoolean();
				}
			}
		}
		return fallback;
	}

	/** Any texture of the game (path under assets/minecraft/textures), tinted. */
	private static void tex(DrawContext context, String path, int x, int y, int w, int h, int texW, int texH, int v, int tint) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "textures/" + path + ".png"), x, y, 0f, (float) v, w, h,
				texW, texW, texW, texH, tint);
	}

	/** A particle sprite (8x8 texture) drawn at {@code size} px. */
	private static void particle(DrawContext context, String name, int x, int y, int size, int tint) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "textures/particle/" + name + ".png"), x - size / 2, y - size / 2,
				0f, 0f, size, size, 8, 8, 8, 8, tint);
	}

	/** The particle sprite of a Hit Particles style (and its tint). */
	private static String[] styleSprite(String style, int dust) {
		return switch (style) {
			case "Crit" -> new String[] {"critical_hit", "FFFFFFFF"};
			case "Magic" -> new String[] {"enchanted_hit", "FFFFFFFF"};
			case "Crit + Magic" -> new String[] {"critical_hit", "FFFFFFFF", "enchanted_hit"};
			case "Blood" -> new String[] {"generic_2", "FFB01818"};
			case "Hearts" -> new String[] {"heart", "FFFFFFFF"};
			case "Flame" -> new String[] {"flame", "FFFFFFFF"};
			case "Soul" -> new String[] {"soul_fire_flame", "FFFFFFFF"};
			case "Totem" -> new String[] {"glitter_3", "FFE8D040"};
			case "Sparks" -> new String[] {"spark_2", "FFFFFFFF"};
			case "Notes" -> new String[] {"note", "FF55FF77"};
			case "Cloud" -> new String[] {"generic_5", "FFF0F0F0"};
			case "Dust" -> new String[] {"generic_1", Integer.toHexString(dust | 0xFF000000).toUpperCase(java.util.Locale.ROOT)};
			default -> null;
		};
	}

	private static void burst(DrawContext context, String[] sprite, int cx, int cy, double p, int n, double size, double spread) {
		if (sprite == null) {
			return;
		}
		int tint = (int) Long.parseLong(sprite[1], 16);
		for (int i = 0; i < n; i++) {
			double a = i * (Math.PI * 2 / n) + i * 0.41;
			double r = 6 + p * spread * (0.6 + (i % 3) * 0.2);
			int px = (int) (cx + Math.cos(a) * r);
			int py = (int) (cy + Math.sin(a) * r * 0.75 + p * p * 10);
			String name = sprite.length > 2 && i % 2 == 1 ? sprite[2] : sprite[0];
			particle(context, name, px, py, (int) Math.max(2, Math.round(8 * size * (1 - p * 0.5))), VeloUi.withAlpha(tint, (int) (255 * (1 - p))));
		}
	}

	private static String text(Module module, String part, String fallback) {
		if (module instanceof Configurable configurable) {
			for (ConfigField field : configurable.configFields()) {
				if (field instanceof ConfigField.TextField t && t.label().toLowerCase(java.util.Locale.ROOT).contains(part)) {
					String v = t.get().get();
					return v == null || v.isBlank() ? fallback : v;
				}
			}
		}
		return fallback;
	}

	private static void block(DrawContext context, String texture, int x, int y, int size, int tint) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "textures/block/" + texture + ".png"), x, y, 0f, 0f, size, size,
				16, 16, 16, 16, tint);
	}

	private static void sprite(DrawContext context, String name, int x, int y, int w, int h) {
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", name), x, y, w, h);
	}

	/** Under the figure (or the whole scene when there is none). */
	public static void drawUnder(DrawContext context, Module module, int x, int y, int w, int h) {
		var tr = MinecraftClient.getInstance().textRenderer;
		double t = time();
		boolean on = module.isEnabled();
		ModulePreview.backdrop(context, x, y, w, h);
		int cx = x + w / 2;
		int cy = y + h / 2;
		var player = MinecraftClient.getInstance().player;
		int ticks = (int) (t * 20);
		context.enableScissor(x, y, x + w, y + h);
		try {
			switch (module.id()) {
				case "hearts" -> {
					int left = cx - 40;
					boolean drawn = player != null && net.veloclient.velo.client.modules.hud.HeartsModule.render(context, player, left, cy + 4, 10, -1,
							20f, 14, 14, 4, false, ticks);
					if (!drawn) {
						for (int i = 0; i < 10; i++) {
							sprite(context, "hud/heart/container", left + i * 8, cy, 9, 9);
							if (i < 7) {
								sprite(context, "hud/heart/full", left + i * 8, cy, 9, 9);
							}
						}
					}
				}
				case "hunger" -> {
					boolean drawn = player != null && net.veloclient.velo.client.modules.hud.HungerModule.render(context, player, cy, cx + 40, ticks);
					if (!drawn) {
						for (int i = 0; i < 10; i++) {
							sprite(context, "hud/food_empty", cx + 31 - i * 8, cy, 9, 9);
							if (i < 8) {
								sprite(context, "hud/food_full", cx + 31 - i * 8, cy, 9, 9);
							}
						}
					}
				}
				case "armor-bar" -> {
					// a sample armour bar (yours may be empty right now)
					for (int i = 0; i < 10; i++) {
						sprite(context, i < 6 ? "hud/armor_full" : i == 6 ? "hud/armor_half" : "hud/armor_empty", cx - 40 + i * 8, cy, 9, 9);
					}
				}
				case "potion-timers" -> {
					String[][] fx = {{"speed", "Speed II", "1:24"}, {"strength", "Strength", "0:52"}, {"fire_resistance", "Fire Resistance", "6:30"}};
					int ry = cy - 30;
					for (String[] f : fx) {
						tex(context, "mob_effect/" + f[0], cx - 60, ry, 18, 18, 18, 18, 0, 0xFFFFFFFF);
						net.veloclient.velo.client.hud.HudLook.draw(context, f[1], cx - 38, ry + 1, 0xFFFFFFFF);
						net.veloclient.velo.client.hud.HudLook.draw(context, f[2], cx - 38, ry + 10, 0xFFB3A8A9);
						ry += 22;
					}
				}
				case "scoreboard-hud" -> {
					String[] lines = {"Kills: 12", "Coins: 4,210", "", "Map: Lighthouse", "Players: 14/16", "", "play.example.net"};
					int pw = 110;
					int px = x + w - pw - 10;
					int py = cy - 50;
					context.fill(px, py, px + pw, py + 12, 0x66000000);
					Text head = Text.literal("BED WARS");
					MenuKit.text(context, tr, head, px + (pw - MenuKit.width(tr, head)) / 2, py + 2, 0xFFFFFF55);
					context.fill(px, py + 12, px + pw, py + 12 + lines.length * 10 + 2, 0x4D000000);
					for (int i = 0; i < lines.length; i++) {
						MenuKit.text(context, tr, Text.literal(lines[i]), px + 3, py + 14 + i * 10, 0xFFFFFFFF);
					}
				}
				case "xp-bar" -> {
					sprite(context, "hud/experience_bar_background", cx - 91, cy + 4, 182, 5);
					int fill = (int) (182 * (0.35 + 0.3 * Math.sin(t * 0.8)));
					context.enableScissor(cx - 91, cy + 4, cx - 91 + fill, cy + 9);
					sprite(context, "hud/experience_bar_progress", cx - 91, cy + 4, 182, 5);
					context.disableScissor();
					context.enableScissor(x, y, x + w, y + h);
					Text level = Text.literal("27");
					int lw = MenuKit.width(tr, level);
					MenuKit.text(context, tr, level, cx - lw / 2, cy - 6, 0xFF80FF20);
				}
				case "hotbar" -> {
					sprite(context, "hud/hotbar", cx - 91, cy - 11, 182, 22);
					int slot = (int) (t * 1.2) % 9;
					sprite(context, "hud/hotbar_selection", cx - 92 + slot * 20, cy - 12, 24, 23);
				}
				case "hud-style" -> {
					int row = y + 18;
					for (String id : new String[] {"fps-counter", "coordinates", "clock"}) {
						var hud = net.veloclient.velo.module.ModuleRegistry.get(id).orElse(null);
						if (hud instanceof net.veloclient.velo.client.hud.HudModule element) {
							ModulePreview.drawHud(context, element, x + 8, row, w - 16, (h - 30) / 3, 1f);
							row += (h - 30) / 3;
						}
					}
				}
				case "small-caps" -> {
					// text, so you see the small capitals (numbers have none)
					net.veloclient.velo.client.modules.qol.SmallCapsModule.setHudRenderActive(true);
					net.veloclient.velo.client.gui.VeloFonts.beginHud();
					try {
						String[] lines = {"Velo Client", "Welcome back, Player", "Server Overlays · Bed Wars"};
						int ly = cy - 20;
						for (String line : lines) {
							int lw = net.veloclient.velo.client.hud.HudLook.measure(line);
							net.veloclient.velo.client.hud.HudLook.draw(context, line, cx - lw / 2, ly, 0xFFFFFFFF);
							ly += 14;
						}
					} finally {
						net.veloclient.velo.client.gui.VeloFonts.endHud();
						net.veloclient.velo.client.modules.qol.SmallCapsModule.setHudRenderActive(false);
					}
				}
				case "block-outline" -> {
					int s = Math.min(w, h) / 2;
					int bx = cx - s / 2;
					int by = cy - s / 2;
					block(context, "grass_block_side", bx, by, s, 0xFFFFFFFF);
					if (on) {
						int c = color(module, 0xFF000000);
						int lw = Math.max(1, (int) Math.round(slider(module, "width", 1.0) * 2));
						context.fill(bx - lw, by - lw, bx + s + lw, by, c);
						context.fill(bx - lw, by + s, bx + s + lw, by + s + lw, c);
						context.fill(bx - lw, by, bx, by + s, c);
						context.fill(bx + s, by, bx + s + lw, by + s, c);
					} else {
						VeloDraw.strokeRounded(context, bx, by, s, s, 0, 0x99000000);
					}
				}
				case "tnt-timer" -> {
					int s = Math.min(w, h) / 2;
					boolean flash = (int) (t * 4) % 2 == 0;
					block(context, "tnt_side", cx - s / 2, cy - s / 2 + 8, s, flash ? 0xFFFFFFFF : 0xFFE0E0E0);
					if (on) {
						double left = 4 - (t % 4);
						Text label = Text.literal(String.format(java.util.Locale.ROOT, "%.1f", left));
						int c = left < 1 ? 0xFFFF5555 : left < 2 ? 0xFFFFAA00 : 0xFF55FF55;
						MenuKit.text(context, tr, label, cx - MenuKit.width(tr, label) / 2, cy - s / 2 - 4, c);
					}
				}
				case "anti-deepslate" -> {
					int s = 30;
					for (int i = 0; i < 4; i++) {
						for (int j = 0; j < 2; j++) {
							String with = text(module, "replace", "minecraft:netherite_block");
							String path = with.contains(":") ? with.substring(with.indexOf(':') + 1) : with;
							block(context, on ? path : "deepslate", cx - 2 * s + i * s, cy - s + j * s, s, 0xFFFFFFFF);
						}
					}
				}
				case "hide-foliage" -> {
					int s = 30;
					for (int i = 0; i < 4; i++) {
						block(context, "grass_block_side", cx - 2 * s + i * s, cy, s, 0xFFFFFFFF);
						if (!on) {
							block(context, i % 2 == 0 ? "short_grass" : "fern", cx - 2 * s + i * s, cy - s, s, 0xFF7CBD6B);
						}
					}
				}
				case "full-bright" -> {
					int s = 30;
					for (int i = 0; i < 5; i++) {
						for (int j = 0; j < 4; j++) {
							block(context, (i + j) % 3 == 0 ? "coal_ore" : "stone", x + 4 + i * s, y + 4 + j * s, s, 0xFFFFFFFF);
						}
					}
					if (!on) {
						context.fill(x, y, x + w, y + h, 0xC0000000);
					}
				}
				case "no-break-particles" -> {
					int s = Math.min(w, h) / 2;
					block(context, "stone", cx - s / 2, cy - s / 2, s, 0xFFFFFFFF);
					int stage = (int) (t * 4) % 10;
					block(context, "destroy_stage_" + stage, cx - s / 2, cy - s / 2, s, 0xFFFFFFFF);
					if (!on) {
						for (int i = 0; i < 10; i++) {
							double a = i * 0.63 + t * 2;
							int px = (int) (cx + Math.cos(a) * (s * 0.6 + (t * 20 % 10)));
							int py = (int) (cy + Math.sin(a) * s * 0.5);
							context.fill(px, py, px + 3, py + 3, 0xFF8A8A8A);
						}
					}
				}
				case "container-animations" -> {
					double open = on ? (Math.sin(t * 1.6) * 0.5 + 0.5) : (Math.sin(t * 1.6) > 0 ? 1 : 0);
					int cw = 70;
					int chh = 34;
					int bx = cx - cw / 2;
					int by = cy;
					context.fill(bx, by, bx + cw, by + chh, 0xFF8A5A2B);
					context.fill(bx, by, bx + cw, by + 2, 0xFF5A3A1B);
					int lid = (int) Math.round(18 * (1 - open * 0.85));
					context.fill(bx, by - lid, bx + cw, by, 0xFF9C6A35);
					context.fill(bx + cw / 2 - 3, by - 4, bx + cw / 2 + 3, by + 4, 0xFFC9C9C9);
				}
				case "waypoints" -> {
					int c = color(module, 0xFF55FFFF);
					for (int i = 0; i < 6; i++) {
						context.fill(cx - 3 + i, y, cx - 2 + i, cy + 30, VeloUi.withAlpha(c, 0x30 + i * 0x10));
					}
					Text label = Text.literal("Base  ·  124m");
					int lw = MenuKit.width(tr, label) + 8;
					VeloDraw.fillRounded(context, cx - lw / 2, cy - 20, lw, 13, 3, 0xA0000000);
					MenuKit.text(context, tr, label, cx - lw / 2 + 4, cy - 17, c);
				}
				case "better-tab-list" -> {
					String ping = choice(module, "ping", "Number");
					boolean colored = toggle(module, "color", true);
					boolean ms = toggle(module, "\"ms\"", true);
					int pw = w - 24;
					int px = x + 12;
					int py = y + 10;
					context.fill(px, py, px + pw, py + h - 20, 0x80000000);
					Text title = Text.literal("play.example.net");
					MenuKit.text(context, tr, title, px + (pw - MenuKit.width(tr, title)) / 2, py + 3, 0xFFFFFFFF);
					String[] names = {"Notch", "jeb_", "Dinnerbone", "Grumm", "Alex", "Steve", "Velo2k", "Kaaayo"};
					int[] pings = {18, 64, 140, 260, 33, 95, 12, 210};
					int colW = (pw - 6) / 2;
					for (int i = 0; i < names.length; i++) {
						int rx = px + 2 + (i / 4) * (colW + 2);
						int ry = py + 16 + (i % 4) * 11;
						context.fill(rx, ry, rx + colW, ry + 10, 0x40FFFFFF);
						net.veloclient.velo.client.gui.PlayerHeads.draw(context, null, names[i], rx + 1, ry + 1, 8);
						MenuKit.text(context, tr, Text.literal(names[i]), rx + 11, ry + 1, 0xFFFFFFFF);
						int c = !colored ? 0xFFDDDDDD : pings[i] < 80 ? 0xFF55FF55 : pings[i] < 180 ? 0xFFFFDD55 : 0xFFFF5555;
						int right = rx + colW - 2;
						if (!ping.equals("Bars")) {
							Text pt = Text.literal(pings[i] + (ms ? "ms" : ""));
							right -= MenuKit.width(tr, pt);
							MenuKit.text(context, tr, pt, right, ry + 1, c);
							right -= 3;
						}
						if (!ping.equals("Number")) {
							int bars = pings[i] < 80 ? 4 : pings[i] < 150 ? 3 : pings[i] < 250 ? 2 : 1;
							for (int b = 0; b < 4; b++) {
								context.fill(right - 9 + b * 2, ry + 8 - (b + 1) * 2, right - 8 + b * 2, ry + 8, b < bars ? c : 0xFF555555);
							}
						}
					}
				}
				case "chat-heads" -> {
					String[][] lines = {{"Notch", "gg everyone"}, {"jeb_", "who wants to duel?"}, {"Alex", "brb 2 min"}};
					int ly = y + h - 14 - lines.length * 11;
					for (String[] line : lines) {
						context.fill(x + 6, ly - 1, x + w - 30, ly + 10, 0x80000000);
						int tx = x + 8;
						if (on) {
							net.veloclient.velo.client.gui.PlayerHeads.draw(context, null, line[0], tx, ly, 8);
							tx += 11;
						}
						MenuKit.text(context, tr, Text.literal("<" + line[0] + "> " + line[1]), tx, ly + 1, 0xFFFFFFFF);
						ly += 11;
					}
				}
				case "block-break-progress" -> {
					int s = Math.min(w, h) / 3;
					double p = (t % 2.4) / 2.4;
					int bx = cx - s / 2;
					int by = cy - s / 2 - 12;
					block(context, "stone", bx, by, s, 0xFFFFFFFF);
					block(context, "destroy_stage_" + Math.min(9, (int) (p * 10)), bx, by, s, 0xFFFFFFFF);
					if (on) {
						String style = choice(module, "style", "Percent");
						boolean redGreen = choice(module, "color", "One Color").equals("Red To Green");
						int c = redGreen ? net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(0xFFFF5555, 0xFF55FF55, (float) p) : color(module, 0xFFFFFFFF);
						int ty = by + s + 10;
						if (style.contains("Bar")) {
							int bw = (int) slider(module, "bar width", 40);
							context.fill(cx - bw / 2, ty, cx + bw / 2, ty + 3, 0xA0000000);
							context.fill(cx - bw / 2, ty, cx - bw / 2 + (int) (bw * p), ty + 3, c);
							ty += 6;
						}
						if (style.startsWith("Ring")) {
							int r = (int) slider(module, "ring", 10);
							for (int k = 0; k < 48; k++) {
								double a = -Math.PI / 2 + k * Math.PI * 2 / 48;
								int col = k / 48.0 <= p ? c : 0x60FFFFFF;
								context.fill((int) (cx + Math.cos(a) * r), (int) (ty + r + Math.sin(a) * r), (int) (cx + Math.cos(a) * r) + 1,
										(int) (ty + r + Math.sin(a) * r) + 1, col);
							}
							ty += r * 2 + 4;
						}
						if (style.contains("Percent")) {
							Text pct = Text.literal(Math.round(p * 100) + "%");
							MenuKit.text(context, tr, pct, cx - MenuKit.width(tr, pct) / 2, ty, c);
						}
					}
				}
				case "glint-colorizer" -> {
					push(context);
					move(context, (float) (cx - 56), (float) (cy - 24));
					scale(context, 3f, 3f);
					context.drawItemWithoutEntity(net.veloclient.velo.client.pvp.PvpCompat.itemStack("minecraft:enchanted_golden_apple"), 0, 0);
					context.drawItemWithoutEntity(net.veloclient.velo.client.pvp.PvpCompat.itemStack("minecraft:enchanted_book"), 21, 0);
					pop(context);
				}
				case "item-physics" -> {
					int s = 24;
					for (int i = 0; i < 5; i++) {
						block(context, "grass_block_side", x + 4 + i * 40, cy + 16, 40, 0xFFFFFFFF);
					}
					String[] items = {"minecraft:diamond", "minecraft:gold_ingot", "minecraft:apple"};
					for (int i = 0; i < items.length; i++) {
						int ix = cx - 50 + i * 40;
						push(context);
						if (on) {
							// lying flat on the ground
							move(context, (float) ix, (float) (cy + 12));
							scale(context, 1.5f, 0.55f);
						} else {
							// vanilla: floating, bobbing and turning
							float turn = (float) Math.cos(t * 2 + i);
							move(context, (float) ix + 12 * (1 - Math.abs(turn)), (float) (cy - 8 + Math.sin(t * 2.5 + i) * 3));
							scale(context, 1.5f * Math.max(0.15f, Math.abs(turn)), 1.5f);
						}
						context.drawItemWithoutEntity(net.veloclient.velo.client.pvp.PvpCompat.itemStack(items[i]), 0, 0);
						pop(context);
					}
				}
				case "chunk-animator" -> {
					boolean above = choice(module, "style", "From Below").equals("From Above");
					double speed = switch (choice(module, "speed", "Normal")) {
						case "Fast" -> 1.6;
						case "Slow" -> 0.6;
						default -> 1.0;
					};
					double cycle = (t * speed) % 3.0;
					int s = 26;
					for (int i = 0; i < 7; i++) {
						double local = Math.max(0, Math.min(1, cycle - i * 0.18));
						double eased = on ? 1 - Math.pow(1 - local, 3) : (local > 0 ? 1 : 0);
						int offset = (int) ((1 - eased) * h * (above ? -0.6 : 0.6));
						for (int j = 0; j < 2; j++) {
							block(context, j == 0 ? "grass_block_side" : "dirt", x + 6 + i * s, cy + j * s + offset, s, 0xFFFFFFFF);
						}
					}
				}
				case "phantom" -> {
					int s = 28;
					for (int j = 0; j < 4; j++) {
						block(context, "stone_bricks", cx - s / 2, y + 8 + j * s, s, on ? 0x70FFFFFF : 0xFFFFFFFF);
					}
					int plateX = cx - 70;
					block(context, "oak_planks", plateX, cy + 30, 28, 0xFFFFFFFF);
					context.fill(plateX + 4, cy + 27, plateX + 24, cy + 30, 0xFFB08850);
					Text note = Text.literal(on ? "walk through · plates stay quiet" : "walls block you · plates click");
					MenuKit.text(context, tr, note, cx - MenuKit.width(tr, note) / 2, y + h - 14, 0xFFEDE6E6);
				}
				case "polyblur" -> {
					int s = 34;
					double px = cx + Math.sin(t * 2.2) * (w / 2.8);
					double vel = Math.cos(t * 2.2);
					int trail = on ? (int) (Math.abs(vel) * 6) : 0;
					for (int k = trail; k >= 0; k--) {
						int alpha = k == 0 ? 0xFF : 0x50 - k * 6;
						block(context, "diamond_block", (int) (px - vel * k * 5) - s / 2, cy - s / 2, s, VeloUi.withAlpha(0xFFFFFFFF, Math.max(16, alpha)));
					}
				}
				case "input-sampler" -> {
					int gx = x + 14;
					int gw = w - 28;
					int gy = cy + 20;
					context.fill(gx, gy, gx + gw, gy + 1, 0x40FFFFFF);
					int prevY = gy;
					for (int k = 0; k < gw; k += 4) {
						double base = on ? 6 : 18;
						double v = base + Math.sin(k * 0.21 + t * 3) * 3 + ((k * 7) % 11 == 0 ? 8 : 0);
						int py = gy - (int) v * 2;
						context.fill(gx + k, Math.min(py, prevY), gx + k + 4, Math.max(py, prevY) + 1, on ? 0xFF55FF77 : 0xFFFFAA44);
						prevY = py;
					}
					Text label = Text.literal(on ? "click -> game: ~3 ms" : "click -> game: ~9 ms (waits for the frame)");
					MenuKit.text(context, tr, label, gx, y + 12, 0xFFEDE6E6);
				}
				case "color-grading" -> {
					int s = 30;
					for (int i = 0; i < 6; i++) {
						block(context, "grass_block_side", x + 4 + i * s, cy, s, 0xFFFFFFFF);
						block(context, "dirt", x + 4 + i * s, cy + s, s, 0xFFFFFFFF);
						if (i % 2 == 0) {
							block(context, "oak_leaves", x + 4 + i * s, cy - s, s, 0xFF59AE30);
						}
					}
					if (on) {
						String preset = choice(module, "preset", "Custom");
						int overlay = switch (preset) {
							case "Vivid" -> 0x2AFF40A0;
							case "Cinematic" -> 0x3020A0B0;
							case "Warm" -> 0x38FF9A30;
							case "Cold" -> 0x383A7BFF;
							case "Black & White" -> 0xA0909090;
							case "Crisp PvP" -> 0x18FFFFFF;
							default -> 0x20FFC080;
						};
						context.fill(x, y, x + w, y + h, overlay);
						if (preset.equals("Cinematic")) {
							context.fill(x, y + h - h / 3, x + w, y + h, 0x30FF8A30);
						}
					}
				}
				case "chunk-border-overlay" -> {
					int s = 14;
					for (int i = 0; i < 14; i++) {
						for (int j = 0; j < 3; j++) {
							block(context, j == 0 ? "grass_block_side" : "dirt", x + 2 + i * s, cy + j * s, s, 0xFFFFFFFF);
						}
					}
					if (on) {
						int c = color(module, 0xFFFFFF55);
						for (int i = 0; i <= 14; i += 4) {
							int lx = x + 2 + i * s;
							context.fill(lx, y + 6, lx + 1, y + h - 6, c);
						}
						for (int ly = y + 10; ly < cy + 42; ly += 14) {
							context.fill(x + 2, ly, x + w - 2, ly + 1, VeloUi.withAlpha(c, 0x60));
						}
					}
				}
				case "world-border-visualizer" -> {
					int c = color(module, 0xFF2080FF);
					int v = (int) ((t * 40) % 256);
					if (on) {
						tex(context, "misc/forcefield", x + w / 2 - 2, y, w / 2, h, 256, 256, v, VeloUi.withAlpha(c, 0xC0));
					}
					Text label = Text.literal(on ? "border: 24 blocks away" : "border hidden until you touch it");
					MenuKit.text(context, tr, label, x + 8, y + h - 14, 0xFFEDE6E6);
				}
				case "light-level-overlay" -> {
					int s = 30;
					int[][] levels = {{0, 3, 7, 11}, {2, 6, 9, 14}};
					for (int i = 0; i < 4; i++) {
						for (int j = 0; j < 2; j++) {
							int bx = cx - 2 * s + i * s;
							int by = cy - s + j * s + 10;
							block(context, "grass_block_top", bx, by, s, 0xFF79C05A);
							if (on) {
								int lv = levels[j][i];
								Text n = Text.literal(String.valueOf(lv));
								int c = lv == 0 ? 0xFFFF5555 : lv < 8 ? 0xFFFFDD55 : 0xFF55FF55;
								MenuKit.text(context, tr, n, bx + (s - MenuKit.width(tr, n)) / 2, by + s / 2 - 4, c);
							}
						}
					}
				}
				case "old-animations" -> {
					// first person: your sword swings (and blocks, 1.7 style) the way the settings say
					double swing = Math.max(2, slider(module, "swing duration", 6)) / 20.0;
					String blockMode = choice(module, "blocking", "Off");
					double cycle = t % (swing + 1.6);
					boolean blocking = on && !blockMode.equals("Off") && (int) (t / (swing + 1.6)) % 2 == 1;
					double swingP = cycle < swing ? cycle / swing : -1;
					push(context);
					float ix = x + w - 70;
					float iy = y + h - 64;
					if (blocking) {
						move(context, ix - 20, iy - 12);
						rotate(context, (float) Math.toRadians(-60));
					} else if (swingP >= 0) {
						double a = Math.sin(swingP * Math.PI);
						move(context, (float) (ix - a * 30), (float) (iy - a * 18));
						rotate(context, (float) Math.toRadians(-a * 50));
					} else {
						move(context, ix, iy);
					}
					scale(context, 4f, 4f);
					context.drawItemWithoutEntity(net.veloclient.velo.client.pvp.PvpCompat.itemStack("minecraft:diamond_sword"), 0, 0);
					pop(context);
					Text label = Text.literal(blocking ? "blocking" : swingP >= 0 ? "hit" : "");
					MenuKit.text(context, tr, label, x + 8, y + 8, 0xFFEDE6E6);
				}
				case "low-fire" -> {
					// first person while burning: the real fire, lowered (and see-through) the way you set it
					double lower = on ? slider(module, "lower", 0.3) : 0;
					double opacity = on ? slider(module, "opacity", 1.0) : 1.0;
					int frame = (int) (t * 20) % 32;
					int fw = w / 2 + 10;
					int fh = (int) (h * 0.9);
					int top = y + h - fh + (int) (lower * fh);
					int alpha = (int) (255 * Math.max(0.1, Math.min(1, opacity)));
					tex(context, "block/fire_1", x - 6, top, fw, fh, 16, 512, frame * 16, VeloUi.withAlpha(0xFFFFFFFF, alpha));
					tex(context, "block/fire_1", x + w - fw + 6, top, fw, fh, 16, 512, ((frame + 9) % 32) * 16, VeloUi.withAlpha(0xFFFFFFFF, alpha));
				}
				default -> {
				}
			}
		} finally {
			context.disableScissor();
		}
	}

	/** Over the figure: what appears on or around your player. */
	public static void drawOver(DrawContext context, Module module, int x, int y, int w, int h) {
		var tr = MinecraftClient.getInstance().textRenderer;
		double t = time();
		boolean on = module.isEnabled();
		int cx = x + w / 2;
		int head = y + (int) (h * 0.16);
		int body = y + (int) (h * 0.48);
		context.enableScissor(x, y, x + w, y + h);
		try {
			switch (module.id()) {
				case "damage-numbers" -> {
					int c = color(module, 0xFFFF5555);
					String[] hits = {"-2.5", "-4.0", "-1.5"};
					for (int i = 0; i < hits.length; i++) {
						double p = (t + i * 0.45) % 1.35 / 1.35;
						int nx = cx + (i - 1) * 22;
						int ny = head - (int) (p * 26);
						int alpha = (int) (255 * (1 - p));
						if (alpha > 8) {
							Text label = Text.literal(hits[i]);
							MenuKit.text(context, tr, label, nx - MenuKit.width(tr, label) / 2, ny, VeloUi.withAlpha(c, alpha));
						}
					}
				}
				case "health-indicator" -> {
					for (int i = 0; i < 10; i++) {
						sprite(context, "hud/heart/container", cx - 40 + i * 8, head - 12, 9, 9);
						if (i < 7) {
							sprite(context, "hud/heart/full", cx - 40 + i * 8, head - 12, 9, 9);
						}
					}
				}
				case "enemy-effects" -> {
					// as the module draws it: effect icon, level, time left
					String[][] fx = {{"speed", " II", " 0:45"}, {"strength", "", " 1:30"}, {"regeneration", " II", " 8s"}};
					int max = (int) slider(module, "max icons", 3);
					boolean lvl = toggle(module, "level", true);
					boolean time = toggle(module, "time left", true);
					int total = 0;
					int shown = Math.min(max, fx.length);
					for (int i = 0; i < shown; i++) {
						total += 10 + (lvl ? MenuKit.width(tr, Text.literal(fx[i][1])) : 0) + (time ? MenuKit.width(tr, Text.literal(fx[i][2])) : 0) + 8;
					}
					int ex = cx - total / 2;
					int ey = head - 14;
					for (int i = 0; i < shown; i++) {
						tex(context, "mob_effect/" + fx[i][0], ex, ey, 9, 9, 18, 18, 0, 0xFFFFFFFF);
						ex += 10;
						if (lvl && !fx[i][1].isEmpty()) {
							MenuKit.text(context, tr, Text.literal(fx[i][1]), ex, ey + 1, 0xFFFFE08A);
							ex += MenuKit.width(tr, Text.literal(fx[i][1]));
						}
						if (time) {
							MenuKit.text(context, tr, Text.literal(fx[i][2]), ex, ey + 1, 0xFFD9F2FF);
							ex += MenuKit.width(tr, Text.literal(fx[i][2]));
						}
						ex += 8;
					}
				}
				case "enemy-hunger" -> {
					for (int i = 0; i < 10; i++) {
						sprite(context, "hud/food_empty", cx + 31 - i * 8, head - 12, 9, 9);
						if (i < 6) {
							sprite(context, "hud/food_full", cx + 31 - i * 8, head - 12, 9, 9);
						}
					}
				}
				case "own-name-tag", "nick-hider" -> {
					String name = module.id().equals("nick-hider") && on ? text(module, "nick", "Player")
							: net.veloclient.velo.client.gui.title.MenuInfo.username();
					Text label = Text.literal(name == null ? "Player" : name);
					int lw = MenuKit.width(tr, label) + 6;
					context.fill(cx - lw / 2, head - 14, cx + lw / 2, head - 3, 0x60000000);
					MenuKit.text(context, tr, label, cx - lw / 2 + 3, head - 12, 0xFFFFFFFF);
				}
				case "hit-particles" -> {
					double p = t % 1.2 / 1.2;
					boolean crit = (int) (t / 1.2) % 2 == 1;
					int dust = color(module, 0xFFFF3030);
					String style = crit ? choice(module, "crit style", "Crit") : choice(module, "style", "Crit");
					if (!on) {
						style = crit ? "Crit" : "Off";
					}
					burst(context, styleSprite(style, dust), cx, body, p, 14, 1.0, 46);
					Text label = Text.literal(crit ? "critical hit" : "normal hit");
					MenuKit.text(context, tr, label, x + 8, y + 8, 0xFFEDE6E6);
				}
				case "particle-size" -> {
					double size = on ? slider(module, "size", 1.0) : 1.0;
					double p = t % 1.4 / 1.4;
					burst(context, new String[] {"flame", "FFFFFFFF"}, cx - 30, body, p, 10, size, 40);
					burst(context, new String[] {"generic_3", "FFE0E0E0"}, cx + 30, body - 20, (p + 0.5) % 1, 10, size, 40);
				}
				case "particle-limiter" -> {
					double p = t % 1.4 / 1.4;
					burst(context, new String[] {"flame", "FFFFFFFF"}, cx, body, p, on ? 8 : 40, 1.0, 60);
					Text label = Text.literal(on ? "busy moments: capped" : "every particle drawn");
					MenuKit.text(context, tr, label, x + 8, y + 8, 0xFFEDE6E6);
				}
				case "hitbox-visualizer" -> {
					if (on) {
						int c = color(module, 0xFFFFFFFF);
						int bw = w / 4;
						int top = head - 8;
						int bottom = y + h - 10;
						context.fill(cx - bw / 2, top, cx + bw / 2, top + 1, c);
						context.fill(cx - bw / 2, bottom, cx + bw / 2, bottom + 1, c);
						context.fill(cx - bw / 2, top, cx - bw / 2 + 1, bottom, c);
						context.fill(cx + bw / 2, top, cx + bw / 2 + 1, bottom + 1, c);
						context.fill(cx - bw / 2, head + 4, cx + bw / 2, head + 5, 0xFFFF3333);
						context.fill(cx, head + 4, cx + 1, head - 30, 0xFF3366FF);
					}
				}
				case "low-health-warning" -> {
					if (on) {
						int c = color(module, 0xFFFF0000);
						int a = (int) (90 + 70 * Math.sin(t * 5));
						for (int i = 0; i < 14; i++) {
							int edge = VeloUi.withAlpha(c, Math.max(0, a - i * 7));
							context.fill(x + i, y, x + i + 1, y + h, edge);
							context.fill(x + w - i - 1, y, x + w - i, y + h, edge);
							context.fill(x, y + i, x + w, y + i + 1, edge);
							context.fill(x, y + h - i - 1, x + w, y + h - i, edge);
						}
					}
				}
				case "low-fire" -> {
					double height = on ? Math.max(0.15, Math.min(1, slider(module, "height", 0.4) > 1 ? slider(module, "height", 40) / 100.0
							: slider(module, "height", 0.4))) : 1.0;
					int fh = (int) (h * 0.55 * height);
					for (int i = 0; i < 9; i++) {
						int fw = w / 9;
						int fx = x + i * fw;
						int flick = (int) (Math.sin(t * 9 + i * 1.7) * fh * 0.15);
						int top = y + h - fh - flick;
						context.fill(fx, top, fx + fw, y + h, VeloUi.withAlpha(0xFFFF7A1A, 0xB0));
						context.fill(fx + 2, top + fh / 3, fx + fw - 2, y + h, VeloUi.withAlpha(0xFFFFD34A, 0xC0));
					}
				}
				case "kill-effects" -> {
					String id = net.veloclient.velo.client.cosmetics.killfx.KillFxManager.equippedId();
					if (id != null && net.veloclient.velo.client.cosmetics.killfx.KillFxManager.IDS.contains(id)) {
						var loop = KILL_LOOPS.computeIfAbsent(id, k -> new net.veloclient.velo.client.gui.widget.KillFxGuiRenderer.Loop(
								net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.kind(k)));
						net.veloclient.velo.client.gui.widget.KillFxGuiRenderer.draw(context, loop,
								net.veloclient.velo.client.cosmetics.killfx.KillFxManager.settings(id), cx, y + h * 0.75f, Math.min(w / 5.2f, h / 5.4f),
								0.45 + t / 9.0);
					} else {
						Text none = Text.literal("Pick a kill effect in the Locker");
						MenuKit.text(context, tr, none, cx - MenuKit.width(tr, none) / 2, y + h / 2, 0xFFB3A8A9);
					}
				}
				default -> {
				}
			}
		} finally {
			context.disableScissor();
		}
	}


	// ---- pose helpers (1.21.1 has a 3D MatrixStack, later versions a 2D one) ----

	public static void push(DrawContext context) {
		//? if <1.21.2 {
		/*context.getMatrices().push();
		*///?} else {
		context.getMatrices().pushMatrix();
		//?}
	}

	public static void pop(DrawContext context) {
		//? if <1.21.2 {
		/*context.getMatrices().pop();
		*///?} else {
		context.getMatrices().popMatrix();
		//?}
	}

	public static void move(DrawContext context, float x, float y) {
		//? if <1.21.2 {
		/*context.getMatrices().translate(x, y, 0);
		*///?} else {
		context.getMatrices().translate(x, y);
		//?}
	}

	public static void scale(DrawContext context, float x, float y) {
		//? if <1.21.2 {
		/*context.getMatrices().scale(x, y, 1f);
		*///?} else {
		context.getMatrices().scale(x, y);
		//?}
	}

	private static void rotate(DrawContext context, float radians) {
		//? if <1.21.2 {
		/*context.getMatrices().multiply(new org.joml.Quaternionf().rotateZ(radians));
		*///?} else {
		context.getMatrices().mul(new org.joml.Matrix3x2f().rotation(radians));
		//?}
	}

	private static final java.util.Map<String, net.veloclient.velo.client.gui.widget.KillFxGuiRenderer.Loop> KILL_LOOPS = new java.util.HashMap<>();
}
